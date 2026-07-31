package com.provenance.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A set of counters plus the named breakdowns that go with them. Used both for
 * an item's overall totals and for a single player's contribution, so the two
 * views are structurally identical and can be compared directly.
 *
 * <p>Not thread-safe on its own. All mutation happens under the owning
 * {@link ItemRecord}'s lock, which is what keeps an overall total and the
 * contribution that fed it from diverging.
 */
public final class StatBucket {

    /**
     * Cap on retained entries per breakdown. A long-lived pickaxe in a large
     * modpack would otherwise accumulate an unbounded map. When the cap is hit
     * the smallest entry is dropped, so the "most-X" answer stays correct and
     * the long tail is what degrades.
     */
    public static final int MAX_BREAKDOWN_ENTRIES = 128;

    private final EnumMap<StatKey, Long> stats = new EnumMap<>(StatKey.class);
    private final EnumMap<BreakdownKind, Map<String, Long>> breakdowns = new EnumMap<>(BreakdownKind.class);

    public long get(StatKey key) {
        Long v = stats.get(key);
        return v == null ? key.identity() : v;
    }

    /** Returns the counter, or {@code 0} when a MAX/MIN counter has no sample yet. */
    public long getOrZero(StatKey key) {
        long v = get(key);
        return key.isUnset(v) ? 0L : v;
    }

    public boolean has(StatKey key) {
        return stats.containsKey(key) && !key.isUnset(stats.get(key));
    }

    /**
     * Folds {@code amount} into {@code key} using the key's aggregation: added
     * for totals, kept if larger for MAX counters, kept if smaller for MIN.
     */
    public void record(StatKey key, long amount) {
        // Written out rather than using Map.merge with a method reference.
        // `key::combine` captures `key`, so it allocates a new object on every
        // call — and this is the single hottest line in the mod, running
        // several times per block broken and per hit landed.
        Long existing = stats.get(key);
        stats.put(key, existing == null ? amount : key.combine(existing, amount));
    }

    public void recordBreakdown(BreakdownKind kind, String id, long amount) {
        Map<String, Long> map = breakdowns.computeIfAbsent(kind, k -> new LinkedHashMap<>());
        map.merge(id, amount, Long::sum);
        if (map.size() > MAX_BREAKDOWN_ENTRIES) {
            trimSmallest(map);
        }
    }

    private static void trimSmallest(Map<String, Long> map) {
        String smallestKey = null;
        long smallest = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (e.getValue() < smallest) {
                smallest = e.getValue();
                smallestKey = e.getKey();
            }
        }
        if (smallestKey != null) {
            map.remove(smallestKey);
        }
    }

    public Map<String, Long> breakdown(BreakdownKind kind) {
        Map<String, Long> map = breakdowns.get(kind);
        return map == null ? Map.of() : Collections.unmodifiableMap(map);
    }

    /** The highest-count entry of a breakdown, e.g. the most-mined block. */
    public String mostFrequent(BreakdownKind kind) {
        Map<String, Long> map = breakdowns.get(kind);
        if (map == null || map.isEmpty()) {
            return null;
        }
        String best = null;
        long bestCount = Long.MIN_VALUE;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    public Map<StatKey, Long> asMap() {
        return Collections.unmodifiableMap(stats);
    }

    public Map<BreakdownKind, Map<String, Long>> breakdownsAsMap() {
        return Collections.unmodifiableMap(breakdowns);
    }

    /** Used when rebuilding overall totals from contributors during a repair pass. */
    public void mergeFrom(StatBucket other) {
        for (Map.Entry<StatKey, Long> e : other.stats.entrySet()) {
            record(e.getKey(), e.getValue());
        }
        for (Map.Entry<BreakdownKind, Map<String, Long>> e : other.breakdowns.entrySet()) {
            for (Map.Entry<String, Long> inner : e.getValue().entrySet()) {
                recordBreakdown(e.getKey(), inner.getKey(), inner.getValue());
            }
        }
    }

    /** Direct set, for deserialisation only. */
    void put(StatKey key, long value) {
        stats.put(key, value);
    }

    /** Direct set, for deserialisation only. */
    void putBreakdown(BreakdownKind kind, String id, long value) {
        breakdowns.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(id, value);
    }

    public boolean isEmpty() {
        return stats.isEmpty() && breakdowns.isEmpty();
    }
}
