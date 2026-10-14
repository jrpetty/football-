package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [defence] The muster at the square. When the bell rings (a raid, a raiding party sighted, monsters inside the wall,
 * an enemy on the approach), every citizen who is not of the watch drops what it is doing, gets up out of bed if it
 * was asleep, and runs to the village square: the children and the frail into the middle first, the rest close round
 * them, and there they wait till the bell stops. Folk far out (in the fields, the woods, a mine) come in through the
 * nearest gate, or shelter where they are if something is between them and the square; nobody goes back to work.
 *
 * <p>The watch rings them round: as many guards as the ring wants (one every four blocks of it, three at least and a
 * dozen at most) stand evenly round the gathered folk facing out, the captain on the side the trouble comes from,
 * calling out; each steps out to meet whatever comes at the square and back to its place once it is down. Guards past
 * what the ring wants go up on the walls with bows (the gatehouse towers and the walkway, Raids.postFor), as before;
 * the town's iron golems take places in the ring between the guards.
 *
 * <p>At the all-clear the folk go back to what they were doing (or to bed), the golems to their posts, and the night
 * goes into the town's history as before, with the muster in it. The old way (everybody indoors, every guard on the
 * walls) is kept behind a switch (AssistantConfig raidMusterAtSquare).
 */
public final class Muster {

    private Muster() {}

    /** One block of ring for each guard it wants; its least and most. */
    static final double PER_GUARD = 4.0;
    static final int RING_LEAST = 3, RING_MOST = 12;
    /** How near the square a monster has to come before a guard of the ring steps out to it. */
    static final double STEP_OUT = 7.0;
    /** How many looks a child stands at the edge of the crowd before it is lifted through to its place. */
    static final int LIFT_AFTER = 20;
    /** How many points round the ring are looked at for level ground when the place is chosen. */
    static final int RING_LOOKS = 24;
    /** How near its mark a guard of the ring stands (blocks). */
    static final double PLACED = 0.3;

    /** A town's muster: where, how big, and who stands where. */
    static final class Gathering {
        final BlockPos at;
        final double inner, ring;
        final long since;
        final Map<UUID, BlockPos> spots = new HashMap<>();
        final List<BlockPos> free = new ArrayList<>();
        final Set<UUID> golems = new HashSet<>();
        final Set<UUID> sheltering = new HashSet<>();
        final Map<UUID, Integer> stuck = new HashMap<>();
        /** How many looks each child has stood at the edge of the crowd, short of its place. */
        final Map<UUID, Integer> atEdge = new HashMap<>();
        long lastCall = -100000L;
        /** The middle places still kept for the children and the frail on their way. */
        int kept;

        Gathering(BlockPos at, double inner, double ring, long since) {
            this.at = at;
            this.inner = inner;
            this.ring = ring;
            this.since = since;
        }
    }

    private static final Map<UUID, Gathering> NOW = new ConcurrentHashMap<>();
    private static volatile Boolean wayForTests;

    public static void resetForTests() {
        NOW.clear();
        wayForTests = null;
    }

    /** Tests: the muster (true) or the old way (false), whatever the config says; null to go by it again. */
    public static void forTests(@Nullable Boolean muster) {
        wayForTests = muster;
    }

    /** Does the town muster at the square when the bell rings (rather than the old way)? */
    public static boolean on() {
        Boolean t = wayForTests;
        return t != null ? t : AssistantConfig.raidMuster();
    }

    /** Is the muster called in this town now? */
    public static boolean called(@Nullable UUID village) {
        return village != null && on() && Raids.underAlarm(village);
    }

    // ------------------------------------------------------------------ the bell

