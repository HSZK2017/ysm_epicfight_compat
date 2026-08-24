package com.ysmef.compat.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.model.runtime.YSMRuntimeModel;
import com.ysmef.compat.ysm.YsmModelPackage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.client.model.Meshes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Central registry of generated Epic Fight base meshes for YSM models.
 *
 * Models are converted lazily (OpenYSM-style): nothing is converted at startup.
 * The first mesh lookup for a model (see ensureModel / findMesh) either restores
 * the model's previous conversion results from the per-model manifest + texture
 * cache (verified by content hashes), or submits the conversion of just that
 * model to a background pool. Until the conversion finishes the mesh selector
 * falls back to Epic Fight's default biped mesh.
 *
 * The generated resource pack folder (config/ysm_epicfight_compat/resourcepack,
 * registered as a client resource pack, see YSMCompatClientEvents) is a live
 * PathPackResources: mesh JSONs written after the pack repository was built are
 * picked up by Epic Fight's on-demand mesh loader without a resource reload.
 *
 * Per-model integrity (manifest.json):
 * - two-tiered source fingerprints (cheap metadata sig, confirmed against the
 *   content sig on mismatch) detect model file changes, including "/ysm model
 *   reload"; a metadata-only rewrite (mtime/encryption refresh) updates the sig
 *   in place without re-converting
 * - every converted output (mesh JSON, runtime JSON, cached texture bytes) is
 *   hashed into the manifest at generation time and re-verified on disk before
 *   the cache is trusted, so a broken/stale cache forces a re-conversion
 *   instead of being trusted forever (the cause of permanently missing faces)
 *
 * Textures of the model packages are registered in the texture manager under
 * our own resource locations so Epic Fight can render the mesh with the YSM
 * model's texture regardless of the (obfuscated) YSM texture registry.
 */
public class YSMMeshLibrary {

    private static final Path CONFIG_ROOT = Paths.get("config", "ysm_epicfight_compat");
    /** Mesh JSON output dir inside the generated pack (pack root lives in TextureStore). */
    private static final Path MESH_DIR = TextureStore.PACK_ROOT.resolve("assets").resolve(YSMEpicFightCompat.MODID).resolve("animmodels").resolve("entity");
    /** Runtime script JSON output dir inside the generated pack. */
    private static final Path RUNTIME_DIR = TextureStore.PACK_ROOT.resolve("assets").resolve(YSMEpicFightCompat.MODID).resolve("ysm_runtime").resolve("entity");

    /**
     * Bumped whenever the conversion algorithm or the manifest format changes
     * in a way that invalidates previously generated meshes (forces a one-time
     * full regeneration).
     *
     * 5: binary animation length is now converted ticks -> seconds (YsmBinaryReader),
     *    which changes the runtime JSON output of converted models.
     * 6: the runtime JSON gains the optional "camera" section (RealCamera bind
     *    target UVs, see YsmCameraTargetSolver).
     * 7: the camera solver restricts the front-face candidates to the eyes
     *    bone's ancestor chain (variant head subtrees are collapsed in the
     *    default form), which changes the "camera" UVs of affected models.
     * 8: the upward side face is flipped to the opposite side of the head
     *    (the right-side choice rendered the view upside-down), and the
     *    "camera" section gains the bind-space eyes position + face normals
     *    (consumed by the RealCamera API bind function for non-battle mode).
     * 9: the "camera" UVs are nudged off UV regions shared with
     *    differently-facing quads of other parts (RealCamera's probe resolves
     *    a UV to the first captured triangle containing it, so an overlapped
     *    point could bind to a hair quad instead of the head's side face).
     * 10: the camera solver's front-face pick is restricted to quads near the
     *    head box (accessories parented under the eyes bone, e.g. a magic
     *    circle blocks ahead, no longer win the area contest), and the
     *    reported eyes position is scaled by the model package's width/height
     *    scales (matching the converted mesh).
     * 11: the joint mapper's name normalization strips YSM's "_Default"
     *    default-form suffix (e.g. the momo wine fox's "RightArm_Default"),
     *    so default-form bones are marked directly-mapped in the runtime JSON
     *    and drive their joint (previously unmapped decorations).
     */
    /**
     * modelId -> registered mesh accessor. Read lock-free from the render
     * thread (findMesh) while worker threads register conversion results, so
     * it must be concurrent.
     */
    private static final Map<String, Meshes.MeshAccessor<YSMMesh>> MESHES = new ConcurrentHashMap<>();

    /** Models whose lazy conversion already failed; not retried until invalidateAll. */
    private static final Set<String> FAILED_MODELS = ConcurrentHashMap.newKeySet();

    /** Models currently being converted on the background pool. */
    private static final Set<String> PENDING_MODELS = ConcurrentHashMap.newKeySet();

    /**
     * EF accessor registrations queued by worker threads and executed on the
     * render thread: Epic Fight's {@code Meshes.ACCESSORS} is a plain HashMap
     * (Meshes.java:40), so {@code MeshAccessor.create} (which writes it) must
     * never run off the render thread - a concurrent put can corrupt the table
     * while EF's own render-thread code reads it. Workers therefore construct
     * nothing and enqueue (modelId, meshId, generation); the render thread
     * drains the queue in {@link #ensureModel} and registers the accessors.
     */
    private record PendingMeshRegistration(String modelId, String meshId, int generation) {}

