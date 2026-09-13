package com.ysmef.compat.model.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the user-override decision that runs after the automatic hidden-bone pass.
 *
 * <p>The automatic pass infers "this bone is a prop the author scales to nothing"
 * from the model's always-playing animations. That is a guess about someone else's
 * model, so {@code hidden-bones.txt} and {@code bone_overrides/&lt;model&gt;.json}
 * exist to correct it in both directions. The rules pinned here are the ones a user
 * cannot see from the file format alone:
 *
 * <ul>
 *   <li>matching is case-insensitive, because bone names are typed by hand;</li>
 *   <li>{@code show} beats {@code hide} for the same bone, because callers layer the
 *       global file first and the model's own file second - a model must be able to
 *       force a bone back that the global file hides, and "first match wins" would
 *       make that order meaningless;</li>
 *   <li>a bone in neither list keeps whatever the automatic pass decided.</li>
 * </ul>
 *
 * <p>{@link YSMRuntimeModel.BoneRt} is a plain data holder over JOML matrices, so it
 * can be built without a Minecraft runtime.
 */
class YsmBoneOverridesTest {

    @Test
    void hideMarksOnlyTheNamedBonesAndLeavesTheRestAlone() {
        YSMRuntimeModel.BoneRt[] bones = bones("Head", "Lantern", "Torso");
        boolean[] hidden = {false, false, true};

        int changed = YsmBoneOverrides.applyOverrides(bones, hidden, Set.of("lantern"), Set.of());

        assertEquals(1, changed, "only the named bone should have been touched");
        assertFalse(hidden[0], "an unnamed visible bone must stay visible");
        assertTrue(hidden[1], "the named bone must be hidden");
        assertTrue(hidden[2], "an unnamed hidden bone must stay hidden");
    }

    @Test
    void showForcesBackABoneTheAutomaticPassHid() {
        YSMRuntimeModel.BoneRt[] bones = bones("Wings");
        boolean[] hidden = {true};

        int changed = YsmBoneOverrides.applyOverrides(bones, hidden, Set.of(), Set.of("wings"));

        assertEquals(1, changed);
        assertFalse(hidden[0], "show must clear the automatic hidden flag");
    }

    /**
     * The layered-file case: the global file hides a bone, a model's own file shows
     * it. Both lists contain the name, and show must win - if hide won, the only way
     * for one model to keep a bone would be to stop hiding it for every model.
     */
    @Test
    void showBeatsHideWhenBothNameTheSameBone() {
        YSMRuntimeModel.BoneRt[] bones = bones("Tail");

        // The automatic pass hid it; the global file also asks to hide it, and the
        // model's own file asks to show it. Show must win, so the flag is cleared.
        boolean[] hidByAutomaticPass = {true};
        int changed = YsmBoneOverrides.applyOverrides(
                bones, hidByAutomaticPass, Set.of("tail"), Set.of("tail"));

        assertFalse(hidByAutomaticPass[0], "show wins over hide for the same bone");
        assertEquals(1, changed, "clearing the automatic hidden flag is one change");

        // The other ordering outcome: it was already visible, so show winning is a
        // no-op and nothing is counted as changed.
        boolean[] alreadyVisible = {false};
        assertEquals(0, YsmBoneOverrides.applyOverrides(
                        bones, alreadyVisible, Set.of("tail"), Set.of("tail")),
                "show winning over an already-visible bone changes nothing");
        assertFalse(alreadyVisible[0]);
    }

    @Test
    void matchingIgnoresCase() {
        YSMRuntimeModel.BoneRt[] bones = bones("RightForeArm");
        boolean[] hidden = {false};

        YsmBoneOverrides.applyOverrides(bones, hidden, Set.of("rightforearm"), Set.of());

        assertTrue(hidden[0], "bone names are typed by hand, so matching must ignore case");
    }

    @Test
    void bonesWithNoNameAreSkippedRatherThanCrashing() {
        YSMRuntimeModel.BoneRt[] bones = {bone(null), bone(""), bone("Known")};
        boolean[] hidden = {false, false, false};

        int changed = YsmBoneOverrides.applyOverrides(bones, hidden, Set.of("known"), Set.of());

        assertEquals(1, changed);
        assertTrue(hidden[2]);
    }

    @Test
    void emptyOverridesChangeNothing() {
        YSMRuntimeModel.BoneRt[] bones = bones("Head", "Torso");
        boolean[] hidden = {true, false};

        int changed = YsmBoneOverrides.applyOverrides(bones, hidden, Set.of(), Set.of());

        assertEquals(0, changed);
        assertTrue(hidden[0], "with no overrides the automatic result must be untouched");
        assertFalse(hidden[1]);
    }

    /** A bone table of the given names, all starting visible. */
    private static YSMRuntimeModel.BoneRt[] bones(String... names) {
        YSMRuntimeModel.BoneRt[] bones = new YSMRuntimeModel.BoneRt[names.length];
        for (int i = 0; i < names.length; i++) {
            bones[i] = bone(names[i]);
        }
        return bones;
    }

    private static YSMRuntimeModel.BoneRt bone(String name) {
        YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
        bone.name = name;
        return bone;
    }
}
