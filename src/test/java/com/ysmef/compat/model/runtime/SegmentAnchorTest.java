package com.ysmef.compat.model.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.ysmef.compat.model.runtime.YsmPhysicsParts.Segment;

import yesman.epicfight.api.utils.math.OpenMatrix4f;

/**
 * The hinge a piece's delta turns it about: <b>where the piece is held</b>, not where its pivot is.
 *
 * <h2>The defect these tests pin</h2>
 *
 * <p>The frame path draws a simulated part as {@code deformation x delta x vertex} with
 * {@code delta = T(A) R T(-A)}. {@code A} used to be the piece's bind pivot, which is a hinge only for a
 * piece whose geometry is held at that pivot. For the reported top-of-head cap, whose pivot is 0.107
 * blocks inside its own geometry, the same delta <b>sweeps</b> the end the piece is attached by, and the
 * user sees the cap lift off the skull when she looks down and sink into it when she looks up. The
 * measurements behind the choice of hinge are in {@code build/reports/ysm-reanchor-round.md}; what is
 * asserted here is the behaviour, on geometry small enough to check by hand.
 *
 * <p>Two tests are written so that moving the hinge back to the pivot - the mutation this round's
 * verification applies - turns them red, and they are the pair that says why: the held end must not move,
 * and the pivot must no longer be the point that is held.
 */
class SegmentAnchorTest {

    /** One frame at 60 Hz, the step every test here advances by. */
    private static final float FRAME = 1.0F / 60.0F;

    private static final float[] NO_TURN = new float[2];

    /** The joint the synthetic pieces hang from, and the one the leaning pose turns. */
    private static final int JOINT = 9;

    /**
     * A piece whose pivot is inside its own volume, resting on a smaller box below it: the cap's shape.
     *
     * <p>The pivot sits at the middle of the piece; the contact is its bottom face, all four corners of
     * which are equally far from the box below. That equality is the point: the hinge has to be the
     * <i>centre</i> of the face, which is not a vertex of anything, so a rule that picked the nearest
     * single vertex would put it on a corner 0.2 blocks away in x and z.
     */
    @Test
    void theHingeIsTheCentreOfThePatchThePieceRestsOn() {
        List<Vector3f> cap = box(-0.2F, 1.0F, -0.2F, 0.2F, 1.4F, 0.2F);
        List<Vector3f> skull = box(-0.15F, 0.9F, -0.15F, 0.15F, 1.1F, 0.15F);
        Vector3f pivot = new Vector3f(0.0F, 1.25F, 0.0F);

        Vector3f anchor = YsmPhysicsParts.contactAnchor(cap, skull, pivot, 0.25F);

        assertEquals(0.0F, anchor.x, 1.0E-4F, "the hinge is not centred on the contact: " + anchor);
        assertEquals(1.0F, anchor.y, 1.0E-4F, "the hinge is not on the face that touches: " + anchor);
        assertEquals(0.0F, anchor.z, 1.0E-4F, "the hinge is not centred on the contact: " + anchor);
        assertEquals(0.25F, anchor.distance(pivot), 1.0E-3F,
                "the cap's hinge should be a quarter of a block below its pivot, not on it");
    }

    /**
     * A strand whose pivot is at its root: the hinge is the pivot and nothing changes for it.
     *
     * <p>This is the other half of the contract, and the reason no classification is involved. A piece
     * whose pivot is already where it is held must keep exactly the motion it has, and it does, because
     * the measured hinge and the pivot are the same point.
     */
    @Test
    void aStrandHeldAtItsOwnPivotKeepsItsPivot() {
        List<Vector3f> strand = box(-0.05F, 0.8F, -0.05F, 0.05F, 1.0F, 0.05F);
        List<Vector3f> head = box(-0.15F, 1.0F, -0.15F, 0.15F, 1.3F, 0.15F);
        Vector3f pivot = new Vector3f(0.0F, 1.0F, 0.0F);

        Vector3f anchor = YsmPhysicsParts.contactAnchor(strand, head, pivot, 0.2F);

        assertEquals(0.0F, anchor.distance(pivot), 1.0E-3F,
                "a strand held at its root must keep its pivot as its hinge, not be moved: " + anchor);
    }

