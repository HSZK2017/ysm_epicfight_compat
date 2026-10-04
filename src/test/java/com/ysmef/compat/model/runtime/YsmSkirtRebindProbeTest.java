package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.JointTable;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The re-binding question, measured before it is implemented: if a lower skirt panel were bound to
 * the leg it is nearest to instead of to the Torso, what would the drawn skirt do under Epic
 * Fight's own clips - in every state this round has to answer for.
 *
 * <h2>Why this probe exists beside {@code YsmEfSkirtClipProbeTest}</h2>
 *
 * <p>Two reasons, and both are about the numbers rather than the code.
 *
 * <p><b>1. What the earlier rounds' vertex list is, and what reading it with the wrong stride
 * costs.</b> {@code YsmLegSkirtCollisionProbeTest.Rig.load} fills each piece's {@code own} list with
 * one point per entry of the part array - and that array is the pre-triangulated <b>corner</b> list
 * (stride 3, each corner repeated three times), so {@code own} is a complete list of the piece's
 * corners in triangle order, the same geometry production's {@code YsmPhysicsParts.verticesByBone}
 * builds. Its consumers walk it with the stride of a <b>flat float list</b> ({@code at += 2}) and so
 * read every other entry - half of {@code y/FL2}'s 108 corners. The question this probe answers with
 * numbers rather than argument is what that costs: on this model, nothing measurable, because the
 * fan repeats every vertex three times, so every other corner still covers every distinct vertex.
 * Section 0 reports the two readings side by side; the round's own finding is that they agree to
 * within a thousandth of a block, and that the earlier rounds' clearances are therefore sound.
 *
 * <p><b>2. A vertex-to-vertex metric cannot see a surface between two vertices.</b> A leg's surface
 * passing between two skirt vertices is invisible to a point-to-point distance, so each state is
 * measured twice: panel vertices against the leg's own vertices, and panel vertices against the
 * leg's own <b>triangles</b> - the faces the mesh is drawn with, fanned out of the part arrays
 * exactly as {@code EFMeshJsonWriter} fanned them.
 *
 * <h2>What a re-bound panel means in the drawing</h2>
 *
 * <p>A panel is drawn by {@code pose(joint) x toOrigin(joint)} - the joint baked into the mesh's
 * {@code vindices} at conversion time - and the piece's own secondary-motion delta is applied on
 * top of that. The delta is produced from the piece's pivot, rest direction and lever, which are the
 * <b>bone's</b>, so it is the same delta either way: re-binding changes only which joint's skinning
 * carries the panel. This probe therefore draws a re-bound panel with the candidate joint's
 * deformation followed by the piece's own composed delta, and everything else in the frame loop is
 * production's own.
 *
 * <h2>The states</h2>
 *
 * <p>Epic Fight's own walk, run/sprint, fall and jump, from the clips bundled byte-identically out
 * of the deployed jar (hash-guarded here as in the earlier probe), plus the previous round's
 * synthetic walk as the stress case.
 */
class YsmSkirtRebindProbeTest {

    private static final String MAID = "wine_fox/01_taisho_maid";

    /**
     * The converted artefacts this probe measures: the <b>deployed</b> pair - the one the running
     * game loads - resolved from {@code YSMEF_YSM_CONFIG_ROOT} / {@code -Dysmef.ysm.configRoot} and
     * verified against {@code config/ysm_epicfight_compat/manifest.json}'s own sizes and hashes
     * before use. Without that pair, this environment-dependent probe is skipped.
     *
     * <p>Why not the bundled snapshot alone: it is a <b>stale</b> revision of the same model. Its
     * bone table carries no {@code scale} section at all, and it binds the panels to joint 8 (Chest),
     * i.e. it predates both the scale section and the hip-relative garment rule. Every number
     * measured on it would describe a mesh the game stopped drawing generations ago. The deployed
     * pair is the one the user is looking at, and the manifest makes that verifiable rather than
     * assumed.
     */
    private static final class Artefacts {
        final Path mesh;
        final Path runtime;
        final String source;
        private Artefacts(Path mesh, Path runtime, String source) {
            this.mesh = mesh;
            this.runtime = runtime;
            this.source = source;
        }
    }

    private static final String[] PANELS = {
            "RM", "RM2", "RM3", "RF", "RF2", "RF3", "RB", "RB2", "RB3",
            "BL", "BL2", "BL3", "BM", "BM2", "BM3", "BR", "BR2", "BR3",
            "FL", "FL1", "FL2", "FM", "FM1", "FM2", "FR", "FR1", "FR2",
            "LM", "LM2", "LM3", "LF", "LF2", "LF3", "LB", "LB2", "LB3"};

    private static final int RIGHT_THIGH = JointTable.THIGH_R;
    private static final int RIGHT_SHIN = JointTable.LEG_R;
    private static final int LEFT_THIGH = JointTable.THIGH_L;
    private static final int LEFT_SHIN = JointTable.LEG_L;
    private static final int[] LIMBS = {RIGHT_THIGH, RIGHT_SHIN, LEFT_THIGH, LEFT_SHIN};

