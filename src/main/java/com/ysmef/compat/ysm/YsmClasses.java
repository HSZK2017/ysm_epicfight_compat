package com.ysmef.compat.ysm;

import com.ysmef.compat.YSMEpicFightCompat;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.ModFileScanData;

import java.lang.module.ModuleReader;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The class names Yes Steve Model actually ships, taken from what the mod loader
 * already knows about it - its scan data first, its module second.
 *
 * <p>Why this exists: this mod has to reach into YSM, whose three known builds
 * (the obfuscated original, OpenYSM, and ModernYSM) deliberately share
 * {@code modId = "yes_steve_model"} and put the same classes in different
 * packages under different - sometimes obfuscated - names. Naming those classes
 * in source means a mapping table per build that silently rots: exactly the
 * failure that disabled the wheel animation bridge on ModernYSM, where the
 * capability provider had moved from {@code capability} to
 * {@code forge.capability} and every hardcoded probe missed it.
 *
 * <p>Asking the loader for YSM's class list instead lets a class be found by
 * <i>what it is</i> - what it extends, what fields it declares - rather than by
 * its name, so an obfuscated build, a repackaged fork and a future relocation
 * all resolve the same way. Ported from EpicYSM's {@code YsmClasses}
 * (MIT), which uses the same technique.
 *
 * <p>All loader access is reflective and tolerant: the {@code ClassData}
 * accessors are named differently across loader builds ({@code clazz()}/{@code parent()}
 * return ASM {@code Type} on Forge 1.20.1, and some builds expose bean-style
 * names instead), so every accessor is tried by name and every route is allowed
 * to fail quietly. A build where nothing resolves simply yields no names and the
 * caller falls back to its fingerprint table.
 */
public final class YsmClasses {

    private static final String YSM_MOD_ID = "yes_steve_model";
    private static final String YSM_PACKAGE = "com.elfmcys.";

    /**
     * YSM's player capability class. One of the few YSM names that is stable across
     * the readable builds, and the anchor for finding the provider: the class that
     * declares a {@code Capability<PlayerCapability>} field is the provider, whatever
     * package it was put in.
     *
     * <p>The obfuscated original has no such class at all - it obfuscates its whole
     * root package and ships no {@code capability} package - which is exactly what
     * makes this a reliable fork discriminator.
     */
    public static final String CAPABILITY_CLASS = "com.elfmcys.yesstevemodel.capability.PlayerCapability";

    /** Cached class list; empty-but-resolved is distinguished from not-yet-looked-up. */
    private static volatile List<String> names = null;
    private static volatile String route = "none";

    private YsmClasses() {}

