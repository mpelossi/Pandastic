package org.pandastic.relay.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.pandastic.relay.BrainHost;
import org.pandastic.relay.FrontendActivity;

/**
 * Foreground service that answers SMS questions while the hub is switched on. The owner starts it
 * from the app (a user action, so Android allows the foreground start); it then stays up with a
 * persistent notification. Questions are answered one at a time from the HubLog queue.
 */
public final class HubService extends Service {
    private static final String TAG = "PandasticHub";
    private static final String CHANNEL = "hub";
    private static final int NOTIFICATION_ID = 7;
    private static final long HOUR = 60 * 60 * 1000L;
    /** Rate limits keep a stuck sender or a reply loop from draining the household's airtime. */
    private static final int MAX_PER_SENDER_PER_HOUR = 10, MAX_TOTAL_PER_HOUR = 30;

    private static volatile HubService running;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Responder responder;

    /** Starts the hub from a user action in the app. */
    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, HubService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, HubService.class));
    }

    public static boolean isRunning() { return running != null; }

    /**
     * Asks the hub to answer queued questions. If it is not running and Android refuses a
     * background start, the questions stay queued until the owner opens the app.
     */
    static void wake(Context context) {
        HubService service = running;
        if (service != null) { service.drain(); return; }
        try { start(context); }
        catch (IllegalStateException e) { Log.w(TAG, "Hub not running; question queued until the app is opened."); }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "SMS helper", NotificationManager.IMPORTANCE_LOW));
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
            Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
        if (new HubPrefs(this).enabled()) {
            responder = Responder.create(this);
            BrainHost.get(this).warmUp();
        }
        running = this;
        ChatStore.get(this).announce();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!new HubPrefs(this).enabled()) { stopSelf(); return START_NOT_STICKY; }
        drain();
        return START_STICKY;
    }

    private void drain() { worker.execute(this::answerPending); }

    private void answerPending() {
        HubLog log = HubLog.get(this);
        HubPrefs prefs = new HubPrefs(this);
        for (HubLog.Entry entry : log.pending()) {
            if (!prefs.enabled()) break;
            long hourAgo = System.currentTimeMillis() - HOUR;
            if (log.answeredSince(entry.sender, hourAgo) >= MAX_PER_SENDER_PER_HOUR
                || log.answeredSince(null, hourAgo) >= MAX_TOTAL_PER_HOUR) {
                log.finish(entry.id, HubLog.RATE_LIMITED, null, null);
                continue;
            }
            PowerManager.WakeLock wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pandastic:answer");
            wakeLock.acquire(90_000);
            try {
                Responder.Reply reply;
                // The reply follows the language of the SMS (keywords detect it; Swahili when unclear, D3).
                // The owner's language is used for the safe fallback only.
                try { reply = responder.answer(entry.body, null); }
                catch (Exception e) {
                    // Noor always gets an answer: if the models fail, the safe "ask a person" reply goes out.
                    Log.e(TAG, "Answering failed, sending the safe reply: " + e.getClass().getSimpleName());
                    reply = new Responder.Fallback().answer(entry.body, prefs.lang());
                }
                if (!prefs.enabled()) break;
                if (!reply.farming) {
                    // A personal message: no reply, and no copy kept here (it is in the normal SMS app).
                    log.finishPersonal(entry.id);
                    continue;
                }
                SmsSender.send(this, entry.sender, reply.sms);
                ChatStore.get(this).recordReply(entry.sender, reply.sms);
                log.finish(entry.id, HubLog.ANSWERED, reply.sms, reply.decisionJson);
            } catch (Exception e) {
                // No message content or number in the system log: it is personal data.
                Log.e(TAG, "Could not answer queued question " + entry.id + ": " + e.getClass().getSimpleName());
                log.finish(entry.id, HubLog.FAILED, null, null);
            } finally {
                if (wakeLock.isHeld()) wakeLock.release();
            }
        }
        if (prefs.enabled() && running == this)
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        ChatStore.get(this).announce();
    }

    private Notification notification() {
        int today = HubLog.get(this).answeredSince(null, startOfToday());
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, FrontendActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("SMS helper is on")
            .setContentText(today == 1 ? "1 question answered today" : today + " questions answered today")
            .setContentIntent(open)
            .setOngoing(true)
            .build();
    }

    /** Local midnight, for the "answered today" count. */
    public static long startOfToday() {
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    @Override public void onDestroy() {
        running = null;
        worker.shutdown();
        ChatStore.get(this).announce();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
