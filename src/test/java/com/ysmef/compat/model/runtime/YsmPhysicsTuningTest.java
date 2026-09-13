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
     * The chain cap is read from the config too, and has to survive its absence: a zero
     * or nonsense cap would either disable the feature or let a pathological model build
     * thousands of chains per frame.
     */
    @Test
    void theChainCapFallsBackToADefault() {
        int cap = YsmPhysicsTuning.maxChains();

        assertTrue(cap >= 0, "a negative cap is not a cap");
        assertTrue(cap <= 128, "the config's own maximum is the ceiling of the fallback too");
    }
}
