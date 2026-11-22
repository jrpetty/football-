package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [biz30] Every business's name at its front. The sign by a building's door (TownLife.addressSign) went up only where the
 * wall either side of the door, at the door's head, was solid with the air before it: a bank with windows by its door, a
 * smithy whose door stands in its open side, the market's open hall, the stable's wide doors, the wheelwright's shed and
 * the smeltery had no name anywhere, and on film read as nobody's building. Now a business with no sign at its front has
 * one put up: on its door's lintel, or the wall a step or two either side of its door at the door's head, or on a post by
 * its doorstep (the market and the open sheds, on the ground before their middle). Its name ("The Smithy") and its
 * address. Out of the stores, put up by a hand at the town's works (free when the town is dressed for a test or a
 * showcase); never on a road, in a doorway or on the way in.
 */
public final class BizFronts {

    private BizFronts() {}

    /** A sign at this building's front already (any sign, board or banner within two of its walls). */
    static boolean signed(ServerLevel level, Ledger.Building b) {
        int[] box = box(b);
        if (box == null) return true;
        for (BlockPos q : BlockPos.betweenClosed(box[0] - 2, box[4], box[2] - 2, box[1] + 2, box[4] + 6, box[3] + 2)) {
            BlockState st = level.getBlockState(q);
            if (st.getBlock() instanceof net.minecraft.world.level.block.SignBlock
                    || st.getBlock() instanceof net.minecraft.world.level.block.CeilingHangingSignBlock
                    || st.getBlock() instanceof net.minecraft.world.level.block.WallHangingSignBlock
                    || st.getBlock() instanceof com.jrpetty.mcassistant.block.ShopSignBlock
                    || st.is(net.minecraft.tags.BlockTags.BANNERS)) return true;
        }
        return false;
    }

    /** The footing's box: x0, x1, z0, z1, and the floor's y (the first cell of air over it). */
    @Nullable
    static int[] box(Ledger.Building b) {
        List<BuildGoal.Placement> plan = BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13);
        int minY = Integer.MAX_VALUE;
        for (BuildGoal.Placement p : plan) if (p.part() != BuildGoal.Part.CLEAR) minY = Math.min(minY, p.pos().getY());
        if (minY == Integer.MAX_VALUE) return null;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.CLEAR || p.pos().getY() != minY) continue;
            x0 = Math.min(x0, p.pos().getX()); x1 = Math.max(x1, p.pos().getX());
            z0 = Math.min(z0, p.pos().getZ()); z1 = Math.max(z1, p.pos().getZ());
        }
        return new int[]{ x0, x1, z0, z1, minY + 1 };
    }

    /**
     * A business's name board put up if it has none at its front. Out of the stores and by a hand at the town's works unless
     * {@code free}. Whether it has one now.
     */
    public static boolean board(ServerLevel level, UUID village, BlockPos heart, Ledger.Building b, @Nullable Villages.Village v, boolean free) {
        if (!BuildingAudit.BUSINESSES.contains(b.structure())) return true;
        if (!free && v == null) return false;
        if (signed(level, b)) return true;
        int[] box = box(b);
        if (box == null) return false;
        String[] where = TownLife.address(village, heart, b);
        String[] lines = { TownLife.title(b.structure()), where != null ? where[0] : "", where != null ? where[1] : "", "" };
        // Each door on the footing's edge, the front's first: its lintel, then the wall beside it at its head.
        List<BlockPos> doors = new ArrayList<>();
        Set<Long> planned = new HashSet<>();
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            planned.add(p.pos().asLong());
            if (p.part() == BuildGoal.Part.DOOR && p.pos().getY() == box[4]) doors.add(p.pos());
        }
        Direction front = b.facing().getOpposite();
        doors.sort(java.util.Comparator.comparingInt((BlockPos d) -> out(d, box) == front ? 0 : 1));
        for (BlockPos door : doors) {
            Direction out = out(door, box);
            if (out == null) continue;
            Direction side = out.getClockWise();
            List<BlockPos> walls = new ArrayList<>();
            walls.add(door.above(2));                                       // the lintel
            for (int k = 1; k <= 3; k++) {
                walls.add(door.above().relative(side, k));
                walls.add(door.above().relative(side.getOpposite(), k));
            }
            for (BlockPos wall : walls) {
                BlockPos spot = wall.relative(out);
                if (!level.getBlockState(spot).isAir() || !level.getBlockState(wall).isFaceSturdy(level, wall, out)) continue;
                if (v != null && !TownBlocks.mayPlace(level, v, spot, TownBlocks.Why.SIGN)) continue;
                if (!pay(level, v, spot, free)) return false;
                level.setBlock(spot, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, out), 3);
                write(level, spot, lines);
                return true;
            }
        }
        // No wall to hang it on: a sign on a post by the way in (or before the middle of its front).
        List<BlockPos> posts = new ArrayList<>();
        for (BlockPos door : doors) {
            Direction out = out(door, box);
            if (out == null) continue;
            posts.add(door.relative(out, 2).relative(out.getClockWise(), 2));
            posts.add(door.relative(out, 2).relative(out.getCounterClockWise(), 2));
        }
        int cx = (box[0] + box[1]) / 2, cz = (box[2] + box[3]) / 2;
        int reach = front.getAxis() == Direction.Axis.X ? (box[1] - box[0]) / 2 : (box[3] - box[2]) / 2;
        BlockPos mid = new BlockPos(cx, box[4], cz).relative(front, reach + 2);
        posts.add(mid.relative(front.getClockWise(), 2));
        posts.add(mid.relative(front.getCounterClockWise(), 2));
        posts.add(mid.relative(front.getClockWise(), 3));
        for (BlockPos at : posts) {
            BlockPos foot = null;
            for (int dy = 1; dy >= -2 && foot == null; dy--) {
                BlockPos c = at.above(dy);
                if (level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir()
                        && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) foot = c;
            }
            if (foot == null || planned.contains(foot.asLong())) continue;
            if (v != null && (!TownBlocks.mayPlace(level, v, foot, TownBlocks.Why.SIGN) || !TownBlocks.mayPlace(level, v, foot.above(), TownBlocks.Why.SIGN))) continue;
            if (!pay(level, v, foot, free)) return false;
            level.setBlock(foot, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
            level.setBlock(foot.above(), Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,
                RotationSegment.convertToSegment(front)), 3);
            write(level, foot.above(), lines);
            return true;
        }
        return false;
    }

    /** The sign paid for and the hand at it, or free. */
    private static boolean pay(ServerLevel level, @Nullable Villages.Village v, BlockPos spot, boolean free) {
        if (free) return true;
        if (!TownJobs.atWork(level, v, "signs", spot, "putting up a name board")) return false;
        return Crafts.sign(level, v);
    }

    private static void write(ServerLevel level, BlockPos at, String[] lines) {
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) TownLife.write(sign, lines);
    }

    /** Which way is out of the building from a door on its footing's edge. */
    @Nullable
    private static Direction out(BlockPos door, int[] box) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = door.relative(d);
            if (n.getX() < box[0] || n.getX() > box[1] || n.getZ() < box[2] || n.getZ() > box[3]) return d;
        }
        return null;
    }
}
