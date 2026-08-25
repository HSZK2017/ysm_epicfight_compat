package com.ysmef.compat.model;

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
}
