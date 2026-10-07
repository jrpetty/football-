package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [civic] The great works drawn to the land they stand on (BigWorks builds them). Not blueprints: a bridge is as
 * long as its river is wide, an aqueduct as tall as the ground under it is high, a wall follows the ground it
 * rings. Each is worked out from the world as it is when the town puts it to the vote, as a list of pieces in the
 * order the town will lay them (the piers before the deck they carry, the deck before its parapets), and the
 * pieces are what the stores pay for, one at a time, as hands set them.
 * <ul>
 * <li><b>A stone bridge</b> where a river cuts the town off from its fields or a neighbour: the deck five wide at
 *     a height boats pass under, a parapet each side, piers every five blocks down to the river bed with an arch's
 *     shoulders either side of them, a ramp of steps down to each bank and a lantern at each end.</li>
 * <li><b>An aqueduct</b> from the nearest water toward the fountain or the square: a stone channel of running
 *     water carried on piers and arches high enough to walk under, spilling at its end into a stone-rimmed
 *     cistern sunk in the ground.</li>
 * <li><b>A canal</b>, where the ground is low enough to dig one: a stone-lined channel cut from the water toward
 *     the fields at the water's own level.</li>
 * <li><b>The town wall</b>, a side at a time, out past the last street: two high with merlons, a tower at each
 *     corner, and a gateway left where a road or a path goes through.</li>
 * <li><b>A harbour</b>: a stone quay along the shore and a pier out into deep water, with bollards and lanterns.</li>
 * <li><b>A great road</b> toward the nearest neighbour: three wide, paved, with lamp posts along it.</li>
 * </ul>
 * Nothing anybody built is ever taken down for a work: a piece whose place is taken by a house, a field or a
 * fence is passed over and left out, and only open ground (earth, sand, stone, grass) is cut for a road or a canal.
 */
final class WorksPlans {

    private WorksPlans() {}

    // ------------------------------------------------------------------ pieces

    /** What a piece is, made of the work's stone (BLOCK to WALL) or of something else the stores hold. */
    enum Part {
        BLOCK(2), SLAB(1), SLAB_TOP(1), STAIRS(3), STAIRS_TOP(3), WALL(2),
        LANTERN(0), LANTERN_HUNG(0), WATER(0), FENCE(0), PLANKS(0), DIG(0);

        /** What it costs in halves of a block of the work's stone (a slab is half a block; stairs a block and a half). */
        final int halves;

        Part(int halves) { this.halves = halves; }

        boolean stone() { return halves > 0; }
    }

    /** One piece: where, what, which way it faces, and whether it may be laid in place of open ground. */
    record Piece(BlockPos pos, Part part, Direction facing, boolean ground) {}

    /** The stone a work is built of, and what in the stores pays for a block of it. */
    enum Family {
        STONE_BRICKS("stone bricks", Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_WALL, Items.STONE_BRICKS),
        MOSSY("mossy stone bricks", Blocks.MOSSY_STONE_BRICKS, Blocks.MOSSY_STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_SLAB,
            Blocks.MOSSY_STONE_BRICK_WALL, Items.MOSSY_STONE_BRICKS),
        ANDESITE("andesite", Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE_STAIRS, Blocks.POLISHED_ANDESITE_SLAB, Blocks.ANDESITE_WALL,
            Items.POLISHED_ANDESITE),
        SANDSTONE("sandstone", Blocks.SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB, Blocks.SANDSTONE_WALL, Items.SANDSTONE),
        BRICKS("brick", Blocks.BRICKS, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB, Blocks.BRICK_WALL, Items.BRICKS),
        DEEPSLATE("deepslate", Blocks.COBBLED_DEEPSLATE, Blocks.COBBLED_DEEPSLATE_STAIRS, Blocks.COBBLED_DEEPSLATE_SLAB,
            Blocks.COBBLED_DEEPSLATE_WALL, Items.COBBLED_DEEPSLATE),
        COBBLESTONE("cobblestone", Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_SLAB, Blocks.COBBLESTONE_WALL, Items.COBBLESTONE);

        final String words;
        final Block block, stairs, slab, wall;
        final Item pay;

        Family(String words, Block block, Block stairs, Block slab, Block wall, Item pay) {
            this.words = words;
            this.block = block;
            this.stairs = stairs;
            this.slab = slab;
            this.wall = wall;
            this.pay = pay;
        }

        /** What in the stores pays for a block of it: the block itself (andesite polishes four for four). */
        Predicate<ItemStack> payment() {
            return this == ANDESITE ? s -> s.is(Items.POLISHED_ANDESITE) || s.is(Items.ANDESITE) : s -> s.is(pay);
        }

