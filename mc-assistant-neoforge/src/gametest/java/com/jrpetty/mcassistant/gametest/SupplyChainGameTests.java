package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MineGround;
import com.jrpetty.mcassistant.entity.MineLevels;
import com.jrpetty.mcassistant.entity.MineSites;
import com.jrpetty.mcassistant.entity.MineWorks;
import com.jrpetty.mcassistant.entity.SmelterFuel;
import com.jrpetty.mcassistant.entity.StoreRoom;
import com.jrpetty.mcassistant.entity.SupplyLean;
import com.jrpetty.mcassistant.entity.ToolTrade;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.TownMine;
import com.jrpetty.mcassistant.entity.TradeBook;
import com.jrpetty.mcassistant.entity.TradeTalks;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [econ32] The town's supply chains, source to use: a smelter with ore and no fuel fetches the stores' logs and smelts
 * (SmelterFuel); a town whose picks are gone and that has no iron buys iron picks from a town whose smith made some to sell
 * (ToolTrade); a mine worked out to the eighth ring of its ground opens a new mine (MineSites); a hungry town wants more
 * farmers; stores filling up get another chest before they are full (StoreRoom). x band 6,600,000 to 6,620,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SupplyChainGameTests {

    private static final int Z = 90000;

    private static void clearStores(ServerLevel level, UUID v) {
        for (BlockPos p : Villages.storeChests(level, v)) {
            if (level.getBlockEntity(p) instanceof Container c) { c.clearContent(); c.setChanged(); }
        }
        Villages.forgetStock();
    }

    /**
     * A smelter with iron ore and nothing to burn, in an Iron Age town whose stores hold logs and no coal: it fetches logs
     * from the stores itself, and smelts its iron at the furnace beside it. The woodcutters' share leans up while the
     * town's fuel is low and its timber short.
     */
    @GameTest(template = "empty", timeoutTicks = 4000, batch = "sc01_smelter_fuel")
    public static void sc01_smelter_fuel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SmelterFuel.resetForTests();
        SupplyLean.resetForTests();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, 6600000, 40, 4, StationTask.FARM);
        helper.assertTrue(folk.size() == 4, "the town");
        UUID v = folk.get(0).ownerId();
        VillageFolkEntity smelter = folk.get(0);
        smelter.setJob(StationTask.SMELT);
        Villages.ageForTests(v, Villages.Age.IRON);
        clearStores(level, v);
        for (int i = 0; i < 3; i++) Market.intoStoresForTests(level, v, new ItemStack(Items.OAK_LOG, 64));
        Villages.forgetStock();
        smelter.removeMatching(s -> AssistantEntity.SMELT_FUEL.test(s) || AssistantEntity.SMELTABLE_ORE.test(s), 999);
        smelter.insertItem(new ItemStack(Items.RAW_IRON, 4));
        helper.assertTrue(SmelterFuel.cold(smelter), "the smelter has its ore and nothing to burn");
        boolean fetched = SmelterFuel.fetch(smelter);
        int logs = smelter.countCarried(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
        Kit.log("[VT] sc01 fetched " + fetched + ", logs in hand " + logs + ", tally " + java.util.Arrays.toString(SmelterFuel.tally(v)));
        helper.assertTrue(fetched && logs > 0, "logs fetched from the stores for the furnaces: " + logs);
        // Its forge: a furnace, its plot round it, and the smelt.
        BlockPos furnace = Kit.surface(level, 6600000 - 6, Z);
        level.setBlock(furnace, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, furnace);
        smelter.assignPlot(com.jrpetty.mcassistant.entity.WorkZone.around(furnace, 4, com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH), "The Forge");
        smelter.moveTo(furnace.getX() + 1.5, furnace.getY(), furnace.getZ() + 0.5, 0.0F, 0.0F);
        smelter.clearQueue();
        smelter.enqueue(Job.smelt("iron", 4));
        // The shares: fuel low (no coal) and timber under the builders' keep once the logs are gone.
        clearStores(level, v);
        SupplyLean.weigh(level, v, true);
        double lean = SupplyLean.lean(v, StationTask.WOOD);
        Kit.log("[VT] sc01 the woodcutters' lean with no logs and no coal: " + lean + " (" + SupplyLean.why(v) + ")");
        helper.assertTrue(lean >= 2.0, "twice the woodcutters with no logs and the fuel low: " + lean);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (level.getDayTime() % 24000L > 9000L) level.setDayTime(1000);
            if (smelter.peekJob() == null && smelter.countCarried(SmelterFuel.IRON_ORE) > 0) smelter.enqueue(Job.smelt("iron", 4));
            if (smelter.distanceToSqr(furnace.getX() + 0.5, furnace.getY(), furnace.getZ() + 0.5) > 64)
                smelter.moveTo(furnace.getX() + 1.5, furnace.getY(), furnace.getZ() + 0.5, 0.0F, 0.0F);   // its forge's work, not the town's
            int bars = smelter.countCarried(s -> s.is(Items.IRON_INGOT));
            if (level.getBlockEntity(furnace) instanceof Container c) bars += c.getItem(2).is(Items.IRON_INGOT) ? c.getItem(2).getCount() : 0;
            if (bars < 1 && tick[0] < 3600) return false;
            Kit.log("[VT] sc01 after " + tick[0] + " ticks: " + bars + " iron bars; " + smelter.debugLine());
            helper.assertTrue(bars >= 1, "the iron smelted on the logs: " + bars);
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    /**
     * Tools between towns: a town with iron to spare, its age asking none, has its smith make iron picks and axes past its
     * rack's spares; a town of miners whose picks are all gone, with no iron and no stone in its stores, is short of tools;
     * the envoys strike a deal for the iron town's picks or axes.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "sc02_tools_traded")
    public static void sc02_tools_traded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        ToolTrade.resetForTests();
        com.jrpetty.mcassistant.entity.TradeDeals.resetForTests();
        TradeBook.resetForTests();
        level.setDayTime(24000L * 6 + 3000);
        Villages.Village[] t = new Villages.Village[2];
        for (int i = 0; i < 2; i++) {
            int cx = 6602000 + i * 300;
            Kit.hold(level, cx, Z, 40);
            Kit.prepare(level, cx, Z, 40);
            BlockPos heart = Kit.surface(level, cx, Z);
            VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 5);
            t[i] = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
            helper.assertTrue(t[i] != null, "a town at " + cx);
            for (AssistantEntity a : Villages.folkOf(t[i].id())) if (a instanceof VillageFolkEntity f) f.ensurePersona();
            clearStores(level, t[i].id());
            Villages.chooseElder(t[i].id(), level.getDayTime() / 24000L);
            TradeTalks.temperForTests(t[i].id(), com.jrpetty.mcassistant.entity.Envoys.Temper.STEADY);
        }
        UUID a = t[0].id(), b = t[1].id();
        helper.assertTrue(!a.equals(b), "two towns");
        Ledger.relate(a, b, 25);
        // The iron town: its smith, iron past its age's ask (the Iron Age's iron in hand), timber for the handles.
        Villages.ageForTests(a, Villages.Age.IRON);
        List<VillageFolkEntity> af = new ArrayList<>();
        for (AssistantEntity x : Villages.folkOf(a)) if (x instanceof VillageFolkEntity f && !f.isBaby()) af.add(f);
        VillageFolkEntity smith = af.get(0);
        smith.setJob(StationTask.SMITH);
        for (int i = 0; i < 6; i++) Market.intoStoresForTests(level, a, new ItemStack(Items.IRON_INGOT, 64));
        Market.intoStoresForTests(level, a, new ItemStack(Items.OAK_LOG, 64));
        Villages.forgetStock();
        int made = 0;
        for (int i = 0; i < 12; i++) {
            ToolTrade.resetForTests();
            if (ToolTrade.forge(smith, level) != null) made++;
        }
        Kit.log("[VT] sc02 the iron town's smith made " + made + " tools to sell");
        helper.assertTrue(made >= 2, "the smith made iron tools to sell: " + made);
        // Its bars gone again (sold on, built with): only the tools are to spare.
        clearStoresBut(level, a, s -> s.is(Items.IRON_PICKAXE) || s.is(Items.IRON_AXE));
        // The town short of picks: miners with none, no iron, no stone, no timber; coin to pay.
        Villages.ageForTests(b, Villages.Age.STONE);
        for (AssistantEntity x : Villages.folkOf(b)) {
            if (!(x instanceof VillageFolkEntity f) || f.isBaby()) continue;
            f.setJob(StationTask.MINE);
            f.removeMatching(s -> s.is(net.minecraft.tags.ItemTags.PICKAXES), 64);
            if (f.getMainHandItem().is(net.minecraft.tags.ItemTags.PICKAXES)) f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            f.recheckKit();
        }
        Ledger.addCoins(b, 400);
        Villages.forgetStock();
        TradeBook.forget(a);
        TradeBook.forget(b);
        TradeBook.Entry sells = TradeBook.of(level, a).get(TradeBook.Ware.TOOLS), buys = TradeBook.of(level, b).get(TradeBook.Ware.TOOLS);
        Kit.log("[VT] sc02 the iron town's tools: " + TradeBook.line(sells) + " | the other's: " + TradeBook.line(buys));
        helper.assertTrue(sells.status() == TradeBook.Status.SURPLUS, "the iron town has tools to spare: " + TradeBook.line(sells));
        helper.assertTrue(buys.status() == TradeBook.Status.SHORT, "the miners' town is short of tools: " + TradeBook.line(buys));
        TradeTalks.Talk k = TradeTalks.negotiate(level, b, a);
        Kit.log("[VT] sc02 talks: " + k.end() + " — " + (k.deal() ? TradeTalks.terms(k) : k.why()));
        helper.assertTrue(k.deal() && k.table() != null && k.table().seller().equals(a)
            && (k.table().x() == Items.IRON_PICKAXE || k.table().x() == Items.IRON_AXE), "a deal for the iron town's tools: " + k.end() + " " + k.why());
        helper.succeed();
    }

    private static void clearStoresBut(ServerLevel level, UUID v, java.util.function.Predicate<ItemStack> keep) {
        for (BlockPos p : Villages.storeChests(level, v)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty() && !keep.test(c.getItem(i))) c.setItem(i, ItemStack.EMPTY);
            c.setChanged();
        }
        Villages.forgetStock();
    }

    /**
     * A mine worked out to the eighth ring: its ground widened as far as it may go, every iron level worked out; the last
     * found worked out by its miner, the town wants a new mine, and opens one clear of the old ground (kept set aside), the
     * miners moved out to the new pithead, the works begun afresh there.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "sc03_new_mine")
    public static void sc03_new_mine(GameTestHelper helper) {
        MineSites.resetForTests();
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 6606000, 2);
        ServerLevel level = r.level();
        UUID v = r.v();
        Villages.ageForTests(v, Villages.Age.IRON);
        BlockPos oldSite = TownMine.siteOf(v);
        // The ground widened out to the eighth ring.
        List<int[]> add = new ArrayList<>();
        for (int i = -8; i <= 8; i++) for (int j = -8; j <= 8; j++)
            if (Math.max(Math.abs(i), Math.abs(j)) > 4 && !MineGround.faceReserved(v, i, j)) add.add(new int[]{ i, j });
        MineGround.addFaces(v, oldSite, add);
        helper.assertTrue(MineGround.ring(v) == 8, "the ground out to the eighth ring: " + MineGround.ring(v));
        // Every iron level worked out but the last; the last found worked out now.
        int further = MineLevels.placesForTests(level, v, MineWorks.IRON).size();
        for (int k = 0; k < further; k++) MineLevels.spendForTests(level, v, MineWorks.IRON + 5 * k);
        MineLevels.markSpentForTests(level, v, MineWorks.IRON + 5 * further);
        Kit.log("[VT] sc03 " + further + " further iron levels; wanted: " + MineSites.pending(v) + "; plan " + MineWorks.planForTests(level, v));
        helper.assertTrue("iron".equals(MineSites.pending(v)), "a new mine wanted for the iron: " + MineSites.pending(v));
        boolean opened = MineSites.open(level, Villages.get(v), "iron");
        BlockPos site = TownMine.siteOf(v);
        Kit.log("[VT] sc03 opened " + opened + ": old " + oldSite + " new " + site + "; faces " + MineGround.faceCount(v) + "; news "
            + Villages.news(v).stream().map(Villages.News::text).filter(s -> s.contains("mine")).toList());
        helper.assertTrue(opened && site != null && !site.equals(oldSite), "a new mine opened: " + site);
        helper.assertTrue(MineGround.reserved(v, oldSite.getX(), oldSite.getZ()), "the old mine's ground still set aside");
        helper.assertTrue(MineGround.faceReserved(v, 0, 0) && MineGround.ring(v) <= 4, "the new ground set aside round the new site");
        helper.assertTrue(MineSites.oldMines(v) == 1 && MineSites.pending(v) == null, "the old mine kept in the books");
        int[] works = MineWorks.shaftForTests(level, v);
        helper.assertTrue(works[2] == site.getX() && works[3] == site.getZ() && works[0] == 0, "the works begun afresh at the new site: "
            + java.util.Arrays.toString(works));
        for (VillageFolkEntity m : r.miners()) {
            BlockPos c = m.workZone() == null ? null : m.workZone().center();
            helper.assertTrue(c != null && Math.abs(c.getX() - site.getX()) <= 1 && Math.abs(c.getZ() - site.getZ()) <= 1,
                "the miner at the new pithead: " + c);
        }
        MineWorksGameTests.done(helper);
    }

    /**
     * A level gone barren: the iron level with hundreds of blocks of drift cut on it this week and no ore out of them; its
     * miner's next look finds it worked out, and the second iron level is planned off the shaft.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "sc06_barren_level")
    public static void sc06_barren_level(GameTestHelper helper) {
        MineLevels.forgetCutForTests();
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 6618000, 1);
        ServerLevel level = r.level();
        UUID v = r.v();
        VillageFolkEntity m = r.miners().get(0);
        Villages.ageForTests(v, Villages.Age.IRON);
        java.util.Map<com.jrpetty.mcassistant.entity.MineDemand.Ore, Integer> stock = MineWorksGameTests.plenty();
        stock.put(com.jrpetty.mcassistant.entity.MineDemand.Ore.IRON, 0);
        com.jrpetty.mcassistant.entity.MineDemand.stockForTests(v, stock);
        MineWorksGameTests.kit(m, Items.IRON_PICKAXE);
        MineWorksGameTests.zone(r, m, 16);
        int y1 = MineWorks.levelY(level, v, MineWorks.IRON);
        MineWorksGameTests.tower(r, y1 - 3);
        MineWorksGameTests.bed(r, y1, 30, Blocks.STONE, 0, 3232L);              // room for its drifts: bare rock
        MineWorks.sinkForTests(level, v, 4 * ((MineWorksGameTests.TOP - y1) / 3) + 1, m);
        MineWorks.roomForTests(level, v, MineWorks.IRON);
        // A first look (the works' week begun), then a while's work: a few hundred blocks of drift, no ore.
        com.jrpetty.mcassistant.entity.MineWorks.Task first = MineWorks.next(level, m);
        if (first != null) MineWorks.release(v, first, m.getUUID());
        MineLevels.cutForTests(level, v, MineWorks.IRON, 300);
        com.jrpetty.mcassistant.entity.MineWorks.Task t0 = MineWorks.next(level, m);
        if (t0 != null) MineWorks.release(v, t0, m.getUUID());
        boolean early = MineLevels.spentForTests(level, v, MineWorks.IRON);
        MineLevels.cutForTests(level, v, MineWorks.IRON, 200);
        com.jrpetty.mcassistant.entity.MineWorks.Task t = MineWorks.next(level, m);
        if (t != null) MineWorks.release(v, t, m.getUUID());
        boolean spent = MineLevels.spentForTests(level, v, MineWorks.IRON);
        List<String> plan = MineWorks.planForTests(level, v);
        Kit.log("[VT] sc06 iron level at Y" + y1 + ": after 300 bare blocks spent " + early + ", after 500 spent " + spent + "; next task "
            + (t == null ? "-" : t.words()) + "; plan " + plan);
        helper.assertTrue(!early, "three hundred blocks are not yet barren");
        helper.assertTrue(spent, "five hundred bare blocks in a week: the iron level worked out");
        helper.assertTrue(plan.stream().anyMatch(x -> x.startsWith((MineWorks.IRON + 5) + ":the second iron level")), "the second iron level planned: " + plan);
        com.jrpetty.mcassistant.entity.MineDemand.stockForTests(v, null);
        MineWorksGameTests.done(helper);
    }

    /** A hungry town wants more farmers: its farms short of hands the morning its larder is low. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "sc04_hungry_farms")
    public static void sc04_hungry_farms(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, 6610000, 40, 12, StationTask.MINE);
        helper.assertTrue(folk.size() == 12, "the town");
        UUID v = folk.get(0).ownerId();
        for (int i = 0; i < 3; i++) folk.get(i).setJob(StationTask.FARM);
        Market.hungryForTests(v, false);
        double fed = Villages.share(v, StationTask.FARM);
        Market.hungryForTests(v, true);
        double hungry = Villages.share(v, StationTask.FARM);
        Kit.log("[VT] sc04 the farms' hands over (+) or under (-) their share: fed " + fed + ", hungry " + hungry);
        helper.assertTrue(hungry < fed - 0.9, "the farms want a hand or more when the town is hungry: " + fed + " -> " + hungry);
        helper.assertTrue(hungry < 0, "the farms are short of hands: " + hungry);
        Market.hungryForTests(v, false);
        helper.succeed();
    }

    /** Stores filling up: with under a tenth of their places free, another chest is set down at once, not after they are full. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "sc05_store_room")
    public static void sc05_store_room(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StoreRoom.resetForTests();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, 6614000, 40, 6, StationTask.FARM);
        helper.assertTrue(!folk.isEmpty(), "the town");
        UUID v = folk.get(0).ownerId();
        TownJobs.instantForTests(true);
        try {
            clearStores(level, v);
            List<BlockPos> chests = Villages.storeChests(level, v);
            if (chests.isEmpty()) {
                BlockPos c = Kit.surface(level, 6614000, Z).offset(3, 0, 3);
                level.setBlock(c, Blocks.CHEST.defaultBlockState(), 3);
                com.jrpetty.mcassistant.entity.ZoneChests.mark(level, c);
                chests = Villages.storeChests(level, v);
            }
            helper.assertTrue(!chests.isEmpty(), "the town's stores");
            // Filled but for a handful of places, the planks for a chest among what is there.
            Market.intoStoresForTests(level, v, new ItemStack(Items.OAK_PLANKS, 64));
            int free = 0, all = 0;
            net.minecraft.world.item.Item[] fill = { Items.COBBLESTONE, Items.DIRT, Items.WHEAT_SEEDS, Items.BONE, Items.FLINT, Items.STICK };
            int k = 0;
            for (BlockPos p : chests) {
                if (!(level.getBlockEntity(p) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    all++;
                    if (c.getItem(i).isEmpty()) c.setItem(i, new ItemStack(fill[k++ % fill.length], 1));
                }
                c.setChanged();
            }
            // Free a few places again: under a tenth.
            outer:
            for (BlockPos p : chests) {
                if (!(level.getBlockEntity(p) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (free >= 3) break outer;
                    if (!c.getItem(i).is(Items.OAK_PLANKS)) { c.setItem(i, ItemStack.EMPTY); free++; }
                }
            }
            int before = Villages.storeChests(level, v).size();
            StoreRoom.ahead(level, v);
            int after = Villages.storeChests(level, v).size();
            Kit.log("[VT] sc05 stores " + all + " places, " + free + " free: chests " + before + " -> " + after + " " + java.util.Arrays.toString(StoreRoom.last(v)));
            helper.assertTrue(after > before, "another chest set down before the stores were full: " + before + " -> " + after);
        } finally {
            TownJobs.instantForTests(false);
        }
        helper.succeed();
    }
}
