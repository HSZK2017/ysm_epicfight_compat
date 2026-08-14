package com.ysmef.compat.ysm.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Conversion between Bedrock animation JSON (YSM directory packages), the runtime
 * JSON written next to generated Epic Fight meshes, and {@link ScriptAnim}.
 *
 * Only animations relevant to the Epic Fight compat runtime are kept:
 * - pre_parallelN/parallelN looped animations (variant visibility, physics springs)
 * - locomotion state animations (idle/walk/run/fly/swim/sneak/...)
 * - condition overlays (hold_mainhand:*, use_mainhand:*, vehicle$*, ...)
 *
 * Extra/GUI/dance animations and animation controllers are deliberately excluded:
 * they are triggered manually through YSM's GUI and never auto-play.
 */
public final class ScriptJson {

    private ScriptJson() {}

    private static final Set<String> STATE_ANIMS = new LinkedHashSet<>(Set.of(
            "idle", "new_idle_empty", "walk", "run", "sneak", "sneaking", "swim", "swim_stand",
            "fly", "elytra_fly", "climb", "climbing", "ladder_up", "ladder_down", "ladder_stillness",
            "sit", "ride", "ride_pig", "boat", "sleep", "death",
            "use_mainhand", "use_offhand", "swing_hand"
    ));

    private static final String[] CONDITION_PREFIXES = {
            "hold_mainhand:", "hold_offhand:", "use_mainhand:", "use_offhand:", "vehicle$"
    };

