package com.ysmef.compat.renderer.layer;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayDeque;

/**
 * Hands the YSM mesh's posed palm joints to Epic Fight's later item layer.
 *
 * <p>EF poses the patch armature, draws the mesh, then renders its layers with
 * the patch's original pose array. The YSM mesh reposes the same animation on
 * model-sized joints during draw, but that array is not passed to the layers.
 * A render scope ties the two stages to the same entity and patch; copied hand
 * matrices also survive another entity posing the shared model armature.
 */
public final class YsmWeaponPoseBridge {
    private static final String[] GRIP_JOINTS = {"Tool_R", "Tool_L", "Hand_R", "Hand_L"};
    private static final ThreadLocal<ArrayDeque<Scope>> SCOPES = new ThreadLocal<>();

    private YsmWeaponPoseBridge() {}

    public static void begin(Object entity, Object patch) {
        ArrayDeque<Scope> scopes = SCOPES.get();
        if (scopes == null) {
            scopes = new ArrayDeque<>();
            SCOPES.set(scopes);
        }
        scopes.push(new Scope(entity, patch));
    }

    public static void end() {
        ArrayDeque<Scope> scopes = SCOPES.get();
        if (scopes != null && !scopes.isEmpty()) {
            scopes.pop();
        }
        if (scopes == null || scopes.isEmpty()) {
            SCOPES.remove();
        }
    }

    /** Called only after the mesh has posed its rebound armature for this draw. */
    public static void capture(Object entity, Armature source, OpenMatrix4f[] sourcePoses, Armature rebound) {
        ArrayDeque<Scope> scopes = SCOPES.get();
        Scope scope = scopes == null ? null : scopes.peek();
        if (scope == null || scope.entity != entity || source == null || rebound == null
                || sourcePoses == null || sourcePoses != source.getPoseMatrices()) {
            return;
        }
        OpenMatrix4f[] reboundPoses = rebound.getPoseMatrices();
        OpenMatrix4f[] grips = new OpenMatrix4f[GRIP_JOINTS.length];
        boolean found = false;
        for (int i = 0; i < GRIP_JOINTS.length; i++) {
            Joint joint = rebound.searchJointByName(GRIP_JOINTS[i]);
            if (joint != null && joint.getId() >= 0 && joint.getId() < reboundPoses.length
                    && reboundPoses[joint.getId()] != null) {
                grips[i] = new OpenMatrix4f(reboundPoses[joint.getId()]);
                found = true;
            }
        }
        scope.snapshot = found ? new Snapshot(source, sourcePoses, grips) : null;
    }

    /**
     * Return a private pose array for the item layer. EF still uses its patch to
     * choose the parent joint and the item's own correction transform; only the
     * named palm/tool joint matrices change. Holstered items on other joints keep
     * their original placement.
     */
    public static OpenMatrix4f[] posesFor(Object entity, Object patch, Armature source,
                                          OpenMatrix4f[] poses) {
        ArrayDeque<Scope> scopes = SCOPES.get();
        Scope scope = scopes == null ? null : scopes.peek();
        if (scope == null || scope.entity != entity || scope.patch != patch || scope.snapshot == null
                || scope.snapshot.source != source || scope.snapshot.sourcePoses != poses || poses == null) {
            return poses;
        }
        OpenMatrix4f[] result = null;
        for (int i = 0; i < GRIP_JOINTS.length; i++) {
            OpenMatrix4f grip = scope.snapshot.grips[i];
            Joint sourceJoint = source.searchJointByName(GRIP_JOINTS[i]);
            if (grip == null || sourceJoint == null || sourceJoint.getId() < 0
                    || sourceJoint.getId() >= poses.length) {
                continue;
            }
            if (result == null) {
                result = poses.clone();
            }
            result[sourceJoint.getId()] = new OpenMatrix4f(grip);
        }
        return result == null ? poses : result;
    }

    private static final class Scope {
        private final Object entity;
        private final Object patch;
        private Snapshot snapshot;

        private Scope(Object entity, Object patch) {
            this.entity = entity;
            this.patch = patch;
        }
    }

    private record Snapshot(Armature source, OpenMatrix4f[] sourcePoses, OpenMatrix4f[] grips) {}
}
