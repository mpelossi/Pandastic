package org.pandastic.relay.brain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword NLU over the knowledge.sqlite lexicon (contracts §3, §4). Deterministic and always available;
 * it is the fallback when the LLM is missing, slow, or returns something that fails the checks.
 */
public final class KeywordNlu implements Nlu {
    /** Order used to break ties between intents with the same number of hits. */
    private static final String[] INTENT_PRIORITY = {"price", "diagnose", "planting"};
    /**
     * 12000 | 12,000 | 12.000 | 12.5, optionally followed by k (thousand) or preceded by "elfu" (thousand).
     * A space is never a thousands separator: in "P 1 12000" the 1 is a menu code, not part of the price.
     */
    private static final Pattern NUMBER = Pattern.compile(
        "(elfu\\s*)?(?<!\\d)(\\d{1,3}(?:[,.]\\d{3})+(?!\\d)|\\d+(?:\\.\\d+)?)\\s*(k\\b|elfu\\b)?", Pattern.CASE_INSENSITIVE);
    /** Prices per kg in UGX are well above this; smaller numbers are menu codes (1, 2, 3) or quantities. */
    private static final double MIN_OFFER = 100;
    /**
     * Frequent function words. They decide the reply language when the lexicon terms don't ("kiboko ... ni sawa?",
     * "how does this work"). Each distinct word counts like one lexicon term.
     */
    private static final Set<String> SW_WORDS = new HashSet<>(Arrays.asList(
        "ni", "na", "ya", "wa", "za", "la", "kwa", "hii", "huu", "hapa", "sana", "sawa", "gani", "ngapi", "nini", "lini",
        "yangu", "wangu", "zangu", "langu", "kuna", "je", "leo", "kesho", "tafadhali", "asante", "mimi", "yote", "kama",
        "bado", "sasa", "au", "lakini", "pia", "ndani", "juu", "chini", "mtu", "hapana", "ndiyo"));
    private static final Set<String> EN_WORDS = new HashSet<>(Arrays.asList(
        "the", "is", "are", "my", "how", "what", "when", "does", "do", "this", "that", "of", "for", "to", "and", "a", "an",
        "i", "it", "have", "has", "with", "in", "on", "at", "can", "should", "much", "today", "please", "pls", "you",
        "your", "there", "be", "not", "why", "which", "per", "was", "will", "get"));

    private final List<Rule> rules = new ArrayList<>();
    /** Terms listed under both languages ("kiboko", "faq") say nothing about which language the farmer writes in. */
    private final Set<String> bothLanguages = new HashSet<>();

    public KeywordNlu(List<Knowledge.LexiconEntry> lexicon) {
        Set<String> sw = new HashSet<>(), en = new HashSet<>();
        for (Knowledge.LexiconEntry entry : lexicon) {
            rules.add(new Rule(entry));
            ("sw".equals(entry.lang) ? sw : en).add(entry.term);
        }
        for (String term : sw) if (en.contains(term)) bothLanguages.add(term);
    }

