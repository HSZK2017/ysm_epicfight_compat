package com.ysmef.compat.mixin;

import com.ysmef.compat.model.AnimationRegistryGuard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.network.client.CPCheckAnimationRegistryMatches;

/**
 * Client-side half of the animation registry exemption: this mod's generated wheel templates never
 * leave the client in the registry list it reports.
 *
 * <p>{@link AnimationManagerValidationMixin} only helps on a server that runs this mod. A dedicated
 * server without it compares the reported list verbatim, and the templates this client generated
 * from its own wheel use - which no server can have - disconnect the player on join. Filtering at
 * the packet's construction makes the list match on any server.
 *
 * <p>Both fields are rewritten together: {@code toBytes} writes {@code animationCount} as the
 * element count and then every entry of {@code registryNames}, and the server reads exactly that
 * many strings. A count that disagreed with the array would misalign the rest of the payload.
 *
 * <p>Soft injection for the same reason as the server half; {@link AnimationRegistryGuard} reports at
 * startup whether the target still exists.
 */
@Mixin(value = CPCheckAnimationRegistryMatches.class, remap = false)
public abstract class AnimationRegistryClientFilterMixin {

    @Shadow
    @Mutable
    @Final
    public int animationCount;

    @Shadow
    @Mutable
    @Final
    public String[] registryNames;

    @Inject(method = "<init>(I[Ljava/lang/String;)V", at = @At("RETURN"), require = 0)
    private void ysmef$dropGeneratedTemplates(int count, String[] names, CallbackInfo ci) {
        String[] filtered = AnimationRegistryGuard.withoutGenerated(this.registryNames);
        if (filtered != this.registryNames) {
            this.registryNames = filtered;
            this.animationCount = filtered.length;
        }
    }
}
