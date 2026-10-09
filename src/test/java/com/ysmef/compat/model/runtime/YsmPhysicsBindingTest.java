package com.ysmef.compat.model.runtime;

import com.ysmef.compat.testutil.LocalModelFixtures;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.ysm.script.ScriptAnim;
import com.ysmef.compat.ysm.script.ScriptJson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the new way the physics-driven bones are found: from the model's animation
 * controllers and the animations they play, instead of from guesses about bone names.
 *
 * <p>The fixtures are not invented. {@code default_controllers.json} and
 * {@code hair_physics.animation.json} are the controller and animation pair YSM ships as
 * its own worked example of the convention ({@code player.pre_parallel_0} plays
 * {@code Hair_Physics}), copied verbatim from a model package on the test instance;
 * {@code astronaut_parallel.animation.json} is a real wine fox variant whose physics
 * animation is named {@code pre_parallel0} and is bound by YSM itself, with no controller
 * file at all. Both routes are exercised because a real install contains both.
 *
 * <p>What the tests are guarding is the difference between "the feature does nothing" and
 * "the feature does the wrong thing". A discovery that returns nothing leaves the model
 * rigid, which is visible but harmless; a discovery that returns the wrong bone swings a
 * body part. The name-based classifier that preceded this could only be argued about; the
 * controller states the answer in the model file, and that is what these assert.
 */
class YsmPhysicsBindingTest {

    /** The controller file of YSM's bundled "default controllers" example model. */
    private static final String CONTROLLER_RESOURCE = "/golden/physics/default_controllers.json";
    /** The animation file of that same model: it defines {@code Hair_Physics}. */
    private static final String HAIR_RESOURCE = "/golden/physics/hair_physics.animation.json";
    /** The wine fox astronaut's continuously-evaluated physics animation. */
    private static final String ASTRONAUT_RESOURCE = "/golden/physics/astronaut_parallel.animation.json";
    /**
     * The simulatable bone names of the shipped maid ({@code wine_fox/01_taisho_maid}), read out of
     * her {@code models/main.json}: 195 bones, 175 of which carry geometry themselves or have a
     * descendant that does. Recorded rather than read at test time because a model package only
     * exists on a machine with the game installed - and this is the file that decides whether the
     * built-in physics animation is hers.
     */
    private static final String MAID_BONES_RESOURCE = "/golden/physics/taisho_maid_bones.json";
    /** The same for the built-in default controllers model: the one {@code Hair_Physics} belongs to. */
    private static final String DEFAULT_CONTROLLERS_BONES_RESOURCE =
            "/golden/physics/default_controllers_bones.json";

    // ------------------------------------------------------------------
    // The controller route
    // ------------------------------------------------------------------

    /**
     * The whole point of the class: the controller names {@code Hair_Physics}, the animation
     * names the bones, and the bones come out.
     *
     * <p>Every name asserted here was previously either missed or mis-classified: the
     * name-based classifier sees "hair" in {@code BackHairA1} but not in
     * {@code MaWei_LeftA1}, and it rejected {@code ElytraLocator} outright - correctly, as a
     * locator - while the author's physics animation drives it on purpose.
     */
    @Test
    void theDefaultControllerNamesThePhysicsBonesOfItsModel() {
        Fixture fixture = loadDefaultControllers();

        List<YsmPhysicsBinding.Part> parts = YsmPhysicsBinding.discover(
                YsmPhysicsBinding.Sources.modelOnly(fixture.controllers(), fixture.animations()));

        assertFalse(parts.isEmpty(), "the controller plays an animation that declares physics bones");
        List<String> names = names(parts);
        assertTrue(names.contains("BackHairA1"), "a back-hair root the controller binds: " + names);
        assertTrue(names.contains("BackHairE3"), "the far end of the same chain: " + names);
        assertTrue(names.contains("MaWei_LeftA1"),
                "a horse-tail segment whose name says nothing about hair: " + names);
        assertTrue(names.contains("Right_SideDownHairM2"), "a side lock: " + names);
    }

