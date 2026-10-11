package com.ysmef.compat.mixin;

import java.util.List;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the concrete vanilla layer classes used by EF's exact-key dispatch. */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererLayersMixin {
    @Accessor("layers")
    List<RenderLayer<?, ?>> ysmef$getLayers();
}
