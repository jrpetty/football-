package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [bridges18] The town's one way of taking a way up a block: a stair, facing the climb, in the way's own stone, never a
 * bare block to clamber onto.
 * <ul>
 * <li><b>At a door</b> (DoorWays): once the way in is walkable, each place on it a block above the next is made a stair
 *     of cobblestone (the doorstep's own stone) facing up the way, the doorstep itself where the ground falls away from
 *     the door: a door stands on a single step, or on a short flight.</li>
 * <li><b>On a road</b> (RoadWorks): a gravel or a stone road that climbs a block from one place to the next gets a stair
 *     there across its width, in its stone (stone brick stairs on stone bricks, cobblestone stairs on cobbles and on
 *     gravel), so a hill road is a stepped road and a cart's folk walk it without a jump.</li>
 * </ul>
 * Each out of the stores (the stair, or the block it is cut from); only where there is head room over it and only in
 * the town's own way (the world's earth or the stone the town laid there), never in anybody's building.
 */
public final class StepKit {

    private StepKit() {}

    /** The stair for a way laid in this block: its own stone's stair; cobblestone's for gravel, a path or earth. */
    static Block stairFor(BlockState surface) {
        if (surface.is(Blocks.STONE_BRICKS) || surface.is(Blocks.STONE_BRICK_STAIRS)) return Blocks.STONE_BRICK_STAIRS;
        if (surface.is(Blocks.MOSSY_STONE_BRICKS)) return Blocks.MOSSY_STONE_BRICK_STAIRS;
        if (surface.is(Blocks.MOSSY_COBBLESTONE)) return Blocks.MOSSY_COBBLESTONE_STAIRS;
        if (surface.is(Blocks.POLISHED_ANDESITE)) return Blocks.POLISHED_ANDESITE_STAIRS;
        if (surface.is(Blocks.BRICKS)) return Blocks.BRICK_STAIRS;
        if (surface.is(Blocks.COBBLED_DEEPSLATE)) return Blocks.COBBLED_DEEPSLATE_STAIRS;
        return Blocks.COBBLESTONE_STAIRS;
    }

    /** The block a stair is cut from. */
    static Item cutFrom(Block stair) {
        if (stair == Blocks.STONE_BRICK_STAIRS) return Items.STONE_BRICKS;
        if (stair == Blocks.MOSSY_STONE_BRICK_STAIRS) return Items.MOSSY_STONE_BRICKS;
        if (stair == Blocks.MOSSY_COBBLESTONE_STAIRS) return Items.MOSSY_COBBLESTONE;
        if (stair == Blocks.POLISHED_ANDESITE_STAIRS) return Items.POLISHED_ANDESITE;
        if (stair == Blocks.BRICK_STAIRS) return Items.BRICKS;
        if (stair == Blocks.COBBLED_DEEPSLATE_STAIRS) return Items.COBBLED_DEEPSLATE;
        return Items.COBBLESTONE;
    }

    /** A stair out of the stores: the stair itself, else the block it is cut from. */
    static boolean pay(ServerLevel level, Villages.Village v, Block stair) {
        Item it = stair.asItem(), from = cutFrom(stair);
        return Crafts.take(level, v, s -> s.is(it), 1) || Crafts.take(level, v, s -> s.is(from), 1);
    }

