package com.ysmef.compat.event;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.TlmModelLibrary;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.renderer.YSMModelAccess;
import com.ysmef.compat.renderer.YSMPlayerRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent;

/**
 * Client-side registration:
 * - hooks the YSM-aware patched renderer into Epic Fight's patched renderer registry
 * - registers the generated mesh resource pack (converted Epic Fight base meshes
 *   for every locally available YSM model, see YSMMeshLibrary)
 * - triggers the base-mesh generation at client setup and refreshes it on reload
 *
 * Epic Fight's own player patches (Server/Remote/LocalPlayerPatch) are intentionally
 * left untouched: they already handle combat animations and decide when Epic Fight
 * takes over rendering.
 */
@Mod.EventBusSubscriber(
        modid = YSMEpicFightCompat.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public class YSMCompatClientEvents {

    /**
     * Register the YSM-aware patched renderer for the player entity type.
     * LOWEST priority so this registration wins over other Epic Fight addons.
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void registerRenderer(PatchedRenderersEvent.Add event) {
        event.addPatchedEntityRenderer(EntityType.PLAYER,
                (entityType) -> new YSMPlayerRenderer(event.getContext(), entityType)
                        .initLayerLast(event.getContext(), entityType));

        YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: Registered YSMPlayerRenderer for Player entity");
    }

    /**
     * Register the generated mesh folder as an always-on client resource pack, so
     * Epic Fight's mesh loader can read the generated animmodels JSONs.
     *
     * This event fires before the very first resource reload, so the blocking
     * generation gate runs here: if the config folder is missing or any YSM
     * model is unconverted/outdated, all models are re-converted on all CPU
     * cores before the pack repository is built, guaranteeing the reload never
     * sees a half-generated pack (which showed up as missing faces).
     *
     * TLM maid meshes are generated in the same blocking gate: they are written
     * into the same generated pack folder, and generating them after the pack
     * was registered (as happened with the FMLClientSetup deferred work) left
     * the first resource reload with an incomplete pack for maids.
     */
    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        YSMMeshLibrary.preparePackFolder();
        try {
            YSMMeshLibrary.ensureGeneratedBlocking();
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: base mesh generation failed", t);
        }
        try {
            TlmModelLibrary.resetLazyGeneration();
            net.minecraft.client.Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getResourceManager() != null) {
                TlmModelLibrary.generateAll(mc.getResourceManager());
            }
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: TLM mesh generation failed", t);
        }
        event.addRepositorySource((consumer) -> {
            Pack pack = Pack.create(
                    "ysm_epicfight_compat_generated",
                    Component.literal("YSM-EF Generated Meshes"),
                    true,
                    (id) -> new PathPackResources(id, YSMMeshLibrary.getPackRoot(), true),
                    new Pack.Info(Component.literal("Generated Epic Fight base meshes for YSM models"), 15, FeatureFlags.VANILLA_SET),
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.TOP,
                    false,
                    PackSource.BUILT_IN);
            if (pack != null) {
                consumer.accept(pack);
            }
        });
    }

    /**
     * Safety net for the YSM/TLM mesh gates: the YSM base meshes are generated
     * earlier, in addPackFinders, before the first resource reload ever reads
     * the generated pack; the ensureGeneratedBlocking call here is only a
     * no-op safety net when everything is already up to date. The TLM meshes
     * are also regenerated here with the fully loaded resource manager (covers
     * jar-builtin TLM manifests that may not be visible during addPackFinders).
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                YSMMeshLibrary.ensureGeneratedBlocking();
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: base mesh generation failed", t);
            }
            try {
                TlmModelLibrary.resetLazyGeneration();
                TlmModelLibrary.generateAll(Minecraft.getInstance().getResourceManager());
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: TLM mesh generation failed", t);
            }
        });
    }

    /**
     * Refresh generated meshes on resource reload so model file changes (F3+T) apply.
     */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            YSMModelAccess.clearCache();
            try {
                YSMMeshLibrary.ensureGeneratedBlocking();
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: base mesh regeneration failed", t);
            }
            try {
                TlmModelLibrary.resetLazyGeneration();
                TlmModelLibrary.generateAll(resourceManager);
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: TLM mesh regeneration failed", t);
            }
        });
    }
}
