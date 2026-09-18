package com.ysmef.compat.acceptance;

import com.ysmef.compat.model.runtime.YsmDynamicBoneSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural acceptance for the three changes that cannot be observed from a pure-Java test.
 *
 * <h2>Why these are structural, and what that costs</h2>
 *
 * <p>The three claims below are about <b>which code path runs</b>, and the code paths need a
 * game to run: {@code YsmMeshSecondaryMotion.apply} takes an Epic Fight skinned mesh (which
 * owns a VBO) and a live {@code LivingEntity}, and {@code YsmPhysicsParts.build} needs the
 * same mesh to measure levers and radii. There is no honest way to instantiate those on a
 * test classpath, so the guarantee is taken from the source text instead.
 *
 * <p>The cost is stated plainly: a structural check is satisfied by the code it forbids being
 * moved, renamed or reformatted, and it can be satisfied by dead code. It is strictly weaker
 * than the behavioural checks in {@link SolverDynamicsAcceptanceTest}, and it is used here
 * only where nothing stronger exists. Two things keep it from being decoration:
 *
 * <ul>
 *   <li>Every check is expressed as an assertion about the <i>body of a named method</i>,
 *       extracted by brace matching, so a forbidden call somewhere else in the file does
 *       not trigger it and a forbidden call in the method does not escape it.</li>
 *   <li>If the method or the file cannot be found, the check <b>throws</b> rather than
 *       passing. A structural check that silently stops looking is the failure mode that
 *       makes people distrust all of them.</li>
 * </ul>
 *
 * <p>The mutation evidence for these three lives in {@code tmp_verify/T5_verification_report.md}:
 * because a verifier may not edit {@code src/main/java}, the mutant is a copy of the file with
 * the removal put back, and the same assertions are run against it through
 * {@code -Dysmef.acceptance.sourceRoot=}.
 */
class ImplementationPathAcceptanceTest {

    private static final String SECONDARY_MOTION =
            "com/ysmef/compat/model/runtime/YsmMeshSecondaryMotion.java";
    private static final String SOLVER =
            "com/ysmef/compat/model/runtime/YsmDynamicBoneSolver.java";
    private static final String PARTS =
            "com/ysmef/compat/model/runtime/YsmPhysicsParts.java";

    /**
     * The claim: an authored physics animation no longer takes over the transform.
     *
     * <p>T3's contract is that {@code Source.AUTHORED} changes only <i>where the part list and
     * the spring parameters come from</i>, and that the motion is integrated by the same
     * pendulum for every model. The defect was a single branch at the top of {@code apply()}:
     * when the model declared a physics animation, the whole solver was skipped and the
     * author's expression angles were written straight into the mesh. That is the "stiff card
     * flung around and snapped home" in the report, because an expression is a scalar filter
     * with no lever, no inertia, no mass and no collision.
     */
    @Test
    @DisplayName("apply() has no authored take-over: no applyAuthored call and no AUTHORED-guarded return")
    void theAuthoredPathDoesNotTakeOverTheTransform() {
        String source = AcceptanceSupport.read(SECONDARY_MOTION);
        String body = AcceptanceSupport.methodBody(source, "void apply(");
        String code = AcceptanceSupport.stripComments(body);

        assertFalse(code.contains("applyAuthored"),
                "apply() still calls applyAuthored, which writes the author's expression angles"
                        + " straight into the mesh instead of letting the solver integrate them."
                        + " applyAuthored is at line " + AcceptanceSupport.lineOf(source, "applyAuthored"));

        Pattern guardedReturn = Pattern.compile(
                "if\\s*\\([^;]{0,240}?AUTHORED[^;]{0,240}?\\)\\s*\\{?\\s*return");
        Matcher matcher = guardedReturn.matcher(code);
        assertFalse(matcher.find(),
                "apply() still returns early on Source.AUTHORED, so a model that declares a physics"
                        + " animation never reaches the solver. The guard is at line "
                        + AcceptanceSupport.lineOf(source, "YsmPhysicsParts.Source.AUTHORED"));

        assertTrue(code.contains("YsmDynamicBoneSolver"),
                "apply() no longer reaches the solver at all, which would mean secondary motion is"
                        + " computed somewhere this check cannot see");
        assertTrue(code.contains("setRuntimeTransformAt"),
                "apply() no longer writes a transform into the mesh, so nothing it computes can be"
                        + " visible");
    }

