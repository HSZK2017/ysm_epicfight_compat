package com.ysmef.compat.renderer;

import com.ysmef.compat.YSMEpicFightCompat;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the currently wheel-selected extra animation of a player from YSM's
 * client-side PlayerCapability without any compile-time dependency on YSM's
 * obfuscated classes.
 *
 * Both supported YSM variants are resolved reflectively:
 * - YSM 2.6.5 release jar (obfuscated class/member names)
 * - OpenYSM / ModernYSM forks (un-obfuscated member names)
 *
 * YSM attaches PlayerCapabilityProvider to every AbstractClientPlayer on the
 * client, so this works for the local player and every remote player whose
 * extra-animation state YSM synced to the client.
 */
public final class YsmWheelAnimationState {

    /** animationName empty + playing false = no extra animation active. */
    public record State(String animationName, boolean playing) {
        public static final State NONE = new State("", false);
    }

    private static volatile Capability<?> capability;
    private static volatile boolean capabilityResolved;
    private static volatile Method getSelectedModelIdMethod;
    private static volatile Method isModelSwitchingMethod;
    private static volatile boolean methodLookupDone;
    private static volatile Method getServerVarContainerMethod;
    private static volatile Method roamingForEachVarMethod;
    private static volatile Method roamingGetPropertyMethod;
    private static volatile Method stringPoolGetNameMethod;
    private static volatile boolean roamingLookupDone;

    private YsmWheelAnimationState() {}

    public static State read(Player player) {
        if (player == null || player.level() == null) {
            return State.NONE;
        }
        Object animatable = resolveAnimatable(player);
        if (animatable == null) {
            return State.NONE;
        }
        try {
            ensureMethods(animatable.getClass());
            if (getSelectedModelIdMethod == null || isModelSwitchingMethod == null) {
                if (!missingMethodsLogged) {
                    missingMethodsLogged = true;
                    YSMEpicFightCompat.LOGGER.info(
                            "YSM-EF Compat: [wheel] YSM wheel-state methods not found on {} (selectedModelId={}, modelSwitching={})",
                            animatable.getClass().getName(), getSelectedModelIdMethod != null, isModelSwitchingMethod != null);
                }
                return State.NONE;
            }
            Object rawName = getSelectedModelIdMethod.invoke(animatable);
            Object rawPlaying = isModelSwitchingMethod.invoke(animatable);
            String name = rawName instanceof String s ? s : "";
            boolean playing = rawPlaying instanceof Boolean b && b;
            return new State(name, playing);
        } catch (Throwable t) {
            logFailureOnce(t);
            return State.NONE;
        }
    }

    /**
     * Read YSM's persistent client-side roaming variables (v.roaming.*) for the
     * player. These are the variables wheel animations like "toggle gun" or
     * "toggle key" flip, and the model's visibility scripts use them to show or
     * collapse accessory parts. An empty map means the YSM fork does not expose
     * them reflectively (or none are set).
     */
    public static Map<String, Float> readRoamingVars(Player player) {
        Object animatable = resolveAnimatable(player);
        if (animatable == null) {
            return Collections.emptyMap();
        }
        try {
            ensureRoamingContainerMethod(animatable.getClass());
            if (getServerVarContainerMethod == null) {
                return Collections.emptyMap();
            }
            Object struct = getServerVarContainerMethod.invoke(animatable);
            if (struct == null || !struct.getClass().getName().endsWith("RoamingStruct")) {
                return Collections.emptyMap();
            }
            ensureRoamingStructMethods(struct.getClass());
            if (roamingForEachVarMethod == null || roamingGetPropertyMethod == null || stringPoolGetNameMethod == null) {
                return Collections.emptyMap();
            }
            Map<String, Float> result = new TreeMap<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            roamingForEachVarMethod.invoke(struct, (java.util.function.Consumer<String>) names::add);
            for (String name : names) {
                if (name == null || name.isEmpty()) {
                    continue;
                }
                try {
                    Object idObj = stringPoolGetNameMethod.invoke(null, name);
                    if (!(idObj instanceof Integer id)) {
                        continue;
                    }
                    Object value = roamingGetPropertyMethod.invoke(struct, id);
                    if (value instanceof Number number) {
                        result.put(name, number.floatValue());
                    }
                } catch (Throwable ignored) {
                }
            }
            return result;
        } catch (Throwable t) {
            logFailureOnce(t);
            return Collections.emptyMap();
        }
    }

