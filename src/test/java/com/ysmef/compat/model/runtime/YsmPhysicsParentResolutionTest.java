package com.ysmef.compat.model.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A rejected geometry segment must not cut a surviving chain in two. */
class YsmPhysicsParentResolutionTest {

    @Test
    void aChildFindsTheNextSurvivingSkeletonAncestor() {
        YSMRuntimeModel.BoneRt[] bones = {
                bone(-1), bone(0), bone(1), bone(2)
        };
        Map<Integer, Integer> surviving = Map.of(0, 0, 3, 1);

        assertEquals(0, YsmPhysicsParts.resolveParent(3, 2, bones, surviving),
                "a geometrically rejected parent and empty wrapper must not turn the tip into a root");
    }

    @Test
    void anAuthoredDriverWinsWhenItSurvivesAndFallsBackWhenItDoesNot() {
        YSMRuntimeModel.BoneRt[] bones = {
                bone(-1), bone(0), bone(-1)
        };
        assertEquals(2, YsmPhysicsParts.resolveParent(1, 2, bones,
                Map.of(0, 0, 1, 1, 2, 2)));
        assertEquals(0, YsmPhysicsParts.resolveParent(1, 2, bones,
                Map.of(0, 0, 1, 1)),
                "a rejected authored driver must fall back to the actual skeleton");
    }

    @Test
    void aMalformedParentCycleTerminatesWithoutMakingTheBoneItsOwnParent() {
        YSMRuntimeModel.BoneRt[] bones = {
                bone(1), bone(0)
        };
        assertEquals(-1, YsmPhysicsParts.resolveParent(0, -1, bones, Map.of(0, 0)));
    }

    private static YSMRuntimeModel.BoneRt bone(int parent) {
        YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
        bone.parent = parent;
        return bone;
    }
}
