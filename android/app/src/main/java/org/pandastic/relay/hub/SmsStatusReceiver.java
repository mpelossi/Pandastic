package org.pandastic.relay.hub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, non-exported carrier callback; "sent" means all parts left this phone, not delivered. */
public final class SmsStatusReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra("message_id", -1);
        if (id >= 0) ChatStore.get(context).sentPart(id, getResultCode());
    }
}
