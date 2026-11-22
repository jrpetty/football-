package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Fuel;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MineDemand;
import com.jrpetty.mcassistant.entity.MineLevels;
import com.jrpetty.mcassistant.entity.MineWorks;
import com.jrpetty.mcassistant.entity.TownLod;
import com.jrpetty.mcassistant.entity.TownWake;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.WorksGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [growth24] The Iron Age stall broken: a level of the mine worked out opens the next level of its ore off the shaft, and
 * the iron keeps coming (MineLevels); the stores' coal kept back for the smelters (Fuel.keptForSmelting); and a sleeping
 * town's folk let go even inside an awake neighbour's ground (SleepClaims), so the server's cost stays with the towns
 * that are awake. x band 5,400,000 to 5,500,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class Growth24GameTests {

    private static final int Z = 90000;

    /**
     * An Iron Age town short of iron with a miner on the iron level: the level is cut to its ends (every drift closed, every
     * branch done), and the miner, finding nothing left on it, marks it worked out; the second iron level is planned six
     * blocks off it and opened (its landing cut, drifts driven), and raw iron comes in from it. The news says so.
     */
    @GameTest(template = "empty", timeoutTicks = 16000, batch = "gw24_01_next_level")
    public static void gw24_01_next_level(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 5402000, 1);
        ServerLevel level = r.level();
        VillageFolkEntity m = r.miners().get(0);
        Villages.ageForTests(r.v(), Villages.Age.IRON);
        Map<MineDemand.Ore, Integer> stock = MineWorksGameTests.plenty();
        stock.put(MineDemand.Ore.IRON, 0);
        MineDemand.stockForTests(r.v(), stock);
        MineWorksGameTests.kit(m, Items.IRON_PICKAXE);
        MineWorksGameTests.zone(r, m, 16);
        int y1 = MineWorks.levelY(level, r.v(), MineWorks.IRON);
        List<Integer> further = MineLevels.placesForTests(level, r.v(), MineWorks.IRON);
        helper.assertTrue(!further.isEmpty(), "further iron levels are planned for when the first is worked out");
        int y2 = further.get(0);
        int second = MineWorks.IRON + 5;
        // The shaft's rock; a small bed at the iron level (soon worked out), a big one with iron in it at the next.
        MineWorksGameTests.tower(r, Math.min(y1, y2) - 3);
        MineWorksGameTests.bed(r, y2, 30, Blocks.IRON_ORE, 220, 2424L);
        MineWorksGameTests.bed(r, y1, 11, Blocks.STONE, 0, 2425L);
        int corner = 4 * ((100 - Math.min(y1, y2)) / 3);
        MineWorks.sinkForTests(level, r.v(), corner + 1, m);
        MineWorks.roomForTests(level, r.v(), MineWorks.IRON);
        int side = ((100 - y1) / 3) % 4;
        int ox = side == 0 || side == 3 ? -1 : 1, oz = side < 2 ? -1 : 1;
        m.teleportTo(r.sx() + 6 * ox + 0.5, y1, r.sz() + 6 * oz + 0.5);
        Kit.log("[VT] gw24_01 the iron level at Y" + y1 + "; further iron levels at " + further + "; plan " + MineWorks.planForTests(level, r.v()));
        MineWorksGameTests.fast(level);
        final int[] tick = { 0 };
        final int[] spentAt = { -1 };
        final int[] mined = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            // The morning kept: the noon put-away (PutAway) sent the miner to the heart's chest on the flat far under the
            // test's tower of rock, a walk it could never make, over and over.
            if (level.getDayTime() % 24000L > 5000L) level.setDayTime(1000);
            // Its pack kept light, the ore it brings counted as it comes: with goods to spare in its pack the courier's
            // round (lendAHand) sent it off to the heart's chest on the flat under the test's tower of rock, a walk it could
            // never make, over and over.
            int raw = m.countCarried(s -> s.is(Items.RAW_IRON));
            if (raw > 0) { m.removeMatching(s -> s.is(Items.RAW_IRON), raw); mined[0] += raw; }
            m.removeMatching(s -> !s.is(net.minecraft.tags.ItemTags.PICKAXES) && !s.is(Items.TORCH) && !s.is(Items.BREAD) && !s.is(Items.COBBLESTONE), 999);
            int cobble = m.countCarried(s -> s.is(Items.COBBLESTONE));
            if (cobble > 16) m.removeMatching(s -> s.is(Items.COBBLESTONE), cobble - 16);
            MineWorksGameTests.keepAtIt(r, m);
            // A while on the iron level, then the rest of it as its miners would leave it, cut to its ends (a real level
            // takes weeks: drifts to the edges of the ground, the caves and the water, every branch cut); the miner finds
            // nothing left on it at its next look.
            if (tick[0] == 800) MineLevels.cutOutForTests(level, r.v(), MineWorks.IRON);
            if (spentAt[0] < 0 && MineLevels.spentForTests(level, r.v(), MineWorks.IRON)) {
                spentAt[0] = tick[0];
                Kit.log("[VT] gw24_01 the iron level worked out at tick " + tick[0] + "; plan " + MineWorks.planForTests(level, r.v()));
            }
            if (tick[0] % 1500 == 0) Kit.log("[VT] gw24_01 @" + tick[0] + " " + WorksGoal.stateForTests(m) + " — " + m.debugLine() + " || "
                + String.join(" | ", MineWorks.eventsForTests()));
            int got = mined[0];
            int onSecond = 0, cells = MineWorks.cellsForTests(level, r.v(), second).size();
            for (int[] d : MineWorks.driftsForTests(level, r.v())) if (d[1] == second) onSecond++;
            boolean ok = spentAt[0] >= 0 && onSecond >= 1 && cells >= 8 && got >= 4;
            if (!ok && tick[0] < 14500) return false;
            boolean told = false;
            for (Villages.News n : Villages.news(r.v())) if (n.text().contains("worked out") && n.text().contains("iron level")) told = true;
            Kit.log("[VT] gw24_01 after " + tick[0] + " ticks: iron level worked out at " + spentAt[0] + ", second iron level (Y" + y2 + ") "
                + onSecond + " drifts, " + cells + " cells; raw iron brought +" + got + "; told " + told + " | "
                + String.join(" | ", MineWorks.report(level, r.v())));
            helper.assertTrue(spentAt[0] >= 0, "the iron level is marked worked out");
            helper.assertTrue(MineWorks.planForTests(level, r.v()).stream().anyMatch(s -> s.startsWith(second + ":the second iron level")),
                "the second iron level is in the plan");
            helper.assertTrue(onSecond >= 1 && cells >= 8, "drifts driven on the second iron level: " + onSecond + " drifts, " + cells + " cells");
            helper.assertTrue(got >= 4, "raw iron comes in from it: " + got);
            helper.assertTrue(told, "the town's news tells of the level worked out and the next");
            MineDemand.stockForTests(r.v(), null);
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    /**
     * The smelters' coal kept back: in a Stone Age town with the stores' coal under the floor and two smelters' draws, the
     * town's works (a street light's torch, a campfire...) take none of it; with plenty, they take what they need.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "gw24_02_coal_kept")
    public static void gw24_02_coal_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, 5406000, 40, 3, StationTask.FARM);
        helper.assertTrue(!folk.isEmpty(), "the town");
        UUID v = folk.get(0).ownerId();
        Villages.ageForTests(v, Villages.Age.STONE);                          // smelting ore from the Stone Age on
        Villages.forgetStock();
        int start = Fuel.inStores(level, v);
        int floor = Fuel.floor(Villages.headcount(v)), keep = floor + Fuel.SMELT_SPARE;
        // Up to a few under what is kept back.
        int add = Math.max(0, keep - 4 - start);
        if (add > 0) Market.intoStoresForTests(level, v, new ItemStack(Items.COAL, add));
        Villages.forgetStock();
        int low = Fuel.inStores(level, v);
        boolean kept = Fuel.keptForSmelting(level, v);
        boolean tookLow = Fuel.takeCoalForTests(level, v, 1);
        Villages.forgetStock();
        int afterLow = Fuel.inStores(level, v);
        Market.intoStoresForTests(level, v, new ItemStack(Items.COAL, 40));
        Villages.forgetStock();
        int plenty = Fuel.inStores(level, v);
        boolean keptPlenty = Fuel.keptForSmelting(level, v);
        boolean tookPlenty = Fuel.takeCoalForTests(level, v, 1);
        Villages.forgetStock();
        int afterPlenty = Fuel.inStores(level, v);
        Kit.log("[VT] gw24_02 floor " + floor + ", kept back under " + keep + "; stores " + low + " coal: kept " + kept + ", the works took " + tookLow
            + " (" + low + " -> " + afterLow + "); stores " + plenty + ": kept " + keptPlenty + ", took " + tookPlenty + " (" + plenty + " -> " + afterPlenty + ")");
        helper.assertTrue(low < keep && kept, "under the floor and two draws the coal is kept back: " + low + " of " + keep);
        helper.assertTrue(!tookLow && afterLow == low, "the town's works take none of it: " + low + " -> " + afterLow);
        helper.assertTrue(plenty >= keep && !keptPlenty, "with plenty it is not kept back: " + plenty);
        helper.assertTrue(tookPlenty && afterPlenty == plenty - 1, "with plenty the works take what they need: " + plenty + " -> " + afterPlenty);
        helper.succeed();
    }

    /**
     * A town asleep beside an awake one: an awake town of fourteen (its heart held, its ground a square ten chunks each
     * way) and a small town a hundred and fifty blocks off, inside that square. The small one sleeps (nobody near, rates of
     * its own): its folk are let go with its chunks, though they stand on its awake neighbour's ground; the awake town's
     * folk all stay loaded and its chunks forced. Before, the sleeper's chunks inside the awake town's square stayed forced
     * and its folk kept working, asleep.
     */
    @GameTest(template = "empty", timeoutTicks = 20000, batch = "gw24_03_sleeper_let_go")
    public static void gw24_03_sleeper_let_go(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int ax = 5410000, bx = ax + 150;
        Kit.reset(level);
        Kit.hold(level, ax + 75, Z, 140);
        Kit.prepare(level, ax + 75, Z, 140);
        BlockPos ah = Kit.surface(level, ax, Z), bh = Kit.surface(level, bx, Z);
        List<VillageFolkEntity> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 0; i < 14; i++) { VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, ah, 0.0F); if (f != null) a.add(f); }
        Villages.Village bv = Villages.found(level, bh);
        for (int i = 0; i < 6; i++) { VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, bh, 0.0F); if (f != null) b.add(f); }
        helper.assertTrue(a.size() == 14 && b.size() == 6, "two towns: " + a.size() + " and " + b.size());
        UUID av = a.get(0).ownerId(), bid = b.get(0).ownerId();
        helper.assertTrue(!av.equals(bid) && bid.equals(bv.id()), "two towns, not one");
        TownLod.ratesForTests(bid, new TownLod.Rates(Map.of("bread", 1.0), 1.0, 0.0, 5.0));
        final int[] tick = { 0 };
        final int[] forcedAwake = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (tick[0] == 600) {
                // The towns' own chunk keeping in force by now: the test's hold off, the big town's heart held as an
                // operator holds it (TownWake: HELD), the small one with rates of its own (not too young to sleep).
                for (int x = (ax + 75 - 140) >> 4; x <= (ax + 75 + 140) >> 4; x++)
                    for (int z = (Z - 140) >> 4; z <= (Z + 140) >> 4; z++) level.setChunkForced(x, z, false);
                level.setChunkForced(ah.getX() >> 4, ah.getZ() >> 4, true);
                return false;
            }
            if (tick[0] == 900) {
                helper.assertTrue(AssistantEntity.countFor(bid) == 6 && AssistantEntity.countFor(av) == 14,
                    "both towns' folk loaded by their own chunk keeping: " + AssistantEntity.countFor(av) + ", " + AssistantEntity.countFor(bid));
                TownLod.liveForTests(true);
                TownWake.liveForTests(true);
                TownLod.lookForTests(level.getServer());
                forcedAwake[0] = ChunkLoad.forcedCount();
                int r = com.jrpetty.mcassistant.village.VillageMath.storesRadius(Villages.headcount(av));
                Kit.log("[VT] gw24_03 the big town " + (TownLod.away(av) ? "asleep" : "awake") + " (stores' reach " + r + "), the small one "
                    + (TownLod.away(bid) ? "asleep" : "awake") + "; forced chunks " + forcedAwake[0]);
                helper.assertTrue(!TownLod.away(av) && TownLod.away(bid), "the held town awake, the other asleep");
                ChunkPos bc = new ChunkPos(bh);
                int area = (com.jrpetty.mcassistant.village.VillageMath.storesRadius(Villages.headcount(av)) >> 4) + 3;
                helper.assertTrue(Math.abs(bc.x - (ah.getX() >> 4)) <= area, "the sleeper's heart inside the awake town's ground: " + (bc.x - (ah.getX() >> 4)) + " of " + area);
                return false;
            }
            if (tick[0] < 900) return false;
            int bLoaded = AssistantEntity.countFor(bid), aLoaded = AssistantEntity.countFor(av);
            if (bLoaded > 0 && tick[0] < 18000) return false;
            long bChunk = ChunkPos.asLong(bh.getX() >> 4, bh.getZ() >> 4);
            List<String> who = new ArrayList<>();
            for (String o : ChunkLoad.ownersForTests(level, bChunk)) {
                UUID id = UUID.fromString(o.substring(0, o.length() - 1));
                who.add((id.equals(av) ? "A" : id.equals(bid) ? "B" : level.getEntity(id) instanceof VillageFolkEntity f
                    ? (av.equals(f.ownerId()) ? "A-folk" : bid.equals(f.ownerId()) ? "B-folk" : "folk") : "other") + o.charAt(o.length() - 1));
            }
            for (VillageFolkEntity f : b) if (f.isAlive()) { who.add("B folk at " + f.blockPosition().toShortString()); break; }
            Kit.log("[VT] gw24_03 the sleeper's heart chunk: held by " + who + "; " + level.getChunkSource().getChunkDebugData(new ChunkPos(bChunk)));
            Kit.log("[VT] gw24_03 after " + tick[0] + " ticks: the sleeper's folk loaded " + bLoaded + " (roll " + Villages.headcount(bid)
                + "), the awake town's " + aLoaded + " of 14; forced chunks " + ChunkLoad.forcedCount() + " (were " + forcedAwake[0] + ")");
            helper.assertTrue(bLoaded == 0, "the sleeping town's folk are let go: " + bLoaded + " still loaded");
            helper.assertTrue(aLoaded == 14, "the awake town's folk all stay: " + aLoaded);
            helper.assertTrue(Villages.headcount(bid) == 6, "the sleeper's roll is kept: " + Villages.headcount(bid));
            level.setChunkForced(ah.getX() >> 4, ah.getZ() >> 4, false);
            MineSiteGameTests.standDown(level);
            helper.runAfterDelay(1, helper::succeed);
            return true;
        });
    }
    /**
     * Miners kept at the mine while the age waits on it: an Iron Age town (two farmers, the rest miners) short of its
     * iron calls a hand to the levelling, and a farmer goes, never a miner; the same town in the Wood Age (nothing asked of
     * the mine) may send a miner.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "gw24_04_miners_kept")
    public static void gw24_04_miners_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        com.jrpetty.mcassistant.entity.MineHands.forgetForTests();
        final int x = 5414000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        level.setDayTime(24000L * 4 + 2000);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 10);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null, "a village");
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && !f.isBaby()) all.add(f);
        helper.assertTrue(all.size() >= 6, "a town of six and more: " + all.size());
        Villages.ageForTests(v.id(), Villages.Age.IRON);
        helper.runAtTickTime(10, () -> {
            level.setDayTime(24000L * 4 + 2000);
            for (int i = 0; i < all.size(); i++) all.get(i).setJob(i < all.size() - 2 ? StationTask.MINE : StationTask.FARM);
            boolean kept = com.jrpetty.mcassistant.entity.MineHands.kept(level, v.id());
            com.jrpetty.mcassistant.entity.TownJobs.atWork(level, v, "levelling", heart.offset(12, 0, 0), "levelling the ground");
            VillageFolkEntity hand = com.jrpetty.mcassistant.entity.TownJobs.handAt(level, v.id(), "levelling");
            StationTask ironAge = hand == null ? null : hand.stationTask();
            com.jrpetty.mcassistant.entity.TownJobs.resetForTests();
            com.jrpetty.mcassistant.entity.MineHands.forgetForTests();
            Villages.ageForTests(v.id(), Villages.Age.WOOD);
            boolean keptWood = com.jrpetty.mcassistant.entity.MineHands.kept(level, v.id());
            com.jrpetty.mcassistant.entity.TownJobs.atWork(level, v, "levelling", heart.offset(12, 0, 0), "levelling the ground");
            VillageFolkEntity woodHand = com.jrpetty.mcassistant.entity.TownJobs.handAt(level, v.id(), "levelling");
            com.jrpetty.mcassistant.entity.TownJobs.resetForTests();
            List<String> trades = new ArrayList<>();
            for (VillageFolkEntity f : all) trades.add(f.stationTask() + (f.isSleeping() ? " asleep" : ""));
            Kit.log("[VT] gw24_04 the folk " + trades + "; the Wood Age's hand " + (woodHand == null ? "none" : woodHand.stationTask()));
            com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
            Kit.log("[VT] gw24_04 " + all.size() + " folk, two farmers; Iron Age, short of iron: the age waits on the mine " + kept + ", the levelling's hand a "
                + ironAge + "; the Wood Age: waits " + keptWood);
            helper.assertTrue(kept, "the Iron Age short of iron waits on the mine");
            helper.assertTrue(ironAge != null && ironAge != StationTask.MINE, "a hand to the levelling, and not a miner: " + ironAge);
            helper.assertTrue(!keptWood, "the Wood Age asks nothing of the mine");
            helper.succeed();
        });
    }
    /**
     * The iron drain named and stopped: an Iron Age town short of its age's iron, a smith of long standing, thirty bars in
     * the stores and the miners' rack empty. The smith's forge turns no bar into an iron pick for the stores (a stone pick
     * cuts iron ore; the rack is kept in stone till the iron is past its mark), and the town keeps its bars; past the mark
     * an iron pick is forged again. What iron does go out is booked in the mine's books by what took it (Crafts.forge).
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "gw24_05_iron_kept")
    public static void gw24_05_iron_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.OreFlow.forgetForTests();
        final int x = 5418000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        level.setDayTime(24000L * 4 + 2000);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 10);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null, "a village");
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && !f.isBaby()) all.add(f);
        helper.runAtTickTime(5, () -> {
            for (int i = 0; i < all.size(); i++) all.get(i).setJob(i == 0 ? StationTask.SMITH : i < 7 ? StationTask.MINE : StationTask.FARM);
            VillageFolkEntity smith = all.get(0);
            smith.tradeXpForTests(StationTask.SMITH, 20000);
            Villages.ageForTests(v.id(), Villages.Age.IRON);
            com.jrpetty.mcassistant.entity.Picks.emptyRackForTests(level, v);
            for (BlockPos p : Villages.storeChests(level, v.id())) {
                if (level.getBlockEntity(p) instanceof net.minecraft.world.Container c) { c.clearContent(); c.setChanged(); }
            }
            Market.intoStoresForTests(level, v.id(), new ItemStack(Items.IRON_INGOT, 30));
            Market.intoStoresForTests(level, v.id(), new ItemStack(Items.OAK_PLANKS, 64));
            Market.intoStoresForTests(level, v.id(), new ItemStack(Items.STICK, 32));
            Market.intoStoresForTests(level, v.id(), new ItemStack(Items.COBBLESTONE, 64));
            Villages.forgetStock();
            java.util.function.Predicate<ItemStack> ironPick = st -> st.is(Items.IRON_PICKAXE);
            List<String> made = new ArrayList<>();
            for (int i = 0; i < 12; i++) made.add(String.valueOf(com.jrpetty.mcassistant.entity.Crafts.smithForTests(level, v, smith)));
            Villages.forgetStock();
            int iron = Market.stock(level, v.id(), st -> st.is(Items.IRON_INGOT));
            int picks = Market.stock(level, v.id(), ironPick);
            boolean saving = com.jrpetty.mcassistant.entity.MineHands.kept(level, v.id());
            // [econ32] Sixty bars, the age still short: no iron pick yet (the pick's mark lay under the age's ask, and the
            // forge took the stores back down to thirteen day after day); the age's iron in hand: the forge makes it again.
            Market.intoStoresForTests(level, v.id(), new ItemStack(Items.IRON_INGOT, 30));
            Villages.forgetStock();
            for (int i = 0; i < 6; i++) { com.jrpetty.mcassistant.entity.Crafts.smithForTests(level, v, smith); Villages.forgetStock(); }
            int picksSixty = Market.stock(level, v.id(), ironPick);
            helper.assertTrue(picksSixty == 0, "no iron pick at sixty bars while the age is short: " + picksSixty);
            for (int i = 0; i < 5; i++) Market.intoStoresForTests(level, v.id(), new ItemStack(Items.IRON_INGOT, 64));
            com.jrpetty.mcassistant.entity.MineHands.forgetForTests();
            Villages.forgetStock();
            List<String> then = new ArrayList<>();
            for (int i = 0; i < 12 && Market.stock(level, v.id(), ironPick) == 0; i++) {
                then.add(String.valueOf(com.jrpetty.mcassistant.entity.Crafts.smithForTests(level, v, smith)));
                Villages.forgetStock();
            }
            int picksThen = Market.stock(level, v.id(), ironPick);
            List<String> books = com.jrpetty.mcassistant.entity.OreFlow.lines(v.id(), level.getDayTime() / 24000L);
            Kit.log("[VT] gw24_05 saving " + saving + ": thirty bars, the smith " + made + "; bars left " + iron + ", iron picks " + picks
                + "; with sixty: " + then + ", iron picks " + picksThen + " | books " + books);
            helper.assertTrue(saving, "the Iron Age short of its iron is saving it");
            helper.assertTrue(picks == 0, "no iron pick forged for the stores while the age saves its iron: " + picks);
            helper.assertTrue(iron >= 24, "the town keeps its bars: " + iron + " of 30");
            helper.assertTrue(picksThen >= 1, "the age's iron in hand: an iron pick is forged again: " + picksThen);
            helper.assertTrue(books.stream().anyMatch(l -> l.contains("Crafts.forge")), "the books name the forge: " + books);
            helper.succeed();
        });
    }
    /**
     * The mine's ground widened when every level of an ore is worked out: the iron level and its five further levels done
     * with, the last of them found worked out by its miner; the ground takes in the faces of the next ring out that touch it
     * (none on the town's ground), the drifts stopped at the old edge on the iron's levels are opened again and those levels
     * are no longer worked out. The news says so.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "gw24_06_ground_widened")
    public static void gw24_06_ground_widened(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 5422000, 1);
        ServerLevel level = r.level();
        UUID v = r.v();
        Villages.ageForTests(v, Villages.Age.IRON);
        List<Integer> further = MineLevels.placesForTests(level, v, MineWorks.IRON);
        helper.assertTrue(!further.isEmpty(), "further iron levels planned");
        int last = MineWorks.IRON + 5 * further.size();
        for (int k = 0; k < further.size(); k++) {
            int id = MineWorks.IRON + 5 * k;
            MineLevels.cutOutForTests(level, v, id);
            MineLevels.spendForTests(level, v, id);
        }
        MineLevels.cutOutForTests(level, v, last);
        int faces = com.jrpetty.mcassistant.entity.MineGround.faceCount(v), ring = com.jrpetty.mcassistant.entity.MineGround.ring(v);
        String before = MineLevels.closedForTests(level, v, MineWorks.IRON) + "/" + MineLevels.closedForTests(level, v, last);
        MineLevels.markSpentForTests(level, v, last);
        int facesAfter = com.jrpetty.mcassistant.entity.MineGround.faceCount(v), ringAfter = com.jrpetty.mcassistant.entity.MineGround.ring(v);
        String after = MineLevels.closedForTests(level, v, MineWorks.IRON) + "/" + MineLevels.closedForTests(level, v, last);
        boolean spentFirst = MineLevels.spentForTests(level, v, MineWorks.IRON), spentLast = MineLevels.spentForTests(level, v, last);
        // None of the new ground on the town's.
        BlockPos site = new BlockPos(r.sx(), 0, r.sz());
        int inTown = 0, added = 0;
        for (int i = -ringAfter; i <= ringAfter; i++) {
            for (int j = -ringAfter; j <= ringAfter; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ringAfter || !com.jrpetty.mcassistant.entity.MineGround.faceReserved(v, i, j)) continue;
                added++;
                BlockPos c = site.offset(i * com.jrpetty.mcassistant.entity.TownMine.PITCH, 0, j * com.jrpetty.mcassistant.entity.TownMine.PITCH);
                if (com.jrpetty.mcassistant.entity.TownDigs.inTownGroundRaw(level, v, r.heart(), c, 0)) inTown++;
            }
        }
        boolean told = false;
        for (Villages.News n : Villages.news(v)) if (n.text().contains("ground was widened")) told = true;
        Kit.log("[VT] gw24_06 the iron's " + (further.size() + 1) + " levels worked out: faces " + faces + " (ring " + ring + ") -> " + facesAfter
            + " (ring " + ringAfter + "), " + added + " in the new ring, " + inTown + " on the town's ground; drifts closed " + before + " -> " + after
            + "; worked out: first " + spentFirst + ", last " + spentLast + "; told " + told);
        helper.assertTrue(facesAfter > faces && ringAfter == ring + 1, "the ground widened by a ring: " + faces + " -> " + facesAfter);
        helper.assertTrue(inTown == 0, "none of it on the town's ground: " + inTown);
        helper.assertTrue(!after.contains("e") && after.contains("-"), "the drifts stopped at the edge driven on: " + before + " -> " + after);
        helper.assertTrue(!spentFirst && !spentLast, "the iron's levels worked again");
        helper.assertTrue(told, "the news tells of it");
        helper.succeed();
    }
    /**
     * A friend's delivery on account: a stone town and a town short of stone with nothing the stone town wants and no coin to
     * spare (it pays its hands every day). With no takings to its name the envoy comes home "the buyer cannot pay"; once it
     * made a hundred and twenty coin's worth yesterday, the stone is sold it on credit (a quarter of that).
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "gw24_07_trade_on_credit")
    public static void gw24_07_trade_on_credit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.TradeDeals.resetForTests();
        level.setDayTime(24000L * 6 + 3000);
        Villages.Village[] t = new Villages.Village[2];
        for (int i = 0; i < 2; i++) {
            int cx = 5426000 + i * 300;
            Kit.hold(level, cx, Z, 40);
            Kit.prepare(level, cx, Z, 40);
            BlockPos heart = Kit.surface(level, cx, Z);
            VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 5);
            t[i] = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
            helper.assertTrue(t[i] != null, "a town at " + cx);
            for (AssistantEntity a : Villages.folkOf(t[i].id())) if (a instanceof VillageFolkEntity f) f.ensurePersona();
            for (BlockPos p : Villages.storeChests(level, t[i].id())) {
                if (level.getBlockEntity(p) instanceof net.minecraft.world.Container c) { c.clearContent(); c.setChanged(); }
            }
            Villages.chooseElder(t[i].id(), level.getDayTime() / 24000L);
            com.jrpetty.mcassistant.entity.TradeTalks.temperForTests(t[i].id(), com.jrpetty.mcassistant.entity.Envoys.Temper.STEADY);
        }
        UUID a = t[0].id(), b = t[1].id();
        helper.assertTrue(!a.equals(b), "two towns");
        com.jrpetty.mcassistant.village.Ledger.relate(a, b, 25);
        for (int i = 0; i < 40; i++) Market.intoStoresForTests(level, a, new ItemStack(Items.COBBLESTONE, 64));
        com.jrpetty.mcassistant.village.Ledger.takeCoins(b, com.jrpetty.mcassistant.village.Ledger.coins(b));   // nothing to spare
        Villages.forgetStock();
        com.jrpetty.mcassistant.entity.TradeBook.forget(a);
        com.jrpetty.mcassistant.entity.TradeBook.forget(b);
        com.jrpetty.mcassistant.village.Ledger.note(b, "output.week", "");
        com.jrpetty.mcassistant.entity.TradeTalks.Talk none = com.jrpetty.mcassistant.entity.TradeTalks.negotiate(level, a, b);
        com.jrpetty.mcassistant.village.Ledger.note(b, "output.week", "120,110,100");
        int line = com.jrpetty.mcassistant.entity.TradeCredit.line(b, a);
        com.jrpetty.mcassistant.entity.TradeTalks.Talk k = com.jrpetty.mcassistant.entity.TradeTalks.negotiate(level, a, b);
        Kit.log("[VT] gw24_07 no takings: " + none.end() + " (" + none.why() + "); takings 120 a day, credit " + line + ": " + k.end() + " — "
            + (k.deal() ? com.jrpetty.mcassistant.entity.TradeTalks.terms(k) : k.why()));
        helper.assertTrue(!none.deal() && none.why().contains("cannot pay"), "with nothing to its name, the buyer cannot pay: " + none.why());
        helper.assertTrue(line == 30, "a quarter of yesterday's takings on credit: " + line);
        helper.assertTrue(k.deal() && k.table().x() == Items.COBBLESTONE && k.table().buyer().equals(b), "the stone sold it on account: " + k.end() + " " + k.why());
        helper.succeed();
    }
    /**
     * The iron at the smelters is the town's: an Iron Age town short of its age's iron, its stores empty; forty raw iron in
     * its smelter's pack (taken out to be smelted) comes off what the age still asks for.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "gw24_08_iron_at_smelter")
    public static void gw24_08_iron_at_smelter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.IronInHand.forgetForTests();
        final int x = 5430000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 6);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null, "a village");
        helper.runAtTickTime(5, () -> {
            List<VillageFolkEntity> all = new ArrayList<>();
            for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && !f.isBaby()) all.add(f);
            VillageFolkEntity smelter = all.get(0);
            smelter.setJob(StationTask.SMELT);
            Villages.ageForTests(v.id(), Villages.Age.IRON);
            int before = ironAsked(level, v.id());
            smelter.insertItem(new ItemStack(Items.RAW_IRON, 40));
            com.jrpetty.mcassistant.entity.IronInHand.forgetForTests();
            int after = ironAsked(level, v.id());
            Kit.log("[VT] gw24_08 the age asks for " + before + " iron; forty raw iron at the smelter: " + after);
            helper.assertTrue(before > 0 && after == Math.max(0, before - 40), "the smelter's forty come off the age's ask: " + before + " -> " + after);
            helper.succeed();
        });
    }

    private static int ironAsked(ServerLevel level, UUID village) {
        Villages.forgetStock();
        for (Villages.Need n : Villages.needs(level, village)) if (n.task() == Villages.Task.IRON) return n.amount();
        return 0;
    }
}
