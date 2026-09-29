package com.ysmef.compat.model.runtime;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the one transform the whole feature rests on: the per-part delta that Epic Fight
 * multiplies into a joint's skinning matrix.
 *
 * <p>Epic Fight draws a vertex as {@code deformation x delta x v}, where
 * {@code deformation = pose x toOrigin} (see {@code vanilla_mesh_transformer.comp} and
 * {@code VanillaComputeShaderSetup}). The delta the physics writes must therefore make that
 * product equal to rotating the <i>posed</i> part about its <i>posed</i> pivot:
 *
 * <pre>
 *   deformation x delta  ==  T(P) x Q x T(-P) x deformation,   P = deformation x bindPivot
 * </pre>
 *
 * <p>This is asserted on points rather than on matrix entries, because the failure it guards
 * against is invisible in the entries: a delta that is a rotation but about the wrong point is
 * still a perfectly good rotation matrix. What it does to a model is displace every part by
 * {@code 2|P| sin(theta/2)} - proportional to how far the part's pivot sits from the model
 * origin - so a skirt whose panels hang at different heights and angles flies apart while every
 * individual matrix looks correct.
 *
 * <p>The deformations used here are arbitrary rigid motions, including ones with translation
 * and rotation, because the identity has to hold for the pose the animation happens to be in,
 * not only for the bind pose where {@code deformation} is the identity.
 */
class YsmSegmentDeltaTest {

    private static final float EPSILON = 1.0E-4F;

    /**
     * The identity, on a pivot at hip height and a swing of the size the solver actually
     * produces: the posed pivot must be a fixed point of the composed transform.
     */
    @Test
    void thePosedPivotIsHeldInPlace() {
        Vector3f bindPivot = new Vector3f(0.0F, 1.4F, -0.15F);
        Quaternionf modelSwing = new Quaternionf()
                .fromAxisAngleRad(new Vector3f(1.0F, 0.2F, 0.0F).normalize(), 0.35F);
        OpenMatrix4f deformation = rigid(new Matrix4f().translate(0.1F, -0.2F, 0.05F)
                .rotateY(0.4F).rotateX(-0.2F));

        Vector3f posedPivot = transform(deformation, bindPivot);
        Vector3f expected = new Vector3f(posedPivot);

        Matrix4f delta = deltaOf(deformation, bindPivot, modelSwing);
        Vector3f actual = transform(deformation, transform(delta, bindPivot));

        assertEquals(expected.x, actual.x, EPSILON, "the pivot moved in x");
        assertEquals(expected.y, actual.y, EPSILON, "the pivot moved in y");
        assertEquals(expected.z, actual.z, EPSILON, "the pivot moved in z");
    }

