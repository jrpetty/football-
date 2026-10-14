package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.TownLod;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * [scale] Towns' level of detail (TownLod), in the scale band (x 1840000 on, z 66000): a town with nobody near sleeps
 * (its chunks let go, its folk saved with them, its stores and treasury kept by its step) and wakes with what it made;
 * and a town simulated asleep for ten days changes as it did over ten days in full.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TownLodGameTests {

    private static final int Z = 66000;

    /**
     * Run {@code step} every server tick till it says it is done. Not helper.onEachTick: that books an entry for every
     * tick up to the timeout and walks them all each tick, and these tests run for hundreds of thousands. A failed
     * assertion is handed to the test framework to fail the test, and a pass (pass) too: the framework starts the next
     * batch from inside a pass, and not from inside a tick listener.
     */
    private static void each(GameTestHelper helper, java.util.function.BooleanSupplier step) {
        final boolean[] over = { false };
        Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> listener = e -> {
            if (over[0]) return;
            try {
                over[0] = step.getAsBoolean();
            } catch (RuntimeException ex) {
                over[0] = true;
                Kit.log("lod test stopped: " + ex);
                helper.getLevel().getServer().tickRateManager().setTickRate(20.0F);
                TownLod.forceForTests(helper.getLevel().getServer(), null);
                com.jrpetty.mcassistant.entity.FolkLod.liveForTests(false);
                helper.runAfterDelay(1, () -> { throw ex; });
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
    }

    /** The test passed, said from the framework's own tick. */
    private static void pass(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
    }

    /** Found a village of this many here, the ground held only for the founding; then {@code then} with it. */
    private static void found(GameTestHelper helper, int cx, int folk, Consumer<Villages.Village> then) {
        found(helper, cx, folk, false, then);
    }

    /** As found, with ponds, woods, stone hills and cattle round the town first (Kit.generousTerrain) if asked. */
    private static void found(GameTestHelper helper, int cx, int folk, boolean generous, Consumer<Villages.Village> then) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        int outer = FoundingPlan.coreRadius(folk) + FoundingPlan.BAND_MAX + 16;
        Kit.hold(level, cx, Z, outer);
        Kit.prepare(level, cx, Z, outer);
        if (generous) Kit.generousTerrain(level, cx, Z);
        BlockPos heart = Kit.surface(level, cx, Z);
        Kit.log("lod found " + folk + ": " + Kit.command(level, "village found " + folk + " " + cx + " " + Z));
        TownLod.forceForTests(level.getServer(), TownLod.Lod.FULL);
        each(helper, () -> {
            if (Founding.near(level, heart, 8)) return false;
            Villages.Village v = Villages.nearest(level, heart, 64);
            helper.assertTrue(v != null, "the village is founded");
            // The test's hold off: from here only the town's own chunk loading keeps its ground.
            for (int x = (cx - outer) >> 4; x <= (cx + outer) >> 4; x++)
                for (int z = (Z - outer) >> 4; z <= (Z + outer) >> 4; z++) level.setChunkForced(x, z, false);
            then.accept(v);
            return true;
        });
    }

    private static int all(ServerLevel level, UUID id) {
        return Market.stock(level, id, s -> true);
    }

    private static int bread(ServerLevel level, UUID id) {
        return Market.stock(level, id, s -> s.is(Items.BREAD));
    }

    /**
     * A town of twelve with nobody near goes to sleep: the chunks it forced are let go, its folk are saved with their
     * chunks (none loaded, the roll still twelve), its stores read as they stood; its step makes bread and coin at its
     * rates; it is still asleep with its bread after a restart (its state is in its ledger); and when somebody comes
     * near it wakes, its twelve folk back and the bread in its real chests.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "lod01_sleep_wake")
    public static void lod01_sleep_wake(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int[] phase = { 0 };
        final long[] at = { 0 };
        final int[] before = new int[4];     // all goods, bread, coins, forced chunks
        found(helper, 1846000, 12, v -> {
            UUID id = v.id();
            each(helper, () -> {
                long t = helper.getTick();
                switch (phase[0]) {
                    case 0 -> {                                 // awake, settling in: its chunks forced
                        if (at[0] == 0) at[0] = t;
                        if (t - at[0] < 300) return false;
                        before[0] = all(level, id);
                        before[1] = bread(level, id);
                        before[2] = Ledger.coins(id);
                        before[3] = ChunkLoad.forcedCount();
                        helper.assertTrue(before[3] > 0, "the town forces its chunks while it is awake");
                        TownLod.ratesForTests(id, new TownLod.Rates(Map.of("bread", 2.0), 3.0, 0.0, 5.0));
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.AWAY);
                        helper.assertTrue(TownLod.away(id), "with nobody near the town sleeps");
                        helper.assertTrue(ChunkLoad.forcedCount() < before[3],
                            "its forced chunks are let go: " + before[3] + " -> " + ChunkLoad.forcedCount());
                        helper.assertTrue(all(level, id) == before[0],
                            "its stores read as they stood: " + before[0] + " -> " + all(level, id));
                        phase[0] = 1;
                        at[0] = t;
                    }
                    case 1 -> {                                 // its folk saved with their chunks
                        if (AssistantEntity.countFor(id) > 0) {
                            if (t - at[0] > 4000) helper.fail("its folk are still loaded " + (t - at[0]) + " ticks on: " + AssistantEntity.countFor(id));
                            return false;
                        }
                        Kit.log("lod01 folk unloaded after " + (t - at[0]) + " ticks; roll " + Villages.headcount(id));
                        helper.assertTrue(Villages.headcount(id) == 12, "the roll is still twelve: " + Villages.headcount(id));
                        TownLod.stepForTests(id, 2.0);          // two days: 2 bread and 3 coin a head a day
                        helper.assertTrue(bread(level, id) == before[1] + 48,
                            "two days' bread for twelve is in its stores: " + before[1] + " -> " + bread(level, id));
                        helper.assertTrue(Ledger.coins(id) == before[2] + 72,
                            "two days' coin for twelve is in its treasury: " + before[2] + " -> " + Ledger.coins(id));
                        // A restart while it sleeps: what is in memory is gone, the ledger keeps the rest.
                        TownLod.reset();
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.AWAY);
                        helper.assertTrue(TownLod.away(id) && bread(level, id) == before[1] + 48,
                            "after a restart it is still asleep, its bread kept: " + bread(level, id));
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.FULL);
                        helper.assertTrue(!TownLod.away(id), "somebody near: it wakes");
                        phase[0] = 2;
                        at[0] = t;
                    }
                    default -> {                                // awake again: its folk back, the bread in its chests
                        boolean back = AssistantEntity.countFor(id) == 12 && TownLod.settledForTests(id);
                        if (!back) {
                            if (t - at[0] > 4000) helper.fail("not back after " + (t - at[0]) + " ticks: folk "
                                + AssistantEntity.countFor(id) + ", settled " + TownLod.settledForTests(id));
                            return false;
                        }
                        int now = realBread(level, id);
                        Kit.log("lod01 woke after " + (t - at[0]) + " ticks: bread in the chests " + now + " (was " + before[1] + ")");
                        helper.assertTrue(now >= before[1] + 40, "the bread made asleep is in its real chests: " + before[1] + " -> " + now);
                        TownLod.forceForTests(level.getServer(), null);
                        pass(helper);
                        return true;
                    }
                }
                return false;
            });
        });
    }

    /**
     * Out of sight, folk travel their walks (FolkLod): in a town of twelve with nobody near, walks are travelled, and
     * every folk ends each one standing on firm ground, not in a block or the water; with a player at the heart, no folk
     * in that player's sight is ever travelling, every tick for twenty seconds.
     */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "lod03_travel_unseen")
    public static void lod03_travel_unseen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final long[] at = { 0, 0 };
        final net.minecraft.server.level.ServerPlayer[] you = { null };
        found(helper, 1854000, 12, v -> {
            UUID id = v.id();
            com.jrpetty.mcassistant.entity.FolkLod.liveForTests(true);
            at[1] = com.jrpetty.mcassistant.entity.FolkNavigation.travelledInAll();
            each(helper, () -> {
                long t = helper.getTick();
                if (at[0] == 0) at[0] = t;
                long ran = t - at[0];
                if (ran < 1200) return false;                   // a minute with nobody near
                if (ran == 1200) {
                    long travelled = com.jrpetty.mcassistant.entity.FolkNavigation.travelledInAll() - at[1];
                    Kit.log("lod03 walks travelled out of sight in a minute: " + travelled);
                    helper.assertTrue(travelled > 0, "out of sight, folk travel their walks");
                    for (AssistantEntity a : Villages.folkOf(id)) {
                        BlockPos p = a.blockPosition();
                        helper.assertTrue(level.getBlockState(p).getCollisionShape(level, p).isEmpty() && !a.isInWaterOrBubble(),
                            a.getAssistantName() + " stands clear and dry at " + p.toShortString());
                    }
                    you[0] = helper.makeMockServerPlayerInLevel();
                    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(you[0].connection.getConnection());
                    you[0].teleportTo(level, v.centre().getX() + 0.5, v.centre().getY() + 1, v.centre().getZ() + 0.5, 0, 0);
                    return false;
                }
                for (AssistantEntity a : Villages.folkOf(id)) {
                    if (a.distanceTo(you[0]) < com.jrpetty.mcassistant.entity.FolkLod.SIGHT - 8
                            && a.getNavigation() instanceof com.jrpetty.mcassistant.entity.FolkNavigation n && n.travelling()) {
                        helper.fail(a.getAssistantName() + " travels in a player's sight, " + (int) a.distanceTo(you[0]) + " blocks off");
                    }
                }
                if (ran < 1600) return false;
                com.jrpetty.mcassistant.entity.FolkLod.liveForTests(false);
                level.getServer().getPlayerList().remove(you[0]);
                pass(helper);
                return true;
            });
        });
    }

    /** The bread in the town's store chests themselves (not the kept count). */
    private static int realBread(ServerLevel level, UUID id) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.BREAD)) n += c.getItem(i).getCount();
        }
        return n;
    }

    private static final double DAYS = Double.parseDouble(System.getProperty("lod.days", "10"));
    /** Days the town settles in before it is measured: its founding stores eaten down, its fields and pens up. */
    private static final int WARM = 3;
    /** Below this many of a kind over the stretch, the kind is too few to compare (a handful either way). */
    private static final int FEW = 24;
    /** How near the sleeping town's change must come to the watched town's, of the watched one's. */
    private static final double TOLERANCE = 0.2;

    /** The stores as kinds of goods (food, timber, stone...), and the treasury and roll. */
    private static Map<String, Integer> picture(ServerLevel level, UUID id) {
        Map<String, Integer> m = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                Economy.Kind k = Economy.kindOf(s);
                m.merge(k == null ? "other" : k.name().toLowerCase(), s.getCount(), Integer::sum);
            }
        }
        m.put("coin", Ledger.coins(id));
        m.put("folk", Villages.recordedPopulation(id));
        return m;
    }

    /**
     * A town of twenty on generous ground (it farms, fells, quarries and keeps cattle), watched for three days to
     * settle and then for {@code lod.days} days (ten), and then asleep for as long: its stores (kind by kind), treasury
     * and roll change asleep as they did watched, each within a fifth (or a couple of dozen, for a kind barely moved).
     */
    @GameTest(template = "empty", timeoutTicks = 1_200_000, batch = "lod02_full_vs_away")
    public static void lod02_full_vs_away(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final long span = Math.round(DAYS * 24000);
        final int[] phase = { 0 };
        final long[] at = { 0 };
        final Map<String, Integer>[] pic = new Map[3];
        found(helper, 1850000, 20, true, v -> {
            UUID id = v.id();
            level.getServer().tickRateManager().setTickRate(10000.0F);
            each(helper, () -> {
                long t = helper.getTick();
                if (at[0] == 0) at[0] = t;
                long ran = t - at[0];
                if (ran % 24000 == 0) Kit.log("lod02 phase " + phase[0] + " day " + ran / 24000 + ": " + picture(level, id));
                switch (phase[0]) {
                    case 0 -> {                                 // days to settle in (stores spent down, fields up)
                        if (ran < WARM * 24000L) return false;
                        pic[0] = picture(level, id);
                        phase[0] = 1;
                        at[0] = t;
                    }
                    case 1 -> {                                 // watched, in full
                        if (ran < span) return false;
                        pic[1] = picture(level, id);
                        Kit.log("lod02 rates learned: " + TownLod.ratesFor(id));
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.AWAY);
                        phase[0] = 2;
                        at[0] = t;
                    }
                    case 2 -> {                                 // asleep, by its step
                        if (ran < span) return false;
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.FULL);
                        phase[0] = 3;
                        at[0] = t;
                    }
                    default -> {                                // awake: what it did asleep in its chests
                        if (!TownLod.settledForTests(id) || ran < 200) {
                            if (ran > 6000) helper.fail("the woken town's goods were not settled in 6000 ticks");
                            return false;
                        }
                        pic[2] = picture(level, id);
                        level.getServer().tickRateManager().setTickRate(20.0F);
                        compare(helper, pic);
                        TownLod.forceForTests(level.getServer(), null);
                        pass(helper);
                        return true;
                    }
                }
                return false;
            });
        });
    }

    private static void compare(GameTestHelper helper, Map<String, Integer>[] pic) {
        java.util.Set<String> kinds = new java.util.TreeSet<>(pic[0].keySet());
        kinds.addAll(pic[1].keySet());
        kinds.addAll(pic[2].keySet());
        StringBuilder report = new StringBuilder();
        StringBuilder wrong = new StringBuilder();
        for (String k : kinds) {
            int full = pic[1].getOrDefault(k, 0) - pic[0].getOrDefault(k, 0);
            int away = pic[2].getOrDefault(k, 0) - pic[1].getOrDefault(k, 0);
            report.append(k).append(" full ").append(full).append(" away ").append(away).append("; ");
            double allowed = Math.max(TOLERANCE * Math.abs(full), "folk".equals(k) ? 2 : FEW);
            if (Math.abs(away - full) > allowed) wrong.append(k).append(' ');
        }
        Kit.log("lod02 " + DAYS + " days, full vs away: " + report);
        helper.assertTrue(wrong.isEmpty(), "asleep the town changes as it did watched (within a fifth); not so for: "
            + wrong + "| " + report);
    }
}
