package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The day's work put away, twice a day: at midday, and at the end of the shift before home and bed.
 *
 * <p>A hand banked what it had made only when its pack was full or the load was worth the walk, so a
 * village went to bed with the day's harvest, ore and timber on its backs: the hundred days' farmers
 * slept on a hundred and thirty meals apiece while the stores held thirteen, and a woodcutter carried
 * three hundred things about for days. Now every working hand (the fields, the woods, the mines, the
 * waters, the pen, the hives, the hunt, the watch, the smeltery and the couriers between runs) puts
 * away what it has collected twice a day, full pack or not:
 * <ul>
 * <li><b>At midday</b> (the noon bell, or from the midday meal's hour: before it sits down to eat), into
 *     its own work chest at its plot, a few steps off, or the stores if they are as near. A hand far
 *     out with neither near keeps it till the evening: nobody walks to town at noon to bank.</li>
 * <li><b>At the end of its shift</b> (the dusk bell, or nightfall where the town keeps no bell; the
 *     watch at dusk, before it goes on watch), into its work chest, or the stores if it has none with
 *     room, before it goes home to eat and sleep. The bell's "home" waits for it.</li>
 * </ul>
 * What it keeps is what a deposit always kept (AssistantEntity.depositReserve): its tools, weapons and
 * armour, its trade's kit and working stock (a farmer's seed, at most thirty-two of a crop; a miner's
 * torches and bridging stone), a day's rations, and a builder's materials for the building it is
 * leading. A courier on a run banks when the run is done; a folk on a caravan, an expedition, the road
 * to another town or through the gateway, or out after a wild animal, is let be. The crafts and the
 * storekeeper work out of the stores and into them already.
 *
 * <p>The card says "Putting the day's work away" while it is about it; the town's books and the Stores
 * page say how many hands banked at noon and at dusk; /village economy says it too.
 */
public final class PutAway {

    private PutAway() {}

    /** The two times of the day the work is put away. */
    public enum When { NOON, DUSK }

    /** The midday hours (the midday meal's, Meals, and a little after), in the time of day. */
    static final long NOON_FROM = 5400L, NOON_TO = 9000L;
    /** The end of the shift: from the evening's hours to before dawn. */
    static final long DUSK_FROM = 11000L, NIGHT_ENDS = 23000L;
    /** The watch banks at dusk, before it goes on watch, and not all night. */
    static final long WATCH_TO = 14000L;
    /** How far a hand walks to its chest at midday, at most. */
    public static final int NOON_REACH = 40;

    /** The trades that collect: the producers, the watch, the smeltery, the couriers (between runs). */
    static final EnumSet<StationTask> COLLECT = EnumSet.of(StationTask.FARM, StationTask.WOOD, StationTask.MINE,
        StationTask.FISH, StationTask.RANCH, StationTask.HUNT, StationTask.BEEKEEP, StationTask.GUARD,
        StationTask.SMELT, StationTask.HAUL);

    /** One folk's put-aways: the day it last looked at noon and at dusk, and the one it has going. */
    private static final class Mine {
        long noonDay = -1, duskDay = -1;
        @Nullable When pending;
        long pendingDay;
        int sentTick;
        int tries;
    }

    /** A village's day: hands due to bank, and hands that did, at noon and at dusk. */
    static final class Tally {
        final long day;
        int noonDue, noonDone, duskDue, duskDone;

        Tally(long day) { this.day = day; }
    }

