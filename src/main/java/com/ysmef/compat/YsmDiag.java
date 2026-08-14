package com.ysmef.compat;

/**
 * Central gate for the mod's diagnostic logging and per-frame timing
 * instrumentation.
 *
 * All "[diag]" logs (render-path skip reasons, per-entity render tracing,
 * per-frame timings) are OFF by default: during normal gameplay they only
 * cost render-thread time (log4j formatting + append per skipped draw, which
 * toggles between reasons every few frames with TLM maids / GUI previews).
 * Set the "ysm_ef_compat.diag" system property to enable them again.
 */
public final class YsmDiag {

    private static final boolean ENABLED = System.getProperty("ysm_ef_compat.diag") != null;

    private YsmDiag() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    // ------------------------------------------------------------------
    // Per-frame timing accumulation (render thread, diagnostic builds only)
    // ------------------------------------------------------------------

    public static final int SLOT_SCRIPT_EVAL = 0;
    public static final int SLOT_GPU_PATH = 1;
    public static final int SLOT_COMPUTE_PATH = 2;
    public static final int SLOT_CPU_PATH = 3;
    private static final int SLOT_COUNT = 4;

    private static final long[] nanos = new long[SLOT_COUNT];
    private static int draws = 0;
    private static final int REPORT_INTERVAL = 120;

    public static void addNanos(int slot, long ns) {
        if (ENABLED && slot >= 0 && slot < SLOT_COUNT) {
            nanos[slot] += ns;
        }
    }

    /**
     * Called once per mesh draw; every {@link #REPORT_INTERVAL} draws the
     * accumulated per-draw averages are logged.
     */
    public static void onMeshDrawEnd() {
        if (!ENABLED) {
            return;
        }
        if (++draws >= REPORT_INTERVAL) {
            draws = 0;
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [diag][perf] avg ms over {} mesh draws: scriptEval={} gpuPath={} computePath={} cpuPath={}",
                    REPORT_INTERVAL, ms(nanos[SLOT_SCRIPT_EVAL]), ms(nanos[SLOT_GPU_PATH]),
                    ms(nanos[SLOT_COMPUTE_PATH]), ms(nanos[SLOT_CPU_PATH]));
            for (int i = 0; i < SLOT_COUNT; i++) {
                nanos[i] = 0L;
            }
        }
    }

    private static double ms(long totalNanos) {
        return Math.round(totalNanos / 1_000_000.0 / REPORT_INTERVAL * 100.0) / 100.0;
    }
}
