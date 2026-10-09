package com.ysmef.compat.model.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic state owned by an entity instance in one world. UUIDs are deliberately not keys:
 * a player can keep the same UUID after loading another save, while its physics must start fresh.
 * Neither the entity nor its world is retained by this cache.
 */
final class YsmEntityMotionStates<S> {
    private final ReferenceQueue<Object> departed = new ReferenceQueue<>();
    private final Map<EntityKey, Entry<S>> states = new HashMap<>();

    S get(Object entity, Object world) {
        expungeDeparted();
        Entry<S> entry = states.get(new EntityKey(entity, null));
        return entry != null && entry.world.get() == world ? entry.state : null;
    }

    void put(Object entity, Object world, S state) {
        expungeDeparted();
        states.put(new EntityKey(entity, departed), new Entry<>(new WeakReference<>(world), state));
    }

    void clear() {
        states.clear();
        while (departed.poll() != null) {
            // Discard queued keys belonging to the old session.
        }
    }

    private void expungeDeparted() {
        EntityKey key;
        while ((key = (EntityKey) departed.poll()) != null) {
            states.remove(key);
        }
    }

    private record Entry<S>(WeakReference<Object> world, S state) {}

    private static final class EntityKey extends WeakReference<Object> {
        private final int identityHash;

        EntityKey(Object entity, ReferenceQueue<Object> queue) {
            super(entity, queue);
            identityHash = System.identityHashCode(entity);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            Object entity = get();
            return entity != null && other instanceof EntityKey key && entity == key.get();
        }
    }
}
