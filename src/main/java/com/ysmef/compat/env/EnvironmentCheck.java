package com.ysmef.compat.env;

import com.ysmef.compat.SystemFlags;
import com.ysmef.compat.YSMEpicFightCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Startup report on whether the runtime environment looks like one this mod was
 * actually built and tested for - and loud warnings when it does not.
 *
 * <p>Why this exists: the mod was designed against desktop Java Minecraft, but a
 * large share of real-world installs turned out to be Android devices running
 * the Java edition through launchers such as PojavLauncher, Fold Craft Launcher
 * (FCL) and Zalith, or through Windows-on-Android emulation layers (Winlator
 * and friends). Those environments differ from the tested one in exactly the
 * ways this mod is sensitive to - the GL context is OpenGL ES or a translation
 * layer (gl4es, ANGLE, zink, VirGL), the JVM heap is small, and the CPU may be
 * 32-bit - and the resulting failures look like mod bugs while reproducing on
 * no desktop machine. There is no Android test rig here, so the one thing this
 * mod can do is make the environment visible in the log: when a user report
 * arrives with "it crashes/renders wrong on my phone", the first lines of the
 * log should already say what kind of host and GL context it was, and should
 * already have warned the user that the environment is outside the tested
 * envelope.
 *
 * <p>Two probes, because the facts become available at different times:
 * <ul>
 *   <li>{@link #reportHost()} - host facts (OS, architecture, launcher
 *       fingerprints, heap limit). Needs no GL context; called from client
 *       setup on any thread.</li>
 *   <li>{@link #reportGlContext()} - GL facts (ES vs desktop, version floor,
 *       wrapper/software renderers). Must run on the render thread with a live
 *       context; called via {@code enqueueWork} from client setup.</li>
 * </ul>
 *
 * <p>The thresholds mirror the real floors of the render paths rather than
 * inventing new ones: the CPU skinning path needs desktop GL 3.3 or OpenGL ES
 * 3.0 ({@link com.ysmef.compat.cpu.YsmCpuSkinShader}), the GPU path needs GL
 * 4.3 or ES 3.1 ({@link com.ysmef.compat.gpu.YsmGpuCapability}). Below the CPU
 * floor nothing in this mod can draw a converted mesh, which is worth saying in
 * words instead of letting every path silently report "unavailable".
 *
 * <p>Warning-only by design: nothing here changes behaviour, gates a feature or
 * disables a path - the capability probes above keep doing that per path. A
 * wrong guess in a heuristic therefore costs a spurious log line, not a broken
 * feature. {@code -Dysm_ef_compat.disable_env_check} silences the whole report
 * (via {@link SystemFlags}, so {@code =false} re-enables it as written).
 *
 * <p>The classification helpers are static and Minecraft-free so plain unit
 * tests can hold the heuristics; only the two {@code report*} entry points
 * touch the live environment.
 */
public final class EnvironmentCheck {

    /** Opt-out flag, same family as ysm_ef_compat.disable_gpu. */
    private static final String DISABLE_FLAG = "ysm_ef_compat.disable_env_check";

    /**
     * Environment-variable prefixes exported by known Android launchers.
     * PojavLauncher exports POJAV_* (Fold Craft Launcher and Zalith are Pojav
     * descendants and keep or extend that set with their own prefixes); Termux
     * sets TERMUX_VERSION. Matching on prefix instead of exact names keeps this
     * working across launcher versions that add or rename individual variables.
     */
    private static final String[] ANDROID_LAUNCHER_ENV_PREFIXES = {
            "POJAV_", "FCL_", "ZALITH_", "TERMUX_"
    };

    /** 32-bit architectures: a 4 GiB address space is below what this mod plus Minecraft needs headroom for. */
    private static final Set<String> BIT32_ARCHES = Set.of("arm", "armv7l", "i386", "i486", "i586", "i686", "x86");

    /** Below this heap limit, model conversion plus Epic Fight is likely to OOM or GC-stall. */
    private static final long LOW_HEAP_BYTES = 1536L * 1024 * 1024;

    private EnvironmentCheck() {}

    // ------------------------------------------------------------------
    // Entry points (touch the live environment, log the verdict)
    // ------------------------------------------------------------------

