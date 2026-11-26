package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * [wpc] The one hook for market days beyond the town's weekly one: {@link #open} declares a market on a day (WEEKLY, the
 * usual; EXTRA, a one-off; WEEKEND, the weekend markets a later feature runs), and Market.marketDay counts it. Folk come to
 * the square to buy, stalls open, and a market-day pitch (PlayerStalls.Licence.MARKET_DAY) may be taken for it.
 */
public final class MarketDays {

    private MarketDays() {}

    public enum Kind { WEEKLY, EXTRA, WEEKEND }

    static CompoundTag book(UUID village) {
        return PropertyBook.town(village, "marketdays");
    }

    /** A market declared on this day (WEEKLY needs no declaring: it is the town's own day, and is accepted as a no-op). */
    public static void open(UUID village, long day, Kind kind) {
        if (kind == Kind.WEEKLY) return;
        book(village).putString(Long.toString(day), kind.name());
        CompoundTag b = book(village);
        for (String k : new java.util.ArrayList<>(b.getAllKeys())) {
            try { if (Long.parseLong(k) < day - 14) b.remove(k); } catch (NumberFormatException e) { b.remove(k); }
        }
        PropertyBook.dirty();
    }

    /** Is a market declared here on this day? */
    public static boolean declared(UUID village, long day) {
        try {
            return book(village).contains(Long.toString(day));
        } catch (RuntimeException e) {
            return false;                                                    // the books not loaded (a plain unit test)
        }
    }

    /** What kind of market, if any: WEEKLY for the town's own day. */
    public static String kind(UUID village, long day) {
        if (declared(village, day)) return book(village).getString(Long.toString(day));
        return Market.marketDay(village, day) ? Kind.WEEKLY.name() : "";
    }
}
