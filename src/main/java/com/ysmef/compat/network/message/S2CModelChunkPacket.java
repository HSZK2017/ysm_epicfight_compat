package com.ysmef.compat.network.message;

import com.ysmef.compat.network.ModelTransferClient;
import com.ysmef.compat.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: one consecutive slice of a model blob (see {@code ModelBlob}). Slices of one model
 * arrive in order on one connection; the client checks every offset against what it has written.
 */
public class S2CModelChunkPacket {

    /** Hard cap on a slice, well under the 1 MiB client-bound custom payload limit. */
    public static final int MAX_CHUNK_BYTES = 64 * 1024;

    private final String modelId;
    private final long offset;
    private final long total;
    private final byte[] data;

    public S2CModelChunkPacket(String modelId, long offset, long total, byte[] data) {
        this.modelId = modelId;
        this.offset = offset;
        this.total = total;
        this.data = data;
    }

    public static void encode(S2CModelChunkPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.modelId, NetworkHandler.MAX_MODEL_ID_LENGTH);
        buf.writeVarLong(message.offset);
        buf.writeVarLong(message.total);
        buf.writeByteArray(message.data);
    }

    public static S2CModelChunkPacket decode(FriendlyByteBuf buf) {
        return new S2CModelChunkPacket(buf.readUtf(NetworkHandler.MAX_MODEL_ID_LENGTH), buf.readVarLong(),
                buf.readVarLong(), buf.readByteArray(MAX_CHUNK_BYTES));
    }

    public static void handle(S2CModelChunkPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()
                && NetworkHandler.isConnectionValid(context.getNetworkManager())) {
            // Straight to the transfer's own ordered writer, not the main thread: the slices are file
            // writes, and this connection delivers them in order.
            ModelTransferClient.onChunk(message.modelId, message.offset, message.total, message.data);
        }
        context.setPacketHandled(true);
    }
}
