package org.openmrs.module.icdhelper.page.controller;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.ModuleException;
import org.openmrs.module.icdhelper.api.ICDHelperService;
import org.openmrs.module.icdhelper.api.ICDSearchResult;
import org.openmrs.ui.framework.page.PageModel;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Page controller for the ICD Helper page.
 * <p>
 * Handles GET and POST requests for the ICD-10 coding assistant UI, accessible at:
 * {@code /openmrs/icdhelper/icdhelper.page?patientId=<uuid>&visitId=<uuid>}
 * </p>
 * <p>
 * Supports two POST actions:
 * <ul>
 * <li>{@code predict}: passes the entire clinical note or a text selection to the embedded AI
 * models and populates the model with ICD-10 code suggestions.</li>
 * <li>{@code save}: persists the user-selected diagnoses to the patient encounter via
 * {@link ICDHelperService#saveSelectionToEncounter}.</li>
 * </ul>
 * </p>
 */
public class IcdhelperPageController {
	
	protected final Log log = LogFactory.getLog(getClass());
	
	/** Lazily initialized; replaceable via {@link #setICDHelperService} for testing. */
	ICDHelperService icdHelperService;
	
	/**
	 * Returns the {@link ICDHelperService}, initializing it lazily from the Spring context on first
	 * call.
	 */
	private ICDHelperService getICDHelperService() {
		if (icdHelperService == null) {
			icdHelperService = Context.getService(ICDHelperService.class);
		}
		return icdHelperService;
	}
	
	/** Setter for dependency injection in tests. */
	public void setICDHelperService(ICDHelperService icdHelperService) {
		this.icdHelperService = icdHelperService;
	}
	
	/**
	 * Helper method that populates the {@link PageModel} with attributes shared across GET and all
	 * POST actions.
	 * <p>
	 * If a {@code lockedNoteUuid} is provided, the full text of the corresponding visit note
	 * observation is fetched and added to the model as {@code fullNote}, so that the selected note
	 * remains displayed after a predict-on-selection POST. Note: this requires a live
	 * {@link Context} and is not exercised in unit tests.
	 * </p>
	 * Model attributes set:
	 * <ul>
	 * <li>{@code patient}, {@code visit} — passed through from request params</li>
	 * <li>{@code clinicalNoteParam} — the text sent for prediction (empty string if null)</li>
	 * <li>{@code fullNote} — full text of the locked visit note, if available</li>
	 * <li>{@code icdResults} — empty list (overwritten by predict action if successful)</li>
	 * <li>{@code error} — null (overwritten on validation failure or exception)</li>
	 * <li>{@code successMessage} — empty string (overwritten on successful save)</li>
	 * <li>{@code visitNotes} — all visit note observations for this visit, newest first</li>
	 * <li>{@code selectedNoteUuid}, {@code lockedNoteUuid} — passed through from request params</li>
	 * </ul>
	 */
	private void initializeModel(PageModel model, Patient patient, Visit visit, String clinicalNote,
	        String selectedNoteUuid, String lockedNoteUuid) {
		ICDHelperService icdService = getICDHelperService();
		
		model.addAttribute("patient", patient);
		model.addAttribute("visit", visit);
		model.addAttribute("clinicalNoteParam", clinicalNote != null ? clinicalNote : "");
		
		// Re-fetch the full note text so it stays displayed after a selection-mode predict POST.
		// Requires a live Context; wrapped defensively since this is not available in unit tests.
		if (lockedNoteUuid != null && !lockedNoteUuid.trim().isEmpty()) {
			try {
				Obs lockedObs = Context.getObsService().getObsByUuid(lockedNoteUuid);
				if (lockedObs != null) {
					model.addAttribute("fullNote", lockedObs.getValueText().trim());
				}
			}
			catch (Exception e) {
				log.debug("Could not fetch full note text for uuid: " + lockedNoteUuid);
			}
		}
		model.addAttribute("icdResults", new ArrayList<ICDSearchResult>());
		model.addAttribute("error", null);
		model.addAttribute("selectedNoteUuid", selectedNoteUuid);
		model.addAttribute("visitNotes", icdService.getVisitNotes(patient, visit));
		model.addAttribute("lockedNoteUuid", lockedNoteUuid);
		model.addAttribute("successMessage", "");
	}
	
	/**
	 * Helper method to call the prediction models and to populate {@code icdResults}, if
	 * {@code clinicalNote} is non-empty. Handles {@link APIException} and
	 * {@link IllegalArgumentException} by setting {@code error} in the model.
	 * <p>
	 * Extracted as a shared helper because both the {@code predict} and post-save branches need
	 * identical prediction-and-error-handling logic.
	 * </p>
	 */
	private void runPredictionIfNotePresent(ICDHelperService icdService, String clinicalNote, String mode, PageModel model) {
		if (clinicalNote == null || clinicalNote.trim().isEmpty()) {
			model.addAttribute("error", "Select or write a clinical note to get ICD-10 code suggestions.");
			return;
		}
		
		if (clinicalNote.length() > 20000) {
			model.addAttribute("error", "The clinical note is too long for the AI model.");
			return;
		}
		
		try {
			List<ICDSearchResult> results = icdService.getPredictionsFromNote(clinicalNote, mode);
			model.addAttribute("icdResults", results);
		}
		catch (ModuleException e) {
			log.error("Prediction Error: " + e.getClass().getName() + " - " + e.getMessage());
			model.addAttribute("error", "Failed ONNX inference (ModuleException). Details in logs.");
		}
		catch (IllegalStateException e) {
			log.error("Prediction Error: " + e.getClass().getName() + " - " + e.getMessage());
			model.addAttribute("error", "Failed ONNX inference (IllegalStateException). Details in logs.");
		}
		catch (IllegalArgumentException e) {
			log.error("Prediction Error: " + e.getClass().getName() + " - " + e.getMessage());
			model.addAttribute("error", "Bad arguments sent to prediction service.");
		}
	}
	
	/**
	 * Handles GET requests. Initializes the page model with empty defaults ready for the clinician
	 * to select a visit note.
	 */
	public void get(@RequestParam(value = "patientId") Patient patient, @RequestParam(value = "visitId") Visit visit,
	        PageModel model) {
		initializeModel(model, patient, visit, null, null, null);
	}
	
	/**
	 * Handles POST requests, dispatching on the {@code action} parameter.
	 * <p>
	 * <strong>action=predict:</strong> validates the clinical note, notably ensuring it is not
	 * greater than 20000 characters, calls the AI prediction service via
	 * {@link ICDHelperService#getPredictionsFromNote}, and populates {@code icdResults} in the
	 * model. Sets {@code error} on validation failure or service exception.
	 * </p>
	 * <p>
	 * <strong>action=save:</strong> validates that a selection and a locked note UUID are present, and
	 * delegates to {@link ICDHelperService#saveSelectionToEncounter}. Sets
	 * {@code successMessage} if the method returned {@code "Success"}, else sets {@code error} with the returned
	 * error details. If a clinical note is also present, re-runs prediction so suggestions remain visible after saving.
	 * </p>
	 * Sets {@code error} if {@code action} is neither {@code "predict"} or {@code "save"}
	 */
	public void post(@RequestParam(value = "patientId") Patient patient, @RequestParam(value = "visitId") Visit visit,
	        @RequestParam(value = "action") String action,
	        @RequestParam(value = "selectedNoteUuid", required = false) String selectedNoteUuid,
	        @RequestParam(value = "analysisMode", defaultValue = "full") String mode,
	        @RequestParam(value = "selectedResults", required = false) List<String> selectedResults,
	        @RequestParam(value = "lockedNoteUuid", required = false) String lockedNoteUuid,
	        @RequestParam(value = "clinicalNote", required = false) String clinicalNote, PageModel model) {
		
		initializeModel(model, patient, visit, clinicalNote, selectedNoteUuid, lockedNoteUuid);
		ICDHelperService icdService = getICDHelperService();
		
		if (action.equals("predict")) {
			runPredictionIfNotePresent(icdService, clinicalNote, mode, model);
			
		} else if (action.equals("save")) {
			if (selectedResults == null || selectedResults.isEmpty()) {
				model.addAttribute("error", "Select some concepts to be saved.");
				return;
			}
			
			if (lockedNoteUuid == null || lockedNoteUuid.trim().isEmpty()) {
				model.addAttribute("error", "No selected visit.");
				return;
			}
			
			try {
				log.info("Selected results: " + selectedResults);
				String saveStatus = icdService.saveSelectionToEncounter(patient, lockedNoteUuid, selectedResults);
				if ("Success".equals(saveStatus)) {
					model.addAttribute("successMessage", "Diagnoses successfully saved to the patient record.");
				} else {
					model.addAttribute("error", saveStatus);
				}
				
				// Re-run prediction so suggestions remain visible alongside the save confirmation.
				if (clinicalNote != null && !clinicalNote.isEmpty()) {
					runPredictionIfNotePresent(icdService, clinicalNote, mode, model);
				}
			}
			catch (Exception e) {
				log.error("Saving Error: " + e.getMessage());
				model.addAttribute("error", "Error saving the concepts.");
			}
		} else {
			model.addAttribute("error", "Unrecognized action.");
		}
		
	}
}
