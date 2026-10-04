package org.pandastic.relay.brain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.pandastic.relay.hub.HubPolicy;

/**
 * The helper phone is the daughter's phone: her mother texts it about everything. Only farming questions get
 * an automatic reply (HubPolicy); personal messages are left to the daughter. Uses the real lexicon.
 */
public class SmsTriageTest {
    private KeywordNlu keywords;
    private Brain brain;

    @Before public void setUp() throws Exception {
        FakeKnowledge knowledge = new FakeKnowledge();
        keywords = new KeywordNlu(knowledge.lexicon());
        brain = new Brain(knowledge, keywords, () -> 0L);
    }

    /** Keywords only (no language model), as on a phone without the model file. */
    private boolean answered(String sms) {
        return HubPolicy.isFarmingQuestion(sms, keywords.parse(sms, null), brain.answerText(sms, null).intent);
    }

    @Test public void farmingQuestionsAreAnswered() {
        for (String sms : new String[]{
            "P 1 12000", "bei ya kahawa leo ni ngapi? wananipa 12000", "mahindi 900", "beans price 3000",
            "?", "msaada", "menu", "majani ya kahawa yana unga wa njano", "my maize leaves have holes",
            "Habari mwanangu, bei ya kahawa ni ngapi leo?"}) {
            assertTrue(sms, answered(sms));
        }
    }

    @Test public void personalMessagesAreLeftToTheDaughter() {
        for (String sms : new String[]{
            "Habari mwanangu, shule inaendaje?", "Nimekutumia pesa ya ada", "Utakuja nyumbani Ijumaa?",
            "how is school?", "Call me when you can", "Nakupenda sana", "Asante", "ok"}) {
            assertFalse(sms, answered(sms));
        }
    }

    @Test public void helpRequestsGetTheMenu() {
        for (String sms : new String[]{
            "nisaidie", "Nisaidie tafadhali", "saidia", "help me please", "what can you do?", "How does this work",
            "Habari, unaweza kufanya nini?", "Pandastic", "msaada kahawa"}) {
            assertTrue(sms, answered(sms));
        }
        // The same words asking the daughter for something else stay personal.
        for (String sms : new String[]{
            "nisaidie pesa ya ada", "Mwanangu nisaidie", "help me with the school fees", "what can you do about the fees?"}) {
            assertFalse(sms, answered(sms));
        }
    }

    @Test public void theModelAloneCannotTriggerAReplyToChitChat() {
        Slots kw = keywords.parse("how is school?", null);
        assertFalse(HubPolicy.isFarmingQuestion("how is school?", kw, "help"));
        assertFalse(HubPolicy.isFarmingQuestion("how is school?", kw, "diagnose"));
    }

    @Test public void theModelCountsWhenTheFarmerNamedACrop() {
        // Luganda: keywords know the crop word (emmwanyi = coffee) but not "sick"; the model reads the problem.
        String sms = "emmwanyi zange zirwadde amakoola gafuuse kyenvu";
        assertTrue(HubPolicy.isFarmingQuestion(sms, keywords.parse(sms, null), "diagnose"));
    }

    @Test public void withoutAnyNluOnlyUnmistakableWordsCount() {
        assertTrue(HubPolicy.isFarmingQuestion("P 1 12000", null, null));
        assertTrue(HubPolicy.isFarmingQuestion("bei ya mahindi", null, null));
        assertFalse(HubPolicy.isFarmingQuestion("shule inaendaje?", null, null));
        assertFalse(HubPolicy.isFarmingQuestion(null, null, "price"));
    }
}
