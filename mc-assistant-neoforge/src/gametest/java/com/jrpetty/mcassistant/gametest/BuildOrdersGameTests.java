package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bank;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.BuildOrders;
import com.jrpetty.mcassistant.entity.CapitalBook;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.HousingMarket;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PriceIndex;
import com.jrpetty.mcassistant.entity.PropertyMarket;
import com.jrpetty.mcassistant.entity.Refits;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [orders17] Buildings commissioned by players (entity/BuildOrders) and the property market (PropertyMarket, Refits): a quote
 * that adds up line by line and follows the prices; a time that grows with a busier queue and shrinks with more builders and
 * the buyer's own materials; an order that is built, block by block out of the stores, and finishes near its estimate; a
 * revised time when the stores run short; the deposit, the stage and the refund all booked (the census drifts by nothing);
 * the priority premium; a house sold to a folk household and a shop to a firm; a building refitted for another use; and the
 * orders kept over a save and a load.
 *
 * <p>At x 4,700,000-4,800,000, z 90000, each test on its own ground.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BuildOrdersGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    private record Town(UUID id, Villages.Village v, BlockPos heart, List<VillageFolkEntity> folk) {}

    private static Town town(GameTestHelper helper, ServerLevel level, int x, int folk) {
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Bank.resetForTests();
        HousingMarket.resetForTests();
        BuildOrders.resetForTests();
        PropertyMarket.resetForTests();
        TownJobs.instantForTests(true);
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
        for (int i = 1; i < folk; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 2 * (i % 12), Z + 3 + 2 * (i / 12)), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i);
            all.add(f);
        }
        Villages.ageForTests(id, Villages.Age.STONE);
        for (VillageFolkEntity f : all) {
            f.spend(f.purse());
            f.rentFree(false);
            f.setJob(StationTask.FARM);
        }
        return new Town(id, v, heart, all);
    }

    /** Store chests by the heart with what a cottage is built and fitted of (less what is left out). */
    private static void stores(ServerLevel level, Town t, Item... without) {
        List<Item> skip = List.of(without);
        Item[] goods = { Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
            Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_LOG, Items.OAK_LOG, Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
            Items.STONE_BRICKS, Items.STONE_BRICKS, Items.GLASS, Items.GLASS_PANE, Items.TORCH, Items.LANTERN, Items.WHITE_WOOL,
            Items.RED_CARPET, Items.POPPY, Items.BARREL, Items.FURNACE, Items.SMOKER, Items.CRAFTING_TABLE, Items.CHEST, Items.RED_BED };
        List<ItemStack> stacks = new ArrayList<>();
        for (Item it : goods) if (!skip.contains(it)) stacks.add(new ItemStack(it, new ItemStack(it).getMaxStackSize()));
        for (int c = 0; c * 27 < stacks.size(); c++) {
            BlockPos chest = Kit.surface(level, t.heart().getX() + 4 + 2 * c, t.heart().getZ() - 4);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, chest);
            Container box = (Container) level.getBlockEntity(chest);
            for (int k = 0; k < 27 && c * 27 + k < stacks.size(); k++) box.setItem(k, stacks.get(c * 27 + k));
            box.setChanged();
        }
        if (!skip.contains(Items.RED_BED)) {
            BlockPos chest = Kit.surface(level, t.heart().getX() - 4, t.heart().getZ() - 4);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, chest);
            Container box = (Container) level.getBlockEntity(chest);
            for (int k = 0; k < 6; k++) box.setItem(k, new ItemStack(Items.RED_BED));
            for (int k = 6; k < 10; k++) box.setItem(k, new ItemStack(Items.SMOKER));
            box.setChanged();
        }
        Villages.forgetStores(t.id());
    }

    private static ServerPlayer player(GameTestHelper helper, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        while (coins > 0) {
            int k = Math.min(64, coins);
            p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), k));
            coins -= k;
        }
        return p;
    }

    private static BlockPos plot(GameTestHelper helper, ServerLevel level, Town t, String drawing, int dx, int dz) {
        BlockPos p = BuildOrders.suggestPlot(level, t.id(), drawing, t.heart().offset(dx, 0, dz), Direction.NORTH, 30);
        helper.assertTrue(p != null, "a plot for " + drawing + " near " + dx + "," + dz);
        return p;
    }

    private static long[] census(UUID id) {
        return new long[]{ CoinCensus.count(id), CoinCensus.netFlowPence(id) };
    }

    private static long drift(long[] before, long[] after) {
        return (after[0] - before[0]) - (after[1] - before[1]);
    }

    /** Hours wound on, two at a time, until the order is done (or the days run out); the days it took. */
    private static double buildOut(ServerLevel level, Town t, String oid, int days) {
        double start = BuildOrders.hoursPassedForTests(t.id());
        for (int i = 0; i < days * 12; i++) {
            CompoundTag o = BuildOrders.orderForTests(oid);
            if (o == null || !o.getString("status").equals("open")) break;
            BuildOrders.hoursForTests(level, t.v(), 2);
        }
        return (BuildOrders.hoursPassedForTests(t.id()) - start) / 24.0;
    }

    // ------------------------------------------------------------------ the quote

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bo01_quote")
    public static void bo01_quote(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_700_000;
        Town t = town(helper, level, x, 6);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos at = plot(helper, level, t, "house", 30, 30);
            BuildOrders.Quote q = BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, null);
            for (String s : q.lines()) Kit.log("[VT] bo01 " + s);
            double m = 0;
            int n = 0;
            for (BuildOrders.Material mat : q.materials) {
                m += mat.total();
                n += mat.count();
                ok.that(Math.abs(mat.unit() - PriceIndex.each(level, id, new ItemStack(mat.item()))) < 1e-9 || mat.unit() == 0.01, "each line at the town's price: " + mat.name());
                ok.that(Math.abs(mat.total() - mat.unit() * (mat.count() - mat.supplied())) < 1e-9, "a line's total is its count times its price: " + mat.name());
            }
            ok.that(q.ok, "the plot is allowed: " + q.why);
            ok.that(Math.abs(m - q.materialsCost) < 1e-6, "the materials are their lines summed: " + m + " = " + q.materialsCost);
            ok.that(n == q.cells + q.fill, "every block (and the hollows filled) on a line: " + n + " = " + q.cells + " + " + q.fill);
            double l = 0, h = 0;
            for (BuildOrders.Labour lab : q.labour) { l += lab.cost(); h += lab.hours(); ok.that(Math.abs(lab.cost() - lab.hours() * lab.rate()) < 1e-9, "a trade's hours at its rate: " + lab.words()); }
            ok.that(q.labour.size() >= 3, "laid by several trades: " + q.labour.size());
            ok.that(Math.abs(l - q.labourCost) < 1e-6 && Math.abs(h - q.labourHours) < 1e-6, "the labour is its trades summed: " + l + " = " + q.labourCost);
            ok.that(Math.abs(q.overheads - q.labourCost * 0.10) < 1e-6, "overheads a tenth of the labour");
            ok.that(Math.abs(q.margin - (q.materialsCost + q.labourCost + q.overheads) * 0.10) < 1e-6, "the margin a tenth of the works");
            ok.that(Math.abs(q.permit - Math.max(2, (q.materialsCost + q.labourCost) * 0.05)) < 1e-6, "the permit a twentieth, two at least");
            ok.that(q.landCost > 0 && q.landTerms.contains("title"), "the land bought: " + q.landCost);
            double sum = q.materialsCost + q.labourCost + q.overheads + q.landCost + q.fees + q.margin + q.premium;
            ok.that(q.total == (int) Math.ceil(sum - 1e-9), "the total is the parts: " + q.total + " = " + sum);
            ok.that(q.deposit + q.stage + q.balance == q.total, "deposit, stage and balance make the total: " + q.deposit + "+" + q.stage + "+" + q.balance);
            ok.that(q.eta != null && q.eta.low() <= q.eta.days() && q.eta.days() <= q.eta.high() && q.eta.days() > 0, "a time with a range: " + q.eta.words());
            // On the buyer's own land, the land is nothing.
            UUID buyer = UUID.randomUUID();
            int[] half = BuildGoal.footprint("house");
            PropertyMarket.register(level, id, buyer, "Tester", "Tester's plot", "plot", at, Direction.NORTH, "house", half[0], half[1]);
            BuildOrders.Quote own = BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, buyer);
            ok.that(own.landCost == 0 && own.landTerms.contains("own"), "no land to pay on its own plot: " + own.landCost + " (" + own.landTerms + ")");
            // Dearer planks, a dearer building.
            PriceIndex.setForTests(id, new ItemStack(Items.OAK_PLANKS), 3.0);
            PriceIndex.setForTests(id, new ItemStack(Items.OAK_STAIRS), 3.0);
            BuildOrders.Quote boom = BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, null);
            Kit.log("[VT] bo01 materials " + q.materialsCost + " -> " + boom.materialsCost + " with timber dearer");
            ok.that(boom.materialsCost > q.materialsCost * 1.1 && boom.total > q.total, "timber dearer, the building dearer: " + q.total + " -> " + boom.total);
            // A street is not a plot.
            BuildOrders.Quote street = BuildOrders.quote(level, id, "house", "oak", t.heart(), Direction.NORTH, null);
            ok.that(!street.ok, "not on the square: " + street.why);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ the time

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "bo02_eta")
    public static void bo02_eta(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_705_000;
        Town t = town(helper, level, x, 6);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos a = plot(helper, level, t, "house", 30, 30), b = plot(helper, level, t, "house", -30, 30);
            // Bare stores: the materials to be made or bought in.
            BuildOrders.Quote bare = BuildOrders.quote(level, id, "house", "oak", b, Direction.NORTH, null);
            BuildOrders.Options mine = new BuildOrders.Options();
            for (BuildOrders.Material m : bare.materials) mine.supplied.put(m.item(), m.count());
            BuildOrders.Quote brought = BuildOrders.quote(level, id, "house", "oak", b, Direction.NORTH, null, mine);
            Kit.log("[VT] bo02 bare stores " + bare.eta.words() + " (" + bare.eta.reason() + "); own materials " + brought.eta.words() + " (" + brought.eta.reason() + ")");
            ok.that(bare.eta.materialsShort() > 0 && brought.eta.materialsShort() == 0, "the stores short, but not of what the buyer brings: " + bare.eta.materialsShort());
            ok.that(brought.eta.days() < bare.eta.days(), "own materials, sooner: " + brought.eta.days() + " < " + bare.eta.days());
            ok.that(brought.materialsCost < bare.materialsCost * 0.05, "own materials, cheaper: " + brought.materialsCost + " < " + bare.materialsCost);
            stores(level, t);
            BuildOrders.Quote first = BuildOrders.quote(level, id, "house", "oak", b, Direction.NORTH, null);
            ServerPlayer p = player(helper, 400);
            BuildOrders.Quote qa = BuildOrders.quote(level, id, "house", "oak", a, Direction.NORTH, p.getUUID());
            BuildOrders.Result r = BuildOrders.place(level, p, qa);
            ok.that(r.ok(), "an order placed: " + r.message());
            BuildOrders.Quote second = BuildOrders.quote(level, id, "house", "oak", b, Direction.NORTH, null);
            Kit.log("[VT] bo02 empty queue " + first.eta.words() + "; behind one order " + second.eta.words() + " (" + second.eta.reason() + ")");
            ok.that(second.eta.position() == 2 && second.eta.days() > first.eta.days() + 0.2, "a busier queue, later: " + first.eta.days() + " -> " + second.eta.days());
            // More builders: a town three times the size has bigger crews, and two of them.
            for (int i = 0; i < 20; i++) {
                VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 20 + 2 * (i % 10), Z - 20 - 2 * (i / 10)), 0.0F);
                if (f != null) { f.setJob(StationTask.FARM); f.spend(f.purse()); }
            }
            BuildOrders.Quote more = BuildOrders.quote(level, id, "house", "oak", b, Direction.NORTH, null);
            Kit.log("[VT] bo02 with more builders " + more.eta.words() + " (" + more.eta.reason() + ")");
            ok.that(more.eta.hands() > second.eta.hands() && more.eta.days() < second.eta.days(), "more builders, sooner: " + second.eta.days() + " -> " + more.eta.days());
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ built

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bo03_build")
    public static void bo03_build(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_710_000;
        Town t = town(helper, level, x, 6);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = player(helper, 600);
            BlockPos at = plot(helper, level, t, "house", 30, 30);
            BuildOrders.Quote q = BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, p.getUUID());
            long[] c0 = census(id);
            int coins0 = Market.coinsHeld(p);
            BuildOrders.Result r = BuildOrders.place(level, p, q);
            ok.that(r.ok(), "placed: " + r.message());
            ok.that(coins0 - Market.coinsHeld(p) == q.deposit, "the deposit taken: " + (coins0 - Market.coinsHeld(p)) + " = " + q.deposit);
            double days = buildOut(level, t, r.orderId(), 20);
            CompoundTag o = BuildOrders.orderForTests(r.orderId());
            BuildOrders.Status s = BuildOrders.status(level, r.orderId());
            for (String line : s.lines()) Kit.log("[VT] bo03 " + line);
            Kit.log("[VT] bo03 quoted " + q.eta.words() + ", took " + String.format(java.util.Locale.ROOT, "%.2f", days) + " days; " + BuildOrders.laidForTests(r.orderId()) + " of " + BuildOrders.cellsForTests(r.orderId()) + " laid; left out " + o.getInt("leftOut"));
            ok.that("done".equals(o.getString("status")), "finished: " + o.getString("status") + " " + o.getString("waiting"));
            ok.that(days >= q.eta.low() - 1.0 && days <= q.eta.high() + 1.0, "near the estimate: " + days + " in " + q.eta.low() + "-" + q.eta.high());
            int stood = 0;
            for (BuildGoal.Placement pl : BuildGoal.plan("house", at, Direction.NORTH, 13)) if (!level.getBlockState(pl.pos()).isAir()) stood++;
            ok.that(stood > 50, "the house stands: " + stood + " blocks");
            ok.that(o.getLong("labourPaid") == o.getLong("labourDue") && !o.getCompound("builders").isEmpty(), "the builders paid their wages: " + o.getCompound("builders"));
            CompoundTag prop = PropertyMarket.propertyForTests(id, at);
            ok.that(prop != null && p.getUUID().toString().equals(prop.getString("owner")), "on the register, the player's");
            ok.that(prop != null && prop.getBoolean("onHomes"), "on the homes' books");
            ok.that(o.getLong("escrow") == 0, "nothing left held: " + o.getLong("escrow"));
            long d = drift(c0, census(id));
            ok.that(d == 0, "every coin accounted for: drift " + d);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ short of materials

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bo04_short")
    public static void bo04_short(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_715_000;
        Town t = town(helper, level, x, 6);
        stores(level, t, Items.GLASS_PANE, Items.GLASS);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = player(helper, 600);
            BlockPos at = plot(helper, level, t, "house", 30, 30);
            BuildOrders.Quote q = BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, p.getUUID());
            BuildOrders.Result r = BuildOrders.place(level, p, q);
            ok.that(r.ok(), "placed: " + r.message());
            ok.that(q.eta.materialsShort() > 0 && q.eta.reason().contains("glass"), "the quote says the glass must be made: " + q.eta.reason());
            for (int i = 0; i < 40; i++) {
                BuildOrders.hoursForTests(level, t.v(), 2);
                if (BuildOrders.orderForTests(r.orderId()).getString("waiting").contains("glass")) break;
            }
            BuildOrders.hoursForTests(level, t.v(), 48);
            BuildOrders.Status s = BuildOrders.status(level, r.orderId());
            for (String line : s.lines()) Kit.log("[VT] bo04 " + line);
            ok.that(s.waitingOn.contains("glass"), "waiting for the glass: " + s.waitingOn);
            ok.that(s.etaDay > s.etaDayFirst && !s.history.isEmpty(), "the time revised, with why: day " + s.etaDay + " (first " + s.etaDayFirst + ") " + s.history);
            ok.that(s.percent < 100, "not finished: " + s.percent);
            // The glass comes in: the work goes on to the end.
            BlockPos chest = Kit.surface(level, t.heart().getX() - 6, t.heart().getZ() - 4);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, chest);
            Container box = (Container) level.getBlockEntity(chest);
            box.setItem(0, new ItemStack(Items.GLASS_PANE, 64));
            box.setItem(1, new ItemStack(Items.GLASS, 64));
            box.setChanged();
            Villages.forgetStores(id);
            buildOut(level, t, r.orderId(), 20);
            CompoundTag o = BuildOrders.orderForTests(r.orderId());
            ok.that("done".equals(o.getString("status")), "finished once the glass came: " + o.getString("status") + " " + o.getString("waiting"));
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ payments and refunds

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bo05_pay")
    public static void bo05_pay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_720_000;
        Town t = town(helper, level, x, 6);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = player(helper, 600);
            p.getInventory().add(new ItemStack(Items.COBBLESTONE, 30));
            BlockPos at = plot(helper, level, t, "house", 30, 30);
            BuildOrders.Quote plain = BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, p.getUUID());
            BuildOrders.Options o = new BuildOrders.Options();
            o.fromInventory = p;
            BuildOrders.Quote q = BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, p.getUUID(), o);
            int cob = q.supplied.getOrDefault(Items.COBBLESTONE, 0);
            Kit.log("[VT] bo05 bringing " + q.supplied + ": materials " + plain.materialsCost + " -> " + q.materialsCost);
            ok.that(cob > 0 && q.materialsCost < plain.materialsCost, "bringing cobblestone, cheaper: " + plain.materialsCost + " -> " + q.materialsCost);
            long[] c0 = census(id);
            int coins0 = Market.coinsHeld(p);
            BuildOrders.Result r = BuildOrders.place(level, p, q);
            ok.that(r.ok(), "placed: " + r.message());
            CompoundTag ord = BuildOrders.orderForTests(r.orderId());
            ok.that(BuildOrders.heldPence(id) == q.deposit * 100L - ord.getLong("toTown"), "the deposit held, less the land and the fees paid: " + BuildOrders.heldPence(id));
            ok.that(drift(c0, census(id)) == 0, "the deposit booked: drift " + drift(c0, census(id)));
            // Past the stage: the stage payment taken from the player.
            int cells = BuildOrders.cellsForTests(r.orderId());
            for (int i = 0; i < 60 && BuildOrders.laidForTests(r.orderId()) * 100 < cells * 45; i++) BuildOrders.hoursForTests(level, t.v(), 1);
            ord = BuildOrders.orderForTests(r.orderId());
            int paid = coins0 - Market.coinsHeld(p);
            Kit.log("[VT] bo05 laid " + BuildOrders.laidForTests(r.orderId()) + "/" + cells + ", paid " + paid + " (deposit " + q.deposit + ", stage " + q.stage + "), held " + ord.getLong("escrow"));
            ok.that(ord.getInt("stagePaid") == 1 && paid == q.deposit + q.stage, "the stage paid: " + paid + " = " + q.deposit + " + " + q.stage);
            ok.that(drift(c0, census(id)) == 0, "the work paid for as it goes, booked: drift " + drift(c0, census(id)));
            int before = Market.coinsHeld(p);
            long held = ord.getLong("escrow");
            int cobLeft = ord.getCompound("supplied").getInt("minecraft:cobblestone");
            String said = BuildOrders.cancel(level, p, r.orderId());
            Kit.log("[VT] bo05 " + said);
            int back = Market.coinsHeld(p) - before;
            ok.that(back > 0 && back <= held / 100 + 1, "what wasn't spent came back: " + back + " of " + held + " pence held");
            int cobBack = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(Items.COBBLESTONE)) cobBack += p.getInventory().getItem(i).getCount();
            ok.that(cobBack == cobLeft, "the cobblestone not laid came back: " + cobBack + " = " + cobLeft);
            ok.that(BuildOrders.heldPence(id) == 0, "nothing held after: " + BuildOrders.heldPence(id));
            ok.that(PropertyMarket.propertyForTests(id, at) != null, "what was built is on the register, the player's");
            long d = drift(c0, census(id));
            ok.that(d == 0, "every coin accounted for: drift " + d);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ priority

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "bo06_priority")
    public static void bo06_priority(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_725_000;
        Town t = town(helper, level, x, 6);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer a = player(helper, 600), b = player(helper, 600);
            BlockPos pa = plot(helper, level, t, "house", 30, 30), pb = plot(helper, level, t, "house", -30, 30);
            BuildOrders.Result ra = BuildOrders.place(level, a, BuildOrders.quote(level, id, "house", "oak", pa, Direction.NORTH, a.getUUID()));
            BuildOrders.Quote plain = BuildOrders.quote(level, id, "house", "oak", pb, Direction.NORTH, b.getUUID());
            BuildOrders.Options o = new BuildOrders.Options();
            o.priority = true;
            BuildOrders.Quote pri = BuildOrders.quote(level, id, "house", "oak", pb, Direction.NORTH, b.getUUID(), o);
            Kit.log("[VT] bo06 plain " + plain.total + " " + plain.eta.words() + "; priority " + pri.total + " (premium " + pri.premium + ") " + pri.eta.words() + " " + pri.eta.reason());
            ok.that(Math.abs(pri.premium - (pri.labourCost + pri.overheads) * 0.25) < 1e-6 && pri.premium > 0, "the premium a quarter of the labour and overheads: " + pri.premium);
            ok.that(pri.total > plain.total, "priority dearer: " + plain.total + " -> " + pri.total);
            ok.that(pri.eta.position() == 1 && plain.eta.position() == 2 && pri.eta.days() < plain.eta.days(), "priority ahead of the plain order: " + pri.eta.position() + ", " + pri.eta.days() + " < " + plain.eta.days());
            BuildOrders.Result rb = BuildOrders.place(level, b, pri);
            ok.that(rb.ok(), "placed: " + rb.message());
            BuildOrders.hoursForTests(level, t.v(), 12);
            int la = BuildOrders.laidForTests(ra.orderId()), lb = BuildOrders.laidForTests(rb.orderId());
            Kit.log("[VT] bo06 after 12 hours: plain " + la + ", priority " + lb);
            ok.that(lb > 0, "the priority order is being built: " + lb);
            String said = BuildOrders.prioritise(level, a, ra.orderId());
            Kit.log("[VT] bo06 " + said);
            ok.that(BuildOrders.orderForTests(ra.orderId()).getBoolean("priority"), "the first order put on priority after: " + said);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ selling

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bo07_sellhouse")
    public static void bo07_sellhouse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_730_000;
        Town t = town(helper, level, x, 6);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = player(helper, 600);
            BlockPos at = plot(helper, level, t, "house", 30, 30);
            BuildOrders.Result r = BuildOrders.place(level, p, BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, p.getUUID()));
            buildOut(level, t, r.orderId(), 20);
            ok.that("done".equals(BuildOrders.orderForTests(r.orderId()).getString("status")), "built");
            for (VillageFolkEntity f : t.folk()) f.earn(150);
            String key = PropertyMarket.keyForTests(at);
            PropertyMarket.Valuation val = PropertyMarket.value(level, id, key);
            Kit.log("[VT] bo07 valued " + val.words());
            ok.that(val.land() > 0 && val.building() > 0 && val.total() > 0, "a valuation of land and building: " + val.words());
            String listed = PropertyMarket.list(level, p, id, key, false, val.total(), false);
            Kit.log("[VT] bo07 " + listed);
            long[] c0 = census(id);
            PropertyMarket.morningForTests(level, t.v(), level.getDayTime() / 24000L + 1);
            CompoundTag prop = PropertyMarket.propertyForTests(id, at);
            for (String s : PropertyMarket.lines(level, id, key)) Kit.log("[VT] bo07 " + s);
            CompoundTag offer = null;
            for (net.minecraft.nbt.Tag o : prop.getList("offers", 10)) if ("folk".equals(((CompoundTag) o).getString("kind"))) offer = (CompoundTag) o;
            ok.that(offer != null, "a household made an offer");
            if (offer != null) {
                int before = Market.coinsHeld(p);
                String said = PropertyMarket.accept(level, p, id, key, offer.getInt("no"));
                Kit.log("[VT] bo07 " + said);
                ok.that(Market.coinsHeld(p) - before == offer.getInt("price"), "the price to the seller: " + (Market.coinsHeld(p) - before) + " = " + offer.getInt("price"));
                ok.that("folk".equals(prop.getString("ownerKind")), "the household owns it now: " + prop.getString("ownerName"));
                ok.that(prop.getBoolean("onHomes"), "on the homes' books, the household's");
            }
            long d = drift(c0, census(id));
            ok.that(d == 0, "every coin accounted for: drift " + d);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "bo08_sellshop")
    public static void bo08_sellshop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_735_000;
        Town t = town(helper, level, x, 12);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = player(helper, 10);
            BlockPos at = plot(helper, level, t, "shop", 30, 30);
            BuildGoal.stamp(level, "shop", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            int[] half = BuildGoal.footprint("shop");
            String key = PropertyMarket.register(level, id, p.getUUID(), p.getName().getString(), "Tester's shop", "shop", at, Direction.NORTH, "shop", half[0], half[1]);
            Enterprise.autoForTests(false);
            Business b = Enterprise.foundForTests(level, t.v(), "greengrocer", t.folk().get(1));
            ok.that(b != null, "a firm in the town");
            if (b == null) { helper.fail(ok.summary()); return; }
            b.till = 200_000;
            PropertyMarket.Valuation val = PropertyMarket.value(level, id, key);
            Kit.log("[VT] bo08 valued " + val.words());
            PropertyMarket.list(level, p, id, key, false, val.total(), false);
            long[] c0 = census(id);
            PropertyMarket.morningForTests(level, t.v(), level.getDayTime() / 24000L + 1);
            CompoundTag prop = PropertyMarket.propertyForTests(id, at);
            for (String s : PropertyMarket.lines(level, id, key)) Kit.log("[VT] bo08 " + s);
            CompoundTag offer = null;
            for (net.minecraft.nbt.Tag o : prop.getList("offers", 10)) if ("firm".equals(((CompoundTag) o).getString("kind"))) offer = (CompoundTag) o;
            ok.that(offer != null, "a firm made an offer");
            if (offer != null) {
                long till = b.till;
                int before = Market.coinsHeld(p);
                Kit.log("[VT] bo08 " + PropertyMarket.accept(level, p, id, key, offer.getInt("no")));
                ok.that(Market.coinsHeld(p) - before == offer.getInt("price"), "the price to the seller");
                ok.that(till - b.till == offer.getInt("price") * 100L, "out of the firm's till: " + (till - b.till));
                ok.that("firm".equals(prop.getString("ownerKind")) && b.id.equals(prop.getString("biz")), "the firm owns it, on its books: " + b.cards.get("premises"));
            }
            long d = drift(c0, census(id));
            ok.that(d == 0, "every coin accounted for: drift " + d);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ repurposing

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bo09_refit")
    public static void bo09_refit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_740_000;
        Town t = town(helper, level, x, 6);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = player(helper, 400);
            BlockPos at = plot(helper, level, t, "shop", 30, 30);
            BuildGoal.stamp(level, "shop", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            int[] half = BuildGoal.footprint("shop");
            String key = PropertyMarket.register(level, id, p.getUUID(), p.getName().getString(), "Tester's shop", "shop", at, Direction.NORTH, "shop", half[0], half[1]);
            Map<net.minecraft.world.level.block.Block, Integer> lack = Refits.missing(level, id, key, "bakery");
            BuildOrders.Quote q = Refits.quote(level, id, key, "bakery");
            for (String s : q.lines()) Kit.log("[VT] bo09 " + s);
            Kit.log("[VT] bo09 lacks " + lack + "; options " + Refits.options(level, id, key));
            ok.that(q.ok && !q.explicit.isEmpty() && q.total > 0, "a refit quote: " + q.total + " for " + q.explicit.size() + " pieces " + q.why);
            BuildOrders.Result r = BuildOrders.place(level, p, q);
            ok.that(r.ok(), "the refit ordered: " + r.message());
            buildOut(level, t, r.orderId(), 10);
            CompoundTag o = BuildOrders.orderForTests(r.orderId());
            ok.that("done".equals(o.getString("status")), "refitted: " + o.getString("status") + " " + o.getString("waiting"));
            CompoundTag prop = PropertyMarket.propertyForTests(id, at);
            ok.that("bakery".equals(prop.getString("use")), "a bakery now: " + prop.getString("use") + " (" + prop.getString("name") + ")");
            Map<net.minecraft.world.level.block.Block, Integer> after = Refits.missing(level, id, key, "bakery");
            ok.that(after.size() < lack.size() || after.isEmpty(), "its kit in place: lacked " + lack + ", now " + after);
            BuildOrders.Quote storey = Refits.extension(level, id, key, "storey");
            Kit.log("[VT] bo09 a storey: " + storey.total + " for " + storey.cells + " blocks, " + storey.eta.words() + " " + storey.why);
            ok.that(storey.ok && storey.cells > 0 && storey.total > 0, "a storey quoted: " + storey.why);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ kept

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bo10_saveload")
    public static void bo10_saveload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4_745_000;
        Town t = town(helper, level, x, 6);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = player(helper, 600);
            BlockPos at = plot(helper, level, t, "house", 30, 30);
            BuildOrders.Result r = BuildOrders.place(level, p, BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, p.getUUID()));
            BuildOrders.hoursForTests(level, t.v(), 10);
            int laid = BuildOrders.laidForTests(r.orderId());
            long held = BuildOrders.heldPence(id);
            CapitalBook.roundTripForTests(id, level.registryAccess());
            BuildOrders.resetForTests();
            PropertyMarket.resetForTests();
            Kit.log("[VT] bo10 laid " + laid + " held " + held + "; after the load laid " + BuildOrders.laidForTests(r.orderId()) + " held " + BuildOrders.heldPence(id));
            ok.that(BuildOrders.laidForTests(r.orderId()) == laid && laid > 0, "what was laid kept: " + laid);
            ok.that(BuildOrders.heldPence(id) == held, "what was held kept: " + held);
            ok.that(PropertyMarket.propertyForTests(id, at) != null, "the plot on the register kept");
            buildOut(level, t, r.orderId(), 20);
            ok.that("done".equals(BuildOrders.orderForTests(r.orderId()).getString("status")), "and finished after it");
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }
}
