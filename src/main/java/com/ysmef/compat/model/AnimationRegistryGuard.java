package com.ysmef.compat.model;

import com.ysmef.compat.YSMEpicFightCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether an animation registry name belongs to this mod's generated
 * runtime templates and therefore must be excluded from Epic Fight's
 * client-vs-server animation registry consistency check
 * (AnimationManager#validateClientAnimationRegistry).
 *
 * <p>Wheel templates ({@code ysm_epicfight_compat:public/pub_*}) are generated
 * on each client from that client's own YSM model data, so the concrete ids
 * can never exist on a dedicated server and the two sides can never agree.
 * Epic Fight 20.14.17 kicks any player whose registry differs, which is why a
 * player using the wheel bridge was disconnected on join. The namespace is
 * matched after stripping every non-alphanumeric character: some builds
 * produced registry names with mangled separators (missing ':' or '/', '_'
 * turned into spaces or dropped), and the normalization covers both the
 * canonical form and all those legacy variants.
 */
public final class AnimationRegistryGuard {

    /** Normalized (alphanumeric-only) form of {@code ysm_epicfight_compat}. */
    private static final String NORMALIZED_PREFIX = "ysmepicfightcompat";

    private AnimationRegistryGuard() {
    }

    /**
     * Whether an animation registry name (canonical
     * {@code ysm_epicfight_compat:public/pub_...} or any mangled legacy
     * variant) belongs to this mod's runtime-generated templates.
     */
    public static boolean shouldIgnore(String registryName) {
        if (registryName == null || registryName.isEmpty()) {
            return false;
        }
        return normalized(registryName).startsWith(NORMALIZED_PREFIX);
    }

    /** Lowercase copy of the input with every non-alphanumeric character removed. */
    private static String normalized(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * Report whether the injection that applies {@link #shouldIgnore} can match this Epic Fight
     * build, so its absence is visible instead of silent.
     *
     * <p>{@code AnimationManagerValidationMixin} is a soft injection ({@code require = 0}), which is
     * the right choice - an Epic Fight update must not turn into a boot failure - but a soft
     * injection that does not match produces <b>no</b> diagnostic at all: Mixin's
     * {@code InjectionInfo.postInject()} has no logging path when {@code require = 0}, and a
     * superclass/field rename is not even a target-miss. The failure mode is therefore invisible and
     * expensive: the exemption quietly stops applying, and every player who used the wheel bridge is
     * disconnected on join with {@code gui.epicfight.warn.animation_unsync} - the exact bug this
     * mod's exemption exists to remove - with nothing in the log connecting the two.
     *
     * <p>So the target is resolved by reflection at startup and the result is stated. The check is a
     * proxy (a method that exists can still fail to match for other reasons), which is why the log
     * line says what it verified rather than claiming the injection applied.
     *
     * <p>Called from the mod constructor, so it runs on both sides: the mixin is in the common
     * section of the mixin config and the exempted method runs on the server.
     */
    public static void reportExemptionTarget() {
        try {
            Class<?> manager = Class.forName("yesman.epicfight.api.animation.AnimationManager",
                    false, AnimationRegistryGuard.class.getClassLoader());
            java.lang.reflect.Method exact = null;
            List<String> sameName = new ArrayList<>();
            for (java.lang.reflect.Method method : manager.getDeclaredMethods()) {
                if (!"validateClientAnimationRegistry".equals(method.getName())) {
                    continue;
                }
                sameName.add(method.toString());
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 2
                        && parameters[0].getName().endsWith("CPCheckAnimationRegistryMatches")
                        && parameters[1].getName().endsWith("ServerGamePacketListenerImpl")) {
                    exact = method;
                }
            }
            if (exact != null) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: animation registry exemption target resolved: {}", exact);
            } else if (!sameName.isEmpty()) {
                YSMEpicFightCompat.LOGGER.error(
                        "YSM-EF Compat: Epic Fight's validateClientAnimationRegistry no longer has the "
                                + "signature this mod exempts (found {}); the generated wheel templates are no longer "
                                + "excluded from the client/server animation registry check, so every player using the "
                                + "wheel bridge will be disconnected on join with gui.epicfight.warn.animation_unsync",
                        sameName);
            } else {
                YSMEpicFightCompat.LOGGER.error(
                        "YSM-EF Compat: Epic Fight does not declare validateClientAnimationRegistry any more; the "
                                + "generated wheel templates are no longer excluded from the client/server animation "
                                + "registry check, so every player using the wheel bridge will be disconnected on join "
                                + "with gui.epicfight.warn.animation_unsync");
            }
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: could not verify the animation registry exemption target", t);
        }
    }
}