        @Nullable
        static Family named(String s) {
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * A work drawn to the land: its pieces in the order they go up; the ribbon across its end (the blocks it is
     * strung over, and the way it runs); where the town gathers to open it and which way the crowd stands from
     * there; where its hands stand to work it; and what it is, in words.
     */
    record Plan(BigWorks.Work kind, List<Piece> pieces, List<BlockPos> ribbon, Direction.Axis ribbonAxis,
                BlockPos focus, Direction audience, List<BlockPos> stands, String where, String brings, BlockPos site) {}

    // ------------------------------------------------------------------ the land

    /** The first free block over a column (water counts as ground; leaves do not). */
    static int walk(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    static boolean loaded(ServerLevel level, int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    /** Is the top of this column water? */
    static boolean water(ServerLevel level, int x, int z) {
        BlockPos top = new BlockPos(x, walk(level, x, z) - 1, z);
        return level.getFluidState(top).is(FluidTags.WATER);
    }

    /** The y of the solid top under the water here (the river bed), at most twenty down. */
    static int bed(ServerLevel level, int x, int z, int surface) {
        // Never below the bottom of the world: water lying on the world's floor has that floor for its bed. (The
        // void under it reads as air, and a pier was once drawn twenty blocks down into it, out of the world, where
        // nobody could ever set its first stone, and the whole work waited on it for ever.)
        int floor = Math.max(surface - 20, level.getMinBuildHeight() - 1);
        for (int y = surface; y > floor; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (level.getFluidState(p).isEmpty() && !level.getBlockState(p).isAir()) return y;
        }
        return floor;
    }

    /** Open ground, the kind a road is laid over or a canal cut through: earth, sand, gravel, plain stone, snow. */
    static boolean openGround(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(Blocks.DIRT_PATH) || st.is(BlockTags.SAND)
            || st.is(Blocks.GRAVEL) || st.is(Blocks.STONE) || st.is(Blocks.ANDESITE) || st.is(Blocks.DIORITE) || st.is(Blocks.GRANITE)
            || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.CLAY) || st.is(Blocks.MUD) || st.is(Blocks.TUFF) || st.is(Blocks.SANDSTONE)
            || st.is(Blocks.RED_SANDSTONE) || st.is(Blocks.TERRACOTTA) || st.is(Blocks.CALCITE) || st.is(Blocks.DEEPSLATE);
    }

    /** Room for a piece here: air, water, grass and flowers, snow, leaves. */
    static boolean room(BlockState st) {
        return st.isAir() || st.canBeReplaced() || st.is(BlockTags.LEAVES);
    }

    /** Does something stand in this column at this height that somebody built (or that is not open air or water)? */
    static boolean blocked(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return !room(st);
    }

    /** A column's offset point: {@code i} out along {@code d} from (x, z), {@code off} across to its right. */
    static BlockPos at(int x, int z, Direction d, int i, int off, int y) {
        Direction p = d.getClockWise();
        return new BlockPos(x + d.getStepX() * i + p.getStepX() * off, y, z + d.getStepZ() * i + p.getStepZ() * off);
    }

    // ------------------------------------------------------------------ the bridge

    /** A river crossing found: from (x, z) out along d, the water from i0 to i1, its surface at y, and why it matters. */
    record Crossing(int x, int z, Direction d, int i0, int i1, int surface, int rank, String where) {}

    /** The sides to look along, the most wanted first: toward the fields, toward a neighbour, then the rest. */
    static List<Direction> sides(UUID village, BlockPos centre) {
        List<Direction> out = new ArrayList<>();
        int fields = Villages.fieldsSide(village);
        if (fields >= 0) out.add(side(fields));
        for (Villages.Village n : Diplomacy.neighboursOf(village)) {
            Direction d = toward(centre, n.centre());
            if (!out.contains(d)) out.add(d);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) if (!out.contains(d)) out.add(d);
        return out;
    }

    static Direction side(int townPlanSide) {
        return switch (townPlanSide) {
            case TownPlan.EAST -> Direction.EAST;
            case TownPlan.SOUTH -> Direction.SOUTH;
            case TownPlan.WEST -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    /** The cardinal way from one place to another. */
    static Direction toward(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** The neighbour that lies this way from the town, if any (within a quarter turn either side). */
    @Nullable
    static Villages.Village neighbourThatWay(UUID village, BlockPos centre, Direction d) {
        for (Villages.Village n : Diplomacy.neighboursOf(village)) if (toward(centre, n.centre()) == d) return n;
        return null;
    }

    /**
     * The river that cuts the town off, if there is one: looking out from the heart along each side, the first
     * stretch of water three to twenty-four across with dry banks either side that runs on past where it is crossed
     * (a river, not a pond to walk round), not already bridged. The fields' side ranks first, a neighbour's next,
     * and any other side only for a river within the town's own reach.
     */
    @Nullable
    static Crossing findRiver(ServerLevel level, UUID village, BlockPos centre) {
        int reach = Math.min(130, Villages.townReach(village) + 40);
        Crossing best = null;
        int fields = Villages.fieldsSide(village);
        for (Direction d : sides(village, centre)) {
            int x = centre.getX(), z = centre.getZ();
            int i0 = -1, i1 = -1;
            for (int i = 6; i <= reach; i++) {
                BlockPos c = at(x, z, d, i, 0, 0);
                if (!loaded(level, c.getX(), c.getZ())) break;
                boolean wet = water(level, c.getX(), c.getZ());
                if (wet && i0 < 0) i0 = i;
                if (!wet && i0 >= 0) { i1 = i - 1; break; }
            }
            if (i0 < 7 || i1 < i0) continue;
            int width = i1 - i0 + 1;
            if (width < 3 || width > 24) continue;
            BlockPos first = at(x, z, d, i0, 0, 0);
            int surface = walk(level, first.getX(), first.getZ()) - 1;
            // Runs on past the crossing both ways: a river, not a pond.
            int mid = (i0 + i1) / 2;
            boolean runs = true;
            for (int off : new int[]{ -6, 6 }) {
                BlockPos s = at(x, z, d, mid, off, 0);
                if (!loaded(level, s.getX(), s.getZ()) || !water(level, s.getX(), s.getZ())) runs = false;
            }
            if (!runs) continue;
            // Already bridged (a road's plank bridge, or one of ours): something solid over the middle.
            BlockPos over = at(x, z, d, mid, 0, surface + 1);
            boolean bridged = false;
            for (int dy = 0; dy < 4; dy++) if (!room(level.getBlockState(over.above(dy)))) bridged = true;
            if (bridged) continue;
            Villages.Village n = neighbourThatWay(village, centre, d);
            int rank;
            String where;
            if (fields >= 0 && side(fields) == d) {
                rank = 3;
                where = "over the river to the fields";
            } else if (n != null) {
                rank = 2;
                where = "over the river on the way to " + Villages.name(n.id());
            } else if (i0 <= Villages.townReach(village)) {
                rank = 1;
                where = "over the river at the town's " + d.getName() + " edge";
            } else {
                continue;
            }
            Crossing c = new Crossing(x, z, d, i0, i1, surface, rank, where);
            if (best == null || c.rank() > best.rank() || c.rank() == best.rank() && c.i0() < best.i0()) best = c;
        }
        return best;
    }

    /** The stone bridge over a crossing: see the class's note. */
    @Nullable
    static Plan bridge(ServerLevel level, UUID village, BlockPos centre) {
        Crossing c = findRiver(level, village, centre);
        return c == null ? null : bridge(level, c);
    }

    static Plan bridge(ServerLevel level, Crossing c) {
        int x = c.x(), z = c.z();
        Direction d = c.d();
        int a = c.i0() - 1, b = c.i1() + 1;
        int la = walkAt(level, x, z, d, a, 0), lb = walkAt(level, x, z, d, b, 0);
        int deck = Math.max(c.surface() + 2, Math.max(la, lb) - 1);
        List<Piece> pieces = new ArrayList<>();
        int span = c.i1() - c.i0() + 1;
        // The piers first, from the river bed up: every five blocks across a river wider than six.
        List<Integer> piers = new ArrayList<>();
        if (span > 6) {
            for (int i = c.i0() + 3; i <= c.i1() - 3; i += 5) piers.add(i);
        }
        for (int i : piers) {
            for (int off = -2; off <= 2; off++) {
                BlockPos col = at(x, z, d, i, off, 0);
                int bedTop = bed(level, col.getX(), col.getZ(), c.surface());
                for (int y = bedTop + 1; y < deck; y++) pieces.add(new Piece(at(x, z, d, i, off, y), Part.BLOCK, d, false));
            }
        }
        // The abutments: the banks built up under the deck's ends.
        for (int i : new int[]{ a, b }) {
            for (int off = -2; off <= 2; off++) {
                int l = walkAt(level, x, z, d, i, off);
                for (int y = l; y < deck; y++) pieces.add(new Piece(at(x, z, d, i, off, y), Part.BLOCK, d, false));
            }
        }
        // The deck, from the town's bank out.
        for (int i = a; i <= b; i++) {
            for (int off = -2; off <= 2; off++) pieces.add(new Piece(at(x, z, d, i, off, deck), Part.BLOCK, d, false));
        }
        // The arches' shoulders under the deck, against each pier and each bank, where there is air over the water.
        if (deck - 1 > c.surface()) {
            List<int[]> shoulders = new ArrayList<>();
            shoulders.add(new int[]{ c.i0(), -1 });
            shoulders.add(new int[]{ c.i1(), 1 });
            for (int i : piers) {
                shoulders.add(new int[]{ i - 1, 1 });
                shoulders.add(new int[]{ i + 1, -1 });
            }
            for (int[] s : shoulders) {
                if (piers.contains(s[0]) || s[0] < c.i0() || s[0] > c.i1()) continue;
                Direction face = s[1] > 0 ? d : d.getOpposite();
                for (int off = -2; off <= 2; off++) pieces.add(new Piece(at(x, z, d, s[0], off, deck - 1), Part.STAIRS_TOP, face, false));
            }
        }
        // The parapets.
        for (int i = a; i <= b; i++) {
            for (int off : new int[]{ -2, 2 }) pieces.add(new Piece(at(x, z, d, i, off, deck + 1), Part.WALL, d, false));
        }
        // A ramp of steps down to each bank, three wide, where the deck stands over the ground.
        ramp(level, pieces, x, z, d, a, -1, deck);
        ramp(level, pieces, x, z, d, b, 1, deck);
        // A lantern on the parapet at each end, and in the middle of a long one.
        for (int off : new int[]{ -2, 2 }) {
            pieces.add(new Piece(at(x, z, d, a, off, deck + 2), Part.LANTERN, d, false));
            pieces.add(new Piece(at(x, z, d, b, off, deck + 2), Part.LANTERN, d, false));
            if (b - a >= 12) pieces.add(new Piece(at(x, z, d, (a + b) / 2, off, deck + 2), Part.LANTERN, d, false));
        }
        List<BlockPos> ribbon = new ArrayList<>();
        for (int off = -1; off <= 1; off++) ribbon.add(at(x, z, d, a, off, deck + 1));
        Direction.Axis axis = d.getClockWise().getAxis();
        int back = a - 3;
        BlockPos focus = at(x, z, d, a - 1, 0, Math.max(walkAt(level, x, z, d, a - 1, 0), deck + 1));
        List<BlockPos> stands = new ArrayList<>();
        stands.add(at(x, z, d, back, 0, walkAt(level, x, z, d, back, 0)));
        stands.add(at(x, z, d, back, -2, walkAt(level, x, z, d, back, -2)));
        stands.add(at(x, z, d, back, 2, walkAt(level, x, z, d, back, 2)));
        String brings = c.rank() == 3 ? "the fields across the river without the long way round, and dry feet at harvest"
            : c.rank() == 2 ? "a dry crossing on the road to our neighbours, for trade and visits"
            : "a way across the river for everybody, and room for the town to grow beyond it";
        return new Plan(BigWorks.Work.BRIDGE, pieces, ribbon, axis, focus, d.getOpposite(), stands, c.where(), brings,
            at(x, z, d, (a + b) / 2, 0, deck));
    }

    /** Steps from the deck's end down to the ground, outward from the end at {@code from} (side -1: back toward the town). */
    private static void ramp(ServerLevel level, List<Piece> pieces, int x, int z, Direction d, int from, int side, int deck) {
        Direction up = side < 0 ? d : d.getOpposite();           // the steps climb toward the bridge
        for (int off = -1; off <= 1; off++) {
            int cur = deck;
            for (int j = 1; j <= 8; j++) {
                int i = from + side * j;
                int l = walkAt(level, x, z, d, i, off);
                if (l >= cur + 1) break;
                for (int y = l; y < cur; y++) pieces.add(new Piece(at(x, z, d, i, off, y), Part.BLOCK, d, false));
                pieces.add(new Piece(at(x, z, d, i, off, cur), Part.STAIRS, up, false));
                if (l >= cur) break;
                cur--;
            }
        }
    }

    private static int walkAt(ServerLevel level, int x, int z, Direction d, int i, int off) {
        BlockPos c = at(x, z, d, i, off, 0);
        return walk(level, c.getX(), c.getZ());
    }

    // ------------------------------------------------------------------ the aqueduct and the canal

    /** Water found out along one side from a spot: the first water column from i = 16 on, and its surface. */
    record Source(Direction d, int at, int surface) {}

    /** Open water (three columns running) out along each side of a spot, the nearest first; null if none. */
    @Nullable
    static Source source(ServerLevel level, BlockPos from, int near, int far, @Nullable Direction only) {
        Source best = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (only != null && d != only) continue;
            for (int i = near; i <= far; i++) {
                BlockPos c = at(from.getX(), from.getZ(), d, i, 0, 0);
                if (!loaded(level, c.getX(), c.getZ())) break;
                if (!water(level, c.getX(), c.getZ())) continue;
                boolean open = true;
                for (int k = 1; k <= 2; k++) {
                    BlockPos n = at(from.getX(), from.getZ(), d, i + k, 0, 0);
                    if (!loaded(level, n.getX(), n.getZ()) || !water(level, n.getX(), n.getZ())) open = false;
                }
                if (open && (best == null || i < best.at())) best = new Source(d, i, walk(level, c.getX(), c.getZ()) - 1);
                break;
            }
        }
        return best;
    }

    /**
     * The aqueduct: from the fountain (or the heart, with none yet) out to the nearest open water between twenty and
     * seventy blocks off, a channel on arches back toward the square, ending five blocks short of it over a cistern.
     * Null if there is no water that way, or a house stands in the line.
     */
    @Nullable
    static Plan aqueduct(ServerLevel level, UUID village, BlockPos centre) {
        BlockPos fountain = Villages.builtAt(village, "fountain");
        BlockPos t = fountain != null ? fountain : centre;
        Source s = source(level, t, 20, 72, null);
        if (s == null) return null;
        int x = t.getX(), z = t.getZ();
        Direction d = s.d();
        int end = 9, intake = s.at();                          // the spout's column, and the first over the water
        int high = Integer.MIN_VALUE;
        for (int i = end - 3; i < intake; i++) {
            for (int off = -2; off <= 2; off++) high = Math.max(high, walkAt(level, x, z, d, i, off));
        }
        int w = Math.max(high + 4, s.surface() + 3);            // the water's level in the channel: room to walk under
        // Nothing built in the way: the columns under the channel clear from the ground to over it.
        for (int i = end; i <= intake; i++) {
            for (int off = -1; off <= 1; off++) {
                int l = walkAt(level, x, z, d, i, off);
                for (int y = l; y <= w + 1; y++) if (blocked(level, at(x, z, d, i, off, y))) return null;
            }
        }
        // The cistern's ground: open, and level (its water would run out of a basin cut in a slope).
        int g = walkAt(level, x, z, d, end - 2, 0);
        for (int i = end - 4; i < end; i++) {
            for (int off = -2; off <= 2; off++) {
                int l = walkAt(level, x, z, d, i, off);
                if (l != g || blocked(level, at(x, z, d, i, off, l))) return null;
                if (!openGround(level.getBlockState(at(x, z, d, i, off, l - 1)))) return null;
            }
        }
        List<Piece> pieces = new ArrayList<>();
        // The intake's pier, from the river bed up, and the piers along: every fourth column from the spout.
        BlockPos in = at(x, z, d, intake, 0, 0);
        int bedTop = bed(level, in.getX(), in.getZ(), s.surface());
        for (int off = -1; off <= 1; off++) {
            for (int y = bedTop + 1; y <= w - 2; y++) pieces.add(new Piece(at(x, z, d, intake, off, y), Part.BLOCK, d, false));
        }
        for (int i = end; i < intake; i++) {
            if ((i - end) % 4 != 0) continue;
            for (int off = -1; off <= 1; off++) {
                int l = walkAt(level, x, z, d, i, off);
                for (int y = l; y <= w - 2; y++) pieces.add(new Piece(at(x, z, d, i, off, y), Part.BLOCK, d, false));
            }
        }
        // The channel's floor and sides, then the arches' shoulders under it.
        for (int i = end; i <= intake; i++) {
            for (int off = -1; off <= 1; off++) pieces.add(new Piece(at(x, z, d, i, off, w - 1), Part.BLOCK, d, false));
            for (int off : new int[]{ -1, 1 }) pieces.add(new Piece(at(x, z, d, i, off, w), Part.BLOCK, d, false));
        }
        pieces.add(new Piece(at(x, z, d, intake, 0, w), Part.BLOCK, d, false));       // the intake end walled
        for (int i = end; i < intake; i++) {
            int k = (i - end) % 4;
            if (k == 0) continue;
            if (k == 1 || k == 3) {
                Direction face = k == 1 ? d.getOpposite() : d;                         // the shoulder against its pier
                for (int off = -1; off <= 1; off++) {
                    BlockPos p = at(x, z, d, i, off, w - 2);
                    if (walkAt(level, x, z, d, i, off) < w - 2) pieces.add(new Piece(p, Part.STAIRS_TOP, face, false));
                }
            }
        }
        // The cistern at the spout's foot: three by three, sunk a block into the ground, on a stone floor, rimmed with slabs.
        for (int i = end - 3; i <= end - 1; i++) {
            for (int off = -1; off <= 1; off++) pieces.add(new Piece(at(x, z, d, i, off, g - 2), Part.BLOCK, d, true));
        }
        for (int i = end - 4; i <= end; i++) {
            for (int off = -2; off <= 2; off++) {
                if (i > end - 4 && i < end && Math.abs(off) < 2) continue;
                if (i == end && Math.abs(off) <= 1) continue;          // under the spout's own pier
                pieces.add(new Piece(at(x, z, d, i, off, walkAt(level, x, z, d, i, off)), Part.SLAB, d, false));
            }
        }
        // The water, last: the cistern filled, then the channel from the intake down to the spout.
        for (int i = end - 3; i <= end - 1; i++) {
            for (int off = -1; off <= 1; off++) pieces.add(new Piece(at(x, z, d, i, off, g - 1), Part.WATER, d, true));
        }
        for (int i = intake - 1; i >= end; i--) pieces.add(new Piece(at(x, z, d, i, 0, w), Part.WATER, d, false));
        // A lantern on the spout's pier.
        pieces.add(new Piece(at(x, z, d, end, -1, w + 1), Part.LANTERN, d, false));
        pieces.add(new Piece(at(x, z, d, end, 1, w + 1), Part.LANTERN, d, false));
        List<BlockPos> ribbon = new ArrayList<>();
        for (int off = -1; off <= 1; off++) ribbon.add(at(x, z, d, end - 5, off, walkAt(level, x, z, d, end - 5, off)));
        BlockPos focus = at(x, z, d, end - 7, 0, walkAt(level, x, z, d, end - 7, 0));
        List<BlockPos> stands = new ArrayList<>();
        for (int i = end - 5; i < intake; i += 8) {
            stands.add(at(x, z, d, i, 3, walkAt(level, x, z, d, i, 3)));
            stands.add(at(x, z, d, i, -3, walkAt(level, x, z, d, i, -3)));
        }
        String where = "from the " + (s.at() < 40 ? "water" : "far water") + " to the " + (fountain != null ? "fountain" : "square")
            + ", carried on arches from the " + d.getName();
        return new Plan(BigWorks.Work.AQUEDUCT, pieces, ribbon, d.getClockWise().getAxis(), focus, d.getOpposite(), stands, where,
            "fresh running water to the square: a cistern for every bucket, and the town's own fountain fed", at(x, z, d, (end + intake) / 2, 0, w));
    }

    /**
     * A canal: from the fields' near edge (or the heart) out to water between ten and fifty blocks off, along ground
     * no more than a block over the water's level, a channel cut at the water's level, lined and floored in stone.
     */
    @Nullable
    static Plan canal(ServerLevel level, UUID village, BlockPos centre) {
        int side = Villages.fieldsSide(village);
        BlockPos t = centre;
        if (side >= 0) {
            t = at(centre.getX(), centre.getZ(), side(side), Villages.FIRST_BLOCK, 0, centre.getY());
            if (!loaded(level, t.getX(), t.getZ())) return null;
        }
        Source s = source(level, t, 10, 50, null);
        if (s == null) return null;
        int x = t.getX(), z = t.getZ();
        Direction d = s.d();
        int wy = s.surface();
        for (int i = 0; i < s.at(); i++) {
            for (int off = -1; off <= 1; off++) {
                BlockPos col = at(x, z, d, i, off, 0);
                int l = walk(level, col.getX(), col.getZ());
                if (l - 1 < wy || l - 1 > wy + 1) return null;                         // too high to dig, or low and wet
                BlockState top = level.getBlockState(new BlockPos(col.getX(), l - 1, col.getZ()));
                if (!openGround(top)) return null;
                if (blocked(level, new BlockPos(col.getX(), l, col.getZ()))) return null;
            }
        }
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < s.at(); i++) {
            for (int off = -1; off <= 1; off++) {
                BlockPos col = at(x, z, d, i, off, 0);
                int l = walk(level, col.getX(), col.getZ());
                if (off == 0) {
                    pieces.add(new Piece(new BlockPos(col.getX(), wy - 1, col.getZ()), Part.BLOCK, d, true));
                    if (l - 1 > wy) pieces.add(new Piece(new BlockPos(col.getX(), l - 1, col.getZ()), Part.DIG, d, true));
                } else {
                    for (int y = wy; y <= l - 1; y++) pieces.add(new Piece(new BlockPos(col.getX(), y, col.getZ()), Part.BLOCK, d, true));
                }
            }
        }
        // The water let in from the far end, so it runs from the river to the fields.
        for (int i = s.at() - 1; i >= 0; i--) pieces.add(new Piece(at(x, z, d, i, 0, wy), Part.WATER, d, true));
        pieces.add(new Piece(at(x, z, d, -1, 0, wy), Part.BLOCK, d, true));            // the head of the canal stopped
        for (int off : new int[]{ -1, 1 }) pieces.add(new Piece(at(x, z, d, -1, off, wy), Part.BLOCK, d, true));
        for (int off : new int[]{ -2, 2 }) {
            BlockPos lamp = at(x, z, d, 0, off, walkAt(level, x, z, d, 0, off));
            pieces.add(new Piece(lamp, Part.WALL, d, false));
            pieces.add(new Piece(lamp.above(), Part.LANTERN, d, false));
        }
        List<BlockPos> ribbon = new ArrayList<>();
        int strung = Math.max(walkAt(level, x, z, d, 1, -1), walkAt(level, x, z, d, 1, 1));
        for (int off = -1; off <= 1; off++) ribbon.add(at(x, z, d, 1, off, strung));
        BlockPos focus = at(x, z, d, -2, 0, walkAt(level, x, z, d, -2, 0));
        List<BlockPos> stands = new ArrayList<>();
        for (int i = 0; i < s.at(); i += 8) stands.add(at(x, z, d, i, 3, walkAt(level, x, z, d, i, 3)));
        String where = (side >= 0 ? "from the water to the fields" : "from the water to the heart of the town") + ", cut from the " + d.getName();
        return new Plan(BigWorks.Work.CANAL, pieces, ribbon, d.getClockWise().getAxis(), focus, d.getOpposite(), stands, where,
            "water at the fields' edge, so every row is near it, and the farmers' buckets filled at the door",
            at(x, z, d, s.at() / 2, 0, wy));
    }

    // ------------------------------------------------------------------ the town wall

    /** The town wall's ring: how far out it stands from the heart. */
    static int wallRing(UUID village) {
        return Villages.townReach(village) + 6;
    }

    /**
     * One side of the town wall (0 north, 1 east, 2 south, 3 west): a wall two high with merlons along the ring
     * between its corner towers (the towers too, if no side beside them was built before), following the ground,
     * a gateway left where a road or a path goes through and over water, fields or anything built.
     */
    @Nullable
    static Plan wall(ServerLevel level, UUID village, BlockPos centre, int sideIndex, int builtSides) {
        int r = wallRing(village);
        Direction out = switch (sideIndex) {
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            case 3 -> Direction.WEST;
            default -> Direction.NORTH;
        };
        Direction along = out.getClockWise();
        int x = centre.getX(), z = centre.getZ();
        List<Piece> pieces = new ArrayList<>();
        // The gateways first: wherever a path or a paved road goes out through the line.
        List<BlockPos> gates = new ArrayList<>();
        for (int k = -r + 2; k <= r - 2; k++) {
            BlockPos col = at(x, z, out, r, k, 0);
            if (!loaded(level, col.getX(), col.getZ())) return null;
            int l = walk(level, col.getX(), col.getZ());
            BlockState top = level.getBlockState(new BlockPos(col.getX(), l - 1, col.getZ()));
            if (top.is(Blocks.DIRT_PATH) || top.is(Blocks.STONE_BRICKS) || top.is(Blocks.COBBLESTONE)) gates.add(new BlockPos(col.getX(), l, col.getZ()));
        }
        for (int k = -r + 2; k <= r - 2; k++) {
            BlockPos col = at(x, z, out, r, k, 0);
            int l = walk(level, col.getX(), col.getZ());
            BlockState top = level.getBlockState(new BlockPos(col.getX(), l - 1, col.getZ()));
            boolean open = openGround(top) && !top.is(Blocks.DIRT_PATH) && !blocked(level, new BlockPos(col.getX(), l, col.getZ()))
                && !blocked(level, new BlockPos(col.getX(), l + 1, col.getZ()));
            if (!open || nearGate(col, gates, 1)) continue;
            pieces.add(new Piece(new BlockPos(col.getX(), l, col.getZ()), Part.BLOCK, out, false));
            pieces.add(new Piece(new BlockPos(col.getX(), l + 1, col.getZ()), Part.BLOCK, out, false));
            if (Math.floorMod(k, 2) == 0) pieces.add(new Piece(new BlockPos(col.getX(), l + 2, col.getZ()), Part.WALL, out, false));
        }
        if (pieces.size() < 30) return null;                          // nothing worth calling a wall that way
        // A gateway's posts: a lantern on the wall either side of it.
        for (BlockPos gte : gates) {
            for (int s : new int[]{ -2, 2 }) {
                BlockPos post = gte.relative(along, s);
                int l = walk(level, post.getX(), post.getZ());
                pieces.add(new Piece(new BlockPos(post.getX(), l, post.getZ()), Part.LANTERN, out, false));
            }
        }
        // The towers at the corners, unless the side beside was built (and its tower with it).
        int leftSide = Math.floorMod(sideIndex - 1, 4), rightSide = Math.floorMod(sideIndex + 1, 4);
        int[][] corners = { { -r, leftSide }, { r, rightSide } };
        for (int[] cn : corners) {
            if ((builtSides & (1 << cn[1])) != 0) continue;
            BlockPos corner = at(x, z, out, r, cn[0], 0);
            tower(level, pieces, corner, out);
        }
        int mid = 0;
        BlockPos m = at(x, z, out, r, mid, 0);
        int lm = walk(level, m.getX(), m.getZ());
        BlockPos inner = at(x, z, out, r - 4, 0, 0);
        List<BlockPos> ribbon = new ArrayList<>();
        BlockPos gate0 = gates.isEmpty() ? null : gates.get(0);
        if (gate0 != null) {
            for (int s = -1; s <= 1; s++) ribbon.add(gate0.relative(along, s));
        }
        BlockPos focus = gate0 != null ? gate0.relative(out.getOpposite(), 2) : new BlockPos(inner.getX(), walk(level, inner.getX(), inner.getZ()), inner.getZ());
        List<BlockPos> stands = new ArrayList<>();
        for (int k = -r + 4; k <= r - 4; k += 10) {
            BlockPos s = at(x, z, out, r - 3, k, 0);
            stands.add(new BlockPos(s.getX(), walk(level, s.getX(), s.getZ()), s.getZ()));
        }
        String where = "along the " + out.getName() + " side of the town, " + (2 * r + 1) + " blocks from corner to corner";
        return new Plan(BigWorks.Work.WALL, pieces, ribbon, along.getAxis(), focus, out.getOpposite(), stands, where,
            "a wall between the town and whatever comes out of the " + out.getName() + " at night, and towers to watch from",
            new BlockPos(m.getX(), lm, m.getZ()));
    }

    private static boolean nearGate(BlockPos col, List<BlockPos> gates, int within) {
        for (BlockPos g : gates) if (Math.abs(g.getX() - col.getX()) + Math.abs(g.getZ() - col.getZ()) <= within) return true;
        return false;
    }

    /** A corner tower: three by three, four high on its lowest corner, merlons round its top and a lantern in the middle. */
    private static void tower(ServerLevel level, List<Piece> pieces, BlockPos corner, Direction out) {
        int base = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int l = walk(level, corner.getX() + dx, corner.getZ() + dz);
                BlockState top = level.getBlockState(new BlockPos(corner.getX() + dx, l - 1, corner.getZ() + dz));
                if (!openGround(top)) return;                        // not on water, fields or anything built
                base = Math.min(base, l);
            }
        }
        int top = base + 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int l = walk(level, corner.getX() + dx, corner.getZ() + dz);
                for (int y = l; y < top; y++) pieces.add(new Piece(new BlockPos(corner.getX() + dx, y, corner.getZ() + dz), Part.BLOCK, out, false));
                if (dx != 0 || dz != 0) {
                    if (Math.floorMod(dx + dz, 2) == 0) pieces.add(new Piece(new BlockPos(corner.getX() + dx, top, corner.getZ() + dz), Part.WALL, out, false));
                }
            }
        }
        pieces.add(new Piece(new BlockPos(corner.getX(), top, corner.getZ()), Part.LANTERN, out, false));
    }

