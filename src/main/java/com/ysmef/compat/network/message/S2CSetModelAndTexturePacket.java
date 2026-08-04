package com.ysmef.compat.network.message;

import com.ysmef.compat.network.ModelSyncClient;
import com.ysmef.compat.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server -> client broadcast of one player's current YSM model selection,
 * mirroring OpenYSM's S2CSetModelAndTexturePacket (message id 4): entityId,
 * modelId, textureId, disabled.
 *
 * Unlike YSM, which applies the payload through an entity-join callback keyed
 * by entityId, this packet additionally carries the player UUID so the client
 * can register the selection directly in ModelSyncClient (keyed by UUID, which
 * is stable across respawns and dimension changes). The entityId is kept for
 * protocol fidelity and diagnostics.
 */
public class S2CSetModelAndTexturePacket {

    private final int entityId;

    private final UUID uuid;

    private final String modelId;

    private final String textureId;

    private final boolean disabled;

    public S2CSetModelAndTexturePacket(int entityId, UUID uuid, String modelId, String textureId, boolean disabled) {
        this.entityId = entityId;
        this.uuid = uuid;
        this.modelId = modelId;
        this.textureId = textureId;
        this.disabled = disabled;
    }

    public static void encode(S2CSetModelAndTexturePacket message, FriendlyByteBuf buf) {
        buf.writeVarInt(message.entityId);
        buf.writeUUID(message.uuid);
        buf.writeUtf(message.modelId);
        buf.writeUtf(message.textureId);
        buf.writeBoolean(message.disabled);
    }

    public static S2CSetModelAndTexturePacket decode(FriendlyByteBuf buf) {
        return new S2CSetModelAndTexturePacket(buf.readVarInt(), buf.readUUID(),
                buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }

    public static void handle(S2CSetModelAndTexturePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()
                && NetworkHandler.isConnectionValid(context.getNetworkManager())) {
            context.enqueueWork(() -> ModelSyncClient.applySyncedModel(
                    message.uuid, message.modelId, message.textureId, message.disabled));
        }
        context.setPacketHandled(true);
    }
}
