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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.GlobalProperty;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.module.icdhelper.api.ICDHelperService;

import java.io.File;

/**
 * This class contains the logic that is run every time this module is either started or shutdown
 */
public class ICDHelperActivator extends BaseModuleActivator {
	
	private Log log = LogFactory.getLog(this.getClass());
	
	/**
	 * @see #started()
	 */
	@Override
	public void started() {
		log.info("Starting ICDHelper initialization");
		
		String subUrl = Context.getAdministrationService().getGlobalProperty("openconceptlab.subscriptionUrl");
		if (subUrl == null || subUrl.isEmpty()) {
			log.warn("ICD Coding Helper: No OCL Subscription found. Codes may be missing!");
		}
		
		AdministrationService adminService = Context.getAdministrationService();
		String modelDir = adminService.getGlobalProperty("icdhelper.modelDirectory");
		
		if (modelDir == null || modelDir.trim().isEmpty()) {
			// Fallback to a subdirectory of the standard OpenMRS data directory
			String dataDir = OpenmrsUtil.getApplicationDataDirectory();
			modelDir = dataDir + File.separator + "icdhelper" + File.separator + "models";
			log.warn("Global property 'icdhelper.modelDirectory' not set, " + "defaulting to: " + modelDir);
			
			// Persist the default so it's visible and editable in the admin panel
			GlobalProperty gp = adminService.getGlobalPropertyObject("icdhelper.modelDirectory");
			if (gp == null) {
				gp = new GlobalProperty("icdhelper.modelDirectory", modelDir,
				        "Directory containing ICD Helper ONNX model files");
			} else {
				gp.setPropertyValue(modelDir);
			}
			adminService.saveGlobalProperty(gp);
		}
		
		Context.getService(ICDHelperService.class).initializeModels(modelDir);
		
		this.validateRequiredConcepts();
	}
	
	@Override
	public void stopped() {
		log.info("Shutting down ICDHelper");
		
		Context.getService(ICDHelperService.class).shutdownModels();
	}
	
	/**
	 * To be called on startup of module, verifies if the hardcoded concepts needed for saving and
	 * retrieving the notes are present in the concept dictionary, if not log an error so
	 * administrators are aware
	 */
	private void validateRequiredConcepts() {
		String[] requiredUuids = { ICDHelperConfig.DIAGNOSIS_CODED_CONCEPT_UUID,
		        ICDHelperConfig.DIAGNOSIS_NON_CODED_CONCEPT_UUID, ICDHelperConfig.VISIT_NOTE_CONCEPT_UUID };
		String[] conceptNames = { "Diagnosis Coded (1284)", "Diagnosis Non-coded (161602)", "Visit Note (162169)" };
		
		boolean allPresent = true;
		for (int i = 0; i < requiredUuids.length; i++) {
			Concept c = Context.getConceptService().getConceptByUuid(requiredUuids[i]);
			if (c == null) {
				log.error("ICDHELPER: Required concept is missing: " + conceptNames[i]
				        + ". Install CIEL dictionary from https://www.openconceptlab.org/");
				allPresent = false;
			}
		}
		if (allPresent) {
			log.info("ICDHELPER: All required concepts present.");
		}
	}
	
}
