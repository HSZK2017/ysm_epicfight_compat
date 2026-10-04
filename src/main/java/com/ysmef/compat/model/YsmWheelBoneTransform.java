package com.ysmef.compat.model;

import org.joml.Matrix4f;

/** Applies one sampled YSM bone transform in the same order as YSM's renderer. */
final class YsmWheelBoneTransform {
    private YsmWheelBoneTransform() {}

    static void apply(Matrix4f target, YSMGeoModel.Bone bone,
                      float tx, float ty, float tz,
                      float rx, float ry, float rz,
                      float sx, float sy, float sz) {
        // Animation position translates the bone; it must not change the pivot
        // used after rotation. Using (pivot + translation) on both sides cancels
        // pure translation and displaces rotated descendants.
        target.translate(bone.pivotX + tx, bone.pivotY + ty, bone.pivotZ + tz);
        target.rotateZ(rz);
        target.rotateY(ry);
        target.rotateX(rx);
        target.scale(sx, sy, sz);
        target.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);
    }
}
