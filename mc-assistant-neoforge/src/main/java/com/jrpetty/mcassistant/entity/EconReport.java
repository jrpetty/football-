package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [econ4] The Economy dashboard of the town's books (the client's EconomyPage) and {@code /village economy}: population,
 * output, the price index and inflation, wages, unemployment, the treasury and the town's debt, the money supply, trade in
 * and out by ware and by partner town, and the cycle's phase, each with its last sixty days drawn as a line; the council's
 * rate, spending and taxes as the times lean them; the shocks in force; the turns of the times.
 */
public final class EconReport {

    private EconReport() {}

    /** The charts the dashboard draws: the series, its title, how it is shown (by hundred: "/100"; by thousand: "/1000"). */
    static final String[][] CHARTS = {
        { "cpi", "Price index", "/100" }, { "output", "Output a day", "c" }, { "wage", "Mean wage", "/100c" },
        { "unemp", "Out of work", "/10%" }, { "treasury", "Treasury", "c" }, { "debt", "Town's debt", "c" },
        { "money", "Money supply", "c" }, { "pop", "Population", "" }, { "emp", "Employment (the reckoning)", "/10%" },
        { "rate", "Council's rate margin", "bp" }, { "tradeIn", "Bought in", "c" }, { "tradeOut", "Sold out", "c" } };

    /** The page's tag. */
    public static CompoundTag report(ServerLevel level, UUID id) {
        CompoundTag t = new CompoundTag();
        CycleModel.Phase ph = EconCycle.phase(id);
        t.putBoolean("live", ph != null);
        if (ph == null) {
            t.putString("state", MarketMaker.live(id) ? "The town's statistics are taken each morning: the first figures come tomorrow."
                : "No market of its own yet: the dashboard opens with the town's Market Opening Day.");
            return t;
        }
        long day = level.getDayTime() / 24000L;
        t.putString("phase", ph.words);
        t.putInt("phaseN", ph.ordinal());
        t.putString("times", EconCycle.line(id));
        ListTag charts = new ListTag();
        for (String[] c : CHARTS) {
            CompoundTag ch = new CompoundTag();
            long[] s = EconIndex.series(id, c[0]);
            int[] v = new int[s.length];
            for (int i = 0; i < s.length; i++) v[i] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, s[i]));
            ch.putString("key", c[0]);
            ch.putString("title", c[1]);
            ch.putString("unit", c[2]);
            ch.putIntArray("v", v);
            charts.add(ch);
        }
        t.put("charts", charts);
        t.putIntArray("phases", toInt(EconIndex.series(id, "phase")));
        t.putInt("inflation", (int) Math.round(EconIndex.inflationWeek(id) * 1000));
        t.putInt("rateBp", (int) Math.round(EconCycle.rateWeekPercent(id) * 100));
        t.putInt("clubBp", FolkCredit.rateBp(id));
        t.putInt("minWage", LabourMarket.minimumWage(id));
        t.putDouble("spend", EconCycle.spendFactor(id));
        t.putInt("taxLean", EconCycle.taxLean(id));
        t.putString("shocks", EconCycle.shocksLine(id, day));
        ListTag lines = new ListTag();
        for (String l : words(level, id, true)) lines.add(StringTag.valueOf(l));
        t.put("lines", lines);
        // Trade by ware and by partner, the week, both ways.
        for (String dir : new String[]{ "in", "out" }) {
            for (String by : new String[]{ "Goods", "Partners" }) {
                ListTag l = new ListTag();
                for (Map.Entry<String, Long> e : EconRegion.week(id, day, dir, by).entrySet()) {
                    CompoundTag r = new CompoundTag();
                    r.putString("name", e.getKey());
                    r.putLong("p", e.getValue());
                    l.add(r);
                    if (l.size() >= 8) break;
                }
                t.put(dir + by, l);
            }
        }
        CompoundTag st = EconCycle.state(id);
        ListTag turns = new ListTag();
        for (Tag e : st.getList("log", Tag.TAG_COMPOUND)) turns.add(e.copy());
        t.put("turns", turns);
        return t;
    }

    static int[] toInt(long[] s) {
        int[] v = new int[s.length];
        for (int i = 0; i < s.length; i++) v[i] = (int) s[i];
        return v;
    }

    /** The dashboard in words ({@code /village economy}); {@code full} adds the trade's lines. */
    public static List<String> words(ServerLevel level, UUID id, boolean full) {
        List<String> out = new ArrayList<>();
        CycleModel.Phase ph = EconCycle.phase(id);
        if (ph == null) {
            out.add(MarketMaker.live(id) ? "The town's statistics start tomorrow morning." : "No market of its own yet: its statistics start with its Market Opening Day.");
            return out;
        }
        long day = level.getDayTime() / 24000L;
        out.add("The times: " + ph.words + ". " + EconCycle.line(id) + ".");
        out.add(String.format(Locale.ROOT, "Population %d; output %d coins a day (a week ago %d); mean wage %.2fc; out of work %.1f%%.",
            EconIndex.last(id, "pop", 0), EconIndex.last(id, "output", 0), EconIndex.back(id, "output", 7, EconIndex.last(id, "output", 0)),
            EconIndex.last(id, "wage", 0) / 100.0, EconIndex.last(id, "unemp", 0) / 10.0));
        out.add(String.format(Locale.ROOT, "Price index %.1f (inflation %+.1f%% this week); money %d coins (deposits %d), the firms' loans %d.",
            EconIndex.cpi(id) / 100.0, EconIndex.inflationWeek(id) * 100, EconIndex.last(id, "money", 0), EconIndex.last(id, "deposits", 0),
            EconIndex.last(id, "loans", 0)));
        out.add(String.format(Locale.ROOT, "Treasury %d coins; the town's debt %d (bonds %d, owed to its own market on IOUs %s).",
            com.jrpetty.mcassistant.village.Ledger.coins(id), EconIndex.last(id, "debt", 0), EconIndex.last(id, "bonds", 0), Businesses.pence(Accounts.debts(id))));
        double[] c = EconCycle.stateForTests(id);
        out.add(String.format(Locale.ROOT, "The council's reckoning: employment %.1f%%, the wage share %.0f%%, profits %.0f%%, investment %.0f%% of output, the firms' debt %.2f; "
            + "its rate %.1f%% (the lenders at %.1f%% a week); its works borrow at %.0f%% of their usual room; minimum wage %d.",
            c[0] * 100, c[1] * 100, c[4] * 100, c[5] * 100, c[2], EconCycle.rateWeekPercent(id), FolkCredit.rateBp(id) / 100.0, EconCycle.spendFactor(id) * 100,
            LabourMarket.minimumWage(id)));
        String sh = EconCycle.shocksLine(id, day);
        if (!sh.isEmpty()) out.add("Shocks: " + sh + ".");
        long[] k = EconCycle.councilForTests(id);
        out.add("The council has raised its rate " + k[0] + " times and cut it " + k[1] + "; raised the minimum wage " + k[2] + " times; sat on the empty treasury " + k[3] + " times.");
        if (full) out.addAll(EconRegion.lines(level, id));
        return out;
    }

    /** The command's text. */
    public static String text(ServerLevel level, UUID id) {
        return "Economy dashboard:\n  " + String.join("\n  ", words(level, id, true));
    }
}
