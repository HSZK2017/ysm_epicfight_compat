package com.ysmef.compat.acceptance;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Lead diagnostic (temporary, T6 scope): does the delta composition really double per link,
 * and where does the extra composition come from?
 *
 * <p>This reproduces the composition the solver performs for a chain, using nothing but JOML,
 * so the answer cannot be blamed on the solver's own bookkeeping. A chain of N links, each with
 * its own swing theta about X, composed parent-then-child, must read N*theta for a small theta
 * (rotations about a common axis add). Any read of 2^N * theta means one link's delta was
 * composed with itself.
 */
public class LeadDeltaCompositionProbeTest {

    /** The same construction YsmMeshSecondaryMotion#buildSegmentDelta performs. */
    private static Matrix4f segmentDelta(Vector3f pivot, Quaternionf swing) {
        return new Matrix4f().identity()
                .translate(pivot.x, pivot.y, pivot.z)
                .rotate(swing)
                .translate(-pivot.x, -pivot.y, -pivot.z);
    }

    /** The angle of a rigid matrix, the way the solver's own rotationAngleOf reads it. */
    private static double angleDegrees(Matrix4f m) {
        double trace = m.m00() + m.m11() + m.m22();
        double cosine = Math.max(-1.0, Math.min(1.0, (trace - 1.0) * 0.5));
        return Math.toDegrees(Math.acos(cosine));
    }

    @Test
    void composingAChainOncePerLinkAddsTheAngles() {
        float theta = 8.643f;
        Vector3f[] pivots = {
                new Vector3f(0.0f, 0.5f, 0.0f),
                new Vector3f(0.0f, 0.4f, 0.0f),
                new Vector3f(0.0f, 0.3f, 0.0f),
                new Vector3f(0.0f, 0.2f, 0.0f),
        };
        Matrix4f[] composed = new Matrix4f[pivots.length];
        Matrix4f scratch = new Matrix4f();
        for (int i = 0; i < pivots.length; i++) {
            composed[i] = segmentDelta(pivots[i], new Quaternionf().rotateX((float) Math.toRadians(theta)));
            if (i > 0) {
                // parent x child, with the child's own copy taken first (no aliasing)
                composed[i] = new Matrix4f(composed[i - 1]).mul(new Matrix4f(composed[i]));
            }
        }
        StringBuilder line = new StringBuilder("composed chain angles: ");
        for (Matrix4f m : composed) {
            line.append(String.format("%.3f ", angleDegrees(m)));
        }
        System.out.println("[LeadProbe] " + line);
        // the composed angle must grow linearly: theta, 2theta, 3theta, 4theta
        for (int i = 0; i < composed.length; i++) {
            double expected = theta * (i + 1);
            double actual = angleDegrees(composed[i]);
            org.junit.jupiter.api.Assertions.assertEquals(expected, actual, 0.05,
                    "link " + i + " composed angle (linear growth expected)");
        }
    }

    @Test
    void theAliasedFormDoublesInstead() {
        float theta = 8.643f;
        Vector3f pivot = new Vector3f(0.0f, 0.4f, 0.0f);
        // the broken form: this and the argument are the same object
        Matrix4f delta = segmentDelta(pivot, new Quaternionf().rotateX((float) Math.toRadians(theta)));
        Matrix4f parent = segmentDelta(pivot, new Quaternionf().rotateX((float) Math.toRadians(theta)));
        delta.set(parent).mul(delta);
        System.out.println("[LeadProbe] aliased form reads " + String.format("%.3f", angleDegrees(delta))
                + " deg for two equal links of " + theta + " deg");
    }
}
