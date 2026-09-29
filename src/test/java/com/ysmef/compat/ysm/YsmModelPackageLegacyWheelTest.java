package com.ysmef.compat.ysm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wheel-selectable animation names of a legacy package.
 *
 * <p>They come from declared display names, indexed: {@code extra0}, {@code extra1}, ... - the
 * animation names the flat generation stores in its {@code extra.animation.json}. Two sources
 * declare them and YSM reads both with a fixed precedence ({@code info.json} first with overwrite,
 * then the geometry's inline {@code ysm_extra_info} which only fills what is still empty). In the
 * local corpus 75 of the 170 legacy packages declare their names only in the geometry, so a reader
 * that knows just one source silently drops the wheel animations of nearly half of them - which is
 * why the precedence is pinned here rather than left to the sweep.
 */
class YsmModelPackageLegacyWheelTest {

    private static byte[] geometryWithExtraInfo() {
        return ("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{"
                + "\"identifier\":\"geometry.unknown\",\"texture_width\":64,\"texture_height\":64,"
                + "\"ysm_extra_info\":{\"name\":\"fixture\",\"extra_animation_names\":[\"wave\",\"dance\"]}},"
                + "\"bones\":[{\"name\":\"Root\",\"pivot\":[0,0,0]}]}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] geometryWithoutExtraInfo() {
        return ("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{"
                + "\"identifier\":\"geometry.unknown\"},\"bones\":[{\"name\":\"Root\",\"pivot\":[0,0,0]}]}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] infoJson(String... names) {
        StringBuilder sb = new StringBuilder("{\"name\":\"fixture\",\"extra_animation_names\":[");
        for (int i = 0; i < names.length; i++) {
            sb.append(i == 0 ? "\"" : ",\"").append(names[i]).append('"');
        }
        return sb.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void namesComeFromTheGeometrysInlineBlockWhenThereIsNoInfoJson() {
        Map<String, String> extra = YsmModelPackage.legacyExtraAnimations(
                new LinkedHashMap<>(), geometryWithExtraInfo());
        assertEquals(2, extra.size());
        assertEquals("wave", extra.get("extra0"));
        assertEquals("dance", extra.get("extra1"));
    }

    @Test
    void namesComeFromInfoJsonWhenItExists() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("info.json", infoJson("idle_extra", "run_extra", "sit_extra"));

        Map<String, String> extra = YsmModelPackage.legacyExtraAnimations(files, geometryWithoutExtraInfo());
        assertEquals(3, extra.size());
        assertEquals("idle_extra", extra.get("extra0"));
        assertEquals("sit_extra", extra.get("extra2"));
    }

    /** YSM reads info.json with overwrite and the geometry block without it: the first one wins. */
    @Test
    void infoJsonWinsOverTheGeometrysInlineBlock() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("info.json", infoJson("from_info_json"));

        Map<String, String> extra = YsmModelPackage.legacyExtraAnimations(files, geometryWithExtraInfo());
        assertEquals(1, extra.size(), "the geometry block must only fill an empty result, got " + extra);
        assertEquals("from_info_json", extra.get("extra0"));
    }

    @Test
    void aPackageDeclaringNothingYieldsNoWheelAnimations() {
        assertTrue(YsmModelPackage.legacyExtraAnimations(new LinkedHashMap<>(), geometryWithoutExtraInfo())
                .isEmpty());
    }

    /** Broken JSON is not fatal: a package must still load, just without wheel animations. */
    @Test
    void brokenMetadataFilesDoNotFailThePackage() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("info.json", "{not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(YsmModelPackage.legacyExtraAnimations(files, "{also not json".getBytes(StandardCharsets.UTF_8))
                .isEmpty());
    }
}