    /**
     * The author's own spring numbers are read, because the alternative is one global
     * constant applied to every model ever made.
     *
     * <p>{@code Right_SideDownHairM2}'s expression is
     * {@code 10*ysm.second_order('A1x增量', v.HP_x, 1.7, 0.5, 0)} - frequency 1.7, coefficient
     * 0.5 - while {@code Right_SideDownHairM1} asks for 1.0 and 0.6. Both are read from the
     * real file.
     */
    @Test
    void theAuthorsSpringNumbersAreRead() {
        Fixture fixture = loadDefaultControllers();

        Map<String, YsmPhysicsBinding.Part> parts = YsmPhysicsBinding.byName(
                YsmPhysicsBinding.discover(
                        YsmPhysicsBinding.Sources.modelOnly(fixture.controllers(), fixture.animations())));

        YsmPhysicsBinding.Part stiff = parts.get("Right_SideDownHairM2");
        assertNotNull(stiff, "the bone is in the animation");
        assertEquals(1.7, stiff.frequency(), 1.0E-6, "ysm.second_order's third argument is the frequency");
        assertEquals(0.5, stiff.coefficient(), 1.0E-6, "and the fourth is the damping coefficient");
        assertTrue(stiff.filtered(), "this bone carries a spring call of its own");

        YsmPhysicsBinding.Part other = parts.get("Right_SideDownHairM1");
        assertNotNull(other);
        assertEquals(1.0, other.frequency(), 1.0E-6,
                "a first call inside a sum of three springs sets the bone's stiffness");
        assertEquals(0.6, other.coefficient(), 1.0E-6);
    }

    /**
     * The chain link, through the variable hop a real model uses.
     *
     * <p>{@code BackHairB2}'s expression reads {@code v.HP_x}, which the timeline sets from
     * {@code v.HP_x_0}, which is {@code ysm.bone_rot('BackHairA1').x}. Resolving only the
     * direct {@code bone_rot} form leaves this bone looking like a chain root - and a hairdo
     * of ten roots is a hairdo of ten independently swinging sticks.
     */
    @Test
    void aChainLinkIsRecoveredThroughATimelineVariable() {
        Fixture fixture = loadDefaultControllers();

        Map<String, YsmPhysicsBinding.Part> parts = YsmPhysicsBinding.byName(
                YsmPhysicsBinding.discover(
                        YsmPhysicsBinding.Sources.modelOnly(fixture.controllers(), fixture.animations())));

        YsmPhysicsBinding.Part follower = parts.get("BackHairB2");
        assertNotNull(follower, "the bone is in the animation");
        assertEquals("BackHairA1", follower.follows(),
                "the timeline carries v.HP_x_0 = ysm.bone_rot('BackHairA1').x into v.HP_x");

        YsmPhysicsBinding.Part direct = parts.get("BackHairC2");
        assertNotNull(direct);
        assertEquals("BackHairB1", direct.follows(),
                "a direct ysm.bone_rot('BackHairB1') argument is the link");

        YsmPhysicsBinding.Part root = parts.get("Right_SideDownHairM1");
        assertNotNull(root);
        assertEquals("", root.follows(),
                "a segment driven straight from the body is a chain root");
    }

    /**
     * The controller route is the point, but a model can be authored without controllers:
     * the astronaut wine fox defines its physics in an animation literally named
     * {@code pre_parallel0}, which YSM binds by name. Dropping that route would lose the
     * physics of every such model while looking like a stricter rule.
     *
     * <p>The counts are from the real file: twenty-three bones of {@code pre_parallel0}
     * declare a spring on their rotation, and {@code parallel0} adds one more
     * ({@code Breast}, whose jiggle is declared on its rotation and scale together).
     */
    @Test
    void aPhysicsAnimationWithNoControllerIsStillFound() {
        Map<String, ScriptAnim> animations = loadAnimations(ASTRONAUT_RESOURCE);

        List<YsmPhysicsBinding.Part> parts = YsmPhysicsBinding.discover(
                YsmPhysicsBinding.Sources.modelOnly(Map.of(), animations));

        List<String> names = names(parts);
        assertEquals(24, parts.size(), "23 rotations from pre_parallel0 and Breast from parallel0: " + names);
        assertTrue(names.contains("FLongHair"));
        assertTrue(names.contains("TuEr_R3"), "an ear segment, which no name list would call hair");
        assertTrue(names.contains("MLongLeftHair"));
        assertTrue(names.contains("Breast"), "the author's jiggle is declared on its rotation channel");
    }

