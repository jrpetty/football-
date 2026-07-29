package com.provenance.mod;

import com.provenance.core.DistanceAccumulator;
import com.provenance.core.FileRecordStore;
import com.provenance.core.ProvenanceConfig;
import com.provenance.core.RecordRegistry;
import com.provenance.core.RepairSessionTracker;
import com.provenance.core.TransferService;
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

    static void start(MinecraftServer server) {
        Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("provenance");
        active = new ProvenanceState(dir);
        Provenance.LOGGER.info("Provenance store ready at {}", dir);
    }

    static void stop() {
        ProvenanceState state = active;
        active = null;
        if (state == null) {
            return;
        }
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
