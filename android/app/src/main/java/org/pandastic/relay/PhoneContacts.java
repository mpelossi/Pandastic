package org.pandastic.relay;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.pandastic.relay.hub.ChatStore;

/** Contact suggestions and SIM identity stay on this phone. No contact becomes allowed automatically. */
final class PhoneContacts {
    static String numberPermission() {
        return Build.VERSION.SDK_INT >= 26 ? Manifest.permission.READ_PHONE_NUMBERS : Manifest.permission.READ_PHONE_STATE;
    }

    static JSONObject identity(Context context) {
        JSONObject result = new JSONObject();
        String number = "", source = "";
        try {
            if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))) {
                number = context.getSharedPreferences("pandastic_lab", Context.MODE_PRIVATE).getString("own_number", "");
                if (ChatStore.validNumber(number)) source = "lab";
                else number = "";
            }
            if (number.isEmpty() && context.checkSelfPermission(numberPermission()) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= 33) {
                    SubscriptionManager subscriptions = context.getSystemService(SubscriptionManager.class);
                    int id = SubscriptionManager.getDefaultSmsSubscriptionId();
                    if (!SubscriptionManager.isValidSubscriptionId(id)) id = SubscriptionManager.getDefaultSubscriptionId();
                    if (subscriptions != null && SubscriptionManager.isValidSubscriptionId(id)) number = subscriptions.getPhoneNumber(id);
                }
                if (number == null || number.isEmpty()) {
                    TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
                    if (telephony != null) number = telephony.getLine1Number();
                }
                if (ChatStore.validNumber(number)) source = "sim";
                else number = "";
            }
        } catch (Exception ignored) { number = ""; source = ""; }
        try { result.put("number", number == null ? "" : number).put("numberSource", source); }
        catch (Exception ignored) { }
        return result;
    }

    static JSONObject read(Context context) {
        JSONObject result = identity(context);
        JSONArray contacts = new JSONArray();
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return result.put("contacts", contacts).put("error", "permission");
            }
            Set<String> seen = new HashSet<>();
            try (Cursor cursor = context.getContentResolver().query(Phone.CONTENT_URI,
                new String[]{Phone.DISPLAY_NAME, Phone.NUMBER}, null, null, Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
                while (cursor != null && cursor.moveToNext()) {
                    String name = cursor.getString(0), number = cursor.getString(1);
                    if (!ChatStore.validNumber(number)) continue;
                    String key = number.replaceAll("[^0-9]", "");
                    if (!seen.add(key)) continue;
                    contacts.put(new JSONObject().put("name", name == null || name.isEmpty() ? number : name).put("number", number));
                }
            }
            result.put("contacts", contacts);
        } catch (Exception error) {
            try { result.put("contacts", contacts).put("error", "unavailable"); } catch (Exception ignored) { }
        }
        return result;
    }
}
