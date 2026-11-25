package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [tp46] A night's sleep for a folk with no bed it can get to.
 *
 * <p>A folk whose look for a bed found none free (the town a house short), or that gave up every bed it tried as out of
 * its reach (up a stair its walk could not find, across the town and cut off), used to stand about its house till the
 * morning: a third of a town of thirty awake at one in the morning. Now it lies down on the floor indoors, as anybody put
 * up for the night without a bed does: in its own house if it has one, else at its work's ground or by the town's heart,
 * on a floor with a roof over it, by the hearth if there is one (a furnace, a smoker, a campfire), never in a doorway nor
 * where another already lies. It walks there (no jump in a player's sight; out of sight a walk that gets nowhere is
 * finished in a step), lies down, and is up with the others in the morning. Nothing is placed in the world.
 *
 * <p>The game stands a sleeper up whose "bed" is no bed; a folk asleep on its floor spot is let lie (the sleeping check).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Bedside {

    private Bedside() {}

    /** How far round its house (or wherever it settles) a floor is looked for, and how far up and down. */
    static final int REACH = 9, DEPTH = 4;
    /** Ticks of walking that get it no nearer before the spot is given up (out of sight: the last of it stepped). */
    static final int NO_HEADWAY = 400;
    /** Spots tried in a night before it lies down where it stands (indoors) or stands the night out. */
    static final int MOST_TRIES = 3;

    static final class Spot {
        final long night;
        @Nullable BlockPos at;
        int tries;
        int since, gained;
        double best = Double.MAX_VALUE;
        final java.util.List<BlockPos> tried = new java.util.ArrayList<>();
        Spot(long night) { this.night = night; }
    }

    private static final Map<UUID, Spot> SPOTS = new ConcurrentHashMap<>();
    /** Folk lying on a floor now, and where (the sleeping check; nobody else lies down on top of them). */
    private static final Map<UUID, BlockPos> LYING = new ConcurrentHashMap<>();
    /** The tests: nights spent on a floor. */
    public static volatile int FLOOR_NIGHTS;

    public static void resetForTests() {
        SPOTS.clear();
        LYING.clear();
        FLOOR_NIGHTS = 0;
    }

    /** [tp46] The hour past which a bed this far off is not walked to: the night is spent on a floor nearer. */
    public static final long TOO_LATE = 17500L;
    public static final int TOO_FAR = 40;

    /** Is this folk asleep on a floor (not in a bed)? */
    public static boolean onAFloor(AssistantEntity f) {
        return f.isSleeping() && LYING.containsKey(f.getUUID());
    }

    /** The night a day time belongs to (from noon to noon), so a new night starts the looking afresh. */
    private static long nightOf(long dayTime) {
        return Math.floorDiv(dayTime + 12000L, 24000L);
    }

    /**
     * Its bedtime with no bed: to a floor indoors and down on it. True while this has it (walking there, or lying down);
     * false if it found nowhere (it stands the night out at home, as before).
     */
    public static boolean onTheFloor(VillageFolkEntity f) {
        return onTheFloor(f, false);
    }

    /** As above; {@code here}: a floor round where it stands, not round its house (its bed too far to walk to tonight). */
    public static boolean onTheFloor(VillageFolkEntity f, boolean here) {
        if (!(f.level() instanceof ServerLevel level) || f.isPassenger()) return false;
        long night = nightOf(level.getDayTime());
        Spot s = SPOTS.get(f.getUUID());
        if (s == null || s.night != night) {
            s = new Spot(night);
            SPOTS.put(f.getUUID(), s);
        }
        if (s.at == null) {
            if (s.tries >= MOST_TRIES) return lastResort(f, s);
            s.at = choose(level, f, s, here);
            s.tries++;
            s.since = f.tickCount;
            s.gained = f.tickCount;
            s.best = Double.MAX_VALUE;
            if (s.at == null) { s.tries = MOST_TRIES; return lastResort(f, s); }
        }
        BlockPos at = s.at;
        double dx = at.getX() + 0.5 - f.getX(), dz = at.getZ() + 0.5 - f.getZ();
        double d2 = dx * dx + dz * dz;
        boolean level0 = Math.abs(f.getY() - at.getY()) < 1.2;
        if (d2 <= 0.8 * 0.8 && level0) {
            lie(f, at);
            return true;
        }
        if (d2 < s.best - 1.0) { s.best = d2; s.gained = f.tickCount; }
        if (f.tickCount - s.gained > NO_HEADWAY) {
            // No nearer in twenty seconds: out of sight the last of the way is a step; in sight another floor.
            if (level0 || d2 < 64.0) {
                if (Blink.mayJumpTo(f, at.getX() + 0.5, at.getZ() + 0.5, "bed: a floor indoors it could not walk to, put on it")) {
                    f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, f.getYRot(), f.getXRot());
                    f.getNavigation().stop();
                    lie(f, at);
                    return true;
                }
            }
            s.tried.add(at);
            s.at = null;
            return true;
        }
        // The last few steps led (the pathfinder stops a block short of a spot in a corner).
        if (d2 <= 3.0 * 3.0 && level0 && !Stroll.led(f) && (f.getNavigation().isDone() || f.tickCount - s.gained > 60)) {
            java.util.List<net.minecraft.world.phys.Vec3> pts = new java.util.ArrayList<>();
            pts.add(f.position());
            net.minecraft.world.level.pathfinder.Path p = f.getNavigation().createPath(at, 0);
            if (p != null && p.canReach()) for (int i = Math.max(1, p.getNextNodeIndex()); i < p.getNodeCount(); i++) pts.add(net.minecraft.world.phys.Vec3.atBottomCenterOf(p.getNodePos(i)));
            pts.add(net.minecraft.world.phys.Vec3.atBottomCenterOf(at));
            if (Stroll.alongPoints(f, pts)) return true;
        }
        if (Stroll.led(f)) return true;
        if (f.getNavigation().isDone() || f.tickCount % 100 == 0) {
            f.getNavigation().moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.9D);
        }
        return true;
    }

    /** Spots all tried: down where it stands if that is indoors on a floor, else nothing (it stands the night at home). */
    private static boolean lastResort(VillageFolkEntity f, Spot s) {
        BlockPos here = f.blockPosition();
        if (f.level() instanceof ServerLevel level && standable(level, here) && !level.canSeeSky(here) && !taken(f, here)) {
            lie(f, here);
            return true;
        }
        return false;
    }

    /** Down on the floor: the sleeping pose, its "bed" the floor spot (the game lets it lie: canContinue below). */
    static void lie(VillageFolkEntity f, BlockPos at) {
        f.getNavigation().stop();
        Stroll.stop(f);
        LYING.put(f.getUUID(), at.immutable());
        // Its length along the way that is free (not into a wall), as one lies down along a room.
        Direction way = Direction.NORTH;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (standable(f.level(), at.relative(d))) { way = d; break; }
        }
        float yaw = way.toYRot();
        f.setYRot(yaw);
        f.yBodyRot = yaw;
        f.yHeadRot = yaw;
        f.setSleepingPos(at.immutable());
        f.setPose(Pose.SLEEPING);
        f.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        FLOOR_NIGHTS++;
        f.brain("no bed to be had tonight: asleep on the floor indoors");
    }

    /** Up in the morning (or woken): its floor given back. */
    public static void up(AssistantEntity f) {
        LYING.remove(f.getUUID());
    }

    /**
     * The night census (the tests, the books): why this folk is not asleep now, in a few words, the kind first ("walking to
     * its bed: 12 off"); "asleep" or "asleep on a floor" for one that is.
     */
    public static String whyAwake(AssistantEntity a) {
        if (a.isSleeping()) return onAFloor(a) ? "asleep on a floor" : "asleep";
        if (a instanceof VillageFolkEntity f) {
            if (f.stationTask() == AssistantEntity.StationTask.GUARD && f.onShift()) return "on watch";
            if (TownJobs.busy(f)) return "on the town's work: " + TownJobs.doing(f);
            if (f.onShift()) return "at work: " + f.offWorkWhy() + " " + f.brainNoteForBooks();
        }
        String n = a.nightNote();
        if (!n.isEmpty()) return n;
        Job j = a.peekJob();
        if (j != null) return "an errand first: " + j.type();
        return "kept from bed: " + a.brainNoteForBooks();
    }

    /** The kind of a census reason (what comes before its colon). */
    public static String kind(String why) {
        int c = why.indexOf(':');
        return c < 0 ? why : why.substring(0, c);
    }

    /** The game's sleeping check: a folk lying on its floor spot is asleep, bed or none. */
    @SubscribeEvent
    public static void canContinue(CanContinueSleepingEvent e) {
        if (e.mayContinueSleeping() || !(e.getEntity() instanceof VillageFolkEntity f)) return;
        BlockPos at = LYING.get(f.getUUID());
        if (at != null && f.getSleepingPos().map(at::equals).orElse(false)) e.setContinueSleeping(true);
    }

    private static boolean taken(AssistantEntity f, BlockPos p) {
        for (Map.Entry<UUID, BlockPos> e : LYING.entrySet()) {
            if (!e.getKey().equals(f.getUUID()) && e.getValue().distManhattan(p) <= 1) return true;
        }
        long night = nightOf(f.level().getDayTime());
        for (Map.Entry<UUID, Spot> e : SPOTS.entrySet()) {
            if (e.getKey().equals(f.getUUID()) || e.getValue().at == null || e.getValue().night != night) continue;
            if (e.getValue().at.distManhattan(p) <= 1) return true;
        }
        return false;
    }

    /** A floor to stand on: solid under, two clear over, no water, not a doorway. */
    static boolean standable(net.minecraft.world.level.Level level, BlockPos p) {
        BlockState feet = level.getBlockState(p), head = level.getBlockState(p.above());
        if (!feet.getCollisionShape(level, p).isEmpty() || !head.getCollisionShape(level, p.above()).isEmpty()) return false;
        if (!feet.getFluidState().isEmpty() || feet.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return false;
        if (feet.getBlock() instanceof net.minecraft.world.level.block.BedBlock) return false;
        return level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
    }

    private static boolean hearth(BlockState st) {
        return st.is(Blocks.FURNACE) || st.is(Blocks.SMOKER) || st.is(Blocks.BLAST_FURNACE) || st.is(Blocks.CAMPFIRE) || st.is(Blocks.SOUL_CAMPFIRE);
    }

    /**
     * The floor it lies down on: round its house's anchor (else its work's ground in the town, else the heart), with a roof
     * over it, room beside it to lie along, by a hearth if there is one, nearest the middle of the house; none already
     * tried tonight, none another lies on.
     */
    @Nullable
    static BlockPos choose(ServerLevel level, VillageFolkEntity f, Spot s, boolean here) {
        BlockPos base = here ? f.blockPosition() : Homes.homeOf(f);
        if (base == null && f.workZone() != null && f.villageCentre() != null
                && f.workZone().center().distSqr(f.villageCentre()) < 64 * 64) base = f.workZone().center();
        if (base == null) base = f.getHome();
        if (base == null) base = f.villageCentre();
        if (base == null) base = f.blockPosition();
        if (!level.isLoaded(base)) return null;
        java.util.TreeMap<Double, BlockPos> ranked = new java.util.TreeMap<>();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                for (int dy = -DEPTH; dy <= DEPTH; dy++) {
                    q.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    if (!level.isLoaded(q) || level.canSeeSky(q) || !standable(level, q)) continue;
                    BlockPos p = q.immutable();
                    if (s.tried.contains(p) || taken(f, p)) continue;
                    boolean room = false;
                    for (Direction d : Direction.Plane.HORIZONTAL) if (standable(level, p.relative(d))) { room = true; break; }
                    if (!room) continue;
                    double score = dx * dx + dz * dz + dy * dy * 4.0;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos n = p.relative(d);
                        if (level.getBlockState(n).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) score += 40;   // in the way through
                    }
                    if (ranked.size() >= 6 && score >= ranked.lastKey()) continue;
                    // By the fire: a hearth within a couple of steps.
                    fire:
                    for (int hx = -2; hx <= 2; hx++) for (int hz = -2; hz <= 2; hz++) {
                        if (hearth(level.getBlockState(p.offset(hx, 0, hz)))) { score -= 30; break fire; }
                    }
                    ranked.put(score + (dx * 31 + dz) * 1.0E-6, p);
                    if (ranked.size() > 6) ranked.pollLastEntry();
                }
            }
        }
        // The best of them it can walk to (a floor in a room it has no way into is no bed for the night).
        boolean near = f.blockPosition().distSqr(base) < 48 * 48;
        for (BlockPos p : ranked.values()) {
            if (!near) return p;
            net.minecraft.world.level.pathfinder.Path path = f.getNavigation().createPath(p, 0);
            if (path != null && path.canReach()) return p;
            s.tried.add(p);
        }
        return null;
    }
}
