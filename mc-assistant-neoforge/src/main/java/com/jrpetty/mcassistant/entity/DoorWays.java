package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A way in at every door. A building is laid out at one height and the ground in front of its door
 * is whatever the world made, so now and then a door opened onto a bank of earth a block high, or
 * onto a drop two blocks down that nobody could step up out of, and the house stood empty with its
 * folk outside it. On its rounds of the streets (TownWork) the town looks at its buildings' doors one
 * by one, and where the way in is not one a folk can walk, a hand comes and makes it so: earth in
 * the doorway dug out, a doorstep laid level with the door, and from there a step at a time (cut
 * into a bank, or built up out of a hollow) until the way meets the ground. Only the ground the
 * world made is dug, never anything built and nothing under a roof (a porch is the building's own);
 * every block laid comes out of the stores, and what is dug goes into them.
 */
public final class DoorWays {

    private DoorWays() {}

    /** How far out from a door the way is made, at most. */
    public static final int MOST_STEPS = 6;
    /** How deep a hollow is built up under a step, at most. */
    public static final int MOST_FILL = 4;
    /** Buildings looked at in one visit. */
    private static final int LOOK = 6;
    /** Cells mended at one door in one visit. */
    private static final int BUDGET = 8;

    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();

    /** A door's lower half, and the way out of it. */
    public record Door(BlockPos pos, Direction out) {}

    /** One thing to do for a door's way: dig out a cell, or lay a block in one. No place: the way runs
     *  into something built, and is not the town's to make. */
    public record Fix(@Nullable BlockPos pos, boolean dig) {}

    private static final Fix BLOCKED = new Fix(null, true);

    /** One visit (TownWork.tick): the next doors whose way in is not walkable, mended by a hand at them. */
    public static void tick(ServerLevel level, Villages.Village v) {
        List<Ledger.Building> all = Ledger.buildings(v.id());
        if (all.isEmpty()) return;
        int cursor = CURSOR.getOrDefault(v.id(), 0);
        for (int looked = 0; looked < Math.min(LOOK, all.size()); looked++) {
            Ledger.Building b = all.get(Math.floorMod(cursor, all.size()));
            for (Door d : doorsOf(level, b)) {
                if (mend(level, v, d, BUDGET) != 0) {      // work done here, or a hand on its way: back next visit
                    CURSOR.put(v.id(), cursor);
                    return;
                }
            }
            cursor++;
        }
        CURSOR.put(v.id(), cursor);
    }

    /**
     * Mend the way in at one door, up to so many cells: 1 if something was done, 0 if the way is
     * walkable already (or cannot be made so without breaking what somebody built), -1 if it waits
     * for a hand to come to it.
     */
    public static int mend(ServerLevel level, Villages.Village v, Door d, int budget) {
        int done = 0;
        for (int i = 0; i < budget; i++) {
            Fix fix = next(level, d);
            if (fix == null || fix.pos() == null) return done > 0 ? 1 : 0;
            if (!TownJobs.atWork(level, v, "streets", fix.pos(), "clearing the way to a door")) return done > 0 ? 1 : -1;
            if (fix.dig()) {
                BlockState there = level.getBlockState(fix.pos());
                for (ItemStack drop : Block.getDrops(there, level, fix.pos(), null)) TownWork.give(level, v, drop);
                level.destroyBlock(fix.pos(), false);
            } else {
                BlockState lay = takeFill(level, v, fix.pos().getY() == d.pos().getY() - 1);
                if (lay == null) return done > 0 ? 1 : 0;         // nothing in the stores to lay: another day
                level.setBlockAndUpdate(fix.pos(), lay);
            }
            done++;
        }
        return 1;
    }

    /** A block out of the stores to lay under a step: cobblestone for the doorstep, earth or stone below. */
    @Nullable
    private static BlockState takeFill(ServerLevel level, Villages.Village v, boolean doorstep) {
        if (doorstep && TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return Blocks.COBBLESTONE.defaultBlockState();
        if (TownWork.take(level, v, s -> s.is(Items.DIRT), 1)) return Blocks.DIRT.defaultBlockState();
        if (TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return Blocks.COBBLESTONE.defaultBlockState();
        if (TownWork.take(level, v, s -> s.is(Items.COBBLED_DEEPSLATE), 1)) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        return null;
    }

    /** Every door of a building, each with its side away from the middle of the building. */
    public static List<Door> doorsOf(Level level, Ledger.Building b) {
        List<Door> out = new ArrayList<>();
        int half;
        try {
            int[] h = Blueprints.has(b.structure()) ? Blueprints.fullHalf(b.structure()) : BuildGoal.footprint(b.structure());
            half = Math.max(h[0], h[1]) + 1;
        } catch (RuntimeException e) {
            half = 6;
        }
        BlockPos a = b.anchor();
        if (!level.hasChunk(a.getX() >> 4, a.getZ() >> 4)) return out;
        for (BlockPos p : BlockPos.betweenClosed(a.offset(-half, -2, -half), a.offset(half, 14, half))) {
            BlockState st = level.getBlockState(p);
            if (!(st.getBlock() instanceof DoorBlock) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
            Direction f = st.getValue(DoorBlock.FACING);
            Direction side = flat(p.relative(f), a) >= flat(p.relative(f.getOpposite()), a) ? f : f.getOpposite();
            out.add(new Door(p.immutable(), side));
        }
        return out;
    }

    private static double flat(BlockPos p, BlockPos a) {
        double dx = p.getX() - a.getX(), dz = p.getZ() - a.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * The first thing to do to make the way in at this door walkable: null when it is walkable, BLOCKED
     * when it runs into something built. Walked out from the door: the doorstep level with the door, its
     * floor under it and two blocks of room over it; then a step at a time toward the ground's own
     * height, never more than one up or down, until it meets the ground.
     */
    @Nullable
    public static Fix next(Level level, Door d) {
        BlockPos door = d.pos();
        Direction out = d.out();
        int walk = door.getY();
        BlockPos prev = door;
        for (int k = 1; k <= MOST_STEPS; k++) {
            BlockPos col = door.relative(out, k);
            if (!level.hasChunk(col.getX() >> 4, col.getZ() >> 4)) return null;
            int want;
            if (k == 1) {
                want = walk;
            } else {
                int ground = standNear(level, col, walk);
                // Met: the ground here is a step from the last one at most, with room to stand on it.
                if (Math.abs(ground - walk) <= 1 && (ground <= walk || passable(level, prev.atY(walk + 2)))) return null;
                want = ground > walk ? walk + 1 : walk - 1;
            }
            BlockPos feet = col.atY(want);
            boolean roofed = roofed(level, col, want);
            // Room to rise onto it from the last step.
            if (want > walk) {
                Fix f = clear(level, prev.atY(walk + 2), roofed(level, prev, walk));
                if (f != null) return f;
            }
            // Room to stand on it: two blocks clear, top down.
            for (BlockPos cell : new BlockPos[] { feet.above(), feet }) {
                Fix f = clear(level, cell, roofed);
                if (f != null) return f;
            }
            // Something under it to stand on: a hollow built up from its bottom.
            if (!solid(level, feet.below())) {
                if (roofed) return BLOCKED;
                int bottom = feet.getY() - 1;
                while (bottom > feet.getY() - 1 - MOST_FILL && !solid(level, feet.atY(bottom - 1))) bottom--;
                if (!solid(level, feet.atY(bottom - 1))) return BLOCKED;      // too deep a hole to build up
                BlockState there = level.getBlockState(feet.atY(bottom));
                if (!there.canBeReplaced() || there.getFluidState().is(FluidTags.LAVA)) return BLOCKED;
                return new Fix(feet.atY(bottom), false);
            }
            walk = want;
            prev = col;
        }
        return null;
    }

    /**
     * The height a folk would stand at in this column, near this one: the highest place three up to six
     * down with something under it and two blocks of room over it. Well above when the column is solid
     * that high (a bank to cut steps into), well below when there is nothing to stand on (a drop).
     */
    private static int standNear(Level level, BlockPos col, int walk) {
        for (int y = walk + 3; y >= walk - 6; y--) {
            if (solid(level, col.atY(y - 1)) && room(level, col.atY(y))) return y;
        }
        return solid(level, col.atY(walk)) ? walk + 10 : walk - 10;
    }

    /** A roof over this spot (a porch's, an arcade's, a room's): something built within eight over it. */
    private static boolean roofed(Level level, BlockPos col, int walk) {
        for (int y = walk + 2; y <= walk + 9; y++) {
            BlockPos p = col.atY(y);
            BlockState st = level.getBlockState(p);
            if (!st.getCollisionShape(level, p).isEmpty() && !diggable(level, p, st)) return true;
        }
        return false;
    }

    /** What is in this cell that must come out for a folk to walk through it: null when it is clear;
     *  BLOCKED when it is something built, or anything under a roof. */
    @Nullable
    private static Fix clear(Level level, BlockPos cell, boolean roofed) {
        if (passable(level, cell)) return null;
        if (!roofed && diggable(level, cell, level.getBlockState(cell))) return new Fix(cell, true);
        return BLOCKED;
    }

    /** Ground the world made, a tree's leaves or trunk, snow, a worn path: what the town may dig out of a
     *  doorway. Never a field, nor the cobblestone somebody laid. */
    static boolean diggable(Level level, BlockPos pos, BlockState st) {
        if (st.hasBlockEntity() || st.is(Blocks.FARMLAND)) return false;
        if (st.is(Blocks.DIRT_PATH)) return true;
        if (st.is(BlockTags.LEAVES)) return true;
        if (st.is(BlockTags.LOGS)) return BuildGoal.isTreeLog(level, pos);
        if (st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.SNOW)) return true;
        return MineStairs.ground(st) && !st.is(Blocks.COBBLESTONE) && !st.is(Blocks.COBBLED_DEEPSLATE)
            && !st.is(Blocks.MOSSY_COBBLESTONE);
    }

    private static boolean passable(Level level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.getCollisionShape(level, p).isEmpty() && !st.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean room(Level level, BlockPos feet) {
        return passable(level, feet) && passable(level, feet.above());
    }

    private static boolean solid(Level level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** Is the way in at this door one a folk can walk? */
    public static boolean walkable(Level level, Door d) {
        return next(level, d) == null;
    }

    public static void resetForTests() {
        CURSOR.clear();
    }
}
