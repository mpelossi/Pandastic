package org.pandastic.relay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The system says a download finished: check the model file now, even if the app is closed. */
public final class DownloadDoneReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ModelDownloader.get(context).status();
    }
}
