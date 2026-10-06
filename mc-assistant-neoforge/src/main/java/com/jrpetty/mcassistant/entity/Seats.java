package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sitting down. [townlife]
 *
 * <p>A folk on its break, most days, looks round for somewhere to sit near where it is — a bench, a
 * step, a chair (to the game, every one of them is a stair the right way up with room over it) — walks
 * over and sits down on it, the way the park's benches are sat on (Park): lowered onto the seat, legs
 * out in front, facing out from its back. It passes the time of day with whoever sits or stands by it,
 * and gets up when its break is over, when it is called back to work or to the bell, or after a good
 * while anyway, to stretch its legs. At a gathering (Assemblies), a folk whose place in the crowd has a
 * seat within a step or two takes the seat, and stands when it is over.
 *
 * <p>A seat is one folk's at a time; a stair on a roof, a stair hanging over nothing, or one with no room
 * over it for a head is no seat, and in the rain nobody sits out under the sky.
 */
public final class Seats {

    private Seats() {}

    static final int BREAK = 0, GATHERING = 1;
    /** How far from where it is a folk on its break looks for a seat; how far from its place at a gathering. */
    static final int REACH = 10, AT_GATHERING = 2;
    /** How long it gives the walk to a seat before it gives the seat up. */
    static final int WALK = 400;

    /** A folk sitting down, or on its way to a seat: why, where, and till when at most. */
    static final class Sit {
        final int why;
        final BlockPos seat;
        final Vec3 spot;
        final float yaw;
        final int until, started;
        final String where;
        boolean seated;
        int moveTick = -1000;

        Sit(int why, BlockPos seat, Vec3 spot, float yaw, int until, int started, String where) {
            this.why = why;
            this.seat = seat;
            this.spot = spot;
            this.yaw = yaw;
            this.until = until;
            this.started = started;
            this.where = where;
        }
    }

