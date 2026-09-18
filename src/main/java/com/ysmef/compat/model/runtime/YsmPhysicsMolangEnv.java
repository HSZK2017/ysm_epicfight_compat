package com.ysmef.compat.model.runtime;

import com.ysmef.compat.ysm.script.Molang;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The molang environment an authored physics animation is evaluated against.
 *
 * <p>This is the second half of "run the physics the author wrote": {@link YsmSecondOrder} is the
 * filter, and this is everything the expressions feeding it are allowed to mention. YSM's own
 * physics animations are written against a small, closed vocabulary, and every name below was taken
 * from expressions in a real one rather than from a list:
 *
 * <pre>
 *   ysm.second_order('key', input, freq, coef, response)   the filter, one per author-named key
 *   ysm.head_yaw, ysm.head_pitch                           degrees, entity-local
 *   q.ground_speed, q.vertical_speed                       blocks/s
 *   q.yaw_speed                                            degrees/s
 *   v.&lt;name&gt;                                               the animation's own variables
 * </pre>
 *
 * <p>Three of these are worth stating precisely, because getting them subtly wrong would look like
 * physics that is merely mistuned rather than broken:
 *
 * <ul>
 *   <li><b>{@code q.yaw_speed} is in degrees per second</b>, and authors write things like
 *       {@code math.clamp(0.05*q.yaw_speed,-80,80)} around it - the 0.05 and the 80 only make sense
 *       against degrees.</li>
 *   <li><b>{@code q.ground_speed} and {@code q.vertical_speed} are in blocks per second</b>, i.e.
 *       the entity's per-tick movement times twenty.</li>
 *   <li><b>{@code ysm.head_yaw} and {@code ysm.head_pitch} are degrees</b>, and they are the values
 *       the head's <i>animation</i> produced, not the entity's body rotation - authors use them to
 *       keep hair out of the head, so they must lead the body rather than follow it.</li>
 * </ul>
 *
 * <p>The environment is per entity per frame and holds no state of its own except the filter bank,
 * which is the point: the filters are what carry a frame into the next one, and everything else is
 * read fresh. It is reused rather than rebuilt, because a physics animation is evaluated every frame
 * and the expressions are compiled once, not per evaluation.
 */
public final class YsmPhysicsMolangEnv implements Molang.Env {

    private final YsmSecondOrder.Bank filters = new YsmSecondOrder.Bank();
    private final java.util.HashMap<String, Double> variables = new java.util.HashMap<>();
    /** The rotations already given to bones this frame, for {@code ysm.bone_rot}. */
    private final java.util.HashMap<String, float[]> boneRotations = new java.util.HashMap<>();

    private LivingEntity entity;
    /** Degrees, from the head's own animation; set by the caller before evaluation. */
    private double headYaw;
    private double headPitch;

    /**
     * Point this environment at an entity for one evaluation.
     *
     * @param entity     the entity being drawn
     * @param headYaw    the animated head yaw in degrees
     * @param headPitch  the animated head pitch in degrees
     */
    public void begin(LivingEntity entity, double headYaw, double headPitch) {
        this.entity = entity;
        this.headYaw = headYaw;
        this.headPitch = headPitch;
    }

    /** The filter bank, so the caller can advance it once per frame before evaluating. */
    public YsmSecondOrder.Bank filters() {
        return this.filters;
    }

    /** Every author variable this animation has set, for the log. */
    public int variableCount() {
        return this.variables.size();
    }

    /** Forget everything, filters included (the model was reloaded, the entity respawned). */
    public void clear() {
        this.filters.clear();
        this.variables.clear();
        this.boneRotations.clear();
    }

    @Override
    public double getVarById(int id) {
        Double value = this.variables.get(Molang.nameOf(id));
        return value == null ? 0.0 : value;
    }

    @Override
    public boolean hasVarById(int id) {
        return this.variables.containsKey(Molang.nameOf(id));
    }

    @Override
    public void setVarById(int id, double value) {
        // The animation's own variables. YSM keeps them per entity and zeroes them when the model
        // is (re)loaded; they are how an author carries a value from one expression to the next,
        // and the shipped physics animations use them for exactly one thing - the previous frame's
        // bone rotation, so that the frame's change in it can be fed to the filter below.
        this.variables.put(Molang.nameOf(id), value);
    }

    @Override
    public double callFunction(String name, double[] args, int argCount) {
        return callMixedFunction(name, EMPTY_STRINGS, args, argCount);
    }

    /**
     * A call with strings and no numbers at all. The physics vocabulary has none - every name an
     * author's physics expression mentions is answered by {@link #callMixedFunction} - so the honest
     * answer is zero, and it is the answer that cannot make a bone move on its own.
     */
    @Override
    public double callStringFunction(String name, String[] args) {
        return 0.0;
    }

    @Override
    public boolean wantsMixedArguments() {
        return true;
    }

