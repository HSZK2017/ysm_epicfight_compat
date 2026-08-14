package com.ysmef.compat.ysm;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Port of YSM's CityHash (rip.ysm.algorithms.CityHash), required by the .ysm
 * binary package decryption (key derivation and rolling state updates).
 */
public class CityHash {
    private static final boolean IS_BIG_EDIAN = !"little".equals(System.getProperty("sun.cpu.endian"));
    public static final long k0 = 0xE4986A230E5AAA17L;
    public static final long k1 = 0x91AF10802CAB25A5L;
    public static final long k2 = 0xAF29CE778879D9C7L;
    public static final long kMul = 0xDE0F6EE09BDBAB91L;
    public static final int c1 = 0xcc9e2d51;
    public static final int c2 = 0x1b873593;

    /** Base offset applied to every array access; 0 for whole-array hashing. */
    private int off = 0;

    /** Explicit hash length; -1 means "rest of the array from off" (whole-array mode). */
    private int len = -1;

    public long hash64(byte[] byteArray) {
        int len = this.len >= 0 ? this.len : byteArray.length - off;
        if (len <= 32) {
            if (len <= 16) {
                return hashLen0to16(byteArray);
            } else {
                return hashLen17to32(byteArray);
            }
        } else if (len <= 64) {
            return hashLen33to64(byteArray);
        }

        long x = fetch64(byteArray, len - 40);
        long y = fetch64(byteArray, len - 16) + fetch64(byteArray, len - 56);
        long z = hashLen16(fetch64(byteArray, len - 48) + len, fetch64(byteArray, len - 24));
        Number128 v = weakHashLen32WithSeeds(byteArray, len - 64, len, z);
        Number128 w = weakHashLen32WithSeeds(byteArray, len - 32, y + k1, x);
        x = x * k1 + fetch64(byteArray, 0);

        len = (len - 1) & ~63;
        int pos = 0;
        do {
            x = rotate(x + y + v.getLowValue() + fetch64(byteArray, pos + 8), 37) * k1;
            y = rotate(y + v.getHiValue() + fetch64(byteArray, pos + 48), 42) * k1;
            x ^= w.getHiValue();
            y += v.getLowValue() + fetch64(byteArray, pos + 40);
            z = rotate(z + w.getLowValue(), 33) * k1;
            v = weakHashLen32WithSeeds(byteArray, pos, v.getHiValue() * k1, x + w.getLowValue());
            w = weakHashLen32WithSeeds(byteArray, pos + 32, z + w.getHiValue(), y + fetch64(byteArray, pos + 16));
            long swapValue = x;
            x = z;
            z = swapValue;
            pos += 64;
            len -= 64;
        } while (len != 0);
        return hashLen16(hashLen16(v.getLowValue(), w.getLowValue()) + shiftMix(y) * k1 + z,
                hashLen16(v.getHiValue(), w.getHiValue()) + x);
    }

    public long hash64WithSeeds(byte[] raw, long seed0, long seed1) {
        return hashLen16(hash64(raw) - seed0, seed1);
    }

    public long hash64WithSeed(byte[] raw, long seed) {
        return hash64WithSeeds(raw, k2, seed);
    }

    /**
     * Range variant: hash raw[offset, offset+length) with the given seed,
     * without copying the array. Instances are per-use (see YsmFileCrypto), so
     * mutating the offset for the duration of the call is safe.
     */
    public long hash64WithSeed(byte[] raw, int offset, int length, long seed) {
        if (offset == 0 && length == raw.length) {
            return hash64WithSeed(raw, seed);
        }
        this.off = offset;
        this.len = length;
        try {
            return hash64WithSeed(raw, seed);
        } finally {
            this.off = 0;
            this.len = -1;
        }
    }

    private long hashLen0to16(byte[] byteArray) {
        int len = this.len >= 0 ? this.len : byteArray.length - off;
        if (len >= 8) {
            long mul = k2 + len * 2;
            long a = fetch64(byteArray, 0) + k2;
            long b = fetch64(byteArray, len - 8);
            long c = rotate(b, 37) * mul + a;
            long d = (rotate(a, 25) + b) * mul;
            return hashLen16(c, d, mul);
        }
        if (len >= 4) {
            long mul = k2 + len * 2;
            long a = fetch32(byteArray, 0) & 0xffffffffL;
            return hashLen16(len + (a << 3), fetch32(byteArray, len - 4) & 0xffffffffL, mul);
        }
        if (len > 0) {
            int a = byteArray[off] & 0xff;
            int b = byteArray[off + (len >>> 1)] & 0xff;
            int c = byteArray[off + len - 1] & 0xff;
            int y = a + (b << 8);
            int z = len + (c << 2);
            return shiftMix(y * k2 ^ z * k0) * k2;
        }
        return k2;
    }

