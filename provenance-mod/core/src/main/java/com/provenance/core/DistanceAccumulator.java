package com.provenance.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Accumulates player movement and hands it to carried items in batches.
 *
 * <p>Per the performance requirements, nothing here runs per item per tick. The
 * server samples a player's position on a configurable interval, this class
 * turns consecutive samples into a distance, and only then is that one figure
 * attributed to the items the player is actually carrying.
 *
 * <p>Two things are deliberately refused:
 *
 * <ul>
 *   <li><b>Teleports.</b> A dimension change or waypoint jump would otherwise
 *       add hundreds of kilometres in a single sample. Discontinuities are
 *       dropped unless the server explicitly opts in.</li>
 *   <li><b>Jitter.</b> Samples below a floor are ignored, so a player standing
 *       still does not slowly accrue distance from sub-block drift.</li>
 * </ul>
 *
 * <p>Items sitting in a chest never appear in a sample, so static storage
 * accrues nothing.
 */
public final class DistanceAccumulator {

    /** A player's last known sampled position, in centimetres. */
    private record Sample(long x, long y, long z, String dimension) {
    }

    private final Map<UUID, Sample> lastSample = new HashMap<>();
    private final ProvenanceConfig config;

    /**
     * Distance beyond which a single sample is treated as a teleport rather
     * than travel. At the default 5-second interval, no legitimate movement
     * covers 500 metres.
     */
    private static final long TELEPORT_THRESHOLD_CM = 50_000L;

    public DistanceAccumulator(ProvenanceConfig config) {
        this.config = config;
    }

    /**
     * Folds a new position sample in and returns the distance to attribute, in
     * centimetres. Returns zero for the first sample, a teleport, a dimension
     * change, or movement below the jitter floor.
     */
    public long sample(UUID playerId, long xCm, long yCm, long zCm, String dimension) {
        Sample previous = lastSample.put(playerId, new Sample(xCm, yCm, zCm, dimension));
        if (previous == null) {
            return 0L;
        }

        // Coordinates are not comparable across dimensions — the Nether's
        // eight-to-one scale alone would make the figure meaningless — so a
        // dimension change only re-baselines.
        if (!previous.dimension().equals(dimension)) {
            return 0L;
        }

        double dx = (double) xCm - previous.x();
        double dy = (double) yCm - previous.y();
        double dz = (double) zCm - previous.z();
        long distance = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));

        if (distance < config.minimumDistanceSampleCm()) {
            return 0L;
        }
        if (distance > TELEPORT_THRESHOLD_CM && !config.teleportDistanceCounts()) {
            return 0L;
        }
        return distance;
    }

    /** Forgets a player, e.g. on logout, so their next session starts clean. */
    public void forget(UUID playerId) {
        lastSample.remove(playerId);
    }

    /**
     * Resets a player's baseline without producing a distance. Call on a known
     * teleport so the jump itself is never measured.
     */
    public void resetBaseline(UUID playerId, long xCm, long yCm, long zCm, String dimension) {
        lastSample.put(playerId, new Sample(xCm, yCm, zCm, dimension));
    }

    public int trackedPlayerCount() {
        return lastSample.size();
    }
}
