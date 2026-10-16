package com.ysmef.compat.network.message;

import com.ysmef.compat.network.ModelTransferClient;
import com.ysmef.compat.network.ModelTransferStatus;
import com.ysmef.compat.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client answer to {@link C2SModelQueryPacket}: the status, and for
 * {@link ModelTransferStatus#AVAILABLE} the package's size and SHA-256 plus the server's id (the
 * client keeps each server's copies in their own folder).
 */
public class S2CModelInfoPacket {

    private final String modelId;
    private final ModelTransferStatus status;
    private final String serverId;
    private final long size;
    private final String sha256;

    public S2CModelInfoPacket(String modelId, ModelTransferStatus status, String serverId, long size, String sha256) {
        this.modelId = modelId;
        this.status = status;
        this.serverId = serverId == null ? "" : serverId;
        this.size = size;
        this.sha256 = sha256 == null ? "" : sha256;
    }

    public static S2CModelInfoPacket unavailable(String modelId, ModelTransferStatus status) {
        return new S2CModelInfoPacket(modelId, status, "", 0L, "");
    }

    public static void encode(S2CModelInfoPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.modelId, NetworkHandler.MAX_MODEL_ID_LENGTH);
        buf.writeByte(message.status.ordinal());
        buf.writeUtf(message.serverId, 64);
        buf.writeVarLong(message.size);
        buf.writeUtf(message.sha256, 64);
    }

    public static S2CModelInfoPacket decode(FriendlyByteBuf buf) {
        return new S2CModelInfoPacket(buf.readUtf(NetworkHandler.MAX_MODEL_ID_LENGTH),
                ModelTransferStatus.fromOrdinal(buf.readUnsignedByte()), buf.readUtf(64), buf.readVarLong(),
                buf.readUtf(64));
    }

    public static void handle(S2CModelInfoPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()
                && NetworkHandler.isConnectionValid(context.getNetworkManager())) {
            context.enqueueWork(() -> ModelTransferClient.onInfo(
                    message.modelId, message.status, message.serverId, message.size, message.sha256));
        }
        context.setPacketHandled(true);
    }
}
