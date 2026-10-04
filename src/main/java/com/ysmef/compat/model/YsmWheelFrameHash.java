package com.ysmef.compat.model;

import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** Identity of the emitted joint matrices, rather than the source channels. */
final class YsmWheelFrameHash {
    private YsmWheelFrameHash() {}

    static String of(YsmExtraFrameWriter.Clip clip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(4);
            putInt(digest, buffer, clip.loop);
            putInt(digest, buffer, Float.floatToIntBits(clip.length));
            putInt(digest, buffer, clip.frameCount);
            for (Map.Entry<Integer, OpenMatrix4f[]> entry : clip.localFrames.entrySet()) {
                putInt(digest, buffer, entry.getKey());
                for (OpenMatrix4f m : entry.getValue()) {
                    putFloat(digest, buffer, m.m00); putFloat(digest, buffer, m.m01);
                    putFloat(digest, buffer, m.m02); putFloat(digest, buffer, m.m03);
                    putFloat(digest, buffer, m.m10); putFloat(digest, buffer, m.m11);
                    putFloat(digest, buffer, m.m12); putFloat(digest, buffer, m.m13);
                    putFloat(digest, buffer, m.m20); putFloat(digest, buffer, m.m21);
                    putFloat(digest, buffer, m.m22); putFloat(digest, buffer, m.m23);
                    putFloat(digest, buffer, m.m30); putFloat(digest, buffer, m.m31);
                    putFloat(digest, buffer, m.m32); putFloat(digest, buffer, m.m33);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void putFloat(MessageDigest digest, ByteBuffer buffer, float value) {
        putInt(digest, buffer, Float.floatToIntBits(Float.isFinite(value) ? value : 0.0f));
    }

    private static void putInt(MessageDigest digest, ByteBuffer buffer, int value) {
        buffer.clear();
        buffer.putInt(value);
        digest.update(buffer.array());
    }
}
