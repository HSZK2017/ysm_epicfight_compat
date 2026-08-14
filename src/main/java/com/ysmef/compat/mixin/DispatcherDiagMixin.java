package com.ysmef.compat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY DIAGNOSTIC: observes EntityRenderDispatcher.render inputs.
 * Prints the camera-relative translation (x/y/z) the vanilla pipeline passes
 * in and the poseStack translation BEFORE the dispatcher's translate (the
 * LevelRenderer poseStack). If the RenderLivingEvent's poseStack shows zero
 * translation but this mixin shows non-zero x/y/z, the translation is lost
 * between dispatcher.render and the event.
 *
 * NOTE: this mixin must use the default remap=true (mojmap "render" maps to
 * srg m_114384_ at runtime) - remap=false silently fails to match.
 *
 * Remove once the maid GPU-stretch issue is resolved.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class DispatcherDiagMixin {

    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0)
    private void ysmef$diagDispatcherHead(Entity entity, double x, double y, double z, float yaw, float partialTicks,
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
                "YSM-EF Compat: [diag] dispatcher.render head: entity={} x={} y={} z={} "
                        + "poseStackPre=(m00={},m11={},m22={},m03={},m13={},m23={})",
                entity.getClass().getName(),
                String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z),
                pose.m00(), pose.m11(), pose.m22(), pose.m03(), pose.m13(), pose.m23());
    }

    /**
     * Fires right before dispatcher.render invokes renderer.render - i.e. AFTER
     * the dispatcher's poseStack.translate(x+offsetX, y+offsetY, z+offsetZ).
     * This is the exact poseStack the renderer (and the RenderLivingEvent)
     * receives, so a zero translation here vs non-zero at the renderer head
     * would prove a mixin replaces the poseStack argument on the call.
     */
    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
            require = 0)
    private void ysmef$diagDispatcherBeforeRender(Entity entity, double x, double y, double z, float yaw, float partialTicks,
                                                  PoseStack poseStack, MultiBufferSource buffer, int light, CallbackInfo ci) {
        if (!com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
        String key = entity.getClass().getName();
        if (!BEFORE_LOGGED.add(key)) {
            return;
        }
        org.joml.Matrix4f pose = poseStack.last().pose();
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] dispatcher.render before-renderer-call: entity={} x={} y={} z={} "
                        + "poseStackPost=(m00={},m11={},m22={},m03={},m13={},m23={})",
                entity.getClass().getName(),
                String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z),
                pose.m00(), pose.m11(), pose.m22(), pose.m03(), pose.m13(), pose.m23());
    }

    private static final java.util.Set<String> BEFORE_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Fires immediately AFTER each of the dispatcher's poseStack.translate(...)
     * calls (the entity translate and the -offset translate). Shows whether the
     * translate actually changed the stack (translation must be non-zero after
     * the first one) and the identity of the stack object.
     */
    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void ysmef$diagDispatcherAfterTranslate(Entity entity, double x, double y, double z, float yaw, float partialTicks,
                                                    PoseStack poseStack, MultiBufferSource buffer, int light, CallbackInfo ci) {
        if (!com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
        String key = entity.getClass().getName();
        if (!AFTER_TRANSLATE_LOGGED.add(key)) {
            return;
        }
        org.joml.Matrix4f pose = poseStack.last().pose();
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] dispatcher.render after-translate: entity={} x={} y={} z={} "
                        + "poseStackId={} poseStack=(m00={},m11={},m22={},m03={},m13={},m23={})",
                entity.getClass().getName(),
                String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z),
                Integer.toHexString(System.identityHashCode(poseStack)),
                pose.m00(), pose.m11(), pose.m22(), pose.m03(), pose.m13(), pose.m23());
    }

    private static final java.util.Set<String> AFTER_TRANSLATE_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
}
