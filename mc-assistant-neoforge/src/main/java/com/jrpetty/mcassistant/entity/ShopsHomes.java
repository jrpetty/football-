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
        WorkStock.tick(level, v);
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        if (CLAIMED.getOrDefault(v.id(), -1L) != day) {
            CLAIMED.put(v.id(), day);
            HomeStores.claim(level, v);
        }
        if (t >= MORNING_FROM || t < MORNING_TO) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isSleeping()) HomeStores.toolsOut(level, f);
            }
        }
    }

    /** The mornings the tools come out of the household chests: from half past four to seven by the clock. */
    static final long MORNING_FROM = 22500L, MORNING_TO = 1000L;

    private static final java.util.Map<java.util.UUID, Long> CLAIMED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tests (Villages.resetForTests): everything the shops and homes keep, forgotten. */
    public static void resetAllForTests() {
        CLAIMED.clear();
        ServicePoints.resetForTests();
        Needs.resetForTests();
        ShoppingTrips.resetForTests();
        HomeStores.resetForTests();
        Belongings.resetForTests();
        WorkStock.resetForTests();
    }
}
