package com.provenance.core;

import java.util.Objects;
import java.util.UUID;

/**
 * One player's permanent, cumulative contribution to one physical item.
 *
 * <p>Keyed by UUID, never by name. The name is a display snapshot refreshed on
 * each use, so a rename updates how the contributor appears without splitting
 * them into two rows — and two players who happen to share a display name are
 * never merged.
 *
 * <p>This record is lifetime, not per-ownership-period. If a player sells an
 * item and later buys it back, this same record continues from where it left
 * off; nothing is reset or duplicated.
 */
public final class Contribution {

    private final UUID playerId;
    private String nameSnapshot;
    private final StatBucket stats = new StatBucket();
    private final long firstUsedEpochMilli;
    private long lastUsedEpochMilli;

    public Contribution(UUID playerId, String nameSnapshot, long nowEpochMilli) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.nameSnapshot = nameSnapshot == null ? "" : nameSnapshot;
        this.firstUsedEpochMilli = nowEpochMilli;
        this.lastUsedEpochMilli = nowEpochMilli;
    }

    Contribution(UUID playerId, String nameSnapshot, long firstUsed, long lastUsed) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.nameSnapshot = nameSnapshot == null ? "" : nameSnapshot;
        this.firstUsedEpochMilli = firstUsed;
        this.lastUsedEpochMilli = lastUsed;
    }

    public UUID playerId() {
        return playerId;
    }

    public String nameSnapshot() {
        return nameSnapshot;
    }

    /** Refreshes the display name after a username change. Does not fork the record. */
    public void updateName(String name) {
        if (name != null && !name.isEmpty()) {
            this.nameSnapshot = name;
        }
    }

    public StatBucket stats() {
        return stats;
    }

    public long firstUsedEpochMilli() {
        return firstUsedEpochMilli;
    }

    public long lastUsedEpochMilli() {
        return lastUsedEpochMilli;
    }

    void touch(long nowEpochMilli) {
        if (nowEpochMilli > lastUsedEpochMilli) {
            lastUsedEpochMilli = nowEpochMilli;
        }
    }
}
