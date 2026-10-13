package com.ysmef.compat.renderer.layer;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

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
        Map<String, OpenMatrix4f> reboundJoints = new HashMap<>();
        rebound.rootJoint.iterSubJoints(joint -> {
            int id = joint.getId();
            if (id >= 0 && id < reboundPoses.length && reboundPoses[id] != null) {
                reboundJoints.put(joint.getName(), new OpenMatrix4f(reboundPoses[id]));
            }
        });
        boolean found = false;
        for (int i = 0; i < GRIP_JOINTS.length; i++) {
            Joint joint = rebound.searchJointByName(GRIP_JOINTS[i]);
            if (joint != null && joint.getId() >= 0 && joint.getId() < reboundPoses.length
                    && reboundPoses[joint.getId()] != null) {
                grips[i] = new OpenMatrix4f(reboundPoses[joint.getId()]);
                found = true;
            }
        }
        scope.snapshot = found ? new Snapshot(source, sourcePoses, grips, reboundJoints) : null;
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
        return replaceGrips(scope.snapshot, source, poses);
    }

    /**
     * Addon item renderers can ignore the pose array supplied by EF's layer and
     * read the patch armature again after calling setPose. Redirect only those
     * reads made while this player's held-item layer is executing. In particular,
     * body, armor, and another entity's armature must keep their original poses.
     */
    public static OpenMatrix4f[] posesForCurrentItem(Armature source, OpenMatrix4f[] poses) {
        ArrayDeque<Scope> scopes = SCOPES.get();
        Scope scope = scopes == null ? null : scopes.peek();
        if (scope == null || scope.itemLayerDepth == 0 || scope.snapshot == null
                || scope.snapshot.source != source || scope.snapshot.sourcePoses != poses) {
            return poses;
        }
        OpenMatrix4f[] result = poses.clone();
        replaceItemJointTree(source.rootJoint, poses, result, scope.snapshot.reboundJoints, null);
        return result;
    }

    public static boolean beginItemLayer(Object entity, Object patch, Armature source) {
        ArrayDeque<Scope> scopes = SCOPES.get();
        Scope scope = scopes == null ? null : scopes.peek();
        if (scope != null && scope.entity == entity && scope.patch == patch
                && scope.snapshot != null && scope.snapshot.source == source) {
            scope.itemLayerDepth++;
            return true;
        }
        return false;
    }

    public static void endItemLayer(boolean entered) {
        if (!entered) {
            return;
        }
        ArrayDeque<Scope> scopes = SCOPES.get();
        Scope scope = scopes == null ? null : scopes.peek();
        if (scope != null && scope.itemLayerDepth > 0) {
            scope.itemLayerDepth--;
        }
    }

    private static OpenMatrix4f[] replaceGrips(Snapshot snapshot, Armature source, OpenMatrix4f[] poses) {
        OpenMatrix4f[] result = null;
        for (int i = 0; i < GRIP_JOINTS.length; i++) {
            OpenMatrix4f grip = snapshot.grips[i];
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

    /** Rebase extra weapon bones on their nearest YSM-sized parent joint. */
    private static void replaceItemJointTree(Joint joint, OpenMatrix4f[] sourcePoses,
                                             OpenMatrix4f[] result, Map<String, OpenMatrix4f> reboundJoints,
                                             OpenMatrix4f parentDelta) {
        if (joint == null) {
            return;
        }
        int id = joint.getId();
        OpenMatrix4f delta = parentDelta;
        if (id >= 0 && id < sourcePoses.length && sourcePoses[id] != null) {
            OpenMatrix4f rebound = reboundJoints.get(joint.getName());
            if (rebound != null) {
                result[id] = new OpenMatrix4f(rebound);
                if (Math.abs(sourcePoses[id].determinant()) > 1.0E-6F) {
                    delta = OpenMatrix4f.mul(rebound, OpenMatrix4f.invert(sourcePoses[id], null), null);
                }
            } else if (delta != null) {
                result[id] = OpenMatrix4f.mul(delta, sourcePoses[id], null);
            }
        }
        for (Joint child : joint.getSubJoints()) {
            replaceItemJointTree(child, sourcePoses, result, reboundJoints, delta);
        }
    }

    private static final class Scope {
        private final Object entity;
        private final Object patch;
        private Snapshot snapshot;
        private int itemLayerDepth;

        private Scope(Object entity, Object patch) {
            this.entity = entity;
            this.patch = patch;
        }
    }

    private record Snapshot(Armature source, OpenMatrix4f[] sourcePoses, OpenMatrix4f[] grips,
                            Map<String, OpenMatrix4f> reboundJoints) {}
}
