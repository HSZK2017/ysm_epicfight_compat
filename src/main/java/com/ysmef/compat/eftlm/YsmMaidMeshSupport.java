package com.ysmef.compat.eftlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.TlmModelLibrary;
import com.ysmef.compat.renderer.YSMMeshSelector;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between Touhou Little Maid entities and the YSM/TLM mesh libraries.
 *
 * A maid's model comes from one of two sources, both covered:
 * - a YSM model (TLM's YSM integration; the selection lives in synced entity
 *   data, readable directly on the client) -> converted YSM base mesh
 * - a TLM model pack GEO model (EntityMaid#getModelId, also synced) -> converted
 *   TLM base mesh (see TlmModelLibrary)
 *
 * When such a maid is rendered through Epic Fight's pipeline
 * (EpicFight_TouhouLittleMaid's patched renderer), the converted mesh for her
 * model is substituted.
 *
 * This class is only referenced when EpicFight_TouhouLittleMaid is installed
 * (the maid renderer mixin lives in the optional eftlm mixin config).
 */
public final class YsmMaidMeshSupport {

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private YsmMaidMeshSupport() {}

    /**
     * Select the converted base mesh for the maid's current model (YSM or TLM
     * GEO), or null when her model has no converted mesh, leaving
     * EpicFight_TouhouLittleMaid's own mesh selection in place.
     */
    public static AssetAccessor<HumanoidMesh> selectMaidMesh(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        if (maid.isYsmModel()) {
            logHookActiveOnce(maid.getYsmModelId(), true);
            String modelId = maid.getYsmModelId();
            if (modelId == null || modelId.isEmpty()) {
                return null;
            }
            String texture = maid.getYsmModelTexture();
            return YSMMeshSelector.selectMeshForModel(maid, modelId, texture, maid.getName().getString());
        }
        String tlmModelId = maid.getModelId();
        if (tlmModelId == null || tlmModelId.isEmpty()) {
            return null;
        }
        logHookActiveOnce(tlmModelId, false);
        TlmModelLibrary.ensureGenerated();
        TlmModelLibrary.TlmMeshEntry entry = TlmModelLibrary.find(tlmModelId);
        if (entry == null) {
            logMeshMissingOnce(tlmModelId, maid);
            return null;
        }
        return YSMMeshSelector.selectResolvedMesh(maid, entry.accessor(), tlmModelId,
                entry.texture(), "", maid.getName().getString());
    }

    /**
     * One-time proof that the maid renderer hook actually fires in-game (if this
     * line never appears in the log, the mixin did not apply).
     */
    private static void logHookActiveOnce(String modelId, boolean ysmModel) {
        if (LOGGED.add("hook-active")) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: maid mesh hook active (first maid model='{}', ysm={})",
                    modelId, ysmModel);
        }
    }

    private static void logMeshMissingOnce(String modelId, EntityMaid maid) {
        if (LOGGED.add("missing|" + modelId)) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: no converted mesh for maid model '{}' (maid '{}'), falling back to EFTLM default mesh",
                    modelId, maid.getName().getString());
        }
    }
}
