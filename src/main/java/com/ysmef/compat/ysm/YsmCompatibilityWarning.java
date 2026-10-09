package com.ysmef.compat.ysm;

/** Identifies YSM's outdated Epic Fight incompatibility warning. */
public final class YsmCompatibilityWarning {
    private YsmCompatibilityWarning() {
    }

    public static boolean isEpicFight(String modId, String displayName) {
        return "epicfight".equals(modId) && "Epic Fight".equals(displayName);
    }
}
