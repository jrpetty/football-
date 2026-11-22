package com.jrpetty.mcassistant.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [growth22] The town's metal, where it comes from and where it goes, by the day: iron (bars, raw, ore; a block nine) and
 * diamonds brought in by whom (the miners, the cave team, a trader) and taken out of the stores by what (the smith's picks,
 * the watch's kit, the golems, the fire brigade's buckets...). The mine's report shows yesterday's and today's (village
 * mine), so a town that sits in the Iron Age with seven iron in its stores can be asked why: none coming in, or all of it
 * going straight out again.
 */
public final class OreFlow {

    private OreFlow() {}

    public enum Metal { IRON, DIAMOND, COAL }   // [growth24] coal and charcoal too: the smelters' fuel

    /** One day's tally: per metal, per who, in (positive) and out (negative). */
    private static final class Day {
        long day = -1;
        final Map<String, int[]> in = new TreeMap<>(), out = new TreeMap<>();
    }

    private static final Map<UUID, Day[]> DAYS = new ConcurrentHashMap<>();

    /** The metal an item is, and how many units of it; null when it is neither. */
    @Nullable
    static Metal metal(ItemStack s) {
        if (s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE)
            || s.is(Items.IRON_BLOCK) || s.is(Items.RAW_IRON_BLOCK)) return Metal.IRON;
        if (s.is(Items.DIAMOND) || s.is(Items.DIAMOND_ORE) || s.is(Items.DEEPSLATE_DIAMOND_ORE) || s.is(Items.DIAMOND_BLOCK)) return Metal.DIAMOND;
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(Items.COAL_ORE) || s.is(Items.DEEPSLATE_COAL_ORE) || s.is(Items.COAL_BLOCK)) return Metal.COAL;   // [growth24]
        return null;
    }

    static int units(ItemStack s, int n) {
        return s.is(Items.IRON_BLOCK) || s.is(Items.RAW_IRON_BLOCK) || s.is(Items.DIAMOND_BLOCK) || s.is(Items.COAL_BLOCK) ? 9 * n : n;
    }

    private static Day today(UUID village, long day) {
        Day[] d = DAYS.computeIfAbsent(village, k -> new Day[]{ new Day(), new Day() });
        synchronized (d) {
            if (d[1].day != day) {
                if (d[1].day >= 0) d[0] = d[1];
                d[1] = new Day();
                d[1].day = day;
            }
            return d[1];
        }
    }

    /** Brought in (gathered by a working hand, bought in): who, so many of it. */
    static void in(@Nullable UUID village, long day, ItemStack s, int n, String who) {
        book(village, day, s, n, who, true);
    }

    /** Taken out of the town's stores, by what. */
    static void out(@Nullable UUID village, long day, ItemStack s, int n, String what) {
        book(village, day, s, n, what, false);
    }

    private static void book(@Nullable UUID village, long day, ItemStack s, int n, String who, boolean in) {
        if (village == null || n <= 0 || s.isEmpty()) return;
        Metal m = metal(s);
        if (m == null) return;
        Day d = today(village, day);
        synchronized (d) {
            (in ? d.in : d.out).computeIfAbsent(who, k -> new int[Metal.values().length])[m.ordinal()] += units(s, n);
        }
    }

    /** What took it out: the first of the town's own classes on the way to the stores that is not the stores' own. */
    static String taker() {
        // [growth24] The innermost of the town's own frames that is not the stores' plumbing (the take, the chests), named
        // with its method: "Crafts.forge", not "VillageFolkEntity" (the folk's tick that the smith's whole day runs under).
        return StackWalker.getInstance().walk(frames -> frames
            .filter(f -> f.getClassName().startsWith("com.jrpetty.mcassistant") && !plumbing(f))
            .findFirst().map(f -> {
                String c = f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1);
                String w = word(c);
                return w.equals(c) ? c + "." + f.getMethodName() : w;
            }).orElse("?"));
    }

    private static boolean plumbing(StackWalker.StackFrame f) {
        String c = f.getClassName(), m = f.getMethodName();
        if (c.endsWith(".OreFlow") || c.endsWith(".ZoneChests") || c.endsWith(".Market")) return true;
        if (c.endsWith(".TownWork") || c.endsWith(".Crafts")) return m.startsWith("take") || m.equals("lambda$take$0");
        return false;
    }

    /** A taker's class in the town's words (the smelter's raw iron comes back as bars: it is no use of it). */
    private static String word(String cls) {
        return switch (cls) {
            case "Links" -> "the furnaces (back as bars)";
            case "Picks", "Toolrack" -> "the miners' and hands' tools";
            case "WatchKit", "Workshop" -> "the watch's kit";
            default -> cls;
        };
    }

    /** Yesterday's and today's flow of each metal, in words (the mine's report). */
    public static List<String> lines(UUID village, long day) {
        List<String> out = new ArrayList<>();
        Day[] d = DAYS.get(village);
        if (d == null) return out;
        for (Metal m : Metal.values()) {
            for (int k = 0; k < 2; k++) {
                Day x = d[k];
                if (x == null || x.day < 0) continue;
                String when = x.day == day ? "today" : x.day == day - 1 ? "yesterday" : "day " + x.day;
                String ins = words(x.in, m), outs = words(x.out, m);
                if (ins.isEmpty() && outs.isEmpty()) continue;
                out.add(cap(m.name().toLowerCase()) + " " + when + ": in " + total(x.in, m) + (ins.isEmpty() ? "" : " (" + ins + ")")
                    + ", out " + total(x.out, m) + (outs.isEmpty() ? "" : " (" + outs + ")"));
            }
        }
        return out;
    }

    private static int total(Map<String, int[]> t, Metal m) {
        int n = 0;
        synchronized (t) { for (int[] v : t.values()) n += v[m.ordinal()]; }
        return n;
    }

    private static String words(Map<String, int[]> t, Metal m) {
        List<String> w = new ArrayList<>();
        synchronized (t) { t.forEach((k, v) -> { if (v[m.ordinal()] > 0) w.add(k + " " + v[m.ordinal()]); }); }
        return String.join(", ", w);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static void forgetForTests() {
        DAYS.clear();
    }
}
