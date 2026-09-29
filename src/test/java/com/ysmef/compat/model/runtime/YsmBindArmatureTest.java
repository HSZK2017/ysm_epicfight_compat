package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.JointTable;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bind-geometry filter and the pivots it feeds, on models small enough to reason about.
 *
 * <p>The subject is the failure the game logs showed: {@code 兽耳酱x1.ysm} and {@code NagaU_Kemomimi.ysm}
 * logged {@code [bind] ... pivots root=null,torso=null,...} for every joint, and
 * {@code STRESSTEST_SMT_Nahobino.ysm} lost its whole arm chain, because the alternate-form rule
 * discarded every bone whose name ends in a digit - including the models that have no alternate forms
 * at all. Null pivots are not an error message in Epic Fight: they silently mean "keep the reference
 * biped's pivot", so the model's limbs rotated about Steve's joints and the geometry visibly separated.
 *
 * <p>Every fixture is built from {@link JointTable} ids and plain vertices, so the real filter and the
 * real pivot arithmetic run here without a game. The three properties pinned are: a digit-suffixed
 * name with no base form is an ordinary name (the rule is sibling-relative), a real variant is still
 * excluded, and no joint - and no model - is ever left without geometry: the filters relax instead,
 * and say so.
 */
class YsmBindArmatureTest {

    // ------------------------------------------------------------------
    // The rule: a trailing digit is a variant marker only if the base form is there
    // ------------------------------------------------------------------

    /**
     * The confirmed regression, on the shape of the two all-null models: fifteen bones, every one of
     * them digit-suffixed, not one base form present. Nothing here declares an alternate form, so the
     * standard filter set must keep the whole skeleton, no relaxation may be needed, and every pivot
     * the {@code [bind]} line prints must resolve to the model's own geometry.
     */
    @Test
    @DisplayName("a skeleton whose every bone ends in a digit keeps its real pivots")
    void everyBoneEndingInADigitIsNotAnAlternateForm() {
        List<Bone> bones = List.of(
                new Bone("X_T4_1", JointTable.TORSO, true).box(0.0F, 1.0F, 0.0F),
                new Bone("X_T6_1", JointTable.CHEST, true).box(0.0F, 1.4F, 0.0F),
                new Bone("X_T7_1", JointTable.HEAD, true).box(0.0F, 1.7F, 0.0F),
                new Bone("X_yiqun1", JointTable.THIGH_R, true).box(0.1F, 1.0F, 0.0F),
                new Bone("X_qunzi1", JointTable.THIGH_L, true).box(-0.1F, 1.0F, 0.0F),
                new Bone("X_T3_1", JointTable.LEG_R, true).box(0.1F, 0.5F, 0.0F),
                new Bone("X_T5_1", JointTable.LEG_L, true).box(-0.1F, 0.5F, 0.0F),
                new Bone("X_liuhai_F_1", JointTable.ARM_R, true).box(0.3F, 1.3F, 0.0F),
                new Bone("X_liuhai_Z_1", JointTable.ARM_L, true).box(-0.3F, 1.3F, 0.0F),
                new Bone("Left_ear_F_1", JointTable.HAND_R, true).box(0.4F, 1.0F, 0.0F),
                new Bone("Right_ear_R_1", JointTable.HAND_L, true).box(-0.4F, 1.0F, 0.0F),
                new Bone("X_liuhai_R_1", JointTable.TORSO, true).box(0.0F, 1.05F, 0.2F),
                new Bone("X_fawei1", JointTable.HEAD, true).box(0.0F, 1.75F, 0.0F),
                new Bone("X_T2_1", JointTable.LEG_R, true).box(0.1F, 0.45F, 0.0F),
                new Bone("X_Hair1", JointTable.HEAD, true).box(0.0F, 1.8F, 0.0F));

        Bind bind = bind(input("兽耳酱x1.ysm", bones));

        assertNull(bind.geometry().relaxation(),
                "no bone here has a base form, so the standard filters keep all of them: "
                        + "nothing may need relaxing to find a pivot");
        assertTrue(bind.warnings().isEmpty(), "a clean model must be silent: " + bind.warnings());

        // The ten values the [bind] line prints, each from the model's own geometry.
        assertPivot(0.0F, 1.0F, 0.0F, bind.pivots().byJoint().get(JointTable.ROOT),
                "the hip is the midpoint of the thigh tops");
        assertPivot(0.0F, 1.2F, 0.0F, bind.pivots().byJoint().get(JointTable.CHEST),
                "the chest is halfway between the hip and the neck");
        assertPivot(0.0F, 1.4F, 0.0F, bind.pivots().byJoint().get(JointTable.HEAD),
                "the neck is the top of the chest geometry");
        assertPivot(0.3F, 1.3F, 0.0F, bind.pivots().byJoint().get(JointTable.ARM_R),
                "the right shoulder is the top of the right arm geometry");
        assertPivot(-0.4F, 1.0F, 0.0F, bind.pivots().byJoint().get(JointTable.HAND_L),
                "the left elbow is the top of the left forearm geometry");

        for (int joint : new int[]{JointTable.ROOT, JointTable.TORSO, JointTable.CHEST, JointTable.HEAD,
                JointTable.SHOULDER_R, JointTable.SHOULDER_L, JointTable.ELBOW_R, JointTable.ELBOW_L,
                JointTable.THIGH_R, JointTable.THIGH_L, JointTable.LEG_R, JointTable.LEG_L}) {
            assertNotNull(bind.pivots().byJoint().get(joint),
                    JointTable.nameOf(joint) + " must not fall back to the reference biped's pivot");
        }
    }

