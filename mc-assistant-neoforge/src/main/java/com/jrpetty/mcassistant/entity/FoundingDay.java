package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Founding Day: once a year (the town's year of four weeks, TownCalendar), on the day it was
 * founded, the town keeps its birthday.
 *
 * <p>In the evening, before any other gathering that night (the weekly feast waits a week; rain
 * does not put it off), the village gathers before the board — the leader's hall behind it, once
 * there is one — and the leader (or, with nobody leading, the eldest) reads out the year's
 * chronicle: the things that mattered most, in the order they happened, a line at a time, slowly
 * enough to read over their heads ("Day 9: the smithy was opened"). Then there is a feast out of
 * the stores (Assemblies' feast: food passed round, eaten there) and, if the stores hold the
 * gunpowder and the paper to make them, fireworks over the board — a rocket made of each powder
 * and paper taken, a bonfire's sparks once they run out. The history notes the year kept.
 */
public final class FoundingDay {

    private FoundingDay() {}

    /** The lines of the year's chronicle read out at most. */
    static final int LINES = 8;

    /** Tests: a town founded on another day than its history says. */
    private static final Map<UUID, Long> FOUNDED = new ConcurrentHashMap<>();
    /** What was read out at each town's last Founding Day, in order (the tests read it back). */
    private static final Map<UUID, List<String>> READ = new ConcurrentHashMap<>();

    public static void resetForTests() {
        FOUNDED.clear();
        READ.clear();
    }

    /** Tests: count this town's years from this day. */
    public static void foundedForTests(UUID village, long day) {
        FOUNDED.put(village, day);
    }

    /** The day the town was founded (its history's first day), or -1. */
    public static long founded(@Nullable UUID village) {
        if (village == null) return -1;
        Long f = FOUNDED.get(village);
        return f != null ? f : Chronicle.foundedOn(village);
    }

    /** Is today this town's Founding Day? */
    public static boolean today(@Nullable UUID village, long day) {
        long f = founded(village);
        return f >= 0 && day > f && (day - f) % TownCalendar.YEAR_DAYS == 0;
    }

    /** Is Founding Day to be kept tonight: today, and not kept already (a restart on the evening itself)? */
    public static boolean due(@Nullable UUID village, long day) {
        return today(village, day) && TownCalendar.foundingKept(village) != day;
    }

    /** The town's age in its own years, on this day (its first Founding Day it is one). */
    public static long years(UUID village, long day) {
        long f = founded(village);
        return f < 0 ? 0 : (day - f) / TownCalendar.YEAR_DAYS;
    }

    /** The next Founding Day from this day (today, if today is one and it has not been kept yet), or -1. */
    public static long next(UUID village, long day) {
        long f = founded(village);
        if (f < 0) return -1;
        long n = f + TownCalendar.YEAR_DAYS * Math.max(1, (day - f + TownCalendar.YEAR_DAYS - 1) / TownCalendar.YEAR_DAYS);
        if (n == day && TownCalendar.foundingKept(village) == day) n += TownCalendar.YEAR_DAYS;
        return n;
    }

    /** The board's and the books' line: "Founding Day: day 29, our first year — in 6 days". */
    @Nullable
    static String line(UUID village, long day) {
        long n = next(village, day);
        if (n < 0) return null;
        long in = n - day;
        String when = in == 0 ? "today!" : in == 1 ? "tomorrow" : "in " + in + " days";
        return "Founding Day: day " + (n + 1) + ", the town's " + TownCalendar.ordinal(years(village, n)) + " year — " + when;
    }

    // ------------------------------------------------------------------ the gathering

    /** Tonight's Founding Day, before the board (Assemblies.tick, in the evening). */
    static Assemblies.Assembly assembly(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        BlockPos lectern = VillageBoards.lectern(id);
        Direction facing = VillageBoards.facingOf(id);
        return new Assemblies.Assembly(id, Assemblies.Kind.FOUNDING, Long.toString(years(id, day)), day,
            lectern != null ? lectern : v.centre(), facing != null ? facing : Direction.SOUTH, Assemblies.Layout.ARC);
    }

    /** The eldest grown folk, by years, to read the chronicle when nobody leads. */
    @Nullable
    static UUID eldest(UUID village) {
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isSleeping()) continue;
            if (best == null || f.ageYears() > best.ageYears()) best = f;
        }
        return best == null ? null : best.getUUID();
    }

    /** What is said: the year's chronicle, read out a line at a time, then the toast and the feast. */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        UUID id = a.village;
        String name = Villages.name(id);
        long years = years(id, a.day);
        READ.put(id, new ArrayList<>());
        long since = a.day - founded(id);
        s.add(new Assemblies.Line(null, years < 1
            ? "Friends! We keep our founding: " + name + " was founded " + since + (since == 1 ? " day" : " days") + " ago."
            : "Friends! It is Founding Day: " + name + " is " + TownCalendar.inWords((int) years) + (years == 1 ? " year old" : " years old")
                + " today.", '!', null));
        List<Chronicle.Entry> year = theYear(id, a.day);
        if (year.isEmpty()) {
            s.add(new Assemblies.Line(null, "A quiet year: we worked, we ate, we slept, and all of us are still here.", '~', null));
        } else {
            s.add(new Assemblies.Line(null, "Hear the year's chronicle, as it was written down.", '~', null));
            for (Chronicle.Entry e : year) {
                String what = e.text().isEmpty() ? e.text() : Character.toUpperCase(e.text().charAt(0)) + e.text().substring(1);
                String said = "Day " + (e.day() + 1) + ": " + what + (what.endsWith(".") || what.endsWith("!") ? "" : ".");
                s.add(new Assemblies.Line(null, said, react(e.text()), () -> READ.computeIfAbsent(id, k -> new ArrayList<>()).add(said)));
            }
        }
        s.add(new Assemblies.Line(null, FolkTalk.pick(r, "To " + name + ", and to the year to come!", "Here's to " + name
            + " — and to everyone who made it!"), '!', null));
        s.add(new Assemblies.Line(null, "Now eat, all of you — and look up!", '!', null));
    }

    /** How the crowd takes a line of the chronicle: a hush for the dead, a cheer for the good. */
    private static char react(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("died") || t.contains("passed") || t.contains("lost") || t.contains("killed") || t.contains("frail")) return '~';
        if (t.contains("wed") || t.contains("born") || t.contains("came into") || t.contains("opened") || t.contains("grew up")
            || t.contains("beat") || t.contains("founded")) return '!';
        return '?';
    }

    /** The year's chronicle: its most notable entries since the last Founding Day (or the founding), in order. */
    static List<Chronicle.Entry> theYear(UUID village, long day) {
        List<Chronicle.Entry> all = new ArrayList<>();
        // The first year from the founding itself; every other from the day after the last Founding Day.
        long from = years(village, day) <= 1 ? day - TownCalendar.YEAR_DAYS : day - TownCalendar.YEAR_DAYS + 1;
        for (Chronicle.Entry e : Chronicle.of(village)) {
            if (e.day() >= from && e.day() <= day && !e.text().startsWith("Founding Day")) all.add(e);
        }
        all.sort(Comparator.comparingLong(Chronicle.Entry::day));     // in the order it happened (a stable sort)
        if (all.size() > LINES) {
            List<Chronicle.Entry> best = new ArrayList<>(all);
            best.sort(Comparator.comparingInt((Chronicle.Entry e) -> -weight(e.text())).thenComparingLong(Chronicle.Entry::day));
            List<Chronicle.Entry> keep = best.subList(0, LINES);
            all.removeIf(e -> !keep.contains(e));
        }
        return all;
    }

    /** How much a line of the history matters, to be read out on Founding Day. */
    static int weight(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("came into") || t.contains(" age")) return 10;
        if (t.contains("founded")) return 9;
        if (t.contains("were wed")) return 8;
        if (t.contains("died") || t.contains("passed away") || t.contains("killed")) return 7;
        if (t.contains("born")) return 7;
        if (t.contains("elected") || t.contains("leader") || t.contains("elder")) return 6;
        if (t.contains("raid") || t.contains("beat off") || t.contains("band")) return 6;
        if (t.contains("was opened")) return 5;
        if (t.contains("grew up")) return 4;
        if (t.contains("turned")) return 3;
        return 1;
    }

    /** Kept: into the history, and remembered by the town (Assemblies.close). */
    static void kept(ServerLevel level, Assemblies.Assembly a) {
        UUID id = a.village;
        long years = years(id, a.day);
        TownCalendar.foundingKept(id, a.day);
        Villages.tell(id, a.day, "Founding Day: " + Villages.name(id) + " kept " + (years < 1 ? "its founding" : "its "
            + TownCalendar.ordinal(years) + " year") + ", with a feast"
            + (a.fired > 0 ? " and fireworks" : ""));
    }

    /** For the tests: what has been read out at this town's Founding Day so far, in order. */
    public static List<String> readOut(UUID village) {
        List<String> r = READ.get(village);
        return r == null ? List.of() : new ArrayList<>(r);
    }
}
