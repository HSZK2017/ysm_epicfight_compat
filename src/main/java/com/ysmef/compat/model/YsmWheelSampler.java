package com.ysmef.compat.model;

import com.ysmef.compat.ysm.script.Molang;
import com.ysmef.compat.ysm.script.ScriptAnim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Samples wheel animation channels and evaluates their Molang timeline state. */
final class YsmWheelSampler {
    private static final float MAX_ANIMATION_LENGTH = 120.0f;

    private YsmWheelSampler() {}

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    static void evalChannel(ScriptAnim.Channel channel, float t, Molang.Env env, float[] out) {
        List<ScriptAnim.Key> keys = channel.keys;
        int n = keys.size();
        if (n == 0) {
            out[0] = out[1] = out[2] = 0.0f;
            return;
        }
        int right = 1;
        while (right < n && keys.get(right).time <= t) {
            right++;
        }
        if (right >= n) {
            evalValue(keys.get(n - 1).post, env, out);
            return;
        }
        if (right == 0) {
            evalValue(keys.get(0).post, env, out);
            return;
        }
        int left = right - 1;
        ScriptAnim.Key leftKey = keys.get(left);
        ScriptAnim.Key rightKey = keys.get(right);
        if (rightKey.lerp == ScriptAnim.Key.LERP_STEP || rightKey.time <= leftKey.time) {
            evalValue(leftKey.post, env, out);
            return;
        }
        float alpha = Math.max(0.0f, Math.min(1.0f, (t - leftKey.time) / (rightKey.time - leftKey.time)));
        float[] l = new float[3];
        float[] r = new float[3];
        evalValue(leftKey.post, env, l);
        evalValue(rightKey.pre != null ? rightKey.pre : rightKey.post, env, r);
        if (rightKey.lerp == ScriptAnim.Key.LERP_CATMULLROM && n >= 2) {
            float[] p0 = new float[3];
            float[] p3 = new float[3];
            evalValue(keys.get(Math.max(0, left - 1)).post, env, p0);
            evalValue(keys.get(Math.min(n - 1, right + 1)).post, env, p3);
            for (int i = 0; i < 3; i++) {
                out[i] = catmullRom(p0[i], l[i], r[i], p3[i], alpha);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                out[i] = l[i] + (r[i] - l[i]) * alpha;
            }
        }
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2.0f * p1) + (-p0 + p2) * t + (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2
                + (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3);
    }

    private static void evalValue(ScriptAnim.Value value, Molang.Env env, float[] out) {
        for (int axis = 0; axis < 3; axis++) {
            if (value.expr[axis] != null) {
                out[axis] = finite((float) Molang.compile(value.expr[axis]).eval(env));
            } else {
                out[axis] = finite((float) value.num[axis]);
            }
        }
    }

    static void fireTimelines(ScriptAnim anim, float t, int[] fired, Molang.Env env) {
        for (int i = 0; i < anim.timelines.size(); i++) {
            if (fired[i] == 0 && anim.timelines.get(i).time <= t + 1e-4f) {
                fired[i] = 1;
                for (String code : anim.timelines.get(i).code) {
                    Molang.compile(code).eval(env);
                }
            }
        }
    }

    static float animationLength(ScriptAnim anim) {
        if (Float.isFinite(anim.length) && anim.length > 1e-4f) {
            return Math.min(anim.length, MAX_ANIMATION_LENGTH);
        }
        // Some packages carry a corrupt/infinite animation_length. Fall back to
        // the maximum keyframe/timeline time instead of producing an endless clip.
        float max = 0.0f;
        for (ScriptAnim.BoneChannels channels : anim.bones.values()) {
            max = Math.max(max, channelMaxTime(channels.rotation));
            max = Math.max(max, channelMaxTime(channels.position));
            max = Math.max(max, channelMaxTime(channels.scale));
        }
        for (ScriptAnim.Timeline timeline : anim.timelines) {
            max = Math.max(max, timeline.time);
        }
        return Float.isFinite(max) ? Math.min(max, MAX_ANIMATION_LENGTH) : 0.0f;
    }

    private static float channelMaxTime(ScriptAnim.Channel channel) {
        if (channel == null || channel.keys.isEmpty()) {
            return 0.0f;
        }
        return channel.keys.get(channel.keys.size() - 1).time;
    }

