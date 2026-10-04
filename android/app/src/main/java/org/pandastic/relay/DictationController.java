package org.pandastic.relay;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Bounded microphone capture followed by local Tiny inference. No speech-provider/network calls. */
final class DictationController {
    interface Events { void send(JSONObject event); }
    private final FrontendActivity activity;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Session active;
    private boolean closed;

    private static final class Session {
        final String id;
        final String lang;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile boolean stopRequested;
        volatile boolean processing;
        volatile AudioRecord recorder;
        Session(String id, String lang) { this.id = id; this.lang = "sw".equals(lang) ? "sw" : "en"; }
        void stopRecording() {
            stopRequested = true;
            AudioRecord current = recorder;
            if (current != null) try { current.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    DictationController(FrontendActivity activity, Events events) { this.activity = activity; this.events = events; }

    JSONObject status() {
        JSONObject value = new JSONObject();
        try {
            value.put("available", OfflineSpeech.AVAILABLE);
            value.put("offline", true);
            value.put("model", "Whisper Tiny multilingual Q5_1");
            value.put("bytes", OfflineSpeech.MODEL_BYTES);
            value.put("permission", activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            Session session = active;
            value.put("listening", session != null);
            value.put("processing", session != null && session.processing);
        } catch (Exception ignored) { }
        return value;
    }

    void start(String id, String lang) {
        activity.runOnUiThread(() -> {
            if (closed || id == null || id.isEmpty()) return;
            cancel();
            Session session = new Session(id, lang);
            active = session;
            emit(session, "requesting", null, null);
            activity.requestMicrophonePermission(() -> {
                if (active != session || closed) return;
                if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    finish(session, null, "permission"); return;
                }
                if (!OfflineSpeech.AVAILABLE) { finish(session, null, "unsupported"); return; }
                worker.execute(() -> capture(session));
            });
        });
    }

    private void capture(Session session) {
        if (session.cancelled.get()) return;
        short[] samples = new short[SpeechCapture.MAX_SAMPLES];
        SpeechCapture detector = new SpeechCapture();
        int count = 0;
        AudioRecord recorder = null;
        Runnable timeLimit = session::stopRecording;
        try {
            int minimum = AudioRecord.getMinBufferSize(SpeechCapture.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Microphone format unavailable");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SpeechCapture.RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 6400));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone unavailable");
            session.recorder = recorder;
            if (session.cancelled.get() || session.stopRequested) { finish(session, null, "no-speech"); return; }
            recorder.startRecording();
            // Bound wall time too if a microphone stalls instead of returning samples.
            main.postDelayed(timeLimit, 30_000);
            main.post(() -> { if (active == session) emit(session, "start", null, null); });
            while (!session.cancelled.get() && !session.stopRequested && count < samples.length) {
                int read = recorder.read(samples, count, Math.min(1600, samples.length - count));
                if (read < 0) {
                    if (session.stopRequested || session.cancelled.get()) break;
                    throw new IllegalStateException("Microphone read failed");
                }
                if (read == 0) continue;
                detector.add(samples, count, read);
                count += read;
                if (detector.finished()) break;
            }
        } catch (Exception error) {
            if (!session.cancelled.get()) finish(session, null, "failed");
            return;
        } finally {
            main.removeCallbacks(timeLimit);
            session.recorder = null;
            if (recorder != null) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { }
                recorder.release();
            }
        }
        if (session.cancelled.get()) return;
        if (!detector.hasSpeech()) { finish(session, null, "no-speech"); return; }
        session.processing = true;
        main.post(() -> { if (active == session) emit(session, "processing", null, null); });
        try {
            String text = OfflineSpeech.transcribe(activity.getAssets(), samples, count, session.lang, session.cancelled);
            if (!session.cancelled.get()) {
                text = text == null ? "" : text.trim();
                finish(session, text.isEmpty() ? null : text, text.isEmpty() ? "no-speech" : null);
            }
        } catch (Exception | LinkageError error) {
            android.util.Log.e("PandasticSpeech", "Offline dictation failed", error);
            if (!session.cancelled.get()) finish(session, null, "failed");
        }
    }

    void stop() {
        activity.runOnUiThread(() -> {
            Session session = active;
            if (session == null || session.processing) return;
            if (session.recorder == null) { cancel(); return; }
            session.stopRecording();
        });
    }

    void cancel() {
        Session session = active;
        active = null;
        if (session != null) {
            session.cancelled.set(true);
            session.stopRecording();
            emit(session, "end", null, null);
        }
    }

    void close() { cancel(); closed = true; worker.shutdown(); main.removeCallbacksAndMessages(null); }

    private void finish(Session session, String text, String error) {
        main.post(() -> {
            if (closed || active != session || session.cancelled.get()) return;
            emit(session, error == null ? "result" : "error", text, error);
            emit(session, "end", null, null);
            active = null;
        });
    }

    private void emit(Session session, String state, String text, String error) {
        if (closed) return;
        try {
            JSONObject event = new JSONObject().put("id", session.id).put("state", state);
            if (text != null) event.put("text", text);
            if (error != null) event.put("error", error);
            events.send(event);
        } catch (Exception ignored) { }
    }
}