    /**
     * What the rotation-only rule keeps out, and why each one is right to keep out.
     *
     * <p>{@code LeftEyelidBase} and {@code RightEyelidBase} are driven by a spring on their
     * <i>position</i> channel - an eye that lags the head - and a pendulum rotation about the
     * bone's pivot is the wrong motion for that. {@code molang} is a placeholder bone whose
     * rotation channel is a bare variable assignment, so it has nothing to rotate at all.
     */
    @Test
    void positionDrivenAndScriptOnlyBonesAreNotRotated() {
        Map<String, ScriptAnim> animations = loadAnimations(ASTRONAUT_RESOURCE);

        List<String> names = names(YsmPhysicsBinding.discover(
                YsmPhysicsBinding.Sources.modelOnly(Map.of(), animations)));

        assertFalse(names.contains("LeftEyelidBase"),
                "eye lag is a position effect; rotating the eyelid about its pivot is not it");
        assertFalse(names.contains("RightEyelidBase"), "same bone on the other side");
        assertFalse(names.contains("molang"),
                "a bone whose rotation is 'v.hv=0;v.hg=0;' is a script, not a swinging piece");
    }

    /**
     * The other half of "only what the model says": an animation nothing plays, and that
     * carries no physics call, is not evidence.
     *
     * <p>This is the failure that would be worst in game - accepting a locomotion animation
     * makes the model's whole body swing on its own - so the rule is pinned even though the
     * real fixtures happen not to contain such an animation.
     */
    @Test
    void anOrdinaryAnimationIsNotPhysicsEvidence() {
        ScriptAnim idle = new ScriptAnim();
        idle.name = "idle";
        ScriptAnim.BoneChannels channels = new ScriptAnim.BoneChannels();
        channels.rotation = channel(0.0F, "q.anim_time * 30");
        idle.bones.put("RightArm", channels);

        assertFalse(YsmPhysicsBinding.looksLikePhysics("idle", idle),
                "a plain keyframe animation is not a physics declaration");
        assertTrue(YsmPhysicsBinding.discover(YsmPhysicsBinding.Sources.modelOnly(
                Map.of("player.idle", List.of("idle")), Map.of("idle", idle))).isEmpty(),
                "and a controller playing it does not make it one");
    }

    /** An animation with no controller and no parallel-family name is never inspected. */
    @Test
    void anUnboundAnimationIsNotInspected() {
        ScriptAnim anim = new ScriptAnim();
        anim.name = "extra4";
        ScriptAnim.BoneChannels channels = new ScriptAnim.BoneChannels();
        channels.rotation = channel(0.0F, "ysm.second_order('x', 1, 2, 0.5, 0)");
        anim.bones.put("SomeBone", channels);

        assertTrue(YsmPhysicsBinding.discover(
                YsmPhysicsBinding.Sources.modelOnly(Map.of(), Map.of("extra4", anim))).isEmpty(),
                "nothing plays this animation, so it declares nothing");
    }

