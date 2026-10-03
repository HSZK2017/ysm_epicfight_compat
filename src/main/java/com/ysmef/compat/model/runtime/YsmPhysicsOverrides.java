package com.ysmef.compat.model.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.TextureStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User overrides for which bones of a model are held <b>rigid</b>: no secondary-motion delta at all,
 * so the piece follows its joint exactly as the animation poses it.
 *
 * <p>Which bones swing is decided automatically, and the decision is a guess about someone else's
 * model: the model's own physics animation names them when it has one, and a model that declares
 * nothing usable falls back to reading bone names as cloth or hair. A guess can be wrong in a
 * direction no tuning can repair - a bone that is <i>not</i> a hanging piece at all (a hair cap that
 * sits on the skull, a piece whose geometry is carried by the body under it) is integrated as a
 * pendulum like any other, and because its pivot is inside its own volume it then turns about
 * whatever point it is anchored to. Measured on {@code wine_fox/01_taisho_maid}'s top-of-head cap
 * {@code BaseHair}: a saturated 20-degree turn about its own contact patch, which moves the end it is
 * held by 0.079 blocks and pulls its far side 0.119-0.120 blocks off the geometry it rests on. That
 * is a rigid turn, and no amount of spring tuning removes it, because it is the piece's own swing
 * limit that saturates. Holding the piece rigid gives 0.000 and 0.000.
 *
 * <p>No rule can find those pieces: measured over the corpus, "the piece has a contact patch above
 * it" is true of 59 of 59 simulated pieces of that model and 17,415 of 18,350 corpus pieces, and the
 * four candidate structural rules agree with each other on 26.5 per cent of pieces - the maid's own
 * fox tail alternates across the decision line, so a rule would freeze four of its seven links and
 * tear the chain. This file is therefore the answer for exactly this case, and it is a <b>choice</b>:
 * nothing is shipped for any model, and with no file present every model's behaviour is the shipped
 * one, byte for byte.
 *
 * <pre>
 * config/ysm_epicfight_compat/physics_overrides/&lt;model&gt;.json
 *
 * { "rigid": ["BaseHair"] }
 * </pre>
 *
 * <p>The per-model JSON is keyed by the YSM model id (the same id the model packages and the server
 * sync use), which is why these live in one config folder rather than beside the model: encrypted
 * {@code .ysm} packages cannot carry a sidecar file, and the model packages are read-only inputs this
 * mod does not own. The id may contain a path ("group/model"), and the file is then nested the same
 * way, exactly like {@link YsmBoneOverrides}'s {@code bone_overrides} folder - which also means the
 * two schemes cannot collide on a model whose id contains what the other scheme would sanitize away.
 *
 * <h2>What the file does and does not change</h2>
 *
 * <p>A held bone keeps its segment, its parent link, its place in the chain allowance and its mesh
 * parts: the piece is still simulated <i>for</i>, it simply has no swing. Selection runs before any of
 * this and is not consulted, so nothing is dropped, nothing is re-classified, and no other piece's
 * own swing changes - a piece held rigid is the identity delta written where its swing used to go.
 * Its children compose under that identity, which is what keeps a chain attached: a strand hanging
 * under a held piece keeps swinging, from wherever the pose put the piece it hangs from.
 *
 * <p>Reading is quiet by design, because the normal state of this folder is "absent": a missing
 * file, a missing folder, an empty list and a file for a model that is never drawn all cost nothing
 * and log nothing. Three cases do speak, once per model each: an override that holds something (one
 * INFO line naming the bones, the line to grep), a bone the file names that holds nothing here (one
 * WARN), and a file that cannot be read as an object with a {@code rigid} array (one WARN, and
 * nothing is applied).
 */
public final class YsmPhysicsOverrides {

    private static final Path CONFIG_ROOT = Paths.get("config", "ysm_epicfight_compat");
    private static final Path OVERRIDE_DIR = CONFIG_ROOT.resolve("physics_overrides");

    /** The one key this file format reads. */
    private static final String RIGID_KEY = "rigid";

