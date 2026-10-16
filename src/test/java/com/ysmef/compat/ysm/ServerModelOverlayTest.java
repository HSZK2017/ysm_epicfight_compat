package com.ysmef.compat.ysm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A copy confirmed by the connected server replaces the local lookup for its id, and only for its
 * id, and only until it is dropped. The fingerprint must differ between the two sources so the
 * cached conversion of one is never reused for the other.
 */
class ServerModelOverlayTest {

    /** An id that does not exist in any real YSM folder. */
    private static final String MODEL_ID = "ysmef_overlay_test/model_7f3a";

    @TempDir
    Path overlayRoot;

    @AfterEach
    void reset() {
        ServerModelOverlay.clear();
    }

    private void installCopy() throws IOException {
        Path dir = overlayRoot.resolve(MODEL_ID);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ysm.json"), "{}");
    }

    @Test
    void activeCopyIsWhatTheModelIdResolvesTo() throws IOException {
        installCopy();
        assertFalse(YsmModelPackage.existsLocally(MODEL_ID));
        assertEquals(-1L, YsmModelPackage.fingerprint(MODEL_ID));

        ServerModelOverlay.activate(MODEL_ID, overlayRoot);
        assertTrue(YsmModelPackage.existsLocally(MODEL_ID));
        assertNotEquals(-1L, YsmModelPackage.fingerprint(MODEL_ID));
        assertFalse(YsmModelPackage.existsLocally("ysmef_overlay_test/other_model"));
        // locateLocal is "what this machine has itself" and never sees the server copy.
        assertNull(YsmModelPackage.locateLocal(MODEL_ID));
        assertEquals(overlayRoot.resolve(MODEL_ID).toAbsolutePath().normalize(),
                YsmModelPackage.locateUnder(overlayRoot, MODEL_ID).path());

        assertEquals(java.util.Set.of(MODEL_ID), ServerModelOverlay.clear());
        assertFalse(YsmModelPackage.existsLocally(MODEL_ID));
    }

    @Test
    void locateUnderRequiresAManifestForFolders() throws IOException {
        Files.createDirectories(overlayRoot.resolve(MODEL_ID));
        assertNull(YsmModelPackage.locateUnder(overlayRoot, MODEL_ID));
        assertNull(YsmModelPackage.locateUnder(overlayRoot, "../escape"));
    }

    @Test
    void serverIdsAreRestrictedToTheUuidAlphabet() {
        assertTrue(ServerModelOverlay.isValidServerId("3f2b8c1e-0d4a-4b7e-9a51-2c6f0e8d9b10"));
        assertFalse(ServerModelOverlay.isValidServerId(""));
        assertFalse(ServerModelOverlay.isValidServerId(null));
        assertFalse(ServerModelOverlay.isValidServerId("../../mods"));
        assertFalse(ServerModelOverlay.isValidServerId("ABC"));
        assertFalse(ServerModelOverlay.isValidServerId("a".repeat(65)));
    }
}
