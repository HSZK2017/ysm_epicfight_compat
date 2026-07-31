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
import java.util.LinkedHashMap;
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

    private YsmModelPackage(String modelId, YSMGeoModel geometry, Map<String, byte[]> textures,
                            Map<String, int[]> textureInfo, float widthScale, float heightScale, String defaultTexture) {
        this(modelId, geometry, textures, textureInfo, java.util.Collections.emptyMap(), widthScale, heightScale, defaultTexture);
    }

    private YsmModelPackage(String modelId, YSMGeoModel geometry, Map<String, byte[]> textures,
                            Map<String, int[]> textureInfo, Map<String, com.ysmef.compat.ysm.script.ScriptAnim> scriptAnims,
                            float widthScale, float heightScale, String defaultTexture) {
        this.modelId = modelId;
        this.geometry = geometry;
        this.textures = textures;
        this.textureInfo = textureInfo;
        this.scriptAnims = scriptAnims;
        this.widthScale = widthScale;
        this.heightScale = heightScale;
        this.defaultTexture = defaultTexture;
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
        } catch (Exception ignored) {
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
            return new YsmModelPackage(modelId, geometry, binary.textures, binary.textureInfo, binary.animations,
                    binary.widthScale, binary.heightScale, binary.defaultTexture);
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
}
