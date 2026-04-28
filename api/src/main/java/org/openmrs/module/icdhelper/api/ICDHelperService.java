/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.icdhelper.api;

import java.util.List;

import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.APIException;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.ModuleException;
import org.openmrs.module.icdhelper.ICDHelperConfig;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.transaction.annotation.Transactional;

/**
 * The main service of ICDHelper module, which is exposed for other modules.
 * <p>
 * Provides AI-assisted ICD-10-CM code suggestions from clinical notes, concept lookup by ICD-10-CM
 * code, and saving selected diagnoses as observations to a patient encounter.
 * <p>
 * See moduleApplicationContext.xml on how it is wired up.
 * <p>
 * <strong>Note on visibility:</strong> {@link #getConceptByICD10Code},{@link #getMappingInfo}, and
 * the two {@link #saveICD10Diagnosis} overloads are internal helpers exposed on this interface
 * solely to allow unit testing without package-private access. They are not intended to be called
 * directly by other modules or UI controllers.
 * </p>
 */
public interface ICDHelperService extends OpenmrsService {
	
	/**
	 * Instanciates both SapBert and Hierarchical predictors independently of one another. Logs
	 * error if there was a failure in the process.
	 * 
	 * @param modelDir path to the directory containing all model files
	 */
	void initializeModels(String modelDir);
	
	/**
	 * Calls {@code close()} on both SapBert and Hierarchical predictors, given they were
	 * instanciated.
	 */
	void shutdownModels();
	
	/**
	 * Analyzes a clinical note using the embedded AI predictors (SapBERT or Hierarchical model)
	 * based on the specified analysis mode, and returns a list of suggested ICD-10-CM codes, each
	 * resolved against the local OpenMRS concept dictionary.
	 * <p>
	 * Each returned {@link ICDSearchResult} contains the matched {@link org.openmrs.Concept} if it
	 * exists, the ICD-10-CM code, the ICD-10-CM description of the code if it was provided by the
	 * external API and the mapping type ({@code SAME-AS}, {@code NARROWER-THAN},
	 * {@code BROADER-THAN}, or {@code NO MAPPING} if absent from the dictionary).
	 * <p>
	 * 
	 * @param clinicalNote the free-text clinical note to analyze; must not be null
	 * @param mode the analysis mode passed to the prediction service (e.g. {@code "selection"} for
	 *            short passages, {@code "full"} for complete notes); must not be null
	 * @return a list of {@link ICDSearchResult}; never null, may be empty
	 * @throws IllegalArgumentException if {@code clinicalNote} or {@code mode} is null, or if mode
	 *             is not either {@code "selection"} or {@code "full"}
	 * @throws APIException if the prediction service cannot be reached, is misconfigured, or
	 *             returns malformed data
	 */
	@Authorized(PrivilegeConstants.GET_OBS)
	@Transactional(readOnly = true)
	List<ICDSearchResult> getPredictionsFromNote(String clinicalNote, String mode) throws IllegalArgumentException,
	        ModuleException, IllegalStateException;
	
	/**
	 * Saves a list of selected diagnoses as observations on the encounter associated with the given
	 * visit note.
	 * <p>
	 * Each item in {@code selection} must be in one of two formats:
	 * <ul>
	 * <li>{@code "<conceptUuid>|<icdCode>"} — will be saved as a coded diagnosis (concept
	 * {@code 1284}, "Diagnosis Coded")</li>
	 * <li>{@code "RAW:<icdCode>: <description>"} — will be saved as a non-coded diagnosis (concept
	 * {@code 161602}, "Diagnosis Non-coded")</li>
	 * </ul>
	 * Items that are already present in the encounter are silently skipped (idempotent).
	 * </p>
	 * 
	 * @param patient the patient to save diagnoses for; must not be null
	 * @param visitNoteUuid the UUID of the visit note {@link Obs} whose encounter will receive the
	 *            diagnoses; must not be null and must belong to {@code patient}
	 * @param selection the list of encoded diagnosis strings to save; must not be null
	 * @return the exact string {@code "Success"} if all items are saved perfectly, otherwise
	 *         returns an aggregated string of per-item error messages.
	 * @throws IllegalArgumentException if any argument is null, if the visit note is not found, or
	 *             if the visit note does not belong to the given patient
	 */
	@Authorized(PrivilegeConstants.ADD_OBS)
	@Transactional
	String saveSelectionToEncounter(Patient patient, String visitNoteUuid, List<String> selection)
	        throws IllegalArgumentException;
	
	/**
	 * <em>Internal helper.</em> Exposed on the interface for testability only; should not be called
	 * directly by controllers or other modules.
	 * <p>
	 * Looks up an OpenMRS concept by its ICD-10-CM code, using a two-pass strategy:
	 * <ol>
	 * <li>First pass: searches in the {@code ICD-10-CM} dictionary, if it exists.</li>
	 * <li>Second pass: if either the {@code ICD-10-CM} dictionary does not exist or the code is not
	 * found, searches in the standard {@code ICD-10-WHO} dictionary with a progressive truncation
	 * strategy (e.g., "S82.001A" &rarr; "S82.001" &rarr; ... &rarr; "S82") If a truncated exact
	 * match ({@code SAME-AS}) is found, it is returned as a {@code BROADER-THAN} relationship to
	 * preserve clinical accuracy.</li>
	 * </ol>
	 * Priority order: {@code SAME-AS} &gt; {@code BROADER-THAN} &gt; {@code NARROWER-THAN}. When
	 * multiple concepts share the same priority, the one with the lowest concept ID is returned to
	 * make the result deterministic.
	 * </p>
	 * <p>
	 * Retired concepts are excluded from results.
	 * </p>
	 * 
	 * @param code the ICD-10-CM code to look up; must not be null; trimmed and uppercased before
	 *            comparison
	 * @return an {@link ICDSearchResult} with the best-matching concept, or {@code null} if no
	 *         non-retired concept is found
	 * @throws IllegalArgumentException if {@code code} is null
	 */
	@Authorized()
	@Transactional(readOnly = true)
	ICDSearchResult getConceptByICD10Code(String code) throws IllegalArgumentException;
	
