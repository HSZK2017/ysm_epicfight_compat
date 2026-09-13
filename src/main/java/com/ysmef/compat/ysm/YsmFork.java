package com.ysmef.compat.ysm;

import com.ysmef.compat.YSMEpicFightCompat;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * Which Yes Steve Model build is loaded, plus the concrete evidence for that
 * verdict.
 *
 * Why this exists: all three known YSM builds deliberately share
 * {@code modId = "yes_steve_model"} (to stay drop-in compatible with the
 * existing mod ecosystem) and all report a version inside this mod's
 * {@code [2.6,2.7)} contract, so neither Forge's mod list nor the version
 * constraint can tell them apart - yet their internals differ enough that
 * getting it wrong silently breaks features. The three builds are:
 *
 * <ul>
 *   <li><b>{@link Fork#LEGACY_YSM}</b> - the original closed-source release.
 *       Protected by VMProtect and shipped ~99.9% obfuscated (932 of 933 root
 *       classes carry names made only of {@code O}/{@code o}/digits), so no
 *       {@code client.event.*} class exists. Its model core is a native library
 *       ({@code META-INF/native/libysm-core*.so|dll}) with no platform
 *       detection and no Java fallback, and the Android build is AArch64-only -
 *       it therefore cannot run on an x86_64 Android host at all.</li>
 *   <li><b>{@link Fork#OPEN_YSM}</b> - the community de-obfuscation and
 *       AI-assisted rewrite of the above. Fully readable, single-module Forge
 *       project, no bundled natives.</li>
 *   <li><b>{@link Fork#MODERN_YSM}</b> - the modernized successor of OpenYSM
 *       (architectury {@code common}/{@code fabric}/{@code forge} layout, and
 *       the first build to actually use the {@code openysm} artifact name).
 *       It moved the Forge-facing classes into a {@code forge} subpackage and
 *       re-introduced small per-platform natives under {@code natives/&lt;platform&gt;/}
 *       <i>with</i> platform detection and a pure-Java render fallback - which
 *       is why it runs on x86_64 Android while the original does not.</li>
 * </ul>
 *
 * The fingerprints below were verified against the actual jars:
 *
 * <pre>
 * probe                                                    LEGACY  OPEN  MODERN
 * com...forge.capability.PlayerCapabilityProvider               -     -    YES
 * com...forge.event.ReplacePlayerRenderForgeHook                -     -    YES
 * com...capability.PlayerCapabilityProvider                     -   YES      -
 * com...client.event.ReplacePlayerRenderEvent                   -   YES    YES
 * </pre>
 *
 * Note the last row: the un-obfuscated render event is shared by OpenYSM and
 * ModernYSM, so it can only serve as an OpenYSM probe <i>after</i> the two
 * ModernYSM probes have been ruled out - it is the reason probe order matters
 * here. Classes that exist in both readable builds (for example
 * {@code config.GeneralConfig} and {@code NativeLibLoader}) must never be used as
 * a ModernYSM marker: that would classify OpenYSM as ModernYSM and hand the GPU
 * toggle to fields only ModernYSM has.
 *
 * Probing is done by class/feature presence rather than by parsing the jar, so
 * it costs a few {@code Class.forName} lookups and is safe to call from any
 * client-side entry point.
 */
public final class YsmFork {

    private static final String YSM_MOD_ID = "yes_steve_model";

    /** Full class-name probes used for identification and for feature gating. */
    private static final String MODERN_PLAYER_CAPABILITY_PROVIDER =
            "com.elfmcys.yesstevemodel.forge.capability.PlayerCapabilityProvider";
    /**
     * Secondary ModernYSM probe, in the same {@code forge} subpackage family as
     * the primary one.
     *
     * Deliberately NOT {@code com.elfmcys.yesstevemodel.config.GeneralConfig}:
     * that class also exists in OpenYSM, so using it as a ModernYSM marker would
     * classify OpenYSM as ModernYSM and hand the GPU toggle to
     * {@code USE_GPU_RENDERER} fields only ModernYSM has.
     */
    private static final String MODERN_FORGE_RENDER_HOOK =
            "com.elfmcys.yesstevemodel.forge.event.ReplacePlayerRenderForgeHook";
    private static final String OPEN_PLAYER_CAPABILITY_PROVIDER =
            "com.elfmcys.yesstevemodel.capability.PlayerCapabilityProvider";
    private static final String UNOBFUSCATED_RENDER_EVENT =
            "com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent";
    /**
     * The capability provider of the obfuscated original, under the obfuscated
     * name it is actually compiled with. Not a fingerprint (it never matches in
     * the readable builds) but kept here so the capability lookup has a single
     * definition site alongside the readable layouts.
     */
    private static final String LEGACY_OBFUSCATED_PLAYER_CAPABILITY_PROVIDER =
            "com.elfmcys.yesstevemodel.O0OooOo0oOOoOoOoOooO000o";
    /** Field holding the {@code Capability<PlayerCapability>} in every layout. */
    public static final String PLAYER_CAPABILITY_FIELD = "PLAYER_CAP";
    /** The obfuscated original names that same field differently. */
    public static final String LEGACY_PLAYER_CAPABILITY_FIELD = "Oo0Oo0o00O00Oo0OOoOOoooo";

    public enum Fork {
        /** The original obfuscated release (a.k.a. LgeacyYSM). */
        LEGACY_YSM,
        /** The community de-obfuscation / rewrite. */
        OPEN_YSM,
        /** OpenYSM's modernized successor (architectury multi-platform). */
        MODERN_YSM,
        /** YSM is not installed at all. */
        NONE
    }

    /**
     * The identification verdict: which build, what decided it, and how to reach
     * that build's player capability.
     *
     * <p>A plain final class rather than a record so the capability-holder scan can
     * be cached per verdict: each scan loads and inspects every YSM class, and the
     * accessors below are called from the startup line, the wheel bridge and
     * diagnostics alike.
     */
    public static final class Info {

        private final Fork fork;
        private final String evidence;
        private final String version;

        /** Lazily resolved capability holder; {@link #HOLDER_UNRESOLVED} until scanned. */
        private static final YsmClasses.Holder HOLDER_UNRESOLVED = new YsmClasses.Holder("", "");
        private volatile YsmClasses.Holder holder = HOLDER_UNRESOLVED;

        Info(Fork fork, String evidence, String version) {
            this.fork = fork;
            this.evidence = evidence;
            this.version = version;
        }

        public Fork fork() {
            return this.fork;
        }

        /** The concrete class/resource that decided the verdict, for logs. */
        public String evidence() {
            return this.evidence;
        }

        /** The version string YSM reports in its mod metadata. */
        public String version() {
            return this.version;
        }

        /** True when some YSM build is present (any fork other than {@link Fork#NONE}). */
        public boolean present() {
            return this.fork != Fork.NONE;
        }

        /**
         * The capability-provider class name this build actually ships, or null
         * when it ships none addressable by a readable name.
         *
         * <p>Resolved by scanning YSM's own class list for the class that declares
         * a {@code Capability<PlayerCapability>} field, so a relocated or
         * re-packaged provider is found anyway; the fingerprint table is only the
         * fallback for a build whose scan data is unavailable.
         */
        public String playerCapabilityProviderClass() {
            YsmClasses.Holder scanned = scannedHolder();
            if (scanned != null) {
                return scanned.className();
            }
            return switch (this.fork) {
                case MODERN_YSM -> MODERN_PLAYER_CAPABILITY_PROVIDER;
                case OPEN_YSM -> OPEN_PLAYER_CAPABILITY_PROVIDER;
                // The original release obfuscates every class name, so its provider
                // has no readable name; obfuscatedPlayerCapabilityProviderClass()
                // covers it instead.
                case LEGACY_YSM, NONE -> null;
            };
        }

        /**
         * The field holding the {@code Capability}, resolved by scan where possible
         * (the readable builds and the obfuscated one all name it differently).
         */
        public String playerCapabilityField() {
            YsmClasses.Holder scanned = scannedHolder();
            if (scanned != null) {
                return scanned.fieldName();
            }
            return this.fork == Fork.LEGACY_YSM
                    ? LEGACY_PLAYER_CAPABILITY_FIELD : PLAYER_CAPABILITY_FIELD;
        }

        /** The scan result for this build's capability holder, or null. Cached. */
        private YsmClasses.Holder scannedHolder() {
            if (this.fork == Fork.NONE) {
                return null;
            }
            YsmClasses.Holder cached = this.holder;
            if (cached != HOLDER_UNRESOLVED) {
                return cached;
            }
            YsmClasses.Holder found;
            try {
                found = YsmClasses.findPlayerCapabilityHolder(YsmClasses.playerCapabilityClassName());
            } catch (Throwable t) {
                found = null;
            }
            this.holder = found;
            return found;
        }

        /**
         * The obfuscated provider name for the original release, or null for the
         * readable builds (whose provider is given by
         * {@link #playerCapabilityProviderClass()}).
         */
        public String obfuscatedPlayerCapabilityProviderClass() {
            return this.fork == Fork.LEGACY_YSM
                    ? LEGACY_OBFUSCATED_PLAYER_CAPABILITY_PROVIDER : null;
        }

        /**
         * Whether every YSM class name is obfuscated - the signature of the
         * original release. When true, only the compile-time obfuscated mixins and
         * the obfuscated provider name can match; string-targeted mixins and
         * readable layouts cannot.
         */
        public boolean obfuscated() {
            return this.fork == Fork.LEGACY_YSM;
        }

        /**
         * Whether {@code client.event.*} hook classes exist under their readable
         * names. False for the obfuscated release, whose render hooks can only be
         * addressed by obfuscated name (and therefore only by this mod's
         * compile-time {@code Ysm*} mixins, not by string-targeted ones).
         */
        public boolean hasUnobfuscatedRenderHooks() {
            return this.fork == Fork.OPEN_YSM || this.fork == Fork.MODERN_YSM;
        }

        /**
         * Whether this build bundles its own native model core, and therefore
         * whether a modern GPU/CPU skinning path can even be reached. The
         * original release is the notable case: its native core is a hard
         * dependency with no Java fallback.
         */
        public boolean shipsNativeCore() {
            return this.fork == Fork.LEGACY_YSM || this.fork == Fork.MODERN_YSM;
        }
    }

    private static final Info ABSENT = new Info(Fork.NONE, "mod 'yes_steve_model' is not loaded", "");

    private static volatile Info info = null;

    private YsmFork() {}

    /**
     * The identified build, detected once and cached. Safe to call from any
     * client-side entry point; never returns null.
     */
    public static Info info() {
        Info cached = info;
        if (cached != null) {
            return cached;
        }
        synchronized (YsmFork.class) {
            if (info == null) {
                info = identify();
            }
            return info;
        }
    }

    public static Fork fork() {
        return info().fork();
    }

    /**
     * Run the detection now and emit the one-line verdict. Called from client
     * setup so the verdict is in the log before any render-path decision, rather
     * than being deferred to whichever feature happens to ask first.
     */
    public static Info reportAtStartup() {
        Info result = info();
        if (result.present()) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: YSM fork identified as {} (version '{}', evidence: {}; "
                            + "class listing via {}; unobfuscated render hooks: {}, bundled native core: {}, capability provider: {})",
                    result.fork(), result.version(), result.evidence(),
                    YsmClasses.route(),
                    result.hasUnobfuscatedRenderHooks() ? "yes" : "no",
                    result.shipsNativeCore() ? "yes" : "no",
                    providerDescription(result));
        } else {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: YSM fork identification found no YSM build ({}) - "
                            + "YSM bridging is inactive", result.evidence());
        }
        return result;
    }

    /** The capability class + field the bridge will use, for the startup line. */
    private static String providerDescription(Info result) {
        String className = result.playerCapabilityProviderClass();
        if (className == null) {
            className = result.obfuscatedPlayerCapabilityProviderClass();
        }
        if (className == null) {
            return "not addressable";
        }
        return className + "#" + result.playerCapabilityField();
    }

    private static Info identify() {
        ModList modList = ModList.get();
        if (modList == null || !modList.isLoaded(YSM_MOD_ID)) {
            return ABSENT;
        }
        String version = version();

        // Ask the loader what YSM ships and decide from that first: the class list
        // is ground truth, so a build whose layout moved is still identified. The
        // probe table below is only the fallback for a build whose scan data is
        // unavailable.
        Info byScan = identifyByScan(version);
        if (byScan != null) {
            return byScan;
        }

        // Most specific first: each probe below is unique to exactly one build
        // (see the table in the class comment).
        if (classExists(MODERN_PLAYER_CAPABILITY_PROVIDER)) {
            return new Info(Fork.MODERN_YSM, MODERN_PLAYER_CAPABILITY_PROVIDER + " (probe)", version);
        }
        if (classExists(MODERN_FORGE_RENDER_HOOK)) {
            // Belt and braces for a future ModernYSM that relocates the provider:
            // the forge-only render hook is exclusive to the multi-platform lineage.
            return new Info(Fork.MODERN_YSM, MODERN_FORGE_RENDER_HOOK + " (probe)", version);
        }
        if (classExists(OPEN_PLAYER_CAPABILITY_PROVIDER)) {
            return new Info(Fork.OPEN_YSM, OPEN_PLAYER_CAPABILITY_PROVIDER + " (probe)", version);
        }
        if (classExists(UNOBFUSCATED_RENDER_EVENT)) {
            return new Info(Fork.OPEN_YSM, UNOBFUSCATED_RENDER_EVENT + " (probe)", version);
        }
        // Present but none of the readable markers exist -> every class name is
        // obfuscated, which is the signature of the original release.
        return new Info(Fork.LEGACY_YSM,
                "no readable YSM class names present (obfuscated build, probe)", version);
    }

    /**
     * Identify the build from YSM's own class list.
     *
     * <p>Identification keys on the classes that actually differ between the builds,
     * never on a count of readable names: the obfuscated original still ships
     * readable classes of its own (its {@code mixin} package, and the mod class
     * itself), so "some names are readable" is true for every build and cannot
     * decide anything. What does decide it:
     *
     * <ul>
     *   <li>the player capability class, which only the readable builds have - the
     *       obfuscated original has no {@code capability} package at all;</li>
     *   <li>whether that capability's provider sits in a {@code forge} subpackage,
     *       which is ModernYSM's multi-platform layout.</li>
     * </ul>
     *
     * @return the verdict, or null when YSM's class list is unavailable
     */
    private static Info identifyByScan(String version) {
        List<String> listed;
        try {
            listed = YsmClasses.names();
        } catch (Throwable t) {
            return null;
        }
        if (listed.isEmpty()) {
            return null;
        }

        YsmClasses.Holder holder;
        try {
            holder = YsmClasses.findPlayerCapabilityHolder(YsmClasses.CAPABILITY_CLASS);
        } catch (Throwable t) {
            holder = null;
        }

        String evidence = "scan of " + listed.size() + " YSM classes";
        if (holder == null) {
            // No readable player capability anywhere in the jar: every name the mod
            // is addressed by is obfuscated. (The jar's own mixin classes are
            // readable, which is why name counts are not used here.)
            return new Info(Fork.LEGACY_YSM,
                    evidence + "; no readable player capability class (obfuscated build)", version);
        }

        boolean forgeSubpackage = holder.className().startsWith("com.elfmcys.yesstevemodel.forge.");
        return new Info(forgeSubpackage ? Fork.MODERN_YSM : Fork.OPEN_YSM,
                evidence + "; capability provider at " + holder.className()
                        + (forgeSubpackage ? " (forge subpackage present)" : ""),
                version);
    }

    /** YSM's reported version, or an empty string when unavailable. */
    private static String version() {
        try {
            return ModList.get().getModContainerById(YSM_MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, YsmFork.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
