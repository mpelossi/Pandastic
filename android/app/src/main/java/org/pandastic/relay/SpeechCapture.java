package org.pandastic.relay;

/** Energy gate and time bounds keep silence out of Whisper and cap audio allocation at 30 seconds. */
final class SpeechCapture {
    static final int RATE = 16000;
    static final int MAX_SAMPLES = RATE * 30;
    private int samples;
    private int voiced;
    private int lastVoice;

    void add(short[] pcm, int offset, int count) {
        long squares = 0;
        for (int i = offset; i < offset + count; i++) squares += (long) pcm[i] * pcm[i];
        samples += count;
        if (count > 0 && squares / count >= 220 * 220) {
            voiced += count;
            lastVoice = samples;
        }
    }
    boolean hasSpeech() { return voiced >= RATE / 5; }
    boolean finished() {
        return samples >= MAX_SAMPLES || (hasSpeech() ? samples - lastVoice >= RATE * 3 / 2 : samples >= RATE * 5);
    }
}
