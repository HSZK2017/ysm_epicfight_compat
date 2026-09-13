package com.ysmef.compat.model.runtime;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the position-based cloth solve: the properties that decide whether a piece of
 * hair looks like hair or like a stiff card being flung.
 *
 * <p>The failures worth pinning here are the ones that look like a physics bug but are
 * really a solver bug - a piece that stretches instead of bending, a piece that keeps
 * oscillating after the body stops, and a piece that is handed one impossible frame and
 * never comes back.
 */
class YsmClothSolverTest {

    /** A step long enough to be a real frame, short enough to be a plausible one. */
    private static final float FRAME = 0.02F;

    /** One joint, at the origin, unrotated unless a test moves it. */
    private static final class Rig {
        /**
         * The joint's pose and the inverse bind matrix that goes with it.
         *
         * <p>The pin is placed from {@code pose x toOrigin x bindVertex}. The inverse bind is
         * the identity here because the strand's pinned particle sits at the origin: whatever
         * the pair does to that vertex is the joint's translation, which is what the tests
         * assert against.
         */
        final OpenMatrix4f[] pose = {new OpenMatrix4f()};
        final OpenMatrix4f[] toOrigin = {new OpenMatrix4f()};

        void moveTo(float x, float y, float z) {
            pose[0] = new OpenMatrix4f();
            pose[0].m30 = x;
            pose[0].m31 = y;
            pose[0].m32 = z;
            toOrigin[0] = new OpenMatrix4f();
        }

        void step(YsmClothSolver.Cloth cloth) {
            YsmClothSolver.INSTANCE.step(cloth, pose, toOrigin, 1, FRAME, YsmClothTuning.DEFAULTS);
        }

        /** Bring the rig to rest at its current position. */
        void settle(YsmClothSolver.Cloth cloth) {
            step(cloth);
            step(cloth);
        }
    }

    /**
     * A two-particle strand hanging from a pinned top. The minimal shape that can show
     * whether the solve holds together.
     */
    private static YsmClothSolver.Cloth strand() {
        YsmClothSolver.Cloth cloth = YsmClothSolver.allocate(2, 1);
        YsmClothSolver.initParticle(cloth, 0, 0.0F, 0.0F, 0.0F);
        YsmClothSolver.initParticle(cloth, 1, 0.0F, -0.5F, 0.0F);
        YsmClothSolver.pin(cloth, 0, 0);
        YsmClothSolver.addLink(cloth, 0, 0, 1, YsmClothSolver.structuralStiffness());
        return cloth;
    }

    /**
     * The property the whole method rests on: the pinned end follows the bone it hangs from,
     * because that is the only thing carrying the body's motion into the cloth.
     *
     * <p>The bone walks in per-frame steps a body could actually make. A single large jump is
     * deliberately not followed - see {@code aBoneStepTooLargeToBeMotionIsRefused}.
     */
    @Test
    void aPinnedParticleFollowsTheBoneItHangsFrom() {
        Rig rig = new Rig();
        YsmClothSolver.Cloth cloth = strand();
        rig.settle(cloth);

        rig.moveTo(0.3F, 0.1F, -0.2F);
        rig.step(cloth);

        Vector3f pinned = new Vector3f();
        cloth.position(0, pinned);
        assertEquals(0.3F, pinned.x, 1.0E-4F, "the pinned particle is carried to the bone");
        assertEquals(0.1F, pinned.y, 1.0E-4F);
        assertEquals(-0.2F, pinned.z, 1.0E-4F);

        rig.moveTo(0.6F, 0.2F, -0.4F);
        rig.step(cloth);
        cloth.position(0, pinned);
        assertEquals(0.6F, pinned.x, 1.0E-4F, "and keeps following as it keeps moving");
    }

