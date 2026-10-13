package com.ysmef.compat.renderer.layer;

import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class YsmWeaponPoseBridgeTest {
    @Test
    void restPoseWeaponAnchorMovesWithTheModelInsteadOfStayingAtReferenceHeight() {
        Armature source = posedArmature("reference", 1.0F);
        Armature small = posedArmature("small", 0.45F);
        Armature large = posedArmature("large", 1.25F);
        Object entity = new Object();
        Object patch = new Object();
        OpenMatrix4f[] sourcePoses = source.getPoseMatrices();

        YsmWeaponPoseBridge.begin(entity, patch);
        try {
            YsmWeaponPoseBridge.capture(entity, source, sourcePoses, small);
            OpenMatrix4f[] smallItemPoses = YsmWeaponPoseBridge.posesFor(entity, patch, source, sourcePoses);
            assertEquals(0.45F, smallItemPoses[1].m31, 0.0001F);
            assertEquals(1.0F, sourcePoses[1].m31, 0.0001F);

            YsmWeaponPoseBridge.capture(entity, source, sourcePoses, large);
            OpenMatrix4f[] largeItemPoses = YsmWeaponPoseBridge.posesFor(entity, patch, source, sourcePoses);
            assertEquals(1.25F, largeItemPoses[1].m31, 0.0001F);
            assertEquals(0.45F, smallItemPoses[1].m31, 0.0001F,
                    "a later model must not mutate the previous entity's item pose");
        } finally {
            YsmWeaponPoseBridge.end();
        }
    }

    @Test
    void mapsBothHandsByJointNameAndPreservesThePatchArray() {
        Armature source = armature("patch", Map.of(
                "Root", 0, "Tool_R", 6, "Hand_R", 2, "Tool_L", 3, "Hand_L", 1));
        Armature rebound = armature("model", Map.of(
                "Root", 0, "Tool_R", 1, "Hand_R", 3, "Tool_L", 5, "Hand_L", 7));
        OpenMatrix4f[] sourcePoses = source.getPoseMatrices();
        sourcePoses[6].m30 = 0.4F;
        rebound.getPoseMatrices()[1].m30 = 1.8F;
        rebound.getPoseMatrices()[3].m30 = 1.6F;
        rebound.getPoseMatrices()[5].m30 = -1.8F;
        rebound.getPoseMatrices()[7].m30 = -1.6F;

        Object entity = new Object();
        Object patch = new Object();
        YsmWeaponPoseBridge.begin(entity, patch);
        try {
            YsmWeaponPoseBridge.capture(entity, source, sourcePoses, rebound);
            OpenMatrix4f[] itemPoses = YsmWeaponPoseBridge.posesFor(entity, patch, source, sourcePoses);
            assertNotSame(sourcePoses, itemPoses);
            assertEquals(1.8F, itemPoses[6].m30);
            assertEquals(1.6F, itemPoses[2].m30);
            assertEquals(-1.8F, itemPoses[3].m30);
            assertEquals(-1.6F, itemPoses[1].m30);
            assertSame(sourcePoses[0], itemPoses[0], "the non-hand joints stay with EF's patch");
            assertEquals(0.4F, sourcePoses[6].m30, "the patch pose must never be changed");

            rebound.getPoseMatrices()[1].m30 = 9.0F;
            assertEquals(1.8F, YsmWeaponPoseBridge.posesFor(entity, patch, source, sourcePoses)[6].m30,
                    "another entity can re-pose the shared model armature before the item layer runs");
        } finally {
            YsmWeaponPoseBridge.end();
        }
        assertSame(sourcePoses, YsmWeaponPoseBridge.posesFor(entity, patch, source, sourcePoses));
    }

    @Test
    void rejectsAnotherEntityPatchOrPoseAndRestoresOuterNestedRender() {
        Armature source = armature("patch", Map.of("Root", 0, "Tool_R", 1));
        Armature rebound = armature("model", Map.of("Root", 0, "Tool_R", 1));
        rebound.getPoseMatrices()[1].m31 = 2.0F;
        OpenMatrix4f[] poses = source.getPoseMatrices();
        Object entity = new Object();
        Object patch = new Object();
        YsmWeaponPoseBridge.begin(entity, patch);
        try {
            YsmWeaponPoseBridge.capture(entity, source, poses, rebound);
            assertSame(poses, YsmWeaponPoseBridge.posesFor(new Object(), patch, source, poses));
            assertSame(poses, YsmWeaponPoseBridge.posesFor(entity, new Object(), source, poses));
            OpenMatrix4f[] unrelatedPoseArray = poses.clone();
            assertSame(unrelatedPoseArray,
                    YsmWeaponPoseBridge.posesFor(entity, patch, source, unrelatedPoseArray));

            YsmWeaponPoseBridge.begin(new Object(), new Object());
            try {
                assertSame(poses, YsmWeaponPoseBridge.posesFor(entity, patch, source, poses));
            } finally {
                YsmWeaponPoseBridge.end();
            }
            assertEquals(2.0F, YsmWeaponPoseBridge.posesFor(entity, patch, source, poses)[1].m31);
        } finally {
            YsmWeaponPoseBridge.end();
        }
    }

    @Test
    void missingModelGripLeavesWeaponOnEpicFightJoint() {
        Armature source = armature("patch", Map.of("Root", 0, "Tool_R", 1));
        Armature rebound = armature("model", Map.of("Root", 0));
        Object entity = new Object();
        Object patch = new Object();
        OpenMatrix4f[] poses = source.getPoseMatrices();
        YsmWeaponPoseBridge.begin(entity, patch);
        try {
            YsmWeaponPoseBridge.capture(entity, source, poses, rebound);
            assertSame(poses, YsmWeaponPoseBridge.posesFor(entity, patch, source, poses));
        } finally {
            YsmWeaponPoseBridge.end();
        }
    }

    @Test
    void addonReadingPatchArmatureAgainOnlySeesAnchoredPosesInsideItemLayer() {
        Armature source = posedArmature("patch", 1.0F);
        Armature rebound = posedArmature("model", 0.45F);
        Armature unrelated = posedArmature("other player", 1.7F);
        Object entity = new Object();
        Object patch = new Object();
        OpenMatrix4f[] sourcePoses = source.getPoseMatrices();

        YsmWeaponPoseBridge.begin(entity, patch);
        try {
            YsmWeaponPoseBridge.capture(entity, source, sourcePoses, rebound);
            assertSame(sourcePoses, YsmWeaponPoseBridge.posesForCurrentItem(source, sourcePoses));
            boolean entered = YsmWeaponPoseBridge.beginItemLayer(entity, patch, source);
            try {
                // Several addons call setPose immediately before drawing their mesh.
                source.setPose(new Pose());
                OpenMatrix4f[] fresh = YsmWeaponPoseBridge.posesForCurrentItem(source, source.getPoseMatrices());
                assertNotSame(sourcePoses, fresh);
                assertEquals(0.45F, fresh[1].m31, 0.0001F);
                assertEquals(1.0F, sourcePoses[1].m31, 0.0001F);
                assertSame(unrelated.getPoseMatrices(),
                        YsmWeaponPoseBridge.posesForCurrentItem(unrelated, unrelated.getPoseMatrices()));
                assertEquals(sourcePoses[0].m31, fresh[0].m31, 0.0001F);
            } finally {
                YsmWeaponPoseBridge.endItemLayer(entered);
            }
            assertSame(sourcePoses, YsmWeaponPoseBridge.posesForCurrentItem(source, sourcePoses));
        } finally {
            YsmWeaponPoseBridge.end();
        }
    }

    @Test
    void itemLayerScopeRejectsOtherPatchAndRestoresOuterRender() {
        Armature source = posedArmature("patch", 1.0F);
        Armature rebound = posedArmature("model", 0.55F);
        Object entity = new Object();
        Object patch = new Object();
        OpenMatrix4f[] poses = source.getPoseMatrices();
        YsmWeaponPoseBridge.begin(entity, patch);
        try {
            YsmWeaponPoseBridge.capture(entity, source, poses, rebound);
            assertEquals(false, YsmWeaponPoseBridge.beginItemLayer(entity, new Object(), source));
            YsmWeaponPoseBridge.endItemLayer(false);
            assertSame(poses, YsmWeaponPoseBridge.posesForCurrentItem(source, poses));
            boolean outer = YsmWeaponPoseBridge.beginItemLayer(entity, patch, source);
            try {
                YsmWeaponPoseBridge.begin(new Object(), new Object());
                try {
                    assertSame(poses, YsmWeaponPoseBridge.posesForCurrentItem(source, poses));
                } finally {
                    YsmWeaponPoseBridge.end();
                }
                assertEquals(0.55F, YsmWeaponPoseBridge.posesForCurrentItem(source, poses)[1].m31, 0.0001F);
            } finally {
                YsmWeaponPoseBridge.endItemLayer(outer);
            }
        } finally {
            YsmWeaponPoseBridge.end();
        }
    }

    @Test
    void addonWeaponBonesFollowNearestModelJointWithoutLosingOwnAnimation() {
        Joint sourceRoot = new Joint("Root", 0, new OpenMatrix4f());
        Joint sourceChest = new Joint("Chest", 1, new OpenMatrix4f().translate(0, 1.0F, 0));
        Joint sheath = new Joint("Sheath", 2, new OpenMatrix4f().translate(0, 0.2F, 0));
        Joint sourceTool = new Joint("Tool_R", 3, new OpenMatrix4f().translate(0.3F, 0, 0));
        sourceRoot.addSubJoints(sourceChest);
        sourceChest.addSubJoints(sheath, sourceTool);
        Armature source = new Armature("addon", 4, sourceRoot,
                Map.of("Root", sourceRoot, "Chest", sourceChest, "Sheath", sheath, "Tool_R", sourceTool));
        source.setPose(new Pose());

        Joint modelRoot = new Joint("Root", 0, new OpenMatrix4f());
        Joint modelChest = new Joint("Chest", 1, new OpenMatrix4f().translate(0, 0.45F, 0));
        Joint modelTool = new Joint("Tool_R", 2, new OpenMatrix4f().translate(0.2F, 0, 0));
        modelRoot.addSubJoints(modelChest);
        modelChest.addSubJoints(modelTool);
        Armature rebound = new Armature("model", 3, modelRoot,
                Map.of("Root", modelRoot, "Chest", modelChest, "Tool_R", modelTool));
        rebound.setPose(new Pose());

        Object entity = new Object();
        Object patch = new Object();
        OpenMatrix4f[] poses = source.getPoseMatrices();
        YsmWeaponPoseBridge.begin(entity, patch);
        try {
            YsmWeaponPoseBridge.capture(entity, source, poses, rebound);
            boolean entered = YsmWeaponPoseBridge.beginItemLayer(entity, patch, source);
            try {
                OpenMatrix4f[] corrected = YsmWeaponPoseBridge.posesForCurrentItem(source, poses);
                assertEquals(0.45F, corrected[1].m31, 0.0001F);
                assertEquals(0.65F, corrected[2].m31, 0.0001F,
                        "the sheath keeps its own offset below the model's chest");
                assertEquals(0.2F, corrected[3].m30, 0.0001F);
                assertEquals(1.2F, poses[2].m31, 0.0001F);
            } finally {
                YsmWeaponPoseBridge.endItemLayer(entered);
            }
        } finally {
            YsmWeaponPoseBridge.end();
        }
    }

    private static Armature armature(String name, Map<String, Integer> ids) {
        Map<String, Joint> joints = new LinkedHashMap<>();
        int jointCount = ids.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        ids.forEach((jointName, id) -> joints.put(jointName,
                new Joint(jointName, id, new OpenMatrix4f())));
        return new Armature(name, jointCount, joints.get("Root"), joints);
    }

    private static Armature posedArmature(String name, float toolHeight) {
        Joint root = new Joint("Root", 0, new OpenMatrix4f());
        Joint tool = new Joint("Tool_R", 1, new OpenMatrix4f().translate(0.2F, toolHeight, 0.0F));
        root.addSubJoints(tool);
        Armature armature = new Armature(name, 2, root, Map.of("Root", root, "Tool_R", tool));
        armature.setPose(new Pose());
        return armature;
    }
}
