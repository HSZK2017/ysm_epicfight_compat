package com.ysmef.compat.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two model-aware rules of {@link YSMJointMapper}, on rigs small enough to read.
 *
 * <p>Both exist because this mod binds every vertex rigidly to one Epic Fight joint, and both were
 * written against shipped models whose names disagree with their geometry:
 *
 * <ul>
 *   <li><b>Garment.</b> Cloth that hangs from a {@code Chest}-named container belongs to the hips.
 *       The reference is the model's own <i>hip</i> (measured from its mapped thigh bones), not the
 *       height of whichever mapped ancestor the name walk stopped at: on the shipped models that
 *       ancestor was authored low and dragged breast plates, collars and cloaks - i.e. upper-body
 *       geometry - down onto the hips, where they stay behind while the chest and the arms (Epic
 *       Fight's {@code Shoulder_*} and {@code Head} hang from {@code Chest}) swing away.</li>
 *   <li><b>Side.</b> A limb's left/right side is where its geometry is. YSM rigs are authored by
 *       mirroring a template, and the copy's names are not always renamed: the shipped Nahobino's
 *       whole left foot is named {@code RightFoot3} and hung inside the left leg, so 4920 of its
 *       vertices followed the other leg - the reported "leg comes apart from the body".</li>
 * </ul>
 *
 * <p>Each rig below is the smallest one that tells the two answers apart, and every assertion is
 * written so that the rule it pins is the only reason it can pass.
 */
class JointMapperModelAwareRulesTest {

    /**
     * A rig 2 blocks tall with its hips at y = 1.0 and a thigh of 0.5 blocks: the allowance a piece
     * of cloth gets is half of that, so anything at or below y = 1.25 is lower-body cloth and
     * anything above it is chest geometry.
     *
     * <pre>
     *   Root
     *   +- AllBody [Torso]              pivot (0, 1.0)
     *   |  +- UpBody [Chest]            pivot (0, 1.2), cubes 1.10..1.55
     *   |     +- Breast [Chest]         cubes 1.50..1.60   (a mapped body part, at chest height)
     *   |     |  +- BreastPlate         cubes 1.52..1.62   (unmapped; the piece the rule is for)
     *   |     +- clothe                 cubes 1.20..1.35
     *   |        +- SkirtPanel          cubes 0.60..1.10   (hangs to the knees -> hips)
     *   |        +- HighSash            cubes 1.30..1.40   (at the ribs -> chest)
     *   +- LeftLeg [Thigh_L]            cubes 0.50..1.00
     *   |  +- LeftLowerLeg [Leg_L]      cubes 0.00..0.50
     *   +- RightLeg [Thigh_R]           cubes 0.50..1.00
     *      +- RightLowerLeg [Leg_R]     cubes 0.00..0.50
     * </pre>
     */
    private static final String GARMENT_RIG = rig(
            bone("Root", null, 0, 0),
            bone("AllBody", "Root", 0, 1.0, cube(-0.35, 0.95, -0.15, 0.7, 0.1, 0.3)),
            bone("UpBody", "AllBody", 0, 1.2),
            bone("Breast", "UpBody", 0, 1.5, cube(-0.2, 1.50, 0, 0.4, 0.1, 0.2)),
            bone("BreastPlate", "Breast", 0, 1.5, cube(-0.25, 1.52, 0, 0.5, 0.1, 0.25)),
            bone("clothe", "UpBody", 0, 1.0),
            bone("SkirtPanel", "clothe", 0, 1.0, cube(-0.3, 0.60, 0, 0.6, 0.5, 0.3)),
            bone("HighSash", "clothe", 0, 1.3, cube(-0.3, 1.30, 0, 0.6, 0.1, 0.3)),
            bone("LeftLeg", "AllBody", -0.12, 1.0, cube(-0.2, 0.50, 0, 0.16, 0.5, 0.16)),
            bone("LeftLowerLeg", "LeftLeg", -0.12, 0.5, cube(-0.2, 0.00, 0, 0.16, 0.5, 0.16)),
            bone("RightLeg", "AllBody", 0.12, 1.0, cube(0.04, 0.50, 0, 0.16, 0.5, 0.16)),
            bone("RightLowerLeg", "RightLeg", 0.12, 0.5, cube(0.04, 0.00, 0, 0.16, 0.5, 0.16)));

    @Test
    void theHipIsMeasuredFromTheModelsOwnThighs() {
        YSMGeoModel model = YSMGeoModel.parse(GARMENT_RIG);

        assertEquals(1.0F, YSMJointMapper.hipHeight(model), 0.02F,
                "the hip is the top of the mapped thigh geometry");
        assertEquals(0.5F, YSMJointMapper.thighLength(model), 0.02F,
                "and the thigh is the hip minus the top of the shin geometry");
    }

    /**
     * The regression this rule was changed for: a piece of upper-body geometry whose mapped
     * ancestor happens to sit at its own height. The ancestor-relative comparison the rule used
     * before cannot tell them apart - {@code BreastPlate} is only 0.02 above {@code Breast} - so it
     * put the plate on the hips while the chest, the head and both arms (all children of Epic
     * Fight's {@code Chest}) turned away from it.
     */
    @Test
    void chestGeometryStaysOnTheChestEvenWhenItsAncestorIsLow() {
        YSMGeoModel model = YSMGeoModel.parse(GARMENT_RIG);
        YSMGeoModel.Bone plate = model.bonesByName.get("BreastPlate");

        assertEquals(1.57F, YSMJointMapper.geometryHeight(plate, model), 0.02F, "the plate is chest-level");
        assertTrue(YSMJointMapper.geometryHeight(plate, model)
                        < YSMJointMapper.geometryHeight(model.bonesByName.get("Breast"), model) + 0.25F,
                "and it is closer to its own mapped ancestor than the old allowance, which is exactly"
                        + " why the ancestor-relative comparison moved it to the hips");
        assertEquals(8, YSMJointMapper.resolveJointId(plate, model),
                "so it must stay on Epic Fight's Chest");
    }

    @Test
    void clothThatHangsToTheHipsFollowsTheHips() {
        YSMGeoModel model = YSMGeoModel.parse(GARMENT_RIG);

        assertEquals(YSMJointMapper.JOINT_TORSO,
                YSMJointMapper.resolveJointId(model.bonesByName.get("SkirtPanel"), model),
                "a panel hanging to the knees is lower-body cloth");
        assertEquals(YSMJointMapper.JOINT_TORSO,
                YSMJointMapper.resolveJointId(model.bonesByName.get("clothe"), model),
                "and so is its container, which is where the name walk goes wrong");
    }

    /** The allowance is half a thigh above the hips, so a sash at the ribs is chest geometry. */
    @Test
    void theAllowanceIsHalfAThighAboveTheHips() {
        YSMGeoModel model = YSMGeoModel.parse(GARMENT_RIG);

        assertEquals(8, YSMJointMapper.resolveJointId(model.bonesByName.get("HighSash"), model),
                "a piece 0.3 above the hips is above the allowance (0.25) and belongs to the chest");
    }

    /** A bone that is itself a named body part is the author's word, whatever its height. */
    @Test
    void aDirectlyMappedBodyPartKeepsItsOwnJoint() {
        YSMGeoModel model = YSMGeoModel.parse(GARMENT_RIG);

        assertEquals(8, YSMJointMapper.resolveJointId(model.bonesByName.get("UpBody"), model),
                "UpBody is the chest, named as one");
        assertEquals(8, YSMJointMapper.resolveJointId(model.bonesByName.get("Breast"), model),
                "and a breast is chest geometry at any height");
    }

    /**
     * A rig whose variant form of the legs is authored 0.5 blocks lower. The variant bones are
     * alternate forms of the primary ones ({@code RightLeg2} beside {@code RightLeg}), so they must
     * not move the hip - on the shipped maid they did, and the hip came out a third of a block below
     * the model's real hips, which dragged the whole skirt back onto the chest.
     *
     * <p>With the variant included the hip would be 0.75 and the allowance 0.125, and the panel
     * below would land on the chest; with the variant excluded it is 1.0 and the panel is lower-body
     * cloth, which is the assertion.
     */
    @Test
    void anAlternateFormDoesNotMoveTheHip() {
        YSMGeoModel model = YSMGeoModel.parse(rig(
                bone("Root", null, 0, 0),
                bone("AllBody", "Root", 0, 1.0),
                bone("UpBody", "AllBody", 0, 1.2),
                bone("SkirtPanel", "UpBody", 0, 1.1, cube(-0.2, 1.05, 0, 0.4, 0.1, 0.2)),
                bone("LeftLeg", "AllBody", -0.12, 1.0, cube(-0.2, 0.50, 0, 0.16, 0.5, 0.16)),
                bone("LeftLowerLeg", "LeftLeg", -0.12, 0.5, cube(-0.2, 0.00, 0, 0.16, 0.5, 0.16)),
                bone("RightLeg", "AllBody", 0.12, 1.0, cube(0.04, 0.50, 0, 0.16, 0.5, 0.16)),
                bone("RightLowerLeg", "RightLeg", 0.12, 0.5, cube(0.04, 0.00, 0, 0.16, 0.5, 0.16)),
                bone("LeftLeg2", "AllBody", -0.12, 0.5, cube(-0.2, 0.00, 0, 0.16, 0.5, 0.16)),
                bone("RightLeg2", "AllBody", 0.12, 0.5, cube(0.04, 0.00, 0, 0.16, 0.5, 0.16))));

        assertEquals(1.0F, YSMJointMapper.hipHeight(model), 0.02F,
                "the variant leg bones must not pull the hip down to their own height");
        assertEquals(YSMJointMapper.JOINT_TORSO,
                YSMJointMapper.resolveJointId(model.bonesByName.get("SkirtPanel"), model),
                "so the panel at 1.10 (0.10 above the hips) stays lower-body cloth");
    }

    /** Without a thigh bone this mapper can name, the rule falls back to the ancestor comparison. */
    @Test
    void withoutThighBonesTheOlderDropRuleStillApplies() {
        YSMGeoModel model = YSMGeoModel.parse(rig(
                bone("Root", null, 0, 0),
                bone("UpBody", "Root", 0, 1.2),
                bone("Qunzi", "UpBody", 0, 1.2, cube(-0.3, 0.70, 0, 0.6, 0.4, 0.3))));

        assertTrue(Float.isNaN(YSMJointMapper.hipHeight(model)), "no thigh bone, no hip");
        assertEquals(YSMJointMapper.JOINT_TORSO,
                YSMJointMapper.resolveJointId(model.bonesByName.get("Qunzi"), model),
                "a garment well below the chest container is still lower-body cloth");
    }

    /**
     * The shipped case in miniature: the whole foot of the <b>left</b> leg is named
     * {@code RightFoot3} and hung inside the left leg's own chain, with its geometry on the left.
     *
     * <pre>
     *   LeftLeg [Thigh_L] -> LeftLowerLeg [Leg_L] -> RightFoot3  <- name says right, x = -0.15
     *   RightLeg [Thigh_R] -> RightLowerLeg [Leg_R] -> LeftFoot2 <- name says left,  x = +0.15
     * </pre>
     */
    private static final String MIRRORED_FEET_RIG = rig(
            bone("Root", null, 0, 0),
            bone("AllBody", "Root", 0, 1.0),
            bone("LeftLeg", "AllBody", -0.12, 1.0, cube(-0.2, 0.50, 0, 0.16, 0.5, 0.16)),
            bone("LeftLowerLeg", "LeftLeg", -0.12, 0.5, cube(-0.2, 0.00, 0, 0.16, 0.5, 0.16)),
            bone("RightFoot3", "LeftLowerLeg", -0.15, 0.05, cube(-0.22, 0.00, 0, 0.14, 0.05, 0.24)),
            bone("RightLeg", "AllBody", 0.12, 1.0, cube(0.04, 0.50, 0, 0.16, 0.5, 0.16)),
            bone("RightLowerLeg", "RightLeg", 0.12, 0.5, cube(0.04, 0.00, 0, 0.16, 0.5, 0.16)),
            bone("LeftFoot2", "RightLowerLeg", 0.15, 0.05, cube(0.08, 0.00, 0, 0.14, 0.05, 0.24)));

    @Test
    void aLimbFollowsTheSideItsGeometryIsOn() {
        YSMGeoModel model = YSMGeoModel.parse(MIRRORED_FEET_RIG);

        assertEquals(5, YSMJointMapper.resolveJointId(model.bonesByName.get("RightFoot3"), model),
                "the left foot's geometry must follow the left shin, whatever its name says");
        assertEquals(2, YSMJointMapper.resolveJointId(model.bonesByName.get("LeftFoot2"), model),
                "and the right foot's geometry the right shin");
        assertEquals(4, YSMJointMapper.resolveJointId(model.bonesByName.get("LeftLeg"), model),
                "while the legs themselves are untouched");
        assertEquals(1, YSMJointMapper.resolveJointId(model.bonesByName.get("RightLeg"), model));
    }

    /**
     * A bone whose geometry sits on the midline has no side to be wrong about. {@code LeftFoot} is
     * named as the left foot and carries its geometry 0.01 blocks to the <i>other</i> side of the
     * centre - far inside the model's limb scale (0.12 for these legs, so the guard is 0.06) -
     * geometry that close to the centre line is a sash, a tail, a crossing strap or a sole, and
     * flipping it would be the rule reading noise.
     */
    @Test
    void geometryOnTheMidlineIsNotJudgedBySide() {
        YSMGeoModel model = YSMGeoModel.parse(rig(
                bone("Root", null, 0, 0),
                bone("AllBody", "Root", 0, 1.0),
                bone("LeftLeg", "AllBody", -0.12, 1.0, cube(-0.2, 0.50, 0, 0.16, 0.5, 0.16)),
                bone("LeftLowerLeg", "LeftLeg", -0.12, 0.5, cube(-0.2, 0.00, 0, 0.16, 0.5, 0.16)),
                bone("RightLeg", "AllBody", 0.12, 1.0, cube(0.04, 0.50, 0, 0.16, 0.5, 0.16)),
                bone("RightLowerLeg", "RightLeg", 0.12, 0.5, cube(0.04, 0.00, 0, 0.16, 0.5, 0.16)),
                bone("LeftFoot", "LeftLowerLeg", 0, 0.3, cube(0.005, 0.25, 0, 0.01, 0.1, 0.02))));

        assertEquals(0.01F, YSMJointMapper.geometryLateral(model.bonesByName.get("LeftFoot"), model), 0.002F,
                "the foot's geometry is on the other side of the centre from its own name");
        assertEquals(5, YSMJointMapper.resolveJointId(model.bonesByName.get("LeftFoot"), model),
                "but it is too close to the midline to be judged, so it keeps the joint its name gives it");
    }

    /**
     * A model that is mirrored <i>as a whole</i> - every left/right name on the geometry of the
     * other side - is a convention, not a mistake, and the rule must leave it alone: the model's own
     * side convention is measured from its own skeleton, so nothing here is "on the wrong side".
     * (Getting this wrong would swap the arms of every second model in the corpus.)
     */
    @Test
    void aGloballyMirroredModelIsLeftAlone() {
        YSMGeoModel model = YSMGeoModel.parse(rig(
                bone("Root", null, 0, 0),
                bone("AllBody", "Root", 0, 1.0),
                bone("LeftLeg", "AllBody", 0.12, 1.0, cube(0.04, 0.50, 0, 0.16, 0.5, 0.16)),
                bone("LeftLowerLeg", "LeftLeg", 0.12, 0.5, cube(0.04, 0.00, 0, 0.16, 0.5, 0.16)),
                bone("LeftFoot", "LeftLowerLeg", 0.15, 0.05, cube(0.08, 0.00, 0, 0.14, 0.05, 0.24)),
                bone("RightLeg", "AllBody", -0.12, 1.0, cube(-0.2, 0.50, 0, 0.16, 0.5, 0.16)),
                bone("RightLowerLeg", "RightLeg", -0.12, 0.5, cube(-0.2, 0.00, 0, 0.16, 0.5, 0.16)),
                bone("RightFoot", "RightLowerLeg", -0.15, 0.05, cube(-0.22, 0.00, 0, 0.14, 0.05, 0.24))));

        assertEquals(-1.0F, YSMJointMapper.sideConvention(model),
                "the model's own right-hand geometry is on the negative side of the model");
        assertEquals(5, YSMJointMapper.resolveJointId(model.bonesByName.get("LeftFoot"), model),
                "so its left-named foot keeps the left joint");
        assertEquals(2, YSMJointMapper.resolveJointId(model.bonesByName.get("RightFoot"), model));
    }

    /** The two rules must not collide: a side correction never lands on the chest and back. */
    @Test
    void aSideCorrectedLimbDoesNotBecomeCloth() {
        YSMGeoModel model = YSMGeoModel.parse(MIRRORED_FEET_RIG);

        for (String name : new String[]{"RightFoot3", "LeftFoot2"}) {
            int joint = YSMJointMapper.resolveJointId(model.bonesByName.get(name), model);
            assertNotEquals(YSMJointMapper.JOINT_CHEST, joint, name);
            assertNotEquals(YSMJointMapper.JOINT_TORSO, joint, name);
        }
    }

    /**
     * A container whose contents cross the body is not corrected: the shipped Jane Doe's left
     * forearm bone carries an arrow aimed across her chest, so what hangs below the forearm sits on
     * the right while the forearm itself is on the left. Reading that as "this limb is on the right"
     * would move the <i>arm</i> to the wrong hand to make room for a prop, so no side is claimed and
     * the name's answer stands.
     */
    @Test
    void aContainerHoldingSomethingAcrossTheBodyIsNotCorrected() {
        YSMGeoModel model = YSMGeoModel.parse(rig(
                bone("Root", null, 0, 0),
                bone("AllBody", "Root", 0, 1.0),
                bone("LeftLeg", "AllBody", -0.12, 1.0, cube(-0.2, 0.50, 0, 0.16, 0.5, 0.16)),
                bone("LeftLowerLeg", "LeftLeg", -0.12, 0.5, cube(-0.2, 0.00, 0, 0.16, 0.5, 0.16)),
                bone("RightLeg", "AllBody", 0.12, 1.0, cube(0.04, 0.50, 0, 0.16, 0.5, 0.16)),
                bone("RightLowerLeg", "RightLeg", 0.12, 0.5, cube(0.04, 0.00, 0, 0.16, 0.5, 0.16)),
                // the forearm bone itself carries nothing; the arm is on the left, the arrow crosses over
                bone("LeftForeArm", "LeftLowerLeg", -0.15, 0.1),
                bone("ForeArmBare", "LeftForeArm", -0.15, 0.1, cube(-0.22, 0.05, 0, 0.14, 0.05, 0.14)),
                bone("Arrow", "LeftForeArm", 0.4, 0.1,
                        cube(0.39, 0.05, 0, 0.02, 0.02, 0.2),
                        cube(0.39, 0.05, 0.2, 0.02, 0.02, 0.2),
                        cube(0.39, 0.05, 0.4, 0.02, 0.02, 0.2))));

        assertEquals(17, YSMJointMapper.resolveJointId(model.bonesByName.get("LeftForeArm"), model),
                "the forearm keeps the left joint its name gives it, arrow or no arrow");
        assertEquals(17, YSMJointMapper.resolveJointId(model.bonesByName.get("ForeArmBare"), model),
                "and so does the arm geometry it carries");
    }

    // ------------------------------------------------------------------
    // A tiny rig builder: model-space blocks in, Bedrock geometry JSON out
    // ------------------------------------------------------------------

    private record Bone(String name, String parent, float pivotX, float pivotY, List<float[]> cubes) {}

    /**
     * A cube by its model-space minimum corner and its size, both in blocks. The parameters are
     * doubles so the fixtures can be written in plain decimal, and are narrowed here once.
     */
    private static float[] cube(double minX, double minY, double minZ,
                                double sizeX, double sizeY, double sizeZ) {
        return new float[]{(float) minX, (float) minY, (float) minZ,
                (float) sizeX, (float) sizeY, (float) sizeZ};
    }

    private static Bone bone(String name, String parent, double pivotX, double pivotY, float[]... cubes) {
        return new Bone(name, parent, (float) pivotX, (float) pivotY, List.of(cubes));
    }

    /**
     * The Bedrock geometry JSON for a rig. The conversion is the one the mod's parser uses and this
     * builder inverts: {@code pivot_x} and cube {@code origin_x} are negated into model space, and
     * cube origins are in 1/16 block units.
     */
    private static String rig(Bone... bones) {
        List<String> parts = new ArrayList<>();
        for (Bone b : bones) {
            StringBuilder sb = new StringBuilder("{\"name\":\"").append(b.name()).append("\"");
            if (b.parent() != null) {
                sb.append(",\"parent\":\"").append(b.parent()).append("\"");
            }
            sb.append(String.format(Locale.ROOT, ",\"pivot\":[%s,%s,0]",
                    num(-b.pivotX() * 16.0F), num(b.pivotY() * 16.0F)));
            if (!b.cubes().isEmpty()) {
                sb.append(",\"cubes\":[");
                for (int i = 0; i < b.cubes().size(); i++) {
                    float[] c = b.cubes().get(i);
                    // model-space min x = -(origin_x + size_x)/16, so origin_x = -min_x*16 - size_x_px
                    float originX = -c[0] * 16.0F - c[3] * 16.0F;
                    float originY = c[1] * 16.0F;
                    float originZ = c[2] * 16.0F;
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(String.format(Locale.ROOT,
                            "{\"origin\":[%s,%s,%s],\"size\":[%s,%s,%s],\"uv\":[0,0]}",
                            num(originX), num(originY), num(originZ),
                            num(c[3] * 16.0F), num(c[4] * 16.0F), num(c[5] * 16.0F)));
                }
                sb.append(']');
            }
            sb.append('}');
            parts.add(sb.toString());
        }
        return "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":"
                + "{\"texture_width\":64,\"texture_height\":64},\"bones\":["
                + String.join(",", parts) + "]}]}";
    }

    private static String num(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
