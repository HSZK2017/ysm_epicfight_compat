package com.ysmef.compat.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.YSMEpicFightCompat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent manifest of the generated mesh cache (config/ysm_epicfight_compat/
 * manifest.json), extracted from the former YSMMeshLibrary god class.
 *
 * Fully self-contained: owns its file path, the in-memory mirror (modelId ->
 * entry), the change version counter and the single background writer thread.
 * The render thread never touches the manifest file: entries are served from
 * the mirror (one disk read + parse on first access), and updates are merged
 * and persisted by the background writer (see {@link #update} /
 * {@link #scheduleWrite}).
 */
public final class ManifestStore {

    /** Bump when the generated mesh/runtime/descriptor formats change; entries from older generations are ignored. */
    public static final int GENERATOR_VERSION = 11;

    private static final Path MANIFEST =
            Paths.get("config", "ysm_epicfight_compat").resolve("manifest.json");

    /** modelId -> manifest entry (written by workers, read by the render thread). */
    private static final Map<String, JsonObject> MANIFEST_MODELS = new ConcurrentHashMap<>();

    /** Incremented on every entry change; the background writer re-runs while the version moved. */
    private static final java.util.concurrent.atomic.AtomicInteger MANIFEST_VERSION =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final Object MANIFEST_WRITE_LOCK = new Object();
    private static volatile boolean manifestWriteInFlight = false;

    /** Dedicated single writer: manifest persists never block the mesh pool or the render thread. */
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ysm-ef-manifest");
                thread.setDaemon(true);
                return thread;
            });

    private ManifestStore() {}

    /**
     * The manifest entry of one model, or null when the manifest is missing,
     * predates the current generator version, or has no entry for the model.
     * Served from the in-memory mirror (single disk read + parse on first
     * access); callers get a copy, so a worker's sig-refresh mutation of its
     * own entry can never race a render-thread read.
     */
    public static JsonObject entry(String modelId) {
        JsonObject cached = MANIFEST_MODELS.get(modelId);
        if (cached != null) {
            return cached.deepCopy();
        }
        try {
            if (!Files.isRegularFile(MANIFEST)) {
                return null;
            }
            JsonObject manifest = JsonParser.parseString(Files.readString(MANIFEST, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!manifest.has("generator") || manifest.get("generator").getAsInt() != GENERATOR_VERSION
                    || !manifest.has("models") || !manifest.get("models").isJsonObject()) {
                return null;
            }
            JsonObject modelEntry = manifest.getAsJsonObject("models").getAsJsonObject(modelId);
            if (modelEntry == null
                    || !modelEntry.has("sig") || !modelEntry.has("csig")
                    || !modelEntry.has("mesh") || !modelEntry.has("mhash") || !modelEntry.has("msize")
                    || !modelEntry.has("rhash") || !modelEntry.has("rsize")) {
                return null;
            }
            MANIFEST_MODELS.put(modelId, modelEntry);
            return modelEntry.deepCopy();
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the mirror currently holds an entry for the model (no disk access). */
    public static boolean contains(String modelId) {
        return MANIFEST_MODELS.containsKey(modelId);
    }

    /**
     * Merge one model's entry into the mirror and schedule a batched
     * background persist. Lock-free: the old implementation re-read and
     * rewrote the WHOLE manifest file under the class lock for every model
     * conversion (O(N^2) I/O that blocked the render thread's ensureModel).
     */
    public static void update(String modelId, JsonObject modelEntry) {
        MANIFEST_MODELS.put(modelId, modelEntry);
        MANIFEST_VERSION.incrementAndGet();
        scheduleWrite();
    }

    /**
     * Replace the whole manifest (full generation run) and persist it
     * synchronously: generateAll is a rare, explicit command, so the write is
     * immediate instead of coalesced.
     */
    public static void replaceAll(JsonObject models) {
        MANIFEST_MODELS.clear();
        for (Map.Entry<String, JsonElement> entry : models.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                MANIFEST_MODELS.put(entry.getKey(), entry.getValue().getAsJsonObject());
            }
        }
        MANIFEST_VERSION.incrementAndGet();
        writeSnapshot();
    }

    /**
     * Persist the mirror in the background, coalescing concurrent updates: one
     * writer serializes snapshots and re-runs while entries changed during its
     * write (see MANIFEST_VERSION). Never runs on the render thread and never
     * holds the mesh library's class lock.
     */
    private static void scheduleWrite() {
        if (manifestWriteInFlight) {
            return;
        }
        synchronized (MANIFEST_WRITE_LOCK) {
            if (manifestWriteInFlight) {
                return;
            }
            manifestWriteInFlight = true;
            WRITER.execute(() -> {
                try {
                    while (true) {
                        int version = MANIFEST_VERSION.get();
                        writeSnapshot();
                        if (MANIFEST_VERSION.get() == version) {
                            break;
                        }
                        // Entries changed while writing: persist again (coalesced).
                    }
                } catch (Throwable t) {
                    YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write generation manifest", t);
                } finally {
                    synchronized (MANIFEST_WRITE_LOCK) {
                        manifestWriteInFlight = false;
                    }
                }
            });
        }
    }

    private static void writeSnapshot() {
        JsonObject models = new JsonObject();
        for (Map.Entry<String, JsonObject> entry : MANIFEST_MODELS.entrySet()) {
            models.add(entry.getKey(), entry.getValue().deepCopy());
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("generator", GENERATOR_VERSION);
        manifest.add("models", models);
        try {
            Files.createDirectories(MANIFEST.getParent());
            EFMeshJsonWriter.writeFileAtomic(MANIFEST,
                    new com.google.gson.GsonBuilder().create().toJson(manifest).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write generation manifest", e);
        }
    }
}