    /**
     * The full identity, on points all over the part - including points far from the pivot,
     * which is where a wrong transform shows largest.
     */
    @Test
    void everyPointOfThePartIsRotatedNotDisplaced() {
        Vector3f bindPivot = new Vector3f(-0.19F, 1.44F, 0.0F);
        Quaternionf modelSwing = new Quaternionf()
                .fromAxisAngleRad(new Vector3f(0.3F, 1.0F, 0.1F).normalize(), 0.6F);
        Matrix4f source = new Matrix4f().translate(-0.05F, 0.35F, 0.2F).rotateZ(0.25F).rotateY(-0.8F);
        OpenMatrix4f deformation = rigid(source);

        Matrix4f delta = deltaOf(deformation, bindPivot, modelSwing);
        Vector3f posedPivot = transform(deformation, bindPivot);
        Matrix4f aboutPivot = new Matrix4f()
                .translate(posedPivot.x, posedPivot.y, posedPivot.z)
                .rotate(modelSwing)
                .translate(-posedPivot.x, -posedPivot.y, -posedPivot.z);

        // Diagnostics as assertions, so the failure message localises the wrong identification.
        assertVectorEquals(new Vector3f(bindPivot).mulPosition(source),
                transform(deformation, bindPivot), "the row-vector formula disagrees with JOML for this M");

        Quaternionf extracted = new Quaternionf();
        YsmMeshSecondaryMotion.rotationOf(deformation, extracted);
        Vector3f pureRotation = new Vector3f(bindPivot).mulPosition(source)
                .sub(source.m30(), source.m31(), source.m32());
        assertVectorEquals(pureRotation, new Vector3f(bindPivot).rotate(extracted),
                "rotationOf(M) is not M's rotational part (a transpose would show here)");

        Quaternionf bindSwing = new Quaternionf();
        YsmMeshSecondaryMotion.bindSwingOf(deformation, modelSwing, bindSwing);
        Matrix4f rotationPart = new Matrix4f().set(
                source.m00(), source.m01(), source.m02(), 0.0F,
                source.m10(), source.m11(), source.m12(), 0.0F,
                source.m20(), source.m21(), source.m22(), 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        assertTrue(new Matrix4f(rotationPart).mul(new Matrix4f().rotation(bindSwing))
                        .equals(new Matrix4f().rotation(modelSwing).mul(rotationPart), 1.0E-4F),
                "Mr x S must equal Q x Mr for the chain bindSwingOf produced");

        for (Vector3f point : points(bindPivot)) {
            Vector3f viaDelta = transform(deformation, transform(delta, point));
            Vector3f viaPivotRotation = new Vector3f(transform(deformation, point)).mulPosition(aboutPivot);
            assertEquals(viaPivotRotation.x, viaDelta.x, EPSILON, "x differs for " + point);
            assertEquals(viaPivotRotation.y, viaDelta.y, EPSILON, "y differs for " + point);
            assertEquals(viaPivotRotation.z, viaDelta.z, EPSILON, "z differs for " + point);
        }
    }

    private static void assertVectorEquals(Vector3f expected, Vector3f actual, String message) {
        assertEquals(expected.x, actual.x, 1.0E-4F, message + " [x]");
        assertEquals(expected.y, actual.y, 1.0E-4F, message + " [y]");
        assertEquals(expected.z, actual.z, 1.0E-4F, message + " [z]");
    }

    private static String fmt(Vector3f v) {
        return String.format("(%.5f, %.5f, %.5f)", v.x, v.y, v.z);
    }

    /**
     * The regression this class exists for, stated as a number: the previous version's delta was
     * a bare rotation about the model origin. The distance between what it produced and what it
     * should have produced is the displacement that scattered the skirt, and it is large.
     */
    @Test
    void aRotationAboutTheOriginInsteadOfThePivotWouldDisplaceThePart() {
        Vector3f bindPivot = new Vector3f(0.0F, 1.42F, -0.14F);
        Quaternionf modelSwing = new Quaternionf()
                .fromAxisAngleRad(new Vector3f(1.0F, 0.0F, 0.0F), 0.35F);
        OpenMatrix4f deformation = rigid(new Matrix4f());

        Matrix4f wrong = new Matrix4f().identity().rotate(modelSwing);
        Vector3f point = new Vector3f(bindPivot.x, bindPivot.y - 0.15F, bindPivot.z);

        float correct = new Vector3f(point)
                .mulPosition(deltaOf(deformation, bindPivot, modelSwing)).distance(point);
        float broken = new Vector3f(point).mulPosition(wrong).distance(point);

        assertTrue(correct < 0.12F,
                "rotating about the pivot moves a point near it by at most the arc it sweeps (got "
                        + correct + ")");
        assertTrue(broken > 0.25F,
                "and rotating about the model origin moves the same point by roughly the model's "
                        + "height times the angle (got " + broken + ") - which is the scatter");
    }

    /** A zero swing is exactly the identity, so a settled part is not touched at all. */
    @Test
    void aZeroSwingIsTheIdentity() {
        Matrix4f delta = new Matrix4f();

        YsmMeshSecondaryMotion.buildSegmentDelta(new Vector3f(0.1F, 1.2F, -0.3F), new Quaternionf(), delta);

        assertTrue(delta.equals(new Matrix4f(), 1.0E-6F), "a neutral swing must produce identity");
    }

    /** Null inputs must be survivable: this runs from render code. */
    @Test
    void nullInputsGiveIdentity() {
        Matrix4f delta = new Matrix4f().scale(3.0F);

        YsmMeshSecondaryMotion.buildSegmentDelta(null, new Quaternionf(), delta);
        assertTrue(delta.equals(new Matrix4f(), 1.0E-6F));

        YsmMeshSecondaryMotion.buildSegmentDelta(new Vector3f(), null, delta);
        assertTrue(delta.equals(new Matrix4f(), 1.0E-6F));
    }

    /**
     * The identity holds for random rigid motions and swings, not only for the hand-picked ones:
     * the deformations a real pose produces are arbitrary, and a transform that happens to be
     * right about an axis-aligned case can still be wrong in general.
     */
    @Test
    void theIdentityHoldsForRandomPoses() {
        Random random = new Random(20260913L);
        for (int i = 0; i < 40; i++) {
            Vector3f bindPivot = new Vector3f(
                    random.nextFloat() * 0.8F - 0.4F,
                    random.nextFloat() * 1.2F + 0.3F,
                    random.nextFloat() * 0.8F - 0.4F);
            Quaternionf swing = new Quaternionf().fromAxisAngleRad(
                    new Vector3f(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F, random.nextFloat() - 0.5F)
                            .normalize(),
                    random.nextFloat() * 1.0F);
            OpenMatrix4f deformation = rigid(new Matrix4f()
                    .translate(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F, random.nextFloat() - 0.5F)
                    .rotateY(random.nextFloat() * 3.0F)
                    .rotateX(random.nextFloat() * 3.0F)
                    .rotateZ(random.nextFloat() * 3.0F));

            Matrix4f delta = deltaOf(deformation, bindPivot, swing);
            Vector3f posedPivot = transform(deformation, bindPivot);
            Matrix4f aboutPivot = new Matrix4f()
                    .translate(posedPivot.x, posedPivot.y, posedPivot.z)
                    .rotate(swing)
                    .translate(-posedPivot.x, -posedPivot.y, -posedPivot.z);

            for (Vector3f point : points(bindPivot)) {
                Vector3f viaDelta = transform(deformation, transform(delta, point));
                Vector3f viaPivotRotation = new Vector3f(transform(deformation, point)).mulPosition(aboutPivot);
                assertEquals(viaPivotRotation.x, viaDelta.x, 2.0E-4F, "iteration " + i + " x");
                assertEquals(viaPivotRotation.y, viaDelta.y, 2.0E-4F, "iteration " + i + " y");
                assertEquals(viaPivotRotation.z, viaDelta.z, 2.0E-4F, "iteration " + i + " z");
            }
        }
    }

    /**
     * The algebra the delta rests on, asserted directly so a failure names the stage that is
     * wrong instead of only reporting a displaced point.
     *
     * <p>Writing {@code M} for the deformation, {@code Mr} its rotation, {@code S} the
     * bind-space swing and {@code Q} the model-space swing, the delta is correct exactly when
     * {@code Mr x S == Q x Mr}. That single product localises a convention mistake: if the
     * rotation is read transposed, the product is {@code Q x Mr⁻¹} instead, and the part turns
     * about the inverse axis.
     */
    @Test
    void theBindSpaceSwingConjugatesTheModelSwing() {
        Quaternionf modelSwing = new Quaternionf()
                .fromAxisAngleRad(new Vector3f(0.3F, 1.0F, 0.1F).normalize(), 0.6F);
        Matrix4f source = new Matrix4f().translate(-0.05F, 0.35F, 0.2F).rotateZ(0.25F).rotateY(-0.8F);
        OpenMatrix4f deformation = rigid(source);

        Quaternionf bindSwing = new Quaternionf();
        YsmMeshSecondaryMotion.bindSwingOf(deformation, modelSwing, bindSwing);

        // Mr x S, with Mr taken from the same matrix the point transform uses.
        Matrix4f rotation = new Matrix4f().set(
                source.m00(), source.m01(), source.m02(), 0.0F,
                source.m10(), source.m11(), source.m12(), 0.0F,
                source.m20(), source.m21(), source.m22(), 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        Matrix4f left = new Matrix4f(rotation).mul(new Matrix4f().rotation(bindSwing));
        Matrix4f right = new Matrix4f().rotation(modelSwing).mul(rotation);

        assertTrue(left.equals(right, 1.0E-4F),
                "Mr x S must equal Q x Mr; got\n" + left + "\nwant\n" + right);
    }

    /** `rotationOf` must agree with the point transform about which way the matrix turns things. */
    @Test
    void theExtractedRotationMatchesThePointTransform() {
        OpenMatrix4f deformation = rigid(new Matrix4f().translate(0.2F, -0.1F, 0.4F)
                .rotateY(0.9F).rotateX(0.3F));
        Quaternionf extracted = new Quaternionf();
        YsmMeshSecondaryMotion.rotationOf(deformation, extracted);

        Vector3f v = new Vector3f(0.31F, -0.22F, 0.73F);
        Vector3f byMatrix = YsmMeshSecondaryMotion.transformDirection(deformation, v, new Vector3f());
        Vector3f byQuaternion = new Vector3f(v).rotate(extracted);

        assertEquals(byMatrix.x, byQuaternion.x, 1.0E-4F, "x: the extracted rotation is not the matrix's");
        assertEquals(byMatrix.y, byQuaternion.y, 1.0E-4F, "y: the extracted rotation is not the matrix's");
        assertEquals(byMatrix.z, byQuaternion.z, 1.0E-4F, "z: the extracted rotation is not the matrix's");
    }

    // ------------------------------------------------------------------
    // The frame path: one path, and it is the solver
    // ------------------------------------------------------------------

    /** One frame of a 60 Hz client, the step the simulation is written for. */
    private static final float FRAME = 1.0F / 60.0F;

    /** No body turn, for the frames below: the swing under test comes from gravity alone. */
    private static final float[] NO_TURN = new float[2];

    /**
     * Every part of a model built from the author's physics animation is <b>integrated</b>, and the
     * transform it receives is a rotation about its own bind pivot.
     *
     * <p>This is the acceptance criterion the whole change is for, stated as observables rather than
     * as an argument about the code:
     *
     * <ul>
     *   <li>{@code integrated} is true for every segment - a flag only the solver's own call site
     *       sets, so a frame that wrote something else onto the parts cannot satisfy it;</li>
     *   <li>the parts do not move on the first frame (the solver adopts the pose) and do move under
     *       gravity afterwards, which is integration rather than an evaluation of angles;</li>
     *   <li>and each delta is {@code T(P) x R x T(-P)}: the pivot does not move, so whatever the
     *       swing is, it is a swing about the point the piece is sewn on at.</li>
     * </ul>
     *
     * <p>The model is the shape that produced the report - a chain whose top strand hangs sideways -
     * because the defect was reported on a tail and a skirt, not on a synthetic pendulum. The pose is
     * a leaning one rather than {@link IdentityPose}: a joint the animation has left as the rig
     * authors it is a joint the animation is holding still, and a piece hanging from one is pulled by
     * its spring alone (round 20) - so "gravity has swung it" is a statement about a joint the pose
     * has actually turned.
     */
    @Test
    void everyPartOfAnAuthoredModelIsIntegratedByTheSolver() {
        YsmPhysicsParts.Segment[] segments = chain();
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.AUTHORED, 0);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(model, null, 1.047F);
        YsmMeshSecondaryMotion.PoseSource pose = new LeaningPose(1.047F);
        YsmMeshSecondaryMotion.PoseSource still = new IdentityPose();

        assertEquals(segments.length,
                YsmMeshSecondaryMotion.simulate(state, pose, FRAME, null, NO_TURN,
                        YsmDynamicBoneSolver.NO_COLLIDERS),
                "every segment is handed to the solver, on the frame the model first appears");
        for (int i = 0; i < segments.length; i++) {
            assertTrue(state.integrated[i], "segment " + i + " was not integrated");
            assertTrue(same(state.deltas[i], new OpenMatrix4f(), 1.0E-6F),
                    "the first frame establishes the rest direction: nothing has swung yet, and a"
                            + " piece that moved on the frame it appeared would be a snap");
        }

        for (int frame = 0; frame < 60; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, FRAME, null, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }

        for (int i = 0; i < segments.length; i++) {
            assertTrue(state.integrated[i], "segment " + i + " stopped being integrated");
            assertTrue(!same(state.deltas[i], new OpenMatrix4f(), 1.0E-6F),
                    "segment " + i + " hangs off the vertical and gravity must have swung it;"
                            + " its delta is still the identity");
            Vector3f pivot = segments[i].bindPivot();
            Vector3f held = YsmMeshSecondaryMotion.transformPoint(state.deltas[i], pivot, new Vector3f());
            assertEquals(0.0F, held.distance(pivot), 1.0E-4F,
                    "a part transform is T(P) x R x T(-P): segment " + i + "'s pivot moved to " + held);
        }
    }

    /**
     * And the source label decides nothing about the frame.
     *
     * <p>Two models with the same segments, one of which declares its parts came from the author's
     * physics animation, must produce the same motion - because the author's animation is a source
     * of <i>which</i> bones and <i>how stiff</i>, never of transforms. This is the regression the
     * change exists for: the frame path used to branch on exactly that field and copy the author's
     * evaluated angles onto the parts instead, and a test that only built one of the two would not
     * notice it coming back.
     */
    @Test
    void theSourceLabelDecidesNothingAboutTheFrame() {
        YsmMeshSecondaryMotion.State authored = run(
                new YsmPhysicsParts.Model(chain(), YsmPhysicsParts.Source.AUTHORED, 0));
        YsmMeshSecondaryMotion.State classified = run(
                new YsmPhysicsParts.Model(chain(), YsmPhysicsParts.Source.BONE_NAMES, 0));

        YsmPhysicsParts.Segment[] segments = authored.parts.segments();
        assertEquals(YsmPhysicsParts.Source.AUTHORED, authored.parts.source(),
                "the two runs must actually differ in the thing under test");
        assertEquals(YsmPhysicsParts.Source.BONE_NAMES, classified.parts.source());
        for (int i = 0; i < segments.length; i++) {
            assertTrue(authored.integrated[i] && classified.integrated[i],
                    "segment " + i + " must be integrated in both models");
            assertTrue(same(authored.deltas[i], classified.deltas[i], 1.0E-5F),
                    "segment " + i + ": a model whose parts were declared and one whose parts were"
                            + " classified by name must be simulated identically");
        }
    }

    /**
     * The piece's bend is shared out along the chain, and no joint is frozen by the ones above it.
     *
     * <p>This is the tail defect as the frame path produces it, and it is asserted here rather than
     * only on {@link YsmPhysicsParts#chainAllowance} because the two numbers the allowance is
     * computed from - how big the piece's own total is, and how many joints are left - are gathered
     * by the frame loop itself. Four bones whose rest direction is off the vertical all want to swing
     * well past their share, so every one of them is held at its allowance and the arithmetic is
     * exact:
     *
     * <pre>
     *   the piece's total is chainLimitFor(4, 60) = 120 degrees, so 30 each, and none of them zero
     * </pre>
     *
     * <p>Under the rule this replaces, each joint was offered everything the ones above had left of a
     * flat sixty - so the first joints ate it and the ones below got {@code max(0, 60 - used)}, which
     * on the wine fox's tail was zero from the fourth bone on. The assertion that matters is the
     * first one below: <b>every</b> joint of the chain has an allowance above zero.
     */
    @Test
    void aFourBoneChainSharesItsBendAndFreezesNobody() {
        YsmPhysicsParts.Segment[] segments = {
                segment("Tail", 7, 0.4F, new Vector3f(1.0F, 0.0F, 0.0F), -1, new int[]{0, 1}),
                segment("Tail2", 7, 0.3F, new Vector3f(1.0F, 0.0F, 0.0F), 0, new int[]{2, 3}),
                segment("Tail3", 8, 0.2F, new Vector3f(1.0F, 0.0F, 0.0F), 1, new int[]{4, 5}),
                segment("Tail4", 8, 0.15F, new Vector3f(1.0F, 0.0F, 0.0F), 2, new int[]{6, 7})};
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.AUTHORED, 0);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(model, null, 1.047F);
        YsmPhysicsParts.Segment[] frameSegments = state.parts.segments();
        YsmMeshSecondaryMotion.PoseSource pose = new LeaningPose(1.047F);
        for (int frame = 0; frame <= 60; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, FRAME, null, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }

        for (int i = 0; i < frameSegments.length; i++) {
            assertTrue(state.chainBudget[i] > 1.0E-4F,
                    "joint " + i + " (" + frameSegments[i].boneName() + ") was left with "
                            + Math.toDegrees(state.chainBudget[i]) + "deg of allowance after "
                            + Math.toDegrees(state.chainUsed[i]) + "deg was spent on its chain");
        }
        assertEquals(120.0F, Math.toDegrees(state.pieceLimit[0]), 0.1F,
                "a four-joint piece is allowed 120 degrees in all, not the per-joint sixty");
        assertEquals(30.0F, Math.toDegrees(state.chainBudget[0]), 0.1F,
                "and the first joint takes a quarter of that: 30 degrees, where the flat-sixty"
                        + " version gave 15");
        assertEquals(30.0F, Math.toDegrees(state.chainBudget[3]), 0.1F,
                "and so does the fourth, where the greedy rule on a flat sixty left it zero");
        assertTrue(state.chainUsed[3] <= state.pieceLimit[0] + 1.0E-3F,
                "the chain as a whole still bends by the piece's own total; it spent "
                        + Math.toDegrees(state.chainUsed[3]) + "deg of "
                        + Math.toDegrees(state.pieceLimit[0]) + "deg");
    }