    private long hashLen17to32(byte[] byteArray) {
        int len = this.len >= 0 ? this.len : byteArray.length - off;
        long mul = k2 + len * 2;
        long a = fetch64(byteArray, 0) * k1;
        long b = fetch64(byteArray, 8);
        long c = fetch64(byteArray, len - 8) * mul;
        long d = fetch64(byteArray, len - 16) * k2;
        return hashLen16(rotate(a + b, 43) + rotate(c, 30) + d,
                a + rotate(b + k2, 18) + c, mul);
    }

    private long hashLen33to64(byte[] byteArray) {
        int len = this.len >= 0 ? this.len : byteArray.length - off;
        long mul = k2 + len * 2;
        long a = fetch64(byteArray, 0) * k2;
        long b = fetch64(byteArray, 8);
        long c = fetch64(byteArray, len - 24);
        long d = fetch64(byteArray, len - 32);
        long e = fetch64(byteArray, 16) * k2;
        long f = fetch64(byteArray, 24) * 9;
        long g = fetch64(byteArray, len - 8);
        long h = fetch64(byteArray, len - 16) * mul;
        long u = rotate(a + g, 43) + (rotate(b, 30) + c) * 9;
        long v = ((a + g) ^ d) + f + 1;
        long w = Long.reverseBytes((u + v) * mul) + h;
        long x = rotate(e + f, 42) + c;
        long y = (Long.reverseBytes((v + w) * mul) + g) * mul;
        long z = e + f + c;
        a = Long.reverseBytes((x + z) * mul + y) + b;
        b = shiftMix((z + a) * mul + d + h) * mul;
        return b + x;
    }

    private long loadUnaligned64(final byte[] byteArray, final int start) {
        long result = 0;
        OrderIter orderIter = new OrderIter(8, CityHash.IS_BIG_EDIAN);
        while (orderIter.hasNext()) {
            int next = orderIter.next();
            long value = (byteArray[next + start] & 0xffL) << (next * 8);
            result |= value;
        }
        return result;
    }

    private int loadUnaligned32(final byte[] byteArray, final int start) {
        int result = 0;
        OrderIter orderIter = new OrderIter(4, CityHash.IS_BIG_EDIAN);
        while (orderIter.hasNext()) {
            int next = orderIter.next();
            int value = (byteArray[next + start] & 0xff) << (next * 8);
            result |= value;
        }
        return result;
    }

    private long fetch64(byte[] byteArray, final int start) {
        return loadUnaligned64(byteArray, off + start);
    }

    private int fetch32(byte[] byteArray, final int start) {
        return loadUnaligned32(byteArray, off + start);
    }

    private long rotate(long val, int shift) {
        return shift == 0 ? val : ((val >>> shift) | (val << (64 - shift)));
    }

    private long hashLen16(long u, long v, long mul) {
        long a = (u ^ v) * mul;
        a ^= (a >>> 47);
        long b = (v ^ a) * mul;
        b ^= (b >>> 47);
        b *= mul;
        return b;
    }

    private long hashLen16(long u, long v) {
        return hash128to64(new Number128(u, v));
    }

    private long hash128to64(final Number128 number128) {
        long low = number128.getLowValue();
        long high = number128.getHiValue();

        long term1 = (low ^ high) * kMul;
        long term2 = (shiftMix(term1) ^ low) * kMul;

        return kMul * shiftMix(term2);
    }

    private long shiftMix(long val) {
        return val ^ (val >>> 47);
    }

    private Number128 weakHashLen32WithSeeds(
            long w, long x, long y, long z, long a, long b) {
        a += w;
        b = rotate(b + a + z, 21);
        long c = a;
        a += x;
        a += y;
        b += rotate(a, 44);
        return new Number128(a + z, b + c);
    }

    private Number128 weakHashLen32WithSeeds(
            byte[] byteArray, int start, long a, long b) {
        return weakHashLen32WithSeeds(fetch64(byteArray, start), fetch64(byteArray, start + 8), fetch64(byteArray, start + 16), fetch64(byteArray, start + 24), a, b);
    }

    private static class OrderIter {
        private final int size;
        private final boolean isBigEdian;
        private int index;

        OrderIter(int size, boolean isBigEdian) {
            this.size = size;
            this.isBigEdian = isBigEdian;
        }

        boolean hasNext() {
            return index < size;
        }

        int next() {
            if (!isBigEdian) {
                return index++;
            } else {
                return size - 1 - index++;
            }
        }
    }

    static class Number128 {
        private long lowValue;
        private long hiValue;

        public Number128(long lowValue, long hiValue) {
            this.setLowValue(lowValue);
            this.setHiValue(hiValue);
        }

        public long getLowValue() {
            return lowValue;
        }

        public long getHiValue() {
            return hiValue;
        }

        public void setLowValue(long lowValue) {
            this.lowValue = lowValue;
        }

        public void setHiValue(long hiValue) {
            this.hiValue = hiValue;
        }
    }
}
