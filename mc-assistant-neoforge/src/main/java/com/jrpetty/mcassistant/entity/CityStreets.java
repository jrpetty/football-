package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.List;

/**
 * [city9] The streets laid in the order a town lays them, and in what the age lays them in.
 *
 * <p><b>The order.</b> The streets' work (TownWork) went over its cells nearest the heart first, every cell of a ring at
 * the same distance one after another, so its one hand was sent from one side of the town to the other and back for
 * each block and laid one or two a visit: a town of ninety had not a yard of street after four days. Now the cells go
 * in walking order, so the hand lays a run of them where it stands before it moves on: the square round and round, each
 * avenue from the gate outward, the ring street round, then the side streets one after another along their length,
 * the nearest first (and each only once the town has come to it: CityPlan.served).
 *
 * <p><b>The grades.</b> Bare earth is worn to a path; from the Stone Age the avenues and the ring street are gravelled;
 * from the Iron Age they are cobbled and the lanes gravelled; from the Diamond Age the avenues and the ring street are
 * laid in stone bricks. The square is cobbled from the Stone Age and in stone bricks from the Iron Age, as before. A
 * cell already laid is laid again only in something better, what came up going back into the stores. The stone comes
 * out of the stores only while they keep a builder's load ({@link #KEEP}) besides, so the streets never hold up a
 * building; short of it, the next best thing, and at worst a path.
 */
public final class CityStreets {

    private CityStreets() {}

    /** The stores keep this much of a paving besides, for the builders. */
    static final int KEEP = 64;

    /** How good a street's surface is: earth 0, path 1, gravel 2, cobblestone 3, stone bricks 4; -1 for anything else. */
    static int grade(BlockState st) {
        if (TownWork.earth(st)) return 0;
        if (st.is(Blocks.DIRT_PATH)) return 1;
        if (st.is(Blocks.GRAVEL)) return 2;
        if (st.is(Blocks.COBBLESTONE)) return 3;
        if (st.is(Blocks.STONE_BRICKS)) return 4;
        return -1;
    }

    /** The surface a cell is due, by its kind and the town's age. */
    static int due(boolean square, boolean avenue, Villages.Age age) {
        int a = age.ordinal();
        boolean stone = a >= Villages.Age.STONE.ordinal(), iron = a >= Villages.Age.IRON.ordinal(), diamond = a >= Villages.Age.DIAMOND.ordinal();
        if (square) return iron ? 4 : stone ? 3 : 1;
        if (avenue) return diamond ? 4 : iron ? 3 : stone ? 2 : 1;
        return iron ? 2 : 1;
    }

    private static final Block[] SURFACE = { null, Blocks.DIRT_PATH, Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.STONE_BRICKS };
    private static final Item[] PAID = { null, null, Items.GRAVEL, Items.COBBLESTONE, Items.STONE_BRICKS };

    /**
     * Wear or pave one cell of a street or the square (its top block {@code top}, what stands over it {@code above}).
     * 1 if it changed, 0 if nothing to do, -1 if it waits for a hand.
     */
    static int pave(ServerLevel level, Villages.Village v, BlockPos top, BlockState ground, BlockState above,
                    boolean square, boolean avenue, Villages.Age age) {
        int have = grade(ground);
        if (have < 0) return 0;
        int want = due(square, avenue, age);
        if (have >= want) return 0;
        if (!above.isAir() && !(above.canBeReplaced() && above.getFluidState().isEmpty())) return 0;
        if (!TownBlocks.mayPlace(level, v, top, TownBlocks.Why.ROAD)) return 0;                // [integrity] not a building's floor
        // The best the stores can spare, down to the next grade up from what lies there.
        int lay = -1;
        for (int g = want; g > have; g--) {
            if (PAID[g] == null) { lay = g; break; }
            Item it = PAID[g];
            if (Market.stock(level, v.id(), s -> s.is(it)) > KEEP) { lay = g; break; }
        }
        if (lay < 0) return 0;
        // Its own hand ("paving"): the doors' ways and lamps keep theirs ("streets"), and the one no longer waits on the other.
        if (!TownJobs.atWork(level, v, "paving", top, lay == 1 ? "laying the streets" : "paving the streets")) {
            WAITED.put(v.id(), top.immutable());
            return -1;
        }
        LAID.merge(v.id(), 1, Integer::sum);
        if (PAID[lay] != null) {
            Item it = PAID[lay];
            if (!TownWork.take(level, v, s -> s.is(it), 1)) return 0;
        }
        if (!above.isAir()) level.removeBlock(top.above(), false);       // the grass and flowers in the way
        Item up = have >= 2 ? PAID[have] : null;
        level.setBlockAndUpdate(top, SURFACE[lay].defaultBlockState());
        if (up != null) TownWork.give(level, v, new ItemStack(up));        // what came up, back into the stores
        return 1;
    }

