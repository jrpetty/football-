package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * What a thing costs in this town today: the one price every buyer and seller in the town goes by (the shop,
 * the café, the builders' bills, the town's deals with its neighbours, the wages reckoned by what a trade
 * brings in). Prices.each is what a thing is usually worth anywhere; this is what it fetches here, now.
 *
 * <p>[economy] The shared seam of the town's economy. Its reckoning (supply against demand, day by day)
 * belongs to the prices-and-buying work; everybody else only asks it. Until then it is the board's
 * scarcity price where the board knows the thing (Market.each, by what the stores hold), and its usual
 * worth everywhere else.
 */
public final class PriceIndex {

    private PriceIndex() {}

    /** One of this thing, in coin, in this town today. */
    public static double each(ServerLevel level, UUID village, ItemStack what) {
        if (what.isEmpty()) return 0.0;
        Market.Good g = Market.goodFor(what);
        if (g != null) return Market.each(g, Market.stock(level, village, g.what()));
        return Prices.of(what.copyWithCount(1));
    }

    /** One of this item, in coin, in this town today. */
    public static double each(ServerLevel level, UUID village, Item item) {
        return each(level, village, new ItemStack(item));
    }

    /** What this thing is usually worth, anywhere: what a buyer expects to pay before it sees the price. */
    public static double usual(ItemStack what) {
        if (what.isEmpty()) return 0.0;
        Market.Good g = Market.goodFor(what);
        return g != null ? g.value() : Prices.of(what.copyWithCount(1));
    }
}
