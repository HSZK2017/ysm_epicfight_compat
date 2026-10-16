package com.ysmef.compat.network;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.network.message.C2SModelDownloadPacket;
import com.ysmef.compat.network.message.C2SModelQueryPacket;
import com.ysmef.compat.network.message.C2SVersionCheckPacket;
import com.ysmef.compat.network.message.S2CModelChunkPacket;
import com.ysmef.compat.network.message.S2CModelInfoPacket;
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

    /**
     * The model-sync handshake version (S2C/C2S version check). Deliberately still 1.0.0 after the
     * model transfer was added: the selection sync itself did not change, and every build compares
     * this string for equality, so bumping it would stop selections syncing between this build and
     * any earlier one in both directions. Transfer support is negotiated separately, through the
     * Forge channel version ({@link #CHANNEL_VERSION}, see {@link #peerSupportsTransfer}).
     */
    public static final String VERSION = "1.0.0";

    /**
     * The Forge channel version, exchanged by Forge's own login handshake and readable on both sides
     * from the connection. 1.1.0 = this build answers and sends model transfers (message ids 4-7).
     * Both acceptance predicates stay permissive, so no combination of versions refuses a login.
     */
    public static final String CHANNEL_VERSION = "1.1.0";

    /** Upper bound on a model id on the wire; real ids are short relative paths. */
    public static final int MAX_MODEL_ID_LENGTH = 256;

    public static final ResourceLocation CHANNEL_ID = ResourceLocation.fromNamespaceAndPath(
            YSMEpicFightCompat.MODID, "model_sync");

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID, () -> CHANNEL_VERSION, str -> true, str -> true);

    /**
     * Whether the peer on {@code connection} handles the model transfer messages: its channel version
     * from the Forge login handshake is 1.1 or later. An older peer has no handler for ids 4-7 and
     * would log an "invalid discriminator" error for each one, so nothing is sent to it. False when
     * Forge recorded no handshake data (in-memory singleplayer connection, which needs no transfer).
     */
    public static boolean peerSupportsTransfer(Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            net.minecraftforge.network.ConnectionData data = net.minecraftforge.network.NetworkHooks
                    .getConnectionData(connection);
            return data != null && supportsTransfer(data.getChannels().get(CHANNEL_ID));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether a channel version string is 1.1 or later; unparsable and absent versions are not. */
    public static boolean supportsTransfer(String channelVersion) {
        if (channelVersion == null) {
            return false;
        }
        String[] parts = channelVersion.trim().split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 1 || (major == 1 && minor >= 1);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static final AttributeKey<String> CHANNEL_VERSION_KEY =
            AttributeKey.valueOf("ysm_epicfight_compat_model_sync");

    private static final AttributeKey<Boolean> MISMATCH_LOGGED_KEY =
            AttributeKey.valueOf("ysm_epicfight_compat_version_mismatch_logged");

    private NetworkHandler() {}

    /**
     * Store the negotiated protocol version on the connection; returns false when the stored value
     * was already there.
     *
     * <p>First handshake wins, like YSM's {@code setChannelVersion} - with one exception. Our own
     * version is allowed to replace anything else that got there first, because otherwise a wrong
     * value keeps the slot for the connection's lifetime: every model packet is gated on
     * {@link #isConnectionValid}, which compares against {@link #VERSION}, and the periodic retry
     * cannot replace a non-null attribute. The connection would simply never sync, with one warning
     * line to explain it.
     */
    public static boolean setChannelVersion(Connection connection, String version) {
        io.netty.util.Attribute<String> pinned = connection.channel().attr(CHANNEL_VERSION_KEY);
        if (pinned.compareAndSet(null, version)) {
            return true;
        }
        String current = pinned.get();
        if (VERSION.equals(version) && !VERSION.equals(current)) {
            pinned.set(version);
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: replaced the model-sync protocol version pinned on this connection "
                            + "('{}') with ours ('{}')", current, version);
            return true;
        }
        return false;
    }

    /**
     * Whether this connection has not yet reported a protocol-version mismatch; true on the first
     * call and false afterwards.
     *
     * <p>Per connection rather than per JVM: the old flag was a static boolean that stayed true for
     * the rest of the session, so the second server whose version did not match was silent.
     */
    public static boolean markVersionMismatchReported(Connection connection) {
        return connection.channel().attr(MISMATCH_LOGGED_KEY).compareAndSet(null, Boolean.TRUE);
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
        CHANNEL.registerMessage(4, C2SModelQueryPacket.class,
                C2SModelQueryPacket::encode, C2SModelQueryPacket::decode,
                C2SModelQueryPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5, S2CModelInfoPacket.class,
                S2CModelInfoPacket::encode, S2CModelInfoPacket::decode,
                S2CModelInfoPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(6, C2SModelDownloadPacket.class,
                C2SModelDownloadPacket::encode, C2SModelDownloadPacket::decode,
                C2SModelDownloadPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(7, S2CModelChunkPacket.class,
                S2CModelChunkPacket::encode, S2CModelChunkPacket::decode,
                S2CModelChunkPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendToClientPlayer(Object message, Player player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), message);
    }

    public static void sendToTrackingEntityAndSelf(Object message, Player player) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), message);
    }
}
