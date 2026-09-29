package com.ysmef.compat.model;

import java.util.HashSet;
import java.util.Set;

/**
 * The alternate-form convention of YSM bone names, settled per model instead of per name.
 *
 * <p>YSM authors name the variant of a bone by appending a digit to the base bone's name
 * ("RightLeg2" beside "RightLeg", "Head2" beside "Head"). That is only a variant when the base
 * bone is actually there. A model whose whole skeleton is named "X_T4_1", "X_yiqun1",
 * "Left_ear_F_1"... declares no alternate forms at all - the trailing digit is part of the name -
 * and a name-only test ("does it end in a digit?") discards its entire skeleton. That is not
 * hypothetical: with every bone thrown away, the bind-armature pivot computation resolved nothing,
 * the model was drawn with the reference biped's pivots, and its limbs rotated about origins its own
 * geometry does not have (the visible "arm detached from the body" during a swing).
 *
 * <p>The question is therefore asked against the model's own names, read once up front
 * ({@link #baseFormsPresent}) rather than re-tested per bone, so a bone and its base form are always
 * judged against the same name set.
 */
public final class BoneAlternateForms {

    private BoneAlternateForms() {}

    /**
     * The base forms this model contains: every name whose trailing digits are stripped is itself a
     * different bone of the same model. An empty result means the model declares no alternate forms,
     * and every bone name - digit-suffixed or not - is a name in its own right.
     */
    public static Set<String> baseFormsPresent(String[] boneNames) {
        Set<String> present = new HashSet<>();
        for (String name : boneNames) {
            if (name != null && !name.isEmpty()) {
                present.add(name);
            }
        }
        Set<String> bases = new HashSet<>();
        for (String name : present) {
            String base = baseFormOf(name);
            if (!base.isEmpty() && !base.equals(name) && present.contains(base)) {
                bases.add(base);
            }
        }
        return bases;
    }

    /**
     * True when this name is the alternate form of a bone the model also contains: it carries the
     * variant's bind geometry at the base form's (or a completely different) position, so it must not
     * contribute to that joint's pivots. Callers pass the set from {@link #baseFormsPresent}.
     */
    public static boolean isAlternateForm(String name, Set<String> baseFormsPresent) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String base = baseFormOf(name);
        return !base.isEmpty() && !base.equals(name) && baseFormsPresent.contains(base);
    }

    /** A name without its trailing digits ("RightLeg2" -> "RightLeg", "0015" -> "", "Head" -> "Head"). */
    private static String baseFormOf(String name) {
        int end = name.length();
        while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
            end--;
        }
        return end == name.length() ? name : name.substring(0, end);
    }
}
