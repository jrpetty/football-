package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.TownLod;
import com.jrpetty.mcassistant.entity.TownWake;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.FoundingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * [long6] Towns that go on with nobody near, in the long band (x 3,600,000 on, z 90000). The four-hundred-day run's town
 * went to sleep five seconds after its founding with no rates of its own to sleep at, and stood at day one for four
 * hundred days. Now a town too young to have rates stays awake on its own chunk keeping; a sleeping town has a day of
 * its own in full every few days; a town whose heart an operator force-loaded runs in full.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class LongGameTests {

    private static final int Z = 90000;

    /** Run {@code step} every server tick till it says it is done (as TownLodGameTests.each). */
    private static void each(GameTestHelper helper, java.util.function.BooleanSupplier step) {
        final boolean[] over = { false };
        Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> listener = e -> {
            if (over[0]) return;
            try {
                over[0] = step.getAsBoolean();
            } catch (RuntimeException ex) {
                over[0] = true;
                Kit.log("long test stopped: " + ex);
                standDown(helper.getLevel());
                helper.runAfterDelay(1, () -> { throw ex; });
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
    }

    private static void standDown(ServerLevel level) {
        level.getServer().tickRateManager().setTickRate(20.0F);
        TownWake.liveForTests(false);
        TownLod.liveForTests(false);
        TownLod.forceForTests(level.getServer(), null);
    }

    /** Found a village of this many here, the test's hold on the ground let go once it stands; then {@code then}. */
    private static void found(GameTestHelper helper, int cx, int folk, Consumer<Villages.Village> then) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        int outer = FoundingPlan.coreRadius(folk) + FoundingPlan.BAND_MAX + 16;
        Kit.hold(level, cx, Z, outer);
        Kit.prepare(level, cx, Z, outer);
        Kit.generousTerrain(level, cx, Z);
        BlockPos heart = Kit.surface(level, cx, Z);
        Kit.log("long found " + folk + ": " + Kit.command(level, "village found " + folk + " " + cx + " " + Z));
        TownLod.forceForTests(level.getServer(), TownLod.Lod.FULL);
        each(helper, () -> {
            if (Founding.near(level, heart, 8)) return false;
            Villages.Village v = Villages.nearest(level, heart, 64);
            helper.assertTrue(v != null, "the village is founded");
            for (int x = (cx - outer) >> 4; x <= (cx + outer) >> 4; x++)
                for (int z = (Z - outer) >> 4; z <= (Z + outer) >> 4; z++) level.setChunkForced(x, z, false);
            then.accept(v);
            return true;
        });
    }

    /**
     * Nobody near a new town: it is too young to sleep (no rates of its own), so it stays awake with its chunks forced
     * and its folk loaded; once it has rates it sleeps; asleep two days it wakes for a day of its own, and sleeps again
     * when that is over; and with its heart force-loaded by an operator it runs in full whatever its rates.
     */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "lg01_kept_awake")
    public static void lg01_kept_awake(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        found(helper, 3600000, 8, v -> {
            UUID id = v.id();
            TownWake.liveForTests(true);
            TownLod.liveForTests(true);
            TownLod.lookForTests(level.getServer());
            String why = TownLod.keptAwakeWhy(id);
            Kit.log("lg01 young: away " + TownLod.away(id) + ", why " + why + ", forced " + ChunkLoad.forcedCount()
                + ", loaded " + Villages.loadedCount(id));
            helper.assertTrue(!TownLod.away(id) && why != null && why.contains("young"),
                "a new town with nobody near stays awake, too young to sleep: " + why);
            helper.assertTrue(ChunkLoad.forcedCount() > 0 && Villages.loadedCount(id) > 0,
                "its chunks forced and its folk loaded: " + ChunkLoad.forcedCount() + ", " + Villages.loadedCount(id));
            // Measured for five days: it has rates of its own, and nobody near puts it to sleep.
            TownLod.ratesForTests(id, new TownLod.Rates(Map.of("bread", 1.0), 1.0, 0.0, 5.0));
            int forcedAwake = ChunkLoad.forcedCount();
            TownLod.lookForTests(level.getServer());
            helper.assertTrue(TownLod.away(id), "with rates of its own and nobody near, it sleeps");
            helper.assertTrue(ChunkLoad.forcedCount() < forcedAwake, "its chunks let go: " + forcedAwake + " -> " + ChunkLoad.forcedCount());
            helper.assertTrue(VillageStatus.of(level, v).contains("asleep, nobody near"), "the status says it sleeps");
            // A day asleep: not yet its own day.
            TownLod.sleptDaysAgoForTests(id, 1.0);
            TownLod.lookForTests(level.getServer());
            helper.assertTrue(TownLod.away(id), "a day asleep, it sleeps on");
            // Two days and more asleep: its own day in full.
            TownLod.sleptDaysAgoForTests(id, 1.5);
            TownLod.lookForTests(level.getServer());
            why = TownLod.keptAwakeWhy(id);
            helper.assertTrue(!TownLod.away(id) && why != null && why.contains("own day"),
                "two days and a half asleep, it wakes for a day of its own: " + why);
            long until = TownLod.ownDayLeftForTests(id, 0);
            helper.assertTrue(until - level.getGameTime() > 24000, "its day is over a day long: " + (until - level.getGameTime()));
            // Its day over: it sleeps again.
            TownLod.ownDayLeftForTests(id, until - level.getGameTime() + 1);
            TownLod.lookForTests(level.getServer());
            helper.assertTrue(TownLod.away(id), "its day over, it sleeps again");
            // An operator force-loads its heart: it runs in full.
            ChunkPos c = new ChunkPos(v.centre());
            level.setChunkForced(c.x, c.z, true);
            TownLod.lookForTests(level.getServer());
            why = TownLod.keptAwakeWhy(id);
            level.setChunkForced(c.x, c.z, false);
            helper.assertTrue(!TownLod.away(id) && why != null && why.contains("force-loaded"),
                "its heart force-loaded, it is awake: " + why);
            helper.assertTrue(VillageStatus.of(level, v).contains("awake with nobody near"), "the status says why it is awake");
            standDown(level);
            helper.runAfterDelay(1, helper::succeed);
            return;
        });
    }

    /**
     * A town of eight with nobody near, the players' reckoning in force, goes on for real: two game days after its
     * founding it is still awake (its own chunk keeping), its folk loaded, and its stores have moved (gathered, eaten,
     * built with): it lives, rather than standing as it was founded.
     */
    @GameTest(template = "empty", timeoutTicks = 80000, batch = "lg02_nobody_near_grows")
    public static void lg02_nobody_near_grows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final long[] at = { 0 };
        final int[] built = { 0, 0 };
        found(helper, 3604000, 8, v -> {
            UUID id = v.id();
            TownWake.liveForTests(true);
            TownLod.liveForTests(true);
            TownLod.forceForTests(level.getServer(), null);
            built[0] = Villages.builtList(id).size();
            built[1] = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> true);
            level.getServer().tickRateManager().setTickRate(200.0F);
            each(helper, () -> {
                long t = helper.getTick();
                if (at[0] == 0) at[0] = t;
                long ran = t - at[0];
                if (ran % 6000 == 0) Kit.log("lg02 day " + String.format("%.2f", ran / 24000.0) + ": away " + TownLod.away(id)
                    + ", loaded " + Villages.loadedCount(id) + ", built " + Villages.builtList(id) + ", why " + TownLod.keptAwakeWhy(id));
                helper.assertTrue(!TownLod.away(id), "a town too young to have rates never sleeps (day "
                    + String.format("%.2f", ran / 24000.0) + ")");
                if (ran < 48000) return false;
                int loaded = Villages.loadedCount(id);
                java.util.List<String> now = Villages.builtList(id);
                int stores = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> true);
                Kit.log("lg02 two days on: loaded " + loaded + " of " + Villages.headcount(id) + ", built " + now + " (was " + built[0]
                    + "), stores " + stores + " (was " + built[1] + ")");
                helper.assertTrue(loaded > 0, "its folk are loaded two days on: " + loaded);
                helper.assertTrue(now.size() > built[0] || stores != built[1],
                    "it has built or its stores have moved with nobody near: " + now + ", stores " + built[1] + " -> " + stores);
                standDown(level);
                helper.runAfterDelay(1, helper::succeed);
                return true;
            });
        });
    }

    /** The status line, as /village status says it. */
    private static final class VillageStatus {
        static String of(ServerLevel level, Villages.Village v) {
            return com.jrpetty.mcassistant.VillageCommands.statusText(level, v);
        }
    }
}
