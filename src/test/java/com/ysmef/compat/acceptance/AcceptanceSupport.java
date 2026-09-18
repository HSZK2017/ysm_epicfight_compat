package com.ysmef.compat.acceptance;

import com.ysmef.compat.model.runtime.YsmDynamicBoneSolver;
import com.ysmef.compat.model.runtime.YsmPhysicsChains;
import com.ysmef.compat.model.runtime.YSMRuntimeModel;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Plumbing for the independent acceptance suite.
 *
 * <p>This package is deliberately <b>not</b> {@code com.ysmef.compat.model.runtime}. The
 * implementers' own tests live in the implementation package, where the package-private
 * helpers are reachable; a verifier that shares that access can end up restating the
 * implementation instead of checking it. Everything here therefore goes through either
 * the public API or a <b>runtime-resolved</b> call, and a missing member is an error, not
 * a skip.
 *
 * <p>Two reasons the internal calls are reflected rather than compiled:
 *
 * <ul>
 *   <li>{@code YsmPhysicsParts.selectBones} and {@code YsmPhysicsChains.build(BoneRt[])}
 *       are package-private on purpose (they are the seams the implementation tests use),
 *       and this package cannot see them.</li>
 *   <li>{@code YsmDynamicBoneSolver.update} is owned by another task and its parameter list
 *       may legitimately change while this suite is being written. Binding to it by
 *       reflection means an interface change shows up as a named acceptance failure with
 *       the actual signature printed, rather than as a compile error that stops every other
 *       teammate's {@code gradlew test}.</li>
 * </ul>
 *
 * <p>Note what this costs: a reflective call cannot be type-checked at compile time, so a
 * signature change can only be caught by running the suite. That trade is stated here
 * rather than hidden.
 */
final class AcceptanceSupport {

    private AcceptanceSupport() {}

    /** Thrown when the acceptance suite cannot reach the code it is supposed to check. */
    static final class MissingApi extends RuntimeException {
        MissingApi(String message) {
            super(message);
        }

        MissingApi(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------
    // The solver
    // ------------------------------------------------------------------

    /**
     * The {@code update} overload this harness drives, chosen by <b>shape</b> and adapted to
     * rather than asserted on.
     *
     * <p>The task that owns this method is landing a change that adds a {@code gravity}
     * parameter, so a harness that pinned the parameter count or order would be a build break
     * for everybody rather than a verification result. What is pinned instead is the
     * <i>template</i>: the parameters the harness knows the meaning of must still appear, in
     * the documented order, with the documented types, or the harness refuses to run and says
     * so. A call built by position against an unexpected signature would silently feed the
     * wrong numbers, and that is the one failure mode an acceptance harness must not have.
     */
    private static final Method UPDATE = resolveUpdate();

    private static Method resolveUpdate() {
        StringBuilder seen = new StringBuilder();
        Method best = null;
        int bestRank = -1;
        for (Method method : YsmDynamicBoneSolver.class.getDeclaredMethods()) {
            if (!method.getName().equals("update")) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            seen.append("\n  ").append(signature(method));
            if (params.length < 14 || params[0] != YsmDynamicBoneSolver.SegmentState.class
                    || params[params.length - 1] != Quaternionf.class) {
                continue;
            }
            boolean hasGravity = params[1] == float.class;
            int knobs = 0;
            while (1 + knobs < params.length && params[1 + knobs] == float.class) {
                knobs++;
            }
            boolean hasTurn = params.length - knobs == 16;
            int rank = (hasGravity ? 2 : 0) + (hasTurn ? 0 : 1);
            if (rank > bestRank) {
                bestRank = rank;
                best = method;
            }
        }
        if (best == null) {
            throw new MissingApi("YsmDynamicBoneSolver has no update(SegmentState, ..., Quaternionf)"
                    + " overload the acceptance suite can drive; declared update methods were:" + seen);
        }
        best.setAccessible(true);
        return best;
    }

    private static String signature(Method method) {
        StringBuilder out = new StringBuilder("update(");
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            out.append(i == 0 ? "" : ", ").append(params[i].getSimpleName());
        }
        return out.append(')').toString();
    }

