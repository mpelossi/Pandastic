package org.pandastic.relay.hub;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Hub settings chosen by the phone's owner: on/off, which numbers may ask, reply language.
 * The allowlist is a cost and spam control, not authentication: caller ID can be spoofed. A spoofer
 * still never sees an answer (replies go to the allowlisted number) and replies are rate-limited.
 */
public final class HubPrefs {
    private static final String FILE = "hub";
    private static final String ENABLED = "enabled";
    private static final String CONTACTS = "contacts";
    private static final String LANG = "lang";
    /** Uganda/Kenya/Tanzania mobile numbers are 9 digits after the country code or leading 0. */
    private static final int MATCH_DIGITS = 9;
    private static final int MIN_DIGITS = 7;

    private final SharedPreferences prefs;

    public HubPrefs(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String phoneMode() { return prefs.getString("phone_mode", ""); }

    public boolean capable() { return "capable".equals(phoneMode()); }

    public void setPhoneMode(String mode) {
        if (!"lite".equals(mode) && !"capable".equals(mode)) return;
        SharedPreferences.Editor editor = prefs.edit().putString("phone_mode", mode);
        if ("lite".equals(mode) || !mode.equals(phoneMode())) editor.putBoolean(ENABLED, false);
        editor.apply();
    }

    public String smsPeer() { return prefs.getString("sms_peer", ""); }

    public void setSmsPeer(String number) { prefs.edit().putString("sms_peer", number.trim()).apply(); }

    public boolean enabled() { return capable() && prefs.getBoolean(ENABLED, false); }

    public void setEnabled(boolean enabled) { prefs.edit().putBoolean(ENABLED, enabled).apply(); }

    public String lang() { return prefs.getString(LANG, "sw"); }

    public void setLang(String lang) { prefs.edit().putString(LANG, "en".equals(lang) ? "en" : "sw").apply(); }

    /** [{"name": "Mama", "number": "+256700000001"}]. Only these senders get answers. */
    public JSONArray contacts() {
        try { return new JSONArray(prefs.getString(CONTACTS, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    public void setContacts(JSONArray contacts) throws JSONException {
        JSONArray clean = new JSONArray();
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.getJSONObject(i);
            String number = contact.optString("number", "").trim();
            if (digits(number).length() < MIN_DIGITS) continue;
            clean.put(new JSONObject().put("name", contact.optString("name", "").trim()).put("number", number));
        }
        prefs.edit().putString(CONTACTS, clean.toString()).apply();
    }

    /** Contact name for an allowed sender, or null when the sender must be ignored. */
    public String contactName(String sender) {
        if (matchKey(sender) == null) return null;
        JSONArray contacts = contacts();
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.optJSONObject(i);
            if (contact != null && sameNumber(sender, contact.optString("number"))) {
                String name = contact.optString("name");
                return name.isEmpty() ? "" : name;
            }
        }
        return null;
    }

    /**
     * Two international numbers (+… or 00…) must match in full, so +254 7… never matches +256 7….
     * Otherwise the last 9 digits decide, so +256 700… and a locally written 0700… match.
     */
    public static boolean sameNumber(String a, String b) {
        String keyA = matchKey(a), keyB = matchKey(b);
        if (keyA == null || !keyA.equals(keyB)) return false;
        String fullA = international(a), fullB = international(b);
        return fullA == null || fullB == null || fullA.equals(fullB);
    }

    /** Digits with the country code, or null if the number is written in local form. */
    private static String international(String number) {
        String trimmed = number.trim();
        if (trimmed.startsWith("+")) return digits(trimmed);
        if (trimmed.startsWith("00")) return digits(trimmed).substring(2);
        return null;
    }

    /** Last 9 digits, used for local-form matching. Null for short codes and alphanumeric senders. */
    static String matchKey(String number) {
        if (number == null) return null;
        if (!number.trim().matches("\\+?[0-9 ()-]+")) return null;
        String digits = digits(number);
        if (digits.length() < MIN_DIGITS) return null;
        return digits.length() > MATCH_DIGITS ? digits.substring(digits.length() - MATCH_DIGITS) : digits;
    }

    private static String digits(String number) { return number == null ? "" : number.replaceAll("[^0-9]", ""); }
}
