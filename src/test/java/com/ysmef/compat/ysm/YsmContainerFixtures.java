package com.ysmef.compat.ysm;

import com.github.luben.zstd.Zstd;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * Writers for the .ysm container generations, used to build fixtures in-test.
 *
 * <p>Why a writer per generation: a reader test that uses a real corpus file can only run on a
 * machine that has the corpus, and a reader tested against bytes it produced itself proves less
 * than one tested against the format's own writer. Both halves are therefore written from YSM's
 * sources, not from {@link YsmFileCrypto}:
 *
 * <ul>
 *   <li><b>Legacy versions 1 and 2</b> - {@code com.elfmcys.yesstevemodel.util.YesModelUtils
 *       #filesToYsm / #filesToBytes / #fileToBytes} of the LgeacyYSM tree: magic + big-endian
 *       version + 16-byte MD5 over the entry stream, then one entry per file
 *       (version 1: name, size, key, iv, AES-CBC(deflate(file));
 *       version 2: base64 name, size, encrypted key size, encrypted key, iv,
 *       AES-CBC(deflate(file)), the key itself encrypted under a key derived from the MD5 of the
 *       ciphertext). Version 2 is the generation 168 of the 170 local corpus packages are in.</li>
 *   <li><b>Version 3</b> - the inverse of {@code YsmFileCrypto#decryptYsmFile}: BOM + magic + text
 *       header + 0x00 + little-endian version 3 + the framed payload, with the tail key/iv and the
 *       CityHash64 file hash over everything before it.</li>
 * </ul>
 *
 * <p>The rolling-XChaCha20 loop below is a deliberate mirror of the production one: encryption and
 * decryption of this cipher are the same XOR under the same rolling state, so the writer is the
 * reader's loop with the plaintext chunk fed to the state update. It is a mirror, not an
 * independent implementation - what it checks is that the version-3 path still runs at all after
 * the container dispatch changed, not that the cipher is correct (the 736 real packages in the
 * corpus are the evidence for that).
 */
final class YsmContainerFixtures {

    static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;
    static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;

    private static final byte[] MAGIC = {0x59, 0x53, 0x47, 0x50};
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private YsmContainerFixtures() {}

    // ------------------------------------------------------------------
    // Legacy containers (versions 1 and 2)
    // ------------------------------------------------------------------

    /** A legacy container of the given version ({@link YsmFileCrypto#CONTAINER_LEGACY_I} or II). */
    static byte[] legacy(int version, Map<String, byte[]> files) {
        if (version != YsmFileCrypto.CONTAINER_LEGACY_I && version != YsmFileCrypto.CONTAINER_LEGACY_II) {
            throw new IllegalArgumentException("not a legacy container version: " + version);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Random random = new Random(0x59534750L + version);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            byte[] raw = file.getValue();
            byte[] key = new byte[16];
            byte[] iv = new byte[16];
            random.nextBytes(key);
            random.nextBytes(iv);
            byte[] encrypted = aesEncrypt(key, iv, deflate(raw));
            if (version == YsmFileCrypto.CONTAINER_LEGACY_I) {
                // The version-1 name is stored as raw UTF-8 (only the version-2 writer base64s it),
                // so the length prefix is the byte count, not the char count.
                byte[] name = file.getKey().getBytes(StandardCharsets.UTF_8);
                writeInt(body, name.length);
                body.writeBytes(name);
                writeInt(body, encrypted.length);
                body.writeBytes(key);
                body.writeBytes(iv);
            } else {
                byte[] encodedName = Base64.getEncoder().encode(file.getKey().getBytes(StandardCharsets.UTF_8));
                byte[] cipherSecretKey = aesEncrypt(keyFromMd5(encrypted), iv, key);
                writeInt(body, encodedName.length);
                body.writeBytes(encodedName);
                writeInt(body, encrypted.length);
                writeInt(body, cipherSecretKey.length);
                body.writeBytes(cipherSecretKey);
                body.writeBytes(iv);
            }
            body.writeBytes(encrypted);
        }
        byte[] bodyBytes = body.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeInt(out, version);
        out.writeBytes(md5(bodyBytes));
        out.writeBytes(bodyBytes);
        return out.toByteArray();
    }

    /**
     * The version-2 per-file key, from the writer: the MD5 of the encrypted file, folded
     * big-endian into a long and used as a {@link Random} seed.
     */
    static byte[] keyFromMd5(byte[] fileData) {
        long seed = 0L;
        for (byte b : md5(fileData)) {
            seed = (seed << 8) + (b & 0xFF);
        }
        byte[] key = new byte[16];
        new Random(seed).nextBytes(key);
        return key;
    }

    // ------------------------------------------------------------------
    // Version 3 (binary) container
    // ------------------------------------------------------------------

    /** A version-3 container holding {@code payload} as the decrypted model data. */
    static byte[] binary(byte[] payload) {
        byte[] key = new byte[32];
        byte[] iv = new byte[24];
        Random random = new Random(0x595347503L);
        random.nextBytes(key);
        random.nextBytes(iv);

        byte[] framed = framed(rewash(Zstd.compress(payload)));
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        byte[] whitened = mt19937Xor(framed, keyIv);
        byte[] encrypted = rollingXChaCha(whitened, key, iv, SEED_RES_VERIFICATION);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);
        out.writeBytes(MAGIC);
        out.writeBytes(("\r\n\r\n--- [ Metadata ] ---\r\n\r\n<name> fixture\r\n"
                + "<author> YSM-EF Compat test\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(0x00);
        writeIntLE(out, YsmFileCrypto.CONTAINER_BINARY);
        out.writeBytes(encrypted);
        out.writeBytes(key);
        out.writeBytes(iv);
        byte[] soFar = out.toByteArray();

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(soFar);
        writeLongLE(file, new CityHash().hash64WithSeed(
                soFar, 0, soFar.length, SEED_FILE_VERIFICATION));
        return file.toByteArray();
    }

    /** The 2-byte prefix whose low 10 bits give the zstd offset, all zero (offset = 2). */
    private static byte[] framed(byte[] ysmZstd) {
        byte[] out = new byte[2 + ysmZstd.length];
        System.arraycopy(ysmZstd, 0, out, 2, ysmZstd.length);
        return out;
    }

    /**
     * The inverse of {@code YsmFileCrypto#washZstd}: flag the frame as YSM-flavored and turn every
     * block header back into the YSM encoding (type mapping and the size XOR).
     */
    static byte[] rewash(byte[] frame) {
        byte[] data = frame.clone();
        int fhd = data[4] & 0xFF;
        data[4] = (byte) (fhd | 0x04);
        int offset = 4 + frameHeaderSize(fhd);
        while (offset + 3 <= data.length) {
            int header = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16);
            int lastBlock = header & 1;
            int blockTypeStd = (header >>> 1) & 3;
            int compressedSize = header >>> 3;
            int rawSize = compressedSize ^ 0xD4E9;
            int blockTypeYsm = switch (blockTypeStd) {
                case 0 -> 3;
                case 1 -> 1;
                case 2 -> 0;
                case 3 -> 2;
                default -> throw new IllegalStateException("unknown block type " + blockTypeStd);
            };
            data[offset] = (byte) ((lastBlock << 7) | (blockTypeYsm << 5) | ((rawSize >>> 16) & 0x1F));
            data[offset + 1] = (byte) rawSize;
            data[offset + 2] = (byte) (rawSize >>> 8);
            offset += 3 + (blockTypeStd == 1 ? 1 : compressedSize);
            if (lastBlock == 1) {
                break;
            }
        }
        return data;
    }

    private static int frameHeaderSize(int fhd) {
        boolean singleSegment = ((fhd >> 5) & 1) == 1;
        int dictionaryId = switch (fhd & 3) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            default -> 0;
        };
        int frameContentSize = switch ((fhd >> 6) & 3) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            default -> 8;
        };
        return 1 + (singleSegment ? 0 : 1) + dictionaryId + frameContentSize;
    }

    private static byte[] rollingXChaCha(byte[] data, byte[] key, byte[] iv, long seed) {
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        CityHash ch = new CityHash();
        long hash = ch.hash64WithSeed(keyIv, seed);
        int nextRoundSize = (int) (((hash & 0x3FL) | 0x40L) << 6);
        int rounds = (int) (10 * Long.remainderUnsigned(hash, 3) + 10);

        XChaCha20 ctx;
        try {
            ctx = new XChaCha20(key, iv, rounds);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        byte[] result = new byte[data.length];
        int pointer = 0;
        while (pointer < data.length) {
            if (pointer + nextRoundSize > data.length) {
                nextRoundSize = data.length - pointer;
            }
            byte[] chunk = ctx.processBytes(data, pointer, nextRoundSize);
            System.arraycopy(chunk, 0, result, pointer, nextRoundSize);
            // The writer's state update hashes the plaintext chunk, which is the byte sequence the
            // reader ends up hashing after decryption - the same call, on the same bytes.
            byte[] plainChunk = Arrays.copyOfRange(data, pointer, pointer + nextRoundSize);
            pointer += nextRoundSize;
            if (pointer < data.length) {
                nextRoundSize = ctx.updateStateYSM(ch.hash64WithSeed(plainChunk, seed));
            }
        }
        return result;
    }

    private static byte[] mt19937Xor(byte[] data, byte[] keyIv) {
        MT19937 mt = new MT19937(new CityHash().hash64WithSeed(keyIv, SEED_KEY_DERIVATION));
        byte[] result = new byte[data.length];
        int i = 0;
        while (i < data.length) {
            long rnd = mt.extract_number();
            for (int j = 0; j < 8 && i < data.length; ++j) {
                result[i] = (byte) (data[i] ^ (byte) ((rnd >>> (j * 8)) & 0xFF));
                i++;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Primitives shared with the writers above
    // ------------------------------------------------------------------

    static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    static byte[] aesEncrypt(byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("fixture AES failed", e);
        }
    }

    static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("fixture MD5 failed", e);
        }
    }

    static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    static void writeLongLE(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((value >>> (8 * i)) & 0xFF));
        }
    }
}
