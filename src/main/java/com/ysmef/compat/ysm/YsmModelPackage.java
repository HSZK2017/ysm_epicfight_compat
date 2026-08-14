package com.ysmef.compat.ysm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.YSMGeoModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Locates and reads YSM model packages (2.6.5 layout) from the local Yes Steve
 * Model folders, without any dependency on YSM's (obfuscated) runtime classes.
 *
 * Models live under config/yes_steve_model/{built,custom,auth} in two forms:
 * - directory package:  &lt;group&gt;/&lt;model&gt;/ysm.json   (manifest + plain files)
 * - binary package:     &lt;path&gt;.ysm                        (encrypted, see YsmFileCrypto)
 *
 * The model id used by YSM's capability is the relative path of the package
 * (directory packages have no extension; binary packages keep the ".ysm" suffix).
 */
public final class YsmModelPackage {

    private static final Path YSM_CONFIG = Paths.get("config", "yes_steve_model");
    private static final String[] ROOTS = {"builtin", "built", "custom", "auth"};

    public final String modelId;
    public final YSMGeoModel geometry;
    public final Map<String, byte[]> textures;
    public final Map<String, int[]> textureInfo;
    public final Map<String, com.ysmef.compat.ysm.script.ScriptAnim> scriptAnims;
    public final float widthScale;
    public final float heightScale;
    public final String defaultTexture;
    /** Precomputed content fingerprint of binary packages (see contentFingerprint), -1 for folder packages. */
    public final long contentFingerprint;

    private YsmModelPackage(String modelId, YSMGeoModel geometry, Map<String, byte[]> textures,
                            Map<String, int[]> textureInfo, float widthScale, float heightScale, String defaultTexture) {
        this(modelId, geometry, textures, textureInfo, java.util.Collections.emptyMap(), widthScale, heightScale, defaultTexture, -1L);
    }

    private YsmModelPackage(String modelId, YSMGeoModel geometry, Map<String, byte[]> textures,
                            Map<String, int[]> textureInfo, Map<String, com.ysmef.compat.ysm.script.ScriptAnim> scriptAnims,
                            float widthScale, float heightScale, String defaultTexture) {
        this(modelId, geometry, textures, textureInfo, scriptAnims, widthScale, heightScale, defaultTexture, -1L);
    }

    private YsmModelPackage(String modelId, YSMGeoModel geometry, Map<String, byte[]> textures,
                            Map<String, int[]> textureInfo, Map<String, com.ysmef.compat.ysm.script.ScriptAnim> scriptAnims,
                            float widthScale, float heightScale, String defaultTexture, long contentFingerprint) {
        this.modelId = modelId;
        this.geometry = geometry;
        this.textures = textures;
        this.textureInfo = textureInfo;
        this.scriptAnims = scriptAnims;
        this.widthScale = widthScale;
        this.heightScale = heightScale;
        this.defaultTexture = defaultTexture;
        this.contentFingerprint = contentFingerprint;
    }

