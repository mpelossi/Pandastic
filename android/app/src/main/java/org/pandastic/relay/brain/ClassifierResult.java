package org.pandastic.relay.brain;

import java.util.Collections;
import java.util.Map;

/** Calibrated output of the leaf classifier (contracts §1, §6). Pure Java so Resolver tests run on the JVM. */
public final class ClassifierResult {
    public final String modelVersion;
    /** Label order from leaf_classifier.json. */
    public final String[] labels;
    /** softmax(logits / temperature), same order as labels. */
    public final float[] probs;
    public final float minProb, minMargin;
    public final Map<String, Float> perClassMinProb;

    public ClassifierResult(String modelVersion, String[] labels, float[] probs,
                            float minProb, float minMargin, Map<String, Float> perClassMinProb) {
        if (labels.length != probs.length) throw new IllegalArgumentException("labels/probs length mismatch");
        this.modelVersion = modelVersion;
        this.labels = labels;
        this.probs = probs;
        this.minProb = minProb;
        this.minMargin = minMargin;
        this.perClassMinProb = perClassMinProb == null ? Collections.emptyMap() : perClassMinProb;
    }

    /** Index of the highest probability. */
    public int top1() {
        int best = 0;
        for (int i = 1; i < probs.length; i++) if (probs[i] > probs[best]) best = i;
        return best;
    }

    /** Index of the second-highest probability, or -1 with a single label. */
    public int top2() {
        int first = top1(), second = -1;
        for (int i = 0; i < probs.length; i++)
            if (i != first && (second < 0 || probs[i] > probs[second])) second = i;
        return second;
    }

    /**
     * The same result with all probability on `other`, for a photo the plant-colour check rejected
     * (QualityGate.MIN_PLANT_SHARE): the resolver then answers UNSUPPORTED. Unchanged if there is no `other` label.
     */
    public ClassifierResult asOther() {
        int other = java.util.Arrays.asList(labels).indexOf("other");
        if (other < 0) return this;
        float[] onlyOther = new float[probs.length];
        onlyOther[other] = 1f;
        return new ClassifierResult(modelVersion, labels, onlyOther, minProb, minMargin, perClassMinProb);
    }

    /** Minimum probability required for this label to count as confident. */
    public float minProbFor(String label) {
        Float value = perClassMinProb.get(label);
        return value != null ? value : minProb;
    }
}
