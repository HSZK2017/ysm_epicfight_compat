package com.ysmef.compat.ysm;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model packages downloaded from the connected server, and which of them currently stand in for the
 * local copy of the same model id.
 *
 * <p>A server's copy lives under {@code config/ysm_epicfight_compat/server_models/<serverId>/}, laid
 * out like one YSM root, and never inside {@code config/yes_steve_model}: that folder is YSM's own and
 * the player's, a same-named local model may be a different one the player wants to keep, and YSM
 * would start offering every downloaded model in single player.
 *
 * <p>Only ids the connected server confirmed in this session are active (the server's current hash
 * matched the downloaded copy, or the copy was just received). A copy left from an earlier session
 * is not trusted until then - the server may have changed the model since. Everything is dropped
 * when the connection ends, so single player and other servers see the local files again.
 *
 * <p>Plain Java on purpose: {@link YsmModelPackage} consults it on every lookup, on both sides.
 */
public final class ServerModelOverlay {

    /** The root label a resolved overlay package carries, in place of builtin/custom/auth. */
    public static final String ROOT_NAME = "server";

    private static final Map<String, Path> ACTIVE = new ConcurrentHashMap<>();

    private ServerModelOverlay() {}

    /** Use {@code overlayRoot} as the only root for {@code modelId} until deactivated or cleared. */
    public static void activate(String modelId, Path overlayRoot) {
        ACTIVE.put(modelId, overlayRoot.toAbsolutePath().normalize());
    }

    /** Stop overriding {@code modelId}; true when it was active. */
    public static boolean deactivate(String modelId) {
        return ACTIVE.remove(modelId) != null;
    }

    /** The overlay root that replaces the YSM roots for this id, or null when the local copy applies. */
    public static Path rootFor(String modelId) {
        return modelId == null ? null : ACTIVE.get(modelId);
    }

    /** Every id currently overridden (a copy). */
    public static Set<String> activeIds() {
        return Set.copyOf(ACTIVE.keySet());
    }

    /**
     * The server id shape both sides accept. It becomes a folder name on the client, so nothing
     * beyond lowercase hex and '-' (a UUID's alphabet) gets through.
     */
    public static boolean isValidServerId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** Drop every override; returns the ids that were active. */
    public static Set<String> clear() {
        Set<String> ids = activeIds();
        ACTIVE.clear();
        return ids;
    }
}
