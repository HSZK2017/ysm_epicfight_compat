package com.ysmef.compat.ysm;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decryption of YSM .ysm model containers, both generations.
 *
 * <p>Three container versions exist, and YSM itself reads all three:
 * <ul>
 *   <li><b>3</b> (binary): UTF-8 BOM + {@code YSGP} + a text header terminated by 0x00 + crypto
 *       version (int32 LE, 3) + encrypted payload + tail (key 32 + iv 24 + file hash 8).
 *       Modified XChaCha20 with rolling state updates, MT19937 XOR whitening, YSM-flavored zstd
 *       frame. Ported from YSM's {@code rip.ysm.security.YsmCrypt#decryptYsmFile}.</li>
 *   <li><b>1</b> and <b>2</b> (legacy): {@code YSGP} + container version (int32 <i>BE</i>, 1 or 2) +
 *       16-byte MD5 of everything after it + an entry stream of AES-CBC/deflate files.
 *       Ported from YSM's {@code rip.ysm.legacy.YesModelUtils} ({@code getYsmCryptoVersion},
 *       {@code input}, {@code ysmToFile}, {@code ysmToFileNew}); see
 *       {@link #decryptLegacyYsmFile(byte[])} for the byte-level layout.</li>
 * </ul>
 *
 * <p>Both generations start with the magic {@code YSGP} ("Ying Su Group"), but only version 3 is
 * preceded by a BOM - that, and the endianness of the following version word, is what tells them
 * apart. {@link #containerVersion(byte[])} is the single dispatch point; the two decode entry
 * points below it never guess.
 */
public final class YsmFileCrypto {

    // ------------------------------------------------------------------
    // Container generations
    // ------------------------------------------------------------------

    /** Container generation of a file whose generation could not be identified. */
    public static final int CONTAINER_UNKNOWN = -1;
    /** Legacy container, version 1: {@code YSGP} + BE int32 1 + MD5 + version-1 entries. */
    public static final int CONTAINER_LEGACY_I = 1;
    /** Legacy container, version 2: {@code YSGP} + BE int32 2 + MD5 + version-2 entries. */
    public static final int CONTAINER_LEGACY_II = 2;
    /** Binary container, version 3. */
    public static final int CONTAINER_BINARY = 3;

    /** {@code YSGP} - the magic both container generations carry. */
    private static final byte[] MAGIC_PREFIX = {0x59, 0x53, 0x47, 0x50};
    /** UTF-8 byte order mark, emitted in front of the magic by the version-3 writer. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** Header of a legacy container: magic (4) + version (4) + MD5 (16). */
    private static final int LEGACY_HEADER_LENGTH = 24;
    /** Trailing block of a binary container: key (32) + iv (24) + file hash (8). */
    private static final int BINARY_TAIL_LENGTH = 64;
    /** The header the legacy writer prepends to the version-2 entry secret key. */
    private static final int AES_IV_LENGTH = 16;
    private static final int AES_KEY_LENGTH = 16;

    private static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    private static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;
    private static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;

    /**
     * Upper bound for the fully decompressed model payload. A valid package is a
     * single avatar (geometry + textures + animations), so anything beyond this
     * is either corrupt or a deliberate decompression bomb; cap it before
     * ByteArrayOutputStream can exhaust the heap. Overridable with the
     * ysm_ef_compat.max_decompressed_bytes system property for unusually large
     * (but trusted) packages.
     */
    private static final long MAX_DECOMPRESSED_BYTES = Math.max(1L,
            Long.getLong("ysm_ef_compat.max_decompressed_bytes", 512L * 1024L * 1024L).longValue());
    /** Mirrors YsmModelPackage's source-file cap for direct decrypt callers. */
    private static final long MAX_ENCRYPTED_PACKAGE_BYTES = Math.max(1L,
            Long.getLong("ysm_ef_compat.max_package_bytes", 512L * 1024L * 1024L).longValue());

    private YsmFileCrypto() {}

    /**
     * Which container generation a file is, without decrypting anything:
     * {@link #CONTAINER_BINARY}, {@link #CONTAINER_LEGACY_I}, {@link #CONTAINER_LEGACY_II} or
     * {@link #CONTAINER_UNKNOWN}.
     *
     * <p>The dispatch rule is YSM's own (ModernYSM {@code rip.ysm.legacy.YesModelUtils
     * #getYsmCryptoVersion}): a BOM in front of the magic means the binary generation, a bare
     * magic followed by a big-endian 1 or 2 means that legacy generation. Nothing else is a
     * container this code knows, and saying {@code UNKNOWN} is the honest answer - the caller can
     * then report the number it actually saw instead of a decryption error.
     */
    public static int containerVersion(byte[] fileData) {
        if (fileData == null || fileData.length < 8) {
            return CONTAINER_UNKNOWN;
        }
        if (startsWithBomMagic(fileData)) {
            return CONTAINER_BINARY;
        }
        int declared = declaredLegacyVersion(fileData);
        return declared == CONTAINER_LEGACY_I || declared == CONTAINER_LEGACY_II
                ? declared : CONTAINER_UNKNOWN;
    }

    /**
     * The big-endian version word of a bare-magic container, or {@link #CONTAINER_UNKNOWN} when
     * the file does not start with the bare magic at all. Exists so an unsupported generation can
     * be named ("unsupported container version 7") instead of being reported as a corrupt file.
     */
    public static int declaredLegacyVersion(byte[] fileData) {
        if (fileData == null || fileData.length < 8 || !startsWithMagic(fileData)) {
            return CONTAINER_UNKNOWN;
        }
        return ByteBuffer.wrap(fileData, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    /**
     * Decrypt and decompress a version-3 binary package, returning the raw binary
     * model data (to be consumed by YsmBinaryReader).
     *
     * <p>Legacy containers are refused here by name rather than by exception
     * fallback: they hold files, not a binary model, so they have to go through
     * {@link #decryptLegacyYsmFile(byte[])} - which the caller can tell beforehand with
     * {@link #containerVersion(byte[])}.
     */
    public static byte[] decryptYsmFile(byte[] fileData) {
        if (fileData == null || fileData.length < 8 + 24 + 32 + 8) {
            throw new IllegalArgumentException("Invalid YSM file: too short");
        }
        if (fileData.length > MAX_ENCRYPTED_PACKAGE_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid YSM file: package exceeds the " + MAX_ENCRYPTED_PACKAGE_BYTES + " byte safety limit");
        }

        int container = containerVersion(fileData);
        if (container == CONTAINER_LEGACY_I || container == CONTAINER_LEGACY_II) {
            throw new IllegalArgumentException("Invalid YSM file: container version " + container
                    + " is the legacy file container, not a binary model; use decryptLegacyYsmFile");
        }
        if (container == CONTAINER_UNKNOWN && startsWithMagic(fileData)) {
            throw new IllegalArgumentException("Invalid YSM file: unsupported container version "
                    + declaredLegacyVersion(fileData));
        }

        int headerLength = 0;
        while (headerLength < fileData.length && fileData[headerLength] != 0x00) {
            headerLength++;
        }
        if (headerLength >= fileData.length) {
            throw new IllegalArgumentException("Invalid YSM file: missing header terminator");
        }

        int tailOffset = fileData.length - BINARY_TAIL_LENGTH;
        int ptrBinaryData = headerLength + 1;
        if (tailOffset < ptrBinaryData + 4) {
            throw new IllegalArgumentException("Invalid YSM file: encrypted payload too short");
        }
        byte[] key = Arrays.copyOfRange(fileData, tailOffset, tailOffset + 32);
        byte[] iv = Arrays.copyOfRange(fileData, tailOffset + 32, tailOffset + 56);

        // The version is read BEFORE the integrity check, and deliberately so: a container of
        // another generation has a different layout, so its "hash" is meaningless, and checking it
        // first reports every unsupported file as "corrupted or truncated" - which sent a real
        // investigation after file damage that did not exist. A version-3 file with a broken hash
        // still gets the hash message.
        int crypto = ByteBuffer.wrap(fileData, ptrBinaryData, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (crypto != CONTAINER_BINARY) {
            throw new IllegalArgumentException("Invalid YSM file: unsupported container version " + crypto);
        }
        ptrBinaryData += 4;

        // File integrity check (mirrors YsmCrypt#decryptYsmFile): the writer
        // hashes everything before the trailing 8 bytes with
        // SEED_FILE_VERIFICATION. Without this a corrupted/truncated package
        // silently decrypts into garbage and only fails much later in the zstd
        // or binary parse stage, which is much harder to diagnose.
        long fileHash = ByteBuffer.wrap(fileData, tailOffset + 56, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        long calculatedHash = new CityHash().hash64WithSeed(fileData, 0, fileData.length - 8, SEED_FILE_VERIFICATION);
        if (calculatedHash != fileHash) {
            throw new IllegalArgumentException(
                    "Invalid YSM file: file hash mismatch (modified after packaging, or corrupted/truncated)");
        }

        byte[] encryptedBinaryData = Arrays.copyOfRange(fileData, ptrBinaryData, tailOffset);
        byte[] chachaDecrypted = modifiedChaChaDecrypt(encryptedBinaryData, key, iv, SEED_RES_VERIFICATION);

        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        byte[] xorredData = mt19937Xor(chachaDecrypted, keyIv, SEED_KEY_DERIVATION);

        int n = ((xorredData[0] & 0xFF) | ((xorredData[1] & 0xFF) << 8)) & 0x3FF;
        int zstdOffset = 2 + n;
        if (zstdOffset > xorredData.length) {
            throw new IllegalArgumentException("Invalid YSM file: zstd payload offset out of range");
        }
        byte[] zstdData = Arrays.copyOfRange(xorredData, zstdOffset, xorredData.length);

        byte[] washed = washZstd(zstdData);
        return zstdDecompress(washed);
    }

    /**
     * Decode a legacy container (versions 1 and 2) into the files it holds, keyed by the names
     * stored inside it. The result is a <i>folder package in a file</i> - YSM reads it with its
     * folder deserializer, not with its binary one - so this is a different model representation,
     * not a different encoding of the same one.
     *
     * <p>Layout of both generations (reader: {@code rip.ysm.legacy.YesModelUtils#inputInternal},
     * present in ModernYSM and OpenYSM; writer of the version-2 entries: LgeacyYSM
     * {@code com.elfmcys.yesstevemodel.util.YesModelUtils#filesToYsm / #filesToBytes / #fileToBytes},
     * which writes exactly this stream):
     * <pre>
     *   offset 0   "YSGP"
     *   offset 4   container version, int32 BIG endian (1 or 2)
     *   offset 8   16-byte MD5 over everything from offset 24 to the end of the file
     *   offset 24  entry stream, read until it is exhausted
     * </pre>
     *
     * <p>Not to be confused with the same fork's {@code data.EncryptTools}: that one writes an
     * older, single-model container with the same magic, version and MD5 header but a single
     * AES(deflate(serialized model)) body plus an external password file. None of the 170 legacy
     * packages in the local corpus are of that generation.
     *
     * <p>Version 1 entry ({@code ysmToFile}): name length (int32 BE) + UTF-8 name,
     * file size (int32 BE), AES key (16), AES iv (16), then the file: AES/CBC/PKCS5 over a
     * zlib-wrapped deflate stream of the raw bytes.
     *
     * <p>Version 2 entry ({@code ysmToFileNew}): name length (int32 BE) + base64 name carrying the
     * UTF-8 file name, file size (int32 BE), cipher-key size (int32 BE), the AES-encrypted file
     * key, iv (16), then the file. The file key is decrypted with a key derived from the
     * <i>MD5 of the encrypted file bytes</i> seeded into {@link Random} - which is why the same
     * plaintext encrypts differently in every container and why no static key exists.
     *
     * @throws IllegalArgumentException when the file is not a legacy container, its checksum does
     *         not match, or an entry is truncated - with the reason, never as "corrupted" when the
     *         real answer is "a generation this reader does not know".
     */
    public static Map<String, byte[]> decryptLegacyYsmFile(byte[] fileData) {
        if (fileData == null || fileData.length < LEGACY_HEADER_LENGTH) {
            throw new IllegalArgumentException("Invalid YSM file: too short for a legacy container");
        }
        if (fileData.length > MAX_ENCRYPTED_PACKAGE_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid YSM file: package exceeds the " + MAX_ENCRYPTED_PACKAGE_BYTES + " byte safety limit");
        }
        int version = declaredLegacyVersion(fileData);
        if (version != CONTAINER_LEGACY_I && version != CONTAINER_LEGACY_II) {
            if (version == CONTAINER_UNKNOWN) {
                throw new IllegalArgumentException(startsWithBomMagic(fileData)
                        ? "Invalid YSM file: this is a version-3 binary container, not a legacy one"
                        : "Invalid YSM file: not a YSGP container (no container magic)");
            }
            throw new IllegalArgumentException("Invalid YSM file: unsupported container version " + version);
        }

        byte[] body = Arrays.copyOfRange(fileData, LEGACY_HEADER_LENGTH, fileData.length);
        byte[] storedMd5 = Arrays.copyOfRange(fileData, 8, LEGACY_HEADER_LENGTH);
        if (!MessageDigest.isEqual(storedMd5, md5(body))) {
            throw new IllegalArgumentException("Invalid YSM file: legacy container checksum mismatch"
                    + " (modified after packaging, or corrupted/truncated)");
        }
        return readLegacyEntries(body, version);
    }

    // ------------------------------------------------------------------
    // Legacy container entries
    // ------------------------------------------------------------------

    private static Map<String, byte[]> readLegacyEntries(byte[] body, int version) {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        Map<String, byte[]> files = new LinkedHashMap<>();
        long decompressedBudget = MAX_DECOMPRESSED_BYTES;
        while (in.available() > 0) {
            int before = in.available();
            String name = version == CONTAINER_LEGACY_I
                    ? readLegacyString(in, false)
                    : readLegacyString(in, true);
            byte[] raw = version == CONTAINER_LEGACY_I ? readLegacyEntryV1(in) : readLegacyEntryV2(in);
            decompressedBudget -= raw.length;
            if (decompressedBudget < 0) {
                throw new IllegalArgumentException("Invalid YSM file: legacy container inflates beyond the "
                        + MAX_DECOMPRESSED_BYTES + " byte safety limit");
            }
            files.put(name, raw);
            if (in.available() >= before) {
                throw new IllegalArgumentException(
                        "Invalid YSM file: legacy container entry made no progress (unknown entry layout?)");
            }
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Invalid YSM file: legacy container holds no files");
        }
        return files;
    }

    /**
     * Version-1 entry: name, size, key (16), iv (16), file. The key and iv are stored in clear -
     * the container's own MD5 is the only protection this generation has.
     */
    private static byte[] readLegacyEntryV1(ByteArrayInputStream in) {
        int size = readLegacyInt(in);
        byte[] key = readLegacyBlock(in, AES_KEY_LENGTH);
        byte[] iv = readLegacyBlock(in, AES_IV_LENGTH);
        byte[] fileData = readLegacyBlock(in, size);
        return inflate(aesDecrypt(key, iv, fileData), MAX_DECOMPRESSED_BYTES);
    }

    /** Version-2 entry: name, size, encrypted key size, encrypted key, iv (16), file. */
    private static byte[] readLegacyEntryV2(ByteArrayInputStream in) {
        int fileSize = readLegacyInt(in);
        int cipherKeySize = readLegacyInt(in);
        byte[] cipherSecretKey = readLegacyBlock(in, cipherKeySize);
        byte[] iv = readLegacyBlock(in, AES_IV_LENGTH);
        byte[] fileData = readLegacyBlock(in, fileSize);
        byte[] secretKey = aesDecrypt(legacyKeyFromFileData(fileData), iv, cipherSecretKey);
        return inflate(aesDecrypt(secretKey, iv, fileData), MAX_DECOMPRESSED_BYTES);
    }

    /**
     * The version-2 per-file key: {@code MD5(encryptedFileBytes)} folded big-endian into a long
     * (wrapping at 64 bits, like YSM's {@code toLong}) and used as a {@link Random} seed for the
     * 16 key bytes.
     */
    private static byte[] legacyKeyFromFileData(byte[] fileData) {
        long seed = 0L;
        for (byte b : md5(fileData)) {
            seed = (seed << 8) + (b & 0xFF);
        }
        byte[] key = new byte[AES_KEY_LENGTH];
        new Random(seed).nextBytes(key);
        return key;
    }

    private static String readLegacyString(ByteArrayInputStream in, boolean base64) {
        int size = readLegacyInt(in);
        byte[] raw = readLegacyBlock(in, size);
        if (!base64) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        try {
            return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid YSM file: legacy container entry name is not valid base64", e);
        }
    }

    private static int readLegacyInt(ByteArrayInputStream in) {
        if (in.available() < 4) {
            throw new IllegalArgumentException("Invalid YSM file: legacy container entry is truncated");
        }
        return ByteBuffer.wrap(readLegacyExact(in, 4)).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    /**
     * A length-prefixed block. The declared size is validated against what is left before anything
     * is allocated: a corrupt (or hostile) length field would otherwise be an out-of-memory error
     * instead of a parse failure.
     */
    private static byte[] readLegacyBlock(ByteArrayInputStream in, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Invalid YSM file: legacy container declares a negative size");
        }
        if (size > in.available()) {
            throw new IllegalArgumentException("Invalid YSM file: legacy container entry declares " + size
                    + " bytes but only " + in.available() + " remain");
        }
        return readLegacyExact(in, size);
    }

    /**
     * Read exactly {@code size} bytes. {@link java.io.InputStream#readNBytes(int)} would be the
     * obvious call, but it declares an IOException this reader has no answer for; a
     * ByteArrayInputStream's own {@code read} neither throws nor short-reads.
     */
    private static byte[] readLegacyExact(ByteArrayInputStream in, int size) {
        byte[] block = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = in.read(block, offset, size - offset);
            if (read <= 0) {
                throw new IllegalArgumentException("Invalid YSM file: legacy container entry is truncated");
            }
            offset += read;
        }
        return block;
    }

    /** AES/CBC/PKCS5Padding, the cipher of both legacy generations (YSM's {@code AESUtil}). */
    private static byte[] aesDecrypt(byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("YSM legacy container AES decryption failed: " + e, e);
        }
    }

    /**
     * zlib-wrapped deflate, the compression of both legacy generations (YSM's
     * {@code DeflateUtil#decompressBytes}). Bounded, and it refuses to spin: an inflater that
     * wants input it will never get is a damaged stream, not a slow one.
     */
    private static byte[] inflate(byte[] input, long budget) {
        if (input.length == 0) {
            return new byte[0];
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.max(64L, Math.min((long) input.length * 4L, 1L << 20)));
            byte[] buffer = new byte[65536];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0) {
                    throw new IllegalArgumentException(
                            "Invalid YSM file: legacy container deflate stream is truncated or stalled");
                }
                out.write(buffer, 0, read);
                if (out.size() > budget) {
                    throw new IllegalArgumentException("Invalid YSM file: legacy container inflates beyond the "
                            + budget + " byte safety limit");
                }
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("Invalid YSM file: legacy container deflate stream is damaged", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable in this JVM", e);
        }
    }

    private static boolean startsWithMagic(byte[] fileData) {
        return fileData.length >= MAGIC_PREFIX.length
                && fileData[0] == MAGIC_PREFIX[0] && fileData[1] == MAGIC_PREFIX[1]
                && fileData[2] == MAGIC_PREFIX[2] && fileData[3] == MAGIC_PREFIX[3];
    }

    private static boolean startsWithBomMagic(byte[] fileData) {
        return fileData.length >= UTF8_BOM.length + MAGIC_PREFIX.length
                && fileData[0] == UTF8_BOM[0] && fileData[1] == UTF8_BOM[1] && fileData[2] == UTF8_BOM[2]
                && fileData[3] == MAGIC_PREFIX[0] && fileData[4] == MAGIC_PREFIX[1]
                && fileData[5] == MAGIC_PREFIX[2] && fileData[6] == MAGIC_PREFIX[3];
    }

    /**
     * Decompress a standard zstd frame. YSM's frames do not carry a content-size
     * field, so a streaming decompressor is used instead of size-based allocation.
     * The total output is capped to {@link #MAX_DECOMPRESSED_BYTES} so a malicious
     * or corrupt frame cannot balloon the heap.
     */
    private static byte[] zstdDecompress(byte[] data) {
        try (com.github.luben.zstd.ZstdInputStream stream =
                     new com.github.luben.zstd.ZstdInputStream(new java.io.ByteArrayInputStream(data));
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            long total = 0L;
            int read;
            while ((read = stream.read(buf)) != -1) {
                total += read;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new java.io.IOException(
                            "zstd output exceeds the " + MAX_DECOMPRESSED_BYTES + " byte safety limit");
                }
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
