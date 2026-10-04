package org.pandastic.relay.brain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.Collections;
import java.util.TimeZone;
import org.junit.Before;
import org.junit.Test;

/** Resolver behaviour through the Brain facade (contracts §2). */
public class BrainTest {
    private static final String[] COFFEE = {"coffee_healthy", "coffee_rust", "coffee_miner", "coffee_cercospora", "coffee_phoma", "other"};
    private Brain brain;

    @Before public void setUp() throws Exception {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        now.clear();
        now.set(2026, Calendar.OCTOBER, 4);
        long fixed = now.getTimeInMillis();
        brain = new Brain(new FakeKnowledge(), null, () -> fixed);
    }

    private static ClassifierResult result(float... probs) {
        return new ClassifierResult("leaf-test", COFFEE, probs, 0.70f, 0.25f, Collections.emptyMap());
    }

    @Test public void confidentRustCarriesCitedAdvice() {
        Decision d = brain.answerPhoto(null, result(0.02f, 0.90f, 0.02f, 0.03f, 0.02f, 0.01f), null, "en");
        assertEquals("CONFIDENT", d.status);
        assertEquals("coffee_rust", d.label);
        assertEquals("coffee", d.crop);
        assertFalse(d.escalate);
        assertNotNull(d.adviceSms);
        assertEquals("plantwise-rw014-coffee-rust", d.source.id);
    }

    @Test public void lowMarginIsUncertainAndEscalates() {
        Decision d = brain.answerPhoto(null, result(0.02f, 0.48f, 0.02f, 0.40f, 0.05f, 0.03f), null, "sw");
        assertEquals("UNCERTAIN", d.status);
        assertTrue(d.escalate);
        assertNull(d.adviceSms);
        assertArrayEquals(new String[]{"coffee_rust", "coffee_cercospora"}, d.candidates);
        assertTrue(d.message.startsWith("Sina uhakika"));
    }

    @Test public void otherIsUnsupported() {
        Decision d = brain.answerPhoto(null, result(0.05f, 0.05f, 0.0f, 0.05f, 0.0f, 0.85f), null, "en");
        assertEquals("UNSUPPORTED", d.status);
        assertTrue(d.escalate);
    }

    @Test public void blurryPhotoIsRetake() {
        Decision d = brain.answerPhoto("blur", null, null, "en");
        assertEquals("RETAKE", d.status);
        assertEquals("blur", d.quality);
    }

    @Test public void missingClassifierIsNoData() {
        assertEquals("NO_DATA", brain.answerPhoto(null, null, null, "en").status);
    }

    @Test public void questionNamingAnotherCropAsksCrop() {
        Decision d = brain.answerPhoto(null, result(0.02f, 0.90f, 0.02f, 0.03f, 0.02f, 0.01f), "my maize leaves", null);
        assertEquals("ASK_CROP", d.status);
        assertEquals("en", d.lang);
    }

    @Test public void confidentLabelWithoutAdviceStillEscalates() {
        Decision d = brain.answerPhoto(null, result(0.02f, 0.02f, 0.02f, 0.02f, 0.90f, 0.02f), null, "en");
        assertEquals("CONFIDENT", d.status);
        assertTrue(d.escalate);
        assertNull(d.adviceSms);
    }

    @Test public void textNeverGivesAConfidentDiagnosis() {
        Decision d = brain.answerText("majani ya kahawa yana unga wa njano", null);
        assertEquals("TEXT_ONLY", d.status);
        assertEquals("coffee_rust", d.label);
        assertTrue(d.escalate);
        assertNull(d.adviceSms);
        assertTrue(d.message.startsWith("Sina uhakika kwa maneno pekee - usinyunyizie dawa bado."));
    }

    @Test public void rustWithoutCropListsBothCandidates() {
        Decision d = brain.answerText("my leaves have rust", null);
        assertEquals("TEXT_ONLY", d.status);
        assertArrayEquals(new String[]{"coffee_rust", "bean_rust"}, d.candidates);
        assertEquals("unknown", d.crop);
    }

    @Test public void coffeePriceWithLowOffer() {
        Decision d = brain.answerText("P 1 12000", "en");
        assertEquals("PRICE", d.status);
        assertFalse(d.escalate);
        assertEquals(15500, d.price.low, 0.01);
        assertEquals(-22.6, d.price.gapPct, 0.01);  // (12000 - 15500) / 15500, computed in code
        assertTrue(d.message, d.message.contains("Offer 12,000 is 23% below."));
        assertTrue(d.message.contains("Aug 2026"));
    }

    @Test public void staleLocalMaizeFallsBackToFreshNationalRange() {
        Decision d = brain.answerText("bei ya mahindi 900", null);
        assertEquals("PRICE", d.status);
        assertNull(d.price.market);
        assertEquals(1273, d.price.low, 0.01);
        assertTrue(d.message, d.message.contains("Bei ya shambani huwa chini zaidi."));
    }

    @Test public void onlyStaleDataIsFlagged() {
        Decision d = brain.answerText("bei ya maharage", null);
        assertEquals("PRICE_STALE", d.status);
        assertTrue(d.escalate);
        assertTrue(d.price.stale);
        assertTrue(d.message.startsWith("Bei ya zamani (Des 2025)"));
    }

    @Test public void priceWithoutCropAsksCrop() {
        assertEquals("ASK_CROP", brain.answerText("bei gani?", null).status);
    }

    @Test public void plantingIsNoDataAndHelpIsMenu() {
        assertEquals("NO_DATA", brain.answerText("when to plant maize this season", null).status);
        Decision help = brain.answerText("?", null);
        assertEquals("HELP", help.status);
        assertFalse(help.escalate);
    }

    @Test public void decisionJsonIsWellFormed() {
        Decision d = brain.answerText("P 1 12000", "en");
        String json = d.toJson();
        assertTrue(json, json.startsWith("{\"status\":\"PRICE\",\"intent\":\"price\",\"crop\":\"coffee\""));
        assertTrue(json, json.contains("\"price\":{\"commodity\":\"coffee_arabica_parchment\""));
        assertTrue(json, json.contains("\"gap_pct\":-22.6"));
        assertTrue(json, json.contains("\"candidates\":[]"));
        assertTrue(json, json.endsWith("\"model_version\":null}"));
    }
}
