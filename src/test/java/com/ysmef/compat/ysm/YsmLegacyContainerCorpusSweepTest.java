package com.ysmef.compat.ysm;

import com.ysmef.compat.model.YSMGeoModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sweep of a real .ysm corpus through the readers of both container generations.
 *
 * <p>Opt-in, like the other corpus sweeps in this suite: set {@code YSMEF_YSM_CORPUS_ROOT} to a
 * directory tree of .ysm packages (the local fixture is {@code E:\program\JAVA\ysm-model-repo}).
 * Skipped otherwise, so the suite still runs without the fixture.
 *
 * <p>What it pins: no package in the corpus is left unexplained - every legacy container decodes
 * into a package that carries the three things the loader needs (main.json, arm.json, a top-level
 * texture) and a geometry that parses, every version-3 package still decrypts, and the legacy count
 * is at least the 170 packages that the binary-only reader rejected. The counts are printed so a
 * report can quote them.
 *
 * <p>Version-3 packages are decrypted but not parsed here, deliberately: their decrypted payloads
 * reach 210 MB in this corpus, and parsing one of those needs a peak heap of ~2.4 GB (measured),
 * which is not a budget a unit-test JVM should carry. The parse-level check for that generation is
 * an offline digest comparison instead - decrypt, parse, and hash the parsed result (bones, per-bone
 * quad counts, texture and animation sets, scales) for every package, with and without the change.
 * The legacy generation has no such constraint (its largest package decompresses to a few tens of
 * MB), so it is checked end to end right here.
 */
class YsmLegacyContainerCorpusSweepTest {

    /** The number of legacy containers in the local fixture corpus when this support was added. */
    private static final int EXPECTED_LEGACY_PACKAGES = 170;

    @Test
    void everyContainerInTheCorpusDecodes() throws Exception {
        String root = System.getenv("YSMEF_YSM_CORPUS_ROOT");
        assumeTrue(root != null && !root.isEmpty(),
                "set YSMEF_YSM_CORPUS_ROOT to sweep the model corpus");

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));
        assumeTrue(!files.isEmpty(), "no .ysm package under " + root);

        int legacyParsed = 0;
        int binaryDecrypted = 0;
        long binaryBytes = 0L;
        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            try {
                byte[] raw = Files.readAllBytes(file);
                int container = YsmFileCrypto.containerVersion(raw);
                if (container == YsmFileCrypto.CONTAINER_LEGACY_I
                        || container == YsmFileCrypto.CONTAINER_LEGACY_II) {
                    assertLegacyPackageIsLoadable(raw, file, failures);
                    legacyParsed++;
                } else {
                    byte[] payload = YsmFileCrypto.decryptYsmFile(raw);
                    if (payload.length == 0) {
                        failures.add(file + ": version-3 package decrypted to nothing");
                        continue;
                    }
                    binaryBytes += payload.length;
                    binaryDecrypted++;
                }
            } catch (Throwable t) {
                failures.add(file + ": " + t);
            }
        }

        System.out.println("[ysm container sweep] " + files.size() + " packages under " + root
                + ": legacy=" + legacyParsed + " binary=" + binaryDecrypted + " ("
                + (binaryBytes >> 20) + " MiB decrypted) failed=" + failures.size());
        assertTrue(failures.isEmpty(), "packages that still do not decode:\n  "
                + String.join("\n  ", failures));
        assertTrue(legacyParsed >= EXPECTED_LEGACY_PACKAGES,
                "expected at least the " + EXPECTED_LEGACY_PACKAGES + " legacy containers of the fixture corpus to"
                        + " decode, decoded " + legacyParsed);
        assertTrue(binaryDecrypted > 0, "the sweep must also cover version-3 packages");
    }

    /**
     * The three things {@code YsmModelPackage}'s legacy loader requires: both geometry files (YSM's
     * own writer refuses to export without them), a top-level texture, and a geometry that parses.
     */
    private static void assertLegacyPackageIsLoadable(byte[] raw, Path file, List<String> failures) {
        Map<String, byte[]> decoded = YsmFileCrypto.decryptLegacyYsmFile(raw);
        byte[] main = decoded.get("main.json");
        if (main == null) {
            failures.add(file + ": legacy container without main.json");
            return;
        }
        if (!decoded.containsKey("arm.json")) {
            failures.add(file + ": legacy container without arm.json");
            return;
        }
        YSMGeoModel geometry = YSMGeoModel.parse(new String(main, StandardCharsets.UTF_8));
        if (geometry == null || geometry.bonesByName.isEmpty()) {
            failures.add(file + ": main.json parsed to an empty geometry");
            return;
        }
        boolean topLevelTexture = decoded.keySet().stream()
                .anyMatch(name -> name.indexOf('/') < 0 && name.endsWith(".png") && !name.equals("arrow.png"));
        if (!topLevelTexture) {
            failures.add(file + ": legacy container without a top-level texture");
        }
    }
}
