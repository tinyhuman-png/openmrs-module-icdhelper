package org.openmrs.module.icdhelper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import org.openmrs.Concept;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.APIException;
import org.openmrs.module.ModuleException;
import org.openmrs.module.icdhelper.api.ICDHelperService;
import org.openmrs.module.icdhelper.api.ICDSearchResult;
import org.openmrs.module.icdhelper.page.controller.IcdhelperPageController;
import org.openmrs.ui.framework.page.PageModel;

public class ICDHelperPageControllerTest {
	
	IcdhelperPageController controller;
	
	@Mock
	ICDHelperService icdService;
	
	@Mock
	Patient patient;
	
	@Mock
	Visit visit;
	
	PageModel model;
	
	@Before
	public void setup() {
		MockitoAnnotations.initMocks(this);
		controller = new IcdhelperPageController();
		controller.setICDHelperService(icdService);
		model = new PageModel();
		
		// Default stub: getVisitNotes is called by initializeModel on every request
		when(icdService.getVisitNotes(any(Patient.class), any(Visit.class))).thenReturn(new ArrayList<Obs>());
	}
	
	// GET method
	@Test
	public void get_shouldInitializeModelWithEmptyDefaultsAndGetCorrectVisitNote() {
		List<Obs> visitNotes = new ArrayList<Obs>();
		Obs visitObs = new Obs(patient, new Concept(), new Date(), new Location());
		visitNotes.add(visitObs);
		
		when(icdService.getVisitNotes(any(Patient.class), any(Visit.class))).thenReturn(visitNotes);
		
		controller.get(patient, visit, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertNull(model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertEquals(1, ((List<?>) model.getAttribute("visitNotes")).size());
		Assert.assertEquals(visitObs, ((List<?>) model.getAttribute("visitNotes")).get(0));
		
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		// Make sure we called getVisitNotes() method
		verify(icdService).getVisitNotes(patient, visit);
	}
	
	@Test
	public void get_shouldInitializeModelWithEmptyDefaultsEvenWhenNoNote() {
		controller.get(patient, visit, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertNull(model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertTrue(((List<?>) model.getAttribute("visitNotes")).isEmpty());
		
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		// Make sure we called getVisitNotes() method
		verify(icdService).getVisitNotes(patient, visit);
	}
	
	@Test
	public void get_shouldInitializeModelWithEmptyDefaultsAndPassVisitNotesAsReturnedByService() {
		List<Obs> visitNotes = new ArrayList<Obs>();
		Obs visitObs3 = new Obs(3);
		visitNotes.add(visitObs3);
		Obs visitObs2 = new Obs(2);
		visitNotes.add(visitObs2);
		Obs visitObs1 = new Obs(1);
		visitNotes.add(visitObs1);
		
		when(icdService.getVisitNotes(any(Patient.class), any(Visit.class))).thenReturn(visitNotes);
		
		controller.get(patient, visit, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertNull(model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertEquals(3, ((List<?>) model.getAttribute("visitNotes")).size());
		Assert.assertEquals(visitObs3, ((List<?>) model.getAttribute("visitNotes")).get(0));
		Assert.assertEquals(visitObs2, ((List<?>) model.getAttribute("visitNotes")).get(1));
		Assert.assertEquals(visitObs1, ((List<?>) model.getAttribute("visitNotes")).get(2));
		
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		// Make sure we called getVisitNotes() method
		verify(icdService).getVisitNotes(patient, visit);
	}
	
	@Test
	public void get_shouldNotWorkWhenNullPatient() {
		controller.get(null, visit, model);
		
		Assert.assertNull(model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertNull(model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertTrue(((List<?>) model.getAttribute("visitNotes")).isEmpty());
		
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
	}
	
	// POST method -> predict action
	@Test
	public void post_predict_shouldSetIcdResultsOnSuccess_full() {
		String note = "Patient has cholera.";
		List<ICDSearchResult> fakeResults = new ArrayList<ICDSearchResult>();
		Concept concept_cholera = new Concept();
		fakeResults.add(new ICDSearchResult(concept_cholera, "SAME-AS", "A00.9"));
		Concept concept_fracture = new Concept();
		fakeResults.add(new ICDSearchResult(concept_fracture, "NARROWER-THAN", "S02.9"));
		when(icdService.getPredictionsFromNote(note, "full")).thenReturn(fakeResults);
		
		controller.post(patient, visit, "predict", "obs-uuid-300", "full", null, null, note, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals(note, model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertEquals(fakeResults, model.getAttribute("icdResults"));
		Assert.assertNull(model.getAttribute("error"));
		Assert.assertEquals("obs-uuid-300", model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService).getPredictionsFromNote(note, "full");
		verify(icdService).getVisitNotes(patient, visit);
	}
	
	@Test
	public void post_predict_shouldSetIcdResultsOnSuccess_selection() {
		String note = "Patient has cholera.";
		List<ICDSearchResult> fakeResults = new ArrayList<ICDSearchResult>();
		Concept concept_cholera = new Concept();
		fakeResults.add(new ICDSearchResult(concept_cholera, "SAME-AS", "A00.9"));
		Concept concept_fracture = new Concept();
		fakeResults.add(new ICDSearchResult(concept_fracture, "NARROWER-THAN", "S02.9"));
		when(icdService.getPredictionsFromNote(note, "selection")).thenReturn(fakeResults);
		
		controller.post(patient, visit, "predict", "obs-uuid-300", "selection", null, null, note, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals(note, model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertEquals(fakeResults, model.getAttribute("icdResults"));
		Assert.assertNull(model.getAttribute("error"));
		Assert.assertEquals("obs-uuid-300", model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService).getPredictionsFromNote(note, "selection");
		verify(icdService).getVisitNotes(patient, visit);
	}
	
	@Test
	public void post_predict_shouldSetErrorWhenClinicalNoteIsNull() {
		controller.post(patient, visit, "predict", null, "full", null, null, null, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Select or write a clinical note to get ICD-10 code suggestions.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService, never()).getPredictionsFromNote(anyString(), anyString());
		verify(icdService).getVisitNotes(patient, visit);
	}
	
	@Test
	public void post_predict_shouldSetErrorWhenClinicalNoteIsBlank() {
		controller.post(patient, visit, "predict", null, null, null, null, "   ", model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("   ", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Select or write a clinical note to get ICD-10 code suggestions.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService, never()).getPredictionsFromNote(anyString(), anyString());
		verify(icdService).getVisitNotes(patient, visit);
	}
	
	@Test
	public void post_predict_shouldSetErrorWhenClinicalNoteIsTooLong() {
		String longNote = String.format("%0" + 20001 + "d", 0).replace("0", "a");
		
		controller.post(patient, visit, "predict", null, "full", null, null, longNote, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals(longNote, model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("The clinical note is too long for the AI model.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService, never()).getPredictionsFromNote(anyString(), eq("full"));
		verify(icdService).getVisitNotes(patient, visit);
	}
	
	@Test
	public void post_predict_shouldAcceptNoteOfExactly20000Characters() {
		String noteAtLimit = String.format("%0" + 20000 + "d", 0).replace("0", "a");
		List<ICDSearchResult> fakeResults = new ArrayList<ICDSearchResult>();
		when(icdService.getPredictionsFromNote(noteAtLimit, "full")).thenReturn(fakeResults);
		
		controller.post(patient, visit, "predict", null, "full", null, null, noteAtLimit, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals(noteAtLimit, model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertEquals(fakeResults, model.getAttribute("icdResults"));
		Assert.assertNull(model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService).getVisitNotes(patient, visit);
		verify(icdService).getPredictionsFromNote(noteAtLimit, "full");
	}
	
	@Test
	public void post_predict_shouldSetErrorWhenServiceThrowsModuleException() {
		String note = "Patient has cholera.";
		when(icdService.getPredictionsFromNote(note, "full")).thenThrow(
		    new ModuleException("ICD prediction inference failed."));
		
		controller.post(patient, visit, "predict", null, "full", null, null, note, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals(note, model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Failed ONNX inference (ModuleException). Details in logs.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
	}
	
	@Test
	public void post_predict_shouldSetErrorWhenServiceThrowsIllegalStateException() {
		String note = "Patient has cholera.";
		when(icdService.getPredictionsFromNote(note, "full")).thenThrow(
		    new IllegalStateException("ICD prediction inference failed."));
		
		controller.post(patient, visit, "predict", null, "full", null, null, note, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals(note, model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Failed ONNX inference (IllegalStateException). Details in logs.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
	}
	
	@Test
	public void post_predict_shouldSetErrorWhenServiceThrowsIllegalArgumentException() {
		String note = "Patient has cholera.";
		when(icdService.getPredictionsFromNote(note, "complete_note")).thenThrow(
		    new IllegalArgumentException("Mode must be either 'selection' or 'full'"));
		
		controller.post(patient, visit, "predict", null, "complete_note", null, null, note, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals(note, model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Bad arguments sent to prediction service.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
	}
	
	//POST method -> save action
	@Test
	public void post_save_shouldSetSuccessMessageOnSuccess() {
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		String lockedNoteUuid = "obs-uuid-300";
		when(icdService.saveSelectionToEncounter(eq(patient), eq(lockedNoteUuid), eq(selection))).thenReturn("Success");
		
		controller.post(patient, visit, "save", null, null, selection, lockedNoteUuid, null, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertNull(model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertEquals(lockedNoteUuid, model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("Diagnoses successfully saved to the patient record.", model.getAttribute("successMessage"));
		
		verify(icdService).saveSelectionToEncounter(patient, lockedNoteUuid, selection);
	}
	
	@Test
	public void post_save_shouldSetErrorWhenSelectedResultsIsNull() {
		controller.post(patient, visit, "save", null, "full", null, "obs-uuid-300", null, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Select some concepts to be saved.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertEquals("obs-uuid-300", model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService, never()).saveSelectionToEncounter((Patient) any(), anyString(), (List<String>) any());
	}
	
	@Test
	public void post_save_shouldSetErrorWhenSelectedResultsIsEmpty() {
		controller.post(patient, visit, "save", null, "full", new ArrayList<String>(), "obs-uuid-300", null, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Select some concepts to be saved.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertEquals("obs-uuid-300", model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService, never()).saveSelectionToEncounter((Patient) any(), anyString(), (List<String>) any());
	}
	
	@Test
	public void post_save_shouldSetErrorWhenLockedNoteUuidIsNull() {
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		
		controller.post(patient, visit, "save", null, null, selection, null, null, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("No selected visit.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService, never()).saveSelectionToEncounter((Patient) any(), anyString(), (List<String>) any());
	}
	
	@Test
	public void post_save_shouldSetErrorWhenLockedNoteUuidIsEmpty() {
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		
		controller.post(patient, visit, "save", null, null, selection, "   ", null, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("No selected visit.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertEquals("   ", model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService, never()).saveSelectionToEncounter((Patient) any(), anyString(), (List<String>) any());
	}
	
	@Test
	public void post_save_shouldSetErrorWhenServiceThrowsException() {
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		String lockedNoteUuid = "obs-uuid-300";
		when(icdService.saveSelectionToEncounter(eq(patient), eq(lockedNoteUuid), eq(selection))).thenThrow(
		    new IllegalArgumentException("Given visit note observation does not belong to the given patient."));
		
		controller.post(patient, visit, "save", null, null, selection, lockedNoteUuid, null, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Error saving the concepts.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertEquals(lockedNoteUuid, model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
	}
	
	@Test
	public void post_save_shouldSetIcdResultsIfClinicalNoteIsPresent() {
		String note = "Patient has cholera.";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		String lockedNoteUuid = "obs-uuid-300";
		
		when(icdService.saveSelectionToEncounter(eq(patient), eq(lockedNoteUuid), eq(selection))).thenReturn("Success");
		
		List<ICDSearchResult> fakeResults = new ArrayList<ICDSearchResult>();
		Concept concept_cholera = new Concept();
		fakeResults.add(new ICDSearchResult(concept_cholera, "SAME-AS", "A00.9"));
		when(icdService.getPredictionsFromNote(eq(note), eq("full"))).thenReturn(fakeResults);
		
		controller.post(patient, visit, "save", null, "full", selection, lockedNoteUuid, note, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals(note, model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertEquals(1, ((List<?>) model.getAttribute("icdResults")).size());
		Assert.assertEquals(fakeResults, model.getAttribute("icdResults"));
		Assert.assertNull(model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertEquals(lockedNoteUuid, model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("Diagnoses successfully saved to the patient record.", model.getAttribute("successMessage"));
		
		verify(icdService).saveSelectionToEncounter(patient, lockedNoteUuid, selection);
		verify(icdService).getPredictionsFromNote(note, "full");
	}
	
	@Test
	public void post_save_shouldSetErrorIfGetPredictionThrows() {
		String note = "Patient has cholera.";
		List<String> selection = new ArrayList<String>();
		selection.add("concept-uuid-1|A00.9");
		String lockedNoteUuid = "obs-uuid-300";
		
		when(icdService.saveSelectionToEncounter(eq(patient), eq(lockedNoteUuid), eq(selection))).thenReturn("Success");
		
		when(icdService.getPredictionsFromNote(eq(note), eq("full"))).thenThrow(new IllegalArgumentException());
		
		controller.post(patient, visit, "save", null, "full", selection, lockedNoteUuid, note, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals(note, model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Bad arguments sent to prediction service.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertEquals(lockedNoteUuid, model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("Diagnoses successfully saved to the patient record.", model.getAttribute("successMessage"));
		
		verify(icdService).saveSelectionToEncounter(patient, lockedNoteUuid, selection);
		verify(icdService).getPredictionsFromNote(note, "full");
	}
	
	@Test
	public void post_shouldSetErrorForUnrecognizedAction() {
		controller.post(patient, visit, "delete", null, null, null, null, null, model);
		
		Assert.assertEquals(patient, model.getAttribute("patient"));
		Assert.assertEquals(visit, model.getAttribute("visit"));
		Assert.assertEquals("", model.getAttribute("clinicalNoteParam"));
		Assert.assertNull(model.getAttribute("fullNote"));
		Assert.assertNotNull(model.getAttribute("icdResults"));
		Assert.assertTrue(((List<?>) model.getAttribute("icdResults")).isEmpty());
		Assert.assertEquals("Unrecognized action.", model.getAttribute("error"));
		Assert.assertNull(model.getAttribute("selectedNoteUuid"));
		Assert.assertNotNull(model.getAttribute("visitNotes"));
		Assert.assertNull(model.getAttribute("lockedNoteUuid"));
		Assert.assertEquals("", model.getAttribute("successMessage"));
		
		verify(icdService, never()).saveSelectionToEncounter((Patient) any(), anyString(), (List<String>) any());
		verify(icdService, never()).getPredictionsFromNote(anyString(), anyString());
	}
}
