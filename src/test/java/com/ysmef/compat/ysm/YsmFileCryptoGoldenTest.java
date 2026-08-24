package com.ysmef.compat.ysm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end golden test over a REAL .ysm binary model package (the encrypted
 * format produced by the official YSM tools).
 *
 * Skipped unless the package path is given:
 *   gradlew test -Dysmef.golden.ysm="C:\...\config\yes_steve_model\custom\xxx.ysm"
 *
 * Covers the whole decryption chain in one go:
 * - the file-hash is verified against the tail-stored CityHash64 value
 *   (the authoritative golden vector ships inside the file itself, produced by
 *   the official writer - no self-bootstrapped constants),
 * - modified XChaCha20 + MT19937 whitening + zstd wash/decompress must succeed,
 * - the decrypted payload must parse as a sane YSM binary model.
 */
@EnabledIfSystemProperty(named = "ysmef.golden.ysm", matches = ".+")
public class YsmFileCryptoGoldenTest {

    private static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;

    private static byte[] goldenFile() throws IOException {
        String path = System.getProperty("ysmef.golden.ysm");
        Path file = Paths.get(path);
        assertTrue(Files.isRegularFile(file), "golden .ysm file not found: " + path);
        return Files.readAllBytes(file);
    }

    /** The stored tail hash must equal CityHash64(header..tail-8) with the file seed. */
    @Test
    void storedFileHashMatchesCityHash64OfPayload() throws Exception {
        byte[] fileData = goldenFile();
        assertTrue(fileData.length >= 8 + 24 + 32 + 8, "file too short to be a .ysm package");

        int tailOffset = fileData.length - 64;
        long storedHash = ByteBuffer.wrap(fileData, tailOffset + 56, 8)
                .order(ByteOrder.LITTLE_ENDIAN).getLong();
        long calculated = new CityHash().hash64WithSeed(fileData, 0, fileData.length - 8, SEED_FILE_VERIFICATION);
        assertEquals(storedHash, calculated,
                "CityHash64(file payload) must equal the tail-stored hash (decryption would reject this file)");
    }

    /** Full chain: decrypt (hash check + XChaCha20 + MT19937) -> zstd wash+decompress -> parse. */
    @Test
    void decryptsAndParsesRealYsmPackage() throws Exception {
        byte[] fileData = goldenFile();
        byte[] decrypted = YsmFileCrypto.decryptYsmFile(fileData);
        assertNotNull(decrypted);
        assertTrue(decrypted.length > 4, "decrypted payload too small");

        YsmBinaryReader.BinaryModel model = YsmBinaryReader.read(decrypted);
        assertNotNull(model);
        assertFalse(model.mainBones.isEmpty(), "parsed model must have bones");
        assertFalse(model.textures.isEmpty(), "parsed model must have textures");
        assertNotNull(model.defaultTexture);
    }

    /** A package that carries wheel animations should surface them (extraAnimations). */
    @Test
    void parsesModelProperties() throws Exception {
        byte[] decrypted = YsmFileCrypto.decryptYsmFile(goldenFile());
        YsmBinaryReader.BinaryModel model = YsmBinaryReader.read(decrypted);
        assertTrue(model.widthScale > 0 && model.heightScale > 0,
                "width/height scale must be positive (got " + model.widthScale + "/" + model.heightScale + ")");
        assertNotNull(model.animations);
    }
}
