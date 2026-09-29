package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one transform on the reported defect's path that no round had measured: the per-part delta.
 *
 * <p>Epic Fight draws a part as {@code pose[joint] x toOrigin[joint] x partTransform}, and this mod
 * supplies {@code partTransform} per part ordinal ({@code YSMMesh#getPartTransform}, written by
 * {@code YSMPlayerAnimator#pushToMesh} and by the secondary-motion writers). The joint half of that
 * product was measured in earlier rounds; the delta half was not. This file measures it, on the
 * <b>deployed</b> build's own artefacts and against the <b>deployed</b> build's own log, for
 * {@code EKU(1.0.ysm} - the model in the report ("thigh and shin come away from the torso, the thigh
 * drawn lying horizontally").
 *
 * <h2>What makes these numbers admissible</h2>
 *
 * <p>A reproduction of production arithmetic is worth nothing until it can be shown to fail, so
 * every number below is anchored to a quantity the running game printed and this file recomputes:
 *
 * <ol>
 *   <li><b>Provenance.</b> The converted mesh and runtime are the pair the manifest names, the
 *       manifest's {@code generator} is the writer fingerprint packaged inside the deployed jar, and
 *       both artefact hashes match the manifest. The log line is read from the instance the
 *       artefacts came from. So the two sides of every comparison describe one build.</li>
 *   <li><b>The lever column.</b> The deployed build's {@code [physics] segments} line prints
 *       {@code L=} - {@code |centroid - bindPivot|} of each simulated bone's own geometry, measured
 *       from the live mesh. This file rebuilds that quantity from the stored artefacts (the runtime
 *       bone table's {@code T(p) Rz Ry Rx T(-p)} chain, {@code YsmPhysicsParts#pivotInMeshSpace},
 *       and the mesh JSON's own part vertices) and requires agreement within five millimetres on
 *       <b>all 76</b> segments. A wrong frame, a wrong Euler order, a wrong parent link, a wrong
 *       vertex set or a wrong scale all move it, and by far more than five millimetres.</li>
 *   <li><b>The joint rotation.</b> {@code rest = rotation(pose x toOrigin) x bindRest}, so the log's
 *       {@code rest=} column pins the rotation of the skinning matrix for every joint that carries at
 *       least two simulated pieces with non-parallel geometry. Fitting one rotation per joint and
 *       requiring the residual over <i>every</i> piece of that joint to stay under half a degree is
 *       what turns the log's {@code rest} column into the drawn orientation of the leg geometry, and
 *       it is the check that the leg pieces' numbers below are about the geometry the user sees.</li>
 * </ol>
 *
 * <h2>What it reports, and why the report is the point</h2>
 *
 * <p>The delta path's verdict is a number, not an opinion: a segment's applied rotation is the
 * solver's swing clamped to the piece's own allowance, and its displacement is
 * {@code 2 L sin(own/2)} - so the delta can rotate a part by at most {@code allowed} and move its
 * centre of mass by at most {@code 2 L}. On this model the leg-region pieces are the extreme of both:
 * the largest {@code moved} in the whole model belongs to a piece of the reported thigh, and its
 * pivot is not on that piece at all - it sits 0.388 blocks below the piece's own geometry, at the
 * ankle, which is also why its {@code rest} direction points <i>up</i> where every correctly hinged
 * piece's points down. That is the same defect class as the round-19 {@code wrapsPivot} rule (a piece
 * rotated about a pivot it does not hang from), and {@code wrapsPivot} does not fire here because
 * leg clothing does not wrap its pivot - it merely hangs a whole limb away from it.
 *
 * <p>Writes {@code build/reports/ysm-eku-part-delta.txt}. Opt-in: skipped unless the YSM config root
 * is given ({@code -Dysmef.golden.ysm_config_root} / {@code YSMEF_YSM_CONFIG_ROOT}).
 */
class EkuPartDeltaPathTest {

    private static final String MODEL = "EKU(1.0.ysm";
    private static final String MESH_STEM = "eku_1.0.ysm_9a5b9a1f";

    /** The known-good model: a maid whose skirt is simulated by the same code path. */
    private static final String CALIBRATION = "wine_fox/01_taisho_maid";

    /** Five millimetres: the log rounds every component to three decimals, and a frame error is 1.5. */
    private static final float LEVER_TOLERANCE = 0.005F;

    /**
     * One degree on a preserved angle between two pieces of one joint. The log prints two decimals
     * per component, so each direction carries up to about 0.6 degrees of rounding and a difference
     * of two angles twice that; the shipped model measures 0.712 degrees over 1731 pairs.
     */
    private static final double RIGID_TOLERANCE_DEG = 1.0;

    /** The pieces the report is about: the thigh and shin the user sees, plus the skirts. */
    private static final String[] LEG_PIECES = {
            "RightLegclothes1", "RightLegclothes2", "LeftLegclothes1", "LeftLegclothes2",
            "LeftLowerclothes1", "LeftLowerclothes2", "RightLowerclothes2",
            "Rightshoe1", "Rightshoe2", "Leftshoe1", "Leftshoe2"};

    @Test
    void theArtefactsAndTheLogComeFromTheSameDeployedBuild() throws IOException {
        Path instance = instanceRoot();
        assumeTrue(instance != null, "set -D" + com.ysmef.compat.ysm.YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or the environment variable) to run this probe");

        JsonObject manifest = readJson(instance.resolve("config").resolve("ysm_epicfight_compat")
                .resolve("manifest.json")).getAsJsonObject();
        String generator = manifest.get("generator").getAsString();

        Path jar = deployedJar(instance);
        assumeTrue(jar != null, "no deployed YSM_EpicFight_Compat jar beside the instance");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("ysm_epicfight_compat/writer_fingerprint.txt");
            assertTrue(entry != null, "the deployed jar carries no writer fingerprint");
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String token = null;
            for (String line : text.split("\\R")) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    token = line.trim();
                }
            }
            assertTrue(token != null && generator.endsWith(token),
                    "the manifest's generator (" + generator + ") is not the writer fingerprint inside "
                            + "the deployed jar (" + token + "): the converted artefacts on disk were not "
                            + "written by the build the log came from, so nothing here would be a "
                            + "measurement of the deployed defect");
        }

        // The manifest only carries the models this install has actually converted, and which those are
        // is the user's business rather than this probe's: a session that never converted EKU leaves no
        // entry here, and that is "this probe has nothing to measure", not "the build is inconsistent".
        // Skipped rather than failed, so the rest of the file - and the suite - stays meaningful
        // whichever models the last session happened to use.
        JsonObject model = manifest.getAsJsonObject("models").getAsJsonObject(MODEL);
        assumeTrue(model != null, "this install's manifest has no entry for " + MODEL
                + " (it converted: " + manifest.getAsJsonObject("models").keySet() + "), so the artefacts "
                + "on disk are not pinned by any build this probe can compare against");
        Path pack = instance.resolve("config").resolve("ysm_epicfight_compat").resolve("resourcepack")
                .resolve("assets").resolve("ysm_epicfight_compat");
        assertEquals(model.get("mhash").getAsString(),
                sha256(pack.resolve("animmodels").resolve("entity").resolve(MESH_STEM + ".json")),
                "the mesh on disk is not the one the manifest pins");
        assertEquals(model.get("rhash").getAsString(),
                sha256(pack.resolve("ysm_runtime").resolve("entity").resolve(MESH_STEM + ".json")),
                "the runtime on disk is not the one the manifest pins");
    }

    /**
     * The calibration: every lever the deployed build printed is reproduced from the stored artefacts.
     *
     * <p>This is the measurement that licenses every other number in this file. {@code L} is
     * {@code |centroid - bindPivot|} of one bone's own mesh part, so it depends on the parent chain,
     * the Euler order, the scale, the mesh frame and the vertex set at once, and it is a
     * pose-independent function of the converted files alone.
     */
    @Test
    void theLeverOfEverySimulatedBoneIsReproducedFromTheStoredArtefacts() throws IOException {
        Path instance = instanceRoot();
        assumeTrue(instance != null, "set the YSM config root to run this probe");
        Model model = read(MODEL);
        List<Logged> logged = loggedSegments(instance);

        assertEquals(76, logged.size(),
                "the deployed log's segment line for " + MODEL + " no longer lists 76 segments; the "
                        + "numbers this file compares against are for that line");
        List<String> report = new ArrayList<>();
        report.add("# " + MODEL + ": the per-part delta path, measured on the deployed build");
        report.add("");
        report.add("| bone | joint | lever (reproduced) | lever (log) | delta | rest (log) | own | moved |");
        report.add("|---|---|---|---|---|---|---|---|");
        double worst = 0.0;
        String worstBone = "-";
        for (Logged row : logged) {
            Bone bone = model.bone(row.name);
            assertTrue(bone != null, "the log names a bone the runtime table does not have: " + row.name);
            float lever = bone.lever;
            double error = Math.abs(lever - row.lever);
            if (error > worst) {
                worst = error;
                worstBone = row.name;
            }
            Vector3f rest = row.rest;
            report.add(String.format(Locale.ROOT,
                    "| %s | %d | %.3f | %.3f | %s | (%.2f,%.2f,%.2f) | %.1f | %.3f |",
                    row.name, bone.joint, lever, row.lever, bone.part == null ? "no part" : "yes",
                    rest.x, rest.y, rest.z, row.own, row.moved));
        }
        writeReport("ysm-eku-part-delta.txt", report);
        assertTrue(worst <= LEVER_TOLERANCE, String.format(Locale.ROOT,
                "the reproduced lever of '%s' is %.4f blocks from the one the deployed build printed "
                        + "(tolerance %.3f). The reproduction of the geometry path is broken, so no "
                        + "delta number below it can be believed.", worstBone, worst, LEVER_TOLERANCE));

        // The same calibration for the direction, and without fitting anything: a rigid rotation
        // preserves every angle, so two pieces carried by one joint must show the same angle between
        // their rest directions as between their reproduced bind directions. 1731 pairs, and the
        // bound is the log's own rounding (every component is printed to two decimals).
        double worstAngle = 0.0;
        String worstPair = "-";
        for (Map.Entry<String, List<String>> joint : jointMembership(logged).entrySet()) {
            List<String> names = joint.getValue();
            for (int a = 0; a < names.size(); a++) {
                for (int b = a + 1; b < names.size(); b++) {
                    Bone first = model.bone(names.get(a));
                    Bone second = model.bone(names.get(b));
                    Logged firstRow = rowOf(logged, names.get(a));
                    Logged secondRow = rowOf(logged, names.get(b));
                    double bindAngle = angleBetween(first.rest, second.rest);
                    double drawnAngle = angleBetween(firstRow.rest, secondRow.rest);
                    double mismatch = Math.abs(bindAngle - drawnAngle);
                    if (mismatch > worstAngle) {
                        worstAngle = mismatch;
                        worstPair = names.get(a) + "/" + names.get(b);
                    }
                }
            }
        }
        assertTrue(worstAngle <= RIGID_TOLERANCE_DEG, String.format(Locale.ROOT,
                "the pieces of one joint do not share a rigid rotation: the angle between '%s' differs "
                        + "by %.3f deg between the geometry and the log (tolerance %.1f). The log's rest "
                        + "column is then not the skinning rotation applied to this geometry, and the "
                        + "drawn orientation this file reports would be about something else.",
                worstPair, worstAngle, RIGID_TOLERANCE_DEG));
    }

    /**
     * The owner of every delta: the part named for the bone, and only that part.
     *
     * <p>The sub-hypothesis this rules out is "the deltas are attached to the wrong parts", which is
     * how a leg-skin part could be handed a hair's rotation. Both writers resolve through the part's
     * own <b>name</b> - the animator with {@code setRuntimeTransform(partName, ...)} on the part it
     * just read from the mesh, the secondary motion through {@code partOrdinalsByBone}, which collects
     * the ordinal of every part whose name is {@code y/<bone>} - so the owner is the name, not a
     * position in a list. What is asserted here is the data side of that: every bone the deployed log
     * simulates owns exactly one part, that part is named for it, and its vertices are skinned to the
     * joint the runtime table gives the same bone. A part that a delta reaches while its vertices are
     * skinned elsewhere, or a bone whose geometry another bone carries, both fail here.
     */
    @Test
    void everySimulatedBoneOwnsThePartNamedForItAndItsVerticesBindToItsJoint() throws IOException {
        Path instance = instanceRoot();
        assumeTrue(instance != null, "set the YSM config root to run this probe");
        Model model = read(MODEL);
        List<Logged> logged = loggedSegments(instance);

        int withParts = 0;
        for (Logged row : logged) {
            Bone bone = model.bone(row.name);
            assertTrue(bone != null && bone.part != null,
                    "the deployed build simulated '" + row.name + "' but the converted mesh has no part "
                            + "named '" + EFMeshJsonWriter.BONE_PART_PREFIX + row.name + "' - the delta "
                            + "would be written to no geometry at all");
            withParts++;
            assertFalse(bone.mapped, "'" + row.name + "' is a mapped body bone and must not be simulated");
            assertEquals(bone.joint, bone.vertexJoint,
                    "the vertices of part '" + EFMeshJsonWriter.BONE_PART_PREFIX + row.name + "' are "
                            + "skinned to joint " + bone.vertexJoint + " while the runtime table binds the "
                            + "bone to joint " + bone.joint + ": the part is drawn by one joint and moved "
                            + "by another");
        }
        assertEquals(76, withParts, "every logged segment must own its part");

        // The other side of the same count, from the other half of the log: the running build reported
        // its own part total (the [iris-diag] line prints mesh.getPartCount()). If this probe's mesh
        // had a different part set, the delta ordinals below it would be about a different mesh.
        int reportedParts = reportedPartCount(instance);
        assertEquals(reportedParts, model.parts.size(), String.format(Locale.ROOT,
                "the deployed build drew %d parts and the stored mesh has %d: the part space this probe "
                        + "measures is not the one the log's deltas were written in",
                reportedParts, model.parts.size()));
        int named = 0;
        for (String partName : model.parts.keySet()) {
            if (partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                named++;
            }
        }
        assertEquals(223, named, "the converted mesh must have one y/<bone> part per bone with geometry");
    }

    /**
     * The leg region, as the deployed build drew it: pivot, lever, rest, swing and displacement.
     *
     * <p>The decisive quantities are the last three columns. A segment's applied rotation is its own
     * swing clamped to its allowance, and its displacement is {@code 2 L sin(own/2)}; both come from
     * the deployed log, so the geometry question - can the delta itself lay a thigh horizontally - is
     * answered by the log's own numbers rather than by a re-simulation. The pivot column is what makes
     * them severe: a piece whose pivot sits at the <b>ankle</b> while its geometry is the thigh swings
     * on a lever of 0.622 blocks instead of the 0.24 its own size would give, and its rest direction
     * points up where every correctly hinged piece's points down.
     */
    @Test
    void theLegPiecesSwingOnPivotsThatAreNotOnThem() throws IOException {
        Path instance = instanceRoot();
        assumeTrue(instance != null, "set the YSM config root to run this probe");
        Model model = read(MODEL);
        List<Logged> logged = loggedSegments(instance);
        Map<String, Logged> byName = new HashMap<>();
        for (Logged row : logged) {
            byName.put(row.name, row);
        }

        StringBuilder report = new StringBuilder();
        report.append("# The leg region of ").append(MODEL).append(" on the deployed build\n\n");
        report.append("| piece | joint | pivot | geometry Y | lever | gap(pivot, own geometry) | "
                + "rest angle from down | own | allowed | moved | tier |\n");
        report.append("|---|---|---|---|---|---|---|---|---|---|---|\n");

        Map<String, Double> gaps = new LinkedHashMap<>();
        for (String name : LEG_PIECES) {
            Bone bone = model.bone(name);
            assertTrue(bone != null && bone.part != null, name + " has no own geometry in this model");
            Logged row = byName.get(name);
            double gap = bone.pivotGapFromOwnGeometry();
            gaps.put(name, gap);
            double restFromDown = row == null ? Double.NaN : angleFromDown(row.rest);
            report.append(String.format(Locale.ROOT,
                    "| %s | %d | (%.3f,%.3f,%.3f) | %.3f..%.3f | %.3f | %.3f | %s | %s | %s | %s | %s |%n",
                    name, bone.joint, bone.pivot.x, bone.pivot.y, bone.pivot.z,
                    bone.min.y, bone.max.y, bone.lever, gap,
                    row == null ? "-" : String.format(Locale.ROOT, "%.1f deg", restFromDown),
                    row == null ? "not simulated" : String.format(Locale.ROOT, "%.1f", row.own),
                    row == null ? "-" : String.format(Locale.ROOT, "%.1f", row.allowed),
                    row == null ? "-" : String.format(Locale.ROOT, "%.3f", row.moved),
                    row == null ? "-" : row.tier));
        }

        // The delta's rotation is the log's own swing, and the clamp holds: nothing on this path can
        // turn a part further than its allowance.
        for (Logged row : logged) {
            assertTrue(row.own <= row.allowed + 1.0E-3, String.format(Locale.ROOT,
                    "'%s' was swung %.1f deg against an allowance of %.1f: the clamp does not hold",
                    row.name, row.own, row.allowed));
            if (row.lever > 0.0F) {
                double expected = 2.0 * row.lever * Math.sin(Math.toRadians(row.own) * 0.5);
                assertEquals(expected, row.moved, 0.002, String.format(Locale.ROOT,
                        "'%s' reports moved=%.3f blocks, which is not 2*L*sin(own/2)=%.3f for its own "
                                + "lever %.3f and swing %.1f deg", row.name, row.moved, expected,
                        row.lever, row.own));
            }
        }

        // The two thigh bones of one leg hang from opposite ends of the same limb, so the solver
        // swings the two halves of that thigh in opposite rotational senses.
        Logged upperRight = byName.get("RightLegclothes1");
        Logged lowerRight = byName.get("RightLegclothes2");
        assertTrue(upperRight != null && lowerRight != null, "both halves of the right thigh must be simulated");
        assertTrue(gaps.get("RightLegclothes2") > 0.30, String.format(Locale.ROOT,
                "RightLegclothes2's pivot is %.3f blocks from its own geometry; the report is about a "
                        + "piece rotated about a point a whole limb away, so this probe is measuring "
                        + "something else", gaps.get("RightLegclothes2")));
        assertTrue(angleFromDown(lowerRight.rest) > 90.0 && angleFromDown(upperRight.rest) < 90.0,
                "the two halves of the right thigh must have opposite rest directions - that is what "
                        + "makes the single solver swing them apart");

        // The largest displacement in the whole model belongs to this piece.
        String biggest = logged.get(0).name;
        double biggestMoved = -1.0;
        for (Logged row : logged) {
            if (row.moved > biggestMoved) {
                biggestMoved = row.moved;
                biggest = row.name;
            }
        }
        // On the build that wrote this log the largest simulated displacement in the model belonged to
        // 'RightLegclothes2'. The current tree DROPS that piece - its pivot sits 0.388 blocks off its own
        // geometry, which is the defect this probe was written for - so the largest remaining mover is
        // one of its leg-clothing siblings ('LeftLowerclothes1' at 0.199 blocks today). Both states are
        // expected; anything else means the probe is reading a different model or build than it
        // calibrated against.
        assertTrue(biggest.equals("RightLegclothes2")
                        || biggest.startsWith("LeftLowerclothes") || biggest.startsWith("RightLowerclothes")
                        || biggest.startsWith("LeftLegclothes") || biggest.startsWith("RightLegclothes"),
                String.format(Locale.ROOT, "the model's largest simulated displacement is '%s' at %.3f "
                        + "blocks, which is neither the reported thigh piece nor one of its leg-clothing "
                        + "siblings", biggest, biggestMoved));

        // The classification itself, against the log: the production selector, run on the stored
        // artefacts, must choose exactly the bones the deployed build simulated. This is the check
        // that makes the calibration below a comparison of like with like - the same rule, both models.
        List<String> selected = simulated(model);
        List<String> loggedNames = new ArrayList<>();
        for (Logged row : logged) {
            loggedNames.add(row.name);
        }
        java.util.Collections.sort(selected);
        java.util.Collections.sort(loggedNames);
        List<String> onlySelected = new ArrayList<>(selected);
        onlySelected.removeAll(loggedNames);
        List<String> onlyLogged = new ArrayList<>(loggedNames);
        onlyLogged.removeAll(selected);
        assertTrue(onlyLogged.isEmpty(),
                "the deployed build simulated bones the production classifier does not select: "
                        + onlyLogged + ". The stored artefacts are then not the mesh those deltas were "
                        + "written against.");
        assertTrue(selected.size() >= loggedNames.size(), "the selection cannot be smaller than the log");

        // The classifier selects more than the build simulated, and the difference has to be the
        // later filters rather than anything else: a bone that only `selectBones` accepts is dropped
        // again by `buildSegment` when it has no lever or when its geometry closes around its pivot
        // (`wrapsPivot`), which is the round-19 rule for a piece that sits about its pivot instead of
        // hanging from it. Naming those two rules here is what makes the selection calibration exact.
        for (String extra : onlySelected) {
            Bone bone = model.bone(extra);
            assertTrue(bone != null, "a selected bone must have geometry: " + extra);
            // The two rules, with their production constants: MIN_LEVER is 0.01 blocks (a bone
            // sitting on its own pivot cannot swing), and MIN_VERTICES is 4.
            boolean noLever = bone.lever < 0.01F || bone.part.length < 4;
            boolean wraps = YsmPhysicsParts.wrapsPivot(bone.vertices, bone.pivot);
            assertTrue(noLever || wraps || bone.mapped || bone.joint < 0, String.format(Locale.ROOT,
                    "'%s' was selected by the classifier but not simulated by the deployed build, and "
                            + "neither rule that drops a selected bone explains it (lever %.4f, wraps "
                            + "%s, mapped %s, joint %d)",
                    extra, bone.lever, wraps, bone.mapped, bone.joint));
        }

        // Calibration: the same statistic over the pieces the same classifier simulates, on a
        // known-good model. A pivot a limb away from its own geometry must not be how this mod treats
        // every model - and `wine_fox/01_taisho_maid` is a model whose skirt this path simulates.
        double calibrationWorst = 0.0;
        String calibrationWorstName = "-";
        int calibrationPieces = 0;
        try {
            Model maid = read(CALIBRATION);
            for (String name : simulated(maid)) {
                Bone bone = maid.bone(name);
                if (bone == null) {
                    continue;
                }
                calibrationPieces++;
                double gap = bone.pivotGapFromOwnGeometry();
                if (gap > calibrationWorst) {
                    calibrationWorst = gap;
                    calibrationWorstName = bone.name;
                }
            }
            report.append(String.format(Locale.ROOT,
                    "%nCalibration (`%s`, %d pieces selected by the same classifier): worst "
                            + "pivot-to-own-geometry gap %.3f blocks (`%s`), against %.3f on "
                            + "`RightLegclothes2`.%n",
                    CALIBRATION, calibrationPieces, calibrationWorst, calibrationWorstName,
                    gaps.get("RightLegclothes2")));
            // The blast radius of the rule the numbers point at: how many of each model's selected
            // pieces have their own geometry above their pivot (a pivot the piece does not hang from).
            int maidUpward = 0;
            for (String name : simulated(maid)) {
                Bone bone = maid.bone(name);
                if (bone != null && bone.rest.y > 0.0F) {
                    maidUpward++;
                }
            }
            int ekuUpward = 0;
            for (String name : selected) {
                Bone bone = model.bone(name);
                if (bone != null && bone.rest.y > 0.0F) {
                    ekuUpward++;
                }
            }
            report.append(String.format(Locale.ROOT,
                    "Pieces the classifier selects whose own geometry sits above their pivot (the "
                            + "vertical twin of `wrapsPivot`): %d of %d on `%s`, %d of %d on `%s`. "
                            + "On `%s` those are %s.%n",
                    ekuUpward, selected.size(), MODEL, maidUpward, calibrationPieces, CALIBRATION,
                    MODEL, upwardNames(model, selected)));
            assertTrue(calibrationPieces > 0, "the calibration model selected no pieces to compare");
            assertTrue(gaps.get("RightLegclothes2") > 5.0 * calibrationWorst,
                    String.format(Locale.ROOT, "the leg defect's pivot gap (%.3f blocks) is not an "
                                    + "outlier against the known-good model (%.3f blocks, '%s'): if every "
                                    + "model's simulated pieces sit this far from their pivots, the rule "
                                    + "this probe points at is not a rule",
                            gaps.get("RightLegclothes2"), calibrationWorst, calibrationWorstName));
        } catch (IOException | RuntimeException e) {
            report.append("\nCalibration model `").append(CALIBRATION)
                    .append("` is not converted in this instance; the outlier claim is uncalibrated.\n");
        }
        writeReport("ysm-eku-part-delta.txt", List.of(report.toString().split("\\R")));
    }

    /**
     * The blast radius of the vertical twin rule, on the two models that matter: the reported one and
     * the known-good one.
     *
     * <p>{@link YsmPhysicsParts#risesFromPivot} drops a piece whose own geometry sits above its pivot.
     * On the reported model that is the defect: {@code RightLegclothes2}'s pivot is at the ankle while
     * its geometry is the thigh, so the piece is swung about a point a whole limb away from it. The
     * question this probe answers is what else on each model the rule takes with it - because the rule
     * is calibrated on models the user has accepted, and a piece there that visibly ought to swing is
     * a reason to narrow it rather than to ship it.
     *
     * <p>Every fire is reported with the two numbers a reader needs to judge it: the direction of the
     * rest (how far above horizontal the geometry's centre of mass sits from the pivot) and whether
     * the pivot is inside the piece's own bounding box (a pivot on the piece can hinge; a pivot off it
     * cannot). The invariant that no down-pointing piece ever fires is asserted, so a flipped sign
     * cannot pass silently.
     */
    @Test
    void thePiecesWhoseGeometrySitsAboveTheirPivotOnBothModels() throws IOException {
        Path instance = instanceRoot();
        assumeTrue(instance != null, "set the YSM config root to run this probe");
        List<String> report = new ArrayList<>();
        report.add("# Pieces the vertical twin rule drops, on the reported model and the known-good one");
        report.add("");
        for (String modelId : new String[]{MODEL, CALIBRATION}) {
            Model model;
            try {
                model = read(modelId);
            } catch (IOException | RuntimeException | AssertionError e) {
                report.add("`" + modelId + "` is not converted in this instance: " + e.getMessage());
                report.add("");
                continue;
            }
            List<String> selected = simulated(model);
            List<String> fires = new ArrayList<>();
            List<String> directionOnly = new ArrayList<>();
            int downward = 0;
            int pivotOnPiece = 0;
            report.add("## `" + modelId + "`: " + selected.size() + " selected piece(s)");
            report.add("");
            report.add("| piece | joint | category | lever | rest from down | upShare | gap(pivot, own geom) | "
                    + "pivot inside own box | wraps pivot | direction test | dropped (shipped rule) |");
            report.add("|---|---|---|---|---|---|---|---|---|---|---|");
            for (String name : selected) {
                Bone bone = model.bone(name);
                if (bone == null) {
                    continue;
                }
                float lever = bone.lever;
                double upShare = lever <= 0.0F ? Double.NaN : bone.rest.y / lever;
                boolean pointsUp = YsmPhysicsParts.risesFromPivot(bone.rest, lever);
                boolean dropped = YsmPhysicsParts.risesOffPivot(bone.vertices, bone.pivot, bone.rest, lever);
                if (bone.rest.y <= 0.0F) {
                    downward++;
                    assertFalse(dropped, "'" + name + "' points down and must not be dropped by the rule");
                }
                if (pointsUp) {
                    directionOnly.add(name);
                }
                if (dropped) {
                    fires.add(name);
                    if (bone.pivotGapFromOwnGeometry() <= 0.0) {
                        pivotOnPiece++;
                    }
                }
                report.add(String.format(Locale.ROOT,
                        "| %s | %d | %s | %.3f | %.1f deg | %+.3f | %.3f | %s | %s | %s | %s |",
                        name, bone.joint, YsmPhysicsParts.categoryOf(name), lever,
                        angleFromDown(bone.rest), upShare, bone.pivotGapFromOwnGeometry(),
                        bone.pivotGapFromOwnGeometry() <= 0.0 ? "yes" : "no",
                        YsmPhysicsParts.wrapsPivot(bone.vertices, bone.pivot) ? "yes" : "no",
                        pointsUp ? "fires" : "-",
                        dropped ? "**DROPPED**" : "-"));
            }
            report.add("");
            report.add(String.format(Locale.ROOT,
                    "`%s`: the direction test alone drops %d of %d selected piece(s) (%s); the shipped rule "
                            + "(direction AND the pivot off the piece) drops **%d of %d**, of which %d have "
                            + "their pivot inside their own geometry, and %d piece(s) point down and none of "
                            + "them fires. Dropped: %s",
                    modelId, directionOnly.size(), selected.size(), directionOnly,
                    fires.size(), selected.size(), pivotOnPiece, downward, fires));
            report.add("");
            // What the shipped rule must do on each model. On the reported one it must take the pieces
            // the defect is made of - the thigh and shin drawn by pivots at the other end of the limb -
            // and leave everything that is merely drawn upward from a pivot on itself alone. On the
            // accepted one it must take nothing at all: a model the user is happy with is not the place
            // to discover a rule's blast radius.
            if (MODEL.equals(modelId)) {
                for (String piece : new String[]{"RightLegclothes2", "LeftLowerclothes1",
                        "LeftLowerclothes2", "RightLowerclothes2"}) {
                    Bone bone = model.bone(piece);
                    assertTrue(bone != null, "'" + piece + "' must exist on the reported model");
                    assertTrue(YsmPhysicsParts.risesOffPivot(bone.vertices, bone.pivot, bone.rest, bone.lever),
                            "'" + piece + "' is drawn by a pivot off its own geometry, and the rule must drop "
                                    + "it: that is the reported defect (pivot gap "
                                    + bone.pivotGapFromOwnGeometry() + " blocks)");
                }
                for (String piece : new String[]{"RightLegclothes1", "Left_Ear", "Right_Ear", "daimao",
                        "faqie", "hdj2"}) {
                    Bone bone = model.bone(piece);
                    assertTrue(bone != null, "'" + piece + "' must exist on the reported model");
                    assertFalse(YsmPhysicsParts.risesOffPivot(bone.vertices, bone.pivot, bone.rest, bone.lever),
                            "'" + piece + "' stands above a pivot that is on it (gap "
                                    + bone.pivotGapFromOwnGeometry() + " blocks), so it must keep its "
                                    + "simulation: the rule is about pivots that are not on the piece, not "
                                    + "about pieces drawn upward");
                }
            } else {
                assertTrue(fires.isEmpty(), "the shipped rule drops " + fires.size() + " piece(s) of the "
                        + "known-good model " + modelId + " (" + fires + "). The rule is calibrated against a "
                        + "model the user has accepted, and a tail tip or a hair piece that stops swinging "
                        + "there is not an acceptable price for the reported defect");
                assertFalse(directionOnly.isEmpty(), "the direction test alone must be doing something on "
                        + "this model, or this probe is not measuring the rule (it dropped nothing at all)");
            }
        }
        writeReport("ysm-hang-rule-instance.txt", report);
    }

    // ------------------------------------------------------------------
    // reading the deployed artefacts
    // ------------------------------------------------------------------

    /** One bone of the converted runtime table, with the geometry its own part draws. */
    private static final class Bone {
        final String name;
        final int joint;
        final boolean mapped;
        final int vertexJoint;
        final Vector3f pivot = new Vector3f();
        final Vector3f min = new Vector3f();
        final Vector3f max = new Vector3f();
        final Vector3f centroid = new Vector3f();
        final Vector3f rest = new Vector3f();
        final float lever;
        int[] part;
        List<Vector3f> vertices = List.of();

        Bone(String name, int joint, boolean mapped, int vertexJoint, Vector3f pivot,
             Vector3f min, Vector3f max, Vector3f centroid) {
            this.name = name;
            this.joint = joint;
            this.mapped = mapped;
            this.vertexJoint = vertexJoint;
            this.pivot.set(pivot);
            this.min.set(min);
            this.max.set(max);
            this.centroid.set(centroid);
            this.rest.set(centroid).sub(pivot);
            this.lever = this.rest.length();
        }

        /**
         * How far the bone's pivot sits outside its own geometry, blocks - 0 when the pivot is
         * inside the box.
         *
         * <p>The distance a piece has to be rotated about a point that is not on it. A piece that
         * hangs from its pivot has this at zero; {@code RightLegclothes2} has it at 0.388 blocks on a
         * piece 0.478 blocks long, i.e. its whole own length.
         */
        double pivotGapFromOwnGeometry() {
            double dx = Math.max(0.0, Math.max(min.x - pivot.x, pivot.x - max.x));
            double dy = Math.max(0.0, Math.max(min.y - pivot.y, pivot.y - max.y));
            double dz = Math.max(0.0, Math.max(min.z - pivot.z, pivot.z - max.z));
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private static final class Model {
        final Map<String, Bone> bones = new LinkedHashMap<>();
        final Map<String, int[]> parts = new LinkedHashMap<>();
        /** The runtime table in its own order, for the production classifier's index space. */
        final List<String> order = new ArrayList<>();
        final Map<String, Integer> indexOf = new HashMap<>();
        final Map<String, Vector3f[]> verticesByBone = new HashMap<>();
        JsonObject runtime;

        Bone bone(String name) {
            return bones.get(name);
        }
    }

    /**
     * The bones the production classifier selects for this model, from the converted artefacts alone.
     *
     * <p>Called rather than re-implemented: {@code selectBones} with {@code ownsItsGeometry} is the
     * rule that decides which pieces are simulated, and the calibration below is only a calibration if
     * both models are put through the same rule. The index space is the runtime table's own, which is
     * what the live {@code YSMRuntimeModel} compiles to (names in the table are unique in this model,
     * so the table order is the map order).
     */
    private static List<String> simulated(Model model) {
        JsonArray array = model.runtime.getAsJsonArray("bones");
        YSMRuntimeModel.BoneRt[] bones = new YSMRuntimeModel.BoneRt[model.order.size()];
        for (int i = 0; i < bones.length; i++) {
            JsonObject source = array.get(i).getAsJsonObject();
            YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
            bone.name = source.get("name").getAsString();
            bone.parent = source.has("parent") && !source.get("parent").getAsString().isEmpty()
                    ? model.indexOf.getOrDefault(source.get("parent").getAsString(), -1) : -1;
            bone.joint = source.get("joint").getAsInt();
            bone.mapped = source.has("mapped") && source.get("mapped").getAsBoolean();
            JsonArray pivot = source.getAsJsonArray("pivot");
            bone.px = pivot.get(0).getAsFloat();
            bone.py = pivot.get(1).getAsFloat();
            bone.pz = pivot.get(2).getAsFloat();
            JsonArray rot = source.getAsJsonArray("rot");
            bone.rx = rot.get(0).getAsFloat();
            bone.ry = rot.get(1).getAsFloat();
            bone.rz = rot.get(2).getAsFloat();
            bones[i] = bone;
        }
        Map<Integer, List<Vector3f>> vertices = new HashMap<>();
        Map<Integer, int[]> partOrdinals = new HashMap<>();
        for (int i = 0; i < bones.length; i++) {
            Vector3f[] own = model.verticesByBone.get(bones[i].name);
            if (own != null) {
                vertices.put(i, java.util.Arrays.asList(own));
                partOrdinals.put(i, new int[]{i});
            }
        }
        Map<Integer, float[]> geometry = YsmPhysicsParts.geometryByBone(vertices);
        IntPredicate owns = index -> YsmPhysicsParts.ownsItsGeometry(index, geometry, partOrdinals);
        List<Integer> selected = YsmPhysicsParts.selectBones(bones, owns, 4096, new int[1]);
        List<String> out = new ArrayList<>(selected.size());
        for (int index : selected) {
            out.add(bones[index].name);
        }
        return out;
    }

    /** The converted pair, read the way {@code YsmPhysicsParts} reads the live mesh. */
    private static Model read(String modelId) throws IOException {
        Path pack = packRoot();
        String stem = modelId.equals(MODEL) ? MESH_STEM
                : findStem(pack.resolve("ysm_runtime").resolve("entity"), modelId);
        assertTrue(stem != null, "no converted runtime for '" + modelId + "' in " + pack);
        JsonObject runtime = readJson(pack.resolve("ysm_runtime").resolve("entity").resolve(stem + ".json"));
        JsonObject mesh = readJson(pack.resolve("animmodels").resolve("entity").resolve(stem + ".json"));

        JsonArray bonesArr = runtime.getAsJsonArray("bones");
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < bonesArr.size(); i++) {
            index.put(bonesArr.get(i).getAsJsonObject().get("name").getAsString(), i);
        }
        float scaleX = scaleOf(runtime, 0);
        float scaleY = scaleOf(runtime, 1);
        Model model = new Model();
        model.runtime = runtime;
        for (int i = 0; i < bonesArr.size(); i++) {
            String name = bonesArr.get(i).getAsJsonObject().get("name").getAsString();
            model.order.add(name);
            model.indexOf.put(name, i);
        }

        // bindWorld, exactly as YSMRuntimeModel#computeBindWorld composes it: T(p) Rz Ry Rx T(-p) per
        // bone, multiplied down the parent chain.
        Map<String, Matrix4f> world = new HashMap<>();
        for (int i = 0; i < bonesArr.size(); i++) {
            bindWorld(bonesArr, index, world, i, 0);
        }

        // Positions in the frame the delta acts in. EFMeshJsonWriter stores (x, -z, y) and Epic
        // Fight's loader applies that map's inverse, so the pair cancels and the drawn frame is the
        // authored one scaled once - see YsmPhysicsParts#pivotInMeshSpace for the same argument.
        JsonObject vertices = mesh.getAsJsonObject("vertices");
        JsonArray positions = vertices.getAsJsonObject("positions").getAsJsonArray("array");
        JsonArray vindices = vertices.getAsJsonObject("vindices").getAsJsonArray("array");
        JsonArray vcounts = vertices.has("vcounts")
                ? vertices.getAsJsonObject("vcounts").getAsJsonArray("array") : null;
        int[] jointOfPosition = new int[positions.size() / 3];
        int cursor = 0;
        for (int p = 0; p < jointOfPosition.length; p++) {
            int count = vcounts == null ? 1 : vcounts.get(p).getAsInt();
            jointOfPosition[p] = count <= 0 ? -1 : vindices.get(cursor * 2).getAsInt();
            cursor += Math.max(1, count);
        }

        for (Map.Entry<String, JsonElement> entry : vertices.getAsJsonObject("parts").entrySet()) {
            JsonArray array = entry.getValue().getAsJsonObject().getAsJsonArray("array");
            int[] indices = new int[array.size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = array.get(i).getAsInt();
            }
            model.parts.put(entry.getKey(), indices);
        }

        String prefix = EFMeshJsonWriter.BONE_PART_PREFIX;
        for (int i = 0; i < bonesArr.size(); i++) {
            JsonObject bone = bonesArr.get(i).getAsJsonObject();
            String name = bone.get("name").getAsString();
            int[] part = model.parts.get(prefix + name);
            if (part == null || part.length == 0) {
                continue;
            }
            JsonArray pivotArray = bone.getAsJsonArray("pivot");
            Vector3f pivot = new Vector3f(pivotArray.get(0).getAsFloat(), pivotArray.get(1).getAsFloat(),
                    pivotArray.get(2).getAsFloat());
            world.get(name).transformPosition(pivot);
            pivot.mul(scaleX, scaleY, scaleX);

            // And the probe's own arithmetic is held against production's, per bone, so that this file
            // cannot quietly stop describing the code it is a measurement of: a change to
            // `pivotInMeshSpace` (the frame, the scale, the order) fails here by construction. The
            // lever assertion is then a statement about production and the deployed log at once.
            Vector3f shipped = YsmPhysicsParts.pivotInMeshSpace(new Matrix4f(world.get(name)),
                    pivotArray.get(0).getAsFloat(), pivotArray.get(1).getAsFloat(),
                    pivotArray.get(2).getAsFloat(), scaleX, scaleY);
            assertTrue(shipped != null && shipped.distance(pivot) < 1.0E-4F, String.format(Locale.ROOT,
                    "the pivot this probe measures for '%s' (%s) is not the one "
                            + "YsmPhysicsParts#pivotInMeshSpace computes (%s): the probe has drifted from "
                            + "production, so its lever calibration no longer says anything about the "
                            + "running code", name, pivot, shipped));

            Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            Vector3f centroid = new Vector3f();
            List<Vector3f> verticesOfPart = new ArrayList<>(part.length);
            for (int position : part) {
                float x = positions.get(position * 3).getAsFloat();
                float y = positions.get(position * 3 + 1).getAsFloat();
                float z = positions.get(position * 3 + 2).getAsFloat();
                // The drawn frame: the loader's inverse of the writer's map.
                float dx = x;
                float dy = z;
                float dz = -y;
                centroid.add(dx, dy, dz);
                verticesOfPart.add(new Vector3f(dx, dy, dz));
                min.set(Math.min(min.x, dx), Math.min(min.y, dy), Math.min(min.z, dz));
                max.set(Math.max(max.x, dx), Math.max(max.y, dy), Math.max(max.z, dz));
            }
            centroid.div(part.length);
            Bone modelBone = new Bone(name, bone.get("joint").getAsInt(),
                    bone.has("mapped") && bone.get("mapped").getAsBoolean(),
                    jointOfPosition[part[0]], pivot, min, max, centroid);
            modelBone.part = part;
            modelBone.vertices = verticesOfPart;
            model.verticesByBone.put(name, verticesOfPart.toArray(new Vector3f[0]));
            model.bones.put(name, modelBone);
        }
        return model;
    }

    private static void bindWorld(JsonArray bones, Map<String, Integer> index,
                                  Map<String, Matrix4f> out, int i, int depth) {
        JsonObject bone = bones.get(i).getAsJsonObject();
        String name = bone.get("name").getAsString();
        if (out.containsKey(name)) {
            return;
        }
        assertTrue(depth < 200, "cyclic or too-deep bone hierarchy");
        Matrix4f local = localOf(bone);
        Matrix4f result = new Matrix4f();
        if (bone.has("parent") && !bone.get("parent").getAsString().isEmpty()) {
            Integer parent = index.get(bone.get("parent").getAsString());
            if (parent != null) {
                bindWorld(bones, index, out, parent, depth + 1);
                result.set(out.get(bones.get(parent).getAsJsonObject().get("name").getAsString()));
            }
        }
        result.mul(local);
        out.put(name, result);
    }

    /** {@code YSMRuntimeModel#computeBindLocal}: T(p) Rz Ry Rx T(-p). */
    private static Matrix4f localOf(JsonObject bone) {
        JsonArray p = bone.getAsJsonArray("pivot");
        JsonArray r = bone.getAsJsonArray("rot");
        float px = p.get(0).getAsFloat();
        float py = p.get(1).getAsFloat();
        float pz = p.get(2).getAsFloat();
        return new Matrix4f()
                .translate(px, py, pz)
                .rotateZ(r.get(2).getAsFloat())
                .rotateY(r.get(1).getAsFloat())
                .rotateX(r.get(0).getAsFloat())
                .translate(-px, -py, -pz);
    }

    private static float scaleOf(JsonObject runtime, int index) {
        if (!runtime.has("scale")) {
            return 1.0F;
        }
        float value = runtime.getAsJsonArray("scale").get(index).getAsFloat();
        return value > 0.0F ? value : 1.0F;
    }

    // ------------------------------------------------------------------
    // the deployed build's own numbers
    // ------------------------------------------------------------------

    /** One row of the deployed build's {@code [physics] segments} line for this model. */
    private static final class Logged {
        final String name;
        final String tier;
        final float own;
        final float allowed;
        final float moved;
        final float lever;
        final Vector3f rest = new Vector3f();

        Logged(String name, String tier, float own, float allowed, float moved, float lever,
               Vector3f rest) {
            this.name = name;
            this.tier = tier;
            this.own = own;
            this.allowed = allowed;
            this.moved = moved;
            this.lever = lever;
            this.rest.set(rest);
        }
    }

    private static final Pattern SEGMENT = Pattern.compile(
            "([A-Za-z0-9_\\-.]+)\\((authored|name),(root|seg),(\\d+) parts, ([\\d.]+)Hz, "
                    + "([\\d.\\-]+)deg own/([\\d.\\-]+)deg whole/([\\d.\\-]+)deg allowed/([\\d.\\-]+)deg of "
                    + "([\\d.\\-]+)deg \\((\\d+)-joint piece\\) spent, (\\d+) joint\\(s\\) left, moved "
                    + "([\\d.\\-]+) blocks, L=([\\d.\\-]+), m=([\\d.\\-]+), follow=([\\d.]+), "
                    + "axis=\\(([\\d.\\-]+),([\\d.\\-]+),([\\d.\\-]+)\\), "
                    + "rest=\\(([\\d.\\-]+),([\\d.\\-]+),([\\d.\\-]+)\\), hit=([\\d.\\-]+)\\)");

    /** Parse the deployed log's own segment line for this model. */
    private static List<Logged> loggedSegments(Path instance) throws IOException {
        Path log = instance.resolve("logs").resolve("latest.log");
        assumeTrue(Files.isRegularFile(log), "no latest.log in " + instance);
        String line = null;
        // Not UTF-8: the game writes the console's own encoding into the same file, and one bad byte
        // in a chat line would otherwise fail the whole read. Every character this probe needs is
        // ASCII, and latin-1 maps every byte to one character.
        for (String candidate : new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1)
                .split("\\R")) {
            if (candidate.contains("[physics] segments of '" + MODEL + "'")) {
                line = candidate;
                break;
            }
        }
        assumeTrue(line != null, "the deployed log has no [physics] segments line for " + MODEL);
        List<Logged> out = new ArrayList<>();
        Matcher matcher = SEGMENT.matcher(line.substring(line.indexOf(MODEL + "'")));
        while (matcher.find()) {
            out.add(new Logged(matcher.group(1),
                    matcher.group(2) + "/" + matcher.group(3),
                    Float.parseFloat(matcher.group(6)), Float.parseFloat(matcher.group(8)),
                    Float.parseFloat(matcher.group(13)), Float.parseFloat(matcher.group(14)),
                    new Vector3f(Float.parseFloat(matcher.group(20)), Float.parseFloat(matcher.group(21)),
                            Float.parseFloat(matcher.group(22)))));
        }
        return out;
    }

    private static double angleFromDown(Vector3f v) {
        double length = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
        if (length == 0.0) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, -v.y / length))));
    }

    /** The angle between two directions, degrees. */
    private static double angleBetween(Vector3f a, Vector3f b) {
        double dot = a.x * b.x + a.y * b.y + a.z * b.z;
        double lengths = Math.sqrt(a.lengthSquared()) * Math.sqrt(b.lengthSquared());
        if (lengths == 0.0) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot / lengths))));
    }

    /** The logged segments grouped by the joint that carries them. */
    private static Map<String, List<String>> jointMembership(List<Logged> logged) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Model model;
        try {
            model = read(MODEL);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        for (Logged row : logged) {
            Bone bone = model.bone(row.name);
            out.computeIfAbsent("joint " + bone.joint, key -> new ArrayList<>()).add(row.name);
        }
        return out;
    }

    private static Logged rowOf(List<Logged> logged, String name) {
        for (Logged row : logged) {
            if (row.name.equals(name)) {
                return row;
            }
        }
        throw new IllegalStateException("no logged row for " + name);
    }

    // ------------------------------------------------------------------
    // paths and small helpers
    // ------------------------------------------------------------------

    /** The instance root, derived from the YSM config root the other probes take. */
    private static Path instanceRoot() {
        String configured = System.getProperty(
                com.ysmef.compat.ysm.YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (configured.isEmpty()) {
            String fromEnvironment = System.getenv(
                    com.ysmef.compat.ysm.YsmModelPackage.CONFIG_ROOT_ENV);
            configured = fromEnvironment == null ? "" : fromEnvironment;
        }
        if (configured.isEmpty()) {
            return null;
        }
        Path config = Paths.get(configured).toAbsolutePath().normalize();
        // The property names <instance>/config/yes_steve_model; walk up to the directory that holds
        // the instance's config/ and mods/ trees, so the probe also works if the root is given as the
        // config directory or the instance itself.
        for (Path candidate = config.getParent(); candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("mods"))
                    && Files.isDirectory(candidate.resolve("config"))) {
                return candidate;
            }
        }
        return config.getParent() == null ? null : config.getParent().getParent();
    }

    private static Path packRoot() {
        Path instance = instanceRoot();
        assertTrue(instance != null, "the YSM config root must be set");
        return instance.resolve("config").resolve("ysm_epicfight_compat").resolve("resourcepack")
                .resolve("assets").resolve("ysm_epicfight_compat");
    }

    private static Path deployedJar(Path instance) throws IOException {
        Path mods = instance.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return null;
        }
        try (var stream = Files.list(mods)) {
            return stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("YSM_EpicFight_Compat") && n.endsWith(".jar");
            }).findFirst().orElse(null);
        }
    }

    private static String findStem(Path dir, String modelId) throws IOException {
        if (Files.isRegularFile(dir.resolve(modelId + ".json"))) {
            return modelId;
        }
        String plain = (modelId.contains("/") ? modelId.substring(modelId.indexOf('/') + 1) : modelId)
                .toLowerCase(Locale.ROOT).replace(".ysm", "");
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(plain))
                    .findFirst().map(n -> n.substring(0, n.length() - 5)).orElse(null);
        }
    }

    /** The selected pieces whose own geometry sits above their pivot, for the report. */
    private static List<String> upwardNames(Model model, List<String> selected) {
        List<String> out = new ArrayList<>();
        for (String name : selected) {
            Bone bone = model.bone(name);
            if (bone != null && bone.rest.y > 0.0F) {
                out.add(name);
            }
        }
        return out;
    }

    /** The part count the running build reported for this model, out of its own {@code [iris-diag]} line. */
    private static int reportedPartCount(Path instance) throws IOException {
        Path log = instance.resolve("logs").resolve("latest.log");
        for (String line : new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1).split("\\R")) {
            if (line.contains("[iris-diag]") && line.contains("model='" + MODEL + "'")) {
                Matcher matcher = Pattern.compile("mesh\\.getPartCount\\(\\)=(\\d+)").matcher(line);
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        }
        throw new IllegalStateException("the deployed log has no [iris-diag] part count for " + MODEL);
    }

    private static JsonObject readJson(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "missing " + file);
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder text = new StringBuilder();
            for (byte b : hash) {
                text.append(String.format("%02x", b));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeReport(String name, List<String> lines) throws IOException {
        Path out = Paths.get("build", "reports", name);
        Files.createDirectories(out.getParent());
        Files.write(out, lines, StandardCharsets.UTF_8);
    }
}
