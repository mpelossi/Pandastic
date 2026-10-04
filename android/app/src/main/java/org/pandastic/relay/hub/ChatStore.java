package org.pandastic.relay.hub;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** App-owned SMS threads, separate from the AI queue. No model or network connection is used. */
public final class ChatStore extends SQLiteOpenHelper {
    public static final String CHANGED = "org.pandastic.relay.CHAT_CHANGED";
    private static ChatStore instance;
    private final Context context;

    public static synchronized ChatStore get(Context context) {
        if (instance == null) instance = new ChatStore(context.getApplicationContext());
        return instance;
    }

    private ChatStore(Context context) {
        super(context, "sms-chat.db", null, 1);
        this.context = context;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chat (id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL, "
            + "body TEXT NOT NULL, direction TEXT NOT NULL, time INTEGER NOT NULL, status TEXT NOT NULL, "
            + "parts INTEGER NOT NULL DEFAULT 1, confirmed INTEGER NOT NULL DEFAULT 0)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public static boolean validNumber(String number) {
        return number != null && number.trim().matches("\\+?[0-9 ()-]+")
            && number.replaceAll("[^0-9]", "").length() >= 7
            && number.replaceAll("[^0-9]", "").length() <= 15;
    }

    public long send(String number, String body) {
        if (!validNumber(number) || body == null || body.trim().isEmpty() || body.length() > 480)
            throw new IllegalArgumentException("invalid_message");
        SmsManager manager = Build.VERSION.SDK_INT >= 31
            ? context.getSystemService(SmsManager.class) : SmsManager.getDefault();
        if (manager == null) throw new IllegalStateException("sms_unavailable");
        ArrayList<String> parts = manager.divideMessage(body);
        long id = add(number, body, "out", "sending", parts.size());
        ArrayList<PendingIntent> sent = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Intent callback = new Intent(context, SmsStatusReceiver.class)
                .setData(Uri.parse("pandastic:sms/" + id + "/" + i)).putExtra("message_id", id);
            sent.add(PendingIntent.getBroadcast(context, 0, callback,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        try {
            String destination = number.replaceAll("[ ()-]", "");
            if (parts.size() == 1) manager.sendTextMessage(destination, null, body, sent.get(0), null);
            else manager.sendMultipartTextMessage(destination, null, parts, sent, null);
        } catch (Exception e) {
            mark(id, "failed");
            throw e;
        }
        return id;
    }

    /** Only messages from a configured peer, an existing thread, or an allowed hub contact are kept. */
    public void receive(String number, String body) {
        HubPrefs prefs = new HubPrefs(context);
        boolean known = HubPrefs.sameNumber(number, prefs.smsPeer()) || prefs.contactName(number) != null;
        if (!known) {
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT DISTINCT number FROM chat", null)) {
                while (cursor.moveToNext()) {
                    if (HubPrefs.sameNumber(number, cursor.getString(0))) { known = true; break; }
                }
            }
        }
        if (known && !body.isEmpty()) add(number, body, "in", "received", 1);
    }

    public void recordReply(String number, String body) { add(number, body, "out", "submitted", 1); }

    private long add(String number, String body, String direction, String status, int parts) {
        ContentValues values = new ContentValues();
        values.put("number", number.trim()); values.put("body", body); values.put("direction", direction);
        values.put("time", System.currentTimeMillis()); values.put("status", status); values.put("parts", parts);
        long id = getWritableDatabase().insertOrThrow("chat", null, values);
        // Bound storage on the smaller phone; newest 300 app messages are kept.
        getWritableDatabase().execSQL("DELETE FROM chat WHERE id NOT IN (SELECT id FROM chat ORDER BY id DESC LIMIT 300)");
        announce();
        return id;
    }

    public synchronized void sentPart(long id, int result) {
        SQLiteDatabase db = getWritableDatabase();
        if (result != Activity.RESULT_OK) { mark(id, "failed"); return; }
        db.execSQL("UPDATE chat SET confirmed = confirmed + 1 WHERE id = ? AND status = 'sending'", new Object[]{id});
        db.execSQL("UPDATE chat SET status = 'sent' WHERE id = ? AND status = 'sending' AND confirmed >= parts", new Object[]{id});
        announce();
    }

    private void mark(long id, String status) {
        ContentValues values = new ContentValues(); values.put("status", status);
        getWritableDatabase().update("chat", values, "id = ?", new String[]{String.valueOf(id)});
        announce();
    }

    public JSONArray recent() throws JSONException {
        JSONArray messages = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM (SELECT * FROM chat ORDER BY id DESC LIMIT 150) ORDER BY id ASC", null)) {
            while (c.moveToNext()) messages.put(new JSONObject()
                .put("id", c.getLong(c.getColumnIndexOrThrow("id")))
                .put("number", c.getString(c.getColumnIndexOrThrow("number")))
                .put("body", c.getString(c.getColumnIndexOrThrow("body")))
                .put("direction", c.getString(c.getColumnIndexOrThrow("direction")))
                .put("time", c.getLong(c.getColumnIndexOrThrow("time")))
                .put("status", c.getString(c.getColumnIndexOrThrow("status"))));
        }
        return messages;
    }

    public void clear() { getWritableDatabase().delete("chat", null, null); announce(); }

    public void announce() { context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName())); }
}