    /**
     * A bone a spring never touches is still a physics bone when it rotates with one that is:
     * the fan of strands a single filtered root drives. It is reported as unfiltered so the
     * caller knows the tuning is a default rather than the author's number.
     */
    @Test
    void aBoneFollowingAFilteredBoneIsAcceptedAsUnfiltered() {
        ScriptAnim anim = new ScriptAnim();
        anim.name = "Hair_Physics";
        anim.bones.put("Root", channels("ysm.second_order('x', q.ground_speed, 2, 0.5, 0)", null));
        anim.bones.put("Tip", channels("0.5*ysm.bone_rot('Root').x", null));
        // A strand that follows the strand above it: accepted only on the second pass.
        anim.bones.put("Tip2", channels("0.5*ysm.bone_rot('Tip').x", null));
        // A bone with a rotation of its own that no spring and no link touches.
        anim.bones.put("Decorative", channels("30", null));

        Map<String, YsmPhysicsBinding.Part> parts = YsmPhysicsBinding.byName(
                YsmPhysicsBinding.discover(YsmPhysicsBinding.Sources.modelOnly(
                        Map.of("player.pre_parallel_0", List.of("Hair_Physics")), Map.of("Hair_Physics", anim))));

        assertTrue(parts.containsKey("Root"));
        assertTrue(parts.get("Root").filtered(), "Root carries the spring");
        assertTrue(parts.containsKey("Tip"), "a strand driven from Root's rotation is physics too");
        assertFalse(parts.get("Tip").filtered(), "but it carries no spring of its own");
        assertTrue(parts.containsKey("Tip2"), "and the link is followed more than one hop");
        assertFalse(parts.containsKey("Decorative"),
                "a bone with nothing driving it but a constant is not physics");
    }

    /**
     * A bone the model declares on its scale channel only - squash and stretch - is not
     * something a pendulum can drive, and accepting it would rotate a bone the author only
     * ever scaled.
     */
    @Test
    void scaleOnlyPhysicsIsNotRotated() {
        ScriptAnim anim = new ScriptAnim();
        anim.name = "parallel0";
        ScriptAnim.BoneChannels squash = new ScriptAnim.BoneChannels();
        squash.scale = channel(0.0F, "1+0.01*ysm.second_order('x', q.vertical_speed, 3, 0.3, 0)");
        anim.bones.put("Belly", squash);

        assertTrue(YsmPhysicsBinding.discover(
                YsmPhysicsBinding.Sources.modelOnly(Map.of(), Map.of("parallel0", anim))).isEmpty(),
                "a scale-only spring has no rotation to simulate");
    }

    /** The name route: the bundled example calls its physics animation "Hair_Physics". */
    @Test
    void aPhysicsNamedAnimationCountsAsEvidenceOnItsOwn() {
        assertTrue(YsmPhysicsBinding.looksLikePhysics("Hair_Physics", new ScriptAnim()),
                "the name alone is evidence, for animations written as plain keyframes");
        assertFalse(YsmPhysicsBinding.looksLikePhysics("hair_style_4", new ScriptAnim()),
                "'hair' is not enough: the name test must stay narrow");
    }

    // ------------------------------------------------------------------
    // Expression reading
    // ------------------------------------------------------------------

    /**
     * The argument splitter, on the shape that actually occurs: a nested call with commas in
     * it, then the numbers. Splitting on every comma yields seven arguments and reads the
     * frequency off the wrong one.
     */
    @Test
    void nestedCallArgumentsAreSplitAtTheTopLevelOnly() {
        String code = "ysm.second_order('头发垂直', math.clamp(-5*q.vertical_speed+v.hv,-10,150), 1.5, 0.6, 0)";
        int open = code.indexOf('(', code.indexOf("ysm.second_order"));

        List<String> args = YsmPhysicsBinding.splitArguments(code, open);

        assertEquals(5, args.size(), "the clamp's two commas are not separators: " + args);
        assertEquals("'头发垂直'", args.get(0));
        assertEquals(" math.clamp(-5*q.vertical_speed+v.hv,-10,150)", args.get(1));
        assertEquals(" 1.5", args.get(2));
        assertEquals(" 0.6", args.get(3));
        assertEquals(" 0", args.get(4));
    }

