package com.ysmef.compat.model.runtime;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class YsmEntityMotionStatesTest {
    private record PlayerIdentity(UUID uuid) {}

    @Test
    void equalUuidsStillHaveSeparateSimulationState() {
        YsmMeshSecondaryMotion.PreparedModel model = preparedModel();
        UUID uuid = UUID.randomUUID();
        PlayerIdentity first = new PlayerIdentity(uuid);
        PlayerIdentity second = new PlayerIdentity(uuid);
        Object world = new Object();

        YsmMeshSecondaryMotion.State a = model.stateFor(first, world);
        YsmMeshSecondaryMotion.State b = model.stateFor(second, world);
        assertNotSame(a, b);
        assertSame(a, model.stateFor(first, world));
        assertSame(b, model.stateFor(second, world));
        assertNotSame(a, preparedModel().stateFor(first, world));
        assertSame(a.parts, b.parts);
        assertSame(a.knits, b.knits);
        assertNotSame(a.states[0], b.states[0]);
        assertNotSame(a.colliders, b.colliders);
        assertNotSame(a.colliders, model.colliderTemplate);

        a.lastStepSeconds = 12.0;
        a.smoothedYawRate = 3.0F;
        a.held[0] = true;
        assertEquals(-1.0, b.lastStepSeconds);
        assertEquals(0.0F, b.smoothedYawRate);
        assertEquals(false, b.held[0]);
    }

    @Test
    void changingWorldResetsEvenTheSameEntityInstance() {
        YsmMeshSecondaryMotion.PreparedModel model = preparedModel();
        PlayerIdentity entity = new PlayerIdentity(UUID.randomUUID());
        Object firstWorld = new Object();
        Object secondWorld = new Object();

        YsmMeshSecondaryMotion.State old = model.stateFor(entity, firstWorld);
        old.lastStepSeconds = 42.0;
        old.smoothedYawRate = 4.0F;
        YsmMeshSecondaryMotion.State fresh = model.stateFor(entity, secondWorld);

        assertNotSame(old, fresh);
        assertEquals(-1.0, fresh.lastStepSeconds);
        assertEquals(0.0F, fresh.smoothedYawRate);
        assertSame(fresh, model.stateFor(entity, secondWorld));
    }

    @Test
    void weakIdentityCacheDoesNotMatchEqualObjectsOrOldWorlds() {
        YsmEntityMotionStates<String> cache = new YsmEntityMotionStates<>();
        PlayerIdentity first = new PlayerIdentity(UUID.randomUUID());
        PlayerIdentity equalButDistinct = new PlayerIdentity(first.uuid());
        Object world = new Object();
        cache.put(first, world, "first");

        assertEquals("first", cache.get(first, world));
        assertNull(cache.get(equalButDistinct, world));
        assertNull(cache.get(first, new Object()));
        cache.clear();
        assertNull(cache.get(first, world));
    }

    private static YsmMeshSecondaryMotion.PreparedModel preparedModel() {
        YsmPhysicsParts.Segment segment = new YsmPhysicsParts.Segment(
                0, "tail", 0, new Vector3f(), new Vector3f(0.0F, -0.3F, 0.0F),
                0.3F, 0.04F, 1.0F, 2.0F, 0.5F, 0.8F, -1,
                new int[]{0}, false, new int[0]);
        YsmPhysicsParts.Model parts = new YsmPhysicsParts.Model(
                new YsmPhysicsParts.Segment[]{segment}, YsmPhysicsParts.Source.BONE_NAMES, 0);
        List<Vector3f> torso = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            float angle = (float) (i * Math.PI / 6.0);
            torso.add(new Vector3f((float) Math.cos(angle) * 0.1F,
                    (float) Math.sin(angle) * 0.1F, (i & 1) * 0.02F));
        }
        YsmBodyColliders colliders = YsmBodyColliders.fromGeometry(Map.of(7, torso));
        assertNotNull(colliders);
        return new YsmMeshSecondaryMotion.PreparedModel(parts, colliders, 0.8F);
    }
}