    /**
     * Load the package for the given YSM model id, or null if unavailable locally.
     */
    public static YsmModelPackage load(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }
        try {
            if (modelId.endsWith(".ysm")) {
                return loadBinary(modelId);
            }
            return loadFolder(modelId);
        } catch (Exception e) {
            // Previously silent: every failure (corrupted file hash, truncated
            // package, buffer underflow in the parser, ...) surfaced only as
            // "model unavailable" with no hint at the cause.
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: failed to load YSM model package '{}': {}", modelId, e.toString());
            return null;
        }
    }

    private static YsmModelPackage loadFolder(String modelId) throws IOException {
        for (String root : ROOTS) {
            Path modelDir = YSM_CONFIG.resolve(root).resolve(modelId);
            Path manifest = modelDir.resolve("ysm.json");
            if (!Files.isRegularFile(manifest)) {
                continue;
            }
            JsonObject json = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();

            float widthScale = 0.7f;
            float heightScale = 0.7f;
            String defaultTexture = "";
            if (json.has("properties")) {
                JsonObject props = json.getAsJsonObject("properties");
                widthScale = props.has("width_scale") ? props.get("width_scale").getAsFloat() : 0.7f;
                heightScale = props.has("height_scale") ? props.get("height_scale").getAsFloat() : 0.7f;
                defaultTexture = props.has("default_texture") ? props.get("default_texture").getAsString() : "";
            }

            YSMGeoModel geometry = null;
            Map<String, byte[]> textures = new LinkedHashMap<>();
            Map<String, com.ysmef.compat.ysm.script.ScriptAnim> scriptAnims = new LinkedHashMap<>();
            if (json.has("files")) {
                JsonObject files = json.getAsJsonObject("files");
                if (files.has("player")) {
                    JsonObject player = files.getAsJsonObject("player");
                    if (player.has("model")) {
                        JsonObject modelObj = player.getAsJsonObject("model");
                        if (modelObj.has("main")) {
                            Path geoPath = modelDir.resolve(modelObj.get("main").getAsString());
                            if (Files.isRegularFile(geoPath)) {
                                geometry = YSMGeoModel.parse(Files.readString(geoPath, StandardCharsets.UTF_8));
                            }
                        }
                    }
                    if (player.has("animation")) {
                        JsonObject animObj = player.getAsJsonObject("animation");
                        for (Map.Entry<String, JsonElement> entry : animObj.entrySet()) {
                            Path animPath = modelDir.resolve(entry.getValue().getAsString());
                            if (Files.isRegularFile(animPath)) {
                                loadScriptAnims(animPath, scriptAnims);
                            }
                        }
                    }
                    if (player.has("texture")) {
                        JsonElement texElem = player.get("texture");
                        Iterable<JsonElement> texArr = texElem.isJsonArray()
                                ? texElem.getAsJsonArray()
                                : java.util.Collections.singletonList(texElem);
                        for (JsonElement elem : texArr) {
                            String texPath = null;
                            if (elem.isJsonPrimitive()) {
                                texPath = elem.getAsString();
                            } else if (elem.isJsonObject() && elem.getAsJsonObject().has("uv")) {
                                texPath = elem.getAsJsonObject().get("uv").getAsString();
                            }
                            if (texPath == null) {
                                continue;
                            }
                            Path texFile = modelDir.resolve(texPath);
                            if (Files.isRegularFile(texFile)) {
                                textures.put(extractFileName(texPath), Files.readAllBytes(texFile));
                            }
                        }
                    }
                }
            }

            if (geometry != null) {
                return new YsmModelPackage(modelId, geometry, textures, java.util.Collections.emptyMap(), scriptAnims,
                        widthScale, heightScale, defaultTexture);
            }
        }
        return null;
    }

    /**
     * Reads one Bedrock .animation.json file and merges the animations relevant to
     * the Epic Fight compat runtime (see ScriptJson.isRuntimeRelevant).
     */
    private static void loadScriptAnims(Path animPath, Map<String, com.ysmef.compat.ysm.script.ScriptAnim> out) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(animPath, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject anims = root.has("animations") ? root.getAsJsonObject("animations") : null;
            if (anims == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : anims.entrySet()) {
                if (com.ysmef.compat.ysm.script.ScriptJson.isRuntimeRelevant(entry.getKey())) {
                    out.put(entry.getKey(), com.ysmef.compat.ysm.script.ScriptJson.fromBedrock(
                            entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            // One broken animation file must not abort the whole package, but it
            // must not be invisible either (it silently dropped every remaining
            // animation of the file before).
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: failed to parse animation file '{}': {}", animPath.getFileName(), e.toString());
        }
    }

    private static YsmModelPackage loadBinary(String modelId) throws IOException {
        for (String root : ROOTS) {
            Path ysmFile = YSM_CONFIG.resolve(root).resolve(modelId);
            if (!Files.isRegularFile(ysmFile)) {
                continue;
            }
            byte[] decrypted = YsmFileCrypto.decryptYsmFile(Files.readAllBytes(ysmFile));
            YsmBinaryReader.BinaryModel binary = YsmBinaryReader.read(decrypted);
            YSMGeoModel geometry = YSMGeoModel.fromBinary(binary);
            // Compute the content fingerprint here while the decrypted payload
            // is still in hand; the conversion caller needs it for the manifest
            // and would otherwise decrypt the whole package a second time.
            long contentFingerprint = contentFingerprintOfBinary(root, modelId, decrypted);
            return new YsmModelPackage(modelId, geometry, binary.textures, binary.textureInfo, binary.animations,
                    binary.widthScale, binary.heightScale, binary.defaultTexture, contentFingerprint);
        }
        return null;
    }

    /**
     * Scan all locally available model ids (used to pre-generate base meshes).
     */
    public static Map<String, Boolean> scanAvailableModels() {
        Map<String, Boolean> models = new LinkedHashMap<>();
        for (String root : ROOTS) {
            Path rootPath = YSM_CONFIG.resolve(root);
            if (!Files.isDirectory(rootPath)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.forEach(path -> {
                    String fileName = path.getFileName().toString();
                    if (fileName.equals("ysm.json")) {
                        String rel = rootPath.relativize(path.getParent()).toString().replace('\\', '/');
                        if (!rel.isEmpty()) {
                            models.put(rel, Boolean.FALSE);
                        }
                    } else if (fileName.endsWith(".ysm") && Files.isRegularFile(path)) {
                        String rel = rootPath.relativize(path).toString().replace('\\', '/');
                        models.put(rel, Boolean.TRUE);
                    }
                });
            } catch (IOException ignored) {
            }
        }
        return models;
    }

    private static String extractFileName(String fullPath) {
        String name = fullPath;
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx >= 0) {
            name = name.substring(0, dotIdx);
        }
        return name;
    }

    /**
     * Cheap fingerprint of the model's source files (FNV-1a 64 over relative
     * paths, sizes and modification times). Fast (metadata only), but NOT
     * stable: YSM re-writes/re-extracts model files at startup in several
     * situations (models bundled by other mods, auth cache refreshes), which
     * bumps mtimes without changing any content. Always confirm a mismatch
     * with contentFingerprint before regenerating.
     *
     * @return the fingerprint, or -1 if the package no longer exists locally
     */
    public static long fingerprint(String modelId) {
        try {
            long hash = 0xcbf29ce484222325L;
            boolean found = false;
            for (String root : ROOTS) {
                Path base = YSM_CONFIG.resolve(root).resolve(modelId);
                if (modelId.endsWith(".ysm")) {
                    if (Files.isRegularFile(base)) {
                        hash = fnv1a(hash, root + '/' + modelId);
                        hash = fnv1a(hash, Long.toString(Files.size(base)));
                        hash = fnv1a(hash, Files.getLastModifiedTime(base).toString());
                        found = true;
                        break;
                    }
                    continue;
                }
                if (Files.isDirectory(base)) {
                    List<String> entries = new ArrayList<>();
                    try (Stream<Path> stream = Files.walk(base)) {
                        stream.filter(Files::isRegularFile).forEach(path -> {
                            String rel = base.relativize(path).toString().replace('\\', '/');
                            try {
                                entries.add(rel + ':' + Files.size(path) + ':' + Files.getLastModifiedTime(path).toMillis());
                            } catch (IOException ignored) {
                            }
                        });
                    }
                    Collections.sort(entries);
                    for (String entry : entries) {
                        hash = fnv1a(hash, entry);
                    }
                    found = true;
                    break;
                }
            }
            return found ? hash : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    private static long contentFingerprintOfBinary(String root, String modelId, byte[] decrypted) {
        long hash = 0xcbf29ce484222325L;
        hash = fnv1a(hash, root + '/' + modelId);
        hash = fnv1a(hash, Long.toString(decrypted.length));
        return fnv1aBytes(hash, decrypted);
    }

    /**
     * Content-based fingerprint of the model's source files (FNV-1a 64 over
     * relative paths and file contents; binary .ysm packages are decrypted
     * first, so re-encryption with a fresh key/iv still yields the same value).
     * Stable across mtime refreshes and spurious rewrites — a mismatch means
     * the model really changed (including a "/ysm model reload" refresh).
     *
     * Slower than fingerprint(): reads (and for .ysm decrypts) every file, so
     * use it only to confirm cheap-fingerprint mismatches. YsmModelPackage
     * instances loaded from binary packages carry this value precomputed (see
     * the contentFingerprint field) so conversions do not decrypt twice.
     *
     * @return the fingerprint, or -1 if the package no longer exists locally
     */
    public static long contentFingerprint(String modelId) {
        try {
            for (String root : ROOTS) {
                Path base = YSM_CONFIG.resolve(root).resolve(modelId);
                if (modelId.endsWith(".ysm")) {
                    if (Files.isRegularFile(base)) {
                        return contentFingerprintOfBinary(root, modelId,
                                YsmFileCrypto.decryptYsmFile(Files.readAllBytes(base)));
                    }
                    continue;
                }
                if (Files.isDirectory(base)) {
                    List<Path> files = new ArrayList<>();
                    try (Stream<Path> stream = Files.walk(base)) {
                        stream.filter(Files::isRegularFile).forEach(files::add);
                    }
                    files.sort(java.util.Comparator.comparing(
                            path -> base.relativize(path).toString().replace('\\', '/')));
                    long hash = 0xcbf29ce484222325L;
                    for (Path file : files) {
                        String rel = base.relativize(file).toString().replace('\\', '/');
                        byte[] data = Files.readAllBytes(file);
                        hash = fnv1a(hash, rel);
                        hash = fnv1a(hash, Long.toString(data.length));
                        hash = fnv1aBytes(hash, data);
                    }
                    return hash;
                }
            }
            return -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static long fnv1aBytes(long hash, byte[] data) {
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long fnv1a(long hash, String value) {
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
