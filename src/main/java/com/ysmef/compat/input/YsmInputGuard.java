package com.ysmef.compat.input;

/**
 * One-shot guard for the "GUI closed while the left mouse button was pressed"
 * case.
 *
 * YSM's AnimationRouletteScreen closes itself inside mouseClicked. Minecraft's
 * MouseHandler then re-checks the current screen after the GUI dispatch, sees
 * null, and still calls KeyMapping#click for the left button. A client-tick
 * screen check therefore arrives too late to suppress Epic Fight's attack
 * trigger. MouseHandlerMixin records the transition and this guard tells the
 * Epic Fight input mixin to drain that same click's attack triggers.
 *
 * This helper intentionally lives OUTSIDE the mixin package: Mixin forbids
 * injection handlers from directly referencing classes inside the configured
 * mixin package (com.ysmef.compat.mixin.*).
 */
public final class YsmInputGuard {

    /** How long after a GUI-closing left click attack clicks are discarded. */
    private static final long SUPPRESS_NANOS = 250_000_000L;

    private static volatile long suppressUntilNanos;

    private YsmInputGuard() {}

    /** Called from MouseHandlerMixin when a left press closes the active GUI. */
    public static void notifyGuiClosedOnLeftClick() {
        suppressUntilNanos = System.nanoTime() + SUPPRESS_NANOS;
    }

    /**
     * True only for the brief window after a left click closed a GUI. The flag
     * survives being read by multiple Epic Fight actions bound to the same
     * mouse button and expires on its own.
     */
    public static boolean consumeGuiCloseAttackClick() {
        long deadline = suppressUntilNanos;
        if (deadline != 0L && System.nanoTime() - deadline < 0L) {
            return true;
        }
        suppressUntilNanos = 0L;
        return false;
    }

    public static void invalidate() {
        suppressUntilNanos = 0L;
    }
}
