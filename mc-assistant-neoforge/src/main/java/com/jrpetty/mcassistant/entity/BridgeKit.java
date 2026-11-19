package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [bridges18] The town's one way of bridging water: every road bridge the folk build is drawn from this kit, so a bridge
 * looks built and not scattered, and the same from one town to the next.
 * <ul>
 * <li><b>Square to the river.</b> However the road comes at the water, the bridge runs straight along the way the road
 *     mostly goes, on the line through the middle of the crossing, from bank to bank, its ends on the land.</li>
 * <li><b>The deck.</b> As wide as the road and a block more each side for the railings, level from end to end, a clear
 *     block over the water so a boat passes under it (or level with the lower bank where the banks stand higher).
 *     Timber: a plank deck between two log beams. Stone: a deck of the stone, edge to edge.</li>
 * <li><b>What holds it up.</b> An abutment of the stone or the planks under each end down to the bank, and between them
 *     piers at even spacing (four to six apart, symmetric) down to the river bed: a timber bridge two log posts and a
 *     cross beam; a stone one a pier the deck's width, with an arch's shoulders (upside-down stairs) either side.</li>
 * <li><b>The railings and the lights.</b> A fence (timber) or a wall (stone) along both edges from end to end, a post at
 *     each end with a lantern on it, and lanterns on the railings at even spacing between.</li>
 * <li><b>The ramps.</b> At each end stairs of the bridge's own material, the road's width, down (or up) to the ground,
 *     with a landing every four steps on a long one, a cheek block either side.</li>
 * <li><b>One material.</b> Decided once for the whole bridge from what the stores hold (timber till the road is stone;
 *     stone bricks if the stores have enough for all of it, else cobblestone; log beams if there are logs enough, else
 *     planks), and the bridge waits for that material, never patched with another.</li>
 * </ul>
 * Laid a few blocks a visit by the road gang, out of the stores; an old bridge of the earlier kind on the same crossing
 * (the planks laid flush on the water) is taken up into the stores as the new one goes down. The crossings are kept in the
 * town's notes, so a restart draws the same bridge.
 */
public final class BridgeKit {

    private BridgeKit() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** What a road step returns when it laid part of a bridge and there is more to lay at the same place. */
    public static final String MORE = "more of the bridge to lay";
    /** Blocks laid a step. */
    static final int PER_STEP = 12;
    /** The longest ramp, a landing in it every so many steps, and the deepest pier. */
    static final int MOST_RAMP = 9, LANDING_EVERY = 4, MOST_DEPTH = 24;

    /** One bridge's materials. */
    public record Family(String name, boolean wood, Block deck, Block beam, Block post, Block stairs, Block slab, Block rail, Item item) {
        /** Timber with log beams and posts. */
        boolean logs() { return beam != deck; }
    }

    public static final Family TIMBER = new Family("timber", true, Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_SPRUCE_LOG, Blocks.SPRUCE_LOG,
        Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_FENCE, Items.SPRUCE_PLANKS);
    public static final Family PLANKS = new Family("plank", true, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS,
        Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_FENCE, Items.SPRUCE_PLANKS);
    public static final Family BRICK = new Family("stone brick", false, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS,
        Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_WALL, Items.STONE_BRICKS);
    public static final Family COBBLE = new Family("cobblestone", false, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE,
        Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_SLAB, Blocks.COBBLESTONE_WALL, Items.COBBLESTONE);

    static Family named(String n) {
        for (Family f : new Family[]{ TIMBER, PLANKS, BRICK, COBBLE }) if (f.name().equals(n)) return f;
        return TIMBER;
    }

    /** The family for a bridge of so many blocks: stone or timber, and the best of it the stores can run to all through. */
    static Family choose(ServerLevel level, @Nullable Villages.Village v, boolean stone, int blocks) {
        if (v == null) return stone ? BRICK : TIMBER;
        if (stone) return Market.stock(level, v.id(), s -> s.is(Items.STONE_BRICKS)) >= blocks ? BRICK : COBBLE;
        return Market.stock(level, v.id(), s -> s.is(ItemTags.LOGS)) >= Math.max(4, blocks / 4) ? TIMBER : PLANKS;
    }

    // ------------------------------------------------------------------ the ground

