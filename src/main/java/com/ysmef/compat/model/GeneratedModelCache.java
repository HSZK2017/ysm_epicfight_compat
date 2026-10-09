package com.ysmef.compat.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmModelPackage;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Disk cache integrity, source fingerprints and generated-file cleanup. */
final class GeneratedModelCache {
    private final Path meshDir;
    private final Path runtimeDir;
    private final TextureVerifier textureVerifier;
    private final Consumer<Set<String>> textureCleaner;

    @FunctionalInterface
    interface TextureVerifier {
        boolean verify(ResourceLocation location, long size, String hash);
    }

    GeneratedModelCache(Path meshDir, Path runtimeDir) {
        this(meshDir, runtimeDir, TextureStore::verifyTextureCache, TextureStore::deleteStaleTextureFiles);
    }

    GeneratedModelCache(Path meshDir, Path runtimeDir, TextureVerifier textureVerifier,
                        Consumer<Set<String>> textureCleaner) {
        this.meshDir = meshDir;
        this.runtimeDir = runtimeDir;
        this.textureVerifier = textureVerifier;
        this.textureCleaner = textureCleaner;
    }

    /**
     * Cheap metadata fingerprint check for one model; a mismatch falls back to
     * the content fingerprint (mirrors the old whole-set gate). A sig-only
     * refresh (YSM re-writes model files without content changes) updates the
     * manifest in place so no re-conversion happens.
     */
    boolean fingerprintMatches(String modelId, JsonObject modelEntry) {
        try {
            if (modelEntry.get("sig").getAsLong() == YsmModelPackage.fingerprint(modelId)) {
                return true;
            }
            long contentFingerprint = YsmModelPackage.contentFingerprint(modelId);
            if (contentFingerprint != -1L && contentFingerprint == modelEntry.get("csig").getAsLong()) {
                long refreshed = YsmModelPackage.fingerprint(modelId);
                if (refreshed != -1L) {
                    modelEntry.addProperty("sig", refreshed);
                    ManifestStore.update(modelId, modelEntry);
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            // A verification that failed is treated exactly like one that answered "no": the model is
            // re-converted, which is the right recovery either way. Only one of the two is worth
            // knowing about, and it used to be silent.
            YSMEpicFightCompat.LOGGER.debug(
                    "YSM-EF Compat: could not verify a cached manifest entry, re-converting: {}", e.toString());
            return false;
        }
    }

    /**
     * Verify every generated output of one model (mesh JSON, runtime JSON and
     * cached texture bytes) against the manifest hashes/sizes.
     */
    boolean verifyModelOutputs(JsonObject modelEntry) {
        try {
            String meshName = modelEntry.get("mesh").getAsString();
            if (!hashMatches(meshDir.resolve(meshName + ".json"),
                    modelEntry.get("msize").getAsLong(), modelEntry.get("mhash").getAsString())) {
                return false;
            }
            if (!hashMatches(runtimeDir.resolve(meshName + ".json"),
                    modelEntry.get("rsize").getAsLong(), modelEntry.get("rhash").getAsString())) {
                return false;
            }
            if (!modelEntry.has("textures") || !modelEntry.get("textures").isJsonObject()) {
                return false;
            }
            for (Map.Entry<String, JsonElement> texEntry : modelEntry.getAsJsonObject("textures").entrySet()) {
                JsonObject tex = texEntry.getValue().getAsJsonObject();
                if (!tex.has("rl") || !tex.has("hash") || !tex.has("size")) {
                    return false;
                }
                if (!textureVerifier.verify(ResourceLocation.parse(tex.get("rl").getAsString()),
                        tex.get("size").getAsLong(), tex.get("hash").getAsString())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hashMatches(Path file, long expectedSize, String expectedHash) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) != expectedSize) {
                return false;
            }
            return sha256Hex(file).equals(expectedHash);
        } catch (IOException e) {
            return false;
        }
    }

    static String sha256Hex(Path file) throws IOException {
        return sha256Hex(Files.readAllBytes(file));
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Remove outputs of models that no longer exist locally so stale meshes,
     * runtime scripts and cached textures are never picked up again.
     */
    void cleanupStaleFiles(JsonObject manifestModels) {
        Set<String> keepMeshIds = new HashSet<>();
        Set<String> keepTexturePaths = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : manifestModels.entrySet()) {
            JsonObject modelEntry = entry.getValue().getAsJsonObject();
            keepMeshIds.add(modelEntry.get("mesh").getAsString() + ".json");
            if (modelEntry.has("textures") && modelEntry.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> texEntry : modelEntry.getAsJsonObject("textures").entrySet()) {
                    String rl = texEntry.getValue().getAsJsonObject().get("rl").getAsString();
                    keepTexturePaths.add(rl.substring(rl.indexOf(':') + 1));
                }
            }
        }
        deleteStaleJsons(meshDir, keepMeshIds);
        deleteStaleJsons(runtimeDir, keepMeshIds);
        textureCleaner.accept(keepTexturePaths);
    }

    private static void deleteStaleJsons(Path dir, Set<String> keepNames) {
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !keepNames.contains(dir.relativize(path).toString().replace('\\', '/')))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
