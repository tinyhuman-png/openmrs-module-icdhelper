/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.icdhelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OrtException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.context.Context;
import org.openmrs.module.ModuleException;
import org.openmrs.module.icdhelper.api.ICDHelperService;
import org.openmrs.module.icdhelper.api.ICDSearchResult;
import org.openmrs.module.icdhelper.api.impl.ICDHelperServiceImpl;
import org.openmrs.module.icdhelper.api.predictors.HierarchicalPredictor;
import org.openmrs.module.icdhelper.api.predictors.SapBertPredictor;
import org.openmrs.test.BaseModuleContextSensitiveTest;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;

/**
 * This is a unit test, which verifies logic in ICDHelperService.
 */
public class ICDHelperServiceTest extends BaseModuleContextSensitiveTest {
	
	ICDHelperService service;
	
	ICDHelperServiceImpl serviceImpl;
	
	@Mock
	SapBertPredictor sapBertPredictor;
	
	@Mock
	HierarchicalPredictor hierarchicalPredictor;
	
	@Before
	public void setup() throws Exception {
		// This loads a small XML dataset into the in-memory DB so you have data to test
		executeDataSet("org/openmrs/module/icdhelper/include/ICDHelperServiceTest.xml");
		service = Context.getService(ICDHelperService.class);
		
		MockitoAnnotations.initMocks(this);
		serviceImpl = Mockito.spy(new ICDHelperServiceImpl());
		serviceImpl.setSapBertPredictor(sapBertPredictor);
		serviceImpl.setHierarchicalPredictor(hierarchicalPredictor);
		serviceImpl.setModelsReady(true);
	}
	
	// Tests for the getConceptByICD10Code method
	@Test
	public void getConceptByICD10Code_shouldReturnCorrectConcept() {
		// When: Looking for a code defined in the XML dataset with a SAME-AS mapping.
		ICDSearchResult result = service.getConceptByICD10Code("A00.9");
		
		// Result should not be null, should be an exact match and should match the expected name.
		Assert.assertNotNull(result);
		Assert.assertTrue(result.isExactMatch());
		Assert.assertEquals("Unspecified Cholera", result.getConcept().getName().getName());
		Assert.assertEquals("A00.9", result.getIcdCode());
	}
	
	@Test
	public void getConceptByICD10Code_caseSensitivityCode_shouldReturnCorrectConcept() {
		// When: Looking for a code defined in the XML dataset with a SAME-AS mapping. + lowercase letter
		ICDSearchResult result = service.getConceptByICD10Code("a00.9");
		
		// Result should not be null, should be an exact match and should match the expected name.
		Assert.assertNotNull(result);
		Assert.assertTrue(result.isExactMatch());
		Assert.assertEquals("Unspecified Cholera", result.getConcept().getName().getName());
		Assert.assertEquals("A00.9", result.getIcdCode());
	}
	
	@Test
	public void getConceptByICD10Code_trailingWhiteSpacesCode_shouldReturnCorrectConcept() {
		// When: Looking for a code defined in the XML dataset with a SAME-AS mapping. + trailing white spaces
		ICDSearchResult result1 = service.getConceptByICD10Code(" A00.9");
		ICDSearchResult result2 = service.getConceptByICD10Code("A00.9 ");
		
		// Result should not be null, should be an exact match and should match the expected name.
		Assert.assertNotNull(result1);
		Assert.assertNotNull(result2);
		Assert.assertEquals(result1.getConcept(), result2.getConcept());
		Assert.assertTrue(result1.isExactMatch());
		Assert.assertEquals("Unspecified Cholera", result1.getConcept().getName().getName());
		Assert.assertEquals("A00.9", result1.getIcdCode());
	}
	
	@Test
	public void getConceptByICD10Code_shouldReturnNullIfCodeNotFound() {
		ICDSearchResult result = service.getConceptByICD10Code("NON-EXISTENT-CODE");
		Assert.assertNull(result);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getConceptByICD10Code_shouldThrowIllegalArgumentIfGivenNull() {
		service.getConceptByICD10Code(null);
	}
	
	@Test
	public void getConceptByICD10Code_shouldReturnLowestConceptIdWhenMultipleSameAs() {
		ICDSearchResult result = service.getConceptByICD10Code("I10");
		
		// Result should not be null and should match the expected name.
		// I10 matches two SAME_AS in the XML dataset, should choose the Concept
		// with lowest id.
		Assert.assertNotNull(result);
		Assert.assertTrue(result.isExactMatch());
		Assert.assertEquals("Essential hypertension", result.getConcept().getName().getName());
	}
	
	@Test
	public void getConceptByICD10Code_shouldPrioritizeSameAs() {
		ICDSearchResult result = service.getConceptByICD10Code("S02.9");
		
		// Result should not be null and should match the expected name.
		// S02.9 matches both SAME_AS and NARROWER_THAN in the XML dataset, should choose the SAME_AS Concept.
		Assert.assertNotNull(result);
		Assert.assertTrue(result.isExactMatch());
		Assert.assertEquals("Fracture of skull and facial bones", result.getConcept().getName().getName());
	}
	
	@Test
	public void getConceptByICD10Code_shouldReturnLowestConceptIdWhenMultipleNarrower() {
		// When: Looking for a code defined in the XML dataset, mapping with two Concepts as NARROWER-THAN.
		ICDSearchResult result1 = service.getConceptByICD10Code("D50.0");
		Assert.assertNotNull(result1);
		ICDSearchResult result2 = service.getConceptByICD10Code("D50.0");
		Assert.assertNotNull(result2);
		
		// Result should be the same the two times, and should contain the Concept
		// with the lowest id.
		Assert.assertEquals(result1.getConcept(), result2.getConcept());
		Assert.assertEquals("Nutritional anaemia", result1.getConcept().getName().getName());
	}
	
	@Test
	public void getConceptByICD10Code_shouldReturnBroaderMatchIfNoSameAsAndNoNarrower() {
		// When: Looking for a code defined in the XML dataset with a BROADER-THAN mapping only.
		ICDSearchResult result = service.getConceptByICD10Code("I21.9");
		
		// Result should not be null, should be a broader-than match and should match the expected name.
		Assert.assertNotNull(result);
		Assert.assertEquals("BROADER-THAN", result.getMappingType());
		Assert.assertEquals("Myocardial infarction", result.getConcept().getName().getName());
	}
	
	@Test
	public void getConceptByICD10Code_shouldReturnBroaderMatchIfNoSameAsAndNarrower() {
		ICDSearchResult result = service.getConceptByICD10Code("B50");
		
		// Result should not be null and should match the expected name.
		// B50 matches no SAME_AS in XML dataset, only matches a NARROWER_THAN and a BROADER-THAN, should choose BROADER-THAN.
		Assert.assertNotNull(result);
		Assert.assertEquals("BROADER-THAN", result.getMappingType());
		Assert.assertEquals("Malaria", result.getConcept().getName().getName());
	}
	
	@Test
	public void getConceptByICD10Code_shouldReturnNullIfOnlyFoundRetiredConcept() {
		// When: Looking for a code defined in the XML dataset associated with a Concept flagged as retired
		ICDSearchResult result = service.getConceptByICD10Code("R25.2");
		Assert.assertNull(result);
	}
	
	@Test
	public void getConceptByICD10Code_shouldReturnNullIfOnlyFoundRetiredTerm() {
		// When: Looking for a code defined in the XML dataset where the reference-term is flagged as retired
		ICDSearchResult result = service.getConceptByICD10Code("M81.2");
		Assert.assertNull(result);
	}
	
	@Test
	public void getConceptByICD10Code_shouldTruncateSkipPointAndReturnBroaderMatch() {
		// When: Looking for a highly specific CM code not in the database
		ICDSearchResult result = service.getConceptByICD10Code("I10.123");
		
		// Result should not be null, it must return the original specific code,
		// but associated to a BROADER-THAN mapping with the parent concept I10 -> Essential hypertension
		Assert.assertNotNull(result);
		Assert.assertEquals("BROADER-THAN", result.getMappingType());
		Assert.assertEquals("I10.123", result.getIcdCode());
		Assert.assertEquals("Essential hypertension", result.getConcept().getName().getName());
	}
	
	@Test
	public void getConceptByICD10Code_shouldTruncateAndReturnBroaderClosestMatch() {
		// When: Looking for a highly specific CM code not in the database
		ICDSearchResult result = service.getConceptByICD10Code("B51.013");
		
		// Result should not be null, it must return the original specific code associated to a BROADER-THAN mapping,
		// with the CLOSEST parent concept with SAME-AS mapping B50.0 (not B50) -> Severe Malaria
		Assert.assertNotNull(result);
		Assert.assertEquals("BROADER-THAN", result.getMappingType());
		Assert.assertEquals("B51.013", result.getIcdCode());
		Assert.assertEquals("Plasmodium vivax malaria with rupture of spleen", result.getConcept().getName().getName());
	}
	
	@Test
	public void getConceptByICD10Code_cmCode_shouldReturnSameAs() {
		// When: Looking for a code defined in the XML dataset with a SAME-AS ICD-10-CM mapping.
		ICDSearchResult result = service.getConceptByICD10Code("S02.92");
		
		// Result should not be null, should be an exact match and should match the expected name.
		// Should not fall back to existing ICD-10-WHO mapping for S02.9 when ICD-10-CM mapping is present and not retired
		Assert.assertNotNull(result);
		Assert.assertTrue(result.isExactMatch());
		Assert.assertEquals("Unspecified fracture of facial bones", result.getConcept().getName().getName());
		Assert.assertEquals("S02.92", result.getIcdCode());
	}
	
	@Test
	public void getConceptByICD10Code_cmCode_shouldReturnBroaderMatchIfNoSameAsAndNarrower() {
		ICDSearchResult result = service.getConceptByICD10Code("M01.X2");
		
		// Result should not be null and should match the expected name.
		// M01.X2 matches no SAME_AS in XML dataset with ICD-10-CM,
		// only matches a NARROWER_THAN and a BROADER-THAN with ICD-10-CM, should choose BROADER-THAN.
		Assert.assertNotNull(result);
		Assert.assertEquals("BROADER-THAN", result.getMappingType());
		Assert.assertEquals("Direct infections of joint in infectious and parasitic diseases classified " + "elsewhere",
		    result.getConcept().getName().getName());
	}
	
	// Tests for the saveICD10Diagnosis methods
	@Test
	public void saveICD10Diagnosis_shouldSaveInCodedFieldWhenGivenICDHelperResultWithConcept() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult cholera = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		
		Obs savedObs = service.saveICD10Diagnosis(patient, encounter, cholera);
		Assert.assertNotNull(savedObs);
		
		// Verify persistence and structure
		Assert.assertNotNull(savedObs.getId());
		Obs retrievedObs = Context.getObsService().getObs(savedObs.getId());
		Assert.assertNotNull(retrievedObs);
		Assert.assertEquals(encounter, savedObs.getEncounter());
		Assert.assertEquals(encounter, retrievedObs.getEncounter());
		Assert.assertEquals(patient, savedObs.getPerson());
		Assert.assertEquals(patient, retrievedObs.getPerson());
		
		Assert.assertEquals(Integer.valueOf(1284), savedObs.getConcept().getConceptId());
		Assert.assertNotNull(savedObs.getValueCoded());
		Assert.assertEquals(cholera.getConcept(), savedObs.getValueCoded());
		Assert.assertNotNull(savedObs.getCreator());
		Assert.assertNotNull(savedObs.getObsDatetime());
		
		Assert.assertEquals(Integer.valueOf(1284), retrievedObs.getConcept().getConceptId());
		Assert.assertNotNull(retrievedObs.getValueCoded());
		Assert.assertEquals(cholera.getConcept(), retrievedObs.getValueCoded());
		Assert.assertNotNull(retrievedObs.getCreator());
		Assert.assertNotNull(retrievedObs.getObsDatetime());
	}
	
