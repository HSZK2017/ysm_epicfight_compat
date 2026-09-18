package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import org.joml.Vector3f;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The collision volumes a piece of hair or cloth has to stay out of: the model's own body.
 *
 * <h2>Why the body and not the world</h2>
 *
 * <p>What a player sees go wrong with untreated secondary motion is cloth inside the legs
 * and hair inside the shoulders, not hair inside a wall. Both of those are the model
 * colliding with itself, so the volumes are built from the model's own geometry: one
 * sphere per <b>body joint</b>, centred on that joint's binding geometry and sized from
 * how far that geometry spreads around its own centre.
 *
 * <p>That makes the set model-specific without being name-specific: a chibi model gets
 * small spheres and a tall one gets tall ones, and nothing had to be told which bone is a
 * thigh. Geometry that belongs to no body joint - a held sword, a floating accessory, a
 * hidden variant - contributes nothing, which is the wanted behaviour rather than a
 * limitation: hair should not be pushed around by a sword it never touches.
 *
 * <h2>Frames</h2>
 *
 * <p>Volumes are stored in the model's <b>bind space</b> and re-placed every frame with
 * {@code pose x toOrigin} for the joint they belong to - the same transform Epic Fight
 * applies to that joint's geometry, so a volume follows exactly what is drawn. The
 * skeleton used is the model's own re-bound armature (see {@link YsmBindArmature}), whose
 * joints sit on the model's real proportions rather than on Steve's.
 *
 * <h2>Under-approximation is deliberate</h2>
 *
 * <p>The radius is the median distance from the geometry's own centre, not the maximum:
 * a sphere that swallows the arms would push a skirt away from the whole body, whereas a
 * slightly small sphere lets cloth rest on the surface, which is what cloth does.
 */
public final class YsmBodyColliders implements YsmDynamicBoneSolver.Colliders {

    /**
     * The joints a garment rests against: the body's core and its legs. The ids are Epic Fight's
     * humanoid armature ids, which are also the ids the runtime bone table resolves YSM bones to,
     * so this is a stable contract rather than a guess about names.
     *
     * <p><b>The arms are deliberately absent</b>, and that is a correction rather than an
     * omission. A crude sphere around an upper arm or a forearm is not a shape cloth can rest on;
     * it is a shape that sweeps through the space a skirt occupies every time the character moves
     * its arms, throwing panels on contact and letting go on the next frame. The shipped log shows
     * it plainly: the right forearm's volume sat <i>inside</i> the right-back panel's resting
     * position. Measuring the model confirms the arms are the wrong thing to collide a garment
     * with - what a skirt has to stay out of is the torso and the thighs.
     */
    private static final int[] BODY_JOINTS = {
            0,  // Root
            1, 4,   // Thigh_R, Thigh_L
            2, 5,   // Leg_R, Leg_L
            3, 6,   // Knee_R, Knee_L
            7, 8,   // Torso, Chest
            9  // Head
    };

    /** Fewer vertices than this and there is no shape to size a volume from. */
    private static final int MIN_VERTICES = 12;

    /**
     * Volumes are clamped to this range, blocks: a degenerate estimate must not explode.
     *
     * <p>The upper bound is deliberately about a fifth of a block, well under half the thigh's
     * width. A volume that covers the limb's whole envelope also covers the space around it where
     * cloth hangs, and a volume that merely <i>reaches</i> a panel's resting place does not
     * collide with it - it oscillates against it, because the panel is clamped to a swing limit
     * that stops it short of the surface. That limit cycle is what a garment panel pinned at its
     * limit looks like, and it is why the measured radii came down twice.
     */
    private static final float MIN_RADIUS = 0.03F;
    private static final float MAX_RADIUS = 0.20F;

