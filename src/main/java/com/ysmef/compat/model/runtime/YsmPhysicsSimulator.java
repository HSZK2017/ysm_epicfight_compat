package com.ysmef.compat.model.runtime;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Secondary motion for the hanging parts of a model - hair, tails, skirts, capes -
 * so they swing after the body instead of being glued to it.
 *
 * <p>Each chain behaves as a damped spring toward the pose the animation asks for: the
 * chain's tip is pulled back to where it hangs at rest ({@code stiffness}), its own
 * velocity is bled off ({@code damping}), and gravity adds a droop that grows while the
 * body moves. The result is a rotation about the chain's pivot, which the caller folds
 * into the pose as an extra delta alongside the animation's own.
 *
 * <p>Ported from EpicYSM's {@code PhysicsAnimator} (MIT), keeping its tuned constants
 * and two of its decisions: a chain root carries the whole hairdo or skirt and stays
 * firm while a skirt panel pushed by a knee may swing right up, and the time step is
 * clamped so a lag spike cannot fling the chain.
 *
 * <p><b>The chain's reach is fixed at bind time.</b> The tip is held on a sphere of that
 * one radius, taken when the chain was first seen. Deriving the radius from the rest tip
 * of the moment looks equivalent and is not: the rest tip moves with the animation, so
 * the sphere would grow with it and the chain would stretch instead of swinging - which
 * on screen is hair pulled across the model rather than hair that lags behind it.
 *
 * <p><b>Frames.</b> The dynamics are all in model bind space, which is where the caller
 * composes pose deltas. Because the caller applies the rotation as part of a bone's
 * <i>local</i> transform, the model-space rotation is conjugated into the bone's local
 * bind frame by {@link #toLocal} before use.
 *
 * <p>Deliberately free of Minecraft types: the caller supplies the pivot and the rest
 * tip, so the dynamics can be tested without a game.
 */
public final class YsmPhysicsSimulator {

    /** Spring toward the animated pose, 1/s^2. */
    public static final float STIFFNESS = 220.0F;
    /** Relative-velocity decay, 1/s. */
    public static final float DAMPING = 24.0F;
    /** Extra droop while moving, blocks/s^2. */
    public static final float GRAVITY = 8.0F;
    /** Radians a chain segment may bend. */
    public static final float MAX_ANGLE = 1.05F;
    /** Chain roots hold the whole hairdo or skirt: stay firm. */
    public static final float MAX_ANGLE_ROOT = 0.35F;
    /** Clamp for lag spikes, seconds. */
    private static final float MAX_DT = 0.05F;
    /** Below this a quantity counts as zero. */
    private static final float EPSILON = 1.0E-4F;

    /** One chain's persistent state. */
    public static final class ChainState {
        /** Where the tip is now, in model space. */
        final Vector3f tip = new Vector3f();
        final Vector3f velocity = new Vector3f();
        /** The chain's fixed reach, taken from the bind pose; see the class comment. */
        float segment;
        boolean initialized;
    }

    /** One simulator; the per-chain state lives in the callers' {@link ChainState}s. */
    public static final YsmPhysicsSimulator INSTANCE = new YsmPhysicsSimulator();

    /**
     * The angle of the swing produced by the last {@link #update}, in radians.
     *
     * <p>Exposed because "the physics is not working" and "the physics is producing a
     * rotation too small to see" look identical on screen, and only this tells them
     * apart. Zero for a chain that is at rest or exactly opposed.
     */
    public float lastAngleRad;

    /** How far the tip was from where the pose asks it to be, in blocks. */
    public float lastTipError;

    // Reused scratch, so a per-frame update allocates nothing.
    private final Vector3f aim = new Vector3f();
    private final Vector3f accel = new Vector3f();
    private final Vector3f dir = new Vector3f();
    private final Vector3f cross = new Vector3f();

    private YsmPhysicsSimulator() {}

    /**
     * Advance one chain by {@code dt} and produce the swing rotation about its pivot.
     *
     * <p>The returned rotation is in <b>model space</b>; a caller folding it into a
     * bone's local transform must convert it with {@link #toLocal}.
     *
     * @param state   this chain's persistent state
     * @param pivot   the chain's pivot in model space (the bone's bind pivot)
     * @param restTip where the tip hangs at rest right now, in model space
     * @param chain   the chain's classification, for its swing limit
     * @param dt      seconds since the last update (clamped internally)
     * @param out     receives the swing rotation; identity when there is no swing
     */
    public void update(ChainState state, Vector3f pivot, Vector3f restTip,
                       YsmPhysicsChains.Chain chain, float dt, Quaternionf out) {
        update(state, pivot, restTip, chain, dt, YsmPhysicsTuning.DEFAULTS, out);
    }

    /**
     * Advance one chain by {@code dt} and produce the swing rotation about its pivot,
     * with an explicit tuning.
     *
     * <p>The tuning is a parameter rather than a field read from the config here, so the
     * dynamics stay testable and this class stays free of Forge types; the caller
     * resolves it once per frame.
     *
     * @param tuning  the spring's shape; null falls back to the tuned defaults
     */
    public void update(ChainState state, Vector3f pivot, Vector3f restTip,
                       YsmPhysicsChains.Chain chain, float dt, YsmPhysicsTuning tuning,
                       Quaternionf out) {
        out.identity();
        this.lastAngleRad = 0.0F;
        this.lastTipError = 0.0F;
        if (state == null || pivot == null || restTip == null || chain == null) {
            return;
        }
        YsmPhysicsTuning shape = tuning == null ? YsmPhysicsTuning.DEFAULTS : tuning;

        dir.set(restTip).sub(pivot);
        float reach = dir.length();

        if (!state.initialized) {
            // The bind pose decides how long this chain is; see the class comment.
            state.segment = reach;
            state.tip.set(restTip);
            state.velocity.zero();
            state.initialized = true;
            return;
        }

        if (!Float.isFinite(reach) || reach < EPSILON || state.segment < EPSILON) {
            // No lever to rotate about, or a rest pose that collapsed.
            return;
        }

        float step = Math.min(dt, MAX_DT);
        if (!(step > 0.0F) || !Float.isFinite(step)) {
            return;
        }

        // Spring toward where the animation wants the tip, damped by the tip's own
        // velocity. The aim point is pulled onto the chain's fixed sphere first, so a
        // rest pose that has moved outward cannot lengthen the chain.
        aim.set(restTip).sub(pivot).mul(state.segment / reach).add(pivot);
        this.lastTipError = aim.distance(state.tip);
        accel.set(aim).sub(state.tip).mul((float) shape.stiffness)
                .fma((float) -shape.damping, state.velocity);
        // Gravity droops the chain, scaled by how fast it is already moving: a standing
        // character's hair should hang exactly as the author posed it.
        accel.y -= (float) shape.gravity * Math.min(1.0F, state.velocity.length());

        state.velocity.fma(step, accel);
        state.tip.fma(step, state.velocity);

        // Hold the tip on its sphere. Letting it drift outward is what turns one bad
        // frame into a visible whip, because a longer chain swings further.
        dir.set(state.tip).sub(pivot);
        float reached = dir.length();
        if (reached > state.segment) {
            dir.mul(state.segment / reached);
            state.tip.set(pivot).add(dir);
        } else if (reached < EPSILON) {
            state.tip.set(aim);
        }

        // The swing is the rotation taking the rest direction onto the current one.
        dir.set(state.tip).sub(pivot).normalize();
        aim.sub(pivot).normalize();
        dir.cross(aim, cross);

        float crossLen = cross.length();
        float dot = Math.max(-1.0F, Math.min(1.0F, dir.x * aim.x + dir.y * aim.y + dir.z * aim.z));
        if (crossLen < EPSILON) {
            // Parallel: at rest (nothing to do), or exactly opposed, which has no single
            // shortest axis and is left as identity rather than guessed at.
            return;
        }
        cross.div(crossLen);
        float angle = (float) Math.atan2(crossLen, dot);

        float limit = chain.chainRoot() ? (float) shape.maxAngleRoot : (float) shape.maxAngle;
        if (angle > limit) {
            angle = limit;
        }
        this.lastAngleRad = angle;
        out.fromAxisAngleRad(cross.x, cross.y, cross.z, angle);
    }

    /**
     * Convert a model-space rotation into the bone's local bind frame.
     *
     * <p>The caller composes the result as part of the bone's local transform, so the
     * rotation has to be expressed in that frame: for a rotation {@code r} and the
     * bone's bind rotation {@code b}, the local form is {@code b⁻¹ · r · b}. Without
     * this the swing is applied about the model's axes instead of the bone's, which for
     * a bone whose bind frame is rotated - a tail hanging backwards, a skirt panel
     * angled outward - bends it the wrong way.
     *
     * @param out     receives the local-frame rotation
     * @param model   the model-space rotation from {@link #update}
     * @param bindRot the bone's bind rotation (identity when the bone is upright)
     */
    public static void toLocal(Quaternionf out, Quaternionf model, Quaternionf bindRot) {
        if (bindRot == null) {
            out.set(model);
            return;
        }
        bindRot.conjugate(out);
        out.mul(model).mul(bindRot);
    }

    /** The rotation part of a bind matrix, as a quaternion. */
    public static void rotationOf(Matrix4f matrix, Quaternionf out) {
        if (matrix == null) {
            out.identity();
            return;
        }
        out.setFromUnnormalized(matrix);
        if (!Float.isFinite(out.w()) || out.lengthSquared() < EPSILON) {
            out.identity();
        } else {
            out.normalize();
        }
    }
}