    /** A piece that rests on nothing measurable keeps the pivot: the one answer that changes nothing. */
    @Test
    void aPieceThatRestsOnNothingKeepsItsPivot() {
        List<Vector3f> cap = box(-0.2F, 1.0F, -0.2F, 0.2F, 1.4F, 0.2F);
        Vector3f pivot = new Vector3f(0.0F, 1.25F, 0.0F);

        assertSame(pivot, YsmPhysicsParts.contactAnchor(cap, null, pivot, 0.25F),
                "no geometry above must mean no re-anchoring");
        assertSame(pivot, YsmPhysicsParts.contactAnchor(cap, new ArrayList<>(), pivot, 0.25F),
                "an empty cloud above must mean no re-anchoring");
        assertSame(pivot, YsmPhysicsParts.contactAnchor(new ArrayList<>(), cap, pivot, 0.25F),
                "a piece with no geometry of its own must mean no re-anchoring");
    }

    /**
     * The search walks up past ancestors that carry no geometry.
     *
     * <p>On the reported model the cap's parent is {@code Hair}, which has no geometry of its own, and
     * the first ancestor that has any is the skull. A rule that read the parent alone would find nothing
     * there and leave the cap hinged at its pivot - the defect, unfixed, on the one model it was reported
     * on.
     */
    @Test
    void theContactSearchWalksPastAncestorsWithNoGeometry() {
        YSMRuntimeModel.BoneRt[] bones = new YSMRuntimeModel.BoneRt[3];
        bones[0] = bone("Head", -1);
        bones[1] = bone("Hair", 0);
        bones[2] = bone("BaseHair", 1);
        Map<Integer, List<Vector3f>> vertices = new HashMap<>();
        List<Vector3f> skull = box(-0.15F, 0.9F, -0.15F, 0.15F, 1.35F, 0.15F);
        vertices.put(0, skull);
        vertices.put(2, box(-0.2F, 1.0F, -0.2F, 0.2F, 1.4F, 0.2F));

        assertSame(skull, YsmPhysicsParts.restsOnGeometry(bones, 2, vertices),
                "the search stopped on an ancestor with no geometry instead of walking past it");
        assertNull(YsmPhysicsParts.restsOnGeometry(bones, 0, vertices),
                "a root bone rests on nothing");
    }

    /**
     * <b>The defect, through the production frame path.</b>
     *
     * <p>A cap whose pivot is inside it is simulated by {@code YsmMeshSecondaryMotion#simulate} for one
     * hundred frames with the joint leaning, and the piece's own held end is read back out of the delta
     * the frame path published. It must not move: a piece that slides relative to the body it is attached
     * to is exactly what the user reported.
     *
     * <p>The test is written against the delta the frame path <i>published</i> rather than against the
     * delta builder, so the mutation this round applies - putting {@code segment.bindPivot()} back at the
     * call site - is what makes it fail, not a change to a helper the frame path does not use.
     */
    @Test
    void theHeldEndDoesNotSlideWhenTheFramePathDrawsThePiece() {
        Vector3f pivot = new Vector3f(0.0F, 1.25F, 0.0F);
        Vector3f anchor = new Vector3f(0.0F, 1.0F, 0.0F);
        Segment segment = new Segment(0, "BaseHair", JOINT, pivot, anchor,
                new Vector3f(0.0F, -0.2F, 0.0F), 0.2F, 0.12F, 4.0F, 0.8F, 0.5F, 1.047F, -1,
                new int[]{0}, true, new int[0], YsmPhysicsParts.Category.HAIR);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                new YsmPhysicsParts.Model(new Segment[]{segment}, YsmPhysicsParts.Source.BONE_NAMES, 0),
                null, 1.047F);

        YsmMeshSecondaryMotion.PoseSource leaning = new LeaningPose(0.5F);
        for (int frame = 0; frame < 100; frame++) {
            YsmMeshSecondaryMotion.simulate(state, leaning, FRAME, null, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }

        Vector3f delta = new Vector3f();
        float anchorMovement = YsmMeshSecondaryMotion.transformPoint(state.deltas[0], anchor, delta)
                .distance(anchor);
        float pivotMovement = YsmMeshSecondaryMotion.transformPoint(state.deltas[0], pivot, delta)
                .distance(pivot);

        assertEquals(0.0F, anchorMovement, 1.0E-4F,
                "the end the piece is held by moved " + anchorMovement
                        + " blocks: the delta is turning it about its pivot, not about the point it is"
                        + " held by");
        assertTrue(pivotMovement > 0.05F,
                "the pivot should be carried by the swing - it is not the hinge any more - but it moved"
                        + " only " + pivotMovement + " blocks, so the swing itself may be missing");
    }

