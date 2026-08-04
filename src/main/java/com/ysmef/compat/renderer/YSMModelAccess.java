package com.ysmef.compat.renderer;

import com.ysmef.compat.network.ModelSyncClient;
import com.ysmef.compat.network.YsmCapabilityReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the Yes Steve Model model selection of a player without any compile-time
 * dependency on YSM classes.
 *
 * YSM's runtime classes are obfuscated, and its ModelInfoCapability (which stores the
 * persistent selection under the ForgeCaps key "yes_steve_model:model_id") is attached
 * to players on the SERVER side only. On the client side, the current model lives in a
 * non-serializable animatable capability that cannot be read without referencing
 * obfuscated classes.
 *
 * The selection is therefore resolved from three sources, in order:
 * 1. the integrated server's player entities (which do carry the serializable
 *    capability) - covers single-player and self-hosted servers;
 * 2. the model-sync channel (see com.ysmef.compat.network): when the mod runs on the
 *    server too, every player's selection is streamed to the client over the network
 *    after a version handshake, following the OpenYSM/YSM 2.6.5 protocol - this covers
 *    dedicated servers for both remote players and the local player;
 * 3. the client capability NBT (normally absent, degrading to Epic Fight's biped mesh).
 *
 * The NBT snapshot is cached briefly per player to avoid serializing the full player
 * every frame. The cache is keyed per client world (the client Level instance): a new
 * world (or returning to the menu and re-entering a world) always gets a fresh read,
 * so a model selection from a previous world can never pin the current one. This
 * matters because the game-time-based TTL alone is unsafe across worlds: entering a
 * new save whose game time is lower than the previous save's would keep a stale entry
 * "fresh" (negative delta is always below the TTL), pinning the old model forever.
 */
@OnlyIn(Dist.CLIENT)
public final class YSMModelAccess {

    private static final long CACHE_TTL_TICKS = 20;

    private record CacheEntry(Level level, YSMModelRef model, long gameTime) {}

    private static final Map<UUID, CacheEntry> CACHE = new ConcurrentHashMap<>();

    public record YSMModelRef(String modelId, String textureName) {}

    /**
     * Get the current YSM model selection of the player, or null if the player has no
     * YSM model (or the selection cannot be determined, e.g. on a server without the
     * model-sync channel).
     */
    public static YSMModelRef getCurrentModel(Player player) {
        if (player == null || player.level() == null) {
            return null;
        }
        Level level = player.level();
        long gameTime = level.getGameTime();
        UUID uuid = player.getUUID();
        CacheEntry entry = CACHE.get(uuid);
        if (entry != null && entry.level() == level && gameTime - entry.gameTime() < CACHE_TTL_TICKS) {
            return entry.model();
        }

        YSMModelRef model = readModel(player);
        CACHE.put(uuid, new CacheEntry(level, model, gameTime));
        logCapabilityRead(player, model);
        return model;
    }

    private static YSMModelRef readModel(Player player) {
        YSMModelRef fromServer = readFromIntegratedServer(player);
        if (fromServer != null) {
            return fromServer;
        }
        ModelSyncClient.SyncedModel synced = ModelSyncClient.getSyncedModel(player.getUUID());
        if (synced != null) {
            return new YSMModelRef(synced.modelId(), synced.textureName());
        }
        return readFromCapabilityNbt(player);
    }

    /**
     * Reads the selection from the integrated server's player entity, whose
     * ModelInfoCapability is attached and synced with the actual selection.
     */
    private static YSMModelRef readFromIntegratedServer(Player player) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return null;
            }
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
            if (serverPlayer == null) {
                return null;
            }
            YsmCapabilityReader.Selection selection = YsmCapabilityReader.readFromPlayer(serverPlayer);
            return selection == null ? null : new YSMModelRef(selection.modelId(), selection.textureName());
        } catch (Exception e) {
            return null;
        }
    }

    private static YSMModelRef readFromCapabilityNbt(Player player) {
        YsmCapabilityReader.Selection selection = YsmCapabilityReader.readFromPlayer(player);
        return selection == null ? null : new YSMModelRef(selection.modelId(), selection.textureName());
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

    /**
     * Clear the per-player NBT selection cache (called on resource reload and
     * when leaving a world / disconnecting, see YSMReloadTrigger).
     *
     * The synced selections (ModelSyncClient) are deliberately NOT cleared
     * here: the server only re-broadcasts on change, so clearing them on F3+T
     * would pin remote players to the Epic Fight biped until their model
     * changes. They are cleared separately when leaving the world.
     */
    public static void clearCache() {
        CACHE.clear();
    }
}
