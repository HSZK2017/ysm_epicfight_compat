package com.ysmef.compat.ysm;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container dispatch: which generation a file is, that both legacy generations decode, that
 * version 3 still does, and - the defect this replaces - that a file is never reported as
 * "corrupted or truncated" when the real answer is "a container generation this reader does not
 * know".
 *
 * <p>Fixtures are written in-test by {@link YsmContainerFixtures} from YSM's own writers, so these
 * tests need no corpus and no install.
 */
class YsmFileCryptoContainerDispatchTest {

    private static Map<String, byte[]> sampleFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("main.json", "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[]}"
                .getBytes(StandardCharsets.UTF_8));
        files.put("arm.json", "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[]}"
                .getBytes(StandardCharsets.UTF_8));
        files.put("texture.png", pngBytes());
        files.put("main.animation.json", "{\"animations\":{\"idle\":{}}}".getBytes(StandardCharsets.UTF_8));
        files.put("子目录/带 空格.png", pngBytes());
        return files;
    }

    private static byte[] pngBytes() {
        byte[] png = new byte[64];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, png, 0, signature.length);
        for (int i = signature.length; i < png.length; i++) {
            png[i] = (byte) i;
        }
        return png;
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    @Test
    void legacyContainersAreDetectedByTheBareMagicAndItsBigEndianVersion() {
        assertEquals(YsmFileCrypto.CONTAINER_LEGACY_I, YsmFileCrypto.containerVersion(
                YsmContainerFixtures.legacy(YsmFileCrypto.CONTAINER_LEGACY_I, sampleFiles())));
        assertEquals(YsmFileCrypto.CONTAINER_LEGACY_II, YsmFileCrypto.containerVersion(
                YsmContainerFixtures.legacy(YsmFileCrypto.CONTAINER_LEGACY_II, sampleFiles())));
        assertEquals(YsmFileCrypto.CONTAINER_LEGACY_II, YsmFileCrypto.declaredLegacyVersion(
                YsmContainerFixtures.legacy(YsmFileCrypto.CONTAINER_LEGACY_II, sampleFiles())));
    }

    @Test
    void versionThreeIsDetectedByTheBomInFrontOfTheMagic() {
        byte[] container = YsmContainerFixtures.binary("payload".getBytes(StandardCharsets.UTF_8));
        assertEquals(YsmFileCrypto.CONTAINER_BINARY, YsmFileCrypto.containerVersion(container));
        // A bare magic is never version 3: that is what makes the two generations distinguishable
        // without decrypting anything.
        assertEquals(YsmFileCrypto.CONTAINER_UNKNOWN,
                YsmFileCrypto.declaredLegacyVersion(container));
    }

    @Test
    void unknownAndTooShortFilesAreNotContainers() {
        assertEquals(YsmFileCrypto.CONTAINER_UNKNOWN, YsmFileCrypto.containerVersion(null));
        assertEquals(YsmFileCrypto.CONTAINER_UNKNOWN, YsmFileCrypto.containerVersion(new byte[4]));
        assertEquals(YsmFileCrypto.CONTAINER_UNKNOWN,
                YsmFileCrypto.containerVersion("not a container at all".getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------
    // Legacy decode
    // ------------------------------------------------------------------

    @Test
    void legacyVersionOneContainerDecodesToItsFiles() {
        Map<String, byte[]> files = sampleFiles();
        Map<String, byte[]> decoded = YsmFileCrypto.decryptLegacyYsmFile(
                YsmContainerFixtures.legacy(YsmFileCrypto.CONTAINER_LEGACY_I, files));
        assertEquals(files.keySet(), decoded.keySet());
        files.forEach((name, data) -> assertArrayEquals(data, decoded.get(name), name));
    }

    @Test
    void legacyVersionTwoContainerDecodesToItsFiles() {
        Map<String, byte[]> files = sampleFiles();
        Map<String, byte[]> decoded = YsmFileCrypto.decryptLegacyYsmFile(
                YsmContainerFixtures.legacy(YsmFileCrypto.CONTAINER_LEGACY_II, files));
        assertEquals(files.keySet(), decoded.keySet(),
                "the version-2 entry name is base64 on disk and must come back as the stored name");
        files.forEach((name, data) -> assertArrayEquals(data, decoded.get(name), name));
    }

    @Test
    void legacyContainerWithADamagedChecksumSaysSoInsteadOfDecodingGarbage() {
        byte[] container = YsmContainerFixtures.legacy(YsmFileCrypto.CONTAINER_LEGACY_II, sampleFiles());
        container[container.length - 1] ^= 0x01;

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> YsmFileCrypto.decryptLegacyYsmFile(container));
        assertTrue(failure.getMessage().contains("legacy container checksum mismatch"),
                "expected the checksum to be named, got: " + failure.getMessage());
    }

    @Test
    void legacyContainerWithAnUnknownVersionNamesThatVersion() {
        byte[] container = YsmContainerFixtures.legacy(YsmFileCrypto.CONTAINER_LEGACY_II, sampleFiles());
        ByteBuffer.wrap(container, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(7);

        assertEquals(YsmFileCrypto.CONTAINER_UNKNOWN, YsmFileCrypto.containerVersion(container));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> YsmFileCrypto.decryptLegacyYsmFile(container));
        assertTrue(failure.getMessage().contains("unsupported container version 7"),
                "expected the version to be named, got: " + failure.getMessage());
    }

    /**
     * The defect: a legacy container handed to the binary reader used to be reported as a corrupt
     * file, because the integrity check ran before the version check and a legacy layout cannot
     * satisfy a version-3 hash. It must be refused by name now - and the name has to say which
     * generation it is, because "unsupported version 2" alone does not tell a caller that the file
     * is readable, just not by this entry point.
     */
    @Test
    void legacyContainerIsNotReportedAsACorruptedBinaryFile() {
        for (int version : new int[]{YsmFileCrypto.CONTAINER_LEGACY_I, YsmFileCrypto.CONTAINER_LEGACY_II}) {
            byte[] container = YsmContainerFixtures.legacy(version, sampleFiles());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> YsmFileCrypto.decryptYsmFile(container));
            assertTrue(failure.getMessage().contains("container version " + version),
                    "expected the container version to be named, got: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("legacy"),
                    "expected the legacy generation to be named, got: " + failure.getMessage());
            assertFalse(failure.getMessage().contains("hash mismatch"),
                    "a legacy container must not be reported as a hash failure: " + failure.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Version 3 must not regress
    // ------------------------------------------------------------------

    @Test
    void versionThreeContainerStillDecryptsToTheSamePayload() {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }
        byte[] container = YsmContainerFixtures.binary(payload);
        assertArrayEquals(payload, YsmFileCrypto.decryptYsmFile(container));
    }

    @Test
    void versionThreeContainerWithADamagedPayloadStillReportsTheHashMismatch() {
        byte[] container = YsmContainerFixtures.binary("some model payload".getBytes(StandardCharsets.UTF_8));
        // A byte inside the readable header: any change before the trailing 8 bytes breaks the hash.
        container[12] ^= 0x01;

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> YsmFileCrypto.decryptYsmFile(container));
        assertTrue(failure.getMessage().contains("file hash mismatch"),
                "a genuinely modified version-3 package must keep the hash diagnosis, got: "
                        + failure.getMessage());
    }

    /**
     * Ordering, stated as a test: a version-3-shaped file that declares an unsupported version must
     * be diagnosed by its version, even though its file hash is broken as well. Before the fix the
     * hash ran first and every such file was reported as corrupt.
     */
    @Test
    void versionCheckRunsBeforeTheHashCheck() {
        byte[] container = YsmContainerFixtures.binary("some model payload".getBytes(StandardCharsets.UTF_8));
        int headerLength = 0;
        while (container[headerLength] != 0x00) {
            headerLength++;
        }
        ByteBuffer.wrap(container, headerLength + 1, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(4);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> YsmFileCrypto.decryptYsmFile(container));
        assertTrue(failure.getMessage().contains("unsupported container version 4"),
                "expected the version to be reported first, got: " + failure.getMessage());
        assertFalse(failure.getMessage().contains("hash mismatch"),
                "the stale hash must not win over the version diagnosis: " + failure.getMessage());
    }

    @Test
    void aBareMagicWithAnUnknownVersionIsNamedNotCalledCorrupt() {
        byte[] container = YsmContainerFixtures.legacy(YsmFileCrypto.CONTAINER_LEGACY_II, sampleFiles());
        ByteBuffer.wrap(container, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(11);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> YsmFileCrypto.decryptYsmFile(container));
        assertTrue(failure.getMessage().contains("unsupported container version 11"),
                "expected the version to be named, got: " + failure.getMessage());
    }

    @Test
    void tooShortAndOversizedFilesAreRejectedByBothEntryPoints() {
        IllegalArgumentException tooShortLegacy = assertThrows(IllegalArgumentException.class,
                () -> YsmFileCrypto.decryptLegacyYsmFile(new byte[8]));
        assertTrue(tooShortLegacy.getMessage().contains("too short"));

        IllegalArgumentException tooShortBinary = assertThrows(IllegalArgumentException.class,
                () -> YsmFileCrypto.decryptYsmFile(new byte[8]));
        assertTrue(tooShortBinary.getMessage().contains("too short"));
    }

    /**
     * The two generations must stay distinguishable in the other direction too: a version-3 file's
     * tail block is not a legacy entry stream, so the legacy reader has to refuse it rather than
     * return something plausible.
     */
    @Test
    void versionThreeContainerIsNotMistakenForALegacyOne() {
        byte[] container = YsmContainerFixtures.binary("some model payload".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(YsmFileCrypto.CONTAINER_LEGACY_I, YsmFileCrypto.containerVersion(container));
        assertNotEquals(YsmFileCrypto.CONTAINER_LEGACY_II, YsmFileCrypto.containerVersion(container));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> YsmFileCrypto.decryptLegacyYsmFile(container));
        assertTrue(failure.getMessage().contains("version-3 binary container"),
                "expected the legacy reader to name what it was handed, got: " + failure.getMessage());
        assertFalse(failure.getMessage().contains("hash mismatch"),
                "and not to blame the file's integrity: " + failure.getMessage());
    }
}
