package com.ysmef.compat.model;

import com.ysmef.compat.testutil.LocalModelFixtures;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.runtime.YsmPhysicsBinding;
import com.ysmef.compat.ysm.YsmModelPackage;
import com.ysmef.compat.ysm.script.ScriptAnim;
import com.ysmef.compat.ysm.script.ScriptJson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers the wiring between the two halves of the physics work: the conversion writes the
 * discovered bones into the runtime JSON, and the runtime reads them back.
 *
 * <p>The two halves live in different packages and are exercised by different tests, so what
 * is pinned here is only that they agree on the section name and the shape - the failure mode
 * otherwise being a model that silently gets no physics at all because the writer emitted
 * {@code "physics"} and the reader looked for something else.
 *
 * <p>The fixtures are real: the controller and animation pair YSM ships, and the bone lists of
 * the models those files belong to. The last test goes one step further and reads a real
 * install's model packages through the production loader, guarded by
 * {@code -Dysmef.golden.ysm_config_root=<path to config/yes_steve_model>} so a machine without
 * the game simply skips it.
 */
class EFMeshJsonWriterPhysicsSectionTest {

    /** The same hook {@code YsmModelPackage} reads its root from, in both of its spellings. */
    private static String configuredConfigRoot() {
        String property = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (!property.isEmpty()) {
            return property;
        }
        String fromEnvironment = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
        return fromEnvironment == null ? "" : fromEnvironment;
    }

    /** Where the offline forensics table is written; relative to the test working directory. */
    private static final String DEFAULT_REPORT = "tmp_verify/T1_java_forensics.md";

    @Test
    void theSectionIsWrittenWhenTheModelDeclaresPhysics() {
        Map<String, List<String>> builtIn = new LinkedHashMap<>();
        builtIn.put("player.pre_parallel_0", List.of("Hair_Physics"));

        JsonObject runtime = new JsonObject();
        YsmPhysicsBinding.Selection selection = EFMeshJsonWriter.writePhysicsSection(runtime,
                YsmPhysicsBinding.Sources.of(Map.of(), Map.of(), builtIn,
                        loadAnimations("/golden/physics/hair_physics.animation.json"),
                        loadBones("/golden/physics/default_controllers_bones.json")));

        assertTrue(runtime.has("physics"), "the section the runtime reads must be present");
        assertEquals("Hair_Physics", selection.animation());
        assertEquals("Hair_Physics", runtime.getAsJsonObject("physics").get("animation").getAsString(),
                "the runtime needs the animation's name as well as its bones");
        List<YsmPhysicsBinding.Part> readBack = YsmPhysicsBinding.fromJson(runtime.get("physics"));
        assertFalse(readBack.isEmpty(), "and it must carry the discovered bones");
        assertTrue(readBack.stream().anyMatch(part -> part.bone().equals("BackHairA1")));
    }

    /**
     * A model that declares nothing gets no section at all, which is what makes the runtime's
     * fallback to bone names reachable. An empty section and no section have to mean the same
     * thing - and the runtime's own log distinguishes the two cases for the user.
     */
    @Test
    void noSectionIsWrittenWhenNothingIsDeclared() {
        JsonObject runtime = new JsonObject();

        YsmPhysicsBinding.Selection selection = EFMeshJsonWriter.writePhysicsSection(runtime,
                YsmPhysicsBinding.Sources.of(Map.of(), Map.of(), Map.of(), Map.of(), List.of()));

        assertFalse(runtime.has("physics"));
        assertNull(selection.animation());
        assertTrue(YsmPhysicsBinding.fromJson(runtime.get("physics")).isEmpty(),
                "and the runtime reads a missing section as no physics rather than as an error");
    }

