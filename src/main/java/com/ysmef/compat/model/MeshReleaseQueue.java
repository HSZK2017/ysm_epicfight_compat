package com.ysmef.compat.model;

import com.ysmef.compat.YSMEpicFightCompat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns meshes evicted from Epic Fight's cache but still potentially referenced
 * by draws in the current frame. All GL destruction happens on the render thread.
 */
final class MeshReleaseQueue {
    private static final int RELEASE_DELAY_TICKS = 5;
    private static final Map<YSMMesh, Integer> PENDING = new ConcurrentHashMap<>();

    private MeshReleaseQueue() {}

    static void schedule(YSMMesh mesh) {
        PENDING.put(mesh, RELEASE_DELAY_TICKS);
    }

    static void processTick() {
        for (var iterator = PENDING.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<YSMMesh, Integer> entry = iterator.next();
            int left = entry.getValue() - 1;
            if (left <= 0) {
                iterator.remove();
                release(entry.getKey());
            } else {
                entry.setValue(left);
            }
        }
    }

    /** Resource reload cannot simply clear this queue: these meshes are no longer in EF's cache. */
    static void releaseAll() {
        for (YSMMesh mesh : PENDING.keySet()) {
            if (PENDING.remove(mesh) != null) {
                release(mesh);
            }
        }
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
