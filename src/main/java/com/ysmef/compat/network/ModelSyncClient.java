package com.ysmef.compat.network;

import com.ysmef.compat.YSMEpicFightCompat;

import java.util.Map;
import java.util.Set;
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

    /**
     * Cap on the registry, and the length a name may have.
     *
     * <p>The entries are written from packets a server we do not control sends, and before this the
     * map had no bound at all and no content check: a server could grow it until the client ran out
     * of memory, using UUIDs that need not belong to any player (nothing evicts those - only leaving
     * the world clears the map). A few thousand entries is far above any real player count and
     * bounded by construction.
     */
    private static final int MAX_SYNCED_MODELS = 4096;
    private static final int MAX_NAME_LENGTH = 128;

    /** One log line per kind of rejection; a hostile server must not be able to spam the log. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private ModelSyncClient() {}

    /**
     * Apply a broadcast selection: an empty model id or the disabled flag
     * stores the no-model sentinel (the player renders with the Epic Fight
     * biped, and the client never serializes the full player NBT for them).
     *
     * <p>An entry with an implausible name, or one that would exceed the cap, is dropped rather than
     * stored; see {@link #MAX_SYNCED_MODELS}.
     */
    public static void applySyncedModel(UUID uuid, String modelId, String textureName, boolean disabled) {
        if (uuid == null) {
            return;
        }
        if (disabled || modelId == null || modelId.isEmpty()) {
            store(uuid, NO_MODEL);
            return;
        }
        if (modelId.length() > MAX_NAME_LENGTH
                || (textureName != null && textureName.length() > MAX_NAME_LENGTH)) {
            if (REPORTED.add("length")) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: ignoring a model-sync entry with an implausible name (modelId {} chars, "
                                + "texture {} chars, max {})", modelId.length(),
                        textureName == null ? 0 : textureName.length(), MAX_NAME_LENGTH);
            }
            return;
        }
        store(uuid, new SyncedModel(modelId, textureName == null ? "" : textureName));
    }

    /** Store one entry, refusing to grow the map past the cap for a UUID it does not yet hold. */
    private static void store(UUID uuid, SyncedModel model) {
        if (!SYNCED.containsKey(uuid) && SYNCED.size() >= MAX_SYNCED_MODELS) {
            if (REPORTED.add("cap")) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: model-sync registry is full ({} entries); further entries are ignored "
                                + "until the world is left", MAX_SYNCED_MODELS);
            }
            return;
        }
        SYNCED.put(uuid, model);
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
