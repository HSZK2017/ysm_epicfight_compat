package com.ysmef.compat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the reading of a boolean system flag.
 *
 * <p>The case that matters is {@code =false}: the flags were once tested with
 * {@code getProperty(name) != null}, so an explicit {@code false} turned a feature on (or left a
 * {@code disable_*} flag disabling). These assertions fail under that rule and pass under
 * {@link SystemFlags#enabled}, which is what makes them worth having - a test that agreed with the
 * old behaviour would have celebrated the trap.
 */
class SystemFlagsTest {

    private static final String FLAG = "ysmef.test.flag";

    @AfterEach
    void clearFlag() {
        System.clearProperty(FLAG);
    }

    @Test
    void anAbsentFlagIsOff() {
        assertFalse(SystemFlags.enabled(FLAG), "no property means the flag is not set");
    }

    @Test
    void aBarePropertyIsOn() {
        System.setProperty(FLAG, "");
        assertTrue(SystemFlags.enabled(FLAG), "-Dname with no value asks for the flag");
    }

    @Test
    void explicitFalseIsOff() {
        System.setProperty(FLAG, "false");
        assertFalse(SystemFlags.enabled(FLAG), "-Dname=false must mean off, not 'the property exists'");
        System.setProperty(FLAG, "FALSE");
        assertFalse(SystemFlags.enabled(FLAG), "and not case-sensitively");
        System.setProperty(FLAG, " false ");
        assertFalse(SystemFlags.enabled(FLAG), "and not with surrounding spaces");
    }

    @Test
    void theOtherWaysofSayingNoAreOffToo() {
        for (String no : new String[]{"0", "no", "off", "OFF"}) {
            System.setProperty(FLAG, no);
            assertFalse(SystemFlags.enabled(FLAG), "'" + no + "' reads as off");
        }
    }

    @Test
    void anythingElseIsOn() {
        for (String yes : new String[]{"true", "TRUE", "1", "yes", "on", "anything"}) {
            System.setProperty(FLAG, yes);
            assertTrue(SystemFlags.enabled(FLAG), "'" + yes + "' reads as on");
        }
    }
}
