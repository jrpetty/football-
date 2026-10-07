package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bench;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.DiverSwim;
import com.jrpetty.mcassistant.entity.Divers;
import com.jrpetty.mcassistant.entity.FuelBook;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.VillageBoards;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Weather;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.goal.SmeltGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [diver] The kelp farmer and diver (Divers, KelpBeds, DiverSwim, TurtleBeach, FuelBook). Each test on its own ground
 * (x 1360000 to 1378000, z 66000), in a batch of its own. The flat world's ground is three blocks of earth over the
 * bedrock, so the lakes are dug three deep (the least a kelp bed is planted in) with a stone bed; the test of the
 * diver's breath is a tank nine deep raised on the ground, half of it roofed over like water under a jetty.
 *
 * <ul>
 * <li>dv01: the trade opens: a town of sixteen by a lake finds it, wants one diver, takes its idle hand (not its farmers
 *     or the watch); the diver's shed is on the wish list, its site the dry bank by the water, built with its smoker and
 *     campfire, its door to the water.</li>
 * <li>dv02: the kelp bed, on the diver's own day: kelp out of the stores planted on the bed; grown by the game's own
 *     random ticks; cut from above the lowest piece, every plant left standing; and growing again.</li>
 * <li>dv03: drying and packing: raw kelp into the shed's smoker on a dried kelp block, the dried kelp out of it, packed
 *     nine to a block at the shed's bench, and a block into the stores.</li>
 * <li>dv04: the fuel: with kelp blocks in the stores a smelter burns them and no coal (its coal is the stores'); the
 *     bench's firings take a kelp block, not coal; with none in the stores, coal again; the books say what was kept.</li>
 * <li>dv05: clay, then sand, then gravel off the bed while the town is short, and into the stores.</li>
 * <li>dv06: twelve plants set on the bed of a tank nine deep, most of them under a deck: the diver goes up for air,
 *     round the deck to open water, and never takes a breath of water.</li>
 * <li>dv07: the turtle beach: seagrass cut with the stores' shears, a pair of wild turtles fed it and bred, the eggs
 *     laid on the beach, fenced and lit, hatched, a hatchling grown, its scute picked up and into the stores.</li>
 * <li>dv08: the smith makes a turtle helmet of five scutes; the diver draws it and wears it, and dives without using a
 *     breath; the shop sells them.</li>
 * <li>dv09: a folk drowning in the middle of the lake is swum out to and pulled ashore alive.</li>
 * <li>dv10: a town of sixteen with no water near it keeps no diver, and its board says why.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class DiverGameTests {

    static final String EMPTY = "empty";
    static final int Z = 66000;

    /** A village of these trades (the founder first), its stores a storehouse and nothing else, so many on its roll. */
    record Town(UUID village, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk) {}

    static Town town(GameTestHelper helper, int x, int roll, int hold, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Weather.stormForTests(false);
        Kit.hold(level, x + 30, Z, hold);
        Kit.prepare(level, x + 30, Z, hold);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, -10, -10);
        onlyTheStorehouse(level, village);
        Villages.ageForTests(village, Villages.Age.WOOD);
        for (int i = 0; i < trades.length; i++) {
            folk.get(i).setAgeForTests(30);
            if (trades[i] != StationTask.NONE) folk.get(i).setJob(trades[i]);
        }
        // The roll: the town's other folk are out about the land, as a town of this size's are.
        int live = Villages.headcount(village);
        for (int i = live; i < roll; i++) Villages.recordBirth(village);
        Divers.onDutyForTests(true);
        return new Town(village, Villages.get(village), heart, store, folk);
    }

    static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        BlockState unit = StorehouseBlock.loose();
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

    /** No chest about but the storehouse (the founders' chest goes). */
    static void onlyTheStorehouse(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    /** The storehouse filled with these, in its first slots, and the village's counts made afresh. */
    static void fill(Town t, ItemStack... goods) {
        for (int i = 0; i < t.store().getContainerSize(); i++) t.store().setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < goods.length; i++) t.store().setItem(i, goods[i]);
        Villages.forgetStock();
        Villages.forgetStores(t.village());
        com.jrpetty.mcassistant.entity.Budget.forget(t.village());
        FuelBook.forget(t.village());
    }

    static int stock(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
    }

    static int carried(VillageFolkEntity f, Item it) {
        return f.countCarried(s -> s.is(it));
    }

    /** No break, no meal and no bell in the middle of a test of something else. */
    static void keepAtWork(ServerLevel level, VillageFolkEntity... folk) {
        for (VillageFolkEntity f : folk) if (f.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
        if (level.getDayTime() % 24000L > 10000L) level.setDayTime(level.getDayTime() - 6000L);
    }

    /** Set down here, still. */
    static void put(VillageFolkEntity f, BlockPos at) {
        f.getNavigation().stop();
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, f.getYRot(), 0.0F);
        f.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    }

    /** This folk the town's diver, on the bank, its pack these things. */
    static VillageFolkEntity diver(ServerLevel level, Town t, VillageFolkEntity f, ItemStack... pack) {
        Divers.takeForTests(level, t.v(), f);
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.BREAD, 8));
        for (ItemStack s : pack) f.insertItem(s);
        Divers.Waterside w = Divers.water(t.village());
        if (w != null) put(f, w.bank());
        return f;
    }

    // ------------------------------------------------------------------ the water

    /** A lake dug into the flat ground, so wide (x) and long (z), three deep with a bed of stone, its top level with the
     *  ground round it; nothing standing over it. Returns the top water's y. */
    static int lake(ServerLevel level, int x0, int z0, int wide, int longWay) {
        int y = Kit.surface(level, x0, z0).getY();
        for (int dx = 0; dx < wide; dx++) {
            for (int dz = 0; dz < longWay; dz++) {
                int x = x0 + dx, z = z0 + dz;
                for (int up = 0; up <= 3; up++) level.setBlock(new BlockPos(x, y + up, z), Blocks.AIR.defaultBlockState(), 2);
                for (int d = 1; d <= 3; d++) level.setBlock(new BlockPos(x, y - d, z), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y - 4, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        return y - 1;
    }

    /** Kelp standing on this root: so many pieces (the root counted). */
    static int height(ServerLevel level, BlockPos root) {
        int n = 0;
        BlockPos q = root;
        while (n < 40 && (level.getBlockState(q).is(Blocks.KELP) || level.getBlockState(q).is(Blocks.KELP_PLANT))) {
            n++;
            q = q.above();
        }
        return n;
    }

    /** The game's own growing: random ticks on the plant's head till it is this tall or can grow no more. */
    static int grow(ServerLevel level, BlockPos root, int tall) {
        for (int i = 0; i < 400 && height(level, root) < tall; i++) {
            BlockPos head = root.above(Math.max(0, height(level, root) - 1));
            BlockState st = level.getBlockState(head);
            if (!st.is(Blocks.KELP)) break;
            st.randomTick(level, head, level.getRandom());
        }
        return height(level, root);
    }

    // ================================================================== dv01: the trade opens

    /**
     * A Wood Age town of sixteen, a lake eighteen across thirty blocks east of its heart. It looks for water and finds the
     * lake (three deep, open water, a bank to go in from, a dry level place on the bank for the shed); it wants one diver
     * and takes its idle hand, not one of its farmers short as they are, nor the watch, and the chronicle tells it. The
     * shed goes on the wish list, its site the place on the bank; built, it has its smoker and its campfire, and its door
     * looks out over the water.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "dv01_opens")
    public static void dv01_opens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1360000;
        Town t = town(helper, x, 16, 90, StationTask.FARM, StationTask.FARM, StationTask.NONE, StationTask.GUARD);
        UUID id = t.village();
        VillageFolkEntity idle = t.folk().get(2);
        int top = lake(level, x + 30, Z - 9, 18, 18);
        helper.assertTrue(Divers.wanted(id) == 0 && Villages.share(id, StationTask.DIVER) == 0.0, "no diver before the water is found");
        Divers.Waterside w = Divers.surveyForTests(level, t.v());
        helper.assertTrue(w != null, "the lake found");
        Kit.log("dv01 the water: " + w.kind() + " at " + w.middle().toShortString() + " (top " + top + "), bank " + w.bank().toShortString()
            + ", shed " + (w.shedAt() == null ? "none" : w.shedAt().toShortString() + " back " + w.shedBack()) + "; headcount " + Villages.headcount(id));
        helper.assertTrue(w.middle().getY() == top && w.middle().getX() >= x + 30 && w.middle().getX() < x + 48, "the water is the lake: " + w.middle().toShortString());
        helper.assertTrue("the lake".equals(w.kind()), "a lake as broad as it is long: " + w.kind());
        helper.assertTrue(level.getFluidState(w.bank().below()).isEmpty() && !level.getBlockState(w.bank().below()).isAir(),
            "the bank is dry ground: " + level.getBlockState(w.bank().below()));
        helper.assertTrue(w.shedAt() != null, "a place on the bank for the shed");
        int wanted = Divers.wanted(id);
        boolean wants = Villages.wants(id, StationTask.DIVER);
        double share = Villages.share(id, StationTask.DIVER);
        helper.assertTrue(wanted == 1 && wants && share < 0.0, "a town of sixteen by the water wants one diver: " + wanted + ", " + wants + ", short " + share);
        helper.assertTrue(Divers.wanted(id) == 1 && Divers.SECOND_AT == 50, "two only at fifty");

        VillageFolkEntity took = Divers.appointForTests(level, t.v());
        Divers.tick(level, t.v());                                          // the town's own look: it has its one
        Kit.log("dv01 took " + (took == null ? "nobody" : took.displayNameCap() + " (" + took.stationTask() + ")") + "; divers "
            + Divers.divers(id).size() + "; the farmers " + t.folk().get(0).stationTask() + ", " + t.folk().get(1).stationTask()
            + "; the guard " + t.folk().get(3).stationTask());
        helper.assertTrue(took == idle && idle.stationTask() == StationTask.DIVER, "the idle hand took up diving");
        helper.assertTrue(Divers.divers(id).size() == 1, "one diver, and no more: " + Divers.divers(id).size());
        helper.assertTrue(t.folk().get(0).stationTask() == StationTask.FARM && t.folk().get(1).stationTask() == StationTask.FARM
            && t.folk().get(3).stationTask() == StationTask.GUARD, "the farmers and the guard kept to their trades");
        boolean told = false;
        for (Chronicle.Entry e : Chronicle.of(id)) if (e.text().contains("diving") || e.text().contains("deep enough for a kelp bed")) told = true;
        helper.assertTrue(told, "the chronicle tells of the water and the new diver");

        // The shed: wanted, its site the place on the bank.
        boolean shedWanted = Divers.shedWanted(id);
        List<String> list = Villages.projectsWanted(id);
        Villages.Site site = Villages.siteFor(level, id, Divers.SHED);
        Kit.log("dv01 wish list " + list + "; the shed's site " + (site == null ? "none" : site.anchor().toShortString() + " " + site.facing()));
        helper.assertTrue(shedWanted && list.contains(Divers.SHED), "the diver's shed on the wish list: " + list);
        helper.assertTrue(site != null && site.anchor().equals(w.shedAt()), "its site the place on the bank");
        int[] half = BuildGoal.footprint(Divers.SHED);
        helper.assertTrue(half[0] >= 2 && half[1] >= 2, "the shed's drawing is read (its walls five across): " + half[0] + "x" + half[1]);

        BuildGoal.stamp(level, Divers.SHED, site.anchor(), site.facing(), 13, Showcase.painter(Showcase.SPRUCE));
        Ledger.built(id, Divers.SHED, site.anchor(), site.facing());
        int smokers = 0, fires = 0, benches = 0, barrels = 0;
        BlockPos door = null;
        for (BlockPos p : BlockPos.betweenClosed(site.anchor().offset(-4, -1, -4), site.anchor().offset(4, 4, 4))) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.SMOKER)) smokers++;
            if (st.is(Blocks.CAMPFIRE)) fires++;
            if (st.is(Blocks.CRAFTING_TABLE)) benches++;
            if (st.is(Blocks.BARREL)) barrels++;
            if (st.getBlock() instanceof DoorBlock && door == null) door = p.immutable();
        }
        double doorToWater = door == null ? Double.MAX_VALUE : door.distSqr(w.middle());
        double middleToWater = site.anchor().distSqr(w.middle());
        Kit.log("dv01 the shed: smokers " + smokers + ", campfires " + fires + ", benches " + benches + ", barrels " + barrels + "; door "
            + (door == null ? "none" : door.toShortString()) + " (" + (int) Math.sqrt(doorToWater) + " from the water's middle, the shed "
            + (int) Math.sqrt(middleToWater) + ")");
        helper.assertTrue(smokers == 1 && fires == 1 && benches == 1 && barrels == 1, "the shed's smoker, campfire, bench and barrel");
        helper.assertTrue(door != null && doorToWater < middleToWater, "its door looks out over the water");
        helper.assertTrue(Divers.shedOf(id) != null && !Divers.shedWanted(id) && !Villages.projectsWanted(id).contains(Divers.SHED),
            "built, and off the wish list");
        helper.succeed();
    }

    // ================================================================== dv02: the kelp bed

    /**
     * The diver's own day by the lake, sixteen kelp in the stores: it draws them, and plants a bed on the lake's bed (no
     * nearer the bank than it should, every other square). The game's random ticks grow it to the top of the water. The
     * diver cuts it, every plant from the top down to the piece above its root: every root is left standing, the growing
     * tip again, and the kelp is in its pack. And the bed grows again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4800, batch = "dv02_kelp")
    public static void dv02_kelp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1362000;
        Town t = town(helper, x, 16, 90, StationTask.FARM, StationTask.NONE);
        UUID id = t.village();
        int top = lake(level, x + 30, Z - 9, 18, 18);
        Divers.Waterside w = Divers.surveyForTests(level, t.v());
        helper.assertTrue(w != null, "the lake found");
        fill(t, new ItemStack(Items.KELP, 16), new ItemStack(Items.BREAD, 32));
        VillageFolkEntity f = diver(level, t, t.folk().get(1));
        int[] phase = { 0 };
        List<BlockPos> ripe = new ArrayList<>();
        long[] at = { 0 };
        helper.onEachTick(() -> {
            if (phase[0] < 0) return;
            keepAtWork(level, f);
            long tick = helper.getTick();
            if (tick % 100 == 0) {
                Kit.log("dv02 @" + tick + " phase " + phase[0] + ": " + Divers.stateForTests(f) + "; bed " + w.bed().size() + ", planted "
                    + w.planted + ", cut " + w.kelp + "; kelp carried " + carried(f, Items.KELP) + ", in the stores " + stock(level, id, Items.KELP)
                    + "; at " + f.blockPosition().toShortString());
            }
            switch (phase[0]) {
                case 0 -> {
                    // Planting: out of the stores, onto the bed.
                    if (w.bed().size() >= 8 && Divers.jobOf(f) == null) {
                        List<BlockPos> bed = w.bed();
                        int wrong = 0;
                        for (BlockPos r : bed) {
                            boolean ok = (level.getBlockState(r).is(Blocks.KELP) || level.getBlockState(r).is(Blocks.KELP_PLANT)) && r.getY() == top - 2
                                && level.getBlockState(r.below()).is(Blocks.STONE) && ((r.getX() + r.getZ()) & 1) == 0;
                            if (!ok) wrong++;
                        }
                        Kit.log("dv02 planted " + bed.size() + " at " + tick + ": " + bed + "; " + wrong + " amiss; stores' kelp " + stock(level, id, Items.KELP));
                        helper.assertTrue(wrong == 0, "every plant on the stone bed, three down, on every other square: " + wrong + " amiss");
                        helper.assertTrue(stock(level, id, Items.KELP) < 16, "the kelp came out of the stores");
                        // The game grows it: to the top of the water, three tall.
                        for (BlockPos r : bed) if (grow(level, r, 3) >= 3) ripe.add(r);
                        Kit.log("dv02 grown: " + ripe.size() + " of " + bed.size() + " three tall");
                        helper.assertTrue(ripe.size() >= 3, "most of the bed grown to the top of the water: " + ripe.size());
                        at[0] = w.kelp;
                        phase[0] = 1;
                    } else if (tick > 2600) {
                        helper.fail("no kelp bed planted: bed " + w.bed().size() + " — " + Divers.stateForTests(f) + " — " + f.debugLine());
                    }
                }
                case 1 -> {
                    // The harvest, on its own day.
                    if (w.harvests > 0 && Divers.jobOf(f) == null) {
                        int cut = 0, gone = 0;
                        for (BlockPos r : ripe) {
                            int h = height(level, r);
                            if (h == 0) gone++;
                            if (h == 1 && level.getBlockState(r).is(Blocks.KELP)) cut++;
                        }
                        long got = w.kelp - at[0];
                        Kit.log("dv02 cut at " + tick + ": " + cut + " of " + ripe.size() + " down to the root, " + gone + " gone; " + got
                            + " kelp off them; carried " + carried(f, Items.KELP));
                        helper.assertTrue(gone == 0, "no plant pulled up by the root: " + gone);
                        helper.assertTrue(cut >= Math.min(3, ripe.size()), "the ripe plants cut to the root, the root the growing tip: " + cut);
                        helper.assertTrue(got >= 2L * cut, "two kelp off each three-tall plant: " + got + " off " + cut);
                        // And it grows again, by the game's own rule, from the root left.
                        int again = 0;
                        for (BlockPos r : ripe) if (height(level, r) == 1 && grow(level, r, 2) >= 2) again++;
                        Kit.log("dv02 regrown: " + again + " of " + cut);
                        helper.assertTrue(again >= cut, "every cut plant grows again: " + again + " of " + cut);
                        phase[0] = -1;
                        helper.succeed();
                    } else if (tick > 4600) {
                        helper.fail("the ripe bed not cut: harvests " + w.harvests + " — " + Divers.stateForTests(f) + " — " + f.debugLine());
                    }
                }
                default -> { }
            }
        });
    }

    // ================================================================== dv03: drying and packing

    /**
     * A diver at its shed (stamped on the bank) with sixteen kelp kept for the bed, nine more to dry, nine dried from
     * yesterday and a dried kelp block for fuel. The nine go into the smoker, the block on its fire (the books have it);
     * the smoker dries them by the game's own recipe and time; the diver takes them out, packs eighteen into two blocks at
     * the shed's bench, and at the stores banks one and keeps one for its smoker.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3200, batch = "dv03_drying")
    public static void dv03_drying(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1364000;
        Town t = town(helper, x, 16, 90, StationTask.FARM, StationTask.NONE);
        UUID id = t.village();
        int top = lake(level, x + 30, Z - 9, 18, 18);
        BlockPos bank = new BlockPos(x + 29, top + 1, Z);
        Divers.Waterside w = Divers.setForTests(id, new BlockPos(x + 39, top, Z), bank, null, "the lake");
        Divers.ownWorkForTests(false);
        BlockPos shedAt = new BlockPos(x + 20, top + 1, Z);
        BuildGoal.stamp(level, Divers.SHED, shedAt, Direction.WEST, 13, Showcase.painter(Showcase.SPRUCE));
        Ledger.built(id, Divers.SHED, shedAt, Direction.WEST);
        BlockPos smoker = null;
        for (BlockPos p : BlockPos.betweenClosed(shedAt.offset(-4, 0, -4), shedAt.offset(4, 2, 4))) {
            if (level.getBlockState(p).is(Blocks.SMOKER)) smoker = p.immutable();
        }
        helper.assertTrue(smoker != null, "the shed's smoker");
        final BlockPos oven = smoker;
        fill(t, new ItemStack(Items.BREAD, 32));
        VillageFolkEntity f = diver(level, t, t.folk().get(1), new ItemStack(Items.KELP, 25), new ItemStack(Items.DRIED_KELP, 9),
            new ItemStack(Items.DRIED_KELP_BLOCK, 1));
        put(f, shedAt);
        long day = level.getDayTime() / 24000L;
        helper.assertTrue(Divers.startForTests(f, level, Divers.Job.SHED), "off to the shed");
        int[] phase = { 0 };
        helper.onEachTick(() -> {
            if (phase[0] < 0) return;
            keepAtWork(level, f);
            long tick = helper.getTick();
            AbstractFurnaceBlockEntity fb = (AbstractFurnaceBlockEntity) level.getBlockEntity(oven);
            if (tick % 100 == 0) {
                Kit.log("dv03 @" + tick + " phase " + phase[0] + ": smoker in " + fb.getItem(0) + ", fuel " + fb.getItem(1) + ", out " + fb.getItem(2)
                    + "; carried kelp " + carried(f, Items.KELP) + ", dried " + carried(f, Items.DRIED_KELP) + ", blocks "
                    + carried(f, Items.DRIED_KELP_BLOCK) + "; " + Divers.stateForTests(f));
            }
            switch (phase[0]) {
                case 0 -> {
                    if (Divers.jobOf(f) != null) return;
                    long[] burnt = FuelBook.lastDays(id, day, 1);
                    Kit.log("dv03 the smoker loaded at " + tick + ": in " + fb.getItem(0) + ", fuel " + fb.getItem(1) + "; carried kelp "
                        + carried(f, Items.KELP) + ", blocks " + carried(f, Items.DRIED_KELP_BLOCK) + "; burnt kelp " + burnt[FuelBook.Kind.KELP.ordinal()]
                        + ", coal " + burnt[FuelBook.Kind.COAL.ordinal()] + "; packed " + w.blocks);
                    int loaded = (fb.getItem(0).is(Items.KELP) ? fb.getItem(0).getCount() : 0) + (fb.getItem(2).is(Items.DRIED_KELP) ? fb.getItem(2).getCount() : 0);
                    helper.assertTrue(loaded == 9, "nine kelp in the smoker: " + fb.getItem(0) + ", " + fb.getItem(2));
                    helper.assertTrue(carried(f, Items.KELP) == 16, "the bed's sixteen kept back: " + carried(f, Items.KELP));
                    helper.assertTrue(burnt[FuelBook.Kind.KELP.ordinal()] == 1 && burnt[FuelBook.Kind.COAL.ordinal()] == 0,
                        "a kelp block on the smoker's fire, in the books");
                    helper.assertTrue(w.blocks == 1 && carried(f, Items.DRIED_KELP_BLOCK) == 1 && carried(f, Items.DRIED_KELP) == 0,
                        "yesterday's nine packed into a block at the bench: " + w.blocks);
                    phase[0] = 1;
                }
                case 1 -> {
                    // The game's smoker at work: a hundred ticks a piece.
                    if (fb.getItem(2).is(Items.DRIED_KELP) && fb.getItem(2).getCount() >= 9) {
                        Kit.log("dv03 dried at " + tick + ": " + fb.getItem(2));
                        put(f, shedAt);
                        helper.assertTrue(Divers.startForTests(f, level, Divers.Job.SHED), "back to the shed");
                        phase[0] = 2;
                    } else if (tick > 2600) {
                        helper.fail("the smoker did not dry the kelp: in " + fb.getItem(0) + ", out " + fb.getItem(2) + ", lit "
                            + level.getBlockState(oven));
                    }
                }
                case 2 -> {
                    if (Divers.jobOf(f) != null) return;
                    Kit.log("dv03 packed at " + tick + ": blocks " + carried(f, Items.DRIED_KELP_BLOCK) + ", dried " + carried(f, Items.DRIED_KELP)
                        + "; books dried " + w.dried + ", packed " + w.blocks);
                    helper.assertTrue(fb.getItem(2).isEmpty() && w.dried >= 9, "the dried kelp taken out of the smoker: " + fb.getItem(2));
                    helper.assertTrue(w.blocks == 2 && carried(f, Items.DRIED_KELP_BLOCK) == 2 && carried(f, Items.DRIED_KELP) == 0,
                        "nine more packed into a block: " + w.blocks);
                    put(f, t.store().getBlockPos().south());
                    helper.assertTrue(Divers.startForTests(f, level, Divers.Job.STORES), "off to the stores");
                    phase[0] = 3;
                }
                case 3 -> {
                    if (Divers.jobOf(f) != null) return;
                    int inStores = stock(level, id, Items.DRIED_KELP_BLOCK);
                    Kit.log("dv03 at the stores: " + inStores + " kelp blocks banked; kept " + carried(f, Items.DRIED_KELP_BLOCK)
                        + "; the fires see kelp " + FuelBook.kelpInStores(level, id));
                    helper.assertTrue(inStores == 1 && carried(f, Items.DRIED_KELP_BLOCK) == 1, "one into the stores, one kept for the smoker");
                    helper.assertTrue(FuelBook.kelpInStores(level, id), "the town's fires see kelp blocks in the stores");
                    phase[0] = -1;
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    // ================================================================== dv04: the fuel

    /**
     * A town with plenty of coal (not putting any by) and four dried kelp blocks in the stores. Its smelter, twenty coal
     * and a kelp block in its pack, eight raw iron to smelt: the kelp block goes on the fire, never the coal (the coal is
     * the stores', none kept back), and the iron is smelted. The bench's firing for a cooked beef takes a kelp block, not
     * coal, and the books say so ("kept"). With the kelp blocks gone from the stores, coal goes on the fires again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "dv04_fuel")
    public static void dv04_fuel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1366000;
        Town t = town(helper, x, 16, 60, StationTask.FARM, StationTask.SMELT);
        UUID id = t.village();
        Villages.ageForTests(id, Villages.Age.STONE);                       // the smelter's age
        fill(t, new ItemStack(Items.DRIED_KELP_BLOCK, 4), new ItemStack(Items.COAL, 64), new ItemStack(Items.COAL, 64),
            new ItemStack(Items.BREAD, 32), new ItemStack(Items.BEEF, 4), new ItemStack(Items.FURNACE), new ItemStack(Items.COBBLESTONE, 64));
        VillageFolkEntity smelter = t.folk().get(1);
        BlockPos forge = Kit.surface(level, x - 4, Z);
        level.setBlock(forge, Blocks.FURNACE.defaultBlockState(), 3);
        ZoneChests.mark(level, forge);
        smelter.assignPlot(WorkZone.around(forge, 4, WorkZone.DEFAULT_DEPTH), "The Forge");
        smelter.moveTo(forge.getX() + 1.5, forge.getY(), forge.getZ() + 0.5, 0.0F, 0.0F);
        smelter.getInventoryItems().clear();
        smelter.insertItem(new ItemStack(Items.BREAD, 8));
        smelter.insertItem(new ItemStack(Items.RAW_IRON, 8));
        smelter.insertItem(new ItemStack(Items.COAL, 20));
        smelter.insertItem(new ItemStack(Items.DRIED_KELP_BLOCK, 1));
        boolean saving = smelter.savingCoal(), low = smelter.coalLow(), kelp = smelter.kelpForFuel();
        int stashable = smelter.countStashable(s -> s.is(Items.COAL));
        boolean coalNow = SmeltGoal.burnsNow(smelter, new ItemStack(Items.COAL)), kelpNow = SmeltGoal.burnsNow(smelter, new ItemStack(Items.DRIED_KELP_BLOCK));
        Kit.log("dv04 the smelter: saving coal " + saving + ", coal low " + low + ", kelp in the stores " + kelp + "; of its 20 coal "
            + stashable + " the stores'; burns coal " + coalNow + ", kelp " + kelpNow);
        helper.assertTrue(!saving && !low, "the town has its coal: the rule that follows is the kelp's, not the coal's");
        helper.assertTrue(kelp && kelpNow && !coalNow, "kelp blocks in the stores: they burn, and no coal");
        helper.assertTrue(stashable == 20, "the smelter keeps no coal back while kelp burns: " + stashable);

        // The bench's firing: a cooked beef in the furnace, on a kelp block.
        Villages.Village v = t.v();
        Bench.Hand hand = Bench.handOf(level, v, null, null);
        Bench.Plan plan = Bench.plan(level, v, Items.COOKED_BEEF, 1, hand);
        Kit.log("dv04 the bench's cooked beef: " + (plan.ok() ? "ok" : "short of " + plan.shortOf) + ", steps " + plan.steps + ", burns " + plan.burnt);
        helper.assertTrue(plan.ok() && plan.burnt.containsKey(Items.DRIED_KELP_BLOCK) && !plan.burnt.containsKey(Items.COAL),
            "the bench's firing on a kelp block, not coal: " + plan.burnt);
        ItemStack beef = Bench.make(level, v, plan, null, hand);
        helper.assertTrue(beef.is(Items.COOKED_BEEF) && stock(level, id, Items.DRIED_KELP_BLOCK) == 3 && stock(level, id, Items.COAL) == 128,
            "made, the kelp block gone from the stores and every coal still there: kelp " + stock(level, id, Items.DRIED_KELP_BLOCK) + ", coal "
            + stock(level, id, Items.COAL));
        long day = level.getDayTime() / 24000L;

        smelter.enqueue(Job.smelt("iron", 8));
        Predicate<ItemStack> coal = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
        int[] seen = { 0 };
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            keepAtWork(level, smelter);
            long tick = helper.getTick();
            if (!(level.getBlockEntity(forge) instanceof AbstractFurnaceBlockEntity fb)) return;
            if (coal.test(fb.getItem(1))) seen[0]++;
            int ingots = smelter.countCarried(s -> s.is(Items.IRON_INGOT)) + (fb.getItem(2).is(Items.IRON_INGOT) ? fb.getItem(2).getCount() : 0)
                + stock(level, id, Items.IRON_INGOT);
            if (tick % 200 == 0) {
                Kit.log("dv04 @" + tick + ": furnace in " + fb.getItem(0) + ", fuel " + fb.getItem(1) + ", out " + fb.getItem(2) + "; ingots " + ingots
                    + "; coal carried " + smelter.countCarried(coal) + " — " + smelter.smeltStateForTests());
            }
            if (seen[0] > 0) {
                done[0] = true;
                helper.fail("coal went on the fire with kelp blocks in the stores: " + fb.getItem(1));
                return;
            }
            if (ingots < 1) {
                if (tick > 3400) {
                    done[0] = true;
                    helper.fail("no iron smelted: " + smelter.smeltStateForTests() + " — " + smelter.debugLine());
                }
                return;
            }
            done[0] = true;
            long[] burnt = FuelBook.lastDays(id, day, 1);
            List<String> lines = FuelBook.lines(level, id);
            String kept = FuelBook.keptLine(level, id);
            Kit.log("dv04 smelted at " + tick + ": books kelp " + burnt[FuelBook.Kind.KELP.ordinal()] + ", coal " + burnt[FuelBook.Kind.COAL.ordinal()]
                + "; " + lines + "; kept: " + kept);
            helper.assertTrue(burnt[FuelBook.Kind.KELP.ordinal()] >= 2 && burnt[FuelBook.Kind.COAL.ordinal()] == 0,
                "the smelter's kelp block and the bench's on the fires, no coal");
            helper.assertTrue(kept != null && kept.contains("kept") && !lines.isEmpty(), "the books say what the kelp kept: " + kept);
            int townCoal = smelter.countCarried(coal) + stock(level, id, Items.COAL) + stock(level, id, Items.CHARCOAL);
            helper.assertTrue(townCoal >= 148, "every coal the town had still there, the smelter's and the stores': " + townCoal);

            // The kelp blocks gone from the stores: coal again, for the smelters and the bench.
            for (int i = 0; i < t.store().getContainerSize(); i++) if (t.store().getItem(i).is(Items.DRIED_KELP_BLOCK)) t.store().setItem(i, ItemStack.EMPTY);
            Villages.forgetStock();
            FuelBook.forget(id);
            VillageFolkEntity next = VillageFolkSpawnerBlock.raise(level, t.heart().offset(-1, 0, 1), 0.0F);
            helper.assertTrue(next != null && id.equals(next.ownerId()), "a second smelter in the village");
            next.setJob(StationTask.SMELT);
            boolean nextKelp = next.kelpForFuel(), nextCoal = SmeltGoal.burnsNow(next, new ItemStack(Items.COAL));
            Bench.Plan plain = Bench.plan(level, v, Items.COOKED_BEEF, 1, Bench.handOf(level, v, null, null));
            Kit.log("dv04 no kelp in the stores: kelp " + nextKelp + ", burns coal " + nextCoal + "; the bench burns " + plain.burnt);
            helper.assertTrue(!nextKelp && nextCoal, "with no kelp blocks in the stores the furnaces burn coal again");
            helper.assertTrue(plain.ok() && (plain.burnt.containsKey(Items.COAL) || plain.burnt.containsKey(Items.CHARCOAL)),
                "and the bench's firing coal: " + plain.burnt);
            helper.succeed();
        });
    }

    // ================================================================== dv05: clay, sand and gravel

    /**
     * Six clay, four sand and three gravel on the lake's bed, and none of any of them in the stores. The diver goes for the
     * clay first (the masons' bricks), then the sand, then the gravel, each off the bed and never the bank, and at the
     * stores banks the lot: the clay in the books and the gazette.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3200, batch = "dv05_clay")
    public static void dv05_clay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1368000;
        Town t = town(helper, x, 16, 90, StationTask.FARM, StationTask.NONE);
        UUID id = t.village();
        int top = lake(level, x + 30, Z - 9, 18, 18);
        List<BlockPos> clay = new ArrayList<>(), sand = new ArrayList<>(), gravel = new ArrayList<>();
        for (int i = 0; i < 6; i++) clay.add(new BlockPos(x + 36 + i, top - 2, Z - 3));
        for (int i = 0; i < 4; i++) sand.add(new BlockPos(x + 36 + i, top - 2, Z + 2));
        for (int i = 0; i < 3; i++) gravel.add(new BlockPos(x + 37 + i, top - 2, Z + 5));
        for (BlockPos p : clay) level.setBlock(p, Blocks.CLAY.defaultBlockState(), 2);
        for (BlockPos p : sand) level.setBlock(p, Blocks.SAND.defaultBlockState(), 2);
        for (BlockPos p : gravel) level.setBlock(p, Blocks.GRAVEL.defaultBlockState(), 2);
        Divers.Waterside w = Divers.surveyForTests(level, t.v());
        helper.assertTrue(w != null, "the lake found");
        fill(t, new ItemStack(Items.BREAD, 32));
        Divers.ownWorkForTests(false);
        VillageFolkEntity f = diver(level, t, t.folk().get(1));
        long day = level.getDayTime() / 24000L;
        Divers.Job[] order = { Divers.Job.CLAY, Divers.Job.SAND, Divers.Job.GRAVEL };
        List<List<BlockPos>> beds = List.of(clay, sand, gravel);
        int[] phase = { 0 };
        boolean[] going = { false };
        helper.onEachTick(() -> {
            if (phase[0] < 0) return;
            keepAtWork(level, f);
            long tick = helper.getTick();
            if (tick % 100 == 0) {
                Kit.log("dv05 @" + tick + " phase " + phase[0] + ": " + Divers.stateForTests(f) + "; carried clay " + carried(f, Items.CLAY_BALL)
                    + ", sand " + carried(f, Items.SAND) + ", gravel " + carried(f, Items.GRAVEL) + ", flint " + carried(f, Items.FLINT));
            }
            if (tick > 3000) {
                phase[0] = -1;
                helper.fail("stuck at phase " + phase[0] + ": " + Divers.stateForTests(f) + " — " + f.debugLine());
                return;
            }
            if (phase[0] < 3) {
                if (!going[0]) {
                    Divers.Job job = Divers.nextForTests(f, level);
                    Kit.log("dv05 next: " + job);
                    helper.assertTrue(job == order[phase[0]], "next the " + order[phase[0]].name().toLowerCase(Locale.ROOT) + ": " + job);
                    going[0] = true;
                    return;
                }
                if (Divers.jobOf(f) != null) return;
                int left = 0;
                for (BlockPos p : beds.get(phase[0])) {
                    BlockState st = level.getBlockState(p);
                    if (st.is(Blocks.CLAY) || st.is(Blocks.SAND) || st.is(Blocks.GRAVEL)) left++;
                }
                Kit.log("dv05 " + order[phase[0]] + " done at " + tick + ": " + left + " left on the bed");
                helper.assertTrue(left == 0, "all of it dug off the bed: " + left + " left");
                going[0] = false;
                phase[0]++;
                if (phase[0] == 3) {
                    put(f, t.store().getBlockPos().south());
                    helper.assertTrue(Divers.startForTests(f, level, Divers.Job.STORES), "off to the stores");
                }
                return;
            }
            if (Divers.jobOf(f) != null) return;
            int c = stock(level, id, Items.CLAY_BALL), s = stock(level, id, Items.SAND), g = stock(level, id, Items.GRAVEL) + stock(level, id, Items.FLINT);
            String gazette = Divers.gazette(id, day + 1);
            Kit.log("dv05 in the stores: clay " + c + ", sand " + s + ", gravel and flint " + g + "; books clay " + w.clay + "; gazette " + gazette);
            helper.assertTrue(c == 24 && w.clay == 24, "six clay blocks are twenty-four clay, in the stores and the books: " + c + ", " + w.clay);
            helper.assertTrue(s == 4 && g == 3, "the sand and the gravel too: " + s + ", " + g);
            helper.assertTrue(gazette != null && gazette.contains("clay"), "the gazette has the clay: " + gazette);
            phase[0] = -1;
            helper.succeed();
        });
    }

    // ================================================================== dv06: it never drowns

    /**
     * A tank nine deep, nine across, raised on the ground; a deck of planks over the west half of it, level with the
     * bank, as water under a jetty is. Twelve kelp to plant on its floor, eight of them under the deck, the farthest
     * first. It is a long dive: the diver goes up for air before its breath runs low, round the deck's edge to the open
     * water and not up under the planks, fills its lungs at the top and goes back down to its work; it plants all twelve,
     * comes out on the bank, and never takes a breath of water nor a point of harm.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "dv06_breath")
    public static void dv06_breath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1370000;
        Town t = town(helper, x, 16, 60, StationTask.FARM, StationTask.NONE);
        UUID id = t.village();
        int x0 = x + 30, z0 = Z - 4, size = 9, depth = 9;
        int y0 = Kit.surface(level, x0, z0).getY();
        for (int x1 = x0 - 4; x1 < x0 + size + 4; x1++) {
            for (int z1 = z0 - 4; z1 < z0 + size + 4; z1++) {
                boolean inside = x1 >= x0 && x1 < x0 + size && z1 >= z0 && z1 < z0 + size;
                level.setBlock(new BlockPos(x1, y0 - 1, z1), Blocks.STONE.defaultBlockState(), 2);
                for (int y = y0; y < y0 + depth; y++) {
                    level.setBlock(new BlockPos(x1, y, z1), inside ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
                }
                for (int y = y0 + depth; y < y0 + depth + 4; y++) level.setBlock(new BlockPos(x1, y, z1), Blocks.AIR.defaultBlockState(), 2);
                if (inside && x1 < x0 + 4) level.setBlock(new BlockPos(x1, y0 + depth, z1), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            }
        }
        BlockPos bank = new BlockPos(x0 + size, y0 + depth, z0 + 4);
        Divers.Waterside w = Divers.setForTests(id, new BlockPos(x0 + 4, y0 + depth - 1, z0 + 4), bank, null, "the lake");
        Divers.ownWorkForTests(false);
        List<BlockPos> spots = new ArrayList<>();
        for (int dz : new int[]{ 0, 2, 4, 6 }) spots.add(new BlockPos(x0, y0, z0 + dz));
        for (int dz : new int[]{ 7, 5, 3, 1 }) spots.add(new BlockPos(x0 + 2, y0, z0 + dz));
        for (int dz : new int[]{ 1, 3, 5, 7 }) spots.add(new BlockPos(x0 + 6, y0, z0 + dz));
        VillageFolkEntity f = diver(level, t, t.folk().get(1), new ItemStack(Items.KELP, 12));
        put(f, bank);
        float health = f.getHealth();
        Divers.spotsForTests(f, level, Divers.Job.PLANT, spots);
        int[] least = { 300 };
        int[] underFor = { 0, 0 };                                          // the longest under in one go; now
        boolean[] underDeck = { false };
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            keepAtWork(level, f);
            long tick = helper.getTick();
            boolean under = f.isEyeInFluid(FluidTags.WATER);
            if (under) {
                least[0] = Math.min(least[0], f.getAirSupply());
                underFor[1]++;
                underFor[0] = Math.max(underFor[0], underFor[1]);
            } else {
                underFor[1] = 0;
            }
            // Came up for air: never with its head under the deck.
            if (!under && f.isInWater() && f.getX() < x0 + 4 && f.getY() > y0 + depth - 2) underDeck[0] = true;
            if (tick % 50 == 0) {
                Kit.log("dv06 @" + tick + ": " + Divers.stateForTests(f) + "; at " + String.format(Locale.ROOT, "%.1f %.1f %.1f", f.getX(), f.getY(), f.getZ())
                    + " under " + under + ", least air " + least[0] + ", health " + f.getHealth() + "; bed " + w.bed().size());
            }
            if (!f.isAlive() || f.getHealth() < health || least[0] <= 0) {
                done[0] = true;
                helper.fail("the diver took water: air " + least[0] + ", health " + f.getHealth() + " of " + health + " — " + Divers.stateForTests(f));
                return;
            }
            if (Divers.jobOf(f) != null) {
                if (tick > 3400) {
                    done[0] = true;
                    helper.fail("the dive never finished: " + Divers.stateForTests(f) + " — " + f.debugLine());
                }
                return;
            }
            done[0] = true;
            int planted = 0;
            for (BlockPos p : spots) if (level.getBlockState(p).is(Blocks.KELP)) planted++;
            Kit.log("dv06 done at " + tick + ": " + planted + " planted, up for air " + w.surfaced + " times, the longest under " + underFor[0]
                + " ticks, the least air " + least[0] + "; out on the bank " + !f.isInWater() + " at " + f.blockPosition().toShortString());
            helper.assertTrue(planted == 12, "every plant set on the floor of the tank: " + planted);
            helper.assertTrue(w.surfaced >= 1, "it went up for air: " + w.surfaced);
            helper.assertTrue(least[0] >= 20 && f.getHealth() >= health, "never short of breath, never hurt: air " + least[0] + ", health " + f.getHealth());
            helper.assertTrue(underFor[0] < 300, "never under for longer than a breath lasts: " + underFor[0]);
            helper.assertTrue(!underDeck[0], "never up under the deck");
            helper.assertTrue(!f.isInWater(), "out on the bank");
            helper.succeed();
        });
    }

    // ================================================================== dv07: the turtle beach

    /**
     * A lake with a sandy beach along its south side and two wild turtles on it; four seagrass on the bed; shears in the
     * diver's pack. It cuts the seagrass (only shears get it), feeds the pair of turtles on the beach, and they breed by the
     * game's own goals; the one with the eggs lays them in the beach's sand. The diver fences the clutch round with a gate
     * to the water and a light on a post, out of the stores' planks and torches. The eggs hatch by the game's random ticks;
     * a hatchling grows up and drops its scute, as every turtle does; the diver picks it up off the sand, and banks it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "dv07_turtles")
    public static void dv07_turtles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1372000;
        Town t = town(helper, x, 16, 90, StationTask.FARM, StationTask.NONE);
        UUID id = t.village();
        int lx = x + 30, lz = Z - 9;
        int top = lake(level, lx, lz, 18, 14);
        // The beach: sand along the south shore, six deep.
        for (int bx = lx + 2; bx < lx + 14; bx++) {
            for (int bz = lz + 14; bz < lz + 20; bz++) level.setBlock(new BlockPos(bx, top, bz), Blocks.SAND.defaultBlockState(), 2);
        }
        List<BlockPos> grass = List.of(new BlockPos(lx + 6, top - 2, lz + 6), new BlockPos(lx + 8, top - 2, lz + 6),
            new BlockPos(lx + 10, top - 2, lz + 7), new BlockPos(lx + 12, top - 2, lz + 8));
        for (BlockPos p : grass) level.setBlock(p, Blocks.SEAGRASS.defaultBlockState(), 2);
        Divers.Waterside w = Divers.surveyForTests(level, t.v());
        helper.assertTrue(w != null && w.beach() != null, "the lake and its beach found: " + (w == null ? "no water" : String.valueOf(w.beach())));
        Kit.log("dv07 the water at " + w.middle().toShortString() + ", bank " + w.bank().toShortString() + ", beach " + w.beach().toShortString());
        helper.assertTrue(level.getBlockState(w.beach().below()).is(Blocks.SAND), "the beach is the sand");
        List<Turtle> pair = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Turtle tu = EntityType.TURTLE.create(level);
            helper.assertTrue(tu != null, "a turtle");
            tu.moveTo(w.beach().getX() + 0.5 + i, w.beach().getY(), w.beach().getZ() + 0.5, 0.0F, 0.0F);
            tu.setPersistenceRequired();
            tu.setHomePos(w.beach());                                       // the beach they were found on
            level.addFreshEntity(tu);
            pair.add(tu);
        }
        fill(t, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.TORCH, 16), new ItemStack(Items.BREAD, 32));
        Divers.ownWorkForTests(false);
        VillageFolkEntity f = diver(level, t, t.folk().get(1), new ItemStack(Items.SHEARS));
        helper.assertTrue(Divers.startForTests(f, level, Divers.Job.SEAGRASS), "down for the seagrass");
        int[] phase = { 0 };
        long[] since = { 0 };
        BlockPos[] egg = { null };
        helper.onEachTick(() -> {
            if (phase[0] < 0) return;
            keepAtWork(level, f);
            long tick = helper.getTick();
            if (tick % 200 == 0) {
                StringBuilder ts = new StringBuilder();
                for (Turtle tu : pair) ts.append(" [").append(tu.blockPosition().toShortString()).append(tu.isInLove() ? " in love" : "")
                    .append(tu.hasEgg() ? " with eggs" : "").append(']');
                Kit.log("dv07 @" + tick + " phase " + phase[0] + ": " + Divers.stateForTests(f) + "; seagrass " + carried(f, Items.SEAGRASS)
                    + "; turtles" + ts + "; bred " + w.bred + ", hatched " + w.hatched + ", scutes " + w.scutes);
            }
            if (tick > 8800) {
                int p = phase[0];
                phase[0] = -1;
                helper.fail("stuck at phase " + p + ": " + Divers.stateForTests(f) + " — " + f.debugLine());
                return;
            }
            switch (phase[0]) {
                case 0 -> {
                    if (Divers.jobOf(f) != null) return;
                    int left = 0;
                    for (BlockPos p : grass) if (level.getBlockState(p).is(Blocks.SEAGRASS)) left++;
                    Kit.log("dv07 seagrass cut at " + tick + ": carried " + carried(f, Items.SEAGRASS) + ", " + left + " left on the bed");
                    helper.assertTrue(carried(f, Items.SEAGRASS) == 4 && left == 0, "four seagrass cut with the shears");
                    for (int i = 0; i < pair.size(); i++) pair.get(i).moveTo(w.beach().getX() + 0.5 + i, w.beach().getY(), w.beach().getZ() + 1.5, 0.0F, 0.0F);
                    put(f, w.beach());
                    helper.assertTrue(Divers.startForTests(f, level, Divers.Job.BEACH), "to the beach with it");
                    phase[0] = 1;
                }
                case 1 -> {
                    if (Divers.jobOf(f) != null) return;
                    int inLove = 0;
                    for (Turtle tu : pair) if (tu.isInLove() || tu.hasEgg()) inLove++;
                    Kit.log("dv07 fed at " + tick + ": " + inLove + " in love; bred " + w.bred + "; seagrass left " + carried(f, Items.SEAGRASS));
                    helper.assertTrue(w.bred == 1 && carried(f, Items.SEAGRASS) == 2, "the pair fed a seagrass each, and courting");
                    since[0] = tick;
                    phase[0] = 2;
                }
                case 2 -> {
                    // The game's own: they breed, and the one with eggs lays them in the beach's sand.
                    if (tick % 10 != 0) return;
                    for (BlockPos p : BlockPos.betweenClosed(w.beach().offset(-10, -2, -10), w.beach().offset(10, 2, 10))) {
                        if (level.getBlockState(p).is(Blocks.TURTLE_EGG)) { egg[0] = p.immutable(); break; }
                    }
                    if (egg[0] == null) return;
                    Kit.log("dv07 eggs laid at " + egg[0].toShortString() + " (" + level.getBlockState(egg[0]) + ") " + (tick - since[0]) + " ticks after");
                    helper.assertTrue(level.getBlockState(egg[0].below()).is(Blocks.SAND), "in the sand");
                    put(f, w.beach());
                    helper.assertTrue(Divers.startForTests(f, level, Divers.Job.BEACH), "to the clutch");
                    phase[0] = 3;
                }
                case 3 -> {
                    if (Divers.jobOf(f) != null) return;
                    int fence = 0, gates = 0;
                    for (BlockPos p : BlockPos.betweenClosed(egg[0].offset(-2, 0, -2), egg[0].offset(2, 0, 2))) {
                        BlockState st = level.getBlockState(p);
                        if (st.is(Blocks.SPRUCE_FENCE)) fence++;
                        if (st.getBlock() instanceof FenceGateBlock) gates++;
                    }
                    boolean lit = false;
                    for (BlockPos p : BlockPos.betweenClosed(egg[0].offset(-2, 1, -2), egg[0].offset(2, 1, 2))) {
                        if (level.getBlockState(p).is(Blocks.TORCH) || level.getBlockState(p).is(Blocks.LANTERN)) lit = true;
                    }
                    boolean round = closedRound(level, egg[0]);
                    Kit.log("dv07 fenced at " + tick + ": " + fence + " fence, " + gates + " gate, lit " + lit + ", closed round " + round
                        + "; planks left " + stock(level, id, Items.OAK_PLANKS) + ", torches " + stock(level, id, Items.TORCH));
                    helper.assertTrue(round && fence >= 4 && gates <= 1 && lit, "the clutch fenced round, and a light on a post");
                    helper.assertTrue(stock(level, id, Items.OAK_PLANKS) < 64 && stock(level, id, Items.TORCH) < 16, "out of the stores' planks and torches");
                    // The eggs hatch, by the game's random ticks.
                    for (int i = 0; i < 40000 && level.getBlockState(egg[0]).is(Blocks.TURTLE_EGG); i++) {
                        level.getBlockState(egg[0]).randomTick(level, egg[0], level.getRandom());
                    }
                    List<Turtle> babies = level.getEntitiesOfClass(Turtle.class, new AABB(egg[0]).inflate(3), Turtle::isBaby);
                    Kit.log("dv07 hatched: " + babies.size() + " hatchlings; the egg block " + level.getBlockState(egg[0]));
                    helper.assertTrue(!babies.isEmpty(), "the eggs hatched");
                    // A hatchling out on the open sand, all but grown.
                    Turtle baby = babies.get(0);
                    BlockPos open = null;
                    for (int bx = lx + 2; bx < lx + 14 && open == null; bx++) {
                        for (int bz = lz + 15; bz < lz + 20 && open == null; bz++) {
                            BlockPos c = new BlockPos(bx, top + 1, bz);
                            if (c.distSqr(egg[0]) >= 16 && level.getBlockState(c).isAir() && level.getBlockState(c.below()).is(Blocks.SAND)) open = c;
                        }
                    }
                    helper.assertTrue(open != null, "open sand away from the nest");
                    baby.moveTo(open.getX() + 0.5, open.getY(), open.getZ() + 0.5, 0.0F, 0.0F);
                    baby.setAge(-1);
                    put(f, open.north(2).east(1));
                    since[0] = tick;
                    phase[0] = 4;
                }
                case 4 -> {
                    if (tick - since[0] < 5) return;
                    List<ItemEntity> scutes = level.getEntitiesOfClass(ItemEntity.class, new AABB(w.beach()).inflate(16, 4, 16),
                        e -> e.getItem().is(Items.TURTLE_SCUTE));
                    Kit.log("dv07 grown up: " + scutes.size() + " scute on the sand");
                    helper.assertTrue(!scutes.isEmpty() || carried(f, Items.TURTLE_SCUTE) > 0, "the hatchling grew up and dropped its scute");
                    helper.assertTrue(Divers.startForTests(f, level, Divers.Job.BEACH), "to pick it up");
                    phase[0] = 5;
                }
                case 5 -> {
                    if (Divers.jobOf(f) != null) return;
                    Kit.log("dv07 picked up at " + tick + ": carried " + carried(f, Items.TURTLE_SCUTE) + "; books scutes " + w.scutes + ", hatched " + w.hatched);
                    helper.assertTrue(carried(f, Items.TURTLE_SCUTE) >= 1 && w.hatched >= 1, "the scute in its pack, the hatchlings in the books");
                    put(f, t.store().getBlockPos().south());
                    helper.assertTrue(Divers.startForTests(f, level, Divers.Job.STORES), "off to the stores");
                    phase[0] = 6;
                }
                case 6 -> {
                    if (Divers.jobOf(f) != null) return;
                    int banked = stock(level, id, Items.TURTLE_SCUTE);
                    Kit.log("dv07 banked " + banked + " scute at " + tick);
                    helper.assertTrue(banked >= 1 && carried(f, Items.TURTLE_SCUTE) == 0, "the scute in the stores");
                    phase[0] = -1;
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    /** Is a nest closed off every way, within three blocks: a fence, a gate, a wall, or the water? */
    static boolean closedRound(ServerLevel level, BlockPos egg) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean closed = false;
            for (int k = 1; k <= 3 && !closed; k++) {
                BlockState st = level.getBlockState(egg.relative(d, k));
                if (st.is(net.minecraft.tags.BlockTags.FENCES) || st.getBlock() instanceof FenceGateBlock || st.isSolid()
                    || !st.getFluidState().isEmpty()) closed = true;
            }
            if (!closed) return false;
        }
        return true;
    }

    // ================================================================== dv08: the turtle helmet

    /**
     * Five scutes in the stores, a smith and a diver with no helmet. The smith makes a turtle helmet of them (the game's
     * recipe) into the stores; the diver draws it at the stores and puts it on. Under the water it has the helmet's ten
     * seconds of water breathing and does not use a breath; it comes up from a short dive with its lungs as full as it
     * went down. The shop has a place on its shelves for one, and the market knows its worth.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "dv08_helmet")
    public static void dv08_helmet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1374000;
        Town t = town(helper, x, 16, 90, StationTask.FARM, StationTask.NONE, StationTask.SMITH);
        UUID id = t.village();
        int top = lake(level, x + 30, Z - 9, 18, 18);
        Divers.Waterside w = Divers.surveyForTests(level, t.v());
        helper.assertTrue(w != null, "the lake found");
        fill(t, new ItemStack(Items.TURTLE_SCUTE, 5), new ItemStack(Items.BREAD, 32));
        Divers.ownWorkForTests(false);
        VillageFolkEntity f = diver(level, t, t.folk().get(1), new ItemStack(Items.KELP, 4));
        VillageFolkEntity smith = t.folk().get(2);
        for (int i = 0; i < 6 && stock(level, id, Items.TURTLE_HELMET) == 0; i++) Crafts.now(smith, level, t.v());
        Kit.log("dv08 the smith: helmets " + stock(level, id, Items.TURTLE_HELMET) + ", scutes left " + stock(level, id, Items.TURTLE_SCUTE)
            + "; books helmets " + w.helmets);
        helper.assertTrue(stock(level, id, Items.TURTLE_HELMET) == 1 && stock(level, id, Items.TURTLE_SCUTE) == 0 && w.helmets == 1,
            "a turtle helmet of the five scutes, into the stores");
        helper.assertTrue(Cafe.shopWares().stream().anyMatch(ware -> ware.is().test(new ItemStack(Items.TURTLE_HELMET)))
            && Market.goodFor(new ItemStack(Items.TURTLE_HELMET)) != null, "the shop sells them, at the market's worth");
        put(f, t.store().getBlockPos().south());
        helper.assertTrue(Divers.startForTests(f, level, Divers.Job.STORES), "off to the stores");
        List<BlockPos> spots = new ArrayList<>();
        for (int dz : new int[]{ -2, 0, 2 }) spots.add(new BlockPos(w.middle().getX() + 2, top - 2, w.middle().getZ() + dz));
        int[] phase = { 0 };
        int[] least = { 300 };
        boolean[] breathing = { false };
        helper.onEachTick(() -> {
            if (phase[0] < 0) return;
            keepAtWork(level, f);
            long tick = helper.getTick();
            if (tick % 50 == 0) Kit.log("dv08 @" + tick + " phase " + phase[0] + ": " + Divers.stateForTests(f) + "; head "
                + f.getItemBySlot(EquipmentSlot.HEAD) + "; least air " + least[0]);
            if (tick > 2200) {
                phase[0] = -1;
                helper.fail("stuck: " + Divers.stateForTests(f) + " — " + f.debugLine());
                return;
            }
            switch (phase[0]) {
                case 0 -> {
                    if (Divers.jobOf(f) != null) return;
                    helper.assertTrue(f.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET) && stock(level, id, Items.TURTLE_HELMET) == 0,
                        "the diver drew the helmet and wears it: " + f.getItemBySlot(EquipmentSlot.HEAD));
                    put(f, w.bank());
                    Divers.spotsForTests(f, level, Divers.Job.PLANT, spots);
                    phase[0] = 1;
                }
                case 1 -> {
                    if (f.isEyeInFluid(FluidTags.WATER)) {
                        least[0] = Math.min(least[0], f.getAirSupply());
                        if (f.hasEffect(MobEffects.WATER_BREATHING)) breathing[0] = true;
                    }
                    if (Divers.jobOf(f) != null) return;
                    int planted = 0;
                    for (BlockPos p : spots) if (level.getBlockState(p).is(Blocks.KELP)) planted++;
                    Kit.log("dv08 dived at " + tick + ": planted " + planted + "; water breathing " + breathing[0] + ", least air " + least[0]
                        + ", up for air " + w.surfaced + " times");
                    helper.assertTrue(planted == 3, "the dive done: " + planted);
                    helper.assertTrue(breathing[0] && least[0] >= DiverSwim.FULL_AIR, "the helmet's water breathing: not a breath used: " + least[0]);
                    phase[0] = -1;
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    // ================================================================== dv09: the rescue

    /**
     * A folk of the town at the bottom of the lake, eleven blocks out from every bank, too far to climb out (it cannot
     * swim: no thinking of its own). The diver on the bank sees it in trouble, swims out, takes hold of it, brings it in
     * with its head out of the water and sets it down on the bank, alive; the books, the chronicle and both their
     * memories have it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "dv09_rescue")
    public static void dv09_rescue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1376000;
        Town t = town(helper, x, 16, 90, StationTask.FARM, StationTask.NONE, StationTask.FARM);
        UUID id = t.village();
        int lx = x + 30, lz = Z - 10;
        int top = lake(level, lx, lz, 21, 21);
        Divers.Waterside w = Divers.surveyForTests(level, t.v());
        helper.assertTrue(w != null, "the lake found");
        Divers.ownWorkForTests(false);
        VillageFolkEntity f = diver(level, t, t.folk().get(1));
        put(f, new BlockPos(lx - 1, top + 1, lz + 10));
        VillageFolkEntity who = t.folk().get(2);
        who.setNoAi(true);
        who.moveTo(lx + 10.5, top - 2, lz + 10.5, 0.0F, 0.0F);
        float health = who.getHealth();
        int[] leastAir = { 300 };
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            keepAtWork(level, f);
            long tick = helper.getTick();
            leastAir[0] = Math.min(leastAir[0], who.getAirSupply());
            if (tick % 40 == 0) {
                Kit.log("dv09 @" + tick + ": diver " + Divers.stateForTests(f) + " at " + f.blockPosition().toShortString() + "; "
                    + who.displayNameCap() + " at " + who.blockPosition().toShortString() + " air " + who.getAirSupply() + ", health " + who.getHealth()
                    + ", towed " + Divers.towedForTests(who));
            }
            if (!who.isAlive()) {
                done[0] = true;
                helper.fail(who.displayNameCap() + " drowned: " + Divers.stateForTests(f));
                return;
            }
            if (w.rescued == 0 || Divers.jobOf(f) != null) {
                if (tick > 1500) {
                    done[0] = true;
                    helper.fail("nobody pulled out: " + Divers.stateForTests(f) + " — " + f.debugLine());
                }
                return;
            }
            done[0] = true;
            boolean told = false;
            for (Chronicle.Entry e : Chronicle.of(id)) if (e.text().contains("pulled " + who.displayNameCap())) told = true;
            Kit.log("dv09 rescued at " + tick + ": " + who.displayNameCap() + " at " + who.blockPosition().toShortString() + ", in the water "
                + who.isInWater() + ", health " + who.getHealth() + " of " + health + ", least air " + leastAir[0] + "; chronicle " + told);
            helper.assertTrue(!who.isInWater() && level.getFluidState(who.blockPosition()).isEmpty(), "set down on the bank, out of the water");
            helper.assertTrue(who.isAlive() && who.getHealth() > 0.0F, "alive");
            helper.assertTrue(w.rescued == 1 && told, "the books and the chronicle have it");
            helper.assertTrue(!Divers.towedForTests(who), "let go of");
            helper.succeed();
        });
    }

    // ================================================================== dv10: no water

    /**
     * A town of sixteen on dry land, no water within reach of it. It looks, and finds none; it wants no diver and takes
     * nobody, and its board's jobs say why. Left to its own day for a while, still nobody dives.
     */
    @GameTest(template = EMPTY, timeoutTicks = 800, batch = "dv10_dry")
    public static void dv10_dry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1378000;
        Town t = town(helper, x, 16, 130, StationTask.FARM, StationTask.NONE, StationTask.NONE);
        UUID id = t.village();
        Divers.Waterside w = Divers.surveyForTests(level, t.v());
        String line = Divers.jobsLine(id);
        Kit.log("dv10 the survey: " + (w == null ? "no water" : w.middle().toShortString()) + "; board: " + line + "; reach " + Villages.townReach(id));
        helper.assertTrue(w == null && Divers.wanted(id) == 0 && Villages.share(id, StationTask.DIVER) == 0.0, "no water, and no diver wanted");
        helper.assertTrue(Divers.appointForTests(level, t.v()) == null, "nobody taken on");
        helper.assertTrue(line != null && line.startsWith("No diver: no river, lake or sea"), "the board says why: " + line);
        boolean onBoard = false;
        for (String l : VillageBoards.compose(level, id)) if (l.contains("No diver")) onBoard = true;
        helper.assertTrue(onBoard, "on the board's jobs");
        helper.assertTrue(!Divers.shedWanted(id) && !Villages.projectsWanted(id).contains(Divers.SHED), "no shed wanted");
        helper.runAfterDelay(600, () -> {
            int divers = 0;
            for (VillageFolkEntity f : t.folk()) if (f.stationTask() == StationTask.DIVER) divers++;
            Kit.log("dv10 after a while: " + divers + " divers; " + Divers.jobsLine(id));
            helper.assertTrue(divers == 0 && Divers.divers(id).isEmpty(), "still nobody dives");
            helper.succeed();
        });
    }
}
