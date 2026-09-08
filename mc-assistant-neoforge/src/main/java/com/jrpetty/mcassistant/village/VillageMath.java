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

    /**
     * The most ground a settlement ever keeps ticking while you are away.
     * Eight chunks is a 17x17 square — about what vanilla keeps awake around
     * world spawn — and it is a CAP, not a target. A town of a hundred spreads
     * further than this, and its outermost plots simply work when somebody is
     * in the area rather than around the clock. That is a deliberate trade: a
     * four-hundred-block city ticking for free is not something a server can
     * be asked to pay for.
     */
    public static final int MAX_LOADED_RADIUS = 8;

    /** How far a folk's ground search scans outward from where it starts. One
     *  number for every trade: the ring, the stores and the plots all have to
     *  be reasoned about together, and they cannot be while each trade reaches
     *  a different distance. */
    public static final int SCAN = 48;

    /** How far the village heart can see on its own: the range its stores are
     *  counted over, and the size of settlement that needs no carriers. Past
     *  this a town relies on its haulers, which is what haulers are for. */
    public static final int CORE = 128;

    /** The furthest a search will ever start from the heart. A town has to be
     *  allowed to spread or its people have nowhere to work; it does not have
     *  to be allowed to spread without limit. */
    public static final int MAX_REACH = 520;

    /**
     * How far a folk's ground search STARTS from the village heart.
     *
     * <p>Grows with the SQUARE ROOT of the headcount, because what a village
     * needs is AREA and area goes as the square of the radius. Growing it
     * linearly would have a town of a hundred reaching to the horizon; growing
     * it not at all — which is what it did until now — packs a hundred plots
     * into a space that fits twenty, and eighty of those people would have
     * stood in the square with no trade for ever, because ground here is
     * claimed whole and never shared.
     *
     * <p>The coefficient carries three times the bare footprint of the plots,
     * because a farmer needs water and a miner needs stone and most ground
     * suits neither.
     */
    public static int searchReach(int folk) {
        double spread = 20.0 * Math.sqrt(Math.max(0, folk - 8));
        return (int) Math.min(MAX_REACH, Math.round(12 + spread));
    }

    /** The furthest a plot can end up from the heart. EVERYTHING ELSE IS
     *  DERIVED FROM THIS rather than guessed alongside it — the ring, the
     *  stores, whether the place needs carriers at all. Three numbers guessed
     *  separately is precisely how a village came to be unable to see its own
     *  harvest. */
    public static int plotReach(int folk) {
        return searchReach(folk) + SCAN;
    }

    /**
     * Chunks kept awake. Derived from the plots so a small settlement is
     * covered entirely, then capped — see {@link #MAX_LOADED_RADIUS}. A
     * village up to about twenty runs completely unattended; a town past that
     * keeps its heart and its inner ring alive and works its far fields when
     * you are near.
     */
    public static int loadedRadiusChunks(int folk) {
        return loadedRadiusChunks(folk, MAX_LOADED_RADIUS);
    }

    /** The same, with the cap the server is willing to pay for. */
    public static int loadedRadiusChunks(int folk, int cap) {
        int chunks = (plotReach(folk) + 15) / 16;              // ceiling
        return Math.max(LOADED_RADIUS, Math.min(Math.max(LOADED_RADIUS, cap), chunks));
    }

    /** The same ring in blocks. */
    public static int loadedRadiusBlocks(int folk) {
        return loadedRadiusChunks(folk) * 16;
    }

    /** Is this settlement entirely inside its own loaded ring? */
    public static boolean fullyAwake(int folk) {
        return loadedRadiusBlocks(folk) >= plotReach(folk);
    }

    /** How far out the village's own stores are counted from. Capped at the
     *  core: sweeping every chest inside a four-hundred-block town ten times a
     *  minute is not a thing anybody should ask a server to do. */
    public static int storesRadius(int folk) {
        return Math.min(CORE, plotReach(folk) + 8);
    }

    /**
     * Has this place outgrown what its heart can see? Past this size the
     * outlying plots bank into their own chests and it takes a CARRIER to get
     * that into the stores the plan reads — so a town this big that has no
     * hauler is a town that will starve in a full barn.
     */
    public static boolean needsCarriers(int folk) {
        return plotReach(folk) > storesRadius(folk);
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

    /** Index of the carrier in {@link #SLOTS} — the trade a town past its own
     *  eyesight cannot do without. */
    public static final int HAUL = 5;

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

    // ------------------------------------------------ what the place needs
    //
    // Every one of these used to be a constant: sixty-four food, sixty-four
    // iron, eight diamonds, whether the settlement was ten people or a
    // hundred. Sixty-four food is a fortnight's larder for ten and about
    // twenty minutes' eating for a hundred, so a town would have declared
    // itself well fed and then starved. A village works out what it needs from
    // how many mouths and hands it actually has.

    /** Meals one pair of hands eats in a Minecraft day, at the default
     *  upkeep of one ration per 3000 ticks against a 24000-tick day. */
    public static final int MEALS_PER_DAY = 8;

    /** Iron in a full set of armour: helmet 5, chest 8, legs 7, boots 4. */
    public static final int ARMOUR_SET = 24;

    /** Iron in one trade's tool, near enough — a pickaxe or axe is three. */
    public static final int TOOL_IRON = 3;

    /**
     * Food the stores should hold: a day and a half of actual meals for
     * actual people, with a floor so a hamlet still keeps a sensible larder.
     * At ten folk that is about the sixty-four it always was; at a hundred it
     * is twelve hundred, because a hundred people eat a hundred people's
     * worth.
     */
    public static int foodWanted(int folk) {
        return Math.max(64, (int) Math.round(folk * MEALS_PER_DAY * 1.5));
    }

    /** Iron to put a full set of armour on every watchman — the first call on
     *  a settlement's metal, because they are the ones walking at things in
     *  the dark. */
    public static int ironForTheWatch(int folk) {
        return Math.max(1, shapeOf(folk)[GUARD]) * ARMOUR_SET;
    }

    /** Iron to put a decent tool in every hand in the place. */
    public static int ironForTools(int folk) {
        return folk * TOOL_IRON;
    }

    /** Iron to armour everybody, not just the watch — what a town works
     *  towards once its own people are the thing worth protecting. */
    public static int ironForEveryone(int folk) {
        return folk * ARMOUR_SET;
    }

    /** What the Iron Age is actually asking for: the watch in armour and
     *  everybody in decent tools. */
    public static int ironWanted(int folk) {
        return Math.max(64, ironForTheWatch(folk) + ironForTools(folk));
    }

    /** Diamonds: a pickaxe for every mining crew, and never fewer than the
     *  eight it takes to say a town has diamonds at all. A diamond pickaxe is
     *  what makes obsidian — and therefore the last age — possible. */
    public static int diamondsWanted(int folk) {
        int miners = Math.max(1, shapeOf(folk)[MINE]);
        return Math.max(8, 3 * Math.max(1, miners / 6));
    }

    /** Beds the house blueprint actually lays. Two, along the side walls. */
    public static final int BEDS_PER_HOUSE = 2;

    /** Roofs. Two beds apiece, so the crowded target beds out most of a town
     *  and the roomy one gets a young village started. */
    public static int housesWanted(int folk, boolean crowded) {
        return crowded ? Math.max(2, folk / 3) : Math.max(1, folk / 4);
    }

    /** Timber: what the houses on the list are actually made of. */
    public static int timberWanted(int folk) {
        return Math.max(128, housesWanted(folk, false) * 96);
    }

    /** Stone: the wall, the smeltery, and the houses that follow them. */
    public static int stoneWanted(int folk) {
        return Math.max(256, folk * 24);
    }

    /** Coal: fuel for the forge, and torches for everybody down a hole. */
    public static int coalWanted(int folk) {
        return Math.max(32, folk * 2);
    }

    /** Obsidian for the way out of the world. Fixed: a portal is a portal
     *  whether ten people built it or five hundred. */
    public static int obsidianWanted(int folk) {
        return 10;
    }

    /** Index of the watch and the mine in {@link #SLOTS}. */
    public static final int GUARD = 4;
    public static final int MINE = 1;

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
