package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Districts;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Football on the day of rest [batchC]. A town with a pitch (Pitch) plays a match on it every rest day, in
 * the early afternoon, after the midday meal.
 *
 * <p><b>The sides.</b> A town's teams are its ends: the side of the square a folk's home is on (the North
 * End, the East End...; one with no bed of its own goes by its name). Each rest day the two ends that have
 * met least this season, and can each turn out two players, play a league match (League), up to five a
 * side, a different five from week to week; with fewer than two ends able to, whoever is free is split evenly
 * into the Greens and the Golds for a match that is just for the fun of it. The watch does not play (it is
 * on duty), nor the old, the children, or anybody away or on the road. With three or more a side, one of
 * them keeps goal.
 *
 * <p><b>The ball</b> is a real one: a slime ball out of the stores, or a scrap of leather if the town has no
 * slime, lying on the grass as any dropped thing lies (nobody picks it up while the match is on), and back in
 * the stores at the final whistle. No ball, no match.
 *
 * <p><b>The match.</b> The players walk out onto the pitch, each to its place in its own half, and the home
 * side kicks off. They run at the ball and kick it, toward the other side's goal, a little this way or that:
 * a pass up the field from far out, a shot from close in, a long clearance from the goalkeeper, who keeps to
 * its goal and follows the ball across it. A goal is the ball over the goal line between the posts and under
 * the bar; the side that let it in kicks off again from the centre spot. A ball over a touchline or a goal
 * line anywhere else is put back on the field where it went off. Two and a half minutes from kick-off the
 * match is over: the result into the chronicle and the board (the goals by who scored them), into the league,
 * and into the players' own memories. The bell or a thunderstorm stops it there and then.
 *
 * <p><b>The crowd.</b> Folk off on their rest day who have nobody to walk out with, the children, and the
 * players' own families come and stand along the sides, on the benches, and cheer their end on.
 */
public final class Football {

    private Football() {}

    /** A match from kick-off to the final whistle: two and a half minutes. */
    static final int LENGTH = 3000;
    /** How long the sides have to come out onto the pitch before the match goes on with whoever is there. */
    static final int GATHER = 700;
    /** Players a side: no more than five, and two at least. */
    public static final int MOST = 5, LEAST = 2;
    /** The rest day's match: the sides gather from one o'clock, the midday meal eaten; none starts after half past two; all are
     *  over by half past five, before the dusk bell. */
    static final long FROM = 7000L, LAST_KICK_OFF = 8500L, CLOSE = 11500L;
    /** A ball this near a player's feet (across the ground) is a ball it can kick. */
    static final double REACH = 1.4;

    /** One side. */
    static final class Side {
        final String name;
        final UUID town;
        final List<UUID> players = new ArrayList<>();
        @Nullable UUID keeper;
        /** +1: it attacks the goal at the back of the lot; -1: the one at the front. */
        int attack;
        int goals;
        final List<String> scorers = new ArrayList<>();

        Side(String name, UUID town) {
            this.name = name;
            this.town = town;
        }

        boolean has(UUID u) { return players.contains(u); }
    }

    static final int GATHERING = 0, PLAYING = 1;

    /** A match on a town's pitch. */
    static final class Match {
        final UUID village;
        final ResourceKey<Level> dim;
        final Ledger.Building pitch;
        final Side home, away;
        /** It counts in the league table. */
        final boolean league;
        /** Two towns' sides (Friendlies): the visitors' tour. */
        @Nullable final Friendlies.Tour tour;
        int stage = GATHERING;
        long gatherFrom, kickOff = -1, endAt;
        @Nullable UUID ball;
        ItemStack ballStack = ItemStack.EMPTY;
        long pauseUntil, lastKick = -1000;
        /** Who may kick in the pause: the side kicking off (its attack), or 0 for nobody. */
        int kickOffBy;
        @Nullable UUID lastTouch;
        final Map<UUID, Long> lastKickBy = new HashMap<>();
        final Map<UUID, Long> pathAt = new HashMap<>();
        final Map<UUID, Boolean> watching = new HashMap<>();
        final Map<UUID, Long> spoke = new HashMap<>();
        int kicks, missing;

        Match(UUID village, ResourceKey<Level> dim, Ledger.Building pitch, Side home, Side away, boolean league, @Nullable Friendlies.Tour tour) {
            this.village = village;
            this.dim = dim;
            this.pitch = pitch;
            this.home = home;
            this.away = away;
            this.league = league;
            this.tour = tour;
        }

        @Nullable
        Side sideOf(UUID u) {
            return home.has(u) ? home : away.has(u) ? away : null;
        }

        String score() {
            return home.name + " " + home.goals + ", " + away.name + " " + away.goals;
        }
    }

