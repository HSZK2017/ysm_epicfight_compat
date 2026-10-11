package com.ysmef.compat.renderer;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmFork;
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

    static Object resolveAnimatable(Player player) {
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
        // YsmFork resolves the provider by scanning YSM's own class list for the
        // class declaring a Capability<PlayerCapability> field, falling back to its
        // fingerprint table. Either way the exact class and field names come from
        // there, so this no longer probes layouts blindly - a blind probe chain
        // that omitted ModernYSM's forge.capability layout is precisely what
        // silently disabled the wheel bridge on ModernYSM.
        YsmFork.Info forkInfo = YsmFork.info();

        String providerClass = forkInfo.playerCapabilityProviderClass();
        if (providerClass == null) {
            // Obfuscated original: the provider exists but only under an obfuscated
            // name, which the scan cannot turn into a readable one.
            providerClass = forkInfo.obfuscatedPlayerCapabilityProviderClass();
        }
        if (providerClass != null) {
            capability = resolveCapabilityFrom(providerClass, forkInfo.playerCapabilityField());
        }
        if (capability == null && !forkInfo.obfuscated()) {
            // Last resort for a build whose provider moved somewhere the scan could
            // not classify: try the other known readable layouts.
            for (String candidate : new String[]{
                    "com.elfmcys.yesstevemodel.forge.capability.PlayerCapabilityProvider",
                    "com.elfmcys.yesstevemodel.capability.PlayerCapabilityProvider"}) {
                if (candidate.equals(providerClass)) {
                    continue;
                }
                capability = resolveCapabilityFrom(candidate, YsmFork.PLAYER_CAPABILITY_FIELD);
                if (capability != null) {
                    break;
                }
            }
        }

        if (capability == null) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [wheel] YSM PlayerCapability not resolvable for fork {} (provider {}), wheel animation bridge disabled",
                    forkInfo.fork(), providerDescription(forkInfo));
        } else {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [wheel] YSM PlayerCapability resolved for fork {} (provider {})",
                    forkInfo.fork(), providerDescription(forkInfo));
        }
        return capability;
    }

    /** Human-readable description of the provider class + field used for a fork. */
    private static String providerDescription(YsmFork.Info forkInfo) {
        String className = forkInfo.playerCapabilityProviderClass();
        if (className == null) {
            className = forkInfo.obfuscatedPlayerCapabilityProviderClass();
        }
        return className == null ? "not addressable" : className + "#" + forkInfo.playerCapabilityField();
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
                "OOOoOOo0oO00O0OoOO0oO00O", "getSelectedModelId", "getModelId");
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
        YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: [wheel] failed to read YSM wheel state", t);
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
    }
}
