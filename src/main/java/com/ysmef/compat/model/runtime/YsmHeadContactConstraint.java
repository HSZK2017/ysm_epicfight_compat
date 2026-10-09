package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.JointTable;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/** Keeps a broad piece resting on the skull with the head pose, rather than swinging it as hair. */
final class YsmHeadContactConstraint {

    /** The mesh has separate vertices for two surfaces that touch; allow their small authored gap. */
    private static final float MAX_CONTACT_GAP = 0.035F;
    private static final float MAX_GAP_TO_HEAD_WIDTH = 0.12F;

    /** The contact patch must reach both sides of the skull, not just a strand's root on one side. */
    private static final float MIN_CONTACT_HALF_WIDTH = 0.25F;
    private static final float MIN_HEAD_WIDTH = 0.02F;

    /** A few touching root vertices cannot make a long lock into a scalp shell. */
    private static final float MIN_SKULL_OCCUPANCY = 0.10F;
    private static final float SKULL_ENVELOPE_SLACK = 0.02F;

    private YsmHeadContactConstraint() {}

    static boolean holds(YSMRuntimeModel.BoneRt[] bones, int boneIndex,
                         Map<Integer, List<Vector3f>> vertices) {
        if (bones == null || vertices == null || boneIndex < 0 || boneIndex >= bones.length
                || bones[boneIndex] == null || bones[boneIndex].joint != JointTable.HEAD) {
            return false;
        }
        List<Vector3f> own = vertices.get(boneIndex);
        if (own == null || own.size() < 4) {
            return false;
        }

        // A head-joint ancestor with its own geometry is the skull. A geometry-bearing hair
        // container may sit between it and this bone, so the first ancestor with geometry is not
        // necessarily the head. A mapped ancestor on another joint ends this head attachment.
        List<Vector3f> skull = null;
        int at = bones[boneIndex].parent;
        for (int guard = 0; at >= 0 && at < bones.length && guard++ < bones.length;) {
            YSMRuntimeModel.BoneRt ancestor = bones[at];
            if (ancestor == null) {
                break;
            }
            if (ancestor.mapped) {
                if (ancestor.joint != JointTable.HEAD) {
                    break;
                }
                List<Vector3f> candidate = vertices.get(at);
                if (candidate != null && candidate.size() >= 4) {
                    skull = candidate;
                    break;
                }
            }
            at = ancestor.parent;
        }
        return restsAcrossSkull(own, skull);
    }

    /** A shell touches near both left and right sides of the head; a hanging lock has one root. */
    static boolean restsAcrossSkull(List<Vector3f> own, List<Vector3f> skull) {
        if (own == null || skull == null || own.size() < 4 || skull.size() < 4) {
            return false;
        }
        float left = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float lowY = Float.POSITIVE_INFINITY;
        float highY = Float.NEGATIVE_INFINITY;
        float nearZ = Float.POSITIVE_INFINITY;
        float farZ = Float.NEGATIVE_INFINITY;
        for (Vector3f vertex : skull) {
            if (vertex != null && YsmDynamicBoneSolver.isFinite(vertex)) {
                left = Math.min(left, vertex.x);
                right = Math.max(right, vertex.x);
                lowY = Math.min(lowY, vertex.y);
                highY = Math.max(highY, vertex.y);
                nearZ = Math.min(nearZ, vertex.z);
                farZ = Math.max(farZ, vertex.z);
            }
        }
        float width = right - left;
        if (!Float.isFinite(width) || width < MIN_HEAD_WIDTH) {
            return false;
        }

        int stride = YsmPhysicsParts.contactStride(own.size(), skull.size());
        float nearest = Float.POSITIVE_INFINITY;
        for (Vector3f vertex : own) {
            if (vertex != null && YsmDynamicBoneSolver.isFinite(vertex)) {
                nearest = Math.min(nearest, YsmPhysicsParts.distanceToCloud(vertex, skull, stride));
            }
        }
        if (!(nearest <= Math.min(MAX_CONTACT_GAP, width * MAX_GAP_TO_HEAD_WIDTH))) {
            return false;
        }

        int valid = 0;
        int withinHead = 0;
        for (Vector3f vertex : own) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            valid++;
            if (vertex.x >= left - SKULL_ENVELOPE_SLACK
                    && vertex.x <= right + SKULL_ENVELOPE_SLACK
                    && vertex.y >= lowY - SKULL_ENVELOPE_SLACK
                    && vertex.y <= highY + SKULL_ENVELOPE_SLACK
                    && vertex.z >= nearZ - SKULL_ENVELOPE_SLACK
                    && vertex.z <= farZ + SKULL_ENVELOPE_SLACK) {
                withinHead++;
            }
        }
        if (valid < 4 || withinHead < valid * MIN_SKULL_OCCUPANCY) {
            return false;
        }

        // Use the same one-centimetre contact patch as the anchor search. The two lateral sides
        // must both occur in that patch, relative to the skull's own centre, regardless of the
        // candidate's name, pivot, size or the direction its remaining geometry extends.
        float middle = (left + right) * 0.5F;
        float contactLeft = Float.POSITIVE_INFINITY;
        float contactRight = Float.NEGATIVE_INFINITY;
        for (Vector3f vertex : own) {
            if (vertex != null && YsmDynamicBoneSolver.isFinite(vertex)
                    && YsmPhysicsParts.distanceToCloud(vertex, skull, stride)
                    <= nearest + YsmPhysicsParts.CONTACT_PATCH_TOLERANCE) {
                contactLeft = Math.min(contactLeft, vertex.x);
                contactRight = Math.max(contactRight, vertex.x);
            }
        }
        return contactLeft <= middle - width * MIN_CONTACT_HALF_WIDTH
                && contactRight >= middle + width * MIN_CONTACT_HALF_WIDTH;
    }
}