    /** The match on each town's pitch, by the town. */
    private static final Map<UUID, Match> MATCHES = new ConcurrentHashMap<>();
    /** Each player's match. */
    private static final Map<UUID, Match> PLAYERS = new ConcurrentHashMap<>();
    /** The day each town last had its match (or tried to). */
    private static final Map<UUID, Long> DAY = new ConcurrentHashMap<>();
    /** Tests: the last few matches' results, by town. */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();
    private static volatile boolean quick;

    public static void resetForTests() {
        MATCHES.clear();
        PLAYERS.clear();
        DAY.clear();
        LAST.clear();
        quick = false;
    }

    /** Tests: a match of half a minute. */
    public static void quickForTests(boolean on) {
        quick = on;
    }

    /** Is a match on this town's pitch now? */
    public static boolean on(@Nullable UUID village) {
        return village != null && MATCHES.containsKey(village);
    }

    /** Is this folk in a match (playing; watching does not count)? */
    static boolean busy(VillageFolkEntity f) {
        return PLAYERS.containsKey(f.getUUID());
    }

    /** Is this folk in the crowd at a match in its town? */
    static boolean watching(VillageFolkEntity f) {
        Match m = f.ownerId() == null ? null : MATCHES.get(f.ownerId());
        return m != null && Boolean.TRUE.equals(m.watching.get(f.getUUID()));
    }

    // ------------------------------------------------------------------ the sides

    /** The end of the town a folk's home is in: "the North End". One with no bed of its own goes by its name. */
    @Nullable
    public static String teamOf(VillageFolkEntity f) {
        BlockPos heart = f.villageCentre();
        if (heart == null || f.ownerId() == null) return null;
        BlockPos home = f.bedPos();
        int side;
        if (home != null && (home.getX() != heart.getX() || home.getZ() != heart.getZ())) {
            side = Districts.sideOf(home.getX() - heart.getX(), home.getZ() - heart.getZ(), null);
        } else {
            side = Math.floorMod(f.getUUID().hashCode() >> 2, 4);
        }
        return "the " + League.capital(Districts.sideWord(side)) + " End";
    }

    /** Free to play: grown, not old, not the watch, at home and not about anything else. */
    static boolean canPlay(VillageFolkEntity f) {
        return f.isAlive() && !f.isBaby() && !f.isOld() && !f.isShowcase() && !f.isHired() && !f.isSleeping()
            && f.trip() == null && f.expedition() == null && !Nether.away(f) && !Drover.busy(f)
            && f.stationTask() != StationTask.GUARD && f.getTarget() == null && !Sport.busyElsewhere(f, "football")
            && f.talkPartner() == null;
    }

    /** A day's turn for a folk, the same all day: who of an end plays this week, and in what order. */
    static int turn(UUID u, long day) {
        return Math.floorMod((int) (day * 7919L) ^ u.hashCode(), 1 << 20);
    }

    /** Up to MOST of these, a different few each week. */
    static List<VillageFolkEntity> pick(List<VillageFolkEntity> pool, long day, int most) {
        List<VillageFolkEntity> out = new ArrayList<>(pool);
        out.sort(Comparator.comparingInt(f -> turn(f.getUUID(), day)));
        return out.size() > most ? new ArrayList<>(out.subList(0, most)) : out;
    }

