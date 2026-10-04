package com.ysmef.compat.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.compat.YSMEpicFightCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Texture pipeline of the generated-mesh cache, extracted from the former
 * YSMMeshLibrary: raw texture bytes, decoded NativeImages, GL uploads
 * (with a per-frame time budget), delayed releases, translucency flags and the
 * pack/cache file layout ("textures/&lt;model&gt;/...", the texturecache dir
 * and the generated resource pack root). CPU image decoding lives in
 * {@link TextureDecoder}; this class owns the handoff to the upload queue.
 *
 * Threading contract (unchanged from the original): byte registration and
 * image DECODING run on the background decode pool; GL uploads and releases
 * run on the render thread (processPendingTextureUploads is drained with a
 * time budget; evicted textures/meshes are released a few ticks later so the
 * current frame's draws never use freed resources mid-frame).
 */
public final class TextureStore {

    private static final Path CONFIG_ROOT = Paths.get("config", "ysm_epicfight_compat");
    /** Root of the generated resource pack (meshes, runtime JSONs, textures). */
    public static final Path PACK_ROOT = CONFIG_ROOT.resolve("resourcepack");
    private static final Path PACK_META = PACK_ROOT.resolve("pack.mcmeta");
    private static final Path TEXTURE_CACHE_DIR = CONFIG_ROOT.resolve("texturecache");

    private static final String MESH_NAMESPACE = YSMEpicFightCompat.MODID;

    /** textureRL string -> original encoded texture bytes (registered into the texture manager on demand). */
    private static final Map<String, byte[]> TEXTURE_DATA = new ConcurrentHashMap<>();

    /** textureRL string -> [width, height, format] (format: -1=raw RGBA, 2=PNG, 3=JPEG, 4=WEBP, 5=AVIF). */
    private static final Map<String, int[]> TEXTURE_INFO = new ConcurrentHashMap<>();

    /**
     * modelId + '#' + textureName -> textureRL. Kept as a lock-guarded
     * LinkedHashMap: findTexture's fallback returns the model's first texture
     * and relies on insertion order. Every access must hold the map's monitor -
     * the render thread reads while worker threads register new models.
     */
    private static final Map<String, ResourceLocation> TEXTURE_LOCATIONS = new LinkedHashMap<>();

    /** textureRL string -> true once registered in the texture manager. */
    private static final Map<String, Boolean> UPLOADED_TEXTURES = new ConcurrentHashMap<>();

    /**
     * ModernYSM-style texture pipeline (see UploadManager): image decoding runs
     * on the background pool, and the GL uploads are drained on the render
     * thread with a small per-frame time budget, so large textures no longer
     * hitch the first draw. Texture releases are delayed a few ticks so a
     * texture still referenced by the current frame is never dropped mid-frame.
     */
    /** A new token is assigned whenever a texture's bytes are replaced. */
    private static final AtomicLong NEXT_TEXTURE_TOKEN = new AtomicLong();
    private static final Map<String, Long> TEXTURE_TOKENS = new ConcurrentHashMap<>();
    private static final Map<String, Long> PENDING_TEXTURE_DECODES = new ConcurrentHashMap<>();
    private static final java.util.Queue<TextureUploadTask> COMPLETED_UPLOADS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final Map<String, Integer> PENDING_RELEASES = new ConcurrentHashMap<>();
    private static final int RELEASE_DELAY_TICKS = 5;
    private static final long TEXTURE_UPLOAD_BUDGET_NANOS = 10_000_000L;

    /** textureRL string -> true when the texture has translucent pixels (alpha < 253). */
    private static final Map<String, Boolean> TEXTURE_TRANSLUCENT = new ConcurrentHashMap<>();

    private record TextureUploadTask(ResourceLocation location, NativeImage image,
                                     boolean translucent, long token) {}

    /** Background decode pool (image decoding is pure CPU work). */
    private static final ExecutorService DECODE_POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), runnable -> {
                Thread thread = new Thread(runnable, "ysm-ef-texture");
                thread.setDaemon(true);
                return thread;
            });

    private TextureStore() {}

    // ------------------------------------------------------------------
    // Public queries
    // ------------------------------------------------------------------

    /** The resource pack root that should be registered as a client resource pack. */
    public static Path getPackRoot() {
        return PACK_ROOT;
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
     * Resolve the texture resource location for the given model + texture name.
     * Falls back to the model's first texture when the name is unknown.
     */
    public static ResourceLocation findTexture(String modelId, String textureName) {
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

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

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
        registerBytes(rl.toString(), data);
        return rl;
    }

    private static void registerBytes(String key, byte[] data) {
        TEXTURE_DATA.put(key, data);
        TEXTURE_TOKENS.put(key, NEXT_TEXTURE_TOKEN.incrementAndGet());
        PENDING_TEXTURE_DECODES.remove(key);
        UPLOADED_TEXTURES.remove(key);
    }

    private static boolean isCurrentTexture(String key, long token) {
        return Long.valueOf(token).equals(TEXTURE_TOKENS.get(key));
    }

    /** Register one converted model's texture entry (locations/data/info tables). */
    public static void registerTexture(String modelId, String textureName, ResourceLocation location,
                                       byte[] data, int[] info) {
        synchronized (TEXTURE_LOCATIONS) {
            TEXTURE_LOCATIONS.put(modelId + "#" + textureName, location);
        }
        registerBytes(location.toString(), data);
        if (info != null) {
            TEXTURE_INFO.put(location.toString(), info);
        }
    }

    /**
     * Release every texture entry of one model (eviction): drop the registrations
     * and schedule the GL release a few ticks later (the texture may still be
     * referenced by the current frame's draws).
     *
     * @return the released resource locations (for logging)
     */
    public static List<ResourceLocation> releaseTexturesOfModel(String modelId) {
        List<ResourceLocation> toRelease = new java.util.ArrayList<>();
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
            TEXTURE_TOKENS.remove(key);
            PENDING_TEXTURE_DECODES.remove(key);
            TEXTURE_INFO.remove(key);
            UPLOADED_TEXTURES.remove(key);
            TEXTURE_TRANSLUCENT.remove(key);
            // delayed release: the texture may still be referenced by the current
            // frame's draws; release it a few ticks later (see processPendingTextureReleases)
            PENDING_RELEASES.put(key, RELEASE_DELAY_TICKS);
        }
        return toRelease;
    }

    /** Replace every texture registration with a full snapshot (generateAll). */
    public static void replaceAll(Map<String, ResourceLocation> locations,
                                  Map<String, byte[]> data, Map<String, int[]> info) {
        synchronized (TEXTURE_LOCATIONS) {
            TEXTURE_LOCATIONS.clear();
            TEXTURE_LOCATIONS.putAll(locations);
        }
        TEXTURE_DATA.clear();
        TEXTURE_DATA.putAll(data);
        TEXTURE_TOKENS.clear();
        PENDING_TEXTURE_DECODES.clear();
        closeQueuedUploads();
        for (String key : data.keySet()) {
            TEXTURE_TOKENS.put(key, NEXT_TEXTURE_TOKEN.incrementAndGet());
        }
        TEXTURE_INFO.clear();
        TEXTURE_INFO.putAll(info);
        UPLOADED_TEXTURES.clear();
    }

    /** Drop every texture registration and pending release (resource reload). */
    public static void invalidateAll() {
        java.util.Set<String> textureKeys = new java.util.HashSet<>(UPLOADED_TEXTURES.keySet());
        textureKeys.addAll(PENDING_RELEASES.keySet());
        TEXTURE_TOKENS.clear();
        TEXTURE_DATA.clear();
        TEXTURE_INFO.clear();
        synchronized (TEXTURE_LOCATIONS) {
            TEXTURE_LOCATIONS.clear();
        }
        UPLOADED_TEXTURES.clear();
        TEXTURE_TRANSLUCENT.clear();
        PENDING_TEXTURE_DECODES.clear();
        closeQueuedUploads();
        PENDING_RELEASES.clear();
        for (String key : textureKeys) {
            try {
                Minecraft.getInstance().getTextureManager().release(ResourceLocation.parse(key));
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to release texture {} on reload", key, t);
            }
        }
    }

    private static void closeQueuedUploads() {
        TextureUploadTask task;
        while ((task = COMPLETED_UPLOADS.poll()) != null) {
            try {
                task.image().close();
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to close decoded texture {}", task.location(), t);
            }
        }
    }

    /**
     * Delete generated-pack and texture-cache files not referenced by the keep
     * set (relative "textures/&lt;...&gt;.png" paths; generateAll cleanup).
     */
    public static void deleteStaleTextureFiles(Set<String> keepTexturePaths) {
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

    // ------------------------------------------------------------------
    // Upload pipeline
    // ------------------------------------------------------------------

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
        Long token = TEXTURE_TOKENS.get(key);
        if (token == null || TEXTURE_DATA.get(key) != data
                || PENDING_TEXTURE_DECODES.putIfAbsent(key, token) != null) {
            return;
        }
        // a re-upload cancels any delayed release of the same resource location
        PENDING_RELEASES.remove(key);
        DECODE_POOL.submit(() -> {
            NativeImage image = null;
            try {
                image = TextureDecoder.decodeTexture(rl, data, TEXTURE_INFO.get(key));
                if (image == null) {
                    if (isCurrentTexture(key, token)) {
                        UPLOADED_TEXTURES.put(key, Boolean.TRUE);
                    }
                } else {
                    // The translucency scan runs here (decode worker thread), not
                    // on the render thread: the per-frame upload drain has a small
                    // time budget and must not spend it scanning millions of pixels.
                    boolean translucent = TextureDecoder.hasTranslucentPixels(image);
                    if (isCurrentTexture(key, token)) {
                        COMPLETED_UPLOADS.add(new TextureUploadTask(rl, image, translucent, token));
                        image = null; // the upload queue now owns it
                        Minecraft.getInstance().execute(TextureStore::processPendingTextureUploads);
                    }
                }
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to decode texture {}", rl, t);
                if (isCurrentTexture(key, token)) {
                    UPLOADED_TEXTURES.put(key, Boolean.TRUE);
                }
            } finally {
                if (image != null) {
                    image.close();
                }
                PENDING_TEXTURE_DECODES.remove(key, token);
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
            if (!isCurrentTexture(key, task.token())) {
                task.image().close();
                continue;
            }
            PENDING_RELEASES.remove(key);
            DynamicTexture texture;
            try {
                texture = new DynamicTexture(task.image());
            } catch (Throwable t) {
                task.image().close();
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to create texture {}", task.location(), t);
                continue;
            }
            try {
                Minecraft.getInstance().getTextureManager().register(task.location(), texture);
            } catch (Throwable t) {
                texture.close();
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to upload texture {}", task.location(), t);
                continue;
            }
            TEXTURE_TRANSLUCENT.put(key, task.translucent());
            UPLOADED_TEXTURES.put(key, Boolean.TRUE);
            if (System.nanoTime() > deadline) {
                // leftover tasks stay queued: re-post the drain for the next frame
                Minecraft.getInstance().execute(TextureStore::processPendingTextureUploads);
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

    // ------------------------------------------------------------------
    // Pack/cache file layout
    // ------------------------------------------------------------------

    /**
     * The shared path prefix of every generated texture of a model
     * ("textures/<model>/"). RealCamera bind targets use it as the textureId
     * matcher: it matches all texture variants of the model (the UV layout is
     * per-model, the captured texture id is "...:textures/<model>/<tex>.png").
     */
    public static String textureIdPrefixOf(String modelId) {
        return "textures/" + sanitize(modelId) + "/";
    }

    /** Ensure the pack/cache skeleton exists (pack.mcmeta + cache dir). */
    public static void preparePackFolder() {
        try {
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

    private static ResourceLocation textureLocation(String modelId, String textureName) {
        return ResourceLocation.fromNamespaceAndPath(MESH_NAMESPACE,
                "textures/" + sanitize(modelId) + "/" + sanitize(textureName) + ".png");
    }

    private static Path textureCachePath(ResourceLocation rl) {
        return guardedResolve(TEXTURE_CACHE_DIR, TEXTURE_CACHE_DIR.resolve(rl.getNamespace()).resolve(rl.getPath()));
    }

    private static void writeTextureCache(ResourceLocation rl, byte[] data) {
        try {
            Path cacheFile = textureCachePath(rl);
            if (cacheFile == null) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: refused to write texture cache outside the cache root for {}", rl);
                return;
            }
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
    public static void writePackTexture(ResourceLocation rl, byte[] data, int[] info, boolean forceRewrite) {
        try {
            Path file = PACK_ROOT.resolve("assets").resolve(rl.getNamespace()).resolve(rl.getPath());
            Path guarded = guardedResolve(PACK_ROOT, file);
            if (guarded == null) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: refused to write pack texture outside the pack root for {}", rl);
                return;
            }
            file = guarded;
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
            NativeImage image = TextureDecoder.decodePackTexture(data, info);
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

    /** Cache + pack a converted model's texture files (conversion path). */
    public static void persistTexture(String modelId, String textureName, byte[] data, int[] info) {
        ResourceLocation rl = textureLocation(modelId, textureName);
        writeTextureCache(rl, data);
        writePackTexture(rl, data, info, false);
    }

    /** The resource location of a model's texture (conversion path). */
    public static ResourceLocation locationOf(String modelId, String textureName) {
        return textureLocation(modelId, textureName);
    }

    /**
     * The resource location of the model's default texture (mirrors the
     * fallback order used at render time).
     */
    public static String defaultTextureOf(String modelId, com.ysmef.compat.ysm.YsmModelPackage pkg) {
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

    /** Read one cached texture's bytes (cache-restore path), or null when absent/invalid. */
    public static byte[] readTextureCache(ResourceLocation rl) {
        Path cacheFile = textureCachePath(rl);
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            return Files.readAllBytes(cacheFile);
        } catch (IOException e) {
            return null;
        }
    }

    /** Verify one cached texture's bytes against the manifest hash/size. */
    public static boolean verifyTextureCache(ResourceLocation rl, long size, String hash) {
        Path cacheFile = textureCachePath(rl);
        if (cacheFile == null) {
            return false;
        }
        try {
            if (!Files.isRegularFile(cacheFile) || Files.size(cacheFile) != size) {
                return false;
            }
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(cacheFile))).equals(hash);
        } catch (Exception e) {
            // "The cache file could not be hashed" answers the same as "the hash differs" - the
            // texture is re-decoded, which is the right recovery - but an unreadable cache directory
            // then looks exactly like a normal cache miss on every texture, forever.
            YSMEpicFightCompat.LOGGER.debug(
                    "YSM-EF Compat: could not verify a cached texture, re-processing it: {}", e.toString());
            return false;
        }
    }

    /** Kept as the public format probe for existing callers. */
    public static boolean isRiffWebp(byte[] data) {
        return TextureDecoder.isRiffWebp(data);
    }

    /** Kept as the public format probe for existing callers. */
    public static boolean isFtypAvif(byte[] data) {
        return TextureDecoder.isFtypAvif(data);
    }

    // ------------------------------------------------------------------
    // Path safety
    // ------------------------------------------------------------------

    /**
     * Path-traversal defense: model ids are relative paths ("group/model") and
     * texture names may contain dots, so '.' and '/' are kept - but any ".." or
     * lone "." segment is rewritten to '_'. ResourceLocation validation does
     * NOT block ".." and Files.resolve resolves it for real, so without this an
     * untrusted .ysm model package could write/read anywhere under the game
     * directory through the generated pack / cache paths.
     */
    public static String sanitize(String value) {
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
        // Collapse empty segments (leading/doubled slashes, i.e. no
        // absolute-path form) and neutralize traversal segments.
        String[] segments = sb.toString().split("/");
        sb.setLength(0);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.equals("..") || segment.equals(".")) {
                sb.append('_');
            } else {
                sb.append(segment);
            }
            sb.append('/');
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * Return {@code candidate} normalized only when it stays inside
     * {@code root}; null otherwise. Defense in depth for the file sites fed by
     * resource locations (sanitize() already blocks traversal segments; this
     * catches any future caller that forgets).
     */
    private static Path guardedResolve(Path root, Path candidate) {
        Path normalized = candidate.normalize();
        return normalized.startsWith(root.normalize()) ? normalized : null;
    }
}
