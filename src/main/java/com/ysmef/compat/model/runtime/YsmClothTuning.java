package com.ysmef.compat.model.runtime;

import com.ysmef.compat.config.YSMCompatConfig;

/**
 * The cloth solve's shape, resolved from the config.
 *
 * <p>Separate from {@link YsmClothSolver} so that class stays free of Forge types and its
 * dynamics can be tested with explicit values; how cloth should look is a matter of taste,
 * and a test that pins the dynamics must not be broken by someone editing their config.
 *
 * <p>Every setting is read defensively. This is a client config, so it is absent in a
 * dedicated-server process and in any test that never loads Forge, and a feature that
 * silently does nothing is worse than one running on its defaults.
 */
public final class YsmClothTuning {

    /** What the solver was tuned to, used whenever the config cannot be read. */
    public static final YsmClothTuning DEFAULTS = new YsmClothTuning(
            YsmClothSolver.DEFAULT_GRAVITY,
            YsmClothSolver.DEFAULT_DAMPING,
            YsmClothSolver.DEFAULT_ITERATIONS,
            YsmClothSolver.DEFAULT_BODY_RADIUS);

    /** Gravity, blocks/s^2. */
    public final float gravity;
    /** Velocity kept between frames, 0..1. */
    public final float damping;
    /** Constraint iterations per step. */
    public final int iterations;
    /** Radius of the body spheres the cloth is kept out of, blocks. */
    public final float bodyRadius;

    public YsmClothTuning(float gravity, float damping, int iterations, float bodyRadius) {
        this.gravity = gravity;
        this.damping = damping;
        this.iterations = iterations;
        this.bodyRadius = bodyRadius;
    }

    /** How many particles one model may simulate, from the config or the default. */
    public static int maxParticles() {
        try {
            return YSMCompatConfig.SECONDARY_MOTION_MAX_PARTICLES.get();
        } catch (Throwable t) {
            return YsmClothSolver.DEFAULT_MAX_PARTICLES;
        }
    }

    /** The current settings, with the defaults standing in for anything unreadable. */
    public static YsmClothTuning current() {
        try {
            return new YsmClothTuning(
                    finite(YSMCompatConfig.SECONDARY_MOTION_GRAVITY.get(), DEFAULTS.gravity),
                    finite(YSMCompatConfig.SECONDARY_MOTION_DAMPING.get(), DEFAULTS.damping),
                    YSMCompatConfig.SECONDARY_MOTION_ITERATIONS.get(),
                    finite(YSMCompatConfig.SECONDARY_MOTION_BODY_RADIUS.get(), DEFAULTS.bodyRadius));
        } catch (Throwable t) {
            return DEFAULTS;
        }
    }

    /**
     * A finite value, or the fallback.
     *
     * <p>A non-finite gravity or damping would propagate into every particle at once, so it
     * is rejected here rather than trusted.
     */
    private static float finite(double value, float fallback) {
        return Double.isFinite(value) ? (float) value : fallback;
    }

    @Override
    public String toString() {
        return "gravity=" + gravity + " damping=" + damping
                + " iterations=" + iterations + " bodyRadius=" + bodyRadius;
    }
}
