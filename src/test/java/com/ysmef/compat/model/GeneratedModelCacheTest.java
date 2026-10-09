package com.ysmef.compat.model;

import com.google.gson.JsonObject;
import com.ysmef.compat.model.YsmModelConverter.ModelResult;
import com.ysmef.compat.model.YsmModelConverter.TextureEntry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedModelCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void generatedOutputsMustMatchBothSizeAndContent() throws Exception {
        Path meshDir = Files.createDirectory(tempDir.resolve("meshes"));
        Path runtimeDir = Files.createDirectory(tempDir.resolve("runtime"));
        Path meshFile = meshDir.resolve("sample.json");
        Path runtimeFile = runtimeDir.resolve("sample.json");
        byte[] mesh = "mesh contents".getBytes(StandardCharsets.UTF_8);
        byte[] runtime = "runtime data".getBytes(StandardCharsets.UTF_8);
        Files.write(meshFile, mesh);
        Files.write(runtimeFile, runtime);

        JsonObject entry = new JsonObject();
        entry.addProperty("mesh", "sample");
        entry.addProperty("msize", mesh.length);
        entry.addProperty("mhash", GeneratedModelCache.sha256Hex(mesh));
        entry.addProperty("rsize", runtime.length);
        entry.addProperty("rhash", GeneratedModelCache.sha256Hex(runtime));
        entry.add("textures", new JsonObject());
        GeneratedModelCache cache = new GeneratedModelCache(meshDir, runtimeDir);

        assertTrue(cache.verifyModelOutputs(entry));
        Files.writeString(runtimeFile, "changed data", StandardCharsets.UTF_8);
        assertFalse(cache.verifyModelOutputs(entry), "same-size corruption must invalidate the cache");
        Files.write(runtimeFile, runtime);
        Files.writeString(meshFile, "short", StandardCharsets.UTF_8);
        assertFalse(cache.verifyModelOutputs(entry), "truncated mesh must invalidate the cache");
    }

    @Test
    void convertedManifestRestoresOnlyWhileAllOutputsAreIntact() throws Exception {
        Path meshDir = Files.createDirectory(tempDir.resolve("meshes"));
        Path runtimeDir = Files.createDirectory(tempDir.resolve("runtime"));
        Path textureCacheDir = Files.createDirectory(tempDir.resolve("texturecache"));
        byte[] mesh = "converted mesh".getBytes(StandardCharsets.UTF_8);
        byte[] runtime = "compiled runtime".getBytes(StandardCharsets.UTF_8);
        byte[] texture = "encoded texture".getBytes(StandardCharsets.UTF_8);
        Files.write(meshDir.resolve("sample.json"), mesh);
        Files.write(runtimeDir.resolve("sample.json"), runtime);
        ResourceLocation location = TextureStore.locationOf("sample", "skin");
        Path textureFile = textureCacheDir.resolve(location.getNamespace()).resolve(location.getPath());
        Files.createDirectories(textureFile.getParent());
        Files.write(textureFile, texture);

        ModelResult result = new ModelResult("sample", "sample", 12, 1L, 2L,
                location.toString(), GeneratedModelCache.sha256Hex(mesh), mesh.length,
                GeneratedModelCache.sha256Hex(runtime), runtime.length,
                List.of(new TextureEntry("skin", location, texture, null,
                        GeneratedModelCache.sha256Hex(texture), texture.length)));
        JsonObject entry = YsmModelManifestEntry.from(result);
        GeneratedModelCache cache = new GeneratedModelCache(meshDir, runtimeDir,
                (rl, size, hash) -> TextureStore.verifyTextureCache(textureCacheDir, rl, size, hash),
                ignored -> {});

        assertTrue(cache.verifyModelOutputs(entry));
        Files.writeString(textureFile, "damaged texture", StandardCharsets.UTF_8);
        assertFalse(cache.verifyModelOutputs(entry), "same-size texture corruption must trigger conversion");
        Files.write(textureFile, texture);
        Files.delete(textureFile);
        assertFalse(cache.verifyModelOutputs(entry), "missing cached texture must trigger conversion");
        Files.write(textureFile, texture);
        Files.delete(runtimeDir.resolve("sample.json"));
        assertFalse(cache.verifyModelOutputs(entry), "missing runtime must trigger conversion");
    }

    @Test
    void incompleteTextureManifestCannotRestoreAVisibleModelWithoutTextures() throws Exception {
        Path meshDir = Files.createDirectory(tempDir.resolve("meshes"));
        Path runtimeDir = Files.createDirectory(tempDir.resolve("runtime"));
        byte[] data = "valid output".getBytes(StandardCharsets.UTF_8);
        Files.write(meshDir.resolve("sample.json"), data);
        Files.write(runtimeDir.resolve("sample.json"), data);
        JsonObject entry = new JsonObject();
        entry.addProperty("mesh", "sample");
        entry.addProperty("msize", data.length);
        entry.addProperty("mhash", GeneratedModelCache.sha256Hex(data));
        entry.addProperty("rsize", data.length);
        entry.addProperty("rhash", GeneratedModelCache.sha256Hex(data));
        GeneratedModelCache cache = new GeneratedModelCache(meshDir, runtimeDir);

        assertFalse(cache.verifyModelOutputs(entry));
        entry.addProperty("textures", "broken");
        assertFalse(cache.verifyModelOutputs(entry));
        entry.add("textures", new JsonObject());
        assertTrue(cache.verifyModelOutputs(entry), "an explicitly textureless model is valid");
    }

    @Test
    void staleCleanupKeepsOnlyReferencedOutputsAndTexturePaths() throws Exception {
        Path meshDir = Files.createDirectory(tempDir.resolve("meshes"));
        Path runtimeDir = Files.createDirectory(tempDir.resolve("runtime"));
        Path packRoot = Files.createDirectory(tempDir.resolve("pack"));
        Path textureCacheDir = Files.createDirectory(tempDir.resolve("texturecache"));
        Files.createDirectories(meshDir.resolve("group"));
        Files.createDirectories(runtimeDir.resolve("group"));
        Path keptMesh = Files.writeString(meshDir.resolve("group/kept.json"), "keep");
        Path staleMesh = Files.writeString(meshDir.resolve("stale.json"), "remove");
        Path keptRuntime = Files.writeString(runtimeDir.resolve("group/kept.json"), "keep");
        Path staleRuntime = Files.writeString(runtimeDir.resolve("stale.json"), "remove");
        Path unrelated = Files.writeString(meshDir.resolve("notes.txt"), "keep");
        String namespace = "ysm_epicfight_compat";
        Path packTextures = packRoot.resolve("assets").resolve(namespace).resolve("textures");
        Path cachedTextures = textureCacheDir.resolve(namespace).resolve("textures");
        Files.createDirectories(packTextures.resolve("group/kept"));
        Files.createDirectories(cachedTextures.resolve("group/kept"));
        Path keptPackTexture = Files.writeString(packTextures.resolve("group/kept/skin.png"), "keep");
        Path stalePackTexture = Files.writeString(packTextures.resolve("stale.png"), "remove");
        Path keptCacheTexture = Files.writeString(cachedTextures.resolve("group/kept/skin.png"), "keep");
        Path staleCacheTexture = Files.writeString(cachedTextures.resolve("stale.png"), "remove");
        JsonObject entry = new JsonObject();
        entry.addProperty("mesh", "group/kept");
        JsonObject texture = new JsonObject();
        texture.addProperty("rl", "ysm_epicfight_compat:textures/group/kept/skin.png");
        JsonObject textures = new JsonObject();
        textures.add("skin", texture);
        entry.add("textures", textures);
        JsonObject models = new JsonObject();
        models.add("group/kept", entry);
        AtomicReference<Set<String>> keptTextures = new AtomicReference<>();
        GeneratedModelCache cache = new GeneratedModelCache(meshDir, runtimeDir,
                (rl, size, hash) -> true, paths -> {
                    keptTextures.set(paths);
                    TextureStore.deleteStaleTextureFiles(paths, packRoot, textureCacheDir);
                });

        cache.cleanupStaleFiles(models);

        assertTrue(Files.exists(keptMesh));
        assertTrue(Files.exists(keptRuntime));
        assertTrue(Files.exists(unrelated));
        assertFalse(Files.exists(staleMesh));
        assertFalse(Files.exists(staleRuntime));
        assertTrue(Files.exists(keptPackTexture));
        assertTrue(Files.exists(keptCacheTexture));
        assertFalse(Files.exists(stalePackTexture));
        assertFalse(Files.exists(staleCacheTexture));
        assertEquals(Set.of("textures/group/kept/skin.png"), keptTextures.get());
    }
}
