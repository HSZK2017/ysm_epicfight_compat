package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.JointTable;
import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.ysm.YsmBinaryReader;
import com.ysmef.compat.ysm.YsmFileCrypto;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The point the secondary-motion solver tests a piece against, set beside the point the piece is
 * <b>drawn</b> at - and what moving the first onto the second does to every model in the corpus.
 *
 * <h2>The invariant</h2>
 *
 * <p>The mesh draws a part as {@code pose[joint] x toOrigin[joint] x delta x vertex}, the delta being
 * the one the simulation wrote ({@code YsmMeshSecondaryMotion#simulate}: the piece's own swing
 * composed under its parent's, all the way up). The solver's collision test asks about
 * {@code pivot + direction*lever} in the model's frame, where {@code direction} is its own integrated
 * answer and the pivot is {@code deformation x bindPivot}. The piece's own swing is therefore already
 * in the tested point; the only transform it lacks is the <b>ancestors'</b> delta. What this probe
 * measures is:
 *
 * <pre>
 *   tested point, rotated by the parent's delta  ==  drawn centre of mass
 *   tested point, unrotated (today)              ==  the drawn centre minus the ancestors' share
 * </pre>
 *
 * <h2>How the "before" arm is produced</h2>
 *
 * <p>Not with a copy of the old code. Production reaches the fix through one value: for a piece with a
 * parent it wraps the collider set, and for a piece with none it hands the solver the colliders it was
 * given. Each state is therefore run <b>twice through the production frame loop</b> - once with the
 * real {@link YsmBodyColliders} (the shipped path, parent delta folded in) and once with a wrapper
 * that forwards every call to the same {@code YsmBodyColliders} and changes nothing (bit for bit the
 * branch production takes for a piece with no parent). Nothing is reimplemented and nothing is
 * stubbed: the two arms differ only in which {@code Colliders} production is handed.
 */
class YsmCollisionPointProbeTest {

    private static final String MAID = "wine_fox/01_taisho_maid";

    /** Opt-in corpus sweep, as the other sweeps in this suite: {@code E:\program\JAVA\ysm-model-repo}. */
    private static final String CORPUS_ROOT = "YSMEF_YSM_CORPUS_ROOT";

    private static final float DT = 1.0F / 60.0F;
    private static final int FRAMES = 600;
    private static final int WARMUP = 200;
    /**
     * The same loop over a handful of frames, for an iteration that is only asking a question of the
     * geometry. {@code YSMEF_PROBE_SHORT} is an environment variable so a single Gradle invocation can
     * be reused for both the measurement and the algebra check, which on this machine is the
     * difference between a one-minute round trip and a six-minute one - not because the numbers it
     * produces may be reported: the frame count it yields is stamped into the report it writes.
     */
    private static final int SHORT_FRAMES = 24;
    private static final int SHORT_WARMUP = 8;
    /** Set this environment variable to run the same loop over {@link #SHORT_FRAMES} frames. */
    private static final String SHORT_PROBE = "YSMEF_PROBE_SHORT";
    /** The corpus sweep is a blast radius, not a dynamics study: fewer frames, same states. */
    private static final int CORPUS_FRAMES = 360;
    private static final int CORPUS_WARMUP = 180;
    private static final float[] NO_TURN = {0.0F, 0.0F};

    private static final Path REPORT =
            Paths.get("build", "reports", "ysm-collision-point-measurement.md");

    // The previous rounds' synthetic gait, unchanged, so "sprint" is the state it was.
    private static final float SYNTH_LEAN = 6.0F;
    private static final float SYNTH_BOB = 0.022F;
    private static final float SYNTH_STRIDE = 20.0F;
    private static final float SYNTH_KNEE = 16.0F;
    private static final float SYNTH_HERTZ = 1.05F;

    private static final float SPRINT_SPEED = 5.612F;
    private static final float WALK_SPEED = 4.317F;

    private static final String WALK_CLIP = "/epicfight/anim_walk.json";
    private static final String RUN_CLIP = "/epicfight/anim_run.json";

    /** The clip revisions the earlier rounds pinned; re-asserted here so the state is the state. */
    private static final Map<String, String> CLIP_SHA256 = Map.of(
            WALK_CLIP, "38F71817692DD9BA69E2AA05566299FE7422C1892D8D52B0ACE6306A06F80B05",
            RUN_CLIP, "762AE35FE778264335EA21FEC452D07FC3676526567038D667E8B0D1096C1730");

    // ------------------------------------------------------------------
    // 1. The invariant, on the reported model
    // ------------------------------------------------------------------

    @Test
    void thePointTheSolverTestsIsThePointThePieceIsDrawnAt() throws Exception {
        Path runtime = deployed("ysm_runtime/entity/" + MAID + ".json");
        Path mesh = deployed("animmodels/entity/" + MAID + ".json");
        assumeTrue(runtime != null && mesh != null,
                "set YSMEF_YSM_CONFIG_ROOT to the instance's config/yes_steve_model to measure the "
                        + "deployed artefacts");
        YsmLegSkirtCollisionProbeTest.Rig rig =
                YsmLegSkirtCollisionProbeTest.Rig.load(runtime, mesh, MAID,
                        YsmLegSkirtCollisionProbeTest.LOGGED_MAID_SIMULATED);

        int frames = System.getenv(SHORT_PROBE) == null ? FRAMES : SHORT_FRAMES;
        int warmup = System.getenv(SHORT_PROBE) == null ? WARMUP : SHORT_WARMUP;

        // The whole measurement runs inside a try/finally so that the diagnostic dump is written even
        // when an assertion fires - which is exactly the case a reader needs it for. A one-line fix to
        // a probe does not help if the round that made it left no numbers behind.
        try {
        StringBuilder report = new StringBuilder();
        report.append("# The collision test point, moved onto the point the piece is drawn at\n\n")
                .append("**Probe revision R8** (instrument: one `frame`-tagged question record per piece ")
                .append("per pass, read at the moment it is answered; probe-side delta composition ")
                .append("`parent x own`, as production builds it; **the drawn point is ")
                .append("`deformation x delta x vertex`** - the mesh's own order, pinned by the ")
                .append("root-piece invariant in `assertInstrument`, where rounds 2-5 applied the delta ")
                .append("to an already-posed point; **every long-lived arm is advanced from frame 0 in ")
                .append("lockstep**, which rounds 6-7 did not do and which is the whole of what they ")
                .append("were measuring).\n\n")
                .append("Model `").append(rig.modelId).append("`, ").append(rig.segments.size())
                .append(" simulated pieces. Both arms are the production frame loop ")
                .append("(`YsmMeshSecondaryMotion.simulate`), ").append(frames)
                .append(" frames of ").append(fmt(DT)).append(" s, the first ").append(warmup)
                .append(" skipped. **before** = production handed an identity collider wrapper; ")
                .append("**after** = production's own parent-frame wrapper. See the class comment.")
                .append(System.getenv(SHORT_PROBE) == null ? ""
                        : "\n\n> **SHORT RUN** (`" + SHORT_PROBE + "` set): the frame counts above are "
                                + "reduced, so the gap and drift columns are a geometry check and not "
                                + "the measured numbers.")
                .append("\n\n");

        for (Drive drive : new Drive[]{
                new Drive("EF run/sprint", RUN_CLIP, SPRINT_SPEED),
                new Drive("EF walk", WALK_CLIP, WALK_SPEED),
                new Drive("synthetic sprint", null, SPRINT_SPEED)}) {
            YsmMeshSecondaryMotion.clear();
            StateRun run = measure(rig, drive, frames, warmup);
            report.append(section(run, rig));
            report.append(harness(run));
            report.append(diagnosticSection(run, rig));
            report.append(termsSection(run));
            // Written before any assertion: a failure must leave the numbers behind.
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, report.toString(), StandardCharsets.UTF_8);
        }
        System.out.println(report);

        // The control: a piece with no chain is tested where it is drawn on both arms.
        YsmMeshSecondaryMotion.clear();
        StateRun sprint = measure(rig, new Drive("EF run/sprint", RUN_CLIP, SPRINT_SPEED), frames, warmup);
        assertHarnessLive(sprint);
        assertInstrumentMeasured(sprint);
        assertTrue(sprint.rootPieces > 0, "the model must have pieces with no chain, for the control");
        assertTrue(sprint.chainedPieces > 0, "the model must have chained pieces to measure");
        assertNotNull(sprint.smallestOffsetPiece,
                "the model must have a piece to use as the control");
        assertTrue(sprint.smallestOffset <= 0.05F,
                "a control piece must exist: the smallest |anchor - pivot| on this model is "
                        + fmt(sprint.smallestOffset) + " blocks (`" + sprint.smallestOffsetPiece + "`)");
        // ------------------------------------------------------------------
        // What the two arms are, and what the assertion is.
        //
        // `before` is production handed a pass-through wrapper: every call forwarded to the real
        // `YsmBodyColliders` and nothing changed, which is bit for bit the branch production took for
        // every piece before the ancestors' frame existed. That arm is not a re-implementation of the
        // old code - it IS the old behaviour, reached through the same `simulate` - so the "before"
        // column is a measurement of the shipped defect and not a model of it.
        //
        // `after` is production handed the real collider set, which is what the frame path does.
        // The claim is that the point a volume is asked about is then the point the piece is drawn at.
        // ------------------------------------------------------------------
        System.out.println("YSCOLLISION-POINT EF run/sprint: worst tested-vs-drawn gap before="
                + fmt(sprint.worstBefore) + " (`" + sprint.worstBeforePiece + "`) after="
                + fmt(sprint.worstAfter) + " (`" + sprint.worstAfterPiece + "`)"
                + "; control piece `" + sprint.smallestOffsetPiece
                + "` |anchor-pivot|=" + fmt(sprint.smallestOffset)
                + " gap before=" + fmt(sprint.smallestOffsetGapBefore)
                + " after=" + fmt(smallestOffsetGapAfter(sprint))
                + "; contact state changed for " + sprint.contactChanged.size() + " piece(s)"
                + "; newly skipped " + sprint.newlySkipped.size()
                + "; untouched drift " + fmt(sprint.worstUntouchedDrift) + " blocks"
                + "; after-vs-after replay drift " + fmt(sprint.worstReplayDrift) + " blocks"
                + "; consecutive-pass drift " + fmt(sprint.worstReplay2Drift) + " blocks");
        // The frame path's own determinism, before any claim about an arm is worth making. `after`
        // against `after`, same pose, same frames, two states: this cannot legitimately be anything but
        // zero, and if it is not, the untouched-drift red line below is measuring `simulate`'s hidden
        // state rather than the collision frame.
        assertTrue(sprint.worstReplayDrift <= 1.0E-6F,
                "the frame path must be deterministic: the same arm run twice on the same pose must "
                        + "draw every piece in the same place, and `" + sprint.worstReplayPiece
                        + "` moved " + fmt(sprint.worstReplayDrift) + " blocks");
        assertTrue(sprint.worstBefore > 0.05F,
                "the defect must be visible before the fix: worst tested-vs-drawn gap "
                        + fmt(sprint.worstBefore) + " blocks on `" + sprint.worstBeforePiece + "`");
        // TEMPORARY, for the baseline run only: production is at HEAD for this measurement, so the
        // "after" arm is today's behaviour and the assertion below is the one the fix must make pass.
        if (System.getenv("YSMEF_ROUND2_BASELINE") == null) {
            assertTrue(sprint.worstAfter <= 1.0E-3F,
                    "after the fix the point a volume is asked about must BE the drawn centre of mass: "
                            + "worst " + fmt(sprint.worstAfter) + " blocks on `"
                            + sprint.worstAfterPiece + "` (was " + fmt(sprint.worstBefore) + " on `"
                            + sprint.worstBeforePiece + "`)");
        }
        // The red line: a piece whose collision was untouched on either arm must be drawn in the same
        // place. The frame is applied to the collision question only, so this must be zero.
        assertTrue(sprint.worstUntouchedDrift <= 1.0E-4F,
                "a piece neither arm's collision touched must be drawn in the same place on both: "
                        + "worst drift " + fmt(sprint.worstUntouchedDrift) + " blocks on `"
                        + sprint.worstUntouchedPiece + "`");
        } finally {
            dumpDiagnostics();
        }
    }

    // ------------------------------------------------------------------
    // 0. The positive control: the harness must be able to fail
    // ------------------------------------------------------------------

    /**
     * A volume the probe places itself, so "the path is live" is a claim this round can test rather
     * than assert. One sphere, moved to wherever the measurement wants it.
     */
    private static final class ControlVolume implements YsmDynamicBoneSolver.Colliders {
        private final Vector3f centre = new Vector3f();
        private float radius;
        private boolean active;
        int resolves;
        int contacts;
        int skips;

        void place(Vector3f point, float sphereRadius) {
            this.centre.set(point);
            this.radius = sphereRadius;
            this.active = true;
        }

        void remove() {
            this.active = false;
        }

        @Override
        public int count() {
            return active ? 1 : 0;
        }

        @Override
        public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
            skips++;
            if (pivot == null) {
                return true;
            }
            // The same containment rule the real set uses, minus the pendulum case: a sphere whose
            // centre IS the piece's rest centre would be skipped as unsatisfiable, which is exactly
            // the trap this control exists to avoid. A sphere whose surface cuts the rest centre
            // (distance < radius but the sphere is not centred on it) is in force.
            return pivot.distance(centre) < radius;
        }

        @Override
        public boolean resolve(Vector3f point, Vector3f velocity, float sphereRadius, int index) {
            resolves++;
            float minimum = radius + Math.max(0.0F, sphereRadius);
            float dx = point.x - centre.x;
            float dy = point.y - centre.y;
            float dz = point.z - centre.z;
            float distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared >= minimum * minimum) {
                return false;
            }
            float distance = Math.max(1.0E-4F, (float) Math.sqrt(distanceSquared));
            point.set(centre.x + dx / distance * minimum, centre.y + dy / distance * minimum,
                    centre.z + dz / distance * minimum);
            contacts++;
            return true;
        }
    }

    /**
     * Force a volume into range and show contact fires; take it away and show it does not.
     *
     * <p>This is the assertion the previous round could not make. Its recorder counted 0 calls in 600
     * frames while the collider set held 4 volumes, so every "nothing changed" number it produced was
     * zero by construction. Here the same path is driven with a volume the probe owns: the point it is
     * placed at is read back from the solver as the point the volumes were asked about, so the sphere
     * lands on the piece's own collision question rather than on a place the probe guessed.
     */
    @Test
    void theCollisionPathIsLiveAndCanBeMadeToFire() throws Exception {
        Path runtime = deployed("ysm_runtime/entity/" + MAID + ".json");
        Path mesh = deployed("animmodels/entity/" + MAID + ".json");
        assumeTrue(runtime != null && mesh != null,
                "set YSMEF_YSM_CONFIG_ROOT to the instance's config/yes_steve_model to run the control");
        YsmLegSkirtCollisionProbeTest.Rig rig =
                YsmLegSkirtCollisionProbeTest.Rig.load(runtime, mesh, MAID,
                        YsmLegSkirtCollisionProbeTest.LOGGED_MAID_SIMULATED);
        YsmPhysicsParts.Model parts = rig.model();
        YsmBodyColliders body = rig.colliders();

        // "far": the sphere is parked a long way from the model, so it can never contain anything.
        // "on":  the sphere is placed on the point the solver last asked about, with a radius big
        //        enough to overlap it. Both runs are the production frame loop and nothing else.
        float[] farResolves = {0.0F};
        float[] farContacts = {0.0F};
        float[] onResolves = {0.0F};
        float[] onContacts = {0.0F};
        String[] place = new String[1];
        Vector3f lastAsked = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);

        for (int run = 0; run < 2; run++) {
            boolean armedRun = run == 1;
            ControlVolume volume = new ControlVolume();
            if (!armedRun) {
                volume.place(new Vector3f(0.0F, 1000.0F, 0.0F), 0.05F);
            }
            Recorder counting = new Recorder(volume);
            YsmMeshSecondaryMotion.clear();
            YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                    parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
            ClipPose pose = new ClipPose(rig, new Drive("control", RUN_CLIP, SPRINT_SPEED));
            Vector3f velocity = new Vector3f(0.0F, 0.0F, -SPRINT_SPEED);
            for (int frame = 0; frame < 240; frame++) {
                pose.at(frame * DT);
                if (frame == 0) {
                    OpenMatrix4f[] poseMatrices = new OpenMatrix4f[JointTable.COUNT];
                    for (int joint = 0; joint < poseMatrices.length; joint++) {
                        poseMatrices[joint] = pose.poseOf(joint);
                    }
                    body.update(rig.armature(), poseMatrices);
                }
                if (armedRun && frame == 61) {
                    // Armed once, from frame 61 on, on the point the FAR run last saw the solver ask
                    // about. `lastAsked` is read from the previous run and never from this one, which
                    // matters: arming on "the point this run has seen so far" would be circular - the
                    // sphere has to be in force before the solver asks anything, or nothing is ever
                    // recorded and the control can never fire. Nothing else about the two runs differs.
                    volume.place(lastAsked, 0.12F);
                }
                YsmMeshSecondaryMotion.simulate(state, pose, DT, velocity, NO_TURN, counting);
                if (counting.sawPoint()) {
                    // Read back through the same interface the solver uses, without changing it: in
                    // the far run this is where the point IS, and in the armed run it is where the
                    // sphere was put.
                    lastAsked.set(counting.lastPoint());
                }
                if (frame >= 59 && frame <= 62) {
                    System.out.println("YSCOLLISION-CONTROL " + (armedRun ? "on " : "far") + " frame "
                            + frame + " sphere at " + point(volume.centre) + " r=" + volume.radius
                            + " active=" + volume.count() + " asked-now " + point(counting.lastPoint())
                            + " saw=" + counting.sawPoint()
                            + " skip=" + volume.skips + " resolve=" + volume.resolves
                            + " contact=" + volume.contacts);
                }
            }
            if (armedRun) {
                onResolves[0] = volume.resolves;
                onContacts[0] = volume.contacts;
                place[0] = point(lastAsked);
            } else {
                farResolves[0] = volume.resolves;
                farContacts[0] = volume.contacts;
            }
        }

        System.out.println("YSCOLLISION-CONTROL far: resolve=" + (int) farResolves[0]
                + " contact=" + (int) farContacts[0]
                + "; on: resolve=" + (int) onResolves[0] + " contact=" + (int) onContacts[0]
                + "; the sphere was placed at " + place[0]);
        assertTrue(farResolves[0] > 0.0F, "with a sphere parked out of range the solver must still "
                + "ask the volume about the piece - that is the path, not the contact");
        assertTrue(farContacts[0] == 0.0F, "a sphere a thousand blocks away must never report contact");
        assertTrue(onResolves[0] > 0.0F, "with the sphere on the piece's own question the solver must "
                + "still ask it");
        assertTrue(onContacts[0] > 0.0F, "the forced volume must fire: contact count "
                + (int) onContacts[0] + " over 240 frames, sphere at " + place[0]);
    }

    /** A recorder that keeps its own last-asked point, for the control above. */
    private static final class Recorder implements YsmDynamicBoneSolver.Colliders {
        private final ControlVolume volume;
        private final Vector3f last = new Vector3f();
        private boolean saw;

        Recorder(ControlVolume volume) {
            this.volume = volume;
        }

        boolean sawPoint() {
            return saw;
        }

        Vector3f lastPoint() {
            return last;
        }

        @Override
        public int count() {
            return volume.count();
        }

        @Override
        public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
            return volume.skipFor(pivot, restCentre, swingReach, index);
        }

        @Override
        public boolean resolve(Vector3f centre, Vector3f velocity, float pointRadius, int index) {
            last.set(centre);
            saw = true;
            return volume.resolve(centre, velocity, pointRadius, index);
        }
    }

    /**
     * A collider set that is asked and never answers, for the "before" arm.
     *
     * <p>{@code count()} is 1 so the solver really enters the collision step and really asks the
     * questions - the arm has to exercise the same path, or the two arms would differ in whether the
     * collision code ran rather than in which frame it asked about. {@code skipFor} returns false and
     * {@code resolve} returns false, so no volume is skipped and no point is ever moved: this is the
     * behaviour of production before the ancestors' frame existed, where the point asked about was the
     * solver's own {@code pivot + direction * lever} and nothing carried it.
     */
    private static final class RejectingColliders implements YsmDynamicBoneSolver.Colliders {
        @Override
        public int count() {
            return 1;
        }

        @Override
        public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
            return false;
        }

        @Override
        public boolean resolve(Vector3f point, Vector3f velocity, float radius, int index) {
            return false;
        }
    }


    private static String firstAsked(Arm arm) {
        StringBuilder out = new StringBuilder("n=" + arm.asked.size());
        for (Map.Entry<String, Vector3f> entry : arm.asked.entrySet()) {
            out.append(" | ").append(entry.getKey()).append('=').append(point(entry.getValue()));
        }
        return out.toString();
    }


    private static float smallestOffsetGapAfter(StateRun run) {        float[] a = run.after.questionedGap.get(run.smallestOffsetPiece);
        return a == null ? -1.0F : a[1];
    }

    /**
     * Whether the harness is live, said out loud for every state.
     *
     * <p>This exists because a metric that cannot fail is worthless, and this probe spent a round
     * reporting zeroes that were zero by construction. The numbers below are the ones that make the
     * rest of the report mean anything: {@code countCalls} is how often the solver asked the volume
     * set what it holds, {@code skipCalls}/{@code resolveCalls} how often it then asked a volume a
     * question. If the solver never reaches the collision step, or reaches it and skips every volume,
     * the gap table is empty and says nothing about the code - and this line says so instead of
     * letting an empty table read as "no difference".
     */
    private static String harness(StateRun run) {
        return "Harness liveness: `count()` asked " + run.before.countCalls + " time(s) on the before "
                + "arm and " + run.after.countCalls + " on the after arm (largest answer "
                + run.before.maxCount + " / " + run.after.maxCount + "); `skipFor` called "
                + run.before.skipCalls + " / " + run.after.skipCalls + " time(s), `resolve` called "
                + run.before.resolveCalls + " / " + run.after.resolveCalls + " time(s). Pieces with a "
                + "recorded asked-about point: " + run.before.questionedGap.size() + " / "
                + run.after.questionedGap.size() + " of " + run.pieces + ".\n\n";
    }

    /** The same assertion as {@link #assertEqualsZero(float, String, StateRun)}, for a bare value. */
    private static void assertZero(float value, String what) {
        assertTrue(value <= 1.0E-4F, what + " must be exact; measured " + fmt(value));
    }

    private static void assertEqualsZero(float value, String what, StateRun run) {
        assertTrue(value <= 1.0E-4F, what + " must be tested exactly where it is drawn; worst "
                + fmt(value) + " on " + run.controlPiece);
    }

    /**
     * The harness saw the collision step run, on both arms, for every piece.
     *
     * <p>Asserted rather than printed, because the failure it guards against is silent and total: the
     * round this class was written in reported "no difference between the arms" from a recorder that
     * had never once been asked a question - 0 {@code skipFor} and 0 {@code resolve} calls in 600
     * frames - and an empty table reads exactly like a clean result. A metric that cannot fail is
     * worthless, so this is the assertion that makes the rest of the class able to fail.
     */
    private static void assertHarnessLive(StateRun run) {
        assertTrue(run.before.resolveCalls > 0 && run.after.resolveCalls > 0,
                "the collision step must actually be asked about volumes on both arms: resolve "
                        + run.before.resolveCalls + " / " + run.after.resolveCalls
                        + " calls, skipFor " + run.before.skipCalls + " / " + run.after.skipCalls
                        + ", count() " + run.before.countCalls + " / " + run.after.countCalls
                        + " with largest answer " + run.before.maxCount + " / " + run.after.maxCount);
        assertTrue(run.before.maxCount > 0 && run.after.maxCount > 0,
                "both arms must see a non-empty collider set: " + run.before.maxCount + " / "
                        + run.after.maxCount);
        assertTrue(run.before.questionedGap.size() >= 10 && run.after.questionedGap.size() >= 10,
                "the point a volume was asked about must be recorded for the pieces, not just for "
                        + "one: " + run.before.questionedGap.size() + " / "
                        + run.after.questionedGap.size() + " of " + run.pieces);
    }

    // ------------------------------------------------------------------
    // The instrument: what the recorded question is, and what it is not
    // ------------------------------------------------------------------

    /**
     * One collision question, copied out of the frame path at the end of the pass that produced it.
     *
     * <p>Everything here is a value the solver BUILT the point from, so the invariant that defines the
     * point - {@code askedPoint == pivot + askedDirection * lever} - can be checked against the record
     * itself, with no state and no second derivation. The frame tag says which pass it belongs to,
     * which is the fact the map-based hook could not carry: a piece the solver skipped this frame left
     * last frame's point behind and a reader could not tell.
     */
    private record QuestionView(int frame, int index, float lever, Vector3f modelPivot,
                                Vector3f transformedPivot, boolean carried, Matrix4f ancestorFrame,
                                Vector3f restDir, Vector3f anchorDirection, Vector3f anchorModelPoint,
                                Vector3f askedDirection, Vector3f askedModelPoint, Vector3f askedPoint,
                                int phase) {

        static QuestionView of(YsmDynamicBoneSolver.ProbeQuestion question) {
            return new QuestionView(question.frame, question.index, question.lever,
                    new Vector3f(question.modelPivot), new Vector3f(question.transformedPivot),
                    question.carried, new Matrix4f(question.ancestorFrame),
                    new Vector3f(question.restDir), new Vector3f(question.anchorDirection),
                    new Vector3f(question.anchorModelPoint), new Vector3f(question.askedDirection),
                    new Vector3f(question.askedModelPoint), new Vector3f(question.askedPoint),
                    question.phase);
        }

        /** {@code |askedModelPoint - (modelPivot + askedDirection*lever)|}: the invariant, solver frame. */
        float askedSphereError() {
            return askedModelPoint.distance(new Vector3f(askedDirection).mul(lever).add(modelPivot));
        }

        /** The same for the point the call started with, before any volume pushed it. */
        float anchorSphereError() {
            return anchorModelPoint.distance(
                    new Vector3f(anchorDirection).mul(lever).add(modelPivot));
        }

        /**
         * The invariant in the volumes' frame: the point they were handed against the carried pivot
         * plus the carried direction. A separate reading from the one above, because the two frames are
         * two different points and a record that mixed them would pass one and fail the other - which is
         * exactly what the first version of this record did.
         */
        float carriedSphereError() {
            Vector3f expected = new Vector3f(askedDirection).mul(lever).add(modelPivot);
            if (carried) {
                ancestorFrame.transformPosition(expected);
            }
            return askedPoint.distance(expected);
        }

        /** How far the point was asked from the pivot it was built about - must be {@code lever}. */
        float askedRadius() {
            return askedModelPoint.distance(modelPivot);
        }

        /** How far the point the volumes were handed is from the pivot the volumes were handed. */
        float carriedRadius() {
            return askedPoint.distance(carried ? transformedPivot : modelPivot);
        }
    }

    /** The frame path's questions, by segment index, for one pass. */
    private static Map<Integer, QuestionView> questionsOfPass(int frame) {
        Map<Integer, QuestionView> out = new LinkedHashMap<>();
        for (YsmDynamicBoneSolver.ProbeQuestion question : YsmDynamicBoneSolver.PROBE_QUESTIONS) {
            if (question.frame == frame) {
                out.put(question.index, QuestionView.of(question));
            }
        }
        return out;
    }

    /**
     * The gap and the terms it is made of, on one piece of one pass at one frame.
     *
     * <p>Round 9 established that the remaining tested-vs-drawn gap is not a radius problem: the worst
     * gaps sit on the tail chain and the near-exact anchor control still sits at two tenths of a block.
     * A distance is not a cause, so this carries one number per candidate cause, each of which is zero
     * when that candidate is not the cause and the size of the effect when it is:
     *
     * <ul>
     *   <li>{@code anchorTerm} - the mesh turns the piece about its contact anchor and the solver about
     *       its bind pivot; this is the distance between those two points in the drawn frame.</li>
     *   <li>{@code leverRatio} - the radius the deformation actually draws against the bind-space lever
     *       the solver integrates.</li>
     *   <li>{@code orderTerm} - the composition order itself: `parent x own` against `own x parent`,
     *       both applied to the bind centre and both then deformed. Zero exactly when the order cannot
     *       matter for this piece, which is every root piece.</li>
     *   <li>{@code commutationDeg}, {@code bindVsSolverDeg} - how far the solver's model-space swing is
     *       from the mesh's bind-space swing: zero exactly when the two are the same motion.</li>
     *   <li>{@code pivotVsDeformation} - the pivot the volumes were handed against the probe's own build
     *       of the same frame's deformation: a frame mismatch, and nothing else.</li>
     *   <li>{@code stateCentreVsDrawn} - the solver's whole formulation, `D(pivot) + direction*lever`,
     *       against the drawn centre.</li>
     *   <li>{@code askedVsStateCentre} - how far the collision response moved the piece after the
     *       question was posed, i.e. how much of the gap is the answer rather than the question.</li>
     *   <li>{@code deltaAfterError} - the size of the error the probe does NOT make: the delta applied
     *       to an already-deformed point (rounds 2-5's order) against the mesh's own.</li>
     * </ul>
     *
     * <p>Quiet by construction: this runs for every piece on every measured frame and is kept only
     * where it is a piece's own worst, so a print here would be thousands of lines of stdout that
     * Gradle swallows anyway.
     */
    private static final class GapTerms {
        final String piece;
        final int frame;
        final float gap;
        final float anchorTerm;
        final float leverRatio;
        final float spanVsLever;
        final float orderTerm;
        final float commutationDeg;
        final float bindVsSolverDeg;
        final float pivotVsDeformation;
        final float stateCentreVsDrawn;
        final float askedVsStateCentre;
        final float swingVsStateDeg;
        final float deltaAfterError;
        final boolean carried;

        GapTerms(String piece, int frame, float gap, float anchorTerm, float leverRatio,
                 float spanVsLever, float orderTerm, float commutationDeg, float bindVsSolverDeg,
                 float pivotVsDeformation, float stateCentreVsDrawn, float askedVsStateCentre,
                 float swingVsStateDeg, float deltaAfterError, boolean carried) {
            this.piece = piece;
            this.frame = frame;
            this.gap = gap;
            this.anchorTerm = anchorTerm;
            this.leverRatio = leverRatio;
            this.spanVsLever = spanVsLever;
            this.orderTerm = orderTerm;
            this.commutationDeg = commutationDeg;
            this.bindVsSolverDeg = bindVsSolverDeg;
            this.pivotVsDeformation = pivotVsDeformation;
            this.stateCentreVsDrawn = stateCentreVsDrawn;
            this.askedVsStateCentre = askedVsStateCentre;
            this.swingVsStateDeg = swingVsStateDeg;
            this.deltaAfterError = deltaAfterError;
            this.carried = carried;
        }

        String row() {
            return "| `" + this.piece + "` | " + this.frame + " | " + fmt(this.gap) + " | "
                    + fmt(this.anchorTerm) + " | " + fmt(this.leverRatio) + " | "
                    + fmt(this.spanVsLever) + " | " + fmt(this.orderTerm) + " | "
                    + fmt(this.commutationDeg) + " | " + fmt(this.bindVsSolverDeg) + " | "
                    + fmt(this.pivotVsDeformation) + " | " + fmt(this.stateCentreVsDrawn) + " | "
                    + fmt(this.askedVsStateCentre) + " | " + fmt(this.swingVsStateDeg) + " | "
                    + fmt(this.deltaAfterError) + " | " + (this.carried ? "yes" : "no") + " |\n";
        }
    }

    /** {@link GapTerms} for one piece of the `after` arm, measured where the question was asked. */
    private static GapTerms gapTermsOf(YsmLegSkirtCollisionProbeTest.Rig rig,
                                       YsmMeshSecondaryMotion.State state,
                                       YsmMeshSecondaryMotion.PoseSource pose, int index,
                                       Map<Integer, QuestionView> questions) {
        QuestionView question = questions.get(index);
        if (question == null) {
            return null;
        }
        YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
        OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
        Vector3f pivotModel = YsmMeshSecondaryMotion.transformPoint(deformation, piece.bindPivot,
                new Vector3f());
        Vector3f anchorModel = YsmMeshSecondaryMotion.transformPoint(deformation, piece.bindAnchor,
                new Vector3f());
        Vector3f centreModel = YsmMeshSecondaryMotion.transformPoint(deformation,
                new Vector3f(piece.bindPivot).add(piece.bindRest), new Vector3f());
        Matrix4f delta = rig.publishedDelta(state, index);
        Vector3f drawn = rig.drawnCentre(piece, deformation, delta);
        Vector3f asked = question.askedPoint();
        float gap = asked.distance(drawn);
        float anchorTerm = anchorModel.distance(pivotModel);
        float drawnLever = centreModel.distance(pivotModel);
        float spanVsLever = centreModel.distance(anchorModel) / Math.max(1.0E-6F, piece.lever);
        // The composition order, both ways, on the same bind point and under the same deformation.
        // `delta` is the composed slot the render path is handed (`parent x own`); the piece's own
        // delta is what is left when the parent's is divided out.
        Vector3f bindCentre = new Vector3f(piece.bindPivot).add(piece.bindRest);
        float orderTerm = 0.0F;
        if (piece.parent >= 0) {
            Matrix4f parent = rig.publishedDelta(state, piece.parent);
            Matrix4f own = new Matrix4f(parent).invert().mul(delta);
            Vector3f meshOrder = YsmMeshSecondaryMotion.transformPoint(deformation,
                    new Vector3f(bindCentre).mulPosition(own).mulPosition(parent), new Vector3f());
            Vector3f otherOrder = YsmMeshSecondaryMotion.transformPoint(deformation,
                    new Vector3f(bindCentre).mulPosition(parent).mulPosition(own), new Vector3f());
            orderTerm = meshOrder.distance(otherOrder);
        }
        Quaternionf solverSwing = new Quaternionf();
        YsmDynamicBoneSolver.rotationFromTo(solverSwing, question.restDir(),
                state.states[index].direction);
        Quaternionf deformationRotation = new Quaternionf();
        YsmMeshSecondaryMotion.rotationOf(deformation, deformationRotation);
        Quaternionf bindSwing = new Quaternionf();
        YsmMeshSecondaryMotion.toLocal(bindSwing, new Quaternionf(solverSwing), deformationRotation);
        Quaternionf commutator = new Quaternionf(solverSwing).mul(deformationRotation)
                .mul(new Quaternionf(solverSwing).conjugate())
                .mul(new Quaternionf(deformationRotation).conjugate());
        float commutationDeg = (float) Math.toDegrees(
                2.0 * Math.acos(Math.min(1.0, Math.abs(commutator.w))));
        float bindVsSolverDeg = (float) Math.toDegrees(2.0 * Math.acos(Math.min(1.0,
                Math.abs(new Quaternionf(solverSwing).dot(bindSwing)))));
        float pivotVsDeformation = question.modelPivot().distance(pivotModel);
        Vector3f stateCentre = new Vector3f(state.states[index].direction).mul(piece.lever)
                .add(pivotModel);
        Vector3f stateCentreCarried = new Vector3f(stateCentre);
        if (question.carried()) {
            question.ancestorFrame().transformPosition(stateCentreCarried);
        }
        float stateCentreVsDrawn = stateCentreCarried.distance(drawn);
        float askedVsStateCentre = asked.distance(stateCentreCarried);
        float swingVsStateDeg = (float) Math.toDegrees(YsmDynamicBoneSolver.angleBetween(
                question.askedDirection(), state.states[index].direction));
        float deltaAfterError = delta.transformPosition(new Vector3f(centreModel)).distance(drawn);
        return new GapTerms(piece.name, question.frame(), gap, anchorTerm,
                drawnLever / Math.max(1.0E-6F, piece.lever), spanVsLever, orderTerm, commutationDeg,
                bindVsSolverDeg, pivotVsDeformation, stateCentreVsDrawn, askedVsStateCentre,
                swingVsStateDeg, deltaAfterError, question.carried());
    }

    /**
     * The residual, term by term, at each piece's own worst frame.
     *
     * <p>The gate's number is a distance and a distance is not a cause. This is the table that names
     * one: the pieces with the widest gap, read at the frame each one's OWN gap was widest - so the row
     * for `Tail7` describes the frame the reported 0.810 was read on - with every candidate cause
     * beside it. The control is the piece with the smallest `anchorTerm` on the model, which is the
     * piece whose anchor-vs-pivot offset cannot explain anything.
     */
    private static String termsSection(StateRun run) {
        if (run.worstTerms.isEmpty()) {
            return "";
        }
        List<GapTerms> rows = new ArrayList<>(run.worstTerms.values());
        rows.sort(Comparator.comparingDouble((GapTerms terms) -> -terms.gap));
        int shown = Math.min(8, rows.size());
        StringBuilder out = new StringBuilder();
        out.append("\n### The residual, term by term, at each piece's own worst frame (round 10)\n\n")
                .append("`gap` is `|asked - drawn|`, in blocks, on the `after` arm at the frame that "
                        + "piece's own gap was widest on. `anchorTerm` is `|D(bindAnchor) - "
                        + "D(bindPivot)|` - the mesh turns about the first, the solver about the "
                        + "second. `leverRatio` is the drawn pivot-to-centre radius over the bind-space "
                        + "`lever` the solver integrates; `span/lever` is the same for the radius the "
                        + "mesh's own delta turns. `orderTerm` is `parent x own` against `own x parent` "
                        + "applied to the same bind point, deformed: the composition order's own size. "
                        + "`comm` and `Q-Rbind` are the two rotations' disagreement in degrees "
                        + "(`Q^-1 Mr^-1 Q Mr` and the rotations themselves). `pivotVsD` is the pivot the "
                        + "volumes were handed against the probe's own build of that frame. "
                        + "`stateCentre-drawn` is the solver's whole formulation against the drawn "
                        + "centre; `asked-stateCentre` is how much the response moved the piece after "
                        + "the question. `swingVsState` is the asked direction against the direction "
                        + "the state ended the frame with. `deltaAfterError` is the order rounds 2-5 "
                        + "used (`delta` on an already-posed point) against the mesh's own, which is "
                        + "the error this probe does NOT make.\n\n")
                .append("| piece | frame | gap | anchorTerm | leverRatio | span/lever | orderTerm "
                        + "| comm deg | Q-Rbind deg | pivotVsD | stateCentre-drawn | "
                        + "asked-stateCentre | swingVsState deg | deltaAfterError | chained |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < shown; i++) {
            out.append(rows.get(i).row());
        }
        GapTerms control = run.smallestAnchorTerms;
        if (control != null) {
            boolean controlAlreadyShown = false;
            for (int i = 0; i < shown; i++) {
                if (rows.get(i).piece.equals(control.piece)) {
                    controlAlreadyShown = true;
                }
            }
            if (!controlAlreadyShown) {
                out.append(control.row());
            }
            out.append("\nControl piece (smallest `anchorTerm` on this model): `")
                    .append(run.smallestAnchorPiece).append("`, ").append(fmt(run.smallestAnchor))
                    .append(" blocks of anchor offset, frame ").append(run.smallestAnchorFrame)
                    .append(", gap ").append(fmt(control.gap))
                    .append("; leverRatio ").append(fmt(control.leverRatio))
                    .append(", orderTerm ").append(fmt(control.orderTerm))
                    .append(", comm ").append(fmt(control.commutationDeg))
                    .append(" deg, stateCentre-drawn ").append(fmt(control.stateCentreVsDrawn))
                    .append(", swingVsState ").append(fmt(control.swingVsStateDeg)).append(" deg.\n\n");
        }
        return out.toString();
    }

    /** The first measured frame of one drive, one row per piece and arm. */
    private static String diagnosticSection(StateRun run, YsmLegSkirtCollisionProbeTest.Rig rig) {
        if (run.diagnostic.length() == 0) {
            return "\n### The question, term by term\n\n(no frame measured)\n\n";
        }
        return "\n### The question, term by term\n\n"
                + "One row per piece per arm, on the first measured frame. Every column is measured at "
                + "the moment the question was asked, from the record the solver built - not "
                + "reconstructed from the state afterwards, which is what every earlier table in this "
                + "line of work did and where its numbers came from. `asked radius / lever` is "
                + "`|asked - pivot|` against the piece's own lever: it must agree to the last bit, "
                + "because the point IS `pivot + direction*lever`. `asked-pivotModel` and "
                + "`asked-anchorModel` are the same point measured against the probe's own build of the "
                + "pivot and the anchor for the same frame. `stateCentre-drawn` is the solver's own "
                + "formulation - `D(pivot) + direction*lever` - against the drawn centre of mass, in the "
                + "same frame, and it is the gap the fix is about. `asked-stateCentre` is how far the "
                + "collision response moved the piece after the question. `radius/anchor` are the two "
                + "radius terms, blocks. `commutation` is the angle of `Q^-1 Mr^-1 Q Mr`, zero exactly "
                + "when the solver's model-space swing and the mesh's bind-space swing are the same "
                + "motion; `|Q - R_bind|` is the angle between the two rotations themselves.\n\n"
                + "| piece | arm | asked vs drawn | asked radius / lever | asked-pivotModel "
                + "| asked-anchorModel | stateCentre-drawn | asked-stateCentre | pivotVsDeformation "
                + "| swingVsState deg | radius / anchor | restVsAsked deg | commutation deg "
                + "| `Q - R_bind|` deg | stateCentre-anchor | restLength / centreFromPivot "
                + "| deformation column norms | deltaOnCentre | order: anchorDrift / radiusDrift "
                + "/ drawnRadius vs lever |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n"
                + run.diagnostic;
    }

    /**
     * The instrument's own assertion, on the numbers the run measured rather than on a plan.
     *
     * <p>If the harness had gone blind again - no question recorded, or a question recorded for a
     * different frame - this is what says so, instead of a table of zeros.
     */
    private static void assertInstrumentMeasured(StateRun run) {
        assertTrue(run.recordedQuestions >= run.pieces,
                "the frame path must record a question for every piece on the measured frame: "
                        + run.recordedQuestions + " of " + run.pieces);
        assertTrue(run.instrumentChecks > 0,
                "the instrument must have checked the questions it measured");
    }

    /**
     * The one thing the recorder cannot check for itself: that the point it is comparing really is the
     * point the solver asked about, on the frame being measured.
     *
     * <p>Five readings, each of which fails on its own:
     *
     * <ol>
     *   <li>a record exists for the piece on the frame just simulated - not a stale one;</li>
     *   <li>the record's own sphere invariant holds exactly;</li>
     *   <li>the record's pivot is the pivot the probe builds for the same frame from the deformation
     *       (which catches a frame or deformation mismatch rather than a solver fault);</li>
     *   <li>the record's radius is the lever and its direction is a unit vector;</li>
     *   <li>the piece is not carrying both "no question" and "asked one earlier".</li>
     * </ol>
     */
    private static void assertInstrument(YsmLegSkirtCollisionProbeTest.Rig rig,
                                         YsmMeshSecondaryMotion.State state,
                                         YsmMeshSecondaryMotion.PoseSource pose,
                                         Map<Integer, QuestionView> asked, int frame, String arm) {
        int checked = 0;
        for (YsmLegSkirtCollisionProbeTest.Segment piece : rig.segments) {
            QuestionView question = asked.get(piece.index);
            if (question == null) {
                int last = YsmDynamicBoneSolver.probeFrameOf(piece.index);
                assertTrue(last < frame, "`" + piece.name + "` has no question on frame " + frame
                        + " but the frame path recorded one for it on frame " + last + " (" + arm
                        + "): the record the measurement reads is not this frame's");
                continue;
            }
            checked++;
            assertEquals(frame, question.frame(), "`" + piece.name + "` was asked about on another pass ("
                    + arm + ")");
            assertZero(question.askedSphereError(), "the sphere invariant of `" + piece.name
                    + "`'s asked point (asked " + point(question.askedModelPoint()) + " pivot "
                    + point(question.modelPivot()) + " direction " + point(question.askedDirection())
                    + " lever " + fmt(question.lever()) + ", " + arm + ")");
            assertZero(question.carriedSphereError(), "the sphere invariant of `" + piece.name
                    + "`'s point in the volumes' frame (asked " + point(question.askedPoint())
                    + " carried pivot " + point(question.carried() ? question.transformedPivot()
                    : question.modelPivot()) + ", " + arm + ")");
            assertZero(question.anchorSphereError(), "the sphere invariant of `" + piece.name
                    + "`'s anchor point (" + arm + ")");
            assertEquals(question.lever(), question.askedRadius(), 1.0E-4F,
                    "`" + piece.name + "` was asked about a point that is not `lever` from its pivot ("
                            + arm + ")");
            assertEquals(question.lever(), question.carriedRadius(), 1.0E-4F,
                    "the volumes were handed a point that is not `lever` from the pivot they were "
                            + "handed, on `" + piece.name + "` (" + arm + ")");
            assertEquals(1.0F, question.askedDirection().length(), 1.0E-4F,
                    "the direction `" + piece.name + "` was asked about is not a unit vector (" + arm + ")");
            OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
            Vector3f pivotModel = YsmMeshSecondaryMotion.transformPoint(deformation, piece.bindPivot,
                    new Vector3f());
            assertZero(pivotModel.distance(question.modelPivot()), "`" + piece.name + "`'s recorded "
                    + "model pivot against the probe's own build of the same frame's deformation ("
                    + arm + ")");
            // ------------------------------------------------------------------
            // R6: the mesh's delta ORDER, pinned by the root-piece invariant.
            //
            // A root piece has no ancestor, so its published delta IS its own delta and the mesh draws
            // it as `deformation x delta x vertex`. Two consequences the pipeline cannot avoid, and
            // neither of them mentions the solver or the collision question:
            //
            //   1. `deformation x buildSegmentDelta(bindAnchor, ...)` fixes the DRAWN anchor, because
            //      `buildSegmentDelta` is `T(bindAnchor) x R x T(-bindAnchor)`;
            //   2. the drawn centre is `|M u|` from the drawn pivot, `u` the unit bind rest and `M` the
            //      deformation's linear part - the map's own scale, not `lever`.
            //
            // The opposite order - `delta(deformation(x))` - fails both, by `(R - I) t` for the
            // model-space offset `t` between the bind origin and the model origin. That is R5-1's
            // frame error, and this is the assertion that keeps it from coming back, on a quantity -
            // `|M u|` - that is built from the deformation alone.
            // ------------------------------------------------------------------
            if (piece.parent < 0) {
                Matrix4f published = rig.publishedDelta(state, piece.index);
                Vector3f drawnAnchor = YsmMeshSecondaryMotion.transformPoint(deformation,
                        new Vector3f(piece.bindAnchor), new Vector3f());
                // (1) the drawn anchor is a fixed point of the pipeline: delta fixes the BIND anchor,
                //     and the deformation fixes whatever the delta leaves alone.
                Vector3f anchorProbe = new Vector3f(piece.bindAnchor);
                YsmMeshSecondaryMotion.transformPoint(deformation,
                        published.transformPosition(anchorProbe), anchorProbe);
                float anchorDrift = drawnAnchor.distance(anchorProbe);
                assertTrue(anchorDrift <= 1.0E-5F, "the mesh's delta is applied in BIND space, so on a "
                        + "ROOT piece the drawn anchor is exactly `D(bindAnchor)`; `" + piece.name
                        + "` drifts " + fmt(anchorDrift) + " blocks under the other order (" + arm + ")");
                // (2) the drawn RADIUS the solver is missing. The drawn offset from the pivot is
                //     `D(delta(bindPivot + bindRest)) - D(delta(bindPivot))`, and because the same
                //     linear map produces both points the offset is that map applied to the
                //     delta-turned bind rest vector. Read entirely through the production reader on
                //     production's own matrix, so no second convention is involved: `offsetDirection`
                //     is what the mesh's reader says the offset's direction and length are, and
                //     `drawnOffset` is the offset itself.
                //
                //     This is the quantity R5-2 measured as `|M u|`: with the delta applied first it is
                //     `|M delta(u)|` and it is LONGER than `lever = |u|` by the deformation's own
                //     scale, on every piece of this model. It is what the solver's sphere radius has to
                //     become, and it is why a bind-space `lever` cannot put the tested point on the
                //     drawn one.
                Vector3f drawnPivotBind = published.transformPosition(new Vector3f(piece.bindPivot));
                Vector3f drawnPivot = YsmMeshSecondaryMotion.transformPoint(deformation,
                        drawnPivotBind, new Vector3f());
                Vector3f centreProbe = new Vector3f(piece.bindPivot).add(piece.bindRest);
                YsmMeshSecondaryMotion.transformPoint(deformation,
                        published.transformPosition(centreProbe), centreProbe);
                Vector3f deltaOffset = published.transformDirection(new Vector3f(piece.bindRest));
                Vector3f offsetDirection = YsmMeshSecondaryMotion.transformDirection(deformation,
                        deltaOffset, new Vector3f());
                float drawnOffset = drawnPivot.distance(centreProbe);
                float drawnRadius = offsetDirection.length();
                float radiusDrift = Math.abs(drawnOffset - drawnRadius);
                assertTrue(radiusDrift <= 1.0E-4F, "the drawn pivot-to-centre offset must be the same "
                        + "map applied to both ends, read through the mesh's own reader: `"
                        + piece.name + "` is " + fmt(drawnOffset) + " against " + fmt(drawnRadius)
                        + ", drift " + fmt(radiusDrift) + " (" + arm + ")");
                trace("YSMCOLLISION-ORDER-PROOF `" + piece.name + "` arm=" + arm
                        + " root=" + (piece.parent < 0)
                        + " bindAnchor=" + point(piece.bindAnchor)
                        + " bindPivot=" + point(piece.bindPivot)
                        + " bindRest=" + point(piece.bindRest)
                        + " anchorDrift=" + colon(anchorDrift)
                        + " drawnPivot=" + point(drawnPivot)
                        + " drawnCentre=" + point(centreProbe)
                        + " pivotToCentre=" + colon(drawnOffset)
                        + " drawnRadius=" + colon(drawnRadius)
                        + " radiusDrift=" + colon(radiusDrift)
                        + " lever=|bindRest|=" + colon(piece.bindRest.length())
                        + " deltaOffset=" + point(deltaOffset)
                        + " drawScale=" + colon(drawnRadius
                                / Math.max(1.0E-6F, piece.bindRest.length())));
                // Kept as well as printed, so the diagnostic TABLE can carry it: Gradle on this machine
                // swallows a test's stdout, and the one thing a reader must be able to check for
                // themselves - whether the drawn point is the mesh's own transform - cannot live only
                // in a stream nobody can read. See `diagnose`.
                orderProofs.put(arm + "/" + piece.name, new float[]{
                        anchorDrift, radiusDrift, drawnRadius, piece.bindRest.length()});
            }
        }
        assertEquals(rig.segments.size(), checked,
                "every piece must carry a question on the frame just simulated (" + arm + ")");
    }

    /** The root-piece order proof for one row of the table, or a placeholder for a chained piece. */
    private static String orderProof(String arm, String name) {
        float[] proof = orderProofs.get(arm + "/" + name);
        if (proof == null) {
            return "(chained)";
        }
        return fmt(proof[0]) + " / " + fmt(proof[1]) + " / " + fmt(proof[2]) + " vs "
                + fmt(proof[3]);
    }

    /**
     * The chain of distances from the recorded question to the drawn centre, one term at a time.
     *
     * <p>This is what makes the verdict a number rather than an opinion: the sphere the solver holds
     * the point on is about the pivot the volumes were handed ({@code pivot}), the drawn centre is
     * {@code deformation(delta(bindPivot + bindRest))} - the mesh's own order, see
     * {@code Rig#drawnCentre} - and the two are the same quantity exactly when every term below is
     * zero. A non-zero term names its own cause:
     *
     * <ul>
     *   <li>{@code leverRadius} - is the point the volumes were asked about on a sphere of radius
     *       {@code lever} about the solver's own pivot? (Non-zero means the record is not the
     *       solver's, which is the fault this round exists to end.)</li>
     *   <li>{@code pivotVsDeformation} - is the solver's pivot the deformation's image of the piece's
     *       bind pivot, on the same frame? (Non-zero means a frame or deformation mismatch.)</li>
     *   <li>{@code swingVsState} - the angle between the direction the piece was asked at and the
     *       direction its state carries at the end of the frame, degrees: the collision response and
     *       the swing limit both write the state after the question was posed.</li>
     *   <li>{@code stateCentreVsDrawn} - the distance from the centre of mass the solver's own state
     *       describes, in the solver's own frame, to the centre the mesh draws: the solver's
     *       formulation, measured against the drawn piece.</li>
     *   <li>{@code askedVsStateCentre} - the distance from the point a volume was asked about to that
     *       same state centre: how much the response moved the piece after the question.</li>
     *   <li>{@code radiusTerm}, {@code anchorTerm} - the two named terms of the difference between the
     *       tested and the drawn radius, in the solver's frame.</li>
     * </ul>
     */
    private static String diagnose(YsmLegSkirtCollisionProbeTest.Rig rig,
                                   YsmMeshSecondaryMotion.State state,
                                   YsmMeshSecondaryMotion.PoseSource pose, int index, String arm,
                                   Map<Integer, QuestionView> questions) {
        YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
        QuestionView question = questions.get(index);
        if (question == null) {
            return "| `" + piece.name + "` | " + arm + " | (no question on this pass) |"
                    + " | | | | | | | | |\n";
        }
        OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
        Vector3f pivotModel = YsmMeshSecondaryMotion.transformPoint(deformation, piece.bindPivot,
                new Vector3f());
        Vector3f anchorModel = YsmMeshSecondaryMotion.transformPoint(deformation, piece.bindAnchor,
                new Vector3f());
        Vector3f centreModel = YsmMeshSecondaryMotion.transformPoint(deformation,
                new Vector3f(piece.bindPivot).add(piece.bindRest), new Vector3f());
        Matrix4f delta = rig.publishedDelta(state, index);
        Vector3f drawn = rig.drawnCentre(piece, deformation, delta);
        Vector3f stateCentre = new Vector3f(state.states[index].direction).mul(piece.lever)
                .add(pivotModel);
        // The point the volumes were handed, and the pivot they were handed with: both in the frame the
        // piece is drawn in, so the radius and the distance to the drawn centre are comparable.
        Vector3f asked = question.askedPoint();
        Vector3f askedPivot = question.carried() ? question.transformedPivot() : question.modelPivot();
        Vector3f stateCentreCarried = new Vector3f(stateCentre);
        if (question.carried()) {
            question.ancestorFrame().transformPosition(stateCentreCarried);
        }
        float askedVsDrawn = asked.distance(drawn);
        float leverRadius = asked.distance(askedPivot);
        float pivotVsDeformation = question.modelPivot().distance(pivotModel);
        float swingVsState = (float) Math.toDegrees(
                YsmDynamicBoneSolver.angleBetween(question.askedDirection(), state.states[index].direction));
        float stateCentreVsDrawn = stateCentreCarried.distance(drawn);
        float askedVsStateCentre = asked.distance(stateCentreCarried);
        float radiusTerm = centreModel.distance(anchorModel);
        float anchorTerm = anchorModel.distance(pivotModel);
        float restVsAsked = (float) Math.toDegrees(
                YsmDynamicBoneSolver.angleBetween(question.restDir(), question.askedDirection()));
        // ------------------------------------------------------------------
        // Is the requirement even reachable from this formulation?
        //
        // The mesh turns the piece by `R_bind = Mr^-1 Q Mr` acting on bind-space offsets
        // (`buildSegmentDelta` gets `bindRotation` from `bindSwingOf`), and the solver turns its
        // direction by `Q` in model space. Written for the radius vector r from the pivot to the centre
        // of mass, the two agree for every r exactly when `Q Mr = Mr R_bind`, i.e. when `Q` and the
        // deformation's own rotation COMMUTE. What the measurement sees is what is left when they do
        // not, and the reading below is the size of that non-commutation: the angle of
        // `Q^-1 Mr^-1 Q Mr`, zero exactly when the two rotations share an axis - which is the
        // condition for "the solver's direction" and "the mesh's swing" to be the same motion.
        // `bindVsSolverDeg` is the angle between the mesh's bind-space swing and the solver's
        // model-space swing, the two rotations themselves.
        // ------------------------------------------------------------------
        Quaternionf solverSwing = new Quaternionf();
        YsmDynamicBoneSolver.rotationFromTo(solverSwing, question.restDir(),
                state.states[index].direction);
        Quaternionf deformationRotation = new Quaternionf();
        YsmMeshSecondaryMotion.rotationOf(deformation, deformationRotation);
        Quaternionf bindSwing = new Quaternionf();
        YsmMeshSecondaryMotion.toLocal(bindSwing, new Quaternionf(solverSwing), deformationRotation);
        Quaternionf commutator = new Quaternionf(solverSwing).mul(deformationRotation)
                .mul(new Quaternionf(solverSwing).conjugate())
                .mul(new Quaternionf(deformationRotation).conjugate());
        float commutationDeg = (float) Math.toDegrees(
                2.0 * Math.acos(Math.min(1.0, Math.abs(commutator.w))));
        float bindVsSolverDeg = (float) Math.toDegrees(2.0 * Math.acos(Math.min(1.0,
                Math.abs(new Quaternionf(solverSwing).dot(bindSwing)))));
        // The lever against the geometry it is supposed to be: `lever = |bindRest|` by construction,
        // `|centreModel - pivotModel|` is what the same vector is worth after the deformation, and
        // `|drawnCentre - centreModel|` is what the mesh's own delta does to the centre. If the first
        // two disagree the deformation scales; if the third is large the delta translates the centre as
        // well as turning it, which `T(a) x Q x T(-a)` cannot do unless the centre is not where the
        // derivation puts it.
        float restLength = piece.bindRest.length();
        float centreFromPivot = centreModel.distance(pivotModel);
        float deltaOnCentre = drawn.distance(centreModel);
        // ------------------------------------------------------------------
        // Is the deformation the pose applies a RIGID motion?
        //
        // `transformDirection` returns the matrix's linear part with NO normalisation, and the solver
        // normalises the result - so a deformation with scale turns the lever the solver integrates
        // (`lever = |bindRest|`) into a different length in the frame the piece is drawn in
        // (`|D(p+u) - D(p)|`). If those two differ, "the centre of mass is `lever` from the pivot" and
        // "the centre of mass is where the mesh draws it" are statements about two different lengths, and
        // no choice of pivot or direction can reconcile them. The three column norms below are the
        // measurement: 1.000 each means rigid.
        // ------------------------------------------------------------------
        float columnX = (float) Math.sqrt(deformation.m00 * deformation.m00
                + deformation.m01 * deformation.m01 + deformation.m02 * deformation.m02);
        float columnY = (float) Math.sqrt(deformation.m10 * deformation.m10
                + deformation.m11 * deformation.m11 + deformation.m12 * deformation.m12);
        float columnZ = (float) Math.sqrt(deformation.m20 * deformation.m20
                + deformation.m21 * deformation.m21 + deformation.m22 * deformation.m22);
        float columnDotXY = deformation.m00 * deformation.m10 + deformation.m01 * deformation.m11
                + deformation.m02 * deformation.m12;
        float columnDotXZ = deformation.m00 * deformation.m20 + deformation.m01 * deformation.m21
                + deformation.m02 * deformation.m22;
        float columnDotYZ = deformation.m10 * deformation.m20 + deformation.m11 * deformation.m21
                + deformation.m12 * deformation.m22;
        float rowX = (float) Math.sqrt(deformation.m00 * deformation.m00
                + deformation.m10 * deformation.m10 + deformation.m20 * deformation.m20);
        float rowY = (float) Math.sqrt(deformation.m01 * deformation.m01
                + deformation.m11 * deformation.m11 + deformation.m21 * deformation.m21);
        float rowZ = (float) Math.sqrt(deformation.m02 * deformation.m02
                + deformation.m12 * deformation.m12 + deformation.m22 * deformation.m22);
        // ------------------------------------------------------------------
        // What the anchor-pair fix would actually achieve, computed from this frame.
        //
        // The mesh draws the centre of mass at the anchor plus the swing applied to the model-space
        // span (`anchorModel -> centreModel`), so the formulation the solver would have to hold is
        // exactly
        //
        //     tested = anchorModel + rotate(span, swing)
        //
        // with `span = centreModel - anchorModel` - the radius the piece's own delta turns, in the
        // frame it is drawn in. `gapSpanFix` is that expression against the drawn centre; it is not a
        // prediction of the dynamics (the swing would move too), but it is the geometry the solver
        // would have to be holding, measured on the frame at hand. `spanLength` beside `lever` is the
        // radius the mesh uses against the radius the solver is handed: they are equal only when the
        // deformation is conformal.
        // ------------------------------------------------------------------
        Vector3f span = new Vector3f(centreModel).sub(anchorModel);
        float spanLength = span.length();
        Vector3f spanFixCentre = spanLength > 1.0E-5F
                ? new Vector3f(anchorModel).add(new Vector3f(span).rotate(solverSwing))
                : null;
        float gapSpanFix = spanFixCentre == null ? -1.0F : drawn.distance(spanFixCentre);
        trace("YSMCOLLISION-FRAME `" + piece.name + "`"
                + " columnNorm=" + colon(columnX) + "/" + colon(columnY) + "/" + colon(columnZ)
                + " columnDot=" + colon(columnDotXY) + "/" + colon(columnDotXZ) + "/"
                + colon(columnDotYZ)
                + " rowNorm=" + colon(rowX) + "/" + colon(rowY) + "/" + colon(rowZ)
                + " diag=" + colon(deformation.m00) + "/" + colon(deformation.m11) + "/"
                + colon(deformation.m22)
                + " restLength=" + fmt(restLength) + " centreFromPivot=" + fmt(centreFromPivot)
                + " ratio=" + fmt(centreFromPivot / Math.max(1.0E-6F, restLength))
                + " radiusTerm=" + fmt(radiusTerm) + " lever=" + fmt(piece.lever)
                + " gapSpanFix=" + fmt(gapSpanFix)
                + " spanLength=" + fmt(spanLength)
                + " leverVsSpan=" + fmt(piece.lever / Math.max(1.0E-6F, spanLength)));
        trace("YSMCOLLISION-POINTS `" + piece.name + "` arm=" + arm
                + " pivotModel=" + point(pivotModel) + " anchorModel=" + point(anchorModel)
                + " centreModel=" + point(centreModel) + " stateCentre=" + point(stateCentre)
                + " stateCentreCarried=" + point(stateCentreCarried) + " drawn=" + point(drawn)
                + " asked=" + point(asked)
                + " |stateCentre-drawn|=" + fmt(stateCentre.distance(drawn))
                + " |carried-drawn|=" + fmt(stateCentreCarried.distance(drawn))
                + " |asked-drawn|=" + fmt(askedVsDrawn));
        // ------------------------------------------------------------------
        // What the published delta does, term by term.
        //
        // `state.deltas[index]` is the matrix the render path applies, so this reads it rather than
        // rebuilding it: where the delta sends the anchor (it must be a FIXED POINT - `buildSegmentDelta`
        // is `T(anchor) x R x T(-anchor)`), what its rotation angle is, what its translation is, and
        // where it sends the deformation's own centre of mass. The last two are the ones that say
        // whether "the drawn centre is the deformed centre turned about the anchor" is true at all.
        // ------------------------------------------------------------------
        Vector3f anchorThroughDelta = delta.transformPosition(new Vector3f(anchorModel));
        Vector3f centreThroughDelta = delta.transformPosition(new Vector3f(centreModel));
        Vector3f pivotThroughDelta = delta.transformPosition(new Vector3f(pivotModel));
        float trace = delta.m00() + delta.m11() + delta.m22();
        float deltaDeg = (float) Math.toDegrees(
                Math.acos(Math.max(-1.0F, Math.min(1.0F, (trace - 1.0F) * 0.5F))));
        float translation = (float) Math.sqrt(delta.m30() * delta.m30()
                + delta.m31() * delta.m31() + delta.m32() * delta.m32());
        float deltaColX = (float) Math.sqrt(delta.m00() * delta.m00() + delta.m10() * delta.m10()
                + delta.m20() * delta.m20());
        float deltaColY = (float) Math.sqrt(delta.m01() * delta.m01() + delta.m11() * delta.m11()
                + delta.m21() * delta.m21());
        float deltaColZ = (float) Math.sqrt(delta.m02() * delta.m02() + delta.m12() * delta.m12()
                + delta.m22() * delta.m22());
        float deltaRowX = (float) Math.sqrt(delta.m00() * delta.m00() + delta.m01() * delta.m01()
                + delta.m02() * delta.m02());
        float deltaRowY = (float) Math.sqrt(delta.m10() * delta.m10() + delta.m11() * delta.m11()
                + delta.m12() * delta.m12());
        float deltaRowZ = (float) Math.sqrt(delta.m20() * delta.m20() + delta.m21() * delta.m21()
                + delta.m22() * delta.m22());
        trace("YSMCOLLISION-DELTA `" + piece.name + "` arm=" + arm
                + " deltaAngle=" + fmt(deltaDeg) + " translation=" + fmt(translation)
                + " deltaColumnNorm=" + fmt(deltaColX) + "/" + fmt(deltaColY) + "/" + fmt(deltaColZ)
                + " deltaRowNorm=" + fmt(deltaRowX) + "/" + fmt(deltaRowY) + "/" + fmt(deltaRowZ)
                + " anchorThroughDelta=" + point(anchorThroughDelta)
                + " |anchor-delta(anchor)|=" + fmt(anchorModel.distance(anchorThroughDelta))
                + " centreThroughDelta=" + point(centreThroughDelta)
                + " |centre-delta(centre)|=" + fmt(centreModel.distance(centreThroughDelta))
                + " |delta(centre)-drawn|=" + fmt(centreThroughDelta.distance(drawn))
                + " pivotThroughDelta=" + point(pivotThroughDelta)
                + " |delta(pivot)-drawn|=" + fmt(pivotThroughDelta.distance(drawn))
                + " |delta(pivot)-stateCentreCarried|="
                + fmt(pivotThroughDelta.distance(stateCentreCarried)));
        // ------------------------------------------------------------------
        // R5-1. WHICH SPACE IS THE PUBLISHED DELTA IN?
        //
        // `resolveSegment` builds `jomlDeltas[index]` with `buildSegmentDelta(segment.bindAnchor(),
        // bindRotation, delta)`, and `buildSegmentDelta` is `T(bindAnchor) x R x T(-bindAnchor)` - a
        // transform of BIND space. The mesh draws a part as `deformation x delta x vertex`, so the
        // delta is applied to bind-space vertices BEFORE the deformation carries them into the model
        // frame. The probe's `drawnCentre` applies it AFTER: `delta.transformPosition(D(bindCentre))`.
        // Those two are the same map only when the delta happens to be the identity on the difference
        // between the two origins, and this block measures the difference on the frame at hand. It
        // also rebuilds the delta the way production does, so "the published slot is
        // buildSegmentDelta(bindAnchor, bindRotation)" stops being an assumption.
        // ------------------------------------------------------------------
        Quaternionf rebuiltRotation = new Quaternionf();
        YsmMeshSecondaryMotion.bindSwingOf(deformation, solverSwing, rebuiltRotation);
        Matrix4f rebuiltDelta = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(piece.bindAnchor, rebuiltRotation, rebuiltDelta);
        Vector3f bindAnchor = piece.bindAnchor;
        Vector3f bindAnchorThroughDelta = delta.transformPosition(new Vector3f(bindAnchor));
        float bindAnchorFix = bindAnchor.distance(bindAnchorThroughDelta);
        float rebuiltAnchorFix = bindAnchor.distance(rebuiltDelta.transformPosition(
                new Vector3f(bindAnchor)));
        float rebuiltVsPublished = maxAbsDifference(rebuiltDelta, delta);
        // The delta the mesh actually applies, carried into the model frame by conjugation with the
        // deformation that carries this joint's bind geometry into it - `D x A x D^-1`, applied to a
        // posed point. This is the only form in which a BIND-space delta may be applied to a
        // MODEL-space point, and it is what the frame path does not do today.
        OpenMatrix4f modelSpaceDelta = conjugatedIntoModelSpace(deformation, delta);
        Vector3f oldWayCentre = delta.transformPosition(new Vector3f(centreModel));
        Vector3f modelSpaceDrawn = YsmMeshSecondaryMotion.transformPoint(modelSpaceDelta,
                new Vector3f(piece.bindPivot).add(piece.bindRest), new Vector3f());
        Vector3f anchorInModelSpace = YsmMeshSecondaryMotion.transformPoint(modelSpaceDelta,
                new Vector3f(bindAnchor), new Vector3f());
        trace("YSMCOLLISION-SPACE `" + piece.name + "` arm=" + arm
                + " root=" + (piece.parent < 0)
                + " bindAnchor=" + point(bindAnchor)
                + " bindAnchorThroughDelta=" + point(bindAnchorThroughDelta)
                + " bindAnchorFix=" + colon(bindAnchorFix)
                + " rebuiltAnchorFix=" + colon(rebuiltAnchorFix)
                + " rebuiltVsPublished=" + colon(rebuiltVsPublished)
                + " |anchorModel-delta(anchorModel)|="
                + colon(anchorModel.distance(anchorThroughDelta))
                + " |anchorModel-modelSpaceDelta(bindAnchor)|="
                + colon(anchorInModelSpace.distance(anchorModel))
                + " oldWayDrawn=" + point(oldWayCentre)
                + " modelSpaceDrawn=" + point(modelSpaceDrawn)
                + " |oldWay-modelSpace|=" + colon(oldWayCentre.distance(modelSpaceDrawn)));
        trace("YSMCOLLISION-SPACE-DRAWN `" + piece.name + "` arm=" + arm
                + " |drawn-asked|=" + colon(drawn.distance(asked))
                + " |modelSpaceDrawn-asked|=" + colon(modelSpaceDrawn.distance(asked))
                + " |modelSpaceDrawn-stateCentreCarried|="
                + colon(modelSpaceDrawn.distance(stateCentreCarried))
                + " |oldWayDrawn-anchorModel|=" + colon(oldWayCentre.distance(anchorModel))
                + " |modelSpaceDrawn-anchorModel|=" + colon(modelSpaceDrawn.distance(anchorModel))
                + " |drawn-anchorModel|=" + colon(drawn.distance(anchorModel)));
        // The deformation, element by element. The column norms above say this map is not rigid; a
        // norm says how much and not which way, and the direction is what decides whether the
        // deformation can be corrected for at all. Printed raw so the reading is not another derived
        // quantity that can disagree with its own source.
        trace("YSMCOLLISION-DEFORMATION `" + piece.name + "` arm=" + arm
                + " m00=" + colon(deformation.m00) + " m01=" + colon(deformation.m01)
                + " m02=" + colon(deformation.m02) + " m03=" + colon(deformation.m03)
                + " m10=" + colon(deformation.m10) + " m11=" + colon(deformation.m11)
                + " m12=" + colon(deformation.m12) + " m13=" + colon(deformation.m13)
                + " m20=" + colon(deformation.m20) + " m21=" + colon(deformation.m21)
                + " m22=" + colon(deformation.m22) + " m23=" + colon(deformation.m23)
                + " m30=" + colon(deformation.m30) + " m31=" + colon(deformation.m31)
                + " m32=" + colon(deformation.m32) + " m33=" + colon(deformation.m33)
                + " bindRest=" + point(piece.bindRest)
                + " restLength=" + colon(piece.bindRest.length())
                + " centreFromPivot=" + colon(centreModel.distance(pivotModel))
                + " ratio=" + colon(centreModel.distance(pivotModel)
                        / Math.max(1.0E-6F, piece.bindRest.length())));
        // The deformation's 3x3, its Gram matrix, its determinant, and the map applied to the bind
        // rest vector and to its unit form. Printed raw: every claim about "is this rigid" has to be
        // checkable from this one line, because two rounds have now produced column norms that a 3x3
        // written out by hand does not reproduce - and a norm that disagrees with its own matrix is
        // worse than no norm, since it is the number the next decision gets made on.
        {
            float[][] mm = {
                    {deformation.m00, deformation.m01, deformation.m02},
                    {deformation.m10, deformation.m11, deformation.m12},
                    {deformation.m20, deformation.m21, deformation.m22}};
            float[] gram = new float[9];
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    float sum = 0.0F;
                    for (int k = 0; k < 3; k++) {
                        sum += mm[r][k] * mm[c][k];
                    }
                    gram[r * 3 + c] = sum;
                }
            }
            float det = mm[0][0] * (mm[1][1] * mm[2][2] - mm[1][2] * mm[2][1])
                    - mm[0][1] * (mm[1][0] * mm[2][2] - mm[1][2] * mm[2][0])
                    + mm[0][2] * (mm[1][0] * mm[2][1] - mm[1][1] * mm[2][0]);
            Vector3f restImage = YsmMeshSecondaryMotion.transformDirection(deformation,
                    new Vector3f(piece.bindRest), new Vector3f());
            Vector3f unitImage = YsmMeshSecondaryMotion.transformDirection(deformation,
                    new Vector3f(piece.bindRest).normalize(new Vector3f()), new Vector3f());
            trace("YSMCOLLISION-RIGIDITY `" + piece.name + "` arm=" + arm
                    + " gram=" + colon(gram[0]) + "," + colon(gram[1]) + "," + colon(gram[2])
                    + "," + colon(gram[3]) + "," + colon(gram[4]) + "," + colon(gram[5])
                    + "," + colon(gram[6]) + "," + colon(gram[7]) + "," + colon(gram[8])
                    + " det=" + colon(det)
                    + " restImage=" + point(restImage)
                    + " restImageLength=" + colon(restImage.length())
                    + " unitImageLength=" + colon(unitImage.length())
                    + " spanFromPivot=" + colon(centreModel.distance(pivotModel)));
        }
        // ------------------------------------------------------------------
        // R6-1. THE ORDER, settled by the root-piece invariant.
        //
        // R5-3 left the mesh's delta order open between `deformation(delta(centre))` and
        // `delta(deformation(centre))`, and named the only measurement that can decide it: for a ROOT
        // piece there is no ancestor, so the drawn anchor must be exactly `D(bindAnchor)` and the drawn
        // centre must be exactly `|M u|` from the drawn pivot. Both are statements about the pipeline
        // and neither involves the solver, the collision question or `asked` - which is what makes a
        // favourable reading here informative where R5-1's conjugation experiment was not.
        //
        // Printed for every piece and both orders, so "which order is the mesh's" is readable from this
        // line rather than inferred from a distance to a quantity under test:
        //
        //   `bindOrder`  = |D(delta(bindAnchor)) - D(bindAnchor)|              -> 0 on a root piece
        //   `modelOrder` = |delta(D(bindAnchor)) - D(bindAnchor)|              -> `(R - I) t`, non-zero
        //   `bindRadius` / `|M u|`                                             -> equal on a root piece
        //   `modelRadius` = |delta(D(centre)) - D(bindAnchor)|                 -> the wrong order's value
        //
        // `publishedAnchorFix` is the same check on the published slot rather than on a rebuild, which
        // is what makes it a statement about the matrix the render path actually applies.
        // ------------------------------------------------------------------
        Vector3f drawnAnchor = YsmMeshSecondaryMotion.transformPoint(deformation,
                new Vector3f(piece.bindAnchor), new Vector3f());
        Vector3f bindOrderCentre = new Vector3f(piece.bindPivot).add(piece.bindRest);
        delta.transformPosition(bindOrderCentre);
        YsmMeshSecondaryMotion.transformPoint(deformation, bindOrderCentre, bindOrderCentre);
        Vector3f bindAnchorOrder = new Vector3f(piece.bindAnchor);
        delta.transformPosition(bindAnchorOrder);
        YsmMeshSecondaryMotion.transformPoint(deformation, bindAnchorOrder, bindAnchorOrder);
        Vector3f modelOrderAnchor = delta.transformPosition(new Vector3f(drawnAnchor));
        Vector3f modelOrderCentre = delta.transformPosition(new Vector3f(centreModel));
        Vector3f unitRest = new Vector3f(piece.bindRest).normalize(new Vector3f());
        float drawnRadius = YsmMeshSecondaryMotion.transformDirection(deformation, unitRest,
                new Vector3f()).length();
        trace("YSMCOLLISION-ORDER `" + piece.name + "` arm=" + arm
                + " root=" + (piece.parent < 0)
                + " bindAnchor=" + point(piece.bindAnchor)
                + " drawnAnchor=" + point(drawnAnchor)
                + " bindOrderAnchor=" + point(bindAnchorOrder)
                + " bindOrder=" + colon(drawnAnchor.distance(bindAnchorOrder))
                + " modelOrder=" + colon(drawnAnchor.distance(modelOrderAnchor))
                + " publishedAnchorFix=" + colon(piece.bindAnchor.distance(
                        delta.transformPosition(new Vector3f(piece.bindAnchor))))
                + " bindRadius=" + colon(drawnAnchor.distance(bindOrderCentre))
                + " modelRadius=" + colon(drawnAnchor.distance(modelOrderCentre))
                + " |Mu|=" + colon(drawnRadius)
                + " radiusDiff=" + colon(Math.abs(drawnAnchor.distance(bindOrderCentre) - drawnRadius))
                + " drawnCentre=" + point(bindOrderCentre)
                + " drawnRadiusFromPivot=" + colon(
                        YsmMeshSecondaryMotion.transformPoint(deformation,
                                new Vector3f(piece.bindPivot), new Vector3f())
                                .distance(bindOrderCentre)));
        trace("YSMCOLLISION-QUESTION `" + piece.name + "` arm=" + arm
                + " frame=" + question.frame() + " phase=" + question.phase()
                + " gap=" + fmt(askedVsDrawn)
                + " leverRadius=" + fmt(leverRadius) + " lever=" + fmt(piece.lever)
                + " |asked-modelPivot|=" + fmt(asked.distance(question.modelPivot()))
                + " |asked-anchorModel|=" + fmt(asked.distance(anchorModel))
                + " |asked-drawn|=" + fmt(askedVsDrawn)
                + " |stateCentreCarried-drawn|=" + fmt(stateCentreVsDrawn)
                + " |asked-stateCentre|=" + fmt(askedVsStateCentre)
                + " pivotVsDeformation=" + fmt(pivotVsDeformation)
                + " swingVsStateDeg=" + fmt(swingVsState)
                + " restVsAskedDeg=" + fmt(restVsAsked)
                + " commutationDeg=" + fmt(commutationDeg)
                + " bindVsSolverDeg=" + fmt(bindVsSolverDeg)
                + " |stateCentre-anchor|=" + fmt(stateCentre.distance(anchorModel))
                + " |drawnCentre-anchor|=" + fmt(drawn.distance(anchorModel))
                + " radiusTerm=" + fmt(radiusTerm) + " anchorTerm=" + fmt(anchorTerm)
                + " restLength=" + fmt(restLength) + " centreFromPivot=" + fmt(centreFromPivot)
                + " deltaOnCentre=" + fmt(deltaOnCentre)
                + " columnNorm=" + fmt(columnX) + "/" + fmt(columnY) + "/" + fmt(columnZ)
                + " carried=" + question.carried());
        return "| `" + piece.name + "` | " + arm + " | " + fmt(askedVsDrawn)
                + " | " + fmt(leverRadius) + " / " + fmt(piece.lever)
                + " | " + fmt(asked.distance(question.modelPivot()))
                + " | " + fmt(asked.distance(anchorModel))
                + " | " + fmt(stateCentreVsDrawn)
                + " | " + fmt(askedVsStateCentre)
                + " | " + fmt(pivotVsDeformation)
                + " | " + fmt(swingVsState)
                + " | " + fmt(radiusTerm) + " / " + fmt(anchorTerm)
                + " | " + fmt(restVsAsked)
                + " | " + fmt(commutationDeg)
                + " | " + fmt(bindVsSolverDeg)
                + " | " + fmt(stateCentre.distance(anchorModel))
                + " | " + fmt(restLength) + " / " + fmt(centreFromPivot)
                + " | " + fmt(columnX) + " " + fmt(columnY) + " " + fmt(columnZ)
                + " | " + fmt(deltaOnCentre)
                + " | " + orderProof(arm, piece.name)
                + " |\n";
    }

    // ------------------------------------------------------------------
    // The measurement itself
    // ------------------------------------------------------------------
    /** One state of the world to drive. */
    private record Drive(String name, String clip, float speed) {
        static Drive synthetic(String name) {
            return new Drive(name, null, SPRINT_SPEED);
        }
    }

    /** What one arm of one state measured. */
    private static final class Arm {
        /** Per piece: {summed gap, max gap, frames measured, max gap on the rest-direction form}. */
        final Map<String, float[]> gap = new LinkedHashMap<>();
        /** Per piece: the largest distance of the UNcarried tested point from the drawn one. */
        final Map<String, Float> raw = new LinkedHashMap<>();
        /** Per piece: {summed, max, frames} of the distance from the point a volume was asked about. */
        final Map<String, float[]> questionedGap = new LinkedHashMap<>();
        /** Per piece: the largest value of the BRIEF's own tested-point expression, see StateRun. */
        final Map<String, Float> restGap = new LinkedHashMap<>();
        /** Per piece: the point the volumes were asked about at the last volume of the last frame. */
        final Map<String, Vector3f> asked = new LinkedHashMap<>();
        /** Per piece: the largest collision turn the solver reported, blocks. */
        final Map<String, Float> contact = new LinkedHashMap<>();
        /** Per piece: how many frames its whole collider set was skipped as containing it. */
        final Map<String, Integer> skippedFrames = new LinkedHashMap<>();
        /** Per piece: the drawn centre of mass at the last measured frame. */
        final Map<String, Vector3f> lastDrawn = new LinkedHashMap<>();
        /** Per piece: the largest swing the solver granted, degrees. */
        final Map<String, Float> swing = new LinkedHashMap<>();
        int frames;
        /**
         * How many times the solver asked this arm's collider set {@code count()}, and what the
         * largest answer was. Diagnostic only: it is the reading that separates "the solver never
         * reached the collision step" from "it reached it and skipped every volume".
         */
        int countCalls;
        int maxCount;
        /** How many times the recorder was handed a volume's question at all, both kinds. */
        int skipCalls;
        int resolveCalls;
        /**
         * Per piece: {deformation row 0..3, pivot xyz, restDir xyz} - everything the pass reads
         * BEFORE the solver is handed anything. See {@link #snapshot}.
         */
        final Map<String, float[]> inputs = new LinkedHashMap<>();
        /**
         * Per piece: {direction xyz, scratch quaternion xyzw, delta translation xyz, delta column
         * norms xyz, lastAngle, lastContact}. Everything the pass produced, read from the state the
         * pass wrote. See {@link #snapshot}.
         */
        final Map<String, float[]> outputs = new LinkedHashMap<>();

        float worst(String name) {
            float[] acc = gap.get(name);
            return acc == null ? -1.0F : acc[1];
        }

        /** A copy of the piece's published delta, as a value. See {@link #snapshot}. */
        Matrix4f deltaOf(YsmMeshSecondaryMotion.State state, int index) {
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

        /** The same, on the brief's own expression: {@code deformation(bindPivot) + restDir*lever}. */
        float worstRest(String name) {
            float[] acc = gap.get(name);
            return acc == null ? -1.0F : acc[3];
        }

        float mean(String name) {
            float[] acc = gap.get(name);
            return acc == null || acc[2] <= 0.0F ? -1.0F : acc[0] / acc[2];
        }
    }

    /** Both arms of one state, and the differences between them. */
    private static final class StateRun {
        final String name;
        final Arm before = new Arm();
        final Arm after = new Arm();
        /**
         * The after arm run a second time from a state of its own, on the same frames and the same
         * pose. `after` against `replay` must agree to the last bit; anything else is `simulate`
         * carrying state across passes, and it would make the untouched-drift red line a measurement
         * of that rather than of the collision frame.
         */
        final Arm replay = new Arm();
        /** The same again, run immediately after {@link #replay} with nothing in between. */
        final Arm replay2 = new Arm();
        /**
         * Two passes that must agree, born at the first measured frame and advanced together: the
         * controlled experiment for "does a pass contaminate the next one". Both are handed the real
         * colliders, the same pose and the same frame; `pairB` follows `pairA` by one pass.
         */
        final Arm pairA = new Arm();
        final Arm pairB = new Arm();
        float worstPairDrift;
        String worstPairPiece;
        int pairFirstFrame;
        /**
         * The same pair with a whole history behind it: both advanced from frame 0 together. A leak
         * that needs a trajectory to appear shows here and not in the pair born at the last frame.
         */
        final Arm twinA = new Arm();
        final Arm twinB = new Arm();
        float worstTwinDrift;
        String worstTwinPiece;
        int twinFirstFrame;
        /**
         * The first frame on which `after` and `replay` disagree on any piece's solver direction, and
         * the last frame on which they agree on every piece - or -1 if they never disagreed.
         */
        int firstDivergenceFrame = -1;
        String firstDivergencePiece;
        /**
         * The largest {@code |a x b|} over every piece on the first frame the two long-lived arms
         * disagreed, and the largest such reading over every measured frame.
         *
         * <p>The cross-product magnitude is the reading that says whether a disagreement is a real
         * difference or an artefact of {@code acos}. The solver reports its swing as
         * {@code acos(dot(rest, direction))}, and {@code acos} has no resolution near either end of
         * its range: at the ANTIPODE, one part in 10^7 of the dot product is a whole fifth of a
         * degree of reported angle. Two arms that differ only in the last bits of a nearly-opposed
         * direction therefore print as degrees apart while being the same trajectory. {@code |a x b|}
         * is exact there - it is {@code sin} of the same angle and does not lose precision at either
         * end - so a disagreement this small with a large reported-angle difference is arithmetic,
         * and a disagreement this small with a small cross product is nothing at all.
         */
        float firstDivergenceCross = -1.0F;
        /** See the bare-pair experiment in {@link #measure}: real colliders, no instrument. */
        YsmMeshSecondaryMotion.State bareA;
        YsmMeshSecondaryMotion.State bareB;
        float worstBareCross = 0.0F;
        String worstBarePiece;
        int worstBareFrame = -1;
        int bareFirstFrame = -1;
        String bareFirstPiece;
        /**
         * The same first-divergence and widest-separation readings the measured region gets, taken
         * for the frames BELOW the warmup as well. Round 8 could only say the two long-lived arms
         * never disagreed on a MEASURED frame; the bare pair is its own witness for the frames before
         * that, so the question "do the two arms that carry the gap numbers also disagree early" had
         * no answer. This is that answer.
         */
        int earlyFirstDivergenceFrame = -1;
        String earlyFirstDivergencePiece;
        float earlyWorstCross = 0.0F;
        String earlyWorstCrossPiece;
        int earlyWorstCrossFrame = -1;
        float worstDirectionCross = 0.0F;
        String worstDirectionCrossPiece;
        int worstDirectionCrossFrame = -1;
        String firstDivergenceLive;
        String firstDivergenceReplay;
        /** The solver's whole persistent state for the piece that first disagreed. */
        String firstDivergenceDetail;
        int lastAgreementFrame = -1;
        /**
         * The first frame on which the two long-lived states were HANDED different inputs, with the
         * slot of the snapshot that differed. A difference in the inputs explains a difference in the
         * outputs without any contamination; no difference ever means the outputs diverged while the
         * inputs never did.
         */
        int firstInputDivergenceFrame = -1;
        String firstInputDivergencePiece;
        int firstInputDivergenceSlot = -1;
        String firstInputDivergenceLive;
        String firstInputDivergenceReplay;
        /** `after` against `replay`: the frame path's own determinism, blocks. */
        float worstReplayDrift;
        String worstReplayPiece;
        /** `replay` against `replay2` - two consecutive identical passes. Must also be zero. */
        float worstReplay2Drift;
        String worstReplay2Piece;
        float worstBefore;
        String worstBeforePiece;
        float worstAfter;
        String worstAfterPiece;
        float controlBefore = 0.0F;
        float controlAfter = 0.0F;
        String controlPiece;
        String controlAfterPiece;
        /** How many pieces have their contact anchor ON their bind pivot (the exact control). */
        int controlPieces;
        /** Their names, for the report. */
        final Set<String> controlCandidates = new LinkedHashSet<>();
        /** The piece whose contact anchor is nearest its bind pivot: the control this model affords. */
        String smallestOffsetPiece;
        float smallestOffset = Float.MAX_VALUE;
        float smallestOffsetGapBefore;
        float smallestOffsetGapAfter;
        /** Pieces whose contact state differs between the arms at any frame. */
        final Set<String> contactChanged = new LinkedHashSet<>();
        /** Pieces whose whole collider set was skipped on one arm and never on the other. */
        final Set<String> newlySkipped = new LinkedHashSet<>();
        final Set<String> noLongerSkipped = new LinkedHashSet<>();
        /** Pieces whose granted swing differs between the arms at any frame. */
        final Set<String> swingChanged = new LinkedHashSet<>();
        float worstSwingDelta;
        /** The largest drawn move of a piece whose contact was zero on both arms. */
        float worstUntouchedDrift;
        String worstUntouchedPiece;
        /**
         * Per piece, the largest value of the BRIEF's own expression for the tested point -
         * {@code deformation(bindPivot) + restDir*lever}, with no delta of any kind - measured against
         * the drawn centre of mass. Held so this round's number and the earlier round's are the same
         * measurement and can be set side by side.
         */
        final Map<String, Float> restGap = new LinkedHashMap<>();
        int pieces;
        int chainedPieces;
        int rootPieces;
        int contactBefore;
        int contactAfter;
        /**
         * The first measured frame, term by term, for every piece and both arms - written at the moment
         * the question was asked rather than reconstructed afterwards. See {@link #diagnose}.
         */
        final StringBuilder diagnostic = new StringBuilder();
        /**
         * Round 10: the gap and its terms, kept at each piece's own worst measured frame. See
         * {@link GapTerms} for what each column is and why the row is taken at that frame rather
         * than at the first one.
         */
        final Map<String, GapTerms> worstTerms = new LinkedHashMap<>();
        /** The smallest anchor-vs-pivot offset seen on any measured frame, and its piece. */
        float smallestAnchor = Float.MAX_VALUE;
        String smallestAnchorPiece;
        int smallestAnchorFrame = -1;
        GapTerms smallestAnchorTerms;
        /**
         * The frame-by-frame solver state of ONE piece, both long-lived arms, in the order the passes
         * ran. See {@link #traceFrame}: this is the evidence for round 8's finding rather than a
         * search for it, and it is what shows the two arms were the same arm at two different ages.
         */
        final StringBuilder trace = new StringBuilder();
        /** How many pieces the instrument checked on this run, and how many pieces it measured. */
        int instrumentChecks;
        int recordedQuestions;

        StateRun(String name) {
            this.name = name;
        }
    }

    private static StateRun measure(YsmLegSkirtCollisionProbeTest.Rig rig, Drive drive,
                                    int frames, int warmup) {
        worstSeen = 0.0F;
        // A new run starts with an empty record: the questions of the previous drive must not be
        // readable as this one's. The frame path tags every question with its pass, so a stale record
        // would also be caught by the instrument's own assertion - this just keeps the two honest.
        YsmDynamicBoneSolver.resetProbe();
        YsmPhysicsParts.Model parts = rig.model();
        YsmBodyColliders body = rig.colliders();
        YsmMeshSecondaryMotion.State beforeState = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        YsmMeshSecondaryMotion.State afterState = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        // The replay of the after arm, on its own state. See the comment where it is used.
        YsmMeshSecondaryMotion.State replayState = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);

        ClipPose pose = new ClipPose(rig, drive);
        Vector3f velocity = new Vector3f(0.0F, 0.0F, -drive.speed());
        StateRun out = new StateRun(drive.name());
        out.pieces = rig.segments.size();
        for (YsmLegSkirtCollisionProbeTest.Segment piece : rig.segments) {
            if (piece.parent < 0) {
                // The root pieces are reported separately as well, because "a piece with no chain" is
                // what the brief calls the control even though the anchor/pivot pair is the property
                // that actually makes the two points the same one.
                out.rootPieces++;
            } else {
                out.chainedPieces++;
            }
            // The control is not "a piece with no parent": the piece is drawn rotated about its
            // CONTACT ANCHOR (`buildSegmentDelta`) while the solver holds it on a sphere about its
            // bind PIVOT, and those are different points unless the author put them together. A piece
            // whose anchor is its pivot has a delta that is a rotation about the very point the solver
            // models, which is the case where the metric must read zero on both arms.
            if (piece.bindAnchor.distance(piece.bindPivot) <= 1.0E-4F) {
                out.controlPieces++;
                out.controlCandidates.add(piece.name);
            }
            float offset = piece.bindAnchor.distance(piece.bindPivot);
            if (offset < out.smallestOffset) {
                out.smallestOffset = offset;
                out.smallestOffsetPiece = piece.name;
            }
        }
        // The two arms. The before arm wraps the colliders in a pass-through, which is bit for bit the
        // branch production takes for a piece with no parent; the after arm hands production the real
        // set. Both are wrapped by the recorder, so the point a volume was asked about is read from
        // what ran rather than re-derived.
        //
        // `before` is a DO-NOTHING delegate and not the real set with a flag: the whole point of the
        // arm is to be the behaviour production had before the ancestors' frame existed, and a wrapper
        // that forwards to the real colliders while claiming to be inert would silently measure the
        // fixed code twice. The rejection stub is null for every question, which is what "there is
        // nothing to collide with" means to the solver and is the branch it takes for a piece whose
        // model has no usable body geometry.
        RecordingColliders beforeColliders =
                new RecordingColliders(new RejectingColliders(), out.before, true);
        RecordingColliders afterColliders = new RecordingColliders(body, out.after, false);
        // ------------------------------------------------------------------
        // A REPLAY of the after arm: the same colliders production is handed, the same pose, in the
        // same frame, from a state of its own.
        //
        // It exists because the red line "a piece neither arm's collision touched must be drawn in the
        // same place on both" is currently violated by 0.648 blocks, and the two ways that can happen
        // have to be told apart before anything is called a defect:
        //
        //   (1) the frame path is NOT DETERMINISTIC - `simulate` reaches some state that outlives a
        //       pass, so which arm ran first changes the answer. Then the "drift" is the frame path's
        //       own hidden state and the red line is measuring that, not the collision frame;
        //   (2) the frame path IS deterministic, and the two arms genuinely diverge - which for an
        //       untouched piece would mean the collision frame reaches further than the contact sets
        //       `arm.contact` records, and the red line's own definition of "untouched" is wrong.
        //
        // Comparing `after` against `after` separates them, and it is the only comparison in this probe
        // whose two sides must agree exactly.
        // ------------------------------------------------------------------
        // ------------------------------------------------------------------
        // ROUND 8: the replay arm is advanced FROM FRAME 0, in lockstep with `after`.
        //
        // It was not, and that was the whole of the R6-7/R6-9/R7-3 blocker. `replayState` is
        // constructed here but its `simulate` call used to sit BELOW the `if (frame < warmup)
        // continue;` in the frame loop, so it was a pristine, never-simulated state the first time it
        // was advanced - and that first time was frame 8, which is exactly the warmup boundary and
        // exactly the frame the first-divergence check first runs on. So the probe compared an arm
        // that had run 8 passes against an arm that had run 1, and read the difference as
        // "`after` carries a 153-degree swing while `replay` sits at its rest direction".
        //
        // It is not a swing and it is not contamination: `replay`'s single pass was its FIRST pass,
        // which takes `update`'s initialisation branch (`direction = rest`, `angularVelocity = 0`,
        // `lastAngle` left at the zero written at the top of `update`). Both arms were behaving
        // correctly for their age. The two candidate causes R6-7 proposed are both dead: the frame
        // path does not carry state across passes, and no arm is contaminated by construction.
        //
        // The fix is to give `replay` the same history `after` has. It is advanced inside the frame
        // loop, immediately after `after`, on every frame from 0 - so from frame 0 on the two states
        // are the same age, see the same poses and are handed the same colliders, and the comparison
        // between them is the controlled experiment it was always meant to be. `replay2` stays
        // immediately behind `replay` with nothing between them, which is what isolates "a pass
        // contaminates the next pass" from "the process carries state".
        // ------------------------------------------------------------------
        RecordingColliders replayColliders = new RecordingColliders(body, out.replay, false);
        RecordingColliders replay2Colliders = new RecordingColliders(body, out.replay2, false);
        YsmMeshSecondaryMotion.State replay2State = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);

        // ------------------------------------------------------------------
        // The controlled pair: two passes that must agree, on the same colliders, the same pose, the
        // same frame, from two states of their own - born at the first measured frame and advanced
        // together from there. `pair2` follows `pair1` by one pass every frame, which is exactly the
        // relation `replay` has to `after`, with no history on either side to attribute a difference
        // to. If they diverge, a pass contaminates the next; if they never do, the frame path is
        // clean and `after` against `replay` is measuring a difference in what the two states were
        // HANDED rather than one the second pass inherited.
        // ------------------------------------------------------------------
        YsmMeshSecondaryMotion.State pairA = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        YsmMeshSecondaryMotion.State pairB = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        RecordingColliders pairAColliders = new RecordingColliders(body, out.pairA, false);
        RecordingColliders pairBColliders = new RecordingColliders(body, out.pairB, false);
        // And the same with the whole history: two states advanced together since frame 0.
        YsmMeshSecondaryMotion.State twinA = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        YsmMeshSecondaryMotion.State twinB = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        RecordingColliders twinAColliders = new RecordingColliders(body, out.twinA, false);
        RecordingColliders twinBColliders = new RecordingColliders(body, out.twinB, false);
        // The bare pair: real colliders, no wrapper, nothing read between the two calls.
        out.bareA = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        out.bareB = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);

        // The `after` arm's own directions as its pass ends, re-taken every frame. See where it is
        // used: the frame-to-frame comparison of the two long-lived states needs one side that has
        // not yet been touched by this frame's other passes.
        Vector3f[] afterDirection = new Vector3f[0];
        // The trace line for `after`, taken the instant its own pass ends - the same discipline the
        // direction copy above follows, and for the same reason.
        String afterTrace = "";

        for (int frame = 0; frame < frames; frame++) {
            pose.at(frame * DT);
            if (frame == 0) {
                OpenMatrix4f[] poseMatrices = new OpenMatrix4f[JointTable.COUNT];
                for (int joint = 0; joint < poseMatrices.length; joint++) {
                    poseMatrices[joint] = pose.poseOf(joint);
                }
                body.update(rig.armature(), poseMatrices);
            }
            // The production frame loop, exactly as the frame path calls it. The recorder wraps the
            // colliders on both arms, so the point a volume was asked about is read from what ran.
            beforeColliders.beginFrame();
            afterColliders.beginFrame();
            replayColliders.beginFrame();
            // One pass over the segments per arm per frame - which is what production does.
            //
            // An earlier revision called `simulate` once per SEGMENT inside this loop, on the theory
            // that naming each piece before the solve was how the recorder attributed a call to it.
            // That is 59 integrations of every piece per frame instead of one: the arms still ran
            // identically-shaped code, so the comparison between them looked meaningful, but neither
            // trajectory was the production one - measured on `FM2`, the solver's direction ended up
            // 0.6 blocks from anything the pose draws.
            //
            // Each arm's records are read back immediately after its own pass, before the other arm
            // runs, and the question they are read from is tagged with the pass it belongs to. A point
            // that is not this frame's cannot be mistaken for one: see assertInstrument.
            YsmMeshSecondaryMotion.simulate(beforeState, pose, DT, velocity, NO_TURN, beforeColliders);
            // ------------------------------------------------------------------
            // THE BARE PAIR: two states advanced in lockstep from frame 0, handed the REAL collider
            // object itself rather than a recording wrapper around it, with nothing between their two
            // calls and nothing read off them. This is the experiment that separates "the solver
            // carries state across passes" from "the probe's own instrumentation does".
            //
            // It exists because EF walk is the one drive where `after` and `replay` - which are now the
            // same age, given the same pose and the same colliders - still disagree, by a real 2.6
            // degrees (cross product 0.046, so not an `acos` artefact), and the disagreement appears
            // exactly on the first frame of the MEASURED region. Either the frame path carries
            // something across passes and only EF walk's long quasi-static poses make it survive, or
            // the instrument that runs between the arms in the measured region is what carries it.
            // The bare pair removes every instrument: two states, one call each, `body` itself.
            // ------------------------------------------------------------------
            YsmMeshSecondaryMotion.simulate(out.bareA, pose, DT, velocity, NO_TURN, body);
            YsmMeshSecondaryMotion.simulate(out.bareB, pose, DT, velocity, NO_TURN, body);
            for (int index = 0; index < rig.segments.size(); index++) {
                float cross = crossMagnitude(out.bareA.states[index].direction,
                        out.bareB.states[index].direction);
                if (cross > out.worstBareCross) {
                    out.worstBareCross = cross;
                    out.worstBarePiece = rig.segments.get(index).name;
                    out.worstBareFrame = frame;
                }
                if (!bitwiseEqual(out.bareA.states[index].direction,
                        out.bareB.states[index].direction) && out.bareFirstFrame < 0) {
                    out.bareFirstFrame = frame;
                    out.bareFirstPiece = rig.segments.get(index).name;
                }
            }
            int beforeFrame = YsmDynamicBoneSolver.probeFrame();
            Map<Integer, QuestionView> beforeQuestions = questionsOfPass(beforeFrame);
            if (frame >= warmup) {
                assertInstrument(rig, beforeState, pose, beforeQuestions, beforeFrame, "before");
                out.instrumentChecks++;
                out.recordedQuestions = Math.max(out.recordedQuestions, beforeQuestions.size());
            }
            YsmMeshSecondaryMotion.simulate(afterState, pose, DT, velocity, NO_TURN, afterColliders);
            int afterFrame = YsmDynamicBoneSolver.probeFrame();
            Map<Integer, QuestionView> afterQuestions = questionsOfPass(afterFrame);
            if (frame >= warmup) {
                assertInstrument(rig, afterState, pose, afterQuestions, afterFrame, "after");
                out.instrumentChecks++;
                out.recordedQuestions = Math.max(out.recordedQuestions, afterQuestions.size());
            }
            // The `after` arm's solver state the moment its own pass has finished, before any other
            // pass has run. The comparison below is then between two states that have each run the
            // same number of frames - it is taken again after `replay`'s pass on the same frame, so
            // frame 0 is comparable too. Without this the detector could only start at the first
            // MEASURED frame, by which time any divergence is long established.
            afterDirection = new Vector3f[rig.segments.size()];
            for (int index = 0; index < rig.segments.size(); index++) {
                afterDirection[index] = new Vector3f(afterState.states[index].direction);
            }
            // The `after` arm's whole per-frame solver state for the traced piece, taken at the same
            // instant and for the same reason: this is the side that has not yet been touched by this
            // frame's other passes. See #traceFrame.
            afterTrace = "`after` " + traceFrame(frame, afterState, rig, pose);
            // ------------------------------------------------------------------
            // ROUND 8: THE REPLAY, IN LOCKSTEP FROM FRAME 0.
            //
            // This call used to live below the `continue` below, together with `replay2` and both
            // pairs - which made every arm on the far side of it exactly one pass old at frame 8 while
            // `after` was eight passes old. That was the entire content of R6-7/R6-9/R7-3: not a
            // contaminated arm, not a stale static, not an order-dependent frame path, but two arms of
            // different ages being compared as if they were the same arm twice.
            //
            // Nothing else moves. The same colliders production is handed, the same pose, one pass per
            // frame, from a state of its own - the only change is that the passes start at 0.
            // ------------------------------------------------------------------
            YsmMeshSecondaryMotion.simulate(replayState, pose, DT, velocity, NO_TURN, replayColliders);
            // ------------------------------------------------------------------
            // ROUND 9: the two long-lived arms, compared on the frames BELOW the warmup.
            //
            // Everything the round-8 report says about `after` against `replay` is about frames from
            // the warmup on, because the first-divergence check lives below the `continue`. The bare
            // pair is compared on every frame, so a divergence it reports on frame 6 has no
            // counterpart reading on the two arms the gap and drift numbers come from. This is that
            // reading, and it is taken here - both passes have run this frame, and each has run the
            // same number of frames since 0.
            // ------------------------------------------------------------------
            if (frame < warmup) {
                for (int index = 0; index < rig.segments.size(); index++) {
                    float cross = crossMagnitude(afterDirection[index],
                            replayState.states[index].direction);
                    if (cross > out.earlyWorstCross) {
                        out.earlyWorstCross = cross;
                        out.earlyWorstCrossPiece = rig.segments.get(index).name;
                        out.earlyWorstCrossFrame = frame;
                    }
                    if (out.earlyFirstDivergenceFrame < 0 && !bitwiseEqual(afterDirection[index],
                            replayState.states[index].direction)) {
                        out.earlyFirstDivergenceFrame = frame;
                        out.earlyFirstDivergencePiece = rig.segments.get(index).name;
                    }
                }
            }
            YsmMeshSecondaryMotion.simulate(replay2State, pose, DT, velocity, NO_TURN, replay2Colliders);
            out.trace.append(afterTrace).append("`replay` ")
                    .append(traceFrame(frame, replayState, rig, pose));
            if (frame < warmup) {
                continue;
            }
            Map<String, Vector3f> drawnBefore = new LinkedHashMap<>();
            Map<String, Vector3f> drawnAfter = new LinkedHashMap<>();
            for (int index = 0; index < rig.segments.size(); index++) {
                YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
                OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
                // The PUBLISHED delta - `state.deltas[index]` - and not a walk of the parent chain. The
                // chain's slot already holds the composed transform, so walking it composes the parents
                // twice; and this is the same matrix the render path hands the mesh.
                drawnBefore.put(piece.name, rig.drawnCentre(piece, deformation, beforeState));
                drawnAfter.put(piece.name, rig.drawnCentre(piece, deformation, afterState));
            }
            // The replay's drawn points, taken between `after`'s pass and the `after` reading below,
            // and on the same pose. Its own state, its own recorder, the same colliders. The two
            // passes themselves ran above, in the same order and on every frame since 0; this only
            // reads where they left the pieces.
            Map<String, Vector3f> drawnReplay = new LinkedHashMap<>();
            Map<String, Vector3f> drawnReplay2 = new LinkedHashMap<>();
            // Both long-lived states have now run the same NUMBER of frames - which is the property
            // round 8 exists to restore, and the reason the comparison below means anything. The first
            // frame on which any piece's direction differs is named here.
            // The reading that survives acos, over EVERY piece on every frame: how far apart the two
            // arms' directions really are, as the magnitude of their cross product. See the field
            // comment on `worstDirectionCross`. Measured before the divergence check below and
            // independently of it, because that check stops at the first piece that differs and would
            // otherwise truncate this reading on exactly the frames that matter.
            for (int index = 0; index < rig.segments.size(); index++) {
                float cross = crossMagnitude(afterDirection[index],
                        replayState.states[index].direction);
                if (cross > out.worstDirectionCross) {
                    out.worstDirectionCross = cross;
                    out.worstDirectionCrossPiece = rig.segments.get(index).name;
                    out.worstDirectionCrossFrame = frame;
                }
            }
            if (out.firstDivergenceFrame < 0) {
                for (int index = 0; index < rig.segments.size(); index++) {
                    Vector3f mine = afterDirection[index];
                    Vector3f theirs = replayState.states[index].direction;
                    if (!bitwiseEqual(mine, theirs)) {
                        out.firstDivergenceFrame = frame;
                        out.firstDivergencePiece = rig.segments.get(index).name;
                        out.firstDivergenceCross = crossMagnitude(mine, theirs);
                        out.firstDivergenceLive = point(mine);
                        out.firstDivergenceReplay = point(theirs);
                        // The solver's own persistent state for this piece, both sides, at the moment
                        // the two first disagree. Everything the solver carries between frames is
                        // here, so a difference that was inherited shows as a difference in one of
                        // these rather than only in the direction.
                        YsmDynamicBoneSolver.SegmentState live = afterState.states[index];
                        YsmDynamicBoneSolver.SegmentState other = replayState.states[index];
                        out.firstDivergenceDetail = String.format(java.util.Locale.ROOT,
                                "angle live %.6f / replay %.6f; velocity live %s / replay %s; "
                                        + "lastPivot live %s / replay %s; smoothedPivot live %s / "
                                        + "replay %s; initialized %s / %s; pivotVelocity live %s / "
                                        + "replay %s; rest live %s / replay %s",
                                Math.toDegrees(live.lastAngle), Math.toDegrees(other.lastAngle),
                                point(live.angularVelocity), point(other.angularVelocity),
                                point(live.lastPivot), point(other.lastPivot),
                                point(live.smoothedPivot), point(other.smoothedPivot),
                                live.initialized, other.initialized,
                                point(live.pivotVelocity), point(other.pivotVelocity),
                                point(afterState.restDirections[index]),
                                point(replayState.restDirections[index]));
                        break;
                    }
                }
                if (out.firstDivergenceFrame < 0) {
                    out.lastAgreementFrame = frame;
                }
            }
            // The same question asked of what the two passes were HANDED. If the solver's output
            // differs on a frame whose inputs are bit-identical, the difference was inherited; if the
            // inputs differ on that frame - or on an earlier one - then it was not, and this is the
            // frame and the quantity to look at instead. `inputs` holds the deformation's first row,
            // the pivot and the rest direction: everything `resolveSegment` builds for the solver out
            // of the pose.
            if (out.firstInputDivergenceFrame < 0) {
                for (int index = 0; index < rig.segments.size(); index++) {
                    YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
                    OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
                    snapshot(out.after, afterState, piece, deformation);
                    snapshot(out.replay, replayState, piece, deformation);
                    float[] mine = out.after.inputs.get(piece.name);
                    float[] theirs = out.replay.inputs.get(piece.name);
                    int slot = firstDifferentSlot(mine, theirs);
                    if (slot >= 0) {
                        out.firstInputDivergenceFrame = frame;
                        out.firstInputDivergencePiece = piece.name;
                        out.firstInputDivergenceSlot = slot;
                        out.firstInputDivergenceLive = fmt(mine[slot]);
                        out.firstInputDivergenceReplay = fmt(theirs[slot]);
                        break;
                    }
                }
            }
            // ------------------------------------------------------------------
            // The controlled experiment: TWO passes that must be identical, side by side from the
            // first measured frame on, on the same colliders, the same pose and the same frame.
            //
            // `replay2` above cannot answer this on its own. It runs once per frame from a state of
            // its own, and `after` also runs once per frame from a state of its own, so `after`'s
            // trajectory and `replay2`'s are the SAME trajectory at DIFFERENT offsets - both saw the
            // same sequence of poses and the same inputs. `after` differs from `replay` by an angle
            // while both are handed bit-identical pivots and rest directions (see #divergenceOf), so
            // either the frame path carries something across passes - in which case two passes
            // started from the same place must still diverge - or the two states did not in fact
            // start from the same place.
            //
            // `pair1` and `pair2` are constructed inside this loop on the same frame from the same
            // model, colliders and pose, and advanced together. Their divergence, per frame, is the
            // leak's own size, with no history between them to attribute it to. The pose is shared
            // and the colliders are shared, which is exactly what production does.
            // ------------------------------------------------------------------
            YsmMeshSecondaryMotion.simulate(pairA, pose, DT, velocity, NO_TURN, pairAColliders);
            YsmMeshSecondaryMotion.simulate(pairB, pose, DT, velocity, NO_TURN, pairBColliders);
            float pairDrift = 0.0F;
            String pairPiece = null;
            for (int index = 0; index < rig.segments.size(); index++) {
                YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
                OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
                float drift = rig.drawnCentre(piece, deformation, pairA)
                        .distance(rig.drawnCentre(piece, deformation, pairB));
                if (drift > pairDrift) {
                    pairDrift = drift;
                    pairPiece = piece.name;
                }
            }
            if (frame == warmup || pairDrift > out.worstPairDrift) {
                out.worstPairDrift = Math.max(out.worstPairDrift, pairDrift);
                out.worstPairPiece = pairPiece;
                out.pairFirstFrame = frame;
            }
            // And the same experiment with the whole history behind it: two states advanced through
            // every frame since 0 together, so both carry a trajectory rather than one pass. A leak
            // that only appears once a state has a history needs this pair; `pairA`/`pairB` above
            // start at the measured frame.
            YsmMeshSecondaryMotion.simulate(twinA, pose, DT, velocity, NO_TURN, twinAColliders);
            YsmMeshSecondaryMotion.simulate(twinB, pose, DT, velocity, NO_TURN, twinBColliders);
            float twinDrift = 0.0F;
            String twinPiece = null;
            for (int index = 0; index < rig.segments.size(); index++) {
                YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
                OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
                float drift = rig.drawnCentre(piece, deformation, twinA)
                        .distance(rig.drawnCentre(piece, deformation, twinB));
                if (drift > twinDrift) {
                    twinDrift = drift;
                    twinPiece = piece.name;
                }
            }
            if (twinDrift > out.worstTwinDrift) {
                out.worstTwinDrift = twinDrift;
                out.worstTwinPiece = twinPiece;
                out.twinFirstFrame = frame;
            }
            for (int index = 0; index < rig.segments.size(); index++) {
                YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
                OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
                Vector3f point = rig.drawnCentre(piece, deformation, replayState);
                Vector3f second = rig.drawnCentre(piece, deformation, replay2State);
                drawnReplay.put(piece.name, point);
                drawnReplay2.put(piece.name, second);
                // `after` against `after`: must be zero. Recorded piece by piece, and the worst is
                // reported, because a non-zero reading here is the frame path carrying state across
                // passes and it would invalidate the untouched-drift red line rather than be one.
                Vector3f was = drawnAfter.get(piece.name);
                if (was != null) {
                    float replayDrift = was.distance(point);
                    if (replayDrift > out.worstReplayDrift) {
                        out.worstReplayDrift = replayDrift;
                        out.worstReplayPiece = piece.name;
                    }
                }
                float consecutiveDrift = point.distance(second);
                if (consecutiveDrift > out.worstReplay2Drift) {
                    out.worstReplay2Drift = consecutiveDrift;
                    out.worstReplay2Piece = piece.name;
                }
            }
            for (int index = 0; index < rig.segments.size(); index++) {
                YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
                OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
                // One point per piece per arm, so the second arm's pass overwrites the first's: the
                // after arm is recorded before its endPiece and the before arm's own point is taken
                // below from its own pass.
                beforeColliders.endPiece(beforeState, index, piece, deformation,
                        rig.publishedDelta(beforeState, index), drawnBefore, beforeQuestions);
                afterColliders.endPiece(afterState, index, piece, deformation,
                        rig.publishedDelta(afterState, index), drawnAfter, afterQuestions);
                out.before.lastDrawn.put(piece.name, drawnBefore.get(piece.name));
                out.after.lastDrawn.put(piece.name, drawnAfter.get(piece.name));
                out.replay.lastDrawn.put(piece.name, drawnReplay.get(piece.name));
                // The divergence instrument: the quantities the solver was HANDED and the quantities
                // it PRODUCED, for the three passes that must agree. Written on every measured frame
                // so the last measured frame is the one reported; the first frame at which the two
                // disagree is named by the table itself.
                snapshot(out.before, beforeState, piece, deformation);
                snapshot(out.after, afterState, piece, deformation);
                snapshot(out.replay, replayState, piece, deformation);
                snapshot(out.replay2, replay2State, piece, deformation);
                // Round 10, item 3: the terms the gap is made of, kept where this piece's own gap is
                // widest. See GapTerms.
                GapTerms terms = gapTermsOf(rig, afterState, pose, index, afterQuestions);
                if (terms != null) {
                    GapTerms worst = out.worstTerms.get(piece.name);
                    if (worst == null || terms.gap > worst.gap) {
                        out.worstTerms.put(piece.name, terms);
                    }
                    if (terms.anchorTerm < out.smallestAnchor) {
                        out.smallestAnchor = terms.anchorTerm;
                        out.smallestAnchorPiece = piece.name;
                        out.smallestAnchorFrame = frame;
                        out.smallestAnchorTerms = terms;
                    }
                }
                // The first measured frame, term by term - written while the pose and both states are
                // the ones the question belongs to.
                if (frame == warmup) {
                    out.diagnostic.append(diagnose(rig, beforeState, pose, index, "before",
                            beforeQuestions));
                    out.diagnostic.append(diagnose(rig, afterState, pose, index, "after",
                            afterQuestions));
                }
            }
        }
        out.before.frames = frames - warmup;
        out.after.frames = frames - warmup;
        finish(out, rig);
        return out;
    }

    /**
     * Whether two vectors are the same float for float. {@code Float.compare} and not {@code ==},
     * because this is asked about a solver's own arithmetic and a sign-of-zero or a NaN would be a
     * difference worth seeing rather than one to swallow.
     */
    /**
     * How far apart two directions really are, as the magnitude of their cross product.
     *
     * <p>{@code |a x b| = sin(angle)}, which is the one reading of an angular difference that keeps
     * its precision at both ends of the range. The solver reports its swing as
     * {@code acos(dot(rest, direction))}, and {@code acos} loses all resolution near 1 AND near -1:
     * around the antipode the reported angle is the square root of a rounding error, so two arms that
     * differ in the last bits of a nearly-opposed direction print as degrees apart. This number does
     * not do that, and it is symmetric in the two ends of the range, so it is what decides whether a
     * printed disagreement is a difference of trajectory or a difference of float rounding.
     */
    private static float crossMagnitude(Vector3f a, Vector3f b) {
        float x = a.y * b.z - a.z * b.y;
        float y = a.z * b.x - a.x * b.z;
        float z = a.x * b.y - a.y * b.x;
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static boolean bitwiseEqual(Vector3f a, Vector3f b) {
        return Float.compare(a.x, b.x) == 0 && Float.compare(a.y, b.y) == 0
                && Float.compare(a.z, b.z) == 0;
    }

    /** The first slot two snapshots differ in, bit for bit, or -1 when they are the same value. */
    private static int firstDifferentSlot(float[] a, float[] b) {
        if (a == null || b == null) {
            return -1;
        }
        for (int i = 0; i < a.length && i < b.length; i++) {
            if (Float.compare(a[i], b[i]) != 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * One line of the per-frame trace: what the solver held for {@link #TRACE_PIECE} at the moment
     * this is called, on the arm whose name it is handed.
     *
     * <p>This is the R7-8 experiment, and it is now the <b>evidence</b> for round 8's finding rather
     * than a way of looking for one. Two readings in it settle the round on their own:
     *
     * <ul>
     *   <li>the alignment {@code dot(rest, direction)}. {@code lastAngle} is
     *       {@code acos(dot)} of the same pair, so the two must agree; where they do not, the angle
     *       is not being read from the vectors it claims to be read from. It is printed raw because
     *       {@code acos} loses all its resolution near 1, and "is this piece at its pose" is exactly
     *       the question that has to stay readable there.</li>
     *   <li>{@code initialized}. The solver's initialisation branch sets {@code direction = rest},
     *       {@code angularVelocity = 0} and does NOT touch {@code lastAngle} - so a pass that takes it
     *       is the exact fingerprint of "this state has never been updated". Round 7 read
     *       {@code replay}'s fingerprint and concluded the state was being re-initialised; it was
     *       being initialised, for the first time, at frame 8.</li>
     * </ul>
     */
    private static String traceFrame(int frame, YsmMeshSecondaryMotion.State state,
                                     YsmLegSkirtCollisionProbeTest.Rig rig, ClipPose pose) {
        int index = -1;
        for (int i = 0; i < rig.segments.size(); i++) {
            if (TRACE_PIECE.equals(rig.segments.get(i).name)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return "";
        }
        YsmDynamicBoneSolver.SegmentState sim = state.states[index];
        Vector3f rest = state.restDirections[index];
        return String.format(java.util.Locale.ROOT,
                "| `%s` | %d | %b | %.9f | %.9f | %s | %s | %s | %s | %s | (%.6f,%.6f,%.6f) |%n",
                TRACE_PIECE, frame, sim.initialized, sim.lastAngle,
                YsmDynamicBoneSolver.alignment(rest, sim.direction),
                point(sim.direction), point(rest), point(sim.angularVelocity),
                point(sim.lastPivot), point(state.pivots[index]),
                state.jomlDeltas[index].m30(), state.jomlDeltas[index].m31(),
                state.jomlDeltas[index].m32());
    }

    /** The piece the per-frame trace follows. `RB3` is the piece R7-3's divergence was named on. */
    private static final String TRACE_PIECE = "RB3";

    /**
     * What one pass was HANDED and what it PRODUCED for one piece, on the frame this is called.
     *
     * <p>Written because "the same arm twice draws the same piece 0.780 blocks apart" (round 6) is a
     * statement about the frame path, and every quantity between the two ends of it was a candidate:
     * the deformation the pose gives (shared by every arm, so it cannot be the difference), the pivot
     * and rest direction built from it, the direction the solver integrated, the swing it returned,
     * and the published delta. Reading them all at once on the disagreeing frame turns "the frame path
     * is not deterministic" into "this quantity differs, and everything before it does not".
     *
     * <p>{@code inputs} is the state BEFORE the collision response has had a say: the deformation's
     * first row (the pose's own numbers), the pivot and the rest direction as the state holds them -
     * both written by {@code resolveSegment} at the top of the pass and by nothing else, which is what
     * makes them the same on every arm if the pose is. {@code outputs} is what the pass left behind:
     * the solver's direction and last angle, the swing quaternion it returned into the shared scratch,
     * and the published delta's translation and column norms.
     */
    private static void snapshot(Arm arm, YsmMeshSecondaryMotion.State state,
                                 YsmLegSkirtCollisionProbeTest.Segment piece, OpenMatrix4f deformation) {
        String name = piece.name;
        int index = piece.index;
        float[] in = arm.inputs.computeIfAbsent(name, key -> new float[10]);
        in[0] = deformation.m00;
        in[1] = deformation.m01;
        in[2] = deformation.m02;
        in[3] = deformation.m03;
        Vector3f pivot = state.pivots[index];
        Vector3f rest = state.restDirections[index];
        in[4] = pivot.x;
        in[5] = pivot.y;
        in[6] = pivot.z;
        in[7] = rest.x;
        in[8] = rest.y;
        in[9] = rest.z;

        YsmDynamicBoneSolver.SegmentState sim = state.states[index];
        Matrix4f delta = arm.deltaOf(state, index);
        float[] outValues = arm.outputs.computeIfAbsent(name, key -> new float[17]);
        outValues[0] = sim.direction.x;
        outValues[1] = sim.direction.y;
        outValues[2] = sim.direction.z;
        outValues[3] = delta.m30();
        outValues[4] = delta.m31();
        outValues[5] = delta.m32();
        // The published delta's whole rotation, in draw order: (m00,m10,m20) then (m01,m11,m21) then
        // (m02,m12,m22). This is the transform the mesh is handed, so a difference here is a
        // difference on screen whatever else agrees - and the sweep of the whole triple says whether
        // the swing itself moved or only its composition with the ancestors did.
        outValues[6] = delta.m00();
        outValues[7] = delta.m10();
        outValues[8] = delta.m20();
        outValues[9] = delta.m01();
        outValues[10] = delta.m11();
        outValues[11] = delta.m21();
        outValues[12] = delta.m02();
        outValues[13] = delta.m12();
        outValues[14] = delta.m22();
        outValues[15] = sim.lastAngle;
        // Slot 16: was this piece handed to the solver at all on the pass just run. `integrated` is
        // written by `resolveSegment` at the one place a segment goes to the solver, so this is a fact
        // about what ran rather than about what the code looks like - and a piece that never reaches
        // the solver keeps whatever direction it had, which is what a stuck arm looks like.
        outValues[16] = state.integrated[index] ? 1.0F : 0.0F;
    }

    /**
     * The three passes' snapshots set side by side, term by term: the largest difference per
     * quantity over every piece, and the piece that carries it. This is the instrument that turns
     * "the frame path is not deterministic" into a named quantity.
     */
    private static String divergenceOf(StateRun run) {
        StringBuilder out = new StringBuilder();
        out.append("### What differs between the passes that must agree, term by term\n\n")
                .append("`after` and `replay` are the same pass on the same pose and frames, from two ")
                .append("states of their own; `replay` and `replay2` are the same pair with nothing ")
                .append("between them. This table is the last measured frame. `inputs` is what the ")
                .append("solver was HANDED - the deformation's first row, then the pivot, then the rest ")
                .append("direction, all built from the pose before any collision response exists; a ")
                .append("difference there is a pose or a state-input problem. `outputs` is what the ")
                .append("pass PRODUCED - the solver's own direction, the published delta the mesh is ")
                .append("handed (its translation and all three rotation columns), and the granted ")
                .append("angle; a difference only there is the solver's path carrying something across ")
                .append("passes. See the controlled pair in the summary above for whether such a ")
                .append("carry exists at all.\n\n")
                .append("| pair | worst input difference | piece | worst output difference | piece |\n")
                .append("|---|---|---|---|---|\n");
        row(out, "after vs replay", run.after, run.replay);
        row(out, "replay vs replay2", run.replay, run.replay2);
        out.append("\nPieces the solver was handed on the last measured frame, by arm: `before` ")
                .append(integratedCount(run.before)).append(" / ").append(run.pieces)
                .append(", `after` ").append(integratedCount(run.after)).append(" / ")
                .append(run.pieces).append(", `replay` ").append(integratedCount(run.replay))
                .append(" / ").append(run.pieces).append(", `replay2` ")
                .append(integratedCount(run.replay2)).append(" / ").append(run.pieces)
                .append(". A piece the solver was not handed keeps its previous direction, so an arm "
                        + "whose count is not the whole model is not running the pass it is believed "
                        + "to run.\n\n");
        traceOf(out, run);
        return out.toString();
    }

    /**
     * The per-frame trace of one piece, both long-lived arms, in pass order.
     *
     * <p>Round 7 asked for exactly this table and could not read it: {@code lastAngle} and
     * {@code initialized} per frame, both arms. Read it as the proof of round 8's finding. On the
     * frame the two arms were first compared, the older arm carries an angle and a non-zero angular
     * velocity while the younger one is at {@code initialized = true} with {@code lastAngle} exactly
     * zero and a direction bit-identical to its rest - which is what a state reports on the pass that
     * takes the solver's initialisation branch. `alignment` is {@code dot(rest, direction)} and must
     * agree with {@code lastAngle} to within {@code acos}'s resolution; it is printed raw because
     * near a rest pose {@code acos} cannot tell "at the pose" from "a hundredth of a degree off it".
     */
    private static void traceOf(StringBuilder out, StateRun run) {
        if (run.trace.length() == 0) {
            return;
        }
        out.append("### One piece, frame by frame, both long-lived arms (`").append(TRACE_PIECE)
                .append("`)\n\n")
                .append("`after` and `replay` are advanced in lockstep from frame 0, so the two rows ")
                .append("of a frame are the same piece at the same age and must agree. `angle` is ")
                .append("`state.lastAngle` in radians and `alignment` is `dot(rest, direction)`, the ")
                .append("same pair `lastAngle` is read from; `init` is the solver's own flag.\n\n")
                .append("| arm | frame | init | angle (rad) | alignment | direction | rest | ")
                .append("angular velocity | lastPivot | pivot | delta translation |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n")
                .append(run.trace);
        out.append('\n');
    }

    /** How many pieces this arm's last pass handed to the solver. See {@link #snapshot}, slot 16. */
    private static int integratedCount(Arm arm) {
        int count = 0;
        for (float[] values : arm.outputs.values()) {
            if (values.length > 16 && values[16] > 0.5F) {
                count++;
            }
        }
        return count;
    }

    private static void row(StringBuilder out, String label, Arm left, Arm right) {
        float worstIn = 0.0F;
        String worstInPiece = "-";
        float worstOut = 0.0F;
        String worstOutPiece = "-";
        for (Map.Entry<String, float[]> entry : left.inputs.entrySet()) {
            float[] other = right.inputs.get(entry.getKey());
            if (other == null) {
                continue;
            }
            float worst = 0.0F;
            for (int i = 0; i < entry.getValue().length; i++) {
                worst = Math.max(worst, Math.abs(entry.getValue()[i] - other[i]));
            }
            if (worst > worstIn) {
                worstIn = worst;
                worstInPiece = entry.getKey();
            }
        }
        for (Map.Entry<String, float[]> entry : left.outputs.entrySet()) {
            float[] other = right.outputs.get(entry.getKey());
            if (other == null) {
                continue;
            }
            float worst = 0.0F;
            for (int i = 0; i < entry.getValue().length; i++) {
                worst = Math.max(worst, Math.abs(entry.getValue()[i] - other[i]));
            }
            if (worst > worstOut) {
                worstOut = worst;
                worstOutPiece = entry.getKey();
            }
        }
        out.append("| ").append(label).append(" | ").append(fmt(worstIn)).append(" | `")
                .append(worstInPiece).append("` | ").append(fmt(worstOut)).append(" | `")
                .append(worstOutPiece).append("` |\n");
        // The same comparison, one quantity at a time, on the piece that carries the worst output
        // difference. Reading a single number ("the outputs differ by 0.78") leaves the reader unable
        // to tell an integrated direction from a published translation; this says which.
        float[] a = left.outputs.get(worstOutPiece);
        float[] b = right.outputs.get(worstOutPiece);
        if (a != null && b != null) {
            String[] names = {"solver direction", "delta translation", "delta rotation m00 m10 m20",
                    "delta rotation m01 m11 m21", "delta rotation m02 m12 m22", "granted angle"};
            int[] from = {0, 3, 6, 9, 12, 15};
            int[] size = {3, 3, 3, 3, 3, 1};
            out.append("\nOn `").append(worstOutPiece).append("`, ")
                    .append(label).append(", term by term:\n\n")
                    .append("| quantity | ").append(label.replace(" vs ", " | ")).append(" | difference |\n")
                    .append("|---|---|---|---|\n");
            for (int group = 0; group < names.length; group++) {
                StringBuilder l = new StringBuilder();
                StringBuilder r = new StringBuilder();
                float worst = 0.0F;
                for (int i = from[group]; i < from[group] + size[group]; i++) {
                    l.append(l.length() == 0 ? "" : ", ").append(fmt(a[i]));
                    r.append(r.length() == 0 ? "" : ", ").append(fmt(b[i]));
                    worst = Math.max(worst, Math.abs(a[i] - b[i]));
                }
                out.append("| ").append(names[group]).append(" | ").append(l).append(" | ")
                        .append(r).append(" | ").append(fmt(worst)).append(" |\n");
            }
            out.append("\n");
        }
    }

    /**
     * The two arms, and the one wrapper both of them run behind.
     *
     * <p><b>before</b> hands production a pass-through wrapper: every call forwarded to the real
     * {@link YsmBodyColliders} and nothing changed, which is bit for bit the branch production takes
     * for a piece with no parent. <b>after</b> hands it the real collider set, so production builds its
     * own {@code CarriedColliders} around it.
     *
     * <p>Both arms are recorded by the same wrapper, and that is the point: rather than deriving where
     * the solver's tested point ends up, the recorder reads the <b>exact point the volume was asked
     * about</b> - whatever frame production decided to ask in - and the gap is that point's distance
     * from the drawn centre of mass. A metric that re-derives the point can be wrong about the frame;
     * this one cannot, and it is applied identically to both arms.
     */
    private static final class RecordingColliders implements YsmDynamicBoneSolver.Colliders {
        private final YsmDynamicBoneSolver.Colliders delegate;
        private final Arm arm;
        private final boolean passThrough;
        /**
         * This frame's points, keyed by the segment's own NAME.
         *
         * <p>By name and not by joint, which is the mistake this recorder made first: several segments
         * of this model resolve for different bones that share one Epic Fight joint, so a joint key
         * hands every one of them the same entry and the whole table reads as one piece's point
         * repeated. Names are unique per segment by construction (`Rig.buildSegments` builds one per
         * bone).
         */
        private final Map<String, Vector3f> askedThisFrame = new LinkedHashMap<>();
        private String currentName;
        private int volumes;
        private int skipped;

        RecordingColliders(YsmDynamicBoneSolver.Colliders delegate, Arm arm, boolean passThrough) {
            this.delegate = delegate;
            this.arm = arm;
            this.passThrough = passThrough;
        }

        void beginFrame() {
            askedThisFrame.clear();
            currentName = null;
        }

        /** The segment the frame loop is about to resolve; every call below belongs to it. */
        void startPiece(YsmLegSkirtCollisionProbeTest.Segment piece) {
            currentName = piece.name;
            volumes = 0;
            skipped = 0;
        }

        void endPiece(YsmMeshSecondaryMotion.State state, int index,
                      YsmLegSkirtCollisionProbeTest.Segment piece, OpenMatrix4f deformation,
                      Matrix4f delta, Map<String, Vector3f> drawnCentres,
                      Map<Integer, QuestionView> askedQuestions) {
            String mine = piece.name;
            if (currentName != null && volumes > 0 && skipped == volumes) {
                arm.skippedFrames.merge(mine, 1, Integer::sum);
            }
            // The point this arm's own pass was asked about, read from where the frame path built it.
            //
            // Not from the recorder, and that is the lesson of two rounds: the recorder cannot know
            // which piece is asking, because `simulate` resolves every segment it is handed and a
            // per-segment loop therefore re-answers all 59 names 59 times. Three separate measurements
            // were lost to that, each producing a plausible table of one point repeated. The frame
            // path's own record is the only source that cannot be wrong about which piece a point
            // belongs to - and it now carries the pass it belongs to as well, so it cannot be wrong
            // about the frame either. See YsmDynamicBoneSolver#ProbeQuestion.
            YsmDynamicBoneSolver.SegmentState sim = state.states[index];
            QuestionView question = askedQuestions.get(index);
            Vector3f asked = question == null ? null : question.askedPoint();
            Vector3f drawn = drawnCentres.get(mine);
            if (asked != null && drawn != null) {
                arm.asked.put(mine, new Vector3f(asked));
                float gap = asked.distance(drawn);
                float[] acc = arm.questionedGap.computeIfAbsent(mine,
                        key -> new float[]{0.0F, 0.0F, 0.0F});
                acc[0] += gap;
                acc[1] = Math.max(acc[1], gap);
                acc[2] += 1.0F;
                if (gap > worstSeen) {
                    worstSeen = gap;
                    System.out.println("YSCOLLISION-POINT the widest gap so far: " + fmt(gap)
                            + " blocks, piece `" + mine + "`, arm "
                            + (passThrough ? "before" : "after") + "; asked " + point(asked)
                            + " drawn " + point(drawn));
                }
            }
            arm.contact.merge(mine, sim.lastContact, Math::max);
            arm.swing.merge(mine, (float) Math.toDegrees(sim.lastAngle), Math::max);
            if (!passThrough && drawn != null) {
                // The brief's own expression, on the same frame and the same drawn centre.
                float rest = testedCentre(piece, deformation, null, null).distance(drawn);
                arm.restGap.merge(mine, rest, Math::max);
            }
        }

        @Override
        public int count() {
            int answer = delegate.count();
            arm.countCalls++;
            arm.maxCount = Math.max(arm.maxCount, answer);
            return answer;
        }

        @Override
        public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
            volumes++;
            arm.skipCalls++;
            boolean skip = delegate.skipFor(pivot, restCentre, swingReach, index);
            if (skip) {
                skipped++;
            }
            return skip;
        }

        @Override
        public boolean resolve(Vector3f centre, Vector3f velocity, float radius, int index) {
            // Counted only. What the volume was asked about is read from the frame path's own record;
            // this wrapper's job is to prove the path ran and to count what it did. An earlier revision
            // also captured `centre` here, which was wrong twice over: it read the point after the
            // delegate had moved it, and it filed the answer under whichever piece the wrapper last
            // heard about rather than the one asking.
            arm.resolveCalls++;
            return delegate.resolve(centre, velocity, radius, index);
        }
    }
    /**
     * The tested point under a parent rotation: the solver's own {@code direction * lever} offset
     * added to the pivot, and the whole point turned by the ancestors' rotation.
     *
     * <p>{@code swing} is the quaternion the solver returned this frame - the rotation from the pose's
     * own rest direction to the direction it integrated - so {@code swing x bindRest} is the
     * direction {@code resolveCollisions} tests, in the model's frame. Read out of the solver rather
     * than re-derived: the point the metric is about is the one the collision test was actually asked
     * about.
     *
     * <p>This is the quantity {@code ParentFrameColliders} makes the solver resolve. It is rebuilt
     * here rather than read out of the solver because the point is built inside
     * {@code resolveCollisions} and never stored.
     */
    static Vector3f testedCentre(YsmLegSkirtCollisionProbeTest.Segment piece,
                                 OpenMatrix4f deformation, Quaternionf parentRotation,
                                 Quaternionf swing) {
        Vector3f pivot = YsmMeshSecondaryMotion.transformPoint(deformation, piece.bindPivot,
                new Vector3f());
        if (parentRotation != null) {
            pivot.rotate(parentRotation);
        }
        Vector3f rest = YsmMeshSecondaryMotion.transformDirection(deformation, piece.bindRest,
                new Vector3f());
        if (rest.lengthSquared() < 1.0E-8F) {
            return pivot;
        }
        rest.normalize();
        if (swing != null) {
            rest.rotate(swing);
        }
        if (parentRotation != null) {
            rest.rotate(parentRotation);
        }
        return pivot.add(rest.mul(piece.lever));
    }

    /** The cross-arm differences: what the fix changed, and what it must not have. */
    private static void finish(StateRun out, YsmLegSkirtCollisionProbeTest.Rig rig) {
        for (YsmLegSkirtCollisionProbeTest.Segment piece : rig.segments) {
            String name = piece.name;
            float[] b = out.before.questionedGap.get(name);
            float[] a = out.after.questionedGap.get(name);
            if (b == null || a == null) {
                continue;
            }
            if (piece.parent < 0) {
                if (b[1] > out.controlBefore) {
                    out.controlBefore = b[1];
                    out.controlPiece = name;
                }
                if (a[1] > out.controlAfter) {
                    out.controlAfter = a[1];
                    out.controlAfterPiece = name;
                }
            }
            if (out.controlCandidates.contains(name)) {
                out.controlBefore = Math.max(out.controlBefore, b[1]);
                out.controlAfter = Math.max(out.controlAfter, a[1]);
            }
            if (name.equals(out.smallestOffsetPiece)) {
                out.smallestOffsetGapBefore = b[1];
                out.smallestOffsetGapAfter = a[1];
            }
            if (b[1] > out.worstBefore) {
                out.worstBefore = b[1];
                out.worstBeforePiece = name;
            }
            if (a[1] > out.worstAfter) {
                out.worstAfter = a[1];
                out.worstAfterPiece = name;
            }
            float beforeContact = out.before.contact.getOrDefault(name, 0.0F);
            float afterContact = out.after.contact.getOrDefault(name, 0.0F);
            if ((beforeContact > 0.0F) != (afterContact > 0.0F)) {
                out.contactChanged.add(name);
            }
            if (beforeContact > 0.0F) {
                out.contactBefore++;
            }
            if (afterContact > 0.0F) {
                out.contactAfter++;
            }
            float beforeSwing = out.before.swing.getOrDefault(name, 0.0F);
            float afterSwing = out.after.swing.getOrDefault(name, 0.0F);
            if (Math.abs(beforeSwing - afterSwing) > 1.0E-3F) {
                out.swingChanged.add(name);
                out.worstSwingDelta = Math.max(out.worstSwingDelta,
                        Math.abs(beforeSwing - afterSwing));
            }
            int skippedBefore = out.before.skippedFrames.getOrDefault(name, 0);
            int skippedAfter = out.after.skippedFrames.getOrDefault(name, 0);
            if (skippedAfter > 0 && skippedBefore == 0) {
                out.newlySkipped.add(name);
            }
            if (skippedBefore > 0 && skippedAfter == 0) {
                out.noLongerSkipped.add(name);
            }
            // The red line: a piece neither arm's collision touched must be drawn in the same place.
            if (beforeContact <= 0.0F && afterContact <= 0.0F) {
                Vector3f was = out.before.lastDrawn.get(name);
                Vector3f now = out.after.lastDrawn.get(name);
                if (was != null && now != null) {
                    float drift = was.distance(now);
                    if (drift > out.worstUntouchedDrift) {
                        out.worstUntouchedDrift = drift;
                        out.worstUntouchedPiece = name;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The report
    // ------------------------------------------------------------------

    /**
     * The run's diagnostic lines, kept so they can be written to a file as well as printed.
     *
     * <p>Gradle swallows a test's stdout on this machine (the focused task prints the failure and
     * nothing else), and the per-line families below - `YSMCOLLISION-ORDER` above all - are the
     * evidence a reader has to be able to check for themselves rather than take on report. The dump is
     * written when this variable is set, so a normal suite run neither pays for it nor leaves a file
     * behind:
     *
     * <pre>
     *   $env:YSMEF_PROBE_DUMP = "build/reports/x.txt"
     *   gradlew test --tests ...YsmCollisionPointProbeTest
     * </pre>
     *
     * <p>An ENVIRONMENT variable and not a system property, deliberately: the tests run in a forked
     * JVM, and the fork inherits the environment while `-D` on the Gradle command line stays in the
     * Gradle JVM. The counterpart of that is that the path is resolved against the test worker's
     * working directory, which is this project's directory - the same root the report path uses.
     */
    private static final String DUMP_ENV = "YSMEF_PROBE_DUMP";

    private static final List<String> DIAGNOSTIC_LINES = new ArrayList<>();

    /**
     * Per `arm/piece`, the root-piece order proof: {@code anchorDrift} (must be zero - the mesh
     * applies the delta to the bind anchor and that point does not move), {@code radiusDrift} (must be
     * zero - the drawn offset is one map applied to both ends), the drawn radius, and the bind-space
     * `lever` it replaces. Written by {@code assertInstrument}, read by {@code diagnose} so it lands
     * in the report file rather than only on a swallowed stdout.
     */
    private static final Map<String, float[]> orderProofs = new LinkedHashMap<>();

    /** Record one diagnostic line: printed, and kept for {@link #dumpDiagnostics}. */
    private static void trace(String line) {
        System.out.println(line);
        DIAGNOSTIC_LINES.add(line);
    }

    /** Write the kept diagnostic lines, if the run asked for a file. Never fails the test. */
    private static void dumpDiagnostics() {
        String path = System.getenv(DUMP_ENV) == null ? "" : System.getenv(DUMP_ENV);
        if (path.isEmpty() || DIAGNOSTIC_LINES.isEmpty()) {
            return;
        }
        try {
            Path out = Paths.get(path);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, DIAGNOSTIC_LINES, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("YSMCOLLISION-DUMP failed: " + e);
        }
    }

    private static String section(StateRun run, YsmLegSkirtCollisionProbeTest.Rig rig) {
        List<YsmLegSkirtCollisionProbeTest.Segment> ordered = new ArrayList<>(rig.segments);
        ordered.sort(Comparator.comparingDouble(
                (YsmLegSkirtCollisionProbeTest.Segment piece) -> -run.before.worst(piece.name)));
        StringBuilder out = new StringBuilder();
        out.append("## ").append(run.name).append("\n\n")
                .append("`gap before` / `gap after` are the distance, in blocks, between the point the ")
                .append("collision test was asked about (the solver's own `com`, recorded where it is ")
                .append("built, turned by the ancestors' rotation on the shipped arm) and the centre of ")
                .append("mass the mesh draws. `rest before` is the same distance for the brief's own ")
                .append("expression, `deformation(bindPivot) + restDir*lever`, so this round's number ")
                .append("and the earlier round's are the same measurement. `contact` is the largest ")
                .append("collision turn the solver reported, blocks; `skipped` is the share of measured ")
                .append("frames in which `skipFor` refused every volume for that piece. `swing` is the ")
                .append("largest angle the solver granted, degrees. Sorted by the before gap.\n\n")
                .append("| piece | parent | gap before | gap after | rest before | contact before ")
                .append("| contact after | swing before | swing after | skipped before | skipped after |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (YsmLegSkirtCollisionProbeTest.Segment piece : ordered) {
            String name = piece.name;
            float[] b = run.before.questionedGap.get(name);
            float[] a = run.after.questionedGap.get(name);
            if (b == null || a == null) {
                continue;
            }
            out.append("| `").append(name).append("` | ")
                    .append(piece.parent < 0 ? "-" : String.valueOf(piece.parent)).append(" | ")
                    .append(fmt(b[1])).append(" | ").append(fmt(a[1])).append(" | ")
                    .append(fmt(run.restGap.getOrDefault(name, -1.0F))).append(" | ")
                    .append(fmt(run.before.contact.getOrDefault(name, 0.0F))).append(" | ")
                    .append(fmt(run.after.contact.getOrDefault(name, 0.0F))).append(" | ")
                    .append(one(run.before.swing.getOrDefault(name, 0.0F))).append(" | ")
                    .append(one(run.after.swing.getOrDefault(name, 0.0F))).append(" | ")
                    .append(share(run.before.skippedFrames.getOrDefault(name, 0), run.before.frames))
                    .append(" | ")
                    .append(share(run.after.skippedFrames.getOrDefault(name, 0), run.after.frames))
                    .append(" |\n");
        }
        out.append("\n**").append(run.name).append("**: ").append(run.pieces)
                .append(" pieces, ").append(run.chainedPieces)
                .append(" with a parent and ").append(run.rootPieces)
                .append(" without (the control, worst gap ").append(fmt(run.controlBefore))
                .append(" -> ").append(fmt(run.controlAfter)).append("). Worst tested-vs-drawn gap ")
                .append(fmt(run.worstBefore)).append(" -> ").append(fmt(run.worstAfter))
                .append(" blocks (`").append(run.worstBeforePiece).append("` -> `")
                .append(run.worstAfterPiece).append("`). Contact state differs for **")
                .append(run.contactChanged.size()).append("** piece(s)")
                .append(list(run.contactChanged)).append("; newly skipped in full: **")
                .append(run.newlySkipped.size()).append("**").append(list(run.newlySkipped))
                .append("; no longer skipped: ").append(run.noLongerSkipped.size())
                .append(list(run.noLongerSkipped)).append(". Granted swing differs for **")
                .append(run.swingChanged.size()).append("** piece(s) (worst ")
                .append(one(run.worstSwingDelta)).append(" deg)")
                .append(list(run.swingChanged)).append(". Pieces with contact: ")
                .append(run.contactBefore).append(" -> ").append(run.contactAfter)
                .append(". Largest drawn move of a piece neither arm touched: **")
                .append(fmt(run.worstUntouchedDrift)).append("** blocks")
                .append(run.worstUntouchedPiece == null ? ""
                        : " (`" + run.worstUntouchedPiece + "`)")
                .append(". **The after arm against itself, same frames and pose: **")
                .append(fmt(run.worstReplayDrift)).append("** blocks")
                .append(run.worstReplayPiece == null ? "" : " (`" + run.worstReplayPiece + "`)")
                .append("; **two consecutive identical passes: **")
                .append(fmt(run.worstReplay2Drift)).append("** blocks")
                .append(run.worstReplay2Piece == null ? "" : " (`" + run.worstReplay2Piece + "`)")
                .append(" - the frame path's own determinism. Both must be zero: the first says an arm "
                        + "does not depend on which arms ran before it, the second says a pass does not "
                        + "contaminate the pass after it. A non-zero second reading means the state is "
                        + "static and every arm in this process is contaminated by construction. "
                        + "**The controlled pair** - two passes born at the first measured frame and "
                        + "advanced together on the same colliders, pose and frame - differs by **")
                .append(fmt(run.worstPairDrift)).append("** blocks")
                .append(run.worstPairPiece == null ? "" : " (`" + run.worstPairPiece + "`, frame "
                        + run.pairFirstFrame + ")")
                .append("; the same pair with the whole history behind it differs by **")
                .append(fmt(run.worstTwinDrift)).append("** blocks")
                .append(run.worstTwinPiece == null ? "" : " (`" + run.worstTwinPiece + "`, frame "
                        + run.twinFirstFrame + ")")
                .append(". Those two are the leak's own size: zero on both means the frame path is a "
                        + "function of its arguments and the `after`/`replay` reading is a difference "
                        + "in what the two states were handed, not one inherited from a pass. The two "
                        + "long-lived states agreed on every piece's direction up to frame **")
                .append(run.lastAgreementFrame).append("** and first disagreed on frame **")
                .append(run.firstDivergenceFrame).append("**")
                .append(run.firstDivergencePiece == null ? ""
                        : " (`" + run.firstDivergencePiece + "`: `" + run.firstDivergenceLive
                        + "` against `" + run.firstDivergenceReplay + "`, cross "
                        + fmt(run.firstDivergenceCross) + ")")
                .append(". Widest real separation between the two arms' directions on any piece on any "
                        + "measured frame: **")
                .append(fmt(run.worstDirectionCross)).append("** (sin of the angle)")
                .append(run.worstDirectionCrossPiece == null ? ""
                        : " (`" + run.worstDirectionCrossPiece + "`, frame "
                        + run.worstDirectionCrossFrame + ")")
                .append(" - the reading that decides whether a printed angle difference is a difference "
                        + "of trajectory or of `acos` losing its resolution at the antipode. ")
                .append("**The bare pair** - two states advanced in lockstep from frame 0, handed the "
                        + "real collider object itself rather than a recording wrapper, with nothing "
                        + "run or read between their two calls - first differs on frame **")
                .append(run.bareFirstFrame).append("**")
                .append(run.bareFirstPiece == null ? "" : " (`" + run.bareFirstPiece + "`)")
                .append(", widest separation **").append(fmt(run.worstBareCross))
                .append("** (sin of the angle)")
                .append(run.worstBarePiece == null ? "" : " (`" + run.worstBarePiece + "`, frame "
                        + run.worstBareFrame + ")")
                .append(". A zero here with a non-zero `after`-against-`replay` reading puts the "
                        + "carry in the probe's instrumentation and not in the frame path; a non-zero "
                        + "here puts it in the frame path itself. ")
                .append("Round 9 traced a non-zero reading here to the collision response reading a "
                        + "correction axis it had not built (see `resolveCollisions`); round 10 guarded "
                        + "that read, so the number this sentence used to quote is a defect that no "
                        + "longer exists rather than a reading that changed. ")
                .append("The two long-lived arms on the frames BELOW the warmup first differed on "
                        + "frame **")
                .append(run.earlyFirstDivergenceFrame).append("**")
                .append(run.earlyFirstDivergencePiece == null ? ""
                        : " (`" + run.earlyFirstDivergencePiece + "`)")
                .append(", widest separation there **").append(fmt(run.earlyWorstCross))
                .append("** (sin of the angle)")
                .append(run.earlyWorstCrossPiece == null ? ""
                        : " (`" + run.earlyWorstCrossPiece + "`, frame " + run.earlyWorstCrossFrame + ")")
                .append(". ")
                .append(". A disagreement on the first measured frame has no earlier frame of either "
                        + "state's own to inherit it from. ")
                .append(run.firstDivergenceDetail == null ? "" : "Solver state there - "
                        + run.firstDivergenceDetail + ". ")
                .append("What the two states were HANDED first "
                        + "differed on frame **")
                .append(run.firstInputDivergenceFrame).append("**")
                .append(run.firstInputDivergencePiece == null ? ""
                        : " (`" + run.firstInputDivergencePiece + "`, snapshot slot "
                        + run.firstInputDivergenceSlot + ": `" + run.firstInputDivergenceLive
                        + "` against `" + run.firstInputDivergenceReplay + "`)")
                .append(". Slot order is the deformation's first row (0-3), the pivot (4-6), the rest "
                        + "direction (7-9); `-1` means every input agreed on every measured frame, "
                        + "which puts the whole difference inside the solver.\n\n")
                .append(divergenceOf(run));
        return out.toString();
    }

    private static String list(Set<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(" (");
        int shown = 0;
        for (String name : names) {
            if (shown++ > 0) {
                out.append(", ");
            }
            if (shown > 9) {
                out.append("...");
                break;
            }
            out.append(name);
        }
        return out.append(')').toString();
    }

    private static String one(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String share(int frames, int total) {
        return total <= 0 ? "-" : String.format(Locale.ROOT, "%.3f", (float) frames / total);
    }

    static String fmt(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** The widest gap seen so far this run, so only new extremes are narrated. */
    private static float worstSeen;

    private static String point(Vector3f v) {
        return String.format(Locale.ROOT, "(%.4f,%.4f,%.4f)", v.x, v.y, v.z);
    }

    /**
     * Six decimals, for the small readings. {@link #fmt} rounds to the millimetre, which is right for
     * the distances this report is mostly about and useless for asking whether a quantity is zero: a
     * "fixed point" that moves 0.0004 blocks prints as 0.000 and a matrix difference of 1e-5 prints
     * as 0.000, and both of those are exactly the questions R5 is asking.
     */
    static String colon(float value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    /** The largest absolute difference between two 4x4 matrices, element by element. */
    static float maxAbsDifference(Matrix4f a, Matrix4f b) {
        float worst = 0.0F;
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                worst = Math.max(worst, Math.abs(element(a, column, row) - element(b, column, row)));
            }
        }
        return worst;
    }

    private static float element(Matrix4f m, int column, int row) {
        switch (column * 4 + row) {
            case 0: return m.m00(); case 1: return m.m01(); case 2: return m.m02();
            case 3: return m.m03();
            case 4: return m.m10(); case 5: return m.m11(); case 6: return m.m12();
            case 7: return m.m13();
            case 8: return m.m20(); case 9: return m.m21(); case 10: return m.m22();
            case 11: return m.m23();
            case 12: return m.m30(); case 13: return m.m31(); case 14: return m.m32();
            default: return m.m33();
        }
    }

    /**
     * A BIND-space transform written in the MODEL frame: {@code D x A x D^-1}.
     *
     * <p>The mesh draws a part as {@code deformation x delta x vertex}, so a delta built by
     * {@code buildSegmentDelta} - which is {@code T(bindAnchor) x R x T(-bindAnchor)}, in bind space -
     * acts on bind-space vertices and reaches the model frame only through the deformation.
     * "Apply the delta to a posed point" and "apply the deformation to the delta's image of the bind
     * point" are therefore different maps, and this method is the composition that makes them the
     * same one: for any posed point {@code y = D(x)},
     * {@code (D A D^-1)(y) = D(A(x))}.
     *
     * <p>The deformation must be invertible for this to exist. It is a joint's {@code pose x toOrigin}
     * on this rig; the caller is measuring whether that is rigid, which is the reason the inverse is
     * taken here rather than assumed to be a transpose.
     */
    static OpenMatrix4f conjugatedIntoModelSpace(OpenMatrix4f deformation, Matrix4f delta) {
        Matrix4f d = new Matrix4f(
                deformation.m00, deformation.m01, deformation.m02, deformation.m03,
                deformation.m10, deformation.m11, deformation.m12, deformation.m13,
                deformation.m20, deformation.m21, deformation.m22, deformation.m23,
                deformation.m30, deformation.m31, deformation.m32, deformation.m33);
        Matrix4f composed = new Matrix4f(d).mul(delta).mul(new Matrix4f(d).invert());
        OpenMatrix4f out = new OpenMatrix4f();
        out.m00 = composed.m00(); out.m01 = composed.m01(); out.m02 = composed.m02();
        out.m03 = composed.m03();
        out.m10 = composed.m10(); out.m11 = composed.m11(); out.m12 = composed.m12();
        out.m13 = composed.m13();
        out.m20 = composed.m20(); out.m21 = composed.m21(); out.m22 = composed.m22();
        out.m23 = composed.m23();
        out.m30 = composed.m30(); out.m31 = composed.m31(); out.m32 = composed.m32();
        out.m33 = composed.m33();
        return out;
    }

    private static double swingDegrees(Matrix4f m) {
        float trace = m.m00() + m.m11() + m.m22();
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, (trace - 1.0) * 0.5))));
    }

    // ------------------------------------------------------------------
    // The poses
    // ------------------------------------------------------------------

    /**
     * The pose source: Epic Fight's own clip where the drive names one, the rounds' synthetic gait
     * otherwise. Both are the earlier probes' own recipes - the clip interpolated between its
     * bracketing keyframes and held past the last one, the synthetic gait rotating the torso, the
     * thighs and the shins about the model's own joint origins.
     */
    private interface Pose {
        void at(float seconds);

        OpenMatrix4f poseOf(int joint);
    }

    private static final class ClipPose implements YsmMeshSecondaryMotion.PoseSource {
        private final YsmLegSkirtCollisionProbeTest.Rig rig;
        private final Animation animation;
        private final SyntheticGait gait;
        private float seconds;

        ClipPose(YsmLegSkirtCollisionProbeTest.Rig rig, Drive drive) {
            this.rig = rig;
            try {
                if (drive.clip() == null) {
                    this.animation = null;
                    this.gait = new SyntheticGait(rig);
                } else {
                    this.animation = Animation.parse(resource(drive.clip()), rig, drive.clip());
                    this.gait = null;
                }
            } catch (IOException e) {
                throw new IllegalStateException("the bundled clip " + drive.clip()
                        + " could not be read", e);
            }
            at(0.0F);
        }

        void at(float seconds) {
            this.seconds = seconds;
            if (gait != null) {
                gait.at(seconds);
            }
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            if (gait != null) {
                return gait.poseOf(joint);
            }
            OpenMatrix4f[] sheet = animation.worlds.get(joint);
            if (sheet == null || sheet.length == 0) {
                return null;
            }
            return sheet[animation.sampleAt(seconds)];
        }

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return rig.toOriginOf(joint);
        }
    }

    /** The rounds' synthetic gait: torso lean, thigh stride, knee bend, bob. */
    private static final class SyntheticGait {
        private final YsmLegSkirtCollisionProbeTest.Rig rig;
        private final OpenMatrix4f[] poses = new OpenMatrix4f[JointTable.COUNT];

        SyntheticGait(YsmLegSkirtCollisionProbeTest.Rig rig) {
            this.rig = rig;
            for (int i = 0; i < poses.length; i++) {
                poses[i] = new OpenMatrix4f();
            }
            at(0.0F);
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
                            -SYNTH_LEAN * 0.45F + 3.5F * (float) Math.sin(2.0F * phase + 1.0F), 0.0F),
                    new OpenMatrix4f());
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

        OpenMatrix4f poseOf(int joint) {
            return joint >= 0 && joint < poses.length ? poses[joint] : null;
        }
    }

    /**
     * One Epic Fight clip, sampled once per 60 Hz frame by the loader's own recipe: the local used is
     * {@code invert(joint local) x keyframe} (with the Root's own coordinate correction), and the
     * world is the chain composed from the Root down. Interpolated between the bracketing keyframes by
     * choosing the earlier one, which is what the earlier rounds' probe does and what makes its
     * numbers the ones this round is compared against.
     */
    private static final class Animation {
        private static final Matrix4f BLENDER_TO_MINECRAFT =
                new Matrix4f(1.0F, 0.0F, 0.0F, 0.0F,
                        0.0F, 0.0F, -1.0F, 0.0F,
                        0.0F, 1.0F, 0.0F, 0.0F,
                        0.0F, 0.0F, 0.0F, 1.0F);

        private final Map<Integer, OpenMatrix4f[]> worlds = new LinkedHashMap<>();
        private final float duration;

        private Animation(float duration) {
            this.duration = duration;
        }

        int sampleAt(float seconds) {
            float time = duration > 0.0F ? seconds % duration : seconds;
            return Math.max(0, Math.round(time / DT));
        }

        static Animation parse(JsonObject doc, YsmLegSkirtCollisionProbeTest.Rig rig, String path)
                throws IOException {
            JsonArray nodes = doc.getAsJsonArray("animation");
            assertNotNull(nodes, path + " has no 'animation' array");
            Map<Integer, List<float[]>> keyTimes = new LinkedHashMap<>();
            Map<Integer, List<Matrix4f>> keyMatrices = new LinkedHashMap<>();
            float last = 0.0F;
            for (JsonElement element : nodes) {
                JsonObject node = element.getAsJsonObject();
                int joint = JointTable.idOf(node.get("name").getAsString());
                assertTrue(joint >= 0, path + " names a joint this mod's table does not have: "
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
                    Matrix4f raw = new Matrix4f(matrixOf(transformArray.get(i).getAsJsonArray()))
                            .transpose();
                    if (joint == JointTable.ROOT) {
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
            Animation animation = new Animation(last);
            Map<Integer, OpenMatrix4f[]> locals = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<float[]>> entry : keyTimes.entrySet()) {
                int joint = entry.getKey();
                Joint skeleton = rig.jointById(joint);
                if (skeleton == null) {
                    continue;
                }
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
                    OpenMatrix4f local = joint == JointTable.ROOT
                            ? toOpen(keyframe)
                            : OpenMatrix4f.mul(inverseLocal, toOpen(keyframe), null);
                    sheet[i] = local;
                }
                locals.put(joint, sheet);
            }
            OpenMatrix4f[] rootWorld = new OpenMatrix4f[sampleCount];
            Arrays.setAll(rootWorld, i -> new OpenMatrix4f());
            compose(rig.armatureRoot(), rootWorld, locals, animation.worlds, sampleCount);
            return animation;
        }

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

    private static OpenMatrix4f about(Vector3f origin, float degrees, float bob) {
        Matrix4f matrix = new Matrix4f()
                .translate(origin.x, origin.y + bob, origin.z)
                .rotateX((float) Math.toRadians(degrees))
                .translate(-origin.x, -origin.y, -origin.z);
        return toOpen(matrix);
    }

    private static OpenMatrix4f toOpen(Matrix4f matrix) {
        // Field for field: both types address their fields column-first with the translation at
        // m30/m31/m32, so this is a copy and not a transpose.
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

    private static Matrix4f matrixOf(JsonArray values) {
        return new Matrix4f(
                values.get(0).getAsFloat(), values.get(4).getAsFloat(),
                values.get(8).getAsFloat(), values.get(12).getAsFloat(),
                values.get(1).getAsFloat(), values.get(5).getAsFloat(),
                values.get(9).getAsFloat(), values.get(13).getAsFloat(),
                values.get(2).getAsFloat(), values.get(6).getAsFloat(),
                values.get(10).getAsFloat(), values.get(14).getAsFloat(),
                values.get(3).getAsFloat(), values.get(7).getAsFloat(),
                values.get(11).getAsFloat(), values.get(15).getAsFloat());
    }

    private static JsonObject resource(String path) throws IOException {
        try (InputStream stream = YsmCollisionPointProbeTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "the bundled resource " + path + " is not on the test classpath");
            byte[] bytes = stream.readAllBytes();
            String expected = CLIP_SHA256.get(path);
            if (expected != null) {
                assertTrue(expected.equalsIgnoreCase(sha256Of(bytes)),
                        "the bundled clip " + path + " is not the deployed jar's own revision");
            }
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static String sha256Of(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest(bytes)) {
                out.append(String.format(Locale.ROOT, "%02X", value));
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    private static Path deployed(String relative) {
        String root = System.getProperty("ysmef.ysm.configRoot", "");
        if (root.isEmpty()) {
            String fromEnvironment = System.getenv("YSMEF_YSM_CONFIG_ROOT");
            root = fromEnvironment == null ? "" : fromEnvironment;
        }
        if (root.isEmpty()) {
            return null;
        }
        Path config = Paths.get(root).toAbsolutePath().getParent();
        Path file = config.resolve("ysm_epicfight_compat").resolve("resourcepack")
                .resolve("assets").resolve("ysm_epicfight_compat").resolve(relative);
        return Files.isRegularFile(file) ? file : null;
    }

    private static List<Path> corpusFiles() {
        String root = System.getenv(CORPUS_ROOT);
        if (root == null || root.isEmpty()) {
            return null;
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".ysm"))
                    .forEach(out::add);
        } catch (IOException e) {
            return null;
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    // ------------------------------------------------------------------
    // 2. The blast radius over the corpus
    // ------------------------------------------------------------------

    @Test
    void whatMovingTheTestPointDoesOverTheCorpus() throws Exception {
        List<Path> files = corpusFiles();
        assumeTrue(files != null, "set " + CORPUS_ROOT + " to sweep the model corpus");
        StringBuilder report = new StringBuilder();
        report.append("# The blast radius of moving the collision test point\n\n")
                .append("Every parseable package in the corpus, assembled through the production seams ")
                .append("(`YsmLegSkirtCollisionProbeTest.Rig.fromRawModel`) and driven by the rounds' ")
                .append("synthetic gait, ").append(CORPUS_FRAMES).append(" frames of ")
                .append(fmt(DT)).append(" s, the first ").append(CORPUS_WARMUP)
                .append(" skipped. Both arms run the production frame loop: the identity wrapper ")
                .append("(today) against production's own parent-frame wrapper (this round).\n\n")
                .append("| | models | pieces | chained | contact changed | newly skipped | no longer ")
                .append("skipped | swing changed | untouched drift (max) |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");

        int parsed = 0;
        int failed = 0;
        int withPieces = 0;
        int pieces = 0;
        int chained = 0;
        int contactChanged = 0;
        int newlySkipped = 0;
        int noLongerSkipped = 0;
        int swingChanged = 0;
        float worstUntouched = 0.0F;
        String worstUntouchedModel = null;
        float worstGapBefore = 0.0F;
        float worstGapAfter = 0.0F;
        String worstGapModel = null;
        List<String> changedModels = new ArrayList<>();
        int number = 0;
        for (Path file : files) {
            YSMGeoModel model;
            float scaleW;
            float scaleH;
            try {
                YsmBinaryReader.BinaryModel binary = YsmBinaryReader.read(
                        YsmFileCrypto.decryptYsmFile(Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
                scaleW = binary.widthScale;
                scaleH = binary.heightScale;
            } catch (Throwable t) {
                failed++;
                continue;
            }
            parsed++;
            String stem = file.getFileName().toString();
            YsmLegSkirtCollisionProbeTest.Rig rig;
            try {
                rig = YsmLegSkirtCollisionProbeTest.fromRawModel(model, scaleW, scaleH, stem);
            } catch (Throwable t) {
                failed++;
                parsed--;
                continue;
            }
            if (rig.segments.isEmpty() || rig.colliders() == null || rig.colliders().count() == 0) {
                continue;
            }
            withPieces++;
            StateRun run;
            try {
                YsmMeshSecondaryMotion.clear();
                run = measure(rig, Drive.synthetic("synthetic walk"), CORPUS_FRAMES, CORPUS_WARMUP);
            } catch (Throwable t) {
                failed++;
                withPieces--;
                continue;
            }
            pieces += run.pieces;
            chained += run.chainedPieces;
            contactChanged += run.contactChanged.size();
            newlySkipped += run.newlySkipped.size();
            noLongerSkipped += run.noLongerSkipped.size();
            swingChanged += run.swingChanged.size();
            if (run.worstUntouchedDrift > worstUntouched) {
                worstUntouched = run.worstUntouchedDrift;
                worstUntouchedModel = stem + "/" + run.worstUntouchedPiece;
            }
            if (run.worstBefore > worstGapBefore) {
                worstGapBefore = run.worstBefore;
                worstGapModel = stem + "/" + run.worstBeforePiece;
            }
            worstGapAfter = Math.max(worstGapAfter, run.worstAfter);
            if (!run.contactChanged.isEmpty() || !run.newlySkipped.isEmpty()
                    || !run.noLongerSkipped.isEmpty() || !run.swingChanged.isEmpty()) {
                if (changedModels.size() < 80) {
                    changedModels.add("| `" + stem + "` | " + run.pieces + " | " + run.chainedPieces
                            + " | " + run.contactChanged.size() + " | " + run.newlySkipped.size()
                            + " | " + run.noLongerSkipped.size() + " | " + run.swingChanged.size()
                            + " | " + fmt(run.worstUntouchedDrift) + " |");
                }
            }
            if (++number % 100 == 0) {
                System.out.println("[collision point sweep] models=" + number + " pieces=" + pieces
                        + " contactChanged=" + contactChanged + " newlySkipped=" + newlySkipped);
            }
        }
        report.append("| all | ").append(withPieces).append(" | ").append(pieces).append(" | ")
                .append(chained).append(" | ").append(contactChanged).append(" | ")
                .append(newlySkipped).append(" | ").append(noLongerSkipped).append(" | ")
                .append(swingChanged).append(" | ").append(fmt(worstUntouched)).append(" |\n\n")
                .append("Corpus: ").append(files.size()).append(" packages, ").append(parsed)
                .append(" parsed and assembled, ").append(failed).append(" unusable. Worst ")
                .append("tested-vs-drawn gap ").append(fmt(worstGapBefore)).append(" -> ")
                .append(fmt(worstGapAfter)).append(" blocks (`").append(worstGapModel)
                .append("`). Largest drawn move of a piece neither arm touched: ")
                .append(fmt(worstUntouched)).append(" blocks (`").append(worstUntouchedModel)
                .append("`).\n\n");
        if (!changedModels.isEmpty()) {
            report.append("### Models where the arms disagree\n\n")
                    .append("| model | pieces | chained | contact changed | newly skipped | no longer ")
                    .append("skipped | swing changed | untouched drift |\n")
                    .append("|---|---|---|---|---|---|---|---|\n");
            for (String row : changedModels) {
                report.append(row).append('\n');
            }
            report.append('\n');
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        assertTrue(parsed > 500, "the corpus sweep must parse the repository, parsed=" + parsed);
        assertTrue(pieces > 1000, "the sweep must assemble pieces, pieces=" + pieces);
    }
}
