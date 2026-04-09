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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java implementation of BERT WordPiece tokenization.
 * <p>
 * Matches the behavior of HuggingFace's {@code BertTokenizer} with {@code do_lower_case=True} and
 * {@code add_special_tokens=True}. Valid for both the SapBERT and Hierarchical model tokenizers
 * (which share the same vocabulary and configuration).
 * </p>
 * <p>
 * Special token IDs for this vocabulary:
 * <ul>
 * <li>{@code [PAD]} = 0</li>
 * <li>{@code [UNK]} = 1</li>
 * <li>{@code [CLS]} = 2</li>
 * <li>{@code [SEP]} = 3</li>
 * </ul>
 * </p>
 * <p>
 * This implementation exists instead of the HuggingFace Java tokenizer binding
 * (ai.djl.huggingface:tokenizers) because that library causes a {@code LinkageError} due to slf4j
 * classloader conflicts in the OpenMRS module classloader environment. The implementation
 * </p>
 * <p>
 * Usage: call {@link #load(String)} once at startup (from {@code SapBertPredictor.initialize()} or
 * {@code HierarchicalPredictor.initialize()}), then {@link #tokenize(String)} per request. This
 * class is stateless after loading and thread-safe
 * </p>
 */
public class BertWordPieceTokenizer {
	
	private static final Log log = LogFactory.getLog(BertWordPieceTokenizer.class);
	
	// Special token IDs
	public static final int PAD_TOKEN_ID = 0;
	
	public static final int UNK_TOKEN_ID = 1;
	
	public static final int CLS_TOKEN_ID = 2;
	
	public static final int SEP_TOKEN_ID = 3;
	
	/** Maximum number of characters in a single word before marking as unknown. */
	private static final int MAX_WORD_CHARS = 100;
	
	/** Token vocabulary: mapping token string to token id */
	private final Map<String, Integer> vocab;
	
	private BertWordPieceTokenizer(Map<String, Integer> vocab) {
		this.vocab = vocab;
	}
	
	/**
	 * Loads the vocabulary from a {@code vocab.txt} file and returns a ready tokenizer.
	 * <p>
	 * {@code vocab.txt} is the standard HuggingFace BERT vocabulary file where each line is one
	 * token and the line number (0-indexed) is the token ID.
	 * </p>
	 * 
	 * @param vocabPath absolute path to {@code vocab.txt}
	 * @return a loaded tokenizer ready to use
	 * @throws IOException if the file cannot be read or if the vocabulary either does not include
	 *             or include with bad ID the required special tokens
	 */
	public static BertWordPieceTokenizer load(String vocabPath) throws IOException {
		Map<String, Integer> vocab = new HashMap<String, Integer>();
		
		if (vocabPath == null) {
			throw new FileNotFoundException();
		}
		BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(vocabPath), "UTF-8"));
		try {
			String line;
			int idx = 0;
			while ((line = reader.readLine()) != null) {
				if (line.trim().isEmpty()) {
					continue;
				}
				vocab.put(line.trim(), idx);
				idx++;
			}
			if (idx == 0) {
				log.warn("The given vocabulary is empty");
			}
		}
		finally {
			reader.close();
		}
		
		// Validate special tokens are present at expected positions
		validateSpecialToken(vocab, "[PAD]", PAD_TOKEN_ID);
		validateSpecialToken(vocab, "[UNK]", UNK_TOKEN_ID);
		validateSpecialToken(vocab, "[CLS]", CLS_TOKEN_ID);
		validateSpecialToken(vocab, "[SEP]", SEP_TOKEN_ID);
		
		log.info("WordPiece vocabulary loaded: " + vocab.size() + " tokens from " + vocabPath);
		return new BertWordPieceTokenizer(vocab);
	}
	
	/**
	 * Tokenizes a text string and returns the token IDs as an {@code int[]}.
	 * <p>
	 * Processing steps, matching HuggingFace {@code BertTokenizer} exactly:
	 * <ol>
	 * <li>Lowercase the entire input ({@code do_lower_case=True})</li>
	 * <li>Clean the text (remove control characters, normalize whitespace)</li>
	 * <li>Tokenize on whitespace and punctuation (basic tokenization)</li>
	 * <li>Apply WordPiece subword segmentation to each token</li>
	 * <li>Wrap with {@code [CLS]} at the start and {@code [SEP]} at the end</li>
	 * </ol>
	 * The returned array is not padded or truncated: callers handle length control itself.
	 * </p>
	 * 
	 * @param text the input text; must not be null
	 * @return token IDs including leading [CLS] and trailing [SEP]
	 */
	public long[] tokenize(String text) {
		String cleaned = text.toLowerCase();
		cleaned = cleanText(cleaned);
		List<String> basicTokens = basicTokenize(cleaned);
		
		// WordPiece subword segmentation
		List<Integer> ids = new ArrayList<Integer>();
		ids.add(CLS_TOKEN_ID);
		
		for (String token : basicTokens) {
			List<Integer> wordpieceIds = wordpieceTokenize(token);
			ids.addAll(wordpieceIds);
		}
		
		ids.add(SEP_TOKEN_ID);
		
		long[] result = new long[ids.size()];
		for (int i = 0; i < ids.size(); i++) {
			result[i] = ids.get(i);
		}
		return result;
	}
	
	// ---- Private helpers ----
	
	/**
	 * Defensive code to verify that the special tokens are present and are given the correct ID
	 */
	private static void validateSpecialToken(Map<String, Integer> vocab, String token, int expectedId) throws IOException {
		if (!vocab.containsKey(token)) {
			throw new IOException("Vocab file is missing required token: " + token);
		}
		if (vocab.get(token) != expectedId) {
			throw new IOException("Token " + token + " has unexpected ID " + vocab.get(token) + ", expected " + expectedId);
		}
	}
	
	/**
	 * Removes control characters and normalizes whitespace. Matches HuggingFace's
	 * {@code BasicTokenizer._clean_text()}.
	 */
	private static String cleanText(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			
			// Skip null characters and replacement characters
			if ((int) c == 0 || (int) c == 0xFFFD || isControl(c)) {
				continue;
			}
			// Replace control characters and non-printable chars with space -> technically dead branch
			// but matches HuggingFace Python implementation
			if (Character.getType(c) == Character.CONTROL && c != '\t' && c != '\n' && c != '\r') {
				sb.append(' ');
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
	
	/**
	 * Splits text on whitespace and around punctuation characters. Matches HuggingFace's
	 * {@code BasicTokenizer.tokenize()} with {@code tokenize_chinese_chars=True} (default) and
	 * {@code strip_accents=False} (default for this tokenizer).
	 * <p>
	 * Punctuation characters become their own tokens before WordPiece is applied.
	 * </p>
	 */
	private static List<String> basicTokenize(String text) {
		// Add spaces around punctuation so they split cleanly
		StringBuilder spaced = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (isPunctuation(c)) {
				spaced.append(' ');
				spaced.append(c);
				spaced.append(' ');
			} else if (isWhitespace(c)) {
				spaced.append(' ');
			} else {
				spaced.append(c);
			}
		}
		
		// Split on whitespace and filter empty strings
		String[] parts = spaced.toString().split(" ");
		List<String> tokens = new ArrayList<String>(parts.length);
		for (String part : parts) {
			if (!part.isEmpty()) {
				tokens.add(part);
			}
		}
		return tokens;
	}
	
	/**
	 * Applies WordPiece subword segmentation to a single token. Matches HuggingFace's
	 * {@code WordpieceTokenizer.tokenize()}.
	 * <p>
	 * Algorithm: greedily find the longest vocabulary prefix of the remaining substring. If no
	 * prefix is found, the entire word maps to {@code [UNK]}. Continuation subwords are prefixed
	 * with {@code ##}. Inspired by <a
	 * href="https://huggingface.co/learn/llm-course/en/chapter6/6">this resource.</a>
	 * </p>
	 */
	private List<Integer> wordpieceTokenize(String token) {
		List<Integer> ids = new ArrayList<Integer>();
		
		if (token.length() > MAX_WORD_CHARS) {
			ids.add(UNK_TOKEN_ID);
			return ids;
		}
		
		boolean isBad = false;
		int start = 0;
		List<Integer> subTokenIds = new ArrayList<Integer>();
		
		while (start < token.length()) {
			int end = token.length();
			Integer curId = null;
			
			// Greedily find the longest matching substring in the vocabulary
			while (start < end) {
				String substr = token.substring(start, end);
				if (start > 0) {
					substr = "##" + substr;
				}
				Integer id = vocab.get(substr);
				if (id != null) {
					curId = id;
					break;
				}
				end--;
			}
			
			if (curId == null) {
				isBad = true;
				break;
			}
			
			subTokenIds.add(curId);
			start = end;
		}
		
		if (isBad) {
			ids.add(UNK_TOKEN_ID);
		} else {
			ids.addAll(subTokenIds);
		}
		return ids;
	}
	
	/**
	 * Returns true if the character is considered punctuation by BERT's tokenizer. Matches
	 * HuggingFace's {@code BasicTokenizer._is_punctuation()}. Includes all ASCII punctuation and
	 * Unicode punctuation categories.
	 */
	private static boolean isPunctuation(char c) {
		int cp = (int) c;
		// ASCII punctuation ranges
		if ((cp >= 33 && cp <= 47) // !"#$%&'()*+,-./
		        || (cp >= 58 && cp <= 64) // :;<=>?@
		        || (cp >= 91 && cp <= 96) // [\]^_`
		        || (cp >= 123 && cp <= 126)) { // {|}~
			return true;
		}
		
		// Unicode punctuation categories
		int type = Character.getType(c);
		return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
		        || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
		        || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
		        || type == Character.OTHER_PUNCTUATION;
	}
	
	/**
	 * Returns true if the character is whitespace. Matches HuggingFace's
	 * {@code BasicTokenizer._is_whitespace()}.
	 */
	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r' || Character.getType(c) == Character.SPACE_SEPARATOR;
	}
	
	/**
	 * Returns true if the character is a control character. Matches HuggingFace's
	 * {@code BasicTokenizer._is_control()}.
	 */
	private static boolean isControl(char c) {
		if (c == '\t' || c == '\n' || c == '\r')
			return false;
		int type = Character.getType(c);
		return type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
		        || type == Character.SURROGATE;
	}
	
	/** Returns the size of the loaded vocabulary. */
	public int getVocabSize() {
		return vocab.size();
	}
}
