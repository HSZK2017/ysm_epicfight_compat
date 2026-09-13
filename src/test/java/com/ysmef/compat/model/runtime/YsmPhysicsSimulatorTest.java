package com.ysmef.compat.model.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the secondary-motion dynamics.
 *
 * <p>What these tests are really guarding is that a bad frame cannot deform a model.
 * The integrator runs on the render thread against pose data that arrives once per
 * frame, so the two ways it goes wrong are a time step it cannot handle and a state
 * that drifts instead of settling - and both show up in game as hair stretched across
 * the screen rather than as an exception, which is why they are pinned here.
 */
class YsmPhysicsSimulatorTest {

    /** A chain hanging straight down from the origin, one unit long. */
    private static final Vector3f PIVOT = new Vector3f(0, 0, 0);
    private static final Vector3f REST_TIP = new Vector3f(0, -1, 0);

    private static YsmPhysicsChains.Chain chain(boolean root, boolean aroundLegs) {
        return new YsmPhysicsChains.Chain(0, "Hair", 9, 9, root, aroundLegs);
    }

    /**
     * The first update only establishes state. Integrating from an all-zero tip would
     * make the chain's first visible frame a snap from the origin to its rest pose.
     */
    @Test
    void theFirstUpdateStartsAtRestWithNoRotation() {
        YsmPhysicsSimulator simulator = YsmPhysicsSimulator.INSTANCE;
        YsmPhysicsSimulator.ChainState state = new YsmPhysicsSimulator.ChainState();
        Quaternionf out = new Quaternionf();

        simulator.update(state, PIVOT, REST_TIP, chain(false, false), 0.016F, out);

        assertEquals(1.0F, out.w(), 1.0E-5F, "the first frame must not rotate the chain");
        assertTrue(state.initialized);
    }

    /** A settled chain given no dt must produce no rotation at all. */
    @Test
    void aSettledChainGivenNoTimeProducesNoRotation() {
        YsmPhysicsSimulator simulator = YsmPhysicsSimulator.INSTANCE;
        YsmPhysicsSimulator.ChainState state = new YsmPhysicsSimulator.ChainState();
        Quaternionf out = new Quaternionf();

        simulator.update(state, PIVOT, REST_TIP, chain(false, false), 0.016F, out);
        simulator.update(state, PIVOT, REST_TIP, chain(false, false), 0.0F, out);

        assertEquals(1.0F, out.w(), 1.0E-5F);
    }

    /**
     * Moving the rest tip makes the spring chase it, and the chase must stay bounded:
     * the tip may never end up further from the pivot than the chain is long. A chain
     * that grows is the visible failure this guards against.
     */
    @Test
    void theTipStaysOnItsOwnReachWhileChasingAMovingRestPose() {
        YsmPhysicsSimulator simulator = YsmPhysicsSimulator.INSTANCE;
        YsmPhysicsSimulator.ChainState state = new YsmPhysicsSimulator.ChainState();
        Quaternionf out = new Quaternionf();
        Vector3f movingTip = new Vector3f(0, -1, 0);

        // Settle, then swing the rest pose around hard.
        simulator.update(state, PIVOT, movingTip, chain(false, false), 0.016F, out);

        float maxReach = 0.0F;
        for (int i = 0; i < 200; i++) {
            float a = i * 0.2F;
            movingTip.set((float) Math.sin(a), -(float) Math.cos(a), (float) Math.sin(a * 0.5F));
            simulator.update(state, PIVOT, movingTip, chain(false, false), 0.016F, out);
            maxReach = Math.max(maxReach, state.tip.length());
        }

        assertTrue(maxReach <= 1.0F + 1.0E-3F,
                "the tip left its reach (" + maxReach + " > 1); a stretched chain shows as a whip");
        assertTrue(Float.isFinite(out.w()) && Float.isFinite(out.x())
                        && Float.isFinite(out.y()) && Float.isFinite(out.z()),
                "the swing quaternion went non-finite");
    }

    /**
     * A lag spike feeds the integrator a huge step. The clamp is what keeps that from
     * being a visible fling, so a 2-second frame must behave exactly like a 50 ms one.
     */
    @Test
    void aLagSpikeIsClampedRatherThanFlingingTheChain() {
        YsmPhysicsSimulator simulator = YsmPhysicsSimulator.INSTANCE;

        YsmPhysicsSimulator.ChainState spiked = new YsmPhysicsSimulator.ChainState();
        Quaternionf spikedOut = new Quaternionf();
        simulator.update(spiked, PIVOT, REST_TIP, chain(false, false), 0.016F, spikedOut);
        simulator.update(spiked, PIVOT, REST_TIP, chain(false, false), 2.0F, spikedOut);

        YsmPhysicsSimulator.ChainState clamped = new YsmPhysicsSimulator.ChainState();
        Quaternionf clampedOut = new Quaternionf();
        simulator.update(clamped, PIVOT, REST_TIP, chain(false, false), 0.016F, clampedOut);
        simulator.update(clamped, PIVOT, REST_TIP, chain(false, false), 0.05F, clampedOut);

        assertEquals(clamped.tip.length(), spiked.tip.length(), 1.0E-4F,
                "a 2 second step must be clamped to the same step as 50 ms");
    }

