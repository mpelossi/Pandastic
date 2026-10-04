package org.pandastic.relay.brain;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** knowledge.sqlite on the phone: copied out of the APK (SQLite can't open an asset in place), then read-only. */
final class SqliteKnowledge implements Knowledge {
    @Override public void close() { db.close(); }
    private static final String ASSET = "models/knowledge.sqlite";
    private static final String FILE = "knowledge.sqlite";
    private static final String PREFS = "pandastic_knowledge";

    private final SQLiteDatabase db;
    private final List<LexiconEntry> lexicon;

    static Knowledge open(Context context) throws Exception {
        File target = context.getDatabasePath(FILE);
        long appVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Re-copy whenever the APK changes, so a rebuilt knowledge base always replaces the old copy.
        if (!target.exists() || prefs.getLong("copied_for", -1) != appVersion) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create " + parent);
            File tmp = new File(target.getPath() + ".tmp");
            try (InputStream in = context.getAssets().open(ASSET); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            }
            if (!tmp.renameTo(target)) {
                if (!target.delete() || !tmp.renameTo(target)) throw new IllegalStateException("Cannot replace " + target);
            }
            prefs.edit().putLong("copied_for", appVersion).apply();
        }
        return new SqliteKnowledge(SQLiteDatabase.openDatabase(target.getPath(), null, SQLiteDatabase.OPEN_READONLY));
    }

    private SqliteKnowledge(SQLiteDatabase db) {
        this.db = db;
        List<LexiconEntry> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT lang, term, slot, value, match FROM lexicon", null)) {
            while (c.moveToNext()) rows.add(new LexiconEntry(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)));
        }
        this.lexicon = Collections.unmodifiableList(rows);
    }

    @Override public List<LexiconEntry> lexicon() { return lexicon; }

    @Override public Advice advice(String label, String lang) {
        String sql = "SELECT label, lang, sms, long, source_id, translation FROM advice WHERE label = ? AND lang IN (?, 'en') "
            + "ORDER BY lang = 'en' LIMIT 1";
        try (Cursor c = db.rawQuery(sql, new String[]{label, lang == null ? "en" : lang})) {
            return c.moveToFirst() ? new Advice(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5)) : null;
        }
    }

    @Override public Source source(String id) {
        try (Cursor c = db.rawQuery("SELECT id, title, publisher, url, licence FROM sources WHERE id = ?", new String[]{id})) {
            return c.moveToFirst() ? new Source(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) : null;
        }
    }

    @Override public PriceRow latestPrice(String commodity, String market) {
        String sql = "SELECT commodity, admin1, market, pricetype, unit, currency, price_low, price_high, date, source_id, derived "
            + "FROM latest_prices WHERE commodity = ? AND IFNULL(market, '') = ? ORDER BY derived LIMIT 1";
        try (Cursor c = db.rawQuery(sql, new String[]{commodity, market == null ? "" : market})) {
            if (!c.moveToFirst()) return null;
            return new PriceRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                c.getDouble(6), c.getDouble(7), c.getString(8), c.getString(9), c.getInt(10) == 1);
        }
    }

    @Override public String meta(String key) {
        try (Cursor c = db.rawQuery("SELECT value FROM meta WHERE key = ?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }
}