    /**
     * The functions themselves. {@code second_order} is the reason this class exists; the rest are
     * the engine's own motion signals, and they are answered from the entity so that an author's
     * expression means the same thing here as it does in YSM.
     */
    @Override
    public double callMixedFunction(String name, String[] strings, double[] numbers, int count) {
        switch (name) {
            case "ysm.second_order": {
                if (count < 2 || strings.length == 0 || strings[0] == null) {
                    // Without a name there is no filter to keep, and a filter that cannot be kept
                    // is not a filter: hand the input back rather than inventing a state slot. The
                    // length check matters as much as the null one - a call written with numbers
                    // only arrives here with no string array at all.
                    return count >= 2 ? numbers[1] : 0.0;
                }
                float input = (float) numbers[1];
                float frequency = count >= 3 ? (float) numbers[2] : 1.0F;
                float coefficient = count >= 4 ? (float) numbers[3] : 1.0F;
                float response = count >= 5 ? (float) numbers[4] : 1.0F;
                return this.filters.filter(Molang.idOf(strings[0]), input, frequency, coefficient, response);
            }
            case "ysm.head_yaw":
                return this.headYaw;
            case "ysm.head_pitch":
                return this.headPitch;
            case "query.ground_speed", "q.ground_speed":
                return this.horizontalSpeed();
            case "query.vertical_speed", "q.vertical_speed":
                return this.verticalSpeed();
            case "query.yaw_speed", "q.yaw_speed":
                return this.yawSpeed();
            default:
                return 0.0;
        }
    }

    @Override
    public double getQueryById(int id) {
        return callMixedFunction(Molang.nameOf(id), EMPTY_STRINGS, EMPTY_NUMBERS, 0);
    }

    /**
     * The rotation a bone has already been given this frame, in degrees, read one component at a
     * time - {@code ysm.bone_rot('BackHairB1').x}.
     *
     * <p>This is how an author's physics chains are built. A segment's expression reads the bone
     * above it and feeds the change into its own filter:
     *
     * <pre>
     *   v.HP_x = v.HP_x_0 - v.HP_x_1;                                    (a timeline, once a frame)
     *   BackHairB2.rotation.x = 10 * ysm.second_order('A1x增量', v.HP_x, 1.7, 0.5, 0);
     *   BackHairC2.rotation.x = ysm.second_order('B1x增量', ysm.bone_rot('BackHairB1').x, 2, 0.5, 0.2) - …
     * </pre>
     *
     * <p>Which means the order of evaluation inside a frame is part of the behaviour, not an
     * implementation detail: a bone's expression must see the rotations the bones before it in the
     * animation have already been given, and cannot see its own or a later one's. The caller fills
     * {@link #setBoneRotation} as it goes, exactly as YSM's own animation pass does.
     *
     * <p>The numbers are the bone's animated rotation in degrees, with the same sign convention the
     * mod's own runtime uses, so an author's arithmetic means here what it means in YSM.
     */
    @Override
    public double callVectorFunction(String name, String[] strings, double[] numbers,
                                     int count, int component) {
        if (!"ysm.bone_rot".equals(name) || component < 0 || count < 1 || strings.length == 0
                || strings[0] == null) {
            return 0.0;
        }
        float[] rotation = this.boneRotations.get(strings[0]);
        return rotation == null ? 0.0 : rotation[component];
    }

    /**
     * Record a bone's animated rotation for the expressions that come after it in this frame.
     *
     * @param boneName the bone's name, as an author would write it in {@code ysm.bone_rot('...')}
     * @param x        degrees, around the bone's own x
     * @param y        degrees, around the bone's own y
     * @param z        degrees, around the bone's own z
     */
    public void setBoneRotation(String boneName, float x, float y, float z) {
        float[] rotation = this.boneRotations.get(boneName);
        if (rotation == null) {
            rotation = new float[3];
            this.boneRotations.put(boneName, rotation);
        }
        rotation[0] = x;
        rotation[1] = y;
        rotation[2] = z;
    }

    /** Blocks per second along the ground, which is what YSM's {@code q.ground_speed} reports. */
    private double horizontalSpeed() {
        if (this.entity == null) {
            return 0.0;
        }
        Vec3 delta = this.entity.getDeltaMovement();
        if (delta == null) {
            return 0.0;
        }
        // Delta movement is blocks per tick; the query is blocks per second.
        return Math.sqrt(delta.x * delta.x + delta.z * delta.z) * 20.0;
    }

    private double verticalSpeed() {
        return this.entity == null ? 0.0 : this.entity.getDeltaMovement().y * 20.0;
    }

    /**
     * Degrees per second, taken as the tick's own yaw difference.
     *
     * <p>Across a tick rather than frame to frame, for the reason the solver's turn driver gives:
     * the yaw is quantised to ticks, so differencing it per frame produces a staircase that reads as
     * one frame of violent turning followed by one of none. The tick difference is the average turn
     * rate over that tick, which is what an author means by the quantity.
     */
    private double yawSpeed() {
        return this.entity == null ? 0.0
                : net.minecraft.util.Mth.wrapDegrees(this.entity.getYRot() - this.entity.yRotO) * 20.0;
    }

    private static final String[] EMPTY_STRINGS = new String[0];
    private static final double[] EMPTY_NUMBERS = new double[0];
}
