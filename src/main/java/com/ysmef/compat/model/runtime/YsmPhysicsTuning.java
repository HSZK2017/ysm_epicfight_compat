package com.ysmef.compat.model.runtime;

import com.ysmef.compat.config.YSMCompatConfig;

/**
 * The shape of the secondary-motion swing, resolved from the config once per frame.
 *
 * <p>Separate from {@link YsmPhysicsSimulator} so that class stays free of Minecraft and
 * Forge types: the dynamics can then be tested with an explicit tuning rather than
 * whatever a config file happens to hold, and a test that pins the dynamics cannot be
 * broken by someone editing their config.
 *
 * <p>Every setting is read defensively. This is a client config, so it is absent in a
 * dedicated-server process and absent again in any test that never loads Forge, and a
 * feature that silently does nothing is worse than one that runs on its defaults.
 */
public final class YsmPhysicsTuning {

    /** What the simulator was tuned to, used whenever the config cannot be read. */
    public static final YsmPhysicsTuning DEFAULTS = new YsmPhysicsTuning(
            YsmPhysicsSimulator.STIFFNESS,
            YsmPhysicsSimulator.DAMPING,
            YsmPhysicsSimulator.GRAVITY,
            YsmPhysicsSimulator.MAX_ANGLE,
            YsmPhysicsSimulator.MAX_ANGLE_ROOT);

    /** Spring toward the animated pose, 1/s^2. */
    public final double stiffness;
    /** Relative-velocity decay, 1/s. */
    public final double damping;
    /** Extra droop while moving, blocks/s^2. */
    public final double gravity;
    /** Ceiling on how far a chain may bend from its animated pose, radians. */
    public final double maxAngle;
    /** The same ceiling for the top of a hanging piece, which carries all of it. */
    public final double maxAngleRoot;

    public YsmPhysicsTuning(double stiffness, double damping, double gravity,
                            double maxAngle, double maxAngleRoot) {
        this.stiffness = stiffness;
        this.damping = damping;
        this.gravity = gravity;
        this.maxAngle = maxAngle;
        this.maxAngleRoot = maxAngleRoot;
    }

    /**
     * How many bones of one model may swing, or {@link #AUTO} when the model decides for itself.
     *
     * <p>The classification already answers the question a limit was invented to guess at: it looks
     * at every bone, keeps the ones that read as hanging cloth or hair, drops the containers, and
     * ends up with the pieces this model actually has. A number in a config file cannot know that -
     * it is the same number for a two-bone fringe and for a garment of forty panels - and when it is
     * too small it does not degrade gracefully: whole pieces are left out, so part of a skirt swings
     * and the rest is bolted to the pose, which reads as the garment coming apart.
     *
     * <p>So the limit is off by default and the classification is the limit. A positive value is
     * still honoured for anyone who wants to bound the per-frame cost on a model they know to be
     * pathological, and the two former defaults are read as "unset" - a config written by an older
     * build carries one of them, and it was never a decision anybody made.
     */
    public static int maxChains() {
        int configured;
        try {
            configured = YSMCompatConfig.SECONDARY_MOTION_MAX_CHAINS.get();
        } catch (Throwable t) {
            return AUTO;
        }
        return configured == FORMER_DEFAULT_CHAINS || configured == 96 ? AUTO : configured;
    }

    /** No limit: every piece the classification finds is simulated. */
    public static final int AUTO = Integer.MAX_VALUE;

    /** The default this key shipped with before it became a limit rather than a guess. */
    private static final int FORMER_DEFAULT_CHAINS = 24;

    /** Whether the model's own classification is what decides, rather than a configured number. */
    public static boolean maxChainsIsAutomatic() {
        return maxChains() == AUTO;
    }