	@Test
	public void saveICD10Diagnosis_shouldSaveAsNonCodedWhenGivenFreeText() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		String freeText = "B98.0: Other specified infectious agents as the cause of diseases classified to other chapters";
		
		Obs savedObs = service.saveICD10Diagnosis(patient, encounter, freeText);
		Assert.assertNotNull(savedObs);
		
		Assert.assertNotNull(savedObs.getId());
		Obs retrievedObs = Context.getObsService().getObs(savedObs.getId());
		Assert.assertNotNull(retrievedObs);
		Assert.assertEquals(encounter, savedObs.getEncounter());
		Assert.assertEquals(encounter, retrievedObs.getEncounter());
		Assert.assertEquals(patient, savedObs.getPerson());
		Assert.assertEquals(patient, retrievedObs.getPerson());
		
		Assert.assertEquals(Integer.valueOf(160221), savedObs.getConcept().getConceptId());
		Assert.assertEquals(freeText, savedObs.getValueText());
		Assert.assertNotNull(savedObs.getCreator());
		Assert.assertNotNull(savedObs.getObsDatetime());
		
		Assert.assertEquals(Integer.valueOf(160221), retrievedObs.getConcept().getConceptId());
		Assert.assertEquals(freeText, retrievedObs.getValueText());
		Assert.assertNotNull(retrievedObs.getCreator());
		Assert.assertNotNull(retrievedObs.getObsDatetime());
	}
	
	@Test
	public void saveICD10Diagnosis_shouldSaveAsNonCodedWhenGivenICDHelperSearchWithoutConcept() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		ICDSearchResult cholera = new ICDSearchResult(null, "SAME-AS", "A00.9");
		cholera.setDescription("Unspecified Cholera ....");
		
		Obs savedObs = service.saveICD10Diagnosis(patient, encounter, cholera);
		Assert.assertNotNull(savedObs);
		
		Assert.assertNotNull(savedObs.getId());
		Obs retrievedObs = Context.getObsService().getObs(savedObs.getId());
		Assert.assertNotNull(retrievedObs);
		Assert.assertEquals(encounter, savedObs.getEncounter());
		Assert.assertEquals(encounter, retrievedObs.getEncounter());
		Assert.assertEquals(patient, savedObs.getPerson());
		Assert.assertEquals(patient, retrievedObs.getPerson());
		
		Assert.assertEquals(Integer.valueOf(160221), savedObs.getConcept().getConceptId());
		Assert.assertEquals("A00.9: Unspecified Cholera ....", savedObs.getValueText());
		Assert.assertNotNull(savedObs.getCreator());
		Assert.assertNotNull(savedObs.getObsDatetime());
		
		Assert.assertEquals(Integer.valueOf(160221), retrievedObs.getConcept().getConceptId());
		Assert.assertEquals("A00.9: Unspecified Cholera ....", retrievedObs.getValueText());
		Assert.assertNotNull(retrievedObs.getCreator());
		Assert.assertNotNull(retrievedObs.getObsDatetime());
	}
	
	@Test
	public void saveICD10Diagnosis_shouldReturnNullWhenGivenEmptyFreeText() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		String freeText = "";
		
		Obs savedObs = service.saveICD10Diagnosis(patient, encounter, freeText);
		Assert.assertNull(savedObs);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveICD10Diagnosis_shouldThrowIllegalArgumentIfGivenNullPatient_nonCoded() {
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		String freeText = "B98.0: Other specified infectious agents as the cause of diseases classified to other chapters";
		
		service.saveICD10Diagnosis(null, encounter, freeText);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveICD10Diagnosis_shouldThrowIllegalArgumentIfGivenNullPatient_coded() {
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult cholera = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		
		service.saveICD10Diagnosis(null, encounter, cholera);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveICD10Diagnosis_shouldThrowIllegalArgumentIfGivenNullEncounter_nonCoded() {
		Patient patient = Context.getPatientService().getPatient(100);
		String freeText = "B98.0: Other specified infectious agents as the cause of diseases classified to other chapters";
		
		service.saveICD10Diagnosis(patient, null, freeText);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveICD10Diagnosis_shouldThrowIllegalArgumentIfGivenNullEncounter_coded() {
		Patient patient = Context.getPatientService().getPatient(100);
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult cholera = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		
		service.saveICD10Diagnosis(patient, null, cholera);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveICD10Diagnosis_shouldThrowIllegalArgumentIfGivenNullDiagnosis_nonCoded() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		
		service.saveICD10Diagnosis(patient, encounter, (String) null);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveICD10Diagnosis_shouldThrowIllegalArgumentIfGivenNullDiagnosis_coded() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		
		service.saveICD10Diagnosis(patient, encounter, (ICDSearchResult) null);
	}
	
	@Test
	public void saveICD10Diagnosis_shouldSetCommentIfBroader() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		Concept heart_attack_concept = Context.getConceptService().getConcept(1006);
		ICDSearchResult heart_attack = new ICDSearchResult(heart_attack_concept, "BROADER-THAN", "I21.9");
		
		Obs savedObs = service.saveICD10Diagnosis(patient, encounter, heart_attack);
		Assert.assertNotNull(savedObs);
		
		// Verify persistence and structure
		Assert.assertNotNull(savedObs.getId());
		Obs retrievedObs = Context.getObsService().getObs(savedObs.getId());
		Assert.assertNotNull(retrievedObs);
		
		Assert.assertNotNull(savedObs.getComment());
		Assert.assertEquals("Originally matched ICD-10 code I21.9, which is narrower than this Concept.",
		    savedObs.getComment());
		
		Assert.assertNotNull(retrievedObs.getComment());
		Assert.assertEquals("Originally matched ICD-10 code I21.9, which is narrower than this Concept.",
		    retrievedObs.getComment());
	}
	
	@Test
	public void saveICD10Diagnosis_shouldSetCommentIfNarrower() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		Concept malaria_concept = Context.getConceptService().getConcept(1001);
		ICDSearchResult malaria = new ICDSearchResult(malaria_concept, "NARROWER-THAN", "B50.0");
		
		Obs savedObs = service.saveICD10Diagnosis(patient, encounter, malaria);
		Assert.assertNotNull(savedObs);
		
		// Verify persistence and structure
		Assert.assertNotNull(savedObs.getId());
		Obs retrievedObs = Context.getObsService().getObs(savedObs.getId());
		Assert.assertNotNull(retrievedObs);
		
		Assert.assertNotNull(savedObs.getComment());
		Assert.assertEquals("Originally matched ICD-10 code B50.0, which is broader than this Concept.",
		    savedObs.getComment());
		
		Assert.assertNotNull(retrievedObs.getComment());
		Assert.assertEquals("Originally matched ICD-10 code B50.0, which is broader than this Concept.",
		    retrievedObs.getComment());
	}
	
	@Test
	public void saveICD10Diagnosis_shouldNotSetCommentIfSameAs() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		Concept hypertension_concept = Context.getConceptService().getConcept(1011);
		ICDSearchResult hypertension = new ICDSearchResult(hypertension_concept, "SAME-AS", "I10");
		
		Obs savedObs = service.saveICD10Diagnosis(patient, encounter, hypertension);
		Assert.assertNotNull(savedObs);
		
		Assert.assertNotNull(savedObs.getId());
		Obs retrievedObs = Context.getObsService().getObs(savedObs.getId());
		Assert.assertNotNull(retrievedObs);
		
		Assert.assertNull(savedObs.getComment());
		Assert.assertNull(retrievedObs.getComment());
	}
	
	@Test
	public void saveICD10Diagnosis_shouldNotSaveDuplicateIfAlreadyExists_concept() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult cholera = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		
		Obs savedObs1 = service.saveICD10Diagnosis(patient, encounter, cholera);
		Obs savedObs2 = service.saveICD10Diagnosis(patient, encounter, cholera);
		Assert.assertEquals(savedObs1.getObsId(), savedObs2.getObsId());
		
		Context.flushSession();
		Context.clearSession();
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> observations = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		
		int count = 0;
		for (Obs obs : observations) {
			if (obs.getEncounter().getUuid().equals(encounter.getUuid()) && obs.getValueCoded().equals(cholera_concept)) {
				count += 1;
			}
		}
		Assert.assertEquals(1, count);
	}
	
	@Test
	public void saveICD10Diagnosis_shouldNotSaveDuplicateIfAlreadyExists_text() {
		Patient patient = Context.getPatientService().getPatient(100);
		Encounter encounter = Context.getEncounterService().getEncounter(100);
		String freeText = "B98.0: Other specified infectious agents as the cause of diseases classified to other chapters";
		
		Obs savedObs1 = service.saveICD10Diagnosis(patient, encounter, freeText);
		Obs savedObs2 = service.saveICD10Diagnosis(patient, encounter, freeText);
		Assert.assertEquals(savedObs1.getObsId(), savedObs2.getObsId());
		
		Context.flushSession();
		Context.clearSession();
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> observations = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		
		int count = 0;
		for (Obs obs : observations) {
			if (obs.getEncounter().getUuid().equals(encounter.getUuid()) && obs.getValueText().equals(freeText)) {
				count += 1;
			}
		}
		Assert.assertEquals(1, count);
	}
	
	// Tests for the getVisitNotes method
	@Test
	public void getVisitNotes_shouldReturnEmptyListIfNoVisitNote() {
		Patient patient = Context.getPatientService().getPatient(200);
		Visit visit = Context.getVisitService().getVisit(200);
		
		List<Obs> visit_notes = service.getVisitNotes(patient, visit);
		
		// Patient 200 has one visit with one encounter with no observation, should not return null or error,
		// should just return an empty list
		Assert.assertNotNull(visit_notes);
		Assert.assertEquals(0, visit_notes.size());
	}
	
	@Test
	public void getVisitNotes_shouldReturnCorrectObservationWhenOneVisitNote() {
		Patient patient = Context.getPatientService().getPatient(300);
		Visit visit = Context.getVisitService().getVisit(300);
		
		List<Obs> visit_notes = service.getVisitNotes(patient, visit);
		
		// Patient 300 has one visit with one encounter with one observation Visit Note
		Assert.assertNotNull(visit_notes);
		Assert.assertEquals(1, visit_notes.size());
		
		Obs observation = visit_notes.get(0);
		Assert.assertNotNull(observation);
		Assert.assertNotNull(observation.getValueText());
		Assert.assertEquals("Patient 3 presented with fever.", observation.getValueText());
		Assert.assertNotNull(observation.getEncounter());
		Assert.assertEquals(300, observation.getEncounter().getId().intValue());
	}
	
	@Test
	public void getVisitNotes_shouldReturnCorrectObservationsWhenMultipleVisitNote() {
		Patient patient = Context.getPatientService().getPatient(400);
		Visit visit = Context.getVisitService().getVisit(400);
		
		List<Obs> visit_notes = service.getVisitNotes(patient, visit);
		
		// Patient 400 has one visit with one encounter with 3 Visit Note observations
		// should return in anti-chronological order (from most to least recent), so its convenient in UI
		Assert.assertNotNull(visit_notes);
		Assert.assertEquals(3, visit_notes.size());
		
		Obs obs_1 = visit_notes.get(0);
		Assert.assertNotNull(obs_1);
		Assert.assertNotNull(obs_1.getValueText());
		Assert.assertEquals("Third visit note.", obs_1.getValueText());
		Assert.assertNotNull(obs_1.getEncounter());
		Assert.assertEquals(400, obs_1.getEncounter().getId().intValue());
		
		Obs obs_2 = visit_notes.get(1);
		Assert.assertNotNull(obs_2);
		Assert.assertNotNull(obs_2.getValueText());
		Assert.assertEquals("Second visit note.", obs_2.getValueText());
		Assert.assertNotNull(obs_2.getEncounter());
		Assert.assertEquals(400, obs_2.getEncounter().getId().intValue());
		
		Obs obs_3 = visit_notes.get(2);
		Assert.assertNotNull(obs_3);
		Assert.assertNotNull(obs_3.getValueText());
		Assert.assertEquals("First visit note.", obs_3.getValueText());
		Assert.assertNotNull(obs_3.getEncounter());
		Assert.assertEquals(400, obs_3.getEncounter().getId().intValue());
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getVisitNotes_shouldThrowIllegalArgumentIfGivenNullPatient() {
		Visit visit = Context.getVisitService().getVisit(400);
		service.getVisitNotes(null, visit);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getVisitNotes_shouldThrowIllegalArgumentIfGivenNullVisit() {
		Patient patient = Context.getPatientService().getPatient(400);
		service.getVisitNotes(patient, null);
	}
	
	@Test
	public void getVisitNotes_shouldReturnOnlyVisitNotes() {
		Patient patient = Context.getPatientService().getPatient(500);
		Visit visit = Context.getVisitService().getVisit(500);
		
		List<Obs> visit_notes = service.getVisitNotes(patient, visit);
		
		// Patient 500 has one visit with one encounter with one Visit Note and another type of observation
		// should not return the other observation in the list
		Assert.assertNotNull(visit_notes);
		Assert.assertEquals(1, visit_notes.size());
		
		Obs observation = visit_notes.get(0);
		Assert.assertNotNull(observation);
		Assert.assertNotNull(observation.getValueText());
		Assert.assertEquals("Patient 5 presented with fever.", observation.getValueText());
		Assert.assertNotNull(observation.getEncounter());
		Assert.assertEquals(500, observation.getEncounter().getId().intValue());
	}
	
	@Test
	public void getVisitNotes_shouldNotReturnVoidedVisitNotes() {
		Patient patient = Context.getPatientService().getPatient(600);
		Visit visit = Context.getVisitService().getVisit(600);
		
		List<Obs> visit_notes = service.getVisitNotes(patient, visit);
		
		// Patient 600 has one visit with one encounter with 2 Visit Note observations,
		// one is active but the other one is voided, should not return the voided one
		Assert.assertNotNull(visit_notes);
		Assert.assertEquals(1, visit_notes.size());
		
		Obs observation = visit_notes.get(0);
		Assert.assertNotNull(observation);
		Assert.assertNotNull(observation.getValueText());
		Assert.assertEquals("Active visit note.", observation.getValueText());
		Assert.assertNotNull(observation.getEncounter());
		Assert.assertEquals(600, observation.getEncounter().getId().intValue());
	}
	
	@Test
	public void getVisitNotes_shouldOnlyReturnVisitNotesFromVisit() {
		Patient patient = Context.getPatientService().getPatient(700);
		Visit visit = Context.getVisitService().getVisit(700);
		
		List<Obs> visit_notes = service.getVisitNotes(patient, visit);
		
		// Patient 700 has 2 visits, each with one encounter with one Visit Note observation,
		// when asked for visit_id=700, should not return the Visit Note from the other visit
		Assert.assertNotNull(visit_notes);
		Assert.assertEquals(1, visit_notes.size());
		
		Obs observation = visit_notes.get(0);
		Assert.assertNotNull(observation);
		Assert.assertNotNull(observation.getValueText());
		Assert.assertEquals("Visit note from visit 700.", observation.getValueText());
		Assert.assertNotNull(observation.getEncounter());
		Assert.assertEquals(700, observation.getEncounter().getId().intValue());
	}
	
	@Test
	public void getVisitNotes_shouldReturnEmptyListWhenVisitBelongsToAnotherPatient() {
		Patient patient = Context.getPatientService().getPatient(200);
		Visit visit = Context.getVisitService().getVisit(300);
		
		List<Obs> visit_notes = service.getVisitNotes(patient, visit);
		
		// Patient 200 has no visit with id 300, should return an empty list
		Assert.assertNotNull(visit_notes);
		Assert.assertEquals(0, visit_notes.size());
	}
	
	// Tests for the getMappingInfo method
	@Test
	public void getMappingInfo_shouldReturnCorrectMappingSameAs() {
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		
		String mapping = service.getMappingInfo(cholera_concept, "A00.9");
		
		Assert.assertNotNull(mapping);
		Assert.assertEquals("SAME-AS", mapping);
	}
	
	@Test
	public void getMappingInfo_shouldReturnCorrectMappingNarrowerThan() {
		Concept openFractureConcept = Context.getConceptService().getConcept(1002);
		
		String mapping = service.getMappingInfo(openFractureConcept, "S02.9");
		
		Assert.assertNotNull(mapping);
		Assert.assertEquals("NARROWER-THAN", mapping);
	}
	
	@Test
	public void getMappingInfo_shouldReturnCorrectMappingBroaderThan() {
		Concept heart_attack_concept = Context.getConceptService().getConcept(1006);
		
		String mapping = service.getMappingInfo(heart_attack_concept, "I21.9");
		
		Assert.assertNotNull(mapping);
		Assert.assertEquals("BROADER-THAN", mapping);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getMappingInfo_shouldThrowIllegalArgumentIfGivenNullConcept() {
		service.getMappingInfo(null, "A00.9");
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getMappingInfo_shouldThrowIllegalArgumentIfGivenNullCode() {
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		
		service.getMappingInfo(cholera_concept, null);
	}
	
	@Test
	public void getMappingInfo_shouldReturnDefaultMappingIfConceptMappedToAnotherICDCode() {
		Concept cholera_concept = Context.getConceptService().getConcept(1001);
		
		String mapping = service.getMappingInfo(cholera_concept, "B50");
		
		Assert.assertNotNull(mapping);
		Assert.assertEquals("NO MAPPING", mapping);
	}
	
	@Test
	public void getMappingInfo_shouldReturnDefaultMappingIfConceptHasNoMapping() {
		Concept covid_concept = Context.getConceptService().getConcept(1013);
		
		String mapping = service.getMappingInfo(covid_concept, "A00.9");
		
		Assert.assertNotNull(mapping);
		Assert.assertEquals("NO MAPPING", mapping);
	}
	
	@Test
	public void getMappingInfo_shouldReturnDefaultMappingIfConceptIsRetired() {
		Concept cholera_concept = Context.getConceptService().getConcept(1009);
		
		String mapping = service.getMappingInfo(cholera_concept, "R25.2");
		
		Assert.assertNotNull(mapping);
		Assert.assertEquals("NO MAPPING", mapping);
	}
	
	@Test
	public void getMappingInfo_shouldReturnDefaultMappingIfReferenceTermIsRetired() {
		Concept cholera_concept = Context.getConceptService().getConcept(1010);
		
		String mapping = service.getMappingInfo(cholera_concept, "M81.2");
		
		Assert.assertNotNull(mapping);
		Assert.assertEquals("NO MAPPING", mapping);
	}
	
	@Test
	public void getMappingInfo_shouldBeConsistentWithGetConceptByICD10Code() {
		ICDSearchResult result = service.getConceptByICD10Code("A00.9");
		
		Assert.assertNotNull(result);
		Assert.assertNotNull(result.getConcept());
		Assert.assertNotNull(result.getIcdCode());
		
		String mapping = service.getMappingInfo(result.getConcept(), result.getIcdCode());
		
		Assert.assertNotNull(mapping);
		Assert.assertEquals(result.getMappingType(), mapping);
	}
	
	// Tests for the saveSelectionToEncounter method
	@Test
	public void saveSelectionToEncounter_shouldHandleConceptSaveCorrectly() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals("Success", resultStatus);
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(1, retrievedObs.size());
		
		Assert.assertNotNull(retrievedObs.get(0).getValueCoded());
		Concept concept_1000 = Context.getConceptService().getConceptByUuid("concept-uuid-1");
		Assert.assertEquals(concept_1000, retrievedObs.get(0).getValueCoded());
		
		Assert.assertNotNull(retrievedObs.get(0).getEncounter().getUuid());
		String encounter_uuid = Context.getObsService().getObsByUuid(visit_note_uuid).getEncounter().getUuid();
		Assert.assertEquals(encounter_uuid, retrievedObs.get(0).getEncounter().getUuid());
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_text = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(0, retrievedObs_text.size());
	}
	
	@Test
	public void saveSelectionToEncounter_shouldHandleFreeTextSaveCorrectly() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		selection.add("RAW:B98.0: Other specified infectious agents as the cause of diseases classified to other chapters");
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals("Success", resultStatus);
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(1, retrievedObs.size());
		
		Assert.assertNotNull(retrievedObs.get(0).getValueText());
		Assert.assertEquals(
		    "B98.0: Other specified infectious agents as the cause of diseases classified to other chapters", retrievedObs
		            .get(0).getValueText());
		
		Assert.assertNotNull(retrievedObs.get(0).getEncounter().getUuid());
		String encounter_uuid = Context.getObsService().getObsByUuid(visit_note_uuid).getEncounter().getUuid();
		Assert.assertEquals(encounter_uuid, retrievedObs.get(0).getEncounter().getUuid());
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_concept = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(0, retrievedObs_concept.size());
	}
	
	@Test
	public void saveSelectionToEncounter_shouldHandleMultipleMixedItemsSelectionSaveCorrectly() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		selection.add("RAW:B98.0: Other specified infectious agents as the cause of diseases classified to other chapters");
		selection.add("concept-uuid-7|D50.0");
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals("Success", resultStatus);
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(1, retrievedObs.size());
		
		Assert.assertNotNull(retrievedObs.get(0).getValueText());
		Assert.assertEquals(
		    "B98.0: Other specified infectious agents as the cause of diseases classified to other chapters", retrievedObs
		            .get(0).getValueText());
		
		Assert.assertNotNull(retrievedObs.get(0).getEncounter().getUuid());
		String encounter_uuid = Context.getObsService().getObsByUuid(visit_note_uuid).getEncounter().getUuid();
		Assert.assertEquals(encounter_uuid, retrievedObs.get(0).getEncounter().getUuid());
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_concept = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(2, retrievedObs_concept.size());
		
		// Order independent check
		Assert.assertNotNull(retrievedObs_concept.get(0).getValueCoded());
		Concept concept_1000 = Context.getConceptService().getConceptByUuid("concept-uuid-1");
		Concept concept_1004 = Context.getConceptService().getConceptByUuid("concept-uuid-7");
		Assert.assertTrue(retrievedObs_concept.get(0).getValueCoded().equals(concept_1000)
		        || retrievedObs_concept.get(0).getValueCoded().equals(concept_1004));
		if (retrievedObs_concept.get(0).getValueCoded().equals(concept_1000)) {
			Assert.assertEquals(retrievedObs_concept.get(1).getValueCoded(), concept_1004);
		} else {
			Assert.assertEquals(retrievedObs_concept.get(1).getValueCoded(), concept_1000);
		}
		
		Assert.assertNotNull(retrievedObs_concept.get(0).getEncounter().getUuid());
		Assert.assertEquals(encounter_uuid, retrievedObs_concept.get(0).getEncounter().getUuid());
		Assert.assertNotNull(retrievedObs_concept.get(1).getEncounter().getUuid());
		Assert.assertEquals(encounter_uuid, retrievedObs_concept.get(1).getEncounter().getUuid());
		
	}
	
	@Test
	public void saveSelectionToEncounter_shouldReturnCorrectMessageWhenEmptySelection() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals("No selected diagnoses; nothing to save.", resultStatus);
		
		//Nothing should have been saved
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(0, retrievedObs.size());
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_concept = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(0, retrievedObs_concept.size());
	}
	
	@Test
	public void saveSelectionToEncounter_shouldReturnCorrectMessageWhenVisitNodeHasNoEncounter() {
		Patient patient = Context.getPatientService().getPatient(700);
		String visit_note_uuid = "obs-uuid-702";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		selection.add("RAW:B98.0: Other specified infectious agents as the cause of diseases classified to other chapters");
		selection.add("concept-uuid-7|D50.0");
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals("Could not save the observations when Visit Note has no associated encounter.", resultStatus);
		
		//Nothing should have been saved
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(0, retrievedObs.size());
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_concept = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(0, retrievedObs_concept.size());
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveSelectionToEncounter_shouldThrowIllegalArgumentExceptionIfNullPatient() {
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		
		service.saveSelectionToEncounter(null, visit_note_uuid, selection);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveSelectionToEncounter_shouldThrowIllegalArgumentExceptionIfNullVisitNoteUuid() {
		Patient patient = Context.getPatientService().getPatient(300);
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		
		service.saveSelectionToEncounter(patient, null, selection);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveSelectionToEncounter_shouldThrowIllegalArgumentExceptionIfNullSelection() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		
		service.saveSelectionToEncounter(patient, visit_note_uuid, null);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveSelectionToEncounter_shouldThrowIllegalArgumentExceptionIfVisitNoteUuidNotFound() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-303";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		
		service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void saveSelectionToEncounter_shouldThrowIllegalArgumentExceptionIfVisitNoteDoesNotBelongToPatient() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-500";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		
		service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
	}
	
	@Test
	public void saveSelectionToEncounter_shouldReturnCorrectMessageWhenUnexpectedSelectionItemFormat() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection1 = new ArrayList<String>();
		selection1.add("B98.0: Other specified infectious agents as the cause of diseases classified to other chapters");
		List<String> selection2 = new ArrayList<String>();
		selection2.add("RAW - B98.0 Other specified infectious agents as the cause of diseases classified to otherchapters");
		List<String> selection3 = new ArrayList<String>();
		selection3.add("concept-uuid-1 - A00.9 RAW:Unspecified Cholera");
		
		String resultStatus1 = service.saveSelectionToEncounter(patient, visit_note_uuid, selection1);
		Assert.assertNotNull(resultStatus1);
		Assert.assertEquals("There was an error saving some diagnose.s: ['B98.0: Other specified infectious "
		        + "agents as the cause of diseases classified to other chapters' is not a valid format for selection item]",
		    resultStatus1);
		
		String resultStatus2 = service.saveSelectionToEncounter(patient, visit_note_uuid, selection2);
		Assert.assertNotNull(resultStatus2);
		Assert.assertEquals("There was an error saving some diagnose.s: ['RAW - B98.0 Other specified infectious "
		        + "agents as the cause of diseases classified to otherchapters' is not a valid format for selection item]",
		    resultStatus2);
		
		String resultStatus3 = service.saveSelectionToEncounter(patient, visit_note_uuid, selection3);
		Assert.assertNotNull(resultStatus3);
		Assert.assertEquals("There was an error saving some diagnose.s: ['concept-uuid-1 - A00.9 RAW:Unspecified "
		        + "Cholera' is not a valid format for selection item]", resultStatus3);
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(0, retrievedObs.size());
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_concept = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(0, retrievedObs_concept.size());
	}
	
	@Test
	public void saveSelectionToEncounter_shouldReturnCorrectMessageWhenValidButEmptySelectionItem() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		selection.add("RAW:");
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals("There was an error saving some diagnose.s: ['RAW:' could not be saved in database]",
		    resultStatus);
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(0, retrievedObs.size());
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_concept = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(0, retrievedObs_concept.size());
	}
	
	@Test
	public void saveSelectionToEncounter_shouldDetectIfCodeNotMatchingTheConceptMappings() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|D50.0");
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals("There was an error saving some diagnose.s: [There is no mapping between "
		        + "concept-uuid-1 and D50.0]", resultStatus);
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(0, retrievedObs.size());
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_concept = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(0, retrievedObs_concept.size());
	}
	
	@Test
	public void saveSelectionToEncounter_shouldDetectIfConceptUuidNotMatchingAnyConcept() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-0|A00.9");
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals("There was an error saving some diagnose.s: [There is no Concept corresponding to "
		        + "concept uuid concept-uuid-0]", resultStatus);
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(0, retrievedObs.size());
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_concept = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(0, retrievedObs_concept.size());
	}
	
	@Test
	public void saveSelectionToEncounter_shouldHandleMixedCorrectAndMalformedSelectionItemsCorrectly() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		selection.add("A00.9: Unspecified cholera");
		selection.add("RAW:B98.0: Other specified infectious agents as the cause of diseases classified to other chapters");
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals(
		    "There was an error saving some diagnose.s: ['A00.9: Unspecified cholera' is not a valid format for "
		            + "selection item]", resultStatus);
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(1, retrievedObs.size());
		
		Assert.assertNotNull(retrievedObs.get(0).getValueText());
		Assert.assertEquals(
		    "B98.0: Other specified infectious agents as the cause of diseases classified to other chapters", retrievedObs
		            .get(0).getValueText());
		
		Assert.assertNotNull(retrievedObs.get(0).getEncounter().getUuid());
		String encounter_uuid = Context.getObsService().getObsByUuid(visit_note_uuid).getEncounter().getUuid();
		Assert.assertEquals(encounter_uuid, retrievedObs.get(0).getEncounter().getUuid());
		
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_concept = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(1, retrievedObs_concept.size());
		
		Assert.assertNotNull(retrievedObs_concept.get(0).getValueCoded());
		Concept concept_1000 = Context.getConceptService().getConceptByUuid("concept-uuid-1");
		Assert.assertEquals(concept_1000, retrievedObs_concept.get(0).getValueCoded());
		
		Assert.assertNotNull(retrievedObs_concept.get(0).getEncounter().getUuid());
		Assert.assertEquals(encounter_uuid, retrievedObs_concept.get(0).getEncounter().getUuid());
	}
	
	@Test
	public void saveSelectionToEncounter_shouldSaveOnceIfDuplicatesInSelection() {
		Patient patient = Context.getPatientService().getPatient(300);
		String visit_note_uuid = "obs-uuid-300";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		selection.add("concept-uuid-1|A00.9");
		
		String resultStatus = service.saveSelectionToEncounter(patient, visit_note_uuid, selection);
		Assert.assertNotNull(resultStatus);
		Assert.assertEquals("Success", resultStatus);
		
		//Should have saved only once
		Concept problem_list = Context.getConceptService().getConceptByUuid("1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs = Context.getObsService().getObservationsByPersonAndConcept(patient, problem_list);
		Assert.assertEquals(1, retrievedObs.size());
		
		Assert.assertNotNull(retrievedObs.get(0).getValueCoded());
		Concept concept_1000 = Context.getConceptService().getConceptByUuid("concept-uuid-1");
		Assert.assertEquals(concept_1000, retrievedObs.get(0).getValueCoded());
		
		Assert.assertNotNull(retrievedObs.get(0).getEncounter().getUuid());
		String encounter_uuid = Context.getObsService().getObsByUuid(visit_note_uuid).getEncounter().getUuid();
		Assert.assertEquals(encounter_uuid, retrievedObs.get(0).getEncounter().getUuid());
		
		Concept med_history = Context.getConceptService().getConceptByUuid("160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		List<Obs> retrievedObs_text = Context.getObsService().getObservationsByPersonAndConcept(patient, med_history);
		Assert.assertEquals(0, retrievedObs_text.size());
	}
	
	@Test
	public void getPredictionsFromNote_shouldReturnResultWithMatchedConceptsIfCodeInDB_selection() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		Map<String, Object> prediction = new HashMap<String, Object>();
		prediction.put("code", "A009");
		prediction.put("description", "Unspecified Cholera");
		prediction.put("score", 0.97);
		modelResponse.add(prediction);
		when(sapBertPredictor.predict("Patient has cholera.", 20)).thenReturn(modelResponse);
		
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult fakeResult = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		doReturn(fakeResult).when(serviceImpl).getConceptByICD10Code("A00.9");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has cholera.", "selection");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("A00.9", results.get(0).getIcdCode());
		Assert.assertEquals("Unspecified Cholera", results.get(0).getDescription());
		Assert.assertEquals(0.97, results.get(0).getConfidence(), 0.001);
		Assert.assertEquals("SAME-AS", results.get(0).getMappingType());
		Assert.assertNotNull(results.get(0).getConcept());
		Assert.assertEquals(cholera_concept, results.get(0).getConcept());
		
		verify(hierarchicalPredictor, never()).predict("Patient has cholera.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldReturnResultWithMatchedConceptsIfCodeInDB_full() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		Map<String, Object> prediction = new HashMap<String, Object>();
		prediction.put("code", "A009");
		prediction.put("description", "Unspecified Cholera");
		prediction.put("score", 0.97);
		modelResponse.add(prediction);
		when(hierarchicalPredictor.predict("Patient has cholera.", 20)).thenReturn(modelResponse);
		
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult fakeResult = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		doReturn(fakeResult).when(serviceImpl).getConceptByICD10Code("A00.9");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has cholera.", "full");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("A00.9", results.get(0).getIcdCode());
		Assert.assertEquals("Unspecified Cholera", results.get(0).getDescription());
		Assert.assertEquals(0.97, results.get(0).getConfidence(), 0.001);
		Assert.assertEquals("SAME-AS", results.get(0).getMappingType());
		Assert.assertNotNull(results.get(0).getConcept());
		Assert.assertEquals(cholera_concept, results.get(0).getConcept());
		
		verify(sapBertPredictor, never()).predict("Patient has cholera.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldReturnResultWithoutConceptIfCodeNotInDB_selection() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		Map<String, Object> prediction = new HashMap<String, Object>();
		prediction.put("code", "B980");
		prediction.put("description",
		    "Other specified infectious agents as the cause of diseases classified to other chapters");
		prediction.put("score", 0.72);
		modelResponse.add(prediction);
		when(sapBertPredictor.predict("Patient has unspecified infectious agent.", 20)).thenReturn(modelResponse);
		
		doReturn(null).when(serviceImpl).getConceptByICD10Code("B98.0");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has unspecified infectious agent.",
		    "selection");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("B98.0", results.get(0).getIcdCode());
		Assert.assertEquals("Other specified infectious agents as the cause of diseases classified to other chapters",
		    results.get(0).getDescription());
		Assert.assertEquals(0.72, results.get(0).getConfidence(), 0.001);
		Assert.assertEquals("NO MAPPING", results.get(0).getMappingType());
		Assert.assertNull(results.get(0).getConcept());
		
		verify(hierarchicalPredictor, never()).predict("Patient has unspecified infectious agent.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldReturnResultWithoutConceptIfCodeNotInDB_full() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		Map<String, Object> prediction = new HashMap<String, Object>();
		prediction.put("code", "B980");
		prediction.put("description",
		    "Other specified infectious agents as the cause of diseases classified to other chapters");
		prediction.put("score", 0.72);
		modelResponse.add(prediction);
		when(hierarchicalPredictor.predict("Patient has unspecified infectious agent.", 20)).thenReturn(modelResponse);
		
		doReturn(null).when(serviceImpl).getConceptByICD10Code("B98.0");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has unspecified infectious agent.",
		    "full");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("B98.0", results.get(0).getIcdCode());
		Assert.assertEquals("Other specified infectious agents as the cause of diseases classified to other chapters",
		    results.get(0).getDescription());
		Assert.assertEquals(0.72, results.get(0).getConfidence(), 0.001);
		Assert.assertEquals("NO MAPPING", results.get(0).getMappingType());
		Assert.assertNull(results.get(0).getConcept());
		
		verify(sapBertPredictor, never()).predict("Patient has unspecified infectious agent.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldReturnCorrectResultsWhenMixedMultiplePredictions_selection() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		
		Map<String, Object> prediction1 = new HashMap<String, Object>();
		prediction1.put("code", "B980");
		prediction1.put("description",
		    "Other specified infectious agents as the cause of diseases classified to other chapters");
		prediction1.put("score", 0.98);
		modelResponse.add(prediction1);
		
		Map<String, Object> prediction2 = new HashMap<String, Object>();
		prediction2.put("code", "A00.9");
		prediction2.put("description", "Unspecified Cholera");
		prediction2.put("score", 0.91);
		modelResponse.add(prediction2);
		
		Map<String, Object> prediction3 = new HashMap<String, Object>();
		prediction3.put("code", "S029");
		prediction3.put("description", "Open skull fracture");
		prediction3.put("score", 0.705);
		modelResponse.add(prediction3);
		
		when(sapBertPredictor.predict("Patient has lots of health problem, including ...", 20)).thenReturn(modelResponse);
		
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult fakeResult_cholera = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		doReturn(fakeResult_cholera).when(serviceImpl).getConceptByICD10Code("A00.9");
		
		doReturn(null).when(serviceImpl).getConceptByICD10Code("B98.0");
		
		Concept fracture_concept = Context.getConceptService().getConcept(1002);
		ICDSearchResult fakeResult_fracture = new ICDSearchResult(fracture_concept, "NARROWER-THAN", "S02.9");
		doReturn(fakeResult_fracture).when(serviceImpl).getConceptByICD10Code("S02.9");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote(
		    "Patient has lots of health problem, including ...", "selection");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(3, results.size());
		
		// Should come in prediction order
		Assert.assertEquals("B98.0", results.get(0).getIcdCode());
		Assert.assertEquals("Other specified infectious agents as the cause of diseases classified to other chapters",
		    results.get(0).getDescription());
		Assert.assertEquals(0.98, results.get(0).getConfidence(), 0.001);
		Assert.assertEquals("NO MAPPING", results.get(0).getMappingType());
		Assert.assertNull(results.get(0).getConcept());
		
		Assert.assertEquals("A00.9", results.get(1).getIcdCode());
		Assert.assertEquals("Unspecified Cholera", results.get(1).getDescription());
		Assert.assertEquals(0.91, results.get(1).getConfidence(), 0.001);
		Assert.assertEquals("SAME-AS", results.get(1).getMappingType());
		Assert.assertNotNull(results.get(1).getConcept());
		Assert.assertEquals(cholera_concept, results.get(1).getConcept());
		
		Assert.assertEquals("S02.9", results.get(2).getIcdCode());
		Assert.assertEquals("Open skull fracture", results.get(2).getDescription());
		Assert.assertEquals(0.705, results.get(2).getConfidence(), 0.001);
		Assert.assertEquals("NARROWER-THAN", results.get(2).getMappingType());
		Assert.assertNotNull(results.get(2).getConcept());
		Assert.assertEquals(fracture_concept, results.get(2).getConcept());
		
		verify(hierarchicalPredictor, never()).predict("Patient has lots of health problem, including ...", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldReturnCorrectResultsWhenMixedMultiplePredictions_full() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		
		Map<String, Object> prediction1 = new HashMap<String, Object>();
		prediction1.put("code", "B980");
		prediction1.put("description",
		    "Other specified infectious agents as the cause of diseases classified to other chapters");
		prediction1.put("score", 0.98);
		modelResponse.add(prediction1);
		
		Map<String, Object> prediction2 = new HashMap<String, Object>();
		prediction2.put("code", "A00.9");
		prediction2.put("description", "Unspecified Cholera");
		prediction2.put("score", 0.91);
		modelResponse.add(prediction2);
		
		Map<String, Object> prediction3 = new HashMap<String, Object>();
		prediction3.put("code", "S029");
		prediction3.put("description", "Open skull fracture");
		prediction3.put("score", 0.705);
		modelResponse.add(prediction3);
		
		when(hierarchicalPredictor.predict("Patient has lots of health problem, including ...", 20)).thenReturn(
		    modelResponse);
		
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult fakeResult_cholera = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		doReturn(fakeResult_cholera).when(serviceImpl).getConceptByICD10Code("A00.9");
		
		doReturn(null).when(serviceImpl).getConceptByICD10Code("B98.0");
		
		Concept fracture_concept = Context.getConceptService().getConcept(1002);
		ICDSearchResult fakeResult_fracture = new ICDSearchResult(fracture_concept, "NARROWER-THAN", "S02.9");
		doReturn(fakeResult_fracture).when(serviceImpl).getConceptByICD10Code("S02.9");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote(
		    "Patient has lots of health problem, including ...", "full");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(3, results.size());
		
		// Should come in prediction order
		Assert.assertEquals("B98.0", results.get(0).getIcdCode());
		Assert.assertEquals("Other specified infectious agents as the cause of diseases classified to other chapters",
		    results.get(0).getDescription());
		Assert.assertEquals(0.98, results.get(0).getConfidence(), 0.001);
		Assert.assertEquals("NO MAPPING", results.get(0).getMappingType());
		Assert.assertNull(results.get(0).getConcept());
		
		Assert.assertEquals("A00.9", results.get(1).getIcdCode());
		Assert.assertEquals("Unspecified Cholera", results.get(1).getDescription());
		Assert.assertEquals(0.91, results.get(1).getConfidence(), 0.001);
		Assert.assertEquals("SAME-AS", results.get(1).getMappingType());
		Assert.assertNotNull(results.get(1).getConcept());
		Assert.assertEquals(cholera_concept, results.get(1).getConcept());
		
		Assert.assertEquals("S02.9", results.get(2).getIcdCode());
		Assert.assertEquals("Open skull fracture", results.get(2).getDescription());
		Assert.assertEquals(0.705, results.get(2).getConfidence(), 0.001);
		Assert.assertEquals("NARROWER-THAN", results.get(2).getMappingType());
		Assert.assertNotNull(results.get(2).getConcept());
		Assert.assertEquals(fracture_concept, results.get(2).getConcept());
		
		verify(sapBertPredictor, never()).predict("Patient has lots of health problem, including ...", 20);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getPredictionsFromNote_shouldThrowIllegalArgumentExceptionWhenNullClinicalNote() {
		serviceImpl.getPredictionsFromNote(null, "selection");
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getPredictionsFromNote_shouldThrowIllegalArgumentExceptionWhenNullMode() {
		serviceImpl.getPredictionsFromNote("Patient has cholera.", null);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getPredictionsFromNote_shouldThrowIllegalArgumentExceptionWhenIllegalModeValue() {
		serviceImpl.getPredictionsFromNote("Patient has cholera.", "partial");
	}
	
	@Test(expected = ModuleException.class)
	public void getPredictionsFromNote_shouldThrowModuleExceptionWhenOrtException_selection() throws Exception {
		// Stub the model response
		when(sapBertPredictor.predict("Patient has cholera.", 20)).thenThrow(new OrtException("Error code - ORT_FAIL"));
		
		serviceImpl.getPredictionsFromNote("Patient has cholera.", "selection");
	}
	
	@Test(expected = ModuleException.class)
	public void getPredictionsFromNote_shouldThrowModuleExceptionWhenOrtException_full() throws Exception {
		// Stub the model response
		when(hierarchicalPredictor.predict("Patient has cholera.", 20)).thenThrow(new OrtException("Error code - ORT_FAIL"));
		
		serviceImpl.getPredictionsFromNote("Patient has cholera.", "full");
	}
	
	@Test(expected = IllegalStateException.class)
	public void getPredictionsFromNote_shouldThrowIllegalStateExceptionWhenPredictorThrowsIt_selection() throws Exception {
		// Stub the model response
		when(sapBertPredictor.predict("Patient has cholera.", 20)).thenThrow(new IllegalStateException());
		
		serviceImpl.getPredictionsFromNote("Patient has cholera.", "selection");
	}
	
	@Test(expected = IllegalStateException.class)
	public void getPredictionsFromNote_shouldThrowIllegalStateExceptionWhenPredictorThrowsIt_full() throws Exception {
		// Stub the model response
		when(hierarchicalPredictor.predict("Patient has cholera.", 20)).thenThrow(new IllegalStateException());
		
		serviceImpl.getPredictionsFromNote("Patient has cholera.", "full");
	}
	
	@Test(expected = IllegalStateException.class)
	public void getPredictionsFromNote_shouldThrowIllegalStateExceptionWhenPredictorIsNull_selection() {
		serviceImpl.setSapBertPredictor(null);
		
		serviceImpl.getPredictionsFromNote("Patient has cholera.", "selection");
	}
	
	@Test(expected = IllegalStateException.class)
	public void getPredictionsFromNote_shouldThrowIllegalStateExceptionWhenPredictorIsNull_full() {
		serviceImpl.setHierarchicalPredictor(null);
		
		serviceImpl.getPredictionsFromNote("Patient has cholera.", "full");
	}
	
	@Test
	public void getPredictionsFromNote_shouldReturnEmptyListIfEmptyClinicalNote() {
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote(" ", "selection");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(0, results.size());
	}
	
	@Test
	public void getPredictionsFromNote_shouldReturnEmptyListIfEmptyPrediction() throws Exception {
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		when(sapBertPredictor.predict("Patient has morning sickness.", 20)).thenReturn(modelResponse);
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has morning sickness.", "selection");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(0, results.size());
		
		verify(hierarchicalPredictor, never()).predict("Patient has morning sickness.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldIgnoreMalformedAnswer() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		Map<String, Object> prediction = new HashMap<String, Object>();
		prediction.put("code", null); // malformed
		prediction.put("description", "Malformed example");
		prediction.put("second_description", "a");
		modelResponse.add(prediction);
		when(sapBertPredictor.predict("Patient has cholera.", 20)).thenReturn(modelResponse);
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has cholera.", "selection");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(0, results.size());
		
		verify(hierarchicalPredictor, never()).predict("Patient has cholera.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldSkipMalformedAndKeepValidEntries() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		
		Map<String, Object> bad = new HashMap<String, Object>();
		bad.put("code", null); // malformed
		bad.put("description", "Malformed example");
		modelResponse.add(bad);
		
		Map<String, Object> good = new HashMap<String, Object>();
		good.put("code", "A009");
		good.put("description", "Unspecified Cholera");
		good.put("score", 0.9);
		modelResponse.add(good);
		
		when(sapBertPredictor.predict("Patient has cholera.", 20)).thenReturn(modelResponse);
		Concept concept = Context.getConceptService().getConcept(1000);
		doReturn(new ICDSearchResult(concept, "SAME-AS", "A00.9")).when(serviceImpl).getConceptByICD10Code("A00.9");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has cholera.", "selection");
		
		Assert.assertEquals(1, results.size()); // bad entry skipped, good entry kept
		Assert.assertEquals("A00.9", results.get(0).getIcdCode());
		
		verify(hierarchicalPredictor, never()).predict("Patient has cholera.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldNotFailIfNoReturnedDescription() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		Map<String, Object> prediction = new HashMap<String, Object>();
		prediction.put("code", "A009");
		prediction.put("description", null);
		modelResponse.add(prediction);
		when(sapBertPredictor.predict("Patient has cholera.", 20)).thenReturn(modelResponse);
		
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult fakeResult_cholera = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		doReturn(fakeResult_cholera).when(serviceImpl).getConceptByICD10Code("A00.9");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has cholera.", "selection");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("A00.9", results.get(0).getIcdCode());
		Assert.assertNull(results.get(0).getDescription());
		Assert.assertNull(results.get(0).getConfidence());
		Assert.assertEquals("SAME-AS", results.get(0).getMappingType());
		Assert.assertNotNull(results.get(0).getConcept());
		Assert.assertEquals(cholera_concept, results.get(0).getConcept());
		
		verify(hierarchicalPredictor, never()).predict("Patient has unspecified infectious agent.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldNotFailIfNoReturnedScore() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		Map<String, Object> prediction = new HashMap<String, Object>();
		prediction.put("code", "A009");
		prediction.put("score", null);
		modelResponse.add(prediction);
		when(sapBertPredictor.predict("Patient has cholera.", 20)).thenReturn(modelResponse);
		
		Concept cholera_concept = Context.getConceptService().getConcept(1000);
		ICDSearchResult fakeResult_cholera = new ICDSearchResult(cholera_concept, "SAME-AS", "A00.9");
		doReturn(fakeResult_cholera).when(serviceImpl).getConceptByICD10Code("A00.9");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has cholera.", "selection");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("A00.9", results.get(0).getIcdCode());
		Assert.assertNull(results.get(0).getDescription());
		Assert.assertNull(results.get(0).getConfidence());
		Assert.assertEquals("SAME-AS", results.get(0).getMappingType());
		Assert.assertNotNull(results.get(0).getConcept());
		Assert.assertEquals(cholera_concept, results.get(0).getConcept());
		
		verify(hierarchicalPredictor, never()).predict("Patient has unspecified infectious agent.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldNotAddTrailingDotIfCodeIsThreeCharacters() throws Exception {
		// Stub the model response
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		Map<String, Object> prediction = new HashMap<String, Object>();
		prediction.put("code", "B50");
		prediction.put("description", "Plasmodium falciparum malaria");
		modelResponse.add(prediction);
		when(sapBertPredictor.predict("Patient has malaria.", 20)).thenReturn(modelResponse);
		
		Concept malaria_concept = Context.getConceptService().getConcept(1001);
		ICDSearchResult fakeResult_cholera = new ICDSearchResult(malaria_concept, "NARROWER-THAN", "B50");
		doReturn(fakeResult_cholera).when(serviceImpl).getConceptByICD10Code("B50");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has malaria.", "selection");
		
		Assert.assertNotNull(results);
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("B50", results.get(0).getIcdCode());
		Assert.assertEquals("Plasmodium falciparum malaria", results.get(0).getDescription());
		Assert.assertEquals("NARROWER-THAN", results.get(0).getMappingType());
		Assert.assertNotNull(results.get(0).getConcept());
		Assert.assertEquals(malaria_concept, results.get(0).getConcept());
		
		verify(hierarchicalPredictor, never()).predict("Patient has malaria.", 20);
	}
	
	@Test
	public void getPredictionsFromNote_shouldNotModifyCodeThatAlreadyHasDot() throws Exception {
		List<Map<String, Object>> modelResponse = new ArrayList<Map<String, Object>>();
		Map<String, Object> prediction = new HashMap<String, Object>();
		prediction.put("code", "A00.9"); // already has dot
		prediction.put("description", "Unspecified Cholera");
		modelResponse.add(prediction);
		when(sapBertPredictor.predict("Patient has cholera.", 20)).thenReturn(modelResponse);
		
		doReturn(null).when(serviceImpl).getConceptByICD10Code("A00.9");
		
		List<ICDSearchResult> results = serviceImpl.getPredictionsFromNote("Patient has cholera.", "selection");
		
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("A00.9", results.get(0).getIcdCode()); // must not be "A00..9"
	}
}