    /**
     * The second key: a per-bone ceiling on the swing the solver may keep, in degrees.
     *
     * <p>Where {@code rigid} removes a piece's motion, this only bounds it. It exists because a
     * piece can be at its <i>chain's</i> allowance in every frame and therefore have no spring left
     * to give back - measured on this model's fox tail, whose seven links each sit at their granted
     * 18.3 degrees in every frame of every state, so the tip is where seven clamps add up (69
     * degrees, against 39 for the hair that looks right) and its direction follows the airflow 1:1.
     * A ceiling here does not restore the spring; it bounds what the chain's clamped links can add
     * up to, which is the visible quantity.
     *
     * <pre>
     * { "rigid": ["BaseHair"], "limitDeg": { "Tail5": 8, "Tail6": 8, "Tail7": 8 } }
     * </pre>
     */
    private static final String LIMIT_KEY = "limitDeg";

    /** Bone names are matched the way YSM writes them, but not case-sensitively. */
    private static final Map<String, Set<String>> RIGID_CACHE = new ConcurrentHashMap<>();

    /** The per-bone swing ceilings this model's file asks for, radians, keyed the same way. */
    private static final Map<String, Map<String, Float>> LIMIT_CACHE = new ConcurrentHashMap<>();

    /** Models already reported, so each of the lines below is written at most once per model. */
    private static final Set<String> LOGGED_MODELS = ConcurrentHashMap.newKeySet();

    private YsmPhysicsOverrides() {}

    // ------------------------------------------------------------------
    // The frame path's entry point
    // ------------------------------------------------------------------

    /**
     * Mark the segments this model's override file names as held, and report what it did.
     *
     * <p>Called once per model, where its segments are built. The array is left alone unless a file
     * exists, so the frame path's {@code held} branch is unreachable for every model without one -
     * which is what makes the no-file case the shipped behaviour rather than a re-derivation of it.
     *
     * @param modelId  the YSM model id (may be null)
     * @param bones    the model's runtime bone table, for telling "not a bone of this model" from
     *                 "a bone that is not a piece the simulation moves" (may be null)
     * @param segments the model's physics pieces
     * @param held     the flags to set, indexed like {@code segments}
     * @return how many pieces were held
     */
    static int markHeld(String modelId, YSMRuntimeModel.BoneRt[] bones,
                        YsmPhysicsParts.Segment[] segments, boolean[] held) {
        return markOverrides(modelId, bones, segments, held, null);
    }

