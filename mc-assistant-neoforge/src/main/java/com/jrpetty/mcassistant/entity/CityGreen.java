package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [city9] The greens on the square: a bed of turf in each corner of it with a tree in the middle and flowers at its
 * corners, so the heart of the town is not a bare yard of stone.
 *
 * <p>Once the town is settled and paving its square (the Stone Age), a hand at its works lifts the paving in a corner of
 * the square, three by three (the stone back into the stores), lays turf in its place out of the stores' earth, plants a
 * sapling out of the stores in the middle (an oak, a birch or a cherry: a tree with its crown up out of folk's way, never
 * a spruce's skirts or a dark oak's thicket) and a flower from the stores at each corner of the bed. One thing a visit, one
 * corner after another, never where anything already stands (the courtyard before the board, a stall, a bench, the
 * founders' camp, a building), and only what the stores can spare: the saplings the households' gardens and the avenues
 * keep come first. Once a tree is grown, its leaves lower than three blocks over the square are trimmed (what they drop
 * into the stores), as the avenues' are. The streets' paving leaves the beds alone (TownWork).
 */
public final class CityGreen {

    private CityGreen() {}

    /** The middle of each bed, from the heart: three in from the square's corner. */
    static final int AT = TownPlan.PLAZA - 4;
    /** Leaves lower than this over the square come off. */
    static final int HEADROOM = 3;

    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CURSOR.clear();
    }

    /** Is this offset in one of the square's four beds? */
    public static boolean bed(int dx, int dz) {
        return Math.abs(Math.abs(dx) - AT) <= 1 && Math.abs(Math.abs(dz) - AT) <= 1;
    }

    /** The four beds' middles, as offsets. */
    static int[][] middles() {
        return new int[][]{ { AT, AT }, { -AT, AT }, { -AT, -AT }, { AT, -AT } };
    }

    /** A tree a green can have: one whose crown stands up out of the way. */
    static boolean fitTree(ItemStack s) {
        return s.is(Items.OAK_SAPLING) || s.is(Items.BIRCH_SAPLING) || s.is(Items.CHERRY_SAPLING);
    }

    /** One visit's work on the greens: a bed laid, a tree or a flower planted, low leaves trimmed. True if done. */
    static boolean tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.headcount(id) < TownJobs.SETTLED) return false;
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal()) return false;
        int cursor = CURSOR.getOrDefault(id, 0);
        for (int k = 0; k < 4; k++) {
            int[] m = middles()[Math.floorMod(cursor + k, 4)];
            int r = corner(level, v, m);
            if (r != 0) {
                CURSOR.put(id, cursor + k);                         // this bed again next visit (its hand on the way, or more to do)
                return r > 0;
            }
        }
        CURSOR.put(id, cursor + 1);
        return false;
    }

    /** One step on one bed: 1 if something was done, -1 if a hand is on its way (the work waits there), 0 if nothing. */
    static int corner(ServerLevel level, Villages.Village v, int[] m) {
        int hx = v.centre().getX(), hz = v.centre().getZ();
        BlockPos mid = TownLook.ground(level, hx + m[0], hz + m[1]);
        if (!level.isLoaded(mid)) return 0;
        BlockPos trunk = Avenues.trunk(level, mid);
        int floor = trunk.getY() - 1;                                   // the square's floor under the bed
        if (Math.abs(floor + 1 - v.centre().getY()) > 3) return 0;      // not a square that was never levelled
        if (!clear(level, v, m, floor)) return 0;
        // The turf first, cell by cell.
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                BlockPos g = new BlockPos(hx + m[0] + ox, floor, hz + m[1] + oz);
                BlockState st = level.getBlockState(g);
                if (st.is(Blocks.GRASS_BLOCK)) continue;
                if (Market.stock(level, v.id(), s -> s.is(Items.DIRT)) < 1) return 0;
                if (!TownBlocks.mayPlace(level, v, g, TownBlocks.Why.CIVIC)) return 0;
                if (!TownJobs.atWork(level, v, "greens", g.above(), "laying the green on the square")) return -1;
                if (!Crafts.take(level, v, s -> s.is(Items.DIRT), 1)) return 0;
                Item paving = st.getBlock().asItem();
                if (paving != Items.AIR && !st.is(BlockTags.DIRT)) Crafts.store(level, v, new ItemStack(paving));
                level.setBlock(g, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                TownBlocks.traced(v, g, TownBlocks.Why.CIVIC);
                return 1;
            }
        }
        // The tree in the middle.
        BlockPos seat = new BlockPos(hx + m[0], floor + 1, hz + m[1]);
        BlockState here = level.getBlockState(seat);
        boolean tree = here.getBlock() instanceof SaplingBlock || here.is(BlockTags.LOGS);
        if (!tree) {
            if (!TownLook.open(here)) return 0;
            if (Market.stock(level, v.id(), TownLook::plantable) <= Avenues.SAPLINGS_KEPT
                    || Market.stock(level, v.id(), CityGreen::fitTree) < 1) return 0;
            if (!TownBlocks.mayPlace(level, v, seat, TownBlocks.Why.CIVIC)) return 0;
            if (!TownJobs.atWork(level, v, "greens", seat, "planting a tree on the square", AssistantEntity.StationTask.WOOD)) return -1;
            ItemStack one = Crafts.takeOne(level, v, CityGreen::fitTree);
            if (one.isEmpty()) return 0;
            Block grows = Block.byItem(one.getItem());
            if (!grows.defaultBlockState().canSurvive(level, seat)) {
                Crafts.store(level, v, one);
                return 0;
            }
            if (!here.isAir()) level.destroyBlock(seat, false);
            level.setBlock(seat, grows.defaultBlockState(), 3);
            TownBlocks.traced(v, seat, TownBlocks.Why.CIVIC);
            level.playSound(null, seat, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            return 1;
        }
        // The flowers at the bed's corners.
        for (int[] c : new int[][]{ { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } }) {
            BlockPos f = new BlockPos(hx + m[0] + c[0], floor + 1, hz + m[1] + c[1]);
            BlockState st = level.getBlockState(f);
            if (st.is(BlockTags.SMALL_FLOWERS) || !TownLook.open(st)) continue;
            if (Market.stock(level, v.id(), s -> s.is(ItemTags.SMALL_FLOWERS)) < 1) break;
            if (!TownBlocks.mayPlace(level, v, f, TownBlocks.Why.CIVIC)) continue;
            if (!TownJobs.atWork(level, v, "greens", f, "planting flowers on the square's green")) return -1;
            ItemStack one = Crafts.takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS));
            if (one.isEmpty()) break;
            Block flower = Block.byItem(one.getItem());
            if (!flower.defaultBlockState().canSurvive(level, f)) {
                Crafts.store(level, v, one);
                break;
            }
            if (!st.isAir()) level.removeBlock(f, false);
            level.setBlock(f, flower.defaultBlockState(), 3);
            TownBlocks.traced(v, f, TownBlocks.Why.CIVIC);
            return 1;
        }
        // A grown tree's low leaves over the square.
        java.util.List<BlockPos> low = new java.util.ArrayList<>();
        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                for (int h = 1; h < 1 + HEADROOM; h++) {
                    BlockPos q = new BlockPos(hx + m[0] + ox, floor + h, hz + m[1] + oz);
                    if (level.getBlockState(q).getBlock() instanceof LeavesBlock) low.add(q);
                }
            }
        }
        if (low.isEmpty()) return 0;
        if (!TownJobs.atWork(level, v, "greens", low.get(0), "trimming the trees on the square", AssistantEntity.StationTask.WOOD)) return -1;
        for (BlockPos q : low) {
            BlockState st = level.getBlockState(q);
            for (ItemStack drop : Block.getDrops(st, level, q, null)) Crafts.store(level, v, drop);
            level.removeBlock(q, false);
        }
        level.playSound(null, low.get(0), SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.6F, 1.0F);
        return 1;
    }

    /**
     * Is the bed's ground and the ring round it the square's own open floor: level, nothing standing on it but what the
     * green itself puts there (its tree, its flowers), out of the courtyard, and no building's?
     */
    static boolean clear(ServerLevel level, Villages.Village v, int[] m, int floor) {
        int hx = v.centre().getX(), hz = v.centre().getZ();
        int[] court = Villages.courtRect(v.id());
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int x = hx + m[0] + ox, z = hz + m[1] + oz;
                if (court != null && x >= court[0] - 1 && x <= court[1] + 1 && z >= court[2] - 1 && z <= court[3] + 1) return false;
                boolean inBed = Math.abs(ox) <= 1 && Math.abs(oz) <= 1;
                BlockPos g = new BlockPos(x, floor, z);
                BlockState under = level.getBlockState(g);
                if (inBed && !under.isSolid() && !under.is(BlockTags.DIRT)) return false;   // a hole: the tidy crew's first
                for (int h = 1; h <= 3; h++) {
                    BlockState st = level.getBlockState(g.above(h));
                    if (TownLook.open(st) || st.getBlock() instanceof LeavesBlock) continue;
                    if (inBed && (st.getBlock() instanceof SaplingBlock || st.is(BlockTags.LOGS) || st.is(BlockTags.SMALL_FLOWERS))) continue;
                    return false;
                }
            }
        }
        return Villages.builtOver(v.id(), m[0], m[1], 2, 2) == false;
    }

    /** How many of the four greens have their tree (a sapling or grown). */
    public static int trees(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (int[] m : middles()) {
            BlockPos mid = TownLook.ground(level, v.centre().getX() + m[0], v.centre().getZ() + m[1]);
            if (!level.isLoaded(mid)) continue;
            BlockState st = level.getBlockState(Avenues.trunk(level, mid));
            if (st.getBlock() instanceof SaplingBlock || st.is(BlockTags.LOGS)) n++;
        }
        return n;
    }

    /** Tests: the greens' rounds, so many times over. Returns how many did something. */
    public static int roundsForTests(ServerLevel level, Villages.Village v, int rounds) {
        int n = 0;
        for (int i = 0; i < rounds; i++) if (tick(level, v)) n++;
        return n;
    }
}
