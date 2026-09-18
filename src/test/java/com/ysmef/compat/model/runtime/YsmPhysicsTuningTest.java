package com.ysmef.compat.model.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void theDefaultsMirrorTheSimulatorConstants() {
        assertEquals(YsmPhysicsSimulator.STIFFNESS, YsmPhysicsTuning.DEFAULTS.stiffness, 1.0E-6);
        assertEquals(YsmPhysicsSimulator.DAMPING, YsmPhysicsTuning.DEFAULTS.damping, 1.0E-6);
        assertEquals(YsmPhysicsSimulator.GRAVITY, YsmPhysicsTuning.DEFAULTS.gravity, 1.0E-6);
        assertEquals(YsmPhysicsSimulator.MAX_ANGLE, YsmPhysicsTuning.DEFAULTS.maxAngle, 1.0E-6);
        assertEquals(YsmPhysicsSimulator.MAX_ANGLE_ROOT, YsmPhysicsTuning.DEFAULTS.maxAngleRoot, 1.0E-6);
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
        YsmPhysicsTuning custom = new YsmPhysicsTuning(400.0, 12.0, 0.0, 0.5, 0.1);

        assertEquals(400.0, custom.stiffness, 1.0E-6);
        assertEquals(12.0, custom.damping, 1.0E-6);
        assertEquals(0.0, custom.gravity, 1.0E-6);
        assertEquals(0.5, custom.maxAngle, 1.0E-6);
        assertEquals(0.1, custom.maxAngleRoot, 1.0E-6);
        assertNotNull(custom.toString(), "the tuning is logged, so it must describe itself");
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
}