    /**
     * Which of the sorted vertex distances from the geometry's centre becomes the radius.
     *
     * <p>Low on purpose, and lower than it looks like it should be. The distances are measured
     * from the centre of everything bound to the joint, and for a limb those distances are set by
     * its <b>length</b>, not its width: a thigh's geometry reaches from about a hand's width away
     * to most of a thigh's length away, so the median of that distribution is nearly half the
     * thigh, and a sphere that big swallows the whole space a skirt hangs in. The low percentile
     * lands near the short end - the limb's own thickness - which is what a collision volume for
     * cloth has to approximate. For a compact part such as the head the distribution is narrow, so
     * any percentile gives about the same answer.
     */
    private static final float RADIUS_PERCENTILE = 0.15F;

    private record BindSphere(int joint, float x, float y, float z, float radius) {}

    private final List<BindSphere> bindSpheres;
    /** Resolved per frame: [x, y, z, radius] per volume. */
    private final float[] resolved;
    /** The joint behind each resolved volume, for the log. */
    private final int[] resolvedJoint;
    private final int count;

    private YsmBodyColliders(List<BindSphere> bindSpheres) {
        this.bindSpheres = bindSpheres;
        this.count = bindSpheres.size();
        this.resolved = new float[count * 5];
        this.resolvedJoint = new int[count];
    }

    /**
     * Build the collision volumes of a model, from its converted mesh and bone table.
     *
     * @return the volumes, or null when the model's body geometry cannot support them
     *         (in which case the caller simulates without collision rather than not at all)
     */
    public static YsmBodyColliders build(YSMMesh mesh, YSMRuntimeModel model) {
        if (mesh == null || model == null || model.bones == null) {
            return null;
        }
        float[] positions = mesh.positions();
        if (positions == null || positions.length < 9) {
            return null;
        }
        // Gather the geometry bound to each body joint, per bone so that a bone's own
        // centre can be used rather than a lumped centroid.
        Map<Integer, List<Vector3f>> byJoint = new java.util.HashMap<>();
        java.util.Set<String> hidden = model.defaultHiddenBoneNames();
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer boneIndex = model.boneIndex.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (boneIndex == null || boneIndex < 0 || boneIndex >= model.bones.length) {
                continue;
            }
            YSMRuntimeModel.BoneRt bone = model.bones[boneIndex];
            if (!isBodyJoint(bone.joint) || !bone.mapped) {
                // Only directly mapped body bones: a "cape" that resolves to the chest is
                // not the chest, and a volume around it would push the hair away from the
                // model instead of away from the body.
                continue;
            }
            // Geometry the model's scripts collapse to nothing in its default form is still in
            // the mesh and still bound to this joint - a hat, a variant's accessory, a weapon on
            // the back. Averaged in, it drags the volume's centre off the body and inflates its
            // radius, and a volume that is too big and in the wrong place does not collide with
            // cloth, it fires it away. YsmBindArmature excludes the same bones for the same
            // reason.
            if (hidden.contains(bone.name)) {
                continue;
            }
            MeshPart part = entry.getValue();
            if (part == null || part.getVertices() == null) {
                continue;
            }
            List<Vector3f> vertices = byJoint.computeIfAbsent(bone.joint, key -> new ArrayList<>());
            for (VertexBuilder builder : part.getVertices()) {
                int position = builder.position * 3;
                if (position + 2 < positions.length) {
                    vertices.add(new Vector3f(positions[position], positions[position + 1], positions[position + 2]));
                }
            }
        }

