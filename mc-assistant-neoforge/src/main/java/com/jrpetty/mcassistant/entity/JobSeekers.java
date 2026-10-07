package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The job market between towns: the folk's side of it (the towns' side is {@link JobMarket}).
 *
 * <p><b>A reason to move.</b> A folk only goes looking with a reason: out of work, or idle at a
 * trade its town has more hands at than it needs; paid less than a notice elsewhere offers (by what
 * that town's paydays really pay, against what its own really do); unhappy at home; family living in
 * the other town; or young, with no trade much learned yet, wanting a start. Each counts for so much,
 * and it takes enough of them to go: more to go over to a rival, more again if its own town is short
 * of its trade ("they need me here"). Folk with no reason stay where they are. The elder never goes,
 * nor the builder leading a build, nor a newcomer not five days settled, nor one waiting to hear.
 *
 * <p><b>At the board.</b> On its free time — its break, the evening before bed, the day of rest, or
 * any time if it has no work — a folk with a reason walks to its own town's board, stands and reads
 * the notices ("Wanted, a miner in Oakhollow, five a day…"), and if one suits it (its years within
 * what the notice asks, near enough the level asked) it puts its name down: booked as an application
 * to that town. Out of work, it goes once in a few days to look, whatever is up.
 *
 * <p><b>Taken on.</b> Told yes, it says so, walks to the square, says its goodbyes (its friends see
 * it off), and goes: what it carries of the village's goods back into the stores, its own things out
 * of its house's chest, off the old town's roll and onto the new one's, and off down the road (the
 * way the caravans go) with its partner and children if it has them, the ground round it kept awake
 * as it walks. The new town pays its road money, a coin a hundred blocks. At the other end it is
 * found a home (an empty house for the household, or a bed at the camp), takes up the trade it was
 * taken on for, and the town's chronicle and its card say where it came from and why. Told no, it
 * says so too.
 *
 * <p><b>Refugees</b> take the same road, sent by JobMarket.raided, and are given work as they fit.
 *
 * <p>A journey is written down in the new town's notes, so a folk on the road when the world was
 * saved takes it up again from where it stands.
 */
public final class JobSeekers {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private JobSeekers() {}

    /** How often a folk thinks about the board (ticks). */
    static final long THINK = 200L;
    /** How long it stands reading the notices. */
    static final int READ = 80;
    /** How long a visit to the board may take, all told. */
    static final int VISIT_MOST = 1600;
    /** Out of work, it goes to look once in so many days whatever is up. */
    static final long LOOK_EVERY = 3;
    /**
     * How long a folk with a trade can go without ground to work it at (no wood to cut, no seam to
     * dig, every search for a plot come to nothing) before it counts as out of work: two minutes, so
     * a hand between one plot and the next, or just taking up its trade, does not.
     */
    static final long NO_GROUND_FOR = 2400L;

    /** A reason to move, what it counts for, and how the folk would put it. */
    public enum Why {
        OUT_OF_WORK("out of work", "for work", 3),
        IDLE("little to do at its trade at home", "for work", 2),
        WAGES("better pay", "for the wages", 2),
        UNHAPPY("unhappy at home", "for a fresh start", 2),
        FAMILY("family there", "to be near its family", 3),
        START("young, wanting a start", "for a start in life", 1),
        // [war-peace] Worn out by a long war at home (WarAndPeace.weariness).
        WAR_WEARY("sick of the war at home", "to get away from the war", 2);

        public final String words, because;
        public final int weight;

        Why(String words, String because, int weight) {
            this.words = words;
            this.because = because;
            this.weight = weight;
        }
    }

    /** A folk's reasons for one notice: what they are, how strong all told, in words, and the one it would give. */
    public record Reasons(List<Why> why, int strength, String words, String because) {
        static final Reasons NONE = new Reasons(List.of(), 0, "", "");
    }

    /** A walk to the board, to read the notices. */
    static final class Visit {
        final long started;
        final boolean forced;
        @Nullable BlockPos spot;
        int walkTick = -1000, readFrom = -1;

        Visit(long started, boolean forced) {
            this.started = started;
            this.forced = forced;
        }
    }

    /** Taken on elsewhere: its goodbyes, before it goes. */
    static final class Leaving {
        /** The town it was taken on from (if it is somewhere else by the time it goes, it does not). */
        final UUID home;
        final UUID town;
        final int opening;
        @Nullable final StationTask trade;
        final String because;
        final int started;
        int walkTick = -1000;
        boolean said;

        Leaving(UUID home, UUID town, int opening, @Nullable StationTask trade, String because, int started) {
            this.home = home;
            this.town = town;
            this.opening = opening;
            this.trade = trade;
            this.because = because;
            this.started = started;
        }
    }

    /** On the road to a new home. */
    static final class Journey {
        final UUID from, to, group;
        final String fromName;
        final List<BlockPos> way;
        @Nullable final StationTask trade;
        final String because;
        final boolean refugee, head;
        /** The household's grown names, for the head to tell at the other end. */
        final List<String> party = new ArrayList<>();
        int at, walkTick = -1000, gainedTick, spokeTick;
        double best = Double.MAX_VALUE;
        @Nullable BlockPos window;
        long lastStep, day;

        Journey(UUID from, UUID to, UUID group, String fromName, List<BlockPos> way, @Nullable StationTask trade, String because,
                boolean refugee, boolean head) {
            this.from = from;
            this.to = to;
            this.group = group;
            this.fromName = fromName;
            this.way = way;
            this.trade = trade;
            this.because = because;
            this.refugee = refugee;
            this.head = head;
        }
    }

    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();
    private static final Map<UUID, Leaving> LEAVING = new ConcurrentHashMap<>();
    private static final Map<UUID, Journey> JOURNEYS = new ConcurrentHashMap<>();
    /** What a folk has to say when next it is about: the answer to its application. */
    private static final Map<UUID, String> TOLD = new ConcurrentHashMap<>();
    /** When each folk next thinks about the board, and the day it last went to look. */
    private static final Map<UUID, Long> NEXT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** Folk sent to look at the board now, whatever the hour (the command, the tests). */
    private static final Set<UUID> FORCED = ConcurrentHashMap.newKeySet();
    /** Folk an operator sent to read the board, reason or none (they apply only with one). */
    private static final Set<UUID> SENT = ConcurrentHashMap.newKeySet();
    /** Since when each folk with a trade has had no ground to work it at (looked at as it thinks about the board). */
    private static final Map<UUID, Long> NO_GROUND = new ConcurrentHashMap<>();

    public static void resetForTests() {
        VISITS.clear();
        LEAVING.clear();
        JOURNEYS.clear();
        TOLD.clear();
        NEXT.clear();
        LOOKED.clear();
        FORCED.clear();
        SENT.clear();
        NO_GROUND.clear();
    }

