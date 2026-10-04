package org.pandastic.relay.brain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.LongSupplier;

/**
 * Decides every status in code (contracts §2). Confidence comes only from the calibrated classifier;
 * text alone never gives a diagnosis; prices come only from knowledge.sqlite and the gap is computed here.
 */
final class Resolver {
    /** Contracts §1 full label set. Text-only answers may name these as candidates, never as a diagnosis. */
    static final Set<String> LABELS = new LinkedHashSet<>(Arrays.asList(
        "coffee_healthy", "coffee_rust", "coffee_miner", "coffee_cercospora", "coffee_phoma",
        "maize_healthy", "maize_fall_armyworm", "maize_streak_virus", "maize_lethal_necrosis", "maize_leaf_blight", "maize_leaf_spot",
        "bean_healthy", "bean_angular_leaf_spot", "bean_rust"));
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int DEFAULT_STALE_DAYS = 120;

    private final Knowledge knowledge;
    private final LongSupplier clock;

    Resolver(Knowledge knowledge, LongSupplier clock) {
        this.knowledge = knowledge;
        this.clock = clock;
    }

    /** Contracts §2 steps 1-4. cropHint comes from the question text (may be null). */
    Decision photo(String qualityIssue, ClassifierResult result, String cropHint, String lang) {
        return photo(qualityIssue, result, cropHint, null, lang);
    }

    /**
     * symptomHint is what the farmer's words describe (may be null). A photo is only CONFIDENT when the words
     * don't contradict it: "the leaves have rust" with a photo that looks healthy (or like another disease)
     * becomes UNCERTAIN and names both, so a person checks.
     */
    Decision photo(String qualityIssue, ClassifierResult result, String cropHint, String symptomHint, String lang) {
        Decision d = base("photo", lang);
        if (qualityIssue != null) {
            d.status = "RETAKE";
            d.quality = qualityIssue;
            d.title = Templates.retake(qualityIssue, lang);
            d.message = d.title;
            return d;
        }
        if (result == null) return noData(d, Templates.noModel(lang));
        d.modelVersion = result.modelVersion;
        int top = result.top1(), second = result.top2();
        String label = result.labels[top];
        float p1 = result.probs[top], p2 = second < 0 ? 0f : result.probs[second];
        d.label = label;
        d.prob = p1;
        if (second >= 0) { d.runnerUp = result.labels[second]; d.runnerUpProb = p2; }
        if ("other".equals(label)) {
            d.status = "UNSUPPORTED";
            d.title = Templates.askPerson(lang);
            d.message = Templates.unsupported(lang);
            return d;
        }
        String crop = cropOf(label);
        String runnerCrop = second < 0 ? null : cropOf(result.labels[second]);
        float margin = p1 - p2;
        boolean cropsDisagree = runnerCrop != null && !runnerCrop.equals(crop) && margin < result.minMargin;
        if ((cropHint != null && !cropHint.equals(crop)) || cropsDisagree) {
            d.status = "ASK_CROP";
            d.title = Templates.askCropPhoto(lang);
            d.message = d.title;
            return d;
        }
        d.crop = crop;
        if (p1 < result.minProbFor(label) || margin < result.minMargin) {
            d.status = "UNCERTAIN";
            d.candidates = d.runnerUp == null || "other".equals(d.runnerUp) ? new String[]{label} : new String[]{label, d.runnerUp};
            d.title = Templates.askPerson(lang);
            d.message = Templates.uncertain(label, d.candidates.length > 1 ? d.candidates[1] : null, lang);
            return d;
        }
        if (symptomHint != null && !label.equals(crop + "_" + symptomHint)) {
            String described = LABELS.contains(crop + "_" + symptomHint) ? crop + "_" + symptomHint : null;
            d.status = "UNCERTAIN";
            d.candidates = described == null ? new String[]{label} : new String[]{label, described};
            d.title = Templates.askPerson(lang);
            d.message = Templates.uncertain(label, described, lang);
            return d;
        }
        d.status = "CONFIDENT";
        Knowledge.Advice advice = knowledge.advice(label, lang);
        d.title = label.endsWith("_healthy") ? Templates.healthyTitle(lang) : Templates.name(label, lang);
        if (advice == null) {
            d.message = Templates.noAdvice(label, lang);
            d.escalate = true;  // a confident label we have no cited advice for still goes to a person
            return d;
        }
        d.adviceSms = advice.sms;
        d.adviceLong = advice.longText;
        d.translation = advice.translation;
        d.source = knowledge.source(advice.sourceId);
        d.message = advice.longText;
        d.escalate = false;
        return d;
    }

