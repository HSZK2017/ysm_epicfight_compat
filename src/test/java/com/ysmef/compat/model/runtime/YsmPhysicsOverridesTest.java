package com.ysmef.compat.model.runtime;

import com.ysmef.compat.YSMEpicFightCompat;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the per-model physics override: the file format, the decision, and what the frame path does
 * with it.
 *
 * <p>The feature exists because a bone that is not a hanging piece can still be classified as one,
 * and no structural rule separates the two: measured on {@code wine_fox/01_taisho_maid}, holding the
 * top-of-head cap {@code BaseHair} rigid takes its far-side separation from 0.119 blocks to 0.000 and
 * moves no other piece of the model at all, while the four candidate rules that would have found it
 * automatically agree with each other on 26.5 per cent of the corpus and freeze four of that model's
 * own tail links. So the list is written by a person, one file per model, and the rules pinned here
 * are the ones a user cannot see from the format alone:
 *
 * <ul>
 *   <li>every quiet case is quiet - no folder, no file, an empty list, a model that is never drawn -
 *       and none of them touches a single flag;</li>
 *   <li>a file that cannot be read holds nothing and says so once, because its symptom on screen is
 *       the defect the file was written to fix;</li>
 *   <li>a bone the file names that holds nothing is reported once, and the two reasons are told
 *       apart: a name that is not on the model at all, and a name that is a bone of it but not a
 *       piece the simulation moves (which already follows its joint).</li>
 * </ul>
 *
 * <p>Reading is exercised against a temporary folder - {@link YsmPhysicsOverrides#read} takes the
 * directory - so these tests never touch the game's config, and the frame path is driven through
 * {@link YsmMeshSecondaryMotion#simulate}, the shipped loop.
 */
class YsmPhysicsOverridesTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------------
    // The file format
    // ------------------------------------------------------------------

    @Test
    void aValidFileNamesTheBonesToHold() throws IOException {
        write("model.json", "{\"rigid\": [\"BaseHair\", \"FFM1\"]}");

        assertEquals(Set.of("basehair", "ffm1"), YsmPhysicsOverrides.read(dir, "model"),
                "the file's names are read as written, and matched without case");
    }

    @Test
    void aMissingFileHoldsNothing() {
        assertTrue(YsmPhysicsOverrides.read(dir, "model").isEmpty(),
                "a model with no file must hold nothing");
        assertTrue(YsmPhysicsOverrides.read(dir.resolve("not-even-a-folder"), "model").isEmpty(),
                "and a missing folder is the same answer as a missing file");
        assertTrue(YsmPhysicsOverrides.read(dir, "group/model").isEmpty(),
                "including the nested form of a model id");
    }

    /**
     * The one mistake the format cannot distinguish from "hold nothing" by itself: a key that is not
     * {@code rigid}. It is reported rather than silently obeyed as an empty list, because the
     * symptom of a silent no-op is the defect the file was written to fix.
     */
    @Test
    void aFileWithNoRigidArrayIsIgnoredAndWarnedAbout() throws IOException {
        write("model.json", "{\"hold\": [\"BaseHair\"]}");

        List<String> log = captureLog(() ->
                assertTrue(YsmPhysicsOverrides.read(dir, "model").isEmpty()));

        assertEquals(1, count(log, Level.WARN), "exactly one warning, and it is a warning");
        assertTrue(log.get(0).contains("model.json"),
                "and it names the file the user has to edit: " + log);
        assertTrue(log.get(0).contains("rigid"), "and the key it expected: " + log.get(0));
    }

    @Test
    void aFileThatIsNotAnObjectIsIgnoredAndWarnedAbout() throws IOException {
        write("model.json", "[ \"BaseHair\" ]");

        List<String> log = captureLog(() ->
                assertTrue(YsmPhysicsOverrides.read(dir, "model").isEmpty()));

        assertEquals(1, count(log, Level.WARN));
        assertTrue(log.get(0).contains("not a JSON object"), log.get(0));
    }

    @Test
    void aFileThatIsNotJsonAtAllIsIgnoredAndWarnedAbout() throws IOException {
        write("model.json", "{\"rigid\": [\"BaseHair\"");

        List<String> log = captureLog(() ->
                assertTrue(YsmPhysicsOverrides.read(dir, "model").isEmpty()));

        assertEquals(1, count(log, Level.WARN));
        assertTrue(log.get(0).contains("could not read"), log.get(0));
    }

    /**
     * The deliberate empty list: a user who wants to hold nothing writes nothing, and the loader has
     * no business asking about it once per frame or once per launch. It is silent, and it holds
     * nothing - which is the whole of what the file says.
     */
    @Test
    void anEmptyListHoldsNothingAndIsSilent() throws IOException {
        write("model.json", "{\"rigid\": []}");

        List<String> log = captureLog(() ->
                assertTrue(YsmPhysicsOverrides.read(dir, "model").isEmpty()));

        assertTrue(log.isEmpty(), "an empty list is a choice, not a mistake: " + log);
    }

    @Test
    void entriesThatAreNotNamesAreSkipped() throws IOException {
        write("model.json", "{\"rigid\": [\"BaseHair\", 7, null, {\"name\": \"FFM1\"}, \"  \", \" FR \"]}");

        assertEquals(Set.of("basehair", "fr"), YsmPhysicsOverrides.read(dir, "model"),
                "a name is a non-empty string, trimmed; anything else is not a bone name");
    }

    @Test
    void theFileIsNestedForAModelIdThatContainsAPath() {
        Path file = YsmPhysicsOverrides.fileFor(dir, "wine_fox/01_taisho_maid");

        assertNotNull(file);
        assertEquals(dir.resolve("wine_fox").resolve("01_taisho_maid.json"), file,
                "a model id with a separator names a file in a folder of the same shape");
    }

    /**
     * Model ids are untrusted - they arrive from the server sync and from player NBT - so the id is
     * flattened the same way the generated resource pack flattens it, and the resolved path is
     * checked to stay inside the override folder. A traversal that resolved would let a model
     * package read a file of its choosing, and a file for a model that does not exist is not a
     * reason to fail open.
     */
    @Test
    void fileForNeverLeavesItsFolder() {
        for (String hostile : new String[]{"../../secret", "..\\..\\secret", "/etc/passwd", "a/../../b"}) {
            Path file = YsmPhysicsOverrides.fileFor(dir, hostile);
            if (file != null) {
                assertTrue(file.normalize().startsWith(dir.normalize()),
                        hostile + " resolved outside the override folder: " + file);
            }
        }
        assertNull(YsmPhysicsOverrides.fileFor(dir, ""), "an empty id names no file");
        assertNull(YsmPhysicsOverrides.fileFor(null, "model"), "and neither does no folder");
    }

    /**
     * The cache is what makes "one lookup per model, ever" true - the frame path asks for the names
     * where a model's pieces are built, not per frame - and it is also what would serve a stale
     * answer after the user edits the file, so it has to be droppable and the drop has to be
     * complete.
     *
     * <p>This is the one test that writes into the real config folder, because the path join and the
     * cache are the two things a temporary folder cannot exercise. It uses a model id no other test
     * and no install has a file for, and removes what it wrote whatever happens.
     */
    @Test
    void theFileIsReadOncePerModelAndTheCacheCanBeDropped() throws IOException {
        String modelId = "ysmef/override_test_model";
        Path file = YsmPhysicsOverrides.fileFor(YsmPhysicsOverrides.overrideDir(), modelId);
        assertNotNull(file, "a model id must name a file inside the config folder");
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, "{\"rigid\": [\"BaseHair\"]}", StandardCharsets.UTF_8);
            YsmPhysicsOverrides.invalidate();
            assertEquals(Set.of("basehair"), YsmPhysicsOverrides.rigidBones(modelId));

            Files.writeString(file, "{\"rigid\": [\"FFM1\"]}", StandardCharsets.UTF_8);
            assertEquals(Set.of("basehair"), YsmPhysicsOverrides.rigidBones(modelId),
                    "the answer is cached: the file is not re-read for every model instance");

            YsmPhysicsOverrides.invalidate();
            assertEquals(Set.of("ffm1"), YsmPhysicsOverrides.rigidBones(modelId),
                    "and a resource reload re-reads the edit, so a user does not restart the game");
        } finally {
            Files.deleteIfExists(file);
            YsmPhysicsOverrides.invalidate();
            // The whole chain the test created, bottom up: the id's own folder, the override folder,
            // the mod's config folder and the config folder itself. Each is removed only while it is
            // empty, so anything a real user or another test put there stays.
            Path at = file.getParent();
            for (int up = 0; up < 4 && at != null; up++) {
                deleteIfEmpty(at);
                at = at.getParent();
            }
        }
    }

    private static void deleteIfEmpty(Path directory) {
        try {
            if (directory != null && Files.isDirectory(directory)) {
                try (var entries = Files.list(directory)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(directory);
                    }
                }
            }
        } catch (IOException ignored) {
            // Left in place rather than failing a test over a directory: an empty
            // config/ysm_epicfight_compat/physics_overrides is what the feature creates anyway.
        }
    }

    // ------------------------------------------------------------------
    // The decision
    // ------------------------------------------------------------------

    @Test
    void onlyTheNamedPiecesAreHeld() {
        YsmPhysicsParts.Segment[] segments = segments("BaseHair", "Tail", "Bangs");
        boolean[] held = new boolean[3];

        int count = YsmPhysicsOverrides.hold(Set.of("bangs"), bones(), segments, held, null, null);

        assertEquals(1, count, "one name, one piece");
        assertFalse(held[0], "an unnamed piece must keep swinging");
        assertFalse(held[1]);
        assertTrue(held[2], "the named piece is held, whatever case it was typed in");
    }

    @Test
    void aNameThatIsNotOnTheModelHoldsNothingAndIsReported() {
        YsmPhysicsParts.Segment[] segments = segments("BaseHair");
        boolean[] held = new boolean[1];
        List<String> notBones = new ArrayList<>();
        List<String> notPieces = new ArrayList<>();

        int count = YsmPhysicsOverrides.hold(Set.of("baselair", "tail"), bones("BaseHair", "Head"),
                segments, held, notBones, notPieces);

        assertEquals(0, count, "a name that matches nothing holds nothing");
        assertFalse(held[0], "and it must not hold the piece it was nearly spelled like");
        assertEquals(Set.copyOf(List.of("baselair", "tail")), Set.copyOf(notBones),
                "a name that is not on the model at all is reported as such");
        assertTrue(notPieces.isEmpty(), "and is not confused with the other reason");
    }

    @Test
    void aBoneThatIsNotAPieceIsReportedSeparately() {
        YsmPhysicsParts.Segment[] segments = segments("BaseHair");
        boolean[] held = new boolean[1];
        List<String> notBones = new ArrayList<>();
        List<String> notPieces = new ArrayList<>();

        int count = YsmPhysicsOverrides.hold(Set.of("head"), bones("BaseHair", "Head"),
                segments, held, notBones, notPieces);

        assertEquals(0, count, "a body bone is not simulated, so holding it changes nothing");
        assertTrue(notBones.isEmpty(), "but it IS a bone of this model: the two cases are different");
        assertEquals(List.of("head"), notPieces);
    }

    @Test
    void holdingAPieceTwiceCountsOnce() {
        YsmPhysicsParts.Segment[] segments = segments("BaseHair", "BaseHair");
        boolean[] held = new boolean[2];

        int count = YsmPhysicsOverrides.hold(Set.of("basehair"), bones(), segments, held, null, null);

        assertEquals(2, count, "both pieces of that name are held, and each flag is one change");
        assertTrue(held[0] && held[1]);
    }

    @Test
    void anEmptyFileListLeavesEveryFlagAlone() {
        YsmPhysicsParts.Segment[] segments = segments("BaseHair", "Tail");
        boolean[] held = new boolean[2];

        int count = YsmPhysicsOverrides.hold(Set.of(), bones(), segments, held, null, null);

        assertEquals(0, count);
        assertFalse(held[0] || held[1], "no names means no flags, and that is the shipped behaviour");
    }

    /** A name list that arrives for no model at all - a null id - is the same as an empty one. */
    @Test
    void aNullModelIdHoldsNothing() {
        boolean[] held = new boolean[1];
        assertEquals(0, YsmPhysicsOverrides.markHeld(null, bones(), segments("BaseHair"), held));
        assertFalse(held[0]);
    }

    // ------------------------------------------------------------------
    // The frame path
    // ------------------------------------------------------------------

    /**
     * The behavioural statement: a held piece has <b>no</b> delta - not a small one - while the piece
     * beside it, and the piece hanging under it, keep the swing they have.
     *
     * <p>Driven through the shipped loop ({@link YsmMeshSecondaryMotion#simulate}) over the shipped
     * state, with the flags set the way {@code markHeld} sets them from a file's own name list. The
     * two runs differ in one boolean, so anything that moves between them is this feature.
     */
    @Test
    void aHeldPieceHasNoDeltaAndItsNeighboursAreUntouched() {
        YsmPhysicsParts.Segment[] segments = chain();
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.BONE_NAMES, 0);
        YsmMeshSecondaryMotion.State control = run(model, Set.of());
        YsmMeshSecondaryMotion.State held = run(model, Set.of("tail"));

        assertFalse(same(control.deltas[0], new OpenMatrix4f()),
                "the piece under test must actually swing in the control, or this proves nothing");
        assertTrue(same(held.deltas[0], new OpenMatrix4f()),
                "held rigid is the identity delta, entry for entry");
        assertFalse(held.integrated[0], "and a piece that is not simulated is not handed to the solver");

        // Its child still swings: it composes under the held piece instead of under a swinging one.
        assertFalse(same(held.deltas[1], new OpenMatrix4f()),
                "a strand hanging under a held piece keeps swinging");
        assertTrue(held.integrated[1], "and is still integrated on its own pivot");

        // The piece beside it - the other root of the model - is digit for digit what it was. This is
        // the whole "blast radius" claim, at the level of one model's own pieces.
        assertTrue(same(control.deltas[2], held.deltas[2]),
                "a piece under no held piece must have exactly the delta it had");
        assertTrue(control.integrated[2] && held.integrated[2]);
        assertEquals(control.lastDegrees[2], held.lastDegrees[2],
                "and the same own swing, to the digit");
    }

    /**
     * And with no override at all, nothing changes: the flags stay false, the loader writes no line,
     * and every delta is bit-identical to the run that never asked the loader anything.
     *
     * <p>This is the zero-blast-radius statement, and it is asserted as an equality of matrices
     * rather than as a tolerance: "behaves as it does today" means the same numbers, not numbers
     * close to them. The model id is one no install has a file for, and
     * {@link YsmPhysicsOverrides#markHeld} is the same call the frame path makes.
     */
    @Test
    void withNoOverrideFileEveryDeltaIsExactlyWhatItWas() {
        YsmPhysicsParts.Segment[] segments = chain();
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.BONE_NAMES, 0);

        YsmMeshSecondaryMotion.State untouched = run(model, Set.of());

        YsmMeshSecondaryMotion.State loaded = run(model, Set.of());
        boolean[] held = new boolean[segments.length];
        int marked = YsmPhysicsOverrides.markHeld("ysmef/test/no-such-model-file",
                bones("Tail", "Tail2", "Tail3"), segments, held);

        assertEquals(0, marked, "with no file for that id, nothing is held");
        assertFalse(held[0] || held[1] || held[2], "and every flag is left exactly as it was");
        for (int i = 0; i < segments.length; i++) {
            assertTrue(same(untouched.deltas[i], loaded.deltas[i]),
                    "segment " + i + " must be the same matrix it was without the override");
            assertTrue(loaded.integrated[i], "and still integrated: the feature is not in the way");
        }
    }

    // ------------------------------------------------------------------
    // The swing ceiling
    // ------------------------------------------------------------------

    @Test
    void aLimitFileNamesTheBonesAndTheirDegrees() throws IOException {
        write("model.json", "{\"limitDeg\": {\"Tail5\": 8, \"tail6\": 2.5}}");

        Map<String, Float> limits = YsmPhysicsOverrides.readLimits(dir, "model");

        assertEquals(2, limits.size(), "both entries are read: " + limits);
        assertEquals(8.0F, Math.toDegrees(limits.get("tail5")), 1.0E-3F,
                "a file's number is degrees, and the frame path's unit is radians");
        assertEquals(2.5F, Math.toDegrees(limits.get("tail6")), 1.0E-3F,
                "and the name is matched without case, like the rigid list");
    }

    /**
     * A file that uses only the second key must not be told it has no first key. This is the one
     * case the two readers could disagree about, and the warning is the thing users see.
     */
    @Test
    void aFileWithOnlyALimitIsNotScoldedForTheMissingRigidArray() throws IOException {
        write("model.json", "{\"limitDeg\": {\"Tail5\": 8}}");

        List<String> log = captureLog(() -> {
            assertTrue(YsmPhysicsOverrides.read(dir, "model").isEmpty(),
                    "there is no rigid list in this file, so nothing is held");
            assertEquals(1, YsmPhysicsOverrides.readLimits(dir, "model").size(),
                    "and the ceiling is read");
        });

        assertEquals(0, count(log, Level.WARN),
                "a file that uses one of the two keys correctly must be silent: " + log);
    }

    @Test
    void aLimitFileWithNeitherKeyIsWarnedAboutOnce() throws IOException {
        write("model.json", "{\"hold\": [\"BaseHair\"]}");

        List<String> log = captureLog(() -> {
            assertTrue(YsmPhysicsOverrides.read(dir, "model").isEmpty());
            assertTrue(YsmPhysicsOverrides.readLimits(dir, "model").isEmpty());
        });

        assertEquals(1, count(log, Level.WARN),
                "one warning for the file, not one per reader: " + log);
        assertTrue(log.get(0).contains("limitDeg"),
                "and it names both keys it could have used: " + log.get(0));
    }

    @Test
    void aLimitThatIsNotANumberIsSkippedAndWarnedAbout() throws IOException {
        write("model.json", "{\"limitDeg\": {\"Tail5\": \"eight\"}}");

        List<String> log = captureLog(() ->
                assertTrue(YsmPhysicsOverrides.readLimits(dir, "model").isEmpty()));

        assertEquals(1, count(log, Level.WARN));
        assertTrue(log.get(0).contains("Tail5"), "the entry that did nothing is named: " + log.get(0));
    }

    @Test
    void aLimitThatIsNotPositiveIsSkippedAndWarnedAbout() throws IOException {
        write("model.json", "{\"limitDeg\": {\"Tail5\": 0, \"Tail6\": -4}}");

        List<String> log = captureLog(() ->
                assertTrue(YsmPhysicsOverrides.readLimits(dir, "model").isEmpty()));

        assertEquals(2, count(log, Level.WARN), "one per entry that cannot be a ceiling: " + log);
    }

    @Test
    void aLimitDegThatIsNotAnObjectIsIgnoredAndWarnedAbout() throws IOException {
        write("model.json", "{\"limitDeg\": [8]}");

        List<String> log = captureLog(() ->
                assertTrue(YsmPhysicsOverrides.readLimits(dir, "model").isEmpty()));

        assertEquals(1, count(log, Level.WARN));
        assertTrue(log.get(0).contains("limitDeg"), log.get(0));
    }

    @Test
    void onlyTheNamedPiecesAreCapped() {
        YsmPhysicsParts.Segment[] segments = segments("BaseHair", "Tail", "Bangs");
        float[] limits = new float[3];

        int count = YsmPhysicsOverrides.cap(Map.of("bangs", (float) Math.toRadians(6.0)),
                bones(), segments, limits, null, null);

        assertEquals(1, count, "one name, one piece");
        assertEquals(0.0F, limits[0], "an unnamed piece has no ceiling");
        assertEquals(0.0F, limits[1]);
        assertEquals(6.0F, Math.toDegrees(limits[2]), 1.0E-3F,
                "the named piece's ceiling is the file's, in radians");
    }

    @Test
    void aCeilingOnlyEverTightens() {
        YsmPhysicsParts.Segment[] segments = segments("Tail");
        float[] limits = new float[1];

        YsmPhysicsOverrides.cap(Map.of("tail", (float) Math.toRadians(8.0)), bones(), segments,
                limits, null, null);
        YsmPhysicsOverrides.cap(Map.of("tail", (float) Math.toRadians(20.0)), bones(), segments,
                limits, null, null);
        assertEquals(8.0F, Math.toDegrees(limits[0]), 1.0E-3F,
                "a wider ceiling must not undo a narrower one");
        YsmPhysicsOverrides.cap(Map.of("tail", (float) Math.toRadians(4.0)), bones(), segments,
                limits, null, null);
        assertEquals(4.0F, Math.toDegrees(limits[0]), 1.0E-3F, "and a narrower one tightens it");
    }

    @Test
    void aCapNameThatIsNotOnTheModelCapsNothingAndIsReported() {
        YsmPhysicsParts.Segment[] segments = segments("Tail");
        float[] limits = new float[1];
        List<String> notBones = new ArrayList<>();
        List<String> notPieces = new ArrayList<>();

        int count = YsmPhysicsOverrides.cap(Map.of("tail9", (float) Math.toRadians(8.0)),
                bones("Tail", "Head"), segments, limits, notBones, notPieces);

        assertEquals(0, count);
        assertEquals(List.of("tail9"), notBones, "a name that is not on the model is reported");
        assertEquals(0.0F, limits[0], "and it must not cap the piece it was nearly spelled like");
    }

    /**
     * <b>The ceiling, through the production frame path.</b> A capped piece must swing less than
     * the chain would have allowed, must still swing, and must not move the piece beside it.
     *
     * <p>The last of the three is the whole reason this is a per-bone key rather than a rule: the
     * cap is applied where the chain's allowance is applied, so it cannot leak into anything that
     * is not under the capped piece.
     */
    @Test
    void aCappedPieceSwingsAtMostItsCeilingAndItsNeighbourIsUntouched() {
        YsmPhysicsParts.Segment[] segments = chain();
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.BONE_NAMES, 0);
        YsmMeshSecondaryMotion.State control = run(model, Set.of(), Map.of());
        YsmMeshSecondaryMotion.State capped = run(model, Set.of(),
                Map.of("tail", (float) Math.toRadians(5.0)));

        assertTrue(control.lastDegrees[0] > 5.0F,
                "the control must demand more than the ceiling, or this proves nothing: "
                        + control.lastDegrees[0] + " deg");
        assertTrue(capped.lastDegrees[0] <= 5.0001F,
                "the capped piece swung " + capped.lastDegrees[0] + " deg, over its 5 deg ceiling");
        assertTrue(capped.lastDegrees[0] > 0.5F,
                "a ceiling is not a freeze: the piece must still swing, and it swung "
                        + capped.lastDegrees[0] + " deg");
        assertEquals(5.0F, Math.toDegrees(capped.chainBudget[0]), 1.0E-3F,
                "and what the chain granted it is the ceiling, which is what the log prints");
        assertTrue(capped.integrated[0], "a capped piece is still simulated");

        assertTrue(same(control.deltas[2], capped.deltas[2]),
                "a piece under no cap must have exactly the delta it had");
        assertEquals(control.lastDegrees[2], capped.lastDegrees[2], "and the same own swing");
    }

    @Test
    void aCeilingAboveTheChainAllowanceChangesNothing() {
        YsmPhysicsParts.Segment[] segments = chain();
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.BONE_NAMES, 0);
        YsmMeshSecondaryMotion.State control = run(model, Set.of(), Map.of());
        YsmMeshSecondaryMotion.State loose = run(model, Set.of(),
                Map.of("tail", (float) Math.toRadians(89.0)));

        for (int i = 0; i < segments.length; i++) {
            assertTrue(same(control.deltas[i], loose.deltas[i]),
                    "segment " + i + " must be unchanged by a ceiling the chain already grants");
        }
    }

    /**
     * And with no override at all the ceilings array is left exactly as it was - all zeroes, which
     * is the value the frame path reads as "no ceiling" and the reason the no-file case is the
     * shipped behaviour rather than a re-derivation of it.
     */
    @Test
    void withNoOverrideFileNoPieceIsCapped() {
        YsmPhysicsParts.Segment[] segments = chain();
        float[] limits = new float[segments.length];

        int capped = YsmPhysicsOverrides.markOverrides("ysmef/test/no-such-model-file",
                bones("Tail", "Tail2", "Bangs"), segments, null, limits);

        assertEquals(0, capped, "with no file for that id, nothing is capped");
        for (float limit : limits) {
            assertEquals(0.0F, limit, 0.0F, "and every ceiling is exactly zero");
        }
        assertTrue(YsmPhysicsOverrides.limits("ysmef/test/no-such-model-file").isEmpty(),
                "and the loader resolved no ceilings at all");
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private void write(String name, String body) throws IOException {
        Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8);
    }

    /**
     * A three-piece model: a root, its child, and a second root beside it.
     *
     * <p>The second root's pivot is 0.5 blocks away from the first's, deliberately: two pieces whose
     * pivots are within {@code YsmPhysicsChains#KNIT_RADIUS} (0.18) and whose rest directions agree
     * are <b>sewn</b>, and a sewn neighbour is pulled by the coupling rather than by the solver - so
     * one sewn to the held piece legitimately moves by a fraction of a millimetre (measured at
     * 0.000593 blocks on the real model; see {@code HeadRegionPitchProbeTest}). The piece this test
     * calls untouched is one that is not sewn to the held piece, which is what "the blast radius"
     * means: the swing must not leak through the simulation.
     */
    private static YsmPhysicsParts.Segment[] chain() {
        return new YsmPhysicsParts.Segment[]{
                segment("Tail", 7, 0.4F, new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f(1.0F, 0.0F, 0.0F),
                        -1, new int[]{0, 1}),
                segment("Tail2", 7, 0.3F, new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f(0.0F, -1.0F, 0.0F),
                        0, new int[]{2, 3}),
                segment("Bangs", 9, 0.3F, new Vector3f(0.5F, 1.0F, 0.0F), new Vector3f(1.0F, 0.0F, 0.0F),
                        -1, new int[]{4, 5})};
    }

    /** One piece with a given name, hanging off its own pivot. */
    private static YsmPhysicsParts.Segment segment(String name, int joint, float lever, Vector3f pivot,
                                                   Vector3f rest, int parent, int[] parts) {
        Vector3f bindRest = new Vector3f(rest).mul(lever);
        return new YsmPhysicsParts.Segment(0, name, joint, pivot, bindRest, lever, 0.03F, 1.0F,
                0.8F, 0.5F, 1.047F, parent, parts, false, new int[0]);
    }

    private static YsmPhysicsParts.Segment[] segments(String... names) {
        YsmPhysicsParts.Segment[] out = new YsmPhysicsParts.Segment[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = segment(names[i], 7, 0.3F, new Vector3f(0.0F, 1.0F, 0.0F),
                    new Vector3f(1.0F, 0.0F, 0.0F), -1, new int[]{i});
        }
        return out;
    }

    private static YSMRuntimeModel.BoneRt[] bones(String... names) {
        YSMRuntimeModel.BoneRt[] out = new YSMRuntimeModel.BoneRt[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = new YSMRuntimeModel.BoneRt();
            out[i].name = names[i];
        }
        return out;
    }

    /**
     * The shipped loop, one model, sixty-one frames, with the named pieces held.
     *
     * <p>The flags are set by {@link YsmPhysicsOverrides#hold} - the same decision the file goes
     * through - so the names here are the ones a file would carry (lower-cased) and the matching is
     * the shipped matching rather than the test's.
     */
    private static YsmMeshSecondaryMotion.State run(YsmPhysicsParts.Model model, Set<String> held) {
        return run(model, held, Map.of());
    }

    /**
     * The same, with a ceiling per bone name in radians, set through
     * {@link YsmPhysicsOverrides#cap} - the same decision the file's numbers go through.
     */
    private static YsmMeshSecondaryMotion.State run(YsmPhysicsParts.Model model, Set<String> held,
                                                    Map<String, Float> limits) {
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(model, null, 1.047F);
        YsmPhysicsOverrides.hold(held, null, model.segments(), state.held, null, null);
        YsmPhysicsOverrides.cap(limits, null, model.segments(), state.limit, null, null);
        YsmMeshSecondaryMotion.PoseSource pose = new LeaningPose(1.047F);
        for (int frame = 0; frame <= 60; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, 1.0F / 60.0F, null, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }
        return state;
    }

    private static final float[] NO_TURN = {0.0F, 0.0F};

    /**
     * A pose that has actually turned every joint, sixty degrees about the model's left-right axis.
     *
     * <p>An identity pose would make this test vacuous in the way round 20 documented: a joint the
     * animation leaves as the rig authors it is a joint the animation is holding still, so the
     * spring's target is the rest direction and nothing swings - the control would be the identity
     * and "holding a piece changes nothing" would be true of every piece for the wrong reason.
     */
    private static final class LeaningPose implements YsmMeshSecondaryMotion.PoseSource {
        private final OpenMatrix4f rotation;

        LeaningPose(float radians) {
            rotation = new OpenMatrix4f();
            float c = (float) Math.cos(radians);
            float s = (float) Math.sin(radians);
            // A rotation about x, in Epic Fight's column-addressed fields: m<column><row>.
            rotation.m11 = c;
            rotation.m12 = s;
            rotation.m21 = -s;
            rotation.m22 = c;
        }

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return new OpenMatrix4f();
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            return rotation;
        }
    }

    /** Entry-for-entry equality: "unchanged" is not "close". */
    private static boolean same(OpenMatrix4f a, OpenMatrix4f b) {
        return a.m00 == b.m00 && a.m01 == b.m01 && a.m02 == b.m02 && a.m03 == b.m03
                && a.m10 == b.m10 && a.m11 == b.m11 && a.m12 == b.m12 && a.m13 == b.m13
                && a.m20 == b.m20 && a.m21 == b.m21 && a.m22 == b.m22 && a.m23 == b.m23
                && a.m30 == b.m30 && a.m31 == b.m31 && a.m32 == b.m32 && a.m33 == b.m33;
    }

    // ------------------------------------------------------------------
    // Log capture: the "one line per model" rules are part of the interface
    // ------------------------------------------------------------------

    /** Run a task with the mod's own log lines collected, so the lines themselves can be asserted. */
    private static List<String> captureLog(Runnable task) {
        List<String> lines = new ArrayList<>();
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger("YSM-EF Compat");
        AbstractAppender appender = new AbstractAppender("ysm-test-capture", null, null, false,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            task.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        return lines;
    }

    private static int count(List<String> lines, Level level) {
        int total = 0;
        for (String line : lines) {
            if (line.startsWith(level.name() + " ")) {
                total++;
            }
        }
        return total;
    }

    /** The log facade the class under test uses, so a rename here cannot silently detach the test. */
    @Test
    void theLogCaptureIsAttachedToTheSameLoggerTheModUses() {
        assertNotNull(YSMEpicFightCompat.LOGGER, "the mod's logger is what these tests read");
        List<String> lines = captureLog(() -> YSMEpicFightCompat.LOGGER.warn("capture-probe"));
        assertEquals(1, lines.size(), "the capture must see the mod's own lines: " + lines);
        assertTrue(lines.get(0).contains("capture-probe"));
    }
}
