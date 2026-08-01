package com.ysmef.compat.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.YSMEpicFightCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import yesman.epicfight.api.client.model.Meshes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of converted Epic Fight base meshes for Touhou Little Maid GEO models.
 *
 * TLM loads model packs from the tlm_custom_pack folder by direct file IO (not
 * the MC ResourceManager). This class replicates that: it walks every folder
 * pack under tlm_custom_pack, reads maid_model.json + model JSONs + texture PNGs
 * from disk, converts them to EF mesh JSONs, and registers textures. Jar-builtin
 * TLM models (inside the TLM mod jar) are additionally fetched via the
 * ResourceManager.
 */
public final class TlmModelLibrary {

    private static final Path MESH_DIR = YSMMeshLibrary.getMeshDir().resolve("tlm");

    public record TlmMeshEntry(Meshes.MeshAccessor<YSMMesh> accessor, ResourceLocation texture) {}

    private static final Map<String, TlmMeshEntry> MESHES = new LinkedHashMap<>();

    private TlmModelLibrary() {}

    public static TlmMeshEntry find(String modelId) {
        return MESHES.get(modelId);
    }

    private static volatile boolean attemptedLazyGeneration = false;

    public static void ensureGenerated() {
        if (!MESHES.isEmpty() || attemptedLazyGeneration) {
            return;
        }
        attemptedLazyGeneration = true;
        try {
            generateAll(net.minecraft.client.Minecraft.getInstance().getResourceManager());
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: lazy TLM mesh generation failed", t);
        }
    }

    public static void resetLazyGeneration() {
        attemptedLazyGeneration = false;
    }