    /**
     * And the same delta, built about the pivot instead, slides the held end: the defect in one number.
     *
     * <p>This is the control for the test above. It is not a statement about the shipped code - it is what
     * the shipped code used to do, measured on the same geometry and the same settled swing, so that
     * "the hinge matters" is a number and not an argument.
     */
    @Test
    void buildingTheSameDeltaAboutThePivotWouldSlideTheHeldEnd() {
        Vector3f pivot = new Vector3f(0.0F, 1.25F, 0.0F);
        Vector3f anchor = new Vector3f(0.0F, 1.0F, 0.0F);
        Quaternionf swing = new Quaternionf().rotateAxis((float) Math.toRadians(30.0), 1.0F, 0.0F, 0.0F);

        Matrix4f aboutAnchor = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(anchor, swing, aboutAnchor);
        Matrix4f aboutPivot = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(pivot, swing, aboutPivot);

        float heldByAnchor = new Vector3f(anchor).mulPosition(aboutAnchor).distance(anchor);
        float heldByPivot = new Vector3f(anchor).mulPosition(aboutPivot).distance(anchor);

        assertEquals(0.0F, heldByAnchor, 1.0E-5F, "a delta about the anchor must hold the anchor");
        assertTrue(heldByPivot > 0.1F,
                "the old hinge should slide the held end by a visible amount, but it moved "
                        + heldByPivot);
        // And both leave the piece rotated by the same angle: the fix moves no angle at all.
        Quaternionf movedAbout = new Quaternionf().setFromUnnormalized(aboutAnchor);
        Quaternionf movedAboutPivot = new Quaternionf().setFromUnnormalized(aboutPivot);
        float agreement = Math.abs(movedAbout.x * movedAboutPivot.x + movedAbout.y * movedAboutPivot.y
                + movedAbout.z * movedAboutPivot.z + movedAbout.w * movedAboutPivot.w);
        assertEquals(1.0F, agreement, 1.0E-5F,
                "re-anchoring must not change the solved rotation at all");
    }

    /** A segment built by hand keeps its pivot as its hinge - the identity its callers rely on. */
    @Test
    void aHandBuiltSegmentTurnsAboutItsPivot() {
        Vector3f pivot = new Vector3f(0.1F, 0.2F, 0.3F);
        Segment segment = new Segment(0, "Tail", 7, pivot, new Vector3f(0.0F, -0.1F, 0.0F), 0.1F, 0.03F,
                1.0F, 0.8F, 0.5F, 1.047F, -1, new int[0], true, new int[0]);
        assertNotNull(segment.bindAnchor());
        assertEquals(0.0F, segment.bindAnchor().distance(pivot), 1.0E-6F,
                "the convenience constructor must leave the hinge where it was");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The eight corners of an axis-aligned box: a voxel part with nothing inside it. */
    private static List<Vector3f> box(float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        List<Vector3f> out = new ArrayList<>(8);
        for (int corner = 0; corner < 8; corner++) {
            out.add(new Vector3f((corner & 1) == 0 ? minX : maxX,
                    (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ));
        }
        return out;
    }

    private static YSMRuntimeModel.BoneRt bone(String name, int parent) {
        YSMRuntimeModel.BoneRt rt = new YSMRuntimeModel.BoneRt();
        rt.name = name;
        rt.parent = parent;
        rt.joint = JOINT;
        return rt;
    }

    /** A pose that has turned every joint about the model's left-right axis, so gravity has work to do. */
    private static final class LeaningPose implements YsmMeshSecondaryMotion.PoseSource {
        private final OpenMatrix4f rotation;

        LeaningPose(float radians) {
            rotation = new OpenMatrix4f();
            float c = (float) Math.cos(radians);
            float s = (float) Math.sin(radians);
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
}
