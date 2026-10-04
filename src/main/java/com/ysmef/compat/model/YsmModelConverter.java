package com.ysmef.compat.model;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmModelPackage;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts one YSM package into generated resources and a model-local result. */
final class YsmModelConverter {
    private final Path meshDir;
    private final Path runtimeDir;

    YsmModelConverter(Path meshDir, Path runtimeDir) {
        this.meshDir = meshDir;
        this.runtimeDir = runtimeDir;
    }

    /** Per-model conversion result produced by worker threads. */
    record TextureEntry(String textureName, ResourceLocation location, byte[] data, int[] info,
                        String hash, long size) {}

    record ModelResult(String modelId, String meshId, int quads, long fingerprint,
                       long contentFingerprint, String defaultTextureRL,
                       String meshHash, long meshSize, String runtimeHash, long runtimeSize,
                       List<TextureEntry> textures) {}

    /**
     * Worker: convert one model package (decrypt, write mesh + runtime JSON,
     * cache texture bytes). Pure CPU/disk work on model-local data; shared
     * registries are only touched by the caller thread when merging results.
     */
    ModelResult convertModel(String modelId) {
        try {
            YsmModelPackage pkg = YsmModelPackage.load(modelId);
            if (pkg == null || pkg.geometry == null) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: skipping model '{}' (failed to load geometry)", modelId);
                return null;
            }

            List<TextureEntry> textures = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : pkg.textures.entrySet()) {
                ResourceLocation rl = TextureStore.locationOf(modelId, entry.getKey());
                int[] info = pkg.textureInfo.get(entry.getKey());
                byte[] data = entry.getValue();
                TextureStore.persistTexture(modelId, entry.getKey(), data, info);
                textures.add(new TextureEntry(entry.getKey(), rl, data, info, GeneratedModelCache.sha256Hex(data), data.length));
            }
            String defaultTextureRL = TextureStore.defaultTextureOf(modelId, pkg);

            String meshId = TextureStore.sanitize(modelId);
            Path outFile = meshDir.resolve(meshId + ".json");
            Path runtimeFile = runtimeDir.resolve(meshId + ".json");
            int quads = EFMeshJsonWriter.write(pkg, outFile, runtimeFile, defaultTextureRL);
            if (quads < 0) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: skipping model '{}' (no geometry after conversion)", modelId);
                return null;
            }
            // Convert the model's wheel-selectable GEO animations into sampled
            // Avalon-style frame animation templates (deduplicated in public/).
            YsmExtraAnimationLibrary.convertModel(pkg);

            String meshHash = GeneratedModelCache.sha256Hex(outFile);
            long meshSize = Files.size(outFile);
            String runtimeHash = GeneratedModelCache.sha256Hex(runtimeFile);
            long runtimeSize = Files.size(runtimeFile);

            return new ModelResult(modelId, meshId, quads, YsmModelPackage.fingerprint(modelId),
                    // Precomputed during the package load (the same FNV-1a over
                    // the decrypted payload) - avoid decrypting the whole package
                    // a second time just for the manifest.
                    pkg.contentFingerprint != -1L ? pkg.contentFingerprint : YsmModelPackage.contentFingerprint(modelId),
                    defaultTextureRL,
                    meshHash, meshSize, runtimeHash, runtimeSize, textures);
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to convert model {}", modelId, e);
            return null;
        }
    }
}
