package com.ysmef.compat.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmModelPackage;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.animation.types.StaticAnimation;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Converts every wheel-selectable YSM extra animation of a model into a sampled
 * Epic Fight frame-animation JSON (60 FPS, Avalon-compatible matrix layout) and
 * stores it in the generated pack under
 * assets/&lt;modid&gt;/animmodels/animations/public/.
 *
 * Similar sampled actions are deduplicated: the first clip becomes the public
 * template, later clips whose per-frame local animation is within the similarity
 * thresholds reuse that template. The model -> template mapping is persisted in
 * config/ysm_epicfight_compat/extra_animation_mappings.json and the template
 * descriptors (used for approximate matching across sessions) in
 * extra_animation_templates.json.
 *
 * Generated JSONs carry Epic Fight's resourcepack-animation "constructor"
 * section, so they auto-register on resource reloads as well; without a reload
 * the mod registers them directly through AnimationManager on the render thread.
 */
public final class YsmExtraAnimationLibrary {

    public static final String PUBLIC_DIRECTORY = "public";

    private static final Path CONFIG_ROOT = Paths.get("config", "ysm_epicfight_compat");
    private static final Path PACK_ROOT = CONFIG_ROOT.resolve("resourcepack");
    private static final Path PUBLIC_DIR = PACK_ROOT.resolve("assets").resolve(YSMEpicFightCompat.MODID)
            .resolve("animmodels").resolve("animations").resolve(PUBLIC_DIRECTORY);
    private static final Path MAPPING_FILE = CONFIG_ROOT.resolve("extra_animation_mappings.json");
    private static final Path DESCRIPTOR_FILE = CONFIG_ROOT.resolve("extra_animation_templates.json");
    private static final String CONSTRUCTOR_PLACEHOLDER = "ysm_epicfight_compat:public/PLACEHOLDER";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .serializeSpecialFloatingPointValues().create();

