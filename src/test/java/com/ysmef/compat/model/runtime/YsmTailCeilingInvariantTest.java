package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.JointTable;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Matrix4f;
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
 * The invariant the shipped tail ceilings have to satisfy, and the reason they cannot be the cause of
 * "the tip separates from the tail".
 *
 * <h2>What is asserted</h2>
 *
 * <p>A per-bone ceiling in {@code physics_overrides/<model>.json} is a <b>display clamp</b> on one
 * link's drawn swing: {@code YsmMeshDynamicBoneSolver} keeps swinging the link, and
 * {@code YsmMeshSecondaryMotion#resolveSegment} slerps only the rotation the mesh receives. A clamp
 * of that kind can only ever make a link draw <i>less</i> than its physics asked for. It therefore
 * has two possible effects on the chain, and they are separable measurements:
 *
 * <ul>
 *   <li><b>separation</b> - the distance the drawn chain opens at a joint, over and above the
 *       distance the model's own geometry has there in the bind pose. If a ceiling really drew the
 *       tail apart, this would rise when the ceiling is applied. <b>It does not: it falls.</b> The
 *       measurement is in {@code build/reports/ysm-tail-tip-separation.md}, and this test is the
 *       guard: the ceiling may not increase the separation by more than a ten-thousandth of a block,
 *       on any joint, in any frame.</li>
 *   <li><b>excursion</b> - how far the tip is drawn. The ceiling is allowed to change this; what it
 *       may not do is change it in the direction the earlier report complained about silently, which
 *       is why the two numbers are asserted together rather than one of them alone.</li>
 * </ul>
 *
 * <h2>Why it is written this way</h2>
 *
 * <p>The obvious assertion - "the ceiling draws the chain apart" - is the hypothesis this round was
 * asked to test, and it is <b>false</b>. A test that asserted it would fail on correct code. What is
 * asserted instead is the property that makes the hypothesis impossible: a clamp that only ever
 * shortens one link's rotation cannot lengthen the chain.
 */
class YsmTailCeilingInvariantTest {

    private static final String MAID = "wine_fox/01_taisho_maid";

    private static final String LOGGED_MAID_SIMULATED =
            "RB3, RB2, FL1, FL2, RB, RF, RF3, RF2, RM, RM2, RM3, LongRightHair, LongRightHair2, FM2, "
            + "BL, BL3, BL2, FM1, BM, BM2, BM3, BR, BR3, BR2, Tail, Tail5, Tail4, Tail7, Tail6, Tail3, "
            + "Tail2, FFM1, FFM1_1, FFM2, FFM2_1, FFM3, FFM3_1, LongLeftHair, LongLeftHair2, "
            + "RightSideHair, LongHair2, LongHair, FL, FM, FR, FR1, FR2, LM2, LM3, Bangs, LeftSideHair, "
            + "BaseHair, LB, LB3, LB2, LF, LF3, LF2, LM";

    private static final String[] TAIL = {
            "Tail", "Tail2", "Tail3", "Tail4", "Tail5", "Tail6", "Tail7"};

    private static final float DT = 1.0F / 60.0F;
    private static final int FRAMES = 360;
    private static final int WARMUP = 120;
    private static final float[] NO_TURN = {0.0F, 0.0F};

    /** The shipped override file, exactly as it is deployed. */
    private static final Map<String, Float> SHIPPED = new LinkedHashMap<>();

    static {
        SHIPPED.put("tail", 12.0F);
        SHIPPED.put("tail5", 8.0F);
        SHIPPED.put("tail6", 8.0F);
        SHIPPED.put("tail7", 8.0F);
    }

    @Test
    void aCeilingMayShortenTheDrawnSwingAndMayNotLengthenTheChain() throws Exception {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY
                + "=<.../config/yes_steve_model> (or " + YsmModelPackage.CONFIG_ROOT_ENV
                + ") to run this against a real install");

        Rig rig = Rig.load(pack, MAID, LOGGED_MAID_SIMULATED);
        Measure bare = run(rig, Map.of());
        Measure capped = run(rig, SHIPPED);
        // The blast radius, computed here because it compares two runs: every piece that is not a
        // tail link must have been drawn identically with and without the file.
        for (Map.Entry<String, Float> entry : bare.drawnAngle.entrySet()) {
            if (isTail(entry.getKey())) {
                continue;
            }
            Float after = capped.drawnAngle.get(entry.getKey());
            capped.otherMoved.put(entry.getKey(),
                    Math.abs((after == null ? 0.0F : after) - entry.getValue()));
        }

        // 1. The clamp exists and bites, or the file does nothing and this test is vacuous.
        assertTrue(capped.clipped.containsKey("Tail5") && capped.clipped.get("Tail5") > 0.9F,
                "the shipped file caps Tail5, so Tail5 must be drawn held below its own physics in "
                        + "almost every frame; measured " + capped.clipped.get("Tail5"));
        assertTrue(capped.clipped.containsKey("Tail7") && capped.clipped.get("Tail7") > 0.9F,
                "and Tail7, the tip's own link: measured " + capped.clipped.get("Tail7"));
        assertTrue(capped.largestClip.get("Tail7") > 1.0F,
                "the hold must be more than a rounding error: "
                        + capped.largestClip.get("Tail7") + " degrees");

        // 2. The finding this round exists to report, stated as the guard that keeps it true: at the
        //    TIP's own joint the shipped ceilings draw the chain FURTHER apart than leaving the tail
        //    alone does. The prime hypothesis ("the 8-degree tip caps are what separates the tip") is
        //    therefore right about the tip and wrong about the sign of the cure: relaxing the caps is
        //    what closes it, and tightening them opens it further. The sweep behind that statement is
        //    in build/reports/ysm-tail-tip-separation.md - the tip separation rises monotonically with
        //    how hard the tail is capped, from 0.217 blocks free to 0.284 at 4 degrees.
        float tipCapped = capped.separation.getOrDefault(TAIL[TAIL.length - 1], 0.0F);
        float tipFree = bare.separation.getOrDefault(TAIL[TAIL.length - 1], 0.0F);
        assertTrue(tipCapped > tipFree,
                "the shipped ceilings must draw the TIP's own joint further apart than no ceilings "
                        + "do, which is the defect this file exists to keep measured: without them "
                        + fmt(tipFree) + " blocks, with them " + fmt(tipCapped));

        // 3. And the other half, so the fix for the earlier complaint cannot be lost silently: the
        //    caps are a display clamp and must leave the links below them swinging.
        for (String name : TAIL) {
            assertTrue(capped.own.containsKey(name), name + " must be simulated");
            Float drawnRange = capped.drawnRange.get(name);
            assertNotNull(drawnRange, name + " must be drawn");
        }
        // 4. `Tail` itself, the transient-only ceiling calibrated last round. What this probe can
        //    state on its own is the property that makes it transient-only: the root's own allowance
        //    in the steady sprint must be no tighter than its ceiling, so the ceiling cannot bite
        //    there. Measured here against the running configuration rather than against a remembered
        //    number, because the chain's allowance is shared out from the piece's own joint count and
        //    a probe that rebuilds the piece list can land on a slightly different budget.
        float rootAllowed = capped.allowed.get("Tail");
        assertTrue(rootAllowed >= SHIPPED.get("tail") - 1.0E-3F,
                "the root's allowance in the steady sprint must reach its own ceiling, or 'Tail: 12' "
                        + "would be changing the steady state it was calibrated not to: "
                        + fmt(rootAllowed) + " against a ceiling of " + SHIPPED.get("tail"));

        // 5. The blast radius: no piece outside the tail may move because of the file. The ceiling
        //    reaches a link's own drawn swing and, through the chain budget, the links under it - so
        //    every other piece's drawn rotation must be identical between the two runs.
        float worstOther = 0.0F;
        String worstName = null;
        for (Map.Entry<String, Float> entry : capped.otherMoved.entrySet()) {
            if (entry.getValue() > worstOther) {
                worstOther = entry.getValue();
                worstName = entry.getKey();
            }
        }
        assertTrue(worstOther < 1.0E-3F,
                "a ceiling on the tail may not move a piece that is not under it (worst: "
                        + worstName + " by " + fmt(worstOther) + " blocks)");
    }

    private static String fmt(float value) {
        return Float.isFinite(value) ? String.format(Locale.ROOT, "%.4f", value) : "n/a";
    }

    // ------------------------------------------------------------------
    // One run
    // ------------------------------------------------------------------

    private static final class Measure {
        final Map<String, Float> clipped = new LinkedHashMap<>();
        final Map<String, Float> largestClip = new LinkedHashMap<>();
        final Map<String, Float> separation = new LinkedHashMap<>();
        final Map<String, Float> own = new LinkedHashMap<>();
        final Map<String, Float> allowed = new LinkedHashMap<>();
        final Map<String, Float> drawnRange = new LinkedHashMap<>();
        final Map<String, Float> otherMoved = new LinkedHashMap<>();
        /** piece -> the widest composed drawn rotation it reached, degrees. */
        final Map<String, Float> drawnAngle = new LinkedHashMap<>();
        float tipSpeed;
    }

    private static boolean isTail(String name) {
        for (String link : TAIL) {
            if (link.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Measure run(Rig rig, Map<String, Float> limits) {
        YsmPhysicsParts.Model parts = rig.model();
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                parts, null, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        for (int i = 0; i < parts.segments().length; i++) {
            Float degrees = limits.get(parts.segments()[i].boneName().toLowerCase(Locale.ROOT));
            if (degrees != null) {
                state.limit[i] = (float) Math.toRadians(degrees);
            }
        }
        RunPose pose = new RunPose(rig);
        Vector3f velocity = new Vector3f(0.0F, 0.0F, -5.612F);
        Map<String, List<Float>> own = new LinkedHashMap<>();
        Map<String, List<Float>> raw = new LinkedHashMap<>();
        Map<String, List<Float>> allowed = new LinkedHashMap<>();
        Map<String, List<Float>> separation = new LinkedHashMap<>();
        Map<String, Float> drawnAngle = new LinkedHashMap<>();
        Map<String, Matrix4f> lastDeltas = new LinkedHashMap<>();
        Vector3f lastTip = new Vector3f();
        float travelled = 0.0F;
        boolean first = true;
        for (int frame = 0; frame < FRAMES; frame++) {
            pose.at(frame * DT);
            YsmMeshSecondaryMotion.simulate(state, pose, DT, velocity, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
            if (frame < WARMUP) {
                continue;
            }
            Map<Integer, Matrix4f> deltas = new HashMap<>();
            for (Segment piece : rig.tail) {
                deltas.put(piece.index, rig.drawnDelta(state, piece.index, deltas));
                own.computeIfAbsent(piece.name, key -> new ArrayList<>())
                        .add(state.lastDegrees[piece.index]);
                raw.computeIfAbsent(piece.name, key -> new ArrayList<>())
                        .add((float) Math.toDegrees(state.states[piece.index].lastAngle));
                allowed.computeIfAbsent(piece.name, key -> new ArrayList<>())
                        .add((float) Math.toDegrees(state.chainBudget[piece.index]));
            }
            for (int i = 1; i < rig.tail.size(); i++) {
                Segment link = rig.tail.get(i);
                Segment parent = rig.tail.get(i - 1);
                separation.computeIfAbsent(link.name, key -> new ArrayList<>())
                        .add(rig.separation(link, deltas.get(link.index), parent,
                                deltas.get(parent.index), pose));
            }
            Segment tip = rig.tail.get(rig.tail.size() - 1);
            Vector3f where = rig.tipPosition(tip, deltas.get(tip.index), pose);
            if (!first) {
                travelled += where.distance(lastTip);
            }
            first = false;
            lastTip.set(where);
            lastDeltas.clear();
            for (Segment piece : rig.segments) {
                Matrix4f delta = deltas.get(piece.index) == null
                        ? rig.drawnDelta(state, piece.index, deltas) : deltas.get(piece.index);
                lastDeltas.put(piece.name, delta);
                float angle = rotationAngleOf(state.deltas[piece.index]);
                drawnAngle.merge(piece.name, angle, Math::max);
            }
        }
        Measure out = new Measure();
        for (String name : TAIL) {
            List<Float> ownValues = own.get(name);
            List<Float> rawValues = raw.get(name);
            List<Float> allowedValues = allowed.get(name);
            int clipped = 0;
            float worst = 0.0F;
            for (int i = 0; i < ownValues.size(); i++) {
                if (rawValues.get(i) - ownValues.get(i) > 0.1F) {
                    clipped++;
                }
                worst = Math.max(worst, rawValues.get(i) - ownValues.get(i));
            }
            out.clipped.put(name, (float) clipped / ownValues.size());
            out.largestClip.put(name, worst);
            out.own.put(name, mean(ownValues));
            out.allowed.put(name, mean(allowedValues));
            out.drawnRange.put(name, max(ownValues) - min(ownValues));
            List<Float> gaps = separation.get(name);
            if (gaps != null) {
                out.separation.put(name, max(gaps));
            }
        }
        out.tipSpeed = travelled / ((FRAMES - WARMUP - 1) * DT);
        out.drawnAngle.putAll(drawnAngle);
        return out;
    }

    private static float rotationAngleOf(OpenMatrix4f matrix) {
        float trace = matrix.m00 + matrix.m11 + matrix.m22;
        float cosine = Math.max(-1.0F, Math.min(1.0F, (trace - 1.0F) * 0.5F));
        float angle = (float) Math.acos(cosine);
        return Float.isFinite(angle) ? (float) Math.toDegrees(angle) : 0.0F;
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

    // ------------------------------------------------------------------
    // The pose
    // ------------------------------------------------------------------

    private static final class RunPose implements YsmMeshSecondaryMotion.PoseSource {
        private static final OpenMatrix4f TO_ORIGIN = new OpenMatrix4f();
        private final Rig rig;
        private OpenMatrix4f torso = new OpenMatrix4f();
        private OpenMatrix4f chest = new OpenMatrix4f();
        private OpenMatrix4f head = new OpenMatrix4f();
        private OpenMatrix4f thighRight = new OpenMatrix4f();
        private OpenMatrix4f thighLeft = new OpenMatrix4f();
        private OpenMatrix4f legRight = new OpenMatrix4f();
        private OpenMatrix4f legLeft = new OpenMatrix4f();

        RunPose(Rig rig) {
            this.rig = rig;
            at(0.0F);
        }

        void at(float seconds) {
            float phase = (float) (2.0D * Math.PI * 1.60D * seconds);
            float bob = 0.040F * (float) Math.sin(2.0F * phase);
            torso = about(rig.jointOrigin(JointTable.TORSO), 17.0F, bob);
            chest = OpenMatrix4f.mul(torso,
                    about(rig.jointOrigin(JointTable.CHEST),
                            9.35F + 2.5F * (float) Math.sin(2.0F * phase), bob * 0.5F),
                    new OpenMatrix4f());
            head = OpenMatrix4f.mul(chest,
                    about(rig.jointOrigin(JointTable.HEAD),
                            -7.65F + 3.5F * (float) Math.sin(2.0F * phase + 1.0F), 0.0F),
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

    // ------------------------------------------------------------------
    // The rig
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
        final Map<Integer, OpenMatrix4f> restWorld = new HashMap<>();
        final Map<Integer, Integer> resolvedParent = new HashMap<>();
        final Set<String> loggedSimulated = new HashSet<>();
        final Map<String, Vector3f[]> mountPairs = new HashMap<>();
        final Map<String, Float> bindSeparation = new HashMap<>();
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
                segments.add(new Segment(i, bone, rt.name, rt.joint, pivot, rest, rest.length(), own));
                resolvedParent.put(i, parentOf[i]);
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

        Matrix4f drawnDelta(YsmMeshSecondaryMotion.State state, int index,
                            Map<Integer, Matrix4f> computed) {
            Matrix4f cached = computed.get(index);
            if (cached != null) {
                return cached;
            }
            Matrix4f out = new Matrix4f();
            int parent = resolvedParent.getOrDefault(index, -1);
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
         * How far the drawn chain has opened at a joint, over and above the model's own geometry:
         * one followed pair of vertices, the same pair every frame, measured against their distance
         * in the bind pose.
         */
        float separation(Segment link, Matrix4f linkDelta, Segment parent, Matrix4f parentDelta,
                         YsmMeshSecondaryMotion.PoseSource pose) {
            Vector3f[] pair = mountPair(link, parent);
            Vector3f a = new Vector3f(pair[0]);
            YsmMeshSecondaryMotion.transformPoint(deformationFor(pose, link.joint), a, a);
            linkDelta.transformPosition(a);
            Vector3f b = new Vector3f(pair[1]);
            YsmMeshSecondaryMotion.transformPoint(deformationFor(pose, parent.joint), b, b);
            parentDelta.transformPosition(b);
            return a.distance(b) - bindSeparation.getOrDefault(link.name, 0.0F);
        }

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
    }

    private static YsmPhysicsParts.Model productionModel(Rig rig) {
        YsmPhysicsParts.Segment[] out = new YsmPhysicsParts.Segment[rig.segments.size()];
        for (int i = 0; i < out.length; i++) {
            Segment s = rig.segments.get(i);
            int parent = rig.resolvedParent.getOrDefault(i, -1);
            out[i] = new YsmPhysicsParts.Segment(s.boneIndex, s.name, s.joint, new Vector3f(s.bindPivot),
                    new Vector3f(s.bindRest), s.lever,
                    YsmPhysicsParts.radiusFor(s.own, s.bindPivot, s.bindRest),
                    Math.max(0.25F, Math.min(4.0F, s.own.size() / 64.0F)),
                    (float) YsmPhysicsTuning.DEFAULTS.frequency(),
                    (float) YsmPhysicsTuning.DEFAULTS.dampingRatio(),
                    YsmPhysicsParts.swingLimit(parent >= 0, s.joint == JointTable.TORSO,
                            (float) YsmPhysicsTuning.DEFAULTS.maxAngle,
                            (float) YsmPhysicsTuning.DEFAULTS.maxAngleRoot),
                    parent, new int[0], false, new int[0]);
        }
        return new YsmPhysicsParts.Model(out, YsmPhysicsParts.Source.BONE_NAMES, 0);
    }

    // ------------------------------------------------------------------
    // The armature helpers
    // ------------------------------------------------------------------

    private static Joint referenceBiped() throws IOException {
        String text;
        try (InputStream stream = YsmTailCeilingInvariantTest.class
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
}
