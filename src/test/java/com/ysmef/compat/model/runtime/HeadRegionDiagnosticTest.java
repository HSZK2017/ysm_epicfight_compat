package com.ysmef.compat.model.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The head-region diagnostic's own contract: read-only, bounded, once per model, behind the same flag
 * as the leg one, and printing the same fields for a piece.
 *
 * <p>Why this is a test rather than a review note. The diagnostic is the only instrument this project
 * has for "the piece is drawn where the pose never put it" - the in-game report that found the
 * thigh defect came out of the leg line, and the head line is the one this round adds for the
 * reported top-of-head hair. Every property below is one whose loss is <b>silent</b>: a diagnostic
 * that writes a mesh transform changes what is drawn, one that is unbounded floods the log every
 * model reload, and one that forgets its once-set prints on every frame. None of those throws, and
 * none of them shows up in a picture of the model - which is exactly why they are pinned here.
 *
 * <p>It reads the source, because there is nothing else to read: the method only runs inside a game
 * with a {@code YSMMesh}, an armature and a pose, and a check that cannot run is worth less than a
 * check on the shape of the code that will run. What it cannot establish is that the numbers the
 * line prints are the right ones - that is what
 * {@code HeadRegionPitchProbeTest} measures offline and what the in-game line is read for.
 */
class HeadRegionDiagnosticTest {

    private static final String RELATIVE =
            "com/ysmef/compat/model/runtime/YsmMeshSecondaryMotion.java";

    @Test
    void theHeadRegionDiagnosticIsReadOnlyBoundedAndOncePerModel() throws IOException {
        String source = source();
        String body = methodBody(source, "private static void logHeadRegion(");

        assertFalse(body.contains("setRuntimeTransformAt"),
                "the head diagnostic writes a mesh transform: a diagnostic that can change what is "
                        + "drawn is not a diagnostic");
        assertFalse(body.contains(".identity()") || body.contains(".set("),
                "the head diagnostic mutates a matrix; every value it prints has to come from what "
                        + "the frame already computed");
        assertTrue(body.contains("HEAD_DIAG_MAX_PIECES"),
                "the head diagnostic has no bound: the reported model has more than a hundred bones "
                        + "carrying geometry on the head joint and this line would print every one");
        assertTrue(body.contains("segmentOfBone.containsKey("),
                "the head diagnostic no longer separates the simulated pieces from the rigid ones, "
                        + "so its bound can spend every row on a mouth or an eye and never reach the "
                        + "hair it exists for");

        String apply = methodBody(source, "public static void apply(");
        assertTrue(apply.contains("HEAD_REGION_LOGGED.add(model.modelId)"),
                "the head diagnostic is no longer once per model: it would print every frame");
        assertTrue(apply.contains("YsmDiag.isEnabled()"),
                "the head diagnostic is no longer behind the diagnostic flag");
        assertTrue(body.contains("catch (Throwable"),
                "the head diagnostic can throw out of a frame: it runs after the mesh is written and "
                        + "a person is the only reader");
    }

    @Test
    void itPrintsTheSameFieldsAsTheLegRegionForAPiece() throws IOException {
        String source = source();
        String body = methodBody(source, "private static void logHeadRegion(");

        assertTrue(body.contains("legPieceRow("),
                "the head diagnostic builds its own row instead of the leg one's: the fields it "
                        + "prints are the contract, and a second copy of them is how the two drift");
        assertTrue(body.contains("legJointRow("),
                "the head diagnostic no longer prints the joint rows, so the Chest's and the Head's "
                        + "own pose rotations - the pitch the region is asked about - are missing");
        assertTrue(body.contains("HEAD_JOINTS"),
                "the head diagnostic does not iterate the chest-and-head joints it is named for");

        String row = methodBody(source, "private static String legPieceRow(");
        for (String field : new String[]{"pivot ", "own geom y ", "centroid ", " L ", "rest ",
                "upShare ", "pivotGap ", "longAxis bind ", "after pose ", "after pose+delta ",
                "joint pose "}) {
            assertTrue(row.contains(field),
                    "the piece row no longer prints '" + field.trim() + "', which is one of the "
                            + "fields the head and leg diagnostics share");
        }
        String verdict = methodBody(source, "private static String legPieceRow(");
        assertTrue(verdict.contains("wrapsPivot") && verdict.contains("risesOffPivot"),
                "the piece row no longer names which of the two pivot rules dropped a rigid piece, "
                        + "so 'it is not simulated' has no reason beside it");
    }

    /** The main source file, found by walking up from the test working directory. */
    private static String source() throws IOException {
        Path directory = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int up = 0; up < 4 && directory != null; up++) {
            Path candidate = directory.resolve("src/main/java").resolve(RELATIVE);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new IOException("could not locate src/main/java/" + RELATIVE + " from "
                + System.getProperty("user.dir"));
    }

    /** The body of the first method whose signature starts with {@code prefix}, by brace matching. */
    private static String methodBody(String source, String prefix) {
        int start = source.indexOf(prefix);
        assertTrue(start >= 0, "no method starting with '" + prefix + "' in the source");
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, "method '" + prefix + "' has no body");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        throw new IllegalStateException("method '" + prefix + "' has no end");
    }
}
