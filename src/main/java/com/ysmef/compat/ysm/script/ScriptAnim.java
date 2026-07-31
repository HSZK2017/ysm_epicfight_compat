package com.ysmef.compat.ysm.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YSM animation script data extracted from model packages (Bedrock .animation.json
 * files or the modern .ysm binary animation section), reduced to the animations the
 * Epic Fight compat layer evaluates at render time (see ScriptJson.isRuntimeRelevant).
 *
 * Values are kept as raw per-axis entries: each axis is either a constant number or a
 * molang expression string. Times are in seconds (binary keyframe ticks are converted).
 */
public class ScriptAnim {

    public static final int LOOP_ONCE = 0;
    public static final int LOOP_REPEAT = 1;
    public static final int LOOP_HOLD = 3;

    public String name;
    public int loop = LOOP_ONCE;
    public float length;
    public final Map<String, BoneChannels> bones = new LinkedHashMap<>();
    public final List<Timeline> timelines = new ArrayList<>();

    public static class BoneChannels {
        public Channel rotation;
        public Channel position;
        public Channel scale;

        public boolean isEmpty() {
            return rotation == null && position == null && scale == null;
        }
    }

    public static class Channel {
        public final List<Key> keys = new ArrayList<>();
    }

    public static class Key {
        public float time;
        public int lerp;
        public Value post;
        public Value pre;

        public static final int LERP_LINEAR = 0;
        public static final int LERP_STEP = 1;
        public static final int LERP_CATMULLROM = 2;
    }

    /** Per-axis value: expr[i] != null means molang, otherwise constant num[i]. */
    public static class Value {
        public final String[] expr = new String[3];
        public final double[] num = new double[3];

        public static Value ofNumber(double x, double y, double z) {
            Value v = new Value();
            v.num[0] = x;
            v.num[1] = y;
            v.num[2] = z;
            return v;
        }

        public static Value ofExpr(String x, String y, String z) {
            Value v = new Value();
            v.expr[0] = x;
            v.expr[1] = y;
            v.expr[2] = z;
            return v;
        }

        public boolean isConstant() {
            return expr[0] == null && expr[1] == null && expr[2] == null;
        }
    }

    public static class Timeline {
        public float time;
        public String[] code;

        public Timeline(float time, String[] code) {
            this.time = time;
            this.code = code;
        }
    }
}