    /** May the way's block here be made a stair: the world's own ground or the town's own stone, or an old stair. */
    private static boolean ours(BlockState st) {
        if (st.hasBlockEntity()) return false;
        return BridgeKit.earth(st) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.STONE_BRICKS) || st.is(Blocks.MOSSY_COBBLESTONE)
            || st.is(Blocks.COBBLED_DEEPSLATE) || st.getBlock() instanceof StairBlock;
    }

    /**
     * Make the block at {@code at} a stair going up toward {@code up}, of {@code stair}, if it may be (its head room clear
     * and the block the way's own). The block it replaces back into the stores. Returns whether it is a stair facing so now.
     */
    static boolean set(ServerLevel level, @Nullable Villages.Village v, BlockPos at, Direction up, Block stair, boolean free) {
        BlockState was = level.getBlockState(at);
        BlockState want = BridgeKit.stair(stair, up, false);
        if (was.getBlock() == stair && was.getValue(StairBlock.FACING) == up && was.getValue(StairBlock.HALF) == Half.BOTTOM) return true;
        if (!ours(was) || !Footways.passable(level, at.above()) || !Footways.passable(level, at.above(2))) return false;
        if (!free && v != null) {
            if (!pay(level, v, stair)) return false;
            Item back = BridgeKit.back(was);
            if (back != Items.AIR) Crafts.giveBack(level, v, back, 1);
        }
        level.setBlock(at, want, Block.UPDATE_ALL);
        return true;
    }

    // ------------------------------------------------------------------ at a door

    /** The height a folk stands at in this column near {@code walk} (its feet), or MIN_VALUE if none near. */
    private static int feet(ServerLevel level, BlockPos col, int walk) {
        BlockPos f = Footways.standNear(level, col.atY(walk), 1, 2);
        return f == null ? Integer.MIN_VALUE : f.getY();
    }

    /**
     * [DoorWays] The way in at a door, walkable, dressed: each place on it a block above the next made a stair facing up
     * the way. Returns the stairs made (or there already).
     */
    static int door(ServerLevel level, Villages.Village v, DoorWays.Door d) {
        BlockPos door = d.pos();
        Direction out = d.out();
        Block stair = houseStair(level, door, out);
        int prev = door.getY(), made = 0;
        List<Integer> feet = new ArrayList<>();
        List<Direction> ups = new ArrayList<>();
        for (int k = 1; k <= DoorWays.MOST_STEPS + 1; k++) {
            BlockPos col = door.relative(out, k);
            if (!level.hasChunk(col.getX() >> 4, col.getZ() >> 4)) break;
            int f = feet(level, col, prev);
            if (f == Integer.MIN_VALUE || Math.abs(f - prev) > 1) break;
            feet.add(f);
            ups.add(null);
            if (f == prev - 1 && k > 1) {
                // Down a step going out: the place before it (the doorstep, or the step above) a stair facing the door.
                BlockPos hi = door.relative(out, k - 1).atY(prev - 1);
                if (set(level, v, hi, out.getOpposite(), stair, false)) made++;
                ups.set(k - 2, out.getOpposite());
            } else if (f == prev + 1) {
                // Up a step going out (a sunk door): this place a stair facing away from the door.
                if (set(level, v, col.atY(f - 1), out, stair, false)) made++;
                ups.set(k - 1, out);
            }
            prev = f;
        }
        if (!feet.isEmpty()) support(level, v, door, out, feet, ups, stair);
        return made;
    }

    /** The stair a door's flight is built of: the house's own stone where it is stone (its wall by the door, else its
     *  footing), cobblestone else. One stone for the whole flight. */
    static Block houseStair(ServerLevel level, BlockPos door, Direction out) {
        for (BlockPos p : new BlockPos[]{ door.relative(out.getClockWise()), door.relative(out.getCounterClockWise()), door.below() }) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.STONE_BRICKS) || st.is(Blocks.MOSSY_STONE_BRICKS) || st.is(Blocks.POLISHED_ANDESITE) || st.is(Blocks.BRICKS)
                || st.is(Blocks.COBBLED_DEEPSLATE) || st.is(Blocks.MOSSY_COBBLESTONE)) return stairFor(st);
        }
        return Blocks.COBBLESTONE_STAIRS;
    }

    /** Earth, nothing, or an earlier flight's own stone (or its old cheek posts): what may be made good in the flight's stone. */
    private static boolean fill(BlockState st) {
        if (st.hasBlockEntity()) return false;
        return st.isAir() || st.canBeReplaced() || st.is(net.minecraft.tags.BlockTags.DIRT) || st.is(Blocks.DIRT_PATH)
            || st.is(Blocks.GRAVEL) || st.is(Blocks.SAND) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.COBBLESTONE_WALL)
            || st.getBlock() instanceof StairBlock;
    }

    /** One block of the flight's stone (or its stair) out of the stores into this place, what was there going back. */
    private static boolean put(ServerLevel level, Villages.Village v, BlockPos at, BlockState want) {
        BlockState was = level.getBlockState(at);
        if (was == want) return true;
        Item exact = want.getBlock().asItem();
        Item base = want.getBlock() instanceof StairBlock ? cutFrom(want.getBlock()) : exact;
        if (!(want.getBlock() instanceof StairBlock && Crafts.take(level, v, s -> s.is(exact), 1)) && !Crafts.take(level, v, s -> s.is(base), 1)) return false;
        Item back = BridgeKit.back(was);
        if (back != Items.AIR) Crafts.giveBack(level, v, back, 1);
        level.setBlock(at, want, Block.UPDATE_ALL);
        return true;
    }

    /** Take a block out of the way into the stores (an old cheek's post). */
    private static void lift(ServerLevel level, Villages.Village v, BlockPos at) {
        BlockState was = level.getBlockState(at);
        if (was.isAir()) return;
        Item back = BridgeKit.back(was);
        if (back != Items.AIR) Crafts.giveBack(level, v, back, 1);
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * A door's flight made into a built stair: under every step above the ground a solid fill of the flight's stone, flush
     * with its sides, down to the ground; and where the ground beside a step is two or more below it, a cheek on each open
     * side, the same both sides: a stair of the same stone level with the step and facing the same way, filled under to the
     * ground, so each side is one even slope from the door down. Old cheek posts (an earlier way of doing it) taken away.
     */
    private static void support(ServerLevel level, Villages.Village v, BlockPos door, Direction out, List<Integer> feet,
                                List<Direction> ups, Block stair) {
        int ground = feet.get(feet.size() - 1) - 1;                      // where the way meets the ground: the ground's top
        BlockState stone = Block.byItem(cutFrom(stair)).defaultBlockState();
        Direction[] sides = { out.getClockWise(), out.getCounterClockWise() };
        for (int i = 0; i < feet.size(); i++) {
            BlockPos col = door.relative(out, i + 1);
            int floor = feet.get(i) - 1;
            for (Direction side : sides) {
                BlockPos post = col.relative(side).atY(floor + 1);
                if (level.getBlockState(post).is(Blocks.COBBLESTONE_WALL)) lift(level, v, post);   // an old cheek's post
            }
            if (floor <= ground) continue;
            // Under the step: down to the ground's height, and on through any hollow under that to something solid.
            for (int y = floor - 1, n = 0; y > ground - 4 && n < 8; y--, n++) {
                BlockPos at = col.atY(y);
                BlockState st = level.getBlockState(at);
                if (y <= ground && !(st.isAir() || st.canBeReplaced())) break;
                if (!fill(st)) {
                    if (y <= ground) break;
                    continue;
                }
                if (!put(level, v, at, stone)) return;
            }
            BlockState top = level.getBlockState(col.atY(floor));
            if (!(top.getBlock() instanceof StairBlock) && fill(top) && !put(level, v, col.atY(floor), stone)) return;
            // The cheeks: only where the drop beside the step is two or more, and on both sides or neither.
            if (feet.get(i) - (ground + 1) < 2) continue;
            Direction up = ups.get(i);
            BlockState cheek = up == null ? stone : BridgeKit.stair(stair, up, false);
            boolean both = true;
            for (Direction side : sides) {
                BlockPos s = col.relative(side);
                if (!fill(level.getBlockState(s.atY(floor))) || !Footways.passable(level, s.atY(floor + 1)) || !Footways.passable(level, s.atY(floor + 2))) both = false;
            }
            if (!both) continue;
            for (Direction side : sides) {
                BlockPos s = col.relative(side);
                for (int y = floor - 1, n = 0; n < 8; y--, n++) {
                    BlockState st = level.getBlockState(s.atY(y));
                    if (y <= ground && !(st.isAir() || st.canBeReplaced())) break;
                    if (!fill(st)) break;
                    if (!put(level, v, s.atY(y), stone)) return;
                }
                if (!put(level, v, s.atY(floor), cheek)) return;
            }
        }
    }

    // ------------------------------------------------------------------ on a road

    /**
     * [RoadWorks] A gravel or stone road's place at line index i, across its width: where the next place along it stands
     * a block higher, a stair in the road's stone facing the climb.
     */
    static void road(ServerLevel level, Villages.Village v, RoadWorks.Road r, int i, boolean alongX) {
        int[] p = r.line().get(i);
        for (int c = -1; c <= 1; c++) {
            int x = alongX ? p[0] : p[0] + c, z = alongX ? p[1] + c : p[1];
            BridgeKit.Column here = BridgeKit.column(level, x, z);
            if (here == null || here.water()) continue;
            for (Direction up : alongX ? new Direction[]{ Direction.EAST, Direction.WEST } : new Direction[]{ Direction.SOUTH, Direction.NORTH }) {
                BridgeKit.Column next = BridgeKit.column(level, x + up.getStepX(), z + up.getStepZ());
                if (next == null || next.water() || next.y() != here.y() + 1) continue;
                BlockPos top = new BlockPos(x, here.y(), z);
                BlockState surface = level.getBlockState(top);
                BlockPos at = top.above();
                BlockState there = level.getBlockState(at);
                if (!(there.isAir() || there.canBeReplaced() || there.getBlock() instanceof StairBlock)) continue;
                if (there.canBeReplaced() && !there.isAir()) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                // In the stone the road is laid in: the place it climbs to (the one under the stair may be the gravel it was).
                BlockState above = level.getBlockState(new BlockPos(x + up.getStepX(), next.y(), z + up.getStepZ()));
                Block stair = stairFor(above.is(Blocks.GRAVEL) || BridgeKit.earth(above) ? surface : above);
                BlockState now = level.getBlockState(at);
                if (now.getBlock() == stair && now.getValue(StairBlock.FACING) == up) break;
                if (now.getBlock() instanceof StairBlock) {
                    Crafts.giveBack(level, v, now.getBlock().asItem(), 1);
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                if (!Footways.passable(level, at.above()) || !pay(level, v, stair)) break;
                level.setBlock(at, BridgeKit.stair(stair, up, false), Block.UPDATE_ALL);
                break;
            }
        }
    }
}
