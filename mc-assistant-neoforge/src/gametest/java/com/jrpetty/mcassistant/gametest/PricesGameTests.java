package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Meals;
import com.jrpetty.mcassistant.entity.PriceIndex;
import com.jrpetty.mcassistant.entity.Purchases;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;
import java.util.UUID;

/**
 * Prices by supply and demand, buyers who mind the price, and paying for everything once the shop opens
 * (entity/PriceIndex, Purchases).
 *
 * <ul>
 * <li><b>px01</b>: bread scarce and wanted by the dozen grows dearer day by day, never more than fifteen in the
 *     hundred a day; a stack of cookies nobody buys grows cheaper; the board's lot price and the prices page follow.</li>
 * <li><b>px02</b>: a rug at three times its worth is left on the shop's shelf (the refusal in the town's prices), and
 *     bread at three times its worth is still bought by a hungry folk at supper.</li>
 * <li><b>px03</b>: cookies at four tenths of their worth: a folk at the market buys two; at the usual price, one.</li>
 * <li><b>px04</b>: before the shop opens a supper comes free out of the stores; after, the same supper costs coin out of
 *     the folk's purse, and the treasury has it.</li>
 * <li><b>px05</b>: a folk with an empty purse still has its supper, on the slate; the next payday pays the slate back
 *     out of its wage, into the treasury.</li>
 * <li><b>px06</b>: a rug bought at the shop costs one rug's price, not the price of a lot of four (the bundle bug).</li>
 * </ul>
 *
 * <p>Each runs on its own ground (x 620,000 to 630,000, z 64,000), calls the logic straight, and keeps the clock out
 * of the morning's market hours (500 to 6000), so the town's own morning does not reckon the prices under it.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PricesGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 64000;
    private static final long DAY = 24000L * 3;

    /** A village's first folk on clean ground at x, the clock in the afternoon. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(DAY + 8000);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, Z), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        return f;
    }

    /** A chest of the stores beside the heart, empty, and no food anywhere else in the stores nor in this folk's pack. */
    private static Container larder(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        BlockPos heart = Villages.get(id).centre();
        BlockPos at = Kit.surface(level, heart.getX() + 3, heart.getZ() + 3);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container larder = (Container) level.getBlockEntity(at);
        Villages.forgetStores(id);
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.get(DataComponents.FOOD) != null || s.is(Items.RED_CARPET)) c.setItem(i, ItemStack.EMPTY);
            }
            c.setChanged();
        }
        noFood(f);
        return larder;
    }

    private static void noFood(VillageFolkEntity f) {
        f.removeMatching(s -> s.get(DataComponents.FOOD) != null, 9999);
    }

    private static int count(Container c, Item it) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    private static void purse(VillageFolkEntity f, int coins) {
        f.spend(f.purse());
        f.earn(coins);
        Purchases.forgetForTests(f);
    }

    // ============================================================ supply and demand

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px01_supply_and_demand")
    public static void px01_supply_and_demand(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 620000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            Container c = larder(level, f);
            c.setItem(0, new ItemStack(Items.BREAD, 2));
            for (int i = 1; i <= 10; i++) c.setItem(i, new ItemStack(Items.COOKIE, 64));
            c.setChanged();
            ItemStack bread = new ItemStack(Items.BREAD), cookie = new ItemStack(Items.COOKIE);
            Market.Good loaf = Market.goodFor(bread);
            int lotBefore = Market.sellPrice(level, id, loaf, false);
            long d0 = level.getDayTime() / 24000L + 1;
            double[] b = new double[5], k = new double[5];
            b[0] = PriceIndex.factor(id, bread);
            k[0] = PriceIndex.factor(id, cookie);
            for (int d = 0; d < 4; d++) {
                // The day: thirty loaves wanted at the shop's counter, twenty sold and ten not there; not a cookie.
                level.setDayTime((d0 + d) * 24000L + 8000L);
                for (int i = 0; i < 20; i++) Stockroom.sold(level, id, Stockroom.Seller.SHOP, bread, 1, 0);
                for (int i = 0; i < 10; i++) Stockroom.missed(level, id, Stockroom.Seller.SHOP, bread);
                // The next morning's reckoning.
                level.setDayTime((d0 + d + 1) * 24000L + 8000L);
                PriceIndex.morning(level, v, d0 + d + 1);
                b[d + 1] = PriceIndex.factor(id, bread);
                k[d + 1] = PriceIndex.factor(id, cookie);
                Kit.log(String.format(Locale.ROOT, "px01 morning %d: bread (2 in the stores, 30 wanted a day) %.3f of its worth, %.2fc; "
                        + "cookies (640, none wanted) %.3f, %.2fc", d + 1, b[d + 1], PriceIndex.each(level, id, bread), k[d + 1],
                    PriceIndex.each(level, id, cookie)));
            }
            int lotAfter = Market.sellPrice(level, id, loaf, false);
            for (int d = 1; d <= 4; d++) {
                helper.assertTrue(b[d] > b[d - 1] && b[d] <= b[d - 1] * 1.151, "bread dearer each morning, by fifteen in the hundred at most: "
                    + b[d - 1] + " -> " + b[d]);
                helper.assertTrue(k[d] < k[d - 1] && k[d] >= k[d - 1] * 0.849, "cookies cheaper each morning, by fifteen in the hundred at most: "
                    + k[d - 1] + " -> " + k[d]);
            }
            helper.assertTrue(b[4] > 1.3 && k[4] < 0.8, "after four days bread is dear and cookies cheap: " + b[4] + ", " + k[4]);
            helper.assertTrue(lotAfter > lotBefore, "the board's price for a lot of bread follows: " + lotBefore + "c -> " + lotAfter + "c");
            String page = PriceIndex.page(level, v);
            Kit.log("px01 eight loaves " + lotBefore + "c -> " + lotAfter + "c; the prices page:\n" + page);
            helper.assertTrue(page.contains("Bread") && page.contains("Cookies") && page.contains("Cost of living"),
                "the prices page shows them, and the cost of living");
            CompoundTag report = PriceIndex.report(level, id);
            ListTag rows = report.getList("goods", Tag.TAG_COMPOUND);
            boolean breadUp = false, cookiesDown = false;
            for (int i = 0; i < rows.size(); i++) {
                CompoundTag r = rows.getCompound(i);
                if (r.getString("name").equals("Bread")) breadUp = r.getInt("trend") > 0 && r.getInt("each100") > r.getInt("usual100");
                if (r.getString("name").equals("Cookies")) cookiesDown = r.getInt("trend") < 0 && r.getInt("each100") < r.getInt("usual100");
            }
            helper.assertTrue(breadUp && cookiesDown, "the books' Prices page has bread over its worth and rising, cookies under and falling: "
                + rows);
            helper.succeed();
        });
    }

    // ============================================================ too dear

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px02_too_dear")
    public static void px02_too_dear(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 622000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        helper.runAtTickTime(10, () -> {
            f.setJob(StationTask.FARM);
            purse(f, 40);
            Container c = larder(level, f);
            c.setItem(0, new ItemStack(Items.BREAD, 16));
            c.setItem(1, new ItemStack(Items.RED_CARPET, 4));
            c.setChanged();
            Purchases.openForTests(id, true);
            ItemStack bread = new ItemStack(Items.BREAD), rug = new ItemStack(Items.RED_CARPET);
            PriceIndex.setForTests(id, bread, 3.0);
            PriceIndex.setForTests(id, rug, 3.0);
            // A rug for its home at three times its worth: left on the shelf.
            int purse = f.purse();
            double rugEach = Purchases.priceEach(level, id, rug, f);
            int lux = Purchases.get(level, f, s -> s.is(Items.RED_CARPET), 1, Purchases.Need.LUXURY);
            int refused = PriceIndex.tallyForTests(id, rug)[2];
            Kit.log(String.format(Locale.ROOT, "px02 a rug at %.2fc (it expects %.2fc): bought %d; rugs in the stores %d; purse %d -> %d; "
                + "refused in the town's prices %d", rugEach, Purchases.expects(f, rug), lux, count(c, Items.RED_CARPET), purse, f.purse(), refused));
            helper.assertTrue(lux == 0 && count(c, Items.RED_CARPET) == 4 && f.purse() == purse, "the dear rug is not bought");
            helper.assertTrue(refused >= 1, "and the refusal is booked in the town's prices, to bring the price down: " + refused);
            // Hungry at supper, with nothing in its pack: the bread at three times its worth is bought all the same.
            level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 11001L);
            noFood(f);
            double breadEach = Purchases.priceEach(level, id, bread, f);
            int loaves = count(c, Items.BREAD);
            Meals.tick(f);
            long spent = Purchases.spentTodayForTests(f);
            Kit.log(String.format(Locale.ROOT, "px02 supper: bread at %.2fc (it expects %.2fc); %s; loaves %d -> %d; charged %d hundredths, "
                + "purse %d; bought in the town's prices %d", breadEach, Purchases.expects(f, bread), Meals.line(f), loaves, count(c, Items.BREAD),
                spent, f.purse(), PriceIndex.tallyForTests(id, bread)[1]));
            helper.assertTrue(f.meals().eatenToday() == 1 && f.meals().missedInRow() == 0, "the hungry folk has its supper: " + Meals.line(f));
            helper.assertTrue(count(c, Items.BREAD) == loaves - 1, "a loaf out of the stores");
            helper.assertTrue(spent == Math.round(breadEach * 100) && PriceIndex.tallyForTests(id, bread)[1] >= 1,
                "bought at the dear price: " + spent + " for " + breadEach);
            helper.succeed();
        });
    }

    // ============================================================ cheap, so more

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px03_cheap_treat")
    public static void px03_cheap_treat(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 624000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            f.setJob(StationTask.FARM);
            purse(f, 30);
            Container c = larder(level, f);
            c.setItem(0, new ItemStack(Items.COOKIE, 32));
            c.setChanged();
            Purchases.openForTests(id, true);
            ItemStack cookie = new ItemStack(Items.COOKIE);
            // At four tenths of their worth.
            PriceIndex.setForTests(id, cookie, 0.4);
            int before = count(c, Items.COOKIE);
            String cheap = Market.folkBuys(level, v, f);
            int cheapGot = before - count(c, Items.COOKIE);
            int bargains = PriceIndex.tallyForTests(id, cookie)[3];
            Kit.log(String.format(Locale.ROOT, "px03 cookies at %.2fc (it expects %.2fc): bought %s, %d out of the stores; bargains booked %d",
                Purchases.priceEach(level, id, cookie, f), Purchases.expects(f, cookie), cheap, cheapGot, bargains));
            // At their usual worth, a fresh look (what it paid last time forgotten).
            Purchases.forgetForTests(f);
            PriceIndex.setForTests(id, cookie, 1.0);
            before = count(c, Items.COOKIE);
            String usual = Market.folkBuys(level, v, f);
            int usualGot = before - count(c, Items.COOKIE);
            Kit.log(String.format(Locale.ROOT, "px03 cookies at %.2fc (it expects %.2fc): bought %s, %d out of the stores",
                Purchases.priceEach(level, id, cookie, f), Purchases.expects(f, cookie), usual, usualGot));
            helper.assertTrue(cheap != null && cheapGot == 2, "cheap, it has two: " + cheap + " (" + cheapGot + ")");
            helper.assertTrue(bargains >= 1, "and the bargain is booked: " + bargains);
            helper.assertTrue(usual != null && usualGot == 1, "at the usual price, one: " + usual + " (" + usualGot + ")");
            helper.succeed();
        });
    }

    // ============================================================ free, then paid

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px04_free_then_paid")
    public static void px04_free_then_paid(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 626000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        Container[] larder = new Container[1];
        long[] day = new long[1];
        helper.runAtTickTime(10, () -> {
            f.setJob(StationTask.FARM);
            purse(f, 20);
            larder[0] = larder(level, f);
            larder[0].setItem(0, new ItemStack(Items.PUMPKIN_PIE, 16));
            larder[0].setChanged();
            PriceIndex.setForTests(id, new ItemStack(Items.PUMPKIN_PIE), 2.0);
            // No shop yet: supper free out of the stores.
            Purchases.openForTests(id, false);
            day[0] = level.getDayTime() / 24000L + 1;
            level.setDayTime(day[0] * 24000L + 11001L);
            int purse = f.purse(), treasury = Ledger.coins(id), pies = count(larder[0], Items.PUMPKIN_PIE);
            Meals.tick(f);
            Kit.log("px04 before the shop: " + Meals.line(f) + "; pies " + pies + " -> " + count(larder[0], Items.PUMPKIN_PIE) + "; purse "
                + purse + " -> " + f.purse() + ", treasury " + treasury + " -> " + Ledger.coins(id) + "; " + Purchases.cardLine(f));
            helper.assertTrue(f.meals().eatenToday() == 1 && count(larder[0], Items.PUMPKIN_PIE) == pies - 1, "supper out of the stores");
            helper.assertTrue(f.purse() == purse && Ledger.coins(id) == treasury && Purchases.balanceForTests(f) == 0, "free: no coin moved");
        });
        helper.runAtTickTime(40, () -> {
            // The shop open: the same supper, a day on, bought at the town's price.
            Purchases.openForTests(id, true);
            level.setDayTime((day[0] + 1) * 24000L + 11001L);
            noFood(f);
            ItemStack pie = new ItemStack(Items.PUMPKIN_PIE);
            double each = Purchases.priceEach(level, id, pie, f);
            int purse = f.purse(), treasury = Ledger.coins(id), pies = count(larder[0], Items.PUMPKIN_PIE);
            int town = Economy.moneyTodayForTests(id)[4];
            Meals.tick(f);
            int paid = purse - f.purse();
            Kit.log(String.format(Locale.ROOT, "px04 the shop open: %s; pies %d -> %d; a pie %.2fc: purse %d -> %d, treasury %d -> %d, "
                    + "spent in town %d -> %d; the card: %s", Meals.line(f), pies, count(larder[0], Items.PUMPKIN_PIE), each, purse, f.purse(),
                treasury, Ledger.coins(id), town, Economy.moneyTodayForTests(id)[4], Purchases.cardLine(f)));
            helper.assertTrue(f.meals().eatenToday() == 1 && count(larder[0], Items.PUMPKIN_PIE) == pies - 1, "supper, the same pie");
            helper.assertTrue(paid == (int) Math.ceil(each - 1e-9) && paid >= 1, "paid for out of its purse: " + paid + " for " + each);
            helper.assertTrue(Ledger.coins(id) == treasury + paid, "into the treasury: " + treasury + " -> " + Ledger.coins(id));
            helper.assertTrue(Economy.moneyTodayForTests(id)[4] == town + paid, "and in the books as spent in town");
            helper.assertTrue(Purchases.spentTodayForTests(f) == Math.round(each * 100), "to the hundredth, the change on account");
            helper.succeed();
        });
    }

    // ============================================================ on the slate

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px05_slate")
    public static void px05_slate(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 628000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            f.setJob(StationTask.SMITH);
            purse(f, 0);
            Container c = larder(level, f);
            c.setItem(0, new ItemStack(Items.PUMPKIN_PIE, 8));
            c.setChanged();
            PriceIndex.setForTests(id, new ItemStack(Items.PUMPKIN_PIE), 2.0);
            Purchases.openForTests(id, true);
            level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 11001L);
            Meals.tick(f);
            int owed = Purchases.slate(f);
            Kit.log("px05 an empty purse at supper: " + Meals.line(f) + "; purse " + f.purse() + "; on the slate " + owed + "; the card: "
                + Purchases.cardLine(f));
            helper.assertTrue(f.meals().eatenToday() == 1 && f.meals().missedInRow() == 0, "it eats all the same: " + Meals.line(f));
            helper.assertTrue(owed >= 1 && f.purse() == 0, "on the slate: " + owed);
            helper.assertTrue(Purchases.cardLine(f).contains("slate"), "its card says so: " + Purchases.cardLine(f));
            // Payday: the slate paid back first, out of the wage.
            Ledger.addCoins(id, 60);
            int treasury = Ledger.coins(id);
            int paid = Market.payWages(level, v);
            Kit.log("px05 payday: paid " + paid + "; purse " + f.purse() + "; on the slate " + Purchases.slate(f) + "; treasury " + treasury + " -> "
                + Ledger.coins(id));
            helper.assertTrue(paid >= owed, "a smith's wage covers it: " + paid);
            helper.assertTrue(Purchases.slate(f) == 0, "the slate is clean: " + Purchases.slate(f));
            helper.assertTrue(f.purse() == paid - owed, "the rest of the wage is its own: " + f.purse());
            helper.assertTrue(Ledger.coins(id) == treasury - paid + owed, "and what it owed is back in the treasury");
            helper.succeed();
        });
    }

    // ============================================================ the bundle bug

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px06_one_not_a_lot")
    public static void px06_one_not_a_lot(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 630000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, 630002, Z), 0.0F);
        helper.assertTrue(keeper != null && id.equals(keeper.ownerId()), "a shopkeeper");
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            f.setJob(StationTask.FARM);
            keeper.setJob(StationTask.SHOP);
            purse(f, 300);
            Container c = larder(level, f);
            c.setItem(0, new ItemStack(Items.RED_CARPET, 4));
            c.setChanged();
            Purchases.openForTests(id, true);
            ItemStack rug = new ItemStack(Items.RED_CARPET);
            PriceIndex.setForTests(id, rug, 1.2);
            Market.Good rugs = Market.goodFor(rug);
            int lot = Market.sellPrice(level, id, rugs, rug, false);
            double each = Purchases.priceEach(level, id, rug, f);
            int purse = f.purse();
            String got = Cafe.folkShops(level, v, f, false, s -> s.is(Items.RED_CARPET));
            long charged = Purchases.spentTodayForTests(f);
            Kit.log(String.format(Locale.ROOT, "px06 a lot of %d rugs %dc; one at %.2fc: bought %s; charged %d hundredths, purse %d -> %d, "
                + "rugs left %d", rugs.bundle(), lot, each, got, charged, purse, f.purse(), count(c, Items.RED_CARPET)));
            helper.assertTrue(got != null && count(c, Items.RED_CARPET) == 3, "one rug bought: " + got);
            helper.assertTrue(charged == Math.round(each * 100), "at one rug's price: " + charged + " for " + each);
            helper.assertTrue(charged * 2 < lot * 100L, "not the lot's: " + charged + " against " + lot * 100);
            helper.succeed();
        });
    }
}
