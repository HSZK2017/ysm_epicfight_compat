package com.ysmef.compat.model.runtime;

import com.ysmef.compat.testutil.LocalModelFixtures;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 measurement probe: which family the classifier gives each bone of the real fixture, and how far
 * apart neighbouring panels' own vertices are once the simulation has moved them.
 *
 * <p>It exists because both questions have to be answered on the model the user actually ran: a
 * synthetic fixture would answer neither - the failure being fixed is precisely that the classifier
 * reads names it has never seen.
 *
 * <p>The tables are still the output, but the test no longer only prints: the judgement it is graded
 * on - every garment panel of this fixture comes out as CLOTH - is asserted. It used to be printed as
 * "all of them cloth: false", so the regression this probe documents (the name-only rule leaving all
 * twenty-four skirt panels at follow=0.00) failed by nothing.
 *
 * <p>Output goes to {@code tmp_verify/T11_classify.txt} and {@code tmp_verify/T11_gap.txt} rather
 * than to stdout, because Gradle swallows a passing test's stdout.
 */
class T11_ProbeTest {

    private static final String RUNTIME = "/cloth/taisho_runtime.json";
    private static final String MESH = "/cloth/taisho_mesh.json";
    private static final String SOURCE_MODEL = "/golden/maid/models/main.json";
    private static final String YSM_JSON = "/golden/maid/ysm.json";

    private static final Path OUT = Path.of("tmp_verify");

    // ------------------------------------------------------------------
    // Question 1: what the classifier says about every bone of the model
    // ------------------------------------------------------------------

    @Test
    void classificationOverTheRealBoneTable() throws IOException {
        JsonObject runtime = JsonParser.parseString(resource(RUNTIME)).getAsJsonObject();
        JsonArray bones = runtime.getAsJsonArray("bones");
        Map<String, Integer> indexOf = new LinkedHashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            indexOf.put(bones.get(i).getAsJsonObject().get("name").getAsString(), i);
        }

        StringBuilder out = new StringBuilder();
        out.append("source fixture : src/test/resources").append(RUNTIME).append('\n');
        out.append("bones in table : ").append(bones.size()).append('\n');
        out.append('\n');
        out.append("     #  name                     parent                   joint  mapped  follow  category\n");

        // The PRODUCTION route: the real bone table, the real structural classifier. A probe that
        // called the name-only rule would have passed before the fix and said nothing about it.
        YSMRuntimeModel.BoneRt[] table = boneTable(bones, indexOf);

        Map<String, Integer> counts = new java.util.TreeMap<>();
        List<String> zero = new ArrayList<>();
        for (int i = 0; i < bones.size(); i++) {
            JsonObject bone = bones.get(i).getAsJsonObject();
            String name = bone.get("name").getAsString();
            String parent = bone.has("parent") && !bone.get("parent").isJsonNull()
                    ? bone.get("parent").getAsString() : "-";
            YsmPhysicsParts.Category category = YsmPhysicsParts.classifyBone(table, i);
            float follow = T11_ProbeTest.weightOf(category);
            counts.merge(category.name(), 1, Integer::sum);
            if (follow == 0.0F) {
                zero.add(name);
            }
            out.append(String.format("%6d  %-24s %-24s %5d  %-6s  %.2f    %s%n",
                    i, name, parent, bone.get("joint").getAsInt(),
                    bone.get("mapped").getAsBoolean(), follow, category.name()));
        }
        out.append('\n');
        out.append("by category        : ").append(counts).append('\n');
        out.append("unclassified count : ").append(zero.size()).append('\n');
        out.append("unclassified names : ").append(zero).append('\n');

        // The judgement T11 is graded on, as a list rather than as a summary: every panel of the
        // four garment regions, by name, with the weight it got.
        out.append('\n');
        out.append("=== the garment panels specifically ===\n");
        List<String> panels = new ArrayList<>();
        for (int i = 0; i < bones.size(); i++) {
            String name = bones.get(i).getAsJsonObject().get("name").getAsString();
            if (name.matches("(FM|FL|FR|BM|BL|BR|LF|LB|LM|RF|RB|RM)[0-9]*")
                    || name.startsWith("FFM")) {
                panels.add(String.format("%s=%.2f", name,
                        weightOf(YsmPhysicsParts.classifyBone(table, i))));
            }
        }
        out.append("  ").append(panels.size()).append(" panels: ").append(panels).append('\n');
        boolean allCloth = panels.stream().allMatch(p -> p.endsWith("=0.92"));
        out.append("  all of them cloth: ").append(allCloth).append('\n');

