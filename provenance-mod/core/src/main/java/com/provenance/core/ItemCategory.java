package com.provenance.core;

/**
 * Broad behavioural class of a tracked item. The category decides which
 * statistics are shown, which statistic drives the milestone track, and which
 * threshold table applies.
 *
 * <p>Category is resolved once, at registration, from configuration (item tags
 * and explicit id overrides) so that modded equipment can join without code
 * changes. It is stored on the record rather than re-derived, so that an item
 * which is later re-tagged keeps a stable history.
 *
 * <p>Shields are intentionally not a category. Tracking them means counting
 * blocked attacks and prevented damage, which needs a damage-pipeline hook that
 * does not exist here yet; carrying a shield category without it would give
 * every shield a milestone track it could never advance along. Adding shields
 * back means adding that handler at the same time.
 */
public enum ItemCategory {
    MELEE_WEAPON("melee_weapon", StatKey.KILLS),
    RANGED_WEAPON("ranged_weapon", StatKey.KILLS),
    /**
     * Firearms from a gun mod. Kept separate from bows because a gun records
     * things a bow cannot — shots fired, and headshots — and because a modpack
     * may want firearms on their own milestone ladder.
     */
    GUN("gun", StatKey.KILLS),
    TRIDENT("trident", StatKey.KILLS),
    PICKAXE("pickaxe", StatKey.BLOCKS_MINED),
    AXE("axe", StatKey.LOGS_CHOPPED),
    SHOVEL("shovel", StatKey.BLOCKS_DUG),
    HOE("hoe", StatKey.BLOCKS_WORKED),
    SHEARS("shears", StatKey.UTILITY_USES),
    FISHING_ROD("fishing_rod", StatKey.CATCHES_TOTAL),
    ARMOUR("armour", StatKey.DAMAGE_ABSORBED),
    /** Eligible, tracked, but with no meaningful milestone driver of its own. */
    OTHER("other", StatKey.UTILITY_USES);

    private final String id;
    private final StatKey defaultPrimaryStat;

    ItemCategory(String id, StatKey defaultPrimaryStat) {
        this.id = id;
        this.defaultPrimaryStat = defaultPrimaryStat;
    }

    public String id() {
        return id;
    }

    /**
     * The statistic the milestone track reads by default. Server owners may
     * override this per category in configuration.
     */
    public StatKey defaultPrimaryStat() {
        return defaultPrimaryStat;
    }

    public static ItemCategory byId(String id) {
        for (ItemCategory c : values()) {
            if (c.id.equalsIgnoreCase(id)) {
                return c;
            }
        }
        return OTHER;
    }
}
