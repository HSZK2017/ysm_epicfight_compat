package com.ysmef.compat.env;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the environment heuristics of {@link EnvironmentCheck} against the
 * string shapes real hosts produce. The entries here are the point: each case
 * is a concrete environment seen in the field (desktop Windows/NVIDIA, Pojav
 * GLES, gl4es wrapper, Winlator-style Turnip/Zink) and the test states which of
 * them must warn and which must stay silent - a false positive on a normal
 * desktop would train users to ignore the warning that matters on Android.
 */
class EnvironmentCheckTest {

    private static final long FOUR_GIB = 4096L * 1024 * 1024;

    // ---------------------------------------------------------------
    // Host classification
    // ---------------------------------------------------------------

    @Test
    void desktopWindowsProducesNoWarnings() {
        List<String> warnings = EnvironmentCheck.hostWarnings(
                "Windows 11", "amd64", "C:\\Program Files\\Java\\jdk-17",
                "C:\\Users\\dev", Set.of("PATH", "TEMP"), FOUR_GIB);
        assertEquals(List.of(), warnings);
    }

    @Test
    void desktopLinuxX86ProducesNoWarnings() {
        List<String> warnings = EnvironmentCheck.hostWarnings(
                "Linux", "amd64", "/usr/lib/jvm/java-17", "/home/dev",
                Set.of("PATH", "HOME", "DISPLAY"), FOUR_GIB);
        assertEquals(List.of(), warnings);
    }

    @Test
    void pojavEnvironmentVariableIsDefiniteAndroidEvidence() {
        assertEquals("launcher environment variable POJAV_NATIVEDIR is set",
                EnvironmentCheck.androidEvidence(
                        "/usr/lib/jvm", "/home/user", Set.of("PATH", "POJAV_NATIVEDIR")));
    }

    @Test
    void androidAppSandboxJavaHomeIsDefiniteEvidence() {
        String evidence = EnvironmentCheck.androidEvidence(
                "/data/data/com.tungsten.fcl/files/java/jre17", "/home", Set.of());
        assertTrue(evidence.contains("/data/data/com.tungsten.fcl"));
    }

    @Test
    void desktopHostHasNoAndroidEvidence() {
        assertNull(EnvironmentCheck.androidEvidence(
                "/usr/lib/jvm/java-17", "/home/dev", Set.of("PATH", "HOME")));
    }

    @Test
    void definiteAndroidHostWarns() {
        List<String> warnings = EnvironmentCheck.hostWarnings(
                "Linux", "aarch64", "/data/data/net.kdt.pojavlaunch/files/jre",
                "/data/data/net.kdt.pojavlaunch", Set.of("POJAV_RENDERER"), FOUR_GIB);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Android device"));
    }

    @Test
    void armLinuxWithoutSandboxEvidenceGetsTheSofterPossiblyAndroidLine() {
        List<String> warnings = EnvironmentCheck.hostWarnings(
                "Linux", "aarch64", "/usr/lib/jvm/java-17", "/home/pi",
                Set.of("PATH"), FOUR_GIB);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("possibly an Android launcher"));
    }

    @Test
    void thirtyTwoBitArchAndLowHeapEachWarn() {
        List<String> warnings = EnvironmentCheck.hostWarnings(
                "Linux", "armv7l", "/data/data/net.kdt.pojavlaunch/files/jre",
                "/data/data/net.kdt.pojavlaunch", Set.of(), 768L * 1024 * 1024);
        // Android + 32-bit + low heap: all three independently worth stating.
        assertEquals(3, warnings.size());
        assertTrue(warnings.get(1).contains("32-bit"));
        assertTrue(warnings.get(2).contains("768 MiB"));
    }

    // ---------------------------------------------------------------
    // GL classification
    // ---------------------------------------------------------------

    @Test
    void desktopNvidiaContextProducesNoWarnings() {
        assertEquals(List.of(), EnvironmentCheck.glWarnings(
                "4.6.0 NVIDIA 537.13", "NVIDIA GeForce RTX 3060/PCIe/SSE2"));
    }

    @Test
    void gles31ContextWarnsOnlyAboutBeingUntestedEs() {
        List<String> warnings = EnvironmentCheck.glWarnings(
                "OpenGL ES 3.2 v1.r32p1-00pxl0", "Mali-G78");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("OpenGL ES context detected"));
    }

    @Test
    void gles30ContextAddsTheGpuPathUnavailableLine() {
        List<String> warnings = EnvironmentCheck.glWarnings(
                "OpenGL ES 3.0 (git-abc)", "Adreno (TM) 610");
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(1).contains("ES 3.1"));
    }

    @Test
    void gles20ContextSaysModelsWillNotRender() {
        List<String> warnings = EnvironmentCheck.glWarnings(
                "OpenGL ES 2.0", "Adreno (TM) 306");
        assertTrue(warnings.get(1).contains("will not render"));
    }

    @Test
    void gl4esWrapperIsCalledOutEvenThoughItReportsDesktopGl21() {
        List<String> warnings = EnvironmentCheck.glWarnings(
                "2.1 gl4es wrapper 1.1.6", "GL4ES wrapper (Adreno (TM) 640)");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("gl4es")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("below this mod's")));
    }

    @Test
    void zinkTranslationLayerWarns() {
        List<String> warnings = EnvironmentCheck.glWarnings(
                "4.6 (Core Profile) Mesa 23.1.0", "zink Vulkan 1.3 (Turnip Adreno (TM) 730)");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("GL-over-Vulkan"));
    }

    @Test
    void softwareRasterizerWarns() {
        List<String> warnings = EnvironmentCheck.glWarnings(
                "4.5 (Core Profile) Mesa 22.0.5", "llvmpipe (LLVM 13.0.1, 256 bits)");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("software rasterizer"));
    }

    @Test
    void nullVersionStringWarnsInsteadOfCrashing() {
        List<String> warnings = EnvironmentCheck.glWarnings(null, null);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("unavailable"));
    }

    // ---------------------------------------------------------------
    // Version parsing
    // ---------------------------------------------------------------

    @Test
    void parsesCommonVersionShapes() {
        assertArrayEquals(new int[] {4, 6}, EnvironmentCheck.parseVersion("4.6.0 NVIDIA 537.13"));
        assertArrayEquals(new int[] {3, 2}, EnvironmentCheck.parseVersion(" 3.2 v1.r32p1-00pxl0"));
        assertArrayEquals(new int[] {3, 0}, EnvironmentCheck.parseVersion("3"));
        assertNull(EnvironmentCheck.parseVersion("no digits here"));
        assertNull(EnvironmentCheck.parseVersion(null));
    }
}
