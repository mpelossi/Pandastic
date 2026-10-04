package org.pandastic.relay;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;
import org.pandastic.relay.brain.LlmNlu;

/**
 * The one thing the app uses the internet for: an opt-in download of the language model (about 540 MB) on a
 * capable phone, over mobile data (Noor's house has no Wi-Fi; brief: 3G bundles). Android's DownloadManager
 * does the transfer, so it resumes after a lost connection or a reboot and shows its own notification. The
 * file lands in the app's external files folder (where LlmNlu looks), and is only kept if its size and
 * SHA-256 match assets/llm/model.json (from ml/llm/model.json, the GitHub release).
 */
public final class ModelDownloader {
    private static final String TAG = "PandasticDownload";
    private static final String PREFS = "pandastic.download", ID = "id", ERROR = "error";
    private static ModelDownloader instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final JSONObject manifest;
    private volatile boolean verifying;

    public static synchronized ModelDownloader get(Context context) {
        if (instance == null) instance = new ModelDownloader(context.getApplicationContext());
        return instance;
    }

    private ModelDownloader(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.manifest = readManifest(context);
    }

    /** Starts (or resumes) the download. Returns null, or an error code: storage, unavailable, installed. */
    public synchronized String start() {
        if (manifest == null) return "unavailable";
        if (installed()) return "installed";
        if (activeId() >= 0) return null;  // already queued or running
        File folder = folder();
        if (folder == null) return "storage";
        long needed = manifest.optLong("bytes") + 50L * 1024 * 1024;
        if (folder.getUsableSpace() < needed) return "storage";
        File partial = partialFile();
        if (partial.exists()) partial.delete();  // DownloadManager refuses to overwrite
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(manifest.optString("url")))
            .setTitle("Pandastic language model")
            .setDescription(String.format(Locale.ROOT, "%d MB, once", manifest.optLong("bytes") / 1_000_000))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)    // mobile data is the only connection Noor's household has
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, null, "models/" + partial.getName());
        long id = manager().enqueue(request);
        prefs.edit().putLong(ID, id).remove(ERROR).apply();
        return null;
    }

    public synchronized void cancel() {
        long id = prefs.getLong(ID, -1);
        if (id >= 0) manager().remove(id);
        partialFile().delete();
        prefs.edit().remove(ID).remove(ERROR).apply();
    }

    /**
     * {state: none|queued|running|paused|verifying|done|failed, bytes, total, name, error?, reason?}.
     * When the transfer has finished, this also checks the file and moves it into place (once).
     */
    public synchronized JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            out.put("name", manifest == null ? JSONObject.NULL : manifest.optString("name"))
                .put("total", manifest == null ? 0 : manifest.optLong("bytes"));
            if (manifest == null) return out.put("state", "none");
            if (installed()) return out.put("state", "done").put("bytes", manifest.optLong("bytes"));
            if (verifying) return out.put("state", "verifying");
            String error = prefs.getString(ERROR, null);
            long id = prefs.getLong(ID, -1);
            if (id < 0) return out.put("state", error == null ? "none" : "failed").put("error", error == null ? JSONObject.NULL : error);
            try (Cursor c = manager().query(new DownloadManager.Query().setFilterById(id))) {
                if (c == null || !c.moveToFirst()) {
                    prefs.edit().remove(ID).apply();
                    return out.put("state", "none");
                }
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                out.put("bytes", bytes);
                switch (status) {
                    case DownloadManager.STATUS_PENDING: return out.put("state", "queued");
                    case DownloadManager.STATUS_RUNNING: return out.put("state", "running");
                    case DownloadManager.STATUS_PAUSED: return out.put("state", "paused").put("reason", pauseReason(reason));
                    case DownloadManager.STATUS_FAILED:
                        prefs.edit().remove(ID).putString(ERROR, "network").apply();
                        partialFile().delete();
                        return out.put("state", "failed").put("error", "network");
                    case DownloadManager.STATUS_SUCCESSFUL:
                        verifyInBackground(id);
                        return out.put("state", "verifying");
                    default: return out.put("state", "queued");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Download status failed: " + e.getClass().getSimpleName());
            try { return out.put("state", "failed").put("error", "status"); } catch (Exception ignored) { return out; }
        }
    }

    /** The model file is present (downloaded, imported or side-loaded): nothing to download. */
    public boolean installed() {
        return LlmNlu.find(context) != null;
    }

    private void verifyInBackground(long id) {
        if (verifying) return;
        verifying = true;
        new Thread(() -> {
            String error = null;
            try {
                File partial = partialFile();
                if (partial.length() != manifest.optLong("bytes")) error = "size";
                else if (!manifest.optString("sha256").equalsIgnoreCase(sha256(partial))) error = "checksum";
                else if (!partial.renameTo(new File(folder(), manifest.optString("name")))) error = "storage";
                if (error != null) partial.delete();
            } catch (Exception e) {
                error = "storage";
            }
            synchronized (this) {
                manager().remove(id);  // forget the DownloadManager entry (our file is already moved)
                SharedPreferences.Editor edit = prefs.edit().remove(ID);
                if (error != null) edit.putString(ERROR, error);
                edit.apply();
                verifying = false;
            }
            Log.i(TAG, error == null ? "Language model downloaded and verified" : "Download rejected: " + error);
            if (error == null) BrainHost.get(context).loadLanguageModel();  // capable phone: use it right away
        }, "model-verify").start();
    }

    private long activeId() {
        long id = prefs.getLong(ID, -1);
        if (id < 0) return -1;
        try (Cursor c = manager().query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status != DownloadManager.STATUS_FAILED) return id;
            }
        }
        prefs.edit().remove(ID).apply();
        return -1;
    }

    private static String pauseReason(int reason) {
        switch (reason) {
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK: return "no_network";
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI: return "wifi_only";
            case DownloadManager.PAUSED_WAITING_TO_RETRY: return "retrying";
            default: return "other";
        }
    }

    private File folder() {
        File external = context.getExternalFilesDir(null);
        if (external == null) return null;
        File models = new File(external, "models");
        return models.isDirectory() || models.mkdirs() ? models : null;
    }

    private File partialFile() {
        File folder = folder();
        return new File(folder == null ? context.getFilesDir() : folder,
            (manifest == null ? "model" : manifest.optString("name")) + ".download");
    }

    private DownloadManager manager() { return context.getSystemService(DownloadManager.class); }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    private static JSONObject readManifest(Context context) {
        try (InputStream in = context.getAssets().open("llm/model.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            JSONObject manifest = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            return LlmNlu.isModelName(manifest.optString("name")) && manifest.optString("url").startsWith("https://")
                ? manifest : null;
        } catch (Exception e) {
            return null;
        }
    }
}
