package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [trade-world] A merchant ship's hull, as blocks on the water: eleven long and five across, a keel below the waterline,
 * a deck at it, a rail of fence round the deck, a mast amidships with a square sail across the hull and its land's
 * colour at the masthead, barrels of cargo on deck and a lantern at the stern. It sails by being drawn a block further
 * along its lane at a time (only the blocks that change are set), and what it leaves behind is put back as it was:
 * water at and below the waterline, air above it. It is only ever drawn over open water and air (Harbours charted the
 * lane), and it waits rather than sail into anything else: ice, a boat, a swimmer, something a player built.
 *
 * <p>The lane is the harbour's (Harbours.Shore): {@code k} blocks out from the shore along it, {@code lat} blocks to its
 * side (the pier is at 0, the ship's middle at {@link #MIDDLE}). {@code pos} is the shoreward end of the hull.
 */
final class ShipHull {

    private ShipHull() {}

    /** The hull's length along the lane, and its middle's place across it. */
    static final int LENGTH = 11, MIDDLE = 5;
    /** Where it lies alongside the pier, and where it is first seen (and last). */
    static final int BERTH = 3, OFFING = 44;
    /** The gangplank's place across the lane: the gap of water between the pier's side and the hull's. */
    static final int PLANK_LAT = 2;

    /** One block of the hull: along it from the bow (0..10), across it (-2..2, -2 the pier side), up from the waterline. */
    record Cell(int a, int b, int y, BlockState state) {}

    /** The hull's blocks, flying this flag. */
    static List<Cell> template(Block flag) {
        List<Cell> out = new ArrayList<>();
        BlockState hull = Blocks.DARK_OAK_PLANKS.defaultBlockState(), deck = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState rail = Blocks.DARK_OAK_FENCE.defaultBlockState();
        // The keel, below the waterline.
        for (int a = 1; a <= 9; a++) for (int b = -1; b <= 1; b++) out.add(new Cell(a, b, -1, hull));
        // The hull's sides and the deck, at the waterline; the bow and the stern drawn in.
        for (int a = 1; a <= 9; a++) for (int b = -2; b <= 2; b++) out.add(new Cell(a, b, 0, Math.abs(b) == 2 ? hull : deck));
        for (int b = -1; b <= 1; b++) {
            out.add(new Cell(0, b, 0, hull));
            out.add(new Cell(10, b, 0, hull));
        }
        // The rail, with a gap on the pier side to step aboard by.
        for (int a = 1; a <= 9; a++) {
            out.add(new Cell(a, 2, 1, rail));
            if (a != 6) out.add(new Cell(a, -2, 1, rail));
        }
        for (int b = -1; b <= 1; b++) {
            out.add(new Cell(0, b, 1, rail));
            out.add(new Cell(10, b, 1, rail));
        }
        // The stern lantern.
        out.add(new Cell(10, 0, 2, Blocks.LANTERN.defaultBlockState()));
        // The cargo on deck.
        BlockState barrel = Blocks.BARREL.defaultBlockState();
        out.add(new Cell(2, -1, 1, barrel));
        out.add(new Cell(2, 1, 1, barrel));
        out.add(new Cell(3, 1, 1, barrel));
        out.add(new Cell(8, 1, 1, barrel));
        // The mast, its yard, the sail across the hull and the land's colour at the top.
        for (int y = 1; y <= 9; y++) out.add(new Cell(5, 0, y, rail));
        for (int b : new int[]{ -2, -1, 1, 2 }) {
            out.add(new Cell(5, b, 8, rail));
            for (int y = 4; y <= 7; y++) out.add(new Cell(5, b, y, Blocks.WHITE_WOOL.defaultBlockState()));
        }
        out.add(new Cell(5, 0, 10, flag.defaultBlockState()));
        return out;
    }

    /** Where in the world a place on the lane is: k out, lat across, dy over the waterline. */
    static BlockPos at(Harbours.Shore s, int k, int lat, int dy) {
        Direction side = s.side();
        return new BlockPos(s.water.getX() + s.out.getStepX() * k + side.getStepX() * lat, s.surface + dy,
            s.water.getZ() + s.out.getStepZ() * k + side.getStepZ() * lat);
    }

    /** The hull's blocks where it lies: its shoreward end at {@code pos}, its bow to the shore (coming in) or the sea. */
    static Map<BlockPos, BlockState> cells(Harbours.Shore s, Block flag, int pos, boolean bowIn) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (Cell c : template(flag)) {
            int k = bowIn ? pos + c.a() : pos + (LENGTH - 1) - c.a();
            out.put(at(s, k, MIDDLE + c.b(), c.y()), c.state());
        }
        return out;
    }

    /** The gangplank, lying across the gap of water from the pier to the gap in the rail. */
    static BlockPos plank(Harbours.Shore s, int pos, boolean bowIn) {
        int k = bowIn ? pos + 6 : pos + (LENGTH - 1) - 6;
        return at(s, k, PLANK_LAT, 0);
    }

    /** Where somebody steps aboard from the pier: on the pier's edge by the gangplank. */
    static BlockPos gangway(Harbours.Shore s, int pos, boolean bowIn) {
        int k = bowIn ? pos + 6 : pos + (LENGTH - 1) - 6;
        return new BlockPos(at(s, k, 1, 0).getX(), s.deck + 1, at(s, k, 1, 0).getZ());
    }

    /** What a place the hull has left goes back to: the sea at and under the waterline, the air above it. */
    static BlockState behind(Harbours.Shore s, BlockPos p) {
        return p.getY() <= s.surface ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    /** May the hull come into this place: open water at and under the waterline, open air above it? */
    static boolean open(ServerLevel level, Harbours.Shore s, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (p.getY() <= s.surface) return st.is(Blocks.WATER) || st.is(Blocks.SEAGRASS) || st.is(Blocks.TALL_SEAGRASS)
            || st.is(Blocks.BUBBLE_COLUMN);
        return st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty() && !st.is(BlockTags.SNOW);
    }

    /** Blocks set as a hand would set them, but nothing that comes away drops into the sea as an item. */
    static final int FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;

    /** What happened to a move. */
    enum Move { DONE, BLOCKED, UNLOADED }

    /** Every chunk under these blocks loaded? */
    static boolean loaded(ServerLevel level, Iterable<BlockPos> ps) {
        for (BlockPos p : ps) if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return false;
        return true;
    }

    /**
     * Draw the hull at its new place, taking it from its old (if it was drawn): only the blocks that change are set, and
     * nothing is set unless every new block's place is open (no ice, no land, nobody standing in it). Returns what came of it.
     */
    static Move move(ServerLevel level, Harbours.Shore s, Block flag, boolean wasDrawn, int fromPos, boolean fromBowIn,
                     int toPos, boolean toBowIn) {
        Map<BlockPos, BlockState> was = wasDrawn ? cells(s, flag, fromPos, fromBowIn) : Map.of();
        Map<BlockPos, BlockState> will = cells(s, flag, toPos, toBowIn);
        List<BlockPos> all = new ArrayList<>(will.keySet());
        all.addAll(was.keySet());
        if (!loaded(level, all)) return Move.UNLOADED;
        for (Map.Entry<BlockPos, BlockState> e : will.entrySet()) {
            BlockPos p = e.getKey();
            if (was.containsKey(p)) continue;
            if (!open(level, s, p)) return Move.BLOCKED;
        }
        if (inTheWay(level, will.keySet(), was.keySet())) return Move.BLOCKED;
        // What it leaves taken up from the masthead down (a lantern before the post it stands on), then what it comes to
        // set from the keel up (the post before its lantern): nothing ever drops off it into the sea.
        List<BlockPos> leave = new ArrayList<>();
        for (BlockPos p : was.keySet()) if (!will.containsKey(p)) leave.add(p);
        leave.sort((x, y) -> Integer.compare(y.getY(), x.getY()));
        for (BlockPos p : leave) level.setBlock(p, behind(s, p), FLAGS);
        List<Map.Entry<BlockPos, BlockState>> come = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : will.entrySet()) {
            if (!level.getBlockState(e.getKey()).is(e.getValue().getBlock())) come.add(e);
        }
        come.sort((x, y) -> Integer.compare(x.getKey().getY(), y.getKey().getY()));
        for (Map.Entry<BlockPos, BlockState> e : come) level.setBlock(e.getKey(), e.getValue(), FLAGS);
        // The rail's fences joined up to each other, as a hand would set them.
        for (Map.Entry<BlockPos, BlockState> e : will.entrySet()) {
            if (!e.getValue().is(BlockTags.FENCES)) continue;
            BlockState now = level.getBlockState(e.getKey());
            BlockState joined = Block.updateFromNeighbourShapes(now, level, e.getKey());
            if (joined != now) level.setBlock(e.getKey(), joined, Block.UPDATE_CLIENTS);
        }
        return Move.DONE;
    }

    /** Somebody (or a boat) standing where the hull would come, and not already aboard it? */
    private static boolean inTheWay(ServerLevel level, Iterable<BlockPos> will, java.util.Set<BlockPos> was) {
        AABB box = null;
        for (BlockPos p : will) {
            if (was.contains(p)) continue;
            AABB b = new AABB(p);
            box = box == null ? b : box.minmax(b);
        }
        if (box == null) return false;
        for (Entity e : level.getEntities((Entity) null, box, x -> !(x instanceof ItemEntity) && !(x instanceof ExperienceOrb))) {
            for (BlockPos p : will) {
                if (was.contains(p)) continue;
                if (e.getBoundingBox().intersects(new AABB(p))) return true;
            }
        }
        return false;
    }

    /** The hull taken off the water altogether (gone over the horizon), the sea put back where it lay. */
    static boolean erase(ServerLevel level, Harbours.Shore s, Block flag, int pos, boolean bowIn) {
        Map<BlockPos, BlockState> was = cells(s, flag, pos, bowIn);
        if (!loaded(level, was.keySet())) return false;
        List<BlockPos> order = new ArrayList<>(was.keySet());
        order.sort((x, y) -> Integer.compare(y.getY(), x.getY()));       // from the masthead down
        for (BlockPos p : order) {
            BlockState now = level.getBlockState(p);
            if (now.is(was.get(p).getBlock())) level.setBlock(p, behind(s, p), FLAGS);
        }
        return true;
    }

    /** The gangplank laid (or taken up). */
    static void gangplank(ServerLevel level, Harbours.Shore s, int pos, boolean bowIn, boolean down) {
        BlockPos p = plank(s, pos, bowIn);
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return;
        BlockState now = level.getBlockState(p);
        if (down && open(level, s, p)) level.setBlock(p, Blocks.SPRUCE_PLANKS.defaultBlockState(), FLAGS);
        else if (!down && now.is(Blocks.SPRUCE_PLANKS)) level.setBlock(p, behind(s, p), FLAGS);
    }

    /** How many of the hull's blocks stand where it lies (the tests: is it really there?). */
    static int standing(ServerLevel level, Harbours.Shore s, Block flag, int pos, boolean bowIn) {
        int n = 0;
        for (Map.Entry<BlockPos, BlockState> e : cells(s, flag, pos, bowIn).entrySet()) {
            if (level.getBlockState(e.getKey()).is(e.getValue().getBlock())) n++;
        }
        return n;
    }
}
