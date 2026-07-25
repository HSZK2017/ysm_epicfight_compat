package com.ysmef.compat.renderer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the Yes Steve Model model selection of a player without any compile-time
 * dependency on YSM classes.
 *
 * YSM's runtime classes are obfuscated, so direct reflection is impossible. Instead the
 * model selection is read from the player's capability NBT: YSM attaches its
 * ModelInfoCapability under the key "yes_steve_model:model_id" and syncs it to clients,
 * where it is serialized through Forge's capability persistence (ForgeCaps).
 *
 * The capability stores plain strings: the model id (e.g. "wine_fox/01_taisho_maid",
 * or "my_model.ysm" for binary packages) and the selected texture name.
 *
 * The NBT snapshot is cached briefly per player to avoid serializing the full player
 * every frame.
 */
@OnlyIn(Dist.CLIENT)
public final class YSMModelAccess {

    private static final String FORGE_CAPS_KEY = "ForgeCaps";
    private static final String YSM_CAP_KEY = "yes_steve_model:model_id";
    private static final String MODEL_ID_TAG = "model_id";
    private static final String TEXTURE_TAG = "select_texture";
    private static final long CACHE_TTL_TICKS = 20;

    private record CacheEntry(YSMModelRef model, long gameTime) {}

    private static final Map<UUID, CacheEntry> CACHE = new ConcurrentHashMap<>();

    public record YSMModelRef(String modelId, String textureName) {}

    /**
     * Get the current YSM model selection of the player, or null if the player has no
     * YSM model (or YSM is not installed / data not synced yet).
     */
    public static YSMModelRef getCurrentModel(Player player) {
        if (player == null || player.level() == null) {
            return null;
        }
        long gameTime = player.level().getGameTime();
        UUID uuid = player.getUUID();
        CacheEntry entry = CACHE.get(uuid);
        if (entry != null && gameTime - entry.gameTime() < CACHE_TTL_TICKS) {
            return entry.model();
        }

        YSMModelRef model = readFromCapabilityNbt(player);
        CACHE.put(uuid, new CacheEntry(model, gameTime));
        logCapabilityRead(player, model);
        return model;
    }

    private static void logCapabilityRead(Player player, YSMModelRef model) {
        if (model != null) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: player '{}' uses YSM model '{}' with texture '{}'",
                    player.getGameProfile().getName(), model.modelId(), model.textureName());
        } else {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.debug(
                    "YSM-EF Compat: no YSM model capability data for player '{}'",
                    player.getGameProfile().getName());
        }
    }

    private static YSMModelRef readFromCapabilityNbt(Player player) {
        try {
            CompoundTag tag = player.saveWithoutId(new CompoundTag());
            if (!tag.contains(FORGE_CAPS_KEY, CompoundTag.TAG_COMPOUND)) {
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
            String modelId = modelInfo.getString(MODEL_ID_TAG);
            String texture = modelInfo.getString(TEXTURE_TAG);
            if (modelId.isEmpty()) {
                return null;
            }
            return new YSMModelRef(modelId, texture);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Clear the per-player selection cache (called on resource reload / disconnect).
     */
    public static void clearCache() {
        CACHE.clear();
    }
}