    private static final ExecutorService CONVERT_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ysm-ef-extra-anim");
        thread.setDaemon(true);
        return thread;
    });

    private static final Set<String> PENDING_MODELS = ConcurrentHashMap.newKeySet();
    private static final Map<String, ModelMapping> MAPPING_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, TemplateDescriptor> TEMPLATES = new ConcurrentHashMap<>();
    private static final Set<String> TEMPLATES_LOADED = ConcurrentHashMap.newKeySet();
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();
    private static final Set<String> REGISTERING = ConcurrentHashMap.newKeySet();
    private static final java.util.Queue<JsonObject> REGISTER_QUEUE = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile boolean descriptorFileDirty;
    private static volatile Method READ_RESOURCEPACK_ANIMATION;
    private static volatile boolean REFLECTION_TRIED;
    private static volatile boolean REFLECTION_FAILED_LOGGED;

    private YsmExtraAnimationLibrary() {}

    // ------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------

    public record WheelEntry(String wheelAnimation, String templateId, int loop, float length) {}

    public record ModelMapping(String modelId, List<WheelEntry> entries) {}

    public record TemplateDescriptor(String id, int loop, float length, int frameCount,
                                     Map<String, float[]> joints, String hash) {}

    // ------------------------------------------------------------------
    // Conversion
    // ------------------------------------------------------------------

    /**
     * Convert all wheel animations of an already loaded package and persist the
     * model mapping. Called on a background worker by the base-mesh conversion
     * path; safe to call directly because it never touches GL or registries.
     */
    public static void convertModel(YsmModelPackage pkg) {
        if (pkg == null || pkg.geometry == null) {
            return;
        }
        ensureTemplatesLoaded();
        List<WheelEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> extra : pkg.extraAnimations.entrySet()) {
            String wheelAnimation = extra.getKey();
            if (wheelAnimation.isEmpty() || wheelAnimation.startsWith("#")) {
                continue;
            }
            YsmExtraFrameWriter.Clip clip;
            try {
                clip = YsmExtraFrameWriter.convert(pkg, wheelAnimation);
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to sample wheel animation '{}' of model '{}'",
                        wheelAnimation, pkg.modelId, t);
                continue;
            }
            if (clip == null) {
                YSMEpicFightCompat.LOGGER.debug(
                        "YSM-EF Compat: wheel animation '{}' of model '{}' has no convertible data",
                        wheelAnimation, pkg.modelId);
                continue;
            }
            TemplateDescriptor descriptor = findOrCreateTemplate(clip);
            if (descriptor != null) {
                entries.add(new WheelEntry(wheelAnimation, descriptor.id(), clip.loop, clip.length));
            }
        }
        updateMapping(pkg.modelId, entries);
        if (!entries.isEmpty()) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: converted {} wheel animations for model '{}' ({} public templates)",
                    entries.size(), pkg.modelId, TEMPLATES.size());
        }
    }

    private static synchronized TemplateDescriptor findOrCreateTemplate(YsmExtraFrameWriter.Clip clip) {
        String hash = exactHash(clip);
        for (TemplateDescriptor descriptor : TEMPLATES.values()) {
            if (descriptor.hash().equals(hash)) {
                return descriptor;
            }
        }
        for (TemplateDescriptor descriptor : TEMPLATES.values()) {
            if (isSimilar(descriptor, clip)) {
                return descriptor;
            }
        }
        String templateId = "pub_" + hash.substring(0, 12);
        Map<String, float[]> joints = new LinkedHashMap<>();
        for (Map.Entry<Integer, float[]> entry : clip.sourceDescriptor.entrySet()) {
            float[] values = entry.getValue();
            float[] sanitized = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                sanitized[i] = finite(values[i]);
            }
            joints.put(jointName(entry.getKey()), sanitized);
        }
        TemplateDescriptor descriptor = new TemplateDescriptor(
                templateId, clip.loop, clip.length, clip.frameCount, joints, hash);

        JsonObject json = replaceConstructorPath(clip.json, templateId);
        Path outFile = PUBLIC_DIR.resolve(templateId + ".json");
        try {
            Files.createDirectories(PUBLIC_DIR);
            EFMeshJsonWriter.writeFileAtomic(outFile, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write public animation template '{}'", templateId, e);
            return null;
        }

        TEMPLATES.put(templateId, descriptor);
        descriptorFileDirty = true;
        writeDescriptors();
        enqueueRegistration(json);
        return descriptor;
    }

    private static JsonObject replaceConstructorPath(JsonObject clipJson, String templateId) {
        String json = GSON.toJson(clipJson);
        json = json.replace(CONSTRUCTOR_PLACEHOLDER, YSMEpicFightCompat.MODID + ":public/" + templateId);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String jointName(int joint) {
        String[] names = {"Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
                "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R",
                "Elbow_R", "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"};
        return joint >= 0 && joint < names.length ? names[joint] : "Joint" + joint;
    }

    // ------------------------------------------------------------------
    // Template similarity / exact hash
    // ------------------------------------------------------------------

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static TemplateDescriptor sanitizeDescriptor(TemplateDescriptor descriptor) {
        Map<String, float[]> joints = new LinkedHashMap<>();
        for (Map.Entry<String, float[]> entry : descriptor.joints().entrySet()) {
            float[] values = entry.getValue();
            if (values == null) {
                continue;
            }
            float[] sanitized = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                sanitized[i] = finite(values[i]);
            }
            joints.put(entry.getKey(), sanitized);
        }
        return new TemplateDescriptor(descriptor.id(), descriptor.loop(), descriptor.length(),
                descriptor.frameCount(), joints, descriptor.hash());
    }

    private static String exactHash(YsmExtraFrameWriter.Clip clip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) clip.loop);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(8);
            buffer.putInt(Float.floatToIntBits(clip.length));
            buffer.putInt(clip.frameCount);
            digest.update(buffer.array());
            for (Map.Entry<Integer, float[]> entry : clip.sourceDescriptor.entrySet()) {
                digest.update((byte) entry.getKey().intValue());
                for (float value : entry.getValue()) {
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(Float.floatToIntBits(finite(value))).array());
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(System.identityHashCode(clip));
        }
    }

    private static final float MAX_AVG_ROT = 5.0f;       // Bedrock degrees per frame
    private static final float MAX_MAX_ROT = 20.0f;      // worst-frame degrees
    private static final float MAX_AVG_POS = 2.0f;       // Bedrock pixels
    private static final float MAX_MAX_POS = 8.0f;
    private static final float MAX_AVG_SCALE = 0.05f;
    private static final float MAX_MAX_SCALE = 0.2f;

    private static boolean isSimilar(TemplateDescriptor descriptor, YsmExtraFrameWriter.Clip clip) {
        if (descriptor.loop() != clip.loop || descriptor.frameCount() != clip.frameCount
                || Math.abs(descriptor.length() - clip.length) > 0.05f) {
            return false;
        }
        if (!descriptor.joints().keySet().equals(jointNamesOf(clip.sourceDescriptor))) {
            return false;
        }
        double rotSum = 0.0;
        double posSum = 0.0;
        double scaleSum = 0.0;
        float maxRot = 0.0f;
        float maxPos = 0.0f;
        float maxScale = 0.0f;
        int samples = 0;
        for (Map.Entry<String, float[]> templateJoint : descriptor.joints().entrySet()) {
            float[] template = templateJoint.getValue();
            float[] candidate = clip.sourceDescriptor.get(jointIdOf(templateJoint.getKey()));
            if (candidate == null || template.length != candidate.length) {
                return false;
            }
            for (int i = 0; i < template.length; i += 9) {
                float dx = finite(template[i]) - finite(candidate[i]);
                float dy = finite(template[i + 1]) - finite(candidate[i + 1]);
                float dz = finite(template[i + 2]) - finite(candidate[i + 2]);
                float rot = finite((float) Math.sqrt(dx * dx + dy * dy + dz * dz));
                float pdx = finite(template[i + 3]) - finite(candidate[i + 3]);
                float pdy = finite(template[i + 4]) - finite(candidate[i + 4]);
                float pdz = finite(template[i + 5]) - finite(candidate[i + 5]);
                float pos = finite((float) Math.sqrt(pdx * pdx + pdy * pdy + pdz * pdz));
                float sdx = finite(template[i + 6]) - finite(candidate[i + 6]);
                float sdy = finite(template[i + 7]) - finite(candidate[i + 7]);
                float sdz = finite(template[i + 8]) - finite(candidate[i + 8]);
                float scale = Math.max(Math.abs(sdx), Math.max(Math.abs(sdy), Math.abs(sdz)));
                rotSum += rot;
                posSum += pos;
                scaleSum += scale;
                maxRot = Math.max(maxRot, rot);
                maxPos = Math.max(maxPos, pos);
                maxScale = Math.max(maxScale, scale);
                samples++;
            }
        }
        if (samples == 0) {
            return false;
        }
        return (rotSum / samples) <= MAX_AVG_ROT && maxRot <= MAX_MAX_ROT
                && (posSum / samples) <= MAX_AVG_POS && maxPos <= MAX_MAX_POS
                && (scaleSum / samples) <= MAX_AVG_SCALE && maxScale <= MAX_MAX_SCALE;
    }

    private static java.util.Set<String> jointNamesOf(Map<Integer, float[]> descriptor) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Integer joint : descriptor.keySet()) {
            names.add(jointName(joint));
        }
        return names;
    }

    private static int jointIdOf(String name) {
        String[] names = {"Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
                "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R",
                "Elbow_R", "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"};
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static void ensureTemplatesLoaded() {
        if (!TEMPLATES_LOADED.add("global")) {
            return;
        }
        try {
            if (Files.isRegularFile(DESCRIPTOR_FILE)) {
                JsonObject root = JsonParser.parseString(Files.readString(DESCRIPTOR_FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("templates") && root.get("templates").isJsonArray()) {
                    for (var element : root.getAsJsonArray("templates")) {
                        TemplateDescriptor descriptor = GSON.fromJson(element, TemplateDescriptor.class);
                        if (descriptor != null && descriptor.id() != null) {
                            TEMPLATES.put(descriptor.id(), sanitizeDescriptor(descriptor));
                        }
                    }
                }
            }
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to load extra animation template descriptors", e);
        }
    }

    private static synchronized void writeDescriptors() {
        if (!descriptorFileDirty) {
            return;
        }
        descriptorFileDirty = false;
        JsonObject root = new JsonObject();
        JsonArray templates = new JsonArray();
        List<TemplateDescriptor> sorted = new ArrayList<>(TEMPLATES.values());
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        for (TemplateDescriptor descriptor : sorted) {
            templates.add(GSON.toJsonTree(descriptor));
        }
        root.add("templates", templates);
        try {
            Files.createDirectories(DESCRIPTOR_FILE.getParent());
            EFMeshJsonWriter.writeFileAtomic(DESCRIPTOR_FILE, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write extra animation template descriptors", e);
        }
    }

    private static synchronized void updateMapping(String modelId, List<WheelEntry> entries) {
        ModelMapping mapping = new ModelMapping(modelId, entries);
        MAPPING_CACHE.put(modelId, mapping);
        JsonObject root = new JsonObject();
        try {
            if (Files.isRegularFile(MAPPING_FILE)) {
                root = JsonParser.parseString(Files.readString(MAPPING_FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception ignored) {
            root = new JsonObject();
        }
        JsonObject models = root.has("models") ? root.getAsJsonObject("models") : new JsonObject();
        models.add(modelId, GSON.toJsonTree(mapping));
        root.add("models", models);
        try {
            Files.createDirectories(MAPPING_FILE.getParent());
            EFMeshJsonWriter.writeFileAtomic(MAPPING_FILE, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write extra animation mapping", e);
        }
    }

    private static ModelMapping loadMapping(String modelId) {
        ModelMapping cached = MAPPING_CACHE.get(modelId);
        if (cached != null) {
            return cached;
        }
        try {
            if (!Files.isRegularFile(MAPPING_FILE)) {
                return null;
            }
            JsonObject root = JsonParser.parseString(Files.readString(MAPPING_FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject models = root.has("models") ? root.getAsJsonObject("models") : null;
            if (models != null && models.has(modelId)) {
                ModelMapping mapping = GSON.fromJson(models.get(modelId), ModelMapping.class);
                if (mapping != null) {
                    MAPPING_CACHE.put(modelId, mapping);
                    return mapping;
                }
            }
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.debug("YSM-EF Compat: failed to read wheel animation mapping for '{}'", modelId);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Runtime lookup
    // ------------------------------------------------------------------

    /**
     * The wheel entry of a model by YSM animation name (the wheel key), or null
     * when the mapping is not generated yet.
     */
    public static WheelEntry findEntry(String modelId, String animationName) {
        ModelMapping mapping = loadMapping(modelId);
        if (mapping == null) {
            ensureConvertedAsync(modelId);
            return null;
        }
        for (WheelEntry entry : mapping.entries()) {
            if (entry.wheelAnimation().equals(animationName)) {
                return entry;
            }
        }
        return null;
    }

    /** The template JSON file of a template id. */
    public static Path templateFile(String templateId) {
        return PUBLIC_DIR.resolve(templateId + ".json");
    }

    private static final Map<String, Set<String>> TEMPLATE_JOINT_NAME_CACHE = new ConcurrentHashMap<>();

    /** Joint names overridden by one public template (used for wheel-pose retarget correction). */
    public static Set<String> templateJointNames(String templateId) {
        Set<String> cached = TEMPLATE_JOINT_NAME_CACHE.get(templateId);
        if (cached != null) {
            return cached;
        }
        Set<String> names = new LinkedHashSet<>();
        try {
            Path file = templateFile(templateId);
            if (Files.isRegularFile(file)) {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("animation") && json.get("animation").isJsonArray()) {
                    for (var element : json.getAsJsonArray("animation")) {
                        if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
                            names.add(element.getAsJsonObject().get("name").getAsString());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        TEMPLATE_JOINT_NAME_CACHE.put(templateId, names);
        return names;
    }

    /**
     * Get (and lazily register) the Epic Fight animation accessor of a public
     * template, or null while it is not registered yet.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static AssetAccessor<? extends StaticAnimation> getTemplateAccessor(String templateId) {
        ResourceLocation registryName = ResourceLocation.fromNamespaceAndPath(
                YSMEpicFightCompat.MODID, PUBLIC_DIRECTORY + "/" + templateId);
        AnimationManager.AnimationAccessor<? extends StaticAnimation> accessor = AnimationManager.byKey(registryName);
        if (accessor != null) {
            return accessor;
        }
        if (REGISTERED.contains(templateId) || REGISTERING.contains(templateId)) {
            return null;
        }
        Path file = templateFile(templateId);
        if (Files.isRegularFile(file)) {
            try {
                enqueueRegistration(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void enqueueRegistration(JsonObject json) {
        REGISTER_QUEUE.add(json);
    }

    /**
     * Render-thread tick: register newly written public templates directly in
     * Epic Fight's AnimationManager (the generated pack is live, so a full
     * resource reload is not needed for a lazily converted model).
     */
    public static void clientTick() {
        migrateLegacyTemplateConstructors();
        JsonObject json;
        while ((json = REGISTER_QUEUE.poll()) != null) {
            registerFromJson(json);
        }
    }

    private static volatile boolean LEGACY_TEMPLATES_MIGRATED;

    /**
     * Early generated templates used "epicfight:biped" as the armature accessor
     * name. Epic Fight 20.14.17 registers the biped armature under
     * "epicfight:entity/biped", and the old name fails at clip load time with
     * "Can't find resource file: epicfight:animmodels/biped.json". Rewrite those
     * already-written template files in place once so existing caches work.
     */
    private static synchronized void migrateLegacyTemplateConstructors() {
        if (LEGACY_TEMPLATES_MIGRATED) {
            return;
        }
        LEGACY_TEMPLATES_MIGRATED = true;
        try {
            if (!Files.isDirectory(PUBLIC_DIR)) {
                return;
            }
            int rewritten = 0;
            try (var files = Files.list(PUBLIC_DIR)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        String json = Files.readString(file, StandardCharsets.UTF_8);
                        if (!json.contains("epicfight:biped#")) {
                            continue;
                        }
                        String fixed = json.replace("epicfight:biped#", "epicfight:entity/biped#");
                        EFMeshJsonWriter.writeFileAtomic(file, fixed.getBytes(StandardCharsets.UTF_8));
                        // Re-register on the render thread even when Epic Fight's
                        // resource reload already created an accessor for this
                        // id: readResourcepackAnimation overwrites by name, so
                        // this replaces the legacy object before playback.
                        enqueueRegistration(JsonParser.parseString(fixed).getAsJsonObject());
                        rewritten++;
                    } catch (Exception ignored) {
                    }
                }
            }
            if (rewritten > 0) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [wheel] migrated {} public templates to armature 'epicfight:entity/biped'", rewritten);
            }
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to migrate legacy wheel template constructors", e);
        }
    }

    private static void registerFromJson(JsonObject json) {
        String id = null;
        try {
            Method method = resourcepackAnimationMethod();
            if (method == null) {
                return;
            }
            JsonObject constructor = json.has("constructor") ? json.getAsJsonObject("constructor") : null;
            if (constructor == null || !constructor.has("invocation_command")) {
                return;
            }
            String invocation = constructor.get("invocation_command").getAsString();
            id = templateIdFromInvocation(invocation);
            if (id == null || REGISTERED.contains(id) || !REGISTERING.add(id)) {
                return;
            }
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                    YSMEpicFightCompat.MODID, PUBLIC_DIRECTORY + "/" + id);
            method.invoke(AnimationManager.getInstance(), rl, json);
            REGISTERED.add(id);
            YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: [wheel] registered public animation template '{}'", id);
        } catch (Throwable t) {
            if (id != null) {
                REGISTERING.remove(id);
            }
            if (!REFLECTION_FAILED_LOGGED) {
                REFLECTION_FAILED_LOGGED = true;
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to register generated wheel animation directly; it will register on the next resource reload", t);
            }
        }
    }

    private static Method resourcepackAnimationMethod() {
        if (REFLECTION_TRIED) {
            return READ_RESOURCEPACK_ANIMATION;
        }
        REFLECTION_TRIED = true;
        try {
            Method method = AnimationManager.class.getDeclaredMethod("readResourcepackAnimation",
                    ResourceLocation.class, JsonObject.class);
            method.setAccessible(true);
            READ_RESOURCEPACK_ANIMATION = method;
        } catch (Throwable t) {
            READ_RESOURCEPACK_ANIMATION = null;
        }
        return READ_RESOURCEPACK_ANIMATION;
    }

    private static String templateIdFromInvocation(String invocation) {
        int start = invocation.indexOf("public/");
        if (start < 0) {
            return null;
        }
        int end = invocation.indexOf('#', start);
        if (end < 0) {
            return null;
        }
        return invocation.substring(start + "public/".length(), end);
    }

    // ------------------------------------------------------------------
    // Async per-model generation
    // ------------------------------------------------------------------

    /**
     * Ensure the wheel animations of one model are converted (used by the
     * cache-restore path and when a model is first played in battle mode).
     */
    public static void ensureConvertedAsync(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return;
        }
        if (loadMapping(modelId) != null) {
            return;
        }
        if (!PENDING_MODELS.add(modelId)) {
            return;
        }
        CONVERT_POOL.submit(() -> {
            try {
                YsmModelPackage pkg = YsmModelPackage.load(modelId);
                if (pkg != null) {
                    convertModel(pkg);
                }
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: wheel animation conversion failed for '{}'", modelId, t);
            } finally {
                PENDING_MODELS.remove(modelId);
            }
        });
    }

    /** Forget cached mappings/descriptors after model reloads. */
    public static void invalidateAll() {
        MAPPING_CACHE.clear();
        TEMPLATES.clear();
        TEMPLATES_LOADED.clear();
        REGISTERING.clear();
        REGISTERED.clear();
        REGISTER_QUEUE.clear();
        descriptorFileDirty = false;
        // On a full resource reload Epic Fight drops resourcepack-animation
        // registrations itself; templates will be re-registered on next lookup.
    }
}