    /** Off to read the notices at its next step, whatever the hour (it still goes only with a reason). */
    public static void lookNow(VillageFolkEntity f) {
        FORCED.add(f.getUUID());
        NEXT.remove(f.getUUID());
        LOOKED.remove(f.getUUID());
    }

    /**
     * Sent to read the board by an operator (/village jobs look), reason or none: it walks there and
     * reads the notices out, and puts its name down only if one is for it and it has a reason to go.
     */
    public static void sendToBoard(VillageFolkEntity f) {
        SENT.add(f.getUUID());
        lookNow(f);
    }

    // ------------------------------------------------------------------ what other parts ask

    /** Is it about the market's business (at the board, saying goodbye, on the road)? Its own work waits (VillageFolkEntity.onBreak). */
    public static boolean busy(VillageFolkEntity f) {
        UUID me = f.getUUID();
        return JOURNEYS.containsKey(me) || VISITS.containsKey(me) || LEAVING.containsKey(me);
    }

    /** Is it on the road to a new home? */
    public static boolean travelling(VillageFolkEntity f) {
        return JOURNEYS.containsKey(f.getUUID());
    }

    /** Reading the notices at the board just now? */
    public static boolean atTheBoard(VillageFolkEntity f) {
        Visit v = VISITS.get(f.getUUID());
        return v != null && v.readFrom >= 0;
    }

    /** Hired hands on the road to this town, by the trade they were taken on for. */
    static Map<StationTask, Integer> comingTo(UUID town) {
        Map<StationTask, Integer> out = new EnumMap<>(StationTask.class);
        for (Journey j : JOURNEYS.values()) if (j.to.equals(town) && j.trade != null && j.trade != StationTask.NONE) out.merge(j.trade, 1, Integer::sum);
        return out;
    }

