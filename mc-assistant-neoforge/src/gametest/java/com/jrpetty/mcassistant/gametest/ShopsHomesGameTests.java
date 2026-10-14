package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bank;
import com.jrpetty.mcassistant.entity.Belongings;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.Goods;
import com.jrpetty.mcassistant.entity.HomeStores;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Meals;
import com.jrpetty.mcassistant.entity.Needs;
import com.jrpetty.mcassistant.entity.ServicePoints;
import com.jrpetty.mcassistant.entity.ServiceQueue;
import com.jrpetty.mcassistant.entity.ServiceRole;
import com.jrpetty.mcassistant.entity.ShopDesk;
import com.jrpetty.mcassistant.entity.ShopStock;
import com.jrpetty.mcassistant.entity.ShoppingTrips;
import com.jrpetty.mcassistant.entity.Store;
import com.jrpetty.mcassistant.entity.StoreDeliveries;
import com.jrpetty.mcassistant.entity.StoreFloor;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkStock;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [shops-homes] Shops that make and sell what folk need, through one counter for players and folk alike; homes that
 * keep what their households use; workshops that keep their makings (ServicePoints, ServiceQueue, ShopDesk, BankDesk,
 * TavernDesk, MakerDesk, Needs, ShoppingTrips, HomeStores, Belongings, WorkStock).
 * <ul>
 * <li><b>sk01</b>: a shop's service counter is fitted in place of its first cask, out of the stores' planks, and is
 *     found as the shop's point with a queue in front and a place behind.</li>
 * <li><b>sk02</b>: a player buys a basket at the counter: the coin and the goods move exactly, as the screen priced it.</li>
 * <li><b>sk03</b>: a player sells to the town at its paying price (four fifths), into its stores.</li>
 * <li><b>sk04</b>: a thing not in is ordered, on the workshop's book, with when to come back.</li>
 * <li><b>sk05</b>: two folk queue at the counter and are served in turn, each buying out of its own purse.</li>
 * <li><b>sk06</b>: a closed shop says when it opens and sells nothing.</li>
 * <li><b>sk07</b>: a request sent twice is answered once: nothing bought twice.</li>
 * <li><b>sk08</b>: the bank's desk: a deposit moves coin from the pack into the account.</li>
 * <li><b>sk09</b>: the tavern's bar: a drink bought out of the stores at the town's price.</li>
 * <li><b>sk10</b>: the smithy's anvil: a worn pick mended with the stores' iron, paid for.</li>
 * <li><b>sk11</b>: a folk's shopping list is its needs, by its means; and the shop's range keeps what is on them.</li>
 * <li><b>sk12</b>: a folk buys what it needs with its coin and carries it home: bread into the larder, the rest into
 *     the chest; the coin and the goods conserved end to end.</li>
 * <li><b>sk13</b>: the larder feeds a meal.</li>
 * <li><b>sk14</b>: the shop's counters show its stock (the service counter among them).</li>
 * <li><b>sk15</b>: ten days of a town: every lived-in home's storage holds more, and more kinds of things, than at
 *     founding, and none is empty; the town's stores never reach into a household's named chest.</li>
 * <li><b>sk16</b>: a smithy's chest is kept stocked with its makings by delivery from the stores, and the smith works
 *     from it.</li>
 * </ul>
 *
 * <p>Each on its own ground, x 1,860,000 to 1,879,999 at z 66,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class ShopsHomesGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    private record Town(UUID id, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, VillageFolkEntity keeper,
                        List<VillageFolkEntity> folk, BlockPos shopAt) {}

    /** A town in the Iron Age: a shopkeeper and so many more, its stores a storehouse (with planks for a counter), its shop
     *  built out of the store area with its service counter fitted, the keeper behind it. */
    private static Town town(GameTestHelper helper, int x, int others, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        level.setDayTime(time);
        Kit.hold(level, x, Z, 72);
        Kit.prepare(level, x, Z, 72);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID id = keeper.ownerId();
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < others; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + (i % 6)).east(i / 6), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i);
            f.setJob(StationTask.NONE);
            folk.add(f);
        }
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, id);
        Villages.ageForTests(id, Villages.Age.IRON);
        keeper.setJob(StationTask.SHOP);
        fill(id, store, new ItemStack(Items.OAK_PLANKS, 64));
        BlockPos shopAt = Kit.surface(level, x - 40, Z - 20);
        BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "shop", shopAt, Direction.NORTH);
        Villages.builtAtForTests(id, "shop", shopAt);
        Villages.Village v = Villages.get(id);
        ServicePoints.fitOutForTests(level, v);
        ServicePoints.Point point = point(level, id, ServiceRole.SHOP);
        if (point != null && point.stand() != null) keeper.teleportTo(point.stand().getX() + 0.5, point.stand().getY(), point.stand().getZ() + 0.5);
        return new Town(id, v, heart, store, keeper, folk, shopAt);
    }

    private static ServicePoints.Point point(ServerLevel level, UUID id, ServiceRole role) {
        List<ServicePoints.Point> all = ServicePoints.of(level, id, role);
        return all.isEmpty() ? null : all.get(0);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) level.setBlock(origin.offset(x, y, z), unit, 3);
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands");
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    private static void onlyTheStorehouse(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    /** The storehouse holds these (on top of what it has). */
    private static void fill(UUID id, StorehouseBlockEntity store, ItemStack... goods) {
        for (ItemStack g : goods) {
            ItemStack left = g.copy();
            for (int i = 0; i < store.getContainerSize() && !left.isEmpty(); i++) {
                if (store.getItem(i).isEmpty()) {
                    store.setItem(i, left.copy());
                    left = ItemStack.EMPTY;
                }
            }
        }
        store.setChanged();
        Villages.forgetStock();
        Villages.forgetStores(id);
        com.jrpetty.mcassistant.entity.Budget.forget(id);
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    private static int carried(ServerPlayer p, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    /** A request from the open screen, numbered as the screen numbers them. */
    private static void press(ServerPlayer p, ServiceRole role, String action, CompoundTag args) {
        ServicePoints.act(p, role.id, action, args, ServicePoints.nextNonceForTests(p));
    }

    private static CompoundTag basket(String id, int n) {
        CompoundTag b = new CompoundTag();
        b.putInt(id, n);
        CompoundTag a = new CompoundTag();
        a.put("basket", b);
        return a;
    }

    /** A house stamped down and on the village's books. */
    private static BlockPos house(ServerLevel level, UUID village, BlockPos at) {
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "house", at, Direction.NORTH);
        return at;
    }

    /** Every coin there is: the treasury, every purse, and these players' packs. */
    private static long coin(UUID id, ServerPlayer... players) {
        long n = Ledger.coins(id);
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) n += f.purse();
        for (ServerPlayer p : players) n += Market.coinsHeld(p);
        return n;
    }

    // ============================================================ the counter

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk01_counter_fitted")
    public static void sk01_counter_fitted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1860500, 0, 2000L);
        ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
        int planks = Market.stock(level, t.id(), s -> s.is(Items.OAK_PLANKS));
        Kit.log("sk01 the shop's point: " + (p == null ? "none" : p.pos().toShortString() + " facing " + p.front() + ", keeper stands at "
            + p.stand() + "; queue " + p.queueSpot(level, 0).toShortString()) + "; planks left " + planks
            + "; at it: " + (p == null ? "-" : level.getBlockState(p.pos())));
        helper.assertTrue(p != null, "the shop has its service counter");
        helper.assertTrue(level.getBlockState(p.pos()).is(McAssistantMod.SERVICE_COUNTER.get()), "a service counter block");
        helper.assertTrue(ServicePoints.at(level, p.pos()) != null && ServicePoints.at(level, p.pos()).role() == ServiceRole.SHOP, "found as the shop's point");
        helper.assertTrue(planks == 60, "four planks of the stores went into it: " + planks);
        helper.assertTrue(p.stand() != null, "a place behind it for the keeper");
        helper.assertTrue(level.getBlockState(p.queueSpot(level, 0)).isAir(), "room in front of it to be served");
        helper.assertTrue(ServicePoints.status(level, t.id(), ServiceRole.SHOP, p).open(), "open, with its keeper in: "
            + ServicePoints.status(level, t.id(), ServiceRole.SHOP, p).words());
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk02_player_buys")
    public static void sk02_player_buys(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1861700, 0, 2000L);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            helper.assertTrue(p != null, "a counter");
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 32));
            Predicate<ItemStack> bread = s -> s.is(Items.BREAD);
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 64);
            ServicePoints.openAt(pl, p.pos());
            String id = Goods.lineId(new ItemStack(Items.BREAD));
            press(pl, ServiceRole.SHOP, "basket", basket(id, 5));
            CompoundTag screen = ServicePoints.screenForTests(pl);
            helper.assertTrue(screen != null, "the screen is open");
            int total = screen.getCompound("body").getCompound("quote").getInt("total");
            int coins0 = Market.coinsHeld(pl), treasury0 = Ledger.coins(t.id()), shop0 = ShopStock.held(level, t.id(), bread), had0 = carried(pl, bread);
            press(pl, ServiceRole.SHOP, "buy", new CompoundTag());
            int coins1 = Market.coinsHeld(pl), treasury1 = Ledger.coins(t.id()), shop1 = ShopStock.held(level, t.id(), bread), had1 = carried(pl, bread);
            Kit.log("sk02 basket of 5 bread priced " + total + "; coins " + coins0 + " -> " + coins1 + ", treasury " + treasury0 + " -> " + treasury1
                + ", shop " + shop0 + " -> " + shop1 + ", pack " + had0 + " -> " + had1 + "; said: " + ServicePoints.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(total > 0, "the basket is priced by the server: " + total);
            helper.assertTrue(coins0 - coins1 == total && treasury1 - treasury0 == total, "the coin moved exactly: " + (coins0 - coins1) + " paid, "
                + (treasury1 - treasury0) + " in, " + total + " asked");
            helper.assertTrue(shop0 - shop1 == 5 && had1 - had0 == 5, "five loaves out of the shop's stock into the pack");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk03_player_sells")
    public static void sk03_player_sells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1862900, 0, 2000L);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 0);
            pl.getInventory().add(new ItemStack(Items.WHEAT, 32));
            Ledger.addCoins(t.id(), 100);
            ServicePoints.openAt(pl, p.pos());
            ItemStack wheat = new ItemStack(Items.WHEAT);
            int lot = ShopDesk.sellLotForTests(wheat);
            int bid = Market.bidLot(level, t.id(), wheat, lot), ask = Market.askLot(level, t.id(), wheat, lot);
            Predicate<ItemStack> isWheat = s -> s.is(Items.WHEAT);
            int stores0 = Market.stock(level, t.id(), isWheat), treasury0 = Ledger.coins(t.id());
            CompoundTag args = new CompoundTag();
            args.putString("id", Goods.lineId(wheat));
            args.putInt("lots", 1);
            press(pl, ServiceRole.SHOP, "sell", args);
            int coins = Market.coinsHeld(pl), stores1 = Market.stock(level, t.id(), isWheat), left = carried(pl, isWheat);
            Kit.log("sk03 a lot of " + lot + " wheat: the town bids " + bid + " against asking " + ask + "; paid " + coins + ", treasury "
                + treasury0 + " -> " + Ledger.coins(t.id()) + ", stores " + stores0 + " -> " + stores1 + ", left " + left + "; said "
                + ServicePoints.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(bid > 0 && coins == bid && treasury0 - Ledger.coins(t.id()) == bid, "paid the town's bid out of the treasury: " + coins);
            helper.assertTrue(bid < ask && bid * 100 <= ask * 85 + 100, "at its paying price, four fifths, under what it asks: " + bid + " vs " + ask);
            helper.assertTrue(stores1 - stores0 == lot && left == 32 - lot, "the lot into the stores, out of the pack");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk04_out_of_stock_ordered")
    public static void sk04_out_of_stock_ordered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1864100, 0, 2000L);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 10);
            ServicePoints.openAt(pl, p.pos());
            List<String> lines = ShopDesk.linesForTests(level, t.v(), ServiceRole.SHOP);
            String lantern = Goods.lineId(new ItemStack(Items.LANTERN));
            CompoundTag args = new CompoundTag();
            args.putString("id", lantern);
            press(pl, ServiceRole.SHOP, "order", args);
            String said = ServicePoints.messageForTests(pl);
            Kit.log("sk04 the list: " + lines + "; ordered a lantern: " + said);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(lines.contains(lantern + "=0+order"), "a lantern on the list, none in, to order: " + lines);
            helper.assertTrue(said.startsWith("On the workshop's book") && said.contains("Ready in"), "on the workshop's book, with when: " + said);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "sk05_folk_queue")
    public static void sk05_folk_queue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1865300, 2, 2000L);
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
        helper.assertTrue(p != null, "a counter");
        Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 16));
        BlockPos near = p.queueSpot(level, 2);
        a.teleportTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
        b.teleportTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
        a.earn(20);
        b.earn(20);
        long[] served = { -1, -1 };
        int[] secondInLine = { 0 };
        boolean[] bought = { false, false };
        int[] purse0 = { a.purse(), b.purse() };
        int shop0 = ShopStock.held(level, t.id(), s -> s.is(Items.BREAD));
        long start = helper.getTick();
        helper.onEachTick(() -> {
            if (p.stand() != null) t.keeper().teleportTo(p.stand().getX() + 0.5, p.stand().getY(), p.stand().getZ() + 0.5);
            VillageFolkEntity[] who = { a, b };
            // Each kept to its place in the line (the walking is the folk's own; here only the line and the turns are on trial).
            List<UUID> inLine = ServiceQueue.lineAt(p.pos());
            for (int i = 0; i < 2; i++) {
                int at = inLine.indexOf(who[i].getUUID());
                if (at < 0 || bought[i]) continue;
                BlockPos spot = p.queueSpot(level, Math.min(at, 4));
                if (who[i].distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > 1.0) who[i].teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            }
            for (int i = 0; i < 2; i++) {
                if (bought[i]) continue;
                ServiceQueue.Turn turn = ServiceQueue.turn(who[i], level, ServiceRole.SHOP, "a loaf of bread");
                if (turn == ServiceQueue.Turn.GO) {
                    served[i] = helper.getTick() - start;
                    bought[i] = ShopDesk.folkBuy(level, t.v(), who[i], new ItemStack(Items.BREAD), 1) != null;
                }
            }
            List<UUID> line = ServiceQueue.lineAt(p.pos());
            if (line.size() == 2) secondInLine[0]++;
            if (bought[0] && bought[1]) {
                int shop1 = ShopStock.held(level, t.id(), s -> s.is(Items.BREAD));
                Kit.log("sk05 served at " + served[0] + " and " + served[1] + " ticks; two in line for " + secondInLine[0] + " ticks; purses "
                    + purse0[0] + " -> " + a.purse() + ", " + purse0[1] + " -> " + b.purse() + "; shop " + shop0 + " -> " + shop1);
                helper.assertTrue(Math.min(served[0], served[1]) >= 60, "served a few seconds, not at once: " + served[0] + ", " + served[1]);
                helper.assertTrue(Math.abs(served[0] - served[1]) >= 60, "one after the other: " + served[0] + ", " + served[1]);
                helper.assertTrue(secondInLine[0] > 20, "the second waited its turn in line");
                helper.assertTrue(a.purse() < purse0[0] && b.purse() < purse0[1] && shop0 - shop1 == 2, "each paid out of its own purse, two loaves sold");
                helper.succeed();
            }
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk06_closed_refuses")
    public static void sk06_closed_refuses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1866500, 0, 15000L);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 8));
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 20);
            ServicePoints.openAt(pl, p.pos());
            press(pl, ServiceRole.SHOP, "basket", basket(Goods.lineId(new ItemStack(Items.BREAD)), 2));
            press(pl, ServiceRole.SHOP, "buy", new CompoundTag());
            CompoundTag screen = ServicePoints.screenForTests(pl);
            String said = ServicePoints.messageForTests(pl);
            Kit.log("sk06 at 9pm: open " + screen.getBoolean("open") + ", '" + screen.getString("status") + "'; buying said '" + said + "'; coins "
                + Market.coinsHeld(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(!screen.getBoolean("open") && screen.getString("status").contains("Opens at 7am"), "closed, and says when it opens");
            helper.assertTrue(said.startsWith("Closed") && Market.coinsHeld(pl) == 20 && carried(pl, s -> s.is(Items.BREAD)) == 0, "nothing sold");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk07_no_double_buy")
    public static void sk07_no_double_buy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1867700, 0, 2000L);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 32));
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 64);
            ServicePoints.openAt(pl, p.pos());
            press(pl, ServiceRole.SHOP, "basket", basket(Goods.lineId(new ItemStack(Items.BREAD)), 3));
            int nonce = ServicePoints.nextNonceForTests(pl);
            ServicePoints.act(pl, "shop", "buy", new CompoundTag(), nonce);
            int coins1 = Market.coinsHeld(pl), bread1 = carried(pl, s -> s.is(Items.BREAD));
            ServicePoints.act(pl, "shop", "buy", new CompoundTag(), nonce);           // the same click again (lag, a double click)
            ServicePoints.act(pl, "shop", "buy", new CompoundTag(), nonce - 1);       // and an older one
            int coins2 = Market.coinsHeld(pl), bread2 = carried(pl, s -> s.is(Items.BREAD));
            press(pl, ServiceRole.SHOP, "buy", new CompoundTag());                     // a fresh one: the basket is empty now
            String said = ServicePoints.messageForTests(pl);
            Kit.log("sk07 after one buy: " + coins1 + " coins, " + bread1 + " bread; after the repeats " + coins2 + ", " + bread2 + "; again: " + said);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(bread1 == 3 && coins2 == coins1 && bread2 == bread1, "the repeats bought nothing");
            helper.assertTrue(said.contains("basket is empty"), "a new request with an empty basket buys nothing: " + said);
            helper.succeed();
        });
    }

    // ============================================================ the other trades

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk08_bank_desk")
    public static void sk08_bank_desk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1868900, 1, 2000L);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity banker = t.folk().get(0);
            banker.setJob(StationTask.BANK);
            BlockPos bankAt = Kit.surface(level, t.heart().getX() + 26, t.heart().getZ() - 26);
            BuildGoal.stamp(level, "bank", bankAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(t.id(), "bank", bankAt, Direction.NORTH);
            boolean open = Bank.openForTests(level, t.v());
            ServicePoints.resetForTests();
            ServicePoints.Point p = point(level, t.id(), ServiceRole.BANK);
            helper.assertTrue(open && p != null, "the bank is open with its desk: " + open + ", " + p);
            banker.teleportTo(p.pos().getX() + 0.5, p.pos().getY(), p.pos().getZ() + 1.5);
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 30);
            ServicePoints.openAt(pl, p.pos());
            CompoundTag args = new CompoundTag();
            args.putInt("amount", 10);
            press(pl, ServiceRole.BANK, "deposit", args);
            int balance = Bank.balanceForTests(t.id(), pl.getUUID());
            Kit.log("sk08 deposited: balance " + balance + ", coins " + Market.coinsHeld(pl) + "; said " + ServicePoints.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(balance == 10 && Market.coinsHeld(pl) == 20, "ten coins from the pack into the account");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk09_tavern_bar")
    public static void sk09_tavern_bar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1870100, 1, 12000L);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity cook = t.folk().get(0);
            cook.setJob(StationTask.COOK);
            BlockPos at = Kit.surface(level, t.heart().getX() + 26, t.heart().getZ() - 26);
            BuildGoal.stamp(level, "tavern", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(t.id(), "tavern", at, Direction.NORTH);
            ItemStack cider = Cafe.drink(Cafe.DRINKS.get(0));
            fill(t.id(), t.store(), new ItemStack(Items.OAK_PLANKS, 16), cider.copyWithCount(6));
            ServicePoints.fitOutForTests(level, t.v());
            ServicePoints.Point p = point(level, t.id(), ServiceRole.TAVERN);
            helper.assertTrue(p != null, "the tavern's bar has its counter");
            cook.teleportTo(p.pos().getX() + 0.5, p.pos().getY(), p.pos().getZ() + 1.5);
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 20);
            ServicePoints.openAt(pl, p.pos());
            CompoundTag args = new CompoundTag();
            args.putString("id", Goods.lineId(cider));
            int treasury0 = Ledger.coins(t.id());
            press(pl, ServiceRole.TAVERN, "drink", args);
            int paid = 20 - Market.coinsHeld(pl);
            Kit.log("sk09 a drink at the bar: paid " + paid + ", treasury +" + (Ledger.coins(t.id()) - treasury0) + "; said "
                + ServicePoints.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(carried(pl, s -> ItemStack.isSameItemSameComponents(s, cider)) == 1, "the drink in the pack");
            helper.assertTrue(paid > 0 && Ledger.coins(t.id()) - treasury0 == paid, "paid into the treasury");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk10_smith_mends")
    public static void sk10_smith_mends(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1871300, 1, 2000L);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity smith = t.folk().get(0);
            smith.setJob(StationTask.SMITH);
            BlockPos at = Kit.surface(level, t.heart().getX() + 26, t.heart().getZ() - 26);
            BuildGoal.stamp(level, "smithy", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(t.id(), "smithy", at, Direction.NORTH);
            fill(t.id(), t.store(), new ItemStack(Items.IRON_INGOT, 8));
            ServicePoints.resetForTests();
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SMITHY);
            helper.assertTrue(p != null, "the smithy's anvil is its point");
            smith.teleportTo(p.pos().getX() + 0.5, p.pos().getY(), p.pos().getZ() + 1.5);
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 30);
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
            pick.setDamageValue(pick.getMaxDamage() / 2);
            pl.getInventory().setItem(5, pick);
            ServicePoints.openAt(pl, p.pos());
            int worn0 = pl.getInventory().getItem(5).getDamageValue();
            CompoundTag args = new CompoundTag();
            args.putInt("slot", 5);
            args.putString("id", Goods.lineId(pl.getInventory().getItem(5)));
            press(pl, ServiceRole.SMITHY, "mend", args);
            int worn1 = pl.getInventory().getItem(5).getDamageValue();
            Kit.log("sk10 the pick worn " + worn0 + " -> " + worn1 + "; coins " + Market.coinsHeld(pl) + "; said " + ServicePoints.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(worn1 < worn0 && Market.coinsHeld(pl) < 30, "mended, and paid for");
            helper.succeed();
        });
    }

    // ============================================================ needs, shopping, homes

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk11_needs_and_range")
    public static void sk11_needs_and_range(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1872500, 1, 2000L);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = t.folk().get(0);
            BlockPos home = house(level, t.id(), Kit.surface(level, t.heart().getX() - 20, t.heart().getZ() + 20));
            Homes.tickForTests(level, t.v());
            f.earn(300);
            f.setJob(StationTask.MINE);
            f.removeMatching(s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem, 64);
            List<String> list = Needs.listForTests(level, f);
            f.spend(f.purse());
            List<String> poor = Needs.listForTests(level, f);
            List<String> range = ShopDesk.linesForTests(level, t.v(), ServiceRole.SHOP);
            Kit.log("sk11 in " + Homes.membersForTests(t.id(), home) + ": a well-off miner wants " + list + "; with nothing in its purse " + poor
                + "; the shop's list " + range);
            boolean food = false, tool = false, candles = false;
            for (String s : list) {
                if (s.startsWith("bread")) food = true;
                if (s.startsWith("stone_pickaxe")) tool = true;
                if (s.startsWith("candle")) candles = true;
            }
            helper.assertTrue(food && tool && candles, "food for the larder, the tool of its trade, candles: " + list);
            for (String s : poor) helper.assertTrue(!s.startsWith("book") && !s.startsWith("lute") && !s.startsWith("pet_treat") && !s.startsWith("chest"),
                "with nothing in its purse, no comforts: " + poor);
            helper.assertTrue(poor.size() <= list.size(), "and no more than it wanted when well off");
            boolean candleOnRange = false;
            for (String r : range) if (r.startsWith("minecraft:candle")) candleOnRange = true;
            helper.assertTrue(candleOnRange, "the shop keeps what the lists ask for: " + range);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "sk12_buys_and_carries_home")
    public static void sk12_buys_and_carries_home(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1873700, 1, 4000L);
        VillageFolkEntity f = t.folk().get(0);
        BlockPos home = house(level, t.id(), Kit.surface(level, t.shopAt().getX() + 12, t.shopAt().getZ()));
        Homes.tickForTests(level, t.v());
        HomeStores.claimForTests(level, t.v());
        Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 32));
        Store.putForTests(level, t.id(), new ItemStack(Items.CANDLE, 8));
        Store.putForTests(level, t.id(), new ItemStack(Items.BARREL, 2));
        f.earn(200);
        Predicate<ItemStack> bread = s -> s.is(Items.BREAD), candle = s -> s.is(Items.CANDLE);
        long coin0 = coin(t.id());
        int breadAll0 = ShopStock.held(level, t.id(), bread) + f.countCarried(bread) + Market.stock(level, t.id(), bread);
        int purse0 = f.purse();
        f.teleportTo(home.getX() + 0.5, home.getY() + 1, home.getZ() + 0.5);
        ShoppingTrips.againForTests(f);
        boolean[] left = { false };
        helper.onEachTick(() -> {
            if (t.keeper().isAlive()) {
                ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
                if (p != null && p.stand() != null) t.keeper().teleportTo(p.stand().getX() + 0.5, p.stand().getY(), p.stand().getZ() + 0.5);
            }
            boolean busy = ShoppingTrips.errand(f, level);
            if (busy) left[0] = true;
            if (!left[0] || busy || !ShoppingTrips.stageForTests(f).equals("none")) return;
            Belongings.resetForTests();
            Belongings.View view = Belongings.ofHome(level, t.id(), home);
            int atHome = 0, candles = 0;
            String larder = "";
            for (Belongings.Thing th : view.things()) {
                if (th.stack().is(Items.BREAD)) { atHome += th.stack().getCount(); if (th.where().equals("Larder")) larder = th.where(); }
                if (th.stack().is(Items.CANDLE)) candles += th.stack().getCount();
            }
            int breadAll1 = ShopStock.held(level, t.id(), bread) + f.countCarried(bread) + Market.stock(level, t.id(), bread) + atHome;
            long coin1 = coin(t.id());
            Kit.log("sk12 the trip: purse " + purse0 + " -> " + f.purse() + "; at home " + view.summary() + " (boxes "
                + HomeStores.boxesForTests(level, t.id(), home) + "); bread everywhere " + breadAll0 + " -> " + breadAll1 + "; coin " + coin0 + " -> " + coin1
                + "; card: " + Needs.cardLine(f));
            helper.assertTrue(f.purse() < purse0, "paid out of its own purse");
            helper.assertTrue(atHome > 0 && candles > 0, "the bread and the candles are at home: " + view.summary());
            helper.assertTrue(larder.equals("Larder"), "the bread in the larder it bought and set down: " + HomeStores.boxesForTests(level, t.id(), home));
            helper.assertTrue(breadAll1 == breadAll0 && coin1 == coin0, "nothing made or lost: bread " + breadAll0 + " -> " + breadAll1 + ", coin " + coin0 + " -> " + coin1);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk13_larder_feeds")
    public static void sk13_larder_feeds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1874900, 1, 2000L);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = t.folk().get(0);
            BlockPos home = house(level, t.id(), Kit.surface(level, t.heart().getX() - 20, t.heart().getZ() + 20));
            Homes.tickForTests(level, t.v());
            // A barrel inside, the household's larder, with its bread.
            BlockPos chest = com.jrpetty.mcassistant.entity.Families.homeChestForTests(level, f);
            helper.assertTrue(chest != null, "a house with its chest");
            BlockPos spot = null;
            for (BlockPos q : BlockPos.betweenClosed(home.offset(-2, 0, -2), home.offset(2, 2, 2))) {
                if (level.getBlockState(q).isAir() && level.getBlockState(q.below()).isSolidRender(level, q.below()) && !q.equals(chest)) { spot = q.immutable(); break; }
            }
            helper.assertTrue(spot != null, "room for a larder");
            level.setBlock(spot, Blocks.BARREL.defaultBlockState(), 3);
            ((Container) level.getBlockEntity(spot)).setItem(0, new ItemStack(Items.BREAD, 4));
            HomeStores.claimForTests(level, t.v());
            f.removeMatching(s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null, 999);
            f.teleportTo(home.getX() + 0.5, home.getY() + 1, home.getZ() + 0.5);
            boolean ate = Meals.eatForTests(level, f);
            int left = ((Container) level.getBlockEntity(spot)).getItem(0).getCount();
            String name = level.getBlockEntity(spot) instanceof Nameable n && n.getCustomName() != null ? n.getCustomName().getString() : "";
            Kit.log("sk13 the larder '" + name + "': ate " + ate + ", bread left " + left + "; boxes " + HomeStores.boxesForTests(level, t.id(), home));
            helper.assertTrue(name.equals("Larder"), "the barrel is the household's larder, named");
            helper.assertTrue(ate && left == 3, "a meal out of the larder");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "sk14_display_shows_stock")
    public static void sk14_display_shows_stock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1876100, 0, 2000L);
        Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 16));
        Store.putForTests(level, t.id(), new ItemStack(Items.LANTERN, 4));
        Store.putForTests(level, t.id(), new ItemStack(Items.CANDLE, 6));
        final long from = helper.getTick();
        helper.onEachTick(() -> {
            long waited = helper.getTick() - from;
            if (waited % 20 == 1) StoreFloor.dress(level, t.v());
            if (waited < 21) return;
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            List<String> shown = new ArrayList<>();
            boolean onCounter = false;
            for (ItemFrame fr : level.getEntitiesOfClass(ItemFrame.class, new AABB(t.shopAt()).inflate(8))) {
                if (!fr.getTags().contains(StoreFloor.FRAME_TAG) || fr.getItem().isEmpty()) continue;
                shown.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(fr.getItem().getItem()).getPath());
                if (p != null && fr.blockPosition().equals(p.pos().above())) onCounter = true;
            }
            if (shown.size() < 3 && waited < 200) return;
            Kit.log("sk14 on the counters: " + shown + "; on the service counter: " + onCounter);
            helper.assertTrue(shown.contains("bread") && shown.contains("lantern") && shown.contains("candle"), "the stock is on show: " + shown);
            helper.assertTrue(onCounter, "the service counter shows a ware too");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "sk15_ten_days_of_homes")
    public static void sk15_ten_days_of_homes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1877300, 3, 2000L);
        helper.runAtTickTime(5, () -> {
            List<BlockPos> homes = new ArrayList<>();
            for (int i = 0; i < 3; i++) homes.add(house(level, t.id(), Kit.surface(level, t.heart().getX() - 30 + 14 * i, t.heart().getZ() + 24)));
            Homes.tickForTests(level, t.v());
            HomeStores.claimForTests(level, t.v());
            int[] tiers = { 15, 60, 250 };
            for (int i = 0; i < 3; i++) t.folk().get(i).earn(tiers[i]);
            List<int[]> before = new ArrayList<>();
            for (BlockPos h : homes) {
                Belongings.resetForTests();
                Belongings.View v = Belongings.ofHome(level, t.id(), h);
                before.add(new int[]{ v.total(), v.kinds() });
            }
            // The stores the shop can draw on for ten days of shopping, and a shop of its own.
            fill(t.id(), t.store(), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.CANDLE, 16),
                new ItemStack(Items.BARREL, 4), new ItemStack(Items.CHEST, 4), new ItemStack(Items.BOOK, 8), new ItemStack(Items.LEATHER_BOOTS, 3));
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 64));
            Store.putForTests(level, t.id(), new ItemStack(Items.CANDLE, 16));
            Store.putForTests(level, t.id(), new ItemStack(Items.BARREL, 4));
            List<String> days = new ArrayList<>();
            for (int d = 1; d <= 10; d++) {
                level.setDayTime(24000L * d + 3000L);
                for (VillageFolkEntity f : t.folk()) {
                    f.earn(6);                                                   // a day's wage
                    List<String> got = ShoppingTrips.tripNowForTests(level, f);
                    if (!got.isEmpty()) days.add("d" + d + " " + f.displayNameCap() + ": " + got);
                    Meals.eatForTests(level, f);                                  // and a meal a day out of the larder
                }
            }
            Belongings.resetForTests();
            List<String> after = new ArrayList<>();
            boolean grew = true, noneEmpty = true;
            int lived = 0;
            for (int i = 0; i < homes.size(); i++) {
                if (Homes.membersForTests(t.id(), homes.get(i)).isEmpty()) continue;           // nobody's home: no household's things
                lived++;
                Belongings.View v = Belongings.ofHome(level, t.id(), homes.get(i));
                after.add(v.total() + " things of " + v.kinds() + " kinds (from " + before.get(i)[0] + " of " + before.get(i)[1] + "): " + v.summary());
                if (v.total() <= before.get(i)[0] || v.kinds() <= before.get(i)[1]) grew = false;
                if (v.total() == 0) noneEmpty = false;
            }
            boolean storesReach = false;
            for (BlockPos p : Villages.storeChests(level, t.id())) for (BlockPos h : homes) if (p.closerThan(h, 6)) storesReach = true;
            Kit.log("sk15 ten days: " + days + " | homes: " + after);
            helper.assertTrue(lived >= 2, "households in the houses: " + lived);
            helper.assertTrue(noneEmpty, "no lived-in home's storage is empty: " + after);
            helper.assertTrue(grew, "every home holds more and more kinds of things than at founding: " + after);
            helper.assertTrue(!storesReach, "the town's stores never count a household's chest");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sk16_smithy_makings")
    public static void sk16_smithy_makings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1878500, 1, 2000L);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity smith = t.folk().get(0);
            smith.setJob(StationTask.SMITH);
            BlockPos at = Kit.surface(level, t.heart().getX() + 26, t.heart().getZ() - 26);
            BuildGoal.stamp(level, "smithy", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(t.id(), "smithy", at, Direction.NORTH);
            fill(t.id(), t.store(), new ItemStack(Items.IRON_INGOT, 48), new ItemStack(Items.COAL, 32), new ItemStack(Items.STICK, 32));
            Predicate<ItemStack> iron = s -> s.is(Items.IRON_INGOT);
            int storesIron0 = Market.stock(level, t.id(), iron);
            BlockPos chest = WorkStock.roundForTests(level, t.v(), StationTask.SMITH);
            helper.assertTrue(chest != null, "the smithy has its chest");
            List<String> queued = StoreDeliveries.listForTests(t.id());
            StoreDeliveries.deliverAllNow(level, t.v());
            Container c = (Container) level.getBlockEntity(chest);
            int ironIn = 0, coalIn = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(Items.IRON_INGOT)) ironIn += c.getItem(i).getCount();
                if (c.getItem(i).is(Items.COAL)) coalIn += c.getItem(i).getCount();
            }
            int storesIron1 = Market.stock(level, t.id(), iron);
            String name = level.getBlockEntity(chest) instanceof Nameable n && n.getCustomName() != null ? n.getCustomName().getString() : "";
            boolean inStores = Villages.storeChests(level, t.id()).contains(chest);
            smith.teleportTo(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
            int made = 0;
            for (int i = 0; i < 6; i++) if (Crafts.now(smith, level, t.v())) made++;
            int ironAfter = 0;
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.IRON_INGOT)) ironAfter += c.getItem(i).getCount();
            int storesIron2 = Market.stock(level, t.id(), iron);
            Kit.log("sk16 queued " + queued + "; the chest '" + name + "' holds " + ironIn + " iron, " + coalIn + " coal; the stores' iron " + storesIron0
                + " -> " + storesIron1 + " -> " + storesIron2 + "; the smith made " + made + " pieces, the chest's iron " + ironIn + " -> " + ironAfter);
            helper.assertTrue(name.equals("Smithy: makings") && !inStores, "the chest is the smithy's, not the stores'");
            helper.assertTrue(ironIn >= 8 && coalIn >= 8, "its makings delivered: " + ironIn + " iron, " + coalIn + " coal");
            helper.assertTrue(storesIron0 - storesIron1 == ironIn, "out of the stores, nothing made: " + storesIron0 + " -> " + storesIron1);
            helper.assertTrue(made > 0 && ironAfter < ironIn, "the smith works from its own chest: " + ironIn + " -> " + ironAfter);
            helper.succeed();
        });
    }
}
