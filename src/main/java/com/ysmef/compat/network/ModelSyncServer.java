package com.ysmef.compat.network;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.network.message.S2CSetModelAndTexturePacket;
import com.ysmef.compat.network.message.S2CVersionCheckPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side YSM model selection broadcaster, mirroring the OpenYSM sync flow
 * (参考/OpenYSM .../capability/ModelInfoCapability.java + event/CapabilityEvent.java):
 *
 * - on join the server sends S2CVersionCheckPacket; once the client replies
 *   with a matching version (C2SVersionCheckPacket -> onClientHandshake),
 *   every online player's current selection is streamed to it
 * - PlayerEvent.StartTracking pushes the tracked player's selection to the
 *   tracker (OpenYSM's onStartTracking)
 * - a periodic server tick diff (every 40 ticks, half OpenYSM's 20-tick
 *   dirty-capability polling cadence - detecting a change requires
 *   serializing the full player NBT, so the diff is the dominant server-side
 *   cost) detects model switches, texture switches and entity id changes
 *   (respawn, dimension change) and re-broadcasts them to tracking players +
 *   self (OpenYSM's sendToTrackingEntityAndSelf)
 *
 * The selection itself is read from the serializable YSM ModelInfoCapability
 * NBT (model_id / select_texture) via YsmCapabilityReader, the same data
 * source the client-side cache uses; no compile-time dependency on YSM's
 * (obfuscated) classes.
 */
@Mod.EventBusSubscriber(modid = YSMEpicFightCompat.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModelSyncServer {

    /**
     * Cadence of the change-detection diff (every 40 ticks / 2 s). Detecting a
     * change requires serializing each player's full NBT (the capability class
     * is obfuscated and can only be read through ForgeCaps serialization), so
     * the cadence is a direct trade-off between server thread cost and
     * change-propagation latency. Join, handshake and StartTracking push
     * selections immediately, so the slower diff only delays picking up a
     * model switch by up to 2 s.
     */
    private static final int DIFF_INTERVAL_TICKS = 40;
    private static final int VERSION_RETRY_TICKS = 200;

    private record Snapshot(int entityId, String modelId, String textureName) {}

    private static final Map<UUID, Snapshot> LAST = new ConcurrentHashMap<>();

    private ModelSyncServer() {}

    /**
     * Kick off the version handshake when a server player enters the level
     * (covers login and respawn; the connection attribute makes re-sends
     * idempotent on both sides).
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NetworkHandler.sendToClientPlayer(new S2CVersionCheckPacket(), player);
        }
    }

    /**
     * When a client starts tracking another player, push the tracked player's
     * current selection to the tracker (mirrors OpenYSM's onStartTracking).
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer tracked && event.getEntity() instanceof ServerPlayer tracker) {
            sendModelToPlayer(tracked, tracker);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LAST.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        for (ServerPlayer player : players) {
            if (!NetworkHandler.isPlayerConnected(player) && player.tickCount % VERSION_RETRY_TICKS == 0) {
                NetworkHandler.sendToClientPlayer(new S2CVersionCheckPacket(), player);
            }
        }
        if (server.getTickCount() % DIFF_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : players) {
            Snapshot current = readSnapshot(player);
            Snapshot previous = LAST.put(player.getUUID(), current);
            if (!current.equals(previous)) {
                broadcastModel(player, current);
            }
        }
    }

    /**
     * Called from C2SVersionCheckPacket once the client confirmed a matching
     * protocol version: stream every online player's current selection to it.
     */
    public static void onClientHandshake(ServerPlayer player) {
        YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: player '{}' completed model sync handshake",
                player.getGameProfile().getName());
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            sendModelToPlayer(other, player);
        }
    }

    private static void sendModelToPlayer(ServerPlayer target, ServerPlayer recipient) {
        if (!NetworkHandler.isPlayerConnected(recipient)) {
            return;
        }
        Snapshot snapshot = readSnapshot(target);
        NetworkHandler.sendToClientPlayer(new S2CSetModelAndTexturePacket(
                target.getId(), target.getUUID(), snapshot.modelId(), snapshot.textureName(),
                snapshot.modelId().isEmpty()), recipient);
    }

    private static void broadcastModel(ServerPlayer player, Snapshot snapshot) {
        NetworkHandler.sendToTrackingEntityAndSelf(new S2CSetModelAndTexturePacket(
                player.getId(), player.getUUID(), snapshot.modelId(), snapshot.textureName(),
                snapshot.modelId().isEmpty()), player);
        YSMEpicFightCompat.LOGGER.debug(
                "YSM-EF Compat: broadcast model '{}' texture '{}' for player '{}' (entity {})",
                snapshot.modelId(), snapshot.textureName(), player.getGameProfile().getName(), snapshot.entityId());
    }

    private static Snapshot readSnapshot(ServerPlayer player) {
        YsmCapabilityReader.Selection selection = YsmCapabilityReader.readFromPlayer(player);
        if (selection == null) {
            return new Snapshot(player.getId(), "", "");
        }
        return new Snapshot(player.getId(), selection.modelId(), selection.textureName());
    }
}
