package com.ysmef.compat.model.runtime;

import com.ysmef.compat.ysm.script.Molang;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One authored physics animation, read as what the author declared.
 *
 * <h2>What this is for, and what it is not</h2>
 *
 * <p><b>It is no longer on the frame path.</b> Its evaluations used to be written onto the mesh
 * parts, which made an authored physics animation a keyframe animation whose input happened to be
 * the body's motion - a piece with no moment arm, no mass and nothing to collide with, and the
 * source of the in-game report this project has been chasing. The author's contribution is now a
 * <i>part list</i> plus each bone's <i>spring parameters</i>, both of which reach the simulation
 * through {@link YsmPhysicsParts} ({@code Source.AUTHORED}, from the conversion-time
 * {@link YsmPhysicsBinding}); every transform is integrated by {@link YsmDynamicBoneSolver}. What
 * is kept here is the reader and the evidence: {@link #of} turns a stored animation into its bone
 * names in the animation's own order, which is what the runtime logs to say how much of the
 * author's rig is actually simulated. Nothing calls {@link #advance} or {@link #evaluate} from the
 * render path any more.
 *
 * <p>It is kept rather than deleted for two reasons. It is the one place that knows the storage
 * format's shape (a bone's rotation channels as expression strings), which is what a future
 * parameter read would need; and the pass it implements - YSM's evaluation order, which expression
 * feeds which - is pinned by its own test against the shipped {@code Hair_Physics}. Deleting that
 * would delete the only written-down statement of the order the authors' expressions rely on.
 *
 * <h2>Why the evaluation order was the behaviour</h2>
 *
 * <p>YSM's chains are built by expressions reading the bone above them
 * ({@code ysm.bone_rot('BackHairB1').x}), so a bone must see the rotations of the bones before it in
 * the animation and must not see its own or a later one's. This class therefore evaluates the list
 * once, in the order it was given, feeding each result back into the environment before moving on -
 * the same single pass YSM's animation loop makes. It is not a detail that can be parallelised or
 * reordered: a chain evaluated out of order reads zeros, and its segments then do not move.
 *
 * <p>Bones the animation says nothing about keep whatever the pose gave them: an expression that is
 * absent is a bone the author did not want moved, and this class answers zero for it rather than
 * leaving a stale rotation behind.
 *
 * <p>The list of bones comes from the model's own physics animation. Keeping that list as a plain
 * sequence of named expressions - rather than reading the animation structure here - is deliberate:
 * the animation's storage is the one part of this that is about our file format, while everything
 * above is about YSM's behaviour, and only the behaviour is worth testing against a fixture.
 */
public final class YsmPhysicsLayer {

    /**
     * One bone of one physics animation: its name, and the expression for each of its rotation
     * channels, exactly as the author wrote them. A null or empty expression means the author did
     * not write one, and the bone does not move.
     */
    public record Bone(String name, String rotationX, String rotationY, String rotationZ) {}

    private final List<Bone> bones;
    private final List<String> timeline;
    private final YsmPhysicsMolangEnv env = new YsmPhysicsMolangEnv();
    private final Map<String, float[]> rotations = new HashMap<>();

    public YsmPhysicsLayer(List<Bone> bones) {
        this(bones, List.of());
    }

