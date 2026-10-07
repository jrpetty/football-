package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Friendly matches between towns [batchC]. Two towns on good terms (Diplomacy: friendly or better) and near
 * enough for their caravans to run between them now and then play each other at football, on the day of rest
 * of the one with the pitch.
 *
 * <p>The visitors' side, up to five of whoever is free to play (Football.canPlay), sets out from home in the
 * morning, after the morning assembly, and walks there, really walks, by the road a caravan would take (out
 * along an avenue, down the road, in along the other town's avenue) to the side of the host's pitch, and waits
 * for the match. Its folk are given the day off for it. The host turns out as many as came, and the match is
 * played as any other on that pitch (Football), but between the two towns and not in either's league. The
 * result goes into both towns' chronicles and books, and a good afternoon's football warms the two towns to
 * each other a little (a thrashing rather less). Then the visitors walk home.
 *
 * <p>Robust to the road: a walker that gets no nearer its next step for half a minute is set down at it, as a
 * caravan's carrier is; if the side has not got there by early afternoon (two of them at least),
 * or nobody in the host town comes out to play, the match is called off, written into both chronicles, and
 * the visitors turn for home at once. A walker caught away from home by a restart walks home from wherever it
 * is (it carries the mark of where home is).
 */
public final class Friendlies {

    private Friendlies() {}

    /** Set out between these hours of the host's rest day (after the morning assembly). */
    static final long SET_OUT_FROM = 1500L, SET_OUT_TILL = 3200L;
    /** Two of the side there by now, or the match is off. */
    static final long ARRIVE_BY = 8200L;
    /** Days between one town's away days, and between its home friendlies. */
    static final int AWAY_EVERY = 10, HOST_EVERY = 6;
    /** The mark a walker carries of the town it walked from. */
    static final String AWAY = "mca_sport_away";

    /** One walker's way, and how far along it is. */
    static final class Walk {
        final List<BlockPos> way = new ArrayList<>();
        int at;
        double best = Double.MAX_VALUE;
        int gainedTick, walkTick = -1000;
        @Nullable BlockPos window;
        boolean there, home;
    }

    /** A side on its way to a neighbour's pitch, there, or on its way home. */
    public static final class Tour {
        final UUID from, to;
        final long day;
        final Map<UUID, Walk> walkers = new LinkedHashMap<>();
        boolean back, played;
        @Nullable String outcome;

        Tour(UUID from, UUID to, long day) {
            this.from = from;
            this.to = to;
            this.day = day;
        }
    }

    /** The tour coming to each host. */
    private static final Map<UUID, Tour> BY_HOST = new ConcurrentHashMap<>();
    /** Each walker's tour. */
    private static final Map<UUID, Tour> BY_WALKER = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BY_HOST.clear();
        BY_WALKER.clear();
    }

    static boolean busy(VillageFolkEntity f) {
        return BY_WALKER.containsKey(f.getUUID());
    }

    /** Is a side on its way to play this town (its own league match waits a week)? */
    static boolean expected(UUID host) {
        Tour t = BY_HOST.get(host);
        return t != null && !t.back && !t.played;
    }

    @Nullable
    static Tour coming(UUID host) {
        return BY_HOST.get(host);
    }

    // ------------------------------------------------------------------ arranging it

    /**
     * Every so often in the morning (Sport's programme), for a town that could send a side: a neighbour on good
     * terms, near enough, with a pitch and its rest day today, that has not had visitors lately; now and then
     * (one morning in three that it could) the side sets out. Returns the host, or null.
     */
    @Nullable
    static Villages.Village consider(ServerLevel level, Villages.Village v, long day) {
        long t = Math.floorMod(level.getDayTime(), 24000L);
        if (t < SET_OUT_FROM || t > SET_OUT_TILL) return null;
        if (day - League.lastFriendly(v.id(), false) < AWAY_EVERY) return null;
        for (Tour x : BY_HOST.values()) if (x.from.equals(v.id())) return null;
        if (Raids.underAlarm(v.id()) || Weather.stormy(level)) return null;
        for (Villages.Village h : Villages.every()) {
            if (h.id().equals(v.id()) || !h.dim().equals(v.dim()) || !Diplomacy.neighbours(v, h)) continue;
            if (Diplomacy.apart(v, h) > Diplomacy.NEAR) continue;                         // a caravan's day out, no more
            if (Ledger.relation(v.id(), h.id()) < Diplomacy.FRIENDLY) continue;
            if (Pitch.of(h.id()) == null || !RestDay.today(h.id(), day) || BY_HOST.containsKey(h.id())) continue;
            if (day - League.lastFriendly(h.id(), true) < HOST_EVERY) continue;
            if (Math.floorMod((int) day * 31 + v.id().hashCode() + h.id().hashCode(), 3) != 0) continue;   // now and then
            if (setOut(level, v, h, day) != null) return h;
        }
        return null;
    }

    /** A side picked and set on the road to the host's pitch; the tour, or null if the town cannot raise three. */
    @Nullable
    static Tour setOut(ServerLevel level, Villages.Village v, Villages.Village h, long day) {
        Ledger.Building pitch = Pitch.of(h.id());
        if (pitch == null) return null;
        List<VillageFolkEntity> free = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && Football.canPlay(f) && f.distanceToSqr(v.centre().getX(), f.getY(), v.centre().getZ()) < 110.0 * 110.0) {
                free.add(f);
            }
        }
        if (free.size() < Football.LEAST + 1) return null;
        List<VillageFolkEntity> side = Football.pick(free, day, Football.MOST);
        Tour t = new Tour(v.id(), h.id(), day);
        List<BlockPos> way = new ArrayList<>(Caravans.way(v, h));
        int i = 0;
        for (VillageFolkEntity f : side) {
            Walk w = new Walk();
            w.way.addAll(way);
            // The last step: the away bench, down the far side of the host's pitch.
            Vec3 bench = Pitch.point(pitch, -(Pitch.HALF_WIDE + 1.0), -2.0 + i * 1.0);
            w.way.add(BlockPos.containing(bench));
            w.gainedTick = f.tickCount;
            t.walkers.put(f.getUUID(), w);
            BY_WALKER.put(f.getUUID(), t);
            f.clearQueue();
            f.getNavigation().stop();
            f.getPersistentData().putUUID(AWAY, v.id());
            i++;
        }
        BY_HOST.put(h.id(), t);
        League.friendly(v.id(), day, "a side of " + side.size() + " set out to play " + Villages.name(h.id()), false);
        Villages.tell(v.id(), day, "a football side of " + side.size() + " set out for " + Villages.name(h.id()) + ", to play them on their pitch");
        Villages.tell(h.id(), day, "a football side from " + Villages.name(v.id()) + " is on its way to play us");
        FolkTalk.speak(side.get(0), FolkTalk.pick(level.getRandom(), "Off to " + Villages.name(h.id()) + " for the football!",
            "We'll show " + Villages.name(h.id()) + " how it's done.", "Come on, lads, it's a long walk."));
        return t;
    }

    // ------------------------------------------------------------------ the host's afternoon

    /**
     * Every second on the host's rest day (Sport's programme): the visitors there, the match begun with as many of
     * the host's as came; too late and not there, or nobody to play them, the match called off.
     */
    static void host(ServerLevel level, Villages.Village h, long day) {
        Tour t = BY_HOST.get(h.id());
        if (t == null || t.back || t.played || Football.on(h.id())) return;
        long time = Math.floorMod(level.getDayTime(), 24000L);
        int there = 0;
        for (Walk w : t.walkers.values()) if (w.there) there++;
        boolean all = there == t.walkers.size();
        if (time >= ARRIVE_BY && there < Football.LEAST) {
            calledOff(level, t, Villages.name(t.from) + "'s side never got here");
            return;
        }
        if (time < Football.FROM || !(all || there >= Football.LEAST && time >= Football.FROM + 600)) return;
        startMatch(level, h, t);
    }

    /** The visitors there: the host turns out as many as came (two at least), and the match is on. */
    static boolean startMatch(ServerLevel level, Villages.Village h, Tour t) {
        Ledger.Building pitch = Pitch.of(h.id());
        if (pitch == null) { calledOff(level, t, Villages.name(h.id()) + " has no pitch"); return false; }
        List<UUID> away = new ArrayList<>();
        for (Map.Entry<UUID, Walk> e : t.walkers.entrySet()) {
            if (e.getValue().there && Football.live(level, e.getKey()) != null) away.add(e.getKey());
        }
        List<VillageFolkEntity> free = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(h.id())) {
            if (a instanceof VillageFolkEntity f && Football.canPlay(f) && f.distanceToSqr(Pitch.point(pitch, 0, 0)) < 120.0 * 120.0) free.add(f);
        }
        long day = level.getDayTime() / 24000L;
        List<VillageFolkEntity> home = Football.pick(free, day, Math.max(Football.LEAST, Math.min(Football.MOST, away.size())));
        if (home.size() < Football.LEAST) {
            calledOff(level, t, "nobody in " + Villages.name(h.id()) + " came out to play");
            return false;
        }
        Football.Side hs = new Football.Side(Villages.name(h.id()), h.id()), as = new Football.Side(Villages.name(t.from), t.from);
        for (VillageFolkEntity f : home) hs.players.add(f.getUUID());
        as.players.addAll(away);
        if (Football.start(level, h, pitch, hs, as, false, t) == null) {
            calledOff(level, t, "there was no ball to play with");
            return false;
        }
        return true;
    }

    /** The match over (Football): into both chronicles and books, the towns the warmer for it, the side for home. */
    static void over(ServerLevel level, Tour t, int hostGoals, int awayGoals, String line) {
        t.played = true;
        long day = level.getDayTime() / 24000L;
        String host = Villages.name(t.to), visitors = Villages.name(t.from);
        Villages.tell(t.to, day, line + ", a friendly on our pitch against " + visitors);
        Villages.tell(t.from, day, line + ", a friendly away at " + host);
        League.friendly(t.to, day, line + " (at home)", true);
        League.friendly(t.from, day, line + " (away at " + host + ")", false);
        int warmth = Math.abs(hostGoals - awayGoals) >= 4 ? 1 : 3;          // a thrashing rankles a little
        Ledger.relate(t.from, t.to, warmth);
        t.outcome = line;
        turnHome(level, t);
    }

    /** The match off: into both chronicles, and the side turns for home (or never sets out). */
    static void calledOff(ServerLevel level, Tour t, String why) {
        long day = level.getDayTime() / 24000L;
        String line = "the football between " + Villages.name(t.from) + " and " + Villages.name(t.to) + " was called off: " + why;
        Villages.tell(t.from, day, line);
        Villages.tell(t.to, day, line);
        t.outcome = line;
        turnHome(level, t);
    }

    /**
     * Once a day (Sport): a tour two days old is forgotten, whatever became of its walkers (dead, or in ground
     * nobody has been near since); any of them still away walks home by the mark it carries (rehome).
     */
    static void sweep(long day) {
        for (Tour t : new ArrayList<>(BY_HOST.values())) {
            if (day - t.day < 2) continue;
            BY_HOST.remove(t.to, t);
            for (UUID u : t.walkers.keySet()) BY_WALKER.remove(u, t);
        }
    }

    /** Every walker turned round: back the way it came, from wherever it has got to. */
    static void turnHome(ServerLevel level, Tour t) {
        t.back = true;
        Villages.Village home = Villages.get(t.from);
        for (Map.Entry<UUID, Walk> e : t.walkers.entrySet()) {
            Walk w = e.getValue();
            List<BlockPos> back = new ArrayList<>();
            for (int i = Math.min(w.at, w.way.size()) - 1; i >= 0; i--) back.add(w.way.get(i));
            if (home != null) back.add(home.centre());
            w.way.clear();
            w.way.addAll(back);
            w.at = 0;
            w.best = Double.MAX_VALUE;
            w.there = false;
            VillageFolkEntity f = Football.live(level, e.getKey());
            if (f != null) w.gainedTick = f.tickCount;
        }
    }

    // ------------------------------------------------------------------ on the road

    /** A walker of a tour, or a folk carrying the mark of an away day a restart cut short: on its way. */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Tour t = BY_WALKER.get(f.getUUID());
        if (t == null) {
            if (f.tickCount % 40 == 0 && f.getPersistentData().hasUUID(AWAY)) rehome(f, level);
            return false;
        }
        Walk w = t.walkers.get(f.getUUID());
        if (w == null) { BY_WALKER.remove(f.getUUID()); return false; }
        if (f.getTarget() != null || f.isSleeping()) return false;
        String host = Villages.name(t.to), home = Villages.name(t.from);
        keepAwake(level, f, w);
        if (!t.back && w.there) {
            // At the side of the host's pitch, waiting for the match (Football has it once it begins). Given up on
            // if the host's afternoon never comes round (its town out of sight, nobody to start it).
            f.hobbyNow = "waiting by the pitch at " + host + " to play football";
            f.lastLeisureTick = f.tickCount;
            long time = Math.floorMod(level.getDayTime(), 24000L);
            if (time >= ARRIVE_BY + 1200 || level.getDayTime() / 24000L > t.day) calledOff(level, t, "nobody came out to meet them");
            else f.getNavigation().stop();
            return true;
        }
        f.hobbyNow = t.back ? "walking home to " + home + " from the football at " + host : "on the road to " + host + " to play football";
        f.lastLeisureTick = f.tickCount;
        if (w.at >= w.way.size()) {
            if (t.back) { done(level, f, t, w); return false; }
            w.there = true;
            f.getNavigation().stop();
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Here we are. Nice pitch.", "Made it! Where are they, then?", "So this is " + host + "."));
            return true;
        }
        BlockPos step = w.way.get(w.at);
        BlockPos ground = surface(level, step);
        double dx = f.getX() - (ground.getX() + 0.5), dz = f.getZ() - (ground.getZ() + 0.5);
        double d = dx * dx + dz * dz;
        boolean last = w.at == w.way.size() - 1;
        if (d <= (last ? 2.5 : 9.0)) {
            w.at++;
            w.best = Double.MAX_VALUE;
            w.gainedTick = f.tickCount;
            return true;
        }
        if (d < w.best - 1.0) { w.best = d; w.gainedTick = f.tickCount; }
        if (f.getNavigation().isDone() || f.tickCount - w.walkTick > 100) {
            f.walkTo(ground, 0.9D);
            w.walkTick = f.tickCount;
        }
        // No nearer for half a minute (a river with no bridge, a cliff): set down at the next step, as the caravans are.
        if (f.tickCount - w.gainedTick > 600) {
            f.moveTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, f.getYRot(), 0.0F);
            w.gainedTick = f.tickCount;
            w.best = Double.MAX_VALUE;
        }
        return true;
    }

    /** Home again: the mark off, the ground let go, back to its own day. */
    static void done(ServerLevel level, VillageFolkEntity f, Tour t, Walk w) {
        w.home = true;
        release(level, f, w);
        BY_WALKER.remove(f.getUUID());
        f.getPersistentData().remove(AWAY);
        boolean all = true;
        for (Walk o : t.walkers.values()) if (!o.home) all = false;
        if (all) BY_HOST.remove(t.to, t);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Home at last. My feet!", "Back again. What a day.", "Good to be home."));
    }

    /** A folk away from home with no tour (a restart on the road): walks home from where it is. */
    static void rehome(VillageFolkEntity f, ServerLevel level) {
        UUID home = f.getPersistentData().getUUID(AWAY);
        Villages.Village v = Villages.get(home);
        if (v == null || !home.equals(f.ownerId()) || f.distanceToSqr(v.centre().getX(), f.getY(), v.centre().getZ()) < 48.0 * 48.0) {
            f.getPersistentData().remove(AWAY);
            return;
        }
        Tour t = new Tour(home, home, level.getDayTime() / 24000L);
        t.back = true;
        Walk w = new Walk();
        BlockPos from = f.blockPosition(), to = v.centre();
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(from.distSqr(to)) / 10.0));
        for (int i = 1; i <= steps; i++) {
            w.way.add(new BlockPos(from.getX() + (to.getX() - from.getX()) * i / steps, from.getY(), from.getZ() + (to.getZ() - from.getZ()) * i / steps));
        }
        w.gainedTick = f.tickCount;
        t.walkers.put(f.getUUID(), w);
        BY_WALKER.put(f.getUUID(), t);
    }

    private static BlockPos surface(ServerLevel level, BlockPos p) {
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return p;
        return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    private static UUID owner(VillageFolkEntity f) {
        return UUID.nameUUIDFromBytes(("mca-sport-" + f.getUUID()).getBytes());
    }

    /** The ground round a walker kept awake as it goes, as a caravan's is. */
    private static void keepAwake(ServerLevel level, VillageFolkEntity f, Walk w) {
        BlockPos here = f.blockPosition();
        if (w.window != null && w.window.distSqr(here) < 16 * 16) return;
        if (w.window != null) ChunkLoad.setLoaded(level, owner(f), w.window, 1, false);
        ChunkLoad.setLoaded(level, owner(f), here, 1, true);
        w.window = here.immutable();
    }

    private static void release(ServerLevel level, VillageFolkEntity f, Walk w) {
        if (w.window != null) ChunkLoad.setLoaded(level, owner(f), w.window, 1, false);
        w.window = null;
    }

    // ------------------------------------------------------------------ where the player sees it

    /** The board's word on a friendly coming or going, or null. */
    @Nullable
    static String line(UUID village) {
        for (Tour t : BY_HOST.values()) {
            if (t.to.equals(village) && !t.back) return "a football side from " + Villages.name(t.from) + " is coming to play us";
            if (t.from.equals(village) && !t.back) return "our football side is away at " + Villages.name(t.to);
            if (t.from.equals(village) && t.back) return "our football side is on its way home from " + Villages.name(t.to);
        }
        return null;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a side sent from one town to the other's pitch now, whatever the day. */
    @Nullable
    public static Tour sendForTests(ServerLevel level, Villages.Village from, Villages.Village to) {
        return setOut(level, from, to, level.getDayTime() / 24000L);
    }

    /** Tests: {walkers, there, home, back (1/0), played (1/0)}. */
    public static int[] stateForTests(Tour t) {
        int there = 0, home = 0;
        for (Walk w : t.walkers.values()) { if (w.there) there++; if (w.home) home++; }
        return new int[]{ t.walkers.size(), there, home, t.back ? 1 : 0, t.played ? 1 : 0 };
    }

    /** Tests: the host's afternoon now (the match begun if the side is there). */
    public static boolean startForTests(ServerLevel level, Villages.Village host, Tour t) {
        return startMatch(level, host, t);
    }

    /** Tests: the match called off now. */
    public static void callOffForTests(ServerLevel level, Tour t, String why) {
        calledOff(level, t, why);
    }

    @Nullable
    public static String outcomeForTests(Tour t) {
        return t.outcome;
    }
}
