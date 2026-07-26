package com.ysmef.compat.renderer;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.model.YSMMeshLibrary;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.mesh.HumanoidMesh;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared mesh-selection logic used by both the patched renderer override and the
 * mixin that hijacks Epic Fight's own PPlayerRenderer#getMeshProvider.
 *
 * Returns a mesh accessor for the player's current YSM model (with the texture
 * override applied), or null to let Epic Fight use its default biped mesh.
 */
public final class YSMMeshSelector {

    private static final Set<String> LOGGED_MESH_USE = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_MESH_MISSING = ConcurrentHashMap.newKeySet();

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
            return null;
        }

        Meshes.MeshAccessor<YSMMesh> accessor = YSMMeshLibrary.findMesh(modelRef.modelId());
        if (accessor == null) {
            logMeshMissingOnce(player, modelRef);
            return null;
        }

        try {
            YSMMesh mesh = accessor.get();
            ResourceLocation texture = YSMMeshLibrary.findTexture(modelRef.modelId(), modelRef.textureName());
            if (texture != null) {
                YSMMeshLibrary.ensureTextureUploaded(texture);
                mesh.setTextureOverride(texture);
            }
            logMeshUsedOnce(player, modelRef, texture);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: failed to load generated mesh for '{}', falling back to Epic Fight biped: {}",
                    modelRef.modelId(), t.toString());
            return null;
        }

        @SuppressWarnings("unchecked")
        AssetAccessor<HumanoidMesh> result = (AssetAccessor<HumanoidMesh>) (AssetAccessor<?>) accessor;
        return result;
    }

    private static void logMeshUsedOnce(AbstractClientPlayer player, YSMModelAccess.YSMModelRef modelRef, ResourceLocation texture) {
        String key = player.getGameProfile().getName() + "|" + modelRef.modelId() + "|" + modelRef.textureName();
        if (LOGGED_MESH_USE.add(key)) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: rendering player '{}' with converted YSM base mesh (model='{}', texture='{}' -> {})",
                    player.getGameProfile().getName(), modelRef.modelId(), modelRef.textureName(), texture);
        }
    }

    private static void logMeshMissingOnce(AbstractClientPlayer player, YSMModelAccess.YSMModelRef modelRef) {
        String key = player.getGameProfile().getName() + "|" + modelRef.modelId();
        if (LOGGED_MESH_MISSING.add(key)) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: no converted base mesh for model '{}' (player '{}', texture '{}'). Falling back to Epic Fight biped. Available: {}",
                    modelRef.modelId(), player.getGameProfile().getName(), modelRef.textureName(),
                    YSMMeshLibrary.availableModelIds());
        }
    }
}
