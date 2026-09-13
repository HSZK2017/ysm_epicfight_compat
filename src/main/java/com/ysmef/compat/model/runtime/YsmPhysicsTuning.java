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
     * How many bones of one model may swing. Zero turns the classification off, which is
     * a cleaner way to measure the feature's cost than disabling it outright.
     */
    public static int maxChains() {
        try {
            return YSMCompatConfig.SECONDARY_MOTION_MAX_CHAINS.get();
        } catch (Throwable t) {
            return YsmPhysicsChains.DEFAULT_MAX_CHAINS;
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
