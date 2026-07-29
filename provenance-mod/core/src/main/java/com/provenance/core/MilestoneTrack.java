package com.provenance.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The threshold table for one item category: which statistic drives progress,
 * and how much of it each tier costs.
 *
 * <p>Server owners edit these in configuration; nothing here is compiled in as
 * a constant beyond the shipped defaults in {@link ProvenanceConfig}.
 */
public final class MilestoneTrack {

    private final StatKey primaryStat;
    private final EnumMap<MilestoneTier, Long> thresholds;

    public MilestoneTrack(StatKey primaryStat, Map<MilestoneTier, Long> thresholds) {
        this.primaryStat = Objects.requireNonNull(primaryStat, "primaryStat");
        this.thresholds = new EnumMap<>(MilestoneTier.class);
        this.thresholds.putAll(thresholds);
    }

    public StatKey primaryStat() {
        return primaryStat;
    }

    public Long threshold(MilestoneTier tier) {
        return thresholds.get(tier);
    }

    public Map<MilestoneTier, Long> thresholds() {
        return new EnumMap<>(thresholds);
    }

    /**
     * The highest tier whose threshold {@code value} meets or exceeds, or null
     * when nothing has been earned yet.
     *
     * <p>Walks the ladder in order and stops at the first unmet tier, so a
     * table whose thresholds are not monotonically increasing cannot award a
     * high tier while skipping a lower one.
     */
    public MilestoneTier tierFor(long value) {
        MilestoneTier earned = null;
        for (MilestoneTier tier : MilestoneTier.values()) {
            Long required = thresholds.get(tier);
            if (required == null || value < required) {
                break;
            }
            earned = tier;
        }
        return earned;
    }

    /** The next tier above {@code current}, or null once Server Relic is held. */
    public MilestoneTier nextTier(MilestoneTier current) {
        int nextIndex = current == null ? 0 : current.index() + 1;
        if (nextIndex >= MilestoneTier.values().length) {
            return null;
        }
        return MilestoneTier.byIndex(nextIndex);
    }

    /**
     * Progress towards the next tier as a fraction in {@code [0,1]}, measured
     * from the previous tier's threshold rather than from zero so the bar does
     * not appear almost full the instant a tier is earned.
     */
    public double progressToNext(long value, MilestoneTier current) {
        MilestoneTier next = nextTier(current);
        if (next == null) {
            return 1.0d;
        }
        Long target = thresholds.get(next);
        if (target == null || target <= 0) {
            return 1.0d;
        }
        long floor = current == null ? 0L : thresholds.getOrDefault(current, 0L);
        if (target <= floor) {
            return 1.0d;
        }
        double fraction = (double) (value - floor) / (double) (target - floor);
        return Math.max(0.0d, Math.min(1.0d, fraction));
    }
}
