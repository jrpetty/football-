package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.MineGround;
import com.jrpetty.mcassistant.entity.MineLevels;
import com.jrpetty.mcassistant.entity.MineSites;
import com.jrpetty.mcassistant.entity.MineWorks;
import com.jrpetty.mcassistant.entity.SmelterFuel;
import com.jrpetty.mcassistant.entity.StoreRoom;
import com.jrpetty.mcassistant.entity.SupplyLean;
import com.jrpetty.mcassistant.entity.ToolTrade;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.TownMine;
import com.jrpetty.mcassistant.entity.TradeBook;
import com.jrpetty.mcassistant.entity.TradeTalks;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.CropPlan;
import com.jrpetty.mcassistant.entity.Drover;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.FirmTrades;
import com.jrpetty.mcassistant.entity.InputWants;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MarketMaker;
import com.jrpetty.mcassistant.entity.PriceIndex;
import com.jrpetty.mcassistant.entity.Purchases;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [supply] The town's supply chains: the fields sown by what pays, the makings a firm wants reaching the producers who can
 * get them (honeycomb, string, hides), and the town's own makers stepping back from a trade a firm of its folk has taken
 * up; and a household that tires of roots and buys a loaf.
 *
 * <p>Each on its own ground, x 3100000 to 3199999 (2000 apart), z 90000, in a batch of its own.
 *
 * <p>[econ32] And the chains from source to use (sc01 to sc06, x 6,600,000 to 6,620,000): a smelter with ore and no fuel
 * fetches the stores' logs and smelts (SmelterFuel); a town whose picks are gone and that has no iron buys iron tools from a
 * town whose smith made some to sell (ToolTrade); a mine worked out to the eighth ring of its ground opens a new mine
 * (MineSites); a level gone barren is worked out (MineLevels.barren); a hungry town wants more farmers; stores filling up get
 * another chest before they are full (StoreRoom).
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SupplyChainGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    // ============================================================ the ground

    /** A town of so many folk at their trades, every purse set to {@code purse}; the market at work and open, the Stone Age. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int purse, StationTask... trades) {
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        Enterprise.resetForTests();
        Enterprise.autoForTests(false);
        level.setDayTime(8 * 24000L + 7000L);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 6 + (i % 6) * 2, Z - 4 + (i / 6) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(trades[i]);
            f.spend(f.purse());
            f.earn(purse);
            Values.setForTests(f, Values.Value.TRADITION, 100);
            Values.setForTests(f, Values.Value.WEALTH, 0);
            Values.setForTests(f, Values.Value.PROGRESS, 0);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        Ledger.addCoins(id, 300);
        MarketMaker.openForTests(level, Villages.get(id));
        return folk;
    }

    /** A chest of the town's stores by its heart, holding these; every other chest of the stores emptied. */
    private static Container stores(ServerLevel level, UUID village, int x, ItemStack... goods) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        BlockPos at = Kit.surface(level, x + 8, Z + 8);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, i < goods.length ? goods[i].copy() : ItemStack.EMPTY);
        c.setChanged();
        Villages.forgetStores(village);
        Villages.forgetStock();
        return c;
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    // ============================================================ sc2_01 the fields follow the price

    /**
     * A farmer's fields, three in four of them potatoes, cut and sown back harvest after harvest: with wheat dear (a bakery's
     * call has run it to two and a half times its usual) and potatoes in a glut, the town's plan gives wheat the most ground,
     * and the field turns over to wheat a harvest at a time; with the prices the other way round, it turns back. Never a crop
     * lost for good: each keeps a tenth of the ground in the plan.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sc2_01_crops_follow_price")
    public static void sc2_01_crops_follow_price(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3102000;
        List<VillageFolkEntity> folk = town(helper, level, x, 10, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity farmer = folk.get(0);
        UUID id = farmer.ownerId();
        Kit.Expect ok = new Kit.Expect();
        java.util.function.BiFunction<Double, Double, Double> run = (wheat, roots) -> {
            CropPlan.resetForTests();
            PriceIndex.setForTests(id, new ItemStack(Items.WHEAT), wheat);
            for (Item it : new Item[]{ Items.POTATO, Items.CARROT, Items.BEETROOT }) PriceIndex.setForTests(id, new ItemStack(it), roots);
            Map<Item, Double> plan = CropPlan.plan(level, id);
            Kit.log("sc2_01 wheat x" + wheat + ", roots x" + roots + ": the plan " + CropPlan.line(level, id));
            // A field of forty: thirty potatoes, ten wheat; four hundred harvests, a block at a time.
            List<Item> field = new ArrayList<>();
            for (int i = 0; i < 40; i++) field.add(i < 30 ? Items.POTATO : Items.WHEAT_SEEDS);
            Random r = new Random(7);
            for (int h = 0; h < 400; h++) {
                int i = r.nextInt(field.size());
                field.set(i, CropPlan.sowBack(farmer, field.get(i), it -> true, it -> true));
            }
            int wheatNow = 0;
            for (Item it : field) if (it == Items.WHEAT_SEEDS) wheatNow++;
            Kit.log("sc2_01 the field after 400 harvests: wheat " + wheatNow + " of 40; plan wheat " + Math.round(plan.getOrDefault(Items.WHEAT_SEEDS, 0.0) * 100) + "%");
            for (Map.Entry<Item, Double> e : plan.entrySet()) ok.that(e.getValue() >= 0.099, "every crop keeps a tenth of the ground: " + e);
            return wheatNow / 40.0;
        };
        double dear = run.apply(2.5, 0.5);
        ok.that(dear >= 0.45, "wheat dear and roots in a glut: the field turned over to wheat (" + Math.round(dear * 100) + "%, from 25%)");
        double cheap = run.apply(0.5, 2.0);
        ok.that(cheap <= 0.2, "wheat cheap and roots dear: the field went to roots (" + Math.round(cheap * 100) + "% wheat)");
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ sc2_02 the town leaves the baking to the bakery

    /**
     * The café's cook, with wheat in the stores, bakes the town's bread; once a bakery of the town's own folk is baking (bread
     * on its shelf), the cook bakes none for the counter and the wheat is left for the bakery to buy; the idle hands' baking
     * stands down too. A bakery that has let its trade go (none held and none made for two days) and the cook bakes again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sc2_02_steps_back_bread")
    public static void sc2_02_steps_back_bread(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3104000;
        List<VillageFolkEntity> folk = town(helper, level, x, 20, StationTask.COOK, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity cook = folk.get(0);
        UUID id = cook.ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, new ItemStack(Items.WHEAT, 30), new ItemStack(Items.CRAFTING_TABLE));
        Kit.Expect ok = new Kit.Expect();
        String first = Cafe.cook(level, v, cook);
        int bread = stock(level, id, s -> s.is(Items.BREAD));
        Kit.log("sc2_02 no bakery: the cook " + first + "; bread " + bread);
        ok.that(bread >= 1, "with no bakery the town's cook bakes the bread: " + bread);
        Business bakery = Enterprise.foundForTests(level, v, "baker", folk.get(1));
        helper.assertTrue(bakery != null, "a bakery opened");
        Business.Lot lot = new Business.Lot();
        lot.qty = 4;
        lot.cost = 400;
        bakery.stock.put("minecraft:bread", lot);
        FirmTrades.offForTests(false);                                          // (the look at the firms' trades afresh)
        ok.that(FirmTrades.leftTo(level, id, "minecraft:bread"), "bread is the bakery's trade now");
        int wheatBefore = stock(level, id, s -> s.is(Items.WHEAT));
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String m = Cafe.cook(level, v, cook);
            if (m != null) made.add(m);
        }
        int breadAfter = stock(level, id, s -> s.is(Items.BREAD));
        int wheatAfter = stock(level, id, s -> s.is(Items.WHEAT));
        Kit.log("sc2_02 with the bakery baking: the cook " + made + "; bread " + bread + " -> " + breadAfter + ", wheat " + wheatBefore + " -> " + wheatAfter);
        ok.that(breadAfter == bread && wheatAfter == wheatBefore, "the cook baked none for the counter: bread " + bread + " -> " + breadAfter
            + ", wheat " + wheatBefore + " -> " + wheatAfter);
        bakery.stock.remove("minecraft:bread");
        bakery.days.clear();
        FirmTrades.offForTests(false);
        ok.that(!FirmTrades.leftTo(level, id, "minecraft:bread"), "a bakery with no bread and none made: the trade is the town's again");
        Cafe.cook(level, v, cook);
        int breadLast = stock(level, id, s -> s.is(Items.BREAD));
        ok.that(breadLast > breadAfter, "and the cook bakes again: " + breadAfter + " -> " + breadLast);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ sc2_03 comb when the chandler wants it

    /**
     * A beekeeper at a full hive, shears and bottles to hand, comb in the stores and honey dear: it bottles the honey. The
     * town's trades short of honeycomb (a chandler with no comb for its candles): at the next full hive it takes the comb.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "sc2_03_comb_on_demand")
    public static void sc2_03_comb_on_demand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3106000;
        List<VillageFolkEntity> folk = town(helper, level, x, 20, StationTask.BEEKEEP, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity k = folk.get(0);
        UUID id = k.ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(5, () -> {
            stores(level, id, x, new ItemStack(Items.GLASS_BOTTLE, 8), new ItemStack(Items.HONEYCOMB, 5));
            k.insertItem(new ItemStack(Items.SHEARS));
            PriceIndex.setForTests(id, new ItemStack(Items.HONEY_BOTTLE), 3.0);   // the café's honey dear,
            PriceIndex.setForTests(id, new ItemStack(Items.HONEYCOMB), 0.5);      // the comb cheap
            BlockPos meadow = Kit.surface(level, x + 16, Z - 16);
            k.assignPlot(WorkZone.around(meadow, 6, WorkZone.DEFAULT_DEPTH), "Meadow");
            BlockPos hive = meadow.offset(2, 0, 2);
            level.setBlock(hive, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, BeehiveBlock.MAX_HONEY_LEVELS), 3);
            for (int i = 0; i < 2; i++) {
                Bee b = EntityType.BEE.create(level);
                if (b == null) continue;
                b.moveTo(meadow.getX() + 0.5, meadow.getY() + 2, meadow.getZ() + 0.5, 0, 0);
                b.setPersistenceRequired();
                level.addFreshEntity(b);
            }
            Kit.Expect ok = new Kit.Expect();
            int honey0 = stock(level, id, s -> s.is(Items.HONEY_BOTTLE)), comb0 = stock(level, id, s -> s.is(Items.HONEYCOMB));
            String a = Crafts.beekeepForTests(level, v, k);
            int honey1 = stock(level, id, s -> s.is(Items.HONEY_BOTTLE)), comb1 = stock(level, id, s -> s.is(Items.HONEYCOMB));
            Kit.log("sc2_03 nobody wanting comb: '" + a + "'; honey " + honey0 + " -> " + honey1 + ", comb " + comb0 + " -> " + comb1);
            ok.that(honey1 == honey0 + 1 && comb1 == comb0, "with honey dear and nobody short of comb, the honey bottled");
            InputWants.wantForTests(id, Items.HONEYCOMB, 12);
            level.setBlock(hive, level.getBlockState(hive).setValue(BeehiveBlock.HONEY_LEVEL, BeehiveBlock.MAX_HONEY_LEVELS), 3);
            String b = Crafts.beekeepForTests(level, v, k);
            int honey2 = stock(level, id, s -> s.is(Items.HONEY_BOTTLE)), comb2 = stock(level, id, s -> s.is(Items.HONEYCOMB));
            Kit.log("sc2_03 the chandler short of comb (" + InputWants.line(id) + "): '" + b + "'; honey " + honey1 + " -> " + honey2 + ", comb " + comb1 + " -> " + comb2);
            ok.that(comb2 == comb1 + 3 && honey2 == honey1, "the town short of comb: the comb taken (" + comb1 + " -> " + comb2 + ")");
            helper.assertTrue(ok.clean(), ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ sc2_04 string when it is wanted

    /**
     * A tailor with wool in the stores and six string (as much as the smith's bows want) spins none; with the town's trades
     * short of string (a chandler's wicks), it spins a lock of wool into four.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sc2_04_string_on_demand")
    public static void sc2_04_string_on_demand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3108000;
        List<VillageFolkEntity> folk = town(helper, level, x, 20, StationTask.TAILOR, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity t = folk.get(0);
        UUID id = t.ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(5, () -> {
            stores(level, id, x, new ItemStack(Items.WHITE_WOOL, 16), new ItemStack(Items.STRING, 6), new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED),
                new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED), new ItemStack(Items.LOOM));
            Kit.Expect ok = new Kit.Expect();
            int s0 = stock(level, id, s -> s.is(Items.STRING));
            String a = Crafts.tailorForTests(level, v, t);
            int s1 = stock(level, id, s -> s.is(Items.STRING));
            Kit.log("sc2_04 nobody short of string: '" + a + "'; string " + s0 + " -> " + s1);
            ok.that(s1 <= s0, "six string in the stores and nobody short: none spun (" + s0 + " -> " + s1 + ")");
            InputWants.wantForTests(id, Items.STRING, 8);
            String b = Crafts.tailorForTests(level, v, t);
            int s2 = stock(level, id, s -> s.is(Items.STRING));
            Kit.log("sc2_04 the town short of string (" + InputWants.line(id) + "): '" + b + "'; string " + s1 + " -> " + s2);
            ok.that(s2 == s1 + 4, "the town short of string: a lock of wool spun into four (" + s1 + " -> " + s2 + ")");
            helper.assertTrue(ok.clean(), ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ sc2_05 hides when they are wanted

    /**
     * A rancher's pen of three cows (within the herd it keeps) culls none; with the town's trades short of leather (a
     * clothier with nothing to cut), it culls a cow past a breeding pair for its hide, and never below the pair.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sc2_05_hides_on_demand")
    public static void sc2_05_hides_on_demand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3110000;
        List<VillageFolkEntity> folk = town(helper, level, x, 20, StationTask.RANCH, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity rancher = folk.get(0);
        UUID id = rancher.ownerId();
        BlockPos pen = Kit.surface(level, x + 14, Z + 14);
        helper.runAtTickTime(5, () -> {
            stores(level, id, x, new ItemStack(Items.WHEAT, 16));
            rancher.assignPlot(WorkZone.around(pen, 6, WorkZone.DEFAULT_DEPTH), "the pen");
            rancher.setJob(StationTask.RANCH);
            rancher.insertItem(new ItemStack(Items.IRON_SWORD));
            rancher.moveTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            for (int i = 0; i < 3; i++) {
                Animal a = EntityType.COW.create(level);
                if (a == null) continue;
                a.moveTo(pen.getX() + 0.5 + i - 1, pen.getY(), pen.getZ() + 1.5, 0.0F, 0.0F);
                a.setPersistenceRequired();
                a.addTag(Drover.HERD);
                level.addFreshEntity(a);
            }
        });
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            boolean a = rancher.cullForTests();
            Kit.log("sc2_05 nobody short of leather: cull " + a + ", job " + rancher.peekJob());
            ok.that(!a, "three cows, within the herd, nobody short of leather: none culled");
            rancher.clearQueue();
            InputWants.wantForTests(id, Items.LEATHER, 4);
            boolean b = rancher.cullForTests();
            Job j = rancher.peekJob();
            Kit.log("sc2_05 the town short of leather (" + InputWants.line(id) + "): cull " + b + ", job " + j);
            ok.that(b && j != null && j.type() == Job.Type.HUNT && "cow".equals(j.arg()), "the town short of leather: a cow culled for its hide (" + j + ")");
            helper.assertTrue(ok.clean(), ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ sc2_06 the smith leaves the tools to the toolshop

    /**
     * The town's smith, iron in the stores and no picks, forges the miners' iron pick; with a toolshop of the town's own folk
     * selling iron picks, it forges none (and turns to the rest of its work), and a miner's pick is the toolshop's to sell.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sc2_06_steps_back_tools")
    public static void sc2_06_steps_back_tools(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3112000;
        List<VillageFolkEntity> folk = town(helper, level, x, 30, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity smith = folk.get(0);
        UUID id = smith.ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.IRON);
            smith.tradeXpForTests(StationTask.SMITH, 20000);
            Predicate<ItemStack> pick = s -> s.is(Items.IRON_PICKAXE);
            Kit.Expect ok = new Kit.Expect();
            stores(level, id, x, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.STICK, 32),   // [econ32] the age's iron in hand: no iron pick while it saves (Picks.ironFor)
                new ItemStack(Items.BOW), new ItemStack(Items.BOW), new ItemStack(Items.BOW), new ItemStack(Items.BOW));
            List<String> made = new ArrayList<>();
            for (int i = 0; i < 6 && stock(level, id, pick) == 0; i++) made.add(String.valueOf(Crafts.smithForTests(level, v, smith)));
            int picks = stock(level, id, pick);
            Kit.log("sc2_06 no toolshop: the smith " + made + "; iron picks " + picks);
            ok.that(picks >= 1, "with no toolshop, the town's smith forges the iron pick: " + picks);
            // A toolshop of the town's own folk, iron picks on its shelf.
            stores(level, id, x, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.STICK, 32),   // [econ32] the age's iron in hand: no iron pick while it saves (Picks.ironFor)
                new ItemStack(Items.BOW), new ItemStack(Items.BOW), new ItemStack(Items.BOW), new ItemStack(Items.BOW));
            int picksBefore = stock(level, id, pick);
            Business shop = Enterprise.foundForTests(level, v, "toolsmith", folk.get(1));
            helper.assertTrue(shop != null, "a toolshop opened");
            Business.Lot lot = new Business.Lot();
            lot.qty = 2;
            lot.cost = 1200;
            shop.stock.put(Stockroom.key(new ItemStack(Items.IRON_PICKAXE)), lot);
            FirmTrades.offForTests(false);
            ok.that(FirmTrades.leftTo(level, id, "minecraft:iron_pickaxe"), "the iron pick is the toolshop's trade");
            List<String> then = new ArrayList<>();
            for (int i = 0; i < 6; i++) then.add(String.valueOf(Crafts.smithForTests(level, v, smith)));
            int picksThen = stock(level, id, pick);
            Kit.log("sc2_06 with the toolshop selling picks: the smith " + then + "; iron picks in the stores " + picksBefore + " -> " + picksThen);
            ok.that(picksThen <= picksBefore, "the town's smith forged no iron pick for the counter: " + picksBefore + " -> " + picksThen);
            helper.assertTrue(ok.clean(), ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ sc2_07 a basket that tires of roots

    /**
     * A poor household choosing its meals from potatoes, baked potatoes and carrots at a glut's prices and the baker's bread at
     * twice the baked potato's: as the roots pall (raw, baked or carrot, they are all roots to it), it buys a loaf now and
     * then, though the bread costs more for the same bellyful.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sc2_07_basket_tires_of_roots")
    public static void sc2_07_basket_tires_of_roots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3114000;
        List<VillageFolkEntity> folk = town(helper, level, x, 1, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity poor = folk.get(0);
        UUID id = poor.ownerId();
        stores(level, id, x, new ItemStack(Items.POTATO, 64), new ItemStack(Items.BAKED_POTATO, 64), new ItemStack(Items.CARROT, 64), new ItemStack(Items.BREAD, 32));
        for (Item it : new Item[]{ Items.POTATO, Items.CARROT }) PriceIndex.setForTests(id, new ItemStack(it), 0.6);
        PriceIndex.setForTests(id, new ItemStack(Items.BREAD), 1.4);
        List<ItemStack> offer = List.of(new ItemStack(Items.POTATO), new ItemStack(Items.BAKED_POTATO), new ItemStack(Items.CARROT), new ItemStack(Items.BREAD));
        double breadP = Purchases.priceEach(level, id, new ItemStack(Items.BREAD), poor), bakedP = Purchases.priceEach(level, id, new ItemStack(Items.BAKED_POTATO), poor);
        StringBuilder picks = new StringBuilder();
        int bread = 0;
        for (int i = 0; i < 16; i++) {
            ItemStack a = Purchases.chooseFoodForTests(level, poor, offer);
            if (a.is(Items.BREAD)) bread++;
            picks.append(Stockroom.key(a).replace("minecraft:", "")).append(' ');
        }
        Kit.log(String.format(java.util.Locale.ROOT, "sc2_07 picks: %s| bread %.2fc, baked potato %.2fc, potato %.2fc, carrot %.2fc", picks, breadP, bakedP,
            Purchases.priceEach(level, id, new ItemStack(Items.POTATO), poor), Purchases.priceEach(level, id, new ItemStack(Items.CARROT), poor)));
        Kit.Expect ok = new Kit.Expect();
        ok.that(breadP > bakedP * 1.5, String.format(java.util.Locale.ROOT, "the bread dearer for the same bellyful: %.2fc against %.2fc", breadP, bakedP));
        ok.that(bread >= 2 && bread <= 10, "a loaf now and then among sixteen meals, the roots the most: " + bread + " loaves");
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ sc2_08 a firm's loaves are its own

    /**
     * A bakery's four loaves on the town's shelves, bonded in its name: a folk helping itself to two at the stores buys them
     * of the bakery, at the bakery's price, into its till (it had been charged the market's ask, and the market kept the
     * money for the bakery's bread). Loaves of the market's own beside them, and a take of two is the market's.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sc2_08_bonded_loaves")
    public static void sc2_08_bonded_loaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3116000;
        List<VillageFolkEntity> folk = town(helper, level, x, 20, StationTask.COOK, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, new ItemStack(Items.BREAD, 4));
        Business bakery = Enterprise.foundForTests(level, v, "baker", folk.get(0));
        helper.assertTrue(bakery != null, "a bakery opened");
        Business.Lot lot = new Business.Lot();
        lot.qty = 4;
        lot.cost = 400;
        bakery.stock.put("minecraft:bread", lot);
        VillageFolkEntity buyer = folk.get(2);
        Kit.Expect ok = new Kit.Expect();
        long till0 = bakery.till + bakery.account, rev0 = bakery.allTime.revenue;
        MarketMaker.takeForTests(level, v, buyer, null, com.jrpetty.mcassistant.entity.Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 2);
        long till1 = bakery.till + bakery.account, rev1 = bakery.allTime.revenue;
        Kit.log("sc2_08 two of the bakery's loaves taken: its lot " + lot.qty + ", its till and credit " + till0 + " -> " + till1 + ", revenue " + rev0 + " -> " + rev1
            + ", bread in the stores " + stock(level, id, s -> s.is(Items.BREAD)));
        ok.that(lot.qty == 2, "the two loaves were the bakery's: its lot 4 -> " + lot.qty);
        ok.that(rev1 > rev0 && till1 > till0, "the bakery's sale, into its till: revenue " + rev0 + " -> " + rev1 + ", till " + till0 + " -> " + till1);
        Crafts.storeForTests(level, v, new ItemStack(Items.BREAD, 3));            // three of the market's own beside them
        MarketMaker.takeForTests(level, v, buyer, null, com.jrpetty.mcassistant.entity.Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 2);
        Kit.log("sc2_08 two more taken with three of the market's own on the shelf: the bakery's lot " + lot.qty + ", revenue " + bakery.allTime.revenue);
        ok.that(lot.qty == 2 && bakery.allTime.revenue == rev1, "the market's own loaves were the market's to sell: the bakery's lot " + lot.qty);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ [econ32] the chains from source to use

    private static void clearStores(ServerLevel level, UUID v) {
        for (BlockPos p : Villages.storeChests(level, v)) {
            if (level.getBlockEntity(p) instanceof Container c) { c.clearContent(); c.setChanged(); }
        }
        Villages.forgetStock();
    }

    /**
     * A smelter with iron ore and nothing to burn, in an Iron Age town whose stores hold logs and no coal: it fetches logs
     * from the stores itself, and smelts its iron at the furnace beside it. The woodcutters' share leans up while the
     * town's fuel is low and its timber short.
     */
    @GameTest(template = "empty", timeoutTicks = 4000, batch = "sc01_smelter_fuel")
    public static void sc01_smelter_fuel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SmelterFuel.resetForTests();
        SupplyLean.resetForTests();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, 6600000, 40, 4, StationTask.FARM);
        helper.assertTrue(folk.size() == 4, "the town");
        UUID v = folk.get(0).ownerId();
        VillageFolkEntity smelter = folk.get(0);
        smelter.setJob(StationTask.SMELT);
        Villages.ageForTests(v, Villages.Age.IRON);
        clearStores(level, v);
        for (int i = 0; i < 3; i++) Market.intoStoresForTests(level, v, new ItemStack(Items.OAK_LOG, 64));
        Villages.forgetStock();
        smelter.removeMatching(s -> AssistantEntity.SMELT_FUEL.test(s) || AssistantEntity.SMELTABLE_ORE.test(s), 999);
        smelter.insertItem(new ItemStack(Items.RAW_IRON, 4));
        helper.assertTrue(SmelterFuel.cold(smelter), "the smelter has its ore and nothing to burn");
        boolean fetched = SmelterFuel.fetch(smelter);
        int logs = smelter.countCarried(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
        Kit.log("[VT] sc01 fetched " + fetched + ", logs in hand " + logs + ", tally " + java.util.Arrays.toString(SmelterFuel.tally(v)));
        helper.assertTrue(fetched && logs > 0, "logs fetched from the stores for the furnaces: " + logs);
        // Its forge: a furnace, its plot round it, and the smelt.
        BlockPos furnace = Kit.surface(level, 6600000 - 6, Z);
        level.setBlock(furnace, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, furnace);
        smelter.assignPlot(com.jrpetty.mcassistant.entity.WorkZone.around(furnace, 4, com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH), "The Forge");
        smelter.moveTo(furnace.getX() + 1.5, furnace.getY(), furnace.getZ() + 0.5, 0.0F, 0.0F);
        smelter.clearQueue();
        smelter.enqueue(Job.smelt("iron", 4));
        // The shares: fuel low (no coal) and timber under the builders' keep once the logs are gone.
        clearStores(level, v);
        SupplyLean.weigh(level, v, true);
        double lean = SupplyLean.lean(v, StationTask.WOOD);
        Kit.log("[VT] sc01 the woodcutters' lean with no logs and no coal: " + lean + " (" + SupplyLean.why(v) + ")");
        helper.assertTrue(lean >= 2.0, "twice the woodcutters with no logs and the fuel low: " + lean);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (level.getDayTime() % 24000L > 9000L) level.setDayTime(1000);
            if (smelter.peekJob() == null && smelter.countCarried(SmelterFuel.IRON_ORE) > 0) smelter.enqueue(Job.smelt("iron", 4));
            if (smelter.distanceToSqr(furnace.getX() + 0.5, furnace.getY(), furnace.getZ() + 0.5) > 64)
                smelter.moveTo(furnace.getX() + 1.5, furnace.getY(), furnace.getZ() + 0.5, 0.0F, 0.0F);   // its forge's work, not the town's
            int bars = smelter.countCarried(s -> s.is(Items.IRON_INGOT));
            if (level.getBlockEntity(furnace) instanceof Container c) bars += c.getItem(2).is(Items.IRON_INGOT) ? c.getItem(2).getCount() : 0;
            if (bars < 1 && tick[0] < 3600) return false;
            Kit.log("[VT] sc01 after " + tick[0] + " ticks: " + bars + " iron bars; " + smelter.debugLine());
            helper.assertTrue(bars >= 1, "the iron smelted on the logs: " + bars);
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    /**
     * Tools between towns: a town with iron to spare, its age asking none, has its smith make iron picks and axes past its
     * rack's spares; a town of miners whose picks are all gone, with no iron and no stone in its stores, is short of tools;
     * the envoys strike a deal for the iron town's picks or axes.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "sc02_tools_traded")
    public static void sc02_tools_traded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        ToolTrade.resetForTests();
        com.jrpetty.mcassistant.entity.TradeDeals.resetForTests();
        TradeBook.resetForTests();
        level.setDayTime(24000L * 6 + 3000);
        Villages.Village[] t = new Villages.Village[2];
        for (int i = 0; i < 2; i++) {
            int cx = 6602000 + i * 300;
            Kit.hold(level, cx, Z, 40);
            Kit.prepare(level, cx, Z, 40);
            BlockPos heart = Kit.surface(level, cx, Z);
            VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 5);
            t[i] = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
            helper.assertTrue(t[i] != null, "a town at " + cx);
            for (AssistantEntity a : Villages.folkOf(t[i].id())) if (a instanceof VillageFolkEntity f) f.ensurePersona();
            clearStores(level, t[i].id());
            Villages.chooseElder(t[i].id(), level.getDayTime() / 24000L);
            TradeTalks.temperForTests(t[i].id(), com.jrpetty.mcassistant.entity.Envoys.Temper.STEADY);
        }
        UUID a = t[0].id(), b = t[1].id();
        helper.assertTrue(!a.equals(b), "two towns");
        Ledger.relate(a, b, 25);
        // The iron town: its smith, iron past its age's ask (the Iron Age's iron in hand), timber for the handles.
        Villages.ageForTests(a, Villages.Age.IRON);
        List<VillageFolkEntity> af = new ArrayList<>();
        for (AssistantEntity x : Villages.folkOf(a)) if (x instanceof VillageFolkEntity f && !f.isBaby()) af.add(f);
        VillageFolkEntity smith = af.get(0);
        smith.setJob(StationTask.SMITH);
        for (int i = 0; i < 6; i++) Market.intoStoresForTests(level, a, new ItemStack(Items.IRON_INGOT, 64));
        Market.intoStoresForTests(level, a, new ItemStack(Items.OAK_LOG, 64));
        Villages.forgetStock();
        int made = 0;
        for (int i = 0; i < 12; i++) {
            ToolTrade.resetForTests();
            if (ToolTrade.forge(smith, level) != null) made++;
        }
        Kit.log("[VT] sc02 the iron town's smith made " + made + " tools to sell");
        helper.assertTrue(made >= 2, "the smith made iron tools to sell: " + made);
        // Its bars gone again (sold on, built with): only the tools are to spare.
        clearStoresBut(level, a, s -> s.is(Items.IRON_PICKAXE) || s.is(Items.IRON_AXE));
        // The town short of picks: miners with none, no iron, no stone, no timber; coin to pay.
        Villages.ageForTests(b, Villages.Age.STONE);
        for (AssistantEntity x : Villages.folkOf(b)) {
            if (!(x instanceof VillageFolkEntity f) || f.isBaby()) continue;
            f.setJob(StationTask.MINE);
            f.removeMatching(s -> s.is(net.minecraft.tags.ItemTags.PICKAXES), 64);
            if (f.getMainHandItem().is(net.minecraft.tags.ItemTags.PICKAXES)) f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            f.recheckKit();
        }
        Ledger.addCoins(b, 400);
        Villages.forgetStock();
        TradeBook.forget(a);
        TradeBook.forget(b);
        TradeBook.Entry sells = TradeBook.of(level, a).get(TradeBook.Ware.TOOLS), buys = TradeBook.of(level, b).get(TradeBook.Ware.TOOLS);
        Kit.log("[VT] sc02 the iron town's tools: " + TradeBook.line(sells) + " | the other's: " + TradeBook.line(buys));
        helper.assertTrue(sells.status() == TradeBook.Status.SURPLUS, "the iron town has tools to spare: " + TradeBook.line(sells));
        helper.assertTrue(buys.status() == TradeBook.Status.SHORT, "the miners' town is short of tools: " + TradeBook.line(buys));
        TradeTalks.Talk k = TradeTalks.negotiate(level, b, a);
        Kit.log("[VT] sc02 talks: " + k.end() + " — " + (k.deal() ? TradeTalks.terms(k) : k.why()));
        helper.assertTrue(k.deal() && k.table() != null && k.table().seller().equals(a)
            && (k.table().x() == Items.IRON_PICKAXE || k.table().x() == Items.IRON_AXE), "a deal for the iron town's tools: " + k.end() + " " + k.why());
        helper.succeed();
    }

    private static void clearStoresBut(ServerLevel level, UUID v, java.util.function.Predicate<ItemStack> keep) {
        for (BlockPos p : Villages.storeChests(level, v)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty() && !keep.test(c.getItem(i))) c.setItem(i, ItemStack.EMPTY);
            c.setChanged();
        }
        Villages.forgetStock();
    }

    /**
     * A mine worked out to the eighth ring: its ground widened as far as it may go, every iron level worked out; the last
     * found worked out by its miner, the town wants a new mine, and opens one clear of the old ground (kept set aside), the
     * miners moved out to the new pithead, the works begun afresh there.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "sc03_new_mine")
    public static void sc03_new_mine(GameTestHelper helper) {
        MineSites.resetForTests();
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 6606000, 2);
        ServerLevel level = r.level();
        UUID v = r.v();
        Villages.ageForTests(v, Villages.Age.IRON);
        BlockPos oldSite = TownMine.siteOf(v);
        // The ground widened out to the eighth ring.
        List<int[]> add = new ArrayList<>();
        for (int i = -8; i <= 8; i++) for (int j = -8; j <= 8; j++)
            if (Math.max(Math.abs(i), Math.abs(j)) > 4 && !MineGround.faceReserved(v, i, j)) add.add(new int[]{ i, j });
        MineGround.addFaces(v, oldSite, add);
        helper.assertTrue(MineGround.ring(v) == 8, "the ground out to the eighth ring: " + MineGround.ring(v));
        // Every iron level worked out but the last; the last found worked out now.
        int further = MineLevels.placesForTests(level, v, MineWorks.IRON).size();
        for (int k = 0; k < further; k++) MineLevels.spendForTests(level, v, MineWorks.IRON + 5 * k);
        MineLevels.markSpentForTests(level, v, MineWorks.IRON + 5 * further);
        Kit.log("[VT] sc03 " + further + " further iron levels; wanted: " + MineSites.pending(v) + "; plan " + MineWorks.planForTests(level, v));
        helper.assertTrue("iron".equals(MineSites.pending(v)), "a new mine wanted for the iron: " + MineSites.pending(v));
        boolean opened = MineSites.open(level, Villages.get(v), "iron");
        BlockPos site = TownMine.siteOf(v);
        Kit.log("[VT] sc03 opened " + opened + ": old " + oldSite + " new " + site + "; faces " + MineGround.faceCount(v) + "; news "
            + Villages.news(v).stream().map(Villages.News::text).filter(s -> s.contains("mine")).toList());
        helper.assertTrue(opened && site != null && !site.equals(oldSite), "a new mine opened: " + site);
        helper.assertTrue(MineGround.reserved(v, oldSite.getX(), oldSite.getZ()), "the old mine's ground still set aside");
        helper.assertTrue(MineGround.faceReserved(v, 0, 0) && MineGround.ring(v) <= 4, "the new ground set aside round the new site");
        helper.assertTrue(MineSites.oldMines(v) == 1 && MineSites.pending(v) == null, "the old mine kept in the books");
        int[] works = MineWorks.shaftForTests(level, v);
        helper.assertTrue(works[2] == site.getX() && works[3] == site.getZ() && works[0] == 0, "the works begun afresh at the new site: "
            + java.util.Arrays.toString(works));
        for (VillageFolkEntity m : r.miners()) {
            BlockPos c = m.workZone() == null ? null : m.workZone().center();
            helper.assertTrue(c != null && Math.abs(c.getX() - site.getX()) <= 1 && Math.abs(c.getZ() - site.getZ()) <= 1,
                "the miner at the new pithead: " + c);
        }
        MineWorksGameTests.done(helper);
    }

    /**
     * A level gone barren: the iron level with hundreds of blocks of drift cut on it this week and no ore out of them; its
     * miner's next look finds it worked out, and the second iron level is planned off the shaft.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "sc06_barren_level")
    public static void sc06_barren_level(GameTestHelper helper) {
        MineLevels.forgetCutForTests();
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 6618000, 1);
        ServerLevel level = r.level();
        UUID v = r.v();
        VillageFolkEntity m = r.miners().get(0);
        Villages.ageForTests(v, Villages.Age.IRON);
        java.util.Map<com.jrpetty.mcassistant.entity.MineDemand.Ore, Integer> stock = MineWorksGameTests.plenty();
        stock.put(com.jrpetty.mcassistant.entity.MineDemand.Ore.IRON, 0);
        com.jrpetty.mcassistant.entity.MineDemand.stockForTests(v, stock);
        MineWorksGameTests.kit(m, Items.IRON_PICKAXE);
        MineWorksGameTests.zone(r, m, 16);
        int y1 = MineWorks.levelY(level, v, MineWorks.IRON);
        MineWorksGameTests.tower(r, y1 - 3);
        MineWorksGameTests.bed(r, y1, 30, Blocks.STONE, 0, 3232L);              // room for its drifts: bare rock
        MineWorks.sinkForTests(level, v, 4 * ((MineWorksGameTests.TOP - y1) / 3) + 1, m);
        MineWorks.roomForTests(level, v, MineWorks.IRON);
        // A first look, then a while's work: a few hundred blocks of drift, no ore.
        com.jrpetty.mcassistant.entity.MineWorks.Task first = MineWorks.next(level, m);
        if (first != null) MineWorks.release(v, first, m.getUUID());
        MineLevels.cutForTests(level, v, MineWorks.IRON, 300);
        com.jrpetty.mcassistant.entity.MineWorks.Task t0 = MineWorks.next(level, m);
        if (t0 != null) MineWorks.release(v, t0, m.getUUID());
        boolean early = MineLevels.spentForTests(level, v, MineWorks.IRON);
        MineLevels.cutForTests(level, v, MineWorks.IRON, 200);
        com.jrpetty.mcassistant.entity.MineWorks.Task t = MineWorks.next(level, m);
        if (t != null) MineWorks.release(v, t, m.getUUID());
        boolean spent = MineLevels.spentForTests(level, v, MineWorks.IRON);
        List<String> plan = MineWorks.planForTests(level, v);
        Kit.log("[VT] sc06 iron level at Y" + y1 + ": after 300 bare blocks spent " + early + ", after 500 spent " + spent + "; next task "
            + (t == null ? "-" : t.words()) + "; plan " + plan);
        helper.assertTrue(!early, "three hundred blocks are not yet barren");
        helper.assertTrue(spent, "five hundred bare blocks in a week: the iron level worked out");
        helper.assertTrue(plan.stream().anyMatch(x -> x.startsWith((MineWorks.IRON + 5) + ":the second iron level")), "the second iron level planned: " + plan);
        com.jrpetty.mcassistant.entity.MineDemand.stockForTests(v, null);
        MineWorksGameTests.done(helper);
    }

    /**
     * The age's own building finds room: a Diamond Age town of a dozen with few buildings, no lot for its lighthouse on the three
     * rings of its plan (three looks in a row), lays out a fourth; for a building the age does not ask for (an auction house) it
     * waits for the forty-five buildings a new ring wants.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "sc07_ring_for_the_age")
    public static void sc07_ring_for_the_age(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        com.jrpetty.mcassistant.entity.CityGrowth.resetForTests();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, 6620000, 40, 12, StationTask.FARM);
        helper.assertTrue(!folk.isEmpty(), "the town");
        UUID v = folk.get(0).ownerId();
        Villages.Village vv = Villages.get(v);
        Villages.ageForTests(v, Villages.Age.DIAMOND);
        Villages.forgetStock();
        boolean asks = false;
        for (Villages.Need n : Villages.needs(level, v)) if (n.what().contains("lighthouse")) asks = true;
        helper.assertTrue(asks, "the Diamond Age asks for a lighthouse");
        int before = com.jrpetty.mcassistant.entity.CityGrowth.rings(v);
        for (int i = 0; i < 3; i++) com.jrpetty.mcassistant.entity.CityGrowth.looked(level, vv, "auctionhouse", false, false);
        int afterOther = com.jrpetty.mcassistant.entity.CityGrowth.rings(v);
        for (int i = 0; i < 3; i++) com.jrpetty.mcassistant.entity.CityGrowth.looked(level, vv, "lighthouse", false, false);
        int afterAge = com.jrpetty.mcassistant.entity.CityGrowth.rings(v);
        Kit.log("[VT] sc07 rings " + before + "; after three misses for an auction house " + afterOther + "; for the lighthouse " + afterAge);
        helper.assertTrue(afterOther == before, "no new ring for a building the age does not ask for: " + afterOther);
        helper.assertTrue(afterAge == before + 1, "a new ring for the age's lighthouse: " + afterAge);
        com.jrpetty.mcassistant.entity.CityGrowth.setRings(v, before);
        helper.succeed();
    }

    /**
     * [econ32b] A further level is laid out before it is ever worked out: the deep level marked worked out, the second deep
     * level planned over it, the town's plan laid out to its last ring over the mine; its landing cut, its miner's next look
     * starts a drift on it all the same (the mine's ground is set aside for good), and it is not called worked out
     * with no drift on it (the long run's second and third deep levels and third and fourth copper levels were "worked out
     * (0 drifts)" the moment they opened).
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "sc08_new_level_laid_out")
    public static void sc08_new_level_laid_out(GameTestHelper helper) {
        MineLevels.forgetCutForTests();
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 6624000, 1);
        ServerLevel level = r.level();
        UUID v = r.v();
        VillageFolkEntity m = r.miners().get(0);
        Villages.ageForTests(v, Villages.Age.DIAMOND);
        java.util.Map<com.jrpetty.mcassistant.entity.MineDemand.Ore, Integer> stock = MineWorksGameTests.plenty();
        stock.put(com.jrpetty.mcassistant.entity.MineDemand.Ore.DIAMOND, 0);
        com.jrpetty.mcassistant.entity.MineDemand.stockForTests(v, stock);
        MineWorksGameTests.kit(m, Items.IRON_PICKAXE);
        MineWorksGameTests.zone(r, m, -50);
        int second = MineWorks.DEEP + 5;
        List<Integer> further = MineLevels.placesForTests(level, v, MineWorks.DEEP);
        helper.assertTrue(!further.isEmpty(), "further deep levels planned: " + further);
        int y2 = further.get(0);
        int y1 = MineWorks.levelY(level, v, MineWorks.DEEP);
        MineWorksGameTests.tower(r, Math.min(y1, y2) - 3);
        MineWorksGameTests.bed(r, y2, 30, Blocks.DEEPSLATE, 0, 3233L);
        MineLevels.spendForTests(level, v, MineWorks.DEEP);
        MineWorks.sinkForTests(level, v, 4 * ((MineWorksGameTests.TOP - Math.min(y1, y2)) / 3) + 1, m);
        MineWorks.roomForTests(level, v, second);
        // The town's plan laid out to its last ring, out over the mine's ground (as a grown town's is: CityGrowth).
        int rings = com.jrpetty.mcassistant.entity.CityGrowth.rings(v);
        com.jrpetty.mcassistant.entity.CityGrowth.setRings(v, com.jrpetty.mcassistant.village.TownPlan.MAX_RINGS);
        com.jrpetty.mcassistant.entity.MineWorks.Task t = MineWorks.next(level, m);
        com.jrpetty.mcassistant.entity.CityGrowth.setRings(v, rings);
        if (t != null) MineWorks.release(v, t, m.getUUID());
        int drifts = 0;
        for (int[] d : MineWorks.driftsForTests(level, v)) if (d[1] == second) drifts++;
        boolean spent = MineLevels.spentForTests(level, v, second);
        Kit.log("[VT] sc08 the second deep level at Y" + y2 + ": next task " + (t == null ? "-" : t.words()) + "; drifts on it " + drifts
            + "; worked out " + spent + "; plan " + MineWorks.planForTests(level, v));
        helper.assertTrue(!spent, "a level just opened is not worked out");
        helper.assertTrue(drifts >= 1, "a drift started on it: " + drifts);
        com.jrpetty.mcassistant.entity.MineDemand.stockForTests(v, null);
        MineWorksGameTests.done(helper);
    }

    /**
     * [econ32b] The barren count survives a restart: three hundred bare blocks cut on the iron level, the works forgotten and
     * read again from the town's notes (as after a restart), the count still three hundred; with ore out of the level, it
     * starts again.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "sc09_barren_kept")
    public static void sc09_barren_kept(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 6628000, 1);
        ServerLevel level = r.level();
        UUID v = r.v();
        MineLevels.cutForTests(level, v, MineWorks.IRON, 300);
        int before = MineLevels.sinceOreForTests(level, v, MineWorks.IRON);
        MineWorks.forgetCache();
        int after = MineLevels.sinceOreForTests(level, v, MineWorks.IRON);
        Kit.log("[VT] sc09 the iron level's bare blocks: " + before + " before, " + after + " after the works were read again");
        helper.assertTrue(before == 300 && after == 300, "the count kept with the works: " + before + " -> " + after);
        MineWorksGameTests.done(helper);
    }

    /**
     * [econ32b] Every building an age asks for finds a lot in a town of a dozen, on its ground of three kinds: flat ground
     * raised four blocks over the heart's kept height after the founding (a square paved over it: the walk of the town's
     * streets used to begin in the earth under it and reach nothing), a hillside, and a shore (the lighthouse by the water).
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "sc10_age_lots_flat")
    public static void sc10_age_lots_flat(GameTestHelper helper) {
        ageLots(helper, 6632000, "flat");
    }

    @GameTest(template = "empty", timeoutTicks = 300, batch = "sc11_age_lots_hill")
    public static void sc11_age_lots_hill(GameTestHelper helper) {
        ageLots(helper, 6636000, "hill");
    }

    @GameTest(template = "empty", timeoutTicks = 300, batch = "sc12_age_lots_shore")
    public static void sc12_age_lots_shore(GameTestHelper helper) {
        ageLots(helper, 6640000, "shore");
    }

    private static void ageLots(GameTestHelper helper, int x, String ground) {
        ServerLevel level = helper.getLevel();
        com.jrpetty.mcassistant.entity.SiteWhy.resetForTests();
        com.jrpetty.mcassistant.entity.AgeSites.resetForTests();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, x, 150, 12, StationTask.FARM);
        helper.assertTrue(!folk.isEmpty(), "the town");
        UUID v = folk.get(0).ownerId();
        BlockPos heart = folk.get(0).villageCentre();
        int R = 110;
        net.minecraft.world.level.block.state.BlockState dirt = Blocks.DIRT.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        if (ground.equals("flat")) {
            // Four blocks of ground laid over the town after its founding: the heart's kept height lies under them.
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                for (int y = 0; y < 4; y++) level.setBlock(p.set(heart.getX() + dx, heart.getY() + y, heart.getZ() + dz), y == 3 ? grass : dirt, 2);
            }
            for (VillageFolkEntity f : folk) f.teleportTo(f.getX(), heart.getY() + 4, f.getZ());
        } else if (ground.equals("hill")) {
            Kit.hill(level, heart.getX() + 70, heart.getZ(), 55, 12, 3241L);
        } else {
            // The sea along the east of the town: from forty blocks out, two deep.
            for (int dx = 40; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                for (int y = -2; y < 0; y++) level.setBlock(p.set(heart.getX() + dx, heart.getY() + y, heart.getZ() + dz), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        com.jrpetty.mcassistant.entity.CityPlan.forget(v);
        Villages.forgetStock();
        List<String> missing = new ArrayList<>();
        StringBuilder found = new StringBuilder();
        for (String b : com.jrpetty.mcassistant.entity.AgeSites.ASKED) {
            Villages.Site site = Villages.siteFor(level, v, b);
            if (site == null) missing.add(b + " (" + com.jrpetty.mcassistant.entity.SiteWhy.last(v, b) + ")");
            else found.append(' ').append(b).append('@').append(site.anchor().getX() - heart.getX()).append(',').append(site.anchor().getZ() - heart.getZ());
        }
        Kit.log("[VT] " + ground + " ground: lots" + found + "; none for " + missing);
        helper.assertTrue(missing.isEmpty(), "every building an age asks for has a lot on " + ground + " ground; none for " + missing);
        if (ground.equals("shore")) {
            Villages.Site light = Villages.sitesOf(v).get("lighthouse");
            helper.assertTrue(light != null && light.anchor().getX() - heart.getX() >= 20, "the lighthouse by the water: "
                + (light == null ? "none" : (light.anchor().getX() - heart.getX()) + " east"));
        }
        helper.succeed();
    }

    /**
     * [econ32b] A smelter at its furnaces indoors (a smeltery of the town's, walled and roofed round it) is not "lost
     * underground", nor "below in the works" though it stands under the pithead's height: no way up is sent for it. (The
     * long run's smelters were sent up out of the smeltery twenty-two times a day.)
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "sc13_indoors_not_lost")
    public static void sc13_indoors_not_lost(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 6644000, 2);
        ServerLevel level = r.level();
        UUID v = r.v();
        VillageFolkEntity f = r.miners().get(1);
        f.setJob(StationTask.SMELT);
        // Seventy-six from the shaft, off the mine's ground, eight under the pithead's height (on a stone floor raised there).
        BlockPos spot = new BlockPos(r.heart().getX() + 60, MineWorksGameTests.TOP - 8, r.heart().getZ());
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) level.setBlock(spot.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
        com.jrpetty.mcassistant.village.Ledger.built(v, "smeltery", spot, net.minecraft.core.Direction.NORTH);
        TownMine.redraw(v);
        // A shut room of stone round it, roofed.
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy <= 4; dy++) {
            boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3 || dy == 4;
            level.setBlock(spot.offset(dx, dy, dz), wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) if (Math.abs(dx) > 3 || Math.abs(dz) > 3)
            for (int dy = 0; dy <= 8; dy++) level.setBlock(spot.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 2);
        f.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        f.clearQueue();
        boolean below = MineWorks.below(f);
        for (int i = 0; i < 6; i++) com.jrpetty.mcassistant.entity.MineStairs.lookForAWayUp(f, level);
        Job j = f.peekJob();
        Kit.log("[VT] sc13 the smelter indoors: below in the works " + below + "; job after six looks " + (j == null ? "-" : j.type() + ":" + j.arg()));
        helper.assertTrue(!below, "not below in the works");
        helper.assertTrue(j == null || j.type() != Job.Type.MINE, "no way up sent for it: " + (j == null ? "-" : j.type() + ":" + j.arg()));
        MineWorksGameTests.done(helper);
    }

    /**
     * [econ32b] The watch's armour comes back: an Iron Age town putting its iron by, a guard with no armour; the smith forges
     * a piece for it (the town's mark on it), the guard is fitted, and when it leaves the watch for the fields the piece goes
     * back into the stores with the mark still on it, so the next guard is fitted with it and the smith forges no other. (A
     * long run's town of fifty had fourteen iron helmets on folk who had left its watch, and forged the new guards'.)
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "sc14_watch_kit_back")
    public static void sc14_watch_kit_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, 6648000, 40, 6, StationTask.FARM);
        helper.assertTrue(folk.size() == 6, "the town");
        UUID v = folk.get(0).ownerId();
        Villages.Village vv = Villages.get(v);
        VillageFolkEntity smith = folk.get(0), guard = folk.get(1), next = folk.get(2);
        smith.setJob(StationTask.SMITH);
        smith.tradeXpForTests(StationTask.SMITH, 20000);
        guard.setJob(StationTask.GUARD);
        for (net.minecraft.world.entity.EquipmentSlot sl : new net.minecraft.world.entity.EquipmentSlot[]{ net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET }) {
            guard.setItemSlot(sl, ItemStack.EMPTY);
            next.setItemSlot(sl, ItemStack.EMPTY);
        }
        Villages.ageForTests(v, Villages.Age.IRON);
        clearStores(level, v);
        Market.intoStoresForTests(level, v, new ItemStack(Items.IRON_INGOT, 40));
        Market.intoStoresForTests(level, v, new ItemStack(Items.OAK_PLANKS, 64));
        Market.intoStoresForTests(level, v, new ItemStack(Items.STICK, 32));
        Villages.forgetStock();
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 6; i++) { String m = Crafts.smithForTests(level, vv, smith); if (m != null) made.add(m); Villages.forgetStock(); }
        int marked = 0;
        for (BlockPos p : Villages.storeChests(level, v)) if (level.getBlockEntity(p) instanceof Container c)
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).getItem() instanceof net.minecraft.world.item.ArmorItem && com.jrpetty.mcassistant.entity.WatchKit.issued(c.getItem(i))) marked++;
        List<ItemStack> fitted = com.jrpetty.mcassistant.entity.WatchKit.fit(level, vv, guard);
        int ironBefore = Market.stock(level, v, st -> st.is(Items.IRON_INGOT));
        guard.setJob(StationTask.FARM);                                          // off the watch
        int worn = 0;
        for (net.minecraft.world.entity.EquipmentSlot sl : net.minecraft.world.entity.EquipmentSlot.values()) if (guard.getItemBySlot(sl).getItem() instanceof net.minecraft.world.item.ArmorItem) worn++;
        next.setJob(StationTask.GUARD);
        List<ItemStack> refit = com.jrpetty.mcassistant.entity.WatchKit.fit(level, vv, next);
        for (int i = 0; i < 6; i++) { Crafts.smithForTests(level, vv, smith); Villages.forgetStock(); }
        int ironAfter = Market.stock(level, v, st -> st.is(Items.IRON_INGOT));
        Kit.log("[VT] sc14 forged " + made + " (" + marked + " marked in the stores); fitted " + fitted.size() + "; the old guard wears " + worn
            + " pieces after leaving the watch; the next guard fitted " + refit.size() + "; iron " + ironBefore + " -> " + ironAfter);
        helper.assertTrue(marked >= 1, "the watch's armour carries the town's mark: " + marked);
        helper.assertTrue(!fitted.isEmpty(), "the guard is fitted");
        helper.assertTrue(worn == 0, "the old guard hands its armour back: " + worn + " pieces kept");
        helper.assertTrue(refit.size() >= fitted.size() - 1, "the next guard fitted with it: " + refit.size() + " of " + fitted.size());
        helper.assertTrue(ironAfter >= ironBefore - 5, "no new suit forged for the next guard: iron " + ironBefore + " -> " + ironAfter);
        helper.succeed();
    }

    /** A hungry town wants more farmers: its farms short of hands the morning its larder is low. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "sc04_hungry_farms")
    public static void sc04_hungry_farms(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, 6610000, 40, 12, StationTask.MINE);
        helper.assertTrue(folk.size() == 12, "the town");
        UUID v = folk.get(0).ownerId();
        for (int i = 0; i < 3; i++) folk.get(i).setJob(StationTask.FARM);
        Market.hungryForTests(v, false);
        double fed = Villages.share(v, StationTask.FARM);
        Market.hungryForTests(v, true);
        double hungry = Villages.share(v, StationTask.FARM);
        Kit.log("[VT] sc04 the farms' hands over (+) or under (-) their share: fed " + fed + ", hungry " + hungry);
        helper.assertTrue(hungry < fed - 0.9, "the farms want a hand or more when the town is hungry: " + fed + " -> " + hungry);
        helper.assertTrue(hungry < 0, "the farms are short of hands: " + hungry);
        Market.hungryForTests(v, false);
        helper.succeed();
    }

    /** Stores filling up: with under a tenth of their places free, another chest is set down at once, not after they are full. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "sc05_store_room")
    public static void sc05_store_room(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StoreRoom.resetForTests();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, 6614000, 40, 6, StationTask.FARM);
        helper.assertTrue(!folk.isEmpty(), "the town");
        UUID v = folk.get(0).ownerId();
        TownJobs.instantForTests(true);
        try {
            clearStores(level, v);
            List<BlockPos> chests = Villages.storeChests(level, v);
            if (chests.isEmpty()) {
                BlockPos c = Kit.surface(level, 6614000, Z).offset(3, 0, 3);
                level.setBlock(c, Blocks.CHEST.defaultBlockState(), 3);
                com.jrpetty.mcassistant.entity.ZoneChests.mark(level, c);
                chests = Villages.storeChests(level, v);
            }
            helper.assertTrue(!chests.isEmpty(), "the town's stores");
            // Filled but for a handful of places, the planks for a chest among what is there.
            Market.intoStoresForTests(level, v, new ItemStack(Items.OAK_PLANKS, 64));
            int free = 0, all = 0;
            net.minecraft.world.item.Item[] fill = { Items.COBBLESTONE, Items.DIRT, Items.WHEAT_SEEDS, Items.BONE, Items.FLINT, Items.STICK };
            int k = 0;
            for (BlockPos p : chests) {
                if (!(level.getBlockEntity(p) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    all++;
                    if (c.getItem(i).isEmpty()) c.setItem(i, new ItemStack(fill[k++ % fill.length], 1));
                }
                c.setChanged();
            }
            // Free a few places again: under a tenth.
            outer:
            for (BlockPos p : chests) {
                if (!(level.getBlockEntity(p) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (free >= 3) break outer;
                    if (!c.getItem(i).is(Items.OAK_PLANKS)) { c.setItem(i, ItemStack.EMPTY); free++; }
                }
            }
            int before = Villages.storeChests(level, v).size();
            StoreRoom.ahead(level, v);
            int after = Villages.storeChests(level, v).size();
            Kit.log("[VT] sc05 stores " + all + " places, " + free + " free: chests " + before + " -> " + after + " " + java.util.Arrays.toString(StoreRoom.last(v)));
            helper.assertTrue(after > before, "another chest set down before the stores were full: " + before + " -> " + after);
        } finally {
            TownJobs.instantForTests(false);
        }
        helper.succeed();
    }
}
