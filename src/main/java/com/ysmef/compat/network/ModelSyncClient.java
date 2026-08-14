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
 * Entries are keyed by player UUID; a no-model report stores a sentinel entry
 * so the selection cache knows the server's answer is definitive and does not
 * fall back to serializing the full player NBT. Entries are only removed when
 * leaving a world (see YSMReloadTrigger). They survive resource reloads on
 * purpose: the server only re-broadcasts on change, so clearing them on F3+T
 * would pin remote players to the Epic Fight biped until their model changes.
 */
public final class ModelSyncClient {

    public record SyncedModel(String modelId, String textureName) {}

    private static final Map<UUID, SyncedModel> SYNCED = new ConcurrentHashMap<>();

    /**
     * Sentinel for "the server explicitly reported no model": unlike removing
     * the entry, this lets the client-side selection cache distinguish
     * "synced, no model" (definitive - never falls back to serializing the
     * full player NBT) from "nothing synced yet" (fall back allowed).
     */
    private static final SyncedModel NO_MODEL = new SyncedModel("", "");

    private ModelSyncClient() {}

    /**
     * Apply a broadcast selection: an empty model id or the disabled flag
     * stores the no-model sentinel (the player renders with the Epic Fight
     * biped, and the client never serializes the full player NBT for them).
     */
    public static void applySyncedModel(UUID uuid, String modelId, String textureName, boolean disabled) {
        if (disabled || modelId == null || modelId.isEmpty()) {
            SYNCED.put(uuid, NO_MODEL);
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
