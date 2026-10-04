package com.ysmef.compat.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YsmWheelBoneTransformTest {
    @Test
    void positionChannelMovesTheBoneInsteadOfCancellingItself() {
        YSMGeoModel.Bone bone = new YSMGeoModel.Bone();
        bone.pivotX = 1.0f;
        bone.pivotY = 2.0f;
        bone.pivotZ = 3.0f;
        Matrix4f transform = new Matrix4f();

        YsmWheelBoneTransform.apply(transform, bone, 0.5f, -0.25f, 0.75f,
                0, 0, 0, 1, 1, 1);

        Vector3f actual = transform.transformPosition(new Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ));
        assertEquals(1.5f, actual.x, 1e-6f);
        assertEquals(1.75f, actual.y, 1e-6f);
        assertEquals(3.75f, actual.z, 1e-6f);
    }

    @Test
    void rotationKeepsTheOriginalPivotWhenPositionAlsoChanges() {
        YSMGeoModel.Bone bone = new YSMGeoModel.Bone();
        bone.pivotX = 1.0f;
        Matrix4f transform = new Matrix4f();

        YsmWheelBoneTransform.apply(transform, bone, 0.5f, 0, 0,
                0, 0, (float) Math.PI / 2, 1, 1, 1);

        Vector3f actual = transform.transformPosition(new Vector3f(1, 0, 0));
        assertEquals(1.5f, actual.x, 1e-6f);
        assertEquals(0.0f, actual.y, 1e-6f);
    }
}
