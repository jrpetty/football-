package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [folk-ui] A folk's day, planned: when it works, takes its break, eats, has its free time and sleeps, worked out from
 * the very same things that make it do so (its shift and the town's bell, VillageFolkEntity.breakStart/breakLength, the
 * meals' hours in Meals, its bedtime, the school's lesson hours, the day of rest, the watch's two halves of the night),
 * so the plan its profile shows is the day it lives. {@link #now} says what it is really doing, read off its state,
 * for the profile to set beside the plan.
 *
 * <p>Day time runs 0 (six in the morning) to 24000. Nothing here is kept or ticked: it is worked out when asked (the
 * profile, every few seconds while it is open) from a handful of fields.
 */
public final class FolkSchedule {

    private FolkSchedule() {}

    /** What a stretch of the day is for. */
    public enum Kind {
        SLEEP("Sleep"), WORK("Work"), BREAK("Break"), FREE("Free time"), SCHOOL("School"), PLAY("Play"),
        WATCH("On watch"), REST_DAY("Day of rest"), LAID_UP("Laid up");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** One stretch of its day, in day ticks [from, to). */
    public record Slot(Kind kind, int from, int to) {
        boolean has(long t) { return t >= from && t < to; }
    }

    /** Clear-weather nightfall and daybreak, in day ticks: when the sky is dark enough for Level.isNight (a day
     *  shift's end and start without a town bell). Rain brings the dark a little sooner. */
    public static final int NIGHTFALL = 12542, DAYBREAK = 23460;
    /** The dusk bell (TownBell), which ends the day's work in a town that keeps it. */
    public static final int DUSK_BELL = 12000;
    /** When the noon bell rings: a town that keeps the bell takes its break then. */
    public static final int NOON_BELL = 6000;
    public static final int DAY = 24000;

    /** Its day today, start to end, with no gaps: the stretches in order. */
    public static List<Slot> today(VillageFolkEntity f) {
        List<Slot> out = new ArrayList<>(8);
        UUID village = f.ownerId();
        long dayTime = f.level().getDayTime();
        long day = dayTime / DAY;
        boolean bell = TownBell.keeps(village, dayTime);
        int dusk = bell ? DUSK_BELL : NIGHTFALL;
        int bed = (int) Math.max(dusk, Math.min(DAY - 1, f.bedtimeTick()));
        if (Health.laidUp(f)) {
            out.add(new Slot(Kind.LAID_UP, 0, DAY));
            return out;
        }
        if (f.isBaby()) {
            if (village != null && Villages.hasBuilt(village, "school") && !RestDay.today(village, day)) {
                out.add(new Slot(Kind.PLAY, 0, (int) School.LESSON_FROM));
                out.add(new Slot(Kind.SCHOOL, (int) School.LESSON_FROM, (int) School.LESSON_TO));
                out.add(new Slot(Kind.PLAY, (int) School.LESSON_TO, bed));
            } else {
                out.add(new Slot(Kind.PLAY, 0, bed));
            }
            out.add(new Slot(Kind.SLEEP, bed, DAY));
            return out;
        }
        boolean guard = f.stationTask() == StationTask.GUARD;
        if (!guard && RestDay.today(village, day)) {
            out.add(new Slot(Kind.REST_DAY, 0, bed));
            out.add(new Slot(Kind.SLEEP, bed, DAY));
            return out;
        }
        if (f.shift() == AssistantEntity.Shift.NIGHT) {
            out.add(new Slot(Kind.SLEEP, 0, NIGHTFALL - 4000));
            out.add(new Slot(Kind.FREE, NIGHTFALL - 4000, NIGHTFALL));
            out.add(new Slot(Kind.WORK, NIGHTFALL, DAYBREAK));
            out.add(new Slot(Kind.SLEEP, DAYBREAK, DAY));
            return out;
        }
        work(f, out, 0, dusk, bell);
        int midnight = (int) VillageFolkEntity.MIDNIGHT;
        if (guard && f.shift() == AssistantEntity.Shift.ALWAYS) {
            if (f.firstWatch()) {
                out.add(new Slot(Kind.WATCH, dusk, midnight));
                out.add(new Slot(Kind.SLEEP, midnight, DAY));
            } else {
                int b = Math.min(bed, midnight);
                if (b > dusk) out.add(new Slot(Kind.FREE, dusk, b));
                out.add(new Slot(Kind.SLEEP, b, midnight));
                out.add(new Slot(Kind.WATCH, midnight, DAY));
            }
            return out;
        }
        if (bed > dusk) out.add(new Slot(Kind.FREE, dusk, bed));
        // Without a bell it is up and at work again at daybreak, before the day's count turns over.
        if (!bell && bed < DAYBREAK) {
            out.add(new Slot(Kind.SLEEP, bed, DAYBREAK));
            out.add(new Slot(Kind.WORK, DAYBREAK, DAY));
        } else {
            out.add(new Slot(Kind.SLEEP, bed, DAY));
        }
        return out;
    }

    /** The working day [from, to), with its break cut out of it. */
    private static void work(VillageFolkEntity f, List<Slot> out, int from, int to, boolean bell) {
        Kind kind = Kind.WORK;
        long start = f.breakStart();
        if (start >= DAY) start = NOON_BELL;                           // waiting on the noon bell: it rings at noon
        int bs = (int) Math.max(from, Math.min(to, start));
        int be = (int) Math.max(bs, Math.min(to, start + f.breakLength()));
        if (be <= bs) {
            out.add(new Slot(kind, from, to));
            return;
        }
        if (bs > from) out.add(new Slot(kind, from, bs));
        out.add(new Slot(Kind.BREAK, bs, be));
        if (to > be) out.add(new Slot(kind, be, to));
    }

    /** What its plan says it should be doing at this time of day. */
    public static Kind planned(VillageFolkEntity f, long dayTime) {
        long t = Math.floorMod(dayTime, (long) DAY);
        for (Slot s : today(f)) if (s.has(t)) return s.kind();
        return Kind.FREE;
    }

    /** What it is really doing, read off its state (not its plan). */
    public static Kind now(VillageFolkEntity f) {
        if (Health.laidUp(f)) return Kind.LAID_UP;
        if (f.isSleeping()) return Kind.SLEEP;
        long dayTime = f.level().getDayTime();
        long t = dayTime % DAY;
        if (f.isBaby()) return School.doing(f) != null ? Kind.SCHOOL : t >= f.bedtimeTick() ? Kind.SLEEP : Kind.PLAY;
        String why = f.offWorkWhy();
        boolean guard = f.stationTask() == StationTask.GUARD;
        if (why.isEmpty()) return guard && f.level().isNight() ? Kind.WATCH : Kind.WORK;
        if (why.equals("on a break")) return Kind.BREAK;
        if (why.equals("the day of rest")) return t >= f.bedtimeTick() ? Kind.SLEEP : Kind.REST_DAY;
        if (why.equals("laid up")) return Kind.LAID_UP;
        if (t >= f.bedtimeTick() || t < 1000L && f.level().isNight()) return Kind.SLEEP;   // turning in, or up before the dawn
        return Kind.FREE;
    }

    // ------------------------------------------------------------------ for the profile

    /** Its day as the profile draws it: the stretches, the meals (when, and eaten or missed), now, and the plan's word. */
    public static CompoundTag report(VillageFolkEntity f) {
        CompoundTag out = new CompoundTag();
        long dayTime = f.level().getDayTime();
        long day = dayTime / DAY, t = dayTime % DAY;
        ListTag slots = new ListTag();
        for (Slot s : today(f)) {
            CompoundTag c = new CompoundTag();
            c.putString("kind", s.kind().name());
            c.putString("label", s.kind() == Kind.WORK && f.stationTask() == StationTask.NONE ? "Looking for a trade" : s.kind().label);
            c.putInt("from", s.from());
            c.putInt("to", s.to());
            slots.add(c);
        }
        out.put("slots", slots);
        ListTag meals = new ListTag();
        if (!f.isBaby()) {
            Meals.Book b = f.meals();
            int behind = 0;
            for (Meals.Meal m : Meals.Meal.values()) if (Meals.hadToday(f, m, day)) behind++;
            int missed = Math.min(behind, b.day == day ? b.missedInRow() : 0);
            int i = 0;
            for (Meals.Meal m : Meals.Meal.values()) {
                CompoundTag c = new CompoundTag();
                c.putString("label", Character.toUpperCase(m.label.charAt(0)) + m.label.substring(1));
                c.putInt("from", m.from);
                c.putInt("to", m.to);
                boolean had = Meals.hadToday(f, m, day);
                // The meals behind it today; the last of them missed, as many as it has missed in a row.
                c.putString("state", !had ? (t >= m.to ? "missed" : "to come") : (i >= behind - missed ? "missed" : "eaten"));
                if (had) i++;
                meals.add(c);
            }
        }
        out.put("meals", meals);
        out.putInt("time", (int) t);
        Kind now = now(f), plan = planned(f, dayTime);
        out.putString("now", now.name());
        out.putString("now_label", now.label);
        out.putString("plan", plan.name());
        out.putString("plan_label", plan.label);
        out.putString("doing", FolkTalk.nowDoing(f));
        out.putString("off_why", f.offWorkWhy());
        out.putInt("bedtime", (int) Math.min(DAY - 1, f.bedtimeTick()));
        out.putBoolean("bell", TownBell.keeps(f.ownerId(), dayTime));
        return out;
    }

    /** "half past seven": a day time as a clock reads it (day time 0 is six in the morning). */
    public static String clock(long dayTime) {
        long t = Math.floorMod(dayTime, (long) DAY);
        int minutes = (int) ((t * 60L / 1000L + 6 * 60) % (24 * 60));
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
