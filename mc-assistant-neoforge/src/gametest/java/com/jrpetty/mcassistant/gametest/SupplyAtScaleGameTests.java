package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Couriers;
import com.jrpetty.mcassistant.entity.Storehouses;
import com.jrpetty.mcassistant.entity.Toolrack;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.goal.WithdrawGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A big village kept in tools and rations (entity/Toolrack, VillageFolkEntity.toolFromTheRack and
 * rationsAhead, Couriers): the storehouse keeps a rack of spare tools sized to the hands that wear
 * them out, made of the stores' own goods by whoever is at the stores (the stores go down by what went
 * into them, and nothing is made of nothing); a worker whose tool breaks takes a spare off the rack;
 * one whose pack is full banks its load first to make room for it; and a worker far out on its plot
 * with its rations running low has a courier bring them out.
 *
 * <p>Each on its own ground, far from the others (x 184000 and on, z 52000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SupplyAtScaleGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 52000;

    // ------------------------------------------------------------------ the ground

    /** A Village Storehouse (twenty-seven units, the door to the south) this far from the heart. */
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

    /** No chest about but the storehouse (the founders' chest goes): what is counted is what is in it. */
    private static void onlyTheStorehouse(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    /**
     * Set the clock to a time from which this folk is at its work for the next {@code span} ticks: on
     * shift, not on its break, and past the morning assembly.
     */
    private static boolean atWorkFor(ServerLevel level, VillageFolkEntity f, long span) {
        // Any day of the week: the day a test begins on may be the town's day of rest, or a festival's.
        long day0 = level.getDayTime() / 24000L * 24000L;
        java.util.Set<String> why = new java.util.TreeSet<>();
        for (int d = 0; d < 7; d++) {
            for (long t = day0 + d * 24000L + 1500; t + span <= day0 + d * 24000L + 12000; t += 250) {
                boolean ok = true;
                for (long k = t; k <= t + span && ok; k += 200) {
                    level.setDayTime(k);
                    ok = !f.offWorkNow();
                    if (!ok) why.add(f.offWorkWhy());
                }
                if (ok) {
                    level.setDayTime(t);
                    return true;
                }
            }
        }
        Kit.log("atWorkFor: " + f.getAssistantName() + " never at work in a week: " + why);
        return false;
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** Timber in half-planks: a log is eight, a plank two, a stick one — what a handle is reckoned in. */
    private static int halfPlanks(Container c) {
        return count(c, s -> s.is(ItemTags.LOGS)) * 8 + count(c, s -> s.is(ItemTags.PLANKS)) * 2 + count(c, s -> s.is(Items.STICK));
    }

    /** Empty the pack and the hands: what the test gives it is all it has. */
    private static void emptyHanded(VillageFolkEntity f) {
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) inv.set(i, ItemStack.EMPTY);
        f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    private static ItemStack named(Item it, String name) {
        ItemStack s = new ItemStack(it);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    private static boolean isNamed(ItemStack s, String name) {
        return !s.isEmpty() && s.has(DataComponents.CUSTOM_NAME) && s.getHoverName().getString().equals(name);
    }

    // ============================================================ a broken pick, and a spare off the rack

    /**
     * A miner whose pick has broken, with nothing to make another of (no stone in its pack, no stone
     * or wood in the stores), takes the spare pick off the storehouse's rack, and is at work with it
     * again inside a minute: the spare leaves the rack, and the miner's checklist no longer wants one.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "sa01_broken_pick_spare")
    public static void sa01_broken_pick_spare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 184000;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity miner = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(miner != null, "a village");
        UUID village = miner.ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, village);
        helper.assertTrue(Storehouses.stands(village), "the village knows its storehouse");
        store.setItem(4, named(Items.STONE_PICKAXE, "Rack Pick"));
        Kit.hill(level, x + 30, Z, 9, 8, 184L);
        BlockPos site = Kit.surface(level, x + 30, Z);
        miner.setJob(StationTask.MINE);
        miner.assignPlot(WorkZone.around(site, 6, WorkZone.DEFAULT_DEPTH), "The Pit");
        miner.moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.0F, 0.0F);
        // Its pick has broken: none in hand or pack, nothing to make one of. Bread for its rations.
        emptyHanded(miner);
        miner.insertItem(new ItemStack(Items.BREAD, 12));
        miner.insertItem(new ItemStack(Items.CRAFTING_TABLE));
        helper.assertTrue(miner.countCarried(s -> s.is(ItemTags.PICKAXES)) == 0, "no pick to its name");
        helper.assertTrue(Toolrack.onRack(level, village, Toolrack.Tool.PICKAXE) == 1, "one spare pick on the rack");
        helper.assertTrue(atWorkFor(level, miner, 2200), "a time of day the miner is at work");
        Kit.log("sa01 the miner's pick is gone; the rack holds " + Toolrack.onRack(level, village, Toolrack.Tool.PICKAXE)
            + " (wanted " + Toolrack.wanted(village, Toolrack.Tool.PICKAXE) + ") — " + miner.debugLine());
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Villages.noteAttempt(village, level.getGameTime());
            if (t % 20 != 0) return;
            boolean has = miner.countCarried(s -> isNamed(s, "Rack Pick")) > 0;
            int left = count(store, s -> s.is(ItemTags.PICKAXES));
            if (t % 200 == 0) Kit.log("sa01 @" + t + " the rack's pick in hand " + has + ", picks left on the rack " + left + " — " + miner.debugLine());
            if (has) {
                miner.recheckKit();
                Kit.log("sa01 the miner took the spare by tick " + t + "; the rack: " + left + "; missing " + miner.missingEssentials()
                    + "; handed out " + java.util.Arrays.toString(Toolrack.tally(village)));
                helper.assertTrue(left == 0, "the spare left the rack (no pick out of nothing): " + left);
                helper.assertTrue(!miner.missingEssentials().contains("a pickaxe"), "and the miner's checklist wants none: " + miner.missingEssentials());
                helper.succeed();
            } else if (t >= 2200) {
                helper.fail("the miner never took the spare off the rack: " + left + " left there — " + miner.debugLine());
            }
        });
    }

    // ============================================================ the rack restocked from the stores

    /**
     * Five miners want a rack of two spare picks. The storekeeper at its counter makes them of the
     * stores: three cobblestone and two sticks apiece, the sticks sawn from a log (what is left of the
     * log goes back as planks and sticks), so the stores go down by exactly what went into them. With
     * the rack full it makes nothing more; with no stone to spare in the Wood Age a wooden pick comes of
     * the timber instead; and in the Stone Age with no stone, nothing at all — nothing of nothing.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sa02_rack_restocked")
    public static void sa02_rack_restocked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 185500;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID village = keeper.ownerId();
        Villages.Village v = Villages.get(village);
        for (int i = 0; i < 5; i++) {
            VillageFolkEntity m = VillageFolkSpawnerBlock.raise(level, heart.south(2 + i), 0.0F);
            helper.assertTrue(m != null && village.equals(m.ownerId()), "miner " + i);
            m.setJob(StationTask.MINE);
        }
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, village);
        keeper.setJob(StationTask.STORE);
        store.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        store.setItem(1, new ItemStack(Items.COBBLESTONE, 40));
        store.setItem(2, new ItemStack(Items.OAK_LOG, 16));
        // All of it now, before the first tick: the folk's own agendas see to the rack as well, and would
        // have made the picks before the storekeeper was asked.
        {
            helper.assertTrue(atWorkFor(level, keeper, 0), "a time of day the storekeeper is at work");
            BlockPos spot = Storehouses.standingSpot(level, village);
            helper.assertTrue(spot != null, "a counter to stand at");
            keeper.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.0F, 0.0F);
            int want = Toolrack.wanted(village, Toolrack.Tool.PICKAXE);
            int cobble0 = count(store, s -> s.is(Items.COBBLESTONE)), wood0 = halfPlanks(store);
            Kit.log("sa02 five miners want " + want + " spare picks; the stores: cobblestone " + cobble0 + ", timber " + wood0 + " half-planks");
            helper.assertTrue(want == 2, "one spare pick for every four miners: " + want);
            String made = Toolrack.tend(keeper, level);
            int picks = Toolrack.onRack(level, village, Toolrack.Tool.PICKAXE);
            int stone = count(store, s -> s.is(ItemTags.PICKAXES) && s.getItem() == Items.STONE_PICKAXE);
            int cobble1 = count(store, s -> s.is(Items.COBBLESTONE)), wood1 = halfPlanks(store);
            Kit.log("sa02 the storekeeper made: " + made + "; picks on the rack " + picks + " (stone " + stone + "); cobblestone "
                + cobble0 + " -> " + cobble1 + ", timber " + wood0 + " -> " + wood1 + " half-planks (logs " + count(store, s -> s.is(ItemTags.LOGS))
                + ", planks " + count(store, s -> s.is(ItemTags.PLANKS)) + ", sticks " + count(store, s -> s.is(Items.STICK)) + ")");
            helper.assertTrue(made != null && picks == 2 && stone == 2, "two stone picks on the rack: " + made + ", " + picks);
            helper.assertTrue(cobble0 - cobble1 == 6, "three cobblestone a pick out of the stores: " + cobble0 + " -> " + cobble1);
            helper.assertTrue(wood0 - wood1 == 4, "two sticks a pick (a plank's worth), the rest of the log back: " + wood0 + " -> " + wood1);
            helper.assertTrue(Toolrack.tally(village)[0] == 2, "two made, in the tally: " + java.util.Arrays.toString(Toolrack.tally(village)));
            // The rack full: nothing more is made, and nothing taken.
            String again = Toolrack.restock(level, v, keeper, 2);
            helper.assertTrue(again == null && count(store, s -> s.is(Items.COBBLESTONE)) == cobble1 && halfPlanks(store) == wood1,
                "with the rack full, nothing more: " + again);
            // Four more miners (nine: three spares wanted) and no stone in the stores: in the Wood Age a
            // wooden pick, of three planks and two sticks.
            for (int i = 0; i < 4; i++) {
                VillageFolkEntity m = VillageFolkSpawnerBlock.raise(level, heart.south(8 + i), 0.0F);
                helper.assertTrue(m != null, "another miner");
                m.setJob(StationTask.MINE);
            }
            for (int i = 0; i < store.getContainerSize(); i++) if (store.getItem(i).is(Items.COBBLESTONE)) store.setItem(i, ItemStack.EMPTY);
            int wood2 = halfPlanks(store);
            String wooden = Toolrack.restock(level, v, keeper, 1);
            int wood3 = halfPlanks(store);
            int woodenPicks = count(store, s -> s.getItem() == Items.WOODEN_PICKAXE);
            Kit.log("sa02 no stone, the Wood Age: " + wooden + "; wooden picks " + woodenPicks + "; timber " + wood2 + " -> " + wood3);
            helper.assertTrue(wooden != null && woodenPicks == 1 && wood2 - wood3 == 8,
                "a wooden pick of the timber (three planks and two sticks): " + wooden + ", " + (wood2 - wood3) + " half-planks");
            // The Stone Age, no stone, and the rack a pick short again (thirteen miners: four wanted):
            // nothing of nothing.
            Villages.ageForTests(village, Villages.Age.STONE);
            for (int i = 0; i < 4; i++) {
                VillageFolkEntity m = VillageFolkSpawnerBlock.raise(level, heart.south(12 + i), 0.0F);
                if (m != null) m.setJob(StationTask.MINE);
            }
            int short4 = Toolrack.wanted(village, Toolrack.Tool.PICKAXE) - Toolrack.onRack(level, village, Toolrack.Tool.PICKAXE);
            int wood4 = halfPlanks(store);
            String none = Toolrack.restock(level, v, keeper, 2);
            Kit.log("sa02 the Stone Age with no stone, the rack " + short4 + " short: " + none + "; timber " + wood4 + " -> " + halfPlanks(store));
            helper.assertTrue(short4 > 0, "the rack is short of a pick: " + short4);
            helper.assertTrue(none == null && halfPlanks(store) == wood4, "in the Stone Age with no stone, no pick at all: " + none);
            helper.succeed();
        }
    }

    // ============================================================ rations out to a far worker

    /**
     * A woodcutter seventy blocks out, down to its last two loaves, asks the storehouse for rations; a
     * courier packs them at the storehouse and walks them out to it, and it has a few days' worth again
     * — out of the storehouse's own bread, which goes down by as much.
     */
    @GameTest(template = EMPTY, timeoutTicks = 5000, batch = "sa03_far_rations")
    public static void sa03_far_rations(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(3000);                                              // the morning, well before the midday meal
        int x = 187000;
        Kit.hold(level, x, Z, 96);
        Kit.prepare(level, x, Z, 96);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity courier = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(courier != null, "a village");
        UUID village = courier.ownerId();
        VillageFolkEntity cutter = VillageFolkSpawnerBlock.raise(level, heart.south(3), 0.0F);
        helper.assertTrue(cutter != null && village.equals(cutter.ownerId()), "a woodcutter");
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, village);
        store.setItem(3, new ItemStack(Items.BREAD, 64));
        courier.setJob(StationTask.HAUL);
        helper.assertTrue(Couriers.employed(courier), "the courier is the storehouse's");
        BlockPos site = Kit.surface(level, x + 70, Z);
        Kit.forest(level, x + 76, Z, 5, 6, 187L);                          // its trees, east of where it stands
        cutter.setJob(StationTask.WOOD);
        cutter.assignPlot(WorkZone.around(site, 8, WorkZone.DEFAULT_DEPTH), "East Wood");
        cutter.moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.0F, 0.0F);
        // Down to its last two loaves: nothing else in its pack to eat.
        Predicate<ItemStack> ration = WithdrawGoal.matcherFor("ration");
        cutter.removeMatching(s -> s.get(DataComponents.FOOD) != null, 64 * 27);
        cutter.insertItem(new ItemStack(Items.BREAD, 2));
        final int bread0 = count(store, s -> s.is(Items.BREAD));
        final int[] start = { 0 };
        helper.runAtTickTime(5, () -> {
            // (Three thousand six hundred ticks always fit round a folk's break between the assembly and dusk.)
            helper.assertTrue(atWorkFor(level, courier, 3600), "a time of day the courier is at work");
            start[0] = cutter.countCarried(ration);
            cutter.rationsAheadForTests();
            List<String> runs = Couriers.refreshForTests(level, village);
            String taken = Couriers.runOfForTests(courier);
            Kit.log("sa03 the woodcutter, " + (int) Math.sqrt(site.distSqr(heart)) + " blocks out with " + start[0] + " rations, asks; the run list: "
                + runs + "; the courier's run " + taken);
            helper.assertTrue(runs.stream().anyMatch(r -> r.startsWith("KIT:") && r.contains("rations out to " + cutter.displayNameCap()))
                    || taken != null && taken.startsWith("KIT"),
                "the rations are on the storehouse's run list (or already in the courier's hands): " + runs + ", " + taken);
        });
        // What came into its pack, counted as it comes (it eats as it works, so what it holds says less).
        final int[] came = { 0 }, last = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Villages.noteAttempt(village, level.getGameTime());
            int now = cutter.countCarried(ration);
            if (t >= 5 && last[0] >= 0 && now > last[0]) came[0] += now - last[0];
            last[0] = now;
            if (t < 10 || t % 20 != 0) return;
            int has = start[0] + came[0];
            int bread = count(store, s -> s.is(Items.BREAD));
            String run = Couriers.runOfForTests(courier);
            if (t % 400 == 0) Kit.log("sa03 @" + t + " the woodcutter has " + now + " rations (" + came[0] + " come in), the storehouse " + bread + " bread; run " + run
                + " — " + courier.debugLine());
            // The run carries what was asked for (four, here: a day's to a woodcutter out at its plot).
            if (has >= start[0] + 4) {
                Kit.log("sa03 rations out by tick " + t + ": " + came[0] + " came into the woodcutter's pack (it had " + start[0] + ", holds " + now + "), the storehouse's bread "
                    + bread0 + " -> " + bread + "; the courier's day " + java.util.Arrays.toString(Couriers.staffForTests(courier)));
                // (The storehouse's bread may stand higher than it did: a courier banks what it carries between runs.)
                helper.succeed();
            } else if (t >= 4800) {
                helper.fail("no rations came out to the woodcutter: " + came[0] + " came in, it holds " + now + ", the storehouse " + bread + "; run " + run
                    + " — " + courier.debugLine() + " | " + cutter.debugLine());
            }
        });
    }

    // ============================================================ a full pack, banked to make room

    /**
     * A miner whose pick broke on the run that filled its pack (every slot taken) has no room for a
     * new one: it banks the load in its production chest first, and then takes the spare off the rack.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "sa04_full_pack_banks_first")
    public static void sa04_full_pack_banks_first(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 188500;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity miner = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(miner != null, "a village");
        UUID village = miner.ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, village);
        store.setItem(4, named(Items.STONE_PICKAXE, "Rack Pick"));
        Kit.hill(level, x + 30, Z, 9, 8, 188L);
        BlockPos site = Kit.surface(level, x + 30, Z);
        miner.setJob(StationTask.MINE);
        miner.assignPlot(WorkZone.around(site, 6, WorkZone.DEFAULT_DEPTH), "The Pit");
        miner.moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.0F, 0.0F);
        BlockPos chest = miner.productionChestForTests();
        helper.assertTrue(chest != null && level.getBlockEntity(chest) instanceof Container, "the miner's production chest at its plot");
        // The pick broke on the run that filled the pack: bread, and cobblestone in every other slot.
        emptyHanded(miner);
        miner.insertItem(new ItemStack(Items.BREAD, 12));
        while (!miner.isPackFull()) miner.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        final int load = miner.countCarried(s -> s.is(Items.COBBLESTONE));
        helper.assertTrue(miner.isPackFull() && miner.countCarried(s -> s.is(ItemTags.PICKAXES)) == 0, "a full pack and no pick");
        helper.assertTrue(atWorkFor(level, miner, 3400), "a time of day the miner is at work");
        Kit.log("sa04 a full pack (" + load + " cobblestone) and no pick — " + miner.debugLine());
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Villages.noteAttempt(village, level.getGameTime());
            if (t % 20 != 0) return;
            boolean has = miner.countCarried(s -> isNamed(s, "Rack Pick")) > 0;
            Container box = level.getBlockEntity(chest) instanceof Container c ? c : null;
            int banked = (box == null ? 0 : count(box, s -> s.is(Items.COBBLESTONE))) + count(store, s -> s.is(Items.COBBLESTONE));
            if (t % 200 == 0) Kit.log("sa04 @" + t + " banked " + banked + ", pack full " + miner.isPackFull() + ", the rack's pick in hand " + has
                + " — " + miner.debugLine());
            if (has) {
                Kit.log("sa04 the miner banked " + banked + " cobblestone and took the spare by tick " + t);
                helper.assertTrue(banked > 0, "it banked its load to make room: " + banked);
                helper.assertTrue(count(store, s -> s.is(ItemTags.PICKAXES)) == 0, "and the spare left the rack");
                helper.succeed();
            } else if (t >= 3400) {
                helper.fail("the miner with a full pack never got the spare: banked " + banked + ", pack full " + miner.isPackFull()
                    + " — " + miner.debugLine());
            }
        });
    }

    // ============================================================ the rack's size

    /**
     * The rack is sized to the hands that wear the tools out: a spare pick for every four miners, an
     * axe for the two woodcutters, a hoe for the farmers (one for every eight), a blade for the watch,
     * and none of what nobody uses (no fisher, no rod).
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "sa05_rack_sized")
    public static void sa05_rack_sized(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 190000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID village = first.ownerId();
        StationTask[] trades = { StationTask.MINE, StationTask.MINE, StationTask.MINE, StationTask.MINE, StationTask.MINE,
            StationTask.MINE, StationTask.MINE, StationTask.MINE, StationTask.MINE, StationTask.WOOD, StationTask.WOOD,
            StationTask.GUARD, StationTask.FARM };
        first.setJob(trades[0]);
        for (int i = 1; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && village.equals(f.ownerId()), "folk " + i);
            f.setJob(trades[i]);
        }
        int picks = Toolrack.wanted(village, Toolrack.Tool.PICKAXE), axes = Toolrack.wanted(village, Toolrack.Tool.AXE);
        int hoes = Toolrack.wanted(village, Toolrack.Tool.HOE), swords = Toolrack.wanted(village, Toolrack.Tool.SWORD);
        int rods = Toolrack.wanted(village, Toolrack.Tool.ROD), shears = Toolrack.wanted(village, Toolrack.Tool.SHEARS);
        Kit.log("sa05 nine miners, two woodcutters, a guard and a farmer: picks " + picks + ", axes " + axes + ", hoes " + hoes
            + ", blades " + swords + ", rods " + rods + ", shears " + shears);
        helper.assertTrue(picks == 3 && axes == 1 && hoes == 1 && swords == 1, "one spare for every four (a hoe for every eight), at least one");
        helper.assertTrue(rods == 0 && shears == 0, "and none of what nobody uses");
        helper.assertTrue(Toolrack.of(StationTask.STORE) == null && Toolrack.of(StationTask.HAUL) == null, "the storehouse's staff wear none out");
        helper.succeed();
    }
}
