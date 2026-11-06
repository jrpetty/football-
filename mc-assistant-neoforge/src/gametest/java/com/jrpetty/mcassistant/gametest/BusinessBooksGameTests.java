package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.BusinessBooks;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.CostBook;
import com.jrpetty.mcassistant.entity.Hospitality;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.BaseWorth;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [business-books] Every establishment a business with its own books (Businesses, Business, BusinessBooks), every item
 * costed the whole way back (CostBook), the town's coin counted to the penny (CoinCensus).
 *
 * <p>Each on its own ground (x 2500000 to 2512000, z 70000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BusinessBooksGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    /** A town of so many folk, the first a cook, the second a shopkeeper; each with a little in its purse. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int n) {
        Kit.reset(level);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 3 + (i % 4) * 2, Z - 2 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(i == 0 ? StationTask.COOK : i == 1 ? StationTask.SHOP : i == 2 ? StationTask.COOK : StationTask.FARM);
            f.earn(i == 0 ? 60 : 12);
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

    /** A counter's sale, as the counters make it: the buyer's coin into the treasury, then the books (Stockroom.sold). */
    private static void counterSale(ServerLevel level, UUID village, VillageFolkEntity buyer, Stockroom.Seller seller, ItemStack what, int coins) {
        if (buyer.spend(coins)) {
            Ledger.addCoins(village, coins);
            Stockroom.sold(level, village, seller, what, what.getCount(), coins);
        }
    }

    // ============================================================ bb01 the sandwich

    /**
     * The sandwich's sum: the café buys thirty wheat at half a coin for exactly fifteen coins (its till down 1500 pennies,
     * the treasury up as many, not a penny made or lost); three of that wheat make a loaf that cost exactly 150 pennies; a
     * steak cooked of a bought beef and a coal costs the beef and the coal; and the loaf sold for two coins books 200
     * pennies of takings into the café's own till, 150 of cost and 50 of margin.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "bb01_sandwich")
    public static void bb01_sandwich_costing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2500000;
        level.setDayTime(4 * 24000L + 7000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 5);
        UUID id = folk.get(0).ownerId();
        stores(level, id, x, join(lots(Items.WHEAT, 3), lots(Items.COAL, 1), lots(Items.BEEF, 1)));
        Ledger.addCoins(id, 300);
        Villages.builtAtForTests(id, "cafe", Kit.surface(level, x + 10, Z));
        Businesses.ensure(level, id);
        Business cafe = Businesses.of(id, "cafe");
        helper.assertTrue(cafe != null && !cafe.town() && cafe.owners.get(0).holder() == Business.Holder.FOLK,
            "the café is a private business with a folk proprietor: " + (cafe == null ? "none" : cafe.ownersWords()));
        Kit.log("bb01 café owned by " + cafe.ownersWords() + ", capital " + Businesses.pence(cafe.till) + "; " + cafe.notes);
        Businesses.spareAllForTests(true);
        Businesses.storesPriceForTests(new ItemStack(Items.WHEAT), 0.5);
        Businesses.storesPriceForTests(new ItemStack(Items.COAL), 0.4);
        Businesses.storesPriceForTests(new ItemStack(Items.BEEF), 0.3);
        Businesses.fundForTests(id, cafe, 2000);
        long till0 = cafe.till, tre0 = Businesses.treasuryPence(id), coin0 = CoinCensus.count(id);
        Businesses.OrderResult r = Businesses.order(level, id, cafe, new ItemStack(Items.WHEAT), 30, false);
        Kit.log("bb01 order: " + r.words() + "; till " + till0 + " -> " + cafe.till + ", treasury " + tre0 + " -> " + Businesses.treasuryPence(id));
        helper.assertTrue(r.ok() && r.total() == 1500 && r.qty() == 30, "30 wheat at 0.50c come to exactly 15.00c: " + r.words());
        helper.assertTrue(cafe.till == till0 - 1500 && Businesses.treasuryPence(id) == tre0 + 1500, "out of the till, into the treasury, to the penny");
        helper.assertTrue(CoinCensus.count(id) == coin0, "not a penny made or lost: " + coin0 + " -> " + CoinCensus.count(id));
        Business.Lot wheat = cafe.stock.get("minecraft:wheat");
        helper.assertTrue(wheat != null && wheat.qty == 30 && wheat.cost == 1500, "thirty wheat in its stock at 1500 pennies");
        // Three wheat make a loaf.
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        samples.put("minecraft:wheat", new ItemStack(Items.WHEAT));
        samples.put("minecraft:bread", new ItemStack(Items.BREAD));
        samples.put("minecraft:beef", new ItemStack(Items.BEEF));
        samples.put("minecraft:coal", new ItemStack(Items.COAL));
        samples.put("minecraft:cooked_beef", new ItemStack(Items.COOKED_BEEF));
        long loaf = Businesses.madeForTests(level, id, cafe, Map.of("minecraft:wheat", 3L), Map.of("minecraft:bread", 1L), samples);
        Business.Lot bread = cafe.stock.get("minecraft:bread");
        helper.assertTrue(loaf == 150 && bread != null && bread.qty == 1 && bread.cost == 150, "the loaf cost exactly its three wheat: " + loaf);
        // A steak of a beef it had not got (bought there and then) and a coal: the beef's 30 and the coal's 40.
        long steak = Businesses.madeForTests(level, id, cafe, Map.of("minecraft:beef", 1L, "minecraft:coal", 1L), Map.of("minecraft:cooked_beef", 1L), samples);
        helper.assertTrue(steak == 70, "the steak cost its beef and its fuel, bought at the stores' price: " + steak);
        helper.assertTrue(cafe.cards.containsKey("minecraft:bread") && cafe.cards.get("minecraft:cooked_beef").contains("fuel"),
            "how each was made is on its card: " + cafe.cards);
        // The loaf sold over the counter for two coins.
        long till1 = cafe.till, tre1 = Businesses.treasuryPence(id), coin1 = CoinCensus.count(id);
        counterSale(level, id, folk.get(3), Stockroom.Seller.CAFE, new ItemStack(Items.BREAD), 2);
        Business.Line line = cafe.today(level.getDayTime() / 24000L).lines.get("minecraft:bread");
        Kit.log("bb01 the loaf sold: " + (line == null ? "no line" : line.units + " for " + line.revenue + ", cost " + line.cogs) + "; till " + till1 + " -> " + cafe.till);
        helper.assertTrue(line != null && line.units == 1 && line.revenue == 200 && line.cogs == 150, "200 takings, 150 cost, 50 margin");
        helper.assertTrue(cafe.till == till1 + 200 && Businesses.treasuryPence(id) == tre1, "the takings in its own till, not the treasury");
        helper.assertTrue(CoinCensus.count(id) == coin1, "the coin moved from the purse to the till, none made: " + coin1 + " -> " + CoinCensus.count(id));
        CompoundTag books = BusinessBooks.books(level, id, cafe, null);
        helper.assertTrue(books.getBoolean("full") && !books.getList("items", 10).isEmpty() && !books.getList("orders", 10).isEmpty(),
            "its books carry its wares and its orders");
        helper.succeed();
    }

    // ============================================================ bb02 orders

    /**
     * Ordering from the stores: a bulk order of three hundred carrots at half a coin, six in the hundred off (141.00c for
     * 150.00c's worth); a handful of five, no discount (2.50c); an order its till cannot pay refused, nothing taken; and
     * one for what the stores have not got refused.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "bb02_orders")
    public static void bb02_bulk_small_and_refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2502000;
        level.setDayTime(4 * 24000L + 7000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 4);
        UUID id = folk.get(0).ownerId();
        stores(level, id, x, lots(Items.CARROT, 8));
        Ledger.addCoins(id, 400);
        Villages.builtAtForTests(id, "cafe", Kit.surface(level, x + 10, Z));
        Businesses.ensure(level, id);
        Business cafe = Businesses.of(id, "cafe");
        Businesses.spareAllForTests(true);
        Businesses.storesPriceForTests(new ItemStack(Items.CARROT), 0.5);
        Businesses.fundForTests(id, cafe, 20000);
        long coin0 = CoinCensus.count(id);
        long till0 = cafe.till;
        Businesses.OrderResult bulk = Businesses.order(level, id, cafe, new ItemStack(Items.CARROT), 300, false);
        Kit.log("bb02 bulk: " + bulk.words());
        helper.assertTrue(bulk.ok() && bulk.total() == 14100 && bulk.discount() == 900 && Businesses.discount(300) == 6,
            "300 carrots at 0.50c: 150.00c less 6% = 141.00c: " + bulk.words());
        Businesses.OrderResult small = Businesses.order(level, id, cafe, new ItemStack(Items.CARROT), 5, false);
        helper.assertTrue(small.ok() && small.total() == 250 && small.discount() == 0, "five at 0.50c, 2.50c: " + small.words());
        helper.assertTrue(cafe.till == till0 - 14350 && cafe.stock.get("minecraft:carrot").qty == 305 && cafe.stock.get("minecraft:carrot").cost == 14350,
            "its stock at exactly what it paid");
        long till1 = cafe.till;
        Businesses.OrderResult dear = Businesses.order(level, id, cafe, new ItemStack(Items.CARROT), 200, false);
        helper.assertTrue(!dear.ok() && cafe.till == till1, "an order its till cannot pay is refused: " + dear.words());
        Businesses.spareAllForTests(false);
        Businesses.OrderResult none = Businesses.order(level, id, cafe, new ItemStack(Items.DIAMOND), 3, false);
        helper.assertTrue(!none.ok() && cafe.till == till1, "nothing the stores have not got: " + none.words());
        helper.assertTrue(CoinCensus.count(id) == coin0, "every penny accounted for: " + coin0 + " -> " + CoinCensus.count(id));
        helper.assertTrue(Businesses.of(id, "storehouse").last(level.getDayTime() / 24000L, 1).revenue == 14350, "the stores' books show the sale");
        helper.succeed();
    }

    // ============================================================ bb03 a week of trade

    /**
     * A town with a café, a shop and a tavern trades for six days: each morning the businesses pay their rent, lights and
     * repairs and pay their own hands out of their tills (the town no longer pays them); the folk buy over the counters;
     * the café makes its bread of bought wheat. At the end every private business's books balance (its worth now is what
     * its owners put in, plus its profit, less what they drew), wages and rent were paid and booked, no till is
     * overdrawn, the treasury is not run dry, and the town's coin is just what it was, to the penny.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "bb03_week")
    public static void bb03_week_of_trade_balances(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2504000;
        long day0 = 6;
        level.setDayTime(day0 * 24000L + 3000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 8);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, join(lots(Items.WHEAT, 4), lots(Items.BREAD, 2), lots(Items.COOKIE, 2), lots(Items.TORCH, 2), lots(Items.COAL, 1)));
        Ledger.addCoins(id, 600);
        for (VillageFolkEntity f : folk) f.earn(30);
        Villages.builtAtForTests(id, "cafe", Kit.surface(level, x + 10, Z));
        Villages.builtAtForTests(id, "shop", Kit.surface(level, x + 10, Z + 12));
        Villages.builtAtForTests(id, "tavern", Kit.surface(level, x - 10, Z + 12));
        Businesses.ensure(level, id);
        Hospitality.appointForTests(level, v, folk.get(2), Hospitality.Role.BARKEEP);
        Business cafe = Businesses.of(id, "cafe"), shop = Businesses.of(id, "shop"), tavern = Businesses.of(id, "tavern");
        helper.assertTrue(cafe != null && shop != null && tavern != null, "three businesses");
        Businesses.spareAllForTests(true);
        long coin0 = CoinCensus.count(id);
        Map<String, ItemStack> samples = Map.of("minecraft:wheat", new ItemStack(Items.WHEAT), "minecraft:bread", new ItemStack(Items.BREAD));
        for (int d = 0; d < 6; d++) {
            level.setDayTime((day0 + d) * 24000L + 3000L);
            Businesses.morningForTests(level, v);
            Market.payWages(level, v);
            for (int k = 3; k < 8; k++) {
                VillageFolkEntity buyer = folk.get(k);
                counterSale(level, id, buyer, Stockroom.Seller.CAFE, new ItemStack(Items.BREAD), 1);
                if (k % 2 == 0) counterSale(level, id, buyer, Stockroom.Seller.SHOP, new ItemStack(Items.TORCH, 8), 2);
                if (k % 3 == 0) counterSale(level, id, buyer, Stockroom.Seller.TAVERN, new ItemStack(Items.COOKIE), 1);
            }
            Businesses.madeForTests(level, id, cafe, Map.of("minecraft:wheat", 6L), Map.of("minecraft:bread", 2L), samples);
        }
        long day = level.getDayTime() / 24000L;
        StringBuilder log = new StringBuilder();
        for (Business b : List.of(cafe, shop, tavern)) {
            long equity = Businesses.equityForTests(b);
            long expect = b.allTime.net() - b.allTime.drawings + b.allTime.capital;
            log.append(b.name).append(": ").append(BusinessBooks.periodWords(b.allTime)).append("; equity ").append(equity).append(" vs ").append(expect)
                .append(", till ").append(b.till).append("; ");
            helper.assertTrue(equity == expect, b.name + "'s books balance: worth " + equity + " = capital + profit - drawings " + expect);
            helper.assertTrue(b.till >= 0, b.name + "'s till is never overdrawn: " + b.till);
            helper.assertTrue(b.allTime.rent > 0, b.name + " paid its rent: " + b.allTime.rent);
        }
        Kit.log("bb03 " + log);
        helper.assertTrue(cafe.allTime.wages > 0 && tavern.allTime.wages > 0 && shop.allTime.wages > 0,
            "each paid its own hands: " + cafe.allTime.wages + ", " + shop.allTime.wages + ", " + tavern.allTime.wages);
        Business.Line bread = cafe.last(day, 7).lines.get("minecraft:bread");
        helper.assertTrue(bread != null && bread.units >= 25 && bread.cogs > 0, "the café's bread sold, at its cost: " + (bread == null ? "none" : bread.units + " " + bread.cogs));
        helper.assertTrue(Ledger.coins(id) > 0, "the treasury is not run dry: " + Ledger.coins(id));
        long coin1 = CoinCensus.count(id);
        Kit.log("bb03 the town's coin " + coin0 + " -> " + coin1 + "; " + CoinCensus.line(id));
        helper.assertTrue(coin1 == coin0, "not a penny made or lost in six days of trade: " + coin0 + " -> " + coin1);
        helper.succeed();
    }

    // ============================================================ bb04 a business that loses money

    /**
     * A café that sells nothing still pays its rent and its cook: three days at a loss and it raises its prices (the
     * reason written in its books); a week and its cook goes on short time; with its owner's savings spent and its trade
     * credit gone it is wound up and the town holds it till someone takes it on. Coin conserved throughout.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "bb04_losses")
    public static void bb04_loss_making_business_reacts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2506000;
        long day0 = 6;
        level.setDayTime(day0 * 24000L + 3000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 4);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, lots(Items.WHEAT, 2));
        Ledger.addCoins(id, 400);
        Villages.builtAtForTests(id, "cafe", Kit.surface(level, x + 10, Z));
        Businesses.ensure(level, id);
        Business cafe = Businesses.of(id, "cafe");
        long coin0 = CoinCensus.count(id);
        double markup0 = cafe.markup;
        boolean shortTime = false, raised = false;
        int days = 0;
        for (int d = 0; d < 40 && !cafe.town(); d++) {
            level.setDayTime((day0 + d) * 24000L + 3000L);
            Businesses.morningForTests(level, v);
            Market.payWages(level, v);
            shortTime |= !cafe.shortTime.isEmpty();
            raised |= cafe.markup > markup0;
            days = d;
        }
        Kit.log("bb04 after " + days + " days: owners " + cafe.ownersWords() + ", markup " + markup0 + " -> " + cafe.markup + ", notes " + cafe.notes);
        helper.assertTrue(raised && cafe.notes.stream().anyMatch(n -> n.contains("prices up")), "it raised its prices and said why");
        helper.assertTrue(shortTime || folk.stream().noneMatch(f -> Businesses.employer(id, f) == cafe && !cafe.ownedBy(f.getUUID())),
            "a hand was put on short time");
        helper.assertTrue(cafe.town() && cafe.notes.stream().anyMatch(n -> n.contains("wound up")), "wound up, the town holding it: " + cafe.ownersWords());
        helper.assertTrue(CoinCensus.count(id) == coin0, "coin conserved: " + coin0 + " -> " + CoinCensus.count(id));
        helper.succeed();
    }

    // ============================================================ bb05 every item costed

    /**
     * Every item in the game has a cost basis behind it, worked out the whole way back without a loop or a crash; every
     * good on the board, every thing on the base list and every ware a seller keeps has a real chain (gathered, made,
     * brewed or bought in). And the chain holds for a crafted tool, a piece of armour, a potion, a building block, a dyed
     * rug and a cooked meal: each costs its parts at their own cost, the fuel's share, and the maker's labour.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bb05_coverage")
    public static void bb05_every_item_costed(GameTestHelper helper) {
        CostBook.reset();
        List<String> bad = new ArrayList<>();
        Map<String, Integer> ways = CostBook.coverage(bad);
        Kit.log("bb05 every item: " + ways + "; bad " + bad);
        helper.assertTrue(bad.isEmpty(), "no item without a cost: " + bad);
        List<String> unbacked = new ArrayList<>();
        List<ItemStack> sellable = new ArrayList<>();
        for (Market.Good g : Market.boardGoods()) {
            for (Item it : BuiltInRegistries.ITEM) {
                ItemStack one = new ItemStack(it);
                if (g.what().test(one)) { sellable.add(one); break; }
            }
        }
        for (BaseWorth.Entry e : BaseWorth.entries()) {
            Item it = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(e.id())).orElse(Items.AIR);
            if (it != Items.AIR) sellable.add(new ItemStack(it));
        }
        for (ItemStack one : sellable) {
            CostBook.Basis b = CostBook.of(one);
            if (!(b.total() > 0) || b.how().equals("not to be had") || b.how().equals("nothing")) unbacked.add(BuiltInRegistries.ITEM.getKey(one.getItem()) + " " + b.how());
        }
        helper.assertTrue(unbacked.isEmpty(), "every sellable thing has a cost from a real chain: " + unbacked);
        // The chains.
        CostBook.Basis pick = CostBook.of(Items.IRON_PICKAXE);
        CostBook.Basis ingot = CostBook.of(Items.IRON_INGOT);
        CostBook.Basis stick = CostBook.of(Items.STICK);
        CostBook.Basis chest = CostBook.of(Items.IRON_CHESTPLATE);
        CostBook.Basis bricks = CostBook.of(Items.STONE_BRICKS);
        CostBook.Basis rug = CostBook.of(Items.RED_CARPET);
        CostBook.Basis steak = CostBook.of(Items.COOKED_BEEF);
        ItemStack swift = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        CostBook.Basis potion = CostBook.of(swift);
        Kit.log("bb05 pick " + pick.words());
        Kit.log("bb05 ingot " + ingot.words());
        Kit.log("bb05 chestplate " + chest.words());
        Kit.log("bb05 stone bricks " + bricks.words());
        Kit.log("bb05 red carpet " + rug.words());
        Kit.log("bb05 steak " + steak.words());
        Kit.log("bb05 swiftness " + potion.words());
        helper.assertTrue(Math.abs(pick.materials() - (3 * ingot.total() + 2 * stick.total())) < 1e-6 && pick.labour() > 0,
            "a pick is three ingots and two sticks at their own cost, and the smith's time: " + pick.words());
        helper.assertTrue(ingot.fuel() > 0 && ingot.how().startsWith("smelted") && ingot.depth() >= 1, "an ingot is smelted with the fuel's share: " + ingot.words());
        helper.assertTrue(Math.abs(chest.materials() - 8 * ingot.total()) < 1e-6, "a chestplate is eight ingots: " + chest.words());
        CostBook.Basis stone = CostBook.of(Items.STONE);
        helper.assertTrue(bricks.depth() >= 1 && Math.abs(bricks.materials() - stone.total()) < 1e-6 && bricks.labour() > 0,
            "stone bricks are cut of stone at its own cost (four of four), and the mason's time: " + bricks.words() + " / stone " + stone.words());
        helper.assertTrue(rug.depth() >= 1 && rug.materials() > 0, "a red rug of red wool: " + rug.words());
        helper.assertTrue(steak.fuel() > 0 && Math.abs(steak.materials() - CostBook.of(Items.BEEF).total()) < 1e-6, "a steak is its beef and the fuel: " + steak.words());
        helper.assertTrue(potion.materials() > CostBook.of(Items.GLASS_BOTTLE).total() && potion.fuel() > 0 && potion.how().contains("brewed"),
            "a potion of swiftness is its bottle, wart and sugar, and the blaze's share: " + potion.words());
        for (CostBook.Basis b : List.of(pick, ingot, chest, bricks, rug, steak, potion)) {
            helper.assertTrue(Math.abs(b.total() - (b.materials() + b.fuel() + b.labour() + b.tools() + b.overhead())) < 1e-9, "its parts add up: " + b.words());
        }
        helper.succeed();
    }

    // ============================================================ bb06 owners

    /**
     * Owners come and go: the proprietor dies with nobody to leave it to, so its stake and its purse go to the town; a
     * player who is a citizen buys the business from the town at its worth, buys a share of another, and takes its
     * drawings as coin. Not a penny made or lost.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "bb06_owners")
    public static void bb06_owners_come_and_go(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2508000;
        level.setDayTime(5 * 24000L + 7000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 5);
        UUID id = folk.get(0).ownerId();
        Ledger.addCoins(id, 500);
        Villages.builtAtForTests(id, "cafe", Kit.surface(level, x + 10, Z));
        Villages.builtAtForTests(id, "smithy", Kit.surface(level, x + 10, Z + 12));
        Businesses.ensure(level, id);
        Business cafe = Businesses.of(id, "cafe"), smithy = Businesses.of(id, "smithy");
        helper.assertTrue(cafe != null && smithy != null && !cafe.town(), "a café and a smithy, privately owned");
        UUID ownerId = cafe.owners.get(0).who();
        VillageFolkEntity owner = folk.stream().filter(f -> f.getUUID().equals(ownerId)).findFirst().orElse(null);
        helper.assertTrue(owner != null, "its owner is one of the town");
        ServerPlayer you = helper.makeMockServerPlayerInLevel();
        you.moveTo(x + 0.5, Kit.surface(level, x, Z).getY(), Z + 0.5);
        Ledger.addCitizen(id, you.getUUID(), you.getName().getString());
        you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
        you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
        long coin0 = CoinCensus.count(id) + Market.coinsHeld(you) * 100L;
        int purse = owner.purse();
        long tre = Businesses.treasuryPence(id);
        Businesses.left(id, owner, true);
        Kit.log("bb06 the owner died: " + cafe.ownersWords() + "; purse " + purse + " -> " + owner.purse() + "; treasury " + tre + " -> " + Businesses.treasuryPence(id));
        helper.assertTrue(cafe.town() || cafe.owners.stream().noneMatch(s -> ownerId.equals(s.who())), "its stake passed on");
        helper.assertTrue(owner.purse() == 0, "its purse passed on, not lost");
        long coin1 = CoinCensus.count(id) + Market.coinsHeld(you) * 100L;
        helper.assertTrue(coin1 == coin0 - purse * 100L || coin1 == coin0, "its purse went to its heir or the town: " + coin0 + " -> " + coin1);
        // The dead owner's own purse is no longer counted (it is gone from the town's folk): count afresh from here.
        coin0 = CoinCensus.count(id) + Market.coinsHeld(you) * 100L;
        if (!cafe.town()) Businesses.windUpForTests(level, id, cafe);
        String bought = Businesses.buy(level, id, cafe, you);
        Kit.log("bb06 " + bought + " -> " + cafe.ownersWords());
        helper.assertTrue(cafe.ownedBy(you.getUUID()) && cafe.owners.size() == 1, "the player owns the café: " + bought);
        String in = Businesses.invest(level, id, smithy, you, 20);
        Kit.log("bb06 " + in + " -> " + smithy.ownersWords());
        helper.assertTrue(smithy.ownedBy(you.getUUID()) && smithy.owners.stream().mapToInt(Business.Stake::parts).sum() == Business.WHOLE,
            "the player holds a share of the smithy, the stakes still the whole: " + smithy.ownersWords());
        long coin2 = CoinCensus.count(id) + Market.coinsHeld(you) * 100L;
        helper.assertTrue(coin2 == coin0, "buying and investing moved coin, made none: " + coin0 + " -> " + coin2);
        helper.succeed();
    }
    // ============================================================ bb07 a fire

    /**
     * A fire on the café's premises: a tenth of its stock spoilt (at cost) and three days' rent of repairs paid to the
     * town, both in its books as losses; its books still balance and no coin is made or lost.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "bb07_fire")
    public static void bb07_fire_on_the_premises(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2510000;
        level.setDayTime(4 * 24000L + 7000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 4);
        UUID id = folk.get(0).ownerId();
        stores(level, id, x, lots(Items.WHEAT, 3));
        Ledger.addCoins(id, 300);
        BlockPos at = Kit.surface(level, x + 10, Z);
        Villages.builtAtForTests(id, "cafe", at);
        Businesses.ensure(level, id);
        Business cafe = Businesses.of(id, "cafe");
        Businesses.spareAllForTests(true);
        Businesses.storesPriceForTests(new ItemStack(Items.WHEAT), 0.5);
        Businesses.fundForTests(id, cafe, 3000);
        Businesses.order(level, id, cafe, new ItemStack(Items.WHEAT), 100, false);
        long coin0 = CoinCensus.count(id), stock0 = cafe.stockCost();
        Businesses.fire(level, id, at.east(3), "a fire at the café was put out");
        Business.Day d = cafe.today(level.getDayTime() / 24000L);
        Kit.log("bb07 stock " + stock0 + " -> " + cafe.stockCost() + "; losses " + d.losses + "; " + cafe.notes);
        // A hundred wheat at 0.50c, 3% off for the bulk: 48.50c; a tenth of them at their average cost, 4.85c.
        helper.assertTrue(stock0 == 4850 && cafe.stockCost() == stock0 - 485 && cafe.stock.get("minecraft:wheat").qty == 90, "a tenth of its wheat lost at cost");
        helper.assertTrue(d.losses == 485 + Businesses.rentForTests(id, cafe) * 3, "the stock and the repairs in its losses: " + d.losses);
        helper.assertTrue(Businesses.equityForTests(cafe) == cafe.allTime.net() - cafe.allTime.drawings + cafe.allTime.capital, "its books balance");
        helper.assertTrue(CoinCensus.count(id) == coin0, "coin conserved: " + coin0 + " -> " + CoinCensus.count(id));
        helper.succeed();
    }
}
