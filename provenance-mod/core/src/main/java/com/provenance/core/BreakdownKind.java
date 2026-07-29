package com.provenance.core;

/**
 * The named tallies that answer "most-mined block", "most-killed mob" and the
 * per-type breakdowns the Statistics tab shows.
 *
 * <p>Each kind maps to a bounded map of registry-id to count. Bounded matters:
 * a pickaxe that has mined every block in a large modpack must not grow an
 * unbounded record, so {@link StatBucket} caps the retained entries and keeps
 * the largest.
 */
public enum BreakdownKind {
    BLOCK_MINED("block_mined"),
    MOB_KILLED("mob_killed"),
    WOOD_CHOPPED("wood_chopped"),
    BLOCK_DUG("block_dug"),
    CROP_WORKED("crop_worked"),
    CATCH_TYPE("catch_type");

    private final String id;

    BreakdownKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static BreakdownKind byId(String id) {
        for (BreakdownKind k : values()) {
            if (k.id.equalsIgnoreCase(id)) {
                return k;
            }
        }
        throw new IllegalArgumentException("Unknown breakdown kind: " + id);
    }
}
