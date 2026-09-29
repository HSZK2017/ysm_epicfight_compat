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

    /**
     * Hand-maintained half of the generation key; entries from other generations are ignored.
     *
     * <p><b>No longer the whole key - read {@link #MANUAL_VERSION} before bumping this.</b> Since
     * the writer-fingerprint mechanism, the key that manifest.json stores and this class compares is
     * {@link #CACHE_KEY} = {@code "<MANUAL_VERSION>:<writer fingerprint>"}, where the fingerprint is
     * derived at build time from the content of every source file under {@code
     * com/ysmef/compat/model/}. So {@link EFMeshJsonWriter} and the rest of the conversion writers
     * are covered automatically and no longer need (or get) a bump: edit them, rebuild, and the key
     * moves by construction. Bump {@link #MANUAL_VERSION} only for a change outside that directory
     * that alters what a cached artefact means (a mixin, a config default, a shader) - and note that
     * this is exactly the mistake the fingerprint exists to absorb, because it does not fail loudly:
     * the already-converted models keep their old artefacts and the change silently does nothing for
     * precisely the models a user has been testing. That cost this feature several rounds, including
     * the {@code physics} and {@code scale} sections of the runtime JSON and the geometry-driven
     * joint binding below - and, most recently, the baked-joint-id rewrite, which shipped with this
     * constant unmoved and was reused from the stale cache exactly as described.
     *
     * <p>The entries below are the history of this constant while it was the whole key; they are kept
     * as the record of what a generation number means.
     *
     * <p>13 -&gt; 14 for the physics parts rewrite. The converter now finds the bones a model
     * author wired to physics by reading the animation controllers - the model's own, plus YSM's
     * bundled default controller set - and accepts a candidate animation only when it drives a
     * bone the model has geometry for, recording the result in the runtime JSON's
     * {@code "physics"} section. Every artifact cached at generation 13 has no such section (a
     * sweep of the 83 runtime models on the development machine found {@code "physics"} zero
     * times) and no way to acquire one, so without this bump a user who installs the new build
     * keeps playing the old, un-simulated classification and sees no change at all - the exact
     * silent no-op this constant exists to prevent.
     *
     * <p>14 -&gt; 15 for the skin joint of cloth, hair and tails. The mesh writer baked each
     * vertex's Epic Fight joint with the name walk alone while the runtime bone table written next
     * to it used the name walk plus the garment rule ("cloth at or below the hips belongs to the
     * Torso"), so on the shipped maid 56 bones - 1800 of its 11283 vertices, 49 of them simulated
     * segments - were <i>drawn</i> on the Chest joint and <i>simulated</i> against the Torso.
     * {@link EFMeshJsonWriter#bakedJointId} now resolves both sides through the same overload. The
     * mesh has to be rebuilt for that to be visible: a cached mesh whose bytes still hash to the
     * manifest entry is trusted without re-running the writer, so without this bump the fix is a
     * no-op for exactly the models that were tested, and the report it answers is a report about
     * one of them.
     *
     * <p>15 -&gt; 16 for the geometry-driven limb side (and the hip-relative garment rule). The rules
     * that decide a bone's joint changed again after the previous bump, and this constant was not
     * bumped with them, so the models that were tested kept generation-15 artefacts: their per-vertex
     * joints and their runtime bone table still came from the older rules while the runtime that read
     * them was new. Measured on the install's own converted pack (the pair the game loaded), the two
     * shoes of {@code 兽耳酱x1.ysm} - 66 quads each, geometry on the left and right foot respectively -
     * were bound to the <i>opposite</i> leg's shin joint in the cached mesh and to the correct one
     * under the current rules, which is exactly the reported "the shoes are not on the feet"; the
     * cached Nahobino mesh mis-sided 46 parts / 53028 vertices. Bumping this regenerates both the
     * mesh JSON and the runtime JSON of every model, so the new rules reach the game at all.
     */
    public static final int GENERATOR_VERSION = 16;

    /**
     * The hand-maintained half of the cache key, for changes outside the fingerprinted set.
     *
     * <p>Bump when a cached artefact's <i>meaning</i> changes through something the fingerprint
     * cannot see: a mixin, a config default, a shader, or any source file under
     * {@code src/main/java} but outside {@code com/ysmef/compat/model/}. This is still a manual act
     * with the failure mode it always had (forgetting it turns a fix into a silent no-op) - which is
     * exactly why everything under {@code model/}, the code that produces the cached bytes, is
     * covered by {@link #WRITER_FINGERPRINT} instead, where the bump cannot be forgotten.
     */
    public static final int MANUAL_VERSION = GENERATOR_VERSION;

    /**
     * Classpath resource holding the fingerprint of the conversion sources, generated at build time
     * by the {@code generateWriterFingerprint} Gradle task. See the comment block above that task in
     * {@code build.gradle} for the fingerprinted set and for why it over-approximates on purpose.
     */
    private static final String FINGERPRINT_RESOURCE = "ysm_epicfight_compat/writer_fingerprint.txt";

    /** Distinct token for "the build metadata is not on the classpath"; never a valid fingerprint. */
    private static final String FINGERPRINT_UNAVAILABLE = "unavailable";

    /**
     * The fingerprint as read at class-init, or {@link #FINGERPRINT_UNAVAILABLE}.
     *
     * <p>Resolved once, through this class's own classloader: it is a property of the deployed
     * build, so a per-call read would only add I/O and a way for two reads to disagree.
     */
    private static final String WRITER_FINGERPRINT = loadWriterFingerprint();

    /**
     * The effective cache key stored in and compared against manifest.json's {@code generator}
     * field: {@code "<MANUAL_VERSION>:<writer fingerprint>"}, e.g. {@code "16:2f35209c39f37430"}.
     *
     * <p>Read it as "the converter that produced this manifest". A hand-bumped int stops matching
     * the code the moment someone edits a writer and forgets the bump - which happened, and cost a
     * full debugging round; the fingerprint half cannot be forgotten, because it is derived from the
     * content of the writer sources themselves.
     *
     * <p>Manifests written before this mechanism carry a bare number ({@code 15}), one written by a
     * build with different sources carries a different fingerprint, and both are simply "not
     * current": the model is re-converted (see {@link #isCurrentGenerator}). The one asymmetric case
     * is an <i>older</i> build reading a <i>newer</i> manifest - it compares its own int against a
     * string, rejects it, and falls into the existing "manifest not current -&gt; treat as empty"
     * path, re-converting every model once. That is the safe direction (one wasted conversion, no
     * stale artefact) and the only one available to code that predates this format.
     *
     * <p>Maintainer's pointer: to add a file to the fingerprinted set, edit the single input
     * declaration on the {@code generateWriterFingerprint} task - never this class. Adding a source
     * file under {@code model/} needs no action at all; changing anything <i>outside</i> {@code
     * model/} that alters a cached artefact's meaning needs {@link #MANUAL_VERSION} bumped here.
     */
    public static final String CACHE_KEY = MANUAL_VERSION + ":" + WRITER_FINGERPRINT;

    static {
        // State the effective key once, loudly enough to answer "did the build I am running have the
        // fix?" from a log. The bug this replaces was invisible for a whole round precisely because
        // nothing in the session said which converter generation the cache was being compared with.
        // The degraded path (metadata missing) is a WARN, not a throw: a build whose resource was not
        // packaged must still convert models - it just cannot promise the cache is current.
        if (FINGERPRINT_UNAVAILABLE.equals(WRITER_FINGERPRINT)) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: build-time writer fingerprint ({} on the classpath) is unreadable; "
                            + "the mesh cache key falls back to the manual version {} alone, so cached "
                            + "artefacts converted after a writer change can be reused. Delete "
                            + "config/ysm_epicfight_compat/manifest.json to force a clean re-conversion.",
                    FINGERPRINT_RESOURCE, MANUAL_VERSION);
        } else {
            YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: mesh cache generator {}", CACHE_KEY);
        }
    }

    /**
     * The fingerprint of the conversion/mesh sources this build was compiled from, or
     * {@link #FINGERPRINT_UNAVAILABLE}. Package-private for the tests that pin the cache key to the
     * packaged resource.
     */
    static String writerFingerprint() {
        return WRITER_FINGERPRINT;
    }

    /** Reads {@link #FINGERPRINT_RESOURCE} and reduces it to the fingerprint token. */
    private static String loadWriterFingerprint() {
        java.io.InputStream in = ManifestStore.class.getClassLoader().getResourceAsStream(FINGERPRINT_RESOURCE);
        if (in == null) {
            return FINGERPRINT_UNAVAILABLE;
        }
        try (java.io.InputStream stream = in) {
            return parseFingerprint(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception | LinkageError e) {
            // LinkageError too: this runs in a static initializer, where an unexpected throwable
            // becomes a class-init failure, and a class that cannot initialize is worse than a
            // generation check that degrades to its pre-fingerprint behaviour.
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: writer fingerprint unreadable: {}", e.toString());
            return FINGERPRINT_UNAVAILABLE;
        }
    }

    /**
     * The token out of the generated resource: the first line that is neither blank nor a
     * {@code #} comment. Package-private and pure so the reader is testable without a classloader.
     */
    static String parseFingerprint(String content) {
        if (content == null) {
            return FINGERPRINT_UNAVAILABLE;
        }
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return trimmed;
            }
        }
        return FINGERPRINT_UNAVAILABLE;
    }

    /**
     * Whether a manifest's {@code generator} value names the converter that is running now.
     *
     * <p>Pure and total by contract: the value comes from a file this build may not have written -
     * an older build's bare number, a newer build's string, or anything at all if the file was edited
     * or truncated - and the unit under test is "does this mean regenerate?", never an exception.
     * Hence no {@code getAsInt()}: that throws on the string form this mechanism itself writes. The
     * comparison is on the string form, so a numeric {@code 15} stringifies to {@code "15"} and
     * simply does not equal {@code "16:2f35209c39f37430"} -&gt; not current -&gt; re-convert.
     *
     * <p>The {@code isJsonPrimitive()} guard is what makes "never throws" true rather than hopeful:
     * an object, an array and a JSON null all have no string form at all ({@code getAsString()} on
     * them raises {@code UnsupportedOperationException} / {@code IllegalStateException}), so for the
     * cache check the question "is this the current generator?" is answered false without going near
     * the accessor. The try/catch below stays as the backstop for a {@code JsonPrimitive} whose
     * content cannot be read as the expected shape.
     */
    static boolean isCurrentGenerator(JsonElement generator, String current) {
        if (generator == null || generator.isJsonNull() || current == null) {
            return false;
        }
        if (!generator.isJsonPrimitive()) {
            // Objects, arrays: not a generation stamp at all. Answered here rather than by catching.
            return false;
        }
        try {
            return current.equals(generator.getAsString());
        } catch (RuntimeException e) {
            return false;
        }
    }

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
            if (!manifest.has("generator") || !isCurrentGenerator(manifest.get("generator"), CACHE_KEY)
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
            // A corrupt manifest and a missing one both mean "no verified entry", so the answer stays
            // null - but they are not the same event, and folding them together made a persistently
            // unreadable manifest indistinguishable from a plain cache miss: every model re-converted,
            // for the rest of the session, with nothing in the log to say why.
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.debug(
                    "YSM-EF Compat: conversion manifest unreadable, treating it as empty: {}", e.toString());
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
     *
     * <p>This one write is a <b>full rewrite</b> - the caller has just produced one entry per model it
     * converted - so it does not carry the file's other entries forward. See
     * {@link #scheduleWrite} for the lazy path, which must.
     */
    public static void replaceAll(JsonObject models) {
        MANIFEST_MODELS.clear();
        for (Map.Entry<String, JsonElement> entry : models.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                MANIFEST_MODELS.put(entry.getKey(), entry.getValue().getAsJsonObject());
            }
        }
        MANIFEST_VERSION.incrementAndGet();
        writeSnapshot(false);
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
                        writeSnapshot(true);
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

    /**
     * Serializes the two writers (the coalescing background thread and {@link #replaceAll}'s
     * synchronous one) against each other. The merge below is a read-modify-write of the manifest
     * file, so two of them interleaving would lose one side's entries.
     */
    private static final Object MANIFEST_SNAPSHOT_LOCK = new Object();

    /**
     * Write the mirror out as the manifest.
     *
     * <p><b>{@code carryUntouchedEntries} is the fix for a silent cache loss</b>, and it is on every
     * lazy write. The mirror holds only the models <i>this session</i> has read or converted:
     * {@link #entry} loads one entry at a time, and the render thread only ever asks for the models
     * it is drawing - so a session that converts a single new model wrote a manifest containing that
     * one model and nothing else, and every other model's cache entry was gone. The next session then
     * re-verified nothing and re-converted all of them: decrypt, re-write the mesh JSON and the
     * runtime JSON, re-cache the textures, for a whole library, because one model was added. The
     * installed manifest's own history shows it - a library of converted models reduced to the two
     * entries a test session had touched.
     *
     * <p>So a lazy write merges the entries already on disk (when the file was written by this same
     * generator - a stale file's entries are not resurrected) under the mirror, which wins on any key
     * it holds. The full generation run does not merge: it has just produced every entry it wants.
     */
    private static void writeSnapshot(boolean carryUntouchedEntries) {
        synchronized (MANIFEST_SNAPSHOT_LOCK) {
            JsonObject models = new JsonObject();
            if (carryUntouchedEntries) {
                for (Map.Entry<String, JsonElement> entry : onDiskEntries().entrySet()) {
                    models.add(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, JsonObject> entry : MANIFEST_MODELS.entrySet()) {
                models.add(entry.getKey(), entry.getValue().deepCopy());
            }
            JsonObject manifest = new JsonObject();
            manifest.addProperty("generator", CACHE_KEY);
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

    /**
     * The model entries of the manifest already on disk, or an empty object when there is none or it
     * was written by a different generator (in which case its entries are not this build's to keep).
     *
     * <p>Read inside the snapshot lock, by the writer thread or by {@code generateAll}: one extra
     * read of a small JSON file per coalesced write, which is what the cached entries are worth.
     */
    private static JsonObject onDiskEntries() {
        try {
            if (!Files.isRegularFile(MANIFEST)) {
                return new JsonObject();
            }
            JsonObject manifest = JsonParser.parseString(Files.readString(MANIFEST, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!isCurrentGenerator(manifest.get("generator"), CACHE_KEY)) {
                return new JsonObject();
            }
            JsonElement models = manifest.get("models");
            return models != null && models.isJsonObject() ? models.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            // An unreadable file is treated as empty, exactly as entry() treats it: the write must
            // still happen, and the entries it cannot read are the ones it cannot keep.
            return new JsonObject();
        }
    }
}
