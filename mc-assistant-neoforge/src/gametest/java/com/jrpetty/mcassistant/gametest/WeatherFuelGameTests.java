package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Annals;
import com.jrpetty.mcassistant.entity.Couriers;
import com.jrpetty.mcassistant.entity.FireBrigade;
import com.jrpetty.mcassistant.entity.Fuel;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Links;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Storehouses;
import com.jrpetty.mcassistant.entity.Sweepers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Weather;
import com.jrpetty.mcassistant.entity.Woods;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.goal.SmeltGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.VillageMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Fuel, fire and weather: what the four hundred days' mountain town showed of its coal, its woods and
 * its roofs.
 *
 * <ul>
 * <li><b>wf01</b>: a Stone Age village short of the coal the age wants: two miners in three take their
 *     mines up to the coal seam (twelve under ground at a hundred: eighty-eight), the third stays at the
 *     iron; there they stay with the age's coal in the stores, and go back down with half as much again;
 *     its smelter burns spare logs into charcoal first and puts no coal on the fire, and the stores'
 *     torches are made of charcoal, the coal left alone.</li>
 * <li><b>wf02</b>: a woodcutter with no saplings, four trees felled in its wood: it shakes the crowns down
 *     and puts a sapling from the drops on every stump; given saplings to spare, it plants its open ground
 *     two blocks clear of each other and no thicker than its wood should be; and the town short of timber,
 *     it feeds its saplings the stores' bone meal.</li>
 * <li><b>wf03</b>: the meeting hall and the chapel get a lightning rod apiece on the highest point of the
 *     roof, three of the stores' copper ingots each; the bell tower waits for the copper and gets its own
 *     when it comes; never a second rod.</li>
 * <li><b>wf04</b>: a fire on a plank by the town's well: the nearest folk makes a bucket of three of the
 *     stores' iron, fills it at the water and puts the fire out; the town's books note the fire.</li>
 * <li><b>wf05</b>: in a snowy town, a courier between runs shovels the snow off the square and the street
 *     (not off a garden), with a wooden shovel made of two of the stores' planks, and banks the snowballs
 *     in the storehouse.</li>
 * <li><b>wf06</b>: a thunderstorm: a farmer out on its field goes indoors into the nearest of the town's
 *     buildings and waits there; the watch stays out; when the storm has passed, the farmer is back to
 *     its day.</li>
 * </ul>
 *
 * <p>Each on its own ground, x 410,000 to 416,000, z 50,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WeatherFuelGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    // ------------------------------------------------------------------ helpers

    /** A chest of the stores at this spot with these goods. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        return box;
    }

    /** Nothing in the village's stores: only what a test puts there. */
    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** Add these goods to a chest's empty slots. */
    private static void add(Container c, ItemStack... goods) {
        int g = 0;
        for (int i = 0; i < c.getContainerSize() && g < goods.length; i++) {
            if (c.getItem(i).isEmpty()) c.setItem(i, goods[g++]);
        }
        c.setChanged();
    }

    /** The ground at this column: under any trees, leaves, plants and snow. */
    private static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        while (y > level.getMinBuildHeight() + 1) {
            BlockState st = level.getBlockState(new BlockPos(x, y - 1, z));
            if (st.isAir() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.canBeReplaced() || !st.isSolid()) y--;
            else break;
        }
        return y;
    }

    /** A level square of grass {@code r} each way round this column, at this height, clear to the sky over it
     *  for twenty blocks; with {@code kerb}, a stone kerb round it to keep water out. */
    private static void lawn(ServerLevel level, int cx, int cz, int r, int y, boolean kerb) {
        int out = kerb ? r + 1 : r;
        for (int dx = -out; dx <= out; dx++) {
            for (int dz = -out; dz <= out; dz++) {
                int x = cx + dx, z = cz + dz;
                boolean edge = kerb && Math.max(Math.abs(dx), Math.abs(dz)) == r + 1;
                for (int dy = 0; dy <= 20; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
                for (int dy = -4; dy <= -2; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y - 1, z), (edge ? Blocks.STONE : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
                if (edge) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
    }

    /**
     * A stretch of the morning (before the midday put-away) this long in which this folk is at its work, not on
     * its break: the clock set to its start, which is returned; or the clock at 2000 and -1 if there is none.
     */
    private static long morningAtWork(ServerLevel level, VillageFolkEntity f, long span) {
        for (long t = 1500; t + span <= 5300; t += 100) {
            boolean ok = true;
            for (long k = t; k <= t + span && ok; k += 100) {
                level.setDayTime(k);
                ok = !f.offWorkNow();
            }
            if (ok) {
                level.setDayTime(t);
                return t;
            }
        }
        level.setDayTime(2000);
        return -1;
    }

    /** Two layers of snow on this column's ground (made {@code ground}), the column cleared over it. */
    private static BlockPos snowOn(ServerLevel level, int x, int z, net.minecraft.world.level.block.Block ground) {
        int y = groundY(level, x, z);
        for (int dy = 0; dy <= 20; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x, y - 1, z), ground.defaultBlockState(), 3);
        BlockPos at = new BlockPos(x, y, z);
        level.setBlock(at, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2), 3);
        return at;
    }

    // ============================================================ wf01: coal for the age

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wf01_coal_for_the_age")
    public static void wf01_coal_for_the_age(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);                                          // past the morning assembly
        final int x = 410300;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null && first.ownerId() != null, "a village");
        UUID id = first.ownerId();
        List<VillageFolkEntity> folk = new ArrayList<>();
        folk.add(first);
        for (int i = 1; i < 4; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 4 + 2 * i, Z + 3), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i + " of the village");
            folk.add(f);
        }
        Villages.ageForTests(id, Villages.Age.STONE);
        emptyStores(level, id);
        // Timber to spare, planks, bread; eight coal and four charcoal: twelve, against the thirty-two the age asks.
        Container stores = chestAt(level, Kit.surface(level, x + 4, Z - 4), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_LOG, 64),
            new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.BREAD, 32),
            new ItemStack(Items.COAL, 8), new ItemStack(Items.CHARCOAL, 4));
        Villages.forgetStock();
        int heads = Math.min(Villages.headcount(id), Villages.AGE_FOLK);
        int wanted = VillageMath.coalWanted(heads);
        boolean short0 = false;
        for (Villages.Need n : Villages.needs(level, id)) if (n.task() == Villages.Task.COAL) short0 = true;
        Kit.log("wf01 a Stone Age village of " + Villages.headcount(id) + ": " + Fuel.inStores(level, id) + " coal against " + wanted
            + " wanted; short " + short0);
        helper.assertTrue(short0 && Fuel.inStores(level, id) == 12, "short of the coal the age wants: " + Fuel.inStores(level, id) + " of " + wanted);

        // The smelter: charcoal first, out of the logs the builders can spare; no coal on the fire; the stores'
        // torches made of the charcoal, the coal left alone.
        VillageFolkEntity smelter = folk.get(3);
        smelter.setJob(StationTask.SMELT);
        helper.assertTrue(smelter.savingCoal(), "the smelter sees the village putting coal by for its age");
        helper.assertTrue(!SmeltGoal.burns(new ItemStack(Items.COAL), true) && !SmeltGoal.burns(new ItemStack(Items.CHARCOAL), true)
            && SmeltGoal.burns(new ItemStack(Items.OAK_LOG), true), "no coal on the fire while the age wants it; logs, yes");
        String torches = Links.torches(smelter, level);
        int coalLeft = Market.stock(level, id, s -> s.is(Items.COAL)), charcoalLeft = Market.stock(level, id, s -> s.is(Items.CHARCOAL));
        int torchesMade = Market.stock(level, id, s -> s.is(Items.TORCH));
        Kit.log("wf01 the stores' torches: " + torches + "; coal " + coalLeft + ", charcoal " + charcoalLeft + ", torches " + torchesMade);
        helper.assertTrue(coalLeft == 8 && charcoalLeft == 0 && torchesMade == 16,
            "the stores' torches made of the charcoal, the coal left alone: coal " + coalLeft + ", charcoal " + charcoalLeft + ", torches " + torchesMade);
        int logsBefore = Market.stock(level, id, s -> s.is(ItemTags.LOGS));
        boolean burning = smelter.burnCharcoal();
        int drawn = logsBefore - Market.stock(level, id, s -> s.is(ItemTags.LOGS));
        boolean smelting = false;
        for (Job j : smelter.queuedJobs()) if (j.type() == Job.Type.SMELT) smelting = true;
        Kit.log("wf01 the smelter: burning " + burning + ", drew " + drawn + " logs, a smelt queued " + smelting + " — " + smelter.debugLine());
        helper.assertTrue(burning && smelting && drawn > 0 && drawn <= 16, "charcoal out of the spare logs: drew " + drawn);

        // Three miners, their mines on ground at a hundred, down at the iron.
        int seam = Fuel.coalSeamFor(100, 16);
        helper.assertTrue(seam == 88 && Fuel.coalSeamFor(30, 16) == -1 && Fuel.coalSeamFor(170, 16) == 158,
            "the coal seam: twelve under the ground, at most ninety-six below the mountains' band; none worth it on low ground: "
            + seam + ", " + Fuel.coalSeamFor(30, 16) + ", " + Fuel.coalSeamFor(170, 16));
        List<VillageFolkEntity> miners = folk.subList(0, 3);
        for (int i = 0; i < miners.size(); i++) {
            VillageFolkEntity m = miners.get(i);
            m.setJob(StationTask.MINE);
            m.assignPlot(WorkZone.around(new BlockPos(x + 20 + 20 * i, 100, Z + 20), 6, 16), "Pit " + i);
        }
        int atCoal = 0, atIron = 0;
        for (VillageFolkEntity m : miners) {
            m.seekTheSeamForTests();
            int d = m.workZone().depth();
            if (d == seam) atCoal++;
            else if (d == 16) atIron++;
            Kit.log("wf01 short: " + m.getAssistantName() + " mine at Y" + d);
        }
        helper.assertTrue(atCoal == 2 && atIron == 1, "short of coal: two miners in three up to the coal seam, one at the iron: " + atCoal + "/" + atIron);

        // The age's coal in the stores: the mines at the coal stay there (the stores swing about the mark).
        add(stores, new ItemStack(Items.COAL, wanted - 8));
        Villages.forgetStock();
        helper.assertTrue(Fuel.inStores(level, id) == wanted, "the age's coal in the stores: " + Fuel.inStores(level, id));
        int stayed = 0;
        for (VillageFolkEntity m : miners) {
            boolean was = m.workZone().depth() == seam;
            m.seekTheSeamForTests();
            if (was && m.workZone().depth() == seam) stayed++;
            Kit.log("wf01 at the mark: " + m.getAssistantName() + " mine at Y" + m.workZone().depth());
        }
        helper.assertTrue(stayed == 2, "at the age's mark the coal mines stay at the coal: " + stayed);

        // Half as much again: back down to the iron, all of them.
        add(stores, new ItemStack(Items.COAL, wanted));
        Villages.forgetStock();
        int down = 0;
        for (VillageFolkEntity m : miners) {
            m.seekTheSeamForTests();
            if (m.workZone().depth() == 16) down++;
            Kit.log("wf01 plenty (" + Fuel.inStores(level, id) + "): " + m.getAssistantName() + " mine at Y" + m.workZone().depth());
        }
        helper.assertTrue(down == 3, "with coal to spare every mine goes back down to the iron: " + down);
        helper.succeed();
    }

    // ============================================================ wf02: the woods kept growing

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "wf02_woods_kept")
    public static void wf02_woods_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(true, level.getServer());   // leaves drop what they drop
        level.setDayTime(2000);
        final int x = 411000;
        Kit.hold(level, x, Z, 90);
        Kit.prepare(level, x, Z, 90);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        UUID id = f.ownerId();
        emptyStores(level, id);
        // The wood: a level lawn sixty-four out, a plot of twenty-one across on it.
        final int px = x + 64;
        final int y = groundY(level, px, Z);
        lawn(level, px, Z, 13, y, true);
        BlockPos centre = new BlockPos(px, y, Z);
        f.setJob(StationTask.WOOD);
        f.assignPlot(WorkZone.around(centre, 10, WorkZone.DEFAULT_DEPTH), "The Wood");
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.STONE_AXE));
        f.insertItem(new ItemStack(Items.BREAD, 16));
        f.moveTo(px + 0.5, y, Z + 0.5, 0.0F, 0.0F);
        // Four trees, felled: the logs gone, the crowns left hanging, the stumps on the woodcutter's books.
        final List<BlockPos> stumps = new ArrayList<>();
        for (int[] o : new int[][]{ {-4, -4}, {4, -4}, {-4, 4}, {4, 4} }) {
            Kit.wildTree(level, px + o[0], Z + o[1]);
            BlockPos stump = new BlockPos(px + o[0], y, Z + o[1]);
            for (int i = 0; i < 5; i++) level.setBlock(stump.above(i), Blocks.AIR.defaultBlockState(), 3);
            stumps.add(stump);
            Woods.felledForTests(f, stump);
        }
        helper.assertTrue(f.countCarried(s -> s.is(ItemTags.SAPLINGS)) == 0, "no saplings in its pack");
        // The morning, at its work and not on its break, the clock kept there.
        final long from = morningAtWork(level, f, 1000);
        Kit.log("wf02 the woodcutter's morning from " + from);
        final int[] stage = { 0 }, mark = { 0, 0, 0, 0 };
        final long[] since = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            long tod = level.getDayTime() % 24000L;
            if (from >= 0 && tod > from + 1000) level.setDayTime(from);
            else if (from < 0 && tod > 5000) level.setDayTime(2000);
            int planted = 0;
            for (BlockPos s : stumps) if (level.getBlockState(s).is(BlockTags.SAPLINGS)) planted++;
            int carried = f.countCarried(s -> s.is(ItemTags.SAPLINGS));
            int[] tally = Woods.tally(f), ground = Woods.groundForTests(f, level);
            if (t % 200 == 0) {
                Kit.log("wf02 @" + t + " stage " + stage[0] + ": stumps planted " + planted + "/4, carried " + carried + ", tally (drops/planted/fed) "
                    + tally[0] + "/" + tally[1] + "/" + tally[2] + ", wood (trunks/saplings/wanted) " + ground[0] + "/" + ground[1] + "/" + ground[2]
                    + ", errand " + Woods.errandForTests(f) + " — " + f.debugLine());
            }
            if (t < 20) return;                                          // the crowns learn they have no trunk
            if (t % 10 == 0) Woods.tendForTests(f, level);
            switch (stage[0]) {
                case 0 -> {
                    // Saplings from the drops, one on every stump.
                    int stores = Market.stock(level, id, s -> s.is(ItemTags.SAPLINGS));
                    if (planted == 4) {
                        Kit.log("wf02 every stump has its sapling by " + t + ": from the drops " + tally[0] + ", carried " + carried
                            + ", the stores' saplings " + stores);
                        helper.assertTrue(stores == 0, "none out of the stores (they had none): " + stores);
                        stage[0] = 1;
                        since[0] = t;
                        // Saplings to spare, as a player might hand over: the open ground planted.
                        f.insertItem(new ItemStack(Items.OAK_SAPLING, 16));
                        mark[0] = f.countCarried(s -> s.is(ItemTags.SAPLINGS));
                        mark[1] = ground[0] + ground[1];                 // (a sapling that grows is a tree: counted together)
                        mark[2] = mark[1];
                    } else if (t >= 2000) {
                        // Leaves give a sapling one time in twenty: should the four crowns have given fewer than four, every one
                        // they gave is on a stump.
                        boolean crownsLeft = false;
                        for (BlockPos s : stumps) {
                            for (BlockPos p : BlockPos.betweenClosed(s.offset(-3, 0, -3), s.offset(3, 7, 3))) {
                                if (level.getBlockState(p).getBlock() instanceof LeavesBlock) { crownsLeft = true; break; }
                            }
                        }
                        if (tally[0] < 4 && !crownsLeft && carried == 0 && planted >= tally[0]) {
                            Kit.log("wf02 the crowns gave only " + tally[0] + " saplings; every one went on a stump (" + planted + ")");
                            helper.succeed();
                            return;
                        }
                        helper.fail("not every stump planted: " + planted + "/4, from the drops " + tally[0] + ", carried " + carried
                            + ", crowns left " + crownsLeft + ", errand " + Woods.errandForTests(f) + " — " + f.debugLine());
                    }
                }
                case 1 -> {
                    // The open ground, two blocks clear, no thicker than the wood should be, and only the saplings to spare.
                    if (ground[0] + ground[1] != mark[2]) {
                        mark[2] = ground[0] + ground[1];
                        since[0] = t;
                    }
                    if (t - since[0] < 300 && t < 3000) return;
                    int open = ground[0] + ground[1] - mark[1];
                    List<BlockPos> all = saplingsIn(level, f.workZone());
                    int crowded = 0;
                    for (int i = 0; i < all.size(); i++) {
                        for (int j = i + 1; j < all.size(); j++) {
                            BlockPos a = all.get(i), b = all.get(j);
                            if (Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ())) <= 2) crowded++;
                        }
                    }
                    Kit.log("wf02 the open ground: " + open + " planted, carried " + carried + " (had " + mark[0] + "), wood "
                        + ground[0] + "/" + ground[1] + "/" + ground[2] + ", crowded pairs " + crowded);
                    helper.assertTrue(open >= 8, "saplings to spare planted on the open ground: " + open);
                    helper.assertTrue(carried >= Woods.KEEP, "the stumps' saplings kept back: " + carried);
                    helper.assertTrue(ground[0] + ground[1] <= ground[2], "no thicker than the wood should be: " + (ground[0] + ground[1]) + " of " + ground[2]);
                    helper.assertTrue(crowded == 0, "two blocks clear of each other: " + crowded + " crowded pairs");
                    // Short of timber (no logs at all in the stores): the stores' bone meal on the saplings.
                    chestAt(level, Kit.surface(level, x - 3, Z + 3), new ItemStack(Items.BONE_MEAL, 6));
                    Villages.forgetStock();
                    mark[3] = f.countCarried(s -> s.is(Items.BONE_MEAL));
                    stage[0] = 2;
                    since[0] = t;
                }
                default -> {
                    int fed = tally[2];
                    int inStores = Market.stock(level, id, s -> s.is(Items.BONE_MEAL));
                    int meal = f.countCarried(s -> s.is(Items.BONE_MEAL));
                    if (fed >= 1) {
                        Kit.log("wf02 fed " + fed + " saplings; bone meal carried " + meal + ", in the stores " + inStores);
                        helper.assertTrue(meal + fed + inStores == 6 + mark[3], "nothing from nothing: " + meal + " carried + " + fed + " fed + "
                            + inStores + " in the stores of " + (6 + mark[3]));
                        helper.succeed();
                    } else if (t - since[0] > 900) {
                        helper.fail("the saplings were not fed: bone meal in the stores " + inStores + ", carried " + meal + ", errand "
                            + Woods.errandForTests(f) + " — " + f.debugLine());
                    }
                }
            }
        });
    }

    private static List<BlockPos> saplingsIn(ServerLevel level, WorkZone z) {
        List<BlockPos> out = new ArrayList<>();
        if (z == null) return out;
        for (int x = z.min().getX(); x <= z.max().getX(); x++) {
            for (int zz = z.min().getZ(); zz <= z.max().getZ(); zz++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zz);
                BlockPos p = new BlockPos(x, y, zz);
                if (level.getBlockState(p).is(BlockTags.SAPLINGS)) out.add(p);
            }
        }
        return out;
    }

    // ============================================================ wf03: lightning rods

    /** A building of so many blocks high, its roof peaking one higher at this offset from its middle, on cleared ground. */
    private static BlockPos raiseBuilding(ServerLevel level, UUID id, String name, int cx, int cz, int high, int peakDx) {
        BlockPos anchor = Kit.surface(level, cx, cz);
        int[] half = BuildGoal.footprint(name);
        int r = Math.max(half[0], half[1]) + 2;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 0; dy <= 40; dy++) level.setBlock(anchor.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(anchor.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy < high; dy++) level.setBlock(anchor.offset(dx, dy, dz), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        level.setBlock(anchor.offset(peakDx, high, 0), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        Ledger.built(id, name, anchor, Direction.NORTH);
        return anchor.offset(peakDx, high + 1, 0);
    }

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "wf03_lightning_rods")
    public static void wf03_lightning_rods(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        final int x = 411700;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        UUID id = f.ownerId();
        emptyStores(level, id);
        BlockPos hallRod = raiseBuilding(level, id, "hall", x + 16, Z, 10, 1);
        BlockPos chapelRod = raiseBuilding(level, id, "chapel", x - 16, Z, 7, -1);
        // Seven copper ingots: two rods' worth and one over.
        Container stores = chestAt(level, Kit.surface(level, x + 3, Z + 4), new ItemStack(Items.COPPER_INGOT, 7));
        Villages.forgetStock();
        int put = Weather.rodsForTests(level, id);
        int copper = Market.stock(level, id, s -> s.is(Items.COPPER_INGOT));
        Kit.log("wf03 rods put up: " + put + "; the hall's peak " + level.getBlockState(hallRod) + ", the chapel's " + level.getBlockState(chapelRod)
            + "; copper left " + copper);
        helper.assertTrue(level.getBlockState(hallRod).is(Blocks.LIGHTNING_ROD), "a rod on the highest point of the hall's roof: " + level.getBlockState(hallRod));
        helper.assertTrue(level.getBlockState(chapelRod).is(Blocks.LIGHTNING_ROD), "and of the chapel's: " + level.getBlockState(chapelRod));
        helper.assertTrue(put == 2 && copper == 1, "three of the stores' copper ingots a rod: " + put + " rods, " + copper + " copper left");
        // The bell tower, with one ingot in the stores: it waits.
        BlockPos towerRod = raiseBuilding(level, id, "belltower", x, Z + 18, 12, 0);
        int none = Weather.rodsForTests(level, id);
        helper.assertTrue(none == 0 && !level.getBlockState(towerRod).is(Blocks.LIGHTNING_ROD)
                && Market.stock(level, id, s -> s.is(Items.COPPER_INGOT)) == 1,
            "no rod of nothing: the bell tower waits for its copper: " + none);
        // The copper comes: the bell tower gets its rod, and only the bell tower.
        add(stores, new ItemStack(Items.COPPER_INGOT, 3));
        int more = Weather.rodsForTests(level, id);
        copper = Market.stock(level, id, s -> s.is(Items.COPPER_INGOT));
        int again = Weather.rodsForTests(level, id);
        int rods = 0;
        for (BlockPos p : BlockPos.betweenClosed(heart.offset(-30, -4, -30), heart.offset(30, 30, 30))) {
            if (level.getBlockState(p).is(Blocks.LIGHTNING_ROD)) rods++;
        }
        Kit.log("wf03 the bell tower: " + more + " put up, copper left " + copper + "; again " + again + "; rods in the town " + rods);
        helper.assertTrue(more == 1 && level.getBlockState(towerRod).is(Blocks.LIGHTNING_ROD) && copper == 1,
            "the bell tower's rod, of its three ingots: " + more + ", copper left " + copper);
        helper.assertTrue(again == 0 && rods == 3, "one rod a building, never a second: " + again + " more, " + rods + " in all");
        helper.succeed();
    }

    // ============================================================ wf04: the fire brigade

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "wf04_fire_brigade")
    public static void wf04_fire_brigade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        final int x = 412400;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : Kit.surface(level, x - 2, Z - 2 + 2 * i), 0.0F);
            helper.assertTrue(f != null && f.ownerId() != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i + " of a village");
            f.removeMatching(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET), 64);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        emptyStores(level, id);
        // Three iron in the stores and no bucket; a pond; a plank alight beside it, the fire held where it is.
        chestAt(level, Kit.surface(level, x - 3, Z + 3), new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.BREAD, 16));
        Villages.forgetStock();
        int gy = heart.getY();
        lawn(level, x + 9, Z, 4, gy, false);
        Kit.pond(level, x + 7, Z - 2, 1);
        BlockPos plank = new BlockPos(x + 11, gy, Z + 2);
        level.setBlock(plank, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        BlockPos fire = plank.above();
        level.setBlock(fire, BaseFireBlock.getState(level, fire), 3);
        helper.assertTrue(level.getBlockState(fire).is(BlockTags.FIRE), "a fire: " + level.getBlockState(fire));
        helper.assertTrue(FireBrigade.oursForTests(level, id, fire), "on the town's own blocks");
        int firesBefore = Annals.fires(id);
        FireBrigade.watchForTests(level, id);
        int[] seen = FireBrigade.townForTests(id);
        Kit.log("wf04 fire at " + fire.toShortString() + ": " + java.util.Arrays.toString(seen) + " (fires, blocks, hands)");
        helper.assertTrue(seen[0] == 1 && seen[1] == 1 && seen[2] == 1, "one fire seen, one hand sent: " + java.util.Arrays.toString(seen));
        // The fire held where it is (no spreading, no burning out of itself) while the test runs; put back after.
        var fireTick = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        final boolean was = fireTick.get();
        fireTick.set(false, level.getServer());
        final long[] outAt = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000L > 10000) level.setDayTime(2000);
            boolean burning = level.getBlockState(fire).is(BlockTags.FIRE);
            if (t % 100 == 0) {
                StringBuilder sb = new StringBuilder();
                for (VillageFolkEntity f : folk) sb.append(" | ").append(FireBrigade.onIt(f) ? "AT THE FIRE " : "").append(f.debugLine());
                Kit.log("wf04 @" + t + " burning " + burning + ", iron in the stores " + Market.stock(level, id, s -> s.is(Items.IRON_INGOT)) + sb);
            }
            if (!burning && outAt[0] < 0) {
                outAt[0] = t;
                FireBrigade.watchForTests(level, id);                   // the books closed on it
            }
            if (outAt[0] >= 0 && t >= outAt[0] + 2) {
                fireTick.set(was, level.getServer());
                int buckets = Market.stock(level, id, s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET));
                int water = Market.stock(level, id, s -> s.is(Items.WATER_BUCKET));
                for (VillageFolkEntity f : folk) {
                    buckets += f.countCarried(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET));
                    water += f.countCarried(s -> s.is(Items.WATER_BUCKET));
                }
                int iron = Market.stock(level, id, s -> s.is(Items.IRON_INGOT));
                List<String> log = Annals.fireLog(id);
                String last = log.isEmpty() ? "" : log.get(log.size() - 1);
                Kit.log("wf04 out by " + outAt[0] + ": iron left " + iron + ", buckets carried " + buckets + " (" + water + " of water); books: "
                    + Annals.fires(id) + " fires, last: " + last);
                helper.assertTrue(iron == 0 && buckets == 1, "a bucket made of the stores' three iron: iron " + iron + ", buckets " + buckets);
                helper.assertTrue(Annals.fires(id) == firesBefore + 1 && last.contains("put out by"), "the town's books note the fire: " + last);
                helper.assertTrue(last.contains("bucket of water") && water == 1, "put out with water from the pond: " + last + " (" + water + ")");
                helper.succeed();
            } else if (t >= 1500) {
                fireTick.set(was, level.getServer());
                helper.fail("the fire was not put out: burning " + burning + ", " + java.util.Arrays.toString(FireBrigade.townForTests(id)));
            }
        });
    }

    // ============================================================ wf05: the snow off the streets

    /** A Village Storehouse (twenty-seven units, the door to the south) this far from the heart. */
    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int ux = 0; ux < 3; ux++) for (int uz = 0; uz < 3; uz++) {
                level.setBlock(origin.offset(ux, y, uz), unit, 3);
            }
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands: " + level.getBlockState(door));
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    /** Set the clock to a time from which this folk is at its work for the next {@code span} ticks. */
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

    @GameTest(template = EMPTY, timeoutTicks = 4800, batch = "wf05_snow_cleared")
    public static void wf05_snow_cleared(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        final int x = 413100;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity courier = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(courier != null, "a village");
        UUID id = courier.ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(id);
        courier.setJob(StationTask.HAUL);
        BlockPos spot = Storehouses.standingSpot(level, id);
        helper.assertTrue(spot != null && Couriers.employed(courier), "the courier is the storehouse's: " + spot);
        courier.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.0F, 0.0F);
        courier.removeMatching(s -> s.is(ItemTags.SHOVELS), 64);
        helper.assertTrue(atWorkFor(level, courier, 4400), "a time of day the courier is at work");
        add(store, new ItemStack(Items.OAK_PLANKS, 4));
        Sweepers.snowyForTests(true);
        // Snow on the square and the avenue (paved, so the town's works leave the ground be), and on a garden.
        List<BlockPos> street = new ArrayList<>();
        for (int[] o : new int[][]{ {-6, -6}, {-9, -2}, {-4, -10}, {5, -8}, {0, 16} }) {
            street.add(snowOn(level, x + o[0], Z + o[1], Blocks.COBBLESTONE));
        }
        BlockPos garden = snowOn(level, x + 22, Z + 22, Blocks.GRASS_BLOCK);
        for (BlockPos p : street) {
            Kit.log("wf05 snow at " + p.toShortString() + ": " + level.getBlockState(p) + ", block light " + level.getBrightness(LightLayer.BLOCK, p));
        }
        int[] snow = Sweepers.snowForTests(level, id);
        Kit.log("wf05 a snowy town: " + java.util.Arrays.toString(snow) + " (patches lying, layers cleared, snowballs)");
        helper.assertTrue(snow[0] == street.size(), "the snow on the streets seen, not the garden's: " + snow[0] + " of " + street.size());
        helper.onEachTick(() -> {
            long t = helper.getTick();
            // Its daily break (a minute or two, three for an easygoing one) is skipped: this is the sweeping's pace.
            if (courier.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
            int lying = 0;
            for (BlockPos p : street) if (level.getBlockState(p).is(Blocks.SNOW)) lying++;
            int balls = count(store, s -> s.is(Items.SNOWBALL));
            int carried = courier.countCarried(s -> s.is(Items.SNOWBALL));
            if (t % 200 == 0) {
                Kit.log("wf05 @" + t + ": " + lying + " patches lying, snowballs in the storehouse " + balls + ", carried " + carried
                    + ", " + java.util.Arrays.toString(Sweepers.snowForTests(level, id)) + "; doing: " + Couriers.doing(courier) + " — " + courier.debugLine());
            }
            if (lying == 0 && carried == 0 && balls > 0) {
                int[] now = Sweepers.snowForTests(level, id);
                int planks = count(store, s -> s.is(ItemTags.PLANKS));
                boolean shovel = courier.countCarried(s -> s.is(Items.WOODEN_SHOVEL)) + count(store, s -> s.is(Items.WOODEN_SHOVEL)) == 1;
                Kit.log("wf05 the streets clear by " + t + ": " + java.util.Arrays.toString(now) + ", snowballs in the storehouse " + balls
                    + ", planks " + planks + ", a wooden shovel " + shovel + "; the garden " + level.getBlockState(garden));
                Sweepers.snowyForTests(null);
                helper.assertTrue(level.getBlockState(garden).is(Blocks.SNOW), "the garden's snow left where it lies: " + level.getBlockState(garden));
                helper.assertTrue(planks == 2 && shovel, "a wooden shovel of two of the stores' planks: planks " + planks + ", shovel " + shovel);
                helper.assertTrue(now[1] >= 2 * street.size() && balls == now[2], "the layers cleared and the snowballs banked: "
                    + java.util.Arrays.toString(now) + ", in the storehouse " + balls);
                helper.succeed();
            } else if (t >= 4600) {
                Sweepers.snowyForTests(null);
                helper.fail("the snow was not cleared: " + lying + " patches lying, snowballs in the storehouse " + balls + ", carried " + carried
                    + "; doing " + Couriers.doing(courier) + " — " + courier.debugLine());
            }
        });
    }

    // ============================================================ wf06: in out of the storm

    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "wf06_storm_shelter")
    public static void wf06_storm_shelter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        final int x = 413800;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity farmer = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(farmer != null && farmer.ownerId() != null, "a village");
        UUID id = farmer.ownerId();
        VillageFolkEntity guard = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 2, Z + 3), 0.0F);
        helper.assertTrue(guard != null && id.equals(guard.ownerId()), "a second folk");
        // A stone hut ten blocks east of the heart, its door to the west, on level ground: the granary.
        int gy = heart.getY();
        int hx = x + 10;
        lawn(level, hx, Z, 4, gy, false);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int dy = 0; dy < 3; dy++) {
                    boolean door = dx == -2 && dz == 0 && dy < 2;
                    if (wall && !door) level.setBlock(new BlockPos(hx + dx, gy + dy, Z + dz), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                }
                level.setBlock(new BlockPos(hx + dx, gy + 3, Z + dz), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            }
        }
        BlockPos anchor = new BlockPos(hx, gy, Z);
        Ledger.built(id, "granary", anchor, Direction.NORTH);
        helper.assertTrue(Weather.roofedForTests(level, anchor) && !Weather.roofedForTests(level, anchor.offset(-4, 0, 0)),
            "under the hut's roof, and not outside it");
        // The farmer out on its field to the west, the guard on the street.
        farmer.setJob(StationTask.FARM);
        BlockPos field = Kit.surface(level, x - 14, Z);
        farmer.assignPlot(WorkZone.around(field, 4, WorkZone.DEFAULT_DEPTH), "Storm Field");
        farmer.moveTo(field.getX() + 0.5, field.getY(), field.getZ() + 0.5, 0.0F, 0.0F);
        guard.setJob(StationTask.GUARD);
        guard.insertItem(new ItemStack(Items.IRON_SWORD));
        BlockPos beat = Kit.surface(level, x - 6, Z + 6);
        guard.moveTo(beat.getX() + 0.5, beat.getY(), beat.getZ() + 0.5, 0.0F, 0.0F);
        Weather.stormForTests(true);
        final long[] in = { -1 }, over = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000L > 9000) level.setDayTime(2000);
            BlockPos at = farmer.blockPosition();
            boolean roofed = Weather.roofedForTests(level, at);
            double d = Math.sqrt(at.distSqr(anchor));
            if (t % 100 == 0) {
                Kit.log("wf06 @" + t + (over[0] >= 0 ? " (storm over)" : " (storm)") + ": farmer sheltering " + Weather.sheltering(farmer) + ", roofed "
                    + roofed + ", " + String.format("%.1f", d) + " from the hut — " + farmer.debugLine() + " | guard sheltering "
                    + Weather.sheltering(guard) + " — " + guard.debugLine());
            }
            if (over[0] < 0 && Weather.sheltering(guard)) {
                Weather.stormForTests(null);
                helper.fail("the watch went indoors: " + guard.debugLine());
                return;
            }
            if (over[0] < 0) {
                if (in[0] < 0 && roofed && d <= 3.0 && Weather.sheltering(farmer)) {
                    in[0] = t;
                    Kit.log("wf06 the farmer is in by " + t + " — " + farmer.debugLine());
                }
                if (in[0] >= 0 && t - in[0] >= 120) {
                    if (!(roofed && d <= 4.0 && Weather.sheltering(farmer))) {
                        Weather.stormForTests(null);
                        helper.fail("it did not wait the storm out indoors: roofed " + roofed + ", " + String.format("%.1f", d)
                            + " from the hut, sheltering " + Weather.sheltering(farmer) + " — " + farmer.debugLine());
                        return;
                    }
                    Kit.log("wf06 still in at " + t + " (job " + farmer.peekJob() + "): the storm passes");
                    Weather.stormForTests(false);
                    over[0] = t;
                }
            } else if (!Weather.sheltering(farmer)) {
                Kit.log("wf06 the storm over at " + over[0] + ", the farmer back to its day by " + t + " — " + farmer.debugLine());
                Weather.stormForTests(null);
                helper.succeed();
                return;
            }
            if (t >= 1700) {
                Weather.stormForTests(null);
                helper.fail("the farmer did not get in out of the storm: sheltering " + Weather.sheltering(farmer) + ", roofed " + roofed + ", "
                    + String.format("%.1f", d) + " from the hut — " + farmer.debugLine());
            }
        });
    }
}
