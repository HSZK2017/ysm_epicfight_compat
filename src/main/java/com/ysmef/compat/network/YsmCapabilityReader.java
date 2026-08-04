package com.ysmef.compat.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Reads a player's Yes Steve Model model selection from the serializable
 * ForgeCaps NBT without any compile-time dependency on YSM's (obfuscated)
 * classes. Shared by the client-side selection cache (YSMModelAccess) and the
 * server-side multiplayer model broadcaster (ModelSyncServer).
 *
 * The NBT layout mirrors YSM 2.6.5 / OpenYSM's ModelInfoCapability
 * serialization: ForgeCaps -> yes_steve_model:model_id -> model_id +
 * select_texture (+ disabled).
 */
public final class YsmCapabilityReader {

    private static final String FORGE_CAPS_KEY = "ForgeCaps";
    private static final String YSM_CAP_KEY = "yes_steve_model:model_id";
    private static final String MODEL_ID_TAG = "model_id";
    private static final String TEXTURE_TAG = "select_texture";
    private static final String DISABLED_TAG = "disabled";

    public record Selection(String modelId, String textureName) {}

    private YsmCapabilityReader() {}

    /**
     * Read the selection from a player entity. The serializable capability is
     * attached server-side, so on the client this only yields data for the
     * integrated server's player entities; on a dedicated server this works
     * for every ServerPlayer.
     */
    public static Selection readFromPlayer(Player player) {
        if (player == null) {
            return null;
        }
        try {
            return parse(player.saveWithoutId(new CompoundTag()));
        } catch (Exception e) {
            return null;
        }
    }

    public static Selection parse(CompoundTag tag) {
        if (tag == null || !tag.contains(FORGE_CAPS_KEY, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag caps = tag.getCompound(FORGE_CAPS_KEY);
        if (!caps.contains(YSM_CAP_KEY, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag modelInfo = caps.getCompound(YSM_CAP_KEY);
        if (!modelInfo.contains(MODEL_ID_TAG, CompoundTag.TAG_STRING)
                || !modelInfo.contains(TEXTURE_TAG, CompoundTag.TAG_STRING)) {
            return null;
        }
        if (modelInfo.getBoolean(DISABLED_TAG)) {
            return null;
        }
        String modelId = modelInfo.getString(MODEL_ID_TAG);
        if (modelId.isEmpty()) {
            return null;
        }
        return new Selection(modelId, modelInfo.getString(TEXTURE_TAG));
    }
}
