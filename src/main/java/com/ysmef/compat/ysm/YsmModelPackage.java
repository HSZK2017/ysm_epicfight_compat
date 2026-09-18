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

    private static final Path YSM_CONFIG = configRoot();
    private static final String[] ROOTS = {"builtin", "built", "custom", "auth"};

    /**
     * YSM's config root - where the game reads its model packages from.
     *
     * <p>The override exists for the offline forensics runs, which read and convert a real
     * install's packages without launching the game; nothing in the mod sets it, and with it unset
     * this is the path YSM itself uses. Two spellings, because the path to a Minecraft instance
     * usually contains spaces: {@code -Dysmef.golden.ysm_config_root=<path>} and, for the Gradle
     * test runs that cannot pass a system property with spaces through, the
     * {@code YSMEF_YSM_CONFIG_ROOT} environment variable.
     */
    private static Path configRoot() {
        String override = System.getProperty(CONFIG_ROOT_PROPERTY, "");
        if (override.isEmpty()) {
            String fromEnvironment = System.getenv(CONFIG_ROOT_ENV);
            override = fromEnvironment == null ? "" : fromEnvironment;
        }
        return override.isEmpty() ? Paths.get("config", "yes_steve_model") : Paths.get(override);
    }

    /** System property naming the config root; see {@link #configRoot()}. */
    public static final String CONFIG_ROOT_PROPERTY = "ysmef.golden.ysm_config_root";
    /** Environment variable naming the config root; see {@link #configRoot()}. */
    public static final String CONFIG_ROOT_ENV = "YSMEF_YSM_CONFIG_ROOT";

    /**
     * Upper bound for a single source file (encrypted package, JSON or texture).
     * Model ids arrive from server sync / player NBT and must be treated as
     * untrusted; this also stops one absurdly large file from exhausting the heap.
     * Overridable with the ysm_ef_compat.max_package_bytes system property for
     * unusually large (but trusted) packages.
     */
    private static final long MAX_PACKAGE_FILE_BYTES = Math.max(1L,
            Long.getLong("ysm_ef_compat.max_package_bytes", 512L * 1024L * 1024L).longValue());

    /**
     * Convert an untrusted YSM model id into a safe, strictly relative path under
     * the YSM config roots, or null when it is not a usable relative path.
     * Rejects absolute paths, drive/UNC forms, ':' (Windows drive-relative
     * oddities), '..' segments and any path that normalizes above the root.
     */
    static Path relativeModelPath(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }
        if (modelId.indexOf('\0') >= 0 || modelId.indexOf(':') >= 0) {
            return null;
        }
        String forward = modelId.replace('\\', '/');
        if (forward.startsWith("/")) {
            return null;
        }
        for (String segment : forward.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        Path path = Paths.get(forward);
        if (path.isAbsolute()) {
            return null;
        }
        Path normalized = path.normalize();
        if (normalized.isAbsolute()) {
            return null;
        }
        String text = normalized.toString().replace('\\', '/');
        if (text.equals(".") || text.equals("..") || text.startsWith("../")) {
            return null;
        }
        return normalized;
    }

    /**
     * Resolve a manifest-declared child path strictly inside {@code root}; null
     * when the child is absolute, escapes via '..', or is otherwise unusable.
     */
    private static Path resolveInside(Path root, String child) {
        if (child == null || child.isEmpty()) {
            return null;
        }
        Path rootNorm = root.toAbsolutePath().normalize();
        Path resolved = root.resolve(child.replace('\\', '/')).normalize().toAbsolutePath();
        return resolved.startsWith(rootNorm) ? resolved : null;
    }

    /**
     * Whether {@code path} really resides under {@code root} after resolving
     * symlinks. The lexical checks above are not enough: a symlink planted in a
     * model package can point outside the package directory.
     */
    private static boolean isInsideReal(Path path, Path root) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isRegularFileInside(Path file, Path root) {
        return Files.isRegularFile(file) && isInsideReal(file, root);
    }

    private static boolean isDirectoryInside(Path directory, Path root) {
        return Files.isDirectory(directory) && isInsideReal(directory, root);
    }

    /**
     * Whether the model id currently exists locally. Unlike
     * {@link #scanAvailableModels()} this only stats the four possible package
     * paths, so it is safe to call from the render thread for a single model.
     */
    public static boolean existsLocally(String modelId) {
        Path relative = relativeModelPath(modelId);
        if (relative == null) {
            return false;
        }
        for (String root : ROOTS) {
            Path rootPath = YSM_CONFIG.resolve(root).normalize();
            Path candidate = rootPath.resolve(relative).normalize();
            boolean exists = modelId.endsWith(".ysm")
                    ? isRegularFileInside(candidate, rootPath)
                    : isDirectoryInside(candidate, rootPath);
            if (exists) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAllBytesBounded(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_PACKAGE_FILE_BYTES) {
            throw new IOException("package file too large: " + size + " bytes (max " + MAX_PACKAGE_FILE_BYTES + ")");
        }
        try (java.io.InputStream in = Files.newInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                    (int) Math.min(size, 1L << 20));
            byte[] buffer = new byte[65536];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_PACKAGE_FILE_BYTES) {
                    throw new IOException(
                            "package file grew beyond the " + MAX_PACKAGE_FILE_BYTES + " byte safety limit while reading");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String readStringBounded(Path file) throws IOException {
        return new String(readAllBytesBounded(file), StandardCharsets.UTF_8);
    }

    public final String modelId;
    public final YSMGeoModel geometry;
    public final Map<String, byte[]> textures;
    public final Map<String, int[]> textureInfo;
    /** Runtime-relevant animations (parallel loops, locomotion states, hold/use overlays). */
    public final Map<String, com.ysmef.compat.ysm.script.ScriptAnim> scriptAnims;
    /** Every parsed animation of the package, including the wheel-selectable extra animations. */
    public final Map<String, com.ysmef.compat.ysm.script.ScriptAnim> allScriptAnims;
    /**
     * Animation controllers of the package: controller name -&gt; the animations its
     * states play. This is where a YSM author binds the model's physics animation
     * (the bundled default controller plays {@code Hair_Physics} from
     * {@code player.pre_parallel_0}), which is how the physics-driven bones are found
     * without guessing from their names - see
     * {@link com.ysmef.compat.model.runtime.YsmPhysicsBinding}.
     */
    public final Map<String, List<String>> animationControllers;
    /**
     * Wheel-selectable extra animations declared by the model properties:
     * animation name -> description (often empty). Entries whose key starts
     * with '#' are wheel sub-menus, not animations.
     */
    public final Map<String, String> extraAnimations;
    public final float widthScale;
    public final float heightScale;
    public final String defaultTexture;
    /** Precomputed content fingerprint of binary packages (see contentFingerprint), -1 for folder packages. */
    public final long contentFingerprint;

    private YsmModelPackage(String modelId, YSMGeoModel geometry, Map<String, byte[]> textures,
                            Map<String, int[]> textureInfo, float widthScale, float heightScale, String defaultTexture) {
        this(modelId, geometry, textures, textureInfo, java.util.Collections.emptyMap(), java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(), java.util.Collections.emptyMap(),
                widthScale, heightScale, defaultTexture, -1L);
    }

    private YsmModelPackage(String modelId, YSMGeoModel geometry, Map<String, byte[]> textures,
                            Map<String, int[]> textureInfo, Map<String, com.ysmef.compat.ysm.script.ScriptAnim> scriptAnims,
                            float widthScale, float heightScale, String defaultTexture) {
        this(modelId, geometry, textures, textureInfo, scriptAnims, scriptAnims, java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(), widthScale, heightScale, defaultTexture, -1L);
    }

    private YsmModelPackage(String modelId, YSMGeoModel geometry, Map<String, byte[]> textures,
                            Map<String, int[]> textureInfo, Map<String, com.ysmef.compat.ysm.script.ScriptAnim> scriptAnims,
                            Map<String, com.ysmef.compat.ysm.script.ScriptAnim> allScriptAnims,
                            Map<String, String> extraAnimations,
                            Map<String, List<String>> animationControllers,
                            float widthScale, float heightScale, String defaultTexture, long contentFingerprint) {
        this.modelId = modelId;
        this.geometry = geometry;
        this.textures = textures;
        this.textureInfo = textureInfo;
        this.scriptAnims = scriptAnims;
        this.allScriptAnims = allScriptAnims;
        this.extraAnimations = extraAnimations;
        this.animationControllers = animationControllers;
        this.widthScale = widthScale;
        this.heightScale = heightScale;
        this.defaultTexture = defaultTexture;
        this.contentFingerprint = contentFingerprint;
    }

    /**
     * The animation data for a wheel-selectable animation name, or null when the
     * package has no animation with that name.
     */
    public com.ysmef.compat.ysm.script.ScriptAnim wheelAnim(String animationName) {
        return animationName == null ? null : allScriptAnims.get(animationName);
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
        } catch (StackOverflowError e) {
            // Last-resort guard for a maliciously deep JSON structure that slips
            // through the parser's own depth limits (Gson itself recurses).
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: YSM model package '{}' is nested too deeply and was rejected", modelId);
            return null;
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
        Path safeModel = relativeModelPath(modelId);
        if (safeModel == null) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: refusing to load YSM model package with unsafe id '{}'", modelId);
            return null;
        }
        for (String root : ROOTS) {
            Path rootPath = YSM_CONFIG.resolve(root).normalize();
            Path modelDir = rootPath.resolve(safeModel).normalize();
            if (!isDirectoryInside(modelDir, rootPath)) {
                continue;
            }
            Path manifest = modelDir.resolve("ysm.json");
            if (!isRegularFileInside(manifest, modelDir)) {
                continue;
            }
            JsonObject json = JsonParser.parseString(readStringBounded(manifest)).getAsJsonObject();

            float widthScale = 0.7f;
            float heightScale = 0.7f;
            String defaultTexture = "";
            Map<String, String> extraAnimations = new LinkedHashMap<>();
            if (json.has("properties")) {
                JsonObject props = json.getAsJsonObject("properties");
                widthScale = props.has("width_scale") ? props.get("width_scale").getAsFloat() : 0.7f;
                heightScale = props.has("height_scale") ? props.get("height_scale").getAsFloat() : 0.7f;
                defaultTexture = props.has("default_texture") ? props.get("default_texture").getAsString() : "";
                if (props.has("extra_animation") && props.get("extra_animation").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : props.getAsJsonObject("extra_animation").entrySet()) {
                        extraAnimations.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                if (props.has("extra_animation_classify") && props.get("extra_animation_classify").isJsonArray()) {
                    for (JsonElement elem : props.getAsJsonArray("extra_animation_classify")) {
                        if (!elem.isJsonObject() || !elem.getAsJsonObject().has("extra_animation")) {
                            continue;
                        }
                        JsonObject clsAnim = elem.getAsJsonObject().getAsJsonObject("extra_animation");
                        for (Map.Entry<String, JsonElement> entry : clsAnim.entrySet()) {
                            extraAnimations.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }
            }

            YSMGeoModel geometry = null;
            Map<String, byte[]> textures = new LinkedHashMap<>();
            Map<String, com.ysmef.compat.ysm.script.ScriptAnim> allScriptAnims = new LinkedHashMap<>();
            Map<String, List<String>> animationControllers = new LinkedHashMap<>();
            if (json.has("files")) {
                JsonObject files = json.getAsJsonObject("files");
                if (files.has("player")) {
                    JsonObject player = files.getAsJsonObject("player");
                    if (player.has("model")) {
                        JsonObject modelObj = player.getAsJsonObject("model");
                        if (modelObj.has("main")) {
                            Path geoPath = resolveInside(modelDir, modelObj.get("main").getAsString());
                            if (geoPath != null && isRegularFileInside(geoPath, modelDir)) {
                                geometry = YSMGeoModel.parse(readStringBounded(geoPath));
                            }
                        }
                    }
                    if (player.has("animation")) {
                        JsonObject animObj = player.getAsJsonObject("animation");
                        for (Map.Entry<String, JsonElement> entry : animObj.entrySet()) {
                            Path animPath = resolveInside(modelDir, entry.getValue().getAsString());
                            if (animPath != null && isRegularFileInside(animPath, modelDir)) {
                                // The "extra" animation file carries the wheel-selectable
                                // animations; it wins on name collisions.
                                loadScriptAnims(animPath, allScriptAnims, "extra".equals(entry.getKey()));
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
                            Path texFile = resolveInside(modelDir, texPath);
                            if (texFile != null && isRegularFileInside(texFile, modelDir)) {
                                textures.put(extractFileName(texPath), readAllBytesBounded(texFile));
                            }
                        }
                    }
                    if (player.has("animation_controllers")) {
                        JsonElement controllers = player.get("animation_controllers");
                        Iterable<JsonElement> controllerFiles = controllers.isJsonArray()
                                ? controllers.getAsJsonArray()
                                : java.util.Collections.singletonList(controllers);
                        for (JsonElement elem : controllerFiles) {
                            if (!elem.isJsonPrimitive()) {
                                continue;
                            }
                            Path controllerPath = resolveInside(modelDir, elem.getAsString());
                            if (controllerPath != null && isRegularFileInside(controllerPath, modelDir)) {
                                loadAnimationControllers(controllerPath, animationControllers);
                            }
                        }
                    }
                }
            }

            if (geometry != null) {
                Map<String, com.ysmef.compat.ysm.script.ScriptAnim> scriptAnims = new LinkedHashMap<>();
                // The physics animation is the one exception to the runtime-relevant filter, and it
                // has to be named here: it is the animation the model's controllers bind as its
                // physics, and the runtime needs it *as written* - its whole content is the author's
                // expressions, which a compiled animation no longer has. Without this the converter
                // kept a bone list and threw away the physics that moves it.
                //
                // The candidate set spans the model's own controllers *and* YSM's built-in default
                // set, because a model that inherits the default controllers never names its physics
                // animation in a file of its own - which is the common case, not the exception. The
                // animation is then cross-checked against the bones this model can actually move: a
                // candidate that drives none of them is not this model's physics, and accepting it
                // would replace the name-based classification with a list of bones the mesh cannot
                // move, taking a model that simulated fifty-nine bones down to none.
                BuiltinControllers builtin = builtinControllers();
                com.ysmef.compat.model.runtime.YsmPhysicsBinding.Selection physics =
                        com.ysmef.compat.model.runtime.YsmPhysicsBinding.select(
                                com.ysmef.compat.model.runtime.YsmPhysicsBinding.Sources.of(
                                        animationControllers, allScriptAnims,
                                        builtin.controllers(), builtin.animations(),
                                        com.ysmef.compat.model.EFMeshJsonWriter.simulatableBoneNames(geometry)));
                String physicsAnimation = physics.animation();
                // A physics animation inherited from the built-in set is not among this package's
                // own files, and the runtime still has to evaluate it: carry the definition along.
                if (physicsAnimation != null && !allScriptAnims.containsKey(physicsAnimation)) {
                    com.ysmef.compat.ysm.script.ScriptAnim inherited = builtin.animations().get(physicsAnimation);
                    if (inherited != null) {
                        allScriptAnims.put(physicsAnimation, inherited);
                    }
                }
                for (Map.Entry<String, com.ysmef.compat.ysm.script.ScriptAnim> entry : allScriptAnims.entrySet()) {
                    if (com.ysmef.compat.ysm.script.ScriptJson.isRuntimeRelevant(entry.getKey())
                            || entry.getKey().equals(physicsAnimation)) {
                        scriptAnims.put(entry.getKey(), entry.getValue());
                    }
                }
                return new YsmModelPackage(modelId, geometry, textures, java.util.Collections.emptyMap(), scriptAnims,
                        allScriptAnims, extraAnimations, animationControllers,
                        widthScale, heightScale, defaultTexture, -1L);
            }
        }
        return null;
    }

    /**
     * Reads one Bedrock .animation.json file and merges every animation into
     * {@code out}. When {@code overwrite} is true (the model's "extra" animation
     * file), an animation of the same name replaces an earlier one; other files
     * never overwrite an already parsed name.
     */
    private static void loadScriptAnims(Path animPath, Map<String, com.ysmef.compat.ysm.script.ScriptAnim> out,
                                        boolean overwrite) {
        try {
            JsonObject root = JsonParser.parseString(readStringBounded(animPath)).getAsJsonObject();
            JsonObject anims = root.has("animations") ? root.getAsJsonObject("animations") : null;
            if (anims == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : anims.entrySet()) {
                if (overwrite || !out.containsKey(entry.getKey())) {
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

    /**
     * Reads one Bedrock animation-controller file and merges every controller's played
     * animations into {@code out}.
     *
     * <p>Bedrock nests these as
     * {@code animation_controllers -> <controller name> -> states -> <state name> -> animations},
     * and an {@code animations} entry is either a bare name or a
     * {@code { "name": "molang condition" }} object. Both forms are read: the bare form is
     * what the bundled default controller uses for the physics animation
     * ({@code "animations": ["Hair_Physics", ...]}), and the object form is what the same
     * file uses everywhere else.
     */
    private static void loadAnimationControllers(Path controllerPath, Map<String, List<String>> out) {
        try {
            controllerAnimationsOf(JsonParser.parseString(readStringBounded(controllerPath)).getAsJsonObject(), out);
        } catch (Exception e) {
            // One broken controller file must not abort the package: the mesh still has to
            // convert. The physics classification then simply falls back to bone names.
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: failed to parse animation controller file '{}': {}",
                    controllerPath.getFileName(), e.toString());
        }
    }

    /**
     * Merges the played animations of every controller in one parsed controller file into
     * {@code out}.
     *
     * <p>Separated from the file reading so the key names can be tested against a real
     * controller file: a wrong key here does not fail loudly, it makes every model fall back
     * to classifying its physics bones by name - which is the thing the classification exists
     * to stop doing.
     */
    static void controllerAnimationsOf(JsonObject root, Map<String, List<String>> out) {
        if (root == null || !root.has("animation_controllers")
                || !root.get("animation_controllers").isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> controller
                : root.getAsJsonObject("animation_controllers").entrySet()) {
            if (!controller.getValue().isJsonObject()) {
                continue;
            }
            JsonElement statesElement = controller.getValue().getAsJsonObject().get("states");
            if (statesElement == null || !statesElement.isJsonObject()) {
                continue;
            }
            JsonObject states = statesElement.getAsJsonObject();
            List<String> names = out.computeIfAbsent(controller.getKey(), key -> new ArrayList<>());
            for (Map.Entry<String, JsonElement> state : states.entrySet()) {
                if (!state.getValue().isJsonObject()) {
                    continue;
                }
                JsonElement animations = state.getValue().getAsJsonObject().get("animations");
                if (animations == null || !animations.isJsonArray()) {
                    continue;
                }
                for (JsonElement entry : animations.getAsJsonArray()) {
                    String name = playedAnimationName(entry);
                    if (name != null && !name.isEmpty() && !names.contains(name)) {
                        names.add(name);
                    }
                }
            }
        }
    }

    /** The animation name of a controller state's {@code animations} entry, or null. */
    private static String playedAnimationName(JsonElement entry) {
        if (entry == null || entry.isJsonNull()) {
            return null;
        }
        if (entry.isJsonPrimitive()) {
            return entry.getAsString();
        }
        if (entry.isJsonObject()) {
            for (Map.Entry<String, JsonElement> property : entry.getAsJsonObject().entrySet()) {
                return property.getKey();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // YSM's built-in default controller set
    // ------------------------------------------------------------------

    /**
     * The package relative id YSM ships its built-in controllers in. The name is a convention,
     * not a contract, so a missing one is looked for under the roots as well
     * ({@link #findDefaultControllerPackage}) and a missing set is reported rather than
     * swallowed - see {@link #builtinControllers()}.
     */
    private static final String DEFAULT_CONTROLLER_PACKAGE = "misc/4_default_controllers";

    /**
     * The controllers YSM applies to a model that declares none, and the animations they play.
     *
     * <p>This is the half of the physics binding that lives outside the model's own package.
     * {@code player.pre_parallel_0} plays {@code Hair_Physics}, and a model inherits that
     * controller whether or not it writes one of its own - the shipped maid's controller file
     * holds a single {@code player.post_main} state and nothing else. Without this table the
     * detection can only see the model's own controllers, which for most real models means
     * seeing no physics at all and guessing from bone names.
     *
     * @param source            the relative id of the package the set was read from, for the log
     * @param controllers       controller name -&gt; the animations its states play
     * @param animations        every animation of that package, by name; consulted only for a
     *                          name the model does not define itself
     * @param unavailableReason why the set is empty, or empty when it was read
     */
    public record BuiltinControllers(String source, Map<String, List<String>> controllers,
                                     Map<String, com.ysmef.compat.ysm.script.ScriptAnim> animations,
                                     String unavailableReason) {

        /** Whether the built-in set was read and can be used as a second candidate source. */
        public boolean available() {
            return !controllers.isEmpty() && !animations.isEmpty();
        }

        private static BuiltinControllers unavailable(String reason) {
            return new BuiltinControllers("", java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(), reason);
        }
    }

    private static volatile BuiltinControllers builtinControllers;

    /**
     * YSM's built-in default controller set, read once per JVM.
     *
     * <p>Fail closed and observable: when the set cannot be read the caller keeps working on the
     * model's own tables - exactly the behaviour before this source existed - and one line says
     * so, because a feature that quietly stops finding anything looks identical to a model that
     * declares nothing.
     */
    public static BuiltinControllers builtinControllers() {
        BuiltinControllers cached = builtinControllers;
        if (cached != null) {
            return cached;
        }
        synchronized (YsmModelPackage.class) {
            if (builtinControllers == null) {
                BuiltinControllers loaded = loadBuiltinControllers();
                builtinControllers = loaded;
                if (!loaded.available()) {
                    com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                            "YSM-EF Compat: [physics] YSM's built-in default controller set is unavailable ({}); "
                                    + "physics detection falls back to each model's own controllers",
                            loaded.unavailableReason());
                }
            }
            return builtinControllers;
        }
    }

    private static BuiltinControllers loadBuiltinControllers() {
        List<String> tried = new ArrayList<>();
        Path dir = findDefaultControllerPackage(tried);
        if (dir == null) {
            return BuiltinControllers.unavailable("no bundled package named for default controllers under '"
                    + YSM_CONFIG.toAbsolutePath() + "' (tried " + String.join(", ", tried) + ")");
        }
        String source = relativeToConfig(dir);
        Path manifest = dir.resolve("ysm.json");
        if (!isRegularFileInside(manifest, dir)) {
            return BuiltinControllers.unavailable("'" + source + "' has no readable ysm.json");
        }
        Map<String, List<String>> controllers = new LinkedHashMap<>();
        Map<String, com.ysmef.compat.ysm.script.ScriptAnim> animations = new LinkedHashMap<>();
        try {
            JsonObject json = JsonParser.parseString(readStringBounded(manifest)).getAsJsonObject();
            JsonObject files = json.has("files") && json.get("files").isJsonObject()
                    ? json.getAsJsonObject("files") : null;
            JsonObject player = files != null && files.has("player") && files.get("player").isJsonObject()
                    ? files.getAsJsonObject("player") : null;
            if (player == null) {
                return BuiltinControllers.unavailable("'" + source + "' declares no files.player section");
            }
            if (player.has("animation")) {
                for (Map.Entry<String, JsonElement> entry : player.getAsJsonObject("animation").entrySet()) {
                    Path animPath = resolveInside(dir, entry.getValue().getAsString());
                    if (animPath != null && isRegularFileInside(animPath, dir)) {
                        loadScriptAnims(animPath, animations, "extra".equals(entry.getKey()));
                    }
                }
            }
            if (player.has("animation_controllers")) {
                JsonElement declared = player.get("animation_controllers");
                Iterable<JsonElement> controllerFiles = declared.isJsonArray()
                        ? declared.getAsJsonArray()
                        : java.util.Collections.singletonList(declared);
                for (JsonElement elem : controllerFiles) {
                    if (!elem.isJsonPrimitive()) {
                        continue;
                    }
                    Path controllerPath = resolveInside(dir, elem.getAsString());
                    if (controllerPath != null && isRegularFileInside(controllerPath, dir)) {
                        loadAnimationControllers(controllerPath, controllers);
                    }
                }
            }
        } catch (Exception e) {
            return BuiltinControllers.unavailable("'" + source + "' could not be read: " + e);
        }
        if (!controllers.isEmpty() && animations.isEmpty()) {
            return BuiltinControllers.unavailable("'" + source + "' declares controllers but no readable animation file");
        }
        return new BuiltinControllers(source, controllers, animations, "");
    }

    /**
     * The bundled default-controller package: the conventional id first, then any bundled
     * package whose directory is named for default controllers, in path order so the answer
     * does not depend on how the file system enumerates directories.
     */
    private static Path findDefaultControllerPackage(List<String> tried) {
        // The id is relative to a root, exactly like every other model id, so it is looked for
        // under each root in the order the roots are searched for a model.
        for (String root : ROOTS) {
            Path preferred = YSM_CONFIG.resolve(root)
                    .resolve(DEFAULT_CONTROLLER_PACKAGE.replace('/', java.io.File.separatorChar));
            if (Files.isDirectory(preferred)) {
                return preferred;
            }
            tried.add(root + '/' + DEFAULT_CONTROLLER_PACKAGE);
        }
        List<Path> candidates = new ArrayList<>();
        for (String root : ROOTS) {
            Path rootPath = YSM_CONFIG.resolve(root);
            if (!Files.isDirectory(rootPath)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(rootPath, 3)) {
                stream.filter(Files::isDirectory)
                        .filter(path -> mentionsDefaultControllers(path.getFileName().toString()))
                        .forEach(candidates::add);
            } catch (IOException ignored) {
                // An unreadable root simply cannot contribute a candidate.
            }
        }
        candidates.sort(java.util.Comparator.comparing(Path::toString));
        if (candidates.isEmpty()) {
            return null;
        }
        tried.add(candidates.get(0).toString());
        return candidates.get(0);
    }

    private static boolean mentionsDefaultControllers(String directoryName) {
        String lower = directoryName.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("default_controller");
    }

    private static String relativeToConfig(Path path) {
        try {
            return YSM_CONFIG.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return path.toString();
        }
    }

    private static YsmModelPackage loadBinary(String modelId) throws IOException {
        Path safeModel = relativeModelPath(modelId);
        if (safeModel == null) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: refusing to load YSM model package with unsafe id '{}'", modelId);
            return null;
        }
        for (String root : ROOTS) {
            Path ysmRoot = YSM_CONFIG.resolve(root).normalize();
            Path ysmFile = ysmRoot.resolve(safeModel).normalize();
            if (!isRegularFileInside(ysmFile, ysmRoot)) {
                continue;
            }
            byte[] decrypted = YsmFileCrypto.decryptYsmFile(readAllBytesBounded(ysmFile));
            YsmBinaryReader.BinaryModel binary = YsmBinaryReader.read(decrypted);
            YSMGeoModel geometry = YSMGeoModel.fromBinary(binary);
            // Compute the content fingerprint here while the decrypted payload
            // is still in hand; the conversion caller needs it for the manifest
            // and would otherwise decrypt the whole package a second time.
            long contentFingerprint = contentFingerprintOfBinary(root, modelId, decrypted);
            return new YsmModelPackage(modelId, geometry, binary.textures, binary.textureInfo, binary.animations,
                    binary.allAnimations, binary.extraAnimations, binary.animationControllers,
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
            Path relative = relativeModelPath(modelId);
            if (relative == null) {
                return -1L;
            }
            long hash = 0xcbf29ce484222325L;
            boolean found = false;
            for (String root : ROOTS) {
                Path rootPath = YSM_CONFIG.resolve(root).normalize();
                Path base = rootPath.resolve(relative).normalize();
                if (modelId.endsWith(".ysm")) {
                    if (isRegularFileInside(base, rootPath)) {
                        hash = fnv1a(hash, root + '/' + modelId);
                        hash = fnv1a(hash, Long.toString(Files.size(base)));
                        hash = fnv1a(hash, Files.getLastModifiedTime(base).toString());
                        found = true;
                        break;
                    }
                    continue;
                }
                if (isDirectoryInside(base, rootPath)) {
                    List<String> entries = new ArrayList<>();
                    try (Stream<Path> stream = Files.walk(base)) {
                        stream.filter(Files::isRegularFile)
                                .filter(path -> isInsideReal(path, base))
                                .forEach(path -> {
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
            Path relative = relativeModelPath(modelId);
            if (relative == null) {
                return -1L;
            }
            for (String root : ROOTS) {
                Path rootPath = YSM_CONFIG.resolve(root).normalize();
                Path base = rootPath.resolve(relative).normalize();
                if (modelId.endsWith(".ysm")) {
                    if (isRegularFileInside(base, rootPath)) {
                        return contentFingerprintOfBinary(root, modelId,
                                YsmFileCrypto.decryptYsmFile(readAllBytesBounded(base)));
                    }
                    continue;
                }
                if (isDirectoryInside(base, rootPath)) {
                    List<Path> files = new ArrayList<>();
                    try (Stream<Path> stream = Files.walk(base)) {
                        stream.filter(Files::isRegularFile)
                                .filter(path -> isInsideReal(path, base))
                                .forEach(files::add);
                    }
                    files.sort(java.util.Comparator.comparing(
                            path -> base.relativize(path).toString().replace('\\', '/')));
                    long hash = 0xcbf29ce484222325L;
                    for (Path file : files) {
                        String rel = base.relativize(file).toString().replace('\\', '/');
                        byte[] data = readAllBytesBounded(file);
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
