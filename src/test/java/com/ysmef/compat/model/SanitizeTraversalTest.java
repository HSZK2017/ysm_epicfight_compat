package com.ysmef.compat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the path-traversal defense of TextureStore#sanitize (the H2 fix):
 * model ids are relative paths and texture names may contain dots, so '.', '/'
 * and '-' stay legal - but '..' / lone '.' segments must never survive into a
 * path that later hits Files.resolve.
 */
public class SanitizeTraversalTest {

    private static String sanitize(String value) {
        return TextureStore.sanitize(value);
    }

    @Test
    void traversalSegmentsAreNeutralized() throws Exception {
        assertFalse(hasParentSegment(sanitize("../../evil")), "parent segments must be neutralized");
        assertFalse(hasParentSegment(sanitize("a/../b")), "embedded parent segment must be neutralized");
        assertFalse(hasParentSegment(sanitize("..")));
        assertFalse(sanitize("a/./b").contains("/./"), "lone dot segment must be neutralized");
        assertFalse(hasParentSegment(sanitize("a/../../b/../../c")));
    }

    @Test
    void absolutePathFormIsRemoved() throws Exception {
        assertFalse(sanitize("/etc/passwd").startsWith("/"), "leading slash must not survive");
        assertFalse(sanitize("//server/share").startsWith("/"));
    }

    @Test
    void windowsSeparatorsAreStripped() throws Exception {
        // Backslashes are not in the whitelist; they become '_', so a Windows
        // traversal like "..\..\x" collapses into the single harmless file-name
        // segment ".._.._x" (no path separator -> Files.resolve cannot climb).
        String result = sanitize("..\\..\\x");
        assertFalse(result.contains("\\"));
        assertFalse(hasParentSegment(result), "no '..' path segment may survive");
    }

    private static boolean hasParentSegment(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void legitimateNamesArePreserved() throws Exception {
        assertEquals("group/model", sanitize("group/model"));
        assertEquals("v1.5", sanitize("v1.5"));
        assertEquals("wine_fox/01_taisho_maid", sanitize("wine_fox/01_taisho_maid"));
        assertEquals("skin", sanitize("skin"));
    }

    @Test
    void hostileNamesStillResolveInsideRoot() throws Exception {
        // Defense in depth: even a hostile string that slipped through the
        // character filter must resolve inside the pack root when joined.
        for (String hostile : new String[]{"../../evil", "a/../b", "..", "/etc/passwd"}) {
            String safe = sanitize(hostile);
            java.nio.file.Path root = java.nio.file.Paths.get("config", "ysm_epicfight_compat", "resourcepack");
            java.nio.file.Path joined = root.resolve("assets").resolve("test").resolve(safe + ".png").normalize();
            assertTrue(joined.startsWith(root.normalize()),
                    "sanitized '" + hostile + "' -> '" + safe + "' must stay inside the root");
        }
    }
}
