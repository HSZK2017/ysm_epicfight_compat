package com.ysmef.compat.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DelayedReleaseQueueTest {
    @Test
    void resourceSurvivesCurrentFramesAndIsReleasedOnlyOnce() {
        List<String> released = new ArrayList<>();
        DelayedReleaseQueue<String> queue = new DelayedReleaseQueue<>(5, released::add);
        queue.schedule("mesh");

        for (int tick = 0; tick < 4; tick++) {
            queue.processTick();
            assertEquals(List.of(), released, "a current-frame draw may still reference the mesh");
        }
        queue.processTick();
        queue.processTick();
        queue.releaseAll();
        assertEquals(List.of("mesh"), released);
    }

    @Test
    void reschedulingRestartsDelayAndReloadFlushesPendingResources() {
        List<String> released = new ArrayList<>();
        DelayedReleaseQueue<String> queue = new DelayedReleaseQueue<>(5, released::add);
        queue.schedule("mesh");
        for (int tick = 0; tick < 3; tick++) {
            queue.processTick();
        }
        queue.schedule("mesh");
        for (int tick = 0; tick < 4; tick++) {
            queue.processTick();
        }
        assertEquals(List.of(), released);
        queue.releaseAll();
        assertEquals(List.of("mesh"), released);
        queue.processTick();
        assertEquals(List.of("mesh"), released, "reload must not release the same mesh twice");
    }
}
