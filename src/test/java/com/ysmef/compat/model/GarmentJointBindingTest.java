package com.ysmef.compat.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The maid skirt must follow the hips, not the chest.
 *
 * <p>This is the defect the whole visual report came down to, and it is a rigging defect rather
 * than a dynamics one - which is why six rounds of force corrections could not touch it. Every one
 * of the model's twenty-four skirt panels descends from {@code clothe -> UpBody}, and
 * {@code UpBody} is the chest:
 *
 * <pre>
 *   FM1 -> FM -> FrontClothe -> clothe -> UpBody [Chest]
 *   RM  -> RightClothe -> clothe -> UpBody [Chest]
 * </pre>
 *
 * <p>So the skirt was skinned to the upper body. YSM does not mind - it animates {@code clothe}
 * itself, so the skirt follows the author's animation - but this mod binds every vertex rigidly to
 * one Epic Fight joint, and once the container's animation is gone the skirt rides the chest. Epic
 * Fight's attacks twist the chest hard, so a skirt bolted to it is thrown off the hips on every
 * swing, and the physics is then asked to hold that pose: the pose itself is the wrong body part's.
 *
 * <p>The rule that fixes it is geometric - cloth whose geometry sits at or below the hips belongs
 * to the lower body, with the hip height read from the model's own mapped thigh bones - and these
 * tests are that rule, on the model that showed it and on the cases that must not change.
 */
class GarmentJointBindingTest {

    private static final String MODEL = "/golden/maid/models/main.json";