    /**
     * A seven-bone chain, through the frame path: every bone above the floor, none of them still.
     *
     * <p>The wine fox's tail, in the shape that produced the report: seven bones hanging off each
     * other, all wanting to swing well past what they are allowed. The piece's total is
     * {@code chainLimitFor(7, 60) = 120}, so each bone is allowed {@code 120/7 = 17.1} degrees - and
     * the number this replaces is {@code 60/7 = 8.6}, a tail that moves a third as much, and behind
     * that the greedy rule's zero for the four bones at the end of it.
     */
    @Test
    void aSevenBoneChainIsNotSharedIntoStillness() {
        YsmPhysicsParts.Segment[] segments = new YsmPhysicsParts.Segment[7];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = segment("Tail" + (i + 1), 7, 0.4F - 0.03F * i,
                    new Vector3f(1.0F, 0.0F, 0.0F), i - 1, new int[]{i * 2, i * 2 + 1});
        }
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.AUTHORED, 0);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(model, null, 1.047F);
        YsmMeshSecondaryMotion.PoseSource pose = new IdentityPose();
        for (int frame = 0; frame <= 60; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, FRAME, null, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }

        assertEquals(120.0F, Math.toDegrees(state.pieceLimit[6]), 0.1F,
                "the tail's own total is 120 degrees: seven joints at the cap, not a flat sixty");
        assertEquals(120.0F / 7.0F, Math.toDegrees(state.chainBudget[0]), 0.15F,
                "and its first bone is allowed a seventh of that: 17.1 degrees, where sharing a flat"
                        + " sixty gave 8.6");
        for (int i = 0; i < segments.length; i++) {
            assertTrue(Math.toDegrees(state.chainBudget[i]) >= Math.toDegrees(
                            YsmPhysicsParts.MIN_CHAIN_ANGLE_PER_JOINT) - 1.0E-4F,
                    "bone " + i + " of the tail is allowed only "
                            + Math.toDegrees(state.chainBudget[i]) + " degrees, under the floor that"
                            + " exists so a long chain is not shared into stillness");
        }
        assertTrue(state.chainUsed[6] <= state.pieceLimit[6] + 1.0E-3F,
                "the tail as a whole still bends by its own total; it spent "
                        + Math.toDegrees(state.chainUsed[6]) + "deg of "
                        + Math.toDegrees(state.pieceLimit[6]) + "deg");
    }

    /**
     * The deleted authored path is not in the frame code - checked on the source, because no model a
     * test can build reaches it.
     *
     * <p>A behavioural test cannot see this one: the branch that used to exist was taken before any
     * part was touched, and what it wrote depended on an entity, an armature and an animation the
     * test has none of. So the source is read, the way this project's independent acceptance suite
     * reads it, and the two names the deleted path had are required to be absent. A re-added
     * {@code applyAuthored}, or a branch on {@code Source.AUTHORED} in this class, fails here.
     */
    @Test
    void theFramePathCarriesNoAuthoredTransformWrite() throws IOException {
        Path source = sourceFile();
        assumeTrue(source != null,
                "the source tree is not reachable from " + System.getProperty("user.dir"));
        String code = withoutComments(
                new String(java.nio.file.Files.readAllBytes(source), StandardCharsets.UTF_8));

        assertTrue(!code.contains("applyAuthored"),
                "the authored transform path must be gone, not merely unreachable");
        assertTrue(!code.contains("Source.AUTHORED"),
                "the frame code must not branch on where the parts came from: it simulates them");
        assertTrue(code.contains("simulate("),
                "the frame is expected to run the one simulation seam, and this file no longer calls it");
    }

    /** The frame loop this file exercises, run for 61 frames: one to establish, sixty to swing. */
    private static YsmMeshSecondaryMotion.State run(YsmPhysicsParts.Model model) {
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(model, null, 1.047F);
        YsmMeshSecondaryMotion.PoseSource pose = new IdentityPose();
        for (int frame = 0; frame <= 60; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, FRAME, null, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }
        return state;
    }

    /**
     * A three-bone chain of the shape the report came from: the top strand hangs sideways, so
     * gravity has something to swing, and the two below it hang from it.
     */
    private static YsmPhysicsParts.Segment[] chain() {
        return new YsmPhysicsParts.Segment[]{
                segment("Tail", 7, 0.4F, new Vector3f(1.0F, 0.0F, 0.0F), -1, new int[]{0, 1}),
                segment("Tail2", 7, 0.3F, new Vector3f(0.0F, -1.0F, 0.0F), 0, new int[]{2, 3}),
                segment("Tail3", 8, 0.2F, new Vector3f(0.0F, -1.0F, 0.0F), 1, new int[]{4, 5})};
    }

    /** One segment of the chain: its own pivot at hip height, its own lever and its own spring. */
    private static YsmPhysicsParts.Segment segment(String name, int joint, float lever,
                                                   Vector3f rest, int parent, int[] parts) {
        Vector3f pivot = new Vector3f(0.0F, 1.0F, 0.0F);
        Vector3f bindRest = new Vector3f(rest).mul(lever);
        return new YsmPhysicsParts.Segment(0, name, joint, pivot, bindRest, lever, 0.03F, 1.0F,
                0.8F, 0.5F, 1.047F, parent, parts, true, new int[0]);
    }

    /** The pose as drawn, with no animation in it: every joint at the identity. */
    private static final class IdentityPose implements YsmMeshSecondaryMotion.PoseSource {
        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return new OpenMatrix4f();
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            return new OpenMatrix4f();
        }
    }

    /**
     * A pose that has turned every joint by sixty degrees about the model's left-right axis: the same
     * joints as {@link #IdentityPose}, with the animation in them.
     *
     * <p>The distinction is not cosmetic and it is round 20's whole subject. A joint the pose has left
     * as the rig authors it is a joint the animation is holding still, and a piece hanging from one is
     * pulled by its spring alone - so a test that wants to see gravity swing a piece has to say so by
     * rotating the joint. Both matrices are built from the same rotation, which is what makes
     * {@code deformation = pose x toOrigin} the rotation itself rather than something else.
     */
    private static final class LeaningPose implements YsmMeshSecondaryMotion.PoseSource {
        private final OpenMatrix4f rotation;

        LeaningPose(float radians) {
            rotation = new OpenMatrix4f();
            float c = (float) Math.cos(radians);
            float s = (float) Math.sin(radians);
            // A rotation about x, in Epic Fight's column-addressed fields: m<column><row>.
            rotation.m11 = c;
            rotation.m12 = s;
            rotation.m21 = -s;
            rotation.m22 = c;
        }

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return new OpenMatrix4f();
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            return rotation;
        }
    }

    /** {@code src/main/java/com/ysmef/compat/model/runtime/YsmMeshSecondaryMotion.java}, or null. */
    private static Path sourceFile() {
        Path directory = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int up = 0; up < 4 && directory != null; up++) {
            Path candidate = directory.resolve("src/main/java/com/ysmef/compat/model/runtime")
                    .resolve("YsmMeshSecondaryMotion.java");
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    /** The source with its comments removed, so a name that survives only in prose does not fail. */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean line = false;
        boolean block = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') {
                    line = false;
                    out.append(c);
                }
                continue;
            }
            if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                line = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                block = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Whether two EF matrices agree entry for entry; EF's own equality is not relied on here. */
    private static boolean same(OpenMatrix4f a, OpenMatrix4f b, float epsilon) {
        float[] left = {a.m00, a.m01, a.m02, a.m03, a.m10, a.m11, a.m12, a.m13,
                a.m20, a.m21, a.m22, a.m23, a.m30, a.m31, a.m32, a.m33};
        float[] right = {b.m00, b.m01, b.m02, b.m03, b.m10, b.m11, b.m12, b.m13,
                b.m20, b.m21, b.m22, b.m23, b.m30, b.m31, b.m32, b.m33};
        for (int i = 0; i < left.length; i++) {
            if (!(Math.abs(left[i] - right[i]) <= epsilon)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------

    private static Matrix4f deltaOf(OpenMatrix4f deformation, Vector3f bindPivot, Quaternionf modelSwing) {
        Quaternionf bindSwing = new Quaternionf();
        YsmMeshSecondaryMotion.bindSwingOf(deformation, modelSwing, bindSwing);
        Matrix4f delta = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(bindPivot, bindSwing, delta);
        return delta;
    }
    /** A handful of points spread over and beyond the part, so a wrong scale shows too. */
    private static Vector3f[] points(Vector3f pivot) {
        return new Vector3f[]{
                new Vector3f(pivot),
                new Vector3f(pivot.x, pivot.y - 0.2F, pivot.z),
                new Vector3f(pivot.x + 0.15F, pivot.y - 0.1F, pivot.z - 0.1F),
                new Vector3f(pivot.x - 0.1F, pivot.y + 0.05F, pivot.z + 0.15F),
                new Vector3f(pivot.x + 0.4F, pivot.y - 0.6F, pivot.z)};
    }

    /** A rigid motion as an EF matrix, built through JOML and copied field for field. */
    private static OpenMatrix4f rigid(Matrix4f source) {
        OpenMatrix4f out = new OpenMatrix4f();
        out.m00 = source.m00(); out.m01 = source.m01(); out.m02 = source.m02(); out.m03 = source.m03();
        out.m10 = source.m10(); out.m11 = source.m11(); out.m12 = source.m12(); out.m13 = source.m13();
        out.m20 = source.m20(); out.m21 = source.m21(); out.m22 = source.m22(); out.m23 = source.m23();
        out.m30 = source.m30(); out.m31 = source.m31(); out.m32 = source.m32(); out.m33 = source.m33();
        return out;
    }

    /** A point through an EF matrix, exactly as the skinning does it. */
    private static Vector3f transform(OpenMatrix4f m, Vector3f v) {
        return new Vector3f(
                v.x * m.m00 + v.y * m.m10 + v.z * m.m20 + m.m30,
                v.x * m.m01 + v.y * m.m11 + v.z * m.m21 + m.m31,
                v.x * m.m02 + v.y * m.m12 + v.z * m.m22 + m.m32);
    }

    /** A point through a JOML matrix. */
    private static Vector3f transform(Matrix4f m, Vector3f v) {
        return new Vector3f(v).mulPosition(m);
    }
}
