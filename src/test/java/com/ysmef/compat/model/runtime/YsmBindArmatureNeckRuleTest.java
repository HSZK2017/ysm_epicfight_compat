package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.model.YSMJointMapper;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The rule that decides whether the top of the Chest joint's settled geometry is the body's collar
 * or a one-sided ornament ({@link YsmBindArmature#neckPivot}), and therefore where the Head pivot's
 * horizontal position comes from.
 *
 * <p>The defect it was written for is {@code STRESSTEST_SMT_Nahobino}: the chest's settled geometry
 * there is a thin emissive strip, 2160 vertex slots between x -0.123..0.156 and z -0.096..0.092, and
 * its highest point is the top corner of that strip - 27 slots within 0.05 of y = 1.569, all of them
 * between x 0.089..0.152. Averaging them puts the Head pivot at (0.121, 1.553, -0.069), 0.138 blocks
 * off the model's own axis (the author's {@code AllHead2} pivot is (0.000, 1.540, 0.000)), and a
 * 30-degree head turn then swings the base of the skull about 0.11 blocks - the head leaving the body.
 * The height is right; only the horizontal position is, so the rule keeps the ring's y and takes x
 * and z from the body's axis, measured at the hips.
 *
 * <p>The hand-built cases pin the three gates one at a time (the ring's share of the joint's
 * geometry, the distance from the axis with its cap, and the null answers); the install-dependent
 * case pins the same rule on the model it is for, through the production geometry filter and pivot
 * arithmetic, and checks that a model whose collar is real is left exactly as it was.
 */
class YsmBindArmatureNeckRuleTest {

    // ------------------------------------------------------------------
    // hand-built chest geometries
    // ------------------------------------------------------------------

    /**
     * The ordinary case, and the one that must not move: a torso whose top ring is a collar - a
     * real share of the joint's geometry, centred on the body's axis. The ring mean is kept, the
     * axis is not consulted at all, and nothing is logged.
     */
    @Test
    @DisplayName("a collar centred on the axis keeps its own mean, silently")
    void aCollarOnTheAxisIsKept() {
        List<Vector3f> chest = torso(8, 0.0F, 0.0F);
        List<String> warnings = new ArrayList<>();
        Vector3f neck = YsmBindArmature.neckPivot(chest, new Vector3f(0.01F, 1.0F, 0.0F), "collar.ysm",
                warnings::add);

        assertEquals(ringMean(chest), neck, "the ring's own mean must be kept when it is a collar");
        assertTrue(warnings.isEmpty(), "a model whose collar is real must be silent: " + warnings);
    }

    /**
     * The ornament, and the fix: a ring that is a tiny share of the joint's geometry <b>and</b> sits
     * off the body's axis. The height stays the ring's; x and z become the axis's.
     */
    @Test
    @DisplayName("a small ring off the axis keeps its height and takes the body's axis")
    void aSmallRingOffTheAxisIsReplaced() {
        List<Vector3f> chest = torso(8, 0.15F, -0.05F);
        Vector3f hip = new Vector3f(-0.02F, 1.0F, 0.03F);
        List<String> warnings = new ArrayList<>();
        Vector3f neck = YsmBindArmature.neckPivot(chest, hip, "ornament.ysm", warnings::add);

        assertNotNull(neck);
        assertEquals(ringMean(chest).y, neck.y, 1.0E-6F, "the ornament's height is not the defect and is kept");
        assertEquals(hip.x, neck.x, 1.0E-6F, "the Head pivot's x must come from the body's axis");
        assertEquals(hip.z, neck.z, 1.0E-6F, "the Head pivot's z must come from the body's axis");
        assertEquals(1, warnings.size(), "the correction must be reported: " + warnings);
        assertTrue(warnings.get(0).contains("ornament"), "the warning must say what it saw: " + warnings);
        assertTrue(warnings.get(0).contains("ornament.ysm"), "the warning must name the model: " + warnings);
    }

    /**
     * The first gate: a ring that is small but sits on the axis is a collar seen edge-on, not an
     * ornament, and the whole point of the gate is that the common case must not move.
     */
    @Test
    @DisplayName("a small ring on the axis is left alone")
    void aSmallRingOnTheAxisIsLeftAlone() {
        List<Vector3f> chest = torso(8, 0.01F, 0.01F);
        List<String> warnings = new ArrayList<>();
        Vector3f neck = YsmBindArmature.neckPivot(chest, new Vector3f(0.0F, 1.0F, 0.0F), "tight.ysm",
                warnings::add);

        assertEquals(ringMean(chest), neck, "an off-axis distance below the gate is not evidence of an ornament");
        assertTrue(warnings.isEmpty(), "nothing to report: " + warnings);
    }

    /**
     * The cap: past a quarter of a block the body's axis is no longer a reference this rule trusts -
     * a golem whose thigh tops are a block and a half from its chest top is the measured case - so
     * the geometry's own mean is left alone. The correction is bounded by construction.
     */
    @Test
    @DisplayName("a ring further from the axis than the cap is left alone")
    void aRingBeyondTheCapIsLeftAlone() {
        List<Vector3f> chest = torso(8, 0.30F, 0.0F);
        List<String> warnings = new ArrayList<>();
        Vector3f neck = YsmBindArmature.neckPivot(chest, new Vector3f(0.0F, 1.0F, 0.0F), "golem.ysm",
                warnings::add);

        assertEquals(ringMean(chest), neck, "the axis is too far away to be believed");
        assertTrue(warnings.isEmpty(), "nothing to report: " + warnings);
    }

    /** A real collar ring: many slots at the top, so the share gate stops the rule before the axis. */
    @Test
    @DisplayName("a ring holding a real share of the geometry is never an ornament")
    void aRingHoldingARealShareIsNeverAnOrnament() {
        List<Vector3f> chest = torso(200, 0.15F, 0.0F);
        Vector3f neck = YsmBindArmature.neckPivot(chest, new Vector3f(0.0F, 1.0F, 0.0F), "collar.ysm",
                message -> { });

        assertEquals(ringMean(chest), neck, "a collar carries a real share of the joint's vertices");
    }

    /** The total answers: no geometry, no axis, no ring - never a throw, never a guess. */
    @Test
    @DisplayName("missing geometry or axis answers with the ring mean or null, never a throw")
    void missingInputIsTotal() {
        assertNull(YsmBindArmature.neckPivot(null, new Vector3f(0.0F, 1.0F, 0.0F), "none.ysm", message -> { }));
        assertNull(YsmBindArmature.neckPivot(List.of(), new Vector3f(0.0F, 1.0F, 0.0F), "none.ysm", message -> { }));
        List<Vector3f> chest = torso(8, 0.15F, 0.0F);
        assertEquals(ringMean(chest),
                YsmBindArmature.neckPivot(chest, null, "no-axis.ysm", message -> { }),
                "without an axis the ring mean is the only answer available");
        Vector3f onTheAxis = YsmBindArmature.neckPivot(chest, new Vector3f(), "no-log.ysm", null);
        assertEquals(0.0F, onTheAxis.x, 1.0E-6F,
                "a null warning sink must not stop the correction: " + onTheAxis);
        assertEquals(0.0F, onTheAxis.z, 1.0E-6F, "nor the axis it corrects to: " + onTheAxis);
        assertEquals(ringMean(chest).y, onTheAxis.y, 1.0E-6F, "and the height is still the ring's");
    }

    // ------------------------------------------------------------------
    // the shipped models
    // ------------------------------------------------------------------

    /**
     * Nahobino: the ornament is corrected, the Head pivot lands on the model's own axis, and the
     * correction is reported. The reference is the model's own authored {@code AllHead2} pivot - the
     * author's statement of where the head hangs - which the plain ring mean misses by 0.138 blocks.
     */
    @Test
    @DisplayName("on STRESSTEST_SMT_Nahobino the Head pivot leaves the ornament for the body's axis")
    void theShippedNahobinoHeadPivotIsCorrected(@TempDir Path temp) throws IOException {
        String modelId = "STRESSTEST_SMT_Nahobino.ysm";
        Fixture fixture = fixture(modelId, temp);
        assumeTrue(fixture != null, "the install has no '" + modelId + "'; set "
                + YsmModelPackage.CONFIG_ROOT_ENV + " or -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY);

        List<String> warnings = new ArrayList<>();
        YsmBindArmature.GeometryData geometry =
                YsmBindArmature.collectGeometry(fixture.input, warnings::add);
        YsmBindArmature.BindPivots pivots = YsmBindArmature.computePivots(fixture.input, geometry, warnings::add);
        Vector3f neck = pivots.byJoint().get(9);
        assertNotNull(neck, "the model must have a geometry-derived Head pivot");

        // The same geometry through the rule with no axis: what the shipped code computed before.
        Vector3f ringMean = estimateRingMean(fixture, geometry);
        assertNotNull(ringMean, "the Chest joint's settled geometry must have a top ring");
        float before = horizontal(ringMean, fixture.authoredHeadPivot);
        float after = horizontal(neck, fixture.authoredHeadPivot);
        assertTrue(before > 0.10F, "this model's ornament must be measurably off its authored axis: " + before);
        assertTrue(after < 0.05F, "the corrected Head pivot must sit on the model's own axis: "
                + after + " (was " + before + ")");
        assertTrue(warnings.stream().anyMatch(message -> message.contains("ornament")),
                "the correction must be reported in the [bind] warnings: " + warnings);
    }

    /**
     * The control: a model whose chest top ring is its collar must be untouched by the rule - the
     * Head pivot is exactly the ring mean, and no ornament warning is emitted.
     */
    @Test
    @DisplayName("on 兽耳酱x1 the collar is real and the Head pivot is untouched")
    void theShippedMaidCollarIsUntouched(@TempDir Path temp) throws IOException {
        String modelId = "兽耳酱x1.ysm";
        Fixture fixture = fixture(modelId, temp);
        assumeTrue(fixture != null, "the install has no '" + modelId + "'; set "
                + YsmModelPackage.CONFIG_ROOT_ENV + " or -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY);

        List<String> warnings = new ArrayList<>();
        YsmBindArmature.GeometryData geometry =
                YsmBindArmature.collectGeometry(fixture.input, warnings::add);
        YsmBindArmature.BindPivots pivots = YsmBindArmature.computePivots(fixture.input, geometry, warnings::add);
        Vector3f neck = pivots.byJoint().get(9);
        Vector3f ringMean = estimateRingMean(fixture, geometry);
        assertNotNull(neck);
        assertNotNull(ringMean);
        assertEquals(0.0F, neck.distance(ringMean), 1.0E-6F,
                "this model's collar is real: the Head pivot must be exactly the ring mean");
        assertTrue(horizontal(neck, fixture.authoredHeadPivot) < 0.05F,
                "and it is already on the model's own axis");
        assertFalse(warnings.stream().anyMatch(message -> message.contains("ornament")),
                "no ornament may be reported for this model: " + warnings);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * A torso: {@code ringSlots} corners at the top, clustered at {@code (ringX, ringZ)}, over the
     * rest of the shell spread symmetrically about the model's axis - the shape the rule has to read.
     */
    private static List<Vector3f> torso(int ringSlots, float ringX, float ringZ) {
        List<Vector3f> out = new ArrayList<>();
        float top = 1.60F;
        for (int i = 0; i < ringSlots; i++) {
            double angle = 2.0 * Math.PI * i / ringSlots;
            out.add(new Vector3f(ringX + (float) Math.cos(angle) * 0.01F, top,
                    ringZ + (float) Math.sin(angle) * 0.01F));
        }
        // The rest of the chest: 400 slots over four layers, symmetric about x = 0 and z = 0, so the
        // geometry's own mass centre is the axis and the ring is a tiny share of the whole.
        for (int layer = 0; layer < 4; layer++) {
            float y = 0.90F + layer * 0.20F;
            for (int i = 0; i < 100; i++) {
                double angle = 2.0 * Math.PI * i / 100.0;
                out.add(new Vector3f((float) Math.cos(angle) * 0.25F, y, (float) Math.sin(angle) * 0.15F));
            }
        }
        return out;
    }

    /** The mean of the vertices within 0.05 of the highest one: what the shipped rule computed. */
    private static Vector3f ringMean(List<Vector3f> vertices) {
        float maxY = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            maxY = Math.max(maxY, v.y);
        }
        Vector3f acc = new Vector3f();
        int n = 0;
        for (Vector3f v : vertices) {
            if (v.y >= maxY - 0.05F) {
                acc.add(v);
                n++;
            }
        }
        return n == 0 ? new Vector3f(vertices.get(0)) : acc.div(n);
    }

    /** The Chest joint's settled geometry, run through the rule with no axis: the pre-fix pivot. */
    private static Vector3f estimateRingMean(Fixture fixture, YsmBindArmature.GeometryData geometry) {
        List<Vector3f> chest = geometry.byJoint().get(8);
        if (chest == null || chest.isEmpty()) {
            return null;
        }
        return YsmBindArmature.neckPivot(chest, null, fixture.input.modelId(), null);
    }

    private static float horizontal(Vector3f a, Vector3f b) {
        return (float) Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** A converted model: the filter's input, plus the model's own authored head control. */
    private static final class Fixture {
        final YsmBindArmature.GeometryInput input;
        final Vector3f authoredHeadPivot;

        Fixture(YsmBindArmature.GeometryInput input, Vector3f authoredHeadPivot) {
            this.input = input;
            this.authoredHeadPivot = authoredHeadPivot;
        }
    }

    private static Fixture fixture(String modelId, Path temp) throws IOException {
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

        JsonObject runtime = JsonParser.parseString(Files.readString(runtimeFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
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
            mapped[i] = bone.get("mapped").getAsBoolean();
            indexOfName.put(names[i], i);
        }

        JsonObject mesh = JsonParser.parseString(Files.readString(meshFile, StandardCharsets.UTF_8)).getAsJsonObject();
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
            List<Vector3f> list = new ArrayList<>(indices.size());
            for (JsonElement index : indices) {
                int p = index.getAsInt() * 3;
                if (p + 2 < positions.length) {
                    // Blender space -> the Minecraft frame, the same swap the diag tests use.
                    list.add(new Vector3f(positions[p], positions[p + 2], -positions[p + 1]));
                }
            }
            parts.add(new YsmBindArmature.BoneGeometry(boneIdx, list));
        }
        YsmBindArmature.GeometryInput input = new YsmBindArmature.GeometryInput(modelId, names, joints, mapped,
                YSMRuntimeModel.computeDefaultHiddenBoneNames(runtime), parts);
        return new Fixture(input, authoredHeadPivot(pkg, modelId));
    }

    /** The model's own authored central head control, in model units scaled like the mesh. */
    private static Vector3f authoredHeadPivot(YsmModelPackage pkg, String modelId) {
        YSMGeoModel.Bone allHead = null;
        YSMGeoModel.Bone mHead = null;
        for (YSMGeoModel.Bone bone : pkg.geometry.bonesByName.values()) {
            String normalized = YSMJointMapper.normalize(bone.name);
            if (normalized.equals("allhead") && allHead == null) {
                allHead = bone;
            } else if (normalized.equals("mhead") && mHead == null) {
                mHead = bone;
            }
        }
        YSMGeoModel.Bone chosen = mHead != null ? mHead : allHead;
        if (chosen == null) {
            return new Vector3f();
        }
        return new Vector3f(chosen.pivotX * pkg.widthScale, chosen.pivotY * pkg.heightScale,
                chosen.pivotZ * pkg.widthScale);
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
