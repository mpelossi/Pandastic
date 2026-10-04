package org.pandastic.relay.hub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * Store-and-forward queue and local history of SMS questions. Messages are written as
 * "pending" first, so nothing is lost if the service is not running when one arrives.
 * Stays on this phone; the owner can clear it.
 */
public final class HubLog extends SQLiteOpenHelper {
    public static final String PENDING = "pending", ANSWERED = "answered", FAILED = "failed", RATE_LIMITED = "rate_limited";
    /** Not a farming question (e.g. family news): no automatic reply; the owner reads it in the SMS app. */
    public static final String PERSONAL = "personal";
    private static HubLog instance;

    public static synchronized HubLog get(Context context) {
        if (instance == null) instance = new HubLog(context.getApplicationContext());
        return instance;
    }

    private HubLog(Context context) { super(context, "hub.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, "
            + "contact TEXT, body TEXT NOT NULL, received_at INTEGER NOT NULL, status TEXT NOT NULL, "
            + "reply TEXT, decision TEXT, handled_at INTEGER)");
        db.execSQL("CREATE INDEX messages_status ON messages(status)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public static final class Entry {
        public long id, receivedAt, handledAt;
        public String sender, contact, body, status, reply, decision;
    }

    public long addPending(String sender, String contact, String body, long receivedAt) {
        ContentValues values = new ContentValues();
        values.put("sender", sender);
        values.put("contact", contact);
        values.put("body", body);
        values.put("received_at", receivedAt);
        values.put("status", PENDING);
        return getWritableDatabase().insert("messages", null, values);
    }

    public void finish(long id, String status, String reply, String decision) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("reply", reply);
        values.put("decision", decision);
        values.put("handled_at", System.currentTimeMillis());
        getWritableDatabase().update("messages", values, "id = ?", new String[]{String.valueOf(id)});
    }

    /** A personal message the helper does not answer: its text is not kept (privacy), only that it came. */
    public void finishPersonal(long id) {
        ContentValues values = new ContentValues();
        values.put("status", PERSONAL);
        values.put("body", "");
        values.putNull("reply");
        values.putNull("decision");
        values.put("handled_at", System.currentTimeMillis());
        getWritableDatabase().update("messages", values, "id = ?", new String[]{String.valueOf(id)});
    }

    public List<Entry> pending() {
        return query("status = ?", new String[]{PENDING}, "id ASC", 50);
    }

    public List<Entry> recent(int limit) {
        return query(null, null, "id DESC", limit);
    }

    public int answeredSince(String sender, long since) {
        String where = "status = ? AND handled_at >= ?" + (sender == null ? "" : " AND sender = ?");
        String[] args = sender == null ? new String[]{ANSWERED, String.valueOf(since)}
            : new String[]{ANSWERED, String.valueOf(since), sender};
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE " + where, args)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public void clear() { getWritableDatabase().delete("messages", null, null); }

    private List<Entry> query(String where, String[] args, String order, int limit) {
        List<Entry> entries = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("messages", null, where, args, null, null, order, String.valueOf(limit))) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getLong(c.getColumnIndexOrThrow("id"));
                e.sender = c.getString(c.getColumnIndexOrThrow("sender"));
                e.contact = c.getString(c.getColumnIndexOrThrow("contact"));
                e.body = c.getString(c.getColumnIndexOrThrow("body"));
                e.receivedAt = c.getLong(c.getColumnIndexOrThrow("received_at"));
                e.status = c.getString(c.getColumnIndexOrThrow("status"));
                e.reply = c.getString(c.getColumnIndexOrThrow("reply"));
                e.decision = c.getString(c.getColumnIndexOrThrow("decision"));
                e.handledAt = c.getLong(c.getColumnIndexOrThrow("handled_at"));
                entries.add(e);
            }
        }
        return entries;
    }
}
