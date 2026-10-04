package org.pandastic.relay.brain;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Scores KeywordNlu on ml/llm/eval_sms.csv (synthetic SW/EN SMS written by the team, labelled synthetic).
 * Commodity is scored as the Resolver uses it: the crop's default when the message names none.
 * Predictions for ml/llm/eval_llm.py are written to build/nlu-eval/kw_{dev,heldout,fresh,fresh2}.csv (under android/app with Gradle).
 */
public class NluEvalTest {
    @Test public void keywordNluOnDevSet() throws Exception {
        int[] correct = evaluate("ml/llm/eval_sms.csv", "pandastic.evalOut", "kw_dev.csv");
        int n = correct[correct.length - 1];
        // Floors, not targets: they catch regressions in the lexicon or the matcher.
        assertTrue(correct[1] >= 0.85 * n);  // intent
        assertTrue(correct[2] >= 0.85 * n);  // crop
        assertTrue(correct[5] >= 0.95 * n);  // offer
    }

    /** Held-out set: written before any results and never used to tune the lexicon. Reported, not asserted. */
    @Test public void keywordNluOnHeldOutSet() throws Exception {
        evaluate("ml/llm/eval_sms_heldout.csv", "pandastic.evalOutHeldout", "kw_heldout.csv");
    }

    /** Fresh set: written after the Qwen LoRA was trained, in phrasings unlike its templates. Reported, not asserted. */
    @Test public void keywordNluOnFreshSet() throws Exception {
        evaluate("ml/llm/eval_sms_fresh.csv", "pandastic.evalOutFresh", "kw_fresh.csv");
    }

    /**
     * Second fresh set: written before the keyword fixes that the first fresh set's errors prompted, and never
     * looked at while making them, so it measures those fixes honestly. Reported, not asserted.
     */
    @Test public void keywordNluOnFresh2Set() throws Exception {
        evaluate("ml/llm/eval_sms_fresh2.csv", "pandastic.evalOutFresh2", "kw_fresh2.csv");
    }

    /** @return correct counts per slot, then the number of rows as the last element. */
    private static int[] evaluate(String relative, String outProperty, String defaultName) throws Exception {
        File file = FakeKnowledge.findRepoFile(relative);
        List<String[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) if (!line.trim().isEmpty()) rows.add(csv(line));

        KeywordNlu nlu = new KeywordNlu(new FakeKnowledge().lexicon());
        String[] slots = {"lang", "intent", "crop", "symptom", "commodity", "offer"};
        int[] correct = new int[slots.length];
        int allCorrect = 0;
        StringBuilder misses = new StringBuilder();
        StringBuilder out = new StringBuilder("id,lang,intent,crop,symptom,commodity,offer,intent_prob\n");
        for (String[] r : rows) {
            Slots s = nlu.parse(r[1], null);
            String commodity = "price".equals(s.intent) ? (s.commodity != null ? s.commodity : defaultCommodity(s.crop)) : null;
            String offer = s.offer == null ? "" : String.valueOf(Math.round(s.offer));
            String[] predicted = {s.lang, s.intent, s.crop, s.symptom, commodity, offer};
            boolean all = true;
            for (int i = 0; i < slots.length; i++) {
                if (same(r[2 + i], predicted[i])) correct[i]++;
                else { all = false; misses.append(String.format("  #%s %-50s %s: want '%s' got '%s'%n", r[0], r[1], slots[i], r[2 + i], nz(predicted[i]))); }
            }
            if (all) allCorrect++;
            out.append(r[0]);
            for (String p : predicted) out.append(',').append(nz(p));
            out.append(',').append(s.intentProb).append('\n');
        }
        StringBuilder report = new StringBuilder(String.format("KeywordNlu on %s (%d synthetic SMS):%n", relative, rows.size()));
        for (int i = 0; i < slots.length; i++) report.append(String.format("  %-9s %5.1f%%%n", slots[i], 100.0 * correct[i] / rows.size()));
        report.append(String.format("  all slots %5.1f%%%n", 100.0 * allCorrect / rows.size()));
        System.out.print(report);
        System.out.print(misses);
        // Gradle does not forward -D flags to the test JVM, so by default the predictions go to the module's
        // build/nlu-eval/ (git-ignored); -Dpandastic.evalOut* still overrides when the test runs outside Gradle.
        String target = System.getProperty(outProperty);
        File outFile = target != null ? new File(target) : new File("build/nlu-eval/" + defaultName);
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        try (PrintWriter w = new PrintWriter(outFile, "UTF-8")) { w.print(out); }
        int[] result = java.util.Arrays.copyOf(correct, correct.length + 1);
        result[correct.length] = rows.size();
        return result;
    }

    private static boolean same(String want, String got) { return nz(want).equals(nz(got)); }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String defaultCommodity(String crop) {
        if ("coffee".equals(crop)) return "coffee_arabica_parchment";
        if ("maize".equals(crop)) return "maize_grain";
        if ("bean".equals(crop)) return "beans_dry";
        return null;
    }

    /** Minimal CSV line parser: commas, double-quoted fields with "" escapes. */
    static String[] csv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') quoted = false;
                else field.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { fields.add(field.toString()); field.setLength(0); }
            else field.append(c);
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }
}
