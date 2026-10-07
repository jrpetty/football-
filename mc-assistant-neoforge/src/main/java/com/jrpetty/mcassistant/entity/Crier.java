package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town crier. [townlife]
 *
 * <p>At the noon bell (or at noon by the clock, in a town that keeps no bell that day) one folk goes
 * to the square and reads out the day's news, a line every few seconds, for whoever is about to hear
 * it: what happened yesterday and this morning, out of the town's own chronicle (who was born, who
 * died, what went up), the elder's order, when market day is, and what the town is short of. The
 * crier is the elder's pick — whoever the leader thinks best of among those free to go — or, with
 * nobody leading, the most sociable folk to hand. Never the leader itself (it has its own day to see
 * to), never the one ringing the bell, never a child.
 *
 * <p>Its midday meal waits till it is done (TownBell answers a folk's meal for a good while after
 * the bell), and its own work waits with it (TownCalendar.busy). Once a day: a town that misses its
 * noon — nobody free, or the hour long gone — goes without its news till tomorrow.
 */
public final class Crier {

    private Crier() {}

    /** Between lines: long enough for a bubble to be read, short enough to keep a crowd. */
    static final long LINE = 90L;
    /** How long it has to get to the square before it reads where it stands. */
    static final long WALK = 1200L;
    /** A crier not heard from in this long (called away, stuck, gone) is replaced. */
    static final long LOST = 400L;
    /** The latest in the day the news is begun: after that it waits for tomorrow. */
    static final long LATEST = 9000L;
    /** How many of the chronicle's lines it reads at most. */
    static final int NEWS = 6;

    /** One day's crying of the news in one town. */
    static final class Cry {
        final long day;
        final List<String> lines;
        final List<String> read = new ArrayList<>();
        @Nullable UUID crier;
        String crierName = "";
        BlockPos stand;
        /** When the news was begun, when this crier was sent, its last step, its next line. */
        long startedAt, sentAt, lastStep, nextAt, closeSince = -1;
        int next, walkTick = -1000;
        boolean arrived, done;

        Cry(long day, List<String> lines, BlockPos stand, long now) {
            this.day = day;
            this.lines = lines;
            this.stand = stand;
            this.startedAt = now;
            this.lastStep = now;
        }
    }

