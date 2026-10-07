package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.ShopRoles;
import com.jrpetty.mcassistant.entity.ShopStock;
import com.jrpetty.mcassistant.entity.StockKeeper;
import com.jrpetty.mcassistant.entity.Store;
import com.jrpetty.mcassistant.entity.StoreDeliveries;
import com.jrpetty.mcassistant.entity.StoreFloor;
import com.jrpetty.mcassistant.entity.StoreStaff;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Workshop;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The town store (Store, ShopStock, StockKeeper, StoreDeliveries, StoreFloor, StoreStaff): the store's drawing
 * stands up with its counters, its stockroom, its workshop and its office, and the town wants it when it has
 * outgrown its shop; the shop sells out of its own stockroom and not the stores while it has the ware, and sends
 * to the stores (a stock-out, booked) when it has not; the stock keeper's count orders a ware running low from
 * the storehouse, and the delivery is real goods carried out of the stores into the stockroom; a ware nobody has
 * is ordered from the crafters, and what they make for it goes into the stockroom; the shop takes on a keeper,
 * crafters, an assistant and a stock keeper; and a folk buying at a counter is served there by its assistant.
 *
 * <p>Each on its own ground (x 640500 to 652500, z 64000), in a batch of its own, all of it before the first tick
 * so that nobody's own agenda takes from the stores meanwhile.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class StoreGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 64000;

    /** A village of a keeper and so many more, its stores a storehouse and nothing else, its shop built (out of the
     *  store area, so its chests and casks are no part of the stores), and the town store too if asked for. */
    private record Town(UUID village, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, VillageFolkEntity keeper,
                        List<VillageFolkEntity> more, BlockPos shopAt, BlockPos storeAt) {}

    private static Town town(GameTestHelper helper, int x, int others, boolean withStore) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 72);
        Kit.prepare(level, x, Z, 72);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID village = keeper.ownerId();
        List<VillageFolkEntity> more = new ArrayList<>();
        for (int i = 0; i < others; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + (i % 6)).east(i / 6), 0.0F);
            helper.assertTrue(f != null && village.equals(f.ownerId()), "folk " + i);
            f.setJob(StationTask.NONE);
            more.add(f);
        }
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, village);
        BlockPos shopAt = Kit.surface(level, x - 42, Z - 20);
        BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "shop", shopAt, Direction.NORTH);
        Villages.builtAtForTests(village, "shop", shopAt);
        BlockPos storeAt = Kit.surface(level, x + 40, Z - 24);
        if (withStore) {
            BuildGoal.stamp(level, Store.STRUCTURE, storeAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(village, Store.STRUCTURE, storeAt, Direction.NORTH);
            Villages.builtAtForTests(village, Store.STRUCTURE, storeAt);
        }
        Villages.forgetStores(village);
        Villages.ageForTests(village, Villages.Age.IRON);
        keeper.setJob(StationTask.SHOP);
        Store.instantForTests(true);
        return new Town(village, Villages.get(village), heart, store, keeper, more, shopAt, storeAt);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
                level.setBlock(origin.offset(x, y, z), unit, 3);
            }
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands: " + level.getBlockState(door));
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    /** No chest about but the storehouse (the founders' chest goes). */
    private static void onlyTheStorehouse(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    /** The storehouse filled with these, in its first slots, and the village's counts made afresh. */
    private static void fill(Town t, ItemStack... goods) {
        for (int i = 0; i < t.store().getContainerSize(); i++) t.store().setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < goods.length; i++) t.store().setItem(i, goods[i]);
        Villages.forgetStock();
        Villages.forgetStores(t.village());
        com.jrpetty.mcassistant.entity.Budget.forget(t.village());
    }

    private static int stores(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    private static int roomOf(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return ShopStock.held(level, village, what);
    }

    // ============================================================ the building

    /**
     * The store's drawing stood up: six counters on its shop floor, a stockroom of twenty-odd chests and casks, the
     * workshop's five benches and the stock keeper's desk, every counter with a place behind or beside it for its
     * assistant. It goes on a long lot by the square; a town of forty in the Iron Age with a shop wants one, a town
     * of twenty does not, and the builders know it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sx01_store_stands")
    public static void sx01_store_stands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 640500, 1, false);
        UUID village = t.village();
        // Wanted (or not) while the town has only its shop.
        boolean at40 = Store.wanted(village, 40), at20 = Store.wanted(village, 20);
        String why = Store.why(village);
        BuildGoal.stamp(level, Store.STRUCTURE, t.storeAt(), Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, Store.STRUCTURE, t.storeAt(), Direction.NORTH);
        Villages.builtAtForTests(village, Store.STRUCTURE, t.storeAt());
        boolean again = Store.wanted(village, 40);
        Ledger.Building b = Villages.builtStructure(village, Store.STRUCTURE);
        helper.assertTrue(b != null, "the store is in the ledger");
        int[] layout = Store.layoutForTests(b);
        List<String> posts = StoreFloor.postsForTests(level, village);
        int manned = 0;
        for (String p : posts) if (!p.endsWith("|")) manned++;
        int[] half = BuildGoal.footprint(Store.STRUCTURE);
        Kit.log("sx01 the store: counters " + layout[0] + ", stockroom " + layout[1] + ", benches " + layout[2] + ", desk " + layout[3]
            + "; posts " + posts.size() + " (" + manned + " with room for an assistant): " + posts + "; footprint +-" + half[0] + " x +-" + half[1]
            + "; wanted at forty " + at40 + ", at twenty " + at20 + ", once built " + again + "; why: " + why + "; lot " + TownPlan.placeFor(Store.STRUCTURE));
        helper.assertTrue(layout[0] == 6, "six counters on the shop floor: " + layout[0]);
        helper.assertTrue(layout[1] >= 20, "a stockroom of twenty-odd chests and casks: " + layout[1]);
        helper.assertTrue(layout[2] >= 5 && layout[3] == 1, "the workshop's benches and the office's desk: " + layout[2] + ", " + layout[3]);
        helper.assertTrue(manned >= 6, "every one of the store's counters has a place for its assistant: " + posts);
        helper.assertTrue(BuildGoal.STRUCTURES.contains(Store.STRUCTURE) && TownPlan.placeFor(Store.STRUCTURE).equals("great"),
            "the builders know it, and it goes on a long lot");
        helper.assertTrue(half[0] <= TownPlan.LOT / 2 && half[1] <= TownPlan.LOT - 1, "it fits a long lot: +-" + half[0] + " x +-" + half[1]);
        helper.assertTrue(at40 && !at20 && !again, "a town of forty wants it, a town of twenty not yet, and not a second: " + at40 + ", " + at20 + ", " + again);
        helper.succeed();
    }

    // ============================================================ the shop's own stock

    /**
     * The shop sells out of its own stockroom: four loaves taken for a sale come out of the stockroom and not a
     * crumb out of the stores. Short of it (six left, ten wanted), the rest is fetched from the stores and booked
     * as a stock-out; a ware the stockroom has none of comes from the stores, booked the same; and what nobody has
     * is no sale, and wanted.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sx02_sells_out_of_its_stockroom")
    public static void sx02_sells_out_of_its_stockroom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 642500, 0, false);
        UUID village = t.village();
        fill(t, new ItemStack(Items.BREAD, 32), new ItemStack(Items.TORCH, 16));
        int left = Store.putForTests(level, village, new ItemStack(Items.BREAD, 10));
        Predicate<ItemStack> bread = s -> s.is(Items.BREAD), torch = s -> s.is(Items.TORCH);
        helper.assertTrue(left == 0 && ShopStock.open(village), "the shop is open, ten loaves in its stockroom");
        int room0 = roomOf(level, village, bread), stores0 = stores(level, village, bread);
        boolean took4 = ShopStock.take(level, t.v(), bread, 4);
        int room1 = roomOf(level, village, bread), stores1 = stores(level, village, bread);
        boolean took10 = ShopStock.take(level, t.v(), bread, 10);
        int room2 = roomOf(level, village, bread), stores2 = stores(level, village, bread);
        int torches0 = stores(level, village, torch);
        boolean tookTorches = ShopStock.take(level, t.v(), torch, 3);
        int torches1 = stores(level, village, torch);
        boolean tookApples = ShopStock.take(level, t.v(), s -> s.is(Items.APPLE), 5);
        String breadLine = StockKeeper.lineForTests(level, village, new ItemStack(Items.BREAD));
        String torchLine = StockKeeper.lineForTests(level, village, new ItemStack(Items.TORCH));
        String appleLine = StockKeeper.lineForTests(level, village, new ItemStack(Items.APPLE));
        int price = ShopStock.price(level, village, new ItemStack(Items.BREAD));
        Kit.log("sx02 bread: stockroom " + room0 + " -> " + room1 + " -> " + room2 + ", stores " + stores0 + " -> " + stores1 + " -> " + stores2
            + " (took 4 " + took4 + ", took 10 " + took10 + "); torches in the stores " + torches0 + " -> " + torches1 + " (" + tookTorches
            + "); apples " + tookApples + "; the book (onHand/reorder/upTo/sold7/outs7/markup): bread " + breadLine + ", torches " + torchLine
            + ", apples " + appleLine + "; a loaf costs " + price);
        helper.assertTrue(took4 && room1 == room0 - 4 && stores1 == stores0, "four loaves out of the stockroom, none out of the stores: "
            + room0 + " -> " + room1 + ", " + stores0 + " -> " + stores1);
        helper.assertTrue(took10 && room2 == 0 && stores2 == stores1 - 4, "short of it: the stockroom's six and four from the stores: "
            + room2 + ", " + stores1 + " -> " + stores2);
        helper.assertTrue(tookTorches && torches1 == torches0 - 3, "a ware the stockroom has none of comes from the stores");
        helper.assertTrue(!tookApples, "and what nobody has is no sale");
        helper.assertTrue(breadLine.split("/")[3].equals("14") && Integer.parseInt(breadLine.split("/")[4]) >= 1,
            "the stock book: fourteen loaves sold, a stock-out booked: " + breadLine);
        helper.assertTrue(Integer.parseInt(torchLine.split("/")[4]) >= 1 && Integer.parseInt(appleLine.split("/")[4]) >= 1,
            "and the torches and the apples as stock-outs: " + torchLine + ", " + appleLine);
        helper.assertTrue(price >= 1, "the shop's price for a loaf: " + price);
        helper.succeed();
    }

    // ============================================================ a delivery

    /**
     * Bread selling twenty a day and two loaves left in the stockroom: the stock keeper's morning count puts it
     * under its reorder point and orders it up from the storehouse; the stock keeper carries it over itself (no
     * courier in the town) — real loaves out of the storehouse, through its pack, into the stockroom, as many out
     * of the one as into the other — and the book has the delivery.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sx03_delivery_from_the_storehouse")
    public static void sx03_delivery_from_the_storehouse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 644500, 1, false);
        UUID village = t.village();
        VillageFolkEntity sk = t.more().get(0);
        StoreStaff.appointForTests(level, t.v(), sk, ShopRoles.Role.STOCK_KEEPER);
        fill(t, new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64));
        Store.putForTests(level, village, new ItemStack(Items.BREAD, 2));
        ItemStack loaf = new ItemStack(Items.BREAD);
        StockKeeper.soldForTests(level, village, loaf, 20, 18, 22);
        Predicate<ItemStack> bread = s -> s.is(Items.BREAD);
        helper.assertTrue(ShopRoles.role(sk) == ShopRoles.Role.STOCK_KEEPER && ShopRoles.role(t.keeper()) == ShopRoles.Role.KEEPER,
            "a keeper and a stock keeper: " + ShopRoles.role(t.keeper()) + ", " + ShopRoles.role(sk));
        int room0 = roomOf(level, village, bread), stores0 = stores(level, village, bread), pack0 = sk.countCarried(bread);
        List<String> placed = StockKeeper.takeStock(level, t.v(), true);
        String line = StockKeeper.lineForTests(level, village, loaf);
        List<String> waiting = StoreDeliveries.listForTests(village);
        int moved = StoreDeliveries.carryForTests(sk, level);
        int room1 = roomOf(level, village, bread), stores1 = stores(level, village, bread), pack1 = sk.countCarried(bread);
        String page = StockKeeper.page(level, t.v());
        Kit.log("sx03 the count: " + placed + "; bread (onHand/reorder/upTo/sold7/outs7/markup) " + line + "; on the list " + waiting
            + "; carried " + moved + ": stockroom " + room0 + " -> " + room1 + ", stores " + stores0 + " -> " + stores1 + ", the stock keeper's pack "
            + pack0 + " -> " + pack1 + "; /village stock:\n" + page);
        String[] l = line.split("/");
        helper.assertTrue(Integer.parseInt(l[0]) == 2 && Integer.parseInt(l[1]) >= 20 && Integer.parseInt(l[2]) > Integer.parseInt(l[1]),
            "two on hand, a reorder point of a day and a half's sales, and an order-up-to over it: " + line);
        helper.assertTrue(placed.stream().anyMatch(p -> p.contains("bread") && p.contains("from the storehouse")), "bread ordered from the storehouse: " + placed);
        helper.assertTrue(waiting.stream().anyMatch(w -> w.startsWith("minecraft:bread:") && w.contains("WAITING")), "a delivery waiting: " + waiting);
        helper.assertTrue(moved > 0 && room1 - room0 == moved && stores0 - stores1 == moved,
            "real loaves carried: " + moved + " out of the stores, " + (room1 - room0) + " into the stockroom");
        helper.assertTrue(pack1 == pack0, "none left in the carrier's pack: " + pack0 + " -> " + pack1);
        helper.assertTrue(stores1 >= 64, "and the stores keep half their bread: " + stores1);
        helper.assertTrue(page.contains("delivered") && page.contains("Bread"), "the stock book says so");
        helper.succeed();
    }

    // ============================================================ the crafters' order

    /**
     * Iron pickaxes selling two or three a day, none in the shop and none in the stores: the stock keeper orders
     * them from the crafters (an order of its own on the workshop's book), the shop's hand makes one of the stores'
     * iron and sticks, and it goes into the stockroom, not the stores; the order is the nearer done.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sx04_ordered_from_the_crafters")
    public static void sx04_ordered_from_the_crafters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 646500, 2, false);
        UUID village = t.village();
        VillageFolkEntity hand = t.more().get(0), sk = t.more().get(1);
        Workshop.appointForTests(hand);
        StoreStaff.appointForTests(level, t.v(), sk, ShopRoles.Role.STOCK_KEEPER);
        fill(t, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.CRAFTING_TABLE));
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        Predicate<ItemStack> picks = s -> s.is(Items.IRON_PICKAXE);
        StockKeeper.soldForTests(level, village, pick, 2, 3, 2);
        List<String> placed = StockKeeper.takeStock(level, t.v(), true);
        List<String> orders = StockKeeper.ordersForTests(level, village);
        Workshop.lookForTests(level, t.v());
        int need = Workshop.need(level, village, "minecraft:iron_pickaxe");
        CompoundTag report = Workshop.report(level, village);
        String why = "";
        ListTag book = report.getList("book", Tag.TAG_COMPOUND);
        for (int i = 0; i < book.size(); i++) if (book.getCompound(i).getString("id").equals("minecraft:iron_pickaxe")) why = book.getCompound(i).getString("why");
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 6 && roomOf(level, village, picks) == 0; i++) {
            if (Crafts.now(hand, level, t.v())) made.add(String.valueOf(Workshop.doing(hand)));
        }
        int room = roomOf(level, village, picks), inStores = stores(level, village, picks);
        List<String> after = StockKeeper.ordersForTests(level, village);
        Kit.log("sx04 the count: " + placed + "; orders " + orders + "; the workshop's book: " + need + " for " + why + "; the hand: " + made
            + "; iron pickaxes in the stockroom " + room + ", in the stores " + inStores + "; orders after " + after);
        helper.assertTrue(placed.stream().anyMatch(p -> p.contains("iron pickaxe") && p.contains("crafters")), "ordered from the crafters: " + placed);
        helper.assertTrue(orders.stream().anyMatch(o -> o.startsWith("minecraft:iron_pickaxe:CRAFTERS")), "an order in flight to the crafters: " + orders);
        helper.assertTrue(need >= 1 && why.contains("the stock keeper"), "on the workshop's book for the stock keeper: " + need + ", " + why);
        helper.assertTrue(room >= 1 && inStores == 0, "made and put in the stockroom, not the stores: " + room + ", " + inStores);
        helper.succeed();
    }

    // ============================================================ the staff

    /**
     * A town of ten with a store: the shop takes on a crafter for its bench (its order book has work in it), a
     * stock keeper and an assistant out of the folk between trades, and keeps one keeper; each job on its card and
     * its nameplate. And by the town's size: the store wants one assistant at forty folk, two at sixty, three at
     * ninety or at two hundred and fifty sales a week, never more than its counters; the little shop one from
     * twenty-four.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sx05_the_store_staffed")
    public static void sx05_the_store_staffed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 648500, 9, true);
        UUID village = t.village();
        fill(t, new ItemStack(Items.COAL, 32), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.CRAFTING_TABLE));
        Workshop.order(level, t.v(), Items.TORCH, 16, "Tester");
        Workshop.tick(level, t.v());
        StoreStaff.tickForTests(level, t.v());
        int keepers = 0, assistants = 0, crafters = 0, stockKeepers = 0;
        VillageFolkEntity sk = null, assistant = null;
        List<String> who = new ArrayList<>();
        for (VillageFolkEntity f : t.more()) {
            ShopRoles.Role r = ShopRoles.role(f);
            who.add(f.displayNameCap() + "=" + r);
        }
        List<VillageFolkEntity> all = new ArrayList<>(t.more());
        all.add(t.keeper());
        for (VillageFolkEntity f : all) {
            switch (ShopRoles.role(f)) {
                case KEEPER -> keepers++;
                case ASSISTANT -> { assistants++; assistant = f; }
                case CRAFTER -> crafters++;
                case STOCK_KEEPER -> { stockKeepers++; sk = f; }
                default -> { }
            }
        }
        int wantA = StoreStaff.assistantsWanted(village), wantS = StoreStaff.stockKeepersWanted(village);
        String skCard = sk == null ? "" : FolkTalk.card(sk), aCard = assistant == null ? "" : FolkTalk.card(assistant);
        Kit.log("sx05 the staff: keepers " + keepers + ", assistants " + assistants + " (wanted " + wantA + "), crafters " + crafters
            + " (the bench wants " + Workshop.handsWanted(village) + "), stock keepers " + stockKeepers + " (wanted " + wantS + "); " + who
            + "; the stock keeper's card: " + skCard.replace('\n', ' ') + "; the assistant's: " + aCard.replace('\n', ' ')
            + "; by size: " + StoreStaff.assistantsFor(40, true, 0, 6) + "/" + StoreStaff.assistantsFor(60, true, 0, 6) + "/"
            + StoreStaff.assistantsFor(90, true, 0, 6) + "/" + StoreStaff.assistantsFor(40, true, 300, 6) + "/" + StoreStaff.assistantsFor(90, true, 0, 2)
            + " (shop: " + StoreStaff.assistantsFor(20, false, 0, 8) + "/" + StoreStaff.assistantsFor(30, false, 0, 8) + ")");
        helper.assertTrue(keepers == 1, "one keeper: " + keepers);
        helper.assertTrue(crafters >= 1, "a crafter at the bench: " + crafters);
        helper.assertTrue(stockKeepers == 1 && wantS == 1, "a stock keeper: " + stockKeepers);
        helper.assertTrue(assistants == wantA && assistants >= 1, "the assistants the store wants: " + assistants + " of " + wantA);
        helper.assertTrue(skCard.contains("Stock keeper") && aCard.contains("Shop assistant"), "each on its card");
        helper.assertTrue(StoreStaff.assistantsFor(40, true, 0, 6) == 1 && StoreStaff.assistantsFor(60, true, 0, 6) == 2
            && StoreStaff.assistantsFor(90, true, 0, 6) == 3 && StoreStaff.assistantsFor(40, true, 300, 6) == 2
            && StoreStaff.assistantsFor(90, true, 0, 2) == 2 && StoreStaff.assistantsFor(20, false, 0, 8) == 0
            && StoreStaff.assistantsFor(30, false, 0, 8) == 1, "the assistants by the town's size and the store's sales");
        helper.succeed();
    }

    // ============================================================ at the counter

    /**
     * A farmer with coin at one of the store's counters, eight loaves in the stockroom and an assistant behind the
     * counter: the assistant serves it — a loaf out of the stockroom into its pack, its coin into the treasury, the
     * sale on the assistant's day — and the stores are untouched. The counters show the stock with its price tag;
     * and a folk across the room is told to come to the counter first.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "sx06_served_at_the_counter")
    public static void sx06_served_at_the_counter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 650500, 3, true);
        UUID village = t.village();
        VillageFolkEntity assistant = t.more().get(0), buyer = t.more().get(1), far = t.more().get(2);
        StoreStaff.appointForTests(level, t.v(), assistant, ShopRoles.Role.ASSISTANT);
        buyer.setJob(StationTask.FARM);
        far.setJob(StationTask.FARM);
        fill(t, new ItemStack(Items.BREAD, 16), new ItemStack(Items.OAK_PLANKS, 32));
        Store.putForTests(level, village, new ItemStack(Items.BREAD, 8));
        // The assistant to its counter, as at work.
        boolean atWork = StoreStaff.work(assistant, level);
        BlockPos counter = StoreFloor.counterForTests(level, assistant), stand = StoreFloor.standForTests(level, assistant);
        helper.assertTrue(counter != null && stand != null, "the assistant has a counter: " + StoreFloor.postForTests(level, assistant));
        double fromStand = Math.sqrt(assistant.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5));
        // The farmer at the counter, in front of it (the side the customers come to).
        BlockPos front = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = counter.relative(d);
            if (p.equals(stand) || !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) continue;
            if (front == null || p.distSqr(stand) > front.distSqr(stand)) front = p;
        }
        helper.assertTrue(front != null, "room in front of the counter");
        buyer.teleportTo(front.getX() + 0.5, front.getY(), front.getZ() + 0.5);
        far.teleportTo(t.heart().getX() + 0.5, t.heart().getY(), t.heart().getZ() + 0.5);
        buyer.earn(30);
        far.earn(30);
        Predicate<ItemStack> bread = s -> s.is(Items.BREAD);
        int purse0 = buyer.purse(), coins0 = Ledger.coins(village), room0 = roomOf(level, village, bread), stores0 = stores(level, village, bread);
        int carried0 = buyer.countCarried(bread);
        StoreFloor.Sale sale = StoreFloor.folkBuys(level, t.v(), buyer, bread, 1);
        int purse1 = buyer.purse(), coins1 = Ledger.coins(village), room1 = roomOf(level, village, bread), stores1 = stores(level, village, bread);
        int carried1 = buyer.countCarried(bread);
        StoreFloor.Sale farSale = StoreFloor.folkBuys(level, t.v(), far, bread, 1);
        int dressed = StoreFloor.dress(level, t.v());
        BlockPos at = t.storeAt();
        Kit.log("sx06 the assistant at work " + atWork + ", " + String.format("%.1f", fromStand) + " from its place behind " + counter.toShortString()
            + "; the sale: ok " + sale.ok() + ", served by " + (sale.servedBy() == null ? "nobody" : sale.servedBy().displayNameCap()) + " at "
            + (sale.counter() == null ? "-" : sale.counter().toShortString()) + " for " + sale.price() + "; purse " + purse0 + " -> " + purse1
            + ", treasury " + coins0 + " -> " + coins1 + ", stockroom " + room0 + " -> " + room1 + ", stores " + stores0 + " -> " + stores1
            + ", carried " + carried0 + " -> " + carried1 + "; served today " + StoreFloor.servedToday(assistant) + "; across the room: " + farSale.why()
            + "; the counters set out: " + dressed);
        helper.assertTrue(atWork && fromStand < 1.5, "the assistant behind its counter: " + fromStand);
        helper.assertTrue(sale.ok() && sale.servedBy() == assistant && counter.equals(sale.counter()),
            "the farmer served at the counter by the assistant: " + sale);
        helper.assertTrue(carried1 == carried0 + 1 && room1 == room0 - 1 && stores1 == stores0, "a loaf out of the stockroom into its pack, the stores untouched");
        helper.assertTrue(purse0 - purse1 == sale.price() && coins1 - coins0 == sale.price() && sale.price() >= 1,
            "its coin into the treasury: " + (purse0 - purse1) + " paid, " + (coins1 - coins0) + " in");
        helper.assertTrue(StoreFloor.servedToday(assistant) == 1, "on the assistant's day");
        helper.assertTrue(!farSale.ok() && farSale.why().equals("walk"), "a folk across the room comes to the counter first: " + farSale.why());
        // The counters, a few ticks on (a frame hung this tick is found from a later one).
        helper.runAfterDelay(5, () -> {
            List<String> shown = new ArrayList<>(), tags = new ArrayList<>();
            for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(at).inflate(12), f -> f.getTags().contains(StoreFloor.FRAME_TAG))) {
                if (!f.getItem().isEmpty()) shown.add(f.getItem().getHoverName().getString());
            }
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-7, 0, -10), at.offset(7, 2, 10))) {
                if (level.getBlockEntity(q) instanceof SignBlockEntity sign) tags.add(sign.getFrontText().getMessage(0, false).getString() + " / "
                    + sign.getFrontText().getMessage(2, false).getString());
            }
            Kit.log("sx06 the counters: " + shown + "; tags " + tags);
            helper.assertTrue(shown.contains("Bread") && tags.stream().anyMatch(s -> s.startsWith("Bread")), "the bread on a counter, its price tag in front");
            helper.succeed();
        });
    }
}
