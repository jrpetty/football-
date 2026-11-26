package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/** [featlife] The game tests' way into the week's package-private steps. */
public final class WeekendLifeTestHooks {

    private WeekendLifeTestHooks() {}

    /** A buyer at the stalls now (Stalls.buy). */
    @Nullable
    public static String buyAtStalls(ServerLevel level, Villages.Village v, VillageFolkEntity buyer, long day) {
        return Stalls.buy(level, v, buyer, day);
    }

    /** A beat of a host's dinner (Friends.hold). */
    public static boolean dinner(ServerLevel level, VillageFolkEntity host) {
        return Friends.hold(level, host);
    }
}
