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
 *
 * One deliberate exception: YSM's built-in vanilla player models
 * ("misc/2_steve" / "misc/1_alex", see {@link YSMModelAccess#isVanillaPlayerModelId})
 * always yield null, so the plain Epic Fight biped is used and no YSM mesh is
 * converted or drawn for them.
 */
public final class YSMMeshSelector {

    private static final Map<java.util.UUID, String[]> LOGGED_MESH_USE = new ConcurrentHashMap<>();
    private static final Map<java.util.UUID, String> LOGGED_MESH_MISSING = new ConcurrentHashMap<>();
    private static final Map<java.util.UUID, String> LOGGED_VANILLA_FALLBACK = new ConcurrentHashMap<>();

    private YSMMeshSelector() {}

    /**
     * Select the converted base mesh for the player's current YSM model.
     *
     * @return the mesh accessor, or null if no converted mesh exists for the model
     *         (or the model is YSM's built-in vanilla player model)
     */
    public static AssetAccessor<HumanoidMesh> selectMesh(AbstractClientPlayer player) {
        if (player == null) {
            return null;
        }
        // Another mod owns this player's look right now (a transformation, a
        // possession, a costume it draws itself): abstain, so this mod does not pose
        // and layer a model that mod has replaced. Answered once a tick and cached
        // there - see LookOwners for why the two questions have different lifetimes.
        if (com.ysmef.compat.compat.LookOwners.ownsLook(player)) {
            logLookOwnerOnce(player);
            return null;
        }
        YSMModelAccess.YSMModelRef modelRef = YSMModelAccess.getCurrentModel(player);
        if (modelRef == null) {
            logNoModelDiagOnce(player);
            return null;
        }
        if (YSMModelAccess.isVanillaPlayerModelId(modelRef.modelId())) {
            logVanillaFallbackOnce(player, modelRef.modelId());
            return null;
        }
        return selectMeshForModel(player, modelRef.modelId(), modelRef.textureName(),
                player.getGameProfile().getName());
    }

    /** Once per player: another mod owns the look, so this mod abstains. */
    private static final Map<java.util.UUID, String> LOGGED_LOOK_OWNER = new ConcurrentHashMap<>();

    private static void logLookOwnerOnce(AbstractClientPlayer player) {
        String reason = com.ysmef.compat.compat.LookOwners.reason(player);
        String prev = LOGGED_LOOK_OWNER.put(player.getUUID(), String.valueOf(reason));
        if (reason != null && !reason.equals(prev)) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: player '{}' is drawn by another mod ({}); this mod abstains until the look is given back",
                    player.getGameProfile().getName(), reason);
        }
    }

    /**
     * YSM's built-in "原版史蒂夫模型" (Steve) and "原版艾利克斯模型" (Alex) are the
     * vanilla player rig skinned with the player's own Mojang skin, so there is
     * nothing to convert: returning null hands the player to Epic Fight's default
     * biped, which renders identically and keeps vanilla armor/head/elytra layers
     * working. See YSMModelAccess#isVanillaPlayerModelId.
     */
    private static void logVanillaFallbackOnce(AbstractClientPlayer player, String modelId) {
        String prev = LOGGED_VANILLA_FALLBACK.put(player.getUUID(), modelId);
        if (modelId.equals(prev)) {
            return;
        }
        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: '{}' uses YSM's built-in vanilla player model '{}' - using the Epic Fight default biped instead of a converted YSM mesh",
                player.getGameProfile().getName(), modelId);
    }

    /** Once per player: the selection cache resolved no YSM model (diagnostics). */
    private static final Map<java.util.UUID, String> DIAG_NO_MODEL = new ConcurrentHashMap<>();

    private static void logNoModelDiagOnce(AbstractClientPlayer player) {
        if (!com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
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
        // Do NOT enumerate all locally available models here: this runs on the
        // render thread and the old availableModelIds() call walked every YSM
        // model folder recursively, hitching the frame on the first missing
        // model of each player.
        YSMEpicFightCompat.LOGGER.warn(
                "YSM-EF Compat: no converted base mesh for model '{}' (entity '{}', texture '{}'). Falling back to Epic Fight default mesh.",
                modelId, displayName, textureName);
    }

    /** Forget per-player diagnostic state (world leave). */
    public static void clear() {
        LOGGED_MESH_USE.clear();
        LOGGED_MESH_MISSING.clear();
        LOGGED_VANILLA_FALLBACK.clear();
        LOGGED_LOOK_OWNER.clear();
        DIAG_NO_MODEL.clear();
    }
}
