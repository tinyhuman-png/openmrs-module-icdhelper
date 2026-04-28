/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.icdhelper.api.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OrtException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.ModuleException;
import org.openmrs.module.icdhelper.ICDHelperConfig;
import org.openmrs.module.icdhelper.api.ICDHelperService;
import org.openmrs.module.icdhelper.api.ICDSearchResult;
import org.openmrs.module.icdhelper.api.predictors.HierarchicalPredictor;
import org.openmrs.module.icdhelper.api.predictors.SapBertPredictor;

/**
 * Default implementation of {@link ICDHelperService}.
 * <p>
 * Uses embedded ONNX machine learning models (SapBERT and a custom hierarchical BERT) to obtain
 * ICD-10 code suggestions for a given clinical note, resolves those codes against the local OpenMRS
 * concept dictionary (CIEL/ICD-10-WHO source), and persists selected diagnoses as observations on
 * patient encounters.
 * </p>
 * <p>
 * The directory containing the ONNX models and vocabularies is read from the global property
 * {@code icdhelper.modelDirectory} at runtime.
 * </p>
 */
public class ICDHelperServiceImpl extends BaseOpenmrsService implements ICDHelperService {
	
	private Log log = LogFactory.getLog(this.getClass());
	
	SapBertPredictor sapBertPredictor;
	
	HierarchicalPredictor hierarchicalPredictor;
	
	private volatile boolean modelsReady = false;
	
	private volatile String modelLoadError = null;
	
	// Setters for testing
	public void setSapBertPredictor(SapBertPredictor predictor) {
		this.sapBertPredictor = predictor;
	}
	
	public void setHierarchicalPredictor(HierarchicalPredictor predictor) {
		this.hierarchicalPredictor = predictor;
	}
	
	public void setModelsReady(boolean bool) {
		this.modelsReady = bool;
	}
	