    /**
     * The same rule on the second failing model's naming: {@code STRESSTEST_SMT_Nahobino.ysm} carries
     * its arm chain on bones called {@code 2}..{@code 21}, {@code 0015}, {@code Cf2} and
     * {@code joint1}..{@code joint9} - digit-suffixed, all-digit, and digit-suffixed with a base form
     * that is not a bone ({@code Cf}, {@code joint}). Its torso chain resolved and its arms did not,
     * which is exactly this test's fixture shape.
     */
    @Test
    @DisplayName("all-digit and dangling-base names are ordinary bone names")
    void namesWithNoBaseFormAreNotVariants() {
        List<Bone> bones = List.of(
                new Bone("Torso", JointTable.TORSO, true).box(0.0F, 1.0F, 0.0F),
                new Bone("Chest", JointTable.CHEST, true).box(0.0F, 1.4F, 0.0F),
                new Bone("Head", JointTable.HEAD, true).box(0.0F, 1.7F, 0.0F),
                new Bone("Thigh_R", JointTable.THIGH_R, true).box(0.1F, 1.0F, 0.0F),
                new Bone("Thigh_L", JointTable.THIGH_L, true).box(-0.1F, 1.0F, 0.0F),
                new Bone("0015", JointTable.ARM_R, true).box(0.3F, 1.3F, 0.0F),
                new Bone("Cf2", JointTable.HAND_R, true).box(0.4F, 1.0F, 0.0F),
                new Bone("joint1", JointTable.ARM_L, true).box(-0.3F, 1.3F, 0.0F),
                new Bone("joint9", JointTable.HAND_L, true).box(-0.4F, 1.0F, 0.0F),
                new Bone("2", JointTable.LEG_R, true).box(0.1F, 0.5F, 0.0F),
                new Bone("21", JointTable.LEG_L, true).box(-0.1F, 0.5F, 0.0F));

        Bind bind = bind(input("STRESSTEST_SMT_Nahobino.ysm", bones));

        assertNull(bind.geometry().relaxation(), "nothing to relax: " + bind.warnings());
        assertPivot(0.3F, 1.3F, 0.0F, bind.pivots().byJoint().get(JointTable.ARM_R), "right shoulder");
        assertPivot(-0.3F, 1.3F, 0.0F, bind.pivots().byJoint().get(JointTable.ARM_L), "left shoulder");
        assertPivot(0.4F, 1.0F, 0.0F, bind.pivots().byJoint().get(JointTable.HAND_R), "right elbow");
        assertPivot(-0.4F, 1.0F, 0.0F, bind.pivots().byJoint().get(JointTable.HAND_L), "left elbow");
        assertPivot(0.1F, 0.5F, 0.0F, bind.pivots().byJoint().get(JointTable.LEG_R), "right knee");
    }

