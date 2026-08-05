package com.ysmef.compat.event;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.TlmModelLibrary;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.renderer.YSMModelAccess;
import com.ysmef.compat.renderer.YSMPlayerRenderer;
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
import yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent;

/**
 * Client-side registration:
 * - hooks the YSM-aware patched renderer into Epic Fight's patched renderer registry
 * - registers the generated mesh resource pack (converted Epic Fight base meshes
 *   for every locally available YSM model, see YSMMeshLibrary)
 *
 * Conversion is fully lazy (OpenYSM-style): nothing is generated at startup.
 * YSMMeshLibrary converts each model on first use (background pool) and the
 * generated pack folder is a live PathPackResources, so mesh JSONs written
 * after the pack repository was built are picked up on demand.
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
     * Epic Fight's on-demand mesh loader can read the generated animmodels JSONs
     * (including ones written later by the lazy per-model conversion - the pack
     * is a folder pack and resolves resources live from disk).
     *
     * Only the pack skeleton (folder + pack.mcmeta) is prepared here; no model
     * is converted at startup.
     */
    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        YSMMeshLibrary.preparePackFolder();
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
     * On resource reload (F3+T): drop the registered meshes, texture state and
     * compiled runtime models so the next mesh lookup re-validates and lazily
     * re-converts whatever changed (cheap for unchanged models: verified cache
     * restore). TLM maid meshes are re-scanned lazily on the next maid render.
     */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            YSMModelAccess.clearCache();
            YSMMeshLibrary.invalidateAll();
            TlmModelLibrary.resetLazyGeneration();
            YSMMeshLibrary.preparePackFolder();
        });
    }
}
