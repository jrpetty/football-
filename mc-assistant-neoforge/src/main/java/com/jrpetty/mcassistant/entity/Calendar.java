package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [featlife] The week: seven named days, the same in every town (the world's own day count: day 0 is a Monday), five of
 * them weekdays and two the weekend.
 * <ul>
 * <li><b>Saturday</b> is market day: the stalls go up in the square (Market, MarketDays), most of the works are shut
 *     (WorkHours) and the town's folk have the day to themselves: they wander, shop, sell what they have spare from a
 *     blanket stall, and drink at the tavern (WeekendLife).</li>
 * <li><b>Sunday</b> is the day of rest (RestDay): the service in the morning, the games and the football after, the
 *     feast in the evening (Gatherings). Shops whose owners choose to open seven days open in the afternoon.</li>
 * <li><b>The fair</b>: the fourth Saturday of each month (a month is four weeks, twenty-eight days, the town's year of
 *     TownCalendar), the square is full of stalls and traders walk in from the neighbouring towns (Fairground).</li>
 * <li>No school on either day (SchoolDays).</li>
 * </ul>
 * Nothing here is kept: it is all read off the day. The client reads it too (the calendar on the HUD: client/LifeHud).
 */
public final class Calendar {

    private Calendar() {}

    /** The seven days, Monday first. */
    public enum Day {
        MONDAY("Monday", "Mon"), TUESDAY("Tuesday", "Tue"), WEDNESDAY("Wednesday", "Wed"), THURSDAY("Thursday", "Thu"),
        FRIDAY("Friday", "Fri"), SATURDAY("Saturday", "Sat"), SUNDAY("Sunday", "Sun");

        public final String word, shortWord;

        Day(String word, String shortWord) {
            this.word = word;
            this.shortWord = shortWord;
        }

        public boolean weekend() {
            return this == SATURDAY || this == SUNDAY;
        }
    }

    public static final int WEEK = 7;
    /** A month: four weeks. The fair is on its fourth Saturday. */
    public static final int MONTH = 28;
    /** The day in the month the fair falls on (the fourth Saturday: day 26 of 0..27). */
    public static final int FAIR_DAY_OF_MONTH = 26;

    /** The day of the week, 0 (Monday) to 6 (Sunday). */
    public static int dow(long day) {
        return (int) Math.floorMod(day, (long) WEEK);
    }

    public static Day day(long day) {
        return Day.values()[dow(day)];
    }

    public static long today(Level level) {
        return level.getDayTime() / 24000L;
    }

    public static Day today(ServerLevel level) {
        return day(today((Level) level));
    }

    public static boolean isWeekend(long day) {
        return day(day).weekend();
    }

    public static boolean isWeekend(Level level) {
        return isWeekend(today(level));
    }

    public static boolean isWeekday(long day) {
        return !isWeekend(day);
    }

    /** Saturday: the weekly market (Market.marketDay). */
    public static boolean isMarketDay(long day) {
        return day(day) == Day.SATURDAY;
    }

    public static boolean isMarketDay(Level level) {
        return isMarketDay(today(level));
    }

    /** Sunday: the day of rest, where a town keeps one (RestDay.today has the rest of the rule). */
    public static boolean isRestDay(long day) {
        return day(day) == Day.SUNDAY;
    }

    /** Wednesday: a mercantile town's second market (Ethos.extraMarket). */
    public static boolean isMidweek(long day) {
        return day(day) == Day.WEDNESDAY;
    }

    /** The fair: the fourth Saturday of each month. */
    public static boolean isFairDay(long day) {
        return Math.floorMod(day, (long) MONTH) == FAIR_DAY_OF_MONTH;
    }

    public static boolean isFairDay(Level level) {
        return isFairDay(today(level));
    }

    /** Days until the next fair (0: today). */
    public static int daysToFair(long day) {
        return (int) Math.floorMod(FAIR_DAY_OF_MONTH - day, (long) MONTH);
    }

    /** Days until the next day of this kind (0: today). */
    public static int daysTo(long day, Day d) {
        return Math.floorMod(d.ordinal() - dow(day), WEEK);
    }

    /** The week of the month, 1 to 4. */
    public static int weekOfMonth(long day) {
        return (int) (Math.floorMod(day, (long) MONTH) / WEEK) + 1;
    }

    /** "Saturday", "Sunday"... */
    public static String name(long day) {
        return day(day).word;
    }

    /** What the day is, in a few words: "Saturday: market day", "the fair", "Sunday: the day of rest". */
    public static String what(long day) {
        if (isFairDay(day)) return "fair day";
        if (isMarketDay(day)) return "market day";
        if (isRestDay(day)) return "the day of rest";
        return day(day) == Day.FRIDAY ? "the last working day of the week" : "a working day";
    }

    /** The HUD's line: "Saturday · Market day · Week 2". */
    public static String hudLine(long day) {
        String w = what(day);
        return name(day) + "  ·  " + Character.toUpperCase(w.charAt(0)) + w.substring(1) + "  ·  Week " + weekOfMonth(day);
    }

    /** "today", "tomorrow", "on Saturday", "in 9 days". */
    public static String when(long today, long day) {
        long d = day - today;
        if (d == 0) return "today";
        if (d == 1) return "tomorrow";
        if (d > 1 && d < WEEK) return "on " + name(day);
        if (d < 0) return d == -1 ? "yesterday" : (-d) + " days ago";
        return "in " + d + " days";
    }

    // ------------------------------------------------------------------ the board and the books

    /** The board's lines (TownCalendar.board): the day of the week, and what is coming. */
    public static List<String> board(ServerLevel level, @Nullable UUID village) {
        List<String> out = new ArrayList<>();
        long day = today((Level) level);
        StringBuilder sb = new StringBuilder(name(day)).append(": ").append(what(day)).append('.');
        int fair = daysToFair(day);
        if (fair > 0 && fair <= 7) sb.append(" The fair is ").append(when(day, day + fair)).append('.');
        else if (!isMarketDay(day)) sb.append(" Market ").append(when(day, day + daysTo(day, Day.SATURDAY))).append('.');
        out.add("RN|" + sb);
        if (village != null) {
            String hours = WorkHours.boardLine(village, day);
            if (hours != null) out.add("RM|" + hours);
        }
        return out;
    }

    /** The books' lines (TownCalendar.book): the week laid out, and what each day holds. */
    public static List<String> book(ServerLevel level, @Nullable UUID village) {
        List<String> out = new ArrayList<>();
        long day = today((Level) level);
        out.add("Today is " + name(day) + ", " + what(day) + " (week " + weekOfMonth(day) + " of the month). The fair is "
            + when(day, day + daysToFair(day)) + ".");
        out.add("The week: Monday to Friday, work; Saturday, market day and most of the works shut; Sunday, the day of rest.");
        if (village != null) out.addAll(WorkHours.bookLines(level, village));
        return out;
    }
}
