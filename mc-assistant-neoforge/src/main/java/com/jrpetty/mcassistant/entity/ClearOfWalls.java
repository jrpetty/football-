package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;

/**
 * [tp49] A folk about to move never starts from a body a hair into a block.
 *
 * <p>The game's own movement takes no notice of a block a body is already in: it stops a body at a wall's face, but one
 * that begins a step a hundredth of a block inside the wall walks on into it as if it were air, and a moment later its
 * head is in the wall, it is hurt by it and steps out of it (StepOut), and walks back in. Folk are set a hair into blocks
 * all the time by calls that place them, not by the game's movement: a led walk's step (Stroll, tested with a hundredth
 * to spare), the glide out of the cage at a stop, down onto a seat (a stair's back is half a block of its spot), up off a
 * floor, out of a wall. In a soak, miners set down out of the cage at the deep stop a hundredth into the gravel beside
 * it walked on into the gravel, every shift.
 *
 * <p>So, each tick a folk is to move (VillageFolkEntity.travel), a body found a little way into a block (at most
 * {@link #MOST} across, or a carpet's height up) is set clear of it first, by the least shift that clears it. Deeper than
 * that it is no hair's breadth but a block set down in it, which StepOut sees to.
 *
 * <p>And a sapling does not grow into a tree while a folk stands where its trunk would go (a woodsman back at its plot
 * stood on the sapling it had planted, and the oak came up through it): it grows on a later try, the folk gone.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class ClearOfWalls {

    private ClearOfWalls() {}

    /** The furthest a body is set aside to clear it (blocks): half its width and a little. */
    static final double MOST = 0.35;
    /** The furthest it is lifted to clear the floor (a carpet's height and a little). */
    static final double UP = 0.07;
    /** A little more than touching, so the shift leaves it clear and not still touching inside the rounding. */
    private static final double MARGIN = 1.0E-5;

    /** How high over a sapling a tree's trunk is looked for room (blocks). */
    static final int TREE_HIGH = 8;

    /** A tree about to grow from a sapling (or a fungus, a mushroom, an azalea): not with a folk standing in its trunk. */
    @SubscribeEvent
    public static void treeGrows(BlockGrowFeatureEvent e) {
        if (e.getLevel().isClientSide()) return;
        BlockPos p = e.getPos();
        AABB trunk = new AABB(p.getX() - 1, p.getY(), p.getZ() - 1, p.getX() + 2, p.getY() + TREE_HIGH, p.getZ() + 2);
        if (!e.getLevel().getEntitiesOfClass(VillageFolkEntity.class, trunk, VillageFolkEntity::isAlive).isEmpty()) e.setCanceled(true);
    }

    /**
     * [tp49] Is a folk standing in this cell (or the {@code high} cells from it up)? If so it is asked to step aside out of
     * it, to open ground beside it, and the block that was to go there waits: a block is never set down inside a folk.
     */
    public static boolean standingIn(net.minecraft.server.level.ServerLevel level, BlockPos p, int high) {
        AABB cell = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + high, p.getZ() + 1);
        java.util.List<VillageFolkEntity> in = level.getEntitiesOfClass(VillageFolkEntity.class, cell, VillageFolkEntity::isAlive);
        for (VillageFolkEntity f : in) stepAside(level, f, cell);
        return !in.isEmpty();
    }

    /** Off to the nearest open ground beside it, out of this box (walked: a step or two). */
    static void stepAside(net.minecraft.server.level.ServerLevel level, VillageFolkEntity f, AABB out) {
        if (f.isPassenger() || f.isSleeping() || !f.getNavigation().isDone() && f.getNavigation().getTargetPos() != null
                && !out.contains(net.minecraft.world.phys.Vec3.atBottomCenterOf(f.getNavigation().getTargetPos()))) return;   // already going
        BlockPos at = f.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
            BlockPos q = at.offset(dx, dy, dz);
            AABB body = f.getDimensions(f.getPose()).makeBoundingBox(q.getX() + 0.5, q.getY(), q.getZ() + 0.5);
            if (body.intersects(out) || !level.noCollision(f, body) || !level.getFluidState(q).isEmpty()) continue;
            if (!level.getBlockState(q.below()).isFaceSturdy(level, q.below(), net.minecraft.core.Direction.UP)) continue;
            double d = dx * dx + dz * dz + dy * dy * 2;
            if (d < bestD) { bestD = d; best = q; }
        }
        if (best != null) f.getNavigation().moveTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, 1.0D);
    }

    /** Bodies set clear so far (the tests, the soak). */
    public static volatile long CLEARED;

    /** Set clear of the block its body is a hair into, if it is; true if it was moved. */
    static boolean clear(VillageFolkEntity f) {
        AABB bb = f.getBoundingBox();
        Iterable<VoxelShape> in = f.level().getBlockCollisions(f, bb);
        boolean any = false;
        double px = Double.MAX_VALUE, nx = Double.MAX_VALUE, pz = Double.MAX_VALUE, nz = Double.MAX_VALUE, up = 0;
        for (VoxelShape s : in) {
            for (AABB a : s.toAabbs()) {
                if (!a.intersects(bb)) continue;
                any = true;
                px = Math.min(px, a.maxX - bb.minX);             // shifts that would clear this box, each way
                nx = Math.min(nx, bb.maxX - a.minX);
                pz = Math.min(pz, a.maxZ - bb.minZ);
                nz = Math.min(nz, bb.maxZ - a.minZ);
                up = Math.max(up, a.maxY - bb.minY);
            }
        }
        if (!any) return false;
        // Every shift that might clear it, the least first: up off a floor a hair, then aside, then aside both ways.
        double[][] tries = new double[9][];
        int n = 0;
        if (up > 0 && up <= UP) tries[n++] = new double[]{ 0, up + MARGIN, 0 };
        double[] xs = { px <= MOST ? px + MARGIN : Double.NaN, nx <= MOST ? -(nx + MARGIN) : Double.NaN };
        double[] zs = { pz <= MOST ? pz + MARGIN : Double.NaN, nz <= MOST ? -(nz + MARGIN) : Double.NaN };
        for (double x : xs) if (!Double.isNaN(x)) tries[n++] = new double[]{ x, 0, 0 };
        for (double z : zs) if (!Double.isNaN(z)) tries[n++] = new double[]{ 0, 0, z };
        for (double x : xs) for (double z : zs) if (!Double.isNaN(x) && !Double.isNaN(z)) tries[n++] = new double[]{ x, 0, z };
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double[] t = tries[i];
            double d = t[0] * t[0] + t[1] * t[1] + t[2] * t[2];
            if (d >= bestD) continue;
            if (!f.level().noCollision(f, bb.move(t[0], t[1], t[2]))) continue;
            best = t;
            bestD = d;
        }
        if (best == null) return false;
        f.setPos(f.getX() + best[0], f.getY() + best[1], f.getZ() + best[2]);
        CLEARED++;
        return true;
    }
}