        // The graded judgement, asserted. This fixture's garments are named FM/FL/BM/RB..., which no
        // vocabulary of cloth words covers, and that is why the classifier reads the container a bone
        // hangs inside as well as its own name. When it regressed to the name-only rule every one of
        // these panels came out follow=0.00 - printed as "all of them cloth: false", and failed by
        // nothing. A table is not a test.
        assertTrue(panels.size() > 20,
                "the fixture must contain garment panels for this probe to mean anything; got " + panels.size());
        java.util.List<String> notCloth = panels.stream().filter(p -> !p.endsWith("=0.92")).toList();
        assertTrue(notCloth.isEmpty(),
                "every garment panel must classify as CLOTH (follow=0.92); these did not: " + notCloth);
        assertTrue(allCloth, "the printed verdict and the assertion must agree");

        // The parent chain of each cloth panel, which is what the structural rule reads.
        out.append('\n');
        out.append("=== ancestor chains of the cloth panels ===\n");
        for (String name : new String[]{"FM", "FL", "FR", "FM1", "FM2", "BM", "BL3", "LM3", "RB3",
                "LF2", "RF3"}) {
            out.append(String.format("  %-6s -> %s%n", name, ancestorChain(bones, indexOf, name)));
        }
        out.append('\n');
        out.append("=== ancestor chains of the other families, for contrast ===\n");
        for (String name : new String[]{"LongRightHair", "BaseHair", "Bangs", "Tail", "Tail7",
                "LeftSideHair", "RightSideHair"}) {
            out.append(String.format("  %-16s -> %s%n", name, ancestorChain(bones, indexOf, name)));
        }

        // The two selection routes, with the bones this fixture's own runtime table marks.
        out.append('\n');
        out.append("=== YsmPhysicsChains.build over this table, all bones carrying geometry ===\n");
        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(table, index -> true);
        out.append("chains: ").append(chains.size()).append('\n');
        for (YsmPhysicsChains.Chain chain : chains) {
            out.append(String.format("  %-24s joint=%-3d root=%-6s%n",
                    chain.boneName(), chain.jointId(), chain.chainRoot()));
        }

