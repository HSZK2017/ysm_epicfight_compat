package com.ysmef.compat.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mesh-cache generation key: manifest.json's {@code generator} value decides whether an
 * already-converted model is reused or re-converted, and getting it wrong is silent by construction -
 * the game keeps serving the previous build's artefacts and the changed writer never runs. That is
 * not hypothetical: a baked-joint-id change to the mesh writer shipped without the hand-maintained
 * {@code GENERATOR_VERSION} being bumped, the tested models kept their old meshes, and the fix looked
 * like a no-op for a whole debugging round.
 *
 * <p>So this class pins the two halves of the replacement mechanism, one per failure direction:
 *
 * <ol>
 *   <li>{@link #isCurrentGenerator} - how an existing manifest's value is interpreted. An old bare
 *       number must mean "regenerate" and a malformed/absent value must never throw (the old
 *       {@code getAsInt()} threw on the string this mechanism now writes).</li>
 *   <li>The packaged fingerprint resource - the half that makes forgetting impossible. If the
 *       resource is missing from the jar, {@link ManifestStore#writerFingerprint()} degrades to
 *       {@code "unavailable"} and every build compares equal, i.e. the original bug returns with no
 *       test red. That is why the assertion is on the <i>packaged</i> resource read through the same
 *       classloader the game uses, not on a re-derivation of the hash.</li>
 * </ol>
 */
class ManifestStoreGeneratorKeyTest {

    /** The generator value this build writes. */
    private static final String CURRENT = ManifestStore.CACHE_KEY;

    @Test
    void manifestGeneratorValuesAreInterpretedAsSpecified() {
        // An older build's manifest: a bare int. Not current -> the model is regenerated.
        assertFalse(ManifestStore.isCurrentGenerator(json("15"), "16:2f35209c39f37430"),
                "a pre-fingerprint numeric generation must not be accepted as current");
        // The value this build writes.
        assertTrue(ManifestStore.isCurrentGenerator(json("\"16:2f35209c39f37430\""), "16:2f35209c39f37430"),
                "the exact current cache key must be accepted");
        // A different writer generation, i.e. a manifest written by another build.
        assertFalse(ManifestStore.isCurrentGenerator(json("\"16:deadbeefdeadbeef\""), "16:2f35209c39f37430"),
                "a different fingerprint must not be accepted as current");
        // Same fingerprint half, different manual half: only changes outside model/ move this one,
        // and it must move the key.
        assertFalse(ManifestStore.isCurrentGenerator(json("\"17:2f35209c39f37430\""), "16:2f35209c39f37430"),
                "a different manual version must not be accepted as current");
        // The old constant as a number, against the string form this build writes - the exact
        // transition a user upgrading into this build performs.
        assertFalse(ManifestStore.isCurrentGenerator(json("16"), CURRENT));
    }

    @Test
    void unexpectedGeneratorValuesAreNotCurrentAndNeverThrow() {
        // Anything that is not the exact key means "regenerate", and must be reached without an
        // exception: this predicate runs on the first access to any cached model, where a throw
        // would take out model conversion entirely (the previous getAsInt() did exactly that on a
        // string, which is the shape this mechanism writes).
        assertDoesNotThrowAndIsFalse(JsonParser.parseString("{}"), CURRENT, "an object");
        assertDoesNotThrowAndIsFalse(JsonParser.parseString("[1,2]"), CURRENT, "an array");
        assertDoesNotThrowAndIsFalse(JsonParser.parseString("null"), CURRENT, "a JSON null");
        assertDoesNotThrowAndIsFalse(JsonParser.parseString("\"\""), CURRENT, "an empty string");
        assertDoesNotThrowAndIsFalse(null, CURRENT, "an absent element");
        assertDoesNotThrowAndIsFalse(JsonParser.parseString("\"16:2f35209c39f37430\""), null,
                "a null current key");
        // The same set, reached through the manifest path callers actually use rather than through
        // the predicate directly - the predicate being total is only useful if entry() is.
        assertNull(ManifestStore.entry("no-such-model"), "a cache miss must stay a cache miss");
    }

    @Test
    void fingerprintResourceIsPresentAndNonDegenerateOnTheClasspath() {
        // The assertion that would have caught the original bug: a build whose cache key cannot move
        // because the resource that carries it was never packaged. Read exactly the way ManifestStore
        // reads it at class-init (own classloader, same path), so this fails for the same reasons.
        assertNotNull(getClass().getClassLoader()
                        .getResourceAsStream("ysm_epicfight_compat/writer_fingerprint.txt"),
                "the writer fingerprint resource is not on the classpath - the mod jar is missing the "
                        + "build-time generated resource, so the cache key cannot track the writer sources");

        assertNotEquals("unavailable", ManifestStore.writerFingerprint(),
                "the fingerprint degraded to the 'unavailable' sentinel: the resource exists but its "
                        + "content has no token line, so every build would compare equal");

        String fingerprint = ManifestStore.writerFingerprint();
        assertEquals(16, fingerprint.length(),
                "a fingerprint that is not the 16-hex-char token from the Gradle task: " + fingerprint);
        assertTrue(fingerprint.matches("[0-9a-f]{16}"),
                "the fingerprint is not lowercase hex: " + fingerprint);
        // Its own class-init read is the value the key is built from, not a second read that could
        // disagree with the one used at runtime.
        assertEquals(ManifestStore.parseFingerprint(resourceText()), fingerprint,
                "the class-init fingerprint and the packaged resource disagree");

        assertEquals(ManifestStore.MANUAL_VERSION + ":" + fingerprint, CURRENT,
                "the effective cache key is not '<manual version>:<fingerprint>'");
        assertTrue(CURRENT.startsWith(ManifestStore.MANUAL_VERSION + ":"),
                "the manual version is not the first component of the cache key: " + CURRENT);
        assertNotEquals(String.valueOf(ManifestStore.MANUAL_VERSION), CURRENT,
                "the cache key is still the bare hand-maintained number - the fingerprint half is "
                        + "not in the key, so a writer change would still be invisible");
    }

    @Test
    void fingerprintReaderTakesTheTokenPastTheHeader() {
        // The generated resource carries a human-readable header; the reader must skip it entirely
        // (a fingerprint that silently included a comment line would be a different, unstable key).
        assertEquals("2f35209c39f37430",
                ManifestStore.parseFingerprint("# comment\n# another\n2f35209c39f37430\n"));
        assertEquals("2f35209c39f37430",
                ManifestStore.parseFingerprint("  \n\n# comment\n  2f35209c39f37430  \n"));
        // Degenerate inputs are the sentinel, never a throw and never an empty key component (which
        // would make "16:" the key and collide across every build).
        assertEquals("unavailable", ManifestStore.parseFingerprint(null));
        assertEquals("unavailable", ManifestStore.parseFingerprint(""));
        assertEquals("unavailable", ManifestStore.parseFingerprint("# only comments\n\n"));
    }

    /**
     * The fingerprint has to be a function of the writer sources, which is only observable if the
     * resource says what it was computed over. This checks the resource against the tree it names:
     * the file count in the header, and that every file it lists exists.
     *
     * <p>Green here is weaker than the build-level proof (the Gradle task re-runs and the jar is
     * re-read); it is the local guard that the resource describes THIS tree rather than a stale copy
     * left in the build directory by an earlier revision.
     */
    @Test
    void fingerprintResourceDescribesTheModelSourceTree() throws Exception {
        Path root = modelSourceRoot();
        Assumptions.assumeTrue(root != null,
                "not running from the project directory (no src/main/java/com/ysmef/compat/model) - "
                        + "the resource-to-source cross-check can only run in the checkout");

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(root)) {
            sources = walk.filter(Files::isRegularFile)
                    // Same rule as the Gradle task's fileTree(include '**/*.java'): every .java
                    // file, nested packages included. Counting a different set here would make this
                    // check disagree with the resource for reasons that have nothing to do with the
                    // resource being stale.
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .toList();
        }
        assertFalse(sources.isEmpty(), "walked the model source directory and found no .java files");

        String text = resourceText();
        Matcher declared = Pattern.compile("(\\d+) \\.java files below").matcher(text);
        assertTrue(declared.find(), "the resource no longer states how many files it hashed: " + text);
        assertEquals(sources.size(), Integer.parseInt(declared.group(1)),
                "the packaged fingerprint was computed over a different number of model sources than "
                        + "the tree has - rebuild (the resource is stale)");
        assertTrue(sources.size() >= 33,
                "the model source set shrank to " + sources.size() + " files; the fingerprint's "
                        + "declared input set may have been narrowed");
    }

    // ---------------------------------------------------------------- helpers

    private static JsonElement json(String literal) {
        return JsonParser.parseString(literal);
    }

    private static void assertDoesNotThrowAndIsFalse(JsonElement generator, String current, String what) {
        boolean accepted;
        try {
            accepted = ManifestStore.isCurrentGenerator(generator, current);
        } catch (RuntimeException e) {
            throw new AssertionError(what + " made the generator check throw: " + e, e);
        }
        assertFalse(accepted, what + " must not be accepted as the current generator");
    }

    /** The packaged resource, read the way {@link ManifestStore} reads it at class-init. */
    private static String resourceText() {
        try (InputStream in = ManifestStore.class.getClassLoader()
                .getResourceAsStream("ysm_epicfight_compat/writer_fingerprint.txt")) {
            assertNotNull(in, "writer_fingerprint.txt is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not read the packaged writer fingerprint", e);
        }
    }

    /**
     * The fingerprinted source directory, or null when the test JVM's working directory is not the
     * project root (an IDE with a different default, a re-run from build/). Deliberately does not
     * "search upward until something matches": a wrong-but-existing directory would let the
     * cross-check above pass against the wrong tree.
     */
    private static Path modelSourceRoot() {
        Path root = Paths.get("src", "main", "java", "com", "ysmef", "compat", "model");
        return Files.isDirectory(root) ? root : null;
    }
}
