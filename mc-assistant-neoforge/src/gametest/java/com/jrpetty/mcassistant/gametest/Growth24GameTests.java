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
        int before = m.countCarried(s -> s.is(Items.RAW_IRON));
        Kit.log("[VT] gw24_01 the iron level at Y" + y1 + "; further iron levels at " + further + "; plan " + MineWorks.planForTests(level, r.v()));
        MineWorksGameTests.fast(level);
        final int[] tick = { 0 };
        final int[] spentAt = { -1 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            // The morning kept: the noon put-away (PutAway) sent the miner to the heart's chest on the flat far under the
            // test's tower of rock, a walk it could never make, over and over.
            if (level.getDayTime() % 24000L > 5000L) level.setDayTime(1000);
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
            int got = m.countCarried(s -> s.is(Items.RAW_IRON)) - before;
            int onSecond = 0, cells = MineWorks.cellsForTests(level, r.v(), second).size();
            for (int[] d : MineWorks.driftsForTests(level, r.v())) if (d[1] == second) onSecond++;
            boolean ok = spentAt[0] >= 0 && onSecond >= 1 && cells >= 8 && got >= 4;
            if (!ok && tick[0] < 14500) return false;
            boolean told = false;
            for (Villages.News n : Villages.news(r.v())) if (n.text().contains("worked out") && n.text().contains("iron level")) told = true;
            Kit.log("[VT] gw24_01 after " + tick[0] + " ticks: iron level worked out at " + spentAt[0] + ", second iron level (Y" + y2 + ") "
                + onSecond + " drifts, " + cells + " cells; raw iron carried +" + got + "; told " + told + " | "
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
}
