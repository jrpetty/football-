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
 * <li>A folk standing still out of sight on firm ground skips the game's movement for the tick (no collisions worked
 *     out for a body that does not move), all but one tick in {@link #SETTLE_EVERY}, so a block taken from under it
 *     still lets it fall.</li>
 * </ul>
 * In sight, nothing changes. On the game tests' server none of it is in force unless a test asks ({@link #liveForTests}):
 * nobody is ever in sight there, and the other features' tests watch their folk walk.
 */
public final class FolkLod {

    /** Further than this from every player, a folk is out of sight (the game draws a mob its size to about 64). */
    public static final double SIGHT = 72.0;
    /** A folk standing still out of sight still moves one tick in this many (staggered by its id). */
    private static final int SETTLE_EVERY = 10;

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
        for (var p : level.players()) {
            if (p.isSpectator()) continue;
            if (p.distanceToSqr(x, y, z) < SIGHT * SIGHT) return false;
        }
        return true;
    }

    /** Is this folk out of every player's sight (and the savings in force)? */
    public static boolean unseen(Entity e) {
        return scaling(e.level()) && unseenAt(e.level(), e.getX(), e.getY(), e.getZ());
    }

    /**
     * May this folk skip the game's movement this tick? Out of sight, on firm ground and dry, not riding or ridden,
     * asked to go nowhere (no input, no walk under way but a travelled one) and not drifting; and not on its tick of
     * the {@link #SETTLE_EVERY}.
     */
    static boolean restsUnseen(VillageFolkEntity f, Vec3 input) {
        if ((f.tickCount + f.getId()) % SETTLE_EVERY == 0) return false;
        if (!f.onGround() || f.isInWaterOrBubble() || f.isInLava() || f.isPassenger() || f.isVehicle()) return false;
        if (input.lengthSqr() > 1.0E-6) return false;
        Vec3 v = f.getDeltaMovement();
        if (v.x * v.x + v.z * v.z > 1.0E-4) return false;
        if (!(f.getNavigation().isDone() || f.getNavigation() instanceof FolkNavigation n && n.travelling())) return false;
        return unseen(f);
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
