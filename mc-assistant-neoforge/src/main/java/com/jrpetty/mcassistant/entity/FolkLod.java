package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * [scale] Level of detail for the folk of a town that is awake (TownLod has the towns): a folk out of every player's
 * sight does the same work, more cheaply.
 *
 * <ul>
 * <li><b>Out of sight</b> is further than {@link #SIGHT} from every player: the game draws a mob of a folk's size to
 *     about sixty-four blocks, so beyond that nobody sees it, wherever it is tracked.</li>
 * <li>A walk with both ends out of sight is <em>travelled</em> (FolkNavigation): no path is searched and no step is
 *     taken; the folk stands where it set out for as long as the walk would take it, and is then where it was going.
 *     A player coming into sight of it on the way has it walk the rest.</li>
 * <li>A folk standing still on firm ground skips the game's movement for the tick (no collisions worked out for a
 *     body that does not move): all but one tick in {@link #SETTLE_EVERY} out of sight, all but one in
 *     {@link #SETTLE_EVERY_SEEN} in it, so a block taken from under it still lets it fall; one asleep in its bed skips
 *     it always (it is laid there). Out of sight, the shoving between folk is worked out every other tick.</li>
 * </ul>
 * In sight, nothing shows. On the game tests' server none of it is in force unless a test asks ({@link #liveForTests}):
 * nobody is ever in sight there, and the other features' tests watch their folk walk.
 */
public final class FolkLod {

    /** Further than this from every player, a folk is out of sight (the game draws a mob its size to about 64). */
    public static final double SIGHT = 72.0;
    /** A folk standing still out of sight still moves one tick in this many (staggered by its id). */
    private static final int SETTLE_EVERY = 10;
    /** In sight, one tick in this many (a fifth of a second: no fall shows late by more). */
    private static final int SETTLE_EVERY_SEEN = 4;

    private static boolean liveInTests;

    private FolkLod() {}

    /** Tests: the folk's level of detail (and the path budget) in force on the test server too, or not. */
    public static void liveForTests(boolean on) {
        liveInTests = on;
    }

    /** Are the savings for scale in force in this level (everywhere but the game tests' server, unless asked)? */
    public static boolean scaling(Level level) {
        if (level.isClientSide) return false;
        return liveInTests || !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer);
    }

    /** Is this spot further than {@link #SIGHT} from every player in its level? */
    public static boolean unseenAt(Level level, double x, double y, double z) {
        return unseenAt(level, x, y, z, 0);
    }

    /** Is this spot further than {@link #SIGHT} and a margin from every player in its level? */
    static boolean unseenAt(Level level, double x, double y, double z, double margin) {
        double r = SIGHT + margin;
        for (var p : level.players()) {
            if (p.isSpectator()) continue;
            if (p.distanceToSqr(x, y, z) < r * r) return false;
        }
        return true;
    }

    /** Beyond sight by this much is where a travelled walk into sight hands over to walking. */
    static final double EDGE_MARGIN = 8.0;
    /** How far along a walk the edge of sight is looked for at a time. */
    private static final double EDGE_STEP = 4.0;

    /**
     * Where a walk from out of sight into it crosses into sight, a little short: the last place on the straight way
     * from {@code from} to {@code to} still {@link #EDGE_MARGIN} out of sight, on ground a folk can stand on (a few
     * blocks up or down from the straight way), or null if there is none.
     */
    static BlockPos edgeOfSight(Level level, Vec3 from, Vec3 to) {
        Vec3 way = from.subtract(to);
        double length = way.length();
        if (length < EDGE_STEP) return null;
        Vec3 step = way.scale(EDGE_STEP / length);
        for (double d = EDGE_STEP; d < length; d += EDGE_STEP) {
            Vec3 at = to.add(step.scale(d / EDGE_STEP));
            if (!unseenAt(level, at.x, at.y, at.z, EDGE_MARGIN)) continue;
            BlockPos column = BlockPos.containing(at);
            for (int dy = 0; dy <= 4; dy++) {
                if (standable(level, column.above(dy))) return column.above(dy);
                if (dy > 0 && standable(level, column.below(dy))) return column.below(dy);
            }
            return null;
        }
        return null;
    }

    /** Is this folk out of every player's sight (and the savings in force)? */
    public static boolean unseen(Entity e) {
        return scaling(e.level()) && unseenAt(e.level(), e.getX(), e.getY(), e.getZ());
    }

    /**
     * May this folk skip the game's movement this tick? Asleep in its bed (it is laid there and moves nowhere, seen or
     * not); or out of sight, on firm ground and dry, not riding or ridden, asked to go nowhere (no input, no walk under
     * way but a travelled one) and not drifting; and not on its tick of the {@link #SETTLE_EVERY}.
     */
    static boolean rests(VillageFolkEntity f, Vec3 input) {
        if (!scaling(f.level())) return false;
        if (f.isSleeping() && !f.isPassenger()) return true;
        return standsStill(f, input);
    }

    /** Out of sight, the shoving between folk is worked out on every other tick only (nobody sees a crowd settle). */
    static boolean shovesLess(VillageFolkEntity f) {
        return (f.tickCount + f.getId()) % 2 == 0 && unseen(f);
    }

    /**
     * Standing still on firm ground and dry, not riding or ridden, asked to go nowhere (no input, no walk under way but
     * a travelled one) and not drifting: the game's movement would leave it where it is. It is skipped all but one tick
     * in {@link #SETTLE_EVERY} out of sight, and one in {@link #SETTLE_EVERY_SEEN} in sight (a block taken from under it
     * lets it fall a fifth of a second late at most; nothing else of it shows).
     */
    private static boolean standsStill(VillageFolkEntity f, Vec3 input) {
        int beat = (f.tickCount + f.getId()) % SETTLE_EVERY;
        if (beat == 0 || beat % SETTLE_EVERY_SEEN == 0 && !unseen(f)) return false;
        if (!f.onGround() || f.isInWaterOrBubble() || f.isInLava() || f.isPassenger() || f.isVehicle()) return false;
        if (input.lengthSqr() > 1.0E-6) return false;
        Vec3 v = f.getDeltaMovement();
        if (v.x * v.x + v.z * v.z > 1.0E-4) return false;
        return f.getNavigation().isDone() || f.getNavigation() instanceof FolkNavigation n && n.travelling();
    }

    /** A town's stores counted for something are not counted for it again by every idle folk for this long. */
    private static final long COUNT_FOR = 100L;
    private static final java.util.Map<String, long[]> COUNTED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How many of this the town's stores held when one of its folk last counted them, if that was in the last five
     * seconds; null to count again. A town of five hundred with no wheat had every chest of it searched for wheat at
     * every idle folk's beat. Only where the savings are in force (null otherwise).
     */
    static Integer recentCount(Level level, java.util.UUID village, String what) {
        if (!scaling(level)) return null;
        long[] c = COUNTED.get(village + what);
        long now = level.getGameTime();
        return c != null && now >= c[0] && now - c[0] < COUNT_FOR ? (int) c[1] : null;
    }

    /** The town's stores were just counted for this. */
    static void counted(Level level, java.util.UUID village, String what, int n) {
        if (scaling(level)) COUNTED.put(village + what, new long[]{ level.getGameTime(), n });
    }

    /** Is this block a place a folk can stand: room for its body, dry, and something firm under its feet? */
    static boolean standable(Level level, BlockPos p) {
        if (!level.hasChunkAt(p)) return false;
        var at = level.getBlockState(p);
        var head = level.getBlockState(p.above());
        var under = level.getBlockState(p.below());
        return at.getCollisionShape(level, p).isEmpty() && head.getCollisionShape(level, p.above()).isEmpty()
            && at.getFluidState().isEmpty() && !under.getCollisionShape(level, p.below()).isEmpty();
    }

    /** Where a folk would stand to be at this target: the target if it can be stood in, else the nearest place beside
     *  it (a step round or up or down) that can, nearest {@code from}; null if there is none. */
    static BlockPos standAt(Level level, BlockPos target, Vec3 from) {
        if (standable(level, target)) return target;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    BlockPos p = target.offset(dx, dy, dz);
                    if (!standable(level, p)) continue;
                    double d = p.distToCenterSqr(from);
                    if (d < bestD) { bestD = d; best = p; }
                }
            }
        }
        return best;
    }
}
