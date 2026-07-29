package com.provenance.core;

/**
 * How an item entered the provenance system. This is an immutable historical
 * fact; nothing that happens to the item afterwards may rewrite it.
 */
public enum Origin {
    /** Made by a known player, on a known date. */
    CRAFTED("crafted"),
    /**
     * Encountered as loot, a trade, or a spawned item with no traceable maker.
     * Creator is Unknown and no manufacturer is invented.
     */
    FOUND("found"),
    /**
     * Predates provenance tracking. Creation date is recorded as the migration
     * date and flagged, never backdated to fake an age.
     */
    LEGACY("legacy"),
    /**
     * Registered from a stack that failed binding-token validation, i.e. the
     * losing side of a suspected duplication. Starts a fresh, empty history so
     * a duplicate can never inherit the original's accomplishments.
     */
    DUPLICATE("duplicate");

    private final String id;

    Origin(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Origin byId(String id) {
        for (Origin o : values()) {
            if (o.id.equalsIgnoreCase(id)) {
                return o;
            }
        }
        return FOUND;
    }
}
