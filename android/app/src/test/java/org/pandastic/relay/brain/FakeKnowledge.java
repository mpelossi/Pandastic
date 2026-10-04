package org.pandastic.relay.brain;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory Knowledge for JVM tests: the real data/lexicon.csv plus a few advice and price rows. */
final class FakeKnowledge implements Knowledge {
    final List<LexiconEntry> lexicon = new ArrayList<>();
    final Map<String, Advice> advice = new HashMap<>();
    final Map<String, PriceRow> prices = new HashMap<>();
    final Map<String, String> meta = new HashMap<>();

    FakeKnowledge() throws Exception {
        List<String> lines = Files.readAllLines(findRepoFile("data/lexicon.csv").toPath(), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (line.trim().isEmpty()) continue;
            String[] f = line.split(",", -1);
            lexicon.add(new LexiconEntry(f[0], f[1], f[2], f[3], f[4]));
        }
        addAdvice("coffee_rust", "sw", "Kutu ya majani. Pogoa kufungua mti.", "Kutu ya majani ya kahawa: madoa ya unga wa njano.");
        addAdvice("coffee_rust", "en", "Leaf rust. Prune to open the bush.", "Coffee leaf rust: yellow powdery spots under the leaves.");
        addAdvice("coffee_healthy", "en", "No disease seen on this leaf.", "No disease was found on this leaf.");
        prices.put("coffee_arabica_parchment|", new PriceRow("coffee_arabica_parchment", null, null, "Farm-gate", "KG", "UGX",
            15500, 15500, "2026-08", "ucda-2026-08", false));
        prices.put("maize_grain|", new PriceRow("maize_grain", null, null, "Retail", "KG", "UGX", 1273, 1888, "2026-08", "wfp-hdx-uga-derived", true));
        prices.put("maize_grain|Kapchorwa", new PriceRow("maize_grain", "Kween", "Kapchorwa", "Retail", "KG", "UGX", 1000, 1000, "2025-12", "wfp-hdx-uga", false));
        prices.put("beans_dry|Kapchorwa", new PriceRow("beans_dry", "Kween", "Kapchorwa", "Retail", "KG", "UGX", 2500, 2500, "2025-12", "wfp-hdx-uga", false));
        meta.put("home_market", "Kapchorwa");
        meta.put("price_stale_days", "120");
    }

    private void addAdvice(String label, String lang, String sms, String longText) {
        advice.put(label + "|" + lang, new Advice(label, lang, sms, longText, "plantwise-rw014-coffee-rust", "en".equals(lang) ? "original" : "machine"));
    }

    static File findRepoFile(String relative) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.exists()) return candidate;
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("Cannot find " + relative + " above " + System.getProperty("user.dir"));
    }

    @Override public List<LexiconEntry> lexicon() { return lexicon; }

    @Override public Advice advice(String label, String lang) {
        Advice a = advice.get(label + "|" + lang);
        return a != null ? a : advice.get(label + "|en");
    }

    @Override public Source source(String id) { return new Source(id, "Title of " + id, "Publisher", "https://example.org/" + id, "CC BY 4.0"); }

    @Override public PriceRow latestPrice(String commodity, String market) { return prices.get(commodity + "|" + (market == null ? "" : market)); }

    @Override public String meta(String key) { return meta.get(key); }
}
