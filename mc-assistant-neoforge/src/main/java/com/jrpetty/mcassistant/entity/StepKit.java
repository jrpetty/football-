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
        int prev = door.getY(), made = 0;
        for (int k = 1; k <= DoorWays.MOST_STEPS + 1; k++) {
            BlockPos col = door.relative(out, k);
            if (!level.hasChunk(col.getX() >> 4, col.getZ() >> 4)) break;
            int f = feet(level, col, prev);
            if (f == Integer.MIN_VALUE || Math.abs(f - prev) > 1) break;
            if (f == prev - 1 && k > 1) {
                // Down a step going out: the place before it (the doorstep, or the step above) a stair facing the door.
                BlockPos hi = door.relative(out, k - 1).atY(prev - 1);
                if (set(level, v, hi, out.getOpposite(), Blocks.COBBLESTONE_STAIRS, false)) made++;
            } else if (f == prev + 1) {
                // Up a step going out (a sunk door): this place a stair facing away from the door.
                if (set(level, v, col.atY(f - 1), out, Blocks.COBBLESTONE_STAIRS, false)) made++;
            }
            prev = f;
        }
        return made;
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
                Block stair = stairFor(surface);
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