    /**
     * The spring frequency the stiffness setting denotes, Hz.
     *
     * <p>{@code omega = sqrt(k)} for a unit-mass oscillator, so this is
     * {@code sqrt(stiffness) / 2pi} - the number the pendulum solver needs, and the same
     * number a model author writes into {@code ysm.second_order}. Keeping the config in
     * {@code 1/s^2} and converting here is what lets an existing config file keep its
     * meaning now that the dynamics are a pendulum rather than a point spring.
     */
    public double frequency() {
        if (!(stiffness > 0.0)) {
            return 1.0;
        }
        return Math.sqrt(stiffness) / (2.0 * Math.PI);
    }

    /**
     * The damping ratio the damping setting denotes.
     *
     * <p>{@code zeta = c / (2 sqrt(k))} for a unit-mass oscillator; 1.0 is critical
     * damping. The old simulator applied {@code c} as an absolute velocity decay, which
     * for the default 24 against a stiffness of 220 is a ratio of 0.81 - a swing that
     * settles in about a second, which is what the config's comment promises.
     */
    public double dampingRatio() {
        if (!(stiffness > 0.0)) {
            return 1.0;
        }
        return Math.max(0.0, damping / (2.0 * Math.sqrt(stiffness)));
    }

    /**
     * Downward acceleration on every swinging piece, blocks/s^2.
     *
     * <p>Separate from {@link #gravity}, which is a field of the older point-spring model with
     * a different meaning ("extra droop while moving"), a different default, and its own
     * config key. This is real gravity, and it is what makes a piece hang.
     */
    public static double gravityAcceleration() {
        try {
            return finite(YSMCompatConfig.SECONDARY_MOTION_GRAVITY_ACCELERATION.get(),
                    YsmDynamicBoneSolver.GRAVITY);
        } catch (Throwable t) {
            return YsmDynamicBoneSolver.GRAVITY;
        }
    }

    /** How strongly the air pushes a swinging piece, 1/(blocks/s). */
    public static double airDrag() {
        try {
            return finite(YSMCompatConfig.SECONDARY_MOTION_AIR_DRAG.get(), YsmDynamicBoneSolver.AIR_DRAG);
        } catch (Throwable t) {
            return YsmDynamicBoneSolver.AIR_DRAG;
        }
    }

    /** Whether swinging pieces are kept out of the model's own body volumes. */
    public static boolean collisionEnabled() {
        try {
            return YSMCompatConfig.SECONDARY_MOTION_COLLISION.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The current settings, with the defaults standing in for anything unreadable or not
     * finite.
     *
     * <p>A non-finite value would propagate into the integrator and freeze or explode
     * every chain at once, so it is rejected here rather than trusted.
     */
    public static YsmPhysicsTuning current() {
        try {
            return new YsmPhysicsTuning(
                    finite(YSMCompatConfig.SECONDARY_MOTION_STIFFNESS.get(), DEFAULTS.stiffness),
                    finite(YSMCompatConfig.SECONDARY_MOTION_DAMPING.get(), DEFAULTS.damping),
                    finite(YSMCompatConfig.SECONDARY_MOTION_GRAVITY.get(), DEFAULTS.gravity),
                    degrees(YSMCompatConfig.SECONDARY_MOTION_MAX_ANGLE_DEGREES.get(), DEFAULTS.maxAngle),
                    degrees(YSMCompatConfig.SECONDARY_MOTION_MAX_ANGLE_ROOT_DEGREES.get(),
                            DEFAULTS.maxAngleRoot));
        } catch (Throwable t) {
            return DEFAULTS;
        }
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    /** A configurable angle in degrees, kept as radians by the dynamics. */
    private static double degrees(double value, double fallbackRadians) {
        return Math.toRadians(finite(value, Math.toDegrees(fallbackRadians)));
    }

    @Override
    public String toString() {
        return "stiffness=" + stiffness + " damping=" + damping + " gravity=" + gravity
                + " maxAngleDeg=" + Math.toDegrees(maxAngle)
                + " maxAngleRootDeg=" + Math.toDegrees(maxAngleRoot);
    }
}