    /**
     * The rule's original intent, kept: {@code RightLeg2} beside {@code RightLeg} is a variant whose
     * bind geometry sits elsewhere (here a whole block above the base form's), so it must not move the
     * knee. The base form serves the joint, so the strict tier is enough and nothing is relaxed - and
     * a digit-suffixed name with no base form ({@code Tail1}) in the same model is still used.
     */
    @Test
    @DisplayName("a variant whose base form exists is still excluded, and its neighbour is not")
    void aVariantWhoseBaseFormExistsIsStillExcluded() {
        List<Bone> bones = List.of(
                new Bone("Torso", JointTable.TORSO, true).box(0.0F, 1.0F, 0.0F),
                new Bone("Chest", JointTable.CHEST, true).box(0.0F, 1.4F, 0.0F),
                new Bone("RightThigh", JointTable.THIGH_R, true).box(0.15F, 1.0F, 0.0F),
                new Bone("LeftThigh", JointTable.THIGH_L, true).box(-0.15F, 1.0F, 0.0F),
                new Bone("RightLeg", JointTable.LEG_R, true).box(0.15F, 0.9F, 0.0F),
                // The alternate form: the same joint, geometry a block away.
                new Bone("RightLeg2", JointTable.LEG_R, true).box(0.15F, 1.5F, 0.0F),
                // Digit-suffixed, but no "Tail" bone exists: an ordinary name, not a variant.
                new Bone("Tail1", JointTable.ARM_R, true).box(0.3F, 1.3F, 0.0F));

        Bind bind = bind(input("variant-and-neighbour.ysm", bones));

        assertNull(bind.geometry().relaxation(),
                "the base form serves the knee and Tail1 is not a variant, so nothing is relaxed: "
                        + bind.warnings());
        assertTrue(bind.warnings().isEmpty(), "a clean model must be silent: " + bind.warnings());

        assertPivot(0.15F, 0.9F, 0.0F, bind.pivots().byJoint().get(JointTable.LEG_R),
                "the knee is the top of the base form's geometry");
        for (Vector3f v : bind.geometry().byJoint().get(JointTable.LEG_R)) {
            assertTrue(v.y <= 1.0F,
                    "the variant's geometry (top y=1.5) must not be part of the joint's geometry: " + v);
        }
        assertPivot(0.3F, 1.3F, 0.0F, bind.pivots().byJoint().get(JointTable.ARM_R),
                "'Tail1' has no base form, so the shoulder is pivoted on the geometry it carries");
    }

    // ------------------------------------------------------------------
    // The safety net: no joint, and no model, is left without geometry
    // ------------------------------------------------------------------

    /**
     * A joint whose only geometry the alternate-form rule excludes: {@code RightArm} exists as a
     * locator with no cubes, the arm itself is on {@code RightArm2}. The joint must not be left empty
     * (null pivot = the reference biped's shoulder, the detachment this whole class exists to avoid):
     * the strict tier comes up empty, tier 1 pulls the variant back, and the relaxation is reported
     * with the model, the joint and the bone.
     */
    @Test
    @DisplayName("a joint emptied by the variant rule relaxes and warns")
    void aJointEmptiedByTheVariantRuleIsRelaxedAndReported() {
        List<Bone> bones = List.of(
                new Bone("Torso", JointTable.TORSO, true).box(0.0F, 1.0F, 0.0F),
                new Bone("Chest", JointTable.CHEST, true).box(0.0F, 1.4F, 0.0F),
                new Bone("Thigh_R", JointTable.THIGH_R, true).box(0.1F, 1.0F, 0.0F),
                new Bone("Thigh_L", JointTable.THIGH_L, true).box(-0.1F, 1.0F, 0.0F),
                new Bone("RightArm", JointTable.ARM_R, true),
                new Bone("RightArm2", JointTable.ARM_R, true).box(0.3F, 1.3F, 0.0F));

        Bind bind = bind(input("relaxed-locator.ysm", bones));

        assertPivot(0.3F, 1.3F, 0.0F, bind.pivots().byJoint().get(JointTable.ARM_R),
                "the shoulder must come from the model's own arm geometry, not from Epic Fight's biped");
        assertNotNull(bind.geometry().relaxation(), "the relaxation must be reported as data too");
        assertEquals(1, bind.geometry().relaxation().tierByJoint().size());
        assertEquals(List.of("RightArm2"),
                bind.geometry().relaxation().bonesByJoint().get(JointTable.ARM_R));

        assertEquals(1, bind.warnings().size(), "exactly one relaxation warning: " + bind.warnings());
        String warning = bind.warnings().get(0);
        assertTrue(warning.contains("relaxed-locator.ysm"), "names the model: " + warning);
        assertTrue(warning.contains(JointTable.nameOf(JointTable.ARM_R)), "names the joint: " + warning);
        assertTrue(warning.contains("RightArm2"), "names the bone it pulled back: " + warning);
        assertTrue(warning.contains("alternate-form"), "says which filter it gave up: " + warning);
    }

