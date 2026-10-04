package org.pandastic.relay.brain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** Photo checks before the classifier: plant colour share, and how a rejected photo reaches the resolver. */
public class QualityGateTest {
    private static int[] fill(int rgb, int n) {
        int[] argb = new int[n];
        Arrays.fill(argb, 0xFF000000 | rgb);
        return argb;
    }

    @Test public void leafColoursCount() {
        assertEquals(1.0, QualityGate.plantShare(fill(0x3A7D32, 100)), 1e-9);  // leaf green
        assertEquals(1.0, QualityGate.plantShare(fill(0xB5C23A, 100)), 1e-9);  // yellow-green (streak, chlorosis)
    }

    @Test public void nonLeafColoursDoNot() {
        assertEquals(0.0, QualityGate.plantShare(fill(0x808080, 100)), 1e-9);  // grey street
        assertEquals(0.0, QualityGate.plantShare(fill(0x1E4E8C, 100)), 1e-9);  // sea blue
        assertEquals(0.0, QualityGate.plantShare(fill(0x8B5A2B, 100)), 1e-9);  // wood brown
        assertEquals(0.0, QualityGate.plantShare(fill(0x0A140A, 100)), 1e-9);  // almost black
    }

    @Test public void mixedPhotoGivesTheShare() {
        int[] argb = fill(0x808080, 100);
        Arrays.fill(argb, 0, 25, 0xFF3A7D32);
        assertEquals(0.25, QualityGate.plantShare(argb), 1e-9);
        assertTrue(0.25 >= QualityGate.MIN_PLANT_SHARE);
    }

    @Test public void notAPlantBecomesOtherSoTheResolverSaysUnsupported() {
        String[] labels = {"coffee_rust", "maize_leaf_blight", "other"};
        ClassifierResult confident = new ClassifierResult("v", labels, new float[]{0.05f, 0.9f, 0.05f}, 0.4f, 0.3f, null);
        ClassifierResult rejected = confident.asOther();
        assertEquals("other", labels[rejected.top1()]);
        assertEquals(1f, rejected.probs[2], 1e-6);
        // Without an `other` label nothing changes.
        ClassifierResult noOther = new ClassifierResult("v", new String[]{"a", "b"}, new float[]{0.7f, 0.3f}, 0.4f, 0.3f, null);
        assertEquals(noOther, noOther.asOther());
    }
}
