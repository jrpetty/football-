package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tree-lined avenues [batchE]. The four avenues run out from the square's gates five blocks wide, and from
 * the Iron Age a lamp post stands on their edge every six blocks (TownWork). Midway between each pair of
 * posts, on the verge just off the avenue, the town plants a tree: a sapling out of the stores (the
 * woodcutters keep them coming: Woods), by a hand at the town's works, the woodcutter for choice. Never on
 * the avenue itself, nor where a street crosses it, nor within a block of a lamp post or a street sign, nor
 * within two of anybody's door, nor hard against a wall, nor out on the farmland, and only as far out as the
 * town has come.
 *
 * <p>A grown tree's crown spreads two blocks each way, over the avenue as much as the lot behind it, and its
 * lowest leaves hang at head height. So the same hand trims them: any leaf over the avenue lower than three
 * blocks above the road comes off, and what it drops (a sapling, a stick, now and then an apple) goes into
 * the stores. A tree is never felled for it.
 */
public final class Avenues {

    private Avenues() {}

    /** Lamps stand every six blocks (TownWork.LAMP_EVERY); a tree midway between two of them. */
    static final int EVERY = 6, MIDWAY = 3;
    /** The verge: the first block off the avenue's edge. */
    static final int VERGE = TownPlan.AVENUE + 1;
    /** Leaves lower than this over the road come off (a folk is two blocks tall, a player under two). */
    static final int HEADROOM = 3;
    /** The saplings the stores keep for a household's garden and the park's corners (Families, Park). */
    static final int SAPLINGS_KEPT = 2;

    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();
    private static final Map<Integer, List<int[]>> SPOTS = new ConcurrentHashMap<>();

    static void resetForTests() {
        CURSOR.clear();
    }

    /** Is this distance out on, or a block either side of, a street that crosses the avenue? */
    static boolean nearCrossing(int d) {
        for (int k = -1; k <= 1; k++) {
            int u = Math.abs(d + k);
            if (u >= TownPlan.RING && (u - TownPlan.RING) % TownPlan.PERIOD < TownPlan.STREET) return true;
            if (u < TownPlan.RING + TownPlan.STREET) return true;              // the ring street and the square
        }
        return false;
    }

    /** Every spot on the avenues' verges a tree may go, as offsets from the heart, nearest the square first. */
    static List<int[]> spots(int reach) {
        return SPOTS.computeIfAbsent(reach, r -> {
            List<int[]> out = new ArrayList<>();
            for (int d = TownPlan.RING + TownPlan.STREET; d <= r; d++) {
                if (Math.floorMod(d, EVERY) != MIDWAY || nearCrossing(d)) continue;
                for (int s : new int[]{ 1, -1 }) {
                    for (int across : new int[]{ VERGE, -VERGE }) {
                        out.add(new int[]{ s * d, across });                  // the east and west avenues
                        out.add(new int[]{ across, s * d });                  // the north and south
                    }
                }
            }
            out.sort(Comparator.comparingInt(c -> Math.max(Math.abs(c[0]), Math.abs(c[1]))));
            return List.copyOf(out);
        });
    }

    /** The world's spot for a tree at this offset (the first free block over the ground there). */
    static BlockPos at(ServerLevel level, Villages.Village v, int[] c) {
        return TownLook.ground(level, v.centre().getX() + c[0], v.centre().getZ() + c[1]);
    }

    /** Is there a tree (a sapling, or a trunk grown from one) standing on this spot? */
    static boolean planted(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.getBlock() instanceof SaplingBlock || st.is(BlockTags.LOGS) || level.getBlockState(p.below()).is(BlockTags.LOGS);
    }

