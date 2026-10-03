package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.JointTable;
import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.model.YSMJointMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The legs against the skirt: what the shipped build already collides, where the skirt is still
 * drawn inside a leg, and how far the point the solver tests is from the point the piece is drawn at.
 *
 * <h2>The three questions, and how each is measured</h2>
 *
 * <ol>
 *   <li><b>What collision volumes does this model actually get?</b> Built through production's own
 *       {@link YsmBodyColliders#fromGeometry} - the shape rule, split out of {@code build} for
 *       exactly this - from the model's own leg geometry in the frame the solver reads it
 *       ({@code (x, y, z)_json -> (x, z, -y)}, the loader's own map).</li>
 *   <li><b>What does the skirt do?</b> The production frame loop
 *       ({@code YsmMeshSecondaryMotion.simulate}) over the model's own converted artefacts, with the
 *       real body colliders placed every frame by {@link YsmBodyColliders#update}, over walk and
 *       sprint. Every garment panel's own vertices are placed in the <b>drawn</b> frame - the pose
 *       deformation, then the parent chain's deltas, then the piece's own delta - and the penetration
 *       of each of those vertices into each leg capsule is read as a distance.</li>
 *   <li><b>Is the point the solver tests the point the piece is drawn at?</b> The solver's contact
 *       point is rebuilt exactly as {@code YsmDynamicBoneSolver#resolveCollisions} builds it
 *       ({@code deformation(bindPivot) + restDir * lever}) and set beside the drawn centre of mass.
 *       The gap between them is the number that decides whether a leg collision can be trusted.</li>
 * </ol>
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It does not build a {@link com.ysmef.compat.model.YSMMesh} - that constructor builds GPU
 * buffers - so the segment list is assembled through the same package-private seams
 * {@code YsmPhysicsParts#build} uses, the way {@code HeadRegionPitchProbeTest} does, and every
 * number a rule decides on is production's own. The one thing it does not reproduce is the author's
 * per-bone physics binding table, which this model's converted artefact does not carry (its
 * {@code physics} key is absent) - and production falls back to exactly the same defaults when it is.
 */
class YsmLegSkirtCollisionProbeTest {

    /** The reported model, by its converted runtime's own id. */
    private static final String MAID = "wine_fox/01_taisho_maid";

    /**
     * The simulated set the client logged for this model ({@code logs/latest.log} 14:21:53.384,
     * "59 simulated bone(s)"): a quotation, not a computation, and this probe asserts the assembled
     * set equals it.
     */
    static final String LOGGED_MAID_SIMULATED =
            "RB3, RB2, FL1, FL2, RB, RF, RF3, RF2, RM, RM2, RM3, LongRightHair, LongRightHair2, FM2, "
            + "BL, BL3, BL2, FM1, BM, BM2, BM3, BR, BR3, BR2, Tail, Tail5, Tail4, Tail7, Tail6, Tail3, "
            + "Tail2, FFM1, FFM1_1, FFM2, FFM2_1, FFM3, FFM3_1, LongLeftHair, LongLeftHair2, "
            + "RightSideHair, LongHair2, LongHair, FL, FM, FR, FR1, FR2, LM2, LM3, Bangs, LeftSideHair, "
            + "BaseHair, LB, LB3, LB2, LF, LF3, LF2, LM";
    /** Every garment panel of this model: the pieces the report is about. */
    private static final String[] PANELS = {
            "RM", "RM2", "RM3", "RF", "RF2", "RF3", "RB", "RB2", "RB3",
            "BL", "BL2", "BL3", "BM", "BM2", "BM3", "BR", "BR2", "BR3",
            "FL", "FL1", "FL2", "FM", "FM1", "FM2", "FR", "FR1", "FR2",
            "LM", "LM2", "LM3", "LF", "LF2", "LF3", "LB", "LB2", "LB3"};

    /** The joints a garment is allowed to rest against; the client's own logged set. */
    private static final int[] BODY_JOINTS = {0, 1, 4, 2, 5, 3, 6, 7, 8, 9};

    /** The six limb joints: their volumes are capsules along the limb. */
    private static final int[] LIMB_JOINTS = {1, 4, 2, 5, 3, 6};

    private static final float DT = 1.0F / 60.0F;
    private static final int FRAMES = 600;
    private static final int WARMUP = 200;
    private static final float[] NO_TURN = {0.0F, 0.0F};

    /** The body's own forward in the model's bind frame: the tail is behind, at +Z. */
    private static final Vector3f FORWARD = new Vector3f(0.0F, 0.0F, -1.0F);

    /** A gait: the pose it runs at and the speed the body moves at while it does. */
    private record Gait(String name, float leanDegrees, float bob, float strideDegrees,
                        float kneeDegrees, float hertz, float speed, float strideSign) {}

    private static final Gait WALK =
            new Gait("walk", 6.0F, 0.022F, 20.0F, 16.0F, 1.05F, 4.317F, 1.0F);
    private static final Gait SPRINT =
            new Gait("sprint", 17.0F, 0.040F, 34.0F, 28.0F, 1.60F, 5.612F, 1.0F);
    /**
     * The same cycle run <b>backwards</b>, which is the direction the report names: the legs swing
     * the other way through the hem, and the body faces the way it is not travelling. The velocity
     * stays positive, so what changes is only which way the legs pass the cloth.
     */
    private static final Gait BACKWARD =
            new Gait("backward", 6.0F, 0.022F, 20.0F, 16.0F, 1.05F, 4.317F, -1.0F);
    /**
     * A deliberately extreme leg cycle - wider than any authored walk this model ships, and at the
     * stiffest frequency the tuning allows - so that "the skirt never overlaps the leg" cannot be an
     * artefact of too gentle a test.
     */
    private static final Gait STRAIN =
            new Gait("strain", 30.0F, 0.060F, 55.0F, 45.0F, 1.80F, 6.0F, 1.0F);
    private static final Gait[] GAITS = {WALK, SPRINT, BACKWARD, STRAIN};

    // ------------------------------------------------------------------
    // The measurement
    // ------------------------------------------------------------------

    /** The test whole round turns on. */
    @Test
    void theLegsAgainstTheSkirtAtWalkAndSprint() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");

        Rig rig = Rig.load(pack, MAID, LOGGED_MAID_SIMULATED);
        StringBuilder report = new StringBuilder();
        report.append("# The legs against the skirt, measured on the deployed build's own artefacts\n\n")
                .append("Model `").append(rig.modelId).append("`, ").append(rig.segments.size())
                .append(" simulated pieces. The dynamics are the production frame loop ")
                .append("(`YsmMeshSecondaryMotion.simulate`, real colliders placed every frame by ")
                .append("`YsmBodyColliders.update`), ").append(FRAMES).append(" frames of ")
                .append(fmt(DT)).append(" s, the first ").append(WARMUP).append(" skipped.\n\n");

        // ---- 1. the collider inventory, against the client's own log line ----------------------
        YsmBodyColliders body = rig.colliders();
        assertNotNull(body, "the model's own leg geometry must produce collision volumes");
        report.append("## 1. The collision volumes this model gets\n\n")
                .append("Read through production's own `YsmBodyColliders.fromGeometry` from the ")
                .append("converted mesh's leg parts, in the frame the solver reads them. The client ")
                .append("logged this model's volumes as `joint1@(0.14,0.41,-0.03) r=0.12 len=0.22, ")
                .append("joint4@(-0.17,0.44,-0.12) r=0.12 len=0.22, joint2@(0.18,0.0,-0.03) r=0.15 ")
                .append("len=0.19, joint5@(-0.24,0.06,-0.24) r=0.15 len=0.19, joint8@(0.0,1.07,-0.02) ")
                .append("r=0.13, joint9@(-0.02,1.41,-0.03) r=0.1` (latest.log 18:30:56.004).\n\n")
                .append("| # | joint | end0 | end1 | span | tube r |\n|---|---|---|---|---|---|\n");
        for (int i = 0; i < body.count(); i++) {
            float[] v = body.resolvedVolume(i);
            Vector3f e0 = new Vector3f(v[0], v[1], v[2]);
            Vector3f e1 = new Vector3f(v[4], v[5], v[6]);
            report.append("| ").append(i).append(" | joint").append(body.jointOf(i)).append(" | ")
                    .append(point(e0)).append(" | ").append(point(e1)).append(" | ")
                    .append(fmt(e0.distance(e1))).append(" | ").append(fmt(v[7])).append(" |\n");
        }
        report.append("\n");
        int limbs = 0;
        StringBuilder joints = new StringBuilder();
        for (int i = 0; i < body.count(); i++) {
            int joint = body.jointOf(i);
            joints.append(joints.length() == 0 ? "" : ",").append(joint);
            if (isLimb(joint)) {
                limbs++;
            }
        }
        report.append("Volumes: ").append(body.count()).append(" (joints [").append(joints)
                .append("]); of them **").append(limbs).append(" are limb capsules**, on the thigh, ")
                .append("shin and knee joints.\n\n");
        report.append(rig.gatheringReport());
        report.append(rig.frameReport());
        report.append(rig.limbGeometryReport());
        report.append(rig.skirtClearanceReport());
        report.append(rig.clearanceReport());
        // Written before any assertion: a failure must leave the numbers behind rather than a
        // message about them.
        Path out = Paths.get("build", "reports", "ysm-leg-skirt-collision.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        assertTrue(limbs >= 4, "the model's leg geometry must produce limb capsules, not only the "
                + "chest and head spheres; joints seen: [" + joints + "]");
        for (int joint : new int[]{1, 4, 2, 5}) {
            assertTrue(rig.rawLeg.containsKey(joint),
                    "the model must carry geometry on limb joint " + joint);
        }

        // ---- 2. the gaits ----------------------------------------------------------------------
        String worstPanel = null;
        float worstPenetration = 0.0F;
        float leastClearance = Float.MAX_VALUE;
        String leastPanel = null;
        report.append(colliderComparison(rig));
        for (Gait gait : GAITS) {
            GaitRun withLegs = run(rig, gait, body);
            GaitRun withoutLegs = run(rig, gait, rig.coreColliders());
            GaitRun generous = run(rig, gait, rig.fullColliders());
            report.append(gaitSection(gait, withLegs, withoutLegs, generous));
            for (Map.Entry<String, float[]> entry : withLegs.penetration.entrySet()) {
                if (entry.getValue()[1] > worstPenetration) {
                    worstPenetration = entry.getValue()[1];
                    worstPanel = entry.getKey() + " (" + gait.name() + ")";
                }
            }
            for (Map.Entry<String, float[]> entry : withLegs.raw.entrySet()) {
                if (entry.getValue()[0] < leastClearance) {
                    leastClearance = entry.getValue()[0];
                    leastPanel = entry.getKey() + " (" + gait.name() + ")";
                }
            }
        }

        // ---- 3. the point tested against the point drawn ---------------------------------------
        GaitRun sprint = run(rig, SPRINT, body);
        report.append(lagSection(SPRINT, sprint));

        float worstLag = 0.0F;
        String worstLagPiece = null;
        for (Map.Entry<String, float[]> entry : sprint.lag.entrySet()) {
            if (entry.getValue()[1] > worstLag) {
                worstLag = entry.getValue()[1];
                worstLagPiece = entry.getKey();
            }
        }
        report.append("\n**Worst drawn-vs-tested point gap: ").append(fmt(worstLag))
                .append(" blocks** (`").append(worstLagPiece).append("`), against limb capsule radii ")
                .append("of ").append(fmt(smallestLimbRadius(body))).append(" to ")
                .append(fmt(largestLimbRadius(body))).append(" blocks.\n");
        // Written before any assertion: a failure must leave the numbers behind rather than a
        // message about them.
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // ---- the assertions ---------------------------------------------------------------------
        assertNotNull(leastPanel, "the mesh clearance must be measured for at least one panel");
        assertTrue(leastClearance > 0.0F,
                "the drawn skirt's own mesh must not overlap the drawn leg's own mesh anywhere in "
                        + "any measured gait, or the report's defect is present in this measurement; "
                        + "the closest approach is " + fmt(leastClearance) + " blocks on "
                        + leastPanel);
        report.append("\n**Closest the drawn skirt comes to the drawn leg over the sprint: ")
                .append(fmt(leastClearance)).append(" blocks** (`").append(leastPanel)
                .append("`); the deepest any panel entered a limb capsule was ")
                .append(fmt(worstPenetration)).append(" blocks");
        if (worstPanel != null) {
            report.append(" (`").append(worstPanel).append("`)");
        }
        report.append(".\n");
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        assertTrue(worstLag > 0.10F,
                "the point the solver tests and the point the piece is drawn at must be measurably "
                        + "different - whether a leg collision can be trusted turns on it; the worst "
                        + "is " + fmt(worstLag) + " blocks on " + worstLagPiece);
    }

    /**
     * The claim the round's third question turns on, asserted on its own: the point production tests
     * a piece against is the piece's <b>resting</b> place under the pose, not the point the piece is
     * drawn at.
     *
     * <p>The solver's contact point is rebuilt exactly as
     * {@code YsmDynamicBoneSolver#resolveCollisions} builds it - {@code deformation(bindPivot) +
     * restDir * lever} - and the drawn centre of mass is the same point carried through the parent
     * chain's deltas and the piece's own. The two disagree by the whole of what the simulation did to
     * the piece and to everything above it.
     */
    @Test
    void thePointTheSolverTestsIsNotThePointThePieceIsDrawnAt() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");
        Rig rig = Rig.load(pack, MAID, LOGGED_MAID_SIMULATED);
        GaitRun sprint = run(rig, SPRINT, rig.colliders());

        float worst = 0.0F;
        String worstName = null;
        for (Map.Entry<String, float[]> entry : sprint.lag.entrySet()) {
            if (entry.getValue()[1] > worst) {
                worst = entry.getValue()[1];
                worstName = entry.getKey();
            }
        }
        assertNotNull(worstName);
        assertTrue(worst > 0.10F, "the tested point must differ from the drawn point by more than a "
                + "limb capsule's radius; worst " + fmt(worst) + " on " + worstName);
        int over = 0;
        int total = 0;
        for (Map.Entry<String, float[]> entry : sprint.lag.entrySet()) {
            total++;
            if (entry.getValue()[1] > 0.05F) {
                over++;
            }
        }
        assertTrue(over * 2 > total, "the gap must be the rule rather than one piece's accident: "
                + over + " of " + total + " measured pieces exceed 0.05 blocks");
    }

    /**
     * <b>Which storage convention the delta is built in</b> - and it is not a matter of opinion.
     *
     * <p>{@code buildSegmentDelta} writes a JOML {@code Matrix4f} through JOML's own
     * {@code translate}/{@code rotate}, while every other matrix the mesh touches is an Epic Fight
     * {@code OpenMatrix4f} read by {@code YsmMeshSecondaryMotion.transformPoint}. The two could in
     * principle disagree about which element holds what, and if they did, applying the delta to a
     * bind-space point and then the deformation would mix two conventions - which is exactly the class
     * of fault R5-1 found, one layer down.
     *
     * <p>The oracle is a rotation whose action is known without any convention: a +90 degree rotation
     * about Z sends the +X axis to the +Y axis. A matrix that does that under the mesh's own
     * {@code transformPoint} is in the mesh's convention; a matrix that sends +X to -Y is stored the
     * other way round, because that is what the transpose does to a 90 degree rotation.
     *
     * <p>This runs with no model and no install, so it cannot be skipped by a missing fixture: it is
     * the one assertion in this line of work that has to hold before any drawn-point number means
     * anything.
     */
    @Test
    void theDeltaIsBuiltInTheConventionTheMeshReads() {
        Vector3f anchor = new Vector3f(0.137F, -0.212F, 0.041F);
        Quaternionf quarterTurn = new Quaternionf().rotateAxis((float) (Math.PI * 0.5), 0.0F, 0.0F, 1.0F);
        Matrix4f delta = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(anchor, quarterTurn, delta);

        // The action the mesh would compute, read through the production reader. `toOpen` copies the
        // sixteen elements ONE FOR ONE - it does not transpose - so this is exactly the matrix the
        // render path would hand the reader.
        //
        // The DIRECTION reader and not the point reader: `buildSegmentDelta` is `T(a) x R x T(-a)`, so
        // the translation is part of the answer for a point, and this question is about the rotation.
        // Reading the linear part is what makes the oracle a statement about the layout rather than
        // about the anchor - a point reading folds in `a - R a` and would report a layout fault for a
        // correct matrix, which is the mistake this test made in its first form.
        Vector3f turned = YsmMeshSecondaryMotion.transformDirection(toOpen(delta),
                new Vector3f(1.0F, 0.0F, 0.0F), new Vector3f());
        assertTrue(Math.abs(turned.x) < 1.0E-4F && Math.abs(turned.y - 1.0F) < 1.0E-4F,
                "buildSegmentDelta must produce a matrix whose LINEAR PART the mesh's own reader reads "
                        + "as a rotation by the quaternion it was given: a +90 degree turn about Z has "
                        + "to send +X to +Y, and this one sends +X to " + point(turned));

        // The delta is a rigid motion in that same reading: it fixes its anchor and preserves lengths.
        // The POINT reader is the right one here, because "fixes the anchor" is a statement about a
        // point - and it is the identity `buildSegmentDelta` is documented to satisfy.
        Vector3f fixed = YsmMeshSecondaryMotion.transformPoint(toOpen(delta),
                new Vector3f(anchor), new Vector3f());
        assertTrue(fixed.distance(anchor) < 1.0E-4F, "the delta must fix the bind anchor it was built "
                + "about under the mesh's own POINT reader: it moves " + point(anchor) + " to "
                + point(fixed));
        Vector3f probe = new Vector3f(0.44F, 0.31F, -0.27F);
        Vector3f moved = YsmMeshSecondaryMotion.transformPoint(toOpen(delta),
                new Vector3f(probe), new Vector3f());
        Vector3f offset = new Vector3f(probe).sub(anchor);
        Vector3f movedOffset = new Vector3f(moved).sub(anchor);
        assertTrue(Math.abs(offset.length() - movedOffset.length()) < 1.0E-4F,
                "the delta must be rigid under the mesh's own reader: " + fmt(offset.length())
                        + " became " + fmt(movedOffset.length()));

        // ...and the readers must tolerate being handed the same object twice, because both the probe
        // and the mesh do it (`transformPoint(deformation, point, point)`). If they did not, a
        // `drawnCentre` that lands a centimetre from its own algebra would be an aliasing bug wearing
        // the costume of a precision limit - and rewriting a value in place while reading it is
        // exactly the kind of fault that produces a small, plausible, wrong number.
        Vector3f aliased = new Vector3f(probe);
        YsmMeshSecondaryMotion.transformPoint(toOpen(delta), aliased, aliased);
        Vector3f separate = YsmMeshSecondaryMotion.transformPoint(toOpen(delta),
                new Vector3f(probe), new Vector3f());
        assertTrue(aliased.distance(separate) < 1.0E-6F, "`transformPoint(m, v, v)` must equal "
                + "`transformPoint(m, v, new)`: " + point(aliased) + " against " + point(separate));
        Vector3f aliasedDirection = new Vector3f(probe);
        YsmMeshSecondaryMotion.transformDirection(toOpen(delta), aliasedDirection, aliasedDirection);
        Vector3f separateDirection = YsmMeshSecondaryMotion.transformDirection(toOpen(delta),
                new Vector3f(probe), new Vector3f());
        assertTrue(aliasedDirection.distance(separateDirection) < 1.0E-6F,
                "`transformDirection(m, v, v)` must equal `transformDirection(m, v, new)`: "
                        + point(aliasedDirection) + " against " + point(separateDirection));
        Vector3f aliasedJoml = new Vector3f(probe);
        delta.transformDirection(aliasedJoml);
        Vector3f separateJoml = delta.transformDirection(new Vector3f(probe));
        assertTrue(aliasedJoml.distance(separateJoml) < 1.0E-6F,
                "JOML's `transformDirection` must agree with itself on an aliased argument: "
                        + point(aliasedJoml) + " against " + point(separateJoml));
    }

    // ------------------------------------------------------------------
    // One gait
    // ------------------------------------------------------------------

    /** What one run measured. */
    private static final class GaitRun {
        /** panel -> {mean penetration where inside, max penetration, share of frames inside}. */
        final Map<String, float[]> penetration = new LinkedHashMap<>();
        /** piece -> {mean gap, max gap} between the solver's contact point and the drawn one. */
        final Map<String, float[]> lag = new LinkedHashMap<>();
        /** piece -> the most of a frame the solver's collision turn was worth, blocks. */
        final Map<String, Float> contact = new LinkedHashMap<>();
        /** panel -> {sum forward, sum backward} of the penetration, along the model's forward. */
        final Map<String, float[]> direction = new LinkedHashMap<>();
        /** panel -> {least clearance to the leg geometry, greatest} over the run, blocks. */
        final Map<String, float[]> raw = new LinkedHashMap<>();
        int frames;
    }

    private static GaitRun run(Rig rig, Gait gait, YsmBodyColliders colliders) {
        YsmPhysicsParts.Model parts = rig.model();
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                parts, colliders, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        // The user's own physics_overrides file for this model, applied exactly where the frame path
        // applies it: this round measures the model as it is deployed, not a stripped one.
        YsmPhysicsOverrides.markOverrides(rig.modelId, rig.bones, parts.segments(),
                state.held, state.limit);
        RunPose pose = new RunPose(rig, gait, 1.0F);
        Vector3f velocity = new Vector3f(0.0F, 0.0F, -gait.speed());
        GaitRun out = new GaitRun();
        Map<String, float[]> penAcc = new HashMap<>();
        Map<String, int[]> penFrames = new HashMap<>();
        Map<String, float[]> dirAcc = new HashMap<>();
        Map<String, float[]> lagAcc = new HashMap<>();
        Map<String, float[]> rawAcc = new HashMap<>();
        Map<String, Float> contactMax = new HashMap<>();
        int frames = 0;
        int segments = rig.segments.size();
        OpenMatrix4f[] deformations = new OpenMatrix4f[segments];
        for (int frame = 0; frame < FRAMES; frame++) {
            pose.at(frame * DT);
            YsmMeshSecondaryMotion.simulate(state, pose, DT, velocity, NO_TURN, colliders);
            if (frame < WARMUP) {
                continue;
            }
            frames++;
            // The frame's deformations, exactly as resolveSegment computes them: pose x toOrigin.
            for (int i = 0; i < segments; i++) {
                Segment piece = rig.segments.get(i);
                OpenMatrix4f toOrigin = pose.toOriginOf(piece.joint);
                OpenMatrix4f jointPose = pose.poseOf(piece.joint);
                deformations[i] = toOrigin == null || jointPose == null
                        ? new OpenMatrix4f()
                        : OpenMatrix4f.mul(jointPose, toOrigin, new OpenMatrix4f());
            }
            // Every piece's composed drawn delta, once per frame.
            Matrix4f[] deltas = new Matrix4f[segments];
            for (int i = 0; i < segments; i++) {
                deltas[i] = rig.drawnDelta(state, i);
            }
            // The leg capsules, placed from the same deformations: a limb volume is rigid with the
            // bone it belongs to, and the transform that places it is that bone's own pose x toOrigin.
            for (Map.Entry<Integer, List<float[]>> entry : rig.bindCapsules.entrySet()) {
                int joint = entry.getKey();
                int segmentIndex = rig.jointSegment(joint);
                if (segmentIndex < 0) {
                    continue;
                }
                OpenMatrix4f deformation = deformations[segmentIndex];
                for (float[] capsule : entry.getValue()) {
                    Vector3f end0 = YsmMeshSecondaryMotion.transformPoint(deformation,
                            new Vector3f(capsule[0], capsule[1], capsule[2]), new Vector3f());
                    Vector3f end1 = YsmMeshSecondaryMotion.transformPoint(deformation,
                            new Vector3f(capsule[3], capsule[4], capsule[5]), new Vector3f());
                    for (int index : rig.panelIndexes) {
                        Segment piece = rig.segments.get(index);
                        float depth = rig.penetration(piece, deformations[index], deltas[index],
                                end0, end1, capsule[6]);
                        if (depth <= 0.0F) {
                            continue;
                        }
                        penFrames.computeIfAbsent(piece.name, key -> new int[1])[0]++;
                        float[] acc = penAcc.computeIfAbsent(piece.name, key -> new float[3]);
                        acc[0] += depth;
                        acc[1] = Math.max(acc[1], depth);
                        Vector3f centre = rig.drawnCentre(piece, deformations[index], deltas[index]);
                        Vector3f offset = new Vector3f(centre).sub(rig.bodyCentre);
                        float[] dir = dirAcc.computeIfAbsent(piece.name, key -> new float[2]);
                        if (offset.dot(FORWARD) < 0.0F) {
                            dir[0] += depth;
                        } else {
                            dir[1] += depth;
                        }
                    }
                }
            }
            // The skirt against the LEG GEOMETRY itself, which is what "clipping through" is: the
            // legs' own vertices and the panels' own vertices, in the frame both are drawn in. A
            // negative clearance is the two bodies overlapping.
            for (Map.Entry<Integer, List<Vector3f>> entry : rig.legPose.entrySet()) {
                OpenMatrix4f deformation = rig.deformationFor(pose, entry.getKey());
                List<Vector3f> placed = new ArrayList<>(entry.getValue().size());
                for (int at = 0; at < entry.getValue().size(); at += 4) {
                    placed.add(YsmMeshSecondaryMotion.transformPoint(deformation,
                            entry.getValue().get(at), new Vector3f()));
                }
                for (int index : rig.panelIndexes) {
                    Segment piece = rig.segments.get(index);
                    float clearance = rig.clearanceTo(piece, deformations[index], deltas[index], placed);
                    float[] acc = rawAcc.computeIfAbsent(piece.name,
                            key -> new float[]{Float.MAX_VALUE, -Float.MAX_VALUE});
                    acc[0] = Math.min(acc[0], clearance);
                    acc[1] = Math.max(acc[1], clearance);
                }
            }
            for (int i = 0; i < segments; i++) {
                Segment piece = rig.segments.get(i);
                Vector3f tested = rig.testedCentre(piece, deformations[i]);
                Vector3f drawn = rig.drawnCentre(piece, deformations[i], deltas[i]);
                float gap = tested.distance(drawn);
                float[] acc = lagAcc.computeIfAbsent(piece.name, key -> new float[3]);
                acc[0] += gap;
                acc[1] = Math.max(acc[1], gap);
                acc[2] += 1.0F;
                contactMax.merge(piece.name, state.lastContact[i], Math::max);
            }
        }
        out.frames = frames;
        for (Map.Entry<String, float[]> entry : rawAcc.entrySet()) {
            out.raw.put(entry.getKey(), new float[]{entry.getValue()[0], entry.getValue()[1]});
        }
        for (Map.Entry<String, float[]> entry : penAcc.entrySet()) {
            float[] acc = entry.getValue();
            int seen = penFrames.get(entry.getKey())[0];
            out.penetration.put(entry.getKey(),
                    new float[]{acc[0] / Math.max(1, seen), acc[1], (float) seen / frames});
            out.direction.put(entry.getKey(), dirAcc.get(entry.getKey()));
        }
        for (Map.Entry<String, float[]> entry : lagAcc.entrySet()) {
            float[] acc = entry.getValue();
            out.lag.put(entry.getKey(), new float[]{acc[0] / Math.max(1.0F, acc[2]), acc[1]});
        }
        out.contact.putAll(contactMax);
        return out;
    }

    /**
     * The collider the shipped frame path builds, and the same rule fed the limb geometry with the
     * hidden-bone filter <b>not</b> applied.
     *
     * <p>Two are reported because the two disagree, and the disagreement is not cosmetic: the
     * shipped gathering excludes the bones this model hides in its default form, and on this model
     * that is what the thigh's own second part is. The generous set is the one that reproduces the
     * numbers the client printed, so it is the better model of what the shipped build places; the
     * shipped set is what production's own code does with this artefact, so it is the better model of
     * what this probe can prove. Every gait is run against both, and against a set with no limb
     * volumes at all, so the answer does not depend on which one is right.
     */
    private static String colliderComparison(Rig rig) {
        StringBuilder out = new StringBuilder();
        out.append("## 1b. The limb volumes: the shipped gathering against the generous one\n\n")
                .append("Both are `YsmBodyColliders.fromGeometry` on this model's own limb parts. ")
                .append("`shipped` is the gathering the frame path performs (the model's default-hidden ")
                .append("bones excluded); `generous` is the same parts with that filter left off. The ")
                .append("client's printed number is repeated for each joint.\n\n")
                .append("| joint | set | end0 | end1 | span | tube r | client span / r |\n")
                .append("|---|---|---|---|---|---|---|\n");
        appendColliderRows(out, "shipped", rig.colliders());
        appendColliderRows(out, "generous", rig.fullColliders());
        out.append('\n');
        return out.toString();
    }

    private static void appendColliderRows(StringBuilder out, String label, YsmBodyColliders colliders) {
        if (colliders == null) {
            return;
        }
        for (int i = 0; i < colliders.count(); i++) {
            int joint = colliders.jointOf(i);
            if (!isLimb(joint)) {
                continue;
            }
            float[] v = colliders.resolvedVolume(i);
            Vector3f e0 = new Vector3f(v[0], v[1], v[2]);
            Vector3f e1 = new Vector3f(v[4], v[5], v[6]);
            out.append("| ").append(joint).append(" | ").append(label).append(" | ")
                    .append(point(e0)).append(" | ").append(point(e1)).append(" | ")
                    .append(fmt(e0.distance(e1))).append(" | ").append(fmt(v[7])).append(" | ")
                    .append(clientSpan(joint)).append(" |\n");
        }
    }

    private static String clientSpan(int joint) {
        switch (joint) {
            case 1:
            case 4:
                return "0.22 / 0.12";
            case 2:
            case 5:
                return "0.19 / 0.15";
            default:
                return "-";
        }
    }

    private static String gaitSection(Gait gait, GaitRun withLegs, GaitRun withoutLegs,
                                      GaitRun generous) {
        StringBuilder out = new StringBuilder();
        out.append("## 2. The skirt inside the legs, ").append(gait.name()).append("\n\n")
                .append("Penetration is read per **vertex of the panel's own geometry**, placed in ")
                .append("the drawn frame (pose deformation, then the parent chain's deltas, then the ")
                .append("panel's own), as the distance that vertex is inside the nearest leg ")
                .append("capsule's surface - the drawn skirt against the leg geometry of the same ")
                .append("part, in the same frame. All values in blocks.\n\n")
                .append("`frames` is the share of measured frames in which the panel enters a leg at ")
                .append("all; `mean` and `max` are the deepest vertex's penetration over those ")
                .append("frames. `fwd` and `back` split the same penetration by which side of the ")
                .append("body's centre the panel's drawn centre of mass is on, along the model's own ")
                .append("forward (-Z, the direction the tail is not on). The last three columns are ")
                .append("the same measurement under three collider sets: **no legs** (torso and head ")
                .append("only), the **shipped** gathering, and the **generous** one. `mesh` is the ")
                .append("least distance between the panel's drawn vertices and the leg's drawn ")
                .append("vertices over the whole run - **negative is the two bodies overlapping**, ")
                .append("which is what the report means by clipping through.\n\n")
                .append("| panel | frames | mean | max | fwd | back | capsule: no legs | shipped | ")
                .append("generous | mesh overlap |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        List<String> names = new ArrayList<>(withLegs.raw.keySet());
        for (String name : withLegs.penetration.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        for (String name : withoutLegs.penetration.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        for (String name : generous.penetration.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        names.sort((a, b) -> Float.compare(maxOf(b, withLegs, withoutLegs, generous),
                maxOf(a, withLegs, withoutLegs, generous)));
        for (String name : names) {
            float[] p = withLegs.penetration.get(name);
            float[] d = withLegs.direction.get(name);
            out.append("| `").append(name).append("` | ")
                    .append(fmt(p == null ? 0.0F : p[2])).append(" | ")
                    .append(fmt(p == null ? 0.0F : p[0])).append(" | ")
                    .append(fmt(p == null ? 0.0F : p[1])).append(" | ")
                    .append(fmt(d == null ? 0.0F : d[0])).append(" | ")
                    .append(fmt(d == null ? 0.0F : d[1])).append(" | ")
                    .append(fmt(maxOf(name, withoutLegs))).append(" | ")
                    .append(fmt(maxOf(name, withLegs))).append(" | ")
                    .append(fmt(maxOf(name, generous))).append(" | ")
                    .append(fmt(clearanceOf(name, withLegs, withoutLegs))).append(" |\n");
        }
        out.append("\n");
        return out.toString();
    }

    private static float maxOf(String name, GaitRun... runs) {
        float worst = 0.0F;
        for (GaitRun run : runs) {
            float[] value = run.penetration.get(name);
            if (value != null) {
                worst = Math.max(worst, value[1]);
            }
        }
        return worst;
    }

    /** The least distance the panel's mesh ever came to the leg's mesh, over the runs given. */
    private static float clearanceOf(String name, GaitRun... runs) {
        float least = Float.MAX_VALUE;
        for (GaitRun run : runs) {
            float[] value = run.raw.get(name);
            if (value != null) {
                least = Math.min(least, value[0]);
            }
        }
        return least == Float.MAX_VALUE ? Float.NaN : least;
    }

    private static String lagSection(Gait gait, GaitRun run) {
        StringBuilder out = new StringBuilder();
        out.append("## 3. The point the solver tests against the point drawn - ").append(gait.name())
                .append("\n\n")
                .append("`tested` is rebuilt exactly as `YsmDynamicBoneSolver#resolveCollisions` ")
                .append("builds it: `deformation(bindPivot) + restDir * lever`, with the rest ")
                .append("direction the pose gives the piece - **no delta of any kind**. `drawn` is the ")
                .append("same point carried through the parent chain's deltas and the piece's own - ")
                .append("the frame the mesh receives. `gap` is the distance between them; `turn` is ")
                .append("the most the solver's collision response moved any piece in any one frame, ")
                .append("blocks.\n\n")
                .append("| piece | mean gap | max gap | worst collision turn |\n|---|---|---|---|\n");
        List<String> names = new ArrayList<>(run.lag.keySet());
        names.sort((a, b) -> Float.compare(run.lag.get(b)[1], run.lag.get(a)[1]));
        for (String name : names) {
            if (run.lag.get(name)[1] < 0.05F) {
                continue;
            }
            out.append("| `").append(name).append("` | ").append(fmt(run.lag.get(name)[0]))
                    .append(" | ").append(fmt(run.lag.get(name)[1])).append(" | ")
                    .append(fmt(run.contact.getOrDefault(name, 0.0F))).append(" |\n");
        }
        out.append("\n");
        return out.toString();
    }

    private static boolean isLimb(int joint) {
        for (int candidate : LIMB_JOINTS) {
            if (candidate == joint) {
                return true;
            }
        }
        return false;
    }

    private static float smallestLimbRadius(YsmBodyColliders colliders) {
        float smallest = Float.MAX_VALUE;
        for (int i = 0; i < colliders.count(); i++) {
            if (isLimb(colliders.jointOf(i))) {
                smallest = Math.min(smallest, colliders.resolvedVolume(i)[7]);
            }
        }
        return smallest;
    }

    private static float largestLimbRadius(YsmBodyColliders colliders) {
        float largest = 0.0F;
        for (int i = 0; i < colliders.count(); i++) {
            if (isLimb(colliders.jointOf(i))) {
                largest = Math.max(largest, colliders.resolvedVolume(i)[7]);
            }
        }
        return largest;
    }

    // ------------------------------------------------------------------
    // The pose
    // ------------------------------------------------------------------

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

        void at(float seconds) {
            float phase = (float) (2.0D * Math.PI * gait.hertz() * seconds);
            float bob = gait.bob() * (float) Math.sin(2.0F * phase);
            // Each joint's pose is the CUMULATIVE transform of its chain, which is what
            // {@code ArmaturePose#poseOf} hands the solver (the skinning's own pose matrices), so the
            // torso's turn is composed into every joint under it. A pose that did not nest would
            // measure a leg standing still while the skirt swung.
            torso = about(rig.jointOrigin(JointTable.TORSO), gait.leanDegrees() * leanSign, bob);
            chest = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.CHEST),
                            gait.leanDegrees() * 0.55F * leanSign
                                    + 2.5F * (float) Math.sin(2.0F * phase),
                            bob * 0.5F), new OpenMatrix4f());
            head = OpenMatrix4f.mul(chest,
                    about(rig.jointOrigin(JointTable.HEAD),
                            -gait.leanDegrees() * 0.45F * leanSign
                                    + 3.5F * (float) Math.sin(2.0F * phase + 1.0F),
                            0.0F), new OpenMatrix4f());
            thighRight = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.THIGH_R),
                            gait.strideSign() * gait.strideDegrees() * (float) Math.sin(phase), 0.0F),
                    new OpenMatrix4f());
            thighLeft = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.THIGH_L),
                            gait.strideSign() * gait.strideDegrees()
                                    * (float) Math.sin(phase + Math.PI), 0.0F),
                    new OpenMatrix4f());
            legRight = OpenMatrix4f.mul(thighRight,
                    about(rig.jointOrigin(JointTable.LEG_R),
                            gait.kneeDegrees() * (float) Math.sin(phase + 2.0F), 0.0F),
                    new OpenMatrix4f());
            legLeft = OpenMatrix4f.mul(thighLeft,
                    about(rig.jointOrigin(JointTable.LEG_L),
                            gait.kneeDegrees() * (float) Math.sin(phase + Math.PI + 2.0F), 0.0F),
                    new OpenMatrix4f());
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

    /** A rotation about a joint's own origin, plus a vertical bob. */
    private static OpenMatrix4f about(Vector3f origin, float degrees, float bob) {
        Matrix4f matrix = new Matrix4f()
                .translate(origin.x, origin.y + bob, origin.z)
                .rotateX((float) Math.toRadians(degrees))
                .translate(-origin.x, -origin.y, -origin.z);
        return toOpen(matrix);
    }

    // ------------------------------------------------------------------
    // The rig
    // ------------------------------------------------------------------

    /** One simulated piece, and the geometry the measurement needs from it. */
    static final class Segment {
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
        final List<Vector3f> own;
        final Vector3f bindAnchor;

        Segment(int index, int boneIndex, String name, int joint, Vector3f bindPivot, Vector3f bindRest,
                float lever, float radius, float mass, float maxAngle, float verticalFollow, int parent,
                int jointsInPiece, int jointsLeft, List<Vector3f> own, Vector3f bindAnchor) {
            this.index = index;
            this.boneIndex = boneIndex;
            this.name = name;
            this.joint = joint;
            this.bindPivot = bindPivot;
            this.bindRest = bindRest;
            this.lever = lever;
            this.radius = radius;
            this.mass = mass;
            this.maxAngle = maxAngle;
            this.verticalFollow = verticalFollow;
            this.parent = parent;
            this.jointsInPiece = jointsInPiece;
            this.jointsLeft = jointsLeft;
            this.own = own;
            this.bindAnchor = bindAnchor;
        }
    }

    /** The model, its geometry, its segments and its armature. */
    static final class Rig {
        final String modelId;
        final YSMRuntimeModel.BoneRt[] bones;
        final Map<Integer, Vector3f> pivotCache = new HashMap<>();
        final List<Segment> segments = new ArrayList<>();
        final Map<Integer, OpenMatrix4f> restWorld = new HashMap<>();
        /** joint -> the bind-space capsules of the limb volumes on it: end0, end1, radius. */
        final Map<Integer, List<float[]>> bindCapsules = new LinkedHashMap<>();
        /**
         * The limb geometry the re-binding probe reads, put here by that probe: per limb joint, the
         * mesh's own <b>true</b> vertices (once each, decoded through {@link #meshGeometry}) and the
         * triangles they are drawn as - the surfaces a point-to-point metric cannot see.
         */
        final Map<Integer, List<Vector3f>> limbVertices = new LinkedHashMap<>();
        final Map<Integer, List<int[]>> limbTriangles = new LinkedHashMap<>();
        final Map<Integer, Float> limbLongestEdge = new LinkedHashMap<>();
        /** The limb geometry as loaded (permuted), and as it sits in the file: for the frame check. */
        private final Map<Integer, List<Vector3f>> rawLeg = new LinkedHashMap<>();
        private final Map<Integer, List<Vector3f>> rawLegUnpermuted = new LinkedHashMap<>();
        private final Map<Integer, List<Vector3f>> rawLegOf = new HashMap<>();
        /** The limb geometry with the hidden-bone filter NOT applied - the generous collider. */
        private final Map<Integer, List<Vector3f>> fullLeg = new LinkedHashMap<>();
        /** The limb geometry placed per frame: joint -> its own vertices. */
        final Map<Integer, List<Vector3f>> legPose = new LinkedHashMap<>();
        private YsmBodyColliders fullColliders;
        final List<Integer> panelIndexes = new ArrayList<>();
        final Set<String> loggedSimulated = new HashSet<>();
        /** The joints the volumes were actually built from, in build order, comma separated. */
        String gatheredJoints = "";
        /** bone -> how many vertices were gathered for it, and whether it was hidden. */
        final List<String> gatheringLog = new ArrayList<>();
        final Vector3f bodyCentre = new Vector3f();
        final float scaleX;
        final float scaleY;
        final Map<String, Integer> boneIndex = new HashMap<>();
        private final Map<Integer, Joint> joints = new HashMap<>();
        private Joint armature;
        private yesman.epicfight.model.armature.HumanoidArmature bindArmature;
        private final Map<Integer, List<Vector3f>> vertices = new HashMap<>();
        private final Map<Integer, List<Vector3f>> legVertices = new HashMap<>();
        private final Map<Integer, float[]> geometry = new HashMap<>();
        private YsmBodyColliders allColliders;
        private YsmBodyColliders coreColliders;
        private YsmPhysicsParts.Model model;
        /**
         * True for a rig assembled from a raw corpus {@code .ysm} rather than from this model's
         * converted artefacts: the "assembled set equals the set the client logged" assertion
         * describes one deployed model and cannot hold for a model nothing has logged.
         */
        private boolean corpus;

        private Rig(String modelId, YSMRuntimeModel.BoneRt[] bones, float scaleX, float scaleY) {
            this.modelId = modelId;
            this.bones = bones;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            for (int i = 0; i < bones.length; i++) {
                boneIndex.put(bones[i].name, i);
            }
        }

        static Rig load(Path pack, String stem, String loggedSimulatedCsv) throws IOException {
            Path runtimeFile = pack.resolve("ysm_runtime/entity").resolve(stem + ".json");
            Path meshFile = pack.resolve("animmodels/entity").resolve(stem + ".json");
            assertTrue(Files.isRegularFile(runtimeFile) && Files.isRegularFile(meshFile),
                    "the converted artefacts for '" + stem + "' are not in " + pack);
            return load(runtimeFile, meshFile, stem, loggedSimulatedCsv);
        }

        /**
         * The same loader over files named directly, so a probe can run against the bundled
         * snapshot of the converted artefacts instead of an installed instance - the round's
         * numbers must not depend on which build last wrote the instance's resource pack.
         */
        static Rig load(Path runtimeFile, Path meshFile, String stem, String loggedSimulatedCsv)
                throws IOException {
            assertTrue(Files.isRegularFile(runtimeFile) && Files.isRegularFile(meshFile),
                    "the converted artefacts for '" + stem + "' are not in " + runtimeFile.getParent());
            JsonObject runtime = JsonParser.parseString(
                    Files.readString(runtimeFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject mesh = JsonParser.parseString(
                    Files.readString(meshFile, StandardCharsets.UTF_8)).getAsJsonObject();

            JsonArray bonesJson = runtime.getAsJsonArray("bones");
            assertTrue(bonesJson != null, runtimeFile + " has no 'bones' array");
            YSMRuntimeModel.BoneRt[] bones = new YSMRuntimeModel.BoneRt[bonesJson.size()];
            Map<String, Integer> indexOf = new HashMap<>();
            for (int i = 0; i < bones.length; i++) {
                JsonObject bone = bonesJson.get(i).getAsJsonObject();
                YSMRuntimeModel.BoneRt rt = new YSMRuntimeModel.BoneRt();
                rt.name = bone.get("name").getAsString();
                JsonArray pivot = bone.getAsJsonArray("pivot");
                JsonArray rot = bone.getAsJsonArray("rot");
                // A converted runtime JSON of any generation carries both; when one does not, say
                // which file and which key rather than throwing out of a nested accessor.
                assertTrue(pivot != null && pivot.size() >= 3,
                        runtimeFile + " bone '" + rt.name + "' has no usable 'pivot' array");
                assertTrue(rot != null && rot.size() >= 3,
                        runtimeFile + " bone '" + rt.name + "' has no usable 'rot' array");
                JsonElement joint = bone.get("joint");
                assertTrue(joint != null && joint.isJsonPrimitive(),
                        runtimeFile + " bone '" + rt.name + "' has no 'joint'");
                rt.px = pivot.get(0).getAsFloat();
                rt.py = pivot.get(1).getAsFloat();
                rt.pz = pivot.get(2).getAsFloat();
                rt.rx = rot.get(0).getAsFloat();
                rt.ry = rot.get(1).getAsFloat();
                rt.rz = rot.get(2).getAsFloat();
                rt.joint = joint.getAsInt();
                rt.mapped = bone.has("mapped") && bone.get("mapped").getAsBoolean();
                bones[i] = rt;
                indexOf.put(rt.name, i);
            }
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
            JsonArray scaleJson = runtime.getAsJsonArray("scale");
            assertTrue(scaleJson != null && scaleJson.size() >= 2,
                    runtimeFile + " has no 'scale' section, so it predates the section the mesh's "
                            + "vertices are baked with and cannot describe the drawn model");
            float scaleX = scaleJson.get(0).getAsFloat();
            float scaleY = scaleJson.get(1).getAsFloat();
            Rig rig = new Rig(stem, bones, scaleX, scaleY);

            JsonObject withoutCamera = runtime.deepCopy();
            withoutCamera.remove("camera");
            Set<String> hidden = YSMRuntimeModel.computeDefaultHiddenBoneNames(withoutCamera);
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
                if (bone == null || hidden.contains(bones[bone].name)) {
                    continue;
                }
                JsonArray indices = entry.getValue().getAsJsonObject().getAsJsonArray("array");
                List<Vector3f> points = new ArrayList<>(indices.size());
                List<Vector3f> unpermuted = new ArrayList<>(indices.size());
                for (JsonElement index : indices) {
                    int at = index.getAsInt() * 3;
                    if (at + 2 >= positions.length) {
                        continue;
                    }
                    // The loader's own map out of the writer's Blender frame: (x, y, z) -> (x, z, -y).
                    points.add(new Vector3f(positions[at], positions[at + 2], -positions[at + 1]));
                    unpermuted.add(new Vector3f(positions[at], positions[at + 1], positions[at + 2]));
                }
                rig.vertices.computeIfAbsent(bone, key -> new ArrayList<>()).addAll(points);
                rig.rawLegOf.put(bone, points);
                if (isBodyJoint(bones[bone].joint)) {
                    rig.rawLegUnpermuted.computeIfAbsent(bones[bone].joint, key -> new ArrayList<>())
                            .addAll(unpermuted);
                }
                if (isLimb(bones[bone].joint)) {
                    // Gathered regardless of the hidden filter, because the calibration below is
                    // against a measurement of the limb's own geometry rather than of a collider.
                    rig.rawLeg.computeIfAbsent(bones[bone].joint, key -> new ArrayList<>())
                            .addAll(points);
                    rig.fullLeg.computeIfAbsent(bones[bone].joint, key -> new ArrayList<>())
                            .addAll(points);
                    rig.legPose.computeIfAbsent(bones[bone].joint, key -> new ArrayList<>())
                            .addAll(points);
                }
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
            for (String name : loggedSimulatedCsv.split(",")) {
                rig.loggedSimulated.add(name.trim());
            }
            rig.buildSegments(partOrdinals);
            rig.buildLegGeometry();
            rig.buildArmature(runtime, mesh, hidden);
            rig.buildColliders();
            rig.model = productionModel(rig);
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

        /**
         * How many mesh vertices this bone's own geometry carries - the fourth column of
         * {@code geometry}, which is exactly what {@code YsmPhysicsParts.ownsItsGeometry} compares
         * against its minimum. A reader, for the diagnostic that has to say WHY a rig assembled no
         * pieces: the map itself is this class's own business.
         */
        int ownVertexCount(int bone) {
            float[] entry = geometry.get(bone);
            return entry == null ? 0 : (int) entry[3];
        }

        /**
         * The bone's own baked vertices, or null when it carries none - the geometry a composition
         * rule swings when it moves that bone. A reader for the probes that measure a rule's effect
         * on the drawn mesh (the animator's rotation rule, over the corpus); the map itself is this
         * class's own business.
         */
        List<Vector3f> ownVertices(int bone) {
            return vertices.get(bone);
        }

        /**
         * Why the selection kept no pieces, by reason, and how many bones it offered.
         *
         * <p>"No pieces" on its own names none of the five ways a bone can be refused, and on a
         * corpus of nine hundred models the difference between "nothing hangs here" and "the
         * geometry predicate refused everything" is the whole diagnosis. Filled by
         * {@link #buildSegments(Map)}; read by the corpus assembly test's failure message.
         */
        final Map<String, Integer> buildDrops = new LinkedHashMap<>();
        int lastSelected;
        int lastDrafted;
        /** The mesh part ordinals the last selection was handed, for the same diagnostic. */
        private Map<Integer, int[]> selectionParts = new HashMap<>();

        /** The part ordinals {@code ownsItsGeometry} was given for this bone; empty when none. */
        int[] selectionParts(int bone) {
            int[] parts = selectionParts.get(bone);
            return parts == null ? new int[0] : parts;
        }

        private void drop(String reason) {
            buildDrops.merge(reason, 1, Integer::sum);
        }

        /** The segment list, assembled through the same production seams `build` uses. */
        private void buildSegments(Map<Integer, int[]> partOrdinals) {
            java.util.function.IntPredicate ownsGeometry =
                    index -> YsmPhysicsParts.ownsItsGeometry(index, geometry, partOrdinals);
            List<Integer> selected = YsmPhysicsParts.selectBones(bones, ownsGeometry,
                    YsmPhysicsTuning.maxChains(), new int[1]);
            lastSelected = selected.size();
            selectionParts = partOrdinals;
            buildDrops.clear();
            List<Integer> drafts = new ArrayList<>();
            for (int bone : selected) {
                if (bones[bone].joint < 0 || YsmPhysicsParts.poseBelongsToEpicFight(bones[bone])) {
                    drop("no joint, or Epic Fight owns the pose");
                    continue;
                }
                Vector3f pivot = pivot(bone);
                List<Vector3f> own = vertices.get(bone);
                Vector3f centre = own == null ? null : centroid(own);
                if (pivot == null || centre == null) {
                    drop("no pivot or no geometry of its own");
                    continue;
                }
                Vector3f rest = new Vector3f(centre).sub(pivot);
                float lever = rest.length();
                if (!Float.isFinite(lever) || lever < 0.01F) {
                    drop("lever shorter than 0.01 blocks");
                    continue;
                }
                if (YsmPhysicsParts.wrapsPivot(own, pivot)) {
                    drop("geometry wraps its pivot");
                    continue;
                }
                if (YsmPhysicsParts.risesOffPivot(own, pivot, rest, lever)) {
                    drop("geometry rises off its pivot");
                    continue;
                }
                drafts.add(bone);
            }
            lastDrafted = drafts.size();
            Map<Integer, Integer> segmentOfBone = new HashMap<>();
            for (int i = 0; i < drafts.size(); i++) {
                segmentOfBone.put(drafts.get(i), i);
            }
            int[] parentOf = new int[drafts.size()];
            for (int i = 0; i < drafts.size(); i++) {
                Integer parentBone = nearestSimulatedAncestor(drafts.get(i), segmentOfBone);
                parentOf[i] = parentBone == null ? -1 : segmentOfBone.get(parentBone);
            }
            int[] size = new int[drafts.size()];
            for (int i = 0; i < drafts.size(); i++) {
                int top = i;
                int guard = 0;
                for (int parent = parentOf[i];
                     parent >= 0 && parent != top && guard++ <= drafts.size();
                     parent = parentOf[parent]) {
                    top = parent;
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
                for (int at = i, guard = 0; parentOf[at] >= 0 && guard++ <= drafts.size();
                     at = parentOf[at]) {
                    root = parentOf[at];
                }
                int inPiece = Math.max(1, size[root]);
                List<Vector3f> restsOn = YsmPhysicsParts.restsOnGeometry(bones, bone, vertices);
                Vector3f anchor = YsmPhysicsParts.contactAnchor(own, restsOn, pivot, lever);
                Segment segment = new Segment(i, bone, rt.name, rt.joint, pivot, rest, lever,
                        YsmPhysicsParts.radiusFor(own, pivot, rest),
                        Math.max(0.25F, Math.min(4.0F, own.size() / 64.0F)),
                        YsmPhysicsParts.swingLimit(parent >= 0, rt.joint == JointTable.TORSO,
                                (float) YsmPhysicsTuning.DEFAULTS.maxAngle,
                                (float) YsmPhysicsTuning.DEFAULTS.maxAngleRoot),
                        category.weight * (float) YsmPhysicsTuning.gravityFollowScale(),
                        parent, inPiece, Math.max(1, inPiece - depthOf(parentOf, i)), own, anchor);
                segments.add(segment);
            }
            if (!corpus) {
                assertEquals(loggedSimulated.size(), segments.size(),
                        "the assembled simulated set must be the one the client logged; assembled "
                                + segments.size() + ", logged " + loggedSimulated.size());
                for (Segment segment : segments) {
                    assertTrue(loggedSimulated.contains(segment.name),
                            segment.name + " is not in the set the client logged for this model");
                }
            }
            for (Segment segment : segments) {
                if (isPanel(segment.name)) {
                    panelIndexes.add(segment.index);
                }
            }
            Vector3f acc = new Vector3f();
            int count = 0;
            for (Segment segment : segments) {
                for (Vector3f vertex : segment.own) {
                    acc.add(vertex);
                    count++;
                }
            }
            bodyCentre.set(count == 0 ? acc : acc.div(count));
        }

        private static int depthOf(int[] parentOf, int index) {
            int depth = 0;
            int guard = 0;
            for (int at = parentOf[index]; at >= 0 && guard++ <= parentOf.length; at = parentOf[at]) {
                depth++;
            }
            return depth;
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

        /** The leg geometry the collider is built from: production's own gathering rule. */
        private void buildLegGeometry() {
            for (Map.Entry<Integer, List<Vector3f>> entry : vertices.entrySet()) {
                YSMRuntimeModel.BoneRt bone = bones[entry.getKey()];
                if (!bone.mapped || !isBodyJoint(bone.joint)) {
                    continue;
                }
                gatheredJoints = gatheredJoints.isEmpty() ? String.valueOf(bone.joint)
                        : gatheredJoints + "," + bone.joint;
                gatheringLog.add("| `" + bone.name + "` | " + bone.joint + " | "
                        + entry.getValue().size() + " | "
                        + (isLimb(bone.joint) ? "capsule" : "sphere") + " |");
                if (!isLimb(bone.joint)) {
                    continue;
                }
                legVertices.computeIfAbsent(bone.joint, key -> new ArrayList<>()).addAll(entry.getValue());
            }
        }

        /** The gathering, per bone, so a missing volume can be read rather than guessed at. */
        String gatheringReport() {
            StringBuilder out = new StringBuilder();
            out.append("### Which bones carried geometry into a volume\n\n")
                    .append("Every bone the collider's own filter accepts: directly mapped, with ")
                    .append("geometry, and a body joint. A joint with fewer than 12 vertices gets no ")
                    .append("volume.\n\n")
                    .append("| bone | joint | vertices | shape |\n|---|---|---|---|\n");
            for (String row : gatheringLog) {
                out.append(row).append('\n');
            }
            out.append('\n');
            return out.toString();
        }

        /**
         * The frame question, settled against the client's own log line rather than argued.
         *
         * <p>The client printed this model's volume ends and radii. Two candidates for the space the
         * collider's vertices are in are run through the same shape rule from the same parts: the
         * composed bind chain applied to the loaded vertices (which is what {@code pivotInMeshSpace}
         * does to a pivot, and therefore the frame the solver's pivots and rest directions are in),
         * and the loaded vertices on their own. Whichever reproduces the printed numbers is the one
         * production is handed.
         */
        String frameReport() {
            StringBuilder out = new StringBuilder();
            out.append("### The frame question: which space the collider's vertices are in\n\n")
                    .append("Both rows are the same parts through the same `YsmBodyColliders.")
                    .append("fromGeometry`. The client printed, for this model: `joint1@(0.14,0.41,")
                    .append("-0.03) r=0.12 len=0.22`, `joint4@(-0.17,0.44,-0.12) r=0.12 len=0.22`, ")
                    .append("`joint2@(0.18,0.0,-0.03) r=0.15 len=0.19`, `joint5@(-0.24,0.06,-0.24) ")
                    .append("r=0.15 len=0.19`.\n\n")
                    .append("| joint | frame | n | bounds | centre | span | tube r | end0 | end1 |\n")
                    .append("|---|---|---|---|---|---|---|---|---|\n");
            for (int joint : LIMB_JOINTS) {
                List<Vector3f> raw = rawLeg.get(joint);
                if (raw == null || raw.size() < 12) {
                    continue;
                }
                appendFrameRow(out, joint, "loaded mesh", raw, false);
                appendFrameRow(out, joint, "bind chain", raw, true);
            }
            for (int joint : LIMB_JOINTS) {
                List<Vector3f> raw = rawLegUnpermuted.get(joint);
                if (raw == null || raw.size() < 12) {
                    continue;
                }
                appendFrameRow(out, joint, "file frame, unpermuted", raw, false);
            }
            out.append('\n');
            return out.toString();
        }

        /**
         * Calibration against a measurement that does not depend on this probe's reconstruction of
         * anything: the published panel-to-thigh-axis distances.
         *
         * <p>{@code tmp_verify/T12_inside.txt} records, for this model, the distance from each front
         * panel's resting centre of mass to the thigh's own axis, with no bone pivot involved -
         * {@code FM1} 0.1523, {@code FR1} 0.1283, {@code FM2} 0.2844 among them. Reproducing the
         * <i>ordering and the spread</i> of those numbers calibrates the vertex frame this probe
         * reads; two frames are tried, and only one of them is within a tenth of a block.
         */
        String clearanceReport() {
            StringBuilder out = new StringBuilder();
            out.append("### Calibration: the published panel-to-thigh distances, reproduced\n\n")
                    .append("`axis` distances from a panel's own resting centre of mass to the thigh ")
                    .append("geometry's principal axis, in blocks, with no bone pivot involved. ")
                    .append("`T12` is the published value from `tmp_verify/T12_inside.txt` (thigh span ")
                    .append("0.5974, tube radius 0.1800).\n\n")
                    .append("| panel | T12 | loaded mesh | bind chain | file frame |\n")
                    .append("|---|---|---|---|---|\n");
            String[] panels = {"FM", "FM1", "FM2", "FL", "FL1", "FL2", "FR", "FR1", "FR2"};
            for (String name : panels) {
                Integer bone = boneIndexOf(name);
                if (bone == null) {
                    continue;
                }
                out.append("| `").append(name).append("` | ").append(t12Distance(name)).append(" | ")
                        .append(fmt(axisDistance(bone, 1, false))).append(" | ")
                        .append(fmt(axisDistance(bone, 1, true))).append(" | ")
                        .append(fmt(axisDistanceUnpermuted(bone, 1))).append(" |\n");
            }
            out.append('\n');
            return out.toString();
        }

        private Integer boneIndexOf(String name) {
            return boneIndex.get(name);
        }

        /** The published distance for this panel, or "-" when T12 did not list it. */
        private static String t12Distance(String name) {
            switch (name) {
                case "FM":
                    return "0.2161";
                case "FM1":
                    return "0.1523";
                case "FM2":
                    return "0.2844";
                case "FL":
                    return "0.2713";
                case "FL1":
                    return "0.2499";
                case "FL2":
                    return "0.3827";
                case "FR":
                    return "0.1967";
                case "FR1":
                    return "0.1283";
                case "FR2":
                    return "0.2762";
                default:
                    return "-";
            }
        }

        private float axisDistance(int bone, int joint, boolean bind) {
            List<Vector3f> geometry = vertices.get(bone);
            List<Vector3f> limb = rawLeg.get(joint);
            if (geometry == null || limb == null) {
                return Float.NaN;
            }
            return distanceToAxis(centroid(geometry), limb, bind, joint);
        }

        private float axisDistanceUnpermuted(int bone, int joint) {
            List<Vector3f> geometry = vertices.get(bone);
            List<Vector3f> limb = rawLegUnpermuted.get(joint);
            if (geometry == null || limb == null) {
                return Float.NaN;
            }
            return distanceToAxis(centroid(geometry), limb, false, joint);
        }

        private float distanceToAxis(Vector3f centre, List<Vector3f> limb, boolean bind, int joint) {
            List<Vector3f> points = new ArrayList<>(limb.size());
            YSMRuntimeModel.BoneRt bone = bind ? firstBoneOn(joint) : null;
            for (Vector3f vertex : limb) {
                Vector3f point = new Vector3f(vertex);
                if (bind) {
                    if (bone == null) {
                        continue;
                    }
                    bone.bindWorld.transformPosition(point);
                    point.mul(scaleX, scaleY, scaleX);
                }
                points.add(point);
            }
            if (points.size() < 12) {
                return Float.NaN;
            }
            Vector3f centreOfLimb = centroid(points);
            Vector3f axis = YsmBodyColliders.principalAxis(points, centreOfLimb);
            Vector3f offset = new Vector3f(centre).sub(centreOfLimb);
            float along = offset.dot(axis);
            return (float) Math.sqrt(Math.max(0.0F, offset.lengthSquared() - along * along));
        }

        /**
         * The measurement the user's question needs, in bind space, where no pose or delta can
         * contaminate it: how much of the leg's own geometry each limb capsule covers, and how far
         * each panel's own geometry sits from the leg's.
         *
         * <p>Both quantities are read from the same vertices in the same frame, so their difference
         * is a fact about the shipped collider rather than about this probe's reconstruction of a
         * pose. `covered` is the share of the limb's vertices that lie inside the capsule built for
         * that limb; `gap` is the smallest distance from the panel's own vertices to the limb's
         * vertices, negative when the two overlap.
         */
        String skirtClearanceReport() {
            StringBuilder out = new StringBuilder();
            out.append("### Coverage and clearance in bind space\n\n");
            for (int joint : LIMB_JOINTS) {
                List<Vector3f> limb = rawLeg.get(joint);
                if (limb == null || limb.size() < 12) {
                    continue;
                }
                List<float[]> capsules = bindCapsules.get(joint);
                if (capsules == null || capsules.isEmpty()) {
                    continue;
                }
                int covered = 0;
                for (Vector3f vertex : limb) {
                    if (insideAnyCapsule(vertex, capsules)) {
                        covered++;
                    }
                }
                out.append("Joint ").append(joint).append(": ").append(limb.size())
                        .append(" vertices, **").append(fmt((float) covered / limb.size()))
                        .append("** of them inside the capsule built for it (")
                        .append(covered).append(" vertices).\n\n");
            }
            out.append("| panel | gap to the nearer leg | nearest vertex | moved by |\n")
                    .append("|---|---|---|---|\n");
            for (int index : panelIndexes) {
                Segment panel = segments.get(index);
                float best = Float.MAX_VALUE;
                String whichLeg = "-";
                for (int joint : LIMB_JOINTS) {
                    List<Vector3f> limb = rawLeg.get(joint);
                    if (limb == null) {
                        continue;
                    }
                    // The panel's own vertices against a grid of the limb's, at the mesh's own
                    // resolution: a stride of 8 is a tenth of a limb's width and the quantity is a
                    // clearance of tenths of a block, not of millimetres.
                    for (int at = 0; at < panel.own.size(); at += 2) {
                        Vector3f vertex = panel.own.get(at);
                        for (int other = 0; other < limb.size(); other += 8) {
                            float distance = vertex.distance(limb.get(other));
                            if (distance < best) {
                                best = distance;
                                whichLeg = "joint" + joint;
                            }
                        }
                    }
                }
                out.append("| `").append(panel.name).append("` | ").append(fmt(best)).append(" | ")
                        .append(whichLeg).append(" | ").append(fmt(panel.lever)).append(" |\n");
            }
            out.append('\n');
            return out.toString();
        }

        private static boolean insideAnyCapsule(Vector3f point, List<float[]> capsules) {
            for (float[] capsule : capsules) {
                float distance = YsmBodyColliders.distanceToSegment(point.x, point.y, point.z,
                        capsule[0], capsule[1], capsule[2], capsule[3], capsule[4], capsule[5]);
                if (distance < capsule[6]) {
                    return true;
                }
            }
            return false;
        }

        /** The limb geometry's own span along its principal axis, and the axis' direction. */
        String limbGeometryReport() {
            StringBuilder out = new StringBuilder();
            out.append("### The limb geometry itself\n\n")
                    .append("| joint | n | bounds | centre | span along axis | axis |\n")
                    .append("|---|---|---|---|---|---|\n");
            for (int joint : LIMB_JOINTS) {
                List<Vector3f> points = rawLeg.get(joint);
                if (points == null || points.size() < 12) {
                    continue;
                }
                Vector3f lo = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
                Vector3f hi = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
                for (Vector3f point : points) {
                    lo.min(point);
                    hi.max(point);
                }
                Vector3f centre = centroid(points);
                Vector3f axis = YsmBodyColliders.principalAxis(points, centre);
                float low = Float.MAX_VALUE;
                float high = -Float.MAX_VALUE;
                Vector3f offset = new Vector3f();
                for (Vector3f point : points) {
                    offset.set(point).sub(centre);
                    float along = offset.dot(axis);
                    low = Math.min(low, along);
                    high = Math.max(high, along);
                }
                out.append("| ").append(joint).append(" | ").append(points.size()).append(" | ")
                        .append(point(lo)).append("..").append(point(hi)).append(" | ")
                        .append(point(centre)).append(" | ").append(fmt(high - low)).append(" | ")
                        .append(point(axis)).append(" |\n");
            }
            out.append('\n');
            return out.toString();
        }

        private void appendFrameRow(StringBuilder out, int joint, String label, List<Vector3f> raw,
                                    boolean bind) {
            List<Vector3f> points = new ArrayList<>(raw.size());
            YSMRuntimeModel.BoneRt bone = bind ? firstBoneOn(joint) : null;
            for (Vector3f vertex : raw) {
                Vector3f point = new Vector3f(vertex);
                if (bind) {
                    if (bone == null) {
                        continue;
                    }
                    bone.bindWorld.transformPosition(point);
                    point.mul(scaleX, scaleY, scaleX);
                }
                points.add(point);
            }
            if (points.size() < 12) {
                return;
            }
            Vector3f lo = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            Vector3f hi = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            for (Vector3f point : points) {
                lo.min(point);
                hi.max(point);
            }
            Map<Integer, List<Vector3f>> one = new LinkedHashMap<>();
            one.put(joint, points);
            YsmBodyColliders colliders = YsmBodyColliders.fromGeometry(one);
            if (colliders == null || colliders.count() == 0) {
                return;
            }
            float[] v = colliders.resolvedVolume(0);
            Vector3f e0 = new Vector3f(v[0], v[1], v[2]);
            Vector3f e1 = new Vector3f(v[4], v[5], v[6]);
            Vector3f centre = new Vector3f(e0).add(e1).mul(0.5F);
            out.append("| ").append(joint).append(" | ").append(label).append(" | ")
                    .append(points.size()).append(" | ").append(point(lo)).append("..")
                    .append(point(hi)).append(" | ").append(point(centre)).append(" | ")
                    .append(fmt(e0.distance(e1))).append(" | ").append(fmt(v[7])).append(" | ")
                    .append(point(e0)).append(" | ").append(point(e1)).append(" |\n");
        }

        private YSMRuntimeModel.BoneRt firstBoneOn(int joint) {
            for (YSMRuntimeModel.BoneRt bone : bones) {
                if (bone.joint == joint && bone.mapped) {
                    return bone;
                }
            }
            return null;
        }

        private void buildColliders() {
            allColliders = YsmBodyColliders.fromGeometry(legVertices);
            fullColliders = YsmBodyColliders.fromGeometry(fullLeg);
            Map<Integer, List<Vector3f>> core = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<Vector3f>> entry : vertices.entrySet()) {
                YSMRuntimeModel.BoneRt bone = bones[entry.getKey()];
                if (!bone.mapped || isLimb(bone.joint) || !isBodyJoint(bone.joint)) {
                    continue;
                }
                core.computeIfAbsent(bone.joint, key -> new ArrayList<>()).addAll(entry.getValue());
            }
            coreColliders = YsmBodyColliders.fromGeometry(core);
            assertNotNull(allColliders, "the model's leg geometry must produce volumes");
            for (int i = 0; i < allColliders.count(); i++) {
                int joint = allColliders.jointOf(i);
                if (!isLimb(joint)) {
                    continue;
                }
                // resolvedVolume returns the BIND geometry until a frame has been placed, which is
                // exactly the shape the bone carries: this is the capsule in bind space.
                float[] v = allColliders.resolvedVolume(i);
                bindCapsules.computeIfAbsent(joint, key -> new ArrayList<>())
                        .add(new float[]{v[0], v[1], v[2], v[4], v[5], v[6], v[7]});
            }
        }

        YsmBodyColliders colliders() {
            return allColliders;
        }

        YsmBodyColliders fullColliders() {
            return fullColliders;
        }

        YsmBodyColliders coreColliders() {
            return coreColliders;
        }

        YsmPhysicsParts.Model model() {
            return model;
        }

        /** The segment whose joint this is: the limb's own bone, used to place its volume. */
        int jointSegment(int joint) {
            for (Segment segment : segments) {
                if (segment.joint == joint) {
                    return segment.index;
                }
            }
            return -1;
        }

        /**
         * The deformation a joint's own geometry is drawn with: {@code pose x toOrigin}, exactly as
         * the frame path builds it. Works for the limb joints, which own their geometry but are not
         * themselves pieces the simulation moves.
         */
        OpenMatrix4f deformationFor(YsmMeshSecondaryMotion.PoseSource pose, int joint) {
            OpenMatrix4f toOrigin = pose.toOriginOf(joint);
            OpenMatrix4f jointPose = pose.poseOf(joint);
            if (toOrigin == null || jointPose == null) {
                return new OpenMatrix4f();
            }
            return OpenMatrix4f.mul(jointPose, toOrigin, new OpenMatrix4f());
        }

        /**
         * The drawn transform of a piece: the ancestors' deltas, then its own, which is the order
         * production builds them - {@code resolveSegment} ends with
         * {@code delta.set(jomlDeltas[parent]).mul(own)}, i.e. {@code parent x own}, and the recursion
         * below reaches the parent first and composes this level on the right.
         *
         * <p>Round 4 briefly changed this to {@code own x parent} on the theory that the order had to
         * matter, and then put it back. It does not: the child's own delta turns about the point the mesh
         * uses as the child's centre of mass, so that point is a fixed point of {@code own} and
         * {@code parent x own x center == own x parent x center}. Measured - the two orders produced
         * identical reports, which is the reading that says the composition is not this defect.
         */
        Matrix4f drawnDelta(YsmMeshSecondaryMotion.State state, int index) {
            Matrix4f out = new Matrix4f();
            drawnDelta(state, index, out, 0);
            // COPIED, and the copy is load-bearing: this must be a VALUE, not the state's own slot.
            // The pre-fix body returned `state.jomlDeltas[index]` itself, so every caller was handed a
            // live reference into the frame's state - a caller that composed anything into what it was
            // given would have written the composed delta back as next frame's own delta. An A/B run
            // (round 2, `round2-rebind-ab-oldhelper.md` vs `round2-rebind-r3-fixed.md`) measured the two
            // bodies producing byte-identical reports on the re-bind probe, so the mutation was never
            // triggered there; the copy stays because "no caller may alias the frame's state" is a
            // property worth having rather than a bug that happened not to fire. The recursion below
            // composes into LOCAL matrices only, and JOML's `mul` writes into its receiver, so the
            // composition itself never touched the state slot.
            return new Matrix4f(state.jomlDeltas[index]);
        }

        private void drawnDelta(YsmMeshSecondaryMotion.State state, int index, Matrix4f out, int depth) {
            assertTrue(depth <= 64, "cyclic segment chain in the converted model");
            Segment segment = segments.get(index);
            if (segment.parent >= 0) {
                Matrix4f parent = new Matrix4f();
                drawnDelta(state, segment.parent, parent, depth + 1);
                out.set(parent);
            } else {
                out.identity();
            }
            out.mul(state.jomlDeltas[index]);
        }

        /**
         * The solver's contact point for a piece: the point {@code resolveCollisions} builds and
         * tests, which is the piece's resting place under the pose and nothing else.
         */
        Vector3f testedCentre(Segment segment, OpenMatrix4f deformation) {
            Vector3f pivot = YsmMeshSecondaryMotion.transformPoint(deformation, segment.bindPivot,
                    new Vector3f());
            Vector3f rest = YsmMeshSecondaryMotion.transformDirection(deformation, segment.bindRest,
                    new Vector3f());
            if (rest.lengthSquared() < 1.0E-8F) {
                return pivot;
            }
            rest.normalize();
            return rest.mul(segment.lever).add(pivot);
        }

        /**
         * The drawn centre of mass of a piece under a frame's deformation and composed delta.
         *
         * <p><b>The order is the mesh's, and round 5 measured the other one.</b> Epic Fight draws a
         * part as {@code deformation x delta x vertex}: the delta is built by
         * {@code buildSegmentDelta(bindAnchor, ...)} = {@code T(bindAnchor) x R x T(-bindAnchor)} out
         * of a <b>bind-space</b> point, so it acts on bind-space vertices and the deformation then
         * carries the result into the posed model frame. That is also the identity
         * {@code buildSegmentDelta}'s own javadoc pins:
         *
         * <pre>
         *   deformation x delta  ==  T(A) x Q x T(-A) x deformation,   A = deformation x bindAnchor
         * </pre>
         *
         * which holds for the delta on the LEFT of the deformation, not the right.
         *
         * <p>The body here used to be {@code delta(deformation(bindCentre))} - the delta applied to a
         * model-space point - and that is a different map: it is off by
         * {@code (R - I) t}, proportional to the model-space offset between the bind origin and the
         * model origin rather than to anything about this piece. It is the whole of R5-1: every
         * drawn-distance number in rounds 2-4 was measuring it, which is why none of them may be
         * quoted as a defect size. {@link #penetration} and {@link #clearanceTo} already placed their
         * vertices in the correct order; this method was the one that did not.
         */
        Vector3f drawnCentre(Segment segment, OpenMatrix4f deformation, Matrix4f delta) {
            Vector3f posed = new Vector3f(segment.bindPivot).add(segment.bindRest);
            delta.transformPosition(posed);
            return YsmMeshSecondaryMotion.transformPoint(deformation, posed, posed);
        }

        /**
         * The composed delta a piece is DRAWN with, read from the state rather than rebuilt.
         *
         * <p>`state.deltas[index]` is the published transform: `simulate` converts each segment's
         * `jomlDeltas` entry once at the end of the pass, and that is the matrix the render path hands
         * the GPU ({@code YsmMeshSecondaryMotion#apply}: {@code mesh.setRuntimeTransformAt(ordinal,
         * state.deltas[i])}). Reading it is the only reconstruction-free way to know where a piece is
         * drawn, and it is why this method exists beside {@link #drawnDelta}: for a chained piece the
         * `jomlDeltas` slot holds the COMPOSED delta (`parent x own`), so composing that slot under its
         * parent again - which is what the recursive helper does - applies the chain's swing more than
         * once. The render path never walks the parent chain, and on the reported model's long chains
         * the difference is worth about a block.
         *
         * <p>A segment the solver did not integrate keeps the identity, which is exactly what the mesh
         * is handed for it - see the "no pose for this joint" branch of `resolveSegment`.
         */
        Matrix4f publishedDelta(YsmMeshSecondaryMotion.State state, int index) {
            Matrix4f out = new Matrix4f();
            OpenMatrix4f published = state.deltas[index];
            if (published == null) {
                return out.identity();
            }
            out.set(published.m00, published.m01, published.m02, published.m03,
                    published.m10, published.m11, published.m12, published.m13,
                    published.m20, published.m21, published.m22, published.m23,
                    published.m30, published.m31, published.m32, published.m33);
            return out;
        }

        /** {@link #drawnCentre(Segment, OpenMatrix4f, Matrix4f)} using the published delta. */
        Vector3f drawnCentre(Segment segment, OpenMatrix4f deformation,
                             YsmMeshSecondaryMotion.State state) {
            return drawnCentre(segment, deformation, publishedDelta(state, segment.index));
        }

        /**
         * The deepest a panel's own vertices are drawn inside a capsule, in blocks: zero when nothing
         * of the panel is inside, and the distance to the capsule's surface when something is.
         *
         * <p>The vertices go through the mesh's own chain - the composed delta in BIND space first,
         * then the deformation into the posed model frame (see
         * {@link #drawnCentre(Segment, OpenMatrix4f, Matrix4f)}) - because the capsule is placed by
         * the deformation alone and the two must be compared in one frame.
         */
        float penetration(Segment segment, OpenMatrix4f deformation, Matrix4f delta,
                          Vector3f end0, Vector3f end1, float radius) {
            float deepest = 0.0F;
            Vector3f point = new Vector3f();
            for (Vector3f vertex : segment.own) {
                point.set(vertex);
                delta.transformPosition(point);
                YsmMeshSecondaryMotion.transformPoint(deformation, point, point);
                float distance = YsmBodyColliders.distanceToSegment(point.x, point.y, point.z,
                        end0.x, end0.y, end0.z, end1.x, end1.y, end1.z);
                deepest = Math.max(deepest, radius - distance);
            }
            return deepest;
        }

        /**
         * The least distance from a panel's drawn vertices to the leg's drawn vertices: negative when
         * the two bodies overlap, which is what "the skirt clips through the leg" is.
         *
         * <p>Both clouds are placed through the same chain - the piece's composed delta in BIND space,
         * then the joint's own deformation - so this is a statement about the two meshes as they are
         * drawn, not about a collider standing in for one of them. The panel's vertices are
         * strided to the mesh's own resolution and the leg's to its own: the panels are surfaces a
         * few centimetres across and the answer wanted is a clearance in tenths of a block.
         */
        float clearanceTo(Segment segment, OpenMatrix4f deformation, Matrix4f delta,
                          List<Vector3f> placedLeg) {
            float least = Float.MAX_VALUE;
            Vector3f point = new Vector3f();
            for (int at = 0; at < segment.own.size(); at += 2) {
                point.set(segment.own.get(at));
                delta.transformPosition(point);
                YsmMeshSecondaryMotion.transformPoint(deformation, point, point);
                for (Vector3f other : placedLeg) {
                    least = Math.min(least, point.distance(other));
                }
            }
            return least == Float.MAX_VALUE ? Float.NaN : least;
        }

        private void buildArmature(JsonObject runtime, JsonObject mesh, Set<String> hidden)
                throws IOException {
            YsmBindArmature.GeometryInput input = geometryInput(modelId, runtime, mesh, hidden);
            YsmBindArmature.BindPivots pivots = computeBindPivots(input);
            buildArmatureFrom(pivots, modelId);
        }

        /**
         * The armature half of {@link #buildArmature}, split out so a rig assembled from a raw corpus
         * {@code .ysm} can be given the same tree from its own pivots. Epic Fight's reference biped
         * with this model's pivots, plus the wrapper production hands to
         * {@code YsmBodyColliders#update}.
         */
        private void buildArmatureFrom(YsmBindArmature.BindPivots pivots, String stem) {
            Joint reference;
            try {
                reference = referenceBiped();
            } catch (IOException e) {
                throw new IllegalStateException("the bundled Epic Fight biped.json is unreadable", e);
            }
            Joint rig = copyHierarchy(reference, new OpenMatrix4f(), pivots.byJoint(), true);
            rig.initOriginTransform(new OpenMatrix4f());
            Map<String, Joint> byName = new HashMap<>();
            collectJoints(rig, byName);
            this.armature = rig;
            this.bindArmature = new yesman.epicfight.model.armature.HumanoidArmature(
                    "ysm_probe_" + stem, byName.size(), rig, byName);
            walkWorlds(rig, new OpenMatrix4f());
        }

        private static YsmBindArmature.BindPivots computeBindPivots(YsmBindArmature.GeometryInput input) {
            List<String> warnings = new ArrayList<>();
            YsmBindArmature.GeometryData data = YsmBindArmature.collectGeometry(input, warnings::add);
            return YsmBindArmature.computePivots(input, data, warnings::add);
        }

        /** The armature wrapper production re-places the collision volumes with. */
        yesman.epicfight.model.armature.HumanoidArmature armature() {
            return bindArmature;
        }

        /** The model's own bind armature: Epic Fight's reference biped with this model's pivots. */
        Joint armatureRoot() {
            return armature;
        }

        /** The skeleton joint with this id, or null. */
        Joint jointById(int joint) {
            return joints.get(joint);
        }

        /** The joint's own bind-space {@code toOrigin}, or null - what the pose source hands over. */
        OpenMatrix4f toOriginOf(int joint) {
            Joint skeleton = joints.get(joint);
            return skeleton == null ? null : skeleton.getToOrigin();
        }

        /** The joint's bind-pose world matrix, or null. */
        OpenMatrix4f restWorldOf(int joint) {
            return restWorld.get(joint);
        }

        private void collectJoints(Joint joint, Map<String, Joint> byName) {
            joints.put(joint.getId(), joint);
            byName.put(joint.getName(), joint);
            for (Joint child : joint.getSubJoints()) {
                collectJoints(child, byName);
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
            return world == null ? new Vector3f() : new Vector3f(world.m30, world.m31, world.m32);
        }

        /**
         * One bone part of a converted mesh as the mesh itself stores it: the part's corners in the
         * loaded frame, and the triangles they were fanned into.
         *
         * <p>Why this exists beside {@link #vertices}: this probe's own vertex lists are built by a
         * loop that walks a {@code List<Vector3f>} with the stride of a flat float list, and that is
         * a defect (see {@code YsmSkirtRebindProbeTest}'s header). Anything that has to be the mesh's
         * <b>true</b> geometry - the distance from a panel to a leg, and the leg's own <b>faces</b>,
         * which are what a leg passing between two skirt vertices is invisible to - is read here
         * instead, straight from the part arrays the loader reads.
         *
         * <p>The part array is the pre-triangulated corner list Epic Fight stores
         * ({@code stride 3}, each corner repeated three times), so entry {@code 3k} of the array is
         * the vertex ordinal of the k-th corner, and every four consecutive corners are one quad,
         * fanned {@code (0,1,2)} and {@code (2,3,0)} - the same fan {@code EFMeshJsonWriter} wrote.
         */
        static Geometry meshGeometry(JsonObject mesh, String partName) {
            JsonObject vertices = mesh.getAsJsonObject("vertices");
            JsonArray positionsJson = vertices.getAsJsonObject("positions").getAsJsonArray("array");
            float[] positions = new float[positionsJson.size()];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = positionsJson.get(i).getAsFloat();
            }
            JsonElement part = vertices.getAsJsonObject("parts").get(partName);
            if (part == null || !part.isJsonObject()) {
                return new Geometry(partName, List.of(), List.of());
            }
            JsonArray array = part.getAsJsonObject().getAsJsonArray("array");
            List<Vector3f> corners = new ArrayList<>();
            for (int k = 0; k * 3 < array.size(); k++) {
                corners.add(vertex(positions, array.get(k * 3).getAsInt()));
            }
            List<int[]> triangles = new ArrayList<>();
            for (int quad = 0; quad * 4 + 3 < corners.size(); quad += 4) {
                int a = quad;
                int b = quad + 1;
                int c = quad + 2;
                int d = quad + 3;
                triangles.add(new int[]{a, b, c});
                triangles.add(new int[]{c, d, a});
            }
            return new Geometry(partName, corners, triangles);
        }

        /** The vertex a part ordinal names, in the frame the loader hands {@code positions()} in. */
        static Vector3f vertex(float[] positions, int ordinal) {
            int at = ordinal * 3;
            // The loader's own map out of the writer's Blender frame: (x, y, z) -> (x, z, -y).
            return new Vector3f(positions[at], positions[at + 2], -positions[at + 1]);
        }
    }

    /**
     * A part of a converted mesh: its corners in the loaded frame (each vertex once per triangle
     * corner, as the mesh stores them) and the triangles they are drawn as - what
     * {@link Rig#meshGeometry} reads.
     */
    static final class Geometry {
        final String part;
        /** Every corner, in triangle order. */
        final List<Vector3f> corners;
        /** Index triplets into {@link #corners}, two per quad. */
        final List<int[]> triangles;

        Geometry(String part, List<Vector3f> corners, List<int[]> triangles) {
            this.part = part;
            this.corners = corners;
            this.triangles = triangles;
        }
    }

    /**
     * The rig this round measures, for the probes that were written after it - same loader, same
     * frame, same logged simulated set, so their numbers are the same measurement.
     */
    static Rig loadRig(Path pack, String stem) throws IOException {
        return Rig.load(pack, stem, LOGGED_MAID_SIMULATED);
    }

    /**
     * The same rig, assembled from a raw corpus {@code .ysm} instead of from converted artefacts, so
     * a corpus-wide sweep can run the production frame path over any model in the repository.
     *
     * <p>The geometry is baked exactly the way {@code DefectCalibrationCorpusSweepTest} bakes it for
     * its own rules - each quad's corners through the bone's own world chain, then the binary's own
     * width/height scale - and the armature, the segment list and the collision volumes are the same
     * production seams {@link #load} uses: {@code YsmBindArmature.collectGeometry} and
     * {@code computePivots}, {@code YsmPhysicsParts.selectBones}/{@code restOnGeometry}/
     * {@code contactAnchor}, and {@code YsmBodyColliders.fromGeometry}. Nothing here is a second
     * implementation of a rule; it is the same rules on a different input.
     *
     * <p>The one assertion it deliberately does not carry is "the assembled set equals the set the
     * client logged": nothing has logged a corpus model.
     */
    static Rig fromRawModel(YSMGeoModel model, float scaleW, float scaleH, String stem) {
        List<YSMGeoModel.Bone> source = new ArrayList<>(model.bonesByName.values());
        Map<String, Matrix4f> worlds = new HashMap<>();
        Map<String, List<Vector3f>> baked = new LinkedHashMap<>();
        for (YSMGeoModel.Bone bone : source) {
            if (bone.quads.isEmpty()) {
                continue;
            }
            Matrix4f world = corpusWorldOf(bone, worlds, 0);
            List<Vector3f> vertices = new ArrayList<>(bone.quads.size() * 4);
            for (YSMGeoModel.Quad quad : bone.quads) {
                if (quad.positions == null) {
                    continue;
                }
                for (Vector3f corner : quad.positions) {
                    if (corner == null) {
                        continue;
                    }
                    Vector3f point = new Vector3f(corner).mulPosition(world);
                    vertices.add(new Vector3f(point.x * scaleW, point.y * scaleH, point.z * scaleW));
                }
            }
            baked.put(bone.name, vertices);
        }
        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < source.size(); i++) {
            indexOf.put(source.get(i).name, i);
        }
        YSMRuntimeModel.BoneRt[] bones = new YSMRuntimeModel.BoneRt[source.size()];
        List<YsmBindArmature.BoneGeometry> parts = new ArrayList<>();
        int[] joints = new int[source.size()];
        boolean[] mapped = new boolean[source.size()];
        for (int i = 0; i < source.size(); i++) {
            YSMGeoModel.Bone bone = source.get(i);
            YSMRuntimeModel.BoneRt rt = new YSMRuntimeModel.BoneRt();
            rt.name = bone.name;
            rt.parent = bone.parent == null ? -1 : indexOf.getOrDefault(bone.parent.name, -1);
            rt.px = bone.pivotX;
            rt.py = bone.pivotY;
            rt.pz = bone.pivotZ;
            rt.rx = bone.rotX;
            rt.ry = bone.rotY;
            rt.rz = bone.rotZ;
            rt.joint = YSMJointMapper.resolveJointId(bone, model);
            rt.mapped = YSMJointMapper.isDirectlyMapped(bone);
            bones[i] = rt;
            joints[i] = rt.joint;
            mapped[i] = rt.mapped;
            List<Vector3f> own = baked.get(bone.name);
            if (own != null && !own.isEmpty()) {
                parts.add(new YsmBindArmature.BoneGeometry(i, own));
            }
        }
        for (int i = 0; i < bones.length; i++) {
            Rig.bindWorld(bones, i, 0);
        }
        Rig rig = new Rig(stem, bones, scaleW, scaleH);
        rig.corpus = true;
        // The mesh part ordinals carrying each bone's geometry, which the selection reads before it
        // reads anything else: `YsmPhysicsParts.ownsItsGeometry` refuses a bone that declares no part,
        // so an empty map here refuses EVERY bone - `selectBones` returns nothing, the rig assembles
        // no pieces, and a corpus sweep reports `models=N pieces=0` while parsing every package. That
        // is what this sweep did until round 10: 906 packages walked, 500+ parsed, 0 rigs usable, and
        // no lever value ever read. The converted path builds this map from the mesh's `bone_<name>`
        // parts (`YsmPhysicsParts.partOrdinalsByBone`); the raw model's equivalent is one part per
        // bone that carries geometry, numbered in bone order.
        Map<Integer, int[]> partOrdinals = new HashMap<>();
        int ordinal = 0;
        for (int i = 0; i < bones.length; i++) {
            List<Vector3f> own = baked.get(bones[i].name);
            if (own == null || own.isEmpty()) {
                continue;
            }
            Vector3f centre = Rig.centroid(own);
            rig.vertices.put(i, own);
            rig.geometry.put(i, new float[]{centre.x, centre.y, centre.z, own.size()});
            partOrdinals.put(i, new int[]{ordinal++});
        }
        rig.buildSegments(partOrdinals);
        Map<Integer, List<Vector3f>> byJoint = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Vector3f>> entry : rig.vertices.entrySet()) {
            YSMRuntimeModel.BoneRt bone = bones[entry.getKey()];
            if (bone.mapped && bone.joint >= 0 && bone.joint < JointTable.COUNT) {
                byJoint.computeIfAbsent(bone.joint, key -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        rig.allColliders = YsmBodyColliders.fromGeometry(byJoint);
        YsmBindArmature.GeometryInput input = new YsmBindArmature.GeometryInput(
                stem, baked.keySet().toArray(new String[0]), joints, mapped, Set.of(), parts);
        rig.buildArmatureFrom(Rig.computeBindPivots(input), stem);
        rig.model = productionModel(rig);
        return rig;
    }

    private static Matrix4f corpusWorldOf(YSMGeoModel.Bone bone, Map<String, Matrix4f> cache, int depth) {
        Matrix4f cached = cache.get(bone.name);
        if (cached != null) {
            return cached;
        }
        if (depth > YSMGeoModel.MAX_BONE_DEPTH) {
            return new Matrix4f();
        }
        Matrix4f local = new Matrix4f();
        local.translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        local.rotateZ(bone.rotZ);
        local.rotateY(bone.rotY);
        local.rotateX(bone.rotX);
        local.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);
        Matrix4f world = bone.parent == null
                ? local
                : new Matrix4f(corpusWorldOf(bone.parent, cache, depth + 1)).mul(local);
        cache.put(bone.name, world);
        return world;
    }

    /** The production {@code YsmPhysicsParts.Segment} records for the assembled pieces. */
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

    private static boolean isPanel(String name) {
        for (String panel : PANELS) {
            if (panel.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBodyJoint(int joint) {
        for (int candidate : BODY_JOINTS) {
            if (candidate == joint) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // The armature helpers, mirroring YsmBindArmature's own
    // ------------------------------------------------------------------

    private static Joint referenceBiped() throws IOException {
        String text;
        try (InputStream stream = YsmLegSkirtCollisionProbeTest.class
                .getResourceAsStream("/epicfight/biped.json")) {
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

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

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
