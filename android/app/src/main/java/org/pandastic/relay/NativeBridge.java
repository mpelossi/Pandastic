package org.pandastic.relay;

import android.Manifest;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.database.Cursor;
import android.net.Uri;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.core.content.ContextCompat;
import java.util.Locale;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pandastic.relay.hub.HubLog;
import org.pandastic.relay.hub.HubPrefs;
import org.pandastic.relay.hub.HubService;
import org.pandastic.relay.hub.ChatStore;
import org.pandastic.relay.brain.LlmNlu;

/**
 * The React UI's only way into native code, exposed as window.PandasticNative. Slow work runs on
 * BrainHost's thread and answers through window.__pandasticReply(id, json); hub changes are
 * announced with a "pandastic:hub" event.
 */
final class NativeBridge {
    private static final String TAG = "PandasticBridge";
    private final FrontendActivity activity;
    private final WebView webView;
    private TextToSpeech tts;
    private final DictationController dictation;
    private volatile boolean ttsReady;
    private volatile String speechId = "";
    private static final AtomicBoolean modelBusy = new AtomicBoolean();
    private final BroadcastReceiver chatChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { announceChat(); announceHub(); }
    };

    NativeBridge(FrontendActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.dictation = new DictationController(activity, event -> webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('pandastic:dictation', {detail: " + event.toString() + "}))", null)));
        ContextCompat.registerReceiver(activity, chatChanges, new IntentFilter(ChatStore.CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    // ---- Photo and questions -------------------------------------------------------------

    /** base64Jpeg: a downscaled photo from the UI (no data: prefix). */
    @JavascriptInterface public void checkPhoto(String id, String base64Jpeg, String text, String lang) {
        if (!new HubPrefs(activity).capable()) { reply(id, error("phone_mode")); return; }
        BrainHost.get(activity).submit(() -> {
            if (!new HubPrefs(activity).capable()) { reply(id, error("phone_mode")); return null; }
            try {
                byte[] bytes = Base64.decode(base64Jpeg, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) { reply(id, error("unreadable_photo")); return null; }
                try { reply(id, BrainHost.get(activity).photo(bitmap, text, lang)); }
                finally { bitmap.recycle(); }
            } catch (Exception e) {
                Log.e(TAG, "Photo check failed", e);
                reply(id, error("photo_failed"));
            } finally { announceModels(); }
            return null;
        });
    }

    @JavascriptInterface public void ask(String id, String text, String lang) {
        if (!new HubPrefs(activity).capable()) { reply(id, error("phone_mode")); return; }
        BrainHost.get(activity).submit(() -> {
            if (!new HubPrefs(activity).capable()) { reply(id, error("phone_mode")); return null; }
            try { reply(id, BrainHost.get(activity).text(text, lang)); }
            catch (Exception e) { Log.e(TAG, "Question failed", e); reply(id, error("question_failed")); }
            finally { announceModels(); }
            return null;
        });
    }

    @JavascriptInterface public String info() {
        if (!new HubPrefs(activity).capable()) return "{}";
        try { return BrainHost.get(activity).info().toString(); }
        catch (JSONException e) { return "{}"; }
    }

    @JavascriptInterface public String modelStatus() {
        if (!new HubPrefs(activity).capable()) return "{}";
        try {
            JSONObject download = ModelDownloader.get(activity).status();
            String state = download.optString("state");
            if (state.equals("queued") || state.equals("running") || state.equals("paused") || state.equals("verifying"))
                tickDownload();
            return BrainHost.status(activity).put("busy", modelBusy.get()).put("download", download).toString();
        }
        catch (Exception e) { return "{\"error\":\"model_status\"}"; }
    }

    /**
     * Opt-in download of the language model over mobile data (capable phone only). action: "start" | "cancel".
     * Returns {"ok":bool,"error":code} at once; progress arrives as "pandastic:models" events (modelStatus().download).
     * Errors: phone_mode, storage (needs ~600 MB free), installed, unavailable, action.
     */
    @JavascriptInterface public String modelDownload(String action) {
        String error;
        if (!new HubPrefs(activity).capable()) error = "phone_mode";
        else if ("start".equals(action)) error = ModelDownloader.get(activity).start();
        else if ("cancel".equals(action)) { ModelDownloader.get(activity).cancel(); error = null; }
        else error = "action";
        announceModels();
        return "{\"ok\":" + (error == null) + ",\"error\":" + JSONObject.quote(error == null ? "" : error) + "}";
    }

    private boolean ticking;

    /** While a download is active, the Models page gets a progress event every 1.5 s. */
    private void tickDownload() {
        if (ticking) return;
        ticking = true;
        webView.postDelayed(() -> { ticking = false; announceModels(); }, 1500);
    }

    /** All model operations are explicit owner actions, serialized with inference. */
    @JavascriptInterface public void manageModels(String id, String action) {
        if (!new HubPrefs(activity).capable() || !modelBusy.compareAndSet(false, true)) {
            modelReply(id, false, "unavailable"); return;
        }
        if (!"load".equals(action) && !"unload".equals(action) && !"import".equals(action)) {
            modelBusy.set(false); modelReply(id, false, "action"); return;
        }
        activity.runOnUiThread(() -> {
            if (!new HubPrefs(activity).capable()) {
                modelBusy.set(false); modelReply(id, false, "phone_mode"); return;
            }
            if ("unload".equals(action)) {
                new HubPrefs(activity).setEnabled(false);
                HubService.stop(activity);
                announceHub();
            }
            announceModels();
            if ("import".equals(action)) { activity.pickModel(id); return; }
            BrainHost host = BrainHost.get(activity);
            host.submit(() -> {
                boolean ok = true;
                try {
                    if ("unload".equals(action)) host.unload();
                    else {
                        host.loadModels();
                        JSONObject status = BrainHost.status(activity);
                        ok = status.getJSONObject("classifier").optBoolean("loaded")
                            && status.getJSONObject("knowledge").optBoolean("loaded")
                            && (!status.getJSONObject("language").optBoolean("installed")
                                || status.getJSONObject("language").optBoolean("loaded"));
                    }
                } catch (Exception e) { ok = false; }
                modelBusy.set(false); announceModels(); modelReply(id, ok, ok ? "" : "load_failed");
                return null;
            });
        });
    }

    void finishModelImport(String id, Uri uri) {
        if (uri == null) {
            modelBusy.set(false); announceModels(); modelReply(id, false, "cancelled"); return;
        }
        BrainHost host = BrainHost.get(activity);
        host.submit(() -> {
            File temporary = new File(activity.getFilesDir(), "models/import.gguf.tmp");
            try {
                if (!new HubPrefs(activity).capable()) throw new IllegalStateException("phone_mode");
                String name = "";
                try (Cursor cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                if (!LlmNlu.isModelName(name)) throw new IllegalArgumentException("model_name");
                File directory = temporary.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("storage");
                long total = 0;
                try (InputStream input = activity.getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) throw new IllegalArgumentException("model_file");
                    byte[] header = new byte[4];
                    for (int i = 0; i < 4; i++) {
                        int value = input.read(); if (value < 0) throw new IllegalArgumentException("model_file");
                        header[i] = (byte) value;
                    }
                    if (header[0] != 'G' || header[1] != 'G' || header[2] != 'U' || header[3] != 'F')
                        throw new IllegalArgumentException("model_file");
                    new HubPrefs(activity).setEnabled(false);
                    HubService.stop(activity);
                    announceHub();
                    output.write(header); total = 4;
                    byte[] buffer = new byte[64 * 1024];
                    for (int n; (n = input.read(buffer)) != -1; ) {
                        if (!new HubPrefs(activity).capable()) throw new IllegalStateException("phone_mode");
                        total += n;
                        if (total > 1_073_741_824L) throw new IllegalArgumentException("model_size");
                        output.write(buffer, 0, n);
                    }
                    output.getFD().sync();
                }
                if (total < 100_000_000L) throw new IllegalArgumentException("model_size");
                if (!new HubPrefs(activity).capable()) throw new IllegalStateException("phone_mode");
                host.unload();
                // Atomic replacement on the same filesystem: a failed import preserves the old file.
                android.system.Os.rename(temporary.getAbsolutePath(), new File(directory, name).getAbsolutePath());
                // One language model at a time (533 MB each): the import replaces the other variant.
                String other = LlmNlu.FINE_TUNED.equals(name) ? LlmNlu.MODEL_NAME : LlmNlu.FINE_TUNED;
                new File(directory, other).delete();
                modelReply(id, true, "");
            } catch (Exception e) {
                Log.w(TAG, "Model import failed: " + e.getClass().getSimpleName());
                modelReply(id, false, "import_failed");
            } finally {
                if (temporary.exists()) temporary.delete();
                modelBusy.set(false); announceModels();
            }
            return null;
        });
    }

    private void modelReply(String id, boolean ok, String error) {
        String json = "{\"ok\":" + ok + ",\"error\":" + JSONObject.quote(error) + "}";
        webView.post(() -> webView.evaluateJavascript("window.__pandasticModelReply && window.__pandasticModelReply("
            + JSONObject.quote(id) + "," + json + ")", null));
    }

    void announceModels() {
        String status = modelStatus();
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('pandastic:models', {detail: " + status + "}))", null));
    }

    // ---- SMS helper ----------------------------------------------------------------------

    @JavascriptInterface public String phoneInfo() {
        try {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            activity.getSystemService(ActivityManager.class).getMemoryInfo(memory);
            return PhoneContacts.identity(activity).put("mode", new HubPrefs(activity).phoneMode())
                .put("totalRamMb", memory.totalMem / (1024 * 1024)).toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface public void phoneContacts(String id) {
        activity.runOnUiThread(() -> activity.requestContactPermissions(() -> {
            Thread reader = new Thread(() -> {
                JSONObject result = PhoneContacts.read(activity);
                webView.post(() -> {
                    if (!activity.isDestroyed()) webView.evaluateJavascript("window.__pandasticContactsReply && window.__pandasticContactsReply("
                        + JSONObject.quote(id) + "," + result.toString() + ")", null);
                });
            }, "pandastic-contacts");
            reader.start();
        }));
    }

    void announcePhone() {
        String info = phoneInfo();
        webView.post(() -> webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('pandastic:phone', {detail:" + info + "}))", null));
    }

    @JavascriptInterface public void setPhoneMode(String mode) {
        HubPrefs prefs = new HubPrefs(activity);
        prefs.setPhoneMode(mode);
        if (!prefs.enabled()) HubService.stop(activity);
        if (!prefs.capable()) {
            BrainHost.unloadIfLoaded();
        }
        announceHub();
    }

    @JavascriptInterface public String chatStatus() {
        try {
            return new JSONObject().put("peer", new HubPrefs(activity).smsPeer())
                .put("messages", ChatStore.get(activity).recent()).put("smsPermission", smsPermission()).toString();
        } catch (JSONException e) { return "{\"peer\":\"\",\"messages\":[],\"smsPermission\":false}"; }
    }

    @JavascriptInterface public void setSmsPeer(String number) {
        if (number == null || (!number.trim().isEmpty() && !ChatStore.validNumber(number))) return;
        new HubPrefs(activity).setSmsPeer(number);
        announceChat();
    }

    @JavascriptInterface public void enableSms() {
        activity.runOnUiThread(() -> activity.requestSmsPermissions(this::announceChat));
    }

    @JavascriptInterface public void sendSms(String id, String number, String body) {
        activity.runOnUiThread(() -> activity.requestSmsPermissions(() -> {
            if (!smsPermission()) { smsReply(id, false, "permission"); return; }
            try {
                ChatStore.get(activity).send(number, body);
                smsReply(id, true, "");
            } catch (Exception e) {
                Log.w(TAG, "SMS send failed: " + e.getClass().getSimpleName());
                smsReply(id, false, "send_failed");
            }
            announceChat();
        }));
    }

    @JavascriptInterface public void clearChatHistory() { ChatStore.get(activity).clear(); }

    private boolean smsPermission() {
        return activity.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
            && activity.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void smsReply(String id, boolean ok, String error) {
        String json = "{\"ok\":" + ok + ",\"error\":" + JSONObject.quote(error) + "}";
        webView.post(() -> webView.evaluateJavascript("window.__pandasticSmsReply && window.__pandasticSmsReply("
            + JSONObject.quote(id) + "," + json + ")", null));
    }

    @JavascriptInterface public String hubStatus() {
        try { return hubJson().toString(); }
        catch (JSONException e) { return "{}"; }
    }

    @JavascriptInterface public void setHubEnabled(boolean enabled) {
        activity.runOnUiThread(() -> {
            HubPrefs prefs = new HubPrefs(activity);
            if (enabled && !prefs.capable()) { announceHub(); return; }
            if (!enabled) {
                prefs.setEnabled(false);
                HubService.stop(activity);
                announceHub();
                return;
            }
            activity.requestHubPermissions(() -> {
                if (prefs.capable() && activity.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                    && activity.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    prefs.setEnabled(true);
                    HubService.start(activity);
                    askToIgnoreBatteryOptimisation();
                }
                announceHub();
            });
        });
    }

    /** contactsJson: [{"name": "Mama", "number": "+256…"}] */
    @JavascriptInterface public void setHubContacts(String contactsJson) {
        try { new HubPrefs(activity).setContacts(new JSONArray(contactsJson)); }
        catch (JSONException e) { Log.w(TAG, "Ignored malformed contact list"); }
        announceHub();
    }

    @JavascriptInterface public void setHubLang(String lang) {
        new HubPrefs(activity).setLang(lang);
        announceHub();
    }

    @JavascriptInterface public void clearHubHistory() {
        HubLog.get(activity).clear();
        announceHub();
    }

    // ---- Human in the loop and voice -----------------------------------------------------

    /** Opens the SMS app with a draft; the person decides whether to send it. */
    @JavascriptInterface public void draftSms(String number, String body) {
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (number == null ? "" : number)));
        intent.putExtra("sms_body", body);
        activity.runOnUiThread(() -> {
            try { activity.startActivity(intent); }
            catch (android.content.ActivityNotFoundException e) { Log.w(TAG, "No SMS app installed"); }
        });
    }

    /** Opens Android's share sheet; the person picks the app and the recipient. */
    @JavascriptInterface public void share(String text) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        activity.runOnUiThread(() -> activity.startActivity(Intent.createChooser(send, null)));
    }

    @JavascriptInterface public String dictationStatus() { return dictation.status().toString(); }
    @JavascriptInterface public void startDictation(String id, String lang) { stopSpeaking(); dictation.start(id, lang); }
    @JavascriptInterface public void stopDictation() { dictation.stop(); }
    @JavascriptInterface public void cancelDictation() { activity.runOnUiThread(dictation::cancel); }

    /**
     * Reads text aloud with an installed offline voice. Returns false if no voice fits the language (the UI
     * then hides its button) or the engine is still starting. Progress is announced with a
     * "pandastic:speech" event, detail {state: "start" | "done" | "stopped" | "error"}, so the UI can show a stop button.
     */
    @JavascriptInterface public boolean speak(String text, String lang) {
        if (!ttsReady) { initTts(); return false; }
        if (text == null || text.trim().isEmpty()) return false;
        Voice voice = offlineVoice(lang);
        if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) return false;
        tts.setSpeechRate(0.9f);  // a little slower: many listeners read little and hear the advice once
        String clipped = text.length() > TextToSpeech.getMaxSpeechInputLength()
            ? text.substring(0, TextToSpeech.getMaxSpeechInputLength()) : text;
        speechId = java.util.UUID.randomUUID().toString();
        return tts.speak(clipped, TextToSpeech.QUEUE_FLUSH, null, speechId) == TextToSpeech.SUCCESS;
    }

    /** {ready, sw, en, speaking}: which offline voices exist. Starts the engine if it is not running yet. */
    @JavascriptInterface public String voices() {
        try {
            if (!ttsReady) { initTts(); return new JSONObject().put("ready", false).toString(); }
            return new JSONObject().put("ready", true)
                .put("sw", offlineVoice("sw") != null)
                .put("en", offlineVoice("en") != null)
                .put("speaking", tts.isSpeaking()).toString();
        } catch (JSONException e) { return "{}"; }
    }

    @JavascriptInterface public void stopSpeaking() {
        if (ttsReady) { speechId = ""; tts.stop(); announceSpeech("stopped"); }
    }

    // ---- Internals -------------------------------------------------------------------------

    private static Locale locale(String lang) { return "en".equals(lang) ? Locale.ENGLISH : new Locale("sw"); }

    private Voice offlineVoice(String lang) {
        if (!ttsReady || tts.getVoices() == null) return null;
        String language = locale(lang).getLanguage();
        Voice chosen = null;
        for (Voice voice : tts.getVoices()) {
            if (!language.equals(voice.getLocale().getLanguage()) || voice.isNetworkConnectionRequired()
                || (voice.getFeatures() != null && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) continue;
            if (chosen == null || voice.getQuality() > chosen.getQuality()
                || (voice.getQuality() == chosen.getQuality() && voice.getName().compareTo(chosen.getName()) < 0)) chosen = voice;
        }
        return chosen;
    }

    /** Started when the page loads, so the first tap on "listen" already has a voice. */
    void initTts() {
        activity.runOnUiThread(() -> {
            if (tts != null) return;
            tts = new TextToSpeech(activity, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (!ttsReady) { Log.w(TAG, "No text-to-speech engine"); return; }
                tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                announceSpeech("ready");
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { if (id.equals(speechId)) announceSpeech("start"); }
                    @Override public void onDone(String id) { if (id.equals(speechId)) announceSpeech("done"); }
                    @Override public void onError(String id) { if (id.equals(speechId)) announceSpeech("error"); }
                    @Override public void onStop(String id, boolean interrupted) { if (id.equals(speechId)) announceSpeech("stopped"); }
                });
            });
        });
    }

    private void announceSpeech(String state) {
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('pandastic:speech', {detail: {state: '" + state + "'}}))", null));
    }

    void close() {
        dictation.close();
        activity.unregisterReceiver(chatChanges);
        if (tts != null) tts.shutdown();
    }

    void announceChat() {
        String status = chatStatus();
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('pandastic:chat', {detail: " + status + "}))", null));
    }

    void announceHub() {
        String status = hubStatus();
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('pandastic:hub', {detail: " + status + "}))", null));
    }

    private JSONObject hubJson() throws JSONException {
        HubPrefs prefs = new HubPrefs(activity);
        JSONArray recent = new JSONArray();
        for (HubLog.Entry entry : HubLog.get(activity).recent(20)) {
            recent.put(new JSONObject().put("id", entry.id).put("contact", entry.contact)
                .put("question", entry.body).put("reply", entry.reply == null ? JSONObject.NULL : entry.reply)
                .put("status", entry.status).put("receivedAt", entry.receivedAt));
        }
        boolean notifications = Build.VERSION.SDK_INT < 33
            || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        return new JSONObject()
            .put("enabled", prefs.enabled())
            .put("running", HubService.isRunning())
            .put("lang", prefs.lang())
            .put("contacts", prefs.contacts())
            .put("smsPermission", activity.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && activity.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
            .put("notificationPermission", notifications)
            .put("answeredToday", HubLog.get(activity).answeredSince(null, HubService.startOfToday()))
            .put("recent", recent);
    }

    private void askToIgnoreBatteryOptimisation() {
        PowerManager power = activity.getSystemService(PowerManager.class);
        if (power.isIgnoringBatteryOptimizations(activity.getPackageName())) return;
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.getPackageName())));
        } catch (android.content.ActivityNotFoundException e) { Log.w(TAG, "Battery settings unavailable"); }
    }

    private void reply(String id, String json) {
        String script = "window.__pandasticReply && window.__pandasticReply(" + JSONObject.quote(id) + ", " + json + ")";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private static String error(String code) { return "{\"status\":\"ERROR\",\"error\":\"" + code + "\",\"escalate\":true}"; }
}