    /** Host-side probe; safe on any thread, no GL context required. */
    public static void reportHost() {
        if (SystemFlags.enabled(DISABLE_FLAG)) {
            return;
        }
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");
        String javaHome = System.getProperty("java.home", "");
        String userHome = System.getProperty("user.home", "");
        long maxHeap = Runtime.getRuntime().maxMemory();

        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: environment: os={} arch={} java={} ({}) maxHeap={}MiB",
                osName, osArch,
                System.getProperty("java.version", "?"),
                System.getProperty("java.vm.name", "?"),
                maxHeap / (1024 * 1024));

        for (String warning : hostWarnings(osName, osArch, javaHome, userHome,
                System.getenv().keySet(), maxHeap)) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: {}", warning);
        }
    }

    /** GL-side probe; must run on the render thread with a live context (enqueueWork from client setup). */
    public static void reportGlContext() {
        if (SystemFlags.enabled(DISABLE_FLAG)) {
            return;
        }
        String glVersion;
        String glRenderer;
        String glVendor;
        try {
            glVersion = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            glRenderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            glVendor = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
        } catch (Throwable t) {
            // No context / wrong thread: the per-path capability probes will say
            // the same thing later; do not crash client setup over a report.
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: environment GL probe skipped ({})", t.toString());
            return;
        }

        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: GL context: version=\"{}\" renderer=\"{}\" vendor=\"{}\"",
                glVersion, glRenderer, glVendor);

        for (String warning : glWarnings(glVersion, glRenderer)) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: {}", warning);
        }
    }

    // ------------------------------------------------------------------
    // Pure classification (no Minecraft, no LWJGL - unit-testable)
    // ------------------------------------------------------------------

    /**
     * Host-side warnings from plain system facts.
     *
     * <p>Android detection is two-tiered on purpose. App-sandbox paths
     * ({@code /data/...} as java.home or user.home) and launcher environment
     * variables are definite - no desktop OS puts a JVM there. Linux on an ARM
     * CPU alone is only a strong hint (ARM Linux desktops exist), so it gets a
     * softer "possibly Android" line instead of a false certainty.
     */
    static List<String> hostWarnings(String osName, String osArch, String javaHome,
                                     String userHome, Set<String> envVarNames, long maxHeapBytes) {
        List<String> warnings = new ArrayList<>();
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);

        String androidEvidence = androidEvidence(javaHome, userHome, envVarNames);
        boolean armLinux = os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm"));

        if (androidEvidence != null) {
            warnings.add("this looks like an Android device (" + androidEvidence + "). "
                    + "This mod is developed and tested on desktop only; Android launchers are "
                    + "supported on a best-effort basis. If rendering breaks or the game crashes, "
                    + "please include this log when reporting - the lines around this one identify "
                    + "your launcher and GL driver.");
        } else if (armLinux) {
            warnings.add("running on Linux/" + osArch + " - possibly an Android launcher or ARM "
                    + "single-board computer. This mod is only tested on desktop x86_64; expect "
                    + "rendering differences and include this log when reporting issues.");
        }

        if (BIT32_ARCHES.contains(arch)) {
            warnings.add("32-bit JVM detected (os.arch=" + osArch + "). The 4 GiB address space "
                    + "is too small for Minecraft plus Epic Fight plus converted YSM models; "
                    + "out-of-memory crashes are likely. A 64-bit device/JVM is required.");
        }

        if (maxHeapBytes > 0 && maxHeapBytes < LOW_HEAP_BYTES) {
            warnings.add("JVM max heap is only " + (maxHeapBytes / (1024 * 1024)) + " MiB. "
                    + "Model conversion and physics need headroom on top of Minecraft and Epic "
                    + "Fight; below ~1536 MiB expect out-of-memory crashes or heavy GC stalls. "
                    + "Raise the memory allocation in your launcher settings.");
        }

        return warnings;
    }

    /**
     * Definite Android evidence, or null. Returns a short human-readable reason
     * (it goes verbatim into the warning) rather than a boolean, so the log
     * says what tripped the detection instead of just that something did.
     */
    static String androidEvidence(String javaHome, String userHome, Set<String> envVarNames) {
        if (javaHome != null && javaHome.startsWith("/data/")) {
            return "java.home is under the Android app sandbox: " + javaHome;
        }
        if (userHome != null && userHome.startsWith("/data/")) {
            return "user.home is under the Android app sandbox: " + userHome;
        }
        if (envVarNames != null) {
            for (String name : envVarNames) {
                for (String prefix : ANDROID_LAUNCHER_ENV_PREFIXES) {
                    if (name != null && name.startsWith(prefix)) {
                        return "launcher environment variable " + name + " is set";
                    }
                }
            }
        }
        return null;
    }

    /**
     * GL-side warnings from the version and renderer strings.
     *
     * <p>Version floors mirror the render paths (see class comment). Renderer
     * substrings identify translation layers and software rasterizers: gl4es
     * (caps out near GL 2.1 - below everything this mod draws with), ANGLE and
     * zink/VirGL (GL arrives translated through another API; shader compilation
     * and buffer behaviour differ from any tested driver), and llvmpipe /
     * softpipe / swrast / SwiftShader (no GPU at all - correctness may hold but
     * the per-frame skinning cost makes it unplayable).
     */
    static List<String> glWarnings(String glVersion, String glRenderer) {
        List<String> warnings = new ArrayList<>();
        if (glVersion == null) {
            warnings.add("GL version string is unavailable; cannot verify that the graphics "
                    + "driver meets this mod's requirements (desktop OpenGL 3.3+ or OpenGL ES 3.0+).");
            return warnings;
        }

        String renderer = glRenderer == null ? "" : glRenderer.toLowerCase(Locale.ROOT);
        String version = glVersion.toLowerCase(Locale.ROOT);

        if (glVersion.startsWith("OpenGL ES")) {
            int[] es = parseVersion(glVersion.substring("OpenGL ES".length()));
            warnings.add("OpenGL ES context detected (" + glVersion + ") - an Android-style "
                    + "graphics environment. This mod's ES support is best-effort and untested "
                    + "on real devices; include this log when reporting rendering issues.");
            if (es == null || es[0] < 3) {
                warnings.add("OpenGL ES 3.0 is required for this mod's skinned rendering, but the "
                        + "context reports \"" + glVersion + "\". Converted YSM models will not "
                        + "render; choose an ES 3.x-capable renderer in your launcher if available.");
            } else if (es[0] == 3 && es[1] < 1) {
                warnings.add("OpenGL ES " + es[0] + "." + es[1] + ": the GPU skinning path needs "
                        + "ES 3.1 and will stay disabled; the CPU path will be used instead "
                        + "(slower, especially on mobile CPUs).");
            }
        } else {
            int[] gl = parseVersion(glVersion);
            if (gl != null && (gl[0] < 3 || (gl[0] == 3 && gl[1] < 3))) {
                warnings.add("desktop OpenGL " + gl[0] + "." + gl[1] + " is below this mod's "
                        + "minimum (OpenGL 3.3). Converted YSM models will not render. On Android "
                        + "GL-wrapper setups, switch the launcher's renderer to one that provides "
                        + "GL 3.3+ or OpenGL ES 3.0+.");
            }
        }

        if (renderer.contains("gl4es") || version.contains("gl4es")) {
            warnings.add("gl4es wrapper detected. gl4es provides roughly OpenGL 2.1 on top of "
                    + "GLES and cannot run this mod's skinning shaders; expect invisible or "
                    + "corrupted models. Switch your launcher's renderer (e.g. to a native GLES 3 "
                    + "renderer or Zink/VirGL with GL 3.3+).");
        }
        if (renderer.contains("angle")) {
            warnings.add("ANGLE translation layer detected (" + glRenderer + "). GL is being "
                    + "translated to another API; this combination is untested and shader or "
                    + "buffer behaviour may differ from real drivers.");
        }
        if (renderer.contains("zink") || renderer.contains("virgl")) {
            warnings.add("GL-over-Vulkan/virtualized driver detected (" + glRenderer + "). This "
                    + "translation layer is untested with this mod; rendering glitches are "
                    + "possible and performance of the skinning paths may vary.");
        }
        if (renderer.contains("llvmpipe") || renderer.contains("softpipe")
                || renderer.contains("swrast") || renderer.contains("swiftshader")) {
            warnings.add("software rasterizer detected (" + glRenderer + ") - no GPU acceleration "
                    + "is active. Per-frame model skinning on the CPU rasterizer will be extremely "
                    + "slow; this is not a supported way to run this mod.");
        }

        return warnings;
    }

    /**
     * Leading "major.minor" of a GL version string ("4.6.0 NVIDIA 537.13",
     * " 3.2 v1.r32p1..."), or null when the front of the string is not a
     * version at all.
     */
    static int[] parseVersion(String versionString) {
        if (versionString == null) {
            return null;
        }
        String s = versionString.trim();
        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
            end++;
        }
        String[] parts = s.substring(0, end).split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return new int[] {major, minor};
        } catch (RuntimeException e) {
            return null;
        }
    }
}