    @Override public Slots parse(String text, String lang) {
        String normalized = normalize(text);
        Map<String, java.util.Set<String>> intents = new LinkedHashMap<>(), crops = new LinkedHashMap<>(),
            symptoms = new LinkedHashMap<>(), commodities = new LinkedHashMap<>();
        // Terms from both languages always count: people mix Swahili and English in one SMS ("bei ya coffee").
        // The language only decides which language the reply is written in. A term counts once per language,
        // even when it fills several slots ("faq" is both a crop and a commodity).
        java.util.Set<String> swTerms = new java.util.HashSet<>(), enTerms = new java.util.HashSet<>();
        for (Rule rule : rules) {
            if (!rule.pattern.matcher(normalized).find()) continue;
            boolean languageNeutral = rule.entry.term.length() <= 1 || bothLanguages.contains(rule.entry.term);  // "1", "p", "?", "kiboko"
            if (!languageNeutral) ("sw".equals(rule.entry.lang) ? swTerms : enTerms).add(rule.entry.term);
            Map<String, java.util.Set<String>> target;
            switch (rule.entry.slot) {
                case "intent": target = intents; break;
                case "crop": target = crops; break;
                case "symptom": target = symptoms; break;
                case "commodity": target = commodities; break;
                default: continue;
            }
            // Distinct terms per value: "1" listed under both languages is still one hit.
            target.computeIfAbsent(rule.entry.value, k -> new java.util.HashSet<>()).add(rule.entry.term);
        }
        for (String word : normalized.split(" ")) {
            if (SW_WORDS.contains(word)) swTerms.add(word);
            else if (EN_WORDS.contains(word)) enTerms.add(word);
        }
        Slots slots = new Slots();
        slots.lang = lang != null ? lang : (enTerms.size() > swTerms.size() ? "en" : "sw");  // Swahili first (decision D3)
        slots.offer = offer(text);
        slots.crop = unique(crops);
        // A symptom word can mean different conditions on different crops ("mistari", lines: leaf-miner trails on coffee,
        // streak virus on maize). Once the crop is known, keep only symptoms that form a real label with it.
        if (slots.crop != null) symptoms.keySet().removeIf(s -> !Resolver.LABELS.contains(slots.crop + "_" + s));
        slots.symptom = unique(symptoms);
        slots.commodity = unique(commodities);
        slots.intent = intent(intents, slots);
        // 0 = no intent evidence at all (the "other" default): the only case where LlmNlu should take the LLM's intent.
        slots.intentProb = intents.isEmpty() && slots.symptom == null ? 0f : 1f;
        return slots;
    }

