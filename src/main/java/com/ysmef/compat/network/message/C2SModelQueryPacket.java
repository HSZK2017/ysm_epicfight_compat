package com.ysmef.compat.network.message;

import com.ysmef.compat.network.ModelTransferServer;
import com.ysmef.compat.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: "what is your version of this model?" The server answers with
 * {@link S2CModelInfoPacket}. Sent once per model id per connection, for every model a synced
 * player is wearing.
 */
public class C2SModelQueryPacket {

    private final String modelId;

    public C2SModelQueryPacket(String modelId) {
        this.modelId = modelId;
    }

    public static void encode(C2SModelQueryPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.modelId, NetworkHandler.MAX_MODEL_ID_LENGTH);
    }

    public static C2SModelQueryPacket decode(FriendlyByteBuf buf) {
        return new C2SModelQueryPacket(buf.readUtf(NetworkHandler.MAX_MODEL_ID_LENGTH));
    }

    public static void handle(C2SModelQueryPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (context.getDirection().getReceptionSide().isServer() && sender != null
                && NetworkHandler.isPlayerConnected(sender)) {
            context.enqueueWork(() -> ModelTransferServer.onQuery(sender, message.modelId));
        }
        context.setPacketHandled(true);
    }
}