    /** Every panel of the skirt ends up on the Torso, not the Chest. */
    @Test
    void theSkirtPanelsBindToTheHips() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));
        String[] panels = {
                "RM", "RM2", "RM3", "RF", "RF2", "RF3", "RB", "RB2", "RB3",
                "BL", "BL2", "BL3", "BM", "BM2", "BM3", "BR", "BR2", "BR3",
                "FL1", "FL2", "FM1", "FM2"};

        for (String name : panels) {
            YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
            assertNotNull(bone, "the fixture has no bone " + name);
            assertEquals(YSMJointMapper.JOINT_TORSO, YSMJointMapper.resolveJointId(bone, geometry),
                    name + " hangs at the waist and must follow the hips; the name walk alone puts"
                            + " it on the chest through 'clothe <- UpBody'");
        }
    }

    /** And the containers themselves, which is where the name walk goes wrong. */
    @Test
    void theGarmentContainersBindToTheHipsToo() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));

        for (String name : new String[]{"FrontClothe", "BackClothe", "RightClothe"}) {
            YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
            if (bone == null) {
                continue;
            }
            assertEquals(YSMJointMapper.JOINT_TORSO, YSMJointMapper.resolveJointId(bone, geometry),
                    name + " is the garment's own container and hangs at the waist");
        }
    }

    /**
     * The rule must not touch anything that is genuinely on the chest: a collar, a cape, a
     * backpack. Those sit above the hips, and moving them would break them instead.
     */
    @Test
    void chestLevelGeometryStaysOnTheChest() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));

        for (String name : new String[]{"UpBody", "UpperBody", "Elytra", "ElytraLocator"}) {
            YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
            if (bone == null) {
                continue;
            }
            assertEquals(YSMJointMapper.JOINT_CHEST, YSMJointMapper.resolveJointId(bone, geometry),
                    name + " is chest-level geometry and must stay on the chest");
        }
    }

    /** The head, the arms and the legs are unaffected: the rule only ever rewrites the chest. */
    @Test
    void otherBodyPartsAreUnaffected() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));

        assertEquals(9, YSMJointMapper.resolveJointId(
                geometry.bonesByName.get("Head"), geometry), "the head stays the head");
        assertEquals(11, YSMJointMapper.resolveJointId(
                geometry.bonesByName.get("RightArm"), geometry), "the arm stays the arm");
        assertEquals(1, YSMJointMapper.resolveJointId(
                geometry.bonesByName.get("RightLeg"), geometry), "the thigh stays the thigh");
    }

    /**
     * The rule reads the drop between a piece of cloth and the body part it hangs from, so a model
     * whose chest sits elsewhere gets a different threshold and the rule still holds. A height
     * threshold could not work here: the skirt's upper panels pivot at the waist and hang only a
     * hand's width, so their geometry is <i>above</i> the hip while still being skirt.
     */
    @Test
    void theDropIsMeasuredAgainstTheModelsOwnAnchor() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));
        YSMGeoModel.Bone panel = geometry.bonesByName.get("FM1");
        YSMGeoModel.Bone anchor = YSMJointMapper.mappedAncestor(panel);

        assertNotNull(anchor, "the skirt descends from a mapped body part");
        assertEquals("UpBody", anchor.name,
                "and that body part is the chest, which is why the name walk alone is wrong");
        assertTrue(YSMJointMapper.centroidHeight(panel) < YSMJointMapper.centroidHeight(anchor),
                "the skirt hangs below the body part it inherited its joint from");
    }

    /** Without a model there is nothing to judge by, so the plain name walk is kept. */
    @Test
    void withoutAModelTheNameWalkIsKept() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));

        assertEquals(YSMJointMapper.JOINT_CHEST,
                YSMJointMapper.resolveJointId(geometry.bonesByName.get("FM1"), null),
                "with no model to measure, the previous behaviour is the only one available");
    }

    /**
     * The joint the <b>mesh</b> bakes onto a bone's vertices must be the joint the <b>runtime bone
     * table</b> carries for that bone.
     *
     * <p>These are two different readers of one decision, and they were two decisions. The mesh
     * bakes each vertex's skin joint through {@link EFMeshJsonWriter#bakedJointId} (the name walk
     * alone, before this fix) while the runtime table - the {@code "joint"} field, written next to
     * the bones in the same conversion - uses the model-aware
     * {@link YSMJointMapper#resolveJointId(YSMGeoModel.Bone, YSMGeoModel)} (the name walk plus the
     * garment rule). On the shipped maid that disagreement covered 56 bones and 1800 of its 11283
     * vertices, 49 of them simulated segments: the geometry was <i>drawn</i> on Epic Fight's Chest
     * joint while the physics <i>followed</i> the Torso, so every cloth piece rode the twisting
     * chest while being simulated against the hips. Both the drawn frame and the simulated frame
     * have to be the same body part, and this is the assertion that keeps them so.
     *
     * <p>It is a regression guard rather than a tautology: it compares the two call sites' results
     * for real bones of the maid rig, so putting either side back on its own is a red test - which
     * is how the defect got in.
     */
    @Test
    void theMeshBakeAndTheRuntimeTableAgreeOnTheJoint() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));

        // Cloth that hangs at the waist: the rule redirects it, and both sides must say Torso.
        String[] cloth = {
                "FM1", "FM2", "FL1", "FL2", "RM", "RM2", "RM3", "RB", "RB2", "RB3",
                "BL", "BL2", "BM", "BM2", "BR", "BR2", "FrontClothe", "BackClothe", "RightClothe"};
        int checked = 0;
        for (String name : cloth) {
            YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
            if (bone == null) {
                continue;
            }
            checked++;
            int baked = EFMeshJsonWriter.bakedJointId(bone, geometry);
            int runtime = YSMJointMapper.resolveJointId(bone, geometry);
            assertEquals(runtime, baked, name + ": the mesh bakes joint " + baked
                    + " while the runtime bone table carries joint " + runtime
                    + "; the geometry would be drawn in one body part's frame and simulated in another's");
            assertEquals(YSMJointMapper.JOINT_TORSO, baked,
                    name + " hangs at the waist, so both the mesh and the runtime table must put it on the hips");
        }
        assertTrue(checked >= 15, "the fixture lost its cloth bones; only " + checked + " were found");

        // And the bones the rule must not touch agree on the Chest, on both sides.
        for (String name : new String[]{"UpBody", "UpperBody", "Elytra"}) {
            YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
            if (bone == null) {
                continue;
            }
            assertEquals(YSMJointMapper.JOINT_CHEST, EFMeshJsonWriter.bakedJointId(bone, geometry),
                    name + " is chest-level geometry and must stay on the chest in the baked mesh too");
            assertEquals(EFMeshJsonWriter.bakedJointId(bone, geometry),
                    YSMJointMapper.resolveJointId(bone, geometry),
                    name + " must be the same joint on both sides");
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = GarmentJointBindingTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
