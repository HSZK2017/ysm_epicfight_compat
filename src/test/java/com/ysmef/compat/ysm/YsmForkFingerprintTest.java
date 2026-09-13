package com.ysmef.compat.ysm;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the YSM fork fingerprints.
 *
 * <p>All three YSM builds deliberately share {@code modId = "yes_steve_model"} and
 * a version inside this mod's {@code [2.6,2.7)} contract, so {@link YsmFork} has to
 * identify them by class presence. Each probe below is unique to exactly one build,
 * and the whole point of pinning them here is that a "reasonable-looking" edit -
 * swapping a discriminator for one that also exists in another build, or dropping
 * the ModernYSM layout from the probe order - silently mis-identifies the fork and
 * disables whole features with no error. That already happened once: the wheel
 * bridge probed only the obfuscated and OpenYSM provider layouts, so it disabled
 * itself on ModernYSM while every other feature kept working.
 *
 * <p>The constants are read without initializing {@link YsmFork}: that class talks
 * to Forge's {@code ModList} at runtime, which is not on the plain-JUnit test
 * classpath. {@code Class.forName(name, false, loader)} neither links nor
 * initializes the class, so reading {@code static final String} constants is safe.
 */
class YsmForkFingerprintTest {

    private static final String MODERN_PROVIDER =
            "com.elfmcys.yesstevemodel.forge.capability.PlayerCapabilityProvider";
    private static final String MODERN_FORGE_RENDER_HOOK =
            "com.elfmcys.yesstevemodel.forge.event.ReplacePlayerRenderForgeHook";
    private static final String OPEN_PROVIDER =
            "com.elfmcys.yesstevemodel.capability.PlayerCapabilityProvider";
    private static final String UNOBFUSCATED_RENDER_EVENT =
            "com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent";
    private static final String LEGACY_PROVIDER =
            "com.elfmcys.yesstevemodel.O0OooOo0oOOoOoOoOooO000o";

    @Test
    void readableFingerprintsMatchTheJarsTheyWereVerifiedAgainst() throws Exception {
        assertEquals(MODERN_PROVIDER, constant("MODERN_PLAYER_CAPABILITY_PROVIDER"),
                "ModernYSM's provider lives in the forge subpackage - this is its only discriminator");
        assertEquals(MODERN_FORGE_RENDER_HOOK, constant("MODERN_FORGE_RENDER_HOOK"));
        assertEquals(OPEN_PROVIDER, constant("OPEN_PLAYER_CAPABILITY_PROVIDER"));
        assertEquals(UNOBFUSCATED_RENDER_EVENT, constant("UNOBFUSCATED_RENDER_EVENT"));
        assertEquals(LEGACY_PROVIDER, constant("LEGACY_OBFUSCATED_PLAYER_CAPABILITY_PROVIDER"));
    }

    /**
     * The four probes must stay distinct. Collapsing two of them would make the
     * probe order decide the fork, which is exactly the failure mode this test
     * exists to prevent.
     */
    @Test
    void fingerprintsAreAllDistinct() throws Exception {
        Set<String> probes = new LinkedHashSet<>(Arrays.asList(
                constant("MODERN_PLAYER_CAPABILITY_PROVIDER"),
                constant("MODERN_FORGE_RENDER_HOOK"),
                constant("OPEN_PLAYER_CAPABILITY_PROVIDER"),
                constant("UNOBFUSCATED_RENDER_EVENT"),
                constant("LEGACY_OBFUSCATED_PLAYER_CAPABILITY_PROVIDER")));

        assertEquals(5, probes.size(), "two YSM fork fingerprints were collapsed into one");
    }

    /**
     * The ModernYSM probes must live in the {@code forge} subpackage, and must not
     * be classes that also ship in OpenYSM.
     *
     * <p>{@code config.GeneralConfig} and {@code NativeLibLoader} both exist in
     * OpenYSM as well, so using either as a ModernYSM marker classifies OpenYSM as
     * ModernYSM - which then routes the GPU gate to ModernYSM's
     * {@code USE_GPU_RENDERER}/{@code USE_COMPATIBILITY_RENDERER} fields, whose
     * reflective lookup fails on OpenYSM, and hides this mod's own GPU option from
     * its config screen (which is suppressed for ModernYSM only). This constant
     * list is the guard against re-introducing that.
     */
    @Test
    void modernProbesAreForgeExclusiveAndNotSharedWithOpenYsm() throws Exception {
        for (String probe : new String[]{
                constant("MODERN_PLAYER_CAPABILITY_PROVIDER"),
                constant("MODERN_FORGE_RENDER_HOOK")}) {
            assertTrue(probe.contains(".yesstevemodel.forge."),
                    probe + " must live in the forge subpackage (the multi-platform marker)");
            assertTrue(!SHARED_WITH_OPEN_YSM.contains(probe),
                    probe + " also ships in OpenYSM and cannot serve as a ModernYSM marker");
        }
    }

    /**
     * Classes verified to exist in <i>both</i> readable builds. None of these may
     * be used to identify ModernYSM.
     */
    private static final Set<String> SHARED_WITH_OPEN_YSM = Set.of(
            "com.elfmcys.yesstevemodel.config.GeneralConfig",
            "com.elfmcys.yesstevemodel.NativeLibLoader",
            "com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent",
            "com.elfmcys.yesstevemodel.client.event.ReplacePlayerHandRenderEvent");

