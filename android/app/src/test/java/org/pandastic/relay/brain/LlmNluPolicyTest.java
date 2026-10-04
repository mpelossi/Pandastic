package org.pandastic.relay.brain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * The keyword + Qwen hybrid policy (the SMS evals in ml/reports): the model may name a missing intent; the fine-tuned
 * model may also name a missing symptom that fits the crop. Never the crop.
 */
public class LlmNluPolicyTest {
    private static Slots keywords(String intent, String crop, String symptom) {
        Slots s = new Slots();
        s.lang = "sw";
        s.intent = intent;
        s.intentProb = intent == null ? 0f : 1f;
        s.crop = crop;
        s.symptom = symptom;
        return s;
    }

    @Test public void modelIsAskedOnlyWhenKeywordsMissSomething() {
        assertFalse(LlmNlu.needsModel(keywords("price", null, null), true));
        assertFalse(LlmNlu.needsModel(keywords("diagnose", "coffee", "rust"), true));
        assertTrue(LlmNlu.needsModel(keywords(null, "coffee", null), false));
        // A problem report without a symptom: only the fine-tuned model is asked for one.
        assertTrue(LlmNlu.needsModel(keywords("diagnose", "coffee", null), true));
        assertFalse(LlmNlu.needsModel(keywords("diagnose", "coffee", null), false));
        assertFalse("no crop: a symptom could not be used", LlmNlu.needsModel(keywords("diagnose", null, null), true));
    }

    @Test public void modelFillsAMissingIntent() {
        Slots s = LlmNlu.merge(keywords(null, null, null), "diagnose", null);
        assertEquals("diagnose", s.intent);
        assertEquals(0.8f, s.intentProb, 1e-6);
    }

    @Test public void modelNeverOverridesAKeywordIntent() {
        Slots s = LlmNlu.merge(keywords("price", null, null), "diagnose", "rust");
        assertEquals("price", s.intent);
        assertEquals(1f, s.intentProb, 1e-6);
        assertNull("no symptom on a price question", s.symptom);
    }

    @Test public void unknownOrMissingModelValuesAreIgnored() {
        assertNull(LlmNlu.merge(keywords(null, null, null), "sell_my_farm", null).intent);
        assertNull(LlmNlu.merge(keywords(null, null, null), null, null).intent);
        assertNull(LlmNlu.merge(keywords("diagnose", "coffee", null), null, "null").symptom);
        assertNull(LlmNlu.merge(keywords("diagnose", "coffee", null), null, "dragon_pox").symptom);
    }

    @Test public void symptomMustFitTheCrop() {
        assertEquals("rust", LlmNlu.merge(keywords("diagnose", "coffee", null), null, "rust").symptom);
        assertEquals("fall_armyworm", LlmNlu.merge(keywords("diagnose", "maize", null), null, "fall_armyworm").symptom);
        // Fall armyworm is not a coffee label: keep "not sure" rather than a wrong pairing.
        assertNull(LlmNlu.merge(keywords("diagnose", "coffee", null), null, "fall_armyworm").symptom);
        // No crop the keywords know (Luganda "emmwanyi" = coffee): the model's symptom may be another plant's.
        assertNull(LlmNlu.merge(keywords("diagnose", null, null), null, "lethal_necrosis").symptom);
        assertNull(LlmNlu.merge(keywords("diagnose", "coffee", null), null, "healthy").symptom);
    }

    @Test public void keywordSymptomIsNeverReplaced() {
        assertEquals("miner", LlmNlu.merge(keywords("diagnose", "coffee", "miner"), null, "rust").symptom);
    }

    @Test public void symptomOnlyForAProblemReport() {
        // The model's own intent makes it a problem report: then its symptom may be used too.
        Slots s = LlmNlu.merge(keywords(null, "coffee", null), "diagnose", "rust");
        assertEquals("diagnose", s.intent);
        assertEquals("rust", s.symptom);
        assertNull(LlmNlu.merge(keywords(null, "coffee", null), "help", "rust").symptom);
    }

    @Test public void cropStaysWhatTheFarmerWrote() {
        // "mihogo" (cassava) has no crop of ours: keywords leave it empty and the model must not invent coffee.
        assertNull(LlmNlu.merge(keywords(null, null, null), "diagnose", null).crop);
        assertEquals("maize", LlmNlu.merge(keywords(null, "maize", null), "price", null).crop);
    }

    @Test public void generationStopsAfterTheSymptom() throws Exception {
        // The bundled grammar (ml/llm/slots.gbnf): only intent, crop and symptom are read, so the root rule ends
        // at the ',"' the model writes after the symptom anyway; commodity and offer are never generated.
        String full = new String(Files.readAllBytes(FakeKnowledge.findRepoFile("ml/llm/slots.gbnf").toPath()), StandardCharsets.UTF_8);
        String cut = LlmNlu.stopAfterSymptom(full);
        String root = cut.lines().filter(l -> l.startsWith("root ::=")).findFirst().orElse("");
        assertTrue(root, root.startsWith("root ::= \"{\\\"lang\\\":\" lang"));
        assertTrue(root, root.endsWith(" symptom \",\\\"\""));
        assertFalse(root, root.contains("commodity"));
        assertEquals("the other rules stay", full.lines().count(), cut.lines().count());
        assertEquals("another grammar is used as it is", "root ::= x", LlmNlu.stopAfterSymptom("root ::= x"));
    }

    @Test public void theCutAnswerIsClosedIntoOneObject() {
        assertEquals("{\"lang\":\"sw\",\"intent\":\"diagnose\",\"crop\":\"coffee\",\"symptom\":\"rust\"}",
            LlmNlu.closeJson("{\"lang\":\"sw\",\"intent\":\"diagnose\",\"crop\":\"coffee\",\"symptom\":\"rust\",\""));
        assertEquals("{\"lang\":\"en\",\"intent\":\"help\",\"crop\":null,\"symptom\":null}",
            LlmNlu.closeJson("{\"lang\":\"en\",\"intent\":\"help\",\"crop\":null,\"symptom\":null,\""));
        String whole = "{\"intent\":\"price\",\"crop\":null,\"symptom\":null}";
        assertEquals(whole, LlmNlu.closeJson(whole));
    }
}
