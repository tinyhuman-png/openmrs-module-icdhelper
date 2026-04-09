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
import org.codehaus.jackson.type.TypeReference;
import org.codehaus.jackson.map.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.*;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.openmrs.module.icdhelper.api.predictors.BertWordPieceTokenizer.PAD_TOKEN_ID;

/**
 * Predicts ICD-10-CM codes from a short clinical text excerpt using SapBERT.
 * <p>
 * Intended for the {@code "selection"} analysis mode, where the user highlights a short passage
 * from a clinical note.
 * </p>
 * <p>
 * At query time:
 * <ol>
 * <li>The input text is tokenized (WordPiece, max 50 tokens) and padded.</li>
 * <li>Tokens are fed to the SapBERT ONNX model to produce a 768-dim CLS embedding.</li>
 * <li>The embedding is L2-normalized and compared against the pre-computed ICD-10 concept
 * embeddings via brute-force cosine similarity.</li>
 * <li>The top-k closest ICD-10 codes are returned.</li>
 * </ol>
 * </p>
 * <p>
 * Lifecycle: call {@link #initialize(String)} once at module startup (from
 * {@code ICDHelperServiceImpl.onStartup()}), and {@link #close()} at shutdown.
 * </p>
 */
public class SapBertPredictor {
	
	private static final Log log = LogFactory.getLog(SapBertPredictor.class);
	
	/** Max token length, must match Python export (max_length=50) */
	private static final int SEQ_LEN = 50;
	
	/** BERT hidden size (fixed for all BERT-base variants) */
	private static final int HIDDEN_DIM = 768;
	
	private OrtEnvironment env;
	
	private OrtSession session;
	
	private BertWordPieceTokenizer tokenizer;
	
	/**
	 * Pre-computed L2-normalized embeddings for every ICD-10-CM concept in the index. Shape: [#
	 * Concepts][HIDDEN_DIM]. Loaded once at startup from embeddings.bin and embeddings_meta.json.
	 * All vectors are already normalized, so cosine similarity reduces to a dot product
	 */
	private float[][] conceptEmbeddings;
	
	/**
	 * ICD-10-CM codes parallel to {@link #conceptEmbeddings}. {@code icdCodes.get(i)} is the code
	 * for {@code conceptEmbeddings[i]}.
	 */
	private List<String> icdCodes;
	
	/**
	 * ICD-10_CM descriptions parallel to {@link #conceptEmbeddings}. {@code descriptions.get(i)} is
	 * the description for {@code conceptEmbeddings[i]}.
	 */
	private List<String> descriptions;
	
	/**
	 * Initializes the ONNX session, tokenizer, and concept embedding index.
	 * <p>
	 * Expected files under {@code modelDir}:
	 * <ul>
	 * <li>{@code sapbert.onnx} — exported SapBERT model</li>
	 * <li>{@code vocab.txt} — standard HuggingFace BERT vocabulary file where each line is one
	 * token and the line number (0-indexed) is the token ID</li>
	 * <li>{@code embeddings.bin} — pre-computed concept embeddings (raw float32, little-endian, no
	 * header)</li>
	 * <li>{@code embeddings_meta.json} — metadata for pre-computed concept embeddings (the codes
	 * and descriptions)</li>
	 * </ul>
	 * </p>
	 * 
	 * @param modelDir path to the directory containing all model files
	 * @throws Exception if any file is missing, malformed, or the ONNX session fails to load
	 */
	public void initialize(String modelDir) throws Exception {
		log.info("Initializing SapBertPredictor from: " + modelDir);
		
		env = OrtEnvironment.getEnvironment();
		session = env.createSession(modelDir + "/sapbert.onnx");
		log.info("SapBERT ONNX session loaded");
		
		tokenizer = BertWordPieceTokenizer.load(modelDir + "/vocab.txt");
		log.info("Tokenizer loaded");
		
		loadEmbeddingIndex(modelDir);
		log.info("Embedding index loaded: " + icdCodes.size() + " concepts");
	}
	
