package com.ysmef.compat.model.runtime;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fabric-continuity pull - the mechanism that makes twenty-two panels of one skirt move
 * as one skirt instead of as twenty-two independent strips.
 *
 * <p>What it is guarding is the difference between a pull and a constraint, which is the same
 * difference as between cloth and a rigid body:
 *
 * <ul>
 *   <li>A panel that is genuinely pushed has to be able to <i>lead</i>, with the panels sewn to it
 *       following. Snapping every panel to the average would forbid that, and the skirt would stop
 *       responding to anything but the whole body moving at once.</li>
 *   <li>But the disagreement has to close, or the gap it opens stays open - and on a garment made
 *       of separate mesh parts a disagreement is not a curve, it is a hole. The shipped run had a
 *       20.0 degree front panel beside a 5.1 degree one.</li>
 * </ul>
 *
 * <p>So: one application closes a fixed fraction and never overshoots, and repeated application
 * converges.
 */
class FabricCoherenceTest {

    private static final Vector3f DOWN = new Vector3f(0.0F, -1.0F, 0.0F);

    /** One application closes exactly the configured fraction of the disagreement. */
    @Test
    void onePullClosesAFixedFractionOfTheDisagreement() {
        Vector3f direction = tilted(0.30F);
        Vector3f target = tilted(0.05F);
        float before = YsmDynamicBoneSolver.angleBetween(direction, target);

        YsmMeshSecondaryMotion.relaxDirection(direction, target, 0.25F);

        float after = YsmDynamicBoneSolver.angleBetween(direction, target);
        assertEquals(before * 0.75F, after, 1.0E-3F,
                "a pull of a quarter must leave three quarters of the disagreement");
    }

    /**
     * And it never overshoots: after a pull the piece is closer to its neighbours than it was,
     * never further. Stated as distance rather than as a signed angle because at {@code factor} 1
     * the piece lands exactly on the target, where a signed test measures nothing but rounding.
     */
    @Test
    void aPullNeverOvershoots() {
        Vector3f target = tilted(0.05F);
        for (float factor : new float[]{0.1F, 0.25F, 0.5F, 1.0F}) {
            Vector3f direction = tilted(0.30F);
            float before = direction.distance(target);

            YsmMeshSecondaryMotion.relaxDirection(direction, target, factor);

            float after = direction.distance(target);
            assertTrue(after <= before + 1.0E-5F,
                    "factor " + factor + " left the piece further from its neighbours ("
                            + before + " -> " + after + ")");
        }
    }

    /** A full-strength pull does land on the target: that is the constraint end of the range. */
    @Test
    void aFullPullLandsOnTheTarget() {
        Vector3f direction = tilted(0.30F);
        Vector3f target = tilted(0.05F);

        YsmMeshSecondaryMotion.relaxDirection(direction, target, 1.0F);

        assertTrue(YsmDynamicBoneSolver.angleBetween(direction, target) < 1.0E-4F,
                "factor 1 must reach the target exactly, for callers that want no give at all");
    }

    /** Repeated pulls converge, so a disagreement closes instead of being exchanged forever. */
    @Test
    void repeatedPullsConverge() {
        Vector3f lead = tilted(0.35F);
        Vector3f follower = tilted(0.09F);

        for (int frame = 0; frame < 12; frame++) {
            Vector3f mean = new Vector3f(lead).add(follower).normalize();
            YsmMeshSecondaryMotion.relaxDirection(lead, mean, 0.25F);
            YsmMeshSecondaryMotion.relaxDirection(follower, mean, 0.25F);
        }

        float disagreement = YsmDynamicBoneSolver.angleBetween(lead, follower);
        assertTrue(disagreement < 0.05F,
                "two coupled panels must agree within a fifth of a second; left " + disagreement + " rad");
    }

    /** A neutral direction with no neighbours to pull toward must come back unchanged. */
    @Test
    void aMissingTargetOrAZeroFactorChangesNothing() {
        Vector3f direction = tilted(0.30F);
        Vector3f copy = new Vector3f(direction);

        YsmMeshSecondaryMotion.relaxDirection(direction, null, 0.25F);
        YsmMeshSecondaryMotion.relaxDirection(null, copy, 0.25F);
        YsmMeshSecondaryMotion.relaxDirection(direction, copy, 0.0F);

        assertEquals(copy.x, direction.x, 1.0E-6F);
        assertEquals(copy.y, direction.y, 1.0E-6F);
        assertEquals(copy.z, direction.z, 1.0E-6F);
    }

    /** A unit direction tilted by {@code angle} from straight down, in the x/y plane. */
    private static Vector3f tilted(float angle) {
        return new Vector3f((float) Math.sin(angle), -(float) Math.cos(angle), 0.0F).normalize();
    }
}
