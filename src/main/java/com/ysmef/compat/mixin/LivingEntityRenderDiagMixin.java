package com.ysmef.compat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY DIAGNOSTIC: observes the poseStack exactly where the Forge
 * RenderLivingEvent.Pre is fired (LivingEntityRenderer.render head, before any
 * entity transform). The event carries this exact poseStack, so if this shows
 * non-zero translation while the EF handler diagnostic shows zero, the event's
 * poseStack is being modified between construction and handler dispatch.
 *
 * Remove once the maid GPU-stretch issue is resolved.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRenderDiagMixin {

    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0)
    private void ysmef$diagLivingRenderHead(LivingEntity entity, float yaw, float partialTicks,
                                            PoseStack poseStack, MultiBufferSource buffer, int light, CallbackInfo ci) {
        if (!com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
        String key = entity.getClass().getName();
        if (!LOGGED.add(key)) {
            return;
        }
        org.joml.Matrix4f pose = poseStack.last().pose();
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] livingRenderer.render head: entity={} yaw={} partialTicks={} "
                        + "poseStackId={} poseStack=(m00={},m11={},m22={},m30={},m31={},m32={}) "
                        + "stack6={} stack7={} stack8={} stack9={}",
                entity.getClass().getName(), yaw, partialTicks,
                Integer.toHexString(System.identityHashCode(poseStack)),
                pose.m00(), pose.m11(), pose.m22(), pose.m30(), pose.m31(), pose.m32(),
                stackFrame(6), stackFrame(7), stackFrame(8), stackFrame(9));
    }

    private static String stackFrame(int depth) {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        if (trace == null || depth >= trace.length) {
            return "?";
        }
        StackTraceElement el = trace[depth];
        String simple = el.getClassName();
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return simple + "." + el.getMethodName() + ":" + el.getLineNumber();
    }
}