    /**
     * Every YSM class the loader knows about, or an empty list when the loader's
     * scan data is unavailable. Cached after the first successful listing; a
     * failed listing is retried on the next call (it may simply be too early).
     */
    public static List<String> names() {
        List<String> cached = names;
        if (cached != null) {
            return cached;
        }
        synchronized (YsmClasses.class) {
            if (names != null) {
                return names;
            }
            List<String> found = fromScan();
            String how = "scan";
            if (found.isEmpty()) {
                found = fromModule();
                how = "module";
            }
            if (found.isEmpty()) {
                // Not remembered: the loader may not have finished scanning yet.
                return Collections.emptyList();
            }
            route = how;
            names = Collections.unmodifiableList(found);
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: listed {} Yes Steve Model classes (via {}) - classes are now addressed by what they are rather than by name",
                    found.size(), how);
            return names;
        }
    }

    /** How the class list was obtained ("scan", "module", or "none"). For diagnostics. */
    public static String route() {
        names();
        return route;
    }

    /**
     * YSM's classes that directly extend {@code parentClassName}, found by
     * inheritance rather than by name - so an obfuscated event class is still
     * found.
     */
    public static List<String> extending(String parentClassName) {
        List<String> found = new ArrayList<>();
        for (ClassInfo info : scan()) {
            if (info.name != null && info.name.startsWith(YSM_PACKAGE)
                    && parentClassName.equals(info.parent)) {
                found.add(info.name);
            }
        }
        return found;
    }

    /**
     * The capability provider YSM registers its player animatable under, found by
     * structure instead of by package: the YSM class that declares a public static
     * capability field whose declared type argument is {@code playerCapabilityClassName}.
     *
     * <p>That is what a Forge capability provider for a given capability looks
     * like, so this finds ModernYSM's {@code forge.capability} layout, OpenYSM's
     * {@code capability} layout and the obfuscated original's class alike. Returns
     * null when nothing matches (caller falls back to its fingerprint table).
     */
    public static Holder findPlayerCapabilityHolder(String playerCapabilityClassName) {
        if (playerCapabilityClassName == null) {
            return null;
        }
        for (ClassInfo info : scan()) {
            if (info.name == null || !info.name.startsWith(YSM_PACKAGE)) {
                continue;
            }
            Holder holder = probeHolder(info.name, playerCapabilityClassName);
            if (holder != null) {
                return holder;
            }
        }
        return null;
    }

    /**
     * @param className the class declaring the capability field
     * @param fieldName the static field holding the {@code Capability}
     */
    public record Holder(String className, String fieldName) {}

    /**
     * Whether {@code className} declares a public static capability field whose
     * generic argument is the named capability type. Never throws and never
     * initializes the class ({@code initialize = false}).
     */
    private static Holder probeHolder(String className, String capabilityClassName) {
        try {
            Class<?> type = Class.forName(className, false, YsmClasses.class.getClassLoader());
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String argument = capabilityArgumentOf(field.getGenericType());
                if (capabilityClassName.equals(argument)) {
                    return new Holder(className, field.getName());
                }
            }
        } catch (Throwable ignored) {
            // Not loadable (missing optional dependency, wrong side, ...) - skip.
        }
        return null;
    }

    /** The {@code X} in a {@code Capability<X>} type, or null for anything else. */
    private static String capabilityArgumentOf(java.lang.reflect.Type genericType) {
        if (!(genericType instanceof java.lang.reflect.ParameterizedType parameterized)) {
            return null;
        }
        if (!(parameterized.getRawType() instanceof Class<?> raw)
                || !"Capability".equals(raw.getSimpleName())) {
            return null;
        }
        java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length != 1) {
            return null;
        }
        if (arguments[0] instanceof Class<?> capability) {
            return capability.getName();
        }
        // Parameterized capability argument (e.g. Capability<Foo<Bar>>): take raw.
        if (arguments[0] instanceof java.lang.reflect.ParameterizedType nested
                && nested.getRawType() instanceof Class<?> nestedRaw) {
            return nestedRaw.getName();
        }
        return null;
    }

    /** The name of YSM's player capability class, resolved by scan where possible. */
    public static String playerCapabilityClassName() {
        return names().contains(CAPABILITY_CLASS) ? CAPABILITY_CLASS : null;
    }

    // ------------------------------------------------------------------
    // Loader access
    // ------------------------------------------------------------------

    /** One scan entry: the class, its direct parent, reduced to names. */
    private record ClassInfo(String name, String parent) {}

    /** Every YSM class name from the loader's scan, or empty when unavailable. */
    private static List<String> fromScan() {
        List<String> found = new ArrayList<>();
        for (ClassInfo info : scan()) {
            if (info.name() != null && info.name().startsWith(YSM_PACKAGE)) {
                found.add(info.name());
            }
        }
        return found;
    }

    private static List<ClassInfo> scan() {
        List<ClassInfo> found = new ArrayList<>();
        try {
            Object modFile = modFile();
            if (modFile == null) {
                return found;
            }
            Object scanResult = invokeNoArg(modFile, "getScanResult");
            if (!(scanResult instanceof ModFileScanData data) || data.getClasses() == null) {
                return found;
            }
            for (ModFileScanData.ClassData entry : data.getClasses()) {
                String name = typeNameOf(entry, "clazz", "getClazz");
                if (name == null) {
                    continue;
                }
                found.add(new ClassInfo(name, typeNameOf(entry, "parent", "getParent")));
            }
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: YSM scan data unavailable", t);
        }
        return found;
    }

    private static Object modFile() {
        try {
            ModList modList = ModList.get();
            if (modList == null) {
                return null;
            }
            Object fileInfo = modList.getModFileById(YSM_MOD_ID);
            return fileInfo == null ? null : invokeNoArg(fileInfo, "getFile");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The class name inside a scan entry. The accessor returns ASM's {@code Type}
     * on Forge 1.20.1, so it is unwrapped through {@code getClassName()} by
     * reflection rather than by casting (this mod has no ASM compile dependency).
     */
    private static String typeNameOf(ModFileScanData.ClassData data, String... accessors) {
        for (String accessor : accessors) {
            Object type = invokeNoArg(data, accessor);
            if (type == null) {
                continue;
            }
            if (type instanceof String name) {
                return name;
            }
            Object className = invokeNoArg(type, "getClassName");
            if (className instanceof String name) {
                return name;
            }
        }
        return null;
    }

    /**
     * Fallback listing through the module system, for a loader build whose scan
     * data is not exposed.
     */
    private static List<String> fromModule() {
        List<String> found = new ArrayList<>();
        try {
            Class<?> sample = Class.forName("com.elfmcys.yesstevemodel.YesSteveModel",
                    false, YsmClasses.class.getClassLoader());
            Module module = sample.getModule();
            if (!module.isNamed() || module.getLayer() == null) {
                return found;
            }
            Optional<ResolvedModule> resolved =
                    module.getLayer().configuration().findModule(module.getName());
            if (resolved.isEmpty()) {
                return found;
            }
            try (ModuleReader reader = resolved.get().reference().open();
                 Stream<String> entries = reader.list()) {
                for (String entry : (Iterable<String>) entries::iterator) {
                    if (!entry.endsWith(".class") || entry.equals("module-info.class")) {
                        continue;
                    }
                    String className = entry.substring(0, entry.length() - 6).replace('/', '.');
                    if (className.startsWith(YSM_PACKAGE)) {
                        found.add(className);
                    }
                }
            }
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: YSM module listing unavailable", t);
        }
        return found;
    }

    /** Drop the cached listing (world leave / resource reload). */
    public static synchronized void invalidate() {
        names = null;
        route = "none";
    }
}