	/**
	 * Predicts the top-k ICD-10-CM codes for a short clinical text query.
	 * 
	 * @param text the clinical text to encode; should be a short excerpt
	 * @param k the number of top results to return
	 * @return a list of up to {@code k} results, each containing {@code "code"},
	 *         {@code "description"}, and {@code "score"} (cosine similarity, between 0 and 1,
	 *         higher is better); never null, may be smaller than {@code k} if the index has fewer
	 *         concepts
	 * @throws IllegalStateException if {@link #initialize} has not been called
	 * @throws OrtException if ONNX inference fails
	 */
	public List<Map<String, Object>> predict(String text, int k) throws IllegalStateException, OrtException {
		if (session == null) {
			throw new IllegalStateException("SapBertPredictor is not initialized. Call initialize() first.");
		}
		
		// Tokenize and pad to SEQ_LEN
		long[] inputIds = padOrTruncate(tokenizer.tokenize(text), SEQ_LEN, 0L);
		long[] tokenTypeIds = new long[SEQ_LEN]; // all zeros, single sentence input
		long[] attentionMask = new long[SEQ_LEN];
		for (int i = 0; i < SEQ_LEN; i++) {
			attentionMask[i] = (inputIds[i] != PAD_TOKEN_ID) ? 1L : 0L;
		}
		
		// Build input tensors, shape = [batch=1, seq_len=50]
		long[] shape = { 1, SEQ_LEN };
		OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape);
		OnnxTensor maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape);
		OnnxTensor typesTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape);
		
		// Run ONNX inference
		Map<String, OnnxTensor> inputs = new HashMap<String, OnnxTensor>();
		inputs.put("input_ids", idsTensor);
		inputs.put("attention_mask", maskTensor);
		inputs.put("token_type_ids", typesTensor);
		
		OrtSession.Result result = session.run(inputs);
		
		// Extract and normalize the CLS token embedding
		// last_hidden_state shape: [1, 50, 768] and CLS is always at token index 0
		float[][][] hiddenState = (float[][][]) result.get("last_hidden_state").get().getValue();
		float[] queryEmbedding = hiddenState[0][0].clone(); // clone: don't mutate ORT's buffer
		normalizeL2(queryEmbedding);
		
		// Cleanup tensors
		idsTensor.close();
		maskTensor.close();
		typesTensor.close();
		result.close();
		
		// Brute-force cosine similarity search
		return topK(queryEmbedding, k);
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
			log.error("Error closing SapBertPredictor resources", e);
		}
	}
	
	// ---- Private helpers ----
	
	/**
	 * Loads the pre-computed ICD-10 concept embeddings from a binary file. Populates
	 * {@link #conceptEmbeddings}. Loads the details about code and description from a json file.
	 * Populates {@link #icdCodes}, and {@link #descriptions}.
	 */
	private void loadEmbeddingIndex(String modelDir) throws IOException {
		// Load metadata (codes + descriptions)
		ObjectMapper mapper = new ObjectMapper();
		List<Map<String, String>> metadata = mapper.readValue(new File(modelDir + "/embeddings_meta.json"),
		    new TypeReference<List<Map<String, String>>>() {});
		
		int n = metadata.size();
		icdCodes = new ArrayList<String>(n);
		descriptions = new ArrayList<String>(n);
		for (Map<String, String> entry : metadata) {
			icdCodes.add(entry.get("code"));
			descriptions.add(entry.get("description"));
		}
		
		conceptEmbeddings = new float[n][HIDDEN_DIM];
		DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(modelDir
		        + "/embeddings.bin")), 1024 * 1024));
		try {
			byte[] buf = new byte[4];
			for (int i = 0; i < n; i++) {
				for (int j = 0; j < HIDDEN_DIM; j++) {
					dis.readFully(buf);
					// Raw float32 is little-endian from numpy
					int bits = ((buf[3] & 0xFF) << 24) | ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
					conceptEmbeddings[i][j] = Float.intBitsToFloat(bits);
				}
			}
		}
		finally {
			dis.close();
		}
		log.info("Loaded " + n + " embeddings from binary file");
	}
	
	/**
	 * Returns the top-k concept indices by cosine similarity to the query embedding. Both the query
	 * and all concept embeddings must be L2-normalized before calling this.
	 */
	private List<Map<String, Object>> topK(float[] query, int k) {
		float[] scores = new float[conceptEmbeddings.length];
		for (int i = 0; i < conceptEmbeddings.length; i++) {
			scores[i] = dotProduct(query, conceptEmbeddings[i]);
		}
		
		// Partial selection sort for top-k, avoids sorting the full array
		int actualK = Math.min(k, scores.length);
		List<Map<String, Object>> results = new ArrayList<Map<String, Object>>(actualK);
		boolean[] used = new boolean[scores.length];
		
		for (int rank = 0; rank < actualK; rank++) {
			int bestIdx = -1;
			for (int i = 0; i < scores.length; i++) {
				if (!used[i] && (bestIdx == -1 || scores[i] > scores[bestIdx])) {
					bestIdx = i;
				}
			}
			used[bestIdx] = true;
			Map<String, Object> result = new HashMap<String, Object>();
			result.put("code", icdCodes.get(bestIdx));
			result.put("description", descriptions.get(bestIdx));
			result.put("score", scores[bestIdx]);
			results.add(result);
		}
		return results;
	}
	
	/**
	 * Pads or truncates a {@code long[]} to exactly {@code targetLength} longs. Padding uses
	 * {@code padValue} (typically 0, the BERT pad token ID). Truncation keeps the first
	 * {@code targetLength} tokens.
	 */
	private static long[] padOrTruncate(long[] input, int targetLength, long padValue) {
		long[] output = new long[targetLength];
		for (int i = 0; i < targetLength; i++) {
			output[i] = (i < input.length) ? input[i] : padValue;
		}
		return output;
	}
	
	/** In-place L2 normalization. After this call, dotProduct(v, v) is approximately 1.0. */
	private static void normalizeL2(float[] v) {
		float n = norm(v);
		if (n > 0f) {
			for (int i = 0; i < v.length; i++) {
				v[i] /= n;
			}
		}
	}
	
	private static float norm(float[] v) {
		float sum = 0f;
		for (float x : v) {
			sum += x * x;
		}
		return (float) Math.sqrt(sum);
	}
	
	private static float dotProduct(float[] a, float[] b) {
		float sum = 0f;
		for (int i = 0; i < a.length; i++) {
			sum += a[i] * b[i];
		}
		return sum;
	}
}
