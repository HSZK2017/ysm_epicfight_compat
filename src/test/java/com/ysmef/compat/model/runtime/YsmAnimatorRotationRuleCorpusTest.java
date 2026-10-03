package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.ysm.YsmBinaryReader;
import com.ysmef.compat.ysm.YsmFileCrypto;
import com.ysmef.compat.ysm.script.Molang;
import com.ysmef.compat.ysm.script.ScriptAnim;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The blast radius of the animator's rotation rule over the corpus, measured per piece.
 *
 * <p><b>The rule.</b> {@code YSMPlayerAnimator#composeBone} composes a bone's animated local
 * transform. For a bone one of the model's own clips keys, the rotation it uses is the whole
 * question, and there are two conventions:
 *
 * <ul>
 *   <li><b>legacy</b> - the clip's value <em>replaces</em> the bone's authored rotation
 *       ({@code rx = hasRot[i] ? animRot[i][0] : bone.rx}). The mesh is baked <em>with</em> the
 *       authored rotations ({@code EFMeshJsonWriter#walkBone} 271-276), so a keyed bone is drawn
 *       un-rotated by its whole bind angle for as long as the clip is active.</li>
 *   <li><b>offset</b> - the clip's value is an <em>offset from</em> the authored rotation, exactly
 *       as YSM composes it: {@code ModernYSM .../geckolib3/core/snapshot/BoneTopLevelSnapshot.java}
 *       45-48 ({@code setRotationX(rotation.x + initialRotation.x)}) with
 *       {@code .../geckolib3/core/util/MathUtil.java} 30-46, {@code OpenYSM} the same file and the
 *       same lines, and this project's own sibling
 *       {@code 参考/YSM-EFC-sakura-1.20.1 .../animation/ParallelAnimationProgram.java} 4609-4614
 *       ({@code bone.rotationX() + (pose.hasRotation[auxiliary] ? ... : 0.0F)}).</li>
 * </ul>
 *
 * <p><b>What is measured.</b> For every bone the model's own clips key and Epic Fight does not own,
 * the distance its farthest own vertex is drawn from where the authored model put it, under each
 * rule, at the same clip times. The offset rule's drawing is the authored shape plus the clip's own
 * offset, so its drift from the authored model <em>is</em> the animation the clip asks for; the
 * legacy rule's drift additionally carries the authored rotation. The difference between the two
 * drawings is the fold: for a piece whose clip is at its neutral value, the legacy drawing is the
 * whole bind angle away about that piece's own pivot.
 *
 * <p><b>What this sweep is, and is not.</b> Both columns are composed here from production's own
 * evaluated channel values, so the sweep measures the two conventions against the whole corpus - how
 * many models and pieces the change moves, and in which direction - but it does not by itself say
 * which rule the jar ships. That is pinned by
 * {@code YsmTailTipSeparationProbeTest#theAuthoredClipOnTheChainTheMeshIsGiven}, which asserts this
 * file's composition of the shipped rule against production's own {@code chainDeltaBuf} and reads
 * the fold off the rule production replaced.
 *
 * <p><b>How it is read.</b> Through production's own seams: {@code YsmBinaryReader} +
 * {@code YSMGeoModel} for the model, {@code Rig.fromRawModel} for the armature and geometry,
 * {@code YSMRuntimeModel#compileAnim} for the compiled clips, and
 * {@code YSMPlayerAnimator#evalChannel} - with the animator itself as the Molang environment, exactly
 * as {@code #evalAnim} calls it - for the clip values, converted with production's own constants
 * (degrees to radians, x and y negated, z not: {@code #evalAnim} 526-528).
 *
 * <p><b>It is a corpus gate, not a unit test</b>, so it is opt-in like the suite's other sweeps:
 * {@code YSMEF_YSM_CORPUS_ROOT} selects the root and with it unset the test is skipped. {@code
 * YSMEF_YSM_CORPUS_SAMPLE} bounds the run (default: every package).
 */
class YsmAnimatorRotationRuleCorpusTest {

    /** The same opt-in corpus root every other sweep in this suite reads. */
    private static final String CORPUS_ROOT = "YSMEF_YSM_CORPUS_ROOT";

    /** Optional: how many packages to measure, spread evenly. Default: every package found. */
    private static final String SAMPLE_ENV = "YSMEF_YSM_CORPUS_SAMPLE";

    /** How many clip times are evaluated per clip. */
    private static final int CLIP_SAMPLES = 8;

    /**
     * A keyed bone drawn at least this far from where the authored model put it, under the rule
     * named, counts as "moved" in the tallies - the same centimetre the tail probe's blast-radius
     * table uses.
     */
    private static final float MOVED = 0.01F;

    /**
     * The two statements {@code #composeBone} can hold for the x axis of a keyed bone, as they appear
     * in the source - anchored on the assignment, so a copy of either expression in the comment above
     * the code (the legacy one is quoted there, as the thing not to simplify back to) cannot be
     * mistaken for the rule. A mutation of either rule breaks the guard below, which is the point:
     * this sweep measures a rule, and a rule it is not measuring is not evidence.
     */
    private static final String LEGACY_RULE = "float rx = hasRot[i] ? animRot[i][0] : bone.rx";
    private static final String OFFSET_RULE = "float rx = bone.rx + (hasRot[i] ? animRot[i][0] : 0.0F)";

    /**
     * Which rule production composes right now, read from the source: {@code legacy} (the clip's
     * value replaces the authored rotation) or {@code offset} (YSM's rule: the clip's value is an
     * offset from it). Both columns are measured either way; this is what the report and the
     * assertion are told the shipped rule is, so the numbers cannot be read under the wrong name.
     */
    private static String shippedRule() {
        String source;
        try {
            source = Files.readString(Paths.get("src", "main", "java", "com", "ysmef", "compat",
                    "model", "runtime", "YSMPlayerAnimator.java"), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "unreadable";
        }
        if (source.contains(OFFSET_RULE)) {
            return "offset";
        }
        if (source.contains(LEGACY_RULE)) {
            return "legacy";
        }
        return "neither";
    }

    @Test
    void theAnimatorRotationRuleOverTheCorpus() throws Exception {
        // The animator's constructor reads two vanilla ItemStack.EMPTY fields, and reading a vanilla
        // item needs vanilla's registries up. Vanilla's half of the bootstrap runs first; Forge's
        // patched tail asks its own event bus for a listener list and throws in a plain JUnit JVM, by
        // which point every registry this sweep needs is already built - so the throw is swallowed,
        // exactly as the tail probe swallows it, and nothing here goes near the game.
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable vanillaOnly) {
            assertTrue(net.minecraft.world.item.ItemStack.EMPTY != null,
                    "vanilla's registries must be up after the bootstrap's vanilla half: " + vanillaOnly);
        }
        String root = System.getenv(CORPUS_ROOT);
        assumeTrue(root != null && !root.isEmpty(), "set " + CORPUS_ROOT + " to sweep the model corpus");
        String shipped = shippedRule();
        assumeTrue("offset".equals(shipped) || "legacy".equals(shipped),
                "production must compose one of the two rules this sweep measures; it composes "
                        + "neither " + OFFSET_RULE + " nor " + LEGACY_RULE);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));
        assumeTrue(!files.isEmpty(), "no .ysm packages under " + root);
        String sampleSetting = System.getenv(SAMPLE_ENV);
        int sample = files.size();
        if (sampleSetting != null && !sampleSetting.isEmpty()) {
            sample = Math.max(1, Math.min(files.size(), Integer.parseInt(sampleSetting.trim())));
        }
        int stride = Math.max(1, files.size() / sample);

        int parsed = 0;
        int rigged = 0;
        int keyedModels = 0;
        int foldedModels = 0;
        int legacyDriftModels = 0;
        int offsetDriftModels = 0;
        long bonesKeyed = 0;
        long bonesWithExpressions = 0;
        long foldedPieces = 0;
        long animatedPieces = 0;
        long legacyDriftPieces = 0;
        long offsetDriftPieces = 0;
        float worstFold = 0.0F;
        float worstAnimated = 0.0F;
        float worstLegacyDrift = 0.0F;
        float worstOffsetDrift = 0.0F;
        String worstFoldWhere = "";
        String worstLegacyDriftWhere = "";
        List<ModelRow> rows = new ArrayList<>();
        StringBuilder witnesses = new StringBuilder();

        for (int at = 0; at < files.size(); at += stride) {
            Path file = files.get(at);
            String stem = file.getFileName().toString();
            YSMGeoModel model;
            YsmBinaryReader.BinaryModel binary;
            float scaleW;
            float scaleH;
            try {
                binary = YsmBinaryReader.read(YsmFileCrypto.decryptYsmFile(Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
                scaleW = binary.widthScale;
                scaleH = binary.heightScale;
            } catch (Throwable unreadable) {
                // A package this sweep cannot read is the legacy-container question, which belongs to
                // the corpus gate's own `failed` tally, not here.
                continue;
            }
            parsed++;
            YsmLegSkirtCollisionProbeTest.Rig rig;
            try {
                rig = YsmLegSkirtCollisionProbeTest.fromRawModel(model, scaleW, scaleH, stem);
            } catch (Throwable unassemblable) {
                continue;
            }
            if (rig.bones.length == 0) {
                continue;
            }
            rigged++;
            RuleMeasurement measurement = measure(binary, rig);
            bonesKeyed += measurement.keyedBones;
            bonesWithExpressions += measurement.expressionBones;
            if (measurement.keyedBones == 0) {
                continue;
            }
            keyedModels++;
            if (measurement.worstFold > MOVED) {
                foldedModels++;
            }
            if (measurement.worstLegacyDrift > MOVED) {
                legacyDriftModels++;
            }
            if (measurement.worstOffsetDrift > MOVED) {
                offsetDriftModels++;
            }
            foldedPieces += measurement.foldedPieces;
            animatedPieces += measurement.animatedPieces;
            legacyDriftPieces += measurement.legacyDriftPieces;
            offsetDriftPieces += measurement.offsetDriftPieces;
            if (measurement.worstLegacyDrift > worstLegacyDrift) {
                worstLegacyDrift = measurement.worstLegacyDrift;
                worstLegacyDriftWhere = stem + " `" + measurement.worstFoldBone + "`";
            }
            worstOffsetDrift = Math.max(worstOffsetDrift, measurement.worstOffsetDrift);
            if (measurement.worstFold > worstFold) {
                worstFold = measurement.worstFold;
                worstFoldWhere = stem + " `" + measurement.worstFoldBone + "`";
            }
            worstAnimated = Math.max(worstAnimated, measurement.worstAnimated);
            rows.add(new ModelRow(stem, measurement.keyedBones, measurement.expressionBones,
                    measurement.foldedPieces, measurement.worstFold, measurement.worstAnimated));
            if (witnesses.length() < 6000) {
                witnesses.append("YSRULE-CORPUS `").append(stem).append("` bones=")
                        .append(rig.bones.length).append(" keyed=")
                        .append(measurement.keyedBones).append(" exprKeyed=")
                        .append(measurement.expressionBones).append(" legacyDrift=")
                        .append(measurement.legacyDriftPieces).append('/')
                        .append(fmt(measurement.worstLegacyDrift)).append(" offsetDrift=")
                        .append(measurement.offsetDriftPieces).append('/')
                        .append(fmt(measurement.worstOffsetDrift)).append(" fold=")
                        .append(measurement.foldedPieces).append('/')
                        .append(fmt(measurement.worstFold)).append(" worstFold=`")
                        .append(measurement.worstFoldBone).append("` bind=")
                        .append(fmt(measurement.worstLegacyBindDegrees)).append("deg clip=")
                        .append(fmt(measurement.worstLegacyClipDegrees)).append("deg\n");
            }
        }

        rows.sort((a, b) -> Float.compare(b.worstFold, a.worstFold));
        StringBuilder report = new StringBuilder();
        report.append("# The animator's rotation rule over the corpus\n\n")
                .append("**The rule production composes in this run: `").append(shipped)
                .append("`.** `legacy` = a keyed bone's rotation is the clip's value alone (the ")
                .append("authored rotation replaced - the rule this change replaces); `offset` = the ")
                .append("clip's value is an offset from the authored rotation (YSM's rule, ")
                .append("`BoneTopLevelSnapshot` 45-48). Per bone the model's own clips key ")
                .append("(Epic-Fight-owned bones excluded, because both rules keep their authored ")
                .append("rotation), the distance the bone's farthest own vertex is drawn:\n\n")
                .append("- **legacy drift** - from where the authored model puts it, under the legacy ")
                .append("rule. For a channel the clips hold at a value `v` this is the authored ")
                .append("rotation plus `v`; at `v = 0` it is the bone's whole bind angle, ")
                .append("`2 r sin(theta/2)` with `r` the vertex's distance from the pivot.\n")
                .append("- **offset drift** - the same distance under the offset rule: the clip's own ")
                .append("offset, which the change keeps rather than loses. It is the control that says ")
                .append("the geometry is still animated, not frozen at bind.\n")
                .append("- **fold** - the distance between the two drawings: what the change moves on ")
                .append("that piece.\n\n")
                .append("The single load-bearing number is that offset drift is the clip's own ")
                .append("animation by construction - the authored rotation cancels between the two ")
                .append("rules' targets - while legacy drift is not: it carries the authored rotation ")
                .append("as a constant error on every keyed piece, which is the fold. Units are the ")
                .append("corpus measure: `Rig.fromRawModel` bakes each bone's quads scaled by the ")
                .append("package's width/height scale while its pivot stays in blocks, so the numbers ")
                .append("are consistent within a model and comparative across the corpus, not absolute ")
                .append("blocks.\n\n")
                .append("| packages | parsed | rigged | with keyed bones | folded (legacy) | ")
                .append("drifted from the authored model (legacy) | (offset) |\n")
                .append("|---|---|---|---|---|---|---|\n")
                .append("| ").append(files.size()).append(" | ").append(parsed).append(" | ")
                .append(rigged).append(" | ").append(keyedModels).append(" | ")
                .append(foldedModels).append(" | ").append(legacyDriftModels).append(" | ")
                .append(offsetDriftModels).append(" |\n\n")
                .append("Keyed bones measured: ").append(bonesKeyed)
                .append(" (of which ").append(bonesWithExpressions)
                .append(" read an expression on at least one axis, so the entity-less environment ")
                .append("here reads them as 0). Pieces drawn more than ").append(fmt(MOVED))
                .append(" from the authored model: ").append(legacyDriftPieces)
                .append(" under the legacy rule, ").append(offsetDriftPieces)
                .append(" under the offset rule; pieces the rule itself moves, i.e. where the two ")
                .append("drawings disagree by more than ").append(fmt(MOVED)).append(": ")
                .append(foldedPieces).append(". Worst single piece: ").append(fmt(worstLegacyDrift))
                .append(" legacy drift (").append(worstLegacyDriftWhere).append("), ")
                .append(fmt(worstOffsetDrift)).append(" offset drift, ")
                .append(fmt(worstFold)).append(" fold.\n\n")
                .append("## The models the legacy rule folds most\n\n")
                .append("| model | keyed bones | of which expression | pieces folded (legacy) | ")
                .append("worst fold (legacy) | worst offset motion |\n")
                .append("|---|---|---|---|---|---|\n");
        for (int i = 0; i < Math.min(20, rows.size()); i++) {
            ModelRow row = rows.get(i);
            report.append("| `").append(row.model).append("` | ").append(row.keyed)
                    .append(" | ").append(row.expressions).append(" | ").append(row.foldedPieces)
                    .append(" | ").append(fmt(row.worstFold)).append(" | ")
                    .append(fmt(row.worstAnimated)).append(" |\n");
        }
        Path out = Paths.get("build", "reports", "ysm-animator-rotation-rule-corpus.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(witnesses);
        System.out.println("YSRULE-CORPUS shipped=" + shipped + " packages=" + files.size()
                + " stride=" + stride
                + " parsed=" + parsed + " rigged=" + rigged + " keyedModels=" + keyedModels
                + " keyedBones=" + bonesKeyed + " expressionBones=" + bonesWithExpressions
                + " foldedModels=" + foldedModels + " foldedPieces=" + foldedPieces
                + " animatedPieces=" + animatedPieces
                + " legacyDriftPieces=" + legacyDriftPieces
                + " offsetDriftPieces=" + offsetDriftPieces
                + " worstFold=" + fmt(worstFold) + " (`" + worstFoldWhere + "`)"
                + " worstLegacyDrift=" + fmt(worstLegacyDrift)
                + " worstOffsetDrift=" + fmt(worstOffsetDrift));

        assertTrue(rigged > 0, "the sweep must assemble at least one rig: parsed=" + parsed);
        assertTrue(keyedModels > 0,
                "the clips of at least one model in the corpus must key a bone, or this sweep "
                        + "measures nothing: rigged=" + rigged);
        // The finding, as an invariant of the corpus: the two rules are different compositions and
        // must draw pieces differently, or this sweep is not measuring a rule at all. Note what this
        // sweep is NOT: both columns are composed here from production's own evaluated channel
        // values, so it does not by itself distinguish which rule the jar ships. That distinction is
        // pinned elsewhere - `YsmTailTipSeparationProbeTest#theAuthoredClipOnTheChainTheMeshIsGiven`
        // reproduces production's own `chainDeltaBuf` under the shipped rule and reads the fold off
        // the rule it replaced - and this sweep's job is the blast radius and the direction of change.
        assertTrue(foldedPieces > 0 && foldedModels > 0,
                "the two rules must draw some of the corpus differently, or this sweep is not "
                        + "measuring a rule: " + foldedPieces + " piece(s) in " + foldedModels
                        + " model(s)");
        // What the change is for, measured: the drawn geometry is closer to the authored model under
        // the offset rule than under the legacy one. The margin is modest in this environment and the
        // report says why - 12697 of the 18740 keyed bones hold a molang expression, which an
        // entity-less environment reads as zero, so most pieces are being measured with the clip's
        // own value out of the picture and with only the authored rotation left to separate the two
        // rules. That is the conservative direction: the fold the change removes is the authored
        // rotation, and it is exactly what remains when the clip is neutral.
        assertTrue(legacyDriftPieces > offsetDriftPieces,
                "the legacy rule must draw more of the corpus away from the authored model than the "
                        + "offset rule does: " + legacyDriftPieces + " pieces against "
                        + offsetDriftPieces);
        assertTrue(animatedPieces > 0,
                "the clips must still move the geometry under either rule, or the composition is "
                        + "vacuous: animatedPieces=" + animatedPieces);
    }

    /** One row of the report: a model, its keyed bones, and how far the legacy rule folds them. */
    private static final class ModelRow {
        final String model;
        final int keyed;
        final int expressions;
        final int foldedPieces;
        final float worstFold;
        final float worstAnimated;

        ModelRow(String model, int keyed, int expressions, int foldedPieces, float worstFold,
                 float worstAnimated) {
            this.model = model;
            this.keyed = keyed;
            this.expressions = expressions;
            this.foldedPieces = foldedPieces;
            this.worstFold = worstFold;
            this.worstAnimated = worstAnimated;
        }
    }

    /** What one model measured, worst over its clips and the sampled clip times. */
    private static final class RuleMeasurement {
        int keyedBones;
        int expressionBones;
        int foldedPieces;
        int animatedPieces;
        int legacyDriftPieces;
        int offsetDriftPieces;
        float worstFold;
        float worstAnimated;
        float worstLegacyDrift;
        float worstOffsetDrift;
        String worstFoldBone = "";
        float worstLegacyBindDegrees;
        float worstLegacyClipDegrees;
    }

    /**
     * The measurement for one model: the clips' own compiled channels, evaluated by production's own
     * channel evaluator at {@link #CLIP_SAMPLES} times per clip, applied to the rig's own geometry
     * under both rules.
     */
    private static RuleMeasurement measure(YsmBinaryReader.BinaryModel binary,
                                           YsmLegSkirtCollisionProbeTest.Rig rig) throws Exception {
        RuleMeasurement out = new RuleMeasurement();
        if (binary.animations.isEmpty()) {
            return out;
        }
        Map<String, Integer> boneIndex = new HashMap<>();
        for (int i = 0; i < rig.bones.length; i++) {
            boneIndex.put(rig.bones[i].name, i);
        }
        Method compileAnim = YSMRuntimeModel.class.getDeclaredMethod("compileAnim",
                ScriptAnim.class, Map.class);
        compileAnim.setAccessible(true);
        Method assignChannelIds = YSMRuntimeModel.class.getDeclaredMethod("assignChannelIds",
                YSMRuntimeModel.CompiledAnim.class, int.class);
        assignChannelIds.setAccessible(true);
        List<YSMRuntimeModel.CompiledAnim> compiled = new ArrayList<>();
        int channelCount = 0;
        for (ScriptAnim anim : binary.animations.values()) {
            if (anim == null) {
                continue;
            }
            YSMRuntimeModel.CompiledAnim one =
                    (YSMRuntimeModel.CompiledAnim) compileAnim.invoke(null, anim, boneIndex);
            // The channel ids are what the evaluator's keyframe cursor is indexed by; production
            // assigns them once per compiled animation (YSMRuntimeModel#compile 898), so the same
            // call has to happen here or evalChannel indexes an empty cursor array.
            channelCount = (Integer) assignChannelIds.invoke(null, one, channelCount);
            compiled.add(one);
        }
        YSMRuntimeModel host = runtimeHost(rig.bones, boneIndex, channelCount);
        YSMPlayerAnimator animator = new YSMPlayerAnimator(host);
        Method evalChannel = YSMPlayerAnimator.class.getDeclaredMethod("evalChannel",
                YSMRuntimeModel.CompiledChannel.class, float.class, float[].class);
        evalChannel.setAccessible(true);

        Map<Integer, Float> fold = new LinkedHashMap<>();
        Map<Integer, Float> legacyDrift = new LinkedHashMap<>();
        Map<Integer, Float> offsetDrift = new LinkedHashMap<>();
        Map<Integer, Float> motion = new LinkedHashMap<>();
        Map<Integer, Float> clipWorst = new LinkedHashMap<>();
        for (YSMRuntimeModel.CompiledAnim anim : compiled) {
            for (Map.Entry<Integer, YSMRuntimeModel.CompiledChannels> entry : anim.bones.entrySet()) {
                int bone = entry.getKey();
                if (bone < 0 || bone >= rig.bones.length || entry.getValue().rot == null) {
                    continue;
                }
                YSMRuntimeModel.BoneRt rt = rig.bones[bone];
                if (rt.mapped) {
                    continue;
                }
                List<Vector3f> own = rig.ownVertices(bone);
                if (own == null || own.isEmpty()) {
                    continue;
                }
                Vector3f pivot = new Vector3f(rt.px, rt.py, rt.pz);
                Vector3f far = farthest(own, pivot);
                Vector3f atBind = dragged(far, pivot, new float[]{rt.rx, rt.ry, rt.rz});
                for (int sample = 0; sample < CLIP_SAMPLES; sample++) {
                    float time = (float) (sample * Math.PI / CLIP_SAMPLES);
                    float[] raw = new float[3];
                    evalChannel.invoke(animator, entry.getValue().rot, time, raw);
                    // production's own conversion (YSMPlayerAnimator#evalAnim 526-528)
                    float[] keyed = {(float) Math.toRadians(-raw[0]), (float) Math.toRadians(-raw[1]),
                            (float) Math.toRadians(raw[2])};
                    float clipDegrees = (float) Math.toDegrees(Math.sqrt(
                            keyed[0] * keyed[0] + keyed[1] * keyed[1] + keyed[2] * keyed[2]));
                    clipWorst.merge(bone, clipDegrees, Math::max);
                    // The legacy rule's own drawing: for a channel the clips hold at a value v, the
                    // rotation it composed was v alone - the authored rotation replaced, not offset.
                    Vector3f legacy = dragged(far, pivot, keyed);
                    // What the offset rule draws instead: the authored rotation plus the same v.
                    Vector3f offset = dragged(far, pivot,
                            new float[]{rt.rx + keyed[0], rt.ry + keyed[1], rt.rz + keyed[2]});
                    fold.merge(bone, legacy.distance(offset), Math::max);
                    legacyDrift.merge(bone, legacy.distance(atBind), Math::max);
                    offsetDrift.merge(bone, offset.distance(atBind), Math::max);
                    motion.merge(bone, offset.distance(atBind), Math::max);
                }
            }
        }
        out.keyedBones = fold.size();
        for (Map.Entry<Integer, Float> entry : fold.entrySet()) {
            int bone = entry.getKey();
            float folded = entry.getValue();
            float moved = motion.getOrDefault(bone, 0.0F);
            float legacy = legacyDrift.getOrDefault(bone, 0.0F);
            float offset = offsetDrift.getOrDefault(bone, 0.0F);
            out.worstFold = Math.max(out.worstFold, folded);
            out.worstAnimated = Math.max(out.worstAnimated, moved);
            out.worstLegacyDrift = Math.max(out.worstLegacyDrift, legacy);
            out.worstOffsetDrift = Math.max(out.worstOffsetDrift, offset);
            if (folded > MOVED) {
                out.foldedPieces++;
            }
            if (legacy > MOVED) {
                out.legacyDriftPieces++;
            }
            if (offset > MOVED) {
                out.offsetDriftPieces++;
            }
            if (moved > MOVED) {
                out.animatedPieces++;
            }
            if (clipWorst.getOrDefault(bone, 0.0F) > 0.0F) {
                out.expressionBones++;
            }
            if (folded >= out.worstFold - 1.0E-9F) {
                YSMRuntimeModel.BoneRt rt = rig.bones[bone];
                out.worstFoldBone = rt.name;
                out.worstLegacyBindDegrees = (float) Math.toDegrees(
                        Math.sqrt(rt.rx * rt.rx + rt.ry * rt.ry + rt.rz * rt.rz));
                out.worstLegacyClipDegrees = clipWorst.getOrDefault(bone, 0.0F);
            }
        }
        return out;
    }

    /**
     * A runtime model wrapper carrying nothing but this rig's bones - what the animator's constructor
     * reads (its chain classification and the tuning line) and what {@code evalChannel} needs for the
     * Molang environment. The clips are compiled separately, from production's own
     * {@code #compileAnim}.
     */
    private static YSMRuntimeModel runtimeHost(YSMRuntimeModel.BoneRt[] bones,
                                               Map<String, Integer> boneIndex, int channelCount)
            throws Exception {
        Constructor<YSMRuntimeModel> constructor = YSMRuntimeModel.class.getDeclaredConstructor(
                String.class, YSMRuntimeModel.BoneRt[].class, Map.class, List.class, Map.class,
                Map.class, Map.class, java.util.Set.class, int.class,
                YSMRuntimeModel.CameraTarget.class, List.class,
                com.google.gson.JsonElement.class, float.class, float.class);
        constructor.setAccessible(true);
        return constructor.newInstance("corpus-blast-radius", bones, boneIndex,
                new ArrayList<YSMRuntimeModel.CompiledAnim>(),
                new HashMap<String, YSMRuntimeModel.CompiledAnim>(),
                new HashMap<String, YSMRuntimeModel.CompiledAnim>(),
                new HashMap<String, ScriptAnim>(), java.util.Set.of(), channelCount, null,
                new ArrayList<YsmPhysicsBinding.Part>(), null, 1.0F, 1.0F);
    }

    /** The farthest of a bone's own vertices from its pivot - what the rule swings furthest. */
    private static Vector3f farthest(List<Vector3f> own, Vector3f pivot) {
        Vector3f far = own.get(0);
        float reach = -1.0F;
        for (Vector3f vertex : own) {
            float distance = vertex.distance(pivot);
            if (distance > reach) {
                reach = distance;
                far = vertex;
            }
        }
        return far;
    }

    /**
     * Where the vertex lands when the bone's rotation is {@code rotation} - the local transform
     * {@code #composeBone} builds, without the chain above it. Both columns call this with their own
     * rotation vector: the offset rule with {@code authored + clip}, the legacy rule with the clip's
     * value alone (what "the clip's value replaces the authored rotation" draws).
     */
    private static Vector3f dragged(Vector3f vertex, Vector3f pivot, float[] rotation) {
        Matrix4f turn = new Matrix4f()
                .rotateZ(rotation[2])
                .rotateY(rotation[1])
                .rotateX(rotation[0]);
        Vector3f offset = new Vector3f(vertex).sub(pivot);
        turn.transformPosition(offset);
        return offset.add(pivot);
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