	public void setModelLoadError(String message) {
		this.modelLoadError = message;
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void initializeModels(final String modelDir) {
		Thread loaderThread = new Thread(new Runnable() {
			
			@Override
			public void run() {
				try {
					sapBertPredictor = new SapBertPredictor();
					sapBertPredictor.initialize(modelDir);
				}
				catch (Exception e) {
					log.error("Failed to initialize SapBertPredictor", e);
					modelLoadError = "SapBERT failed to load: " + e.getMessage();
				}
				
				try {
					hierarchicalPredictor = new HierarchicalPredictor();
					hierarchicalPredictor.initialize(modelDir);
				}
				catch (Exception e) {
					log.error("Failed to initialize HierarchicalPredictor", e);
					modelLoadError = (modelLoadError != null ? modelLoadError + "; " : "")
					        + "HierarchicalPredictor failed to load: " + e.getMessage();
				}
				
				modelsReady = true;
				log.info("ICD Helper models finished loading.");
			}
		}, "icdhelper-model-loader");
		
		loaderThread.setDaemon(true); // don't block JVM shutdown
		loaderThread.start();
		log.info("ICD Helper model loading started in background.");
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void shutdownModels() {
		if (sapBertPredictor != null) {
			try {
				sapBertPredictor.close();
			}
			catch (Exception e) {
				log.error("Failed to close SapBertPredictor", e);
			}
		}
		
		if (hierarchicalPredictor != null) {
			try {
				hierarchicalPredictor.close();
			}
			catch (Exception e) {
				log.error("Failed to close HierarchicalPredictor", e);
			}
		}
	}
	
	/**
	 * {@inheritDoc}
	 * <p>
	 * Implementation steps:
	 * <ol>
	 * <li>Call the {@code predict()} method of the predictor corresponding to the requested mode,
	 * with {@code k=20}</li>
	 * <li>For each returned prediction, formats the raw code (inserting a dot if needed to match
	 * the CIEL convention, e.g. {@code "A001"} -> {@code "A00.1"}), then looks it up via
	 * {@link #getConceptByICD10Code}.</li>
	 * <li>If no concept is found, creates a {@code NO MAPPING} result so the code is still surfaced
	 * to the user (and can be saved as a non-coded diagnosis).</li>
	 * <li>Malformed individual predictions are logged and skipped rather than failing the whole
	 * request.</li>
	 * </ol>
	 * </p>
	 */
	@Override
	public List<ICDSearchResult> getPredictionsFromNote(String clinicalNote, String mode) throws IllegalArgumentException,
	        ModuleException, IllegalStateException {
		if (!modelsReady) {
			throw new IllegalStateException("Models are still loading, please try again in a moment.");
		}
		if (modelLoadError != null) {
			throw new IllegalStateException("Models failed to load: " + modelLoadError);
		}
		
		if (mode == null) {
			throw new IllegalArgumentException("Mode cannot be null");
		}
		if (clinicalNote == null) {
			throw new IllegalArgumentException("ClinicalNote cannot be null");
		}
		if (!mode.equals("selection") && !mode.equals("full")) {
			throw new IllegalArgumentException("Mode must be either 'selection' or 'full'");
		}
		
		List<ICDSearchResult> finalResults = new ArrayList<ICDSearchResult>();
		if (clinicalNote.trim().isEmpty()) {
			log.warn("ClinicalNote is empty, no prediction.");
			return finalResults;
		}
		
		List<Map<String, Object>> response;
		try {
			// Length check: if mode is selection but the selected text is too long, fallback to hierarchical model.
			if (mode.equals("selection") && clinicalNote.length() < 1000) {
				if (sapBertPredictor == null) {
					throw new IllegalStateException("SapBertPredictor is not initialized. Call initialize() first.");
				}
				response = sapBertPredictor.predict(clinicalNote, 20);
			} else {
				if (hierarchicalPredictor == null) {
					throw new IllegalStateException("HierarchicalPredictor is not initialized. Call initialize() first.");
				}
				response = hierarchicalPredictor.predict(clinicalNote, 20);
			}
		}
		catch (OrtException e) {
			throw new ModuleException("ICD prediction inference failed: " + e.getMessage(), e);
		}
		catch (IllegalStateException e) {
			throw new IllegalStateException("ICD prediction inference failed: " + e.getMessage(), e);
		} //No need to catch IllegalArgumentException of the tokenizer because we checked before that clinicalNote is not null
		
		for (Map<String, Object> prediction : response) {
			try {
				Object codeObj = prediction.get("code");
				if (!(codeObj instanceof String)) {
					log.warn("Skipping prediction with missing or non-string code: " + prediction);
					continue;
				}
				String code = (String) codeObj;
				String formattedCode = code;
				String description = prediction.get("description") instanceof String ? (String) prediction
				        .get("description") : null;
				Double confidence = prediction.get("score") instanceof Number ? ((Number) prediction.get("score"))
				        .doubleValue() : null;
				
				// CIEL stores codes with a dot after the 3rd character (e.g. "A00.1").
				// The prediction service may return codes without the dot (e.g. "A001")
				if (code.length() > 3 && !code.contains(".")) {
					formattedCode = code.substring(0, 3) + "." + code.substring(3);
				}
				
				ICDSearchResult res = this.getConceptByICD10Code(formattedCode);
				if (res == null) {
					// Code is valid per the AI prediction but absent from the local dictionary.
					// Surface it anyway so the user can still save it as a non-coded diagnosis
					res = new ICDSearchResult(null, "NO MAPPING", formattedCode);
				}
				res.setDescription(description);
				res.setConfidence(confidence);
				finalResults.add(res);
			}
			catch (Exception e) {
				log.warn("Skip malformed entry: " + prediction, e);
			}
		}
		return finalResults;
	}
	
	/**
	 * {@inheritDoc}
	 * <p>
	 * The encounter is resolved from the visit note observation identified by {@code visitNoteUuid}
	 * . Items that already exist as observations on the encounter are silently skipped. Per-item
	 * failures are collected and returned in the status message rather than aborting the entire
	 * save.
	 * </p>
	 */
	@Override
	public String saveSelectionToEncounter(Patient patient, String visitNoteUuid, List<String> selection)
	        throws IllegalArgumentException {
		if (patient == null) {
			throw new IllegalArgumentException("Patient must not be null.");
		}
		if (visitNoteUuid == null) {
			throw new IllegalArgumentException("Visit note uuid must not be null.");
		}
		if (selection == null) {
			throw new IllegalArgumentException("Selection must not be null.");
		}
		if (selection.isEmpty()) {
			return "No selected diagnoses; nothing to save.";
		}
		
		Obs selectedObs = Context.getObsService().getObsByUuid(visitNoteUuid);
		if (selectedObs == null) {
			throw new IllegalArgumentException("Given visit note observation not found in db.");
		}
		if (!selectedObs.getPersonId().equals(patient.getId())) {
			throw new IllegalArgumentException("Given visit note observation does not belong to the given patient.");
		}
		if (selectedObs.getEncounter() == null) {
			return "Could not save the observations when Visit Note has no associated encounter.";
		}
		
		Encounter encounter = selectedObs.getEncounter();
		
		List<String> status = new ArrayList<String>();
		for (String item : selection) {
			Obs savedObs = null;
			if (item.startsWith("RAW:")) {
				// It is a non-coded item containing ICD code and eventually description.
				// Save as free text under concept 161602 ("Diagnosis, Non-coded")
				String rawText = item.replace("RAW:", "");
				savedObs = this.saveICD10Diagnosis(patient, encounter, rawText);
			} else if (item.contains("|")) {
				// It is a coded item, with format <conceptUuid>|<icdCode>
				String conceptUuid = item.substring(0, item.indexOf("|"));
				String code = item.substring(item.indexOf("|") + 1);
				
				Concept c = Context.getConceptService().getConceptByUuid(conceptUuid);
				if (c != null) {
					// The mapping type is re-derived here to set the correct obs comment
					String mapping = this.getMappingInfo(c, code);
					if (!mapping.equals("NO MAPPING")) {
						ICDSearchResult result = new ICDSearchResult(c, mapping, code);
						savedObs = this.saveICD10Diagnosis(patient, encounter, result);
					} else {
						log.warn("There is no mapping between " + conceptUuid + " and " + code);
						status.add("There is no mapping between " + conceptUuid + " and " + code);
						continue;
					}
				} else {
					log.warn("There is no Concept corresponding to concept uuid " + conceptUuid);
					status.add("There is no Concept corresponding to concept uuid " + conceptUuid);
					continue;
				}
			} else {
				log.warn("'" + item + "' is not a valid format for selection item");
				status.add("'" + item + "' is not a valid format for selection item");
				continue;
			}
			if (savedObs == null) {
				log.warn("'" + item + "' could not be saved in database");
				status.add("'" + item + "' could not be saved in database");
			}
		}
		if (status.isEmpty()) {
			return "Success";
		} else {
			return "There was an error saving some diagnose.s: " + status;
		}
	}
	
	/**
	 * {@inheritDoc}
	 */
	public ICDSearchResult getConceptByICD10Code(String code) throws IllegalArgumentException {
		if (code == null) {
			throw new IllegalArgumentException("Code must not be null.");
		}
		
		ConceptService cs = Context.getConceptService();
		final String originalCode = code.trim().toUpperCase();
		
		// first pass, try to find an exact match in the ICD-10-CM dictionary if it exists
		List<Concept> cmConcepts = cs.getConceptsByMapping(originalCode, "ICD-10-CM", false);
		ICDSearchResult cmResult = resolveBestMatch(cmConcepts, originalCode);
		if (cmResult != null) {
			return cmResult;
		}
		
		//second pass, truncation loop against the standard ICD-10-WHO dictionary
		String currentCode = originalCode;
		boolean isTruncated = false;
		
		while (currentCode.length() >= 3) {
			List<Concept> whoConcepts = cs.getConceptsByMapping(currentCode, "ICD-10-WHO", false);
			ICDSearchResult whoResult = resolveBestMatch(whoConcepts, currentCode);
			
			if (whoResult != null) {
				if (isTruncated) {
					if (whoResult.getMappingType().equals("SAME-AS")) {
						// The found WHO concept with SAME-AS mapping to truncated code is inherently
						// broader than the specific CM code
						whoResult.setIcdCode(originalCode);
						whoResult.setMappingType("BROADER-THAN");
						return whoResult;
					}
				} else {
					return whoResult;
				}
			}
			
			// nothing was found: truncate and try again
			isTruncated = true;
			if (currentCode.contains(".") && currentCode.length() == 5) {
				currentCode = currentCode.substring(0, 3); // e.g. "A00.1" -> "A00"
			} else {
				currentCode = currentCode.substring(0, currentCode.length() - 1);
			}
		}
		return null;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public Obs saveICD10Diagnosis(Patient patient, Encounter encounter, ICDSearchResult diagnosis)
	        throws IllegalArgumentException, IllegalStateException {
		if (patient == null) {
			throw new IllegalArgumentException("Patient must not be null.");
		}
		if (encounter == null) {
			throw new IllegalArgumentException("Encounter must not be null.");
		}
		if (diagnosis == null) {
			throw new IllegalArgumentException("Diagnosis must not be null.");
		}
		if (diagnosis.getConcept() == null) {
			// No concept found in the ICDSearchResult, fall back to saving as non-coded free text.
			log.warn("ICDSearchResult Concept is null, save the code + description in valueText instead");
			return saveICD10Diagnosis(patient, encounter, diagnosis.getIcdCode() + ": " + diagnosis.getDescription());
		}
		
		Obs obs = new Obs();
		obs.setPerson(patient);
		obs.setEncounter(encounter);
		obs.setObsDatetime(new Date());
		Concept questionConcept = Context.getConceptService().getConceptByUuid(ICDHelperConfig.DIAGNOSIS_CODED_CONCEPT_UUID);
		if (questionConcept == null) {
			throw new IllegalStateException("Required concept '1284' (Diagnosis Coded) not found. "
			        + "Please install the CIEL concept dictionary. " + "See: https://www.openconceptlab.org/");
		}
		
		// Check for an existing non-voided obs with the same coded answer in this encounter
		List<Obs> existing = Context.getObsService().getObservationsByPersonAndConcept(patient, questionConcept);
		for (Obs existingObs : existing) {
			if (!existingObs.getVoided() && existingObs.getValueCoded() != null
			        && existingObs.getValueCoded().equals(diagnosis.getConcept()) && existingObs.getEncounter() != null
			        && existingObs.getEncounter().getUuid().equals(encounter.getUuid())) {
				return existingObs; // Skip saving if it already exists
			}
		}
		
		//		for (Obs existingObs : encounter.getObs()) {
		//			if (!existingObs.getVoided() && existingObs.getConcept().equals(questionConcept)
		//			        && existingObs.getValueCoded() != null && existingObs.getValueCoded().equals(diagnosis.getConcept())) {
		//				return existingObs;
		//			}
		//		}
		
		obs.setConcept(questionConcept); // "Diagnosis Coded"
		obs.setValueCoded(diagnosis.getConcept());
		
		// When the mapping is not exact, record the original ICD-10 code in the comment
		// so that the clinical intent is preserved and auditable.
		if (diagnosis.getMappingType().equals("BROADER-THAN")) {
			obs.setComment("Originally matched ICD-10 code " + diagnosis.getIcdCode()
			        + ", which is narrower than this Concept.");
		} else if (diagnosis.getMappingType().equals("NARROWER-THAN")) {
			obs.setComment("Originally matched ICD-10 code " + diagnosis.getIcdCode()
			        + ", which is broader than this Concept.");
		}
		
		return Context.getObsService().saveObs(obs, "Saved via ICD Helper");
	}
	
	/**
	 * {@inheritDoc}
	 */
	public Obs saveICD10Diagnosis(Patient patient, Encounter encounter, String nonCodedText)
	        throws IllegalArgumentException, IllegalStateException {
		if (patient == null) {
			throw new IllegalArgumentException("Patient must not be null.");
		}
		if (encounter == null) {
			throw new IllegalArgumentException("Encounter must not be null.");
		}
		if (nonCodedText == null) {
			throw new IllegalArgumentException("Diagnosis must not be null.");
		}
		if (nonCodedText.isEmpty()) {
			log.error("Diagnosis must not be empty.");
			return null;
		}
		
		Obs obs = new Obs();
		obs.setPerson(patient);
		obs.setEncounter(encounter);
		obs.setObsDatetime(new Date());
		Concept questionConcept = Context.getConceptService().getConceptByUuid(
		    ICDHelperConfig.DIAGNOSIS_NON_CODED_CONCEPT_UUID);
		if (questionConcept == null) {
			throw new IllegalStateException("Required concept '161602' (Diagnosis Non-Coded) not found. "
			        + "Please install the CIEL concept dictionary. " + "See: https://www.openconceptlab.org/");
		}
		
		// Check for an existing non-voided obs with the same free-text value in this encounter
		List<Obs> existing = Context.getObsService().getObservationsByPersonAndConcept(patient, questionConcept);
		for (Obs existingObs : existing) {
			if (!existingObs.getVoided() && existingObs.getValueText() != null
			        && existingObs.getValueText().equals(nonCodedText) && existingObs.getEncounter() != null
			        && existingObs.getEncounter().getUuid().equals(encounter.getUuid())) {
				return existingObs; // Skip saving if it already exists
			}
		}
		
		//		for (Obs existingObs : encounter.getObs()) {
		//			if (!existingObs.getVoided() && existingObs.getConcept().equals(questionConcept)
		//			        && existingObs.getValueText() != null && existingObs.getValueText().equals(nonCodedText)) {
		//				return existingObs;
		//			}
		//		}
		
		obs.setConcept(questionConcept); // "Diagnosis, Non-coded"
		obs.setValueText(nonCodedText);
		return Context.getObsService().saveObs(obs, "Saved via ICD Helper");
	}
	
	/**
	 * {@inheritDoc}
	 */
	public List<Obs> getVisitNotes(Patient patient, Visit visit) throws IllegalArgumentException {
		if (patient == null) {
			throw new IllegalArgumentException("Patient must not be null.");
		}
		if (visit == null) {
			throw new IllegalArgumentException("Visit must not be null.");
		}
		
		// CIEL concept 162169 = "Visit Note"
		Concept noteConcept = Context.getConceptService().getConceptByUuid(ICDHelperConfig.VISIT_NOTE_CONCEPT_UUID);
		List<Obs> allNotes = Context.getObsService().getObservationsByPersonAndConcept(patient, noteConcept);
		
		List<Obs> filteredNotes = new ArrayList<Obs>();
		
		if (allNotes != null) {
			for (Obs obs : allNotes) {
				if (obs.getEncounter() != null && visit.equals(obs.getEncounter().getVisit())) {
					filteredNotes.add(obs);
				}
			}
		}
		
		// Sort the filtered note, so they appear in reverse chronological order (most recent first)
		Collections.sort(filteredNotes, new Comparator<Obs>() {
			
			@Override
			public int compare(Obs o1, Obs o2) {
				return -(o1.getObsDatetime().compareTo(o2.getObsDatetime()));
			}
		});
		return filteredNotes;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public String getMappingInfo(Concept concept, String icdCode) throws IllegalArgumentException {
		if (concept == null) {
			throw new IllegalArgumentException("Concept must not be null.");
		}
		if (icdCode == null) {
			throw new IllegalArgumentException("IcdCode must not be null.");
		}
		
		if (concept.isRetired() || icdCode.isEmpty()) {
			return "NO MAPPING";
		}
		
		final String normalizedInput = icdCode.trim().toUpperCase();
		
		for (ConceptMap map : concept.getConceptMappings()) {
			ConceptReferenceTerm term = map.getConceptReferenceTerm();
			if (term.isRetired()) {
				continue;
			}
			String sourceName = term.getConceptSource().getName();
			String termCode = term.getCode().trim().toUpperCase();
			
			// Exact match in either WHO or CM source
			if ((sourceName.equalsIgnoreCase("ICD-10-WHO") || sourceName.equalsIgnoreCase("ICD-10-CM"))
			        && termCode.equalsIgnoreCase(normalizedInput)) {
				return map.getConceptMapType().getName();
			}
			
			// The stored code is a CM code that was truncated to find a WHO parent
			// -> the concept is a broader match.
			if (sourceName.equalsIgnoreCase("ICD-10-WHO") && normalizedInput.startsWith(termCode)
			        && normalizedInput.length() > termCode.length()) {
				return "BROADER-THAN";
			}
		}
		return "NO MAPPING";
	}
	
	/**
	 * From a list of candidate concepts, selects the best match for the given ICD code using the
	 * priority order: SAME-AS > BROADER-THAN > NARROWER-THAN. Retired concepts are skipped. Returns
	 * {@code null} if no usable concept is found.
	 */
	private ICDSearchResult resolveBestMatch(List<Concept> candidates, String icdCode) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		
		// Sort by concept ID for deterministic tie-breaking (lowest ID wins)
		Collections.sort(candidates, new Comparator<Concept>() {
			
			@Override
			public int compare(Concept o1, Concept o2) {
				return o1.getConceptId().compareTo(o2.getConceptId());
			}
		});
		
		Concept fallbackNarrower = null;
		Concept fallbackBroader = null;
		for (Concept concept : candidates) {
			if (concept.isRetired()) {
				continue;
			}
			String type = getMappingInfo(concept, icdCode);
			if (type.equalsIgnoreCase("SAME-AS")) {
				return new ICDSearchResult(concept, "SAME-AS", icdCode);
			} else if (type.equalsIgnoreCase("BROADER-THAN") && fallbackBroader == null) {
				fallbackBroader = concept;
			} else if (type.equalsIgnoreCase("NARROWER-THAN") && fallbackNarrower == null) {
				fallbackNarrower = concept;
			}
		}
		
		// Reminder: "The concept is [mapping] the ICD code"
		// Having a broader concept is safer than a narrower one from a data integrity and audit POV
		if (fallbackBroader != null) {
			return new ICDSearchResult(fallbackBroader, "BROADER-THAN", icdCode);
		}
		if (fallbackNarrower != null) {
			return new ICDSearchResult(fallbackNarrower, "NARROWER-THAN", icdCode);
		}
		
		return null;
	}
}
