package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war-scouting] Scouting the enemy. A town on its guard or at war wants to know what it is up against,
 * and it sends somebody to look.
 * <ul>
 * <li><b>Who goes.</b> A town of any size may send one: its scout if it has one, else its sharpest eyes
 *     (the Sharp Eyes knack), its best rider (a courier, who rides the stable's horses, or the rancher),
 *     a hunter who knows the country, or any grown folk who can be spared. Never a guard (the watch stays
 *     at home) and never the leader. It draws food for the road out of the stores, as a scout does (and, as
 *     a scout does, takes a saddled horse from the stable if one stands free: Riding).</li>
 * <li><b>Where it goes.</b> To a vantage outside the enemy's streets on our side of it, on the highest
 *     ground to be had there: a ridge, if there is one. It walks the way a scout walks (Scouts.drive),
 *     a stage at a time, keeping clear of monsters.</li>
 * <li><b>What it does there.</b> It gets down in the grass and watches for a minute, and counts, for real,
 *     what it can see from where it lies: the guards out of doors (a guard indoors is not seen), which of
 *     them are in iron and which carry bows; the wall's sides and its gates; the grown folk about the
 *     streets, and the houses for the rest; the fields and the granary for a guess at their food. What it
 *     cannot see it does not count: guards indoors are missed, and the report is short by them. A bold one
 *     (a scout, or a curious folk, now and then, at war) then slips in among the houses for a closer look
 *     and, if it is not caught, counts the barracks and the granary too: the enemy's secrets.</li>
 * <li><b>Home.</b> It comes back along its own trail and files its report, dated the day it watched, in its
 *     own words (Intel): told at the morning assembly, to the leader if it is about, in the chronicle and on
 *     the board and the war map with its age. A spy that is seen runs for it (Spies has the watch's chase);
 *     a spy that is caught brings nothing home at all.</li>
 * </ul>
 * In tension a town looks again every four days; at war, every two.
 */
public final class Spying {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Spying() {}

    /** After this hour (day time) a spy still on its way gives it up for the day and comes home. */
    static final long LATE = 10500L;
    /** The longest a spy is out (ticks): the way there, the watch and the way back. */
    static final long LONGEST = 15000L;
    /** How long it lies watching at its vantage (ticks). */
    static final int WATCH = 1200;
    /** How far it can make out a folk and what it carries (blocks; Sharp Eyes sixteen further). */
    static final int SIGHT = 72;
    /** How long a bold one is given to slip in among the houses and out again (ticks). */
    static final int SLIP = 500;
    /** Days between looks, at war and on our guard. */
    static final int AGAIN_AT_WAR = 2, AGAIN_IN_TENSION = 4;
    /** The furthest a town sends anybody to look. */
    static final int FARTHEST = 1400;
    /** Days a town waits after losing a spy before it sends another the same way. */
    static final int AFTER_A_LOSS = 2;

    /** Where a spy is in its errand. */
    public enum Phase { GOING, WATCHING, SLIPPING, FLED, HOME }

    /** What one spy is about: whom it watches, from where, and everything it has counted. */
    public static final class Mission {
        final UUID them;
        final String name;
        final BlockPos heart;
        final String from;
        Phase phase = Phase.GOING;
        long watchedOn = -1L;
        int until, countedTick;
        final Set<UUID> folk = new HashSet<>(), guards = new HashSet<>(), armoured = new HashSet<>(), archers = new HashSet<>();
        int walls, gates, foodDays;
        boolean sampled;
        @Nullable BlockPos slipTo;
        boolean secrets;
        int[] truth = new int[0];
        boolean seen;
        @Nullable UUID chaser;
        @Nullable String rode;
        /** A captive let go, walking home: none of the rest applies. */
        boolean freed;

        Mission(UUID them, String name, BlockPos heart, String from) {
            this.them = them;
            this.name = name;
            this.heart = heart;
            this.from = from;
        }

        public UUID them() { return them; }
        public String name() { return name; }
        public Phase phase() { return phase; }
        /** Still on its way there, or there: not yet heading home. */
        public boolean going() { return !freed && (phase == Phase.GOING || phase == Phase.WATCHING || phase == Phase.SLIPPING); }
        public int guardsSeen() { return guards.size(); }
        public boolean seen() { return seen; }
        /** Has it lain at its vantage and looked (there is something to report)? */
        public boolean watched() { return watchedOn >= 0; }
    }

    /** By "us/them": the day a spy was last sent that way. */
    private static final Map<String, Long> SENT = new ConcurrentHashMap<>();
    /** Tests: how long a spy watches (ticks), and whether it dares slip in (null: as its nature says). */
    private static int watchTicks = WATCH;
    @Nullable private static Boolean bold;

    static void resetForTests() {
        SENT.clear();
        watchTicks = WATCH;
        bold = null;
    }

    /** Tests: how long a spy lies watching. */
    public static void watchForTests(int ticks) {
        watchTicks = Math.max(20, ticks);
    }

    /** Tests: whether a spy slips in among the houses after its watch (null: as its nature says). */
    public static void boldForTests(@Nullable Boolean b) {
        bold = b;
    }

    // ------------------------------------------------------------------ whom to watch

    /** The towns this one is at odds with: those it is at war with first, then those in a feud with it. */
    public static List<UUID> rivals(UUID us) {
        List<UUID> out = new ArrayList<>(Wars.enemies(us));
        for (Villages.Village v : Villages.every()) {
            if (v.id().equals(us) || out.contains(v.id())) continue;
            if (Ledger.relation(us, v.id()) <= Diplomacy.FEUD) out.add(v.id());
        }
        return out;
    }

    /** Are these two at odds (at war, or in a feud): no swapping news, a stranger from there is a spy? */
    public static boolean hostile(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        return Wars.atWar(a, b) || Ledger.relation(a, b) <= Diplomacy.FEUD;
    }

    /** Has this town somebody out watching that one now? */
    static boolean out(UUID us, UUID them) {
        for (AssistantEntity a : Villages.folkOf(us)) {
            if (a instanceof VillageFolkEntity f && f.expedition() != null && f.expedition().mission() != null
                    && !f.expedition().mission().freed && f.expedition().mission().them.equals(them)) return true;
        }
        return false;
    }

    /** The rival this town most wants news of today (none of it, or none fresh enough), or null. */
    @Nullable
    static UUID wanted(ServerLevel level, Villages.Village v, long day) {
        for (UUID r : rivals(v.id())) {
            Villages.Village o = Villages.get(r);
            if (o == null || !o.dim().equals(v.dim())) continue;
            if (Scouts.flat(o.centre(), v.centre()) > (double) FARTHEST * FARTHEST) continue;
            if (SENT.getOrDefault(v.id() + "/" + r, -100L) >= day) continue;               // one a day that way
            if (Spies.lostOn(v.id(), r) > day - AFTER_A_LOSS) continue;                   // one lost lately: not yet
            if (out(v.id(), r)) continue;
            Intel.Report rep = Intel.latest(v.id(), r);
            int again = Wars.atWar(v.id(), r) ? AGAIN_AT_WAR : AGAIN_IN_TENSION;
            if (rep != null && Intel.age(rep, day) < again) continue;
            return r;
        }
        return null;
    }

    /** Is this folk free to be sent? Grown, well, at home and at nothing that cannot wait. */
    static boolean free(VillageFolkEntity f) {
        if (!f.isAlive() || f.isBaby() || f.isSleeping() || f.isHired() || f.isShowcase()) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || Drover.busy(f) || Assemblies.attending(f)) return false;
        if (Spies.held(f) || Pickets.on(f)) return false;
        // Nor one whose day is already somebody else's: away visiting or out with a dog (Visitors), on a horse
        // or with one out (Riding: a courier sent off mid-run rode its horse back to the stable first, and the
        // stable's errand runs before the scout's), on its rounds, or called to the town's works.
        if (Visitors.is(f) || FriendVisits.away(f) || WatchDogs.busy(f)) return false;
        if (Stables.busy(f) || Riding.doing(f) != null || f.isPassenger() || Couriers.onARun(f) || TownJobs.busy(f)) return false;
        return f.getHealth() >= f.getMaxHealth() * 0.7F;
    }

    /** How fit a folk is to go and look: a scout first; then sharp eyes, a rider, a hunter; anybody spare at a pinch. */
    static int fitness(VillageFolkEntity f) {
        int s = 1;
        if (f.stationTask() == StationTask.SCOUT) s += 100;
        if (FolkSkills.active(f, FolkSkills.Knack.SHARP_EYES) || f.knacks().has(FolkSkills.Knack.SHARP_EYES)) s += 50;
        if (Couriers.employed(f)) s += 40;                         // the stable's horses are theirs to ride
        if (f.stationTask() == StationTask.RANCH) s += 30;
        if (f.stationTask() == StationTask.HUNT) s += 25;          // knows the country, and how to keep low in it
        if (f.life().has(Social.Trait.CURIOUS)) s += 5;
        if (f.life().has(Social.Trait.SHY)) s -= 1;
        return s;
    }

    /** Who this town sends: the fittest of its free folk (not the watch, not the leader), or null. */
    @Nullable
    public static VillageFolkEntity pick(Villages.Village v) {
        UUID elder = Villages.elder(v.id());
        VillageFolkEntity best = null;
        int bestScore = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !free(f)) continue;
            if (f.getUUID().equals(elder) || f.stationTask() == StationTask.GUARD) continue;
            int s = fitness(f);
            if (s > bestScore || s == bestScore && best != null && f.getUUID().compareTo(best.getUUID()) < 0) {
                bestScore = s;
                best = f;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ sending one

    /** The town's look round (WarScouting, every few seconds): of a morning, on a war footing, somebody sent to look. */
    static void tick(ServerLevel level, Villages.Village v) {
        long t = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        if (Wars.footing(v.id()) == Wars.Footing.PEACE) return;
        beliefs(v.id());
        if (t < 1400L || t > 6000L) return;                               // set out of a morning, after the assembly
        UUID them = wanted(level, v, day);
        if (them == null) return;
        VillageFolkEntity spy = pick(v);
        if (spy != null) send(level, spy, v, them, day);
    }

    /**
     * A war begun: what the town believed of its enemy that day is set down (Intel.acted, "went to war
     * with"), once a war, so that its next good look at the enemy can tell it how far out it was. (The
     * war-and-peace work may set it down itself when the council votes; this is for a war however it came.)
     */
    static void beliefs(UUID us) {
        for (UUID them : Wars.enemies(us)) {
            long since = Wars.since(us, them);
            String key = "intel.went/" + them;
            if (Long.toString(since).equals(Ledger.note(us, key))) continue;
            Ledger.note(us, key, Long.toString(since));
            if (Ledger.note(us, "intel.acted/" + them) == null) Intel.acted(us, them, Math.max(0L, since), "went to war with");
        }
    }

    /** Scouts.work: a town's scout, setting out of a morning on a war footing, goes to watch the enemy instead. */
    static boolean instead(VillageFolkEntity f, ServerLevel level, Villages.Village v, long day) {
        if (!com.jrpetty.mcassistant.AssistantConfig.villageWars() || Wars.footing(v.id()) == Wars.Footing.PEACE) return false;
        UUID them = wanted(level, v, day);
        return them != null && send(level, f, v, them, day);
    }

    /** Off to watch that town: food for the road out of the stores, the vantage chosen, and away. */
    public static boolean send(ServerLevel level, VillageFolkEntity f, Villages.Village v, UUID them, long day) {
        Villages.Village o = Villages.get(them);
        if (o == null) return false;
        String name = Villages.name(them);
        SENT.put(v.id() + "/" + them, day);
        if (!provision(level, v, f)) {
            FolkTalk.speak(f, "Nothing in the stores to take on the road. I'll not go hungry all the way to " + name + ".");
            return false;
        }
        BlockPos vantage = vantage(level, v.centre(), o);
        boolean ridge = vantage.getY() >= o.centre().getY() + 3;
        Mission m = new Mission(them, name, o.centre(), (ridge ? "the ridge " : "the fields ") + Guide.direction(o.centre(), vantage) + " of it");
        Scouts.mission(f, v, vantage, m);
        Scouts.wentToday(f, day);
        boolean war = Wars.atWar(v.id(), them);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Off to have a look at " + name + " — quietly.",
            "I'm to see what " + name + " has behind its walls. Back by nightfall.",
            war ? "Somebody has to count their spears. Might as well be me." : "Just a look at " + name + ". Nobody need know."));
        Villages.tell(v.id(), day, f.displayNameCap() + " went to watch " + name + (war ? ", the enemy" : ""));
        LOG.info("[MCA-SPY] {} of {} sent to watch {} from {} ({} blocks, vantage {})", f.displayNameCap(), Villages.name(v.id()), name,
            m.from, (int) Math.sqrt(Scouts.flat(v.centre(), vantage)), vantage.toShortString());
        return true;
    }

    /** Food for the road (four of something) out of the stores, unless it carries some. Returns whether it has any. */
    static boolean provision(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        for (int i = 0; i < 4 && f.countMatching(s -> s.get(DataComponents.FOOD) != null) < 4; i++) {
            ItemStack s = Crafts.takeOne(level, v, x -> x.get(DataComponents.FOOD) != null && !x.is(Items.ROTTEN_FLESH) && !x.is(Items.SPIDER_EYE));
            if (s.isEmpty()) break;
            ItemStack left = f.insertItem(s);
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        return f.countMatching(s -> s.get(DataComponents.FOOD) != null) > 0;
    }

    /**
     * How far out from a town's heart to lie and watch it: sixteen blocks past its furthest building (the
     * first real look at a hamlet of a few tents was from across a river sixty blocks off, the town a speck),
     * but never nearer than forty, nor further than sixteen past the streets it has laid.
     */
    static int watchFrom(UUID them) {
        Villages.Village o = Villages.get(them);
        int built = 0;
        if (o != null) {
            for (Ledger.Building b : Ledger.buildings(them)) {
                if (b.structure().equals("colony") || b.structure().equals("road")) continue;
                built = Math.max(built, (int) Math.sqrt(Scouts.flat(b.anchor(), o.centre())));
            }
        }
        return Math.max(40, Math.min(Villages.townReach(them) + 16, built + 16));
    }

    /**
     * Somewhere to watch a town from: out past its buildings ({@link #watchFrom}), on our side of it, on the
     * best ground of a fan of places there (a little further out, and up to forty degrees either way): high
     * ground first (a rise, a ridge), open sky over it, straight on and nearer in for choice; never in water,
     * nor on the water's edge, where whatever lives in the river comes up the bank at it.
     */
    static BlockPos vantage(ServerLevel level, BlockPos home, Villages.Village o) {
        BlockPos c = o.centre();
        int r = watchFrom(o.id());
        double base = Math.atan2(home.getZ() - c.getZ(), home.getX() - c.getX());
        BlockPos best = null, fallback = null;
        int bestScore = Integer.MIN_VALUE;
        for (int out : new int[]{ 0, 6, 12, 20 }) {
            for (int deg : new int[]{ 0, 20, -20, 40, -40 }) {
                double ang = base + Math.toRadians(deg);
                int x = c.getX() + (int) Math.round(Math.cos(ang) * (r + out)), z = c.getZ() + (int) Math.round(Math.sin(ang) * (r + out));
                if (fallback == null) fallback = new BlockPos(x, c.getY(), z);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos p = Scouts.surface(level, new BlockPos(x, c.getY(), z));
                if (wet(level, p)) continue;
                int score = 2 * p.getY() - Math.abs(deg) / 10 - out / 4 - (level.canSeeSky(p.above()) ? 0 : 6);
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        return best != null ? best : fallback;
    }

    /** Water here, or within three blocks of it on any side: no place to lie and watch from. */
    static boolean wet(ServerLevel level, BlockPos p) {
        if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.below()).isEmpty()) return true;
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (dx == 0 && dz == 0 || !level.hasChunk((p.getX() + dx) >> 4, (p.getZ() + dz) >> 4)) continue;
                BlockPos q = Scouts.surface(level, p.offset(dx, 0, dz));
                if (!level.getFluidState(q.below()).isEmpty()) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ out there (Scouts.drive)

    /** A spy's errand, a stage at a time (from Scouts.drive, every few ticks). True while this is what it does. */
    static boolean drive(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e) {
        Mission m = e.mission;
        if (m == null || m.freed) return false;
        Villages.Village o = Villages.get(m.them);
        if (o == null) {
            stand(f);
            if (!e.returning) Scouts.turnBack(level, f, e, "the town I went to watch was gone");
            m.phase = Phase.HOME;
            return false;
        }
        if (m.rode == null && f.getVehicle() instanceof AbstractHorse h) m.rode = Stables.name(h);
        switch (m.phase) {
            case GOING -> {
                if (e.returning) {                                     // turned back on the way (hurt, late, the way blocked)
                    m.phase = Phase.HOME;
                    return false;
                }
                if (Scouts.flat(f.blockPosition(), e.target) > 10 * 10) return false;
                beginWatch(level, f, m, o);
                watch(level, f, e, m, o);
                return true;
            }
            case WATCHING -> {
                if (e.returning) {
                    stand(f);
                    m.phase = Phase.HOME;
                    return false;
                }
                watch(level, f, e, m, o);
                return true;
            }
            case SLIPPING -> {
                if (e.returning) {
                    stand(f);
                    m.phase = Phase.HOME;
                    return false;
                }
                slip(level, f, e, m, o);
                return true;
            }
            case FLED -> {
                return flee(level, f, m);
            }
            default -> {
                return false;
            }
        }
    }

    /** At its vantage: down in the grass, the watch begun (dated today), and a first look over the town at once. */
    static void beginWatch(ServerLevel level, VillageFolkEntity f, Mission m, Villages.Village o) {
        m.phase = Phase.WATCHING;
        m.watchedOn = level.getDayTime() / 24000L;
        m.until = f.tickCount + watchTicks;
        m.countedTick = f.tickCount;
        f.getNavigation().stop();
        sample(level, f, m, o);
        count(level, f, m, o);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There it is: " + m.name + ". Now, quiet...",
            "Down in the grass, and count.", "So that's " + m.name + ". Let's see what they've got."));
        LOG.info("[MCA-SPY] {} at its vantage over {} ({}), {} blocks from its heart; first look: {} folk, {} guards", f.displayNameCap(),
            m.name, m.from, (int) Math.sqrt(Scouts.flat(f.blockPosition(), m.heart)), m.folk.size(), m.guards.size());
    }

    /** Down in the grass at its vantage, looking and counting, until it has seen enough. */
    static void watch(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Mission m, Villages.Village o) {
        f.getNavigation().stop();
        f.getLookControl().setLookAt(m.heart.getX() + 0.5, m.heart.getY() + 2.0, m.heart.getZ() + 0.5);
        if (!f.isPassenger() && f.getPose() == Pose.STANDING) f.setPose(Pose.CROUCHING);
        f.hobbyNow = "watching " + m.name + " from " + m.from;
        if (!m.sampled) sample(level, f, m, o);
        if (f.tickCount - m.countedTick >= 40) {
            m.countedTick = f.tickCount;
            count(level, f, m, o);
        }
        if (f.tickCount < m.until) return;
        stand(f);
        if (dares(f, m)) {
            m.phase = Phase.SLIPPING;
            m.until = f.tickCount + SLIP;
            int in = Math.max(Watch.R + 6, Villages.townReach(o.id()) - 12);
            Vec3 way = Vec3.atCenterOf(f.blockPosition()).subtract(Vec3.atCenterOf(m.heart));
            way = way.lengthSqr() < 1 ? new Vec3(1, 0, 0) : way.normalize();
            m.slipTo = Scouts.surface(level, BlockPos.containing(m.heart.getX() + way.x * in, m.heart.getY(), m.heart.getZ() + way.z * in));
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A closer look. In and out — nobody'll know.", "Their barracks, now. Quietly does it."));
            return;
        }
        m.phase = Phase.HOME;
        Scouts.turnBack(level, f, e, "I had seen what I went to see");
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "That'll do. Home, before they spot me.",
            "Counted. Now back the way I came, quiet as you like."));
        LOG.info("[MCA-SPY] {} done watching {}: {} folk, {} guards ({} armoured, {} archers) seen", f.displayNameCap(), m.name,
            m.folk.size(), m.guards.size(), m.armoured.size(), m.archers.size());
    }

    /** Does it dare slip in among the houses after its watch? A scout or a curious folk, unseen, at war, one time in three. */
    static boolean dares(VillageFolkEntity f, Mission m) {
        if (m.seen) return false;
        if (bold != null) return bold;
        UUID us = f.ownerId();
        if (us == null || !Wars.atWar(us, m.them)) return false;
        if (f.stationTask() != StationTask.SCOUT && !f.life().has(Social.Trait.CURIOUS)) return false;
        return new Random(f.getUUID().getLeastSignificantBits() ^ m.watchedOn * 977L).nextInt(3) == 0;
    }

    /** In among their houses: closer, counting as it goes, and at the barracks and the granary, their secrets. */
    static void slip(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Mission m, Villages.Village o) {
        f.hobbyNow = "slipping into " + m.name + "'s streets";
        if (f.tickCount - m.countedTick >= 40) {
            m.countedTick = f.tickCount;
            count(level, f, m, o);
        }
        BlockPos to = m.slipTo != null ? m.slipTo : m.heart;
        double d = Scouts.flat(f.blockPosition(), to);
        if (d > 4 * 4 && f.tickCount < m.until) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(to, 0.9D);
            return;
        }
        if (d <= 6 * 6) {
            // In among the houses, and nobody has stopped it: the barracks' beds counted through a window, the
            // granary's door looked in at. What the town keeps out of sight is no secret from it now.
            Intel.Report ex = Intel.exact(level, o.id(), level.getDayTime() / 24000L);
            m.truth = new int[]{ ex.guards(), ex.armoured(), ex.archers(), ex.foodDays() };
            m.secrets = true;
            LOG.info("[MCA-SPY] {} slipped into {}: the barracks hold {} guards, the stores {} days of food", f.displayNameCap(), m.name,
                ex.guards(), ex.foodDays());
        }
        m.phase = Phase.HOME;
        Scouts.turnBack(level, f, e, m.secrets ? "I had a look round their streets" : "I could not get close");
        FolkTalk.speak(f, m.secrets ? "Got it — every bed in their barracks counted. Now out of here." : "Too many eyes. Home with what I have.");
    }

    /** Seen, and running for it: away from whoever is after it, until it has shaken them off. */
    static boolean flee(ServerLevel level, VillageFolkEntity f, Mission m) {
        Entity c = m.chaser == null ? null : level.getEntity(m.chaser);
        if (!(c instanceof LivingEntity g) || !g.isAlive() || g.distanceToSqr(f) > 40 * 40 || !Spies.chasing(m.chaser)) {
            m.phase = Phase.HOME;
            m.chaser = null;
            return false;
        }
        Vec3 away = f.position().subtract(g.position());
        away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize().scale(10);
        BlockPos run = Scouts.surface(level, BlockPos.containing(f.getX() + away.x, f.getY(), f.getZ() + away.z));
        f.getNavigation().moveTo(run.getX() + 0.5, run.getY(), run.getZ() + 0.5, 1.3D);
        f.hobbyNow = "running from " + m.name + "'s watch";
        return true;
    }

    /** It has been seen, and somebody is after it (Spies): up, and run. */
    static void spotted(ServerLevel level, VillageFolkEntity spy, VillageFolkEntity by) {
        Scouts.Expedition e = spy.expedition();
        Mission m = e == null ? null : e.mission;
        if (m == null) return;
        m.seen = true;
        m.chaser = by.getUUID();
        stand(spy);
        if (!e.returning) Scouts.turnBack(level, spy, e, "I was seen, and ran for it");
        m.phase = Phase.FLED;
        FolkTalk.speak(spy, FolkTalk.pick(spy.getRandom(), "They've seen me — run!", "Time I wasn't here!", "Blast. Away, quick!"));
    }

    /** Up out of the grass. */
    static void stand(VillageFolkEntity f) {
        if (f.getPose() == Pose.CROUCHING) f.setPose(Pose.STANDING);
    }

    // ------------------------------------------------------------------ counting

    /** What there is to see once: the wall and its gates, and a guess at the food from the fields and the granary. */
    static void sample(ServerLevel level, VillageFolkEntity f, Mission m, Villages.Village o) {
        int[] w = Intel.walls(level, o.id());
        m.walls = w[0];
        m.gates = w[1];
        m.foodDays = foodSeen(f, m, o.id());
        m.sampled = true;
    }

    /**
     * What can be seen of a town's food from outside it: its fields, its pens, a granary, the carts coming in.
     * Its own books say what is there; the scout's guess is out by up to a third, less with sharp eyes or a
     * granary to look at.
     */
    static int foodSeen(VillageFolkEntity f, Mission m, UUID them) {
        Leader.Books b = Leader.books(them);
        double truth = b == null ? 10.0 : Math.max(0.0, Math.min(999.0, b.days()));
        double err = 0.33 - (FolkSkills.active(f, FolkSkills.Knack.SHARP_EYES) ? 0.1 : 0.0) - (Villages.hasBuilt(them, "granary") ? 0.1 : 0.0);
        Random rng = new Random(f.getUUID().getLeastSignificantBits() ^ them.getMostSignificantBits() ^ m.watchedOn * 131L);
        return (int) Math.round(truth * (1.0 + (rng.nextDouble() * 2.0 - 1.0) * err));
    }

    /**
     * One look over the town from where it lies: every grown folk of theirs within sight, out of doors (or up
     * on the wall) and not hidden from it by the lie of the land or a house, noted; and the fighters among
     * them (the watch, and the militia once there is one) with their iron and their bows. Each is counted
     * once however often it walks past: a minute's watching sees most of a watch walking its beats, and
     * none of one sitting in the barracks.
     */
    static void count(ServerLevel level, VillageFolkEntity f, Mission m, Villages.Village o) {
        double sight = SIGHT + 4.0 * FolkSkills.sightBonus(f);
        Set<UUID> militia = Intel.militia(o.id());
        for (AssistantEntity a : Villages.folkOf(o.id())) {
            if (!(a instanceof VillageFolkEntity x) || x.isBaby() || !x.isAlive() || x.isShowcase() || x.level() != level) continue;
            if (x.distanceToSqr(f) > sight * sight) continue;
            boolean open = x.post() != null || level.canSeeSky(x.blockPosition().above());
            if (!open || !f.hasLineOfSight(x)) continue;
            m.folk.add(x.getUUID());
            if (!Intel.fighter(x, militia)) continue;
            m.guards.add(x.getUUID());
            if (Intel.armoured(x)) m.armoured.add(x.getUUID());
            if (Intel.archer(x)) m.archers.add(x.getUUID());
        }
    }

    // ------------------------------------------------------------------ home (Scouts.home)

    /** Home from watching: the report filed, dated the day it watched, and told. */
    static void home(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e) {
        Mission m = e.mission;
        stand(f);
        long today = level.getDayTime() / 24000L;
        UUID us = e.village;
        if (m.freed) {
            Villages.tell(us, today, f.displayNameCap() + " came home from " + m.name + ", where " + f.displayNameCap() + " had been held");
            FolkTalk.speak(f, "Home! I never thought I'd be so glad to see these streets.");
            f.persona().remember(today, "I came home after being held in " + m.name, 6);
            return;
        }
        if (m.watchedOn < 0) {
            Villages.tell(us, today, f.displayNameCap() + " came back without a look at " + m.name + " (" + e.why + ")");
            FolkTalk.speak(f, "I never got near " + m.name + ". Another day.");
            return;
        }
        Intel.Report r = report(m);
        Intel.file(us, r);
        // A council that went to war on a guess is told how far out the guess was, now it has a count (Intel.learned).
        Intel.learned(us, m.them, r.guards(), today);
        String said = Intel.summary(r);
        Villages.tell(us, today, f.displayNameCap() + " came back from watching " + m.name + ": " + said);
        Scouts.report(us, "Our scout " + f.displayNameCap() + " watched " + m.name + (m.watchedOn < today ? " yesterday" : " today")
            + ": " + said + (r.note().isEmpty() ? "" : " (" + r.note() + ")") + ".");
        f.persona().remember(today, "I watched " + m.name + " from " + m.from + " and counted " + r.guards() + " guards", 5);
        tellTheLeader(level, f, us, m, r);
        LOG.info("[MCA-SPY] {} home from {}: filed {} guards, {} armoured, {} archers, walls {}, gates {}, food {}, folk {} ({})",
            f.displayNameCap(), m.name, r.guards(), r.armoured(), r.archers(), r.walls(), r.gates(), r.foodDays(), r.folk(), r.note());
    }

    /** The report as filed: what it counted (or, if it got in among the houses, what the barracks and the granary told it). */
    static Intel.Report report(Mission m) {
        int guards = m.guards.size(), armoured = m.armoured.size(), archers = m.archers.size(), food = m.foodDays;
        if (m.secrets && m.truth.length == 4) {
            guards = m.truth[0];
            armoured = m.truth[1];
            archers = m.truth[2];
            food = m.truth[3];
        }
        int houses = 0;
        for (String s : Villages.builtList(m.them)) if (s.startsWith("house") || s.startsWith("flats") || s.equals("manor")) houses++;
        int folk = Math.max(m.folk.size(), Math.round(houses * 2.5F));
        return new Intel.Report(m.them, m.watchedOn, folk, guards, armoured, archers, m.walls, m.gates, food, words(m));
    }

    /** The scout's own words for how it went. */
    static String words(Mission m) {
        List<String> bits = new ArrayList<>();
        bits.add("watched from " + m.from + (m.rode != null ? ", on " + m.rode : ""));
        if (m.secrets) bits.add("slipped into their streets and counted the barracks and the granary");
        if (m.seen) bits.add("I was seen, and had to run for it");
        if (m.guards.isEmpty() && !m.secrets) bits.add("not a guard to be seen: kept indoors, or none to keep");
        return String.join("; ", bits);
    }

    /** To the leader, if it is about: what was seen, and what the leader makes of it. */
    static void tellTheLeader(ServerLevel level, VillageFolkEntity f, UUID us, Mission m, Intel.Report r) {
        VillageFolkEntity leader = Envoys.leader(us);
        if (leader == null || leader == f || leader.distanceToSqr(f) > 32 * 32) {
            FolkTalk.speak(f, "Home. I've news of " + m.name + " for the elder: " + TownCalendar.inWords(Math.min(99, r.guards()))
                + (r.guards() == 1 ? " guard." : " guards."));
            return;
        }
        f.getLookControl().setLookAt(leader, 30.0F, 30.0F);
        FolkTalk.speak(f, "Elder, I've been to " + m.name + ": " + Intel.summary(r) + ".");
        double odds = Intel.strength(level, us, m.them).attack();
        leader.sayLater(odds >= 1.2 ? FolkTalk.pick(leader.getRandom(), "Good work. We have the better of them.", "Is that all? Good.")
            : odds < 0.8 ? FolkTalk.pick(leader.getRandom(), "Then we look to our walls.", "More than I'd like. We'll not be marching yet.")
            : "Evenly matched, then. We'll think on it.", 60);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the spy put down at its vantage now, ready to watch (as if it had walked there). */
    public static void atVantageForTests(VillageFolkEntity f) {
        Scouts.Expedition e = f.expedition();
        if (e == null || e.mission == null) return;
        BlockPos at = e.target;
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, f.getYRot(), 0.0F);
        f.getNavigation().stop();
        Villages.Village o = Villages.get(e.mission.them);
        if (e.mission.phase == Phase.GOING && o != null && f.level() instanceof ServerLevel level) beginWatch(level, f, e.mission, o);
    }

    /**
     * /village war scout home: this town's spies who have lain at their vantage and looked, home now with what
     * they have counted, their reports filed; one still on its way there is left to it (it has nothing to tell),
     * unless it is already at its vantage, when it takes its look first. A line a spy, for the command.
     */
    public static List<String> homeNow(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.expedition() == null) continue;
            Scouts.Expedition e = f.expedition();
            Mission m = e.mission;
            if (m == null || m.freed) continue;
            Villages.Village o = Villages.get(m.them);
            if (!m.watched() && m.phase == Phase.GOING && o != null && Scouts.flat(f.blockPosition(), e.target) <= 12 * 12) {
                beginWatch(level, f, m, o);
            }
            if (!m.watched()) {
                out.add(f.displayNameCap() + ": still on the way to " + m.name + ", " + (int) Math.sqrt(Scouts.flat(f.blockPosition(), e.target))
                    + " blocks from its vantage; nothing to tell yet");
                continue;
            }
            homeNowForTests(level, f);
            Intel.Report r = Intel.latest(v.id(), m.them);
            out.add(f.displayNameCap() + ": " + (r == null ? "no report" : "report on " + m.name + " for day " + r.day() + ": " + Intel.summary(r)));
        }
        return out;
    }

    /** Tests: the spy home now (as if it had walked back along its trail): its report filed. */
    public static void homeNowForTests(ServerLevel level, VillageFolkEntity f) {
        Scouts.Expedition e = f.expedition();
        if (e == null) return;
        Villages.Village v = Villages.get(e.village);
        if (v != null) f.moveTo(v.centre().getX() + 0.5, v.centre().getY(), v.centre().getZ() + 0.5, f.getYRot(), 0.0F);
        Scouts.release(level, f, e);
        f.expedition(null);
        if (e.mission != null) home(level, f, e);
    }

    /** Tests: the mission this folk is on, or null. */
    @Nullable
    public static Mission missionOf(VillageFolkEntity f) {
        return f.expedition() == null ? null : f.expedition().mission;
    }
}