    /**
     * The capability-class anchor must be the readable builds' capability class.
     *
     * <p>This one matters more than it looks. Identification keys on the presence of
     * a class declaring a {@code Capability<PlayerCapability>} field, and the
     * obfuscated original has no {@code capability} package at all - so if this
     * constant ever drifted to a name the obfuscated build also has, that build
     * would be classified as a readable fork and the compat mod would look for render
     * hooks that do not exist there.
     */
    @Test
    void capabilityAnchorIsTheReadableBuildsCapabilityClass() throws Exception {
        assertEquals("com.elfmcys.yesstevemodel.capability.PlayerCapability",
                constant("com.ysmef.compat.ysm.YsmClasses", "CAPABILITY_CLASS"));
    }

    /**
     * Guards directly against the mis-identification that actually shipped: the
     * obfuscated original also contains readable class names (its mod class and its
     * whole {@code mixin} package), so "some names are readable" is true for every
     * build and must never be the evidence for a readable fork.
     *
     * <p>Checks the real jars when they are present, and skips otherwise, so the
     * suite still runs on a machine without the game installed.
     */
    @Test
    void readableClassesAloneDoNotIdentifyAReadableFork() throws Exception {
        java.nio.file.Path legacy = legacyJar();
        org.junit.jupiter.api.Assumptions.assumeTrue(legacy != null && java.nio.file.Files.isRegularFile(legacy),
                "official YSM jar not installed; skipping the on-disk check");

        int readable = 0;
        boolean hasCapabilityClass = false;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(legacy.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("com/elfmcys/") || !name.endsWith(".class")) {
                    continue;
                }
                if (name.equals("com/elfmcys/yesstevemodel/capability/PlayerCapability.class")) {
                    hasCapabilityClass = true;
                }
                String simple = name.substring(name.lastIndexOf('/') + 1, name.length() - ".class".length());
                simple = simple.replaceAll("\\$.*$", "");
                if (!(simple.length() >= 8 && simple.matches("[Oo0-9]+"))) {
                    readable++;
                }
            }
        }

        assertTrue(readable > 0,
                "the obfuscated original does ship readable class names (its mod class and mixin package); "
                        + "if this ever becomes 0 the identification could safely count names again");
        assertTrue(!hasCapabilityClass,
                "the obfuscated original must not have the capability class, or the capability anchor "
                        + "stops discriminating between the builds");
    }

    /** The official (obfuscated) YSM jar, or null when it is not installed. */
    private static java.nio.file.Path legacyJar() {
        java.nio.file.Path mods = java.nio.file.Paths.get(
                System.getProperty("user.home"), "AppData", "Roaming", ".minecraft", "versions",
                "EPIC mod test", "mods");
        if (!java.nio.file.Files.isDirectory(mods)) {
            return null;
        }
        try (java.util.stream.Stream<java.nio.file.Path> jars = java.nio.file.Files.list(mods)) {
            return jars.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".jar") && n.contains("ysm-2.6.5") && n.contains("release");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * The legacy provider name must be recognised as an obfuscated name (only
     * {@code O}/{@code o}/digits) while the readable probes must not be - if a
     * readable probe matched this shape, the identification would degrade to
     * "everything is LegacyYSM".
     */
    @Test
    void onlyTheLegacyProviderLooksObfuscated() throws Exception {
        assertTrue(looksObfuscated(simpleName(constant("LEGACY_OBFUSCATED_PLAYER_CAPABILITY_PROVIDER"))),
                "the original release's provider name must be recognisable as obfuscated");

        for (String readable : new String[]{
                simpleName(constant("MODERN_PLAYER_CAPABILITY_PROVIDER")),
                simpleName(constant("OPEN_PLAYER_CAPABILITY_PROVIDER")),
                simpleName(constant("UNOBFUSCATED_RENDER_EVENT"))}) {
            assertTrue(!looksObfuscated(readable),
                    readable + " is a readable name and must not be treated as an obfuscated one");
        }
    }

    /**
     * The capability field name differs between the readable builds and the
     * obfuscated original; using one for the other yields a null capability and a
     * silently disabled wheel bridge.
     */
    @Test
    void capabilityFieldNamesAreForkSpecific() throws Exception {
        assertEquals("PLAYER_CAP", constant("PLAYER_CAPABILITY_FIELD"));
        assertEquals("Oo0Oo0o00O00Oo0OOoOOoooo", constant("LEGACY_PLAYER_CAPABILITY_FIELD"));
        assertNotEquals(constant("PLAYER_CAPABILITY_FIELD"), constant("LEGACY_PLAYER_CAPABILITY_FIELD"));
    }

    /** Full class name -> its simple name (last dot-separated segment). */
    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /**
     * The same shape test the production code uses, mirrored here rather than
     * imported: the rule lives in a class this test deliberately never initializes,
     * which is the whole reason these assertions read constants reflectively.
     */
    private static boolean looksObfuscated(String name) {
        return name.length() >= 8 && name.matches("[Oo0-9]+");
    }

    /** Read a private static constant of {@link YsmFork} without initializing it. */
    private static String constant(String fieldName) throws ReflectiveOperationException {
        return constant("com.ysmef.compat.ysm.YsmFork", fieldName);
    }

    /** Read a static constant of the named class without initializing that class. */
    private static String constant(String className, String fieldName) throws ReflectiveOperationException {
        Class<?> holder = Class.forName(className, false,
                YsmForkFingerprintTest.class.getClassLoader());
        Field field = holder.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
