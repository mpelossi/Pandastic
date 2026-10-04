package org.pandastic.relay.brain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

/** A photo sent with words: both are used, and they must agree before the answer is CONFIDENT. */
public class PhotoWithTextTest {
    private static final String[] LABELS = {"coffee_healthy", "coffee_rust", "coffee_miner", "maize_leaf_blight",
        "maize_fall_armyworm", "other"};
    private final Brain brain;

    public PhotoWithTextTest() throws Exception { brain = new Brain(new FakeKnowledge(), null, () -> 0L); }

    private static ClassifierResult photo(String label, float p) {
        float[] probs = new float[LABELS.length];
        float rest = (1 - p) / (LABELS.length - 1);
        for (int i = 0; i < probs.length; i++) probs[i] = LABELS[i].equals(label) ? p : rest;
        return new ClassifierResult("leaf-test", LABELS, probs, 0.40f, 0.05f, Collections.emptyMap());
    }

    @Test public void wordsThatAgreeKeepAConfidentPhoto() {
        Decision d = brain.answerPhoto(null, photo("coffee_rust", 0.95f), "majani ya kahawa yana kutu", null);
        assertEquals("CONFIDENT", d.status);
        assertEquals("coffee_rust", d.label);
        assertEquals("sw", d.lang);
    }

    @Test public void wordsThatContradictAHealthyLookingPhotoMakeItUncertain() {
        Decision d = brain.answerPhoto(null, photo("coffee_healthy", 0.97f), "my coffee has rust", null);
        assertEquals("UNCERTAIN", d.status);
        assertArrayEquals(new String[]{"coffee_healthy", "coffee_rust"}, d.candidates);
        assertTrue(d.escalate);
        assertEquals("en", d.lang);
    }

    @Test public void anotherPestInTheWordsNamesBoth() {
        Decision d = brain.answerPhoto(null, photo("maize_leaf_blight", 0.9f), "mahindi yana viwavi", null);
        assertEquals("UNCERTAIN", d.status);
        assertArrayEquals(new String[]{"maize_leaf_blight", "maize_fall_armyworm"}, d.candidates);
    }

    @Test public void wordsWithoutASymptomChangeNothing() {
        Decision d = brain.answerPhoto(null, photo("coffee_rust", 0.95f), "angalia jani hili", null);
        assertEquals("CONFIDENT", d.status);
    }

    @Test public void aSymptomOfAnotherCropStillBlocksConfidence() {
        Decision d = brain.answerPhoto(null, photo("coffee_rust", 0.95f), "viwavi", null);
        assertEquals("UNCERTAIN", d.status);
        assertArrayEquals(new String[]{"coffee_rust"}, d.candidates);
    }
}