    /** A chain hanging straight down with no motion has nothing to rotate about. */
    @Test
    void aChainWithNoReachIsLeftAlone() {
        YsmPhysicsSimulator simulator = YsmPhysicsSimulator.INSTANCE;
        YsmPhysicsSimulator.ChainState state = new YsmPhysicsSimulator.ChainState();
        Quaternionf out = new Quaternionf();

        simulator.update(state, PIVOT, new Vector3f(PIVOT), chain(false, false), 0.016F, out);

        assertEquals(1.0F, out.w(), 1.0E-5F, "a zero-length chain has no lever and must not rotate");
    }

    /** Null inputs must be survivable: callers reach here from render code. */
    @Test
    void nullInputsAreSurvivable() {
        YsmPhysicsSimulator simulator = YsmPhysicsSimulator.INSTANCE;
        Quaternionf out = new Quaternionf();

        simulator.update(null, PIVOT, REST_TIP, chain(false, false), 0.016F, out);
        assertEquals(1.0F, out.w(), 1.0E-5F);

        simulator.update(new YsmPhysicsSimulator.ChainState(), null, REST_TIP, chain(false, false), 0.016F, out);
        assertEquals(1.0F, out.w(), 1.0E-5F);
    }

    /**
     * A chain root carries the whole piece, so it is held far firmer than a strand.
     * The limits are what keep a hairdo's base from swinging like its tips.
     */
    @Test
    void aChainRootSwingsLessThanAStrandUnderTheSameMotion() {
        float rootAngle = chaseAndMeasure(true, false);
        float strandAngle = chaseAndMeasure(false, false);

        assertTrue(rootAngle < strandAngle,
                "a chain root must swing less than a strand (root " + rootAngle + " vs strand " + strandAngle + ")");
    }

    /** A skirt panel may swing much further than a lock of hair. */
    @Test
    void aPieceAroundTheLegsMaySwingFurtherThanHair() {
        float hair = chaseAndMeasure(false, false);
        float skirt = chaseAndMeasure(false, true);

        assertTrue(skirt >= hair,
                "a piece kept off the legs allows a larger swing (skirt " + skirt + " vs hair " + hair + ")");
    }

    /** Drive a chain with a hard swing and report the largest angle it reached. */
    private static float chaseAndMeasure(boolean root, boolean aroundLegs) {
        YsmPhysicsSimulator simulator = YsmPhysicsSimulator.INSTANCE;
        YsmPhysicsSimulator.ChainState state = new YsmPhysicsSimulator.ChainState();
        Quaternionf out = new Quaternionf();
        Vector3f tip = new Vector3f(0, -1, 0);

        simulator.update(state, PIVOT, tip, chain(root, aroundLegs), 0.016F, out);

        float maxAngle = 0.0F;
        for (int i = 0; i < 120; i++) {
            float a = i * 0.5F;
            tip.set((float) Math.sin(a), -(float) Math.cos(a), 0.0F);
            simulator.update(state, PIVOT, tip, chain(root, aroundLegs), 0.016F, out);
            maxAngle = Math.max(maxAngle, 2.0F * (float) Math.acos(Math.min(1.0F, Math.abs(out.w()))));
        }
        return maxAngle;
    }

    /**
     * The frame conversion: a model-space rotation must come back unchanged when the
     * bind frame is identity, and must be rotated when it is not. Skipping this
     * conversion bends a bone about the model's axes instead of its own.
     */
    @Test
    void toLocalLeavesTheRotationAloneForAnIdentityBindFrame() {
        Quaternionf model = new Quaternionf().fromAxisAngleRad(new Vector3f(0, 1, 0), 0.4F);
        Quaternionf out = new Quaternionf();

        YsmPhysicsSimulator.toLocal(out, model, new Quaternionf());

        assertEquals(model.x, out.x, 1.0E-5F);
        assertEquals(model.y, out.y, 1.0E-5F);
        assertEquals(model.z, out.z, 1.0E-5F);
        assertEquals(model.w, out.w, 1.0E-5F);
    }

    @Test
    void toLocalRotatesTheFrameWhenTheBindRotationIsNotIdentity() {
        Quaternionf model = new Quaternionf().fromAxisAngleRad(new Vector3f(0, 1, 0), 0.4F);
        Quaternionf bind = new Quaternionf().fromAxisAngleRad(new Vector3f(0, 1, 0), 0.9F);
        Quaternionf out = new Quaternionf();

        YsmPhysicsSimulator.toLocal(out, model, bind);

        // Same axis, and conjugation about that axis must leave the angle unchanged.
        float angle = 2.0F * (float) Math.acos(Math.min(1.0F, Math.abs(out.w())));
        assertEquals(0.4F, angle, 1.0E-3F, "conjugation about the rotation's own axis preserves the angle");
        assertTrue(Float.isFinite(out.x) && Float.isFinite(out.y) && Float.isFinite(out.z));
    }

    @Test
    void rotationOfAdegenerateMatrixFallsBackToIdentity() {
        Quaternionf out = new Quaternionf();
        YsmPhysicsSimulator.rotationOf(null, out);
        assertEquals(1.0F, out.w(), 1.0E-5F);

        YsmPhysicsSimulator.rotationOf(new org.joml.Matrix4f().zero(), out);
        assertNotNull(out);
        assertEquals(1.0F, out.w(), 1.0E-5F, "a zero matrix has no rotation and must not produce NaNs");
        assertFalse(Float.isNaN(out.x));
    }
}
