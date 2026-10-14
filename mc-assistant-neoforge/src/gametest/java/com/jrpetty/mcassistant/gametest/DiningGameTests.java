package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.PlaceSettingBlock;
import com.jrpetty.mcassistant.block.PlaceSettingBlockEntity;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Diners;
import com.jrpetty.mcassistant.entity.Dining;
import com.jrpetty.mcassistant.entity.DiningMenu;
import com.jrpetty.mcassistant.entity.DiningPlaces;
import com.jrpetty.mcassistant.entity.Hospitality;
import com.jrpetty.mcassistant.entity.JobWorth;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.ServicePoints;
import com.jrpetty.mcassistant.entity.ServiceRole;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.DiningItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [dining] Eating out: a player and a folk served the same way, from a real kitchen, by real hands (Dining, Diners,
 * DiningMenu, DiningPlaces, DiningDesk, Hospitality, PlaceSettingBlock).
 * <ul>
 * <li><b>dn01</b>: a player orders at a table: the roast is cooked from the stores' raw beef by the named head cook at the
 *     smoker, the drink goes out first, the waiter sets both down on a plate and a cup on the table's cloth; the player
 *     eats it in bites (the beef's whole hunger, no more) and pays with a tip; coin and food conserved end to end; the
 *     waiter clears and the pot washer washes the plate and the cup back onto the shelf.</li>
 * <li><b>dn02</b>: a folk dines out the same way: sits, the waiter takes its order, it is cooked and served, eaten bite by
 *     bite, paid for to the hundredth with a tip, and its supper is had; the coin conserved.</li>
 * <li><b>dn03</b>: a dish with nothing to make it of is sold out on the card and cannot be ordered.</li>
 * <li><b>dn04</b>: the makings run out mid-service: the second roast is struck off, the player told and refunded.</li>
 * <li><b>dn05</b>: to take away: paid upfront, handed over at the counter, the coin into the till.</li>
 * <li><b>dn06</b>: brought to you: a runner walks the dish out to the player in town.</li>
 * <li><b>dn07</b>: the hospitality jobs: a town with a café, a tavern, a restaurant, an inn and a bakery staffs a head cook,
 *     a barkeep, a waiter, a baker, an innkeeper, a pot washer and a host out of its cooks; each a post on the books.</li>
 * <li><b>dn08</b>: a restart mid-order: the work in hand goes back in the queue and the order is still served.</li>
 * <li><b>dn09</b>: the restaurant as built: six tables with chairs, the kitchen's stations, the pass at the service
 *     counter, the sink, and the menu board by the door written up.</li>
 * </ul>
 * <p>Each on its own ground, x 1,880,000 to 1,899,999 at z 66,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class DiningGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    /** Evening, and dinner on. */
    private static final long EVENING = 12500L;

    private record Town(UUID id, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk,
                        Ledger.Building building, DiningPlaces.Place place, VillageFolkEntity cook, VillageFolkEntity waiter,
                        VillageFolkEntity washer) {}

    /** A Stone Age town with a restaurant: its head cook, waiter and pot washer in it, the stores a storehouse with coal,
     *  planks (for the service counter) and what is given, the house's plates and cups on its shelf. */
    private static Town town(GameTestHelper helper, int x, int others, ItemStack... goods) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        level.setDayTime(EVENING);
        Kit.hold(level, x, Z, 72);
        Kit.prepare(level, x, Z, 72);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        List<VillageFolkEntity> folk = new ArrayList<>();
        folk.add(first);
        for (int i = 0; i < 2 + others; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + (i % 6)).east(i / 6), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i);
            f.setJob(StationTask.NONE);
            folk.add(f);
        }
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, id);
        Villages.ageForTests(id, Villages.Age.STONE);
        List<ItemStack> all = new ArrayList<>(List.of(new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.COAL, 16)));
        all.addAll(List.of(goods));
        fill(id, store, all.toArray(new ItemStack[0]));
        BlockPos at = Kit.surface(level, x - 30, Z - 24);
        BuildGoal.stamp(level, DiningPlaces.RESTAURANT, at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, DiningPlaces.RESTAURANT, at, Direction.NORTH);
        Villages.builtAtForTests(id, DiningPlaces.RESTAURANT, at);
        Villages.noteProject(id, DiningPlaces.RESTAURANT, level.getGameTime());
        Villages.Village v = Villages.get(id);
        ServicePoints.HOURS_OFF_FOR_TESTS = true;
        ServicePoints.fitOutForTests(level, v);
        DiningPlaces.forget();
        Ledger.Building b = null;
        for (Ledger.Building x2 : Ledger.buildings(id)) if (x2.structure().equals(DiningPlaces.RESTAURANT)) b = x2;
        helper.assertTrue(b != null, "the restaurant is on the books");
        DiningPlaces.Place place = DiningPlaces.place(level, id, b);
        helper.assertTrue(place != null && !place.tables.isEmpty(), "the restaurant is a place to eat with tables");
        Dining.quickForTests(true);
        VillageFolkEntity cook = folk.get(0), waiter = folk.get(1), washer = folk.get(2);
        Hospitality.appointForTests(level, v, cook, Hospitality.Role.HEAD_COOK);
        Hospitality.appointForTests(level, v, waiter, Hospitality.Role.WAITER);
        Hospitality.appointForTests(level, v, washer, Hospitality.Role.POT_WASHER);
        for (VillageFolkEntity s : List.of(cook, waiter, washer)) {
            BlockPos in = place.centre();
            s.teleportTo(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
        }
        shelve(level, place, new ItemStack(DiningItems.PLATE.get(), 4), new ItemStack(DiningItems.CUP.get(), 4));
        return new Town(id, v, heart, store, folk, b, place, cook, waiter, washer);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        BlockState unit = StorehouseBlock.loose();
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

    /** The house's clean crockery onto its shelf (the kitchen's chest). */
    private static void shelve(ServerLevel level, DiningPlaces.Place p, ItemStack... things) {
        Container shelf = shelf(level, p);
        if (shelf == null) return;
        int slot = 0;
        for (ItemStack s : things) {
            while (slot < shelf.getContainerSize() && !shelf.getItem(slot).isEmpty()) slot++;
            if (slot < shelf.getContainerSize()) shelf.setItem(slot, s.copy());
        }
        shelf.setChanged();
    }

    private static Container shelf(ServerLevel level, DiningPlaces.Place p) {
        for (BuildGoal.Placement pl : BuildGoal.plan(p.building.structure(), p.building.anchor(), p.building.facing(), 13)) {
            if (level.getBlockEntity(pl.pos()) instanceof ChestBlockEntity c) return c;
        }
        return null;
    }

    private static int onShelf(ServerLevel level, DiningPlaces.Place p, Item it) {
        Container c = shelf(level, p);
        int n = 0;
        if (c != null) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    private static int inStores(StorehouseBlockEntity store, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < store.getContainerSize(); i++) if (what.test(store.getItem(i))) n += store.getItem(i).getCount();
        return n;
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

    private static void press(ServerPlayer p, ServiceRole role, String action, CompoundTag args) {
        ServicePoints.act(p, role.id, action, args, ServicePoints.nextNonceForTests(p));
    }

    private static CompoundTag id(String dish) {
        CompoundTag t = new CompoundTag();
        t.putString("id", dish);
        return t;
    }

    /** A player at table 1: stood by its chair, its screen open. */
    private static ServerPlayer atTable(GameTestHelper helper, Town t, int coins) {
        DiningPlaces.Table tb = t.place().tables.get(0);
        ServerPlayer p = player(helper, tb.seat(), coins);
        ServicePoints.openDining(p, t.place(), tb);
        return p;
    }

    /** A right-click on the setting here: on the plate's middle, or on the cup in its corner. */
    private static void use(ServerLevel level, ServerPlayer p, BlockPos cell, boolean cup) {
        Vec3 hit = cup ? new Vec3(cell.getX() + 0.8, cell.getY() + 0.2, cell.getZ() + 0.15) : new Vec3(cell.getX() + 0.5, cell.getY() + 0.1, cell.getZ() + 0.5);
        level.getBlockState(cell).useWithoutItem(level, p, new BlockHitResult(hit, Direction.UP, cell, false));
    }

    // ------------------------------------------------------------------ dn01

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "dn01_player_table_meal")
    public static void dn01_player_table_meal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack cider = Cafe.drink(Cafe.DRINKS.get(0));
        Town t = town(helper, 1880100, 0, new ItemStack(Items.BEEF, 4), cider.copyWithCount(3));
        DiningPlaces.Table tb = t.place().tables.get(0);
        BlockPos smoker = t.place().stations.get(DiningPlaces.Station.SMOKER);
        int[] start = new int[4];
        ServerPlayer[] pl = new ServerPlayer[1];
        int[] orderId = { -1 };
        helper.startSequence()
            .thenExecuteAfter(5, () -> {
                helper.assertTrue(smoker != null, "the restaurant has its smoker");
                pl[0] = atTable(helper, t, 30);
                start[0] = Ledger.coins(t.id());
                start[1] = t.waiter().purse();
                start[2] = inStores(t.store(), s -> s.is(Items.BEEF));
                start[3] = onShelf(level, t.place(), DiningItems.PLATE.get());
                press(pl[0], ServiceRole.RESTAURANT, "add", id("beef"));
                press(pl[0], ServiceRole.RESTAURANT, "add", id("drink/apple_cider"));
                press(pl[0], ServiceRole.RESTAURANT, "order", new CompoundTag());
                Dining.Order o = Dining.orderOf(pl[0].getUUID());
                helper.assertTrue(o != null && o.lines.size() == 2, "the order is in: " + ServicePoints.messageForTests(pl[0]));
                helper.assertTrue(o.lines.get(0).drink(), "the drink goes first");
                orderId[0] = o.id;
                helper.assertTrue(Market.coinsHeld(pl[0]) == 30, "nothing paid yet: it is paid from the bill");
            })
            .thenWaitUntil(() -> {
                Dining.Order o = Dining.order(orderId[0]);
                helper.assertTrue(o != null && o.allServed(), "served: " + (o == null ? "gone" : steps(o)));
            })
            .thenExecute(() -> {
                Dining.Order o = Dining.order(orderId[0]);
                Dining.Line beef = o.lines.get(1);
                helper.assertTrue(beef.cook.equals(t.cook().displayNameCap()), "cooked by the head cook, " + t.cook().displayNameCap() + ": " + beef.cook);
                helper.assertTrue(smoker.equals(beef.at), "the roast at the smoker: " + beef.at + " not " + smoker);
                helper.assertTrue(inStores(t.store(), s -> s.is(Items.BEEF)) == start[2] - 1, "one raw beef out of the stores");
                helper.assertTrue(inStores(t.store(), s -> s.is(Items.COOKED_BEEF)) == 0, "the roast is on the plate, not left in the stores");
                BlockState st = level.getBlockState(tb.cell());
                helper.assertTrue(st.getBlock() instanceof PlaceSettingBlock && st.getValue(PlaceSettingBlock.CLOTH)
                    && st.getValue(PlaceSettingBlock.HOUSE), "a house's setting laid on the table's cloth: " + st);
                PlaceSettingBlockEntity be = (PlaceSettingBlockEntity) level.getBlockEntity(tb.cell());
                helper.assertTrue(be.food().is(Items.COOKED_BEEF) && !be.drink().isEmpty(), "the roast on the plate, the cider in the cup");
                helper.assertTrue(onShelf(level, t.place(), DiningItems.PLATE.get()) == start[3] - 1, "a clean plate off the shelf");
                // Eaten: every bite and every sip.
                pl[0].getFoodData().setFoodLevel(2);
                pl[0].getFoodData().setSaturation(0);
                for (int i = 0; i < 6 && !be.food().isEmpty(); i++) use(level, pl[0], tb.cell(), false);
                for (int i = 0; i < 6 && !be.drink().isEmpty(); i++) use(level, pl[0], tb.cell(), true);
                helper.assertTrue(be.food().isEmpty() && be.drink().isEmpty(), "eaten and drunk to the last");
                helper.assertTrue(pl[0].getFoodData().getFoodLevel() == 2 + 8, "the roast's eight hunger, in its bites: " + pl[0].getFoodData().getFoodLevel());
                helper.assertTrue(o.finished(), "every line eaten: " + steps(o));
                int due = o.total();
                CompoundTag tip = new CompoundTag();
                tip.putInt("tip", 1);
                press(pl[0], ServiceRole.RESTAURANT, "pay", tip);
                Kit.log("dn01 paid " + due + " and a tip; " + ServicePoints.messageForTests(pl[0]));
                helper.assertTrue(Market.coinsHeld(pl[0]) == 30 - due - 1, "the bill and a tip out of the pack");
                helper.assertTrue(Ledger.coins(t.id()) - start[0] == due, "the bill into the till");
                helper.assertTrue(t.waiter().purse() - start[1] == 1, "the tip to the waiter");
                Kit.noLeftoverPlayers(level);
            })
            .thenWaitUntil(() -> helper.assertTrue(!(level.getBlockState(tb.cell()).getBlock() instanceof PlaceSettingBlock),
                "the waiter clears the table"))
            .thenWaitUntil(() -> helper.assertTrue(PlaceSettingBlock.isCloth(level.getBlockState(tb.cell())), "the cloth back to its carpet"))
            .thenWaitUntil(() -> helper.assertTrue(onShelf(level, t.place(), DiningItems.PLATE.get()) == start[3]
                && onShelf(level, t.place(), DiningItems.CUP.get()) == 4, "the plate and the cup washed and back on the shelf"))
            .thenExecute(() -> helper.assertTrue(Dining.booksForTests(t.id())[1] >= 2, "the pot washer washed them"))
            .thenSucceed();
    }

    private static String steps(Dining.Order o) {
        StringBuilder sb = new StringBuilder();
        for (Dining.Line l : o.lines) sb.append(l.dish).append('=').append(l.step).append(' ');
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ dn02

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "dn02_folk_dines_out")
    public static void dn02_folk_dines_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack cider = Cafe.drink(Cafe.DRINKS.get(0));
        Town t = town(helper, 1881300, 1, new ItemStack(Items.BEEF, 6), cider.copyWithCount(4));
        VillageFolkEntity diner = t.folk().get(3);
        int[] start = new int[3];
        helper.startSequence()
            .thenExecuteAfter(5, () -> {
                diner.earn(40);
                start[0] = diner.purse();
                start[1] = Ledger.coins(t.id());
                start[2] = t.waiter().purse();
                helper.assertTrue(Diners.dineForTests(level, diner, t.place()), "a free table");
                DiningPlaces.Table tb = t.place().table(Diners.tableForTests(diner));
                diner.teleportTo(tb.seat().getX() + 0.5, tb.seat().getY() + 0.5, tb.seat().getZ() + 0.5);
            })
            .thenWaitUntil(() -> helper.assertTrue(Diners.orderForTests(diner) > 0, "the waiter takes its order: " + Diners.stageForTests(diner)))
            .thenWaitUntil(() -> helper.assertTrue(Diners.stageForTests(diner) == null, "eaten, paid and gone: " + Diners.stageForTests(diner)
                + " " + (Dining.order(Diners.orderForTests(diner)) == null ? "" : steps(Dining.order(Diners.orderForTests(diner))))))
            .thenExecute(() -> {
                int spent = start[0] - diner.purse(), till = Ledger.coins(t.id()) - start[1], tip = t.waiter().purse() - start[2];
                Kit.log("dn02 a folk's dinner: spent " + spent + ", the till +" + till + ", the waiter's tip " + tip + "; ate " + diner.meals().lastWhat());
                helper.assertTrue(spent > 0 && spent == till + tip, "the coin conserved: spent " + spent + " = till " + till + " + tip " + tip);
                helper.assertTrue(!diner.meals().lastWhat().isEmpty(), "its meal booked");
            })
            .thenSucceed();
    }

    // ------------------------------------------------------------------ dn03

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "dn03_sold_out")
    public static void dn03_sold_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1882500, 0, new ItemStack(Items.BEEF, 2));
        helper.runAtTickTime(5, () -> {
            List<DiningMenu.Line> card = DiningMenu.card(level, t.place(), DiningMenu.Meal.DINNER);
            DiningMenu.Line mutton = null, beef = null;
            for (DiningMenu.Line l : card) {
                if (l.dish().id().equals("mutton")) mutton = l;
                if (l.dish().id().equals("beef")) beef = l;
            }
            helper.assertTrue(mutton != null && mutton.soldOut(), "no mutton in the stores: sold out");
            helper.assertTrue(beef != null && !beef.soldOut(), "beef can be had");
            ServerPlayer p = atTable(helper, t, 20);
            press(p, ServiceRole.RESTAURANT, "add", id("mutton"));
            helper.assertTrue(ServicePoints.messageForTests(p).contains("sold out"), "told it is sold out: " + ServicePoints.messageForTests(p));
            press(p, ServiceRole.RESTAURANT, "order", new CompoundTag());
            helper.assertTrue(Dining.orderOf(p.getUUID()) == null, "nothing ordered");
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ dn04

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "dn04_struck_mid_service")
    public static void dn04_struck_mid_service(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1883700, 0, new ItemStack(Items.BEEF, 1));
        ServerPlayer[] pl = new ServerPlayer[1];
        int[] orderId = { -1 };
        int[] till = { 0 };
        helper.startSequence()
            .thenExecuteAfter(5, () -> {
                pl[0] = atTable(helper, t, 30);
                press(pl[0], ServiceRole.RESTAURANT, "add", id("beef"));
                press(pl[0], ServiceRole.RESTAURANT, "add", id("beef"));
                CompoundTag on = new CompoundTag();
                on.putBoolean("on", true);
                press(pl[0], ServiceRole.RESTAURANT, "payNow", on);
                till[0] = Ledger.coins(t.id());
                press(pl[0], ServiceRole.RESTAURANT, "order", new CompoundTag());
                Dining.Order o = Dining.orderOf(pl[0].getUUID());
                helper.assertTrue(o != null && o.lines.size() == 2 && o.paid, "two roasts ordered and paid: " + ServicePoints.messageForTests(pl[0]));
                orderId[0] = o.id;
            })
            .thenWaitUntil(() -> {
                Dining.Order o = Dining.order(orderId[0]);
                helper.assertTrue(o != null && o.allServed(), "the one served, the other struck: " + (o == null ? "gone" : steps(o)));
            })
            .thenExecute(() -> {
                Dining.Order o = Dining.order(orderId[0]);
                int struck = 0;
                for (Dining.Line l : o.lines) if (l.step == Dining.Step.STRUCK) struck++;
                helper.assertTrue(struck == 1, "one roast struck off: " + steps(o));
                helper.assertTrue(Market.coinsHeld(pl[0]) == 30 - o.total(), "paid for the one only: " + Market.coinsHeld(pl[0]) + " left of 30, bill " + o.total());
                helper.assertTrue(Ledger.coins(t.id()) - till[0] == o.total(), "the till keeps only what was served");
                Kit.noLeftoverPlayers(level);
            })
            .thenSucceed();
    }

    // ------------------------------------------------------------------ dn05

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "dn05_takeaway")
    public static void dn05_takeaway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack cider = Cafe.drink(Cafe.DRINKS.get(0));
        Town t = town(helper, 1884900, 0, cider.copyWithCount(2));
        ServerPlayer[] pl = new ServerPlayer[1];
        int[] till = { 0 };
        helper.startSequence()
            .thenExecuteAfter(5, () -> {
                BlockPos front = t.place().pass.relative(Direction.SOUTH);
                pl[0] = player(helper, front, 20);
                ServicePoints.openDining(pl[0], t.place(), null);
                press(pl[0], ServiceRole.RESTAURANT, "add", id("drink/apple_cider"));
                CompoundTag m = new CompoundTag();
                m.putString("mode", "takeaway");
                press(pl[0], ServiceRole.RESTAURANT, "mode", m);
                till[0] = Ledger.coins(t.id());
                press(pl[0], ServiceRole.RESTAURANT, "order", new CompoundTag());
                helper.assertTrue(Dining.orderOf(pl[0].getUUID()) != null, "ordered to take away: " + ServicePoints.messageForTests(pl[0]));
                helper.assertTrue(Market.coinsHeld(pl[0]) < 20, "paid upfront");
            })
            .thenWaitUntil(() -> helper.assertTrue(carried(pl[0], s -> ItemStack.isSameItemSameComponents(s, cider)) == 1, "handed over"))
            .thenExecute(() -> {
                helper.assertTrue(Ledger.coins(t.id()) - till[0] == 20 - Market.coinsHeld(pl[0]), "the coin into the till");
                Kit.noLeftoverPlayers(level);
            })
            .thenSucceed();
    }

    // ------------------------------------------------------------------ dn06

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "dn06_delivery")
    public static void dn06_delivery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack cider = Cafe.drink(Cafe.DRINKS.get(0));
        Town t = town(helper, 1886100, 0, cider.copyWithCount(2));
        ServerPlayer[] pl = new ServerPlayer[1];
        helper.startSequence()
            .thenExecuteAfter(5, () -> {
                pl[0] = player(helper, t.place().board != null ? t.place().board : t.place().pass, 20);
                ServicePoints.openDining(pl[0], t.place(), null);
                press(pl[0], ServiceRole.RESTAURANT, "add", id("drink/apple_cider"));
                CompoundTag m = new CompoundTag();
                m.putString("mode", "delivery");
                press(pl[0], ServiceRole.RESTAURANT, "mode", m);
                press(pl[0], ServiceRole.RESTAURANT, "order", new CompoundTag());
                helper.assertTrue(Dining.orderOf(pl[0].getUUID()) != null, "ordered to be brought: " + ServicePoints.messageForTests(pl[0]));
                BlockPos away = Kit.surface(level, t.heart().getX(), t.heart().getZ() + 14);
                pl[0].teleportTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
            })
            .thenWaitUntil(() -> helper.assertTrue(carried(pl[0], s -> ItemStack.isSameItemSameComponents(s, cider)) == 1, "brought out to the player"))
            .thenExecute(() -> {
                helper.assertTrue(t.waiter().distanceTo(pl[0]) < 6, "by the runner's own hand: " + t.waiter().distanceTo(pl[0]));
                Kit.noLeftoverPlayers(level);
            })
            .thenSucceed();
    }

    // ------------------------------------------------------------------ dn07

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "dn07_hospitality_jobs")
    public static void dn07_hospitality_jobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1887300, 30);
        helper.runAtTickTime(5, () -> {
            for (String s : List.of("cafe", "tavern", "inn", "bakery")) {
                Ledger.built(t.id(), s, t.heart().offset(60, 0, 60), Direction.NORTH);
                Villages.noteProject(t.id(), s, level.getGameTime());
            }
            List<VillageFolkEntity> cooks = new ArrayList<>();
            for (int i = 3; i < 12; i++) {
                VillageFolkEntity f = t.folk().get(i);
                f.setJob(StationTask.COOK);
                cooks.add(f);
            }
            helper.assertTrue(Hospitality.staffWanted(t.id()) >= 5, "the cook's share raised for the jobs: " + Hospitality.staffWanted(t.id()));
            Hospitality.tickForTests(level, t.v());
            for (Hospitality.Role r : List.of(Hospitality.Role.HEAD_COOK, Hospitality.Role.BARKEEP, Hospitality.Role.WAITER, Hospitality.Role.BAKER,
                    Hospitality.Role.INNKEEPER, Hospitality.Role.POT_WASHER, Hospitality.Role.HOST)) {
                List<VillageFolkEntity> in = Hospitality.in(t.id(), r);
                helper.assertTrue(!in.isEmpty(), "a " + r.title + " taken on");
                if (r != Hospitality.Role.HEAD_COOK) {
                    JobWorth.Post post = JobWorth.postOf(in.get(0));
                    helper.assertTrue(post.key.equals("COOK/" + r.name()), r.title + "'s post on the books: " + post.key);
                }
                helper.assertTrue(Hospitality.badge(in.get(0)).equals(r.badge), "its job on its nameplate");
            }
            helper.assertTrue(!Hospitality.in(t.id(), Hospitality.Role.LINE_COOK).isEmpty(), "and cooks left at the stove");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ dn08

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "dn08_restart_mid_order")
    public static void dn08_restart_mid_order(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1888500, 0, new ItemStack(Items.BEEF, 2));
        ServerPlayer[] pl = new ServerPlayer[1];
        int[] orderId = { -1 };
        helper.startSequence()
            .thenExecuteAfter(5, () -> {
                pl[0] = atTable(helper, t, 30);
                press(pl[0], ServiceRole.RESTAURANT, "add", id("beef"));
                press(pl[0], ServiceRole.RESTAURANT, "order", new CompoundTag());
                Dining.Order o = Dining.orderOf(pl[0].getUUID());
                helper.assertTrue(o != null, "ordered");
                orderId[0] = o.id;
            })
            .thenWaitUntil(() -> helper.assertTrue("COOK".equals(Dining.jobForTests(t.cook())), "at the stove"))
            .thenExecute(() -> {
                Dining.restartForTests(level);
                Dining.Order o = Dining.order(orderId[0]);
                helper.assertTrue(o != null && o.lines.get(0).step != Dining.Step.COOKING, "read back, the stove's work back in the queue: "
                    + (o == null ? "gone" : steps(o)));
                helper.assertTrue(Dining.jobForTests(t.cook()) == null, "nobody's hands full after the restart");
            })
            .thenWaitUntil(() -> {
                Dining.Order o = Dining.order(orderId[0]);
                helper.assertTrue(o != null && o.allServed(), "served after all: " + (o == null ? "gone" : steps(o)));
            })
            .thenExecute(() -> Kit.noLeftoverPlayers(level))
            .thenSucceed();
    }

    // ------------------------------------------------------------------ dn09

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "dn09_restaurant_as_built")
    public static void dn09_restaurant_as_built(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1889700, 0, new ItemStack(Items.BEEF, 2), new ItemStack(Items.OAK_SIGN, 2));
        helper.runAtTickTime(5, () -> {
            DiningPlaces.Place p = t.place();
            Kit.log("dn09 the restaurant: " + p.tables.size() + " tables, stations " + p.stations.keySet() + ", pass " + p.pass + ", wash " + p.wash);
            helper.assertTrue(p.tables.size() == 6, "six tables, each with its chair: " + p.tables.size());
            for (DiningPlaces.Station s : DiningPlaces.Station.values()) helper.assertTrue(p.stations.containsKey(s), "the kitchen's " + s);
            helper.assertTrue(level.getBlockState(p.pass).getBlock() instanceof com.jrpetty.mcassistant.block.ServiceCounterBlock, "the pass at the service counter");
            helper.assertTrue(level.getBlockState(p.wash).getBlock() instanceof net.minecraft.world.level.block.AbstractCauldronBlock, "the sink");
            helper.assertTrue(ServicePoints.status(level, t.id(), ServiceRole.RESTAURANT, null).open(), "open with its staff in");
            Dining.round(level, t.v());
            helper.assertTrue(p.board != null && level.getBlockState(p.board).getBlock() instanceof WallSignBlock, "the menu board by the door");
            helper.succeed();
        });
    }
}
