package com.ysmef.compat.golden;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.ysm.script.ScriptAnim;
import com.ysmef.compat.ysm.script.ScriptJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-case test over the real Wine Fox model package (TLM's public model,
 * stored under src/test/resources/golden/winefox/ as the plaintext directory
 * package: ysm.json + models/main.json + animations/main.animation.json).
 *
 * Locks the YSM Bedrock-format parsing chain (YSMGeoModel + ScriptJson) against
 * the actual model data, and pins the keyframe pre/post semantics that the
 * binary reader must reproduce (see YsmBinaryKeyframeGoldenTest for the binary
 * side).
 */
public class WineFoxPackageGoldenTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = WineFoxPackageGoldenTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The model skeleton: 195 bones, 256x256 texture atlas. */
    @Test
    void parsesSkeletonGeometry() throws Exception {
        YSMGeoModel geometry = YSMGeoModel.parse(resource("/golden/winefox/models/main.json"));
        assertNotNull(geometry);
        assertEquals(195, geometry.bonesByName.size());
        assertEquals(256, geometry.textureWidth);
        assertEquals(256, geometry.textureHeight);
        // Key bones of the Wine Fox rig (YSM naming).
        for (String bone : new String[]{"Head", "Tail", "LeftArm", "RightArm", "LeftLeg", "RightLeg", "Eyes"}) {
            assertTrue(geometry.bonesByName.containsKey(bone), "missing bone " + bone);
        }
    }

    /** The main animation file: 49 animations incl. parallel loops and locomotion states. */
    @Test
    void parsesMainAnimationFile() throws Exception {
        JsonObject root = JsonParser.parseString(resource("/golden/winefox/animations/main.animation.json")).getAsJsonObject();
        JsonObject anims = root.getAsJsonObject("animations");
        assertNotNull(anims);
        assertEquals(49, anims.size());
        for (String name : new String[]{"idle", "walk", "run", "pre_parallel0", "parallel0", "use_mainhand"}) {
            assertTrue(anims.has(name), "missing animation " + name);
        }
    }

    /**
     * Golden pre/post keyframe semantics (plaintext path): the gui animation's
     * MRoot position keyframe at t=3.96 carries pre != post values. ScriptJson
     * must surface them exactly as written - this is the reference semantic the
     * binary reader must match (see YsmBinaryKeyframeGoldenTest).
     */
    @Test
    void prePostKeyframeValuesMatchBedrockJson() throws Exception {
        JsonObject root = JsonParser.parseString(resource("/golden/winefox/animations/main.animation.json")).getAsJsonObject();
        JsonObject anim = root.getAsJsonObject("animations").getAsJsonObject("gui");
        JsonObject bone = anim.getAsJsonObject("bones").getAsJsonObject("MRoot");
        JsonObject keyframe = bone.getAsJsonObject("position").getAsJsonObject("3.96");

        ScriptAnim anim2 = ScriptJson.fromBedrock("gui", anim);
        ScriptAnim.Channel channel = anim2.bones.get("MRoot").position;
        assertNotNull(channel);
        ScriptAnim.Key key = channel.keys.stream()
                .filter(k -> Math.abs(k.time - 3.96f) < 1e-4f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("keyframe t=3.96 not found"));
        assertNotNull(key.pre, "keyframe must carry pre data");
        assertEquals(3.96f, key.time, 1e-4f);
        assertValueEquals(key.pre, -35.40136, -37.9875, -22.55315);
        assertValueEquals(key.post, 4.14442, -90.765, -11.54742);
    }

    /** The manifest: player model + animation + texture file mapping. */
    @Test
    void manifestDeclaresPlayerFiles() throws Exception {
        JsonObject manifest = JsonParser.parseString(resource("/golden/winefox/ysm.json")).getAsJsonObject();
        JsonObject player = manifest.getAsJsonObject("files").getAsJsonObject("player");
        assertEquals("models/main.json", player.getAsJsonObject("model").get("main").getAsString());
        assertEquals("animations/main.animation.json", player.getAsJsonObject("animation").get("main").getAsString());
        JsonElement textures = player.get("texture");
        assertTrue(textures.isJsonArray() && textures.getAsJsonArray().size() == 2);
    }

    private static void assertValueEquals(ScriptAnim.Value value, double x, double y, double z) {
        assertNotNull(value);
        assertEquals(x, value.num[0], 1e-4);
        assertEquals(y, value.num[1], 1e-4);
        assertEquals(z, value.num[2], 1e-4);
    }
}
