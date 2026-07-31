package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

/**
 * Per-frame bridge between the Epic Fight render pipeline and the YSM script
 * evaluator. The patched renderer records the player currently being drawn
 * (single render thread, sequential per entity); when a YSMMesh is about to draw,
 * its model's scripts are evaluated for that player and the resulting per-part
 * hidden flags / bind-space delta transforms are pushed into the mesh.
 */
public final class YSMRuntimeBridge {

    private static final ThreadLocal<Player> CURRENT_PLAYER = new ThreadLocal<>();

    private YSMRuntimeBridge() {}

    public static void setCurrentPlayer(Player player) {
        CURRENT_PLAYER.set(player);
    }

    public static void clearCurrentPlayer() {
        CURRENT_PLAYER.remove();
    }

    /**
     * Evaluate the YSM scripts for the player currently being rendered and apply
     * the results (per-part hidden flags and transforms) to the mesh. No-op when
     * there is no current player or no runtime data for the mesh's model.
     */
    public static void apply(YSMMesh mesh, Armature armature, OpenMatrix4f[] poses) {
        mesh.clearRuntimeTransforms();
        String modelId = mesh.getRuntimeModelId();
        if (modelId == null) {
            return;
        }
        Player player = CURRENT_PLAYER.get();
        if (player == null) {
            return;
        }
        YSMRuntimeModel model = YSMRuntimeModel.get(modelId);
        if (model == null) {
            return;
        }
        float partialTick = Minecraft.getInstance().getFrameTime();
        model.animatorFor(player).apply(mesh, player, poses, partialTick);
    }
}
