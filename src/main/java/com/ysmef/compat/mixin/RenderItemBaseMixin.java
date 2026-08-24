package com.ysmef.compat.mixin;

import com.ysmef.compat.model.runtime.YsmBindArmature;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.model.armature.HumanoidArmature;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Anchors Epic Fight's held weapons in the converted YSM model's actual hand,
 * instead of leaving them floating at the entity's biped armature position.
 *
 * Epic Fight's PatchedItemInHandLayer renders weapons with the entity's own
 * (biped-proportioned) armature poses: the weapon transform is a fixed grip
 * correction times {@code poses[parentJointId]} (the Tool_R/Tool_L joint's
 * world transform, see RenderItemBase#getCorrectionMatrix). The converted YSM
 * mesh, however, renders with the rebound model armature (YsmBindArmature). For
 * a model whose proportions differ from the biped's, the weapon ends up at the
 * biped hand position - floating away from the model's hand.
 *
 * This substitutes the Tool/Hand joint entries of the pose array handed to the
 * weapon correction with the rebound armature's current poses (the model's
 * hand), so the weapon lands in the model's hand and follows the arm through
 * combat animations. The rebound armature is posed by YSMMesh#draw earlier in
 * the same render pass (mesh draw precedes the item layer), and it shares the
 * biped's joint ids, so the substitution is index-compatible. Only the pose
 * array handed to the weapon correction is touched - the entity's own armature
 * and the mesh skinning are unaffected.
 *
 * The Epic Fight grip correction (translate(0,0,-0.13) + rotate -90 deg X) is
 * applied by Epic Fight on top, unchanged.
 */
@Mixin(value = RenderItemBase.class, remap = false)
public abstract class RenderItemBaseMixin {

    /** Tool_R, Tool_L, Hand_R, Hand_L joint ids (fixed biped layout). */
    private static final int[] WEAPON_JOINTS = {13, 18, 12, 17};

    @ModifyVariable(method = "getCorrectionMatrix", at = @At("HEAD"), argsOnly = true, require = 0)
    private OpenMatrix4f[] ysmef$useReboundWeaponPoses(OpenMatrix4f[] poses, LivingEntityPatch<?> entitypatch,
                                                       InteractionHand hand) {
        if (!(entitypatch.getOriginal() instanceof Player player)) {
            return poses;
        }
        boolean battle = com.ysmef.compat.renderer.YSMBattleMode.isBattleMode(player);
        if (!battle) {
            return poses;
        }
        com.ysmef.compat.renderer.YSMModelAccess.YSMModelRef modelRef =
                com.ysmef.compat.renderer.YSMModelAccess.getCurrentModel(player);
        if (modelRef == null) {
            return poses;
        }
        HumanoidArmature bind = YsmBindArmature.getBuiltArmature(modelRef.modelId());
        // Diagnostic (once per model+hand): why the weapon is/isn't re-anchored.
        boolean diagKey = WEAPON_DIAG_LOGGED.add(modelRef.modelId() + "|" + hand);
        if (bind == null) {
            if (diagKey) {
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [weapon] model='{}' hand={} NOT re-anchored: no built rebound armature",
                        modelRef.modelId(), hand);
            }
            return poses;
        }
        OpenMatrix4f[] bindPoses = bind.getPoseMatrices();
        if (bindPoses == null || bindPoses.length == 0) {
            if (diagKey) {
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [weapon] model='{}' hand={} NOT re-anchored: empty rebound poses",
                        modelRef.modelId(), hand);
            }
            return poses;
        }
        OpenMatrix4f[] substituted = null;
        StringBuilder nullJoints = new StringBuilder();
        for (int joint : WEAPON_JOINTS) {
            if (joint >= poses.length || joint >= bindPoses.length || bindPoses[joint] == null) {
                nullJoints.append(joint).append(' ');
                continue;
            }
            if (substituted == null) {
                substituted = new OpenMatrix4f[poses.length];
                System.arraycopy(poses, 0, substituted, 0, poses.length);
            }
            substituted[joint] = bindPoses[joint];
        }
        if (substituted == null) {
            if (diagKey) {
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [weapon] model='{}' hand={} NOT re-anchored: no weapon joint substituted (null joints: {}; poses.len={}, bindPoses.len={})",
                        modelRef.modelId(), hand, nullJoints, poses.length, bindPoses.length);
            }
            return poses;
        }
        if (WEAPON_REBIND_LOGGED.add(modelRef.modelId())) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [weapon] anchoring held weapons to the model's hand for model='{}'",
                    modelRef.modelId());
        }
        return substituted;
    }

    private static final java.util.Set<String> WEAPON_REBIND_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> WEAPON_DIAG_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Diagnostic (once per model+hand): the weapon's final entity-local
     * position vs the rebound hand/tool joint positions. If the weapon position
     * does not sit on the model's hand, these numbers show the mismatch.
     */
    @Inject(method = "getCorrectionMatrix", at = @At("RETURN"), require = 0)
    private void ysmef$logWeaponPosition(LivingEntityPatch<?> entitypatch, InteractionHand hand,
                                         OpenMatrix4f[] poses, CallbackInfoReturnable<OpenMatrix4f> cir) {
        if (!(entitypatch.getOriginal() instanceof Player player)) {
            return;
        }
        if (!com.ysmef.compat.renderer.YSMBattleMode.isBattleMode(player)) {
            return;
        }
        com.ysmef.compat.renderer.YSMModelAccess.YSMModelRef modelRef =
                com.ysmef.compat.renderer.YSMModelAccess.getCurrentModel(player);
        if (modelRef == null || !WEAPON_POS_LOGGED.add(modelRef.modelId() + "|" + hand)) {
            return;
        }
        OpenMatrix4f result = cir.getReturnValue();
        HumanoidArmature bind = YsmBindArmature.getBuiltArmature(modelRef.modelId());
        StringBuilder sb = new StringBuilder();
        if (bind != null) {
            OpenMatrix4f[] bp = bind.getPoseMatrices();
            for (String jn : new String[]{"Tool_R", "Hand_R", "Tool_L", "Hand_L"}) {
                yesman.epicfight.api.animation.Joint j = bind.searchJointByName(jn);
                if (j != null && j.getId() < bp.length && bp[j.getId()] != null) {
                    sb.append(" ").append(jn).append(String.format("=(%.3f,%.3f,%.3f)",
                            bp[j.getId()].m30, bp[j.getId()].m31, bp[j.getId()].m32));
                }
            }
        }
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [weapon] model='{}' hand={} weaponPos=({},{},{}) bindJoints:{}",
                modelRef.modelId(), hand,
                String.format("%.3f", result.m30), String.format("%.3f", result.m31), String.format("%.3f", result.m32),
                sb.toString());
    }

    private static final java.util.Set<String> WEAPON_POS_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
}
