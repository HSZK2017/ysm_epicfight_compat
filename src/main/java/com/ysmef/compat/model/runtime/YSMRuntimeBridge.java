package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
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

    private static final ThreadLocal<LivingEntity> CURRENT_ENTITY = new ThreadLocal<>();

    private YSMRuntimeBridge() {}

    public static void setCurrentEntity(LivingEntity entity) {
        CURRENT_ENTITY.set(entity);
    }

    public static void clearCurrentEntity() {
        CURRENT_ENTITY.remove();
    }

    /** The entity currently being drawn (null outside the mesh draw call). */
    public static LivingEntity getCurrentEntity() {
        return CURRENT_ENTITY.get();
    }

    /**
     * Evaluate the YSM scripts for the entity currently being rendered and apply
     * the results (per-part hidden flags and transforms) to the mesh. No-op when
     * there is no current entity or no runtime data for the mesh's model.
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
        LivingEntity entity = CURRENT_ENTITY.get();
        if (entity == null) {
            return;
        }
        YSMRuntimeModel model = YSMRuntimeModel.get(modelId);
        if (YSMBattleMode.isBattleMode(entity)) {
            if (model != null) {
                if (entity instanceof Player player) {
                    // Honor persistent v.roaming.* toggles from the wheel
                    // (accessory switches like the gun/key animations) so the
                    // converted mesh shows/hides the same accessory parts as
                    // YSM's own renderer would.
                    model.applyEntityVisibility(mesh, player);
                } else {
                    model.applyDefaultVisibility(mesh);
                }
                // Cloth for the mesh that is actually on screen: this is the only path that
                // draws the converted mesh, so it is the only path where swinging its hair and
                // cloth can be seen. Written after the visibility pass because it writes the
                // same per-part transforms.
                YsmMeshCloth.apply(mesh, model, armature, poses);
            } else {
                unhideAllBoneParts(mesh);
            }
            return;
        }
        if (model == null) {
            return;
        }
        float partialTick = Minecraft.getInstance().getFrameTime();
        model.animatorFor(entity).apply(mesh, entity, poses, partialTick);
    }

    /**
     * The entity whose mesh is being drawn right now, or null outside a draw.
     *
     * <p>Exposed for the cloth, which has to express the skeleton's joints in the same frame
     * as the mesh's own vertices: {@code poses} carries where the joints are in the world,
     * and the vertices are in the model's bind space, so the entity's position is the
     * translation between them.
     */
    public static LivingEntity currentEntity() {
        return CURRENT_ENTITY.get();
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