    public static boolean isRuntimeRelevant(String animName) {
        if (animName == null || animName.isEmpty()) {
            return false;
        }
        if (animName.startsWith("pre_parallel") || animName.startsWith("parallel")) {
            return true;
        }
        if (STATE_ANIMS.contains(animName)) {
            return true;
        }
        for (String prefix : CONDITION_PREFIXES) {
            if (animName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Bedrock animation JSON -> ScriptAnim
    // ------------------------------------------------------------------

    public static ScriptAnim fromBedrock(String name, JsonObject animJson) {
        ScriptAnim anim = new ScriptAnim();
        anim.name = name;
        JsonElement loopEl = animJson.get("loop");
        if (loopEl != null) {
            if (loopEl.isJsonObject()) {
                // Bedrock "loop": { "time": <seconds> } - loop over a fixed
                // window. ModernYSM itself throws on this form (getAsString on
                // a JsonObject); treat it as a repeat loop over that window.
                anim.loop = ScriptAnim.LOOP_REPEAT;
                JsonObject loopObj = loopEl.getAsJsonObject();
                if (loopObj.has("time") && loopObj.get("time").isJsonPrimitive()) {
                    anim.length = loopObj.get("time").getAsFloat();
                }
            } else if (loopEl.isJsonPrimitive() && loopEl.getAsJsonPrimitive().isBoolean()) {
                anim.loop = loopEl.getAsBoolean() ? ScriptAnim.LOOP_REPEAT : ScriptAnim.LOOP_ONCE;
            } else if (loopEl.isJsonPrimitive() && loopEl.getAsJsonPrimitive().isString()) {
                String loopStr = loopEl.getAsString();
                if ("true".equals(loopStr)) {
                    anim.loop = ScriptAnim.LOOP_REPEAT;
                } else if ("hold_on_last_frame".equals(loopStr)) {
                    anim.loop = ScriptAnim.LOOP_HOLD;
                }
            }
            // JsonNull / unknown forms keep LOOP_ONCE, like the reference
            // (any unrecognized value maps to PLAY_ONCE).
        }
        if (animJson.has("animation_length")) {
            // explicit length wins over the loop-object window
            anim.length = animJson.get("animation_length").getAsFloat();
        }

        JsonObject bones = animJson.has("bones") ? animJson.getAsJsonObject("bones") : null;
        if (bones != null) {
            for (Map.Entry<String, JsonElement> entry : bones.entrySet()) {
                JsonObject channels = entry.getValue().getAsJsonObject();
                ScriptAnim.BoneChannels bc = new ScriptAnim.BoneChannels();
                if (channels.has("rotation")) {
                    bc.rotation = parseChannel(channels.get("rotation"));
                }
                if (channels.has("position")) {
                    bc.position = parseChannel(channels.get("position"));
                }
                if (channels.has("scale")) {
                    bc.scale = parseChannel(channels.get("scale"));
                }
                if (!bc.isEmpty()) {
                    anim.bones.put(entry.getKey(), bc);
                }
            }
        }

        JsonObject timeline = animJson.has("timeline") ? animJson.getAsJsonObject("timeline") : null;
        if (timeline != null) {
            for (Map.Entry<String, JsonElement> entry : timeline.entrySet()) {
                float time;
                try {
                    time = Float.parseFloat(entry.getKey());
                } catch (NumberFormatException e) {
                    continue;
                }
                JsonElement codeEl = entry.getValue();
                String[] code;
                if (codeEl.isJsonArray()) {
                    JsonArray arr = codeEl.getAsJsonArray();
                    code = new String[arr.size()];
                    for (int i = 0; i < arr.size(); i++) {
                        code[i] = arr.get(i).getAsString();
                    }
                } else {
                    code = new String[]{codeEl.getAsString()};
                }
                anim.timelines.add(new ScriptAnim.Timeline(time, code));
            }
            anim.timelines.sort((a, b) -> Float.compare(a.time, b.time));
        }
        return anim;
    }

    private static ScriptAnim.Channel parseChannel(JsonElement el) {
        ScriptAnim.Channel channel = new ScriptAnim.Channel();
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                float time;
                try {
                    time = Float.parseFloat(entry.getKey());
                } catch (NumberFormatException e) {
                    continue;
                }
                ScriptAnim.Key key = new ScriptAnim.Key();
                key.time = time;
                JsonElement kv = entry.getValue();
                if (kv.isJsonObject()) {
                    JsonObject kobj = kv.getAsJsonObject();
                    if (kobj.has("post")) {
                        key.post = parseValue(kobj.get("post"));
                    }
                    if (kobj.has("pre")) {
                        key.pre = parseValue(kobj.get("pre"));
                    }
                    if (kobj.has("lerp_mode")) {
                        String mode = kobj.get("lerp_mode").getAsString();
                        if ("catmullrom".equals(mode)) {
                            key.lerp = ScriptAnim.Key.LERP_CATMULLROM;
                        } else if ("step".equals(mode)) {
                            key.lerp = ScriptAnim.Key.LERP_STEP;
                        }
                    }
                } else {
                    key.post = parseValue(kv);
                }
                if (key.post != null) {
                    channel.keys.add(key);
                }
            }
            channel.keys.sort((a, b) -> Float.compare(a.time, b.time));
        } else {
            ScriptAnim.Key key = new ScriptAnim.Key();
            key.time = 0;
            key.post = parseValue(el);
            if (key.post != null) {
                channel.keys.add(key);
            }
        }
        return channel.keys.isEmpty() ? null : channel;
    }

    private static ScriptAnim.Value parseValue(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isNumber()) {
                double n = prim.getAsDouble();
                return ScriptAnim.Value.ofNumber(n, n, n);
            }
            String s = prim.getAsString();
            return ScriptAnim.Value.ofExpr(s, s, s);
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            ScriptAnim.Value value = new ScriptAnim.Value();
            for (int i = 0; i < 3 && i < arr.size(); i++) {
                JsonElement axis = arr.get(i);
                if (axis.isJsonPrimitive() && axis.getAsJsonPrimitive().isNumber()) {
                    value.num[i] = axis.getAsDouble();
                } else {
                    value.expr[i] = axis.getAsString();
                }
            }
            return value;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // ScriptAnim <-> runtime JSON (written next to the generated meshes)
    // ------------------------------------------------------------------

    public static JsonObject animationsToJson(Map<String, ScriptAnim> anims) {
        JsonObject root = new JsonObject();
        for (ScriptAnim anim : anims.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("loop", anim.loop);
            obj.addProperty("length", anim.length);
            if (!anim.bones.isEmpty()) {
                JsonObject bones = new JsonObject();
                for (Map.Entry<String, ScriptAnim.BoneChannels> entry : anim.bones.entrySet()) {
                    ScriptAnim.BoneChannels bc = entry.getValue();
                    JsonObject ch = new JsonObject();
                    if (bc.rotation != null) {
                        ch.add("rotation", channelToJson(bc.rotation));
                    }
                    if (bc.position != null) {
                        ch.add("position", channelToJson(bc.position));
                    }
                    if (bc.scale != null) {
                        ch.add("scale", channelToJson(bc.scale));
                    }
                    bones.add(entry.getKey(), ch);
                }
                obj.add("bones", bones);
            }
            if (!anim.timelines.isEmpty()) {
                JsonArray tl = new JsonArray();
                for (ScriptAnim.Timeline entry : anim.timelines) {
                    JsonObject e = new JsonObject();
                    e.addProperty("t", entry.time);
                    JsonArray code = new JsonArray();
                    for (String c : entry.code) {
                        code.add(c);
                    }
                    e.add("code", code);
                    tl.add(e);
                }
                obj.add("timeline", tl);
            }
            root.add(anim.name, obj);
        }
        return root;
    }

    private static JsonArray channelToJson(ScriptAnim.Channel channel) {
        JsonArray keys = new JsonArray();
        for (ScriptAnim.Key key : channel.keys) {
            JsonObject obj = new JsonObject();
            obj.addProperty("t", key.time);
            if (key.lerp != ScriptAnim.Key.LERP_LINEAR) {
                obj.addProperty("lerp", key.lerp);
            }
            obj.add("post", valueToJson(key.post));
            if (key.pre != null) {
                obj.add("pre", valueToJson(key.pre));
            }
            keys.add(obj);
        }
        return keys;
    }

    private static JsonArray valueToJson(ScriptAnim.Value value) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < 3; i++) {
            if (value.expr[i] != null) {
                arr.add(value.expr[i]);
            } else {
                arr.add(value.num[i]);
            }
        }
        return arr;
    }

    public static ScriptAnim animationsFromJson(String name, JsonObject obj) {
        ScriptAnim anim = new ScriptAnim();
        anim.name = name;
        anim.loop = obj.has("loop") ? obj.get("loop").getAsInt() : ScriptAnim.LOOP_ONCE;
        anim.length = obj.has("length") ? obj.get("length").getAsFloat() : 0;
        if (obj.has("bones")) {
            JsonObject bones = obj.getAsJsonObject("bones");
            for (Map.Entry<String, JsonElement> entry : bones.entrySet()) {
                JsonObject ch = entry.getValue().getAsJsonObject();
                ScriptAnim.BoneChannels bc = new ScriptAnim.BoneChannels();
                if (ch.has("rotation")) {
                    bc.rotation = channelFromJson(ch.getAsJsonArray("rotation"));
                }
                if (ch.has("position")) {
                    bc.position = channelFromJson(ch.getAsJsonArray("position"));
                }
                if (ch.has("scale")) {
                    bc.scale = channelFromJson(ch.getAsJsonArray("scale"));
                }
                if (!bc.isEmpty()) {
                    anim.bones.put(entry.getKey(), bc);
                }
            }
        }
        if (obj.has("timeline")) {
            for (JsonElement el : obj.getAsJsonArray("timeline")) {
                JsonObject e = el.getAsJsonObject();
                JsonArray codeArr = e.getAsJsonArray("code");
                String[] code = new String[codeArr.size()];
                for (int i = 0; i < codeArr.size(); i++) {
                    code[i] = codeArr.get(i).getAsString();
                }
                anim.timelines.add(new ScriptAnim.Timeline(e.get("t").getAsFloat(), code));
            }
        }
        return anim;
    }

    private static ScriptAnim.Channel channelFromJson(JsonArray arr) {
        ScriptAnim.Channel channel = new ScriptAnim.Channel();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            ScriptAnim.Key key = new ScriptAnim.Key();
            key.time = obj.get("t").getAsFloat();
            key.lerp = obj.has("lerp") ? obj.get("lerp").getAsInt() : ScriptAnim.Key.LERP_LINEAR;
            key.post = parseValue(obj.get("post"));
            if (obj.has("pre")) {
                key.pre = parseValue(obj.get("pre"));
            }
            if (key.post != null) {
                channel.keys.add(key);
            }
        }
        return channel.keys.isEmpty() ? null : channel;
    }
}