    private static final Map<UUID, Sit> SITTING = new ConcurrentHashMap<>();
    /** Whose each seat is, by the seat. */
    private static final Map<Long, UUID> CLAIMED = new ConcurrentHashMap<>();
    /** The day each folk last looked for a seat on its break (once a break). */
    private static final Map<UUID, Long> LOOKED_DAY = new ConcurrentHashMap<>();
    /** When each folk last looked for a seat at a gathering. */
    private static final Map<UUID, Integer> LOOKED_AT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SITTING.clear();
        CLAIMED.clear();
        LOOKED_DAY.clear();
        LOOKED_AT.clear();
    }

    // ------------------------------------------------------------------ on its break

    /**
     * Its break (VillageFolkEntity.resting, at each of the brain's looks): a seat near it, if it is a day it
     * sits and there is one free; then on it till it gets up. True while it is about it (its break's
     * walking about waits).
     */
    static boolean onBreak(VillageFolkEntity f, ServerLevel level) {
        return onBreak(f, level, false);
    }

    static boolean onBreak(VillageFolkEntity f, ServerLevel level, boolean always) {
        if (f.isBaby() || f.isSleeping() || f.ownerId() == null || f.isShowcase()) return false;
        UUID id = f.getUUID();
        Sit s = SITTING.get(id);
        if (s != null) {
            if (s.why != BREAK) return false;
            if (f.tickCount > s.until || !stillFree(f, s)) {
                stand(f, s);
                return false;
            }
            return drive(f, level, s);
        }
        if (Park.doing(f) != null) return false;                 // on a park bench of its own (Park)
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        if (!always) {
            Long looked = LOOKED_DAY.put(id, day);
            if (looked != null && looked == day) return false;   // one sit a break: the rest of it is its own
            if (!sitsToday(f, day)) return false;
        }
        BlockPos seat = find(level, f, f.blockPosition(), REACH);
        if (seat == null) return false;
        s = claim(f, level, seat, BREAK, f.tickCount + 500 + f.getRandom().nextInt(700));
        return drive(f, level, s);
    }

    /** Does it sit down on today's break? Two days in three or so: more for the old and the easygoing, fewer for the hardworking. */
    static boolean sitsToday(VillageFolkEntity f, long day) {
        int roll = Math.floorMod((int) (day * 29L) + f.getUUID().hashCode() * 7, 12);
        int chance = 8;
        if (f.isOld()) chance += 2;
        if (f.life().has(Social.Trait.EASYGOING)) chance += 2;
        if (f.life().has(Social.Trait.HARDWORKING)) chance -= 3;
        return roll < chance;
    }

    /** Still free to sit: on its break and not called away; at a gathering, the gathering not over. */
    static boolean stillFree(VillageFolkEntity f, Sit s) {
        if (!f.isAlive() || f.isSleeping() || f.getTarget() != null || f.isPassenger()) return false;
        if (s.why == GATHERING) return Assemblies.attending(f);
        if (Assemblies.attending(f) || TownCalendar.busy(f) || TownJobs.busy(f) || School.teaching(f)) return false;
        // Its break over, it is back to work. (A job queued meanwhile waits for the break's end, as its
        // trade does; one that walks it off somewhere sooner has it up off the seat anyway: tick.)
        return f.onItsBreak();
    }

    // ------------------------------------------------------------------ at a gathering

    /**
     * At its place at a gathering (Assemblies.attend): a seat within a step or two of it, if there is one
     * free, is taken. Looked for now and then, not every tick.
     */
    static void atGathering(VillageFolkEntity f, ServerLevel level, BlockPos place) {
        UUID id = f.getUUID();
        if (SITTING.containsKey(id)) return;
        Integer looked = LOOKED_AT.get(id);
        if (looked != null && f.tickCount >= looked && f.tickCount - looked < 200) return;
        LOOKED_AT.put(id, f.tickCount);
        BlockPos seat = find(level, f, place, AT_GATHERING);
        if (seat == null) return;
        drive(f, level, claim(f, level, seat, GATHERING, f.tickCount + 24000));
    }

    /** Sitting, or on its way to, a seat for a gathering this near its place there (its place is the seat, then). */
    static boolean atGatheringNear(VillageFolkEntity f, BlockPos place, double reach) {
        Sit s = SITTING.get(f.getUUID());
        return s != null && s.why == GATHERING && s.seat.distSqr(place) <= reach * reach;
    }

    // ------------------------------------------------------------------ the seat

    /**
     * The nearest free seat to {@code from}: a stair the right way up, on something solid, with room over
     * it for a sitting folk, within so far across and a step up or down; out of the rain.
     */
    @Nullable
    static BlockPos find(ServerLevel level, VillageFolkEntity f, BlockPos from, int r) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        boolean wet = level.isRaining();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = from.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) continue;
                    BlockState st = level.getBlockState(p);
                    if (!seat(level, p, st)) continue;
                    if (wet && level.canSeeSky(p.above())) continue;
                    if (taken(level, p, f)) continue;
                    double score = p.distSqr(from);
                    if (score < bestScore) { bestScore = score; best = p.immutable(); }
                }
            }
        }
        return best;
    }

    /** Is this a seat: a stair the right way up, on something solid (no roof's eaves), with room over it for a head? */
    static boolean seat(ServerLevel level, BlockPos p, BlockState st) {
        if (!(st.getBlock() instanceof StairBlock) || st.getValue(StairBlock.HALF) != Half.BOTTOM) return false;
        BlockPos below = p.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        for (int h = 1; h <= 2; h++) {
            BlockPos up = p.above(h);
            if (!level.getBlockState(up).getCollisionShape(level, up).isEmpty() || !level.getFluidState(up).isEmpty()) return false;
        }
        // Room for its legs in front.
        BlockPos feet = p.relative(st.getValue(StairBlock.FACING).getOpposite());
        BlockState fs = level.getBlockState(feet);
        return fs.getCollisionShape(level, feet).isEmpty() || fs.getBlock() instanceof StairBlock;
    }

    /** Somebody's seat already: claimed by another folk, or one sat on it (in the park too). */
    static boolean taken(ServerLevel level, BlockPos seat, @Nullable VillageFolkEntity me) {
        UUID who = CLAIMED.get(seat.asLong());
        if (who != null && (me == null || !who.equals(me.getUUID()))) {
            Sit s = SITTING.get(who);
            if (s != null && s.seat.equals(seat)) return true;
            CLAIMED.remove(seat.asLong(), who);
        }
        AABB box = new AABB(seat).inflate(0.1, 0.5, 0.1);
        return !level.getEntitiesOfClass(VillageFolkEntity.class, box, o -> o != me && o.getPose() == Pose.SITTING).isEmpty();
    }

    /** Claimed by a folk sitting (or going to sit) here (Park, choosing a bench). */
    static boolean claimed(BlockPos seat) {
        UUID who = CLAIMED.get(seat.asLong());
        if (who == null) return false;
        Sit s = SITTING.get(who);
        return s != null && s.seat.equals(seat);
    }

    private static Sit claim(VillageFolkEntity f, ServerLevel level, BlockPos seat, int why, int until) {
        BlockState st = level.getBlockState(seat);
        Direction looks = st.getValue(StairBlock.FACING).getOpposite();
        Vec3 spot = new Vec3(seat.getX() + 0.5 + looks.getStepX() * 0.2, seat.getY() + 0.5, seat.getZ() + 0.5 + looks.getStepZ() * 0.2);
        Sit s = new Sit(why, seat, spot, looks.toYRot(), until, f.tickCount, where(level, seat, looks));
        Sit was = SITTING.put(f.getUUID(), s);
        if (was != null) CLAIMED.remove(was.seat.asLong(), f.getUUID());
        CLAIMED.put(seat.asLong(), f.getUUID());
        return s;
    }

    /** What it is sitting on, in words: a bench (a stair with another beside it), a chair indoors, a step outside. */
    private static String where(ServerLevel level, BlockPos seat, Direction looks) {
        Direction side = looks.getClockWise();
        boolean bench = level.getBlockState(seat.relative(side)).getBlock() instanceof StairBlock
            || level.getBlockState(seat.relative(side.getOpposite())).getBlock() instanceof StairBlock;
        if (bench) return "on a bench";
        return level.canSeeSky(seat.above()) ? "on a step" : "in a chair";
    }

    // ------------------------------------------------------------------ sitting

    /** One step: over to the seat, down onto it; once sat, a look round and a word with whoever is by. True while it is about it. */
    private static boolean drive(VillageFolkEntity f, ServerLevel level, Sit s) {
        f.lastLeisureTick = f.tickCount;
        String why = s.why == BREAK ? " on its break" : "";
        if (!s.seated) {
            if (f.position().distanceToSqr(s.spot) <= 1.5 * 1.5) {
                sit(f, s);
                f.hobbyNow = "sitting " + s.where + why;
                return true;
            }
            if (f.tickCount - s.started > WALK) {                  // no way to it: the seat given up
                stand(f, s);
                return false;
            }
            if (f.getNavigation().isDone() || f.tickCount - s.moveTick > 60) {
                f.walkTo(s.seat, 0.8D);
                s.moveTick = f.tickCount;
            }
            f.hobbyNow = "going to sit down" + why;
            return true;
        }
        VillageFolkEntity by = null;
        double nearest = 3.5 * 3.5;
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(3.5),
                o -> o != f && o.isAlive() && !o.isSleeping())) {
            double d = o.distanceToSqr(f);
            if (d < nearest) { nearest = d; by = o; }
        }
        if (s.why == BREAK && by != null) {
            f.getLookControl().setLookAt(by, 20.0F, 20.0F);
            if (f.tickCount - f.lastSmalltalk > 1200 && f.getRandom().nextInt(4) == 0 && Smalltalk.chat(f, by, level)) {
                f.lastSmalltalk = f.tickCount;
                by.lastSmalltalk = by.tickCount;
            }
        }
        f.hobbyNow = "sitting " + s.where + why + (s.why == BREAK && by != null ? ", with " + by.displayNameCap() : "");
        return true;
    }

    private static void sit(VillageFolkEntity f, Sit s) {
        f.getNavigation().stop();
        f.moveTo(s.spot.x, s.spot.y, s.spot.z, s.yaw, 0.0F);
        f.setYHeadRot(s.yaw);
        f.setYBodyRot(s.yaw);
        f.setPose(Pose.SITTING);
        s.seated = true;
    }

    /** Up off the seat, and the seat let go. */
    static void stand(VillageFolkEntity f, @Nullable Sit s) {
        if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
        Sit mine = s != null ? s : SITTING.get(f.getUUID());
        if (mine == null) return;
        SITTING.remove(f.getUUID(), mine);
        CLAIMED.remove(mine.seat.asLong(), f.getUUID());
    }

    /**
     * Every tick (VillageFolkEntity.aiStep): a folk sat down stays sat, facing the way the seat does, till
     * it is time to get up or something moves it; on its way to a seat, the walk goes on between the
     * brain's looks.
     */
    static void tick(VillageFolkEntity f) {
        Sit s = SITTING.get(f.getUUID());
        if (s == null || !(f.level() instanceof ServerLevel level)) return;
        if (!s.seated) {
            if (f.tickCount % 10 != 0) return;
            if (!stillFree(f, s)) stand(f, s);
            else drive(f, level, s);
            return;
        }
        boolean onSeat = f.getPose() == Pose.SITTING && f.position().distanceToSqr(s.spot) < 0.5 && !f.getNavigation().isInProgress();
        boolean free = f.tickCount % 10 != 0 || stillFree(f, s);
        if (!onSeat || !free || f.tickCount > s.until) {
            stand(f, s);
            return;
        }
        f.setYBodyRot(s.yaw);
        if (f.tickCount % 40 == 0) drive(f, level, s);
    }

    /** Is it sat down (by this, not the park) just now? */
    public static boolean seated(VillageFolkEntity f) {
        Sit s = SITTING.get(f.getUUID());
        return s != null && s.seated;
    }

    // ------------------------------------------------------------------ the tests

    /** For the tests: its break's seat looked for now, whatever the day's roll. True if it is going to (or did) sit. */
    public static boolean restForTests(VillageFolkEntity f) {
        return f.level() instanceof ServerLevel level && onBreak(f, level, true);
    }

    /** For the tests: the seat it has (sat on or going to), or null. */
    @Nullable
    public static BlockPos seatForTests(VillageFolkEntity f) {
        Sit s = SITTING.get(f.getUUID());
        return s == null ? null : s.seat;
    }
}
