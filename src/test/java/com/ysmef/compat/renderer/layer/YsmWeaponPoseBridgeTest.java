package com.ysmef.compat.renderer.layer;

import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class YsmWeaponPoseBridgeTest {
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

    private static Armature armature(String name, Map<String, Integer> ids) {
        Map<String, Joint> joints = new LinkedHashMap<>();
        int jointCount = ids.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        ids.forEach((jointName, id) -> joints.put(jointName,
                new Joint(jointName, id, new OpenMatrix4f())));
        return new Armature(name, jointCount, joints.get("Root"), joints);
    }
}
