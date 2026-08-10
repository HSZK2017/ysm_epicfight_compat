package com.ysmef.compat.eftlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.renderer.YSMMeshSelector;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between Touhou Little Maid entities and the YSM mesh library.
 *
 * A maid may use a YSM model (TLM's YSM integration; the selection lives in
 * synced entity data, readable directly on the client) -> converted YSM base
 * mesh. TLM model-pack GEO models are handled by EpicFight_TouhouLittleMaid
 * itself, so no GEO support is needed here.
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
     * Select the converted base mesh for the maid's current YSM model, or null
     * when she has none, leaving EpicFight_TouhouLittleMaid's own mesh
     * selection in place.
     */
    public static AssetAccessor<HumanoidMesh> selectMaidMesh(EntityMaid maid) {
        if (maid == null || !maid.isYsmModel()) {
            return null;
        }
        logHookActiveOnce(maid.getYsmModelId(), maid.getYsmModelId());
        String modelId = maid.getYsmModelId();
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }
        String texture = maid.getYsmModelTexture();
        return YSMMeshSelector.selectMeshForModel(maid, modelId, texture, maid.getName().getString());
    }

    /**
     * One-time proof that the maid renderer hook actually fires in-game (if this
     * line never appears in the log, the mixin did not apply).
     */
    private static void logHookActiveOnce(String modelId, String displayName) {
        if (LOGGED.add("hook-active")) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: maid mesh hook active (first maid model='{}', maid='{}')",
                    modelId, displayName);
        }
    }
}
