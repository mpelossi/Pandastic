package org.pandastic.relay.brain;

import android.content.Context;
import java.util.List;

/** Read-only access to knowledge.sqlite (contracts §3). SqliteKnowledge on the phone, fakes in JVM tests. */
public interface Knowledge {
    default void close() {}
    /** Every lexicon row, loaded once. */
    List<LexiconEntry> lexicon();

    /** Advice for a classifier label in this language, falling back to English; null if none. */
    Advice advice(String label, String lang);

    /** Null if the id is unknown. */
    Source source(String id);

    /** Newest price row for a commodity; market null = the national figure. Null if none. */
    PriceRow latestPrice(String commodity, String market);

    /** meta table value, or null. */
    String meta(String key);

    /** Copies assets/models/knowledge.sqlite to the database directory when the app changes, then opens it read-only. */
    static Knowledge open(Context context) throws Exception {
        return SqliteKnowledge.open(context);
    }

    final class LexiconEntry {
        public final String lang, term, slot, value, match;

        public LexiconEntry(String lang, String term, String slot, String value, String match) {
            this.lang = lang;
            this.term = term;
            this.slot = slot;
            this.value = value;
            this.match = match;
        }
    }

    final class Advice {
        public final String label, lang, sms, longText, sourceId, translation;

        public Advice(String label, String lang, String sms, String longText, String sourceId, String translation) {
            this.label = label;
            this.lang = lang;
            this.sms = sms;
            this.longText = longText;
            this.sourceId = sourceId;
            this.translation = translation;
        }
    }

    final class Source {
        public final String id, title, publisher, url, licence;

        public Source(String id, String title, String publisher, String url, String licence) {
            this.id = id;
            this.title = title;
            this.publisher = publisher;
            this.url = url;
            this.licence = licence;
        }
    }

    final class PriceRow {
        public final String commodity, admin1, market, pricetype, unit, currency, date, sourceId;
        public final double low, high;
        public final boolean derived;

        public PriceRow(String commodity, String admin1, String market, String pricetype, String unit, String currency,
                        double low, double high, String date, String sourceId, boolean derived) {
            this.commodity = commodity;
            this.admin1 = admin1;
            this.market = market;
            this.pricetype = pricetype;
            this.unit = unit;
            this.currency = currency;
            this.low = low;
            this.high = high;
            this.date = date;
            this.sourceId = sourceId;
            this.derived = derived;
        }
    }
}
