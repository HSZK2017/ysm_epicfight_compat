package com.ysmef.compat.network.message;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.network.ModelSyncServer;
import com.ysmef.compat.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server protocol version reply, mirroring OpenYSM's
 * C2SVersionCheckPacket (message id 52). Once a matching version is recorded,
 * the server streams every online player's current model selection to this
 * client (ModelSyncServer.onClientHandshake).
 */
public class C2SVersionCheckPacket {

    private final String version;

    public C2SVersionCheckPacket() {
        this(NetworkHandler.VERSION);
    }

    public C2SVersionCheckPacket(String version) {
        this.version = version;
    }

    public static C2SVersionCheckPacket decode(FriendlyByteBuf buf) {
        return new C2SVersionCheckPacket(buf.readUtf());
    }

    public static void encode(C2SVersionCheckPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.version);
    }

    public static void handle(C2SVersionCheckPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                if (!NetworkHandler.VERSION.equals(message.version)) {
                    // The client runs a different protocol version; the
                    // handshake below would mark it "connected", but its
                    // isConnectionValid check would reject every model packet.
                    YSMEpicFightCompat.LOGGER.warn(
                            "YSM-EF Compat: player '{}' replied model-sync protocol version '{}' (ours '{}'); "
                                    + "model selections will not sync to this client",
                            sender.getGameProfile().getName(), message.version, NetworkHandler.VERSION);
                }
                if (NetworkHandler.setChannelVersion(context.getNetworkManager(), message.version)) {
                    context.enqueueWork(() -> ModelSyncServer.onClientHandshake(sender));
                }
            }
        }
        context.setPacketHandled(true);
    }
}
