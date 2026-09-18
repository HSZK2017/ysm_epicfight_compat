package com.ysmef.compat.ysm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the model package's animation-controller parsing, against the real file YSM ships.
 *
 * <p>The fixture is the controller file of YSM's bundled "default controllers" example,
 * copied verbatim. What it guards is the key names: the physics classification is driven by
 * which animations a controller plays, so reading {@code states} or {@code animations} one
 * level off would not fail - it would silently make every model fall back to classifying its
 * physics bones by name, which is exactly the behaviour this work exists to replace.
 *
 * <p>Both entry forms in the file are real: {@code player.pre_parallel_0} lists bare names
 * ({@code "Hair_Physics"}), and {@code player.post_hold} uses the {@code {"name": "molang
 * condition"}} object form.
 */
class YsmAnimationControllerParseTest {

    private static final String CONTROLLER_RESOURCE = "/golden/physics/default_controllers.json";

    /**
     * The controller that binds the physics animation. Its first entry is the whole reason
     * the physics bones can be found without guessing: nothing about the model's bone names
     * says which of them swing, and this says it.
     */
    @Test
    void theDefaultControllerBindsThePhysicsAnimation() {
        Map<String, List<String>> controllers = parse();

        List<String> preParallel = controllers.get("player.pre_parallel_0");
        assertNotNull(preParallel, "controllers are keyed by their full name: " + controllers.keySet());
        assertTrue(preParallel.contains("Hair_Physics"),
                "the physics animation is named in the state's animations list: " + preParallel);
        assertTrue(preParallel.contains("pre_parallel1"),
                "and the parallel family is listed alongside it: " + preParallel);
    }

    /** Every controller of the file, so a key-name mistake cannot pass by luck on one entry. */
    @Test
    void everyControllerOfTheFileIsRead() {
        Map<String, List<String>> controllers = parse();

        assertTrue(controllers.containsKey("player.pre_parallel_0"));
        assertTrue(controllers.containsKey("player.parallel_0"));
        assertTrue(controllers.containsKey("player.post_swing"));
        assertTrue(controllers.containsKey("player.post_hold"));
        assertEquals(5, controllers.size(), "the file defines five controllers: " + controllers.keySet());
    }

    /**
     * The object form is what the state machines use almost everywhere
     * ({@code {"sword_attack_01": "!ctrl.idle"}}), so the animation name is the object's key,
     * not its value - reading the value would collect molang conditions as animation names.
     */
    @Test
    void theObjectFormYieldsTheAnimationNameNotTheCondition() {
        Map<String, List<String>> controllers = parse();

        List<String> postSwing = controllers.get("player.post_swing");
        assertNotNull(postSwing);
        assertTrue(postSwing.contains("sword_attack_01"),
                "the object key is the animation: " + postSwing);
        assertTrue(postSwing.contains("sword_idle_attack_01"),
                "and an entry whose condition selects the idle variant: " + postSwing);
        assertTrue(postSwing.stream().noneMatch(name -> name.contains("ctrl.idle")),
                "a transition condition must never be mistaken for an animation: " + postSwing);
        assertTrue(postSwing.stream().noneMatch(name -> name.contains("swing_sword")),
                "nor a transition's molang side effect: " + postSwing);
    }

    /** A controller file with nothing usable must leave the table empty, not throw. */
    @Test
    void aControllerFileWithoutControllersIsSurvivable() {
        Map<String, List<String>> out = new LinkedHashMap<>();

        YsmModelPackage.controllerAnimationsOf(null, out);
        YsmModelPackage.controllerAnimationsOf(JsonParser.parseString("{}").getAsJsonObject(), out);
        YsmModelPackage.controllerAnimationsOf(
                JsonParser.parseString("{\"animation_controllers\":3}").getAsJsonObject(), out);
        YsmModelPackage.controllerAnimationsOf(
                JsonParser.parseString("{\"animation_controllers\":{\"a\":{\"states\":3}}}").getAsJsonObject(), out);
        YsmModelPackage.controllerAnimationsOf(
                JsonParser.parseString("{\"animation_controllers\":{\"a\":{\"states\":{\"s\":{\"animations\":7}}}}}")
                        .getAsJsonObject(), out);

        assertTrue(out.isEmpty() || out.values().stream().allMatch(List::isEmpty),
                "nothing parseable means nothing recorded: " + out);
    }

    /** Controllers are merged, not replaced, when several files are read into one table. */
    @Test
    void readingTheSameFileTwiceDoesNotDuplicateAnimations() {
        Map<String, List<String>> out = new LinkedHashMap<>();

        YsmModelPackage.controllerAnimationsOf(read(CONTROLLER_RESOURCE), out);
        int firstSize = out.get("player.pre_parallel_0").size();
        YsmModelPackage.controllerAnimationsOf(read(CONTROLLER_RESOURCE), out);

        assertEquals(firstSize, out.get("player.pre_parallel_0").size(),
                "the same animation listed twice must be recorded once");
    }

    private static Map<String, List<String>> parse() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        YsmModelPackage.controllerAnimationsOf(read(CONTROLLER_RESOURCE), out);
        return out;
    }

    private static JsonObject read(String resource) {
        try (InputStream in = YsmAnimationControllerParseTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing test fixture " + resource);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }
}
