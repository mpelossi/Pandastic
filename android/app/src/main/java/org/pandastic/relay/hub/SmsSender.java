package org.pandastic.relay.hub;

import android.content.Context;
import android.os.Build;
import android.telephony.SmsManager;
import java.util.ArrayList;

/** Sends a reply over the carrier SMS channel (no mobile data). */
final class SmsSender {
    /** A longer reply costs Noor's household more; the formatter puts the safety line first. */
    static final int MAX_PARTS = 2;

    private SmsSender() {}

    static int send(Context context, String to, String text) {
        SmsManager manager = Build.VERSION.SDK_INT >= 31
            ? context.getSystemService(SmsManager.class)
            : SmsManager.getDefault();
        ArrayList<String> parts = manager.divideMessage(text);
        if (parts.size() > MAX_PARTS) parts = new ArrayList<>(parts.subList(0, MAX_PARTS));
        if (parts.size() == 1) manager.sendTextMessage(to, null, parts.get(0), null, null);
        else manager.sendMultipartTextMessage(to, null, parts, null, null);
        return parts.size();
    }
}
