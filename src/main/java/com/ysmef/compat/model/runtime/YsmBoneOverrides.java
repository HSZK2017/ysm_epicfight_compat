package com.ysmef.compat.model.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.TextureStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User overrides for which bones this mod hides in battle mode.
 *
 * <p>The set of bones to hide is derived automatically - the model's
 * always-playing animations scale some bones to zero, and those are the props,
 * sheaths and variant geometry that must not show while Epic Fight poses the
 * body. That derivation is a guess about someone else's model, so it can be
 * wrong in both directions: a prop this mod does not recognise keeps floating
 * beside the player, or a piece the author meant to keep visible disappears.
 *
 * <p>This gives both directions a user-editable answer, ported from EpicYSM's
 * {@code hidden-bones.txt} / per-model {@code epicysm.json} scheme (MIT):
 *
 * <pre>
 * config/ysm_epicfight_compat/hidden-bones.txt      # every model
 * config/ysm_epicfight_compat/bone_overrides/&lt;model&gt;.json   # one model
 * </pre>
 *
 * <p>The text file takes one bone name per line, {@code #} starts a comment, and
 * a {@code show:} prefix forces a bone to stay visible instead:
 *
 * <pre>
 * Lantern          # hide a prop this mod did not recognise
 * show:Wings       # keep something the automatic pass hid by mistake
 * </pre>
 *
 * <p>The per-model JSON is keyed by the YSM model id (the same id the model
 * packages and the server sync use), which is why these live in one config folder
 * rather than beside the model: encrypted {@code .ysm} packages cannot carry a
 * sidecar file, and the model packages are read-only inputs this mod does not own.
 *
 * <pre>
 * { "hide": ["Lantern", "Sheath"], "show": ["Wings"] }
 * </pre>
 *
 * <p>Per-model entries are applied after the global ones, so a model can undo a
 * global choice for itself.
 */
public final class YsmBoneOverrides {

    private static final Path CONFIG_ROOT = Paths.get("config", "ysm_epicfight_compat");
    private static final Path GLOBAL_FILE = CONFIG_ROOT.resolve("hidden-bones.txt");
    private static final Path PER_MODEL_DIR = CONFIG_ROOT.resolve("bone_overrides");

    /** Bone names are matched the way YSM writes them, but not case-sensitively. */
    private static final Map<String, Set<String>> HIDE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> SHOW_CACHE = new ConcurrentHashMap<>();

    /** Whole-file state: modification stamps, so an edited file is re-read. */
    private static volatile long globalStamp = Long.MIN_VALUE;
    private static volatile Set<String> globalHide = Collections.emptySet();
    private static volatile Set<String> globalShow = Collections.emptySet();
    private static final Set<String> LOGGED_MODELS = ConcurrentHashMap.newKeySet();

    private YsmBoneOverrides() {}

    /**
     * Apply the overrides to an automatically computed hidden-bone array, in
     * place. Cheap: the global file is re-read only when its timestamp changes and
     * each model's entries are cached after the first lookup.
     *
     * @param modelId the YSM model id these bones belong to (may be null)
     * @param bones   the runtime bone table, indexed the same way as {@code hidden}
     * @param hidden  the computed flags to adjust
     */
    public static void apply(String modelId, YSMRuntimeModel.BoneRt[] bones, boolean[] hidden) {
        if (bones == null || hidden == null) {
            return;
        }
        Set<String> hide = new java.util.HashSet<>(globalHide());
        Set<String> show = new java.util.HashSet<>(globalShow());
        // The per-model entries are layered on top of the global ones and therefore
        // win, which is what lets a model undo a global choice for itself.
        readPerModel(modelId, hide, show);

        if (hide.isEmpty() && show.isEmpty()) {
            return;
        }

        int changed = applyOverrides(bones, hidden, hide, show);
        if (changed > 0 && modelId != null && LOGGED_MODELS.add(modelId)) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: bone overrides applied to model '{}': {} bone(s) changed ({} hide / {} show entries)",
                    modelId, changed, hide.size(), show.size());
        }
    }

    /**
     * The override decision itself, kept separate from file reading so the
     * precedence rules can be tested directly.
     *
     * <p>{@code show} wins over {@code hide} when a bone appears in both: callers
     * layer the global list first and each model's own list second, so a model has
     * to be able to force back a bone the global file hides - otherwise the only
     * way to keep one bone visible would be to stop hiding it everywhere.
     *
     * @return how many flags changed
     */
    static int applyOverrides(YSMRuntimeModel.BoneRt[] bones, boolean[] hidden,
                              Set<String> hide, Set<String> show) {
        int changed = 0;
        for (int i = 0; i < bones.length && i < hidden.length; i++) {
            YSMRuntimeModel.BoneRt bone = bones[i];
            String name = bone == null ? null : bone.name;
            if (name == null || name.isEmpty()) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            boolean shouldHide;
            if (show.contains(key)) {
                shouldHide = false;
            } else if (hide.contains(key)) {
                shouldHide = true;
            } else {
                continue;
            }
            if (hidden[i] != shouldHide) {
                hidden[i] = shouldHide;
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Global list
    // ------------------------------------------------------------------

    private static Set<String> globalHide() {
        readGlobalFile();
        return globalHide;
    }

    private static Set<String> globalShow() {
        readGlobalFile();
        return globalShow;
    }

    private static void readGlobalFile() {
        long stamp;
        try {
            stamp = Files.exists(GLOBAL_FILE) ? Files.getLastModifiedTime(GLOBAL_FILE).toMillis() : -1L;
        } catch (IOException e) {
            stamp = -1L;
        }
        if (stamp == globalStamp) {
            return;
        }
        synchronized (YsmBoneOverrides.class) {
            if (stamp == globalStamp) {
                return;
            }
            List<String> hide = new ArrayList<>();
            List<String> show = new ArrayList<>();
            try {
                if (stamp >= 0) {
                    parseLines(Files.readAllLines(GLOBAL_FILE, StandardCharsets.UTF_8), hide, show);
                }
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: could not read {} - bone overrides from it are ignored", GLOBAL_FILE, t);
            }
            globalHide = Set.copyOf(hide);
            globalShow = Set.copyOf(show);
            globalStamp = stamp;
            if (!hide.isEmpty() || !show.isEmpty()) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: read {} ({} to hide, {} to show)",
                        GLOBAL_FILE, hide.size(), show.size());
            }
        }
    }

    /** One bone name per line; blank lines and {@code #} comments skipped. */
    private static void parseLines(List<String> lines, List<String> hide, List<String> show) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.regionMatches(true, 0, "show:", 0, 5)) {
                addName(show, line.substring(5));
            } else {
                addName(hide, line);
            }
        }
    }

    private static void addName(List<String> target, String name) {
        String trimmed = name.trim();
        if (!trimmed.isEmpty()) {
            target.add(trimmed.toLowerCase(Locale.ROOT));
        }
    }

    // ------------------------------------------------------------------
    // Per-model entries
    // ------------------------------------------------------------------

    private static void readPerModel(String modelId, Set<String> hide, Set<String> show) {
        if (modelId == null || modelId.isEmpty()) {
            return;
        }
        Set<String> cachedHide = HIDE_CACHE.get(modelId);
        if (cachedHide != null) {
            hide.addAll(cachedHide);
            Set<String> cachedShow = SHOW_CACHE.get(modelId);
            if (cachedShow != null) {
                show.addAll(cachedShow);
            }
            return;
        }

        List<String> modelHide = new ArrayList<>();
        List<String> modelShow = new ArrayList<>();
        Path file = perModelFile(modelId);
        try {
            if (file != null && Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonElement parsed = JsonParser.parseString(text);
                if (parsed.isJsonObject()) {
                    JsonObject root = parsed.getAsJsonObject();
                    collectArray(root, "hide", modelHide);
                    collectArray(root, "show", modelShow);
                } else {
                    YSMEpicFightCompat.LOGGER.warn(
                            "YSM-EF Compat: {} is not a JSON object - ignored", file);
                }
            }
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: could not read bone override file {} - ignored", file, t);
        }

        Set<String> hideSet = Set.copyOf(modelHide);
        Set<String> showSet = Set.copyOf(modelShow);
        HIDE_CACHE.put(modelId, hideSet);
        SHOW_CACHE.put(modelId, showSet);
        hide.addAll(hideSet);
        show.addAll(showSet);
    }

    private static void collectArray(JsonObject root, String key, List<String> target) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                addName(target, item.getAsString());
            }
        }
    }

    /**
     * The override file for a model id. Model ids are untrusted (they arrive from
     * the server sync and from player NBT) and may contain path separators, so the
     * id is flattened through the same sanitizer the generated resource pack uses.
     */
    private static Path perModelFile(String modelId) {
        try {
            String sanitized = TextureStore.sanitize(modelId);
            if (sanitized == null || sanitized.isEmpty()) {
                return null;
            }
            return PER_MODEL_DIR.resolve(sanitized + ".json");
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Drop every cache (resource reload, world leave, or after editing the files). */
    public static synchronized void invalidate() {
        HIDE_CACHE.clear();
        SHOW_CACHE.clear();
        LOGGED_MODELS.clear();
        globalStamp = Long.MIN_VALUE;
        globalHide = Collections.emptySet();
        globalShow = Collections.emptySet();
    }

    /** The folder users put these files in, for log/help output. */
    public static Path configDir() {
        return CONFIG_ROOT;
    }
}
