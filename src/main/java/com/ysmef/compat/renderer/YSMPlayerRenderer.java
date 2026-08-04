package com.ysmef.compat.renderer;

import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.renderer.layer.YsmConditionalArmorLayer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.patched.entity.PHumanoidRenderer;
import yesman.epicfight.client.renderer.patched.layer.PatchedArrowLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedBeeStingerLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedCapeLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.client.world.capabilites.entitypatch.player.AbstractClientPlayerPatch;

/**
 * Patched player renderer bridging YSM models into Epic Fight's render pipeline.
 *
 * Rendering itself is entirely handled by Epic Fight's patched render pipeline; this class
 * only swaps the mesh to a YSM-converted one when the rendered player has a YSM model
 * active. The mesh draws with the YSM model's texture (see YSMMesh) and is deformed by
 * Epic Fight's animations through joint skinning.
 *
 * Note on the base type: this deliberately uses LivingEntityRenderer as the renderer type
 * parameter instead of PlayerRenderer (which Epic Fight's PPlayerRenderer uses). YSM
 * replaces the vanilla player renderer with its own CustomPlayerRenderer (a
 * LivingEntityRenderer subclass that is NOT a PlayerRenderer), and Epic Fight's pipeline
 * may be invoked with it. PPlayerRenderer.prepareModel casts the renderer to
 * PlayerRenderer unconditionally and crashes in that case. The visibility toggling of
 * PPlayerRenderer is replicated here, guarded by an instanceof check.
 */
@OnlyIn(Dist.CLIENT)
public class YSMPlayerRenderer extends PHumanoidRenderer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, PlayerModel<AbstractClientPlayer>, LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>, HumanoidMesh> {

    public YSMPlayerRenderer(EntityRendererProvider.Context context, EntityType<?> entityType) {
        super(Meshes.BIPED, context, entityType);

        this.addPatchedLayer(ArrowLayer.class, new PatchedArrowLayer<>(context));
        this.addPatchedLayer(BeeStingerLayer.class, new PatchedBeeStingerLayer<>());
        this.addPatchedLayer(CapeLayer.class, new PatchedCapeLayer());
        this.addPatchedLayer(PlayerItemInHandLayer.class, new PatchedItemInHandLayer<>());
        // Replace Epic Fight's vanilla-shaped armor with a conditional layer that
        // stays hidden while a YSM mesh is in use (armor models do not fit it).
        this.addPatchedLayerAlways(HumanoidArmorLayer.class,
                new YsmConditionalArmorLayer<>(Meshes.BIPED, context.getModelManager()));
    }

    @Override
    public AssetAccessor<HumanoidMesh> getMeshProvider(AbstractClientPlayerPatch<AbstractClientPlayer> entitypatch) {
        validateArmatureOnce(entitypatch.getArmature());
        logBattleModeDiagOnce(entitypatch.getOriginal());
        AssetAccessor<HumanoidMesh> mesh = YSMMeshSelector.selectMesh(entitypatch.getOriginal());
        return mesh != null ? mesh : super.getMeshProvider(entitypatch);
    }

    private static final java.util.Set<String> DIAG_MODE_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** One-time per player: whether Epic Fight's battle mode is active while the patched renderer runs. */
    private static void logBattleModeDiagOnce(AbstractClientPlayer player) {
        if (player == null || !DIAG_MODE_LOGGED.add(player.getUUID().toString())) {
            return;
        }
        boolean battle = YSMBattleMode.isBattleMode(player);
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] player '{}' epicFightBattleMode={}",
                player.getGameProfile().getName(), battle);
    }

    /**
     * The generated meshes assume the joint ids of Epic Fight's biped armature (see
     * YSMJointMapper). Validate the assumption once against the live armature.
     */
    private static volatile boolean armatureValidated = false;

    private static void validateArmatureOnce(yesman.epicfight.api.model.Armature armature) {
        if (armatureValidated || armature == null) {
            return;
        }
        armatureValidated = true;
        String[][] expected = {
                {"Root", "0"}, {"Thigh_R", "1"}, {"Leg_R", "2"}, {"Knee_R", "3"},
                {"Thigh_L", "4"}, {"Leg_L", "5"}, {"Knee_L", "6"}, {"Torso", "7"},
                {"Chest", "8"}, {"Head", "9"}, {"Shoulder_R", "10"}, {"Arm_R", "11"},
                {"Hand_R", "12"}, {"Tool_R", "13"}, {"Elbow_R", "14"}, {"Shoulder_L", "15"},
                {"Arm_L", "16"}, {"Hand_L", "17"}, {"Tool_L", "18"}, {"Elbow_L", "19"}
        };
        for (String[] pair : expected) {
            yesman.epicfight.api.animation.Joint joint = armature.searchJointByName(pair[0]);
            int expectedId = Integer.parseInt(pair[1]);
            if (joint == null || joint.getId() != expectedId) {
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.error(
                        "YSM-EF Compat: biped armature mismatch for joint '{}' (expected id {}, got {}). Generated meshes will NOT deform correctly with this Epic Fight version!",
                        pair[0], expectedId, joint == null ? "missing" : joint.getId());
                return;
            }
        }
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: biped armature joint layout verified");
    }

    @Override
    protected void prepareModel(HumanoidMesh mesh, AbstractClientPlayer entity,
                                AbstractClientPlayerPatch<AbstractClientPlayer> entitypatch,
                                LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer) {
        mesh.initialize();

        if (entity.isSpectator()) {
            mesh.head.setHidden(false);
            mesh.hat.setHidden(false);
            mesh.torso.setHidden(true);
            mesh.jacket.setHidden(true);
            mesh.leftArm.setHidden(true);
            mesh.leftSleeve.setHidden(true);
            mesh.rightArm.setHidden(true);
            mesh.rightSleeve.setHidden(true);
            mesh.leftLeg.setHidden(true);
            mesh.leftPants.setHidden(true);
            mesh.rightLeg.setHidden(true);
            mesh.rightPants.setHidden(true);
        } else {
            mesh.head.setHidden(false);
            mesh.torso.setHidden(false);
            mesh.leftArm.setHidden(false);
            mesh.rightArm.setHidden(false);
            mesh.leftLeg.setHidden(false);
            mesh.rightLeg.setHidden(false);
            mesh.hat.setHidden(!entity.isModelPartShown(PlayerModelPart.HAT));
            mesh.jacket.setHidden(!entity.isModelPartShown(PlayerModelPart.JACKET));
            mesh.leftSleeve.setHidden(!entity.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
            mesh.rightSleeve.setHidden(!entity.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
            mesh.leftPants.setHidden(!entity.isModelPartShown(PlayerModelPart.LEFT_PANTS_LEG));
            mesh.rightPants.setHidden(!entity.isModelPartShown(PlayerModelPart.RIGHT_PANTS_LEG));
        }
    }
}
