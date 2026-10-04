package org.pandastic.relay.brain;

import android.content.Context;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.sqlite.SQLiteConfig;

/**
 * Desktop replacement for the phone's SqliteKnowledge (android.database): the same queries over JDBC, so
 * Knowledge.open() and everything above it compile unchanged. Keep the SQL in step with the phone file.
 */
final class SqliteKnowledge implements Knowledge {
    private final Connection db;
    private final List<LexiconEntry> lexicon;

    static Knowledge open(Context context) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        String path = context.getAssets().file("models/knowledge.sqlite").getPath();
        return new SqliteKnowledge(DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties()));
    }

    private SqliteKnowledge(Connection db) throws SQLException {
        this.db = db;
        List<LexiconEntry> rows = new ArrayList<>();
        try (PreparedStatement q = db.prepareStatement("SELECT lang, term, slot, value, match FROM lexicon"); ResultSet c = q.executeQuery()) {
            while (c.next()) rows.add(new LexiconEntry(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5)));
        }
        lexicon = Collections.unmodifiableList(rows);
    }

    @Override public void close() {
        try { db.close(); } catch (SQLException ignored) { }
    }

    @Override public List<LexiconEntry> lexicon() { return lexicon; }

    @Override public Advice advice(String label, String lang) {
        String sql = "SELECT label, lang, sms, long, source_id, translation FROM advice WHERE label = ? AND lang IN (?, 'en') "
            + "ORDER BY lang = 'en' LIMIT 1";
        try (PreparedStatement q = query(sql, label, lang == null ? "en" : lang); ResultSet c = q.executeQuery()) {
            return c.next() ? new Advice(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6)) : null;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public Source source(String id) {
        try (PreparedStatement q = query("SELECT id, title, publisher, url, licence FROM sources WHERE id = ?", id); ResultSet c = q.executeQuery()) {
            return c.next() ? new Source(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5)) : null;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public PriceRow latestPrice(String commodity, String market) {
        String sql = "SELECT commodity, admin1, market, pricetype, unit, currency, price_low, price_high, date, source_id, derived "
            + "FROM latest_prices WHERE commodity = ? AND IFNULL(market, '') = ? ORDER BY derived LIMIT 1";
        try (PreparedStatement q = query(sql, commodity, market == null ? "" : market); ResultSet c = q.executeQuery()) {
            if (!c.next()) return null;
            return new PriceRow(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6),
                c.getDouble(7), c.getDouble(8), c.getString(9), c.getString(10), c.getInt(11) == 1);
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public String meta(String key) {
        try (PreparedStatement q = query("SELECT value FROM meta WHERE key = ?", key); ResultSet c = q.executeQuery()) {
            return c.next() ? c.getString(1) : null;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private PreparedStatement query(String sql, String... args) throws SQLException {
        PreparedStatement q = db.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) q.setString(i + 1, args[i]);
        return q;
    }
}
