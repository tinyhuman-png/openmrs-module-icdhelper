/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.icdhelper.api.predictors;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import java.io.File;
import java.io.IOException;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Predicts ICD-10_CM codes from a full clinical note using a hierarchical BERT model.
 * <p>
 * Intended for the {@code "full"} analysis mode, where the entire clinical note is analyzed. Long
 * notes are split into overlapping chunks of 512 tokens (stride 256, max 12 chunks), processed
 * jointly by the model, and the output is a multi-label probability distribution over all ICD-10_CM
 * classes.
 * </p>
 * <p>
 * Lifecycle: call {@link #initialize(String)} once at module startup (from
 * {@code ICDHelperServiceImpl.onStartup()}), and {@link #close()} at shutdown.
 * </p>
 */
public class HierarchicalPredictor {
	
	private static final Log log = LogFactory.getLog(HierarchicalPredictor.class);
	
	/** Max token length (must match Python export (max_length=512)). */
	private static final int SEQ_LEN = 512;
	
	/**
	 * Stride between chunk start positions (must match Python export (stride=256)). A stride of 256
	 * with seq_len of 512 gives 50% overlap between adjacent chunks, ensuring no clinical
	 * information is lost at chunk boundaries.
	 */
	private static final int STRIDE = 256;
	
	/** Maximum number of chunks fed to the model (must match Python export (max_chunks=12)). */
	private static final int MAX_CHUNKS = 12;
	
	private static final long PAD_TOKEN_ID = 0L;
	
	private OrtEnvironment env;
	
	private OrtSession session;
	
	private BertWordPieceTokenizer tokenizer;
	
	/**
	 * ICD-10_CM codes indexed by model output position. {@code mlbClasses[i]} is the ICD-10-CM code
	 * corresponding to logit index {@code i}. Loaded from {@code mlb_full_classes.json}.
	 */
	private String[] mlbClasses;
	
	/**
	 * Maps ICD-10-CM code to a human-readable description. Built at startup from
	 * {@code mappings.json}
	 */
	private Map<String, String> codeToDescription;
	
	/**
	 * Initializes the ONNX session, tokenizer, MLB class index, and description map.
	 * <p>
	 * Expected files under {@code modelDir}:
	 * <ul>
	 * <li>{@code hierarchical.onnx} — exported hierarchical BERT model</li>
	 * <li>{@code vocab.txt} — standard HuggingFace BERT vocabulary file where each line is one
	 * token and the line number (0-indexed) is the token ID</li>
	 * <li>{@code mlb_full_classes.json} — JSON array of ICD-10_CM codes in model output order,
	 * exported from Python via {@code json.dump(mlb.classes_.tolist())}</li>
	 * <li>{@code mappings.json} — ICD-10_CM code/description index with fields {@code "ids"} and
	 * {@code "descriptions"}</li>
	 * </ul>
	 * </p>
	 * 
	 * @param modelDir path to the directory containing all model files
	 * @throws Exception if any file is missing, malformed, or the ONNX session fails to load
	 */
	public void initialize(String modelDir) throws Exception {
		log.info("Initializing HierarchicalPredictor from: " + modelDir);
		
		env = OrtEnvironment.getEnvironment();
		session = env.createSession(modelDir + "/hierarchical.onnx");
		log.info("Hierarchical BERT ONNX session loaded");
		
		tokenizer = BertWordPieceTokenizer.load(modelDir + "/vocab.txt");
		log.info("Tokenizer loaded");
		
		loadMlbClasses(modelDir + "/mlb_full_classes.json");
		log.info("MLB classes loaded: " + mlbClasses.length + " ICD-10 codes");
		
		loadCodeToDescription(modelDir + "/mappings.json");
		log.info("Mappings loaded: " + codeToDescription.size() + " descriptions");
	}
	
	/**
	 * Predicts the top-k ICD-10-CM codes for a full clinical note.
	 * <p>
	 * The note is tokenized without truncation, split into overlapping 512-token chunks, and fed to
	 * the model as a single batched input. The model outputs per-class logits which are converted
	 * to probabilities via sigmoid.
	 * </p>
	 * 
	 * @param text the full clinical note text
	 * @param k the number of top results to return
	 * @return a list of up to {@code k} results, each containing {@code "code"},
	 *         {@code "description"}, and {@code "score"} (sigmoid probability, between 0 and 1,
	 *         higher is better); never null, may be smaller than {@code k} if the model has fewer
	 *         classes
	 * @throws IllegalStateException if {@link #initialize} has not been called
	 * @throws OrtException if ONNX inference fails
	 */
	public List<Map<String, Object>> predict(String text, int k) throws OrtException {
		if (session == null) {
			throw new IllegalStateException("HierarchicalPredictor is not initialized. Call initialize() first.");
		}
		
		// Tokenize without truncation
		long[] allIds = tokenizer.tokenize(text);
		
		// Chunk with stride:
		// chunks = [input_ids[i:i+512] for i in range(0, len, 256)][:12]
		// Each chunk is padded to exactly SEQ_LEN=512 tokens
		int nChunks = 0;
		long[][] chunks = new long[MAX_CHUNKS][SEQ_LEN];
		
		for (int start = 0; start < allIds.length && nChunks < MAX_CHUNKS; start += STRIDE) {
			for (int t = 0; t < SEQ_LEN; t++) {
				int srcIdx = start + t;
				chunks[nChunks][t] = (srcIdx < allIds.length) ? allIds[srcIdx] : PAD_TOKEN_ID;
			}
			nChunks++;
		}
		
		// Build flat input buffers for the 3D tensors [batch=1, nChunks, SEQ_LEN].
		// ORT expects a flat LongBuffer laid out as:
		// [chunk0_token0, chunk0_token1, ..., chunk1_token0, chunk1_token1, ...]
		long[] inputIds = new long[nChunks * SEQ_LEN];
		long[] attentionMask = new long[nChunks * SEQ_LEN];
		
		for (int c = 0; c < nChunks; c++) {
			for (int t = 0; t < SEQ_LEN; t++) {
				int pos = c * SEQ_LEN + t;
				inputIds[pos] = chunks[c][t];
				// mask = 1 for real tokens, 0 for padding
				attentionMask[pos] = (chunks[c][t] != PAD_TOKEN_ID) ? 1L : 0L;
			}
		}
		
		// Create 3D tensors and run inference
		long[] shape = { 1, nChunks, SEQ_LEN };
		OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape);
		OnnxTensor maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape);
		
		Map<String, OnnxTensor> inputs = new HashMap<String, OnnxTensor>();
		inputs.put("input_ids", idsTensor);
		inputs.put("attention_mask", maskTensor);
		
		OrtSession.Result result = session.run(inputs);
		
		// Extract logits [1, n_classes] and apply sigmoid (not softmax because each class is independent)
		float[][] logits = (float[][]) result.get("logits").get().getValue();
		float[] probs = sigmoid(logits[0]);
		
		// Cleanup tensors
		idsTensor.close();
		maskTensor.close();
		result.close();
		
		// Top-k by probability & format results
		int[] topIndices = topK(probs, k);
		List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
		for (int idx : topIndices) {
			String code = mlbClasses[idx];
			
			Map<String, Object> mapResult = new HashMap<String, Object>();
			mapResult.put("code", code);
			// fallback to the code itself if no description found
			mapResult.put("description", codeToDescription.containsKey(code) ? codeToDescription.get(code) : code);
			mapResult.put("score", probs[idx]);
			
			results.add(mapResult);
		}
		return results;
	}
	
	/**
	 * Releases the ONNX session. Call from {@code ICDHelperServiceImpl.shutdownModels()}.
	 */
	public void close() {
		try {
			if (session != null) {
				session.close();
			}
			if (env != null) {
				env.close();
			}
		}
		catch (OrtException e) {
			log.error("Error closing HierarchicalPredictor resources", e);
		}
	}
	
	// ---- Private helpers ----
	
	/**
	 * Loads the MLB class array from a JSON file. The file is a plain JSON array of ICD-10_CM codes
	 * strings, e.g. {@code ["A00.1", "A00.9", ...]}.
	 */
	private void loadMlbClasses(String path) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		List<String> classes = mapper.readValue(new File(path), new TypeReference<List<String>>() {});
		mlbClasses = classes.toArray(new String[0]);
	}
	
	/**
	 * Builds a code to description lookup map from mappings.json. The file has two parallel arrays:
	 * {@code "ids"} (ICD-10_CM codes) and {@code "descriptions"} (human-readable labels), where
	 * {@code ids[i]} corresponds to {@code descriptions[i]}.
	 */
	private void loadCodeToDescription(String path) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		Map<String, List<String>> raw = mapper.readValue(new File(path), new TypeReference<Map<String, List<String>>>() {});
		
		List<String> ids = raw.get("ids");
		List<String> descriptions = raw.get("descriptions");
		
		Map<String, String> result = new HashMap<String, String>(ids.size());
		for (int i = 0; i < ids.size(); i++) {
			result.put(ids.get(i), descriptions.get(i));
		}
		codeToDescription = result;
	}
	
	/** Element-wise sigmoid. Each output is independently in (0, 1). */
	private float[] sigmoid(float[] logits) {
		float[] out = new float[logits.length];
		for (int i = 0; i < logits.length; i++)
			out[i] = (float) (1.0 / (1.0 + Math.exp(-logits[i])));
		return out;
	}
	
	/**
	 * Returns the indices of the top-k highest values in descending order.
	 */
	private static int[] topK(float[] scores, int k) {
		int actualK = Math.min(k, scores.length);
		int[] result = new int[actualK];
		boolean[] used = new boolean[scores.length];
		
		for (int rank = 0; rank < actualK; rank++) {
			int best = -1;
			for (int i = 0; i < scores.length; i++) {
				if (!used[i] && (best == -1 || scores[i] > scores[best])) {
					best = i;
				}
			}
			used[best] = true;
			result[rank] = best;
		}
		return result;
	}
}
