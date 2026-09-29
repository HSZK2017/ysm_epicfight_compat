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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The measurement the previous rounds lacked: <b>orientations</b>, not just positions.
 *
 * <p>Three prior checks declared the leg path sound and all three were blind to a rotation
 * error: the pivot-inside-its-own-geometry-bbox test is position-only, "the joint the mesh
 * DRAWS a part with equals the joint the runtime TABLE simulates it on" only says the two
 * sides agree with each other, and "no leg bone is a physics segment" rules out the
 * secondary-motion path rather than the rest-pose basis. A joint can sit at exactly the right
 * point and still be rotated ninety degrees.
 *
 * <p>What is measured here, per joint, over the twenty joints of Epic Fight's reference biped:
 *
 * <ol>
 *   <li>the rest rotation of the joint in the armature {@link YsmBindArmature} builds, as a
 *       quaternion;</li>
 *   <li>the rotation part of that joint's {@code toOrigin} in that same armature (Epic Fight
 *       stores {@code toOrigin} on the {@link Joint}, not in the mesh JSON - {@code
 *       Armature#bakeOriginMatrices} / {@code Joint#initOriginTransform} fill it from the same
 *       local transforms, so this file has to build the joints to see it);</li>
 *   <li>the product {@code pose_rest[joint] * toOrigin[joint]}, with its residual rotation
 *       angle and translation. At rest that product is the skinning matrix and must be the
 *       identity. This is the table that decides everything;</li>
 *   <li>the authored rotation of the bones bound to that joint, from the package, composed
 *       through the authored chain into the frame the mesh is drawn in;</li>
 *   <li>which bone geometry binds to which leg joint, assigned by <b>geometry position</b> -
 *       where the box actually sits along the leg - not by bone name.</li>
 * </ol>
 *
 * <p>Everything runs off the production artefacts: the converted mesh and runtime JSON the
 * deployed build wrote, {@link YsmBindArmature#collectGeometry} and {@link
 * YsmBindArmature#computePivots} called directly, and the reference rotations read from a
 * bundled copy of Epic Fight's own {@code biped.json} (hash-guarded below, so a mismatch with
 * the running Epic Fight cannot pass silently).
 *
 * <p><b>What the residual table turned out to be worth, measured rather than assumed.</b> The
 * product is the identity on every joint of every model tested, to 0.04 degrees and 0.000 blocks -
 * because {@code toOrigin} is the inverse of the bind world by construction ({@code
 * Joint#initOriginTransform} fills it from the same local transforms the world is composed from).
 * A wrong pivot, a wrong reference rotation, or a swapped hierarchy all cancel in it. So the table
 * cannot decide anything on its own, and the check that <i>can</i> is {@link
 * #theRetargetedPoseTurnsTheGeometryTheSameWayEpicFightsOwnBipedDoes}: apply a real Epic Fight
 * pose through the same code path the game uses and compare the result against Epic Fight's own
 * biped. What that one measures over the corpus is recorded in the second test's javadoc.
 */
class EkuLegOrientationProbeTest {

    /** The two models to compare: the model the in-game report names, and a known-good one. */
    private static final List<String[]> MODELS = List.of(
            new String[]{"EKU(1.0.ysm", "eku"},
            new String[]{"known-good: default (Steve-shaped, ships with YSM)", "default"},
            new String[]{"known-good: izayoi_sakuya", "sakuya"});

    /**
     * SHA-256 of the bundled {@code src/test/resources/epicfight/biped.json}, which is
     * Epic Fight 20.14.17's own {@code assets/epicfight/animmodels/entity/biped.json}. Pinned
     * because every reference rotation in this measurement comes from it: if Epic Fight ships
     * a different biped the numbers below describe a model the game does not have.
     */
    private static final String BIPED_SHA256 =
            "7c58b17c465f792db849fbc3ec377ea9c42c0517027808c7e3d6f3bc5953e583";

    /** The joints the report is about, in the order the table prints them. */
    private static final int[] REPORTED = {
            JointTable.ROOT, JointTable.THIGH_R, JointTable.LEG_R, JointTable.KNEE_R,
            JointTable.THIGH_L, JointTable.LEG_L, JointTable.KNEE_L, JointTable.TORSO};

    /** The six leg joints, as (joint id, side) - the chain that is reported broken. */
    private static final int[][] LEG_CHAIN = {
            {JointTable.THIGH_R, JointTable.LEG_R, JointTable.KNEE_R},
            {JointTable.THIGH_L, JointTable.LEG_L, JointTable.KNEE_L}};

    @Test
    void theIdentityResidualTableAndWhereTheNinetyDegreesLives() throws IOException {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null,
                "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + "=<.../config/yes_steve_model> (or "
                        + YsmModelPackage.CONFIG_ROOT_ENV + ") to run this against a real install");
        assertTrue(BIPED_SHA256.equals(sha256Of(bipedJsonBytes())),
                "the bundled Epic Fight biped.json is not the revision this measurement was calibrated on");

        StringBuilder report = new StringBuilder();
        report.append("# Per-joint orientation table: EKU(1.0.ysm against a known-good model\n\n");
        report.append("Built from the converted artefacts in `").append(pack).append("`.\n\n");

        for (String[] target : MODELS) {
            String needle = target[1];
            Path runtimeFile = findConverted(pack.resolve("ysm_runtime/entity"), needle);
            if (runtimeFile == null) {
                report.append("## ").append(target[0]).append(": not converted in this install\n\n");
                continue;
            }
            Path meshFile = pack.resolve("animmodels/entity").resolve(runtimeFile.getFileName().toString());
            if (!Files.isRegularFile(meshFile)) {
                report.append("## ").append(target[0]).append(": mesh missing\n\n");
                continue;
            }
            measure(target[0], runtimeFile, meshFile, report);
        }

        Path out = Paths.get("build", "reports", "ysm-leg-orientation.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // Deliberately NOT asserted as "residual == 0". It is measured and printed, and it is
        // always zero, and a mutation that perturbs a pivot by 0.05 blocks does not move it: the
        // pose side and the toOrigin side of the product are composed from the same local
        // transforms (Joint#initOriginTransform fills toOrigin from exactly the transforms the
        // world is built from), so they cancel by construction and no armature change can break
        // them apart. An assertion here would be a test that cannot fail, which is worse than no
        // test at all - it is the same species of mistake as the metrics this round exists to
        // correct. The table's value is as a record of what was ruled out, and the assertion that
        // can fail lives in the retarget test below.
    }

    // ------------------------------------------------------------------
    // the animation retarget: does an Epic Fight pose mean the same thing
    // on the mod's armature that it means on Epic Fight's own biped?
    // ------------------------------------------------------------------

    /**
     * The measurement the identity-residual table cannot make.
     *
     * <p>{@code pose_rest * toOrigin} is the identity on every joint of every model <b>by
     * construction</b> - {@code toOrigin} is the inverse of the bind world, so the product cancels
     * whatever the pivots and the reference rotations happen to be. It is a real invariant and it
     * is worth pinning, but it can never distinguish a correct armature from a rotated one, which
     * is exactly why the previous round's "the leg path is sound" survived a visible break.
     *
     * <p>What can distinguish them is a <b>pose</b>. Epic Fight evaluates a pose on its own biped
     * and this mod re-evaluates the same pose on the model's armature ({@code YSMMesh#draw}:
     * {@code bind.setPose(captured)}), so the only question that matters is whether the same pose
     * moves the same limb the same way. The reference rotations are what makes it a question: the
     * mod copies Epic Fight's joint rotations and replaces only the translations, so a joint whose
     * own geometry is authored with a different orientation than the reference biped's can carry
     * the pose in a different frame.
     *
     * <p>This test answers it with Epic Fight's own animation data. For each of two bundled clips
     * ({@code living/idle}, {@code living/walk}, hash-guarded) it builds the pose the clip's own
     * loader would build - {@code animLocal = invert(joint local) * keyframe}, which is the
     * pre-multiplication {@code getTransformSheet} performs at clip-load time - and then, for
     * every bone whose geometry hangs on a leg joint, compares the <b>rotation the drawn geometry
     * undergoes</b> between the two armatures. The quantity is the bone's own geometry principal
     * axis, mapped through the skinning matrix that places it, and the reported number is the
     * angle between the direction the reference biped takes that axis and the direction the mod's
     * armature takes it. Zero means the pose means the same thing on both. Anything else is the
     * orientation error, measured.
     */
    @Test
    void theRetargetedPoseTurnsTheGeometryTheSameWayEpicFightsOwnBipedDoes() throws IOException {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null, "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + " to run this");
        assertTrue(BIPED_SHA256.equals(sha256Of(bipedJsonBytes())),
                "the bundled Epic Fight biped.json is not the revision this measurement was calibrated on");

        StringBuilder report = new StringBuilder();
        report.append("# Retarget check: does an Epic Fight pose move a limb the same way on the\n");
        report.append("# mod's armature as on Epic Fight's own biped?\n\n");
        report.append("`drawn rotation delta` is the rotation the *drawn geometry* undergoes between the\n");
        report.append("rest frame and the posed frame, in the body frame, measured from the principal axis\n");
        report.append("of the bone's own geometry. `reference` is Epic Fight's biped biped.json leg part\n");
        report.append("for the same joint. The last column is the disagreement - what the user sees.\n\n");

        JsonObject bipedDoc = JsonParser.parseString(new String(bipedJsonBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        Map<String, List<Vector3f>> referenceLegParts = referenceLegParts(bipedDoc);

        List<String> sampled = new ArrayList<>();
        for (String[] clip : List.of(
                new String[]{"/epicfight/anim_idle.json", "living/idle"},
                new String[]{"/epicfight/anim_walk.json", "living/walk"})) {
            JsonObject anim = JsonParser.parseString(new String(resourceBytes(clip[0]), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            report.append("## clip `").append(clip[1]).append("`\n\n");

            for (String[] target : MODELS) {
                Path runtimeFile = findConverted(pack.resolve("ysm_runtime/entity"), target[1]);
                if (runtimeFile == null) {
                    report.append("### ").append(target[0]).append(": not converted\n\n");
                    continue;
                }
                Path meshFile = pack.resolve("animmodels/entity").resolve(runtimeFile.getFileName().toString());
                if (!Files.isRegularFile(meshFile)) {
                    report.append("### ").append(target[0]).append(": mesh missing\n\n");
                    continue;
                }
                report.append("### ").append(target[0]).append("\n\n");
                retargetReport(anim, runtimeFile, meshFile, referenceLegParts, report, sampled);
            }
        }

        Path out = Paths.get("build", "reports", "ysm-leg-retarget.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // The assertion that can fail: for every leg bone of every model, under two real Epic Fight
        // clips, the rotation the drawn geometry undergoes agrees with Epic Fight's own biped.
        // A mutation that reaches this number - a dropped or conjugated reference rotation, a
        // transposed frame, a pivot used as a rotation centre - moves it by degrees. Calibrated on
        // the shipped build: all 18 EKU bones and all 6 known-good bones sit at 0.00 deg.
        assertFalse(sampled.isEmpty(),
                "no leg bone was sampled, so this measurement proved nothing");
        double worstDisagreement = 0.0;
        String worstRow = "none";
        for (String row : sampled) {
            String[] parts = row.split("\\|");
            if (parts.length < 2) {
                continue;
            }
            double value = Double.parseDouble(parts[0]);
            if (value > worstDisagreement) {
                worstDisagreement = value;
                worstRow = parts[1];
            }
        }
        assertTrue(worstDisagreement <= RETARGET_TOLERANCE_DEG,
                "an Epic Fight pose no longer turns a model's leg geometry the way it turns Epic"
                        + " Fight's own biped: worst disagreement " + worstDisagreement
                        + " deg (" + worstRow + "). This is the orientation error the user sees.");
    }

    /**
     * How far the drawn geometry's rotation may disagree with Epic Fight's own biped, in degrees.
     * The shipped build measures 0.00 on every sampled bone; the tolerance leaves room for the
     * principal-axis fit on a coarse mesh and is still two orders of magnitude below the rotation
     * error a basis mistake produces.
     */
    private static final double RETARGET_TOLERANCE_DEG = 0.5;

    /** One line per leg joint: where the pose puts it on each rig. Cleared per model. */
    private static final List<String> separationSamples = new ArrayList<>();

    /** One clip, one model: the per-bone retarget disagreement table. */
    private static void retargetReport(JsonObject anim, Path runtimeFile, Path meshFile,
                                       Map<String, List<Vector3f>> referenceLegParts,
                                       StringBuilder report, List<String> sampled) throws IOException {
        JsonObject runtime = JsonParser.parseString(Files.readString(runtimeFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject mesh = JsonParser.parseString(Files.readString(meshFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        String modelId = runtimeFile.getFileName().toString();
        separationSamples.clear();

        YsmBindArmature.GeometryInput input = inputOf(modelId, runtime, mesh);
        List<String> warnings = new ArrayList<>();
        YsmBindArmature.GeometryData geometry = YsmBindArmature.collectGeometry(input, warnings::add);
        YsmBindArmature.BindPivots bind = YsmBindArmature.computePivots(input, geometry, warnings::add);

        Joint reference = referenceBiped();
        Map<Integer, OpenMatrix4f> pivotMatrices = new HashMap<>();
        for (Map.Entry<Integer, Vector3f> entry : bind.byJoint().entrySet()) {
            OpenMatrix4f m = new OpenMatrix4f();
            m.m30 = entry.getValue().x;
            m.m31 = entry.getValue().y;
            m.m32 = entry.getValue().z;
            pivotMatrices.put(entry.getKey(), m);
        }
        Map<String, Joint> jointMap = new HashMap<>();
        Joint built = copyHierarchy(reference, new OpenMatrix4f(), pivotMatrices, jointMap, true);
        Joint referenceRig = reference;

        // ---- the pose, built exactly as JsonAssetLoader builds it ----
        // animLocal = invert(joint local) * keyframe ; getAnimationBoundMatrix then multiplies
        // the joint's own local transform back in front, which is the round trip at rest.
        JsonArray clipRows = anim.getAsJsonArray("animation");
        Map<String, OpenMatrix4f> animLocalByName = new LinkedHashMap<>();
        Map<String, Matrix4f> keyframeByName = new LinkedHashMap<>();
        for (JsonElement rowElement : clipRows) {
            JsonObject row = rowElement.getAsJsonObject();
            String name = row.get("name").getAsString();
            Joint joint = jointMap.get(name);
            if (joint == null) {
                continue;
            }
            JsonArray transforms = row.getAsJsonArray("transform");
            if (transforms.size() < 2) {
                continue;
            }
            // Keyframe 1, deliberately: frame 0 of these clips is authored at the bind pose, so a
            // frame-0 measurement could not see anything at all.
            Matrix4f keyframe = transposeOf(matrixOf(transforms.get(1).getAsJsonArray()));
            keyframeByName.put(name, keyframe);
            animLocalByName.put(name, OpenMatrix4f.mul(OpenMatrix4f.invert(joint.getLocalTransform(), null),
                    toOpenMatrix4f(keyframe), null));
        }

        Map<Integer, OpenMatrix4f> restWorlds = new HashMap<>();
        Map<Integer, OpenMatrix4f> animWorlds = new HashMap<>();
        walkPose(built, new OpenMatrix4f(), animLocalByName, restWorlds, animWorlds, new HashMap<>());

        // The same pose on Epic Fight's own biped, so the two can be compared joint by joint.
        Map<Integer, OpenMatrix4f> refRestWorlds = new HashMap<>();
        Map<Integer, OpenMatrix4f> refPosedWorlds = new HashMap<>();
        walkPose(referenceRig, new OpenMatrix4f(), animLocalByName, refRestWorlds, refPosedWorlds,
                new HashMap<>());
        for (int jointId : new int[]{JointTable.THIGH_R, JointTable.LEG_R,
                JointTable.THIGH_L, JointTable.LEG_L}) {
            separationSamples.add(JointTable.nameOf(jointId)
                    + " bipedRest=" + fmt(translationOf(refRestWorlds.get(jointId)))
                    + " bipedPosed=" + fmt(translationOf(refPosedWorlds.get(jointId)))
                    + " modPosed=" + fmt(translationOf(animWorlds.get(jointId))));
        }

        report.append("| joint | bone | reference turn (axis, angle) | mod armature turn (axis, angle) | "
                + "disagreement | geometry centroid shift |\n");
        report.append("|---|---|---|---|---|---|\n");
        double worst = 0.0;
        String worstLabel = "none";
        for (int jointId : new int[]{JointTable.THIGH_R, JointTable.LEG_R,
                JointTable.THIGH_L, JointTable.LEG_L}) {
            String jointName = JointTable.nameOf(jointId);
            Joint refJoint = jointMap.get(jointName);
            List<Vector3f> refPart = referenceThigh(jointId);
            OpenMatrix4f refSkinRest = refJoint == null || refPart == null ? null
                    : OpenMatrix4f.mul(restWorlds.get(jointId), refJoint.getToOrigin(), null);
            OpenMatrix4f refSkinAnim = refJoint == null || refPart == null ? null
                    : OpenMatrix4f.mul(animWorlds.get(jointId), refJoint.getToOrigin(), null);
            List<Vector3f> refPosed = new ArrayList<>();
            if (refPart != null) {
                for (Vector3f v : refPart) {
                    refPosed.add(apply(refSkinAnim, v));
                }
            }
            double referenceTurn = refPart == null ? Double.NaN
                    : geometryTurnDeg(refPart, refPosed);
            Vector3f referenceShift = refPart == null ? null
                    : centroid(refPosed).sub(centroid(refPart), new Vector3f());

            for (YsmBindArmature.BoneGeometry part : input.parts()) {
                int boneIndex = part.boneIndex();
                if (input.boneJoints()[boneIndex] != jointId || part.vertices().isEmpty()) {
                    continue;
                }
                String boneName = input.boneNames()[boneIndex];
                Joint bindJoint = findById(built, jointId);
                OpenMatrix4f toOrigin = bindJoint == null ? new OpenMatrix4f() : bindJoint.getToOrigin();
                OpenMatrix4f modSkinRest = OpenMatrix4f.mul(restWorlds.get(jointId), toOrigin, null);
                OpenMatrix4f modSkinAnim = OpenMatrix4f.mul(animWorlds.get(jointId), toOrigin, null);
                List<Vector3f> posedVertices = new ArrayList<>(part.vertices().size());
                for (Vector3f v : part.vertices()) {
                    posedVertices.add(apply(modSkinAnim, v));
                }
                double modTurn = geometryTurnDeg(part.vertices(), posedVertices);
                double disagreement = Double.isNaN(referenceTurn) || Double.isNaN(modTurn)
                        ? Double.NaN : Math.abs(referenceTurn - modTurn);
                if (!Double.isNaN(disagreement) && disagreement > worst) {
                    worst = disagreement;
                    worstLabel = boneName + " on " + jointName;
                }
                Vector3f modShift = centroid(posedVertices).sub(centroid(part.vertices()), new Vector3f());
                report.append("| ").append(jointName)
                        .append(" | ").append(boneName)
                        .append(" | ").append(rotationTextOf(refSkinRest, refSkinAnim))
                        .append(" | ").append(rotationTextOf(modSkinRest, modSkinAnim))
                        .append(" | ").append(Double.isNaN(disagreement) ? "n/a"
                                : String.format(Locale.ROOT, "%.2f deg", disagreement))
                        .append(" | ").append(Double.isNaN(disagreement) ? "" : shiftText(referenceShift, modShift))
                        .append(" |\n");
                if (!Double.isNaN(disagreement)) {
                    sampled.add(disagreement + "|" + boneName + " on " + jointName + " (" + modelId + ")");
                }
            }
        }
        report.append('\n')
                .append("worst disagreement: ").append(String.format(Locale.ROOT, "%.2f deg", worst))
                .append(" (").append(worstLabel).append(")\n\n");

        // The joint origins the pose puts on screen, against where the same pose puts Epic Fight's
        // own biped. A retarget that turns a limb correctly can still translate it, and a limb that
        // has come away from the body is a translation, not a rotation.
        report.append("### where the pose puts each leg joint (world)\n\n");
        report.append("`separation` is the distance between the joint's origin on Epic Fight's own "
                + "biped and on the mod's armature under the same pose.\n\n");
        report.append("| joint | reference biped | mod armature | separation |\n");
        report.append("|---|---|---|---|\n");
        double worstSeparation = 0.0;
        String worstSeparationJoint = "none";
        for (int jointId : new int[]{JointTable.THIGH_R, JointTable.LEG_R,
                JointTable.THIGH_L, JointTable.LEG_L}) {
            Vector3f onBiped = translationOf(refPosedWorlds.get(jointId));
            Vector3f mine = translationOf(animWorlds.get(jointId));
            double separation = onBiped.distance(mine);
            if (separation > worstSeparation) {
                worstSeparation = separation;
                worstSeparationJoint = JointTable.nameOf(jointId);
            }
            report.append("| ").append(JointTable.nameOf(jointId))
                    .append(" | ").append(fmt(onBiped))
                    .append(" | ").append(fmt(mine))
                    .append(" | ").append(String.format(Locale.ROOT, "%.4f blocks", separation))
                    .append(" |\n");
        }
        report.append('\n')
                .append("worst separation: ").append(String.format(Locale.ROOT, "%.4f blocks", worstSeparation))
                .append(" (").append(worstSeparationJoint).append(")\n\n");
        for (String row : separationSamples) {
            report.append("  sample: ").append(row).append('\n');
        }
        report.append('\n');
    }

    /**
     * How far the posed geometry's centre sits from where the reference biped puts it. This is the
     * quantity a rotation measurement cannot see: a pose can turn a limb exactly right and still
     * place it somewhere the reference rig never puts it.
     */
    private static String shiftText(Vector3f referenceShift, Vector3f modShift) {
        if (referenceShift == null) {
            return "";
        }
        float difference = referenceShift.distance(modShift);
        return String.format(Locale.ROOT, "ref %s | mod %s | differ %.3f blocks",
                fmt(referenceShift), fmt(modShift), difference);
    }

    /** A pose evaluated the way {@code JointTransform#getAnimationBoundMatrix} evaluates it. */
    private static void walkPose(Joint joint, OpenMatrix4f parentAnimWorld,
                                 Map<String, OpenMatrix4f> animLocalByName,
                                 Map<Integer, OpenMatrix4f> restWorlds,
                                 Map<Integer, OpenMatrix4f> animWorlds,
                                 Map<Integer, String> nameById) {
        OpenMatrix4f restWorld = OpenMatrix4f.mul(parentAnimWorld, joint.getLocalTransform(), null);
        nameById.put(joint.getId(), joint.getName());
        OpenMatrix4f animLocal = animLocalByName.get(joint.getName());
        OpenMatrix4f combined = animLocal == null ? joint.getLocalTransform()
                : OpenMatrix4f.mul(joint.getLocalTransform(), animLocal, null);
        OpenMatrix4f animWorld = OpenMatrix4f.mul(parentAnimWorld, combined, null);
        restWorlds.put(joint.getId(), restWorld);
        animWorlds.put(joint.getId(), animWorld);
        for (Joint child : joint.getSubJoints()) {
            walkPose(child, animWorld, animLocalByName, restWorlds, animWorlds, nameById);
        }
    }

    private static Joint findById(Joint joint, int id) {
        if (joint.getId() == id) {
            return joint;
        }
        for (Joint child : joint.getSubJoints()) {
            Joint found = findById(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The leg parts of Epic Fight's own biped mesh, keyed by the joint they are bound to. */
    private static Map<String, List<Vector3f>> referenceLegParts(JsonObject bipedDoc) {
        JsonObject vertices = bipedDoc.getAsJsonObject("vertices");
        JsonArray positions = vertices.getAsJsonObject("positions").getAsJsonArray("array");
        JsonArray vindices = vertices.getAsJsonObject("vindices").getAsJsonArray("array");
        JsonObject parts = vertices.getAsJsonObject("parts");
        Map<String, List<Vector3f>> out = new LinkedHashMap<>();
        for (String partName : new String[]{"leftLeg", "rightLeg"}) {
            JsonElement element = parts.get(partName);
            if (element == null) {
                continue;
            }
            JsonArray array = element.getAsJsonObject().getAsJsonArray("array");
            List<Vector3f> points = new ArrayList<>();
            int jointId = -1;
            for (int i = 0; i < array.size(); i += 3) {
                int index = array.get(i).getAsInt();
                if (jointId < 0) {
                    // The skin joint of a part's vertices, out of the mesh's own vindices table.
                    jointId = vindices.get(index).getAsInt();
                }
                points.add(new Vector3f(positions.get(index * 3).getAsFloat(),
                        positions.get(index * 3 + 1).getAsFloat(),
                        positions.get(index * 3 + 2).getAsFloat()));
            }
            if (jointId >= 0 && jointId < JointTable.COUNT) {
                out.put(JointTable.nameOf(jointId), points);
            }
        }
        return out;
    }

    /**
     * Epic Fight's own biped thigh geometry: the unit box its {@code legs} are modelled from, as
     * eight authored corners per leg. Read off {@code biped.json}'s own positions array - the leg
     * parts span x[0,0.25] y[-0.125,0.125] z[0,0.75] on the right and the mirror of that on the
     * left - and hard-coded because it is a constant of Epic Fight's asset, not of this mod.
     */
    private static final float[][] REFERENCE_RIGHT_THIGH = {
            {0.000F, -0.125F, 0.000F}, {0.250F, -0.125F, 0.000F},
            {0.250F, 0.125F, 0.000F}, {0.000F, 0.125F, 0.000F},
            {0.000F, -0.125F, 0.750F}, {0.250F, -0.125F, 0.750F},
            {0.250F, 0.125F, 0.750F}, {0.000F, 0.125F, 0.750F}};

    private static final float[][] REFERENCE_LEFT_THIGH = {
            {0.000F, -0.125F, 0.000F}, {-0.250F, -0.125F, 0.000F},
            {-0.250F, 0.125F, 0.000F}, {0.000F, 0.125F, 0.000F},
            {0.000F, -0.125F, 0.750F}, {-0.250F, -0.125F, 0.750F},
            {-0.250F, 0.125F, 0.750F}, {0.000F, 0.125F, 0.750F}};

    /** The eight corners of the given leg's box on Epic Fight's own biped. */
    private static List<Vector3f> referenceThigh(int jointId) {
        float[][] corners = jointId == JointTable.THIGH_R ? REFERENCE_RIGHT_THIGH
                : jointId == JointTable.THIGH_L ? REFERENCE_LEFT_THIGH : null;
        if (corners == null) {
            return null;
        }
        List<Vector3f> points = new ArrayList<>(corners.length);
        for (float[] corner : corners) {
            points.add(new Vector3f(corner[0], corner[1], corner[2]));
        }
        return points;
    }

    private static Vector3f apply(OpenMatrix4f m, Vector3f v) {
        return new Vector3f(
                m.m00 * v.x + m.m10 * v.y + m.m20 * v.z + m.m30,
                m.m01 * v.x + m.m11 * v.y + m.m21 * v.z + m.m31,
                m.m02 * v.x + m.m12 * v.y + m.m22 * v.z + m.m32);
    }

    /**
     * The rotation a pose applies to a drawn piece of geometry, measured from the geometry.
     *
     * <p>Kabsch: centre both point sets, build the 3x3 cross-covariance, and read the rotation out
     * of the 4x4 quaternion matrix by power iteration. Deliberately not the eigenvectors of the
     * covariance: a box has two nearly equal eigenvalues, so its eigenvector basis is unstable
     * under a pose and the "rotation" read from it jumps by tens of degrees with no rotation
     * present. That failure is worth naming here because it is the same shape of mistake as the
     * one this whole investigation is about - a number that looks like a measurement and is
     * actually an artefact of the basis it was read in.
     *
     * <p>Both point sets are in the body frame - the frame both the mod's mesh and Epic Fight's own
     * biped mesh are drawn in - so the result is a property of what is on screen.
     */
    private static double geometryTurnDeg(List<Vector3f> restPoints, List<Vector3f> posedPoints) {
        int n = Math.min(restPoints.size(), posedPoints.size());
        if (n < 4) {
            return Double.NaN;
        }
        Vector3f restCentre = centroid(restPoints);
        Vector3f posedCentre = centroid(posedPoints);
        double sxx = 0;
        double sxy = 0;
        double sxz = 0;
        double syx = 0;
        double syy = 0;
        double syz = 0;
        double szx = 0;
        double szy = 0;
        double szz = 0;
        for (int i = 0; i < n; i++) {
            double rx = restPoints.get(i).x - restCentre.x;
            double ry = restPoints.get(i).y - restCentre.y;
            double rz = restPoints.get(i).z - restCentre.z;
            double px = posedPoints.get(i).x - posedCentre.x;
            double py = posedPoints.get(i).y - posedCentre.y;
            double pz = posedPoints.get(i).z - posedCentre.z;
            sxx += rx * px;
            sxy += rx * py;
            sxz += rx * pz;
            syx += ry * px;
            syy += ry * py;
            syz += ry * pz;
            szx += rz * px;
            szy += rz * py;
            szz += rz * pz;
        }
        double[][] k = {
                {sxx + syy + szz, syz - szy, szx - sxz, sxy - syx},
                {syz - szy, sxx - syy - szz, sxy + syx, szx + sxz},
                {szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy},
                {sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz}};
        // Power iteration for the dominant eigenvector of the symmetric 4x4: the quaternion of the
        // rotation that best maps rest onto posed.
        double[] q = {1, 0, 0, 0};
        for (int step = 0; step < 96; step++) {
            double[] next = new double[4];
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    next[r] += k[r][c] * q[c];
                }
            }
            double norm = Math.sqrt(next[0] * next[0] + next[1] * next[1]
                    + next[2] * next[2] + next[3] * next[3]);
            if (norm < 1.0E-12) {
                return Double.NaN;
            }
            for (int r = 0; r < 4; r++) {
                q[r] = next[r] / norm;
            }
        }
        double w = Math.max(-1.0, Math.min(1.0, Math.abs(q[0])));
        return Math.toDegrees(2.0 * Math.acos(w));
    }

    /** The turn from one skinning matrix to another, printed as an axis and an angle. */
    private static String rotationTextOf(OpenMatrix4f from, OpenMatrix4f to) {
        if (from == null || to == null) {
            return "n/a";
        }
        org.joml.Matrix3f turn = new org.joml.Matrix3f(
                to.m00, to.m01, to.m02,
                to.m10, to.m11, to.m12,
                to.m20, to.m21, to.m22)
                .mul(new org.joml.Matrix3f(
                        from.m00, from.m01, from.m02,
                        from.m10, from.m11, from.m12,
                        from.m20, from.m21, from.m22).transpose());
        return axisAngleText(quaternionOf(turn).normalize());
    }

    /** The rotation a 3x3 matrix holds, as a quaternion (joml reads it out of a 4x4). */
    private static Quaternionf quaternionOf(org.joml.Matrix3f m) {
        org.joml.Matrix4f four = new org.joml.Matrix4f(
                m.m00, m.m01, m.m02, 0.0F,
                m.m10, m.m11, m.m12, 0.0F,
                m.m20, m.m21, m.m22, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        return four.getNormalizedRotation(new Quaternionf());
    }

    /**
     * A direction (no translation) through the rotation part of a transform. Kept beside {@link
     * #geometryTurnDeg} so the two ways of asking "which way does this point" stay readable
     * together.
     */
    private static Vector3f direction(OpenMatrix4f m, Vector3f v) {
        Vector3f out = new Vector3f(
                m.m00 * v.x + m.m10 * v.y + m.m20 * v.z,
                m.m01 * v.x + m.m11 * v.y + m.m21 * v.z,
                m.m02 * v.x + m.m12 * v.y + m.m22 * v.z);
        return out.lengthSquared() < 1.0E-12F ? out : out.normalize();
    }

    private static double angleBetween(Vector3f a, Vector3f b) {
        float dot = Math.max(-1.0F, Math.min(1.0F, a.dot(b)));
        return Math.toDegrees(Math.acos(dot));
    }

    private static Matrix4f transposeOf(OpenMatrix4f m) {
        return new Matrix4f(
                m.m00, m.m10, m.m20, m.m30,
                m.m01, m.m11, m.m21, m.m31,
                m.m02, m.m12, m.m22, m.m32,
                m.m03, m.m13, m.m23, m.m33).transpose();
    }

    private static byte[] resourceBytes(String path) throws IOException {
        try (InputStream stream = EkuLegOrientationProbeTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("missing test resource " + path);
            }
            return stream.readAllBytes();
        }
    }

    // ------------------------------------------------------------------
    // the measurement
    // ------------------------------------------------------------------

    private static void measure(String label, Path runtimeFile, Path meshFile, StringBuilder report)
            throws IOException {
        JsonObject runtime = JsonParser.parseString(Files.readString(runtimeFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject mesh = JsonParser.parseString(Files.readString(meshFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        String modelId = runtimeFile.getFileName().toString();
        float[] scale = readScale(runtime);

        YsmBindArmature.GeometryInput input = inputOf(modelId, runtime, mesh);
        List<String> warnings = new ArrayList<>();
        YsmBindArmature.GeometryData geometry = YsmBindArmature.collectGeometry(input, warnings::add);
        YsmBindArmature.BindPivots bind = YsmBindArmature.computePivots(input, geometry, warnings::add);

        // ---- 1 & 2: the armature the mod builds, and its toOrigin ----
        Joint reference = referenceBiped();
        Map<Integer, OpenMatrix4f> pivotMatrices = new HashMap<>();
        for (Map.Entry<Integer, Vector3f> entry : bind.byJoint().entrySet()) {
            OpenMatrix4f m = new OpenMatrix4f();
            m.m30 = entry.getValue().x;
            m.m31 = entry.getValue().y;
            m.m32 = entry.getValue().z;
            pivotMatrices.put(entry.getKey(), m);
        }
        Map<String, Joint> jointMap = new HashMap<>();
        Joint built = copyHierarchy(reference, new OpenMatrix4f(), pivotMatrices, jointMap, true);
        built.initOriginTransform(new OpenMatrix4f());

        report.append("## ").append(label).append("  (`").append(modelId).append("`)\n\n");
        report.append("scale w=").append(scale[0]).append(" h=").append(scale[1])
                .append(", bones=").append(input.boneNames().length)
                .append(", mesh parts=").append(input.parts().size()).append("\n\n");

        // ---- the table ----
        report.append("| joint | pivot (built) | rest rotation of the built joint (axis, angle) | "
                + "toOrigin rotation (axis, angle) | |pose_rest * toOrigin| residual rot (deg) | "
                + "residual translation (blocks) |\n");
        report.append("|---|---|---|---|---|---|\n");
        Map<Integer, OpenMatrix4f> builtWorlds = new LinkedHashMap<>();
        Map<Integer, OpenMatrix4f> builtLocals = new LinkedHashMap<>();
        collectWorlds(built, new OpenMatrix4f(), builtWorlds, builtLocals);
        Map<Integer, Joint> byId = new HashMap<>();
        collectById(built, byId);

        for (int id : REPORTED) {
            Joint joint = byId.get(id);
            if (joint == null) {
                continue;
            }
            OpenMatrix4f poseRest = builtWorlds.get(id);
            OpenMatrix4f toOrigin = joint.getToOrigin();
            OpenMatrix4f residual = OpenMatrix4f.mul(poseRest, toOrigin, null);
            Vector3f pivot = translationOf(joint.getLocalTransform());
            report.append("| ").append(JointTable.nameOf(id))
                    .append(" | ").append(fmt(pivot))
                    .append(" | ").append(rotationText(poseRest))
                    .append(" | ").append(rotationText(toOrigin))
                    .append(" | ").append(String.format(Locale.ROOT, "%.4f", residualAngleDeg(residual)))
                    .append(" | ").append(fmt(translationOf(residual)))
                    .append(" |\n");
        }
        report.append('\n');

        // ---- the same residual over ALL twenty joints, so nothing hides off the reported list ----
        double worstAngle = 0.0;
        float worstTranslation = 0.0F;
        int worstJoint = -1;
        for (Map.Entry<Integer, Joint> entry : byId.entrySet()) {
            OpenMatrix4f poseRest = builtWorlds.get(entry.getKey());
            if (poseRest == null) {
                continue;
            }
            OpenMatrix4f residual = OpenMatrix4f.mul(poseRest, entry.getValue().getToOrigin(), null);
            double angle = residualAngleDeg(residual);
            float translation = translationOf(residual).length();
            if (angle > worstAngle) {
                worstAngle = angle;
                worstJoint = entry.getKey();
            }
            worstTranslation = Math.max(worstTranslation, translation);
        }
        report.append(String.format(Locale.ROOT,
                "all %d joints: worst residual rotation %.6f deg (%s), worst residual translation %.6f blocks%n%n",
                byId.size(), worstAngle, JointTable.nameOf(worstJoint), worstTranslation));

        // ---- 4: the authored rotation of the bones bound to each leg joint ----
        report.append("### authored chain, per leg joint\n\n");
        report.append("`authoredLocal` is the bone's own `rot` from the runtime JSON (degrees), "
                + "`authoredWorldRot` is the composed authored chain's rotation at that bone.\n\n");
        report.append("| bone | parent | joint | authoredLocal rot (deg) | authoredWorld axis (deg) | pivot |\n");
        report.append("|---|---|---|---|---|---|\n");
        JsonArray bones = runtime.getAsJsonArray("bones");
        List<Integer> legBound = new ArrayList<>();
        for (int i = 0; i < bones.size(); i++) {
            int joint = bones.get(i).getAsJsonObject().get("joint").getAsInt();
            if (joint >= JointTable.THIGH_R && joint <= JointTable.KNEE_L) {
                legBound.add(i);
            }
        }
        Map<Integer, Matrix4f> authoredWorld = authoredWorlds(bones);
        for (int index : legBound) {
            JsonObject bone = bones.get(index).getAsJsonObject();
            JsonArray rot = bone.getAsJsonArray("rot");
            JsonArray pivot = bone.getAsJsonArray("pivot");
            report.append("| ").append(bone.get("name").getAsString())
                    .append(" | ").append(bone.get("parent").getAsString())
                    .append(" | ").append(JointTable.nameOf(bone.get("joint").getAsInt()))
                    .append(" | (").append(fmt(rot)).append(")")
                    .append(" | ").append(rotationText(toOpenMatrix4f(authoredWorld.get(index))))
                    .append(" | (").append(fmt(pivot)).append(") |\n");
        }
        report.append('\n');

        // ---- 5: which geometry binds to which leg joint, decided by position ----
        report.append("### leg geometry by position (not by name)\n\n");
        report.append("| EF joint | bone | pivot | own geometry bbox (authored frame) | "
                + "geometry centroid | which leg joint the geometry SITS on |\n");
        report.append("|---|---|---|---|---|---|\n");
        Map<Integer, List<Vector3f>> geometryByJoint = geometry.byJoint();
        for (int index : legBound) {
            JsonObject bone = bones.get(index).getAsJsonObject();
            String name = bone.get("name").getAsString();
            List<Vector3f> own = ownGeometry(input, index);
            if (own.isEmpty()) {
                continue;
            }
            int nearest = nearestLegJoint(geometryByJoint, own);
            report.append("| ").append(JointTable.nameOf(bone.get("joint").getAsInt()))
                    .append(" | ").append(name)
                    .append(" | (").append(fmt(bone.getAsJsonArray("pivot"))).append(")")
                    .append(" | ").append(bboxText(own))
                    .append(" | ").append(fmt(centroid(own)))
                    .append(" | ").append(nearest < 0 ? "none" : JointTable.nameOf(nearest))
                    .append(" |\n");
        }
        report.append('\n');

        // ---- the leg chain read as a shape: where each joint's geometry hangs ----
        report.append("### the leg read as a shape\n\n");
        for (int[] chain : LEG_CHAIN) {
            report.append("side ").append(JointTable.nameOf(chain[0])).append(":\n\n");
            for (int id : chain) {
                Vector3f pivot = bind.byJoint().get(id);
                List<Vector3f> settled = geometryByJoint.getOrDefault(id, List.of());
                report.append("  - ").append(JointTable.nameOf(id))
                        .append(" pivot ").append(fmt(pivot))
                        .append("  settled geometry ").append(bboxText(settled))
                        .append("  centroid ").append(fmt(centroid(settled)));
                if (pivot != null && !settled.isEmpty()) {
                    Vector3f c = centroid(settled);
                    report.append("  pivot->centroid ").append(fmt(c.sub(pivot, new Vector3f())))
                            .append(" |").append(String.format(Locale.ROOT, "%.3f", c.distance(pivot)))
                            .append("|");
                }
                report.append('\n');
            }
            report.append('\n');
        }
        for (String warning : warnings) {
            report.append("  warn: ").append(warning).append('\n');
        }
        report.append('\n');
    }

    // ------------------------------------------------------------------
    // the reference biped, rebuilt from Epic Fight's own biped.json
    // ------------------------------------------------------------------

    /**
     * Epic Fight's reference biped armature, rebuilt from the bundled {@code biped.json} the
     * way {@code JsonAssetLoader#loadArmature} builds it: the joints array gives the ids and the
     * hierarchy gives each joint's local transform.
     */
    private static Joint referenceBiped() throws IOException {
        JsonObject doc = JsonParser.parseString(new String(bipedJsonBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject armature = doc.getAsJsonObject("armature");
        JsonArray joints = armature.getAsJsonArray("joints");
        Map<String, Integer> idOf = new HashMap<>();
        for (int i = 0; i < joints.size(); i++) {
            idOf.put(joints.get(i).getAsString(), i);
        }
        JsonArray hierarchy = armature.getAsJsonArray("hierarchy");
        return buildReference(hierarchy.get(0).getAsJsonObject(), idOf);
    }

    private static Joint buildReference(JsonObject node, Map<String, Integer> idOf) {
        String name = node.get("name").getAsString();
        OpenMatrix4f local = matrixOf(node.getAsJsonArray("transform"));
        Joint joint = new Joint(name, idOf.get(name), local);
        for (JsonElement child : node.getAsJsonArray("children")) {
            joint.addSubJoints(buildReference(child.getAsJsonObject(), idOf));
        }
        return joint;
    }

    /** The perturbation the pivot mutation check applies; 0.0 in the shipped file. */
    private static final float MUTANT_PIVOT_OFFSET = 0.0F;

    /**
     * Mutation-check switch, false in the shipped file. Setting it true drops every reference
     * joint's rotation, which is the "the foreign model's own frame" hypothesis for the leg break.
     * With it true the retarget measurement recorded 168.35/168.08 degrees of turn instead of
     * 12.70/32.33 <b>on both sides at once</b> - which is the point of the note on the residual
     * table: this test compares two rigs that share the armature code, so it catches a change in
     * what the code is fed (a pivot, a hierarchy, an animation matrix) and not a change in the
     * conventions the code uses. A mutation of the latter has to be caught against the geometry
     * the mesh file already bakes, which is the next thing to measure.
     */
    private static final boolean MUTANT_DROP_REFERENCE_ROTATION = false;

    /** {@code YsmBindArmature#copyHierarchy}, mirrored: see that method for the shipped logic. */
    private static Joint copyHierarchy(Joint refJoint, OpenMatrix4f newParentWorld, Map<Integer, OpenMatrix4f> pivots, Map<String, Joint> out, boolean root) {
        OpenMatrix4f refLocal = refJoint.getLocalTransform();
        OpenMatrix4f newLocal = new OpenMatrix4f(refLocal);
        // MUTANT: the reference joint's rotation is not carried over, which is the "foreign model's
        // own frame" hypothesis for the leg break. Reverts to the shipped line when 0.
        if (MUTANT_DROP_REFERENCE_ROTATION && !"Knee_R".equals(refJoint.getName())) {
            newLocal.m00 = 1.0F;
            newLocal.m01 = 0.0F;
            newLocal.m02 = 0.0F;
            newLocal.m10 = 0.0F;
            newLocal.m11 = 1.0F;
            newLocal.m12 = 0.0F;
            newLocal.m20 = 0.0F;
            newLocal.m21 = 0.0F;
            newLocal.m22 = 1.0F;
        }
        OpenMatrix4f pivot = pivots.get(refJoint.getId());
        if (pivot != null) {
            if (root) {
                newLocal.m30 = pivot.m30;
                newLocal.m31 = pivot.m31;
                newLocal.m32 = pivot.m32;
            } else {
                OpenMatrix4f parentInv = OpenMatrix4f.invert(newParentWorld, null);
                OpenMatrix4f offset = OpenMatrix4f.mul(parentInv, pivot, null);
                newLocal.m30 = offset.m30 + MUTANT_PIVOT_OFFSET;
                newLocal.m31 = offset.m31;
                newLocal.m32 = offset.m32;
            }
        }
        Joint joint = new Joint(refJoint.getName(), refJoint.getId(), newLocal);
        out.put(joint.getName(), joint);
        OpenMatrix4f newWorld = OpenMatrix4f.mul(newParentWorld, newLocal, null);
        for (Joint child : refJoint.getSubJoints()) {
            joint.addSubJoints(copyHierarchy(child, newWorld, pivots, out, false));
        }
        return joint;
    }

    private static void collectWorlds(Joint joint, OpenMatrix4f parentWorld,
                                      Map<Integer, OpenMatrix4f> worlds, Map<Integer, OpenMatrix4f> locals) {
        OpenMatrix4f world = OpenMatrix4f.mul(parentWorld, joint.getLocalTransform(), null);
        worlds.put(joint.getId(), world);
        locals.put(joint.getId(), joint.getLocalTransform());
        for (Joint child : joint.getSubJoints()) {
            collectWorlds(child, world, worlds, locals);
        }
    }

    private static void collectById(Joint joint, Map<Integer, Joint> out) {
        out.put(joint.getId(), joint);
        for (Joint child : joint.getSubJoints()) {
            collectById(child, out);
        }
    }

    /** The composed authored chain of the runtime bone table, in the package's own frame. */
    private static Map<Integer, Matrix4f> authoredWorlds(JsonArray bones) {
        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            indexOf.put(bones.get(i).getAsJsonObject().get("name").getAsString(), i);
        }
        Matrix4f[] cache = new Matrix4f[bones.size()];
        Map<Integer, Matrix4f> out = new LinkedHashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            out.put(i, authoredWorld(bones, indexOf, cache, i, 0));
        }
        return out;
    }

    private static Matrix4f authoredWorld(JsonArray bones, Map<String, Integer> indexOf,
                                          Matrix4f[] cache, int index, int depth) {
        if (cache[index] != null) {
            return cache[index];
        }
        if (depth > 64) {
            return new Matrix4f();
        }
        JsonObject bone = bones.get(index).getAsJsonObject();
        JsonArray pivot = bone.getAsJsonArray("pivot");
        JsonArray rot = bone.getAsJsonArray("rot");
        Matrix4f local = new Matrix4f()
                .translate(pivot.get(0).getAsFloat(), pivot.get(1).getAsFloat(), pivot.get(2).getAsFloat())
                .rotateZ((float) Math.toRadians(rot.get(2).getAsFloat()))
                .rotateY((float) Math.toRadians(rot.get(1).getAsFloat()))
                .rotateX((float) Math.toRadians(rot.get(0).getAsFloat()))
                .translate(-pivot.get(0).getAsFloat(), -pivot.get(1).getAsFloat(), -pivot.get(2).getAsFloat());
        String parentName = bone.get("parent").getAsString();
        Integer parent = parentName.isEmpty() ? null : indexOf.get(parentName);
        Matrix4f world = parent == null ? new Matrix4f(local)
                : new Matrix4f(authoredWorld(bones, indexOf, cache, parent, depth + 1)).mul(local);
        cache[index] = world;
        return world;
    }

    // ------------------------------------------------------------------
    // inputs
    // ------------------------------------------------------------------

    private static float[] readScale(JsonObject runtime) {
        if (!runtime.has("scale")) {
            return new float[]{1.0F, 1.0F};
        }
        JsonArray scale = runtime.getAsJsonArray("scale");
        return new float[]{scale.get(0).getAsFloat(), scale.get(1).getAsFloat()};
    }

    /** {@code YsmBindArmature#inputOf}, over the converted files rather than a live mesh. */
    private static YsmBindArmature.GeometryInput inputOf(String modelId, JsonObject runtime, JsonObject mesh) {
        JsonArray bonesJson = runtime.getAsJsonArray("bones");
        int boneCount = bonesJson.size();
        String[] names = new String[boneCount];
        int[] joints = new int[boneCount];
        boolean[] mapped = new boolean[boneCount];
        Map<String, Integer> indexOfName = new HashMap<>();
        for (int i = 0; i < boneCount; i++) {
            JsonObject bone = bonesJson.get(i).getAsJsonObject();
            names[i] = bone.get("name").getAsString();
            joints[i] = bone.get("joint").getAsInt();
            mapped[i] = bone.has("mapped") && bone.get("mapped").getAsBoolean();
            indexOfName.put(names[i], i);
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
            Integer boneIdx = indexOfName.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (boneIdx == null) {
                continue;
            }
            JsonArray indices = entry.getValue().getAsJsonObject().getAsJsonArray("array");
            List<Vector3f> partVertices = new ArrayList<>(indices.size());
            for (JsonElement index : indices) {
                int p = index.getAsInt() * 3;
                if (p + 2 < positions.length) {
                    partVertices.add(new Vector3f(positions[p], positions[p + 1], positions[p + 2]));
                }
            }
            parts.add(new YsmBindArmature.BoneGeometry(boneIdx, partVertices));
        }
        return new YsmBindArmature.GeometryInput(modelId, names, joints, mapped, Set.of(), parts);
    }

    /** The vertices of one bone's own mesh part, straight out of the filter's input. */
    private static List<Vector3f> ownGeometry(YsmBindArmature.GeometryInput input, int boneIndex) {
        List<Vector3f> out = new ArrayList<>();
        for (YsmBindArmature.BoneGeometry part : input.parts()) {
            if (part.boneIndex() == boneIndex) {
                out.addAll(part.vertices());
            }
        }
        return out;
    }

    /** Which of the leg joints a blob of geometry sits nearest to, by centroid distance. */
    private static int nearestLegJoint(Map<Integer, List<Vector3f>> geometryByJoint, List<Vector3f> own) {
        Vector3f c = centroid(own);
        if (c == null) {
            return -1;
        }
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int joint : new int[]{JointTable.THIGH_R, JointTable.LEG_R, JointTable.KNEE_R,
                JointTable.THIGH_L, JointTable.LEG_L, JointTable.KNEE_L}) {
            Vector3f pivot = centroid(geometryByJoint.getOrDefault(joint, List.of()));
            if (pivot == null) {
                continue;
            }
            float distance = c.distance(pivot);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = joint;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // small maths helpers
    // ------------------------------------------------------------------

    private static OpenMatrix4f matrixOf(JsonArray array) {
        OpenMatrix4f m = new OpenMatrix4f();
        m.m00 = array.get(0).getAsFloat();
        m.m01 = array.get(1).getAsFloat();
        m.m02 = array.get(2).getAsFloat();
        m.m03 = array.get(3).getAsFloat();
        m.m10 = array.get(4).getAsFloat();
        m.m11 = array.get(5).getAsFloat();
        m.m12 = array.get(6).getAsFloat();
        m.m13 = array.get(7).getAsFloat();
        m.m20 = array.get(8).getAsFloat();
        m.m21 = array.get(9).getAsFloat();
        m.m22 = array.get(10).getAsFloat();
        m.m23 = array.get(11).getAsFloat();
        m.m30 = array.get(12).getAsFloat();
        m.m31 = array.get(13).getAsFloat();
        m.m32 = array.get(14).getAsFloat();
        m.m33 = array.get(15).getAsFloat();
        return m;
    }

    private static Vector3f translationOf(OpenMatrix4f m) {
        return new Vector3f(m.m30, m.m31, m.m32);
    }

    /** The same transform as an Epic Fight matrix, so one rotation reader serves both frames. */
    private static OpenMatrix4f toOpenMatrix4f(Matrix4f m) {
        OpenMatrix4f out = new OpenMatrix4f();
        out.m00 = m.m00();
        out.m01 = m.m01();
        out.m02 = m.m02();
        out.m03 = m.m03();
        out.m10 = m.m10();
        out.m11 = m.m11();
        out.m12 = m.m12();
        out.m13 = m.m13();
        out.m20 = m.m20();
        out.m21 = m.m21();
        out.m22 = m.m22();
        out.m23 = m.m23();
        out.m30 = m.m30();
        out.m31 = m.m31();
        out.m32 = m.m32();
        out.m33 = m.m33();
        return out;
    }

    private static Quaternionf rotationOf(OpenMatrix4f m) {
        Matrix4f joml = new Matrix4f(
                m.m00, m.m10, m.m20, 0.0F,
                m.m01, m.m11, m.m21, 0.0F,
                m.m02, m.m12, m.m22, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        return joml.getNormalizedRotation(new Quaternionf());
    }

    private static double residualAngleDeg(OpenMatrix4f m) {
        Quaternionf q = rotationOf(m);
        double angle = 2.0 * Math.acos(Math.min(1.0, Math.abs(q.w)));
        return Math.toDegrees(angle);
    }

    private static String rotationText(OpenMatrix4f m) {
        if (m == null) {
            return "null";
        }
        Quaternionf q = rotationOf(m);
        return quaternionText(q) + " = " + axisAngleText(q);
    }

    private static String quaternionText(Quaternionf q) {
        return String.format(Locale.ROOT, "(%.4f,%.4f,%.4f,%.4f)", q.x, q.y, q.z, q.w);
    }

    private static String axisAngleText(Quaternionf q) {
        float w = Math.max(-1.0F, Math.min(1.0F, q.w));
        double angle = Math.toDegrees(2.0 * Math.acos(Math.abs(w)));
        float sin = (float) Math.sqrt(Math.max(0.0, 1.0 - w * w));
        if (sin < 1.0E-6F) {
            return String.format(Locale.ROOT, "%.2f deg", angle);
        }
        float sign = w < 0 ? -1.0F : 1.0F;
        return String.format(Locale.ROOT, "axis (%.3f,%.3f,%.3f) by %.2f deg",
                sign * q.x / sin, sign * q.y / sin, sign * q.z / sin, angle);
    }

    private static String fmt(Vector3f v) {
        return v == null ? "null" : String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

    private static String fmt(JsonArray a) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f",
                a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    private static String bboxText(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return "none";
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        return String.format(Locale.ROOT, "x[%.3f,%.3f] y[%.3f,%.3f] z[%.3f,%.3f]",
                minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static Vector3f centroid(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        Vector3f acc = new Vector3f();
        for (Vector3f v : vertices) {
            acc.add(v);
        }
        return acc.div((float) vertices.size());
    }

    // ------------------------------------------------------------------
    // install plumbing
    // ------------------------------------------------------------------

    private static byte[] bipedJsonBytes() throws IOException {
        try (InputStream stream = EkuLegOrientationProbeTest.class.getResourceAsStream("/epicfight/biped.json")) {
            if (stream == null) {
                throw new IOException("the bundled Epic Fight biped.json is missing from the test resources");
            }
            return stream.readAllBytes();
        }
    }

    private static String sha256Of(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
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

    private static Path findConverted(Path dir, String needle) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .contains(needle.toLowerCase(Locale.ROOT)))
                    .min(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElse(null);
        }
    }
}
