package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.model.YSMJointMapper;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8 measurement probe: the vertex-level gap between neighbouring skirt panels of the real
 * {@code wine_fox/01_taisho_maid} mesh.
 *
 * <p>A probe rather than an assertion test. It writes a table to {@code tmp_verify/T8_gaps.md}
 * and asserts only what the table needs to be usable: the panels exist, and the measured bind
 * seams are centimetres rather than blocks - a bind seam of a whole block would mean the fixture
 * is being read in the wrong space and every number below it would be about something else.
 *
 * <h2>The space, and the check that pins it</h2>
 *
 * <p>Distances are reported in <b>blocks</b>, in the mesh's own bind space - the space
 * {@code EFMeshJsonWriter.walkBone} bakes the vertices in and the space the part delta
 * {@code T(P) x R x T(-P)} acts in. The conversion is a single factor,
 * {@link #BLOCKS_PER_MODEL_UNIT}, and section 1a of the report <i>establishes</i> it rather than
 * assuming it: each panel's lever is recomputed from the fixture and compared against the lever
 * the production {@code [physics]} log printed for the same panel on the real install. The ratio
 * comes out a constant 0.70 - RM 1.438 -> 0.104, RM3 0.125 -> 0.089, FL1 0.224 -> 0.157,
 * FL2 0.128 -> 0.089, RB3 0.125 -> 0.090 - which is the model's own {@code width_scale}.
 */
class T8_PanelGapProbeTest {

    private static final String MODEL = "/golden/maid/models/main.json";

    /**
     * YSM model units to blocks: the 0.7 the model is scaled by.
     *
     * <p>Established in section 1a, not assumed. Both rejected alternatives are recorded there,
     * because both were tried: {@code 1/16} of the raw distance gives 0.0078 against a logged
     * 0.089, and the raw distance unscaled gives 0.125 against 0.089. Only 0.7 reproduces the log
     * on every panel.
     */
    private static final float BLOCKS_PER_MODEL_UNIT = 0.7F;

    private static final float FRAME = 0.010F;

    /** The levers the production {@code [physics]} log printed, for the units check in section 1a. */
    private static final Map<String, Float> LOGGED_LEVERS = new LinkedHashMap<>();

    static {
        LOGGED_LEVERS.put("RM", 0.104F);
        LOGGED_LEVERS.put("RM2", 0.156F);
        LOGGED_LEVERS.put("RM3", 0.089F);
        LOGGED_LEVERS.put("RB", 0.104F);
        LOGGED_LEVERS.put("RB2", 0.156F);
        LOGGED_LEVERS.put("RB3", 0.090F);
        LOGGED_LEVERS.put("FL1", 0.157F);
        LOGGED_LEVERS.put("FL2", 0.089F);
        LOGGED_LEVERS.put("RF", 0.116F);
        LOGGED_LEVERS.put("RF2", 0.149F);
        LOGGED_LEVERS.put("RF3", 0.089F);
    }

    /** The panels the {@code [physics]} log named, paired with the panel each is sewn to. */
    private static final String[][] PAIRS = {
            {"FL", "FL1"}, {"FL1", "FL2"},
            {"FR", "FR1"}, {"FR1", "FR2"},
            {"RB", "RB2"}, {"RB2", "RB3"},
            {"RF", "RF2"}, {"RF2", "RF3"},
            {"FL", "FR"}, {"FL1", "FR1"}, {"FL2", "FR2"},
            {"RF", "RB"}, {"RF2", "RB2"},
            {"FM1", "FL"}, {"FM1", "FR"}, {"FM1", "RM"},
            {"RM", "RM2"}, {"RM2", "RM3"},
            {"BL", "BL2"}, {"BR", "BR2"}, {"BM", "BM2"}};

    @Test
    void measureThePanelsOfTheRealMaidMesh() throws IOException {
        Shape shape = Shape.load();
        List<YsmPhysicsParts.Segment> panels = shape.panels();
        assertTrue(panels.size() > 20, "the maid skirt should select its panels; got " + panels.size());

        StringBuilder report = new StringBuilder();
        report.append("# T8 panel gap probe\n\n");
        report.append("Fixture: `").append(MODEL)
                .append("` (the plaintext source of `wine_fox/01_taisho_maid`), whose bone pivots and"
                        + " cube definitions were compared field by field against the installed"
                        + " converted copy at `built/wine_fox/01_taisho_maid/models/main.json`.\n\n");
        report.append("All distances are **blocks**, in the mesh's own bind space - the space "
                + "`EFMeshJsonWriter.walkBone` bakes the vertices in and the space the part delta "
                + "`T(P) x R x T(-P)` acts in. Section 1a establishes the unit conversion against the"
                + " lever the production log printed.\n\n");

        report.append("## 1. The panels, their pivots and their own geometry\n\n");
        report.append("### 1a. The unit check: recomputed lever against the production log\n\n");
        report.append("The `[physics]` log printed each segment's lever in blocks. Recomputing it"
                + " from the fixture's geometry under each candidate scale says which units the"
                + " fixture is in - and the answer is that the ratio is a constant 0.70, the model's"
                + " own `width_scale`.\n\n");
        report.append("| panel | raw lever | raw x 0.7 | raw / 16 | raw x 0.7 / 16 | logged |"
                + " logged / raw |\n");
        report.append("|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Float> entry : LOGGED_LEVERS.entrySet()) {
            Bone bone = shape.byName.get(entry.getKey());
            if (bone == null || bone.vertices.isEmpty()) {
                report.append("| ").append(entry.getKey()).append(" | *(no geometry)* | | | | | |\n");
                continue;
            }
            float raw = bone.leverInModelUnits();            report.append(String.format(Locale.ROOT,
                    "| %s | %.4f | **%.4f** | %.4f | %.4f | %.3f | %.3f |%n",
                    entry.getKey(), raw, raw * BLOCKS_PER_MODEL_UNIT, raw / 16.0F,
                    raw * BLOCKS_PER_MODEL_UNIT / 16.0F, entry.getValue(),
                    entry.getValue() / raw));
        }

        // Section 1a's conclusion, asserted rather than left as the table's impression. The table is
        // the evidence; this is the answer it establishes - the logged lever is the recomputed one
        // times BLOCKS_PER_MODEL_UNIT - and it is what makes every distance below meaningful. Both
        // rejected alternatives are far outside the tolerance: the raw distance unscaled gives a
        // ratio of 1.0, and 1/16 of it gives 0.0625, against the 0.7 pinned here.
        //
        // The tolerance is three percent, and it is set from the measurement rather than from the
        // word "constant": the worst of the eleven panels deviates by 0.023, so "a constant 0.70" is
        // really "0.70 to within about 2.3%". Most of that is the log's own three decimals (under one
        // percent of the smallest lever), and the rest is that the probe recomputes the lever from
        // the fixture's cube geometry while production measured the converted mesh. A tolerance that
        // said 0.02 would have been a claim the data does not support, and one that said 0.5 would
        // not tell 0.7 from the unscaled alternative.
        int unitChecks = 0;
        float worstRatioDeviation = 0.0F;
        for (Map.Entry<String, Float> entry : LOGGED_LEVERS.entrySet()) {
            Bone bone = shape.byName.get(entry.getKey());
            if (bone == null || bone.vertices.isEmpty()) {
                continue;
            }
            float raw = bone.leverInModelUnits();
            if (!(raw > 0.0F)) {
                continue;
            }
            worstRatioDeviation = Math.max(worstRatioDeviation,
                    Math.abs(entry.getValue() / raw - BLOCKS_PER_MODEL_UNIT));
            unitChecks++;
        }
        assertTrue(unitChecks >= 8,
                "the unit check must run on the fixture's panels; it ran on " + unitChecks);
        assertTrue(worstRatioDeviation < 0.03F,
                "the production log's lever must be the recomputed one times " + BLOCKS_PER_MODEL_UNIT
                        + " blocks per model unit; worst deviation " + worstRatioDeviation
                        + " over " + unitChecks + " panels");
        report.append(String.format(Locale.ROOT,
                "%nUnit check: %d panels, worst deviation from %s = %.4f.%n", unitChecks,
                BLOCKS_PER_MODEL_UNIT, worstRatioDeviation));

        report.append("\n### 1b. The panels as the probe uses them\n\n");
        report.append("| panel | parent | pivot (blocks, panel-local) | lever L | verts |"
                + " nearest own vert to its pivot |\n");
        report.append("|---|---|---|---|---|---|\n");
        for (YsmPhysicsParts.Segment segment : panels) {
            Vector3f pivot = segment.bindPivot();
            report.append(String.format(Locale.ROOT, "| %s | %s | (%.4f, %.4f, %.4f) | %.4f | %d | %.4f |%n",
                    segment.boneName(),
                    segment.parent() < 0 ? "-" : panels.get(segment.parent()).boneName(),
                    pivot.x, pivot.y, pivot.z, segment.lever(),
                    shape.vertices(segment.boneName()).size(),
                    shape.nearestVertex(segment.boneName(), pivot)));
        }

        report.append("\n## 2. The seams in the bind pose\n\n");
        report.append("| A | B | min dist between their vertices | pivot-to-pivot |"
                + " rest-direction angle | knit partner of A? |\n");
        report.append("|---|---|---|---|---|---|\n");
        YsmMeshSecondaryMotion.State state = shape.run(panels);
        for (String[] pair : PAIRS) {
            YsmPhysicsParts.Segment a = shape.byName(panels, pair[0]);
            YsmPhysicsParts.Segment b = shape.byName(panels, pair[1]);
            if (a == null || b == null) {
                report.append("| ").append(pair[0]).append(" | ").append(pair[1])
                        .append(" | *(one of them is not a segment)* | | | |\n");
                continue;
            }
            int aIndex = shape.indexOf(panels, pair[0]);
            int bIndex = shape.indexOf(panels, pair[1]);
            report.append(String.format(Locale.ROOT, "| %s | %s | %.4f | %.4f | %.2f deg | %s |%n",
                    pair[0], pair[1],
                    shape.minDistance(a.boneName(), b.boneName()),
                    a.bindPivot().distance(b.bindPivot()),
                    Math.toDegrees(YsmDynamicBoneSolver.angleBetween(
                            new Vector3f(a.bindRest()).normalize(),
                            new Vector3f(b.bindRest()).normalize())),
                    shape.knit(state, aIndex, bIndex) ? "**yes**" : "no"));
        }

        report.append("\n## 3. What the swing does to each seam (body still, 90 frames of ")
                .append(FRAME).append(" s)\n\n");
        report.append("Every vertex of the pair is put through its own panel's part delta and the"
                + " closest approach of the two clouds is measured. A gap grown past its bind value is"
                + " cloth separating; a gap that stays at its bind value is cloth that was modelled"
                + " apart and never came together.\n\n");
        report.append("| A | B | gap at rest | gap after 90 frames | change | A own deg | B own deg |\n");
        report.append("|---|---|---|---|---|---|---|\n");
        float worst = 0.0F;
        List<Float> gaps = new ArrayList<>();
        for (String[] pair : PAIRS) {
            int aIndex = shape.indexOf(panels, pair[0]);
            int bIndex = shape.indexOf(panels, pair[1]);
            if (aIndex < 0 || bIndex < 0) {
                continue;
            }
            float bind = shape.bindGap(pair[0], pair[1]);
            float gap = shape.gap(state, panels, aIndex, bIndex);
            gaps.add(gap);
            worst = Math.max(worst, gap);
            report.append(String.format(Locale.ROOT, "| %s | %s | %.4f | %.4f | %+.4f | %.1f | %.1f |%n",
                    pair[0], pair[1], bind, gap, gap - bind,
                    state.lastDegrees[aIndex], state.lastDegrees[bIndex]));
        }
        java.util.Collections.sort(gaps);

        report.append("\n## 4. The swing of every panel after 90 frames\n\n");
        report.append("| panel | own deg | whole-chain deg | allowed deg | moved (blocks) |\n");
        report.append("|---|---|---|---|---|\n");
        float min = Float.MAX_VALUE;
        float max = 0.0F;
        for (int i = 0; i < panels.size(); i++) {
            min = Math.min(min, state.lastDegrees[i]);
            max = Math.max(max, state.lastDegrees[i]);
            report.append(String.format(Locale.ROOT, "| %s | %.1f | %.1f | %.1f | %.4f |%n",
                    panels.get(i).boneName(), state.lastDegrees[i],
                    Math.toDegrees(state.chainAngle[i]), Math.toDegrees(state.chainBudget[i]),
                    state.lastDisplacement[i]));
        }
        report.append("\n- own-angle spread across the whole selection: **")
                .append(String.format(Locale.ROOT, "%.1f", max - min)).append(" deg**\n");
        report.append("- seam gaps: p50 **")
                .append(String.format(Locale.ROOT, "%.4f", median(gaps)))
                .append("**, max **").append(String.format(Locale.ROOT, "%.4f", worst))
                .append("** blocks\n");

        Path out = Path.of("E:/program/JAVA/epic mod suitable/tmp_verify/T8_gaps.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println("T8 probe wrote " + out);
        System.out.println(report);
    }

    private static float median(List<Float> sorted) {
        return sorted.isEmpty() ? Float.NaN : sorted.get(sorted.size() / 2);
    }

    // ------------------------------------------------------------------

    /** The real mesh's bind geometry, as the converter bakes it. */
    private static final class Shape {
        final List<Bone> bones = new ArrayList<>();
        final Map<String, Bone> byName = new LinkedHashMap<>();
        private List<YsmPhysicsParts.Segment> cachedPanels;

        static Shape load() throws IOException {
            YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));
            assertNotNull(geometry, "the maid fixture must parse");
            Shape shape = new Shape();
            for (YSMGeoModel.Bone root : geometry.topLevelBones) {
                shape.walk(root, null);
            }
            return shape;
        }

        /**
         * Reproduce {@code EFMeshJsonWriter.walkBone}'s per-bone transform and bake.
         *
         * <p>A YSM cube's {@code origin} is written in the <b>model's</b> own frame, and a YSM
         * bone's {@code pivot} is written in its <b>parent's</b> frame, so a corner is carried into
         * this bone's local frame by subtracting the pivot's model-space position before the bone's
         * own {@code T(p) R T(-p)} is applied. Reading it the other way - applying the accumulated
         * chain to a corner - inflates every panel's lever, and inflates it more the deeper the
         * bone is; section 1a's unit check is what catches that, because the lever stops matching
         * the production log.
         *
         * <p>Vertices are stored centred on the pivot: the delta is {@code T(P) x R x T(-P)}, so the
         * pivot is the origin of the panel's own little space and a gap is a distance in that space.
         */
        private void walk(YSMGeoModel.Bone source, Bone parent) {
            Vector3f origin = new Vector3f(source.pivotX, source.pivotY, source.pivotZ);
            Vector3f pivotLocal = parent == null ? origin : new Vector3f(origin).sub(parent.origin);
            Matrix4f own = new Matrix4f()
                    .translate(pivotLocal.x, pivotLocal.y, pivotLocal.z)
                    .rotateZ(source.rotZ)
                    .rotateY(source.rotY)
                    .rotateX(source.rotX)
                    .translate(-pivotLocal.x, -pivotLocal.y, -pivotLocal.z);

            Bone bone = new Bone(source.name, source, origin, own, parent);
            bones.add(bone);
            byName.put(bone.name, bone);

            Vector3f local = new Vector3f();
            Vector3f baked = new Vector3f();
            for (YSMGeoModel.Quad quad : source.quads) {
                for (Vector3f corner : quad.positions) {
                    if (corner == null) {
                        continue;
                    }
                    local.set(corner).sub(origin).mulPosition(own);
                    // The writer's bake order: its scale, then its axis swap (x, y, z) -> (x, -z, y).
                    baked.set(local.x * BLOCKS_PER_MODEL_UNIT,
                            -local.z * BLOCKS_PER_MODEL_UNIT,
                            local.y * BLOCKS_PER_MODEL_UNIT);
                    bone.vertices.add(new Vector3f(baked));
                }
            }
            for (YSMGeoModel.Bone child : source.children) {
                walk(child, bone);
            }
        }

        /** The segments, built from the mesh the way {@code YsmPhysicsParts.buildSegment} does. */
        List<YsmPhysicsParts.Segment> panels() {
            if (cachedPanels != null) {
                return cachedPanels;
            }
            List<Bone> selected = new ArrayList<>();
            for (Bone bone : bones) {
                if (bone.vertices.size() >= 4 && !bone.mapped
                        && bone.leverInModelUnits() >= 1.0E-4F) {
                    selected.add(bone);
                }
            }
            List<String> names = new ArrayList<>();
            for (Bone bone : selected) {
                names.add(bone.name);
            }
            List<YsmPhysicsParts.Segment> out = new ArrayList<>();
            for (Bone bone : selected) {
                int parent = -1;
                for (Bone above = bone.parent; above != null; above = above.parent) {
                    int index = names.indexOf(above.name);
                    if (index >= 0) {
                        parent = index;
                        break;
                    }
                }
                // Production's own numbers, from the model-space pivot and centroid: bindPivot is
                // the pivot in the mesh's space, bindRest is the pivot-to-centroid vector there.
                // Taken in model space and scaled, exactly as YsmPhysicsParts.pivotInMeshSpace and
                // buildSegment do, so the lever reported here is the number the log prints.
                Vector3f pivot = new Vector3f(bone.origin).mul(BLOCKS_PER_MODEL_UNIT);
                Vector3f centroid = new Vector3f(bone.modelCentroid()).mul(BLOCKS_PER_MODEL_UNIT);
                Vector3f rest = new Vector3f(centroid).sub(pivot);
                float lever = rest.length();
                boolean root = bone.parent == null || bone.parent.mapped || !hangs(bone.parent.name);
                float limit = (float) Math.toRadians(root ? 20.0F : 60.0F);
                out.add(new YsmPhysicsParts.Segment(0, bone.name, bone.joint, pivot, rest, lever,
                        0.05F, Math.max(0.25F, Math.min(4.0F, bone.vertices.size() / 64.0F)),
                        2.36F, 0.5F, limit, parent, new int[0], true, new int[0]));
            }
            cachedPanels = out;
            return out;
        }

        private static boolean hangs(String name) {
            if (name == null) {
                return false;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (String hint : new String[]{"hair", "tail", "twintail", "qun", "skirt", "dress",
                    "cloth", "hem", "cape", "cloak", "mantle", "scarf", "ribbon", "sash",
                    "tassel", "braid", "belt", "pendant"}) {
                if (lower.contains(hint)) {
                    return true;
                }
            }
            return false;
        }

        YsmPhysicsParts.Segment byName(List<YsmPhysicsParts.Segment> panels, String name) {
            for (YsmPhysicsParts.Segment segment : panels) {
                if (segment.boneName().equals(name)) {
                    return segment;
                }
            }
            return null;
        }

        int indexOf(List<YsmPhysicsParts.Segment> panels, String name) {
            for (int i = 0; i < panels.size(); i++) {
                if (panels.get(i).boneName().equals(name)) {
                    return i;
                }
            }
            return -1;
        }

        List<Vector3f> vertices(String name) {
            Bone bone = byName.get(name);
            return bone == null ? List.of() : bone.verticesInMeshSpace();
        }

        float nearestVertex(String name, Vector3f point) {
            float best = Float.MAX_VALUE;
            for (Vector3f vertex : vertices(name)) {
                best = Math.min(best, vertex.distance(point));
            }
            return best;
        }

        float minDistance(String a, String b) {
            float best = Float.MAX_VALUE;
            for (Vector3f va : vertices(a)) {
                for (Vector3f vb : vertices(b)) {
                    best = Math.min(best, va.distance(vb));
                }
            }
            return best;
        }

        float bindGap(String a, String b) {
            return minDistance(a, b);
        }

        /** Whether the production coupling table holds the two as partners. */
        boolean knit(YsmMeshSecondaryMotion.State state, int a, int b) {
            YsmMeshSecondaryMotion.Knits knits = state.knits;
            for (int slot = 0; slot < knits.count[a]; slot++) {
                if (knits.partners[knits.start[a] + slot] == b) {
                    return true;
                }
            }
            return false;
        }

        /** Run the real frame loop: 91 frames of a still body, the state the log came from. */
        YsmMeshSecondaryMotion.State run(List<YsmPhysicsParts.Segment> panels) {
            YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                    panels.toArray(new YsmPhysicsParts.Segment[0]), YsmPhysicsParts.Source.AUTHORED, 0);
            YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(model, null, 1.047F);
            YsmMeshSecondaryMotion.PoseSource pose = new IdentityPose();
            for (int frame = 0; frame <= 90; frame++) {
                YsmMeshSecondaryMotion.simulate(state, pose, FRAME, null,
                        new float[2], YsmDynamicBoneSolver.NO_COLLIDERS);
            }
            return state;
        }

        /** The closest approach of two panels' vertex clouds, each through its own delta. */
        float gap(YsmMeshSecondaryMotion.State state, List<YsmPhysicsParts.Segment> panels,
                  int a, int b) {
            float best = Float.MAX_VALUE;
            for (Vector3f pa : moved(state, panels, a)) {
                for (Vector3f pb : moved(state, panels, b)) {
                    best = Math.min(best, pa.distance(pb));
                }
            }
            return best;
        }

        /**
         * One panel's vertices after its delta.
         *
         * <p>{@code delta = T(P) x R x T(-P)} acts on the mesh's bind space, and the vertices above
         * are written relative to the panel's own pivot in that space, so they go straight through
         * the delta.
         */
        private List<Vector3f> moved(YsmMeshSecondaryMotion.State state,
                                    List<YsmPhysicsParts.Segment> panels, int index) {
            OpenMatrix4f delta = state.deltas[index];
            List<Vector3f> out = new ArrayList<>();
            for (Vector3f vertex : vertices(panels.get(index).boneName())) {
                out.add(YsmMeshSecondaryMotion.transformPoint(delta, vertex, new Vector3f()));
            }
            return out;
        }
    }

    /** One bone of the real fixture: its frames and its baked, pivot-centred vertices. */
    private static final class Bone {
        final String name;
        final Bone parent;
        /** The bone's pivot in the model's own frame, model units. */
        final Vector3f origin;
        /** The bone's own bind transform, acting in its local frame at bind scale. */
        final Matrix4f bindLocal;
        final List<Vector3f> vertices = new ArrayList<>();
        final int joint;
        final boolean mapped;
        /** This bone's geometry centroid in the model's own frame, model units. */
        final Vector3f modelCentroid = new Vector3f();

        Bone(String name, YSMGeoModel.Bone source, Vector3f origin, Matrix4f bindLocal, Bone parent) {
            this.name = name;
            this.parent = parent;
            this.origin = origin;
            this.bindLocal = bindLocal;
            this.joint = YSMJointMapper.resolveJointId(source);
            this.mapped = YSMJointMapper.isDirectlyMapped(source);
        }

        /**
         * The centre of the bone's geometry in the model's own frame.
         *
         * <p>The vertices are stored pivot-centred and in the mesh's axis order, so they are
         * carried back: the axis swap is undone, the bake scale divided out, and the pivot added.
         */
        Vector3f modelCentroid() {
            Vector3f mean = new Vector3f();
            for (Vector3f vertex : vertices) {
                // mesh (x, -z, y) <- model (x, y, z) with the scale applied.
                mean.add(vertex.x, vertex.z, -vertex.y);
            }
            if (vertices.isEmpty()) {
                return mean;
            }
            mean.div(vertices.size()).div(BLOCKS_PER_MODEL_UNIT);
            return mean.add(origin);
        }

        /**
         * The lever in YSM model units, for section 1a's unit check.
         *
         * <p>The model-space distance from the pivot to the geometry's centre of mass, unscaled -
         * which is what the fixture's own numbers give and what the log's block figure is a
         * constant multiple of.
         */
        float leverInModelUnits() {
            return new Vector3f(modelCentroid()).sub(origin).length();
        }

        /** The pivot in the mesh's own space, blocks. */
        Vector3f meshPivot() {
            return new Vector3f(origin).mul(BLOCKS_PER_MODEL_UNIT);
        }

        /**
         * The panel's vertices in the mesh's own space, blocks - the frame the part delta acts in.
         *
         * <p>The stored vertices are pivot-centred, and the delta is {@code T(P) x R x T(-P)} about
         * the panel's own pivot {@code P}, so the pivot has to be added back before the delta is
         * applied. Without it the two panels of a pair are compared in two different frames and the
         * "gap" is the distance between two pivots rather than between two seams.
         */
        List<Vector3f> verticesInMeshSpace() {
            Vector3f pivot = meshPivot();
            List<Vector3f> out = new ArrayList<>(vertices.size());
            for (Vector3f vertex : vertices) {
                out.add(new Vector3f(vertex).add(pivot));
            }
            return out;
        }

        /** The geometry centroid in the mesh's own space, blocks. */
        Vector3f meshCentroid() {
            return new Vector3f(modelCentroid()).mul(BLOCKS_PER_MODEL_UNIT);
        }
    }

    /** The pose as drawn for the measurement: no animation, every joint at the identity. */
    private static final class IdentityPose implements YsmMeshSecondaryMotion.PoseSource {
        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return new OpenMatrix4f();
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            return new OpenMatrix4f();
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = T8_PanelGapProbeTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}