package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ysmef.compat.ysm.script.ScriptAnim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the bones a YSM model author actually wired to physics, by reading the model's
 * <b>animation controllers</b> and then the animations those controllers play.
 *
 * <h2>Why names are not enough</h2>
 *
 * <p>The classifier in {@link YsmPhysicsChains} guesses from bone names ("hair", "tail",
 * "qun"), and a name is a guess about someone else's model: it accepts a lock of hair an
 * author posed by hand and misses a strand called {@code FLongRightHair} that the author
 * did wire up. The model already <i>says</i> which bones are physics driven, in the one
 * place a YSM author has to write it down - the controller that plays the physics
 * animation.
 *
 * <h2>The mechanism, as the bundled default controller states it</h2>
 *
 * <p>YSM ships a default controller whose first entry is
 * {@code player.pre_parallel_0}. Its single state plays the animations
 * {@code ["Hair_Physics", "pre_parallel1", ...]}, and {@code Hair_Physics} is an
 * animation whose {@code bones} map names every physics-driven bone of the model
 * ({@code BackHairA1}, {@code MaWei_LeftA2}, ...), each with a rotation expression built
 * from YSM's own physics functions:
 *
 * <pre>{@code
 * "rotation": [
 *   "ysm.second_order('头发垂直', math.clamp(-5*q.vertical_speed+v.hv,-10,150), 1.5, 0.6, 0)+...",
 *   0,
 *   "0.6*math.abs(ysm.second_order('头发角速度', math.clamp(0.05*q.yaw_speed,-80,80), 1.2, 0.3, 0))"
 * ]
 * }</pre>
 *
 * <p>So the evidence is in the file, and it is of two kinds, both of which this class
 * reads:
 *
 * <ul>
 *   <li><b>The controller binds the animation.</b> A controller state's {@code animations}
 *       list, or a name in YSM's continuously-evaluated {@code parallel*} family, is what
 *       makes an animation a candidate at all - an animation nothing plays says nothing.</li>
 *   <li><b>The animation uses a physics function.</b> {@code ysm.second_order} and
 *       {@code ysm.first_order} are YSM's spring filters (a second-order filter is what
 *       "secondary motion" means); {@code ysm.bone_rot} is how one segment is driven from
 *       the segment above it, which is what makes a chain a chain rather than a fan.</li>
 * </ul>
 *
 * <h2>The chain, recovered</h2>
 *
 * <p>A bone's expression usually drives it from another bone's already-simulated rotation
 * - directly ({@code ysm.bone_rot('BackHairB1')}) or through a timeline variable
 * ({@code v.HP_x_0 = ysm.bone_rot('BackHairA1').x}, then {@code v.HP_x = v.HP_x_0 - ...},
 * then a rotation of {@code 10*ysm.second_order('A1x增量', v.HP_x, 1.7, 0.5, 0)}). Both
 * forms are read here, and the variable hop is followed, because resolving only the
 * direct form recovers about half the links on real models. What comes out is
 * {@link Part#follows()}: the bone this one hangs off, or empty for a segment driven
 * straight from the body.
 *
 * <h2>The tuning, recovered</h2>
 *
 * <p>{@code ysm.second_order(name, input, frequency, coefficient, response)} carries the
 * author's own numbers: {@code frequency} is how fast the segment settles and
 * {@code coefficient} is its damping. Those are read as the simulation's defaults for
 * that bone, so a model tuned to be stiff stays stiff and a long tail stays floppy -
 * instead of one global constant applied to every model ever made.
 *
 * <h2>The second controller table: YSM's built-in default set</h2>
 *
 * <p>A model's own {@code animation_controllers} file is not the only place its physics is
 * bound, and on most real models it is not where it is bound at all. YSM ships a controller
 * package of its own ({@code builtin/misc/4_default_controllers}) whose {@code player.pre_parallel_0}
 * plays {@code Hair_Physics}, and every model inherits it: the maid's own controller file
 * contains a single {@code player.post_main} state and nothing else, and yet {@code Hair_Physics}
 * is exactly the animation that would be asked to drive her. Reading only the model's own
 * controller file therefore does not make the class stricter - it makes it blind, and every
 * model falls back to the very name guess this class exists to replace.
 *
 * <p>Both tables are read here, the model's first: an animation the model defines itself
 * shadows the built-in one of the same name, which is what Bedrock name resolution does.
 *
 * <h2>Only the bones the model actually has</h2>
 *
 * <p>A candidate animation is not evidence until it drives something. The maid shares exactly
 * one bone with {@code Hair_Physics} - {@code ElytraLocator}, a locator every YSM skeleton
 * carries - and that bone has no geometry and no children, so a part list built from that match
 * would name one bone the mesh cannot move. Because an authored part list <i>replaces</i> the
 * name-based classification rather than adding to it, accepting it would take the model from
 * fifty-nine simulated bones to none: the detection would look like it had finally worked while
 * the hair hung rigid. So a candidate must drive at least one bone that exists on this model
 * <b>and carries geometry in its own subtree</b>; otherwise the next candidate is tried, and if
 * none qualifies the model declares nothing and the caller's fallback stands. That is why
 * {@link Sources#bones()} is the set of simulatable bones rather than every name in the file.
 *
 * <p>Everything here is pure: no Minecraft types, no I/O, no state. The caller supplies
 * the controller tables, the animation tables and the model's simulatable bone names,
 * which is what makes the rules testable against real model data.
 */
public final class YsmPhysicsBinding {

    /**
     * Molang calls that mark an animation as physics-driven.
     *
     * <p>{@code second_order}/{@code first_order} are YSM's spring filters;
     * {@code bone_rot}/{@code bone_pos} mean the write depends on another bone's
     * simulated value, i.e. the animation is a chain rather than a pose.
     */
    private static final String[] PHYSICS_CALLS = {
            "ysm.second_order(", "ysm.first_order(", "ysm.bone_rot(", "ysm.bone_pos("
    };

    /**
     * Animation-name fragments that mark a physics animation even when its expressions
     * were written as plain keyframes. Deliberately narrow: "physics" is the word the
     * bundled default controller uses ({@code Hair_Physics}), and a name test that is
     * any looser starts claiming locomotion animations.
     */
    private static final String[] PHYSICS_NAME_HINTS = {"physics", "_phys", "phys_"};

    /** Frequencies are clamped by YSM to 0..5 (see its SecondOrder); a sane spring lives below this. */
    private static final double MAX_FREQUENCY = 5.0;
    /** YSM clamps the damping coefficient to 0..1. */
    private static final double MAX_COEFFICIENT = 1.0;

    /** Fallbacks when an expression carries no readable numbers. */
    public static final double DEFAULT_FREQUENCY = 1.2;
    public static final double DEFAULT_COEFFICIENT = 0.6;
    public static final double DEFAULT_RESPONSE = 0.0;

    /**
     * One physics-driven bone.
     *
     * @param bone        the YSM bone name, exactly as the animation spells it
     * @param follows     the physics bone this one is driven from, or empty when it is
     *                    driven straight from the body; used to recover the chain
     * @param frequency   the author's spring frequency (1/s), defaulted when unreadable
     * @param coefficient the author's damping coefficient (0..1), defaulted when unreadable
     * @param response    the author's response term, which is a velocity feed-forward
     * @param filtered    true when a spring call was actually found on this bone, false
     *                    when the bone was accepted from a sibling's evidence alone
     */
    public record Part(String bone, String follows, double frequency, double coefficient,
                       double response, boolean filtered) {

        public Part {
            follows = follows == null || follows.isEmpty() ? "" : follows;
        }
    }

    private YsmPhysicsBinding() {}

    /**
     * Everything the discovery reads, gathered in one value so a caller converts a model once
     * and the rule stays a pure function of its input.
     *
     * @param controllers        the model's own controllers: controller name -&gt; the animation
     *                           names its states play
     * @param animations         every animation of the model's own package, by name (not only
     *                           the runtime-relevant subset: the physics animation is usually
     *                           excluded from that subset)
     * @param builtinControllers YSM's built-in default controllers, in the same shape; empty
     *                           when that package is unavailable, and then the model's own
     *                           tables are all there is
     * @param builtinAnimations  the animations of that built-in package, consulted only for a
     *                           name the model does not define itself
     * @param bones              the model's <b>simulatable</b> bone names - the bones that carry
     *                           geometry themselves or have a descendant that does. Empty means
     *                           the caller has no bone table to cross-check against, and the
     *                           candidate is then accepted on the animation's own evidence
     *                           (the behaviour before the cross-check existed)
     */
    public record Sources(Map<String, List<String>> controllers,
                          Map<String, ScriptAnim> animations,
                          Map<String, List<String>> builtinControllers,
                          Map<String, ScriptAnim> builtinAnimations,
                          Collection<String> bones) {

        public Sources {
            controllers = controllers == null ? Map.of() : controllers;
            animations = animations == null ? Map.of() : animations;
            builtinControllers = builtinControllers == null ? Map.of() : builtinControllers;
            builtinAnimations = builtinAnimations == null ? Map.of() : builtinAnimations;
            bones = bones == null ? List.of() : bones;
        }

        /**
         * A model's own tables only - no built-in candidates and no bone cross-check, i.e. exactly
         * the wiring that made every model fall back to its bone names.
         *
         * <p>Named for what it leaves out rather than offered as a convenient short overload: a
         * caller with a model on disk almost certainly wants {@link #of} instead, and one that
         * reaches for this by habit re-creates the blindness this class was changed to fix.
         */
        public static Sources modelOnly(Map<String, ? extends Collection<String>> controllers,
                                        Map<String, ? extends ScriptAnim> animations) {
            return new Sources(copyControllers(controllers), copyAnimations(animations),
                    Map.of(), Map.of(), List.of());
        }

        /** A model's own tables plus YSM's built-in set, cross-checked against its simulatable bones. */
        public static Sources of(Map<String, ? extends Collection<String>> controllers,
                                 Map<String, ? extends ScriptAnim> animations,
                                 Map<String, List<String>> builtinControllers,
                                 Map<String, ScriptAnim> builtinAnimations,
                                 Collection<String> bones) {
            return new Sources(copyControllers(controllers), copyAnimations(animations),
                    builtinControllers, builtinAnimations, bones);
        }
    }

    /**
     * The outcome of reading a model's candidates.
     *
     * @param animation the first candidate accepted - the animation the physics is written in
     *                  - or null when the model declares none
     * @param parts     the physics bones, in first-seen order, restricted to bones the model can
     *                  actually move; empty when nothing was accepted
     * @param declared  how many bones the accepted candidates drive in total before the model's own
     *                  bone table is applied, or - when nothing was accepted - how many the first
     *                  refused candidate drives, so the log can say "none of its 58 bones"
     * @param matched   how many bones the model ends up simulating, i.e. the size of {@code parts};
     *                  zero is why a candidate is refused, and it is read off the list so the two
     *                  can never disagree
     * @param rejected  a one-line reason naming the first rejected candidate, or empty
     */
    public record Selection(String animation, List<Part> parts, int declared, int matched, String rejected) {

        public Selection {
            parts = List.copyOf(parts);
            rejected = rejected == null ? "" : rejected;
        }

        /** Whether the model declared nothing and the caller's own fallback has to stand. */
        public boolean isEmpty() {
            return parts.isEmpty();
        }
    }

    /**
     * The physics-driven bones of a model.
     *
     * @return the parts, in first-seen order; empty when the model declares no physics
     */
    public static List<Part> discover(Sources sources) {
        return select(sources).parts();
    }

    /**
     * The physics-driven bones of a model, plus how the choice was made.
     *
     * <p>The rule, in one place, so the decision can be tested without a model package on disk:
     * walk the candidates in order, keep the ones that are evidence of physics and that drive at
     * least one bone this model can move, and merge their bones. The first accepted one names the
     * animation; a candidate that drives nothing of this model is skipped rather than accepted,
     * and the reason is reported instead of being swallowed - a silent refusal here is
     * indistinguishable from a model that authored nothing.
     */
    public static Selection select(Sources sources) {
        List<Part> parts = new ArrayList<>();
        java.util.Set<String> known = new java.util.HashSet<>();
        boolean crossCheck = !sources.bones().isEmpty();
        java.util.Set<String> bones = crossCheck ? new java.util.HashSet<>(sources.bones()) : java.util.Set.of();

        String animation = null;
        int declared = 0;
        int matched = 0;
        String rejected = "";
        for (String name : candidateAnimations(sources)) {
            ScriptAnim anim = resolve(name, sources);
            if (anim == null || !looksLikePhysics(name, anim)) {
                continue;
            }
            List<Part> collected = collectParts(anim);
            List<Part> accepted = crossCheck ? retain(collected, bones) : collected;
            if (crossCheck && accepted.isEmpty()) {
                // Only the first refusal is worth reporting, and only while nothing has been
                // accepted: a candidate refused after one was accepted must not overwrite the
                // numbers of the animation that was actually chosen.
                if (animation == null && rejected.isEmpty()) {
                    declared = collected.size();
                    matched = 0;
                    rejected = name + " (drives " + collected.size()
                            + " bone(s), none of which this model has geometry for)";
                }
                continue;
            }
            if (animation == null) {
                animation = name;
            }
            declared += collected.size();
            for (Part part : accepted) {
                if (known.add(part.bone())) {
                    parts.add(part);
                }
            }
        }
        // The part list is the answer, so the count is read off it rather than accumulated: a bone
        // two accepted animations both drive is one bone, and a count that disagreed with the list
        // would make the log argue with the simulation.
        return new Selection(animation, parts, declared, parts.size(), rejected);
    }

    /**
     * The name of the animation the model's physics is written in, or null when it declares none.
     *
     * <p>Recorded beside the parts so the runtime can read the author's expressions instead of
     * only knowing which bones they move - a physics rig without its animation is a list of bones
     * with nothing driving them.
     */
    public static String physicsAnimationName(Sources sources) {
        return select(sources).animation();
    }

    /** The animations worth inspecting: everything a controller plays - the model's own first,
     * then YSM's built-in set - plus the model's continuously-evaluated {@code parallel*} family.
     *
     * <p>The controller route is the point of the class, and the built-in table is half of it:
     * a model that inherits the default controllers never names {@code Hair_Physics} itself,
     * which is the common case rather than the exception.
     *
     * <p>The {@code parallel*} route is kept because a model can be authored without controllers
     * at all - the astronaut wine fox defines its physics in an animation literally called
     * {@code pre_parallel0} and relies on YSM's built-in binding of that name - so dropping it
     * would lose the physics of every such model while looking like a stricter rule.
     */
    static List<String> candidateAnimations(Sources sources) {
        List<String> ordered = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        addPlayed(ordered, seen, sources.controllers());
        for (String name : sources.animations().keySet()) {
            if (isParallelFamily(name) && seen.add(name)) {
                ordered.add(name);
            }
        }
        addPlayed(ordered, seen, sources.builtinControllers());
        return ordered;
    }

    private static void addPlayed(List<String> ordered, java.util.Set<String> seen,
                                  Map<String, List<String>> controllers) {
        for (List<String> names : controllers.values()) {
            if (names == null) {
                continue;
            }
            for (String name : names) {
                if (name != null && !name.isEmpty() && seen.add(name)) {
                    ordered.add(name);
                }
            }
        }
    }

    /**
     * The definition of a candidate animation: the model's own if it has one, otherwise YSM's
     * built-in one. A model that defines its own {@code pre_parallel1} means it - the built-in
     * copy is a default, not an override.
     */
    private static ScriptAnim resolve(String name, Sources sources) {
        ScriptAnim own = sources.animations().get(name);
        return own != null ? own : sources.builtinAnimations().get(name);
    }

    /** The collected parts that name a bone the model can move. */
    private static List<Part> retain(List<Part> parts, java.util.Set<String> bones) {
        List<Part> out = new ArrayList<>(parts.size());
        for (Part part : parts) {
            if (bones.contains(part.bone())) {
                out.add(part);
            }
        }
        return out;
    }

    private static boolean isParallelFamily(String name) {
        return name != null && (name.startsWith("pre_parallel") || name.startsWith("parallel"));
    }

    private static Map<String, List<String>> copyControllers(Map<String, ? extends Collection<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (in != null) {
            for (Map.Entry<String, ? extends Collection<String>> entry : in.entrySet()) {
                out.put(entry.getKey(), entry.getValue() == null ? List.of() : new ArrayList<>(entry.getValue()));
            }
        }
        return out;
    }

    private static Map<String, ScriptAnim> copyAnimations(Map<String, ? extends ScriptAnim> in) {
        Map<String, ScriptAnim> out = new LinkedHashMap<>();
        if (in != null) {
            out.putAll(in);
        }
        return out;
    }

    /**
     * Whether this animation drives bones with YSM's physics functions (or is named as a
     * physics animation).
     *
     * <p>Package-private so the rule can be tested against a hand-written animation
     * without building a whole model package.
     */
    static boolean looksLikePhysics(String animationName, ScriptAnim anim) {
        if (anim == null) {
            return false;
        }
        if (animationName != null) {
            String lower = animationName.toLowerCase(Locale.ROOT);
            for (String hint : PHYSICS_NAME_HINTS) {
                if (lower.contains(hint)) {
                    return true;
                }
            }
        }
        for (String code : expressionsOf(anim)) {
            if (containsPhysicsCall(code)) {
                return true;
            }
        }
        for (ScriptAnim.Timeline timeline : anim.timelines) {
            for (String code : timeline.code) {
                if (containsPhysicsCall(code)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsPhysicsCall(String code) {
        if (code == null) {
            return false;
        }
        for (String call : PHYSICS_CALLS) {
            if (code.contains(call)) {
                return true;
            }
        }
        return false;
    }

    /** Every molang expression of an animation, in bone then channel then key then axis order. */
    private static List<String> expressionsOf(ScriptAnim anim) {
        List<String> out = new ArrayList<>();
        for (ScriptAnim.BoneChannels channels : anim.bones.values()) {
            appendExpressions(out, channels.rotation);
            appendExpressions(out, channels.position);
            appendExpressions(out, channels.scale);
        }
        return out;
    }

    private static void appendExpressions(List<String> out, ScriptAnim.Channel channel) {
        if (channel == null) {
            return;
        }
        for (ScriptAnim.Key key : channel.keys) {
            appendValue(out, key.post);
            appendValue(out, key.pre);
        }
    }

    private static void appendValue(List<String> out, ScriptAnim.Value value) {
        if (value == null) {
            return;
        }
        for (int axis = 0; axis < 3; axis++) {
            if (value.expr[axis] != null) {
                out.add(value.expr[axis]);
            }
        }
    }

    /** The physics bones of one animation, collected on their own so they can be counted first. */
    static List<Part> collectParts(ScriptAnim anim) {
        List<Part> parts = new ArrayList<>();
        collect(anim, parts);
        return parts;
    }

    /**
     * Append the physics bones of one candidate animation.
     *
     * <p>Two rules, in two passes, and both of them are about the <b>rotation</b> channel -
     * which is the only channel a pendulum can drive.
     *
     * <ol>
     *   <li><b>A bone whose own rotation is a spring is physics.</b> This is the direct
     *       declaration, and it carries the author's frequency with it.</li>
     *   <li><b>A bone that rotates with another physics bone is physics too.</b> Real models
     *       drive a whole fan of strands from one filtered value -
     *       {@code 10*ysm.second_order('A1x增量', v.HP_x, ...)} where {@code v.HP_x} is the
     *       root's rotation - and those strands carry no spring call of their own. The
     *       second pass follows those links, repeatedly, so a chain of followers is
     *       recovered rather than only its first link.</li>
     * </ol>
     *
     * <p><b>What is deliberately not accepted.</b> A bone driven on its <i>position</i>
     * channel only is left out. That looks like an omission and is not: the astronaut model
     * declares its eyelid bases that way ({@code ysm.second_order('眼睛yaw',
     * ysm.head_yaw/180, 1, 0.8, 0)} on {@code position}), which is an eye that lags the
     * head, and rotating a bone about its pivot is the wrong motion for it. A bone whose
     * rotation channel is a bare script ({@code v.hv=0;v.hg=0;...}, as the astronaut's
     * {@code molang} placeholder bone is) is left out for the same reason - it has no
     * rotation to simulate, it exists to set variables.
     */
    private static void collect(ScriptAnim anim, List<Part> parts) {
        if (!looksLikePhysics(anim.name, anim)) {
            return;
        }
        Map<String, String> varSource = variableBoneSources(anim);
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Part part : parts) {
            known.add(part.bone());
        }

        // Pass one: the bones that declare a spring on their own rotation.
        Map<String, String> links = new LinkedHashMap<>();
        Map<String, double[]> tuningByBone = new LinkedHashMap<>();
        for (Map.Entry<String, ScriptAnim.BoneChannels> entry : anim.bones.entrySet()) {
            String bone = entry.getKey();
            if (bone == null || bone.isEmpty() || known.contains(bone) || entry.getValue() == null) {
                continue;
            }
            ScriptAnim.Channel rotation = entry.getValue().rotation;
            if (rotation == null || rotation.keys.isEmpty()) {
                continue;
            }
            List<String> rotationCodes = new ArrayList<>();
            appendExpressions(rotationCodes, rotation);
            List<String> allCodes = new ArrayList<>(rotationCodes);
            appendExpressions(allCodes, entry.getValue().position);
            appendExpressions(allCodes, entry.getValue().scale);
            links.put(bone, readFollows(allCodes, varSource));
            double[] tuning = readTuning(rotationCodes);
            if (tuning != null) {
                tuningByBone.put(bone, tuning);
            }
        }

        // Pass two: followers of an accepted bone, to a fixed point so a chain of them is
        // recovered rather than only its first link.
        java.util.Set<String> accepted = new java.util.HashSet<>(tuningByBone.keySet());
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (Map.Entry<String, String> link : links.entrySet()) {
                if (accepted.contains(link.getKey())) {
                    continue;
                }
                String driver = link.getValue();
                if (!driver.isEmpty() && (accepted.contains(driver) || tuningByBone.containsKey(driver))) {
                    accepted.add(link.getKey());
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        for (String bone : links.keySet()) {
            if (!accepted.contains(bone) || !known.add(bone)) {
                continue;
            }
            double[] tuning = tuningByBone.get(bone);
            boolean filtered = tuning != null;
            if (tuning == null) {
                tuning = new double[]{DEFAULT_FREQUENCY, DEFAULT_COEFFICIENT, DEFAULT_RESPONSE};
            }
            parts.add(new Part(bone, links.get(bone), tuning[0], tuning[1], tuning[2], filtered));
        }
    }

    // ------------------------------------------------------------------
    // Expression reading
    // ------------------------------------------------------------------

    /**
     * The first spring call's {@code (frequency, coefficient, response)}, or null when the
     * bone has no spring call of its own.
     *
     * <p>Only the first is read: an expression like the wine fox's chains three
     * {@code second_order} calls for pitch, droop and yaw, and each of them is a
     * different axis of the same bone. Taking the first keeps the bone's stiffness in the
     * range the author wrote rather than an average of three unrelated springs.
     */
    static double[] readTuning(List<String> codes) {
        for (String code : codes) {
            double[] found = readTuning(code);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static double[] readTuning(String code) {
        if (code == null) {
            return null;
        }
        int at = indexOfPhysicsCall(code);
        if (at < 0) {
            return null;
        }
        int open = code.indexOf('(', at);
        if (open < 0) {
            return null;
        }
        List<String> args = splitArguments(code, open);
        // (name, input, frequency, coefficient, response) for second_order;
        // (name, input, response) for first_order.
        boolean secondOrder = code.startsWith("ysm.second_order", at);
        double frequency = DEFAULT_FREQUENCY;
        double coefficient = DEFAULT_COEFFICIENT;
        double response = DEFAULT_RESPONSE;
        if (secondOrder) {
            frequency = numberOr(args, 2, DEFAULT_FREQUENCY);
            coefficient = numberOr(args, 3, DEFAULT_COEFFICIENT);
            response = numberOr(args, 4, DEFAULT_RESPONSE);
        } else {
            // first_order's third argument is its response (a time constant, in seconds).
            response = numberOr(args, 2, DEFAULT_RESPONSE);
        }
        if (!Double.isFinite(frequency) || frequency < 0.0) {
            frequency = DEFAULT_FREQUENCY;
        }
        frequency = Math.min(frequency, MAX_FREQUENCY);
        if (!Double.isFinite(coefficient) || coefficient < 0.0) {
            coefficient = DEFAULT_COEFFICIENT;
        }
        coefficient = Math.min(coefficient, MAX_COEFFICIENT);
        if (!Double.isFinite(response)) {
            response = DEFAULT_RESPONSE;
        }
        return new double[]{frequency, coefficient, response};
    }

    private static int indexOfPhysicsCall(String code) {
        int best = -1;
        for (String call : new String[]{"ysm.second_order", "ysm.first_order"}) {
            int at = code.indexOf(call);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static double numberOr(List<String> args, int index, double fallback) {
        if (index < 0 || index >= args.size()) {
            return fallback;
        }
        String raw = args.get(index).trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            // A pure literal is the only form worth trusting here: the argument may
            // equally be a molang expression, and evaluating one is the animator's job.
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Split the argument list of the call whose opening parenthesis is at {@code open}.
     *
     * <p>Commas inside nested calls, ternaries' string literals and array selectors are
     * not separators, so this counts brackets rather than splitting on every comma -
     * which on a real expression like
     * {@code second_order('x', math.clamp(-5*q.vertical_speed+v.hv,-10,150), 1.5, 0.6, 0)}
     * is the difference between three arguments and seven.
     */
    static List<String> splitArguments(String code, int open) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        StringBuilder current = new StringBuilder();
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    args.add(current.substring(1));
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args.add(current.substring(1));
                current.setLength(0);
                current.append('(');
                continue;
            }
            current.append(c);
        }
        // Unterminated: the model data is damaged, and a partial list beats throwing on
        // the render thread.
        if (current.length() > 1) {
            args.add(current.substring(1));
        }
        return args;
    }

    /**
     * The bone this one is driven from.
     *
     * <p>Two forms, both taken from real models: the direct
     * {@code ysm.second_order('x', ysm.bone_rot('BackHairB1').x, 2, 0.5, 0.2)}, and the
     * variable hop the wine fox uses - the timeline stores
     * {@code v.HP_x_0 = ysm.bone_rot('BackHairA1').x}, the next line derives
     * {@code v.HP_x = v.HP_x_0 - v.HP_x_1}, and the strand reads {@code v.HP_x}. Reading
     * only the direct form leaves every strand of that hairdo looking like a chain root.
     */
    static String readFollows(List<String> codes, Map<String, String> varSource) {
        for (String code : codes) {
            String bone = firstBoneRotationArgument(code);
            if (bone != null) {
                return bone;
            }
        }
        for (String code : codes) {
            for (String var : referencedVariables(code)) {
                String bone = varSource.get(var);
                if (bone != null) {
                    return bone;
                }
            }
        }
        return "";
    }

    /** The first {@code ysm.bone_rot('Name')} argument of an expression, or null. */
    static String firstBoneRotationArgument(String code) {
        if (code == null) {
            return null;
        }
        int at = code.indexOf("ysm.bone_rot(");
        if (at < 0) {
            return null;
        }
        int open = code.indexOf('(', at);
        List<String> args = splitArguments(code, open);
        return args.isEmpty() ? null : unquote(args.get(0));
    }

    /**
     * Map every timeline variable to the bone whose rotation it carries, following
     * variable-to-variable assignments so {@code v.HP_x = v.HP_x_0 - v.HP_x_1} still
     * names the bone behind {@code v.HP_x_0}.
     */
    static Map<String, String> variableBoneSources(ScriptAnim anim) {
        Map<String, String> direct = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        for (ScriptAnim.Timeline timeline : anim.timelines) {
            for (String code : timeline.code) {
                readTimelineStatement(code, direct, aliases);
            }
        }
        // Resolve aliases (bounded: a model's timeline is a handful of lines, and a
        // cyclic assignment must not spin here).
        Map<String, String> resolved = new HashMap<>(direct);
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                if (resolved.containsKey(entry.getKey())) {
                    continue;
                }
                String source = resolved.get(entry.getValue());
                if (source != null) {
                    resolved.put(entry.getKey(), source);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return resolved;
    }

    /**
     * Read one {@code v.x = ...;} statement, recording either the bone it reads or the
     * variable it mirrors.
     */
    private static void readTimelineStatement(String code, Map<String, String> direct, Map<String, String> aliases) {
        if (code == null) {
            return;
        }
        int eq = code.indexOf('=');
        if (eq < 0) {
            return;
        }
        // Skip the comparison operators of a conditional expression.
        if (eq + 1 < code.length() && code.charAt(eq + 1) == '=') {
            return;
        }
        String target = code.substring(0, eq).trim();
        if (!target.startsWith("v.") || target.indexOf(' ') >= 0) {
            return;
        }
        String rhs = code.substring(eq + 1);
        String bone = firstBoneRotationArgument(rhs);
        if (bone != null) {
            direct.putIfAbsent(target, bone);
            return;
        }
        for (String var : referencedVariables(rhs)) {
            if (!var.equals(target)) {
                aliases.putIfAbsent(target, var);
                return;
            }
        }
    }

    /** Every {@code v.name} reference in an expression, in first-seen order. */
    static List<String> referencedVariables(String code) {
        List<String> out = new ArrayList<>();
        if (code == null) {
            return out;
        }
        for (int i = 0; i + 1 < code.length(); i++) {
            if (code.charAt(i) != 'v' || code.charAt(i + 1) != '.') {
                continue;
            }
            if (i > 0) {
                char before = code.charAt(i - 1);
                if (Character.isLetterOrDigit(before) || before == '_' || before == '.') {
                    continue;
                }
            }
            int end = i + 2;
            while (end < code.length()) {
                char c = code.charAt(end);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    end++;
                } else {
                    break;
                }
            }
            if (end > i + 2) {
                String name = code.substring(i, Math.min(end, i + 2 + 64));
                if (!out.contains(name)) {
                    out.add(name);
                }
                i = end - 1;
            }
        }
        return out;
    }

    private static String unquote(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '\'' || first == '"') && last == first) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    // ------------------------------------------------------------------
    // Runtime JSON round-trip (written by EFMeshJsonWriter)
    // ------------------------------------------------------------------

    /** Serialize the discovered parts for the runtime JSON's {@code physics} section. */
    public static JsonObject toJson(List<Part> parts) {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (Part part : parts) {
            JsonObject obj = new JsonObject();
            obj.addProperty("bone", part.bone());
            if (!part.follows().isEmpty()) {
                obj.addProperty("follows", part.follows());
            }
            obj.addProperty("frequency", part.frequency());
            obj.addProperty("coefficient", part.coefficient());
            obj.addProperty("response", part.response());
            if (part.filtered()) {
                obj.addProperty("filtered", true);
            }
            array.add(obj);
        }
        root.add("bones", array);
        return root;
    }

    /**
     * The name of the animation the physics is written in, out of the runtime JSON's
     * {@code physics} section, or null when the section does not carry one.
     *
     * <p>Read separately from the parts because it answers a different question and a section
     * written before this field existed is still a perfectly good part list: a model converted by
     * an older build has the rig and not the animation, and the caller's answer to that is to fall
     * back to its own physics rather than to treat the section as broken.
     */
    public static String animationNameOf(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject root = element.getAsJsonObject();
        if (!root.has("animation") || !root.get("animation").isJsonPrimitive()) {
            return null;
        }
        String name = root.get("animation").getAsString();
        return name == null || name.isEmpty() ? null : name;
    }

    /** Read back what {@link #toJson} wrote; empty on any malformed entry. */
    public static List<Part> fromJson(JsonElement element) {
        List<Part> parts = new ArrayList<>();
        if (element == null || !element.isJsonObject()) {
            return parts;
        }
        JsonObject root = element.getAsJsonObject();
        if (!root.has("bones") || !root.get("bones").isJsonArray()) {
            return parts;
        }
        for (JsonElement entry : root.getAsJsonArray("bones")) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject obj = entry.getAsJsonObject();
            if (!obj.has("bone")) {
                continue;
            }
            try {
                parts.add(new Part(
                        obj.get("bone").getAsString(),
                        obj.has("follows") ? obj.get("follows").getAsString() : "",
                        obj.has("frequency") ? obj.get("frequency").getAsDouble() : DEFAULT_FREQUENCY,
                        obj.has("coefficient") ? obj.get("coefficient").getAsDouble() : DEFAULT_COEFFICIENT,
                        obj.has("response") ? obj.get("response").getAsDouble() : DEFAULT_RESPONSE,
                        obj.has("filtered") && obj.get("filtered").getAsBoolean()));
            } catch (RuntimeException ignored) {
                // A damaged entry drops itself rather than the whole model's physics.
            }
        }
        return parts;
    }

    /** Names of the parts, for the log. */
    public static String names(List<Part> parts) {
        StringBuilder builder = new StringBuilder();
        for (Part part : parts) {
            builder.append(builder.length() == 0 ? "" : ", ").append(part.bone());
        }
        return builder.toString();
    }

    /** The parts as a name-indexed map, for the callers that resolve links by name. */
    public static Map<String, Part> byName(List<Part> parts) {
        Map<String, Part> map = new LinkedHashMap<>();
        for (Part part : parts) {
            map.putIfAbsent(part.bone(), part);
        }
        return map;
    }
}
