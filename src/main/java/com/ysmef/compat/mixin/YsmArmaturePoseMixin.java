package com.ysmef.compat.mixin;

import com.ysmef.compat.model.runtime.YsmBindArmature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.model.Armature;

/**
 * Captures the animation pose Epic Fight applies to an entity's armature every
 * frame (PatchedEntityRenderer#setArmaturePose -> Armature#setPose), keyed by
 * the armature instance. YSMMesh#draw later re-evaluates that pose on the
 * model's YSM-bind armature (see YsmBindArmature) so combat rotations pivot at
 * the YSM model's own joints instead of the Steve bind pose.
 */
@Mixin(value = Armature.class, remap = false)
public abstract class YsmArmaturePoseMixin {

    @Inject(method = "setPose(Lyesman/epicfight/api/animation/Pose;)V", at = @At("TAIL"), require = 0)
    private void ysmef$capturePoseForBindRetarget(Pose pose, CallbackInfo ci) {
        YsmBindArmature.onArmatureSetPose((Armature) (Object) this, pose);
    }
}
