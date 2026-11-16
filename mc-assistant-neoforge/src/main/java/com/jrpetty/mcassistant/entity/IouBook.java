package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * [econ-1d] The town's IOU book: every IOU the town writes its market, who wrote it and for what, and every one paid off
 * or set against the market's holdings, day by day. The IOUs themselves are kept in Accounts (the list, oldest first);
 * this is their day book, so the council (and anybody looking at the Market page) can see where the town's debt to its
 * market comes from and where it goes: the works' stone, the watch's arrows, paid out of the taxes, set against the
 * market's surplus.
 *
 * <p>Each day: {@code w} written by who (the department, or the town's own business), {@code k} written for what (the
 * ware, its count left off), {@code o} paid off or set against by how (the taxes, the profits tax, the market's holdings,
 * its weekly surplus, the treasury's float), and in the tests the place in the code each was written from ({@code s}).
 * The last fortnight kept. Kept with the accounts (Accounts).
 */
public final class IouBook {

    private IouBook() {}

    static final int DAYS = 14;

    /** Tests: each IOU's place in the code written down too (where it was written from). */
    private static boolean trace;

    public static void traceForTests(boolean on) {
        trace = on;
    }

    static CompoundTag book(UUID village) {
        return Accounts.sub(Accounts.town(village), "iouBook");
    }

    /** Today's page, opened if need be (the last fortnight kept, oldest first). */
    static CompoundTag today(UUID village) {
        CompoundTag b = book(village);
        long day = Accounts.dayNow();
        ListTag days = Accounts.list(b, "days");
        if (!days.isEmpty()) {
            CompoundTag last = days.getCompound(days.size() - 1);
            if (last.getLong("day") == day) return last;
        }
        CompoundTag t = new CompoundTag();
        t.putLong("day", day);
        days.add(t);
        while (days.size() > DAYS) days.remove(0);
        Accounts.dirty();
        return t;
    }

    /** An IOU written (Accounts.debt): by whom, for what ("12 cobblestone"), so many pennies. */
    static void written(UUID village, String who, String what, long p) {
        if (village == null || p <= 0) return;
        CompoundTag t = today(village);
        Accounts.add(Accounts.sub(t, "w"), who, p);
        Accounts.add(Accounts.sub(t, "k"), ware(what), p);
        Accounts.add(t, "written", p);
        if (trace) Accounts.add(Accounts.sub(t, "s"), site(), p);
        Accounts.dirty();
        TownBudget.check(village);                                       // the works put off the moment the town is short
    }

    /** IOUs paid off in coin, or set against what the market holds: how ("taxes", "the market's holdings"...), so many pennies. */
    static void cleared(UUID village, String how, long p) {
        if (village == null || p <= 0) return;
        CompoundTag t = today(village);
        Accounts.add(Accounts.sub(t, "o"), how, p);
        Accounts.add(t, "cleared", p);
        Accounts.dirty();
    }

    /** "12 cobblestone (requisitioned)" as "cobblestone". */
    static String ware(String what) {
        String s = what == null ? "?" : what.trim();
        int i = s.indexOf(' ');
        if (i > 0 && s.substring(0, i).chars().allMatch(Character::isDigit)) s = s.substring(i + 1);
        int j = s.indexOf(" (");
        if (j > 0) s = s.substring(0, j);
        return s.isEmpty() ? "?" : s;
    }

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final java.util.Set<String> PLUMBING = java.util.Set.of(
        IouBook.class.getName(), Accounts.class.getName(), MarketMaker.class.getName(), TownWork.class.getName(), Crafts.class.getName(),
        Market.class.getName(), Workshop.class.getName());

    /** The first two places outside the money's plumbing that led to this IOU ("Builders.lay:120<Builders.tick:88"). */
    static String site() {
        return WALKER.walk(s -> {
            List<String> out = new ArrayList<>();
            s.filter(f -> !PLUMBING.contains(f.getClassName()) && f.getClassName().startsWith("com.jrpetty"))
                .limit(2).forEach(f -> out.add(f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber()));
            return out.isEmpty() ? "?" : String.join("<", out);
        });
    }

    // ------------------------------------------------------------------ reading it

    /** The last seven days' IOUs written and cleared, pennies: {written, cleared}. */
    public static long[] week(UUID village) {
        long w = 0, c = 0, day = Accounts.dayNow();
        for (Tag e : Accounts.list(book(village), "days")) {
            CompoundTag t = (CompoundTag) e;
            if (day - t.getLong("day") >= 7) continue;
            w += t.getLong("written");
            c += t.getLong("cleared");
        }
        return new long[]{ w, c };
    }

    /** A day's page in a line: what was written, by whom and for what, and what was cleared and how. */
    public static String dayLine(UUID village, long day) {
        for (Tag e : Accounts.list(book(village), "days")) {
            CompoundTag t = (CompoundTag) e;
            if (t.getLong("day") != day) continue;
            return "written " + Businesses.pence(t.getLong("written")) + " by " + top(t.getCompound("w"), 6) + "; for " + top(t.getCompound("k"), 8)
                + (t.contains("s") ? "; from " + top(t.getCompound("s"), 8) : "")
                + "; cleared " + Businesses.pence(t.getLong("cleared")) + " by " + top(t.getCompound("o"), 6);
        }
        return "nothing on day " + (day + 1);
    }

    /** The week in a line, for the Market page: written by whom, cleared how. */
    public static String weekLine(UUID village) {
        Map<String, Long> by = new TreeMap<>(), how = new TreeMap<>();
        long day = Accounts.dayNow();
        for (Tag e : Accounts.list(book(village), "days")) {
            CompoundTag t = (CompoundTag) e;
            if (day - t.getLong("day") >= 7) continue;
            CompoundTag w = t.getCompound("w"), o = t.getCompound("o");
            for (String k : w.getAllKeys()) by.merge(k, w.getLong(k), Long::sum);
            for (String k : o.getAllKeys()) how.merge(k, o.getLong(k), Long::sum);
        }
        long[] wk = week(village);
        if (wk[0] == 0 && wk[1] == 0) return "";
        return "IOUs this week: written " + Businesses.pence(wk[0]) + (by.isEmpty() ? "" : " (" + words(by, 4) + ")") + ", cleared "
            + Businesses.pence(wk[1]) + (how.isEmpty() ? "" : " (" + words(how, 4) + ")");
    }

    static String top(CompoundTag c, int n) {
        Map<String, Long> m = new TreeMap<>();
        for (String k : c.getAllKeys()) m.put(k, c.getLong(k));
        return m.isEmpty() ? "-" : words(m, n);
    }

    static String words(Map<String, Long> m, int n) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < l.size() && i < n; i++) out.add(l.get(i).getKey().toLowerCase(Locale.ROOT) + " " + Businesses.pence(l.get(i).getValue()));
        if (l.size() > n) out.add("and " + (l.size() - n) + " more");
        return String.join(", ", out);
    }
}
