package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

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
 * Experiment: does a collision correction on a <b>real</b> long-hair chain ever move a segment's
 * attachment away from the piece above it?
 *
 * <h2>The question, and why it is asked this way</h2>
 *
 * <p>The report is <i>"long hair detaches from the head when it touches the model, and comes back
 * when the contact ends"</i>. Everything the physics writes onto a mesh part is a delta
 * {@code T(bindPivot) R T(-bindPivot)} composed under the parent's delta
 * ({@link YsmMeshSecondaryMotion}), and the only thing in the solver that writes a direction
 * directly is the collision response ({@link YsmDynamicBoneSolver}). So there are exactly two ways
 * a contact could lift a strand off the head:
 *
 * <ol>
 *   <li><b>the delta composition</b> - a segment's drawn origin moves relative to its parent's when
 *       the correction fires, i.e. the correction translates the piece or turns it about a pivot
 *       that is not this segment's own (the chain root, say);</li>
 *   <li><b>the correction itself</b> - the composition is exact, so the fault is how far the
 *       correction turns the piece, or the point it turns it about.</li>
 * </ol>
 *
 * <p>This test decides between them by measurement, on a real chain read out of the converted
 * artifacts: the four segments of {@code EKU(1.0.ysm}'s {@code LeftBackHair1} (the model of the legs
 * report, and a four-link strand that hangs from the Head joint), the body collision volumes the game
 * logged for it, and the production frame loop ({@link YsmMeshSecondaryMotion#simulate}) driven by a
 * walk-like body motion and a head lean this test supplies, so the tip is genuinely pressed into the
 * body.
 *
 * <p>Per frame it records, for every segment: the drawn origin of its attachment (the mesh-space
 * point its delta takes its own pivot to), the granted swing angle, the collision correction in
 * blocks, and the two residuals that decide the branch -
 * {@code |origin_i - delta_parent(pivot_i)|} (a correction that translates, or turns the piece about
 * the wrong point, shows here) and {@code ||origin_i - origin_parent| - |pivot_i - pivot_parent||}
 * (the chain staying rigid). A third run with no colliders at all measures how deep the same motion
 * would have buried the strand had nothing corrected it, so the episode is known to be a real
 * contact rather than a graze.
 *
 * <p>Then the same measurement is applied to the alternative the report would require - every
 * segment turned about the <b>chain root</b> instead of its own pivot, built from the very rotations
 * this run produced - and the residual that leaves is printed. That is the falsification control: it
 * shows these numbers can see the defect they are looking for.
 *
 * <p>Opt-in: skipped unless {@code -Dysmef.golden.ysm_config_root} / {@code YSMEF_YSM_CONFIG_ROOT}
 * names the YSM config directory (the converted mesh and runtime are read out of the instance's
 * converted pack). Writes {@code build/reports/ysm-longhair-contact.txt}.
 */
class LongHairContactProbeTest {

    /** The model of the legs report; its {@code LeftBackHair1_*} is a four-link strand on the Head. */
    private static final String MODEL = "EKU(1.0.ysm";

    /** The chain: every bone whose name starts with this, in name order. */
    private static final String CHAIN = "LeftBackHair1_";

    /** The Head joint, which is what this model's hair hangs from (see the binding probe). */
    private static final int JOINT_HEAD = 9;

    private static final float FRAME = 1.0F / 60.0F;
    private static final float[] NO_TURN = new float[2];

    /** A second of contact, then a second with the body stopped: the reported recovery. */
    private static final int CONTACT_FRAMES = 60;
    private static final int RECOVERY_FRAMES = 60;

    /** A residual above this is a real movement, not float noise, blocks. */
    private static final float INVARIANT_TOLERANCE = 1.0E-4F;

    /**
     * The two collision volumes the game logged for this model:
     * {@code joint8@(-0.05,0.95,0.0) r=0.11, joint9@(0.01,1.19,-0.08) r=0.1}. They are in bind space
     * and follow their joint's transform every frame, as {@link YsmBodyColliders#update} places them.
     */
    private static final float[][] VOLUMES = {
            {8, -0.05F, 0.95F, 0.0F, 0.11F},
            {9, 0.01F, 1.19F, -0.08F, 0.10F}};

    @Test
    void aRealHairChainUnderRealContact() throws Exception {
        String configured = configuredConfigRoot();
        assumeTrue(!configured.isEmpty(),
                "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + "=<config/yes_steve_model> (or the "
                        + YsmModelPackage.CONFIG_ROOT_ENV + " environment variable) to run the probe");

        Chain chain = Chain.read(configured);
        System.out.println(chain.describe());

        // The premise of "the hair is attached to the head": the strand's root pivot sits on its own
        // geometry, and the whole chain hangs from the Head joint. If either failed, no contact would
        // be needed to explain a detachment.
        assertTrue(chain.rootPivotAboveOwnGeometry() < 0.05F,
                "the root's pivot must sit on its own geometry, not blocks away: it is "
                        + chain.rootPivotAboveOwnGeometry() + " blocks from its highest vertex");
        for (Segment segment : chain.segments) {
            assertTrue(segment.joint == JOINT_HEAD,
                    "the strand must hang from the Head joint; " + segment.name + " is on joint "
                            + segment.joint);
        }

        // The stimulus: a head lean - the pose that sweeps a strand hanging off the back of the head
        // through the chest - plus a sprint's drag. Which amplitude and which way is measured rather
        // than assumed: the sweep runs every combination and keeps the one that presses the strand
        // deepest into a volume *as the solver's own collision test sees it*, since that is the
        // contact a correction can respond to.
        Stimulus best = null;
        float bestDepth = 0.0F;
        float bestDrawn = 0.0F;
        List<String> sweep = new ArrayList<>();
        for (float degrees : new float[]{15.0F, 30.0F, 45.0F, 60.0F}) {
            for (int leanSign : new int[]{1, -1}) {
                for (float speed : new float[]{0.0F, 4.0F}) {
                    Stimulus candidate = new Stimulus(speed, (float) Math.toRadians(degrees) * leanSign);
                    Run candidateRun = new Run(chain, candidate, true);
                    float solverDepth = 0.0F;
                    float drawnDepth = 0.0F;
                    for (int frame = 0; frame < CONTACT_FRAMES; frame++) {
                        candidateRun.step();
                        solverDepth = Math.max(solverDepth, candidateRun.deepestSolverPenetration());
                        drawnDepth = Math.max(drawnDepth, candidateRun.deepestPenetration());
                    }
                    sweep.add(String.format(Locale.ROOT,
                            "head lean %+.0f deg, velocity z=%+.1f blocks/s: penetration %.4f blocks"
                                    + " (solver's own test) / %.4f (drawn), correction applied %.4f",
                            Math.toDegrees(candidate.lean), candidate.forwardSpeed, solverDepth,
                            drawnDepth, candidateRun.correction()));
                    if (best == null || solverDepth > bestDepth) {
                        bestDepth = solverDepth;
                        bestDrawn = drawnDepth;
                        best = candidate;
                    }
                }
            }
        }
        System.out.println("stimulus sweep:");
        for (String line : sweep) {
            System.out.println("   " + line);
        }
        assumeTrue(best != null && bestDepth > 0.01F,
                "no stimulus pressed the strand into a volume as the solver's collision test sees it"
                        + " (deepest " + bestDepth + " blocks), so this run would prove nothing about a"
                        + " contact; the deepest penetration of the drawn strand was " + bestDrawn);
        System.out.println(String.format(Locale.ROOT,
                "reported stimulus: velocity z=%+.1f blocks/s with a %+.0f degree head lean, %d frames"
                        + " of contact then %d frames of rest",
                best.forwardSpeed, Math.toDegrees(best.lean), CONTACT_FRAMES, RECOVERY_FRAMES));

        Run run = new Run(chain, best, true);
        Run shadow = new Run(chain, best, false);
        StringBuilder table = new StringBuilder();
        table.append("frame | segment | drawn origin (mesh space) | residual vs parent |"
                + " rigidity residual | granted deg | composed deg | correction blocks |"
                + " penetration: solver's own test | penetration: drawn (no colliders) |"
                + " phantom lag (tested COM vs drawn COM)\n");
        table.append("---|---|---|---|---|---|---|---|---|---|---|---\n");

        int violations = 0;
        int contactFrames = 0;
        float worstTranslate = 0.0F;
        float worstRigidity = 0.0F;
        float worstRootDrift = 0.0F;
        float deepest = 0.0F;
        float deepestSolver = 0.0F;
        float worstPhantom = 0.0F;
        float correctionTotal = 0.0F;
        int worstFrame = -1;
        int worstSegment = -1;
        for (int frame = 0; frame < CONTACT_FRAMES + RECOVERY_FRAMES; frame++) {
            run.step();
            shadow.step();
            boolean contact = run.correction() > 1.0E-4F;
            if (contact) {
                contactFrames++;
                correctionTotal += run.correction();
            }
            worstRootDrift = Math.max(worstRootDrift, run.rootDrift());
            deepestSolver = Math.max(deepestSolver, run.deepestSolverPenetration());
            for (int i = 0; i < chain.segments.size(); i++) {
                float translate = run.residualToParent(i);
                float rigidity = run.rigidityResidual(i);
                if (translate > INVARIANT_TOLERANCE || rigidity > INVARIANT_TOLERANCE) {
                    violations++;
                    if (worstFrame < 0) {
                        worstFrame = frame;
                        worstSegment = i;
                    }
                }
                if (translate > worstTranslate) {
                    worstTranslate = translate;
                }
                if (rigidity > worstRigidity) {
                    worstRigidity = rigidity;
                }
                if (i > 0) {
                    worstPhantom = Math.max(worstPhantom, run.phantomLag(i));
                }
                float penetration = shadow.penetration(i);
                if (penetration > deepest) {
                    deepest = penetration;
                }
                if (frame < CONTACT_FRAMES + 20 || contact) {
                    table.append(String.format(Locale.ROOT,
                            "%d | %s | %s | %.7f | %.7f | %.2f | %.2f | %.4f | %.4f | %.4f | %.4f%n",
                            frame, chain.segments.get(i).name, fmt(run.origin(i)), translate, rigidity,
                            run.grantedDegrees(i), run.composedDegrees(i),
                            run.correctionOf(i), run.solverPenetration(i), penetration,
                            run.phantomLag(i)));
                }
            }
        }

        // The falsification control: the last frame's own rotations, each applied about the chain
        // root's pivot instead of the segment's own - the "correct the tip about the chain root"
        // response the report would need. Its residual is what the numbers above have to be read
        // against.
        float controlTranslate = run.rootPivotControl();

        StringBuilder report = new StringBuilder();
        report.append("# long hair under contact: ").append(MODEL).append(' ').append(CHAIN)
                .append(" chain\n\n");
        report.append(chain.describe()).append("\n\n");
        report.append("## stimulus (chosen by sweep)\n\n");
        for (String line : sweep) {
            report.append("- ").append(line).append('\n');
        }
        report.append("- reported: velocity z=").append(best.forwardSpeed)
                .append(" blocks/s, head lean ").append(Math.toDegrees(best.lean))
                .append(" degrees, ").append(CONTACT_FRAMES).append(" frames of contact then ")
                .append(RECOVERY_FRAMES).append(" frames at rest\n\n");
        report.append("## what the measurement found\n\n");
        report.append("| quantity | value |\n|---|---|\n");
        report.append("| frames the correction fired on | ").append(contactFrames).append(" of ")
                .append(CONTACT_FRAMES + RECOVERY_FRAMES).append(" |\n");
        report.append("| correction applied over the episode | ")
                .append(String.format(Locale.ROOT, "%.4f", correctionTotal)).append(" blocks |\n");
        report.append("| deepest penetration of a segment's centre of mass (no colliders) | ")
                .append(String.format(Locale.ROOT, "%.4f", deepest)).append(" blocks |\n");
        report.append("| deepest penetration by the solver's OWN collision test | ")
                .append(String.format(Locale.ROOT, "%.4f", deepestSolver)).append(" blocks |\n");
        report.append("| worst lag between the tested centre of mass and the drawn one | ")
                .append(String.format(Locale.ROOT, "%.4f", worstPhantom)).append(" blocks |\n");
        report.append("| worst \u007corigin_i - delta_parent(pivot_i)\u007c | ")
                .append(String.format(Locale.ROOT, "%.7f", worstTranslate)).append(" blocks |\n");
        report.append("| worst rigidity residual | ")
                .append(String.format(Locale.ROOT, "%.7f", worstRigidity)).append(" blocks |\n");
        report.append("| worst drift of the chain root's own attachment | ")
                .append(String.format(Locale.ROOT, "%.7f", worstRootDrift)).append(" blocks |\n");
        report.append("| (frame, segment) pairs violating the invariants | ").append(violations)
                .append(" (first at frame ").append(worstFrame).append(", segment ")
                .append(worstSegment).append(") |\n");
        report.append("| the same, with every segment turned about the CHAIN ROOT | ")
                .append(String.format(Locale.ROOT, "%.4f", controlTranslate))
                .append(" blocks (falsification control) |\n\n");
        report.append("## per-frame detail\n\n").append(table);

        Path out = Paths.get("build", "reports", "ysm-longhair-contact.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println("long-hair contact probe written to " + out.toAbsolutePath());
        System.out.println(String.format(Locale.ROOT,
                "contact frames=%d correction=%.4f blocks deepest drawn=%.4f blocks deepest solver"
                        + " test=%.4f blocks worstPhantomLag=%.4f blocks worstTranslate=%.7f"
                        + " worstRigidity=%.7f rootDrift=%.7f violations=%d chainRootControl=%.4f",
                contactFrames, correctionTotal, deepest, deepestSolver, worstPhantom, worstTranslate,
                worstRigidity, worstRootDrift, violations, controlTranslate));

        // The episode has to be a real contact, or every assertion below is vacuous: the solver's own
        // collision test has to have seen the piece inside a volume, the correction has to have
        // fired, and the drawn strand has to have been inside as well.
        assertTrue(deepestSolver > 0.01F,
                "the solver's own collision test has to see the strand inside a volume for this to be"
                        + " a contact test; its deepest penetration was " + deepestSolver + " blocks");
        assertTrue(deepest > 0.01F,
                "the drawn strand has to be inside the body too; the deepest penetration of the drawn"
                        + " centre of mass was " + deepest + " blocks");
        assertTrue(contactFrames > 5 && correctionTotal > 0.02F,
                "the correction has to have fired; it fired on " + contactFrames + " frames for "
                        + correctionTotal + " blocks in all");
        // The branch: the composition is exact, so a contact cannot move a segment relative to the
        // piece above it.
        assertTrue(violations == 0,
                "a collision moved a segment's attachment relative to its parent on " + violations
                        + " (frame,segment) pairs; worst translation " + worstTranslate
                        + " blocks, worst rigidity residual " + worstRigidity + " blocks, first at"
                        + " frame " + worstFrame + " segment " + worstSegment);
        // And the control says that zero is not the measurement being blind: the same rotations
        // applied about the chain root move the attachments by two orders of magnitude more.
        assertTrue(controlTranslate > 0.05F,
                "the chain-root control must show a large residual - otherwise this measurement"
                        + " cannot see the defect it is looking for; it saw " + controlTranslate);
    }

    // ------------------------------------------------------------------
    // the model's chain, read out of the converted artifacts
    // ------------------------------------------------------------------

    /** One segment of the real chain: the numbers the physics is handed, from the artifacts. */
    private static final class Segment {
        final String name;
        final int joint;
        final Vector3f pivot;
        final Vector3f rest;
        final float lever;
        final int parent;

        Segment(String name, int joint, Vector3f pivot, Vector3f rest, float lever, int parent) {
            this.name = name;
            this.joint = joint;
            this.pivot = pivot;
            this.rest = rest;
            this.lever = lever;
            this.parent = parent;
        }
    }

    /**
     * The chain as the production classification would build it, minus the plumbing that needs a
     * live game: {@code bindPivot} through {@link YsmPhysicsParts#pivotInMeshSpace} (the production
     * function, called here), the rest direction to the centroid of the bone's own converted
     * geometry, and the lever that falls out of it. The radius, mass, spring and limit are the
     * shipped values for a hair piece - the log's own {@code m=0.56} for this model's strands,
     * 2.36 Hz, and the 60 degree per-joint limit.
     */
    private static final class Chain {
        final List<Segment> segments;
        final float geometryTop;
        final String detail;

        Chain(List<Segment> segments, float geometryTop, String detail) {
            this.segments = segments;
            this.geometryTop = geometryTop;
            this.detail = detail;
        }

        static Chain read(String configured) throws IOException {
            Path configDir = Paths.get(configured).toAbsolutePath().getParent();
            Path pack = configDir.resolve("ysm_epicfight_compat").resolve("resourcepack")
                    .resolve("assets").resolve("ysm_epicfight_compat");
            Path runtimeFile = findConverted(pack.resolve("ysm_runtime").resolve("entity"), "eku");
            Path meshFile = findConverted(pack.resolve("animmodels").resolve("entity"), "eku");
            if (runtimeFile == null || meshFile == null) {
                throw new IllegalStateException("no converted pair for " + MODEL + " under " + pack);
            }
            JsonObject runtime = JsonParser.parseString(
                    Files.readString(runtimeFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject mesh = JsonParser.parseString(
                    Files.readString(meshFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray bones = runtime.getAsJsonArray("bones");
            float scale = runtime.getAsJsonArray("scale").get(0).getAsFloat();

            Map<String, Integer> indexOfName = new HashMap<>();
            for (int i = 0; i < bones.size(); i++) {
                indexOfName.put(bones.get(i).getAsJsonObject().get("name").getAsString(), i);
            }
            // The bind chain the writer baked with, so a pivot can be named in the frame the
            // vertices are in: this is the input YsmPhysicsParts#pivotInMeshSpace is handed.
            Matrix4f[] bindWorld = new Matrix4f[bones.size()];
            for (int i = 0; i < bones.size(); i++) {
                bindWorldOf(bones, indexOfName, bindWorld, i, 0);
            }

            JsonObject verticesJson = mesh.getAsJsonObject("vertices");
            JsonArray positionsJson = verticesJson.getAsJsonObject("positions").getAsJsonArray("array");
            float[] positions = new float[positionsJson.size()];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = positionsJson.get(i).getAsFloat();
            }
            Map<String, List<Vector3f>> verticesByBone = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : verticesJson.getAsJsonObject("parts").entrySet()) {
                String partName = entry.getKey();
                if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                    continue;
                }
                String boneName = partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length());
                JsonArray indices = entry.getValue().getAsJsonObject().getAsJsonArray("array");
                List<Vector3f> list = verticesByBone.computeIfAbsent(boneName, k -> new ArrayList<>());
                for (JsonElement index : indices) {
                    int p = index.getAsInt() * 3;
                    if (p + 2 < positions.length) {
                        // The stored numbers are the file frame; the loader turns them back on load,
                        // and this is that turn - the frame the part delta is applied in.
                        list.add(new Vector3f(positions[p], positions[p + 2], -positions[p + 1]));
                    }
                }
            }

            List<String> names = new ArrayList<>();
            for (String name : verticesByBone.keySet()) {
                if (name.startsWith(CHAIN) && indexOfName.containsKey(name)) {
                    names.add(name);
                }
            }
            names.sort(String::compareTo);
            List<Segment> segments = new ArrayList<>();
            StringBuilder detail = new StringBuilder("segments read from the converted artifacts: ");
            float geometryTop = 0.0F;
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                int boneIndex = indexOfName.get(name);
                JsonObject bone = bones.get(boneIndex).getAsJsonObject();
                JsonArray pivot = bone.getAsJsonArray("pivot");
                Vector3f meshPivot = YsmPhysicsParts.pivotInMeshSpace(bindWorld[boneIndex],
                        pivot.get(0).getAsFloat(), pivot.get(1).getAsFloat(),
                        pivot.get(2).getAsFloat(), scale, scale);
                List<Vector3f> own = verticesByBone.get(name);
                Vector3f centroid = new Vector3f();
                float top = -Float.MAX_VALUE;
                for (Vector3f vertex : own) {
                    centroid.add(vertex);
                    top = Math.max(top, vertex.y);
                }
                centroid.div(own.size());
                Vector3f rest = new Vector3f(centroid).sub(meshPivot);
                float lever = rest.length();
                rest.div(lever);
                int joint = bone.get("joint").getAsInt();
                segments.add(new Segment(name, joint, new Vector3f(meshPivot), rest, lever, i - 1));
                if (i == 0) {
                    geometryTop = top;
                }
                detail.append(String.format(Locale.ROOT,
                        "%s joint=%d pivot=%s lever=%.4f verts=%d; ",
                        name, joint, fmt(meshPivot), lever, own.size()));
            }
            return new Chain(segments, geometryTop, detail.toString());
        }

        /** How far the root's pivot sits from its own geometry's highest vertex, blocks. */
        float rootPivotAboveOwnGeometry() {
            return Math.abs(segments.get(0).pivot.y - geometryTop);
        }

        String describe() {
            return detail;
        }
    }

    /** {@code YSMRuntimeModel#computeBindWorld}, the chain the mesh writer baked with. */
    private static Matrix4f bindWorldOf(JsonArray bones, Map<String, Integer> indexOfName,
                                        Matrix4f[] cache, int index, int depth) {
        if (depth > 64) {
            throw new IllegalStateException("cyclic bone hierarchy in the runtime table");
        }
        if (cache[index] != null) {
            return cache[index];
        }
        JsonObject bone = bones.get(index).getAsJsonObject();
        JsonArray pivot = bone.getAsJsonArray("pivot");
        JsonArray rot = bone.getAsJsonArray("rot");
        float px = pivot.get(0).getAsFloat();
        float py = pivot.get(1).getAsFloat();
        float pz = pivot.get(2).getAsFloat();
        Matrix4f local = new Matrix4f().translation(px, py, pz)
                .rotateZ(rot.get(2).getAsFloat())
                .rotateY(rot.get(1).getAsFloat())
                .rotateX(rot.get(0).getAsFloat())
                .translate(-px, -py, -pz);
        String parent = bone.has("parent") ? bone.get("parent").getAsString() : "";
        Integer parentIndex = parent.isEmpty() ? null : indexOfName.get(parent);
        Matrix4f world = parentIndex != null && parentIndex != index
                ? new Matrix4f(bindWorldOf(bones, indexOfName, cache, parentIndex, depth + 1)).mul(local)
                : local;
        cache[index] = world;
        return world;
    }

    // ------------------------------------------------------------------
    // driving the production frame loop
    // ------------------------------------------------------------------

    /** The body motion the test supplies: a speed along the model's z, and a head lean in radians. */
    private static final class Stimulus {
        final float forwardSpeed;
        final float lean;

        Stimulus(float forwardSpeed, float lean) {
            this.forwardSpeed = forwardSpeed;
            this.lean = lean;
        }
    }

    /**
     * The body's collision volumes, in bind space, following their joint's transform every frame -
     * the same two-step {@link YsmBodyColliders#update} does, and the same push-out
     * ({@link YsmBodyColliders#pushOutOfCapsule}).
     *
     * <p>One deliberate difference, and it is the conservative one: {@code skipFor} answers
     * {@code false} always, so a volume that happens to contain a piece's <i>rest</i> position is
     * not skipped. Production skips it - a volume the model already intersects at rest is one the
     * pose is inside, and pushing out of it would be ejection rather than collision. Disabling that
     * rule makes the correction fire more often and harder, so an invariant that survives here
     * survives production's milder version.
     */
    private static final class Body implements YsmDynamicBoneSolver.Colliders {
        private final Vector3f[] bind = new Vector3f[VOLUMES.length];
        private final Vector3f[] centres = new Vector3f[VOLUMES.length];
        private final int[] joints = new int[VOLUMES.length];
        private final float[] radii = new float[VOLUMES.length];

        Body() {
            for (int i = 0; i < VOLUMES.length; i++) {
                joints[i] = (int) VOLUMES[i][0];
                bind[i] = new Vector3f(VOLUMES[i][1], VOLUMES[i][2], VOLUMES[i][3]);
                centres[i] = new Vector3f(bind[i]);
                radii[i] = VOLUMES[i][4];
            }
        }

        /** Places the volumes the way the joint each belongs to is posed this frame. */
        void place(YsmMeshSecondaryMotion.PoseSource poses) {
            for (int i = 0; i < centres.length; i++) {
                OpenMatrix4f deformation = OpenMatrix4f.mul(poses.poseOf(joints[i]),
                        poses.toOriginOf(joints[i]), new OpenMatrix4f());
                centres[i].set(transform(deformation, bind[i]));
            }
        }

        @Override
        public int count() {
            return centres.length;
        }

        @Override
        public boolean resolve(Vector3f point, Vector3f velocity, float pointRadius, int index) {
            return YsmBodyColliders.pushOutOfCapsule(point, velocity, pointRadius,
                    centres[index].x, centres[index].y, centres[index].z,
                    centres[index].x, centres[index].y, centres[index].z, radii[index]);
        }

        @Override
        public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
            return false;
        }

        float radius(int index) {
            return radii[index];
        }

        int joint(int index) {
            return joints[index];
        }

        Vector3f bind(int index) {
            return bind[index];
        }
    }

    /**
     * A pose that leans the head: {@code deformation = pose x toOrigin} is a rotation about the Head
     * joint's own pivot, for the Head joint only. Every other joint is left at the bind pose, so the
     * only thing moving the strand is the head it hangs from - which is what a head lean is.
     */
    private static final class HeadLean implements YsmMeshSecondaryMotion.PoseSource {
        private final Vector3f pivot;
        private final OpenMatrix4f toOrigin = new OpenMatrix4f();
        private final OpenMatrix4f pose = new OpenMatrix4f();
        private final OpenMatrix4f identity = new OpenMatrix4f();
        private final OpenMatrix4f deformation = new OpenMatrix4f();

        HeadLean(Vector3f headPivot) {
            this.pivot = new Vector3f(headPivot);
            toOrigin.m30 = -pivot.x;
            toOrigin.m31 = -pivot.y;
            toOrigin.m32 = -pivot.z;
        }

        void lean(float radians) {
            // pose = T(p) x R, so pose x toOrigin = T(p) x R x T(-p): a rotation whose centre is the
            // joint's own pivot. Built through JOML and copied field for field, as the other probes
            // here do - Epic Fight's matrix carries the same column-addressed fields.
            copy(new Matrix4f().translation(pivot.x, pivot.y, pivot.z).rotateX(radians), pose);
            OpenMatrix4f.mul(pose, toOrigin, deformation);
        }

        /** The model-space point a bind-space point is drawn at, this frame. */
        Vector3f deform(Vector3f bindPoint) {
            return transform(deformation, bindPoint);
        }

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return joint == JOINT_HEAD ? toOrigin : identity;
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            return joint == JOINT_HEAD ? pose : identity;
        }
    }

    /**
     * One run of the production frame loop over the real chain: {@link YsmMeshSecondaryMotion#simulate}
     * with the colliders the caller chooses, then the per-segment numbers the report needs, read out
     * of the deltas the frame published rather than recomputed here.
     */
    private final class Run {
        private final Chain chain;
        private final Stimulus stimulus;
        private final YsmPhysicsParts.Segment[] parts;
        private final YsmMeshSecondaryMotion.State state;
        private final Body body = new Body();
        private final HeadLean pose;
        private final Vector3f velocity = new Vector3f();
        private final Vector3f[] origins;
        private final Vector3f[] parentOrigins;
        private final Vector3f[] pivots;
        private int frame;

        Run(Chain chain, Stimulus stimulus, boolean collide) {
            this.chain = chain;
            this.stimulus = stimulus;
            this.colliding = collide;
            this.parts = new YsmPhysicsParts.Segment[chain.segments.size()];
            for (int i = 0; i < parts.length; i++) {
                Segment segment = chain.segments.get(i);
                parts[i] = new YsmPhysicsParts.Segment(i, segment.name, segment.joint,
                        new Vector3f(segment.pivot), new Vector3f(segment.rest).mul(segment.lever),
                        segment.lever, 0.03F, 0.56F, 2.36F, 0.4F,
                        (float) Math.toRadians(60.0), segment.parent, new int[]{i}, true, new int[0]);
            }
            this.state = new YsmMeshSecondaryMotion.State(
                    new YsmPhysicsParts.Model(parts, YsmPhysicsParts.Source.BONE_NAMES, 0), null,
                    (float) Math.toRadians(60.0));
            this.pose = new HeadLean(chain.segments.get(0).pivot);
            this.origins = new Vector3f[parts.length];
            this.parentOrigins = new Vector3f[parts.length];
            this.pivots = new Vector3f[parts.length];
            for (int i = 0; i < parts.length; i++) {
                origins[i] = new Vector3f();
                parentOrigins[i] = new Vector3f();
                pivots[i] = new Vector3f();
            }
        }

        private final boolean colliding;

        void step() {
            // The lean ramps in over fifteen frames and holds, so the strand is *held* against
            // whatever it reaches rather than swept past it; then it ramps out over the recovery
            // window, which is the report's "it comes back when the contact ends". The sprint starts
            // with it and stops when the contact window ends.
            float held = frame < 15 ? frame / 15.0F
                    : (frame < CONTACT_FRAMES ? 1.0F
                            : Math.max(0.0F, 1.0F - (frame - CONTACT_FRAMES) / 15.0F));
            pose.lean(stimulus.lean * held);
            velocity.set(0.0F, 0.0F, stimulus.forwardSpeed * held);
            body.place(pose);
            YsmMeshSecondaryMotion.simulate(state, pose, FRAME, velocity, NO_TURN,
                    colliding ? body : YsmDynamicBoneSolver.NO_COLLIDERS);
            for (int i = 0; i < parts.length; i++) {
                origins[i].set(transform(state.deltas[i], parts[i].bindPivot()));
                pivots[i].set(pose.deform(parts[i].bindPivot()));
                int parent = parts[i].parent();
                parentOrigins[i].set(parent < 0 ? parts[i].bindPivot()
                        : transform(state.deltas[parent], parts[i].bindPivot()));
            }
            frame++;
        }

        /** How far a segment's drawn attachment is from where its parent's delta puts it. */
        float residualToParent(int index) {
            return origins[index].distance(parentOrigins[index]);
        }

        /** How far the chain is from rigid: the drawn joint-to-joint distance against the bind one. */
        float rigidityResidual(int index) {
            int parent = parts[index].parent();
            if (parent < 0) {
                return 0.0F;
            }
            return Math.abs(origins[index].distance(origins[parent])
                    - parts[index].bindPivot().distance(parts[parent].bindPivot()));
        }

        /** The chain root's own attachment: the point drawn on the scalp, which nothing may move. */
        float rootDrift() {
            return origins[0].distance(parts[0].bindPivot());
        }

        Vector3f origin(int index) {
            return origins[index];
        }

        Vector3f posedPivot(int index) {
            return pivots[index];
        }

        float correction() {
            float total = 0.0F;
            for (int i = 0; i < parts.length; i++) {
                total += state.lastContact[i];
            }
            return total;
        }

        float correctionOf(int index) {
            return state.lastContact[index];
        }

        float grantedDegrees(int index) {
            return state.lastDegrees[index];
        }

        float composedDegrees(int index) {
            return (float) Math.toDegrees(state.chainAngle[index]);
        }

        /** How deep a segment's centre of mass is inside a volume this frame, blocks. */
        float penetration(int index) {
            Vector3f modelCom = modelCom(index);
            float worst = 0.0F;
            for (int v = 0; v < body.count(); v++) {
                // A volume is placed by its own joint's deformation; only the Head joint is posed
                // here, so the head's volume follows the head and the chest's stays put.
                Vector3f centre = centreOf(v);
                float gap = body.radius(v) + parts[index].radius();
                worst = Math.max(worst, gap - centre.distance(modelCom));
            }
            return Math.max(0.0F, worst);
        }

        Vector3f modelCom(int index) {
            Vector3f com = transform(state.deltas[index],
                    new Vector3f(parts[index].bindPivot()).add(parts[index].bindRest()));
            return pose.deform(com);
        }

        /** The centre of mass exactly as the solver computes it, for a cross-check. */
        Vector3f solverCom(int index) {
            return new Vector3f(state.states[index].direction).mul(parts[index].lever())
                    .add(pivots[index]);
        }

        float solverPenetration(int index) {
            float worst = 0.0F;
            for (int v = 0; v < body.count(); v++) {
                float gap = body.radius(v) + parts[index].radius();
                worst = Math.max(worst, gap - centreOf(v).distance(solverCom(index)));
            }
            return Math.max(0.0F, worst);
        }

        float distanceToVolume(int index, int volume) {
            return centreOf(volume).distance(solverCom(index));
        }

        Vector3f centreOf(int volume) {
            return body.joint(volume) == JOINT_HEAD ? pose.deform(body.bind(volume)) : body.bind(volume);
        }

        float deepestPenetration() {
            float worst = 0.0F;
            for (int i = 0; i < parts.length; i++) {
                worst = Math.max(worst, penetration(i));
            }
            return worst;
        }

        /** The deepest penetration by the test the solver itself applies, blocks. */
        float deepestSolverPenetration() {
            float worst = 0.0F;
            for (int i = 0; i < parts.length; i++) {
                worst = Math.max(worst, solverPenetration(i));
            }
            return worst;
        }

        /**
         * How far the point the collision response tests is from the point the piece is drawn at,
         * blocks. The solver names a segment's centre of mass as {@code deformation(bindPivot) +
         * direction * lever} - the joint's pose and the segment's own swing, with no term for the
         * ancestors' deltas - while the mesh draws it as {@code deformation(delta_i(pivot + bindRest))},
         * and {@code delta_i} is composed under every ancestor. On a chain the two differ by exactly
         * the amount the piece above has already carried this one.
         */
        float phantomLag(int index) {
            return modelCom(index).distance(solverCom(index));
        }

        /**
         * The falsification control, on the last frame's own rotations: each segment turned about
         * the <b>chain root's</b> pivot instead of its own - the "correct the tip about the chain
         * root" response the report would need. Returns the worst residual against the parent that
         * this alternative leaves, so the zero above is read against a number that is not zero.
         */
        float rootPivotControl() {
            Vector3f root = parts[0].bindPivot();
            Matrix4f[] composed = new Matrix4f[parts.length];
            for (int i = 0; i < parts.length; i++) {
                Matrix4f own = i == 0 ? new Matrix4f(state.jomlDeltas[i])
                        : new Matrix4f(state.jomlDeltas[i]).mul(
                                new Matrix4f(state.jomlDeltas[parts[i].parent()]).invert());
                // The pure rotation of this segment's own delta, re-centred on the chain root.
                Matrix4f rotation = new Matrix4f(own).setTranslation(0.0F, 0.0F, 0.0F);
                Matrix4f aboutRoot = new Matrix4f().translation(root.x, root.y, root.z)
                        .mul(rotation)
                        .mul(new Matrix4f().translation(-root.x, -root.y, -root.z));
                composed[i] = i == 0 ? aboutRoot : new Matrix4f(composed[i - 1]).mul(aboutRoot);
            }
            float worst = 0.0F;
            for (int i = 1; i < parts.length; i++) {
                Vector3f viaOwn = new Vector3f(parts[i].bindPivot()).mulPosition(composed[i]);
                Vector3f viaParent = new Vector3f(parts[i].bindPivot())
                        .mulPosition(composed[parts[i].parent()]);
                worst = Math.max(worst, viaOwn.distance(viaParent));
            }
            return worst;
        }
    }

    // ------------------------------------------------------------------

    /** A point through an EF matrix, exactly as the skinning does it. */
    private static Vector3f transform(OpenMatrix4f m, Vector3f v) {
        return new Vector3f(
                v.x * m.m00 + v.y * m.m10 + v.z * m.m20 + m.m30,
                v.x * m.m01 + v.y * m.m11 + v.z * m.m21 + m.m31,
                v.x * m.m02 + v.y * m.m12 + v.z * m.m22 + m.m32);
    }

    /** A rigid motion as an EF matrix, built through JOML and copied field for field. */
    private static void copy(Matrix4f source, OpenMatrix4f out) {
        out.m00 = source.m00(); out.m01 = source.m01(); out.m02 = source.m02(); out.m03 = source.m03();
        out.m10 = source.m10(); out.m11 = source.m11(); out.m12 = source.m12(); out.m13 = source.m13();
        out.m20 = source.m20(); out.m21 = source.m21(); out.m22 = source.m22(); out.m23 = source.m23();
        out.m30 = source.m30(); out.m31 = source.m31(); out.m32 = source.m32(); out.m33 = source.m33();
    }

    private static String fmt(Vector3f v) {
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

    private static String configuredConfigRoot() {
        String property = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (!property.isEmpty()) {
            return property;
        }
        String fromEnvironment = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
        return fromEnvironment == null ? "" : fromEnvironment;
    }

    /** The converted file of a model under a pack directory, found by a case-insensitive needle. */
    private static Path findConverted(Path dir, String needle) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .contains(needle.toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElse(null);
        }
    }
}
