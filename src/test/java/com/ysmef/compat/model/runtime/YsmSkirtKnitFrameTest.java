package com.ysmef.compat.model.runtime;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The skirt's knit graph and the frame in which a child swing is measured. */
class YsmSkirtKnitFrameTest {

    @Test
    void aNearbyTailCannotPullASkirtPanel() {
        YsmPhysicsParts.Segment skirt = segment(0, "BM", YsmPhysicsParts.Category.CLOTH,
                -1, new Vector3f(0.0F, 1.0F, 0.0F));
        YsmPhysicsParts.Segment tail = segment(1, "Tail", YsmPhysicsParts.Category.TAIL,
                -1, new Vector3f(0.0F, 1.03F, 0.02F));
        YsmPhysicsParts.Segment nextPanel = segment(2, "BR", YsmPhysicsParts.Category.CLOTH,
                -1, new Vector3f(0.04F, 1.0F, 0.0F));
        YsmPhysicsTopology.Knits knits = YsmPhysicsTopology.knitsOf(
                new YsmPhysicsParts.Segment[]{skirt, tail, nextPanel});

        assertFalse(hasPartner(knits, 0, 1), "the tail is not sewn into the skirt");
        assertFalse(hasPartner(knits, 1, 0), "the tail must not follow a skirt panel either");
        assertTrue(hasPartner(knits, 0, 2), "the adjacent skirt panels still share motion");
    }

    @Test
    void aChildDoesNotRepeatItsParentsAlreadyComposedSwing() {
        YsmPhysicsParts.Segment root = segment(0, "FR", YsmPhysicsParts.Category.CLOTH,
                -1, new Vector3f(0.0F, 1.0F, 0.0F));
        YsmPhysicsParts.Segment hem = segment(1, "FR1", YsmPhysicsParts.Category.CLOTH,
                0, new Vector3f(0.0F, 0.8F, 0.0F));
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(
                new YsmPhysicsParts.Model(new YsmPhysicsParts.Segment[]{root, hem},
                        YsmPhysicsParts.Source.BONE_NAMES, 0), null,
                (float) Math.toRadians(60.0));
        Vector3f rest = new Vector3f(0.0F, -1.0F, 0.0F);
        state.restDirections[0].set(rest);
        state.restDirections[1].set(rest);
        state.states[0].direction.set(rest).rotateZ((float) Math.toRadians(20.0));
        state.states[1].direction.set(rest);
        state.integrated[0] = true;

        YsmPhysicsCoupling.relaxTowardsNeighbours(state, 1, 1.0F / 60.0F);

        assertTrue(YsmDynamicBoneSolver.angleBetween(rest, state.states[1].direction) < 1.0E-4F,
                "the hem already inherits its parent's 20 degree swing through chain composition");
    }

    private static YsmPhysicsParts.Segment segment(int index, String name,
                                                    YsmPhysicsParts.Category category,
                                                    int parent, Vector3f pivot) {
        return new YsmPhysicsParts.Segment(index, name, 7, pivot, new Vector3f(pivot),
                new Vector3f(0.0F, -0.1F, 0.0F), 0.1F, 0.03F, 1.0F,
                2.36F, 0.5F, (float) Math.toRadians(30.0), parent,
                new int[0], false, new int[0], category);
    }

    private static boolean hasPartner(YsmPhysicsTopology.Knits knits, int index, int partner) {
        for (int slot = 0; slot < knits.count[index]; slot++) {
            if (knits.partners[knits.start[index] + slot] == partner) {
                return true;
            }
        }
        return false;
    }
}
