package com.jrpetty.mcassistant.pa;

/**
 * [pa33] One thing the player asked the Player AI Assistant for, read out of a sentence: what (the kind), of what
 * (the item, a plain word or phrase: "logs", "stone pickaxe", "house"), how many (0 when not said) and where.
 * Pure data; {@link PaUnderstand} makes them, PaOrders carries them out.
 */
public record PaIntent(Kind kind, String item, int amount, Place place, String raw) {

    /** Everything the assistant can be asked. */
    public enum Kind {
        // moving and standing
        FOLLOW, STAY, COME, GUARD_ME, GUARD_HERE, GO_HOME, SET_HOME, EXPLORE,
        // [pa33c-2b] its bed, which is where it comes back after a fall
        BED_USE, BED_PLACE, BED_WHERE,
        // [pa33c-2b] pings and waypoints: go to a place, save one, find the nearest of something, mark it, edit the queue
        GOTO, WAYPOINT, FIND, MARK, PING_EDIT, BRIDGE,
        // [pa33d] the rest of the game: enchanting, the Nether, taming, villagers, better gear, redstone, maps, pillars,
        // trick shots at a ping, and challenges against its player
        ENCHANT, NETHER, TAME, VTRADE, UPGRADE, PLACE_THING, MAP, PILLAR, SHOOT_AT, CHALLENGE, BREW,
        // work
        GATHER, MINE_ORE, MINE_DOWN, FARM, BUILD, CRAFT, SMELT, DEPOSIT, FETCH, GIVE, BRING_FOOD, FIGHT, HUNT, FISH,
        BREED, SHEAR, LIGHT_AREA, SORT, CLEAR_AREA, FUEL, TRADE_BUY, TRADE_SELL, REPAIR,
        // control
        STOP, UNDO, PAUSE, RESUME, AUTO_ON, AUTO_OFF, SLEEP, WAKE, RENAME,
        // asking it things
        STATUS, DOING, INVENTORY, NEEDS, HELP, WHERE, QUESTION,
        // small talk and answers
        GREETING, THANKS, BYE, YES, NO,
        // not understood (the reader asks back with choices)
        UNKNOWN,
        // [pa36] a chest, gathering or logistics order (its PaLogiOrder rides in the item as a spec)
        LOGI,
        // [pa38] snowballs or eggs at something, the rod's hook, the mace's smash, a zombie villager cured, bookshelves round the table
        THROW, HOOK, SMASH, CURE, SHELVES;

        /** Work that takes a while or changes the world: the assistant checks before it starts (unless told not to). */
        public boolean confirmable() {
            return this == MINE_ORE || this == MINE_DOWN || this == BUILD || this == CLEAR_AREA || this == TRADE_BUY
                || this == TRADE_SELL;
        }

        /** Orders that are carried out in turn (a queue), rather than at once. */
        public boolean queued() {
            return switch (this) {
                case GATHER, MINE_ORE, MINE_DOWN, FARM, BUILD, CRAFT, SMELT, DEPOSIT, FETCH, GIVE, BRING_FOOD, HUNT, FISH,
                     BREED, SHEAR, LIGHT_AREA, SORT, CLEAR_AREA, FUEL, TRADE_BUY, TRADE_SELL, REPAIR, GO_HOME, EXPLORE, BED_PLACE, GOTO, BRIDGE,
                     ENCHANT, NETHER, TAME, VTRADE, UPGRADE, PLACE_THING, MAP, PILLAR, SHOOT_AT, BREW,
                     LOGI, SHELVES -> true;                                                            // [pa36] [pa38]
                default -> false;
            };
        }
    }

    /** Where: nowhere said, here (where the player stands or looks), at the player, at home, at what is looked at. */
    public enum Place { NONE, HERE, ME, HOME, LOOKED }

    public static PaIntent of(Kind k, String raw) {
        return new PaIntent(k, "", 0, Place.NONE, raw);
    }

    public PaIntent withPlace(Place p) {
        return new PaIntent(kind, item, amount, p, raw);
    }

    /** A short test-friendly form: KIND, KIND:item, KIND:item:amount, with @PLACE when said. */
    public String brief() {
        StringBuilder b = new StringBuilder(kind.name());
        if (!item.isEmpty()) b.append(':').append(item);
        if (amount > 0) b.append(item.isEmpty() ? "::" : ":").append(amount);
        if (place != Place.NONE) b.append('@').append(place.name());
        return b.toString();
    }
}