    /** A ternary containing string literals: its commas and quotes must not confuse the split. */
    @Test
    void quotedCommasInsideAnArgumentAreNotSeparators() {
        String code = "ysm.second_order('a,b', q.x>0?1:2, 2, 0.5, 0)";
        int open = code.indexOf('(', code.indexOf("ysm.second_order"));

        List<String> args = YsmPhysicsBinding.splitArguments(code, open);

        assertEquals(5, args.size());
        assertEquals("'a,b'", args.get(0));
    }

    /**
     * An expression whose spring arguments are themselves expressions cannot be read as
     * numbers, and guessing one would silently pin the bone to the default frequency. The
     * default is the honest answer, and it is what this asserts.
     */
    @Test
    void aNonNumericArgumentFallsBackToTheDefault() {
        double[] tuning = YsmPhysicsBinding.readTuning(
                "ysm.second_order('x', q.ground_speed, v.freq, 0.4, 0)");

        assertNotNull(tuning);
        assertEquals(YsmPhysicsBinding.DEFAULT_FREQUENCY, tuning[0], 1.0E-6);
        assertEquals(0.4, tuning[1], 1.0E-6, "the arguments that are literals are still read");
    }

    /** YSM clamps both numbers, so a model asking for 900 Hz must not reach the integrator. */
    @Test
    void authoredNumbersAreClampedToTheRangeYsmItselfUses() {
        double[] tuning = YsmPhysicsBinding.readTuning("ysm.second_order('x', 1, 900, 12, 0)");

        assertNotNull(tuning);
        assertTrue(tuning[0] <= 5.0, "frequency is clamped to 5 Hz, as YSM's own SecondOrder does");
        assertTrue(tuning[1] <= 1.0, "as is the damping coefficient");
    }

    /** A bone with no spring call of its own reads the defaults and is marked unfiltered. */
    @Test
    void aBoneWithNoSpringCallIsMarkedUnfiltered() {
        Fixture fixture = loadDefaultControllers();

        Map<String, YsmPhysicsBinding.Part> parts = YsmPhysicsBinding.byName(
                YsmPhysicsBinding.discover(
                        YsmPhysicsBinding.Sources.modelOnly(fixture.controllers(), fixture.animations())));

        // Every bone the bundled example's physics animation lists carries its own spring,
        // which is why the unfiltered case is exercised on a synthetic animation above; what
        // is worth pinning on the real file is that none of them lost its tuning.
        for (YsmPhysicsBinding.Part part : parts.values()) {
            assertTrue(part.filtered(),
                    part.bone() + " carries a spring call in the real file and must keep its author's tuning");
            assertTrue(part.frequency() > 0.0, part.bone() + " needs a usable frequency");
        }
    }

    // ------------------------------------------------------------------
    // Runtime JSON round trip
    // ------------------------------------------------------------------

    /**
     * The discovery runs at conversion time and the runtime reads the result back, so the two
     * have to agree on the wire format - including the defaults, which must survive the trip
     * as defaults and not as zeros.
     */
    @Test
    void theRuntimeJsonRoundTripsEveryField() {
        List<YsmPhysicsBinding.Part> parts = List.of(
                new YsmPhysicsBinding.Part("Hair_A", "Hair_Root", 1.7, 0.5, 0.2, true),
                new YsmPhysicsBinding.Part("Hair_B", "", 1.0, 0.6, 0.0, false));

        JsonObject json = YsmPhysicsBinding.toJson(parts);
        List<YsmPhysicsBinding.Part> read = YsmPhysicsBinding.fromJson(json);

        assertEquals(2, read.size());
        assertEquals(parts.get(0), read.get(0));
        assertEquals(parts.get(1), read.get(1), "an empty follows link survives as empty, not as null");
    }

