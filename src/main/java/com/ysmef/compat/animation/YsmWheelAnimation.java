package com.ysmef.compat.animation;

import net.minecraftforge.fml.loading.FMLEnvironment;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.api.client.animation.property.ClientAnimationProperties;
import yesman.epicfight.api.model.Armature;

/**
 * Client-side playback type for a converted YSM wheel animation.
 *
 * The animation is played as a HIGHEST-priority composite layer. The Epic Fight
 * base layer keeps running its combat motion (movement, attack state and weapon
 * logic stay intact) while the converted frame animation overwrites the rendered
 * joint pose; this matches YSM's own extra-animation layer semantics better than
 * interrupting the current combat action on the base layer.
 *
 * Instantiated by Epic Fight's resourcepack animation loader through the
 * "constructor" section of the generated animation JSONs. The extra {@code int}
 * constructor parameter exists deliberately: Epic Fight's resourcepack loader
 * always tries the base {@link StaticAnimation} constructor first when the
 * signatures match, so the generated command uses this distinct signature to
 * force the loader to instantiate this subclass.
 */
public class YsmWheelAnimation extends StaticAnimation {

    public YsmWheelAnimation(float transitionTime, boolean isRepeat, String path,
                             AssetAccessor<? extends Armature> armature, int unusedSignatureMarker) {
        super(transitionTime, isRepeat, path, armature);
        if (FMLEnvironment.dist != null && FMLEnvironment.dist.isClient()) {
            this.addProperty(ClientAnimationProperties.LAYER_TYPE, Layer.LayerType.COMPOSITE_LAYER);
            this.addProperty(ClientAnimationProperties.PRIORITY, Layer.Priority.HIGHEST);
        }
    }

    /**
     * Converted wheel clips animate the Head joint themselves. Epic Fight's
     * player pose hook would otherwise overwrite the Head rotation with the
     * camera's look direction every tick while the clip is active, which fights
     * the sampled YSM head motion and visibly detaches the neck on large models
     * during violent animations.
     */
    @Override
    public boolean doesHeadRotFollowEntityHead() {
        return false;
    }
}
