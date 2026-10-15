package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Buyers;
import com.jrpetty.mcassistant.entity.EarnBoard;
import com.jrpetty.mcassistant.entity.Earnings;
import com.jrpetty.mcassistant.entity.Goods;
import com.jrpetty.mcassistant.entity.HaulContracts;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PlayerServices;
import com.jrpetty.mcassistant.entity.ServicePoints;
import com.jrpetty.mcassistant.entity.ServiceQueue;
import com.jrpetty.mcassistant.entity.ServiceRole;
import com.jrpetty.mcassistant.entity.ShiftRole;
import com.jrpetty.mcassistant.entity.Shifts;
import com.jrpetty.mcassistant.entity.ShopDesk;
import com.jrpetty.mcassistant.entity.Store;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WantedBoard;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [earning] Ways for a player to earn the towns' coin, each paid by somebody real (Buyers, WantedBoard, Shifts,
 * ShiftTasks, HaulContracts, BankCounter, PlayerServices' bounty, Earnings, EarnBoard).
 * <ul>
 * <li><b>er01</b>: selling to the store pays out of the treasury, and the goods land in its stock: board goods in the
 *     stores, its bench's makings in its makings chest.</li>
 * <li><b>er02</b>: a glutted line pays less, then nothing; selling all stops where the counter has what it keeps.</li>
 * <li><b>er03</b>: buying a lot and selling it straight back loses money.</li>
 * <li><b>er04</b>: a Wanted order is filled and paid out of the treasury, into the stores, and comes off the board.</li>
 * <li><b>er05</b>: a counter shift: taken, a customer served, paid at once, rated by speed (and a mistake for serving
 *     out of turn); the HUD says what the server has.</li>
 * <li><b>er06</b>: a slow task pays less than a quick one; skill raises pay; three poor shifts in a row lose a standing
 *     job, with a warning after two.</li>
 * <li><b>er07</b>: a bounty kill on a busy night pays out of the treasury, booked, up to the night's purse.</li>
 * <li><b>er08</b>: a haul contract between two towns: the fee in escrow, the load out of the one's stores into the
 *     other's, the fee and the deposit paid on delivery, the goods paid for town to town.</li>
 * <li><b>er09</b>: the money changer conserves value: gold for coin and back at the mint's rate.</li>
 * <li><b>er10</b>: coin is conserved end to end: the treasury, the folk's purses, the player and the escrow sum to the
 *     same through selling, an order, a shift and a tip.</li>
 * <li><b>er11</b>: the Earnings page's ways to earn are the town's real state: the order, the open shift, and gone or
 *     unpaid when they are.</li>
 * <li><b>er12</b>: a hauler's shift: the load from the storehouse (a deposit held) into a workshop chest short of it,
 *     the deposit back and the task paid.</li>
 * <li><b>er13</b>: a farm hand's shift: a ripe plot marked, broken (its harvest into the stores, the seed to hand),
 *     planted back, the task paid.</li>
 * </ul>
 *
 * <p>Each on its own ground, x 1,880,000 to 1,899,999 at z 66,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class EarningGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    private record Town(UUID id, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, VillageFolkEntity keeper,
                        List<VillageFolkEntity> folk, BlockPos shopAt) {}

    /** A town in the Iron Age with a shop (its counter fitted, the keeper behind it), a storehouse, and coin. */
    private static Town town(GameTestHelper helper, int x, int z, int others, long time, boolean reset) {
        ServerLevel level = helper.getLevel();
        if (reset) {
            Kit.reset(level);
            Kit.noLeftoverPlayers(level);
            Shifts.resetForTests();
            ServicePoints.resetForTests();
        }
        level.setDayTime(time);
        Kit.hold(level, x, z, 72);
        Kit.prepare(level, x, z, 72);
        BlockPos heart = Kit.surface(level, x, z);
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
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c && c != (Object) store) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        keeper.setJob(StationTask.SHOP);
        fill(id, store, new ItemStack(Items.OAK_PLANKS, 64));
        BlockPos shopAt = Kit.surface(level, x - 40, z - 20);
        BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "shop", shopAt, Direction.NORTH);
        Villages.builtAtForTests(id, "shop", shopAt);
        Villages.Village v = Villages.get(id);
        ServicePoints.fitOutForTests(level, v);
        ServicePoints.Point point = point(level, id, ServiceRole.SHOP);
        if (point != null && point.stand() != null) keeper.teleportTo(point.stand().getX() + 0.5, point.stand().getY(), point.stand().getZ() + 0.5);
        Ledger.addCoins(id, 200);
        return new Town(id, v, heart, store, keeper, folk, shopAt);
    }

    private static ServicePoints.Point point(ServerLevel level, UUID id, ServiceRole role) {
        List<ServicePoints.Point> all = ServicePoints.of(level, id, role);
        return all.isEmpty() ? null : all.get(0);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
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

    private static void fill(UUID id, StorehouseBlockEntity store, ItemStack... goods) {
        for (ItemStack g : goods) {
            ItemStack left = g.copy();
            while (!left.isEmpty()) {
                int slot = -1;
                for (int i = 0; i < store.getContainerSize(); i++) if (store.getItem(i).isEmpty()) { slot = i; break; }
                if (slot < 0) break;
                store.setItem(slot, left.split(left.getMaxStackSize()));
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

    private static void press(ServerPlayer p, ServiceRole role, String action, CompoundTag args) {
        ServicePoints.act(p, role.id, action, args, ServicePoints.nextNonceForTests(p));
    }

    private static CompoundTag sell(ItemStack one, int n) {
        CompoundTag a = new CompoundTag();
        a.putString("id", Goods.lineId(one));
        a.putInt("n", n);
        return a;
    }

    private static ServicePoints.Visit visit(ServerLevel level, Town t, ServiceRole role, ServerPlayer p) {
        return new ServicePoints.Visit(level, t.v(), role, point(level, t.id(), role), p, t.keeper(), new CompoundTag());
    }

    private static int purses(Town t) {
        int n = 0;
        for (var a : Villages.folkOf(t.id())) if (a instanceof VillageFolkEntity f) n += f.purse();
        return n;
    }

    // ============================================================ selling

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "er01_sell_to_the_store")
    public static void er01_sell_to_the_store(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1940500, Z, 0, 2000L, true);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 0);
            pl.getInventory().add(new ItemStack(Items.WHEAT, 16));
            pl.getInventory().add(new ItemStack(Items.COAL, 16));
            ServicePoints.openAt(pl, p.pos());
            ItemStack wheat = new ItemStack(Items.WHEAT), stick = new ItemStack(Items.COAL);
            int treasury0 = Ledger.coins(t.id()), stores0 = Market.stock(level, t.id(), s -> s.is(Items.WHEAT));
            int expect = Buyers.lotPriceForTests(level, t.id(), ServiceRole.SHOP, wheat);
            press(pl, ServiceRole.SHOP, "sell", sell(wheat, ShopDesk.sellLotForTests(wheat)));
            int paid = Market.coinsHeld(pl);
            int stores1 = Market.stock(level, t.id(), s -> s.is(Items.WHEAT));
            BlockPos chest = WorkStock.chestOf(level, t.id(), StationTask.SHOP);
            int chest0 = count(level, chest, Items.COAL);
            press(pl, ServiceRole.SHOP, "sell", sell(stick, 16));
            int paid2 = Market.coinsHeld(pl) - paid, chest1 = count(level, chest, Items.COAL);
            Kit.log("er01 wheat: paid " + paid + " (offered " + expect + "), treasury " + treasury0 + " -> " + Ledger.coins(t.id()) + ", stores "
                + stores0 + " -> " + stores1 + "; coal: paid " + paid2 + ", makings chest " + chest0 + " -> " + chest1 + "; booked "
                + Earnings.earnedTodayForTests(pl, t.id(), Earnings.Source.SELL) + "; said " + ServicePoints.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(expect > 0 && paid == expect, "the lot paid what the counter offered: " + paid + " of " + expect);
            helper.assertTrue(treasury0 - Ledger.coins(t.id()) == paid + paid2, "out of the treasury, nothing minted");
            helper.assertTrue(stores1 - stores0 == ShopDesk.sellLotForTests(wheat), "the wheat in the stores");
            helper.assertTrue(chest != null && chest1 - chest0 == 16 && paid2 >= 0, "the coal in the store's makings chest: " + chest0 + " -> " + chest1);
            helper.assertTrue(Earnings.earnedTodayForTests(pl, t.id(), Earnings.Source.SELL) == paid + paid2, "booked to the player's earnings");
            helper.succeed();
        });
    }

    private static int count(ServerLevel level, BlockPos chest, net.minecraft.world.item.Item it) {
        if (chest == null || !(level.getBlockEntity(chest) instanceof Container c)) return -1;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "er02_glut")
    public static void er02_glut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1941700, Z, 0, 2000L, true);
        helper.runAtTickTime(5, () -> {
            ItemStack wheat = new ItemStack(Items.WHEAT);
            int[] want = Buyers.wantForTests(level, t.id(), ServiceRole.SHOP, wheat);
            int keep = want[0];
            int empty = Buyers.lotPriceForTests(level, t.id(), ServiceRole.SHOP, wheat);
            fill(t.id(), t.store(), new ItemStack(Items.WHEAT, keep * 3 / 4));
            int threeQuarters = Buyers.lotPriceForTests(level, t.id(), ServiceRole.SHOP, wheat);
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 0);
            pl.getInventory().add(new ItemStack(Items.WHEAT, 64));
            ServicePoints.Result r = Buyers.sellForTests(visit(level, t, ServiceRole.SHOP, pl), wheat, 64);
            int left = carried(pl, s -> s.is(Items.WHEAT)), stock = Market.stock(level, t.id(), s -> s.is(Items.WHEAT));
            int full = Buyers.lotPriceForTests(level, t.id(), ServiceRole.SHOP, wheat);
            Kit.log("er02 keeps " + keep + " wheat: a lot pays " + empty + " empty, " + threeQuarters + " three quarters full, " + full
                + " full; selling 64 sold " + (64 - left) + " for " + Market.coinsHeld(pl) + ", stock now " + stock + "; said " + r.message());
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(empty > 0 && threeQuarters < empty, "a filling line pays less: " + empty + " -> " + threeQuarters);
            helper.assertTrue(full == 0, "a full one pays nothing: " + full);
            helper.assertTrue(left > 0 && stock - keep < ShopDesk.sellLotForTests(wheat), "selling all stopped where it had what it keeps: " + left + " left");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "er03_no_loop")
    public static void er03_no_loop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1942900, Z, 0, 2000L, true);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            Store.putForTests(level, t.id(), new ItemStack(Items.BREAD, 32));
            ServerPlayer pl = player(helper, p.queueSpot(level, 0), 100);
            ServicePoints.openAt(pl, p.pos());
            String id = Goods.lineId(new ItemStack(Items.BREAD));
            CompoundTag b = new CompoundTag();
            b.putInt(id, 8);
            CompoundTag a = new CompoundTag();
            a.put("basket", b);
            press(pl, ServiceRole.SHOP, "basket", a);
            int coins0 = Market.coinsHeld(pl);
            press(pl, ServiceRole.SHOP, "buy", new CompoundTag());
            int spent = coins0 - Market.coinsHeld(pl);
            int bread = carried(pl, s -> s.is(Items.BREAD));
            int coins1 = Market.coinsHeld(pl);
            press(pl, ServiceRole.SHOP, "sell", sell(new ItemStack(Items.BREAD), bread));
            int back = Market.coinsHeld(pl) - coins1;
            Kit.log("er03 bought " + bread + " bread for " + spent + ", sold back for " + back + "; said " + ServicePoints.messageForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(bread == 8 && spent > 0, "bought the bread: " + bread + " for " + spent);
            helper.assertTrue(back < spent, "selling it straight back loses money: " + back + " back of " + spent);
            helper.succeed();
        });
    }

    // ============================================================ the Wanted board

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "er04_wanted_order")
    public static void er04_wanted_order(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1944100, Z, 0, 2000L, true);
        helper.runAtTickTime(5, () -> {
            WantedBoard.Order o = WantedBoard.postForTests(level, t.v(), Items.COAL, 16);
            ServerPlayer pl = player(helper, t.heart(), 0);
            pl.getInventory().add(new ItemStack(Items.COAL, 20));
            int treasury0 = Ledger.coins(t.id()), stores0 = Market.stock(level, t.id(), s -> s.is(Items.COAL));
            String said = WantedBoard.deliverForTests(level, pl, t.v(), o.id);
            int paid = Market.coinsHeld(pl), left = carried(pl, s -> s.is(Items.COAL));
            int stores1 = Market.stock(level, t.id(), s -> s.is(Items.COAL));
            boolean stillUp = false;
            for (WantedBoard.Order x : WantedBoard.orders(level, t.v())) stillUp |= x.id.equals(o.id) && x.left() > 0;
            String board = WantedBoard.boardLine(level, t.id());
            Kit.log("er04 order " + o.count + " coal at " + o.eachX100 + "/100 each: paid " + paid + ", treasury " + treasury0 + " -> "
                + Ledger.coins(t.id()) + ", stores " + stores0 + " -> " + stores1 + ", left in pack " + left + ", still up " + stillUp + "; said '" + said
                + "'; board " + board);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(said.isEmpty() && paid == o.pay(16) && paid > 0, "paid what the order said for sixteen: " + paid);
            helper.assertTrue(treasury0 - Ledger.coins(t.id()) == paid, "out of the treasury");
            helper.assertTrue(stores1 - stores0 == 16 && left == 4, "sixteen into the stores, the rest kept");
            helper.assertTrue(!stillUp, "the filled order is off the board");
            helper.assertTrue(o.eachX100 <= Math.ceil(com.jrpetty.mcassistant.entity.PriceIndex.each(level, t.id(), new ItemStack(Items.COAL)) * 100),
                "never above the town's own price: no loop");
            helper.succeed();
        });
    }

    // ============================================================ shifts

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "er05_counter_shift")
    public static void er05_counter_shift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1945300, Z, 3, 2000L, true);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            ServerPlayer pl = player(helper, p.stand() != null ? p.stand() : p.pos().north(), 0);
            String took = Shifts.take(pl, t.id(), ShiftRole.COUNTER.id, false);
            helper.assertTrue(took.isEmpty() && Shifts.onShift(pl), "the counter shift is taken: " + took);
            VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
            for (VillageFolkEntity f : List.of(a, b)) f.teleportTo(p.queueSpot(level, 0).getX() + 0.5, p.queueSpot(level, 0).getY(), p.queueSpot(level, 0).getZ() + 0.5);
            ServiceQueue.turn(a, level, ServiceRole.SHOP, "a loaf of bread");
            ServiceQueue.turn(b, level, ServiceRole.SHOP, "some candles");
            Shifts.secondForTests(level, pl);
            String task = Shifts.taskForTests(pl);
            int treasury0 = Ledger.coins(t.id());
            String said = Shifts.serveForTests(level, pl, a.getUUID());
            int[] st = Shifts.stateForTests(pl);
            String last1 = Shifts.lastForTests(pl);
            CompoundTag hud = Shifts.hudForTests(pl);
            ServiceQueue.Turn turn = ServiceQueue.turn(a, level, ServiceRole.SHOP, "");
            // A second customer, and a third: the third served out of turn.
            VillageFolkEntity c = t.folk().get(2);
            c.teleportTo(p.queueSpot(level, 0).getX() + 0.5, p.queueSpot(level, 0).getY(), p.queueSpot(level, 0).getZ() + 0.5);
            ServiceQueue.turn(c, level, ServiceRole.SHOP, "");
            Shifts.secondForTests(level, pl);
            Shifts.serveForTests(level, pl, c.getUUID());
            String last2 = Shifts.lastForTests(pl);
            int[] st2 = Shifts.stateForTests(pl);
            Kit.log("er05 task '" + task + "'; served: '" + said + "' " + last1 + "; state " + java.util.Arrays.toString(st) + "; then "
                + last2 + " " + java.util.Arrays.toString(st2) + "; treasury " + treasury0 + " -> " + Ledger.coins(t.id()) + "; booked "
                + Earnings.earnedTodayForTests(pl, t.id(), Earnings.Source.SHIFT) + "; hud " + hud + "; the customer's turn " + turn);
            helper.assertTrue(task != null && task.contains(a.displayNameCap()), "the first in line is the task: " + task);
            helper.assertTrue(said.isEmpty() && st != null && st[0] == 1 && st[2] == 5, "served quickly: one task, five stars: " + last1);
            helper.assertTrue(turn == ServiceQueue.Turn.GO, "the customer served goes to buy");
            helper.assertTrue(last2.contains("mistake") && st2[2] - st[2] <= 4, "out of turn is a mistake: " + last2);
            helper.assertTrue(hud != null && hud.getInt("tasks") == st[0] && hud.getInt("pay") == st[1]
                && hud.getString("title").equals(ShiftRole.COUNTER.title), "the HUD says what the server has: " + hud);
            int booked = Earnings.earnedTodayForTests(pl, t.id(), Earnings.Source.SHIFT);
            helper.assertTrue(booked == st2[1] && treasury0 - Ledger.coins(t.id()) == st2[1], "paid at once, out of the treasury, booked: " + booked);
            Shifts.endForTests(level, pl);
            CompoundTag end = Shifts.hudForTests(pl);
            helper.assertTrue(!Shifts.onShift(pl) && end != null && end.contains("summary") && end.getCompound("summary").getInt("tasks") == 2,
                "the end of the shift is summed up: " + end);
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "er06_rating_skill_and_letgo")
    public static void er06_rating_skill_and_letgo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1946500, Z, 4, 2000L, true);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            ServerPlayer pl = player(helper, p.stand() != null ? p.stand() : p.pos().north(), 0);
            // A quick task, and a slow one: the slow pays less.
            int[] quick = serveOne(level, pl, t, p, t.folk().get(0), 0);
            int[] slow = serveOne(level, pl, t, p, t.folk().get(1), 2400);
            // The same quick task by a master pays more.
            Shifts.xpForTests(pl, ShiftRole.COUNTER, 10_000);
            int[] master = serveOne(level, pl, t, p, t.folk().get(2), 0);
            Shifts.xpForTests(pl, ShiftRole.COUNTER, 0);
            Kit.log("er06 owed (hundredths) quick " + quick[0] + " (" + quick[1] + " stars), slow " + slow[0] + " (" + slow[1] + " stars), master "
                + master[0] + " (" + master[1] + " stars)");
            helper.assertTrue(slow[1] < quick[1] && slow[0] < quick[0], "a slow task earns fewer stars and less pay");
            helper.assertTrue(master[0] > quick[0], "skill raises pay");
            // Three poor shifts in a row (nothing done) lose a standing job; a warning after two.
            List<String> said = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                said.add(Shifts.take(pl, t.id(), ShiftRole.COUNTER.id, true));
                Shifts.endForTests(level, pl);
                said.add(String.valueOf(Shifts.standingForTests(pl)));
            }
            String again = Shifts.take(pl, t.id(), ShiftRole.COUNTER.id, true);
            Kit.log("er06 standing job: " + said + "; again: " + again);
            helper.assertTrue("counter".equals(said.get(1)) && "counter".equals(said.get(3)), "kept through two poor shifts (a warning)");
            helper.assertTrue("null".equals(said.get(5)), "let go after the third");
            helper.assertTrue(again.contains("let go"), "and not taken back standing at once: " + again);
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    /** One customer served on a fresh counter shift, the task made to have taken so long: {owed in hundredths, stars}. */
    private static int[] serveOne(ServerLevel level, ServerPlayer pl, Town t, ServicePoints.Point p, VillageFolkEntity f, long took) {
        Shifts.endForTests(level, pl);
        Shifts.take(pl, t.id(), ShiftRole.COUNTER.id, false);
        f.teleportTo(p.queueSpot(level, 0).getX() + 0.5, p.queueSpot(level, 0).getY(), p.queueSpot(level, 0).getZ() + 0.5);
        ServiceQueue.turn(f, level, ServiceRole.SHOP, "");
        Shifts.secondForTests(level, pl);
        if (took > 0) Shifts.ageTaskForTests(pl, took);
        Shifts.serveForTests(level, pl, f.getUUID());
        int[] st = Shifts.stateForTests(pl);
        int[] out = st == null ? new int[]{ 0, 0 } : new int[]{ st[1] * 100 + st[4], st[2] };
        Shifts.endForTests(level, pl);
        return out;
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "er12_haul_shift")
    public static void er12_haul_shift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1958000, Z, 0, 2000L, true);
        helper.runAtTickTime(5, () -> {
            BlockPos chest = WorkStock.chestOf(level, t.id(), StationTask.SHOP);
            helper.assertTrue(chest != null, "the store has a makings chest");
            if (level.getBlockEntity(chest) instanceof Container c) c.clearContent();
            fill(t.id(), t.store(), new ItemStack(Items.STICK, 64));
            ServerPlayer pl = player(helper, t.store().getBlockPos().south(2), 30);
            String took = Shifts.take(pl, t.id(), ShiftRole.HAUL.id, false);
            Shifts.secondForTests(level, pl);
            String task = Shifts.taskForTests(pl);
            int coins0 = Market.coinsHeld(pl);
            boolean loaded = Shifts.useForTests(level, pl, t.store().getBlockPos());
            int deposit = coins0 - Market.coinsHeld(pl);
            int carried = 0;
            for (ItemStack s : pl.getInventory().items) if (Shifts.isShiftItem(s)) carried += s.getCount();
            boolean unloaded = Shifts.useForTests(level, pl, chest);
            int[] st = Shifts.stateForTests(pl);
            int inChest = 0;
            if (level.getBlockEntity(chest) instanceof Container c) for (int i = 0; i < c.getContainerSize(); i++) inChest += c.getItem(i).getCount();
            Kit.log("er12 took '" + took + "', task '" + task + "', loaded " + loaded + " (" + carried + ", deposit " + deposit + "), unloaded "
                + unloaded + "; chest holds " + inChest + "; state " + java.util.Arrays.toString(st) + "; last " + Shifts.lastForTests(pl)
                + "; coins " + coins0 + " -> " + Market.coinsHeld(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(took.isEmpty() && task != null && task.startsWith("Fetch"), "a load to fetch: " + task);
            helper.assertTrue(loaded && carried > 0, "loaded at the storehouse");
            helper.assertTrue(unloaded && inChest >= carried && st != null && st[0] == 1, "unloaded into the chest, one task done");
            helper.assertTrue(Market.coinsHeld(pl) >= coins0, "the deposit came back with the pay");
            helper.succeed();
        });
    }

    // ============================================================ bounties

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "er07_bounty")
    public static void er07_bounty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1947700, Z, 0, 14000L, true);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity guard = t.keeper();
            guard.setJob(StationTask.GUARD);
            guard.ensurePersona();
            ServerPlayer pl = player(helper, t.heart(), 0);
            double kx = t.heart().getX() + 5.5, ky = t.heart().getY(), kz = t.heart().getZ() + 4.5;
            for (int i = 0; i < PlayerServices.BUSY; i++) kill(level, guard, kx - i, ky, kz);
            int treasury0 = Ledger.coins(t.id());
            for (int i = 0; i < PlayerServices.NIGHT_PURSE + 6; i++) kill(level, pl, kx, ky, kz + 1);
            int paid = Market.coinsHeld(pl), booked = Earnings.earnedTodayForTests(pl, t.id(), Earnings.Source.BOUNTY);
            Kit.log("er07 a busy night: posted " + PlayerServices.bountyPosted(level, t.id()) + "; paid " + paid + " of " + PlayerServices.NIGHT_PURSE
                + ", booked " + booked + ", treasury " + treasury0 + " -> " + Ledger.coins(t.id()));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(paid > 0 && paid <= PlayerServices.NIGHT_PURSE, "paid within the night's purse: " + paid);
            helper.assertTrue(treasury0 - Ledger.coins(t.id()) == paid && booked == paid, "out of the treasury, booked");
            helper.succeed();
        });
    }

    private static void kill(ServerLevel level, net.minecraft.world.entity.Entity killer, double x, double y, double z) {
        Spider s = EntityType.SPIDER.create(level);
        s.moveTo(x, y, z, 0.0F, 0.0F);
        var source = killer instanceof net.minecraft.world.entity.player.Player p ? level.damageSources().playerAttack(p)
            : level.damageSources().mobAttack((net.minecraft.world.entity.LivingEntity) killer);
        PlayerServices.onDeath(new LivingDeathEvent(s, source));
        s.discard();
    }

    // ============================================================ a contract between towns

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "er08_haul_contract")
    public static void er08_haul_contract(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town to = town(helper, 1948900, Z, 0, 2000L, true);
        Town from = town(helper, 1948900 + 700, Z, 0, 2000L, false);
        helper.runAtTickTime(5, () -> {
            fill(from.id(), from.store(), new ItemStack(Items.COAL, 64 * 6));
            WantedBoard.postForTests(level, to.v(), Items.COAL, 32);
            List<HaulContracts.Contract> offered = HaulContracts.offered(level, to.v());
            helper.assertTrue(!offered.isEmpty(), "a contract offered for the coal");
            HaulContracts.Contract c = offered.get(0);
            ServerPlayer pl = player(helper, from.store().getBlockPos().south(2), 100);
            int toT0 = Ledger.coins(to.id()), fromT0 = Ledger.coins(from.id()), coins0 = Market.coinsHeld(pl);
            String acc = HaulContracts.accept(level, pl, c.id());
            int escrow = HaulContracts.escrowed(to.id());
            String load = HaulContracts.loadForTests(level, pl);
            int load1 = carried(pl, s -> s.is(Items.COAL));
            int toStores0 = Market.stock(level, to.id(), s -> s.is(Items.COAL));
            pl.teleportTo(to.store().getBlockPos().getX(), to.store().getBlockPos().getY(), to.store().getBlockPos().getZ() + 2);
            String del = HaulContracts.deliverForTests(level, pl);
            int toStores1 = Market.stock(level, to.id(), s -> s.is(Items.COAL));
            int earned = Market.coinsHeld(pl) - coins0;
            Kit.log("er08 " + c + ": '" + acc + "' escrow " + escrow + "; '" + load + "' carrying " + load1 + "; '" + del + "'; earned " + earned
                + "; to's treasury " + toT0 + " -> " + Ledger.coins(to.id()) + ", from's " + fromT0 + " -> " + Ledger.coins(from.id())
                + "; to's stores " + toStores0 + " -> " + toStores1 + "; escrow left " + HaulContracts.escrowed(to.id()) + "/" + HaulContracts.escrowed(from.id()));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(escrow == c.fee(), "the fee held in escrow: " + escrow);
            helper.assertTrue(load1 == c.count(), "the load in hand");
            helper.assertTrue(toStores1 - toStores0 == c.count(), "into the buying town's stores");
            helper.assertTrue(earned == c.fee(), "the fee paid on delivery, the deposit back: " + earned);
            helper.assertTrue(HaulContracts.escrowed(to.id()) == 0 && HaulContracts.escrowed(from.id()) == 0, "nothing left in escrow");
            helper.assertTrue(toT0 - Ledger.coins(to.id()) == c.fee() + c.coinDue() && Ledger.coins(from.id()) - fromT0 == c.coinDue(),
                "the fee and the goods paid town to town: nothing made, nothing lost");
            helper.succeed();
        });
    }

    // ============================================================ the changer and conservation

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "er09_changer")
    public static void er09_changer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1950100, Z, 0, 2000L, true);
        helper.runAtTickTime(5, () -> {
            ServerPlayer pl = player(helper, t.heart(), 0);
            pl.getInventory().add(new ItemStack(Items.GOLD_INGOT, 3));
            ServicePoints.Visit vis = new ServicePoints.Visit(level, t.v(), ServiceRole.BANK, null, pl, null, new CompoundTag());
            int worth0 = Ledger.coins(t.id()) + Market.coinsHeld(pl) + 9 * (carried(pl, s -> s.is(Items.GOLD_INGOT))
                + Market.stock(level, t.id(), s -> s.is(Items.GOLD_INGOT)));
            ServicePoints.Result in = Buyers.changeForTests(vis, Items.GOLD_INGOT, 3, true);
            int coins = Market.coinsHeld(pl);
            ServicePoints.Result out = Buyers.changeForTests(vis, Items.GOLD_INGOT, 2, false);
            int worth1 = Ledger.coins(t.id()) + Market.coinsHeld(pl) + 9 * (carried(pl, s -> s.is(Items.GOLD_INGOT))
                + Market.stock(level, t.id(), s -> s.is(Items.GOLD_INGOT)));
            Kit.log("er09 '" + in.message() + "' (" + coins + "c), '" + out.message() + "'; gold back " + carried(pl, s -> s.is(Items.GOLD_INGOT))
                + "; worth " + worth0 + " -> " + worth1);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(in.ok() && coins == 27, "three bars for twenty-seven coins: " + coins);
            helper.assertTrue(out.ok() && carried(pl, s -> s.is(Items.GOLD_INGOT)) == 2 && Market.coinsHeld(pl) == 9, "two back for eighteen");
            helper.assertTrue(worth0 == worth1, "nothing made or lost changing: " + worth0 + " -> " + worth1);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "er10_coin_conserved")
    public static void er10_coin_conserved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1951300, Z, 3, 2000L, true);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            ServerPlayer pl = player(helper, p.stand() != null ? p.stand() : p.pos().north(), 10);
            for (VillageFolkEntity f : t.folk()) f.earn(20);
            pl.getInventory().add(new ItemStack(Items.WHEAT, 24));
            pl.getInventory().add(new ItemStack(Items.COAL, 16));
            int sum0 = Ledger.coins(t.id()) + purses(t) + Market.coinsHeld(pl) + HaulContracts.escrowed(t.id());
            Buyers.sellForTests(visit(level, t, ServiceRole.SHOP, pl), new ItemStack(Items.WHEAT), 24);
            WantedBoard.Order o = WantedBoard.postForTests(level, t.v(), Items.COAL, 16);
            WantedBoard.deliverForTests(level, pl, t.v(), o.id);
            Shifts.xpForTests(pl, ShiftRole.COUNTER, 10_000);                  // a master: tips likely
            Shifts.take(pl, t.id(), ShiftRole.COUNTER.id, false);
            for (VillageFolkEntity f : t.folk()) {
                f.teleportTo(p.queueSpot(level, 0).getX() + 0.5, p.queueSpot(level, 0).getY(), p.queueSpot(level, 0).getZ() + 0.5);
                ServiceQueue.turn(f, level, ServiceRole.SHOP, "");
                Shifts.secondForTests(level, pl);
                Shifts.serveForTests(level, pl, f.getUUID());
            }
            Shifts.endForTests(level, pl);
            int sum1 = Ledger.coins(t.id()) + purses(t) + Market.coinsHeld(pl) + HaulContracts.escrowed(t.id());
            Kit.log("er10 treasury+purses+player+escrow " + sum0 + " -> " + sum1 + " (player " + Market.coinsHeld(pl) + ", earned today "
                + Earnings.sum(Earnings.earned(pl, t.id(), level.getDayTime() / 24000L, 1)) + ")");
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(Market.coinsHeld(pl) > 10, "the player earned");
            helper.assertTrue(sum0 == sum1, "every coin came from somebody: " + sum0 + " -> " + sum1);
            helper.succeed();
        });
    }

    // ============================================================ the page

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "er11_ways_to_earn")
    public static void er11_ways_to_earn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1952500, Z, 0, 2000L, true);
        helper.runAtTickTime(5, () -> {
            ServicePoints.Point p = point(level, t.id(), ServiceRole.SHOP);
            ServerPlayer pl = player(helper, t.heart(), 0);
            WantedBoard.Order o = WantedBoard.postForTests(level, t.v(), Items.COAL, 16);
            CompoundTag page = EarnBoard.page(level, pl);
            boolean order = has(page, "wanted", "Coal", false), counter = has(page, "shift", ShiftRole.COUNTER.title, true);
            pl.getInventory().add(new ItemStack(Items.COAL, 16));
            WantedBoard.deliverForTests(level, pl, t.v(), o.id);
            Ledger.takeCoins(t.id(), Ledger.coins(t.id()));
            CompoundTag after = EarnBoard.page(level, pl);
            boolean orderAfter = has(after, "wanted", "Coal", false), unpaid = false;
            ListTag ways = after.getList("ways", Tag.TAG_COMPOUND);
            for (int i = 0; i < ways.size(); i++) {
                CompoundTag w = ways.getCompound(i);
                if (w.getString("kind").equals("shift") && w.getString("why").startsWith("Unpaid")) unpaid = true;
            }
            Kit.log("er11 before: order " + order + ", counter " + counter + " (at " + p.pos().toShortString() + "); after: order " + orderAfter
                + ", unpaid " + unpaid + "; ways " + ways.size());
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(order && counter, "the order and the open counter shift are listed");
            helper.assertTrue(!orderAfter, "the filled order is gone");
            helper.assertTrue(unpaid, "with no coin in the treasury, its shifts say they are unpaid");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "er13_farm_shift")
    public static void er13_farm_shift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1959000, Z, 0, 2000L, true);
        helper.runAtTickTime(5, () -> {
            BlockPos farm = t.heart().east(14).south(14);
            Ledger.built(t.id(), "farm", farm, Direction.NORTH);
            BlockPos plot = Kit.surface(level, farm.getX() + 2, farm.getZ() + 2);
            level.setBlock(plot.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(plot, ((net.minecraft.world.level.block.CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
            ServerPlayer pl = player(helper, plot.north(2), 0);
            pl.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            String took = Shifts.take(pl, t.id(), ShiftRole.FARM.id, false);
            Shifts.secondForTests(level, pl);
            String task = Shifts.taskForTests(pl);
            int stores0 = Market.stock(level, t.id(), s -> s.is(Items.WHEAT));
            pl.gameMode.destroyBlock(plot);
            int seeds = carried(pl, s -> s.is(Items.WHEAT_SEEDS)), stores1 = Market.stock(level, t.id(), s -> s.is(Items.WHEAT));
            String replant = Shifts.taskForTests(pl);
            level.setBlock(plot, Blocks.WHEAT.defaultBlockState(), 3);
            com.jrpetty.mcassistant.entity.ShiftTasks.onPlace(new net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent(
                net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(), level, plot), level.getBlockState(plot.below()), pl));
            int[] st = Shifts.stateForTests(pl);
            Kit.log("er13 took '" + took + "'; task '" + task + "'; broken: seeds in hand " + seeds + ", wheat in the stores " + stores0 + " -> " + stores1
                + "; then '" + replant + "'; state " + java.util.Arrays.toString(st) + "; " + Shifts.lastForTests(pl));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(took.isEmpty() && task != null && task.startsWith("Harvest"), "a ripe plot to harvest: " + task);
            helper.assertTrue(stores1 > stores0 && seeds == 1, "the harvest into the stores, one seed to hand to plant again");
            helper.assertTrue(replant != null && replant.contains("plant"), "then told to plant it again: " + replant);
            helper.assertTrue(st != null && st[0] == 1 && st[2] >= 4, "planted back: the task done, well");
            helper.succeed();
        });
    }

    private static boolean has(CompoundTag page, String kind, String title, boolean open) {
        ListTag ways = page.getList("ways", Tag.TAG_COMPOUND);
        for (int i = 0; i < ways.size(); i++) {
            CompoundTag w = ways.getCompound(i);
            if (w.getString("kind").equals(kind) && w.getString("title").toLowerCase(java.util.Locale.ROOT).contains(title.toLowerCase(java.util.Locale.ROOT))
                && (!open || w.getString("why").isEmpty())) return true;
        }
        return false;
    }
}
