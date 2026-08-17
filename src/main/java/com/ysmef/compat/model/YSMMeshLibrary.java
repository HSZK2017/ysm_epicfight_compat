package com.ysmef.compat.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.model.runtime.YSMRuntimeModel;
import com.ysmef.compat.ysm.YsmModelPackage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.client.model.Meshes;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import javax.imageio.ImageIO;
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
    private static final Path PACK_ROOT = CONFIG_ROOT.resolve("resourcepack");
    private static final Path MESH_DIR = PACK_ROOT.resolve("assets").resolve(YSMEpicFightCompat.MODID).resolve("animmodels").resolve("entity");
    private static final Path RUNTIME_DIR = PACK_ROOT.resolve("assets").resolve(YSMEpicFightCompat.MODID).resolve("ysm_runtime").resolve("entity");
    private static final Path PACK_META = PACK_ROOT.resolve("pack.mcmeta");
    private static final Path MANIFEST = CONFIG_ROOT.resolve("manifest.json");
    private static final Path TEXTURE_CACHE_DIR = CONFIG_ROOT.resolve("texturecache");

    /**
     * Bumped whenever the conversion algorithm or the manifest format changes
     * in a way that invalidates previously generated meshes (forces a one-time
     * full regeneration).
     *
     * 5: binary animation length is now converted ticks -> seconds (YsmBinaryReader),
     *    which changes the runtime JSON output of converted models.
     */
    private static final int GENERATOR_VERSION = 5;

    private static final String MESH_NAMESPACE = YSMEpicFightCompat.MODID;

    /**
     * modelId -> registered mesh accessor. Read lock-free from the render
     * thread (findMesh) while worker threads register conversion results, so
     * it must be concurrent.
     */
    private static final Map<String, Meshes.MeshAccessor<YSMMesh>> MESHES = new ConcurrentHashMap<>();

    /** textureRL string -> original encoded texture bytes (registered into the texture manager on demand). */
    private static final Map<String, byte[]> TEXTURE_DATA = new ConcurrentHashMap<>();

    /** textureRL string -> [width, height, format] (format: -1=raw RGBA, 2=PNG, 3=JPEG, 4=WEBP, 5=AVIF). */
    private static final Map<String, int[]> TEXTURE_INFO = new ConcurrentHashMap<>();

    /**
     * OpenYSM/ModernYSM ship the ImageStream decoders (WebP/AVIF) inside their
     * jar (jar-in-jar). The compat mod cannot compile against them (the libs
     * YSM jar is obfuscated), so they are looked up reflectively at runtime.
     * Null when a legacy fork without ImageStream is installed.
     */
    private static final Class<?> YSM_WEBP_DECODER_CLASS = findImageStreamDecoder("rip.ysm.imagestream.webp.WebpDecoder");
    private static final Class<?> YSM_AVIF_DECODER_CLASS = findImageStreamDecoder("rip.ysm.imagestream.avif.AvifDecoder");
    private static final Map<Class<?>, Method> IMAGE_STREAM_READ_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> IMAGE_STREAM_CTORS = new ConcurrentHashMap<>();
    private static volatile boolean IMAGE_STREAM_MISSING_LOGGED = false;
    private static volatile boolean IMAGE_STREAM_FAILED_LOGGED = false;

    /**
     * modelId + '#' + textureName -> textureRL. Kept as a lock-guarded
     * LinkedHashMap: findTexture's fallback returns the model's first texture
     * and relies on insertion order. Every access must hold the map's monitor -
     * the render thread reads while worker threads register new models.
     */
    private static final Map<String, ResourceLocation> TEXTURE_LOCATIONS = new LinkedHashMap<>();

    /** textureRL string -> true once registered in the texture manager */
    private static final Map<String, Boolean> UPLOADED_TEXTURES = new ConcurrentHashMap<>();

    /**
     * ModernYSM-style texture pipeline (see UploadManager): image decoding runs
     * on the background pool, and the GL uploads are drained on the render
     * thread with a small per-frame time budget, so large textures no longer
     * hitch the first draw. Texture releases are delayed a few ticks so a
     * texture still referenced by the current frame is never dropped mid-frame.
     */
    private static final Set<String> PENDING_TEXTURE_DECODES = ConcurrentHashMap.newKeySet();
    private static final java.util.Queue<TextureUploadTask> COMPLETED_UPLOADS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final Map<String, Integer> PENDING_RELEASES = new ConcurrentHashMap<>();
    private static final int RELEASE_DELAY_TICKS = 5;
    private static final long TEXTURE_UPLOAD_BUDGET_NANOS = 10_000_000L;

    private record TextureUploadTask(ResourceLocation location, NativeImage image, boolean translucent) {}

    /** Models whose lazy conversion already failed; not retried until invalidateAll. */
    private static final Set<String> FAILED_MODELS = ConcurrentHashMap.newKeySet();

    /** Models currently being converted on the background pool. */
    private static final Set<String> PENDING_MODELS = ConcurrentHashMap.newKeySet();

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
        return PACK_ROOT;
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
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(MESH_NAMESPACE,
                "textures/" + relativePath + ".png");
        TEXTURE_DATA.put(rl.toString(), data);
        return rl;
    }

    /**
     * The runtime script JSON (bone table + molang animations) generated for the
     * given mesh id, evaluated by YSMRuntimeModel at render time.
     */
    public static Path getRuntimeFile(String meshId) {
        return RUNTIME_DIR.resolve(meshId + ".json");
    }

    /** modelId -> meshId (sanitized), for runtime lookup. */
    public static String meshIdOf(String modelId) {
        return sanitize(modelId);
    }

    /**
     * Ensure the generated resource pack skeleton exists (called before the pack
     * repository is built).
     */
    public static void preparePackFolder() {
        try {
            Files.createDirectories(MESH_DIR);
            Files.createDirectories(RUNTIME_DIR);
            Files.createDirectories(TEXTURE_CACHE_DIR);
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
        if (MESHES.containsKey(modelId)) {
            touch(modelId);
            return true;
        }
        if (FAILED_MODELS.contains(modelId) || PENDING_MODELS.contains(modelId)) {
            return false;
        }

        JsonObject modelEntry = manifestEntry(modelId);
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
                    Path cacheFile = textureCachePath(rl);
                    if (Files.isRegularFile(cacheFile)) {
                        byte[] bytes = Files.readAllBytes(cacheFile);
                        textureBytes.put(texEntry.getKey(), bytes);
                        int format = tex.has("fmt") ? tex.get("fmt").getAsInt() : 0;
                        // WebP/AVIF pack textures written by older builds may be
                        // blank transparent PNGs (decoded as raw RGBA), so they
                        // are rewritten on every cache restore.
                        writePackTexture(rl, bytes, format != 0
                                ? new int[]{tex.has("w") ? tex.get("w").getAsInt() : 0,
                                tex.has("h") ? tex.get("h").getAsInt() : 0, format} : null,
                                format == 4 || format == 5 || isRiffWebp(bytes) || isFtypAvif(bytes));
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
                YSMRuntimeModel.invalidate(modelId);
                touch(modelId);
                trimIfNeeded();
                PENDING_MODELS.remove(modelId);
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
                    registerModelResult(result);
                    YSMRuntimeModel.invalidate(modelId);
                    touch(modelId);
                    trimIfNeeded();
                } else {
                    FAILED_MODELS.add(modelId);
                }
                PENDING_MODELS.remove(modelId);
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
                try {
                    mesh.destroy();
                    com.ysmef.compat.gpu.YsmGpuRenderPath.disposeMesh(mesh);
                    com.ysmef.compat.cpu.YsmCpuRenderPath.disposeMesh(mesh);
                } catch (Throwable t) {
                    YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to release evicted model '{}'", modelId, t);
                }
            }
        }
        java.util.List<ResourceLocation> toRelease = new java.util.ArrayList<>();
        synchronized (TEXTURE_LOCATIONS) {
            java.util.Iterator<Map.Entry<String, ResourceLocation>> it = TEXTURE_LOCATIONS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, ResourceLocation> entry = it.next();
                if (entry.getKey().startsWith(modelId + "#")) {
                    toRelease.add(entry.getValue());
                    it.remove();
                }
            }
        }
        for (ResourceLocation rl : toRelease) {
            String key = rl.toString();
            TEXTURE_DATA.remove(key);
            TEXTURE_INFO.remove(key);
            UPLOADED_TEXTURES.remove(key);
            TEXTURE_TRANSLUCENT.remove(key);
            // delayed release: the texture may still be referenced by the current
            // frame's draws; release it a few ticks later (see processPendingTextureReleases)
            PENDING_RELEASES.put(key, RELEASE_DELAY_TICKS);
        }
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
     * The manifest entry of one model, or null when the manifest is missing,
     * predates the current generator version, or has no entry for the model.
     */
    private static JsonObject manifestEntry(String modelId) {
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
            return modelEntry;
        } catch (Exception e) {
            return null;
        }
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
                    updateManifestModel(modelId, modelEntry);
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
                    Path cacheFile = textureCachePath(ResourceLocation.parse(tex.get("rl").getAsString()));
                    if (!hashMatches(cacheFile, tex.get("size").getAsLong(), tex.get("hash").getAsString())) {
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
            Meshes.MeshAccessor<YSMMesh> accessor = Meshes.MeshAccessor.create(
                    MESH_NAMESPACE, "entity/" + meshId,
                    (loader) -> loader.loadSkinnedMesh(YSMMesh::new));
            MESHES.put(modelId, accessor);

            if (modelEntry.has("textures") && modelEntry.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> texEntry : modelEntry.getAsJsonObject("textures").entrySet()) {
                    JsonObject tex = texEntry.getValue().getAsJsonObject();
                    ResourceLocation rl = ResourceLocation.parse(tex.get("rl").getAsString());
                    synchronized (TEXTURE_LOCATIONS) {
                        TEXTURE_LOCATIONS.put(modelId + "#" + texEntry.getKey(), rl);
                    }
                    int format = tex.has("fmt") ? tex.get("fmt").getAsInt() : 0;
                    if (format != 0) {
                        TEXTURE_INFO.put(rl.toString(), new int[]{
                                tex.has("w") ? tex.get("w").getAsInt() : 0,
                                tex.has("h") ? tex.get("h").getAsInt() : 0,
                                format});
                    }
                    byte[] bytes = textureBytes.get(texEntry.getKey());
                    if (bytes != null) {
                        TEXTURE_DATA.put(rl.toString(), bytes);
                    }
                }
            }
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
            synchronized (TEXTURE_LOCATIONS) {
                TEXTURE_LOCATIONS.put(result.modelId() + "#" + tex.textureName(), tex.location());
            }
            TEXTURE_DATA.put(tex.location().toString(), tex.data());
            if (tex.info() != null) {
                TEXTURE_INFO.put(tex.location().toString(), tex.info());
            }
        }

        Meshes.MeshAccessor<YSMMesh> accessor = Meshes.MeshAccessor.create(
                MESH_NAMESPACE, "entity/" + result.meshId(),
                (loader) -> loader.loadSkinnedMesh(YSMMesh::new));
        MESHES.put(result.modelId(), accessor);

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
        updateManifestModel(result.modelId(), modelEntry);

        if (YSMEpicFightCompat.LOGGER.isDebugEnabled()) {
            YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: converted model '{}' -> {} quads", result.modelId(), result.quads());
        }
    }

    /**
     * Merge one model's manifest entry into the manifest on disk, preserving
     * every other model's entry (the lazy path converts models independently).
     * Synchronized: restore workers (fingerprintMatches sig refreshes) and
     * conversion workers (registerModelResult) rewrite the whole manifest and
     * must not interleave; the lock is reentrant for callers already holding
     * the YSMMeshLibrary monitor.
     */
    private static synchronized void updateManifestModel(String modelId, JsonObject modelEntry) {
        try {
            JsonObject manifestModels = new JsonObject();
            if (Files.isRegularFile(MANIFEST)) {
                JsonObject manifest = JsonParser.parseString(Files.readString(MANIFEST, StandardCharsets.UTF_8)).getAsJsonObject();
                if (manifest.has("generator") && manifest.get("generator").getAsInt() == GENERATOR_VERSION
                        && manifest.has("models") && manifest.get("models").isJsonObject()) {
                    manifestModels = manifest.getAsJsonObject("models");
                }
            }
            manifestModels.add(modelId, modelEntry);
            writeManifest(manifestModels);
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to update generation manifest for '{}'", modelId);
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
        TEXTURE_DATA.clear();
        TEXTURE_INFO.clear();
        synchronized (TEXTURE_LOCATIONS) {
            TEXTURE_LOCATIONS.clear();
        }
        UPLOADED_TEXTURES.clear();
        TEXTURE_TRANSLUCENT.clear();
        FAILED_MODELS.clear();
        PENDING_MODELS.clear();
        PENDING_TEXTURE_DECODES.clear();
        COMPLETED_UPLOADS.clear();
        PENDING_RELEASES.clear();
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.clear();
        }
        LOADED_MODELS.clear();
        com.ysmef.compat.gpu.YsmGpuRenderPath.disposeAll();
        com.ysmef.compat.cpu.YsmCpuRenderPath.disposeAll();
        YSMRuntimeModel.invalidateAll();
        com.ysmef.compat.model.runtime.YsmBindArmature.invalidateAll();
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
                        MESH_NAMESPACE, "entity/" + result.meshId(),
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
        TEXTURE_DATA.clear();
        TEXTURE_DATA.putAll(newTexData);
        TEXTURE_INFO.clear();
        TEXTURE_INFO.putAll(newTexInfo);
        synchronized (TEXTURE_LOCATIONS) {
            TEXTURE_LOCATIONS.clear();
            TEXTURE_LOCATIONS.putAll(newTexLocations);
        }
        UPLOADED_TEXTURES.clear();

        cleanupStaleFiles(manifestModels);
        writeManifest(manifestModels);

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
                ResourceLocation rl = textureLocation(modelId, entry.getKey());
                int[] info = pkg.textureInfo.get(entry.getKey());
                byte[] data = entry.getValue();
                writeTextureCache(rl, data);
                writePackTexture(rl, data, info, false);
                textures.add(new TextureEntry(entry.getKey(), rl, data, info, sha256Hex(data), data.length));
            }
            String defaultTextureRL = defaultTextureOf(modelId, pkg);

            String meshId = sanitize(modelId);
            Path outFile = MESH_DIR.resolve(meshId + ".json");
            Path runtimeFile = RUNTIME_DIR.resolve(meshId + ".json");
            int quads = EFMeshJsonWriter.write(pkg, outFile, runtimeFile, defaultTextureRL);
            if (quads < 0) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: skipping model '{}' (no geometry after conversion)", modelId);
                return null;
            }

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

    /**
     * Resolve the resource location of the model's default texture (mirrors the
     * fallback order used at render time).
     */
    private static String defaultTextureOf(String modelId, YsmModelPackage pkg) {
        ResourceLocation defaultRL = null;
        if (!pkg.defaultTexture.isEmpty()) {
            defaultRL = ResourceLocation.fromNamespaceAndPath(MESH_NAMESPACE,
                    "textures/" + sanitize(modelId) + "/" + sanitize(pkg.defaultTexture) + ".png");
            if (!pkg.textures.containsKey(pkg.defaultTexture)) {
                defaultRL = null;
            }
        }
        if (defaultRL == null && !pkg.textures.isEmpty()) {
            String first = pkg.textures.keySet().iterator().next();
            defaultRL = ResourceLocation.fromNamespaceAndPath(MESH_NAMESPACE,
                    "textures/" + sanitize(modelId) + "/" + sanitize(first) + ".png");
        }
        return defaultRL != null ? defaultRL.toString()
                : ResourceLocation.withDefaultNamespace("textures/entity/steve.png").toString();
    }

    private static ResourceLocation textureLocation(String modelId, String textureName) {
        return ResourceLocation.fromNamespaceAndPath(MESH_NAMESPACE,
                "textures/" + sanitize(modelId) + "/" + sanitize(textureName) + ".png");
    }

    private static Path textureCachePath(ResourceLocation rl) {
        return TEXTURE_CACHE_DIR.resolve(rl.getNamespace()).resolve(rl.getPath());
    }

    private static void writeTextureCache(ResourceLocation rl, byte[] data) {
        try {
            Path cacheFile = textureCachePath(rl);
            EFMeshJsonWriter.writeFileAtomic(cacheFile, data);
        } catch (IOException e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to cache texture bytes for {}", rl);
        }
    }

    /**
     * Write the texture into the generated resource pack folder
     * (assets/&lt;ns&gt;/&lt;path&gt;), so the texture resolves through the
     * ResourceManager. The DynamicTexture registration alone is not enough:
     * Epic Fight's compute-shader path (and GeckoLib's AnimatableTexture
     * wrapper) bind textures by resource location and fail with a
     * FileNotFoundException when only a DynamicTexture exists, rendering the
     * converted mesh with a garbage/missing texture (red edges).
     *
     * PNG/JPEG payloads are copied verbatim (stb detects the format by magic
     * bytes); WebP/AVIF payloads are decoded through YSM's ImageStream and
     * re-encoded to PNG; legacy raw-RGBA payloads are re-encoded to PNG.
     */
    private static void writePackTexture(ResourceLocation rl, byte[] data, int[] info, boolean forceRewrite) {
        try {
            Path file = PACK_ROOT.resolve("assets").resolve(rl.getNamespace()).resolve(rl.getPath());
            if (Files.isRegularFile(file) && Files.size(file) > 0 && !forceRewrite) {
                return;
            }
            Files.createDirectories(file.getParent());
            boolean png = data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50
                    && data[2] == 0x4E && data[3] == 0x47;
            boolean jpeg = data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
            if (png || jpeg) {
                Files.write(file, data);
                return;
            }
            // WebP/AVIF must be decoded before they are written into the pack:
            // the previous code treated those bytes as raw RGBA and wrote a
            // valid but fully transparent PNG (mesh visible, texture missing).
            NativeImage image = null;
            if (isRiffWebp(data)) {
                image = decodeWithImageStream(YSM_WEBP_DECODER_CLASS, data, "WebP");
            } else if (isFtypAvif(data)) {
                image = decodeWithImageStream(YSM_AVIF_DECODER_CLASS, data, "AVIF");
            } else {
                image = readRawRgba(data, info != null ? info[0] : 0, info != null ? info[1] : 0);
            }
            if (image != null) {
                try {
                    image.writeToFile(file);
                } finally {
                    image.close();
                }
            }
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write pack texture {}", rl);
        }
    }

    private static void writeManifest(JsonObject manifestModels) {
        try {
            JsonObject manifest = new JsonObject();
            manifest.addProperty("generator", GENERATOR_VERSION);
            manifest.add("models", manifestModels);
            EFMeshJsonWriter.writeFileAtomic(MANIFEST, new com.google.gson.GsonBuilder().create().toJson(manifest).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write generation manifest", e);
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
        // stale textures in the generated pack (kept textures are tracked by
        // their "textures/<...>.png" relative path)
        Path packTextures = PACK_ROOT.resolve("assets").resolve(MESH_NAMESPACE).resolve("textures");
        try (var stream = Files.walk(packTextures)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .filter(path -> !keepTexturePaths.contains(
                            "textures/" + packTextures.relativize(path).toString().replace('\\', '/')))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        Path cacheRoot = TEXTURE_CACHE_DIR.resolve(MESH_NAMESPACE);
        try (var stream = Files.walk(TEXTURE_CACHE_DIR)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                boolean keep = path.startsWith(cacheRoot)
                        && keepTexturePaths.contains(cacheRoot.relativize(path).toString().replace('\\', '/'));
                if (!keep) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (IOException ignored) {
        }
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
        synchronized (TEXTURE_LOCATIONS) {
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
    }

    /**
     * Upload the texture bytes to the texture manager if not done yet. The image
     * decode runs on the background pool; the GL upload is drained on the render
     * thread (see {@link #processPendingTextureUploads()}), so the first draw of
     * a model no longer blocks on large PNG decodes (the texture may briefly
     * render as missing for a frame or two, like ModernYSM's async uploads).
     */
    public static void ensureTextureUploaded(ResourceLocation rl) {
        if (rl == null || UPLOADED_TEXTURES.containsKey(rl.toString())) {
            return;
        }
        String key = rl.toString();
        byte[] data = TEXTURE_DATA.get(key);
        if (data == null) {
            return;
        }
        if (!PENDING_TEXTURE_DECODES.add(key)) {
            return;
        }
        // a re-upload cancels any delayed release of the same resource location
        PENDING_RELEASES.remove(key);
        LAZY_POOL.submit(() -> {
            try {
                NativeImage image = decodeTexture(rl, data);
                if (image == null) {
                    UPLOADED_TEXTURES.put(key, Boolean.TRUE);
                } else {
                    // The translucency scan runs here (decode worker thread), not
                    // on the render thread: the per-frame upload drain has a small
                    // time budget and must not spend it scanning millions of pixels.
                    boolean translucent = hasTranslucentPixels(image);
                    COMPLETED_UPLOADS.add(new TextureUploadTask(rl, image, translucent));
                    Minecraft.getInstance().execute(YSMMeshLibrary::processPendingTextureUploads);
                }
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to decode texture {}", rl, t);
                UPLOADED_TEXTURES.put(key, Boolean.TRUE);
            } finally {
                PENDING_TEXTURE_DECODES.remove(key);
            }
        });
    }

    /**
     * Drain the completed texture uploads on the render thread with a small
     * per-frame time budget (ModernYSM UploadManager UPLOAD_TIME_LIMIT_MS).
     * The DynamicTexture takes ownership of the decoded image.
     */
    public static void processPendingTextureUploads() {
        long deadline = System.nanoTime() + TEXTURE_UPLOAD_BUDGET_NANOS;
        while (true) {
            TextureUploadTask task = COMPLETED_UPLOADS.poll();
            if (task == null) {
                return;
            }
            String key = task.location().toString();
            PENDING_RELEASES.remove(key);
            TEXTURE_TRANSLUCENT.put(key, task.translucent());
            Minecraft.getInstance().getTextureManager().register(task.location(), new DynamicTexture(task.image()));
            UPLOADED_TEXTURES.put(key, Boolean.TRUE);
            if (System.nanoTime() > deadline) {
                // leftover tasks stay queued: re-post the drain for the next frame
                Minecraft.getInstance().execute(YSMMeshLibrary::processPendingTextureUploads);
                return;
            }
        }
    }

    /**
     * Release textures that were evicted a few ticks ago (delayed so a texture
     * still referenced by the current frame's draws is not dropped mid-frame).
     * Called from the client tick.
     */
    public static void processPendingTextureReleases() {
        if (PENDING_RELEASES.isEmpty()) {
            return;
        }
        for (java.util.Iterator<Map.Entry<String, Integer>> it = PENDING_RELEASES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left <= 0) {
                it.remove();
                try {
                    Minecraft.getInstance().getTextureManager().release(ResourceLocation.parse(entry.getKey()));
                } catch (Throwable ignored) {
                }
            } else {
                entry.setValue(left);
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
        return rl != null && UPLOADED_TEXTURES.containsKey(rl.toString());
    }

    /**
     * Whether the model texture has translucent pixels (any alpha below 253),
     * driving the GPU path's second (blended) draw pass. Unknown textures are
     * treated as opaque.
     */
    public static boolean isTranslucentTexture(ResourceLocation rl) {
        if (rl == null) {
            return false;
        }
        return Boolean.TRUE.equals(TEXTURE_TRANSLUCENT.get(rl.toString()));
    }

    /**
     * One-time full scan of the decoded texture for translucent pixels
     * (alpha &lt; 253). Runs on the texture decode worker thread (see
     * ensureTextureUploaded) - every pixel is checked, because a strided
     * sampling misses small translucent regions (hair strands, gradients) and
     * the GPU path's first pass then discards them (alphaMode == 1).
     */
    private static boolean hasTranslucentPixels(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (((image.getPixelRGBA(x, y) >>> 24) & 0xFF) < 253) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Decode texture bytes into a NativeImage, supporting PNG/JPEG encoded data,
     * WebP/AVIF (through YSM's ImageStream, reflectively) and raw RGBA pixels
     * (legacy .ysm binary textures).
     *
     * Uses the InputStream-based read: NativeImage.read(byte[]) copies the whole
     * array onto the 64KB LWJGL MemoryStack, which overflows for large textures
     * ("Out of stack space"), while the InputStream overload buffers off-heap.
     */
    private static NativeImage decodeTexture(ResourceLocation rl, byte[] data) throws IOException {
        if (data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return NativeImage.read(new ByteArrayInputStream(data));
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return NativeImage.read(new ByteArrayInputStream(data));
        }
        if (isRiffWebp(data)) {
            return decodeWithImageStream(YSM_WEBP_DECODER_CLASS, data, "WebP");
        }
        if (isFtypAvif(data)) {
            return decodeWithImageStream(YSM_AVIF_DECODER_CLASS, data, "AVIF");
        }

        int[] info = TEXTURE_INFO.get(rl.toString());
        if (info != null && info[2] == -1) {
            return readRawRgba(data, info[0], info[1]);
        }

        if (data.length % 4 == 0) {
            int pixels = data.length / 4;
            int side = (int) Math.round(Math.sqrt(pixels));
            if ((long) side * side == pixels) {
                return readRawRgba(data, side, side);
            }
        }
        YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: unsupported texture format for {}", rl);
        return null;
    }

    /**
     * Interpret the bytes as raw RGBA pixels (YSM legacy texture format) and
     * build a NativeImage (Minecraft packs pixels as ABGR).
     */
    private static NativeImage readRawRgba(byte[] data, int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height * 4 > data.length) {
            int side = (int) Math.round(Math.sqrt(data.length / 4.0));
            width = side;
            height = side;
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int r = data[i] & 0xFF;
                int g = data[i + 1] & 0xFF;
                int b = data[i + 2] & 0xFF;
                int a = data[i + 3] & 0xFF;
                image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return image;
    }

    private static Class<?> findImageStreamDecoder(String className) {
        try {
            return Class.forName(className, false, YSMMeshLibrary.class.getClassLoader());
        } catch (Throwable ignored) {
            try {
                ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
                return contextLoader != null
                        ? Class.forName(className, false, contextLoader)
                        : Class.forName(className);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static boolean isRiffWebp(byte[] data) {
        return data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private static boolean isFtypAvif(byte[] data) {
        return data.length >= 12
                && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
    }

    /**
     * Decode WebP/AVIF with OpenYSM/ModernYSM's ImageStream decoders
     * (rip.ysm.imagestream.*). Those classes are loaded from the YSM jar at
     * runtime; reflection keeps the compat mod buildable against the obfuscated
     * release jar and still works on LegacyYSM when the classes are absent
     * (returns null and the model keeps its mesh with an untextured/fallback
     * texture instead of crashing).
     */
    private static NativeImage decodeWithImageStream(Class<?> decoderClass, byte[] data, String formatName) {
        if (decoderClass == null) {
            // Some YSM forks register ImageStream as an ImageIO plugin rather
            // than exposing the decoder class directly.
            try {
                BufferedImage imageIoImage = ImageIO.read(new ByteArrayInputStream(data));
                if (imageIoImage != null) {
                    return bufferedImageToNative(imageIoImage);
                }
            } catch (Throwable ignored) {
            }
            if (!IMAGE_STREAM_MISSING_LOGGED) {
                IMAGE_STREAM_MISSING_LOGGED = true;
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: {} texture found but the YSM ImageStream decoder is not available; "
                                + "the model will render without this texture", formatName);
            }
            return null;
        }
        try {
            Method read = IMAGE_STREAM_READ_METHODS.get(decoderClass);
            if (read == null) {
                for (Method candidate : decoderClass.getMethods()) {
                    if ("read".equals(candidate.getName())
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0] == byte[].class
                            && BufferedImage.class.isAssignableFrom(candidate.getReturnType())) {
                        read = candidate;
                        break;
                    }
                }
                if (read == null) {
                    if (!IMAGE_STREAM_FAILED_LOGGED) {
                        IMAGE_STREAM_FAILED_LOGGED = true;
                        YSMEpicFightCompat.LOGGER.warn(
                                "YSM-EF Compat: cannot find read(byte[]) on {}; {} textures will be skipped",
                                decoderClass.getName(), formatName);
                    }
                    return null;
                }
                IMAGE_STREAM_READ_METHODS.put(decoderClass, read);
            }
            Constructor<?> ctor = IMAGE_STREAM_CTORS.get(decoderClass);
            if (ctor == null) {
                ctor = decoderClass.getDeclaredConstructor();
                IMAGE_STREAM_CTORS.put(decoderClass, ctor);
            }
            Object image = read.invoke(ctor.newInstance(), (Object) data);
            return image instanceof BufferedImage bufferedImage
                    ? bufferedImageToNative(bufferedImage)
                    : null;
        } catch (Throwable t) {
            if (!IMAGE_STREAM_FAILED_LOGGED) {
                IMAGE_STREAM_FAILED_LOGGED = true;
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to decode {} texture with {}", formatName, decoderClass.getName(), t);
            }
            return null;
        }
    }

    /** Convert an ImageStream BufferedImage to Minecraft's ABGR NativeImage. */
    private static NativeImage bufferedImageToNative(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                int a = (pixel >>> 24) & 0xFF;
                int r = (pixel >>> 16) & 0xFF;
                int g = (pixel >>> 8) & 0xFF;
                int b = pixel & 0xFF;
                // NativeImage packs pixels as ABGR.
                out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return out;
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
