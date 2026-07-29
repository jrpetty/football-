package com.provenance.core;

/**
 * The seven-step milestone ladder, early game through super-late game.
 *
 * <p>Tiers belong to the physical item, not to a player, and confer nothing but
 * cosmetics. There is intentionally no field here for attack damage,
 * protection, efficiency, mining speed or luck — a tier carries an emblem and a
 * title, and nothing a combat or mining calculation could ever read.
 */
public enum MilestoneTier {
    INITIATED(0, "Initiated", "wooden"),
    PROVEN(1, "Proven", "iron"),
    SEASONED(2, "Seasoned", "copper"),
    VETERAN(3, "Veteran", "gold"),
    MASTER(4, "Master", "diamond"),
    LEGENDARY(5, "Legendary", "enchanted_animated"),
    SERVER_RELIC(6, "Server Relic", "relic_animated");

    private final int index;
    private final String displayName;
    private final String emblem;

    MilestoneTier(int index, String displayName, String emblem) {
        this.index = index;
        this.displayName = displayName;
        this.emblem = emblem;
    }

    public int index() {
        return index;
    }

    /**
     * The authenticated title shown beside the item. Kept separate from the
     * player's custom item name: a pickaxe renamed "Steelbreaker" still
     * displays "Veteran" as its earned tier, and no amount of lore editing can
     * produce that title.
     */
    public String displayName() {
        return displayName;
    }

    /** Cosmetic emblem id resolved by the client into a texture. */
    public String emblem() {
        return emblem;
    }

    /** True for tiers whose emblem animates, so accessibility settings can flatten them. */
    public boolean isAnimated() {
        return this == LEGENDARY || this == SERVER_RELIC;
    }

    public static MilestoneTier byIndex(int index) {
        for (MilestoneTier t : values()) {
            if (t.index == index) {
                return t;
            }
        }
        throw new IllegalArgumentException("No milestone tier at index " + index);
    }

    public static MilestoneTier byName(String name) {
        for (MilestoneTier t : values()) {
            if (t.name().equalsIgnoreCase(name) || t.displayName.equalsIgnoreCase(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown milestone tier: " + name);
    }
}
