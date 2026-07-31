package com.provenance.mod;

import com.provenance.core.DistanceAccumulator;
import com.provenance.core.FileRecordStore;
import com.provenance.core.ProvenanceConfig;
import com.provenance.core.RecordRegistry;
import com.provenance.core.ItemRecord;
import com.provenance.core.RepairSessionTracker;
import com.provenance.core.TransferService;
import com.provenance.core.UsageAccumulator;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-server holder for everything stateful.
 *
 * <p>Persistence runs on its own single thread. Gameplay marks records dirty
 * and returns immediately; this executor does the serialisation, so no disk
 * write ever happens on the server thread and a large farm cannot stall the
 * tick loop on IO.
 */
public final class ProvenanceState {

    private static ProvenanceState active;

    private final FileRecordStore store;
    private final RecordRegistry registry;
    private final ProvenanceConfig config;
    private final TransferService transfers;
    private final DistanceAccumulator distances;
    private final RepairSessionTracker repairs;
    private final UsageAccumulator usage = new UsageAccumulator();
    private final ScheduledExecutorService writer;

    private ProvenanceState(Path dataDirectory) {
        this.config = ConfigIo.loadOrCreate(dataDirectory.resolve("provenance-config.json"));
        this.store = new FileRecordStore(dataDirectory.resolve("records"));
        this.registry = new RecordRegistry(store, config);
        this.transfers = new TransferService();
        this.distances = new DistanceAccumulator(config);
        this.repairs = new RepairSessionTracker(config.repairSessionWindowSeconds());

        this.writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "provenance-writer");
            thread.setDaemon(true);
            return thread;
        });
        this.writer.scheduleWithFixedDelay(this::flushQuietly, 15, 15, TimeUnit.SECONDS);
    }

    private void flushQuietly() {
        try {
            store.flush();
        } catch (RuntimeException e) {
            // Never let a write failure kill the scheduler; the record stays
            // dirty and the next pass retries it.
            Provenance.LOGGER.error("Provenance flush failed; will retry", e);
        }
    }

    public UsageAccumulator usage() {
        return usage;
    }

    /**
     * Writes buffered time and distance into records.
     *
     * <p>The per-second sampler only touches memory; this is where those totals
     * become history. Called on the flush interval, when a player logs out,
     * before an item is inspected, and at shutdown, so what a player sees is
     * always current even though records are written a hundred times less
     * often than they are sampled.
     */
    public void flushUsage() {
        usage.drain((recordId, playerId, ticks, distanceCm) -> {
            ItemRecord record = store.load(recordId);
            if (record == null) {
                // Purged while buffered — a dropped item that despawned, say.
                return;
            }
            String name = nameOf(playerId);
            long now = registry.now();
            com.provenance.core.StatKey timeKey = UsageAccumulator.timeKeyFor(record.category());
            if (ticks > 0 && timeKey != null) {
                record.record(playerId, name, timeKey, ticks, now);
            }
            if (distanceCm > 0) {
                record.record(playerId, name, com.provenance.core.StatKey.DISTANCE_CM, distanceCm, now);
            }
            store.save(record);
        });
    }

    /** Flushes just one item, so an inspection shows totals current to the second. */
    public void flushUsage(java.util.UUID recordId) {
        usage.drainRecord(recordId, (id, playerId, ticks, distanceCm) -> {
            ItemRecord record = store.load(id);
            if (record == null) {
                return;
            }
            String name = nameOf(playerId);
            long now = registry.now();
            com.provenance.core.StatKey timeKey = UsageAccumulator.timeKeyFor(record.category());
            if (ticks > 0 && timeKey != null) {
                record.record(playerId, name, timeKey, ticks, now);
            }
            if (distanceCm > 0) {
                record.record(playerId, name, com.provenance.core.StatKey.DISTANCE_CM, distanceCm, now);
            }
            store.save(record);
        });
    }

    /**
     * Best-effort display name for a buffered contribution. The record keeps
     * whatever name it already had if the player is offline, and refreshes it
     * the next time they act.
     */
    private String nameOf(java.util.UUID playerId) {
        MinecraftServer server = serverRef;
        if (server != null) {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                return player.getGameProfile().getName();
            }
        }
        return null;
    }

    private static MinecraftServer serverRef;

    static void start(MinecraftServer server) {
        Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("provenance");
        serverRef = server;
        active = new ProvenanceState(dir);
        Stamps.clearCategoryCache();
        Provenance.LOGGER.info("Provenance store ready at {}", dir);

        // Retention sweep, off the server thread: records for items that broke
        // long ago are removed so the store does not grow without bound.
        ProvenanceState state = active;
        state.writer.execute(() -> {
            try {
                int removed = state.store.pruneDestroyed(state.config.destroyedRecordRetentionDays());
                if (removed > 0) {
                    Provenance.LOGGER.info("Provenance retention sweep removed {} record(s)", removed);
                }
            } catch (RuntimeException e) {
                Provenance.LOGGER.error("Provenance retention sweep failed", e);
            }
        });
    }

    static void stop() {
        ProvenanceState state = active;
        active = null;
        serverRef = null;
        if (state == null) {
            return;
        }
        // Buffered time and distance become history before anything shuts down.
        state.flushUsage();
        state.writer.shutdown();
        try {
            // Give the writer a moment to finish, then flush synchronously so
            // nothing is lost on shutdown.
            state.writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        state.store.close();
        Provenance.LOGGER.info("Provenance store closed");
    }

    /** Null on the client and before the server has started. */
    public static ProvenanceState get() {
        return active;
    }

    public RecordRegistry registry() {
        return registry;
    }

    public ProvenanceConfig config() {
        return config;
    }

    public TransferService transfers() {
        return transfers;
    }

    public DistanceAccumulator distances() {
        return distances;
    }

    public RepairSessionTracker repairs() {
        return repairs;
    }

    public FileRecordStore store() {
        return store;
    }
}
