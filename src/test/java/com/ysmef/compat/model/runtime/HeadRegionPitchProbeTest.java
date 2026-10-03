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
    // The skirt layers and the tail tip: this round's measurement
    // ------------------------------------------------------------------

    /** Cell size the concentric structure is measured in: 2 degrees of azimuth, 2 cm of height. */
    private static final float LADDER_DEGREES = 2.0F;
    private static final float LADDER_HEIGHT = 0.02F;

    /** Barycentric subdivisions of every mesh triangle, so a panel's surface is sampled. */
    private static final int SURFACE_SUBDIVISION = 4;

    /** Frames per motion state, and how many are skipped before any statistic is taken. */
    private static final int GAIT_FRAMES = 600;
    private static final int GAIT_WARMUP = 200;

    /** The body's forward speed in the two gaits, blocks/s, in the model's own frame (-Z forward). */
    private static final float WALK_SPEED = 4.317F;
    private static final float SPRINT_SPEED = 5.612F;

    /** The pieces the user says look right, which the tail tip has to be read against. */
    private static final String[] KEEP_SWINGING = {
            "Tail", "Tail2", "Tail3", "Tail4", "LongHair", "LongHair2",
            "LongLeftHair", "LongLeftHair2", "LongRightHair", "LongRightHair2",
            "LeftSideHair", "RightSideHair", "Bangs", "BaseHair"};

    /** One gait: the pose it runs at, and the speed the body moves at while it does. */
    private record Gait(String name, float leanDegrees, float bob, float strideDegrees,
                        float kneeDegrees, float hertz, float speed) {}

    /** One candidate override: what it holds rigid and what it caps, by bone name. */
    private record Candidate(String label, Set<String> rigid, Map<String, Float> limitDeg) {}

    /** The candidates, from the mildest to the harshest, plus the do-nothing baseline. */
    private static final Candidate[] TAIL_CANDIDATES = {
            new Candidate("nothing (baseline)", Set.of(), Map.of()),
            new Candidate("rigid Tail7", Set.of("Tail7"), Map.of()),
            new Candidate("rigid Tail6, Tail7", Set.of("Tail6", "Tail7"), Map.of()),
            new Candidate("limitDeg Tail5..7 = 8", Set.of(),
                    Map.of("tail5", 8.0F, "tail6", 8.0F, "tail7", 8.0F)),
            new Candidate("limitDeg Tail2..7 = 8", Set.of(),
                    Map.of("tail2", 8.0F, "tail3", 8.0F, "tail4", 8.0F,
                            "tail5", 8.0F, "tail6", 8.0F, "tail7", 8.0F)),
            new Candidate("limitDeg Tail5..7 = 4", Set.of(),
                    Map.of("tail5", 4.0F, "tail6", 4.0F, "tail7", 4.0F))};

    private static final Gait[] GAITS = {
            new Gait("idle", 0.0F, 0.003F, 2.0F, 2.0F, 0.30F, 0.0F),
            new Gait("walk", 6.0F, 0.022F, 20.0F, 16.0F, 1.05F, WALK_SPEED),
            new Gait("sprint", 17.0F, 0.040F, 34.0F, 28.0F, 1.60F, SPRINT_SPEED)};

    /** The sprint, which is the state the tail was reported in. */
    private static final Gait SPRINT = GAITS[2];

    /**
     * The skirt crossing and the tail tip: what the two reported defects are, as numbers.
     *
     * <p>Both are measured the same way, in <b>cells</b> of azimuth and height about the body's own
     * vertical axis, over the piece's own <b>surface</b> rather than its corner list: every mesh
     * triangle is barycentrically subdivided, so a panel's radial extent at one height is the panel,
     * not the two corners that happen to be sampled there.
     *
     * <p>Only each piece's own delta is applied, in bind space. Every skirt piece and every tail
     * link is drawn on the same joint, so the pose deformation is common to them and cannot change
     * their relative geometry; measuring it would add the body's whole lean to every radius and
     * hide the effect this round is about. The test asserts that common joint rather than assuming
     * it.
     */
    @Test
    void theSkirtLayersAndTheTailTipAtSpeed() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");

        Rig maid = Rig.load(pack, MAID[1], LOGGED_MAID_SIMULATED);
        StringBuilder report = new StringBuilder();
        report.append("# The skirt layers and the tail tip, from the deployed build's own artefacts\n\n")
                .append("Model `").append(maid.modelId).append("`, ").append(maid.segments.size())
                .append(" simulated pieces. A **cell** is ")
                .append(fmt(LADDER_DEGREES)).append(" deg of azimuth by ")
                .append(fmt(LADDER_HEIGHT)).append(" blocks of height about the body's own vertical ")
                .append("axis, and a piece occupies a cell with the radial interval `[rMin, rMax]` of ")
                .append("its own surface inside it. Every number is read with **only that piece's own ")
                .append("delta** applied, in bind space.\n\n");
        // The reader is calibrated on the one place a running client printed the same numbers this
        // probe computes: the leg rows of the OTHER model of that session, to the log's own
        // rounding. The reported model has no such leg rows, so it is calibrated instead against
        // the armature line its own session printed.
        List<String> problems = new ArrayList<>();
        String problem = calibrateTheMatrixConvention(report);
        if (problem != null) {
            problems.add(problem);
        }
        Rig eku = Rig.load(pack, EKU[1], LOGGED_EKU_SIMULATED);
        problem = calibrateAgainstTheClientsLegRows(eku, report);
        if (problem != null) {
            problems.add(problem);
        }
        problem = calibrateArmatureAgainstTheClientsBindLine(maid, report);
        if (problem != null) {
            problems.add(problem);
        }

        Vector3f axis = bodyAxis(maid);
        report.append("## The frame the measurement is taken in\n\n")
                .append("- the body's own vertical axis, from the two hip joints the armature ")
                .append("settled: `").append(point(axis)).append("`\n");
        // The sign a run leans into, read off the model's own face rather than assumed.
        Vector3f faceMoved = YsmMeshSecondaryMotion.transformDirection(
                about(maid.jointOrigin(JointTable.TORSO), 10.0F, 0.0F), maid.face, new Vector3f());
        float leanSign = faceMoved.y <= maid.face.y ? 1.0F : -1.0F;
        report.append("- the model's own face `").append(point(maid.face))
                .append("`: +10 deg about the torso joint moves it to `").append(point(faceMoved))
                .append("`, so a **forward lean is ").append(leanSign > 0.0F ? "+" : "-")
                .append("x** and the gaits below use ")
                .append(leanSign > 0.0F ? "+" : "-").append("lean\n");
        int wrongJoint = 0;
        Set<Integer> jointsOfTheGarment = new java.util.TreeSet<>();
        for (Segment piece : maid.segments) {
            if (isSkirtOrTail(piece.name)) {
                jointsOfTheGarment.add(piece.joint);
            }
        }
        for (String name : KEEP_SWINGING) {
            Segment piece = maid.segmentNamed(name);
            if (piece != null) {
                jointsOfTheGarment.add(piece.joint);
            }
        }
        for (int joint : jointsOfTheGarment) {
            if (joint != JointTable.TORSO && joint != JointTable.CHEST && joint != JointTable.HEAD) {
                wrongJoint++;
            }
        }
        report.append("- the joints every skirt piece, tail link and reference piece is drawn on: ")
                .append(jointsOfTheGarment).append(" (")
                .append(wrongJoint == 0 ? "all of them on the torso, the chest or the head, which is "
                        + "why one deformation cannot change their relative geometry"
                        : "**NOT all on one joint** - the relative-geometry argument does not hold")
                .append(")\n\n");

        // The samples: one surface per piece, in bind space, taken once.
        float[][] samples = new float[maid.segments.size()][];
        for (Segment piece : maid.segments) {
            samples[piece.index] = surfaceSamples(triangleSoup(piece.own), null);
        }

        // ---- 1. the inventory ------------------------------------------------------------------
        report.append("## 1. Every simulated piece, measured\n\n")
                .append("`pivot` is the piece's bind pivot; `anchor` is the shipped hinge the delta ")
                .append("turns it about (`YsmPhysicsParts#contactAnchor`); `rest` is the piece's own ")
                .append("centroid minus its pivot; `lever` is its length; `radius` the collision radius ")
                .append("the solve is given. `y`, `r` and `deg` are its own surface's extent in height, ")
                .append("radius from the body axis, and azimuth.\n\n")
                .append("| bone | joint | parent | pivot | anchor | |anchor-pivot| | rest | lever | ")
                .append("radius | maxAngle | verts | y | r | deg |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Segment piece : maid.segments) {
            Map<Long, float[]> cells = cellsOf(samples[piece.index], null, axis);
            float rMin = Float.MAX_VALUE;
            float rMax = 0.0F;
            float degMin = Float.MAX_VALUE;
            float degMax = -Float.MAX_VALUE;
            for (Map.Entry<Long, float[]> entry : cells.entrySet()) {
                rMin = Math.min(rMin, entry.getValue()[0]);
                rMax = Math.max(rMax, entry.getValue()[1]);
                float degrees = azimuthDegrees(entry.getKey());
                degMin = Math.min(degMin, degrees);
                degMax = Math.max(degMax, degrees);
            }
            float yLo = Float.MAX_VALUE;
            float yHi = -Float.MAX_VALUE;
            for (Vector3f vertex : piece.own) {
                yLo = Math.min(yLo, vertex.y);
                yHi = Math.max(yHi, vertex.y);
            }
            Segment parent = piece.parent >= 0 ? maid.segmentByIndex.get(piece.parent) : null;
            report.append("| `").append(piece.name).append("` | ").append(piece.joint)
                    .append(" | ").append(parent == null ? "-" : "`" + parent.name + "`")
                    .append(" | ").append(point(piece.bindPivot))
                    .append(" | ").append(point(piece.bindAnchor))
                    .append(" | ").append(fmt(piece.bindAnchor.distance(piece.bindPivot)))
                    .append(" | ").append(point(piece.bindRest))
                    .append(" | ").append(fmt(piece.lever))
                    .append(" | ").append(fmt(piece.radius))
                    .append(" | ").append(fmt(piece.maxAngle))
                    .append(" | ").append(piece.own.size())
                    .append(" | ").append(fmt(yLo)).append("..").append(fmt(yHi))
                    .append(" | ").append(fmt(rMin)).append("..").append(fmt(rMax))
                    .append(" | ").append(fmt(degMin)).append("..").append(fmt(degMax))
                    .append(" |\n");
        }
        report.append('\n');

        // ---- 2. the columns ---------------------------------------------------------------------
        int[] all = new int[maid.segments.size()];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        // The pieces this round reads frame by frame: everything the simulation moves. Read as the
        // whole simulated set rather than a name family, because the first run of this probe showed
        // what a name family costs: the six `FFM*` pieces of the front of this skirt are not named
        // like the twelve columns, and they were the one part of the garment the per-frame crossing
        // test never looked at.
        int[] tracked = all;
        Map<Long, float[]>[] bindCells = cellMaps(samples, null, axis);
        report.append(concentricLadder(maid, bindCells, tracked, axis));

        // ---- 3. the crossing at bind, every pair ------------------------------------------------
        List<SurfaceRef> staticSurfaces = staticSurfaces(maid);
        report.append("The crossing test runs over two layers at once, because they are drawn by two ")
                .append("different rules: the **simulated** pieces, which the solver moves, and every ")
                .append("bone of this model that carries visible geometry the simulation does ")
                .append("**not** move (").append(staticSurfaces.size())
                .append(" of them) - those follow the pose exactly, so a simulated piece swings ")
                .append("through a layer that never yields. Testing only the simulated set against ")
                .append("itself would be blind to exactly that case.\n\n")
                .append("### The control, before any model number\n\n")
                .append("The ray-parity test is run on two hand-built boxes first: a small box wholly ")
                .append("inside a bigger one must report every one of its vertices inside, with the ")
                .append("depth to the nearest face, and two boxes side by side must report none. ")
                .append("These are asserted, so \"no pair crosses\" below cannot be a statement about a ")
                .append("broken test.\n\n");
        report.append(controlReport(problems));
        report.append(modelControl(maid, all, axis, problems));

        Solid[] staticSolids = solidsOf(staticSurfaces);
        List<String> staticNames = namesOf(staticSurfaces);
        List<SurfaceRef> allSurfaces = trackedSurfaces(maid, all, null);
        Solid[] bindSolids = concat(solidsOf(allSurfaces), staticSolids);
        List<String> bindNames = concat(namesOf(allSurfaces), staticNames);
        int bindTracked = allSurfaces.size();
        report.append("## 3. The crossing at bind: one piece's vertices inside another's surface\n\n")
                .append("A **crossing** is measured as geometry, not as a statistic: a piece's own ")
                .append("vertex that lies **inside** another piece's closed surface, and how deep. ")
                .append("Inside is decided by parity along three axis rays, and the depth is the ")
                .append("shortest way back out along the six axis directions - the thickness of the ")
                .append("other piece at that point. A pair that merely touches has no vertex inside, ")
                .append("so it does not appear; a pair that crosses has as many as the overlap ")
                .append("contains. `a in b` and `b in a` are counted separately, because which of the ")
                .append("two is on the outside is the whole question.\n\n");
        List<Crossing> bindCrossings = crossingTable(bindSolids, axis);
        report.append(crossingTableReport(maid, bindNames, bindCrossings, bindTracked, 25));
        report.append("### The garment's own crossings, at bind\n\n")
                .append("The same table restricted to pairs where **both** pieces are the garment: ")
                .append("the twelve three-link columns and the six `FFM*` pieces of its front. ")
                .append("Everything above this line is hair, ears, mouth parts and limbs - shapes ")
                .append("that are authored to wrap one another (a cap over a skull, a chain link ")
                .append("over the link above it), which is why the whole-model table is not the ")
                .append("table this defect is read from.\n\n");
        List<Crossing> garmentBind = garmentOnly(bindCrossings, bindNames);
        report.append(crossingTableReport(maid, bindNames, garmentBind, bindTracked, 30));
        report.append("Pairs that neither meet nor contain: ")
                .append(bindNames.size() * (bindNames.size() - 1) / 2 - bindCrossings.size())
                .append(" of ").append(bindNames.size() * (bindNames.size() - 1) / 2).append(".\n\n");

        // ---- 4. the radial order, for the record ------------------------------------------------
        float[] score = outerScores(bindCells, all);
        report.append("## 4. Where each piece sits radially, in the cells it occupies\n\n")
                .append("`outerShare` is the piece's mean position inside the radial span of every ")
                .append("cell it occupies - 0 at the innermost surface in that cell, 1 at the ")
                .append("outermost. It is reported for the record and is **not** the layer assignment: ")
                .append("a piece that occupies cells no other piece reaches reads 0 or 1 by ")
                .append("construction, and on this model that is most of them, so the column table in ")
                .append("section 2 and the crossings in section 3 are what identify the layers.\n\n")
                .append("| bone | outerShare |\n|---|---|\n");
        Integer[] boxed = new Integer[all.length];
        for (int i = 0; i < boxed.length; i++) {
            boxed[i] = i;
        }
        java.util.Arrays.sort(boxed, (a, b) -> Float.compare(score[a], score[b]));
        for (int index : boxed) {
            report.append("| `").append(maid.segments.get(index).name).append("` | ")
                    .append(fmt(score[index])).append(" |\n");
        }
        report.append('\n');

        // ---- 5. the crossing in each state ------------------------------------------------------
        report.append("## 5. The crossing, state by state\n\n")
                .append("`bind` is the authored geometry with no physics at all. `settled` is the ")
                .append("production solver run to rest with no pose and no velocity: what the player ")
                .append("sees standing still. `idle`, `walk` and `sprint` are the production frame ")
                .append("loop over the gait named, ").append(GAIT_FRAMES).append(" frames of ")
                .append(fmt(DT)).append(" s each with that gait's own pose and body velocity, ")
                .append("collision off. Every fortieth frame after the warm-up is measured, over ")
                .append("every simulated piece and every carried bone at once.\n\n")
                .append("| state | all pairs: worst | the pair | garment pairs crossing | garment worst | ")
                .append("the garment pair | largest move of a tracked vertex | frames measured |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        report.append(crossingRow("bind", bindNames, bindCrossings, bindTracked, 0.0F, 1,
                garmentBind.size()));
        report.append(trackCrossings("settled", maid, STILL_POSE, null, 300, tracked, staticSolids,
                staticNames));
        for (Gait gait : GAITS) {
            report.append(trackCrossings(gait.name(), maid, new RunPose(maid, gait, leanSign), gait,
                    GAIT_FRAMES, tracked, staticSolids, staticNames));
        }
        report.append('\n');

        // ---- 6. the tail tip and the pieces that must keep swinging -----------------------------
        report.append("## 6. What each piece does while the body runs\n\n")
                .append("`own` is the swing the solver gave the piece this frame and `allowed` the ")
                .append("allowance the chain granted it; **pinned share** is the share of frames whose ")
                .append("own swing is at that allowance, i.e. the frames the piece spends against its ")
                .append("clamp rather than on its spring. `tip speed` is the distance the piece's own ")
                .append("farthest vertex travels per second, and `reversals/s` how often that vertex ")
                .append("changes direction - together they are what \"swings all over the place\" ")
                .append("means. `composed` is the angle of the delta the mesh actually receives, ")
                .append("which includes every ancestor's swing.\n\n");
        for (Gait gait : GAITS) {
            RunPose pose = new RunPose(maid, gait, leanSign);
            List<Trace> traces = tracesOf(maid);
            runGait(maid, pose, gait, traces, Set.of(), Map.of());
            report.append(gaitTable(maid, gait, traces));
        }

        // ---- 7. the candidates for the tail tip, measured ---------------------------------------
        report.append("## 7. The candidates for the tail tip, measured\n\n")
                .append("Each candidate re-runs the same sprint, 600 frames of ").append(fmt(DT))
                .append(" s at ").append(fmt(SPRINT_SPEED)).append(" blocks/s, with the candidate's ")
                .append("own bones held and/or capped exactly where the override file puts them ")
                .append("(`state.held` and `state.limit`, the two arrays `YsmPhysicsOverrides")
                .append("#markOverrides` fills). `cost` is the largest distance any vertex of a ")
                .append("piece the candidate does **not** name moves against the baseline run: a ")
                .append("piece under a capped link is expected to move, everything else must not.\n\n")
                .append("| candidate | piece | own mean | allowed mean | pinned | composed max | ")
                .append("tip spread | tip speed | tip speed vs baseline |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        RunPose baselinePose = new RunPose(maid, SPRINT, leanSign);
        List<Trace> baselineTraces = tracesOf(maid);
        YsmMeshSecondaryMotion.State baseline = runGait(maid, baselinePose, SPRINT, baselineTraces,
                Set.of(), Map.of());
        Map<String, Float> baselineTips = tipSpeeds(baselineTraces);
        for (Candidate candidate : TAIL_CANDIDATES) {
            if (candidate.label().startsWith("nothing")) {
                report.append(candidateRows(maid, candidate, baselineTraces, baselineTips, baseline,
                        baseline, 0.0F));
                continue;
            }
            RunPose pose = new RunPose(maid, SPRINT, leanSign);
            List<Trace> traces = tracesOf(maid);
            YsmMeshSecondaryMotion.State state = runGait(maid, pose, SPRINT, traces,
                    candidate.rigid(), candidate.limitDeg());
            report.append(candidateRows(maid, candidate, traces, baselineTips, baseline, state,
                    worstUnnamedMove(maid, candidate, baseline, state)));
        }
        report.append('\n');

        Path out = Paths.get("build", "reports", "ysm-skirt-tail-measurement.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertTrue(Files.size(out) > 0L);
        assertEquals(0, wrongJoint,
                "every skirt piece, tail link and reference piece must be drawn on one of the three "
                        + "upper joints, or the relative-geometry argument does not hold");
    }

    // ------------------------------------------------------------------
    // The tail root: this round's measurement
    // ------------------------------------------------------------------

    /** The override the tip round replaced, and the one it shipped - the two states compared. */
    private static final Set<String> RIGID_ONLY = Set.of("BaseHair");
    private static final Map<String, Float> NO_CEILING = Map.of();
    private static final Map<String, Float> TIP_CEILING_8 =
            Map.of("tail5", 8.0F, "tail6", 8.0F, "tail7", 8.0F);

    /** The seven links of the reported fox tail, root first. */
    private static final String[] TAIL_LINKS =
            {"Tail", "Tail2", "Tail3", "Tail4", "Tail5", "Tail6", "Tail7"};

    /** The four links at the tail's base: the ones the report says still overshoot. */
    private static final String[] TAIL_ROOTS = {"Tail", "Tail2", "Tail3", "Tail4"};

    /** The pieces the report says look right today, as the calibration for every metric below. */
    private static final String[] REFERENCE_LINKS = {"LongHair", "LongHair2", "LongLeftHair2",
            "LongRightHair2", "LeftSideHair", "RightSideHair", "Bangs", "BL3", "RF3", "FL2"};

    /** The transient: how long the body settles before the step, and how long the step is followed. */
    private static final int STEP_SETTLE_FRAMES = 300;
    private static final int STEP_TAIL_FRAMES = 30;
    private static final int STEP_FRAMES = 240;

    /** The candidates for the root's problem: the shipped file, and the narrowest ways out of it. */
    private static final Candidate[] ROOT_CANDIDATES = {
            new Candidate("before: no ceilings at all", RIGID_ONLY, NO_CEILING),
            new Candidate("after: limitDeg Tail5..7 = 8", RIGID_ONLY, TIP_CEILING_8),
            new Candidate("shipped + limitDeg Tail = 14", RIGID_ONLY, tailAndTips(14.0F)),
            new Candidate("shipped + limitDeg Tail = 12", RIGID_ONLY, tailAndTips(12.0F)),
            new Candidate("shipped + limitDeg Tail = 10", RIGID_ONLY, tailAndTips(10.0F)),
            new Candidate("shipped + limitDeg Tail = 8", RIGID_ONLY, tailAndTips(8.0F)),
            new Candidate("shipped + limitDeg Tail..2 = 12", RIGID_ONLY, rootsAndTips(12.0F, 2)),
            new Candidate("shipped + limitDeg Tail..3 = 8", RIGID_ONLY, rootsAndTips(8.0F, 3))};

    /** The shipped file's three tip ceilings, plus one on the root link. */
    private static Map<String, Float> tailAndTips(float tail) {
        return Map.of("tail", tail, "tail5", 8.0F, "tail6", 8.0F, "tail7", 8.0F);
    }

    /** The shipped file's three tip ceilings, plus one on the first {@code count} links. */
    private static Map<String, Float> rootsAndTips(float degrees, int count) {
        Map<String, Float> out = new LinkedHashMap<>();
        String[] names = {"tail", "tail2", "tail3", "tail4"};
        for (int i = 0; i < count; i++) {
            out.put(names[i], degrees);
        }
        out.put("tail5", 8.0F);
        out.put("tail6", 8.0F);
        out.put("tail7", 8.0F);
        return Map.copyOf(out);
    }

    /** One damping candidate: a ratio for the pieces named, with the shipped ceilings in force. */
    private record DampingCandidate(String label, Map<String, Float> ratio) {}

    private static final DampingCandidate[] DAMPING_CANDIDATES = {
            new DampingCandidate("damping 0.50 (control, looser than shipped)",
                    Map.of("tail", 0.5F, "tail2", 0.5F, "tail3", 0.5F)),
            new DampingCandidate("damping 0.81 (shipped)", Map.of()),
            new DampingCandidate("damping Tail..3 = 1.00",
                    Map.of("tail", 1.0F, "tail2", 1.0F, "tail3", 1.0F)),
            new DampingCandidate("damping Tail..7 = 1.00",
                    Map.of("tail", 1.0F, "tail2", 1.0F, "tail3", 1.0F, "tail4", 1.0F,
                            "tail5", 1.0F, "tail6", 1.0F, "tail7", 1.0F))};

    /**
     * The tail root, before and after the tip ceilings: the prime hypothesis, the overshoot as a
     * transient, and the candidates for the root's own problem.
     *
     * <p>There are three questions and they are answered in this order, because the second one is
     * only worth asking of the state the first one leaves standing.
     *
     * <ol>
     *   <li><b>Did the root links absorb the bend the three tip ceilings removed?</b> The two states
     *       differ in the three ceilings and in nothing else; if the root's own swing, allowance,
     *       pinned share or far-end travel moved at all, this section says by how much.</li>
     *   <li><b>What is the overshoot, as a transient property?</b> Not the amplitude: a body that
     *       starts moving and then stops, with the pose frozen so nothing but the step is driving the
     *       chain, and per link the distance its own far vertex travels past where it comes to rest -
     *       as a fraction of the step, with how many times it crosses the rest point and how long it
     *       takes to stay inside five per cent of the step. The metric is calibrated by running it on
     *       the pieces the report says look right, and the assertions at the end fail if it cannot
     *       discriminate at all.</li>
     *   <li><b>Is the root link's own rest and anchor wrong?</b> Its pivot, the contact patch the
     *       delta turns it about, its rest direction and the angles between them and the chain, read
     *       beside the same numbers for the pieces that look right.</li>
     * </ol>
     */
    @Test
    void theTailRootBeforeAndAfterTheTipCeilings() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");

        Rig maid = Rig.load(pack, MAID[1], LOGGED_MAID_SIMULATED);
        StringBuilder report = new StringBuilder();
        report.append("# The tail root: before and after the tip ceilings\n\n")
                .append("Model `").append(maid.modelId).append("`, ").append(maid.segments.size())
                .append(" simulated pieces. **before** is the override file the tip round replaced, ")
                .append("`{\"rigid\":[\"BaseHair\"]}` with no `limitDeg`; **after** is the shipped ")
                .append("file, the same rigid bone and `limitDeg` on `Tail5`, `Tail6`, `Tail7` at 8 ")
                .append("degrees. The two runs differ in those three ceilings and nothing else, so ")
                .append("every difference below is attributable to them.\n\n");
        List<String> problems = new ArrayList<>();
        String problem = calibrateTheMatrixConvention(report);
        if (problem != null) {
            problems.add(problem);
        }
        problem = calibrateArmatureAgainstTheClientsBindLine(maid, report);
        if (problem != null) {
            problems.add(problem);
        }

        Vector3f faceMoved = YsmMeshSecondaryMotion.transformDirection(
                about(maid.jointOrigin(JointTable.TORSO), 10.0F, 0.0F), maid.face, new Vector3f());
        float leanSign = faceMoved.y <= maid.face.y ? 1.0F : -1.0F;

        // ---- 1. the sprint, every candidate ------------------------------------------------------
        String[] all = allNames(maid);
        String[] followed = join(TAIL_LINKS, REFERENCE_LINKS);
        List<Trace> baselineTraces = tracesNamed(maid, all);
        YsmMeshSecondaryMotion.State baselineState = runGait(maid, new RunPose(maid, SPRINT, leanSign),
                SPRINT, baselineTraces, RIGID_ONLY, NO_CEILING);
        List<List<Trace>> runs = new ArrayList<>();
        List<Float> costs = new ArrayList<>();
        for (Candidate candidate : ROOT_CANDIDATES) {
            if (candidate.label().startsWith("before")) {
                runs.add(baselineTraces);
                costs.add(0.0F);
                continue;
            }
            List<Trace> traces = tracesNamed(maid, all);
            runGait(maid, new RunPose(maid, SPRINT, leanSign), SPRINT, traces,
                    candidate.rigid(), candidate.limitDeg());
            runs.add(traces);
            costs.add(worstUnnamedDifference(maid, candidate, baselineTraces, traces));
        }

        report.append("## 1. Every link at sprint, before and after the tip ceilings\n\n")
                .append("The production frame loop, ").append(GAIT_FRAMES).append(" frames of ")
                .append(fmt(DT)).append(" s at ").append(fmt(SPRINT_SPEED))
                .append(" blocks/s, the first ").append(GAIT_WARMUP)
                .append(" frames skipped. `own` is the swing the solver gave the link and `allowed` ")
                .append("the allowance the chain - and now the file's ceiling - granted it; ")
                .append("**pinned** is the share of frames spent against that limit, where a pinned ")
                .append("link has no spring left to give back. `whole` is the composed delta the mesh ")
                .append("receives, `travel` the distance the link's own far vertex covers per second, ")
                .append("`spread` the size of the region it swept.\n\n")
                .append("| link | own before | own after | allowed before | allowed after | pinned ")
                .append("before | pinned after | drawn before | drawn after | clipped before | ")
                .append("clipped after | clip max | whole before | whole after | travel before | ")
                .append("travel after |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (String name : followed) {
            Trace before = traceNamed(runs.get(0), name);
            Trace after = traceNamed(runs.get(1), name);
            if (before == null || after == null) {
                continue;
            }
            report.append("| `").append(name).append("` | ")
                    .append(fmt(before.ownMean())).append(" | ").append(fmt(after.ownMean()))
                    .append(" | ").append(fmt(before.allowedMean())).append(" | ")
                    .append(fmt(after.allowedMean()))
                    .append(" | ").append(fmt(before.saturatedShare())).append(" | ")
                    .append(fmt(after.saturatedShare()))
                    .append(" | ").append(fmt(before.drawnRange())).append(" | ")
                    .append(fmt(after.drawnRange()))
                    .append(" | ").append(fmt(before.clippedShare())).append(" | ")
                    .append(fmt(after.clippedShare()))
                    .append(" | ").append(fmt(Math.max(before.worstClip(), after.worstClip())))
                    .append(" | ").append(fmt(before.composedMax())).append(" | ")
                    .append(fmt(after.composedMax()))
                    .append(" | ").append(fmt(before.tipSpeed())).append(" | ")
                    .append(fmt(after.tipSpeed()))
                    .append(" |\n");
        }
        report.append("\n`drawn` is how far the link's own **drawn** swing travels between its ")
                .append("extremes over the run and `clipped` the share of frames in which the drawn ")
                .append("swing is held below the solver's own by more than a tenth of a degree - the ")
                .append("allowance is a display clamp, so a link that is never clipped is a link whose ")
                .append("physics fits inside its budget. `clip max` is the widest that hold gets, in ")
                .append("degrees. A link with `drawn` near zero is not a link that is still: it is a ")
                .append("link drawn at a fixed angle in every frame.\n\n");

        report.append("\n### Did the root links absorb the bend?\n\n")
                .append("The four root links, before and after, in the units the question is asked ")
                .append("in:\n\n")
                .append("| root link | own before | own after | change | travel before | travel after ")
                .append("| change | pinned before | pinned after |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        float worstOwnChange = 0.0F;
        float worstTravelChange = 0.0F;
        float rootOwnBefore = 0.0F;
        float rootOwnAfter = 0.0F;
        for (String name : TAIL_ROOTS) {
            Trace before = traceNamed(runs.get(0), name);
            Trace after = traceNamed(runs.get(1), name);
            if (before == null || after == null) {
                continue;
            }
            float ownChange = after.ownMean() - before.ownMean();
            float travelChange = after.tipSpeed() - before.tipSpeed();
            worstOwnChange = Math.max(worstOwnChange, Math.abs(ownChange));
            worstTravelChange = Math.max(worstTravelChange, Math.abs(travelChange));
            rootOwnBefore += before.ownMean();
            rootOwnAfter += after.ownMean();
            report.append("| `").append(name).append("` | ").append(fmt(before.ownMean()))
                    .append(" | ").append(fmt(after.ownMean())).append(" | ").append(signed(ownChange))
                    .append(" | ").append(fmt(before.tipSpeed())).append(" | ")
                    .append(fmt(after.tipSpeed())).append(" | ").append(signed(travelChange))
                    .append(" | ").append(fmt(before.saturatedShare())).append(" | ")
                    .append(fmt(after.saturatedShare())).append(" |\n");
        }
        report.append("\nThe largest change any root link's own swing shows is ")
                .append(signed(worstOwnChange)).append(" degrees and the largest change in its ")
                .append("far-end travel is ").append(signed(worstTravelChange))
                .append(" blocks/s; the four roots together move from ").append(fmt(rootOwnBefore))
                .append(" to ").append(fmt(rootOwnAfter)).append(" degrees of summed own swing.\n\n")
                .append("The shipped ceilings' blast radius, measured as the largest distance any ")
                .append("vertex of a piece they do not name - and that does not hang under one they ")
                .append("do - is in a different place at any of the ").append(GAIT_FRAMES - GAIT_WARMUP)
                .append(" measured frames: ").append(fmt(costs.get(1))).append(" blocks.\n\n");

        // ---- 2. the overshoot, as a transient ----------------------------------------------------
        report.append("## 2. The overshoot, measured as a transient\n\n")
                .append("A transient and not an amplitude, because \"overshoot\" is a property of a ")
                .append("response: the body settles in one state for ").append(STEP_SETTLE_FRAMES)
                .append(" frames with the **pose frozen** - the sprint gait's phase 0, held, so the ")
                .append("only thing that changes at the step is the body's velocity - and then the ")
                .append("velocity is stepped. **stop** is ").append(fmt(SPRINT_SPEED))
                .append(" -> 0 blocks/s and **start** is 0 -> ").append(fmt(SPRINT_SPEED))
                .append("; each is followed for ").append(STEP_FRAMES).append(" frames of ")
                .append(fmt(DT)).append(" s.\n\n")
                .append("Per link, with `P0` the mean position of its own far vertex over the last ")
                .append(STEP_TAIL_FRAMES).append(" frames before the step, `Pinf` the same over the ")
                .append("last ").append(STEP_TAIL_FRAMES).append(" frames after it, and ")
                .append("`d(t) = (P(t) - Pinf)` projected on `P0 - Pinf`:\n\n")
                .append("- **step**: `|P0 - Pinf|`, blocks, what the link is asked to travel;\n")
                .append("- **overshoot**: `max(0, max_t -d(t)) / step` - how far past where it comes ")
                .append("to rest it goes *along the line it travelled*, as a fraction of the step;\n")
                .append("- **reach**: the same question asked without a direction - ")
                .append("`max(0, max_t |P(t) - Pinf| - step) / step`, so an excursion that leaves the ")
                .append("line between the two settled places still counts;\n")
                .append("- **own before / own settled / own peak**: the link's own clamped swing at ")
                .append("the two ends of the step and at its largest during it; the ratio below is ")
                .append("how far the swing overshoots where it ends up, and `own raw peak` is the same ")
                .append("peak read from the solver's own state, before the allowance is applied - the ")
                .append("two differ exactly when the link is drawn held on its stop;\n")
                .append("- **clipped**: the share of the step's frames in which the drawn swing is held ")
                .append("below the solver's own by more than a tenth of a degree;\n")
                .append("- **5% time**: the last frame at which `|d(t)|` exceeds five per cent of ")
                .append("the step - the damping time;\n")
                .append("- **travel**: the path length the far vertex covers during the step, ")
                .append("blocks, which is what the eye tracks.\n\n");

        Step stopBefore = runStep(maid, leanSign, 0.0F, SPRINT_SPEED, 0.0F, RIGID_ONLY, NO_CEILING);
        Step stopAfter = runStep(maid, leanSign, 0.0F, SPRINT_SPEED, 0.0F, RIGID_ONLY, TIP_CEILING_8);
        Step startBefore = runStep(maid, leanSign, 0.0F, 0.0F, SPRINT_SPEED, RIGID_ONLY, NO_CEILING);
        Step startAfter = runStep(maid, leanSign, 0.0F, 0.0F, SPRINT_SPEED, RIGID_ONLY, TIP_CEILING_8);
        report.append(stepReport("stop, before", stopBefore));
        report.append(stepReport("stop, after", stopAfter));
        report.append(stepReport("start, before", startBefore));
        report.append(stepReport("start, after", startAfter));
        report.append(stepComparison("stop", stopBefore, stopAfter));
        report.append(stepComparison("start", startBefore, startAfter));

        float worstOvershoot = 0.0F;
        float leastOvershoot = Float.MAX_VALUE;
        for (Step step : new Step[]{stopBefore, stopAfter, startBefore, startAfter}) {
            for (StepTrace trace : step.traces) {
                float value = reachOf(trace);
                worstOvershoot = Math.max(worstOvershoot, value);
                leastOvershoot = Math.min(leastOvershoot, value);
            }
        }
        // The mechanism this round is about, as a number the test can fail on: in the shipped state
        // some of these links are drawn held on their stop in every frame and some are not. If that
        // is not true of this model, the diagnosis is wrong and everything above is about something
        // else.
        float worstClipShare = 0.0F;
        float leastClipShare = 1.0F;
        for (String name : followed) {
            Trace trace = traceNamed(runs.get(0), name);
            if (trace == null) {
                continue;
            }
            worstClipShare = Math.max(worstClipShare, trace.clippedShare());
            leastClipShare = Math.min(leastClipShare, trace.clippedShare());
        }
        report.append("Over all four transients and every link followed, the direction-free ")
                .append("overshoot runs from ").append(fmt3(leastOvershoot)).append(" to ")
                .append(fmt3(worstOvershoot)).append(" of the step, and over the sprint the share ")
                .append("of frames a followed link is drawn held on its stop runs from ")
                .append(fmt(leastClipShare)).append(" to ").append(fmt(worstClipShare))
                .append(".\n\n");
        if (!(worstOvershoot > 0.05F) || !(leastOvershoot < 0.05F)) {
            problems.add("the transient overshoot does not discriminate on this model: range "
                    + fmt3(leastOvershoot) + ".." + fmt3(worstOvershoot));
        }
        if (!(worstClipShare > 0.5F) || !(leastClipShare < 0.5F)) {
            problems.add("no link of this model is drawn held on its stop while another is free: "
                    + "clipped share range " + fmt(leastClipShare) + ".." + fmt(worstClipShare)
                    + ", so the mechanism this round measured is not present");
        }

        // ---- 3. the root's own rest and anchor ---------------------------------------------------
        report.append("## 3. The root link's own rest and anchor\n\n")
                .append("`rest` is the piece's centroid minus its pivot, which is the direction the ")
                .append("solver's spring pulls the piece back to; `anchor` is the contact patch the ")
                .append("delta turns it about. `down` is the angle from `rest` to the world's own ")
                .append("downward direction (the solver's gravity target), `to parent` the angle to ")
                .append("the simulated link above it (`-` for a root link), `to child` the angle to ")
                .append("the link below it, and `anchor vs rest` whether the anchor is on the same ")
                .append("side of the pivot as the mass (`+`) or the opposite one (`-`, which turns ")
                .append("the piece about a point its mass is not on).\n\n")
                .append("| link | |rest| (lever) | pivot | anchor | |anchor-pivot| | rest | down | ")
                .append("to parent | to child | anchor vs rest |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (String name : followed) {
            Segment piece = maid.segmentNamed(name);
            if (piece == null) {
                continue;
            }
            Segment parent = piece.parent >= 0 ? maid.segmentByIndex.get(piece.parent) : null;
            List<Segment> children = children(maid, piece);
            Vector3f toParent = parent == null ? null
                    : new Vector3f(parent.bindPivot).sub(piece.bindPivot);
            Vector3f toChild = children.isEmpty() ? null
                    : new Vector3f(children.get(0).bindPivot).sub(piece.bindPivot);
            Vector3f toAnchor = new Vector3f(piece.bindAnchor).sub(piece.bindPivot);
            float side = piece.bindRest.dot(toAnchor);
            report.append("| `").append(name).append("` | ").append(fmt(piece.lever))
                    .append(" | ").append(point(piece.bindPivot))
                    .append(" | ").append(point(piece.bindAnchor))
                    .append(" | ").append(fmt(toAnchor.length()))
                    .append(" | ").append(point(piece.bindRest))
                    .append(" | ").append(fmt(angleBetweenDegrees(piece.bindRest, DOWN)))
                    .append(" | ").append(toParent == null ? "-"
                            : fmt(angleBetweenDegrees(piece.bindRest, toParent)))
                    .append(" | ").append(toChild == null ? "-"
                            : fmt(angleBetweenDegrees(piece.bindRest, toChild)))
                    .append(" | ").append(Math.abs(side) < 1.0E-6F ? "0" : (side > 0.0F ? "+" : "-"))
                    .append(" |\n");
        }
        report.append('\n');

        // ---- 4. the candidates ------------------------------------------------------------------
        report.append("## 4. The candidates for the root, measured\n\n")
                .append("Every candidate over the same sprint, ").append(GAIT_FRAMES)
                .append(" frames. `cost` is the largest distance any vertex of a piece the ")
                .append("candidate does not name - and that does not hang under one it does - is in ")
                .append("a different place from the before run at any measured frame.\n\n")
                .append("| candidate | link | own | allowed | pinned | drawn | clipped | whole | ")
                .append("travel | cost |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < ROOT_CANDIDATES.length; i++) {
            Candidate candidate = ROOT_CANDIDATES[i];
            for (String name : join(TAIL_LINKS, REFERENCE_LINKS)) {
                Trace trace = traceNamed(runs.get(i), name);
                if (trace == null) {
                    continue;
                }
                report.append("| ").append(candidate.label()).append(" | `").append(name)
                        .append("` | ").append(fmt(trace.ownMean()))
                        .append(" | ").append(fmt(trace.allowedMean()))
                        .append(" | ").append(fmt(trace.saturatedShare()))
                        .append(" | ").append(fmt(trace.drawnRange()))
                        .append(" | ").append(fmt(trace.clippedShare()))
                        .append(" | ").append(fmt(trace.composedMax()))
                        .append(" | ").append(fmt(trace.tipSpeed()))
                        .append(" | ").append(fmt(costs.get(i))).append(" |\n");
            }
        }
        report.append('\n');

        // The shortlist's transients: the four root ceilings and the narrowest multi-link one, which
        // is what decides whether a ceiling on the bone the user names bounds the overshoot.
        for (Candidate candidate : ROOT_CANDIDATES) {
            if (!candidate.label().startsWith("shipped + limitDeg")) {
                continue;
            }
            Step stop = runStep(maid, leanSign, 0.0F, SPRINT_SPEED, 0.0F, candidate.rigid(),
                    candidate.limitDeg());
            report.append(stepReport("stop, " + candidate.label(), stop));
        }
        report.append('\n');

        // ---- the third key, measured before it is designed --------------------------------------
        report.append("### The third key: per-bone damping, measured before it is designed\n\n")
                .append("The solver takes a damping ratio per piece - ")
                .append(fmt((float) YsmPhysicsTuning.DEFAULTS.dampingRatio()))
                .append(" for every piece of every model today - and clamps it to 0..1. The rows ")
                .append("below re-run the sprint with the **shipped** ceilings in force and a ")
                .append("different ratio for the tail's links: 0.50 is the negative control (if more ")
                .append("damping helps, less must hurt), and 1.00 is as much as the solver accepts. ")
                .append("The transient is the same stop step as above.\n\n")
                .append("| damping | link | own | drawn | clipped | whole | travel | stop ")
                .append("overshoot | stop reach | stop travel |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (DampingCandidate candidate : DAMPING_CANDIDATES) {
            List<Trace> traces = tracesNamed(maid, all);
            runGait(maid, new RunPose(maid, SPRINT, leanSign), SPRINT, traces, RIGID_ONLY,
                    TIP_CEILING_8, candidate.ratio());
            Step stop = runStep(maid, leanSign, 0.0F, SPRINT_SPEED, 0.0F, RIGID_ONLY, TIP_CEILING_8,
                    candidate.ratio());
            for (String name : TAIL_LINKS) {
                Trace trace = traceNamed(traces, name);
                StepTrace transientTrace = null;
                for (StepTrace candidateTrace : stop.traces) {
                    if (candidateTrace.piece.name.equals(name)) {
                        transientTrace = candidateTrace;
                    }
                }
                if (trace == null || transientTrace == null) {
                    continue;
                }
                report.append("| ").append(candidate.label()).append(" | `").append(name)
                        .append("` | ").append(fmt(trace.ownMean()))
                        .append(" | ").append(fmt(trace.drawnRange()))
                        .append(" | ").append(fmt(trace.clippedShare()))
                        .append(" | ").append(fmt(trace.composedMax()))
                        .append(" | ").append(fmt(trace.tipSpeed()))
                        .append(" | ").append(fmt3(overshootOf(transientTrace)))
                        .append(" | ").append(fmt3(reachOf(transientTrace)))
                        .append(" | ").append(fmt(travelOf(transientTrace)))
                        .append(" |\n");
            }
        }
        report.append('\n');

        Path out = Paths.get("build", "reports", "ysm-tail-root-measurement.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println("YSM-EF probe wrote " + out.toAbsolutePath() + " ("
                + report.length() + " chars)");

        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(0, problems.size());
    }

    // ---- the step transient --------------------------------------------------------------------

    /** One link followed through a step: its own far vertex frame by frame, and its own swing. */
    private static final class StepTrace {
        final Segment piece;
        final Vector3f vertex;
        final List<Vector3f> settled = new ArrayList<>();
        final List<Vector3f> path = new ArrayList<>();
        final List<Float> own = new ArrayList<>();
        final List<Float> raw = new ArrayList<>();
        final List<Float> ownSettled = new ArrayList<>();

        StepTrace(Segment piece) {
            this.piece = piece;
            this.vertex = farthestVertex(piece.own, piece.bindPivot);
        }

        void settle(YsmMeshSecondaryMotion.State state) {
            settled.add(YsmMeshSecondaryMotion.transformPoint(state.deltas[piece.index], vertex,
                    new Vector3f()));
            ownSettled.add(state.lastDegrees[piece.index]);
        }

        void frame(YsmMeshSecondaryMotion.State state) {
            path.add(YsmMeshSecondaryMotion.transformPoint(state.deltas[piece.index], vertex,
                    new Vector3f()));
            own.add(state.lastDegrees[piece.index]);
            raw.add((float) Math.toDegrees(state.states[piece.index].lastAngle));
        }
    }

    /** One step run: the traces, each holding where the link was and where it went. */
    private static final class Step {
        final float fromSpeed;
        final float toSpeed;
        final List<StepTrace> traces;

        Step(float fromSpeed, float toSpeed, List<StepTrace> traces) {
            this.fromSpeed = fromSpeed;
            this.toSpeed = toSpeed;
            this.traces = traces;
        }
    }

    /**
     * A step: settle at one body velocity with the pose frozen, then step the velocity and follow.
     *
     * <p>The pose is frozen for both halves - the same matrices every frame - so the only input that
     * changes at the step is the body's velocity, and what is measured is the chain's own response to
     * it rather than a gait cycle's drive.
     */
    private static Step runStep(Rig rig, float leanSign, float phase, float fromSpeed, float toSpeed,
                                Set<String> rigid, Map<String, Float> ceilings) {
        return runStep(rig, leanSign, phase, fromSpeed, toSpeed, rigid, ceilings, Map.of());
    }

    /**
     * A step: settle at one body velocity with the pose frozen, then step the velocity and follow.
     *
     * <p>The pose is frozen for both halves - the same matrices every frame - so the only input that
     * changes at the step is the body's velocity, and what is measured is the chain's own response to
     * it rather than a gait cycle's drive.
     */
    private static Step runStep(Rig rig, float leanSign, float phase, float fromSpeed, float toSpeed,
                                Set<String> rigid, Map<String, Float> ceilings,
                                Map<String, Float> damping) {
        YsmPhysicsParts.Model parts = productionModel(rig, damping);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                parts, null, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        for (int i = 0; i < parts.segments().length; i++) {
            String name = parts.segments()[i].boneName();
            state.held[i] = rigid.contains(name);
            Float degrees = name == null ? null : ceilings.get(name.toLowerCase(Locale.ROOT));
            if (degrees != null) {
                state.limit[i] = (float) Math.toRadians(degrees);
            }
        }
        RunPose pose = new RunPose(rig, SPRINT, leanSign);
        pose.at(phase);
        List<StepTrace> traces = new ArrayList<>();
        for (String name : join(TAIL_LINKS, REFERENCE_LINKS)) {
            Segment piece = rig.segmentNamed(name);
            if (piece != null) {
                traces.add(new StepTrace(piece));
            }
        }
        Vector3f velocity = new Vector3f(0.0F, 0.0F, -fromSpeed);
        for (int frame = 0; frame < STEP_SETTLE_FRAMES; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, DT, velocity, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
            if (frame >= STEP_SETTLE_FRAMES - STEP_TAIL_FRAMES) {
                for (StepTrace trace : traces) {
                    trace.settle(state);
                }
            }
        }
        velocity = new Vector3f(0.0F, 0.0F, -toSpeed);
        for (int frame = 0; frame < STEP_FRAMES; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, DT, velocity, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
            for (StepTrace trace : traces) {
                trace.frame(state);
            }
        }
        return new Step(fromSpeed, toSpeed, traces);
    }

    /** Every link's transient numbers, as a table. */
    private static String stepReport(String label, Step step) {
        StringBuilder out = new StringBuilder();
        out.append("### `").append(label).append("`\n\n")
                .append("| link | step | overshoot | reach | own before | own settled | own peak | ")
                .append("own peak/settled | own raw peak | clipped | 5% time | travel |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (StepTrace trace : step.traces) {
            float stepSize = stepOf(trace);
            float overshoot = overshootOf(trace);
            float ownPeak = 0.0F;
            for (float value : trace.own) {
                ownPeak = Math.max(ownPeak, value);
            }
            float rawPeak = 0.0F;
            for (float value : trace.raw) {
                rawPeak = Math.max(rawPeak, value);
            }
            int clipped = 0;
            for (int i = 0; i < trace.own.size() && i < trace.raw.size(); i++) {
                if (trace.raw.get(i) - trace.own.get(i) > 0.1F) {
                    clipped++;
                }
            }
            float ownBefore = tailMean(trace.ownSettled, STEP_TAIL_FRAMES);
            float ownSettled = tailMean(trace.own, STEP_TAIL_FRAMES);
            out.append("| `").append(trace.piece.name).append("` | ")
                    .append(stepSize > 1.0E-4F ? fmt(stepSize) : "-").append(" | ")
                    .append(stepSize > 1.0E-4F ? fmt3(overshoot) : "-").append(" | ")
                    .append(stepSize > 1.0E-4F ? fmt3(reachOf(trace)) : "-")
                    .append(" | ").append(fmt(ownBefore))
                    .append(" | ").append(fmt(ownSettled))
                    .append(" | ").append(fmt(ownPeak))
                    .append(" | ").append(ownSettled > 0.1F ? fmt(ownPeak / ownSettled) : "-")
                    .append(" | ").append(fmt(rawPeak))
                    .append(" | ").append(trace.own.isEmpty() ? "0.000"
                            : fmt((float) clipped / trace.own.size()))
                    .append(" | ").append(fmt(setttlingTimeOf(trace))).append(" s")
                    .append(" | ").append(fmt(travelOf(trace)))
                    .append(" |\n");
        }
        return out.append('\n').toString();
    }

    /**
     * The overshoot read without a direction: how much further from its rest the link ever is than
     * where it started.
     *
     * <p>The projection above can only see an overshoot along the line between the two settled
     * places; a link that swings out and back around a turning axis - which is what a pendulum under
     * a moving pivot does - moves off that line and the projection reads nothing. This reads the
     * distance from the rest point itself, so any excursion past it counts, whichever way it goes.
     */
    private static float reachOf(StepTrace trace) {
        float stepSize = stepOf(trace);
        if (!(stepSize > 1.0E-4F)) {
            return 0.0F;
        }
        Vector3f rest = restPoint(trace);
        float worst = 0.0F;
        for (Vector3f at : trace.path) {
            worst = Math.max(worst, at.distance(rest));
        }
        return Math.max(0.0F, worst - stepSize) / stepSize;
    }

    /** The same numbers, before against after, for the links that changed most. */
    private static String stepComparison(String label, Step before, Step after) {
        StringBuilder out = new StringBuilder();
        out.append("### `").append(label).append("`: before against after\n\n")
                .append("| link | overshoot before | overshoot after | reach before | reach after | ")
                .append("own peak before | own peak after | 5% time before | 5% time after | travel ")
                .append("before | travel after |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < before.traces.size() && i < after.traces.size(); i++) {
            StepTrace b = before.traces.get(i);
            StepTrace a = after.traces.get(i);
            if (!b.piece.name.equals(a.piece.name)) {
                continue;
            }
            float peakBefore = 0.0F;
            for (float value : b.own) {
                peakBefore = Math.max(peakBefore, value);
            }
            float peakAfter = 0.0F;
            for (float value : a.own) {
                peakAfter = Math.max(peakAfter, value);
            }
            out.append("| `").append(b.piece.name).append("` | ").append(fmt3(overshootOf(b)))
                    .append(" | ").append(fmt3(overshootOf(a)))
                    .append(" | ").append(fmt3(reachOf(b))).append(" | ").append(fmt3(reachOf(a)))
                    .append(" | ").append(fmt(peakBefore)).append(" | ").append(fmt(peakAfter))
                    .append(" | ").append(fmt(setttlingTimeOf(b))).append(" s | ")
                    .append(fmt(setttlingTimeOf(a))).append(" s")
                    .append(" | ").append(fmt(travelOf(b))).append(" | ").append(fmt(travelOf(a)))
                    .append(" |\n");
        }
        return out.append('\n').toString();
    }

    /** The step the link is asked to travel: how far its rest point moves. */
    private static float stepOf(StepTrace trace) {
        return new Vector3f(meanPoint(trace.settled)).sub(restPoint(trace)).length();
    }

    /** Where the link comes to rest: the mean of the last frames of the step. */
    private static Vector3f restPoint(StepTrace trace) {
        int from = Math.max(0, trace.path.size() - STEP_TAIL_FRAMES);
        return meanPoint(trace.path.subList(from, trace.path.size()));
    }

    /**
     * How far past its rest the link travels, as a fraction of the step: the overshoot.
     *
     * <p>Zero when the link approaches its rest from one side and stops there, which is what a
     * critically damped piece does; the value is the extreme of the projection of the path beyond
     * the rest point, divided by the size of the step, so it is the classic overshoot ratio.
     */
    private static float overshootOf(StepTrace trace) {
        float stepSize = stepOf(trace);
        if (!(stepSize > 1.0E-4F)) {
            return 0.0F;
        }
        Vector3f direction = new Vector3f(meanPoint(trace.settled)).sub(restPoint(trace)).div(stepSize);
        Vector3f rest = restPoint(trace);
        float worst = 0.0F;
        for (Vector3f at : trace.path) {
            worst = Math.max(worst, -new Vector3f(at).sub(rest).dot(direction));
        }
        return worst / stepSize;
    }

    /** How many times the link passes through its rest point, with a two per cent deadband. */
    private static int passesOf(StepTrace trace) {
        float stepSize = stepOf(trace);
        if (!(stepSize > 1.0E-4F)) {
            return 0;
        }
        Vector3f direction = new Vector3f(meanPoint(trace.settled)).sub(restPoint(trace)).div(stepSize);
        Vector3f rest = restPoint(trace);
        int passes = 0;
        int sign = 0;
        for (Vector3f at : trace.path) {
            float d = new Vector3f(at).sub(rest).dot(direction);
            int now = d > 0.02F * stepSize ? 1 : (d < -0.02F * stepSize ? -1 : 0);
            if (now != 0) {
                if (sign != 0 && now != sign) {
                    passes++;
                }
                sign = now;
            }
        }
        return passes;
    }

    /** The damping time: the last frame at which the link is more than five per cent off its rest. */
    private static float setttlingTimeOf(StepTrace trace) {
        float stepSize = stepOf(trace);
        if (!(stepSize > 1.0E-4F)) {
            return 0.0F;
        }
        Vector3f direction = new Vector3f(meanPoint(trace.settled)).sub(restPoint(trace)).div(stepSize);
        Vector3f rest = restPoint(trace);
        float last = 0.0F;
        for (int i = 0; i < trace.path.size(); i++) {
            float d = Math.abs(new Vector3f(trace.path.get(i)).sub(rest).dot(direction));
            if (d > 0.05F * stepSize) {
                last = (i + 1) * DT;
            }
        }
        return last;
    }

    /** The path length the far vertex covers during the step. */
    private static float travelOf(StepTrace trace) {
        float travel = 0.0F;
        for (int i = 1; i < trace.path.size(); i++) {
            travel += trace.path.get(i).distance(trace.path.get(i - 1));
        }
        return travel;
    }

    /** The mean of the last {@code count} values of a list. */
    private static float tailMean(List<Float> values, int count) {
        int from = Math.max(0, values.size() - count);
        double sum = 0.0D;
        for (int i = from; i < values.size(); i++) {
            sum += values.get(i);
        }
        return values.size() <= from ? 0.0F : (float) (sum / (values.size() - from));
    }

    private static Vector3f meanPoint(List<Vector3f> points) {
        Vector3f out = new Vector3f();
        int used = 0;
        for (Vector3f point : points) {
            if (point == null || !YsmDynamicBoneSolver.isFinite(point)) {
                continue;
            }
            out.add(point);
            used++;
        }
        return used == 0 ? out : out.div(used);
    }

    /** The traces of the pieces named, in the order named, skipping names this model has not got. */
    private static List<Trace> tracesNamed(Rig rig, String[] names) {
        List<Trace> out = new ArrayList<>();
        for (String name : names) {
            Segment piece = rig.segmentNamed(name);
            if (piece != null) {
                out.add(new Trace(piece, farthestVertex(piece.own, piece.bindPivot)));
            }
        }
        return out;
    }

    private static Trace traceNamed(List<Trace> traces, String name) {
        for (Trace trace : traces) {
            if (trace.piece.name.equals(name)) {
                return trace;
            }
        }
        return null;
    }

    private static String[] allNames(Rig rig) {
        String[] out = new String[rig.segments.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = rig.segments.get(i).name;
        }
        return out;
    }

    private static String[] join(String[] first, String[] second) {
        String[] out = new String[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    /**
     * The largest distance a piece the candidate does not name is in a different place.
     *
     * <p>Measured per frame over the whole run and on the same vertex the trace follows, so a piece
     * that is a millimetre out for one frame counts and a piece that is not drawn differently at all
     * reads zero - which is the claim the candidates make about everything they do not name.
     */
    private static float worstUnnamedDifference(Rig rig, Candidate candidate,
                                                List<Trace> baseline, List<Trace> other) {
        Set<Integer> named = namedIndexes(rig, candidate);
        float worst = 0.0F;
        for (int i = 0; i < baseline.size() && i < other.size(); i++) {
            Segment piece = baseline.get(i).piece;
            if (candidate.rigid().contains(piece.name)
                    || candidate.limitDeg().containsKey(piece.name.toLowerCase(Locale.ROOT))
                    || descendsFrom(rig, piece.index, named)) {
                continue;
            }
            List<Vector3f> b = baseline.get(i).tipPath;
            List<Vector3f> a = other.get(i).tipPath;
            int frames = Math.min(b.size(), a.size());
            for (int frame = 0; frame < frames; frame++) {
                worst = Math.max(worst, b.get(frame).distance(a.get(frame)));
            }
        }
        return worst;
    }

    /** A signed number, for the change columns. */
    private static String signed(float value) {
        return String.format(Locale.ROOT, "%+.3f", value);
    }

    /** Three decimals with a digit more than {@link #fmt}, for the overshoot ratios. */
    private static String fmt3(float value) {
        return Float.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "n/a";
    }

    // ---- the frame's geometry ------------------------------------------------------------------

    /** The body's own vertical axis, from the two hip joints the armature settled. */
    private static Vector3f bodyAxis(Rig rig) {
        Vector3f left = rig.jointOrigin(JointTable.THIGH_L);
        Vector3f right = rig.jointOrigin(JointTable.THIGH_R);
        if (left == null || right == null) {
            return new Vector3f();
        }
        return new Vector3f((left.x + right.x) * 0.5F, 0.0F, (left.z + right.z) * 0.5F);
    }

    /** A rotation of {@code degrees} about {@code origin} and the model's own x axis, plus a bob. */
    private static OpenMatrix4f about(Vector3f origin, float degrees, float bob) {
        if (origin == null) {
            return new OpenMatrix4f();
        }
        Matrix4f matrix = new Matrix4f()
                .translate(origin.x, origin.y + bob, origin.z)
                .rotateX((float) Math.toRadians(degrees))
                .translate(-origin.x, -origin.y, -origin.z);
        return toOpen(matrix);
    }

    /** The piece's own surface as sampled points: every mesh triangle, barycentrically subdivided. */
    private static float[] surfaceSamples(List<Vector3f> own, OpenMatrix4f delta) {
        List<Float> points = new ArrayList<>();
        int n = SURFACE_SUBDIVISION;
        Vector3f moved = new Vector3f();
        for (int i = 0; i + 2 < own.size(); i += 3) {
            Vector3f a = own.get(i);
            Vector3f b = own.get(i + 1);
            Vector3f c = own.get(i + 2);
            if (a == null || b == null || c == null) {
                continue;
            }
            for (int u = 0; u <= n; u++) {
                for (int v = 0; v + u <= n; v++) {
                    float wa = (float) u / n;
                    float wb = (float) v / n;
                    float wc = 1.0F - wa - wb;
                    moved.set(a.x * wa + b.x * wb + c.x * wc,
                            a.y * wa + b.y * wb + c.y * wc,
                            a.z * wa + b.z * wb + c.z * wc);
                    if (delta != null) {
                        YsmMeshSecondaryMotion.transformPoint(delta, moved, moved);
                    }
                    if (!YsmDynamicBoneSolver.isFinite(moved)) {
                        continue;
                    }
                    points.add(moved.x);
                    points.add(moved.y);
                    points.add(moved.z);
                }
            }
        }
        float[] out = new float[points.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = points.get(i);
        }
        return out;
    }

    /** The cell key: azimuth bin in the high half, height bin in the low half. */
    private static long cellKey(float x, float y, float z, Vector3f axis) {
        float dx = x - axis.x;
        float dz = z - axis.z;
        float degrees = (float) Math.toDegrees(Math.atan2(dz, dx));
        if (degrees < 0.0F) {
            degrees += 360.0F;
        }
        int azimuth = Math.min(179, (int) (degrees / LADDER_DEGREES));
        int height = Math.max(0, Math.min(1023, (int) Math.floor((y + 4.0F) / LADDER_HEIGHT)));
        return (long) azimuth * 1024L + height;
    }

    /** The azimuth a cell key names, degrees. */
    private static float azimuthDegrees(long key) {
        return (key / 1024L) * LADDER_DEGREES;
    }

    /** One piece's surface as a map from cell to the radial interval it occupies there. */
    private static Map<Long, float[]> cellsOf(float[] samples, OpenMatrix4f delta, Vector3f axis) {
        Map<Long, float[]> out = new HashMap<>();
        Vector3f point = new Vector3f();
        for (int i = 0; i + 2 < samples.length; i += 3) {
            point.set(samples[i], samples[i + 1], samples[i + 2]);
            if (delta != null) {
                YsmMeshSecondaryMotion.transformPoint(delta, point, point);
            }
            float r = (float) Math.hypot(point.x - axis.x, point.z - axis.z);
            long key = cellKey(point.x, point.y, point.z, axis);
            float[] interval = out.get(key);
            if (interval == null) {
                out.put(key, new float[]{r, r});
            } else {
                if (r < interval[0]) {
                    interval[0] = r;
                }
                if (r > interval[1]) {
                    interval[1] = r;
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, float[]>[] cellMaps(float[][] samples, OpenMatrix4f[] deltas, Vector3f axis) {
        Map<Long, float[]>[] out = new Map[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = samples[i] == null ? new HashMap<>() : cellsOf(samples[i], deltas == null ? null : deltas[i], axis);
        }
        return out;
    }

    /** The same, for the few pieces a per-frame measurement tracks: every other slot stays null. */
    @SuppressWarnings("unchecked")
    private static Map<Long, float[]>[] cellMapsOf(float[][] samples, int[] subset, Vector3f axis) {
        Map<Long, float[]>[] out = new Map[samples.length];
        for (int index : subset) {
            out[index] = cellsOf(samples[index], null, axis);
        }
        return out;
    }

    // ---- the crossing --------------------------------------------------------------------------

    /**
     * One surface the crossing test is run over: a name, the geometry it is drawn from, and the
     * delta the state applies to it - or null for a piece the simulation does not move, which
     * therefore follows the pose exactly and sits where it was authored.
     */
    private record SurfaceRef(String name, List<Vector3f> own, OpenMatrix4f delta) {}

    /**
     * One piece's own surface as a closed triangle shell in the frame of one state, with the
     * bounding box that lets a pair be skipped before any ray is cast.
     */
    private static final class Solid {
        final float[] vertices;
        final float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        final int vertexCount;

        Solid(int vertices) {
            this.vertices = new float[vertices * 3];
            this.vertexCount = vertices;
        }
    }

    /** How far one piece reaches into another: the count of vertices inside, and the deepest. */
    private record Crossing(int a, int b, int meetings, int insideA, float depthA, int insideB,
                            float depthB, float azimuth, float height) {
        float depth() {
            return Math.max(depthA, depthB);
        }
    }

    /**
     * The triangles of a piece, from the list the physics reader hands over.
     *
     * <p>The mesh lays a part out as its vertex ordinals with <b>stride three</b> - each ordinal
     * written three times, once per position component - so the reader's own list holds every vertex
     * three times over and its consecutive triples are degenerate. Every vertex-level statistic
     * survives that (a centroid, a y range, a lever and a pivot gap are all the same over a tripled
     * cloud), which is why five earlier rounds could calibrate against the client through this
     * reader without noticing. Triangles do not: they need every third entry taken, which is what
     * this does, and only here, so the calibrated reader is left exactly as it was.
     *
     * <p>A list that is not so packed (the hand-built controls) is returned unchanged.
     */
    private static List<Vector3f> triangleSoup(List<Vector3f> own) {
        if (own.size() < 9 || own.size() % 3 != 0) {
            return own;
        }
        List<Vector3f> out = new ArrayList<>(own.size() / 3);
        for (int i = 0; i + 2 < own.size(); i += 3) {
            Vector3f first = own.get(i);
            if (!first.equals(own.get(i + 1)) || !first.equals(own.get(i + 2))) {
                return own;
            }
            out.add(first);
        }
        return out;
    }

    /** Every surface of one state, transformed by that state's own delta where it has one. */
    private static Solid[] solidsOf(List<SurfaceRef> surfaces) {
        Solid[] out = new Solid[surfaces.size()];
        Vector3f moved = new Vector3f();
        for (int s = 0; s < surfaces.size(); s++) {
            SurfaceRef ref = surfaces.get(s);
            List<Vector3f> own = ref.own();
            int count = own.size() - own.size() % 3;
            Solid solid = new Solid(count);
            for (int v = 0; v < count; v++) {
                Vector3f vertex = own.get(v);
                moved.set(vertex);
                if (ref.delta() != null) {
                    YsmMeshSecondaryMotion.transformPoint(ref.delta(), moved, moved);
                }
                solid.vertices[v * 3] = moved.x;
                solid.vertices[v * 3 + 1] = moved.y;
                solid.vertices[v * 3 + 2] = moved.z;
                if (moved.x < solid.bounds[0]) {
                    solid.bounds[0] = moved.x;
                }
                if (moved.y < solid.bounds[1]) {
                    solid.bounds[1] = moved.y;
                }
                if (moved.z < solid.bounds[2]) {
                    solid.bounds[2] = moved.z;
                }
                if (moved.x > solid.bounds[3]) {
                    solid.bounds[3] = moved.x;
                }
                if (moved.y > solid.bounds[4]) {
                    solid.bounds[4] = moved.y;
                }
                if (moved.z > solid.bounds[5]) {
                    solid.bounds[5] = moved.z;
                }
            }
            out[s] = solid;
        }
        return out;
    }

    /**
     * Whether a point is inside the shell: parity along three <b>skewed</b> rays drawn from the
     * point, majority. Skewed rather than axis-aligned because an axis ray through a boxy part
     * regularly passes exactly along a face diagonal, and a crossing counted twice is a crossing
     * counted zero times - which the control below caught on the first run.
     */
    private static boolean inside(Solid solid, float x, float y, float z) {
        if (x < solid.bounds[0] || x > solid.bounds[3] || y < solid.bounds[1] || y > solid.bounds[4]
                || z < solid.bounds[2] || z > solid.bounds[5]) {
            return false;
        }
        int votes = 0;
        for (int ray = 0; ray < 3; ray++) {
            if (crossingsAlong(solid, x, y, z, RAY_DIRECTIONS[ray]) % 2 != 0) {
                votes++;
            }
        }
        return votes >= 2;
    }

    /** Three directions with no exact relation to a box's own axes or diagonals. */
    private static final float[][] RAY_DIRECTIONS = {
            {1.0F, 0.3711F, 0.6187F}, {0.5331F, 1.0F, 0.2917F}, {0.7103F, 0.4317F, 1.0F}};

    /** How many triangles a ray from the point crosses: Moller-Trumbore, no backface cull. */
    private static int crossingsAlong(Solid solid, float x, float y, float z, float[] direction) {
        int hits = 0;
        float dx = direction[0];
        float dyy = direction[1];
        float dz = direction[2];
        for (int t = 0; t + 8 < solid.vertices.length; t += 9) {
            float ax = solid.vertices[t];
            float ay = solid.vertices[t + 1];
            float az = solid.vertices[t + 2];
            float e1x = solid.vertices[t + 3] - ax;
            float e1y = solid.vertices[t + 4] - ay;
            float e1z = solid.vertices[t + 5] - az;
            float e2x = solid.vertices[t + 6] - ax;
            float e2y = solid.vertices[t + 7] - ay;
            float e2z = solid.vertices[t + 8] - az;
            float px = dyy * e2z - dz * e2y;
            float py = dz * e2x - dx * e2z;
            float pz = dx * e2y - dyy * e2x;
            float determinant = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(determinant) < 1.0E-12F) {
                continue;
            }
            float inverse = 1.0F / determinant;
            float tx = x - ax;
            float ty = y - ay;
            float tz = z - az;
            float u = (tx * px + ty * py + tz * pz) * inverse;
            if (u <= 0.0F || u >= 1.0F) {
                continue;
            }
            float qx = ty * e1z - tz * e1y;
            float qy = tz * e1x - tx * e1z;
            float qz = tx * e1y - ty * e1x;
            float v = (dx * qx + dyy * qy + dz * qz) * inverse;
            if (v <= 0.0F || u + v >= 1.0F) {
                continue;
            }
            float along = (e2x * qx + e2y * qy + e2z * qz) * inverse;
            if (along > 1.0E-6F) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * The shortest way out of the shell along the six axis directions: how deep the point is in.
     *
     * <p>Axis directions are the right choice here even though they are the wrong choice for the
     * parity test: the depth is the nearest exit, and a direction that happens to graze a face
     * diagonal still reports the distance it grazed at, while a direction that misses entirely
     * simply does not win the minimum.
     */
    private static float exitDepth(Solid solid, float x, float y, float z) {
        float best = Float.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                float nearest = firstHit(solid, x, y, z, axis, sign);
                if (nearest < best) {
                    best = nearest;
                }
            }
        }
        return best == Float.MAX_VALUE ? 0.0F : best;
    }

    private static float firstHit(Solid solid, float x, float y, float z, int axis, int sign) {
        float nearest = Float.MAX_VALUE;
        for (int t = 0; t + 8 < solid.vertices.length; t += 9) {
            float ax = solid.vertices[t];
            float ay = solid.vertices[t + 1];
            float az = solid.vertices[t + 2];
            float e1x = solid.vertices[t + 3] - ax;
            float e1y = solid.vertices[t + 4] - ay;
            float e1z = solid.vertices[t + 5] - az;
            float e2x = solid.vertices[t + 6] - ax;
            float e2y = solid.vertices[t + 7] - ay;
            float e2z = solid.vertices[t + 8] - az;
            float dx = axis == 0 ? sign : 0.0F;
            float dy = axis == 1 ? sign : 0.0F;
            float dz = axis == 2 ? sign : 0.0F;
            float px = dy * e2z - dz * e2y;
            float py = dz * e2x - dx * e2z;
            float pz = dx * e2y - dy * e2x;
            float determinant = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(determinant) < 1.0E-9F) {
                continue;
            }
            float inverse = 1.0F / determinant;
            float tx = x - ax;
            float ty = y - ay;
            float tz = z - az;
            float u = (tx * px + ty * py + tz * pz) * inverse;
            if (u < 0.0F || u > 1.0F) {
                continue;
            }
            float qx = ty * e1z - tz * e1y;
            float qy = tz * e1x - tx * e1z;
            float qz = tx * e1y - ty * e1x;
            float v = (dx * qx + dy * qy + dz * qz) * inverse;
            if (v < 0.0F || u + v > 1.0F) {
                continue;
            }
            float along = (e2x * qx + e2y * qy + e2z * qz) * inverse;
            if (along > 1.0E-6F && along < nearest) {
                nearest = along;
            }
        }
        return nearest;
    }

    /**
     * Whether the two surfaces cross, and how far one reaches into the other.
     *
     * <p>Two questions, because they answer different shapes of defect and neither implies the
     * other. <b>Do the surfaces meet</b> is the crossing test proper: an edge of one triangle that
     * passes through the <i>interior</i> of a triangle of the other, in either direction. Two thin
     * panels crossing like an X have none of each other's vertices inside, so a containment test
     * alone would call them clean - which is why both are asked. Excluding the triangle boundaries
     * is what keeps panels that merely share an edge from reading as crossings. <b>How deep</b> is
     * the containment test: a vertex of one inside the other's shell, and the shortest way out.
     *
     * <p>Reads the location of the first meeting or the deepest vertex, for the report.
     */
    private static Crossing crossing(Solid a, Solid b, int indexA, int indexB, Vector3f axis) {
        if (!overlaps(a.bounds, b.bounds)) {
            return null;
        }
        float[] where = new float[3];
        int meetings = meetings(a, b, where) + meetings(b, a, where);
        int insideA = 0;
        int insideB = 0;
        float depthA = 0.0F;
        float depthB = 0.0F;
        float worstGround = -1.0F;
        float worstX = 0.0F;
        float worstY = 0.0F;
        float worstZ = 0.0F;
        for (int v = 0; v < a.vertexCount; v++) {
            float x = a.vertices[v * 3];
            float y = a.vertices[v * 3 + 1];
            float z = a.vertices[v * 3 + 2];
            if (!inside(b, x, y, z)) {
                continue;
            }
            insideA++;
            float depth = exitDepth(b, x, y, z);
            depthA = Math.max(depthA, depth);
            if (depth > worstGround) {
                worstGround = depth;
                worstX = x;
                worstY = y;
                worstZ = z;
            }
        }
        for (int v = 0; v < b.vertexCount; v++) {
            float x = b.vertices[v * 3];
            float y = b.vertices[v * 3 + 1];
            float z = b.vertices[v * 3 + 2];
            if (!inside(a, x, y, z)) {
                continue;
            }
            insideB++;
            float depth = exitDepth(a, x, y, z);
            depthB = Math.max(depthB, depth);
            if (depth > worstGround) {
                worstGround = depth;
                worstX = x;
                worstY = y;
                worstZ = z;
            }
        }
        if (meetings == 0 && insideA == 0 && insideB == 0) {
            return null;
        }
        float x = meetings > 0 ? where[0] : worstX;
        float y = meetings > 0 ? where[1] : worstY;
        float z = meetings > 0 ? where[2] : worstZ;
        float degrees = (float) Math.toDegrees(Math.atan2(z - axis.z, x - axis.x));
        if (degrees < 0.0F) {
            degrees += 360.0F;
        }
        return new Crossing(indexA, indexB, meetings, insideA, depthA, insideB, depthB, degrees, y);
    }

    /**
     * How many edges of {@code from} pass through the interior of a triangle of {@code to}, with the
     * first such point written to {@code where}.
     */
    private static int meetings(Solid from, Solid to, float[] where) {
        int count = 0;
        for (int t = 0; t + 8 < from.vertices.length; t += 9) {
            for (int corner = 0; corner < 3; corner++) {
                int p = t + corner * 3;
                int q = t + ((corner + 1) % 3) * 3;
                if (segmentMeets(to, from.vertices[p], from.vertices[p + 1], from.vertices[p + 2],
                        from.vertices[q], from.vertices[q + 1], from.vertices[q + 2],
                        count == 0 ? where : null)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Whether the segment p-q passes through the interior of a triangle of the shell. */
    private static boolean segmentMeets(Solid solid, float px, float py, float pz,
                                        float qx, float qy, float qz, float[] where) {
        float dx = qx - px;
        float dyy = qy - py;
        float dz = qz - pz;
        for (int t = 0; t + 8 < solid.vertices.length; t += 9) {
            float ax = solid.vertices[t];
            float ay = solid.vertices[t + 1];
            float az = solid.vertices[t + 2];
            float e1x = solid.vertices[t + 3] - ax;
            float e1y = solid.vertices[t + 4] - ay;
            float e1z = solid.vertices[t + 5] - az;
            float e2x = solid.vertices[t + 6] - ax;
            float e2y = solid.vertices[t + 7] - ay;
            float e2z = solid.vertices[t + 8] - az;
            float rx = dyy * e2z - dz * e2y;
            float ry = dz * e2x - dx * e2z;
            float rz = dx * e2y - dyy * e2x;
            float determinant = e1x * rx + e1y * ry + e1z * rz;
            if (Math.abs(determinant) < 1.0E-12F) {
                continue;
            }
            float inverse = 1.0F / determinant;
            float tx = px - ax;
            float ty = py - ay;
            float tz = pz - az;
            float u = (tx * rx + ty * ry + tz * rz) * inverse;
            if (u <= 0.0F || u >= 1.0F) {
                continue;
            }
            float sx = ty * e1z - tz * e1y;
            float sy = tz * e1x - tx * e1z;
            float sz = tx * e1y - ty * e1x;
            float v = (dx * sx + dyy * sy + dz * sz) * inverse;
            if (v <= 0.0F || u + v >= 1.0F) {
                continue;
            }
            float along = (e2x * sx + e2y * sy + e2z * sz) * inverse;
            if (along <= 0.0F || along >= 1.0F) {
                continue;
            }
            if (where != null) {
                where[0] = px + dx * along;
                where[1] = py + dyy * along;
                where[2] = pz + dz * along;
            }
            return true;
        }
        return false;
    }

    private static boolean overlaps(float[] a, float[] b) {
        return a[0] <= b[3] && a[3] >= b[0] && a[1] <= b[4] && a[4] >= b[1]
                && a[2] <= b[5] && a[5] >= b[2];
    }

    /** Every pair of the list that crosses, deepest first. */
    private static List<Crossing> crossingTable(Solid[] solids, Vector3f axis) {
        List<Crossing> out = new ArrayList<>();
        for (int a = 0; a < solids.length; a++) {
            for (int b = a + 1; b < solids.length; b++) {
                Crossing value = crossing(solids[a], solids[b], a, b, axis);
                if (value != null) {
                    out.add(value);
                }
            }
        }
        out.sort((x, y) -> {
            int byMeetings = Integer.compare(y.meetings(), x.meetings());
            if (byMeetings != 0) {
                return byMeetings;
            }
            int byDepth = Float.compare(y.depth(), x.depth());
            return byDepth != 0 ? byDepth : Integer.compare(y.insideA() + y.insideB(),
                    x.insideA() + x.insideB());
        });
        return out;
    }

    // ---- the crossing test's own control --------------------------------------------------------

    /** A closed box as a triangle soup, laid out the way the mesh lays a part out. */
    private static List<Vector3f> box(float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        float[][] corners = {
                {minX, minY, minZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {minX, maxY, minZ},
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}};
        int[][] faces = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 2, 6, 7},
                {0, 3, 7, 4}, {1, 2, 6, 5}};
        List<Vector3f> out = new ArrayList<>();
        for (int[] face : faces) {
            // The same fan the writer emits: (0,1,2) then (2,3,0).
            int[] order = {face[0], face[1], face[2], face[2], face[3], face[0]};
            for (int corner : order) {
                out.add(new Vector3f(corners[corner][0], corners[corner][1], corners[corner][2]));
            }
        }
        return out;
    }

    /** Unused placeholder removed. */

    /** Where each piece sits inside the radial span of every cell it occupies, averaged. */
    private static float[] outerScores(Map<Long, float[]>[] cells, int[] subset) {
        Map<Long, float[]> span = new HashMap<>();
        for (int index : subset) {
            for (Map.Entry<Long, float[]> entry : cells[index].entrySet()) {
                float[] current = span.get(entry.getKey());
                if (current == null) {
                    span.put(entry.getKey(), new float[]{entry.getValue()[0], entry.getValue()[1]});
                } else {
                    current[0] = Math.min(current[0], entry.getValue()[0]);
                    current[1] = Math.max(current[1], entry.getValue()[1]);
                }
            }
        }
        float[] out = new float[cells.length];
        for (int index : subset) {
            double sum = 0.0D;
            int used = 0;
            for (Map.Entry<Long, float[]> entry : cells[index].entrySet()) {
                float[] whole = span.get(entry.getKey());
                float width = whole == null || whole[1] <= whole[0] ? 0.0F : whole[1] - whole[0];
                if (width <= 0.0F) {
                    continue;
                }
                sum += (entry.getValue()[1] - whole[0]) / width;
                used++;
            }
            out[index] = used == 0 ? 0.0F : (float) (sum / used);
        }
        return out;
    }

    private static String layerLine(Rig rig, float[] score, Integer[] order, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append("`").append(rig.segments.get(order[i]).name).append("` ")
                    .append(fmt(score[order[i]]));
        }
        return out.toString();
    }

    // ---- the states ----------------------------------------------------------------------------

    /** The simulated pieces this round follows, as surfaces carrying one state's deltas. */
    private static List<SurfaceRef> trackedSurfaces(Rig rig, int[] subset,
                                                    YsmMeshSecondaryMotion.State state) {
        List<SurfaceRef> out = new ArrayList<>();
        for (int index : subset) {
            Segment piece = rig.segments.get(index);
            out.add(new SurfaceRef(piece.name, triangleSoup(piece.own),
                    state == null ? null : state.deltas[index]));
        }
        return out;
    }

    /**
     * Every bone of the model that carries visible geometry and is <b>not</b> a piece the
     * simulation moves: the layer that follows the pose exactly. Read through the same map
     * production's own selection reads, so the hidden bones of the model's default form are out of
     * it by the same rule.
     */
    private static List<SurfaceRef> staticSurfaces(Rig rig) {
        Set<Integer> simulated = new HashSet<>();
        for (Segment piece : rig.segments) {
            simulated.add(piece.boneIndex);
        }
        List<SurfaceRef> out = new ArrayList<>();
        for (Map.Entry<Integer, List<Vector3f>> entry : rig.vertices.entrySet()) {
            if (simulated.contains(entry.getKey()) || entry.getValue().size() < 3) {
                continue;
            }
            out.add(new SurfaceRef(rig.bones[entry.getKey()].name,
                    triangleSoup(entry.getValue()), null));
        }
        return out;
    }

    private static List<String> namesOf(List<SurfaceRef> surfaces) {
        List<String> out = new ArrayList<>(surfaces.size());
        for (SurfaceRef surface : surfaces) {
            out.add(surface.name());
        }
        return out;
    }

    private static Solid[] concat(Solid[] first, Solid[] second) {
        Solid[] out = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>(first);
        out.addAll(second);
        return out;
    }

    /** The crossing table for one state, the two layers listed apart. */
    private static String crossingTableReport(Rig rig, List<String> names, List<Crossing> found,
                                              int trackedCount, int limit) {
        StringBuilder out = new StringBuilder();
        out.append("| rank | a | b | surface meetings | a-vertices inside b | deepest | ")
                .append("b-vertices inside a | deepest | where |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        int plotted = 0;
        for (Crossing value : found) {
            if (plotted++ >= limit) {
                break;
            }
            out.append("| ").append(plotted)
                    .append(" | `").append(names.get(value.a())).append("`")
                    .append(" | `").append(names.get(value.b())).append("`")
                    .append(" | ").append(value.meetings())
                    .append(" | ").append(value.insideA()).append(" | ").append(fmt(value.depthA()))
                    .append(" | ").append(value.insideB()).append(" | ").append(fmt(value.depthB()))
                    .append(" | ").append(fmt(value.azimuth())).append(" deg, ")
                    .append(fmt(value.height())).append(" |\n");
        }
        if (found.isEmpty()) {
            out.append("| - | - | - | - | - | - | - | - | - |\n");
        }
        // Which of the two layers each crossing is between, and how many pieces of the static layer
        // there are at all: the count is what makes "the swinging layer meets the carried one" a
        // reading rather than a guess.
        int garmentIntoGarment = 0;
        int garmentIntoStatic = 0;
        int staticIntoGarment = 0;
        for (Crossing value : found) {
            boolean aTracked = value.a() < trackedCount;
            boolean bTracked = value.b() < trackedCount;
            if (aTracked && bTracked) {
                garmentIntoGarment++;
                continue;
            }
            if (aTracked) {
                if (value.insideA() > 0) {
                    garmentIntoStatic++;
                }
                if (value.insideB() > 0) {
                    staticIntoGarment++;
                }
            } else {
                if (value.insideB() > 0) {
                    garmentIntoStatic++;
                }
                if (value.insideA() > 0) {
                    staticIntoGarment++;
                }
            }
        }
        out.append("\nOf those crossings: ").append(garmentIntoGarment)
                .append(" between two simulated pieces, ").append(garmentIntoStatic)
                .append(" a simulated piece's vertices inside a carried one, and ")
                .append(staticIntoGarment)
                .append(" a carried piece's vertices inside a simulated one.\n\n")
                .append("The carried layer (").append(names.size() - trackedCount).append(" bones): ")
                .append(names.subList(trackedCount, names.size()).toString()).append("\n\n");
        return out.toString();
    }

    /** One row of the state table: the worst crossing the run produced, all pairs and garment only. */
    private static String crossingRow(String label, List<String> names, List<Crossing> found,
                                      int trackedCount, float maxMove, int measured,
                                      int garmentPairFrames) {
        List<Crossing> garment = garmentOnly(found, names);
        Crossing worst = found.isEmpty() ? null : found.get(0);
        Crossing worstGarment = garment.isEmpty() ? null : garment.get(0);
        return "| " + label + " | " + describe(worst, names)
                + " | " + (worst == null ? "-" : "`" + names.get(worst.a()) + "` / `"
                        + names.get(worst.b()) + "`")
                + " | " + garment.size() + " of " + garmentPairFrames + " pair-frames | "
                + describe(worstGarment, names)
                + " | " + (worstGarment == null ? "-" : "`" + names.get(worstGarment.a()) + "` / `"
                        + names.get(worstGarment.b()) + "`")
                + " | " + fmt(maxMove) + " | " + measured + " |\n";
    }

    private static String describe(Crossing value, List<String> names) {
        return value == null ? "none"
                : value.meetings() + " meet / " + value.insideA() + "+" + value.insideB()
                        + " inside / " + fmt(value.depth()) + " deep";
    }

    /**
     * Drive the production frame loop over one state and measure the crossing every twentieth frame.
     *
     * <p>The whole model is simulated, because a piece's swing depends on its ancestors, but only
     * the garment is rebuilt per frame and only pairs whose boxes overlap are tested, which is what
     * makes a per-frame geometric crossing affordable.
     */
    private static String trackCrossings(String label, Rig rig, YsmMeshSecondaryMotion.PoseSource pose,
                                         Gait gait, int frames, int[] subset, Solid[] staticSolids,
                                         List<String> staticNames) {
        YsmPhysicsParts.Model parts = productionModel(rig);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                parts, null, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        Vector3f velocity = gait == null ? null : new Vector3f(0.0F, 0.0F, -gait.speed());
        Vector3f axis = bodyAxis(rig);
        boolean moving = pose instanceof RunPose;
        Solid[] rest = solidsOf(trackedSurfaces(rig, subset, null));
        List<Crossing> worst = null;
        List<String> worstNames = null;
        int crossingPairs = 0;
        int garmentPairs = 0;
        int measured = 0;
        float maxMove = 0.0F;
        for (int frame = 0; frame < frames; frame++) {
            if (moving) {
                ((RunPose) pose).at(frame * DT);
            }
            YsmMeshSecondaryMotion.simulate(state, pose, DT, velocity, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
            if (frame % 40 != 0 || frame < Math.min(GAIT_WARMUP, frames / 2)) {
                continue;
            }
            measured++;
            List<SurfaceRef> tracked = trackedSurfaces(rig, subset, state);
            List<String> names = concat(namesOf(tracked), staticNames);
            Solid[] frameSolids = solidsOf(tracked);
            for (int s = 0; s < frameSolids.length; s++) {
                for (int v = 0; v < frameSolids[s].vertices.length; v += 3) {
                    float dx = frameSolids[s].vertices[v] - rest[s].vertices[v];
                    float dy = frameSolids[s].vertices[v + 1] - rest[s].vertices[v + 1];
                    float dz = frameSolids[s].vertices[v + 2] - rest[s].vertices[v + 2];
                    maxMove = Math.max(maxMove, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }
            List<Crossing> found = crossingTable(concat(frameSolids, staticSolids), axis);
            List<Crossing> garment = garmentOnly(found, names);
            crossingPairs += found.size();
            garmentPairs += garment.size();
            boolean better = worst == null
                    || (!garment.isEmpty() && garmentOnly(worst, worstNames).isEmpty())
                    || (garment.isEmpty() == garmentOnly(worst, worstNames).isEmpty()
                            && found.get(0).meetings() > worst.get(0).meetings());
            if (!found.isEmpty() && better) {
                worst = found;
                worstNames = names;
            }
        }
        return crossingRow(label, worstNames == null ? List.of() : worstNames,
                worst == null ? List.of() : worst, subset.length, maxMove, measured, garmentPairs);
    }

    /**
     * The second control, on the model's own geometry: <b>the same test on a copy of a real piece
     * that is known to cut through it</b>. The two pieces closest to each other at bind are found,
     * and the first is turned thirty degrees about a horizontal axis through its own centre; the
     * test must then report a meeting with the copy it came from.
     *
     * <p>It exists because every other number in this report is a "no", and a "no" from a pipeline
     * that never applies a delta looks exactly like a "no" from a model that is clean. Sliding one
     * piece onto another would not do: two panels of this skirt are parallel facets of the same
     * cylinder, so a slide leaves them parallel and a turning is what makes them cut.
     */
    private static String modelControl(Rig rig, int[] subset, Vector3f axis, List<String> problems) {
        int bestA = -1;
        int bestB = -1;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < subset.length; i++) {
            for (int j = i + 1; j < subset.length; j++) {
                float gap = Rig.centroid(rig.segments.get(subset[i]).own)
                        .distance(Rig.centroid(rig.segments.get(subset[j]).own));
                if (gap < best) {
                    best = gap;
                    bestA = subset[i];
                    bestB = subset[j];
                }
            }
        }
        if (bestA < 0) {
            problems.add("the model has no pair to run the crossing test's own control on");
            return "";
        }
        Solid[] solids = solidsOf(trackedSurfaces(rig, subset, null));
        Crossing clean = crossing(solids[bestA], solids[bestB], bestA, bestB, axis);
        Segment piece = rig.segments.get(bestA);
        Vector3f centre = Rig.centroid(piece.own);
        Matrix4f turn = new Matrix4f().translate(centre.x, centre.y, centre.z)
                .rotateX(0.5236F).translate(-centre.x, -centre.y, -centre.z);
        Solid turned = solidsOf(List.of(new SurfaceRef(piece.name, triangleSoup(piece.own),
                toOpen(turn))))[0];
        Crossing forced = crossing(solids[bestA], turned, bestA, bestA, axis);
        String report = "The model's own control, on the closest pair it has (`"
                + piece.name + "` and `" + rig.segments.get(bestB).name + "`, centres "
                + fmt(best) + " blocks apart): clean = "
                + (clean == null ? "nothing" : clean.meetings() + " meeting(s), "
                        + clean.insideA() + "+" + clean.insideB() + " vertices inside")
                + "; `" + piece.name + "` against a copy of itself turned 30 degrees about a "
                + "horizontal axis through its own centre = "
                + (forced == null ? "STILL NOTHING"
                        : forced.meetings() + " meeting(s), " + forced.insideA() + "+"
                                + forced.insideB() + " vertices inside, "
                                + fmt(forced.depth()) + " blocks deep")
                + ". `" + piece.name + "`: " + solidTriangleReport(solids[bestA])
                + "; the turned copy: " + solidTriangleReport(turned)
                + "; bounds " + boundsText(solids[bestA]) + " against " + boundsText(turned)
                + ".\n\n";
        if (forced == null || forced.meetings() == 0) {
            problems.add("the crossing test's own control failed on the model's own geometry: a "
                    + "piece turned 30 degrees through its own centre must cut the copy it came "
                    + "from, and the test reports nothing - so every \"no crossing\" number here is "
                    + "untrustworthy");
        }
        return report;
    }

    /** How many of a solid's triangles have any area at all, and its vertex count. */
    private static String solidTriangleReport(Solid solid) {
        int triangles = 0;
        int flat = 0;
        for (int t = 0; t + 8 < solid.vertices.length; t += 9) {
            triangles++;
            float e1x = solid.vertices[t + 3] - solid.vertices[t];
            float e1y = solid.vertices[t + 4] - solid.vertices[t + 1];
            float e1z = solid.vertices[t + 5] - solid.vertices[t + 2];
            float e2x = solid.vertices[t + 6] - solid.vertices[t];
            float e2y = solid.vertices[t + 7] - solid.vertices[t + 1];
            float e2z = solid.vertices[t + 8] - solid.vertices[t + 2];
            float nx = e1y * e2z - e1z * e2y;
            float ny = e1z * e2x - e1x * e2z;
            float nz = e1x * e2y - e1y * e2x;
            if (Math.sqrt(nx * nx + ny * ny + nz * nz) < 1.0E-8F) {
                flat++;
            }
        }
        return solid.vertexCount + " vertices, " + triangles + " triangles, " + flat
                + " of them flat";
    }

    private static String boundsText(Solid solid) {
        return "[" + fmt(solid.bounds[0]) + ".." + fmt(solid.bounds[3]) + ", "
                + fmt(solid.bounds[1]) + ".." + fmt(solid.bounds[4]) + ", "
                + fmt(solid.bounds[2]) + ".." + fmt(solid.bounds[5]) + "]";
    }

    /**
     * The first control: a small box inside a bigger one, two boxes side by side, two plates
     * crossing like an X and two plates sharing a face. Asserted, because every "no crossing"
     * number in this report is a statement about the same two tests.
     */
    private static String controlReport(List<String> problems) {
        Solid outer = solidsOf(List.of(new SurfaceRef("outer",
                box(-0.10F, 0.00F, -0.10F, 0.10F, 0.20F, 0.10F), null)))[0];
        Solid inner = solidsOf(List.of(new SurfaceRef("inner",
                box(-0.02F, 0.08F, -0.02F, 0.02F, 0.12F, 0.02F), null)))[0];
        Solid beside = solidsOf(List.of(new SurfaceRef("beside",
                box(0.30F, 0.00F, -0.10F, 0.50F, 0.20F, 0.10F), null)))[0];
        // Two thin plates crossing like an X: no vertex of either is inside the other, so this pair
        // is the one that says whether the crossing test proper (edges through faces) works.
        Solid plateA = solidsOf(List.of(new SurfaceRef("plateA",
                box(-0.05F, 0.00F, -0.02F, 0.05F, 0.20F, 0.02F), null)))[0];
        Solid plateB = solidsOf(List.of(new SurfaceRef("plateB",
                box(-0.02F, 0.08F, -0.05F, 0.02F, 0.12F, 0.05F), null)))[0];
        // And two plates that share a face but cross nothing: touching is not crossing.
        Solid touchA = solidsOf(List.of(new SurfaceRef("touchA",
                box(-0.05F, 0.00F, -0.05F, 0.05F, 0.20F, -0.01F), null)))[0];
        Solid touchB = solidsOf(List.of(new SurfaceRef("touchB",
                box(-0.05F, 0.00F, -0.01F, 0.05F, 0.20F, 0.03F), null)))[0];
        Crossing nested = crossing(inner, outer, 0, 1, new Vector3f());
        Crossing disjoint = crossing(outer, beside, 0, 1, new Vector3f());
        Crossing crossingPlates = crossing(plateA, plateB, 0, 1, new Vector3f());
        Crossing touching = crossing(touchA, touchB, 0, 1, new Vector3f());
        int innerVertices = inner.vertexCount;
        // The inner box's own faces are 0.08 blocks from the outer box's nearest face, so every one
        // of its vertices is inside and the deepest is 0.08 blocks from the way out.
        String control = "The inner box has " + innerVertices + " vertices, "
                + (nested == null ? 0 : nested.insideA()) + " of them inside the outer box, and the "
                + "deepest is " + fmt(nested == null ? 0.0F : nested.depth())
                + " blocks from a face; the box beside the outer one reports "
                + (disjoint == null ? "nothing" : fmt(disjoint.depth()) + " blocks")
                + ". Two plates crossing like an X meet in " + (crossingPlates == null ? 0
                        : crossingPlates.meetings()) + " place(s) with "
                + (crossingPlates == null ? 0 : crossingPlates.insideA() + crossingPlates.insideB())
                + " vertices inside either; two plates sharing a face meet in "
                + (touching == null ? 0 : touching.meetings()) + ".\n\n";
        if (nested == null || nested.insideA() != innerVertices) {
            problems.add("the crossing test's own control failed: a box wholly inside another "
                    + "reports " + (nested == null ? 0 : nested.insideA()) + " of " + innerVertices
                    + " vertices inside");
        }
        if (nested == null || Math.abs(nested.depth() - 0.08F) > 0.005F) {
            problems.add("the crossing test's own control failed: the deepest vertex of a box whose "
                    + "own faces are 0.08 blocks from the outer box's nearest face should read 0.08 "
                    + "blocks, not " + fmt(nested == null ? 0.0F : nested.depth()));
        }
        if (nested == null || nested.meetings() != 0) {
            problems.add("the crossing test's own control failed: a box wholly inside another has "
                    + "no surface crossing, but reported "
                    + (nested == null ? 0 : nested.meetings()));
        }
        if (disjoint != null) {
            problems.add("the crossing test's own control failed: two boxes side by side must not "
                    + "report a crossing, but reported " + fmt(disjoint.depth()) + " blocks and "
                    + disjoint.meetings() + " meeting(s)");
        }
        if (crossingPlates == null || crossingPlates.meetings() == 0) {
            problems.add("the crossing test's own control failed: two plates crossing like an X "
                    + "must be reported, and their vertices are outside each other, so only the "
                    + "edge-through-face test can see them");
        }
        if (touching != null && touching.meetings() != 0) {
            problems.add("the crossing test's own control failed: two plates sharing a face meet "
                    + "along an edge, which is a touch and not a crossing, but "
                    + touching.meetings() + " interior meeting(s) were reported");
        }
        return control;
    }

    /** The pose of a body that is not moving at all. */
    private static final YsmMeshSecondaryMotion.PoseSource STILL_POSE =
            new YsmMeshSecondaryMotion.PoseSource() {
                private final OpenMatrix4f identity = new OpenMatrix4f();

                @Override
                public OpenMatrix4f toOriginOf(int joint) {
                    return identity;
                }

                @Override
                public OpenMatrix4f poseOf(int joint) {
                    return identity;
                }
            };

    // ---- the gaits -----------------------------------------------------------------------------

    /** A running body: the torso leaning and bobbing, the legs cycling, and a constant velocity. */
    private static final class RunPose implements YsmMeshSecondaryMotion.PoseSource {
        private static final OpenMatrix4f TO_ORIGIN = new OpenMatrix4f();
        private final Rig rig;
        private final Gait gait;
        private final float leanSign;
        private OpenMatrix4f torso = new OpenMatrix4f();
        private OpenMatrix4f chest = new OpenMatrix4f();
        private OpenMatrix4f head = new OpenMatrix4f();
        private OpenMatrix4f thighRight = new OpenMatrix4f();
        private OpenMatrix4f thighLeft = new OpenMatrix4f();
        private OpenMatrix4f legRight = new OpenMatrix4f();
        private OpenMatrix4f legLeft = new OpenMatrix4f();

        RunPose(Rig rig, Gait gait, float leanSign) {
            this.rig = rig;
            this.gait = gait;
            this.leanSign = leanSign;
            at(0.0F);
        }

        /** The pose one instant into the cycle. */
        void at(float seconds) {
            float phase = (float) (2.0D * Math.PI * gait.hertz() * seconds);
            float bob = gait.bob() * (float) Math.sin(2.0F * phase);
            torso = about(rig.jointOrigin(JointTable.TORSO), gait.leanDegrees() * leanSign, bob);
            // The chest and the head carry the same cycle and a counter-lean, so every reference
            // piece this round compares the tail against is driven too: a comparison against a
            // piece that is not moving would prove nothing about the tail's motion.
            chest = about(rig.jointOrigin(JointTable.CHEST),
                    gait.leanDegrees() * 0.55F * leanSign + 2.5F * (float) Math.sin(2.0F * phase),
                    bob * 0.5F);
            head = about(rig.jointOrigin(JointTable.HEAD),
                    -gait.leanDegrees() * 0.45F * leanSign + 3.5F * (float) Math.sin(2.0F * phase + 1.0F),
                    0.0F);
            thighRight = about(rig.jointOrigin(JointTable.THIGH_R),
                    gait.strideDegrees() * (float) Math.sin(phase), 0.0F);
            thighLeft = about(rig.jointOrigin(JointTable.THIGH_L),
                    gait.strideDegrees() * (float) Math.sin(phase + Math.PI), 0.0F);
            legRight = about(rig.jointOrigin(JointTable.LEG_R),
                    gait.kneeDegrees() * (float) Math.sin(phase + 2.0F), 0.0F);
            legLeft = about(rig.jointOrigin(JointTable.LEG_L),
                    gait.kneeDegrees() * (float) Math.sin(phase + Math.PI + 2.0F), 0.0F);
        }

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return TO_ORIGIN;
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            if (joint == JointTable.TORSO) {
                return torso;
            }
            if (joint == JointTable.CHEST) {
                return chest;
            }
            if (joint == JointTable.HEAD) {
                return head;
            }
            if (joint == JointTable.THIGH_R) {
                return thighRight;
            }
            if (joint == JointTable.THIGH_L) {
                return thighLeft;
            }
            if (joint == JointTable.LEG_R) {
                return legRight;
            }
            if (joint == JointTable.LEG_L) {
                return legLeft;
            }
            return TO_ORIGIN;
        }
    }

    /** The production frame loop over a moving body, recording every traced piece. */
    private static YsmMeshSecondaryMotion.State runGait(Rig rig, RunPose pose, Gait gait,
                                                        List<Trace> traces, Set<String> rigid,
                                                        Map<String, Float> limitDeg) {
        return runGait(rig, pose, gait, traces, rigid, limitDeg, Map.of());
    }

    /** The same, with a per-piece damping ratio for the pieces named. */
    private static YsmMeshSecondaryMotion.State runGait(Rig rig, RunPose pose, Gait gait,
                                                        List<Trace> traces, Set<String> rigid,
                                                        Map<String, Float> limitDeg,
                                                        Map<String, Float> damping) {
        YsmPhysicsParts.Model parts = productionModel(rig, damping);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                parts, null, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        for (int i = 0; i < parts.segments().length; i++) {
            String name = parts.segments()[i].boneName();
            state.held[i] = rigid.contains(name);
            Float degrees = name == null ? null : limitDeg.get(name.toLowerCase(Locale.ROOT));
            if (degrees != null) {
                state.limit[i] = (float) Math.toRadians(degrees);
            }
        }
        Vector3f velocity = new Vector3f(0.0F, 0.0F, -gait.speed());
        for (int frame = 0; frame < GAIT_FRAMES; frame++) {
            pose.at(frame * DT);
            YsmMeshSecondaryMotion.simulate(state, pose, DT, velocity, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
            if (frame >= GAIT_WARMUP) {
                for (Trace trace : traces) {
                    trace.frame(state);
                }
            }
        }
        return state;
    }

    /** The pieces this round follows: the whole garment, the tail and the pieces that look right. */
    private static List<Trace> tracesOf(Rig rig) {
        List<Trace> out = new ArrayList<>();
        for (Segment piece : rig.segments) {
            if (isGarment(piece.name) || contains(KEEP_SWINGING, piece.name)) {
                out.add(new Trace(piece, farthestVertex(piece.own, piece.bindPivot)));
            }
        }
        return out;
    }

    private static Map<String, Float> tipSpeeds(List<Trace> traces) {
        Map<String, Float> out = new HashMap<>();
        for (Trace trace : traces) {
            out.put(trace.piece.name, trace.tipSpeed());
        }
        return out;
    }

    /** One candidate's rows: what it does to the tail, and what it costs everything else. */
    private static String candidateRows(Rig rig, Candidate candidate, List<Trace> traces,
                                        Map<String, Float> baselineTips,
                                        YsmMeshSecondaryMotion.State baseline,
                                        YsmMeshSecondaryMotion.State state, float cost) {
        StringBuilder out = new StringBuilder();
        String[] rows = {"Tail", "Tail2", "Tail3", "Tail4", "Tail5", "Tail6", "Tail7",
                "LongHair", "LongHair2", "LongRightHair2", "BL3", "RF3"};
        for (String name : rows) {
            Trace trace = null;
            for (Trace candidateTrace : traces) {
                if (candidateTrace.piece.name.equals(name)) {
                    trace = candidateTrace;
                    break;
                }
            }
            if (trace == null) {
                continue;
            }
            Float before = baselineTips.get(name);
            float speed = trace.tipSpeed();
            out.append("| ").append(candidate.label()).append(" | `").append(name).append("` | ")
                    .append(fmt(trace.ownMean())).append(" | ").append(fmt(trace.allowedMean()))
                    .append(" | ").append(fmt(trace.saturatedShare()))
                    .append(" | ").append(fmt(trace.composedMax()))
                    .append(" | ").append(fmt(trace.tipSpread()))
                    .append(" | ").append(fmt(speed))
                    .append(" | ").append(before == null ? "-" : fmt(speed - before))
                    .append(" |\n");
        }
        out.append("\n`").append(candidate.label()).append("`: worst move of a piece it does not ")
                .append("name = ").append(fmt(cost)).append(" blocks.\n\n");
        return out.toString();
    }

    /** The largest move a candidate causes in a piece it does not name: the blast radius. */
    private static float worstUnnamedMove(Rig rig, Candidate candidate,
                                          YsmMeshSecondaryMotion.State baseline,
                                          YsmMeshSecondaryMotion.State state) {
        float worst = 0.0F;
        for (Segment piece : rig.segments) {
            String name = piece.name;
            boolean named = candidate.rigid().contains(name)
                    || candidate.limitDeg().containsKey(name.toLowerCase(Locale.ROOT));
            // A piece hanging under a named one is expected to move: its parent's swing changed.
            if (named || descendsFrom(rig, piece.index, namedIndexes(rig, candidate))) {
                continue;
            }
            worst = Math.max(worst, maxMove(baseline.deltas[piece.index], state.deltas[piece.index],
                    piece.index, rig));
        }
        return worst;
    }

    private static Set<Integer> namedIndexes(Rig rig, Candidate candidate) {
        Set<Integer> out = new HashSet<>();
        for (Segment piece : rig.segments) {
            if (candidate.rigid().contains(piece.name)
                    || candidate.limitDeg().containsKey(piece.name.toLowerCase(Locale.ROOT))) {
                out.add(piece.index);
            }
        }
        return out;
    }

    /** What one piece did over a run, sampled once per frame after the warm-up. */
    private static final class Trace {
        final Segment piece;
        final Vector3f tip;
        final List<Float> own = new ArrayList<>();
        /**
         * The swing the solver holds, degrees, <b>before</b> the allowance is applied.
         *
         * <p>The allowance is a display clamp: {@code resolveSegment} slerps the drawn rotation back
         * to the limit and writes that into {@code lastDegrees}, but nothing writes it back into the
         * solver state, so the pendulum keeps swinging past it. The difference between this and
         * {@link #own} is therefore how far the link is drawn <i>behind</i> where its own physics is -
         * a link whose difference is never zero is a link drawn held on a stop in every frame.
         */
        final List<Float> raw = new ArrayList<>();
        final List<Float> allowed = new ArrayList<>();
        final List<Float> composed = new ArrayList<>();
        final List<Vector3f> tipPath = new ArrayList<>();

        Trace(Segment piece, Vector3f tip) {
            this.piece = piece;
            this.tip = tip;
        }

        void frame(YsmMeshSecondaryMotion.State state) {
            own.add(state.lastDegrees[piece.index]);
            raw.add((float) Math.toDegrees(state.states[piece.index].lastAngle));
            // The budget the chain granted, in the same unit as the swing the solver reported: the
            // solver works in radians and the log in degrees, and comparing the two without this
            // conversion reads every piece as pinned.
            allowed.add((float) Math.toDegrees(state.chainBudget[piece.index]));
            composed.add(rotationAngleOf(state.deltas[piece.index]));
            tipPath.add(YsmMeshSecondaryMotion.transformPoint(state.deltas[piece.index], tip,
                    new Vector3f()));
        }

        float ownMean() {
            return mean(own);
        }

        float ownMax() {
            return max(own);
        }

        /** How far the link's <b>drawn</b> swing travels between its own extremes, degrees. */
        float drawnRange() {
            return max(own) - min(own);
        }

        /** The share of frames in which the drawn swing is held below the solver's own: on a stop. */
        float clippedShare() {
            int clipped = 0;
            for (int i = 0; i < own.size() && i < raw.size(); i++) {
                if (raw.get(i) - own.get(i) > 0.1F) {
                    clipped++;
                }
            }
            return own.isEmpty() ? 0.0F : (float) clipped / own.size();
        }

        /** The widest the drawn swing is held below the solver's own, degrees. */
        float worstClip() {
            float worst = 0.0F;
            for (int i = 0; i < own.size() && i < raw.size(); i++) {
                worst = Math.max(worst, raw.get(i) - own.get(i));
            }
            return worst;
        }

        /** The largest swing the solver itself holds, before the allowance is applied, degrees. */
        float rawMax() {
            return max(raw);
        }

        float allowedMean() {
            return mean(allowed);
        }

        /** The share of frames whose own swing is at its own allowance: pinned at the clamp. */
        float saturatedShare() {
            int pinned = 0;
            for (int i = 0; i < own.size(); i++) {
                if (own.get(i) > 0.5F && own.get(i) >= allowed.get(i) - 0.01F) {
                    pinned++;
                }
            }
            return own.isEmpty() ? 0.0F : (float) pinned / own.size();
        }

        float composedMax() {
            return max(composed);
        }

        /** How far the composed swing travels between its own extremes. */
        float composedPeakToPeak() {
            return max(composed) - min(composed);
        }

        /** Blocks per second the tip travels: the plainest reading of "all over the place". */
        float tipSpeed() {
            float path = 0.0F;
            for (int i = 1; i < tipPath.size(); i++) {
                path += tipPath.get(i).distance(tipPath.get(i - 1));
            }
            return tipPath.size() < 2 ? 0.0F : path / ((tipPath.size() - 1) * DT);
        }

        /** How far the tip moves in the worst single frame, blocks per second. */
        float worstTipSpeed() {
            float worst = 0.0F;
            for (int i = 1; i < tipPath.size(); i++) {
                worst = Math.max(worst, tipPath.get(i).distance(tipPath.get(i - 1)));
            }
            return worst / DT;
        }

        /**
         * How far apart the two farthest places the tip visited are: the size of the region the
         * tip swept, which is the plainest reading of "swings all over the place" - a tip that
         * whips back and forth over a wide arc has a large spread however little path it covers.
         */
        float tipSpread() {
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            for (Vector3f at : tipPath) {
                minX = Math.min(minX, at.x);
                minY = Math.min(minY, at.y);
                minZ = Math.min(minZ, at.z);
                maxX = Math.max(maxX, at.x);
                maxY = Math.max(maxY, at.y);
                maxZ = Math.max(maxZ, at.z);
            }
            return tipPath.isEmpty() ? 0.0F
                    : (float) Math.sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY)
                            + (maxZ - minZ) * (maxZ - minZ));
        }

        /** How often the tip reverses direction, per second. */
        float reversalsPerSecond() {
            int reversals = 0;
            Vector3f previous = null;
            Vector3f last = null;
            for (Vector3f at : tipPath) {
                if (last != null) {
                    Vector3f step = new Vector3f(at).sub(last);
                    if (previous != null && step.dot(previous) < 0.0F) {
                        reversals++;
                    }
                    if (step.lengthSquared() > 1.0E-10F) {
                        previous = step;
                    }
                }
                last = at;
            }
            return tipPath.size() < 2 ? 0.0F : reversals / ((tipPath.size() - 1) * DT);
        }

        private static float mean(List<Float> values) {
            double sum = 0.0D;
            for (float value : values) {
                sum += value;
            }
            return values.isEmpty() ? 0.0F : (float) (sum / values.size());
        }

        private static float max(List<Float> values) {
            float out = Float.NEGATIVE_INFINITY;
            for (float value : values) {
                out = Math.max(out, value);
            }
            return out == Float.NEGATIVE_INFINITY ? 0.0F : out;
        }

        private static float min(List<Float> values) {
            float out = Float.POSITIVE_INFINITY;
            for (float value : values) {
                out = Math.min(out, value);
            }
            return out == Float.POSITIVE_INFINITY ? 0.0F : out;
        }
    }

    private static String gaitTable(Rig rig, Gait gait, List<Trace> traces) {
        StringBuilder out = new StringBuilder();
        out.append("### `").append(gait.name()).append("` - lean ")
                .append(fmt(gait.leanDegrees())).append(" deg, bob ")
                .append(fmt(gait.bob())).append(" blocks, stride ")
                .append(fmt(gait.strideDegrees())).append(" deg at ")
                .append(fmt(gait.hertz())).append(" Hz, body speed ")
                .append(fmt(gait.speed())).append(" blocks/s\n\n")
                .append("| bone | support | own mean | own max | allowed mean | **pinned share** | ")
                .append("composed max | composed p-p | tip speed | worst tip speed | reversals/s |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Trace trace : traces) {
            Segment piece = trace.piece;
            out.append("| `").append(piece.name).append("` | `").append(piece.supportName)
                    .append("` | ").append(fmt(trace.ownMean()))
                    .append(" | ").append(fmt(trace.ownMax()))
                    .append(" | ").append(fmt(trace.allowedMean()))
                    .append(" | ").append(fmt(trace.saturatedShare()))
                    .append(" | ").append(fmt(trace.composedMax()))
                    .append(" | ").append(fmt(trace.composedPeakToPeak()))
                    .append(" | ").append(fmt(trace.tipSpeed()))
                    .append(" | ").append(fmt(trace.worstTipSpeed()))
                    .append(" | ").append(fmt(trace.reversalsPerSecond()))
                    .append(" |\n");
        }
        return out.append('\n').toString();
    }

    // ---- the concentric ladder -----------------------------------------------------------------

    private static String concentricLadder(Rig rig, Map<Long, float[]>[] cells, int[] subset,
                                           Vector3f axis) {
        StringBuilder out = new StringBuilder();
        out.append("## 2. The concentric ladder: the garment's pieces every 60 degrees of azimuth\n\n")
                .append("Each row lists the pieces whose surface is present in that sector and that ")
                .append("height band, sorted by the radius they **start** at, with their radial ")
                .append("interval inside the band. Two concentric garments show up as two groups per ")
                .append("row; a row whose intervals are not ordered is a crossing, and the exact ")
                .append("crossings are measured in section 3.\n\n");
        float[] bandBase = {0.15F, 0.35F, 0.55F, 0.75F, 0.95F, 1.15F, 1.35F};
        for (int sector = 0; sector < 6; sector++) {
            out.append("### ").append(sector * 60).append(" deg\n\n")
                    .append("| height | pieces, innermost first |\n|---|---|\n");
            for (float base : bandBase) {
                List<String> rows = new ArrayList<>();
                for (int index : subset) {
                    float rMin = Float.MAX_VALUE;
                    float rMax = 0.0F;
                    for (Map.Entry<Long, float[]> entry : cells[index].entrySet()) {
                        if ((int) (entry.getKey() / 1024L) / 30 != sector) {
                            continue;
                        }
                        float y = (entry.getKey() % 1024L) * LADDER_HEIGHT - 4.0F;
                        if (y < base || y >= base + 0.20F) {
                            continue;
                        }
                        rMin = Math.min(rMin, entry.getValue()[0]);
                        rMax = Math.max(rMax, entry.getValue()[1]);
                    }
                    if (rMin != Float.MAX_VALUE) {
                        rows.add(String.format(Locale.ROOT, "`%s` %.3f..%.3f",
                                rig.segments.get(index).name, rMin, rMax));
                    }
                }
                java.util.Collections.sort(rows);
                out.append("| ").append(fmt(base)).append("..").append(fmt(base + 0.20F))
                        .append(" | ").append(rows.isEmpty() ? "-" : String.join(", ", rows))
                        .append(" |\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    // ---- small helpers -------------------------------------------------------------------------

    private static boolean contains(String[] names, String name) {
        for (String candidate : names) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a piece is part of the skirt: the twelve three-link chains and nothing else. Read as
     * a name family here only to keep the tables short - the layers themselves are assigned by
     * measurement in section 4.
     */
    private static boolean isSkirtOrTail(String name) {
        if (name.startsWith("Tail")) {
            return true;
        }
        String[] families = {"RF", "RM", "RB", "LF", "LM", "LB", "FL", "FM", "FR", "BL", "BM", "BR"};
        for (String family : families) {
            if (name.startsWith(family)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a piece is part of the garment: the twelve three-link columns, and the six `FFM*`
     * pieces of its front - the second family is named unlike the first and was missed by a name
     * filter on the first run of this probe.
     */
    private static boolean isGarment(String name) {
        return isSkirtOrTail(name) || name.startsWith("FFM");
    }

    /** The crossings of the list where both pieces are the garment. */
    private static List<Crossing> garmentOnly(List<Crossing> found, List<String> names) {
        List<Crossing> out = new ArrayList<>();
        for (Crossing value : found) {
            if (isGarment(names.get(value.a())) && isGarment(names.get(value.b()))) {
                out.add(value);
            }
        }
        return out;
    }

    /** The piece's own vertex farthest from its pivot: the tip the user watches. */
    private static Vector3f farthestVertex(List<Vector3f> own, Vector3f pivot) {
        Vector3f best = pivot;
        float bestDistance = -1.0F;
        for (Vector3f vertex : own) {
            if (vertex == null) {
                continue;
            }
            float distance = vertex.distance(pivot);
            if (distance > bestDistance) {
                bestDistance = distance;
                best = vertex;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // The production frame loop, driven for the override measurement
    // ------------------------------------------------------------------

    /** This model's pieces as the production record, from the probe's own reading of the model. */
    private static YsmPhysicsParts.Model productionModel(Rig rig) {
        return productionModel(rig, Map.of());
    }

    /**
     * The same, with a damping ratio of its own for the pieces named.
     *
     * <p>The third key the brief names, measured before it is designed: the damping ratio is a
     * property of the piece's record here ({@code YsmPhysicsTuning.DEFAULTS.dampingRatio()}, 0.81 for
     * every piece of every model today), so a candidate can be run without the production change.
     * The solver clamps the ratio to 0..1, so 1.00 is the most damping it can be given.
     */
    private static YsmPhysicsParts.Model productionModel(Rig rig, Map<String, Float> damping) {
        YsmPhysicsParts.Segment[] out = new YsmPhysicsParts.Segment[rig.segments.size()];
        for (int i = 0; i < out.length; i++) {
            Segment s = rig.segments.get(i);
            Float ratio = damping.get(s.name.toLowerCase(Locale.ROOT));
            out[i] = new YsmPhysicsParts.Segment(s.boneIndex, s.name, s.joint, new Vector3f(s.bindPivot),
                    new Vector3f(s.bindAnchor), new Vector3f(s.bindRest), s.lever, s.radius, s.mass,
                    (float) YsmPhysicsTuning.DEFAULTS.frequency(),
                    ratio == null ? (float) YsmPhysicsTuning.DEFAULTS.dampingRatio() : ratio,
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
