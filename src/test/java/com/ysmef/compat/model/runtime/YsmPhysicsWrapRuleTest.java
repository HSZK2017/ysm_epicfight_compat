package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The rule that keeps a band worn <b>around</b> the body out of the pendulum simulation
 * ({@link YsmPhysicsParts#wrapsPivot}), and the shapes it must leave alone.
 *
 * <p>The defect it was written for is the skewed skirt of {@code 兽耳酱x1} / {@code NagaU_Kemomimi}:
 * the outer band {@code X_yiqun1} carries its authored pivot on its own rim - (0.150, 0.727, -0.025)
 * against a geometry centre of (0.000, 0.752, -0.010) - so {@code rest = centroid - pivot} comes out
 * 0.98 horizontal, and the solver's gravity blend tilts the whole band about a point beside it,
 * moving the hem about 0.2 blocks.
 *
 * <p>Two kinds of assertion, and both matter:
 *
 * <ol>
 *   <li><b>Hand-built shapes</b> (always run): a ring round the model's axis with its pivot on the
 *       rim must be rejected; a hanging panel, a strand and a lock of hair on one side of the body
 *       must not be. These pin the three structural conditions - centred on the mirror plane, pivot
 *       out at the rim, and geometry whose directions from the pivot are not concentrated on one
 *       axis - one at a time, and they are what the mutation check breaks.</li>
 *   <li><b>The shipped model itself</b> (skipped unless the install is configured): every physics
 *       bone the game's own log named for {@code 兽耳酱x1}, measured through the production pivot
 *       arithmetic on the model's own converted mesh. This is the calibration the corpus report
 *       ({@code build/reports/ysm-physics-wrap-corpus.md}) summarises, on the one model whose answer
 *       is known.</li>
 * </ol>
 */
class YsmPhysicsWrapRuleTest {

    /** The physics bones the game logged for 兽耳酱x1 (see HeadSkirtDefectDiagTest). */
    private static final List<String> LOGGED_PHYSICS_BONES = List.of(
            "X_T4_1", "X_yiqun1", "X_T3_1", "X_T6_1", "X_Hair1", "X_T5_1", "X_liuhai_F_1", "X_fawei1",
            "X_T7_1", "Left_ear_F_1", "X_T2_1", "X_qunzi1", "Right_ear_R_1", "X_liuhai_Z_1", "X_liuhai_R_1");

    /** The bones of that list which are bands round the body and must lose their segment. */
    private static final List<String> MUST_BE_REJECTED = List.of("X_yiqun1");

    // ------------------------------------------------------------------
    // hand-built shapes
    // ------------------------------------------------------------------

    /**
     * The shape of the shipped defect: a band round the model's own axis whose pivot sits inside its
     * wall. Every slice of it is centred on x = 0, so the piece is worn around the trunk, and the
     * pivot is 0.15 of the 0.21 half-width away from that axis.
     */
    @Test
    @DisplayName("a band round the body with its pivot on the rim is rejected")
    void aBandWithItsPivotOnTheRimIsRejected() {
        List<Vector3f> band = ringAroundTheAxis(0.21F, 0.19F, 0.58F, 0.97F);
        Vector3f pivot = new Vector3f(0.150F, 0.727F, -0.025F);

        assertTrue(YsmPhysicsParts.wrapsPivot(band, pivot),
                "a skirt band whose pivot is on its rim must not become a swinging segment");
        assertTrue(YsmPhysicsParts.bandAxisOffset(band, pivot) >= 0.5F,
                "the pivot is out at the rim of a piece centred on the body axis");
        assertTrue(YsmPhysicsParts.directionSpread(band, pivot) < 2.6F,
                "a band's geometry surrounds its pivot, so the direction spread stays low");
    }

    /**
     * The counter-shape from the same model: {@code X_qunzi1} is a band too, but its pivot sits at
     * its own top edge on the axis (0.036 off it), so it hangs from the waist and must keep swinging.
     */
    @Test
    @DisplayName("a band whose pivot is at its top edge on the axis keeps its segment")
    void aBandHangingFromItsTopEdgeIsKept() {
        List<Vector3f> band = ringAroundTheAxis(0.25F, 0.19F, 0.46F, 0.65F);

        assertFalse(YsmPhysicsParts.wrapsPivot(band, new Vector3f(0.036F, 0.649F, -0.018F)),
                "a panel that hangs from its own top edge is a pendulum, not a band about its pivot");
    }

    /**
     * The other counter-shape: a lock of hair on one side of the head. Its geometry is entirely on
     * one side of the mirror plane, so the "worn around the body" condition cannot hold however far
     * its pivot is from that plane.
     */
    @Test
    @DisplayName("a lock of hair on one side of the body is kept, whatever its pivot")
    void aLockOnOneSideOfTheBodyIsKept() {
        List<Vector3f> lock = box(0.12F, 0.12F, 0.10F, 1.35F, 1.60F, 0.20F);

        assertFalse(YsmPhysicsParts.wrapsPivot(lock, new Vector3f(0.30F, 1.62F, 0.10F)),
                "a lock hanging beside the head is not a band worn round the body");
        assertEquals(0.0F, YsmPhysicsParts.bandAxisOffset(lock, new Vector3f(0.30F, 1.62F, 0.10F)), 1.0E-6F,
                "a piece that does not straddle the mirror plane has no band offset at all");
    }

    /** A thin strand hanging from its root: the shape that must always stay simulated. */
    @Test
    @DisplayName("a strand hanging from its root is kept")
    void aHangingStrandIsKept() {
        List<Vector3f> strand = box(0.0F, 0.0F, 0.02F, 1.40F, 1.62F, 0.02F);

        assertFalse(YsmPhysicsParts.wrapsPivot(strand, new Vector3f(0.0F, 1.62F, 0.0F)),
                "a ponytail is the piece the pendulum exists for");
        assertTrue(YsmPhysicsParts.directionSpread(strand, new Vector3f(0.0F, 1.62F, 0.0F)) > 2.6F,
                "a strand's directions are concentrated on one axis");
    }

    /**
     * What "cannot tell" must answer: a bone with too little geometry to have a shape is never
     * rejected, because dropping a segment on no evidence is a visible loss (the piece stops moving)
     * and an unclassified one is not.
     */
    @Test
    @DisplayName("too little geometry to measure is never rejected")
    void tooLittleGeometryIsNeverRejected() {
        assertFalse(YsmPhysicsParts.wrapsPivot(null, new Vector3f(1.0F, 1.0F, 1.0F)));
        assertFalse(YsmPhysicsParts.wrapsPivot(List.of(), new Vector3f(1.0F, 1.0F, 1.0F)));
        assertFalse(YsmPhysicsParts.wrapsPivot(
                List.of(new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f(0.1F, 1.0F, 0.0F)),
                new Vector3f(0.15F, 1.0F, 0.0F)));
        assertFalse(YsmPhysicsParts.wrapsPivot(ringAroundTheAxis(0.21F, 0.19F, 0.58F, 0.97F), null));
        assertEquals(Float.MAX_VALUE, YsmPhysicsParts.directionSpread(null, new Vector3f()), 1.0E-6F);
        assertEquals(0.0F, YsmPhysicsParts.bandAxisOffset(null, new Vector3f()), 1.0E-6F);
    }

    /** A flat piece with no width at all has no axis to be offset from and is left alone. */
    @Test
    @DisplayName("a degenerate x span cannot make a band")
    void aDegenerateSpanCannotMakeABand() {
        List<Vector3f> flat = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            flat.add(new Vector3f(0.0F, 1.0F + i * 0.01F, 0.1F * i));
        }

        assertEquals(0.0F, YsmPhysicsParts.bandAxisOffset(flat, new Vector3f(0.5F, 1.0F, 0.0F)), 1.0E-6F);
        assertFalse(YsmPhysicsParts.wrapsPivot(flat, new Vector3f(0.5F, 1.0F, 0.0F)));
    }

    // ------------------------------------------------------------------
    // the shipped model
    // ------------------------------------------------------------------

    /**
     * The same question asked of the model the defect was reported on, through the production
     * arithmetic: the pivot is {@link YsmPhysicsParts#pivotInMeshSpace} over the model's own bind
     * chain, and the vertices are the ones the converted mesh part carries.
     */
    @Test
    @DisplayName("on 兽耳酱x1 the skirt band is rejected and every other physics bone is kept")
    void theShippedSkirtModelIsJudgedBoneByBone(@TempDir Path temp) throws IOException {
        String modelId = "兽耳酱x1.ysm";
        Converted converted = convert(modelId, temp);
        assumeTrue(converted != null, "the install has no '" + modelId + "'; set "
                + YsmModelPackage.CONFIG_ROOT_ENV + " or -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY);

        StringBuilder report = new StringBuilder();
        report.append("bone | own geometry slots | bandOffset | spread | verdict\n");
        report.append("---|---|---|---|---\n");
        int judged = 0;
        for (String bone : LOGGED_PHYSICS_BONES) {
            Judgement judgement = converted.judge(bone);
            if (judgement == null) {
                report.append(bone).append(" | - | - | - | no geometry of its own\n");
                continue;
            }
            judged++;
            report.append(String.format(Locale.ROOT, "%s | %d | %.3f | %.3f | %s%n",
                    bone, judgement.slots, judgement.bandOffset, judgement.spread,
                    judgement.wraps ? "REJECTED (band about its pivot)" : "kept"));
        }
        // Written before the assertions, so a failure still leaves the table that explains it.
        writeReport("ysm-wrap-rule-___x1.md", report.toString());
        for (String bone : LOGGED_PHYSICS_BONES) {
            Judgement judgement = converted.judge(bone);
            if (judgement == null) {
                continue;
            }
            if (MUST_BE_REJECTED.contains(bone)) {
                assertTrue(judgement.wraps, bone + " is the skewed skirt band and must be rejected: " + report);
            } else {
                assertFalse(judgement.wraps,
                        bone + " must keep its segment - rejecting it would silently stop a piece the "
                                + "user watches move: " + report);
            }
        }
        assertTrue(judged >= 10, "only " + judged + " of the logged physics bones were found in the "
                + "converted mesh; the pack is stale or the model changed: " + report);
    }

    /**
     * The rule is read in the frame - and at the scale - the vertices are in, and the classification
     * is invariant under both.
     *
     * <p>This is the measurement that says the pivot-frame fix did <b>not</b> move this rule: the mesh
     * frame and the authored frame are a quarter turn apart, and {@code wrapsPivot}'s inputs (a
     * distance to the pivot, the pivot's offset from the geometry's axis, and a ratio of principal
     * extents) are all invariant under a rotation. The two readings are therefore identical, and the
     * rule's verdict on the real model is the same one it had.
     *
     * <p>What that means for the test above is the important part: the shipped code and this fixture
     * only agree when the fixture reads the vertices in the frame the pivot is named in <i>and at the
     * units the mesh carries</i>. Reading them through a swap and an extra scale produced a different
     * bone - the fixture's old {@code (x, z, -y)} of the raw JSON numbers at the model's own scale,
     * against a pivot the write path had already scaled - and that disagreement, not the rule, is what
     * the frame fix surfaced.
     */
    @Test
    @DisplayName("the frame is a rotation, so the classification is the same in both")
    void theFrameDoesNotMoveTheClassification(@TempDir Path temp) throws IOException {
        Converted converted = convert("兽耳酱x1.ysm", temp);
        assumeTrue(converted != null, "the install has no '兽耳酱x1.ysm'");

        StringBuilder report = new StringBuilder();
        report.append("bone | frame | bandOffset | spread | verdict\n---|---|---|---|---\n");
        int compared = 0;
        for (String bone : LOGGED_PHYSICS_BONES) {
            Judgement mesh = converted.judge(bone);
            Judgement authored = converted.judgeInTheAuthoredFrame(bone);
            if (mesh == null || authored == null) {
                continue;
            }
            compared++;
            report.append(String.format(Locale.ROOT, "%s | mesh frame | %.3f | %.3f | %s%n",
                    bone, mesh.bandOffset, mesh.spread, mesh.wraps ? "REJECTED" : "kept"));
            report.append(String.format(Locale.ROOT, "%s | authored frame | %.3f | %.3f | %s%n",
                    bone, authored.bandOffset, authored.spread, authored.wraps ? "REJECTED" : "kept"));
            assertEquals(mesh.spread, authored.spread, 1.0E-3F,
                    bone + ": the two frames are a rotation of each other, so the geometry's shape"
                            + " about its pivot cannot differ: " + report);
            assertEquals(mesh.wraps, authored.wraps,
                    bone + ": and the verdict that follows from it cannot differ either: " + report);
        }
        assertTrue(compared >= 10, "too few bones compared: " + compared);
        writeReport("ysm-wrap-rule-frames.md", report.toString());

        // The reading that would have caught the fixture's own error, on the bone whose verdict is
        // known, and the reason the two consistent frames above agree: a rotation preserves the
        // classification, so a fixture that reads its vertices in a frame the pivot is NOT named in is
        // not testing the rule at all. That mixed reading is what this project's pivot-frame defect
        // actually was, and on this bone it reads the band as not wrapped.
        Judgement mesh = converted.judge("X_yiqun1");
        Judgement mixed = converted.judgeWithPivotInTheOtherFrame("X_yiqun1");
        assertNotNull(mesh, "X_yiqun1 must carry geometry in the converted mesh");
        assertNotNull(mixed, "X_yiqun1 must carry geometry in the converted mesh");
        assertTrue(mesh.wraps, "the shipped rule must reject this model's skewed skirt band: " + report);
        assertFalse(mixed.wraps,
                "and naming the pivot in a frame the vertices are not in must NOT reproduce that"
                        + " verdict - the rule would then be reading a different bone: " + report);
        assertFalse(mixed.spread == mesh.spread,
                "the mixed frame's spread must differ: mesh=" + mesh.spread + " mixed=" + mixed.spread);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * A box shell of vertices, in mesh space: the shape a model's own geometry has. {@code centreX}
     * and {@code centreZ} place it, {@code halfWidth} and {@code depth} size it in the horizontal
     * plane and {@code minY..maxY} its height.
     */
    private static List<Vector3f> box(float centreX, float centreZ, float halfWidth,
                                      float minY, float maxY, float depth) {
        List<Vector3f> out = new ArrayList<>();
        for (int x = -1; x <= 1; x += 2) {
            for (int z = -1; z <= 1; z += 2) {
                for (int y = 0; y < 4; y++) {
                    float t = y / 3.0F;
                    out.add(new Vector3f(centreX + x * halfWidth, minY + t * (maxY - minY), centreZ + z * depth));
                }
            }
        }
        return out;
    }

    /** A band round the model's own left-right axis: a box shell centred on x = 0. */
    private static List<Vector3f> ringAroundTheAxis(float halfWidth, float depth, float minY, float maxY) {
        return box(0.0F, 0.0F, halfWidth, minY, maxY, depth);
    }

    // ------------------------------------------------------------------
    // the converted pack
    // ------------------------------------------------------------------

    /** One bone's production measurements, or null when the mesh carries no part for it. */
    private record Judgement(int slots, float bandOffset, float spread, boolean wraps) {}

    /**
     * The converted mesh + runtime of one model, with the pivot arithmetic of the production path.
     */
    private static final class Converted {
        final float[] positionsMesh;
        final Map<String, int[]> parts;
        final Map<String, Matrix4f> bindWorld;
        final Map<String, float[]> raw;
        final float scaleW;
        final float scaleH;

        Converted(float[] positionsMesh, Map<String, int[]> parts, Map<String, Matrix4f> bindWorld,
                  Map<String, float[]> raw, float scaleW, float scaleH) {
            this.positionsMesh = positionsMesh;
            this.parts = parts;
            this.bindWorld = bindWorld;
            this.raw = raw;
            this.scaleW = scaleW;
            this.scaleH = scaleH;
        }

        Judgement judge(String boneName) {
            int[] indices = parts.get(EFMeshJsonWriter.BONE_PART_PREFIX + boneName);
            float[] bone = raw.get(boneName);
            if (indices == null || indices.length == 0 || bone == null) {
                return null;
            }
            Vector3f pivot = YsmPhysicsParts.pivotInMeshSpace(bindWorld.get(boneName),
                    bone[0], bone[1], bone[2], scaleW, scaleH);
            if (pivot == null) {
                return null;
            }
            List<Vector3f> vertices = new ArrayList<>(indices.length);
            for (int index : indices) {
                if (index >= 0 && index * 3 + 2 < positionsMesh.length) {
                    // The JSON stores the writer's FILE frame ((x, -z, y) times the scale); the rule in
                    // production is handed the loader's frame - the authored chain scaled once, no turn
                    // - so un-turn here. Pairing the stored values with the production pivot directly is
                    // the frame-mixed reading that made this rule look like a rule about a bone a body
                    // away, and it is kept as the explicit negative control below.
                    vertices.add(new Vector3f(positionsMesh[index * 3], positionsMesh[index * 3 + 2],
                            -positionsMesh[index * 3 + 1]));
                }
            }
            return new Judgement(vertices.size(), YsmPhysicsParts.bandAxisOffset(vertices, pivot),
                    YsmPhysicsParts.directionSpread(vertices, pivot),
                    YsmPhysicsParts.wrapsPivot(vertices, pivot));
        }

        /**
         * The same comparison with both halves in the authored frame: the pivot as the shipped code
         * named it (the composed chain, then the scale, no turn) and the vertices un-turned back into
         * that frame. Consistent, so it agrees with {@link #judge} - which is the point, and is why
         * the fixture's frame was never what moved this rule's verdict.
         */
        Judgement judgeInTheAuthoredFrame(String boneName) {
            int[] part = parts.get(EFMeshJsonWriter.BONE_PART_PREFIX + boneName);
            float[] bone = raw.get(boneName);
            if (part == null || part.length == 0 || bone == null) {
                return null;
            }
            Vector3f pivot = new Vector3f(bone[0], bone[1], bone[2]);
            bindWorld.get(boneName).transformPosition(pivot);
            pivot.mul(scaleW, scaleH, scaleW);
            List<Vector3f> vertices = new ArrayList<>(part.length);
            for (int index : part) {
                if (index >= 0 && index * 3 + 2 < positionsMesh.length) {
                    // The inverse of the writer's (x, y, z) -> (x, -z, y).
                    vertices.add(new Vector3f(positionsMesh[index * 3], positionsMesh[index * 3 + 2],
                            -positionsMesh[index * 3 + 1]));
                }
            }
            return new Judgement(vertices.size(), YsmPhysicsParts.bandAxisOffset(vertices, pivot),
                    YsmPhysicsParts.directionSpread(vertices, pivot),
                    YsmPhysicsParts.wrapsPivot(vertices, pivot));
        }

        /**
         * The mixed reading: the production pivot - named in the mesh frame - against the vertices
         * turned into the authored frame. Each half is consistent with the writer on its own and the
         * two are not consistent with each other, which is exactly what the pivot-frame defect did to
         * this classification: a rule about a bone's rim read as a rule about a bone a body away.
         *
         * <p>Kept beside {@link #judgeInTheAuthoredFrame} because the pair is the measurement: one
         * reproduces the shipped verdict, the other shows what the defect looked like, and the gap
         * between them is why the fixture's frame had to be pinned rather than left implicit.
         */
        Judgement judgeWithPivotInTheOtherFrame(String boneName) {
            int[] part = parts.get(EFMeshJsonWriter.BONE_PART_PREFIX + boneName);
            float[] bone = raw.get(boneName);
            if (part == null || part.length == 0 || bone == null) {
                return null;
            }
            Vector3f pivot = YsmPhysicsParts.pivotInMeshSpace(bindWorld.get(boneName),
                    bone[0], bone[1], bone[2], scaleW, scaleH);
            if (pivot == null) {
                return null;
            }
            Vector3f otherFrame = new Vector3f(pivot.x, pivot.z, -pivot.y);
            List<Vector3f> vertices = new ArrayList<>(part.length);
            for (int index : part) {
                if (index >= 0 && index * 3 + 2 < positionsMesh.length) {
                    vertices.add(new Vector3f(positionsMesh[index * 3], positionsMesh[index * 3 + 1],
                            positionsMesh[index * 3 + 2]));
                }
            }
            return new Judgement(vertices.size(),
                    YsmPhysicsParts.bandAxisOffset(vertices, otherFrame),
                    YsmPhysicsParts.directionSpread(vertices, otherFrame),
                    YsmPhysicsParts.wrapsPivot(vertices, otherFrame));
        }
    }

    /** Convert the model package to a temporary mesh + runtime pair and read both back. */
    private static Converted convert(String modelId, Path temp) throws IOException {
        if (configuredConfigRoot().isEmpty()) {
            return null;
        }
        YsmModelPackage pkg;
        try {
            pkg = YsmModelPackage.load(modelId);
        } catch (Throwable t) {
            return null;
        }
        if (pkg == null || pkg.geometry == null) {
            return null;
        }
        Path meshFile = temp.resolve("mesh.json");
        Path runtimeFile = temp.resolve("runtime.json");
        EFMeshJsonWriter.write(pkg, meshFile, runtimeFile, "ysm_epicfight_compat:textures/audit.png");
        return read(meshFile, runtimeFile, pkg.widthScale, pkg.heightScale);
    }

    private static Converted read(Path meshFile, Path runtimeFile, float scaleW, float scaleH)
            throws IOException {
        JsonObject meshRoot = JsonParser.parseString(Files.readString(meshFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject vertices = meshRoot.getAsJsonObject("vertices");
        JsonArray positions = vertices.getAsJsonObject("positions").getAsJsonArray("array");
        int count = positions.size() / 3;
        // The vertices the production rule is handed, in the frame it is handed them: the mesh's own
        // space, after the writer's scale, with no further conversion. Reading them through some other
        // frame or an extra scale is what made this fixture disagree with the shipped code once the
        // pivot was named in the mesh's frame rather than the model's own - so the numbers are taken
        // here exactly as mesh.positions() would hand them over.
        float[] mesh = new float[count * 3];
        for (int i = 0; i < count; i++) {
            mesh[i * 3] = positions.get(i * 3).getAsFloat();
            mesh[i * 3 + 1] = positions.get(i * 3 + 1).getAsFloat();
            mesh[i * 3 + 2] = positions.get(i * 3 + 2).getAsFloat();
        }
        Map<String, int[]> parts = new HashMap<>();
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
        for (int i = 0; i < bones.size(); i++) {
            JsonObject bone = bones.get(i).getAsJsonObject();
            String name = bone.get("name").getAsString();
            parentOf.put(name, bone.has("parent") ? bone.get("parent").getAsString() : "");
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
        return new Converted(mesh, parts, bindWorld, raw, scaleW, scaleH);
    }

    /**
     * The accumulated bind chain of a bone - {@code T(p) R T(-p)} for every bone from the root down -
     * in the model's own units: exactly the matrix {@link YsmPhysicsParts#pivotInMeshSpace} is handed
     * by the production path, and exactly the chain the convert-time mesh was baked with.
     */
    private static Matrix4f bindWorldOf(String name, Map<String, float[]> raw, Map<String, String> parentOf,
                                        Map<String, Matrix4f> cache, int depth) {
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

    private static String configuredConfigRoot() {
        String property = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (!property.isEmpty()) {
            return property;
        }
        String fromEnvironment = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
        return fromEnvironment == null ? "" : fromEnvironment;
    }

    private static void writeReport(String name, String content) throws IOException {
        Path out = Paths.get("build", "reports", name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, content, StandardCharsets.UTF_8);
    }
}
