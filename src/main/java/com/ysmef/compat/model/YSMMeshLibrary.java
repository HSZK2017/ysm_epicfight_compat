package com.ysmef.compat.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmModelPackage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.client.model.Meshes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry of generated Epic Fight base meshes for YSM models.
 *
 * At client setup, every locally available YSM model package is converted into
 * an Epic Fight animmodels mesh JSON and written into a generated resource pack
 * at config/ysm_epicfight_compat/resourcepack (registered as a client resource
 * pack, see YSMCompatClientEvents). Each converted mesh is then registered in
 * Epic Fight's Meshes registry through a MeshAccessor, the same mechanism the
 * EpicFight_TouhouLittleMaid compat mod uses for its wine_fox model.
 *
 * Textures of the model packages are registered in the texture manager under
 * our own resource locations so Epic Fight can render the mesh with the YSM
 * model's texture regardless of the (obfuscated) YSM texture registry.
 */
public class YSMMeshLibrary {

    private static final Path PACK_ROOT = Paths.get("config", "ysm_epicfight_compat", "resourcepack");
    private static final Path MESH_DIR = PACK_ROOT.resolve("assets")
            .resolve(YSMEpicFightCompat.MODID).resolve("animmodels").resolve("entity");
    private static final Path PACK_META = PACK_ROOT.resolve("pack.mcmeta");

    private static final String MESH_NAMESPACE = YSMEpicFightCompat.MODID;

    /** modelId -> registered mesh accessor */
    private static final Map<String, Meshes.MeshAccessor<YSMMesh>> MESHES = new LinkedHashMap<>();

    /** textureRL string -> png bytes (registered into the texture manager on demand) */
    private static final Map<String, byte[]> TEXTURE_DATA = new LinkedHashMap<>();

    /** modelId + '#' + textureName -> textureRL */
    private static final Map<String, ResourceLocation> TEXTURE_LOCATIONS = new LinkedHashMap<>();

    /** textureRL string -> true once registered in the texture manager */
    private static final Map<String, Boolean> UPLOADED_TEXTURES = new ConcurrentHashMap<>();

    private static volatile boolean generated = false;

    /**
     * The resource pack root that should be registered as a client resource pack.
     */
    public static Path getPackRoot() {
        return PACK_ROOT;
    }

