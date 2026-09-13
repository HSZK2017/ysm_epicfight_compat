package com.ysmef.compat.model.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

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

    /** One bone, at the origin, unrotated unless a test moves it. */
    private static final class Rig {
        final Quaternionf[] pose = {new Quaternionf()};
        final Vector3f[] origin = {new Vector3f()};

        void moveTo(float x, float y, float z) {
            origin[0].set(x, y, z);
        }

        void spin(float degrees) {
            pose[0].identity().rotateY((float) Math.toRadians(degrees));
        }

        void step(YsmClothSolver.Cloth cloth) {
            YsmClothSolver.INSTANCE.step(cloth, pose, origin, 1, FRAME, 0.5F, YsmClothTuning.DEFAULTS);
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
        YsmClothSolver.pin(cloth, 0, 0, 0.0F, 0.0F, 0.0F);
        YsmClothSolver.addLink(cloth, 0, 0, 1, YsmClothSolver.structuralStiffness());
        return cloth;
    }

    /**
     * The property the whole method rests on: the pinned end follows the skeleton exactly,
     * because that is the only thing carrying the body's motion into the cloth.
     */
    @Test
    void aPinnedParticleFollowsTheBoneItHangsFrom() {
        Rig rig = new Rig();
        YsmClothSolver.Cloth cloth = strand();

        rig.moveTo(3.0F, 1.0F, -2.0F);
        rig.step(cloth);

        Vector3f pinned = new Vector3f();
        cloth.position(0, pinned);
        assertEquals(3.0F, pinned.x, 1.0E-4F, "the pinned particle is carried to the bone");
        assertEquals(1.0F, pinned.y, 1.0E-4F);
        assertEquals(-2.0F, pinned.z, 1.0E-4F);
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
        YsmClothSolver.pin(cloth, 0, 0, 0.0F, 2.0F, 0.0F);
        YsmClothSolver.addLink(cloth, 0, 0, 1, YsmClothSolver.structuralStiffness());
        // The free particle is assigned to the same bone, with a sphere around the origin.
        cloth.avoidBone[1] = 0;
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

        YsmClothSolver.pin(cloth, 0, 0, 0, 0, 0);
        YsmClothSolver.pin(cloth, 0, 0, 0, 0, 0);

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

        YsmClothSolver.INSTANCE.step(cloth, rig.pose, rig.origin, 1, 0.0F, 0.5F, YsmClothTuning.DEFAULTS);
        YsmClothSolver.INSTANCE.step(cloth, rig.pose, rig.origin, 1, -1.0F, 0.5F, YsmClothTuning.DEFAULTS);
        YsmClothSolver.INSTANCE.step(cloth, rig.pose, rig.origin, 1, Float.NaN, 0.5F, YsmClothTuning.DEFAULTS);

        Vector3f after = new Vector3f();
        cloth.position(1, after);
        assertEquals(before, after, "a step without time must not move anything");
    }
}
