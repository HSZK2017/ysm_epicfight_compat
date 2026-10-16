package com.ysmef.compat.network;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatServerConfig;
import com.ysmef.compat.network.message.S2CModelChunkPacket;
import com.ysmef.compat.network.message.S2CModelInfoPacket;
import com.ysmef.compat.ysm.ModelBlob;
import com.ysmef.compat.ysm.ServerModelOverlay;
import com.ysmef.compat.ysm.YsmModelPackage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosting side of the model transfer: answers which version of a model this server has, and sends
 * the package to a client that lacks it or holds a different one.
 *
 * <p>Why this exists: the client converts model <i>files</i> into Epic Fight meshes. YSM's own sync
 * gives a client the server's models, but stored under {@code cache/client/} encrypted with a key
 * only YSM's runtime has, so a model the client does not also have in its own
 * {@code config/yes_steve_model} could not be converted - everyone wearing it rendered as the Epic
 * Fight default model in battle mode. The workaround was copying the server's folders by hand.
 *
 * <p>Flow per model: query -> {@link S2CModelInfoPacket} with the package's SHA-256 -> the client
 * compares with what it has and asks for the package only when nothing matches -> the package is
 * streamed as {@link S2CModelChunkPacket} slices under a per-player rate budget.
 *
 * <p>Cost bounds, since every connected player can trigger work: hashing and packing run on one
 * background thread (never the server thread); a package's hash is cached under a metadata signature
 * so a join re-reads nothing that did not change; at most {@code maxConcurrentTransfers} packages are
 * held in memory at once, each at most {@code maxModelSizeMiB}; queries and queued downloads per
 * connection are capped. Auth-folder packages are refused unless the operator opts in.
 *
 * <p>All per-player state is touched on the server thread only; the background thread hands its
 * results back through {@link MinecraftServer#execute}.
 */
@Mod.EventBusSubscriber(modid = YSMEpicFightCompat.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModelTransferServer {

    private static final int CHUNK_BYTES = 32 * 1024;
    private static final int MAX_QUERIES_PER_CONNECTION = 512;
    private static final int MAX_QUEUED_DOWNLOADS = 64;
    private static final Path SERVER_ID_FILE = Paths.get("config", "ysm_epicfight_compat", "server_id.txt");

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ysm-ef-model-transfer");
        thread.setDaemon(true);
        return thread;
    });

    /** What the server last computed for a package, valid while its metadata signature holds. */
    private record Offer(long signature, long size, String sha256) {}

    private record Answer(ModelTransferStatus status, long size, String sha256) {
        static Answer of(ModelTransferStatus status) {
            return new Answer(status, 0L, "");
        }
    }

    private static final Map<String, Offer> OFFERS = new ConcurrentHashMap<>();

    private static final class Download {
        final String modelId;
        final String sha256;
        volatile byte[] blob;
        volatile boolean failed;
        /** The package no longer has the offered hash; the client gets a fresh answer. */
        volatile boolean changed;
        int offset;
        boolean holdsSlot;

        Download(String modelId, String sha256) {
            this.modelId = modelId;
            this.sha256 = sha256;
        }
    }

    private static final class PlayerState {
        final Set<String> queried = new HashSet<>();
        /** model id -> the hash offered to this player, the only one it may ask to download. */
        final Map<String, String> offered = new HashMap<>();
        final ArrayDeque<Download> queue = new ArrayDeque<>();
        Download active;
        boolean capReported;
    }

    private static final Map<UUID, PlayerState> PLAYERS = new ConcurrentHashMap<>();
    /** Packages currently held for sending; server thread only. */
    private static int slotsInUse;
    private static volatile String serverId;

    private ModelTransferServer() {}

    /** Server thread: a client asks for this server's version of {@code modelId}. */
    public static void onQuery(ServerPlayer player, String modelId) {
        PlayerState state = PLAYERS.computeIfAbsent(player.getUUID(), uuid -> new PlayerState());
        if (!state.queried.contains(modelId) && state.queried.size() >= MAX_QUERIES_PER_CONNECTION) {
            if (!state.capReported) {
                state.capReported = true;
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: player '{}' asked about more than {} models in one session; further "
                                + "model queries from this connection are ignored",
                        player.getGameProfile().getName(), MAX_QUERIES_PER_CONNECTION);
            }
            return;
        }
        state.queried.add(modelId);
        if (!YSMCompatServerConfig.MODEL_TRANSFER_ENABLED.get()) {
            reply(player, S2CModelInfoPacket.unavailable(modelId, ModelTransferStatus.DISABLED));
            return;
        }
        if (YsmModelPackage.safeRelativePath(modelId) == null) {
            reply(player, S2CModelInfoPacket.unavailable(modelId, ModelTransferStatus.NOT_FOUND));
            return;
        }
        if (player.server.isSingleplayerOwner(player.getGameProfile())) {
            // The integrated server's own player reads the very files the server would send.
            reply(player, S2CModelInfoPacket.unavailable(modelId, ModelTransferStatus.SAME_FILES));
            return;
        }
        answerAsync(player.server, player.getUUID(), modelId);
    }

    /** Server thread: a client asks for the package it was offered under {@code sha256}. */
    public static void onDownload(ServerPlayer player, String modelId, String sha256) {
        PlayerState state = PLAYERS.get(player.getUUID());
        if (state == null || !YSMCompatServerConfig.MODEL_TRANSFER_ENABLED.get()) {
            return;
        }
        String offered = state.offered.get(modelId);
        if (offered == null || !offered.equals(sha256)) {
            // Only what was offered may be requested: anything else is a stale or forged request.
            YSMEpicFightCompat.LOGGER.debug(
                    "YSM-EF Compat: ignoring a download of '{}' from '{}' that does not match the offer",
                    modelId, player.getGameProfile().getName());
            return;
        }
        if (state.active != null && state.active.modelId.equals(modelId)) {
            return;
        }
        for (Download queued : state.queue) {
            if (queued.modelId.equals(modelId)) {
                return;
            }
        }
        if (state.queue.size() >= MAX_QUEUED_DOWNLOADS) {
            return;
        }
        state.queue.add(new Download(modelId, sha256));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PLAYERS.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        int budget = YSMCompatServerConfig.MODEL_TRANSFER_KIB_PER_TICK.get() * 1024;
        int maxConcurrent = YSMCompatServerConfig.MODEL_TRANSFER_MAX_CONCURRENT.get();
        long maxBytes = maxModelBytes();
        boolean shareAuth = YSMCompatServerConfig.MODEL_TRANSFER_SHARE_AUTH.get();
        Iterator<Map.Entry<UUID, PlayerState>> it = PLAYERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PlayerState> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            PlayerState state = entry.getValue();
            if (player == null) {
                release(state);
                it.remove();
                continue;
            }
            Download download = state.active;
            if (download == null) {
                if (state.queue.isEmpty() || slotsInUse >= maxConcurrent) {
                    continue;
                }
                download = state.queue.poll();
                state.active = download;
                download.holdsSlot = true;
                slotsInUse++;
                load(download, maxBytes, shareAuth);
                continue;
            }
            if (download.failed) {
                finish(state, download);
                if (download.changed) {
                    OFFERS.remove(download.modelId);
                    answerAsync(server, entry.getKey(), download.modelId);
                } else {
                    reply(player, S2CModelInfoPacket.unavailable(download.modelId, ModelTransferStatus.FAILED));
                }
                continue;
            }
            byte[] blob = download.blob;
            if (blob == null) {
                continue;
            }
            int sent = 0;
            while (sent < budget && download.offset < blob.length) {
                int length = Math.min(Math.min(CHUNK_BYTES, budget - sent), blob.length - download.offset);
                byte[] slice = java.util.Arrays.copyOfRange(blob, download.offset, download.offset + length);
                reply(player, new S2CModelChunkPacket(download.modelId, download.offset, blob.length, slice));
                download.offset += length;
                sent += length;
            }
            if (download.offset >= blob.length) {
                finish(state, download);
                YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: sent model '{}' ({} KiB) to '{}'",
                        download.modelId, (blob.length + 1023) / 1024, player.getGameProfile().getName());
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerState state = PLAYERS.remove(event.getEntity().getUUID());
        if (state != null) {
            release(state);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PLAYERS.clear();
        OFFERS.clear();
        slotsInUse = 0;
    }

    private static void finish(PlayerState state, Download download) {
        if (download.holdsSlot) {
            download.holdsSlot = false;
            slotsInUse = Math.max(0, slotsInUse - 1);
        }
        download.blob = null;
        if (state.active == download) {
            state.active = null;
        }
    }

    private static void release(PlayerState state) {
        if (state.active != null) {
            finish(state, state.active);
        }
        state.queue.clear();
    }

    private static void answerAsync(MinecraftServer server, UUID playerId, String modelId) {
        long maxBytes = maxModelBytes();
        boolean shareAuth = YSMCompatServerConfig.MODEL_TRANSFER_SHARE_AUTH.get();
        IO.execute(() -> {
            Answer answer = describe(modelId, maxBytes, shareAuth);
            String id = answer.status() == ModelTransferStatus.AVAILABLE ? serverId() : "";
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                PlayerState state = PLAYERS.get(playerId);
                if (player == null || state == null) {
                    return;
                }
                if (answer.status() == ModelTransferStatus.AVAILABLE) {
                    state.offered.put(modelId, answer.sha256());
                } else {
                    state.offered.remove(modelId);
                }
                reply(player, new S2CModelInfoPacket(modelId, answer.status(), id, answer.size(), answer.sha256()));
            });
        });
    }

    /** Background thread: this server's answer for one model id. */
    private static Answer describe(String modelId, long maxBytes, boolean shareAuth) {
        YsmModelPackage.Located located = YsmModelPackage.locateLocal(modelId);
        if (located == null) {
            return Answer.of(ModelTransferStatus.NOT_FOUND);
        }
        if (located.isAuth() && !shareAuth) {
            return Answer.of(ModelTransferStatus.AUTH_RESTRICTED);
        }
        try {
            long size = ModelBlob.contentSize(located);
            if (size > maxBytes) {
                return Answer.of(ModelTransferStatus.TOO_LARGE);
            }
            long signature = ModelBlob.signature(located);
            Offer cached = OFFERS.get(modelId);
            if (cached == null || cached.signature() != signature) {
                cached = new Offer(signature, size, ModelBlob.sha256(located, maxBytes));
                OFFERS.put(modelId, cached);
            }
            return new Answer(ModelTransferStatus.AVAILABLE, cached.size(), cached.sha256());
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: could not read model '{}' for transfer: {}",
                    modelId, e.toString());
            return Answer.of(ModelTransferStatus.FAILED);
        }
    }

    /** Background thread: read the package into memory for sending. */
    private static void load(Download download, long maxBytes, boolean shareAuth) {
        IO.execute(() -> {
            try {
                YsmModelPackage.Located located = YsmModelPackage.locateLocal(download.modelId);
                if (located == null || (located.isAuth() && !shareAuth)) {
                    download.changed = true;
                    download.failed = true;
                    return;
                }
                byte[] blob = ModelBlob.pack(located, maxBytes);
                if (!ModelBlob.sha256(blob).equals(download.sha256)) {
                    download.changed = true;
                    download.failed = true;
                    return;
                }
                download.blob = blob;
            } catch (Exception e) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: could not pack model '{}' for transfer: {}",
                        download.modelId, e.toString());
                download.failed = true;
            }
        });
    }

    private static void reply(ServerPlayer player, Object message) {
        NetworkHandler.sendToClientPlayer(message, player);
    }

    private static long maxModelBytes() {
        return YSMCompatServerConfig.MODEL_TRANSFER_MAX_MODEL_MIB.get() * 1024L * 1024L;
    }

    /**
     * This server's stable id: clients keep each server's copies in their own folder, so two servers
     * that ship different versions of the same model id do not overwrite each other's on every join.
     */
    private static String serverId() {
        String id = serverId;
        if (id != null) {
            return id;
        }
        synchronized (ModelTransferServer.class) {
            if (serverId != null) {
                return serverId;
            }
            try {
                if (Files.isRegularFile(SERVER_ID_FILE)) {
                    String stored = Files.readString(SERVER_ID_FILE, StandardCharsets.UTF_8).trim();
                    if (ServerModelOverlay.isValidServerId(stored)) {
                        serverId = stored;
                        return stored;
                    }
                }
                String fresh = UUID.randomUUID().toString();
                Files.createDirectories(SERVER_ID_FILE.getParent());
                Files.writeString(SERVER_ID_FILE, fresh, StandardCharsets.UTF_8);
                serverId = fresh;
            } catch (Exception e) {
                // Unpersisted: still unique for this run, clients just re-download after a restart.
                serverId = UUID.randomUUID().toString();
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: could not persist the model transfer server id: {}",
                        e.toString());
            }
            return serverId;
        }
    }
}
