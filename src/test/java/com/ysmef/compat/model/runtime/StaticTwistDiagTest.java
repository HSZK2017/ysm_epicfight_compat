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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The round-20 defect as a number, on the model it was reported on: <b>the hem is rotated while the
 * character stands still</b>.
 *
 * <p>Each piece is measured through the production arithmetic - the pivot is
 * {@link YsmPhysicsParts#pivotInMeshSpace} over the model's own bind chain, the rest direction is the
 * geometry's centroid minus that pivot, the lever, frequency and limit are the ones
 * {@code YsmPhysicsParts} gives the piece - and then settled by the production solver twice:
 *
 * <ol>
 *   <li><b>standing still</b>: the pose's rotation of the piece's joint is the identity, which is what
 *       the frame path hands the solver when the animation has left the piece where the mesh was
 *       authored. The piece must end up exactly where it was drawn;</li>
 *   <li><b>a sixty-degree lean</b>: the rotation is real and the follow scale is saturated, so the
 *       mechanism that makes cloth hang toward the ground on a leaning body is the one that runs. The
 *       piece must leave the authored direction by a visible angle, or the fix has traded the defect
 *       for a garment that ignores the body.</li>
 * </ol>
 *
 * <p>The table is written to {@code build/reports/ysm-static-twist.md}; the assertions are on the two
 * numbers the report is about, and the second one is what stops "delete the gravity-follow weight"
 * from passing.
 *
 * <p>Opt-in: skipped unless the YSM config root is given, like the other corpus diagnostics.
 */
class StaticTwistDiagTest {

    /** The model the defect was reported on. */
    private static final String MODEL = "兽耳酱x1.ysm";

    /** The pieces the game's own log named as simulated for it. */
    private static final List<String> LOGGED_BONES = List.of(
            "X_T4_1", "X_yiqun1", "X_T3_1", "X_T6_1", "X_Hair1", "X_T5_1", "X_liuhai_F_1", "X_fawei1",
            "X_T7_1", "Left_ear_F_1", "X_T2_1", "X_qunzi1", "Right_ear_R_1", "X_liuhai_Z_1",
            "X_liuhai_R_1");

    /**
     * The hem, which is the piece the report is about: the mesh's own flare must survive a standstill
     * untouched.
     */
    private static final List<String> HEM = List.of("X_qunzi1", "X_qunzi2", "X_qunzi3", "X_qunzi4");

    /**
     * How far a piece may be rotated off its authored direction while the pose has not moved it. Not
     * zero, because a solver is arithmetic: what the defect was is 22 degrees, and what is being
     * asserted is that the number is a rounding error rather than a rotation a viewer can see.
     */
    private static final float MAX_STANDSTILL_DEGREES = 0.05F;

    /** How far a piece must move once the pose really has leaned it, or the weight has stopped working. */
    private static final float MIN_LEAN_DEGREES = 5.0F;

    @Test
    void theHemIsNotRotatedWhileStandingStill(@TempDir Path temp) throws IOException {
        assumeTrue(!configuredConfigRoot().isEmpty(), "set " + YsmModelPackage.CONFIG_ROOT_ENV
                + " (or -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + ") to run this diagnostic");

        YsmModelPackage pkg = YsmModelPackage.load(MODEL);
        assumeTrue(pkg != null && pkg.geometry != null, "the install has no '" + MODEL + "'");
        Path meshFile = temp.resolve("mesh.json");
        Path runtimeFile = temp.resolve("runtime.json");
        EFMeshJsonWriter.write(pkg, meshFile, runtimeFile, "ysm_epicfight_compat:textures/audit.png");

        Pack pack = Pack.read(meshFile, runtimeFile, pkg.widthScale, pkg.heightScale);

        StringBuilder md = new StringBuilder();
        md.append("# Static twist while standing still (round 20)\n\n");
        md.append("Model `").append(MODEL).append("`. `standstill` = the pose's rotation of the ")
                .append("piece's joint is the identity, which is what the frame path hands the solver ")
                .append("when the animation has left the piece where the mesh was authored; `lean 60` ")
                .append("= a real sixty-degree rotation of that joint, with the follow scale ")
                .append("saturated. Both are degrees between the settled direction and the authored ")
                .append("one; `moved` is the settled centre of mass's travel, blocks.\n\n");
        md.append("| bone | joint | lever | rest off vertical | **standstill** | standstill moved | ")
                .append("lean 60 |\n");
        md.append("|---|---|---|---|---|---|---|\n");

        List<String> failures = new ArrayList<>();
        for (String bone : LOGGED_BONES) {
            Measured m = pack.measure(bone);
            if (m == null) {
                md.append("| ").append(bone).append(" | - | - | - | no geometry of its own | | |\n");
                continue;
            }
            md.append(String.format(Locale.ROOT,
                    "| %s | %s | %.4f | %.2f | **%.4f** | %.5f | %.2f |%n",
                    bone, JointTable.nameOf(m.joint), m.lever, m.restOffDown,
                    m.standstillTwist, m.standstillMovement, m.leanTwist));
            if (m.standstillTwist > MAX_STANDSTILL_DEGREES) {
                failures.add(bone + " was rotated " + m.standstillTwist
                        + " degrees off its authored direction while standing still");
            }
            if (HEM.contains(bone) && m.leanTwist < MIN_LEAN_DEGREES) {
                failures.add(bone + " only moved " + m.leanTwist + " degrees at a sixty degree lean,"
                        + " so the gravity-follow mechanism is no longer reaching the dynamics");
            }
        }

        Path out = Paths.get("build", "reports", "ysm-static-twist.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        System.out.println(md);
        assertTrue(failures.isEmpty(), String.join("; ", failures) + "\n" + md);
    }

    /** One bone's production numbers and the two settled angles. */
    private record Measured(int joint, float lever, float restOffDown, float standstillTwist,
                            float standstillMovement, float leanTwist) {}

    /**
     * Every simulated piece of one model, measured as the diagnostic measures them - the same pivot
     * arithmetic, the same classification, the same solver.
     *
     * <p>Package-private and free of any test annotation so that the corpus sweep can be run over a
     * whole model library from a harness rather than from a JUnit run: "how many models are affected"
     * is a question about the class of defect, and answering it by hand-picking models is how a
     * systemic problem gets read as a per-model one.
     *
     * @param pkg  the model package
     * @param temp a directory for the converted mesh/runtime pair
     * @return one row per piece that has geometry, a pivot and a lever worth simulating
     */
    static List<String[]> measureAll(YsmModelPackage pkg, Path temp) throws IOException {
        Path meshFile = temp.resolve("mesh.json");
        Path runtimeFile = temp.resolve("runtime.json");
        EFMeshJsonWriter.write(pkg, meshFile, runtimeFile, "ysm_epicfight_compat:textures/audit.png");
        Pack pack = Pack.read(meshFile, runtimeFile, pkg.widthScale, pkg.heightScale);
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : pack.parts.entrySet()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            String bone = partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length());
            Measured m = pack.measure(bone);
            if (m == null) {
                continue;
            }
            rows.add(new String[]{bone, Float.toString(m.lever), Float.toString(m.restOffDown),
                    Float.toString(m.standstillTwist), Float.toString(m.leanTwist)});
        }
        return rows;
    }

    /**
     * The converted mesh + runtime of one model, with the pivot arithmetic of the production path and
     * the production solver - the same objects the frame path builds, without a render thread.
     */
    private static final class Pack {
        final float[] mc;
        final Map<String, int[]> parts;
        final Map<String, float[]> raw;
        final Map<String, Matrix4f> bindWorld;
        final Map<String, Integer> joint;
        final Map<String, Float> authoredOffDown = new HashMap<>();
        final float scaleW;
        final float scaleH;

        Pack(float[] mc, Map<String, int[]> parts, Map<String, float[]> raw,
             Map<String, Matrix4f> bindWorld, Map<String, Integer> joint, float scaleW, float scaleH) {
            this.mc = mc;
            this.parts = parts;
            this.raw = raw;
            this.bindWorld = bindWorld;
            this.joint = joint;
            this.scaleW = scaleW;
            this.scaleH = scaleH;
        }

        Measured measure(String bone) {
            int[] indices = parts.get(EFMeshJsonWriter.BONE_PART_PREFIX + bone);
            float[] rawBone = raw.get(bone);
            if (indices == null || indices.length == 0 || rawBone == null) {
                return null;
            }
            Vector3f pivot = YsmPhysicsParts.pivotInMeshSpace(bindWorld.get(bone),
                    rawBone[0], rawBone[1], rawBone[2], scaleW, scaleH);
            if (pivot == null) {
                return null;
            }
            Vector3f centroid = centroid(indices);
            if (centroid == null) {
                return null;
            }
            Vector3f rest = new Vector3f(centroid).sub(pivot);
            float lever = rest.length();
            if (!(lever > 0.02F)) {
                return null;
            }
            Vector3f down = new Vector3f(0.0F, -1.0F, 0.0F);
            // The frequency and limit the production path would give this piece: the classification
            // decides the weight (and therefore the limit), and the author's own frequency is not read
            // here because it does not change where the piece rests - only how fast it gets there.
            float weight = categoryWeight(bone);
            float limit = (float) Math.toRadians(20.0);
            Vector3f standstill = settle(rest, lever, weight, limit, new Quaternionf());
            Vector3f lean = settle(rest, lever, weight, limit,
                    moveAway(rest, (float) Math.toRadians(60.0)));
            float standstillAngle = YsmDynamicBoneSolver.angleBetween(rest, standstill);
            float moved = (float) (2.0 * lever * Math.sin(standstillAngle * 0.5F));
            return new Measured(joint.getOrDefault(bone, -1), lever,
                    (float) Math.toDegrees(YsmDynamicBoneSolver.angleBetween(rest, down)),
                    (float) Math.toDegrees((double) standstillAngle),
                    moved,
                    (float) Math.toDegrees((double) YsmDynamicBoneSolver.angleBetween(rest, lean)));
        }

        /**
         * The production solver, driven exactly as the frame path drives it: the same gravity and air
         * drag the config defaults to, the same authored frequency the shipped log measures, and the
         * pose's rotation of the piece's joint as the round-20 input.
         */
        private Vector3f settle(Vector3f rest, float lever, float weight, float limit,
                                Quaternionf jointRotation) {
            YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
            Quaternionf out = new Quaternionf();
            Vector3f pivot = new Vector3f();
            Vector3f still = new Vector3f();
            float dt = 1.0F / 60.0F;
            for (int i = 0; i < 360; i++) {
                YsmDynamicBoneSolver.INSTANCE.update(state, YsmDynamicBoneSolver.GRAVITY,
                        YsmDynamicBoneSolver.AIR_DRAG, weight, new Vector3f(0.0F, -1.0F, 0.0F),
                        pivot, rest, lever, 2.36F, 0.81F, 1.0F, limit, still,
                        YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.0F, 0.0F,
                        i == 0 ? 0.0F : dt, out, jointRotation);
            }
            return new Vector3f(state.direction);
        }

        /** A rotation that moves {@code direction} by exactly {@code radians}. */
        private static Quaternionf moveAway(Vector3f direction, float radians) {
            Vector3f axis = new Vector3f(direction).cross(0.0F, 1.0F, 0.0F);
            if (axis.lengthSquared() < 1.0E-8F) {
                axis.set(1.0F, 0.0F, 0.0F);
            }
            return new Quaternionf().fromAxisAngleRad(axis.normalize(), radians);
        }

        /** The category weight the classifier gives a bone, by the same hints it uses. */
        private static float categoryWeight(String bone) {
            String normalized = com.ysmef.compat.model.YSMJointMapper.normalize(bone);
            for (String hint : new String[]{"hair", "bangs", "fringe", "ahoge", "mop"}) {
                if (normalized.contains(hint)) {
                    return 0.60F;
                }
            }
            for (String hint : new String[]{"skirt", "dress", "qun", "cloth", "hem", "coat", "robe",
                    "kimono", "cape", "cloak", "mantle", "scarf", "sash", "belt", "apron", "sleeve",
                    "ribbon"}) {
                if (normalized.contains(hint)) {
                    return 0.92F;
                }
            }
            return 0.80F;
        }

        private Vector3f centroid(int[] indices) {
            Vector3f acc = new Vector3f();
            int n = 0;
            for (int index : indices) {
                if (index >= 0 && index * 3 + 2 < mc.length) {
                    acc.add(mc[index * 3], mc[index * 3 + 1], mc[index * 3 + 2]);
                    n++;
                }
            }
            return n == 0 ? null : acc.div(n);
        }

        static Pack read(Path meshFile, Path runtimeFile, float scaleW, float scaleH) throws IOException {
            JsonObject meshRoot = JsonParser.parseString(Files.readString(meshFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject vertices = meshRoot.getAsJsonObject("vertices");
            JsonArray positions = vertices.getAsJsonObject("positions").getAsJsonArray("array");
            int count = positions.size() / 3;
            float[] mc = new float[count * 3];
            for (int i = 0; i < count; i++) {
                // Epic Fight's mesh JSON is in Blender space: (x, y, z)_json -> (x, z, -y)_mc.
                mc[i * 3] = positions.get(i * 3).getAsFloat();
                mc[i * 3 + 1] = positions.get(i * 3 + 2).getAsFloat();
                mc[i * 3 + 2] = -positions.get(i * 3 + 1).getAsFloat();
            }
            Map<String, int[]> parts = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : vertices.getAsJsonObject("parts").entrySet()) {
                JsonArray array = entry.getValue().getAsJsonObject().getAsJsonArray("array");
                int[] indices = new int[array.size()];
                for (int i = 0; i < indices.length; i++) {
                    indices[i] = array.get(i).getAsInt();
                }
                parts.put(entry.getKey(), indices);
            }

            JsonObject runtime = JsonParser.parseString(Files.readString(runtimeFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray bones = runtime.getAsJsonArray("bones");
            Map<String, float[]> raw = new HashMap<>();
            Map<String, String> parentOf = new HashMap<>();
            Map<String, Integer> joint = new HashMap<>();
            for (int i = 0; i < bones.size(); i++) {
                JsonObject bone = bones.get(i).getAsJsonObject();
                String name = bone.get("name").getAsString();
                parentOf.put(name, bone.has("parent") ? bone.get("parent").getAsString() : "");
                joint.put(name, bone.get("joint").getAsInt());
                JsonArray pivot = bone.getAsJsonArray("pivot");
                JsonArray rot = bone.getAsJsonArray("rot");
                raw.put(name, new float[]{pivot.get(0).getAsFloat(), pivot.get(1).getAsFloat(),
                        pivot.get(2).getAsFloat(), rot.get(0).getAsFloat(), rot.get(1).getAsFloat(),
                        rot.get(2).getAsFloat()});
            }
            Map<String, Matrix4f> bindWorld = new HashMap<>();
            for (String name : raw.keySet()) {
                bindWorldOf(name, raw, parentOf, bindWorld, 0);
            }
            return new Pack(mc, parts, raw, bindWorld, joint, scaleW, scaleH);
        }

        /**
         * The accumulated bind chain of a bone - {@code T(p) R T(-p)} for every bone from the root
         * down - which is the matrix {@link YsmPhysicsParts#pivotInMeshSpace} is handed.
         */
        private static Matrix4f bindWorldOf(String name, Map<String, float[]> raw,
                                            Map<String, String> parentOf, Map<String, Matrix4f> cache,
                                            int depth) {
            Matrix4f cached = cache.get(name);
            if (cached != null) {
                return cached;
            }
            float[] bone = raw.get(name);
            if (bone == null || depth > 600) {
                return new Matrix4f();
            }
            Matrix4f local = new Matrix4f();
            local.translate(bone[0], bone[1], bone[2]);
            local.rotateZ(bone[5]);
            local.rotateY(bone[4]);
            local.rotateX(bone[3]);
            local.translate(-bone[0], -bone[1], -bone[2]);
            String parent = parentOf.getOrDefault(name, "");
            Matrix4f world = !parent.isEmpty() && raw.containsKey(parent)
                    ? new Matrix4f(bindWorldOf(parent, raw, parentOf, cache, depth + 1)).mul(local)
                    : local;
            cache.put(name, world);
            return world;
        }
    }

    private static String configuredConfigRoot() {
        String property = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (!property.isEmpty()) {
            return property;
        }
        String fromEnvironment = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
        return fromEnvironment == null ? "" : fromEnvironment;
    }
}
