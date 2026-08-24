package com.ysmef.compat.ysm;

import com.ysmef.compat.ysm.script.ScriptAnim;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Golden test locking the binary keyframe pre/post semantics of
 * {@link YsmBinaryReader#readScriptChannel} (the H1 fix: with pre data, the
 * disk order is (pre triple, flag=1, post triple) - first triple is pre, as in
 * YSMBinarySerializer#writeChannel / YSMBinaryDeserializer#parseChannel).
 *
 * The bytes are hand-encoded in the serializer's exact layout, so the parsed
 * values must match the plaintext Bedrock animation values from the Wine Fox
 * golden package (see WineFoxPackageGoldenTest): MRoot position keyframe at
 * t=3.96 with pre != post. Before the fix, the same bytes parsed with pre/post
 * swapped and this test failed.
 */
public class YsmBinaryKeyframeGoldenTest {

    private static final float PRE_X = -35.40136f;
    private static final float PRE_Y = -37.9875f;
    private static final float PRE_Z = -22.55315f;
    private static final float POST_X = 4.14442f;
    private static final float POST_Y = -90.765f;
    private static final float POST_Z = -11.54742f;
    /** t=3.96s stored as ticks (x20). */
    private static final float KEY_TICKS = 3.96f * 20.0f;

    /** A single keyframe WITH pre data, encoded exactly like YSMBinarySerializer#writeChannel. */
    @Test
    void keyframeWithPreDataParsesPreAndPostFromDiskOrder() throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        varint(buf, 1);          // keyframeCount
        buf.putFloat(KEY_TICKS); // timestamp (ticks)
        varint(buf, 0);          // interpolationMode (linear)
        writeValue(buf, PRE_X, PRE_Y, PRE_Z); // first triple = preData (serializer writes pre first)
        varint(buf, 1);          // hasPreData = true
        writeValue(buf, POST_X, POST_Y, POST_Z); // second triple = postData

        ScriptAnim.Channel channel = readChannel(buf.array());
        assertNotNull(channel);
        assertEquals(1, channel.keys.size());
        ScriptAnim.Key key = channel.keys.get(0);
        assertEquals(3.96f, key.time, 1e-4f);
        assertNotNull(key.pre, "first triple must map to pre when hasPreData");
        assertNotNull(key.post, "second triple must map to post when hasPreData");
        assertEquals(PRE_X, key.pre.num[0], 1e-4f);
        assertEquals(PRE_Y, key.pre.num[1], 1e-4f);
        assertEquals(PRE_Z, key.pre.num[2], 1e-4f);
        assertEquals(POST_X, key.post.num[0], 1e-4f);
        assertEquals(POST_Y, key.post.num[1], 1e-4f);
        assertEquals(POST_Z, key.post.num[2], 1e-4f);
    }

    /** A keyframe WITHOUT pre data: the single triple maps to post. */
    @Test
    void keyframeWithoutPreDataMapsSingleTripleToPost() throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        varint(buf, 1);
        buf.putFloat(KEY_TICKS);
        varint(buf, 0);
        writeValue(buf, POST_X, POST_Y, POST_Z);
        varint(buf, 0);          // hasPreData = false

        ScriptAnim.Channel channel = readChannel(buf.array());
        ScriptAnim.Key key = channel.keys.get(0);
        assertNull(key.pre, "no pre data -> pre stays null");
        assertNotNull(key.post);
        assertEquals(POST_X, key.post.num[0], 1e-4f);
        assertEquals(POST_Y, key.post.num[1], 1e-4f);
        assertEquals(POST_Z, key.post.num[2], 1e-4f);
    }

    /** Mixed channel: key0 without pre, key1 with pre - the reader must not desynchronize. */
    @Test
    void mixedChannelKeepsPerKeyframeOrdering() throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        varint(buf, 2);
        // key0: no pre, post = (1, 2, 3)
        buf.putFloat(0.25f * 20.0f);
        varint(buf, 0);
        writeValue(buf, 1, 2, 3);
        varint(buf, 0);
        // key1: with pre, pre = (10, 20, 30), post = (40, 50, 60)
        buf.putFloat(0.5f * 20.0f);
        varint(buf, 0);
        writeValue(buf, 10, 20, 30);
        varint(buf, 1);
        writeValue(buf, 40, 50, 60);

        ScriptAnim.Channel channel = readChannel(buf.array());
        assertEquals(2, channel.keys.size());
        ScriptAnim.Key k0 = channel.keys.get(0);
        ScriptAnim.Key k1 = channel.keys.get(1);
        assertNull(k0.pre);
        assertEquals(1f, k0.post.num[0], 1e-4f);
        assertEquals(2f, k0.post.num[1], 1e-4f);
        assertEquals(3f, k0.post.num[2], 1e-4f);
        assertEquals(10f, k1.pre.num[0], 1e-4f);
        assertEquals(20f, k1.pre.num[1], 1e-4f);
        assertEquals(30f, k1.pre.num[2], 1e-4f);
        assertEquals(40f, k1.post.num[0], 1e-4f);
        assertEquals(50f, k1.post.num[1], 1e-4f);
        assertEquals(60f, k1.post.num[2], 1e-4f);
    }

    // ------------------------------------------------------------------
    // Serializer-layout encoding helpers
    // ------------------------------------------------------------------

    private static void varint(ByteBuffer buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    private static void writeValue(ByteBuffer buf, float... axes) {
        for (float axis : axes) {
            buf.put((byte) 0x01); // datatype: float
            buf.putFloat(axis);
        }
    }

    // ------------------------------------------------------------------
    // Reflection into the private reader (readScriptChannel is package-private
    // in spirit but private static in the class; the layout contract is what
    // matters, not the access modifier).
    // ------------------------------------------------------------------

    private static ScriptAnim.Channel readChannel(byte[] bytes) throws Exception {
        Class<?> readerClass = Class.forName("com.ysmef.compat.ysm.YsmBinaryReader$Reader");
        Constructor<?> ctor = readerClass.getDeclaredConstructor(byte[].class);
        ctor.setAccessible(true);
        Object reader = ctor.newInstance(bytes);
        Method read = YsmBinaryReader.class.getDeclaredMethod("readScriptChannel", readerClass);
        read.setAccessible(true);
        return (ScriptAnim.Channel) read.invoke(null, reader);
    }
}