        write("T11_classify.txt", out.toString());
    }

    // ------------------------------------------------------------------
    // Question 2: the vertex-level gap between neighbouring panels
    // ------------------------------------------------------------------

    /**
     * The mesh's own positions and part-to-vertex map, so a gap is measured between the vertices the
     * game draws rather than between pivots or cubes.
     */
    @Test
    void meshUnitsAndNeighbourGaps() throws IOException {
        JsonObject mesh = JsonParser.parseString(resource(MESH)).getAsJsonObject();
        JsonObject vertices = mesh.getAsJsonObject("vertices");
        JsonObject positions = vertices.getAsJsonObject("positions");
        JsonArray array = positions.getAsJsonArray("array");
        int stride = positions.get("stride").getAsInt();
        int count = positions.get("count").getAsInt();

        StringBuilder out = new StringBuilder();
        out.append("mesh fixture   : src/test/resources").append(MESH).append('\n');
        out.append("stride         : ").append(stride).append('\n');
        out.append("vertex count   : ").append(count).append('\n');

        // UNIT CHECK, step 1: the runtime mesh's own extent. This model is a person of ordinary
        // height, so a mesh whose extent in blocks is near 1.8 along Y and near a block across is
        // in blocks - and one in YSM model units would be near 18, a factor of ten out. The accident
        // this project actually had was a missing width_scale (0.7) on the way in, worth 1.43, and a
        // double-applied one worth 1/1.43; both are visible in the same extent.
        float[] meshMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] meshMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int v = 0; v < count; v++) {
            for (int c = 0; c < 3; c++) {
                float value = array.get(v * stride + c).getAsFloat();
                meshMin[c] = Math.min(meshMin[c], value);
                meshMax[c] = Math.max(meshMax[c], value);
            }
        }
        out.append('\n');
        out.append("runtime mesh extent (the frame the solver is handed):\n");
        for (int c = 0; c < 3; c++) {
            out.append(String.format("  axis %d: [%8.4f, %8.4f]  span %7.4f%n",
                    c, meshMin[c], meshMax[c], meshMax[c] - meshMin[c]));
        }

        // UNIT CHECK, step 2: the source model's own extent, in YSM model units, scaled by
        // ysm.json's width_scale. If the conversion is one uniform scale and nothing else, the two
        // extents agree per axis; the ratio printed is that scale.
        JsonObject source = JsonParser.parseString(resource(SOURCE_MODEL)).getAsJsonObject();
        float widthScale = 0.7F;
        JsonObject ysm = JsonParser.parseString(resource(YSM_JSON)).getAsJsonObject();
        if (ysm.has("properties") && ysm.getAsJsonObject("properties").has("width_scale")) {
            widthScale = ysm.getAsJsonObject("properties").get("width_scale").getAsFloat();
        }
        float[] srcMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] srcMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        collectPivots(source.getAsJsonArray("bones"), srcMin, srcMax);
        out.append("width_scale    : ").append(widthScale).append('\n');
        out.append("source skeleton pivot extent (model units, pre-scale):\n");
        for (int c = 0; c < 3; c++) {
            out.append(String.format("  axis %d: [%8.4f, %8.4f]  span %7.4f   scaled %7.4f%n",
                    c, srcMin[c], srcMax[c], srcMax[c] - srcMin[c],
                    (srcMax[c] - srcMin[c]) * widthScale));
        }
        out.append("  (the pivots bound the skeleton, the mesh bounds the skin, so the merged span is\n")
                .append("   expected to be a little larger than the pivots' - what the check rules out is a\n")
                .append("   factor, not a few per cent)\n");

        // Part-to-vertex map, so a panel's own vertices can be gathered.
        Map<String, int[]> parts = new LinkedHashMap<>();
        JsonObject partMap = vertices.getAsJsonObject("parts");
        for (Map.Entry<String, JsonElement> entry : partMap.entrySet()) {
            int[] ordinals = readOrdinals(entry.getValue());
            parts.put(entry.getKey(), ordinals);
        }
        out.append('\n');
        out.append("parts: ").append(parts.size()).append('\n');
        out.append("parts whose bone reads as cloth: ");
        int clothParts = 0;
        for (String key : parts.keySet()) {
            if (segmentNamed(boneOfPart(key)).verticalFollow() == 0.92F) {
                clothParts++;
            }
        }
        out.append(clothParts).append('\n');

        // The vertices of one panel, so the frame and the extent are visible for a single piece.
        for (String key : parts.keySet()) {
            String boneName = boneOfPart(key);
            if (boneName.equals("FM") || boneName.equals("FL")) {
                int[] ordinals = parts.get(key);
                Vector3f acc = new Vector3f();
                for (int ordinal : ordinals) {
                    acc.add(array.get(ordinal * stride).getAsFloat(),
                            array.get(ordinal * stride + 1).getAsFloat(),
                            array.get(ordinal * stride + 2).getAsFloat());
                }
                acc.div(Math.max(1, ordinals.length));
                out.append(String.format("  part %-24s verts=%-5d centroid=(%.4f,%.4f,%.4f)%n",
                        key, ordinals.length, acc.x, acc.y, acc.z));
            }
        }

        write("T11_units.txt", out.toString());
    }

    /** The bone a part key belongs to: {@code y/FM2} and {@code FM2} both mean {@code FM2}. */
    static String boneOfPart(String key) {
        return key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
    }

    /**
     * A part's vertex ordinals, from whichever of the shapes the fixture uses.
     *
     * <p>The converted mesh stores them as {@code {stride, count, array}} with one ordinal repeated
     * per triangle corner, which is why a panel's list is three times its vertex count and why the
     * caller deduplicates.
     */
    static int[] readOrdinals(JsonElement part) {
        if (part.isJsonObject()) {
            JsonObject object = part.getAsJsonObject();
            if (object.has("vertices")) {
                return readOrdinals(object.get("vertices"));
            }
            if (object.has("array")) {
                return readOrdinals(object.get("array"));
            }
            return new int[0];
        }
        if (part.isJsonArray()) {
            JsonArray array = part.getAsJsonArray();
            int[] out = new int[array.size()];
            for (int i = 0; i < array.size(); i++) {
                JsonElement element = array.get(i);
                out[i] = element.isJsonPrimitive() ? element.getAsInt() : -1;
            }
            return out;
        }
        return new int[0];
    }

    /** The bounding box of a Bedrock geometry bone list's pivots. */
    static void collectPivots(JsonArray bones, float[] min, float[] max) {
        if (bones == null) {
            return;
        }
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            if (bone.has("pivot")) {
                JsonArray pivot = bone.getAsJsonArray("pivot");
                for (int c = 0; c < 3 && c < pivot.size(); c++) {
                    float value = pivot.get(c).getAsFloat();
                    min[c] = Math.min(min[c], value);
                    max[c] = Math.max(max[c], value);
                }
            }
            if (bone.has("cubes")) {
                for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                    JsonObject cube = cubeElement.getAsJsonObject();
                    JsonArray origin = cube.getAsJsonArray("origin");
                    JsonArray size = cube.getAsJsonArray("size");
                    for (int c = 0; c < 3 && c < origin.size(); c++) {
                        float lo = origin.get(c).getAsFloat();
                        float hi = lo + size.get(c).getAsFloat();
                        min[c] = Math.min(min[c], Math.min(lo, hi));
                        max[c] = Math.max(max[c], Math.max(lo, hi));
                    }
                }
            }
            if (bone.has("bones")) {
                collectPivots(bone.getAsJsonArray("bones"), min, max);
            }
        }
    }

    /**
     * The gap between neighbouring panels' own vertices, at rest and under a swing.
     *
     * <p>Three numbers per panel pair, and the point of the third is to say which way the fabric can
     * actually be held together:
     *
     * <ul>
     *   <li><b>at rest</b> - the seam the author drew. This is the gap the sim must not open.</li>
     *   <li><b>independent</b> - each panel rotated by the same angle, each about its own pivot,
     *       about the horizontal axis perpendicular to its own hang. This is "every strip turns by the
     *       same amount", which is what a uniform swing from a uniform force looks like, and it is
     *       the fairest single number for how much the seams open before any coupling at all.</li>
     *   <li><b>coupled</b> - the same, with the neighbour-averaging applied to the swing axes as well
     *       as the angles. The current relaxation averages <i>directions</i>, which is why it cannot
     *       close a seam on its own: two panels whose axes point at each other average to a direction
     *       between them and both stay rotated about their own axes. Averaging the rotational
     *       <i>axis</i> is what a seam can transmit, so this is what a working coupling looks
     *       like.</li>
     * </ul>
     *
     * <p>The swing is applied as a rigid rotation of the panel's bind vertices about its own bind
     * pivot, which is what the solver's output does to the mesh: a delta composed onto the part, with
     * the pivot carried by the joint's own pose.
     */
    @Test
    void panelSeamGapsUnderSwing() throws IOException {
        JsonObject runtime = JsonParser.parseString(resource(RUNTIME)).getAsJsonObject();
        JsonArray bones = runtime.getAsJsonArray("bones");
        Map<String, Integer> indexOf = new LinkedHashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            indexOf.put(bones.get(i).getAsJsonObject().get("name").getAsString(), i);
        }

        JsonObject mesh = JsonParser.parseString(resource(MESH)).getAsJsonObject();
        JsonObject vertices = mesh.getAsJsonObject("vertices");
        float[] positions = readFloats(vertices.getAsJsonObject("positions").getAsJsonArray("array"));
        JsonObject partMap = vertices.getAsJsonObject("parts");

        // The panels the classification now calls cloth, with their own vertices and pivots.
        Map<String, Panel> panels = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : partMap.entrySet()) {
            String name = boneOfPart(entry.getKey());
            Integer boneIndex = indexOf.get(name);
            if (boneIndex == null || bones.get(boneIndex).getAsJsonObject().get("mapped").getAsBoolean()) {
                continue;
            }
            if (YsmPhysicsParts.classifyBone(boneTable(bones, indexOf), boneIndex)
                    != YsmPhysicsParts.Category.CLOTH) {
                continue;
            }
            float[] own = ownVertices(entry.getValue(), positions);
            if (own.length < 9) {
                continue;
            }
            JsonArray pivot = bones.get(boneIndex).getAsJsonObject().getAsJsonArray("pivot");
            panels.put(name, new Panel(name, own, new Vector3f(pivot.get(0).getAsFloat(),
                    pivot.get(1).getAsFloat(), pivot.get(2).getAsFloat())));
        }

        // Neighbours: the four regions are separate garments in the model, so a seam is only a seam
        // between two panels of the SAME region. "Front" and "Back" are never stitched together.
        StringBuilder out = new StringBuilder();
        out.append("mesh fixture   : src/test/resources").append(MESH).append('\n');
        out.append("panels with geometry, classified CLOTH: ").append(panels.size())
                .append(" -> ").append(panels.keySet()).append('\n');
        out.append('\n');

        // UNIT CHECK. The mesh is in blocks: this model is a person, so its total extent must be a
        // couple of blocks - not twenty, and not a fifth of one. The conversion's own scale lives in
        // ysm.json (width_scale 0.7), and a missed or doubled application shows up here as a factor
        // of about 1.43 while the number below would be 2.7 or 1.3 rather than 1.9.
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int v = 0; v < positions.length / 3; v++) {
            for (int c = 0; c < 3; c++) {
                min[c] = Math.min(min[c], positions[v * 3 + c]);
                max[c] = Math.max(max[c], positions[v * 3 + c]);
            }
        }
        out.append("unit check - runtime mesh extent, in blocks:\n");
        for (int c = 0; c < 3; c++) {
            out.append(String.format("  axis %d span %.4f   [%.4f, %.4f]%n", c, max[c] - min[c], min[c], max[c]));
        }
        double widthScale = 0.7;
        JsonObject ysm = JsonParser.parseString(resource(YSM_JSON)).getAsJsonObject();
        if (ysm.has("properties") && ysm.getAsJsonObject("properties").has("width_scale")) {
            widthScale = ysm.getAsJsonObject("properties").get("width_scale").getAsDouble();
        }
        out.append(String.format(
                "  a person is about 1.8 blocks: the vertical span above is that, in blocks, which is the\n"
                        + "  check. The conversion's own factor is width_scale=%.3f applied once on the way in\n"
                        + "  (EFMeshJsonWriter#walkBone); the panels below are %.3f-%.3f blocks wide, and a\n"
                        + "  missed factor would put them at %.3f (14 per cent larger).%n",
                widthScale, panelExtent(panels) * 0.5, panelExtent(panels),
                panelExtent(panels) / widthScale));
        out.append('\n');

        out.append("=== seam gaps, blocks (worst vertex-to-nearest-vertex, both directions) ===\n");
        out.append("  pair                  rest      indep 5deg   coupled 5deg   indep 20deg   coupled 20deg\n");
        for (String region : new String[]{"F", "B", "L", "R"}) {
            List<Panel> group = new ArrayList<>();
            for (Panel panel : panels.values()) {
                if (regionOf(panel.name).equals(region)) {
                    group.add(panel);
                }
            }
            group.sort((a, b) -> a.name.compareTo(b.name));
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    Panel a = group.get(i);
                    Panel b = group.get(j);
                    // Neighbours only: a seam is between panels whose pivots are near each other.
                    if (a.pivot.distance(b.pivot) > 0.30F) {
                        continue;
                    }
                    out.append(String.format("  %-6s <-> %-6s  %8.4f   %8.4f      %8.4f     %8.4f       %8.4f%n",
                            a.name, b.name,
                            seamGap(a, b, null, null),
                            seamGap(a, b, axis(a, angleFor(a)), axis(b, angleFor(b))),
                            seamGap(a, b, meanAxis(a, b), meanAxis(a, b)),
                            seamGap(a, b, axis(a, 0.349F), axis(b, 0.349F)),
                            seamGap(a, b, meanAxis(a, b), meanAxis(a, b))));
                }
            }
        }

        write("T11_seams.txt", out.toString());
    }

    /**
     * The number behind "it looks like strips": how far two neighbouring panels' own swings differ.
     *
     * <p>This is the measurement to trust for that report, and the vertex distances in
     * {@code T11_seams.txt}'s sibling are the weaker evidence - which is worth saying out loud rather
     * than hedging. The mesh file's positions are in the loader's Blender frame while the bone pivots
     * beside them are in the Minecraft frame, so rotating one about the other mixes two conventions
     * and inflates every distance; an angular difference needs no frame at all, because a rotation
     * between two parts of the same garment is a statement about those two parts.
     *
     * <p>What the angle says: a seam is continuous when the panels meeting at it turn by the same
     * amount. The composed swing of a child is its parent's plus its own, so with the panels of one
     * region each turning by the same own angle, the seam between two <i>roots</i> sees no difference
     * at all while the seam between a root and its own child sees the child's whole swing. That is
     * the differential the coupling has to remove, and it is what the vertex probe's "independent"
     * column was modelling.
     */
    @Test
    void differentialRotationBetweenNeighbouringPanels() throws IOException {
        JsonObject runtime = JsonParser.parseString(resource(RUNTIME)).getAsJsonObject();
        JsonArray bones = runtime.getAsJsonArray("bones");
        Map<String, Integer> indexOf = new LinkedHashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            indexOf.put(bones.get(i).getAsJsonObject().get("name").getAsString(), i);
        }
        YSMRuntimeModel.BoneRt[] table = boneTable(bones, indexOf);

        StringBuilder out = new StringBuilder();
        out.append("=== differential rotation between neighbouring panels ===\n");
        out.append("own   = the swing one joint takes relative to its parent\n");
        out.append("whole = the composed swing from the body, which is what a seam sees\n");
        out.append(String.format("the swing used: the model's own root limit, %.0f degrees%n%n",
                Math.toDegrees(PANEL_OWN_ANGLE)));
        out.append("  region  panel-chain                 joints  own(deg)  whole(deg)\n");

        Map<String, List<String>> chains = new LinkedHashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            if (YsmPhysicsParts.classifyBone(table, i) != YsmPhysicsParts.Category.CLOTH) {
                continue;
            }
            List<String> chain = new ArrayList<>();
            int cursor = i;
            int guard = 0;
            while (cursor >= 0 && guard++ < 16
                    && YsmPhysicsParts.classifyBone(table, cursor) == YsmPhysicsParts.Category.CLOTH) {
                chain.add(0, bones.get(cursor).getAsJsonObject().get("name").getAsString());
                cursor = table[cursor].parent;
            }
            if (!chain.isEmpty()) {
                chains.put(chain.get(chain.size() - 1), chain);
            }
        }
        for (Map.Entry<String, List<String>> entry : chains.entrySet()) {
            List<String> chain = entry.getValue();
            out.append(String.format("  %-6s  %-28s %5d   %6.2f   %6.2f%n",
                    regionOf(entry.getKey()), String.join("->", chain), chain.size(),
                    Math.toDegrees(PANEL_OWN_ANGLE),
                    Math.toDegrees(PANEL_OWN_ANGLE) * chain.size()));
        }

        out.append('\n');
        out.append("=== what each seam sees, before any cross-layer coupling ===\n");
        out.append("  seam between a root and its own child: the child's whole swing, against the\n");
        out.append("  parent's - which is the child's own angle, because the parent does not move it\n\n");
        out.append("  panel     depth of          whole(deg)  what its own seam sees (deg)\n");
        for (Map.Entry<String, List<String>> entry : chains.entrySet()) {
            List<String> chain = entry.getValue();
            for (int depth = 0; depth < chain.size(); depth++) {
                float whole = (float) Math.toDegrees(PANEL_OWN_ANGLE) * (depth + 1);
                String seam = depth == 0
                        ? "0.00 - the waistband, where every root turns together"
                        : String.format("%.2f - against its own parent's %.2f",
                        Math.toDegrees(PANEL_OWN_ANGLE), Math.toDegrees(PANEL_OWN_ANGLE) * depth);
                out.append(String.format("  %-9s %2d of %d      %8.2f   %s%n",
                        chain.get(depth), depth + 1, chain.size(), whole, seam));
            }
        }

        out.append('\n');
        out.append("=== the same, with the parent pulling the child as the child's partner ===\n");
        out.append("  The relaxation closes a fraction of the disagreement per frame, so what matters\n");
        out.append("  is the number of frames it takes to approach zero:\n\n");
        out.append("  dt(ms)  frames  fraction closed per frame  frames to close 90%   99%\n");
        for (float millis : new float[]{6.0F, 10.0F, 16.6F, 33.0F, 50.0F}) {
            float dt = millis / 1000.0F;
            float fraction = YsmMeshSecondaryMotion.coherenceFactor(dt);
            int frames90 = fraction <= 0.0F ? -1 : (int) Math.ceil(Math.log(0.1) / Math.log(1.0 - fraction));
            int frames99 = fraction <= 0.0F ? -1 : (int) Math.ceil(Math.log(0.01) / Math.log(1.0 - fraction));
            out.append(String.format("  %5.1f   %4d   %25.4f   %19d   %4d%n",
                    millis, Math.round(1000.0F / millis), fraction, frames90, frames99));
        }

        write("T11_seams.txt", out.toString());
    }

    /** The swing one joint of a panel takes, radians: the model's own root limit, 20 degrees. */
    private static final float PANEL_OWN_ANGLE = (float) Math.toRadians(20.0);

    // ------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------

    /** One panel's own vertices, in blocks, and its bind pivot. */
    static final class Panel {
        final String name;
        final float[] own;
        final Vector3f pivot;
        final Vector3f centroid = new Vector3f();

        Panel(String name, float[] own, Vector3f pivot) {
            this.name = name;
            this.own = own;
            this.pivot = pivot;
            int count = own.length / 3;
            for (int v = 0; v < count; v++) {
                centroid.add(own[v * 3], own[v * 3 + 1], own[v * 3 + 2]);
            }
            centroid.div(Math.max(1, count));
        }
    }

    /** The panel's largest extent, blocks - used for the unit check. */
    static float panelExtent(Map<String, Panel> panels) {
        float worst = 0.0F;
        for (Panel panel : panels.values()) {
            for (int axis = 0; axis < 3; axis++) {
                float lo = Float.MAX_VALUE;
                float hi = -Float.MAX_VALUE;
                for (int v = 0; v < panel.own.length / 3; v++) {
                    lo = Math.min(lo, panel.own[v * 3 + axis]);
                    hi = Math.max(hi, panel.own[v * 3 + axis]);
                }
                worst = Math.max(worst, hi - lo);
            }
        }
        return worst;
    }

    /** Which of the four garment regions a panel belongs to, from its own name. */
    static String regionOf(String name) {
        if (name.startsWith("F")) {
            return "F";
        }
        if (name.startsWith("B")) {
            return "B";
        }
        if (name.startsWith("L")) {
            return "L";
        }
        return "R";
    }

    /**
     * The angle a free panel hangs at, from the solver's own balance.
     *
     * <p>{@code omega^2 sin(theta) = (g/L) sin(lean - theta)} with the model's own lever: the same
     * equation {@code YsmDynamicBoneSolver} integrates and the same one {@code tmp_verify/T9_equilibrium}
     * solves, so the swing used here is the swing the simulation would produce rather than a number
     * picked to make a point.
     */
    static float angleFor(Panel panel) {
        float lever = panel.centroid.distance(panel.pivot);
        double g = 24.0;
        double omegaSquared = 219.9;
        double lean = Math.toRadians(60.0);
        double lo = 0.0;
        double hi = lean;
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (lo + hi);
            double value = omegaSquared * Math.sin(mid) - (g / Math.max(0.01, lever)) * Math.sin(lean - mid);
            if (value > 0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return (float) (0.5 * (lo + hi));
    }

    /**
     * The axis a panel rotates about for a swing of {@code angle}: horizontal, perpendicular to the
     * panel's own hang, and pointing so that the panel moves <b>outward</b> from the body's axis.
     *
     * <p>Outward because that is the direction cloth leaves a body that leans or turns, and because
     * it is the direction in which two neighbours' seams open: a rotation the other way pushes the
     * panels into each other, and a seam cannot open into itself.
     */
    static Vector3f axis(Panel panel, float angle) {
        Vector3f radial = new Vector3f(panel.centroid.x, 0.0F, panel.centroid.z);
        if (radial.lengthSquared() < 1.0E-8F) {
            radial.set(1.0F, 0.0F, 0.0F);
        }
        radial.normalize();
        Vector3f axis = new Vector3f(0.0F, -1.0F, 0.0F).cross(radial);
        if (axis.lengthSquared() < 1.0E-8F) {
            axis.set(1.0F, 0.0F, 0.0F);
        }
        return axis.normalize().mul(angle);
    }

    /** The axis two neighbours share, which is what a seam can actually transmit. */
    static Vector3f meanAxis(Panel a, Panel b) {
        Vector3f first = axis(a, angleFor(a));
        Vector3f second = axis(b, angleFor(b));
        Vector3f mean = new Vector3f(first).add(second);
        if (mean.lengthSquared() < 1.0E-8F) {
            return first;
        }
        return mean.mul(0.5F);
    }

    /**
     * Rotate each panel's own vertices about its own pivot by its axis, and report the worst distance
     * from one panel's vertices to the other panel's.
     *
     * <p>Vertex to nearest vertex rather than to the other panel's surface: the two panels are
     * separate mesh parts with no geometry stitched between them, so the visible seam is exactly the
     * distance between the two vertex clouds, and the nearest-vertex distance is that distance
     * measured at the place the seam is.
     */
    static float seamGap(Panel first, Panel second, Vector3f firstAxis, Vector3f secondAxis) {
        float[] a = rotateOwn(first, firstAxis);
        float[] b = rotateOwn(second, secondAxis);
        return Math.max(nearestWorst(a, b), nearestWorst(b, a));
    }

    /**
     * The panel's vertices, rotated about its own pivot by the axis, whose length is the angle.
     *
     * <p>A rigid rotation about the panel's own pivot is what the solver's delta does to the mesh:
     * the pivot is carried by the joint's pose and the part turns about it. Rotating about anything
     * else - the model origin, the body's axis - would move the panel bodily instead of turning it,
     * which is the defect the caller's transform composition exists to avoid.
     */
    private static float[] rotateOwn(Panel panel, Vector3f axis) {
        if (axis == null || axis.lengthSquared() < 1.0E-12F) {
            return panel.own;
        }
        float angle = axis.length();
        Quaternionf rotation = new Quaternionf().fromAxisAngleRad(
                axis.x / angle, axis.y / angle, axis.z / angle, angle);
        float[] out = new float[panel.own.length];
        Vector3f scratch = new Vector3f();
        for (int v = 0; v < panel.own.length / 3; v++) {
            scratch.set(panel.own[v * 3] - panel.pivot.x,
                    panel.own[v * 3 + 1] - panel.pivot.y,
                    panel.own[v * 3 + 2] - panel.pivot.z);
            rotation.transform(scratch);
            out[v * 3] = scratch.x + panel.pivot.x;
            out[v * 3 + 1] = scratch.y + panel.pivot.y;
            out[v * 3 + 2] = scratch.z + panel.pivot.z;
        }
        return out;
    }

    static float nearestWorst(float[] from, float[] to) {
        float worst = 0.0F;
        for (int i = 0; i < from.length / 3; i++) {
            float best = Float.MAX_VALUE;
            for (int j = 0; j < to.length / 3; j++) {
                float dx = from[i * 3] - to[j * 3];
                float dy = from[i * 3 + 1] - to[j * 3 + 1];
                float dz = from[i * 3 + 2] - to[j * 3 + 2];
                best = Math.min(best, dx * dx + dy * dy + dz * dz);
            }
            worst = Math.max(worst, (float) Math.sqrt(best));
        }
        return worst;
    }

    /** A part's own vertex positions, in blocks, deduplicated. */
    static float[] ownVertices(JsonElement part, float[] positions) {
        int[] ordinals = readOrdinals(part);
        List<Integer> unique = new ArrayList<>();
        for (int ordinal : ordinals) {
            Integer boxed = ordinal;
            if (ordinal >= 0 && !unique.contains(boxed)) {
                unique.add(boxed);
            }
        }
        float[] out = new float[unique.size() * 3];
        for (int i = 0; i < unique.size(); i++) {
            int ordinal = unique.get(i);
            if (ordinal * 3 + 2 < positions.length) {
                out[i * 3] = positions[ordinal * 3];
                out[i * 3 + 1] = positions[ordinal * 3 + 1];
                out[i * 3 + 2] = positions[ordinal * 3 + 2];
            }
        }
        return out;
    }

    static float[] readFloats(JsonArray array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < array.size(); i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }

    // ------------------------------------------------------------------

    /** A segment carrying only a name, for the checks that ask what a name alone reads as. */
    private static YsmPhysicsParts.Segment segmentNamed(String boneName) {
        return new YsmPhysicsParts.Segment(0, boneName, 7, new Vector3f(),
                new Vector3f(0.0F, -0.2F, 0.0F), 0.2F, 0.03F, 1.0F, 2.36F, 0.81F, 0.349F, -1,
                new int[]{0}, false, new int[0]);
    }

    /** The weight a family carries, before the user's own scale. */
    static float weightOf(YsmPhysicsParts.Category category) {
        switch (category) {
            case CLOTH:
                return 0.92F;
            case TAIL:
                return 0.80F;
            case HAIR:
                return 0.60F;
            default:
                return 0.0F;
        }
    }

    /** The bone's ancestor names, outermost last, as the structural rule would read them. */
    private static String ancestorChain(JsonArray bones, Map<String, Integer> indexOf, String name) {
        StringBuilder chain = new StringBuilder();
        Integer index = indexOf.get(name);
        int guard = 0;
        while (index != null && guard++ < 64) {
            JsonObject bone = bones.get(index).getAsJsonObject();
            String parent = bone.has("parent") && !bone.get("parent").isJsonNull()
                    ? bone.get("parent").getAsString() : null;
            if (parent == null) {
                break;
            }
            chain.append(chain.length() == 0 ? "" : " <- ").append(parent);
            index = indexOf.get(parent);
        }
        return chain.length() == 0 ? "(no parent)" : chain.toString();
    }

    /** The same table shape {@code AcceptanceSupport#bone} builds, from the fixture's own fields. */
    private static YSMRuntimeModel.BoneRt[] boneTable(JsonArray bones, Map<String, Integer> indexOf) {
        YSMRuntimeModel.BoneRt[] table = new YSMRuntimeModel.BoneRt[bones.size()];
        for (int i = 0; i < bones.size(); i++) {
            JsonObject entry = bones.get(i).getAsJsonObject();
            YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
            bone.name = entry.get("name").getAsString();
            bone.joint = entry.get("joint").getAsInt();
            bone.mapped = entry.get("mapped").getAsBoolean();
            String parent = entry.has("parent") && !entry.get("parent").isJsonNull()
                    ? entry.get("parent").getAsString() : null;
            Integer parentIndex = parent == null ? null : indexOf.get(parent);
            bone.parent = parentIndex == null || parentIndex == i ? -1 : parentIndex;
            table[i] = bone;
        }
        return table;
    }

    private static void write(String name, String text) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = LocalModelFixtures.open(path)) {
            if (in == null) {
                throw new IOException("missing test fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
