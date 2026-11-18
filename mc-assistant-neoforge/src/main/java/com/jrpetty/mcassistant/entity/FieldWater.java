package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [build5] Water for a town's fields. A field is laid out the way anybody lays one out: a hole in the middle of each
 * nine-by-nine square, filled from a bucket, wets the eighty squares round it (FarmGoal). But only a farmer that happened
 * to carry a full bucket dug one, and the town's farmers almost never did: they tilled the ground dry (a settler's
 * fallback), and the building audit found a fifth to a third of every town's farmland four or more blocks from water,
 * the crops on it growing at a third of the pace. A town's farmer draws its water from the town's well now, or fills an
 * empty bucket it carries, so every square of the field gets its hole. A hired hand still needs a full bucket of its own.
 */
public final class FieldWater {

    private FieldWater() {}

    /** May this farmer fill a field's water hole without a full bucket in its pack: a town's, with a well, or with an
     *  empty bucket to fill at the water? */
    public static boolean fromTheTown(AssistantEntity farmer) {
        if (!(farmer instanceof VillageFolkEntity f) || !farmer.isSettler() || f.ownerId() == null) return false;
        return Villages.builtStructure(f.ownerId(), "well") != null || farmer.countMatching(s -> s.is(Items.BUCKET)) > 0;
    }

    /**
     * A field's dry farmland watered, on the farmer's rounds (Fields, once a minute): every square of farmland four or
     * more from water gets a hole, in the middle of its nine-by-nine square where that cell lies at its level (the tidy
     * way: one hole a square), else at the dry cell nearest that middle on its own level (a field over a slope steps, and
     * a hole only wets its own step). At most {@code most} holes a round. Never a block a player set down.
     */
    public static int wet(ServerLevel level, VillageFolkEntity f, WorkZone z, int most) {
        if (!fromTheTown(f)) return 0;
        BlockPos c = z.center();
        int r = z.radius(), made = 0;
        for (int dx = -r; dx <= r && made < most; dx++) {
            for (int dz = -r; dz <= r && made < most; dz++) {
                for (int dy = 4; dy >= -4; dy--) {
                    BlockPos p = c.offset(dx, dy, dz);
                    if (!(level.getBlockState(p).getBlock() instanceof FarmBlock)) continue;
                    if (watered(level, p)) break;
                    // The middle of its square (squares of nine from the field's own middle), on this level.
                    int mx = c.getX() + Math.round((dx) / 9.0f) * 9, mz = c.getZ() + Math.round((dz) / 9.0f) * 9;
                    BlockPos hole = new BlockPos(mx, p.getY(), mz);
                    if (Math.abs(mx - p.getX()) > 4 || Math.abs(mz - p.getZ()) > 4 || !holds(level, hole)) hole = nearestHolding(level, p, mx, mz);
                    if (hole != null && pour(level, hole)) made++;
                    break;
                }
            }
        }
        return made;
    }

    /** Within four of water at its own level or one over, as the game wets farmland. */
    static boolean watered(ServerLevel level, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-4, 0, -4), p.offset(4, 1, 4))) {
            if (level.getFluidState(q).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** Will a hole here hold its water: farmland or earth, firm under it and every side, open over it, nobody's? */
    static boolean holds(ServerLevel level, BlockPos at) {
        BlockState s = level.getBlockState(at);
        if (!(s.getBlock() instanceof FarmBlock) && !s.is(Blocks.DIRT) && !s.is(Blocks.GRASS_BLOCK)) return false;
        if (PlayerBlocks.placed(level, at)) return false;
        if (!level.getBlockState(at.below()).isSolid()) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(at.relative(d));
            if (!n.isSolid() && !n.is(Blocks.WATER) && !(n.getBlock() instanceof FarmBlock)) return false;
        }
        BlockState over = level.getBlockState(at.above());
        return over.isAir() || over.canBeReplaced() || over.getBlock() instanceof net.minecraft.world.level.block.CropBlock;
    }

    /** The farmland cell on this one's level, within four of it, nearest the square's middle, that will hold a hole. */
    static BlockPos nearestHolding(ServerLevel level, BlockPos p, int mx, int mz) {
        BlockPos best = null;
        int bestD = Integer.MAX_VALUE;
        for (int ox = -4; ox <= 4; ox++) {
            for (int oz = -4; oz <= 4; oz++) {
                BlockPos q = p.offset(ox, 0, oz);
                int d = Math.abs(q.getX() - mx) + Math.abs(q.getZ() - mz);
                if (d < bestD && holds(level, q)) { best = q; bestD = d; }
            }
        }
        return best;
    }

    /** The crop over it taken up, and water in. */
    static boolean pour(ServerLevel level, BlockPos at) {
        BlockState over = level.getBlockState(at.above());
        if (!over.isAir()) level.destroyBlock(at.above(), true);
        level.setBlockAndUpdate(at, Blocks.WATER.defaultBlockState());
        return true;
    }
}
