package org.pandastic.relay.hub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Queues SMS questions from allowed numbers; HubService answers them. Never runs a model here. */
public final class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        HubPrefs prefs = new HubPrefs(context);
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;

        // One broadcast can carry a multipart message; join the parts per sender.
        Map<String, StringBuilder> bodies = new LinkedHashMap<>();
        for (SmsMessage part : parts) {
            String sender = part.getDisplayOriginatingAddress();
            if (sender == null) continue;
            bodies.computeIfAbsent(sender, key -> new StringBuilder()).append(part.getDisplayMessageBody());
        }

        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                boolean queued = false;
                for (Map.Entry<String, StringBuilder> entry : bodies.entrySet()) {
                    String contact = prefs.contactName(entry.getKey());
                    String body = entry.getValue().toString().trim();
                    ChatStore.get(context).receive(entry.getKey(), body);
                    // Unknown numbers, short codes and our own echoed replies are ignored and not stored.
                    if (!prefs.enabled() || contact == null || body.isEmpty() || body.startsWith("Pandastic:")) continue;
                    HubLog.get(context).addPending(entry.getKey(), contact, body, System.currentTimeMillis());
                    queued = true;
                }
                if (queued) HubService.wake(context);
            } finally {
                result.finish();
            }
        }, "sms-queue").start();
    }
}