    /**
     * The rest day's match (Sport's programme, from one o'clock): the two ends that have met least, or the Greens and
     * the Golds; a match is started, or why not. Once a day.
     */
    @Nullable
    static String fixture(ServerLevel level, Villages.Village v, long day) {
        Ledger.Building pitch = Pitch.of(v.id());
        if (pitch == null || MATCHES.containsKey(v.id())) return null;
        if (DAY.getOrDefault(v.id(), -1L) >= day) return null;
        if (!Land.areaLoaded(level, pitch.anchor(), 14)) return null;
        if (Friendlies.expected(v.id())) return null;              // a side is coming from a neighbour: their match
        DAY.put(v.id(), day);
        if (Weather.stormy(level)) {
            Villages.tell(v.id(), day, "the football was rained off: a thunderstorm");
            return "rained off";
        }
        Map<String, List<VillageFolkEntity>> ends = new LinkedHashMap<>();
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !canPlay(f)) continue;
            if (f.distanceToSqr(Pitch.point(pitch, 0, 0)) > 120.0 * 120.0) continue;
            String end = teamOf(f);
            if (end == null) continue;
            ends.computeIfAbsent(end, k -> new ArrayList<>()).add(f);
            all.add(f);
        }
        List<String> able = new ArrayList<>();
        for (Map.Entry<String, List<VillageFolkEntity>> e : ends.entrySet()) if (e.getValue().size() >= LEAST) able.add(e.getKey());
        able.sort(String::compareTo);
        Side home, away;
        boolean league;
        if (able.size() >= 2) {
            String[] best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int i = 0; i < able.size(); i++) {
                for (int j = i + 1; j < able.size(); j++) {
                    String a = able.get(i), b = able.get(j);
                    int played = 0;
                    for (League.Row r : League.table(v.id())) if (r.team.equals(a) || r.team.equals(b)) played += r.played;
                    int score = League.met(v.id(), a, b) * 1000 + played * 10 + Math.floorMod((int) day + i * 3 + j, 7);
                    if (score < bestScore) { bestScore = score; best = new String[]{ a, b }; }
                }
            }
            home = new Side(best[0], v.id());
            away = new Side(best[1], v.id());
            for (VillageFolkEntity f : pick(ends.get(best[0]), day, MOST)) home.players.add(f.getUUID());
            for (VillageFolkEntity f : pick(ends.get(best[1]), day, MOST)) away.players.add(f.getUUID());
            league = true;
        } else {
            if (all.size() < 2 * LEAST) return "too few to play: " + all.size();
            List<VillageFolkEntity> turnOut = pick(all, day, 2 * MOST);
            home = new Side("the Greens", v.id());
            away = new Side("the Golds", v.id());
            for (int i = 0; i < turnOut.size(); i++) (i % 2 == 0 ? home : away).players.add(turnOut.get(i).getUUID());
            league = false;
        }
        Match m = start(level, v, pitch, home, away, league, null);
        return m == null ? "no ball in the stores" : "the sides are out: " + home.name + " and " + away.name;
    }

    /** The ball out of the stores: a slime ball, else a scrap of leather. Empty if the stores have neither. */
    static ItemStack takeBall(ServerLevel level, Villages.Village v) {
        ItemStack s = Crafts.takeOne(level, v, st -> st.is(Items.SLIME_BALL));
        return s.isEmpty() ? Crafts.takeOne(level, v, st -> st.is(Items.LEATHER)) : s;
    }

    /** A match begun: the ball out of the stores, the players called out to the pitch. Null with no ball. */
    @Nullable
    static Match start(ServerLevel level, Villages.Village v, Ledger.Building pitch, Side home, Side away, boolean league,
                       @Nullable Friendlies.Tour tour) {
        ItemStack ball = takeBall(level, v);
        long day = level.getDayTime() / 24000L;
        if (ball.isEmpty()) {
            Villages.tell(v.id(), day, "no football today: not a slime ball or a bit of leather in the stores for a ball");
            return null;
        }
        home.attack = 1;
        away.attack = -1;
        for (Side s : new Side[]{ home, away }) if (s.players.size() >= 3) s.keeper = s.players.get(s.players.size() - 1);
        Match m = new Match(v.id(), level.dimension(), pitch, home, away, league, tour);
        m.ballStack = ball;
        m.gatherFrom = level.getGameTime();
        MATCHES.put(v.id(), m);
        for (Side s : new Side[]{ home, away }) {
            for (UUID u : s.players) {
                PLAYERS.put(u, m);
                VillageFolkEntity f = live(level, u);
                if (f == null) continue;
                f.clearQueue();
                f.getNavigation().stop();
            }
        }
        VillageFolkEntity first = live(level, home.players.isEmpty() ? null : home.players.get(0));
        if (first != null) {
            FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "Match day! Come on, " + home.name + "!",
                "Football on the pitch! Who's coming to watch?", "Boots on. " + League.capital(away.name) + " won't know what hit them."));
        }
        return m;
    }

    @Nullable
    static VillageFolkEntity live(ServerLevel level, @Nullable UUID u) {
        return u != null && level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    @Nullable
    static ItemEntity ball(ServerLevel level, Match m) {
        return m.ball != null && level.getEntity(m.ball) instanceof ItemEntity e && e.isAlive() ? e : null;
    }

    // ------------------------------------------------------------------ the referee: every tick

    /** Every tick, every match on in this level: kick-off, the ball's kicks, goals, the final whistle. */
    static void tick(ServerLevel level) {
        if (MATCHES.isEmpty()) return;
        for (Match m : new ArrayList<>(MATCHES.values())) {
            if (!m.dim.equals(level.dimension())) continue;
            try {
                step(level, m);
            } catch (RuntimeException e) {
                end(level, m, "a muddle");
                throw e;
            }
        }
    }

    static void step(ServerLevel level, Match m) {
        long now = level.getGameTime(), t = Math.floorMod(level.getDayTime(), 24000L);
        if (Villages.get(m.village) == null) { end(level, m, "the town is gone"); return; }
        if (Raids.underAlarm(m.village)) { end(level, m, "the bell rang"); return; }
        if (Weather.stormy(level)) { end(level, m, "a thunderstorm"); return; }
        if (t >= CLOSE && m.tour == null || t >= CLOSE + 500L) { end(level, m, m.stage == PLAYING ? null : "it grew too late"); return; }
        if (m.stage == GATHERING) {
            int[] on = { 0, 0 };
            boolean all = true;
            for (Side s : new Side[]{ m.home, m.away }) {
                int i = 0;
                for (UUID u : s.players) {
                    VillageFolkEntity f = live(level, u);
                    if (f != null && Pitch.onField(m.pitch, f.position())) on[s == m.home ? 0 : 1]++;
                    if (f == null || f.position().distanceToSqr(startSpot(m, s, i)) > 6.0) all = false;
                    i++;
                }
            }
            boolean waited = now - m.gatherFrom >= GATHER;
            if (all || waited) {
                if (on[0] >= 1 && on[1] >= 1 && on[0] + on[1] >= 2 * LEAST - 1) kickOff(level, m);
                else end(level, m, "too few came out to play");
            }
            return;
        }
        ItemEntity ball = ball(level, m);
        if (ball == null) {
            if (++m.missing > 40) end(level, m, "the ball was lost");
            return;
        }
        m.missing = 0;
        if (now >= m.endAt) { end(level, m, null); return; }
        // Where the ball is: in the net, over a line, or in play.
        int call = judge(m.pitch, ball.position());
        if (call == 1 || call == -1) {
            goal(level, m, ball, call);
            return;
        }
        if (call == 2) {
            double[] uw = Pitch.local(m.pitch, ball.position());
            double u = Math.max(-Pitch.HALF_WIDE + 0.8, Math.min(Pitch.HALF_WIDE - 0.8, uw[0]));
            double w = Math.max(-Pitch.HALF_LONG + 1.5, Math.min(Pitch.HALF_LONG - 1.5, uw[1]));
            place(m, ball, u, w);
            m.pauseUntil = now + 20;
            m.kickOffBy = 0;
            return;
        }
        // A ball nobody has touched for half a minute (in a corner, under a bench): back to the middle.
        if (now - m.lastKick > 600 && now > m.pauseUntil + 600) {
            place(m, ball, 0, 0);
            m.lastKick = now;
            return;
        }
        kicks(level, m, ball, now);
    }

    /**
     * What the referee makes of the ball here: +1 a goal at the back end (over the goal line between the posts
     * and under the bar), -1 a goal at the front end, 2 out of play over a line, 0 in play.
     */
    public static int judge(Ledger.Building pitch, Vec3 ball) {
        double[] uw = Pitch.local(pitch, ball);
        double u = uw[0], w = uw[1], h = ball.y - pitch.anchor().getY();
        if (Math.abs(w) > Pitch.HALF_LONG + 0.5) {
            if (Math.abs(u) < Pitch.MOUTH && h < Pitch.BAR && Math.abs(w) < Pitch.HALF_LONG + 2.5) return w > 0 ? 1 : -1;
            return 2;
        }
        if (Math.abs(u) > Pitch.HALF_WIDE + 0.5) return 2;
        if (h < -1.5 || h > 6) return 2;
        return 0;
    }

    /** The ball set down on the field, still. */
    static void place(Match m, ItemEntity ball, double u, double w) {
        Vec3 p = Pitch.point(m.pitch, u, w);
        ball.moveTo(p.x, p.y + 0.1, p.z);
        ball.setDeltaMovement(Vec3.ZERO);
        ball.hasImpulse = true;
    }

    /** The kick-off: the ball on the centre spot, the home side to play it. */
    static void kickOff(ServerLevel level, Match m) {
        long now = level.getGameTime();
        Vec3 c = Pitch.point(m.pitch, 0, 0);
        ItemEntity ball = new ItemEntity(level, c.x, c.y + 0.1, c.z, m.ballStack.copy(), 0, 0, 0);
        ball.setNeverPickUp();
        ball.setUnlimitedLifetime();
        ball.addTag(Sport.BALL);
        // Spoken for (Sweepers.playersOwn): nobody sweeps it up, or pockets it on an idle look round, mid-match.
        ball.setTarget(m.village);
        level.addFreshEntity(ball);
        m.ball = ball.getUUID();
        m.stage = PLAYING;
        m.kickOff = now;
        m.endAt = now + (quick ? 600 : LENGTH);
        m.lastKick = now;
        m.pauseUntil = now + 40;
        m.kickOffBy = m.home.attack;
        level.playSound(null, BlockPos.containing(c), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.NEUTRAL, 1.0F, 1.6F);
        VillageFolkEntity f = live(level, m.home.players.isEmpty() ? null : m.home.players.get(0));
        if (f != null) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Kick-off!", "Here we go!", "Off we go, then!"));
    }

    /** The nearest player to the ball who can reach it kicks it, if anybody can. */
    static void kicks(ServerLevel level, Match m, ItemEntity ball, long now) {
        if (now - m.lastKick < 5) return;
        Vec3 bp = ball.position();
        VillageFolkEntity best = null;
        Side bestSide = null;
        double bestD = REACH * REACH;
        for (Side s : new Side[]{ m.home, m.away }) {
            if (now < m.pauseUntil && s.attack != m.kickOffBy) continue;      // the other side stands off at a kick-off
            for (UUID u : s.players) {
                if (now - m.lastKickBy.getOrDefault(u, -1000L) < 12) continue;
                VillageFolkEntity f = live(level, u);
                if (f == null) continue;
                double dy = bp.y - f.getY();
                if (dy < -0.8 || dy > 1.6) continue;
                double dx = f.getX() - bp.x, dz = f.getZ() - bp.z, d = dx * dx + dz * dz;
                if (d <= bestD) { bestD = d; best = f; bestSide = s; }
            }
        }
        if (best != null) kick(level, m, ball, best, bestSide, now);
    }

    /**
     * A kick: toward the other side's goal, a spot between its posts, and off true by a little (more from far
     * out, more again for a clearance). A shot from close in is struck hard and low; a pass from further out
     * goes a few blocks up the field; a goalkeeper's clearance goes high and long.
     */
    static void kick(ServerLevel level, Match m, ItemEntity ball, VillageFolkEntity f, Side s, long now) {
        RandomSource r = level.getRandom();
        double[] uw = Pitch.local(m.pitch, ball.position());
        boolean keeper = f.getUUID().equals(s.keeper);
        double goalW = s.attack * (Pitch.HALF_LONG + 0.9), goalU = (r.nextDouble() * 2 - 1) * 1.1;
        double du = goalU - uw[0], dw = goalW - uw[1];
        double dist = Math.sqrt(du * du + dw * dw);
        boolean shot = !keeper && dist <= 7.5;
        double spread = Math.toRadians(keeper ? 40 : shot ? 14 : 28);
        double angle = Math.atan2(du, dw) + (r.nextDouble() * 2 - 1) * spread;
        double power = keeper ? 0.8 + r.nextDouble() * 0.15 : shot ? 0.62 + r.nextDouble() * 0.2 : 0.34 + r.nextDouble() * 0.16;
        double lift = keeper ? 0.3 : shot ? 0.1 + r.nextDouble() * 0.1 : 0.14 + r.nextDouble() * 0.06;
        double lu = Math.sin(angle) * power, lw = Math.cos(angle) * power;
        Direction right = m.pitch.facing().getClockWise(), along = m.pitch.facing();
        ball.setDeltaMovement(right.getStepX() * lu + along.getStepX() * lw, lift, right.getStepZ() * lu + along.getStepZ() * lw);
        ball.hasImpulse = true;
        f.swing(InteractionHand.MAIN_HAND);
        f.getLookControl().setLookAt(ball, 30.0F, 30.0F);
        level.playSound(null, ball.blockPosition(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, 0.7F, shot ? 0.8F : 1.1F);
        m.lastTouch = f.getUUID();
        m.lastKick = now;
        m.lastKickBy.put(f.getUUID(), now);
        m.kicks++;
        if (now < m.pauseUntil) m.pauseUntil = now;                           // kicked off: play on
        if (shot && r.nextInt(4) == 0) say(level, m, f, FolkTalk.pick(r, "Shoot!", "Have that!", "Top corner!"));
        else if (keeper && r.nextInt(3) == 0) say(level, m, f, FolkTalk.pick(r, "Mine!", "Clear it!", "Away!"));
    }

    /** A goal at that end: to the side attacking it; the scorer named (an own goal if the other side touched it last). */
    static void goal(ServerLevel level, Match m, ItemEntity ball, int end) {
        Side scored = m.home.attack == end ? m.home : m.away, let = scored == m.home ? m.away : m.home;
        scored.goals++;
        VillageFolkEntity by = live(level, m.lastTouch);
        String who = by == null ? "a scramble" : scored.has(by.getUUID()) ? by.displayNameCap() : "an own goal";
        scored.scorers.add(who);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, ball.getX(), ball.getY() + 0.5, ball.getZ(), 20, 0.6, 0.4, 0.6, 0.1);
        level.playSound(null, ball.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        RandomSource r = level.getRandom();
        if (by != null && scored.has(by.getUUID())) {
            say(level, m, by, FolkTalk.pick(r, "GOAL!", "Get in!", "Did you see that?", "In the net!"));
            by.persona().remember(level.getDayTime() / 24000L, "I scored for " + scored.name + " against " + let.name, 3);
        }
        for (Map.Entry<UUID, Boolean> e : m.watching.entrySet()) {
            if (!e.getValue() || r.nextInt(3) != 0) continue;
            VillageFolkEntity w = live(level, e.getKey());
            if (w != null && scored.name.equals(cheersFor(w, m))) {
                say(level, m, w, FolkTalk.pick(r, "Goal! Come on, " + scored.name + "!", "Yes! Get in!", "What a goal!"));
                w.getJumpControl().jump();
            }
        }
        // The side that let it in kicks off.
        place(m, ball, 0, 0);
        m.pauseUntil = level.getGameTime() + 60;
        m.kickOffBy = let.attack;
        m.lastTouch = null;
    }

    /** Words said, no more than one line every ten seconds a folk. */
    static void say(ServerLevel level, Match m, VillageFolkEntity f, String text) {
        long now = level.getGameTime();
        if (now - m.spoke.getOrDefault(f.getUUID(), -1000L) < 200) return;
        m.spoke.put(f.getUUID(), now);
        FolkTalk.speak(f, text);
    }

    /** The final whistle (reason null), or the match stopped (why): the ball back in the stores, the result told. */
    static void end(ServerLevel level, Match m, @Nullable String why) {
        if (MATCHES.remove(m.village) == null) return;
        for (Side s : new Side[]{ m.home, m.away }) for (UUID u : s.players) PLAYERS.remove(u, m);
        Villages.Village v = Villages.get(m.village);
        long day = level.getDayTime() / 24000L;
        ItemEntity ball = ball(level, m);
        if (ball != null) {
            ItemStack back = ball.getItem().copy();
            ball.discard();
            if (v != null) Crafts.store(level, v, back);
        } else if (m.stage == GATHERING && v != null) {
            Crafts.store(level, v, m.ballStack);                            // never kicked: still in hand, back it goes
        }
        if (v == null) return;
        boolean played = m.stage == PLAYING && (why == null || level.getGameTime() - m.kickOff >= (quick ? 300 : LENGTH / 2));
        String result = resultLine(m);
        if (!played) {
            String line = "the football on the pitch was called off" + (why == null ? "" : ": " + why);
            if (m.tour == null) Villages.tell(m.village, day, line);
            if (m.tour != null) Friendlies.calledOff(level, m.tour, why == null ? "the match never got going" : why);
            LAST.put(m.village, line);
            return;
        }
        String full = result + (why == null ? "" : " (stopped early: " + why + ")");
        LAST.put(m.village, full);
        if (m.tour != null) {
            Friendlies.over(level, m.tour, m.home.goals, m.away.goals, full);
        } else {
            Villages.tell(m.village, day, full);
            League.played(level, v, day, m.home.name, m.away.name, m.home.goals, m.away.goals, m.league);
        }
        // The players' own memories of it.
        for (Side s : new Side[]{ m.home, m.away }) {
            Side other = s == m.home ? m.away : m.home;
            String how = s.goals > other.goals ? "we beat " + other.name + " at football, " + s.goals + "-" + other.goals
                : s.goals < other.goals ? other.name + " beat us at football, " + other.goals + "-" + s.goals
                : "we drew with " + other.name + " at football, " + s.goals + " all";
            for (UUID u : s.players) {
                VillageFolkEntity f = live(level, u);
                if (f == null) continue;
                f.persona().remember(day, how, s.goals > other.goals ? 3 : 2);
                if (s.goals > other.goals && level.getRandom().nextInt(2) == 0) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(),
                    "We won! We won!", "Get in! Champions!", "Good game, all."));
            }
        }
    }

    /** "the North End beat the South End 3-1 on the football pitch (Bramble 2, Holt)" */
    static String resultLine(Match m) {
        Side h = m.home, a = m.away;
        String where = m.tour != null ? "" : " on the football pitch";
        String line = h.goals > a.goals ? h.name + " beat " + a.name + " " + h.goals + "-" + a.goals + where
            : a.goals > h.goals ? a.name + " beat " + h.name + " " + a.goals + "-" + h.goals + where
            : h.name + " and " + a.name + " drew " + h.goals + "-" + a.goals + where;
        List<String> goals = new ArrayList<>();
        for (Side s : new Side[]{ h, a }) goals.addAll(tally(s.scorers));
        return goals.isEmpty() ? line : line + " (" + String.join(", ", goals) + ")";
    }

    /** "Bramble 2", "Holt" */
    static List<String> tally(List<String> scorers) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (String s : scorers) n.merge(s, 1, Integer::sum);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : n.entrySet()) out.add(e.getValue() > 1 ? e.getKey() + " " + e.getValue() : e.getKey());
        return out;
    }

    // ------------------------------------------------------------------ the players and the crowd: each folk's own look

    /**
     * A folk in a match: to its place, at the ball, in goal; or, off on its rest day with a match on in its
     * town, to the side of the pitch to watch. True while it is about either (its own day waits).
     */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Match m = PLAYERS.get(f.getUUID());
        if (m != null) {
            if (!m.dim.equals(level.dimension())) return false;
            play(f, level, m);
            return true;
        }
        UUID village = f.ownerId();
        m = village == null ? null : MATCHES.get(village);
        if (m == null || !m.dim.equals(level.dimension())) return false;
        Boolean w = m.watching.get(f.getUUID());
        if (w == null) {
            w = watches(f, level, m);
            m.watching.put(f.getUUID(), w);
        }
        if (!w || f.isSleeping() || f.getTarget() != null || Sport.busyElsewhere(f, "football")) return false;
        watch(f, level, m);
        return true;
    }

    /** Does this folk come and watch: off on its rest day, kin to a player, a child, or with nobody to walk out with. */
    static boolean watches(VillageFolkEntity f, ServerLevel level, Match m) {
        if (!f.isAlive() || f.isShowcase() || f.isHired() || f.trip() != null || f.expedition() != null) return false;
        if (f.stationTask() == StationTask.GUARD && !f.isBaby()) return false;
        UUID village = f.ownerId();
        if (village == null || RestDay.now(village, level.getDayTime()) == null) return false;
        if (f.distanceToSqr(Pitch.point(m.pitch, 0, 0)) > 96.0 * 96.0) return false;
        if (f.isBaby()) return true;
        for (Side s : new Side[]{ m.home, m.away }) {
            for (UUID u : s.players) {
                VillageFolkEntity p = live(level, u);
                if (p == null) continue;
                if (u.equals(f.life().partner()) || f.parentIds().contains(u) || p.parentIds().contains(f.getUUID())) return true;
            }
        }
        return RestDay.sweetheart(f, level) == null;
    }

    /** Whose side a folk in the crowd is on: its own end's, if it is playing, else its kin's, else the home side. */
    static String cheersFor(VillageFolkEntity f, Match m) {
        String end = teamOf(f);
        if (end != null && (end.equals(m.home.name) || end.equals(m.away.name))) return end;
        if (f.ownerId() != null && m.away.town.equals(f.ownerId()) && !m.home.town.equals(m.away.town)) return m.away.name;
        for (Side s : new Side[]{ m.home, m.away }) {
            for (UUID u : s.players) if (u.equals(f.life().partner()) || f.parentIds().contains(u)) return s.name;
        }
        return m.home.name;
    }

    /** Where a player stands at the kick-off: in its own half, spread across it; the keeper on its goal line. */
    static Vec3 startSpot(Match m, Side s, int i) {
        if (s.keeper != null && i < s.players.size() && s.players.get(i).equals(s.keeper)) {
            return Pitch.point(m.pitch, 0, -s.attack * (Pitch.HALF_LONG - 0.7));
        }
        int n = s.players.size();
        double u = Math.max(-3.2, Math.min(3.2, (i - (n - 1) / 2.0) * 2.2));
        double w = -s.attack * (1.6 + 1.8 * (i % 2));
        return Pitch.point(m.pitch, u, w);
    }

    static void play(VillageFolkEntity f, ServerLevel level, Match m) {
        Side s = m.sideOf(f.getUUID());
        if (s == null) return;
        f.hobbyNow = (m.tour != null ? "playing football for " + s.name + " against " + (s == m.home ? m.away : m.home).name
            : "playing football for " + s.name + " on the pitch");
        f.lastLeisureTick = f.tickCount;
        if (f.getTarget() != null) return;
        long now = level.getGameTime();
        int i = s.players.indexOf(f.getUUID());
        ItemEntity ball = ball(level, m);
        Vec3 to;
        double speed = 1.0;
        boolean keeper = f.getUUID().equals(s.keeper);
        if (m.stage == GATHERING || ball == null || (now < m.pauseUntil && m.kickOffBy != s.attack && m.kickOffBy != 0)) {
            to = startSpot(m, s, i);
            speed = m.stage == GATHERING ? 0.9 : 1.1;
        } else {
            double[] b = Pitch.local(m.pitch, ball.position());
            if (keeper) {
                double ownEnd = -s.attack * Pitch.HALF_LONG;
                boolean near = Math.abs(b[1] - ownEnd) < 3.5 && Math.abs(b[0]) < 3.0;
                to = near ? ball.position() : Pitch.point(m.pitch, Math.max(-1.3, Math.min(1.3, b[0])), -s.attack * (Pitch.HALF_LONG - 0.7));
                speed = near ? 1.25 : 1.0;
            } else if (chaser(level, m, s, f, ball)) {
                // At the ball, from behind it: a kick goes up the field from there.
                to = Pitch.point(m.pitch, b[0], b[1] - s.attack * 0.4);
                speed = 1.3;
            } else {
                double lane = (i % 2 == 0 ? -1 : 1) * (1.5 + (i % 3));
                double w = Math.max(-Pitch.HALF_LONG + 1.5, Math.min(Pitch.HALF_LONG - 1.5, b[1] + s.attack * 2.5));
                to = Pitch.point(m.pitch, Math.max(-3.5, Math.min(3.5, lane)), w);
            }
            f.getLookControl().setLookAt(ball, 30.0F, 30.0F);
        }
        double d = f.position().distanceToSqr(to.x, f.getY(), to.z);
        if (d > 0.5 && (now - m.pathAt.getOrDefault(f.getUUID(), -1000L) >= 8 || f.getNavigation().isDone())) {
            f.getNavigation().moveTo(to.x, to.y, to.z, speed);
            m.pathAt.put(f.getUUID(), now);
        } else if (d <= 0.5) {
            f.getNavigation().stop();
        }
        if (m.stage == PLAYING && level.getRandom().nextInt(900) == 0) {
            say(level, m, f, FolkTalk.pick(level.getRandom(), "Pass it!", "Man on!", "Over here!", "Come on, " + s.name + "!", "Keep it moving!"));
        }
    }

    /** Is this one of the two of its side nearest the ball (the ones who go for it)? */
    static boolean chaser(ServerLevel level, Match m, Side s, VillageFolkEntity f, ItemEntity ball) {
        double mine = f.distanceToSqr(ball);
        int nearer = 0;
        for (UUID u : s.players) {
            if (u.equals(f.getUUID()) || u.equals(s.keeper)) continue;
            VillageFolkEntity o = live(level, u);
            if (o != null && o.distanceToSqr(ball) < mine) nearer++;
        }
        return nearer < 2;
    }

    static void watch(VillageFolkEntity f, ServerLevel level, Match m) {
        f.hobbyNow = "watching the football on the pitch";
        f.lastLeisureTick = f.tickCount;
        int h = f.getUUID().hashCode();
        double u = (Math.floorMod(h, 2) == 0 ? -1 : 1) * (Pitch.HALF_WIDE + 1.0);
        double w = Math.floorMod(h >> 3, 13) - 6;
        Vec3 spot = Pitch.point(m.pitch, u, w);
        long now = level.getGameTime();
        if (f.position().distanceToSqr(spot.x, f.getY(), spot.z) > 2.0) {
            if (f.getNavigation().isDone() || now - m.pathAt.getOrDefault(f.getUUID(), -1000L) > 100) {
                f.getNavigation().moveTo(spot.x, spot.y, spot.z, 0.9);
                m.pathAt.put(f.getUUID(), now);
            }
            return;
        }
        f.getNavigation().stop();
        ItemEntity ball = ball(level, m);
        if (ball != null) f.getLookControl().setLookAt(ball, 30.0F, 30.0F);
        if (m.stage == PLAYING && level.getRandom().nextInt(700) == 0) {
            String side = cheersFor(f, m);
            say(level, m, f, f.isBaby() ? FolkTalk.pick(level.getRandom(), "Go on! Go on!", "Kick it!", "Yay!")
                : FolkTalk.pick(level.getRandom(), "Come on, " + side + "!", "Get stuck in!", "Oh, so close!", "Ref!", "Shoot!"));
        }
    }

    // ------------------------------------------------------------------ where the player sees it

    /** The match on now, in a line (the board), or null. */
    @Nullable
    static String now(UUID village) {
        Match m = MATCHES.get(village);
        if (m == null) return null;
        return m.stage == GATHERING ? "football on the pitch: " + m.home.name + " against " + m.away.name + ", the sides coming out"
            : "football on the pitch: " + m.score();
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a match begun now on the town's pitch between these two sides (null if the stores have no ball). */
    @Nullable
    public static String startForTests(ServerLevel level, Villages.Village v, List<VillageFolkEntity> home, String homeName,
                                       List<VillageFolkEntity> away, String awayName, boolean league) {
        Ledger.Building pitch = Pitch.of(v.id());
        if (pitch == null) return null;
        Side h = new Side(homeName, v.id()), a = new Side(awayName, v.id());
        for (VillageFolkEntity f : home) h.players.add(f.getUUID());
        for (VillageFolkEntity f : away) a.players.add(f.getUUID());
        Match m = start(level, v, pitch, h, a, league, null);
        return m == null ? null : m.home.name + " v " + m.away.name;
    }

    /** Tests: the rest day's fixture, as the programme makes it. */
    @Nullable
    public static String fixtureForTests(ServerLevel level, Villages.Village v, long day) {
        DAY.remove(v.id());
        return fixture(level, v, day);
    }

    /** Tests: kick off now, whoever is out (true once the match is under way, now or already). */
    public static boolean kickOffForTests(ServerLevel level, UUID village) {
        Match m = MATCHES.get(village);
        if (m == null) return false;
        if (m.stage == GATHERING) kickOff(level, m);
        return m.stage == PLAYING;
    }

    @Nullable
    public static ItemEntity ballForTests(ServerLevel level, UUID village) {
        Match m = MATCHES.get(village);
        return m == null ? null : ball(level, m);
    }

    /** Tests: {home goals, away goals, kicks}, or null with no match. */
    @Nullable
    public static int[] scoreForTests(UUID village) {
        Match m = MATCHES.get(village);
        return m == null ? null : new int[]{ m.home.goals, m.away.goals, m.kicks };
    }

    /** Tests: the referee looks at the ball now. */
    public static void refereeForTests(ServerLevel level, UUID village) {
        Match m = MATCHES.get(village);
        if (m != null) step(level, m);
    }

    /** Tests: the final whistle now. */
    public static void endForTests(ServerLevel level, UUID village) {
        Match m = MATCHES.get(village);
        if (m != null) end(level, m, null);
    }

    /** Tests: the last match's result, as told. */
    @Nullable
    public static String lastForTests(UUID village) {
        return LAST.get(village);
    }
}
