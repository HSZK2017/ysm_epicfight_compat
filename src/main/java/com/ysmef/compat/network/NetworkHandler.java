package com.ysmef.compat.network;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.network.message.C2SVersionCheckPacket;
import com.ysmef.compat.network.message.S2CSetModelAndTexturePacket;
import com.ysmef.compat.network.message.S2CVersionCheckPacket;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Model-sync channel of the compat mod, modelled after the Yes Steve Model
 * 2.6.5 / OpenYSM network protocol (参考/OpenYSM .../network/NetworkHandler.java):
 *
 * - the channel accepts any version at login (like YSM's
 *   NetworkRegistry.newSimpleChannel with true-accept predicates); both sides
 *   run an explicit version-check handshake before any model data is
 *   exchanged: server -> client S2CVersionCheckPacket, client replies
 *   C2SVersionCheckPacket (YSM message ids 51/52). The negotiated version is
 *   stored on the netty connection via an AttributeKey, exactly like YSM's
 *   NetworkHandler.setChannelVersion
 * - once the handshake is done, the server pushes each player's current model
 *   selection with S2CSetModelAndTexturePacket (YSM message id 4: entityId,
 *   modelId, textureId, disabled), extended with the player UUID so the client
 *   can key its registry by UUID instead of YSM's entity-join callback
 *
 * The client only ever applies model packets while isConnectionValid holds
 * for its connection; the server only streams to players whose connection
 * completed the handshake (isPlayerConnected).
 */
public final class NetworkHandler {

    public static final String VERSION = "1.0.0";

    public static final ResourceLocation CHANNEL_ID = ResourceLocation.fromNamespaceAndPath(
            YSMEpicFightCompat.MODID, "model_sync");

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID, () -> VERSION, str -> true, str -> true);

    private static final AttributeKey<String> CHANNEL_VERSION_KEY =
            AttributeKey.valueOf("ysm_epicfight_compat_model_sync");

    private NetworkHandler() {}

    /**
     * Store the negotiated protocol version on the connection; returns false
     * when the version was already set (first handshake wins, like YSM).
     */
    public static boolean setChannelVersion(Connection connection, String version) {
        return connection.channel().attr(CHANNEL_VERSION_KEY).compareAndSet(null, version);
    }

    /**
     * Whether the connection completed the handshake with a matching version.
     */
    public static boolean isConnectionValid(Connection connection) {
        return connection != null && connection.channel() != null
                && VERSION.equals(connection.channel().attr(CHANNEL_VERSION_KEY).get());
    }

    /**
     * Whether the given server player's connection completed the handshake.
     */
    public static boolean isPlayerConnected(ServerPlayer serverPlayer) {
        return serverPlayer.connection != null && isConnectionValid(serverPlayer.connection.connection);
    }

    public static void init() {
        CHANNEL.registerMessage(1, S2CSetModelAndTexturePacket.class,
                S2CSetModelAndTexturePacket::encode, S2CSetModelAndTexturePacket::decode,
                S2CSetModelAndTexturePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, S2CVersionCheckPacket.class,
                S2CVersionCheckPacket::encode, S2CVersionCheckPacket::decode,
                S2CVersionCheckPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, C2SVersionCheckPacket.class,
                C2SVersionCheckPacket::encode, C2SVersionCheckPacket::decode,
                C2SVersionCheckPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void sendToClientPlayer(Object message, Player player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), message);
    }

    public static void sendToTrackingEntityAndSelf(Object message, Player player) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), message);
    }
}
