package com.ysmef.compat.network;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.network.message.C2SModelDownloadPacket;
import com.ysmef.compat.network.message.C2SModelQueryPacket;
import com.ysmef.compat.ysm.ModelBlob;
import com.ysmef.compat.ysm.ServerModelOverlay;
import com.ysmef.compat.ysm.YsmModelPackage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Receiving side of the model transfer (see {@link ModelTransferServer} for why it exists).
 *
 * <p>For every model a synced player wears, this client asks the server for its version once per
 * connection and then uses, in order of preference: its own copy in {@code config/yes_steve_model}
 * when it is byte-identical to the server's; a copy downloaded from this server earlier, when that
 * one still is; otherwise it downloads the server's package. A downloaded copy stands in for the
 * local one only while connected (see {@link ServerModelOverlay}) - the server's version is what
 * YSM itself shows in that world, so the converted mesh should match it.
 *
 * <p>Threads: state changes happen on the client main thread (packet handlers enqueue there, and
 * background results come back through {@link #drainMainThread}); file work - hashing, spooling
 * slices, unpacking - runs on one background thread, which also keeps the slices in order. Every
 * background result is tagged with the connection it belongs to and dropped when that has ended.
 *
 * <p>No client-only classes here: the packet classes referencing it are loaded on dedicated servers.
 */
public final class ModelTransferClient {

    /** Told when a model's source may have changed: re-resolve it; drop its mesh when replaced. */
    public interface Listener {
        void availabilityChanged(String modelId, boolean sourceReplaced);
    }

    private enum Phase { QUERIED, CHECKING, DOWNLOADING, LOCAL, SERVER_COPY, UNAVAILABLE }

    private record State(Phase phase, String detail, long sinceMillis) {
        static State of(Phase phase, String detail) {
            return new State(phase, detail, System.currentTimeMillis());
        }
    }

    private static final Path CACHE_ROOT = Paths.get("config", "ysm_epicfight_compat", "server_models");
    private static final int MAX_TRACKED_MODELS = 1024;
    /** An unanswered query stops suppressing the "model missing" warning after this long. */
    private static final long ANSWER_TIMEOUT_MILLIS = 60_000L;
    /** Headers and paths on top of the content size; a blob larger than content + this is refused. */
    private static final long BLOB_OVERHEAD_BYTES = 4L * 1024 * 1024;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ysm-ef-model-download");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<String, State> MODELS = new ConcurrentHashMap<>();
    private static final Queue<Runnable> MAIN = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SESSION = new AtomicInteger();
    private static volatile Listener listener = (modelId, replaced) -> {};
    /** Whether the connected server answers model queries; false until its handshake says so. */
    private static volatile boolean serverSupportsTransfer;
    /** Overlay roots whose leftovers from an interrupted session were removed; IO thread only. */
    private static final java.util.Set<Path> CLEANED_ROOTS = new java.util.HashSet<>();

    /** A download in progress; IO thread only. */
    private static final class Incoming {
        final String modelId;
        final String sha256;
        final long maxBlobBytes;
        final Path overlayRoot;
        final Path spool;
        final OutputStream out;
        long total = -1L;
        long received;

        Incoming(String modelId, String sha256, long maxBlobBytes, Path overlayRoot, Path spool, OutputStream out) {
            this.modelId = modelId;
            this.sha256 = sha256;
            this.maxBlobBytes = maxBlobBytes;
            this.overlayRoot = overlayRoot;
            this.spool = spool;
            this.out = out;
        }
    }

    private static final Map<String, Incoming> INCOMING = new HashMap<>();

    /** Hashes of packages on this machine, valid while their metadata signature holds; IO thread only. */
    private record HashKey(Path path, long signature) {}

    private static final Map<HashKey, String> HASHES = new HashMap<>();
    private static final int MAX_CACHED_HASHES = 512;

    private ModelTransferClient() {}

    public static void setListener(Listener newListener) {
        listener = newListener == null ? (modelId, replaced) -> {} : newListener;
    }

    /**
     * Main thread: the server's model-sync handshake arrived; {@code supported} says whether it runs
     * a build that answers model queries. Repeats on every respawn and dimension change.
     */
    public static void onHandshake(boolean supported) {
        serverSupportsTransfer = supported;
    }

    /**
     * Main thread: someone here wears {@code modelId}, or this client needs it and does not have it;
     * ask the server about it, once per connection.
     */
    public static void requestIfNeeded(String modelId) {
        if (!serverSupportsTransfer || modelId == null || modelId.isEmpty()
                || modelId.length() > NetworkHandler.MAX_MODEL_ID_LENGTH
                || !downloadsEnabled() || MODELS.size() >= MAX_TRACKED_MODELS) {
            return;
        }
        if (MODELS.putIfAbsent(modelId, State.of(Phase.QUERIED, "")) == null) {
            NetworkHandler.sendToServer(new C2SModelQueryPacket(modelId));
        }
    }

    /** Main thread: the server's answer for one model. */
    public static void onInfo(String modelId, ModelTransferStatus status, String serverId, long size, String sha256) {
        State current = MODELS.get(modelId);
        if (current == null || (current.phase() != Phase.QUERIED && current.phase() != Phase.DOWNLOADING)) {
            return;
        }
        if (current.phase() == Phase.DOWNLOADING) {
            // A fresh answer mid-download means the server dropped that transfer (its file changed).
            IO.execute(() -> abort(modelId));
        }
        int session = SESSION.get();
        switch (status) {
            case SAME_FILES -> useLocal(modelId);
            case AVAILABLE -> {
                long maxBytes = maxDownloadBytes();
                if (!ServerModelOverlay.isValidServerId(serverId) || !ModelBlob.isSha256Hex(sha256) || size < 0) {
                    settleUnavailable(modelId, "the server sent a malformed answer");
                } else if (size > maxBytes) {
                    settleUnavailable(modelId, "it is " + (size >> 20) + " MiB, over this client's limit "
                            + "(maxDownloadedModelSizeMiB)");
                } else {
                    MODELS.put(modelId, State.of(Phase.CHECKING, ""));
                    Path overlayRoot = CACHE_ROOT.resolve(serverId).toAbsolutePath().normalize();
                    IO.execute(() -> check(session, modelId, sha256, size, overlayRoot, maxBytes));
                }
            }
            default -> {
                if (ServerModelOverlay.deactivate(modelId)) {
                    listener.availabilityChanged(modelId, true);
                }
                settleUnavailable(modelId, status.description());
            }
        }
    }

    /** Network thread: one slice of a requested package. */
    public static void onChunk(String modelId, long offset, long total, byte[] data) {
        int session = SESSION.get();
        IO.execute(() -> append(session, modelId, offset, total, data));
    }

    /** Main thread (client tick): apply results the background thread produced. */
    public static void drainMainThread() {
        Runnable task;
        while ((task = MAIN.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: model transfer update failed", t);
            }
        }
    }

    /** Main thread: the connection ended. Server copies stop standing in for local models. */
    public static void clear() {
        SESSION.incrementAndGet();
        serverSupportsTransfer = false;
        MODELS.clear();
        MAIN.clear();
        IO.execute(() -> {
            for (String modelId : java.util.List.copyOf(INCOMING.keySet())) {
                abort(modelId);
            }
            CLEANED_ROOTS.clear();
        });
        for (String modelId : ServerModelOverlay.clear()) {
            listener.availabilityChanged(modelId, true);
        }
    }

    /** Whether an answer or download for this model is still on its way (suppresses "missing" warnings). */
    public static boolean isResolving(String modelId) {
        State state = MODELS.get(modelId);
        if (state == null) {
            return false;
        }
        return switch (state.phase()) {
            case DOWNLOADING -> true;
            case QUERIED, CHECKING -> System.currentTimeMillis() - state.sinceMillis() < ANSWER_TIMEOUT_MILLIS;
            default -> false;
        };
    }

    /** Why the server's copy of this model will not arrive, or null when that is not known. */
    public static String unavailableReason(String modelId) {
        State state = MODELS.get(modelId);
        return state != null && state.phase() == Phase.UNAVAILABLE ? state.detail() : null;
    }

    // ------------------------------------------------------------------
    // Main-thread outcomes
    // ------------------------------------------------------------------

    private static void useLocal(String modelId) {
        MODELS.put(modelId, State.of(Phase.LOCAL, ""));
        listener.availabilityChanged(modelId, ServerModelOverlay.deactivate(modelId));
    }

    private static void useServerCopy(String modelId, Path overlayRoot) {
        MODELS.put(modelId, State.of(Phase.SERVER_COPY, ""));
        ServerModelOverlay.activate(modelId, overlayRoot);
        listener.availabilityChanged(modelId, true);
    }

    private static void settleUnavailable(String modelId, String reason) {
        MODELS.put(modelId, State.of(Phase.UNAVAILABLE, reason));
        listener.availabilityChanged(modelId, false);
    }

    private static void post(int session, Runnable task) {
        MAIN.add(() -> {
            if (session == SESSION.get()) {
                task.run();
            }
        });
    }

    // ------------------------------------------------------------------
    // IO thread
    // ------------------------------------------------------------------

    private static void check(int session, String modelId, String sha256, long size, Path overlayRoot, long maxBytes) {
        if (session != SESSION.get()) {
            return;
        }
        try {
            YsmModelPackage.Located local = YsmModelPackage.locateLocal(modelId);
            if (local != null && sha256.equals(hashOf(local, maxBytes))) {
                post(session, () -> useLocal(modelId));
                return;
            }
            YsmModelPackage.Located cached = YsmModelPackage.locateUnder(overlayRoot, modelId);
            if (cached != null && sha256.equals(hashOf(cached, maxBytes))) {
                post(session, () -> useServerCopy(modelId, overlayRoot));
                return;
            }
            abort(modelId);
            Files.createDirectories(overlayRoot);
            if (CLEANED_ROOTS.add(overlayRoot)) {
                // Before the first download into this root this session: nothing of this session
                // is in flight there yet, so any spool or staging entry is from an interrupted one.
                removeLeftovers(overlayRoot);
            }
            Path spool = Files.createTempFile(overlayRoot, ".download-", ".part");
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(spool));
            INCOMING.put(modelId, new Incoming(modelId, sha256, maxBytes + BLOB_OVERHEAD_BYTES, overlayRoot, spool, out));
            post(session, () -> {
                MODELS.put(modelId, State.of(Phase.DOWNLOADING, ""));
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: {} model '{}' from the server ({} KiB)",
                        local == null ? "downloading missing" : "downloading the server's version of",
                        modelId, (size + 1023) / 1024);
                NetworkHandler.sendToServer(new C2SModelDownloadPacket(modelId, sha256));
            });
        } catch (Exception e) {
            post(session, () -> settleUnavailable(modelId, "could not prepare the download: " + e));
        }
    }

    private static void append(int session, String modelId, long offset, long total, byte[] data) {
        if (session != SESSION.get()) {
            return;
        }
        Incoming incoming = INCOMING.get(modelId);
        if (incoming == null) {
            return;
        }
        if (incoming.total < 0) {
            if (total <= 0 || total > incoming.maxBlobBytes) {
                fail(session, incoming, "the server announced " + total + " bytes");
                return;
            }
            incoming.total = total;
        }
        if (total != incoming.total || offset != incoming.received || data.length == 0
                || data.length > incoming.total - incoming.received) {
            fail(session, incoming, "slices arrived out of order");
            return;
        }
        try {
            incoming.out.write(data);
            incoming.received += data.length;
            if (incoming.received == incoming.total) {
                complete(session, incoming);
            }
        } catch (IOException e) {
            fail(session, incoming, e.toString());
        }
    }

    private static void complete(int session, Incoming incoming) throws IOException {
        incoming.out.close();
        INCOMING.remove(incoming.modelId);
        Path staging = null;
        try {
            String actual = ModelBlob.sha256(incoming.spool);
            if (!actual.equals(incoming.sha256)) {
                throw new IOException("the received data does not match the announced hash");
            }
            Path relative = YsmModelPackage.safeRelativePath(incoming.modelId);
            Path target = relative == null ? null : incoming.overlayRoot.resolve(relative).normalize();
            if (target == null || !target.startsWith(incoming.overlayRoot) || target.equals(incoming.overlayRoot)) {
                throw new IOException("unsafe model id");
            }
            staging = Files.createTempDirectory(incoming.overlayRoot, ".staging-");
            Path staged = staging.resolve("package");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(incoming.spool))) {
                ModelBlob.unpack(in, incoming.modelId.endsWith(".ysm"), staged, incoming.maxBlobBytes);
            }
            deleteRecursively(target);
            Files.createDirectories(target.getParent());
            Files.move(staged, target);
            long kib = (incoming.total + 1023) / 1024;
            post(session, () -> {
                YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: received model '{}' from the server ({} KiB)",
                        incoming.modelId, kib);
                useServerCopy(incoming.modelId, incoming.overlayRoot);
            });
        } catch (IOException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: could not store model '{}' from the server: {}",
                    incoming.modelId, reason);
            post(session, () -> settleUnavailable(incoming.modelId, "the download failed: " + reason));
        } finally {
            deleteQuietly(incoming.spool);
            if (staging != null) {
                try {
                    deleteRecursively(staging);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void fail(int session, Incoming incoming, String reason) {
        abort(incoming.modelId);
        YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: download of model '{}' failed: {}", incoming.modelId, reason);
        post(session, () -> settleUnavailable(incoming.modelId, "the download failed: " + reason));
    }

    private static void abort(String modelId) {
        Incoming incoming = INCOMING.remove(modelId);
        if (incoming == null) {
            return;
        }
        try {
            incoming.out.close();
        } catch (IOException ignored) {
        }
        deleteQuietly(incoming.spool);
    }

    private static String hashOf(YsmModelPackage.Located located, long maxBytes) {
        try {
            HashKey key = new HashKey(located.path().toAbsolutePath().normalize(), ModelBlob.signature(located));
            String cached = HASHES.get(key);
            if (cached != null) {
                return cached;
            }
            String hash = ModelBlob.sha256(located, maxBytes);
            if (HASHES.size() >= MAX_CACHED_HASHES) {
                HASHES.clear();
            }
            HASHES.put(key, hash);
            return hash;
        } catch (IOException e) {
            // Unreadable or over the limit: it cannot be the server's version.
            return null;
        }
    }

    private static void removeLeftovers(Path overlayRoot) {
        try (Stream<Path> entries = Files.list(overlayRoot)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".download-") || name.startsWith(".staging-")) {
                    deleteRecursively(entry);
                }
            }
        } catch (IOException ignored) {
            // Litter, not a correctness problem: every download uses fresh unique names.
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path child : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(child);
                }
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static boolean downloadsEnabled() {
        try {
            return YSMCompatConfig.DOWNLOAD_SERVER_MODELS.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    private static long maxDownloadBytes() {
        try {
            return YSMCompatConfig.MAX_DOWNLOADED_MODEL_MIB.get() * 1024L * 1024L;
        } catch (IllegalStateException notLoaded) {
            return 128L * 1024L * 1024L;
        }
    }
}