    /**
     * The physics knobs the resolved overload takes before the pose arguments, in order, with the
     * solver constant that holds the default value for each.
     *
     * <p>This is the only thing about the signature that is allowed to move: the owning task has
     * been adding these one at a time ({@code gravity}, then {@code airDrag}), and a harness that
     * pinned the count would be a build break for everyone instead of a verification result. New
     * knobs go in this list; anything else changing shape is a hard failure.
     */
    private static final String[] LEADING_KNOBS = {"GRAVITY", "AIR_DRAG"};

    private static int leadingKnobs(Method method) {
        Class<?>[] params = method.getParameterTypes();
        int count = 0;
        while (1 + count < params.length && params[1 + count] == float.class) {
            count++;
        }
        return count;
    }

    /** Whether the resolved overload carries the body's gravity as a parameter. */
    static boolean updateTakesGravity() {
        return leadingKnobs(UPDATE) >= 1;
    }

    /**
     * One solver step, adapted to the resolved signature.
     *
     * <p>Every position is checked against the documented template before it is filled. The
     * leading physics knobs ({@link #LEADING_KNOBS}) are the only variation supported; anything
     * else throws with the real signature printed, so a further interface change shows up as a
     * named failure rather than as a plausible wrong number.
     */
    static void step(YsmDynamicBoneSolver.SegmentState state, Vector3f pivot, Vector3f restDir,
                     float lever, float frequency, float damping, float mass, float maxAngle,
                     Vector3f bodyVelocity, YsmDynamicBoneSolver.Colliders colliders,
                     float radius, boolean[] collideAgainst, float dt, Quaternionf out) {
        step(state, pivot, restDir, lever, frequency, damping, mass, maxAngle, bodyVelocity,
                colliders, radius, collideAgainst, Float.NaN, dt, out);
    }

    /**
     * The same with an explicit air-drag value, for the tests that measure what the knob does.
     *
     * <p>There is one overload too many on purpose: the tuning test has to vary one knob at a
     * time, and a knob that can only be read from a constant cannot be varied at all.
     */
    static void step(YsmDynamicBoneSolver.SegmentState state, Vector3f pivot, Vector3f restDir,
                     float lever, float frequency, float damping, float mass, float maxAngle,
                     Vector3f bodyVelocity, YsmDynamicBoneSolver.Colliders colliders,
                     float radius, boolean[] collideAgainst, float airDrag, float dt, Quaternionf out) {
        Class<?>[] params = UPDATE.getParameterTypes();
        Object[] args = new Object[params.length];
        int at = 0;
        args[at] = state;
        expect(params, at, YsmDynamicBoneSolver.SegmentState.class);
        at++;
        int knobs = leadingKnobs(UPDATE);
        if (knobs > LEADING_KNOBS.length) {
            throw new MissingApi("YsmDynamicBoneSolver.update takes " + knobs + " leading float"
                    + " knobs and this harness knows " + LEADING_KNOBS.length + " ("
                    + String.join(", ", LEADING_KNOBS) + "); add the new one to"
                    + " AcceptanceSupport#LEADING_KNOBS and to the report's arithmetic."
                    + " Signature: " + signature(UPDATE));
        }
        for (int i = 0; i < knobs; i++) {
            expect(params, at, float.class);
            float value = constant(LEADING_KNOBS[i], i == 0 ? 24.0F : 0.9F);
            if (i == 1 && Float.isFinite(airDrag)) {
                value = airDrag;
            }
            args[at] = value;
            at++;
        }
        at = fill(params, args, at, Vector3f.class, pivot, "pivot");
        at = fill(params, args, at, Vector3f.class, restDir, "restDir");
        at = fill(params, args, at, float.class, lever, "lever");
        at = fill(params, args, at, float.class, frequency, "frequency");
        at = fill(params, args, at, float.class, damping, "damping");
        at = fill(params, args, at, float.class, mass, "mass");
        at = fill(params, args, at, float.class, maxAngle, "maxAngle");
        at = fill(params, args, at, Vector3f.class, bodyVelocity, "bodyVelocity");
        at = fill(params, args, at, YsmDynamicBoneSolver.Colliders.class, colliders, "colliders");
        at = fill(params, args, at, float.class, radius, "segmentRadius");
        at = fill(params, args, at, boolean[].class, collideAgainst, "collideAgainst");
        if (params.length - at == 3) {
            // The turn overload: yaw rate and yaw acceleration, both zero here - this suite
            // measures the translational dynamics.
            at = fill(params, args, at, float.class, 0.0F, "bodyYawRate");
            at = fill(params, args, at, float.class, 0.0F, "bodyYawAccel");
        }
        at = fill(params, args, at, float.class, dt, "dt");
        at = fill(params, args, at, Quaternionf.class, out, "out");
        if (at != params.length) {
            throw new MissingApi("the acceptance harness cannot fill " + signature(UPDATE)
                    + ": it ran out of template after " + at + " parameters. Add the new parameter"
                    + " to AcceptanceSupport#step and to the arithmetic in the report.");
        }
        try {
            UPDATE.invoke(YsmDynamicBoneSolver.INSTANCE, args);
        } catch (ReflectiveOperationException e) {
            throw new MissingApi("YsmDynamicBoneSolver.update could not be invoked", e);
        }
    }

