package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import net.minecraft.server.level.ServerLevel;

/**
 * [wpc] The property package's day, called from the town's morning (Market) and its every-few-seconds tick (PlayerShops):
 * land rates, house rates, rents, premises, the debts ladder and discharges, notices to quit, and the long-absence rule;
 * and after a restart, the lots and houses held for players held again.
 */
public final class PropertyDay {

    private PropertyDay() {}

    /** Each morning, after the property market's own. */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        Guard.run("wpc land", () -> LandPlots.morning(level, v, day));
        Guard.run("wpc home rates", () -> HomePage.morning(level, v, day));
        Guard.run("wpc evictions", () -> HomePage.evictions(level, v, day));
        Guard.run("wpc tenancy", () -> Tenancy.morning(level, v, day));
        Guard.run("wpc premises", () -> Premises.morning(level, v, day));
        Guard.run("wpc debts", () -> Debts.morning(level, v, day));
        Guard.run("wpc insolvency", () -> Insolvency.morning(level, v, day));
        Guard.run("wpc absence", () -> StandingOrders.morning(level, v, day));
    }

    /** Every few seconds for a town. */
    public static void tick(ServerLevel level, Villages.Village v) {
        LandPlots.tick(level, v);
        Tenancy.load(v.id());
    }
}
