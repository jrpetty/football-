package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MarketLean;
import com.jrpetty.mcassistant.entity.PriceIndex;
import com.jrpetty.mcassistant.entity.Prices;
import com.jrpetty.mcassistant.entity.Purchases;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.Worth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * [market] The town economy: one worth for everything, one spread for everybody, prices by supply and demand, coin
 * that holds its value (entity/Prices, Market, PriceIndex, MarketLean; village/Worth).
 *
 * <ul>
 * <li><b>mk01</b>: everything in the game has a worth, none absurd; a made thing is worth at least its cheapest makings;
 *     the board's goods are worth what the engine says.</li>
 * <li><b>mk02</b>: no loop pays: at any of a town's prices, a lot never goes for less than the town would pay for it,
 *     and nothing made of what the town sells fetches more from it than its makings cost; a player buying a lot at the
 *     stall and selling it straight back loses coin, on any day.</li>
 * <li><b>mk03</b>: a glut makes a thing cheap and a shortage dear, a little a day, within the band; the gold bar never
 *     moves and the emerald hardly; the town's want shows on the price ("wanted for ...").</li>
 * <li><b>mk04</b>: coin is conserved: a player's purchase and sale and a payday move coin and make none.</li>
 * <li><b>mk05</b>: a hundred days of a working town: the treasury never runs dry and never hoards, wages are paid,
 *     prices stay in their band, the coin in the town grows no faster than its work.</li>
 * <li><b>mk06</b>: production follows prices: dear bread wants more farmers, a glut of logs fewer woodcutters.</li>
 * </ul>
 *
 * <p>Each runs on its own ground (x 1,720,000 to 1,739,999, z 66,000).
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class MarketGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** A village's first folk on clean ground at x, the clock in the afternoon (out of the morning's market hours). */
    private static VillageFolkEntity founder(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 3 + 8000);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, Z), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        return f;
    }

    /** A chest of the stores beside the heart. */
    private static Container chest(ServerLevel level, UUID id, int dx, int dz) {
        BlockPos heart = Villages.get(id).centre();
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Villages.forgetStores(id);
        return (Container) level.getBlockEntity(at);
    }

    /** A grown folk of this village at this trade. */
    private static VillageFolkEntity hand(ServerLevel level, Villages.Village v, int x, int z, StationTask trade, int lv) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        BlockPos at = Kit.surface(level, x, z);
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        f.rename(Names.freeFor(v.id()));
        VillageSpawner.starterKit(f);
        f.joinVillage(v.id(), v.centre());
        level.addFreshEntity(f);
        Villages.recordBirth(v.id());
        f.setAgeForTests(30);
        f.setJob(trade);
        f.tradeXpForTests(trade, lv * lv * Math.max(1, AssistantConfig.levelCurveFactor()) + 1);
        return f;
    }

    private static int coins(Player p) {
        return Market.coinsHeld(p);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    // ============================================================ one worth

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "mk01_one_worth")
    public static void mk01_one_worth(GameTestHelper helper) {
        Prices.reset();
        int all = 0, known = 0, absurd = 0;
        List<String> bad = new ArrayList<>();
        for (Item it : BuiltInRegistries.ITEM) {
            if (it == Items.AIR) continue;
            double v = Prices.each(it);
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) bad.add(BuiltInRegistries.ITEM.getKey(it).getPath() + "=" + v);
            if (v <= 0) continue;                                         // not to be had in survival
            all++;
            if (Prices.known(it)) known++;
            if (v > 2000) absurd++;
        }
        // A made thing at least its cheapest makings (the work on top), unless the game gives it for less some other way:
        // gathered (a melon off the vine), or the recipe is the undoing of another, or an emptying.
        Map<String, List<Worth.Recipe>> recipes = Prices.recipes();
        List<String> under = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, List<Worth.Recipe>> e : recipes.entrySet()) {
            Item out = item(e.getKey());
            if (out == Items.AIR || !Prices.known(out)) continue;
            double cheapest = Double.MAX_VALUE;
            for (Worth.Recipe r : e.getValue()) {
                if (r.way() == Worth.Way.EMPTY || Worth.unmaking(r, recipes)) continue;
                double m = Worth.makings(r, k -> {
                    Item i = item(k);
                    return i == Items.AIR || !Prices.known(i) ? Double.NaN : Prices.each(i);
                });
                if (!Double.isNaN(m)) cheapest = Math.min(cheapest, m / r.count());
            }
            if (cheapest == Double.MAX_VALUE) continue;
            checked++;
            if (Prices.each(out) + 1e-6 < cheapest && !gathered(out)) {
                under.add(e.getKey().replace("minecraft:", "") + String.format(Locale.ROOT, " %.3f<%.3f", Prices.each(out), cheapest));
            }
        }
        List<String> board = new ArrayList<>();
        for (Market.Good g : Market.boardGoods()) {
            ItemStack s = PriceIndex.sampleOfGood(g);
            if (s.isEmpty()) continue;
            if (Math.abs(g.value() - Prices.each(s.getItem())) > 1e-9) board.add(g.name() + " " + g.value() + " vs " + Prices.each(s.getItem()));
        }
        Kit.log("mk01 " + Prices.summary() + "; " + all + " to be had, " + known + " known, " + absurd + " over 2000; " + checked
            + " made things checked, " + under.size() + " under their makings: " + under + "; raised to their makings: " + Prices.raised());
        Kit.log(String.format(Locale.ROOT, "mk01 bread %.3f, cookie %.3f, cake %.3f, iron %.3f, block %.3f, bucket %.3f, glass %.3f, planks %.3f, "
                + "stick %.4f, torch %.3f, emerald %.2f, gold %.2f, diamond %.1f", Prices.each(Items.BREAD), Prices.each(Items.COOKIE),
            Prices.each(Items.CAKE), Prices.each(Items.IRON_INGOT), Prices.each(Items.IRON_BLOCK), Prices.each(Items.BUCKET),
            Prices.each(Items.GLASS), Prices.each(Items.OAK_PLANKS), Prices.each(Items.STICK), Prices.each(Items.TORCH),
            Prices.each(Items.EMERALD), Prices.each(Items.GOLD_INGOT), Prices.each(Items.DIAMOND)));
        helper.assertTrue(bad.isEmpty(), "no worth below nothing or not a number: " + bad);
        helper.assertTrue(known * 20 >= all * 19, "all but a few priced by name or recipe: " + known + " of " + all);
        helper.assertTrue(absurd <= 3, "nothing absurd (over 2000 a piece is the dragon's egg and the like): " + absurd);
        helper.assertTrue(under.isEmpty(), "every made thing worth at least its makings: " + under);
        helper.assertTrue(board.isEmpty(), "the board's goods at the engine's worth: " + board);
        helper.assertTrue(Prices.each(Items.BREAD) >= 3 * Prices.each(Items.WHEAT), "a loaf at least its three wheat");
        helper.assertTrue(Math.abs(Prices.each(Items.GOLD_INGOT) - Market.COINS_PER_GOLD) < 1e-9, "a gold bar is its nine coins");
        helper.succeed();
    }

    /** Gathered: on the base list or of a family the engine prices as gathered (logs, wool, flowers, saplings, leaves). */
    private static boolean gathered(Item it) {
        String id = BuiltInRegistries.ITEM.getKey(it).toString();
        for (com.jrpetty.mcassistant.village.BaseWorth.Entry e : com.jrpetty.mcassistant.village.BaseWorth.entries()) {
            if (e.id().equals(id)) return true;
        }
        ItemStack s = new ItemStack(it);
        return s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.WOOL) || s.is(net.minecraft.tags.ItemTags.FLOWERS)
            || s.is(net.minecraft.tags.ItemTags.SAPLINGS) || s.is(net.minecraft.tags.ItemTags.LEAVES);
    }

    // ============================================================ no loops

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "mk02_no_loops")
    public static void mk02_no_loops(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 1721000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            Container c = chest(level, id, 3, 3);
            Item[] stock = { Items.BREAD, Items.WHEAT, Items.COOKIE, Items.COCOA_BEANS, Items.OAK_PLANKS, Items.OAK_LOG, Items.IRON_INGOT,
                Items.RAW_IRON, Items.GLASS, Items.SAND, Items.TORCH, Items.COAL, Items.COBBLESTONE, Items.STONE_BRICKS, Items.SUGAR,
                Items.SUGAR_CANE, Items.COOKED_BEEF, Items.BEEF, Items.IRON_PICKAXE, Items.BUCKET };
            for (int i = 0; i < stock.length; i++) c.setItem(i, new ItemStack(stock[i], Math.min(64, stock[i].getDefaultMaxStackSize())));
            c.setChanged();
            Ledger.addCoins(id, 5000);
            Random rnd = new Random(5);
            Map<String, List<Worth.Recipe>> recipes = Prices.recipes();
            int checks = 0;
            List<String> loops = new ArrayList<>();
            for (int trial = 0; trial < 12; trial++) {
                // Every price in the town anywhere in its band, and market day or not.
                for (Item it : stock) PriceIndex.setForTests(id, new ItemStack(it), Worth.LOWEST + rnd.nextDouble() * (Worth.DEAREST - Worth.LOWEST));
                long day = level.getDayTime() / 24000L;
                if (trial % 2 == 1) {
                    for (int d = 0; d < 7 && !Market.marketDay(id, day); d++) day++;
                    level.setDayTime(day * 24000L + 8000L);
                }
                for (Item it : stock) {
                    ItemStack one = new ItemStack(it);
                    Market.Good g = Market.goodFor(one);
                    int lot = g == null ? 1 : g.bundle();
                    int least = Market.leastLot(level, id, one, lot), bid = Market.bidLot(level, id, one, lot);
                    checks++;
                    if (least <= bid) loops.add(it + ": sold for " + least + ", bought back for " + bid);
                    // Made of what the town sells: the makings at the least it lets them go for, against what it pays.
                    for (Worth.Recipe r : recipes.getOrDefault(BuiltInRegistries.ITEM.getKey(it).toString(), List.of())) {
                        if (r.way() == Worth.Way.EMPTY) continue;
                        int paid = 0;
                        boolean all = true;
                        for (Worth.Part p : r.parts()) {
                            int best = Integer.MAX_VALUE;
                            for (String o : p.options()) {
                                Item oi = item(o);
                                if (Market.goodFor(new ItemStack(oi)) == null) continue;
                                best = Math.min(best, Market.leastLot(level, id, new ItemStack(oi), p.n()));
                            }
                            if (best == Integer.MAX_VALUE) { all = false; break; }
                            paid += best;
                        }
                        if (!all) continue;
                        int got = Market.bidLot(level, id, one, r.count());
                        checks++;
                        if (got > paid) loops.add(it + " made of what the town sells: paid " + paid + ", fetched " + got);
                    }
                }
            }
            // A player at the stall: a lot of bread bought and sold straight back, at the dearest and the cheapest.
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            p.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
            ItemStack bread = new ItemStack(Items.BREAD);
            List<String> trips = new ArrayList<>();
            for (double factor : new double[]{ Worth.LOWEST, 1.0, Worth.DEAREST }) {
                PriceIndex.setForTests(id, bread, factor);
                int before = coins(p);
                String bought = Market.buy(level, v, p, bread, Stockroom.Seller.MARKET);
                p.getInventory().setItem(p.getInventory().selected, ItemStack.EMPTY);
                ItemStack lot = ItemStack.EMPTY;
                for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                    if (p.getInventory().getItem(i).is(Items.BREAD)) { lot = p.getInventory().removeItemNoUpdate(i); break; }
                }
                p.getInventory().setItem(p.getInventory().selected, lot);
                String sold = Market.deal(level, v, p, bread);
                int after = coins(p);
                trips.add(factor + ": " + bought + " / " + sold + " (" + before + " -> " + after + ")");
                helper.assertTrue(after < before, "a lot bought and sold straight back loses coin: " + trips);
            }
            Kit.log("mk02 " + checks + " checks, " + loops.size() + " loops: " + loops + "; at the stall: " + trips);
            helper.assertTrue(loops.isEmpty(), "no buying of the town and selling back pays: " + loops);
            helper.succeed();
        });
    }

    // ============================================================ supply and demand

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "mk03_supply_and_demand")
    public static void mk03_supply_and_demand(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 1722000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            Container c = chest(level, id, 3, 3);
            for (int i = 0; i < 20; i++) c.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            c.setChanged();
            ItemStack bread = new ItemStack(Items.BREAD), cobble = new ItemStack(Items.COBBLESTONE), gold = new ItemStack(Items.GOLD_INGOT),
                emerald = new ItemStack(Items.EMERALD);
            long d0 = level.getDayTime() / 24000L + 1;
            StringBuilder days = new StringBuilder();
            double lastBread = PriceIndex.factor(id, bread), lastCobble = PriceIndex.factor(id, cobble);
            for (int d = 0; d < 20; d++) {
                // The day: bread, gold and emeralds wanted at the counter and none there; nobody wants the cobblestone.
                level.setDayTime((d0 + d) * 24000L + 8000L);
                for (int i = 0; i < 20; i++) Stockroom.missed(level, id, Stockroom.Seller.SHOP, bread);
                for (int i = 0; i < 10; i++) Stockroom.missed(level, id, Stockroom.Seller.SHOP, gold);
                for (int i = 0; i < 10; i++) Stockroom.missed(level, id, Stockroom.Seller.SHOP, emerald);
                level.setDayTime((d0 + d + 1) * 24000L + 8000L);
                PriceIndex.morning(level, v, d0 + d + 1);
                double b = PriceIndex.factor(id, bread), k = PriceIndex.factor(id, cobble);
                helper.assertTrue(b >= lastBread - 1e-9 && b <= lastBread * 1.151 + 1e-9, "bread a little dearer each day: " + lastBread + " -> " + b);
                helper.assertTrue(k <= lastCobble + 1e-9 && k >= lastCobble * 0.849 - 1e-9, "cobblestone a little cheaper: " + lastCobble + " -> " + k);
                helper.assertTrue(b <= Worth.DEAREST + 1e-9 && k >= Worth.LOWEST - 1e-9, "within the band: " + b + ", " + k);
                helper.assertTrue(Math.abs(PriceIndex.factor(id, gold) - 1.0) < 1e-9, "the gold bar never moves: " + PriceIndex.factor(id, gold));
                double e = PriceIndex.factor(id, emerald);
                helper.assertTrue(e >= 0.8 - 1e-9 && e <= 1.25 + 1e-9, "the emerald hardly: " + e);
                lastBread = b;
                lastCobble = k;
                if (d % 4 == 3) days.append(String.format(Locale.ROOT, "day %d bread %.2f cobble %.2f gold %.2f emerald %.2f; ", d + 1, b, k,
                    PriceIndex.factor(id, gold), e));
            }
            String page = PriceIndex.page(level, v);
            Kit.log("mk03 " + days + "\nmk03 the page:\n" + page);
            helper.assertTrue(lastBread > 2.0 && lastCobble < 0.7, "dear bread and cheap cobblestone: " + lastBread + ", " + lastCobble);
            helper.assertTrue(PriceIndex.trend(id, bread) >= 0 && page.contains("Coin:"), "the page shows the trend and the coin");
            helper.succeed();
        });
    }

    // ============================================================ coin conserved

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "mk04_coin_conserved")
    public static void mk04_coin_conserved(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 1723000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            Container c = chest(level, id, 3, 3);
            for (int i = 0; i < 6; i++) c.setItem(i, new ItemStack(Items.BREAD, 64));
            c.setChanged();
            List<VillageFolkEntity> folk = new ArrayList<>();
            folk.add(f);
            f.setJob(StationTask.FARM);
            for (int i = 0; i < 5; i++) folk.add(hand(level, v, v.centre().getX() - 8 + 3 * i, Z + 8, i % 2 == 0 ? StationTask.FARM : StationTask.MINE, 5));
            Ledger.takeCoins(id, Ledger.coins(id));
            Ledger.addCoins(id, 200);
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            p.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 40));
            int[] total = new int[1];
            Runnable count = () -> {
                int n = Ledger.coins(id) + coins(p);
                for (VillageFolkEntity x : folk) n += x.purse();
                total[0] = n;
            };
            count.run();
            int start = total[0];
            // A player buys a lot of bread, and sells some back.
            String bought = Market.buy(level, v, p, new ItemStack(Items.BREAD), Stockroom.Seller.MARKET);
            count.run();
            int afterBuy = total[0];
            p.getInventory().setItem(p.getInventory().selected, new ItemStack(Items.BREAD, 8));
            String sold = Market.deal(level, v, p, new ItemStack(Items.BREAD));
            count.run();
            int afterSell = total[0];
            // A payday: the treasury's coin into the purses, the tax kept.
            int paid = Market.payWages(level, v);
            count.run();
            int afterPay = total[0];
            Kit.log("mk04 start " + start + "; " + bought + " -> " + afterBuy + "; " + sold + " -> " + afterSell + " (8 bread were the player's own); paid "
                + paid + " in wages -> " + afterPay + "; treasury " + Ledger.coins(id));
            helper.assertTrue(afterBuy == start && afterSell == start && afterPay == start, "no coin made or lost by a sale, a purchase or a payday: "
                + start + ", " + afterBuy + ", " + afterSell + ", " + afterPay);
            helper.assertTrue(paid > 0, "the wages were paid: " + paid);
            helper.succeed();
        });
    }

    // ============================================================ a hundred days

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "mk05_hundred_days")
    public static void mk05_hundred_days(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 1724000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        final int days = 100;
        List<VillageFolkEntity> folk = new ArrayList<>();
        long[] d0 = new long[1];
        int[] start = new int[3];
        int[][] rows = new int[days][5];
        double[][] prices = new double[days][2];
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            Villages.noteAttempt(id, level.getGameTime() + 4_000_000L);          // no building: the books, not the builders
            Villages.ageForTests(id, Villages.Age.STONE);
            Container c = chest(level, id, 3, 3);
            for (int i = 0; i < 8; i++) c.setItem(i, new ItemStack(Items.BREAD, 64));
            c.setChanged();
            folk.add(f);
            f.setJob(StationTask.FARM);
            StationTask[] trades = { StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.WOOD, StationTask.MINE, StationTask.MINE,
                StationTask.GUARD, StationTask.FISH, StationTask.SMELT };
            for (int i = 0; i < trades.length; i++) folk.add(hand(level, v, v.centre().getX() - 12 + 3 * i, Z + 9, trades[i], 5));
            Purchases.openForTests(id, true);
            d0[0] = level.getDayTime() / 24000L + 1;
        });
        for (int d = 0; d < days; d++) {
            final int day = d;
            helper.runAtTickTime(20 + d * 3, () -> {
                Villages.Village v = Villages.get(id);
                long today = d0[0] + day;
                // The day's work: the farmers' bread and wheat, the woodcutter's logs, the miners' stone and coal, the fish.
                level.setDayTime(today * 24000L + 7000L);
                for (VillageFolkEntity x : folk) {
                    ItemStack made = switch (x.stationTask()) {
                        case FARM -> new ItemStack(Items.BREAD, 12);
                        case WOOD -> new ItemStack(Items.OAK_LOG, 24);
                        case MINE -> new ItemStack(Items.COBBLESTONE, 48);
                        case FISH -> new ItemStack(Items.COD, 8);
                        case SMELT -> new ItemStack(Items.CHARCOAL, 8);
                        default -> ItemStack.EMPTY;
                    };
                    if (made.isEmpty()) continue;
                    Economy.produced(x, made.copy());
                    Market.intoStoresForTests(level, id, made);
                    // Its meals, bought at the town's price out of its own purse.
                    Purchases.get(level, x, s -> s.is(Items.BREAD) || s.is(Items.COD), 2, Purchases.Need.FOOD);
                }
                // The next morning's business.
                level.setDayTime((today + 1) * 24000L + 1000L);
                Market.tick(level, v);
                int purses = 0;
                for (VillageFolkEntity x : folk) purses += x.purse();
                rows[day][0] = Ledger.coins(id);
                rows[day][1] = purses;
                rows[day][2] = Market.wageBill(id);
                rows[day][3] = Market.lastShare(id);
                rows[day][4] = Economy.yesterday(id);
                prices[day][0] = PriceIndex.factor(id, new ItemStack(Items.BREAD));
                prices[day][1] = PriceIndex.factor(id, new ItemStack(Items.COBBLESTONE));
            });
        }
        helper.runAtTickTime(40 + days * 3, () -> {
            StringBuilder sb = new StringBuilder();
            int dry = 0, worst = Integer.MAX_VALUE, most = 0, unpaid = 0;
            for (int d = 0; d < days; d++) {
                int[] r = rows[d];
                if (d >= 3 && r[0] <= 0) dry++;
                if (d >= 3 && r[3] < 100) unpaid++;
                worst = Math.min(worst, r[0]);
                most = Math.max(most, r[0] + r[1]);
                if (d % 10 == 9 || d < 3) sb.append(String.format(Locale.ROOT, "day %d: treasury %d, purses %d, bill %d, paid %d%%, made %d, bread %.2f, stone %.2f; ",
                    d + 1, r[0], r[1], r[2], r[3], r[4], prices[d][0], prices[d][1]));
                helper.assertTrue(prices[d][0] >= Worth.LOWEST - 1e-9 && prices[d][0] <= Worth.DEAREST + 1e-9, "bread in its band");
            }
            int[] last = rows[days - 1], mid = rows[days / 2];
            int bill = Math.max(1, last[2]);
            Kit.log("mk05 " + sb);
            Kit.log("mk05 the treasury ran dry " + dry + " mornings, paid short " + unpaid + "; its least " + worst + "; most coin in the town "
                + most + "; at the end treasury " + last[0] + " (" + (last[0] / bill) + " days' wages), purses " + last[1] + "; at the half "
                + mid[0] + " and " + mid[1]);
            helper.assertTrue(dry == 0, "the treasury never runs dry after the first mornings: " + dry);
            helper.assertTrue(unpaid <= 10, "the wages paid in full nearly every morning: " + unpaid + " short");
            helper.assertTrue(last[0] <= (Market.UPKEEP_OVER_DAYS + 25) * bill, "and never hoards: " + last[0] + " against a bill of " + bill);
            int coinEnd = last[0] + last[1], coinMid = mid[0] + mid[1];
            helper.assertTrue(coinEnd <= coinMid * 2 + 20 * bill, "the coin in the town no runaway: " + coinMid + " at the half, " + coinEnd + " at the end");
            helper.succeed();
        });
    }

    // ============================================================ production follows prices

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "mk06_production_leans")
    public static void mk06_production_leans(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 1725000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            for (int i = 0; i < 9; i++) hand(level, v, v.centre().getX() - 12 + 3 * i, Z + 9, i < 3 ? StationTask.FARM : StationTask.WOOD, 5);
            double farmBefore = Villages.share(id, StationTask.FARM), woodBefore = Villages.share(id, StationTask.WOOD);
            for (String g : List.of("Wheat", "Bread", "Carrots", "Potatoes", "Beetroot", "Baked potato")) {
                ItemStack s = PriceIndex.sampleOfGood(goodNamed(g));
                PriceIndex.setForTests(id, s, 2.5);
            }
            PriceIndex.setForTests(id, new ItemStack(Items.OAK_LOG), 0.5);
            PriceIndex.setForTests(id, new ItemStack(Items.OAK_PLANKS), 0.5);
            double farm = MarketLean.lean(id, StationTask.FARM), wood = MarketLean.lean(id, StationTask.WOOD);
            double farmAfter = Villages.share(id, StationTask.FARM), woodAfter = Villages.share(id, StationTask.WOOD);
            String page = PriceIndex.page(level, v);
            Kit.log(String.format(Locale.ROOT, "mk06 lean farm %.2f, wood %.2f; shares (have - want) farm %.2f -> %.2f, wood %.2f -> %.2f; %s",
                farm, wood, farmBefore, farmAfter, woodBefore, woodAfter, MarketLean.line(id)));
            helper.assertTrue(farm > 1.2 && wood < 0.9, "dear bread leans the farm up, cheap logs the woods down: " + farm + ", " + wood);
            helper.assertTrue(farmAfter <= farmBefore && woodAfter >= woodBefore, "the shares follow: farm " + farmBefore + " -> " + farmAfter
                + ", wood " + woodBefore + " -> " + woodAfter);
            helper.assertTrue(page.contains("lean with the prices"), "the Prices page says so");
            helper.succeed();
        });
    }

    private static Market.Good goodNamed(String name) {
        for (Market.Good g : Market.GOODS) if (g.name().equals(name)) return g;
        throw new IllegalStateException("no good " + name);
    }
}