    /** A damaged section drops the entry, not the model. */
    @Test
    void aMalformedSectionIsSurvivable() {
        assertTrue(YsmPhysicsBinding.fromJson(null).isEmpty());
        assertTrue(YsmPhysicsBinding.fromJson(JsonParser.parseString("\"nope\"")).isEmpty());
        assertTrue(YsmPhysicsBinding.fromJson(JsonParser.parseString("{\"bones\":3}")).isEmpty());
        assertEquals(1, YsmPhysicsBinding.fromJson(
                JsonParser.parseString("{\"bones\":[{\"bone\":\"A\"},{\"nope\":1},7]}")).size());
    }

    // ------------------------------------------------------------------
    // YSM's built-in default controllers, and the bones the model can move
    // ------------------------------------------------------------------

    /**
     * The shipped case the name-based classifier was never going to win: the maid's physics is
     * bound by YSM's built-in controller, and the built-in animation is not hers.
     *
     * <p>Her own package offers nothing to find - one {@code player.post_main} state playing idle
     * animations, and no spring call in any of her 114 animations - so the built-in
     * {@code Hair_Physics} is the only candidate in the whole model. It names fifty-eight bones.
     * Exactly one of them, {@code ElytraLocator}, exists on her at all, and it carries no geometry
     * and has no children, so a part list built from that match would name one bone the mesh
     * cannot move - and, because an authored list replaces the name-based classification instead
     * of adding to it, would take her from fifty-nine simulated bones to none. The rule has to
     * refuse it, and the caller then falls back to the names, which is the truth about this model:
     * it declares no physics of its own.
     */
    @Test
    void theBuiltInPhysicsAnimationIsRefusedForAModelItDoesNotDrive() {
        Fixture fixture = loadDefaultControllers();
        List<String> simulatable = loadBones(MAID_BONES_RESOURCE);

        YsmPhysicsBinding.Selection selection = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(Map.of(), Map.of(),
                        fixture.controllers(), fixture.animations(), simulatable));

