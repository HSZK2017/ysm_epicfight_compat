package com.ysmef.compat.model;

import com.ysmef.compat.YSMEpicFightCompat;

/**
 * Owns meshes evicted from Epic Fight's cache but still potentially referenced
 * by draws in the current frame. All GL destruction happens on the render thread.
 */
final class MeshReleaseQueue {
    private static final int RELEASE_DELAY_TICKS = 5;
    private static final DelayedReleaseQueue<YSMMesh> PENDING =
            new DelayedReleaseQueue<>(RELEASE_DELAY_TICKS, MeshReleaseQueue::release);

    private MeshReleaseQueue() {}

    static void schedule(YSMMesh mesh) {
        PENDING.schedule(mesh);
    }

    static void processTick() {
        PENDING.processTick();
    }

    /** Resource reload cannot simply clear this queue: these meshes are no longer in EF's cache. */
    static void releaseAll() {
        PENDING.releaseAll();
    }

    private static void release(YSMMesh mesh) {
        try {
            mesh.destroy();
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to destroy evicted mesh", t);
        }
        try {
            YSMMeshLibrary.releaseMeshAcrossPaths(mesh);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to release evicted mesh across render paths", t);
        }
    }
}
