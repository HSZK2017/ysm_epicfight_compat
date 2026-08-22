package com.ysmef.compat.mixin;

import com.ysmef.compat.model.runtime.YsmBindArmature;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.api.utils.math.Vec4f;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Anchors Epic Fight's held weapons in the converted YSM model's actual hand
 * (the geometrically located fist, covering any bone naming - English or
 * Chinese hand bones), instead of the Tool joint's elbow pivot which leaves
 * the weapon floating up the arm.
 *
 * This modifies only the WEAPON's correction transform (weapon coordinates),
 * never the model's armature/Tool pivot (so the bone animations stay intact).
 * The correction anchors the weapon at the model's geometrically computed
 * fist position (see YsmBindArmature#fistPosition): the fist (bind world) is
 * expressed in the Tool joint's local frame via the joint's inverse bind
 * matrix (toOrigin), so the weapon lands in the hand in bind pose and follows
 * the arm rigidly during combat animations.
 */
@Mixin(value = RenderItemBase.class, remap = false)
public abstract class RenderItemBaseMixin {

    @Inject(method = "getCorrectionMatrix", at = @At("HEAD"), cancellable = true, require = 0)
    private void ysmef$fistCorrection(LivingEntityPatch<?> entitypatch, InteractionHand hand,
                                      OpenMatrix4f[] poses, CallbackInfoReturnable<OpenMatrix4f> cir) {
        if (!(entitypatch.getOriginal() instanceof Player player)) {
            return;
        }
        if (!com.ysmef.compat.renderer.YSMBattleMode.isBattleMode(player)) {
            return;
        }
        com.ysmef.compat.renderer.YSMModelAccess.YSMModelRef modelRef =
                com.ysmef.compat.renderer.YSMModelAccess.getCurrentModel(player);
        if (modelRef == null) {
            return;
        }
        Vector3f fist = YsmBindArmature.fistPosition(modelRef.modelId(), hand == InteractionHand.OFF_HAND);
        if (fist == null) {
            return;
        }
        Joint parentJoint = entitypatch.getParentJointOfHand(hand);
        if (parentJoint == null) {
            return;
        }
        // The Tool joint's inverse bind matrix maps the fist's bind-world
        // position into the joint's local frame; anchoring the weapon there
        // puts it in the hand in bind pose and keeps it there while the arm
        // swings (the offset rotates with the joint).
        OpenMatrix4f toOrigin = parentJoint.getToOrigin();
        Vec4f local = new Vec4f(fist.x, fist.y, fist.z, 1.0f).transform(toOrigin);
        OpenMatrix4f correction = new OpenMatrix4f()
                .translate(local.x, local.y, local.z)
                .rotateDeg(-90.0f, Vec3f.X_AXIS);
        correction.mulFront(poses[parentJoint.getId()]);
        cir.setReturnValue(correction);
    }
}
