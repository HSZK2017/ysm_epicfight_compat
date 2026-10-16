package com.ysmef.compat.network.message;

import com.ysmef.compat.network.ModelTransferServer;
import com.ysmef.compat.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: send the package whose hash the server just offered. The hash ties the request
 * to that offer; when the server's file changed in between, it answers with a fresh
 * {@link S2CModelInfoPacket} instead of sending something the client did not ask for.
 */
public class C2SModelDownloadPacket {

    private final String modelId;
    private final String sha256;

    public C2SModelDownloadPacket(String modelId, String sha256) {
        this.modelId = modelId;
        this.sha256 = sha256;
    }

    public static void encode(C2SModelDownloadPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.modelId, NetworkHandler.MAX_MODEL_ID_LENGTH);
        buf.writeUtf(message.sha256, 64);
    }

    public static C2SModelDownloadPacket decode(FriendlyByteBuf buf) {
        return new C2SModelDownloadPacket(buf.readUtf(NetworkHandler.MAX_MODEL_ID_LENGTH), buf.readUtf(64));
    }

    public static void handle(C2SModelDownloadPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (context.getDirection().getReceptionSide().isServer() && sender != null
                && NetworkHandler.isPlayerConnected(sender)) {
            context.enqueueWork(() -> ModelTransferServer.onDownload(sender, message.modelId, message.sha256));
        }
        context.setPacketHandled(true);
    }
}
