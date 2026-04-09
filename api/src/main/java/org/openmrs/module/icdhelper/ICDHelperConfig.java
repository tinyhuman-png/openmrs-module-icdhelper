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

import org.springframework.stereotype.Component;

/**
 * Contains global configuration constants for the ICD Helper module.
 */
@Component("icdhelper.ICDHelperConfig")
public class ICDHelperConfig {
	
	public final static String MODULE_PRIVILEGE = "ICDHelper Privilege";
	
	public final static String DIAGNOSIS_CODED_CONCEPT_UUID = "1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	public final static String DIAGNOSIS_NON_CODED_CONCEPT_UUID = "160221AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	public final static String VISIT_NOTE_CONCEPT_UUID = "162169AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
}
