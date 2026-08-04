package com.ysmef.compat.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmModelPackage;
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
 * At client setup, every locally available YSM model package is converted into
 * an Epic Fight animmodels mesh JSON and written into a generated resource pack
 * at config/ysm_epicfight_compat/resourcepack (registered as a client resource
 * pack, see YSMCompatClientEvents). Each converted mesh is then registered in
 * Epic Fight's Meshes registry through a MeshAccessor, the same mechanism the
 * EpicFight_TouhouLittleMaid compat mod uses for its wine_fox model.
 *
 * Generation gating (manifest.json + per-model source fingerprints + output
 * integrity hashes):
 * - if the config folder, the manifest or any mesh file is missing, if the
 *   generator version changed, or if any YSM model was added/removed/modified
 *   (e.g. via "/ysm model reload"), ALL models are re-converted in parallel on
 *   every available CPU core, and the caller blocks until conversion finishes,
 *   so the game cannot reach the main menu with half-generated meshes
 * - every converted output (mesh JSON, runtime JSON, cached texture bytes) is
 *   hashed into the manifest at generation time; before restoring the cache
 *   the hashes are re-verified on disk, so a broken/stale cache (e.g. files
 *   copied mid-write or partially overwritten) forces a full regeneration
 *   instead of being trusted forever (the cause of permanently missing faces)
 * - otherwise the previous results (mesh accessors + cached texture bytes) are
 *   loaded back without decrypting any model package
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

    private static volatile boolean generated = false;

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
     * Whether the generated meshes are missing or out of date and a full
     * regeneration is required. Checks, in cheap-to-expensive order:
     * the config folder, the manifest, the generator version, the set of
     * locally available YSM models, per-model source fingerprints (picks up
     * "/ysm model reload" changes) and the presence of each mesh JSON.
     *
     * Fingerprints are two-tiered: a cheap metadata sig (paths/sizes/mtimes)
     * guards the common case, and any mismatch is confirmed against the
     * content sig (file contents / decrypted .ysm payload) before regenerating.
     * If only the metadata changed — YSM re-writes or re-encrypts model files
     * at startup in several situations (models bundled by other mods, auth
     * cache refreshes) — the manifest's cheap sigs are refreshed in place and
     * no regeneration happens.
     *
     * The manifest must also carry the output integrity hashes (mhash/rhash
     * per model, hash per texture, see verifyOutputs); their absence means the
     * cache predates hash tracking and a full regeneration is forced. The
     * byte-level verification itself runs on the cache-restore path
     * (loadFromCache), keeping this gate free of file hashing.
     */
    public static boolean needsGeneration() {
        try {
            if (!Files.isDirectory(MESH_DIR) || !Files.isRegularFile(MANIFEST)) {
                return true;
            }
            JsonObject manifest = JsonParser.parseString(Files.readString(MANIFEST, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!manifest.has("generator") || manifest.get("generator").getAsInt() != GENERATOR_VERSION
                    || !manifest.has("models") || !manifest.get("models").isJsonObject()) {
                return true;
            }
            JsonObject manifestModels = manifest.getAsJsonObject("models");
            Map<String, Boolean> scanned = YsmModelPackage.scanAvailableModels();
            Set<String> manifestIds = new HashSet<>();
            for (Map.Entry<String, JsonElement> entry : manifestModels.entrySet()) {
                manifestIds.add(entry.getKey());
            }
            if (!manifestIds.equals(scanned.keySet())) {
                return true;
            }
            boolean manifestTouched = false;
            for (Map.Entry<String, JsonElement> entry : manifestModels.entrySet()) {
                String modelId = entry.getKey();
                JsonObject modelEntry = entry.getValue().getAsJsonObject();
                if (!modelEntry.has("sig") || !modelEntry.has("csig") || !modelEntry.has("mesh")
                        || !modelEntry.has("mhash") || !modelEntry.has("msize")
                        || !modelEntry.has("rhash") || !modelEntry.has("rsize")) {
                    return true;
                }
                if (!Files.isRegularFile(MESH_DIR.resolve(modelEntry.get("mesh").getAsString() + ".json"))) {
                    return true;
                }
                if (modelEntry.has("textures") && modelEntry.get("textures").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> texEntry : modelEntry.getAsJsonObject("textures").entrySet()) {
                        JsonObject tex = texEntry.getValue().getAsJsonObject();
                        if (!tex.has("rl") || !tex.has("hash") || !tex.has("size")) {
                            return true;
                        }
                    }
                }
                if (modelEntry.get("sig").getAsLong() != YsmModelPackage.fingerprint(modelId)) {
                    long contentFingerprint = YsmModelPackage.contentFingerprint(modelId);
                    if (contentFingerprint == -1L
                            || contentFingerprint != modelEntry.get("csig").getAsLong()) {
                        return true;
                    }
                    long refreshed = YsmModelPackage.fingerprint(modelId);
                    if (refreshed != -1L) {
                        modelEntry.addProperty("sig", refreshed);
                        manifestTouched = true;
                    }
                }
            }
            if (manifestTouched) {
                writeManifest(manifestModels);
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: YSM model files were rewritten without content changes (mtime/encryption refresh), updated manifest signatures; no regeneration needed");
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Startup gate: if anything is missing or outdated, re-convert ALL models in
     * parallel and block until every conversion has finished; otherwise restore
     * the previous results from the manifest + texture cache. Safe to call on
     * every resource reload — it is a no-op while everything is up to date.
     */
    public static synchronized void ensureGeneratedBlocking() {
        if (needsGeneration()) {
            YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: generated meshes missing or outdated, converting all YSM models (blocking until done)");
            generateAll();
        } else if (!generated) {
            loadFromCache();
        }
    }

    /**
     * Restore previously generated results without touching the (encrypted)
     * model packages: mesh accessors come from the manifest, texture bytes are
     * read back from the texture cache written during the last generation.
     *
     * Before trusting the cache, every recorded output is re-verified on disk
     * (see verifyOutputs); a cache that fails the check — e.g. files copied
     * mid-write, partially overwritten or truncated, which produce permanently
     * missing faces — is discarded and a full regeneration is forced.
     */
    private static void loadFromCache() {
        long start = System.nanoTime();
        preparePackFolder();

        MESHES.clear();
        TEXTURE_DATA.clear();
        TEXTURE_INFO.clear();
        TEXTURE_LOCATIONS.clear();
        UPLOADED_TEXTURES.clear();

        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(MANIFEST, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject manifestModels = manifest.getAsJsonObject("models");
            if (!verifyOutputs(manifestModels)) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: cached outputs failed integrity verification (missing or corrupted mesh/runtime/texture files), forcing full regeneration");
                generateAll();
                return;
            }
            int textures = 0;
            for (Map.Entry<String, JsonElement> entry : manifestModels.entrySet()) {
                String modelId = entry.getKey();
                JsonObject modelEntry = entry.getValue().getAsJsonObject();
                String meshId = modelEntry.get("mesh").getAsString();

                Meshes.MeshAccessor<YSMMesh> accessor = Meshes.MeshAccessor.create(
                        MESH_NAMESPACE, "entity/" + meshId,
                        (loader) -> loader.loadSkinnedMesh(YSMMesh::new));
                MESHES.put(modelId, accessor);

                if (!modelEntry.has("textures") || !modelEntry.get("textures").isJsonObject()) {
                    continue;
                }
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
                        textures++;
                    }
                }
            }
            generated = true;
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: all {} YSM models already converted, restored meshes and {} textures from cache in {} ms (no recompute needed)",
                    MESHES.size(), textures, (System.nanoTime() - start) / 1_000_000L);
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to load generated meshes from cache, forcing regeneration", e);
            generated = false;
            generateAll();
        }
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

        generated = true;
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

    /**
     * Verify that every generated output recorded in the manifest (mesh JSON,
     * runtime JSON and cached texture bytes) still exists on disk and is
     * byte-identical to what generation produced. Size is checked first (a
     * truncated or partial file almost always changes size), then the content
     * hash. Returns false when anything is missing or corrupted, forcing a
     * full regeneration instead of restoring a stale/broken cache.
     */
    private static boolean verifyOutputs(JsonObject manifestModels) {
        try {
            for (Map.Entry<String, JsonElement> entry : manifestModels.entrySet()) {
                JsonObject modelEntry = entry.getValue().getAsJsonObject();
                if (!modelEntry.has("mesh") || !modelEntry.has("mhash") || !modelEntry.has("msize")
                        || !modelEntry.has("rhash") || !modelEntry.has("rsize")) {
                    return false;
                }
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
     * Find the generated mesh accessor for the given YSM model id.
     */
    public static Meshes.MeshAccessor<YSMMesh> findMesh(String modelId) {
        return MESHES.get(modelId);
    }

    /**
     * Resolve the texture resource location for the given model + texture name.
     * Falls back to the model's first texture when the name is unknown.
     */
    public static ResourceLocation findTexture(String modelId, String textureName) {
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
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to upload texture {}", rl, t);
            UPLOADED_TEXTURES.put(rl.toString(), Boolean.TRUE);
        }
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
        return generated;
    }

    public static int meshCount() {
        return MESHES.size();
    }

    /**
     * The model ids that have a generated base mesh (for diagnostics).
     */
    public static java.util.Set<String> availableModelIds() {
        return java.util.Collections.unmodifiableSet(MESHES.keySet());
    }
}
