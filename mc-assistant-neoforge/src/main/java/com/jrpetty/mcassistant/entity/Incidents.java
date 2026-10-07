package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [police] What the watch answers: things that happen, met as they happen (never looked for every tick).
 * <ul>
 * <li><b>A crime seen.</b> The victim, or a witness who saw it plainly, runs to the nearest guard (or the watch
 *     house's desk) calling for the watch, tells it what happened, and the guard runs to the scene; a culprit still in
 *     sight there with the goods on it is chased.</li>
 * <li><b>A crime in progress.</b> A guard who sees a deed done, or hears a witness cry "Stop, thief!", gives chase:
 *     the culprit runs for it through the streets, the guard after it, and either catches it by the collar (and it is
 *     arrested and walked to the cells on a lead) or loses it, and says where and who. Run off past the town's edge, it
 *     is wanted, with a bounty on it, and lies low out there.</li>
 * <li><b>A fight.</b> Two who can't abide each other, words already exchanged, now and then come to blows: the nearest
 *     guard runs to it, steps between them, pushes them apart and takes their names: a warning the first time, a fine
 *     the second, a night in the cells the third. Each is walked home.</li>
 * <li><b>The worse for drink</b> at the tavern at closing time (two drinks of an evening: Kitchen), a word and
 *     walked home; the curfew's warnings and fines; a wound seen to with a bandage out of the guard's own kit.</li>
 * <li><b>Fire and flood.</b> The watch is first to them: the nearest guard to the bell cries it, every guard not
 *     already at the buckets makes a cordon and clears the folk back, and in a flood goes from house to flooded house
 *     calling the folk out to the high ground.</li>
 * <li><b>The lost.</b> A search party has a guard at its head (SearchParties).</li>
 * </ul>
 * Each piece is a task on the guard and on the folk it concerns, run from their own ticks (Police.hold), and let go
 * when it is done, has failed, or has gone on too long.
 */
final class Incidents {

    private Incidents() {}

    enum Kind { RESPOND, CHASE, SEPARATE, HOME, AID, CORDON, EVAC, PLAYER, DRIVE, CARAVAN, REPORT, FLEE, BRAWL, WALKED, HIDE, LED }

    /** One part in an incident, a folk's own. */
    static final class Task {
        final Kind kind;
        final UUID village;
        final long since;
        @Nullable UUID other, other2, player;
        int caseId;
        @Nullable BlockPos to;
        long step = -1;
        int stage;
        String words = "", why = "";
        final List<String> streets = new ArrayList<>();
        boolean sawWell;
        double best = Double.MAX_VALUE;
        long unseen;

        Task(Kind kind, UUID village, long since) {
            this.kind = kind;
            this.village = village;
            this.since = since;
        }
    }

    /** How long a chase is run before the guard gives it up, and how far ahead the culprit gets away. */
    static final int CHASE_MOST = 900, LOSE = 30;
    /** How close a guard must be to take a fleeing culprit by the collar. */
    static final double CATCH = 2.5;
    /** How far a guard is fetched from to a report, a fight or a cry of "thief". */
    static final double CALL = 48.0;

    private static final Map<UUID, Task> TASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PATHED = new ConcurrentHashMap<>();
    /** Each town's emergency (a fire, a flood) as last looked at, and where the fire is. */
    private static final Map<UUID, Roster.Duty> EMERGENCY = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FIRE_AT = new ConcurrentHashMap<>();
    /** Cases the watch has in hand already (a chase on it): reported without a run to the watch. */
    private static final Set<Integer> QUIET = ConcurrentHashMap.newKeySet();
    /** Caravan trips seen out of town, by carrier: when. */
    private static final Map<UUID, Long> SEEN_OFF = new ConcurrentHashMap<>();
    /** Fights started, for the tests (and /village police fight). */
    static volatile boolean forceFight;

    static void resetForTests() {
        TASKS.clear();
        PATHED.clear();
        EMERGENCY.clear();
        FIRE_AT.clear();
        QUIET.clear();
        SEEN_OFF.clear();
        forceFight = false;
    }

    @Nullable
    static Task task(VillageFolkEntity f) {
        return TASKS.get(f.getUUID());
    }

    @Nullable
    static Task task(UUID f) {
        return TASKS.get(f);
    }

    private static void put(VillageFolkEntity f, Task t) {
        TASKS.put(f.getUUID(), t);
        f.clearQueue();
    }

    private static void end(VillageFolkEntity f) {
        Task t = TASKS.remove(f.getUUID());
        if (t != null) f.setSprinting(false);
    }

    private static void end(ServerLevel level, @Nullable UUID f) {
        if (f == null) return;
        VillageFolkEntity e = Civics.find(level, f);
        if (e != null) end(e);
        else TASKS.remove(f);
    }

    // ------------------------------------------------------------------ the round

    /** Every second (Police.tick): the town's emergency looked at, old tasks let go, the wanted who come home taken in. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Roster.Duty was = EMERGENCY.get(id);
        Roster.Duty now = null;
        if (FireBrigade.burningNow(id) != null) now = Roster.Duty.FIRE;
        else {
            Disasters.Flood fl = Disasters.town(id).flood;
            if (fl != null && fl.risen && !fl.draining) now = Roster.Duty.FLOOD;
        }
        if (now == null) {
            EMERGENCY.remove(id);
            FIRE_AT.remove(id);
        } else {
            EMERGENCY.put(id, now);
        }
        if (was != now && now == Roster.Duty.FLOOD) {
            Police.log(id, level.getDayTime(), "flood", "the river came up and the watch went from house to house calling the folk out", null, 0);
        }
        long gt = level.getGameTime();
        for (Map.Entry<UUID, Task> e : TASKS.entrySet()) {
            Task t = e.getValue();
            if (!t.village.equals(id)) continue;
            // Anything gone on far too long (its folk unloaded, a path that never came right) is let go.
            if (gt - t.since > (t.kind == Kind.HIDE ? 24000L * 3 : 2400L) || gt < t.since) TASKS.remove(e.getKey(), t);
        }
    }

    /** The town's emergency just now (FIRE, FLOOD), or null. */
    @Nullable
    static Roster.Duty emergency(@Nullable UUID village) {
        return village == null ? null : EMERGENCY.get(village);
    }

    // ------------------------------------------------------------------ the folk's part

    /** From Police.hold: whatever incident has this folk. What it is doing, or null. */
    @Nullable
    static String hold(VillageFolkEntity f, ServerLevel level) {
        Task t = TASKS.get(f.getUUID());
        if (t == null) return null;
        Villages.Village v = Villages.get(t.village);
        if (v == null || !t.village.equals(f.ownerId())) {
            end(f);
            return null;
        }
        // The bell: every guard to the walls, and the incident waits (a culprit got away meanwhile is chased no more).
        if (Raids.underAlarm(t.village) && f.stationTask() == AssistantEntity.StationTask.GUARD && t.kind != Kind.HIDE) {
            end(f);
            return null;
        }
        if (f.isSleeping() && t.kind != Kind.HIDE) f.stopSleeping();
        long gt = level.getGameTime();
        return switch (t.kind) {
            case REPORT -> report(level, v, f, t, gt);
            case RESPOND -> respond(level, v, f, t, gt);
            case CHASE -> chase(level, v, f, t, gt);
            case FLEE -> flee(level, v, f, t, gt);
            case SEPARATE -> separate(level, v, f, t, gt);
            case BRAWL -> brawl(level, v, f, t, gt);
            case HOME -> home(level, v, f, t, gt);
            case WALKED -> walked(level, v, f, t, gt);
            case AID -> aid(level, v, f, t, gt);
            case CORDON -> cordon(level, v, f, t, gt);
            case EVAC -> evac(level, v, f, t, gt);
            case PLAYER, DRIVE -> PlayerLaw.guardStep(level, v, f, t, gt);
            case CARAVAN -> caravanStep(level, v, f, t, gt);
            case HIDE -> hide(level, v, f, t, gt);
            case LED -> PlayerLaw.ledStep(level, v, f, t, gt);
        };
    }

    // ------------------------------------------------------------------ a crime seen: run for the watch

    /**
     * A case reported (Crime.report): the victim (or the witness who told) runs to the nearest guard with it, crying for
     * the watch, and the guard runs to the scene. A case the watch has in hand already (a chase) is not run for.
     */
    static void reported(ServerLevel level, Villages.Village v, Crime.Case c, String by) {
        if (QUIET.remove(c.id) || !Police.active()) return;
        VillageFolkEntity reporter = c.victim == null ? null : Civics.find(level, c.victim);
        if (reporter == null || !fitToRun(reporter) || reporter.getUUID().equals(c.culprit)) {
            reporter = null;
            for (Crime.Witness w : c.witnesses) {
                if (w.player || !w.name.equals(by)) continue;
                VillageFolkEntity o = Civics.find(level, w.id);
                if (o != null && fitToRun(o)) reporter = o;
                break;
            }
        }
        if (reporter == null || reporter.stationTask() == AssistantEntity.StationTask.GUARD) return;
        VillageFolkEntity g = WatchHouse.nearestGuard(level, v, reporter.blockPosition(), CALL * 1.5, null);
        if (g == null) return;
        long gt = level.getGameTime();
        Task t = new Task(Kind.REPORT, v.id(), gt);
        t.other = g.getUUID();
        t.caseId = c.id;
        t.to = c.where;
        put(reporter, t);
        reporter.setSprinting(true);
        boolean victim = reporter.getUUID().equals(c.victim);
        FolkTalk.speak(reporter, victim ? switch (c.kind) {
            case BURGLARY -> FolkTalk.pick(reporter.getRandom(), "Watch! Someone's been at my chest!", "Watch! Watch! I've been robbed!");
            case PICKPOCKET -> FolkTalk.pick(reporter.getRandom(), "Watch! My purse — somebody's had my coins!", "Thief! Watch!");
            case VANDALISM -> "Watch! Somebody's broken " + (c.brokeWhat.isEmpty() ? "it" : c.brokeWhat) + "!";
            case POACHING -> "Watch! A beast's been taken from the pen!";
            case FORGERY -> "Watch! There's a bad coin in the takings!";
            default -> "Watch! The stores have been robbed!";
        } : FolkTalk.pick(reporter.getRandom(), "Watch! I saw it — come quick!", "Watch! Over here! I saw who did it!"));
    }

    private static boolean fitToRun(VillageFolkEntity f) {
        return f.isAlive() && !f.isSleeping() && !f.isBaby() && !f.isHired() && TASKS.get(f.getUUID()) == null
            && WatchHouse.custodyOf(f.getUUID()) == null && f.trip() == null;
    }

    private static String report(ServerLevel level, Villages.Village v, VillageFolkEntity f, Task t, long gt) {
        Crime.Case c = Crime.get(t.caseId);
        VillageFolkEntity g = t.other == null ? null : Civics.find(level, t.other);
        if (c == null) {
            end(f);
            return null;
        }
        if (t.stage == 0) {
            if (g == null || gt - t.since > 600) {
                end(f);
                if (g != null) respondTo(level, v, g, c, f);
                return null;
            }
            double d = f.distanceTo(g);
            if (d > 3.0) {
                walk(f, g.blockPosition(), 1.15D);
                if (d < 12 && TASKS.get(g.getUUID()) == null) {
                    g.getNavigation().stop();
                    g.getLookControl().setLookAt(f, 30.0F, 30.0F);
                }
                return "running to the watch";
            }
            f.getNavigation().stop();
            f.setSprinting(false);
            f.getLookControl().setLookAt(g, 30.0F, 30.0F);
            FolkTalk.speak(f, Mischief.capital(c.what()) + ", at " + c.place + (c.day == level.getDayTime() / 24000L ? "" : " — on day " + c.day) + "!");
            respondTo(level, v, g, c, f);
            c.note(level.getDayTime() / 24000L, f.displayNameCap() + " ran to " + g.displayNameCap() + " of the watch with it.");
            Crime.changed();
            t.stage = 1;
            t.step = gt;
            return "telling the watch what happened";
        }
        if (gt - t.step > 300 || Mischief.near(f, c.where, 3.0)) {
            end(f);
            return null;
        }
        walk(f, c.where, 0.9D);
        return "showing the watch where it happened";
    }

    /** The guard runs to the scene of a reported case. */
    static void respondTo(ServerLevel level, Villages.Village v, VillageFolkEntity g, Crime.Case c, @Nullable VillageFolkEntity reporter) {
        if (TASKS.get(g.getUUID()) != null || WatchHouse.escorting(g) != null) return;
        Task t = new Task(Kind.RESPOND, v.id(), level.getGameTime());
        t.caseId = c.id;
        t.to = c.where;
        t.other = reporter == null ? null : reporter.getUUID();
        put(g, t);
        g.setSprinting(true);
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Show me — quick!", "I'm coming. Where?", "Right. Let's have a look."));
        Police.count(g, "responded", 1);
    }

    private static String respond(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, long gt) {
        Crime.Case c = Crime.get(t.caseId);
        if (c == null || gt - t.since > 900 || t.to == null) {
            end(g);
            return null;
        }
        if (t.stage == 0) {
            if (!Mischief.near(g, t.to, 3.0)) {
                walk(g, t.to, 1.25D);
                return "running to " + c.place;
            }
            g.getNavigation().stop();
            g.setSprinting(false);
            t.stage = 1;
            t.step = gt;
            long day = level.getDayTime() / 24000L;
            // The culprit still about, with the goods on it: after it.
            VillageFolkEntity culprit = Civics.find(level, c.culprit);
            if (culprit != null && culprit.isAlive() && culprit.distanceTo(g) <= 24 && g.hasLineOfSight(culprit) && WatchHouse.custodyOf(culprit.getUUID()) == null
                    && (carries(culprit, c.id) || Mischief.PLANS.get(culprit.getUUID()) != null && Mischief.PLANS.get(culprit.getUUID()).done == c)) {
                FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "There! " + culprit.displayNameCap() + " — stop!", "You! Stop right there!"));
                end(g);
                startChase(level, v, g, culprit, c.id, c.kind.word + " at " + c.place, true);
                return "after the culprit";
            }
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Nobody touch anything. The watch will get to the bottom of it.",
                "So this is where it happened. Leave it with us.", "Right. It'll be looked into — you have my word."));
            c.note(day, g.displayNameCap() + " of the watch came at a run when the alarm was raised, and kept the scene for the investigation.");
            Crime.changed();
            Police.log(v.id(), level.getDayTime(), "report", g.displayNameCap() + " ran to " + c.place + " when the " + c.kind.word + " was reported", g, 0);
            return "at the scene of the " + c.kind.word;
        }
        if (t.to != null) g.getLookControl().setLookAt(t.to.getX() + 0.5, t.to.getY() + 0.5, t.to.getZ() + 0.5);
        if (gt - t.step < 60) return "looking over the scene at " + c.place;
        end(g);
        return null;
    }

    private static boolean carries(VillageFolkEntity f, int caseId) {
        for (ItemStack s : f.getInventoryItems()) if (Crime.stolenCase(s) == caseId) return true;
        return false;
    }

    // ------------------------------------------------------------------ a crime in progress: the chase

    /**
     * A deed done (Mischief.commit): a guard who saw it gives chase; a witness who saw it plainly cries "Stop, thief!",
     * and the nearest guard who hears it gives chase.
     */
    static void inTheAct(ServerLevel level, Villages.Village v, VillageFolkEntity culprit, Crime.Case c) {
        if (!Police.active() || TASKS.get(culprit.getUUID()) != null) return;
        VillageFolkEntity guard = null, crier = null;
        for (Crime.Witness w : c.witnesses) {
            if (w.player) continue;
            VillageFolkEntity o = Civics.find(level, w.id);
            if (o == null || o.isSleeping()) continue;
            if (o.stationTask() == AssistantEntity.StationTask.GUARD && !o.isBaby() && w.certainty >= 45 && WatchHouse.freeFor(o)) {
                guard = o;
                break;
            }
            if (crier == null && w.certainty >= 65 && culprit.getUUID().equals(w.thinks)) crier = o;
        }
        if (guard == null && crier != null) {
            FolkTalk.speak(crier, FolkTalk.pick(crier.getRandom(), "Stop, thief!", "Thief! Stop " + culprit.displayNameCap() + "!", "Watch! Thief!"));
            guard = WatchHouse.nearestGuard(level, v, culprit.blockPosition(), 32.0, null);
        }
        if (guard == null) return;
        startChase(level, v, guard, culprit, c.id, c.kind.word + " at " + c.place, true);
    }

    /** A chase begun: the guard after the culprit, the culprit off at a run. */
    static boolean startChase(ServerLevel level, Villages.Village v, VillageFolkEntity g, VillageFolkEntity f, int caseId, String why, boolean sawIt) {
        if (TASKS.get(f.getUUID()) != null && TASKS.get(f.getUUID()).kind == Kind.FLEE) return false;
        if (WatchHouse.custodyOf(f.getUUID()) != null) return false;
        end(g);
        end(f);
        long gt = level.getGameTime();
        Task chase = new Task(Kind.CHASE, v.id(), gt);
        chase.other = f.getUUID();
        chase.caseId = caseId;
        chase.why = why;
        chase.sawWell = sawIt;
        chase.to = f.blockPosition().immutable();
        Task flee = new Task(Kind.FLEE, v.id(), gt);
        flee.other = g.getUUID();
        flee.caseId = caseId;
        put(g, chase);
        put(f, flee);
        Mischief.PLANS.remove(f.getUUID());
        g.setSprinting(true);
        f.setSprinting(true);
        if (f.isSleeping()) f.stopSleeping();
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Stop! Thief!", "Stop in the name of the watch!", f.displayNameCap() + "! Stop right there!"));
        f.sayLater(FolkTalk.pick(f.getRandom(), "Not likely!", "You'll never catch me!", "Leave me be!"), 15);
        String street = Police.streetAt(v.id(), v.centre(), f.blockPosition());
        if (street != null) chase.streets.add(street);
        return true;
    }

    private static String chase(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, long gt) {
        VillageFolkEntity f = t.other == null ? null : Civics.find(level, t.other);
        Task flee = f == null ? null : TASKS.get(f.getUUID());
        if (f == null || !f.isAlive() || flee == null || flee.kind != Kind.FLEE) {
            end(g);
            return null;
        }
        double d = g.distanceTo(f);
        t.best = Math.min(t.best, d);
        if (d <= 12 && g.hasLineOfSight(f)) t.sawWell = true;
        // A badge is the watch's authority: a culprit run close to the constable's gives itself up.
        boolean badge = g.countCarried(s -> s.is(com.jrpetty.mcassistant.item.PoliceItems.CONSTABLE_BADGE.get())) > 0;
        // Within arm's reach at a run: both sprinting, a guard on the culprit's heels trails it by two blocks or so as its
        // path catches up with where the culprit was, and a grab at 1.9 let one run clean out of the town a step ahead.
        if (d <= CATCH || badge && d <= 5.0 && g.hasLineOfSight(f)) {
            caught(level, v, g, f, t, badge && d > CATCH);
            return "has caught " + f.displayNameCap();
        }
        if (g.hasLineOfSight(f)) t.unseen = 0;
        else t.unseen += 4;
        int edge = Villages.townReach(v.id()) + 32;
        boolean fled = Math.max(Math.abs(f.getX() - v.centre().getX()), Math.abs(f.getZ() - v.centre().getZ())) > edge;
        if (gt - t.since > CHASE_MOST || d > LOSE || t.unseen > 120 && d > 12 || fled) {
            lost(level, v, g, f, t, fled);
            return null;
        }
        if (gt % 8 < 4 || g.getNavigation().isDone()) {
            double speed = 1.3D + Math.min(0.25D, g.veteranLevel() / 80.0);
            g.getNavigation().moveTo(f, speed);
        }
        g.setSprinting(true);
        if ((gt - t.since) % 80 < 4 && gt > t.since + 20) {
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Stop!", "In the name of the watch — stop!", "You can't run for ever, " + f.displayNameCap() + "!"));
        }
        if (gt % 20 < 4) {
            String street = Police.streetAt(v.id(), v.centre(), f.blockPosition());
            if (street != null && (t.streets.isEmpty() || !t.streets.get(t.streets.size() - 1).equals(street))) t.streets.add(street);
        }
        return "chasing " + f.displayNameCap();
    }

    private static String flee(ServerLevel level, Villages.Village v, VillageFolkEntity f, Task t, long gt) {
        VillageFolkEntity g = t.other == null ? null : Civics.find(level, t.other);
        Task chase = g == null ? null : TASKS.get(g.getUUID());
        if (g == null || chase == null || chase.kind != Kind.CHASE) {
            end(f);
            return null;
        }
        if (gt % 10 < 4 || f.getNavigation().isDone()) {
            double fit = f.life().has(Social.Trait.HARDWORKING) ? 0.08 : 0.0;
            double ax = f.getX() - g.getX(), az = f.getZ() - g.getZ();
            double len = Math.max(0.1, Math.sqrt(ax * ax + az * az));
            ax /= len;
            az /= len;
            // Away from the guard: straight on if the way is open, else off to one side or the other.
            boolean gone = false;
            for (double turn : new double[]{ 0.0, 0.7, -0.7, 1.4, -1.4 }) {
                double cx = ax * Math.cos(turn) - az * Math.sin(turn), cz = ax * Math.sin(turn) + az * Math.cos(turn);
                int x = (int) Math.floor(f.getX() + cx * 12), z = (int) Math.floor(f.getZ() + cz * 12);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (Math.abs(y - f.getY()) > 6) continue;
                if (f.getNavigation().moveTo(x + 0.5, y, z + 0.5, 1.05D + fit)) {
                    gone = true;
                    break;
                }
            }
            if (!gone) f.getNavigation().moveTo(f.getX() + ax * 6, f.getY(), f.getZ() + az * 6, 1.05D + fit);
        }
        f.setSprinting(true);
        return "running from the watch";
    }

    /** Caught by the collar: arrested, the case put to the council with the watch's word on it, and walked to the cells. */
    static void caught(ServerLevel level, Villages.Village v, VillageFolkEntity g, VillageFolkEntity f, Task t, boolean gaveUp) {
        end(g);
        end(f);
        g.getNavigation().stop();
        f.getNavigation().stop();
        long now = level.getDayTime(), day = now / 24000L;
        String street = t.streets.isEmpty() ? null : t.streets.get(t.streets.size() - 1);
        String down = street == null ? "" : " down " + street;
        FolkTalk.speak(g, gaveUp ? FolkTalk.pick(g.getRandom(), "That's far enough. You know what this badge means.", "Give it up, " + f.displayNameCap() + ".")
            : FolkTalk.pick(g.getRandom(), "Got you!", "Gotcha! That's the end of that.", "Not so fast!"));
        f.sayLater(gaveUp ? "...All right. All right, I give up." : FolkTalk.pick(f.getRandom(), "All right! All right — I'll come quietly.", "Let go — I'm not going anywhere!"), 20);
        Crime.Case c = Crime.get(t.caseId);
        String what = c == null ? t.why : c.kind.word + " at " + c.place;
        if (c != null && c.stage.open() && c.stage != Crime.Stage.TRIAL) {
            if (c.stage == Crime.Stage.UNNOTICED) {
                QUIET.add(c.id);
                Crime.report(level, v, c, g.displayNameCap());
            }
            boolean goods = carries(f, c.id);
            c.clues.add(new Crime.Clue("caught", "Caught in the act by " + g.displayNameCap() + " of the watch, after a chase" + down
                + (goods ? ", with " + c.goods + " on them" : "") + ".", goods ? 9.0 : 7.0, day).at(f.getUUID(), f.displayNameCap()));
            if (c.investigator == null) {
                c.investigator = g.getUUID();
                c.investigatorName = g.displayNameCap();
            }
            c.accused = f.getUUID();
            c.accusedName = f.displayNameCap();
            c.stage = Crime.Stage.ACCUSED;
            c.progressDay = day;
            c.note(day, g.displayNameCap() + " chased " + f.displayNameCap() + down + " and caught them" + (goods ? " with the goods" : "") + ".");
            Crime.changed();
        }
        WatchHouse.arrest(level, v, g, f, c == null ? t.caseId : c.id, what, -1);
        PlayerLaw.caught(v.id(), f.getUUID());
        Police.count(g, "chases", 1);
        Police.trust(v.id(), 2);
        String line = g.displayNameCap() + " caught " + f.displayNameCap() + " after a chase" + down + ": " + what;
        Police.log(v.id(), now, "chase", line, g, 0);
        Villages.tell(v.id(), day, "the watch caught " + (c == null ? f.displayNameCap() : "the " + c.title().toLowerCase(java.util.Locale.ROOT) + " culprit, "
            + f.displayNameCap() + ",") + " after a chase" + down);
        level.playSound(null, f.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.NEUTRAL, 0.7F, 1.0F);
    }

    /** Lost it: where, and who it was; the case has the guard's word for it. Run clean out of the town, it is wanted. */
    static void lost(ServerLevel level, Villages.Village v, VillageFolkEntity g, VillageFolkEntity f, Task t, boolean fled) {
        end(g);
        end(f);
        g.getNavigation().stop();
        long now = level.getDayTime(), day = now / 24000L;
        String street = t.streets.isEmpty() ? null : t.streets.get(t.streets.size() - 1);
        String down = street == null ? "" : " down " + street;
        FolkTalk.speak(g, t.sawWell ? FolkTalk.pick(g.getRandom(), "Lost them" + down + ". But I know who it was.", "Gone" + down + ". It was " + f.displayNameCap() + ", though.")
            : FolkTalk.pick(g.getRandom(), "Lost them" + down + ".", "Gone. Blast it."));
        Crime.Case c = Crime.get(t.caseId);
        if (c != null && c.stage.open() && t.sawWell) {
            if (c.stage == Crime.Stage.UNNOTICED) {
                QUIET.add(c.id);
                Crime.report(level, v, c, g.displayNameCap());
            }
            c.clues.add(new Crime.Clue("chased", g.displayNameCap() + " of the watch chased " + f.displayNameCap() + down + " from the scene and lost them.", 4.5, day)
                .at(f.getUUID(), f.displayNameCap()));
            c.note(day, "Chased " + f.displayNameCap() + down + " and lost them.");
            Crime.changed();
        }
        Police.count(g, "lost", 1);
        Police.trust(v.id(), -1);
        Police.log(v.id(), now, "lost", g.displayNameCap() + " chased " + f.displayNameCap() + down + " and lost them" + (fled ? " past the town's edge" : ""), g, 0);
        if (fled || c == null || !c.stage.open()) {
            PlayerLaw.postBounty(level, v, f, c == null ? t.caseId : c.id, c == null ? t.why : c.kind.word + " at " + c.place, fled ? "ran from the watch past the town's edge" : "ran from the watch");
            hideOut(level, v, f, g.blockPosition());
        }
    }

    /** Broke out of the cells (WatchHouse.jailbreak): a guard in sight gives chase; else it is wanted, and lies low. */
    static void escaped(ServerLevel level, Villages.Village v, VillageFolkEntity f, int caseId, String why) {
        VillageFolkEntity g = WatchHouse.nearestGuard(level, v, f.blockPosition(), 32.0, null);
        if (g != null && g.hasLineOfSight(f)) {
            startChase(level, v, g, f, caseId, why, true);
            return;
        }
        PlayerLaw.postBounty(level, v, f, caseId, why, "broke out of the cells");
        hideOut(level, v, f, v.centre());
    }

    /** Wanted: off past the fields, as far from the watch as it can get, to lie low (a bounty hunter's quarry). */
    static void hideOut(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos awayFrom) {
        double ax = f.getX() - awayFrom.getX(), az = f.getZ() - awayFrom.getZ();
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 1) {
            ax = f.getX() - v.centre().getX();
            az = f.getZ() - v.centre().getZ();
            len = Math.max(1, Math.sqrt(ax * ax + az * az));
        }
        int r = Villages.townReach(v.id()) + 24;
        int x = v.centre().getX() + (int) Math.round(ax / len * r), z = v.centre().getZ() + (int) Math.round(az / len * r);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Task t = new Task(Kind.HIDE, v.id(), level.getGameTime());
        t.to = new BlockPos(x, y, z);
        put(f, t);
        PlayerLaw.hideoutAt(v.id(), f.getUUID(), t.to);
    }

    private static String hide(ServerLevel level, Villages.Village v, VillageFolkEntity f, Task t, long gt) {
        if (!PlayerLaw.isWanted(v.id(), f.getUUID())) {
            end(f);
            return null;
        }
        if (t.to == null) return null;
        // Two days out there and it has had enough: it comes home, and the watch takes it in.
        if (PlayerLaw.givesUp(level, v.id(), f.getUUID())) {
            end(f);
            VillageFolkEntity g = WatchHouse.nearestGuard(level, v, f.blockPosition(), 200.0, null);
            FolkTalk.speak(f, "I've had enough of sleeping in hedges. I'll go back and face it.");
            if (g != null) {
                Task led = new Task(Kind.WALKED, v.id(), gt);
                led.to = g.blockPosition();
                led.other = g.getUUID();
                led.why = "surrender";
                put(f, led);
            }
            return "going back to give itself up";
        }
        if (!Mischief.near(f, t.to, 3.0)) {
            walk(f, t.to, 0.9D);
            return "keeping out of the watch's way";
        }
        f.getNavigation().stop();
        long tod = Math.floorMod(level.getDayTime(), 24000L);
        if (tod >= 13500L && tod < 23000L && f.getPose() != net.minecraft.world.entity.Pose.SITTING) f.setPose(net.minecraft.world.entity.Pose.SITTING);
        else if (tod < 13500L && f.getPose() == net.minecraft.world.entity.Pose.SITTING) f.setPose(net.minecraft.world.entity.Pose.STANDING);
        if (f.getRandom().nextInt(300) == 0) {
            double a = f.getRandom().nextDouble() * Math.PI * 2;
            f.getLookControl().setLookAt(f.getX() + Math.cos(a) * 8, f.getEyeY(), f.getZ() + Math.sin(a) * 8);
        }
        return "lying low out past the fields";
    }

    // ------------------------------------------------------------------ fights

    /**
     * Words between two who can't abide each other (VillageFolkEntity.quarrel): now and then they come to blows. More
     * often between grumps, after a drink or two, in an unhappy town or one that has lost faith in its watch; never under
     * a guard's nose.
     */
    static void quarrel(ServerLevel level, VillageFolkEntity a, VillageFolkEntity b, long day) {
        if (!Police.active()) return;
        if (a.isBaby() || b.isBaby() || a.ownerId() == null || !a.ownerId().equals(b.ownerId())) return;
        if (TASKS.get(a.getUUID()) != null || TASKS.get(b.getUUID()) != null) return;
        if (WatchHouse.custodyOf(a.getUUID()) != null || WatchHouse.custodyOf(b.getUUID()) != null) return;
        if (a.stationTask() == AssistantEntity.StationTask.GUARD || b.stationTask() == AssistantEntity.StationTask.GUARD) return;
        Villages.Village v = Villages.get(a.ownerId());
        if (v == null) return;
        for (VillageFolkEntity g : Patrols.watch(v.id())) if (!g.isSleeping() && g.distanceToSqr(a) < 8 * 8) return;
        int odds = 7;
        if (a.life().has(Social.Trait.GRUMPY)) odds--;
        if (b.life().has(Social.Trait.GRUMPY)) odds--;
        if (Beats.inDrink(a, day) || Beats.inDrink(b, day)) odds -= 2;
        if (Contentment.score(v.id()) < 40) odds--;
        if (Police.trust(v.id()) < 40) odds--;
        if (!forceFight && level.getRandom().nextInt(Math.max(2, odds)) != 0) return;
        startFight(level, v, a, b);
    }

    /** Two come to blows: the nearest guard is called to it at a run. */
    static boolean startFight(ServerLevel level, Villages.Village v, VillageFolkEntity a, VillageFolkEntity b) {
        long gt = level.getGameTime();
        Task ta = new Task(Kind.BRAWL, v.id(), gt), tb = new Task(Kind.BRAWL, v.id(), gt);
        ta.other = b.getUUID();
        tb.other = a.getUUID();
        put(a, ta);
        put(b, tb);
        FolkTalk.speak(a, FolkTalk.pick(a.getRandom(), "Right, that's it!", "Say that again. Go on!", "I've had it with you!"));
        b.sayLater(FolkTalk.pick(b.getRandom(), "Come on, then!", "You want some?", "Get your hands off me!"), 15);
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, a.getBoundingBox().inflate(12.0),
                o -> o != a && o != b && o.isAlive() && !o.isSleeping() && o.stationTask() != AssistantEntity.StationTask.GUARD)) {
            o.sayLater(FolkTalk.pick(o.getRandom(), "Fight! Fight!", "Somebody fetch the watch!", "Stop it, the pair of you!"), 20 + o.getRandom().nextInt(20));
            break;
        }
        VillageFolkEntity g = WatchHouse.nearestGuard(level, v, a.blockPosition(), CALL, null);
        if (g != null) {
            Task s = new Task(Kind.SEPARATE, v.id(), gt);
            s.other = a.getUUID();
            s.other2 = b.getUUID();
            put(g, s);
            g.setSprinting(true);
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Oi! Break it up!", "Hold it, you two!", "What's all this?"));
        }
        return true;
    }

    private static String brawl(ServerLevel level, Villages.Village v, VillageFolkEntity f, Task t, long gt) {
        VillageFolkEntity o = t.other == null ? null : Civics.find(level, t.other);
        if (o == null || !o.isAlive() || TASKS.get(o.getUUID()) == null) {
            end(f);
            return null;
        }
        if (gt - t.since > 500) {
            // Nobody came: friends pull them apart, and it is over. The town hears of it all the same.
            end(f);
            end(o);
            if (f.getUUID().compareTo(o.getUUID()) < 0) {
                Police.log(v.id(), level.getDayTime(), "disorder", f.displayNameCap() + " and " + o.displayNameCap() + " came to blows in the street; friends pulled them apart", null, 0);
            }
            return null;
        }
        f.getLookControl().setLookAt(o, 30.0F, 30.0F);
        double d = f.distanceTo(o);
        if (d > 2.0) walk(f, o.blockPosition(), 1.0D);
        else f.getNavigation().stop();
        if (d < 3.0 && (gt + (f.getId() % 7)) % 12 < 4) {
            f.swing(InteractionHand.MAIN_HAND);
            if (f.getRandom().nextInt(3) == 0) {
                o.knockback(0.25, f.getX() - o.getX(), f.getZ() - o.getZ());
                level.playSound(null, o.blockPosition(), SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.NEUTRAL, 0.6F, 1.0F);
            }
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, f.getX(), f.getY() + 2.1, f.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
        }
        return "in a fight with " + o.displayNameCap();
    }

    private static String separate(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, long gt) {
        VillageFolkEntity a = t.other == null ? null : Civics.find(level, t.other), b = t.other2 == null ? null : Civics.find(level, t.other2);
        Task ta = a == null ? null : TASKS.get(a.getUUID());
        if (a == null || b == null || ta == null || ta.kind != Kind.BRAWL || gt - t.since > 600) {
            end(g);
            return null;
        }
        Vec3 mid = a.position().add(b.position()).scale(0.5);
        BlockPos at = BlockPos.containing(mid);
        if (g.distanceToSqr(mid) > 3.0 * 3.0) {
            walk(g, at, 1.3D);
            return "running to break up a fight";
        }
        breakUp(level, v, g, a, b);
        end(g);
        return "has broken up a fight";
    }

    /** Stepped between them: pushed apart, names taken, a warning, a fine or a night in the cells; each walked home. */
    static void breakUp(ServerLevel level, Villages.Village v, VillageFolkEntity g, VillageFolkEntity a, VillageFolkEntity b) {
        end(a);
        end(b);
        g.getNavigation().stop();
        g.setSprinting(false);
        Vec3 mid = a.position().add(b.position()).scale(0.5);
        a.knockback(0.7, mid.x - a.getX(), mid.z - a.getZ());
        b.knockback(0.7, mid.x - b.getX(), mid.z - b.getZ());
        g.swing(InteractionHand.MAIN_HAND);
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "That's enough! Break it up — now!", "Apart, the pair of you! I'll have your names.",
            "Not in my streets, you don't."));
        long now = level.getDayTime(), day = now / 24000L;
        List<String> said = new ArrayList<>();
        for (VillageFolkEntity f : List.of(a, b)) {
            String out = deal(level, v, g, f, day);
            said.add(f.displayNameCap() + " " + out);
            f.persona().remember(day, "The watch broke up my fight with " + (f == a ? b : a).displayNameCap(), -3);
        }
        String street = Police.streetAt(v.id(), v.centre(), g.blockPosition());
        Police.count(g, "fights", 1);
        Police.trust(v.id(), 1);
        Police.log(v.id(), now, "fight", g.displayNameCap() + " broke up a fight between " + a.displayNameCap() + " and " + b.displayNameCap()
            + (street == null ? "" : " on " + street) + " (" + String.join("; ", said) + ")", g, 0);
        a.refreshMood();
        b.refreshMood();
    }

    /** One brawler's due: a warning, a fine of two coins, or (a third time in a fortnight) a night in the cells; then home. */
    private static String deal(ServerLevel level, Villages.Village v, VillageFolkEntity g, VillageFolkEntity f, long day) {
        net.minecraft.nbt.CompoundTag r = Police.folk(f.getUUID());
        int n = day - r.getLong("brawlDay") > 14 ? 0 : r.getInt("brawlsRecent");
        n++;
        r.putInt("brawlsRecent", n);
        r.putLong("brawlDay", day);
        r.putInt("brawls", r.getInt("brawls") + 1);
        Police.changed();
        if (n == 1) {
            f.sayLater(FolkTalk.pick(f.getRandom(), "It was them started it!", "Sorry. Sorry. I lost my temper."), 30);
            g.sayLater("A warning this time, " + f.displayNameCap() + ". Next time it's a fine.", 50);
            Police.count(g, "warnings", 1);
            walkHome(level, v, null, f, "after a fight");
            return "warned";
        }
        if (n == 2 || !WatchHouse.lockUp(level, v, g, f, "fighting in the street")) {
            int coins = n == 2 ? 2 : 4;
            fine(level, v, g, f, coins, "fighting in the street");
            g.sayLater("That's twice, " + f.displayNameCap() + ". " + coins + " coins for the disturbance.", 50);
            walkHome(level, v, null, f, "after a fight");
            return "fined " + coins;
        }
        return "locked up for the night";
    }

    /** A fine on a folk: out of its purse into the treasury, what it has not got owed out of its wages (Trial.owe). */
    static int fine(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity g, VillageFolkEntity f, int coins, String why) {
        int took = Math.min(f.purse(), coins);
        if (took > 0 && f.spend(took)) Ledger.addCoins(v.id(), took);
        else took = 0;
        if (coins - took > 0) Trial.owe(f, null, "the town", coins - took);
        net.minecraft.nbt.CompoundTag r = Police.folk(f.getUUID());
        r.putLong("finedDay", level.getDayTime() / 24000L);
        Police.changed();
        Police.count(g, "fines", 1);
        Police.log(v.id(), level.getDayTime(), "fine", f.displayNameCap() + " was fined " + coins + " for " + why + (coins - took > 0 ? " (" + (coins - took) + " owed)" : ""), g, took);
        f.refreshMood();
        return took;
    }

    // ------------------------------------------------------------------ walked home

    /** A folk sent home (after a fight, after curfew, the worse for drink); with a guard beside it, if {@code g}. */
    static void walkHome(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity g, VillageFolkEntity f, String why) {
        BlockPos home = Homes.homeOf(f);
        if (home == null) home = f.bedPos();
        if (home == null) home = v.centre();
        long gt = level.getGameTime();
        Task t = new Task(Kind.WALKED, v.id(), gt);
        t.to = home;
        t.why = why;
        if (g != null) {
            t.other = g.getUUID();
            Task h = new Task(Kind.HOME, v.id(), gt);
            h.other = f.getUUID();
            h.to = home;
            put(g, h);
            net.minecraft.nbt.CompoundTag r = Police.folk(f.getUUID());
            r.putInt("walkedHome", r.getInt("walkedHome") + 1);
            Police.changed();
        }
        put(f, t);
    }

    private static String walked(ServerLevel level, Villages.Village v, VillageFolkEntity f, Task t, long gt) {
        if (t.to == null || gt - t.since > 900) {
            end(f);
            return null;
        }
        if ("surrender".equals(t.why)) {
            VillageFolkEntity g = t.other == null ? null : Civics.find(level, t.other);
            if (g == null) {
                end(f);
                return null;
            }
            if (f.distanceTo(g) > 3.0) {
                walk(f, g.blockPosition(), 0.8D);
                return "going to give itself up to the watch";
            }
            end(f);
            FolkTalk.speak(f, "I'm the one you're after. I'll come quietly.");
            Police.log(v.id(), level.getDayTime(), "arrest", f.displayNameCap() + " came back and gave themselves up to " + g.displayNameCap(), g, 0);
            PlayerLaw.caught(v.id(), f.getUUID());
            int caseId = PlayerLaw.bountyCase(v.id(), f.getUUID());
            WatchHouse.arrest(level, v, g, f, caseId, "running from the watch", -1);
            return null;
        }
        if (Mischief.near(f, t.to, 3.0)) {
            end(f);
            return null;
        }
        VillageFolkEntity g = t.other == null ? null : Civics.find(level, t.other);
        walk(f, t.to, g != null ? 0.6D : 0.8D);
        return "on its way home" + (g != null ? ", the watch beside it" : "") + (t.why.isEmpty() ? "" : ", " + t.why);
    }

    private static String home(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, long gt) {
        VillageFolkEntity f = t.other == null ? null : Civics.find(level, t.other);
        Task w = f == null ? null : TASKS.get(f.getUUID());
        if (f == null || w == null || w.kind != Kind.WALKED || gt - t.since > 900) {
            end(g);
            if (f != null && t.to != null && Mischief.near(f, t.to, 4.0)) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "In you go. Goodnight.", "Home safe. Sleep it off."));
            return null;
        }
        // Beside it, a step behind, as it goes.
        double d = g.distanceTo(f);
        if (d > 2.5) walk(g, f.blockPosition(), 0.75D);
        else g.getNavigation().stop();
        return "walking " + f.displayNameCap() + " home";
    }

    // ------------------------------------------------------------------ first aid

    /** A hurt folk seen on the beat: the guard goes to it and binds the wound, or helps it to the infirmary. */
    static boolean aid(ServerLevel level, Villages.Village v, VillageFolkEntity g, VillageFolkEntity f) {
        if (TASKS.get(g.getUUID()) != null || TASKS.get(f.getUUID()) != null) return false;
        Task t = new Task(Kind.AID, v.id(), level.getGameTime());
        t.other = f.getUUID();
        put(g, t);
        return true;
    }

    private static String aid(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, long gt) {
        VillageFolkEntity f = t.other == null ? null : Civics.find(level, t.other);
        if (f == null || !f.isAlive() || gt - t.since > 600) {
            end(g);
            return null;
        }
        if (g.distanceTo(f) > 2.5) {
            walk(g, f.blockPosition(), 1.1D);
            return "going to see to " + f.displayNameCap();
        }
        end(g);
        g.getNavigation().stop();
        g.getLookControl().setLookAt(f, 30.0F, 30.0F);
        long day = level.getDayTime() / 24000L;
        String how;
        if (g.removeMatching(Kitchen.BANDAGE, 1) == 1 || Crafts.take(level, v, Kitchen.BANDAGE, 1)) {
            com.jrpetty.mcassistant.item.BandageItem.bind(f);
            g.swing(InteractionHand.MAIN_HAND);
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Hold still — there. That'll stop the bleeding.", "Let me bind that for you. There."));
            how = "bound " + f.displayNameCap() + "'s wound with a bandage";
        } else {
            ItemStack remedy = Crafts.takeOne(level, v, s -> s.is(Items.HONEY_BOTTLE) || s.is(Items.SWEET_BERRIES) || s.is(Items.BREAD));
            if (!remedy.isEmpty()) {
                f.heal(remedy.is(Items.HONEY_BOTTLE) ? 4.0F : 2.0F);
                if (remedy.is(Items.HONEY_BOTTLE)) Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));
                FolkTalk.speak(g, "Here, get this down you. You'll mend.");
                how = "saw to " + f.displayNameCap() + " with " + Crafts.named(remedy.copy()) + " out of the stores";
            } else {
                FolkTalk.speak(g, "Lean on me. We'll get you to the healer.");
                how = "helped " + f.displayNameCap() + " on its way to be seen to";
                Ledger.Building inf = Infirmary.of(v.id());
                if (inf != null) walkHome(level, v, g, f, "to the infirmary");
            }
        }
        f.sayLater(FolkTalk.pick(f.getRandom(), "Thank you. I'm much obliged to the watch.", "Bless you. That's better already."), 40);
        f.life().feel(g.getUUID(), g.displayNameCap(), 8);
        Police.folk(f.getUUID()).putLong("helpedDay", day);
        Police.count(g, "helped", 1);
        Police.trust(v.id(), 1);
        Police.log(v.id(), level.getDayTime(), "help", g.displayNameCap() + " " + how, g, 0);
        f.refreshMood();
        return null;
    }

    // ------------------------------------------------------------------ fire and flood

    /**
     * A new fire (FireBrigade.alarm): the watch is first to it. The guard nearest the bell cries it (the bell is rung for
     * the brigade), and every guard not already one of its hands is sent to make a cordon: the folk kept back out of the
     * buckets' way. Returns the guard that cries it, or the town's own crier.
     */
    @Nullable
    static VillageFolkEntity fire(ServerLevel level, Villages.Village v, BlockPos at, String where, BlockPos bell, @Nullable VillageFolkEntity crier) {
        if (!Police.active() || Patrols.watch(v.id()).isEmpty()) return crier;
        FIRE_AT.put(v.id(), at.immutable());
        EMERGENCY.put(v.id(), Roster.Duty.FIRE);
        VillageFolkEntity first = null;
        double bd = 48.0 * 48.0;
        for (VillageFolkEntity g : Patrols.watch(v.id())) {
            if (g.isSleeping() || !g.isAlive()) continue;
            double d = g.blockPosition().distSqr(bell);
            if (d < bd) { bd = d; first = g; }
        }
        for (VillageFolkEntity g : Patrols.watch(v.id())) {
            if (FireBrigade.onIt(g) || !WatchHouse.freeFor(g)) continue;
            cordon(level, v, g, at);
        }
        Police.log(v.id(), level.getDayTime(), "fire", "fire " + where + ": the watch rang the bell and cleared the folk back for the buckets", first, 0);
        return first != null ? first : crier;
    }

    /** A guard sent to keep the folk back from a fire. */
    static void cordon(ServerLevel level, Villages.Village v, VillageFolkEntity g, BlockPos fire) {
        Task t = new Task(Kind.CORDON, v.id(), level.getGameTime());
        t.to = fire.immutable();
        put(g, t);
        g.setSprinting(true);
    }

    /** The emergency duty with no task yet (Police.duty): a cordon at the fire, or the flooded houses. */
    static boolean toTheEmergency(VillageFolkEntity g, ServerLevel level, Villages.Village v) {
        Roster.Duty d = EMERGENCY.get(v.id());
        if (d == Roster.Duty.FIRE) {
            BlockPos at = FIRE_AT.get(v.id());
            if (at == null || FireBrigade.onIt(g)) return false;
            cordon(level, v, g, at);
            return true;
        }
        if (d == Roster.Duty.FLOOD) {
            Task t = new Task(Kind.EVAC, v.id(), level.getGameTime());
            put(g, t);
            return true;
        }
        return false;
    }

    private static String cordon(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, long gt) {
        if (EMERGENCY.get(v.id()) != Roster.Duty.FIRE || t.to == null) {
            end(g);
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "It's out. Back to it, everyone.", "That's the last of it. Well done, all."));
            return null;
        }
        BlockPos fire = FIRE_AT.getOrDefault(v.id(), t.to);
        double d = Math.sqrt(g.blockPosition().distSqr(fire));
        if (d > 7.0) {
            walk(g, fire, 1.25D);
            return "running to the fire";
        }
        g.setSprinting(false);
        if (d < 4.0) {
            double ax = g.getX() - fire.getX(), az = g.getZ() - fire.getZ(), len = Math.max(0.1, Math.sqrt(ax * ax + az * az));
            g.getNavigation().moveTo(fire.getX() + ax / len * 6, g.getY(), fire.getZ() + az / len * 6, 0.9D);
        } else {
            g.getNavigation().stop();
        }
        if ((gt - t.since) % 40 < 4) {
            int moved = 0;
            for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(fire).inflate(8.0),
                    o -> o.isAlive() && !o.isSleeping() && o.stationTask() != AssistantEntity.StationTask.GUARD && !FireBrigade.onIt(o)
                        && v.id().equals(o.ownerId()))) {
                double ox = o.getX() - fire.getX(), oz = o.getZ() - fire.getZ(), ol = Math.max(0.1, Math.sqrt(ox * ox + oz * oz));
                o.getNavigation().moveTo(fire.getX() + ox / ol * 14, o.getY(), fire.getZ() + oz / ol * 14, 1.0D);
                moved++;
            }
            if (moved > 0) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Stand back! Let the buckets through!", "Clear the way! Back, everybody!",
                "Get back from it — you'll be burnt!"));
            else if (g.getRandom().nextInt(3) == 0) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Keep that water coming!", "Faster with the buckets!", "Mind the roof!"));
        }
        g.getLookControl().setLookAt(fire.getX() + 0.5, fire.getY() + 1.0, fire.getZ() + 0.5);
        return "keeping the folk back from the fire";
    }

    private static String evac(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, long gt) {
        Disasters.Flood fl = Disasters.town(v.id()).flood;
        if (EMERGENCY.get(v.id()) != Roster.Duty.FLOOD || fl == null) {
            end(g);
            return null;
        }
        if (t.to == null) {
            // The next flooded house, the guards each to a different one in turn.
            List<BlockPos> homes = new ArrayList<>();
            for (Long l : fl.homes) homes.add(BlockPos.of(l));
            if (homes.isEmpty()) homes.add(v.centre());
            int k = Math.floorMod(g.getUUID().hashCode() + t.stage, homes.size());
            t.to = homes.get(k);
            t.step = gt;
        }
        if (!Mischief.near(g, t.to, 3.0) && gt - t.step < 400) {
            walk(g, t.to, 1.2D);
            return "going from house to house in the flood";
        }
        if (t.stage % 2 == 0) {
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Out! The river's up — make for the high ground!", "Everybody out! Up to the square, quick now!",
                "Leave it! Get yourselves up the hill!"));
            for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, g.getBoundingBox().inflate(8.0),
                    o -> o.isAlive() && o.stationTask() != AssistantEntity.StationTask.GUARD && v.id().equals(o.ownerId()))) {
                if (o.isSleeping()) o.stopSleeping();
                o.getNavigation().moveTo(v.centre().getX() + 0.5, v.centre().getY(), v.centre().getZ() + 0.5, 1.0D);
            }
            Police.count(g, "helped", 1);
        }
        t.stage++;
        t.to = null;
        return "calling the folk out of the flood";
    }

    // ------------------------------------------------------------------ the lost

    /** A search party (SearchParties.start): a guard at its head, if one is free by day. */
    static void leadSearch(ServerLevel level, Villages.Village v, List<UUID> party) {
        if (level.isNight() || !Police.active()) return;
        for (UUID u : party) {
            VillageFolkEntity f = Civics.find(level, u);
            if (f != null && f.stationTask() == AssistantEntity.StationTask.GUARD) return;
        }
        VillageFolkEntity best = null;
        for (VillageFolkEntity g : Patrols.watch(v.id())) {
            if (!WatchHouse.freeFor(g) || Civics.busy(g)) continue;
            Roster.Duty d = Roster.dutyOf(level, g);
            if (d == Roster.Duty.WALLS || d == Roster.Duty.DESK) continue;
            if (best == null || d == Roster.Duty.BEAT) best = g;
        }
        if (best == null) return;
        party.add(0, best.getUUID());
        Police.count(best, "helped", 1);
        Police.log(v.id(), level.getDayTime(), "search", best.displayNameCap() + " of the watch led the search party", best, 0);
    }

    // ------------------------------------------------------------------ escort and events

    /** ESCORT duty with no prisoner to walk: a caravan leaving town is seen safe to its edge. True while it has one. */
    static boolean caravan(VillageFolkEntity g, ServerLevel level, Villages.Village v) {
        long gt = level.getGameTime();
        int reach = Villages.townReach(v.id());
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity c) || c.trip() == null || c.trip().homeward()) continue;
            Long seen = SEEN_OFF.get(c.getUUID());
            if (seen != null && gt - seen < 24000L && gt >= seen) continue;
            double out = Math.max(Math.abs(c.getX() - v.centre().getX()), Math.abs(c.getZ() - v.centre().getZ()));
            if (out > reach + 8 || c.distanceTo(g) > 64) continue;
            SEEN_OFF.put(c.getUUID(), gt);
            Task t = new Task(Kind.CARAVAN, v.id(), gt);
            t.other = c.getUUID();
            put(g, t);
            FolkTalk.speak(g, "I'll see you to the edge of town, " + c.displayNameCap() + ".");
            return true;
        }
        return false;
    }

    private static String caravanStep(ServerLevel level, Villages.Village v, VillageFolkEntity g, Task t, long gt) {
        VillageFolkEntity c = t.other == null ? null : Civics.find(level, t.other);
        int reach = Villages.townReach(v.id());
        if (c == null || c.trip() == null || gt - t.since > 1200) {
            end(g);
            return null;
        }
        double out = Math.max(Math.abs(c.getX() - v.centre().getX()), Math.abs(c.getZ() - v.centre().getZ()));
        if (out > reach + 16) {
            end(g);
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Safe road, " + c.displayNameCap() + "!", "Mind how you go. Back by the bell!"));
            Police.log(v.id(), level.getDayTime(), "escort", g.displayNameCap() + " saw the caravan safe out of town", g, 0);
            return null;
        }
        if (g.distanceTo(c) > 3.0) walk(g, c.blockPosition(), 1.0D);
        else g.getNavigation().stop();
        return "seeing the caravan out of town";
    }

    /**
     * An event's crowd, with the guards on event duty posted round it (Police.hold, before the gathering takes its folk):
     * four places on a ring about it, facing in, keeping it orderly. What it is doing, or null when no gathering is on.
     */
    @Nullable
    static String eventPost(VillageFolkEntity g, ServerLevel level) {
        UUID id = g.ownerId();
        if (id == null || g.isBaby() || g.isSleeping() || Raids.underAlarm(id)) return null;
        if (TASKS.get(g.getUUID()) != null || WatchHouse.escorting(g) != null) return null;
        if (g.getTarget() != null && g.getTarget().isAlive()) return null;
        Assemblies.Assembly a = Assemblies.current(id);
        if (a == null || a.phase == Assemblies.Phase.DISPERSE || a.kind == Assemblies.Kind.WATCH) return null;
        Roster.Duty d = Roster.dutyOf(level, g);
        if (d != Roster.Duty.EVENT) return null;
        Villages.Village v = Villages.get(id);
        if (v == null) return null;
        List<VillageFolkEntity> on = Roster.on(level, v, Roster.Duty.EVENT);
        int k = Math.max(0, on.indexOf(g));
        Direction side = Direction.from2DDataValue(k % 4);
        BlockPos spot = a.focus.relative(side, 9);
        spot = new BlockPos(spot.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ()), spot.getZ());
        if (!Mischief.near(g, spot, 1.5)) {
            walk(g, spot, 0.9D);
            return "taking up a post at the gathering";
        }
        g.getNavigation().stop();
        g.getLookControl().setLookAt(a.focus.getX() + 0.5, a.focus.getY() + 1.5, a.focus.getZ() + 0.5);
        if (g.getRandom().nextInt(400) == 0) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Make way there. Mind the little ones at the front.",
            "Nice and orderly, everyone.", "No pushing. There's room for all."));
        return "keeping order at the gathering";
    }

    /**
     * EVENT duty with no gathering on: the bank's door at night when its vault holds a lot, the stores on market day,
     * the square on a festival's or an election's day. True while it is posted.
     */
    static boolean guardThings(VillageFolkEntity g, ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        BlockPos spot = null;
        String what = null;
        if (level.isNight() && Bank.cash(id) >= Police.BANK_GUARD) {
            Ledger.Building bank = Villages.builtStructure(id, "bank");
            if (bank != null) {
                spot = WatchHouse.at(bank, 0, 0, -6);
                what = "guarding the bank's door: its vault holds " + Bank.cash(id) + " coins";
            }
        } else if (!level.isNight() && Market.marketDay(id, day)) {
            BlockPos depot = Villages.depot(level, id);
            if (depot != null) {
                spot = depot.relative(Direction.SOUTH, 3);
                what = "keeping an eye on the stores on market day";
                Beats.inspect(level, v, g);
            }
        } else if (!level.isNight() && (Festivals.today(id, day) != null || Elections.countsToday(id, day) || Referendums.gatheringDue(id, day))) {
            spot = v.centre().relative(Direction.EAST, 6);
            what = "posted on the square for the day's doings";
        }
        if (spot == null) return false;
        spot = new BlockPos(spot.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ()), spot.getZ());
        if (!Mischief.near(g, spot, 1.5)) walk(g, spot, 0.8D);
        else g.getNavigation().stop();
        g.brain(what);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    static void walk(VillageFolkEntity f, BlockPos to, double speed) {
        Integer last = PATHED.get(f.getUUID());
        if (last == null || f.tickCount - last > 20 || f.tickCount < last || f.getNavigation().isDone()) {
            f.walkTo(to, speed);
            PATHED.put(f.getUUID(), f.tickCount);
        }
    }

    /** Tests: a task put on a folk as the watch would (a chase, a fight's two). */
    static void putForTests(VillageFolkEntity f, Task t) {
        put(f, t);
    }

    @Nullable
    static Player playerOf(ServerLevel level, @Nullable UUID id) {
        return id == null ? null : level.getPlayerByUUID(id);
    }

    @Nullable
    static ServerPlayer serverPlayer(ServerLevel level, @Nullable UUID id) {
        return id == null ? null : level.getServer().getPlayerList().getPlayer(id);
    }

    static Task newTask(Kind kind, UUID village, long since) {
        return new Task(kind, village, since);
    }

    static void putTask(VillageFolkEntity f, Task t) {
        put(f, t);
    }

    static void endTask(VillageFolkEntity f) {
        end(f);
    }
}
