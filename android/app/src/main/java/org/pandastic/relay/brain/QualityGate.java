package org.pandastic.relay.brain;

import android.graphics.Bitmap;

/**
 * Rejects photos the classifier should not judge (contracts §2 step 1). Deliberately lenient:
 * a false RETAKE only costs a second photo, but the thresholds still need checking on the phone.
 */
public final class QualityGate {
    private static final int SIDE = 256;
    /** Variance of the Laplacian below this means the photo is too blurry. */
    static final double MIN_SHARPNESS = 40;
    /** Mean luminance (0–255) below this means too dark. */
    static final double MIN_LUMINANCE = 35;
    /** Share of blown-out pixels above this means too bright. A white background alone is fine. */
    static final double MAX_SATURATED = 0.6;
    /**
     * Below this share of green-to-yellow plant pixels the photo is treated as "not a crop leaf" (UNSUPPORTED).
     * A second net behind the classifier's `other` class: random non-plant photos that a plant-only `other`
     * called CONFIDENT had share 0.00. Chosen on data (B, ml/leaf/photo_stats.py, calib+test through the app
     * path): at 0.05 no RoCoLe/PlantDoc photo and 6 of 684 CCMT maize photos are rejected (0.10: 9).
     */
    public static final double MIN_PLANT_SHARE = 0.05;

    private QualityGate() {}

    /** Returns null when the photo is usable, otherwise "blur", "dark" or "bright". */
    public static String check(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true);
        int[] argb = new int[SIDE * SIDE];
        small.getPixels(argb, 0, SIDE, 0, 0, SIDE, SIDE);
        if (small != bitmap) small.recycle();
        return check(argb, SIDE);
    }

    /** Share of pixels with a leaf colour (hue ~47-162 degrees, not grey, not black), on a 64 px copy. */
    public static double plantShare(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 64, 64, true);
        int[] argb = new int[64 * 64];
        small.getPixels(argb, 0, 64, 0, 0, 64, 64);
        if (small != bitmap) small.recycle();
        return plantShare(argb);
    }

    static double plantShare(int[] argb) {
        int plant = 0;
        for (int c : argb) {
            float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
            float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), chroma = max - min;
            if (max < 0.12f || chroma / max < 0.18f) continue;  // too dark or too grey
            float hue = max == r ? ((g - b) / chroma) / 6f : max == g ? (2 + (b - r) / chroma) / 6f : (4 + (r - g) / chroma) / 6f;
            if (hue < 0) hue += 1;
            if (hue >= 0.13f && hue <= 0.45f) plant++;
        }
        return argb.length == 0 ? 0 : (double) plant / argb.length;
    }

    /** Pure-Java core, so it can be unit-tested on the JVM. */
    static String check(int[] argb, int side) {
        double[] gray = new double[argb.length];
        double sum = 0;
        int saturated = 0;
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            gray[i] = 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF);
            sum += gray[i];
            if (gray[i] > 250) saturated++;
        }
        if (sum / gray.length < MIN_LUMINANCE) return "dark";
        if ((double) saturated / gray.length > MAX_SATURATED) return "bright";

        double lapSum = 0, lapSquares = 0;
        int count = 0;
        for (int y = 1; y < side - 1; y++) {
            for (int x = 1; x < side - 1; x++) {
                int i = y * side + x;
                double lap = gray[i - 1] + gray[i + 1] + gray[i - side] + gray[i + side] - 4 * gray[i];
                lapSum += lap;
                lapSquares += lap * lap;
                count++;
            }
        }
        double lapMean = lapSum / count;
        double variance = lapSquares / count - lapMean * lapMean;
        return variance < MIN_SHARPNESS ? "blur" : null;
    }
}
