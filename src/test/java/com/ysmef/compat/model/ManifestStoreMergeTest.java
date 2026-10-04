package com.ysmef.compat.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest must not lose the entries a session never looked at.
 *
 * <p>The defect this pins is silent and cumulative. The mirror behind {@link ManifestStore} holds
 * only the models <i>this session</i> read or converted ({@link ManifestStore#entry} loads one at a
 * time), and the render thread only asks for the models it draws - so a session that converted a
 * single new model wrote a manifest containing that one model, and every other converted model's
 * entry was gone. The install's own manifest is the evidence: a library of converted models reduced
 * to the two entries a test session had touched. The next session then has no verified entry for the
 * others, so it re-converts all of them - decrypt, re-write a multi-megabyte mesh JSON and runtime
 * JSON, re-cache the textures - for a whole library because one model was added.
 *
 * <p>The test drives the real store against the real path it uses (relative to the JVM's working
 * directory, which for the Gradle test task is the project) and cleans up after itself: it plants a
 * manifest with three entries, reads one, converts a fourth, waits for the background writer, and
 * requires all four to be present. Before the fix the write carried only what the mirror held, and
 * the three planted entries were dropped. The assertion depends only on the file's contents, so it
 * does not care what else in the suite has touched the in-memory mirror.
 */
class ManifestStoreMergeTest {

    /** The path {@link ManifestStore} writes to, relative to the working directory. */
    private static final Path MANIFEST = Paths.get("config", "ysm_epicfight_compat", "manifest.json");

    private static final String NEW_MODEL = "manifest-merge-new.ysm";

    @Test
    @DisplayName("an update during the writer's final handoff is persisted")
    void finalWriterHandoffDoesNotDropAnUpdate() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(Paths.get("src", "main", "java")));
        java.lang.reflect.Field writeLockField = ManifestStore.class.getDeclaredField("MANIFEST_WRITE_LOCK");
        java.lang.reflect.Field inFlightField = ManifestStore.class.getDeclaredField("manifestWriteInFlight");
        writeLockField.setAccessible(true);
        inFlightField.setAccessible(true);
        Object writeLock = writeLockField.get(null);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while ((boolean) inFlightField.get(null) && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(!(boolean) inFlightField.get(null), "a previous manifest write did not finish");

        byte[] previous = Files.isRegularFile(MANIFEST) ? Files.readAllBytes(MANIFEST) : null;
        String first = "manifest-handoff-first.ysm";
        String last = "manifest-handoff-last.ysm";
        try {
            synchronized (writeLock) {
                ManifestStore.update(first, entryOf(first));
                // The writer can write its first snapshot but must wait at its
                // final state transition until this test releases the lock.
                deadline = System.nanoTime() + 10_000_000_000L;
                while (!manifestWriterBlocked() && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                assertTrue(manifestWriterBlocked(), "the manifest writer did not reach its final handoff");
                ManifestStore.update(last, entryOf(last));
            }
            JsonObject saved = awaitManifest(last);
            assertNotNull(saved);
            assertTrue(saved.getAsJsonObject("models").has(last),
                    "the update that arrived during the final handoff was not persisted");
        } finally {
            deadline = System.nanoTime() + 10_000_000_000L;
            while ((boolean) inFlightField.get(null) && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            if (previous == null) {
                Files.deleteIfExists(MANIFEST);
            } else {
                Files.write(MANIFEST, previous);
            }
            deleteIfEmpty(MANIFEST.getParent());
            deleteIfEmpty(MANIFEST.getParent() == null ? null : MANIFEST.getParent().getParent());
        }
    }

    private static boolean manifestWriterBlocked() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals("ysm-ef-manifest") && thread.getState() == Thread.State.BLOCKED) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a lazy conversion keeps the entries this session never read")
    void entriesTheSessionNeverReadSurviveALazyConversion() throws Exception {
        // Only meaningful where the store's own relative path resolves to this project; a test run
        // from elsewhere must not write into an unrelated tree.
        Assumptions.assumeTrue(Files.isDirectory(Paths.get("src", "main", "java")),
                "not running from the project directory");

        byte[] previous = Files.isRegularFile(MANIFEST) ? Files.readAllBytes(MANIFEST) : null;
        try {
            JsonObject seeded = new JsonObject();
            seeded.addProperty("generator", ManifestStore.CACHE_KEY);
            JsonObject models = new JsonObject();
            for (String model : new String[]{"manifest-merge-a.ysm", "manifest-merge-b.ysm", "manifest-merge-c.ysm"}) {
                models.add(model, entryOf(model));
            }
            seeded.add("models", models);
            Files.createDirectories(MANIFEST.getParent());
            Files.writeString(MANIFEST, seeded.toString(), StandardCharsets.UTF_8);

            // One model read (as the render thread reads the models it draws), one converted.
            ManifestStore.entry("manifest-merge-b.ysm");
            ManifestStore.update(NEW_MODEL, entryOf(NEW_MODEL));

            JsonObject onDisk = awaitManifest(NEW_MODEL);
            assertNotNull(onDisk, "the background writer never wrote the converted model: " + MANIFEST);
            assertTrue(ManifestStore.isCurrentGenerator(onDisk.get("generator"), ManifestStore.CACHE_KEY),
                    "the written manifest must carry this build's generator: " + onDisk);
            JsonObject kept = onDisk.getAsJsonObject("models");
            assertTrue(kept.has(NEW_MODEL), "the converted model must be in the manifest: " + onDisk);
            assertTrue(kept.has("manifest-merge-b.ysm"),
                    "the entry this session read must survive the write: " + onDisk);
            assertTrue(kept.has("manifest-merge-a.ysm"),
                    "an entry this session never touched must survive the write - dropping it makes the "
                            + "next session re-convert that model from scratch: " + onDisk);
            assertTrue(kept.has("manifest-merge-c.ysm"),
                    "an entry this session never touched must survive the write: " + onDisk);
        } finally {
            if (previous == null) {
                Files.deleteIfExists(MANIFEST);
            } else {
                Files.write(MANIFEST, previous);
            }
            deleteIfEmpty(MANIFEST.getParent());
            deleteIfEmpty(MANIFEST.getParent() == null ? null : MANIFEST.getParent().getParent());
        }
    }

    /**
     * A manifest entry with every field {@link ManifestStore#entry} validates. The values are never
     * used: this test is about the set of keys the file keeps, not about what they point at.
     */
    private static JsonObject entryOf(String modelId) {
        JsonObject entry = new JsonObject();
        entry.addProperty("sig", 1L);
        entry.addProperty("csig", 2L);
        entry.addProperty("mesh", modelId + ".mesh");
        entry.addProperty("mhash", "0".repeat(64));
        entry.addProperty("msize", 1024);
        entry.addProperty("rhash", "1".repeat(64));
        entry.addProperty("rsize", 512);
        entry.add("textures", new JsonObject());
        return entry;
    }

    /** Wait for the coalescing background writer to publish the entry, then return the whole file. */
    private static JsonObject awaitManifest(String modelId) throws InterruptedException {
        JsonObject last = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            try {
                JsonObject manifest = JsonParser.parseString(
                        Files.readString(MANIFEST, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonElement models = manifest.get("models");
                if (models != null && models.isJsonObject()) {
                    last = manifest;
                    if (models.getAsJsonObject().has(modelId)) {
                        return manifest;
                    }
                }
            } catch (Exception ignored) {
                // The writer replaces the file atomically, but a missing or half-copied read is not
                // worth failing over: poll again.
            }
            Thread.sleep(50L);
        }
        return last;
    }

    private static void deleteIfEmpty(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            if (Files.isDirectory(directory)) {
                try (var entries = Files.list(directory)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        } catch (Exception ignored) {
            // Leaving an empty directory behind is not a test failure.
        }
    }
}
