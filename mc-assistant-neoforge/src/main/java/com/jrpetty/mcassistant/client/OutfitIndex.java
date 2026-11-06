package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;

/**
 * [roles-a] Which outfit picture each trade wears, by number (FolkModel.TRADES, textures/entity/folk/NAME.png): the
 * trades drawn first in StationTask's order, then the outfits drawn since, each picked by its trade here. Kept apart from
 * FolkModel and free of the client's classes, so a test on the server can check every trade has its own picture.
 */
public final class OutfitIndex {

    private OutfitIndex() {}

    /** The outfits, by number, as the pictures are named (tools/folk_art.py writes them in this order). */
    public static final String[] NAMES = {
        "none", "farmer", "lumberjack", "miner", "rancher",
        "guard", "smelter", "fisher", "storekeeper", "hauler",
        "blacksmith", "tailor", "beekeeper", "brewer", "enchanter", "cook", "shopkeeper", "scout", "hunter",
        "cavedweller",                                         // [caves] not in StationTask's order: see of()
        "fletcher", "golemkeeper",                             // [fletcher] [golems] the same
        "fireworks",                                           // [fireworks] the same
        "cartographer",                                        // [cartographer] likewise
        "emerald",                                             // [emerald] the emerald trader's
        "diver",                                               // [diver] its own too
        "netherrunner",                                        // [nether] nor this
        "engineer",                                            // [redstone] the redstone engineer's
        "healer", "banker", "ferryman",                        // [roles-a] the healer's; the banker's and ferryman's own at last
        "baker", "barkeep", "innkeeper", "waiter",             // [roles-c] the hospitality posts' own, over the cook's trade: ofPost
    };

    /** [caves] The cave dweller's outfit, after the trades drawn in StationTask's order (those before it). */
    public static final int CAVE = 19;
    public static final int FLETCHER = 20, GOLEMS = 21, FIREWORKS = 22, CARTOGRAPHER = 23, EMERALD = 24, DIVER = 25, NETHER = 26,
        ENGINEER = 27;
    /** [roles-a] The healer's (the sage gown, the apron and coif), the banker's (waistcoat and eyeshade) and the ferryman's
     *  (the waterman's jacket, the knitted cap and the rope). */
    public static final int HEALER = 28, BANKER = 29, FERRYMAN = 30;
    /** [roles-c] The hospitality posts' own (the cook's trade split up: entity/Hospitality): the baker's whites and floury
     *  apron, the barkeep's waistcoat and long bar apron, the innkeeper's (and the host's) coat and keys, the waiter's
     *  black waistcoat and white apron. The kitchen's own (head cook, kitchen hand, pot washer) keep the cook's whites. */
    public static final int BAKER = 31, BARKEEP = 32, INNKEEPER = 33, WAITER = 34;

    /** [roles-c] The outfit a post within a trade wears instead of its trade's, by the post's name (Hospitality.Role's);
     *  0 for none (its trade's own). */
    public static int ofPost(String post) {
        return switch (post == null ? "" : post) {
            case "BAKER" -> BAKER;
            case "BARKEEP" -> BARKEEP;
            case "INNKEEPER", "HOST" -> INNKEEPER;
            case "WAITER" -> WAITER;
            default -> 0;
        };
    }

    /** The outfit a folk of this trade (StationTask's number) wears: its own, never another trade's or the blank. */
    public static int of(int job) {
        if (job == StationTask.CAVE.ordinal()) return CAVE;
        if (job == StationTask.FERRY.ordinal()) return FERRYMAN;               // [roles-a] was the fisher's oilskins
        if (job == StationTask.BANK.ordinal()) return BANKER;                  // [roles-a] was the shopkeeper's (its number wraps to "none")
        if (job == StationTask.FLETCHER.ordinal()) return FLETCHER;
        if (job == StationTask.GOLEMS.ordinal()) return GOLEMS;
        if (job == StationTask.FIREWORKS.ordinal()) return FIREWORKS;
        if (job == StationTask.CARTOGRAPHER.ordinal()) return CARTOGRAPHER;
        if (job == StationTask.EMERALD.ordinal()) return EMERALD;
        if (job == StationTask.DIVER.ordinal()) return DIVER;
        if (job == StationTask.NETHER.ordinal()) return NETHER;
        if (job == StationTask.REDSTONE.ordinal()) return ENGINEER;
        if (job == StationTask.HEAL.ordinal()) return HEALER;
        return Math.floorMod(job, CAVE);
    }
}
