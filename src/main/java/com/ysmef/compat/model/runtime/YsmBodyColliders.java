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
     * Which of the sorted vertex distances from the geometry's centre becomes the radius, for a
     * <b>compact</b> part.
     *
     * <p>Low on purpose, and lower than it looks like it should be. The distances are measured
     * from the centre of everything bound to the joint, and for a limb those distances are set by
     * its <b>length</b>, not its width: a thigh's geometry reaches from about a hand's width away
     * to most of a thigh's length away, so the median of that distribution is nearly half the
     * thigh, and a sphere that big swallows the whole space a skirt hangs in. The low percentile
     * lands near the short end - the limb's own thickness - which is what a collision volume for
     * cloth has to approximate. For a compact part such as the head the distribution is narrow, so
     * any percentile gives about the same answer.
     *
     * <p>Only the torso, the chest and the head use it now; a limb gets a capsule, whose tube radius
     * is measured perpendicular to its own axis and therefore has no length in it at all. See
     * {@link #LIMB_JOINTS}.
     */
    private static final float RADIUS_PERCENTILE = 0.15F;

    /**
     * The joints whose volume is a capsule along the limb rather than a sphere around its middle.
     *
     * <h2>Why the shape had to change, in one measurement</h2>
     *
     * <p>A limb's vertex cloud is long and thin: its points run from the hip to the knee along one
     * axis, and only a couple of centimetres either side of it. A sphere can only contain that cloud
     * by growing to the limb's <i>length</i> - which, at the length of a thigh, is a sphere that
     * swallows the whole space a skirt hangs in. So the rule took the 15th percentile instead and
     * got a sphere the width of the limb and located at the middle of it. The shipped numbers on the
     * model this was reported against are the consequence: the thigh volume's radius is 0.087 blocks
     * against a geometry that reaches 0.458 blocks from its own centre, so the volume covers about a
     * fifth of the limb it stands for. The leg's mesh passes outside the volume, the cloth is never
     * asked to move, and what a viewer sees is <b>the leg stepping through the skirt</b> - which is
     * the report this fixes.
     *
     * <p>Radius alone cannot fix that, and the clamp says why: the head and torso radii are already
     * being clamped <i>down</i> (0.29 to 0.20 and 0.31 to 0.20), so raising the ceiling would inflate
     * the compact volumes that are already too big for the cloth around them. The shape is what is
     * wrong. A capsule is unbounded along the limb and thin across it, which is the same thing as
     * saying the sphere's "too long" and "too fat" trade-off does not exist for it.
     */
    private static final int[] LIMB_JOINTS = {
            1, 4,   // Thigh_R, Thigh_L
            2, 5,   // Leg_R, Leg_L
            3, 6    // Knee_R, Knee_L
    };

    /**
     * How far out the geometry is allowed to reach radially, as a percentile, when the capsule's
     * tube radius is measured perpendicular to the limb's own axis.
     *
     * <p>Higher than the sphere's percentile, and deliberately: perpendicular distances carry no
     * length, so the distribution they form <i>is</i> the limb's thickness profile rather than a
     * mixture of its length and its width. The 85th percentile then leaves only the corners of a
     * blocky mesh outside - a hip's flare, a knee's corner - instead of leaving most of the limb
     * outside as the sphere did. {@code tmp_verify/T12_volumes.txt} carries the coverage this
     * produces per joint.
     */
    private static final float LIMB_RADIUS_PERCENTILE = 0.85F;

    /** A capsule's tube radius is clamped to this, blocks, for the same reason the sphere's is. */
    private static final float MAX_LIMB_RADIUS = 0.20F;

    /**
     * The character of a collision volume: a sphere when {@code halfLength} is zero, and a capsule
     * along {@code axis} otherwise.
     *
     * <p>One type rather than two because a sphere IS a capsule whose two cap centres coincide, and
     * that is not a coincidence of this code but the geometry: every distance test, the push-out and
     * the skip rule are the same statement with the segment collapsed to a point. Two parallel
     * implementations would be two chances for a skirt to rest on a thigh in one and sink into it in
     * the other.
     */
    private record BindVolume(int joint, float x, float y, float z,
                              float axisX, float axisY, float axisZ,
                              float halfLength, float radius) {}

    private final List<BindVolume> bindVolumes;
    /** Resolved per frame: [end0 x,y,z, end1 x,y,z, radius] per volume. */
    private final float[] resolved;
    /** The joint behind each resolved volume, for the log. */
    private final int[] resolvedJoint;
    private final int count;
    /** Whether {@link #update} has placed the volumes this frame; see {@link #resolve}. */
    private boolean placed;

    private YsmBodyColliders(List<BindVolume> bindVolumes) {
        this.bindVolumes = bindVolumes;
        this.count = bindVolumes.size();
        this.resolved = new float[count * 8];
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
            List<Vector3f> vertices = new ArrayList<>();
            for (VertexBuilder builder : part.getVertices()) {
                int position = builder.position * 3;
                if (position + 2 < positions.length) {
                    vertices.add(new Vector3f(positions[position], positions[position + 1], positions[position + 2]));
                }
            }
            if (!vertices.isEmpty()) {
                byJoint.computeIfAbsent(bone.joint, key -> new ArrayList<>()).addAll(vertices);
            }
        }
        return fromGeometry(byJoint);
    }

    /**
     * The shape rule on its own, over geometry already gathered per joint.
     *
     * <p>Split out of {@link #build} because this is the part that decides what a limb's volume
     * <i>is</i> - a capsule along the limb or a sphere at its middle - and it is the part the user's
     * report is about. Driving it needs no mesh, no armature and no game: only the vertices that
     * belong to each joint, which a fixture can supply directly. {@link #build} is the mesh plumbing
     * above this and nothing else.
     *
     * @param byJoint the vertices bound to each Epic Fight joint, in blocks, in bind space
     * @return the volumes, or null when no joint has enough geometry to size one
     */
    static YsmBodyColliders fromGeometry(Map<Integer, List<Vector3f>> byJoint) {
        List<BindVolume> volumes = new ArrayList<>();
        for (int joint : BODY_JOINTS) {
            List<Vector3f> vertices = byJoint.get(joint);
            if (vertices == null || vertices.size() < MIN_VERTICES) {
                continue;
            }
            BindVolume volume = isLimbJoint(joint)
                    ? limbVolume(joint, vertices)
                    : compactVolume(joint, vertices);
            if (volume != null) {
                volumes.add(volume);
            }
        }
        return volumes.isEmpty() ? null : new YsmBodyColliders(volumes);
    }

    /**
     * The capsule of a limb: as long as the limb, no thicker than it.
     *
     * <p>The axis is the geometry's own principal direction rather than the bone's, and the choice
     * matters enough to state. A bone axis is anatomically the right line but is not available here
     * in the same space as the vertices: this runs at build time from the converted mesh, where a
     * joint's {@code toOrigin} is reachable but its parent's pivot is not without walking the
     * armature, and the two frames - bind geometry and joint local - are exactly the pair that has
     * caused a factor-of-16 accident in this project. The principal axis is measured from the same
     * vertices as everything else in this method, in the same frame, with no transform between them;
     * for a limb, whose geometry is a box along the bone, it is the bone's direction by construction.
     *
     * <p>The radius is the 85th percentile of the distances <i>perpendicular</i> to that axis. Those
     * distances contain no length - that is what projecting the length out of them means - so the
     * percentile now selects among the limb's own thicknesses rather than among a mixture of its
     * thickness and its length, which is what made the sphere's 15th percentile land on a radius six
     * times too small.
     */
    private static BindVolume limbVolume(int joint, List<Vector3f> vertices) {
        Vector3f centre = new Vector3f();
        for (Vector3f vertex : vertices) {
            centre.add(vertex);
        }
        centre.div(vertices.size());
        Vector3f axis = principalAxis(vertices, centre);
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        float[] perpendicular = new float[vertices.size()];
        Vector3f offset = new Vector3f();
        for (int i = 0; i < vertices.size(); i++) {
            offset.set(vertices.get(i)).sub(centre);
            float along = offset.dot(axis);
            lo = Math.min(lo, along);
            hi = Math.max(hi, along);
            perpendicular[i] = (float) Math.sqrt(Math.max(0.0F,
                    offset.lengthSquared() - along * along));
        }
        java.util.Arrays.sort(perpendicular);
        float radius = perpendicular[Math.max(0, Math.min(perpendicular.length - 1,
                (int) (perpendicular.length * LIMB_RADIUS_PERCENTILE)))];
        if (!Float.isFinite(radius) || !Float.isFinite(lo) || !Float.isFinite(hi)) {
            return null;
        }
        radius = Math.max(MIN_RADIUS, Math.min(MAX_LIMB_RADIUS, radius));
        // The segment's ends are the cap centres: pull them in by the cap radius from the two
        // extremes of the projections, so the capsule's total reach is exactly the geometry's.
        float halfLength = Math.max(0.0F, (hi - lo) * 0.5F - radius);
        return new BindVolume(joint, centre.x, centre.y, centre.z,
                axis.x, axis.y, axis.z, halfLength, radius);
    }

    /** The sphere of a compact part: the rule this file has always used, unchanged. */
    private static BindVolume compactVolume(int joint, List<Vector3f> vertices) {
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
            return null;
        }
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        // The joint's own pivot is not available here; the bind pivot of the joint is
        // the pose matrix's translation at bind time, so the centre is kept in bind
        // space and moved by the joint's own transform every frame.
        return new BindVolume(joint, centre.x, centre.y, centre.z, 0.0F, 1.0F, 0.0F, 0.0F, radius);
    }

    /**
     * The direction a vertex cloud is longest in, by power iteration on its covariance matrix.
     *
     * <p>Power iteration rather than an eigen-decomposition because only the dominant axis is
     * wanted, the cloud is a few hundred points, and the classpath holds no linear algebra for the
     * general case. Sixty iterations is far past convergence for a well-conditioned cloud; a cloud
     * with no dominant direction returns a unit vector rather than a zero one, so a caller can
     * always project onto something.
     */
    static Vector3f principalAxis(List<Vector3f> vertices, Vector3f centre) {
        double xx = 0.0;
        double xy = 0.0;
        double xz = 0.0;
        double yy = 0.0;
        double yz = 0.0;
        double zz = 0.0;
        Vector3f offset = new Vector3f();
        for (Vector3f vertex : vertices) {
            offset.set(vertex).sub(centre);
            xx += offset.x * offset.x;
            xy += offset.x * offset.y;
            xz += offset.x * offset.z;
            yy += offset.y * offset.y;
            yz += offset.y * offset.z;
            zz += offset.z * offset.z;
        }
        // Start from the axis the cloud is least spread along, so the iteration cannot begin
        // orthogonal to the answer and stall at zero.
        Vector3f axis = new Vector3f(0.0F, 1.0F, 0.0F);
        if (yy <= xx && yy <= zz) {
            axis.set(1.0F, 0.0F, 0.0F);
        } else if (zz <= xx) {
            axis.set(0.0F, 0.0F, 1.0F);
        }
        for (int iteration = 0; iteration < 60; iteration++) {
            float nx = (float) (xx * axis.x + xy * axis.y + xz * axis.z);
            float ny = (float) (xy * axis.x + yy * axis.y + yz * axis.z);
            float nz = (float) (xz * axis.x + yz * axis.y + zz * axis.z);
            axis.set(nx, ny, nz);
            if (axis.lengthSquared() < 1.0E-12F) {
                return new Vector3f(0.0F, 1.0F, 0.0F);
            }
            axis.normalize();
        }
        return axis;
    }

    private static boolean isLimbJoint(int joint) {
        for (int candidate : LIMB_JOINTS) {
            if (candidate == joint) {
                return true;
            }
        }
        return false;
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
        placed = false;
        if (bindArmature == null || poses == null || count == 0) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            BindVolume volume = bindVolumes.get(i);
            int joint = volume.joint();
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
            //
            // Both cap centres go through it, and the axis with them - as a DIRECTION, so the
            // translation is left out of the second pass. A capsule whose two ends were placed by
            // different rules would change its length with the pose, which is the one thing a
            // capsule must not do.
            float half = volume.halfLength();
            float ax = volume.axisX() * half;
            float ay = volume.axisY() * half;
            float az = volume.axisZ() * half;
            for (int end = 0; end < 2; end++) {
                float sx = volume.x() + (end == 0 ? -ax : ax);
                float sy = volume.y() + (end == 0 ? -ay : ay);
                float sz = volume.z() + (end == 0 ? -az : az);
                float lx = toOrigin.m00 * sx + toOrigin.m10 * sy + toOrigin.m20 * sz + toOrigin.m30;
                float ly = toOrigin.m01 * sx + toOrigin.m11 * sy + toOrigin.m21 * sz + toOrigin.m31;
                float lz = toOrigin.m02 * sx + toOrigin.m12 * sy + toOrigin.m22 * sz + toOrigin.m32;
                float rx = pose.m00 * lx + pose.m10 * ly + pose.m20 * lz + pose.m30;
                float ry = pose.m01 * lx + pose.m11 * ly + pose.m21 * lz + pose.m31;
                float rz = pose.m02 * lx + pose.m12 * ly + pose.m22 * lz + pose.m32;
                if (!Float.isFinite(rx) || !Float.isFinite(ry) || !Float.isFinite(rz)) {
                    return false;
                }
                resolved[i * 8 + end * 4] = rx;
                resolved[i * 8 + end * 4 + 1] = ry;
                resolved[i * 8 + end * 4 + 2] = rz;
            }
            resolved[i * 8 + 7] = volume.radius();
            resolvedJoint[i] = joint;
        }
        placed = true;
        return true;
    }

    @Override
    public int count() {
        return count;
    }

    /**
     * One volume's geometry this frame: {@code [end0 x,y,z, end1 x,y,z, radius]}, or null when the
     * index is out of range.
     *
     * <p>Before the first {@link #update} the frame's array holds nothing yet, and this returns the
     * volume's <b>bind</b> geometry instead - the same shape in the space the model was built in.
     * Returning the empty frame array would answer every question about a volume with zeros, which
     * looks like a volume of radius zero rather than like "no frame has been drawn yet"; that
     * distinction cost a debugging round when this accessor was added.
     *
     * <p>For the log, the tests and the measurement probes. A caller must not write into it.
     */
    public float[] resolvedVolume(int index) {
        if (index < 0 || index >= count) {
            return null;
        }
        float[] out = java.util.Arrays.copyOfRange(resolved, index * 8, index * 8 + 8);
        if (out[7] > 0.0F) {
            return out;
        }
        BindVolume volume = bindVolumes.get(index);
        float half = volume.halfLength();
        out[0] = volume.x() - volume.axisX() * half;
        out[1] = volume.y() - volume.axisY() * half;
        out[2] = volume.z() - volume.axisZ() * half;
        out[4] = volume.x() + volume.axisX() * half;
        out[5] = volume.y() + volume.axisY() * half;
        out[6] = volume.z() + volume.axisZ() * half;
        out[7] = volume.radius();
        return out;
    }

    /**
     * The joint behind a volume.
     *
     * <p>Read from the <b>bind</b> set rather than from the frame's resolved state: the resolved
     * array is filled by {@link #update}, so before the first frame every entry is zero and a caller
     * asking which joint a volume belongs to would be told {@code Root} for all of them. That is a
     * silent wrong answer to a question a log line or a test asks directly, and it cost a debugging
     * round to find.
     */
    public int jointOf(int index) {
        return index >= 0 && index < count ? bindVolumes.get(index).joint() : -1;
    }

    @Override
    public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
        if (pivot == null || index < 0 || index >= count) {
            return true;
        }
        int base = index * 8;
        return volumeIsInsideWorkspace(pivot, restCentre, swingReach,
                resolved[base], resolved[base + 1], resolved[base + 2],
                resolved[base + 4], resolved[base + 5], resolved[base + 6],
                resolved[base + 7]);
    }

    /**
     * Whether a volume lies inside a segment's working space, and must therefore be ignored for it.
     *
     * <p>Two cases, and both are now measured from the volume's <b>axis</b> rather than from a
     * single point, because a limb's volume is a segment: the distance that decides whether cloth is
     * inside a thigh is the distance to the line the thigh runs along, not to the middle of it. With
     * a sphere the two are the same statement; with a capsule, measuring from the centre would skip
     * a volume for a panel resting beside the knee while leaving it in force for the same panel
     * beside the hip.
     *
     * <p>The two cases on {@link YsmDynamicBoneSolver.Colliders#skipFor} are implemented here:
     * the pivot inside the volume (the piece hangs off it), and the volume inside the piece's
     * reach - closer to the rest centre of mass than the volume's radius plus how far the piece's
     * own swing can carry it.
     */
    static boolean volumeIsInsideWorkspace(Vector3f pivot, Vector3f restCentre, float swingReach,
                                           float centreX, float centreY, float centreZ,
                                           float end1X, float end1Y, float end1Z, float radius) {
        if (pivot != null) {
            float distance = distanceToSegment(pivot.x, pivot.y, pivot.z,
                    centreX, centreY, centreZ, end1X, end1Y, end1Z);
            if (distance < radius) {
                return true;
            }
        }
        if (restCentre == null) {
            return false;
        }
        // A volume is skipped only when the piece's RESTING centre of mass is genuinely inside it.
        //
        // This used to be `radius + swingReach`, and that margin was the defect. Its meaning was "the
        // piece could touch this volume anywhere within its swing" - which is nearly every volume
        // near nearly every panel - when the case that genuinely cannot be satisfied is narrower: a
        // volume that CONTAINS the piece's resting place, because pushing a piece out of a volume it
        // starts inside is ejection rather than collision. Measured on the model this was reported
        // against, the two readings differ for real panels: FM1 rests 0.171 blocks from a thigh axis
        // whose capsule radius is 0.223 - outside it, clear - and the old margin still called that a
        // skip, so the thigh was told to ignore three panels it should have been holding off.
        // tmp_verify/T12_inside.txt carries the table.
        //
        // Containment, not proximity, and it is one test for both shapes: a sphere contains a point
        // within its radius of its centre, a capsule within its tube radius of its axis.
        float distance = distanceToSegment(restCentre.x, restCentre.y, restCentre.z,
                centreX, centreY, centreZ, end1X, end1Y, end1Z);
        return distance < radius;
    }

    /**
     * The distance from a point to the segment between two cap centres.
     *
     * <p>The one piece of geometry the whole capsule rests on, and it collapses to the sphere case
     * when the two ends are equal - which is what makes "a sphere is a capsule of zero length" true
     * here rather than merely asserted.
     */
    static float distanceToSegment(float px, float py, float pz,
                                   float ax, float ay, float az,
                                   float bx, float by, float bz) {
        float abx = bx - ax;
        float aby = by - ay;
        float abz = bz - az;
        float lengthSquared = abx * abx + aby * aby + abz * abz;
        float t = 0.0F;
        if (lengthSquared > 1.0E-12F) {
            t = ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / lengthSquared;
            t = t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
        }
        float dx = px - (ax + abx * t);
        float dy = py - (ay + aby * t);
        float dz = pz - (az + abz * t);
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean resolve(Vector3f point, Vector3f velocity, float radius, int index) {
        if (point == null || index < 0 || index >= count) {
            return false;
        }
        if (!placed) {
            // No frame has been drawn yet, so the volumes are still in bind space. Resolving against
            // them there is the same shape in the model's own frame: for a caller asking about the
            // model at rest - a test, a probe, a diagnostic - that is the honest answer, and it is
            // preferable to resolving against a zeroed array, which silently reports "no contact"
            // against a volume of radius zero.
            return resolveInBindSpace(point, velocity, radius, index);
        }
        int base = index * 8;
        return pushOutOfCapsule(point, velocity, radius,
                resolved[base], resolved[base + 1], resolved[base + 2],
                resolved[base + 4], resolved[base + 5], resolved[base + 6],
                resolved[base + 7]);
    }

    /** The same push-out against a volume's bind geometry. See {@link #resolve}. */
    private boolean resolveInBindSpace(Vector3f point, Vector3f velocity, float radius, int index) {
        BindVolume volume = bindVolumes.get(index);
        float half = volume.halfLength();
        return pushOutOfCapsule(point, velocity, radius,
                volume.x() - volume.axisX() * half,
                volume.y() - volume.axisY() * half,
                volume.z() - volume.axisZ() * half,
                volume.x() + volume.axisX() * half,
                volume.y() + volume.axisY() * half,
                volume.z() + volume.axisZ() * half,
                volume.radius());
    }

    /**
     * The capsule-against-sphere push-out the collision response is made of.
     *
     * <p>Sphere-against-sphere when the two ends coincide, which is the whole of the change: the
     * clip of the moving centre onto the segment's axis is skipped, {@code t} stays where the
     * degenerate segment puts it, and the arithmetic below is the arithmetic this method always did.
     *
     * <p>Static and free of the collider set so the one piece of geometry that decides whether
     * cloth rests on a thigh or sinks into it can be tested on its own.
     *
     * @param point       the moving sphere's centre, mutated in place when it overlaps
     * @param velocity    the moving sphere's velocity, mutated in place on contact
     * @param pointRadius the moving sphere's own radius
     * @return true when the point overlapped and was moved
     */
    static boolean pushOutOfCapsule(Vector3f point, Vector3f velocity, float pointRadius,
                                    float ax, float ay, float az,
                                    float bx, float by, float bz, float radius) {
        if (point == null) {
            return false;
        }
        float abx = bx - ax;
        float aby = by - ay;
        float abz = bz - az;
        float lengthSquared = abx * abx + aby * aby + abz * abz;
        float t = 0.0F;
        if (lengthSquared > 1.0E-12F) {
            t = ((point.x - ax) * abx + (point.y - ay) * aby + (point.z - az) * abz) / lengthSquared;
            t = t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
        }
        // The nearest point ON the volume, and the direction from there to the piece: the surface
        // of a capsule is the locus of points at `radius` from its axis, so this is the surface
        // normal rather than an approximation to it.
        float nearestX = ax + abx * t;
        float nearestY = ay + aby * t;
        float nearestZ = az + abz * t;
        float dx = point.x - nearestX;
        float dy = point.y - nearestY;
        float dz = point.z - nearestZ;
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
        point.set(nearestX + dx * minimum, nearestY + dy * minimum, nearestZ + dz * minimum);
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
            int base = i * 8;
            float dx = resolved[base + 4] - resolved[base];
            float dy = resolved[base + 5] - resolved[base + 1];
            float dz = resolved[base + 6] - resolved[base + 2];
            float span = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            builder.append(builder.length() == 0 ? "" : ", ")
                    .append("joint").append(resolvedJoint[i])
                    .append("@(")
                    .append(Math.round(resolved[base] * 100.0F) / 100.0F).append(',')
                    .append(Math.round(resolved[base + 1] * 100.0F) / 100.0F).append(',')
                    .append(Math.round(resolved[base + 2] * 100.0F) / 100.0F)
                    .append(") r=")
                    .append(Math.round(resolved[base + 7] * 100.0F) / 100.0F);
            if (span > 1.0E-3F) {
                // A limb, and the length goes in the log too: a capsule's radius alone does not say
                // whether it covers the thigh, and "the volume is the right thickness but half the
                // length it should be" is a failure that only this number would show.
                builder.append(" len=").append(Math.round(span * 100.0F) / 100.0F);
            }
        }
        return builder.toString();
    }
}