    /**
     * @param bones    the bones to drive, in the animation's own order
     * @param timeline the animation's timeline statements, in time order; these run before the
     *                 bones, because that is what the authors' deltas are built out of
     */
    public YsmPhysicsLayer(List<Bone> bones, List<String> timeline) {
        this.bones = bones == null ? List.of() : List.copyOf(bones);
        this.timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    /**
     * The physics animation of a model, read out of the animation the converter stored.
     *
     * <p>This is the one place that knows about our own file format, and it is kept to a shape
     * change: a bone's rotation channels become expression strings, in the animation's own order,
     * and its timeline becomes a list of statements. Constants are turned into the expression that
     * evaluates to them, so everything downstream sees one kind of thing.
     *
     * <p>Each rotation channel is read as its first key. Physics animations are authored as a single
     * pose with expressions in it - that is what makes them physics rather than animation - so
     * there is one key and it does not matter which one is taken; a keyframed rotation in one of
     * these would be an animation that also happens to be called physics, and reading it from the
     * first key is then a reasonable answer rather than a wrong one.
     */
    public static YsmPhysicsLayer of(com.ysmef.compat.ysm.script.ScriptAnim animation) {
        if (animation == null) {
            return new YsmPhysicsLayer(List.of());
        }
        List<Bone> bones = new ArrayList<>();
        for (Map.Entry<String, com.ysmef.compat.ysm.script.ScriptAnim.BoneChannels> entry
                : animation.bones.entrySet()) {
            com.ysmef.compat.ysm.script.ScriptAnim.BoneChannels channels = entry.getValue();
            if (channels == null || channels.rotation == null || channels.rotation.keys.isEmpty()) {
                // Nothing to drive: the bone is not part of the physics, and leaving it out keeps
                // the evaluation order to the bones that are.
                continue;
            }
            com.ysmef.compat.ysm.script.ScriptAnim.Value value =
                    channels.rotation.keys.get(0).post != null
                            ? channels.rotation.keys.get(0).post
                            : channels.rotation.keys.get(0).pre;
            if (value == null) {
                continue;
            }
            bones.add(new Bone(entry.getKey(),
                    channelExpression(value, 0), channelExpression(value, 1), channelExpression(value, 2)));
        }
        List<String> timeline = new ArrayList<>();
        for (com.ysmef.compat.ysm.script.ScriptAnim.Timeline entry : animation.timelines) {
            if (entry != null && entry.code != null) {
                for (String code : entry.code) {
                    if (code != null && !code.isEmpty()) {
                        timeline.add(code);
                    }
                }
            }
        }
        return new YsmPhysicsLayer(bones, timeline);
    }

    /** One channel of a stored value: the author's expression, or the constant written as one. */
    private static String channelExpression(com.ysmef.compat.ysm.script.ScriptAnim.Value value, int axis) {
        if (value.expr[axis] != null && !value.expr[axis].isEmpty()) {
            return value.expr[axis];
        }
        return String.valueOf(value.num[axis]);
    }

    /** How many bones this animation drives; for the log. */
    public int size() {
        return this.bones.size();
    }

    /** The names, in evaluation order; for the log. */
    public List<String> boneNames() {
        List<String> names = new ArrayList<>(this.bones.size());
        for (Bone bone : this.bones) {
            names.add(bone.name());
        }
        return names;
    }

    /** Forget the filters and the variables (the model was reloaded, the entity respawned). */
    public void clear() {
        this.env.clear();
        this.rotations.clear();
    }

    /**
     * Advance the author's filters by one frame.
     *
     * <p>Called before {@link #evaluate}, which is the order YSM uses: a value an expression reads
     * during a frame is the one the previous frame's input produced. That one-frame lag is the
     * whole reason an authored physics animation trails instead of tracking.
     */
    public void advance(float dt) {
        this.env.filters().update(dt);
    }

    /**
     * Evaluate every bone's expressions once, in order.
     *
     * @param entity    the entity being drawn, or null when there is none (queries answer zero)
     * @param headYaw   the animated head yaw in degrees, as the pose has it this frame
     * @param headPitch the animated head pitch in degrees
     */
    public void evaluate(LivingEntity entity, double headYaw, double headPitch) {
        this.env.begin(entity, headYaw, headPitch);
        // The timeline first, in its own time order, once per frame. For the physics animations the
        // authors actually ship that is exactly right rather than an approximation: their length is
        // about one tick, and the timeline exists to difference a bone's rotation between frames -
        // one pass per frame is the thing it was written to do, and running it any less often would
        // make the delta it computes span more than the frame it is used in.
        //
        // A physics animation long enough to have a timeline that only wants some of its entries on
        // a given frame would need the time gate that YSM's animation loop applies. None has been
        // seen, and inventing one now would be guessing at a shape nobody has written.
        for (String statement : this.timeline) {
            Molang.eval(statement, this.env);
        }
        for (Bone bone : this.bones) {
            float x = channel(bone.rotationX());
            float y = channel(bone.rotationY());
            float z = channel(bone.rotationZ());
            // Recorded before it is stored, so a later bone reading this one sees the value this
            // frame produced and a bone reading itself sees zero - which is what an author's
            // arithmetic assumes in both cases.
            this.env.setBoneRotation(bone.name(), x, y, z);
            this.rotations.put(bone.name(), new float[]{x, y, z});
        }
    }

    /**
     * The rotation this bone was given by the last evaluation, in degrees and in bone-local axes,
     * or null when the animation does not drive it.
     */
    public float[] rotationOf(String boneName) {
        return this.rotations.get(boneName);
    }

    /** Every rotation the last evaluation produced, keyed by bone name. */
    public Map<String, float[]> rotations() {
        return this.rotations;
    }

    /** One channel: the author's expression, or zero when the author wrote none. */
    private float channel(String expression) {
        if (expression == null || expression.isEmpty()) {
            return 0.0F;
        }
        double value = Molang.eval(expression, this.env);
        return Float.isFinite((float) value) ? (float) value : 0.0F;
    }
}