    /**
     * Apply this model's whole override file: the bones held rigid and the bones whose swing is
     * capped, and report what it did - once per model, whichever of the two keys did something.
     *
     * <p>Called once per model, where its segments are built. Both arrays are left alone unless a
     * file exists, so the frame path's {@code held} and {@code limit} branches are unreachable for
     * every model without one - which is what makes the no-file case the shipped behaviour rather
     * than a re-derivation of it.
     *
     * @param modelId  the YSM model id (may be null)
     * @param bones    the model's runtime bone table, for telling "not a bone of this model" from
     *                 "a bone that is not a piece the simulation moves" (may be null)
     * @param segments the model's physics pieces
     * @param held     the rigid flags to set, indexed like {@code segments} (may be null)
     * @param limits   the per-bone ceilings in radians to set, or null for "no ceilings"
     * @return how many pieces were held
     */
    static int markOverrides(String modelId, YSMRuntimeModel.BoneRt[] bones,
                             YsmPhysicsParts.Segment[] segments, boolean[] held, float[] limits) {
        if (segments == null || segments.length == 0 || (held == null && limits == null)) {
            return 0;
        }
        Set<String> names = rigidBones(modelId);
        Map<String, Float> ceilings = limits(modelId);
        if (names.isEmpty() && ceilings.isEmpty()) {
            return 0;
        }
        List<String> notPieces = new ArrayList<>();
        List<String> notBones = new ArrayList<>();
        int count = held == null ? 0 : hold(names, bones, segments, held, notBones, notPieces);
        int capped = limits == null ? 0
                : cap(ceilings, bones, segments, limits, notBones, notPieces);
        if (LOGGED_MODELS.add(modelId == null ? "" : modelId)) {
            if (count > 0 || capped > 0) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [physics] model '{}':{}{} from physics_overrides/{}.json [{}]",
                        modelId,
                        count > 0 ? " " + count + " bone(s) held rigid" : "",
                        capped > 0 ? " " + capped + " bone(s) with a swing ceiling ("
                                + ceilingsOf(segments, limits) + ")" : "",
                        relativeFile(modelId), namesOf(segments, held));
            }
            if (!notBones.isEmpty() || !notPieces.isEmpty()) {
                // One warning, because the user has to be able to see that the file was read and
                // which of its entries did nothing - a silent no-op is the worst outcome here: the
                // piece keeps sliding and nothing says the name was never matched.
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: [physics] model '{}': bone name(s) in physics_overrides/{}.json hold nothing: {}{}{}",
                        modelId, relativeFile(modelId),
                        notBones.isEmpty() ? "" : quote(notBones) + " not on this model",
                        notBones.isEmpty() || notPieces.isEmpty() ? "" : "; ",
                        notPieces.isEmpty() ? ""
                                : quote(notPieces) + " on this model but not a piece the simulation moves, so it already follows its joint");
            }
        }
        return count;
    }

    /**
     * The decision for the ceilings: which pieces a map of name to degrees caps, and why the rest
     * cap nothing. Ceilings are stored in radians, because that is the unit the frame path compares
     * in; a file's {@code 8} is eight degrees.
     *
     * @return how many flags this changed
     */
    static int cap(Map<String, Float> ceilings, YSMRuntimeModel.BoneRt[] bones,
                   YsmPhysicsParts.Segment[] segments, float[] limits,
                   List<String> notBones, List<String> notPieces) {
        if (ceilings == null || ceilings.isEmpty() || segments == null || limits == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, Float> entry : ceilings.entrySet()) {
            String name = entry.getKey();
            float radians = entry.getValue() == null ? 0.0F : entry.getValue();
            if (name == null || name.isEmpty() || !(radians > 0.0F)) {
                continue;
            }
            int matched = 0;
            for (int i = 0; i < segments.length && i < limits.length; i++) {
                YsmPhysicsParts.Segment segment = segments[i];
                String boneName = segment == null ? null : segment.boneName();
                if (boneName == null || !boneName.toLowerCase(Locale.ROOT).equals(name)) {
                    continue;
                }
                matched++;
                // Only ever tightens: a ceiling above what the chain already grants is the same as
                // no ceiling, and writing it would make the log claim a limit that never bites.
                float ceiling = radians;
                if (limits[i] <= 0.0F || ceiling < limits[i]) {
                    limits[i] = ceiling;
                    count++;
                }
            }
            if (matched > 0) {
                continue;
            }
            if (notBones == null && notPieces == null) {
                continue;
            }
            if (hasBone(bones, name)) {
                if (notPieces != null) {
                    notPieces.add(name);
                }
            } else if (notBones != null) {
                notBones.add(name);
            }
        }
        return count;
    }

    /**
     * The decision itself: which pieces a list of names holds, and why the rest of the names hold
     * nothing. Kept separate from reading the file so the matching rules can be tested directly, and
     * so the frame path has exactly one place where a flag is ever set.
     *
     * @param names     the bone names to hold, as the file wrote them (lower-cased)
     * @param bones     the model's bone table, or null; tells the two reasons apart
     * @param segments  the model's pieces
     * @param held      the flags to set, indexed like {@code segments}
     * @param notBones  filled with names that are not bones of this model at all, or null
     * @param notPieces filled with names that are bones of it but not pieces it simulates, or null
     * @return how many flags this changed
     */
    static int hold(Set<String> names, YSMRuntimeModel.BoneRt[] bones,
                    YsmPhysicsParts.Segment[] segments, boolean[] held,
                    List<String> notBones, List<String> notPieces) {
        if (names == null || names.isEmpty() || segments == null || held == null) {
            return 0;
        }
        int count = 0;
        for (String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            // Every piece of that name, not the first one found: a bone name identifies a bone, and
            // if a damaged bone table ever carried one twice, holding half of it would leave the
            // other half sliding with nothing in the log to say why.
            int matched = 0;
            for (int i = 0; i < segments.length && i < held.length; i++) {
                YsmPhysicsParts.Segment segment = segments[i];
                String boneName = segment == null ? null : segment.boneName();
                if (boneName == null || !boneName.toLowerCase(Locale.ROOT).equals(name)) {
                    continue;
                }
                matched++;
                if (!held[i]) {
                    held[i] = true;
                    count++;
                }
            }
            if (matched > 0) {
                continue;
            }
            if (notBones == null && notPieces == null) {
                continue;
            }
            if (hasBone(bones, name)) {
                if (notPieces != null) {
                    notPieces.add(name);
                }
            } else if (notBones != null) {
                notBones.add(name);
            }
        }
        return count;
    }

    /**
     * The names this model's override file asks to hold, lower-cased, or an empty set.
     *
     * <p>Empty is the answer for every quiet case: no file, no folder, an empty list, a model id that
     * cannot name a file, or a file that could not be read (which has already warned).
     */
    static Set<String> rigidBones(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return Set.of();
        }
        Set<String> cached = RIGID_CACHE.get(modelId);
        if (cached != null) {
            return cached;
        }
        Set<String> read = read(OVERRIDE_DIR, modelId);
        RIGID_CACHE.put(modelId, read);
        return read;
    }

    /**
     * The per-bone swing ceilings this model's file asks for, in radians, or an empty map.
     *
     * <p>Empty is the answer for every quiet case, exactly as for the rigid set: no file, no folder,
     * no {@code limitDeg} object, a model id that cannot name a file, or a file that could not be
     * read (which has already warned).
     */
    static Map<String, Float> limits(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return Map.of();
        }
        Map<String, Float> cached = LIMIT_CACHE.get(modelId);
        if (cached != null) {
            return cached;
        }
        Map<String, Float> read = readLimits(OVERRIDE_DIR, modelId);
        LIMIT_CACHE.put(modelId, read);
        return read;
    }

    // ------------------------------------------------------------------
    // The file format
    // ------------------------------------------------------------------

    /**
     * Read one model's override file from one folder. The whole of the parsing, and the one place a
     * malformed file is reported; deliberately uncached so the rules can be tested against a
     * temporary folder rather than against the game's config.
     *
     * @return the bone names to hold, lower-cased; empty when there is nothing to hold
     */
    static Set<String> read(Path dir, String modelId) {
        JsonObject root = rootOf(dir, modelId, RIGID_KEY);
        if (root == null) {
            return Set.of();
        }
        JsonElement element = root.get(RIGID_KEY);
        if (element == null) {
            return Set.of();
        }
        if (!element.isJsonArray()) {
            // Said out loud rather than treated as an empty list: a misspelled key is the one
            // mistake the format cannot otherwise distinguish from "hold nothing", and its
            // symptom on screen is the defect the file was written to fix.
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: physics override file {} has a '{}' that is not an array of bone "
                            + "names - ignored",
                    fileFor(dir, modelId), RIGID_KEY);
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                String name = item.getAsString().trim();
                if (!name.isEmpty()) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Read one model's swing ceilings from one folder. The same file as {@link #read}, the other
     * key; deliberately uncached for the same reason, so the rules can be tested against a temporary
     * folder rather than against the game's config.
     *
     * @return the bone names to cap, lower-cased, with their ceilings in radians
     */
    static Map<String, Float> readLimits(Path dir, String modelId) {
        JsonObject root = rootOf(dir, modelId, LIMIT_KEY);
        if (root == null) {
            return Map.of();
        }
        JsonElement element = root.get(LIMIT_KEY);
        if (element == null) {
            return Map.of();
        }
        if (!element.isJsonObject()) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: physics override file {} has a '{}' that is not an object of "
                            + "bone name to degrees - ignored",
                    fileFor(dir, modelId), LIMIT_KEY);
            return Map.of();
        }
        Map<String, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            JsonElement value = entry.getValue();
            if (name.isEmpty() || value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: physics override file {} has a '{}' entry that is not a "
                                + "number of degrees: '{}' - skipped",
                        fileFor(dir, modelId), LIMIT_KEY, name);
                continue;
            }
            float degrees = value.getAsFloat();
            if (!Float.isFinite(degrees) || degrees <= 0.0F) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: physics override file {} has '{}' {} set to {} - a ceiling "
                                + "must be a positive number of degrees, so it is skipped",
                        fileFor(dir, modelId), LIMIT_KEY, name, degrees);
                continue;
            }
            out.put(name.toLowerCase(Locale.ROOT), (float) Math.toRadians(degrees));
        }
        return Map.copyOf(out);
    }

    /**
     * The parsed object of one model's override file, or null when there is nothing to read. The one
     * place a file that is not an object, or cannot be read at all, is reported - and it is reported
     * only when the file carries <b>neither</b> of the two keys this format knows, so a file that
     * uses only one of them is not scolded for the other.
     *
     * @param askedFor the key whose reader is asking, named in the warning
     */
    private static JsonObject rootOf(Path dir, String modelId, String askedFor) {
        Path file = fileFor(dir, modelId);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: physics override file {} is not a JSON object - ignored", file);
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            // Warned about by the first reader only - the frame path always asks the rigid list
            // first - so a file that uses neither key is reported once rather than once per key.
            if (!root.has(RIGID_KEY) && !root.has(LIMIT_KEY) && RIGID_KEY.equals(askedFor)) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: physics override file {} has neither a '{}' array of bone "
                                + "names nor a '{}' object of bone name to degrees - ignored",
                        file, RIGID_KEY, LIMIT_KEY);
                return null;
            }
            return root;
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: could not read physics override file {} - ignored", file, t);
            return null;
        }
    }

    /**
     * The override file for a model id. Model ids are untrusted (they arrive from the server sync and
     * from player NBT) and may contain path separators, so the id is flattened through the same
     * sanitizer the generated resource pack and the bone overrides use - which keeps the separators
     * that make a nested model id readable and neutralizes every traversal segment.
     */
    static Path fileFor(Path dir, String modelId) {
        if (dir == null || modelId == null || modelId.isEmpty()) {
            return null;
        }
        try {
            String sanitized = TextureStore.sanitize(modelId);
            if (sanitized == null || sanitized.isEmpty()) {
                return null;
            }
            Path file = dir.resolve(sanitized + ".json").normalize();
            // Defense in depth: sanitize() already removes traversal segments, so this can only
            // fail if a future caller hands in a root the id escapes - in which case no file is
            // read rather than a file outside the config folder.
            return file.startsWith(dir.normalize()) ? file : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The path as the user writes it, for the log: relative to the config folder. */
    private static String relativeFile(String modelId) {
        Path file = fileFor(OVERRIDE_DIR, modelId);
        if (file == null) {
            return "?";
        }
        return modelId == null ? file.getFileName().toString()
                : TextureStore.sanitize(modelId);
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    private static int indexOf(YsmPhysicsParts.Segment[] segments, String name) {
        for (int i = 0; i < segments.length; i++) {
            String boneName = segments[i] == null ? null : segments[i].boneName();
            if (boneName != null && boneName.toLowerCase(Locale.ROOT).equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasBone(YSMRuntimeModel.BoneRt[] bones, String name) {
        if (bones == null) {
            return false;
        }
        for (YSMRuntimeModel.BoneRt bone : bones) {
            if (bone != null && bone.name != null && bone.name.toLowerCase(Locale.ROOT).equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** The names held, in segment order, for the log. */
    private static String namesOf(YsmPhysicsParts.Segment[] segments, boolean[] held) {
        if (held == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.length && i < held.length; i++) {
            if (held[i]) {
                builder.append(builder.length() == 0 ? "" : ", ").append(segments[i].boneName());
            }
        }
        return builder.toString();
    }

    /** The ceilings in force, in segment order, for the log: {@code Tail5<=8.0deg, ...}. */
    private static String ceilingsOf(YsmPhysicsParts.Segment[] segments, float[] limits) {
        if (limits == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.length && i < limits.length; i++) {
            if (limits[i] > 0.0F) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(segments[i].boneName()).append("<=")
                        .append(String.format(Locale.ROOT, "%.1f", Math.toDegrees(limits[i])))
                        .append("deg");
            }
        }
        return builder.toString();
    }

    private static String quote(List<String> names) {
        StringBuilder builder = new StringBuilder();
        for (String name : names) {
            builder.append(builder.length() == 0 ? "" : ", ").append('\'').append(name).append('\'');
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Drop every cache; the files are re-read on the next mesh that needs them. */
    public static synchronized void invalidate() {
        RIGID_CACHE.clear();
        LIMIT_CACHE.clear();
        LOGGED_MODELS.clear();
    }

    /** The folder users put these files in, for log/help output. */
    public static Path configDir() {
        return CONFIG_ROOT;
    }

    /** The override folder itself: {@code config/ysm_epicfight_compat/physics_overrides}. */
    static Path overrideDir() {
        return OVERRIDE_DIR;
    }
}
