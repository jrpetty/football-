package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Couriers;
import com.jrpetty.mcassistant.entity.Stacking;
import com.jrpetty.mcassistant.entity.Storehouses;
import com.jrpetty.mcassistant.entity.Storekeeping;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The Village Storehouse kept (entity/Stacking, Storekeeping, Couriers): goods put away stack the way
 * a person stacks them, onto the part stacks of the same wherever they are before an empty slot; a
 * worker keeps one production chest and brings it along when its plot moves on; the storehouse runs
 * the couriers — a full production chest goes on its run list, a courier takes the run, carries the
 * goods in merged onto the stacks there and reports back, and the run is in the books; with nothing
 * to run, a courier waits at the storehouse door; the storekeeper's tidy compacts what is scattered;
 * and a request is booked as served at the counter when the storekeeper is on duty, self-served when
 * it is not.
 *
 * <p>Each on its own ground, far from the others, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class StorehouseGameTests {

    private static final String EMPTY = "empty";

    /** A Village Storehouse (twenty-seven units, the door to the south) this far from the heart: its goods. */
    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
                level.setBlock(origin.offset(x, y, z), unit, 3);
            }
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands: " + level.getBlockState(door));
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    /** A marked chest of the village's this far from the heart, with these in these slots (slot, stack, slot, stack...). */
    private static Container chest(ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos p = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, p);
        return (Container) level.getBlockEntity(p);
    }

    /** No old chests about (the founders' chest, any other store chest): a courier with nothing to do has nothing to clear. */
    private static void noOldChests(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    private static int count(Container c, Item it) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    private static int stacks(Container c, Item it) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n++;
        return n;
    }

    private static int carried(VillageFolkEntity f, Item it) {
        return f.countCarried(s -> s.is(it));
    }

    /**
     * Set the clock to a time from which this folk is at its work for the next {@code span} ticks: on
     * shift, not on its break (an hour of its own, from its id), and past the morning assembly. A
     * fixed hour would have caught one folk in three on its break, and its work waits for that.
     */
    private static boolean atWorkFor(ServerLevel level, VillageFolkEntity f, long span) {
        for (long t = 1500; t + span <= 12000; t += 250) {
            boolean ok = true;
            for (long k = t; k <= t + span && ok; k += 200) {
                level.setDayTime(k);
                ok = !f.offWorkNow();
            }
            if (ok) {
                level.setDayTime(t);
                return true;
            }
        }
        return false;
    }

    // ============================================================ the one way in

    /**
     * The shared insert: onto a part stack of the same further along before an empty slot; across
     * containers, onto the part stacks in all of them (in order) before an empty slot in any; never
     * more in a slot than it holds (a lot of a hundred and fifty is three stacks, not sixty-four and
     * the rest lost); and a marked or named thing never stacks with a plain one. The storehouse's own
     * insert does the same.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "sh_st01_stacking")
    public static void st01_stacking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 160000, z = 40000;
        Kit.hold(level, x, z, 16);
        Kit.prepare(level, x, z, 16);
        BlockPos heart = Kit.surface(level, x, z);
        Container a = chest(level, heart, 0, 0);
        Container b = chest(level, heart, 3, 0);
        // One container: the part stack at slot 5, not the empty slot 1.
        a.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        a.setItem(5, new ItemStack(Items.COBBLESTONE, 10));
        ItemStack left = Stacking.insert(a, new ItemStack(Items.COBBLESTONE, 20));
        Kit.log("st01 into one chest: slot 1 " + a.getItem(1) + ", slot 5 " + a.getItem(5));
        helper.assertTrue(left.isEmpty() && a.getItem(1).isEmpty() && a.getItem(5).getCount() == 30,
            "onto the part stack further along, not the first empty slot: slot1 " + a.getItem(1) + ", slot5 " + a.getItem(5));
        // Across containers: b's part stack of wheat before a's empty slots.
        b.setItem(3, new ItemStack(Items.WHEAT, 30));
        left = Stacking.insert(List.of(a, b), new ItemStack(Items.WHEAT, 20));
        helper.assertTrue(left.isEmpty() && count(a, Items.WHEAT) == 0 && b.getItem(3).getCount() == 50,
            "across containers, onto the part stack in the second before an empty slot in the first: a " + count(a, Items.WHEAT)
                + ", b " + b.getItem(3));
        // Then the rest of a bigger lot: b's stack topped up to 64, the rest in a's first empty slot.
        left = Stacking.insert(List.of(a, b), new ItemStack(Items.WHEAT, 40));
        helper.assertTrue(left.isEmpty() && b.getItem(3).getCount() == 64 && a.getItem(1).is(Items.WHEAT) && a.getItem(1).getCount() == 26,
            "topped up to the full, then the first empty slot in order: b " + b.getItem(3) + ", a slot1 " + a.getItem(1));
        // Never more in a slot than it holds.
        Container c = chest(level, heart, 6, 0);
        left = Stacking.insert(c, new ItemStack(Items.OAK_LOG, 150));
        helper.assertTrue(left.isEmpty() && count(c, Items.OAK_LOG) == 150 && stacks(c, Items.OAK_LOG) == 3 && c.getItem(0).getCount() == 64,
            "a hundred and fifty logs are three stacks, none lost: " + count(c, Items.OAK_LOG) + " in " + stacks(c, Items.OAK_LOG));
        // A named stick is its own: it never joins the plain ones.
        c.setItem(5, new ItemStack(Items.STICK, 10));
        ItemStack marked = new ItemStack(Items.STICK, 3);
        marked.set(DataComponents.CUSTOM_NAME, Component.literal("Holt's mark"));
        Stacking.insert(c, marked);
        helper.assertTrue(c.getItem(5).getCount() == 10 && stacks(c, Items.STICK) == 2, "a marked thing keeps its own stack");
        // The storehouse's own insert: onto its part stack at slot 40 before its empty slot 0.
        StorehouseBlockEntity store = storehouse(helper, level, heart, -6, -6);
        store.setItem(40, new ItemStack(Items.BREAD, 20));
        store.insert(new ItemStack(Items.BREAD, 30));
        helper.assertTrue(store.getItem(0).isEmpty() && store.getItem(40).getCount() == 50, "the storehouse merges too: " + store.getItem(40));
        helper.succeed();
    }

    // ============================================================ one chest a worker

    /**
     * A miner whose plot moves on keeps one production chest: the old one is not left standing with
     * its goods in it while another goes down. It is in use while the miner brings it along (no
     * courier may clear it away); the miner walks back to it, takes the goods and the chest up, and
     * sets it down at the edge of the new plot with the goods back in it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "sh_st02_miner_moves_its_chest")
    public static void st02_miner_moves_its_chest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 161500, z = 40000;
        Kit.hold(level, x, z, 64);
        Kit.prepare(level, x, z, 64);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity miner = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(miner != null, "a village");
        UUID village = miner.ownerId();
        VillageFolkEntity other = VillageFolkSpawnerBlock.raise(level, heart.south(3), 0.0F);
        helper.assertTrue(other != null && village.equals(other.ownerId()), "and a neighbour");
        BlockPos siteA = Kit.surface(level, heart.getX() + 34, heart.getZ());
        miner.setJob(StationTask.MINE);
        miner.assignPlot(WorkZone.around(siteA, 6, WorkZone.DEFAULT_DEPTH), "The Old Mine");
        miner.moveTo(siteA.getX() + 0.5, siteA.getY(), siteA.getZ() + 0.5, 0.0F, 0.0F);
        BlockPos old = miner.productionChestForTests();
        helper.assertTrue(old != null && level.getBlockState(old).is(Blocks.CHEST), "the miner's production chest at its old plot");
        Container box = (Container) level.getBlockEntity(old);
        box.setItem(0, new ItemStack(Items.COBBLESTONE, 20));
        box.setItem(4, new ItemStack(Items.RAW_IRON, 6));
        // The plot moves on: twenty-odd blocks round.
        BlockPos siteB = Kit.surface(level, heart.getX() + 34, heart.getZ() + 26);
        miner.assignPlot(WorkZone.around(siteB, 6, WorkZone.DEFAULT_DEPTH), "The New Mine");
        BlockPos none = miner.productionChestForTests();
        Kit.log("st02 the plot moved: old chest " + old.toShortString() + ", a new one now? " + none + "; bringing along " + miner.oldProductionChest());
        helper.assertTrue(none == null && old.equals(miner.oldProductionChest()), "no second chest goes down: the old one is coming along");
        helper.assertTrue(VillageFolkEntity.chestInUseByAnother(village, old, other), "and it is in use: nobody else may clear it away");
        // What the miner and the stores hold already (the founders' chest has cobblestone of its own).
        int cobble0 = carried(miner, Items.COBBLESTONE), iron0 = carried(miner, Items.RAW_IRON);
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container s) { cobble0 += count(s, Items.COBBLESTONE); iron0 += count(s, Items.RAW_IRON); }
        }
        final int cobbleBefore = cobble0, ironBefore = iron0;
        miner.clearQueue();
        miner.moveTo(old.getX() + 1.5, old.getY(), old.getZ() + 0.5, 0.0F, 0.0F);
        helper.assertTrue(atWorkFor(level, miner, 2400), "a time of day the miner is at work");
        helper.assertTrue(miner.carryTheOldChestForTests(), "the miner goes back for its chest");
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Villages.noteAttempt(village, level.getGameTime());
            if (level.getBlockState(old).is(Blocks.CHEST)) {
                if (t % 200 == 0) Kit.log("st02 @" + t + " the old chest still stands — " + miner.debugLine());
                if (t > 2200) helper.fail("the miner never took its old chest up — " + miner.debugLine());
                return;
            }
            BlockPos now = miner.productionChest();
            if (now == null) {
                miner.carryTheOldChestForTests();
                if (t > 2200) helper.fail("the old chest is gone but none was set down at the new plot — " + miner.debugLine());
                return;
            }
            Container fresh = (Container) level.getBlockEntity(now);
            int cobble = count(fresh, Items.COBBLESTONE) + carried(miner, Items.COBBLESTONE) - cobbleBefore;
            int iron = count(fresh, Items.RAW_IRON) + carried(miner, Items.RAW_IRON) - ironBefore;
            for (BlockPos p : Villages.storeChests(level, village)) {
                if (level.getBlockEntity(p) instanceof Container s) { cobble += count(s, Items.COBBLESTONE); iron += count(s, Items.RAW_IRON); }
            }
            int near = Math.max(Math.abs(now.getX() - siteB.getX()), Math.abs(now.getZ() - siteB.getZ()));
            Kit.log("st02 @" + t + " the chest is at " + now.toShortString() + " (" + near + " from the new plot's middle); the goods: cobble "
                + cobble + ", raw iron " + iron + "; production chests " + VillageFolkEntity.productionChests(village).size());
            helper.assertTrue(near <= 8 && level.getBlockState(now).is(Blocks.CHEST), "set down at the edge of the new plot");
            helper.assertTrue(VillageFolkEntity.productionChests(village).size() == 1 && miner.oldProductionChest() == null,
                "and it is the miner's only production chest");
            helper.assertTrue(cobble >= 20 && iron >= 6, "with its goods: in the chest, the miner's pack or the stores");
            helper.succeed();
        });
    }

    // ============================================================ the storehouse runs the couriers

    /**
     * A farmer's production chest fills up: the storehouse puts it on its run list, a courier takes
     * the run, empties the chest of its harvest (the field's seed stays), carries it into the
     * storehouse merged onto the part stack there, and reports back; the run is in the storehouse's
     * books and the courier's day. The chest is never offered up as an old one to clear away.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "sh_st03_courier_run")
    public static void st03_courier_run(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 163000, z = 40000;
        Kit.hold(level, x, z, 64);
        Kit.prepare(level, x, z, 64);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity farmer = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(farmer != null, "a village");
        UUID village = farmer.ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        helper.assertTrue(Storehouses.stands(village), "the village knows its storehouse");
        noOldChests(level, village);                                         // (the founders' chest: an old chest to clear)
        store.setItem(7, new ItemStack(Items.WHEAT, 30));                    // a part stack, further along
        BlockPos site = Kit.surface(level, heart.getX() + 40, heart.getZ());
        farmer.setJob(StationTask.FARM);
        farmer.assignPlot(WorkZone.around(site, 4, WorkZone.DEFAULT_DEPTH), "Farm");
        farmer.moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.0F, 0.0F);
        BlockPos chest = farmer.productionChestForTests();
        helper.assertTrue(chest != null, "the farmer's production chest");
        Container box = (Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.WHEAT, 20));
        box.setItem(5, new ItemStack(Items.WHEAT, 20));
        box.setItem(9, new ItemStack(Items.WHEAT_SEEDS, 12));
        VillageFolkEntity courier = VillageFolkSpawnerBlock.raise(level, heart.south(3), 0.0F);
        helper.assertTrue(courier != null && village.equals(courier.ownerId()), "a courier for the village");
        courier.setJob(StationTask.HAUL);
        helper.assertTrue(Couriers.employed(courier), "the courier is the storehouse's");
        helper.assertTrue(atWorkFor(level, courier, 4000), "a time of day the courier is at work");
        List<String> runs = Couriers.refreshForTests(level, village);
        Kit.log("st03 the run list: " + runs);
        String at = chest.toShortString();
        helper.assertTrue(!runs.isEmpty() && runs.get(0).startsWith("CHEST:" + at), "the full chest is on the run list, first: " + runs);
        BlockPos offered = com.jrpetty.mcassistant.entity.Retiring.next(courier, level, village);
        if (offered != null) com.jrpetty.mcassistant.entity.Retiring.release(offered);
        helper.assertTrue(!chest.equals(offered), "and never offered up as an old chest to clear away");
        final boolean[] took = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Villages.noteAttempt(village, level.getGameTime());
            String run = Couriers.runOfForTests(courier);
            if (run != null && run.startsWith("CHEST:" + at)) took[0] = true;
            int wheat = count(store, Items.WHEAT);
            int[] books = Storekeeping.requestsForTests(level, village);
            int[] day = Couriers.staffForTests(courier);
            if (t % 400 == 0) {
                Kit.log("st03 @" + t + " storehouse wheat " + wheat + " in " + stacks(store, Items.WHEAT) + " stacks; the chest: wheat "
                    + count(box, Items.WHEAT) + ", seed " + count(box, Items.WHEAT_SEEDS) + "; run " + run + "; books " + java.util.Arrays.toString(books)
                    + " — " + courier.debugLine());
            }
            if (took[0] && wheat >= 70 && books[2] >= 1 && day[0] >= 1) {
                Kit.log("st03 the courier brought the harvest in by tick " + t + ": " + wheat + " wheat in " + stacks(store, Items.WHEAT)
                    + " stacks, slot 7 " + store.getItem(7) + "; books " + java.util.Arrays.toString(books) + ", the courier's day "
                    + java.util.Arrays.toString(day));
                helper.assertTrue(stacks(store, Items.WHEAT) == 2 && store.getItem(7).getCount() == 64,
                    "merged onto the part stack there: seventy wheat is two stacks, the old one topped up");
                helper.assertTrue(count(store, Items.WHEAT_SEEDS) <= 6 && level.getBlockState(chest).is(Blocks.CHEST),
                    "the field's seed is not carried in (the storehouse has only the courier's own six), and the chest stays: "
                        + count(store, Items.WHEAT_SEEDS));
                helper.assertTrue(Storekeeping.itemForTests(level, village, new ItemStack(Items.WHEAT))[0] >= 40, "the wheat is booked in");
                helper.succeed();
            } else if (t >= 3800) {
                helper.fail("the courier did not bring the chest in: took " + took[0] + ", wheat " + wheat + ", books "
                    + java.util.Arrays.toString(books) + " — " + courier.debugLine());
            }
        });
    }

    /**
     * A courier with no run waits at the storehouse: it goes to the door and stands there for the
     * next one (it is not lent out to the woods or the building).
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "sh_st04_courier_waits")
    public static void st04_courier_waits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 164500, z = 40000;
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity courier = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(courier != null, "a village");
        UUID village = courier.ownerId();
        storehouse(helper, level, heart, 8, 8);
        noOldChests(level, village);
        courier.setJob(StationTask.HAUL);
        BlockPos start = Kit.surface(level, heart.getX() - 14, heart.getZ() - 10);
        courier.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0.0F, 0.0F);
        BlockPos spot = Storehouses.standingSpot(level, village);
        helper.assertTrue(spot != null && Couriers.employed(courier), "a storehouse to work out of: " + spot);
        helper.assertTrue(atWorkFor(level, courier, 1600), "a time of day the courier is at work");
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Villages.noteAttempt(village, level.getGameTime());
            String doing = Couriers.doing(courier);
            double d = Math.sqrt(courier.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5));
            if (t % 200 == 0) Kit.log("st04 @" + t + " " + String.format("%.1f", d) + " from the door, " + doing + " — " + courier.debugLine());
            if (d <= 4.0 && doing != null && doing.contains("waiting at the storehouse") && Couriers.runOfForTests(courier) == null) {
                Kit.log("st04 the courier waits at the storehouse by tick " + t + ": " + doing);
                helper.succeed();
            } else if (t >= 1500) {
                helper.fail("the courier did not wait at the storehouse: " + String.format("%.1f", d) + " away, " + doing + " — " + courier.debugLine());
            }
        });
    }

    // ============================================================ the storekeeper

    /**
     * The storekeeper's tidy: the storehouse's scattered part stacks made whole and put in order by
     * kind — food, then timber, then stone — and the part stacks of a store chest beside it topped up
     * from each other; the books keep the slots before and after and the stacks merged.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "sh_st05_tidy")
    public static void st05_tidy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 166000, z = 40000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID village = keeper.ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        noOldChests(level, village);
        keeper.setJob(StationTask.STORE);
        for (int i = 0; i < 5; i++) store.setItem(i * 3, new ItemStack(Items.COBBLESTONE, 10));
        for (int i = 0; i < 3; i++) store.setItem(i * 3 + 1, new ItemStack(Items.BREAD, 5));
        store.setItem(2, new ItemStack(Items.OAK_LOG, 20));
        store.setItem(20, new ItemStack(Items.OAK_LOG, 20));
        Container side = chest(level, heart, -4, 0);
        side.setItem(0, new ItemStack(Items.TORCH, 10));
        side.setItem(5, new ItemStack(Items.TORCH, 10));
        side.setItem(8, new ItemStack(Items.TORCH, 10));
        int before = store.used();
        helper.assertTrue(before == 10, "ten part stacks scattered through the storehouse: " + before);
        helper.assertTrue(keeper.tidyForTests(), "the storekeeper tidies");
        int[] tidy = Storekeeping.lastTidyForTests(village);
        Kit.log("st05 tidied: " + store.used() + " slots used (" + store.getItem(0) + ", " + store.getItem(1) + ", " + store.getItem(2)
            + "); the chest's torches " + stacks(side, Items.TORCH) + " stack(s); the books " + java.util.Arrays.toString(tidy));
        helper.assertTrue(store.used() == 3 && store.getItem(0).is(Items.BREAD) && store.getItem(0).getCount() == 15
            && store.getItem(1).is(Items.OAK_LOG) && store.getItem(1).getCount() == 40
            && store.getItem(2).is(Items.COBBLESTONE) && store.getItem(2).getCount() == 50,
            "like with like, whole, and in order by kind: food, timber, stone");
        helper.assertTrue(stacks(side, Items.TORCH) == 1 && count(side, Items.TORCH) == 30, "the store chest beside it compacted too");
        helper.assertTrue(tidy != null && tidy[0] == 10 && tidy[1] == 3 && tidy[2] == 9, "the books: ten slots to three, nine stacks merged");
        helper.succeed();
    }

    /**
     * A request at the storehouse is booked as served by the storekeeper when it is on duty at the
     * counter, and as self-served when it is not (away from the counter): the village never waits on it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "sh_st06_counter")
    public static void st06_counter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 167500, z = 40000;
        Kit.hold(level, x, z, 64);
        Kit.prepare(level, x, z, 64);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID village = keeper.ownerId();
        VillageFolkEntity miner = VillageFolkSpawnerBlock.raise(level, heart.south(3), 0.0F);
        helper.assertTrue(miner != null && village.equals(miner.ownerId()), "a customer");
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        noOldChests(level, village);
        keeper.setJob(StationTask.STORE);
        miner.setJob(StationTask.MINE);
        store.insert(new ItemStack(Items.TORCH, 40));
        helper.runAtTickTime(5, () -> {
            helper.assertTrue(atWorkFor(level, keeper, 0), "a time of day the storekeeper is at work");
            BlockPos spot = Storehouses.standingSpot(level, village);
            keeper.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.0F, 0.0F);
            helper.assertTrue(Storekeeping.onDuty(level, village) == keeper, "the storekeeper is on duty at the counter");
            int got = miner.drawFrom(heart, s -> s.is(Items.TORCH), 16, 48);
            int[] books = Storekeeping.requestsForTests(level, village);
            Kit.log("st06 with the storekeeper at the counter: drew " + got + ", books " + java.util.Arrays.toString(books));
            helper.assertTrue(got == 16 && books[0] == 1 && books[1] == 0, "served by the storekeeper");
            // The storekeeper away from the counter: the miner serves itself.
            BlockPos away = Kit.surface(level, heart.getX() - 40, heart.getZ() - 40);
            keeper.moveTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5, 0.0F, 0.0F);
            helper.assertTrue(Storekeeping.onDuty(level, village) == null, "nobody at the counter");
            got = miner.drawFrom(heart, s -> s.is(Items.TORCH), 8, 48);
            books = Storekeeping.requestsForTests(level, village);
            int[] torches = Storekeeping.itemForTests(level, village, new ItemStack(Items.TORCH));
            Kit.log("st06 with nobody at the counter: drew " + got + ", books " + java.util.Arrays.toString(books) + ", torches in/out "
                + java.util.Arrays.toString(torches));
            helper.assertTrue(got == 8 && books[0] == 1 && books[1] == 1, "self-served, and the village did not wait");
            helper.assertTrue(torches[1] == 24, "twenty-four torches out, in the books");
            helper.succeed();
        });
    }
}
