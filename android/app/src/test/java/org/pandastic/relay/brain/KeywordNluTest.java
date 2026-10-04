package org.pandastic.relay.brain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

public class KeywordNluTest {
    private KeywordNlu nlu;

    @Before public void setUp() throws Exception { nlu = new KeywordNlu(new FakeKnowledge().lexicon()); }

    @Test public void swahiliRustDescription() {
        Slots s = nlu.parse("Majani ya kahawa yana unga wa njano chini", null);
        assertEquals("sw", s.lang);
        assertEquals("diagnose", s.intent);
        assertEquals("coffee", s.crop);
        assertEquals("rust", s.symptom);
        assertNull(s.offer);
    }

    @Test public void swahiliPriceWithOffer() {
        Slots s = nlu.parse("Bei ya kahawa? Mnunuzi anasema 12,000", null);
        assertEquals("sw", s.lang);
        assertEquals("price", s.intent);
        assertEquals("coffee", s.crop);
        assertEquals(12000.0, s.offer, 0.01);
    }

    @Test public void shortCodePriceIgnoresMenuNumber() {
        Slots s = nlu.parse("P 1 12k", null);
        assertEquals("price", s.intent);
        assertEquals("coffee", s.crop);
        assertEquals(12000.0, s.offer, 0.01);
    }

    @Test public void priceCodeWithoutItsSpace() {
        Slots s = nlu.parse("p1 13000", null);
        assertEquals("price", s.intent);
        assertEquals("coffee", s.crop);
        assertEquals(13000.0, s.offer, 0.01);
        assertEquals("maize", nlu.parse("P2: 1,000", null).crop);
        // "P3" is also a primary-school year: only an SMS that starts with the code and has a number is a price check.
        assertNull(nlu.parse("Sarah is in P3 now, fees 150000", null).crop);
        assertNull(nlu.parse("p1 imeanza vizuri", null).crop);
    }

    @Test public void cropAndNumberAloneMeansPrice() {
        Slots s = nlu.parse("mahindi 900", null);
        assertEquals("price", s.intent);
        assertEquals("maize", s.crop);
        assertEquals(900.0, s.offer, 0.01);
    }

    @Test public void englishArmyworm() {
        Slots s = nlu.parse("Worms are eating my maize leaves", null);
        assertEquals("en", s.lang);
        assertEquals("diagnose", s.intent);
        assertEquals("maize", s.crop);
        assertEquals("fall_armyworm", s.symptom);
    }

    @Test public void mixedLanguageStillFindsCrop() {
        Slots s = nlu.parse("bei ya coffee leo 15000", null);
        assertEquals("price", s.intent);
        assertEquals("coffee", s.crop);
    }

    @Test public void kibokoSetsCommodity() {
        Slots s = nlu.parse("bei ya kiboko", null);
        assertEquals("price", s.intent);
        assertEquals("coffee_robusta_kiboko", s.commodity);
    }

    @Test public void helpAndUnknown() {
        assertEquals("help", nlu.parse("?", null).intent);
        assertEquals("help", nlu.parse("Habari", null).intent);
        assertEquals("other", nlu.parse("asante sana", null).intent);
    }

    @Test public void shortTermsDoNotMatchInsideWords() {
        // "bei" must not fire inside "kubeba"; "p" must not fire inside "panda"; "doa" not inside "kadoa".
        Slots s = nlu.parse("nataka kubeba panda kadoa", null);
        assertEquals("other", s.intent);
    }

    @Test public void offerParsing() {
        assertEquals(12000.0, KeywordNlu.offer("12,000"), 0.01);
        assertEquals(12000.0, KeywordNlu.offer("12.000 shs"), 0.01);
        assertEquals(12500.0, KeywordNlu.offer("12.5k"), 0.01);
        assertEquals(15000.0, KeywordNlu.offer("elfu 15"), 0.01);
        assertNull(KeywordNlu.offer("P 1"));
        assertNull(KeywordNlu.offer(null));
    }

    @Test public void swahiliNumberWords() {
        assertEquals(10000.0, KeywordNlu.offer("mnunuzi anasema elfu kumi"), 0.01);
        assertEquals(12000.0, KeywordNlu.offer("elfu kumi na mbili kwa kilo"), 0.01);
        assertEquals(12500.0, KeywordNlu.offer("elfu kumi na mbili na mia tano"), 0.01);
        assertEquals(25000.0, KeywordNlu.offer("elfu ishirini na tano"), 0.01);
        assertEquals(900.0, KeywordNlu.offer("mahindi mia tisa"), 0.01);
        assertEquals(1200.0, KeywordNlu.offer("elfu moja mia mbili"), 0.01);
        assertEquals(1500.0, KeywordNlu.offer("elfu moja na mia tano"), 0.01);
        assertNull(KeywordNlu.offer("kilo moja ni shilingi ngapi"));   // 1 is not a price
        assertNull(KeywordNlu.offer("siku tano zilizopita"));           // 5 days
    }

    @Test public void numberWordsMakeAPriceQuestion() {
        Slots s = nlu.parse("mnunuzi wa kahawa anasema elfu kumi na nne", null);
        assertEquals("price", s.intent);
        assertEquals("coffee", s.crop);
        assertEquals(14000.0, s.offer, 0.01);
    }

    @Test public void functionWordsDecideTheLanguage() {
        assertEquals("sw", nlu.parse("faq iko bei gani hapa kwetu", null).lang);  // "faq" is listed in both languages
        assertEquals("en", nlu.parse("what is this app for", null).lang);         // no lexicon term at all
        assertEquals("sw", nlu.parse("bei ya coffee", null).lang);                // code-switching: Swahili frame wins
    }

    @Test public void symptomWordMustFitTheCrop() {
        Slots coffee = nlu.parse("kuna mistari mieupe kwenye majani ya kahawa", null);
        assertEquals("coffee", coffee.crop);
        assertEquals("miner", coffee.symptom);
        Slots maize = nlu.parse("mistari ya njano kwenye majani ya mahindi", null);
        assertEquals("maize", maize.crop);
        assertEquals("streak_virus", maize.symptom);
        assertNull(nlu.parse("kuna mistari kwenye majani", null).symptom);  // no crop: ambiguous, so no symptom
    }

    @Test public void seasonAloneIsNotPlanting() {
        assertEquals("diagnose", nlu.parse("msimu huu mahindi yanakufa", null).intent);
        assertEquals("planting", nlu.parse("can we plant beans next week", null).intent);
    }

    @Test public void lugandaCropWordsGiveTheCropButNotTheLanguage() {
        Slots coffee = nlu.parse("emmwanyi zange zirwadde amakoola gafuuse kyenvu", null);
        assertEquals("coffee", coffee.crop);
        assertEquals("sw", coffee.lang);  // replies stay Swahili-first (D3); the word counts for neither language
        assertEquals("maize", nlu.parse("kasooli wange alina obuwuka", null).crop);
        assertEquals("bean", nlu.parse("ebijanjaalo byange", null).crop);
        assertEquals("en", nlu.parse("my kasooli has worms", null).lang);
    }

    @Test public void normalizeKeepsQuestionMark() {
        assertEquals("bei ya kahawa ?", KeywordNlu.normalize("Bei ya KAHAWA ?!"));
    }
}