    /**
     * The audit of the other filters, on the same failure mode. A cape is excluded because its
     * geometry reaches past the body, and a held item because it reaches past the fist - but a joint
     * whose ONLY geometry is one of those still needs a pivot, and the reference biped's is worse than
     * the model's own cloak. Both relax one tier further, and the warning says so.
     */
    @Test
    @DisplayName("the accessory and held-item filters relax for a joint they emptied")
    void theAccessoryAndHeldItemFiltersRelaxToo() {
        List<Bone> bones = List.of(
                new Bone("Torso", JointTable.TORSO, true).box(0.0F, 1.0F, 0.0F),
                new Bone("Chest", JointTable.CHEST, true).box(0.0F, 1.4F, 0.0F),
                new Bone("Thigh_R", JointTable.THIGH_R, true).box(0.1F, 1.0F, 0.0F),
                new Bone("Thigh_L", JointTable.THIGH_L, true).box(-0.1F, 1.0F, 0.0F),
                new Bone("Cape", JointTable.ARM_L, true).box(-0.3F, 1.35F, 0.0F),
                new Bone("Sword", JointTable.HAND_L, true).box(-0.4F, 1.0F, 0.0F));

        Bind bind = bind(input("accessory-arms.ysm", bones));

        assertPivot(-0.3F, 1.35F, 0.0F, bind.pivots().byJoint().get(JointTable.ARM_L), "left shoulder");
        assertPivot(-0.4F, 1.0F, 0.0F, bind.pivots().byJoint().get(JointTable.HAND_L), "left elbow");
        assertNotNull(bind.geometry().relaxation());
        assertEquals(Set.of(JointTable.ARM_L, JointTable.HAND_L),
                bind.geometry().relaxation().tierByJoint().keySet());
        assertEquals(1, bind.warnings().size(), "one warning for the model: " + bind.warnings());
        assertTrue(bind.warnings().get(0).contains("accessory/held-item"),
                "the warning must say which filter was given up: " + bind.warnings().get(0));
    }

    /**
     * The last resort, and the reason it exists: a joint whose geometry is hidden in the default form
     * (the Yukikaze shipgirl's rigging) is normally excluded, because hidden geometry never renders.
     * When it is all a joint has, using it still beats a pivot in empty space, so tier 3 pulls it back
     * - and the warning names that filter too.
     */
    @Test
    @DisplayName("the hidden-bone filter is the last thing given up, and it is reported")
    void theHiddenBoneFilterIsRelaxedAsALastResort() {
        List<Bone> bones = List.of(
                new Bone("Torso", JointTable.TORSO, true).box(0.0F, 1.0F, 0.0F),
                new Bone("Chest", JointTable.CHEST, true).box(0.0F, 1.4F, 0.0F),
                new Bone("Thigh_R", JointTable.THIGH_R, true).box(0.1F, 1.0F, 0.0F),
                new Bone("Thigh_L", JointTable.THIGH_L, true).box(-0.1F, 1.0F, 0.0F),
                new Bone("Rigging", JointTable.ARM_R, true).box(0.3F, 1.3F, 0.0F));

        Bind bind = bind(input("hidden-rigging.ysm", bones, "Rigging"));

        assertEquals(new Vector3f(0.3F, 1.3F, 0.0F), bind.pivots().byJoint().get(JointTable.ARM_R),
                "hidden geometry beats no geometry: the joint still pivots on the model's own bones");
        assertNotNull(bind.geometry().relaxation());
        assertEquals(3, bind.geometry().relaxation().tierByJoint().get(JointTable.ARM_R),
                "the hidden-bone rule is the LAST one given up");
        assertEquals(1, bind.warnings().size(), "one warning for the model: " + bind.warnings());
        assertTrue(bind.warnings().get(0).contains("hidden-bone"),
                "the warning must say the hidden-bone filter was given up: " + bind.warnings().get(0));
    }

