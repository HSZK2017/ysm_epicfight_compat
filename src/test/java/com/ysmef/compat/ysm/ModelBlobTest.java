package com.ysmef.compat.ysm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model transfer's unit of exchange. Two properties carry the feature: the hash identifies a
 * package by content alone (so a client with an identical copy never downloads, and one with a
 * different copy always does), and unpacking data from a server cannot write outside its target.
 */
class ModelBlobTest {

    private static final long LIMIT = 16L * 1024 * 1024;

    @TempDir
    Path temp;

    private static Path folderPackage(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("models"));
        Files.createDirectories(dir.resolve("textures"));
        Files.writeString(dir.resolve("ysm.json"), "{\"spec\":2}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("models/main.json"), "{\"bones\":[]}", StandardCharsets.UTF_8);
        Files.write(dir.resolve("textures/skin.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3});
        return dir;
    }

    private static YsmModelPackage.Located folder(String root, Path dir) {
        return new YsmModelPackage.Located(root, dir.getParent(), dir, false);
    }

    @Test
    void folderRoundTripReproducesEveryFileAndTheHash() throws IOException {
        YsmModelPackage.Located source = folder("custom", folderPackage(temp.resolve("src/model")));
        byte[] blob = ModelBlob.pack(source, LIMIT);
        assertEquals(ModelBlob.sha256(source, LIMIT), ModelBlob.sha256(blob));

        Path target = temp.resolve("dst/model");
        ModelBlob.unpack(new ByteArrayInputStream(blob), false, target, LIMIT);

        assertEquals("{\"spec\":2}", Files.readString(target.resolve("ysm.json")));
        assertArrayEquals(Files.readAllBytes(source.path().resolve("textures/skin.png")),
                Files.readAllBytes(target.resolve("textures/skin.png")));
        assertEquals(ModelBlob.sha256(blob), ModelBlob.sha256(folder("server", target), LIMIT));
    }

    @Test
    void hashIgnoresModificationTimesAndTheRootItSitsUnder() throws IOException {
        Path a = folderPackage(temp.resolve("a/model"));
        Path b = folderPackage(temp.resolve("b/model"));
        Files.setLastModifiedTime(b.resolve("ysm.json"), FileTime.fromMillis(1_000_000L));
        assertEquals(ModelBlob.sha256(folder("custom", a), LIMIT), ModelBlob.sha256(folder("auth", b), LIMIT));
        // The signature is the cheap "did anything change on disk" check and does see the time.
        assertNotEquals(ModelBlob.signature(folder("custom", a)), ModelBlob.signature(folder("custom", b)));
    }

    @Test
    void hashChangesWithAnyContentChange() throws IOException {
        Path a = folderPackage(temp.resolve("a/model"));
        Path b = folderPackage(temp.resolve("b/model"));
        Files.write(b.resolve("textures/skin.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 4});
        assertNotEquals(ModelBlob.sha256(folder("custom", a), LIMIT), ModelBlob.sha256(folder("custom", b), LIMIT));
    }

    @Test
    void binaryPackageRoundTrips() throws IOException {
        Path file = temp.resolve("src/model.ysm");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {'Y', 'S', 'G', 'P', 9, 8, 7});
        YsmModelPackage.Located source = new YsmModelPackage.Located("custom", file.getParent(), file, true);

        Path target = temp.resolve("dst/group/model.ysm");
        ModelBlob.unpack(new ByteArrayInputStream(ModelBlob.pack(source, LIMIT)), true, target, LIMIT);
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(target));
    }

    @Test
    void blobOfTheOtherKindIsRefused() throws IOException {
        byte[] blob = ModelBlob.pack(folder("custom", folderPackage(temp.resolve("src/model"))), LIMIT);
        assertThrows(IOException.class,
                () -> ModelBlob.unpack(new ByteArrayInputStream(blob), true, temp.resolve("dst.ysm"), LIMIT));
    }

    @Test
    void oversizedPackageIsRefusedOnBothSides() throws IOException {
        YsmModelPackage.Located source = folder("custom", folderPackage(temp.resolve("src/model")));
        assertThrows(IOException.class, () -> ModelBlob.pack(source, 4));
        byte[] blob = ModelBlob.pack(source, LIMIT);
        assertThrows(IOException.class,
                () -> ModelBlob.unpack(new ByteArrayInputStream(blob), false, temp.resolve("dst"), 4));
    }

    /** Hand-built blob, as a hostile server could send it. */
    private static byte[] craft(String... pathsAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write("YSMEFMB1".getBytes(StandardCharsets.US_ASCII));
        out.writeByte(0);
        out.writeInt(pathsAndContents.length / 2);
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            byte[] content = pathsAndContents[i + 1].getBytes(StandardCharsets.UTF_8);
            out.writeUTF(pathsAndContents[i]);
            out.writeLong(content.length);
            out.write(content);
        }
        return bytes.toByteArray();
    }

    @Test
    void traversalEntriesNeverWriteOutsideTheTarget() throws IOException {
        String[] evilPaths = {"../escaped.txt", "a/../../escaped.txt", "/abs.txt", "a\\..\\..\\x",
                "C:/x.txt", "a//b", "./ysm.json"};
        for (int i = 0; i < evilPaths.length; i++) {
            Path target = temp.resolve("dst/model" + i);
            // The unsafe entry comes first so the refusal cannot be a side effect of an earlier write.
            byte[] blob = craft(evilPaths[i], "pwned", "ysm.json", "{}");
            assertThrows(IOException.class,
                    () -> ModelBlob.unpack(new ByteArrayInputStream(blob), false, target, LIMIT), evilPaths[i]);
        }
        assertFalse(Files.exists(temp.resolve("dst/escaped.txt")));
        assertFalse(Files.exists(temp.resolve("escaped.txt")));
    }

    @Test
    void folderWithoutManifestDuplicatesOrTrailingBytesIsRefused() throws IOException {
        assertThrows(IOException.class, () -> ModelBlob.unpack(
                new ByteArrayInputStream(craft("models/main.json", "{}")), false, temp.resolve("d1"), LIMIT));
        assertThrows(IOException.class, () -> ModelBlob.unpack(
                new ByteArrayInputStream(craft("ysm.json", "{}", "ysm.json", "{}")), false, temp.resolve("d2"), LIMIT));
        byte[] valid = craft("ysm.json", "{}");
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IOException.class, () -> ModelBlob.unpack(
                new ByteArrayInputStream(trailing), false, temp.resolve("d3"), LIMIT));
        assertThrows(IOException.class, () -> ModelBlob.unpack(
                new ByteArrayInputStream("not a blob at all".getBytes(StandardCharsets.UTF_8)), false,
                temp.resolve("d4"), LIMIT));
    }

    @Test
    void sha256HexShapeCheck() {
        assertTrue(ModelBlob.isSha256Hex("0".repeat(64)));
        assertFalse(ModelBlob.isSha256Hex("0".repeat(63)));
        assertFalse(ModelBlob.isSha256Hex("G".repeat(64)));
        assertFalse(ModelBlob.isSha256Hex(null));
    }
}