    private static void expect(Class<?>[] params, int at, Class<?> expected) {
        if (at >= params.length || params[at] != expected) {
            throw new MissingApi("the acceptance harness expects " + expected.getSimpleName()
                    + " at position " + at + " of " + signature(UPDATE) + " but found "
                    + (at < params.length ? params[at].getSimpleName() : "nothing"));
        }
    }

    private static int fill(Class<?>[] params, Object[] args, int at, Class<?> expected,
                            Object value, String name) {
        expect(params, at, expected);
        args[at] = value;
        if (args[at] == null && expected.isPrimitive()) {
            throw new MissingApi("no value for the primitive parameter '" + name + "' at position " + at);
        }
        return at + 1;
    }

    /** A numeric constant of the solver, read reflectively so a rename cannot break the build. */
    static float constant(String name, float fallback) {
        try {
            Field field = YsmDynamicBoneSolver.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getFloat(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Selection helpers
    // ------------------------------------------------------------------

    private static final Method CHAIN_BUILD = resolve("YsmPhysicsChains", "build",
            YSMRuntimeModel.BoneRt[].class, IntPredicate.class);

    private static final Method SELECT_BONES = resolve("YsmPhysicsParts", "selectBones",
            YSMRuntimeModel.BoneRt[].class, IntPredicate.class, int.class, int[].class);

    private static Method resolve(String ownerSimpleName, String name, Class<?>... params) {
        Class<?> owner;
        try {
            owner = Class.forName("com.ysmef.compat.model.runtime." + ownerSimpleName);
        } catch (ClassNotFoundException e) {
            throw new MissingApi(ownerSimpleName + " is not on the test classpath", e);
        }
        try {
            Method method = owner.getDeclaredMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            StringBuilder seen = new StringBuilder();
            for (Method candidate : owner.getDeclaredMethods()) {
                if (candidate.getName().equals(name)) {
                    seen.append("\n  ").append(candidate);
                }
            }
            throw new MissingApi(ownerSimpleName + "." + name + " does not exist with the expected "
                    + "parameter list; same-named methods declared:" + seen, e);
        }
    }

    /** {@code YsmPhysicsChains.build(BoneRt[], IntPredicate)} - the classification over a table. */
    @SuppressWarnings("unchecked")
    static List<YsmPhysicsChains.Chain> chains(YSMRuntimeModel.BoneRt[] bones, IntPredicate carriesGeometry) {
        try {
            return (List<YsmPhysicsChains.Chain>) CHAIN_BUILD.invoke(null, bones, carriesGeometry);
        } catch (ReflectiveOperationException e) {
            throw new MissingApi("YsmPhysicsChains.build threw", e.getCause());
        }
    }

    /** {@code YsmPhysicsParts.selectBones(BoneRt[], IntPredicate, int, int[])} - the cap rule. */
    @SuppressWarnings("unchecked")
    static List<Integer> selectBones(YSMRuntimeModel.BoneRt[] bones, IntPredicate carriesGeometry,
                                     int cap, int[] droppedOut) {
        try {
            return (List<Integer>) SELECT_BONES.invoke(null, bones, carriesGeometry, cap, droppedOut);
        } catch (ReflectiveOperationException e) {
            throw new MissingApi("YsmPhysicsParts.selectBones threw", e.getCause());
        }
    }

    private static final Method CHAIN_ALLOWANCE = resolve("YsmPhysicsParts", "chainAllowance",
            float.class, float.class, float.class, int.class);

    private static final Method CHAIN_LIMIT_FOR = resolve("YsmPhysicsParts", "chainLimitFor",
            int.class, float.class);

    /**
     * {@code YsmPhysicsParts.chainLimitFor(joints, maxAnglePerJoint)} - how far a whole piece of
     * {@code joints} joints may bend.
     *
     * <p>The frame path takes two steps, and a test that skips the first one measures the wrong
     * thing: the per-joint allowance is shared out of the <b>piece total</b>, not out of the
     * per-joint limit. Calling {@code chainAllowance} with the per-joint sixty was this suite's own
     * first mistake, and it passed only because the per-joint floor happened to sit exactly on the
     * assertion's boundary.
     */
    static float chainLimitFor(int joints, float maxAnglePerJoint) {
        try {
            return (Float) CHAIN_LIMIT_FOR.invoke(null, joints, maxAnglePerJoint);
        } catch (ReflectiveOperationException e) {
            throw new MissingApi("YsmPhysicsParts.chainLimitFor threw", e.getCause());
        }
    }

    /**
     * {@code YsmPhysicsParts.chainAllowance(ownLimit, chainLimit, usedByAncestors, jointsLeft)} -
     * how much of a chain's swing budget one joint may still use.
     */
    static float chainAllowance(float ownLimit, float chainLimit, float usedByAncestors,
                                int jointsLeft) {
        try {
            return (Float) CHAIN_ALLOWANCE.invoke(null, ownLimit, chainLimit, usedByAncestors,
                    jointsLeft);
        } catch (ReflectiveOperationException e) {
            throw new MissingApi("YsmPhysicsParts.chainAllowance threw", e.getCause());
        }
    }

    /**
     * The per-joint allowances of one piece, in joint order, taking both steps of the frame path.
     *
     * @param joints           how many resolved segments the piece has
     * @param rootLimit        the base's own limit, radians
     * @param strandLimit      every joint below the base's own limit, radians
     */
    static float[] pieceAllowances(int joints, float rootLimit, float strandLimit) {
        float total = chainLimitFor(joints, strandLimit);
        float[] out = new float[joints];
        float used = 0.0F;
        for (int joint = 0; joint < joints; joint++) {
            float own = joint == 0 ? rootLimit : strandLimit;
            out[joint] = chainAllowance(own, total, used, joints - joint);
            used += out[joint];
        }
        return out;
    }

    /** A bone table entry; {@code mapped} is Epic Fight's own joint assignment. */
    static YSMRuntimeModel.BoneRt bone(String name, int joint, int parent, boolean mapped) {
        YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
        bone.name = name;
        bone.joint = joint;
        bone.parent = parent;
        bone.mapped = mapped;
        return bone;
    }

    static IntPredicate carrying(int... indices) {
        return index -> {
            for (int candidate : indices) {
                if (candidate == index) {
                    return true;
                }
            }
            return false;
        };
    }

    static String names(YSMRuntimeModel.BoneRt[] bones, List<Integer> indices) {
        StringBuilder builder = new StringBuilder("[");
        for (int index : indices) {
            builder.append(builder.length() == 1 ? "" : ", ").append(bones[index].name);
        }
        return builder.append(']').toString();
    }

    static String chainNames(List<YsmPhysicsChains.Chain> chains) {
        StringBuilder builder = new StringBuilder("[");
        for (YsmPhysicsChains.Chain chain : chains) {
            builder.append(builder.length() == 1 ? "" : ", ").append(chain.boneName());
        }
        return builder.append(']').toString();
    }

    // ------------------------------------------------------------------
    // Source access, for the structural checks
    // ------------------------------------------------------------------

    /**
     * The source root of the main source set.
     *
     * <p>Resolved rather than hard-coded, and an absence is an error: a structural check that
     * cannot find its file and quietly passes is worse than no check at all.
     *
     * <p>An override is honoured from {@code -Dysmef.acceptance.sourceRoot=<java source root>}
     * or, failing that, from the {@code YSMEF_ACCEPTANCE_SOURCE_ROOT} environment variable. The
     * environment variable exists because Gradle does not forward the build JVM's system
     * properties to the test JVM: without it, the suite could not be pointed at a mutated copy
     * of a source file, and the mutation evidence for these checks would not be reproducible.
     */
    static Path mainSource(String relativePath) {
        String override = System.getProperty("ysmef.acceptance.sourceRoot", "");
        if (override.isEmpty()) {
            override = System.getenv("YSMEF_ACCEPTANCE_SOURCE_ROOT");
            if (override == null) {
                override = "";
            }
        }
        if (!override.isEmpty()) {
            Path candidate = Paths.get(override).resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            throw new MissingApi("source root override '" + override
                    + "' does not contain " + relativePath);
        }
        Path directory = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int up = 0; up < 4 && directory != null; up++) {
            Path candidate = directory.resolve("src/main/java").resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new MissingApi("could not locate src/main/java/" + relativePath
                + " from " + System.getProperty("user.dir")
                + "; pass -Dysmef.acceptance.sourceRoot=<project dir> to point the suite at it");
    }

    static String read(String relativePath) {
        try {
            return new String(Files.readAllBytes(mainSource(relativePath)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new MissingApi("could not read " + relativePath, e);
        }
    }

    /**
     * A file under the workspace root (the directory that holds {@code src} and {@code tmp_verify}),
     * or null when it is not there.
     *
     * <p>Used by the real-model fixture, which lives outside the test resources on purpose: it is
     * data exported from this machine's game install, and copying it into
     * {@code src/test/resources} would put a 300 kB machine-specific file into the repository.
     * A caller that gets null must skip loudly rather than pass.
     */
    static Path workspaceFile(String relativePath) {
        String override = System.getProperty("ysmef.acceptance.workspace", "");
        if (override.isEmpty()) {
            override = System.getenv("YSMEF_ACCEPTANCE_WORKSPACE");
            if (override == null) {
                override = "";
            }
        }
        if (!override.isEmpty()) {
            Path candidate = Paths.get(override).resolve(relativePath);
            return Files.isRegularFile(candidate) ? candidate : null;
        }
        Path directory = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int up = 0; up < 4 && directory != null; up++) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    /**
     * The body of a method, found by signature prefix and delimited by brace matching.
     *
     * <p>Braces inside comments, string literals and character literals are skipped, because a
     * {@code "{"} in a log message would otherwise end the body early and every check made on it
     * would be made on the wrong text.
     */
    static String methodBody(String source, String signaturePrefix) {
        int start = source.indexOf(signaturePrefix);
        if (start < 0) {
            throw new MissingApi("no method starting with '" + signaturePrefix + "' in the source");
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            throw new MissingApi("method '" + signaturePrefix + "' has no body");
        }
        String body = bodyAt(source, open);
        if (body == null) {
            throw new MissingApi("could not delimit the body of '" + signaturePrefix + "'");
        }
        return body;
    }

    /**
     * The body of the (single) method whose signature contains {@code signatureNeedle} and
     * whose body contains {@code bodyNeedle}.
     *
     * <p>Used where a check is about a <i>role</i> rather than a name - the swing limit is "the
     * method that projects the direction back inside {@code maxAngle}" - so that renaming the
     * method does not silently disable the check. If nothing matches, this throws: a check
     * that cannot find its subject must not pass.
     */
    static String methodBodyContaining(String source, String signatureNeedle, String bodyNeedle) {
        java.util.regex.Matcher declarations = DECLARATION.matcher(source);
        StringBuilder seen = new StringBuilder();
        while (declarations.find()) {
            String signature = declarations.group();
            if (!signature.contains(signatureNeedle)) {
                continue;
            }
            int open = source.indexOf('{', declarations.start());
            String body = bodyAt(source, open);
            seen.append("\n  ").append(signature.trim());
            if (body != null && stripComments(body).contains(bodyNeedle)) {
                return body;
            }
        }
        throw new MissingApi("no method with '" + signatureNeedle + "' in its signature and '"
                + bodyNeedle + "' in its body; candidates considered:" + seen);
    }

    private static final java.util.regex.Pattern DECLARATION = java.util.regex.Pattern.compile(
            "(?m)^[ \\t]+(?:[\\w<>\\[\\],.]+[ \\t]+)+\\w+[ \\t]*\\([^()]*\\)[ \\t]*\\{");

    /** The body delimited by the braces starting at {@code open}, or null when there is none. */
    static String bodyAt(String source, int open) {
        if (open < 0 || open >= source.length() || source.charAt(open) != '{') {
            return null;
        }
        int depth = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean inTextBlock = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (inTextBlock) {
                if (c == '"' && next == '"' && i + 2 < source.length() && source.charAt(i + 2) == '"') {
                    inTextBlock = false;
                    i += 2;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '"' && next == '"' && i + 2 < source.length() && source.charAt(i + 2) == '"') {
                inTextBlock = true;
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                for (int j = i + 1; j < source.length(); j++) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j++;
                        continue;
                    }
                    if (d == quote) {
                        i = j;
                        break;
                    }
                    if (d == '\n') {
                        i = j;
                        break;
                    }
                }
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        return null;
    }

    /** Whether a text contains {@code needle} outside of any comment; used by the structural checks. */
    static boolean codeContains(String source, String needle) {
        return stripComments(source).contains(needle);
    }

    /** The source with {@code //} and block comments removed, string literals left in place. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The 1-based line number of the first occurrence of {@code needle}, or -1. */
    static int lineOf(String source, String needle) {
        int at = source.indexOf(needle);
        if (at < 0) {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Every line of {@code text} that contains {@code needle}.
     *
     * <p>Exists so a check can ask what a symbol is <i>used for</i> rather than whether it occurs:
     * a flag printed in a log line and a flag branched on are the same two words, and a guard that
     * cannot tell them apart is a guard that fails on log messages.
     */
    static List<String> linesContaining(String text, String needle) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.contains(needle)) {
                out.add(line);
            }
        }
        return out;
    }

    static float degrees(double radians) {
        return (float) Math.toDegrees(radians);
    }

    static float radians(double degrees) {
        return (float) Math.toRadians(degrees);
    }

    static float angleBetween(Vector3f a, Vector3f b) {
        return YsmDynamicBoneSolver.angleBetween(a, b);
    }

    static float length(Vector3f v) {
        return v.length();
    }
}