    /** The bell has just been rung (Raids.raise): the place worked out, and everybody up and out. */
    public static void raised(ServerLevel level, Villages.Village v) {
        if (!on()) return;
        Gathering g = gathering(level, v);
        if (g == null) return;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() == AssistantEntity.StationTask.GUARD) continue;
            if (f.isSleeping()) f.stopSleeping();
            if (f.peekJob() != null) f.clearQueue();
        }
    }

    /** The town's muster, worked out once a bell. */
    @Nullable
    static Gathering gathering(ServerLevel level, Villages.Village v) {
        Gathering g = NOW.get(v.id());
        if (g != null) return g;
        int folk = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a.stationTask() != AssistantEntity.StationTask.GUARD) folk++;
        double inner = 1.2 + 0.55 * Math.sqrt(Math.max(1, folk));
        BlockPos at = place(level, v, inner);
        if (at == null) return null;
        g = new Gathering(at, inner, inner + 2.5, level.getGameTime());
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && f.stationTask() != AssistantEntity.StationTask.GUARD && (f.isBaby() || Neighbourly.frail(f))) g.kept++;
        }
        // The places in the crowd, the middle first: close together, a little over a block apart.
        g.free.add(at);
        for (double r = 1.2; r <= inner + 0.6; r += 1.1) {
            int n = Math.max(6, (int) (2 * Math.PI * r / 1.1));
            for (int i = 0; i < n; i++) {
                double ang = 2 * Math.PI * i / n + r;
                BlockPos col = BlockPos.containing(at.getX() + 0.5 + r * Math.cos(ang), at.getY(), at.getZ() + 0.5 + r * Math.sin(ang));
                BlockPos p = Assemblies.standable(level, col, at.getY());
                if (p != null && !g.free.contains(p)) g.free.add(p);
            }
        }
        NOW.put(v.id(), g);
        return g;
    }

    /**
     * Where on the square the town gathers: the open ground before the board (the courtyard) if the town has one,
     * else by the heart; of the spots near that, the one with the most room round it to stand on.
     */
    @Nullable
    static BlockPos place(ServerLevel level, Villages.Village v, double inner) {
        BlockPos heart = v.centre();
        BlockPos want = heart;
        int[] court = Villages.courtRect(v.id());
        if (court != null) want = new BlockPos((court[0] + court[1]) / 2, heart.getY(), (court[2] + court[3]) / 2);
        int reach = (int) Math.ceil(inner + 1);
        // The ring stands round the crowd: its whole circle wants level ground clear of the heart's stall and the
        // board, or a guard's mark falls on a block it cannot stand on and the ring stands lopsided.
        double ringR = inner + 2.5;
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos col = want.offset(dx, 0, dz);
                // The crowd on the square, inside its wall's line (the ring round it may stand out on the street).
                if (Math.max(Math.abs(col.getX() - heart.getX()), Math.abs(col.getZ() - heart.getZ())) > com.jrpetty.mcassistant.village.TownPlan.PLAZA - reach - 1) continue;
                BlockPos c = Assemblies.standable(level, col, heart.getY());
                if (c == null) continue;
                int room = 0, all = 0;
                for (int x = -reach; x <= reach; x++) {
                    for (int z = -reach; z <= reach; z++) {
                        if (x * x + z * z > reach * reach) continue;
                        all++;
                        BlockPos p = Assemblies.standable(level, c.offset(x, 0, z), c.getY());
                        if (p != null && Math.abs(p.getY() - c.getY()) <= 1) room++;
                    }
                }
                int ringRoom = 0;
                for (int i = 0; i < RING_LOOKS; i++) {
                    double a = 2 * Math.PI * i / RING_LOOKS;
                    BlockPos p = Assemblies.standable(level, BlockPos.containing(c.getX() + 0.5 + ringR * Math.cos(a), c.getY(), c.getZ() + 0.5 + ringR * Math.sin(a)), c.getY());
                    if (p != null && p.getY() == c.getY()) ringRoom++;
                }
                int score = room * 4 + ringRoom * 8 - (Math.abs(dx) + Math.abs(dz));
                if (score > bestScore) { bestScore = score; best = c; }
                if (room == all && ringRoom == RING_LOOKS && dx == 0 && dz == 0) return c;
            }
        }
        if (best != null) return best;
        return Assemblies.standable(level, heart, heart.getY());
    }

    // ------------------------------------------------------------------ the folk

    /**
     * A folk's part when the bell rings (VillageFolkEntity.bedtime): up, and to its place in the crowd on the square;
     * there it waits. False if the town is not mustering (the old way: indoors), or this one is of the watch.
     */
    public static boolean gather(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (!called(id) || f.stationTask() == AssistantEntity.StationTask.GUARD || f.isHired()) return false;
        if (!(f.level() instanceof ServerLevel level)) return false;
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        Gathering g = gathering(level, v);
        if (g == null) return false;
        if (f.isSleeping()) f.stopSleeping();
        BlockPos spot = spotFor(g, f);
        double dx = f.getX() - (spot.getX() + 0.5), dz = f.getZ() - (spot.getZ() + 0.5);
        double d2 = dx * dx + dz * dz;
        // Far out with the trouble between it and the square: it shelters where it is (indoors, if it can).
        if (d2 > 40 * 40 && (g.sheltering.contains(f.getUUID()) || threatNear(level, f, 10.0))) {
            g.sheltering.add(f.getUUID());
            return Raids.shelter(f);
        }
        // A child or one of the frail come late to a crowd already close round its place in the middle: the grown
        // folk lift it through to it, as anybody would (it cannot shoulder its way in).
        if (d2 > 2.0 && (f.isBaby() || Neighbourly.frail(f)) && d2 <= (g.inner + 2.5) * (g.inner + 2.5)) {
            int at = g.atEdge.merge(f.getUUID(), 1, Integer::sum);
            if (at >= LIFT_AFTER) {
                f.getNavigation().stop();
                f.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                g.atEdge.remove(f.getUUID());
                return true;
            }
        }
        if (d2 > 2.0) {
            if (f.getNavigation().isDone() || f.tickCount % 20 == 0) {
                boolean going = f.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, f.isBaby() ? 1.4D : 1.3D);
                // No way there (cut off, or out in a mine): it shelters where it is rather than stand in the open.
                if (!going && d2 > 12 * 12) {
                    int n = g.stuck.merge(f.getUUID(), 1, Integer::sum);
                    if (n >= 3) g.sheltering.add(f.getUUID());
                }
            }
            if (d2 > 30 * 30) f.setSprinting(true);
        } else {
            f.getNavigation().stop();
            f.setSprinting(false);
            f.getLookControl().setLookAt(g.at.getX() + 0.5, f.getEyeY(), g.at.getZ() + 0.5);
        }
        return true;
    }

    /**
     * Between its looks (the folk think once in five seconds), the muster holds a folk of the town (VillageFolkEntity
     * aiStep, before the day's errands): nothing else of its day (a meal, the assembly, a neighbour's call, indoors at
     * the sight of a monster with the ring round it) takes it out of its place in the crowd. Its place is looked to
     * twice a second; a folk sheltering where it is, or laid up, is left to that.
     */
    public static boolean hold(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (!called(id) || f.stationTask() == AssistantEntity.StationTask.GUARD || f.isHired() || Health.laidUp(f)) return false;
        Gathering g = NOW.get(id);
        if ((f.tickCount + f.getId()) % 10 != 0) return g != null && !g.sheltering.contains(f.getUUID());
        return gather(f) && (g == null || !g.sheltering.contains(f.getUUID()));
    }

    /** A folk's place in the crowd: the children and the frail nearest the middle (their places kept for them
     *  while they come), the rest round them. Kept for the bell. */
    static BlockPos spotFor(Gathering g, VillageFolkEntity f) {
        BlockPos have = g.spots.get(f.getUUID());
        if (have != null) return have;
        BlockPos pick = null;
        if (!g.free.isEmpty()) {
            if (f.isBaby() || Neighbourly.frail(f)) {
                pick = g.free.remove(0);
                g.kept = Math.max(0, g.kept - 1);
            } else {
                pick = g.free.remove(Math.min(g.kept, g.free.size() - 1));
            }
        }
        if (pick == null) {
            int n = g.spots.size();
            double ang = n * 2.39996, r = g.inner + 0.5 + (n % 3) * 0.6;
            pick = BlockPos.containing(g.at.getX() + 0.5 + r * Math.cos(ang), g.at.getY(), g.at.getZ() + 0.5 + r * Math.sin(ang));
        }
        g.spots.put(f.getUUID(), pick);
        return pick;
    }

    static boolean threatNear(ServerLevel level, LivingEntity f, double r) {
        return !level.getEntitiesOfClass(Mob.class, f.getBoundingBox().inflate(r), m -> m.isAlive() && Raids.hostile(m)).isEmpty();
    }

    // ------------------------------------------------------------------ the watch

    /** The guards of the ring, in their order round it: the captain first, then by id. */
    static List<VillageFolkEntity> ringGuards(UUID village, Gathering g) {
        List<VillageFolkEntity> watch = new ArrayList<>(Patrols.watch(village));
        VillageFolkEntity captain = Roster.captain(village);
        watch.sort(Comparator.comparing((VillageFolkEntity x) -> captain != null && x == captain ? 0 : 1).thenComparing(VillageFolkEntity::getUUID));
        int want = wanted(g);
        return watch.size() > want ? new ArrayList<>(watch.subList(0, want)) : watch;
    }

    /** How many guards the ring wants. */
    static int wanted(Gathering g) {
        return Math.max(RING_LEAST, Math.min(RING_MOST, (int) Math.round(2 * Math.PI * g.ring / PER_GUARD)));
    }

    /** Is this guard one of the ring round the square now (and so not for the walls)? */
    public static boolean inRing(VillageFolkEntity guard) {
        UUID id = guard.ownerId();
        Gathering g = id == null || !called(id) ? null : NOW.get(id);
        return g != null && ringGuards(id, g).contains(guard);
    }

    /**
     * A guard's part when the bell rings (Raids.guardDuty): its place in the ring round the gathered folk, facing out;
     * out to meet whatever comes at the square, and back. Null if the town is not mustering, or the ring has enough
     * guards without this one (it goes up on the wall).
     */
    @Nullable
    public static Boolean guard(ServerLevel level, Villages.Village v, VillageFolkEntity guard, @Nullable Direction from) {
        if (!on()) return null;
        Gathering g = gathering(level, v);
        if (g == null) return null;
        List<VillageFolkEntity> ring = ringGuards(v.id(), g);
        int k = ring.indexOf(guard);
        if (k < 0) return null;
        if (guard.post() != null) guard.holdPost(null);
        // Whatever comes at the square: the nearest guard to it steps out to meet it.
        LivingEntity target = guard.getTarget();
        if (target != null && (!target.isAlive() || horizontal(target, g.at) > g.ring + STEP_OUT + 3)) {
            guard.setTarget(null);
            target = null;
        }
        if (target == null) {
            Mob coming = comingAt(level, g, guard);
            if (coming != null) {
                guard.setTarget(coming);
                return true;
            }
        } else {
            return true;
        }
        BlockPos spot = ringSpot(level, g, k, ring.size(), from);
        // Its exact place on the ring (not the middle of the block it falls in), so the ring stands evenly spaced.
        double[] at = ringPoint(g, k, ring.size(), from);
        double tx = BlockPos.containing(at[0], spot.getY(), at[1]).equals(spot) ? at[0] : spot.getX() + 0.5;
        double tz = BlockPos.containing(at[0], spot.getY(), at[1]).equals(spot) ? at[1] : spot.getZ() + 0.5;
        double dx = guard.getX() - tx, dz = guard.getZ() - tz, d2 = dx * dx + dz * dz;
        if (d2 > 2.25) {
            if (guard.getNavigation().isDone() || guard.tickCount % 20 == 0) {
                guard.getNavigation().moveTo(tx, spot.getY(), tz, 1.3D);
            }
        } else if (d2 > PLACED * PLACED) {
            guard.getNavigation().stop();
            guard.teleportTo(tx, spot.getY(), tz);                  // the last shuffle onto its mark (the path ends a block short)
        } else {
            guard.getNavigation().stop();
            // Facing out, away from the folk.
            double ox = tx - (g.at.getX() + 0.5), oz = tz - (g.at.getZ() + 0.5);
            guard.getLookControl().setLookAt(tx + ox * 4, guard.getEyeY(), tz + oz * 4);
        }
        // The captain calls out, every so often.
        if (k == 0 && level.getGameTime() - g.lastCall > 240L) {
            g.lastCall = level.getGameTime();
            FolkTalk.speak(guard, FolkTalk.pick(guard.getRandom(), "Hold the ring!", "Steady, watch — nothing gets to the square!",
                from != null ? "They're coming from the " + from.getName() + "! Eyes out!" : "Eyes out, all round!",
                "Stay together in the middle, everyone!", "Keep your places!"));
        }
        return true;
    }

    /** Where on the ring the k-th of so many guards stands: evenly round it, the first (the captain) toward the
     *  trouble. {x, z}. */
    static double[] ringPoint(Gathering g, int k, int of, @Nullable Direction from) {
        double ang = angle(k, of, from);
        return new double[]{ g.at.getX() + 0.5 + g.ring * Math.cos(ang), g.at.getZ() + 0.5 + g.ring * Math.sin(ang) };
    }

    private static double angle(int k, int of, @Nullable Direction from) {
        double base = from == null ? 0.0 : Math.atan2(from.getStepZ(), from.getStepX());
        return base + 2 * Math.PI * k / Math.max(1, of);
    }

    /** A guard's place in the ring as a block to stand in: its point's, or the nearest along its line that will do. */
    static BlockPos ringSpot(ServerLevel level, Gathering g, int k, int of, @Nullable Direction from) {
        double ang = angle(k, of, from);
        for (double r : new double[]{ g.ring, g.ring + 1, g.ring - 1, g.ring + 2 }) {
            BlockPos col = BlockPos.containing(g.at.getX() + 0.5 + r * Math.cos(ang), g.at.getY(), g.at.getZ() + 0.5 + r * Math.sin(ang));
            BlockPos p = Assemblies.standable(level, col, g.at.getY());
            if (p != null) return p;
        }
        return BlockPos.containing(g.at.getX() + 0.5 + g.ring * Math.cos(ang), g.at.getY(), g.at.getZ() + 0.5 + g.ring * Math.sin(ang));
    }

    /** The nearest thing hostile coming at the square that this guard is the nearest of the ring to, or null. */
    @Nullable
    static Mob comingAt(ServerLevel level, Gathering g, VillageFolkEntity guard) {
        double reach = g.ring + STEP_OUT;
        AABB box = new AABB(g.at).inflate(reach, 8, reach);
        Mob best = null;
        double bd = Double.MAX_VALUE;
        for (Mob m : level.getEntitiesOfClass(Mob.class, box, x -> x.isAlive() && Raids.hostile(x))) {
            if (horizontal(m, g.at) > reach) continue;
            double d = m.distanceToSqr(guard);
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    private static double horizontal(LivingEntity e, BlockPos p) {
        double dx = e.getX() - (p.getX() + 0.5), dz = e.getZ() - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------------ the golems

    /** A golem of the town's (Golems.tick): into the ring between the guards while the town musters. */
    public static boolean golem(ServerLevel level, Villages.Village v, Mob golem) {
        if (!called(v.id()) || !(golem instanceof IronGolem)) return false;
        Gathering g = gathering(level, v);
        if (g == null) return false;
        g.golems.add(golem.getUUID());
        List<UUID> gs = new ArrayList<>(g.golems);
        gs.sort(Comparator.naturalOrder());
        int k = gs.indexOf(golem.getUUID()), of = gs.size();
        BlockPos spot = ringSpot(level, g, 0, 1, null);
        double ang = Math.PI / Math.max(1, ringGuards(v.id(), g).size()) + 2 * Math.PI * k / Math.max(1, of);
        BlockPos col = BlockPos.containing(g.at.getX() + 0.5 + (g.ring + 0.5) * Math.cos(ang), g.at.getY(), g.at.getZ() + 0.5 + (g.ring + 0.5) * Math.sin(ang));
        BlockPos p = Assemblies.standable(level, col, g.at.getY());
        if (p != null) spot = p;
        golem.restrictTo(spot, 4);
        LivingEntity t = golem.getTarget();
        if (t != null && t.isAlive() && horizontal(t, g.at) <= g.ring + STEP_OUT + 3) return true;
        if (golem.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > 2.0 && golem.getNavigation().isDone()) {
            golem.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.8D);
        }
        return true;
    }

    // ------------------------------------------------------------------ the all-clear

    /** The bell has stopped (Raids.end): the golems back to their posts, and the muster into the history. */
    public static void end(ServerLevel level, Villages.Village v) {
        Gathering g = NOW.remove(v.id());
        if (g == null) return;
        for (UUID u : g.golems) if (level.getEntity(u) instanceof Mob m) m.clearRestriction();
        int folk = 0;
        for (Map.Entry<UUID, BlockPos> e : g.spots.entrySet()) {
            if (level.getEntity(e.getKey()) instanceof VillageFolkEntity f && f.isAlive()
                && f.distanceToSqr(g.at.getX() + 0.5, f.getY(), g.at.getZ() + 0.5) < (g.inner + 3) * (g.inner + 3)) folk++;
        }
        int ring = ringGuards(v.id(), g).size();
        long day = level.getDayTime() / 24000L;
        if (level.getGameTime() - g.since > 200L && folk > 0) {
            Villages.tell(v.id(), day, folk + " of the town gathered on the square while " + ring + " of the watch"
                + (g.golems.isEmpty() ? "" : " and " + g.golems.size() + (g.golems.size() == 1 ? " golem" : " golems"))
                + " held the ring round them");
        }
        for (UUID u : g.spots.keySet()) {
            if (level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive() && f.getRandom().nextInt(4) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The bell's stopped. Back to it.", "That's over, then.", "Thank the watch!"));
            }
        }
    }

    // ------------------------------------------------------------------ tests and the stage

    /** Tests: where the town gathers now (null if it is not mustering). */
    @Nullable
    public static BlockPos pointForTests(UUID village) {
        Gathering g = NOW.get(village);
        return g == null ? null : g.at;
    }

    /** Tests: {the crowd's radius, the ring's radius, the guards the ring wants}. */
    public static double[] sizeForTests(UUID village) {
        Gathering g = NOW.get(village);
        return g == null ? new double[3] : new double[]{ g.inner, g.ring, wanted(g) };
    }

    /** Tests: the guards of the ring now. */
    public static List<VillageFolkEntity> ringForTests(ServerLevel level, Villages.Village v) {
        Gathering g = gathering(level, v);
        return g == null ? List.of() : ringGuards(v.id(), g);
    }
}