        List<BindSphere> spheres = new ArrayList<>();
        for (int joint : BODY_JOINTS) {
            List<Vector3f> vertices = byJoint.get(joint);
            if (vertices == null || vertices.size() < MIN_VERTICES) {
                continue;
            }
            Vector3f centre = new Vector3f();
            for (Vector3f vertex : vertices) {
                centre.add(vertex);
            }
            centre.div(vertices.size());
            float[] distances = new float[vertices.size()];
            for (int i = 0; i < vertices.size(); i++) {
                distances[i] = vertices.get(i).distance(centre);
            }
            java.util.Arrays.sort(distances);
            float radius = distances[Math.max(0, Math.min(distances.length - 1,
                    (int) (distances.length * RADIUS_PERCENTILE)))];
            if (!Float.isFinite(radius)) {
                continue;
            }
            radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
            // The joint's own pivot is not available here; the bind pivot of the joint is
            // the pose matrix's translation at bind time, so the centre is kept in bind
            // space and moved by the joint's own transform every frame.
            spheres.add(new BindSphere(joint, centre.x, centre.y, centre.z, radius));
        }
        return spheres.isEmpty() ? null : new YsmBodyColliders(spheres);
    }

    private static boolean isBodyJoint(int joint) {
        for (int candidate : BODY_JOINTS) {
            if (candidate == joint) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-place every volume for this frame.
     *
     * <p>{@code poses} must be the pose matrices of {@code bindArmature} (the model's own
     * re-bound skeleton), which is what {@code YSMMesh#draw} hands to the runtime bridge.
     * Anything else leaves the volumes where they were, which degrades to "no collision
     * this frame" rather than to a collision volume in the wrong place - so the caller
     * must check that the armature really is the model's bind armature before calling.
     *
     * @return true when the volumes are usable this frame
     */
    public boolean update(Armature bindArmature, OpenMatrix4f[] poses) {
        if (bindArmature == null || poses == null || count == 0) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            BindSphere sphere = bindSpheres.get(i);
            int joint = sphere.joint();
            if (joint >= poses.length || joint >= bindArmature.getJointNumber()
                    || poses[joint] == null) {
                return false;
            }
            yesman.epicfight.api.animation.Joint skeletonJoint = bindArmature.searchJointById(joint);
            if (skeletonJoint == null) {
                return false;
            }
            OpenMatrix4f pose = poses[joint];
            OpenMatrix4f toOrigin = skeletonJoint.getToOrigin();
            // A bind-space point is placed by pose x toOrigin: toOrigin carries it into the
            // joint's own frame and the pose carries it back out to the model's, which is
            // the exact product Epic Fight's skinning uses for that joint's geometry.
            float lx = toOrigin.m00 * sphere.x() + toOrigin.m10 * sphere.y() + toOrigin.m20 * sphere.z() + toOrigin.m30;
            float ly = toOrigin.m01 * sphere.x() + toOrigin.m11 * sphere.y() + toOrigin.m21 * sphere.z() + toOrigin.m31;
            float lz = toOrigin.m02 * sphere.x() + toOrigin.m12 * sphere.y() + toOrigin.m22 * sphere.z() + toOrigin.m32;
            float rx = pose.m00 * lx + pose.m10 * ly + pose.m20 * lz + pose.m30;
            float ry = pose.m01 * lx + pose.m11 * ly + pose.m21 * lz + pose.m31;
            float rz = pose.m02 * lx + pose.m12 * ly + pose.m22 * lz + pose.m32;
            if (!Float.isFinite(rx) || !Float.isFinite(ry) || !Float.isFinite(rz)) {
                return false;
            }
            int base = i * 5;
            resolved[base] = rx;
            resolved[base + 1] = ry;
            resolved[base + 2] = rz;
            resolved[base + 3] = sphere.radius();
            resolvedJoint[i] = joint;
        }
        return true;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
        if (pivot == null || index < 0 || index >= count) {
            return true;
        }
        int base = index * 5;
        return volumeIsInsideWorkspace(pivot, restCentre, swingReach,
                resolved[base], resolved[base + 1], resolved[base + 2], resolved[base + 3]);
    }

    /**
     * Whether a volume lies inside a segment's working space, and must therefore be ignored for it.
     *
     * <p>Static and free of the collider set so the rule can be tested against the real model and
     * the volumes a real run reported - which is how it was established that this is not a
     * threshold to be tuned but the difference between collision and ejection. On the maid skirt,
     * ten of its twenty-two panels have a thigh volume inside their own resting position
     * ({@code FM1} at 0.138 blocks from joint 4, needing 0.226 to clear its own swing): a sphere
     * that lives inside the cloth cannot police the cloth.
     *
     * <p>The two cases on {@link YsmDynamicBoneSolver.Colliders#skipFor} are implemented here:
     * the pivot inside the volume (the piece hangs off it), and the volume inside the piece's
     * reach - closer to the rest centre of mass than the volume's radius plus how far the piece's
     * own swing can carry it.
     */
    static boolean volumeIsInsideWorkspace(Vector3f pivot, Vector3f restCentre, float swingReach,
                                           float centreX, float centreY, float centreZ, float radius) {
        if (pivot != null) {
            float px = pivot.x - centreX;
            float py = pivot.y - centreY;
            float pz = pivot.z - centreZ;
            if (px * px + py * py + pz * pz < radius * radius) {
                return true;
            }
        }
        if (restCentre == null) {
            return false;
        }
        float margin = radius + Math.max(0.0F, swingReach);
        float cx = restCentre.x - centreX;
        float cy = restCentre.y - centreY;
        float cz = restCentre.z - centreZ;
        return cx * cx + cy * cy + cz * cz < margin * margin;
    }

    @Override
    public boolean resolve(Vector3f point, Vector3f velocity, float radius, int index) {
        if (point == null || index < 0 || index >= count) {
            return false;
        }
        int base = index * 5;
        return pushOutOfSphere(point, velocity, radius,
                resolved[base], resolved[base + 1], resolved[base + 2], resolved[base + 3]);
    }

    /**
     * The sphere-against-sphere push-out the collision response is made of.
     *
     * <p>Static and free of the collider set so the one piece of geometry that decides whether
     * cloth rests on a thigh or sinks into it can be tested on its own.
     *
     * @param point       the moving sphere's centre, mutated in place when it overlaps
     * @param velocity    the moving sphere's velocity, mutated in place on contact
     * @param pointRadius the moving sphere's own radius
     * @return true when the point overlapped and was moved
     */
    static boolean pushOutOfSphere(Vector3f point, Vector3f velocity, float pointRadius,
                                   float centreX, float centreY, float centreZ, float radius) {
        if (point == null) {
            return false;
        }
        float dx = point.x - centreX;
        float dy = point.y - centreY;
        float dz = point.z - centreZ;
        float minimum = radius + Math.max(0.0F, pointRadius);
        float distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared >= minimum * minimum) {
            return false;
        }
        float distance = (float) Math.sqrt(distanceSquared);
        if (distance < 1.0E-4F) {
            // Dead centre: any direction is as good as another, and "up" at least leaves the
            // piece hanging rather than shooting it sideways.
            dx = 0.0F;
            dy = 1.0F;
            dz = 0.0F;
            distance = 1.0F;
        }
        dx /= distance;
        dy /= distance;
        dz /= distance;
        point.set(centreX + dx * minimum, centreY + dy * minimum, centreZ + dz * minimum);
        if (velocity != null) {
            float normalSpeed = velocity.x * dx + velocity.y * dy + velocity.z * dz;
            if (normalSpeed < 0.0F) {
                // No restitution: cloth does not bounce off a thigh.
                velocity.x -= normalSpeed * dx;
                velocity.y -= normalSpeed * dy;
                velocity.z -= normalSpeed * dz;
            }
        }
        return true;
    }

    /** One line naming the volumes, for the log: "the physics has no collisions" is otherwise unanswerable. */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int base = i * 5;
            builder.append(builder.length() == 0 ? "" : ", ")
                    .append("joint").append(resolvedJoint[i])
                    .append("@(")
                    .append(Math.round(resolved[base] * 100.0F) / 100.0F).append(',')
                    .append(Math.round(resolved[base + 1] * 100.0F) / 100.0F).append(',')
                    .append(Math.round(resolved[base + 2] * 100.0F) / 100.0F)
                    .append(") r=")
                    .append(Math.round(resolved[base + 3] * 100.0F) / 100.0F);
        }
        return builder.toString();
    }
}
