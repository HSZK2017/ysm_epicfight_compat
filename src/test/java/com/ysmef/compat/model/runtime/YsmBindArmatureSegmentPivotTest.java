package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.JointTable;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The limb-pivot correction measured on the shipped fixtures: a joint's pivot may leave the geometry the
 * filter settled on for the joint's own <b>directly-mapped segment geometry</b>, but only when that
 * geometry reaches further up towards the joint's <b>parent</b>.
 *
 * <p>Every fixture here is the shape the two shipped models failed on and a shape the corpus says the
 * correction must NOT touch. The values are hand-built boxes, so each assertion is a distance between
 * two numbers the test itself wrote:
 *
 * <ul>
 *   <li>the decal at the ankle (0.16) versus the shin whose geometry reaches the hip (0.80) - the
 *       Nahobino / 绫濑桃 / Yukikaze shape, where the knee must be the shin's top;</li>
 *   <li>the same shin parked <i>above</i> the hips (3.00) - the counter-shape the corpus is full of
 *       (ReZero's second-form leg), where the decal must stay the pivot;</li>
 *   <li>a sleeve named outside the mapping table (1.45) beside a stub {@code LeftArm} (0.30) - the
 *       雪狐桑 shape, where the sleeve must stay;</li>
 *   <li>a hidden segment bone whose geometry is closer to the parent than the visible one, which must
 *       not become the pivot (hidden geometry never renders);</li>
 *   <li>the elbow, whose parent is the arm's pivot rather than the chest's.</li>
 * </ul>
 */
class YsmBindArmatureSegmentPivotTest {

    /** One bone of a fixture: name, EF joint, mapping flag, and a box of mesh vertices. */
    private static final class Bone {
        final String name;
        final int joint;
        final boolean mapped;
        final List<Vector3f> vertices = new ArrayList<>();

        Bone(String name, int joint, boolean mapped) {
            this.name = name;
            this.joint = joint;
            this.mapped = mapped;
        }

        /** A box whose top ring sits at {@code topY} (the value the segment top is read from). */
        Bone box(float cx, float topY, float cz) {
            for (int corner = 0; corner < 4; corner++) {
                float x = cx + ((corner & 1) == 0 ? -0.1F : 0.1F);
                float z = cz + ((corner & 2) == 0 ? -0.1F : 0.1F);
                vertices.add(new Vector3f(x, topY - 0.4F, z));
                vertices.add(new Vector3f(x, topY, z));
            }
            return this;
        }
    }

    private static YsmBindArmature.BindPivots bind(List<Bone> bones, String... hidden) {
        String[] names = new String[bones.size()];
        int[] joints = new int[bones.size()];
        boolean[] mapped = new boolean[bones.size()];
        List<YsmBindArmature.BoneGeometry> parts = new ArrayList<>();
        for (int i = 0; i < bones.size(); i++) {
            Bone bone = bones.get(i);
            names[i] = bone.name;
            joints[i] = bone.joint;
            mapped[i] = bone.mapped;
            parts.add(new YsmBindArmature.BoneGeometry(i, List.copyOf(bone.vertices)));
        }
        YsmBindArmature.GeometryInput input = new YsmBindArmature.GeometryInput(
                "segment-pivot-fixture", names, joints, mapped, Set.of(hidden), parts);
        YsmBindArmature.GeometryData geometry = YsmBindArmature.collectGeometry(input, message -> { });
        return YsmBindArmature.computePivots(input, geometry, message -> { });
    }

    /**
     * The shipped Nahobino shape: the shin's geometry is an alternate form (so the strict tier skips
     * it), the strict tier keeps an unmapped decal at the ankle, and the shin is the geometry that
     * reaches the hip. The knee must be the shin's top, not the decal's.
     */
    @Test
    @DisplayName("the knee is the shin that reaches the hip, not the decal the strict tier kept")
    void theSegmentBoneWinsWhenItReachesTheParent() {
        List<Bone> bones = List.of(
                new Bone("RightLeg", JointTable.THIGH_R, true).box(0.1F, 1.4F, 0F),
                new Bone("RightLowerLeg", JointTable.LEG_R, true),                 // present, no geometry
                new Bone("RightLowerLeg2", JointTable.LEG_R, true).box(0.1F, 0.8F, 0F),  // the shin
                new Bone("ysmGlowxiegen", JointTable.LEG_R, false).box(-0.1F, 0.16F, 0F)); // ankle decal
        YsmBindArmature.BindPivots pivots = bind(bones);

        Vector3f knee = pivots.byJoint().get(JointTable.LEG_R);
        assertNotNull(knee, "the leg joint must have a geometry-derived pivot");
        assertEquals(0.8F, knee.y, 1.0E-5F,
                "the knee must be measured on the shin's own top ring (0.8), not on the ankle decal (0.16)");
        assertEquals(0.1F, knee.x, 1.0E-5F, "the pivot must be the shin's centroid, not the decal's");
    }

    /**
     * The counter-shape the corpus is full of: the alternate form is a spare part parked above the
     * hips. It is not the segment, and the geometry the filter chose must stay the pivot.
     */
    @Test
    @DisplayName("a spare part parked above the hips does not become the knee")
    void aSegmentCandidateAboveTheParentDoesNotWin() {
        List<Bone> bones = List.of(
                new Bone("RightLeg", JointTable.THIGH_R, true).box(0.1F, 1.4F, 0F),
                new Bone("RightLowerLeg", JointTable.LEG_R, true),
                new Bone("RightLowerLeg2", JointTable.LEG_R, true).box(0.1F, 3.0F, 0F),   // parked high
                new Bone("RightSole", JointTable.LEG_R, false).box(0.1F, 0.55F, 0F));     // the real choice
        YsmBindArmature.BindPivots pivots = bind(bones);

        Vector3f knee = pivots.byJoint().get(JointTable.LEG_R);
        assertNotNull(knee);
        assertEquals(0.55F, knee.y, 1.0E-5F,
                "the parked alternate form (3.0) is farther from the hip than the chosen geometry (0.55), "
                        + "so the chosen geometry must stay the pivot");
    }

    /** A sleeve the mapping table does not know is the arm's real geometry; a stub segment bone is not. */
    @Test
    @DisplayName("a sleeve beside a stub arm bone keeps the shoulder where the sleeve is")
    void anUnmappedSleeveKeepsTheShoulderWhenTheSegmentBoneIsAStub() {
        List<Bone> bones = List.of(
                new Bone("Chest", JointTable.CHEST, true).box(0F, 1.5F, 0F),
                new Bone("LeftArm", JointTable.ARM_L, true),                                    // no geometry
                new Bone("LeftArm2", JointTable.ARM_L, true).box(-0.2F, 0.3F, 0F),              // stub, variant
                new Bone("LeftArmXiuzi", JointTable.ARM_L, false).box(-0.3F, 1.45F, 0F));       // sleeve
        YsmBindArmature.BindPivots pivots = bind(bones);

        Vector3f shoulder = pivots.byJoint().get(JointTable.ARM_L);
        assertNotNull(shoulder);
        assertEquals(1.45F, shoulder.y, 1.0E-5F,
                "the sleeve (1.45) is far closer to the parent than the stub (0.3), so it must stay the pivot");
    }

    /** A joint with no mapped geometry at all keeps the filter's answer - the user's own model's case. */
    @Test
    @DisplayName("a model with no mapped segment geometry anywhere keeps its pivots unchanged")
    void aModelWithoutMappedGeometryIsUntouched() {
        List<Bone> bones = List.of(
                new Bone("LeftLeg", JointTable.THIGH_L, false).box(-0.09F, 0.64F, 0F),
                new Bone("X_pifu_xiaotui_F_1", JointTable.LEG_L, false).box(-0.09F, 0.33F, 0F),
                new Bone("XZ_1_2", JointTable.LEG_L, false).box(-0.09F, 0.08F, 0F));
        YsmBindArmature.BindPivots pivots = bind(bones);

        Vector3f knee = pivots.byJoint().get(JointTable.LEG_L);
        assertNotNull(knee);
        assertEquals(0.33F, knee.y, 1.0E-5F,
                "with no mapped geometry there is no segment candidate; the shin/sock geometry must stay");
    }

    /**
     * Hidden geometry never renders, so it may not define a segment pivot even when it is the geometry
     * closest to the parent. (It can still be the settled choice for a joint with nothing visible.)
     */
    @Test
    @DisplayName("a hidden segment bone does not become the pivot of a joint that has visible geometry")
    void aHiddenSegmentCandidateDoesNotWin() {
        List<Bone> bones = List.of(
                new Bone("RightLeg", JointTable.THIGH_R, true).box(0.1F, 1.4F, 0F),
                new Bone("RightLowerLeg", JointTable.LEG_R, true).box(0.1F, 1.1F, 0F),   // hidden, close to hip
                new Bone("RightSole", JointTable.LEG_R, false).box(0.1F, 0.55F, 0F));    // visible, chosen
        YsmBindArmature.BindPivots pivots = bind(bones, "RightLowerLeg");

        Vector3f knee = pivots.byJoint().get(JointTable.LEG_R);
        assertNotNull(knee);
        assertEquals(0.55F, knee.y, 1.0E-5F,
                "the hidden segment bone (1.1) is closer to the hip than the visible geometry (0.55), but "
                        + "hidden geometry must not define the pivot of a joint that has visible geometry");
    }

    /**
     * The elbow's parent is the arm's pivot, not the chest's: the same two candidates swap places
     * depending on which reference is used, so this pins the staged reference. (The mapped hand bone
     * has to be an alternate form for the choice to be live at all: a mapped bone in the strict tier
     * wins by the filter's own rule and the parent would never be consulted.)
     */
    @Test
    @DisplayName("the elbow is measured against the arm's pivot, not against the chest's")
    void theElbowUsesTheArmPivotAsItsParent() {
        List<Bone> bones = List.of(
                new Bone("Chest", JointTable.CHEST, true).box(0F, 1.5F, 0F),
                new Bone("RightArm", JointTable.ARM_R, false).box(0.2F, 0.7F, 0F),
                new Bone("RightHand", JointTable.HAND_R, true),                                  // no geometry
                new Bone("RightHand2", JointTable.HAND_R, true).box(0.2F, 0.9F, 0F),             // segment, variant
                new Bone("RightHandCloth", JointTable.HAND_R, false).box(0.2F, 1.3F, 0F));       // filter's choice
        YsmBindArmature.BindPivots pivots = bind(bones);

        Vector3f elbow = pivots.byJoint().get(JointTable.HAND_R);
        assertNotNull(elbow);
        assertEquals(0.9F, elbow.y, 1.0E-5F,
                "against the arm pivot (0.7) the mapped hand (0.9) is closer than the cloth (1.3); against "
                        + "the chest (1.5) the answer would be the cloth - so this pins the parent reference");
    }
}