    /** Text without a photo: candidates only, always "do not spray yet" + photo or extension officer. */
    Decision textOnly(Slots slots) {
        Decision d = base("diagnose", slots.lang);
        d.status = "TEXT_ONLY";
        List<String> candidates = new ArrayList<>();
        if (slots.symptom != null) {
            for (String label : LABELS) {
                if (!label.endsWith("_" + slots.symptom)) continue;
                if (slots.crop == null || label.equals(slots.crop + "_" + slots.symptom)) candidates.add(label);
            }
        }
        if (slots.crop != null) d.crop = slots.crop;
        else if (candidates.size() == 1) d.crop = cropOf(candidates.get(0));
        d.candidates = candidates.toArray(new String[0]);
        if (candidates.size() == 1) d.label = candidates.get(0);
        d.title = Templates.askPerson(d.lang);
        d.message = Templates.textOnly(d.candidates, d.lang);
        return d;
    }

    Decision price(Slots slots) {
        Decision d = base("price", slots.lang);
        String commodity = slots.commodity != null ? slots.commodity : defaultCommodity(slots.crop);
        if (commodity == null) {
            d.status = "ASK_CROP";
            d.title = Templates.askCropPrice(d.lang);
            d.message = d.title;
            return d;
        }
        d.crop = commodity.startsWith("coffee") ? "coffee" : commodity.startsWith("maize") ? "maize" : "bean";
        Knowledge.PriceRow row = knowledge.latestPrice(commodity, null);
        if (!commodity.startsWith("coffee")) {  // maize/beans: the home market if fresh, else the national range
            Knowledge.PriceRow local = knowledge.latestPrice(commodity, knowledge.meta("home_market"));
            if (local != null && (row == null || !isStale(local.date) || local.date.compareTo(row.date) >= 0)) row = local;
        }
        if (row == null) return noData(d, Templates.noPrice(d.lang));
        boolean stale = isStale(row.date);
        Decision.Price p = new Decision.Price();
        p.commodity = row.commodity;
        p.name = Templates.name(row.commodity, d.lang);
        p.market = row.market;
        p.pricetype = row.pricetype;
        p.low = row.low;
        p.high = row.high;
        p.currency = row.currency;
        p.unit = row.unit;
        p.date = row.date;
        p.sourceId = row.sourceId;
        p.derived = row.derived;
        p.stale = stale;
        p.offer = slots.offer;
        if (slots.offer != null && row.low > 0) p.gapPct = Math.round((slots.offer - row.low) / row.low * 1000) / 10.0;
        d.price = p;
        d.source = knowledge.source(row.sourceId);
        d.status = stale ? "PRICE_STALE" : "PRICE";
        d.escalate = stale;
        d.title = p.name;
        d.message = Templates.price(row, sourceShort(row), p.offer, p.gapPct, stale, d.lang);
        return d;
    }

    Decision planting(Slots slots) {
        Decision d = base("planting", slots.lang);
        if (slots.crop != null) d.crop = slots.crop;
        return noData(d, Templates.planting(d.lang));
    }

    Decision help(Slots slots) {
        Decision d = base(slots.intent == null ? "other" : slots.intent, slots.lang);
        d.status = "HELP";
        d.escalate = false;
        d.title = "Pandastic";
        d.message = Templates.help(d.lang);
        return d;
    }

    boolean isStale(String date) {
        String days = knowledge.meta("price_stale_days");
        int limit = DEFAULT_STALE_DAYS;
        try { if (days != null) limit = Integer.parseInt(days); } catch (NumberFormatException ignored) { }
        return ageDays(date) > limit;
    }

    /** Age of a 'YYYY-MM' (taken as the 15th) or 'YYYY-MM-DD' date, in days. */
    long ageDays(String date) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        int day = date.length() >= 10 ? Integer.parseInt(date.substring(8, 10)) : 15;
        c.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1, day);
        return (clock.getAsLong() - c.getTimeInMillis()) / DAY_MS;
    }

    private static Decision base(String intent, String lang) {
        Decision d = new Decision();
        d.intent = intent;
        d.lang = "en".equals(lang) ? "en" : "sw";
        return d;
    }

    private static Decision noData(Decision d, String message) {
        d.status = "NO_DATA";
        d.title = message;
        d.message = message;
        return d;
    }

    static String cropOf(String label) {
        int i = label.indexOf('_');
        return i < 0 ? null : label.substring(0, i);
    }

    private static String defaultCommodity(String crop) {
        if ("coffee".equals(crop)) return "coffee_arabica_parchment";  // Noor grows Arabica on the upper slope
        if ("maize".equals(crop)) return "maize_grain";
        if ("bean".equals(crop)) return "beans_dry";
        return null;
    }

    private static String sourceShort(Knowledge.PriceRow row) {
        if (row.sourceId.startsWith("ucda")) return "MAAIF/UCDA";
        if (row.sourceId.startsWith("wfp")) return "WFP";
        return row.sourceId;
    }
}
