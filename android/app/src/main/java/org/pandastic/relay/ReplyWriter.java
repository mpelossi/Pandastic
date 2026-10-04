package org.pandastic.relay;

import android.util.Log;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pandastic.relay.brain.Decision;
import org.pandastic.relay.brain.LlmNlu;

/**
 * Grounded reply writing (user decision, 07:25 UTC): the on-phone model rewrites the fixed, cited answer as one or
 * two friendly sentences in the farmer's language. It sees only that answer (the "facts") and the farmer's message,
 * and check() reads every word it returns before anyone sees it: no number, crop, disease, pest, chemical, unit,
 * organisation or "healthy" claim that the facts do not contain; the facts' warnings ("not sure", "do not spray yet",
 * "ask the extension officer") must stay; a price keeps its UGX figure and source; the language must match. If any
 * check fails or the model is too slow, the fixed answer is used. The chat always shows the fixed answer below it.
 */
public final class ReplyWriter {
    private static final String TAG = "PandasticLlm";
    static final int MAX_TOKENS = 110, TIME_BUDGET_MS = 20_000, MAX_CHARS = 300;

    /** Words a rewrite may use only if the facts use them. Short ones (<= 3 letters) match whole words only. */
    private static final List<String> GUARDED = Arrays.asList(
        // crops
        "kahawa", "coffee", "mahindi", "maize", "maharage", "bean", "muhogo", "cassava", "nyanya", "tomato", "ndizi",
        "banana", "matooke", "chai", "tea",
        // problems
        "kutu", "rust", "mchimba", "miner", "doa", "madoa", "spot", "phoma", "cercospora", "viwavi", "armyworm", "virusi", "virus",
        "michirizi", "streak", "ukungu", "blight", "necrosis", "mln", "wadudu", "pest", "insect", "ugonjwa", "disease",
        "kuvu", "fung", "bakteria", "bacteria", "kuoza", "rot",
        // chemicals, doses, units
        "kiuatilifu", "viuatilifu", "pesticide", "insecticide", "herbicide", "copper", "shaba", "mancozeb", "ridomil",
        "chlorpyrifos", "cypermethrin", "emamectin", "metalaxyl", "salfa", "sulphur", "sulfur", "mbolea", "fertili",
        "ml", "lita", "litre", "liter", "gram", "kilo", "kg", "ugx", "shilingi", "shilling", "asilimia", "percent",
        // organisations, places (an invented price is caught by the number rule)
        "maaif", "ucda", "wfp", "naro", "kampala", "nairobi", "kenya", "uganda",
        // health claims and judgments (probe 07:50: "Hii ni sawa" / "that's a fair price" for an offer 23% below)
        "afya", "healthy", "mzima", "salama", "safe", "tiba", "cure", "pona", "sawa", "fair", "good", "great", "fine");
    /** More of these in the rewrite than in the facts can flip a meaning ("copper is not needed"). */
    private static final Set<String> NEGATIONS = new HashSet<>(Arrays.asList(
        "not", "no", "never", "don't", "dont", "isn't", "aren't", "doesn't", "cannot", "can't", "without", "unless",
        "si", "sio", "hapana", "hakuna", "bila"));
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,.]*");
    private static final Set<String> CROPS = new HashSet<>(Arrays.asList(
        "kahawa", "coffee", "mahindi", "maize", "maharage", "bean"));
    private static final Pattern UNSURE = Pattern.compile(
        "not sure|unsure|not certain|can't (confirm|tell|be sure)|cannot (confirm|tell|be sure)|hard to (say|tell)");
    /** "do not spray yet" kept: "don't / won't / not … spray", or "before spraying". */
    private static final Pattern NO_SPRAY = Pattern.compile(
        "(\\b(not|no|never|don't|dont|won't|can't|cannot)\\b[^.!?]{0,40}\\bspray)|before spraying");
    private static final Pattern SOURCE = Pattern.compile("\\(([A-Z][A-Za-z/]+)\\)");
    private static final Set<String> EN_WORDS = new HashSet<>(Arrays.asList(
        "the", "is", "your", "you", "and", "to", "of", "not", "please", "leaves", "it", "a", "are", "with"));
    private static final Set<String> SW_WORDS = new HashSet<>(Arrays.asList(
        "ya", "na", "kwa", "ni", "la", "wa", "za", "bado", "yako", "hii", "kwenye", "au", "pole", "usinyunyizie"));

    /** Everyday Swahili a rewrite may add; every other Swahili word must come from the facts or the farmer's message. */
    private static final Set<String> SW_EVERYDAY = new HashSet<>(Arrays.asList(
        "pole", "asante", "mama", "baba", "habari", "ndiyo", "hapana", "tafadhali", "sasa", "yako", "yangu", "wako",
        "wangu", "zako", "zangu", "lako", "langu", "hii", "hiyo", "huu", "hizi", "hilo", "hili", "kwa", "na", "ni", "ya",
        "wa", "la", "za", "kwenye", "katika", "au", "lakini", "kama", "huenda", "labda", "pia", "sana", "tu", "kisha",
        "kwanza", "bado", "si", "sio", "hakuna", "kuna", "nini", "gani", "je", "karibu", "rafiki", "mkulima", "jibu",
        "swali", "kuhusu", "ili", "hivyo", "basi", "ona", "naona", "nimeona", "mwanangu", "mpendwa"));

    private ReplyWriter() {}

    /** The model's rewrite of the facts, checked; null means "send the fixed answer". Call on the model thread. */
    public static String write(LlmNlu model, String question, String facts, Decision decision) {
        if (model == null || facts == null || facts.isEmpty()) return null;
        boolean en = "en".equals(decision.lang);
        String farmer = question == null || question.trim().isEmpty()
            ? (en ? "(sent a leaf photo)" : "(ametuma picha ya jani)") : question.trim();
        String user = (en ? "Farmer: " : "Mkulima: ") + farmer + "\nFACTS: " + facts;
        String output = model.write(system(en), user, MAX_TOKENS, TIME_BUDGET_MS);
        String checked = check(output, facts, question, decision.lang, decision.status);
        Log.i(TAG, "LLM reply " + (checked != null ? "passed the checks" : "rejected: " + reason));
        // The rejected wording, to tune the prompt. It can echo the farmer's words, so it is logged only after
        // `adb shell setprop log.tag.PandasticLlm DEBUG` (the default level is INFO).
        if (checked == null && output != null && Log.isLoggable(TAG, Log.DEBUG))
            Log.d(TAG, "LLM reply text: " + output.replace('\n', ' '));
        return checked;
    }

    static String system(boolean en) {
        String language = en ? "English" : "Swahili";
        return "You are Pandastic, a friendly farm helper chatting with a smallholder farmer in Uganda. Answer the "
            + "farmer's message in " + language + " in 1 or 2 short, warm sentences, using only the FACTS: keep every "
            + "number, price, unit, source and name exactly as written, and add nothing else: no new disease, pest, "
            + "chemical, dose, price or advice. If the FACTS say not sure, do not spray yet, or ask the extension "
            + "officer, say that too. Write only the reply, in " + language + ".";
    }

    /** Why the last check failed, for logcat and tests (model thread only). */
    static String reason = "";

    /** The cleaned rewrite if it passes every rule, else null (the fixed answer is used). Pure Java: JVM-tested. */
    static String check(String output, String facts, String question, String lang, String status) {
        if (output == null || output.trim().isEmpty()) return fail("empty or timed out");
        String text = output.trim().replaceAll("\\s+", " ");
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) text = text.substring(1, text.length() - 1).trim();
        if (text.regionMatches(true, 0, "Pandastic:", 0, 10)) text = text.substring(10).trim();
        String lower = text.toLowerCase(Locale.ROOT), factsLower = facts.toLowerCase(Locale.ROOT);
        if (text.length() < 20) return fail("too short");
        if (text.length() > MAX_CHARS) return fail("too long");
        for (String bad : new String[]{"{", "}", "<", ">", "http", "*", "#", "facts", "mkulima:", "farmer:"})
            if (lower.contains(bad)) return fail("format: " + bad);

        Set<String> factNumbers = new HashSet<>();
        for (Matcher m = NUMBER.matcher(facts); m.find(); ) factNumbers.add(digits(m.group()));
        for (Matcher m = NUMBER.matcher(text); m.find(); )
            if (!factNumbers.contains(digits(m.group()))) return fail("new number " + m.group());

        Set<String> words = new HashSet<>(Arrays.asList(lower.split("[^\\p{L}0-9%]+")));
        Set<String> factWords = new HashSet<>(Arrays.asList(factsLower.split("[^\\p{L}0-9%]+")));
        String asked = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (String term : GUARDED) {
            boolean said = term.length() <= 3 ? words.contains(term) : lower.contains(term);
            boolean given = term.length() <= 3 ? factWords.contains(term) : factsLower.contains(term);
            // Naming the crop the farmer asked about is fine ("your coffee"); every other term must be in the facts.
            if (said && !given && !(CROPS.contains(term) && asked.contains(term))) return fail("new term " + term);
        }

        // The facts' warnings must survive the rewrite.
        if (factsLower.contains("usinyunyizie") && !lower.contains("usinyunyizie")) return fail("dropped: usinyunyizie");
        if (factsLower.contains("do not spray") && !NO_SPRAY.matcher(lower).find()) return fail("dropped: do not spray");
        if (factsLower.contains("sina uhakika") && !lower.contains("uhakika")) return fail("dropped: sina uhakika");
        if (factsLower.contains("not sure") && !UNSURE.matcher(lower).find()) return fail("dropped: not sure");
        if (factsLower.contains("afisa ugani") && !(lower.contains("afisa") || lower.contains("uliza"))) return fail("dropped: afisa");
        if (factsLower.contains("extension officer") && !(words.contains("officer") || words.contains("ask"))) return fail("dropped: officer");
        if ("PRICE".equals(status) || "PRICE_STALE".equals(status)) {
            Matcher ugx = Pattern.compile("UGX ([\\d,]+)").matcher(facts);
            if (ugx.find() && !text.contains(ugx.group(1))) return fail("dropped price " + ugx.group(1));
            Matcher source = SOURCE.matcher(facts);
            if (source.find() && !text.contains(source.group(1).split("/")[0])) return fail("dropped source " + source.group(1));
        }

        if (negations(lower) > negations(factsLower)) return fail("more negations than the facts (flipped meaning?)");

        // It must carry the answer ("Thank you for asking." alone does not) and say more than a copy of it.
        Set<String> shared = new HashSet<>(words);
        shared.retainAll(factWords);
        shared.removeIf(w -> w.length() < 3);
        if (shared.size() < Math.min(5, factWords.size() / 2)) return fail("does not carry the answer");
        Set<String> union = new HashSet<>(words);
        union.addAll(factWords);
        union.removeIf(w -> w.length() < 3);
        if (shared.size() >= 0.85 * union.size()) return fail("a copy of the fixed answer");

        // A 0.8B model writes Swahili non-words ("majindi", "Nakupya"): a Swahili rewrite may only reuse words of
        // the facts or the farmer's message (the guarded terms above still come from the facts alone).
        if (!"en".equals(lang)) {
            Set<String> known = new HashSet<>(factWords);
            if (question != null) known.addAll(Arrays.asList(question.toLowerCase(Locale.ROOT).split("[^\\p{L}0-9%]+")));
            for (String w : words)
                if (w.length() >= 3 && !w.matches(".*\\d.*") && !known.contains(w) && !SW_EVERYDAY.contains(w))
                    return fail("unknown Swahili word " + w);
        }

        int en = 0, sw = 0;
        for (String w : words) { if (EN_WORDS.contains(w)) en++; if (SW_WORDS.contains(w)) sw++; }
        if ("en".equals(lang) ? sw > en : en > sw) return fail("wrong language (en " + en + ", sw " + sw + ")");
        reason = "";
        return text;
    }

    private static String fail(String why) { reason = why; return null; }

    private static int negations(String lowerText) {
        int count = 0;
        // "do not spray yet" only adds caution: it never counts as a flip.
        lowerText = NO_SPRAY.matcher(lowerText).replaceAll(" ");
        for (String w : lowerText.split("[^\\p{L}']+"))
            if (NEGATIONS.contains(w) || (w.startsWith("usi") && w.length() > 5) || w.endsWith("n't")) count++;
        return count;
    }

    private static String digits(String number) { return number.replaceAll("[^0-9]", ""); }
}