    /** "Fen (a miner) from Riverford": who is on the road here. */
    static List<String> onTheRoadTo(UUID town) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Journey> e : JOURNEYS.entrySet()) {
            Journey j = e.getValue();
            if (!j.to.equals(town) || !j.head) continue;
            String who = j.party.isEmpty() ? "a newcomer" : JobMarket.join(j.party);
            out.add(who + (j.trade == null || j.trade == StationTask.NONE ? "" : " (" + JobMarket.a(JobMarket.noun(j.trade)) + ")")
                + " from " + j.fromName + (j.refugee ? ", homeless after the raid" : ""));
        }
        return out;
    }

    /** The card's line while it is on the road, or null. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        Journey j = JOURNEYS.get(f.getUUID());
        if (j != null) {
            return "On the road from " + j.fromName + " to " + Villages.name(j.to)
                + (j.trade == null || j.trade == StationTask.NONE ? "" : ", to work as " + JobMarket.a(JobMarket.noun(j.trade)))
                + (j.refugee ? " (homeless after the raid)" : "");
        }
        Leaving l = LEAVING.get(f.getUUID());
        if (l != null) return "Taken on by " + Villages.name(l.town) + (l.trade == null ? "" : " as " + JobMarket.a(JobMarket.noun(l.trade)))
            + "; saying its goodbyes";
        return null;
    }

    /** What it is about just now, for the top of the talk screen: "Reading the notices on the board", or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        UUID me = f.getUUID();
        Journey j = JOURNEYS.get(me);
        if (j != null) return "On the road to " + Villages.name(j.to) + (j.refugee ? ", to be taken in" : ", to a new place");
        Leaving l = LEAVING.get(me);
        if (l != null) return "Saying its goodbyes: off to " + Villages.name(l.town);
        Visit v = VISITS.get(me);
        if (v != null) return v.readFrom >= 0 ? "Reading the notices on the board" : "Off to read the notices on the board";
        return null;
    }

    /** The answer to an application, for the folk to say when next it is about. */
    static void told(UUID folk, String line) {
        TOLD.put(folk, line);
    }

    @Nullable
    static VillageFolkEntity live(ServerLevel level, UUID id) {
        return level.getEntity(id) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    // ------------------------------------------------------------------ the step (VillageFolkEntity.aiStep, every tick)

    /** Every tick: true while it is about the market's business (the rest of its day waits). */
    public static boolean step(VillageFolkEntity f, ServerLevel level) {
        if (f.tickCount % 4 != 2) return busy(f);                 // the business itself, every fourth tick
        UUID me = f.getUUID();
        Journey j = JOURNEYS.get(me);
        if (j != null) return travel(f, level, j);
        Leaving l = LEAVING.get(me);
        if (l != null) return leaving(f, level, l);
        if (f.ownerId() == null || f.isShowcase() || f.isHired()) return false;
        Visit v = VISITS.get(me);
        if (v != null) return visit(f, level, v);
        String said = TOLD.get(me);
        if (said != null && !f.isSleeping()) {
            TOLD.remove(me);
            FolkTalk.speak(f, said);
        }
        long now = level.getGameTime();
        Long next = NEXT.get(me);
        if (next != null && now < next && now >= next - THINK * 2) return false;
        NEXT.put(me, now + THINK + Math.floorMod(me.hashCode(), 40));
        noteGround(f, now);
        if (resume(f, level)) return true;                         // a child on the road too, after a restart
        if (f.isBaby()) return false;
        if (offered(f, level)) return true;
        return consider(f, level);
    }

    // ------------------------------------------------------------------ reasons

    /**
     * Out of work: no trade at all, or a trade it has had no ground to work at for two minutes and
     * more (a woodcutter with no wood within reach, a miner with no hill to dig: the village's shape
     * gave it the trade, but there is no living in it here).
     */
    public static boolean outOfWork(VillageFolkEntity f) {
        if (f.isBaby()) return false;
        if (f.stationTask() == StationTask.NONE) return true;
        Long since = NO_GROUND.get(f.getUUID());
        long now = f.level().getGameTime();
        return since != null && f.workZone() == null && now >= since && now - since >= NO_GROUND_FOR;
    }

    /** As it thinks about the board: has it ground for its trade, and if not, since when has it had none? */
    private static void noteGround(VillageFolkEntity f, long now) {
        if (!f.isBaby() && f.stationTask() != StationTask.NONE && f.workZone() == null) {
            Long since = NO_GROUND.get(f.getUUID());
            if (since == null || since > now) NO_GROUND.put(f.getUUID(), now);
        } else {
            NO_GROUND.remove(f.getUUID());
        }
    }

    /** Its reasons to take this notice, in another town. */
    public static Reasons reasons(VillageFolkEntity f, UUID town, JobMarket.Opening o) {
        UUID home = f.ownerId();
        if (home == null) return Reasons.NONE;
        if (Wars.atWar(home, town)) return Reasons.NONE;                   // [war-peace] nobody goes over to the enemy
        List<Why> why = new ArrayList<>();
        StationTask mine = f.stationTask();
        boolean noGround = mine != StationTask.NONE && outOfWork(f);
        if (mine == StationTask.NONE || noGround) why.add(Why.OUT_OF_WORK);
        else if (Villages.overStaffed(home, mine)) why.add(Why.IDLE);
        int now = JobMarket.paidNow(f), there = JobMarket.likely(o.wage, town);
        boolean better = there >= now + 1 && there * 4 >= now * 5;
        if (better) why.add(Why.WAGES);
        if (f.persona().mood() < 30 && Contentment.score(home) < 40) why.add(Why.UNHAPPY);
        String kin = kinIn(f, town);
        if (kin != null) why.add(Why.FAMILY);
        if (f.ageYears() <= 24 && FolkSkills.bestLevel(f) < 5) why.add(Why.START);
        if (WarAndPeace.wearyOfWar(home)) why.add(Why.WAR_WEARY);           // [war-peace]
        if (why.isEmpty()) return Reasons.NONE;
        int strength = 0;
        for (Why w : why) strength += w.weight;
        if (better && there >= now * 2 && now > 0) strength++;                // twice the pay speaks louder
        List<String> words = new ArrayList<>();
        for (Why w : why) {
            words.add(switch (w) {
                case OUT_OF_WORK -> noGround ? "out of work (no ground for its " + JobMarket.noun(mine) + "'s work here)" : w.words;
                case WAGES -> "better pay (" + there + " a day against " + now + ")";
                case FAMILY -> "family there (" + kin + ")";
                default -> w.words;
            });
        }
        // The reason it would give: family first, then work, then the pay, then the rest.
        Why first = why.contains(Why.FAMILY) ? Why.FAMILY : why.contains(Why.OUT_OF_WORK) ? Why.OUT_OF_WORK
            : why.contains(Why.WAGES) ? Why.WAGES : why.get(0);
        return new Reasons(List.copyOf(why), strength, String.join("; ", words), first.because);
    }

    /** How strong its reasons must be to go to this town: more for a rival, more if its own town needs it. */
    static int needed(VillageFolkEntity f, JobMarket.Link link) {
        return link.need + (JobMarket.neededAtHome(f) ? 2 : 0);
    }

    /** Does the notice suit it, as it reads it: its years near enough, its level near enough what is asked? */
    static boolean suits(VillageFolkEntity f, JobMarket.Opening o) {
        int age = f.ageYears();
        if (age > o.ageHi + 15 || age < o.ageLo - 4) return false;              // it knows better than to try
        StationTask t = o.task();
        int lv = t == null ? 0 : f.tradeLevel(t);
        if (o.minLevel <= 1 || lv >= o.minLevel - 1) return true;
        // A learner tries for a place that asks for little, if it has no trade to speak of.
        return o.minLevel <= 2 && (outOfWork(f) || f.ageYears() <= 24);
    }

    /** The best notice for it among those heard of here, with its reasons; or null. */
    @Nullable
    static Choice best(VillageFolkEntity f, long day) {
        UUID home = f.ownerId();
        if (home == null) return null;
        Choice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (JobMarket.Seen s : JobMarket.seen(home, day)) {
            JobMarket.Opening o = s.opening();
            if (!suits(f, o)) continue;
            Reasons r = reasons(f, s.town(), o);
            if (r.strength() < needed(f, s.link())) continue;
            StationTask t = o.task();
            int score = r.strength() * 10 + JobMarket.likely(o.wage, s.town()) * 3 + s.link().ease + (t == null ? 0 : f.tradeLevel(t)) * 2
                - s.distance() / 100;
            if (score > bestScore) {
                bestScore = score;
                best = new Choice(s.town(), o, r, s.link());
            }
        }
        return best;
    }

    record Choice(UUID town, JobMarket.Opening opening, Reasons reasons, JobMarket.Link link) {}

    /** Somebody of its family in that town (its partner, a parent, a child), by name; or null. */
    @Nullable
    static String kinIn(VillageFolkEntity f, UUID town) {
        if (town.equals(f.ownerId())) return null;
        UUID partner = f.life().partner();
        for (AssistantEntity a : Villages.folkOf(town)) {
            if (!(a instanceof VillageFolkEntity o) || o == f) continue;
            if (o.getUUID().equals(partner)) return o.displayNameCap() + ", its partner";
            if (Homes.childOf(f, o)) return o.displayNameCap() + ", its parent";
            if (Homes.childOf(o, f)) return o.displayNameCap() + ", its child";
        }
        return null;
    }

    /** Its household: itself, its partner and their children, of its own town and loaded. */
    static List<VillageFolkEntity> household(VillageFolkEntity f) {
        List<VillageFolkEntity> out = new ArrayList<>();
        out.add(f);
        UUID home = f.ownerId();
        if (home == null || f.isBaby()) return out;
        UUID partner = f.life().partner();
        VillageFolkEntity p = null;
        for (AssistantEntity a : Villages.folkOf(home)) {
            if (a instanceof VillageFolkEntity o && o != f && o.getUUID().equals(partner) && !o.isBaby() && !busyElsewhere(o)) {
                p = o;
                out.add(o);
            }
        }
        for (AssistantEntity a : Villages.folkOf(home)) {
            if (!(a instanceof VillageFolkEntity c) || !c.isBaby() || out.contains(c) || busyElsewhere(c)) continue;
            if (Homes.childOf(c, f) || p != null && Homes.childOf(c, p)) out.add(c);
        }
        return out;
    }

    private static boolean busyElsewhere(VillageFolkEntity o) {
        return o.trip() != null || o.expedition() != null || JOURNEYS.containsKey(o.getUUID()) || o.isHired();
    }

    static int grownIn(List<VillageFolkEntity> folk) {
        int n = 0;
        for (VillageFolkEntity f : folk) if (!f.isBaby()) n++;
        return n;
    }

    // ------------------------------------------------------------------ deciding to go and look

    /** Free to go and read the board: its break, the evening, the day of rest; any time with no work. */
    static boolean free(VillageFolkEntity f) {
        UUID home = f.ownerId();
        if (home == null || f.isSleeping() || f.getTarget() != null || Raids.underAlarm(home)) return false;
        if (f.talkPartner() != null || f.companionPlayer() != null || f.guidePlayer() != null) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || Drover.busy(f) || TownJobs.busy(f)) return false;
        if (Assemblies.attending(f) || Patrols.escorting(f)) return false;
        long t = f.level().getDayTime() % 24000L;
        if (t >= 13500L && t < 23500L) return false;                         // in bed by now, or should be
        if (FORCED.contains(f.getUUID())) return true;
        if (f.stationTask() == StationTask.NONE || f.workZone() == null) return true;
        if (RestDay.now(home, f.level().getDayTime()) != null) return true;
        return f.peekJob() == null && (f.onBreak() || !f.onShift());
    }

    /** Should it go and look at the board now? If so, off it goes. */
    private static boolean consider(VillageFolkEntity f, ServerLevel level) {
        UUID me = f.getUUID(), home = f.ownerId();
        boolean forced = FORCED.contains(me);
        if (!free(f)) return false;
        long day = level.getDayTime() / 24000L;
        if (SENT.remove(me)) {                                              // an operator sent it: it goes, reason or none
            FORCED.remove(me);
            startVisit(f, level, true);
            return true;
        }
        if (!forced && LOOKED.getOrDefault(me, -100L) >= day) return false;
        LOOKED.put(me, day);
        if (f.getUUID().equals(Villages.elder(home)) || Villages.holdsTheLead(home, me, level.getGameTime())) { FORCED.remove(me); return false; }
        if (JobMarket.waiting(home, me) || recentlyRefused(home, me, day) || newcomer(home, me, day)) { FORCED.remove(me); return false; }
        // Its town cannot spare it (too few hands, its last farmer, enough gone this week): it knows, and does not go looking.
        String kept = JobMarket.cannotSpare(level, home, f, grownIn(household(f)));
        if (kept != null) {
            if (forced) LOG.info("[MCA-JOBS] {} of {} stays: {}", f.displayNameCap(), Villages.name(home), kept);
            FORCED.remove(me);
            return false;
        }
        Choice c = best(f, day);
        boolean outOfWork = outOfWork(f);
        boolean look = c != null || outOfWork && day - lastLook(me) >= LOOK_EVERY;
        if (!look) {
            if (forced) LOG.info("[MCA-JOBS] {} of {} has no reason to look at the board", f.displayNameCap(), Villages.name(home));
            FORCED.remove(me);
            return false;
        }
        startVisit(f, level, forced);
        LOG.info("[MCA-JOBS] {} of {} goes to read the notices{}", f.displayNameCap(), Villages.name(home),
            c == null ? " (out of work)" : " (" + c.reasons().words() + ")");
        return true;
    }

    private static void startVisit(VillageFolkEntity f, ServerLevel level, boolean forced) {
        UUID me = f.getUUID();
        Visit v = new Visit(level.getGameTime(), forced);
        v.spot = readingSpot(level, f.ownerId(), f);
        // What it had in hand waits: a job queued runs whether or not it is on its break, and would pull it back.
        f.clearQueue();
        f.getNavigation().stop();
        VISITS.put(me, v);
        LAST_LOOK.put(me, level.getDayTime() / 24000L);
        f.brain("off to read the notices on the board");
    }

    private static final Map<UUID, Long> LAST_LOOK = new ConcurrentHashMap<>();

    private static long lastLook(UUID me) {
        return LAST_LOOK.getOrDefault(me, -100L);
    }

    private static boolean recentlyRefused(UUID home, UUID me, long day) {
        String s = Ledger.note(home, "jm.refused/" + me);
        long d = s == null ? -1 : JobMarket.parseLong(s);
        return d >= 0 && day - d < 2;
    }

    /** Come within the last five days: it settles in first. */
    private static boolean newcomer(UUID home, UUID me, long day) {
        String s = Ledger.note(home, "jm.story/" + me);
        if (s == null || !s.contains("came from")) return false;
        int bar = s.indexOf('|');
        long d = JobMarket.parseLong(bar < 0 ? s : s.substring(0, bar));
        return d >= 0 && day - d < 5;
    }

    /**
     * Where a folk stands to read its town's board: before it, at a place along it of its own (so three
     * readers do not stand on each other's toes), or by the heart if the town has no board.
     */
    static BlockPos readingSpot(ServerLevel level, UUID home, VillageFolkEntity f) {
        BlockPos at = VillageBoards.lectern(home);
        Direction facing = VillageBoards.facingOf(home);
        if (at != null && facing != null) {
            at = at.relative(com.jrpetty.mcassistant.block.VillageBoardBlock.right(facing), Math.floorMod(f.getUUID().hashCode(), 7) - 3);
        } else {
            Villages.Village v = Villages.get(home);
            at = v != null ? v.centre().offset(Math.floorMod(f.getUUID().hashCode(), 5) - 2, 0, -3) : f.blockPosition();
        }
        return surface(level, at);
    }

    // ------------------------------------------------------------------ at the board

    private static boolean visit(VillageFolkEntity f, ServerLevel level, Visit v) {
        UUID me = f.getUUID(), home = f.ownerId();
        long now = level.getGameTime();
        boolean gone = home == null || f.isSleeping() || Raids.underAlarm(home) || Assemblies.attending(f) || f.trip() != null
            || f.getTarget() != null || f.talkPartner() != null || now - v.started > VISIT_MOST;
        if (gone) {
            VISITS.remove(me);
            if (!v.forced || now - v.started > VISIT_MOST) FORCED.remove(me);
            return false;
        }
        BlockPos spot = v.spot == null ? f.blockPosition() : v.spot;
        double dx = f.getX() - (spot.getX() + 0.5), dz = f.getZ() - (spot.getZ() + 0.5), d = dx * dx + dz * dz;
        // At its place before the board; or near enough, after a while (somebody stands in its way).
        boolean there = d <= 2.5 * 2.5 || d <= 6.0 * 6.0 && now - v.started > 400;
        if (v.readFrom < 0 && !there) {
            if (f.getNavigation().isDone() || f.tickCount - v.walkTick > 60) {
                f.walkTo(spot, 0.9D);
                v.walkTick = f.tickCount;
            }
            f.hobbyNow = "going to read the notices";
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        f.getNavigation().stop();
        lookAtTheBoard(f, home);
        f.hobbyNow = "reading the notices on the board";
        f.lastLeisureTick = f.tickCount;
        long day = level.getDayTime() / 24000L;
        if (v.readFrom < 0) {
            v.readFrom = f.tickCount;
            Choice c = best(f, day);
            List<JobMarket.Seen> seen = JobMarket.seen(home, day);
            JobMarket.Seen first = c != null ? null : seen.isEmpty() ? null : seen.get(0);
            if (c != null) FolkTalk.speak(f, notice(c.town(), c.opening()) + " " + FolkTalk.pick(f.getRandom(), "That'll do me.",
                "Now there's a thing.", "Hm. Worth a try."));
            else if (first != null) FolkTalk.speak(f, notice(first.town(), first.opening()) + " Not for me, though.");
            else FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Nothing wanted anywhere, by the look of it.", "No notices from the other towns today."));
            LOG.info("[MCA-JOBS] {} of {} reads the board: {}", f.displayNameCap(), Villages.name(home),
                c == null ? "nothing for it (" + seen.size() + " notices heard of)" : notice(c.town(), c.opening()));
            return true;
        }
        if (f.tickCount - v.readFrom < READ) return true;
        // Read: it puts its name down for the best of them, if one still suits.
        VISITS.remove(me);
        FORCED.remove(me);
        Choice c = best(f, day);
        if (c == null || c.opening().state() != JobMarket.State.OPEN) {
            f.brain("read the notices: nothing for it");
            return false;
        }
        JobMarket.apply(level, f, c.town(), c.opening(), c.reasons());
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'll put my name down for that.", "Right — I'm applying to " + Villages.name(c.town()) + ".",
            "Worth a go. My name goes down for it."));
        f.brain("applied to " + Villages.name(c.town()) + " as " + JobMarket.a(c.opening().title()));
        return false;
    }

    /** "Wanted, a miner in Oakhollow, five a day — level two or better..." */
    static String notice(UUID town, JobMarket.Opening o) {
        return "Wanted, " + JobMarket.a(o.title()) + " in " + Villages.name(town) + ", " + JobMarket.words(o.wage()) + " a day"
            + (o.minLevel() > 0 ? ", some years at it" : "") + (o.ageWords().isEmpty() ? "" : ", " + o.ageWords()) + "...";
    }

    private static void lookAtTheBoard(VillageFolkEntity f, @Nullable UUID home) {
        BlockPos a = home == null ? null : VillageBoards.boardOf(home);
        Direction facing = home == null ? null : VillageBoards.facingOf(home);
        if (a == null || facing == null) return;
        BlockPos mid = a.relative(com.jrpetty.mcassistant.block.VillageBoardBlock.right(facing), com.jrpetty.mcassistant.block.VillageBoardBlock.WIDE / 2);
        f.getLookControl().setLookAt(mid.getX() + 0.5, mid.getY() + 2.0, mid.getZ() + 0.5);
    }

    // ------------------------------------------------------------------ taken on

    /** Its application was taken: it goes, now if it is about, or when it next is (the offer in its town's notes). */
    static void hired(ServerLevel level, UUID folk, @Nullable VillageFolkEntity f, UUID town, JobMarket.Opening o, String because) {
        String t = JobMarket.a(o.title());
        if (f == null) return;                                    // the offer waits in its town's notes (offered)
        if (f.ownerId() == null) return;
        LEAVING.put(folk, new Leaving(f.ownerId(), town, o.id(), o.task(), because, f.tickCount));
        VISITS.remove(folk);
        f.clearQueue();
        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), Villages.name(town) + " will have me! I'm to be their " + o.title() + ".",
            "I've been taken on in " + Villages.name(town) + " as " + t + "!"));
        TOLD.remove(folk);
    }

    /** A place offered while it was away: it goes now. */
    private static boolean offered(VillageFolkEntity f, ServerLevel level) {
        UUID home = f.ownerId();
        if (home == null) return false;
        String s = Ledger.note(home, "jm.offer/" + f.getUUID());
        if (s == null || s.isEmpty()) return false;
        String[] p = s.split("\\|", -1);
        if (p.length < 5) return false;
        UUID town;
        int opening;
        try {
            town = UUID.fromString(p[0]);
            opening = Integer.parseInt(p[1]);
        } catch (IllegalArgumentException e) {
            Ledger.forget(home, "jm.offer/" + f.getUUID());
            return false;
        }
        LEAVING.put(f.getUUID(), new Leaving(home, town, opening, JobMarket.named(p[2]), p[4], f.tickCount));
        f.clearQueue();
        FolkTalk.speak(f, Villages.name(town) + " has taken me on! Time I was going.");
        return true;
    }

    /** Its goodbyes on the square, and then off. */
    private static boolean leaving(VillageFolkEntity f, ServerLevel level, Leaving l) {
        UUID home = f.ownerId();
        Villages.Village v = home == null ? null : Villages.get(home);
        if (v == null || !home.equals(l.home)) {
            // Gone from the town it was taken on from (married away, left in misery): the place goes up again.
            LEAVING.remove(f.getUUID());
            reopen(level, l, f, "it had gone elsewhere");
            return false;
        }
        if (f.isSleeping()) f.stopSleeping();
        BlockPos spot = readingSpot(level, home, f);
        double d = f.blockPosition().distSqr(spot);
        boolean near = d <= 4.0 * 4.0 || d > 64.0 * 64.0;                    // far out at its work: it goes from where it is
        if (!near && f.tickCount - l.started < 400) {
            if (f.getNavigation().isDone() || f.tickCount - l.walkTick > 60) {
                f.walkTo(spot, 1.0D);
                l.walkTick = f.tickCount;
            }
            f.hobbyNow = "off to say its goodbyes";
            return true;
        }
        f.getNavigation().stop();
        if (!l.said) {
            l.said = true;
            l.walkTick = f.tickCount;
            Villages.Village to = Villages.get(l.town);
            String where = to == null ? "the next town" : Villages.name(to.id());
            JobMarket.Opening o = JobMarket.opening(l.town, l.opening);
            int wage = o == null ? 0 : o.wage();
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(),
                "Goodbye, all! I'm off to " + where + (l.trade == null ? "" : " — they want " + JobMarket.a(JobMarket.noun(l.trade)))
                    + (wage > 0 ? ", and they pay " + JobMarket.words(wage) + " a day" : "") + ".",
                "That's me, then. " + where + " it is. Look after the place for me!",
                "Farewell, " + Villages.name(home) + ". I'll not forget you."));
            // Its friends see it off.
            List<String> friends = new ArrayList<>();
            for (Social.Bond b : f.life().friends()) friends.add(b.name);
            int seen = 0;
            for (AssistantEntity a : Villages.folkOf(home)) {
                if (seen >= 2) break;
                if (!(a instanceof VillageFolkEntity friend) || friend == f || friend.isSleeping() || friend.isBaby()) continue;
                if (!friends.contains(friend.displayNameCap()) || friend.distanceToSqr(f) > 20.0 * 20.0) continue;
                friend.sayLater(FolkTalk.pick(friend.getRandom(), "Mind how you go, " + f.displayNameCap() + "!", "Send word when you're settled!",
                    "We'll miss you, " + f.displayNameCap() + "."), 30 + 20 * seen);
                seen++;
            }
            return true;
        }
        if (f.tickCount - l.walkTick < 60) return true;
        LEAVING.remove(f.getUUID());
        depart(level, f, l);
        return JOURNEYS.containsKey(f.getUUID());
    }

    /** Off it goes, with its household: off the old roll, onto the new, and down the road. */
    static void depart(ServerLevel level, VillageFolkEntity f, Leaving l) {
        UUID home = f.ownerId();
        Villages.Village from = home == null ? null : Villages.get(home), to = Villages.get(l.town);
        if (home != null) Ledger.forget(home, "jm.offer/" + f.getUUID());
        long day = level.getDayTime() / 24000L;
        if (from == null || to == null || !from.dim().equals(to.dim())) {
            if (home != null) JobMarket.story(home, f.getUUID(), day, null);
            return;
        }
        List<VillageFolkEntity> household = household(f);
        String no = JobMarket.cannotSpare(level, home, f, grownIn(household));
        if (no != null) {
            FolkTalk.speak(f, "I can't go after all: " + no + ".");
            reopen(level, l, f, no);
            JobMarket.story(home, f.getUUID(), day, "was to go to " + Villages.name(to.id()) + ", but stayed: " + no);
            return;
        }
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity m : household) if (!m.isBaby()) names.add(m.displayNameCap());
        Set<UUID> going = ConcurrentHashMap.newKeySet();
        for (VillageFolkEntity m : household) going.add(m.getUUID());
        for (VillageFolkEntity m : household) {
            leave(level, m, from, to, m == f ? l.trade : StationTask.NONE, l.because, false, f.getUUID(), m == f, names, going);
        }
        // The road money: the new town pays its newcomer a coin a hundred blocks, if it has the coin.
        int blocks = (int) Math.sqrt(from.centre().distSqr(to.centre()));
        int fare = Math.min(Ledger.coins(to.id()), Math.max(1, blocks / 100));
        if (fare > 0) {
            int paid = Ledger.takeCoins(to.id(), fare);
            if (paid > 0) {
                f.earn(paid);
                Economy.spent(to.id(), paid);
            }
        }
        JobMarket.departed(level, from, to, names, l.trade, l.because, fare, day);
        LOG.info("[MCA-JOBS] {} leave {} for {} ({}; {} blocks)", JobMarket.join(names), Villages.name(from.id()), Villages.name(to.id()),
            l.because, blocks);
    }

    /** It could not go after all: the notice goes up again, and the application is let go. */
    private static void reopen(ServerLevel level, Leaving l, VillageFolkEntity f, String why) {
        List<JobMarket.Opening> all = JobMarket.openings(l.town);
        for (JobMarket.Opening o : all) {
            if (o.id != l.opening || o.state != JobMarket.State.FILLED) continue;
            o.state = JobMarket.State.OPEN;
            o.note = o.by + " could not come: " + why;
            o.by = "";
            o.from = "";
            o.closed = -1;
        }
        JobMarket.openings(l.town, all);
        List<JobMarket.Application> apps = JobMarket.applications(l.town);
        for (JobMarket.Application a : apps) {
            if (a.opening == l.opening && a.folk.equals(f.getUUID()) && a.verdict == JobMarket.Verdict.HIRED) {
                a.verdict = JobMarket.Verdict.WITHDRAWN;
                a.why = why;
            }
        }
        JobMarket.applications(l.town, apps);
    }

    /** Refugees: the household goes to the town that will take it in. */
    static void refuge(ServerLevel level, List<VillageFolkEntity> household, Villages.Village from, Villages.Village to) {
        if (household.isEmpty()) return;
        VillageFolkEntity head = household.get(0);
        for (VillageFolkEntity m : household) if (!m.isBaby()) { head = m; break; }
        List<String> names = new ArrayList<>();
        Set<UUID> going = ConcurrentHashMap.newKeySet();
        for (VillageFolkEntity m : household) {
            going.add(m.getUUID());
            if (!m.isBaby()) names.add(m.displayNameCap());
        }
        FolkTalk.speak(head, FolkTalk.pick(level.getRandom(), "There's nowhere left to sleep here... " + Villages.name(to.id()) + " will take us in.",
            "We've lost everything. " + Villages.name(to.id()) + " has room for us, they say.", "Come on. " + Villages.name(to.id()) + " will have us."));
        for (VillageFolkEntity m : household) {
            leave(level, m, from, to, StationTask.NONE, "homeless after the raid", true, head.getUUID(), m == head, names, going);
        }
    }

    /**
     * One folk leaves its town for another: what it carries of the village's goods back into the stores
     * (not a refugee's: what it saved is its own), its own things out of its house's chest, off the old
     * roll and onto the new, and onto the road.
     */
    private static void leave(ServerLevel level, VillageFolkEntity m, Villages.Village from, Villages.Village to, @Nullable StationTask trade,
                              String because, boolean refugee, UUID group, boolean head, List<String> party, Set<UUID> going) {
        UUID me = m.getUUID();
        long day = level.getDayTime() / 24000L;
        VISITS.remove(me);
        LEAVING.remove(me);
        FORCED.remove(me);
        NO_GROUND.remove(me);
        m.clearQueue();
        m.getNavigation().stop();
        m.stopFollowing();
        if (m.isSleeping()) m.stopSleeping();
        belongings(level, m, from.id(), refugee, going);
        Homes.left(from.id(), me);
        m.forgetBed();
        m.setWorkZone(null);
        m.setStation(null, StationTask.NONE);
        m.leftItsPlot();
        if (me.equals(Villages.elder(from.id()))) Villages.elderGone(from.id(), me);
        Villages.recordDeath(from.id());
        Annals.moved(from.id(), to.id());
        Ledger.forget(from.id(), "jm.applied/" + me);
        JobMarket.story(from.id(), me, day, null);
        m.joinVillage(to.id(), to.centre());
        Villages.recordBirth(to.id());
        Journey j = new Journey(from.id(), to.id(), group, Villages.name(from.id()), Caravans.way(from, to), trade, because, refugee, head);
        if (head) j.party.addAll(party);
        j.gainedTick = m.tickCount;
        j.lastStep = level.getGameTime();
        j.day = day;
        JOURNEYS.put(me, j);
        writeIncoming(j, me, null);
        if (!m.isBaby()) {
            m.persona().remember(day, refugee ? "the raiders left us homeless, and we went to " + Villages.name(to.id())
                : "I left " + Villages.name(from.id()) + " for " + Villages.name(to.id()) + ", " + because, refugee ? 9 : 8);
        }
        m.brain("on the road to " + Villages.name(to.id()));
    }

    /** Its own things out of its house's chest (all of it, if the household is leaving the house), and the village's goods it carries back to the stores. */
    private static void belongings(ServerLevel level, VillageFolkEntity m, UUID from, boolean refugee, Set<UUID> going) {
        if (m.isBaby()) return;
        Homes.Home h = Homes.homeOf(from, m.getUUID());
        if (h != null && level.isLoaded(h.anchor)) {
            boolean whole = going.containsAll(h.members);
            BlockPos chest = Homes.chestOf(level, from, h);
            if (chest != null && level.getBlockEntity(chest) instanceof net.minecraft.world.Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty() || !whole && !Homes.ownedBy(s, m)) continue;
                    ItemStack carried = s.copy();
                    Homes.keepsake(carried, m);
                    ItemStack left = m.insertItem(carried);
                    c.setItem(i, left.isEmpty() ? ItemStack.EMPTY : s.copyWithCount(left.getCount()));
                }
                c.setChanged();
            }
        }
        if (refugee || !Villages.hasStores(level, from)) return;          // (no stores to give them back to: it keeps them)
        // The village's goods it was carrying (the day's ore, a load for the stores) are the village's: into the stores.
        net.minecraft.core.NonNullList<ItemStack> pack = m.getInventoryItems();
        int food = 0;
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || Homes.isKeepsake(s) || s.isDamageableItem()) continue;     // its own, and its tools
            if (s.get(net.minecraft.core.component.DataComponents.FOOD) != null && food < 8) {
                food += s.getCount();                                                      // food for the road
                continue;
            }
            ItemStack left = Market.intoStores(level, from, s.copy());
            pack.set(i, left);
        }
    }

    // ------------------------------------------------------------------ on the road

    private static boolean travel(VillageFolkEntity f, ServerLevel level, Journey j) {
        j.lastStep = level.getGameTime();
        // Gone to another town meanwhile (married away, left in misery): this road is not its road any more.
        if (!j.to.equals(f.ownerId())) {
            JOURNEYS.remove(f.getUUID());
            release(level, f, j);
            Ledger.forget(j.to, "jm.incoming/" + f.getUUID());
            return false;
        }
        if (f.isSleeping()) f.stopSleeping();
        keepAwake(level, f, j);
        if (j.at >= j.way.size()) {
            arrive(level, f, j);
            return false;
        }
        BlockPos onGround = surface(level, j.way.get(j.at));
        double dx = f.getX() - (onGround.getX() + 0.5), dz = f.getZ() - (onGround.getZ() + 0.5);
        double d = dx * dx + dz * dz;
        if (d <= 9.0) {
            j.at++;
            j.best = Double.MAX_VALUE;
            j.gainedTick = f.tickCount;
            return true;
        }
        if (d < j.best - 1.0) {
            j.best = d;
            j.gainedTick = f.tickCount;
        }
        if (f.getNavigation().isDone() || f.tickCount - j.walkTick > 80) {
            f.walkTo(onGround, 0.9D);
            j.walkTick = f.tickCount;
        }
        // No nearer for half a minute (a river with no bridge, a cliff): set down at the next step, as a caravan is.
        if (f.tickCount - j.gainedTick > 600) {
            f.moveTo(onGround.getX() + 0.5, onGround.getY(), onGround.getZ() + 0.5, f.getYRot(), 0.0F);
            f.getNavigation().stop();
            j.gainedTick = f.tickCount;
            j.best = Double.MAX_VALUE;
        }
        if (j.head && f.tickCount - j.spokeTick > 2400) {
            j.spokeTick = f.tickCount;
            FolkTalk.speak(f, j.refugee ? "Not far now. " + Villages.name(j.to) + " will take us in."
                : FolkTalk.pick(f.getRandom(), "On the road to " + Villages.name(j.to) + ", to my new place.", "A long walk, but there's work at the end of it."));
        }
        f.hobbyNow = "on the road to " + Villages.name(j.to);
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** Arrived: a roof, its work, and its name in the books. */
    private static void arrive(ServerLevel level, VillageFolkEntity f, Journey j) {
        UUID me = f.getUUID();
        JOURNEYS.remove(me);
        release(level, f, j);
        Ledger.forget(j.to, "jm.incoming/" + me);
        f.getNavigation().stop();
        Villages.Village to = Villages.get(j.to);
        if (to == null) return;
        long day = level.getDayTime() / 24000L;
        if (!to.id().equals(f.ownerId())) f.joinVillage(to.id(), to.centre());
        // A roof: the household into an empty house; failing that, a bed at the camp.
        if (j.head) {
            List<VillageFolkEntity> household = new ArrayList<>();
            household.add(f);
            for (AssistantEntity a : Villages.folkOf(to.id())) {
                if (a instanceof VillageFolkEntity o && o != f && sameGroup(o, j.group)) household.add(o);
            }
            Homes.Home h = Homes.vacancyFor(level, to.id(), household);
            if (h != null) Homes.settle(level, to, h, household, day);
        }
        if (f.bedPos() == null && Homes.homeOf(to.id(), me) == null) f.claimBedNear(to.centre());
        // Its work.
        StationTask trade = f.isBaby() ? StationTask.NONE : j.refugee ? JobMarket.fitFor(f, to.id()) : j.trade == null ? StationTask.NONE : j.trade;
        if (trade != StationTask.NONE) {
            f.setStation(f.blockPosition(), trade);
            f.setAutonomous(true);
        }
        if (!f.isBaby()) {
            JobMarket.arrived(level, to, f, j.from, j.fromName, trade, j.because, j.refugee, j.head ? j.party : List.of(), day);
            f.persona().remember(day, j.refugee ? Villages.name(to.id()) + " took us in after the raid" : "I came to " + Villages.name(to.id())
                + (trade == StationTask.NONE ? "" : " to work as " + JobMarket.a(JobMarket.noun(trade))), 7);
        }
        if (j.head) {
            FolkTalk.speak(f, j.refugee
                ? FolkTalk.pick(f.getRandom(), "Thank you for taking us in. We'll pull our weight, I promise.", "A roof again. Thank you, " + Villages.name(to.id()) + ".")
                : FolkTalk.pick(f.getRandom(), "Hello! I'm " + f.displayNameCap() + ", come from " + j.fromName + " to be your "
                    + JobMarket.noun(trade) + ".", "So this is " + Villages.name(to.id()) + ". Where do I start?"));
        }
        f.brain("arrived in " + Villages.name(to.id()) + (trade == StationTask.NONE ? "" : ", to work as " + JobMarket.a(JobMarket.noun(trade))));
        LOG.info("[MCA-JOBS] {} arrives in {} from {}{} (bed {}, trade {})", f.displayNameCap(), Villages.name(to.id()), j.fromName,
            j.refugee ? " (taken in after the raid)" : "", f.bedPos() == null ? "none yet" : f.bedPos().toShortString(), trade);
    }

    private static boolean sameGroup(VillageFolkEntity o, UUID group) {
        Journey oj = JOURNEYS.get(o.getUUID());
        return oj != null && oj.group.equals(group);
    }

    /** On the road when the world was saved: it takes the road up again from where it stands. */
    private static boolean resume(VillageFolkEntity f, ServerLevel level) {
        UUID home = f.ownerId();
        if (home == null) return false;
        String s = Ledger.note(home, "jm.incoming/" + f.getUUID());
        if (s == null || s.isEmpty()) return false;
        String[] p = s.split("\\|", -1);
        Villages.Village to = Villages.get(home);
        if (p.length < 8 || to == null) {
            Ledger.forget(home, "jm.incoming/" + f.getUUID());
            return false;
        }
        UUID from;
        try {
            from = UUID.fromString(p[0]);
        } catch (IllegalArgumentException e) {
            Ledger.forget(home, "jm.incoming/" + f.getUUID());
            return false;
        }
        // The ground it was keeping awake when the world was saved, let go: a fresh window goes with it.
        long wx = JobMarket.parseLong(p[6]), wz = JobMarket.parseLong(p[7]);
        if (p[6].length() > 0 && !p[6].equals("-") && wx != -1) {
            ChunkLoad.setLoaded(level, owner(f.getUUID()), new BlockPos((int) wx, 64, (int) wz), 1, false);
        }
        List<BlockPos> way = new ArrayList<>();
        BlockPos a = f.blockPosition(), b = to.centre().offset(2, 0, 2);
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(a.distSqr(b)) / 10.0));
        for (int i = 1; i <= steps; i++) way.add(new BlockPos(a.getX() + (b.getX() - a.getX()) * i / steps, a.getY(), a.getZ() + (b.getZ() - a.getZ()) * i / steps));
        boolean refugee = "1".equals(p[3]);
        Journey j = new Journey(from, home, f.getUUID(), p.length > 8 ? p[8] : Villages.name(from), way, JobMarket.named(p[1]), p[2], refugee, true);
        j.party.add(f.displayNameCap());
        j.gainedTick = f.tickCount;
        j.lastStep = level.getGameTime();
        j.day = level.getDayTime() / 24000L;
        JOURNEYS.put(f.getUUID(), j);
        writeIncoming(j, f.getUUID(), null);
        LOG.info("[MCA-JOBS] {} takes the road to {} up again", f.displayNameCap(), Villages.name(home));
        return true;
    }

    /** The journey in the new town's notes: from, trade, why, refugee, day, the window's x and z, and the old town's name. */
    private static void writeIncoming(Journey j, UUID folk, @Nullable BlockPos window) {
        String w = window == null ? "-|-" : window.getX() + "|" + window.getZ();
        Ledger.note(j.to, "jm.incoming/" + folk, j.from + "|" + (j.trade == null ? "" : j.trade.name()) + "|" + JobMarket.clean(j.because)
            + "|" + (j.refugee ? 1 : 0) + "|" + j.lastStep + "|" + j.day + "|" + w + "|" + JobMarket.clean(j.fromName));
    }

    private static UUID owner(UUID folk) {
        return UUID.nameUUIDFromBytes(("mca-jobmove-" + folk).getBytes());
    }

    /** The ground round the traveller kept awake as it goes. */
    private static void keepAwake(ServerLevel level, VillageFolkEntity f, Journey j) {
        BlockPos here = f.blockPosition();
        if (j.window != null && j.window.distSqr(here) < 16 * 16) return;
        if (j.window != null) ChunkLoad.setLoaded(level, owner(f.getUUID()), j.window, 1, false);
        ChunkLoad.setLoaded(level, owner(f.getUUID()), here, 1, true);
        j.window = here.immutable();
        writeIncoming(j, f.getUUID(), j.window);
    }

    private static void release(ServerLevel level, VillageFolkEntity f, Journey j) {
        if (j.window != null) ChunkLoad.setLoaded(level, owner(f.getUUID()), j.window, 1, false);
        j.window = null;
    }

    /**
     * Journeys whose traveller has not been seen for a minute (dead on the road, or gone with its
     * chunk): let go, and the ground round it with them. The note in the new town's books stays a few
     * days (JobMarket.daily), in case it was only asleep in an unloaded chunk and turns up again.
     */
    static void sweep(ServerLevel level) {
        long now = level.getGameTime();
        for (Map.Entry<UUID, Journey> e : JOURNEYS.entrySet()) {
            Journey j = e.getValue();
            if (!inLevel(level, j.to)) continue;                        // another world's road: its own sweep
            if (now - j.lastStep < 1200L && now >= j.lastStep) continue;
            if (live(level, e.getKey()) != null) continue;
            JOURNEYS.remove(e.getKey());
            if (j.window != null) ChunkLoad.setLoaded(level, owner(e.getKey()), j.window, 1, false);
        }
        VISITS.entrySet().removeIf(e -> now - e.getValue().started > VISIT_MOST * 2L);
        LEAVING.entrySet().removeIf(e -> inLevel(level, e.getValue().home) && live(level, e.getKey()) == null);
    }

    /** Is this town in this world (a town gone from the books counts as the overworld's)? */
    private static boolean inLevel(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v != null ? v.dim().equals(level.dimension()) : level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
    }

    private static BlockPos surface(ServerLevel level, BlockPos p) {
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return p;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return new BlockPos(p.getX(), y, p.getZ());
    }

    /** Its reasons in a line, for the command: "out of work; better pay (4 a day against 0)". */
    public static String reasonsLine(VillageFolkEntity f) {
        UUID home = f.ownerId();
        if (home == null) return "no town";
        long day = f.level().getDayTime() / 24000L;
        Choice c = best(f, day);
        if (c != null) return "would apply to " + Villages.name(c.town()) + " as " + JobMarket.a(c.opening().title()) + ": " + c.reasons().words()
            + " (" + c.reasons().strength() + ", needs " + needed(f, c.link()) + ")";
        List<JobMarket.Seen> seen = JobMarket.seen(home, day);
        if (seen.isEmpty()) return "has heard of no notices elsewhere";
        JobMarket.Seen s = seen.get(0);
        Reasons r = reasons(f, s.town(), s.opening());
        return "nothing for it: " + JobMarket.a(s.opening().title()) + " in " + Villages.name(s.town()) + " — "
            + (suits(f, s.opening()) ? (r.why().isEmpty() ? "no reason to go" : r.words() + " (" + r.strength() + ", needs " + needed(f, s.link()) + ")")
            : "it does not suit (" + f.ageYears() + " years, level " + (s.opening().task() == null ? 0 : f.tradeLevel(s.opening().task())) + ")");
    }
}