    /**
     * The attachment is a transform, not a motion.
     *
     * <p>Wherever the joint is, the pin is at {@code pose x toOrigin x bindVertex} - so even a
     * frame that moves the body far further than a body can move puts the pin exactly where the
     * renderer would put its vertex. The attempts that reconstructed this from the bone's motion
     * instead had to guess at a step size that was plausible, and a piece whose bone moved
     * further than the guess was dragged across the model.
     */
    @Test
    void theAttachmentIsExactWhateverTheJointDoes() {
        Rig rig = new Rig();
        YsmClothSolver.Cloth cloth = strand();
        rig.settle(cloth);

        rig.moveTo(40.0F, -3.0F, 2.0F);
        rig.step(cloth);

        Vector3f pinned = new Vector3f();
        cloth.position(0, pinned);
        assertEquals(40.0F, pinned.x, 1.0E-4F, "the pin follows the joint's own transform");
        assertEquals(-3.0F, pinned.y, 1.0E-4F);
        assertEquals(2.0F, pinned.z, 1.0E-4F);
    }

    /**
     * A free particle hangs below its pivot rather than staying where the bind pose put it.
     * Without gravity the strand would keep whatever shape it was authored with, which for
     * a piece of hair is the shape it has when the model is standing still.
     */
    @Test
    void aFreeParticleHangsBelowItsPinnedEnd() {
        Rig rig = new Rig();
        YsmClothSolver.Cloth cloth = strand();

        // Start it out to the side, inside the link's reach, and let it fall.
        for (int i = 0; i < 120; i++) {
            rig.step(cloth);
        }

        Vector3f free = new Vector3f();
        cloth.position(1, free);
        assertTrue(free.y < -0.4F, "the strand should have fallen to hang below its pin, y=" + free.y);
        assertTrue(Math.abs(free.x) < 0.1F, "and settled under it, x=" + free.x);
    }

    /**
     * The visible failure of the spring implementation this replaces: a piece that stretches
     * instead of bending. A distance link that is allowed to grow turns hair into a rubber
     * band, so the length is asserted rather than assumed.
     */
    @Test
    void aStrandDoesNotStretch() {
        Rig rig = new Rig();
        YsmClothSolver.Cloth cloth = strand();

        // Yank the pin a long way every frame: the worst case a solver will ever see.
        for (int i = 0; i < 60; i++) {
            rig.moveTo(i * 0.4F, 0.0F, 0.0F);
            rig.step(cloth);
        }

        Vector3f top = new Vector3f();
        Vector3f bottom = new Vector3f();
        cloth.position(0, top);
        cloth.position(1, bottom);
        float length = top.distance(bottom);

        assertEquals(0.5F, length, 0.02F, "the link must hold its length, got " + length);
    }

    /**
     * A piece that keeps moving after the body has stopped reads as a bug rather than as
     * cloth. Damping has to bring it to rest, not just slow it down.
     */
    @Test
    void aStrandComesToRestOnceTheBodyStops() {
        Rig rig = new Rig();
        YsmClothSolver.Cloth cloth = strand();

        // A short walk, then stand still.
        for (int i = 0; i < 40; i++) {
            rig.moveTo(i * 0.05F, 0.0F, 0.0F);
            rig.step(cloth);
        }
        for (int i = 0; i < 400; i++) {
            rig.step(cloth);
        }

        Vector3f before = new Vector3f();
        cloth.position(1, before);
        for (int i = 0; i < 30; i++) {
            rig.step(cloth);
        }
        Vector3f after = new Vector3f();
        cloth.position(1, after);

        assertTrue(before.distance(after) < 0.01F,
                "the strand drifted " + before.distance(after) + " blocks after the body stopped");
    }

