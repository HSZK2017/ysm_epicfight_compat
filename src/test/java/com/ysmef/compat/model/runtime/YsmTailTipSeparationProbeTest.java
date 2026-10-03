package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.JointTable;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * The tail <b>tip</b>: does the shipped per-link ceiling draw the chain apart, and what does removing
 * it cost?
 *
 * <h2>The mechanism being measured</h2>
 *
 * <p>The per-bone ceiling in {@code physics_overrides/<model>.json} is applied inside
 * {@code YsmMeshSecondaryMotion#resolveSegment} to the <b>drawn</b> swing: the solver's quaternion is
 * slerped back toward the identity until it is no longer wider than the ceiling, and nothing is
 * written back into the pendulum state. Each link's delta is then composed under its parent's and
 * applied about <b>that link's own</b> anchor. So a link that is capped while its own physics asks for
 * far more is drawn at a joint angle its parent's drawn pose does not share, and the chain is drawn
 * with a kink at every capped joint.
 *
 * <p>Two consequences are separable, and this class measures both:
 *
 * <ul>
 *   <li><b>separation</b> - the distance the drawn chain opens between a link's own attachment vertex
 *       and the link above it, in blocks. A continuous tail has this at zero; a tail whose links are
 *       each drawn short of where their parents put them has it at the sum of the shortfalls.</li>
 *   <li><b>excursion</b> - how far and how fast the tip is drawn, which is the earlier complaint
 *       ("the tip swings all over the place"). A cap that is removed buys separation back with
 *       excursion, and the numbers below are the exchange rate.</li>
 * </ul>
 */
class YsmTailTipSeparationProbeTest {

    private static final String MAID = "wine_fox/01_taisho_maid";

    private static final String LOGGED_MAID_SIMULATED =
            "RB3, RB2, FL1, FL2, RB, RF, RF3, RF2, RM, RM2, RM3, LongRightHair, LongRightHair2, FM2, "
            + "BL, BL3, BL2, FM1, BM, BM2, BM3, BR, BR3, BR2, Tail, Tail5, Tail4, Tail7, Tail6, Tail3, "
            + "Tail2, FFM1, FFM1_1, FFM2, FFM2_1, FFM3, FFM3_1, LongLeftHair, LongLeftHair2, "
            + "RightSideHair, LongHair2, LongHair, FL, FM, FR, FR1, FR2, LM2, LM3, Bangs, LeftSideHair, "
            + "BaseHair, LB, LB3, LB2, LF, LF3, LF2, LM";

    /** The tail, root first. The links the shipped ceilings name are the last three. */
    private static final String[] TAIL = {
            "Tail", "Tail2", "Tail3", "Tail4", "Tail5", "Tail6", "Tail7"};

    private static final float DT = 1.0F / 60.0F;
    private static final int FRAMES = 600;
    private static final int WARMUP = 200;
    private static final float[] NO_TURN = {0.0F, 0.0F};

    /** The two gaits the defect was reported in. */
    private static final float SPRINT_LEAN = 17.0F;
    private static final float SPRINT_SPEED = 5.612F;
    private static final float WALK_LEAN = 6.0F;
    private static final float WALK_SPEED = 4.317F;

    /**
     * The candidates, as the shipped override file's own `limitDeg` maps. `none` is the file before
     * the tip round; `shipped` is the file deployed now; the rest walk the ceilings back up.
     */
    private static final Object[][] CANDIDATES = {
            {"none (no ceilings)", Map.of()},
            {"shipped: Tail5..7 = 8", caps(8.0F)},
            {"Tail5..7 = 6", caps(6.0F)},
            {"Tail5..7 = 10", caps(10.0F)},
            {"Tail5..7 = 12", caps(12.0F)},
            {"Tail5..7 = 16", caps(16.0F)},
            {"Tail5..7 = 24", caps(24.0F)},
            {"Tail6..7 = 8 (Tail5 free)", new LinkedHashMap<>(Map.of("tail6", 8.0F, "tail7", 8.0F))},
            {"Tail7 = 8 only", new LinkedHashMap<>(Map.of("tail7", 8.0F))},
            {"Tail5..7 = 4", caps(4.0F)},
    };

    private static Map<String, Float> caps(float degrees) {
        Map<String, Float> out = new LinkedHashMap<>();
        out.put("tail5", degrees);
        out.put("tail6", degrees);
        out.put("tail7", degrees);
        return out;
    }

    @Test
    void theTailTipUnderTheCeilingsAndWithoutThem() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");

        Rig rig = Rig.load(pack, MAID, LOGGED_MAID_SIMULATED);
        StringBuilder report = new StringBuilder();
        report.append("# The tail tip: separation and excursion under the per-link ceilings\n\n")
                .append("Model `").append(rig.modelId).append("`, ").append(rig.segments.size())
                .append(" simulated pieces. The production frame loop over the sprint gait, ")
                .append(FRAMES).append(" frames of ").append(fmt(DT)).append(" s, the first ")
                .append(WARMUP).append(" skipped. `separation` is the distance the **drawn** chain ")
                .append("opens between a link's own nearest vertex to the link above and that link's ")
                .append("own, in blocks: a continuous chain reads 0.000.\n\n");

        for (Object[] candidate : CANDIDATES) {
            String label = (String) candidate[0];
            @SuppressWarnings("unchecked")
            Map<String, Float> limits = (Map<String, Float>) candidate[1];
            Run run = run(rig, limits);
            report.append(run.report(label));
        }

        Path out = Paths.get("build", "reports", "ysm-tail-tip-separation.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // ---- the assertions ---------------------------------------------------------------------
        // The metric's own control, run first: with the pose at rest and every delta the identity,
        // the chain is drawn exactly as the model was built, so the separation must read zero. A
        // metric that fires here is measuring the bind pose and not the defect.
        for (int i = 1; i < rig.tail.size(); i++) {
            float control = rig.separation(rig.tail.get(i), new Matrix4f(), rig.tail.get(i - 1),
                    new Matrix4f(), rig.restPose());
            assertEquals(0.0F, control, 1.0E-4F,
                    "the separation metric must read exactly zero on a chain drawn as built; "
                            + rig.tail.get(i).name + " reads " + control);
        }

        Run shipped = run(rig, caps(8.0F));
        Run none = run(rig, Map.of());
        assertNotNull(shipped.worstLink, "the shipped file must have a worst link");
        // The finding this round exists to report: at the TIP's own joint the shipped ceilings draw
        // the chain further apart than leaving the tail alone does. The assertion states the
        // measured fact and fails if a later change makes it false in either direction.
        assertTrue(shipped.tipSeparation > none.tipSeparation,
                "the shipped ceilings must draw the tip's own joint further apart than no ceilings: "
                        + "none " + fmt(none.tipSeparation) + " against shipped "
                        + fmt(shipped.tipSeparation));
        assertTrue(shipped.tipSpeed > 0.0F, "the tip must be drawn moving");
    }

    // ==================================================================
    // This round: the tip measured the way the renderer draws it
    // ==================================================================

    private static final float TIP_STEADY_SECONDS = 4.0F;
    private static final float TIP_WARMUP_SECONDS = 2.0F;
    private static final float TIP_SWAY_BEFORE = 2.5F;
    private static final float TIP_SWAY_RAMP = 0.35F;
    private static final float TIP_SWAY_AFTER = 3.0F;

    /**
     * The states this round is measured in. `stop-sway` is the report's own case: a sprinting body
     * stops and the torso comes back upright over {@link #TIP_SWAY_RAMP} seconds. `yaw-sway` is the
     * other reading of "as the body sways": the body turns left and right while walking, and the turn
     * is fed to the solver as the turn rate and acceleration it reads, which is the only state here
     * where the tail feels the body <b>rotating</b> rather than translating. `fall` is the body
     * dropping with the tail hanging free.
     */
    private static final String[] TIP_STATES =
            {"idle", "walk", "sprint", "fall", "stop-sway", "yaw-sway"};

    /** The body's own turn in {@code yaw-sway}: how far it swings, and how fast. */
    private static final float YAW_SWING_DEGREES = 35.0F;
    private static final float YAW_HZ = 0.8F;

    /** One candidate: the override file's ceilings, and a chain total for the tail's own piece. */
    private record Setup(String label, Map<String, Float> limits, float chainTotalDeg,
                         boolean totalIsTailOnly) {
        static Setup shipped() {
            return new Setup("shipped (Tail<=12)", Map.of("tail", 12.0F), 0.0F, true);
        }

        static Setup total(float degrees) {
            return new Setup("chain total " + fmt(degrees) + " for the tail",
                    Map.of("tail", 12.0F), degrees, true);
        }

        static Setup totalWholeModel(float degrees) {
            return new Setup("chain total " + fmt(degrees) + " for every piece",
                    Map.of("tail", 12.0F), degrees, false);
        }

        static Setup noTailCap() {
            return new Setup("no Tail cap", Map.of(), 0.0F, true);
        }
    }

    /** The candidates this round measures, in the order the report prints them. */
    private static final Setup[] TIP_SETUPS = {
            Setup.shipped(),
            Setup.noTailCap(),
            Setup.total(160.0F),
            Setup.total(200.0F),
            Setup.total(240.0F),
            Setup.total(320.0F),
            Setup.totalWholeModel(240.0F),
    };

    /** What one state measured, for one candidate. */
    private static final class Tip {
        final Map<String, Float> render = new LinkedHashMap<>();
        final Map<String, Float> probe = new LinkedHashMap<>();
        final Map<String, Float> own = new LinkedHashMap<>();
        final Map<String, Float> allowed = new LinkedHashMap<>();
        final Map<String, Float> raw = new LinkedHashMap<>();
        final Map<String, Float> range = new LinkedHashMap<>();
        final Map<String, Float> composed = new LinkedHashMap<>();
        final Map<String, Float> pinned = new LinkedHashMap<>();
        final Map<String, Float> counts = new LinkedHashMap<>();
        final Map<String, Float> minOwn = new LinkedHashMap<>();
        final Map<String, Float> maxOwn = new LinkedHashMap<>();
        /** The closest approach of the two drawn clouds, over and above the bind one, blocks. */
        final Map<String, Float> near = new LinkedHashMap<>();
        /**
         * How far the link's own farthest vertex is drawn from where its parent's delta alone would
         * put it, blocks. One composed delta per link means this is the link's own rotation of that
         * vertex, bounded by {@code 2 |v - hinge| sin(theta/2)}; anything larger means the link is
         * not carried by the link above it.
         */
        final Map<String, Float> step = new LinkedHashMap<>();
        /** The angle of the rotation the link's own delta actually applies, degrees. */
        final Map<String, Float> applied = new LinkedHashMap<>();
        /** How far that rotation's centre is from the link's own hinge, blocks. */
        final Map<String, Float> centre = new LinkedHashMap<>();
        float tipSpeed;
        float tipSpread;
        float tipGap;
        float tipGapProbe;
        float tipNear;
        float overshoot = Float.NaN;
        float settleSeconds = Float.NaN;
        float stepBlocks = Float.NaN;
        float cost;
        /** One drawn point per non-tail piece per recorded frame, for the blast-radius comparison. */
        final List<Vector3f[]> trace = new ArrayList<>();

        String line() {
            return String.format(Locale.ROOT,
                    "%s | %s | %s | %s | %s | %s | %s | %s | %s | %s",
                    fmt(render.getOrDefault("Tail7", 0.0F)), fmt(render.getOrDefault("Tail5", 0.0F)),
                    fmt(probe.getOrDefault("Tail7", 0.0F)), fmt(near.getOrDefault("Tail7", 0.0F)),
                    fmt(composed.getOrDefault("Tail7", 0.0F)),
                    fmt(range.getOrDefault("Tail2", 0.0F)), fmt(pinned.getOrDefault("Tail2", 0.0F)),
                    fmt(tipSpeed), fmt(overshoot), fmt(settleSeconds));
        }
    }

    /**
     * The body's pose for the states above: the torso leaning, the chest and head following it, and
     * the legs cycling or still. A still-legged variant is what an idle or a fall is - the tail hangs
     * from the torso, so what it feels is the lean and the velocity, not the gait's leg swing.
     */
    private static final class TipPose implements YsmMeshSecondaryMotion.PoseSource {
        private static final OpenMatrix4f TO_ORIGIN = new OpenMatrix4f();
        private final Rig rig;
        private float legs;
        private float bob;
        private float lean;
        private OpenMatrix4f torso = new OpenMatrix4f();
        private OpenMatrix4f chest = new OpenMatrix4f();
        private OpenMatrix4f head = new OpenMatrix4f();
        private OpenMatrix4f thighRight = new OpenMatrix4f();
        private OpenMatrix4f thighLeft = new OpenMatrix4f();
        private OpenMatrix4f legRight = new OpenMatrix4f();
        private OpenMatrix4f legLeft = new OpenMatrix4f();
        private float yaw;

        TipPose(Rig rig, float lean, boolean cycle) {
            this.rig = rig;
            this.legs = cycle ? 1.0F : 0.0F;
            this.bob = cycle ? 1.0F : 0.0F;
            this.lean = lean;
            at(0.0F);
        }

        /** The body's own turn this frame, degrees: the pose turns, and the solver is told. */
        void yaw(float degrees) {
            this.yaw = degrees;
        }

        /** The body's state this frame: the lean, and how much the legs and the bob are moving. */
        void drive(float leanDegrees, float legAmplitude, float bobAmplitude) {
            this.lean = leanDegrees;
            this.legs = legAmplitude;
            this.bob = bobAmplitude;
        }

        void at(float seconds) {
            float phase = (float) (2.0D * Math.PI * 1.60D * seconds);
            float bobOffset = bob * 0.040F * (float) Math.sin(2.0F * phase);
            OpenMatrix4f pitch = about(rig.jointOrigin(JointTable.TORSO), lean, bobOffset);
            torso = yaw == 0.0F ? pitch : OpenMatrix4f.mul(
                    yawAbout(rig.jointOrigin(JointTable.TORSO), yaw), pitch, new OpenMatrix4f());
            chest = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.CHEST),
                            lean * 0.55F + bob * 2.5F * (float) Math.sin(2.0F * phase),
                            bobOffset * 0.5F),
                    new OpenMatrix4f());
            head = OpenMatrix4f.mul(chest,
                    about(rig.jointOrigin(JointTable.HEAD),
                            -lean * 0.45F + bob * 3.5F * (float) Math.sin(2.0F * phase + 1.0F), 0.0F),
                    new OpenMatrix4f());
            thighRight = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.THIGH_R),
                            legs * 34.0F * (float) Math.sin(phase), 0.0F), new OpenMatrix4f());
            thighLeft = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.THIGH_L),
                            legs * 34.0F * (float) Math.sin(phase + Math.PI), 0.0F),
                    new OpenMatrix4f());
            legRight = OpenMatrix4f.mul(thighRight,
                    about(rig.jointOrigin(JointTable.LEG_R),
                            legs * 28.0F * (float) Math.sin(phase + 2.0F), 0.0F),
                    new OpenMatrix4f());
            legLeft = OpenMatrix4f.mul(thighLeft,
                    about(rig.jointOrigin(JointTable.LEG_L),
                            legs * 28.0F * (float) Math.sin(phase + Math.PI + 2.0F), 0.0F),
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

    @Test
    void theTipAsTheRendererDrawsIt() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");

        Rig rig = Rig.load(pack, MAID, LOGGED_MAID_SIMULATED);
        StringBuilder report = new StringBuilder();
        report.append("# The tail tip on the renderer's own chain\n\n")
                .append("Model `").append(rig.modelId).append("`, ").append(rig.segments.size())
                .append(" simulated pieces. `render` is the gap at a joint with the **one** composed ")
                .append("delta per link the mesh is actually given (`state.deltas[i]`); `probe` is the ")
                .append("same quantity as the previous round's instrument computes it, which composes ")
                .append("the parent's delta a second time. Blocks, worst over the recorded frames.\n\n");

        // ---- the metric's control, both ways, before anything is measured ----------------------
        for (int i = 1; i < rig.tail.size(); i++) {
            Segment link = rig.tail.get(i);
            Segment parent = rig.tail.get(i - 1);
            float controlProbe = rig.separation(link, new Matrix4f(), parent, new Matrix4f(),
                    rig.restPose());
            assertEquals(0.0F, controlProbe, 1.0E-4F,
                    "the probe metric must read zero on a chain drawn as built; " + link.name
                            + " reads " + controlProbe);
        }

        report.append("## 0. What each link turns about, and how far its mounting geometry is from it\n\n")
                .append("`hinge` is the point the link's delta rotates about, measured by the frame ")
                .append("path's own rule (`YsmPhysicsParts#contactAnchor`); `pivot` is where the model ")
                .append("authored the bone. `|u|` is the distance from the link's hinge to the vertex of ")
                .append("its own geometry nearest the link above; `|w|` the same for that link's vertex, ")
                .append("measured from the child's hinge; `bind` is the closest the two clouds come in the ")
                .append("bind pose. The gap a drawn rotation of theta opens at the joint is ")
                .append("`|R(theta)u - w| - |u - w|`.\n\n")
                .append("| link | vertices | chain parent | production parent | pivot | hinge | ")
                .append("|hinge-pivot| | |u| | |w| | bind | lever |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 1; i < rig.tail.size(); i++) {
            Segment link = rig.tail.get(i);
            Segment parent = rig.tail.get(i - 1);
            Vector3f[] pair = rig.mountPair(link, parent);
            YsmPhysicsParts.Segment part = rig.part(link.index);
            report.append("| `").append(link.name).append("` | ").append(link.own.size())
                    .append(" | `").append(nameOf(rig, rig.parentOf(link.index))).append("` | `")
                    .append(nameOf(rig, part.parent())).append("` | ")
                    .append(point(part.bindPivot()))
                    .append(" | ").append(point(part.bindAnchor())).append(" | ")
                    .append(fmt(part.bindAnchor().distance(part.bindPivot()))).append(" | ")
                    .append(fmt(pair[0].distance(part.bindAnchor()))).append(" | ")
                    .append(fmt(pair[1].distance(part.bindAnchor()))).append(" | ")
                    .append(fmt(pair[0].distance(pair[1]))).append(" | ")
                    .append(fmt(part.lever())).append(" |\n");
        }
        report.append('\n');

        // ---- the states, shipped file, both instruments ---------------------------------------
        report.append("## 1. The shipped file, by state\n\n")
                .append("`render` is the gap at a joint on the mesh's own chain; `probe` the same ")
                .append("quantity as the previous round's instrument; `near` the closest the two drawn ")
                .append("clouds come over the bind one. Tail7 is the tip, Tail5 the worst joint. Blocks, ")
                .append("degrees, seconds.\n\n")
                .append("| state | render Tail7 | render Tail5 | probe Tail7 | near Tail7 | tip composed ")
                .append("| drawn range Tail2 | pinned Tail2 | tip speed | overshoot | settle s |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        Map<String, Tip> shipped = new LinkedHashMap<>();
        for (String state : TIP_STATES) {
            Tip tip = tipRun(rig, Setup.shipped(), state);
            shipped.put(state, tip);
            report.append("| ").append(state).append(" | ").append(tip.line()).append('\n');
        }
        report.append('\n');

        // ---- the per-link detail, at sprint and in the sway ------------------------------------
        for (String state : new String[]{"sprint", "stop-sway"}) {
            Tip tip = shipped.get(state);
            report.append("## 2. Per link, ").append(state).append("\n\n")
                    .append("`own-vertex step` is how far the link's farthest vertex is drawn from ")
                    .append("where the link above's delta alone puts that same vertex; `radius` is ")
                    .append("that vertex's distance from the link's own hinge, so a link carried by the ")
                    .append("link above it cannot step further than `bound = 2 radius sin(theta/2)`.\n\n")
                    .append("| link | own drawn | allowed | solver asks | pinned | drawn range | composed ")
                    .append("| render gap | probe gap | near gap | own-vertex step | radius | bound ")
                    .append("| applied | centre off |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (String name : TAIL) {
                float own = tip.own.getOrDefault(name, 0.0F);
                float radius = 0.0F;
                for (Segment piece : rig.tail) {
                    if (piece.name.equals(name)) {
                        radius = rig.farthest(piece).distance(rig.part(piece.index).bindAnchor());
                    }
                }
                float bound = 2.0F * radius * (float) Math.sin(Math.toRadians(own) * 0.5F);
                report.append("| `").append(name).append("` | ")
                        .append(fmt(own)).append(" | ")
                        .append(fmt(tip.allowed.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(tip.raw.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(tip.pinned.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(tip.range.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(tip.composed.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(tip.render.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(tip.probe.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(tip.near.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(tip.step.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(radius)).append(" | ")
                        .append(fmt(bound)).append(" | ")
                        .append(fmt(tip.applied.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(tip.centre.getOrDefault(name, 0.0F))).append(" |\n");
            }
            report.append('\n');
        }

        // ---- the levers, in the state that reproduces the report and at the sprint ------------
        for (String state : new String[]{"yaw-sway", "sprint"}) {
            report.append("## 3. The levers, ").append(state).append("\n\n")
                    .append("`frozen` is the share of frames the link below the root is drawn on its ")
                    .append("allowance - 1.000 is a link drawn as part of a rigid body; `range` is how far ")
                    .append("its drawn angle travels; `curl` the tip's composed bend; `tip gap` the tip's own ")
                    .append("joint on the renderer's chain; `near tip` the closest its own clouds come over ")
                    .append("bind; `cost` the largest distance any piece the candidate does not name is drawn ")
                    .append("from where the shipped file draws it, over the same frames.\n\n")
                    .append("| candidate | frozen Tail2 | frozen Tail6 | range Tail6 | curl | tip gap ")
                    .append("| near tip | tip speed | overshoot | settle s | cost |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (int i = 0; i < TIP_SETUPS.length; i++) {
                Setup setup = TIP_SETUPS[i];
                Tip tip = i == 0 ? shipped.get(state) : tipRun(rig, setup, state);
                tip.cost = i == 0 ? 0.0F : cost(shipped.get(state), tip);
                report.append("| ").append(setup.label()).append(" | ")
                        .append(fmt(tip.pinned.getOrDefault("Tail2", 0.0F))).append(" | ")
                        .append(fmt(tip.pinned.getOrDefault("Tail6", 0.0F))).append(" | ")
                        .append(fmt(tip.range.getOrDefault("Tail6", 0.0F))).append(" | ")
                        .append(fmt(tip.composed.getOrDefault("Tail7", 0.0F))).append(" | ")
                        .append(fmt(tip.render.getOrDefault("Tail7", 0.0F))).append(" | ")
                        .append(fmt(tip.near.getOrDefault("Tail7", 0.0F))).append(" | ")
                        .append(fmt(tip.tipSpeed)).append(" | ")
                        .append(fmt(tip.overshoot)).append(" | ")
                        .append(fmt(tip.settleSeconds)).append(" | ")
                        .append(fmt(tip.cost)).append(" |\n");
            }
            report.append('\n');
        }

        Path out = Paths.get("build", "reports", "ysm-tail-tip-render-measurement.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // ---- what this round asserts ------------------------------------------------------------
        Tip sprint = shipped.get("sprint");
        // The defect the report is about is not a hole in the drawn chain: on the chain the mesh is
        // actually given, the tip's own joint is closed to within a few millimetres in every state
        // measured, where the previous round's instrument - which composes the parent's delta a
        // second time - reads several centimetres. Both halves are asserted, so a later change to
        // either the renderer or the instrument fails this loudly.
        assertTrue(sprint.render.getOrDefault("Tail7", 0.0F) <= 0.05F,
                "the tip's own joint must be closed on the renderer's chain; reads "
                        + fmt(sprint.render.getOrDefault("Tail7", 0.0F)));
        assertTrue(sprint.probe.getOrDefault("Tail7", 0.0F)
                        > 2.0F * sprint.render.getOrDefault("Tail7", 0.0F),
                "the probe's double-composed instrument must over-report the tip's joint; reads "
                        + fmt(sprint.probe.getOrDefault("Tail7", 0.0F)) + " against "
                        + fmt(sprint.render.getOrDefault("Tail7", 0.0F)));
        // What the eye is reading instead: every link below the root is drawn on its allowance in
        // every frame, so the tail is a rigid crescent that the body waves about - and in the state
        // where the body itself turns, the drawn tip travels at metres per second while nothing
        // inside the chain moves at all.
        Tip yaw = shipped.get("yaw-sway");
        assertTrue(yaw.pinned.getOrDefault("Tail2", 0.0F) >= 0.999F,
                "the shipped file must draw Tail2 on its allowance in every frame of the yaw sway; "
                        + "reads " + fmt(yaw.pinned.getOrDefault("Tail2", 0.0F)));
        assertTrue(yaw.range.getOrDefault("Tail2", 0.0F) <= 1.0F,
                "the shipped file must draw the chain's angles frozen through the yaw sway; Tail2 "
                        + "travels " + fmt(yaw.range.getOrDefault("Tail2", 0.0F)) + " degrees");
        assertTrue(yaw.tipSpeed > 1.5F,
                "the drawn tip must be travelling fast while the chain is frozen - that pair is the "
                        + "reported defect; tip speed " + fmt(yaw.tipSpeed) + " blocks/s");
        assertTrue(yaw.render.getOrDefault("Tail7", 0.0F) <= 0.05F,
                "the tip's own joint must stay closed through the body's turn; reads "
                        + fmt(yaw.render.getOrDefault("Tail7", 0.0F)));
        assertTrue(yaw.probe.getOrDefault("Tail7", 0.0F)
                        > 2.0F * yaw.render.getOrDefault("Tail7", 0.0F),
                "the double-composed instrument must over-report the tip's joint in the yaw sway too; "
                        + "reads " + fmt(yaw.probe.getOrDefault("Tail7", 0.0F)) + " against "
                        + fmt(yaw.render.getOrDefault("Tail7", 0.0F)));
        // The lever the brief put on the table: a chain total for the tail's own piece. Measured in
        // the state that reproduces the report, it gives the chain a shape that travels - and it
        // makes the tip's own joint open further and the tip travel FASTER, monotonically in the
        // total. So it is not shipped, and what is asserted here is the refutation.
        Tip loosened = tipRun(rig, Setup.total(240.0F), "yaw-sway");
        loosened.cost = cost(yaw, loosened);
        assertTrue(loosened.range.getOrDefault("Tail6", 0.0F)
                        > yaw.range.getOrDefault("Tail6", 0.0F) + 1.0F,
                "a raised chain total must let the chain's drawn angle travel; Tail6 travels "
                        + fmt(loosened.range.getOrDefault("Tail6", 0.0F)) + " degrees, against "
                        + fmt(yaw.range.getOrDefault("Tail6", 0.0F)));
        assertTrue(loosened.render.getOrDefault("Tail7", 0.0F)
                        > yaw.render.getOrDefault("Tail7", 0.0F),
                "a raised chain total must open the tip's own joint further, which is why it is not "
                        + "the fix; reads " + fmt(loosened.render.getOrDefault("Tail7", 0.0F))
                        + " against " + fmt(yaw.render.getOrDefault("Tail7", 0.0F)));
        assertTrue(loosened.tipSpeed > yaw.tipSpeed,
                "a raised chain total must draw the tip travelling faster, not slower - it is a "
                        + "longer, more curled lever; tip speed " + fmt(loosened.tipSpeed)
                        + " against " + fmt(yaw.tipSpeed));
        assertTrue(loosened.render.getOrDefault("Tail7", 0.0F) <= 0.05F,
                "even with the raised total the tip's joint must stay closed to within a few "
                        + "centimetres; reads " + fmt(loosened.render.getOrDefault("Tail7", 0.0F)));
        assertEquals(0.0F, loosened.cost, 1.0E-4F,
                "a chain total for the tail's piece must not move any piece outside it; worst move "
                        + fmt(loosened.cost));
        // Removing the Tail ceiling is not the fix either: it is transient-only, and in the yaw sway
        // it moves the tip's travel by a hundredth of a block per second.
        Tip uncapped = tipRun(rig, Setup.noTailCap(), "yaw-sway");
        assertTrue(Math.abs(uncapped.tipSpeed - yaw.tipSpeed) < 0.05F,
                "removing the Tail ceiling must not change the tip's travel; reads "
                        + fmt(uncapped.tipSpeed) + " against " + fmt(yaw.tipSpeed));
        Tip plainSprint = tipRun(rig, Setup.noTailCap(), "sprint");
        assertEquals(sprint.composed.getOrDefault("Tail7", 0.0F),
                plainSprint.composed.getOrDefault("Tail7", 0.0F), 1.0E-3F,
                "in the steady sprint the Tail ceiling must not bite at all");
    }

    // ==================================================================
    // Round 2: the authored layer, on the chain the mesh is given
    // ==================================================================

    /**
     * The model's own authored clips, evaluated by production's own evaluator
     * ({@code YSMPlayerAnimator#evalAnim} + {@code #compose}), and the two rules the chain delta can
     * be composed from them with:
     *
     * <ul>
     *   <li>{@code offset} - what this mod ships ({@code #composeBone} 889-918:
     *       {@code rx = bone.rx + (hasRot[i] ? animRot[i][0] : 0.0F)}). The animation's value is an
     *       offset from the bone's authored rotation, exactly as YSM composes it. Vendored
     *       references: {@code ModernYSM .../geckolib3/core/snapshot/BoneTopLevelSnapshot.java}
     *       45-48 ({@code setRotationX(rotation.x + initialRotation.x)}) with
     *       {@code .../core/util/MathUtil.java} 30-46, {@code OpenYSM} the same file and lines, and
     *       this project's sibling {@code 参考/YSM-EFC-sakura-1.20.1
     *       .../ParallelAnimationProgram.java} 4609-4614.</li>
     *   <li>{@code legacy} - the rule this mod shipped before the authored-rotation round: a clip
     *       that keyed a bone drew it at the animation's value, so the bone's authored rotation was
     *       gone for as long as that clip was active. The mesh is baked <b>with</b> those authored
     *       rotations ({@code EFMeshJsonWriter#walkBone} 271-276), so the drawn geometry was
     *       un-rotated by the whole bind angle. Kept here as the <b>measured</b> control: the fold the
     *       change removes is reproduced by this rule and by no other, which is what says the change
     *       moved the defect and not something else.</li>
     * </ul>
     *
     * <p>The animated value is the same in both runs - production's own {@code animRot} - so the only
     * variable between the two columns is the rule. The chain the shipped rule produces is asserted
     * against production's own {@code chainDeltaBuf}, which is what makes the local composition of
     * the {@code legacy} column trustworthy rather than a second convention guessed here.
     */
    private static final class AuthoredLayer {
        private final Rig rig;
        private final YSMPlayerAnimator evaluator;
        private final Method evalAnim;
        private final Method compose;
        private final Field rotField;
        private final Field hasRotField;
        private final Field posField;
        private final Field hasPosField;
        private final Field scaleField;
        private final Field hasScaleField;
        private final Field chainField;
        /** The legacy-rule chains (the {@code replace} rule), per bone index, rebuilt per frame. */
        private final Matrix4f[] reference;
        /**
         * The same chain under the shipped offset rule, composed here rather than read from
         * production. It exists to be compared with production's own {@code chainDeltaBuf} on the
         * tail's links: if this file's arithmetic - the order of the two multiplications, the
         * conjugation, the pivot - disagreed with {@code #composeBone}, the comparison fails and the
         * {@code legacy} column beside it is not evidence.
         */
        private final Matrix4f[] control;
        private final boolean[] built;

        AuthoredLayer(Rig rig, YSMRuntimeModel runtime) throws Exception {
            this.rig = rig;
            // The animator is constructed with two ItemStack.EMPTY fields, and reading a vanilla item
            // needs vanilla's registries up. Vanilla's half of the bootstrap runs first; Forge's
            // patched tail asks its own event bus for a listener list and throws in a plain JUnit JVM,
            // by which point every registry this probe needs is already built - so the throw is
            // swallowed here, and nothing else in this measurement goes near the game.
            net.minecraft.SharedConstants.tryDetectVersion();
            try {
                net.minecraft.server.Bootstrap.bootStrap();
            } catch (Throwable vanillaOnly) {
                assertTrue(net.minecraft.world.item.ItemStack.EMPTY != null,
                        "vanilla's registries must be up after the bootstrap's vanilla half: "
                                + vanillaOnly);
            }
            this.evaluator = new YSMPlayerAnimator(runtime);
            this.evalAnim = YSMPlayerAnimator.class.getDeclaredMethod("evalAnim",
                    YSMRuntimeModel.CompiledAnim.class, double.class);
            this.evalAnim.setAccessible(true);
            this.compose = YSMPlayerAnimator.class.getDeclaredMethod("compose");
            this.compose.setAccessible(true);
            this.rotField = YSMPlayerAnimator.class.getDeclaredField("animRot");
            this.rotField.setAccessible(true);
            this.hasRotField = YSMPlayerAnimator.class.getDeclaredField("hasRot");
            this.hasRotField.setAccessible(true);
            this.posField = YSMPlayerAnimator.class.getDeclaredField("animPos");
            this.posField.setAccessible(true);
            this.hasPosField = YSMPlayerAnimator.class.getDeclaredField("hasPos");
            this.hasPosField.setAccessible(true);
            this.scaleField = YSMPlayerAnimator.class.getDeclaredField("animScale");
            this.scaleField.setAccessible(true);
            this.hasScaleField = YSMPlayerAnimator.class.getDeclaredField("hasScale");
            this.hasScaleField.setAccessible(true);
            this.chainField = YSMPlayerAnimator.class.getDeclaredField("chainDeltaBuf");
            this.chainField.setAccessible(true);
            this.reference = new Matrix4f[rig.bones.length];
            this.control = new Matrix4f[rig.bones.length];
            this.built = new boolean[rig.bones.length];
            for (int i = 0; i < reference.length; i++) {
                reference[i] = new Matrix4f();
                control[i] = new Matrix4f();
            }
        }

        /** One frame: evaluate every parallel clip production evaluates, then compose. */
        void frame(YSMRuntimeModel runtime, double now) throws Exception {
            for (YSMRuntimeModel.CompiledAnim anim : runtime.parallels) {
                evalAnim.invoke(evaluator, anim, now);
            }
            compose.invoke(evaluator);
            java.util.Arrays.fill(built, false);
            float[][] rotations = (float[][]) rotField.get(evaluator);
            boolean[] hasRotation = (boolean[]) hasRotField.get(evaluator);
            float[][] positions = (float[][]) posField.get(evaluator);
            boolean[] hasPosition = (boolean[]) hasPosField.get(evaluator);
            float[][] scales = (float[][]) scaleField.get(evaluator);
            boolean[] hasScale = (boolean[]) hasScaleField.get(evaluator);
            for (int bone = 0; bone < reference.length; bone++) {
                build(runtime.bones, rotations, hasRotation, positions, hasPosition,
                        scales, hasScale, bone, 0);
            }
        }

        /**
         * Both chains for one bone: the shipped offset rule (the control) and the legacy replace rule
         * (the reference).
         *
         * <p>Everything else the composition reads - the position and scale channels, the pivot
         * offset, and the rule for a bone Epic Fight owns ({@code mapped}, which keeps its authored
         * rotation) - is identical in the two chains, so the only thing the comparison can differ by
         * is the rotation rule itself.
         */
        private void build(YSMRuntimeModel.BoneRt[] bones, float[][] rotations, boolean[] hasRotation,
                           float[][] positions, boolean[] hasPosition, float[][] scales,
                           boolean[] hasScale, int bone, int depth) {
            if (built[bone]) {
                return;
            }
            assertTrue(depth <= 512, "cyclic bone hierarchy in the converted runtime");
            built[bone] = true;
            YSMRuntimeModel.BoneRt rt = bones[bone];
            if (rt.parent >= 0) {
                build(bones, rotations, hasRotation, positions, hasPosition, scales, hasScale,
                        rt.parent, depth + 1);
            }
            boolean keyed = !rt.mapped && hasRotation[bone];
            float[] shippedRot = {
                    rt.rx + (keyed ? rotations[bone][0] : 0.0F),
                    rt.ry + (keyed ? rotations[bone][1] : 0.0F),
                    rt.rz + (keyed ? rotations[bone][2] : 0.0F)};
            float[] legacyRot = {rt.rx, rt.ry, rt.rz};
            if (keyed) {
                legacyRot[0] = rotations[bone][0];
                legacyRot[1] = rotations[bone][1];
                legacyRot[2] = rotations[bone][2];
            }
            // production's own placement: an unmapped bone is offset by its position channel, a mapped
            // one is not, and both are scaled about the authored pivot.
            float ox = rt.px + (!rt.mapped && hasPosition[bone] ? positions[bone][0] : 0.0F);
            float oy = rt.py + (!rt.mapped && hasPosition[bone] ? positions[bone][1] : 0.0F);
            float oz = rt.pz + (!rt.mapped && hasPosition[bone] ? positions[bone][2] : 0.0F);
            float sx = hasScale[bone] ? scales[bone][0] : 1.0F;
            float sy = hasScale[bone] ? scales[bone][1] : 1.0F;
            float sz = hasScale[bone] ? scales[bone][2] : 1.0F;
            assign(rt, shippedRot, ox, oy, oz, sx, sy, sz, control, bone);
            assign(rt, legacyRot, ox, oy, oz, sx, sy, sz, reference, bone);
        }

        private void assign(YSMRuntimeModel.BoneRt rt, float[] rotation, float ox, float oy, float oz,
                            float sx, float sy, float sz, Matrix4f[] chain, int bone) {
            Matrix4f localAnim = new Matrix4f()
                    .translation(ox, oy, oz)
                    .rotateZ(rotation[2]).rotateY(rotation[1]).rotateX(rotation[0])
                    .scale(sx, sy, sz)
                    .translate(-rt.px, -rt.py, -rt.pz);
            Matrix4f own = new Matrix4f(localAnim).mul(new Matrix4f(rt.bindLocal).invert());
            own.set(new Matrix4f(rt.bindWorld).mul(own).mul(new Matrix4f(rt.bindWorld).invert()));
            if (rt.parent >= 0) {
                chain[bone].set(chain[rt.parent]).mul(own);
            } else {
                chain[bone].set(own);
            }
        }

        /** Production's own chain delta for a bone, after {@link #frame}. */
        OpenMatrix4f shipped(int boneIndex) throws Exception {
            Matrix4f[][] buffers = (Matrix4f[][]) chainField.get(evaluator);
            OpenMatrix4f out = new OpenMatrix4f();
            importInto(out, buffers[0][boneIndex]);
            return out;
        }

        /** The {@code legacy} (replace) chain delta for a bone, after {@link #frame}. */
        OpenMatrix4f legacy(int boneIndex) {
            OpenMatrix4f out = new OpenMatrix4f();
            importInto(out, reference[boneIndex]);
            return out;
        }

        /** This file's own composition of the shipped offset rule, for the control comparison. */
        OpenMatrix4f control(int boneIndex) {
            OpenMatrix4f out = new OpenMatrix4f();
            importInto(out, control[boneIndex]);
            return out;
        }

        /** The animated rotation production evaluated for a bone this frame, radians. */
        float[] animated(int boneIndex) throws Exception {
            float[][] rotations = (float[][]) rotField.get(evaluator);
            boolean[] hasRotation = (boolean[]) hasRotField.get(evaluator);
            return hasRotation[boneIndex] ? rotations[boneIndex] : null;
        }

        private static void importInto(OpenMatrix4f out, Matrix4f m) {
            out.m00 = m.m00(); out.m01 = m.m01(); out.m02 = m.m02(); out.m03 = m.m03();
            out.m10 = m.m10(); out.m11 = m.m11(); out.m12 = m.m12(); out.m13 = m.m13();
            out.m20 = m.m20(); out.m21 = m.m21(); out.m22 = m.m22(); out.m23 = m.m23();
            out.m30 = m.m30(); out.m31 = m.m31(); out.m32 = m.m32(); out.m33 = m.m33();
        }
    }

    /** What one state's authored layer measured, for one composition rule. */
    private static final class Authored {
        /** link -> the angle of the link's own delta (its part's own rotation), degrees. */
        final Map<String, Float> ownDelta = new LinkedHashMap<>();
        /** link -> how far the link's farthest vertex is drawn from where the pose alone puts it. */
        final Map<String, Float> ownMove = new LinkedHashMap<>();
        /** The animated rotation production evaluated for the link, degrees, worst over the frames. */
        final Map<String, Float> animated = new LinkedHashMap<>();
        /** The furthest the tip's own vertex is drawn from where the pose alone puts it, blocks. */
        float tipMove;
        /** The tip's drawn speed along its path, blocks/s. */
        float tipSpeed;
        /** Share of frames the physics drew the link on its allowance (the previous round's `pinned`). */
        final Map<String, Float> pinned = new LinkedHashMap<>();
        private int frames;

        String line() {
            return fmt(tipMove) + " | " + fmt(tipSpeed);
        }
    }

    @Test
    void theAuthoredClipOnTheChainTheMeshIsGiven() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");

        Rig rig = Rig.load(pack, MAID, LOGGED_MAID_SIMULATED);
        YSMRuntimeModel runtime = runtimeModel(pack, MAID);
        assertNotNull(runtime, "the production runtime model must compile from the converted pack for "
                + MAID);
        AuthoredLayer authored = new AuthoredLayer(rig, runtime);

        StringBuilder report = new StringBuilder();
        report.append("# The authored layer on the chain the mesh is given\n\n")
                .append("Model `").append(rig.modelId).append("`. `shipped/offset` is the rule ")
                .append("`YSMPlayerAnimator#composeBone` applies since the authored-rotation round ")
                .append("(a keyed bone is drawn at its authored rotation PLUS the animation's value, ")
                .append("YSM's own rule - `BoneTopLevelSnapshot` 45-48); `legacy/replace` is the rule it ")
                .append("replaced (a keyed bone drawn AT the animation's value, its authored rotation ")
                .append("gone), kept as the measured control. The ")
                .append("animated values are production's, evaluated by production's own ")
                .append("`evalAnim`/`compose`, so the rule is the only variable. Blocks, degrees.\n\n")
                .append("## 1. What the clips key, and what the chain does with it\n\n")
                .append("`animated` is the rotation the model's parallel clips gave the link (degrees, ")
                .append("worst over the frames); `bind` the model's authored rotation for it; ")
                .append("`own delta` the angle the link's own part delta applies under each rule - the ")
                .append("angle the link's geometry is drawn away from where the model was built, for ")
                .append("the same body pose.\n\n")
                .append("| link | bind rot (deg) | animated (deg) | own delta shipped/offset | ")
                .append("own delta legacy/replace | shipped move (blocks) | legacy move (blocks) |\n")
                .append("|---|---|---|---|---|---|---|\n");

        Map<String, Authored> shippedStates = new LinkedHashMap<>();
        Map<String, Authored> legacyStates = new LinkedHashMap<>();
        Map<String, Float> physicsSpeeds = new LinkedHashMap<>();
        Map<String, Float> physicsSpeedsLegacy = new LinkedHashMap<>();
        StringBuilder physicsRows = new StringBuilder();
        /** piece name -> the furthest the authored layer draws it from where the pose puts it. */
        Map<String, Float> blastShipped = new LinkedHashMap<>();
        Map<String, Float> blastLegacy = new LinkedHashMap<>();
        /** The largest disagreement between this file's composition and production's own chain. */
        float controlWorst = 0.0F;

        for (String state : TIP_STATES) {
            boolean sway = "stop-sway".equals(state);
            boolean yawSway = "yaw-sway".equals(state);
            float lean = "idle".equals(state) || "fall".equals(state) ? 0.0F
                    : "walk".equals(state) || yawSway ? WALK_LEAN : SPRINT_LEAN;
            float speed = "idle".equals(state) || "fall".equals(state) ? 0.0F
                    : "walk".equals(state) || yawSway ? WALK_SPEED : SPRINT_SPEED;
            boolean cycle = !"idle".equals(state) && !"fall".equals(state);
            Vector3f velocity = "fall".equals(state) ? new Vector3f(0.0F, -1.6F, 0.0F)
                    : new Vector3f(0.0F, 0.0F, -speed);
            TipPose pose = new TipPose(rig, lean, cycle);

            YsmPhysicsParts.Model parts = rig.model();
            YsmMeshSecondaryMotion.State engine = new YsmMeshSecondaryMotion.State(
                    parts, null, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
            applySetup(parts, engine, Setup.shipped());

            Authored shipped = new Authored();
            Authored legacy = new Authored();
            List<Vector3f> shippedPath = new ArrayList<>();
            List<Vector3f> legacyPath = new ArrayList<>();
            List<Vector3f> shippedComposedPath = new ArrayList<>();
            List<Vector3f> legacyComposedPath = new ArrayList<>();

            int frames = (int) ((TIP_STEADY_SECONDS + (sway ? TIP_SWAY_RAMP + TIP_SWAY_AFTER : 0.0F)) / DT);
            int warmup = (int) (TIP_WARMUP_SECONDS / DT);
            for (int frame = 0; frame < frames; frame++) {
                float t = frame * DT;
                Vector3f at = velocity;
                float[] turn = NO_TURN;
                if (sway) {
                    float k = Math.max(0.0F, Math.min(1.0F, (t - TIP_STEADY_SECONDS) / TIP_SWAY_RAMP));
                    pose.drive(lean * (1.0F - k), 1.0F - k, 1.0F - k);
                    at = new Vector3f(velocity).mul(1.0F - k);
                }
                if (yawSway) {
                    double omega = 2.0D * Math.PI * YAW_HZ;
                    float degrees = YAW_SWING_DEGREES * (float) Math.sin(omega * t);
                    float rate = YAW_SWING_DEGREES * (float) (omega * Math.cos(omega * t));
                    float accel = -YAW_SWING_DEGREES * (float) (omega * omega * Math.sin(omega * t));
                    pose.yaw(degrees);
                    turn = new float[]{(float) Math.toRadians(rate), (float) Math.toRadians(accel)};
                }
                pose.at(t);
                YsmMeshSecondaryMotion.simulate(engine, pose, DT, at, turn,
                        YsmDynamicBoneSolver.NO_COLLIDERS);
                authored.frame(runtime, t);
                if (frame < warmup) {
                    continue;
                }
                // The control: every bone, every frame. This file's own composition must reproduce
                // production's chain when it is given production's rule, so the `legacy` column beside
                // it is a measured difference of one rule and not of two implementations.
                for (int bone = 0; bone < runtime.bones.length; bone++) {
                    controlWorst = Math.max(controlWorst,
                            difference(authored.control(bone), authored.shipped(bone)));
                }
                for (int linkAt = 0; linkAt < rig.tail.size(); linkAt++) {
                    Segment link = rig.tail.get(linkAt);
                    OpenMatrix4f shippedDelta = authored.shipped(link.boneIndex);
                    OpenMatrix4f legacyDelta = authored.legacy(link.boneIndex);
                    // The link's own rotation, over and above its parent's: what the rule does to the
                    // link's own geometry. Both sides are the same chain, so the parent cancels in the
                    // difference and what is left is the link's own contribution.
                    OpenMatrix4f identity = new OpenMatrix4f();
                    OpenMatrix4f parentShipped = linkAt == 0 ? identity
                            : authored.shipped(rig.tail.get(linkAt - 1).boneIndex);
                    OpenMatrix4f parentLegacy = linkAt == 0 ? identity
                            : authored.legacy(rig.tail.get(linkAt - 1).boneIndex);
                    shipped.ownDelta.merge(link.name, ownAngle(parentShipped, shippedDelta), Math::max);
                    legacy.ownDelta.merge(link.name, ownAngle(parentLegacy, legacyDelta), Math::max);
                    float[] animatedRotation = authored.animated(link.boneIndex);
                    float animatedDegrees = animatedRotation == null ? 0.0F
                            : (float) Math.toDegrees(Math.sqrt(
                                    animatedRotation[0] * animatedRotation[0]
                                            + animatedRotation[1] * animatedRotation[1]
                                            + animatedRotation[2] * animatedRotation[2]));
                    shipped.animated.merge(link.name, animatedDegrees, Math::max);
                    Vector3f far = rig.farthest(link);
                    Vector3f base = drawnWith(rig, link, far, pose);
                    shipped.ownMove.merge(link.name,
                            base.distance(drawnWith(rig, link, far, pose, shippedDelta)), Math::max);
                    legacy.ownMove.merge(link.name,
                            base.distance(drawnWith(rig, link, far, pose, legacyDelta)), Math::max);
                    shipped.pinned.merge(link.name,
                            engine.lastDegrees[link.index] >= Math.toDegrees(engine.chainBudget[link.index]) - 0.01F
                                    && engine.lastDegrees[link.index] > 0.5F ? 1.0F : 0.0F, Float::sum);
                }
                // The blast radius of the rule, measured the same way on every piece the rig carries
                // that is not a tail link - the pieces the clips key (the ears, the mask, the eyelids)
                // and every piece hanging under one of them.
                for (Integer bone : rig.vertices.keySet()) {
                    String pieceName = rig.bones[bone].name;
                    if (isTailBone(pieceName)) {
                        continue;
                    }
                    Vector3f pivot = rig.pivot(bone);
                    Vector3f far = null;
                    float reach = -1.0F;
                    for (Vector3f vertex : rig.vertices.get(bone)) {
                        float distance = vertex.distance(pivot);
                        if (distance > reach) {
                            reach = distance;
                            far = vertex;
                        }
                    }
                    if (far == null) {
                        continue;
                    }
                    int joint = rig.bones[bone].joint;
                    Vector3f base = drawnAtJoint(rig, joint, far, pose);
                    blastShipped.merge(pieceName, base.distance(drawnAtJoint(rig, joint, far, pose,
                            authored.shipped(bone))), Math::max);
                    blastLegacy.merge(pieceName, base.distance(drawnAtJoint(rig, joint, far, pose,
                            authored.legacy(bone))), Math::max);
                }
                Segment tip = rig.tail.get(rig.tail.size() - 1);
                Vector3f tipVertex = rig.farthest(tip);
                Vector3f tipBase = drawnWith(rig, tip, tipVertex, pose);
                Vector3f tipShipped = drawnWith(rig, tip, tipVertex, pose, authored.shipped(tip.boneIndex));
                Vector3f tipLegacy = drawnWith(rig, tip, tipVertex, pose, authored.legacy(tip.boneIndex));
                shipped.tipMove = Math.max(shipped.tipMove, tipBase.distance(tipShipped));
                legacy.tipMove = Math.max(legacy.tipMove, tipBase.distance(tipLegacy));
                shippedPath.add(tipShipped);
                legacyPath.add(tipLegacy);
                OpenMatrix4f shippedComposed = new OpenMatrix4f();
                shippedComposed.load(authored.shipped(tip.boneIndex));
                shippedComposed.mulBack(engine.deltas[tip.index]);
                OpenMatrix4f legacyComposed = new OpenMatrix4f();
                legacyComposed.load(authored.legacy(tip.boneIndex));
                legacyComposed.mulBack(engine.deltas[tip.index]);
                shippedComposedPath.add(drawnWith(rig, tip, tipVertex, pose, shippedComposed));
                legacyComposedPath.add(drawnWith(rig, tip, tipVertex, pose, legacyComposed));
                shipped.frames++;
            }
            for (String name : TAIL) {
                Float count = shipped.pinned.get(name);
                if (count != null && shipped.frames > 0) {
                    shipped.pinned.put(name, count / shipped.frames);
                }
            }
            shipped.tipSpeed = tipSpeedOf(shippedPath);
            legacy.tipSpeed = tipSpeedOf(legacyPath);
            shippedStates.put(state, shipped);
            legacyStates.put(state, legacy);
            physicsSpeeds.put(state, tipSpeedOf(shippedComposedPath));
            physicsSpeedsLegacy.put(state, tipSpeedOf(legacyComposedPath));
            physicsRows.append("| ").append(state).append(" | ")
                    .append(fmt(shipped.tipMove)).append(" | ")
                    .append(fmt(legacy.tipMove)).append(" | ")
                    .append(fmt(shipped.tipSpeed)).append(" | ")
                    .append(fmt(tipSpeedOf(shippedComposedPath))).append(" | ")
                    .append(fmt(tipSpeedOf(legacyComposedPath))).append(" | ")
                    .append(fmt(shipped.pinned.getOrDefault("Tail2", 0.0F))).append(" | ")
                    .append(fmt(shipped.pinned.getOrDefault("Tail6", 0.0F))).append(" |\n");
        }

        for (String name : TAIL) {
            Segment link = null;
            for (Segment piece : rig.tail) {
                if (piece.name.equals(name)) {
                    link = piece;
                }
            }
            assertNotNull(link, "the rig must carry the tail link " + name);
            YSMRuntimeModel.BoneRt bone = runtime.bones[link.boneIndex];
            float bindDegrees = (float) Math.toDegrees(Math.sqrt(bone.rx * bone.rx + bone.ry * bone.ry
                    + bone.rz * bone.rz));
            float animatedWorst = 0.0F;
            float shippedDelta = 0.0F;
            float legacyDelta = 0.0F;
            float shippedMove = 0.0F;
            float legacyMove = 0.0F;
            for (String state : TIP_STATES) {
                Authored shipped = shippedStates.get(state);
                Authored legacy = legacyStates.get(state);
                animatedWorst = Math.max(animatedWorst, shipped.animated.getOrDefault(name, 0.0F));
                shippedDelta = Math.max(shippedDelta, shipped.ownDelta.getOrDefault(name, 0.0F));
                legacyDelta = Math.max(legacyDelta, legacy.ownDelta.getOrDefault(name, 0.0F));
                shippedMove = Math.max(shippedMove, shipped.ownMove.getOrDefault(name, 0.0F));
                legacyMove = Math.max(legacyMove, legacy.ownMove.getOrDefault(name, 0.0F));
            }
            report.append("| `").append(name).append("` | ").append(fmt(bindDegrees)).append(" | ")
                    .append(fmt(animatedWorst)).append(" | ")
                    .append(fmt(shippedDelta)).append(" | ")
                    .append(fmt(legacyDelta)).append(" | ")
                    .append(fmt(shippedMove)).append(" | ")
                    .append(fmt(legacyMove)).append(" |\n");
        }
        report.append('\n')
                .append("## 3. The tip, by state\n\n")
                .append("`authored move` is how far the tip's own vertex is drawn from where the ")
                .append("body's pose alone puts it, under each rule; `tip travel` the distance the ")
                .append("drawn tip covers per second, authored layer alone; the two `+ authored` ")
                .append("columns the same with the secondary-motion delta composed on top of the ")
                .append("authored chain; `pinned` the share of frames the physics drew the link on its ")
                .append("allowance (the previous round's quantity, unchanged by the authored layer ")
                .append("because nothing here writes the solver's state). The largest disagreement ")
                .append("between this file's own composition and production's chain, over the tail's ")
                .append("links and all recorded frames, is ").append(fmt(controlWorst))
                .append(" (a matrix element; the control that makes the `legacy` column evidence).\n\n")
                .append("| state | authored move shipped/offset | authored move legacy/replace | tip ")
                .append("travel shipped | tip travel + authored shipped | tip travel + authored legacy ")
                .append("| pinned Tail2 | pinned Tail6 |\n")
                .append("|---|---|---|---|---|---|---|---|\n")
                .append(physicsRows);

        report.append('\n')
                .append("## 2. The blast radius, outside the tail\n\n")
                .append("The legacy rule was not a tail rule: every piece the parallel clips keyed, ")
                .append("and every piece hanging under one, was drawn with its authored rotation ")
                .append("removed too. The shipped offset rule draws them at the composed rotation. ")
                .append("`shipped move` / `legacy move` are the same quantity as in the table above, for ")
                .append("the pieces outside the tail, worst over the six states. Pieces a visibility ")
                .append("script hides are included (this is the drawn transform, not visibility).\n\n")
                .append("| piece | shipped move (blocks) | legacy move (blocks) |\n")
                .append("|---|---|---|\n");
        int movedShipped = 0;
        int movedLegacy = 0;
        int ruleSensitiveLegacy = 0;
        for (Map.Entry<String, Float> entry : blastShipped.entrySet()) {
            if (entry.getValue() > 0.01F) {
                movedShipped++;
            }
        }
        for (Map.Entry<String, Float> entry : blastLegacy.entrySet()) {
            if (entry.getValue() > 0.01F) {
                movedLegacy++;
            }
            // A piece the rule itself moved: the legacy drawing materially further from the composed
            // rotation than the shipped one. The eyes are NOT among these - their 0.62-0.65 blocks is
            // the model's own SCALE channels and is identical under both rules - which is what says
            // only the ears were this rule's business on this model.
            if (entry.getValue() - blastShipped.getOrDefault(entry.getKey(), 0.0F) > 0.01F) {
                ruleSensitiveLegacy++;
            }
        }
        List<String> ranked = new ArrayList<>(blastShipped.keySet());
        ranked.sort((a, b) -> Float.compare(blastShipped.getOrDefault(b, 0.0F),
                blastShipped.getOrDefault(a, 0.0F)));
        for (int i = 0; i < Math.min(10, ranked.size()); i++) {
            String name = ranked.get(i);
            report.append("| `").append(name).append("` | ")
                    .append(fmt(blastShipped.getOrDefault(name, 0.0F))).append(" | ")
                    .append(fmt(blastLegacy.getOrDefault(name, 0.0F))).append(" |\n");
        }
        // The pieces the RULE moved, named whatever their rank: on this model the ears are the only
        // ones, and they rank below the eyes' scale channels, so a top-ten table would not show them.
        for (String name : new String[]{"Right_ear", "Left_ear"}) {
            if (blastLegacy.containsKey(name)) {
                report.append("| `").append(name).append("` *(the rule's own blast radius)* | ")
                        .append(fmt(blastShipped.getOrDefault(name, 0.0F))).append(" | ")
                        .append(fmt(blastLegacy.getOrDefault(name, 0.0F))).append(" |\n");
            }
        }
        report.append("\nOf the ").append(blastShipped.size())
                .append(" pieces outside the tail, ").append(movedShipped)
                .append(" are drawn more than a centimetre from where the pose puts them under the ")
                .append("shipped offset rule, and ").append(movedLegacy)
                .append(" under the legacy replace rule. The two counts agree because the pieces the ")
                .append("rule does not touch - the eyes and eyelids, moved by the model's own SCALE ")
                .append("channels, identical under either rule - dominate both; the pieces the RULE ")
                .append("moved, i.e. those the legacy rule drew at least a centimetre further from the ")
                .append("composed rotation than the shipped rule does, number ")
                .append(ruleSensitiveLegacy).append(".\n\n");

        Path out = Paths.get("build", "reports", "ysm-tail-authored-layer.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // ---- what this round asserts -------------------------------------------------------------
        // The clip this round is about, and the links it must key for the measurement to mean what it
        // says: the premise, checked before the finding.
        YSMRuntimeModel.CompiledAnim tailClip = null;
        for (YSMRuntimeModel.CompiledAnim anim : runtime.parallels) {
            if (anim.name.equals("pre_parallel2")) {
                tailClip = anim;
            }
        }
        assertNotNull(tailClip, "the model must carry the authored clip the report names");
        assertTrue(tailClip.bones.containsKey(rig.tail.get(0).boneIndex),
                "pre_parallel2 must key the tail's root link");
        for (Segment link : rig.tail) {
            if (link.name.equals("Tail7")) {
                continue;
            }
            assertTrue(tailClip.bones.containsKey(link.boneIndex),
                    "pre_parallel2 must key " + link.name + ", which is the premise of this round");
        }
        // The parallel clips are the only ones that key the tail in these states: a state clip that
        // keyed it too would make the measurement depend on the state machine's choice, and this
        // round holds the authored layer to the clip the brief names.
        for (String stateName : new String[]{"idle", "walk", "run"}) {
            YSMRuntimeModel.CompiledAnim stateClip = runtime.states.get(stateName);
            if (stateClip == null) {
                continue;
            }
            for (int i = 0; i < 6; i++) {
                assertFalse(stateClip.bones.containsKey(rig.tail.get(i).boneIndex),
                        stateName + " must not key " + rig.tail.get(i).name
                                + "; the authored layer measured here is the parallel clip's");
            }
        }
        // The fix, pinned per link: the two tail links whose authored rotation is not zero - 32.5
        // degrees on the root link and 127.5 on Tail2, which carries the whole tip section - are
        // drawn at their authored rotation under the shipped offset rule (the animated values the
        // clips give them are ~0 in these states), and the legacy replace rule draws exactly the fold
        // this round removed from them. The legacy numbers are kept as MEASURED controls: they are the
        // defect, reproduced by the rule that caused it and by no other.
        Segment tailRoot = rig.tail.get(0);
        Segment tail2 = rig.tail.get(1);
        assertEquals(32.5F, Math.toDegrees(Math.abs(runtime.bones[tailRoot.boneIndex].rx)), 0.1F,
                "the model authors a 32.5 degree rotation on the tail's root link");
        assertEquals(127.5F, Math.toDegrees(Math.abs(runtime.bones[tail2.boneIndex].rx)), 0.1F,
                "the model authors a 127.5 degree rotation on Tail2, which carries the tip section");
        float shippedRoot = worstOver(shippedStates, "Tail");
        float shippedTail2 = worstOver(shippedStates, "Tail2");
        float legacyRoot = worstOver(legacyStates, "Tail");
        float legacyTail2 = worstOver(legacyStates, "Tail2");
        assertTrue(shippedRoot <= 1.0F && shippedTail2 <= 1.0F,
                "under the shipped offset rule the clip's value is an offset from the authored "
                        + "rotation, so neither link leaves the shape the model was built with: root "
                        + fmt(shippedRoot) + ", Tail2 " + fmt(shippedTail2));
        assertEquals(32.5F, legacyRoot, 0.5F,
                "under the legacy replace rule the authored clip drew the root link at the animation's "
                        + "value, i.e. 32.5 degrees from the rotation the model authored - the defect "
                        + "this round fixed; reads " + fmt(legacyRoot));
        assertEquals(127.5F, legacyTail2, 0.5F,
                "under the legacy replace rule the authored clip drew Tail2 127.5 degrees from the "
                        + "rotation the model authored; reads " + fmt(legacyTail2));
        // The control that makes the `legacy` column evidence rather than a second opinion: this
        // file's own composition, given production's rule, must be production's chain - every bone,
        // every frame.
        assertEquals(0.0F, controlWorst, 1.0E-4F,
                "this file's composition of the shipped offset rule must reproduce production's own "
                        + "chain for every bone and every frame; the largest disagreement is "
                        + controlWorst);
        // The defect, measured, and the numbers the fix must produce. Under the shipped offset rule
        // the authored layer is the shape the model was built with - the fold is gone - while the
        // legacy rule's fold is one fixed displacement of the tip, the same in every one of the six
        // states to the millimetre, because the clips' own springs have no input in these states and
        // their variables are unset. That is why the authored layer was never the source of a SWAY:
        // a constant displacement has no motion to overshoot with.
        float idleMove = legacyStates.get("idle").tipMove;
        assertTrue(idleMove > 0.5F && idleMove < 1.2F,
                "the legacy rule must draw the tip most of a block from where the pose alone puts "
                        + "it; reads " + fmt(idleMove));
        for (String state : TIP_STATES) {
            assertEquals(idleMove, legacyStates.get(state).tipMove, 1.0E-3F,
                    "the legacy rule's displacement must be the same fold in " + state
                            + " as in idle; reads " + fmt(legacyStates.get(state).tipMove));
            assertTrue(shippedStates.get(state).tipMove < 0.05F,
                    "the shipped offset rule must leave the tip where the model was built, in " + state
                            + "; reads " + fmt(shippedStates.get(state).tipMove));
        }
        // The control that ties this round to the last one: under the offset rule the authored chain
        // is the identity to a thousandth of a degree, so the drawn tail must be the tail the
        // previous round measured with no authored layer at all - whereas the legacy rule's fold
        // changed the drawn motion as well as the shape.
        Tip sprintPlain = tipRun(rig, Setup.shipped(), "sprint");
        assertEquals(sprintPlain.tipSpeed, physicsSpeeds.get("sprint"), 0.01F,
                "with the offset rule composed, the physics must draw the tip at the speed the "
                        + "previous round measured; reads " + fmt(physicsSpeeds.get("sprint"))
                        + " against " + fmt(sprintPlain.tipSpeed));
        Tip yawPlain = tipRun(rig, Setup.shipped(), "yaw-sway");
        assertEquals(yawPlain.tipSpeed, physicsSpeeds.get("yaw-sway"), 0.01F,
                "with the offset rule composed, the physics must draw the tip at the speed the "
                        + "previous round measured in the yaw sway; reads "
                        + fmt(physicsSpeeds.get("yaw-sway")) + " against "
                        + fmt(yawPlain.tipSpeed));
        assertTrue(Math.abs(physicsSpeedsLegacy.get("sprint") - sprintPlain.tipSpeed) > 0.3F,
                "under the legacy rule the fold changed the drawn tip's travel too - it is a "
                        + "different, longer lever; reads " + fmt(physicsSpeedsLegacy.get("sprint"))
                        + " against " + fmt(sprintPlain.tipSpeed));
        // Nothing about the physics changes: the authored layer writes the mesh, not the solver. The
        // battle-mode path is not this rule's business and its numbers must stay put.
        assertTrue(physicsSpeeds.get("yaw-sway") > 1.0F,
                "with the authored layer composed the physics still draws the tip travelling in the "
                        + "yaw sway; reads " + fmt(physicsSpeeds.get("yaw-sway")));
        assertEquals(1.0F, shippedStates.get("yaw-sway").pinned.getOrDefault("Tail2", 0.0F), 0.001F,
                "the physics must still draw the chain on its allowance in the yaw sway with the "
                        + "authored layer composed on top");
        // The blast radius, as a fact: the rule is not the tail's alone, and the shipped rule leaves
        // the pieces the rule does not touch exactly where they were.
        assertTrue(movedLegacy > 3,
                "the legacy rule must move more than the tail's own links; " + movedLegacy
                        + " piece(s) outside the tail move");
        assertTrue(ruleSensitiveLegacy > 0,
                "the rule must be shown to move pieces outside the tail that the shipped rule does "
                        + "not: pieces whose legacy drawing is at least a centimetre further from the "
                        + "composed rotation number " + ruleSensitiveLegacy);
        assertTrue(ruleSensitiveLegacy < movedLegacy,
                "not every piece outside the tail may be the rule's business - the eyes and eyelids "
                        + "are the model's own scale channels and must move by the same number under "
                        + "either rule; rule-sensitive " + ruleSensitiveLegacy + " of " + movedLegacy);
        // The named one, in numbers: the ears are what this rule was doing to the head.
        for (String ear : new String[]{"Right_ear", "Left_ear"}) {
            Float legacyEar = blastLegacy.get(ear);
            assertNotNull(legacyEar, "the rig must carry the ear piece " + ear);
            assertTrue(legacyEar > 0.5F,
                    "the legacy rule must draw " + ear + " most of a block from the composed "
                            + "rotation; reads " + fmt(legacyEar));
            assertTrue(blastShipped.getOrDefault(ear, 99.0F) < 0.05F,
                    "the shipped offset rule must leave " + ear + " on the head; reads "
                            + fmt(blastShipped.getOrDefault(ear, 99.0F)));
        }
    }

    /**
     * The production compile of a converted runtime JSON, without its on-disk lookup: the reader is
     * production's own ({@code YSMRuntimeModel#compile}), and only the path it would have resolved
     * from the game's config directory is supplied here.
     */
    private static YSMRuntimeModel runtimeModel(Path pack, String stem) throws Exception {
        Path file = pack.resolve("ysm_runtime/entity").resolve(stem + ".json");
        assertTrue(Files.isRegularFile(file), "the converted runtime for '" + stem + "' is not in " + pack);
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        // The camera section is a RealCamera bind target, registered through a bridge whose static
        // initialiser asks Forge's ModList whether RealCamera is installed - which is null in a unit
        // test. It has nothing to do with the bone table or the animations this probe measures, so the
        // key is dropped here rather than the compile being replaced.
        root.remove("camera");
        Method compile = YSMRuntimeModel.class.getDeclaredMethod("compile", String.class, JsonObject.class);
        compile.setAccessible(true);
        return (YSMRuntimeModel) compile.invoke(null, stem, root);
    }

    /** The angle of a child's delta over its parent's: what the link's own part delta does. */
    private static float ownAngle(OpenMatrix4f parent, OpenMatrix4f child) {
        Matrix4f own = new Matrix4f(Rig.effective(parent, new Matrix4f())).invert()
                .mul(Rig.effective(child, new Matrix4f()));
        float cosine = Math.max(-1.0F, Math.min(1.0F,
                (own.m00() + own.m11() + own.m22() - 1.0F) * 0.5F));
        float angle = (float) Math.toDegrees(Math.acos(cosine));
        return Float.isFinite(angle) ? angle : 0.0F;
    }

    /** The largest element-by-element difference of two published deltas. */
    private static float difference(OpenMatrix4f a, OpenMatrix4f b) {
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

    private static float worstOver(Map<String, Authored> states, String link) {
        float worst = 0.0F;
        for (Authored state : states.values()) {
            worst = Math.max(worst, state.ownDelta.getOrDefault(link, 0.0F));
        }
        return worst;
    }

    /** A bind-space point as the renderer draws it, with up to two factors after the pose. */
    private static Vector3f drawnWith(Rig rig, Segment link, Vector3f vertex,
                                      YsmMeshSecondaryMotion.PoseSource pose, OpenMatrix4f... after) {
        return drawnAtJoint(rig, link.joint, vertex, pose, after);
    }

    /** The same, for a piece named by its joint rather than by a segment. */
    private static Vector3f drawnAtJoint(Rig rig, int joint, Vector3f vertex,
                                         YsmMeshSecondaryMotion.PoseSource pose, OpenMatrix4f... after) {
        OpenMatrix4f skinning = new OpenMatrix4f();
        skinning.load(rig.deformationFor(pose, joint));
        for (OpenMatrix4f factor : after) {
            if (factor != null) {
                skinning.mulBack(factor);
            }
        }
        return YsmMeshSecondaryMotion.transformPoint(skinning, new Vector3f(vertex), new Vector3f());
    }

    /** The tip's own vertex to the link above it, in the two ways of drawing the chain. */
    private static void record(Rig rig, YsmMeshSecondaryMotion.State state,
                               YsmMeshSecondaryMotion.PoseSource pose, Map<Integer, Matrix4f> probeDeltas,
                               int frame, Tip out) {
        for (int i = 1; i < rig.tail.size(); i++) {
            Segment link = rig.tail.get(i);
            Segment parent = rig.tail.get(i - 1);
            float render = rig.renderSeparation(link, parent, state, pose);
            float probe = rig.separation(link, probeDeltas.get(link.index),
                    parent, probeDeltas.get(parent.index), pose);
            out.render.merge(link.name, render, Math::max);
            out.probe.merge(link.name, probe, Math::max);
            // The closest approach is O(vertices x vertices) per joint; every fourth frame is enough
            // for a worst case that is held for many frames at a time.
            if (frame % 4 == 0) {
                out.near.merge(link.name, rig.nearSeparation(link, parent, state, pose), Math::max);
            }
            // Where the link's farthest vertex is drawn, against where the parent's delta alone would
            // put the same vertex: the one number that says whether the link is carried by the link
            // above it or swings on its own.
            Vector3f far = rig.farthest(link);
            out.step.merge(link.name,
                    rig.renderPoint(link.index, far, state, pose)
                            .distance(rig.renderPoint(parent.index, far, state, pose)),
                    Math::max);
            // What the link's own delta does, read off the drawn chain: the rotation that carries the
            // parent's drawn delta to this one, and where its centre is relative to the link's hinge.
            float[] own = rig.ownRotation(state, parent.index, link.index);
            out.applied.merge(link.name, own[0], Math::max);
            out.centre.merge(link.name,
                    new Vector3f(own[1], own[2], own[3])
                            .distance(rig.part(link.index).bindAnchor()),
                    Math::max);
        }
        for (Segment piece : rig.tail) {
            out.own.merge(piece.name, state.lastDegrees[piece.index], Float::sum);
            out.allowed.merge(piece.name, (float) Math.toDegrees(state.chainBudget[piece.index]),
                    Float::sum);
            out.raw.merge(piece.name, (float) Math.toDegrees(state.states[piece.index].lastAngle),
                    Float::sum);
            out.composed.merge(piece.name, rotationAngleOf(state.deltas[piece.index]), Math::max);
            out.minOwn.merge(piece.name, state.lastDegrees[piece.index], Math::min);
            out.maxOwn.merge(piece.name, state.lastDegrees[piece.index], Math::max);
            out.pinned.merge(piece.name,
                    state.lastDegrees[piece.index] >= Math.toDegrees(state.chainBudget[piece.index])
                            - 0.01F && state.lastDegrees[piece.index] > 0.5F ? 1.0F : 0.0F,
                    Float::sum);
            out.counts.merge(piece.name, 1.0F, Float::sum);
        }
    }

    private static Tip tipRun(Rig rig, Setup setup, String state) throws IOException {
        boolean sway = "stop-sway".equals(state);
        boolean yawSway = "yaw-sway".equals(state);
        float lean = "idle".equals(state) || "fall".equals(state) ? 0.0F
                : "walk".equals(state) || yawSway ? WALK_LEAN : SPRINT_LEAN;
        float speed = "idle".equals(state) || "fall".equals(state) ? 0.0F
                : "walk".equals(state) || yawSway ? WALK_SPEED : SPRINT_SPEED;
        boolean cycle = !"idle".equals(state) && !"fall".equals(state);
        Vector3f velocity = "fall".equals(state) ? new Vector3f(0.0F, -1.6F, 0.0F)
                : new Vector3f(0.0F, 0.0F, -speed);
        float total = TIP_STEADY_SECONDS + (sway ? TIP_SWAY_RAMP + TIP_SWAY_AFTER : 0.0F);

        YsmPhysicsParts.Model parts = rig.model();
        YsmMeshSecondaryMotion.State engine = new YsmMeshSecondaryMotion.State(
                parts, null, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        applySetup(parts, engine, setup);
        TipPose pose = new TipPose(rig, lean, cycle);

        Tip out = new Tip();
        List<Vector3f> path = new ArrayList<>();
        int frames = (int) (total / DT);
        int warmup = (int) (TIP_WARMUP_SECONDS / DT);
        int swayStart = (int) (TIP_STEADY_SECONDS / DT);
        int swayEnd = swayStart + (int) (TIP_SWAY_RAMP / DT);
        Vector3f stepStart = null;
        for (int frame = 0; frame < frames; frame++) {
            float t = frame * DT;
            Vector3f at = velocity;
            float[] turn = NO_TURN;
            if (sway) {
                float k = Math.max(0.0F, Math.min(1.0F, (t - TIP_STEADY_SECONDS) / TIP_SWAY_RAMP));
                pose.drive(lean * (1.0F - k), 1.0F - k, 1.0F - k);
                at = new Vector3f(velocity).mul(1.0F - k);
            }
            if (yawSway) {
                // The body turning left and right while it walks: the pose turns with it, and the
                // solver is handed the turn rate and its acceleration, which is what the tail's drag
                // term reads. Measured by differencing the same schedule rather than invented.
                double omega = 2.0D * Math.PI * YAW_HZ;
                float degrees = YAW_SWING_DEGREES * (float) Math.sin(omega * t);
                float rate = YAW_SWING_DEGREES * (float) (omega * Math.cos(omega * t));
                float accel = -YAW_SWING_DEGREES * (float) (omega * omega * Math.sin(omega * t));
                pose.yaw(degrees);
                turn = new float[]{(float) Math.toRadians(rate), (float) Math.toRadians(accel)};
            }
            pose.at(t);
            YsmMeshSecondaryMotion.simulate(engine, pose, DT, at, turn,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
            if (frame == swayStart) {
                stepStart = rig.renderTip(rig.tail.get(rig.tail.size() - 1), engine, pose);
            }
            if (frame < warmup) {
                continue;
            }
            Map<Integer, Matrix4f> probeDeltas = new HashMap<>();
            for (Segment piece : rig.tail) {
                probeDeltas.put(piece.index, rig.drawnDelta(engine, piece.index, probeDeltas));
            }
            record(rig, engine, pose, probeDeltas, frame, out);
            path.add(rig.renderTip(rig.tail.get(rig.tail.size() - 1), engine, pose));
            // Every piece the candidate does not name, drawn, so its blast radius is a measurement
            // rather than an argument: the states are identical frame for frame.
            int outside = 0;
            for (Segment piece : rig.segments) {
                if (!isTailBone(piece.name)) {
                    outside++;
                }
            }
            Vector3f[] points = new Vector3f[outside];
            int at2 = 0;
            for (Segment piece : rig.segments) {
                if (!isTailBone(piece.name)) {
                    points[at2++] = rig.renderPoint(piece.index, piece.bindPivot, engine, pose);
                }
            }
            out.trace.add(points);
        }
        for (String name : TAIL) {
            Float count = out.counts.get(name);
            if (count != null && count > 0.0F) {
                out.pinned.put(name, out.pinned.get(name) / count);
                out.own.put(name, out.own.get(name) / count);
                out.allowed.put(name, out.allowed.get(name) / count);
                out.raw.put(name, out.raw.get(name) / count);
                out.range.put(name, out.maxOwn.get(name) - out.minOwn.get(name));
            }
        }
        out.tipSpeed = tipSpeedOf(path);
        out.tipSpread = spreadOf(path);
        out.tipGap = out.render.getOrDefault(TAIL[TAIL.length - 1], 0.0F);
        out.tipGapProbe = out.probe.getOrDefault(TAIL[TAIL.length - 1], 0.0F);
        if (sway && stepStart != null && path.size() > 8) {
            List<Vector3f> tailPath = path.subList(path.size() - (int) (0.5F / DT), path.size());
            Vector3f settled = new Vector3f();
            for (Vector3f point : tailPath) {
                settled.add(point);
            }
            settled.div(tailPath.size());
            Vector3f step = new Vector3f(settled).sub(stepStart);
            out.stepBlocks = step.length();
            if (out.stepBlocks > 1.0E-4F) {
                Vector3f direction = new Vector3f(step).normalize();
                Vector3f past = new Vector3f();
                float worst = -Float.MAX_VALUE;
                int startAt = Math.max(0, swayStart - warmup);
                for (int i = startAt; i < path.size(); i++) {
                    float along = new Vector3f(path.get(i)).sub(settled).dot(direction);
                    worst = Math.max(worst, along);
                }
                out.overshoot = worst / out.stepBlocks;
                // How long the tip takes to stay inside five per cent of the step, measured from the
                // end of the ramp. NaN when it never settles.
                out.settleSeconds = Float.NaN;
                for (int i = Math.max(0, swayEnd - warmup); i < path.size(); i++) {
                    boolean settledAfter = true;
                    for (int j = i; j < path.size(); j++) {
                        if (new Vector3f(path.get(j)).sub(settled).length() > 0.05F * out.stepBlocks) {
                            settledAfter = false;
                            break;
                        }
                    }
                    if (settledAfter) {
                        out.settleSeconds = (i + warmup - swayEnd) * DT;
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static void applySetup(YsmPhysicsParts.Model parts, YsmMeshSecondaryMotion.State state,
                                   Setup setup) {
        YsmPhysicsParts.Segment[] segments = parts.segments();
        for (int i = 0; i < segments.length; i++) {
            Float degrees = setup.limits().get(segments[i].boneName().toLowerCase(Locale.ROOT));
            if (degrees != null) {
                state.limit[i] = (float) Math.toRadians(degrees);
            }
        }
        if (setup.chainTotalDeg() > 0.0F) {
            for (int i = 0; i < segments.length; i++) {
                if (setup.totalIsTailOnly() && !isTailBone(segments[i].boneName())) {
                    continue;
                }
                state.pieceLimit[i] = (float) Math.toRadians(setup.chainTotalDeg());
            }
        }
    }

    /** The largest distance any piece outside the tail is drawn from where the reference run drew it. */
    private static float cost(Tip reference, Tip candidate) {
        float worst = 0.0F;
        int frames = Math.min(reference.trace.size(), candidate.trace.size());
        for (int frame = 0; frame < frames; frame++) {
            Vector3f[] was = reference.trace.get(frame);
            Vector3f[] now = candidate.trace.get(frame);
            for (int i = 0; i < Math.min(was.length, now.length); i++) {
                worst = Math.max(worst, was[i].distance(now[i]));
            }
        }
        return worst;
    }

    private static boolean isTailBone(String name) {        for (String tail : TAIL) {
            if (tail.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String point(Vector3f value) {
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", value.x, value.y, value.z);
    }

    /** The name of a simulated piece by its index in the assembled set, for the tables. */
    private static String nameOf(Rig rig, int index) {
        if (index < 0 || index >= rig.segments.size()) {
            return "-";
        }
        return rig.segments.get(index).name;
    }

    // ------------------------------------------------------------------
    // One candidate
    // ------------------------------------------------------------------

    /** What one run measured, per tail link and for the tip. */
    private static final class Run {
        final Map<String, float[]> link = new LinkedHashMap<>();
        float worstSeparation;
        String worstLink;
        /** The tip's own joint: the separation the user is looking at. */
        float tipSeparation;
        float tipSpeed;
        float tipSpread;
        float composed;

        String report(String label) {
            StringBuilder out = new StringBuilder();
            out.append("## ").append(label).append("\n\n")
                    .append("| link | own | allowed | pinned | drawn range | clipped | worst clip | ")
                    .append("composed | tip speed | separation |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|\n");
            for (String name : TAIL) {
                float[] v = link.get(name);
                if (v == null) {
                    continue;
                }
                out.append("| `").append(name).append("` | ").append(fmt(v[0])).append(" | ")
                        .append(fmt(v[1])).append(" | ").append(fmt(v[2])).append(" | ")
                        .append(fmt(v[3])).append(" | ").append(fmt(v[4])).append(" | ")
                        .append(fmt(v[5])).append(" | ").append(fmt(v[6])).append(" | ")
                        .append(fmt(v[8])).append(" | ").append(fmt(v[7])).append(" |\n");
            }
            out.append("\nworst separation ").append(fmt(worstSeparation)).append(" blocks on `")
                    .append(worstLink).append("`; **tip separation ").append(fmt(tipSeparation))
                    .append(" blocks**; tip drawn at ").append(fmt(tipSpeed))
                    .append(" blocks/s over a region ").append(fmt(tipSpread))
                    .append(" blocks across; tip composed bend ").append(fmt(composed))
                    .append(" degrees.\n\n");
            return out.toString();
        }
    }

    private static Run run(Rig rig, Map<String, Float> limits) {
        YsmPhysicsParts.Model parts = rig.model();
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                parts, null, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        for (int i = 0; i < parts.segments().length; i++) {
            Float degrees = limits.get(parts.segments()[i].boneName().toLowerCase(Locale.ROOT));
            if (degrees != null) {
                state.limit[i] = (float) Math.toRadians(degrees);
            }
        }
        RunPose pose = new RunPose(rig, SPRINT_LEAN, SPRINT_SPEED);
        Vector3f velocity = new Vector3f(0.0F, 0.0F, -SPRINT_SPEED);
        Map<String, List<Float>> own = new LinkedHashMap<>();
        Map<String, List<Float>> raw = new LinkedHashMap<>();
        Map<String, List<Float>> allowed = new LinkedHashMap<>();
        Map<String, List<Float>> composed = new LinkedHashMap<>();
        Map<String, List<Float>> separation = new LinkedHashMap<>();
        Map<String, List<Vector3f>> tips = new LinkedHashMap<>();
        int frames = 0;
        for (int frame = 0; frame < FRAMES; frame++) {
            pose.at(frame * DT);
            YsmMeshSecondaryMotion.simulate(state, pose, DT, velocity, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
            if (frame < WARMUP) {
                continue;
            }
            frames++;
            Map<Integer, Matrix4f> deltas = new HashMap<>();
            for (Segment piece : rig.tail) {
                deltas.put(piece.index, rig.drawnDelta(state, piece.index, deltas));
                own.computeIfAbsent(piece.name, key -> new ArrayList<>())
                        .add(state.lastDegrees[piece.index]);
                raw.computeIfAbsent(piece.name, key -> new ArrayList<>())
                        .add((float) Math.toDegrees(state.states[piece.index].lastAngle));
                allowed.computeIfAbsent(piece.name, key -> new ArrayList<>())
                        .add((float) Math.toDegrees(state.chainBudget[piece.index]));
                composed.computeIfAbsent(piece.name, key -> new ArrayList<>())
                        .add(rotationAngleOf(state.deltas[piece.index]));
                tips.computeIfAbsent(piece.name, key -> new ArrayList<>())
                        .add(rig.tipPosition(piece, deltas.get(piece.index), pose));
            }
            for (int i = 1; i < rig.tail.size(); i++) {
                Segment link = rig.tail.get(i);
                Segment parent = rig.tail.get(i - 1);
                float gap = rig.separation(link, deltas.get(link.index), parent,
                        deltas.get(parent.index), pose);
                separation.computeIfAbsent(link.name, key -> new ArrayList<>()).add(gap);
            }
        }
        Run out = new Run();
        for (String name : TAIL) {
            List<Float> ownValues = own.get(name);
            if (ownValues == null) {
                continue;
            }
            List<Float> rawValues = raw.get(name);
            List<Float> allowedValues = allowed.get(name);
            List<Float> separationValues = separation.get(name);
            int clipped = 0;
            float worstClip = 0.0F;
            int pinned = 0;
            for (int i = 0; i < ownValues.size(); i++) {
                if (rawValues.get(i) - ownValues.get(i) > 0.1F) {
                    clipped++;
                }
                worstClip = Math.max(worstClip, rawValues.get(i) - ownValues.get(i));
                if (ownValues.get(i) > 0.5F && ownValues.get(i) >= allowedValues.get(i) - 0.01F) {
                    pinned++;
                }
            }
            float separationWorst = 0.0F;
            if (separationValues != null) {
                for (float value : separationValues) {
                    separationWorst = Math.max(separationWorst, value);
                }
            }
            out.link.put(name, new float[]{
                    mean(ownValues), mean(allowedValues), (float) pinned / ownValues.size(),
                    max(ownValues) - min(ownValues), (float) clipped / ownValues.size(), worstClip,
                    max(composed.get(name)), separationWorst, tipSpeedOf(tips.get(name))});
            if (separationWorst > out.worstSeparation) {
                out.worstSeparation = separationWorst;
                out.worstLink = name;
            }
            if (name.equals(TAIL[TAIL.length - 1])) {
                out.tipSeparation = separationWorst;
            }
        }
        List<Vector3f> tipPath = tips.get(TAIL[TAIL.length - 1]);
        out.tipSpeed = tipSpeedOf(tipPath);
        out.tipSpread = spreadOf(tipPath);
        out.composed = max(composed.get(TAIL[TAIL.length - 1]));
        return out;
    }

    /** Blocks per second the tip is drawn travelling: the plainest reading of "all over the place". */
    private static float tipSpeedOf(List<Vector3f> path) {
        float travelled = 0.0F;
        for (int i = 1; i < path.size(); i++) {
            travelled += path.get(i).distance(path.get(i - 1));
        }
        return path.size() < 2 ? 0.0F : travelled / ((path.size() - 1) * DT);
    }

    /** How wide a region the tip swept, blocks. */
    private static float spreadOf(List<Vector3f> path) {
        if (path.isEmpty()) {
            return 0.0F;
        }
        Vector3f lo = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f hi = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (Vector3f point : path) {
            lo.min(point);
            hi.max(point);
        }
        return lo.distance(hi);
    }

    private static float mean(List<Float> values) {
        float sum = 0.0F;
        for (float value : values) {
            sum += value;
        }
        return values.isEmpty() ? 0.0F : sum / values.size();
    }

    private static float max(List<Float> values) {
        float best = -Float.MAX_VALUE;
        for (float value : values) {
            best = Math.max(best, value);
        }
        return values.isEmpty() ? 0.0F : best;
    }

    private static float min(List<Float> values) {
        float best = Float.MAX_VALUE;
        for (float value : values) {
            best = Math.min(best, value);
        }
        return values.isEmpty() ? 0.0F : best;
    }

    private static float rotationAngleOf(OpenMatrix4f matrix) {
        float trace = matrix.m00 + matrix.m11 + matrix.m22;
        float cosine = Math.max(-1.0F, Math.min(1.0F, (trace - 1.0F) * 0.5F));
        float angle = (float) Math.acos(cosine);
        return Float.isFinite(angle) ? (float) Math.toDegrees(angle) : 0.0F;
    }

    // ------------------------------------------------------------------
    // The pose
    // ------------------------------------------------------------------

    /** A body running: the torso leaning and bobbing, the legs cycling, a constant velocity. */
    private static final class RunPose implements YsmMeshSecondaryMotion.PoseSource {
        private static final OpenMatrix4f TO_ORIGIN = new OpenMatrix4f();
        private final Rig rig;
        private final float lean;
        private final float speed;
        private OpenMatrix4f torso = new OpenMatrix4f();
        private OpenMatrix4f chest = new OpenMatrix4f();
        private OpenMatrix4f head = new OpenMatrix4f();
        private OpenMatrix4f thighRight = new OpenMatrix4f();
        private OpenMatrix4f thighLeft = new OpenMatrix4f();
        private OpenMatrix4f legRight = new OpenMatrix4f();
        private OpenMatrix4f legLeft = new OpenMatrix4f();

        RunPose(Rig rig, float lean, float speed) {
            this.rig = rig;
            this.lean = lean;
            this.speed = speed;
            at(0.0F);
        }

        void at(float seconds) {
            float phase = (float) (2.0D * Math.PI * 1.60D * seconds);
            float bob = 0.040F * (float) Math.sin(2.0F * phase);
            torso = about(rig.jointOrigin(JointTable.TORSO), lean, bob);
            chest = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.CHEST),
                            lean * 0.55F + 2.5F * (float) Math.sin(2.0F * phase), bob * 0.5F),
                    new OpenMatrix4f());
            head = OpenMatrix4f.mul(chest,
                    about(rig.jointOrigin(JointTable.HEAD),
                            -lean * 0.45F + 3.5F * (float) Math.sin(2.0F * phase + 1.0F), 0.0F),
                    new OpenMatrix4f());
            thighRight = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.THIGH_R),
                            34.0F * (float) Math.sin(phase), 0.0F), new OpenMatrix4f());
            thighLeft = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.THIGH_L),
                            34.0F * (float) Math.sin(phase + Math.PI), 0.0F), new OpenMatrix4f());
            legRight = OpenMatrix4f.mul(thighRight,
                    about(rig.jointOrigin(JointTable.LEG_R),
                            28.0F * (float) Math.sin(phase + 2.0F), 0.0F), new OpenMatrix4f());
            legLeft = OpenMatrix4f.mul(thighLeft,
                    about(rig.jointOrigin(JointTable.LEG_L),
                            28.0F * (float) Math.sin(phase + Math.PI + 2.0F), 0.0F),
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

    private static OpenMatrix4f about(Vector3f origin, float degrees, float bob) {
        Matrix4f matrix = new Matrix4f()
                .translate(origin.x, origin.y + bob, origin.z)
                .rotateX((float) Math.toRadians(degrees))
                .translate(-origin.x, -origin.y, -origin.z);
        return toOpen(matrix);
    }

    /** The same, about the model's own vertical: the body's turn. */
    private static OpenMatrix4f yawAbout(Vector3f origin, float degrees) {
        Matrix4f matrix = new Matrix4f()
                .translate(origin.x, origin.y, origin.z)
                .rotateY((float) Math.toRadians(degrees))
                .translate(-origin.x, -origin.y, -origin.z);
        return toOpen(matrix);
    }

    // ------------------------------------------------------------------
    // The rig: the skeleton, the mesh, the segments and the armature
    // ------------------------------------------------------------------

    private static final class Segment {
        final int index;
        final int boneIndex;
        final String name;
        final int joint;
        final Vector3f bindPivot;
        final Vector3f bindRest;
        final float lever;
        final List<Vector3f> own;

        Segment(int index, int boneIndex, String name, int joint, Vector3f bindPivot, Vector3f bindRest,
                float lever, List<Vector3f> own) {
            this.index = index;
            this.boneIndex = boneIndex;
            this.name = name;
            this.joint = joint;
            this.bindPivot = bindPivot;
            this.bindRest = bindRest;
            this.lever = lever;
            this.own = own;
        }
    }

    private static final class Rig {
        final String modelId;
        final YSMRuntimeModel.BoneRt[] bones;
        final Map<String, Integer> boneIndex = new HashMap<>();
        final Map<Integer, List<Vector3f>> vertices = new HashMap<>();
        final Map<Integer, float[]> geometry = new HashMap<>();
        final Map<Integer, Vector3f> pivotCache = new HashMap<>();
        final List<Segment> segments = new ArrayList<>();
        final List<Segment> tail = new ArrayList<>();
        /** Resolved parent links and piece sizes, kept so the production records can be built. */
        final Map<Integer, Integer> resolvedParent = new HashMap<>();
        /** The chain the model authored: index -> the index of the link it hangs from. */
        final Map<Integer, Integer> chainParent = new HashMap<>();
        final Map<Integer, Integer> pieceSize = new HashMap<>();
        final Map<Integer, Integer> pieceDepth = new HashMap<>();
        final Map<Integer, OpenMatrix4f> restWorld = new HashMap<>();
        final Set<String> loggedSimulated = new HashSet<>();
        final float scaleX;
        final float scaleY;
        private YsmPhysicsParts.Model model;

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
                for (JsonElement index : indices) {
                    int at = index.getAsInt() * 3;
                    if (at + 2 >= positions.length) {
                        continue;
                    }
                    points.add(new Vector3f(positions[at], positions[at + 2], -positions[at + 1]));
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
            for (String name : loggedSimulatedCsv.split(",")) {
                rig.loggedSimulated.add(name.trim());
            }
            rig.buildSegments(partOrdinals);
            rig.buildArmature(runtime, mesh, hidden);
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

        private void buildSegments(Map<Integer, int[]> partOrdinals) {
            java.util.function.IntPredicate ownsGeometry =
                    index -> YsmPhysicsParts.ownsItsGeometry(index, geometry, partOrdinals);
            List<Integer> selected = YsmPhysicsParts.selectBones(bones, ownsGeometry,
                    YsmPhysicsTuning.maxChains(), new int[1]);
            List<Integer> drafts = new ArrayList<>();
            for (int bone : selected) {
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
                Segment segment = new Segment(i, bone, rt.name, rt.joint, pivot, rest, rest.length(), own);
                segments.add(segment);
                chainParent.put(i, parentOf[i]);
                resolvedParent.put(i, parentOf[i]);
                int root = i;
                for (int at = i, guard = 0; parentOf[at] >= 0 && guard++ <= drafts.size();
                     at = parentOf[at]) {
                    root = parentOf[at];
                }
                pieceSize.put(i, Math.max(1, size[root]));
                pieceDepth.put(i, depth[i]);
            }
            assertEquals(loggedSimulated.size(), segments.size(),
                    "the assembled simulated set must be the one the client logged; assembled "
                            + segments.size() + ", logged " + loggedSimulated.size());
            for (Segment segment : segments) {
                assertTrue(loggedSimulated.contains(segment.name),
                        segment.name + " is not in the set the client logged for this model");
            }
            for (String name : TAIL) {
                for (Segment segment : segments) {
                    if (segment.name.equals(name)) {
                        tail.add(segment);
                    }
                }
            }
            assertEquals(TAIL.length, tail.size(), "the model's tail must be the seven named links");
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

        YsmPhysicsParts.Model model() {
            return model;
        }

        /** The production segment behind a rig piece: the one the frame path actually simulates. */
        YsmPhysicsParts.Segment part(int index) {
            return model.segments()[index];
        }

        /** The declared parent link of a piece: the chain the model itself authored. */
        int parentOf(int index) {
            return chainParent.getOrDefault(index, -1);
        }

        /**
         * The drawn transform of a piece: its parents' deltas first, then its own - the order the
         * mesh applies them in.
         */
        Matrix4f drawnDelta(YsmMeshSecondaryMotion.State state, int index,
                            Map<Integer, Matrix4f> computed) {
            Matrix4f cached = computed.get(index);
            if (cached != null) {
                return cached;
            }
            Matrix4f out = new Matrix4f();
            int parent = parentOf(index);
            if (parent >= 0) {
                out.set(drawnDelta(state, parent, computed));
            } else {
                out.identity();
            }
            out.mul(state.jomlDeltas[index]);
            computed.put(index, out);
            return out;
        }

        OpenMatrix4f deformationFor(YsmMeshSecondaryMotion.PoseSource pose, int joint) {
            OpenMatrix4f toOrigin = pose.toOriginOf(joint);
            OpenMatrix4f jointPose = pose.poseOf(joint);
            if (toOrigin == null || jointPose == null) {
                return new OpenMatrix4f();
            }
            return OpenMatrix4f.mul(jointPose, toOrigin, new OpenMatrix4f());
        }

        /**
         * How far the drawn chain has opened at a joint, <b>over and above where the mesh already
         * is</b>: the distance between the link's own mounting vertex and the link above it, drawn,
         * minus the same distance in the bind pose. The bind overhang is the model's own geometry
         * and is not a defect; what a ceiling adds on top of it is.
         *
         * <p>The mounting vertex is the link's own vertex nearest the link above in the bind pose,
         * and the same vertex of the parent is used for the bind reference, so one pair of points is
         * followed through the whole run rather than a different nearest pair being chosen every
         * frame - which would read the two pieces sliding past each other as separation.
         */
        float separation(Segment link, Matrix4f linkDelta, Segment parent, Matrix4f parentDelta,
                         YsmMeshSecondaryMotion.PoseSource pose) {
            Vector3f[] pair = mountPair(link, parent);
            OpenMatrix4f linkDeformation = deformationFor(pose, link.joint);
            OpenMatrix4f parentDeformation = deformationFor(pose, parent.joint);
            Vector3f a = new Vector3f(pair[0]);
            YsmMeshSecondaryMotion.transformPoint(linkDeformation, a, a);
            linkDelta.transformPosition(a);
            Vector3f b = new Vector3f(pair[1]);
            YsmMeshSecondaryMotion.transformPoint(parentDeformation, b, b);
            parentDelta.transformPosition(b);
            return a.distance(b) - bindSeparation.getOrDefault(link.name, 0.0F);
        }

        /** The link's mounting vertex and the parent's nearest vertex to it, in the bind pose. */
        Vector3f[] mountPair(Segment link, Segment parent) {
            Vector3f[] cached = mountPairs.get(link.name);
            if (cached != null) {
                return cached;
            }
            float least = Float.MAX_VALUE;
            Vector3f mine = link.bindPivot;
            Vector3f theirs = parent.bindPivot;
            for (Vector3f a : link.own) {
                for (Vector3f b : parent.own) {
                    float distance = a.distance(b);
                    if (distance < least) {
                        least = distance;
                        mine = a;
                        theirs = b;
                    }
                }
            }
            Vector3f[] pair = {new Vector3f(mine), new Vector3f(theirs)};
            mountPairs.put(link.name, pair);
            bindSeparation.put(link.name, least);
            return pair;
        }

        /** link name -> the two vertices whose distance is followed. */
        final Map<String, Vector3f[]> mountPairs = new HashMap<>();
        /** link name -> that distance in the bind pose, blocks. */
        final Map<String, Float> bindSeparation = new HashMap<>();

        /** Where the link's farthest vertex is drawn. */
        Vector3f tipPosition(Segment link, Matrix4f delta, YsmMeshSecondaryMotion.PoseSource pose) {
            Vector3f farthest = null;
            float best = -1.0F;
            for (Vector3f vertex : link.own) {
                float distance = vertex.distance(link.bindPivot);
                if (distance > best) {
                    best = distance;
                    farthest = vertex;
                }
            }
            Vector3f out = new Vector3f(farthest == null ? link.bindPivot : farthest);
            YsmMeshSecondaryMotion.transformPoint(deformationFor(pose, link.joint), out, out);
            delta.transformPosition(out);
            return out;
        }

        /**
         * Where a bind-space vertex is <b>drawn</b>, built the way the renderer builds it and with
         * no assumption of mine about which factor is applied first.
         *
         * <p>The renderer's own three lines ({@code YsmCpuRenderPath#tryPrecomputePartMatrix}
         * 479-482 beside {@code #skinVertexRigid} 509-511; the GPU path's own copy at
         * {@code YsmGpuRenderPath} 218-223) are {@code m = total[joint]; m.mulBack(partDelta);} and
         * then {@code p x m}. {@code partDelta} is the <b>one</b> matrix the simulation published
         * for this segment - {@code YSMMesh#setRuntimeTransformAt(ordinal, state.deltas[i])},
         * {@code YsmMeshSecondaryMotion} main 672 - and it is already composed under its parent's
         * (main 1639-1665), so nothing here composes a second time. Doing the multiply with Epic
         * Fight's own {@code mulBack}, rather than with two of my own in a row, is what keeps the
         * answer independent of the row/column convention this file would otherwise have to guess.
         */
        Vector3f renderPoint(int index, Vector3f bindVertex, YsmMeshSecondaryMotion.State state,
                             YsmMeshSecondaryMotion.PoseSource pose) {
            OpenMatrix4f skinning = new OpenMatrix4f();
            skinning.load(deformationFor(pose, segments.get(index).joint));
            skinning.mulBack(state.deltas[index]);
            return YsmMeshSecondaryMotion.transformPoint(skinning, new Vector3f(bindVertex),
                    new Vector3f());
        }

        /**
         * What the {@code index} link's own delta does, read off the two drawn transforms: the
         * rotation that carries the parent's drawn transform to the child's, as
         * {@code {degrees, centreX, centreY, centreZ}} with the centre in the space the deltas act
         * in - so it can be compared with the link's own hinge.
         */
        float[] ownRotation(YsmMeshSecondaryMotion.State state, int parentIndex, int index) {
            Matrix4f parent = effective(state.deltas[parentIndex], new Matrix4f());
            Matrix4f child = effective(state.deltas[index], new Matrix4f());
            Matrix4f own = new Matrix4f(parent).invert().mul(child);
            float cosine = Math.max(-1.0F, Math.min(1.0F,
                    (own.m00() + own.m11() + own.m22() - 1.0F) * 0.5F));
            float[] out = {(float) Math.toDegrees(Math.acos(cosine)), 0.0F, 0.0F, 0.0F};
            Matrix3f linear = new Matrix3f(own.m00(), own.m01(), own.m02(),
                    own.m10(), own.m11(), own.m12(), own.m20(), own.m21(), own.m22());
            linear.m00 -= 1.0F;
            linear.m11 -= 1.0F;
            linear.m22 -= 1.0F;
            if (linear.invert() != null) {
                Vector3f centre = linear.transform(
                        new Vector3f(-own.m03(), -own.m13(), -own.m23()));
                out[1] = centre.x;
                out[2] = centre.y;
                out[3] = centre.z;
            }
            return out;
        }

        /**
         * An {@code OpenMatrix4f} as the affine map {@code transformPoint} makes of it:
         * {@code p x M}, translation in m30..m32, so the linear part is the transpose of the stored
         * 3x3. Composing two of these with JOML answers "what does the child's delta do relative to
         * its parent's" without this file having to decide which factor is applied first.
         */
        private static Matrix4f effective(OpenMatrix4f m, Matrix4f out) {
            out.set(m.m00, m.m10, m.m20, m.m30,
                    m.m01, m.m11, m.m21, m.m31,
                    m.m02, m.m12, m.m22, m.m32,
                    0.0F, 0.0F, 0.0F, 1.0F);
            return out;
        }

        /** The gap at a joint as the renderer draws it: one composed delta per link, no more. */
        float renderSeparation(Segment link, Segment parent, YsmMeshSecondaryMotion.State state,
                              YsmMeshSecondaryMotion.PoseSource pose) {            Vector3f[] pair = mountPair(link, parent);
            Vector3f a = renderPoint(link.index, pair[0], state, pose);
            Vector3f b = renderPoint(parent.index, pair[1], state, pose);
            return a.distance(b) - bindSeparation.getOrDefault(link.name, 0.0F);
        }

        /**
         * How much further apart the two pieces are drawn than they were built, as the closest their
         * own vertices come: the visual gap the report is about, independent of which pair happens to
         * be nearest. Zero or negative means the two clouds are drawn no further apart than the model
         * authored them.
         */
        float nearSeparation(Segment link, Segment parent, YsmMeshSecondaryMotion.State state,
                             YsmMeshSecondaryMotion.PoseSource pose) {
            float least = Float.MAX_VALUE;
            for (Vector3f a : link.own) {
                Vector3f drawn = renderPoint(link.index, a, state, pose);
                for (Vector3f b : parent.own) {
                    least = Math.min(least,
                            drawn.distance(renderPoint(parent.index, b, state, pose)));
                }
            }
            if (!Float.isFinite(least)) {
                return 0.0F;
            }
            Float bind = nearestBind.get(link.name);
            if (bind == null) {
                bind = bindNear(link, parent);
                nearestBind.put(link.name, bind);
            }
            return least - bind;
        }

        /** The closest the two clouds come in the bind pose, the reference the gap is measured from. */
        private float bindNear(Segment link, Segment parent) {            float least = Float.MAX_VALUE;
            for (Vector3f a : link.own) {
                for (Vector3f b : parent.own) {
                    least = Math.min(least, a.distance(b));
                }
            }
            return Float.isFinite(least) ? least : 0.0F;
        }

        final Map<String, Float> nearestBind = new HashMap<>();

        /** Where the link's farthest vertex is drawn, by the renderer's own chain. */
        Vector3f renderTip(Segment link, YsmMeshSecondaryMotion.State state,
                           YsmMeshSecondaryMotion.PoseSource pose) {
            return renderPoint(link.index, farthest(link), state, pose);
        }

        /** The link's own vertex farthest from its bind pivot. */
        Vector3f farthest(Segment link) {
            Vector3f best = link.bindPivot;
            float distance = -1.0F;
            for (Vector3f vertex : link.own) {
                float candidate = vertex.distance(link.bindPivot);
                if (candidate > distance) {
                    distance = candidate;
                    best = vertex;
                }
            }
            return best;
        }

        private void buildArmature(JsonObject runtime, JsonObject mesh, Set<String> hidden)
                throws IOException {
            YsmBindArmature.GeometryInput input = geometryInput(modelId, runtime, mesh, hidden);
            List<String> warnings = new ArrayList<>();
            YsmBindArmature.GeometryData data = YsmBindArmature.collectGeometry(input, warnings::add);
            YsmBindArmature.BindPivots pivots = YsmBindArmature.computePivots(input, data, warnings::add);
            Joint rig = copyHierarchy(referenceBiped(), new OpenMatrix4f(), pivots.byJoint(), true);
            rig.initOriginTransform(new OpenMatrix4f());
            walkWorlds(rig, new OpenMatrix4f());
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

        /** A pose that puts every joint where the model was built: the metric's own control. */
        YsmMeshSecondaryMotion.PoseSource restPose() {
            return new YsmMeshSecondaryMotion.PoseSource() {
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
        }
    }

    private static YsmPhysicsParts.Model productionModel(Rig rig) {
        YsmPhysicsParts.Segment[] out = new YsmPhysicsParts.Segment[rig.segments.size()];
        for (int i = 0; i < out.length; i++) {
            Segment s = rig.segments.get(i);
            int parent = rig.resolvedParent.getOrDefault(i, -1);
            int inPiece = rig.pieceSize.getOrDefault(i, 1);
            int depth = rig.pieceDepth.getOrDefault(i, 0);
            // The hinge exactly as the frame path measures it (YsmPhysicsParts#build 1779), not the
            // convenience constructor's "hinge is the pivot": a piece turns about the patch of its own
            // geometry that touches what it rests on, pulled within its own lever of the pivot, and on
            // this model's tail that is a different point from the pivot.
            Vector3f anchor = YsmPhysicsParts.contactAnchor(s.own,
                    YsmPhysicsParts.restsOnGeometry(rig.bones, s.boneIndex, rig.vertices),
                    s.bindPivot, s.lever);
            out[i] = new YsmPhysicsParts.Segment(s.boneIndex, s.name, s.joint, new Vector3f(s.bindPivot),
                    new Vector3f(anchor),
                    new Vector3f(s.bindRest), s.lever,
                    YsmPhysicsParts.radiusFor(s.own, s.bindPivot, s.bindRest),
                    Math.max(0.25F, Math.min(4.0F, s.own.size() / 64.0F)),
                    (float) YsmPhysicsTuning.DEFAULTS.frequency(),
                    (float) YsmPhysicsTuning.DEFAULTS.dampingRatio(),
                    YsmPhysicsParts.swingLimit(parent >= 0, s.joint == JointTable.TORSO,
                            (float) YsmPhysicsTuning.DEFAULTS.maxAngle,
                            (float) YsmPhysicsTuning.DEFAULTS.maxAngleRoot),
                    parent, new int[0], false, new int[0],
                    YsmPhysicsParts.categoryOf(s.name));
        }
        return new YsmPhysicsParts.Model(out, YsmPhysicsParts.Source.BONE_NAMES, 0);
    }

    // ------------------------------------------------------------------
    // The armature helpers
    // ------------------------------------------------------------------

    private static Joint referenceBiped() throws IOException {
        String text;
        try (InputStream stream = YsmTailTipSeparationProbeTest.class
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

    private static String fmt(float value) {
        return Float.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "n/a";
    }
}
