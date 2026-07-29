package com.provenance.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Buffers time-carried and distance-carried in memory, then writes them to
 * records in batches.
 *
 * <p>Time is counted continuously: an item accrues every second it is held or
 * worn, with no activity gate. That is the behaviour players expect — an hour
 * spent mining one spot, fishing, or standing guard in full armour is an hour
 * the item was in service — and the earlier movement-gated version recorded
 * nothing for exactly those cases.
 *
 * <p>Counting every second does not mean <em>writing</em> every second. Sampling
 * six equipment slots per player per second and pushing each straight into a
 * record would keep every record permanently dirty, so the persistence layer
 * could never evict anything and would rewrite unchanged files forever. Instead
 * the deltas land in this map — two long additions per slot, no allocation, no
 * locks on records — and are drained into records on an interval, on logout,
 * before an inspection, and at shutdown.
 *
 * <p>The player-visible result is identical, because every path that displays a
 * record drains first. The server-visible result is roughly a hundredfold fewer
 * record mutations.
 */
public final class UsageAccumulator {

    /** Buffered totals for one player's use of one item. */
    private record Key(UUID recordId, UUID playerId) {
    }

    private static final class Pending {
        long ticks;
        long distanceCm;
    }

    /** Receives drained totals. Implemented by the game layer. */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param ticks      time held or worn since the last drain
         * @param distanceCm distance carried since the last drain
         */
        void accept(UUID recordId, UUID playerId, long ticks, long distanceCm);
    }

    private final Map<Key, Pending> pending = new HashMap<>();

    /** Adds time held or worn. Called once per sampling tick per carried item. */
    public synchronized void addTime(UUID recordId, UUID playerId, long ticks) {
        if (ticks <= 0) {
            return;
        }
        entry(recordId, playerId).ticks += ticks;
    }

    /** Adds distance carried. Only ever called for genuine movement. */
    public synchronized void addDistance(UUID recordId, UUID playerId, long distanceCm) {
        if (distanceCm <= 0) {
            return;
        }
        entry(recordId, playerId).distanceCm += distanceCm;
    }

    private Pending entry(UUID recordId, UUID playerId) {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(playerId, "playerId");
        return pending.computeIfAbsent(new Key(recordId, playerId), k -> new Pending());
    }

    /**
     * Hands every buffered total to the sink and clears the buffer.
     *
     * <p>The buffer is copied and released before the sink runs, so record
     * updates never happen while holding this object's lock.
     */
    public void drain(Sink sink) {
        List<Map.Entry<Key, Pending>> snapshot;
        synchronized (this) {
            if (pending.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(pending.entrySet());
            pending.clear();
        }
        for (Map.Entry<Key, Pending> e : snapshot) {
            sink.accept(e.getKey().recordId(), e.getKey().playerId(),
                    e.getValue().ticks, e.getValue().distanceCm);
        }
    }

    /**
     * Drains only what belongs to one item, so inspecting an item shows totals
     * that include the second you are looking at it.
     */
    public void drainRecord(UUID recordId, Sink sink) {
        List<Map.Entry<Key, Pending>> mine = new ArrayList<>();
        synchronized (this) {
            pending.entrySet().removeIf(e -> {
                if (e.getKey().recordId().equals(recordId)) {
                    mine.add(Map.entry(e.getKey(), e.getValue()));
                    return true;
                }
                return false;
            });
        }
        for (Map.Entry<Key, Pending> e : mine) {
            sink.accept(e.getKey().recordId(), e.getKey().playerId(),
                    e.getValue().ticks, e.getValue().distanceCm);
        }
    }

    /** Discards buffered totals for an item that no longer exists. */
    public synchronized void forgetRecord(UUID recordId) {
        pending.keySet().removeIf(k -> k.recordId().equals(recordId));
    }

    /** Discards buffered totals for a player, e.g. once they have logged out and drained. */
    public synchronized void forgetPlayer(UUID playerId) {
        pending.keySet().removeIf(k -> k.playerId().equals(playerId));
    }

    public synchronized int pendingEntries() {
        return pending.size();
    }

    /** The counter this category records time against. */
    public static StatKey timeKeyFor(ItemCategory category) {
        return category == ItemCategory.ARMOUR ? StatKey.TIME_WORN_TICKS : StatKey.TIME_USED_TICKS;
    }
}