    /**
     * The shipped case, through the writer rather than the rule: the maid inherits the built-in
     * {@code Hair_Physics}, none of whose bones she can move, so the section stays absent and her
     * existing fifty-nine-bone name-based classification survives.
     *
     * <p>This is the assertion that fails if someone "fixes" detection by accepting the first
     * candidate that merely looks like physics: the section would appear with a single
     * geometry-less locator in it, and the runtime, seeing an authored part list, would stop
     * classifying her bones by name - turning a model whose hair swings into one whose hair hangs
     * rigid, under a log line that says the opposite.
     */
    @Test
    void noSectionIsWrittenWhenTheBuiltInPhysicsAnimationIsNotTheModelsOwn() {
        Map<String, List<String>> builtIn = new LinkedHashMap<>();
        builtIn.put("player.pre_parallel_0", List.of("Hair_Physics"));

        JsonObject runtime = new JsonObject();
        YsmPhysicsBinding.Selection selection = EFMeshJsonWriter.writePhysicsSection(runtime,
                YsmPhysicsBinding.Sources.of(Map.of(), Map.of(), builtIn,
                        loadAnimations("/golden/physics/hair_physics.animation.json"),
                        loadBones("/golden/physics/taisho_maid_bones.json")));

        assertFalse(runtime.has("physics"), "an authored section here would replace the name fallback with one bone");
        assertTrue(selection.isEmpty());
        assertEquals(0, selection.matched());
        assertTrue(selection.rejected().contains("Hair_Physics"),
                "the caller logs this as the reason no section was written: " + selection.rejected());
    }

    /**
     * The bones a part list may name, and why the rule asks about geometry rather than spelling:
     * a container bone with the cubes hanging below it can swing, and a locator cannot move at all.
     *
     * <p>{@code StrandA} carries the geometry, {@code HairRoot} carries none of its own but is the
     * bone the strand hangs from, and {@code Locator} is the shape every YSM skeleton has one of -
     * which is why a name-only cross-check accepts YSM's built-in hairdo animation on models that
     * share no hairdo with it.
     */
    @Test
    void onlyBonesWithGeometryBelowThemCanBeSimulated() {
        YSMGeoModel model = YSMGeoModel.parse(MINI_GEOMETRY);
        assertNotNull(model, "the miniature model must parse");

        Set<String> simulatable = EFMeshJsonWriter.simulatableBoneNames(model);

        assertEquals(Set.of("Torso", "HairRoot", "StrandA"), simulatable);
        assertFalse(simulatable.contains("Locator"), "a locator has nothing to move");

        // ... and the rule reads that set, so a candidate driving only the locator is refused while
        // one driving a bone with geometry hanging below it is accepted.
        ScriptAnim locatorOnly = new ScriptAnim();
        locatorOnly.name = "Hair_Physics";
        locatorOnly.bones.put("Locator",
                channels("ysm.second_order('hair', q.ground_speed, 2, 0.5, 0)"));
        ScriptAnim strand = new ScriptAnim();
        strand.name = "pre_parallel0";
        strand.bones.put("StrandA",
                channels("ysm.second_order('hair', q.ground_speed, 1.5, 0.6, 0)"));

        YsmPhysicsBinding.Selection refused = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(Map.of(), Map.of(),
                        Map.of("player.pre_parallel_0", List.of("Hair_Physics")),
                        Map.of("Hair_Physics", locatorOnly), simulatable));
        assertNull(refused.animation(), "the locator is not something this model can move");

