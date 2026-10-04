package org.pandastic.relay;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class SpeechCaptureTest {
    private static void feed(SpeechCapture capture, int amplitude, int frames) {
        short[] frame = new short[1600];
        Arrays.fill(frame, (short) amplitude);
        for (int i = 0; i < frames; i++) capture.add(frame, 0, frame.length);
    }
    @Test public void silenceEndsWithoutSendingAudioToTheModel() {
        SpeechCapture c = new SpeechCapture();
        feed(c, 0, 49); assertFalse(c.finished());
        feed(c, 0, 1); assertTrue(c.finished()); assertFalse(c.hasSpeech());
    }
    @Test public void aSingleClickIsNotSpeech() {
        SpeechCapture c = new SpeechCapture();
        feed(c, 5000, 1); feed(c, 0, 49);
        assertTrue(c.finished()); assertFalse(c.hasSpeech());
    }
    @Test public void pausesKeepRecordingUntilOneAndAHalfSeconds() {
        SpeechCapture c = new SpeechCapture();
        feed(c, -500, 3); assertTrue(c.hasSpeech());
        feed(c, 0, 14); assertFalse(c.finished());
        feed(c, 0, 1); assertTrue(c.finished());
    }
    @Test public void ongoingSpeechCannotExceedThirtySeconds() {
        SpeechCapture c = new SpeechCapture();
        feed(c, Short.MIN_VALUE, 299); assertFalse(c.finished());
        feed(c, Short.MIN_VALUE, 1); assertTrue(c.finished());
    }
}