    // ------------------------------------------------------------------ the harbour

    /**
     * A harbour, where the town comes down to wide water (ten blocks of it running out from the shore): a stone quay
     * eleven along the shore and two out, at the bank's height, on posts to the bottom at its front; a pier from its
     * middle eight out, three wide, on posts; bollards along the quay's edge and the pier's sides; lanterns at the end.
     */
    @Nullable
    static Plan harbour(ServerLevel level, UUID village, BlockPos centre) {
        int reach = Math.min(120, Villages.townReach(village) + 40);
        int x = centre.getX(), z = centre.getZ();
        Direction best = null;
        int bestAt = Integer.MAX_VALUE, surface = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int i = 8; i <= reach; i++) {
                BlockPos c = at(x, z, d, i, 0, 0);
                if (!loaded(level, c.getX(), c.getZ())) break;
                if (!water(level, c.getX(), c.getZ())) continue;
                boolean wide = true;
                for (int k = 0; k < 11 && wide; k++) {
                    for (int off = -6; off <= 6 && wide; off += 6) {
                        BlockPos n = at(x, z, d, i + k, off, 0);
                        if (!loaded(level, n.getX(), n.getZ()) || !water(level, n.getX(), n.getZ())) wide = false;
                    }
                }
                if (wide && i < bestAt) {
                    best = d;
                    bestAt = i;
                    surface = walk(level, c.getX(), c.getZ()) - 1;
                }
                break;
            }
        }
        if (best == null) return null;
        Direction d = best;
        int s0 = bestAt;
        int bank = walkAt(level, x, z, d, s0 - 1, 0);
        int q = Math.max(surface, bank - 1);
        List<Piece> pieces = new ArrayList<>();
        // The quay's posts at its front, then its deck.
        for (int off = -5; off <= 5; off += 2) {
            BlockPos col = at(x, z, d, s0 + 1, off, 0);
            int bedTop = bed(level, col.getX(), col.getZ(), surface);
            for (int y = bedTop + 1; y < q; y++) pieces.add(new Piece(at(x, z, d, s0 + 1, off, y), Part.BLOCK, d, false));
        }
        for (int i = s0; i <= s0 + 1; i++) {
            for (int off = -5; off <= 5; off++) pieces.add(new Piece(at(x, z, d, i, off, q), Part.BLOCK, d, false));
        }
        // The pier: posts on alternate columns, then its deck.
        for (int i = s0 + 2; i <= s0 + 9; i++) {
            if ((i - s0) % 2 == 1) {
                for (int off : new int[]{ -1, 1 }) {
                    BlockPos col = at(x, z, d, i, off, 0);
                    int bedTop = bed(level, col.getX(), col.getZ(), surface);
                    for (int y = bedTop + 1; y < q; y++) pieces.add(new Piece(at(x, z, d, i, off, y), Part.BLOCK, d, false));
                }
            }
            for (int off = -1; off <= 1; off++) pieces.add(new Piece(at(x, z, d, i, off, q), Part.BLOCK, d, false));
        }
        // Bollards and lanterns.
        for (int off : new int[]{ -5, -3, 3, 5 }) pieces.add(new Piece(at(x, z, d, s0 + 1, off, q + 1), Part.FENCE, d, false));
        for (int i = s0 + 4; i <= s0 + 7; i += 3) {
            for (int off : new int[]{ -1, 1 }) pieces.add(new Piece(at(x, z, d, i, off, q + 1), Part.FENCE, d, false));
        }
        for (int off : new int[]{ -1, 1 }) {
            pieces.add(new Piece(at(x, z, d, s0 + 9, off, q + 1), Part.WALL, d, false));
            pieces.add(new Piece(at(x, z, d, s0 + 9, off, q + 2), Part.LANTERN, d, false));
        }
        List<BlockPos> ribbon = new ArrayList<>();
        for (int off = -1; off <= 1; off++) ribbon.add(at(x, z, d, s0 + 2, off, q + 1));
        BlockPos focus = at(x, z, d, s0, 0, q + 1);
        List<BlockPos> stands = new ArrayList<>();
        for (int off : new int[]{ -4, 0, 4 }) stands.add(at(x, z, d, s0 - 2, off, walkAt(level, x, z, d, s0 - 2, off)));
        return new Plan(BigWorks.Work.HARBOUR, pieces, ribbon, d.getClockWise().getAxis(), focus, d.getOpposite(), stands,
            "on the " + d.getName() + " shore, a quay and a pier out into deep water",
            "a stone quay for the boats and the fishers, and a pier into deep water where the big fish are", at(x, z, d, s0 + 1, 0, q));
    }

    // ------------------------------------------------------------------ the great road

    /**
     * A great road toward the nearest neighbour: from the town's edge sixty blocks on (or as far as the neighbour's
     * own edge), three wide, paved in the work's stone laid in place of the open ground, following it up and down;
     * a lamp post every twelve blocks, one side and then the other. Water, fields and anything built are passed over.
     */
    @Nullable
    static Plan road(ServerLevel level, UUID village, BlockPos centre) {
        Villages.Village n = null;
        for (Villages.Village o : Diplomacy.neighboursOf(village)) {
            if (Wars.atWar(village, o.id())) continue;
            n = o;
            break;
        }
        if (n == null) return null;
        int dx = n.centre().getX() - centre.getX(), dz = n.centre().getZ() - centre.getZ();
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        int from = Villages.townReach(village) - 2;
        int to = (int) Math.min(from + 60, dist - Villages.townReach(n.id()));
        if (to - from < 16) return null;
        boolean xMajor = Math.abs(dx) >= Math.abs(dz);
        List<Piece> pieces = new ArrayList<>();
        List<BlockPos> stands = new ArrayList<>();
        int laid = 0;
        Direction main = toward(centre, n.centre());
        for (int i = from; i <= to; i++) {
            double f = i / dist;
            int cx = centre.getX() + (int) Math.round(dx * f), cz = centre.getZ() + (int) Math.round(dz * f);
            for (int off = -1; off <= 1; off++) {
                int px = cx + (xMajor ? 0 : off), pz = cz + (xMajor ? off : 0);
                if (!loaded(level, px, pz)) return null;
                int l = walk(level, px, pz);
                BlockPos top = new BlockPos(px, l - 1, pz);
                BlockState st = level.getBlockState(top);
                if (!openGround(st) || blocked(level, top.above())) continue;
                pieces.add(new Piece(top, Part.BLOCK, main, true));
                laid++;
            }
            if ((i - from) % 12 == 6) {
                int side = ((i - from) / 12) % 2 == 0 ? 2 : -2;
                int px = cx + (xMajor ? 0 : side), pz = cz + (xMajor ? side : 0);
                int l = walk(level, px, pz);
                BlockPos foot = new BlockPos(px, l, pz);
                if (openGround(level.getBlockState(foot.below())) && !blocked(level, foot) && !blocked(level, foot.above())) {
                    pieces.add(new Piece(foot, Part.WALL, main, false));
                    pieces.add(new Piece(foot.above(), Part.LANTERN, main, false));
                }
            }
            if ((i - from) % 10 == 0) {
                int px = cx + (xMajor ? 0 : 3), pz = cz + (xMajor ? 3 : 0);
                stands.add(new BlockPos(px, walk(level, px, pz), pz));
            }
        }
        if (laid < 30) return null;
        double f0 = from / dist;
        int sx = centre.getX() + (int) Math.round(dx * f0), sz = centre.getZ() + (int) Math.round(dz * f0);
        List<BlockPos> ribbon = new ArrayList<>();
        Direction across = xMajor ? Direction.SOUTH : Direction.EAST;
        for (int off = -1; off <= 1; off++) {
            int px = sx + (xMajor ? 0 : off), pz = sz + (xMajor ? off : 0);
            ribbon.add(new BlockPos(px, walk(level, px, pz), pz));
        }
        BlockPos focus = new BlockPos(sx - main.getStepX() * 2, 0, sz - main.getStepZ() * 2);
        focus = new BlockPos(focus.getX(), walk(level, focus.getX(), focus.getZ()), focus.getZ());
        return new Plan(BigWorks.Work.ROAD, pieces, ribbon, across.getAxis(), focus, main.getOpposite(), stands,
            "a paved road from the town's edge toward " + Villages.name(n.id()),
            "a road to " + Villages.name(n.id()) + " fit for carts in all weathers, lit at night: trade, visits and help when it's needed",
            new BlockPos(sx, walk(level, sx, sz), sz));
    }

    // ------------------------------------------------------------------ keeping a plan

    /** The pieces, kept with the world as three arrays (positions, parts with the ground bit, facings). */
    static CompoundTag save(List<Piece> pieces) {
        long[] pos = new long[pieces.size()];
        byte[] part = new byte[pieces.size()], face = new byte[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            pos[i] = p.pos().asLong();
            part[i] = (byte) (p.part().ordinal() | (p.ground() ? 0x40 : 0));
            face[i] = (byte) p.facing().get3DDataValue();
        }
        CompoundTag t = new CompoundTag();
        t.put("pos", new LongArrayTag(pos));
        t.put("part", new ByteArrayTag(part));
        t.put("face", new ByteArrayTag(face));
        return t;
    }

    static List<Piece> load(CompoundTag t) {
        long[] pos = t.getLongArray("pos");
        byte[] part = t.getByteArray("part"), face = t.getByteArray("face");
        List<Piece> out = new ArrayList<>();
        Part[] parts = Part.values();
        for (int i = 0; i < pos.length && i < part.length && i < face.length; i++) {
            int k = part[i] & 0x3F;
            if (k >= parts.length) continue;
            out.add(new Piece(BlockPos.of(pos[i]), parts[k], Direction.from3DDataValue(face[i]), (part[i] & 0x40) != 0));
        }
        return out;
    }

    /** What the pieces cost: {stone blocks, lanterns, fences, planks, water (0 or 1)}. */
    static int[] cost(List<Piece> pieces) {
        int halves = 0, lanterns = 0, fences = 0, planks = 0, water = 0;
        for (Piece p : pieces) {
            halves += p.part().halves;
            switch (p.part()) {
                case LANTERN, LANTERN_HUNG -> lanterns++;
                case FENCE -> fences++;
                case PLANKS -> planks++;
                case WATER -> water = 1;
                default -> { }
            }
        }
        return new int[]{ (halves + 1) / 2, lanterns, fences, planks, water };
    }
}