    private static final java.util.Queue<PendingMeshRegistration> PENDING_MESH_REGISTRATIONS =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Models whose mesh JSON was just registered but not yet instantiated
     * (accessor.get() = JSON parse + SkinnedMesh build + compute setup, the
     * first-draw hitch). Drained by {@link #prewarmMeshes()} on the client
     * tick with a small time budget per tick, so the first time the player
     * actually SEES the model, its mesh already exists.
     */
    private static final Set<String> PENDING_PREWARM = ConcurrentHashMap.newKeySet();
    private static final long PREWARM_BUDGET_NANOS = 8_000_000L;

    /**
     * ModernYSM-style LRU usage tracking (access order): every successful mesh
     * lookup touches its model; when the loaded model count exceeds the config
     * cap, the least-recently-used models are evicted (GPU buffers + textures +
     * compiled scripts released) and re-registered from the verified on-disk
     * cache on next use.
     */
    private static final java.util.LinkedHashMap<String, Boolean> ACCESS_ORDER = new java.util.LinkedHashMap<>(64, 0.75f, true);

    /** Models whose mesh was actually instantiated (accessor.get() succeeded), i.e. own GL resources. */
    private static final Set<String> LOADED_MODELS = ConcurrentHashMap.newKeySet();

    /**
     * Invalidated by {@link #invalidateAll()}: in-flight lazy conversions of the
     * previous generation must not register their (possibly stale) results after
     * a model reload - the on-disk outputs stay valid and are re-verified by the
     * next lookup, but the in-memory registration is dropped.
     */
    private static final java.util.concurrent.atomic.AtomicInteger LOAD_GENERATION = new java.util.concurrent.atomic.AtomicInteger();

    /** textureRL string -> true when the texture has translucent pixels (alpha < 253). */
    private static final Map<String, Boolean> TEXTURE_TRANSLUCENT = new ConcurrentHashMap<>();

    /** Background conversion pool (model decryption + mesh writing are pure CPU work). */
    private static final ExecutorService LAZY_POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), runnable -> {
                Thread thread = new Thread(runnable, "ysm-ef-lazy");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Render-path resource releasers (GPU/CPU/Iris skinning), registered by the
     * implementing classes' static initializers. Using the interface keeps this
     * class (and TextureStore) free of gpu/cpu package imports - the model
     * &lt;-&gt; gpu/cpu package cycle is broken at the resource-release edge.
     */
    private static final java.util.List<MeshReleaser> RELEASERS = new ArrayList<>();

    public static synchronized void registerMeshReleaser(MeshReleaser releaser) {
        if (!RELEASERS.contains(releaser)) {
            RELEASERS.add(releaser);
        }
    }

    /** Release one mesh across every registered render path (render thread). */
    static void releaseMeshAcrossPaths(YSMMesh mesh) {
        for (MeshReleaser releaser : RELEASERS) {
            try {
                releaser.disposeMesh(mesh);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Release every mesh resource across every registered render path (render thread). */
    private static void disposeAllPaths() {
        for (MeshReleaser releaser : RELEASERS) {
            try {
                releaser.disposeAll();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * ModernYSM-style peak-memory control (preparedModelSlots): at most this
     * many model conversions run at once - each conversion holds the decrypted
     * package plus the parsed geometry/mesh arrays in memory, so converting
     * several large models simultaneously could spike RAM by hundreds of MB.
     * Further conversions queue on the pool instead of running concurrently.
     */
    private static final java.util.concurrent.Semaphore CONVERSION_SLOTS = new java.util.concurrent.Semaphore(2);

    /** Per-model conversion result produced by worker threads. */
    private record TextureEntry(String textureName, ResourceLocation location, byte[] data, int[] info,
                                String hash, long size) {}

    private record ModelResult(String modelId, String meshId, int quads, long fingerprint,
                               long contentFingerprint, String defaultTextureRL,
                               String meshHash, long meshSize, String runtimeHash, long runtimeSize,
                               List<TextureEntry> textures) {}

    /**
     * The resource pack root that should be registered as a client resource pack.
     */
    public static Path getPackRoot() {
        return TextureStore.getPackRoot();
    }

    /**
     * Directory inside the generated pack where Epic Fight animmodels mesh JSONs
     * live (assets/<modid>/animmodels/entity).
     */
    public static Path getMeshDir() {
        return MESH_DIR;
    }

    /**
     * Register raw texture bytes (PNG/JPEG or raw RGBA with accompanying info)
     * under our own resource location, ready for on-demand upload to the texture
     * manager (see ensureTextureUploaded).
     *
     * @param relativePath path inside our textures/ space (without .png)
     * @param data         encoded image bytes
     * @return the resource location the bytes were registered under
     */
    public static ResourceLocation registerTextureBytes(String relativePath, byte[] data) {
        return TextureStore.registerTextureBytes(relativePath, data);
    }

    /**
     * The runtime script JSON (bone table + molang animations) generated for the
     * given mesh id, evaluated by YSMRuntimeModel at render time.
     */
    public static Path getRuntimeFile(String meshId) {
        return RUNTIME_DIR.resolve(meshId + ".json");
    }

    /** The directory holding every converted model's runtime JSON (recursive: per-namespace subdirs). */
    public static Path getRuntimeEntityDir() {
        return RUNTIME_DIR;
    }

    /** modelId -> meshId (sanitized), for runtime lookup. */
    public static String meshIdOf(String modelId) {
        return TextureStore.sanitize(modelId);
    }

    /**
     * The shared path prefix of every generated texture of a model
     * ("textures/<model>/"). RealCamera bind targets use it as the textureId
     * matcher: it matches all texture variants of the model (the UV layout is
     * per-model, the captured texture id is "...:textures/<model>/<tex>.png").
     */
    public static String textureIdPrefixOf(String modelId) {
        return TextureStore.textureIdPrefixOf(modelId);
    }

    /**
     * Ensure the generated resource pack skeleton exists (called before the pack
     * repository is built).
     */
    public static void preparePackFolder() {
        TextureStore.preparePackFolder();
        try {
            Files.createDirectories(MESH_DIR);
            Files.createDirectories(RUNTIME_DIR);
        } catch (IOException e) {
            YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: failed to prepare generated pack folder", e);
        }
    }

    /**
     * Ensure the converted mesh for one YSM model is generated and registered.
     * Called lazily from the mesh selection path on the render thread:
     *
     * - already registered -> true
     * - previously failed / currently converting or restoring -> false
     *   (Epic Fight biped fallback)
     * - manifest entry present -> the on-disk cache is verified and the model
     *   restored on the background pool; false until it is registered
     * - missing or outdated -> the conversion is submitted to the background
     *   pool and false is returned; the mesh becomes available from the next
     *   frame on
     *
     * Returns true when the mesh accessor is available for {@link #findMesh}.
     *
     * Caching is per model (OpenYSM-style lazy loading - nothing is converted at
     * startup): the manifest entry carries two-tiered source fingerprints (a
     * cheap metadata sig guarding the common case, confirmed against the content
     * sig on mismatch, so a metadata-only rewrite like YSM's re-encryption
     * refresh updates the sig in place without re-converting) plus the output
     * integrity hashes (mhash/rhash per model, hash per texture) verified on
     * disk before the cache is trusted.
     */
    public static synchronized boolean ensureModel(String modelId) {
        // Register conversions finished on worker threads. Must run on the
        // render thread (EF's ACCESSORS HashMap), and inside the class lock so
        // the state transitions (PENDING_MODELS -> registered) are atomic for
        // the check below.
        drainPendingMeshRegistrations();
        if (MESHES.containsKey(modelId)) {
            touch(modelId);
            return true;
        }
        if (FAILED_MODELS.contains(modelId) || PENDING_MODELS.contains(modelId)) {
            return false;
        }

        JsonObject modelEntry = ManifestStore.entry(modelId);
        if (modelEntry != null) {
            // Verify + restore from the on-disk cache on the background pool:
            // hashing the mesh/runtime/texture outputs of a large model on the
            // render thread caused a first-draw hitch of up to a second. Until
            // the worker registers the accessor the caller falls back to the
            // Epic Fight biped (same as the conversion path).
            PENDING_MODELS.add(modelId);
            LAZY_POOL.submit(() -> restoreModelAsync(modelId, modelEntry));
            return false;
        }

        if (!YsmModelPackage.scanAvailableModels().containsKey(modelId)) {
            FAILED_MODELS.add(modelId);
            return false;
        }

        PENDING_MODELS.add(modelId);
        LAZY_POOL.submit(() -> convertModelAsync(modelId));
        return false;
    }

    /**
     * Worker: verify the on-disk cache outputs of one model against the
     * manifest and register the mesh from the verified cache (no decryption
     * involved). Falls back to a full conversion when the cache does not
     * verify (stale, broken or partially removed outputs).
     */
    private static void restoreModelAsync(String modelId, JsonObject modelEntry) {
        int generation = LOAD_GENERATION.get();
        boolean verified;
        try {
            verified = fingerprintMatches(modelId, modelEntry) && verifyModelOutputs(modelEntry);
        } catch (Throwable t) {
            verified = false;
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: cache verification failed for '{}'", modelId, t);
        }
        // File I/O stays outside the class lock: reading the texture cache (and
        // re-writing missing pack textures) can take tens of ms for large
        // models, and the render thread takes the same lock in ensureModel.
        Map<String, byte[]> textureBytes = new HashMap<>();
        if (verified && modelEntry.has("textures") && modelEntry.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> texEntry : modelEntry.getAsJsonObject("textures").entrySet()) {
                try {
                    JsonObject tex = texEntry.getValue().getAsJsonObject();
                    ResourceLocation rl = ResourceLocation.parse(tex.get("rl").getAsString());
                    byte[] bytes = TextureStore.readTextureCache(rl);
                    if (bytes != null) {
                        textureBytes.put(texEntry.getKey(), bytes);
                        int format = tex.has("fmt") ? tex.get("fmt").getAsInt() : 0;
                        // WebP/AVIF pack textures written by older builds may be
                        // blank transparent PNGs (decoded as raw RGBA), so they
                        // are rewritten on every cache restore.
                        TextureStore.writePackTexture(rl, bytes, format != 0
                                ? new int[]{tex.has("w") ? tex.get("w").getAsInt() : 0,
                                tex.has("h") ? tex.get("h").getAsInt() : 0, format} : null,
                                format == 4 || format == 5 || TextureStore.isRiffWebp(bytes) || TextureStore.isFtypAvif(bytes));
                    }
                } catch (Exception ignored) {
                    // a missing/broken cached texture makes the registration skip
                    // just that texture; the model itself still registers
                }
            }
        }
        synchronized (YSMMeshLibrary.class) {
            if (generation != LOAD_GENERATION.get()) {
                PENDING_MODELS.remove(modelId);
                return;
            }
            if (verified && registerFromCache(modelId, modelEntry, textureBytes)) {
                // The accessor registration is queued (EF's ACCESSORS HashMap
                // must only be written on the render thread); MESHES.put,
                // touch/trim and PENDING_MODELS.remove happen in
                // drainPendingMeshRegistrations on the next ensureModel.
                YsmExtraAnimationLibrary.ensureConvertedAsync(modelId);
                return;
            }
            PENDING_MODELS.remove(modelId);
        }
        // Cache unusable: re-convert (queued like the lazy path).
        if (YsmModelPackage.scanAvailableModels().containsKey(modelId)) {
            PENDING_MODELS.add(modelId);
            LAZY_POOL.submit(() -> convertModelAsync(modelId));
        } else {
            FAILED_MODELS.add(modelId);
        }
    }

    /**
     * Render-thread only: register accessors queued by worker threads. Epic
     * Fight's {@code Meshes.ACCESSORS} is a plain HashMap, so
     * {@code MeshAccessor.create} (which writes it) must run on the same thread
     * that reads it (the render thread). Stale registrations from a previous
     * load generation are dropped.
     *
     * Called from {@link #ensureModel} (render thread, class lock held); the
     * worker side keeps PENDING_MODELS set until this method registers the
     * model, so no duplicate conversion can be scheduled in between.
     */
    private static void drainPendingMeshRegistrations() {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        PendingMeshRegistration registration;
        while ((registration = PENDING_MESH_REGISTRATIONS.poll()) != null) {
            if (registration.generation() != LOAD_GENERATION.get()) {
                // stale result of a previous load generation: drop, never register
                PENDING_MODELS.remove(registration.modelId());
                continue;
            }
            try {
                Meshes.MeshAccessor<YSMMesh> accessor = Meshes.MeshAccessor.create(
                        YSMEpicFightCompat.MODID, "entity/" + registration.meshId(),
                        (loader) -> loader.loadSkinnedMesh(YSMMesh::new));
                MESHES.put(registration.modelId(), accessor);
                YSMRuntimeModel.invalidate(registration.modelId());
                touch(registration.modelId());
                // Schedule the mesh instantiation for a later client tick (see
                // prewarmMeshes) so the first actual draw does not pay the
                // JSON-parse + mesh-build cost on the render thread.
                PENDING_PREWARM.add(registration.modelId());
            } catch (Throwable t) {
                FAILED_MODELS.add(registration.modelId());
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to register converted mesh for '{}'", registration.modelId(), t);
            } finally {
                PENDING_MODELS.remove(registration.modelId());
            }
        }
        trimIfNeeded();
    }

    /** Worker: convert one model off-thread and merge the result under the lock. */
    private static void convertModelAsync(String modelId) {
        int generation = LOAD_GENERATION.get();
        try {
            CONVERSION_SLOTS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (YSMMeshLibrary.class) {
                PENDING_MODELS.remove(modelId);
            }
            return;
        }
        try {
            ModelResult result = convertModel(modelId);
            synchronized (YSMMeshLibrary.class) {
                if (generation != LOAD_GENERATION.get()) {
                    // the caches were invalidated (model reload) while converting:
                    // the on-disk outputs are still valid for the next lookup, but
                    // the in-memory registration of this (possibly stale) result is dropped
                    PENDING_MODELS.remove(modelId);
                    return;
                }
                if (result != null) {
                    // Registration is queued (see PendingMeshRegistration); the
                    // accessor + MESHES.put + PENDING_MODELS.remove are done by
                    // drainPendingMeshRegistrations on the render thread.
                    registerModelResult(result);
                } else {
                    FAILED_MODELS.add(modelId);
                    PENDING_MODELS.remove(modelId);
                }
            }
            if (result != null) {
                // compile the freshly written runtime JSON on this worker thread, so
                // the first draw finds the compiled scripts instead of compiling
                // (potentially ~100ms for big models) on the render thread
                YSMRuntimeModel.preload(modelId);
            }
        } catch (Throwable t) {
            synchronized (YSMMeshLibrary.class) {
                if (generation == LOAD_GENERATION.get()) {
                    FAILED_MODELS.add(modelId);
                }
                PENDING_MODELS.remove(modelId);
            }
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: lazy mesh conversion failed for '{}'", modelId, t);
        } finally {
            CONVERSION_SLOTS.release();
        }
    }

    /**
     * Record a model usage (LRU touch). The access-ordered map makes the next
     * {@link #trimIfNeeded()} evict the least-recently-used models first.
     */
    private static void touch(String modelId) {
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.put(modelId, Boolean.TRUE);
        }
    }

    /**
     * ModernYSM-style LRU eviction: when more models are loaded than the config
     * cap, release the least-recently-used ones (GPU buffers, textures, compiled
     * scripts). The next lookup re-registers them from the verified on-disk
     * cache without re-converting. GL/texture calls require the render thread,
     * so eviction is skipped when called from a worker (the next render-thread
     * lookup trims).
     */
    private static void trimIfNeeded() {
        int cap = YSMCompatConfig.LAZY_MODEL_CACHE_SIZE.get();
        java.util.Iterator<String> victimIterator;
        synchronized (ACCESS_ORDER) {
            if (ACCESS_ORDER.size() <= cap) {
                return;
            }
            victimIterator = new java.util.ArrayList<>(ACCESS_ORDER.keySet()).iterator();
        }
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        synchronized (YSMMeshLibrary.class) {
            int evicted = 0;
            while (true) {
                String victim;
                synchronized (ACCESS_ORDER) {
                    if (ACCESS_ORDER.size() <= cap || !victimIterator.hasNext()) {
                        break;
                    }
                    victim = victimIterator.next();
                    ACCESS_ORDER.remove(victim);
                }
                if (PENDING_MODELS.contains(victim) || FAILED_MODELS.contains(victim)) {
                    continue;
                }
                if (evictModel(victim)) {
                    evicted++;
                }
            }
            if (evicted > 0) {
                YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: evicted {} LRU models ({} loaded, cap {})",
                        evicted, ACCESS_ORDER.size(), cap);
            }
        }
    }

    /**
     * Release everything of one model (mesh GL resources, uploaded textures,
     * compiled runtime scripts) and forget its registrations, so the next lookup
     * re-registers it from the verified cache. Returns true when something was
     * released. The loaded mesh is also removed from Epic Fight's own private
     * mesh cache (see {@link #takeFromEfMeshCache}) - it has no public removal
     * API, so without this every evicted mesh instance (potentially several MB
     * of vertex arrays) would stay referenced there until the next resource
     * reload.
     */
    private static boolean evictModel(String modelId) {
        Meshes.MeshAccessor<YSMMesh> accessor = MESHES.remove(modelId);
        if (accessor == null) {
            return false;
        }
        if (LOADED_MODELS.remove(modelId)) {
            YSMMesh mesh;
            try {
                mesh = takeFromEfMeshCache(accessor);
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: Epic Fight mesh cache unreachable, releasing evicted model '{}' in place", modelId);
                try {
                    mesh = accessor.get();
                } catch (Throwable ignored) {
                    mesh = null;
                }
            }
            if (mesh != null) {
                // Delayed release (5 ticks, like the evicted textures): the
                // YSMMesh instance is shared by every entity using the model
                // and may still be drawn later in this frame (shadow/outline/
                // capture passes select meshes before the main pass draws
                // them). Destroying it immediately would use freed GL objects.
                // The next accessor.get() rebuilds a fresh instance meanwhile.
                TextureStore.scheduleMeshRelease(mesh);
            }
        }
        java.util.List<ResourceLocation> toRelease = TextureStore.releaseTexturesOfModel(modelId);
        YSMRuntimeModel.invalidate(modelId);
        YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: evicted model '{}' ({} textures released)", modelId, toRelease.size());
        return true;
    }

    /**
     * Remove the given accessor's loaded mesh instance from Epic Fight's private
     * static mesh cache (Meshes#MESHES, keyed by the accessor with no public
     * removal API) and return it, so the instance can be fully released. Returns
     * null when the cache has no entry for the accessor (the mesh was already
     * released by Epic Fight's own resource reload) and throws when the cache
     * cannot be reached reflectively - the caller then falls back to destroying
     * the mesh in place.
     */
    private static YSMMesh takeFromEfMeshCache(Meshes.MeshAccessor<?> accessor) {
        java.lang.reflect.Field field;
        try {
            field = Meshes.class.getDeclaredField("MESHES");
            field.setAccessible(true);
        } catch (Throwable t) {
            throw new IllegalStateException("Epic Fight mesh cache field unreachable", t);
        }
        Object map;
        try {
            map = field.get(null);
        } catch (Throwable t) {
            throw new IllegalStateException("Epic Fight mesh cache read failed", t);
        }
        if (!(map instanceof java.util.Map<?, ?>)) {
            throw new IllegalStateException("Epic Fight mesh cache is not a map");
        }
        Object removed = ((java.util.Map<Object, Object>) map).remove(accessor);
        return removed instanceof YSMMesh mesh ? mesh : null;
    }

    /**
     * Cheap metadata fingerprint check for one model; a mismatch falls back to
     * the content fingerprint (mirrors the old whole-set gate). A sig-only
     * refresh (YSM re-writes model files without content changes) updates the
     * manifest in place so no re-conversion happens.
     */
    private static boolean fingerprintMatches(String modelId, JsonObject modelEntry) {
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
            return false;
        }
    }

    /**
     * Verify every generated output of one model (mesh JSON, runtime JSON and
     * cached texture bytes) against the manifest hashes/sizes.
     */
    private static boolean verifyModelOutputs(JsonObject modelEntry) {
        try {
            String meshName = modelEntry.get("mesh").getAsString();
            if (!hashMatches(MESH_DIR.resolve(meshName + ".json"),
                    modelEntry.get("msize").getAsLong(), modelEntry.get("mhash").getAsString())) {
                return false;
            }
            if (!hashMatches(RUNTIME_DIR.resolve(meshName + ".json"),
                    modelEntry.get("rsize").getAsLong(), modelEntry.get("rhash").getAsString())) {
                return false;
            }
            if (modelEntry.has("textures") && modelEntry.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> texEntry : modelEntry.getAsJsonObject("textures").entrySet()) {
                    JsonObject tex = texEntry.getValue().getAsJsonObject();
                    if (!tex.has("rl") || !tex.has("hash") || !tex.has("size")) {
                        return false;
                    }
                    if (!TextureStore.verifyTextureCache(ResourceLocation.parse(tex.get("rl").getAsString()),
                            tex.get("size").getAsLong(), tex.get("hash").getAsString())) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Register the mesh accessor and texture state of one model from the
     * verified cache (manifest entry + texture cache files), without touching
     * the (encrypted) model packages. In-memory only: the texture bytes were
     * read (and the pack textures written) by the caller outside the class
     * lock, so the render thread never blocks on file I/O while the lock is
     * held by a worker.
     */
    private static boolean registerFromCache(String modelId, JsonObject modelEntry, Map<String, byte[]> textureBytes) {
        try {
            String meshId = modelEntry.get("mesh").getAsString();

            if (modelEntry.has("textures") && modelEntry.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> texEntry : modelEntry.getAsJsonObject("textures").entrySet()) {
                    JsonObject tex = texEntry.getValue().getAsJsonObject();
                    ResourceLocation rl = ResourceLocation.parse(tex.get("rl").getAsString());
                    int format = tex.has("fmt") ? tex.get("fmt").getAsInt() : 0;
                    byte[] bytes = textureBytes.get(texEntry.getKey());
                    if (bytes != null) {
                        TextureStore.registerTexture(modelId, texEntry.getKey(), rl, bytes, format != 0
                                ? new int[]{tex.has("w") ? tex.get("w").getAsInt() : 0,
                                tex.has("h") ? tex.get("h").getAsInt() : 0, format} : null);
                    }
                }
            }
            // The accessor itself is created on the render thread (see
            // PendingMeshRegistration / drainPendingMeshRegistrations): EF's
            // MeshAccessor.create writes its non-thread-safe ACCESSORS map.
            // Enqueued last so a texture-registration failure cannot leave a
            // zombie registration behind (the caller re-converts then).
            PENDING_MESH_REGISTRATIONS.add(new PendingMeshRegistration(modelId, meshId, LOAD_GENERATION.get()));
            // restore path: compile the runtime scripts on the background pool so
            // the first draw does not compile them inline on the render thread
            preloadRuntimeAsync(modelId);
            return true;
        } catch (Exception e) {
            MESHES.remove(modelId);
            return false;
        }
    }

    /**
     * Submit the runtime script compilation of a model to the background pool
     * (used by the cache-restore path and by TLM model registration).
     */
    public static void preloadRuntimeAsync(String modelId) {
        LAZY_POOL.submit(() -> YSMRuntimeModel.preload(modelId));
    }

    /**
     * Merge one converted model into the registries and the manifest. Only the
     * caller under the YSMMeshLibrary lock (render thread or worker).
     */
    private static void registerModelResult(ModelResult result) {
        for (TextureEntry tex : result.textures()) {
            TextureStore.registerTexture(result.modelId(), tex.textureName(), tex.location(), tex.data(), tex.info());
        }

        // The accessor is created on the render thread (see
        // PendingMeshRegistration / drainPendingMeshRegistrations): EF's
        // MeshAccessor.create writes its non-thread-safe ACCESSORS map.
        PENDING_MESH_REGISTRATIONS.add(new PendingMeshRegistration(
                result.modelId(), result.meshId(), LOAD_GENERATION.get()));

        JsonObject modelEntry = new JsonObject();
        modelEntry.addProperty("sig", result.fingerprint());        modelEntry.addProperty("csig", result.contentFingerprint());
        modelEntry.addProperty("mesh", result.meshId());
        modelEntry.addProperty("mhash", result.meshHash());
        modelEntry.addProperty("msize", result.meshSize());
        modelEntry.addProperty("rhash", result.runtimeHash());
        modelEntry.addProperty("rsize", result.runtimeSize());
        JsonObject texturesObj = new JsonObject();
        for (TextureEntry tex : result.textures()) {
            JsonObject texObj = new JsonObject();
            texObj.addProperty("rl", tex.location().toString());
            if (tex.info() != null) {
                texObj.addProperty("w", tex.info()[0]);
                texObj.addProperty("h", tex.info()[1]);
                texObj.addProperty("fmt", tex.info()[2]);
            }
            texObj.addProperty("hash", tex.hash());
            texObj.addProperty("size", tex.size());
            texturesObj.add(tex.textureName(), texObj);
        }
        modelEntry.add("textures", texturesObj);
        ManifestStore.update(result.modelId(), modelEntry);
        if (YSMEpicFightCompat.LOGGER.isDebugEnabled()) {
            YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: converted model '{}' -> {} quads", result.modelId(), result.quads());
        }
    }

    /**
     * Drop every registered mesh/accessor and cached texture state so the next
     * mesh lookup re-validates and re-registers from disk (cheap) or re-converts
     * lazily (when model files changed). Also forgets failed/pending conversions
     * and the compiled runtime models. Called on resource reload (F3+T) and after
     * a "/ysm model reload" command.
     */
    public static synchronized void invalidateAll() {
        LOAD_GENERATION.incrementAndGet();
        // Queued accessor registrations of the previous generation are stale
        // (drainPendingMeshRegistrations checks the generation); drop them so
        // the queue cannot grow across repeated reloads.
        PENDING_MESH_REGISTRATIONS.clear();
        // Release every loaded mesh from Epic Fight's own mesh cache too (its
        // key is our accessor, which is dropped below): without this the mesh
        // instances and their compute buffers stay referenced until Epic
        // Fight's next resource reload - which never runs on "/ysm model
        // reload" - so each invalidation would leak one mesh per loaded model.
        // On F3+T this is idempotent with Epic Fight's own reload: whichever
        // runs first removes the entry, the other finds nothing to release.
        for (Meshes.MeshAccessor<YSMMesh> accessor : java.util.List.copyOf(MESHES.values())) {
            YSMMesh mesh;
            try {
                mesh = takeFromEfMeshCache(accessor);
            } catch (Throwable ignored) {
                continue;
            }
            if (mesh != null) {
                try {
                    mesh.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        MESHES.clear();
        TextureStore.invalidateAll();
        FAILED_MODELS.clear();
        PENDING_MODELS.clear();
        // No registration survives the invalidation, so nothing left to prewarm.
        PENDING_PREWARM.clear();
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.clear();
        }
        LOADED_MODELS.clear();
        disposeAllPaths();
        YSMRuntimeModel.invalidateAll();
        com.ysmef.compat.model.runtime.YsmBindArmature.invalidateAll();
        YsmExtraAnimationLibrary.invalidateAll();
    }

    /**
     * Scan all locally available YSM models, convert them to Epic Fight mesh
     * JSONs on disk, and register them in Epic Fight's mesh registry.
     *
     * Conversion runs on a worker pool sized to the available CPU cores
     * (package decryption + mesh writing are pure CPU work); the calling thread
     * blocks until every model has been processed and the manifest is written.
     */
    public static synchronized void generateAll() {
        preparePackFolder();
        long start = System.nanoTime();

        Map<String, Boolean> models = YsmModelPackage.scanAvailableModels();
        int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable, "ysm-ef-meshgen");
            thread.setDaemon(true);
            return thread;
        });

        Map<String, Meshes.MeshAccessor<YSMMesh>> newMeshes = new LinkedHashMap<>();
        Map<String, byte[]> newTexData = new LinkedHashMap<>();
        Map<String, int[]> newTexInfo = new LinkedHashMap<>();
        Map<String, ResourceLocation> newTexLocations = new LinkedHashMap<>();
        JsonObject manifestModels = new JsonObject();
        int converted = 0;

        try {
            List<Future<ModelResult>> futures = new ArrayList<>();
            for (String modelId : models.keySet()) {
                futures.add(pool.submit(() -> convertModel(modelId)));
            }

            for (Future<ModelResult> future : futures) {
                ModelResult result;
                try {
                    result = future.get();
                } catch (Exception e) {
                    YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to convert model ({})", e.toString());
                    continue;
                }
                if (result == null) {
                    continue;
                }
                converted++;

                for (TextureEntry tex : result.textures()) {
                    newTexLocations.put(result.modelId() + "#" + tex.textureName(), tex.location());
                    newTexData.put(tex.location().toString(), tex.data());
                    if (tex.info() != null) {
                        newTexInfo.put(tex.location().toString(), tex.info());
                    }
                }

                Meshes.MeshAccessor<YSMMesh> accessor = Meshes.MeshAccessor.create(
                        YSMEpicFightCompat.MODID, "entity/" + result.meshId(),
                        (loader) -> loader.loadSkinnedMesh(YSMMesh::new));
                newMeshes.put(result.modelId(), accessor);

                JsonObject modelEntry = new JsonObject();
                modelEntry.addProperty("sig", result.fingerprint());
                modelEntry.addProperty("csig", result.contentFingerprint());
                modelEntry.addProperty("mesh", result.meshId());
                modelEntry.addProperty("mhash", result.meshHash());
                modelEntry.addProperty("msize", result.meshSize());
                modelEntry.addProperty("rhash", result.runtimeHash());
                modelEntry.addProperty("rsize", result.runtimeSize());
                JsonObject texturesObj = new JsonObject();
                for (TextureEntry tex : result.textures()) {
                    JsonObject texObj = new JsonObject();
                    texObj.addProperty("rl", tex.location().toString());
                    if (tex.info() != null) {
                        texObj.addProperty("w", tex.info()[0]);
                        texObj.addProperty("h", tex.info()[1]);
                        texObj.addProperty("fmt", tex.info()[2]);
                    }
                    texObj.addProperty("hash", tex.hash());
                    texObj.addProperty("size", tex.size());
                    texturesObj.add(tex.textureName(), texObj);
                }
                modelEntry.add("textures", texturesObj);
                manifestModels.add(result.modelId(), modelEntry);

                if (YSMEpicFightCompat.LOGGER.isDebugEnabled()) {
                    YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: converted model '{}' -> {} quads", result.modelId(), result.quads());
                }
            }
        } finally {
            pool.shutdown();
        }

        MESHES.clear();
        MESHES.putAll(newMeshes);
        TextureStore.replaceAll(newTexLocations, newTexData, newTexInfo);

        cleanupStaleFiles(manifestModels);
        // Full rewrite: persist synchronously and keep the mirror consistent
        // (ManifestStore owns both), so later lazy conversions merge into the
        // right baseline.
        ManifestStore.replaceAll(manifestModels);

        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: generated {} base meshes from {} YSM model packages on {} threads in {} ms",
                converted, models.size(), threadCount, (System.nanoTime() - start) / 1_000_000L);
    }

    /**
     * Worker: convert one model package (decrypt, write mesh + runtime JSON,
     * cache texture bytes). Pure CPU/disk work on model-local data; shared
     * registries are only touched by the caller thread when merging results.
     */
    private static ModelResult convertModel(String modelId) {
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
                textures.add(new TextureEntry(entry.getKey(), rl, data, info, sha256Hex(data), data.length));
            }
            String defaultTextureRL = TextureStore.defaultTextureOf(modelId, pkg);

            String meshId = TextureStore.sanitize(modelId);
            Path outFile = MESH_DIR.resolve(meshId + ".json");
            Path runtimeFile = RUNTIME_DIR.resolve(meshId + ".json");
            int quads = EFMeshJsonWriter.write(pkg, outFile, runtimeFile, defaultTextureRL);
            if (quads < 0) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: skipping model '{}' (no geometry after conversion)", modelId);
                return null;
            }
            // Convert the model's wheel-selectable GEO animations into sampled
            // Avalon-style frame animation templates (deduplicated in public/).
            YsmExtraAnimationLibrary.convertModel(pkg);

            String meshHash = sha256Hex(outFile);
            long meshSize = Files.size(outFile);
            String runtimeHash = sha256Hex(runtimeFile);
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

    private static String sha256Hex(Path file) throws IOException {
        return sha256Hex(Files.readAllBytes(file));
    }

    private static String sha256Hex(byte[] data) {
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
    private static void cleanupStaleFiles(JsonObject manifestModels) {
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
        deleteStaleJsons(MESH_DIR, keepMeshIds);
        deleteStaleJsons(RUNTIME_DIR, keepMeshIds);
        TextureStore.deleteStaleTextureFiles(keepTexturePaths);
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

    /**
     * Find the generated mesh accessor for the given YSM model id, generating
     * (lazily, on the background pool) and registering the model on first use.
     *
     * @return the mesh accessor, or null if the model is unavailable or its
     *         conversion has not finished yet (Epic Fight biped fallback)
     */
    public static Meshes.MeshAccessor<YSMMesh> findMesh(String modelId) {
        ensureModel(modelId);
        Meshes.MeshAccessor<YSMMesh> accessor = MESHES.get(modelId);
        if (accessor != null) {
            touch(modelId);
        }
        return accessor;
    }

    /**
     * Record that the model's mesh was actually instantiated (owns GL resources:
     * Epic Fight compute buffers + the GPU skinning buffers). Used by the LRU
     * eviction to release those resources. Called after accessor.get() succeeds.
     */
    public static void markMeshLoaded(String modelId) {
        LOADED_MODELS.add(modelId);
        touch(modelId);
    }

    /**
     * Resolve the texture resource location for the given model + texture name.
     * Falls back to the model's first texture when the name is unknown.
     */
    public static ResourceLocation findTexture(String modelId, String textureName) {
        ensureModel(modelId);
        return TextureStore.findTexture(modelId, textureName);
    }

    /**
     * Upload the texture bytes to the texture manager if not done yet (delegated
     * to {@link TextureStore}; decode on the background pool, GL upload drained
     * on the render thread with a time budget).
     */
    public static void ensureTextureUploaded(ResourceLocation rl) {
        TextureStore.ensureTextureUploaded(rl);
    }

    /**
     * Drain the completed texture uploads on the render thread with a small
     * per-frame time budget (ModernYSM UploadManager UPLOAD_TIME_LIMIT_MS).
     * The DynamicTexture takes ownership of the decoded image.
     */
    public static void processPendingTextureUploads() {
        TextureStore.processPendingTextureUploads();
    }

    /**
     * Release textures that were evicted a few ticks ago (delayed so a texture
     * still referenced by the current frame's draws is not dropped mid-frame).
     * Called from the client tick.
     */
    public static void processPendingTextureReleases() {
        TextureStore.processPendingTextureReleases();
    }

    /**
     * Release evicted meshes whose delay elapsed (same cadence as
     * {@link #processPendingTextureReleases}, called from the client tick).
     * Must run on the render thread: the GL deletions require it. The mesh was
     * already removed from Epic Fight's mesh cache by the eviction, so any
     * later draw uses a freshly rebuilt instance.
     */
    public static void processPendingMeshReleases() {
        TextureStore.processPendingMeshReleases();
    }

    /**
     * Instantiate recently registered meshes on the render thread with a small
     * per-tick time budget (called from the client tick). accessor.get() does
     * the JSON parse + SkinnedMesh construction + compute setup - spread over
     * ticks after registration instead of paying it all on the first draw.
     */
    public static void prewarmMeshes() {
        if (PENDING_PREWARM.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + PREWARM_BUDGET_NANOS;
        while (!PENDING_PREWARM.isEmpty() && System.nanoTime() < deadline) {
            String modelId = PENDING_PREWARM.iterator().next();
            PENDING_PREWARM.remove(modelId);
            Meshes.MeshAccessor<YSMMesh> accessor = MESHES.get(modelId);
            if (accessor == null || LOADED_MODELS.contains(modelId)
                    || PENDING_MODELS.contains(modelId) || FAILED_MODELS.contains(modelId)) {
                continue;
            }
            try {
                accessor.get();
                LOADED_MODELS.add(modelId);
                touch(modelId);
            } catch (Throwable t) {
                FAILED_MODELS.add(modelId);
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to prewarm mesh for '{}'", modelId, t);
            }
        }
    }

    /**
     * Whether the texture was fully uploaded to the texture manager (its GL
     * texture id is valid). Textures still decoding/uploading asynchronously
     * must not be bound by the GPU path - their GL id is not ready yet and
     * binding it would draw the mesh untextured (white) for a frame or two;
     * the caller falls back to Epic Fight's render path in the meantime.
     */
    public static boolean isTextureUploaded(ResourceLocation rl) {
        return TextureStore.isTextureUploaded(rl);
    }

    /**
     * Whether the model texture has translucent pixels (any alpha below 253),
     * driving the GPU path's second (blended) draw pass. Unknown textures are
     * treated as opaque.
     */
    public static boolean isTranslucentTexture(ResourceLocation rl) {
        return TextureStore.isTranslucentTexture(rl);
    }

    public static boolean isGenerated() {
        return !MESHES.isEmpty();
    }

    public static int meshCount() {
        return MESHES.size();
    }

    /**
     * The model ids that have a generated base mesh (for diagnostics).
     */
    public static java.util.Set<String> availableModelIds() {
        return YsmModelPackage.scanAvailableModels().keySet();
    }
}
