package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The streets of the town plan (village/TownPlan), made real a little at a time:
 * <ul>
 * <li>the streets of the built-up part of the town are worn into paths, the way
 *     feet wear them;</li>
 * <li>from the Stone Age the square is paved, with stone out of the stores, and
 *     from the Iron Age the avenues are cobbled and lit: a post with a lamp on it
 *     every few blocks along the avenues and the ring street, out of the stores'
 *     logs and torches.</li>
 * </ul>
 * A few dozen blocks a visit, so a town's streets come in over the days.
 */
public final class TownWork {

    private TownWork() {}

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();
    private static final Map<Integer, List<int[]>> CELLS = new ConcurrentHashMap<>();
    private static final long EVERY = 300L;
    private static final int LAMP_EVERY = 6;

    /** One visit's work on a village's streets, at most once every fifteen seconds. */
    public static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        UUID id = v.id();
        if (now - LAST.getOrDefault(id, -100000L) < EVERY) return;
        LAST.put(id, now);
        int reach = Villages.townReach(id);
        List<int[]> cells = cellsWithin(reach);
        if (cells.isEmpty()) return;
        Villages.Age age = Villages.ageOf(id);
        int cursor = CURSOR.getOrDefault(id, 0);
        int done = 0;
        for (int looked = 0; looked < 160 && done < 24; looked++) {
            int[] c = cells.get(Math.floorMod(cursor++, cells.size()));
            if (work(level, v, c[0], c[1], age)) done++;
        }
        CURSOR.put(id, cursor);
    }

    /** Every square, street and avenue cell of the plan within this reach, nearest first. */
    private static List<int[]> cellsWithin(int reach) {
        return CELLS.computeIfAbsent(reach, r -> {
            List<int[]> out = new ArrayList<>();
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    boolean square = TownPlan.isSquare(x, z) && Math.max(Math.abs(x), Math.abs(z)) > 4;  // not the camp
                    if (square || TownPlan.isStreet(x, z)) out.add(new int[]{ x, z });
                }
            }
            out.sort(Comparator.comparingInt(c -> Math.max(Math.abs(c[0]), Math.abs(c[1]))));
            return out;
        });
    }

    /** Wear, pave or light one cell. Returns true if anything changed. */
    private static boolean work(ServerLevel level, Villages.Village v, int dx, int dz, Villages.Age age) {
        int x = v.centre().getX() + dx, z = v.centre().getZ() + dz;
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos top = new BlockPos(x, y - 1, z);
        BlockState ground = level.getBlockState(top);
        BlockState above = level.getBlockState(top.above());
        // Not up a hill or down a hole from the heart: those are somebody's to sort out by hand.
        if (Math.abs(top.getY() + 1 - v.centre().getY()) > 10) return false;
        boolean square = TownPlan.isSquare(dx, dz);
        boolean avenue = !square && (Math.abs(dx) <= TownPlan.AVENUE || Math.abs(dz) <= TownPlan.AVENUE
            || ring(dx, dz));
        boolean stoneAge = age.ordinal() >= Villages.Age.STONE.ordinal();
        boolean ironAge = age.ordinal() >= Villages.Age.IRON.ordinal();
        // A lamp post along the avenues and round the ring street.
        if (ironAge && avenue && lampSpot(dx, dz) && above.isAir() && ground.isSolid()
                && !level.getBlockState(top.above(2)).isSolid()) {
            // The light first: no light, no post (and no logs spent on one).
            boolean lantern = take(level, v, s -> s.is(Items.LANTERN), 1);
            if (!lantern && !take(level, v, s -> s.is(Items.TORCH), 1)) return false;
            if (!take(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS), 2)) {
                give(level, v, new ItemStack(lantern ? Items.LANTERN : Items.TORCH));
                return false;
            }
            level.setBlockAndUpdate(top.above(), Blocks.SPRUCE_FENCE.defaultBlockState());
            level.setBlockAndUpdate(top.above(2), Blocks.SPRUCE_FENCE.defaultBlockState());
            level.setBlockAndUpdate(top.above(3), lantern ? Blocks.LANTERN.defaultBlockState() : Blocks.TORCH.defaultBlockState());
            return true;
        }
        if (!earth(ground) && !(ground.is(Blocks.DIRT_PATH) && (square ? stoneAge : avenue && ironAge))) return false;
        if (!above.isAir() && !(above.canBeReplaced() && above.getFluidState().isEmpty())) return false;
        BlockState paving = null;
        if (square && stoneAge) {
            Predicate<ItemStack> stone = ironAge ? s -> s.is(Items.STONE_BRICKS) : s -> s.is(Items.COBBLESTONE);
            if (take(level, v, stone, 1)) paving = ironAge ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            else if (ironAge && take(level, v, s -> s.is(Items.COBBLESTONE), 1)) paving = Blocks.COBBLESTONE.defaultBlockState();
        } else if (avenue && ironAge) {
            if (take(level, v, s -> s.is(Items.COBBLESTONE), 1)) paving = Blocks.COBBLESTONE.defaultBlockState();
        }
        if (paving == null) {
            if (ground.is(Blocks.DIRT_PATH)) return false;
            paving = Blocks.DIRT_PATH.defaultBlockState();
        }
        if (!above.isAir()) level.removeBlock(top.above(), false);   // the grass and flowers in the way
        level.setBlockAndUpdate(top, paving);
        return true;
    }

    private static boolean ring(int dx, int dz) {
        int u = Math.max(Math.abs(dx), Math.abs(dz));
        return u >= TownPlan.RING && u < TownPlan.RING + TownPlan.STREET;
    }

    /** Lamps stand at the street's edge, every few blocks along it. */
    private static boolean lampSpot(int dx, int dz) {
        int ax = Math.abs(dx), az = Math.abs(dz);
        if (ax == TownPlan.AVENUE && az > TownPlan.RING + TownPlan.STREET && az % LAMP_EVERY == 0) return true;
        if (az == TownPlan.AVENUE && ax > TownPlan.RING + TownPlan.STREET && ax % LAMP_EVERY == 0) return true;
        int outer = TownPlan.RING + TownPlan.STREET - 1;
        if (ax == outer && az <= outer && az > TownPlan.AVENUE + 1 && az % LAMP_EVERY == 0) return true;
        return az == outer && ax <= outer && ax > TownPlan.AVENUE + 1 && ax % LAMP_EVERY == 0;
    }

    /** Ground feet wear into a path: grass and bare earth. */
    private static boolean earth(BlockState st) {
        return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL)
            || st.is(Blocks.MYCELIUM) || st.is(Blocks.ROOTED_DIRT);
    }

    /** Put a thing back in the village's stores (the first chest with room). */
    static void give(ServerLevel level, Villages.Village v, ItemStack stack) {
        boolean before = ZoneChests.askAs(true);
        try {
            for (ZoneChests.Found f : ZoneChests.around(level, v.centre(), Villages.storesRadius(v.id()), 32)) {
                if (stack.isEmpty()) return;
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                net.minecraft.world.Container c = f.container();
                for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
                    if (c.getItem(i).isEmpty()) {
                        c.setItem(i, stack.copy());
                        stack.setCount(0);
                    }
                }
                c.setChanged();
            }
        } finally {
            ZoneChests.askAs(before);
        }
    }

    /** Take so many of a thing out of the village's stores; all or nothing. */
    static boolean take(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int n) {
        boolean before = ZoneChests.askAs(true);
        try {
            List<ZoneChests.Found> stores = ZoneChests.around(level, v.centre(), Villages.storesRadius(v.id()), 32);
            int have = 0;
            for (ZoneChests.Found f : stores) {
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                net.minecraft.world.Container c = f.container();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack st = c.getItem(i);
                    if (!st.isEmpty() && what.test(st)) have += st.getCount();
                }
                if (have >= n) break;
            }
            if (have < n) return false;
            int left = n;
            for (ZoneChests.Found f : stores) {
                if (left <= 0) break;
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                net.minecraft.world.Container c = f.container();
                for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                    ItemStack st = c.getItem(i);
                    if (st.isEmpty() || !what.test(st)) continue;
                    int k = Math.min(left, st.getCount());
                    st.shrink(k);
                    if (st.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                    left -= k;
                }
                c.setChanged();
            }
            return true;
        } finally {
            ZoneChests.askAs(before);
        }
    }
}
