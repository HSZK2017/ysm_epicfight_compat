package com.ysmef.compat.network.message;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client protocol version check, mirroring OpenYSM's
 * S2CVersionCheckPacket (message id 51). Stores the negotiated version on the
 * connection and replies with C2SVersionCheckPacket; model packets are only
 * applied while the stored version matches ours.
 */
public class S2CVersionCheckPacket {

    private final String version;

    public S2CVersionCheckPacket() {
        this(NetworkHandler.VERSION);
    }

    private S2CVersionCheckPacket(String version) {
        this.version = version;
    }

    public static S2CVersionCheckPacket decode(FriendlyByteBuf buf) {
        return new S2CVersionCheckPacket(buf.readUtf());
    }

    public static void encode(S2CVersionCheckPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.version);
    }

    public static void handle(S2CVersionCheckPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            if (!NetworkHandler.VERSION.equals(message.version)) {
                // The server runs a different protocol version: model packets
                // will never pass isConnectionValid on this side. The handshake
                // stays compatible (we still reply), but the user must know the
                // sync is off instead of silently rendering bipeds forever.
                if (!MISMATCH_LOGGED) {
                    MISMATCH_LOGGED = true;
                    YSMEpicFightCompat.LOGGER.warn(
                            "YSM-EF Compat: server model-sync protocol version '{}' does not match ours ('{}'); "
                                    + "YSM model selections will not sync from this server",
                            message.version, NetworkHandler.VERSION);
                }
            }
            if (NetworkHandler.setChannelVersion(context.getNetworkManager(), message.version)) {
                context.enqueueWork(() -> YSMEpicFightCompat.LOGGER.debug(
                        "YSM-EF Compat: model sync handshake, server protocol version '{}'", message.version));
            }
        }
        NetworkHandler.CHANNEL.reply(new C2SVersionCheckPacket(), context);
        context.setPacketHandled(true);
    }

    private static volatile boolean MISMATCH_LOGGED = false;
}
