package com.ysmef.compat.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the mixin package against holding anything that is not a mixin.
 *
 * <p>Mixin claims the whole {@code com.ysmef.compat.mixin} package named by
 * {@code ysm_epicfight_compat.mixins.json}. A non-mixin class in that package may
 * compile, load and even be called from Java - and then fails only when an injected
 * method body touches it, because the code Mixin splices into a target class is
 * subject to a rule ordinary code is not:
 *
 * <pre>
 * IllegalClassLoadError: com.ysmef.compat.mixin.X is in a defined mixin package
 * com.ysmef.compat.mixin.* owned by ysm_epicfight_compat.mixins.json and cannot be
 * referenced directly
 * </pre>
 *
 * <p>That failure mode is nastier than it looks: it fires at the moment the target
 * method runs, not at startup, so the mod appears to load cleanly (the mixin even
 * reports as successfully applied) and then hard-crashes the game the first time the
 * patched path executes. It happened exactly once here, with a helper class shared by
 * two paperdoll mixins, and cost a crash report to find.
 *

 * <p>This test deliberately lives outside the package it guards: a class here cannot be
 * mistaken for a mixin by its own rule.
 * * <p>Checking the source tree rather than the compiled jar keeps this honest: the rule
 * is about where a class is written, and a source-level check needs no build step to
 * be meaningful.
 */
class MixinPackageContractTest {

    /** Where mixin classes live; mirrored in the mixin config's {@code package}. */
    private static final String MIXIN_PACKAGE_PATH = "src/main/java/com/ysmef/compat/mixin";

    /**
     * Every {@code .java} file directly in the mixin package must be a mixin.
     *
     * <p>The rule is applied to files directly in the package (and its subpackages),
     * matching how Mixin scopes the package: a class placed there is reached by
     * injected code the same way, so moving a helper into a subpackage would not
     * help.
     */
    @Test
    void everyClassInTheMixinPackageIsAMixin() throws IOException {
        Path root = locate(MIXIN_PACKAGE_PATH);
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) {
                    continue;
                }
                String simple = name.substring(0, name.length() - ".java".length());
                if (!simple.endsWith("Mixin")) {
                    offenders.add(root.relativize(file).toString().replace('\\', '/'));
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "these classes sit in the mixin package but are not mixins, so any injected code "
                        + "referencing them will crash the game the first time the patched method runs: "
                        + offenders);
    }

    /**
     * The mixin package must not be empty and must contain at least the two paperdoll
     * mixins, so a silently wrong working directory cannot make the check above pass
     * by finding nothing.
     */
    @Test
    void theCheckActuallySeesTheMixinSources() throws IOException {
        Path root = locate(MIXIN_PACKAGE_PATH);
        long count;
        try (Stream<Path> files = Files.walk(root)) {
            count = files.filter(p -> p.getFileName().toString().endsWith("Mixin.java")).count();
        }
        assertTrue(count > 20,
                "expected to find this mod's mixin sources under " + root + ", found " + count
                        + " - the test is looking in the wrong place and would pass vacuously");
    }

    /** Resolve a path relative to the project directory, wherever the test is run from. */
    private static Path locate(String relative) {
        Path direct = Paths.get(relative);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        // Gradle runs tests with the project directory as the working directory, but
        // fall back to walking up so the check is not silently skipped.
        Path here = Paths.get("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path resolved = candidate.resolve(relative);
            if (Files.isDirectory(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("could not locate " + relative + " from " + here);
    }
}
