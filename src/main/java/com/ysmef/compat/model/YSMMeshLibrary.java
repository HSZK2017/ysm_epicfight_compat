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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
     */
    private static final int GENERATOR_VERSION = 4;

    private static final String MESH_NAMESPACE = YSMEpicFightCompat.MODID;

    /** modelId -> registered mesh accessor */
    private static final Map<String, Meshes.MeshAccessor<YSMMesh>> MESHES = new LinkedHashMap<>();

    /** textureRL string -> png bytes (registered into the texture manager on demand) */
    private static final Map<String, byte[]> TEXTURE_DATA = new LinkedHashMap<>();

    /** textureRL string -> [width, height, format] (format: -1=raw RGBA, 2=PNG, 3=JPEG, 4=WEBP, 5=AVIF) */
    private static final Map<String, int[]> TEXTURE_INFO = new LinkedHashMap<>();

    /** modelId + '#' + textureName -> textureRL */
    private static final Map<String, ResourceLocation> TEXTURE_LOCATIONS = new LinkedHashMap<>();

    /** textureRL string -> true once registered in the texture manager */
    private static final Map<String, Boolean> UPLOADED_TEXTURES = new ConcurrentHashMap<>();

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
     * The mesh file name used for TLM model-pack meshes: "namespace__path"
     * (mirrors the naming in TlmModelLibrary, WITHOUT the extra hash suffix
     * that {@link #sanitize} appends for characters like ':').
     */
    public static String tlmMeshIdOf(String modelId) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(modelId);
        if (rl == null) {
            return null;
        }
        return tlmSanitize(rl.getNamespace()) + "__" + tlmSanitize(rl.getPath());
    }

    private static String tlmSanitize(String value) {
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
     * - previously failed / currently converting -> false (Epic Fight biped fallback)
     * - cached outputs (manifest entry + verified mesh/runtime/texture files) ->
     *   registered from cache without decrypting the model package -> true
     * - missing or outdated -> the conversion is submitted to the background pool
     *   and false is returned; the mesh becomes available from the next frame on
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
        if (modelEntry != null && generatorVersionMatches()
                && fingerprintMatches(modelId, modelEntry) && verifyModelOutputs(modelEntry)
                && registerFromCache(modelId, modelEntry)) {
            touch(modelId);
            trimIfNeeded();
            return true;
        }

        if (!YsmModelPackage.scanAvailableModels().containsKey(modelId)) {
            FAILED_MODELS.add(modelId);
            return false;
        }

        PENDING_MODELS.add(modelId);
        LAZY_POOL.submit(() -> convertModelAsync(modelId));
        return false;
    }

    /** Worker: convert one model off-thread and merge the result under the lock. */
    private static void convertModelAsync(String modelId) {
        int generation = LOAD_GENERATION.get();
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
     * released.
     */
    private static boolean evictModel(String modelId) {
        Meshes.MeshAccessor<YSMMesh> accessor = MESHES.remove(modelId);
        if (accessor == null) {
            return false;
        }
        if (LOADED_MODELS.remove(modelId)) {
            try {
                YSMMesh mesh = accessor.get();
                if (mesh != null) {
                    mesh.destroy();
                    com.ysmef.compat.gpu.YsmGpuRenderPath.disposeMesh(mesh);
                }
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to release evicted model '{}'", modelId, t);
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
            try {
                Minecraft.getInstance().getTextureManager().release(rl);
            } catch (Throwable ignored) {
            }
        }
        YSMRuntimeModel.invalidate(modelId);
        YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: evicted model '{}' ({} textures released)", modelId, toRelease.size());
        return true;
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

    private static boolean generatorVersionMatches() {
        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(MANIFEST, StandardCharsets.UTF_8)).getAsJsonObject();
            return manifest.has("generator") && manifest.get("generator").getAsInt() == GENERATOR_VERSION;
        } catch (Exception e) {
            return false;
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
     * the (encrypted) model packages.
     */
    private static boolean registerFromCache(String modelId, JsonObject modelEntry) {
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
                    TEXTURE_LOCATIONS.put(modelId + "#" + texEntry.getKey(), rl);
                    int format = tex.has("fmt") ? tex.get("fmt").getAsInt() : 0;
                    if (format != 0) {
                        TEXTURE_INFO.put(rl.toString(), new int[]{
                                tex.has("w") ? tex.get("w").getAsInt() : 0,
                                tex.has("h") ? tex.get("h").getAsInt() : 0,
                                format});
                    }
                    Path cacheFile = textureCachePath(rl);
                    if (Files.isRegularFile(cacheFile)) {
                        TEXTURE_DATA.put(rl.toString(), Files.readAllBytes(cacheFile));
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
            TEXTURE_LOCATIONS.put(result.modelId() + "#" + tex.textureName(), tex.location());
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
     */
    private static void updateManifestModel(String modelId, JsonObject modelEntry) {
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
        MESHES.clear();
        TEXTURE_DATA.clear();
        TEXTURE_INFO.clear();
        TEXTURE_LOCATIONS.clear();
        UPLOADED_TEXTURES.clear();
        TEXTURE_TRANSLUCENT.clear();
        FAILED_MODELS.clear();
        PENDING_MODELS.clear();
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.clear();
        }
        LOADED_MODELS.clear();
        com.ysmef.compat.gpu.YsmGpuRenderPath.disposeAll();
        YSMRuntimeModel.invalidateAll();
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
        TEXTURE_LOCATIONS.clear();
        TEXTURE_LOCATIONS.putAll(newTexLocations);
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
                    YsmModelPackage.contentFingerprint(modelId), defaultTextureRL,
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
                    .filter(path -> dir.relativize(path).getNameCount() == 0
                            || !dir.relativize(path).getName(0).toString().equals("tlm"))
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

    /**
     * Upload the texture bytes to the texture manager if not done yet.
     * Must be called on the render thread.
     */
    public static void ensureTextureUploaded(ResourceLocation rl) {
        if (rl == null || UPLOADED_TEXTURES.containsKey(rl.toString())) {
            return;
        }
        byte[] data = TEXTURE_DATA.get(rl.toString());
        if (data == null) {
            return;
        }
        try {
            NativeImage image = decodeTexture(rl, data);
            if (image == null) {
                UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
                return;
            }
            // the GPU skinning path needs the model's translucency for its two-pass draw
            TEXTURE_TRANSLUCENT.put(rl.toString(), hasTranslucentPixels(image));
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to upload texture {}", rl, t);
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
        }
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

    /** One-time scan of the decoded texture for translucent pixels (alpha < 253). */
    private static boolean hasTranslucentPixels(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        // large textures: sample a strided grid instead of every pixel
        long pixels = (long) width * height;
        int stride = 1;
        if (pixels > 1_048_576) {
            stride = (int) Math.ceil(Math.sqrt(pixels / 262_144.0));
        }
        for (int y = 0; y < height; y += stride) {
            for (int x = 0; x < width; x += stride) {
                if (((image.getPixelRGBA(x, y) >>> 24) & 0xFF) < 253) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Decode texture bytes into a NativeImage, supporting PNG/JPEG encoded data
     * as well as raw RGBA pixels (legacy .ysm binary textures).
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