        assertNull(selection.animation(), "the built-in animation drives nothing this model can move");
        assertTrue(selection.isEmpty(), "so the model declares nothing and the name fallback stands");
        assertEquals(0, selection.matched(), "not one of the bones it drives is one this model can move");
        assertTrue(selection.declared() > 50,
                "the whole animation was examined, not just its first bone: " + selection.declared());
        assertTrue(selection.rejected().contains("Hair_Physics"),
                "and the refusal is reported instead of being swallowed: " + selection.rejected());
    }

    /**
     * The other side of the same rule, on the model the built-in animation was written for: the
     * cross-check must not refuse everything, or the class has simply stopped working.
     *
     * <p>Its one non-part is a locator too - {@code ElytraLocator} is the only bone of the
     * fifty-eight that has no geometry anywhere below it - which is what makes the rule's
     * question "can this model move it" rather than "is it spelled the same".
     */
    @Test
    void theBuiltInPhysicsAnimationIsAcceptedForTheModelItBelongsTo() {
        Fixture fixture = loadDefaultControllers();
        List<String> simulatable = loadBones(DEFAULT_CONTROLLERS_BONES_RESOURCE);

        YsmPhysicsBinding.Selection selection = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(Map.of(), Map.of(),
                        fixture.controllers(), fixture.animations(), simulatable));

        assertEquals("Hair_Physics", selection.animation());
        assertFalse(selection.isEmpty());
        assertEquals(selection.parts().size(), selection.matched(),
                "the count the caller logs is read off the list it simulates");
        assertTrue(selection.matched() >= 57,
                "every bone of the animation that this model can move is kept: " + selection.matched());
        assertTrue(names(selection.parts()).contains("BackHairA1"));
        assertFalse(names(selection.parts()).contains("ElytraLocator"),
                "a locator with no geometry below it is not a part the mesh can move");
        assertTrue(selection.rejected().isEmpty(), "nothing needed refusing: " + selection.rejected());
        for (YsmPhysicsBinding.Part part : selection.parts()) {
            assertTrue(simulatable.contains(part.bone()), part.bone() + " must be a bone with geometry");
        }
    }

    /**
     * The fix itself, in the smallest form that fails on the old wiring: an animation the model
     * never mentions. Nothing in the model's own package says a word about physics; the built-in
     * controller plays {@code Hair_Physics}, and that is the only route by which the model can be
     * asked what its physics is.
     */
    @Test
    void aPhysicsAnimationTheModelInheritsFromTheBuiltInSetIsFound() {
        ScriptAnim idle = new ScriptAnim();
        idle.name = "new_idle_1";
        idle.bones.put("Head", channels("q.anim_time * 10", null));

        ScriptAnim inherited = new ScriptAnim();
        inherited.name = "Hair_Physics";
        inherited.bones.put("LongHair",
                channels("ysm.second_order('hair', q.ground_speed, 2, 0.5, 0)", null));

        Map<String, List<String>> ownControllers = Map.of("player.post_main", List.of("new_idle_1"));
        Map<String, ScriptAnim> ownAnimations = Map.of("new_idle_1", idle);
        List<String> bones = List.of("Head", "LongHair");

        YsmPhysicsBinding.Selection withoutBuiltIn = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(ownControllers, ownAnimations, Map.of(), Map.of(), bones));
        assertNull(withoutBuiltIn.animation(),
                "the model's own package says nothing about physics, which is why the old wiring found nothing");

        YsmPhysicsBinding.Selection withBuiltIn = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(ownControllers, ownAnimations,
                        Map.of("player.pre_parallel_0", List.of("Hair_Physics")),
                        Map.of("Hair_Physics", inherited), bones));

        assertEquals("Hair_Physics", withBuiltIn.animation(),
                "the built-in controller binds it, so the model inherits it");
        assertEquals(List.of("LongHair"), names(withBuiltIn.parts()));
        assertEquals(1, withBuiltIn.matched());
    }

    /**
     * A candidate that drives nothing this model can move is skipped and the next one is tried,
     * rather than the model being declared physics-less because its first candidate was wrong.
     */
    @Test
    void aCandidateThatDrivesNothingIsSkippedForTheNextOne() {
        ScriptAnim ownButForeign = new ScriptAnim();
        ownButForeign.name = "pre_parallel0";
        ownButForeign.bones.put("BackHairA1",
                channels("ysm.second_order('hair', q.ground_speed, 2, 0.5, 0)", null));

        ScriptAnim inherited = new ScriptAnim();
        inherited.name = "Hair_Physics";
        inherited.bones.put("LongHair",
                channels("ysm.second_order('hair', q.ground_speed, 1.5, 0.6, 0)", null));

        YsmPhysicsBinding.Selection selection = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(Map.of(),
                        Map.of("pre_parallel0", ownButForeign),
                        Map.of("player.pre_parallel_0", List.of("Hair_Physics")),
                        Map.of("Hair_Physics", inherited),
                        List.of("LongHair")));

        assertEquals("Hair_Physics", selection.animation(),
                "the model's own pre_parallel0 drives a bone it does not have, so the next candidate is read");
        assertEquals(List.of("LongHair"), names(selection.parts()));
        assertTrue(selection.rejected().contains("pre_parallel0"),
                "and the skipped candidate is named: " + selection.rejected());
    }

    /**
     * Name resolution between the two tables: the model's own definition of an animation wins,
     * which is what Bedrock name resolution does. A model that defines its own {@code pre_parallel1}
     * means it - the built-in copy of that name is a default, not an override - so a built-in
     * spring must not be read out of a model that authored a plain pose under the same name.
     */
    @Test
    void theModelsOwnDefinitionShadowsTheBuiltInOne() {
        ScriptAnim ownPose = new ScriptAnim();
        ownPose.name = "pre_parallel1";
        ownPose.bones.put("LongHair", channels("q.anim_time * 30", null));

        ScriptAnim builtInSpring = new ScriptAnim();
        builtInSpring.name = "pre_parallel1";
        builtInSpring.bones.put("LongHair",
                channels("ysm.second_order('hair', q.ground_speed, 2, 0.5, 0)", null));

        YsmPhysicsBinding.Selection selection = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(Map.of(),
                        Map.of("pre_parallel1", ownPose),
                        Map.of("player.pre_parallel_0", List.of("pre_parallel1")),
                        Map.of("pre_parallel1", builtInSpring),
                        List.of("LongHair")));

        assertNull(selection.animation(),
                "the model's own pre_parallel1 is a pose, and the model's own file is the one that plays");
        assertTrue(selection.isEmpty());
    }

    /**
     * The documented meaning of an empty bone table: the caller has no bone table to cross-check
     * against, so the candidate is accepted on the animation's own evidence - the behaviour before
     * the cross-check existed. Pinned because the alternative reading (an empty table means
     * nothing matches) would silently turn the feature off for every caller that forgets to pass
     * one.
     */
    @Test
    void withoutABoneTableTheCandidateIsAcceptedOnItsOwnEvidence() {
        Fixture fixture = loadDefaultControllers();

        YsmPhysicsBinding.Selection selection = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(Map.of(), Map.of(),
                        fixture.controllers(), fixture.animations(), List.of()));

        assertEquals("Hair_Physics", selection.animation());
        assertFalse(selection.isEmpty());
        assertEquals(selection.declared(), selection.parts().size(),
                "with no bone table, every declared bone is kept");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Fixture(Map<String, List<String>> controllers, Map<String, ScriptAnim> animations) {}

    /**
     * The controller table and animation table the way {@code YsmModelPackage} builds them:
     * controller name -&gt; the animations its states play, and animation name -&gt; parsed
     * animation. Reimplemented here on purpose - a test that called the production parser
     * would pass even if that parser read the wrong key, which is the one thing the real
     * files exist to catch.
     */
    private static Fixture loadDefaultControllers() {
        JsonObject controllers = readJson(CONTROLLER_RESOURCE);
        Map<String, List<String>> table = new LinkedHashMap<>();
        JsonObject definitions = controllers.getAsJsonObject("animation_controllers");
        for (Map.Entry<String, com.google.gson.JsonElement> controller : definitions.entrySet()) {
            JsonObject states = controller.getValue().getAsJsonObject().getAsJsonObject("states");
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, com.google.gson.JsonElement> state : states.entrySet()) {
                for (com.google.gson.JsonElement entry
                        : state.getValue().getAsJsonObject().getAsJsonArray("animations")) {
                    names.add(entry.isJsonPrimitive()
                            ? entry.getAsString()
                            : entry.getAsJsonObject().keySet().iterator().next());
                }
            }
            table.put(controller.getKey(), names);
        }
        return new Fixture(table, loadAnimations(HAIR_RESOURCE));
    }

    /**
     * The {@code simulatableBones} list of a golden bone fixture: the bones of a real model that
     * carry geometry themselves or have a descendant that does. It is the set the rule cross-checks
     * a candidate animation against, so a fixture that lost it would turn the cross-check off and
     * make every assertion about refusals vacuous.
     */
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

    private static ScriptAnim.Channel channel(float time, String expression) {
        ScriptAnim.Channel channel = new ScriptAnim.Channel();
        ScriptAnim.Key key = new ScriptAnim.Key();
        key.time = time;
        key.post = ScriptAnim.Value.ofExpr(expression, expression, expression);
        channel.keys.add(key);
        return channel;
    }

    private static ScriptAnim.BoneChannels channels(String rotation, String position) {
        ScriptAnim.BoneChannels channels = new ScriptAnim.BoneChannels();
        if (rotation != null) {
            channels.rotation = channel(0.0F, rotation);
        }
        if (position != null) {
            channels.position = channel(0.0F, position);
        }
        return channels;
    }

    private static List<String> names(List<YsmPhysicsBinding.Part> parts) {
        List<String> names = new ArrayList<>();
        for (YsmPhysicsBinding.Part part : parts) {
            names.add(part.bone());
        }
        return names;
    }
}
