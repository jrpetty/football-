package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [econ32d] The iron levels' pace: while the town's age waits on iron, iron levels that give fewer than {@link #POOR} ore
 * in a week are left for the next iron level deeper (or, with none left in the shaft's reach, a new mine: MineLevels.markSpent,
 * MineSites.exhausted), without waiting for them to be cut to their edges or to go barren.
 *
 * <p>A Diamond Age town was two hundred iron short with four of its twelve miners on its third iron level: 3,808 blocks of
 * tunnel, 6 to 19 ore a week, 0 to 15 a day, its first and second iron levels worked out. The barren rule (four hundred
 * blocks of drift with no ore at all) never fired, for a stray ore every few hundred blocks started its count again; and
 * the next iron level waited for it. Now the ore cut on the iron levels is kept day by day (the last eight days, with the
 * works); once a day, if the age asks for iron and the last seven days brought fewer than forty, the iron level being
 * worked deepest is marked worked out and the next opened, at most once in {@link #REST} days (the new level wants its
 * landing cut and its drifts begun before it is judged). The mine's report says the week's iron and why it moved on.
 */
public final class IronPace {

    private IronPace() {}

    /** Fewer ore than this from the iron levels in seven days, while the age asks for iron: poor. */
    static final int POOR = 40;
    /** Days after moving on before the iron levels are judged again. */
    static final int REST = 5;

    private static final String DAYS = "mine.iron.days", WHY = "mine.iron.why", LAST = "mine.iron.moved", SINCE = "mine.iron.since";

    /** Ore cut on one of the iron levels (MineWorks.done). */
    static void cut(ServerLevel level, UUID village, int id, int ore) {
        if (ore <= 0 || MineLevels.base(id) != MineWorks.IRON) return;
        long day = level.getDayTime() / 24000L;
        List<long[]> d = days(village);
        if (!d.isEmpty() && d.get(d.size() - 1)[0] == day) d.get(d.size() - 1)[1] += ore;
        else d.add(new long[]{ day, ore });
        while (d.size() > 8) d.remove(0);
        StringBuilder sb = new StringBuilder();
        for (long[] e : d) sb.append(sb.length() == 0 ? "" : ";").append(e[0]).append(':').append(e[1]);
        Ledger.note(village, DAYS, sb.toString());
    }

    static List<long[]> days(UUID village) {
        List<long[]> out = new ArrayList<>();
        String s = Ledger.note(village, DAYS);
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(";")) {
            String[] kv = p.split(":");
            try { out.add(new long[]{ Long.parseLong(kv[0]), Long.parseLong(kv[1]) }); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    /** Iron-level ore in the seven days to today. */
    static int week(UUID village, long today) {
        int n = 0;
        for (long[] e : days(village)) if (today - e[0] < 7 && e[0] <= today) n += (int) e[1];
        return n;
    }

    /** Once a day (MineWorks.tick): the iron levels judged, and the next one opened if they are poor. True if it moved on. */
    static boolean tick(ServerLevel level, MineWorks.Works w) {
        UUID v = w.village;
        long today = level.getDayTime() / 24000L;
        if (Long.toString(today).equals(Ledger.note(v, "mine.iron.looked"))) return false;
        Ledger.note(v, "mine.iron.looked", Long.toString(today));
        if (!Fuel.ageWants(level, v, Villages.Task.IRON)) return false;
        String last = Ledger.note(v, LAST);
        long since = Long.MAX_VALUE;
        try { if (last != null) since = today - Long.parseLong(last); } catch (NumberFormatException ignored) { }
        if (since < REST) return false;
        // The deepest-opened iron level still worked (the frontier); judged only once it has been worked a week.
        MineWorks.Lvl front = null;
        for (MineWorks.Lvl l : MineWorks.plan(level, w)) {
            if (MineLevels.base(l.id()) != MineWorks.IRON || MineLevels.spent(w, l.id()) || l.id() >= 31) continue;
            if ((w.rooms & (1 << l.id())) == 0) return false;            // a new iron level not yet opened: wait for it
            if (front == null || MineLevels.nth(l.id()) > MineLevels.nth(front.id())) front = l;
        }
        if (front == null || w.oreAll[front.id()] == 0) return false;   // nothing dug there yet
        // The days kept so far: from a load with no record, this week's count of the iron levels (MineWorks' own) stands in.
        String sinceRaw = Ledger.note(v, SINCE);
        long from;
        try { from = sinceRaw == null ? Long.MIN_VALUE : Long.parseLong(sinceRaw); } catch (NumberFormatException e) { from = Long.MIN_VALUE; }
        if (from == Long.MIN_VALUE) {
            int thisWeek = 0;
            for (int i = MineWorks.IRON; i < w.oreWeek.length; i += MineLevels.BASES) thisWeek += w.oreWeek[i];
            from = today - Math.floorMod(today, 7);
            Ledger.note(v, SINCE, Long.toString(from));
            if (thisWeek > 0 && from < today) {
                List<long[]> d = days(v);
                d.add(0, new long[]{ from, thisWeek });
                StringBuilder sb = new StringBuilder();
                for (long[] e : d) sb.append(sb.length() == 0 ? "" : ";").append(e[0]).append(':').append(e[1]);
                Ledger.note(v, DAYS, sb.toString());
            }
        }
        long start = since == Long.MAX_VALUE ? from : Math.max(from, today - since);   // from the last move on, at most a week
        long known = Math.min(7, today - start);                                       // whole days looked at
        if (known < 2) return false;
        int got = 0;
        for (long[] e : days(v)) if (e[0] >= start && today - e[0] < 7 && e[0] <= today) got += (int) e[1];
        if (got * 7L / known >= POOR) return false;
        String why = "the iron levels gave " + got + " ore in " + known + " days (under " + POOR + " a week) while the age waits on iron: "
            + front.name() + " (Y" + front.y() + ") left for the next iron level, day " + today;
        Ledger.note(v, WHY, why);
        Ledger.note(v, LAST, Long.toString(today));
        com.mojang.logging.LogUtils.getLogger().info("[MCA-WORKS] {}: {}", Villages.name(v), why);
        MineLevels.markSpent(level, w, front);
        return true;
    }

    /** For the mine's report: the week's iron from the iron levels, and why the works last moved on, or null. */
    @Nullable
    static String report(ServerLevel level, UUID village) {
        if (days(village).isEmpty() && Ledger.note(village, WHY) == null) return null;
        String why = Ledger.note(village, WHY);
        return "Iron from the iron levels in the last seven days: " + week(village, level.getDayTime() / 24000L) + " (poor under " + POOR + ")"
            + (why == null ? "." : "; " + why + ".");
    }

    /** Tests: so much iron-level ore on these days. */
    public static void daysForTests(UUID village, @Nullable String raw) {
        if (raw == null) Ledger.forget(village, DAYS); else Ledger.note(village, DAYS, raw);
        Ledger.forget(village, LAST);
        Ledger.forget(village, WHY);
        Ledger.forget(village, "mine.iron.looked");
        Ledger.forget(village, SINCE);
    }

    /** Tests: the record kept from this day. */
    public static void sinceForTests(UUID village, long day) {
        Ledger.note(village, SINCE, Long.toString(day));
    }

    public static boolean tickForTests(ServerLevel level, UUID village) {
        MineWorks.Works w = MineWorks.works(level, village);
        return w != null && tick(level, w);
    }

    @Nullable
    public static String whyForTests(UUID village) {
        return Ledger.note(village, WHY);
    }
}