    private static void ensureRoamingContainerMethod(Class<?> animatableClass) {
        if (roamingLookupDone) {
            return;
        }
        roamingLookupDone = true;
        try {
            Method container = animatableClass.getMethod("getServerVarContainer");
            if (container.getParameterCount() == 0) {
                getServerVarContainerMethod = container;
            }
        } catch (Throwable ignored) {
        }
    }

    private static Class<?> roamingStructMethodsClass;

    private static synchronized void ensureRoamingStructMethods(Class<?> roamingType) {
        if (roamingStructMethodsClass == roamingType && roamingForEachVarMethod != null && roamingGetPropertyMethod != null) {
            return;
        }
        try {
            roamingForEachVarMethod = roamingType.getMethod("forEachVar", java.util.function.Consumer.class);
            roamingGetPropertyMethod = roamingType.getMethod("getProperty", int.class);
            Class<?> stringPool = Class.forName("com.elfmcys.yesstevemodel.geckolib3.core.molang.util.StringPool");
            stringPoolGetNameMethod = stringPool.getMethod("getName", String.class);
            roamingStructMethodsClass = roamingType;
        } catch (Throwable ignored) {
            roamingForEachVarMethod = null;
            roamingGetPropertyMethod = null;
            stringPoolGetNameMethod = null;
        }
    }

    private static Object resolveAnimatable(Player player) {
        Capability<?> cap = resolveCapability();
        if (cap == null) {
            return null;
        }
        try {
            LazyOptional<?> lazy = player.getCapability(cap);
            Object resolved = lazy.resolve();
            // Forge's LazyOptional#resolve returns an Optional, not the stored
            // value directly (see the 1.20.1 Forge sources). Unwrap it before
            // reflecting over the YSM PlayerCapability.
            if (resolved instanceof java.util.Optional<?> optional) {
                return optional.orElse(null);
            }
            return resolved;
        } catch (Throwable t) {
            logFailureOnce(t);
            return null;
        }
    }

    private static Capability<?> resolveCapability() {
        if (capabilityResolved) {
            return capability;
        }
        capabilityResolved = true;
        capability = resolveCapabilityFrom(
                "com.elfmcys.yesstevemodel.O0OooOo0oOOoOoOoOooO000o",   // YSM 2.6.5 obfuscated provider
                "Oo0Oo0o00O00Oo0OOoOOoooo");
        if (capability == null) {
            capability = resolveCapabilityFrom(
                    "com.elfmcys.yesstevemodel.capability.PlayerCapabilityProvider", // OpenYSM / ModernYSM
                    "PLAYER_CAP");
        }
        if (capability == null) {
            YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: [wheel] YSM PlayerCapability not resolvable, wheel animation bridge disabled");
        }
        return capability;
    }

    private static Capability<?> resolveCapabilityFrom(String className, String fieldName) {
        try {
            Class<?> providerClass = Class.forName(className);
            Field field = providerClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof Capability<?> cap ? cap : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ensureMethods(Class<?> animatableClass) {
        if (methodLookupDone) {
            return;
        }
        methodLookupDone = true;
        getSelectedModelIdMethod = findNoArgStringMethod(animatableClass,
                "OOOoOOo0oO00O0OoOO0oO00O", "getSelectedModelId");
        isModelSwitchingMethod = findNoArgBooleanMethod(animatableClass,
                "O0OooOo0oOOoOoOoOooO000o", "isModelSwitching");
    }

    private static Method findNoArgStringMethod(Class<?> type, String... names) {
        for (String name : names) {
            try {
                Method method = type.getMethod(name);
                if (method.getParameterCount() == 0 && method.getReturnType() == String.class) {
                    return method;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findNoArgBooleanMethod(Class<?> type, String... names) {
        for (String name : names) {
            try {
                Method method = type.getMethod(name);
                if (method.getParameterCount() == 0 && method.getReturnType() == boolean.class) {
                    return method;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static volatile boolean failureLogged;
    private static volatile boolean missingMethodsLogged;

    private static void logFailureOnce(Throwable t) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: failed to read YSM wheel animation state", t);
    }

    /** Force re-resolution after a YSM mod replacement / resource reload. */
    public static void invalidate() {
        capability = null;
        capabilityResolved = false;
        getSelectedModelIdMethod = null;
        isModelSwitchingMethod = null;
        methodLookupDone = false;
        failureLogged = false;
        missingMethodsLogged = false;
        getServerVarContainerMethod = null;
        roamingForEachVarMethod = null;
        roamingGetPropertyMethod = null;
        stringPoolGetNameMethod = null;
        roamingLookupDone = false;
        roamingStructMethodsClass = null;
    }
}
