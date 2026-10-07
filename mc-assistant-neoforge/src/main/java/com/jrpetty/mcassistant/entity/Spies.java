package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war-scouting] Catching spies. A town on its guard keeps an eye out for strangers watching it from the
 * hills.
 * <ul>
 * <li><b>Spotted.</b> A guard (or a picket out on the road, who is there to look) that has a spy of a town
 *     we are at odds with in plain sight, within twenty blocks (Sharp Eyes, four more; a picket, twelve
 *     more), picks it out: at once within eight blocks, else one look in two, or one in four if it is lying
 *     low in the grass.</li>
 * <li><b>The chase.</b> The nearest free guard runs it down; the spy, seeing it, gets up and runs for home.
 *     Caught within reach, it is taken; past sixty-four blocks beyond the town's edge, or after three quarters
 *     of a minute, the watch lets it go, and it goes home with a report that says it was seen.</li>
 * <li><b>Taken.</b> Its count of us is lost with it. It is held at the barracks (else the hall, else by the
 *     square), and questioned: what it knows of its own town (its watch, its walls, its stores) is ours, filed
 *     as a report. Caught spying does the peace between the two towns no good.</li>
 * <li><b>Let go.</b> When the two towns are at peace again, or when the peace talks exchange captives
 *     ({@link #exchange}), it is let go and walks home.</li>
 * </ul>
 * Captives are kept with the world (Ledger notes "captive/&lt;folk&gt;" on the town that holds them).
 */
public final class Spies {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Spies() {}

    /** How far off a guard picks out a stranger watching the town. */
    static final int SPOT = 20;
    /** So near it cannot be missed. */
    static final int CLOSE = 8;
    /** Within reach: caught. */
    static final double REACH = 2.2;
    /** The longest the watch runs after one (ticks). */
    static final int CHASE_MOST = 900;
    /** How far past the town's edge the watch follows a spy before it lets it go. */
    static final int LET_GO = 64;

    /** A guard after a spy, for which town, since when. */
    record Chase(UUID guard, UUID spy, UUID village, long since) {}

    /** A captive: who, of which town, held by which, since what day. */
    public record Captive(UUID who, String name, UUID home, UUID holder, long day) {
        String encode() {
            return home + ";" + day + ";" + name.replace(";", ",");
        }
    }

    /** By guard. */
    private static final Map<UUID, Chase> CHASES = new ConcurrentHashMap<>();
    /** By captive. */
    private static final Map<UUID, Captive> HELD = new ConcurrentHashMap<>();
    /** By "home/holder": the day a spy of home's was last taken by holder. */
    private static final Map<String, Long> LOST = new ConcurrentHashMap<>();
    /** How many towns the captives were last read for (the towns come back one by one after a start). */
    private static volatile int scanned = -1;

    static void resetForTests() {
        CHASES.clear();
        HELD.clear();
        LOST.clear();
        scanned = -1;
    }

    /** The captives as the world's books keep them: read again whenever the towns known have changed in number. */
    private static void load() {
        List<Villages.Village> all = Villages.every();
        if (all.size() == scanned) return;
        scanned = all.size();
        for (Villages.Village v : all) {
            for (Map.Entry<String, String> n : Ledger.notes(v.id()).entrySet()) {
                if (!n.getKey().startsWith("captive/") || n.getValue() == null || n.getValue().isEmpty()) continue;
                String[] p = n.getValue().split(";", 3);
                if (p.length < 3) continue;
                try {
                    UUID who = UUID.fromString(n.getKey().substring("captive/".length()));
                    HELD.putIfAbsent(who, new Captive(who, p[2], UUID.fromString(p[0]), v.id(), Long.parseLong(p[1])));
                } catch (IllegalArgumentException e) {
                    LOG.debug("[MCA-SPY] a captive's note would not read: {}", n.getValue());
                }
            }
        }
    }

    // ------------------------------------------------------------------ who is held

    /** Is this folk held captive by another town? (Asked every tick: the books are read by the watch's look round.) */
    public static boolean held(VillageFolkEntity f) {
        return HELD.containsKey(f.getUUID());
    }

    /** The captives this town holds. */
    public static List<Captive> captives(UUID holder) {
        load();
        List<Captive> out = new ArrayList<>();
        for (Captive c : HELD.values()) if (c.holder().equals(holder)) out.add(c);
        out.sort(java.util.Comparator.comparingLong(Captive::day));
        return out;
    }

    /** This town's folk held captive elsewhere. */
    public static List<Captive> ofOurs(UUID home) {
        load();
        List<Captive> out = new ArrayList<>();
        for (Captive c : HELD.values()) if (c.home().equals(home)) out.add(c);
        return out;
    }

    /** The day that town last lost a spy to this one (-100 if never): it waits a little before sending another. */
    static long lostOn(UUID home, UUID holder) {
        return LOST.getOrDefault(home + "/" + holder, -100L);
    }

    /** Is this guard after a spy just now? */
    static boolean chasing(@Nullable UUID guard) {
        return guard != null && CHASES.containsKey(guard);
    }

    // ------------------------------------------------------------------ the watch's look out (WarScouting)

    /** The watch's look round one town (every second): spies spotted and chased; captives let go at peace. */
    public static void tick(ServerLevel level, Villages.Village v) {
        load();
        UUID id = v.id();
        long now = level.getGameTime();
        // A chase whose guard is gone (fell, or is in ground not loaded) is given up.
        CHASES.values().removeIf(c -> now - c.since() > CHASE_MOST + 200 || now < c.since());
        if (now % 200 < 20) letGoAtPeace(level, v);
        if (Wars.footing(id) == Wars.Footing.PEACE) return;
        int edge = Villages.townReach(id) + LET_GO;
        for (VillageFolkEntity spy : spiesOn(level, v)) {
            if (Scouts.flat(spy.blockPosition(), v.centre()) > (double) edge * edge) continue;
            if (chasedBy(spy) != null) continue;
            VillageFolkEntity seer = spotter(level, v, spy);
            if (seer == null) continue;
            VillageFolkEntity runner = seer.stationTask() == StationTask.GUARD ? seer : nearestFreeGuard(v, spy);
            Spying.Mission m = Spying.missionOf(spy);
            if (runner == null) {
                // Seen, and nobody to send after it: a shout is enough to send it off.
                if (m != null && !m.seen) {
                    FolkTalk.speak(seer, "You there, on the hill! I see you!");
                    Spying.spotted(level, spy, seer);
                    CHASES.remove(seer.getUUID());
                }
                continue;
            }
            CHASES.put(runner.getUUID(), new Chase(runner.getUUID(), spy.getUUID(), id, now));
            FolkTalk.speak(runner, FolkTalk.pick(runner.getRandom(), "You there! Stop!", "A spy! After it!", "Stay where you are, you!"));
            if (seer != runner) FolkTalk.speak(seer, "Up there — a stranger watching us!");
            Spying.spotted(level, spy, runner);
            LOG.info("[MCA-SPY] {} of {} spotted {} of {} ({} blocks off); {} gives chase", seer.displayNameCap(), Villages.name(id),
                spy.displayNameCap(), Villages.name(spy.ownerId()), (int) seer.distanceTo(spy), runner.displayNameCap());
        }
    }

    /** Folk of a rival town out to watch this one, in this world. */
    static List<VillageFolkEntity> spiesOn(ServerLevel level, Villages.Village v) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (UUID r : Spying.rivals(v.id())) {
            for (AssistantEntity a : Villages.folkOf(r)) {
                if (!(a instanceof VillageFolkEntity f) || f.level() != level || !f.isAlive()) continue;
                Spying.Mission m = Spying.missionOf(f);
                if (m != null && !m.freed && m.them.equals(v.id())) out.add(f);
            }
        }
        return out;
    }

    @Nullable
    static UUID chasedBy(VillageFolkEntity spy) {
        for (Chase c : CHASES.values()) if (c.spy().equals(spy.getUUID())) return c.guard();
        return null;
    }

    /** One of ours who picks out this spy now, or null: in plain sight, near enough, and (unless close) with a little luck. */
    @Nullable
    static VillageFolkEntity spotter(ServerLevel level, Villages.Village v, VillageFolkEntity spy) {
        List<VillageFolkEntity> eyes = new ArrayList<>(Patrols.watch(v.id()));
        for (VillageFolkEntity p : Pickets.of(v.id())) if (!eyes.contains(p)) eyes.add(p);
        boolean low = spy.getPose() == Pose.CROUCHING;
        for (VillageFolkEntity g : eyes) {
            if (!g.isAlive() || g.isSleeping() || g.level() != level || held(g)) continue;
            double reach = SPOT + FolkSkills.sightBonus(g) + (Pickets.on(g) ? 12 : 0);
            double d = g.distanceTo(spy);
            if (d > reach || !g.hasLineOfSight(spy)) continue;
            if (d > CLOSE && g.getRandom().nextInt(low ? 4 : 2) != 0) continue;
            return g;
        }
        return null;
    }

    @Nullable
    static VillageFolkEntity nearestFreeGuard(Villages.Village v, VillageFolkEntity spy) {
        VillageFolkEntity best = null;
        double bd = Double.MAX_VALUE;
        for (VillageFolkEntity g : Patrols.watch(v.id())) {
            if (!g.isAlive() || g.isSleeping() || CHASES.containsKey(g.getUUID()) || g.onWatch()) continue;
            double d = g.distanceToSqr(spy);
            if (d < bd) {
                bd = d;
                best = g;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the chase (the guard's own step)

    /** A guard after a spy (WarScouting.hold): run it down, or let it go. True while it is after one. */
    static boolean chase(VillageFolkEntity g, ServerLevel level, boolean think) {
        Chase c = CHASES.get(g.getUUID());
        if (c == null) return false;
        Entity e = level.getEntity(c.spy());
        Villages.Village v = Villages.get(c.village());
        if (!(e instanceof VillageFolkEntity spy) || !spy.isAlive() || v == null || held(spy) || Raids.underAlarm(c.village())) {
            CHASES.remove(g.getUUID());
            return false;
        }
        if (!think) return true;
        long now = level.getGameTime();
        if (g.distanceTo(spy) <= REACH) {
            CHASES.remove(g.getUUID());
            caught(level, v, g, spy);
            return false;
        }
        int edge = Villages.townReach(v.id()) + LET_GO;
        if (Scouts.flat(spy.blockPosition(), v.centre()) > (double) edge * edge || now - c.since() > CHASE_MOST) {
            CHASES.remove(g.getUUID());
            g.getNavigation().stop();
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Let it run. It won't be back in a hurry.", "Gone. Well, it knows we're awake."));
            Villages.tell(v.id(), level.getDayTime() / 24000L, g.displayNameCap() + " of the watch chased off a spy from "
                + Villages.name(spy.ownerId()));
            return false;
        }
        g.getNavigation().moveTo(spy, 1.35D);
        g.hobbyNow = "chasing a spy from " + Villages.name(spy.ownerId());
        return true;
    }

    /** Taken: its count lost, held at the barracks, questioned. */
    static void caught(ServerLevel level, Villages.Village v, VillageFolkEntity g, VillageFolkEntity spy) {
        long day = level.getDayTime() / 24000L;
        UUID home = spy.ownerId();
        if (home == null) return;
        String them = Villages.name(home), us = Villages.name(v.id());
        Spying.stand(spy);
        Scouts.abandon(level, spy);                       // what it counted goes nowhere now
        Riding.fell(level, spy);                          // a horse it rode out on left loose, for its stable to fetch
        if (spy.isPassenger()) spy.stopRiding();
        spy.clearQueue();
        spy.getNavigation().stop();
        Captive c = new Captive(spy.getUUID(), spy.displayNameCap(), home, v.id(), day);
        HELD.put(spy.getUUID(), c);
        Ledger.note(v.id(), "captive/" + spy.getUUID(), c.encode());
        LOST.put(home + "/" + v.id(), day);
        // Questioned: it tells what it knows of its own town, and that is ours now.
        Intel.truth(level, v.id(), home, day, "from " + spy.displayNameCap() + ", taken spying and questioned");
        Ledger.relate(v.id(), home, -6);
        Villages.tell(v.id(), day, g.displayNameCap() + " of the watch caught " + spy.displayNameCap() + " of " + them
            + " spying on the town; held at the barracks, it has told what it knows of " + them);
        Villages.tell(home, day, spy.displayNameCap() + " went to watch " + us + " and did not come back: taken by their watch");
        Scouts.report(v.id(), "The watch caught a spy from " + them + ", " + spy.displayNameCap() + ". It is held at the barracks.");
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Got you! You'll come along to the barracks.", "That's enough watching for you. This way."));
        spy.sayLater(FolkTalk.pick(spy.getRandom(), "All right, all right... I was only looking.", "Fine. I'll come quietly."), 30);
        g.persona().remember(day, "I caught a spy from " + them, 5);
        spy.persona().remember(day, "I was caught spying on " + us, 7);
        Raids.tellNear(level, v.centre(), 160, Component.literal("The watch of " + us + " caught a spy from " + them + ".")
            .withStyle(ChatFormatting.GOLD), true);
        LOG.info("[MCA-SPY] {} of {} caught {} of {}; held at {}", g.displayNameCap(), us, spy.displayNameCap(), them, holdAt(v).toShortString());
    }

    /** Where a town keeps its captives: by its barracks, else its hall, else the square. */
    static BlockPos holdAt(Villages.Village v) {
        for (String s : new String[]{ "barracks", "townhall", "hall" }) {
            BlockPos at = Villages.builtAt(v.id(), s);
            if (at != null) return at;
        }
        return v.centre().offset(3, 0, 3);
    }

    // ------------------------------------------------------------------ held (the captive's own step)

    /** A captive (WarScouting.hold): kept by the barracks of the town that took it. True while it is held. */
    static boolean hold(VillageFolkEntity f, ServerLevel level, boolean think) {
        Captive c = HELD.get(f.getUUID());
        if (c == null) return false;
        if (!think) return true;
        Villages.Village v = Villages.get(c.holder());
        if (v == null) return false;                      // (its keepers' town not known just now: its own day meanwhile)
        BlockPos at = holdAt(v);
        if (f.blockPosition().distSqr(at) > 9) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(at, 0.9D);
        } else {
            f.getNavigation().stop();
        }
        f.hobbyNow = "held at " + Villages.name(c.holder()) + "'s barracks";
        return true;
    }

    // ------------------------------------------------------------------ let go

    /** Captives whose towns are at peace with their holders again: let go, every one. */
    static void letGoAtPeace(ServerLevel level, Villages.Village v) {
        for (Captive c : captives(v.id())) {
            if (!Spying.hostile(v.id(), c.home())) release(level, c.who(), "now there is peace");
        }
    }

    /** Let one captive go home. Returns whether there was such a captive. */
    public static boolean release(ServerLevel level, UUID captive, String why) {
        load();
        Captive c = HELD.remove(captive);
        if (c == null) return false;
        Ledger.forget(c.holder(), "captive/" + captive);
        long day = level.getDayTime() / 24000L;
        String holder = Villages.name(c.holder()), home = Villages.name(c.home());
        Villages.Village back = Villages.get(c.home());
        if (level.getEntity(captive) instanceof VillageFolkEntity f && back != null) {
            Villages.Village h = Villages.get(c.holder());
            Spying.Mission m = new Spying.Mission(c.holder(), holder, h != null ? h.centre() : f.blockPosition(), "its barracks");
            m.freed = true;
            m.phase = Spying.Phase.HOME;
            Scouts.walkHome(f, back, m);
            FolkTalk.speak(f, "Home, then. I'll not forget this place in a hurry.");
        }
        Villages.tell(c.holder(), day, c.name() + " of " + home + ", taken spying, was let go home " + why);
        Villages.tell(c.home(), day, c.name() + ", held in " + holder + ", was let go " + why + ", and is on the way home");
        LOG.info("[MCA-SPY] {} of {} let go by {} ({})", c.name(), home, holder, why);
        return true;
    }

    /** The peace talks' exchange: each town's captives of the other's let go home. Returns how many went. */
    public static int exchange(ServerLevel level, UUID a, UUID b) {
        int n = 0;
        for (Captive c : captives(a)) if (c.home().equals(b) && release(level, c.who(), "in an exchange of captives")) n++;
        for (Captive c : captives(b)) if (c.home().equals(a) && release(level, c.who(), "in an exchange of captives")) n++;
        return n;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the town's captives looked over now, those of towns at peace with it let go. */
    public static void letGoForTests(ServerLevel level, Villages.Village v) {
        letGoAtPeace(level, v);
    }

    /** Tests: the guard sent after this spy, or null. */
    @Nullable
    public static UUID chaserForTests(VillageFolkEntity spy) {
        return chasedBy(spy);
    }
}
