package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [paths] The town's footways: where a folk can walk, and what stands in its way.
 *
 * <p>The ground a folk can walk to from a place ({@link #reach}) is worked out the way a folk walks it: two blocks of room
 * to stand in, something under its feet, a block up at a time (a jump) and no more than three down, through any wooden door
 * or gate (a folk opens them) and up a ladder. It is the measure the town's doors are held to (WayAudit: every door reached
 * from the heart) and it is cheap enough to work out on a command, though never on a tick.
 *
 * <p>A building's ground (its footing, and its whole reach with the eaves) is worked out from its drawing and the way it
 * faces ({@link #footprint}), so the lots can be kept apart (Layout) and a block in a street told from a block in a wall.
 */
public final class Footways {

    private Footways() {}

    /** The furthest a folk drops without thinking twice (AssistantEntity.CAREFUL_DROP). */
    public static final int DROP = 3;

    // ------------------------------------------------------------------ standing and walking

    /** Can a folk be in this block: nothing in it to bump into (or a wooden door or a gate, which it opens), and no lava. */
    public static boolean passable(Level level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (st.getFluidState().is(FluidTags.LAVA)) return false;
        if (st.getBlock() instanceof DoorBlock) return st.is(BlockTags.WOODEN_DOORS) || st.getValue(DoorBlock.OPEN);
        if (st.getBlock() instanceof FenceGateBlock) return true;
        VoxelShape shape = st.getCollisionShape(level, p);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.1875;   // a carpet, a little snow
    }

    /** Is something under this place to stand on (not a fence's or a wall's top, which a folk cannot get onto)? */
    public static boolean floor(Level level, BlockPos below) {
        BlockState st = level.getBlockState(below);
        VoxelShape shape = st.getCollisionShape(level, below);
        if (shape.isEmpty()) return false;
        return shape.max(Direction.Axis.Y) <= 1.0;
    }

    private static boolean wet(Level level, BlockPos p) {
        return level.getFluidState(p).is(FluidTags.WATER);
    }

    private static boolean climbable(Level level, BlockPos p) {
        return level.getBlockState(p).is(BlockTags.CLIMBABLE);
    }

    /** Can a folk stand here, its feet in this block: room for it, and a floor (or water to swim in, or a ladder to hold). */
    public static boolean standable(Level level, BlockPos feet) {
        if (!passable(level, feet) || !passable(level, feet.above())) return false;
        return floor(level, feet.below()) || wet(level, feet) || climbable(level, feet);
    }

    /** The place nearest this one, up to {@code up} above and {@code down} below, where a folk could stand; null if none. */
    @Nullable
    public static BlockPos standNear(Level level, BlockPos p, int up, int down) {
        for (int d = 0; d <= Math.max(up, down); d++) {
            if (d <= down && standable(level, p.below(d))) return p.below(d);
            if (d > 0 && d <= up && standable(level, p.above(d))) return p.above(d);
        }
        return null;
    }

    /**
     * Every place a folk can walk to from {@code from} (feet blocks, as BlockPos.asLong), within {@code radius} of it
     * across and {@code depth} up or down, at most {@code most} places. Walked as a folk walks: to the four sides a
     * block up (with room over its head to jump), on the level, or down as far as {@link #DROP}; up and down a ladder.
     */
    public static LongOpenHashSet reach(Level level, BlockPos from, int radius, int depth, int most) {
        LongOpenHashSet seen = new LongOpenHashSet();
        BlockPos start = standNear(level, from, 3, 4);
        if (start == null) return seen;
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(start.asLong());
        queue.enqueue(start.asLong());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int x0 = start.getX(), z0 = start.getZ(), y0 = start.getY();
        while (!queue.isEmpty() && seen.size() < most) {
            long at = queue.dequeueLong();
            int x = BlockPos.getX(at), y = BlockPos.getY(at), z = BlockPos.getZ(at);
            BlockPos here = new BlockPos(x, y, z);
            // Up and down a ladder.
            if (climbable(level, here) || climbable(level, here.above())) {
                BlockPos up = here.above();
                if (passable(level, up.above()) && standable(level, up)) visit(seen, queue, up);
            }
            if (climbable(level, here.below()) && standable(level, here.below())) visit(seen, queue, here.below());
            boolean jump = passable(level, here.above(2));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = x + d.getStepX(), nz = z + d.getStepZ();
                if (Math.abs(nx - x0) > radius || Math.abs(nz - z0) > radius) continue;
                // A block up, with room to jump.
                if (jump && Math.abs(y + 1 - y0) <= depth) {
                    m.set(nx, y + 1, nz);
                    if (standable(level, m)) visit(seen, queue, m.immutable());
                }
                // Level, or down: the way off this block clear, then a fall to the first floor.
                m.set(nx, y, nz);
                if (!passable(level, m) || !passable(level, m.above())) continue;
                for (int ny = y; ny >= y - DROP && Math.abs(ny - y0) <= depth; ny--) {
                    m.set(nx, ny, nz);
                    if (!passable(level, m)) break;
                    if (floor(level, m.below()) || wet(level, m) || climbable(level, m)) {
                        if (passable(level, m.above())) visit(seen, queue, m.immutable());
                        break;
                    }
                }
            }
        }
        return seen;
    }

    private static void visit(LongOpenHashSet seen, LongArrayFIFOQueue queue, BlockPos p) {
        long k = p.asLong();
        if (seen.add(k)) queue.enqueue(k);
    }

    // ------------------------------------------------------------------ the ground a building stands on

    /** A building's ground, as {minX, maxX, minZ, maxZ} in the world: its footing ({@code eaves} false) or all of it. */
    public static int[] footprint(Ledger.Building b, boolean eaves) {
        return footprint(b.structure(), b.anchor(), b.facing(), eaves);
    }

    /** As above, for a building of this kind with its middle here and its back toward {@code back}. */
    public static int[] footprint(String structure, BlockPos anchor, Direction back, boolean eaves) {
        int[] h;
        try {
            h = Blueprints.has(structure) ? (eaves ? Blueprints.fullHalf(structure) : Blueprints.groundHalf(structure))
                : BuildGoal.footprint(structure);
        } catch (RuntimeException e) {
            h = new int[]{ 4, 4 };
        }
        boolean turned = back.getAxis() == Direction.Axis.X;
        int hx = turned ? h[1] : h[0], hz = turned ? h[0] : h[1];
        return new int[]{ anchor.getX() - hx, anchor.getX() + hx, anchor.getZ() - hz, anchor.getZ() + hz };
    }

    /** How many blocks of open ground lie between two pieces of ground (0: they touch; -1: they overlap). */
    public static int gap(int[] a, int[] b) {
        int gx = Math.max(a[0] - b[1], b[0] - a[1]) - 1;
        int gz = Math.max(a[2] - b[3], b[2] - a[3]) - 1;
        return gx < 0 && gz < 0 ? -1 : Math.max(gx, gz);
    }

    public static boolean inside(int[] r, int x, int z) {
        return x >= r[0] && x <= r[1] && z >= r[2] && z <= r[3];
    }

    /** Is this spot within the ground of any building the town has put up (with its eaves), or one going up? */
    public static boolean builtOn(Villages.Village v, int x, int z) {
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (skip(b.structure())) continue;
            if (inside(footprint(b, true), x, z)) return true;
        }
        for (Map.Entry<String, Villages.Site> s : Villages.sitesOf(v.id()).entrySet()) {
            if (skip(s.getKey())) continue;
            if (inside(footprint(s.getKey(), s.getValue().anchor(), s.getValue().facing(), true), x, z)) return true;
        }
        return false;
    }

    /** The open ground kept between any two buildings: a way round each, and light to their windows. */
    public static final int GAP = 1;

    /**
     * Would a building of this kind, its middle here and its back toward {@code back}, crowd one already standing or going
     * up: its ground (eaves and all) overlapping theirs, or touching it with no way between? A lot of the plan is eleven
     * across and most buildings leave a block or two of it, so two neighbours on lots back to back keep a way between them;
     * the few that fill their lots (a villa, a museum, a block of flats) would stand wall to wall with another such, and go
     * to another lot instead.
     */
    public static boolean crowds(UUID village, String project, int x, int z, Direction back) {
        int[] mine = footprint(project, new BlockPos(x, 0, z), back, true);
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (skip(b.structure())) continue;
            if (gap(mine, footprint(b, true)) < GAP) return true;
        }
        for (Map.Entry<String, Villages.Site> s : Villages.sitesOf(village).entrySet()) {
            if (skip(s.getKey()) || s.getKey().equals(project)) continue;
            if (gap(mine, footprint(s.getKey(), s.getValue().anchor(), s.getValue().facing(), true)) < GAP) return true;
        }
        return false;
    }

    /** Ground a building of the ledger does not hold to itself: the wall round the square, the storehouse in its shed. */
    static boolean skip(String structure) {
        return structure.equals("fortify") || structure.equals("storehouse") || structure.equals("colony");
    }

    // ------------------------------------------------------------------ the plan's ground

    /** Is this spot (in the world) on one of the town plan's streets, avenues or the ring street? */
    public static boolean street(Villages.Village v, int x, int z) {
        return TownPlan.isStreet(x - v.centre().getX(), z - v.centre().getZ());
    }

    /** Is this spot (in the world) on the square? */
    public static boolean square(Villages.Village v, int x, int z) {
        return TownPlan.isSquare(x - v.centre().getX(), z - v.centre().getZ());
    }

    /** The heart's own ground on the square: the founders' camp and the stores, kept as they are. */
    public static final int CAMP = 4;

    /**
     * Is this spot (in the world) on the square's open ground: on the square, but not the camp at its middle, the well's or
     * a monument's place, the board or the courtyard before it? Nothing is set down there but what the square was laid out
     * for: no bed, no chest, no stall.
     */
    public static boolean squareOpen(Villages.Village v, int x, int z) {
        int dx = x - v.centre().getX(), dz = z - v.centre().getZ();
        if (!TownPlan.isSquare(dx, dz)) return false;
        if (Math.max(Math.abs(dx), Math.abs(dz)) <= CAMP) return false;
        for (TownPlan.Lot s : TownPlan.squareSpots()) {
            if (Math.abs(dx - s.x()) <= s.halfAcross() + 1 && Math.abs(dz - s.z()) <= s.halfDeep() + 1) return false;
        }
        int[] court = Villages.courtRect(v.id());
        if (court != null && x >= court[0] - 1 && x <= court[1] + 1 && z >= court[2] - 1 && z <= court[3] + 1) return false;
        BlockPos board = VillageBoards.boardOf(v.id());
        Direction f = VillageBoards.facingOf(v.id());
        if (board != null && f != null) {
            BlockPos mid = board.relative(com.jrpetty.mcassistant.block.VillageBoardBlock.right(f), com.jrpetty.mcassistant.block.VillageBoardBlock.WIDE / 2);
            if (Math.abs(x - mid.getX()) <= com.jrpetty.mcassistant.block.VillageBoardBlock.WIDE / 2 + 2
                && Math.abs(z - mid.getZ()) <= com.jrpetty.mcassistant.block.VillageBoardBlock.WIDE / 2 + 2) return false;
        }
        return !builtOn(v, x, z);
    }

    /** The doors of every building the town has put up. */
    public static List<DoorWays.Door> doors(ServerLevel level, Villages.Village v) {
        List<DoorWays.Door> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (skip(b.structure())) continue;
            out.addAll(DoorWays.doorsOf(level, b));
        }
        return out;
    }
}
