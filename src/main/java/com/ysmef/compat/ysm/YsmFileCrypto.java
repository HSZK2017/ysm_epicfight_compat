package com.ysmef.compat.ysm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Decryption of YSM .ysm binary model packages (crypto version 3), ported from
 * YSM's YsmCrypt (rip.ysm.security.YsmCrypt).
 *
 * File layout:
 * - ASCII header terminated by 0x00
 * - crypto version (int32 LE, must be 3)
 * - encrypted payload (modified XChaCha20 with rolling state updates)
 * - tail: key (32 bytes) + iv (24 bytes) + file hash (8 bytes LE)
 *
 * After decryption, an MT19937-based XOR whitening is removed, a small header
 * (2 + n bytes) is skipped, and the remaining payload is a YSM-flavored zstd
 * frame which is sanitized to standard zstd before decompression.
 */
public final class YsmFileCrypto {

    private static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    private static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;
    private static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;

    private YsmFileCrypto() {}

    /**
     * Decrypt and decompress a .ysm binary package, returning the raw binary
     * model data (to be consumed by YsmBinaryReader).
     */
    public static byte[] decryptYsmFile(byte[] fileData) {
        if (fileData == null || fileData.length < 8 + 24 + 32 + 8) {
            throw new IllegalArgumentException("Invalid YSM file: too short");
        }

        int headerLength = 0;
        while (headerLength < fileData.length && fileData[headerLength] != 0x00) {
            headerLength++;
        }

        int tailOffset = fileData.length - 64;
        byte[] key = Arrays.copyOfRange(fileData, tailOffset, tailOffset + 32);
        byte[] iv = Arrays.copyOfRange(fileData, tailOffset + 32, tailOffset + 56);

        // File integrity check (mirrors YsmCrypt#decryptYsmFile): the writer
        // hashes everything before the trailing 8 bytes with
        // SEED_FILE_VERIFICATION. Without this a corrupted/truncated package
        // silently decrypts into garbage and only fails much later in the zstd
        // or binary parse stage, which is much harder to diagnose.
        long fileHash = ByteBuffer.wrap(fileData, tailOffset + 56, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        long calculatedHash = new CityHash().hash64WithSeed(fileData, 0, fileData.length - 8, SEED_FILE_VERIFICATION);
        if (calculatedHash != fileHash) {
            throw new IllegalArgumentException("Invalid YSM file: file hash mismatch (corrupted or truncated file?)");
        }

        int ptrBinaryData = headerLength + 1;
        int crypto = ByteBuffer.wrap(fileData, ptrBinaryData, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (crypto != 3) {
            throw new IllegalArgumentException("Invalid YSM file: crypto version is not 3");
        }
        ptrBinaryData += 4;

        byte[] encryptedBinaryData = Arrays.copyOfRange(fileData, ptrBinaryData, tailOffset);
        byte[] chachaDecrypted = modifiedChaChaDecrypt(encryptedBinaryData, key, iv, SEED_RES_VERIFICATION);

        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        byte[] xorredData = mt19937Xor(chachaDecrypted, keyIv, SEED_KEY_DERIVATION);

        int n = ((xorredData[0] & 0xFF) | ((xorredData[1] & 0xFF) << 8)) & 0x3FF;
        int zstdOffset = 2 + n;
        byte[] zstdData = Arrays.copyOfRange(xorredData, zstdOffset, xorredData.length);

        byte[] washed = washZstd(zstdData);
        return zstdDecompress(washed);
    }

    /**
     * Decompress a standard zstd frame. YSM's frames do not carry a content-size
     * field, so a streaming decompressor is used instead of size-based allocation.
     */
    private static byte[] zstdDecompress(byte[] data) {
        try (com.github.luben.zstd.ZstdInputStream stream =
                     new com.github.luben.zstd.ZstdInputStream(new java.io.ByteArrayInputStream(data));
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = stream.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("zstd decompression failed: " + e.getMessage(), e);
        }
    }

    private static byte[] modifiedChaChaDecrypt(byte[] data, byte[] key, byte[] iv, long seed) {
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);

        CityHash ch = new CityHash();
        long hash2 = ch.hash64WithSeed(keyIv, seed);

        int nextRoundSize = (int) (((hash2 & 0x3FL) | 0x40L) << 6);
        int rounds = (int) (10 * Long.remainderUnsigned(hash2, 3) + 10);

        XChaCha20 ctx;
        try {
            ctx = new XChaCha20(key, iv, rounds);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init XChaCha20", e);
        }

        byte[] result = new byte[data.length];
        int blockPointer = 0;

        while (blockPointer < data.length) {
            if (blockPointer + nextRoundSize > data.length) {
                nextRoundSize = data.length - blockPointer;
            }
            byte[] decChunk = ctx.processBytes(data, blockPointer, nextRoundSize);
            System.arraycopy(decChunk, 0, result, blockPointer, nextRoundSize);
            blockPointer += nextRoundSize;

            if (blockPointer < data.length) {
                long resHash = ch.hash64WithSeed(decChunk, seed);
                nextRoundSize = ctx.updateStateYSM(resHash);
            }
        }
        return result;
    }

    private static byte[] mt19937Xor(byte[] data, byte[] currentKeyIv, long seedDerivation) {
        long mtSeed = new CityHash().hash64WithSeed(currentKeyIv, seedDerivation);
        MT19937 mt = new MT19937(mtSeed);
        byte[] result = new byte[data.length];

        int i = 0;
        while (i < data.length) {
            long rnd = mt.extract_number();
            for (int j = 0; j < 8 && i < data.length; ++j) {
                byte keystreamByte = (byte) ((rnd >>> (j * 8)) & 0xFF);
                result[i] = (byte) (data[i] ^ keystreamByte);
                i++;
            }
        }
        return result;
    }

    /**
     * Sanitizes a YSM-flavored zstd frame into a standard zstd frame
     * (ported from rip.ysm.zstd.YsmZstd.wash).
     */
    private static byte[] washZstd(byte[] data) {
        if (data == null || data.length < 5) {
            throw new IllegalArgumentException("Invalid zstd data length");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buffer.getInt(0);
        if (magic != 0xFD2FB528) {
            throw new IllegalArgumentException("Not a standard ZSTD magic number");
        }

        byte fhd = data[4];
        data[4] = (byte) (fhd & 0xFB);

        int frameHeaderSize = calculateFrameHeaderSize(fhd);
        int offset = 4 + frameHeaderSize;

        while (offset + 3 <= data.length) {
            int b0 = data[offset] & 0xFF;
            int b1 = data[offset + 1] & 0xFF;
            int b2 = data[offset + 2] & 0xFF;

            int lastBlock = (b0 >> 7) & 1;
            int blockTypeYSM = (b0 >> 5) & 3;

            int rawSize = ((b0 & 0x1F) << 16) | b1 | (b2 << 8);
            int cSize = rawSize ^ 0xD4E9;

            int blockTypeStd;
            switch (blockTypeYSM) {
                case 0 -> blockTypeStd = 2;
                case 1 -> blockTypeStd = 1;
                case 2 -> blockTypeStd = 3;
                case 3 -> blockTypeStd = 0;
                default -> throw new IllegalStateException("Unknown block type");
            }

            int stdHeader = lastBlock | (blockTypeStd << 1) | (cSize << 3);
            data[offset] = (byte) (stdHeader & 0xFF);
            data[offset + 1] = (byte) ((stdHeader >> 8) & 0xFF);
            data[offset + 2] = (byte) ((stdHeader >> 16) & 0xFF);

            int blockDataSize = (blockTypeStd == 1) ? 1 : cSize;
            offset += 3 + blockDataSize;

            if (lastBlock == 1) {
                break;
            }
        }
        return data;
    }

    private static int calculateFrameHeaderSize(byte fhd) {
        int size = 1;
        boolean singleSegment = ((fhd >> 5) & 1) == 1;

        int dictIdSize = 0;
        int dictIdBits = fhd & 3;
        if (dictIdBits == 1) dictIdSize = 1;
        else if (dictIdBits == 2) dictIdSize = 2;
        else if (dictIdBits == 3) dictIdSize = 4;

        int fcsSize = 0;
        int fcsBits = (fhd >> 6) & 3;
        if (fcsBits == 0) fcsSize = singleSegment ? 1 : 0;
        else if (fcsBits == 1) fcsSize = 2;
        else if (fcsBits == 2) fcsSize = 4;
        else if (fcsBits == 3) fcsSize = 8;

        int windowDescSize = singleSegment ? 0 : 1;
        return size + windowDescSize + dictIdSize + fcsSize;
    }
}