    /** Each town's paving: the cell it waits on (its place in the round) and since when. */
    private record Wait(int cursor, long since) {}

    private static final java.util.Map<java.util.UUID, Wait> WAITING = new java.util.concurrent.ConcurrentHashMap<>();
    /** How long the paving waits on one cell for a hand before it passes it over (ticks). */
    static final long PATIENCE = 1200;
    private static final java.util.Map<java.util.UUID, Integer> PASSED = new java.util.concurrent.ConcurrentHashMap<>();

    /** The paving waits on the cell at this place in its round: has it waited long enough to pass it over? */
    static boolean givenUp(java.util.UUID village, int cursor, long now) {
        Wait w = WAITING.get(village);
        if (w == null || w.cursor() != cursor || now < w.since()) {
            WAITING.put(village, new Wait(cursor, now));
            return false;
        }
        if (now - w.since() < PATIENCE) return false;
        WAITING.remove(village);
        PASSED.merge(village, 1, Integer::sum);
        return true;
    }

    /** A cell seen to (or nothing to do there): the wait is over. */
    static void reached(java.util.UUID village) {
        WAITING.remove(village);
    }

    /** Tests and the books: how many cells the paving has passed over for want of a hand getting there. */
    public static int passedOver(java.util.UUID village) {
        return PASSED.getOrDefault(village, 0);
    }

    public static void resetForTests() {
        WAITING.clear();
        PASSED.clear();
        WAITED.clear();
        LAID.clear();
    }

    private static final java.util.Map<java.util.UUID, BlockPos> WAITED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> LAID = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tests and the books: the cell the paving last waited on for a hand, and how many cells it has laid. */
    public static String state(java.util.UUID village) {
        BlockPos w = WAITED.get(village);
        return "laid " + LAID.getOrDefault(village, 0) + ", passed over " + passedOver(village) + ", last waited at " + (w == null ? "-" : w.toShortString());
    }

    /** The cells (offsets from the heart) in walking order: the square, the avenues outward, the ring, the side streets. */
    static void order(List<int[]> cells) {
        cells.sort(Comparator.comparingLong(CityStreets::key));
    }

    private static long key(int[] c) {
        int x = c[0], z = c[1], ax = Math.abs(x), az = Math.abs(z);
        int u = Math.max(ax, az);
        double angle = Math.atan2(z, x) + Math.PI;                         // 0 .. 2 pi round the heart
        int turn = (int) (angle / (2 * Math.PI) * 4000);
        if (TownPlan.isSquare(x, z)) return key(0, u, turn, 0);
        if (ax <= TownPlan.AVENUE || az <= TownPlan.AVENUE) {
            int which = ax <= TownPlan.AVENUE ? (z < 0 ? 0 : 2) : (x > 0 ? 1 : 3);
            int out = ax <= TownPlan.AVENUE ? az : ax, across = ax <= TownPlan.AVENUE ? x : z;
            return key(1, which, out, across + 8);
        }
        if (u < TownPlan.RING + TownPlan.STREET) return key(2, 0, turn, u);
        boolean onX = ax >= TownPlan.RING && (ax - TownPlan.RING) % TownPlan.PERIOD < TownPlan.STREET;
        int line = onX ? (ax - TownPlan.RING) / TownPlan.PERIOD : (az - TownPlan.RING) / TownPlan.PERIOD;
        int side = onX ? (x > 0 ? 0 : 1) : (z > 0 ? 2 : 3);
        int along = (onX ? z : x) + 4096;
        return key(3 + line, side, along, onX ? ax : az);
    }

    private static long key(int cat, int line, int along, int across) {
        return ((long) cat << 48) | ((long) (line & 0xfff) << 36) | ((long) (along & 0xfffff) << 12) | (across & 0xfff);
    }
}
