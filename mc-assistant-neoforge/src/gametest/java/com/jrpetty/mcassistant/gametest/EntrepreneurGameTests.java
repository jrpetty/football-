package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.BusinessTypes;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.Purchases;
import com.jrpetty.mcassistant.entity.Sellers;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.TaxBook;
import com.jrpetty.mcassistant.entity.TownBudget;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.ZoneChests;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import com.jrpetty.mcassistant.entity.Accounts;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.MarketMaker;
import com.jrpetty.mcassistant.entity.Privatisation;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [econ-1b] Entrepreneurs and competition, and the two weaknesses of the first market batch put right: the producers'
 * pay and the town's budget.
 *
 * <p>Each on its own ground, x 1400000 to 1499999 (2000 apart), z 90000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class EntrepreneurGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    // ============================================================ the ground

    /** A town of so many folk at their trades, every purse set to {@code purse}; the market at work and open, the Stone Age. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int purse, StationTask... trades) {
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        Enterprise.resetForTests();
        Enterprise.autoForTests(true);
        level.setDayTime(8 * 24000L + 7000L);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 6 + (i % 6) * 2, Z - 4 + (i / 6) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(trades[i]);
            f.spend(f.purse());
            f.earn(purse);
            Values.setForTests(f, Values.Value.TRADITION, 100);         // nobody bold unless a test makes it so
            Values.setForTests(f, Values.Value.WEALTH, 0);              // [econ-1c] (nor bold for a sure thing: wages and trade
            Values.setForTests(f, Values.Value.PROGRESS, 0);            //  and progress at nothing)
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        Ledger.addCoins(id, 300);
        MarketMaker.openForTests(level, Villages.get(id));
        return folk;
    }

    /** A folk made a bold one at a trade: a Merchant at heart, level ten at it. */
    private static void bold(VillageFolkEntity f, StationTask trade) {
        Values.setForTests(f, Values.Value.TRADITION, 0);
        Values.setForTests(f, Values.Value.WEALTH, 100);
        f.tradeXpForTests(trade, 20000);
    }

    /** A chest of the town's stores by its heart, holding these. */
    private static Container stores(ServerLevel level, UUID village, int x, ItemStack... goods) {
        BlockPos at = Kit.surface(level, x + 8, Z + 8);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length && i < c.getContainerSize(); i++) c.setItem(i, goods[i].copy());
        c.setChanged();
        Villages.forgetStores(village);
        return c;
    }

    private static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    private static void nextDay(ServerLevel level) {
        level.setDayTime((day(level) + 1) * 24000L + 7000L);
    }

    private static void missed(ServerLevel level, UUID id, Item it, int n) {
        for (int i = 0; i < n; i++) Stockroom.missed(level, id, Stockroom.Seller.SHOP, new ItemStack(it));
    }

    // ============================================================ ent01 a founding

    /**
     * Picks wanted and not to be had all week, a bold smith with the skill and the savings: the morning's look at the town's
     * wants finds the toolshop worth opening; the smith says so; the next morning it opens, with the smith's own coin in its
     * till, the smith its hand, the chronicle told. A timid smith of the same skill, beside it, opens nothing.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent01_found")
    public static void ent01_found(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1400000;
        List<VillageFolkEntity> folk = town(helper, level, x, 40, StationTask.SMITH, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity wren = folk.get(0), timid = folk.get(1);
        bold(wren, StationTask.SMITH);
        timid.tradeXpForTests(StationTask.SMITH, 20000);
        UUID id = wren.ownerId();
        Villages.Village v = Villages.get(id);
        missed(level, id, Items.STONE_PICKAXE, 25);
        missed(level, id, Items.STONE_AXE, 15);
        // [econ-1c] The makings of picks in the stores (a toolshop with nothing to make its tools of is no opportunity).
        stores(level, id, x, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.STICK, 64),
            new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.CRAFTING_TABLE, 1));   // (a town short of stone keeps it all for its buildings)
        Kit.log("ent01 makeable " + Enterprise.makeableForTests(level, v, "toolsmith"));
        Kit.Expect ok = new Kit.Expect();
        Enterprise.Opportunity o = Enterprise.weighForTests(level, v, "toolsmith");
        Kit.log("ent01 " + (o == null ? "no opportunity" : o.words()));
        ok.that(o != null && o.gap() > 0 && o.profit7() > 0.15 * o.startup(), "the toolshop is worth opening: " + (o == null ? "-" : o.words()));
        int purse0 = wren.purse();
        Enterprise.morningForTests(level, v, false);
        String intent = Enterprise.intentForTests(id);
        ok.that(intent != null && intent.startsWith("toolsmith|" + wren.getUUID()), "the bold smith means to open a toolshop: " + intent);
        nextDay(level);
        Enterprise.morningForTests(level, v, false);
        List<Business> firms = Enterprise.open(id);
        Kit.log("ent01 " + Enterprise.words(level, id).replace('\n', '|'));
        ok.that(firms.size() == 1, "one firm opened: " + firms.size());
        if (!firms.isEmpty()) {
            Business b = firms.get(0);
            ok.that(b.ownedBy(wren.getUUID()) && !b.ownedBy(timid.getUUID()), "the bold smith's own: " + b.ownersWords());
            ok.that(b.name.contains("Tools"), "named for its trade: " + b.name);
            ok.that(wren.purse() < purse0, "its founder put its own coin in: " + purse0 + " -> " + wren.purse());
            ok.that(Enterprise.hands(id, b).contains(wren), "its founder works it");
            ok.that(Businesses.employer(id, wren) == b, "its founder's employer is its firm");
            boolean told = false;
            for (Villages.News n : Villages.news(id)) told |= n.text().contains("opened " + b.name);
            ok.that(told, "the chronicle has it");
        }
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent02 none when served

    /**
     * The same town with its picks sold as fast as they are wanted (nothing missed, nothing refused): no firm is worth
     * opening, and none opens. And with picks wanted but nobody bold enough, none opens either.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent02_served")
    public static void ent02_served(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1402000;
        List<VillageFolkEntity> folk = town(helper, level, x, 40, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity smith = folk.get(0);
        bold(smith, StationTask.SMITH);
        UUID id = smith.ownerId();
        Villages.Village v = Villages.get(id);
        for (int i = 0; i < 20; i++) Stockroom.sold(level, id, Stockroom.Seller.SHOP, new ItemStack(Items.STONE_PICKAXE), 1, 2);
        // The town's counter well stocked with every tool the folk might want.
        List<ItemStack> shelf = new ArrayList<>();
        for (Item it : new Item[]{ Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE, Items.IRON_PICKAXE, Items.IRON_AXE,
            Items.IRON_SHOVEL, Items.IRON_HOE, Items.SHEARS, Items.BUCKET }) for (int i = 0; i < 2; i++) shelf.add(new ItemStack(it, 1));
        for (int i = 0; i < 6; i++) shelf.add(new ItemStack(Items.STONE_HOE, 1));
        stores(level, id, x, shelf.toArray(new ItemStack[0]));
        Kit.Expect ok = new Kit.Expect();
        Enterprise.Opportunity o = Enterprise.weighForTests(level, v, "toolsmith");
        Kit.log("ent02 served: " + (o == null ? "-" : o.words()));
        ok.that(o == null || o.profit7() <= 0.15 * o.startup(), "a served market is no opportunity: " + (o == null ? "-" : o.words()));
        Enterprise.morningForTests(level, v, false);
        nextDay(level);
        Enterprise.morningForTests(level, v, false);
        ok.that(Enterprise.open(id).isEmpty() && Enterprise.intentForTests(id) == null, "nothing opened: " + Enterprise.open(id).size());
        // Wanted now, but nobody bold.
        Values.setForTests(smith, Values.Value.WEALTH, 0);
        Values.setForTests(smith, Values.Value.TRADITION, 100);
        missed(level, id, Items.STONE_PICKAXE, 30);
        nextDay(level);
        Enterprise.morningForTests(level, v, false);
        nextDay(level);
        Enterprise.morningForTests(level, v, false);
        ok.that(Enterprise.open(id).isEmpty(), "nobody bold, nothing opened: " + Enterprise.open(id).size());
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent03 buyers choose

    /**
     * Two toolshops with picks in stock: a buyer goes to the cheaper (its markup lower), and when the prices change places,
     * to the other. A pick bought at the counter is the chosen firm's sale: its till, its stock, its books.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent03_choose")
    public static void ent03_choose(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1404000;
        List<VillageFolkEntity> folk = town(helper, level, x, 40, StationTask.SMITH, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        bold(folk.get(0), StationTask.SMITH);
        bold(folk.get(1), StationTask.SMITH);
        Business a = Enterprise.foundForTests(level, v, "toolsmith", folk.get(0));
        Business b = Enterprise.foundForTests(level, v, "toolsmith", folk.get(1));
        helper.assertTrue(a != null && b != null, "two toolshops");
        ItemStack pick = new ItemStack(Items.STONE_PICKAXE);
        stores(level, id, x, new ItemStack(Items.STONE_PICKAXE, 1), new ItemStack(Items.STONE_PICKAXE, 1), new ItemStack(Items.STONE_PICKAXE, 1),
            new ItemStack(Items.STONE_PICKAXE, 1), new ItemStack(Items.STONE_PICKAXE, 1), new ItemStack(Items.STONE_PICKAXE, 1));
        Enterprise.stockForTests(a, pick, 3, 20);
        Enterprise.stockForTests(b, pick, 3, 20);
        a.markup = 0.2;
        b.markup = 1.2;
        VillageFolkEntity buyer = folk.get(5);
        Kit.Expect ok = new Kit.Expect();
        double town = Purchases.priceEach(level, id, pick, buyer) * 1.5;           // the town's counter, dearer than both
        Sellers.Offer first = Sellers.best(level, v, pick, buyer, town, 1);
        Kit.log("ent03 a " + Sellers.priceOf(level, id, a, pick) + " b " + Sellers.priceOf(level, id, b, pick) + " town " + town + " -> "
            + (first.firm() == null ? "the town" : first.firm().name));
        ok.that(first.firm() == a, "the buyer goes to the cheaper: " + (first.firm() == null ? "the town" : first.firm().name));
        a.markup = 1.5;
        b.markup = 0.1;
        Sellers.Offer second = Sellers.best(level, v, pick, buyer, town, 1);
        ok.that(second.firm() == b, "and to the other when the prices change places: " + (second.firm() == null ? "the town" : second.firm().name));
        // A sale through the counter.
        Purchases.openForTests(id, true);
        long till0 = b.till;
        long units0 = b.allTime.lines.containsKey(Stockroom.key(pick)) ? b.allTime.lines.get(Stockroom.key(pick)).units : 0;
        buyer.earn(30);
        int got = Purchases.get(level, buyer, s -> s.is(Items.STONE_PICKAXE), 1, Purchases.Need.TOOL);
        long units1 = b.allTime.lines.containsKey(Stockroom.key(pick)) ? b.allTime.lines.get(Stockroom.key(pick)).units : 0;
        Kit.log("ent03 bought " + got + "; b's till " + till0 + " -> " + b.till + ", units " + units0 + " -> " + units1 + ", stock " + b.stock.get(Stockroom.key(pick)).qty);
        ok.that(got == 1 && b.till > till0 && units1 == units0 + 1 && b.stock.get(Stockroom.key(pick)).qty == 2,
            "the pick was the cheaper firm's sale: its till, its books, its stock");
        Purchases.openForTests(id, null);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent04 a losing firm cuts its markup

    /** A toolshop selling nothing while the town's counter sells its picks: under a tenth of its market for a week, it cuts its markup. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent04_cuts")
    public static void ent04_cuts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1406000;
        List<VillageFolkEntity> folk = town(helper, level, x, 40, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        bold(folk.get(0), StationTask.SMITH);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", folk.get(0));
        helper.assertTrue(b != null, "a toolshop");
        b.markup = 0.6;
        double was = b.markup;
        Kit.Expect ok = new Kit.Expect();
        for (int d = 0; d < 10; d++) {
            nextDay(level);
            for (int i = 0; i < 5; i++) Stockroom.sold(level, id, Stockroom.Seller.SHOP, new ItemStack(Items.STONE_PICKAXE), 1, 2);
            Enterprise.shareForTests(level, id, b);
        }
        CompoundTag r = Enterprise.recordForTests(id, b);
        Kit.log("ent04 share " + r.getDouble("share") + ", markup " + was + " -> " + b.markup + "; " + String.join(" | ", b.notes));
        ok.that(r.getDouble("share") < 0.1, "its share is under a tenth: " + r.getDouble("share"));
        ok.that(b.markup < was, "it cut its markup: " + was + " -> " + b.markup);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent05 bankruptcy

    /**
     * A toolshop in debt every way (a hand's wages, the town's rent, the market's trade credit, the bank) goes bankrupt: its
     * stock sold to the market, its creditors paid in order (the wages in full first, then the town, then the market as far
     * as the money runs, the bank last and written off), its sole trader's purse taken down to six coins and no further, its
     * hands laid off, the chronicle told why; and the town's coin, counted the morning before and the morning after, at
     * nought drift.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent05_bankrupt")
    public static void ent05_bankrupt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1408000;
        List<VillageFolkEntity> folk = town(helper, level, x, 40, StationTask.SMITH, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity owner = folk.get(0), hand = folk.get(1);
        UUID id = owner.ownerId();
        Villages.Village v = Villages.get(id);
        bold(owner, StationTask.SMITH);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", owner);
        helper.assertTrue(b != null, "a toolshop");
        Enterprise.staffForTests(id, b, hand);
        stores(level, id, x, new ItemStack(Items.STONE_PICKAXE, 1), new ItemStack(Items.STONE_PICKAXE, 1));
        Enterprise.stockForTests(b, new ItemStack(Items.STONE_PICKAXE), 2, 50);
        owner.spend(owner.purse());
        owner.earn(14);
        hand.spend(hand.purse());                       // [econ-1c] the hand not the one to buy the stall (its wages are what is checked)
        hand.earn(4);
        CoinCensus.morning(level, v, day(level));
        nextDay(level);
        // Its till, and its debts: wages 3c, the town 4c, the market 30c, the bank 20c (the bank's loan was coin it had).
        long till = b.till;
        Businesses.fundForTests(id, b, 900 - till);
        Enterprise.debtsForTests(id, b, hand, 300, 400, 3000, 2000);
        int handPurse = hand.purse();
        long tre = Businesses.treasuryPence(id), mkt = MarketMaker.cash(id);
        Enterprise.bankrupt(level, id, b, day(level), "tests");
        CompoundTag r = Enterprise.recordForTests(id, b);
        Kit.log("ent05 " + Enterprise.line(level, id, b) + " | " + r.getString("creditors") + " | notes " + String.join(" | ", b.notes));
        Kit.Expect ok = new Kit.Expect();
        ok.that(!b.open && r.getBoolean("closed"), "it closed");
        ok.that(hand.purse() == handPurse + 3, "the hand's wages paid first, in full: " + handPurse + " -> " + hand.purse());
        ok.that(Businesses.treasuryPence(id) >= tre + 400, "the town paid next: " + tre + " -> " + Businesses.treasuryPence(id));
        ok.that(MarketMaker.cash(id) > mkt - 200 && Accounts.owed(id, Accounts.Account.firm(b)) == 0, "the market paid as far as the money ran, the rest written off");
        ok.that(owner.purse() == 6, "the sole trader's purse taken down to six coins and no further: " + owner.purse());
        ok.that(b.loan == 0 && r.getString("creditors").contains("written off"), "the bank last, written off: " + r.getString("creditors"));
        ok.that(Enterprise.hands(id, b).isEmpty() && Businesses.employer(id, hand) != b, "its hands laid off");
        ok.that(b.stock.isEmpty(), "its stock sold");
        boolean told = false;
        for (Villages.News n : Villages.news(id)) told |= n.text().startsWith(b.name + " closed");
        ok.that(told, "the chronicle says it closed and why");
        nextDay(level);
        CoinCensus.morning(level, v, day(level));
        ok.that(CoinCensus.lastDrift(id) == 0, "the census at nought drift: " + CoinCensus.line(id));
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent14 a bankrupt's kit sold

    /**
     * [econ-1d] A bankrupt firm's kit sold, not written off: its hand carries the bucket and the shears the firm bought (its
     * kit on its books at eight coins); at the bankruptcy the receiver takes them back, sells them to the market at its bid
     * (the worn shears for less), and what they fetch goes to its creditors with the rest of its till. The tools are in the
     * stores afterwards, not in the hand's pack; the kit is off the firm's books; the census at nought drift.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent14_kit")
    public static void ent14_kit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1600000;
        List<VillageFolkEntity> folk = town(helper, level, x, 40, StationTask.SMITH, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity owner = folk.get(0), hand = folk.get(1);
        UUID id = owner.ownerId();
        Villages.Village v = Villages.get(id);
        bold(owner, StationTask.SMITH);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", owner);
        helper.assertTrue(b != null, "a toolshop");
        Enterprise.staffForTests(id, b, hand);
        stores(level, id, x, new ItemStack(Items.COBBLESTONE, 8));
        ItemStack shears = new ItemStack(Items.SHEARS);
        shears.setDamageValue(shears.getMaxDamage() / 2);
        hand.insertGiven(new ItemStack(Items.BUCKET));
        hand.insertGiven(shears);
        com.jrpetty.mcassistant.entity.Capital.boughtForTests(id, b, 800);
        CoinCensus.morning(level, v, day(level));
        nextDay(level);
        long till = b.till;
        Businesses.fundForTests(id, b, 100 - till);
        Enterprise.debtsForTests(id, b, hand, 0, 0, 3000, 0);           // owes the market 30c
        long mkt = MarketMaker.cash(id);
        int bucketsBefore = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(Items.BUCKET));
        Enterprise.bankrupt(level, id, b, day(level), "tests");
        CompoundTag r = Enterprise.recordForTests(id, b);
        Kit.log("ent14 " + r.getString("creditors") + " | notes " + String.join(" | ", b.notes));
        Kit.Expect ok = new Kit.Expect();
        ok.that(!b.open && r.getBoolean("closed"), "it closed");
        ok.that(hand.countCarried(s -> s.is(Items.BUCKET) || s.is(Items.SHEARS)) == 0, "the kit taken out of its hand's pack");
        ok.that(com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(Items.BUCKET)) == bucketsBefore + 1, "the bucket in the stores");
        boolean noted = false;
        for (String n : b.notes) noted |= n.contains("its kit sold to the market");
        ok.that(noted, "its books say the kit was sold");
        ok.that(r.getString("creditors").contains("the market"), "the market paid out of it: " + r.getString("creditors"));
        ok.that(com.jrpetty.mcassistant.entity.Capital.value(id, b) == 0, "the kit off its books");
        ok.that(MarketMaker.cash(id) > mkt - 3000, "the market paid for the kit and was paid back as a creditor: " + mkt + " -> " + MarketMaker.cash(id));
        nextDay(level);
        CoinCensus.morning(level, v, day(level));
        ok.that(CoinCensus.lastDrift(id) == 0, "the census at nought drift: " + CoinCensus.line(id));
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent16 a household's basket

    /**
     * [econ-1d] What a household buys follows its needs and tastes, not only the cheapest bellyful: a poor folk with glut
     * carrots at a few pennies and bread at the going price on the shelf buys bread as well as carrots over a week of
     * buying (a made meal is worth more to a body, and the same thing day after day palls); a comfortable folk, minding the
     * price less, has bread more often than not.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent16_basket")
    public static void ent16_basket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1602000;
        List<VillageFolkEntity> folk = town(helper, level, x, 1, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity poor = folk.get(0), comfy = folk.get(1);
        UUID id = poor.ownerId();
        comfy.earn(200);
        stores(level, id, x, new ItemStack(Items.CARROT, 64), new ItemStack(Items.CARROT, 64), new ItemStack(Items.BREAD, 32));
        List<ItemStack> offer = List.of(new ItemStack(Items.CARROT), new ItemStack(Items.BREAD));
        int poorBread = 0, comfyBread = 0;
        StringBuilder picks = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            ItemStack a = Purchases.chooseFoodForTests(level, poor, offer), b = Purchases.chooseFoodForTests(level, comfy, offer);
            if (a.is(Items.BREAD)) poorBread++;
            if (b.is(Items.BREAD)) comfyBread++;
            picks.append(a.is(Items.BREAD) ? 'B' : 'c').append(b.is(Items.BREAD) ? 'B' : 'c').append(' ');
        }
        Kit.log("ent16 picks (poor, comfortable): " + picks + "| carrot " + Purchases.priceEach(level, id, new ItemStack(Items.CARROT), poor)
            + "c, bread " + Purchases.priceEach(level, id, new ItemStack(Items.BREAD), poor) + "c; " + Wealth.tier(poor) + " / " + Wealth.tier(comfy));
        Kit.Expect ok = new Kit.Expect();
        ok.that(poorBread >= 1, "bread bought at least once by the poor folk: " + poorBread);
        ok.that(comfyBread >= poorBread, "the comfortable folk has bread at least as often: " + comfyBread + " against " + poorBread);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent06 expansion

    /** A thriving toolshop takes on a hand, then larger premises (its rent up), then opens a second branch of the same owner. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent06_expand")
    public static void ent06_expand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1410000;
        List<VillageFolkEntity> folk = town(helper, level, x, 40, StationTask.SMITH, StationTask.SMITH, StationTask.SMITH, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity owner = folk.get(0);
        UUID id = owner.ownerId();
        Villages.Village v = Villages.get(id);
        bold(owner, StationTask.SMITH);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", owner);
        helper.assertTrue(b != null, "a toolshop");
        Kit.Expect ok = new Kit.Expect();
        int hands0 = Enterprise.hands(id, b).size();
        long rent0 = Enterprise.rentPence(id, b);
        Enterprise.expandForTests(level, v, b);
        ok.that(Enterprise.hands(id, b).size() == hands0 + 1, "it took on a hand: " + hands0 + " -> " + Enterprise.hands(id, b).size());
        Enterprise.expandForTests(level, v, b);
        ok.that(Enterprise.rentPence(id, b) > rent0, "larger premises: rent " + rent0 + " -> " + Enterprise.rentPence(id, b));
        Businesses.fundForTests(id, b, 3000);
        Enterprise.expandForTests(level, v, b);
        List<Business> mine = new ArrayList<>();
        for (Business x2 : Enterprise.open(id)) if (x2.ownedBy(owner.getUUID())) mine.add(x2);
        Kit.log("ent06 " + Enterprise.words(level, id).replace('\n', '|'));
        ok.that(mine.size() == 2, "a second branch, the same owner's: " + mine.size());
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent07 a player's charter

    /**
     * A citizen at the town's heart takes out a charter for a bakery: ten coins to the treasury (coin into the town, booked),
     * the firm its own, on its stall; it sets its markup and takes on a hand. Without the coin, no charter.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent07_charter")
    public static void ent07_charter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1412000;
        List<VillageFolkEntity> folk = town(helper, level, x, 20, StationTask.COOK, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        ServerPlayer you = helper.makeMockServerPlayerInLevel();
        you.moveTo(v.centre().getX() + 0.5, v.centre().getY() + 1, v.centre().getZ() + 0.5);
        Ledger.addCitizen(id, you.getUUID(), you.getName().getString());
        Kit.Expect ok = new Kit.Expect();
        String no = Enterprise.charter(level, v, you, "baker");
        ok.that(Enterprise.open(id).isEmpty() && no.contains("costs"), "no coin, no charter: " + no);
        you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 15));
        long tre = Businesses.treasuryPence(id);
        String yes = Enterprise.charter(level, v, you, "baker");
        Kit.log("ent07 " + yes);
        List<Business> firms = Enterprise.open(id);
        ok.that(firms.size() == 1 && firms.get(0).ownedBy(you.getUUID()), "the player's own bakery: " + yes);
        ok.that(Businesses.treasuryPence(id) == tre + Enterprise.CHARTER_FEE * 100L, "the charter fee to the treasury: " + tre + " -> " + Businesses.treasuryPence(id));
        ok.that(com.jrpetty.mcassistant.entity.Market.coinsHeld(you) == 5, "out of the player's pack: " + com.jrpetty.mcassistant.entity.Market.coinsHeld(you));
        if (!firms.isEmpty()) {
            Business b = firms.get(0);
            VillageFolkEntity h = Enterprise.hireForTests(level, v, b);
            ok.that(h != null && Businesses.employer(id, h) == b, "it took on a hand: " + (h == null ? "nobody" : h.displayNameCap()));
        }
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent08 kept with the world

    /**
     * A firm's books and its record kept with the world: the business saved and loaded is the same firm (its name, owner,
     * till, stock, markup), and with the index of its hands forgotten (as after a restart) its hand is still its hand.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent08_saved")
    public static void ent08_saved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1414000;
        List<VillageFolkEntity> folk = town(helper, level, x, 40, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity owner = folk.get(0);
        UUID id = owner.ownerId();
        Villages.Village v = Villages.get(id);
        bold(owner, StationTask.SMITH);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", owner);
        helper.assertTrue(b != null, "a toolshop");
        Enterprise.stockForTests(b, new ItemStack(Items.STONE_PICKAXE), 3, 55);
        b.markup = 0.42;
        Business back = Business.loadForTests(b.saveForTests());
        Kit.Expect ok = new Kit.Expect();
        ok.that(back != null && back.id.equals(b.id) && back.type == Business.Type.ENTERPRISE && back.name.equals(b.name), "the same firm");
        ok.that(back != null && back.till == b.till && back.markup == 0.42 && back.ownedBy(owner.getUUID()) && back.stockUnits() == 3, "its till, stock, markup and owner");
        Enterprise.forgetIndexForTests();
        ok.that(Businesses.employer(id, owner) == b, "its hand still its hand after the index is rebuilt");
        CompoundTag r = Enterprise.recordForTests(id, b);
        ok.that(r != null && r.getString("type").equals("toolsmith") && r.hasUUID("founder"), "its record kept with the town's accounts");
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent09 the town's budget

    /**
     * The town's budget once its market is open: the profits tax set on Market Opening Day and taken off a business's week
     * before its owners draw (into the treasury and the council's books); the IOUs set against what the market holds over
     * its float; and a town owing more than it can carry puts off the works that can wait, but never its houses or its walls.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ent09_budget")
    public static void ent09_budget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1416000;
        List<VillageFolkEntity> folk = town(helper, level, x, 20, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        ok.that(TaxBook.profitsPercent(id) == 10, "a profits tax of a tenth from Market Opening Day: " + TaxBook.profitsPercent(id));
        // A business's good week.
        bold(folk.get(0), StationTask.SMITH);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", folk.get(0));
        helper.assertTrue(b != null, "a toolshop");
        Businesses.fundForTests(id, b, 4000);
        long tax = TownBudget.profitsTaxForTests(level, id, b, 2000);
        ok.that(tax == 200, "a tenth of a 20c week's profit taken: " + tax);
        ok.that(b.allTime.taxes >= 200, "in its books as a tax");
        // The IOUs set against what the market holds.
        long cash = MarketMaker.cash(id);
        Accounts.debitForTests(level, id, Accounts.Account.dept(Accounts.Dept.WORKS), Businesses.treasuryPence(id) + 5000, Accounts.Purpose.BUILD);
        long ious = Accounts.debts(id);
        ok.that(ious > 0, "the town wrote its market IOUs: " + ious);
        boolean short0 = TownBudget.shortForTests(id);
        TownBudget.morningForTests(level, v);
        ok.that(!short0 || TownBudget.deferredDaysForTests(id) > 0, "owing more than it can carry, the works that can wait are put off");
        ok.that(!TownBudget.essential("theatre") && TownBudget.essential("house") && TownBudget.essential("fortify") && TownBudget.essential("hall"),
            "the theatre waits; the houses, the walls and the age's own buildings never");
        ok.that(!short0 || com.jrpetty.mcassistant.entity.Villages.nextProject(id) == null || TownBudget.essential(com.jrpetty.mcassistant.entity.Villages.nextProject(id)),
            "its next project is an essential one: " + com.jrpetty.mcassistant.entity.Villages.nextProject(id));
        MarketMaker.morningForTests(level, v);
        Kit.log("ent09 IOUs " + ious + " -> " + Accounts.debts(id) + ", market cash " + cash + " -> " + MarketMaker.cash(id) + "; " + TownBudget.line(id));
        ok.that(Accounts.debts(id) < ious, "the IOUs set against what the market holds over its float: " + ious + " -> " + Accounts.debts(id));
        // The mint: three bars bought at the mint's price (eight coins), twenty-seven coins struck: three to the good.
        Villages.ageForTests(id, Villages.Age.IRON);
        stores(level, id, x, new ItemStack(Items.GOLD_INGOT, 3));
        long drain = Businesses.treasuryPence(id) - 3000;
        if (drain > 0) Businesses.fromTreasuryForTests(id, drain);
        else Ledger.addCoins(id, (int) ((-drain + 99) / 100));            // thirty coins in the treasury, under what it wants
        long debts0 = Accounts.debts(id), tre0 = Businesses.treasuryPence(id);
        double goldAsk = MarketMaker.ask(level, id, new ItemStack(Items.GOLD_INGOT));
        int struck;
        try (Accounts.Scope m = Accounts.as(Accounts.Dept.MINT, Accounts.Purpose.WORK)) { struck = com.jrpetty.mcassistant.entity.Market.mint(level, v); }
        Kit.log("ent09 mint: struck " + struck + ", treasury " + tre0 + " -> " + Businesses.treasuryPence(id) + ", IOUs " + debts0 + " -> " + Accounts.debts(id) + ", gold ask " + goldAsk);
        ok.that(struck == 27 && Businesses.treasuryPence(id) == tre0 + 300 && Accounts.debts(id) == debts0,
            "the town's mint strikes 27 of 3 bars bought at 8 each: the treasury 3 coins to the good, no IOU: " + tre0 + " -> " + Businesses.treasuryPence(id));
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent11 the clock set back, the clock held  [econ-1c]

    /**
     * The towns count their days by the world's time (TownClock). A town of four, its market not open, the clock kept as in a
     * world being played. The player sets the time back to the first morning ("time set", absolute) on the town's third
     * day: the day count is kept, the hour as set. Then the clock is held: set back to the same hour of the same day over
     * and over while a whole day of the world's time goes by, eleven times: each is a day gone by, and the town holds its
     * morning late (an hour outside the morning's) each time: its wages, its books, and on its tenth day its Market
     * Opening Day. The coin census at nought drift.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "ent11_clock")
    public static void ent11_clock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int x = 1502000;
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        Enterprise.resetForTests();
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        long d0 = 2;
        level.setDayTime(d0 * 24000L + 1000L);
        com.jrpetty.mcassistant.entity.TownClock.liveForTests(true);
        List<VillageFolkEntity> folk = new ArrayList<>();
        StationTask[] trades = { StationTask.FARM, StationTask.MINE, StationTask.WOOD, StationTask.FISH };
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 4 + i * 2, Z), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(trades[i]);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        net.minecraft.world.level.storage.ServerLevelData data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
        com.jrpetty.mcassistant.entity.TownClock.tickForTests(server);
        com.jrpetty.mcassistant.entity.Market.tick(level, v);
        long paid0 = Ledger.paidOn(id);
        ok.that(paid0 == d0, "the town's morning on its first day: " + paid0);
        // The morning after, as the world turns.
        level.setDayTime((d0 + 1) * 24000L + 1000L);
        com.jrpetty.mcassistant.entity.TownClock.tickForTests(server);
        com.jrpetty.mcassistant.entity.Market.tick(level, v);
        ok.that(Ledger.paidOn(id) == d0 + 1, "and its second: " + Ledger.paidOn(id));
        // "time set 1000": the first morning of the world; the day count kept, the hour as set.
        level.setDayTime(1000L);
        com.jrpetty.mcassistant.entity.TownClock.tickForTests(server);
        ok.that(level.getDayTime() == (d0 + 1) * 24000L + 1000L, "the clock set back: the day kept, the hour as set: " + level.getDayTime());
        // The clock held: set back to seven in the morning over and over, a whole day of the world's time each time.
        int held = 11;
        for (int i = 0; i < held; i++) {
            long dayBefore = level.getDayTime() / 24000L;
            level.setDayTime(7000L);                                              // "time set 7000" (the first day's)
            com.jrpetty.mcassistant.entity.TownClock.tickForTests(server);       // kept at its day
            data.setGameTime(data.getGameTime() + 25300L);                      // a day of the world's time
            level.setDayTime(dayBefore * 24000L + 7000L);
            com.jrpetty.mcassistant.entity.TownClock.tickForTests(server);       // a day gone by
            com.jrpetty.mcassistant.entity.Market.tick(level, v);
        }
        long paid = Ledger.paidOn(id);
        int[] counts = com.jrpetty.mcassistant.entity.TownClock.countsForTests();
        Kit.log("ent11 day " + (level.getDayTime() / 24000L) + " hour " + (level.getDayTime() % 24000L) + ", last morning " + paid + ", kept back "
            + counts[0] + ", counted " + counts[1] + ", market " + (MarketMaker.live(id) ? "open since day " + MarketMaker.openedDay(id) : "not open")
            + ", drift " + CoinCensus.lastDrift(id) + " | " + CoinCensus.line(id));
        ok.that(counts[1] >= held, "each day held a day gone by: " + counts[1]);
        ok.that(level.getDayTime() % 24000L == 7000L, "the hour where the player put it: " + level.getDayTime() % 24000L);
        ok.that(paid == d0 + 1 + held, "a morning each day, held late: the last on day " + paid + " (of " + (d0 + 1 + held) + ")");
        ok.that(MarketMaker.live(id), "its Market Opening Day came by its tenth day, the clock held or not");
        ok.that(CoinCensus.lastDrift(id) == 0, "the census at nought drift: " + CoinCensus.lastDrift(id));
        com.jrpetty.mcassistant.entity.TownClock.liveForTests(false);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent13 the court and the auction  [econ-1c]

    /**
     * A toolshop that cannot pay its way: its creditors take it to the court. It trades no more; the town has no hall, so
     * the next morning the council hears it on the square and declares it bankrupt: its stock to the market, its till to
     * its creditors in their order, the rest of what it owes kept by the receiver against its stall, which goes under the
     * hammer at the town's auction (a town of thirteen in the Stone Age holds one) with the town's other lots. A folk with
     * the means buys it; what it brings pays the creditors still owed, in order; the winner owns a stall to set up on. The
     * chronicle has it all, and the coin census stays at nought drift.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ent13_court")
    public static void ent13_court(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1506000;
        List<VillageFolkEntity> folk = town(helper, level, x, 40, StationTask.SMITH, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        Enterprise.autoForTests(false);
        com.jrpetty.mcassistant.entity.Auctions.manualForTests(true);
        VillageFolkEntity owner = folk.get(0), hand = folk.get(1);
        UUID id = owner.ownerId();
        Villages.Village v = Villages.get(id);
        bold(owner, StationTask.SMITH);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", owner);
        helper.assertTrue(b != null, "a toolshop");
        Enterprise.staffForTests(id, b, hand);
        stores(level, id, x, new ItemStack(Items.STONE_PICKAXE, 1), new ItemStack(Items.STONE_PICKAXE, 1));
        Enterprise.stockForTests(b, new ItemStack(Items.STONE_PICKAXE), 2, 50);
        owner.spend(owner.purse());
        owner.earn(8);
        CoinCensus.morning(level, v, day(level));
        nextDay(level);
        long till = b.till;
        Businesses.fundForTests(id, b, 300 - till);
        Enterprise.debtsForTests(id, b, hand, 300, 400, 3000, 0);
        Kit.Expect ok = new Kit.Expect();
        CompoundTag r = Enterprise.recordForTests(id, b);
        Enterprise.petitionForTests(level, id, b, "its cash and its credit gone");
        ok.that(b.open && "before the court".equals(r.getString("status")), "taken to the court, not yet heard: " + r.getString("status"));
        nextDay(level);
        Enterprise.morningForTests(level, v, false);
        Kit.log("ent13 heard: " + Enterprise.line(level, id, b) + " | " + r.getString("creditors") + " | notes " + String.join(" | ", b.notes));
        ok.that(!b.open && r.getBoolean("closed"), "declared bankrupt");
        ok.that(r.getString("heard").contains("council"), "heard by the council on the square (no hall): " + r.getString("heard"));
        ok.that(r.contains("receiver"), "its stall in the receiver's hands, for the auction");
        ok.that(r.getString("creditors").contains("till its stall is sold"), "creditors still owed till the stall is sold: " + r.getString("creditors"));
        long mkt0 = MarketMaker.cash(id);
        List<String> lots = com.jrpetty.mcassistant.entity.Auctions.openForTests(level, v);
        Kit.log("ent13 lots: " + lots);
        boolean stallLot = false;
        for (String l : lots) stallLot |= l.contains("stall of") && l.contains("FIRM");
        ok.that(stallLot, "the stall up for auction: " + lots);
        Map<UUID, Integer> purse0 = new java.util.HashMap<>();
        for (VillageFolkEntity f : folk) purse0.put(f.getUUID(), f.purse());
        int crowd = com.jrpetty.mcassistant.entity.Auctions.startForTests(level, v);
        for (int i = 0; i < 300 && !com.jrpetty.mcassistant.entity.Auctions.doneForTests(id); i++) com.jrpetty.mcassistant.entity.Auctions.roundForTests(level, v);
        List<String> results = com.jrpetty.mcassistant.entity.Auctions.resultsForTests(id);
        Kit.log("ent13 a crowd of " + crowd + "; results " + results + " | " + r.getString("creditors"));
        VillageFolkEntity winner = null;
        for (VillageFolkEntity f : folk) for (String res : results) if (res.contains("stall of") && res.contains("sold to " + f.displayNameCap() + " for")) winner = f;
        ok.that(winner != null, "a folk bought the stall: " + results);
        if (winner != null) {
            int price = purse0.get(winner.getUUID()) - winner.purse();
            ok.that(price > 0 && Enterprise.stallsForTests(id, winner) == 1, "it paid " + price + " and owns the stall");
            ok.that(MarketMaker.cash(id) > mkt0 || r.getString("creditors").contains("wages"), "what it brought to the creditors still owed: " + r.getString("creditors"));
        }
        ok.that(!r.contains("receiver") && r.getString("creditors").contains("sold at auction"), "the receiver done: " + r.getString("creditors"));
        boolean told = false;
        for (Villages.News n : Villages.news(id)) told |= n.text().contains("stall was sold at auction");
        ok.that(told, "the chronicle has the sale");
        nextDay(level);
        CoinCensus.morning(level, v, day(level));
        ok.that(CoinCensus.lastDrift(id) == 0, "the census at nought drift: " + CoinCensus.line(id));
        com.jrpetty.mcassistant.entity.Auctions.manualForTests(false);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ ent10 ten days of a town under the market

    /**
     * A town founded and left to itself for ten days with the market at work (its Market Opening Day as it comes), its
     * stores topped up each morning with building stuff and bread as the long growth tests do. Each morning: the census
     * (nought drift), the treasury and its IOUs, the market, the producers' takings against the other trades' wages, and
     * the firms. At the end: IOUs bounded, the producers earning a living comparable to the other trades, at least one firm
     * founded by an entrepreneur, no deadlock (it grew and built and fed itself).
     */
    @GameTest(template = EMPTY, timeoutTicks = 900000, required = false, batch = "ent10_ten_days")
    public static void ent10_ten_days(GameTestHelper helper) {
        tenDays(helper, 1418000, "ent10", true);
    }

    /**
     * [econ-1c] Ten days of a town under the market, nobody seeded: the same town as ent10 with no would-be entrepreneur
     * given it. At least one firm opened by its own folk all the same; its farms, ranch and woodlot selling to the market;
     * and the same checks as ent10. Logged as it goes: what each producer does, its fields' crops, its tools bought.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900000, required = false, batch = "ent12_unseeded")
    public static void ent12_unseeded(GameTestHelper helper) {
        tenDays(helper, 1504000, "ent12", false);
    }

    /**
     * [econ-1d] Twenty days of a town nobody seeded, the market at work: the same as ent12, ten days longer, long enough for
     * a firm with real call for its wares and the makings to make them to thrive. At least one firm founded by its own folk
     * and at least one thriving at some morning; the census at nought drift every morning; the IOUs bounded.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1900000, required = false, batch = "ent15_twenty_days")
    public static void ent15_twenty_days(GameTestHelper helper) {
        tenDays(helper, 1604000, "ent15", false, 20);
    }

    static void tenDays(GameTestHelper helper, int cx, String tag, boolean seeded) {
        tenDays(helper, cx, tag, seeded, 10);
    }

    static void tenDays(GameTestHelper helper, int cx, String tag, boolean seeded, int days) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        com.jrpetty.mcassistant.entity.IouBook.traceForTests(true);              // [econ-1d] each IOU's place in the code
        // [econ-1c] The game's own crop growth (a random tick speed of three, as in any world): the tests' server has it at
        // nought, and a town's fields never ripened in ten days, so its farms had nothing to sell. Put back at the end.
        var ticking = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
        final int tickingWas = ticking.get();
        ticking.set(3, server);
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
        final long[] worstDrift = { 0 };
        final long[] mostIous = { 0 };
        final long[] iouTrend = { 0, -1, 0 };                                    // [econ-1d] the peak's day, the last, IOUs then
        final java.util.Set<String> thrived = new java.util.TreeSet<>();          // [econ-1d] firms seen thriving
        final java.util.Map<String, long[]> money = new java.util.TreeMap<>();    // [econ-1d] the town's revenue and spending, by day
        final Runnable[] look = { null };
        look[0] = () -> {
            if (verdict[0] == null) helper.runAfterDelay(100, () -> look[0].run());
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
                        ticking.set(tickingWas, server);
                        verdict[0] = "no village founded";
                        return;
                    }
                    town[0] = v.id();
                    began[0] = t;
                    start[0] = Villages.headcount(v.id());
                    start[1] = Ledger.buildings(v.id()).size();
                    Kit.log(tag + " " + Villages.name(v.id()) + " of " + start[0] + " founded at tick " + t + ", " + start[1] + " buildings");
                    return;
                }
                long ran = t - began[0];
                UUID id = town[0];
                int dayNo = (int) (ran / 24000L);
                if (!seeded && ran % 3000L == 0) for (String l : fieldLines(level, id)) Kit.log(tag + " t" + ran + " " + l);
                if (ran % 2000L == 0 && MarketMaker.live(id)) {                  // [econ-1d] the IOUs through the day
                    Kit.log(tag + " t" + ran + " (" + (level.getDayTime() % 24000L) + ") IOUs " + Businesses.pence(Accounts.debts(id)) + ", market cash "
                        + Businesses.pence(MarketMaker.cash(id)) + " credit " + Businesses.pence(MarketMaker.storeCredit(id)) + " float "
                        + Businesses.pence(MarketMaker.advanced(id)) + ", treasury " + Businesses.pence(Businesses.treasuryPence(id)) + " | today "
                        + com.jrpetty.mcassistant.entity.IouBook.dayLine(id, level.getDayTime() / 24000L));
                }
                if (dayNo != start[2]) {
                    start[2] = dayNo;
                    // [econ-1d] Bread given free every morning undersold any baker in the town: once its market is open, a
                    // town feeds itself by its own trade (the building stuff is still given, as the long growth tests do).
                    Kit.stockForGrowth(level, Villages.get(id), !MarketMaker.live(id));
                    if (dayNo == 1 && seeded) wouldBe(level, id, tag);
                    long drift = CoinCensus.lastDrift(id);
                    worstDrift[0] = Math.max(worstDrift[0], Math.abs(drift));
                    if (Accounts.debts(id) > mostIous[0]) iouTrend[0] = dayNo;
                    mostIous[0] = Math.max(mostIous[0], Accounts.debts(id));
                    iouTrend[1] = dayNo;
                    iouTrend[2] = Accounts.debts(id);
                    for (Business fb : Enterprise.all(id)) {                      // [econ-1d] a firm seen thriving
                        CompoundTag fr = Enterprise.recordForTests(id, fb);
                        if (fr != null && "thriving".equals(fr.getString("status"))) thrived.add(fb.name);
                    }
                    if (MarketMaker.live(id)) money.put(String.format(Locale.ROOT, "%02d", dayNo), new long[]{ TownBudget.revenue7(id), TownBudget.spent7(id), Accounts.debts(id), TownBudget.limit(id) });
                    Kit.log(tag + " day " + dayNo + ": " + Villages.headcount(id) + " folk, " + Ledger.buildings(id).size() + " buildings, food "
                        + Villages.stock(level, Villages.get(id).centre(), Villages.Task.FOOD, Villages.storesRadius(id)) + ", treasury "
                        + Businesses.pence(Businesses.treasuryPence(id)) + ", IOUs " + Businesses.pence(Accounts.debts(id)) + ", market "
                        + (MarketMaker.live(id) ? "open " + Businesses.pence(MarketMaker.cash(id)) + " credit " + Businesses.pence(MarketMaker.storeCredit(id))
                        + " float " + Businesses.pence(MarketMaker.advanced(id)) : "commons") + ", drift " + drift + " | " + CoinCensus.line(id));
                    for (String l : producerLines(level, id)) Kit.log(tag + " day " + dayNo + " " + l);
                    Kit.log(tag + " day " + dayNo + " firms: " + Enterprise.words(level, id).replace('\n', '|'));
                    Kit.log(tag + " day " + dayNo + " budget: " + TownBudget.line(id));
                    long today = level.getDayTime() / 24000L;
                    Kit.log(tag + " day " + dayNo + " IOU book yesterday: " + com.jrpetty.mcassistant.entity.IouBook.dayLine(id, today - 1));   // [econ-1d]
                    for (Villages.News n : Villages.news(id)) if (n.day() >= today - 1) Kit.log(tag + " news day " + (n.day() + 1) + ": " + n.text());
                }
                if (ran >= days * 24000L) {
                    over[0] = true;
                    server.tickRateManager().setTickRate(20.0F);
                    ticking.set(tickingWas, server);
                    Kit.Expect ok = new Kit.Expect();
                    int folkNow = Villages.headcount(id), built = Ledger.buildings(id).size();
                    int food = Villages.stock(level, Villages.get(id).centre(), Villages.Task.FOOD, Villages.storesRadius(id));
                    Kit.log(tag + " FINAL " + folkNow + " folk (from " + start[0] + "), " + built + " buildings (from " + start[1] + "), food " + food);
                    Kit.log(tag + " " + MarketMaker.words(level, id).replace('\n', '|'));
                    Kit.log(tag + " " + CoinCensus.page(id).replace('\n', '|'));
                    for (String l : producerLines(level, id)) Kit.log(tag + " FINAL " + l);
                    ok.that(MarketMaker.live(id), "its market opened");
                    // (A night's raid can take one or two of them: the town's walls, not its market.)
                    ok.that(folkNow >= start[0] - 2, "it kept its folk: " + start[0] + " -> " + folkNow);
                    ok.that(built > start[1], "it built: " + start[1] + " -> " + built);
                    ok.that(food > 0, "it fed itself: food in the stores " + food);
                    ok.that(worstDrift[0] == 0, "the census at nought drift every morning (worst " + worstDrift[0] + ")");
                    Kit.log(tag + " most IOUs " + Businesses.pence(mostIous[0]));
                    Kit.log(tag + " IOU TREND: peak " + Businesses.pence(mostIous[0]) + " on day " + iouTrend[0] + ", " + Businesses.pence(iouTrend[2])
                        + " on day " + iouTrend[1] + ", now " + Businesses.pence(Accounts.debts(id)) + " against the bound " + Businesses.pence(Math.max(TownBudget.limit(id) * 2, 20000)));
                    StringBuilder mb = new StringBuilder();
                    for (java.util.Map.Entry<String, long[]> e2 : money.entrySet()) mb.append("d").append(e2.getKey()).append(" in7 ").append(Businesses.pence(e2.getValue()[0]))
                        .append(" spent7 ").append(Businesses.pence(e2.getValue()[1])).append(" ious ").append(Businesses.pence(e2.getValue()[2])).append(" limit ")
                        .append(Businesses.pence(e2.getValue()[3])).append("; ");
                    Kit.log(tag + " MONEY by day: " + mb);
                    Kit.log(tag + " THRIVED: " + thrived);
                    Kit.log(tag + " FINAL firms: " + Enterprise.words(level, id).replace('\n', '|'));
                    for (Business fb : Enterprise.all(id)) {                       // [econ-1c] each firm's books in brief
                        CompoundTag fr = Enterprise.recordForTests(id, fb);
                        StringBuilder st = new StringBuilder();
                        for (java.util.Map.Entry<String, Business.Lot> e2 : fb.stock.entrySet()) st.append(e2.getValue().qty).append(' ').append(e2.getKey().replace("minecraft:", "")).append(", ");
                        Business.Day aw = fb.allTime;
                        Kit.log(String.format(Locale.ROOT, "%s FINAL firm %s: revenue %s, cogs %s, wages %s, rent %s, other %s, net %s; stock %s; targets %s; record %s; notes %s",
                            tag, fb.name, Businesses.pence(aw.revenue), Businesses.pence(aw.cogs), Businesses.pence(aw.wages), Businesses.pence(aw.rent), Businesses.pence(aw.other),
                            Businesses.pence(aw.net()), st, fr == null ? "-" : fr.getCompound("targets"), fr == null ? "-" : fr.getString("creditors") + " " + fr.getString("heard"),
                            String.join(" | ", fb.notes.subList(Math.max(0, fb.notes.size() - 8), fb.notes.size()))));
                    }
                    StringBuilder who = new StringBuilder();
                    for (AssistantEntity e2 : Villages.folkOf(id)) {
                        if (!(e2 instanceof VillageFolkEntity f2) || f2.isBaby()) continue;
                        who.append(f2.displayNameCap()).append(' ').append(f2.stationTask().name().toLowerCase(Locale.ROOT)).append(" lv").append(f2.tradeLevel(f2.stationTask()))
                            .append(" bold ").append(Enterprise.boldness(f2)).append(" purse ").append(f2.purse()).append("; ");
                    }
                    Kit.log(tag + " FINAL founders? " + who);
                    if (!seeded) {
                        // [econ-1c] The farms sell their harvest to the market and earn by it.
                        int farms = 0, selling = 0;
                        for (Business b : Businesses.all(id)) {
                            if (b.type.size != Business.Size.YARD || !b.open || b.town()) continue;
                            VillageFolkEntity hand = handOf(id, b);
                            if (hand == null || hand.stationTask() != StationTask.FARM) continue;
                            farms++;
                            if (b.allTime.revenue > 0) selling++;
                        }
                        ok.that(farms > 0 && selling * 2 >= farms, "the farms sell their harvest: " + selling + " of " + farms + " took something");
                    }
                    int[] counts = Enterprise.countsForTests(id);
                    ok.that(counts[0] >= 1, "at least one firm opened by its own folk: " + counts[0] + " opened, " + counts[1] + " bankrupt, " + counts[2] + " wound up");
                    if (days >= 20) ok.that(!thrived.isEmpty(), "at least one firm thriving: " + thrived);   // [econ-1d]
                    ok.that(mostIous[0] <= Math.max(TownBudget.limit(id) * 2, 20000), "the town's IOUs bounded: at most " + Businesses.pence(mostIous[0])
                        + " against what it can carry, " + Businesses.pence(TownBudget.limit(id)));
                    verdict[0] = ok.clean() ? "" : ok.summary();
                }
            } catch (RuntimeException ex) {
                over[0] = true;
                server.tickRateManager().setTickRate(20.0F);
                ticking.set(tickingWas, server);
                verdict[0] = "the run threw " + ex;
                Kit.log(tag + " threw " + ex);
                ex.printStackTrace();
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }

    /**
     * The town given one would-be entrepreneur on its first morning, as the long tests stock its stores: a farmer made a
     * tailor of long practice and a Merchant at heart, with thirty coins of the treasury's in its purse (coin moved, not
     * made). Whether it opens a business, and what becomes of it, is the town's own doing.
     */
    static void wouldBe(ServerLevel level, UUID id, String tag) {
        for (AssistantEntity e : Villages.folkOf(id)) {
            if (!(e instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() != StationTask.FARM) continue;
            f.setJob(StationTask.TAILOR);
            f.tradeXpForTests(StationTask.TAILOR, 20000);
            Values.setForTests(f, Values.Value.TRADITION, 0);
            Values.setForTests(f, Values.Value.SAFETY, 0);
            Values.setForTests(f, Values.Value.WEALTH, 100);
            int coins = Ledger.takeCoins(id, Math.min(30, Ledger.coins(id)));
            f.earn(coins);
            Kit.log(tag + " the would-be entrepreneur: " + f.displayNameCap() + ", a tailor at level " + f.tradeLevel(StationTask.TAILOR) + ", bold "
                + Enterprise.boldness(f) + ", purse " + f.purse());
            return;
        }
    }

    /**
     * The producers against the other trades: each producer business's takings, its costs other than its hand's wage, and
     * its surplus a day since it opened (what its hand lives on: its wage and its drawings come out of it), beside the
     * average wage of the other trades and the producer's own going wage.
     */
    static List<String> producerLines(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        double surplusSum = 0, bringing = 0, wages = 0, farmSum = 0;
        int producers = 0, bringers = 0, farms = 0, farmsSelling = 0;
        for (Business b : Businesses.all(id)) {
            if (b.type.size != Business.Size.YARD || !b.open) continue;
            long days = Math.max(1, day - b.opened);
            Business.Day a = b.allTime;
            long surplus = a.revenue - a.cogs - a.other - a.rent - a.interest - a.taxes - a.losses;
            VillageFolkEntity hand = handOf(id, b);
            int wage = hand == null ? 0 : Wealth.wage(hand);
            Business.Day y = b.last(day - 1, 1);
            out.add(String.format(Locale.ROOT, "  producer %-22s %s: took %s, costs %s, surplus %s a day over %d days (yesterday %s on %d units); its wage %d; paid %s, drew %s; till %s, owes town %s, loan %s; purse %s",
                b.name, b.town() ? "(town's)" : b.ownersWords(), Businesses.pence(a.revenue), Businesses.pence(a.other + a.rent + a.interest + a.taxes),
                Businesses.pence(surplus / days), days, Businesses.pence(y.revenue), unitsOf(y), wage, Businesses.pence(a.wages), Businesses.pence(a.drawings),
                Businesses.pence(b.till), Businesses.pence(-Math.min(0, b.account)), Businesses.pence(b.loan), hand == null ? "-" : String.valueOf(hand.purse())));
            if (!b.town()) {
                surplusSum += surplus / (double) days;
                producers++;
            }
            if (!b.town() && a.revenue > 0) {
                bringing += surplus / (double) days;
                wages += wage;
                bringers++;
            }
            if (!b.town() && hand != null && hand.stationTask() == StationTask.FARM) {   // [econ-1c] the farmers apart
                farmSum += surplus / (double) days;
                farms++;
                if (a.revenue > 0) farmsSelling++;
            }
            if (a.other > 500) {
                StringBuilder bought = new StringBuilder();
                for (Business.Order o : b.orders) bought.append(o.qty()).append(' ').append(o.item()).append(' ').append(Businesses.pence(o.total())).append(" from ").append(o.supplier()).append(", ");
                out.add("    " + b.name + " bought: " + bought);
            }
        }
        // Yesterday's output by trade, and what each producer has in its pack now.
        java.util.Map<StationTask, StringBuilder> made = new java.util.EnumMap<>(StationTask.class);
        for (java.util.Map.Entry<String, java.util.Map<StationTask, Integer>> e : com.jrpetty.mcassistant.entity.Economy.yesterdayMakersForTests(id).entrySet()) {
            for (java.util.Map.Entry<StationTask, Integer> m : e.getValue().entrySet()) {
                made.computeIfAbsent(m.getKey(), k -> new StringBuilder()).append(e.getKey()).append(' ').append(m.getValue()).append(", ");
            }
        }
        for (java.util.Map.Entry<StationTask, StringBuilder> e : made.entrySet()) out.add("  made yesterday by " + e.getKey().name().toLowerCase(Locale.ROOT) + ": " + e.getValue());
        StringBuilder packs = new StringBuilder();
        for (AssistantEntity e : Villages.folkOf(id)) {
            if (!(e instanceof VillageFolkEntity f) || f.isBaby() || !Privatisation.producer(f.stationTask())) continue;
            int carried = 0;
            for (net.minecraft.world.item.ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && !s.isDamageableItem()) carried += s.getCount();
            packs.append(f.displayNameCap()).append(" (").append(f.stationTask().name().toLowerCase(Locale.ROOT)).append(") carries ").append(carried)
                .append(", ").append(f.brainNoteForBooks()).append("; ");
        }
        out.add("  packs: " + packs);
        double others = 0;
        int n = 0;
        StringBuilder trades = new StringBuilder();
        for (AssistantEntity e : Villages.folkOf(id)) {
            if (!(e instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() == StationTask.NONE) continue;
            if (Privatisation.producer(f.stationTask())) continue;
            int w = Wealth.wage(f);
            others += w;
            n++;
            trades.append(f.stationTask().name().toLowerCase(Locale.ROOT)).append(' ').append(w).append(", ");
        }
        out.add(String.format(Locale.ROOT, "  FARMS %d private, %d of them selling, surplus %.2fc a day each on average", farms, farmsSelling,
            farms == 0 ? 0 : farmSum / farms / 100.0));
        out.add(String.format(Locale.ROOT, "  PRODUCERS %d private, surplus %.2fc a day each on average; %d of them bringing goods in, %.2fc a day each against their own wage %.2f; OTHER TRADES %d, wage %.2f a day on average (%s)",
            producers, producers == 0 ? 0 : surplusSum / producers / 100.0, bringers, bringers == 0 ? 0 : bringing / bringers / 100.0,
            bringers == 0 ? 0 : wages / bringers, n, n == 0 ? 0 : others / n, trades));
        return out;
    }

    /** [econ-1c] Each farmer, rancher and woodcutter now: where it is against its plot, what it is doing, what its plot
     *  holds (crops ripe and growing, farmland), what it carries. */
    static List<String> fieldLines(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        for (AssistantEntity e : Villages.folkOf(id)) {
            if (!(e instanceof VillageFolkEntity f) || f.isBaby()) continue;
            StationTask st = f.stationTask();
            if (st != StationTask.FARM && st != StationTask.RANCH && st != StationTask.WOOD && st != StationTask.HUNT) continue;
            com.jrpetty.mcassistant.entity.WorkZone z = f.workZone();
            int ripe = 0, growing = 0, farmland = 0, logs = 0, saplings = 0;
            if (z != null) {
                BlockPos c = z.center();
                for (int dx = z.min().getX(); dx <= z.max().getX(); dx++) for (int dz = z.min().getZ(); dz <= z.max().getZ(); dz++) for (int dy = -4; dy <= 8; dy++) {
                    BlockPos p = new BlockPos(dx, c.getY() + dy, dz);
                    net.minecraft.world.level.block.state.BlockState bs = level.getBlockState(p);
                    if (bs.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) { if (crop.isMaxAge(bs)) ripe++; else growing++; }
                    else if (bs.is(Blocks.FARMLAND)) farmland++;
                    else if (bs.is(net.minecraft.tags.BlockTags.LOGS)) logs++;
                    else if (bs.is(net.minecraft.tags.BlockTags.SAPLINGS)) saplings++;
                }
            }
            StringBuilder pack = new StringBuilder();
            for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty()) pack.append(s.getCount()).append(' ').append(Stockroom.key(s).replace("minecraft:", "")).append(',');
            out.add(String.format(Locale.ROOT, "%s %s at %s plot %s (%s, in it %s) ripe %d growing %d farmland %d logs %d saplings %d; hand %s; %s | %s",
                f.displayNameCap(), st.name().toLowerCase(Locale.ROOT), f.blockPosition().toShortString(), z == null ? "-" : z.center().toShortString(),
                z == null ? "-" : z.describe(), z != null && z.containsColumn(f.blockPosition()), ripe, growing, farmland, logs, saplings,
                f.getMainHandItem().isEmpty() ? "-" : Stockroom.key(f.getMainHandItem()).replace("minecraft:", ""), f.brainNoteForBooks(), pack));
        }
        return out;
    }

    static long unitsOf(Business.Day d) {
        long u = 0;
        for (Business.Line l : d.lines.values()) u += l.units;
        return u;
    }

    static VillageFolkEntity handOf(UUID id, Business b) {
        return Privatisation.handForTests(id, b);
    }
}
