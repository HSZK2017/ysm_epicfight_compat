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
 * Generated frame clips are deduplicated only when their emitted joint matrices
 * match exactly. The model -> template mapping is persisted in
 * config/ysm_epicfight_compat/extra_animation_mappings.json and the template
 * legacy source descriptors in
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
    /**
     * New per-model mapping sidecars. The legacy aggregate
     * {@link #MAPPING_FILE} is still READ for caches written by older builds,
     * but new mappings are written one small file per model: rewriting the whole
     * aggregate for every converted model was O(N^2) disk I/O across a session.
     */
    private static final Path MAPPING_DIR = CONFIG_ROOT.resolve("extra_animation_mappings");
    private static final Object MAPPING_WRITE_LOCK = new Object();
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
    /** Models whose mapping was looked up and found nowhere: skip repeated render-tick disk reads. */
    private static final Set<String> MAPPING_MISS = ConcurrentHashMap.newKeySet();
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
        // Flush the descriptor file once for the whole batch (serializing every
        // template's frame data per template was the model-switch freeze).
        flushDescriptors();
        updateMapping(pkg.modelId, entries);
        if (!entries.isEmpty()) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: converted {} wheel animations for model '{}' ({} public templates)",
                    entries.size(), pkg.modelId, TEMPLATES.size());
        }
    }

    private static synchronized TemplateDescriptor findOrCreateTemplate(YsmExtraFrameWriter.Clip clip) {
        String hash = YsmWheelFrameHash.of(clip);
        for (TemplateDescriptor descriptor : TEMPLATES.values()) {
            if (descriptor.hash().equals(hash)) {
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
            // Preserve compact source descriptors for legacy file compatibility;
            // only the emitted matrices determine template reuse.
            joints.put(jointName(entry.getKey()), downsample(sanitized, DESCRIPTOR_SAMPLING));
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
        // Only mark the descriptor file dirty here: the flush (which serializes
        // every template's frame data) is batched to the end of the conversion
        // (flushDescriptors), not done per template.
        descriptorFileDirty = true;
        enqueueRegistration(json);
        return descriptor;
    }

    private static JsonObject replaceConstructorPath(JsonObject clipJson, String templateId) {
        String json = GSON.toJson(clipJson);
        json = json.replace(CONSTRUCTOR_PLACEHOLDER, YSMEpicFightCompat.MODID + ":public/" + templateId);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String jointName(int joint) {
        return JointTable.nameOf(joint);
    }

    // ------------------------------------------------------------------
    // Compact legacy source descriptors
    // ------------------------------------------------------------------

    /**
     * Legacy source descriptors are stored downsampled: every Nth frame instead of
     * full per-frame data. They remain for reading existing descriptor files;
     * template identity now uses the emitted matrices in YsmWheelFrameHash.
     */
    private static final int DESCRIPTOR_SAMPLING = 8;

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    /** Keep every {@code sampling}-th frame of a per-frame 9-float joint channel. */
    private static float[] downsample(float[] full, int sampling) {
        int fullFrames = full.length / 9;
        int outFrames = Math.max(1, (fullFrames + sampling - 1) / sampling);
        float[] out = new float[outFrames * 9];
        for (int f = 0; f < outFrames; f++) {
            int src = Math.min(f * sampling, fullFrames - 1) * 9;
            System.arraycopy(full, src, out, f * 9, 9);
        }
        return out;
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
                boolean needsRewrite = false;
                // Stream the file template by template: the legacy format stored
                // FULL per-frame data and could reach hundreds of MB - parsing it
                // into one JsonObject tree would OOM the client.
                try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                        Files.newBufferedReader(DESCRIPTOR_FILE, StandardCharsets.UTF_8))) {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String name = reader.nextName();
                        if (!"templates".equals(name)) {
                            reader.skipValue();
                            continue;
                        }
                        reader.beginArray();
                        while (reader.hasNext()) {
                            TemplateDescriptor descriptor = GSON.fromJson(reader, TemplateDescriptor.class);
                            if (descriptor == null || descriptor.id() == null) {
                                continue;
                            }
                            boolean sampled = false;
                            Map<String, float[]> joints = new LinkedHashMap<>();
                            int expectedFrames = Math.max(1,
                                    (descriptor.frameCount() + DESCRIPTOR_SAMPLING - 1) / DESCRIPTOR_SAMPLING);
                            for (Map.Entry<String, float[]> entry : descriptor.joints().entrySet()) {
                                float[] values = entry.getValue();
                                if (values == null) {
                                    continue;
                                }
                                // Legacy (unsampled) descriptors carry one entry
                                // per frame; downsample them in place and mark
                                // the file for a rewrite to the compact format.
                                if (values.length / 9 > expectedFrames) {
                                    values = downsample(values, DESCRIPTOR_SAMPLING);
                                    sampled = true;
                                }
                                float[] sanitized = new float[values.length];
                                for (int i = 0; i < values.length; i++) {
                                    sanitized[i] = finite(values[i]);
                                }
                                joints.put(entry.getKey(), sanitized);
                            }
                            TEMPLATES.put(descriptor.id(), new TemplateDescriptor(descriptor.id(),
                                    descriptor.loop(), descriptor.length(), descriptor.frameCount(), joints, descriptor.hash()));
                            needsRewrite |= sampled;
                        }
                        reader.endArray();
                    }
                    reader.endObject();
                }
                if (needsRewrite) {
                    // The legacy full-frame file is replaced by the compact
                    // downsampled form on the next flush.
                    descriptorFileDirty = true;
                    YSMEpicFightCompat.LOGGER.info(
                            "YSM-EF Compat: extra animation descriptors were legacy full-frame format; "
                                    + "downsampled {} templates in memory, compact rewrite scheduled", TEMPLATES.size());
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
        List<TemplateDescriptor> sorted = new ArrayList<>(TEMPLATES.values());
        writeDescriptorsNow(sorted);
    }

    /**
     * Flush the descriptor file immediately if it is dirty, then any templates
     * added while the (heavy) serialization was in flight are caught by a
     * follow-up flush. Called once per conversion batch - never per template.
     */
    private static void flushDescriptors() {
        writeDescriptors();
        // A template registered while the previous flush serialized could have
        // re-dirtied the flag without being included; flush again in that case.
        if (descriptorFileDirty) {
            writeDescriptors();
        }
    }

    /**
     * Serialize + write the descriptor file. NOT synchronized: the snapshot is
     * taken by the caller under the class lock, but the JSON serialization of
     * hundreds of templates (each carrying thousands of frame floats) and the
     * disk write run unlocked, so the render thread's class-lock acquisitions
     * (clientTick's registration drain) are never blocked by it.
     */
    private static void writeDescriptorsNow(List<TemplateDescriptor> sortedSnapshot) {
        List<TemplateDescriptor> sorted = new ArrayList<>(sortedSnapshot);
        JsonObject root = new JsonObject();
        JsonArray templates = new JsonArray();
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

    private static void updateMapping(String modelId, List<WheelEntry> entries) {
        ModelMapping mapping = new ModelMapping(modelId, entries);
        MAPPING_CACHE.put(modelId, mapping);
        MAPPING_MISS.remove(modelId);

        // Per-model sidecar: one small atomic write instead of parsing and
        // rewriting the whole session aggregate for every converted model.
        JsonObject root = new JsonObject();
        JsonObject models = new JsonObject();
        models.add(modelId, GSON.toJsonTree(mapping));
        root.add("models", models);
        Path sidecar = mappingSidecar(modelId);
        synchronized (MAPPING_WRITE_LOCK) {
            try {
                EFMeshJsonWriter.writeFileAtomic(sidecar, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write extra animation mapping for '{}'", modelId, e);
            }
        }
    }

    private static Path mappingSidecar(String modelId) {
        return MAPPING_DIR.resolve(TextureStore.sanitize(modelId) + ".json");
    }

    private static ModelMapping loadMapping(String modelId) {
        ModelMapping cached = MAPPING_CACHE.get(modelId);
        if (cached != null) {
            return cached;
        }
        // Negative cache: findEntry() retries every tick while a wheel animation
        // waits for conversion; without this each tick re-read the mapping files
        // from disk on the render thread.
        if (MAPPING_MISS.contains(modelId)) {
            return null;
        }
        ModelMapping mapping = readMappingFile(mappingSidecar(modelId), modelId);
        if (mapping == null) {
            // Backward compatibility with caches written by older builds.
            mapping = readMappingFile(MAPPING_FILE, modelId);
        }
        if (mapping != null) {
            MAPPING_CACHE.put(modelId, mapping);
            MAPPING_MISS.remove(modelId);
            return mapping;
        }
        MAPPING_MISS.add(modelId);
        return null;
    }

    private static ModelMapping readMappingFile(Path file, String modelId) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject models = root.has("models") ? root.getAsJsonObject("models") : null;
            if (models != null && models.has(modelId)) {
                ModelMapping mapping = GSON.fromJson(models.get(modelId), ModelMapping.class);
                if (mapping != null) {
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
        // Older mappings were written before classified submenu animations
        // (e.g. "#快捷交流" entries) were merged into extraAnimations. Re-convert
        // such a model once per session so newly discovered submenu actions are
        // appended to the persisted mapping.
        remapConvertedAsync(modelId);
        return null;
    }

    /** The template JSON file of a template id. */
    public static Path templateFile(String templateId) {
        return PUBLIC_DIR.resolve(templateId + ".json");
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
     *
     * Called every render-thread tick by clientTick: the volatile fast-path
     * check below must NOT acquire the class lock after the one-time migration
     * (a synchronized method would contend with the conversion pool's
     * findOrCreateTemplate lock and freeze the render thread during model
     * switches).
     */
    private static void migrateLegacyTemplateConstructors() {
        if (LEGACY_TEMPLATES_MIGRATED) {
            return;
        }
        migrateLegacyTemplateConstructorsLocked();
    }

    private static synchronized void migrateLegacyTemplateConstructorsLocked() {
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

    private static final Set<String> REMAP_PENDING_MODELS = ConcurrentHashMap.newKeySet();
    private static final Set<String> REMAP_CHECKED_MODELS = ConcurrentHashMap.newKeySet();

    /**
     * Re-run conversion for a model whose persisted mapping exists but does not
     * contain the requested animation (legacy mapping missing classified
     * submenu entries). Runs at most once per model per session.
     */
    private static void remapConvertedAsync(String modelId) {
        if (modelId == null || modelId.isEmpty() || !REMAP_CHECKED_MODELS.add(modelId)) {
            return;
        }
        if (!REMAP_PENDING_MODELS.add(modelId)) {
            return;
        }
        CONVERT_POOL.submit(() -> {
            try {
                YsmModelPackage pkg = YsmModelPackage.load(modelId);
                if (pkg != null) {
                    convertModel(pkg);
                }
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: wheel animation remap failed for '{}'", modelId, t);
            } finally {
                REMAP_PENDING_MODELS.remove(modelId);
            }
        });
    }

    /** Forget cached mappings/descriptors after model reloads. */
    public static void invalidateAll() {
        MAPPING_CACHE.clear();
        MAPPING_MISS.clear();
        TEMPLATES.clear();
        TEMPLATES_LOADED.clear();
        REGISTERING.clear();
        REGISTERED.clear();
        REGISTER_QUEUE.clear();
        REMAP_CHECKED_MODELS.clear();
        REMAP_PENDING_MODELS.clear();
        descriptorFileDirty = false;
        // On a full resource reload Epic Fight drops resourcepack-animation
        // registrations itself; templates will be re-registered on next lookup.
    }
}
