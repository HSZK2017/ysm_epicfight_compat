package com.ysmef.compat.renderer;

import com.ysmef.compat.YSMEpicFightCompat;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    private static Object resolveAnimatable(Player player) {
        Capability<?> cap = resolveCapability();
        if (cap == null) {
            return null;
        }
        try {
            LazyOptional<?> lazy = player.getCapability(cap);
            return lazy.resolve();
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
            YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: YSM PlayerCapability not resolvable, wheel animation bridge disabled");
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
    }
}
