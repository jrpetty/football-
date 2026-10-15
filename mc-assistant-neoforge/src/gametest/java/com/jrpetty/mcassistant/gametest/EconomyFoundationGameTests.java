package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Accounts;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bank;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.HousingMarket;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MarketMaker;
import com.jrpetty.mcassistant.entity.Privatisation;
import com.jrpetty.mcassistant.entity.Purchases;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.Tourists;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Visitors;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.Showcase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [econ-1a] The foundation of the market economy: accounts at the choke points (Accounts), the storehouse as the town's
 * market (MarketMaker), every trade a business (Privatisation), the mint, the coin census at zero drift.
 *
 * <p>Each on its own ground, x 2560000 to 2619999 (2000 apart), z 70000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class EconomyFoundationGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    /** A town of so many folk, the first of each trade given, every purse emptied then given {@code purse}; the market at work. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int purse, StationTask... trades) {
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        level.setDayTime(8 * 24000L + 7000L);                          // past the morning's business: only ours runs
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 3 + (i % 4) * 2, Z - 2 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(trades[i]);
            f.spend(f.purse());
            f.earn(purse);
            folk.add(f);
        }
        return folk;
    }

    /** A chest of the town's stores by its heart, holding these. */
    private static Container stores(ServerLevel level, UUID village, int x, ItemStack... goods) {
        BlockPos at = Kit.surface(level, x + 6, Z + 6);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length && i < c.getContainerSize(); i++) c.setItem(i, goods[i].copy());
        c.setChanged();
        Villages.forgetStores(village);
        return c;
    }

    private static ItemStack[] lots(Item it, int stacks) {
        ItemStack[] out = new ItemStack[stacks];
        for (int i = 0; i < stacks; i++) out[i] = new ItemStack(it, it.getDefaultMaxStackSize());
        return out;
    }

    private static ItemStack[] join(ItemStack[]... parts) {
        List<ItemStack> all = new ArrayList<>();
        for (ItemStack[] p : parts) all.addAll(List.of(p));
        return all.toArray(new ItemStack[0]);
    }

    private static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    // ============================================================ acc01 the smith

    /**
     * A smith's craft turn at its smithy: its iron, sticks and coal bought of the market at the ask out of the smithy's
     * till (into the market's cash), the pick made of them sold to the market, its wage paid out of the till; the smithy's
     * profit is exactly its takings less its makings and its hand's wage, to the penny, and not a penny made or lost.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "acc01_smith")
    public static void acc01_smith(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2560000;
        List<VillageFolkEntity> folk = town(helper, level, x, 30, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity smith = folk.get(0);
        UUID id = smith.ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, join(lots(Items.IRON_INGOT, 1), lots(Items.STICK, 1), lots(Items.COAL, 1)));
        Ledger.addCoins(id, 400);
        MarketMaker.openForTests(level, v);
        Villages.builtAtForTests(id, "smithy", Kit.surface(level, x + 12, Z));
        Businesses.ensure(level, id);
        Business smithy = Businesses.of(id, "smithy");
        helper.assertTrue(smithy != null && !smithy.town(), "the smithy is a private business: " + (smithy == null ? "none" : smithy.ownersWords()));
        ItemStack iron = new ItemStack(Items.IRON_INGOT), stick = new ItemStack(Items.STICK), coal = new ItemStack(Items.COAL), pick = new ItemStack(Items.IRON_PICKAXE);
        MarketMaker.midForTests(iron, 1.0);
        MarketMaker.midForTests(stick, 0.05);
        MarketMaker.midForTests(coal, 0.3);
        MarketMaker.midForTests(pick, 6.0);
        Businesses.fundForTests(id, smithy, 1500);
        long dayNo = day(level);
        Business.Day d0 = smithy.last(dayNo, 1);
        long rev0 = d0.revenue, cogs0 = d0.cogs, wages0 = d0.wages, net0 = d0.net();
        long till0 = smithy.till, cash0 = MarketMaker.cash(id), census0 = CoinCensus.count(id);
        long inputs = Businesses.orderCost(MarketMaker.ask(level, id, iron), 3)[0] + Businesses.orderCost(MarketMaker.ask(level, id, stick), 2)[0]
            + Businesses.orderCost(MarketMaker.ask(level, id, coal), 1)[0];
        Map<String, Long> used = new LinkedHashMap<>();
        used.put("minecraft:iron_ingot", 3L);
        used.put("minecraft:stick", 2L);
        used.put("minecraft:coal", 1L);
        Map<String, ItemStack> samples = Map.of("minecraft:iron_ingot", iron, "minecraft:stick", stick, "minecraft:coal", coal, "minecraft:iron_pickaxe", pick);
        long cost = Businesses.madeForTests(level, id, smithy, used, Map.of("minecraft:iron_pickaxe", 1L), samples);
        Business.Day d1 = smithy.last(dayNo, 1);
        long revenue = d1.revenue - rev0;
        int paid = Businesses.paysHand(level, v, smith, 3);
        Business.Day d2 = smithy.last(dayNo, 1);
        long wages = d2.wages - wages0;
        long profit = d2.net() - net0;
        Kit.log("acc01 inputs at the ask " + inputs + " (cost booked " + cost + "), pick sold to the market for " + revenue + ", wage " + wages
            + " (net to the smith " + paid + "); profit " + profit + "; till " + till0 + " -> " + smithy.till + ", market " + cash0 + " -> " + MarketMaker.cash(id));
        Kit.Expect ok = new Kit.Expect();
        ok.that(cost == inputs, "its makings cost what it paid the market at the ask: " + cost + " = " + inputs);
        ok.that(revenue > 0 && d2.cogs - cogs0 == inputs, "the pick sold, at the cost of its makings: takings " + revenue + ", cost of goods " + (d2.cogs - cogs0));
        ok.that(wages == 300, "its hand's wage out of its till: " + wages);
        ok.that(profit == revenue - inputs - wages, "profit = takings - makings - wages, to the penny: " + profit + " = " + revenue + " - " + inputs + " - " + wages);
        ok.that(smithy.till == till0 - inputs + revenue - wages, "its till: " + till0 + " - " + inputs + " + " + revenue + " - " + wages + " = " + smithy.till);
        ok.that(MarketMaker.cash(id) == cash0 + inputs - revenue, "the market's cash: + its makings - the pick: " + cash0 + " -> " + MarketMaker.cash(id));
        ok.that(CoinCensus.count(id) == census0, "not a penny made or lost: " + census0 + " -> " + CoinCensus.count(id));
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ acc02 the farmer

    /**
     * A farmer's sixty-four wheat brought in (DepositGoal: the wheat into the stores, the hand-back check, Economy.produced)
     * pays its farm sixty-four times the market's bid; the market's stock is up sixty-four, its cash down by as much as the
     * farm's till is up, and the stores' own books hold the wheat at what the market paid.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "acc02_farmer")
    public static void acc02_farmer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2562000;
        List<VillageFolkEntity> folk = town(helper, level, x, 10, StationTask.FARM, StationTask.FARM, StationTask.MINE);
        VillageFolkEntity farmer = folk.get(0);
        UUID id = farmer.ownerId();
        Villages.Village v = Villages.get(id);
        Container chest = stores(level, id, x, new ItemStack(Items.COBBLESTONE, 64));
        Ledger.addCoins(id, 300);
        MarketMaker.openForTests(level, v);
        Business farm = Businesses.employer(id, farmer);
        helper.assertTrue(farm != null && farm.type == Business.Type.FARM && farm.ownedBy(farmer.getUUID()),
            "the farmer works its own farm: " + (farm == null ? "none" : farm.name + " of " + farm.ownersWords()));
        ItemStack wheat = new ItemStack(Items.WHEAT, 64);
        MarketMaker.midForTests(wheat, 0.10);
        double bid = MarketMaker.bid(level, id, wheat);
        double spread = MarketMaker.spread(id);
        long expect = Math.round(bid * 100 * 64);
        long till0 = farm.till, cash0 = MarketMaker.cash(id), census0 = CoinCensus.count(id), rev0 = farm.allTime.revenue;
        int stock0 = Market.stock(level, id, s -> s.is(Items.WHEAT));
        // The deposit, as DepositGoal makes it.
        chest.setItem(5, wheat.copy());
        chest.setChanged();
        MarketMaker.deposited(farmer, wheat, 64);
        Economy.produced(farmer, wheat.copy());
        Kit.log("acc02 bid " + bid + ", 64 wheat " + expect + "p; farm till " + till0 + " -> " + farm.till + ", market " + cash0 + " -> " + MarketMaker.cash(id)
            + "; tallies " + MarketMaker.tallies(id));
        Kit.Expect ok = new Kit.Expect();
        ok.that(Math.abs(bid - 0.10 * (1 - spread / 2)) < 1e-9, "the bid is the going price less half the spread: " + bid);
        ok.that(farm.till == till0 + expect, "the farm paid 64 x the bid: " + (farm.till - till0) + " = " + expect);
        ok.that(farm.allTime.revenue == rev0 + expect, "its takings booked: " + (farm.allTime.revenue - rev0));
        ok.that(MarketMaker.cash(id) == cash0 - expect, "the market's cash down as much: " + (cash0 - MarketMaker.cash(id)));
        ok.that(Market.stock(level, id, s -> s.is(Items.WHEAT)) == stock0 + 64, "its stock up 64");
        ok.that(CoinCensus.count(id) == census0, "not a penny made or lost");
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ acc03 meals

    /**
     * A loaf out of the stores is charged to the eater's household at the market's ask (a coin out of its purse, the odd
     * pennies kept on account as change); a child's to its parent; a poor folk's with nothing in its purse is paid by the
     * town's relief, and never refused.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "acc03_meals")
    public static void acc03_meals(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2564000;
        List<VillageFolkEntity> folk = town(helper, level, x, 10, StationTask.FARM, StationTask.FARM, StationTask.MINE, StationTask.WOOD);
        VillageFolkEntity parent = folk.get(0), child = folk.get(1), poor = folk.get(2);
        UUID id = parent.ownerId();
        Villages.Village v = Villages.get(id);
        child.setChild(true);
        child.parentIds().add(parent.getUUID());
        stores(level, id, x, new ItemStack(Items.BREAD, 64));
        Ledger.addCoins(id, 300);
        MarketMaker.openForTests(level, v);
        poor.spend(poor.purse());                                      // after its homestead's grant: nothing in its purse
        ItemStack bread = new ItemStack(Items.BREAD);
        MarketMaker.midForTests(bread, 0.5);
        long price = Math.round(MarketMaker.ask(level, id, bread) * 100);
        int purse0 = parent.purse();
        long cash0 = MarketMaker.cash(id), census0 = CoinCensus.count(id);
        Kit.Expect ok = new Kit.Expect();
        ok.that(MarketMaker.resolveForTests(id, parent, bread).account().kind() == Accounts.Kind.FOLK, "a grown folk's loaf is its household's");
        boolean a = MarketMaker.takeForTests(level, v, parent, null, Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 1);
        ok.that(a && parent.purse() == purse0 - 1 && Accounts.changeForTests(id, parent) == 100 - price,
            "its loaf: a coin out of its purse, " + (100 - price) + "p change on account (" + parent.purse() + ", " + Accounts.changeForTests(id, parent) + ")");
        ok.that(MarketMaker.cash(id) == cash0 + price, "the market took the price: " + (MarketMaker.cash(id) - cash0) + " = " + price);
        // The child's loaf: its parent pays.
        int childPurse = child.purse();
        long change1 = Accounts.changeForTests(id, parent);
        int parentPurse1 = parent.purse();
        price = Math.round(MarketMaker.ask(level, id, bread) * 100);      // the spread narrows as the market trades
        boolean b = MarketMaker.takeForTests(level, v, child, null, Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 1);
        long paidByParent = (parentPurse1 - parent.purse()) * 100L + change1 - Accounts.changeForTests(id, parent);
        ok.that(b && paidByParent == price && child.purse() == childPurse, "the child's loaf charged to its parent: " + paidByParent);
        // The poor folk's: the relief pays, never refused.
        long tre0 = Businesses.treasuryPence(id), relief0 = Accounts.deptBook(id, Accounts.Dept.RELIEF)[2];
        price = Math.round(MarketMaker.ask(level, id, bread) * 100);
        boolean c = MarketMaker.takeForTests(level, v, poor, null, Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 1);
        long relief = Accounts.deptBook(id, Accounts.Dept.RELIEF)[2] - relief0;
        ok.that(c, "the poor folk's loaf is not refused");
        ok.that(relief == price && Businesses.treasuryPence(id) == tre0 - price, "the relief paid for it out of the treasury: " + relief);
        ok.that(Accounts.owed(id, Accounts.Account.folk(poor)) == 0, "nothing on its slate");
        ok.that(CoinCensus.count(id) == census0, "not a penny made or lost: " + census0 + " -> " + CoinCensus.count(id));
        Kit.log("acc03 price " + price + "p; tallies " + MarketMaker.tallies(id));
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ acc04 the founders' commons

    /**
     * Before Market Opening Day the stores are the founders' commons: a take is free (counted). On the day the commons'
     * stock is the market's, the treasury puts up the market's float, and each producing founder is given the title to its
     * homestead (its own business, six coins for its household, four in its till); after it a take is charged.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "acc04_commons")
    public static void acc04_commons(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2566000;
        List<VillageFolkEntity> folk = town(helper, level, x, 10, StationTask.FARM, StationTask.WOOD, StationTask.MINE, StationTask.FISH);
        VillageFolkEntity farmer = folk.get(0);
        UUID id = farmer.ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, new ItemStack(Items.BREAD, 64), new ItemStack(Items.COBBLESTONE, 64));
        Ledger.addCoins(id, 300);
        Kit.Expect ok = new Kit.Expect();
        ok.that(MarketMaker.commons(id) && !MarketMaker.live(id), "the founders' commons before Market Opening Day");
        int purse0 = farmer.purse();
        long cash0 = MarketMaker.cash(id), tre0 = Businesses.treasuryPence(id);
        boolean took = MarketMaker.takeForTests(level, v, farmer, null, Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 2);
        ok.that(took && farmer.purse() == purse0 && Businesses.treasuryPence(id) == tre0, "free out of the commons");
        ok.that(MarketMaker.tallies(id).getOrDefault("commons", 0L) == 2, "and counted: " + MarketMaker.tallies(id));
        MarketMaker.openForTests(level, v);
        ok.that(MarketMaker.live(id), "Market Opening Day: the market is open");
        ok.that(MarketMaker.cash(id) > cash0, "the treasury put up its float: " + MarketMaker.cash(id));
        int titles = 0;
        for (VillageFolkEntity f : folk) {
            Business b = Businesses.employer(id, f);
            if (b != null && b.ownedBy(f.getUUID()) && b.till >= 400) titles++;
        }
        ok.that(titles == 4, "each producing founder owns its homestead, four coins in its till: " + titles);
        ok.that(farmer.purse() == purse0 + 6, "six coins for its household: " + purse0 + " -> " + farmer.purse());
        boolean told = false;
        for (Villages.News n : Villages.news(id)) if (n.text().contains("Market Opening Day")) told = true;
        ok.that(told, "the town told of its Market Opening Day");
        int purse1 = farmer.purse();
        MarketMaker.takeForTests(level, v, farmer, null, Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 1);
        ok.that(farmer.purse() < purse1 || Accounts.changeForTests(id, farmer) > 0, "after it, a loaf is bought: " + purse1 + " -> " + farmer.purse());
        Kit.log("acc04 " + Privatisation.lines(level, id) + "; " + MarketMaker.tallies(id));
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ acc05 no double charge

    /**
     * A thing bought at a counter is paid for once: the counter's charge, never the market's ask again at the stores.
     * Purchases (the folk's own shopping, its loaf), a counter sale booked after its take in the same tick and one booked
     * before it, and a household's own house going up a course at a time (its bill pays the market for the blocks).
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "acc05_once")
    public static void acc05_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2568000;
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        Bank.resetForTests();
        HousingMarket.resetForTests();
        HousingMarket.openForTests(true);
        level.setDayTime(24000L * 8 + 7000);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        List<VillageFolkEntity> all = new ArrayList<>();
        all.add(first);
        for (int i = 1; i < 4; i++) all.add(VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 2 * i, Z + 3), 0.0F));
        Villages.ageForTests(id, Villages.Age.IRON);
        for (VillageFolkEntity f : all) {
            f.spend(f.purse());
            f.rentFree(false);
            f.setJob(StationTask.FARM);
            for (Values.Value val : Values.Value.values()) Values.setForTests(f, val, val == Values.Value.FOOD ? 100 : 0);
            f.life().setTraitsForTests(Social.Trait.HARDWORKING, Social.Trait.SHY);
        }
        for (int n = 0; n < 2; n++) {
            BlockPos at = Kit.surface(level, x - 30 + 14 * n, Z + 52);
            BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", at, Direction.NORTH);
        }
        ItemStack[][] goods = {
            { new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.STONE_BRICKS, 64),
                new ItemStack(Items.GLASS, 32), new ItemStack(Items.GLASS_PANE, 32), new ItemStack(Items.TORCH, 32),
                new ItemStack(Items.LANTERN, 8), new ItemStack(Items.WHITE_WOOL, 16), new ItemStack(Items.BREAD, 64) },
            { new ItemStack(Items.STONE, 64), new ItemStack(Items.BRICKS, 64), new ItemStack(Items.BRICKS, 64),
                new ItemStack(Items.COBBLED_DEEPSLATE, 64), new ItemStack(Items.DEEPSLATE_TILES, 64), new ItemStack(Items.DEEPSLATE_TILES, 64),
                new ItemStack(Items.RED_CARPET, 16), new ItemStack(Items.POPPY, 8), new ItemStack(Items.BARREL, 2),
                new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED),
                new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED) } };
        int[][] at = { { 4, -4 }, { -4, -4 } };
        for (int i = 0; i < goods.length; i++) {
            BlockPos chest = Kit.surface(level, heart.getX() + at[i][0], heart.getZ() + at[i][1]);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, chest);
            Container box = (Container) level.getBlockEntity(chest);
            for (int k = 0; k < goods[i].length; k++) box.setItem(k, goods[i][k]);
            box.setChanged();
        }
        Villages.forgetStores(id);
        Ledger.addCoins(id, 300);
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            MarketMaker.openForTests(level, v);
            Purchases.openForTests(id, true);
            ItemStack bread = new ItemStack(Items.BREAD);
            MarketMaker.midForTests(bread, 0.5);
            // (a) Purchases: the folk's own loaf, bought at the counter.
            VillageFolkEntity buyer = all.get(1);
            buyer.earn(20);
            int p0 = buyer.purse();
            long census0 = CoinCensus.count(id);
            int got = Purchases.get(level, buyer, s -> s.is(Items.BREAD), 1, Purchases.Need.FOOD);
            long spent = Purchases.spentTodayForTests(buyer);
            ok.that(got >= 1, "a loaf bought at the counter: " + got);
            ok.that(Accounts.changeForTests(id, buyer) == 0 && Accounts.owed(id, Accounts.Account.folk(buyer)) == 0,
                "nothing charged at the stores besides: change " + Accounts.changeForTests(id, buyer));
            ok.that((p0 - buyer.purse()) * 100L <= spent + 99, "its purse paid the counter's price only: " + (p0 - buyer.purse()) + " coin for " + spent + "p");
            ok.that(CoinCensus.count(id) == census0, "not a penny made or lost by the purchase");
            // (b) A counter's sale booked after its take, the same tick.
            VillageFolkEntity b2 = all.get(2);
            b2.earn(10);
            int q0 = b2.purse();
            MarketMaker.takeForTests(level, v, b2, null, Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 1);
            if (b2.spend(1)) {
                Ledger.addCoins(id, 1);
                Stockroom.sold(level, id, Stockroom.Seller.STORES, bread.copy(), 1, 1);
            }
            ok.that(b2.purse() == q0 - 1 && Accounts.changeForTests(id, b2) == 0, "taken then sold at the counter: the counter's coin only ("
                + q0 + " -> " + b2.purse() + ", change " + Accounts.changeForTests(id, b2) + ")");
            // (c) A counter's sale booked before its take, the same tick.
            VillageFolkEntity b3 = all.get(3);
            b3.earn(10);
            int r0 = b3.purse();
            if (b3.spend(1)) {
                Ledger.addCoins(id, 1);
                Stockroom.sold(level, id, Stockroom.Seller.STORES, bread.copy(), 1, 1);
            }
            MarketMaker.takeForTests(level, v, b3, null, Accounts.Purpose.FOOD, s -> s.is(Items.BREAD), 1);
            ok.that(b3.purse() == r0 - 1 && Accounts.changeForTests(id, b3) == 0, "sold at the counter then taken: the counter's coin only");
            // (d) A household's house: its bill pays the market for the blocks, the course's takes are free.
            VillageFolkEntity owner = all.get(0);
            for (Values.Value val : Values.Value.values()) Values.setForTests(owner, val, val == Values.Value.HOMES ? 100 : 0);
            owner.setJob(StationTask.SMITH);
            owner.earn(400);
            List<VillageFolkEntity> household = HousingMarket.householdForTests(owner);
            long cash1 = MarketMaker.cash(id);
            long census1 = CoinCensus.count(id);
            boolean made = HousingMarket.commissionForTests(level, v, household, HousingMarket.Design.COTTAGE);
            int[] bill = HousingMarket.buildForTests(id);
            int purseAfterBill = owner.purse();
            int visits = 0;
            while (made && HousingMarket.buildForTests(id) != null && visits < 60) {
                bill = HousingMarket.buildForTests(id);
                String last = HousingMarket.workForTests(level, v, 24);
                visits++;
                if (last.startsWith("waiting")) break;
            }
            Map<String, Long> how = MarketMaker.tallies(id);
            long charged = 0;
            for (Map.Entry<String, Long> e : how.entrySet()) if (e.getKey().startsWith("charged to")) charged += e.getValue();
            ok.that(made, "a cottage commissioned");
            ok.that(owner.purse() == purseAfterBill, "the household paid its bill once (nothing more at the stores): " + purseAfterBill + " -> " + owner.purse());
            ok.that(how.getOrDefault("free: a household's house, paid by the course", 0L) > 0, "its blocks free at the stores, paid by the course: " + how);
            ok.that(MarketMaker.cash(id) > cash1, "the market had the blocks' price from the bill: " + cash1 + " -> " + MarketMaker.cash(id));
            ok.that(CoinCensus.count(id) == census1, "not a penny made or lost by the build: " + census1 + " -> " + CoinCensus.count(id));
            Kit.log("acc05 bill " + (bill == null ? "done" : java.util.Arrays.toString(bill)) + " after " + visits + " visits; tallies " + how);
            helper.assertTrue(ok.clean(), ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ acc06 no stragglers

    /**
     * A town of twenty founded and run a whole day with its market open: every take out of its stores is answered for by
     * somebody's account (a business, a household, a department); not one straggler.
     */
    @GameTest(template = EMPTY, timeoutTicks = 120000, batch = "acc06_stragglers")
    public static void acc06_no_stragglers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int cx = 2570000;
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        com.jrpetty.mcassistant.entity.Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        int outer = com.jrpetty.mcassistant.village.FoundingPlan.coreRadius(20) + com.jrpetty.mcassistant.village.FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, Z, outer + 32);
        Kit.prepare(level, cx, Z, outer + 32);
        BlockPos heart = Kit.surface(level, cx, Z);
        Kit.generousTerrain(level, cx, Z);
        Kit.log("acc06 found: " + Kit.command(level, "village found 20 " + cx + " " + Z));
        server.tickRateManager().setTickRate(2000.0F);
        final long[] began = { -1 };
        final UUID[] town = { null };
        final boolean[] over = { false };
        final String[] verdict = { null };
        // The verdict is handed to the test's own tick (an exception out of the server's tick event would stop the server):
        // looked at every hundred ticks.
        final Runnable[] look = { null };
        look[0] = () -> {
            if (verdict[0] == null) helper.runAfterDelay(100, () -> look[0].run());   // a new one each time: the helper keys them by the runnable
            else if (verdict[0].isEmpty()) helper.succeed();
            else helper.fail(verdict[0]);
        };
        helper.runAfterDelay(100, look[0]);
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> each = e -> {
            if (over[0]) return;
            long t = helper.getTick();
            try {
                if (began[0] < 0) {
                    if (com.jrpetty.mcassistant.entity.Founding.near(level, heart, 8)) return;
                    Villages.Village v = Villages.nearest(level, heart, 64);
                    if (v == null) {
                        over[0] = true;
                        server.tickRateManager().setTickRate(20.0F);
                        verdict[0] = "no village founded";
                        return;
                    }
                    town[0] = v.id();
                    Kit.stockForGrowth(level, v);
                    MarketMaker.openForTests(level, v);
                    began[0] = t;
                    Kit.log("acc06 " + Villages.name(v.id()) + " of " + Villages.headcount(v.id()) + " founded, its market open, at tick " + t);
                    return;
                }
                long ran = t - began[0];
                UUID id = town[0];
                if (ran % 4000 == 0) Kit.log("acc06 +" + ran + ": stragglers " + Accounts.stragglersAll(id) + ", takes " + MarketMaker.tallies(id));
                if (ran >= 24000) {
                    over[0] = true;
                    server.tickRateManager().setTickRate(20.0F);
                    long n = Accounts.stragglersAll(id);
                    Kit.log("acc06 a day: stragglers " + n + " " + Accounts.stragglerSites(level, id) + "; takes " + MarketMaker.tallies(id));
                    Kit.log("acc06 " + MarketMaker.words(level, id).replace('\n', '|'));
                    Kit.log("acc06 the town's systems on its works account: " + Accounts.systemSites(level, id));
                    verdict[0] = n == 0 ? "" : "takes nobody's account answered for: " + n + " " + Accounts.stragglerSites(level, id);
                }
            } catch (RuntimeException ex) {
                over[0] = true;
                server.tickRateManager().setTickRate(20.0F);
                verdict[0] = "the run threw " + ex;
                Kit.log("acc threw " + ex);
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }

    // ============================================================ acc07 viability

    /**
     * Before anybody is charged: can every producing trade pay its hand's wage at the market's prices? Each trade's day's
     * takings at the bid (less what its work uses up at the ask) at least 1.3 times its going wage.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "acc07_viability")
    public static void acc07_viability(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2572000;
        List<VillageFolkEntity> folk = town(helper, level, x, 10, StationTask.FARM, StationTask.WOOD, StationTask.MINE, StationTask.FISH,
            StationTask.RANCH, StationTask.HUNT, StationTask.SMELT, StationTask.FARM);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Ledger.addCoins(id, 300);
        MarketMaker.openForTests(level, v);
        Kit.Expect ok = new Kit.Expect();
        for (MarketMaker.Viable r : MarketMaker.viabilityForTests(level, id)) {
            Kit.log("acc07 " + r.words());
            ok.that(r.ratio() >= 1.3, r.trade() + " pays its wage: " + r.words());
        }
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ acc08 the census

    /**
     * The town's coin counted each morning over three days, with the traders buying its glut (exports), wool bought in
     * (imports), a tourist with a purse of its own coming and going, a death (its purse to its heir), the mint striking the
     * town's gold, the wages, the businesses' mornings and the market's: every morning's drift is nought.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "acc08_census")
    public static void acc08_census(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2574000;
        List<VillageFolkEntity> folk = town(helper, level, x, 12, StationTask.FARM, StationTask.MINE, StationTask.WOOD, StationTask.FISH,
            StationTask.STORE, StationTask.HAUL);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, join(lots(Items.COBBLESTONE, 12), lots(Items.OAK_LOG, 6), lots(Items.GOLD_INGOT, 1), lots(Items.BREAD, 2)));
        Ledger.addCoins(id, 60);
        MarketMaker.openForTests(level, v);
        Villages.ageForTests(id, Villages.Age.IRON);
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            long d0 = day(level);
            CoinCensus.morning(level, v, d0);
            List<String> did = new ArrayList<>();
            for (int k = 1; k <= 3; k++) {
                level.setDayTime((d0 + k) * 24000L + 7000L);
                long dd = day(level);
                switch (k) {
                    case 1 -> {
                        int exp;
                        try (Accounts.Scope m = Accounts.as(Accounts.Dept.MARKET, Accounts.Purpose.WORK)) { exp = Market.trade(level, v); }
                        int imp;
                        try (Accounts.Scope m = Accounts.as(Accounts.Dept.MARKET, Accounts.Purpose.WORK)) { imp = Market.buyWool(level, v, dd); }
                        did.add("exports " + exp + ", imports " + imp);
                    }
                    case 2 -> {
                        VillageFolkEntity tourist = Tourists.arriveNowForTests(level, v);
                        did.add("a tourist " + (tourist == null ? "did not come" : "with " + tourist.purse()));
                        if (tourist != null) {
                            Visitors.arriveForTests(level, tourist);
                            Visitors.goneForTests(level, tourist);
                        }
                        VillageFolkEntity dead = folk.get(3);
                        dead.earn(9);
                        dead.kill();
                        did.add("a death");
                    }
                    case 3 -> {
                        int minted;
                        try (Accounts.Scope m = Accounts.as(Accounts.Dept.MINT, Accounts.Purpose.WORK)) { minted = Market.mint(level, v); }
                        did.add("minted " + minted);
                    }
                    default -> { }
                }
                MarketMaker.morningForTests(level, v);
                Businesses.morningForTests(level, v);
                Market.payWages(level, v);
                CoinCensus.morning(level, v, dd);
                long drift = CoinCensus.lastDrift(id);
                Kit.log("acc08 day " + k + " (" + did.get(did.size() - 1) + "): " + CoinCensus.line(id));
                ok.that(drift == 0, "day " + k + ": drift " + drift + " (" + CoinCensus.line(id) + ")");
            }
            helper.assertTrue(ok.clean(), ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ acc09 public works

    /**
     * The town's works pay for what they build with: a builder holding the town's building lead draws stone for a well,
     * and the town's works buy it of the market at the ask out of the treasury (the treasury down, the market's cash up as
     * much, the works' book showing it); what it hands back within the day is refunded at what was paid.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "acc09_works")
    public static void acc09_public_works(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2576000;
        List<VillageFolkEntity> folk = town(helper, level, x, 5, StationTask.FARM, StationTask.MINE, StationTask.WOOD);
        VillageFolkEntity builder = folk.get(0);
        UUID id = builder.ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, lots(Items.COBBLESTONE, 4));
        Ledger.addCoins(id, 300);
        MarketMaker.openForTests(level, v);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE);
        MarketMaker.midForTests(cobble, 0.05);
        Kit.Expect ok = new Kit.Expect();
        ok.that(Villages.isLead(id, builder.getUUID(), level.getGameTime()), "the builder holds the lead");
        ok.that(MarketMaker.resolveForTests(id, builder, cobble).account().deptOf() == Accounts.Dept.WORKS, "its stone is the town's works'");
        long price = Math.round(MarketMaker.ask(level, id, cobble) * 100 * 64);
        long tre0 = Businesses.treasuryPence(id), cash0 = MarketMaker.cash(id), works0 = Accounts.deptBook(id, Accounts.Dept.WORKS)[2];
        int purse0 = builder.purse();
        boolean took = MarketMaker.takeForTests(level, v, builder, null, Accounts.Purpose.WORK, s -> s.is(Items.COBBLESTONE), 64);
        ok.that(took, "the stone drawn");
        ok.that(Businesses.treasuryPence(id) == tre0 - price, "the treasury paid the ask: " + (tre0 - Businesses.treasuryPence(id)) + " = " + price);
        ok.that(MarketMaker.cash(id) == cash0 + price, "the market's cash up as much");
        ok.that(Accounts.deptBook(id, Accounts.Dept.WORKS)[2] - works0 == price, "in the works' book");
        ok.that(builder.purse() == purse0, "not the builder's own coin");
        long tre1 = Businesses.treasuryPence(id);
        MarketMaker.giveForTests(level, v, builder, null, Accounts.Purpose.RETURN, new ItemStack(Items.COBBLESTONE, 20));
        long back = Businesses.treasuryPence(id) - tre1;
        ok.that(back == Math.round(price * 20.0 / 64), "twenty handed back, refunded at what was paid: " + back);
        Kit.log("acc09 64 cobble " + price + "p to the works; 20 back " + back + "p; " + MarketMaker.tallies(id));
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ acc10 a requisition

    /**
     * An emergency with an empty treasury: the watch requisitions what it needs and the market is not refused it, but the
     * town writes it an IOU; the next tax that comes in pays the IOU first, into the market's cash.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "acc10_requisition")
    public static void acc10_requisition(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2578000;
        List<VillageFolkEntity> folk = town(helper, level, x, 5, StationTask.GUARD, StationTask.FARM, StationTask.MINE);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, join(lots(Items.ARROW, 1), lots(Items.IRON_SWORD, 1)));
        Ledger.addCoins(id, 100);
        MarketMaker.openForTests(level, v);
        Ledger.takeCoins(id, Ledger.coins(id));
        Businesses.fromTreasuryForTests(id, Businesses.treasuryPence(id));
        ItemStack arrow = new ItemStack(Items.ARROW);
        MarketMaker.midForTests(arrow, 0.2);
        long price = Math.round(MarketMaker.ask(level, id, arrow) * 100 * 32);
        long cash0 = MarketMaker.cash(id), census0 = CoinCensus.count(id);
        Kit.Expect ok = new Kit.Expect();
        boolean took = MarketMaker.takeForTests(level, v, null, Accounts.Account.dept(Accounts.Dept.WATCH), Accounts.Purpose.REQUISITION, s -> s.is(Items.ARROW), 32);
        ok.that(took, "the watch has its arrows, the treasury empty");
        ok.that(Accounts.debts(id) == price, "an IOU to the market for them: " + Accounts.debts(id) + " = " + price + " " + Accounts.debtLines(id));
        ok.that(MarketMaker.cash(id) == cash0, "no coin moved yet");
        // The next tax: the wage tax comes into the treasury, the IOU paid first out of it.
        Ledger.addCoins(id, 20);
        long paid = Accounts.taxIn(id, 2000);
        ok.that(paid == price && Accounts.debts(id) == 0, "the IOU paid first out of the tax: " + paid);
        ok.that(MarketMaker.cash(id) == cash0 + price, "into the market's cash");
        ok.that(CoinCensus.count(id) == census0 + 2000, "only the tax's coin new to the count");
        Kit.log("acc10 IOU " + price + "p, paid " + paid + "p");
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ acc11 ten days

    /**
     * A town founded and left to itself for ten days with the market at work (its Market Opening Day as it comes), its
     * stores topped up each morning with building stuff and bread as the long growth tests do: it still founds, grows,
     * feeds itself and builds; its market opens; no work stands still for want of money. Each day's census, stragglers and
     * the market's trade are logged, and every unbooked change of its coin with where it happened (CoinCensus.probe).
     */
    @GameTest(template = EMPTY, timeoutTicks = 900000, required = false, batch = "acc11_ten_days")
    public static void acc11_ten_days(GameTestHelper helper) {
        tenDays(helper, 2590000, true);
    }

    /** The same ten days with no market (the town as it was), for the comparison: not required. */
    @GameTest(template = EMPTY, timeoutTicks = 900000, required = false, batch = "acc12_ten_days_plain")
    public static void acc12_ten_days_plain(GameTestHelper helper) {
        tenDays(helper, 2600000, false);
    }

    private static void tenDays(GameTestHelper helper, int cx, boolean market) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Kit.reset(level);
        MarketMaker.liveForTests(market);
        String tag = market ? "acc11" : "acc12";
        com.jrpetty.mcassistant.entity.Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        int outer = com.jrpetty.mcassistant.village.FoundingPlan.coreRadius(16) + com.jrpetty.mcassistant.village.FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, Z, outer + 32);
        Kit.prepare(level, cx, Z, outer + 32);
        BlockPos heart = Kit.surface(level, cx, Z);
        Kit.generousTerrain(level, cx, Z);
        Kit.log(tag + " found: " + Kit.command(level, "village found 16 " + cx + " " + Z));
        server.tickRateManager().setTickRate(10000.0F);
        final long[] began = { -1 };
        final int[] start = { 0, 0, -1 };
        final UUID[] town = { null };
        final boolean[] over = { false };
        final String[] verdict = { null };
        // The verdict is handed to the test's own tick (an exception out of the server's tick event would stop the server):
        // looked at every hundred ticks.
        final Runnable[] look = { null };
        look[0] = () -> {
            if (verdict[0] == null) helper.runAfterDelay(100, () -> look[0].run());   // a new one each time: the helper keys them by the runnable
            else if (verdict[0].isEmpty()) helper.succeed();
            else helper.fail(verdict[0]);
        };
        helper.runAfterDelay(100, look[0]);
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> each = e -> {
            if (over[0]) return;
            long t = helper.getTick();
            try {
                if (began[0] < 0) {
                    if (com.jrpetty.mcassistant.entity.Founding.near(level, heart, 8)) return;
                    Villages.Village v = Villages.nearest(level, heart, 64);
                    if (v == null) {
                        over[0] = true;
                        server.tickRateManager().setTickRate(20.0F);
                        verdict[0] = "no village founded";
                        return;
                    }
                    town[0] = v.id();
                    began[0] = t;
                    start[0] = Villages.headcount(v.id());
                    start[1] = Ledger.buildings(v.id()).size();
                    CoinCensus.probeForTests(v.id(), true);              // every unbooked change of its coin logged with where
                    Kit.log(tag + " " + Villages.name(v.id()) + " of " + start[0] + " founded at tick " + t + ", " + start[1] + " buildings");
                    return;
                }
                long ran = t - began[0];
                UUID id = town[0];
                int dayNo = (int) (ran / 24000L);
                if (dayNo != start[2]) {
                    start[2] = dayNo;
                    // The stores topped up each morning, as the long growth tests do (a test of the town under the market,
                    // not of how fast a new town's hands fell and quarry): wood, stone, the fittings and bread.
                    Kit.stockForGrowth(level, Villages.get(id));
                    Kit.log(tag + " day " + dayNo + ": " + Villages.headcount(id) + " folk, " + Ledger.buildings(id).size() + " buildings, food "
                        + Villages.stock(level, Villages.get(id).centre(), Villages.Task.FOOD, Villages.storesRadius(id)) + ", treasury " + Ledger.coins(id)
                        + ", market " + (MarketMaker.live(id) ? "open " + Businesses.pence(MarketMaker.cash(id)) : "commons") + ", stragglers "
                        + Accounts.stragglersAll(id) + ", drift " + CoinCensus.lastDrift(id) + " | " + CoinCensus.line(id));
                    Kit.log(tag + " day " + dayNo + " takes " + MarketMaker.tallies(id) + " | " + Privatisation.lines(level, id));
                    long today = level.getDayTime() / 24000L;
                    for (Villages.News n : Villages.news(id)) if (n.day() >= today - 1) Kit.log(tag + " news day " + (n.day() + 1) + ": " + n.text());
                }
                if (ran >= 10 * 24000L) {
                    over[0] = true;
                    CoinCensus.probeForTests(null);
                    server.tickRateManager().setTickRate(20.0F);
                    Kit.Expect ok = new Kit.Expect();
                    int folkNow = Villages.headcount(id), built = Ledger.buildings(id).size();
                    int food = Villages.stock(level, Villages.get(id).centre(), Villages.Task.FOOD, Villages.storesRadius(id));
                    int priv = 0, firms = 0;
                    for (Business b : Businesses.all(id)) {
                        if (b.type.keeping != Business.Keeping.OWN) continue;
                        firms++;
                        if (!b.town()) priv++;
                    }
                    Kit.log(tag + " FINAL " + folkNow + " folk (from " + start[0] + "), " + built + " buildings (from " + start[1] + "), food " + food
                        + ", " + priv + " of " + firms + " businesses private, stragglers " + Accounts.stragglersAll(id));
                    Kit.log(tag + " " + MarketMaker.words(level, id).replace('\n', '|'));
                    Kit.log(tag + " " + CoinCensus.page(id).replace('\n', '|'));
                    if (market) ok.that(MarketMaker.live(id), "its market opened");
                    ok.that(folkNow >= start[0], "it kept its folk: " + start[0] + " -> " + folkNow);
                    ok.that(built > start[1], "it built: " + start[1] + " -> " + built);
                    ok.that(food > 0, "it fed itself: food in the stores " + food);
                    if (market) ok.that(priv > 0, "its trades are businesses in private hands: " + priv + " of " + firms);
                    verdict[0] = ok.clean() ? "" : ok.summary();
                }
            } catch (RuntimeException ex) {
                over[0] = true;
                server.tickRateManager().setTickRate(20.0F);
                verdict[0] = "the run threw " + ex;
                Kit.log("acc threw " + ex);
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }
}