    /**
     * The bones of the model that carry each limb joint's geometry, in the deployed converted mesh -
     * read from that mesh's own bone table rather than guessed, so a model whose thigh is named
     * something else (and its alternate form beside it) is measured without editing this table.
     */
    private static Map<Integer, List<String>> limbBones(JsonObject runtime) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        for (int joint : LIMBS) {
            out.put(joint, new ArrayList<>());
        }
        for (JsonElement element : runtime.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            int joint = bone.get("joint").getAsInt();
            if (!out.containsKey(joint)) {
                continue;
            }
            String name = bone.get("name").getAsString();
            if (bone.has("mapped") && bone.get("mapped").getAsBoolean()) {
                out.get(joint).add(name);
            }
        }
        return out;
    }

    private static final float DT = 1.0F / 60.0F;
    private static final int FRAMES = 600;
    private static final int WARMUP = 200;

    private static final Map<String, String> CLIP_SHA256 = Map.of(
            "/epicfight/anim_walk.json", "38F71817692DD9BA69E2AA05566299FE7422C1892D8D52B0ACE6306A06F80B05",
            "/epicfight/anim_run.json", "762AE35FE778264335EA21FEC452D07FC3676526567038D667E8B0D1096C1730",
            "/epicfight/anim_fall.json", "2612D4B6E3B6CA1EE40016D5DEB8970752DFAA64E04B1C43B5A672F2F040D94B",
            "/epicfight/anim_jump.json", "CDB2D1DB6AB53F4C4FE0BF325E779B4BA5A05332F79BC412F03360DBB5C93370");

    private static final String BIPED_SHA256 =
            "7C58B17C465F792DB849FBC3EC377EA9C42C0517027808C7E3D6F3BC5953E583";

    private static final Path REPORT = Paths.get("build", "reports", "ysm-rebind-measurement.md");

    /** The largest disagreement between the true vertex reading and the walked one, blocks. */
    private static float walkedReadingError;

    /** One state to drive, exactly as the earlier probe drives them. */
    private record Drive(String name, String clip, float speed) {}

    private static final Drive[] DRIVES = {
            new Drive("EF walk", "/epicfight/anim_walk.json", 4.317F),
            new Drive("EF run/sprint", "/epicfight/anim_run.json", 5.612F),
            new Drive("EF fall", "/epicfight/anim_fall.json", 0.0F),
            new Drive("EF jump", "/epicfight/anim_jump.json", 4.317F),
            new Drive("synthetic walk", null, 4.317F)};

    // The previous round's synthetic gait, unchanged, so its state is the same state.
    private static final float SYNTH_LEAN = 6.0F;
    private static final float SYNTH_BOB = 0.022F;
    private static final float SYNTH_STRIDE = 20.0F;
    private static final float SYNTH_KNEE = 16.0F;
    private static final float SYNTH_HERTZ = 1.05F;

    // ------------------------------------------------------------------
    // The measurement
    // ------------------------------------------------------------------

    @Test
    void whatReBindingTheLowerSkirtPanelsToTheirNearestLegWouldDraw() throws Exception {
        Artefacts artefacts = artefacts();
        JsonObject mesh = JsonParser.parseString(Files.readString(artefacts.mesh, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject runtime = JsonParser.parseString(
                Files.readString(artefacts.runtime, StandardCharsets.UTF_8)).getAsJsonObject();
        YsmLegSkirtCollisionProbeTest.Rig rig = YsmLegSkirtCollisionProbeTest.Rig.load(
                artefacts.runtime, artefacts.mesh, MAID,
                YsmLegSkirtCollisionProbeTest.LOGGED_MAID_SIMULATED);

        assertEquals(BIPED_SHA256, sha256Of(resourceBytes("/epicfight/biped.json")),
                "the bundled Epic Fight biped.json is not the revision the pose recipe was calibrated on");
        Map<String, Animation> clips = new LinkedHashMap<>();
        for (Drive drive : DRIVES) {
            if (drive.clip() == null) {
                continue;
            }
            byte[] bytes = resourceBytes(drive.clip());
            String actual = sha256Of(bytes);
            assertEquals(CLIP_SHA256.get(drive.clip()), actual,
                    "the bundled clip " + drive.clip() + " is not the deployed jar's own revision");
            clips.put(drive.name(), Animation.parse(
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject(), rig, drive.clip()));
        }

        // ---- the geometry, read from the mesh's own part arrays -----------------------------
        Map<String, PanelGeometry> panels = new LinkedHashMap<>();
        for (String name : PANELS) {
            PanelGeometry geometry = PanelGeometry.of(mesh, rig, name);
            assertNotNull(geometry, "panel " + name + " must be a part of the converted mesh");
            panels.put(name, geometry);
        }
        Map<Integer, List<String>> legBones = limbBones(runtime);
        for (int joint : LIMBS) {
            List<Vector3f> vertices = new ArrayList<>();
            List<int[]> triangles = new ArrayList<>();
            assertTrue(!legBones.get(joint).isEmpty(),
                    "no directly mapped bone carries " + legName(joint) + "'s geometry in the "
                            + "converted runtime's own bone table");
            for (String bone : legBones.get(joint)) {
                YsmLegSkirtCollisionProbeTest.Geometry part =
                        YsmLegSkirtCollisionProbeTest.Rig.meshGeometry(mesh, "y/" + bone);
                if (part.corners.isEmpty()) {
                    continue;
                }
                int base = vertices.size();
                vertices.addAll(part.corners);
                for (int[] triangle : part.triangles) {
                    triangles.add(new int[]{base + triangle[0], base + triangle[1], base + triangle[2]});
                }
            }
            assertTrue(vertices.size() > 24 && triangles.size() > 12,
                    "limb " + legName(joint) + " must carry the model's own leg geometry, found "
                            + vertices.size() + " vertices and " + triangles.size() + " triangles on "
                            + legBones.get(joint));
            rig.limbVertices.put(joint, vertices);
            rig.limbTriangles.put(joint, triangles);
            float longest = 0.0F;
            for (int[] triangle : triangles) {
                longest = Math.max(longest, vertices.get(triangle[0]).distance(vertices.get(triangle[1])));
                longest = Math.max(longest, vertices.get(triangle[1]).distance(vertices.get(triangle[2])));
                longest = Math.max(longest, vertices.get(triangle[2]).distance(vertices.get(triangle[0])));
            }
            rig.limbLongestEdge.put(joint, longest);
        }

        Map<String, Integer> binding = candidateBinding(rig, panels);
        assertEquals(PANELS.length, binding.size(), "every panel must be bound to a leg");

        StringBuilder report = new StringBuilder();
        report.append("# What re-binding the skirt to the legs would draw\n\n")
                .append("Model `").append(rig.modelId).append("`, ").append(PANELS.length)
                .append(" panels, ").append(rig.model().segments().length)
                .append(" simulated pieces. Artefacts: ").append(artefacts.source).append(".\n\n")
                .append("Dynamics: the production frame loop ")
                .append("(`YsmMeshSecondaryMotion.simulate`, the model's own volumes placed every ")
                .append("frame by `YsmBodyColliders.update`), ").append(FRAMES)
                .append(" frames of ").append(fmt(DT)).append(" s, the first ").append(WARMUP)
                .append(" skipped. Pose: Epic Fight's own clips, byte-identical to the deployed ")
                .append("jar's entries (hash-guarded here), composed by the loader's own recipe.\n\n");
        report.append(probeGeometrySection(rig, panels));

        for (Drive drive : DRIVES) {
            Animation animation = drive.clip() == null ? null : clips.get(drive.name());
            StateRun shipped = measure(rig, drive, panels, binding, animation, 0.0F, null);
            recordShippedBinding(shipped, binding);
            Map<Float, StateRun> blended = new LinkedHashMap<>();
            for (float blend : BLENDS) {
                StateRun run = measure(rig, drive, panels, binding, animation, blend, shipped);
                run.shipped = shipped;
                blended.put(blend, run);
            }
            states.put(drive.name(), blended);
        }
        report.append(bindingSection(rig, panels, binding, legBones));
        report.append(sweepSection());
        report.append(perPanelSection());
        report.append(verdictSection());
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // ---- the assertions the round turns on ----------------------------------------------
        for (Map.Entry<String, Integer> entry : binding.entrySet()) {
            assertTrue(isLimb(entry.getValue()), entry.getKey() + " was bound to "
                    + JointTable.nameOf(entry.getValue()) + ", which is not a leg joint");
        }
        // The correction this round owes the earlier ones: the two readings of the same panel are
        // the same measurement on this model. Asserted as agreement rather than as a difference,
        // because a difference here would have meant every clearance in the earlier reports needed
        // re-measuring - which is what the round went looking for and did not find.
        assertTrue(walkedReadingError < 0.001F,
                "the earlier rounds' stride-2 reading and this round's corner reading of the same "
                        + "panel must agree on the minimum distance to the leg; they disagree by "
                        + walkedReadingError + " blocks");
        float worstShipped = -Float.MAX_VALUE;
        for (Map.Entry<String, Map<Float, StateRun>> entry : states.entrySet()) {
            for (Map.Entry<Float, StateRun> row : entry.getValue().entrySet()) {
                StateRun run = row.getValue();
                assertTrue(run.frames > 0, entry.getKey() + " at blend " + row.getKey()
                        + " measured no frames");
                assertTrue(Float.isFinite(run.worstSkin()) && Float.isFinite(run.worstFace()),
                        entry.getKey() + " at blend " + row.getKey()
                                + " must have measured both the vertex and the face metric; "
                                + "closest " + fmt(run.worstSkin()) + ", face " + fmt(run.worstFace()));
            }
            worstShipped = Math.max(worstShipped,
                    entry.getValue().get(0.0F).shipped.worstDepth());
        }
        // The clips must actually drive the legs through the cloth, or the states below are states
        // in which nothing happens: the shipped binding has to show contact somewhere.
        assertTrue(worstShipped > 0.02F,
                "the clips must drive the leg through the bind clearance under the shipped binding, "
                        + "or there is nothing for a re-binding to fix; the deepest was "
                        + worstShipped + " blocks");
        System.out.println("YSREBIND-WALKED-READING-ERROR " + fmt(walkedReadingError));
        for (Map.Entry<String, Map<Float, StateRun>> entry : states.entrySet()) {
            for (Map.Entry<Float, StateRun> row : entry.getValue().entrySet()) {
                StateRun run = row.getValue();
                System.out.println("YSREBIND " + entry.getKey() + " blend " + fmt(row.getKey())
                        + " depth " + fmt(run.shipped.worstDepth()) + " -> " + fmt(run.worstDepth())
                        + "; closest " + fmt(run.shipped.worstSkin()) + " -> " + fmt(run.worstSkin())
                        + "; face " + fmt(run.shipped.worstFace()) + " -> " + fmt(run.worstFace())
                        + "; movedStatic " + fmt(run.worstStaticMove()) + "; moved " + fmt(run.worstMove()));
            }
        }
    }

    /** The measured states, in drive order, each against the whole blend ladder. */
    private static final Map<String, Map<Float, StateRun>> states = new LinkedHashMap<>();

    /**
     * How much of the leg's own swing a re-bound panel follows, from none (the shipped Torso
     * binding) to all of it (a rigid bind to the leg joint). The ladder is what decides the round:
     * a panel bound rigidly to a joint is carried the whole of the clip's thigh or knee rotation,
     * which is three times what the cloth needs and throws the panel away from the leg instead of
     * keeping it clear of it - so the question "does this fix the clipping" is a question about
     * <i>how much</i> of the leg the cloth should follow, and the answer is read off the numbers.
     */
    private static final float[] BLENDS = {0.0F, 0.2F, 0.35F, 0.5F, 0.75F, 1.0F};

    /** The blend the panel-by-panel table is printed at. */
    private static final float PER_PANEL_BLEND = 0.5F;

    /**
     * The deformation a re-bound panel is drawn with: the shipped binding's own skinning, with the
     * candidate leg joint's rotation and pivot blended into it by {@code blend}.
     *
     * <p>Not a linear interpolation of the two matrices: that would shear the panel, because a
     * rotation between two rotations is not their average. Each deformation's own basis is taken as
     * a quaternion and the two are slerped, and the joint's origin is blended as a point - which is
     * the whole transform for a rigid bind, and exactly the shipped deformation at {@code blend = 0}.
     *
     * @param shipped  {@code pose(boneJoint) x toOrigin(boneJoint)}, the panel's own binding
     * @param legJoint the candidate leg joint the panel would be re-bound to
     */
    private static OpenMatrix4f blendedDeformation(YsmLegSkirtCollisionProbeTest.Rig rig,
                                                   YsmMeshSecondaryMotion.PoseSource pose,
                                                   int boneJoint, int legJoint, float blend,
                                                   OpenMatrix4f shipped) {
        OpenMatrix4f leg = rig.deformationFor(pose, legJoint);
        if (blend >= 1.0F) {
            return leg;
        }
        Quaternionf from = rotationOf(shipped);
        Quaternionf to = rotationOf(leg);
        Quaternionf rotation = new Quaternionf(from).slerp(to, blend);
        Vector3f pivot = rig.jointOrigin(legJoint);
        Vector3f shippedPivot = from.transform(new Vector3f(pivot));
        Vector3f legPivot = to.transform(new Vector3f(pivot));
        Vector3f origin = shippedPivot.lerp(legPivot, blend);
        Matrix4f matrix = new Matrix4f().rotation(rotation);
        matrix.setTranslation(origin.x, origin.y, origin.z);
        return toOpen(matrix);
    }

    /** The rotation of a deformation matrix: its three basis vectors, re-orthonormalized. */
    private static Quaternionf rotationOf(OpenMatrix4f matrix) {
        Vector3f x = new Vector3f(matrix.m00, matrix.m01, matrix.m02).normalize();
        Vector3f y = new Vector3f(matrix.m10, matrix.m11, matrix.m12).normalize();
        Vector3f z = new Vector3f(x).cross(y).normalize();
        y.set(new Vector3f(z).cross(x));
        Matrix4f basis = new Matrix4f(
                x.x, x.y, x.z, 0.0F,
                y.x, y.y, y.z, 0.0F,
                z.x, z.y, z.z, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        return basis.getNormalizedRotation(new Quaternionf());
    }

    private static void recordShippedBinding(StateRun shipped, Map<String, Integer> binding) {
        for (Map.Entry<String, Integer> entry : binding.entrySet()) {
            shipped.binding.put(entry.getKey(), legName(entry.getValue()));
        }
    }

    /**
     * Where a re-bound panel's vertex is drawn: the shipped placement and the leg joint's placement,
     * interpolated as <b>points</b>, and the pivot the secondary motion then turns about given as
     * the same interpolation of the two pivots.
     *
     * <p>Not an interpolation of the two skinning matrices, which is what a first cut of this did
     * and what the round's own numbers rejected: the two matrices are rigid motions about different
     * points (the piece's own bone pivot and the leg joint's origin), and a pointwise average of two
     * rigid motions is not a rigid motion - it swept the panel 2.9 blocks away in every state, which
     * is the one thing the blend was supposed to avoid. Interpolating the placements instead is the
     * motion the eye expects: at 0 the panel is exactly where it always was, at 1 exactly on the leg.
     */
    private static final class Placement {
        final List<Vector3f> points = new ArrayList<>();
        final List<Vector3f> pivots = new ArrayList<>();
    }

    private static Placement blendPlacements(YsmLegSkirtCollisionProbeTest.Rig rig,
                                             YsmMeshSecondaryMotion.PoseSource pose,
                                             int boneJoint, int legJoint, float blend,
                                             OpenMatrix4f shipped, List<Vector3f> vertices) {
        OpenMatrix4f leg = rig.deformationFor(pose, legJoint);
        Vector3f pivot = new Vector3f();
        Vector3f shippedPivot = YsmMeshSecondaryMotion.transformPoint(shipped,
                new Vector3f(rig.jointOrigin(legJoint)), new Vector3f());
        Vector3f legPivot = YsmMeshSecondaryMotion.transformPoint(leg,
                new Vector3f(rig.jointOrigin(legJoint)), new Vector3f());
        Placement out = new Placement();
        for (Vector3f vertex : vertices) {
            Vector3f before = YsmMeshSecondaryMotion.transformPoint(shipped, new Vector3f(vertex),
                    new Vector3f());
            Vector3f after = YsmMeshSecondaryMotion.transformPoint(leg, new Vector3f(vertex),
                    new Vector3f());
            out.points.add(before.lerp(after, blend, new Vector3f()));
            pivot.set(shippedPivot).lerp(legPivot, blend);
            out.pivots.add(new Vector3f(pivot));
        }
        return out;
    }

    private static Map<String, Integer> candidateBinding(YsmLegSkirtCollisionProbeTest.Rig rig, Map<String, PanelGeometry> panels) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, PanelGeometry> entry : panels.entrySet()) {
            int joint = nearestLimb(rig, entry.getValue().vertices);
            if (joint >= 0) {
                out.put(entry.getKey(), joint);
            }
        }
        return out;
    }

    private static int nearestLimb(YsmLegSkirtCollisionProbeTest.Rig rig, List<Vector3f> vertices) {
        int best = -1;
        double least = Double.MAX_VALUE;
        for (int joint : LIMBS) {
            double distance = minVertexDistance(vertices, rig.limbVertices.get(joint));
            if (distance < least) {
                least = distance;
                best = joint;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // One state
    // ------------------------------------------------------------------

    /**
     * @param blend    how much of the candidate joint's swing each panel follows: 0 for the shipped
     *                 binding, 1 for a rigid bind to the leg joint
     * @param baseline the as-shipped run, when this is a re-bound one; used for the moved distance
     */
    private static StateRun measure(YsmLegSkirtCollisionProbeTest.Rig rig, Drive drive, Map<String, PanelGeometry> panels,
                                    Map<String, Integer> binding, Animation animation,
                                    float blend, StateRun baseline) {
        boolean rebind = blend > 0.0F;
        YsmPhysicsParts.Model parts = rig.model();
        YsmBodyColliders body = rig.colliders();
        body.update(rig.armature(), bindPoseMatrices(rig));
        YsmMeshSecondaryMotion.State sim = new YsmMeshSecondaryMotion.State(
                parts, body, (float) YsmPhysicsTuning.DEFAULTS.maxAngle);
        YsmPhysicsOverrides.markOverrides(rig.modelId, rig.bones, parts.segments(),
                sim.held, sim.limit);
        YsmMeshSecondaryMotion.PoseSource pose = animation != null
                ? new ClipPose(rig, animation) : new SyntheticGait(rig);
        Vector3f velocity = new Vector3f(0.0F, 0.0F, -drive.speed());

        StateRun out = new StateRun(drive.name());
        for (String name : PANELS) {
            out.panels.put(name, new PanelRun());
        }
        for (Map.Entry<String, PanelGeometry> entry : panels.entrySet()) {
            PanelRun row = out.panels.get(entry.getKey());
            row.bindMin = (float) minVertexToLimb(rig, entry.getValue().vertices);
            row.points = entry.getValue().vertices.size();
        }
        Map<String, String> partners = rebind ? partnersOf(panels, binding) : null;
        Map<String, Float> bindPartnerGap = rebind ? partnerGaps(panels, partners, null) : null;
        Map<String, List<List<Vector3f>>> shippedPositions = rebind && baseline != null
                ? baseline.panelPositions : null;
        Map<String, List<List<Vector3f>>> baselineStatic = rebind && baseline != null
                ? baseline.staticPositions : null;
        int frames = 0;
        int drawnFrames = 0;
        for (int frame = 0; frame < FRAMES; frame++) {
            float time = frame * DT;
            if (pose instanceof ClipPose) {
                ((ClipPose) pose).at(time);
            } else {
                ((SyntheticGait) pose).at(time);
            }
            if (frame == 0) {
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
            drawnFrames++;
            // The legs as drawn: each limb joint's own geometry through its own deformation.
            Map<Integer, List<Vector3f>> drawnLeg = new LinkedHashMap<>();
            for (int joint : LIMBS) {
                OpenMatrix4f deformation = rig.deformationFor(pose, joint);
                List<Vector3f> placed = new ArrayList<>();
                for (Vector3f vertex : rig.limbVertices.get(joint)) {
                    placed.add(YsmMeshSecondaryMotion.transformPoint(deformation,
                            new Vector3f(vertex), new Vector3f()));
                }
                drawnLeg.put(joint, placed);
            }
            Vector3f forward = forwardOf(pose);
            for (int index : rig.panelIndexes) {
                YsmLegSkirtCollisionProbeTest.Segment piece = rig.segments.get(index);
                PanelRun row = out.panels.get(piece.name);
                if (row == null) {
                    continue;
                }
                row.frames++;
                int joint = piece.joint;
                OpenMatrix4f deformation = rig.deformationFor(pose, piece.joint);
                Placement placement = null;
                if (rebind) {
                    Integer override = binding.get(piece.name);
                    if (override != null) {
                        joint = override;
                        placement = blendPlacements(rig, pose, piece.joint, override, blend,
                                deformation, panels.get(piece.name).vertices);
                    }
                }
                Matrix4f delta = rig.drawnDelta(sim, index);
                List<Vector3f> drawn = new ArrayList<>();
                List<Vector3f> staticPlacement = new ArrayList<>();
                for (int at = 0; at < panels.get(piece.name).vertices.size(); at++) {
                    Vector3f local;
                    if (placement != null) {
                        local = placement.points.get(at);
                    } else {
                        local = YsmMeshSecondaryMotion.transformPoint(deformation,
                                new Vector3f(panels.get(piece.name).vertices.get(at)),
                                new Vector3f());
                    }
                    staticPlacement.add(new Vector3f(local));
                    if (placement != null) {
                        // The solver's delta turns the piece about the piece's OWN bind pivot,
                        // because the piece's pivot and rest direction are its bone's. Re-binding
                        // moves that pivot, and the same rotation about the origin would then
                        // translate the panel by the pivot's whole travel - 1.0 to 1.4 blocks of
                        // pure error on this model. So it is conjugated to turn about the pivot the
                        // placement puts the piece around: the same delta at `joint == piece.joint`,
                        // and the correct one otherwise.
                        Vector3f pivot = placement.pivots.get(at);
                        drawn.add(new Matrix4f()
                                .translate(pivot.x, pivot.y, pivot.z)
                                .mul(delta)
                                .translate(-pivot.x, -pivot.y, -pivot.z)
                                .transformPosition(new Vector3f(local)));
                    } else {
                        drawn.add(delta.transformPosition(new Vector3f(local)));
                    }
                }
                if (!rebind) {
                    out.staticPositions.computeIfAbsent(piece.name, key -> new ArrayList<>())
                            .add(staticPlacement);
                } else {
                    List<List<Vector3f>> shippedStatic = baselineStatic.get(piece.name);
                    if (shippedStatic != null && shippedStatic.size() >= drawnFrames) {
                        List<Vector3f> before = shippedStatic.get(drawnFrames - 1);
                        float worst = 0.0F;
                        for (int at = 0; at < staticPlacement.size() && at < before.size(); at++) {
                            worst = Math.max(worst, staticPlacement.get(at).distance(before.get(at)));
                        }
                        out.movedStatic.merge(piece.name, worst, Math::max);
                    }
                }
                // a) the panel's vertices against the leg's vertices, and against the leg's faces.
                double vsVertex = Double.MAX_VALUE;
                double vsFace = Double.MAX_VALUE;
                int nearestJoint = -1;
                for (int limb : LIMBS) {
                    List<Vector3f> leg = drawnLeg.get(limb);
                    double vertexDistance = minVertexDistance(drawn, leg);
                    if (vertexDistance < vsVertex) {
                        vsVertex = vertexDistance;
                        nearestJoint = limb;
                    }
                    vsFace = Math.min(vsFace, minFaceDistance(drawn, leg, rig.limbTriangles.get(limb),
                            rig.limbLongestEdge.get(limb)));
                }
                row.leg = legName(nearestJoint);
                row.legFrames.merge(nearestJoint, 1, Integer::sum);
                row.skinMin = (float) Math.min(row.skinMin, vsVertex);
                row.faceMin = (float) Math.min(row.faceMin, vsFace);
                Vector3f nearestLegPoint = nearestPoint(drawn, drawnLeg.get(nearestJoint));
                if (nearestLegPoint != null) {
                    row.forwardness += nearestLegPoint.dot(forward);
                }
                // b) the shipped volumes, placed the way the frame path places them.
                for (int i = 0; i < body.count(); i++) {
                    float[] capsule = body.resolvedVolume(i);
                    if (capsule == null) {
                        continue;
                    }
                    float deepest = 0.0F;
                    for (Vector3f point : drawn) {
                        float distance = YsmBodyColliders.distanceToSegment(point.x, point.y,
                                point.z, capsule[0], capsule[1], capsule[2], capsule[4],
                                capsule[5], capsule[6]);
                        deepest = Math.max(deepest, capsule[7] - distance);
                    }
                    row.wire = Math.max(row.wire, deepest);
                }
                row.turn = Math.max(row.turn, sim.lastContact[index]);
                // c) how far the re-binding moved the panel, against the shipped run's own frames.
                if (shippedPositions != null) {
                    List<List<Vector3f>> before = shippedPositions.get(piece.name);
                    if (before != null && before.size() >= drawnFrames) {
                        List<Vector3f> previous = before.get(drawnFrames - 1);
                        float worst = 0.0F;
                        for (int at = 0; at < drawn.size() && at < previous.size(); at++) {
                            worst = Math.max(worst, drawn.get(at).distance(previous.get(at)));
                        }
                        out.moved.merge(piece.name, worst, Math::max);
                    }
                }
                if (!rebind) {
                    out.panelPositions.computeIfAbsent(piece.name, key -> new ArrayList<>()).add(drawn);
                } else {
                    out.drawnPositions.computeIfAbsent(piece.name, key -> new ArrayList<>()).add(drawn);
                }
            }
        }
        out.frames = frames;
        for (PanelRun row : out.panels.values()) {
            row.skinDepth = row.bindMin - row.skinMin;
        }
        // d) the boundary: how far each re-bound panel sits from the nearest panel that keeps its
        //    Torso binding, posed - the largest such distance over the measured frames.
        if (rebind) {
            out.partners = partners;
            out.bindPartnerGap = bindPartnerGap;
            out.partnerGap.putAll(partnerGaps(panels, partners, out.drawnPositions));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The frame's own helpers
    // ------------------------------------------------------------------

    private static OpenMatrix4f[] bindPoseMatrices(YsmLegSkirtCollisionProbeTest.Rig rig) {
        OpenMatrix4f[] out = new OpenMatrix4f[JointTable.COUNT];
        for (int joint = 0; joint < out.length; joint++) {
            out[joint] = rig.jointById(joint) == null ? null : rig.restWorldOf(joint);
        }
        return out;
    }

    /** The model's own forward, carried into the frame the clip draws: the root's turn only. */
    private static Vector3f forwardOf(YsmMeshSecondaryMotion.PoseSource pose) {
        OpenMatrix4f world = pose.poseOf(JointTable.ROOT);
        if (world == null) {
            return new Vector3f(0.0F, 0.0F, -1.0F);
        }
        Vector3f out = new Vector3f(world.m20, world.m21, world.m22);
        return out.lengthSquared() < 1.0E-9F ? new Vector3f(0.0F, 0.0F, -1.0F) : out.normalize();
    }

    private static Vector3f nearestPoint(List<Vector3f> from, List<Vector3f> to) {
        double least = Double.MAX_VALUE;
        Vector3f best = null;
        for (Vector3f point : from) {
            for (Vector3f other : to) {
                double distance = point.distanceSquared(other);
                if (distance < least) {
                    least = distance;
                    best = other;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    static double minVertexDistance(List<Vector3f> a, List<Vector3f> b) {
        double least = Double.MAX_VALUE;
        for (Vector3f p : a) {
            for (Vector3f q : b) {
                least = Math.min(least, p.distanceSquared(q));
            }
        }
        return Math.sqrt(least);
    }

    /**
     * The least distance from any of {@code points} to the leg's own drawn triangles.
     *
     * <p>The vertex distance bounds the face distance from above, so a triangle none of whose
     * corners is within {@code longestEdge} of the nearest vertex the panel has already found cannot
     * beat it, and is skipped untransformed: a shin is thousands of triangles and this runs per
     * panel per frame, six hundred frames a state.
     */
    static double minFaceDistance(List<Vector3f> points, List<Vector3f> leg,
                                  List<int[]> triangles, float longestEdge) {
        if (leg.isEmpty() || triangles.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double least = Double.MAX_VALUE;
        for (Vector3f point : points) {
            // The vertex distance bounds the face distance from above, so only a triangle whose
            // first corner is within the triangle's longest edge of the nearest vertex can beat
            // what has already been found; a shin is hundreds of triangles and this runs per panel
            // per frame, six hundred frames a state.
            double nearestVertex = Double.MAX_VALUE;
            for (Vector3f vertex : leg) {
                nearestVertex = Math.min(nearestVertex, point.distance(vertex));
            }
            double cutoff = Math.min(least, nearestVertex + longestEdge);
            double cutoffSquared = cutoff * cutoff;
            for (int[] triangle : triangles) {
                Vector3f a = leg.get(triangle[0]);
                if (point.distanceSquared(a) > cutoffSquared) {
                    continue;
                }
                least = Math.min(least, pointTriangle(point, a, leg.get(triangle[1]),
                        leg.get(triangle[2])));
            }
        }
        return least;
    }

    /** The distance from {@code p} to the segment {@code ab}, for a collapsed triangle. */
    static double pointSegment(Vector3f p, Vector3f a, Vector3f b) {
        Vector3f ab = new Vector3f(b).sub(a);
        double lengthSquared = ab.lengthSquared();
        if (lengthSquared < 1.0E-12) {
            return p.distance(a);
        }
        double t = Math.min(1.0, Math.max(0.0, new Vector3f(p).sub(a).dot(ab) / lengthSquared));
        return p.distance(a.x + (float) (t * ab.x), a.y + (float) (t * ab.y), a.z + (float) (t * ab.z));
    }

    /** The distance from {@code p} to the triangle {@code (a, b, c)}: Ericson's closest point. */
    static double pointTriangle(Vector3f p, Vector3f a, Vector3f b, Vector3f c) {
        // A zero-area triangle has no interior and no barycentric denominator; the model has them
        // (its alternate-form limb bones carry quads collapsed onto a single point), and the
        // fallback at the bottom of this method would return NaN for one. A degenerate triangle is
        // a segment or a point, and its distance is the least distance to its longest edge.
        if (a.equals(b) || b.equals(c) || a.equals(c)) {
            return Math.min(pointSegment(p, a, b), Math.min(pointSegment(p, b, c), pointSegment(p, a, c)));
        }
        Vector3f ab = new Vector3f(b).sub(a);
        Vector3f ac = new Vector3f(c).sub(a);
        Vector3f ap = new Vector3f(p).sub(a);
        double d1 = ab.dot(ap);
        double d2 = ac.dot(ap);
        if (d1 <= 0.0 && d2 <= 0.0) {
            return p.distance(a);
        }
        Vector3f bp = new Vector3f(p).sub(b);
        double d3 = ab.dot(bp);
        double d4 = ac.dot(bp);
        if (d3 >= 0.0 && d4 <= d3) {
            return p.distance(b);
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
            double v = d1 / (d1 - d3);
            return p.distance(a.x + (float) (v * ab.x), a.y + (float) (v * ab.y),
                    a.z + (float) (v * ab.z));
        }
        Vector3f cp = new Vector3f(p).sub(c);
        double d5 = ab.dot(cp);
        double d6 = ac.dot(cp);
        if (d6 >= 0.0 && d5 <= d6) {
            return p.distance(c);
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
            double w = d2 / (d2 - d6);
            return p.distance(a.x + (float) (w * ac.x), a.y + (float) (w * ac.y),
                    a.z + (float) (w * ac.z));
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return p.distance(b.x + (float) (w * (c.x - b.x)), b.y + (float) (w * (c.y - b.y)),
                    b.z + (float) (w * (c.z - b.z)));
        }
        double denominator = va + vb + vc;
        if (Math.abs(denominator) < 1.0E-12) {
            return Math.min(p.distance(a), Math.min(p.distance(b), p.distance(c)));
        }
        double v = vb / denominator;
        double w = vc / denominator;
        return p.distance(a.x + (float) (v * ab.x + w * ac.x),
                a.y + (float) (v * ab.y + w * ac.y),
                a.z + (float) (v * ab.z + w * ac.z));
    }

    static double minVertexToLimb(YsmLegSkirtCollisionProbeTest.Rig rig, List<Vector3f> vertices) {
        double least = Double.MAX_VALUE;
        for (int joint : LIMBS) {
            least = Math.min(least, minVertexDistance(vertices, rig.limbVertices.get(joint)));
        }
        return least;
    }

    private static boolean isLimb(int joint) {
        for (int candidate : LIMBS) {
            if (candidate == joint) {
                return true;
            }
        }
        return false;
    }

    static String legName(int joint) {
        switch (joint) {
            case RIGHT_THIGH: return "Thigh_R";
            case RIGHT_SHIN: return "Leg_R";
            case LEFT_THIGH: return "Thigh_L";
            case LEFT_SHIN: return "Leg_L";
            default: return "-";
        }
    }

    static String fmt(float value) {
        return Float.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "n/a";
    }

    // ------------------------------------------------------------------
    // Per-panel geometry
    // ------------------------------------------------------------------

    private static final class PanelGeometry {
        final List<Vector3f> vertices;
        final List<Vector3f> scrambled;

        private PanelGeometry(List<Vector3f> vertices, List<Vector3f> scrambled) {
            this.vertices = vertices;
            this.scrambled = scrambled;
        }

        static PanelGeometry of(JsonObject mesh, YsmLegSkirtCollisionProbeTest.Rig rig, String name) {
            YsmLegSkirtCollisionProbeTest.Geometry geometry =
                    YsmLegSkirtCollisionProbeTest.Rig.meshGeometry(mesh, "y/" + name);
            if (geometry.corners.isEmpty()) {
                return null;
            }
            return new PanelGeometry(geometry.corners, scrambledVertices(rig, name));
        }

        /**
         * The list the earlier probes actually walk: the same part array, but consumed with the
         * stride of a flat float list - which is what every consumer of
         * {@code YsmLegSkirtCollisionProbeTest.Segment#own} does. Kept only so the disagreement
         * between the two readings can be reported as a number.
         */
        private static List<Vector3f> scrambledVertices(YsmLegSkirtCollisionProbeTest.Rig rig, String name) {
            List<Vector3f> out = new ArrayList<>();
            for (YsmLegSkirtCollisionProbeTest.Segment piece : rig.segments) {
                if (!piece.name.equals(name)) {
                    continue;
                }
                for (int at = 0; at < piece.own.size(); at += 2) {
                    out.add(new Vector3f(piece.own.get(at)));
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // The report
    // ------------------------------------------------------------------

    private static String probeGeometrySection(YsmLegSkirtCollisionProbeTest.Rig rig, Map<String, PanelGeometry> panels) {
        StringBuilder out = new StringBuilder();
        out.append("## 0. The geometry this round reads, against the geometry the earlier rounds read\n\n")
                .append("`corners` is the panel's own corner list - what the mesh stores and what ")
                .append("`YsmPhysicsParts.verticesByBone` reads; `walked` is how many of those ")
                .append("entries the earlier probes actually reach, because their loops walk a ")
                .append("`List<Vector3f>` with the stride of a flat float list (`at += 2`). `bind ")
                .append("gap` is the least distance from each reading to the leg's own geometry in ")
                .append("the bind pose. All lengths in blocks: if the two columns agree, the stride ")
                .append("costs the minimum-distance statistics nothing, because the fan repeats ")
                .append("every vertex.\n\n")
                .append("| panel | corners | walked | bind gap, corners | bind gap, walked | disagreement |\n")
                .append("|---|---|---|---|---|---|\n");
        float worst = 0.0F;
        for (Map.Entry<String, PanelGeometry> entry : panels.entrySet()) {
            PanelGeometry panel = entry.getValue();
            double trueGap = minVertexToLimb(rig, panel.vertices);
            double walkedGap = panel.scrambled.isEmpty()
                    ? Double.NaN : minVertexToLimb(rig, panel.scrambled);
            float disagreement = Double.isFinite(walkedGap) ? (float) Math.abs(trueGap - walkedGap)
                    : Float.NaN;
            if (Float.isFinite(disagreement)) {
                worst = Math.max(worst, disagreement);
            }
            out.append("| `").append(entry.getKey()).append("` | ").append(panel.vertices.size())
                    .append(" | ").append(panel.scrambled.size()).append(" | ")
                    .append(fmt((float) trueGap)).append(" | ")
                    .append(Double.isFinite(walkedGap) ? fmt((float) walkedGap) : "n/a")
                    .append(" | ").append(fmt(disagreement)).append(" |\n");
        }
        out.append("\n**Largest disagreement between the two readings of the same panel: ")
                .append(String.format(Locale.ROOT, "%.6f", worst))
                .append(" blocks** - so the earlier rounds' vertex metric is the same measurement ")
                .append("this round makes, and the round's correction to itself is that the stride ")
                .append("costs it nothing on this model.\n\n");
        walkedReadingError = worst;
        return out.toString();
    }

    private static String bindingSection(YsmLegSkirtCollisionProbeTest.Rig rig, Map<String, PanelGeometry> panels,
                                         Map<String, Integer> binding, Map<Integer, List<String>> legBones) {
        StringBuilder out = new StringBuilder();
        out.append("## 1. The candidate binding, decided before anything is implemented\n\n")
                .append("For each panel: the distance from its own true vertices to each limb ")
                .append("joint's own geometry in the bind pose, and the joint the brief's rule binds ")
                .append("it to - the nearer **leg**, and within that leg the nearer **joint**. All ")
                .append("lengths in blocks.\n\n")
                .append("| panel | Thigh_R | Leg_R | Thigh_L | Leg_L | nearest leg | bind to | draw bone |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, PanelGeometry> entry : panels.entrySet()) {
            PanelGeometry panel = entry.getValue();
            out.append("| `").append(entry.getKey()).append("` |");
            for (int joint : LIMBS) {
                out.append(' ').append(fmt((float) minVertexDistance(panel.vertices,
                        rig.limbVertices.get(joint)))).append(" |");
            }
            int joint = binding.getOrDefault(entry.getKey(), -1);
            out.append(' ').append(sideOf(joint)).append(" | ").append(legName(joint))
                    .append(" | `").append(legBones.get(joint).get(0)).append("` |\n");
        }
        out.append('\n');
        return out.toString();
    }

    /**
     * The blend ladder, state by state: how much of the leg's swing the cloth should follow. Each
     * row is the same model, the same clip and the same frame loop, differing only in the fraction
     * of the candidate leg joint's deformation the re-bound panels receive.
     *
     * <p>`worst depth` is the largest leg-through-cloth depth any panel reaches - the number the
     * report is about, and the one that must fall. `closest` is the least distance the drawn cloth
     * ever comes to the drawn leg; `face` is the same against the leg's own triangles. `rose` and
     * `fell` count the panels whose depth moved by more than 0.005 blocks either way, and
     * `moved` is the largest distance any cloth corner was drawn from where the shipped binding
     * drew it - the honest size of the change on screen.
     */
    private static String sweepSection() {
        StringBuilder out = new StringBuilder();
        out.append("## 2. The blend ladder, state by state\n\n")
                .append("`blend` is the fraction of the candidate leg joint's own deformation a ")
                .append("re-bound panel receives: 0 is the shipped Torso binding (the control), 1 is ")
                .append("a rigid bind to the leg joint. All lengths in blocks.\n\n")
                .append("| state | blend | worst depth | deepest panel | closest | face | wire | ")
                .append("rose | fell | joint frame moved | drawn moved |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Map<Float, StateRun>> entry : states.entrySet()) {
            for (Map.Entry<Float, StateRun> row : entry.getValue().entrySet()) {
                StateRun run = row.getValue();
                int rose = 0;
                int fell = 0;
                for (String name : PANELS) {
                    PanelRun now = run.panels.get(name);
                    PanelRun before = run.shipped.panels.get(name);
                    if (now == null || before == null) {
                        continue;
                    }
                    if (now.skinDepth - before.skinDepth > 0.005F) {
                        rose++;
                    } else if (before.skinDepth - now.skinDepth > 0.005F) {
                        fell++;
                    }
                }
                out.append("| ").append(entry.getKey()).append(" | ").append(fmt(row.getKey()))
                        .append(" | ").append(fmt(run.worstDepth())).append(" | ")
                        .append(run.deepestPanel()).append(" | ").append(fmt(run.worstSkin()))
                        .append(" | ").append(fmt(run.worstFace())).append(" | ")
                        .append(fmt(run.worstWire())).append(" | ").append(rose).append(" | ")
                        .append(fell).append(" | ").append(fmt(run.worstStaticMove())).append(" | ")
                        .append(fmt(run.worstMove())).append(" |\n");
            }
        }
        out.append('\n');
        return out.toString();
    }

    private static String perPanelSection() {
        StringBuilder out = new StringBuilder();
        out.append("## 3. Panel by panel at a blend of ").append(fmt(PER_PANEL_BLEND))
                .append("\n\n")
                .append("Per state and panel: the leg it is nearest to, the joint the candidate ")
                .append("binding gives it, the depth through the bind clearance as shipped and at ")
                .append("this blend, how deep the panel is drawn inside a shipped capsule, and how ")
                .append("far it moves against the shipped run.\n\n");
        for (Map.Entry<String, Map<Float, StateRun>> entry : states.entrySet()) {
            StateRun run = entry.getValue().get(PER_PANEL_BLEND);
            if (run == null) {
                continue;
            }
            StateRun shipped = run.shipped;
            out.append("### ").append(entry.getKey()).append("\n\n")
                    .append("| panel | leg | bind to | depth shipped | depth blended | ")
                    .append("closest shipped | closest blended | wire shipped | wire blended | move |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|\n");
            for (String name : PANELS) {
                PanelRun row = run.panels.get(name);
                PanelRun before = shipped.panels.get(name);
                if (row == null || before == null || row.frames == 0) {
                    continue;
                }
                out.append("| `").append(name).append("` | ").append(row.leg).append(" | ")
                        .append(shipped.binding.getOrDefault(name, "-")).append(" | ")
                        .append(fmt(before.skinDepth)).append(" | ").append(fmt(row.skinDepth))
                        .append(" | ").append(fmt(before.skinMin)).append(" | ")
                        .append(fmt(row.skinMin)).append(" | ").append(fmt(before.wire))
                        .append(" | ").append(fmt(row.wire)).append(" | ")
                        .append(fmt(run.moved.getOrDefault(name, 0.0F))).append(" |\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String verdictSection() {
        StringBuilder out = new StringBuilder();
        out.append("## 4. The verdict, and the opposite case\n\n");
        for (Map.Entry<String, Map<Float, StateRun>> entry : states.entrySet()) {
            out.append("### ").append(entry.getKey()).append("\n\n")
                    .append("| blend | worst depth | deepest panel | rose | fell | closest | ")
                    .append("moved | gap to a Torso-bound neighbour |\n")
                    .append("|---|---|---|---|---|---|---|---|\n");
            for (Map.Entry<Float, StateRun> row : entry.getValue().entrySet()) {
                StateRun run = row.getValue();
                int rose = 0;
                int fell = 0;
                for (String name : PANELS) {
                    PanelRun now = run.panels.get(name);
                    PanelRun before = run.shipped.panels.get(name);
                    if (now == null || before == null) {
                        continue;
                    }
                    if (now.skinDepth - before.skinDepth > 0.005F) {
                        rose++;
                    } else if (before.skinDepth - now.skinDepth > 0.005F) {
                        fell++;
                    }
                }
                float worstGapChange = 0.0F;
                String worstGapName = "-";
                for (Map.Entry<String, Float> gap : run.partnerGap.entrySet()) {
                    Float before = run.bindPartnerGap.get(gap.getKey());
                    if (before == null) {
                        continue;
                    }
                    float change = gap.getValue() - before;
                    if (change > worstGapChange) {
                        worstGapChange = change;
                        worstGapName = gap.getKey();
                    }
                }
                out.append("| ").append(fmt(row.getKey())).append(" | ")
                        .append(fmt(run.worstDepth())).append(" | ").append(run.deepestPanel())
                        .append(" | ").append(rose).append(" | ").append(fell).append(" | ")
                        .append(fmt(run.worstSkin())).append(" | ").append(fmt(run.worstMove()))
                        .append(" | ").append(fmt(worstGapChange)).append(" (`")
                        .append(worstGapName).append("` -> `")
                        .append(run.partners == null ? "-" : run.partners.getOrDefault(worstGapName, "-"))
                        .append("`) |\n");
            }
            out.append('\n');
        }
        out.append("`rose` and `fell` count the panels whose leg-through-cloth depth moved by more ")
                .append("than 0.005 blocks against the shipped binding; `gap to a Torso-bound ")
                .append("neighbour` is the largest widening of the distance between a re-bound panel ")
                .append("and the nearest panel that keeps its Torso binding - the number that decides ")
                .append("whether the skirt tears where the two bindings meet.\n");
        return out.toString();
    }

    private static String sideOf(int joint) {
        return joint == RIGHT_THIGH || joint == RIGHT_SHIN ? "right"
                : joint == LEFT_THIGH || joint == LEFT_SHIN ? "left" : "-";
    }

    /** The nearest panel that keeps its Torso binding, per re-bound panel, in the bind pose. */
    private static Map<String, String> partnersOf(Map<String, PanelGeometry> panels,
                                                  Map<String, Integer> binding) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, PanelGeometry> entry : panels.entrySet()) {
            if (!binding.containsKey(entry.getKey())) {
                continue;
            }
            String best = "-";
            double least = Double.MAX_VALUE;
            for (Map.Entry<String, PanelGeometry> other : panels.entrySet()) {
                if (binding.containsKey(other.getKey())) {
                    continue;
                }
                double distance = minVertexDistance(entry.getValue().vertices,
                        other.getValue().vertices);
                if (distance < least) {
                    least = distance;
                    best = other.getKey();
                }
            }
            out.put(entry.getKey(), best);
        }
        return out;
    }

    /**
     * The largest distance from each re-bound panel to its Torso-bound partner over the measured
     * frames ({@code drawn} holds each panel's own drawn corners per frame), or the bind-pose
     * distance when {@code drawn} is null.
     */
    private static Map<String, Float> partnerGaps(Map<String, PanelGeometry> panels,
                                                  Map<String, String> partners,
                                                  Map<String, List<List<Vector3f>>> drawn) {
        Map<String, Float> out = new LinkedHashMap<>();
        if (partners == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : partners.entrySet()) {
            String name = entry.getKey();
            String partner = entry.getValue();
            if (partner == null || "-".equals(partner)) {
                continue;
            }
            float widest = 0.0F;
            if (drawn == null) {
                widest = (float) minVertexDistance(panels.get(name).vertices,
                        panels.get(partner).vertices);
            } else {
                List<List<Vector3f>> first = drawn.get(name);
                List<List<Vector3f>> second = drawn.get(partner);
                if (first == null || second == null) {
                    continue;
                }
                for (int frame = 0; frame < Math.min(first.size(), second.size()); frame++) {
                    widest = Math.max(widest, (float) minVertexDistance(first.get(frame),
                            second.get(frame)));
                }
            }
            out.put(name, widest);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The measured state
    // ------------------------------------------------------------------

    private static final class PanelRun {
        float skinMin = Float.MAX_VALUE;
        float faceMin = Float.MAX_VALUE;
        float bindMin = Float.MAX_VALUE;
        float skinDepth = Float.NaN;
        float wire;
        float turn;
        float forwardness;
        int points;
        int frames;
        String leg = "-";
        final Map<Integer, Integer> legFrames = new LinkedHashMap<>();
    }

    private static final class StateRun {
        final String name;
        final Map<String, PanelRun> panels = new LinkedHashMap<>();
        final Map<String, List<List<Vector3f>>> panelPositions = new LinkedHashMap<>();
        final Map<String, List<List<Vector3f>>> drawnPositions = new LinkedHashMap<>();
        /** The same frames with the secondary-motion delta NOT applied: the joint frame alone. */
        final Map<String, List<List<Vector3f>>> staticPositions = new LinkedHashMap<>();
        final Map<String, Float> movedStatic = new LinkedHashMap<>();
        final Map<String, Float> moved = new LinkedHashMap<>();
        final Map<String, Float> partnerGap = new LinkedHashMap<>();
        final Map<String, String> binding = new LinkedHashMap<>();
        Map<String, Float> bindPartnerGap = new LinkedHashMap<>();
        Map<String, String> partners;
        StateRun shipped;
        int frames;

        StateRun(String name) {
            this.name = name;
        }

        float worstSkin() {
            float worst = Float.MAX_VALUE;
            for (PanelRun row : panels.values()) {
                worst = Math.min(worst, row.skinMin);
            }
            return worst;
        }

        float worstFace() {
            float worst = Float.MAX_VALUE;
            for (PanelRun row : panels.values()) {
                worst = Math.min(worst, row.faceMin);
            }
            return worst;
        }

        float worstDepth() {
            float worst = -Float.MAX_VALUE;
            for (PanelRun row : panels.values()) {
                if (Float.isFinite(row.skinDepth)) {
                    worst = Math.max(worst, row.skinDepth);
                }
            }
            return worst;
        }

        float worstWire() {
            float worst = 0.0F;
            for (PanelRun row : panels.values()) {
                worst = Math.max(worst, row.wire);
            }
            return worst;
        }

        float worstTurn() {
            float worst = 0.0F;
            for (PanelRun row : panels.values()) {
                worst = Math.max(worst, row.turn);
            }
            return worst;
        }

        /** The same distance with the secondary-motion delta left out: the joint frame alone. */
        float worstStaticMove() {
            float worst = 0.0F;
            for (Float value : movedStatic.values()) {
                worst = Math.max(worst, value);
            }
            return worst;
        }

        /** The largest distance any cloth corner was drawn from where the shipped binding drew it. */
        float worstMove() {
            float worst = 0.0F;
            for (Float value : moved.values()) {
                worst = Math.max(worst, value);
            }
            return worst;
        }

        String deepestPanel() {
            String name = "-";
            float worst = -Float.MAX_VALUE;
            for (Map.Entry<String, PanelRun> entry : panels.entrySet()) {
                if (Float.isFinite(entry.getValue().skinDepth) && entry.getValue().skinDepth > worst) {
                    worst = entry.getValue().skinDepth;
                    name = entry.getKey();
                }
            }
            return name;
        }
    }

    // ------------------------------------------------------------------
    // The pose the clips give (the earlier probe's own recipe)
    // ------------------------------------------------------------------

    private static final Matrix4f BLENDER_TO_MINECRAFT =
            new Matrix4f(1.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, -1.0F, 0.0F,
                    0.0F, 1.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F, 1.0F);

    private static final class Animation {
        final String path;
        final float[] times;
        final float duration;
        final Map<Integer, OpenMatrix4f[]> worlds = new LinkedHashMap<>();

        private Animation(String path, float[] times, float duration) {
            this.path = path;
            this.times = times;
            this.duration = duration;
        }

        static Animation parse(JsonObject doc, YsmLegSkirtCollisionProbeTest.Rig rig, String path) throws IOException {
            JsonArray nodes = doc.getAsJsonArray("animation");
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
            Animation animation = new Animation(path, sampleTimes, last);
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

    private static final class ClipPose implements YsmMeshSecondaryMotion.PoseSource {
        private final YsmLegSkirtCollisionProbeTest.Rig rig;
        private final Animation animation;
        private int sample = -1;

        ClipPose(YsmLegSkirtCollisionProbeTest.Rig rig, Animation animation) {
            this.rig = rig;
            this.animation = animation;
        }

        void at(float seconds) {
            float time = animation.duration > 0.0F ? seconds % animation.duration : seconds;
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

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return rig.toOriginOf(joint);
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            return joint >= 0 && joint < poses.length ? poses[joint] : IDENTITY;
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

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    /**
     * The deployed pair of converted artefacts. The bundled snapshot predates the scale and
     * garment rules, so it cannot produce a valid result for this probe.
     */
    private static Artefacts artefacts() throws Exception {
        String root = System.getProperty("ysmef.ysm.configRoot", "");
        if (root.isEmpty()) {
            String fromEnvironment = System.getenv("YSMEF_YSM_CONFIG_ROOT");
            root = fromEnvironment == null ? "" : fromEnvironment;
        }
        assumeTrue(!root.isEmpty(), "set YSMEF_YSM_CONFIG_ROOT to run the deployed skirt probe");
        Path config = Paths.get(root).toAbsolutePath().getParent();
        assumeTrue(config != null, "YSM config root has no parent directory");
        Path assets = config.resolve("ysm_epicfight_compat").resolve("resourcepack")
                .resolve("assets").resolve("ysm_epicfight_compat");
        Path mesh = assets.resolve("animmodels/entity").resolve(MAID + ".json");
        Path runtime = assets.resolve("ysm_runtime/entity").resolve(MAID + ".json");
        Path manifest = config.resolve("ysm_epicfight_compat").resolve("manifest.json");
        assumeTrue(Files.isRegularFile(mesh) && Files.isRegularFile(runtime)
                        && manifestAgrees(manifest, mesh, runtime),
                "deployed skirt artefacts are absent or disagree with manifest.json under " + assets);
        return new Artefacts(mesh, runtime,
                "the deployed pair under " + assets.getParent().getParent()
                        + ", verified against manifest.json");
    }

    /** Whether the manifest's entry for this model names these two files' own sizes and hashes. */
    private static boolean manifestAgrees(Path manifest, Path mesh, Path runtime) {
        try {
            if (!Files.isRegularFile(manifest)) {
                return false;
            }
            JsonObject root = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement models = root.get("models");
            if (models == null || !models.isJsonObject()) {
                return false;
            }
            JsonElement entry = models.getAsJsonObject().get(MAID);
            if (entry == null || !entry.isJsonObject()) {
                return false;
            }
            JsonObject model = entry.getAsJsonObject();
            return agrees(mesh, model, "msize", "mhash") && agrees(runtime, model, "rsize", "rhash");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean agrees(Path file, JsonObject entry, String sizeKey, String hashKey)
            throws Exception {
        if (!entry.has(sizeKey) || !entry.has(hashKey)) {
            return false;
        }
        byte[] bytes = Files.readAllBytes(file);
        return bytes.length == entry.get(sizeKey).getAsLong()
                && sha256Of(bytes).equalsIgnoreCase(entry.get(hashKey).getAsString());
    }

    private static byte[] resourceBytes(String path) throws IOException {
        try (InputStream in = YsmSkirtRebindProbeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "the bundled resource " + path + " is not on the test classpath");
            return in.readAllBytes();
        }
    }

    private static String sha256Of(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest(bytes)) {
            out.append(String.format(Locale.ROOT, "%02X", value));
        }
        return out.toString();
    }
}
