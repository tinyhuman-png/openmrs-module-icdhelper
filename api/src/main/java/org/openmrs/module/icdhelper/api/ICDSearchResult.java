package org.openmrs.module.icdhelper.api;

import org.openmrs.Concept;

/**
 * Represents a single ICD-10-CM diagnosis suggestion returned by the AI predictors.
 * <p>
 * This DTO aggregates the raw prediction from the ONNX models (the ICD-10-CM code,
 * description, and confidence score) with the local OpenMRS dictionary resolution
 * (the matched Concept and the type of mapping).
 * </p>
 */
public class ICDSearchResult {
	
	private Concept concept;
	
	private String mappingType; // "SAME-AS", "NARROWER-THAN", "BROADER-THAN", "NO MAPPING"
	
	private String icdCode;
	
	private String description;
	
	private Double confidence;
	
	public ICDSearchResult(Concept concept, String mappingType, String code) {
		this.concept = concept;
		this.mappingType = mappingType;
		this.icdCode = code;
	}

	/**
	 * @return The OpenMRS Concept that matches the ICD-10-CM code, or null if the
	 * code does not exist in the local CIEL dictionary.
	 */
	public Concept getConcept() {
		return this.concept;
	}
	
	public void setConcept(Concept concept) {
		this.concept = concept;
	}

	/**
	 * @return The type of relationship between the Concept and the ICD-10-CM code.
	 * Expected values: "SAME-AS", "NARROWER-THAN", "BROADER-THAN", or "NO MAPPING".
	 */
	public String getMappingType() {
		return this.mappingType;
	}
	
	public void setMappingType(String mappingType) {
		this.mappingType = mappingType;
	}
	
	public boolean isExactMatch() {
		return "SAME-AS".equalsIgnoreCase(this.mappingType);
	}
	
	public String getIcdCode() {
		return this.icdCode;
	}
	
	public void setIcdCode(String icdCode) {
		this.icdCode = icdCode;
	}
	
	public String getDescription() {
		return this.description;
	}
	
	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * @return The model's confidence score for this prediction (0.0 to 1.0).
	 */
	public Double getConfidence() {
		return this.confidence;
	}
	
	public void setConfidence(Double confidence) {
		this.confidence = confidence;
	}
}
