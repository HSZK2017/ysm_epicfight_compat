package com.ysmef.compat.ysm;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the read-path traversal defense of {@link YsmModelPackage}: model ids
 * arrive from server sync / player NBT and must never escape
 * config/yes_steve_model. The write-path counterpart is covered by
 * {@code SanitizeTraversalTest}.
 */
class YsmModelPackageTraversalTest {

    @Test
    void legitimateRelativeIdsAreAccepted() {
        assertEquals(Paths.get("group", "model"), YsmModelPackage.relativeModelPath("group/model"));
        assertEquals(Paths.get("wine_fox", "01_taisho_maid"),
                YsmModelPackage.relativeModelPath("wine_fox/01_taisho_maid"));
        assertEquals(Paths.get("group", "model.ysm"), YsmModelPackage.relativeModelPath("group/model.ysm"));
        assertEquals(Paths.get("group/model"), YsmModelPackage.relativeModelPath("group\\model"));
    }

    @Test
    void traversalAndAbsoluteIdsAreRejected() {
        assertNull(YsmModelPackage.relativeModelPath(null));
        assertNull(YsmModelPackage.relativeModelPath(""));
        assertNull(YsmModelPackage.relativeModelPath("../evil"));
        assertNull(YsmModelPackage.relativeModelPath("a/../b"));
        assertNull(YsmModelPackage.relativeModelPath("a/../../b"));
        assertNull(YsmModelPackage.relativeModelPath(".."));
        assertNull(YsmModelPackage.relativeModelPath("..\\..\\evil"));
        assertNull(YsmModelPackage.relativeModelPath("/etc/passwd"));
        assertNull(YsmModelPackage.relativeModelPath("//server/share"));
        assertNull(YsmModelPackage.relativeModelPath("C:/Windows/win.ini"));
        assertNull(YsmModelPackage.relativeModelPath("a\0b"));
    }

    @Test
    void dotSegmentsAreNormalizedInsideTheRoot() {
        assertEquals(Paths.get("a", "b"), YsmModelPackage.relativeModelPath("a/./b"));
        assertEquals(Paths.get("a", "b"), YsmModelPackage.relativeModelPath("a//b"));
    }

    @Test
    void normalizedPathsStayRelative() {
        Path safe = YsmModelPackage.relativeModelPath("group/model");
        Path root = Paths.get("config", "yes_steve_model", "builtin");
        Path joined = root.resolve(safe).normalize();
        assertEquals(joined, root.resolve("group/model").normalize());
    }
}
