package org.pandastic.relay.hub;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pandastic.relay.brain.Slots;

/**
 * Which SMS from an allowed contact the helper phone answers by itself. Noor texts her daughter about
 * everything ("how is school?"), and the daughter's phone is the helper: personal messages must never get an
 * automatic reply. Pandastic only listens; every SMS still arrives in the phone's normal SMS app, so the
 * daughter reads and answers personal ones as always. The helper answers only clear farming questions:
 * <ul>
 *   <li>a message that is only a request for the menu ("?", "msaada", "nisaidie", "help", "what can you do");</li>
 *   <li>a price, crop problem or planting question that the keywords recognised;</li>
 *   <li>one the language model recognised, if the keywords also found a crop word in it (the model alone
 *       sometimes reads chit-chat as a question, and a wrong reply to a family message is worse than none).</li>
 * </ul>
 */
public final class HubPolicy {
    private static final Pattern ONLY_QUESTION_MARKS = Pattern.compile("^\\s*\\?+\\s*$");
    private static final Pattern MENU_PHRASE = Pattern.compile(
        "(what can you do|how does (this|it) work|how (do i|to) use (this|it|pandastic)|unaweza kufanya nini|inafanyaje kazi)");
    private static final Set<String> MENU_WORDS = new HashSet<>(Arrays.asList(
        "msaada", "menyu", "menu", "help", "pandastic", "nisaidie", "nisaidia", "saidia", "info"));
    // A menu word counts only with greetings and politeness around it: "nisaidie pesa" (help me with money)
    // is for the daughter, not the helper. A farming word next to it ("msaada kahawa") also counts.
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
        "me", "please", "pls", "plz", "hi", "hello", "hey", "tafadhali", "naomba", "habari", "jambo", "mambo",
        "sasa", "salaam", "mama", "i", "need", "nahitaji", "nataka"));
    // Used only when the models failed: then nothing tells us the intent, so a few unmistakable words decide.
    private static final Pattern FARMING_WORDS = Pattern.compile(
        "(^|[^a-z])(p ?[123]|kahawa|mahindi|maharage|coffee|maize|beans?|bei|price|majani|leaves|leaf|"
            + "wadudu|pests?|ugonjwa|disease|mbolea|kupanda|plant)([^a-z]|$)");

    private HubPolicy() {}

    /**
     * @param keywords what the keyword NLU alone read from the SMS (null if unavailable)
     * @param intent   the final intent of the answer (keywords, or the language model when they found none)
     */
    public static boolean isFarmingQuestion(String text, Slots keywords, String intent) {
        if (text == null) return false;
        if (asksForMenu(text)) return true;
        if (keywords == null) return looksLikeFarming(text);
        if (keywords.intentProb > 0 && isQuestion(keywords.intent)) return true;
        return isQuestion(intent) && keywords.crop != null;
    }

    /** "?", "msaada", "nisaidie tafadhali", "what can you do", "help coffee"; not "nisaidie pesa ya ada". */
    public static boolean asksForMenu(String text) {
        if (text == null) return false;
        if (ONLY_QUESTION_MARKS.matcher(text).find()) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher phrase = MENU_PHRASE.matcher(lower);
        boolean asked = phrase.find();
        boolean otherWords = false;
        for (String word : phrase.replaceAll(" ").split("[^\\p{L}0-9]+")) {
            if (word.isEmpty() || FILLER.contains(word)) continue;
            if (MENU_WORDS.contains(word)) asked = true;
            else otherWords = true;
        }
        return asked && (!otherWords || FARMING_WORDS.matcher(lower).find());
    }

    /** Fallback when no NLU ran (models failed): unmistakable farming words only. */
    public static boolean looksLikeFarming(String text) {
        return text != null && (asksForMenu(text) || FARMING_WORDS.matcher(text.toLowerCase(Locale.ROOT)).find());
    }

    private static boolean isQuestion(String intent) {
        return "price".equals(intent) || "diagnose".equals(intent) || "planting".equals(intent);
    }
}
