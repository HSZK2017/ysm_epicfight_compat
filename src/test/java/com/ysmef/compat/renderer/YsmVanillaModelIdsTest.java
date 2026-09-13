package com.ysmef.compat.renderer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Locks the set of YSM model ids that the compat mod treats as "the vanilla
 * player rig" and therefore renders with the plain Epic Fight biped instead of a
 * converted YSM mesh: YSM's built-in misc/2_steve ("原版史蒂夫模型" / Minecraft
 * Steve Model) and misc/1_alex ("原版艾利克斯模型" / Minecraft Alex Model).
 *
 * <p>The check reads the constant without initializing
 * {@link YSMModelAccess}: that class is client-only and its Minecraft imports
 * (Player, Level) are not on the plain-JUnit test classpath, and
 * {@code Class.forName(name, false, loader)} neither links nor initializes it.
 * Every other model id - including {@code default}, the Misc pack's other
 * entries and ordinary model ids - must stay out of the set, or it would lose
 * its YSM mesh and animation bridge.
 */
class YsmVanillaModelIdsTest {

    @Test
    void vanillaPlayerModelIdsAreExactlySteveAndAlex() throws ReflectiveOperationException {
        Set<String> ids = new LinkedHashSet<>(Arrays.asList(vanillaPlayerModelIds()));

        assertEquals(Set.of("misc/2_steve", "misc/1_alex"), ids,
                "the vanilla-player-model set changed; every id in it is rendered with the Epic Fight biped and no longer loads a YSM mesh");
    }

    @Test
    void miscPackEntriesOtherThanSteveAndAlexAreNotVanillaPlayerModels() throws ReflectiveOperationException {
        // The Misc model pack also ships these; they are real custom models and
        // must keep their YSM meshes.
        Set<String> ids = new LinkedHashSet<>(Arrays.asList(vanillaPlayerModelIds()));

        for (String customId : new String[]{"misc/3_default_boy", "misc/4_default_controllers"}) {
            assertFalse(ids.contains(customId), customId + " is a custom model and must not fall back to the biped");
        }
        // "default" is YSM's fallback model for a player without a selection;
        // treating it as vanilla would silently disable YSM for those players.
        assertFalse(ids.contains("default"), "'default' is YSM's own fallback model, not a vanilla player model");
    }

    @Test
    void idsAreStoredInTheirCanonicalYsmForm() throws ReflectiveOperationException {
        String[] ids = vanillaPlayerModelIds();

        assertArrayEquals(new String[]{"misc/2_steve", "misc/1_alex"}, ids,
                "ids must keep YSM's group/model spelling (a '.'-separated or '.ysm'-suffixed form would never match a synced selection)");
    }

    /** The private constant, read without initializing the client-only holder class. */
    private static String[] vanillaPlayerModelIds() throws ReflectiveOperationException {
        Class<?> access = Class.forName("com.ysmef.compat.renderer.YSMModelAccess", false,
                YsmVanillaModelIdsTest.class.getClassLoader());
        Field field = access.getDeclaredField("VANILLA_PLAYER_MODEL_IDS");
        field.setAccessible(true);
        return (String[]) field.get(null);
    }
}