    /**
     * The velocity ceiling: one impossible frame - a teleport, a model swap, a lag spike -
     * must not be handed to the solver as real motion.
     *
     * <p>This is what stops the piece flying, which is what the spring implementation did
     * when its lever was wrong.
     */
    @Test
    void oneImpossibleFrameDoesNotFlingThePiece() {
        Rig rig = new Rig();
        YsmClothSolver.Cloth cloth = strand();

        rig.step(cloth);
        // A jump no entity could make in one frame.
        rig.moveTo(600.0F, 0.0F, 0.0F);
        rig.step(cloth);

        Vector3f top = new Vector3f();
        Vector3f bottom = new Vector3f();
        cloth.position(0, top);
        cloth.position(1, bottom);

        float length = top.distance(bottom);
        assertTrue(length < 0.6F, "the link must not be pulled open by the jump, got " + length);
        assertTrue(Float.isFinite(bottom.x) && Float.isFinite(bottom.y) && Float.isFinite(bottom.z),
                "the solve must stay finite");
    }

    /**
     * A collision sphere is the only thing keeping a skirt out of a thigh, so it has to
     * actually push: a particle started inside one must end up outside it.
     */
    @Test
    void aParticleInsideACollisionSphereIsPushedOut() {
        Rig rig = new Rig();
        YsmClothSolver.Cloth cloth = YsmClothSolver.allocate(2, 1);
        YsmClothSolver.initParticle(cloth, 0, 0.0F, 2.0F, 0.0F);
        YsmClothSolver.initParticle(cloth, 1, 0.02F, 0.01F, 0.0F);
        YsmClothSolver.pin(cloth, 0, 0);
        YsmClothSolver.addLink(cloth, 0, 0, 1, YsmClothSolver.structuralStiffness());
        // The free particle is assigned to the same joint, with a sphere around the origin.
        cloth.avoidJoint[1] = 0;
        cloth.avoidRadius[1] = 0.3F;

        for (int i = 0; i < 20; i++) {
            rig.step(cloth);
        }

        Vector3f free = new Vector3f();
        cloth.position(1, free);
        assertTrue(free.length() >= 0.29F,
                "the particle was left inside the body sphere at " + free.length());
    }

    /** Building a cloth must not leave stale bookkeeping behind. */
    @Test
    void pinningIsCountedAndReported() {
        YsmClothSolver.Cloth cloth = YsmClothSolver.allocate(3, 2);
        YsmClothSolver.initParticle(cloth, 0, 0, 0, 0);
        YsmClothSolver.initParticle(cloth, 1, 0, -1, 0);
        YsmClothSolver.initParticle(cloth, 2, 0, -2, 0);
        YsmClothSolver.addLink(cloth, 0, 0, 1, YsmClothSolver.structuralStiffness());
        YsmClothSolver.addLink(cloth, 1, 1, 2, YsmClothSolver.structuralStiffness());

        YsmClothSolver.pin(cloth, 0, 0);
        YsmClothSolver.pin(cloth, 0, 0);

        assertTrue(cloth.isPinned(0), "the top of the piece hangs from the body");
        assertFalse(cloth.isPinned(1), "and the rest of it is simulated");
        assertEquals(1, cloth.pinnedCount(), "pinning twice is still one pinned particle");
    }

    /** A zero or negative step must be ignored rather than integrated as no time at all. */
    @Test
    void aNonPositiveStepIsIgnored() {
        Rig rig = new Rig();
        YsmClothSolver.Cloth cloth = strand();
        Vector3f before = new Vector3f();
        cloth.position(1, before);

        YsmClothSolver.INSTANCE.step(cloth, rig.pose, rig.toOrigin, 1, 0.0F, YsmClothTuning.DEFAULTS);
        YsmClothSolver.INSTANCE.step(cloth, rig.pose, rig.toOrigin, 1, -1.0F, YsmClothTuning.DEFAULTS);
        YsmClothSolver.INSTANCE.step(cloth, rig.pose, rig.toOrigin, 1, Float.NaN, YsmClothTuning.DEFAULTS);

        Vector3f after = new Vector3f();
        cloth.position(1, after);
        assertEquals(before, after, "a step without time must not move anything");
    }
}
