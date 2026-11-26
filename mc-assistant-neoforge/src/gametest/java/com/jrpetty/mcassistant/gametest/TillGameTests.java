package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.FittingBlock;
import com.jrpetty.mcassistant.block.FittingBlockEntity;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.TillBlock;
import com.jrpetty.mcassistant.block.TillBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.Fittings;
import com.jrpetty.mcassistant.entity.Goods;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.ServicePoints;
import com.jrpetty.mcassistant.entity.ServiceRole;
import com.jrpetty.mcassistant.entity.ShopDesk;
import com.jrpetty.mcassistant.entity.ShopStock;
import com.jrpetty.mcassistant.entity.Store;
import com.jrpetty.mcassistant.entity.Tills;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.TillItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
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
 * [till] The till and the checkout (entity/Tills, TillShops, Fittings): a player buys three with + and pays (the stock, the
 * coin and the till all move, exactly); can't afford it, and Pay says why; sold out, greyed; a folk buys at the same till
 * and the till says who it served; a full pack drops the rest at the player's feet; selling back on the second tab;
 * nobody behind the till, so the kiosk serves; a shelf's ware browsed into the basket and sold off the shelf; the staff fill
 * a shelf from the stock; a fit-out brings footfall; and every new block crafts by its recipe. Its own x band: 7,700,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TillGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    private record Town(UUID id, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, VillageFolkEntity keeper,
                        List<VillageFolkEntity> folk, ServicePoints.Point point, BlockPos till) {}

    /** A town of the Iron Age with its shop built, its service counter fitted, a till on it and the keeper behind it. */
    private static Town town(GameTestHelper helper, int x, int others) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Tills.resetForTests();
        Fittings.resetForTests();
        level.setDayTime(2000L);
        Kit.hold(level, x, Z, 72);
        Kit.prepare(level, x, Z, 72);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID id = keeper.ownerId();
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < others; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i);
            f.setJob(StationTask.NONE);
            folk.add(f);
        }
        StorehouseBlockEntity store = storehouse(helper, level, heart);
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) { c.clearContent(); level.removeBlock(p, false); }
        }
        Villages.forgetStores(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        keeper.setJob(StationTask.SHOP);
        store.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        store.setChanged();
        Villages.forgetStock();
        Villages.forgetStores(id);
        BlockPos shopAt = Kit.surface(level, x - 40, Z - 20);
        BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "shop", shopAt, Direction.NORTH);
        Villages.builtAtForTests(id, "shop", shopAt);
        Villages.Village v = Villages.get(id);
        ServicePoints.fitOutForTests(level, v);
        List<ServicePoints.Point> points = ServicePoints.of(level, id, ServiceRole.SHOP);
        helper.assertTrue(!points.isEmpty(), "the shop's counter is fitted");
        ServicePoints.Point point = points.get(0);
        if (point.stand() != null) keeper.teleportTo(point.stand().getX() + 0.5, point.stand().getY(), point.stand().getZ() + 0.5);
        BlockPos till = point.pos().above();
        if (!(level.getBlockEntity(till) instanceof TillBlockEntity)) {
            Kit.log("tl: the town set no till on its counter (its gate refused the cell): set here");
            level.setBlock(till, TillItems.TILL.get().defaultBlockState().setValue(TillBlock.FACING, point.front()), 3);
        }
        Tills.bindForTests(level, till, id, "town:shop", null, false);
        return new Town(id, v, heart, store, keeper, folk, point, till);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart) {
        BlockPos origin = Kit.surface(level, heart.getX() + 8, heart.getZ() + 8);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
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

    private static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.getInventory().clearContent();
        p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    private static int carried(ServerPlayer p, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    private static CompoundTag set(String id, int n) {
        CompoundTag a = new CompoundTag();
        a.putString("id", id);
        a.putInt("n", n);
        return a;
    }

    @javax.annotation.Nullable
    private static CompoundTag line(CompoundTag screen, String id) {
        ListTag items = screen.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) if (items.getCompound(i).getString("id").equals(id)) return items.getCompound(i);
        return null;
    }

    private static final Predicate<ItemStack> BREAD = s -> s.is(Items.BREAD);
    private static final String BREAD_ID = Goods.lineId(new ItemStack(Items.BREAD));

    // ============================================================ a player buys three, with +, and pays

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl01_buy_three")
    public static void tl01_buy_three(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7700100, 0);
        helper.runAtTickTime(5, () -> {
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 32));
            ServerPlayer pl = player(helper, t.point().queueSpot(level, 0), 64);
            Tills.openAt(pl, t.till());
            CompoundTag first = Tills.screenForTests(pl);
            helper.assertTrue(first != null, "the checkout opened");
            helper.assertTrue(first.getBoolean("staffed"), "the keeper is at the till: " + first.getString("status"));
            for (int n = 1; n <= 3; n++) Tills.pressForTests(pl, "set", set(BREAD_ID, n));     // + + +
            CompoundTag screen = Tills.screenForTests(pl);
            int total = screen.getCompound("quote").getInt("total");
            helper.assertTrue(screen.getString("why").isEmpty(), "Pay is ready: " + screen.getString("why"));
            long town0 = CoinCensus.count(t.id());
            int coins0 = Market.coinsHeld(pl), shop0 = ShopStock.held(level, t.id(), BREAD), had0 = carried(pl, BREAD);
            Tills.pressForTests(pl, "pay", new CompoundTag());
            int coins1 = Market.coinsHeld(pl), shop1 = ShopStock.held(level, t.id(), BREAD), had1 = carried(pl, BREAD);
            long town1 = CoinCensus.count(t.id());
            CompoundTag after = Tills.screenForTests(pl);
            String serving = level.getBlockEntity(t.till()) instanceof TillBlockEntity be ? be.serving() : "";
            Kit.log("tl01 3 bread priced " + total + "; coins " + coins0 + " -> " + coins1 + ", the town's coin " + town0 + " -> " + town1
                + ", shop " + shop0 + " -> " + shop1 + ", pack " + had0 + " -> " + had1 + "; said " + Tills.messageForTests(pl) + " / " + after.getString("voice")
                + "; the till: serving " + serving);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(total > 0, "priced by the business: " + total);
            helper.assertTrue(coins0 - coins1 == total && town1 - town0 == total * 100L, "the coin moved exactly: " + (coins0 - coins1) + " of " + total);
            helper.assertTrue(shop0 - shop1 == 3 && had1 - had0 == 3, "three loaves off the shop's stock into the pack");
            helper.assertTrue(after.getCompound("quote").getList("lines", Tag.TAG_COMPOUND).isEmpty(), "the basket emptied");
            helper.assertTrue(!after.getString("voice").isEmpty(), "the keeper said something dry");
            helper.assertTrue(serving.equals(pl.getName().getString()), "the till says who it served: " + serving);
            helper.succeed();
        });
    }

    // ============================================================ can't afford it

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl02_cant_afford")
    public static void tl02_cant_afford(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7700300, 0);
        helper.runAtTickTime(5, () -> {
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 32));
            ServerPlayer pl = player(helper, t.point().queueSpot(level, 0), 1);
            Tills.openAt(pl, t.till());
            Tills.pressForTests(pl, "set", set(BREAD_ID, 20));
            CompoundTag screen = Tills.screenForTests(pl);
            String why = screen.getString("why");
            int shop0 = ShopStock.held(level, t.id(), BREAD);
            Tills.pressForTests(pl, "pay", new CompoundTag());
            Kit.log("tl02 20 bread for " + screen.getCompound("quote").getInt("total") + " with 1 coin: Pay rests: " + why + "; pressed anyway: "
                + Tills.messageForTests(pl));
            int coins = Market.coinsHeld(pl), shop1 = ShopStock.held(level, t.id(), BREAD);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(why.startsWith("Short by"), "Pay says why it rests: " + why);
            helper.assertTrue(coins == 1 && shop0 == shop1, "nothing moved");
            helper.succeed();
        });
    }

    // ============================================================ sold out

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl03_sold_out")
    public static void tl03_sold_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7700500, 0);
        helper.runAtTickTime(5, () -> {
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 2));
            ServerPlayer pl = player(helper, t.point().queueSpot(level, 0), 64);
            Tills.openAt(pl, t.till());
            Tills.pressForTests(pl, "set", set(BREAD_ID, 2));
            Tills.pressForTests(pl, "pay", new CompoundTag());
            Tills.pressForTests(pl, "set", set(BREAD_ID, 1));
            CompoundTag screen = Tills.screenForTests(pl);
            CompoundTag bread = line(screen, BREAD_ID);
            Kit.log("tl03 after the last two: bread " + (bread == null ? "off the list" : "stock " + bread.getInt("stock")) + "; basket "
                + screen.getCompound("quote").getList("lines", Tag.TAG_COMPOUND).size() + " lines; Pay: " + screen.getString("why"));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(bread != null && bread.getInt("stock") == 0, "bread stays on the list, sold out");
            helper.assertTrue(screen.getCompound("quote").getList("lines", Tag.TAG_COMPOUND).isEmpty(), "none of it goes in a basket");
            helper.assertTrue(!screen.getString("why").isEmpty(), "Pay rests");
            helper.succeed();
        });
    }

    // ============================================================ a folk at the same till

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl04_folk_buys")
    public static void tl04_folk_buys(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7700700, 1);
        VillageFolkEntity f = t.folk().get(0);
        helper.runAtTickTime(5, () -> {
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 16));
            BlockPos at = t.point().queueSpot(level, 0);
            f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);   // the test's stage: in front of the till
            f.earn(20);
            int purse0 = f.purse(), shop0 = ShopStock.held(level, t.id(), BREAD), treasury0 = Ledger.coins(t.id());
            ItemStack got = Tills.folkCheckout(level, t.till(), f, new ItemStack(Items.BREAD), 1);
            int purse1 = f.purse(), shop1 = ShopStock.held(level, t.id(), BREAD);
            String serving = level.getBlockEntity(t.till()) instanceof TillBlockEntity be ? be.serving() : "";
            Kit.log("tl04 " + f.displayNameCap() + " at the till: got " + got + "; purse " + purse0 + " -> " + purse1 + ", shop " + shop0 + " -> " + shop1
                + ", treasury " + treasury0 + " -> " + Ledger.coins(t.id()) + "; the till: serving " + serving);
            helper.assertTrue(got != null && got.is(Items.BREAD), "bought at the till");
            helper.assertTrue(purse1 < purse0 && shop0 - shop1 == 1, "paid out of its purse, a loaf off the stock");
            helper.assertTrue(serving.equals(f.displayNameCap()), "the till says who it served: " + serving);
            helper.succeed();
        });
    }

    // ============================================================ a full pack

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl05_full_pack")
    public static void tl05_full_pack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7700900, 0);
        helper.runAtTickTime(5, () -> {
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 32));
            ServerPlayer pl = player(helper, t.point().queueSpot(level, 0), 64);
            for (int i = 0; i < pl.getInventory().items.size(); i++) if (pl.getInventory().items.get(i).isEmpty()) pl.getInventory().items.set(i, new ItemStack(Items.COBBLESTONE, 64));
            Tills.openAt(pl, t.till());
            Tills.pressForTests(pl, "set", set(BREAD_ID, 3));
            Tills.pressForTests(pl, "pay", new CompoundTag());
            int onFloor = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(pl.blockPosition()).inflate(4))) if (e.getItem().is(Items.BREAD)) onFloor += e.getItem().getCount();
            String said = Tills.messageForTests(pl);
            Kit.log("tl05 a full pack: " + onFloor + " loaves at the feet; said " + said);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(onFloor == 3, "the three at the player's feet: " + onFloor);
            helper.assertTrue(said.contains("at your feet"), "and told so: " + said);
            helper.succeed();
        });
    }

    // ============================================================ selling back

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl06_sell_back")
    public static void tl06_sell_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7701100, 0);
        helper.runAtTickTime(5, () -> {
            ServerPlayer pl = player(helper, t.point().queueSpot(level, 0), 0);
            pl.getInventory().add(new ItemStack(Items.WHEAT, 32));
            Ledger.addCoins(t.id(), 100);
            Tills.openAt(pl, t.till());
            CompoundTag tab = new CompoundTag();
            tab.putString("tab", "sell");
            Tills.pressForTests(pl, "tab", tab);
            ItemStack wheat = new ItemStack(Items.WHEAT);
            String id = Goods.lineId(wheat);
            CompoundTag screen = Tills.screenForTests(pl);
            int bid = 0, lot = ShopDesk.sellLotForTests(wheat);
            ListTag sell = screen.getList("sell", Tag.TAG_COMPOUND);
            for (int i = 0; i < sell.size(); i++) if (sell.getCompound(i).getString("id").equals(id)) bid = sell.getCompound(i).getInt("bid");
            Tills.pressForTests(pl, "sset", set(id, 1));
            int sellTotal = Tills.screenForTests(pl).getInt("sellTotal");
            Tills.pressForTests(pl, "sell", new CompoundTag());
            int coins = Market.coinsHeld(pl), left = carried(pl, s -> s.is(Items.WHEAT));
            Kit.log("tl06 sell back a lot of " + lot + " wheat bid " + bid + " (basket " + sellTotal + "): paid " + coins + ", left " + left + "; said "
                + Tills.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(screen.getBoolean("buys") && bid > 0, "the shop buys wheat back: " + bid);
            helper.assertTrue(coins == bid && sellTotal == bid && left == 32 - lot, "paid its bid for the lot");
            helper.succeed();
        });
    }

    // ============================================================ nobody at the till: the kiosk

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl07_no_staff_kiosk")
    public static void tl07_no_staff_kiosk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7701300, 0);
        helper.runAtTickTime(5, () -> {
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 32));
            BlockPos away = t.point().queueSpot(level, 0).relative(t.point().front(), 30);
            t.keeper().teleportTo(away.getX() + 0.5, Kit.surface(level, away.getX(), away.getZ()).getY(), away.getZ() + 0.5);   // off on its break
            ServerPlayer pl = player(helper, t.point().queueSpot(level, 0), 64);
            Tills.openAt(pl, t.till());
            Tills.pressForTests(pl, "set", set(BREAD_ID, 2));
            CompoundTag atTill = Tills.screenForTests(pl);
            // The owner's kiosk beside the counter.
            BlockPos kiosk = t.point().queueSpot(level, 0).relative(t.point().front().getClockWise(), 2);
            level.setBlock(kiosk, TillItems.KIOSK.get().defaultBlockState().setValue(TillBlock.FACING, t.point().front()), 3);
            Tills.bindForTests(level, kiosk, t.id(), "town:shop", null, true);
            CompoundTag offered = Tills.screenForTests(pl);
            int shop0 = ShopStock.held(level, t.id(), BREAD), coins0 = Market.coinsHeld(pl);
            Tills.openAt(pl, kiosk);
            Tills.pressForTests(pl, "set", set(BREAD_ID, 2));
            CompoundTag atKiosk = Tills.screenForTests(pl);
            Tills.pressForTests(pl, "pay", new CompoundTag());
            int shop1 = ShopStock.held(level, t.id(), BREAD), coins1 = Market.coinsHeld(pl);
            Kit.log("tl07 nobody at the till: " + atTill.getString("status") + " / Pay: " + atTill.getString("why") + "; with a kiosk by: offers self-service "
                + offered.getBoolean("offerSelf") + "; at the kiosk: " + atKiosk.getString("status") + ", bought: shop " + shop0 + " -> " + shop1 + ", coins "
                + coins0 + " -> " + coins1 + "; said " + Tills.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(!atTill.getBoolean("staffed") && atTill.getString("status").startsWith("Nobody at the till: back at"), "says nobody's in, and when: "
                + atTill.getString("status"));
            helper.assertTrue(!atTill.getBoolean("canServe") && !atTill.getString("why").isEmpty(), "Pay rests at the empty till");
            helper.assertTrue(offered.getBoolean("offerSelf"), "self-service offered once the owner has a kiosk");
            helper.assertTrue(atKiosk.getBoolean("kiosk") && atKiosk.getBoolean("canServe"), "the kiosk serves");
            helper.assertTrue(shop0 - shop1 == 2 && coins1 < coins0, "bought at the kiosk");
            helper.succeed();
        });
    }

    // ============================================================ a shelf: shown, browsed, sold off

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl08_shelf_sells")
    public static void tl08_shelf_sells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7701500, 0);
        helper.runAtTickTime(5, () -> {
            BlockPos shelf = t.point().queueSpot(level, 0).relative(t.point().front().getCounterClockWise(), 2);
            level.setBlock(shelf, TillItems.DISPLAY_SHELF.get().defaultBlockState().setValue(FittingBlock.FACING, t.point().front()), 3);
            Fittings.bindForTests(level, shelf, t.id(), "town:shop");
            FittingBlockEntity be = (FittingBlockEntity) level.getBlockEntity(shelf);
            be.setItem(0, new ItemStack(Items.APPLE, 5));
            ServerPlayer pl = player(helper, t.point().queueSpot(level, 0), 64);
            Tills.browse(pl, t.id(), "town:shop", shelf, new ItemStack(Items.APPLE));
            CompoundTag screen = Tills.screenForTests(pl);
            String apple = Goods.lineId(new ItemStack(Items.APPLE));
            CompoundTag line = line(screen, apple);
            int inBasket = 0;
            ListTag ql = screen.getCompound("quote").getList("lines", Tag.TAG_COMPOUND);
            for (int i = 0; i < ql.size(); i++) if (ql.getCompound(i).getString("id").equals(apple)) inBasket = ql.getCompound(i).getInt("n");
            Tills.pressForTests(pl, "pay", new CompoundTag());
            int onShelf = be.getItem(0).getCount(), had = carried(pl, s -> s.is(Items.APPLE));
            Kit.log("tl08 a shelf of 5 apples: on the list " + (line == null ? "no" : "stock " + line.getInt("stock")) + ", in the basket " + inBasket
                + "; paid: shelf " + onShelf + ", pack " + had + "; said " + Tills.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(line != null && line.getInt("stock") >= 5, "the shelf's apples are on sale");
            helper.assertTrue(inBasket == 1, "browsing put one in the basket");
            helper.assertTrue(onShelf == 4 && had == 1, "sold off the shelf");
            helper.succeed();
        });
    }

    // ============================================================ the staff fill a shelf

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl09_staff_restock")
    public static void tl09_staff_restock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7701700, 0);
        helper.runAtTickTime(5, () -> {
            Store.putForTests(level, t.id(), new ItemStack(Items.CARROT, 40));
            BlockPos shelf = t.point().queueSpot(level, 0).relative(t.point().front().getCounterClockWise(), 2);
            level.setBlock(shelf, TillItems.DISPLAY_SHELF.get().defaultBlockState().setValue(FittingBlock.FACING, t.point().front()), 3);
            Fittings.bindForTests(level, shelf, t.id(), "town:shop");
            FittingBlockEntity be = (FittingBlockEntity) level.getBlockEntity(shelf);
            BlockPos by = shelf.relative(t.point().front());
            t.keeper().teleportTo(by.getX() + 0.5, by.getY(), by.getZ() + 0.5);   // the test's stage: the walk is the tick's
            int moved = Fittings.restockForTests(level, shelf, t.keeper());
            int shown = 0;
            for (ItemStack s : be.shown()) shown += s.getCount();
            Kit.log("tl09 the keeper at an empty shelf: moved " + moved + ", now on it " + shown + " (" + be.shown() + ")");
            helper.assertTrue(moved > 0 && shown == moved, "the shelf filled from the stock: " + moved);
            helper.succeed();
        });
    }

    // ============================================================ a fit-out brings footfall

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl10_fitout_footfall")
    public static void tl10_fitout_footfall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7701900, 0);
        helper.runAtTickTime(5, () -> {
            int bare = Fittings.footfallPercentForTests(level, t.id(), "town:shop");
            BlockPos base = t.heart().east(6);
            for (int i = 0; i < 4; i++) {
                BlockPos p = Kit.surface(level, base.getX() + i, base.getZ());
                level.setBlock(p, TillItems.DISPLAY_SHELF.get().defaultBlockState(), 3);
                Fittings.bindForTests(level, p, t.id(), "town:shop");
                FittingBlockEntity be = (FittingBlockEntity) level.getBlockEntity(p);
                for (int s = 0; s < FittingBlockEntity.SLOTS; s++) be.setItem(s, new ItemStack(Items.BREAD, 8));
            }
            BlockPos board = Kit.surface(level, base.getX(), base.getZ() + 2);
            level.setBlock(board, TillItems.PRICE_BOARD.get().defaultBlockState(), 3);
            Fittings.bindForTests(level, board, t.id(), "town:shop");
            int fitted = Fittings.footfallPercentForTests(level, t.id(), "town:shop");
            double factor = Fittings.footfall(t.id(), "town:shop");
            List<String> lines = Fittings.boardForTests(level, (FittingBlockEntity) level.getBlockEntity(board));
            Kit.log("tl10 footfall bare " + bare + "%, with 4 full shelves and a board " + fitted + "% (x" + factor + "); the board: " + lines);
            helper.assertTrue(bare == 0 && fitted >= 15 && factor > 1.1, "a fitted-out floor brings custom: " + fitted + "%");
            helper.assertTrue(!lines.isEmpty(), "the board names its business");
            helper.succeed();
        });
    }

    // ============================================================ every new block crafts

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "tl11_recipes_craft")
    public static void tl11_recipes_craft(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> wanting = new ArrayList<>();
        for (var it : TillItems.allItems()) {
            Item want = it.get();
            boolean made = false;
            for (RecipeHolder<CraftingRecipe> h : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
                if (!(h.value() instanceof ShapedRecipe r) || !r.getResultItem(level.registryAccess()).is(want)) continue;
                NonNullList<Ingredient> ing = r.getIngredients();
                List<ItemStack> grid = new ArrayList<>();
                for (Ingredient i : ing) grid.add(i.isEmpty() ? ItemStack.EMPTY : i.getItems()[0].copy());
                CraftingInput in = CraftingInput.of(r.getWidth(), r.getHeight(), grid);
                if (r.matches(in, level) && r.assemble(in, level.registryAccess()).is(want)) made = true;
            }
            if (!made) wanting.add(it.getId().getPath());
        }
        Kit.log("tl11 " + TillItems.allItems().size() + " blocks; not crafted: " + wanting);
        helper.assertTrue(wanting.isEmpty(), "every new block crafts at the table: " + wanting);
        helper.succeed();
    }

    // ============================================================ a player's own shop: its till, self-service, its till's coin

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tl12_player_shop_till")
    public static void tl12_player_shop_till(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 7702100, 0);
        helper.runAtTickTime(5, () -> {
            BlockPos heart = t.heart();
            int x0 = heart.getX() + 5, x1 = heart.getX() + 11, z0 = heart.getZ() - 3, z1 = heart.getZ() + 3, y = heart.getY();
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y + 3, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                for (int h = 0; h < 3; h++) level.setBlock(new BlockPos(x, y + h, z), wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
            BlockPos door = new BlockPos(x0, y, heart.getZ());
            level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST)
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
            level.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST)
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
            BlockPos counter = heart.offset(8, 0, 0);
            level.setBlockAndUpdate(counter, McAssistantMod.SHOP_COUNTER.get().defaultBlockState().setValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING, Direction.WEST));
            BlockPos chestAt = heart.offset(10, 0, 2);
            level.setBlockAndUpdate(chestAt, Blocks.CHEST.defaultBlockState());
            net.minecraft.world.Container chest = (net.minecraft.world.Container) level.getBlockEntity(chestAt);
            chest.setItem(0, new ItemStack(Items.BREAD, 12));
            ServerPlayer owner = player(helper, heart.offset(8, 0, -1), 40);
            com.jrpetty.mcassistant.entity.PlayerShops.Shop shop = com.jrpetty.mcassistant.entity.PlayerShops.registerForTests(level, counter, owner, "general");
            helper.assertTrue(shop != null, "the shop is registered");
            com.jrpetty.mcassistant.entity.PlayerShops.priceForTests(shop, new ItemStack(Items.BREAD), 2);
            boolean fitted = level.getBlockEntity(counter.above()) instanceof TillBlockEntity;
            if (!fitted) {
                level.setBlock(counter.above(), TillItems.TILL.get().defaultBlockState().setValue(TillBlock.FACING, Direction.WEST), 3);
                Tills.bindForTests(level, counter.above(), t.id(), Tills.keyOfShopForTests(shop), owner.getUUID(), true);
            }
            owner.teleportTo(heart.getX() - 30.5, heart.getY(), heart.getZ() + 0.5);   // off out: nobody at the till
            ServerPlayer buyer = player(helper, counter.west(), 30);
            Tills.openAt(buyer, counter.above());
            CompoundTag before = Tills.screenForTests(buyer);
            Tills.pressForTests(buyer, "self", new CompoundTag());
            String id = com.jrpetty.mcassistant.entity.PlayerStalls.key(new ItemStack(Items.BREAD));
            Tills.pressForTests(buyer, "set", set(id, 3));
            CompoundTag ready = Tills.screenForTests(buyer);
            int till0 = shop.till, coins0 = Market.coinsHeld(buyer), shelf0 = chest.getItem(0).getCount();
            Tills.pressForTests(buyer, "pay", new CompoundTag());
            int till1 = shop.till, coins1 = Market.coinsHeld(buyer), shelf1 = chest.getItem(0).getCount(), had = carried(buyer, BREAD);
            Kit.log("tl12 " + shop.name + ": a till on its counter " + fitted + "; nobody in: " + before.getString("status") + " (self-service offered "
                + before.getBoolean("offerSelf") + "); 3 bread for " + ready.getCompound("quote").getInt("total") + ": coins " + coins0 + " -> " + coins1
                + ", shelf " + shelf0 + " -> " + shelf1 + ", its till " + till0 + " -> " + till1 + ", pack " + had + "; said " + Tills.messageForTests(buyer));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(fitted, "the shop's till set on its counter when it was registered");
            helper.assertTrue(before.getBoolean("offerSelf") && !before.getBoolean("staffed"), "nobody in, self-service offered");
            helper.assertTrue(shelf0 - shelf1 == 3 && had == 3, "three loaves off its shelf into the buyer's pack");
            helper.assertTrue(coins0 - coins1 == ready.getCompound("quote").getInt("total") && till1 > till0, "the coin into the shop's till: " + till0 + " -> " + till1);
            helper.succeed();
        });
    }
}
