package com.jrpetty.mcassistant.village;

/**
 * The arithmetic a settlement runs on, with no Minecraft in it.
 *
 * <p>These numbers used to live in three different files that had no idea the
 * others existed, and that is exactly how villages came to be blind to their
 * own harvest: plots were staked sixty blocks out while the stores were read
 * from thirty-two, and nothing anywhere was in a position to notice. The
 * numbers are one thing now, in one place, and the test beside this file
 * asserts the relationships BETWEEN them rather than the values themselves —
 * because the values are a matter of taste and the relationships are not.
 *
 * <p>Deliberately free of Minecraft types so it can be run in an ordinary
 * test on an ordinary machine, which is the only reason any of it is checked
 * at all.
 */
public final class VillageMath {

    private VillageMath() {}

    /** The shape a settlement is measured against: ten folk, four of them
     *  farming. Not a cap — a village of twenty wants twice of everything. */
    public static final int VILLAGE_SIZE = 10;

    /** Chunks a young settlement keeps awake around itself. */
    public static final int LOADED_RADIUS = 4;

    /** The most a grown one ever keeps awake. Releasing is always done at THIS
     *  radius, so a ring that grew can never be under-released and left
     *  ticking for the rest of the world's life. */
    public static final int MAX_LOADED_RADIUS = 6;

    /** How far a folk's ground search scans outward from where it starts. One
     *  number for every trade: the ring, the stores and the plots all have to
     *  be reasoned about together, and they cannot be while each trade reaches
     *  a different distance. */
    public static final int SCAN = 48;

    /** How far a folk's ground search STARTS from the village heart. Ten plots
     *  fit around one hillside and twenty do not; ground is claimed whole and
     *  never shared, so past eight folk a search that starts where everybody
     *  else's started finds nothing but its neighbours' fields. */
    public static int searchReach(int folk) {
        return Math.min(32, 12 + Math.max(0, folk - 8) * 3);
    }

    /** The furthest a plot can end up from the heart. EVERYTHING ELSE IS
     *  DERIVED FROM THIS, rather than guessed alongside it — the ring, the
     *  stores. Three numbers guessed separately is precisely how a village
     *  came to be unable to see its own harvest. */
    public static int plotReach(int folk) {
        return searchReach(folk) + SCAN;
    }

    /** Chunks kept awake. Derived from the plots, because a plot outside the
     *  ring is a plot nobody works while you are away — which is the one thing
     *  a settlement on the map is FOR. */
    public static int loadedRadiusChunks(int folk) {
        int chunks = (plotReach(folk) + 15) / 16;              // ceiling
        return Math.max(LOADED_RADIUS, Math.min(MAX_LOADED_RADIUS, chunks));
    }

    /** The same ring in blocks. */
    public static int loadedRadiusBlocks(int folk) {
        return loadedRadiusChunks(folk) * 16;
    }

    /** How far out the village's own stores are counted from. Derived from the
     *  plots with a little margin: a settlement that cannot see the chest at
     *  the far end of its own farm believes it is starving in a good year. */
    public static int storesRadius(int folk) {
        return Math.min(112, plotReach(folk) + 8);
    }

    // ------------------------------------------------------------ trade shares

    /** One trade's share of a full village, and the headcount it opens at. */
    public record Slot(int weight, int from) {}

    /** In the same order as the live table, so an index means the same trade
     *  in both. Farmers, miners, woodcutters, smelter, watch, carrier,
     *  storekeeper, pen, boat. */
    public static final Slot[] SLOTS = {
        new Slot(4, 1),    // FARM
        new Slot(3, 2),    // MINE
        new Slot(2, 3),    // WOOD
        new Slot(1, 6),    // SMELT
        new Slot(1, 11),   // GUARD
        new Slot(1, 12),   // HAUL
        new Slot(1, 13),   // STORE
        new Slot(1, 14),   // RANCH
        new Slot(1, 16),   // FISH
    };

    /**
     * Which trade the next pair of hands should take: whichever is furthest
     * below its share, ties going to the trade the village wants most of.
     * Returns an index into {@link #SLOTS}, or -1 when the settlement is too
     * small to want anything at all.
     */
    public static int pick(int folk, int[] have) {
        int total = Math.max(1, folk);
        int best = -1;
        double bestDeficit = -Double.MAX_VALUE;
        int bestWeight = 0;
        for (int i = 0; i < SLOTS.length; i++) {
            if (total < SLOTS[i].from()) continue;
            double target = SLOTS[i].weight() * total / (double) VILLAGE_SIZE;
            double deficit = target - (i < have.length ? have[i] : 0);
            if (deficit > bestDeficit
                || (deficit == bestDeficit && SLOTS[i].weight() > bestWeight)) {
                bestDeficit = deficit;
                bestWeight = SLOTS[i].weight();
                best = i;
            }
        }
        return best;
    }

    /**
     * Is this trade carrying more hands than the shape calls for? Never true
     * of a trade down to its last hand, whatever the arithmetic says — the
     * last farmer in a village is not spare.
     */
    public static boolean overStaffed(int folk, int slot, int have) {
        if (slot < 0 || slot >= SLOTS.length) return have > 0;
        double target = SLOTS[slot].weight() * Math.max(1, folk) / (double) VILLAGE_SIZE;
        return have > Math.max(1, (int) Math.ceil(target));
    }

    /**
     * The shape a village of this size settles into, as a count per slot —
     * what you would get by handing out every pair of hands in turn.
     */
    public static int[] shapeOf(int folk) {
        int[] have = new int[SLOTS.length];
        for (int i = 0; i < folk; i++) {
            int pick = pick(i + 1, have);
            if (pick >= 0) have[pick]++;
        }
        return have;
    }
}