    public static synchronized void generateAll(ResourceManager resourceManager) {
        MESHES.clear();
        int[] converted = {0};
        int[] seen = {0};

        // 1) tlm_custom_pack folder packs — direct disk IO (mirrors TLM's CustomPackLoader)
        Path packFolder = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("tlm_custom_pack");
        if (Files.isDirectory(packFolder)) {
            try (var stream = Files.walk(packFolder, 1)) {
                stream.filter(Files::isDirectory).skip(1)
                        .forEach(pack -> scanDiskPack(pack, converted, seen));
            } catch (Exception ignored) {
            }
        }

        // 2) jar-builtin manifests (e.g. touhou_little_maid:maid_model.json inside the TLM jar)
        for (String namespace : resourceManager.getNamespaces()) {
            ResourceLocation manifestId = ResourceLocation.fromNamespaceAndPath(namespace, "maid_model.json");
            for (Resource resource : resourceManager.getResourceStack(manifestId)) {
                try (InputStream in = resource.open()) {
                    JsonObject packJson = JsonParser.parseString(
                            new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonArray modelList = packJson.getAsJsonArray("model_list");
                    if (modelList == null) continue;
                    for (JsonElement element : modelList) {
                        if (!element.isJsonObject()) continue;
                        seen[0]++;
                        try {
                            if (convertEntryFromRM(resourceManager, element.getAsJsonObject())) {
                                converted[0]++;
                            }
                        } catch (Exception e) {
                            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to convert TLM maid model: {}", e.toString());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: generated {} TLM maid meshes from {} model entries", converted[0], seen[0]);
    }

    private static void scanDiskPack(Path packRoot, int[] converted, int[] seen) {
        Path assetsDir = packRoot.resolve("assets");
        if (!Files.isDirectory(assetsDir)) return;
        try (var nsStream = Files.list(assetsDir)) {
            nsStream.filter(Files::isDirectory).forEach(nsDir -> {
                Path manifest = nsDir.resolve("maid_model.json");
                if (!Files.isRegularFile(manifest)) return;
                try {
                    JsonObject packJson = JsonParser.parseString(
                            Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonArray modelList = packJson.getAsJsonArray("model_list");
                    if (modelList == null) return;
                    for (JsonElement element : modelList) {
                        if (!element.isJsonObject()) continue;
                        seen[0]++;
                        try {
                            if (convertEntryFromDisk(packRoot, element.getAsJsonObject())) {
                                converted[0]++;
                            }
                        } catch (Exception e) {
                            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to convert TLM maid model: {}", e.toString());
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** Convert a model entry, reading model/texture files directly from disk (packRoot/assets/...). */
    private static boolean convertEntryFromDisk(Path packRoot, JsonObject entry) {
        if (!entry.has("model_id")) return false;
        String modelId = entry.get("model_id").getAsString();
        ResourceLocation modelIdRl = ResourceLocation.tryParse(modelId);
        if (modelIdRl == null) return false;

        String namespace = modelIdRl.getNamespace();
        boolean isGecko = entry.has("is_gecko") && entry.get("is_gecko").getAsBoolean();
        float scale = entry.has("render_entity_scale")
                ? Math.max(0.2f, Math.min(2.0f, entry.get("render_entity_scale").getAsFloat()))
                : 1.0f;

        // resolve model file path relative to packRoot/assets/<ns>/<path>
        String modelPathStr = entry.has("model")
                ? ResourceLocation.parse(entry.get("model").getAsString()).getPath()
                : "models/entity/" + modelIdRl.getPath() + ".json";
        Path modelFile = packRoot.resolve("assets").resolve(namespace).resolve(modelPathStr);
        if (!Files.isRegularFile(modelFile)) return false;

        YSMGeoModel geoModel;
        try {
            String json = Files.readString(modelFile, StandardCharsets.UTF_8);
            geoModel = isGecko ? YSMGeoModel.parse(json) : TlmGeoModelParser.parse(json);
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to parse TLM model {} ({}): {}", modelId, modelFile, e.toString());
            return false;
        }
        if (geoModel == null) return false;

        // resolve texture file
        ResourceLocation ourTexture = null;
        String texPathStr = entry.has("texture")
                ? ResourceLocation.parse(entry.get("texture").getAsString()).getPath()
                : "textures/entity/" + modelIdRl.getPath() + ".png";
        Path texFile = packRoot.resolve("assets").resolve(namespace).resolve(texPathStr);
        if (Files.isRegularFile(texFile)) {
            try {
                ourTexture = YSMMeshLibrary.registerTextureBytes(
                        "tlm/" + sanitize(namespace) + "/" + sanitize(texPathStr),
                        Files.readAllBytes(texFile));
            } catch (Exception e) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to read TLM texture {}: {}", texFile, e.toString());
            }
        }

        return writeAndRegister(modelId, modelIdRl, geoModel, scale, ourTexture);
    }

    /** Convert a model entry, reading model/texture files via the resource manager (jar-builtin). */
    private static boolean convertEntryFromRM(ResourceManager resourceManager, JsonObject entry) {
        if (!entry.has("model_id")) return false;
        String modelId = entry.get("model_id").getAsString();
        ResourceLocation modelIdRl = ResourceLocation.tryParse(modelId);
        if (modelIdRl == null) return false;

        ResourceLocation modelRl = entry.has("model")
                ? ResourceLocation.parse(entry.get("model").getAsString())
                : ResourceLocation.fromNamespaceAndPath(modelIdRl.getNamespace(),
                        "models/entity/" + modelIdRl.getPath() + ".json");
        ResourceLocation textureRl = entry.has("texture")
                ? ResourceLocation.parse(entry.get("texture").getAsString())
                : ResourceLocation.fromNamespaceAndPath(modelIdRl.getNamespace(),
                        "textures/entity/" + modelIdRl.getPath() + ".png");
        boolean isGecko = entry.has("is_gecko") && entry.get("is_gecko").getAsBoolean();
        float scale = entry.has("render_entity_scale")
                ? Math.max(0.2f, Math.min(2.0f, entry.get("render_entity_scale").getAsFloat()))
                : 1.0f;

        Optional<Resource> modelResource = resourceManager.getResource(modelRl);
        if (modelResource.isEmpty()) return false;

        YSMGeoModel geoModel;
        try (InputStream in = modelResource.get().open()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            geoModel = isGecko ? YSMGeoModel.parse(json) : TlmGeoModelParser.parse(json);
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to parse TLM model {} ({}): {}", modelId, modelRl, e.toString());
            return false;
        }
        if (geoModel == null) return false;

        ResourceLocation ourTexture = null;
        Optional<Resource> textureResource = resourceManager.getResource(textureRl);
        if (textureResource.isPresent()) {
            try (InputStream in = textureResource.get().open()) {
                ourTexture = YSMMeshLibrary.registerTextureBytes(
                        "tlm/" + sanitize(textureRl.getNamespace()) + "/" + sanitize(textureRl.getPath()),
                        in.readAllBytes());
            } catch (Exception e) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to read TLM texture {}: {}", textureRl, e.toString());
            }
        }

        return writeAndRegister(modelId, modelIdRl, geoModel, scale, ourTexture);
    }

    private static boolean writeAndRegister(String modelId, ResourceLocation modelIdRl,
                                            YSMGeoModel geoModel, float scale, ResourceLocation ourTexture) {
        String meshFile = sanitize(modelIdRl.getNamespace()) + "__" + sanitize(modelIdRl.getPath());
        Path outFile = MESH_DIR.resolve(meshFile + ".json");
        String texturePath = ourTexture != null
                ? ourTexture.toString()
                : ResourceLocation.withDefaultNamespace("textures/entity/steve.png").toString();
        try {
            int quads = EFMeshJsonWriter.writeTlmMesh(geoModel, scale, outFile, texturePath);
            if (quads < 0) return false;
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write TLM mesh for {}: {}", modelId, e.toString());
            return false;
        }

        Meshes.MeshAccessor<YSMMesh> accessor = Meshes.MeshAccessor.create(
                YSMEpicFightCompat.MODID, "entity/tlm/" + meshFile,
                (loader) -> loader.loadSkinnedMesh(YSMMesh::new));
        MESHES.put(modelId, new TlmMeshEntry(accessor, ourTexture));
        return true;
    }

    private static String sanitize(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
