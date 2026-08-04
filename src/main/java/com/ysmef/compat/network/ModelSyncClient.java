package com.ysmef.compat.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side registry of the YSM model selections broadcast by the server
 * through the model-sync channel (S2CSetModelAndTexturePacket). Lives in
 * common code so the packet handler can reference it from either side; the
 * renderer reads it through YSMModelAccess (client-only).
 *
 * Entries are keyed by player UUID and are only removed when the server
 * explicitly reports no model (empty model id / disabled), or when leaving a
 * world (see YSMReloadTrigger). They survive resource reloads on purpose: the
 * server only re-broadcasts on change, so clearing them on F3+T would pin
 * remote players to the Epic Fight biped until their model changes.
 */
public final class ModelSyncClient {

    public record SyncedModel(String modelId, String textureName) {}

    private static final Map<UUID, SyncedModel> SYNCED = new ConcurrentHashMap<>();

    private ModelSyncClient() {}

    /**
     * Apply a broadcast selection: an empty model id or the disabled flag
     * clears the entry (the player renders with the Epic Fight biped).
     */
    public static void applySyncedModel(UUID uuid, String modelId, String textureName, boolean disabled) {
        if (disabled || modelId == null || modelId.isEmpty()) {
            SYNCED.remove(uuid);
            return;
        }
        SYNCED.put(uuid, new SyncedModel(modelId, textureName == null ? "" : textureName));
    }

    /**
     * The synced selection of the player, or null if unknown / no model.
     */
    public static SyncedModel getSyncedModel(UUID uuid) {
        return SYNCED.get(uuid);
    }

    /**
     * Drop all synced selections (called when leaving a world).
     */
    public static void clear() {
        SYNCED.clear();
    }
}
