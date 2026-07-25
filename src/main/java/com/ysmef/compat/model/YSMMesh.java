package com.ysmef.compat.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.MeshPartDefinition;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.EpicFightRenderTypes;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * A HumanoidMesh loaded from a generated Epic Fight animmodels JSON (see
 * EFMeshJsonWriter / YSMMeshLibrary).
 *
 * Epic Fight's patched render pipeline always draws the mesh with the render type of the
 * vanilla entity renderer (i.e. the player's own skin texture). To display the YSM model's
 * texture instead, the render type's texture is replaced here, keeping every other render
 * state (translucency, outline, cull, ...) from the original render type.
 *
 * The default texture comes from the mesh JSON's render_properties; the patched renderer
 * can override it per frame (players may select different textures of the same model).
 */
public class YSMMesh extends HumanoidMesh {

    private ResourceLocation textureOverride;

    public YSMMesh(Map<String, Number[]> arrayMap,
                   Map<MeshPartDefinition, List<VertexBuilder>> parts,
                   @Nullable SkinnedMesh parent,
                   RenderProperties properties) {
        super(arrayMap, parts, parent, properties);
    }

    public void setTextureOverride(ResourceLocation texture) {
        this.textureOverride = texture;
    }

    private ResourceLocation resolveTexture() {
        if (this.textureOverride != null) {
            return this.textureOverride;
        }
        if (this.getRenderProperties() != null && this.getRenderProperties().customTexturePath() != null) {
            return this.getRenderProperties().customTexturePath();
        }
        return null;
    }

    @Override
    public void draw(PoseStack poseStack, MultiBufferSource bufferSources, RenderType renderType,
                     Mesh.DrawingFunction drawingFunction, int packedLight, float r, float g, float b, float a,
                     int overlay, @Nullable Armature armature, OpenMatrix4f[] poses) {
        ResourceLocation texture = resolveTexture();
        RenderType finalRenderType = texture != null
                ? EpicFightRenderTypes.replaceTexture(texture, renderType)
                : renderType;
        super.draw(poseStack, bufferSources, finalRenderType, drawingFunction, packedLight, r, g, b, a, overlay, armature, poses);
    }
}
