package org.pandastic.relay.hub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the SMS helper after the phone reboots, if the owner had switched it on. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (new HubPrefs(context).enabled()) HubService.wake(context);
    }
}
