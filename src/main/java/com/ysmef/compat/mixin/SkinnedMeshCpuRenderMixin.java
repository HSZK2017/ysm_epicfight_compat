package com.ysmef.compat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ysmef.compat.cpu.YsmCpuRenderPath;
import com.ysmef.compat.model.YSMMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

/**
 * Redirects Epic Fight's CPU skinning fallback for converted YSM meshes to
 * this mod's CPU skinning render path.
 *
 * Epic Fight's {@code SkinnedMesh#draw} uses the compute-shader path only
 * while the client config use_compute_shader is enabled; otherwise (and on
 * GPUs without compute-shader support, where the compute setup cannot exist
 * at all) it falls back to {@code drawPosed}, which renders the converted YSM
 * meshes with missing faces (verified empirically: flipping
 * use_compute_shader reproduces/removes the artifact - see YSMMesh#drawWithPreferredPath).
 *
 * This mixin runs at the head of {@code drawPosed} - the exact moment Epic
 * Fight is about to use its CPU rendering shader - and substitutes this mod's
 * CPU skinning path (CPU skin -> dynamic VBO -> cpu_skin shader) for YSM
 * meshes. When the CPU path declines (GUI projection, shader packs, corrupt
 * input, shader compile failure, ...), the original drawPosed runs unchanged,
 * so no other mesh or render pass is affected.
 */
@Mixin(value = SkinnedMesh.class, remap = false)
public abstract class SkinnedMeshCpuRenderMixin {

    @Inject(
            method = "drawPosed(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lyesman/epicfight/api/client/model/Mesh$DrawingFunction;IFFFFILyesman/epicfight/api/model/Armature;[Lyesman/epicfight/api/utils/math/OpenMatrix4f;)V",
            at = @At("HEAD"),
            cancellable = true,
            // Non-critical: if an Epic Fight version changes this signature the
            // original CPU skinning path simply keeps running.
            require = 0
    )
    private void ysmef$useCpuSkinning(PoseStack poseStack, VertexConsumer bufferbuilder,
                                      Mesh.DrawingFunction drawingFunction, int packedLight,
                                      float r, float g, float b, float a, int overlay,
                                      Armature armature, OpenMatrix4f[] poses, CallbackInfo ci) {
        SkinnedMesh self = (SkinnedMesh) (Object) this;
        if (self instanceof YSMMesh mesh) {
            if (YsmCpuRenderPath.tryRender(mesh, poseStack, drawingFunction, packedLight,
                    r, g, b, a, overlay, armature, poses)) {
                ci.cancel();
            }
        }
    }
}