    /** What lies under a column: the ground's top (the earth, a road on it, the fill under a ramp) or water's surface. */
    record Column(int y, boolean water) {}

    /** A block a bridge or a step is made of (or an old bridge was). */
    static boolean made(BlockState st) {
        Block b = st.getBlock();
        return st.is(BlockTags.PLANKS) || st.is(BlockTags.LOGS) || st.is(BlockTags.FENCES) || st.is(BlockTags.WALLS)
            || b instanceof StairBlock || b instanceof SlabBlock || b instanceof LanternBlock || st.is(Blocks.TORCH)
            || st.is(BlockTags.STONE_BRICKS) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE);
    }

    /** Half a block or less to stand on, or nothing: stairs, slabs, railings, lights, plants. */
    private static boolean partial(BlockState st) {
        Block b = st.getBlock();
        return st.isAir() || st.canBeReplaced() || b instanceof StairBlock || b instanceof SlabBlock || b instanceof FenceBlock
            || b instanceof WallBlock || b instanceof LanternBlock || st.is(Blocks.TORCH) || st.is(BlockTags.LEAVES)
            || st.is(BlockTags.FLOWERS) || st.is(Blocks.SNOW) || st.is(Blocks.LADDER);
    }

    /** The world's own ground (no tree). */
    static boolean earth(BlockState st) {
        return Railways.natural(st) && !st.is(BlockTags.LOGS) && !st.is(BlockTags.LEAVES) && !st.canBeReplaced();
    }

    /**
     * The column under (x, z) as a bridge sees it: through railings, stairs and plants; through a deck to the water under
     * it; the top of a built-up block standing on the earth (a road, a ramp's fill) is the ground. Null over lava or
     * nothing near.
     */
    @Nullable
    static Column column(ServerLevel level, int x, int z) {
        return column(level, x, z, false);
    }

    /** As above; {@code throughTimber}: a timber bridge's own planks and logs looked through too (a stone one going up in its place). */
    @Nullable
    static Column column(ServerLevel level, int x, int z, boolean throughTimber) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int floor = Math.max(level.getMinBuildHeight() + 1, y - 48);
        for (; y > floor; y--) {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            if (!st.getFluidState().isEmpty()) return st.getFluidState().is(FluidTags.WATER) ? new Column(y, true) : null;
            if (partial(st) || st.is(BlockTags.LOGS) || throughTimber && st.is(BlockTags.PLANKS)) continue;
            if (earth(st)) return new Column(y, false);
            // A made block: on the earth (a road, a fill) it is the ground; over water (a deck) the water is.
            int top = y;
            for (int q = y - 1; q > Math.max(floor, y - MOST_DEPTH); q--) {
                BlockState s = level.getBlockState(new BlockPos(x, q, z));
                if (!s.getFluidState().isEmpty()) return s.getFluidState().is(FluidTags.WATER) ? new Column(q, true) : null;
                if (earth(s)) return new Column(top, false);
            }
            return new Column(top, false);
        }
        return null;
    }

    // ------------------------------------------------------------------ the plan

    /** One block of a bridge: where, what, and its kind (P pier, H arch, A abutment or fill, D deck, S stair, L landing,
     *  R railing, E end post, Q light, C cut out of the ground, X an old bridge's block taken up). */
    record Piece(BlockPos pos, BlockState state, char kind) {}

    /** Where a bridge goes: square along X or Z, on the minor line, over the water from w0 to w1, the water's top and the deck's height. */
    public record Shape(boolean alongX, int minor, int w0, int w1, int waterY, int deckY, int half) {
        BlockPos at(int major, int c, int y) { return alongX ? new BlockPos(major, y, minor + c) : new BlockPos(minor + c, y, major); }
        Direction forward() { return alongX ? Direction.EAST : Direction.SOUTH; }
        int length() { return w1 - w0 + 3; }

        String encode() { return (alongX ? "x" : "z") + ":" + minor + ":" + w0 + ":" + w1 + ":" + waterY + ":" + deckY + ":" + half; }

        @Nullable
        static Shape decode(String s) {
            try {
                String[] p = s.split(":");
                return new Shape(p[0].equals("x"), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** A bridge drawn: its shape, family and blocks in the order they are laid, and how far the laying has got. */
    public static final class Plan {
        final String key;
        final Shape shape;
        final Family family;
        final List<Piece> pieces;
        int cursor;

        Plan(String key, Shape shape, Family family, List<Piece> pieces) {
            this.key = key;
            this.shape = shape;
            this.family = family;
            this.pieces = pieces;
        }

        public Shape shape() { return shape; }
        public Family family() { return family; }
        public int size() { return pieces.size(); }
    }

    /** The piers' places between two ends: as many bays as make each four to six long, evenly spaced. */
    static List<Integer> piers(int from, int to) {
        List<Integer> out = new ArrayList<>();
        int d = to - from;
        int bays = Math.max(1, (int) Math.round(d / 5.0));
        for (int k = 1; k < bays; k++) out.add(from + (int) Math.round(k * d / (double) bays));
        return out;
    }

    /** The lamps' places on the railings between the end posts: none on a short bridge, else evenly about six apart. */
    static List<Integer> lamps(int from, int to) {
        List<Integer> out = new ArrayList<>();
        int d = to - from;
        if (d < 8) return out;
        int n = Math.max(1, (int) Math.round(d / 6.0) - 1);
        for (int k = 1; k <= n; k++) out.add(from + (int) Math.round(k * d / (double) (n + 1)));
        return out;
    }

    private static BlockState pillar(Block b, Direction.Axis axis) {
        BlockState st = b.defaultBlockState();
        return st.hasProperty(RotatedPillarBlock.AXIS) ? st.setValue(RotatedPillarBlock.AXIS, axis) : st;
    }

    static BlockState stair(Block b, Direction facing, boolean top) {
        return b.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
    }

    /** The deck's level: a clear block over the water, or level with the lower bank where both banks stand higher. */
    static int deckY(ServerLevel level, boolean alongX, int minor, int w0, int w1, int waterY) {
        int deck = waterY + 2;
        Column a = column(level, alongX ? w0 - 1 : minor, alongX ? minor : w0 - 1);
        Column b = column(level, alongX ? w1 + 1 : minor, alongX ? minor : w1 + 1);
        if (a != null && b != null && !a.water() && !b.water()) deck = Math.max(deck, Math.min(Math.min(a.y(), b.y()), waterY + 6));
        return deck;
    }

    /**
     * The shape of the crossing a road's line makes from land at index a to land at index b: square along the way it
     * mostly goes, on the middle line, over the water found along that line (the strip as wide as the bridge).
     */
    @Nullable
    static Shape shape(ServerLevel level, List<int[]> line, int a, int b, int half) {
        int[] pa = line.get(a), pb = line.get(b);
        boolean alongX = Math.abs(pb[0] - pa[0]) >= Math.abs(pb[1] - pa[1]);
        int ma = alongX ? pa[0] : pa[1], mb = alongX ? pb[0] : pb[1];
        int minor = (int) Math.round(((alongX ? pa[1] : pa[0]) + (alongX ? pb[1] : pb[0])) / 2.0);
        int lo = Math.min(ma, mb), hi = Math.max(ma, mb);
        int w0 = Integer.MAX_VALUE, w1 = Integer.MIN_VALUE;
        int[] waterY = { Integer.MIN_VALUE };
        for (int m = lo; m <= hi; m++) {
            if (!wet(level, alongX, minor, m, half, waterY)) continue;
            w0 = Math.min(w0, m);
            w1 = Math.max(w1, m);
        }
        if (w0 == Integer.MAX_VALUE) return null;
        // The water runs on past the line's own ends where the road meets the river slantwise: the bridge's strip with it.
        while (w0 > lo - 6 && wet(level, alongX, minor, w0 - 1, half, waterY)) w0--;
        while (w1 < hi + 6 && wet(level, alongX, minor, w1 + 1, half, waterY)) w1++;
        int wy = waterY[0];
        return new Shape(alongX, minor, w0, w1, wy, deckY(level, alongX, minor, w0, w1, wy), half);
    }

    /** Is there water under any of the strip's columns at this step along it (its top kept, the highest)? */
    private static boolean wet(ServerLevel level, boolean alongX, int minor, int m, int half, int[] top) {
        boolean any = false;
        for (int c = -half; c <= half; c++) {
            Column col = column(level, alongX ? m : minor + c, alongX ? minor + c : m);
            if (col == null || !col.water()) continue;
            any = true;
            top[0] = Math.max(top[0], col.y());
        }
        return any;
    }


    /** The bridge's blocks for a shape and a family, in the order they are laid; old blocks at {@code old} taken up. */
    static List<Piece> pieces(ServerLevel level, Shape s, Family f, List<BlockPos> old) {
        List<Piece> out = new ArrayList<>();
        int e0 = s.w0() - 1, e1 = s.w1() + 1, deck = s.deckY(), h = s.half();
        Direction fwd = s.forward();
        Direction.Axis along = s.alongX() ? Direction.Axis.X : Direction.Axis.Z, across = s.alongX() ? Direction.Axis.Z : Direction.Axis.X;
        List<Integer> piers = piers(e0, e1);
        // The piers, from the bed up.
        for (int p : piers) {
            for (int c = -h; c <= h; c++) {
                boolean edge = Math.abs(c) == h;
                if (f.wood() && !edge) {
                    out.add(new Piece(s.at(p, c, deck - 1), pillar(f.beam(), across), 'P'));
                    continue;
                }
                int bed = bed(level, s.at(p, c, deck - 1));
                for (int y = bed; y <= deck - 1; y++) out.add(new Piece(s.at(p, c, y), f.wood() ? pillar(f.post(), Direction.Axis.Y) : f.post().defaultBlockState(), 'P'));
            }
        }
        // The abutments under the ends, down to the bank.
        for (int e : new int[]{ e0, e1 }) {
            for (int c = -h; c <= h; c++) {
                BlockPos top = s.at(e, c, deck - 1);
                for (int d = 0; d < 6; d++) {
                    BlockPos q = top.below(d);
                    BlockState st = level.getBlockState(q);
                    if (earth(st) && st.getFluidState().isEmpty()) break;
                    out.add(new Piece(q, f.deck().defaultBlockState(), 'A'));
                }
            }
        }
        // A stone bridge's arches: an upside-down stair either side of every pier and inside each abutment.
        if (!f.wood()) {
            List<Integer> sup = new ArrayList<>();
            sup.add(e0);
            sup.addAll(piers);
            sup.add(e1);
            for (int k = 0; k + 1 < sup.size(); k++) {
                int s0 = sup.get(k), s1 = sup.get(k + 1);
                if (s1 - s0 < 4) continue;
                for (int c = -h; c <= h; c++) {
                    out.add(new Piece(s.at(s0 + 1, c, deck - 1), stair(f.stairs(), fwd.getOpposite(), true), 'H'));
                    out.add(new Piece(s.at(s1 - 1, c, deck - 1), stair(f.stairs(), fwd, true), 'H'));
                }
            }
        }
        // The deck: beams at the edges, the deck between.
        for (int m = e0; m <= e1; m++) {
            for (int c = -h; c <= h; c++) {
                BlockState st = Math.abs(c) == h ? pillar(f.beam(), along) : f.deck().defaultBlockState();
                out.add(new Piece(s.at(m, c, deck), st, 'D'));
            }
        }
        // The ramps at both ends.
        ramp(level, s, f, e0, fwd.getOpposite(), out);
        ramp(level, s, f, e1, fwd, out);
        // The railings, the end posts and the lights.
        List<Integer> lamps = lamps(e0, e1);
        for (int m = e0; m <= e1; m++) {
            for (int c : new int[]{ -h, h }) {
                boolean end = m == e0 || m == e1;
                out.add(new Piece(s.at(m, c, deck + 1), end ? (f.wood() ? pillar(f.post(), Direction.Axis.Y) : f.post().defaultBlockState()) : f.rail().defaultBlockState(), end ? 'E' : 'R'));
            }
        }
        for (int m = e0; m <= e1; m++) {
            if (m != e0 && m != e1 && !lamps.contains(m)) continue;
            for (int c : new int[]{ -h, h }) out.add(new Piece(s.at(m, c, deck + 2), Blocks.LANTERN.defaultBlockState(), 'Q'));
        }
        // Room over the deck: plants and leaves off it.
        for (int m = e0; m <= e1; m++) {
            for (int c = -h + 1; c <= h - 1; c++) for (int y = 1; y <= 3; y++) out.add(new Piece(s.at(m, c, deck + y), Blocks.AIR.defaultBlockState(), 'C'));
        }
        // The old bridge's blocks that the new one does not cover, taken up.
        Set<BlockPos> mine = new HashSet<>();
        for (Piece p : out) mine.add(p.pos());
        for (BlockPos q : old) if (!mine.contains(q)) out.add(new Piece(q, Blocks.AIR.defaultBlockState(), 'X'));
        return out;
    }

    /** The first block of the river bed under this place (the lowest block a pier stands in). */
    private static int bed(ServerLevel level, BlockPos top) {
        int y = top.getY();
        for (int d = 0; d < MOST_DEPTH; d++) {
            BlockState st = level.getBlockState(new BlockPos(top.getX(), y - 1, top.getZ()));
            if (earth(st) && st.getFluidState().isEmpty()) break;
            y--;
        }
        return y;
    }

    /**
     * A ramp out from the end at {@code end}, going {@code out}: a stair a block, down onto (or up into) the ground at the
     * road's width, a landing every few steps, a cheek block either side; the fill under it and the bank cut over it.
     */
    private static void ramp(ServerLevel level, Shape s, Family f, int end, Direction out, List<Piece> pieces) {
        int h = s.half();
        int step = out == s.forward() ? 1 : -1;
        int walk = s.deckY() + 1, run = 0;
        Direction.Axis along = s.alongX() ? Direction.Axis.X : Direction.Axis.Z;
        for (int k = 1; k <= MOST_RAMP; k++) {
            int m = end + step * k;
            Column g = column(level, s.at(m, 0, 0).getX(), s.at(m, 0, 0).getZ(), !f.wood());
            if (g == null || g.water()) break;
            int ground = g.y() + 1;
            if (ground == walk) break;
            boolean landing = run >= LANDING_EVERY && Math.abs(ground - walk) > 1;
            int y;
            BlockState tread;
            if (landing) {
                y = walk - 1;
                tread = f.deck().defaultBlockState();
                run = 0;
            } else if (ground < walk) {
                y = walk - 1;
                tread = stair(f.stairs(), out.getOpposite(), false);
                walk--;
                run++;
            } else {
                y = walk;
                tread = stair(f.stairs(), out, false);
                walk++;
                run++;
            }
            for (int c = -h; c <= h; c++) {
                boolean cheek = Math.abs(c) == h;
                BlockPos at = s.at(m, c, y);
                Column under = cheek ? column(level, at.getX(), at.getZ(), !f.wood()) : g;
                int base = under == null || under.water() ? g.y() : under.y();
                for (int q = base + 1; q < y; q++) pieces.add(new Piece(at.atY(q), f.deck().defaultBlockState(), 'A'));
                pieces.add(new Piece(at, cheek ? pillar(f.beam(), along) : tread, cheek ? 'A' : (landing ? 'L' : 'S')));
                // The railing carried down the ramp on its cheeks: nobody steps up onto the side of it.
                if (cheek) pieces.add(new Piece(at.above(), f.rail().defaultBlockState(), 'R'));
                for (int q = y + (cheek ? 2 : 1); q <= y + 3; q++) pieces.add(new Piece(at.atY(q), Blocks.AIR.defaultBlockState(), 'C'));
            }
        }
    }

    // ------------------------------------------------------------------ laying it

    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        PLANS.clear();
    }

    /** Is this piece of the bridge there already? */
    static boolean done(BlockState st, Piece p) {
        BlockState want = p.state();
        if (p.kind() == 'X') return st.isAir() || !made(st) || st.hasBlockEntity();
        if (p.kind() == 'C') return st.isAir() || !(st.canBeReplaced() || st.is(BlockTags.LEAVES) || st.is(BlockTags.FLOWERS) || st.is(Blocks.SNOW)
            || earth(st) || st.is(BlockTags.LOGS)) || !st.getFluidState().isEmpty();
        if (p.kind() == 'Q') return st.getBlock() instanceof LanternBlock || st.is(Blocks.TORCH);
        if (st.getBlock() != want.getBlock()) return false;
        if (want.getBlock() instanceof StairBlock) {
            return st.getValue(StairBlock.FACING) == want.getValue(StairBlock.FACING) && st.getValue(StairBlock.HALF) == want.getValue(StairBlock.HALF);
        }
        if (want.hasProperty(RotatedPillarBlock.AXIS)) return st.getValue(RotatedPillarBlock.AXIS) == want.getValue(RotatedPillarBlock.AXIS);
        return true;
    }

    /** Somebody else's: a block with something in it, or anything built that is no bridge's. Never broken for a bridge. */
    static boolean theirs(BlockState st) {
        if (st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty()) return false;
        if (st.hasBlockEntity()) return true;
        return !(made(st) || Railways.natural(st));
    }

    /** What comes up out of a block taken out of the way, into the stores. */
    static Item back(BlockState st) {
        if (st.isAir() || !st.getFluidState().isEmpty() || st.canBeReplaced() || st.is(BlockTags.LEAVES)) return Items.AIR;
        if (st.is(Blocks.STONE)) return Items.COBBLESTONE;
        if (st.is(BlockTags.DIRT) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.FARMLAND)) return Items.DIRT;
        return st.getBlock().asItem();
    }

    /** The piece's block out of the stores; null (and what is short in {@code why}) if they cannot run to it. */
    @Nullable
    static BlockState pay(ServerLevel level, Villages.Village v, Family f, Piece p, String[] why) {
        BlockState want = p.state();
        Block b = want.getBlock();
        if (p.kind() == 'Q') {
            Block light = Masonry.light(level, v);
            if (light == null) return null;
            return light.defaultBlockState();
        }
        if (f.wood()) {
            if (b == f.rail()) {
                if (Crafts.fence(level, v)) return want;
                why[0] = "planks for the bridge's railings";
                return null;
            }
            if (f.logs() && (b == f.beam() || b == f.post())) {
                if (Crafts.take(level, v, s -> s.is(ItemTags.LOGS), 1)) return want;
                why[0] = "logs for the bridge's beams";
                return null;
            }
            if (Crafts.usePlanks(level, v, 1)) return want;
            why[0] = "planks for the bridge";
            return null;
        }
        Item exact = b.asItem();
        if (exact != f.item() && Crafts.take(level, v, s -> s.is(exact), 1)) return want;
        if (TownWork.take(level, v, s -> s.is(f.item()), 1)) return want;
        why[0] = f.name() + " for the bridge";
        return null;
    }

    /**
     * Up to {@code budget} more of a bridge's blocks laid out of the stores ({@code free}: for nothing). Returns null when
     * the whole bridge is down, {@link #MORE} when there is more to lay, or what the stores are short of.
     */
    @Nullable
    static String lay(ServerLevel level, @Nullable Villages.Village v, Plan plan, int budget, boolean free) {
        int laid = 0;
        String[] why = { null };
        while (plan.cursor < plan.pieces.size()) {
            Piece p = plan.pieces.get(plan.cursor);
            if (!level.isLoaded(p.pos())) level.getChunk(p.pos());
            BlockState was = level.getBlockState(p.pos());
            if (done(was, p) || theirs(was)) {
                plan.cursor++;
                continue;
            }
            if (laid >= budget) return MORE;
            BlockState put;
            if (p.state().isAir()) {
                put = p.state();
            } else if (free || v == null) {
                put = p.state();
            } else {
                put = pay(level, v, plan.family, p, why);
                if (put == null) {
                    if (p.kind() == 'Q') {               // no light to hand: the bridge goes on without it for now
                        plan.cursor++;
                        continue;
                    }
                    return why[0] == null ? "stone for the bridge" : why[0];
                }
            }
            if (!free && v != null) {
                Item up = back(was);
                if (up != Items.AIR) Crafts.giveBack(level, v, up, 1);
            }
            if (!was.getFluidState().isEmpty() && put.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
                put = put.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
            }
            level.setBlock(p.pos(), put, Block.UPDATE_ALL);
            laid++;
            plan.cursor++;
        }
        return null;
    }

    // ------------------------------------------------------------------ on a road

    /** The note a road's crossings are kept in: "a:b:shape" for each, ";" between. */
    static final String SPANS = "b18.spans/";

    /** The road's crossings as noted: {a, b} land indices with their shape. */
    record Span(int a, int b, Shape shape) {}

    private static List<Span> spans(UUID owner, String road) {
        List<Span> out = new ArrayList<>();
        String s = Ledger.note(owner, SPANS + road);
        if (s == null || s.isEmpty()) return out;
        for (String one : s.split(";")) {
            String[] p = one.split(":", 3);
            if (p.length < 3) continue;
            try {
                Shape sh = Shape.decode(p[2]);
                if (sh != null) out.add(new Span(Integer.parseInt(p[0]), Integer.parseInt(p[1]), sh));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static void note(UUID owner, String road, List<Span> spans) {
        StringBuilder b = new StringBuilder();
        for (Span s : spans) b.append(b.length() == 0 ? "" : ";").append(s.a()).append(':').append(s.b()).append(':').append(s.shape().encode());
        Ledger.note(owner, SPANS + road, b.toString());
    }

    /** Is step i of this road within so many steps of one of its bridges (its ramps, where the road's own work keeps off)? */
    static boolean nearBridge(UUID owner, String road, int i, int reach) {
        for (Span s : spans(owner, road)) if (i > s.a() - reach && i < s.b() + reach) return true;
        return false;
    }

    /** The crossing step i of the line is in: as noted, or found now (the water under it) and noted. Null on dry land. */
    @Nullable
    static Span spanAt(ServerLevel level, UUID owner, String road, List<int[]> line, int i, int half) {
        List<Span> known = spans(owner, road);
        for (Span s : known) if (i > s.a() && i < s.b()) return s;
        int[] p = line.get(i);
        Column here = column(level, p[0], p[1]);
        if (here == null || !here.water()) return null;
        int a = i, b = i;
        while (a > 0) {
            int[] q = line.get(a - 1);
            Column c = column(level, q[0], q[1]);
            a--;
            if (c == null || !c.water()) break;
        }
        while (b < line.size() - 1) {
            int[] q = line.get(b + 1);
            Column c = column(level, q[0], q[1]);
            b++;
            if (c == null || !c.water()) break;
        }
        Shape sh = shape(level, line, a, b, half);
        if (sh == null) return null;
        Span s = new Span(a, b, sh);
        known.add(s);
        note(owner, road, known);
        LOG.info("[MCA-BRIDGE] {} crossing at {}..{}: {}", road, a, b, sh.encode());
        return s;
    }

    /** The old kind of bridge's blocks on this crossing: planks flush on the water across the road, and its rails. */
    static List<BlockPos> oldDeck(ServerLevel level, List<int[]> line, Span s, boolean wholeLineAlongX, boolean perStep) {
        List<BlockPos> out = new ArrayList<>();
        int y0 = s.shape().waterY() + 1;
        for (int j = s.a() + 1; j < s.b(); j++) {
            int[] p = line.get(j);
            boolean ax = perStep ? alongX(line, j) : wholeLineAlongX;
            for (int c = -2; c <= 2; c++) {
                int x = ax ? p[0] : p[0] + c, z = ax ? p[1] + c : p[1];
                for (int y = y0; y <= y0 + 2; y++) out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    private static boolean alongX(List<int[]> line, int i) {
        int[] s = line.get(Math.max(0, i - 3)), e = line.get(Math.min(line.size() - 1, i + 3));
        return Math.abs(e[0] - s[0]) >= Math.abs(e[1] - s[1]);
    }

    /** The plan for a noted crossing in the family the road's grade wants, drawn once. */
    static Plan plan(ServerLevel level, @Nullable Villages.Village v, UUID owner, String road, List<int[]> line, Span s, boolean stone,
                     boolean perStep) {
        String base = road + "/" + s.a();
        String fam = Ledger.note(owner, "b18.fam/" + base + (stone ? "/s" : "/w"));
        Family f;
        if (fam == null || fam.isEmpty()) {
            f = choose(level, v, stone, (s.shape().length() + 6) * (2 * s.shape().half() + 3));
            Ledger.note(owner, "b18.fam/" + base + (stone ? "/s" : "/w"), f.name());
        } else {
            f = named(fam);
        }
        String key = base + "/" + f.name();
        Family ff = f;
        return PLANS.computeIfAbsent(key, k -> new Plan(k, s.shape(), ff, pieces(level, s.shape(), ff,
            oldDeck(level, line, s, alongX(line, Math.max(0, line.size() / 2)), perStep))));
    }

    /**
     * [RoadWorks] A road step over water: the bridge across the whole crossing laid a few blocks at a time. Null when the
     * crossing's bridge is down (or there is no water here: {@code dry[0]} set), MORE, or what the stores are short of.
     */
    @Nullable
    static String roadStep(ServerLevel level, Villages.Village v, RoadWorks.Road r, int i, int grade, boolean[] dry) {
        UUID owner = r.a();
        String road = "road/" + r.b();
        Span s = spanAt(level, owner, road, r.line(), i, 2);
        if (s == null) {
            dry[0] = true;
            return null;
        }
        Plan plan = plan(level, v, owner, road, r.line(), s, grade >= 3, true);
        String done = "b18.done/" + plan.key;
        if ("1".equals(Ledger.note(owner, done))) return null;
        String out = lay(level, v, plan, PER_STEP, false);
        if (out == null) {
            Ledger.note(owner, done, "1");
            Villages.tell(v.id(), level.getDayTime() / 24000L, "a " + plan.family.name() + " bridge was finished on the road to "
                + Villages.name(r.other(v.id())));
        }
        return out;
    }

    /** [Roads] A colony road's step over water, as above (a timber bridge). Returns null when down, MORE, or "short". */
    @Nullable
    static String colonyStep(ServerLevel level, Villages.Village mother, UUID colony, List<int[]> line, int i, boolean[] dry) {
        String road = "colony";
        Span s = spanAt(level, colony, road, line, i, 2);
        if (s == null) {
            dry[0] = true;
            return null;
        }
        Plan plan = plan(level, mother, colony, road, line, s, false, false);
        String done = "b18.done/" + plan.key;
        if ("1".equals(Ledger.note(colony, done))) return null;
        String out = lay(level, mother, plan, PER_STEP, false);
        if (out == null) Ledger.note(colony, done, "1");
        return out;
    }

    /**
     * [old saves] A town's roads gone over gently once a day: a crossing on its own half whose bridge is the old kind (or
     * not finished) gets the kit's bridge, a few dozen blocks a day, out of its stores. Only the town's own roads'
     * crossings, where its ground is awake: nothing else is touched.
     */
    static void tidy(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        String k = "b18.tidy";
        String last = Ledger.note(v.id(), k);
        if (last != null && last.equals(Long.toString(day))) return;
        String working = Ledger.note(v.id(), RoadWorks.WORKING);
        if (working != null && !working.isEmpty()) return;               // the gang is out on a road: it bridges as it goes
        Ledger.note(v.id(), k, Long.toString(day));
        int budget = 48;
        for (RoadWorks.Road r : RoadWorks.roads(v.id())) {
            int grade = RoadWorks.halfState(v.id(), r.other(v.id()))[0];
            if (grade < 1) continue;
            String road = "road/" + r.b();
            int half = r.half(v.id());
            for (int n = 0; n < half && budget > 0; n++) {
                int i = r.index(v.id(), n);
                int[] p = r.line().get(i);
                if (!level.hasChunk(p[0] >> 4, p[1] >> 4)) continue;
                Span s = spanAt(level, r.a(), road, r.line(), i, 2);
                if (s == null) continue;
                Plan plan = plan(level, v, r.a(), road, r.line(), s, grade >= 3, true);
                String done = "b18.done/" + plan.key;
                if (!"1".equals(Ledger.note(r.a(), done))) {
                    // By a hand of the road gang at the bridge, as any road work.
                    BlockPos head = plan.shape.at((plan.shape.w0() + plan.shape.w1()) / 2, 0, plan.shape.deckY() + 1);
                    if (!TownJobs.atWork(level, v, "road/bridge/" + r.other(v.id()), head, "making over the bridge on the road to "
                        + Villages.name(r.other(v.id())), AssistantEntity.StationTask.HAUL)) return;
                    String out = lay(level, v, plan, budget, false);
                    if (out != null) return;                         // more another day, or the stores short
                    Ledger.note(r.a(), done, "1");
                    budget -= 16;
                }
                int past = v.id().equals(r.a()) ? s.b() : r.length() - 1 - s.a();
                n = Math.max(n, past);
            }
        }
    }
}