    private static final Map<UUID, Mine> FOLK = new ConcurrentHashMap<>();
    private static final Map<UUID, Tally> TODAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Tally> YESTERDAY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        FOLK.clear();
        TODAY.clear();
        YESTERDAY.clear();
    }

    // ------------------------------------------------------------------ when, and who

    /** Which put-away it is time for, for this folk now, or null. */
    @Nullable
    public static When window(VillageFolkEntity f) {
        long tod = Math.floorMod(f.level().getDayTime(), 24000L);
        if (tod >= NOON_FROM && tod < NOON_TO) return When.NOON;
        if (f.stationTask() == StationTask.GUARD) return tod >= DUSK_FROM && tod < WATCH_TO ? When.DUSK : null;
        if (tod >= DUSK_FROM && tod < NIGHT_ENDS && !f.onShift()) return When.DUSK;
        return null;
    }

    /** Does this folk put its work away at all (a grown hand of a trade that collects, at home)? */
    public static boolean takesPart(VillageFolkEntity f) {
        return f.isAlive() && !f.isBaby() && f.ownerId() != null && !f.showcaseFolk() && !f.isHired()
            && COLLECT.contains(f.stationTask());
    }

    /** Why it is let be just now, or null: away from the village, or a courier on a run. */
    @Nullable
    public static String excused(VillageFolkEntity f) {
        if (f.trip() != null) return "on the road with a caravan";
        if (f.expedition() != null) return "out scouting";
        if (Nether.away(f)) return "through the gateway";
        if (Drover.busy(f)) return "out with a lead after a wild animal";
        if (JobSeekers.busy(f)) return "about the job market";
        if (f.stationTask() == StationTask.HAUL && Couriers.onARun(f)) return "on a run (it banks when the run is done)";
        if (Raids.underAlarm(f.ownerId())) return "the alarm bell";
        return null;
    }

    // ------------------------------------------------------------------ the look

    /**
     * The look, from the folk's own round (VillageFolkEntity.agenda, at work and off it) and from the noon
     * and dusk bells (TownBell.hold, before the meal and before home): if it is time, and it has collected
     * anything past what it keeps, it sets off to put it away. True if it set off now (the bell's meal or
     * walk home waits for it: its queue is not empty).
     */
    public static boolean look(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || !takesPart(f)) return false;
        UUID village = f.ownerId();
        long dt = level.getDayTime(), day = Math.floorDiv(dt, 24000L);
        Mine m = FOLK.computeIfAbsent(f.getUUID(), k -> new Mine());
        settle(f, m, village, day);
        When w = window(f);
        if (w == null || f.isSleeping() || excused(f) != null) return false;
        long looked = w == When.NOON ? m.noonDay : m.duskDay;
        if (looked == day) return resend(f, m, w, day);
        if (w == When.NOON) m.noonDay = day; else m.duskDay = day;
        int n = f.stashable();
        if (n <= 0) return false;                                       // nothing collected: nothing to put away
        Tally t = tally(village, day);
        if (w == When.NOON) t.noonDue++; else t.duskDue++;
        m.pending = w;
        m.pendingDay = day;
        m.tries = 0;
        return send(f, level, m, w, n);
    }

    /** The put-away it set going was lost (its queue cleared at bedtime behind a day's job): once more. */
    private static boolean resend(VillageFolkEntity f, Mine m, When w, long day) {
        if (m.pending != w || m.pendingDay != day || m.tries >= 2) return false;
        for (Job j : f.queuedJobs()) if (j.putAway()) return false;   // still going
        int n = f.stashable();
        if (n <= 0) return false;
        return f.level() instanceof ServerLevel level && send(f, level, m, w, n);
    }

    private static boolean send(VillageFolkEntity f, ServerLevel level, Mine m, When w, int n) {
        m.tries++;
        BlockPos at = chestFor(f, w);
        if (at == null && w == When.NOON) {
            m.pending = null;                                         // due, and not done: it keeps it till the evening
            f.brain("nothing to put the morning's work in near enough: it keeps it till the evening");
            return false;
        }
        f.enqueue(Job.putAway(at));
        m.sentTick = f.tickCount;
        f.brain("putting the day's work away: " + n + " things" + (at == null ? "" : " into " + where(f, at)));
        return true;
    }

    /**
     * Where it puts its work: its own work chest at its plot (if it has room), else the stores; at midday
     * only if that is a few steps off. Null at midday when neither is near, or when there is nowhere at all
     * (then a deposit looks for itself: DepositGoal).
     */
    @Nullable
    static BlockPos chestFor(VillageFolkEntity f, When w) {
        Job out = f.villageDepositJob();
        BlockPos at = out == null ? null : chestOf(out);
        if (w == When.NOON) {
            if (at == null) return null;
            double dx = at.getX() + 0.5 - f.getX(), dz = at.getZ() + 0.5 - f.getZ();
            return dx * dx + dz * dz <= (double) NOON_REACH * NOON_REACH ? at : null;
        }
        return at;
    }

    @Nullable
    private static BlockPos chestOf(Job j) {
        if (j.arg() == null) return null;
        String[] p = j.arg().split(" ");
        if (p.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String where(VillageFolkEntity f, BlockPos at) {
        return at.equals(f.productionChest()) ? "its work chest" : "the stores";
    }

    /** Was the put-away it set going done (it banked since, or carries nothing past its keep)? Booked if so. */
    private static void settle(VillageFolkEntity f, Mine m, UUID village, long day) {
        if (m.pending == null) return;
        if (m.pendingDay < day - 1) {                                   // long gone: let it be
            m.pending = null;
            return;
        }
        boolean banked = f.lastStashTick() >= m.sentTick;
        if (!banked && f.stashable() > 0) return;
        Tally t = m.pendingDay == day ? tally(village, day) : YESTERDAY.get(village);
        if (t != null && t.day == m.pendingDay) {
            if (m.pending == When.NOON) t.noonDone++; else t.duskDone++;
        }
        m.pending = null;
    }

    private static Tally tally(UUID village, long day) {
        Tally t = TODAY.get(village);
        if (t == null || t.day != day) {
            if (t != null && t.day == day - 1) YESTERDAY.put(village, t);
            else if (t != null && t.day < day - 1) YESTERDAY.remove(village);
            t = new Tally(day);
            TODAY.put(village, t);
        }
        return t;
    }

    // ------------------------------------------------------------------ what the player reads

    /** {noon due, noon done, dusk due, dusk done} for today ({@code today}) or yesterday, or null. */
    @Nullable
    public static int[] counts(UUID village, long today, boolean yesterday) {
        Tally t = yesterday ? YESTERDAY.get(village) : TODAY.get(village);
        Tally cur = TODAY.get(village);
        if (yesterday && cur != null && cur.day == today - 1) t = cur;  // the books are made up before today's first look
        if (t == null || t.day != (yesterday ? today - 1 : today)) return null;
        return new int[]{ t.noonDue, t.noonDone, t.duskDue, t.duskDone };
    }

    private static String words(int[] c) {
        return "at noon " + c[1] + " of " + c[0] + (c[0] == 1 ? " hand" : " hands") + ", at dusk " + c[3] + " of " + c[2];
    }

    /** The town's books (Annals, once a morning): yesterday's put-aways, or null before there were any. */
    @Nullable
    public static String booksLine(UUID village, long today) {
        int[] c = counts(village, today, true);
        if (c == null || c[0] + c[2] == 0) return null;
        boolean all = c[1] >= c[0] && c[3] >= c[2];
        return (all ? "+" : "-") + "Banked yesterday " + words(c) + (all ? ": nobody went to bed with the day's work on its back."
            : ": the rest kept it about them overnight.");
    }

    /** The Stores page and /village economy: today so far, and yesterday. */
    public static String line(UUID village, long today) {
        int[] now = counts(village, today, false), then = counts(village, today, true);
        StringBuilder sb = new StringBuilder();
        if (now != null && now[0] + now[2] > 0) sb.append("today ").append(words(now));
        if (then != null && then[0] + then[2] > 0) sb.append(sb.length() > 0 ? "; " : "").append("yesterday ").append(words(then));
        return sb.length() == 0 ? "nobody has had the day's work to put away yet" : "Banked " + sb;
    }

    /** Tests: is the folk's put-away for this time of day booked as done? */
    public static boolean doneForTests(VillageFolkEntity f, When w) {
        Mine m = FOLK.get(f.getUUID());
        if (m == null || !(f.level() instanceof ServerLevel level)) return false;
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        if (f.ownerId() != null) settle(f, m, f.ownerId(), day);
        long looked = w == When.NOON ? m.noonDay : m.duskDay;
        return looked == day && m.pending == null;
    }
}
