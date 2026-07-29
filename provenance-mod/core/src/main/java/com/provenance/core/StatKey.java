package com.provenance.core;

/**
 * Every quantity this system records.
 *
 * <p>Each key carries an {@link Aggregation} describing how the item-wide
 * ("overall") value relates to the per-player values. This matters: a total is
 * the sum of every contributor's total, but a "longest kill" is the maximum
 * across contributors and a "deepest block" is the minimum. Getting this wrong
 * is how overall totals silently drift away from contributor totals.
 *
 * <p>Deliberately absent, per the specification: any Hunter, Arena, Survival
 * Arena, server-event or historical-event counter. There is no key for them,
 * so no part of the system can record one.
 *
 * <p>Equally deliberate: every key here is written by a real event handler. A
 * counter with no handler behind it renders as a permanent zero and reads as a
 * broken feature rather than an unused one, so shield blocking, projectiles
 * fired, farmland tilled, entities sheared, honeycomb harvested and paths
 * created were removed rather than displayed empty. Re-add a key only together
 * with the handler that writes it.
 */
public enum StatKey {
    // --- Combat ---
    KILLS("kills", Aggregation.SUM),
    DAMAGE_DEALT("damage_dealt", Aggregation.SUM),
    HITS_LANDED("hits_landed", Aggregation.SUM),
    PROJECTILE_HITS("projectile_hits", Aggregation.SUM),
    /** Centimetres, so the record stays integral. */
    LONGEST_KILL_CM("longest_kill_cm", Aggregation.MAX),

    // --- Mining and digging ---
    BLOCKS_MINED("blocks_mined", Aggregation.SUM),
    ORES_MINED("ores_mined", Aggregation.SUM),
    /** Y level. Lower is deeper, hence MIN. */
    DEEPEST_BLOCK_Y("deepest_block_y", Aggregation.MIN),
    LOGS_CHOPPED("logs_chopped", Aggregation.SUM),
    BLOCKS_CHOPPED("blocks_chopped", Aggregation.SUM),
    BLOCKS_DUG("blocks_dug", Aggregation.SUM),

    // --- Farming ---
    CROPS_HARVESTED("crops_harvested", Aggregation.SUM),
    BLOCKS_WORKED("blocks_worked", Aggregation.SUM),

    // --- Utility ---
    BLOCKS_CUT("blocks_cut", Aggregation.SUM),
    UTILITY_USES("utility_uses", Aggregation.SUM),

    // --- Fishing ---
    CATCHES_TOTAL("catches_total", Aggregation.SUM),
    FISH_CAUGHT("fish_caught", Aggregation.SUM),
    TREASURE_CAUGHT("treasure_caught", Aggregation.SUM),
    JUNK_CAUGHT("junk_caught", Aggregation.SUM),

    // --- Armour ---
    DAMAGE_ABSORBED("damage_absorbed", Aggregation.SUM),
    HITS_RECEIVED("hits_received", Aggregation.SUM),
    TIME_WORN_TICKS("time_worn_ticks", Aggregation.SUM),

    // --- Common to everything ---
    TIME_USED_TICKS("time_used_ticks", Aggregation.SUM),
    /** Centimetres travelled while carried or equipped. */
    DISTANCE_CM("distance_cm", Aggregation.SUM),
    REPAIRS("repairs", Aggregation.SUM),
    DURABILITY_RESTORED("durability_restored", Aggregation.SUM);

    /** How an item-wide value is derived from its contributors' values. */
    public enum Aggregation {
        SUM,
        MAX,
        MIN
    }

    private final String id;
    private final Aggregation aggregation;

    StatKey(String id, Aggregation aggregation) {
        this.id = id;
        this.aggregation = aggregation;
    }

    public String id() {
        return id;
    }

    public Aggregation aggregation() {
        return aggregation;
    }

    /** The value an untouched counter holds, chosen so the first real sample always wins. */
    public long identity() {
        return switch (aggregation) {
            case SUM -> 0L;
            case MAX -> Long.MIN_VALUE;
            case MIN -> Long.MAX_VALUE;
        };
    }

    /** True when the counter has never received a sample. */
    public boolean isUnset(long value) {
        return aggregation != Aggregation.SUM && value == identity();
    }

    public long combine(long a, long b) {
        return switch (aggregation) {
            case SUM -> a + b;
            case MAX -> Math.max(a, b);
            case MIN -> Math.min(a, b);
        };
    }

    public static StatKey byId(String id) {
        for (StatKey k : values()) {
            if (k.id.equalsIgnoreCase(id)) {
                return k;
            }
        }
        throw new IllegalArgumentException("Unknown stat key: " + id);
    }
}
