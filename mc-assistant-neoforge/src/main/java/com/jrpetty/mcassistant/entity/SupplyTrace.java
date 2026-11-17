package com.jrpetty.mcassistant.entity;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [supply] The tests' look at a town's supply chains: for each ware, day by day, what came into its market (whose output,
 * by trade) and what went out of its stores (to whom, for what, from which place in the code). Off in play; the long
 * tests turn it on and log it every morning, so where a town's wheat goes (the baker's bread, a hungry hand's own loaves,
 * the archery's bales) can be read off rather than guessed.
 */
public final class SupplyTrace {

    private SupplyTrace() {}

    private static volatile boolean on;
    /** village|day|ware -> "in: who" / "out: who" -> count. */
    private static final Map<String, Map<String, Long>> BOOK = new ConcurrentHashMap<>();

    public static void traceForTests(boolean b) {
        on = b;
        if (!b) BOOK.clear();
    }

    public static boolean tracing() {
        return on;
    }

    private static Map<String, Long> page(UUID village, String key) {
        if (BOOK.size() > 20000) BOOK.clear();
        return BOOK.computeIfAbsent(village + "|" + Accounts.dayNow() + "|" + key, k -> new ConcurrentHashMap<>());
    }

    /** Out of the stores (MarketMaker.taken): so many, to whom ({@code how}: commons, charged, free...). */
    static void out(UUID village, ItemStack st, int k, String how, Accounts.Resolved r) {
        if (!on || village == null || k <= 0) return;
        VillageFolkEntity a = Accounts.actor();
        String who = how + " " + r.account().kind().name().toLowerCase(Locale.ROOT) + "/" + r.purpose().name().toLowerCase(Locale.ROOT)
            + (a == null ? "" : " " + a.stationTask().name().toLowerCase(Locale.ROOT)) + " @" + IouBook.site();
        page(village, Stockroom.key(st)).merge("out: " + who, (long) k, Long::sum);
    }

    /** Into the market (a producer's output bought). */
    static void in(UUID village, ItemStack st, long n, @Nullable String who) {
        if (!on || village == null || n <= 0) return;
        page(village, Stockroom.key(st)).merge("in: " + (who == null ? "?" : who), n, Long::sum);
    }

    /** Anything else worth a line (a field sown, a ware made). */
    public static void note(UUID village, String key, String what, long n) {
        if (!on || village == null || n <= 0) return;
        page(village, key).merge(what, n, Long::sum);
    }

    /** A day's lines, the wares asked for (all if null): "wheat: in 150 (farm 140); out 160 (...)". */
    public static List<String> dayLines(UUID village, long day, @Nullable List<String> wares) {
        List<String> out = new ArrayList<>();
        String head = village + "|" + day + "|";
        Map<String, Map<String, Long>> mine = new TreeMap<>();
        for (Map.Entry<String, Map<String, Long>> e : BOOK.entrySet()) {
            if (!e.getKey().startsWith(head)) continue;
            String ware = e.getKey().substring(head.length());
            if (wares != null && !wares.contains(ware)) continue;
            mine.put(ware, e.getValue());
        }
        for (Map.Entry<String, Map<String, Long>> e : mine.entrySet()) {
            long in = 0, outN = 0;
            List<Map.Entry<String, Long>> rows = new ArrayList<>(e.getValue().entrySet());
            rows.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
            StringBuilder ins = new StringBuilder(), outs = new StringBuilder(), other = new StringBuilder();
            for (Map.Entry<String, Long> r : rows) {
                if (r.getKey().startsWith("in: ")) { in += r.getValue(); ins.append(r.getKey().substring(4)).append(' ').append(r.getValue()).append(", "); }
                else if (r.getKey().startsWith("out: ")) { outN += r.getValue(); outs.append(r.getKey().substring(5)).append(' ').append(r.getValue()).append(", "); }
                else other.append(r.getKey()).append(' ').append(r.getValue()).append(", ");
            }
            out.add(e.getKey().replace("minecraft:", "") + ": in " + in + " (" + ins + "); out " + outN + " (" + outs + ")" + (other.length() > 0 ? "; " + other : ""));
        }
        return out;
    }
}
