package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
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

    /** A chest of the town's stores by its heart, holding these (its old contents gone). */
    private static Container stores(ServerLevel level, UUID village, int x, ItemStack... goods) {
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
            stores(level, id, x, new ItemStack(Items.IRON_INGOT, 40), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.STICK, 32),
                new ItemStack(Items.BOW), new ItemStack(Items.BOW), new ItemStack(Items.BOW), new ItemStack(Items.BOW));
            List<String> made = new ArrayList<>();
            for (int i = 0; i < 6 && stock(level, id, pick) == 0; i++) made.add(String.valueOf(Crafts.smithForTests(level, v, smith)));
            int picks = stock(level, id, pick);
            Kit.log("sc2_06 no toolshop: the smith " + made + "; iron picks " + picks);
            ok.that(picks >= 1, "with no toolshop, the town's smith forges the iron pick: " + picks);
            // A toolshop of the town's own folk, iron picks on its shelf.
            stores(level, id, x, new ItemStack(Items.IRON_INGOT, 40), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.STICK, 32),
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
}