    /** Could a tree be planted here: earth under it, room to grow, and in nobody's way? */
    static boolean fit(ServerLevel level, Villages.Village v, int[] c, BlockPos p) {
        if (Villages.onFarmland(v.id(), c[0], c[1], 1, 1)) return false;
        if (TownPlan.isStreet(c[0], c[1]) || TownPlan.isSquare(c[0], c[1])) return false;
        if (Math.abs(p.getY() - v.centre().getY()) > 10) return false;
        if (!level.getBlockState(p.below()).is(BlockTags.DIRT)) return false;
        for (int h = 0; h <= 5; h++) if (!TownLook.open(level.getBlockState(p.above(h)))) return false;
        // Not hard against anything built: a wall, a post, a stall beside the trunk.
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, 0, -1), p.offset(1, 2, 1))) {
            if (q.equals(p) || q.equals(p.above()) || q.equals(p.above(2))) continue;
            if (!TownLook.open(level.getBlockState(q))) return false;
        }
        return !TownLook.doorNear(level, p, 2) && !TownLook.postNear(level, p);
    }

    /**
     * One visit's work along the avenues: a tree's low leaves trimmed off the road, else a tree planted on
     * the next free spot. True if anything was done.
     */
    static boolean tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.headcount(id) < TownJobs.SETTLED) return false;
        List<int[]> spots = spots(Villages.townReach(id));
        if (spots.isEmpty()) return false;
        int cursor = CURSOR.getOrDefault(id, 0);
        boolean saplings = Market.stock(level, id, TownLook::plantable) > SAPLINGS_KEPT;
        for (int looked = 0; looked < 24; looked++) {
            int[] c = spots.get(Math.floorMod(cursor, spots.size()));
            cursor++;
            BlockPos p = at(level, v, c);
            if (!level.isLoaded(p)) continue;
            if (planted(level, p) || level.getBlockState(trunk(level, p)).is(BlockTags.LOGS)) {
                if (trim(level, v, c, trunk(level, p)) > 0) {
                    CURSOR.put(id, cursor);
                    return true;
                }
                continue;
            }
            if (!saplings || !fit(level, v, c, p)) continue;
            if (!TownJobs.atWork(level, v, "avenues", p, "planting a tree along the avenue", AssistantEntity.StationTask.WOOD)) {
                CURSOR.put(id, cursor - 1);                                  // the hand is on its way: this spot next
                return false;
            }
            if (plant(level, v, p)) {
                CURSOR.put(id, cursor);
                if (trees(level, id) == 1) Villages.tell(id, level.getDayTime() / 24000L, "the first tree was planted along the avenues");
                return true;
            }
        }
        CURSOR.put(id, cursor);
        return false;
    }

    /** The foot of the trunk standing at or just under this spot (the heightmap counts a trunk as ground). */
    static BlockPos trunk(ServerLevel level, BlockPos p) {
        BlockPos q = p;
        for (int i = 0; i < 8 && level.getBlockState(q.below()).is(BlockTags.LOGS); i++) q = q.below();
        return q;
    }

    /** A sapling out of the stores into the ground here. */
    static boolean plant(ServerLevel level, Villages.Village v, BlockPos p) {
        ItemStack one = Crafts.takeOne(level, v, TownLook::plantable);
        if (one.isEmpty()) return false;
        Block grows = Block.byItem(one.getItem());
        if (grows == Blocks.AIR || !grows.defaultBlockState().canSurvive(level, p)) {
            Crafts.store(level, v, one);
            return false;
        }
        if (!level.getBlockState(p).isAir()) level.destroyBlock(p, false);
        level.setBlock(p, grows.defaultBlockState(), 3);
        level.playSound(null, p, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    /**
     * The leaves of the tree at this spot that hang over the avenue lower than HEADROOM above the road,
     * taken off by a hand at the works, their drops into the stores. Returns how many came off.
     */
    static int trim(ServerLevel level, Villages.Village v, int[] c, BlockPos foot) {
        List<BlockPos> low = lowLeaves(level, v, c, foot);
        if (low.isEmpty()) return 0;
        if (!TownJobs.atWork(level, v, "avenues", low.get(0), "trimming the avenue trees", AssistantEntity.StationTask.WOOD)) return 0;
        int n = 0;
        for (BlockPos q : low) {
            BlockState st = level.getBlockState(q);
            if (!(st.getBlock() instanceof LeavesBlock)) continue;
            for (ItemStack drop : Block.getDrops(st, level, q, null)) Crafts.store(level, v, drop);
            level.removeBlock(q, false);
            n++;
        }
        if (n > 0) level.playSound(null, low.get(0), SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.6F, 1.0F);
        return n;
    }

    /** The leaves within the crown's reach of this trunk that are over the avenue (or a street) and too low. */
    static List<BlockPos> lowLeaves(ServerLevel level, Villages.Village v, int[] c, BlockPos foot) {
        List<BlockPos> out = new ArrayList<>();
        int hx = v.centre().getX(), hz = v.centre().getZ();
        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                int dx = c[0] + ox, dz = c[1] + oz;
                if (!TownPlan.isStreet(dx, dz)) continue;
                BlockPos road = TownLook.ground(level, hx + dx, hz + dz);
                if (Math.abs(road.getY() - foot.getY()) > 3) continue;
                for (int h = 0; h < HEADROOM; h++) {
                    BlockPos q = road.above(h);
                    if (level.getBlockState(q).getBlock() instanceof LeavesBlock) out.add(q.immutable());
                }
            }
        }
        return out;
    }

    /** How many trees (saplings and grown) stand along the village's avenues. */
    static int trees(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null || !level.isLoaded(v.centre())) return 0;
        int n = 0;
        for (int[] c : spots(Villages.townReach(village))) {
            BlockPos p = at(level, v, c);
            if (level.isLoaded(p) && (planted(level, p) || level.getBlockState(trunk(level, p)).is(BlockTags.LOGS))) n++;
        }
        return n;
    }

    static String line(ServerLevel level, Villages.Village v) {
        int spots = spots(Villages.townReach(v.id())).size(), trees = trees(level, v.id());
        return trees + " of " + spots + " spots on the verges planted; " + Market.stock(level, v.id(), TownLook::plantable) + " saplings in the stores";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: every spot on the verges, in the world, nearest first. */
    public static List<BlockPos> spotsForTests(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        for (int[] c : spots(Villages.townReach(v.id()))) out.add(at(level, v, c));
        return out;
    }

    /** Tests: the town's rounds along the avenues, so many times over. Returns how many did something. */
    public static int roundsForTests(ServerLevel level, Villages.Village v, int rounds) {
        int n = 0;
        for (int i = 0; i < rounds; i++) if (tick(level, v)) n++;
        return n;
    }

    /** Tests: the trees standing along the avenues. */
    public static int treesForTests(ServerLevel level, Villages.Village v) {
        return trees(level, v.id());
    }

    /** Tests: is a sapling of the stores' a kind the avenues take? */
    public static boolean plantableForTests(ItemStack s) {
        return TownLook.plantable(s) || s.is(Items.OAK_SAPLING);
    }
}