	/**
	 * <em>Internal helper.</em> Exposed on the interface for testability only; should not be called
	 * directly by controllers or other modules.
	 * <p>
	 * Saves a coded ICD-10-CM diagnosis as an observation on an encounter.
	 * </p>
	 * <p>
	 * Uses CIEL concept {@code 1284} ("Diagnosis Coded") as the question concept and the diagnosis
	 * concept as the answer. If the mapping is not exact ({@code BROADER-THAN} or
	 * {@code NARROWER-THAN}), the original ICD-10-CM code is recorded in the observation's comment
	 * field to preserve clinical context.
	 * </p>
	 * <p>
	 * If {@code diagnosis} has no concept (i.e. {@code NO MAPPING} result), the method falls back
	 * to {@link #saveICD10Diagnosis(Patient, Encounter, String)} using the code and description as
	 * free text.
	 * </p>
	 * <p>
	 * If the same coded concept already exists as a non-voided observation in this encounter, the
	 * existing observation is returned and no duplicate is created.
	 * </p>
	 * 
	 * @param patient the patient; must not be null
	 * @param encounter the encounter to attach the observation to; must not be null
	 * @param diagnosis the diagnosis result to save, in the form of a {@link ICDSearchResult}; must
	 *            not be null
	 * @return the saved (or pre-existing) {@link Obs}
	 * @throws IllegalArgumentException if any argument is null
	 */
	@Authorized(ICDHelperConfig.MODULE_PRIVILEGE)
	@Transactional
	Obs saveICD10Diagnosis(Patient patient, Encounter encounter, ICDSearchResult diagnosis) throws IllegalArgumentException,
	        IllegalStateException;
	
	/**
	 * <em>Internal helper.</em> Exposed on the interface for testability only; should not be called
	 * directly by controllers or other modules.
	 * <p>
	 * Saves a non-coded diagnosis as a free-text observation on an encounter.
	 * </p>
	 * <p>
	 * Used when no concept maps to the AI-suggested ICD-10-CM code. Uses CIEL concept
	 * {@code 161602} ("Diagnosis, Non-coded") as the question concept.
	 * </p>
	 * <p>
	 * If an identical non-voided free-text observation already exists in this encounter, the
	 * existing observation is returned and no duplicate is created.
	 * </p>
	 * 
	 * @param patient the patient; must not be null
	 * @param encounter the encounter to attach the observation to; must not be null
	 * @param nonCodedText the free-text diagnosis string; must not be null or empty
	 * @return the saved (or pre-existing) {@link Obs}, or {@code null} if {@code nonCodedText} is
	 *         empty
	 * @throws IllegalArgumentException if any argument is null
	 */
	@Authorized(ICDHelperConfig.MODULE_PRIVILEGE)
	@Transactional
	Obs saveICD10Diagnosis(Patient patient, Encounter encounter, String nonCodedText) throws IllegalArgumentException,
	        IllegalStateException;
	
	/**
	 * Returns all visit note observations for a given patient within a specific visit, sorted in
	 * reverse chronological order (most recent first).
	 * <p>
	 * Uses CIEL concept {@code 162169} ("Visit Note") to identify note observations. Only
	 * observations linked to an encounter belonging to the given visit are returned.
	 * </p>
	 * 
	 * @param patient the patient whose notes to retrieve; must not be null
	 * @param visit the visit to filter notes by; must not be null
	 * @return a list of visit note {@link Obs}, sorted newest first; never null, may be empty
	 * @throws IllegalArgumentException if {@code patient} or {@code visit} is null
	 */
	@Authorized(PrivilegeConstants.GET_OBS)
	@Transactional(readOnly = true)
	List<Obs> getVisitNotes(Patient patient, Visit visit) throws IllegalArgumentException;
	
	/**
	 * <em>Internal helper.</em> Exposed on the interface for testability only; should not be called
	 * directly by controllers or other modules.
	 * <p>
	 * Returns the mapping type between a Concept and an ICD-10-CM code, by inspecting the concept's
	 * reference terms. Checks both {@code ICD-10-WHO} and {@code ICD-10-CM} sources.
	 * </p>
	 * <p>
	 * Iterates over the concept's mappings and returns the map type name (e.g. {@code "SAME-AS"},
	 * {@code "NARROWER-THAN"}, {@code "BROADER-THAN"}) for the first non-retired mapping whose code
	 * exactly matches (case-insensitive). If the input code is a truncated, highly-specific CM code
	 * (e.g., input "A00.12" matching reference term "A00"), it dynamically returns
	 * {@code "BROADER-THAN"}.
	 * </p>
	 * 
	 * @param concept the concept to inspect; must not be null
	 * @param icdCode the ICD-10-CM code to match against; must not be null
	 * @return the mapping type name, or {@code "NO MAPPING"} if either the concept is retired, or
	 *         no match is found
	 * @throws IllegalArgumentException if {@code concept} or {@code icdCode} is null
	 */
	@Authorized()
	@Transactional(readOnly = true)
	String getMappingInfo(Concept concept, String icdCode) throws IllegalArgumentException;
}
