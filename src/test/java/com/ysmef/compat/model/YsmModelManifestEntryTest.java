package com.ysmef.compat.model;

import com.google.gson.JsonObject;
import com.ysmef.compat.model.YsmModelConverter.ModelResult;
import com.ysmef.compat.model.YsmModelConverter.TextureEntry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class YsmModelManifestEntryTest {
    @Test
    void recordsOutputHashesAndOptionalTextureFormatInOneSchema() {
        ModelResult result = new ModelResult("sample", "sample", 12, 101L, 202L,
                "sample:default", "mesh-hash", 303L, "runtime-hash", 404L,
                List.of(
                        new TextureEntry("skin", ResourceLocation.parse("sample:textures/skin.png"),
                                new byte[0], new int[]{64, 32, 4}, "skin-hash", 505L),
                        new TextureEntry("plain", ResourceLocation.parse("sample:textures/plain.png"),
                                new byte[0], null, "plain-hash", 606L)));

        JsonObject entry = YsmModelManifestEntry.from(result);
        assertEquals(Set.of("sig", "csig", "mesh", "mhash", "msize", "rhash", "rsize", "textures"),
                entry.keySet());
        assertEquals(101L, entry.get("sig").getAsLong());
        assertEquals(202L, entry.get("csig").getAsLong());
        assertEquals("sample", entry.get("mesh").getAsString());
        assertEquals("mesh-hash", entry.get("mhash").getAsString());
        assertEquals(303L, entry.get("msize").getAsLong());
        assertEquals("runtime-hash", entry.get("rhash").getAsString());
        assertEquals(404L, entry.get("rsize").getAsLong());

        JsonObject textures = entry.getAsJsonObject("textures");
        JsonObject skin = textures.getAsJsonObject("skin");
        assertEquals("sample:textures/skin.png", skin.get("rl").getAsString());
        assertEquals(64, skin.get("w").getAsInt());
        assertEquals(32, skin.get("h").getAsInt());
        assertEquals(4, skin.get("fmt").getAsInt());
        assertEquals("skin-hash", skin.get("hash").getAsString());
        assertEquals(505L, skin.get("size").getAsLong());

        JsonObject plain = textures.getAsJsonObject("plain");
        assertFalse(plain.has("w"));
        assertFalse(plain.has("h"));
        assertFalse(plain.has("fmt"));
        assertEquals("plain-hash", plain.get("hash").getAsString());
        assertEquals(606L, plain.get("size").getAsLong());
    }
}
