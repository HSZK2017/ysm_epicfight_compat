package com.ysmef.compat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YsmModelResidencyTest {
    @Test
    void evictsTheOldestModelThatWasNotUsedAgain() {
        YsmModelResidency residency = new YsmModelResidency();
        residency.touch("first");
        residency.touch("second");
        residency.touch("third");
        residency.touch("first");

        assertEquals("second", residency.claimEviction(2, ignored -> false));
        assertEquals(2, residency.trackedCount());
        assertNull(residency.claimEviction(2, ignored -> false));
    }

    @Test
    void protectedModelStaysTrackedForLaterTrim() {
        YsmModelResidency residency = new YsmModelResidency();
        residency.touch("pending");
        residency.touch("middle");
        residency.touch("newest");

        assertEquals("middle", residency.claimEviction(1, "pending"::equals));
        assertEquals("newest", residency.claimEviction(1, "pending"::equals));
        assertNull(residency.claimEviction(1, "pending"::equals));
        assertEquals(1, residency.trackedCount());
        residency.touch("later");
        assertEquals("pending", residency.claimEviction(1, ignored -> false));
    }

    @Test
    void instantiatedMeshTrackingHasTheSameInvalidationBoundary() {
        YsmModelResidency residency = new YsmModelResidency();
        residency.markLoaded("model");
        assertTrue(residency.isLoaded("model"));
        assertEquals(1, residency.trackedCount());
        assertTrue(residency.forgetLoaded("model"));
        assertFalse(residency.isLoaded("model"));

        residency.markLoaded("model");
        residency.clear();
        assertFalse(residency.isLoaded("model"));
        assertEquals(0, residency.trackedCount());
    }
}
