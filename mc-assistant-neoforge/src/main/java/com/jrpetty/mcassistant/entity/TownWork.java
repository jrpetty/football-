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
        golem(level, v);
        Weather.rods(level, v);                     // [wf] a lightning rod on each tall roof, of the stores' copper
        DoorWays.tick(level, v);                    // a way in a folk can walk at every door
        MineSafety.tick(level, v);                  // [mine-safety] the mine's stair heads fenced, the sign up
        TownLook.tick(level, v);                    // [batchE] the trees, benches, allotments, orchard, mill, bakery and inn
        Store.tick(level, v);                       // [econ-store] the shop's staff, its stock book and its deliveries
        int reach = Villages.townReach(id);
        List<int[]> cells = cellsWithin(reach);
        if (cells.isEmpty()) return;
        Villages.Age age = Villages.ageOf(id);
        int cursor = CURSOR.getOrDefault(id, 0);
        int done = 0;
        // A town sitting on a mountain of cobblestone paves its streets faster.
        int budget = age.ordinal() >= Villages.Age.STONE.ordinal()
            && Market.stock(level, id, s -> s.is(net.minecraft.world.item.Items.COBBLESTONE)) > 512 ? 64 : 24;
        for (int looked = 0; looked < 400 && done < budget; looked++) {
            int[] c = cells.get(Math.floorMod(cursor, cells.size()));
            int r = work(level, v, c[0], c[1], age);
            if (r < 0) break;                       // a hand is on its way to it: the work waits there
            cursor++;
            if (r > 0) done++;
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

    /** Wear, pave or light one cell, by hand (TownJobs). 1 if it changed, 0 if nothing to do, -1 if it waits for a hand. */
    private static int work(ServerLevel level, Villages.Village v, int dx, int dz, Villages.Age age) {
        int x = v.centre().getX() + dx, z = v.centre().getZ() + dz;
        if (!level.hasChunk(x >> 4, z >> 4)) return 0;
        // The streets stop at the farmland: the town grows round its fields, never through them.
        if (Villages.onFarmland(v.id(), dx, dz, 0, 0)) return 0;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos top = new BlockPos(x, y - 1, z);
        BlockState ground = level.getBlockState(top);
        BlockState above = level.getBlockState(top.above());
        // Not up a hill or down a hole from the heart: those are somebody's to sort out by hand.
        if (Math.abs(top.getY() + 1 - v.centre().getY()) > 10) return 0;
        boolean square = TownPlan.isSquare(dx, dz);
        boolean avenue = !square && (Math.abs(dx) <= TownPlan.AVENUE || Math.abs(dz) <= TownPlan.AVENUE
            || ring(dx, dz));
        boolean stoneAge = age.ordinal() >= Villages.Age.STONE.ordinal();
        boolean ironAge = age.ordinal() >= Villages.Age.IRON.ordinal();
        // A lamp post along the avenues and round the ring street.
        if (ironAge && avenue && lampSpot(dx, dz) && above.isAir() && ground.isSolid()
                && !level.getBlockState(top.above(2)).isSolid()) {
            // The light first: no light, no post (and no logs spent on one). A lantern if the smith has
            // made one (or left the nuggets for one), a torch if not (Masonry).
            if (!Masonry.canLight(level, v)) return 0;
            if (!TownJobs.atWork(level, v, "streets", top.above(), "putting up a lamp post")) return -1;
            net.minecraft.world.level.block.Block light = Masonry.light(level, v);
            if (light == null) return 0;
            if (!take(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS), 2)) {
                Masonry.unlight(level, v, light);
                return 0;
            }
            level.setBlockAndUpdate(top.above(), Blocks.SPRUCE_FENCE.defaultBlockState());
            level.setBlockAndUpdate(top.above(2), Blocks.SPRUCE_FENCE.defaultBlockState());
            level.setBlockAndUpdate(top.above(3), light.defaultBlockState());
            return 1;
        }
        // Before the Iron Age's lamp posts: a torch on a fence post at the same spots, from the
        // first days, so the streets are not dark enough for monsters to come up in the middle
        // of the town ("clear 3 zombies from the fields" was on every board). A torch is made
        // here from the stores' coal and a stick's worth of wood if there is none put by.
        if (!ironAge && avenue && lampSpot(dx, dz) && above.isAir() && ground.isSolid()
                && !level.getBlockState(top.above(2)).isSolid()) {
            if (Market.stock(level, v.id(), s -> s.is(Items.TORCH) || s.is(Items.COAL) || s.is(Items.CHARCOAL)) == 0) return 0;
            if (!TownJobs.atWork(level, v, "streets", top.above(), "putting up a street light")) return -1;
            if (!take(level, v, s -> s.is(Items.TORCH), 1)) {
                if (!take(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1)) return 0;
                if (!take(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 1)
                        && !take(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS), 1)) {
                    give(level, v, new ItemStack(Items.COAL));
                    return 0;
                }
                give(level, v, new ItemStack(Items.TORCH, 3));               // four made, one used
            }
            if (!take(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 1)
                    && !take(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS), 1)) {
                give(level, v, new ItemStack(Items.TORCH));
                return 0;
            }
            level.setBlockAndUpdate(top.above(), Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(top.above(2), Blocks.TORCH.defaultBlockState());
            return 1;
        }
        if (!earth(ground) && !(ground.is(Blocks.DIRT_PATH) && (square ? stoneAge : avenue && ironAge))) return 0;
        if (!above.isAir() && !(above.canBeReplaced() && above.getFluidState().isEmpty())) return 0;
        BlockState paving = null;
        if (square && stoneAge) {
            Predicate<ItemStack> stone = ironAge ? s -> s.is(Items.STONE_BRICKS) : s -> s.is(Items.COBBLESTONE);
            if (take(level, v, stone, 1)) paving = ironAge ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            else if (ironAge && take(level, v, s -> s.is(Items.COBBLESTONE), 1)) paving = Blocks.COBBLESTONE.defaultBlockState();
        } else if (avenue && ironAge) {
            if (take(level, v, s -> s.is(Items.COBBLESTONE), 1)) paving = Blocks.COBBLESTONE.defaultBlockState();
        }
        if (paving == null) {
            if (ground.is(Blocks.DIRT_PATH)) return 0;
            paving = Blocks.DIRT_PATH.defaultBlockState();
        }
        if (!TownJobs.atWork(level, v, "streets", top, paving.is(Blocks.DIRT_PATH) ? "laying the streets" : "paving the streets")) {
            if (!paving.is(Blocks.DIRT_PATH)) give(level, v, new ItemStack(paving.getBlock().asItem()));   // the stone back, till a hand is there
            return -1;
        }
        if (!above.isAir()) level.removeBlock(top.above(), false);   // the grass and flowers in the way
        level.setBlockAndUpdate(top, paving);
        return 1;
    }

    /**
     * From the Iron Age a village keeps an iron golem, as a vanilla village does: one made the day
     * the age comes, and another a few days after one is lost. It walks the town and fights what
     * comes into it; the folk are no monsters to it.
     */
    static boolean golem(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.ageOf(id).ordinal() < Villages.Age.IRON.ordinal()) return false;
        BlockPos c = v.centre();
        if (!level.isLoaded(c)) return false;
        net.minecraft.world.phys.AABB town = new net.minecraft.world.phys.AABB(c).inflate(64, 24, 64);
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.animal.IronGolem.class, town,
                net.minecraft.world.entity.LivingEntity::isAlive).isEmpty()) return false;
        long day = level.getDayTime() / 24000L;
        String last = com.jrpetty.mcassistant.village.Ledger.note(id, "golem");
        if (last != null) {
            try {
                if (day - Long.parseLong(last) < 3) return false;              // a new one takes a few days
            } catch (NumberFormatException ignored) { }
        }
        BlockPos at = Trades.floorSpot(level, c, 8);
        if (at == null) return false;
        // A golem is made, not conjured: four blocks of iron and a carved pumpkin out of the stores
        // (thirty-six ingots will do for the iron, a plain pumpkin carved on the spot for the head).
        boolean blocks = Market.stock(level, id, s -> s.is(Items.IRON_BLOCK)) >= 4;
        boolean ingots = Market.stock(level, id, s -> s.is(Items.IRON_INGOT)) >= 36 + 16;   // and some left over for the age
        boolean head = Market.stock(level, id, s -> s.is(Items.CARVED_PUMPKIN) || s.is(Items.PUMPKIN)) >= 1;
        if (!head || !blocks && !ingots) return false;
        if (!TownJobs.atWork(level, v, "golem", at, "building an iron golem")) return false;
        if (blocks ? !take(level, v, s -> s.is(Items.IRON_BLOCK), 4) : !take(level, v, s -> s.is(Items.IRON_INGOT), 36)) return false;
        if (!take(level, v, s -> s.is(Items.CARVED_PUMPKIN) || s.is(Items.PUMPKIN), 1)) {
            give(level, v, blocks ? new ItemStack(Items.IRON_BLOCK, 4) : new ItemStack(Items.IRON_INGOT, 36));
            return false;
        }
        net.minecraft.world.entity.animal.IronGolem g = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(level);
        if (g == null) return false;
        g.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
        g.setPersistenceRequired();
        if (!level.addFreshEntity(g)) return false;
        com.jrpetty.mcassistant.village.Ledger.note(id, "golem", Long.toString(day));
        Villages.tell(id, day, last == null ? "an iron golem was raised to keep the town" : "a new iron golem was raised in place of the one lost");
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

    /** Put a thing back in the village's stores: the Village Storehouse, else the chests with room —
     *  onto the part stacks of the same in all of them first, then empty slots (Stacking). */
    static void give(ServerLevel level, Villages.Village v, ItemStack stack) {
        if (stack.isEmpty()) return;
        com.jrpetty.mcassistant.block.StorehouseBlockEntity store = Storehouses.storeFor(level, v.id());
        List<net.minecraft.world.Container> boxes = new ArrayList<>();
        if (store != null) {
            // The storehouse first, and the stores round it: never a chest out on somebody's plot.
            boxes.add(store);
            for (BlockPos p : Villages.storeChests(level, v.id())) {
                if (level.getBlockEntity(p) instanceof net.minecraft.world.Container c && c != store) boxes.add(c);
            }
        } else {
            java.util.Set<Long> inUse = VillageFolkEntity.chestsInUse(v.id());
            boolean before = ZoneChests.askAs(true);
            try {
                for (ZoneChests.Found f : ZoneChests.around(level, v.centre(), Villages.storesRadius(v.id()), 32)) {
                    if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                    if (inUse.contains(f.pos().asLong())) continue;           // a worker's own, not the stores'
                    boxes.add(f.container());
                }
            } finally {
                ZoneChests.askAs(before);
            }
        }
        int[] took = new int[boxes.size()];
        ItemStack left = Stacking.insert(boxes, stack, took);
        if (store != null && took[0] > 0) Storekeeping.bookIn(level, v.id(), null, stack, took[0], false);
        stack.setCount(left.getCount());
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
                    Economy.storesOut(v.id(), st, k);                           // a maker's work, item by item
                    if (f.blockEntity() instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity) {
                        Storekeeping.bookOut(level, v.id(), null, st, k, null);   // the town's works, in its books
                    }
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
