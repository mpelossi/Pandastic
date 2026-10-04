package org.pandastic.relay.brain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every fixed sentence the app can say, in Swahili and English (the brief's "fixed list of answers").
 * GSM-7 characters only, so an SMS keeps 160 characters per segment. Safety sentences come first.
 * Swahili text is machine translated by the team and should be reviewed by a native speaker.
 */
public final class Templates {
    private Templates() {}

    private static final Map<String, String[]> NAMES = new HashMap<>();  // label/commodity -> {sw, en}
    static {
        name("coffee_healthy", "Jani la kahawa lenye afya", "Healthy coffee leaf");
        name("coffee_rust", "Kutu ya majani ya kahawa", "Coffee leaf rust");
        name("coffee_miner", "Mchimba majani ya kahawa", "Coffee leaf miner");
        name("coffee_cercospora", "Doa la jicho la kahawia", "Brown eye spot");
        name("coffee_phoma", "Doa la Phoma", "Phoma leaf spot");
        name("maize_healthy", "Jani la mahindi lenye afya", "Healthy maize leaf");
        name("maize_fall_armyworm", "Viwavijeshi", "Fall armyworm");
        name("maize_streak_virus", "Virusi vya michirizi ya mahindi", "Maize streak virus");
        name("maize_lethal_necrosis", "Ugonjwa hatari wa mahindi (MLN)", "Maize lethal necrosis");
        name("maize_leaf_blight", "Ukungu wa majani ya mahindi", "Northern leaf blight");
        name("maize_leaf_spot", "Madoa ya kijivu ya mahindi", "Grey leaf spot");
        name("bean_healthy", "Jani la maharage lenye afya", "Healthy bean leaf");
        name("bean_rust", "Kutu ya maharage", "Bean rust");
        name("bean_angular_leaf_spot", "Madoa ya pembe ya maharage", "Angular leaf spot");
        name("coffee", "kahawa", "coffee");
        name("maize", "mahindi", "maize");
        name("bean", "maharage", "beans");
        name("coffee_arabica_parchment", "Kahawa Arabica (parchment)", "Arabica parchment");
        name("coffee_arabica_drugar", "Kahawa Arabica (drugar)", "Arabica drugar");
        name("coffee_robusta_kiboko", "Kahawa Robusta (kiboko)", "Robusta kiboko");
        name("coffee_robusta_faq", "Kahawa Robusta (FAQ)", "Robusta FAQ");
        name("maize_grain", "Mahindi (nafaka)", "Maize grain");
        name("beans_dry", "Maharage makavu", "Dry beans");
    }

    private static final String[] MONTHS_SW = {"Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des"};
    private static final String[] MONTHS_EN = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private static void name(String key, String sw, String en) { NAMES.put(key, new String[]{sw, en}); }

    static boolean en(String lang) { return "en".equals(lang); }

    private static String t(String lang, String sw, String en) { return en(lang) ? en : sw; }

    /** Display name of a label, crop or commodity; the key itself if unknown. */
    public static String name(String key, String lang) {
        String[] pair = NAMES.get(key);
        return pair == null ? key : pair[en(lang) ? 1 : 0];
    }

    public static String askPerson(String lang) {
        return t(lang, "Sina uhakika - uliza mtu (afisa ugani au chama cha ushirika). Usinyunyizie dawa bado.",
            "Not sure - ask a person (extension officer or cooperative). Do not spray yet.");
    }

    public static String retake(String issue, String lang) {
        if ("blur".equals(issue)) return t(lang, "Picha haiko wazi. Shika simu bila kutikisa, karibu na jani moja, upige tena.",
            "The photo is blurry. Hold the phone still, close to one leaf, and take it again.");
        if ("dark".equals(issue)) return t(lang, "Picha ina giza sana. Piga tena kwenye mwanga wa mchana.",
            "The photo is too dark. Take it again in daylight.");
        if ("bright".equals(issue)) return t(lang, "Picha ina mwanga mwingi. Kinga jani na jua kali, upige tena.",
            "The photo is too bright. Shade the leaf from direct sun and take it again.");
        return t(lang, "Tafadhali piga picha tena.", "Please take the photo again.");
    }

    public static String unsupported(String lang) {
        return t(lang, "Hili halionekani kama jani la kahawa, mahindi au maharage ambalo programu inalijua. ",
            "This does not look like a coffee, maize or bean leaf the app knows. ") + askPerson(lang);
    }

    public static String uncertain(String a, String b, String lang) {
        String maybe = b == null ? name(a, lang) : name(a, lang) + t(lang, " au ", " or ") + name(b, lang);
        return askPerson(lang) + t(lang, " Inaweza kuwa ", " It may be ") + maybe + ".";
    }

    public static String askCropPhoto(String lang) {
        return t(lang, "Hili ni kahawa, mahindi au maharage? Chagua zao, kisha jaribu tena.",
            "Is this coffee, maize or beans? Choose the crop and try again.");
    }

    public static String askCropPrice(String lang) {
        return t(lang, "Bei ya zao gani? Tuma P 1 kahawa, P 2 mahindi au P 3 maharage, na bei ya mnunuzi. Mfano: P 1 12000",
            "Price of which crop? Send P 1 coffee, P 2 maize or P 3 beans, with the buyer's price. Example: P 1 12000");
    }

    public static String healthyTitle(String lang) { return t(lang, "Hakuna ugonjwa", "No disease found"); }

    public static String noAdvice(String label, String lang) {
        return name(label, lang) + t(lang, ". Programu bado haina ushauri kwa hili - uliza afisa ugani.",
            ". The app has no advice for this yet - ask the extension officer.");
    }

    /** Text-only symptom message: never a diagnosis, always "do not spray yet" first. */
    public static String textOnly(String[] candidates, String lang) {
        StringBuilder s = new StringBuilder(t(lang, "Sina uhakika kwa maneno pekee - usinyunyizie dawa bado.",
            "Not sure from words only - do not spray yet."));
        if (candidates.length > 0) {
            s.append(t(lang, " Inaweza kuwa ", " It could be "));
            for (int i = 0; i < candidates.length; i++) {
                if (i > 0) s.append(t(lang, " au ", " or "));
                s.append(name(candidates[i], lang));
            }
            s.append('.');
        }
        return s.append(t(lang, " Onyesha picha ya jani kwenye simu ya nyumbani, au uliza afisa ugani.",
            " Show a leaf photo on the home phone, or ask the extension officer.")).toString();
    }

    public static String help(String lang) {
        return t(lang, "Tuma 1 kahawa, 2 mahindi au 3 maharage na unachoona kwenye majani. Kwa bei tuma P, namba ya zao na bei ya mnunuzi, mfano P 1 12000.",
            "Send 1 coffee, 2 maize or 3 beans and what you see on the leaves. For prices send P, the crop number and the buyer's price, e.g. P 1 12000.");
    }

    public static String planting(String lang) {
        return t(lang, "Tarehe za kupanda bado hazipo kwenye programu. Uliza afisa ugani au chama cha ushirika.",
            "Planting dates are not in the app yet. Ask the extension officer or cooperative.");
    }

    public static String noPrice(String lang) {
        return t(lang, "Hakuna bei ya zao hilo bado. Uliza chama cha ushirika kabla ya kuuza.",
            "No price for that crop yet. Ask the cooperative before selling.");
    }

    public static String noModel(String lang) {
        return t(lang, "Kikagua picha hakiko tayari. ", "The photo checker is not ready. ") + askPerson(lang);
    }

    public static String seeHomePhone(String lang) {
        return t(lang, "Maelezo zaidi kwenye simu ya nyumbani.", "More details on the home phone.");
    }

    /**
     * Price answer. Every number comes from knowledge.sqlite or the farmer's message; the percentage is
     * computed in Java by the Resolver, never by a model.
     */
    public static String price(Knowledge.PriceRow row, String sourceShort, Double offer, Double gapPct, boolean stale, String lang) {
        StringBuilder s = new StringBuilder();
        String when = month(row.date, lang);
        if (stale) s.append(t(lang, "Bei ya zamani (", "Old price (")).append(when).append(t(lang, ") - hakikisha na chama. ", ") - check with the cooperative. "));
        s.append(name(row.commodity, lang)).append(", ");
        if ("Farm-gate".equals(row.pricetype)) s.append(t(lang, "bei ya shambani", "farm-gate"));
        else s.append(t(lang, "bei ya rejareja sokoni", "market retail"));
        if (row.market != null) s.append(' ').append(row.market);
        s.append(' ').append(when).append(": ").append(row.currency).append(' ').append(money(row.low));
        if (row.high > row.low) s.append('-').append(money(row.high));
        s.append('/').append(row.unit.toLowerCase(Locale.ROOT)).append(" (").append(sourceShort).append(").");
        if (offer != null) {
            // Against a retail reference, say so: a farm-gate offer is normally below retail, and "29% below" alone
            // would make a fair offer look like a cheat.
            boolean retail = !"Farm-gate".equals(row.pricetype);
            s.append(t(lang, " Bei ya ", " Offer ")).append(money(offer));
            if (offer < row.low) s.append(t(lang, retail ? " iko chini ya bei ya rejareja kwa " : " iko chini kwa ", " is "))
                .append(Math.round(Math.abs(gapPct))).append(t(lang, "%.", retail ? "% below retail." : "% below."));
            else if (offer > row.high) s.append(t(lang, " iko juu ya bei hii.", " is above this price."));
            else s.append(t(lang, " iko ndani ya bei hii.", " is within this price."));
        }
        if (!"Farm-gate".equals(row.pricetype)) s.append(t(lang, " Bei ya shambani huwa chini zaidi.", " Farm-gate is usually lower."));
        return s.append(t(lang, " Uliza chama kabla ya kuuza.", " Ask the cooperative before selling.")).toString();
    }

    /** "2026-08" -> "Aug 2026" / "Ago 2026". */
    static String month(String date, String lang) {
        try {
            int m = Integer.parseInt(date.substring(5, 7));
            return (en(lang) ? MONTHS_EN : MONTHS_SW)[m - 1] + " " + date.substring(0, 4);
        } catch (RuntimeException e) {
            return date;
        }
    }

    /** 15500 -> "15,500". */
    static String money(double value) {
        return String.format(Locale.US, "%,d", Math.round(value));
    }
}
