package org.pandastic.relay.brain;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs the leaf classifier (contracts §1) with ONNX Runtime. Labels, normalisation, temperature
 * and thresholds all come from leaf_classifier.json; nothing about the model is hard-coded here.
 */
public final class LeafClassifier implements AutoCloseable {
    private static final String MODEL = "models/leaf_classifier.onnx", META = "models/leaf_classifier.json";

    public final String version;
    /** True for the colour-heuristic stub: the UI must not present its output as AI. */
    public final boolean stub;
    private final int size;
    private final float[] mean, std;
    private final String[] labels;
    private final float temperature, minProb, minMargin;
    private final Map<String, Float> perClassMinProb;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;

    public LeafClassifier(Context context) throws Exception {
        JSONObject meta = new JSONObject(new String(readAsset(context, META), StandardCharsets.UTF_8));
        version = meta.getString("version");
        stub = meta.optBoolean("stub", false);
        size = meta.getInt("input_size");
        mean = floats(meta.getJSONArray("mean"));
        std = floats(meta.getJSONArray("std"));
        JSONArray labelArray = meta.getJSONArray("labels");
        labels = new String[labelArray.length()];
        for (int i = 0; i < labels.length; i++) labels[i] = labelArray.getString(i);
        temperature = (float) meta.optDouble("temperature", 1.0);
        JSONObject thresholds = meta.getJSONObject("thresholds");
        minProb = (float) thresholds.getDouble("min_prob");
        minMargin = (float) thresholds.getDouble("min_margin");
        Map<String, Float> perClass = new HashMap<>();
        JSONObject perClassJson = meta.optJSONObject("per_class_min_prob");
        if (perClassJson != null) {
            for (Iterator<String> keys = perClassJson.keys(); keys.hasNext(); ) {
                String key = keys.next();
                perClass.put(key, (float) perClassJson.getDouble(key));
            }
        }
        perClassMinProb = Collections.unmodifiableMap(perClass);

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(2);
        // The ensemble stores int8 weights: dequantize them once at load, not on every photo (B: 15 ms -> 5 ms).
        options.addConfigEntry("session.disable_quant_qdq", "1");
        session = env.createSession(readAsset(context, MODEL), options);
        inputName = session.getInputNames().iterator().next();
    }

    public ClassifierResult classify(Bitmap bitmap) throws Exception {
        // Contract §1: direct bilinear resize to size×size, RGB, x/255, (x-mean)/std, NCHW.
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, size, size, true);
        int pixels = size * size;
        int[] argb = new int[pixels];
        scaled.getPixels(argb, 0, size, 0, 0, size, size);
        if (scaled != bitmap) scaled.recycle();
        float[] input = new float[3 * pixels];
        for (int i = 0; i < pixels; i++) {
            int color = argb[i];
            input[i] = (((color >> 16) & 0xFF) / 255f - mean[0]) / std[0];
            input[pixels + i] = (((color >> 8) & 0xFF) / 255f - mean[1]) / std[1];
            input[2 * pixels + i] = ((color & 0xFF) / 255f - mean[2]) / std[2];
        }
        float[] logits;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[]{1, 3, size, size});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            logits = ((float[][]) result.get(0).getValue())[0];
        }
        return new ClassifierResult(version, labels, softmax(logits, temperature), minProb, minMargin, perClassMinProb);
    }

    static float[] softmax(float[] logits, float temperature) {
        float max = Float.NEGATIVE_INFINITY;
        for (float value : logits) max = Math.max(max, value / temperature);
        float[] probs = new float[logits.length];
        float sum = 0;
        for (int i = 0; i < logits.length; i++) { probs[i] = (float) Math.exp(logits[i] / temperature - max); sum += probs[i]; }
        for (int i = 0; i < probs.length; i++) probs[i] /= sum;
        return probs;
    }

    private static float[] floats(JSONArray array) throws Exception {
        float[] values = new float[array.length()];
        for (int i = 0; i < values.length; i++) values[i] = (float) array.getDouble(i);
        return values;
    }

    private static byte[] readAsset(Context context, String path) throws IOException {
        try (InputStream in = context.getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    @Override public void close() throws Exception { session.close(); }
}
