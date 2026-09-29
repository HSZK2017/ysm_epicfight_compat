package com.ysmef.compat.ysm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The legacy generation end to end against a real install: every legacy .ysm package in
 * {@code config/yes_steve_model/custom} must load through {@link YsmModelPackage#load(String)} -
 * the same entry point the mesh conversion uses - and the version-3 packages that were modified
 * after packaging must still be reported as hash failures.
 *
 * <p>Opt-in: skipped unless the install is configured through {@code -Dysmef.golden.ysm_config_root}
 * or {@code YSMEF_YSM_CONFIG_ROOT} (the same two spellings the other install-driven tests use), and
 * skipped when the install has no legacy package at all - a vacuously green sweep would be worse
 * than a skip.
 */
class YsmLegacyModelPackageInstallTest {

    /**
     * Two version-3 packages the user confirmed YSM itself does not load either: they carry a valid
     * version-3 header and a broken file hash, i.e. they were modified after packaging. They are
     * the negative control for the diagnosis: "modified file" must stay distinguishable from
     * "container generation this reader does not know".
     */
    private static final String[] MODIFIED_AFTER_PACKAGING = {"BMW_Wukong_1.ysm", "老大.ysm"};

    @Test
    void everyLegacyPackageInTheInstallLoadsThroughThePackageLoader() throws Exception {
        Path custom = customDirectory();
        List<Path> legacyFiles = new ArrayList<>();
        try (Stream<Path> list = Files.list(custom)) {
            for (Path path : (Iterable<Path>) list::iterator) {
                if (!Files.isRegularFile(path)
                        || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm")) {
                    continue;
                }
                int container = YsmFileCrypto.containerVersion(Files.readAllBytes(path));
                if (container == YsmFileCrypto.CONTAINER_LEGACY_I || container == YsmFileCrypto.CONTAINER_LEGACY_II) {
                    legacyFiles.add(path);
                }
            }
        }
        legacyFiles.sort(Comparator.comparing(Path::toString));
        assumeTrue(!legacyFiles.isEmpty(),
                "the install at " + custom + " has no legacy .ysm package to load");

        List<String> failures = new ArrayList<>();
        int withWheelAnimations = 0;
        for (Path file : legacyFiles) {
            String modelId = file.getFileName().toString();
            try {
                YsmModelPackage pkg = YsmModelPackage.load(modelId);
                if (pkg == null) {
                    failures.add(modelId + ": load() returned null");
                    continue;
                }
                if (pkg.geometry == null || pkg.geometry.bonesByName.isEmpty()) {
                    failures.add(modelId + ": no geometry");
                }
                if (pkg.textures.isEmpty()) {
                    failures.add(modelId + ": no textures");
                }
                if (pkg.defaultTexture == null || pkg.defaultTexture.isEmpty()) {
                    failures.add(modelId + ": no default texture");
                }
                if (pkg.widthScale <= 0 || pkg.heightScale <= 0) {
                    failures.add(modelId + ": non-positive scale");
                }
                if (pkg.contentFingerprint == -1L) {
                    failures.add(modelId + ": no content fingerprint (the manifest would re-convert it forever)");
                }
                if (!pkg.extraAnimations.isEmpty()) {
                    withWheelAnimations++;
                }
            } catch (Throwable t) {
                failures.add(modelId + ": " + t);
            }
        }

        System.out.println("[ysm legacy install] " + legacyFiles.size() + " legacy packages under " + custom
                + ", failed=" + failures.size() + ", with wheel animations=" + withWheelAnimations);
        assertTrue(failures.isEmpty(), "legacy packages that do not load:\n  " + String.join("\n  ", failures));
        // The flat generation declares wheel animations in two different places and most of these
        // packages use the geometry's inline block; a reader that reads the wrong one - or neither -
        // still loads every model and simply has nothing to show on the wheel.
        assertTrue(withWheelAnimations > 0,
                "no legacy package in " + custom + " exposed wheel animations; the extra_animation_names"
                        + " extraction is not reaching real packages");
    }

    @Test
    void packagesModifiedAfterPackagingStillReportTheHashMismatch() throws Exception {
        Path custom = customDirectory();
        List<Path> present = new ArrayList<>();
        for (String name : MODIFIED_AFTER_PACKAGING) {
            Path file = custom.resolve(name);
            if (Files.isRegularFile(file)) {
                present.add(file);
            }
        }
        assumeTrue(present.size() == MODIFIED_AFTER_PACKAGING.length,
                "the install is missing one of the " + MODIFIED_AFTER_PACKAGING.length
                        + " modified version-3 packages; found " + present);

        for (Path file : present) {
            byte[] raw = Files.readAllBytes(file);
            assertEquals(YsmFileCrypto.CONTAINER_BINARY, YsmFileCrypto.containerVersion(raw),
                    file.getFileName() + " must still be recognised as a version-3 container");

            Exception failure = assertThrows(Exception.class,
                    () -> YsmFileCrypto.decryptYsmFile(raw), file.getFileName().toString());
            String message = String.valueOf(failure.getMessage());
            assertTrue(message.contains("file hash mismatch"),
                    file.getFileName() + " must be reported as a modified package, got: " + message);
            assertFalse(message.contains("unsupported container version"),
                    file.getFileName() + " has a supported container version and must not be reported"
                            + " as unsupported, got: " + message);
        }
    }

    /** The install's user model directory; skips the test when no install is configured. */
    private static Path customDirectory() {
        String configured = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (configured.isEmpty()) {
            String fromEnvironment = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
            configured = fromEnvironment == null ? "" : fromEnvironment;
        }
        assumeTrue(!configured.isEmpty(), "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + " or "
                + YsmModelPackage.CONFIG_ROOT_ENV + " to run this against a real install");
        Path custom = Paths.get(configured, "custom");
        assumeTrue(Files.isDirectory(custom), "no custom model directory at " + custom);
        return custom;
    }
}
