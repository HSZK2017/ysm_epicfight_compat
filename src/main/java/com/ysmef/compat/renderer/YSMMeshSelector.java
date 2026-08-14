package com.ysmef.compat.renderer;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.model.runtime.YSMRuntimeBridge;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.mesh.HumanoidMesh;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared mesh-selection logic used by the patched renderer override, the mixin
 * that hijacks Epic Fight's own PPlayerRenderer#getMeshProvider, and the
 * EpicFight_TouhouLittleMaid maid renderer hook.
 *
 * Returns a mesh accessor for the entity's current YSM model (with the texture
 * override applied), or null to let Epic Fight use its default mesh.
 */
public final class YSMMeshSelector {

    private static final Map<java.util.UUID, String[]> LOGGED_MESH_USE = new ConcurrentHashMap<>();
    private static final Map<java.util.UUID, String> LOGGED_MESH_MISSING = new ConcurrentHashMap<>();

    private YSMMeshSelector() {}

    /**
     * Select the converted base mesh for the player's current YSM model.
     *
     * @return the mesh accessor, or null if no converted mesh exists for the model
     */
    public static AssetAccessor<HumanoidMesh> selectMesh(AbstractClientPlayer player) {
        if (player == null) {
            return null;
        }
        YSMModelAccess.YSMModelRef modelRef = YSMModelAccess.getCurrentModel(player);
        if (modelRef == null) {
            logNoModelDiagOnce(player);
            return null;
        }
        return selectMeshForModel(player, modelRef.modelId(), modelRef.textureName(),
                player.getGameProfile().getName());
    }

    /** Once per player: the selection cache resolved no YSM model (diagnostics). */
    private static final Map<java.util.UUID, String> DIAG_NO_MODEL = new ConcurrentHashMap<>();

    private static void logNoModelDiagOnce(AbstractClientPlayer player) {
        String prev = DIAG_NO_MODEL.put(player.getUUID(), "noModel");
        if (prev == null) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [diag] selectMesh: no YSM model ref for '{}' (battleMode={}, level={})",
                    player.getGameProfile().getName(), YSMBattleMode.isBattleMode(player), player.level());
        }
    }

    /**
     * Select the converted base mesh for an explicitly given YSM model + texture
     * (used for maids, whose current YSM model id is read from synced entity data).
     *
     * @return the mesh accessor, or null if no converted mesh exists for the model
     */
    public static AssetAccessor<HumanoidMesh> selectMeshForModel(LivingEntity entity, String modelId,
                                                                 String textureName, String displayName) {
        if (entity == null || modelId == null || modelId.isEmpty()) {
            return null;
        }
        Meshes.MeshAccessor<YSMMesh> accessor = YSMMeshLibrary.findMesh(modelId);
        if (accessor == null) {
            logMeshMissingOnce(entity, modelId, textureName, displayName);
            return null;
        }
        ResourceLocation texture = YSMMeshLibrary.findTexture(modelId, textureName);
        return selectResolvedMesh(entity, accessor, modelId, texture, textureName, displayName);
    }

    /**
     * Shared core: apply the runtime model id, current entity and texture
     * override to a resolved mesh accessor and return it for the Epic Fight
     * render pipeline.
     */
    public static AssetAccessor<HumanoidMesh> selectResolvedMesh(LivingEntity entity,
                                                                 Meshes.MeshAccessor<YSMMesh> accessor,
                                                                 String modelId, ResourceLocation texture,
                                                                 String textureName, String displayName) {
        if (entity == null || accessor == null) {
            return null;
        }
        try {
            YSMMesh mesh = accessor.get();
            YSMMeshLibrary.markMeshLoaded(modelId);
            mesh.setRuntimeModelId(modelId);
            YSMRuntimeBridge.setCurrentEntity(entity);
            if (texture != null) {
                YSMMeshLibrary.ensureTextureUploaded(texture);
                mesh.setTextureOverride(texture);
            }
            logMeshUsedOnce(entity, modelId, textureName, texture, displayName);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: failed to load generated mesh for '{}', falling back to Epic Fight default mesh",
                    modelId, t);
            return null;
        }

        @SuppressWarnings("unchecked")
        AssetAccessor<HumanoidMesh> result = (AssetAccessor<HumanoidMesh>) (AssetAccessor<?>) accessor;
        return result;
    }

    private static void logMeshUsedOnce(LivingEntity entity, String modelId, String textureName,
                                        ResourceLocation texture, String displayName) {
        // Runs every frame per drawn player: compare against the cached pair
        // instead of building a "uuid|modelId|texture" string every frame.
        String[] prev = LOGGED_MESH_USE.get(entity.getUUID());
        if (prev != null && prev[0].equals(modelId) && java.util.Objects.equals(prev[1], textureName)) {
            return;
        }
        LOGGED_MESH_USE.put(entity.getUUID(), new String[]{modelId, textureName});
        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: rendering '{}' with converted YSM base mesh (model='{}', texture='{}' -> {})",
                displayName, modelId, textureName, texture);
    }

    private static void logMeshMissingOnce(LivingEntity entity, String modelId, String textureName, String displayName) {
        String prev = LOGGED_MESH_MISSING.put(entity.getUUID(), modelId);
        if (modelId.equals(prev)) {
            return;
        }
        YSMEpicFightCompat.LOGGER.warn(
                "YSM-EF Compat: no converted base mesh for model '{}' (entity '{}', texture '{}'). Falling back to Epic Fight default mesh. Available: {}",
                modelId, displayName, textureName, YSMMeshLibrary.availableModelIds());
    }
}
