package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

/**
 * [shops-homes] The town's shops and homes, seen to on the town's own quarter-minute round (Grow.tick): the service
 * counters fitted (once a day), the folk's shopping lists (once a day), the homes' storage kept the household's
 * (once a day) and the workshops' chests stocked with their makings (each a minute). Each part keeps its own clock and
 * returns at once when it is not due, so a round costs a few map lookups.
 */
public final class ShopsHomes {

    private ShopsHomes() {}

    /** One round for this town. */
    public static void tick(ServerLevel level, Villages.Village v) {
        ServicePoints.fitOut(level, v);
    }
}
