package com.jrpetty.mcassistant.entity;

import java.util.UUID;

/**
 * [featlife] Which market a town holds on a day: the weekly one (Saturday, by the Calendar), a mercantile town's extra
 * one midweek (or one by decree), and the weekend's (the square's stalls, Saturday and Sunday). Kept minimal: the
 * property package keeps the fuller one, and calls the Calendar for its days.
 */
public final class MarketDays {

    private MarketDays() {}

    public enum Kind { WEEKLY, EXTRA, WEEKEND }

    /** Is this kind of market open in this town on this day? */
    public static boolean open(UUID village, long day, Kind kind) {
        return switch (kind) {
            case WEEKLY -> Calendar.isMarketDay(day);
            case EXTRA -> village != null && (Ethos.extraMarket(village, day) || AdminTools.marketDecreed(village, day));
            case WEEKEND -> Calendar.isWeekend(day);
        };
    }
}
