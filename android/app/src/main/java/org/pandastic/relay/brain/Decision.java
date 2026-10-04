package org.pandastic.relay.brain;

import java.util.Locale;

/**
 * The answer for one question (contracts §2). Built only by the Resolver; read by the UI (as JSON) and the
 * SMS formatter. Pure Java with its own JSON writer, so it behaves the same in JVM tests and on the phone.
 */
public final class Decision {
    public String status;              // CONFIDENT | UNCERTAIN | UNSUPPORTED | RETAKE | ASK_CROP | TEXT_ONLY | PRICE | PRICE_STALE | NO_DATA | HELP
    public String intent;              // what the question asked for: photo | diagnose | price | planting | help | other
    public String crop = "unknown";
    public String label;
    public Float prob;
    public String runnerUp;
    public Float runnerUpProb;
    public String[] candidates = new String[0];
    public String lang = "sw";
    public String title;               // short headline for the UI card
    public String message;             // full user-facing text (safety sentence first where relevant)
    public String adviceSms;           // knowledge.sqlite advice.sms (CONFIDENT only)
    public String adviceLong;          // knowledge.sqlite advice.long (CONFIDENT only)
    public String translation;         // advice row: original | machine | machine+reviewed
    public Knowledge.Source source;
    public Price price;
    public boolean escalate = true;
    public String quality;             // RETAKE reason: blur | dark | bright
    public String modelVersion;

    public static final class Price {
        public String commodity, name, market, pricetype, currency, unit, date, sourceId;
        public double low, high;
        public boolean derived, stale;
        public Double offer, gapPct;
    }

    public String toJson() {
        Json j = new Json();
        j.put("status", status).put("intent", intent).put("crop", crop).put("label", label).put("prob", prob)
            .put("runner_up", runnerUp).put("runner_up_prob", runnerUpProb);
        j.key("candidates").raw("[");
        for (int i = 0; i < candidates.length; i++) { if (i > 0) j.raw(","); j.string(candidates[i]); }
        j.raw("]");
        j.put("lang", lang).put("title", title).put("message", message).put("advice_sms", adviceSms)
            .put("advice_long", adviceLong).put("translation", translation);
        j.key("source");
        if (source == null) j.raw("null");
        else j.raw("{").putFirst("id", source.id).put("title", source.title).put("publisher", source.publisher)
            .put("url", source.url).put("licence", source.licence).raw("}");
        j.key("price");
        if (price == null) j.raw("null");
        else j.raw("{").putFirst("commodity", price.commodity).put("name", price.name).put("market", price.market)
            .put("pricetype", price.pricetype).put("low", price.low).put("high", price.high).put("currency", price.currency)
            .put("unit", price.unit).put("date", price.date).put("source_id", price.sourceId).put("derived", price.derived)
            .put("offer", price.offer).put("gap_pct", price.gapPct).put("stale", price.stale).raw("}");
        j.put("escalate", escalate).put("quality", quality).put("model_version", modelVersion);
        return j.finish();
    }

    @Override public String toString() { return toJson(); }

    /** Minimal JSON object writer: strings, numbers, booleans and null. */
    private static final class Json {
        private final StringBuilder out = new StringBuilder("{");
        private boolean first = true;

        Json key(String name) {
            if (!first) out.append(',');
            first = false;
            string(name);
            out.append(':');
            return this;
        }

        Json putFirst(String name, Object value) { first = true; return put(name, value); }

        Json put(String name, Object value) {
            key(name);
            if (value == null) out.append("null");
            else if (value instanceof String) string((String) value);
            else if (value instanceof Boolean) out.append(value);
            else if (value instanceof Float || value instanceof Double) {
                double d = ((Number) value).doubleValue();
                out.append(Double.isFinite(d) ? String.format(Locale.US, "%.4f", d).replaceAll("0+$", "").replaceAll("\\.$", ".0") : "null");
            } else out.append(value);
            return this;
        }

        /** Appends literal JSON; a nested object starts its first field with putFirst. */
        Json raw(String text) { out.append(text); return this; }

        Json string(String s) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) out.append(String.format(Locale.US, "\\u%04x", (int) c));
                        else out.append(c);
                }
            }
            out.append('"');
            return this;
        }

        String finish() { return out.append('}').toString(); }
    }
}
