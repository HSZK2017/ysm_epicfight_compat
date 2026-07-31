package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.Map;

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
     *
     * In Epic Fight battle mode no script animation runs: the mesh is drawn with
     * the model's default form only (animation-driven variant geometry hidden,
     * no transforms), so Epic Fight's combat animations are the sole deformation.
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
        if (YSMBattleMode.isBattleMode(player)) {
            if (model != null) {
                model.applyDefaultVisibility(mesh);
            } else {
                unhideAllBoneParts(mesh);
            }
            return;
        }
        if (model == null) {
            return;
        }
        float partialTick = Minecraft.getInstance().getFrameTime();
        model.animatorFor(player).apply(mesh, player, poses, partialTick);
    }

    /**
     * Restore full visibility of every per-bone part, undoing any hidden flags
     * the script evaluator set in previous frames.
     */
    private static void unhideAllBoneParts(YSMMesh mesh) {
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            if (entry.getKey().startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                entry.getValue().setHidden(false);
            }
        }
    }
}
