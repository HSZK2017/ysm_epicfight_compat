package com.ysmef.compat.renderer;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmFork;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Reads model variables from the selected YSM fork without linking to its classes. */
public final class YsmModelVariableReader {
    private static final String OBF_POOL =
            "com.elfmcys.yesstevemodel.oOO0OO0oO0oooo00OooooO0O";
    private static final String SOURCE_POOL =
            "com.elfmcys.yesstevemodel.geckolib3.core.molang.util.StringPool";
    private static final String OBF_GETTER = "oOO0O0o0ooO0O0o0oo0oOooO";
    private static final String OBF_CONTAINER = "o0OOooo0o0OO00OoOOOo0o0O";
    private static final String OBF_GET_PROPERTY = "Oo0Oo0o00O00Oo0OOoOOoooo";
    private static final String OBF_GET_NAME = "o0OOooo0o0OO00OoOOOo0o0O";

    private static volatile Method poolGetName;
    private static volatile boolean poolLookupDone;
    private static volatile boolean failureLogged;

    private YsmModelVariableReader() {}

    /**
     * Read only names actually referenced by the converted model. Ordinary v.*
     * variables live in YSM's public variable storage; v.roaming.* is a separate
     * synchronized struct and must be read by its leaf name.
     */
    public static Map<String, Float> read(Player player, Set<String> names) {
        if (player == null || names == null || names.isEmpty()) {
            return Map.of();
        }
        Object animatable = YsmWheelAnimationState.resolveAnimatable(player);
        if (animatable == null) {
            return Map.of();
        }
        Method getName = poolGetName();
        if (getName == null) {
            return Map.of();
        }
        boolean obfuscated = YsmFork.info().obfuscated();
        Map<String, Float> values = new TreeMap<>();
        try {
            Method getter = animatable.getClass().getMethod(obfuscated ? OBF_GETTER : "getPropertyGetter");
            Object storage = getter.invoke(animatable);
            if (storage != null) {
                Method getPublic = storage.getClass().getMethod(
                        obfuscated ? OBF_GET_PROPERTY : "getPublic", int.class);
                for (String name : names) {
                    if (roamingLeaf(name) != null) {
                        continue;
                    }
                    Float value = numericProperty(storage, getPublic, getName, name);
                    if (value != null) {
                        values.put(name, value);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            logFailureOnce("public model variables", e);
        }
        try {
            Method getter = animatable.getClass().getMethod(obfuscated ? OBF_CONTAINER : "getServerVarContainer");
            Object roaming = getter.invoke(animatable);
            if (roaming != null) {
                Method forEach = roaming.getClass().getMethod(
                        obfuscated ? OBF_GET_PROPERTY : "forEachVar", Consumer.class);
                Set<String> present = new HashSet<>();
                forEach.invoke(roaming, (Consumer<String>) present::add);
                Method getProperty = roaming.getClass().getMethod(
                        obfuscated ? OBF_GET_PROPERTY : "getProperty", int.class);
                for (String name : names) {
                    String leaf = roamingLeaf(name);
                    if (leaf == null) continue;
                    if (present.contains(leaf)) {
                        Float value = numericProperty(roaming, getProperty, getName, leaf);
                        if (value != null) {
                            values.put(name, value);
                        }
                    }
                }
            }
        } catch (NoSuchMethodException ignored) {
            // A fork may expose only ordinary public variables or a read-only
            // roaming struct without enumeration. Missing names stay unset.
        } catch (ReflectiveOperationException | RuntimeException e) {
            logFailureOnce("roaming model variables", e);
        }
        return values;
    }

    private static Float numericProperty(Object owner, Method getter, Method getName, String name)
            throws ReflectiveOperationException {
        Object id = getName.invoke(null, name);
        if (!(id instanceof Integer key) || key == Integer.MIN_VALUE) {
            return null;
        }
        Object value = getter.invoke(owner, key);
        return value instanceof Number number && Float.isFinite(number.floatValue())
                ? number.floatValue() : null;
    }

    private static String roamingLeaf(String name) {
        if (name.startsWith("v.roaming.")) return name.substring("v.roaming.".length());
        if (name.startsWith("variable.roaming.")) return name.substring("variable.roaming.".length());
        return null;
    }

    private static synchronized Method poolGetName() {
        if (!poolLookupDone) {
            poolLookupDone = true;
            try {
                boolean obfuscated = YsmFork.info().obfuscated();
                Class<?> pool = Class.forName(obfuscated ? OBF_POOL : SOURCE_POOL);
                poolGetName = pool.getMethod(obfuscated ? OBF_GET_NAME : "getName", String.class);
            } catch (ReflectiveOperationException | RuntimeException e) {
                logFailureOnce("YSM StringPool", e);
            }
        }
        return poolGetName;
    }

    private static void logFailureOnce(String stage, Throwable error) {
        if (!failureLogged) {
            failureLogged = true;
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: cannot read {}: {}", stage, error.toString());
        }
    }
}
