package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.JointTable;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The head region of the reported model under a <b>head pitch</b>: what each physics piece does, and
 * how much of it is the pose and how much is this mod's own delta.
 *
 * <h2>What this answers</h2>
 *
 * <p>The report is "the top-of-head hair moves <i>down</i> when the head looks up and <i>up</i> when
 * it looks down, and the whole hair block moves rather than just the tip". The quantity that decides
 * the second half is measured here for every simulated piece of the head region:
 *
 * <ul>
 *   <li>the <b>pose</b> ({@code pose x toOrigin}) - what Epic Fight's own animation does to the
 *       piece, which is rigid and carries the whole block;</li>
 *   <li>the <b>delta</b> ({@code T(P) R T(-P)} about the piece's own bind pivot, composed under its
 *       parent's) - what the simulation adds;</li>
 *   <li>the displacement, in blocks, of the piece's <b>root</b> (the vertex it is attached by: the
 *       one nearest the parent segment's pivot, or nearest the joint's own origin when it has no
 *       simulated ancestor) and of its <b>tip</b> (the vertex farthest from its own bind pivot),
 *       after the pose, and after the pose and the delta.</li>
 * </ul>
 *
 * <p>A delta that holds the root still and moves the tip is what the report asks for. A delta that
 * moves the root is the piece being <b>translated</b> rather than turned about where it is attached:
 * the delta's pivot is not on the piece, so {@code T(P) R T(-P)} sweeps it instead of hinging it.
 *
 * <h2>What is read, and what is driven</h2>
 *
 * <p>The model is the <b>deployed build's own converted artefacts</b> in the game instance - the
 * meshes the running client drew - and the reader that turns them into a bone table is calibrated
 * twice, with both calibrations asserted here rather than described:
 *
 * <ul>
 *   <li><b>against the client's own leg-region diagnostic</b> (same build, same model,
 *       {@code logs/latest.log} 14:21:21.812-.826): eleven pieces' pivot, own-geometry y range,
 *       centroid, lever, up-share and pivot gap, to the three decimals that line prints -
 *       {@link #LOGGED_LEG_ROWS};</li>
 *   <li><b>against the count {@code YsmPhysicsParts#pivotInMeshSpace}'s own javadoc records</b> for
 *       this model: the pivot is inside its own geometry's bounding box for 172 of the 223 bones that
 *       carry geometry on a joint, and for 4 of 223 under the corner-turned frame that was reverted.</li>
 * </ul>
 *
 * <p>The armature is calibrated a third time, against the client's own {@code [bind] ... pivots}
 * line for the other model of the same session ({@link #LOGGED_BIND_PIVOTS}, ten joints): the pitch
 * is a rotation about the Head joint's origin, so an armature that is off moves the centre of the
 * measurement.
 *
 * <p>The dynamics are the production solver, driven as {@code YsmMeshSecondaryMotion#resolveSegment}
 * drives it: the same {@code deformation = pose x toOrigin}, the same {@code pivot} and
 * {@code restDir} derived from it, the same {@code pivotDeltaOf}, the same allowance from
 * {@code chainAllowance}, and the same parent composition of the deltas. What this class cannot use
 * is {@code YsmPhysicsParts#build} itself - it takes a {@code YSMMesh}, whose constructor builds GPU
 * buffers - so the segment list is assembled from the same package-private seams {@code build} uses
 * ({@code selectBones}, {@code ownsItsGeometry}, {@code poseBelongsToEpicFight}, {@code wrapsPivot},
 * {@code risesOffPivot}, {@code swingLimit}, {@code classifyBone}, {@code radiusFor},
 * {@code pivotInMeshSpace}). Every value a rule decides on is production's; what is re-stated here is
 * the order the rules are asked in, and the two things this probe deliberately does <b>not</b> do:
 *
 * <ul>
 *   <li><b>the knit relaxation</b> ({@code relaxTowardsNeighbours}) is not applied. It averages each
 *       piece's swing toward the pieces sewn to it and would move the settled angles by a few
 *       degrees; the numbers below are therefore per piece, and the composed in-game number comes
 *       from the diagnostic this round also extends, which runs the real frame path.</li>
 *   <li><b>collision</b> is off ({@code NO_COLLIDERS}), which is the standing-still case the report
 *       is about.</li>
 * </ul>
 */
class HeadRegionPitchProbeTest {

    /** The reported model and the known-good one, as named in the converted pack. */
    private static final String[] EKU = {"EKU(1.0.ysm", "eku_1.0.ysm_9a5b9a1f"};
    private static final String[] MAID = {"wine_fox/01_taisho_maid", "wine_fox/01_taisho_maid"};

    /**
     * The simulated set the client logged for {@code EKU(1.0.ysm} in the session the defect was
     * reported in ({@code logs/latest.log} 14:21:21.810, "72 simulated bone(s) from bone names").
     * A quotation, not a computation.
     */
    private static final String LOGGED_EKU_SIMULATED =
            "mao2, mao6, group, Leftlonghair1_2_1, Leftlonghair1_2_3, Leftlonghair1_2_2, mao5, "
            + "LeftBackHair1_1, LeftBackHair1_3, LeftBackHair1_2, LeftBackHair1_4, mao9, mao8, mao7, "
            + "left_cefa, SkirtRight, RightLegclothes1, Rightbianzi, LeftBackHair2_2, LeftBackHair2_4, "
            + "LeftBackHair2_3, LeftBackHair2_1, BackLonghair1_3, BackLonghair1_4, RightBackHair1_3, "
            + "RightBackHair1_4, daimao, lalian, Uppercoat1, Leftlonghair1_3, Leftlonghair1_4, "
            + "SkirtFront, SkirtBack, Rightlonghair2_2, Rightlonghair2_4, Rightlonghair2_3, "
            + "Rightlonghair2_1_1, Rightlonghair2_1_2, hdj, Rightlonghair1_2_1, Rightlonghair1_2_2, "
            + "Rightlonghair1_2_3, Rightlonghair1_3, Rightlonghair1_4, Rightlonghair1_1, "
            + "Leftlonghair2_4, Leftlonghair2_2, Leftlonghair2_3, Leftlonghair2_1_2, "
            + "Leftlonghair2_1_1, hdj2, hdj3, fadai, Leftlonghair1_1, SkirtLeft, Left_Ear, "
            + "xiaobianzi, mao, RightBackHair2_4, RightBackHair2_2, RightBackHair2_3, qunziyingcang, "
            + "Right_Ear, LeftLegclothes2, LeftLegclothes1, right_cefa, BackLonghair1_1, "
            + "BackLonghair1_2, RightBackHair1_1, RightBackHair1_2, Leftbianzi, RightBackHair2_1";

    /** The same, for the other model of that session ({@code logs/latest.log} 14:21:53.384). */
    private static final String LOGGED_MAID_SIMULATED =
            "RB3, RB2, FL1, FL2, RB, RF, RF3, RF2, RM, RM2, RM3, LongRightHair, LongRightHair2, FM2, "
            + "BL, BL3, BL2, FM1, BM, BM2, BM3, BR, BR3, BR2, Tail, Tail5, Tail4, Tail7, Tail6, Tail3, "
            + "Tail2, FFM1, FFM1_1, FFM2, FFM2_1, FFM3, FFM3_1, LongLeftHair, LongLeftHair2, "
            + "RightSideHair, LongHair2, LongHair, FL, FM, FR, FR1, FR2, LM2, LM3, Bangs, LeftSideHair, "
            + "BaseHair, LB, LB3, LB2, LF, LF3, LF2, LM";

    /**
     * One row of the client's own leg-region diagnostic for {@code EKU(1.0.ysm}
     * ({@code logs/latest.log} 14:21:21.812-.826): bone, pivot, own-geometry y range, centroid,
     * lever, up-share, pivot gap - this reader's ground truth.
     */
    private record LoggedLegRow(String bone, float px, float py, float pz, float yLo, float yHi,
                               float cx, float cy, float cz, float lever, float upShare, float gap) {}

    private static final LoggedLegRow[] LOGGED_LEG_ROWS = {
            new LoggedLegRow("RightLegclothes2", 0.022F, 0.068F, 0.004F, 0.456F, 0.934F,
                    0.078F, 0.687F, 0.010F, 0.622F, 0.996F, 0.388F),
            new LoggedLegRow("RightLegclothes1", 0.092F, 0.897F, 0.014F, 0.456F, 0.967F,
                    0.089F, 0.759F, 0.000F, 0.139F, -0.995F, 0.000F),
            new LoggedLegRow("LeftLowerclothes2", -0.023F, 0.046F, -0.009F, 0.094F, 0.576F,
                    -0.088F, 0.256F, 0.022F, 0.222F, 0.947F, 0.048F),
            new LoggedLegRow("LeftLowerclothes1", -0.023F, 0.046F, -0.009F, 0.099F, 0.576F,
                    -0.105F, 0.420F, 0.031F, 0.385F, 0.972F, 0.053F),
            new LoggedLegRow("hdj", 0.088F, 0.443F, -0.046F, 0.251F, 0.473F,
                    0.089F, 0.398F, -0.048F, 0.045F, -0.999F, 0.000F),
            new LoggedLegRow("hdj2", 0.091F, 0.334F, -0.046F, 0.313F, 0.385F,
                    0.090F, 0.355F, -0.047F, 0.021F, 0.999F, 0.000F),
            new LoggedLegRow("hdj3", 0.093F, 0.246F, -0.046F, 0.226F, 0.298F,
                    0.093F, 0.267F, -0.047F, 0.021F, 0.999F, 0.000F),
            new LoggedLegRow("LeftLegclothes2", -0.092F, 0.897F, 0.014F, 0.456F, 0.934F,
                    -0.078F, 0.687F, 0.010F, 0.210F, -0.998F, 0.000F),
            new LoggedLegRow("LeftLegclothes1", -0.092F, 0.897F, 0.014F, 0.456F, 0.967F,
                    -0.089F, 0.758F, 0.000F, 0.139F, -0.995F, 0.000F),
            new LoggedLegRow("RightLowerclothes1", 0.023F, 0.046F, -0.009F, 0.099F, 0.576F,
                    0.105F, 0.423F, 0.039F, 0.389F, 0.970F, 0.053F),
            new LoggedLegRow("RightLowerclothes2", 0.023F, 0.046F, -0.009F, 0.094F, 0.576F,
                    0.091F, 0.265F, 0.015F, 0.230F, 0.950F, 0.048F),
    };

    /**
     * The armature the client built for the other model of the same session, as it printed it
     * ({@code logs/latest.log} 14:21:53.370-371, {@code [bind] ... pivots ...} and
     * {@code [diag] bind armature joints}): joint name, {@link JointTable} id, and the pivot in model
     * space. The two Tool rows are taken from the {@code [diag]} line, because the {@code [bind]}
     * line's {@code wristR}/{@code wristL} columns are the wrist and fist points the rest of the mod
     * reads back, not the joint's pivot - they differ by 0.010 blocks, and reading the wrong one is
     * how this table first reported a disagreement that was not there.
     */
    private static final Object[][] LOGGED_BIND_PIVOTS = {
            {"Root", JointTable.ROOT, 0.000F, 0.862F, 0.000F},
            {"Torso", JointTable.TORSO, 0.000F, 0.862F, 0.000F},
            {"Chest", JointTable.CHEST, 0.000F, 1.102F, -0.005F},
            {"Head", JointTable.HEAD, 0.000F, 1.343F, -0.010F},
            {"Shoulder_R", JointTable.SHOULDER_R, 0.213F, 1.341F, 0.000F},
            {"Shoulder_L", JointTable.SHOULDER_L, -0.213F, 1.341F, 0.000F},
            {"Elbow_R", JointTable.ELBOW_R, 0.216F, 1.061F, 0.001F},
            {"Elbow_L", JointTable.ELBOW_L, -0.216F, 1.061F, 0.001F},
            {"Tool_R", JointTable.TOOL_R, 0.327F, 0.743F, 0.002F},
            {"Tool_L", JointTable.TOOL_L, -0.327F, 0.743F, 0.002F},
    };

    /** How far a reproduced row may differ from the client's printed number: the log rounds to 3. */
    private static final float CALIBRATION_TOLERANCE = 0.002F;

    /**
     * The reference biped's own rest rotations for the four leg joints, as the client printed them
     * in the same leg-region diagnostic ({@code joint 1 'Thigh_R': rest toOrigin 180.0 deg, rest
     * local 180.0 deg, ... | EF biped rest toOrigin 180.0 deg, EF biped rest local 180.0 deg}):
     * joint, the logged rest local, the logged rest {@code toOrigin}. The last two columns of that
     * line are Epic Fight's own {@code Armatures.BIPED} values, so this pins the bundled
     * {@code biped.json} reading against the running game.
     */
    private static final Object[][] LOGGED_BIPED_REST_ROTATIONS = {
            {JointTable.THIGH_R, 180.0F, 180.0F},
            {JointTable.LEG_R, 0.0F, 180.0F},
            {JointTable.THIGH_L, 180.0F, 180.0F},
            {JointTable.LEG_L, 0.0F, 180.0F},
    };

    /** Head pitches measured. Both signs, three magnitudes: the verdict must not need one number. */
    private static final float[] PITCH_DEGREES = {15.0F, 30.0F, 45.0F};

    /** Steps of the production solver per pitch, at the client's own step (~60 fps). */
    private static final int SETTLE_STEPS = 300;
    private static final float DT = 1.0F / 60.0F;

    /** The most rows in one table. Above the head region's own piece count, so nothing is cut. */
    private static final int TABLE_LIMIT = 80;

    @Test
    void theHeadRegionOfTheReportedModelUnderAHeadPitch() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");

        StringBuilder report = new StringBuilder();
        report.append("# The head region under a head pitch, read from the deployed build's own artefacts\n\n");
        List<String> problems = new ArrayList<>();

        Rig eku = Rig.load(pack, EKU[1], LOGGED_EKU_SIMULATED);
        String problem = calibrateTheMatrixConvention(report);
        if (problem != null) {
            problems.add(problem);
        }
        problem = calibrateAgainstTheClientsLegRows(eku, report);
        if (problem != null) {
            problems.add(problem);
        }
        problem = calibrateAgainstTheDocumentedContainmentCount(eku, report);
        if (problem != null) {
            problems.add(problem);
        }

        Rig maid = Rig.load(pack, MAID[1], LOGGED_MAID_SIMULATED);
        problem = calibrateArmatureAgainstTheClientsBindLine(maid, report);
        if (problem != null) {
            problems.add(problem);
        }

        if (!problems.isEmpty()) {
            // Written before the assertions so a miscalibration still leaves the whole measurement on
            // disk to be read - but marked, because nothing under it can be trusted.
            report.append("> **A CALIBRATION FAILED. The numbers below are in an unverified frame and ")
                    .append("must not be used.**\n\n");
        }
        report.append(eku.headReport());
        report.append(eku.offPivotReport());
        report.append(eku.containmentReport());
        report.append(pitchReport(eku));
        report.append(maid.headReport());
        report.append(maid.offPivotReport());
        report.append(maid.containmentReport());
        report.append(pitchReport(maid));

        Path out = Paths.get("build", "reports", "ysm-head-region-pitch.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        // The tables are long by design; the console gets the calibrations and the summaries, the
        // file gets everything.
        StringBuilder console = new StringBuilder();
        for (String line : report.toString().split("\n", -1)) {
            if (line.startsWith("#") || line.startsWith("| joint |") || line.startsWith("Eleven pieces")
                    || line.startsWith("Documented for") || line.startsWith("Ten joints")
                    || line.startsWith("Worst") || line.contains("head pieces swing")
                    || line.startsWith("The raw `computePivots`") || line.startsWith("A CALIBRATION")) {
                console.append(line).append('\n');
            }
        }
        System.out.println(console);

        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * The pieces that <b>rest on</b> the geometry above them, and what each candidate rule does to
     * them at the pitch the defect was reported at.
     *
     * <p>The reported frame has the Head joint at 53 degrees, and the residual the user still sees is
     * the one thing a hinge cannot remove: a rigid turn of a piece that sits on a support tips its far
     * edge off that support by {@code 2 r sin(theta/2)} whatever point it is turned about. This probe
     * measures three rules at 50, 55 and 60 degrees, both signs, on the deployed build's own converted
     * artefacts, with the production solver and the production delta order:
     *
     * <ul>
     *   <li><b>now</b> - the shipped build: the piece's own swing about its measured contact anchor,
     *       composed under its parent's delta;</li>
     *   <li><b>rigid</b> - a piece whose contact patch is below its own centre of mass follows the pose
     *       exactly (no delta of its own and no carry from a swinging parent); every other piece is
     *       untouched, which is what "the pieces that must keep swinging do" means numerically;</li>
     *   <li><b>capped</b> - the same classification, but the resting piece keeps at most
     *       {@link #RESTING_SWING_CAP_DEGREES} of its own swing about its anchor.</li>
     * </ul>
     *
     * <p>Two numbers per rule, in blocks, both measured against the geometry the piece rests on:
     * <b>held shift</b> (how far the delta moves the vertex the piece is held by - the slide) and
     * <b>far pull</b> (the largest amount by which any of its own vertices is pulled <i>away</i> from
     * that geometry - the lift-off the eye reads as the piece coming off the skull).
     */
    @Test
    void theRestingPiecesUnderTheFailingFramesPitch() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");
        Rig maid = Rig.load(pack, MAID[1], LOGGED_MAID_SIMULATED);
        // The other model the brief pins: its own segment set is what "nothing else of its 89 may newly
        // drop" is about, and the candidate rules here change a delta, never the set - measured, not
        // argued, by reporting the same classifier over it.
        Rig eku = Rig.load(pack, EKU[1], LOGGED_EKU_SIMULATED);
        StringBuilder report = new StringBuilder();
        report.append("# The resting piece: what is carried by its support, and what each rule does ")
                .append("at the reported pitch\n\n");
        report.append("## `").append(maid.modelId).append("`\n\n").append(maid.restingReport());
        report.append("## `").append(eku.modelId).append("`\n\n").append(eku.restingReport());
        Path out = Paths.get("build", "reports", "ysm-resting-piece.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        StringBuilder console = new StringBuilder();
        for (String line : report.toString().split("\n", -1)) {
            if (line.startsWith("#") || line.startsWith("At ") || line.startsWith("Worst")
                    || line.startsWith("The cap") || line.startsWith("resting pieces that would stop")
                    || line.startsWith("resting:") || line.startsWith("hanging:")
                    || line.startsWith("candidate") || line.startsWith("named ")) {
                console.append(line).append('\n');
            }
        }
        System.out.println(console);

        // The measurement is the deployed build's own geometry, checked against the line the live
        // client printed for this very piece (logs/latest.log: "bone 'BaseHair' ... 0.162 blocks from
        // its own pivot (pivot (0.0,1.575,0.206), hinge (-0.015,1.423,0.151))"). If the reader had
        // drifted, everything in the report would be about a different piece.
        Segment cap = maid.segmentNamed("BaseHair");
        assertNotNull(cap, "the reported model has no simulated 'BaseHair' piece");
        assertEquals(0.000F, cap.bindPivot.x, CALIBRATION_TOLERANCE);
        assertEquals(1.575F, cap.bindPivot.y, CALIBRATION_TOLERANCE);
        assertEquals(0.206F, cap.bindPivot.z, CALIBRATION_TOLERANCE);
        assertEquals(0.162F, cap.bindAnchor.distance(cap.bindPivot), CALIBRATION_TOLERANCE);
        assertEquals("Head", cap.supportName,
                "the cap must be in contact with the skull itself, not with another hair piece");
        assertFalse(cap.supportSimulated, "the skull is not a simulated piece");
        assertTrue(restsOnItsSupport(cap), "the cap is the piece this round is about");
        float up = maid.pitchSign() > 0.0F ? 1.0F : -1.0F;
        RestingRun capRun = maid.restingRun(cap, 55.0F * up);
        // Its own swing is the configuration's root allowance and nothing else: the piece is the base
        // of its piece, so swingLimit hands it maxAngleRoot, and the solver's demand saturates there at
        // every pitch in the failing frame's regime. That is why the tilt reads as the cap having
        // settled somewhere it does not belong rather than as the head's motion.
        assertEquals((float) Math.toDegrees(YsmPhysicsTuning.DEFAULTS.maxAngleRoot), capRun.ownDegrees,
                0.05F, "the cap's own swing must be its root allowance, saturated");
        assertTrue(capRun.farNow > 0.10F,
                "the residual is the cap's own turn off the skull, and it is visible");
        assertEquals(0.0F, capRun.heldRigid, 1.0E-4F,
                "a resting piece that follows the pose exactly cannot slide");
        assertEquals(0.0F, capRun.farRigid, 1.0E-4F,
                "a resting piece that follows the pose exactly cannot lift off its support");
        assertTrue(capRun.farCapped > 0.02F && capRun.farCapped < capRun.farNow,
                "capping the angle leaves part of the separation behind: it softens, it does not fix");
        // And the selector this round rejects, pinned with the numbers: the sign rule takes the maid's
        // own tail links - the brief's red line - while narrowing it to a support the simulation does
        // not move does not.
        for (String tail : new String[]{"Tail3", "Tail5", "Tail6", "Tail7"}) {
            Segment link = maid.segmentNamed(tail);
            assertNotNull(link, tail + " is not simulated on the reported model");
            assertTrue(restsOnItsSupport(link),
                    tail + " is classified resting by the sign rule: that is the red line it breaks");
        }
        for (String name : MUST_SWING_NAMES) {
            Segment piece = maid.segmentNamed(name);
            if (piece != null) {
                assertFalse(restsOnItsSupport(piece) && !piece.supportSimulated,
                        name + " is a piece the brief requires to keep swinging and the narrowed rule "
                                + "would stop it");
            }
        }
    }

    // ------------------------------------------------------------------
    // The per-model physics override: the pieces a file can hold rigid
    // ------------------------------------------------------------------

    /**
     * The pieces the brief puts on the table for {@code wine_fox/01_taisho_maid}: the reported cap,
     * and the two the earlier round flagged by name from the same structural rule.
     */
    private static final String[] OVERRIDE_CANDIDATES = {"BaseHair", "FFM1", "FR"};

    /** The pieces of that model which must keep swinging, whatever is held. */
    private static final String[] OVERRIDE_MUST_SWING = {
            "LongHair", "LongHair2", "Bangs", "LeftSideHair", "RightSideHair",
            "LongRightHair", "LongRightHair2", "LongLeftHair", "LongLeftHair2",
            "Tail", "Tail2", "Tail3", "Tail4", "Tail5", "Tail6", "Tail7"};

    /** The reported frame's own regime, and the production step the client runs its solver at. */
    private static final float OVERRIDE_PITCH_DEGREES = 55.0F;
    private static final int OVERRIDE_SETTLE_STEPS = 300;

    /** A body that is not turning. */
    private static final float[] NO_TURN = {0.0F, 0.0F};

    /**
     * What holding named bones rigid does, measured through the <b>production frame loop</b>.
     *
     * <p>The previous round measured the same idea through a re-statement of
     * {@code resolveSegment} ({@link #settleResting}). This one drives the shipped code itself:
     * {@link YsmMeshSecondaryMotion#simulate} over a {@link YsmMeshSecondaryMotion.State} built from
     * this model's own pieces, with {@code state.held} set exactly where
     * {@code YsmPhysicsOverrides#markHeld} sets it. So the numbers below are the deltas the mesh
     * would be given, not a second implementation's idea of them.
     *
     * <p>Three questions, in the order the brief asks them:
     *
     * <ul>
     *   <li><b>per candidate</b> - the far-side separation from the geometry it rests on, and the
     *       shift of the end it is held by, with and without the piece held;</li>
     *   <li><b>downstream</b> - what every <i>other</i> piece of the model does, as the largest
     *       distance any of its own vertices moves between the two runs. A piece that is a
     *       descendant of a held piece is expected to differ (it no longer composes under a swinging
     *       parent); a piece that is not must be bit-identical, or holding one bone would have moved
     *       another and the whole exercise would be a rebalance rather than a hold;</li>
     *   <li><b>the other model</b> - {@code EKU(1.0.ysm}, which has no override file: its pieces and
     *       their deltas must be the ones they are today.</li>
     * </ul>
     */
    @Test
    void thePerModelOverrideHoldsTheNamedBonesRigid() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");

        Rig maid = Rig.load(pack, MAID[1], LOGGED_MAID_SIMULATED);
        Rig eku = Rig.load(pack, EKU[1], LOGGED_EKU_SIMULATED);
        float up = maid.pitchSign() > 0.0F ? 1.0F : -1.0F;
        float degrees = OVERRIDE_PITCH_DEGREES * up;

        StringBuilder report = new StringBuilder();
        report.append("# The per-model physics override: what holding the named pieces rigid does\n\n")
                .append("The **production** frame loop (`YsmMeshSecondaryMotion.simulate`, the shipped ")
                .append("`resolveSegment`) over `").append(maid.modelId).append("`'s own ")
                .append(maid.segments.size()).append(" simulated pieces, at ")
                .append(fmt(degrees)).append(" deg of head pitch, ")
                .append(OVERRIDE_SETTLE_STEPS).append(" steps of ")
                .append(fmt(DT)).append(" s with no body velocity and no collision - the standing ")
                .append("case the report is about. `held` is set on the state exactly where ")
                .append("`YsmPhysicsOverrides#markHeld` sets it, so these are the deltas the mesh ")
                .append("would receive.\n\n")
                .append("`far pull` is the largest amount any of the piece's own vertices is moved ")
                .append("**away** from the cloud it rests on; `held shift` is how far the delta moves ")
                .append("the vertex it is held by. Both in blocks.\n\n");

        Set<String> shipped = Set.of(OVERRIDE_CANDIDATES);
        YsmMeshSecondaryMotion.State control = runFrameLoop(maid, degrees, Set.of());
        YsmMeshSecondaryMotion.State allHeld = runFrameLoop(maid, degrees, shipped);

        // The per-candidate table, each candidate held on its own: what it fixes, and whether
        // holding it moves anything that is not under it.
        report.append("## Each candidate held on its own\n\n")
                .append("| bone | support | support simulated | own swing now | whole swing now | ")
                .append("far pull now | far pull held | held shift now | held shift held | ")
                .append("descendants | worst move of a piece NOT under it |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<Segment> childrenOfCap = new ArrayList<>();
        for (String candidate : OVERRIDE_CANDIDATES) {
            Segment piece = maid.segmentNamed(candidate);
            assertNotNull(piece, "the brief names '" + candidate
                    + "' but this model has no such simulated piece");
            YsmMeshSecondaryMotion.State held = runFrameLoop(maid, degrees, Set.of(candidate));
            OpenMatrix4f now = control.deltas[piece.index];
            OpenMatrix4f fixed = held.deltas[piece.index];
            Vector3f root = maid.rootVertex(piece);
            float worstOther = 0.0F;
            String worstOtherName = "none";
            List<String> descendants = new ArrayList<>();
            for (Segment other : maid.segments) {
                if (other.index == piece.index) {
                    continue;
                }
                if (descendsFrom(maid, other.index, Set.of(piece.index))) {
                    descendants.add(other.name);
                    continue;
                }
                // The two runs' deltas for THAT piece: what holding this one did to it.
                float moved = maxMove(control.deltas[other.index], held.deltas[other.index],
                        other.index, maid);
                if (moved > worstOther) {
                    worstOther = moved;
                    worstOtherName = other.name;
                }
            }
            report.append("| `").append(candidate).append("` | `").append(piece.supportName)
                    .append("` | ").append(piece.supportSimulated ? "yes" : "no")
                    .append(" | ").append(fmt(control.lastDegrees[piece.index])).append(" deg | ")
                    .append(fmt(rotationAngleOf(now))).append(" deg | ")
                    .append(fmt(farPull(now, piece))).append(" | ")
                    .append(fmt(farPull(fixed, piece))).append(" | ")
                    .append(fmt(heldShift(now, root))).append(" | ")
                    .append(fmt(heldShift(fixed, root))).append(" | ")
                    .append(descendants.isEmpty() ? "none" : String.join(", ", descendants))
                    .append(" | ").append(fmt(worstOther)).append(" (`").append(worstOtherName)
                    .append("`) |\n");
            if ("BaseHair".equals(candidate)) {
                childrenOfCap.addAll(children(maid, piece));
            }
        }
        report.append("\nThe cap's own children (").append(childrenOfCap.isEmpty() ? "none" : "")
                .append(childrenOfCap.stream().map(child -> "`" + child.name + "`")
                        .collect(java.util.stream.Collectors.joining(", ")))
                .append(childrenOfCap.isEmpty()
                        ? "): nothing hangs under it, so holding it rigid cannot change any other "
                                + "piece's swing."
                        : "): they keep their own swing and compose under the held piece, so they "
                                + "stay attached and keep moving.")
                .append("\n\n");

        // Where those three sit among the whole model, which is what makes the list a ranking
        // rather than a choice: the artifact is a distance in blocks, and every piece has one.
        List<Segment> ranked = new ArrayList<>(maid.segments);
        Map<Integer, Float> farOf = new java.util.HashMap<>();
        for (Segment piece : ranked) {
            farOf.put(piece.index, farPull(control.deltas[piece.index], piece));
        }
        ranked.sort((a, b) -> Float.compare(farOf.get(b.index), farOf.get(a.index)));
        report.append("## Every simulated piece of the model, ranked by its far-side separation ")
                .append("(no override)\n\n")
                .append("`radius` is the piece's own collision radius, blocks - the scale the ")
                .append("separation is read against. `support` is the first ancestor above it that ")
                .append("carries geometry, and `support simulated` says whether that support is ")
                .append("itself a piece the simulation moves: a chain link's support is the link ")
                .append("above it (the piece is hanging, and its separation from the link above is ")
                .append("the swing that must keep happening), while a piece whose support is the ")
                .append("body is **carried**, and its separation is the artifact.\n\n")
                .append("| rank | bone | joint | support | support simulated | far pull | held shift | own swing | radius |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        int rank = 0;
        for (Segment piece : ranked) {
            rank++;
            report.append("| ").append(rank).append(" | `").append(piece.name).append("` | ")
                    .append(piece.joint).append(" | `").append(piece.supportName).append("` | ")
                    .append(piece.supportSimulated ? "yes" : "**no**").append(" | ")
                    .append(fmt(farOf.get(piece.index))).append(" | ")
                    .append(fmt(heldShift(control.deltas[piece.index], maid.rootVertex(piece))))
                    .append(" | ").append(fmt(control.lastDegrees[piece.index])).append(" deg | ")
                    .append(fmt(piece.radius)).append(" |\n");
        }
        report.append("\n");

        // The whole shipped list together, and the full per-piece diff: this is the blast radius.
        report.append("\n## All of them held together (")
                .append(String.join(", ", OVERRIDE_CANDIDATES)).append(")\n\n")
                .append("| piece | held | under a held piece | own swing now | own swing held | ")
                .append("whole swing now | whole swing held | moved by the override |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        Set<Integer> heldIndexes = new java.util.HashSet<>();
        for (String candidate : OVERRIDE_CANDIDATES) {
            heldIndexes.add(maid.segmentNamed(candidate).index);
        }
        float worstUnrelated = 0.0F;
        String worstUnrelatedName = "none";
        int identical = 0;
        List<String> touched = new ArrayList<>();
        for (Segment piece : maid.segments) {
            boolean isHeld = heldIndexes.contains(piece.index);
            boolean under = !isHeld && descendsFrom(maid, piece.index, heldIndexes);
            float moved = maxMove(control.deltas[piece.index], allHeld.deltas[piece.index],
                    piece.index, maid);
            if (moved <= 1.0E-7F) {
                identical++;
            } else {
                touched.add("`" + piece.name + "` " + fmt6(moved)
                        + (isHeld ? " (held)" : under ? " (under a held piece)" : ""));
                if (!isHeld && !under && moved > worstUnrelated) {
                    worstUnrelated = moved;
                    worstUnrelatedName = piece.name;
                }
            }
            report.append("| `").append(piece.name).append("` | ").append(isHeld ? "**yes**" : "")
                    .append(" | ").append(under ? "yes" : "")
                    .append(" | ").append(fmt(control.lastDegrees[piece.index])).append(" deg")
                    .append(" | ").append(fmt(allHeld.lastDegrees[piece.index])).append(" deg")
                    .append(" | ").append(fmt(rotationAngleOf(control.deltas[piece.index])))
                    .append(" deg | ").append(fmt(rotationAngleOf(allHeld.deltas[piece.index])))
                    .append(" deg | ").append(fmt6(moved)).append(" |\n");
        }
        report.append("\n").append(identical).append(" of ").append(maid.segments.size())
                .append(" pieces are identical to the digit with the override active. Every piece ")
                .append("that moved, with the distance any of its own vertices moved (blocks): ")
                .append(String.join("; ", touched)).append(".\n\n")
                .append("The pieces that are neither held nor under a held piece are the blast ")
                .append("radius: the largest move among them is `")
                .append(worstUnrelatedName).append("` by ").append(fmt6(worstUnrelated))
                .append(" blocks. What moves them is the knit relaxation, which no longer pulls a ")
                .append("panel sewn to a held one toward a swing that is not drawn; the whole of ")
                .append("that effect is a fraction of a millimetre against the ")
                .append(fmt(farOf.get(maid.segmentNamed("BaseHair").index)))
                .append(" blocks the override removes.\n\n");

        // The other model: no file for it, so nothing about it may differ - and the resolution is
        // asserted empty rather than assumed, because "the file is keyed by model id" is the whole
        // of why one model's override cannot reach another.
        YsmMeshSecondaryMotion.State ekuControl = runFrameLoop(eku, degrees, Set.of());
        YsmMeshSecondaryMotion.State ekuAgain = runFrameLoop(eku, degrees, Set.of());
        float worstEku = 0.0F;
        String worstEkuName = "none";
        for (Segment piece : eku.segments) {
            float moved = maxMove(ekuControl.deltas[piece.index], ekuAgain.deltas[piece.index],
                    piece.index, eku);
            if (moved > worstEku) {
                worstEku = moved;
                worstEkuName = piece.name;
            }
        }
        report.append("## `").append(eku.modelId).append("`\n\n")
                .append("Simulated pieces: ").append(eku.segments.size())
                .append("; the override file resolved for it: ")
                .append(YsmPhysicsOverrides.rigidBones(eku.modelId).isEmpty()
                        ? "none (empty set)" : YsmPhysicsOverrides.rigidBones(eku.modelId).toString())
                .append("; largest move between two runs: ").append(fmt(worstEku))
                .append(" blocks (`").append(worstEkuName).append("`).\n\n");

        Path out = Paths.get("build", "reports", "ysm-physics-override-bones.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // ---- the red lines, as assertions -------------------------------------------------
        // 1. The reported cap: the whole defect, and its exact removal.
        Segment cap = maid.segmentNamed("BaseHair");
        OpenMatrix4f capNow = control.deltas[cap.index];
        OpenMatrix4f capHeld = allHeld.deltas[cap.index];
        assertTrue(farPull(capNow, cap) > 0.10F,
                "the cap's far-side separation must be the reported defect before the override");
        assertTrue(heldShift(capNow, maid.rootVertex(cap)) > 0.05F,
                "and the end it is held by must slide, which is what the user still sees");
        assertTrue(isIdentity(capHeld), "held rigid means the identity delta, and nothing else");
        assertEquals(0.0F, heldShift(capHeld, maid.rootVertex(cap)), 0.0F);
        assertEquals(0.0F, farPull(capHeld, cap), 0.0F);

        // 2. Everything the brief requires to keep swinging, digit for digit.
        for (String name : OVERRIDE_MUST_SWING) {
            Segment piece = maid.segmentNamed(name);
            if (piece == null) {
                continue;
            }
            assertFalse(descendsFrom(maid, piece.index, heldIndexes),
                    name + " hangs under a held piece, so its delta is not its own any more");
            assertEquals(0.0F, maxMove(control.deltas[piece.index], allHeld.deltas[piece.index],
                            piece.index, maid), 0.0F,
                    name + " must keep swinging exactly as it does now");
        }
        // The blast radius, bounded rather than asserted away: a piece sewn to a held one is no
        // longer pulled toward a swing that is not drawn, and that coupling is worth a fraction of
        // a millimetre - measured at 0.000593 blocks on this model, against the 0.119 the override
        // removes. The bound is a millimetre, so a real leak of the swing into a neighbour fails.
        assertTrue(worstUnrelated < 0.001F,
                "an override may not move a piece that is not under it (worst: " + worstUnrelatedName
                        + " by " + fmt6(worstUnrelated) + " blocks)");

        // 3. The other model, which the brief pins: unaffected, because nothing resolved for it.
        assertTrue(YsmPhysicsOverrides.rigidBones(eku.modelId).isEmpty(),
                "no override file exists for " + eku.modelId);
        assertEquals(0.0F, worstEku, 0.0F,
                "a model with no override file must behave exactly as it does today");
        assertEquals(LOGGED_EKU_SIMULATED.split(",").length, eku.segments.size(),
                "EKU's simulated piece set is its own classification's, and an override cannot "
                        + "change it: this is the set the client logged for it");

        // 4. The measurement itself, written where the round can quote it.
        assertTrue(Files.size(out) > 0L);
        assertEquals("Head", cap.supportName,
                "the cap is held by the skull itself: that is what 'it rests on' means here");
        assertFalse(cap.supportSimulated, "and the skull is not a piece the simulation moves");

        // 5. The list is read off that ranking rather than chosen: among the pieces the body
        //    CARRIES - the ones whose separation from their support is the artifact, as opposed to
        //    the chain links above them, whose separation from the link above is the swing that must
        //    keep happening - the reported cap is the worst on the model.
        Segment worstCarried = null;
        for (Segment piece : ranked) {
            if (!piece.supportSimulated && restsOnItsSupport(piece)) {
                worstCarried = piece;
                break;
            }
        }
        assertNotNull(worstCarried, "the model must carry at least one piece");
        assertEquals("BaseHair", worstCarried.name,
                "the worst body-carried piece of this model is the cap the user reported");
        assertTrue(farOf.get(worstCarried.index) > 0.10F,
                "and its separation is over a tenth of a block: " + fmt(farOf.get(worstCarried.index)));
    }

    // ------------------------------------------------------------------
    // The production frame loop, driven for the override measurement
    // ------------------------------------------------------------------

    /** This model's pieces as the production record, from the probe's own reading of the model. */
    private static YsmPhysicsParts.Model productionModel(Rig rig) {
        YsmPhysicsParts.Segment[] out = new YsmPhysicsParts.Segment[rig.segments.size()];
        for (int i = 0; i < out.length; i++) {
            Segment s = rig.segments.get(i);
            out[i] = new YsmPhysicsParts.Segment(s.boneIndex, s.name, s.joint, new Vector3f(s.bindPivot),
                    new Vector3f(s.bindAnchor), new Vector3f(s.bindRest), s.lever, s.radius, s.mass,
                    (float) YsmPhysicsTuning.DEFAULTS.frequency(),
                    (float) YsmPhysicsTuning.DEFAULTS.dampingRatio(),
                    s.maxAngle, s.parent, new int[0], false, new int[0],
                    YsmPhysicsParts.classifyBone(rig.bones, s.boneIndex));
        }
        return new YsmPhysicsParts.Model(out, YsmPhysicsParts.Source.BONE_NAMES, 0);
    }

    /**
     * Run the production frame loop over this model's pieces, holding the named bones.
     *
     * <p>{@code state.held} is written here the way {@code YsmPhysicsOverrides#markHeld} writes it -
     * by matching the piece's own bone name - so what is measured is the shipped frame path with the
     * shipped flag, and not a second composition of the same idea.
     */
    private static YsmMeshSecondaryMotion.State runFrameLoop(Rig rig, float degrees, Set<String> held) {
        YsmPhysicsParts.Model parts = productionModel(rig);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                parts, null, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        for (int i = 0; i < parts.segments().length; i++) {
            state.held[i] = held.contains(parts.segments()[i].boneName());
        }
        YsmMeshSecondaryMotion.PoseSource pose = new PitchPose(rig, degrees);
        for (int step = 0; step < OVERRIDE_SETTLE_STEPS; step++) {
            YsmMeshSecondaryMotion.simulate(state, pose, DT, null, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }
        return state;
    }

    /** The drawn frame's pose for the override measurement: a pitch about the Head joint. */
    private static final class PitchPose implements YsmMeshSecondaryMotion.PoseSource {
        /** {@code toOrigin} is already folded into {@link Rig#deformationFor}, so it is the identity. */
        private static final OpenMatrix4f TO_ORIGIN = new OpenMatrix4f();
        private final OpenMatrix4f deformation;

        PitchPose(Rig rig, float degrees) {
            this.deformation = rig.deformationFor(JointTable.HEAD, degrees);
        }

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return TO_ORIGIN;
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            return deformation;
        }
    }

    /** The pieces that hang, directly or not, under any of these. */
    private static boolean descendsFrom(Rig rig, int index, Set<Integer> roots) {
        int guard = 0;
        for (int at = index; at >= 0 && guard++ <= rig.segments.size(); ) {
            if (roots.contains(at)) {
                return at != index;
            }
            at = rig.segments.get(at).parent;
        }
        return false;
    }

    /** The pieces whose parent link points at this one. */
    private static List<Segment> children(Rig rig, Segment parent) {
        List<Segment> out = new ArrayList<>();
        for (Segment segment : rig.segments) {
            if (segment.parent == parent.index) {
                out.add(segment);
            }
        }
        return out;
    }

    /** The largest distance any of a piece's own vertices moves between two frames' deltas. */
    private static float maxMove(OpenMatrix4f before, OpenMatrix4f after, int index, Rig rig) {
        List<Vector3f> own = rig.vertices.get(rig.segments.get(index).boneIndex);
        if (own == null || own.isEmpty()) {
            return 0.0F;
        }
        float worst = 0.0F;
        for (Vector3f vertex : own) {
            Vector3f a = YsmMeshSecondaryMotion.transformPoint(before, vertex, new Vector3f());
            Vector3f b = YsmMeshSecondaryMotion.transformPoint(after, vertex, new Vector3f());
            worst = Math.max(worst, a.distance(b));
        }
        return worst;
    }

    /** How far a rigid delta moves one point. */
    private static float heldShift(OpenMatrix4f delta, Vector3f vertex) {
        return YsmMeshSecondaryMotion.transformPoint(delta, vertex, new Vector3f()).distance(vertex);
    }

    /** The largest distance any of the piece's own vertices gains from the cloud it rests on. */
    private static float farPull(OpenMatrix4f delta, Segment segment) {
        if (delta == null || segment.own == null || segment.own.isEmpty()
                || segment.restsOn == null || segment.restsOn.isEmpty()) {
            return Float.NaN;
        }
        float worst = Float.NEGATIVE_INFINITY;
        for (Vector3f vertex : segment.own) {
            float before = nearestDistance(vertex, segment.restsOn);
            float after = nearestDistance(
                    YsmMeshSecondaryMotion.transformPoint(delta, vertex, new Vector3f()),
                    segment.restsOn);
            worst = Math.max(worst, after - before);
        }
        return worst == Float.NEGATIVE_INFINITY ? Float.NaN : worst;
    }

    /** The rotation angle of a delta, in degrees. */
    private static float rotationAngleOf(OpenMatrix4f matrix) {
        float trace = matrix.m00 + matrix.m11 + matrix.m22;
        float cosine = Math.max(-1.0F, Math.min(1.0F, (trace - 1.0F) * 0.5F));
        float angle = (float) Math.acos(cosine);
        return Float.isFinite(angle) ? (float) Math.toDegrees(angle) : 0.0F;
    }

    /** Blocks to a micrometre, for the numbers whose whole question is "is this exactly zero". */
    private static String fmt6(float value) {
        return Float.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "n/a";
    }

    /** Exactly the identity, entry for entry: "held rigid" is not "nearly held". */
    private static boolean isIdentity(OpenMatrix4f matrix) {
        return matrix.m00 == 1.0F && matrix.m11 == 1.0F && matrix.m22 == 1.0F && matrix.m33 == 1.0F
                && matrix.m01 == 0.0F && matrix.m02 == 0.0F && matrix.m03 == 0.0F
                && matrix.m10 == 0.0F && matrix.m12 == 0.0F && matrix.m13 == 0.0F
                && matrix.m20 == 0.0F && matrix.m21 == 0.0F && matrix.m23 == 0.0F
                && matrix.m30 == 0.0F && matrix.m31 == 0.0F && matrix.m32 == 0.0F;
    }

    // ------------------------------------------------------------------
    // Calibration
    // ------------------------------------------------------------------

    /**
     * The matrix convention, before anything is built on it: is Epic Fight's {@code OpenMatrix4f} read
     * the way this probe reads it (translation at {@code m30..m32}, point transformed as
     * {@code M x p}), and does {@code OpenMatrix4f#invert} agree with an explicit rigid inverse?
     *
     * <p>This is Calibration 0 because the whole armature is built out of an inverse:
     * {@code YsmBindArmature#copyHierarchy} turns each joint's pivot into a local offset with
     * {@code invert(parentWorld) x pivot}. If {@code invert} is in the other convention the offsets
     * are wrong and every joint ends up somewhere else, which is a rig in the wrong place rather than
     * a subtly wrong number - and it is exactly the kind of thing that reads as "the head pitch moved
     * the hair" when the pitch's own rotation centre is what moved.
     */
    private static String calibrateTheMatrixConvention(StringBuilder report) {
        Matrix4f joml = new Matrix4f().translate(0.31F, 0.82F, -0.17F).rotateY(0.5F).rotateX(-0.3F);
        OpenMatrix4f ef = toOpen(joml);
        Vector3f point = new Vector3f(0.13F, -0.24F, 0.61F);
        Vector3f viaJoml = joml.transformPosition(new Vector3f(point));
        Vector3f viaEf = YsmMeshSecondaryMotion.transformPoint(ef, point, new Vector3f());
        float conventionGap = viaEf.distance(viaJoml);

        Vector3f back = YsmMeshSecondaryMotion.transformPoint(ef, inversePoint(ef, viaJoml),
                new Vector3f());
        float roundTripGap = back.distance(viaJoml);

        OpenMatrix4f efInverse = OpenMatrix4f.invert(ef, null);
        Vector3f viaEfInverse = YsmMeshSecondaryMotion.transformPoint(efInverse, viaJoml,
                new Vector3f());
        float efInverseGap = viaEfInverse.distance(point);

        // The product, which is what the armature's world is composed from.
        Matrix4f second = new Matrix4f().translate(-0.4F, 0.25F, 0.9F).rotateZ(0.7F);
        OpenMatrix4f efSecond = toOpen(second);
        Vector3f viaProduct = YsmMeshSecondaryMotion.transformPoint(
                OpenMatrix4f.mul(ef, efSecond, new OpenMatrix4f()), point, new Vector3f());
        Vector3f viaJomlProduct = new Matrix4f(joml).mul(second).transformPosition(new Vector3f(point));
        float productGap = viaProduct.distance(viaJomlProduct);

        // The copy constructor, which every rebuilt joint's local transform goes through.
        OpenMatrix4f copied = new OpenMatrix4f(ef);
        float copyGap = maxComponentGap(copied, ef);

        report.append("## Calibration 0: the matrix convention\n\n")
                .append("| quantity | gap |\n|---|---|\n")
                .append("| `OpenMatrix4f` point transform vs JOML, same rigid transform | ")
                .append(fmt(conventionGap)).append(" |\n")
                .append("| `OpenMatrix4f.mul(a, b)` vs JOML `a x b`, on a point | ")
                .append(fmt(productGap)).append(" |\n")
                .append("| `new OpenMatrix4f(m)` copies m field for field | ")
                .append(fmt(copyGap)).append(" |\n")
                .append("| explicit rigid inverse, then the forward transform | ")
                .append(fmt(roundTripGap)).append(" |\n")
                .append("| `OpenMatrix4f#invert`, then the forward transform | ")
                .append(fmt(efInverseGap)).append(" |\n\n");
        if (conventionGap > 1.0E-4F) {
            return "the matrix convention is not what this probe assumes: transforming a point with "
                    + "OpenMatrix4f disagrees with JOML by " + conventionGap + " blocks";
        }
        if (productGap > 1.0E-4F) {
            return "OpenMatrix4f.mul does not compose the way JOML does: a point through the product "
                    + "lands " + productGap + " blocks away";
        }
        if (copyGap > 1.0E-6F) {
            return "OpenMatrix4f's copy constructor does not copy field for field: largest field "
                    + "difference " + copyGap;
        }
        if (roundTripGap > 1.0E-4F) {
            return "the explicit rigid inverse this probe builds the armature with does not round "
                    + "trip: " + roundTripGap + " blocks";
        }
        return null;
    }

    /** The largest absolute field difference between two 4x4s. */
    private static float maxComponentGap(OpenMatrix4f a, OpenMatrix4f b) {
        float[] left = {a.m00, a.m01, a.m02, a.m03, a.m10, a.m11, a.m12, a.m13,
                a.m20, a.m21, a.m22, a.m23, a.m30, a.m31, a.m32, a.m33};
        float[] right = {b.m00, b.m01, b.m02, b.m03, b.m10, b.m11, b.m12, b.m13,
                b.m20, b.m21, b.m22, b.m23, b.m30, b.m31, b.m32, b.m33};
        float worst = 0.0F;
        for (int i = 0; i < left.length; i++) {
            worst = Math.max(worst, Math.abs(left[i] - right[i]));
        }
        return worst;
    }

    /** The reader against the client's own leg-region diagnostic: eleven pieces, eleven numbers each. */
    private static String calibrateAgainstTheClientsLegRows(Rig rig, StringBuilder report) {
        float worst = 0.0F;
        String worstLabel = "none";
        for (LoggedLegRow row : LOGGED_LEG_ROWS) {
            Integer index = rig.boneIndex.get(row.bone());
            if (index == null) {
                return "the converted model has no bone '" + row.bone()
                        + "', so this calibration cannot be run and no head number is trustworthy";
            }
            Vector3f pivot = rig.pivot(index);
            List<Vector3f> own = rig.vertices.get(index);
            if (pivot == null || own == null || own.isEmpty()) {
                return "no geometry or pivot for '" + row.bone() + "'";
            }
            Vector3f centre = Rig.centroid(own);
            float lever = centre.distance(pivot);
            float upShare = (centre.y - pivot.y) / lever;
            float gap = (float) YsmPhysicsParts.pivotGapFromGeometry(own, pivot);
            float[] deviations = {
                    Math.abs(pivot.x - row.px()), Math.abs(pivot.y - row.py()),
                    Math.abs(pivot.z - row.pz()),
                    Math.abs(YsmPhysicsParts.minY(own) - row.yLo()),
                    Math.abs(YsmPhysicsParts.maxY(own) - row.yHi()),
                    Math.abs(centre.x - row.cx()), Math.abs(centre.y - row.cy()),
                    Math.abs(centre.z - row.cz()),
                    Math.abs(lever - row.lever()), Math.abs(upShare - row.upShare()),
                    Math.abs(gap - row.gap())};
            for (float deviation : deviations) {
                if (deviation > worst) {
                    worst = deviation;
                    worstLabel = row.bone();
                }
            }
        }
        report.append("## Calibration 1: the reader against the client's own leg-region diagnostic\n\n")
                .append("Eleven pieces of this model, eleven numbers each, from `logs/latest.log` ")
                .append("(14:21:21.812-.826, the deployed build). Worst disagreement **")
                .append(fmt(worst)).append("** blocks (`").append(worstLabel)
                .append("`), against a tolerance of ").append(fmt(CALIBRATION_TOLERANCE))
                .append(" - the log's own rounding.\n\n");
        return worst <= CALIBRATION_TOLERANCE ? null
                : "the offline reader no longer reproduces the client's own leg-region numbers for "
                        + "EKU(1.0.ysm: worst disagreement " + worst + " blocks (" + worstLabel + ")";
    }

    /**
     * The reader against the number {@code YsmPhysicsParts#pivotInMeshSpace} records: on this model,
     * with this build, the pivot is inside the piece's own geometry for 172 of the 223 bones that
     * carry geometry on a joint, and for 4 under the corner-turned frame that was tried and reverted.
     * Strict containment, because that is the count the comment states.
     */
    private static String calibrateAgainstTheDocumentedContainmentCount(Rig rig, StringBuilder report) {
        int bones = 0;
        int inside = 0;
        int insideTurned = 0;
        for (int index = 0; index < rig.bones.length; index++) {
            // Every bone with geometry, hidden or not: the documented count is over the model's
            // geometry as converted, and the physics' own vertex map is not what it was measured on.
            List<Vector3f> own = rig.ownGeometryAll.get(index);
            if (own == null || own.isEmpty() || rig.bones[index].joint < 0) {
                continue;
            }
            bones++;
            if (containsStrictly(own, rig.pivot(index))) {
                inside++;
            }
            Vector3f turned = new Vector3f(rig.bones[index].px, -rig.bones[index].pz, rig.bones[index].py);
            if (containsStrictly(own, rig.pivotOfLocal(index, turned))) {
                insideTurned++;
            }
        }
        report.append("## Calibration 2: the count `YsmPhysicsParts#pivotInMeshSpace` records\n\n")
                .append("Documented for this model and build: the pivot is inside its own geometry's ")
                .append("bounding box for **172 of 223** bones, and for **4** of 223 under the ")
                .append("corner-turned frame (the revision that was reverted). Measured here: **")
                .append(inside).append(" of ").append(bones).append("** and **").append(insideTurned)
                .append(" of ").append(bones).append("**.\n\n");
        return bones == 223 && inside == 172 && insideTurned == 4 ? null
                : "the reader no longer reproduces the containment counts recorded for EKU(1.0.ysm: "
                        + bones + " bones (documented 223), " + inside + " inside (documented 172), "
                        + insideTurned + " under the corner-turned frame (documented 4)";
    }

    /**
     * The armature this probe builds against the client's own {@code [bind] ... pivots} line for the
     * maid: ten joints, three decimals. The pitch rotates about the Head joint's origin, so an
     * armature that is off moves the centre of the measurement and every displacement with it.
     */
    private static String calibrateArmatureAgainstTheClientsBindLine(Rig rig, StringBuilder report) {
        float worst = 0.0F;
        String worstLabel = "none";
        StringBuilder table = new StringBuilder();
        table.append("| joint | the client built | this reader | disagreement |\n|---|---|---|---|\n");
        for (Object[] row : LOGGED_BIND_PIVOTS) {
            int joint = (Integer) row[1];
            Vector3f mine = rig.jointOrigin(joint);
            if (mine == null) {
                return "the offline armature has no joint " + row[0];
            }
            Vector3f logged = new Vector3f((Float) row[2], (Float) row[3], (Float) row[4]);
            float deviation = mine.distance(logged);
            table.append("| ").append(row[0]).append(" (").append(joint).append(") | ")
                    .append(point(logged)).append(" | ").append(point(mine)).append(" | ")
                    .append(fmt(deviation)).append(" |\n");
            if (deviation > worst) {
                worst = deviation;
                worstLabel = (String) row[0];
            }
        }
        report.append("## Calibration 3: the offline armature against the client's own bind line\n\n")
                .append("Ten joints of `").append(MAID[0]).append("`, from `logs/latest.log` ")
                .append("14:21:53.370. Worst disagreement **").append(fmt(worst))
                .append("** blocks (`").append(worstLabel).append("`). The default-hidden set this ")
                .append("reader excluded has ").append(rig.hidden.size()).append(" name(s)")
                .append(rig.hidden.isEmpty() ? "" : ": " + rig.hidden)
                .append(".\n\n")
                .append(table).append('\n');
        StringBuilder map = new StringBuilder();
        map.append("| joint | `computePivots` gave | the Client built |\n|---|---|---|\n");
        for (Object[] row : LOGGED_BIND_PIVOTS) {
            Vector3f mine = rig.bindPivotMap.get((Integer) row[1]);
            map.append("| ").append(row[0]).append(" (").append(row[1]).append(") | ")
                    .append(point(mine)).append(" | (")
                    .append(fmt((Float) row[2])).append(",").append(fmt((Float) row[3])).append(",")
                    .append(fmt((Float) row[4])).append(") |\n");
        }
        report.append("The raw `computePivots` map this reader got, against the client's own "
                + "`[diag] bind armature joints` line (14:21:53.371):\n\n").append(map).append('\n');

        // The reference rotations, against the client's own `EF biped rest local` numbers: the
        // second half of the armature this pitch rotates about, and the half a row-major /
        // column-major mix-up in the bundled biped.json shows up in. The comparison is against the
        // reference rig itself - Epic Fight's own biped, no model pivots in it - because that is
        // what the client's last two columns print.
        StringBuilder rotations = new StringBuilder();
        rotations.append("| joint | `EF biped rest local`, the client's line | the bundled biped.json, "
                + "read here | `EF biped rest toOrigin`, the client's line | the bundled biped.json, "
                + "read here |\n|---|---|---|---|---|\n");
        float worstRotation = 0.0F;
        for (Object[] row : LOGGED_BIPED_REST_ROTATIONS) {
            int joint = (Integer) row[0];
            Joint mine = rig.referenceJointsById.get(joint);
            double local = mine == null ? Double.NaN
                    : YsmMeshSecondaryMotion.degreesOf(mine.getLocalTransform());
            double toOrigin = mine == null || mine.getToOrigin() == null ? Double.NaN
                    : YsmMeshSecondaryMotion.degreesOf(mine.getToOrigin());
            rotations.append("| ").append(JointTable.nameOf(joint)).append(" (").append(joint)
                    .append(") | ").append(fmt((Float) row[1])).append(" deg | ")
                    .append(fmt((float) local)).append(" deg | ").append(fmt((Float) row[2]))
                    .append(" deg | ").append(fmt((float) toOrigin)).append(" deg |\n");
            worstRotation = Math.max(worstRotation, Math.abs((float) local - (Float) row[1]));
        }
        report.append("The reference rotations, against the same client log line's own `EF biped "
                + "rest local` / `EF biped rest toOrigin` columns. This is the bundled biped.json "
                + "read with no model pivots in it, which is what Epic Fight's own biped is:\n\n")
                .append(rotations).append('\n')
                .append("The `rest local` column is the one this probe uses and it matches the client ")
                .append("exactly. The `rest toOrigin` column is 90 degrees away on all four joints, ")
                .append("and the reason is a frame and not a reading: Epic Fight's own armature is ")
                .append("turned out of Blender's z-up frame as it is loaded, while the raw JSON read ")
                .append("here carries that turn as the Root's own local rotation - visible in the ")
                .append("bundled file as the Root's `[1,0,0,0, 0,0,-1,0, 0,1,0,0]`. It does not reach ")
                .append("this measurement: the head pitch below is a rotation in the mesh's own frame ")
                .append("about the Head joint's origin, and the origin and the pieces' bind pivots are ")
                .append("the two armature quantities it uses, both of which match the client to 0.000 ")
                .append("blocks above.\n\n");
        if (worstRotation > 0.2F) {
            return "the bundled biped.json no longer reads the way the client's own `EF biped rest "
                    + "local` column says it does: worst disagreement " + worstRotation + " degrees. "
                    + "Table:\n" + rotations;
        }
        return worst <= 0.02F ? null
                : "the offline armature no longer matches the armature the client built for "
                        + MAID[0] + ": worst joint pivot disagreement " + worst + " blocks ("
                        + worstLabel + "). The head pitch rotates about the Head joint's origin, so "
                        + "this number is load-bearing. Table:\n" + table;
    }

    /** Strict bounding-box containment: no slack, because the documented count uses none. */
    private static boolean containsStrictly(List<Vector3f> vertices, Vector3f pivot) {
        if (vertices == null || vertices.isEmpty() || pivot == null) {
            return false;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        int used = 0;
        for (Vector3f vertex : vertices) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
            used++;
        }
        return used >= 4 && pivot.x >= minX && pivot.x <= maxX && pivot.y >= minY && pivot.y <= maxY
                && pivot.z >= minZ && pivot.z <= maxZ;
    }

    // ------------------------------------------------------------------
    // The pitch measurement
    // ------------------------------------------------------------------

    private static String pitchReport(Rig rig) {
        StringBuilder out = new StringBuilder();
        Vector3f face = rig.face;
        float perDegree = rig.pitchSign();
        out.append("## The head pitch\n\n")
                .append("A pitch is a rotation of joint ").append(JointTable.HEAD)
                .append(" (Head) about its own origin, which the armature above puts at ")
                .append(point(rig.jointOrigin(JointTable.HEAD)))
                .append(". The model's own face normal, out of the converted runtime's camera block, is ")
                .append(point(face)).append("; a rotation of one degree about the model's x axis moves ")
                .append("that normal's height by ").append(fmt(perDegree))
                .append(" blocks, so **+x is look ").append(perDegree > 0.0F ? "up" : "down")
                .append("**.\n\n");
        assertTrue(Math.abs(perDegree) > 1.0E-4F,
                "the face normal does not move under an x rotation, so 'look up' cannot be identified "
                        + "and the signs below would be a coin flip");
        out.append("`own swing` is the solver's angle between the pitched rest direction and where the ")
                .append("piece settled; `whole swing` is the rotation of the composed delta, which is ")
                .append("what a viewer sees added to the pose. Every `dy` is in the model's own frame, ")
                .append("so **positive is up**: `pose tip dy` and `drawn tip dy` are where the tip ends ")
                .append("up relative to where the model was authored, with the pose alone and with the ")
                .append("pose and the simulation together. `delta would move the root` is ")
                .append("`2 L sin(a/2)` for the root's own lever `L` and that swing - zero exactly when ")
                .append("the piece's bind pivot is the point it is attached by, and the figure the ")
                .append("measured `delta root` has to agree with.\n\n");

        for (float degrees : PITCH_DEGREES) {
            out.append(pitchSection(rig, degrees * (perDegree > 0.0F ? 1.0F : -1.0F), "look up"));
            out.append(pitchSection(rig, -degrees * (perDegree > 0.0F ? 1.0F : -1.0F), "look down"));
        }
        return out.toString();
    }

    private static String pitchSection(Rig rig, float degrees, String label) {
        List<Segment> head = rig.headSegments();
        Map<Integer, Run> runs = new LinkedHashMap<>();
        for (Segment segment : head) {
            settle(rig, segment, degrees, runs);
        }
        int moved = 0;
        int rootMoved = 0;
        float worstStructuralGap = 0.0F;
        String worstStructuralBone = "none";
        for (Map.Entry<Integer, Run> entry : runs.entrySet()) {
            Run run = entry.getValue();
            if (run == null) {
                continue;
            }
            if (run.wholeDegrees > 1.0F) {
                moved++;
            }
            if (run.deltaRoot > 0.005F) {
                rootMoved++;
            }
            float gap = Math.abs(run.deltaRoot - run.deltaWouldMoveRoot);
            if (gap > worstStructuralGap) {
                worstStructuralGap = gap;
                worstStructuralBone = rig.segmentByIndex.get(entry.getKey()).name;
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("### ").append(label).append(" ").append(fmt(degrees)).append(" deg\n\n");
        if (head.isEmpty()) {
            out.append("No head-region piece is simulated at all on this model.\n\n");
            return out.toString();
        }
        out.append("| bone | joint | pose tip | pose tip dy | drawn tip | drawn tip dy | ")
                .append("delta root | delta root dy | delta tip | delta tip dy | own swing | whole swing | ")
                .append("root lever | tip lever | delta would move root |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        int listed = 0;
        for (Segment segment : head) {
            if (listed++ >= TABLE_LIMIT) {
                break;
            }
            Run run = runs.get(segment.index);
            out.append("| `").append(segment.name).append("` | ").append(segment.joint)
                    .append(" | ").append(fmt(run.poseTip)).append(" | ").append(fmt(run.poseTipDy))
                    .append(" | ").append(fmt(run.drawnTip)).append(" | ").append(fmt(run.drawnTipDy))
                    .append(" | ").append(fmt(run.deltaRoot)).append(" | ").append(fmt(run.deltaRootDy))
                    .append(" | ").append(fmt(run.deltaTip)).append(" | ").append(fmt(run.deltaTipDy))
                    .append(" | ").append(fmt(run.ownDegrees)).append(" deg | ")
                    .append(fmt(run.wholeDegrees)).append(" deg | ").append(fmt(run.rootLever))
                    .append(" | ").append(fmt(run.tipLever)).append(" | ")
                    .append(fmt(run.deltaWouldMoveRoot)).append(" |\n");
        }
        if (head.size() > TABLE_LIMIT) {
            out.append("\n(").append(head.size() - TABLE_LIMIT)
                    .append(" further head piece(s) not listed)\n");
        }
        out.append("\n").append(moved).append(" of ").append(head.size())
                .append(" head pieces swing more than one degree under this pose; ")
                .append(rootMoved).append(" of them have their <b>root</b> moved more than 5 mm by ")
                .append("their own delta. Worst gap between the measured `delta root` and the ")
                .append("structural `2 L sin(a/2)`: ").append(fmt(worstStructuralGap))
                .append(" blocks (`").append(worstStructuralBone).append("`).\n\n");
        return out.toString();
    }

    /** Settle one segment (and, first, its ancestors) under one pitch, and record what it draws. */
    private static Run settle(Rig rig, Segment segment, float degrees, Map<Integer, Run> runs) {
        Run existing = runs.get(segment.index);
        if (existing != null) {
            return existing;
        }
        Run run = new Run();
        runs.put(segment.index, run);
        Segment parent = segment.parent >= 0 ? rig.segmentByIndex.get(segment.parent) : null;
        if (parent != null) {
            // Production resolves a segment's parent before the segment itself; the child's delta is
            // composed under the parent's, so the parent's run has to exist first.
            settle(rig, parent, degrees, runs);
        }
        OpenMatrix4f deformation = rig.deformationFor(segment.joint, degrees);
        Vector3f pivot = YsmMeshSecondaryMotion.transformPoint(deformation, segment.bindPivot,
                new Vector3f());
        Vector3f restDir = YsmMeshSecondaryMotion.transformDirection(deformation, segment.bindRest,
                new Vector3f());
        if (restDir.lengthSquared() < 1.0E-8F) {
            return run;
        }
        restDir.normalize();
        Quaternionf pivotDelta = new Quaternionf();
        YsmMeshSecondaryMotion.pivotDeltaOf(deformation, pivotDelta);

        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf swing = new Quaternionf();
        for (int step = 0; step < SETTLE_STEPS; step++) {
            YsmDynamicBoneSolver.INSTANCE.update(state,
                    YsmDynamicBoneSolver.GRAVITY,
                    YsmDynamicBoneSolver.AIR_DRAG,
                    segment.verticalFollow, new Vector3f(0.0F, -1.0F, 0.0F),
                    pivot, restDir, segment.lever,
                    (float) YsmPhysicsTuning.DEFAULTS.frequency(),
                    (float) YsmPhysicsTuning.DEFAULTS.dampingRatio(),
                    segment.mass, segment.maxAngle,
                    null, YsmDynamicBoneSolver.NO_COLLIDERS, segment.radius, null,
                    0.0F, 0.0F, DT, swing, pivotDelta);
        }
        float ownAngle = state.lastAngle;
        float used = parent == null ? 0.0F : runs.get(parent.index).chainUsed;
        float allowed = YsmPhysicsParts.chainAllowance(segment.maxAngle,
                YsmPhysicsParts.chainLimitFor(segment.jointsInPiece,
                        (float) YsmPhysicsTuning.DEFAULTS.maxAngle),
                used, segment.jointsLeft);
        if (ownAngle > allowed && ownAngle > 1.0E-4F) {
            swing.slerp(new Quaternionf(), 1.0F - allowed / ownAngle);
            ownAngle = allowed;
        }
        run.chainUsed = used + Math.max(0.0F, ownAngle);
        run.ownDegrees = (float) Math.toDegrees(ownAngle);

        // The delta exactly as the frame path builds it: the model-space swing conjugated into the
        // joint's frame, applied about the piece's own bind pivot, composed under the parent's.
        Quaternionf bind = new Quaternionf();
        YsmMeshSecondaryMotion.bindSwingOf(deformation, swing, bind);
        Matrix4f delta = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(segment.bindPivot, bind, delta);
        if (parent != null) {
            delta = new Matrix4f(runs.get(parent.index).delta).mul(delta);
        }
        run.delta = delta;
        run.wholeDegrees = (float) Math.toDegrees(rotationAngleOf(delta));

        Vector3f rootVertex = rig.rootVertex(segment);
        Vector3f tipVertex = rig.tipVertex(segment);
        measure(run, deformation, delta, rootVertex, tipVertex);
        run.rootLever = segment.bindPivot.distance(rootVertex);
        run.tipLever = segment.bindPivot.distance(tipVertex);
        // The most the delta can do to the root at all: a swing of `ownDegrees` about the bind pivot
        // moves a point `rootLever` away by 2 L sin(a/2), and by nothing when L is zero.
        run.deltaWouldMoveRoot = 2.0F * run.rootLever
                * (float) Math.sin(0.5F * Math.toRadians(run.ownDegrees));
        return run;
    }

    /**
     * What one piece's root and tip do: where the pose puts them, where the pose and the piece's own
     * delta put them, and - separately - how far the <b>delta alone</b> moves them.
     *
     * <p>The three are not the same measurement and conflating them is the mistake this method
     * exists to prevent: the distance from the bind position to the drawn position is the total a
     * viewer sees, while the distance <i>between</i> the posed position and the posed-and-swung one
     * is what the simulation did. Subtracting two distances from the bind pose would give a number
     * that is neither, and on a piece whose pose already carried it 0.12 blocks it would read as a
     * fraction of that rather than as the delta's own work.
     */
    private static void measure(Run run, OpenMatrix4f deformation, Matrix4f delta,
                                Vector3f rootVertex, Vector3f tipVertex) {
        Vector3f posedRoot = YsmMeshSecondaryMotion.transformPoint(deformation, rootVertex, new Vector3f());
        Vector3f drawnRoot = new Vector3f(posedRoot);
        delta.transformPosition(drawnRoot);
        Vector3f posedTip = YsmMeshSecondaryMotion.transformPoint(deformation, tipVertex, new Vector3f());
        Vector3f drawnTip = new Vector3f(posedTip);
        delta.transformPosition(drawnTip);

        run.poseRoot = posedRoot.distance(rootVertex);
        run.poseTip = posedTip.distance(tipVertex);
        run.drawnRoot = drawnRoot.distance(rootVertex);
        run.drawnTip = drawnTip.distance(tipVertex);
        run.deltaRoot = drawnRoot.distance(posedRoot);
        run.deltaTip = drawnTip.distance(posedTip);
        run.poseTipDy = posedTip.y - tipVertex.y;
        run.drawnTipDy = drawnTip.y - tipVertex.y;
        run.deltaTipDy = drawnTip.y - posedTip.y;
        run.drawnRootDy = drawnRoot.y - rootVertex.y;
        run.deltaRootDy = drawnRoot.y - posedRoot.y;
    }

    private static float rotationAngleOf(Matrix4f m) {
        float trace = m.m00() + m.m11() + m.m22();
        float cosine = Math.max(-1.0F, Math.min(1.0F, (trace - 1.0F) * 0.5F));
        float angle = (float) Math.acos(cosine);
        return Float.isFinite(angle) ? angle : 0.0F;
    }

    /** One segment settled under one pitch, and what it draws. */
    private static final class Run {
        float chainUsed;
        float ownDegrees;
        float wholeDegrees;
        float poseRoot, poseTip, drawnRoot, drawnTip, deltaRoot, deltaTip;
        float poseTipDy, drawnTipDy, deltaTipDy, drawnRootDy, deltaRootDy;
        float rootLever, tipLever, deltaWouldMoveRoot;
        Matrix4f delta = new Matrix4f();
    }

    // ------------------------------------------------------------------
    // The resting piece: the classification and the three candidate rules
    // ------------------------------------------------------------------

    /**
     * The most a resting piece may swing about its anchor under the <b>capped</b> rule, in degrees.
     *
     * <p>This is the one number in the round that is chosen rather than measured, and it is only
     * measured <i>as a candidate</i>: it is not shipped on the strength of this constant. It is set
     * where {@code 2 r sin(theta/2)} for the reported piece's own radius falls below the mesh's own
     * resolution: the cap's radius is about 0.25 blocks, and 2 * 0.25 * sin(5 deg) = 0.044 blocks.
     */
    private static final float RESTING_SWING_CAP_DEGREES = 10.0F;

    /** The pitches measured here: the reported frame's own regime (53 degrees), not the 15-45 band. */
    private static final float[] FAILING_PITCH_DEGREES = {50.0F, 55.0F, 60.0F};

    /** Model-space vertical, the frame the deltas act in - production's own direction. */
    private static final Vector3f DOWN = new Vector3f(0.0F, -1.0F, 0.0F);
    private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);

    /** The names the brief pins: they must keep swinging, whatever the classifier says elsewhere. */
    private static final String[] MUST_SWING_NAMES = {
            "LongHair", "LongHair2", "Bangs", "LeftSideHair", "RightSideHair", "LongRightHair",
            "LongRightHair2", "LongLeftHair", "LongLeftHair2", "Tail", "Tail2", "Tail3", "Tail4",
            "Tail5", "Tail6", "Tail7"};

    /** One piece's three deltas under one pitch, and what each of them draws. */
    private static final class RestingRun {
        float chainUsed;
        float ownDegrees;
        float parentSwing;
        boolean resting;
        final Matrix4f now = new Matrix4f();
        final Matrix4f rigid = new Matrix4f();
        final Matrix4f capped = new Matrix4f();
        /** The same swing about the piece's bind pivot instead of its anchor: the pre-anchor build. */
        final Matrix4f pivotHinged = new Matrix4f();
        float heldNow, heldRigid, heldCapped;
        float farNow, farRigid, farCapped;
        float maxNow, maxRigid, maxCapped;
        float heldPivot, farPivot;
    }

    /**
     * Settle one piece (and first its ancestors) under one pitch, and build the three candidate
     * deltas for it: the shipped one about its measured anchor, the resting-rigid one, and the capped
     * one. The solver, the allowance and the composition order are the production ones - see
     * {@link #settle} for the same three steps without the anchor.
     */
    private static RestingRun settleResting(Rig rig, Segment segment, float degrees,
                                            Map<Integer, RestingRun> runs) {
        RestingRun existing = runs.get(segment.index);
        if (existing != null) {
            return existing;
        }
        RestingRun run = new RestingRun();
        runs.put(segment.index, run);
        Segment parent = segment.parent >= 0 ? rig.segmentByIndex.get(segment.parent) : null;
        if (parent != null) {
            // The parent is resolved first: the child's delta is composed under the parent's.
            settleResting(rig, parent, degrees, runs);
        }
        OpenMatrix4f deformation = rig.deformationFor(segment.joint, degrees);
        Vector3f pivot = YsmMeshSecondaryMotion.transformPoint(deformation, segment.bindPivot,
                new Vector3f());
        Vector3f restDir = YsmMeshSecondaryMotion.transformDirection(deformation, segment.bindRest,
                new Vector3f());
        if (restDir.lengthSquared() < 1.0E-8F) {
            run.now.identity();
            run.rigid.identity();
            run.capped.identity();
            return run;
        }
        restDir.normalize();
        Quaternionf pivotDelta = new Quaternionf();
        YsmMeshSecondaryMotion.pivotDeltaOf(deformation, pivotDelta);

        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf swing = new Quaternionf();
        for (int step = 0; step < SETTLE_STEPS; step++) {
            YsmDynamicBoneSolver.INSTANCE.update(state,
                    YsmDynamicBoneSolver.GRAVITY,
                    YsmDynamicBoneSolver.AIR_DRAG,
                    segment.verticalFollow, DOWN,
                    pivot, restDir, segment.lever,
                    (float) YsmPhysicsTuning.DEFAULTS.frequency(),
                    (float) YsmPhysicsTuning.DEFAULTS.dampingRatio(),
                    segment.mass, segment.maxAngle,
                    null, YsmDynamicBoneSolver.NO_COLLIDERS, segment.radius, null,
                    0.0F, 0.0F, DT, swing, pivotDelta);
        }
        float ownAngle = state.lastAngle;
        float used = parent == null ? 0.0F : runs.get(parent.index).chainUsed;
        float allowed = YsmPhysicsParts.chainAllowance(segment.maxAngle,
                YsmPhysicsParts.chainLimitFor(segment.jointsInPiece,
                        (float) YsmPhysicsTuning.DEFAULTS.maxAngle),
                used, segment.jointsLeft);
        if (ownAngle > allowed && ownAngle > 1.0E-4F) {
            swing.slerp(new Quaternionf(), 1.0F - allowed / ownAngle);
            ownAngle = allowed;
        }
        run.chainUsed = used + Math.max(0.0F, ownAngle);
        run.ownDegrees = (float) Math.toDegrees(ownAngle);
        run.resting = restsOnItsSupport(segment);

        Quaternionf bind = new Quaternionf();
        YsmMeshSecondaryMotion.bindSwingOf(deformation, swing, bind);
        Matrix4f parentNow = parent == null ? new Matrix4f() : runs.get(parent.index).now;
        Matrix4f parentRigid = parent == null ? new Matrix4f() : runs.get(parent.index).rigid;
        Matrix4f parentCapped = parent == null ? new Matrix4f() : runs.get(parent.index).capped;
        run.parentSwing = (float) Math.toDegrees(rotationAngleOf(parentNow));

        Matrix4f own = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(segment.bindAnchor, bind, own);
        run.now.set(parentNow).mul(own);
        // The same swing about the piece's own bind pivot: what the build before the anchor round
        // drew, kept here so the round can say what the anchor removed and what it cannot.
        Matrix4f ownPivot = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(segment.bindPivot, bind, ownPivot);
        run.pivotHinged.set(parentNow).mul(ownPivot);
        // The resting rule: a piece carried by its support follows the pose exactly, so neither its
        // own swing nor a swinging parent's carry reaches it. Everything else is the shipped delta.
        if (run.resting) {
            run.rigid.identity();
        } else {
            run.rigid.set(run.now);
        }
        Quaternionf cappedBind = new Quaternionf(bind);
        if (run.ownDegrees > RESTING_SWING_CAP_DEGREES) {
            cappedBind.slerp(new Quaternionf(), 1.0F - RESTING_SWING_CAP_DEGREES / run.ownDegrees);
        }
        Matrix4f ownCapped = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(segment.bindAnchor, cappedBind, ownCapped);
        if (run.resting) {
            Matrix4f parentForCapped = parent == null ? new Matrix4f() : parentCapped;
            run.capped.set(parentForCapped).mul(ownCapped);
        } else {
            run.capped.set(run.now);
        }
        measureResting(rig, run, segment);
        return run;
    }

    /**
     * Does this piece rest on its support, structurally?
     *
     * <p>One comparison and no constant: the point the piece is <b>held</b> by (the shipped anchor,
     * the centre of its contact patch with the first ancestor above it that carries geometry) is
     * below the piece's own centre of mass. Gravity then presses the piece onto that contact - the
     * piece is <i>carried</i>, and turning it about the contact tips its far edge off. When the
     * contact is above the centre of mass the piece <i>hangs</i> from it and its own swing is the
     * pendulum the solver was written for.
     */
    private static boolean restsOnItsSupport(Segment segment) {
        if (segment.restsOn == null || segment.restsOn.isEmpty() || segment.own == null
                || segment.own.isEmpty()) {
            return false;
        }
        Vector3f centre = Rig.centroid(segment.own);
        return segment.bindAnchor.y < centre.y;
    }

    /** What each of the three deltas does to the piece: the slide at the held end, the far pull. */
    private static void measureResting(Rig rig, RestingRun run, Segment segment) {
        Vector3f root = rig.rootVertex(segment);
        run.heldNow = displacementOf(run.now, root);
        run.heldRigid = displacementOf(run.rigid, root);
        run.heldCapped = displacementOf(run.capped, root);
        run.farNow = farPull(run.now, segment.own, segment.restsOn);
        run.farRigid = farPull(run.rigid, segment.own, segment.restsOn);
        run.farCapped = farPull(run.capped, segment.own, segment.restsOn);
        run.maxNow = maxDisplacement(run.now, segment.own);
        run.maxRigid = maxDisplacement(run.rigid, segment.own);
        run.maxCapped = maxDisplacement(run.capped, segment.own);
        run.heldPivot = displacementOf(run.pivotHinged, root);
        run.farPivot = farPull(run.pivotHinged, segment.own, segment.restsOn);
    }

    /** How far a rigid delta moves one point. */
    private static float displacementOf(Matrix4f delta, Vector3f vertex) {
        Vector3f moved = delta.transformPosition(new Vector3f(vertex));
        return moved.distance(vertex);
    }

    /** The largest distance any of the piece's own vertices gains from the cloud it rests on. */
    private static float farPull(Matrix4f delta, List<Vector3f> own, List<Vector3f> restsOn) {
        if (own == null || own.isEmpty() || restsOn == null || restsOn.isEmpty()) {
            return Float.NaN;
        }
        float worst = Float.NEGATIVE_INFINITY;
        for (Vector3f vertex : own) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            float before = nearestDistance(vertex, restsOn);
            float after = nearestDistance(delta.transformPosition(new Vector3f(vertex)), restsOn);
            if (!Float.isFinite(before) || !Float.isFinite(after)) {
                continue;
            }
            worst = Math.max(worst, after - before);
        }
        return worst == Float.NEGATIVE_INFINITY ? Float.NaN : worst;
    }

    /** The largest distance a rigid delta moves any of the piece's own vertices. */
    private static float maxDisplacement(Matrix4f delta, List<Vector3f> own) {
        if (own == null || own.isEmpty()) {
            return Float.NaN;
        }
        float worst = 0.0F;
        for (Vector3f vertex : own) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            worst = Math.max(worst, displacementOf(delta, vertex));
        }
        return worst;
    }

    /** How far a point is from the nearest vertex of a cloud. */
    private static float nearestDistance(Vector3f point, List<Vector3f> cloud) {
        float best = Float.MAX_VALUE;
        for (Vector3f other : cloud) {
            if (other == null || !YsmDynamicBoneSolver.isFinite(other)) {
                continue;
            }
            best = Math.min(best, point.distance(other));
        }
        return best;
    }

    /** The angle between two directions, in degrees; 0 when either is degenerate. */
    private static float angleBetweenDegrees(Vector3f a, Vector3f b) {
        float lengthA = a.length();
        float lengthB = b.length();
        if (!(lengthA > 1.0E-8F) || !(lengthB > 1.0E-8F)) {
            return 0.0F;
        }
        float cosine = Math.max(-1.0F, Math.min(1.0F, a.dot(b) / (lengthA * lengthB)));
        return (float) Math.toDegrees(Math.acos(cosine));
    }

    // ------------------------------------------------------------------
    // The model, read from the deployed build's own converted artefacts
    // ------------------------------------------------------------------

    /** One physics segment as the shipped rules select it. */
    private static final class Segment {
        final int index;
        final int boneIndex;
        final String name;
        final int joint;
        final Vector3f bindPivot;
        final Vector3f bindRest;
        final float lever;
        final float radius;
        final float mass;
        final float maxAngle;
        final float verticalFollow;
        final int parent;
        final int jointsInPiece;
        final int jointsLeft;
        final boolean loggedSimulated;
        /** The piece's own geometry, mesh space - the same list production passes to the anchor. */
        final List<Vector3f> own;
        /** What it rests on: the first ancestor above it that carries geometry, or null. */
        final List<Vector3f> restsOn;
        /** The shipped hinge: {@code YsmPhysicsParts#contactAnchor(own, restsOn, pivot, lever)}. */
        final Vector3f bindAnchor;
        /** The bone the support geometry belongs to, '' when nothing above carries any. */
        final String supportName;
        /** Whether that bone is a piece the simulation moves - a chain link's own link above it. */
        final boolean supportSimulated;

        Segment(int index, int boneIndex, String name, int joint, Vector3f bindPivot, Vector3f bindRest,
                float lever, float radius, float mass, float maxAngle, float verticalFollow, int parent,
                int jointsInPiece, int jointsLeft, boolean loggedSimulated, List<Vector3f> own,
                List<Vector3f> restsOn, Vector3f bindAnchor, String supportName,
                boolean supportSimulated) {
            this.index = index;
            this.boneIndex = boneIndex;
            this.name = name;
            this.joint = joint;
            this.bindPivot = bindPivot;
            this.own = own;
            this.restsOn = restsOn;
            this.bindAnchor = bindAnchor;
            this.supportName = supportName;
            this.supportSimulated = supportSimulated;
            this.bindRest = bindRest;
            this.lever = lever;
            this.radius = radius;
            this.mass = mass;
            this.maxAngle = maxAngle;
            this.verticalFollow = verticalFollow;
            this.parent = parent;
            this.jointsInPiece = jointsInPiece;
            this.jointsLeft = jointsLeft;
            this.loggedSimulated = loggedSimulated;
        }
    }

    /** The model, its geometry, its segments and its armature. */
    private static final class Rig {
        final String label;
        final String modelId;
        final YSMRuntimeModel.BoneRt[] bones;
        final Map<String, Integer> boneIndex = new HashMap<>();
        final Map<Integer, List<Vector3f>> vertices = new HashMap<>();
        final Map<Integer, float[]> geometry = new HashMap<>();
        final Map<Integer, Vector3f> pivotCache = new HashMap<>();
        final List<Segment> segments = new ArrayList<>();
        final Map<Integer, Segment> segmentByIndex = new HashMap<>();
        final Map<Integer, Segment> segmentByBone = new HashMap<>();
        final Map<Integer, Joint> jointsById = new HashMap<>();
        final Map<Integer, Joint> referenceJointsById = new HashMap<>();
        final Map<Integer, OpenMatrix4f> restWorld = new HashMap<>();
        final Map<Integer, List<Vector3f>> ownGeometryAll = new HashMap<>();
        final Set<String> loggedSimulated = new HashSet<>();
        final Set<String> hidden = new HashSet<>();
        final Map<Integer, Vector3f> bindPivotMap = new HashMap<>();
        Joint referenceRigRoot;
        final float scaleX;
        final float scaleY;
        final Vector3f face;
        Joint rig;

        private Rig(String label, String modelId, YSMRuntimeModel.BoneRt[] bones, float scaleX,
                    float scaleY, Vector3f face) {
            this.label = label;
            this.modelId = modelId;
            this.bones = bones;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.face = face;
            for (int i = 0; i < bones.length; i++) {
                boneIndex.put(bones[i].name, i);
            }
        }

        static Rig load(Path pack, String stem, String loggedSimulatedCsv) throws IOException {
            Path runtimeFile = pack.resolve("ysm_runtime/entity").resolve(stem + ".json");
            Path meshFile = pack.resolve("animmodels/entity").resolve(stem + ".json");
            assertTrue(Files.isRegularFile(runtimeFile) && Files.isRegularFile(meshFile),
                    "the converted artefacts for '" + stem + "' are not in " + pack
                            + "; this probe measures the deployed build's own output and does not "
                            + "substitute the package for it");
            JsonObject runtime = JsonParser.parseString(
                    Files.readString(runtimeFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject mesh = JsonParser.parseString(
                    Files.readString(meshFile, StandardCharsets.UTF_8)).getAsJsonObject();

            JsonArray bonesJson = runtime.getAsJsonArray("bones");
            YSMRuntimeModel.BoneRt[] bones = new YSMRuntimeModel.BoneRt[bonesJson.size()];
            Map<String, Integer> indexOf = new HashMap<>();
            for (int i = 0; i < bones.length; i++) {
                JsonObject bone = bonesJson.get(i).getAsJsonObject();
                YSMRuntimeModel.BoneRt rt = new YSMRuntimeModel.BoneRt();
                rt.name = bone.get("name").getAsString();
                JsonArray pivot = bone.getAsJsonArray("pivot");
                rt.px = pivot.get(0).getAsFloat();
                rt.py = pivot.get(1).getAsFloat();
                rt.pz = pivot.get(2).getAsFloat();
                JsonArray rot = bone.getAsJsonArray("rot");
                rt.rx = rot.get(0).getAsFloat();
                rt.ry = rot.get(1).getAsFloat();
                rt.rz = rot.get(2).getAsFloat();
                rt.joint = bone.get("joint").getAsInt();
                rt.mapped = bone.has("mapped") && bone.get("mapped").getAsBoolean();
                bones[i] = rt;
                indexOf.put(rt.name, i);
            }
            // bindLocal = T(p) Rz Ry Rx T(-p), bindWorld = parent.bindWorld x bindLocal: the two
            // steps YSMRuntimeModel#computeBindLocal / #computeBindWorld take.
            for (int i = 0; i < bones.length; i++) {
                JsonObject json = bonesJson.get(i).getAsJsonObject();
                String parentName = json.has("parent") ? json.get("parent").getAsString() : "";
                Integer parent = parentName.isEmpty() ? null : indexOf.get(parentName);
                bones[i].parent = parent == null ? -1 : parent;
                bones[i].bindLocal.translation(bones[i].px, bones[i].py, bones[i].pz)
                        .rotateZ(bones[i].rz).rotateY(bones[i].ry).rotateX(bones[i].rx)
                        .translate(-bones[i].px, -bones[i].py, -bones[i].pz);
            }
            for (int i = 0; i < bones.length; i++) {
                bindWorld(bones, i, 0);
            }
            float scaleX = runtime.getAsJsonArray("scale").get(0).getAsFloat();
            float scaleY = runtime.getAsJsonArray("scale").get(1).getAsFloat();
            Rig rig = new Rig(stem, stem, bones, scaleX, scaleY, faceOf(runtime));

            // Own geometry: the mesh's y/<bone> parts minus the bones this model's own scripts hide
            // in its default form - the same set production's verticesByBone excludes, read through
            // production's own helper. The camera section is removed first because that helper
            // compiles the JSON it is handed and its own contract is that the JSON it reads has no
            // camera block yet (see its javadoc): with one, the compile reaches the RealCamera
            // bridge and the helper's catch-all answers "nothing is hidden", which is a silently
            // different model.
            JsonObject withoutCamera = runtime.deepCopy();
            withoutCamera.remove("camera");
            Set<String> hidden = YSMRuntimeModel.computeDefaultHiddenBoneNames(withoutCamera);
            rig.hidden.addAll(hidden);
            JsonObject verticesJson = mesh.getAsJsonObject("vertices");
            JsonArray positionsJson = verticesJson.getAsJsonObject("positions").getAsJsonArray("array");
            float[] positions = new float[positionsJson.size()];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = positionsJson.get(i).getAsFloat();
            }
            int ordinal = 0;
            Map<Integer, int[]> partOrdinals = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : verticesJson.getAsJsonObject("parts").entrySet()) {
                String partName = entry.getKey();
                int thisOrdinal = ordinal++;
                if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                    continue;
                }
                Integer bone = indexOf.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
                if (bone == null) {
                    continue;
                }
                JsonArray indices = entry.getValue().getAsJsonObject().getAsJsonArray("array");
                List<Vector3f> points = new ArrayList<>(indices.size());
                for (JsonElement index : indices) {
                    int at = index.getAsInt() * 3;
                    if (at + 2 >= positions.length) {
                        continue;
                    }
                    // The mesh JSON is the writer's Blender frame, scaled about the origin; the map
                    // back into the frame the physics reads is (x, y, z) -> (x, z, -y). Calibrated.
                    points.add(new Vector3f(positions[at], positions[at + 2], -positions[at + 1]));
                }
                // The same geometry with no bone hidden, kept beside the physics' own map because
                // that is what the documented containment count in
                // YsmPhysicsParts#pivotInMeshSpace was measured over. Two maps rather than one
                // because they answer two different questions.
                rig.ownGeometryAll.computeIfAbsent(bone, key -> new ArrayList<>()).addAll(points);
                if (hidden.contains(bones[bone].name)) {
                    continue;
                }
                rig.vertices.computeIfAbsent(bone, key -> new ArrayList<>()).addAll(points);
                int[] existing = partOrdinals.get(bone);
                int[] updated = existing == null ? new int[1]
                        : java.util.Arrays.copyOf(existing, existing.length + 1);
                updated[updated.length - 1] = thisOrdinal;
                partOrdinals.put(bone, updated);
            }
            for (Map.Entry<Integer, List<Vector3f>> entry : rig.vertices.entrySet()) {
                Vector3f centre = centroid(entry.getValue());
                rig.geometry.put(entry.getKey(),
                        new float[]{centre.x, centre.y, centre.z, entry.getValue().size()});
            }
            rig.buildSegments(partOrdinals, loggedSimulatedCsv);
            rig.buildArmature(runtime, mesh, hidden);
            return rig;
        }

        private static void bindWorld(YSMRuntimeModel.BoneRt[] bones, int i, int depth) {
            assertTrue(depth <= 512, "cyclic bone hierarchy in the converted runtime");
            YSMRuntimeModel.BoneRt bone = bones[i];
            if (bone.parent >= 0) {
                bindWorld(bones, bone.parent, depth + 1);
                bone.bindWorld.set(bones[bone.parent].bindWorld).mul(bone.bindLocal);
            } else {
                bone.bindWorld.set(bone.bindLocal);
            }
        }

        static Vector3f centroid(List<Vector3f> points) {
            Vector3f acc = new Vector3f();
            int used = 0;
            for (Vector3f point : points) {
                if (point == null || !YsmDynamicBoneSolver.isFinite(point)) {
                    continue;
                }
                acc.add(point);
                used++;
            }
            return used == 0 ? new Vector3f() : acc.div(used);
        }

        Vector3f pivot(int bone) {
            Vector3f cached = pivotCache.get(bone);
            if (cached != null) {
                return cached;
            }
            YSMRuntimeModel.BoneRt rt = bones[bone];
            Vector3f value = YsmPhysicsParts.pivotInMeshSpace(rt.bindWorld, rt.px, rt.py, rt.pz,
                    scaleX, scaleY);
            pivotCache.put(bone, value);
            return value;
        }

        /** The same pivot from an arbitrary local point, for the corner-turned calibration. */
        Vector3f pivotOfLocal(int bone, Vector3f local) {
            Vector3f value = new Vector3f(local);
            bones[bone].bindWorld.transformPosition(value);
            return value.mul(scaleX, scaleY, scaleX);
        }

        /** The segment list, assembled through the same production seams `build` uses. */
        private void buildSegments(Map<Integer, int[]> partOrdinals, String loggedSimulatedCsv) {
            for (String name : loggedSimulatedCsv.split(",")) {
                loggedSimulated.add(name.trim());
            }
            java.util.function.IntPredicate ownsGeometry =
                    index -> YsmPhysicsParts.ownsItsGeometry(index, geometry, partOrdinals);
            List<Integer> selected = YsmPhysicsParts.selectBones(bones, ownsGeometry,
                    YsmPhysicsTuning.maxChains(), new int[1]);
            List<Integer> drafts = new ArrayList<>();
            for (int bone : selected) {
                // The three tests buildSegment applies, then the two pivot rules, in its order.
                if (bones[bone].joint < 0 || YsmPhysicsParts.poseBelongsToEpicFight(bones[bone])) {
                    continue;
                }
                Vector3f pivot = pivot(bone);
                List<Vector3f> own = vertices.get(bone);
                Vector3f centre = own == null ? null : centroid(own);
                if (pivot == null || centre == null) {
                    continue;
                }
                Vector3f rest = new Vector3f(centre).sub(pivot);
                float lever = rest.length();
                if (!Float.isFinite(lever) || lever < 0.01F) {
                    continue;
                }
                if (YsmPhysicsParts.wrapsPivot(own, pivot)
                        || YsmPhysicsParts.risesOffPivot(own, pivot, rest, lever)) {
                    continue;
                }
                drafts.add(bone);
            }
            Map<Integer, Integer> segmentOfBone = new HashMap<>();
            for (int i = 0; i < drafts.size(); i++) {
                segmentOfBone.put(drafts.get(i), i);
            }
            // The parent link resolves to the nearest SURVIVING segment's bone, and the allowance is
            // computed from the links that survived - see YsmPhysicsParts#resolveParent and
            // YsmMeshSecondaryMotion#piecesOf.
            int[] parentOf = new int[drafts.size()];
            for (int i = 0; i < drafts.size(); i++) {
                Integer parentBone = nearestSimulatedAncestor(drafts.get(i), segmentOfBone);
                parentOf[i] = parentBone == null ? -1 : segmentOfBone.get(parentBone);
            }
            int[] size = new int[drafts.size()];
            int[] depth = new int[drafts.size()];
            for (int i = 0; i < drafts.size(); i++) {
                int top = i;
                int guard = 0;
                for (int parent = parentOf[i];
                     parent >= 0 && parent != top && guard++ <= drafts.size();
                     parent = parentOf[parent]) {
                    top = parent;
                    depth[i]++;
                }
                size[top]++;
            }
            for (int i = 0; i < drafts.size(); i++) {
                int bone = drafts.get(i);
                YSMRuntimeModel.BoneRt rt = bones[bone];
                List<Vector3f> own = vertices.get(bone);
                Vector3f pivot = pivot(bone);
                Vector3f rest = new Vector3f(centroid(own)).sub(pivot);
                float lever = rest.length();
                int parent = parentOf[i];
                YsmPhysicsParts.Category category = YsmPhysicsParts.classifyBone(bones, bone);
                int root = i;
                for (int at = i, guard = 0; parentOf[at] >= 0 && guard++ <= drafts.size(); at = parentOf[at]) {
                    root = parentOf[at];
                }
                int inPiece = Math.max(1, size[root]);
                // The shipped hinge, measured the way production measures it: the same call, the same
                // two clouds, the same bounds (see YsmPhysicsParts#buildSegment lines 1779-1780).
                List<Vector3f> restsOn = YsmPhysicsParts.restsOnGeometry(bones, bone, vertices);
                Vector3f bindAnchor = YsmPhysicsParts.contactAnchor(own, restsOn, pivot, lever);
                // Which bone that support geometry belongs to, and whether that bone is itself one of
                // the pieces the simulation moves: a chain link's support is the link above it.
                int supportBone = -1;
                for (int at = bones[bone].parent, guard = 0;
                     at >= 0 && guard++ <= bones.length; at = bones[at].parent) {
                    List<Vector3f> candidate = vertices.get(at);
                    if (candidate != null && !candidate.isEmpty()) {
                        supportBone = at;
                        break;
                    }
                }
                Segment segment = new Segment(i, bone, rt.name, rt.joint, pivot, rest, lever,
                        YsmPhysicsParts.radiusFor(own, pivot, rest),
                        Math.max(0.25F, Math.min(4.0F, own.size() / 64.0F)),
                        YsmPhysicsParts.swingLimit(parent >= 0, rt.joint == JointTable.TORSO,
                                (float) YsmPhysicsTuning.DEFAULTS.maxAngle,
                                (float) YsmPhysicsTuning.DEFAULTS.maxAngleRoot),
                        category.weight * (float) YsmPhysicsTuning.gravityFollowScale(),
                        parent, inPiece, Math.max(1, inPiece - depth[i]),
                        loggedSimulated.contains(rt.name), own, restsOn, bindAnchor,
                        supportBone < 0 ? "" : bones[supportBone].name,
                        supportBone >= 0 && segmentOfBone.containsKey(supportBone));
                segments.add(segment);
                segmentByIndex.put(i, segment);
                segmentByBone.put(bone, segment);
            }
        }

        private Integer nearestSimulatedAncestor(int bone, Map<Integer, Integer> segmentOfBone) {
            int guard = 0;
            for (int parent = bones[bone].parent;
                 parent >= 0 && guard++ <= bones.length; parent = bones[parent].parent) {
                if (segmentOfBone.containsKey(parent)) {
                    return parent;
                }
            }
            return null;
        }

        /**
         * The armature: the reference biped's hierarchy with this model's computed joint pivots,
         * which is what {@code YsmBindArmature} builds (it copies the reference's rotations and
         * replaces the translations with the pivots its filter settles).
         */
        private void buildArmature(JsonObject runtime, JsonObject mesh, Set<String> hidden)
                throws IOException {
            YsmBindArmature.GeometryInput input = geometryInput(modelId, runtime, mesh, hidden);
            List<String> warnings = new ArrayList<>();
            YsmBindArmature.GeometryData data = YsmBindArmature.collectGeometry(input, warnings::add);
            YsmBindArmature.BindPivots pivots = YsmBindArmature.computePivots(input, data, warnings::add);
            bindPivotMap.putAll(pivots.byJoint());
            // Epic Fight's own biped, untouched: the client's `EF biped rest ...` columns are about
            // this rig, not about the model's, and it is the reading of the bundled biped.json that
            // a row-major / column-major mix-up shows up in.
            referenceRigRoot = referenceBiped();
            referenceRigRoot.initOriginTransform(new OpenMatrix4f());
            collectJoints(referenceRigRoot, referenceJointsById);
            rig = copyHierarchy(referenceBiped(), new OpenMatrix4f(), pivots.byJoint(), true);
            rig.initOriginTransform(new OpenMatrix4f());
            collectJoints(rig);
            walkWorlds(rig, new OpenMatrix4f());
        }

        private void collectJoints(Joint joint) {
            collectJoints(joint, jointsById);
        }

        private static void collectJoints(Joint joint, Map<Integer, Joint> into) {
            into.put(joint.getId(), joint);
            for (Joint child : joint.getSubJoints()) {
                collectJoints(child, into);
            }
        }

        private void walkWorlds(Joint joint, OpenMatrix4f parentWorld) {
            OpenMatrix4f world = OpenMatrix4f.mul(parentWorld, joint.getLocalTransform(), null);
            restWorld.put(joint.getId(), world);
            for (Joint child : joint.getSubJoints()) {
                walkWorlds(child, world);
            }
        }

        Vector3f jointOrigin(int joint) {
            OpenMatrix4f world = restWorld.get(joint);
            return world == null ? null : new Vector3f(world.m30, world.m31, world.m32);
        }

        /** The deformation a pitch of {@code degrees} about {@code joint}'s own origin produces. */
        OpenMatrix4f deformationFor(int joint, float degrees) {
            if (joint != JointTable.HEAD || degrees == 0.0F) {
                return new OpenMatrix4f();
            }
            Vector3f origin = jointOrigin(JointTable.HEAD);
            Matrix4f matrix = new Matrix4f()
                    .translate(origin.x, origin.y, origin.z)
                    .rotateX((float) Math.toRadians(degrees))
                    .translate(-origin.x, -origin.y, -origin.z);
            return toOpen(matrix);
        }

        /** How much an x rotation moves the face normal's height, per degree. */
        float pitchSign() {
            float degrees = 10.0F;
            Vector3f moved = YsmMeshSecondaryMotion.transformDirection(
                    deformationFor(JointTable.HEAD, degrees), face, new Vector3f());
            return (moved.y - face.y) / degrees;
        }

        /** The vertex a piece is attached by: nearest its parent's pivot, else nearest the joint. */
        Vector3f rootVertex(Segment segment) {
            Segment parent = segment.parent >= 0 ? segmentByIndex.get(segment.parent) : null;
            Vector3f anchor = parent != null ? parent.bindPivot : jointOrigin(segment.joint);
            List<Vector3f> own = vertices.get(segment.boneIndex);
            Vector3f best = null;
            float bestDistance = Float.MAX_VALUE;
            if (anchor != null && own != null) {
                for (Vector3f vertex : own) {
                    float distance = vertex.distance(anchor);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = vertex;
                    }
                }
            }
            return best == null ? new Vector3f(segment.bindPivot) : best;
        }

        /** The vertex farthest from the piece's own bind pivot: its free end. */
        Vector3f tipVertex(Segment segment) {
            List<Vector3f> own = vertices.get(segment.boneIndex);
            Vector3f best = null;
            float bestDistance = -1.0F;
            if (own != null) {
                for (Vector3f vertex : own) {
                    float distance = vertex.distance(segment.bindPivot);
                    if (distance > bestDistance) {
                        bestDistance = distance;
                        best = vertex;
                    }
                }
            }
            return best == null ? new Vector3f(segment.bindPivot) : best;
        }

        /** Every simulated piece on the Chest or the Head. */
        List<Segment> headSegments() {
            List<Segment> out = new ArrayList<>();
            for (Segment segment : segments) {
                if (segment.joint == JointTable.HEAD || segment.joint == JointTable.CHEST) {
                    out.add(segment);
                }
            }
            return out;
        }

        /** The simulated piece of this name, or null when the model has none. */
        Segment segmentNamed(String name) {
            for (Segment segment : segments) {
                if (segment.name.equals(name)) {
                    return segment;
                }
            }
            return null;
        }

        /** The three rules' numbers for one piece at one pitch, for the test's own assertions. */
        RestingRun restingRun(Segment segment, float degrees) {
            Map<Integer, RestingRun> runs = new LinkedHashMap<>();
            settleResting(this, segment, degrees, runs);
            return runs.get(segment.index);
        }

        /**
         * The classification table for every simulated piece of this model, and the three candidate
         * rules at the failing frame's own pitches, both signs.
         *
         * <p>See {@link #settleResting} for what the rules are and {@link #restsOnItsSupport} for the
         * comparison the classification is. Everything here is measured on the deployed build's own
         * converted artefacts, with the production solver.
         */
        String restingReport() {
            StringBuilder out = new StringBuilder();
            out.append("## What each piece contacts, and which way gravity presses it\n\n")
                    .append("`anchor` is the shipped hinge's distance from the bind pivot, blocks; ")
                    .append("`gap` is the nearest approach of the piece's own geometry to the first ")
                    .append("ancestor above it that carries any; `patch` is how many of its own ")
                    .append("vertices lie within `CONTACT_PATCH_TOLERANCE` of that approach; `press` ")
                    .append("is the angle between the model's own downward direction and the line from ")
                    .append("the piece's centre of mass to that hinge - **0 is a piece resting on its ")
                    .append("support, 180 a piece hanging from it**, and the rule this round measures ")
                    .append("is the sign of it (`press` under 90). `below` is the share of the ")
                    .append("piece's own vertices under the hinge; `normal` is the angle between the ")
                    .append("support's outward normal at the contact and the world's up. `support` is ")
                    .append("the bone that geometry belongs to, and `simulated` whether that bone is ")
                    .append("itself one of the pieces the simulation moves - **a chain link's support ")
                    .append("is the link above it, while a piece carried by the body rests on a bone ")
                    .append("the simulation does not move**.\n\n")
                    .append("| bone | joint | anchor | gap | patch | press | below | normal | support | simulated | verdict |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
            int resting = 0;
            int hanging = 0;
            int noSupport = 0;
            for (Segment segment : segments) {
                Vector3f centre = centroid(segment.own);
                Vector3f toAnchor = new Vector3f(segment.bindAnchor).sub(centre);
                float gap = Float.MAX_VALUE;
                if (segment.restsOn != null && segment.own != null) {
                    for (Vector3f vertex : segment.own) {
                        gap = Math.min(gap, nearestDistance(vertex, segment.restsOn));
                    }
                }
                boolean hasSupport = Float.isFinite(gap) && gap != Float.MAX_VALUE;
                int patch = 0;
                int below = 0;
                if (hasSupport) {
                    for (Vector3f vertex : segment.own) {
                        if (nearestDistance(vertex, segment.restsOn)
                                <= gap + YsmPhysicsParts.CONTACT_PATCH_TOLERANCE) {
                            patch++;
                        }
                        if (vertex.y <= segment.bindAnchor.y) {
                            below++;
                        }
                    }
                }
                float normal = Float.NaN;
                if (hasSupport) {
                    Vector3f nearestRest = null;
                    float best = Float.MAX_VALUE;
                    for (Vector3f other : segment.restsOn) {
                        float distance = segment.bindAnchor.distance(other);
                        if (distance < best) {
                            best = distance;
                            nearestRest = other;
                        }
                    }
                    if (nearestRest != null) {
                        normal = angleBetweenDegrees(new Vector3f(segment.bindAnchor).sub(nearestRest),
                                UP);
                    }
                }
                boolean isResting = restsOnItsSupport(segment);
                if (!hasSupport) {
                    noSupport++;
                } else if (isResting) {
                    resting++;
                } else {
                    hanging++;
                }
                out.append("| `").append(segment.name).append("` | ").append(segment.joint)
                        .append(" | ").append(fmt(segment.bindAnchor.distance(segment.bindPivot)))
                        .append(" | ").append(hasSupport ? fmt(gap) : "none")
                        .append(" | ").append(hasSupport ? Integer.toString(patch) : "-")
                        .append(" | ").append(hasSupport ? fmt(angleBetweenDegrees(toAnchor, DOWN)) : "-")
                        .append(" | ").append(hasSupport && segment.own != null
                                ? fmt((float) below / Math.max(1, segment.own.size())) : "-")
                        .append(" | ").append(Float.isFinite(normal) ? fmt(normal) : "-")
                        .append(" | `").append(segment.supportName.isEmpty() ? "-" : segment.supportName)
                        .append("` | ").append(segment.supportName.isEmpty() ? "-"
                                : (segment.supportSimulated ? "yes" : "no"))
                        .append(" | ").append(!hasSupport ? "no support"
                                : (isResting ? "**resting**" : "hanging"))
                        .append(" |\n");
            }
            out.append("\n").append(resting).append(" resting, ").append(hanging).append(" hanging, ")
                    .append(noSupport).append(" with no geometry above them, of ")
                    .append(segments.size()).append(" simulated piece(s) on `").append(modelId)
                    .append("`.\n\n");

            float perDegree = pitchSign();
            for (float degrees : FAILING_PITCH_DEGREES) {
                out.append(pitchRestingSection(degrees * (perDegree > 0.0F ? 1.0F : -1.0F), "look up"));
                out.append(pitchRestingSection(-degrees * (perDegree > 0.0F ? 1.0F : -1.0F), "look down"));
            }
            return out.toString();
        }

        /** One pitch, one sign: every simulated piece of the model under the three rules. */
        private String pitchRestingSection(float degrees, String label) {
            Map<Integer, RestingRun> runs = new LinkedHashMap<>();
            for (Segment segment : segments) {
                settleResting(this, segment, degrees, runs);
            }
            StringBuilder out = new StringBuilder();
            out.append("### ").append(label).append(" ").append(fmt(degrees)).append(" deg\n\n")
                    .append("`own swing` is the solver's settled angle for the piece and `parent ")
                    .append("swing` the composed turn its parent's delta already applies to it. Each ")
                    .append("rule then reports, in blocks, the **held shift** (how far the delta moves ")
                    .append("the vertex the piece is held by, i.e. the slide) and the **far pull** (the ")
                    .append("most any of its own vertices is moved away from the geometry it rests ")
                    .append("on, i.e. the lift-off), and `max` the largest displacement of any vertex.\n\n")
                    .append("| bone | resting | own swing | parent swing | now held | now far | now max "
                            + "| rigid held | rigid far | rigid max | capped held | capped far | capped max |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (Segment segment : segments) {
                RestingRun run = runs.get(segment.index);
                if (run == null) {
                    continue;
                }
                out.append("| `").append(segment.name).append("` | ")
                        .append(run.resting ? "**yes**" : "no")
                        .append(" | ").append(fmt(run.ownDegrees))
                        .append(" | ").append(fmt(run.parentSwing))
                        .append(" | ").append(fmt(run.heldNow)).append(" | ").append(fmt(run.farNow))
                        .append(" | ").append(fmt(run.maxNow))
                        .append(" | ").append(fmt(run.heldRigid)).append(" | ").append(fmt(run.farRigid))
                        .append(" | ").append(fmt(run.maxRigid))
                        .append(" | ").append(fmt(run.heldCapped)).append(" | ").append(fmt(run.farCapped))
                        .append(" | ").append(fmt(run.maxCapped))
                        .append(" |\n");
            }
            Segment cap = segmentNamed("BaseHair");
            RestingRun capRun = cap == null ? null : runs.get(cap.index);
            if (capRun != null) {
                out.append("\nThe cap (`BaseHair`) at this pitch: own swing ").append(fmt(capRun.ownDegrees))
                        .append(" deg; held shift ").append(fmt(capRun.heldNow))
                        .append(" and far pull ").append(fmt(capRun.farNow)).append(" now, ")
                        .append(fmt(capRun.heldRigid)).append(" / ").append(fmt(capRun.farRigid))
                        .append(" rigid, ").append(fmt(capRun.heldCapped)).append(" / ")
                        .append(fmt(capRun.farCapped)).append(" capped at ")
                        .append(fmt(RESTING_SWING_CAP_DEGREES)).append(" deg; with the same swing about ")
                        .append("its own pivot instead (the build before the anchor round): held shift ")
                        .append(fmt(capRun.heldPivot)).append(", far pull ").append(fmt(capRun.farPivot))
                        .append(".\n\n");
            }
            List<String> wouldStop = new ArrayList<>();
            List<String> wouldStopOnBody = new ArrayList<>();
            List<String> mustSwingResting = new ArrayList<>();
            List<String> mustSwingOnBody = new ArrayList<>();
            for (Segment segment : segments) {
                RestingRun run = runs.get(segment.index);
                if (run == null || !run.resting) {
                    continue;
                }
                if (run.ownDegrees > 1.0F) {
                    wouldStop.add(segment.name + " (" + fmt(run.ownDegrees) + " deg)");
                    if (!segment.supportSimulated) {
                        wouldStopOnBody.add(segment.name + " (" + fmt(run.ownDegrees) + " deg)");
                    }
                }
                for (String must : MUST_SWING_NAMES) {
                    if (segment.name.equals(must)) {
                        mustSwingResting.add(must);
                        if (!segment.supportSimulated) {
                            mustSwingOnBody.add(must);
                        }
                    }
                }
            }
            out.append("resting pieces that would stop swinging: ").append(wouldStop.size())
                    .append(wouldStop.isEmpty() ? "" : " " + wouldStop)
                    .append("; of the pieces the brief names as must-keep-swinging, ")
                    .append(mustSwingResting.size()).append(" are classified resting")
                    .append(mustSwingResting.isEmpty() ? "" : " " + mustSwingResting)
                    .append(". Narrowed to pieces resting on a bone the simulation does not move: ")
                    .append(wouldStopOnBody.size()).append(wouldStopOnBody.isEmpty() ? ""
                            : " " + wouldStopOnBody)
                    .append(" would stop, of which must-keep-swinging: ").append(mustSwingOnBody.size())
                    .append(mustSwingOnBody.isEmpty() ? "" : " " + mustSwingOnBody).append(".\n\n");
            return out.toString();
        }

        /**
         * What the <b>direction-independent containment</b> rule would do to this model: which of the
         * pieces the client actually simulated have a pivot that is not on their own geometry, and
         * would therefore stop swinging.
         *
         * <p>This is the candidate rule's own blast radius on the two models the brief names, measured
         * with the reader calibrated above and against the client's own simulated list - not inferred
         * from the corpus.
         */
        String containmentReport() {
            List<String> droppedSimulated = new ArrayList<>();
            List<String> droppedRigid = new ArrayList<>();
            int pieces = 0;
            for (int bone = 0; bone < bones.length; bone++) {
                List<Vector3f> own = vertices.get(bone);
                if (own == null || own.size() < 4 || bones[bone].joint < 0) {
                    continue;
                }
                Vector3f pivot = pivot(bone);
                if (pivot == null || YsmPhysicsParts.pivotOnGeometry(own, pivot)) {
                    continue;
                }
                pieces++;
                boolean simulated = segmentByBone.containsKey(bone);
                if (simulated) {
                    droppedSimulated.add("| `" + bones[bone].name + "` | "
                            + fmt((float) YsmPhysicsParts.pivotGapFromGeometry(own, pivot)) + " | "
                            + fmt(restUpShare(bone)) + " |");
                } else {
                    droppedRigid.add(bones[bone].name);
                }
            }
            StringBuilder out = new StringBuilder();
            out.append("## What the direction-independent containment rule would do to this model\n\n")
                    .append("Every bone with geometry on a joint whose pivot is not on that geometry - ")
                    .append("whether or not it hangs down, which is the whole difference from the ")
                    .append("shipped rule.\n\n")
                    .append("- pieces with a pivot off their own geometry: **").append(pieces)
                    .append("**\n")
                    .append("- **of them, simulated today: ").append(droppedSimulated.size())
                    .append("** (the client logged ").append(loggedSimulated.size())
                    .append(" simulated bones for this model)\n")
                    .append("- of them, already rigid: ").append(droppedRigid.size()).append("\n\n");
            if (!droppedSimulated.isEmpty()) {
                out.append("| piece that would stop swinging | pivot gap | up-share |\n|---|---|---|\n");
                for (String row : droppedSimulated) {
                    out.append(row).append('\n');
                }
                out.append('\n');
            }
            return out.toString();
        }

        /** The up-component of a piece's unit rest direction, for the report above. */
        private float restUpShare(int bone) {
            List<Vector3f> own = vertices.get(bone);
            Vector3f pivot = pivot(bone);
            if (own == null || pivot == null) {
                return Float.NaN;
            }
            Vector3f rest = new Vector3f(centroid(own)).sub(pivot);
            float lever = rest.length();
            return lever > 0.0F ? rest.y / lever : Float.NaN;
        }

        /** The pieces of the head that the shipped rules keep, as a markdown table. */
        String headReport() {
            StringBuilder out = new StringBuilder();
            out.append("## The head region of `").append(label).append("`\n\n")
                    .append("Every simulated piece on joint ").append(JointTable.CHEST)
                    .append(" (Chest) or ").append(JointTable.HEAD)
                    .append(" (Head). `up-share` is the up-component of the unit rest direction - ")
                    .append("what the shipped rule reads (margin ")
                    .append(fmt(YsmPhysicsParts.risesFromPivotMargin()))
                    .append("); `root lever` and `tip lever` are the distances from the piece's own ")
                    .append("bind pivot to the vertex it is attached by and to its free end, which ")
                    .append("are what decide whether its delta hinges it or sweeps it.\n\n")
                    .append("| bone | joint | pivot | own geom y | lever | root lever | tip lever | ")
                    .append("rest from down | up-share | follow | client logged it |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
            int listed = 0;
            for (Segment segment : headSegments()) {
                if (listed++ >= TABLE_LIMIT) {
                    break;
                }
                Vector3f centre = centroid(vertices.get(segment.boneIndex));
                float upShare = segment.bindRest.y / Math.max(1.0E-9F, segment.lever);
                double fromDown = Math.toDegrees(Math.acos(Math.max(-1.0,
                        Math.min(1.0, -segment.bindRest.y / Math.max(1.0E-9F, segment.lever)))));
                out.append("| `").append(segment.name).append("` | ").append(segment.joint)
                        .append(" | ").append(point(segment.bindPivot))
                        .append(" | ").append(fmt(YsmPhysicsParts.minY(vertices.get(segment.boneIndex))))
                        .append("..").append(fmt(YsmPhysicsParts.maxY(vertices.get(segment.boneIndex))))
                        .append(" | ").append(fmt(segment.lever))
                        .append(" | ").append(fmt(segment.bindPivot.distance(rootVertex(segment))))
                        .append(" | ").append(fmt(segment.bindPivot.distance(tipVertex(segment))))
                        .append(" | ").append(fmt((float) fromDown)).append(" deg")
                        .append(" | ").append(fmt(upShare))
                        .append(" | ").append(fmt(segment.verticalFollow))
                        .append(" | ").append(segment.loggedSimulated ? "yes" : "no")
                        .append(" |\n");
                if (centre == null) {
                    out.append('\n');
                }
            }
            if (headSegments().size() > TABLE_LIMIT) {
                out.append("\n(").append(headSegments().size() - TABLE_LIMIT)
                        .append(" further head piece(s) not listed)\n");
            }
            out.append("\nThe client logged ").append(loggedSimulated.size())
                    .append(" simulated bones for this model; this reader's list has ")
                    .append(segments.size()).append(" in all, of which ").append(headSegments().size())
                    .append(" are on the Chest or the Head.\n\n");
            return out.toString();
        }

        /**
         * The pieces of the head region whose pivot is <b>not on their own geometry</b>, whether or
         * not the shipped rules drop them. This is the table the direction-independent containment
         * rule is about.
         */
        String offPivotReport() {
            StringBuilder out = new StringBuilder();
            out.append("## Head-region pieces whose pivot is off their own geometry\n\n")
                    .append("`gap` is the distance from the pivot to the piece's own bounding box; ")
                    .append("`simulated today` is what the shipped pair of rules decides, and ")
                    .append("`client logged it` is what the running client actually simulated.\n\n")
                    .append("| bone | joint | gap | root lever | lever | up-share | rest from down | ")
                    .append("simulated today | client logged it |\n")
                    .append("|---|---|---|---|---|---|---|---|---|\n");
            int listed = 0;
            for (int bone = 0; bone < bones.length; bone++) {
                List<Vector3f> own = vertices.get(bone);
                if (own == null || own.isEmpty() || bones[bone].joint < 0) {
                    continue;
                }
                if (bones[bone].joint != JointTable.HEAD && bones[bone].joint != JointTable.CHEST) {
                    continue;
                }
                Vector3f pivot = pivot(bone);
                if (pivot == null) {
                    continue;
                }
                double gap = YsmPhysicsParts.pivotGapFromGeometry(own, pivot);
                if (!(gap > 0.005D)) {
                    continue;
                }
                if (listed++ >= TABLE_LIMIT) {
                    break;
                }
                Vector3f centre = centroid(own);
                Vector3f rest = new Vector3f(centre).sub(pivot);
                float lever = rest.length();
                Segment segment = segmentByBone.get(bone);
                float rootLever = segment == null ? Float.NaN : segment.bindPivot.distance(rootVertex(segment));
                out.append("| `").append(bones[bone].name).append("` | ").append(bones[bone].joint)
                        .append(" | ").append(fmt((float) gap))
                        .append(" | ").append(fmt(rootLever))
                        .append(" | ").append(fmt(lever))
                        .append(" | ").append(fmt(lever > 0.0F ? rest.y / lever : Float.NaN))
                        .append(" | ").append(fmt((float) Math.toDegrees(Math.acos(Math.max(-1.0,
                                Math.min(1.0, -rest.y / Math.max(1.0E-9F, lever)))))))
                        .append(" deg | ").append(segment != null ? "**yes**" : "no")
                        .append(" | ").append(loggedSimulated.contains(bones[bone].name) ? "yes" : "no")
                        .append(" |\n");
            }
            if (listed == 0) {
                out.append("| (none) | | | | | | | | |\n");
            }
            out.append('\n');
            return out.toString();
        }
    }

    // ------------------------------------------------------------------
    // The helpers the armature needs, mirroring YsmBindArmature's own
    // ------------------------------------------------------------------

    /** The bundled Epic Fight biped, as {@code YsmBindArmature} copies it for every model. */
    private static Joint referenceBiped() throws IOException {
        String text;
        try (InputStream stream = HeadRegionPitchProbeTest.class.getResourceAsStream("/epicfight/biped.json")) {
            assertTrue(stream != null, "the bundled Epic Fight biped.json is not on the test classpath");
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject armature = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("armature");
        JsonArray joints = armature.getAsJsonArray("joints");
        Map<String, Integer> idOf = new HashMap<>();
        for (int i = 0; i < joints.size(); i++) {
            idOf.put(joints.get(i).getAsString(), i);
        }
        return buildReference(armature.getAsJsonArray("hierarchy").get(0).getAsJsonObject(), idOf);
    }

    private static Joint buildReference(JsonObject node, Map<String, Integer> idOf) {
        Joint joint = new Joint(node.get("name").getAsString(), idOf.get(node.get("name").getAsString()),
                matrixOf(node.getAsJsonArray("transform")));
        for (JsonElement child : node.getAsJsonArray("children")) {
            joint.addSubJoints(buildReference(child.getAsJsonObject(), idOf));
        }
        return joint;
    }

    /** {@code YsmBindArmature#copyHierarchy}: the reference's rotations, this model's pivots. */
    private static Joint copyHierarchy(Joint reference, OpenMatrix4f parentWorld,
                                       Map<Integer, Vector3f> pivots, boolean root) {
        OpenMatrix4f local = new OpenMatrix4f(reference.getLocalTransform());
        Vector3f pivot = pivots.get(reference.getId());
        if (pivot != null) {
            if (root) {
                local.m30 = pivot.x;
                local.m31 = pivot.y;
                local.m32 = pivot.z;
            } else {
                Vector3f offset = inversePoint(parentWorld, pivot);
                local.m30 = offset.x;
                local.m31 = offset.y;
                local.m32 = offset.z;
            }
        }
        Joint joint = new Joint(reference.getName(), reference.getId(), local);
        OpenMatrix4f world = OpenMatrix4f.mul(parentWorld, local, new OpenMatrix4f());
        for (Joint child : reference.getSubJoints()) {
            joint.addSubJoints(copyHierarchy(child, world, pivots, false));
        }
        return joint;
    }

    /**
     * A point carried into a rigid transform's own frame: {@code world^-1 x point}.
     *
     * <p>Written out rather than taken from {@code OpenMatrix4f#invert}, and the reason is worth
     * recording, because it is a convention question and not an arithmetic one. The transform is read
     * the way the production frame path reads one ({@code YsmMeshSecondaryMotion#transformPoint}:
     * {@code x' = m00 x + m10 y + m20 z + m30}), so its rotation is the 3x3 with
     * {@code R[row][col] = m<col><row>} and its translation is {@code (m30, m31, m32)}; the inverse of
     * such a transform is the transpose of that rotation with translation {@code -R^T t}. The
     * armature this rebuilds is calibrated against the pivot map the client logged, joint by joint,
     * so an inverse in the other convention would show up as a whole rig in the wrong place rather
     * than as a subtly wrong pivot.
     */
    private static Vector3f inversePoint(OpenMatrix4f world, Vector3f point) {
        float x = point.x - world.m30;
        float y = point.y - world.m31;
        float z = point.z - world.m32;
        return new Vector3f(
                world.m00 * x + world.m01 * y + world.m02 * z,
                world.m10 * x + world.m11 * y + world.m12 * z,
                world.m20 * x + world.m21 * y + world.m22 * z);
    }

    private static OpenMatrix4f toOpen(Matrix4f matrix) {
        OpenMatrix4f out = new OpenMatrix4f();
        out.m00 = matrix.m00();
        out.m01 = matrix.m01();
        out.m02 = matrix.m02();
        out.m03 = matrix.m03();
        out.m10 = matrix.m10();
        out.m11 = matrix.m11();
        out.m12 = matrix.m12();
        out.m13 = matrix.m13();
        out.m20 = matrix.m20();
        out.m21 = matrix.m21();
        out.m22 = matrix.m22();
        out.m23 = matrix.m23();
        out.m30 = matrix.m30();
        out.m31 = matrix.m31();
        out.m32 = matrix.m32();
        out.m33 = matrix.m33();
        return out;
    }

    /**
     * A transform out of Epic Fight's own {@code biped.json}.
     *
     * <p>Read <b>row major</b>, which is what those arrays are: the translation sits at indices 3, 7
     * and 11 (the last column) and the last row at 12..15 is {@code (0,0,0,1)}. On the bundled file
     * the Root's array is {@code [1,0,0,-0, 0,0,-1,0.0009, 0,1,0,0.764, 0,0,0,1]}, i.e. a root
     * 0.764 up the model's own vertical - and read the other way that entry lands in the last row,
     * where a rigid transform may not have it, which is how this was found: the rig it produced did
     * not land on the joint pivots, while the rotation angles below said the file was fine.
     */
    private static OpenMatrix4f matrixOf(JsonArray values) {
        OpenMatrix4f out = new OpenMatrix4f();
        out.m00 = values.get(0).getAsFloat();
        out.m10 = values.get(1).getAsFloat();
        out.m20 = values.get(2).getAsFloat();
        out.m30 = values.get(3).getAsFloat();
        out.m01 = values.get(4).getAsFloat();
        out.m11 = values.get(5).getAsFloat();
        out.m21 = values.get(6).getAsFloat();
        out.m31 = values.get(7).getAsFloat();
        out.m02 = values.get(8).getAsFloat();
        out.m12 = values.get(9).getAsFloat();
        out.m22 = values.get(10).getAsFloat();
        out.m32 = values.get(11).getAsFloat();
        out.m03 = values.get(12).getAsFloat();
        out.m13 = values.get(13).getAsFloat();
        out.m23 = values.get(14).getAsFloat();
        out.m33 = values.get(15).getAsFloat();
        return out;
    }

    private static YsmBindArmature.GeometryInput geometryInput(String modelId, JsonObject runtime,
                                                               JsonObject mesh, Set<String> hidden) {
        JsonArray bonesJson = runtime.getAsJsonArray("bones");
        String[] names = new String[bonesJson.size()];
        int[] joints = new int[bonesJson.size()];
        boolean[] mapped = new boolean[bonesJson.size()];
        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < names.length; i++) {
            JsonObject bone = bonesJson.get(i).getAsJsonObject();
            names[i] = bone.get("name").getAsString();
            joints[i] = bone.get("joint").getAsInt();
            mapped[i] = bone.has("mapped") && bone.get("mapped").getAsBoolean();
            indexOf.put(names[i], i);
        }
        JsonObject vertices = mesh.getAsJsonObject("vertices");
        JsonArray positionsJson = vertices.getAsJsonObject("positions").getAsJsonArray("array");
        float[] positions = new float[positionsJson.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = positionsJson.get(i).getAsFloat();
        }
        List<YsmBindArmature.BoneGeometry> parts = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : vertices.getAsJsonObject("parts").entrySet()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer bone = indexOf.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (bone == null || hidden.contains(names[bone])) {
                continue;
            }
            JsonArray indices = entry.getValue().getAsJsonObject().getAsJsonArray("array");
            List<Vector3f> points = new ArrayList<>(indices.size());
            for (JsonElement index : indices) {
                int at = index.getAsInt() * 3;
                if (at + 2 < positions.length) {
                    points.add(new Vector3f(positions[at], positions[at + 2], -positions[at + 1]));
                }
            }
            parts.add(new YsmBindArmature.BoneGeometry(bone, points));
        }
        return new YsmBindArmature.GeometryInput(modelId, names, joints, mapped, hidden, parts);
    }

    /** The face normal of a model, from its converted runtime's camera block. */
    private static Vector3f faceOf(JsonObject runtime) {
        if (!runtime.has("camera") || !runtime.get("camera").isJsonObject()) {
            return new Vector3f(0.0F, 0.0F, -1.0F);
        }
        JsonObject camera = runtime.getAsJsonObject("camera");
        Vector3f out = new Vector3f(
                camera.has("normalX") ? camera.get("normalX").getAsFloat() : 0.0F,
                camera.has("normalY") ? camera.get("normalY").getAsFloat() : 0.0F,
                camera.has("normalZ") ? camera.get("normalZ").getAsFloat() : -1.0F);
        return out.lengthSquared() < 1.0E-8F ? new Vector3f(0.0F, 0.0F, -1.0F) : out.normalize();
    }

    /** The instance's converted pack, or null when the config root is not configured. */
    private static Path convertedPackRoot() {
        String configured = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (configured.isEmpty()) {
            String fromEnvironment = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
            configured = fromEnvironment == null ? "" : fromEnvironment;
        }
        if (configured.isEmpty()) {
            return null;
        }
        Path configDir = Paths.get(configured).toAbsolutePath().getParent();
        if (configDir == null) {
            return null;
        }
        Path pack = configDir.resolve("ysm_epicfight_compat").resolve("resourcepack").resolve("assets")
                .resolve("ysm_epicfight_compat");
        return Files.isDirectory(pack.resolve("ysm_runtime/entity")) ? pack : null;
    }

    private static String point(Vector3f value) {
        return value == null || !YsmDynamicBoneSolver.isFinite(value) ? "n/a"
                : String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", value.x, value.y, value.z);
    }

    private static String fmt(float value) {
        return Float.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "n/a";
    }
}