    static final class Env implements Molang.Env {
        float animTime;
        private final Map<Integer, Double> vars = new HashMap<>();

        @Override
        public double getVarById(int id) {
            return vars.getOrDefault(id, 0.0);
        }

        @Override
        public boolean hasVarById(int id) {
            return vars.containsKey(id);
        }

        @Override
        public void setVarById(int id, double value) {
            vars.put(id, value);
        }

        @Override
        public double getQueryById(int id) {
            if (id == Molang.queryIdOf("query.anim_time")) {
                return animTime;
            }
            if (id == Molang.queryIdOf("query.health") || id == Molang.queryIdOf("query.max_health")) {
                return 20.0;
            }
            if (id == Molang.queryIdOf("query.is_on_ground") || id == Molang.queryIdOf("query.is_alive")) {
                return 1.0;
            }
            if (id == Molang.queryIdOf("ctrl.playing_extra_animation")) {
                return 1.0;
            }
            return 0.0;
        }

        @Override
        public double callFunction(String name, double[] args, int argCount) {
            switch (name) {
                case "math.sin": return argCount < 1 ? 0.0 : finite(Math.sin(Math.toRadians(args[0])));
                case "math.cos": return argCount < 1 ? 0.0 : finite(Math.cos(Math.toRadians(args[0])));
                case "math.tan": return argCount < 1 ? 0.0 : finite(Math.tan(Math.toRadians(args[0])));
                case "math.asin": return argCount < 1 ? 0.0 : finite(Math.toDegrees(Math.asin(args[0])));
                case "math.acos": return argCount < 1 ? 0.0 : finite(Math.toDegrees(Math.acos(args[0])));
                case "math.atan": return argCount < 1 ? 0.0 : finite(Math.toDegrees(Math.atan(args[0])));
                case "math.atan2": return argCount < 2 ? 0.0 : finite(Math.toDegrees(Math.atan2(args[0], args[1])));
                case "math.abs": return argCount < 1 ? 0.0 : finite(Math.abs(args[0]));
                case "math.floor": return argCount < 1 ? 0.0 : finite(Math.floor(args[0]));
                case "math.ceil": return argCount < 1 ? 0.0 : finite(Math.ceil(args[0]));
                case "math.round": return argCount < 1 ? 0.0 : finite(Math.round(args[0]));
                case "math.trunc": return argCount < 1 ? 0.0 : finite((long) (args[0] >= 0 ? Math.floor(args[0]) : Math.ceil(args[0])));
                case "math.sqrt": return argCount < 1 ? 0.0 : finite(args[0] < 0 ? 0 : Math.sqrt(args[0]));
                case "math.pow": return argCount < 2 ? 0.0 : finite(Math.pow(args[0], args[1]));
                case "math.exp": return argCount < 1 ? 0.0 : finite(Math.exp(args[0]));
                case "math.ln": return argCount < 1 ? 0.0 : finite(args[0] <= 0 ? 0 : Math.log(args[0]));
                case "math.log": return argCount < 1 ? 0.0 : finite(args[0] <= 0 ? 0 : Math.log(args[0]));
                case "math.lerp": return argCount < 3 ? 0.0 : finite(args[0] + (args[1] - args[0]) * args[2]);
                case "math.min": return argCount < 1 ? 0.0 : finite(argCount < 2 ? args[0] : Math.min(args[0], args[1]));
                case "math.max": return argCount < 1 ? 0.0 : finite(argCount < 2 ? args[0] : Math.max(args[0], args[1]));
                case "math.clamp": return argCount < 3 ? 0.0 : finite(Math.max(args[1], Math.min(args[2], args[0])));
                case "math.mod": return argCount < 2 ? 0.0 : finite(args[1] == 0 ? 0 : args[0] % args[1]);
                case "math.random": return argCount < 2 ? 0.0 : finite((args[0] + args[1]) * 0.5);
                case "math.pi": return Math.PI;
                case "math.sign": return argCount < 1 ? 0.0 : finite(Math.signum(args[0]));
                default: return 0.0;
            }
        }

        @Override
        public double callStringFunction(String name, String[] args) {
            return 0.0;
        }
    }
}
