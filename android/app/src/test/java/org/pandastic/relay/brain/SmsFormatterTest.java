package org.pandastic.relay.brain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

public class SmsFormatterTest {
    private Brain brain;

    @Before public void setUp() throws Exception { brain = new Brain(new FakeKnowledge(), null); }

    @Test public void everyTextReplyFitsTwoGsmSegments() {
        String[] questions = {"?", "habari", "P 1 12000", "bei ya mahindi 900", "bei ya maharage", "bei gani",
            "majani ya kahawa yana unga wa njano", "my leaves have rust", "worms in my maize", "when to plant maize",
            "asante", "Price of coffee parchment, the buyer offers 12,000 shillings per kilo"};
        for (String lang : new String[]{null, "sw", "en"}) {
            for (String q : questions) {
                String sms = SmsFormatter.format(brain.answerText(q, lang));
                assertTrue(q + " -> " + sms, SmsFormatter.segments(sms) <= SmsFormatter.MAX_SEGMENTS);
                assertEquals(q + " -> non-GSM text", sms, SmsFormatter.gsmSafe(sms));
                assertTrue(sms.startsWith("Pandastic: "));
            }
        }
    }

    @Test public void safetySentenceComesFirstForTextOnly() {
        String sms = SmsFormatter.format(brain.answerText("my coffee leaves have orange powder", "en"));
        assertTrue(sms, sms.startsWith("Pandastic: Not sure from words only - do not spray yet."));
    }

    @Test public void confidentPhotoUsesTitleAndAdvice() {
        String[] labels = {"coffee_healthy", "coffee_rust", "other"};
        ClassifierResult r = new ClassifierResult("t", labels, new float[]{0.05f, 0.9f, 0.05f}, 0.7f, 0.25f, Collections.emptyMap());
        assertEquals("Pandastic: Coffee leaf rust. Leaf rust. Prune to open the bush.",
            SmsFormatter.format(brain.answerPhoto(null, r, null, "en")));
    }

    @Test public void segmentCounting() {
        assertEquals(1, SmsFormatter.segments(repeat('a', 160)));
        assertEquals(2, SmsFormatter.segments(repeat('a', 161)));
        assertEquals(2, SmsFormatter.segments(repeat('a', 306)));
        assertEquals(3, SmsFormatter.segments(repeat('a', 307)));
        assertEquals(1, SmsFormatter.segments(repeat('{', 80)));      // extension chars cost 2 septets
        assertEquals(2, SmsFormatter.segments(repeat('{', 81)));
        assertEquals(2, SmsFormatter.segments("ŋ" + repeat('a', 70)));  // one non-GSM char -> UCS-2, 70 per segment
    }

    @Test public void longTextIsCutAtAWord() {
        Decision d = new Decision();
        d.status = "HELP";
        d.lang = "en";
        d.message = repeat("word ", 100);
        String sms = SmsFormatter.format(d);
        assertTrue(SmsFormatter.segments(sms) <= 2);
        assertTrue(sms, sms.endsWith("word..."));
    }

    @Test public void lugandaEngIsReplaced() {
        assertEquals("ng'ombe", SmsFormatter.gsmSafe("ŋombe"));
        assertEquals("it's - ok", SmsFormatter.gsmSafe("it’s – ok"));
    }

    private static String repeat(char c, int n) { return repeat(String.valueOf(c), n); }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
