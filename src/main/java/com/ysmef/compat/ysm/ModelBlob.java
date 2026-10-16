package com.ysmef.compat.ysm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One model package (a {@code .ysm} file, or a folder holding {@code ysm.json}) as a single byte
 * stream - the unit the server sends and the unit both sides hash.
 *
 * <p>The hash is what decides whether a client's copy is the server's: SHA-256 over this exact
 * serialization, which depends only on file contents and their paths relative to the package, sorted.
 * Modification times, the root the package sits under and the order a file system lists entries in
 * do not enter it, so two machines with byte-identical packages agree, and nothing else does.
 *
 * <p>Layout: magic {@code YSMEFMB1}, one byte (1 = .ysm file, 0 = folder), entry count, then per entry
 * its relative path ({@link DataOutputStream#writeUTF}, {@code /}-separated; empty for the single entry
 * of a .ysm file), its length and its bytes.
 *
 * <p>{@link #unpack} reads data sent by a server this client does not control, so it validates every
 * field before it writes anything: entry count and total size are bounded, and every path must stay
 * strictly inside the target.
 */
public final class ModelBlob {

    private static final byte[] MAGIC = "YSMEFMB1".getBytes(StandardCharsets.US_ASCII);
    /** Far above any real model's file count (real folder packages hold a few dozen). */
    public static final int MAX_ENTRIES = 4096;

    private ModelBlob() {}

    private record Entry(String path, Path file, long length) {}

    /** Total file bytes of the package, without reading the contents. */
    public static long contentSize(YsmModelPackage.Located located) throws IOException {
        long total = 0L;
        for (Entry entry : entries(located)) {
            total += entry.length();
        }
        return total;
    }

    /**
     * Metadata-only signature (relative paths, sizes, modification times): whether a hash computed
     * earlier still describes the package, without reading its contents. Not a content identity -
     * only ever compared against itself on the same machine.
     */
    public static long signature(YsmModelPackage.Located located) throws IOException {
        long hash = 0xcbf29ce484222325L;
        for (Entry entry : entries(located)) {
            String key = entry.path() + ':' + entry.length() + ':'
                    + Files.getLastModifiedTime(entry.file()).toMillis();
            for (int i = 0; i < key.length(); i++) {
                hash ^= key.charAt(i);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    /** The serialized package; throws when the file content exceeds {@code maxBytes}. */
    public static byte[] pack(YsmModelPackage.Located located, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                (int) Math.min(Math.max(contentSize(located), 0L) + 1024L, Integer.MAX_VALUE - 8L));
        write(located, maxBytes, out);
        return out.toByteArray();
    }

    /** SHA-256 (lowercase hex) of the serialization, computed while streaming - nothing is buffered. */
    public static String sha256(YsmModelPackage.Located located, long maxBytes) throws IOException {
        MessageDigest digest = newDigest();
        try (DigestOutputStream out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            write(located, maxBytes, out);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** SHA-256 (lowercase hex) of serialized bytes, as received. */
    public static String sha256(byte[] blob) {
        return HexFormat.of().formatHex(newDigest().digest(blob));
    }

    /** SHA-256 (lowercase hex) of a file's bytes - a received blob spooled to disk. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Whether {@code value} has the shape of a hash this class produces. */
    public static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    /** Serialize the package into {@code sink}. */
    public static void write(YsmModelPackage.Located located, long maxBytes, OutputStream sink) throws IOException {
        List<Entry> entries = entries(located);
        long total = 0L;
        for (Entry entry : entries) {
            total += entry.length();
        }
        if (total > maxBytes) {
            throw new IOException("model package is " + total + " bytes, over the " + maxBytes + " byte limit");
        }
        DataOutputStream out = new DataOutputStream(sink);
        out.write(MAGIC);
        out.writeByte(located.binary() ? 1 : 0);
        out.writeInt(entries.size());
        byte[] buffer = new byte[65536];
        for (Entry entry : entries) {
            out.writeUTF(entry.path());
            out.writeLong(entry.length());
            long copied = 0L;
            try (InputStream in = Files.newInputStream(entry.file())) {
                int read;
                while (copied < entry.length()
                        && (read = in.read(buffer, 0, (int) Math.min(buffer.length, entry.length() - copied))) != -1) {
                    out.write(buffer, 0, read);
                    copied += read;
                }
            }
            if (copied != entry.length()) {
                // The file changed while it was being read; a blob that disagrees with its own header
                // would fail the receiver's checks anyway, and the hash would describe no real package.
                throw new IOException("'" + entry.path() + "' changed size while reading");
            }
        }
        out.flush();
    }

    private static List<Entry> entries(YsmModelPackage.Located located) throws IOException {
        if (located.binary()) {
            return List.of(new Entry("", located.path(), Files.size(located.path())));
        }
        Path base = located.path();
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(base)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file) || !YsmModelPackage.residesInside(file, base)) {
                    continue;
                }
                entries.add(new Entry(base.relativize(file).toString().replace('\\', '/'), file, Files.size(file)));
                if (entries.size() > MAX_ENTRIES) {
                    throw new IOException("model package has more than " + MAX_ENTRIES + " files");
                }
            }
        }
        entries.sort(java.util.Comparator.comparing(Entry::path));
        return entries;
    }

    /**
     * Write a received serialization out as a package at {@code target} (a file for a .ysm package,
     * a directory otherwise), which must not exist yet. On failure, whatever was written is left for
     * the caller to delete - it always writes into a staging location it owns.
     *
     * @param expectBinary what the model id says the package is; a blob claiming otherwise is refused
     */
    public static void unpack(InputStream source, boolean expectBinary, Path target, long maxBytes) throws IOException {
        DataInputStream in = new DataInputStream(source);
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IOException("not a model blob");
        }
        boolean binary = in.readByte() == 1;
        if (binary != expectBinary) {
            throw new IOException(binary ? "blob holds a .ysm file but the id names a folder"
                    : "blob holds a folder but the id names a .ysm file");
        }
        int count = in.readInt();
        if (count < 1 || count > MAX_ENTRIES || (binary && count != 1)) {
            throw new IOException("implausible entry count " + count);
        }
        Path root = target.toAbsolutePath().normalize();
        if (!binary) {
            Files.createDirectories(root);
        }
        Set<String> seen = new HashSet<>();
        boolean hasManifest = false;
        long total = 0L;
        byte[] buffer = new byte[65536];
        for (int i = 0; i < count; i++) {
            String path = in.readUTF();
            long length = in.readLong();
            if (length < 0 || length > maxBytes - total) {
                throw new IOException("entry '" + path + "' exceeds the size limit");
            }
            total += length;
            Path file;
            if (binary) {
                if (!path.isEmpty()) {
                    throw new IOException("a .ysm blob entry must be unnamed");
                }
                file = root;
                Files.createDirectories(root.getParent());
            } else {
                file = entryTarget(root, path);
                if (!seen.add(path)) {
                    throw new IOException("duplicate entry '" + path + "'");
                }
                hasManifest |= path.equals("ysm.json");
                Files.createDirectories(file.getParent());
            }
            try (OutputStream out = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                long remaining = length;
                while (remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        throw new IOException("blob ends inside entry '" + path + "'");
                    }
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        }
        if (in.read() != -1) {
            throw new IOException("trailing data after the last entry");
        }
        if (!binary && !hasManifest) {
            throw new IOException("folder package has no ysm.json");
        }
    }

    /** Resolve a received entry path strictly inside {@code root}, or throw. */
    static Path entryTarget(Path root, String path) throws IOException {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0 || path.indexOf(':') >= 0
                || path.indexOf('\\') >= 0 || path.startsWith("/")) {
            throw new IOException("unsafe entry path '" + path + "'");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("unsafe entry path '" + path + "'");
            }
        }
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IOException("unsafe entry path '" + path + "'");
        }
        return resolved;
    }
}
