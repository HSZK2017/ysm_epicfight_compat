package com.ysmef.compat.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Holds resources through the current frame and releases each one once its delay expires. */
final class DelayedReleaseQueue<T> {
    private final Map<T, Integer> pending = new ConcurrentHashMap<>();
    private final int delayTicks;
    private final Consumer<T> releaser;

    DelayedReleaseQueue(int delayTicks, Consumer<T> releaser) {
        this.delayTicks = delayTicks;
        this.releaser = releaser;
    }

    void schedule(T resource) {
        pending.put(resource, delayTicks);
    }

    void processTick() {
        for (var iterator = pending.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<T, Integer> entry = iterator.next();
            int left = entry.getValue() - 1;
            if (left <= 0) {
                iterator.remove();
                releaser.accept(entry.getKey());
            } else {
                entry.setValue(left);
            }
        }
    }

    void releaseAll() {
        for (T resource : pending.keySet()) {
            if (pending.remove(resource) != null) {
                releaser.accept(resource);
            }
        }
    }
}