    /**
     * Ensure the generated resource pack skeleton exists (called before the pack
     * repository is built).
     */
    public static void preparePackFolder() {
        try {
            Files.createDirectories(MESH_DIR);
            if (!Files.exists(PACK_META)) {
                Files.writeString(PACK_META, """
                        {
                            "pack": {
                                "description": "YSM-EF Compat generated meshes",
                                "pack_format": 15
                            }
                        }
                        """, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: failed to prepare generated pack folder", e);
        }
    }

    /**
     * Scan all locally available YSM models, convert them to Epic Fight mesh
     * JSONs on disk, and register them in Epic Fight's mesh registry.
     */
    public static synchronized void generateAll() {
        preparePackFolder();

        MESHES.clear();
        TEXTURE_DATA.clear();
        TEXTURE_LOCATIONS.clear();

        Map<String, Boolean> models = YsmModelPackage.scanAvailableModels();
        int converted = 0;
        for (Map.Entry<String, Boolean> entry : models.entrySet()) {
            String modelId = entry.getKey();
            try {
                YsmModelPackage pkg = YsmModelPackage.load(modelId);
                if (pkg == null || pkg.geometry == null) {
                    YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: skipping model '{}' (failed to load geometry)", modelId);
                    continue;
                }

                String textureRL = registerTextures(modelId, pkg);

                String meshId = sanitize(modelId);
                Path outFile = MESH_DIR.resolve(meshId + ".json");
                int quads = EFMeshJsonWriter.write(pkg, outFile, textureRL);
                if (quads < 0) {
                    YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: skipping model '{}' (no geometry after conversion)", modelId);
                    continue;
                }

                Meshes.MeshAccessor<YSMMesh> accessor = Meshes.MeshAccessor.create(
                        MESH_NAMESPACE, "entity/" + meshId,
                        (loader) -> loader.loadSkinnedMesh(YSMMesh::new));
                MESHES.put(modelId, accessor);
                converted++;
                if (YSMEpicFightCompat.LOGGER.isDebugEnabled()) {
                    YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: converted model '{}' -> {} quads", modelId, quads);
                }
            } catch (Exception e) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to convert model {}", modelId, e);
            }
        }
        generated = true;
        YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: generated {} base meshes from {} YSM model packages", converted, models.size());
    }

    /**
     * Register all textures of the package as texture-manager-ready data and
     * return the resource location of the model's default texture.
     */
    private static String registerTextures(String modelId, YsmModelPackage pkg) {
        String defaultName = pkg.defaultTexture;
        for (Map.Entry<String, byte[]> entry : pkg.textures.entrySet()) {
            ResourceLocation rl = textureLocation(modelId, entry.getKey());
            TEXTURE_LOCATIONS.put(modelId + "#" + entry.getKey(), rl);
            TEXTURE_DATA.put(rl.toString(), entry.getValue());
        }
        ResourceLocation defaultRL = null;
        if (!defaultName.isEmpty()) {
            defaultRL = TEXTURE_LOCATIONS.get(modelId + "#" + defaultName);
        }
        if (defaultRL == null && !pkg.textures.isEmpty()) {
            defaultRL = TEXTURE_LOCATIONS.get(modelId + "#" + pkg.textures.keySet().iterator().next());
        }
        return defaultRL != null ? defaultRL.toString()
                : ResourceLocation.withDefaultNamespace("textures/entity/steve.png").toString();
    }

    private static ResourceLocation textureLocation(String modelId, String textureName) {
        return ResourceLocation.fromNamespaceAndPath(MESH_NAMESPACE,
                "textures/" + sanitize(modelId) + "/" + sanitize(textureName) + ".png");
    }

    private static String sanitize(String value) {
        StringBuilder sb = new StringBuilder();
        boolean stripped = false;
        for (char c : value.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
                stripped = true;
            }
        }
        if (stripped) {
            sb.append('_').append(Integer.toHexString(value.hashCode()));
        }
        return sb.toString();
    }

    /**
     * Find the generated mesh accessor for the given YSM model id.
     */
    public static Meshes.MeshAccessor<YSMMesh> findMesh(String modelId) {
        return MESHES.get(modelId);
    }

    /**
     * Resolve the texture resource location for the given model + texture name.
     * Falls back to the model's first texture when the name is unknown.
     */
    public static ResourceLocation findTexture(String modelId, String textureName) {
        ResourceLocation rl = TEXTURE_LOCATIONS.get(modelId + "#" + textureName);
        if (rl == null) {
            for (Map.Entry<String, ResourceLocation> entry : TEXTURE_LOCATIONS.entrySet()) {
                if (entry.getKey().startsWith(modelId + "#")) {
                    return entry.getValue();
                }
            }
        }
        return rl;
    }

    /**
     * Upload the texture bytes to the texture manager if not done yet.
     * Must be called on the render thread.
     */
    public static void ensureTextureUploaded(ResourceLocation rl) {
        if (rl == null || UPLOADED_TEXTURES.containsKey(rl.toString())) {
            return;
        }
        byte[] data = TEXTURE_DATA.get(rl.toString());
        if (data == null) {
            return;
        }
        try {
            NativeImage image = NativeImage.read(data);
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
        } catch (IOException e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to upload texture {}", rl, e);
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
        }
    }

    public static boolean isGenerated() {
        return generated;
    }

    public static int meshCount() {
        return MESHES.size();
    }

    /**
     * The model ids that have a generated base mesh (for diagnostics).
     */
    public static java.util.Set<String> availableModelIds() {
        return java.util.Collections.unmodifiableSet(MESHES.keySet());
    }
}
