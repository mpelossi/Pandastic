package org.pandastic.relay;

import android.content.res.AssetManager;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tiny's weights and all inference stay on the phone. JNI frees the model after each request. */
final class OfflineSpeech {
    static final String MODEL = "speech/ggml-tiny-q5_1.bin";
    static final long MODEL_BYTES = 32152673;
    static final boolean AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("pandastic_speech"); loaded = true; }
        catch (UnsatisfiedLinkError error) { loaded = false; }
        AVAILABLE = loaded;
    }
    static native String transcribe(AssetManager assets, short[] pcm, int length, String lang, AtomicBoolean cancelled);
    private OfflineSpeech() { }
}
