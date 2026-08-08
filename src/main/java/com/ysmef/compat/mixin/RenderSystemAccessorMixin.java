package com.ysmef.compat.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes RenderSystem's shader light directions to the GPU skinning shader
 * (ported from ModernYSM's RenderSystemAccessor).
 */
@Mixin(RenderSystem.class)
public interface RenderSystemAccessorMixin {
    @Accessor("shaderLightDirections")
    static Vector3f[] ysmef$getShaderLightDirections() {
        return null;
    }
}
