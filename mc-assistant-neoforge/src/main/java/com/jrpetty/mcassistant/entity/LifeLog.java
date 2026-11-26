package com.jrpetty.mcassistant.entity;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] What a folk did with its days, and why: the lines its card shows ("Spent the afternoon at the tavern
 * (easygoing)") and its week in figures (hours worked, overtime, late mornings, coins spent, friends called on, services
 * attended), so its character's doing can be seen and measured (LifeWeights). Kept while the server runs, the last dozen
 * lines and the last two weeks' figures a folk.
 */
public final class LifeLog {

    private LifeLog() {}

    /** What is counted, a week at a time. */
    public enum Stat { WORKED, OVERTIME, LATE, SPENT, BOUGHT, SOLD, CALLS, TAVERN, MARKET, PARK, SERVICE, CLUB, DINNERS, HOME }

    public record Line(long day, String text) {}

    private static final int LINES = 12;
    private static final Map<UUID, Deque<Line>> LOG = new ConcurrentHashMap<>();
    /** By folk: week number to its figures. */
    private static final Map<UUID, Map<Long, EnumMap<Stat, Integer>>> WEEKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> OVERTIME_DAY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOG.clear();
        WEEKS.clear();
        OVERTIME_DAY.clear();
    }

    /** A line for its card: what it did, and the mark that made it ("easygoing"), if one did. Said once a day each. */
    public static void note(VillageFolkEntity f, long day, String what, @Nullable String why) {
        String text = what + (why == null || why.isEmpty() ? "" : " (" + why + ")") + ".";
        Deque<Line> l = LOG.computeIfAbsent(f.getUUID(), k -> new ArrayDeque<>());
        synchronized (l) {
            for (Line x : l) if (x.day() == day && x.text().equals(text)) return;
            l.addFirst(new Line(day, text));
            while (l.size() > LINES) l.removeLast();
        }
        f.brain(text);
    }

    public static List<Line> lines(VillageFolkEntity f) {
        Deque<Line> l = LOG.get(f.getUUID());
        if (l == null) return List.of();
        synchronized (l) {
            return new ArrayList<>(l);
        }
    }

    /** Count something it did this week. */
    public static void count(VillageFolkEntity f, Stat s, int n) {
        long week = Math.floorDiv(f.level().getDayTime() / 24000L, (long) Calendar.WEEK);
        Map<Long, EnumMap<Stat, Integer>> m = WEEKS.computeIfAbsent(f.getUUID(), k -> new ConcurrentHashMap<>());
        m.computeIfAbsent(week, k -> new EnumMap<>(Stat.class)).merge(s, n, Integer::sum);
        if (m.size() > 2) m.keySet().removeIf(w -> w < week - 1);
    }

    /** What it did of this this week. */
    public static int week(VillageFolkEntity f, Stat s) {
        long week = Math.floorDiv(f.level().getDayTime() / 24000L, (long) Calendar.WEEK);
        Map<Long, EnumMap<Stat, Integer>> m = WEEKS.get(f.getUUID());
        if (m == null || !m.containsKey(week)) return 0;
        return m.get(week).getOrDefault(s, 0);
    }

    /** This week and last together (the tests run a week through and read it at its end). */
    public static int fortnight(VillageFolkEntity f, Stat s) {
        Map<Long, EnumMap<Stat, Integer>> m = WEEKS.get(f.getUUID());
        if (m == null) return 0;
        int n = 0;
        for (EnumMap<Stat, Integer> e : m.values()) n += e.getOrDefault(s, 0);
        return n;
    }

    // ------------------------------------------------------------------ work's own

    static void overtime(VillageFolkEntity f, long day, String why) {
        OVERTIME_DAY.put(f.getUUID(), day);
        count(f, Stat.OVERTIME, 1);
        note(f, day, "Took the Saturday morning's extra work", why);
    }

    @Nullable
    static Long lastOvertime(VillageFolkEntity f) {
        return OVERTIME_DAY.get(f.getUUID());
    }

    static void late(VillageFolkEntity f, long day, String why) {
        count(f, Stat.LATE, 1);
        note(f, day, "Turned up to work an hour late", why);
    }

    /** Ten seconds of a working day, at work (LifeTick): the hours it puts in, a fifth of an hour (200 ticks) a beat. */
    static void worked(VillageFolkEntity f) {
        count(f, Stat.WORKED, 1);
    }

    /** Its week in a line, for its card: "This week: 31 hours' work, 1 overtime, spent 6c, 2 calls on friends, church once". */
    public static String weekLine(VillageFolkEntity f) {
        List<String> parts = new ArrayList<>();
        int w = week(f, Stat.WORKED);
        if (w > 0) parts.add(w / 5 + " hours' work");
        if (week(f, Stat.OVERTIME) > 0) parts.add(week(f, Stat.OVERTIME) + " overtime");
        if (week(f, Stat.LATE) > 0) parts.add("late " + times(week(f, Stat.LATE)));
        if (week(f, Stat.SPENT) > 0) parts.add("spent " + week(f, Stat.SPENT) + "c on itself");
        if (week(f, Stat.SOLD) > 0) parts.add("sold " + week(f, Stat.SOLD) + "c at its stall");
        if (week(f, Stat.CALLS) > 0) parts.add(week(f, Stat.CALLS) + (week(f, Stat.CALLS) == 1 ? " call on a friend" : " calls on friends"));
        if (week(f, Stat.TAVERN) > 0) parts.add("the tavern " + times(week(f, Stat.TAVERN)));
        if (week(f, Stat.SERVICE) > 0) parts.add("church " + times(week(f, Stat.SERVICE)));
        if (week(f, Stat.CLUB) > 0) parts.add("its club " + times(week(f, Stat.CLUB)));
        return parts.isEmpty() ? "" : "This week: " + String.join(", ", parts) + ".";
    }

    static String times(int n) {
        return n == 1 ? "once" : n == 2 ? "twice" : n + " times";
    }

    /** Its card's line: the latest few things it did and why. */
    public static String cardLine(VillageFolkEntity f) {
        List<Line> l = lines(f);
        if (l.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, l.size()); i++) sb.append(i == 0 ? "" : " ").append(l.get(i).text());
        return sb.toString();
    }
}