    /**
     * The state that used to be silent. Every pivot null is not a no-op in Epic Fight - it means "keep
     * the reference biped's joint" - so a model that yields no geometry at all must say so, naming the
     * model and what the filter saw. This is the guard that makes the {@code [bind] ...=null} line
     * impossible to reach quietly.
     */
    @Test
    @DisplayName("a model with no usable geometry warns instead of reporting null pivots silently")
    void aModelWithNoGeometryAtAllWarns() {
        Bind bind = bind(input("nothing-to-pivot-on.ysm", List.of(
                new Bone("Torso", JointTable.TORSO, true),
                new Bone("Chest", JointTable.CHEST, true))));

        assertTrue(bind.pivots().byJoint().isEmpty(), "no geometry means no geometry-derived pivot");
        assertEquals(1, bind.warnings().size(), "exactly one warning: " + bind.warnings());
        String warning = bind.warnings().get(0);
        assertTrue(warning.contains("nothing-to-pivot-on.ysm"), "names the model: " + warning);
        assertTrue(warning.contains("NO geometry-derived pivot"), "states the verdict: " + warning);
        assertTrue(warning.contains("bones=2"), "reports what the filter saw: " + warning);
    }

    /**
     * The production sink is the mod log, and the tests observe the same messages through their own
     * consumer. This pins that the sink handed to the real path is usable and does not depend on
     * anything a test JVM lacks (it is what {@code build} passes in).
     */
    @Test
    @DisplayName("the production warning sink exists and is callable")
    void theProductionWarningSinkIsWired() {
        assertNotNull(YsmBindArmature.warningSink());
        YsmBindArmature.warningSink().accept(
                "YSM-EF Compat: [bind] sink probe from YsmBindArmatureTest (expected, no action needed)");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** One bone of a fixture: its name, EF joint, mapping flag and the box its mesh part carries. */
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

        /**
         * Give this bone a box whose top ring sits at {@code topY}: the four corners at the top
         * average to (cx, topY, cz), which is what the segment-top pivot is read from.
         */
        Bone box(float cx, float topY, float cz) {
            return box(cx, topY, cz, 0.1F);
        }

        Bone box(float cx, float topY, float cz, float halfWidth) {
            for (int corner = 0; corner < 4; corner++) {
                float x = cx + ((corner & 1) == 0 ? -halfWidth : halfWidth);
                float z = cz + ((corner & 2) == 0 ? -halfWidth : halfWidth);
                vertices.add(new Vector3f(x, topY - 0.4F, z));
                vertices.add(new Vector3f(x, topY, z));
            }
            return this;
        }
    }

    /** The filter's input, built the way the runtime path builds it from the mesh. */
    private static YsmBindArmature.GeometryInput input(String modelId, List<Bone> bones, String... hidden) {
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
        return new YsmBindArmature.GeometryInput(modelId, names, joints, mapped, Set.of(hidden), parts);
    }

    /** What the two production calls produced, with every warning they emitted captured. */
    private record Bind(YsmBindArmature.GeometryData geometry, YsmBindArmature.BindPivots pivots,
                        List<String> warnings) {}

    private static Bind bind(YsmBindArmature.GeometryInput input) {
        List<String> warnings = new ArrayList<>();
        YsmBindArmature.GeometryData geometry = YsmBindArmature.collectGeometry(input, warnings::add);
        YsmBindArmature.BindPivots pivots = YsmBindArmature.computePivots(input, geometry, warnings::add);
        return new Bind(geometry, pivots, warnings);
    }

    /**
     * A pivot's expected position. Compared per component with a tolerance: the pivot is the mean of a
     * box's top ring, so the last float bit is accumulation noise rather than information.
     */
    private static void assertPivot(float x, float y, float z, Vector3f actual, String message) {
        assertNotNull(actual, message + " (no pivot at all)");
        assertEquals(x, actual.x, 1.0E-5F, message);
        assertEquals(y, actual.y, 1.0E-5F, message);
        assertEquals(z, actual.z, 1.0E-5F, message);
    }
}
