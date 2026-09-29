package com.ysmef.compat.model.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the settings that shape the secondary-motion swing.
 *
 * <p>The unit tested here is the conversion, not the taste: how many degrees a user writes
 * in the config is not how many radians the dynamics take, and a mismatch is invisible
 * until someone sets the value to its minimum and the pieces still move.
 *
 * <p>{@link YsmPhysicsTuning#current()} cannot be exercised without Forge, which is why
 * the fallback matters: it is what runs in a dedicated server, in a test, and in any
 * client where the config failed to load.
 */
class YsmPhysicsTuningTest {

    /**
     * The fallbacks, as literals, and that they are the values the config documents.
     *
     * <p>These four numbers were once asserted against {@code YsmPhysicsSimulator}'s own constants,
     * and the two are the same numbers: 220 1/s^2, 24, and the 60 and 20 degrees the config comments
     * promise. The simulator class is gone - it was the point-spring model the pendulum solver
     * replaced, and its constants were the only reason it was still referenced - so the values are
     * pinned here directly. Pinned as literals rather than derived, because a test that computed them
     * the way the code does would agree with the code by construction and catch nothing.
     *
     * <p>The retired droop value used to be asserted here too. It is not a setting of these dynamics
     * - the pendulum reads its own gravity key - so pinning it made a key that nothing integrates
     * look tested. See {@link #theLogLineReportsTheGravityTheDynamicsUse()}.
     */
    @Test
    void theDefaultsAreTheDocumentedValues() {
        assertEquals(220.0, YsmPhysicsTuning.DEFAULTS.stiffness, 1.0E-6,
                "the stiffness key's default, and the authors' ysm.second_order default");
        assertEquals(24.0, YsmPhysicsTuning.DEFAULTS.damping, 1.0E-6,
                "the damping key's default: 0.81 of critical against 220");
        assertEquals(Math.toRadians(60.0), YsmPhysicsTuning.DEFAULTS.maxAngle, 1.0E-6,
                "secondaryMotionMaxAngleDegrees");
        assertEquals(Math.toRadians(20.0), YsmPhysicsTuning.DEFAULTS.maxAngleRoot, 1.0E-6,
                "secondaryMotionMaxAngleRootDegrees");
    }

    /**
     * A root carries the whole hairdo or skirt, so its own swing is what everything under
     * it multiplies against. If it were not the smaller of the two, the top of a piece
     * would swing as far as its tips and the whole thing would look unhinged rather than
     * trailing.
     */
    @Test
    void aChainRootSwingsLessThanThePiecesUnderIt() {
        assertTrue(YsmPhysicsTuning.DEFAULTS.maxAngleRoot < YsmPhysicsTuning.DEFAULTS.maxAngle,
                "the top of a hanging piece must be held firmer than its tips");
    }

    /**
     * The values the config documents, in the units the config documents them in: the
     * degrees in the comments have to be the radians the dynamics use.
     */
    @Test
    void theDocumentedDegreesAreTheRadiansInUse() {
        assertEquals(60.0, Math.toDegrees(YsmPhysicsTuning.DEFAULTS.maxAngle), 0.5,
                "the per-piece limit is documented as 60 degrees");
        assertEquals(20.0, Math.toDegrees(YsmPhysicsTuning.DEFAULTS.maxAngleRoot), 0.5,
                "the chain-root limit is documented as 20 degrees");
    }

    /** The config's own fallback: an unreadable client config leaves these in place. */
    @Test
    void anExplicitTuningIsKept() {
        YsmPhysicsTuning custom = new YsmPhysicsTuning(400.0, 12.0, 0.5, 0.1);

        assertEquals(400.0, custom.stiffness, 1.0E-6);
        assertEquals(12.0, custom.damping, 1.0E-6);
        assertEquals(0.5, custom.maxAngle, 1.0E-6);
        assertEquals(0.1, custom.maxAngleRoot, 1.0E-6);
    }

    /**
     * The log line reports the gravity the dynamics integrate, not a stored field.
     *
     * <p>It used to print the value of the retired {@code secondaryMotionGravity} key, which the
     * pendulum never reads - so the one number a user tunes from the log was a number that did
     * nothing. Asserting the printed value against {@link YsmPhysicsTuning#gravityAcceleration()}
     * is what keeps the two from drifting apart again; the assertion can fail if the line goes back
     * to reporting a field of its own.
     */
    @Test
    void theLogLineReportsTheGravityTheDynamicsUse() {
        YsmPhysicsTuning custom = new YsmPhysicsTuning(400.0, 12.0, 0.5, 0.1);
        String line = custom.toString();

        assertTrue(line.contains("gravity=" + YsmPhysicsTuning.gravityAcceleration()),
                "the logged gravity must be the one the pendulum uses (" + YsmPhysicsTuning.gravityAcceleration()
                        + "), got: " + line);
        assertTrue(line.contains("stiffness=400.0"), "the line still describes the tuning: " + line);
    }

    /**
     * The limit answers one of three things, and never nonsense.
     *
     * <p>It used to be a cap with a default, and the test for it asserted a ceiling. The limit is
     * now the model's own classification unless a config says otherwise (see
     * {@code YsmPhysicsTuning#maxChains}), so what has to survive a missing or corrupted config is
     * the shape of the answer: automatic, off, or a positive number inside the range the config
     * declares - never a negative number dressed up as a limit, and never an automatic answer that
     * disagrees with itself.
     */
    @Test
    void theChainLimitIsAutomaticOrAConfiguredCap() {
        int cap = YsmPhysicsTuning.maxChains();

        if (cap == YsmPhysicsTuning.AUTO) {
            assertTrue(YsmPhysicsTuning.maxChainsIsAutomatic(),
                    "the two answers about the same setting must agree");
            return;
        }
        assertTrue(cap >= 0, "a negative limit is not a limit");
        assertTrue(cap <= 512, "a limit above the config's own range is not this setting");
        assertTrue(!YsmPhysicsTuning.maxChainsIsAutomatic(),
                "a configured limit is not an automatic one");
    }

    /**
     * The chain limit's rule, over values rather than over the config.
     *
     * <p>96 is the case that was wrong. It is the default the last build shipped, so every config
     * file that was generated rather than written carries it, and the rule read it as "unlimited" -
     * while this key's own config description promised a backstop. A user who writes 96 means 96.
     */
    @Test
    void theChainLimitRuleHonoursAConfiguredCap() {
        assertEquals(YsmPhysicsTuning.AUTO, YsmPhysicsTuning.maxChainsFor(-1),
                "-1 is the documented automatic value");
        assertEquals(YsmPhysicsTuning.AUTO, YsmPhysicsTuning.maxChainsFor(24),
                "the older build's default was not a decision, and honouring it would truncate garments");
        assertEquals(96, YsmPhysicsTuning.maxChainsFor(96),
                "the last build's default sits above the range real models use, so it is a real cap");
        assertEquals(1, YsmPhysicsTuning.maxChainsFor(1));
        assertEquals(512, YsmPhysicsTuning.maxChainsFor(512));
        assertEquals(0, YsmPhysicsTuning.maxChainsFor(0),
                "0 disables secondary motion; it is not an automatic answer");
    }
}
