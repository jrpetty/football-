package com.provenance.core;

import java.util.Objects;

/**
 * A milestone the item has actually earned: which tier, exactly when, and the
 * overall statistic that triggered it.
 *
 * <p>Immutable by construction. A tier is awarded once and the date it was
 * reached is part of the item's history, so re-crossing a threshold after a
 * configuration change must not overwrite the original date.
 */
public final class MilestoneAward {

    private final MilestoneTier tier;
    private final long achievedEpochMilli;
    private final StatKey triggeringStat;
    private final long triggeringValue;

    public MilestoneAward(MilestoneTier tier, long achievedEpochMilli, StatKey triggeringStat, long triggeringValue) {
        this.tier = Objects.requireNonNull(tier, "tier");
        this.achievedEpochMilli = achievedEpochMilli;
        this.triggeringStat = Objects.requireNonNull(triggeringStat, "triggeringStat");
        this.triggeringValue = triggeringValue;
    }

    public MilestoneTier tier() {
        return tier;
    }

    public long achievedEpochMilli() {
        return achievedEpochMilli;
    }

    public StatKey triggeringStat() {
        return triggeringStat;
    }

    public long triggeringValue() {
        return triggeringValue;
    }
}
