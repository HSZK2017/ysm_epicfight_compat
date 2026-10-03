package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The reported clipping, reproduced offline: the maid's skirt against her legs under <b>Epic
 * Fight's own animation clips</b> - the walk, the run and the fall - instead of a synthetic gait.
 *
 * <h2>Why the previous round could not see it</h2>
 *
 * <p>The previous round drove the model with a hand-built leg cycle (a 20-34 degree hip swing, a
 * 16-28 degree knee, 1.05-1.60 Hz) and measured that no skirt vertex ever entered a leg capsule and
 * that the two drawn meshes never came closer than 0.005 blocks. The user's report names the state
 * that probe did not reproduce: <b>in Epic Fight mode, walking, sprinting and falling</b>. Epic
 * Fight does not draw those states with a hip swing of the same size or shape - it draws the clips
 * in this model's own {@code assets/epicfight/animmodels/animations/biped/living/*.json} - so the
 * probe is re-run on those clips, at their own timings, with everything else held identical.
 *
 * <h2>The pose, built the way Epic Fight builds it</h2>
 *
 * <p>Every number here comes from the clip files in {@code src/test/resources/epicfight/}, which
 * are byte-identical copies of the deployed Epic Fight jar's own entries (SHA-256 pinned below), and
 * from the recipe read out of Epic Fight's source rather than remembered:
 *
 * <ul>
 *   <li>{@code JsonAssetLoader#getTransformSheet}: the JSON array is a <b>transposed</b> matrix; the
 *       Root joint gets {@code BLENDER_TO_MINECRAFT_COORD} on the front and <b>no</b> local
 *       inversion, every other joint gets {@code invert(joint local)} on the front, and the result
 *       is decomposed and its quaternion normalized. So for every joint except Root,
 *       {@code animLocal = invert(local) * keyframe}.</li>
 *   <li>{@code JointTransform#getAnimationBoundMatrix} with {@code AnimationTransformEntry}: the
 *       world matrix is {@code parentWorld * local * animLocal}.</li>
 *   <li>Between keyframes, {@code TransformSheet#getInterpolationInfo}: the bracketing pair with a
 *       clamped progression, translation lerped and rotation lerped; past the last keyframe the
 *       value is held.</li>
 * </ul>
 *
 * <p>The joint locals are the ones this mod's own armature is built from - Epic Fight's reference
 * biped's rotations with the model's own pivots - because that is the armature the running game
 * poses, and because the product {@code local * invert(local)} is the identity, the pose that comes
 * out is the clip's authored transform wherever the pivots agree and the model's pivots where they
 * differ. That is exactly what the mod evaluates on screen.
 *
 * <h2>The two numbers per panel</h2>
 *
 * <ul>
 *   <li><b>skin depth</b>: the least distance from the panel's drawn vertices to the drawn leg
 *       vertices, subtracted from the same pair's distance in the model's <b>bind pose</b>. Zero
 *       means the clip has not changed how deeply the leg sits in the cloth; a positive value means
 *       the leg has moved <b>through</b> the cloth by that many blocks since the model was authored.
 *       This is the number the user's eye is measuring and it needs no collider.</li>
 *   <li><b>wire depth</b>: the deepest any of the panel's own vertices is drawn inside one of the
 *       model's shipped limb capsules - the previous round's metric, so the two rounds compare
 *       directly.</li>
 * </ul>
 *
 * <p>Both are reported per panel together with the leg they are nearest to and the sign of the
 * offset along the model's own forward (-Z): a negative {@code fwd} in a row's summary means the
 * clipping is <b>behind</b> the leg.
 */
class YsmEfSkirtClipProbeTest {

    /** The reported model, by its converted runtime's own id. */
    private static final String MAID = "wine_fox/01_taisho_maid";

    private static final String[] PANELS = {
            "RM", "RM2", "RM3", "RF", "RF2", "RF3", "RB", "RB2", "RB3",
            "BL", "BL2", "BL3", "BM", "BM2", "BM3", "BR", "BR2", "BR3",
            "FL", "FL1", "FL2", "FM", "FM1", "FM2", "FR", "FR1", "FR2",
            "LM", "LM2", "LM3", "LF", "LF2", "LF3", "LB", "LB2", "LB3"};

    /** The limb joints, as in the previous round. */
    private static final int[] LIMB_JOINTS = {1, 4, 2, 5, 3, 6};

    private static final float DT = 1.0F / 60.0F;
    private static final int FRAMES = 600;
    private static final int WARMUP = 200;

    /** The body's own forward in the model's bind frame; the tail is at +Z, behind. */
    private static final Vector3f FORWARD = new Vector3f(0.0F, 0.0F, -1.0F);

    /**
     * The bundled clip files, pinned to the deployed Epic Fight jar's own bytes
     * ({@code epic-fight-20.14.17-mc1.20.1-forge.jar}, entries under
     * {@code assets/epicfight/animmodels/animations/biped/living/}). A mismatch means the numbers
     * below describe an animation the game does not have.
     */
    private static final Map<String, String> CLIP_SHA256 = Map.of(
            "/epicfight/anim_walk.json", "38F71817692DD9BA69E2AA05566299FE7422C1892D8D52B0ACE6306A06F80B05",
            "/epicfight/anim_run.json", "762AE35FE778264335EA21FEC452D07FC3676526567038D667E8B0D1096C1730",
            "/epicfight/anim_fall.json", "2612D4B6E3B6CA1EE40016D5DEB8970752DFAA64E04B1C43B5A672F2F040D94B",
            "/epicfight/anim_jump.json", "CDB2D1DB6AB53F4C4FE0BF325E779B4BA5A05332F79BC412F03360DBB5C93370");

    private static final String BIPED_SHA256 =
            "7C58B17C465F792DB849FBC3EC377EA9C42C0517027808C7E3D6F3BC5953E583";

    /**
     * One state to drive: a clip, whether it loops, the body velocity the pieces feel while it runs
     * (Epic Fight's own walk and run speeds; a fall has no horizontal velocity), and whether the
     * shipped collision volumes are re-placed every frame the way the production frame path places
     * them ({@code YsmBodyColliders#update}).
     */
    private record State(String name, String clip, boolean loop, float speed, float phaseOffset,
                         boolean placeCollidersPerFrame) {}

    private static final State EF_WALK =
            new State("EF walk", "/epicfight/anim_walk.json", true, 4.317F, 0.0F, true);
    private static final State EF_RUN =
            new State("EF run/sprint", "/epicfight/anim_run.json", true, 5.612F, 0.0F, true);
    private static final State EF_FALL =
            new State("EF fall", "/epicfight/anim_fall.json", false, 0.0F, 0.0F, true);
    private static final State EF_JUMP =
            new State("EF jump", "/epicfight/anim_jump.json", false, 4.317F, 0.0F, true);
    /** The previous round's synthetic walk, for the side-by-side the brief asks for. */
    private static final State SYNTHETIC_WALK =
            new State("synthetic walk (previous round)", null, true, 4.317F, 0.0F, true);
    /** The same synthetic walk with the volumes left in bind space - what the previous round ran. */
    private static final State SYNTHETIC_BIND =
            new State("synthetic walk, volumes in bind space (previous round's path)", null, true,
                    4.317F, 0.0F, false);

    private static final State[] STATES =
            {EF_WALK, EF_RUN, EF_FALL, EF_JUMP, SYNTHETIC_WALK, SYNTHETIC_BIND};

    // The previous round's own synthetic gait, so its column is the same measurement.
    private static final float SYNTH_LEAN = 6.0F;
    private static final float SYNTH_BOB = 0.022F;
    private static final float SYNTH_STRIDE = 20.0F;
    private static final float SYNTH_KNEE = 16.0F;
    private static final float SYNTH_HERTZ = 1.05F;

    // ------------------------------------------------------------------

    @Test
    void theSkirtAgainstTheLegsUnderEpicFightsOwnClips() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");
        assertTrue(BIPED_SHA256.equals(sha256Of(resourceBytes("/epicfight/biped.json"))),
                "the bundled Epic Fight biped.json is not the revision this measurement was "
                        + "calibrated on; the pose recipe reads the reference biped's joint locals");

        YsmLegSkirtCollisionProbeTest.Rig rig =
                YsmLegSkirtCollisionProbeTest.loadRig(pack, MAID);
        assertNotNull(rig, "the model's converted artefacts must be readable");

        Map<String, Animation> clips = new LinkedHashMap<>();
        for (State state : STATES) {
            if (state.clip() == null) {
                continue;
            }
            byte[] bytes = resourceBytes(state.clip());
            String actual = sha256Of(bytes);
            assertTrue(CLIP_SHA256.get(state.clip()).equals(actual),
                    "the bundled clip " + state.clip() + " is not the deployed Epic Fight jar's "
                            + "own revision: hash " + actual);
            System.out.println("YSKIRT-CLIP " + state.clip() + " " + actual);
            clips.put(state.name(), Animation.parse(
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(),
                    rig, state.clip()));
        }

        StringBuilder report = new StringBuilder();
        report.append("# The skirt against the legs under Epic Fight's own clips\n\n")
                .append("Model `").append(rig.modelId).append("`, ").append(rig.segments.size())
                .append(" simulated pieces. The dynamics are the production frame loop ")
                .append("(`YsmMeshSecondaryMotion.simulate`, the model's own colliders placed every ")
                .append("frame by `YsmBodyColliders.update`), ").append(FRAMES).append(" frames of ")
                .append(fmt(DT)).append(" s, the first ").append(WARMUP)
                .append(" skipped. The pose is Epic Fight's own: the clips bundled in ")
                .append("`src/test/resources/epicfight/` are byte-identical to the deployed ")
                .append("`epic-fight-20.14.17` jar's entries (hash-guarded in the probe), and the ")
                .append("pose is built by the loader's own recipe - `animLocal = invert(joint ")
                .append("local) * keyframe` with the Root's own coordinate correction, composed ")
                .append("`parentWorld * local * animLocal`, interpolated between the bracketing ")
                .append("keyframes and held past the last one.\n\n");

        report.append(clipReport(clips));

        Map<String, Run> runs = new LinkedHashMap<>();
        for (State state : STATES) {
            Animation animation = state.clip() == null ? null : clips.get(state.name());
            Run run = measure(rig, state, animation);
            runs.put(state.name(), run);
            report.append(runSection(state, run));
        }
        report.append(comparison(runs));

        // ---- the fix experiment: would a collider sized from the drawn limb move the cloth? ---
        report.append(colliderShapeReport(rig));
        Map<String, Run> sized = new LinkedHashMap<>();
        for (float scale : new float[]{1.0F, 1.5F, 2.0F}) {
            YsmBodyColliders grown = grownLimbColliders(rig, scale);
            assertNotNull(grown, "the shipped limb volumes must be readable");
            Run run = measure(rig, EF_FALL, clips.get(EF_FALL.name()), grown,
                    "EF fall, limb volumes at " + fmt(scale) + "x", false);
            sized.put(fmt(scale) + "x", run);
        }
        report.append(experimentSection(EF_FALL, rig, sortedByKey(sized),
                runs.get(EF_FALL.name())));
        report.append(mechanism(rig, runs, sized));

        Path out = Paths.get("build", "reports", "ysm-ef-skirt-round.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // ---- the assertions, on the thing the round is about --------------------------------
        Run walk = runs.get(EF_WALK.name());
        Run run = runs.get(EF_RUN.name());
        Run fall = runs.get(EF_FALL.name());
        assertNotNull(walk, "the EF walk must have been measured");
        assertNotNull(run, "the EF run must have been measured");
        assertNotNull(fall, "the EF fall must have been measured");

        // The two ways of placing the same volume must agree now that both actually run. The
        // shipped path writes cap centres through `YsmBodyColliders#update`; the other maps the
        // bind-space cap centres through the joint's own `pose x toOrigin`. The previous round's
        // zero came from a guard that was false for every limb volume - no simulated piece sits on
        // a leg joint - so its loop never ran; with the guard gone the two must agree.
        assertTrue(run.worstWire > 0.02F,
                "the cloth must register inside the thigh's tube in the run, measured "
                        + run.worstWire);
        assertTrue(Math.abs(run.worstWire - run.worstWireByDeformation) < 0.02F,
                "the two placements of the same volume must agree in the run: "
                        + run.worstWire + " against " + run.worstWireByDeformation);
        assertTrue(walk.worstWireByDeformation > walk.worstWire - 0.02F,
                "the joint-frame placement must not read systematically lower than the shipped one: "
                        + walk.worstWireByDeformation + " against " + walk.worstWire);

        // The clips must actually move the legs, or nothing below is a measurement of anything.
        assertTrue(walk.thighSwingDeg > 30.0F && walk.thighSwingDeg < 60.0F,
                "the EF walk clip must swing the thigh through a stride, measured "
                        + walk.thighSwingDeg + " deg");
        assertTrue(run.thighSwingDeg > walk.thighSwingDeg,
                "the EF run must swing the thigh further than the EF walk: run "
                        + run.thighSwingDeg + " deg, walk " + walk.thighSwingDeg + " deg");

        // The mechanism: the clip drives the leg most of the way through the clearance the mesh was
        // authored with, in the two states the user names, and barely at all in the walk.
        assertTrue(run.worstSkinDepth >= 0.08F, "the EF run must drive the leg through the bind "
                + "clearance, measured " + run.worstSkinDepth + " blocks");
        assertTrue(fall.worstSkinDepth >= 0.08F, "the EF fall must drive the leg through the bind "
                + "clearance, measured " + fall.worstSkinDepth + " blocks");
        assertTrue(run.worstSkin < 0.02F, "the EF run must bring the drawn cloth and the drawn leg "
                + "into contact, measured a closest pair of " + run.worstSkin + " blocks - the "
                + "worst panel was " + worstDepthPanel(run));
        // The run's contact must be a different regime from the walk's clearance, and the deepest
        // panel's depth must be attainable at all: a depth larger than that panel's own
        // nearest-vertex distance in the bind pose would mean the cloth had been carried INTO the
        // leg rather than the leg into the cloth, which no pose can do.
        assertTrue(run.worstSkin < walk.worstSkin / 3.0F, "the run must close the cloth-leg gap far "
                + "more than the walk does: " + run.worstSkin + " against " + walk.worstSkin);
        for (Map.Entry<String, Panel> entry : run.panels.entrySet()) {
            Panel panel = entry.getValue();
            assertTrue(panel.skinDepth <= panel.bindMin + 1.0E-4F, "panel " + entry.getKey()
                    + " reports a depth of " + panel.skinDepth + " blocks against a bind-pose "
                    + "clearance of only " + panel.bindMin + ", so the cloth was carried into the "
                    + "leg rather than the leg into the cloth");
            assertTrue(panel.wireDepth <= 0.2001F, "panel " + entry.getKey() + " reports "
                    + panel.wireDepth + " blocks inside a capsule whose largest radius is 0.200");
        }

        // The negative control the previous round's "the capsules never fire" rests on: with the
        // volumes left at their bind centres - that round's own path - the contact is much smaller,
        // because the cloth has to swing into a stationary volume rather than into one the limb
        // carries with it.
        Run bindSpace = runs.get(SYNTHETIC_BIND.name());
        assertNotNull(bindSpace, "the bind-space control must have been measured");
        assertTrue(bindSpace.worstWire < run.worstWire,
                "leaving the volumes at their bind centres must contact the cloth less than the "
                        + "shipped placement does: " + bindSpace.worstWire + " against "
                        + run.worstWire);

        // The design consequence: growing the collider does not move the cloth, so no resizing of
        // the shipped volume can be the fix for what the user sees.
        for (Map.Entry<String, Run> entry : sized.entrySet()) {
            assertTrue(entry.getValue().worstTurn <= 0.001F,
                    "the limb collider at " + entry.getKey() + " moved the cloth by "
                            + entry.getValue().worstTurn + " blocks, so the response is no longer "
                            + "inert and the conclusion that resizing cannot be the fix must be "
                            + "re-derived");
        }

        StringBuilder offending = new StringBuilder();
        for (Map.Entry<String, Run> entry : runs.entrySet()) {
            for (Map.Entry<String, Panel> panel : entry.getValue().panels.entrySet()) {
                if (panel.getValue().skinDepth > SKIN_TOLERANCE) {
                    offending.append(entry.getKey()).append('/').append(panel.getKey())
                            .append('=').append(fmt(panel.getValue().skinDepth)).append(' ');
                }
            }
        }
        System.out.println("YSKIRT-EF-SKIN-DEPTH " + (offending.length() == 0 ? "none" : offending));
    }

    /** The largest depth any panel reached in a run. */
    private static float worstDepth(Run run) {
        float worst = 0.0F;
        for (Panel panel : run.panels.values()) {
            worst = Math.max(worst, panel.skinDepth);
        }
        return worst;
    }

    private static String worstDepthPanel(Run run) {
        String name = "-";
        float worst = 0.0F;
        for (Map.Entry<String, Panel> entry : run.panels.entrySet()) {
            if (entry.getValue().skinDepth > worst) {
                worst = entry.getValue().skinDepth;
                name = entry.getKey() + ": moved " + fmt(entry.getValue().skinDepth)
                        + " of its " + fmt(entry.getValue().bindMin) + " blocks of bind clearance";
            }
        }
        return name;
    }

    /** How deep the leg must pass through the cloth before this round calls it a report. */
    private static final float SKIN_TOLERANCE = 0.002F;

    /** What one state measured, per panel. */
    private static final class Panel {
        float skinMin = Float.MAX_VALUE;
        float bindMin = Float.MAX_VALUE;
        float skinDepth;
        float wireDepth;
        float wireDepthByDeformation;
        float contactTurn;
        int framesClear;
        int skipped;
        String leg = "-";
        float behind;
        int behindFrames;
        int frames;
    }

    private static final class Run {
        final Map<String, Panel> panels = new LinkedHashMap<>();
        /** The volumes this run used, so the report can print their shape. */
        YsmBodyColliders colliders;
        /** Pieces that had at least one volume skipped for them in the last measured frame. */
        int skippedPieces;
        float thighSwingDeg;
        float kneeSwingDeg;
        float worstWire;
        float worstWireByDeformation;
        float worstTurn;
        float worstSkin = Float.MAX_VALUE;
        float worstSkinDepth;
        String worstSkinPanel = "-";
        String worstSkinLeg = "-";
        int worstSkinFrame = -1;
        String worstWirePanel = "-";
        float thighBackDeg;
        float thighFrontDeg;
        int frames;
    }

    // ------------------------------------------------------------------
    // The measurement
    // ------------------------------------------------------------------

    private static Run measure(YsmLegSkirtCollisionProbeTest.Rig rig, State state,
                               Animation animation) {
        return measure(rig, state, animation, null, null, false);
    }

    /**
     * @param collidersOverride the volumes to run against, or null for the model's own shipped set
     * @param label             the name this run was measured under
     */
    private static Run measure(YsmLegSkirtCollisionProbeTest.Rig rig, State state,
                               Animation animation, YsmBodyColliders collidersOverride,
                               String label, boolean unused) {
        YsmPhysicsParts.Model parts = rig.model();
        YsmBodyColliders body = collidersOverride == null ? rig.colliders() : collidersOverride;
        body.update(rig.armature(), bindPoseMatrices(rig));
        YsmMeshSecondaryMotion.State sim = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        YsmPhysicsOverrides.markOverrides(rig.modelId, rig.bones, parts.segments(),
                sim.held, sim.limit);
        SyntheticGait gait = state.clip() == null ? new SyntheticGait(rig) : null;
        Vector3f velocity = new Vector3f(0.0F, 0.0F, -state.speed());
        YsmMeshSecondaryMotion.PoseSource pose =
                animation != null ? new ClipPose(rig, animation) : gait;

        Run out = new Run();
        out.colliders = body;
        for (String name : PANELS) {
            out.panels.put(name, new Panel());
        }
        int segments = rig.segments.size();
        OpenMatrix4f[] deformations = new OpenMatrix4f[segments];
        assertRigSanity(rig);
        // The baseline the skin depth is measured against: the same panel/leg vertex pairs in the
        // model's own bind pose, which is the clearance the author left in the mesh the user sees at
        // rest. A clip that drives the leg through more than this is drawing the leg inside the
        // cloth the author drew around it.
        Map<String, Float> bindClearance = new HashMap<>();
        for (int index : rig.panelIndexes) {
            YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
            float least = Float.MAX_VALUE;
            for (int at = 0; at < piece.own.size(); at += 2) {
                Vector3f point = piece.own.get(at);
                for (List<Vector3f> leg : rig.legPose.values()) {
                    for (int other = 0; other < leg.size(); other += 2) {
                        least = Math.min(least, point.distance(leg.get(other)));
                    }
                }
            }
            bindClearance.put(piece.name, least);
            Panel panel = out.panels.get(piece.name);
            if (panel != null) {
                panel.bindMin = least;
            }
        }
        boolean placePerFrame = state.placeCollidersPerFrame();
        int frames = 0;
        for (int frame = 0; frame < FRAMES; frame++) {
            float time = frame * DT;
            if (animation != null) {
                ((ClipPose) pose).at(time + state.phaseOffset());
            } else {
                gait.at(time);
            }
            if (placePerFrame && frame == 0) {
                OpenMatrix4f[] poseMatrices = new OpenMatrix4f[JointTable.COUNT];
                for (int joint = 0; joint < poseMatrices.length; joint++) {
                    poseMatrices[joint] = pose.poseOf(joint);
                }
                body.update(rig.armature(), poseMatrices);
            }
            YsmMeshSecondaryMotion.simulate(sim, pose, DT, velocity, new float[]{0.0F, 0.0F},
                    rig.colliders());
            if (frame < WARMUP) {
                continue;
            }
            frames++;
            for (int i = 0; i < segments; i++) {
                YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(i);
                deformations[i] = rig.deformationFor(pose, piece.joint);
            }
            // The clip's own leg motion: how far the pose turns the limb's own geometry, in the
            // model's sagittal plane. Measured from the DEFORMATION (`pose x toOrigin`) rather than
            // from two joint origins, so it is independent of how the armature's bone tree happens
            // to be shaped - on this biped the shin and the knee are siblings, and a two-origin
            // difference reads the wrong pair.
            Vector3f forward = forwardOf(rig, pose);
            Vector3f lateral = lateralOf(forward);
            float thighSwing = swingOf(rig, pose, JointTable.THIGH_R, forward, lateral);
            out.thighSwingDeg = Math.max(out.thighSwingDeg, Math.abs(thighSwing));
            out.thighBackDeg = Math.max(out.thighBackDeg, thighSwing);
            out.thighFrontDeg = Math.min(out.thighFrontDeg, thighSwing);
            out.kneeSwingDeg = Math.max(out.kneeSwingDeg,
                    Math.abs(swingOf(rig, pose, JointTable.LEG_R, forward, lateral)));

            // The legs as drawn.
            List<Vector3f> legVertices = new ArrayList<>();
            List<Integer> legJoints = new ArrayList<>();
            for (Map.Entry<Integer, List<Vector3f>> entry : rig.legPose.entrySet()) {
                OpenMatrix4f deformation = rig.deformationFor(pose, entry.getKey());
                for (Vector3f vertex : entry.getValue()) {
                    legVertices.add(YsmMeshSecondaryMotion.transformPoint(deformation, vertex,
                            new Vector3f()));
                    legJoints.add(entry.getKey());
                }
            }
            // The shipped capsules, placed exactly where the frame path places them: through
            // `YsmBodyColliders#update`, which is the only thing that moves a volume with the pose.
            List<float[]> capsules = new ArrayList<>();
            YsmBodyColliders colliders = rig.colliders();
            for (int i = 0; i < colliders.count(); i++) {
                float[] volume = colliders.resolvedVolume(i);
                if (volume != null) {
                    capsules.add(volume);
                }
            }

            for (int index : rig.panelIndexes) {
                YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
                Panel panel = out.panels.get(piece.name);
                if (panel == null) {
                    continue;
                }
                panel.frames++;
                Matrix4f delta = rig.drawnDelta(sim, index);
                // The panel as drawn.
                List<Vector3f> drawn = new ArrayList<>(piece.own.size() / 2);
                for (int at = 0; at < piece.own.size(); at += 2) {
                    Vector3f point = new Vector3f(piece.own.get(at));
                    YsmMeshSecondaryMotion.transformPoint(deformations[index], point, point);
                    delta.transformPosition(point);
                    drawn.add(point);
                }
                // a) the leg's own vertices.
                float mesh = Float.MAX_VALUE;
                int nearestLeg = -1;
                Vector3f nearestPoint = null;
                Vector3f nearestOther = null;
                for (Vector3f point : drawn) {
                    for (int at = 0; at < legVertices.size(); at += 2) {
                        float distance = point.distanceSquared(legVertices.get(at));
                        if (distance < mesh) {
                            mesh = distance;
                            nearestLeg = legJoints.get(at);
                            nearestPoint = point;
                            nearestOther = legVertices.get(at);
                        }
                    }
                }
                mesh = mesh == Float.MAX_VALUE ? Float.NaN : (float) Math.sqrt(mesh);
                if (mesh < panel.skinMin) {
                    panel.skinMin = mesh;
                }
                if (mesh < out.worstSkin) {
                    out.worstSkin = mesh;
                    out.worstSkinPanel = piece.name;
                    out.worstSkinLeg = legName(nearestLeg);
                    out.worstSkinFrame = frame;
                }
                if (nearestPoint != null && nearestOther != null) {
                    // The model's own forward, in the frame the vertices are drawn in - the clip
                    // turns the whole body about the root, and a turn must not read as a front/back.
                    Vector3f offset = new Vector3f(nearestPoint).sub(nearestOther);
                    float forwardness = offset.dot(forwardOf(rig, pose));
                    if (forwardness < 0.0F) {
                        panel.behind += forwardness;
                        panel.behindFrames++;
                    }
                    panel.leg = legName(nearestLeg);
                }
                // b) the same panel against the shipped capsules, the previous round's metric.
                // `resolvedVolume` is [end0 x,y,z, -, end1 x,y,z, radius], so the radius is the
                // eighth slot - reading the sixth would test the cloth against end1's z.
                for (float[] capsule : capsules) {
                    float deepest = 0.0F;
                    for (Vector3f point : drawn) {
                        float distance = YsmBodyColliders.distanceToSegment(point.x, point.y,
                                point.z, capsule[0], capsule[1], capsule[2], capsule[4],
                                capsule[5], capsule[6]);
                        deepest = Math.max(deepest, capsule[7] - distance);
                    }
                    if (deepest > panel.wireDepth) {
                        panel.wireDepth = deepest;
                    }
                }
                // The same cloth against the same volumes placed the OTHER way: each cap centre
                // mapped through the joint's own deformation, `pose x toOrigin` - which is what the
                // previous round's loop did, and what this round does too now that the reason its
                // number was zero is known.
                //
                // That loop was guarded by `rig.jointSegment(joint) >= 0` - "the segment whose
                // joint this is" - and NO simulated piece sits on a leg joint: the model's 59
                // simulated pieces are all on Torso, Chest and Head. So the guard was false for
                // every limb volume, the loop body never ran, and the reported contact was `0.000`
                // because nothing was compared rather than because nothing touched. The guard is
                // dropped here; the deformation is taken from the joint itself.
                for (Map.Entry<Integer, List<float[]>> entry : rig.bindCapsules.entrySet()) {
                    OpenMatrix4f deformation = rig.deformationFor(pose, entry.getKey());
                    for (float[] capsule : entry.getValue()) {
                        Vector3f end0 = YsmMeshSecondaryMotion.transformPoint(deformation,
                                new Vector3f(capsule[0], capsule[1], capsule[2]), new Vector3f());
                        Vector3f end1 = YsmMeshSecondaryMotion.transformPoint(deformation,
                                new Vector3f(capsule[3], capsule[4], capsule[5]), new Vector3f());
                        float deepest = 0.0F;
                        for (Vector3f point : drawn) {
                            float distance = YsmBodyColliders.distanceToSegment(point.x, point.y,
                                    point.z, end0.x, end0.y, end0.z, end1.x, end1.y, end1.z);
                            deepest = Math.max(deepest, capsule[6] - distance);
                        }
                        if (deepest > panel.wireDepthByDeformation) {
                            panel.wireDepthByDeformation = deepest;
                        }
                    }
                }
                if (panel.wireDepth > 0.0F) {
                    out.worstWirePanel = piece.name;
                } else {
                    panel.framesClear++;
                }
                out.worstWire = Math.max(out.worstWire, panel.wireDepth);
                out.worstWireByDeformation = Math.max(out.worstWireByDeformation,
                        panel.wireDepthByDeformation);
                // What the collision response ACTUALLY did to this piece this frame: the largest
                // turn the solver produced, in blocks. Zero means the volume was skipped, or the
                // point never entered it, or the response was eaten by the budget.
                panel.contactTurn = Math.max(panel.contactTurn, sim.lastContact[index]);
                out.worstTurn = Math.max(out.worstTurn, panel.contactTurn);
                // Whether this piece's own place is inside a volume the solver therefore refuses to
                // push it out of - the rule that makes a skirt around the hips "not a collision".
                if (skippedFor(body, parts.segments()[index], deformations[index])) {
                    panel.skipped++;
                }
            }
        }
        for (Panel panel : out.panels.values()) {
            if (panel.skipped > 0) {
                out.skippedPieces++;
            }
        }
        // The bind-pose distance for the same panel/leg pair, so skin depth is a difference.
        for (Map.Entry<String, Panel> entry : out.panels.entrySet()) {
            Panel panel = entry.getValue();
            float bind = bindClearance.getOrDefault(entry.getKey(), Float.MAX_VALUE);
            if (bind == Float.MAX_VALUE || panel.skinMin == Float.MAX_VALUE) {
                panel.skinDepth = Float.NaN;
                continue;
            }
            panel.skinDepth = bind - panel.skinMin;
            out.worstSkinDepth = Math.max(out.worstSkinDepth, panel.skinDepth);
        }
        out.frames = frames;
        return out;
    }

    // ------------------------------------------------------------------
    // The pose the clips give
    // ------------------------------------------------------------------

    /**
     * Epic Fight's own {@code BLENDER_TO_MINECRAFT_COORD}, applied to the Root keyframe by
     * {@code JsonAssetLoader#getTransformSheet}'s {@code rootCorrection}. The same rotation the
     * bundled {@code biped.json}'s Root carries in its own local transform, which is why the two
     * cancel in the loader's product.
     */
    private static final Matrix4f BLENDER_TO_MINECRAFT =
            new Matrix4f(1.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, -1.0F, 0.0F,
                    0.0F, 1.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F, 1.0F);

    /** A clip: per joint, the world matrices at the sampled frames, in the model's own frame. */
    private static final class Animation {
        final String path;
        final float[] times;
        final float duration;
        final Map<Integer, OpenMatrix4f[]> worlds = new LinkedHashMap<>();
        final Map<Integer, OpenMatrix4f[]> locals = new LinkedHashMap<>();
        final Map<Integer, Matrix4f> keyframes = new LinkedHashMap<>();

        private Animation(String path, float[] times, float duration) {
            this.path = path;
            this.times = times;
            this.duration = duration;
        }

        static Animation parse(JsonObject doc, YsmLegSkirtCollisionProbeTest.Rig rig, String path)
                throws IOException {
            JsonArray nodes = doc.getAsJsonArray("animation");
            // Every joint's keyframes, converted to the local transform the pose composes.
            Map<Integer, List<float[]>> keyTimes = new LinkedHashMap<>();
            Map<Integer, List<Matrix4f>> keyMatrices = new LinkedHashMap<>();
            float last = 0.0F;
            for (JsonElement element : nodes) {
                JsonObject node = element.getAsJsonObject();
                int joint = JointTable.idOf(node.get("name").getAsString());
                assertTrue(joint >= 0, "the clip names a joint this mod's table does not have: "
                        + node.get("name").getAsString());
                JsonArray timeArray = node.getAsJsonArray("time");
                JsonArray transformArray = node.getAsJsonArray("transform");
                List<float[]> times = new ArrayList<>();
                List<Matrix4f> matrices = new ArrayList<>();
                for (int i = 0; i < timeArray.size(); i++) {
                    float time = timeArray.get(i).getAsFloat();
                    if (time < 0.0F) {
                        continue;
                    }
                    last = Math.max(last, time);
                    Matrix4f raw = transpose(matrixOf(transformArray.get(i).getAsJsonArray()));
                    if (joint == JointTable.ROOT) {
                        // The loader's own root correction: matrix.mulFront(BLENDER_TO_MINECRAFT),
                        // the same rotation the bundled biped's Root carries in its own local.
                        raw = BLENDER_TO_MINECRAFT.mul(raw, new Matrix4f());
                    }
                    times.add(new float[]{time});
                    matrices.add(raw);
                }
                keyTimes.put(joint, times);
                keyMatrices.put(joint, matrices);
            }
            int sampleCount = Math.max(2, Math.round(last / DT) + 1);
            float[] sampleTimes = new float[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                sampleTimes[i] = i * DT;
            }
            Animation animation = new Animation(path, sampleTimes, last);
            for (Map.Entry<Integer, List<Matrix4f>> entry : keyMatrices.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    animation.keyframes.put(entry.getKey(), entry.getValue().get(0));
                }
            }
            Map<Integer, OpenMatrix4f[]> locals = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<float[]>> entry : keyTimes.entrySet()) {
                int joint = entry.getKey();
                Joint skeleton = rig.jointById(joint);
                if (skeleton == null) {
                    continue;
                }
                // The local the loader composes: invert(the joint's own local) x keyframe, and for
                // the Root the coordinate correction with no inversion at all.
                OpenMatrix4f inverseLocal = OpenMatrix4f.invert(skeleton.getLocalTransform(), null);
                List<float[]> times = entry.getValue();
                List<Matrix4f> matrices = keyMatrices.get(joint);
                OpenMatrix4f[] sheet = new OpenMatrix4f[sampleCount];
                for (int i = 0; i < sampleCount; i++) {
                    float time = sampleTimes[i];
                    int begin = 0;
                    int end = Math.max(0, times.size() - 1);
                    while (end - begin > 1) {
                        int mid = begin + (end - begin) / 2;
                        if (times.get(mid)[0] <= time && times.get(mid + 1)[0] > time) {
                            begin = mid;
                            end = mid + 1;
                            break;
                        }
                        if (times.get(mid)[0] > time) {
                            end = mid;
                        } else if (times.get(mid + 1)[0] <= time) {
                            begin = mid;
                        }
                    }
                    Matrix4f keyframe = matrices.get(begin);
                    float span = times.get(end)[0] - times.get(begin)[0];
                    float progression = span <= 1.0E-9F ? 1.0F
                            : Math.min(1.0F, Math.max(0.0F, (time - times.get(begin)[0]) / span));
                    OpenMatrix4f local = joint == JointTable.ROOT
                            ? toOpen(keyframe)
                            : OpenMatrix4f.mul(inverseLocal, toOpen(keyframe), null);
                    sheet[i] = local;
                }
                locals.put(joint, sheet);
            }
            animation.locals.putAll(locals);
            // The composition, once: world = parentWorld * local * animLocal, per sample.
            OpenMatrix4f[] rootWorld = new OpenMatrix4f[sampleCount];
            java.util.Arrays.setAll(rootWorld, i -> new OpenMatrix4f());
            compose(rig.armatureRoot(), rootWorld, locals, animation.worlds, sampleCount);
            return animation;
        }

        /** One depth-first pass, so a joint's parent world is always already computed. */
        private static void compose(Joint joint, OpenMatrix4f[] parentWorlds,
                                    Map<Integer, OpenMatrix4f[]> locals,
                                    Map<Integer, OpenMatrix4f[]> worlds, int samples) {
            OpenMatrix4f[] sheet = locals.get(joint.getId());
            OpenMatrix4f[] out = new OpenMatrix4f[samples];
            for (int i = 0; i < samples; i++) {
                OpenMatrix4f local = sheet == null ? joint.getLocalTransform() : sheet[i];
                OpenMatrix4f combined = OpenMatrix4f.mul(joint.getLocalTransform(), local, null);
                out[i] = OpenMatrix4f.mul(parentWorlds[i], combined, null);
            }
            worlds.put(joint.getId(), out);
            for (Joint child : joint.getSubJoints()) {
                compose(child, out, locals, worlds, samples);
            }
        }
    }

    /**
     * The clip, evaluated at a time: the joints the model's armature has, as the world matrices
     * Epic Fight would hand the renderer at that instant.
     */
    private static final class ClipPose implements YsmMeshSecondaryMotion.PoseSource {
        private final YsmLegSkirtCollisionProbeTest.Rig rig;
        private final Animation animation;
        private int sample = -1;

        ClipPose(YsmLegSkirtCollisionProbeTest.Rig rig, Animation animation) {
            this.rig = rig;
            this.animation = animation;
        }

        void at(float seconds) {
            float time = seconds;
            if (animation.duration > 0.0F) {
                time = seconds % animation.duration;
            }
            if (time < 0.0F) {
                time = 0.0F;
            }
            sample = Math.min(animation.times.length - 1, Math.round(time / DT));
        }

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return rig.toOriginOf(joint);
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            OpenMatrix4f[] sheet = animation.worlds.get(joint);
            return sheet == null || sample < 0 ? null : sheet[sample];
        }
    }

    /** The previous round's synthetic gait, kept so its column is the same measurement. */
    private static final class SyntheticGait implements YsmMeshSecondaryMotion.PoseSource {
        private static final OpenMatrix4f IDENTITY = new OpenMatrix4f();
        private final YsmLegSkirtCollisionProbeTest.Rig rig;
        private final OpenMatrix4f[] poses = new OpenMatrix4f[JointTable.COUNT];

        SyntheticGait(YsmLegSkirtCollisionProbeTest.Rig rig) {
            this.rig = rig;
            for (int i = 0; i < poses.length; i++) {
                poses[i] = new OpenMatrix4f();
            }
            at(0.0F);
        }

        private static OpenMatrix4f about(Vector3f origin, float degrees, float bob) {
            Matrix4f matrix = new Matrix4f()
                    .translate(origin.x, origin.y + bob, origin.z)
                    .rotateX((float) Math.toRadians(degrees))
                    .translate(-origin.x, -origin.y, -origin.z);
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

        void at(float seconds) {
            float phase = (float) (2.0D * Math.PI * SYNTH_HERTZ * seconds);
            float bob = SYNTH_BOB * (float) Math.sin(2.0F * phase);
            OpenMatrix4f torso = about(rig.jointOrigin(JointTable.TORSO), SYNTH_LEAN, bob);
            poses[JointTable.TORSO] = torso;
            poses[JointTable.CHEST] = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.CHEST),
                            SYNTH_LEAN * 0.55F + 2.5F * (float) Math.sin(2.0F * phase), bob * 0.5F),
                    new OpenMatrix4f());
            poses[JointTable.HEAD] = OpenMatrix4f.mul(poses[JointTable.CHEST],
                    about(rig.jointOrigin(JointTable.HEAD),
                            -SYNTH_LEAN * 0.45F + 3.5F * (float) Math.sin(2.0F * phase + 1.0F),
                            0.0F), new OpenMatrix4f());
            OpenMatrix4f thighRight = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.THIGH_R),
                            SYNTH_STRIDE * (float) Math.sin(phase), 0.0F), new OpenMatrix4f());
            OpenMatrix4f thighLeft = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.THIGH_L),
                            SYNTH_STRIDE * (float) Math.sin(phase + Math.PI), 0.0F),
                    new OpenMatrix4f());
            poses[JointTable.THIGH_R] = thighRight;
            poses[JointTable.THIGH_L] = thighLeft;
            poses[JointTable.LEG_R] = OpenMatrix4f.mul(thighRight,
                    about(rig.jointOrigin(JointTable.LEG_R),
                            SYNTH_KNEE * (float) Math.sin(phase + 2.0F), 0.0F), new OpenMatrix4f());
            poses[JointTable.LEG_L] = OpenMatrix4f.mul(thighLeft,
                    about(rig.jointOrigin(JointTable.LEG_L),
                            SYNTH_KNEE * (float) Math.sin(phase + Math.PI + 2.0F), 0.0F),
                    new OpenMatrix4f());
        }

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return rig.toOriginOf(joint);
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            return joint >= 0 && joint < poses.length ? poses[joint] : IDENTITY;
        }
    }

    // ------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------

    /** The joint's origin under the pose, or the zero vector when the pose has no such joint. */
    private static Vector3f originOfJoint(YsmMeshSecondaryMotion.PoseSource pose, int joint) {
        OpenMatrix4f world = pose.poseOf(joint);
        return world == null ? new Vector3f() : new Vector3f(world.m30, world.m31, world.m32);
    }

    /** The joint's origin as the skinning draws it: {@code pose x toOrigin} applied to the origin. */
    private static Vector3f originOf(YsmLegSkirtCollisionProbeTest.Rig rig,
                                     YsmMeshSecondaryMotion.PoseSource pose, int joint) {
        OpenMatrix4f world = pose.poseOf(joint);
        if (world == null) {
            return rig.jointOrigin(joint);
        }
        OpenMatrix4f toOrigin = pose.toOriginOf(joint);
        if (toOrigin == null) {
            return new Vector3f(world.m30, world.m31, world.m32);
        }
        OpenMatrix4f skin = OpenMatrix4f.mul(world, toOrigin, null);
        return new Vector3f(skin.m30, skin.m31, skin.m32);
    }

    /** The model's own forward, carried into the frame the clip draws: the root's turn only. */
    private static Vector3f forwardOf(YsmLegSkirtCollisionProbeTest.Rig rig,
                                      YsmMeshSecondaryMotion.PoseSource pose) {
        OpenMatrix4f world = pose.poseOf(JointTable.ROOT);
        if (world == null) {
            return new Vector3f(FORWARD);
        }
        Vector3f out = new Vector3f(world.m20, world.m21, world.m22);
        if (out.lengthSquared() < 1.0E-9F) {
            return new Vector3f(FORWARD);
        }
        return out.normalize();
    }

    /** The bind-pose leg axis, as {hip, knee, ankle} in the mesh frame. */
    private static float[] legAxis(YsmLegSkirtCollisionProbeTest.Rig rig) {
        Vector3f thigh = rig.jointOrigin(JointTable.THIGH_R);
        Vector3f leg = rig.jointOrigin(JointTable.LEG_R);
        return new float[]{thigh.x, thigh.y, thigh.z, leg.x, leg.y, leg.z};
    }

    /**
     * The rotation about the given lateral axis that takes the rest axis to the posed one, in
     * degrees, positive when the limb swings to the model's <b>back</b> (+Z).
     *
     * <p>Both arguments are copied before anything is normalized: the first version normalized the
     * second in place, so when a caller passed the same vector twice the cross product was taken
     * against a unit vector and the reported swing was the angle between the axis and its own
     * normalized copy - 0, or 180, or nonsense. A measurement helper that silently corrupts its
     * own input is worse than one that is missing, so the copies are explicit.
     */
    private static float signedAngle(Vector3f rest, Vector3f posed) {
        return signedAngle(rest, posed, new Vector3f(1.0F, 0.0F, 0.0F));
    }

    private static float signedAngle(Vector3f rest, Vector3f posed, Vector3f lateral) {
        Vector3f a = new Vector3f(rest);
        Vector3f b = new Vector3f(posed);
        if (a.lengthSquared() < 1.0E-9F || b.lengthSquared() < 1.0E-9F) {
            return 0.0F;
        }
        a.normalize();
        b.normalize();
        Vector3f axis = new Vector3f(a).cross(b);
        float sine = axis.dot(lateral);
        float cosine = a.dot(b);
        return (float) Math.toDegrees(Math.atan2(sine, cosine));
    }

    /**
     * How far the pose has turned a limb's own geometry away from the model's up axis, in the
     * sagittal plane, in degrees - positive towards the model's back (+Z).
     *
     * <p>Read from the joint's <b>deformation</b>, which is the matrix Epic Fight skins that
     * joint's geometry with, so it is exactly the rotation the leg is drawn with. At the bind pose
     * the deformation is the identity and this is zero by construction.
     */
    private static float swingOf(YsmLegSkirtCollisionProbeTest.Rig rig,
                                 YsmMeshSecondaryMotion.PoseSource pose, int joint,
                                 Vector3f forward, Vector3f lateral) {
        OpenMatrix4f deformation = rig.deformationFor(pose, joint);
        if (deformation == null) {
            return 0.0F;
        }
        Vector3f up = YsmMeshSecondaryMotion.transformDirection(deformation,
                new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f());
        Vector3f upRest = new Vector3f(0.0F, 1.0F, 0.0F);
        if (up.lengthSquared() < 1.0E-9F) {
            return 0.0F;
        }
        up.normalize();
        Vector3f axis = new Vector3f(upRest).cross(up);
        float sine = axis.dot(lateral);
        float cosine = upRest.dot(up);
        return (float) Math.toDegrees(Math.atan2(sine, cosine));
    }

    /**
     * The joints' bind-pose world matrices, for placing the volumes before the first frame.
     */
    private static OpenMatrix4f[] bindPoseMatrices(YsmLegSkirtCollisionProbeTest.Rig rig) {
        OpenMatrix4f[] out = new OpenMatrix4f[JointTable.COUNT];
        for (int joint = 0; joint < out.length; joint++) {
            yesman.epicfight.api.animation.Joint skeleton = rig.jointById(joint);
            out[joint] = skeleton == null ? null : rig.restWorldOf(joint);
        }
        return out;
    }

    /**
     * Whether the solver refuses to push this piece out of any volume this frame: the production
     * rule skips a volume that contains the piece's pivot or its resting centre of mass, because
     * pushing a piece out of a volume it starts inside is ejection rather than collision.
     */
    private static boolean skippedFor(YsmBodyColliders colliders, YsmPhysicsParts.Segment piece,
                                      OpenMatrix4f deformation) {
        if (piece == null || colliders == null || colliders.count() == 0) {
            return false;
        }
        Vector3f pivot = YsmMeshSecondaryMotion.transformPoint(deformation, piece.bindPivot(),
                new Vector3f());
        Vector3f rest = YsmMeshSecondaryMotion.transformDirection(deformation, piece.bindRest(),
                new Vector3f());
        Vector3f restCentre = new Vector3f(pivot);
        if (rest.lengthSquared() > 1.0E-8F) {
            restCentre.add(rest.normalize().mul(piece.lever()));
        }
        for (int i = 0; i < colliders.count(); i++) {
            if (colliders.skipFor(pivot, restCentre, 0.2F, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * before the cloth responds at all?
     *
     * <p>Sizing a volume from the drawn limb cannot be done by handing {@code fromGeometry} the
     * leg's own vertices: {@code limbVolume} takes the 85th percentile of the perpendicular
     * distances and pulls the cap centres in by the radius, so a volume built that way sits
     * <b>inside</b> the limb by construction, and feeding it the clip's whole sampled envelope just
     * rotates the principal axis towards the middle of the swing and comes back the same size. The
     * honest version of the question is the one this builds: keep the shipped volume's own centre
     * and axis, and grow its radius and span by a named factor, then measure whether the cloth the
     * user is looking at moves.
     *
     * <p>The growth is expressed by handing the shape rule a synthetic cloud around the shipped
     * volume's axis - a ring of points at each cap centre, at the chosen radius - so the volume the
     * solver gets is built by production's own rule, with only its size chosen here.
     */
    private static YsmBodyColliders grownLimbColliders(YsmLegSkirtCollisionProbeTest.Rig rig,
                                                       float scale) {
        Map<Integer, List<Vector3f>> byJoint = new LinkedHashMap<>();
        YsmBodyColliders shipped = rig.colliders();
        for (int i = 0; i < shipped.count(); i++) {
            int joint = shipped.jointOf(i);
            if (joint != JointTable.THIGH_R && joint != JointTable.THIGH_L
                    && joint != JointTable.LEG_R && joint != JointTable.LEG_L) {
                continue;
            }
            float[] v = shipped.resolvedVolume(i);
            Vector3f end0 = new Vector3f(v[0], v[1], v[2]);
            Vector3f end1 = new Vector3f(v[4], v[5], v[6]);
            float radius = v[7] * scale;
            Vector3f axis = new Vector3f(end1).sub(end0);
            if (axis.lengthSquared() < 1.0E-9F) {
                continue;
            }
            axis.normalize();
            // Push the cap centres apart so that growing the radius grows the whole capsule rather
            // than eating the span: the shipped centre pair is the geometry's own extremes pulled in
            // by the old radius, so adding (scale-1) * radius to each end restores the reach.
            float half = 0.5F * end0.distance(end1) + (scale - 1.0F) * v[7];
            Vector3f centre = new Vector3f(end0).add(end1).mul(0.5F);
            Vector3f a1 = new Vector3f(axis).cross(0.0F, 1.0F, 0.0F);
            if (a1.lengthSquared() < 1.0E-6F) {
                a1 = new Vector3f(axis).cross(1.0F, 0.0F, 0.0F);
            }
            a1.normalize();
            Vector3f a2 = new Vector3f(axis).cross(a1, new Vector3f()).normalize();
            List<Vector3f> cloud = new ArrayList<>();
            for (int ring = 0; ring < 2; ring++) {
                Vector3f capCentre = new Vector3f(axis).mul(ring == 0 ? -half : half).add(centre);
                for (int step = 0; step < 8; step++) {
                    double angle = Math.PI * 2.0 * step / 8.0;
                    Vector3f point = new Vector3f(a1).mul((float) Math.cos(angle) * radius)
                            .add(new Vector3f(a2).mul((float) Math.sin(angle) * radius))
                            .add(capCentre);
                    cloud.add(point);
                }
            }
            byJoint.put(joint, cloud);
        }
        return YsmBodyColliders.fromGeometry(byJoint);
    }

    private static Map<String, Run> sortedByKey(Map<String, Run> runs) {
        Map<String, Run> out = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(runs.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            out.put(key, runs.get(key));
        }
        return out;
    }

    /**
     * Asserts the rig the measurement stands on: the armature's joints must sit where the model's
     * own geometry puts them. A bone tree whose thigh and shin collapsed onto one origin silently
     * turns every leg direction into a zero vector, which reads as "the leg never moves" rather
     * than as a bug - and that is exactly what it did on the first run of this probe.
     *
     * <p>Epic Fight's own biped deliberately puts {@code Knee_R} on top of {@code Leg_R} (they are
     * siblings under the thigh in {@code biped.json}, and only {@code Leg_R} carries geometry), so
     * the pairs checked here are the ones a limb direction is actually measured between.
     */
    private static void assertRigSanity(YsmLegSkirtCollisionProbeTest.Rig rig) {
        for (int[] pair : new int[][]{{JointTable.THIGH_R, JointTable.LEG_R},
                {JointTable.THIGH_L, JointTable.LEG_L}}) {
            float span = rig.jointOrigin(pair[0]).distance(rig.jointOrigin(pair[1]));
            assertTrue(span > 0.15F && span < 0.75F, "the bind armature puts "
                    + JointTable.nameOf(pair[0]) + " and " + JointTable.nameOf(pair[1]) + " "
                    + span + " blocks apart, which is not a leg");
        }
    }

    /** The model's own lateral axis in the frame the clip draws, from its own forward. */
    private static Vector3f lateralOf(Vector3f forward) {
        Vector3f lateral = new Vector3f(forward).cross(0.0F, 1.0F, 0.0F);
        if (lateral.lengthSquared() < 1.0E-9F) {
            return new Vector3f(1.0F, 0.0F, 0.0F);
        }
        return lateral.normalize();
    }

    private static String legName(int joint) {
        switch (joint) {
            case 1:
                return "Thigh_R";
            case 4:
                return "Thigh_L";
            case 2:
                return "Leg_R";
            case 5:
                return "Leg_L";
            case 3:
                return "Knee_R";
            case 6:
                return "Knee_L";
            default:
                return "-";
        }
    }

    // ------------------------------------------------------------------
    // The report
    // ------------------------------------------------------------------

    private static String clipReport(Map<String, Animation> clips) {
        StringBuilder out = new StringBuilder();
        out.append("## 0. The clips, as loaded\n\n")
                .append("| clip | keys | duration | sampled frames |\n|---|---|---|---|\n");
        for (Map.Entry<String, Animation> entry : clips.entrySet()) {
            Animation animation = entry.getValue();
            out.append("| ").append(entry.getKey()).append(" (`").append(animation.path)
                    .append("`) | ").append(animation.worlds.size()).append(" joints | ")
                    .append(fmt(animation.duration)).append(" s | ")
                    .append(animation.times.length).append(" |\n");
        }
        out.append('\n');
        return out.toString();
    }

    private static String runSection(State state, Run run) {
        StringBuilder out = new StringBuilder();
        out.append("## ").append(state.name()).append("\n\n")
                .append("`skin` is the least distance between the panel's drawn vertices and the ")
                .append("drawn leg's own vertices; `depth` is that distance subtracted from the same ")
                .append("pair's distance in the model's **bind pose**, so a positive depth is how far ")
                .append("the clip has driven the leg **through** the cloth the author left around it. ")
                .append("`wire` is the deepest vertex inside a shipped limb capsule - the previous ")
                .append("round's metric. `leg` is the limb the closest pair belongs to; `back` counts ")
                .append("the measured frames in which the closest pair is on the far side of the leg ")
                .append("from the body's forward. All lengths in blocks.\n\n")
                .append("| panel | skin | depth | wire | leg | frames with a pair behind | frames |\n")
                .append("|---|---|---|---|---|---|---|\n");
        List<Map.Entry<String, Panel>> rows = new ArrayList<>(run.panels.entrySet());
        rows.sort((a, b) -> Float.compare(b.getValue().skinDepth, a.getValue().skinDepth));
        for (Map.Entry<String, Panel> entry : rows) {
            Panel panel = entry.getValue();
            if (panel.frames == 0) {
                continue;
            }
            out.append("| `").append(entry.getKey()).append("` | ").append(fmt(panel.skinMin))
                    .append(" | ").append(fmt(panel.skinDepth)).append(" | ")
                    .append(fmt(panel.wireDepth)).append(" | ").append(panel.leg).append(" | ")
                    .append(panel.behindFrames).append(" | ").append(panel.frames).append(" |\n");
        }
        out.append("\n**Thigh swing**: ").append(fmt(run.thighSwingDeg))
                .append(" deg about the model's lateral axis (").append(fmt(run.thighFrontDeg))
                .append(" deg forward, ").append(fmt(run.thighBackDeg))
                .append(" deg back); knee ").append(fmt(run.kneeSwingDeg))
                .append(" deg. **Deepest wire penetration**: ")
                .append(fmt(run.worstWire)).append(" (").append(run.worstWirePanel)
                .append("). **Closest the two meshes ever come**: ").append(fmt(run.worstSkin))
                .append(" (`").append(run.worstSkinPanel).append("` against ").append(run.worstSkinLeg)
                .append(", frame ").append(run.worstSkinFrame).append(").\n\n");
        return out.toString();
    }

    private static String comparison(Map<String, Run> runs) {
        StringBuilder out = new StringBuilder();
        out.append("## The same number across the states\n\n")
                .append("Every state through the same loop and the same metric. `closest` is the ")
                .append("least distance the drawn skirt's own vertices ever came to the drawn leg's ")
                .append("own vertices; `deepest` is the largest measured depth of the leg through the ")
                .append("bind clearance; `wire` is the shipped capsules' deepest penetration; `turn` ")
                .append("is the most the collision response moved any piece in any one frame. ")
                .append("`wire'` is the same penetration with the volumes placed the other way - ")
                .append("each cap centre mapped through `pose x toOrigin` as a point, which is the ")
                .append("code the previous round used; the two placements must agree, and where they ")
                .append("do not, this round says so rather than quoting either.\n\n")
                .append("| state | closest skirt-leg pair | deepest panel | depth | wire | wire' | turn | thigh swing |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Run> entry : runs.entrySet()) {
            Run run = entry.getValue();
            out.append("| ").append(entry.getKey()).append(" | ").append(fmt(run.worstSkin))
                    .append(" (`").append(run.worstSkinPanel).append("`, ")
                    .append(run.worstSkinLeg).append(") | ").append(deepestPanel(run))
                    .append(" | ").append(fmt(run.worstSkinDepth)).append(" | ")
                    .append(fmt(run.worstWire)).append(" | ")
                    .append(fmt(run.worstWireByDeformation)).append(" | ")
                    .append(fmt(run.worstTurn))
                    .append(" | ").append(fmt(run.thighSwingDeg))
                    .append(" deg |\n");
        }
        out.append('\n');
        return out.toString();
    }

    /** The volumes the model ships, against the leg the model draws. */
    private static String colliderShapeReport(YsmLegSkirtCollisionProbeTest.Rig rig) {
        StringBuilder out = new StringBuilder();
        out.append("## 1. The collider the shape rule builds, against the leg the model draws\n\n")
                .append("`shipped` is `YsmBodyColliders` on this model's own limb geometry in bind ")
                .append("space - what the frame path places every frame. `span` is the capsule's ")
                .append("cap-centre distance, `tube r` its radius; `limb span` is the extent of the ")
                .append("limb's own drawn geometry through its centre, so a collider far shorter ")
                .append("than that covers only part of the leg. The radius is the rule's 85th ")
                .append("percentile of the perpendicular distances and the cap centres are pulled in ")
                .append("by it, so the volume is inside the limb <b>by construction</b>: feeding the ")
                .append("rule the drawn leg's own vertices returns these same numbers, which is why ")
                .append("the experiment below sizes the tube by hand instead.\n\n")
                .append("| joint | end0 | end1 | span | tube r | limb span | vertices |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (int joint : new int[]{JointTable.THIGH_R, JointTable.THIGH_L,
                JointTable.LEG_R, JointTable.LEG_L}) {
            appendShape(out, rig.colliders(), joint);
            List<Vector3f> part = rig.legPose.get(joint);
            out.append("| ").append(JointTable.nameOf(joint)).append(" | limb geometry | - | ")
                    .append(fmt(part == null ? Float.NaN : extent(part))).append(" | - | ")
                    .append(fmt(legRadius(rig, joint))).append(" | ")
                    .append(part == null ? "-" : String.valueOf(part.size() / 2)).append(" |\n");
        }
        out.append('\n');
        return out.toString();
    }

    /** How far the limb's own drawn geometry reaches from the joint-to-joint axis, blocks. */
    private static float legRadius(YsmLegSkirtCollisionProbeTest.Rig rig, int joint) {
        List<Vector3f> part = rig.legPose.get(joint);
        if (part == null || part.isEmpty()) {
            return Float.NaN;
        }
        int below = joint == JointTable.THIGH_R ? JointTable.LEG_R
                : joint == JointTable.THIGH_L ? JointTable.LEG_L : -1;
        Vector3f origin = rig.jointOrigin(joint);
        Vector3f axis = below >= 0
                ? new Vector3f(rig.jointOrigin(below)).sub(origin) : new Vector3f(0.0F, -1.0F, 0.0F);
        if (axis.lengthSquared() < 1.0E-9F) {
            return Float.NaN;
        }
        axis.normalize();
        float farthest = 0.0F;
        Vector3f offset = new Vector3f();
        for (Vector3f vertex : part) {
            offset.set(vertex).sub(origin);
            float along = offset.dot(axis);
            farthest = Math.max(farthest, (float) Math.sqrt(Math.max(0.0F,
                    offset.lengthSquared() - along * along)));
        }
        return farthest;
    }

    private static void appendShape(StringBuilder out, YsmBodyColliders colliders, int joint) {
        if (colliders == null) {
            return;
        }
        for (int i = 0; i < colliders.count(); i++) {
            if (colliders.jointOf(i) != joint) {
                continue;
            }
            float[] v = colliders.resolvedVolume(i);
            Vector3f end0 = new Vector3f(v[0], v[1], v[2]);
            Vector3f end1 = new Vector3f(v[4], v[5], v[6]);
            out.append("| ").append(JointTable.nameOf(joint)).append(" | ").append(fmtVec(end0))
                    .append(" | ").append(fmtVec(end1)).append(" | ")
                    .append(fmt(end0.distance(end1))).append(" | ").append(fmt(v[7]))
                    .append(" | | |\n");
        }
    }

    /** The longest extent of a vertex cloud through its own centre. */
    private static float extent(List<Vector3f> points) {
        Vector3f centre = new Vector3f();
        for (Vector3f point : points) {
            centre.add(point);
        }
        centre.div(points.size());
        float farthest = 0.0F;
        for (Vector3f point : points) {
            farthest = Math.max(farthest, centre.distance(point) * 2.0F);
        }
        return farthest;
    }

    private static String experimentSection(State state, YsmLegSkirtCollisionProbeTest.Rig rig,
                                            Map<String, Run> sized, Run shipped) {
        StringBuilder out = new StringBuilder();
        out.append("## The fix experiment: ").append(state.name())
                .append(", the limb collider grown by a factor\n\n")
                .append("The same clip, the same frame loop, the same model - the only change is ")
                .append("the size of the four limb capsules, which keep the shipped volume's own ")
                .append("centre and axis and grow both radius and span. This is the narrowest form of ")
                .append("\"size the collider from the drawn limb\": if the response is still zero at ")
                .append("2x, the cloth that clips has not reached the collision path at all and no ")
                .append("resizing of these volumes can be the fix.\n\n")
                .append("| joint | shipped span / r | 1x | 1.5x | 2x | drawn limb span / r |\n")
                .append("|---|---|---|---|---|---|\n");
        for (int joint : new int[]{JointTable.THIGH_R, JointTable.LEG_R}) {
            out.append("| ").append(JointTable.nameOf(joint)).append(" | ")
                    .append(shapeOf(rig.colliders(), joint)).append(" | ");
            for (Run run : sized.values()) {
                out.append(shapeOf(run.colliders, joint)).append(" | ");
            }
            out.append(fmt(extent(rig.legPose.get(joint)))).append(" / ")
                    .append(fmt(legRadius(rig, joint))).append(" |\n");
        }
        out.append("\n| measurement | shipped collider | 1x | 1.5x | 2x |\n")
                .append("|---|---|---|---|---|\n")
                .append("| closest skirt-leg pair | ").append(fmt(shipped.worstSkin)).append(" | ");
        for (Run run : sized.values()) {
            out.append(fmt(run.worstSkin)).append(" | ");
        }
        out.append("\n| deepest leg-through-cloth | ").append(fmt(shipped.worstSkinDepth))
                .append(" | ");
        for (Run run : sized.values()) {
            out.append(fmt(run.worstSkinDepth)).append(" | ");
        }
        out.append("\n| deepest capsule penetration | ").append(fmt(shipped.worstWire)).append(" | ");
        for (Run run : sized.values()) {
            out.append(fmt(run.worstWire)).append(" | ");
        }
        out.append("\n| most the response moved a piece, blocks | ").append(fmt(shipped.worstTurn))
                .append(" | ");
        for (Run run : sized.values()) {
            out.append(fmt(run.worstTurn)).append(" | ");
        }
        out.append("\n| pieces the response touched at all | ").append(touched(shipped)).append(" | ");
        for (Run run : sized.values()) {
            out.append(touched(run)).append(" | ");
        }
        out.append("\n| pieces skipped as contained | ").append(skipped(shipped)).append(" | ");
        for (Run run : sized.values()) {
            out.append(skipped(run)).append(" | ");
        }
        out.append("\n\n");
        return out.toString();
    }

    private static String skipped(Run run) {
        return run.skippedPieces + " of " + run.panels.size();
    }

    private static String shapeOf(YsmBodyColliders colliders, int joint) {
        if (colliders == null) {
            return "-";
        }
        for (int i = 0; i < colliders.count(); i++) {
            if (colliders.jointOf(i) != joint) {
                continue;
            }
            float[] v = colliders.resolvedVolume(i);
            return fmt(new Vector3f(v[0], v[1], v[2]).distance(new Vector3f(v[4], v[5], v[6])))
                    + " / " + fmt(v[7]);
        }
        return "-";
    }

    private static String touched(Run run) {
        int count = 0;
        for (Panel panel : run.panels.values()) {
            if (panel.contactTurn > 1.0E-5F) {
                count++;
            }
        }
        return count + " of " + run.panels.size();
    }

    private static String deepestPanel(Run run) {
        String worst = "-";
        float depth = 0.0F;
        for (Map.Entry<String, Panel> entry : run.panels.entrySet()) {
            if (entry.getValue().skinDepth > depth) {
                depth = entry.getValue().skinDepth;
                worst = "`" + entry.getKey() + "` (" + entry.getValue().leg + ")";
            }
        }
        return worst;
    }

    private static String mechanism(YsmLegSkirtCollisionProbeTest.Rig rig, Map<String, Run> runs,
                                    Map<String, Run> sized) {
        StringBuilder out = new StringBuilder();
        Run walk = runs.get(EF_WALK.name());
        Run run = runs.get(EF_RUN.name());
        Run fall = runs.get(EF_FALL.name());
        Run bindSpace = runs.get(SYNTHETIC_BIND.name());
        out.append("## The mechanism, with the numbers that decide it\n\n")
                .append("Four candidate mechanisms, each with the measurement that settles it.\n\n")
                .append("### (a) the skirt is bound to the torso and nothing poses it away from the ")
                .append("legs\n\n")
                .append("Every garment panel of this model hangs on **Torso (joint 7)** - all ")
                .append("thirty-six of them, and the bones that carry them are `mapped: false`, so the ")
                .append("pose moves them only through the torso. The clips move the legs and not the ")
                .append("torso's relation to them:\n\n")
                .append("- thigh swing about the model's lateral axis: **")
                .append(fmt(walk.thighSwingDeg)).append(" deg** (walk), **")
                .append(fmt(run.thighSwingDeg)).append(" deg** (run), **")
                .append(fmt(fall.thighSwingDeg)).append(" deg** (fall).\n")
                .append("- the leg drives through the clearance the mesh author left between cloth ")
                .append("and limb: deepest **").append(fmt(run.worstSkinDepth))
                .append(" blocks** (run), **").append(fmt(fall.worstSkinDepth))
                .append(" blocks** (fall), against a walk of **").append(fmt(walk.worstSkinDepth))
                .append(" blocks** - and the *closest the two drawn meshes ever come* falls from ")
                .append(fmt(walk.worstSkin)).append(" blocks in the walk to **")
                .append(fmt(run.worstSkin)).append(" blocks** in the run and **")
                .append(fmt(fall.worstSkin)).append(" blocks** in the fall, i.e. to contact.\n")
                .append("- in the run and the fall the closest cloth-leg pair is on the **far side ")
                .append("of the leg** in hundreds of the 400 measured frames, which is the ")
                .append("\"through to behind the legs\" the report names.\n\n")
                .append("### (b) the shipped capsules are too thin and too short to be the surface ")
                .append("the cloth should rest on\n\n")
                .append("The thigh tube is **0.223 blocks long and 0.116 blocks in radius** against a ")
                .append("thigh whose own drawn geometry spans **0.636 blocks** and reaches **0.119 ")
                .append("blocks** from its axis; the shin tube spans 0.188 against drawn geometry of ")
                .append("0.850 blocks reaching 0.208. The shape rule takes the 85th percentile of the ")
                .append("perpendicular distances and pulls the cap centres in by the radius, so the ")
                .append("volume sits **inside the limb by construction** and covers the upper ")
                .append("two-thirds of the thigh at best. Feeding the rule the leg's own drawn ")
                .append("geometry returns the same numbers - that is the rule, not a bug - which is why ")
                .append("the experiment below grows the tube by a factor instead of rebuilding it from ")
                .append("the mesh.\n\n")
                .append("### (c) the collision path's tested point is wrong\n\n")
                .append("Measured with the volumes placed every frame, the response **does** fire: ")
                .append("the capsule's deepest penetration into the cloth is **")
                .append(fmt(run.worstWire)).append(" blocks** in the run, **")
                .append(fmt(fall.worstWire)).append("** in the fall (the same numbers come out of ")
                .append("the other placement, to within 0.001 blocks), and the response moves a ")
                .append("piece by up to **").append(fmt(run.worstTurn)).append(" blocks**. The ")
                .append("previous round's \"not one skirt vertex enters a leg capsule in a single ")
                .append("frame\" was **not** a measurement: its capsule loop was guarded by ")
                .append("`rig.jointSegment(joint) >= 0`, and no simulated piece sits on a leg joint - ")
                .append("this model's 59 simulated pieces are all on Torso, Chest and Head - so the ")
                .append("guard was false for every limb volume and the loop body never ran. `0.000` ")
                .append("was the absence of a comparison, not the absence of contact. The production ")
                .append("frame path **does** call `update` every frame, and with both placements ")
                .append("running the two agree, so the number above is the one the frames carry.\n\n")
                .append("### (d) nothing the collision layer can do, because the cloth is already ")
                .append("touching\n\n")
                .append("This is what the experiment shows. Growing the four limb capsules - keeping ")
                .append("their shipped centre and axis, growing radius and span together - changes the ")
                .append("measurement not at all:\n\n")
                .append("| collider | closest pair | depth | capsule penetration | response | pieces ")
                .append("touched |\n|---|---|---|---|---|---|\n")
                .append("| shipped | ").append(fmt(fall.worstSkin)).append(" | ")
                .append(fmt(fall.worstSkinDepth)).append(" | ").append(fmt(fall.worstWire))
                .append(" | ").append(fmt(fall.worstTurn)).append(" | ")
                .append(touched(fall)).append(" |\n");
        for (Map.Entry<String, Run> grownEntry : sized.entrySet()) {
            Run grown = grownEntry.getValue();
            out.append("| grown ").append(grownEntry.getKey()).append(" | ")
                    .append(fmt(grown.worstSkin))
                    .append(" | ").append(fmt(grown.worstSkinDepth)).append(" | ")
                    .append(fmt(grown.worstWire)).append(" | ").append(fmt(grown.worstTurn))
                    .append(" | ").append(touched(grown)).append(" |\n");
        }
        out.append("\nA skirt panel is a surface the leg passes **beside**, and the rest place the ")
                .append("solver tests is the part that is inside the volume the leg is sweeping ")
                .append("through - the production rule skips exactly that case, because pushing a ")
                .append("piece out of a volume it starts inside is ejection rather than collision. So ")
                .append("a collision volume can only ever act on a piece it does not already contain, ")
                .append("and this model's cloth starts ")
                .append("0.10 blocks from the limb. **The mechanism is (a), with (b) as the reason the ")
                .append("existing response cannot help and (d) as the reason a bigger one cannot ")
                .append("either.**\n\n")
                .append("### Which joint each offending panel is bound to\n\n")
                .append("| panel | joint | parent piece |\n|---|---|---|\n");
        for (YsmLegSkirtCollisionProbeTest.Segment segment : rig.segments) {
            if (!isPanel(segment.name)) {
                continue;
            }
            out.append("| `").append(segment.name).append("` | ")
                    .append(JointTable.nameOf(segment.joint)).append(" (").append(segment.joint)
                    .append(") | ")
                    .append(segment.parent < 0 ? "-" : "`" + rig.segments.get(segment.parent).name + "`")
                    .append(" |\n");
        }
        out.append('\n');
        return out.toString();
    }

    private static boolean isPanel(String name) {
        for (String panel : PANELS) {
            if (panel.equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    private static byte[] resourceBytes(String path) throws IOException {
        try (InputStream stream = YsmEfSkirtClipProbeTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "the bundled resource " + path + " is not on the test classpath");
            return stream.readAllBytes();
        }
    }

    private static String sha256Of(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                out.append(String.format("%02X", value));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static Path convertedPackRoot() {
        String property = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY);
        if (property == null || property.isBlank()) {
            property = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
        }
        if (property == null || property.isBlank()) {
            return null;
        }
        Path root = Paths.get(property).toAbsolutePath();
        Path pack = root.getParent().resolve("ysm_epicfight_compat").resolve("resourcepack")
                .resolve("assets").resolve("ysm_epicfight_compat");
        return Files.isDirectory(pack.resolve("ysm_runtime/entity")) ? pack : null;
    }

    private static Matrix4f matrixOf(JsonArray values) {
        return new Matrix4f(
                values.get(0).getAsFloat(), values.get(1).getAsFloat(),
                values.get(2).getAsFloat(), values.get(3).getAsFloat(),
                values.get(4).getAsFloat(), values.get(5).getAsFloat(),
                values.get(6).getAsFloat(), values.get(7).getAsFloat(),
                values.get(8).getAsFloat(), values.get(9).getAsFloat(),
                values.get(10).getAsFloat(), values.get(11).getAsFloat(),
                values.get(12).getAsFloat(), values.get(13).getAsFloat(),
                values.get(14).getAsFloat(), values.get(15).getAsFloat());
    }

    private static Matrix4f transpose(Matrix4f matrix) {
        return new Matrix4f(matrix).transpose();
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

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String fmtVec(Vector3f value) {
        return value == null ? "null" : String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)",
                value.x, value.y, value.z);
    }

    private static float degreesOf(OpenMatrix4f matrix) {
        if (matrix == null) {
            return Float.NaN;
        }
        Quaternionf quaternion = new Quaternionf();
        float m00 = matrix.m00;
        float m01 = matrix.m01;
        float m02 = matrix.m02;
        float m10 = matrix.m10;
        float m11 = matrix.m11;
        float m12 = matrix.m12;
        float m20 = matrix.m20;
        float m21 = matrix.m21;
        float m22 = matrix.m22;
        float trace = m00 + m11 + m22;
        if (trace > 0.0F) {
            float s = (float) Math.sqrt(trace + 1.0F) * 2.0F;
            quaternion.set((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25F * s);
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1.0F + m00 - m11 - m22) * 2.0F;
            quaternion.set(0.25F * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s);
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1.0F + m11 - m00 - m22) * 2.0F;
            quaternion.set((m01 + m10) / s, 0.25F * s, (m12 + m21) / s, (m02 - m20) / s);
        } else {
            float s = (float) Math.sqrt(1.0F + m22 - m00 - m11) * 2.0F;
            quaternion.set((m02 + m20) / s, (m12 + m21) / s, 0.25F * s, (m10 - m01) / s);
        }
        quaternion.normalize();
        return (float) Math.toDegrees(quaternion.angle());
    }
}