    /**
     * The same claim from the other side: the solver's interface cannot distinguish the two
     * sources, so the two paths cannot have different dynamics.
     *
     * <p>This one is stronger than it looks. "Both sources go through one solver" is only
     * true if the solver is not told which source it is serving; a {@code Source} or
     * {@code authored} parameter would let a later change branch inside the integration and
     * the structural check above would never see it.
     */
    @Test
    @DisplayName("the solver's update() cannot tell AUTHORED from BONE_NAMES")
    void theSolverCannotSeeTheSource() {
        Method update = null;
        for (Method method : YsmDynamicBoneSolver.class.getDeclaredMethods()) {
            if (method.getName().equals("update")
                    && method.getParameterTypes()[0] == YsmDynamicBoneSolver.SegmentState.class) {
                update = method;
                break;
            }
        }
        assertTrue(update != null, "YsmDynamicBoneSolver.update(SegmentState, ...) disappeared");

        for (Parameter parameter : update.getParameters()) {
            String type = parameter.getType().getName();
            assertFalse(type.contains("YsmPhysicsParts") || type.endsWith("Source")
                            || type.equals("boolean"),
                    "the solver takes a '" + parameter.getName() + "' of type " + type
                            + ", so it can behave differently for an authored part than for a"
                            + " name-classified one. The two paths are supposed to be the same"
                            + " dynamics with different parameters.");
        }
    }

    /**
     * The claim: the outside-force ceiling is gone from the integrator (T2).
     *
     * <p>The design being removed bounded the sum of gravity, the pivot's fictitious force,
     * the centrifugal and Euler terms and the drag to a fixed fraction of the spring's own
     * authority, and scaled that bound down with the piece's swing limit. Its effect is
     * visible in the shipped log as a whole skirt sitting between 18.8 and 20.0 degrees with
     * the player standing still: the ceiling, not the physics, decided the angle. The
     * assertion is that neither the constant nor the scaling against it survives in code -
     * the long explanatory comment may stay or go, which is why comments are stripped first.
     */
    @Test
    @DisplayName("the external-force ceiling is gone from the integrator")
    void theExternalForceCeilingIsGone() {
        String source = AcceptanceSupport.read(SOLVER);
        String code = AcceptanceSupport.stripComments(source);

        // \b so that a marker such as EXTERNAL_AUTHORITY_REMOVED does not count as the living
        // constant: the name of a thing that was deleted is not the thing.
        assertNoIdentifier(code, "EXTERNAL_AUTHORITY",
                "YsmDynamicBoneSolver still uses EXTERNAL_AUTHORITY, the ceiling that held every"
                        + " panel of a real skirt at its stop while the player stood still");
        assertNoIdentifier(code, "REFERENCE_ANGLE",
                "the ceiling's calibration angle REFERENCE_ANGLE is still used; it exists only to"
                        + " scale that ceiling");
    }

    private static void assertNoIdentifier(String code, String identifier, String message) {
        Matcher matcher = Pattern.compile("\\b" + identifier + "\\b").matcher(code);
        if (matcher.find()) {
            throw new AssertionError(message + "; the offending code reads: "
                    + context(code, matcher.start()));
        }
    }

    private static String context(String code, int at) {
        int from = Math.max(0, at - 60);
        int to = Math.min(code.length(), at + 60);
        return "... " + code.substring(from, to).replace('\n', ' ').replaceAll("\\s+", " ").trim() + " ...";
    }

