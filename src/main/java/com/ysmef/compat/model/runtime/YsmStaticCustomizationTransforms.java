package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.ysm.script.Molang;
import com.ysmef.compat.ysm.script.ScriptAnim;
import org.joml.Matrix4f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Static parallel position/rotation channels used for customization in battle mode.
 * Epic Fight supplies the pose of mapped body bones; these transforms only move
 * auxiliary geometry, such as expression plates on a face.
 */
final class YsmStaticCustomizationTransforms {
    private static final Pattern MODEL_VARIABLE = Pattern.compile(
            "\\b(?:v|variable)\\.[A-Za-z_][A-Za-z0-9_.]*", Pattern.CASE_INSENSITIVE);
    private static final int CACHE_MAX = 32;

    private final YSMRuntimeModel model;
    private final Map<Long, OpenMatrix4f[]> cache = new ConcurrentHashMap<>();
    private final boolean enabled;

    YsmStaticCustomizationTransforms(YSMRuntimeModel model) {
        this.model = model;
        boolean found = false;
        for (YSMRuntimeModel.CompiledAnim anim : model.parallels) {
            ScriptAnim authored = model.authoredAnims.get(anim.name);
            if (authored == null) continue;
            for (ScriptAnim.BoneChannels channels : authored.bones.values()) {
                found |= usesModelVariable(channels.position) || usesModelVariable(channels.rotation);
            }
        }
        enabled = found;
    }

    void apply(YSMMesh mesh, Map<String, Float> values, boolean[] hidden,
               List<String> partNames, int[] partBones) {
        if (!enabled) return;
        long fingerprint = fingerprint(values);
        OpenMatrix4f[] transforms = cache.get(fingerprint);
        if (transforms == null) {
            transforms = evaluate(values);
            if (cache.size() >= CACHE_MAX) cache.clear();
            cache.put(fingerprint, transforms);
        }
        for (int i = 0; i < partBones.length; i++) {
            int bone = partBones[i];
            if (bone >= 0 && !hidden[bone] && transforms[bone] != null) {
                mesh.setRuntimeTransform(partNames.get(i), transforms[bone]);
            }
        }
    }

    /** Package-visible for a focused test of the resulting bind-space delta. */
    OpenMatrix4f[] evaluate(Map<String, Float> values) {
        int count = model.bones.length;
        float[][] positions = new float[count][];
        float[][] rotations = new float[count][];
        Molang.Env env = YSMRuntimeModel.newDefaultEnv();
        for (Map.Entry<String, Float> value : values.entrySet()) {
            env.setVar(value.getKey(), value.getValue());
        }
        for (YSMRuntimeModel.CompiledAnim anim : model.parallels) {
            ScriptAnim authored = model.authoredAnims.get(anim.name);
            for (YSMRuntimeModel.CompiledTimeline timeline : anim.timelines) {
                if (timeline.time <= 0) {
                    for (Molang.Expr expr : timeline.code) expr.eval(env);
                }
            }
            if (authored == null) continue;
            for (Map.Entry<Integer, YSMRuntimeModel.CompiledChannels> entry : anim.bones.entrySet()) {
                int bone = entry.getKey();
                ScriptAnim.BoneChannels source = authored.bones.get(model.bones[bone].name);
                if (source == null) continue;
                YSMRuntimeModel.CompiledChannels channels = entry.getValue();
                // Later parallel channels replace earlier channels, including when
                // the later channel is ordinary motion rather than customization.
                if (channels.pos != null) {
                    positions[bone] = usesModelVariable(source.position)
                            ? YSMRuntimeModel.evalChannelAtZero(channels.pos, env) : null;
                }
                if (channels.rot != null) {
                    rotations[bone] = usesModelVariable(source.rotation)
                            ? YSMRuntimeModel.evalChannelAtZero(channels.rot, env) : null;
                }
            }
        }
        Matrix4f[] chains = new Matrix4f[count];
        OpenMatrix4f[] result = new OpenMatrix4f[count];
        for (int i = 0; i < count; i++) {
            Matrix4f chain = compose(i, positions, rotations, chains, 0);
            if (!isIdentity(chain)) result[i] = toOpenMatrix(chain);
        }
        return result;
    }

    private Matrix4f compose(int index, float[][] positions, float[][] rotations,
                             Matrix4f[] chains, int depth) {
        if (depth > model.bones.length || depth > 256) {
            throw new IllegalStateException("cyclic YSM customization bone hierarchy");
        }
        if (chains[index] != null) return chains[index];
        YSMRuntimeModel.BoneRt bone = model.bones[index];
        // Never transfer an auxiliary parent's static motion to an EF-owned joint.
        if (bone.mapped) return chains[index] = new Matrix4f();
        Matrix4f chain = bone.parent >= 0
                ? new Matrix4f(compose(bone.parent, positions, rotations, chains, depth + 1))
                : new Matrix4f();
        float[] pos = positions[index];
        float[] rot = rotations[index];
        if (pos != null || rot != null) {
            float x = pos != null ? -pos[0] / 16.0f : 0.0f;
            float y = pos != null ? pos[1] / 16.0f : 0.0f;
            float z = pos != null ? pos[2] / 16.0f : 0.0f;
            float rx = bone.rx + (rot != null ? (float) Math.toRadians(-rot[0]) : 0.0f);
            float ry = bone.ry + (rot != null ? (float) Math.toRadians(-rot[1]) : 0.0f);
            float rz = bone.rz + (rot != null ? (float) Math.toRadians(rot[2]) : 0.0f);
            Matrix4f local = new Matrix4f().translation(bone.px + x, bone.py + y, bone.pz + z)
                    .rotateZ(rz).rotateY(ry).rotateX(rx)
                    .translate(-bone.px, -bone.py, -bone.pz);
            Matrix4f delta = new Matrix4f(bone.bindWorld)
                    .mul(local.mul(bone.bindLocalInv)).mul(bone.bindWorldInv);
            chain.mul(delta);
        }
        return chains[index] = chain;
    }

    private static boolean usesModelVariable(ScriptAnim.Channel channel) {
        if (channel == null) return false;
        for (ScriptAnim.Key key : channel.keys) {
            if (matches(key.post) || matches(key.pre)) return true;
        }
        return false;
    }

    private static boolean matches(ScriptAnim.Value value) {
        if (value == null) return false;
        for (String expression : value.expr) {
            if (expression != null && MODEL_VARIABLE.matcher(expression).find()) return true;
        }
        return false;
    }

    private static long fingerprint(Map<String, Float> values) {
        long hash = 1125899906842597L;
        for (Map.Entry<String, Float> entry : new java.util.TreeMap<>(values).entrySet()) {
            hash = hash * 31L + entry.getKey().hashCode();
            hash = hash * 31L + Float.floatToIntBits(entry.getValue());
        }
        return hash;
    }

    private static boolean isIdentity(Matrix4f m) {
        return m.equals(new Matrix4f(), 1.0e-6f);
    }

    private static OpenMatrix4f toOpenMatrix(Matrix4f m) {
        OpenMatrix4f out = new OpenMatrix4f();
        out.m00 = m.m00(); out.m01 = m.m01(); out.m02 = m.m02(); out.m03 = m.m03();
        out.m10 = m.m10(); out.m11 = m.m11(); out.m12 = m.m12(); out.m13 = m.m13();
        out.m20 = m.m20(); out.m21 = m.m21(); out.m22 = m.m22(); out.m23 = m.m23();
        out.m30 = m.m30(); out.m31 = m.m31(); out.m32 = m.m32(); out.m33 = m.m33();
        return out;
    }
}
