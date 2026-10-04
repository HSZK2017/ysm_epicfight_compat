package com.ysmef.compat.model;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        GeneratedModelCache cache = new GeneratedModelCache(meshDir, runtimeDir);

        assertTrue(cache.verifyModelOutputs(entry));
        Files.writeString(runtimeFile, "changed data", StandardCharsets.UTF_8);
        assertFalse(cache.verifyModelOutputs(entry), "same-size corruption must invalidate the cache");
        Files.write(runtimeFile, runtime);
        Files.writeString(meshFile, "short", StandardCharsets.UTF_8);
        assertFalse(cache.verifyModelOutputs(entry), "truncated mesh must invalidate the cache");
    }
}