    /**
     * The claim: the swing limit is a constraint, not an energy sink (T2).
     *
     * <p>{@code clampToRest} used to multiply the angular velocity by a quarter on every
     * frame the piece sat at its stop. A constraint removes the component of the velocity
     * that drives the piece into the limit and leaves the rest - a piece pinned against a
     * limit under a sideways pull slides along it. Scaling <i>all</i> of the velocity is not
     * a constraint; it is a brake, and it is what "snapped home" is made of.
     */
    @Test
    @DisplayName("the limit projection removes a component instead of scaling the whole velocity")
    void theLimitDoesNotBrakeTheWholeVelocity() {
        String source = AcceptanceSupport.read(SOLVER);
        // Found by role, not by name: the swing limit is the method that rotates the direction
        // back by maxAngle itself. "maxAngle in the signature and rotateAxis somewhere in the
        // body" is not specific enough - collision resolution also takes a budget derived from
        // maxAngle and also rotates - and a check that inspects the wrong method is worse than
        // no check, because it reports on code nobody asked about.
        String body = AcceptanceSupport.stripComments(
                AcceptanceSupport.methodBodyContaining(source, "maxAngle", "rotateAxis(maxAngle"));

        Matcher scaled = Pattern.compile("angularVelocity\\s*\\.\\s*(mul|scale)\\s*\\(\\s*[0-9]")
                .matcher(body);
        assertFalse(scaled.find(),
                "the limit projection scales the whole angular velocity by a constant ('"
                        + (scaled.reset().find() ? scaled.group() : "mul(<literal>)") + "'). That is"
                        + " a brake, not a constraint: a piece that is merely resting against its"
                        + " limit loses energy it should keep, and the snap it produces is what the"
                        + " in-game report called 'flung away and home again'.");

        assertTrue(Pattern.compile("angularVelocity\\s*\\.\\s*(fma|dot)\\s*\\(").matcher(body).find(),
                "the limit projection never touches a component of the angular velocity, so the"
                        + " piece is frozen at its limit rather than sliding along it. A constraint"
                        + " must remove only the component along the constraint direction.");
    }

    /**
     * The claim: an authored part list is no longer hard-truncated at the cap (T3).
     *
     * <p>Cutting a flat list at the cap is the authored counterpart of splitting a panel: the
     * bones past the cut simply stop being simulated, which for a garment is some panels
     * moving and some bolted down, and the count that was capped is not a number anybody can
     * see. The check is deliberately narrow - it looks for the truncation itself rather than
     * for how the fix was written.
     */
    @Test
    @DisplayName("build() does not truncate the authored part list with subList(0, cap)")
    void theAuthoredPartListIsNotHardTruncated() {
        String source = AcceptanceSupport.read(PARTS);
        String body = AcceptanceSupport.stripComments(AcceptanceSupport.methodBody(source, "Model build("));

        Pattern truncation = Pattern.compile("subList\\s*\\([^)]*cap\\s*\\)");
        Matcher matcher = truncation.matcher(body);
        assertFalse(matcher.find(),
                "build() still truncates a list at the cap (line "
                        + AcceptanceSupport.lineOf(source, "subList")
                        + "): '" + (matcher.reset().find() ? matcher.group() : "subList(0, cap)")
                        + "'. Whole pieces have to be dropped and counted, not cut.");
    }

    /**
     * The claim: the classes a pure-Java test can reach still have no Minecraft in them.
     *
     * <p>The project's unit tests run without a game, and they reach the dynamics, the
     * classifiers and the tuning directly. Referencing a {@code net.minecraft} type from one
     * of them does not break the compile - the whole Minecraft classpath is there - it breaks
     * the <i>run</i>, and only when the referencing class is initialised, which is the kind of
     * failure that appears as an unrelated test error.
     */
    @Test
    @DisplayName("the pure-Java core has no net.minecraft references")
    void thePureJavaCoreStaysFreeOfMinecraft() {
        String[] pure = {
                "com/ysmef/compat/model/runtime/YsmDynamicBoneSolver.java",
                "com/ysmef/compat/model/runtime/YsmPhysicsParts.java",
                "com/ysmef/compat/model/runtime/YsmPhysicsChains.java",
                "com/ysmef/compat/model/runtime/YsmSecondOrder.java",
                "com/ysmef/compat/model/runtime/YsmPhysicsTuning.java",
                "com/ysmef/compat/model/runtime/YsmBodyColliders.java",
                "com/ysmef/compat/model/runtime/YsmPhysicsBinding.java",
        };
        StringBuilder offenders = new StringBuilder();
        for (String file : pure) {
            String code = AcceptanceSupport.stripComments(AcceptanceSupport.read(file));
            if (code.contains("net.minecraft")) {
                offenders.append("\n  ").append(file)
                        .append(" (line ").append(AcceptanceSupport.lineOf(code, "net.minecraft"))
                        .append(')');
            }
        }
        assertTrue(offenders.length() == 0,
                "these classes are reached by tests that run without a game and now reference"
                        + " Minecraft, which will fail at class-initialisation time rather than at"
                        + " compile time:" + offenders);
    }
}
