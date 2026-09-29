package com.ysmef.compat.ysm;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the section-count guards of {@link YsmBinaryReader} - specifically the one
 * count that had none.
 *
 * <p>Every other attacker-supplied count in the reader is bounded before it is used
 * ({@code animationCount}, {@code textureCount}, {@code modelTotalCount},
 * {@code boneCount}, {@code cubeCount}, {@code faceCount} - all {@code 1_000_000}).
 * The per-timeline event count was the exception:
 * {@code String[] code = new String[timelineEventsCount]} allocated straight from a
 * varint read out of the stream, so a crafted package could ask for an array of
 * {@code Integer.MAX_VALUE} references (roughly 8-17 GB) from a payload a few dozen
 * bytes long. {@link YsmModelPackage#load} catches {@code Exception} and
 * {@code StackOverflowError}, not {@code OutOfMemoryError}, so that allocation would
 * escape as a fatal error rather than a rejected model.
 *
 * <p>The payload below is a minimal well-formed modern-format prefix that reaches the
 * timeline section; it is deliberately not a complete model, and every test here
 * stops inside {@code readAnimations}. What is pinned is the guard itself: the reader
 * refuses an absurd declared count <i>by name</i>, and does not refuse a legitimate
 * one. The hostile count is 1_000_001 rather than {@code Integer.MAX_VALUE} so that
 * the behaviour this test was written against - an 8 MB allocation followed by an
 * unrelated parse error - reproduces inside the test JVM instead of risking it.
 */
class YsmBinaryReaderSectionCountTest {

    /**
     * A modern-format (16) package prefix that reaches the timeline section of one
     * animation. Every field is written exactly as {@code Reader} reads it: little
     * endian dword and float, LEB128 varints, and a length-prefixed string.
     */
    private static byte[] modernPayload(int animationCount, int timelineEventsCount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeDword(out, 16);                    // format -> readModern (format > 15)
        writeVarInt(out, 0);                    // soundCount
        writeVarInt(out, 0);                    // functionCount
        writeVarInt(out, 0);                    // languageCount
        writeVarInt(out, 0);                    // subEntityTotalCount (format < 26)
        writeVarInt(out, 0);                    // the bare varint read after the sub entities
        writeVarInt(out, 1);                    // unknownEntityFlag: must be 1
        writeVarInt(out, animationCount);       // <-- the pre-existing cap covers this one
        if (animationCount != 1) {
            // The guard rejects before the loop body reads anything, so the payload can
            // stop here for the hostile case.
            return out.toByteArray();
        }
        writeVarInt(out, 0);                    // per animation: leading varint
        writeString(out, "");                   // per animation: name
        // ---- readAnimations ----
        writeVarInt(out, 1);                    // animations in this blob
        writeString(out, "a");                  // animation name
        writeFloat(out, 20.0f);                 // length in ticks -> 1 s
        writeVarInt(out, 0);                    // loop mode
        writeVarInt(out, 0);                    // format > 9: bare varint
        writeVarInt(out, 0);                    // format > 9: bare varint
        writeVarInt(out, 0);                    // format > 9: blendWeightMolangCount
        writeVarInt(out, 0);                    // format > 9: trailing varint
        writeVarInt(out, 0);                    // boneCount: no channels
        writeVarInt(out, 1);                    // timelineEventGroupsCount
        writeVarInt(out, timelineEventsCount);  // <-- the value under test
        // Enough events for the legitimate case (2). The hostile case is rejected before
        // it reads them, so writing them unconditionally is harmless.
        writeString(out, "");
        writeString(out, "");
        writeFloat(out, 20.0f);                 // group time
        writeVarInt(out, 0);                    // soundEffectsCount (format > 9)
        return out.toByteArray();
    }

    @Test
    void aCraftedTimelineEventCountIsRefusedByName() {
        Throwable failure = readExpectingFailure(modernPayload(1, 1_000_001));
        assertTrue(isTimelineCountRejection(failure),
                "a declared event count of 1000001 must be refused by name - not turned into an array "
                        + "allocation and then reported as an unrelated parse error; got: " + failure);
    }

    @Test
    void aLegitimateTimelineEventCountIsNotRejected() {
        // The same payload with a count inside the cap. Whatever happens next is this
        // prefix being incomplete, which is expected - what must not happen is the
        // count guard firing on a model that declares two events.
        Throwable failure = readExpectingFailure(modernPayload(1, 2));
        assertFalse(isTimelineCountRejection(failure),
                "a legitimate event count must not be rejected; got: " + failure);
    }

    @Test
    void theNeighbouringAnimationCountGuardStillHolds() {
        // animationCount already had a cap before this change; pinning it keeps the new
        // guard from being read as a replacement for the old one.
        Throwable failure = readExpectingFailure(modernPayload(1_000_001, 1));
        assertTrue(failure instanceof IllegalStateException
                        && String.valueOf(failure.getMessage()).contains("unreasonable animation count"),
                "the pre-existing animation-count cap must keep working; got: " + failure);
    }

    /** The failure of reading {@code payload}, or null when it parsed. */
    private static Throwable readExpectingFailure(byte[] payload) {
        try {
            YsmBinaryReader.read(payload);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static boolean isTimelineCountRejection(Throwable failure) {
        return failure instanceof IllegalStateException
                && String.valueOf(failure.getMessage()).contains("timeline event count");
    }

    private static void writeDword(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        writeDword(out, Float.floatToRawIntBits(value));
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        do {
            int b = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (remaining != 0);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, utf8.length);
        out.write(utf8, 0, utf8.length);
    }
}
