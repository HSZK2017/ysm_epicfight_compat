package com.ysmef.compat.model;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Owns in-memory model usage order and the set of instantiated mesh resources.
 * It only selects eviction candidates; the caller releases GL, textures and
 * scripts on the render thread after selection.
 */
final class YsmModelResidency {
    private final LinkedHashMap<String, Boolean> accessOrder = new LinkedHashMap<>(64, 0.75f, true);
    private final Set<String> loadedMeshes = ConcurrentHashMap.newKeySet();

    synchronized void touch(String modelId) {
        accessOrder.put(modelId, Boolean.TRUE);
    }

    synchronized void markLoaded(String modelId) {
        loadedMeshes.add(modelId);
        touch(modelId);
    }

    boolean isLoaded(String modelId) {
        return loadedMeshes.contains(modelId);
    }

    boolean forgetLoaded(String modelId) {
        return loadedMeshes.remove(modelId);
    }

    synchronized int trackedCount() {
        return accessOrder.size();
    }

    /**
     * Claims one oldest eligible entry immediately before the caller releases
     * its resources. Protected entries stay tracked so a later trim can retry
     * them once their pending or failed state clears.
     */
    synchronized String claimEviction(int cap, Predicate<String> protectedModel) {
        if (accessOrder.size() <= cap) {
            return null;
        }
        Iterator<String> iterator = accessOrder.keySet().iterator();
        while (iterator.hasNext()) {
            String modelId = iterator.next();
            if (protectedModel.test(modelId)) {
                continue;
            }
            iterator.remove();
            return modelId;
        }
        return null;
    }

    synchronized void clear() {
        accessOrder.clear();
        loadedMeshes.clear();
    }
}