    /**
     * Lowercase, keep letters, digits and '?', collapse everything else to single spaces.
     * Thousands separators are joined first, so "1,200" stays one number and never looks like menu code "1".
     * The price code typed without its space ("P1 13000") is split like "P 1 13000", but only at the start of an SMS
     * that also has a number: "P1".."P7" are also Ugandan primary-school years ("Sarah's P3 fees").
     */
    static String normalize(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT).replace("ŋ", "ng'").replaceAll("(?<=\\d)[,.](?=\\d{3}(?!\\d))", "");
        return lower.replaceAll("[^a-z0-9?]+", " ").trim().replaceFirst("^p([123]) (?=.*\\d{2})", "p $1 ");
    }

    /** Largest number in the text that looks like a price: digits ("12k", "elfu 12") or Swahili words ("elfu kumi na mbili"). */
    static Double offer(String text) {
        if (text == null) return null;
        Double best = null;
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String digits = m.group(2);
            double value;
            if (digits.matches("\\d{1,3}(?:[,.]\\d{3})+")) value = Double.parseDouble(digits.replaceAll("[,.]", ""));
            else value = Double.parseDouble(digits);
            if (m.group(1) != null || m.group(3) != null) value *= 1000;
            if (value >= MIN_OFFER && (best == null || value > best)) best = value;
        }
        Double words = swahiliNumber(normalize(text));
        if (words != null && words >= MIN_OFFER && (best == null || words > best)) best = words;
        return best;
    }

    private static final Map<String, Integer> SW_UNITS = new LinkedHashMap<>();
    private static final Map<String, Integer> SW_TENS = new LinkedHashMap<>();
    static {
        String[] units = {"moja", "mbili", "tatu", "nne", "tano", "sita", "saba", "nane", "tisa"};
        String[] tens = {"kumi", "ishirini", "thelathini", "arobaini", "hamsini", "sitini", "sabini", "themanini", "tisini"};
        for (int i = 0; i < 9; i++) { SW_UNITS.put(units[i], i + 1); SW_TENS.put(tens[i], 10 * (i + 1)); }
    }

    /**
     * Largest Swahili number written in words. Swahili puts the multiplier first:
     * "elfu kumi na mbili" = 12,000; "elfu kumi na mbili na mia tano" = 12,500; "mia tisa" = 900; "elfu moja mia mbili" = 1,200.
     */
    static Double swahiliNumber(String normalized) {
        String[] w = normalized.isEmpty() ? new String[0] : normalized.split(" ");
        Double best = null;
        for (int start = 0; start < w.length; start++) {
            if (!isNumberWord(w[start]) || "na".equals(w[start])) continue;
            int[] pos = {start};
            long value = 0;
            if ("elfu".equals(w[pos[0]])) {
                pos[0]++;
                long thousands = belowThousand(w, pos);
                value += (thousands == 0 ? 1 : thousands) * 1000;
                skipNa(w, pos);
            }
            if (pos[0] < w.length && "mia".equals(w[pos[0]])) {
                pos[0]++;
                long hundreds = unit(w, pos);
                value += (hundreds == 0 ? 1 : hundreds) * 100;
                skipNa(w, pos);
            }
            value += tensAndUnits(w, pos);
            if (value > 0 && (best == null || value > best)) best = (double) value;
        }
        return best;
    }

    private static boolean isNumberWord(String s) {
        return SW_UNITS.containsKey(s) || SW_TENS.containsKey(s) || "mia".equals(s) || "elfu".equals(s) || "na".equals(s);
    }

    private static long belowThousand(String[] w, int[] pos) {
        long value = 0;
        if (pos[0] < w.length && "mia".equals(w[pos[0]])) {
            pos[0]++;
            long hundreds = unit(w, pos);
            value += (hundreds == 0 ? 1 : hundreds) * 100;
            if (pos[0] + 1 < w.length && "na".equals(w[pos[0]]) && !"mia".equals(w[pos[0] + 1])) pos[0]++;
        }
        return value + tensAndUnits(w, pos);
    }

    private static long tensAndUnits(String[] w, int[] pos) {
        long value = 0;
        if (pos[0] < w.length && SW_TENS.containsKey(w[pos[0]])) {
            value += SW_TENS.get(w[pos[0]++]);
            if (pos[0] + 1 < w.length && "na".equals(w[pos[0]]) && SW_UNITS.containsKey(w[pos[0] + 1])) {
                value += SW_UNITS.get(w[pos[0] + 1]);
                pos[0] += 2;
            }
        } else {
            value += unit(w, pos);
        }
        return value;
    }

    private static long unit(String[] w, int[] pos) {
        if (pos[0] < w.length && SW_UNITS.containsKey(w[pos[0]])) return SW_UNITS.get(w[pos[0]++]);
        return 0;
    }

    private static void skipNa(String[] w, int[] pos) {
        if (pos[0] < w.length && "na".equals(w[pos[0]])) pos[0]++;
    }

    private static String intent(Map<String, java.util.Set<String>> intents, Slots slots) {
        // A crop plus a price-like number ("mahindi 900") is a price question even without a price word.
        if (slots.offer != null && slots.crop != null) intents.computeIfAbsent("price", k -> new java.util.HashSet<>()).add("<offer>");
        String best = null;
        int bestCount = 0;
        for (String candidate : INTENT_PRIORITY) {
            int count = intents.containsKey(candidate) ? intents.get(candidate).size() : 0;
            if (count > bestCount) { best = candidate; bestCount = count; }
        }
        if (best != null) return best;
        if (slots.symptom != null) return "diagnose";
        if (intents.containsKey("help")) return "help";
        return "other";
    }

    /** The value with the most distinct terms; null if none or if two values tie. */
    private static String unique(Map<String, java.util.Set<String>> hits) {
        String best = null;
        int bestCount = 0;
        boolean tie = false;
        for (Map.Entry<String, java.util.Set<String>> e : hits.entrySet()) {
            int count = e.getValue().size();
            if (count > bestCount) { best = e.getKey(); bestCount = count; tie = false; }
            else if (count == bestCount) tie = true;
        }
        return tie ? null : best;
    }

    private static final class Rule {
        final Knowledge.LexiconEntry entry;
        final Pattern pattern;

        Rule(Knowledge.LexiconEntry entry) {
            this.entry = entry;
            String term = Pattern.quote(normalizeTerm(entry.term));
            String start = "(?<![a-z0-9?])", end = "(?![a-z0-9?])";
            switch (entry.match) {
                case "token": pattern = Pattern.compile(start + term + end); break;
                case "substring": pattern = Pattern.compile(term); break;
                default: pattern = Pattern.compile(start + term); break;  // "prefix"
            }
        }

        private static String normalizeTerm(String term) {
            return "?".equals(term) ? "?" : normalize(term);
        }
    }
}
