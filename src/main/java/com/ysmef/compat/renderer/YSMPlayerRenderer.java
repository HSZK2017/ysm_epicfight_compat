package com.ysmef.compat.renderer;

import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.renderer.layer.YsmConditionalArmorLayer;
import com.ysmef.compat.renderer.layer.YsmConditionalElytraLayer;
import com.ysmef.compat.renderer.layer.YsmConditionalHeadLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
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
import yesman.epicfight.client.renderer.patched.entity.PatchedEntityRenderer;
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

    /**
     * The patched player renderer this one displaced, or null when there was none.
     *
     * <p>Epic Fight has a single slot for the player renderer and whoever registers
     * last wins, so taking the slot outright silently drops another addon's player
     * renderer. Keeping it here lets players this mod has nothing to say about be
     * handed straight back to it rather than drawn as a plain Epic Fight biped.
     * Ported from EpicYSM's {@code PlayerRendererSlot} sharing approach (MIT).
     */
    private final PatchedEntityRenderer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, ?, ?> displaced;

    /** Renderers that threw once, so the log says it once and the fallback sticks. */
    private static final java.util.Set<String> DISPLACED_FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public YSMPlayerRenderer(EntityRendererProvider.Context context, EntityType<?> entityType) {
        this(context, entityType, null);
    }

    public YSMPlayerRenderer(EntityRendererProvider.Context context, EntityType<?> entityType,
                             PatchedEntityRenderer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, ?, ?> displaced) {
        super(Meshes.BIPED, context, entityType);
        this.displaced = displaced;

        this.addPatchedLayer(ArrowLayer.class, new PatchedArrowLayer<>(context));
        this.addPatchedLayer(BeeStingerLayer.class, new PatchedBeeStingerLayer<>());
        this.addPatchedLayer(CapeLayer.class, new PatchedCapeLayer());
        this.addPatchedLayer(PlayerItemInHandLayer.class, new PatchedItemInHandLayer<>());
        // Replace Epic Fight's vanilla-shaped armor with a conditional layer that
        // stays hidden while a YSM mesh is in use (armor models do not fit it).
        this.addPatchedLayerAlways(HumanoidArmorLayer.class,
                new YsmConditionalArmorLayer<>(Meshes.BIPED, context.getModelManager()));
        // Same for the vanilla head (worn headgear) and elytra models: they are
        // shaped for the vanilla biped and would overlay a YSM mesh in battle mode.
        this.addPatchedLayerAlways(CustomHeadLayer.class, new YsmConditionalHeadLayer<>());
        this.addPatchedLayerAlways(ElytraLayer.class, new YsmConditionalElytraLayer<>());
    }

    private static int meshProviderDiagCount = 0;

    // ------------------------------------------------------------------
    // Sharing Epic Fight's single player-renderer slot
    // ------------------------------------------------------------------

    /**
     * @param renderer    the renderer that held the slot, or null when none did
     * @param description a short word for the log ("" when there was no previous)
     */
    public record Previous(
            PatchedEntityRenderer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, ?, ?> renderer,
            String description) {}

    /** Renderer context Epic Fight handed out, needed to build a wrapper later. */
    private static volatile EntityRendererProvider.Context context;

    /** Whether the periodic slot check has already given up. */
    private static boolean slotCheckGaveUp = false;
    private static int lastSlotCheckTick = Integer.MIN_VALUE;

    public static void rememberContext(EntityRendererProvider.Context rendererContext) {
        context = rendererContext;
    }

    /** The renderer this one took the slot from, or null. */
    public PatchedEntityRenderer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, ?, ?> displaced() {
        return this.displaced;
    }

    /**
     * The player-renderer provider that was already in the registration event's own
     * map, resolved to a renderer instance - or a no-previous result.
     *
     * <p>The map is private on Epic Fight's event, so it is read reflectively. The
     * renderer is built here rather than lazily so that the caller has something
     * concrete to remember; a provider that throws yields the no-previous result.
     */
    @SuppressWarnings("unchecked")
    public static Previous takePreviousProvider(Object event) {
        try {
            for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (!java.util.Map.class.isAssignableFrom(field.getType()) || !field.trySetAccessible()) {
                        continue;
                    }
                    Object value = field.get(event);
                    if (!(value instanceof java.util.Map<?, ?> map)) {
                        continue;
                    }
                    Object provider = map.get(EntityType.PLAYER);
                    if (provider instanceof java.util.function.Function<?, ?> function) {
                        PatchedEntityRenderer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, ?, ?> built =
                                ((java.util.function.Function<EntityType<?>, PatchedEntityRenderer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, ?, ?>>) function)
                                        .apply(EntityType.PLAYER);
                        if (built != null) {
                            return new Previous(built, built.getClass().getName());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.debug(
                    "YSM-EF Compat: could not read the player renderer registered before this mod's", t);
        }
        return new Previous(null, "");
    }

    /**
     * Every few seconds: is this mod's renderer still the one Epic Fight uses for
     * players? If another addon's has taken the slot since (it registered later, or
     * re-registered), this mod's renderer is wrapped around it - and around whatever
     * it displaced in turn - so all of them keep being called.
     *
     * <p>This is a safety net, not the normal path: normally registration order
     * settles the slot once.
     */
    public static void ensureInFront(int tick) {
        if (slotCheckGaveUp || context == null || tick - lastSlotCheckTick < 100) {
            return;
        }
        lastSlotCheckTick = tick;
        try {
            Object engine = yesman.epicfight.client.ClientEngine.getInstance().renderEngine;
            Object current = yesman.epicfight.client.ClientEngine.getInstance().renderEngine
                    .getEntityRenderer(EntityType.PLAYER);
            if (!(current instanceof PatchedEntityRenderer<?, ?, ?, ?>) || current instanceof YSMPlayerRenderer) {
                return;
            }
            @SuppressWarnings("unchecked")
            PatchedEntityRenderer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, ?, ?> displaced =
                    (PatchedEntityRenderer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>, ?, ?>) current;
            YSMPlayerRenderer wrapper = new YSMPlayerRenderer(context, EntityType.PLAYER, displaced);
            wrapper.initLayerLast(context, EntityType.PLAYER);

            if (putRendererInEngine(engine, current, wrapper)) {
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: another mod's player renderer ({}) took Epic Fight's slot after this mod's; players are now drawn through this mod, which hands them to that renderer whenever it does not handle them",
                        current.getClass().getName());
            } else {
                slotCheckGaveUp = true;
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: another mod's player renderer ({}) holds Epic Fight's slot and its renderer table could not be reached to share it; YSM models will not be drawn through Epic Fight until that mod is removed or loads before this one",
                        current.getClass().getName());
            }
        } catch (Throwable t) {
            slotCheckGaveUp = true;
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: could not check which player renderer Epic Fight uses", t);
        }
    }

    /** Swap the player entry in Epic Fight's renderer table, wherever it is held. */
    private static boolean putRendererInEngine(Object engine, Object expected, YSMPlayerRenderer replacement) {
        try {
            for (java.lang.reflect.Field field : engine.getClass().getDeclaredFields()) {
                if (!java.util.Map.class.isAssignableFrom(field.getType()) || !field.trySetAccessible()) {
                    continue;
                }
                Object value = field.get(engine);
                if (!(value instanceof java.util.Map<?, ?> map) || map.get(EntityType.PLAYER) != expected) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                java.util.Map<EntityType<?>, Object> cache = (java.util.Map<EntityType<?>, Object>) map;
                cache.put(EntityType.PLAYER, replacement);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Whether this mod has a converted mesh for the player, and therefore whether
     * the player is this renderer's business at all. False for a player with no YSM
     * model, one using YSM's built-in vanilla player models, and one whose
     * conversion has not finished - all of which belong to whoever held the slot
     * before (or to Epic Fight's default biped when nobody did).
     */
    private static boolean handles(AbstractClientPlayer player) {
        try {
            return YSMMeshSelector.selectMesh(player) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Hand a player this mod does not handle back to the renderer that held the
     * slot before. That is what makes taking the slot shareable instead of
     * destructive.
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void render(AbstractClientPlayer entity, AbstractClientPlayerPatch<AbstractClientPlayer> entitypatch,
                       LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer,
                       MultiBufferSource buffer, PoseStack poseStack, int packedLight, float partialTicks) {
        if (this.displaced != null && !handles(entity)) {
            try {
                // The displaced renderer's own type arguments are whatever it
                // declared (Epic Fight's base declares four, and another addon's
                // mesh and armature types are not knowable here), so this one call
                // is made through the raw type rather than inventing type arguments
                // that would be wrong for every renderer but one.
                ((PatchedEntityRenderer) this.displaced).render(
                        entity, entitypatch, renderer, buffer, poseStack, packedLight, partialTicks);
                return;
            } catch (Throwable t) {
                if (DISPLACED_FAILED.add(this.displaced.getClass().getName())) {
                    com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                            "YSM-EF Compat: the player renderer this mod displaced ({}) threw; this mod's own path is used for the rest of the session",
                            this.displaced.getClass().getName(), t);
                }
                // Fall through to this renderer's own path.
            }
        }
        super.render(entity, entitypatch, renderer, buffer, poseStack, packedLight, partialTicks);
    }

    @Override
    public AssetAccessor<HumanoidMesh> getMeshProvider(AbstractClientPlayerPatch<AbstractClientPlayer> entitypatch) {
        if (meshProviderDiagCount < 3 && com.ysmef.compat.YsmDiag.isEnabled()) {
            meshProviderDiagCount++;
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [diag] YSMPlayerRenderer#getMeshProvider: entity={} patch={} battleMode={}",
                    entitypatch.getOriginal().getGameProfile().getName(), entitypatch.getClass().getName(),
                    YSMBattleMode.isBattleMode(entitypatch.getOriginal()));
        }
        validateArmatureOnce(entitypatch.getArmature());
        AssetAccessor<HumanoidMesh> mesh = YSMMeshSelector.selectMesh(entitypatch.getOriginal());
        return mesh != null ? mesh : super.getMeshProvider(entitypatch);
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
