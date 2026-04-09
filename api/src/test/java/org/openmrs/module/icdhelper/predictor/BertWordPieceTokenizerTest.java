package org.openmrs.module.icdhelper.predictor;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.icdhelper.api.predictors.BertWordPieceTokenizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class BertWordPieceTokenizerTest {
	
	private BertWordPieceTokenizer tokenizer;
	
	@Before
	public void setup() throws IOException {
		// Minimal vocab covering special tokens + test words
		// Line number = token ID (0-indexed)
		String vocab = String.join("\n", "[PAD]", // 0
		    "[UNK]", // 1
		    "[CLS]", // 2
		    "[SEP]", // 3
		    "hello", // 4
		    "world", // 5
		    "##ing", // 6
		    "##s", // 7
		    "un", // 8
		    "##known", // 9
		    "ch", // 10
		    "##ol", // 11
		    "##era", // 12
		    "life", // 13
		    "li", // 14
		    "##fe", //15
		    ".", //16
		    ",", //17
		    "-", //18
		    "non", //19
		    "coded", //20
		    "a", //21
		    "##0", //22
		    "9" //23
		);
		Path tempVocab = Files.createTempFile("vocab", ".txt");
		Files.write(tempVocab, vocab.getBytes());
		tokenizer = BertWordPieceTokenizer.load(tempVocab.toString());
		tempVocab.toFile().deleteOnExit();
	}
	
	// Tests for the load method
	@Test
	public void load_shouldReturnTokenizerWhenVocabFileIsValid() {
		Assert.assertNotNull(tokenizer);
		Assert.assertEquals(24, tokenizer.getVocabSize());
		
		long[] result1 = tokenizer.tokenize("hello");
		Assert.assertArrayEquals(new long[] { 2L, 4L, 3L }, result1); // [CLS=2, hello=4, SEP=3]
		long[] result2 = tokenizer.tokenize("wordThatIsNotInVocab");
		Assert.assertArrayEquals(new long[] { 2L, 1L, 3L }, result2); // [CLS=2, UNK=1, SEP=3]
	}
	
	@Test(expected = IOException.class)
	public void load_shouldThrowIOExceptionWhenFileDoesNotExist() throws IOException {
		BertWordPieceTokenizer.load("/nonexistent/path/vocab.txt");
	}
	
	@Test(expected = IOException.class)
	public void load_shouldThrowIOExceptionWhenPathIsNull() throws IOException {
		BertWordPieceTokenizer.load(null);
	}
	
	@Test(expected = IOException.class)
	public void load_shouldHandleEmptyVocabFile() throws IOException {
		String vocab = " ";
		Path emptyVocab = Files.createTempFile("vocab_empty", ".txt");
		Files.write(emptyVocab, vocab.getBytes());
		emptyVocab.toFile().deleteOnExit();
		
		// When file is empty or only white spaces, the file is read but the method throws IO exception because
		// special tokens are required to be present with the correct IDs for the tokenizer to work
		BertWordPieceTokenizer.load(emptyVocab.toString());
	}
	
	// Tests for the tokenize method
	@Test
	public void tokenize_shouldReturnOnlyCLSAndSEPForEmptyInput() {
		long[] result = tokenizer.tokenize("");
		Assert.assertArrayEquals(new long[] { 2L, 3L }, result); // [CLS=2, SEP=3]
	}
	
	@Test
	public void tokenize_shouldLowercaseBeforeWordPieceSegmentation() {
		long[] result1 = tokenizer.tokenize("HelLO");
		long[] result2 = tokenizer.tokenize("hello");
		Assert.assertArrayEquals(result1, result2);
		
		// [CLS=2, hello=4, SEP=3], not [CLS=2, UNK=1, SEP=3]
		Assert.assertArrayEquals(new long[] { 2L, 4L, 3L }, result1);
	}
	
	@Test
	public void tokenize_shouldReturnCorrectIdForKnownWord() {
		long[] result = tokenizer.tokenize("hello world");
		Assert.assertArrayEquals(new long[] { 2L, 4L, 5L, 3L }, result); // [CLS=2, hello=4, world=5, SEP=3]
	}
	
	@Test
	public void tokenize_shouldReturnUNKForWordNotInVocab() {
		long[] result = tokenizer.tokenize("hi planet");
		Assert.assertArrayEquals(new long[] { 2L, 1L, 1L, 3L }, result); // [CLS=2, UNK=1, UNK=1, SEP=3]
	}
	
	@Test
	public void tokenize_shouldSegmentWordIntoSubtokens() {
		long[] result = tokenizer.tokenize("cholera");
		Assert.assertArrayEquals(new long[] { 2L, 10L, 11L, 12L, 3L }, result); // [CLS=2, ch=10, ##ol=11, ##era=12, SEP=3]
	}
	
	@Test
	public void tokenize_shouldUseGreedyLongestMatchForSegmentation() {
		long[] result = tokenizer.tokenize("life");
		
		// [CLS=2, life=13, SEP=3], not [CLS=2, li=14, ##fe=15, SEP=3] split as subtokens
		Assert.assertArrayEquals(new long[] { 2L, 13L, 3L }, result);
	}
	
	@Test
	public void tokenize_shouldReturnUNKWhenWordCannotBeFullySegmented() {
		long[] result = tokenizer.tokenize("charming");
		
		// [CLS=2, UNK=1, SEP=3], neither [CLS=2, ch=10, UNK=1, SEP=3] nor [CLS=2, ch=10, UNK=1, ##ing=6, SEP=3]
		Assert.assertArrayEquals(new long[] { 2L, 1L, 3L }, result);
	}
	
	@Test
	public void tokenize_shouldNormalizeAllWhiteSpaceVariants() {
		List<String> variants = new ArrayList<String>();
		variants.add("hello   world");
		variants.add("  hello world  ");
		variants.add("hello\tworld");
		variants.add("hello\nworld");
		variants.add("hello\r world");
		
		long[] ref = tokenizer.tokenize("hello world");
		for (String variant : variants) {
			long[] result = tokenizer.tokenize(variant);
			Assert.assertArrayEquals("Mismatch for: " + variant, ref, result);
		}
	}
	
	@Test
	public void tokenize_shouldNormaliseUnicodeWhitespace() {
		List<String> variants = new ArrayList<String>();
		variants.add("hello\u00a0world"); // non-breaking space
		variants.add("hello\u2003world"); // em space
		variants.add("hello\u0009world"); // tab
		
		long[] ref = tokenizer.tokenize("hello world");
		for (String variant : variants) {
			long[] result = tokenizer.tokenize(variant);
			Assert.assertArrayEquals("Mismatch for: " + variant, ref, result);
		}
	}
	
	@Test
	public void tokenize_shouldSplitOnPunctuation() {
		// should split: [CLS=2, hello=4, .=16, SEP=3], not [CLS=2, UNK=1, SEP=3]
		long[] result = tokenizer.tokenize("hello.");
		Assert.assertArrayEquals(new long[] { 2L, 4L, 16L, 3L }, result);
		
		// should split: CLS=2, hello=4, ,=17, world=5, SEP=3], not [CLS=2, UNK=1, SEP=3]
		long[] result2 = tokenizer.tokenize("hello,world");
		Assert.assertArrayEquals(new long[] { 2L, 4L, 17L, 5L, 3L }, result2);
	}
	
	@Test
	public void tokenize_shouldReturnUNKForPunctuationNotInVocab() {
		long[] result = tokenizer.tokenize("hello!");
		
		// ! is not in the test vocab, [CLS=2, hello=4, UNK=1, SEP=3]
		Assert.assertArrayEquals(new long[] { 2L, 4L, 1L, 3L }, result);
	}
	
	@Test
	public void tokenize_shouldRemoveControlCharacters() {
		List<String> variants = new ArrayList<String>();
		variants.add("hello \u0000world"); // null character
		variants.add("hello \ufffdworld"); // replacement character
		variants.add("hello \u200bworld"); // zero-width space
		
		long[] ref = tokenizer.tokenize("hello world");
		for (String variant : variants) {
			long[] result = tokenizer.tokenize(variant);
			Assert.assertArrayEquals("Mismatch for: " + variant, ref, result);
		}
	}
	
	@Test
	public void tokenize_shouldHandleHyphenatedTerms() {
		long[] result = tokenizer.tokenize("non-coded");
		Assert.assertArrayEquals(new long[] { 2L, 19L, 18L, 20L, 3L }, result); //[CLS=2, non=19, -=18, coded=20, SEP=3]
	}
	
	@Test
	public void tokenize_shouldHandleNumericTokens() {
		long[] result = tokenizer.tokenize("A00.9");
		// [CLS=2, A=21, 0=22, 0=22, .=16, 9=23, SEP=3]
		Assert.assertArrayEquals(new long[] { 2L, 21L, 22L, 22L, 16L, 23L, 3L }, result);
	}
	
	@Test
	public void tokenize_shouldProduceDeterministicResultsOnRepeatedCalls() {
		long[] first = tokenizer.tokenize("hello Cholera");
		long[] second = tokenizer.tokenize("hello Cholera");
		long[] third = tokenizer.tokenize("hello Cholera");
		Assert.assertArrayEquals(first, second);
		Assert.assertArrayEquals(first, third);
	}
	
	@Test
	public void tokenize_shouldProduceSameResultFromMultipleThreads() throws InterruptedException {
		int threadCount = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		final CountDownLatch latch = new CountDownLatch(threadCount);
		
		final String text = "patient has cholera";
		final long[] expected = tokenizer.tokenize(text);
		final AtomicInteger errors = new AtomicInteger(0);
		
		for (int i = 0; i < threadCount; i++) {
			executor.submit(new Runnable() {
				
				@Override
				public void run() {
					try {
						long[] result = tokenizer.tokenize(text);
						if (!Arrays.equals(expected, result)) {
							errors.incrementAndGet();
						}
					}
					finally {
						latch.countDown();
					}
				}
			});
		}
		
		latch.await(); // Wait for all threads to finish
		executor.shutdown();
		Assert.assertEquals(0, errors.get());
	}
	
	@Test
	public void tokenize_shouldMatchHuggingFaceOutputForKnownClinicalSentence() throws IOException {
		// Load real vocab from test resources
		String vocabPath = getClass().getResource("/org/openmrs/module/icdhelper/include/vocab.txt").getPath();
		BertWordPieceTokenizer realTokenizer = BertWordPieceTokenizer.load(vocabPath);
		
		List<String> clinicalNotes = new ArrayList<String>();
		clinicalNotes.add("patient has COVID-19 and hypertension");
		clinicalNotes.add("12:10AM BLOOD Glucose-108* UreaN-17 Creat-1.1 Na-135 K-5.5* Cl-99 HCO3-23 AnGap-19");
		clinicalNotes.add("IMPRESSION: 1. Decreased conspicuity of previously seen pulmonary emboli "
		        + "without new filling defects to suggest interval embolism.");
		
		// Expected values precomputed from Python:
		// from transformers import AutoTokenizer
		// t = AutoTokenizer.from_pretrained('path/to/model')
		// t.encode(clinicalText)
		List<long[]> expectedIds = new ArrayList<long[]>();
		expectedIds.add(new long[] { 2, 2774, 2258, 6083, 1960, 17, 2282, 1930, 5579, 3 });
		expectedIds.add(new long[] { 2, 2369, 30, 18241, 1035, 2877, 3817, 17, 9123, 14, 10505, 1022, 17, 2752, 3478, 1926,
		        17, 21, 18, 21, 4049, 17, 11477, 53, 17, 25, 18, 25, 14, 2111, 17, 4922, 25181, 17, 3020, 3409, 2026, 17,
		        2282, 3 });
		expectedIds.add(new long[] { 2, 19297, 30, 21, 18, 3261, 24700, 10087, 1927, 3024, 3945, 5352, 12494, 1033, 2979,
		        2814, 12919, 6007, 1942, 3220, 4979, 19287, 18, 3 });
		
		for (int i = 0; i < 3; i++) {
			long[] result = realTokenizer.tokenize(clinicalNotes.get(i));
			Assert.assertArrayEquals("Mismatch for: " + clinicalNotes.get(i), expectedIds.get(i), result);
		}
	}
	
}