    private static final Map<UUID, Cry> CRIES = new ConcurrentHashMap<>();
    /** The day each town last had (or let go) its news. */
    private static final Map<UUID, Long> CRIED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CRIES.clear();
        CRIED.clear();
    }

    // ------------------------------------------------------------------ the town's part

    /** The town's look at its news, every second (TownCalendar's server tick). */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = Math.floorDiv(dt, 24000L), t = Math.floorMod(dt, 24000L), now = level.getGameTime();
        Cry c = CRIES.get(id);
        if (c != null && c.day != day) {
            CRIES.remove(id, c);
            c = null;
        }
        if (c != null) {
            if (c.done) return;
            VillageFolkEntity f = c.crier == null ? null : live(level, c.crier);
            boolean lost = f == null || !fit(f, id) && !c.arrived || now - c.lastStep > LOST;
            if (lost) {
                // Called away before it got there, or stuck: somebody else takes the news over.
                VillageFolkEntity next = choose(level, v, c.crier);
                if (next == null || now - c.startedAt > WALK * 2) {
                    c.done = true;
                    return;
                }
                appoint(c, next, now);
            }
            return;
        }
        Long had = CRIED.get(id);
        if (had != null && had == day) return;
        if (Villages.headcount(id) < 3) return;              // a camp of two tells itself its news
        if (!noon(id, dt)) return;
        if (t > LATEST) {
            CRIED.put(id, day);
            return;
        }
        begin(level, v, day, now);
    }

    /** Is it noon in this town: its noon bell rung today, or, with no bell to wait for, the clock past twelve? */
    static boolean noon(UUID village, long dayTime) {
        if (TownBell.rung(village, TownBell.Peal.NOON, dayTime) != null) return true;
        long t = Math.floorMod(dayTime, 24000L);
        return t >= 6000L && t < 12000L && !TownBell.lunchWaits(village, dayTime);
    }

    /** The news made up and a crier sent to the square. Null if nobody could go. */
    @Nullable
    static Cry begin(ServerLevel level, Villages.Village v, long day, long now) {
        VillageFolkEntity f = choose(level, v, null);
        if (f == null) return null;
        Cry c = new Cry(day, script(level, v, day, f.getRandom()), TownBell.crierSpot(v), now);
        appoint(c, f, now);
        CRIES.put(v.id(), c);
        CRIED.put(v.id(), day);
        return c;
    }

    private static void appoint(Cry c, VillageFolkEntity f, long now) {
        c.crier = f.getUUID();
        c.crierName = f.displayNameCap();
        c.sentAt = now;
        c.lastStep = now;
        c.walkTick = -1000;
        c.closeSince = -1;
        c.arrived = false;                                    // a crier taking over walks to the square too
        f.clearQueue();                                       // a walk in for food queued: after the news
        f.getNavigation().stop();
        f.brain("to cry the news on the square");
    }

    @Nullable
    private static VillageFolkEntity live(ServerLevel level, UUID id) {
        return level.getEntity(id) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    /** Free to go and cry the news: as free as a bell-ringer must be, and not ringing the bell itself. */
    static boolean fit(VillageFolkEntity f, UUID village) {
        if (!TownBell.fitToRing(f, TownBell.Peal.NOON, village)) return false;
        if (School.teaching(f) || Patrols.escorting(f)) return false;
        return !f.getUUID().equals(TownBell.ringer(village));
    }

    /**
     * The crier: the elder's pick (whoever the leader thinks best of, a sociable one by preference), or
     * with nobody leading, the most sociable of those free; the nearer the square the better.
     */
    @Nullable
    static VillageFolkEntity choose(ServerLevel level, Villages.Village v, @Nullable UUID not) {
        UUID id = v.id();
        Villages.chooseElder(id, Math.floorDiv(level.getDayTime(), 24000L));   // today's leader known before it picks
        VillageFolkEntity elder = Orders.elderOf(id);
        BlockPos square = TownBell.crierSpot(v);
        VillageFolkEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isElder() || f.getUUID().equals(not) || !fit(f, id)) continue;
            Social.Life life = f.life();
            double score = 0;
            if (elder != null && elder != f) score += elder.life().affinity(f.getUUID());
            if (life.has(Social.Trait.SOCIABLE)) score += 40;
            if (life.has(Social.Trait.CHEERFUL)) score += 15;
            if (life.has(Social.Trait.SHY)) score -= 40;
            if (life.has(Social.Trait.GRUMPY)) score -= 15;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) score -= 30;     // the watch has its own rounds
            score -= Math.sqrt(f.blockPosition().distSqr(square)) / 4.0;
            if (score > bestScore) { bestScore = score; best = f; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the news, in words

    /**
     * What it reads out: the call, the chronicle's lines since yesterday (the latest last, as they
     * happened), the elder's order, market day and what the town is short of, and the sign-off.
     */
    static List<String> script(ServerLevel level, Villages.Village v, long day, RandomSource r) {
        UUID id = v.id();
        String town = Villages.name(id);
        List<String> out = new ArrayList<>();
        out.add(FolkTalk.pick(r, "Oyez, oyez, oyez! Hear the news of " + town + "!", "Hear ye, hear ye! The news of " + town + "!",
            "Gather round, all! Here's the news of " + town + "!"));
        List<String> yesterday = new ArrayList<>(), today = new ArrayList<>();
        for (Chronicle.Entry e : Chronicle.of(id)) {
            if (e.day() == day - 1) yesterday.add("Yesterday: " + capital(e.text()));
            else if (e.day() == day) today.add("Today: " + capital(e.text()));
        }
        if (yesterday.isEmpty() && today.isEmpty()) {
            out.add(FolkTalk.pick(r, "A quiet day: nothing to tell since yesterday, and long may it last.",
                "Nothing new since yesterday — a quiet town is a happy town."));
        } else {
            // Yesterday's first, as it happened (the latest four of it at most), then this morning's in what room is left.
            List<String> y = yesterday.subList(Math.max(0, yesterday.size() - 4), yesterday.size());
            int room = NEWS - y.size();
            out.addAll(y);
            out.addAll(today.subList(Math.max(0, today.size() - room), today.size()));
        }
        Orders.Given g = Orders.given(id);
        if (g != null) {
            out.add("By order of " + (g.by().isEmpty() ? "the elder" : g.by()) + ": " + g.order().title + "! " + g.order().words);
        }
        int toMarket = Market.daysToMarket(id, day);
        out.add(toMarket == 0 ? "It's market day — the stalls are open on the square!"
            : toMarket == 1 ? "Market day tomorrow!" : "Market day in " + toMarket + " days.");
        List<Villages.Need> needs = Villages.needs(level, id);
        if (!needs.isEmpty()) {
            StringBuilder sb = new StringBuilder("The town is short of ");
            for (int i = 0; i < Math.min(2, needs.size()); i++) sb.append(i == 0 ? "" : " and ").append(needs.get(i).what());
            out.add(sb.append(" — any help is welcome.").toString());
        }
        out.addAll(Traditions.crierLines(level, v, day));          // [batchD] a custom kept today or tomorrow, the motto on a feast day
        out.add(FolkTalk.pick(r, "That's the news! Long live " + town + "!", "That's all. Back to your dinners!",
            "That's the news. God keep " + town + "!"));
        return out;
    }

    private static String capital(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + (s.endsWith(".") || s.endsWith("!") ? "" : ".");
    }

    // ------------------------------------------------------------------ the crier's part

    /** The crier's step, from its own tick (TownCalendar.hold): to the square, then a line at a time. True while it is about it. */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Cry c = id == null ? null : CRIES.get(id);
        if (c == null || c.done || !f.getUUID().equals(c.crier)) return false;
        long now = level.getGameTime();
        if (!c.arrived && !fit(f, id)) return false;          // the town's look finds it another crier
        c.lastStep = now;
        f.lastLeisureTick = f.tickCount;
        if (!c.arrived) {
            if (there(f, c, now) || now - c.sentAt > WALK) {
                c.arrived = true;
                c.nextAt = now;
                f.getNavigation().stop();
            } else {
                if (f.getNavigation().isDone() || f.tickCount - c.walkTick > 60) {
                    f.walkTo(c.stand, 1.0D);
                    c.walkTick = f.tickCount;
                }
                f.hobbyNow = "on the way to the square to cry the news";
                return true;
            }
        }
        f.getNavigation().stop();
        f.hobbyNow = "crying the news on the square";
        // To whoever is listening: the nearest player, else the square.
        Player listener = level.getNearestPlayer(f, 16.0);
        if (listener != null && !listener.isSpectator()) f.getLookControl().setLookAt(listener, 30.0F, 30.0F);
        else {
            Villages.Village v = Villages.get(id);
            BlockPos look = v != null ? v.centre() : c.stand;
            f.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 1.5, look.getZ() + 0.5);
        }
        if (now < c.nextAt) return true;
        String line = c.lines.get(c.next++);
        if (c.next == 1 || c.next == c.lines.size()) f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);  // the arm up to call
        FolkTalk.speak(f, line);
        c.read.add(line);
        c.nextAt = now + LINE;
        if (c.next >= c.lines.size()) {
            c.done = true;
            f.brain("cried the news on the square");
            if (level.getRandom().nextInt(3) == 0) f.persona().remember(c.day, "I cried the news on the square", 1);
        }
        return true;
    }

    /** At the square: by the spot, or as near as the crowd lets it get. */
    private static boolean there(VillageFolkEntity f, Cry c, long now) {
        double dx = f.getX() - (c.stand.getX() + 0.5), dz = f.getZ() - (c.stand.getZ() + 0.5);
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat <= 2.5 && Math.abs(f.getY() - c.stand.getY()) <= 3.0) return true;
        if (flat <= 6.0 && f.getNavigation().isDone()) {
            if (c.closeSince < 0) c.closeSince = now;
            return now - c.closeSince > 100;
        }
        c.closeSince = -1;
        return false;
    }

    /** Is this folk crying the news just now (its meal and its work wait)? */
    static boolean busy(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Cry c = id == null ? null : CRIES.get(id);
        return c != null && !c.done && f.getUUID().equals(c.crier);
    }

    // ------------------------------------------------------------------ what the player and the tests see

    /** Who is crying the news in this town today (or cried it), or null. */
    @Nullable
    public static UUID crier(UUID village) {
        Cry c = CRIES.get(village);
        return c == null ? null : c.crier;
    }

    /** For the tests: the lines read out so far today, in order. */
    public static List<String> readForTests(UUID village) {
        Cry c = CRIES.get(village);
        return c == null ? List.of() : new ArrayList<>(c.read);
    }

    /** For the tests: everything it is to read out today, in order. */
    public static List<String> linesForTests(UUID village) {
        Cry c = CRIES.get(village);
        return c == null ? List.of() : new ArrayList<>(c.lines);
    }

    /** For the tests: where the crier stands to read. Null before the news is begun. */
    @Nullable
    public static BlockPos standForTests(UUID village) {
        Cry c = CRIES.get(village);
        return c == null ? null : c.stand;
    }

    /** For the tests: the news begun now, whatever the hour (a crier chosen and sent). True if one went. */
    public static boolean beginForTests(ServerLevel level, Villages.Village v) {
        CRIES.remove(v.id());
        CRIED.remove(v.id());
        return begin(level, v, Math.floorDiv(level.getDayTime(), 24000L), level.getGameTime()) != null;
    }
}
