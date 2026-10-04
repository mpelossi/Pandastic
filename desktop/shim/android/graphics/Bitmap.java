package android.graphics;

/**
 * Desktop stand-in for the two Bitmap calls the brain makes. createScaledBitmap(..., true) samples bilinearly at
 * pixel centres without antialiasing, like Android's (the app path is not PIL's antialiased resize).
 * Close to the phone, not bit-identical: judge models with scripts/photo-eval.mjs on an emulator.
 */
public final class Bitmap {
    private final int width, height;
    private final int[] argb;

    private Bitmap(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    public static Bitmap of(int width, int height, int[] argb) { return new Bitmap(width, height, argb.clone()); }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public void recycle() {}

    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int w, int h) {
        for (int row = 0; row < h; row++) System.arraycopy(argb, (y + row) * width + x, pixels, offset + row * stride, w);
    }

    public static Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
        if (src.width == dstWidth && src.height == dstHeight) return src;
        int[] out = new int[dstWidth * dstHeight];
        float sx = (float) src.width / dstWidth, sy = (float) src.height / dstHeight;
        for (int y = 0; y < dstHeight; y++) {
            float fy = Math.max(0, (y + 0.5f) * sy - 0.5f);
            int y0 = Math.min((int) fy, src.height - 1), y1 = Math.min(y0 + 1, src.height - 1);
            float wy = filter ? fy - y0 : 0;
            for (int x = 0; x < dstWidth; x++) {
                float fx = Math.max(0, (x + 0.5f) * sx - 0.5f);
                int x0 = Math.min((int) fx, src.width - 1), x1 = Math.min(x0 + 1, src.width - 1);
                float wx = filter ? fx - x0 : 0;
                int a = src.argb[y0 * src.width + x0], b = src.argb[y0 * src.width + x1];
                int c = src.argb[y1 * src.width + x0], d = src.argb[y1 * src.width + x1];
                int pixel = 0xFF000000;
                for (int shift = 0; shift <= 16; shift += 8) {
                    float top = ((a >> shift) & 0xFF) * (1 - wx) + ((b >> shift) & 0xFF) * wx;
                    float bottom = ((c >> shift) & 0xFF) * (1 - wx) + ((d >> shift) & 0xFF) * wx;
                    pixel |= Math.round(top * (1 - wy) + bottom * wy) << shift;
                }
                out[y * dstWidth + x] = pixel;
            }
        }
        return new Bitmap(dstWidth, dstHeight, out);
    }
}
