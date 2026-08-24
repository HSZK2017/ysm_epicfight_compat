package com.ysmef.compat.ysm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fixed-input CityHash regression vector. The implementation's correctness is
 * pinned end-to-end by {@link YsmFileCryptoGoldenTest} (against the tail-stored
 * hashes of real .ysm files written by the official tool); this vector locks a
 * pure-Java regression without needing an external model file. The EXPECTED
 * value was bootstrapped once from the implementation while the file-hash test
 * was green.
 */
public class CityHashGoldenTest {

    private static final byte[] FIXED_INPUT =
            "ysm-epicfight-compat cityhash golden input 0123456789 abcdefghijklmnopqrstuvwxyz"
                    .getBytes(StandardCharsets.UTF_8);
    private static final long SEED = 0x9E5599DB80C67C29L;
    /** Bootstrapped value - do not change without re-running the file-hash golden test. */
    private static final long EXPECTED = 0xD8374FF25E11C124L;

    @Test
    void fixedInputVector() {
        assertEquals(EXPECTED, new CityHash().hash64WithSeed(FIXED_INPUT, SEED));
    }

    @Test
    void rangeVariantMatchesWholeArray() {
        byte[] data = FIXED_INPUT;
        assertEquals(new CityHash().hash64WithSeed(data, SEED),
                new CityHash().hash64WithSeed(data, 0, data.length, SEED));
    }

    @Test
    void rangeVariantMatchesOffsetSlice() {
        byte[] data = FIXED_INPUT;
        int offset = 10;
        int length = data.length - offset - 5;
        assertEquals(new CityHash().hash64WithSeed(data, offset, length, SEED),
                new CityHash().hash64WithSeed(
                        java.util.Arrays.copyOfRange(data, offset, offset + length), SEED));
    }
}
