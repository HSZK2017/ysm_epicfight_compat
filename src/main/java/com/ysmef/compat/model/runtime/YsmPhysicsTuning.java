package com.ysmef.compat.model.runtime;

import com.ysmef.compat.config.YSMCompatConfig;

/**
 * The shape of the secondary-motion swing, resolved from the config once per frame.
 *
 * <p>Free of Minecraft and Forge types, and of the dynamics themselves: the pendulum
 * solver can then be driven with an explicit tuning rather than whatever a config file
 * happens to hold, and a test that pins the dynamics cannot be broken by someone editing
 * their config.
 *
 * <p>Every setting is read defensively. This is a client config, so it is absent in a
 * dedicated-server process and absent again in any test that never loads Forge, and a
 * feature that silently does nothing is worse than one that runs on its defaults.
 */
public final class YsmPhysicsTuning {

    /**
     * What every piece starts from, used whenever the config cannot be read.
     *
     * <p>These are the fallbacks, not a second copy of the settings: every one of them is the
     * default of the config key the matching getter reads, which is the only arrangement in which
     * "the config could not be read" and "the config says nothing" give the same answer. They are
     * written out here rather than read off a simulator class because this file must stay free of
     * the dynamics - the {@code YsmPhysicsSimulator} these five numbers were once taken from is
     * gone, and the two constants below have been through the pendulum rewrite's own
     * recomputation of them:
     *
     * <ul>
     *   <li>{@code 220.0} 1/s^2 is the stiffness key's default, and the same number the model
     *       authors' {@code ysm.second_order} default carries.</li>
     *   <li>{@code 24.0} is the damping key's default; against 220 that is the ratio 0.81 the
     *       config comment promises, so a swing settles in about a second.</li>
     *   <li>{@code 8.0} is the <i>retired</i> droop key's default, kept only so a config that
     *       still carries it is read into the same field. The dynamics use
     *       {@link #gravityAcceleration()} - real gravity, 24 blocks/s^2 - and say so in
     *       {@link #toString()}.</li>
     *   <li>The two angles are expressed in degrees because that is the unit the config and the
     *       comments use, the unit a person tunes in, and the unit the per-joint ceiling is
     *       specified in. Degrees are converted here, at the one place the default enters.</li>
     * </ul>
     */
    public static final YsmPhysicsTuning DEFAULTS = new YsmPhysicsTuning(
            220.0,
            24.0,
            8.0,
            Math.toRadians(60.0),
            Math.toRadians(20.0));

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

    /**
     * The user's own scaling of every piece's gravity-follow weight, 0..1.
     *
     * <p>A scale on the classification's per-category weights rather than a weight of its own, and
     * the difference matters: a single number for every piece would make the mechanism unusable on
     * either hair or cloth, because the two want opposite things - a skirt panel is meant to leave
     * the body's axis and hang toward the ground, while a lock of hair grows out of a skull, has its
     * own volume, and pointing every strand straight down is what makes a hairdo look wet. The
     * classification decides which of the two a piece is (see
     * {@code YsmPhysicsParts.Segment#verticalFollow}); this is the one knob that moves all of them
     * together, and 0 is the escape hatch back to the pre-existing behaviour.
     */
    public static double gravityFollowScale() {
        try {
            double configured = YSMCompatConfig.SECONDARY_MOTION_GRAVITY_FOLLOW.get();
            // A client config is the one thing here that is not a number the caller controls, and a
            // NaN would reach the solver's clamp as a NaN. Outside 0..1 it would extrapolate the
            // spring's target past the world's vertical and pull the cloth upwards.
            if (!Double.isFinite(configured)) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, configured));
        } catch (Throwable t) {
            return 1.0;
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
