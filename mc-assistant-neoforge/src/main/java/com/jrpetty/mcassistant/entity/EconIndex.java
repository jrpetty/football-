package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * [econ4] The town's economic statistics, taken each morning and kept sixty days: what the dashboard draws and the cycle
 * and the council read.
 *
 * <ul>
 * <li><b>The price index</b>: a household's basket (bread, roots, grain, fruit, meat and fish, and the wood, stone, coal,
 *     iron and wool a home burns and mends with), each at today's price in the town (PriceIndex, by supply and demand),
 *     weighed by what a household spends on it. Chain-linked day to day: a hundred on the first morning, and each morning
 *     yesterday's figure times what the same basket costs today against yesterday. A town's rising to a village's or a
 *     town's price level (its hands paid more and its goods dearer alike) is not counted as inflation. <b>Inflation</b> is
 *     the index against a week ago.</li>
 * <li><b>Output</b> (what the town made yesterday, in coin: Economy), <b>wages</b> (the mean day's wage of the hands at
 *     work), <b>unemployment</b> (the grown hands out of work against all who could work: the labour market's count),
 *     <b>population</b>.</li>
 * <li><b>The money supply</b>: every coin in the town (the census: the treasury, purses, tills, funds, the bank's and the
 *     club's chests), the club's chest set aside and its savers' deposits counted instead (coin and deposits, as an
 *     economist counts it); with the town's debt beside it (its IOUs to its market and its bonds) and the firms' loans.</li>
 * <li><b>The treasury</b>, the trade in and out with other towns (EconRegion), the firms open, and the cycle's own figures
 *     (EconCycle).</li>
 * </ul>
 */
public final class EconIndex {

    private EconIndex() {}

    /** The household's basket: the thing and its weight. */
    static final Item[] BASKET = { Items.BREAD, Items.POTATO, Items.CARROT, Items.WHEAT, Items.BEETROOT, Items.APPLE, Items.COOKED_BEEF,
        Items.COOKED_COD, Items.OAK_LOG, Items.COBBLESTONE, Items.COAL, Items.IRON_INGOT, Items.WHITE_WOOL };
    static final double[] WEIGHT = { 4, 2, 2, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1 };
    /** Days kept. */
    public static final int KEPT = 60;

    /** The series kept, by name: the dashboard's and the tests'. */
    public static final List<String> SERIES = List.of("day", "cpi", "wage", "unemp", "output", "pop", "treasury", "debt", "money", "loans",
        "firms", "emp", "phase", "rate", "tradeIn", "tradeOut", "deposits", "bonds");

    static CompoundTag hist(UUID village) {
        return Accounts.sub(EconRegion.data(village), "hist");
    }

    /** One series, oldest first (empty if none). */
    public static long[] series(UUID village, String name) {
        return hist(village).getLongArray(name);
    }

    /** The last value of a series, or {@code or}. */
    public static long last(UUID village, String name, long or) {
        long[] s = series(village, name);
        return s.length == 0 ? or : s[s.length - 1];
    }

    /** The value {@code back} days before the last, or {@code or}. */
    public static long back(UUID village, String name, int back, long or) {
        long[] s = series(village, name);
        return s.length <= back ? or : s[s.length - 1 - back];
    }

    static void push(CompoundTag h, String name, long v) {
        long[] s = h.getLongArray(name);
        long[] n = s.length >= KEPT ? Arrays.copyOfRange(s, s.length - KEPT + 1, s.length + 1) : Arrays.copyOf(s, s.length + 1);
        n[n.length - 1] = v;
        h.putLongArray(name, n);
    }

    /** What the basket costs today (in this town's own prices, before its price level), coin. */
    public static double basket(ServerLevel level, UUID village) {
        double sum = 0;
        for (int i = 0; i < BASKET.length; i++) sum += WEIGHT[i] * PriceIndex.each(level, village, new ItemStack(BASKET[i]));
        return sum;
    }

    /** The morning's figures, once a day (EconCycle.morning, before the cycle steps). Returns false if taken already. */
    public static boolean record(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag h = hist(id);
        if (h.contains("lastDay") && h.getLong("lastDay") >= day) return false;
        h.putLong("lastDay", day);
        // The price index, chain-linked.
        double cost = basket(level, id);
        double was = h.getDouble("basket");
        long cpiWas = last(id, "cpi", 10000);
        long cpi = was <= 0 || cost <= 0 ? cpiWas : Math.round(cpiWas * Math.max(0.5, Math.min(2.0, cost / was)));
        h.putDouble("basket", cost);
        push(h, "day", day);
        push(h, "cpi", cpi);
        // Wages and work.
        int working = 0, bill = 0, grown = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            grown++;
            if (f.stationTask() != AssistantEntity.StationTask.NONE) { working++; bill += Wealth.wage(f); }
        }
        push(h, "wage", working == 0 ? 0 : Math.round(bill * 100.0 / working));
        int out = unemployed(id);
        push(h, "unemp", grown == 0 ? 0 : Math.round(out * 1000.0 / Math.max(1, out + working)));
        push(h, "output", Economy.yesterday(id));
        push(h, "pop", Villages.headcount(id));
        push(h, "treasury", com.jrpetty.mcassistant.village.Ledger.coins(id));
        long bonds = TownBonds.outstanding(id);
        push(h, "debt", (Accounts.debts(id) + bonds) / 100);
        push(h, "bonds", bonds / 100);
        long coin = CoinCensus.count(id), club = FolkCredit.clubCash(id), dep = FolkCredit.deposits(id);
        push(h, "money", (coin - club + dep) / 100);
        push(h, "deposits", dep / 100);
        push(h, "loans", FolkCredit.loansOutQuick(id) / 100);
        push(h, "firms", Enterprise.open(id).size());
        long[] t = EconRegion.onDay(id, day - 1);
        push(h, "tradeIn", t[0] / 100);
        push(h, "tradeOut", t[1] / 100);
        Accounts.dirty();
        return true;
    }

    /** The cycle's own figures for the day, after it steps (EconCycle). */
    static void recordCycle(UUID village, double employment, int phase, int rateBp) {
        CompoundTag h = hist(village);
        push(h, "emp", Math.round(employment * 1000));
        push(h, "phase", phase);
        push(h, "rate", rateBp);
        Accounts.dirty();
    }

    /** The grown hands out of work (the labour market's count, where it is at work; else those at no trade). */
    static int unemployed(UUID village) {
        if (LabourMarket.live(village)) return LabourMarket.counts(village, LabourBook.town(village))[2];
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() == AssistantEntity.StationTask.NONE && !f.isHired()) n++;
        }
        return n;
    }

    /** The price index today (a hundred the first morning), in hundredths. */
    public static long cpi(UUID village) {
        return last(village, "cpi", 10000);
    }

    /** Inflation over the last week (or as many days as there are), a fraction (0.05 = five in the hundred). */
    public static double inflationWeek(UUID village) {
        long[] s = series(village, "cpi");
        if (s.length < 2) return 0;
        long then = s[Math.max(0, s.length - 8)];
        return then <= 0 ? 0 : s[s.length - 1] / (double) then - 1;
    }
}