        YsmPhysicsBinding.Selection accepted = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(Map.of(), Map.of("pre_parallel0", strand),
                        Map.of(), Map.of(), simulatable));
        assertEquals("pre_parallel0", accepted.animation());
        assertEquals(List.of("StrandA"), bonesOf(accepted.parts()));
    }

    /** The scale section, which the physics needs to place a bone's pivot in the mesh's units. */
    @Test
    void theScaleSectionCarriesBothScales() {
        JsonObject runtime = new JsonObject();

        EFMeshJsonWriter.writeScaleSection(runtime, 0.7F, 1.1F);

        assertTrue(runtime.has("scale"));
        assertEquals(2, runtime.getAsJsonArray("scale").size());
        assertEquals(0.7F, runtime.getAsJsonArray("scale").get(0).getAsFloat(), 1.0E-6F);
        assertEquals(1.1F, runtime.getAsJsonArray("scale").get(1).getAsFloat(), 1.0E-6F);
    }

    /**
     * The end-to-end run over a real install, through the production loader and the production
     * writer: this is the evidence for the claim that the conversion path, not just the rule,
     * produces the physics section - or refuses to.
     *
     * <p>Skipped unless {@code -Dysmef.golden.ysm_config_root} points at a YSM config root, because
     * a model package only exists on a machine with the game installed. When it runs it also writes
     * the table for the detection report, to {@code -Dysmef.forensics.out} (default
     * {@code tmp_verify/T1_java_forensics.md}); that write is part of the opt-in.
     *
     * <p>The invariants asserted here hold for any install. The per-model outcome is recorded and
     * asserted only for the two models whose data is also pinned as a golden fixture, so the
     * difference between "the rule changed" and "this install has other models" stays visible.
     */
    @Test
    void theRealInstallIsConvertedThroughTheProductionPath() throws Exception {
        String configured = configuredConfigRoot();
        assumeTrue(!configured.isEmpty(),
                "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + "=<config/yes_steve_model> (or the "
                        + YsmModelPackage.CONFIG_ROOT_ENV + " environment variable) to run the install forensics");

        YsmModelPackage.BuiltinControllers builtIn = YsmModelPackage.builtinControllers();
        assertTrue(builtIn.available(),
                "the install must have a default controller set, or this run proves nothing: "
                        + builtIn.unavailableReason());

        StringBuilder report = new StringBuilder();
        report.append("# T1 conversion-path forensics (generated by EFMeshJsonWriterPhysicsSectionTest)\n\n");
        report.append("Config root: `").append(configured).append("`\n\n");
        report.append("Built-in controller set: `").append(builtIn.source()).append("` (")
                .append(builtIn.controllers().size()).append(" controller(s), ")
                .append(builtIn.animations().size()).append(" animation(s))\n\n");
        report.append("| model | own controllers | own animations | selected animation | declared | matched | parts | refused |\n");
        report.append("|---|---|---|---|---|---|---|---|\n");

        for (String modelId : REPORTED_MODELS) {
            YsmModelPackage pkg = YsmModelPackage.load(modelId);
            assertNotNull(pkg, "model package '" + modelId + "' must load from " + configured);
            assertNotNull(pkg.geometry, "model '" + modelId + "' must have geometry");

            Set<String> simulatable = EFMeshJsonWriter.simulatableBoneNames(pkg.geometry);
            assertFalse(simulatable.isEmpty(),
                    "a model whose every bone lacks geometry cannot be simulated at all: " + modelId);

            JsonObject runtime = new JsonObject();
            YsmPhysicsBinding.Selection selection = EFMeshJsonWriter.writePhysicsSection(runtime,
                    YsmPhysicsBinding.Sources.of(pkg.animationControllers, pkg.allScriptAnims,
                            builtIn.controllers(), builtIn.animations(), simulatable));

            // Invariants that hold on any install, so a broken rule cannot pass by being reported.
            for (YsmPhysicsBinding.Part part : selection.parts()) {
                assertTrue(simulatable.contains(part.bone()),
                        modelId + " would simulate " + part.bone() + ", which has no geometry to move");
            }
            if (selection.animation() == null) {
                assertTrue(selection.isEmpty(), modelId + " names no animation but produced parts");
                assertFalse(runtime.has("physics"), modelId + " wrote a section with no animation");
            } else {
                assertFalse(selection.isEmpty(),
                        modelId + " named '" + selection.animation() + "' but produced no parts");
                assertTrue(selection.matched() > 0, modelId + " accepted a candidate that matches nothing");
                assertEquals(selection.animation(),
                        runtime.getAsJsonObject("physics").get("animation").getAsString(),
                        "the section and the selection must agree on the animation");
            }

            if ("wine_fox/01_taisho_maid".equals(modelId)) {
                assertNull(selection.animation(),
                        "the maid shares only a geometry-less locator with the built-in physics animation");
            }
            if ("misc/4_default_controllers".equals(modelId)) {
                assertEquals("Hair_Physics", selection.animation(),
                        "the model the built-in physics animation was written for must still find it");
            }

            report.append("| ").append(modelId)
                    .append(" | ").append(pkg.animationControllers.size())
                    .append(" | ").append(pkg.allScriptAnims.size())
                    .append(" | ").append(selection.animation() == null ? "*(none)*" : selection.animation())
                    .append(" | ").append(selection.declared())
                    .append(" | ").append(selection.matched())
                    .append(" | ").append(selection.parts().size())
                    .append(" | ").append(selection.rejected().isEmpty() ? "" : selection.rejected())
                    .append(" |\n");
        }

        Path reportPath = Paths.get(System.getenv("YSMEF_FORENSICS_OUT") == null
                ? DEFAULT_REPORT : System.getenv("YSMEF_FORENSICS_OUT"));
        if (reportPath.getParent() != null) {
            Files.createDirectories(reportPath.getParent());
        }
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
    }

    /** The models the offline forensics table reports on: the shipped ones this task is about. */
    private static final List<String> REPORTED_MODELS = List.of(
            "wine_fox/01_taisho_maid",
            "wine_fox/22_elf",
            "wine_fox/03_astronaut",
            "misc/3_default_boy",
            "misc/4_default_controllers");

    /**
     * A miniature model with the two shapes the geometry question turns on: a bone whose cubes hang
     * below it, and a locator with none anywhere in its subtree.
     */
    private static final String MINI_GEOMETRY =
            "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":"
                    + "\"geometry.test\",\"texture_width\":64,\"texture_height\":64},\"bones\":["
                    + "{\"name\":\"Torso\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[0,0,0],\"size\":[4,4,4],\"uv\":[0,0]}]},"
                    + "{\"name\":\"HairRoot\",\"parent\":\"Torso\",\"pivot\":[0,8,0]},"
                    + "{\"name\":\"StrandA\",\"parent\":\"HairRoot\",\"pivot\":[0,9,0],\"cubes\":[{\"origin\":[0,9,0],\"size\":[2,4,2],\"uv\":[0,0]}]},"
                    + "{\"name\":\"Locator\",\"parent\":\"Torso\",\"pivot\":[0,4,0]}]}]}";

    private static List<String> bonesOf(List<YsmPhysicsBinding.Part> parts) {
        List<String> names = new ArrayList<>();
        for (YsmPhysicsBinding.Part part : parts) {
            names.add(part.bone());
        }
        return names;
    }

    private static List<String> loadBones(String resource) {
        JsonObject root = readJson(resource);
        List<String> bones = new ArrayList<>();
        for (com.google.gson.JsonElement element : root.getAsJsonArray("simulatableBones")) {
            bones.add(element.getAsString());
        }
        assertFalse(bones.isEmpty(), "fixture " + resource + " must carry a bone list");
        return bones;
    }

    private static Map<String, ScriptAnim> loadAnimations(String resource) {
        JsonObject root = readJson(resource);
        Map<String, ScriptAnim> animations = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : root.getAsJsonObject("animations").entrySet()) {
            animations.put(entry.getKey(),
                    ScriptJson.fromBedrock(entry.getKey(), entry.getValue().getAsJsonObject()));
        }
        return animations;
    }

    private static JsonObject readJson(String resource) {
        try (InputStream in = LocalModelFixtures.open(resource)) {
            assertNotNull(in, "missing test fixture " + resource);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (org.opentest4j.TestAbortedException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }

    private static ScriptAnim.BoneChannels channels(String rotation) {
        ScriptAnim.Channel channel = new ScriptAnim.Channel();
        ScriptAnim.Key key = new ScriptAnim.Key();
        key.time = 0.0F;
        key.post = ScriptAnim.Value.ofExpr(rotation, rotation, rotation);
        channel.keys.add(key);
        ScriptAnim.BoneChannels boneChannels = new ScriptAnim.BoneChannels();
        boneChannels.rotation = channel;
        return boneChannels;
    }
}
