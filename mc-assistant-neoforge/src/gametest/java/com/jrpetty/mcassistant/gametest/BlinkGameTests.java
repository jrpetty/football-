package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.Blink;
import com.jrpetty.mcassistant.entity.MineDemand;
import com.jrpetty.mcassistant.entity.MineLift;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.goal.WorksGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * [tp43] No folk visibly jumps while a player could see it (Blink).
 *
 * <ul>
 * <li><b>bk01</b> A shift change at the pithead with a player standing by the headframe: ten miners come over from the
 *     town to the mine, crowd round the cage and the stair head, go down to the deep level for their shift and come up
 *     again at dusk. Not one jump in the player's sight, and every miner below at work within a few minutes and up at
 *     the pithead again after the shift.</li>
 * <li><b>bk02</b> The cage at a lower stop, watched from that stop's landing: the miners step in through the stop's
 *     window and out of it, never popped through the bars.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BlinkGameTests {

    private static final String EMPTY = "empty";

    /** A stand-in player at this spot, counted as a player who could see (Blink.watchForTests). */
    static ServerPlayer watcher(GameTestHelper helper, ServerLevel level, double x, double y, double z) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(p.connection.getConnection());
        p.teleportTo(level, x, y, z, 0, 0);
        Blink.watchForTests(p);
        return p;
    }

    static String pathLine(com.jrpetty.mcassistant.entity.AssistantEntity a) {
        net.minecraft.world.level.pathfinder.Path p = a.getNavigation().getPath();
        return p == null ? "none" : (p.isDone() ? "done " : "") + p.getNextNodeIndex() + "/" + p.getNodeCount() + " to " + p.getTarget().toShortString()
            + (p.canReach() ? "" : " (cannot reach)");
    }

    static void letGo(ServerLevel level, ServerPlayer p) {
        Blink.unwatchForTests(p);
        Blink.setOn(false);
        level.getServer().getPlayerList().remove(p);
    }

    // ============================================================ bk01: a shift change at the pithead, a player watching

    /** The cage's rig (SupplyChainGameTests.liftRig) with rock at the coal and copper levels for ten miners to work in. */
    static MineWorksGameTests.Rig workedRig(GameTestHelper helper, int x, int miners) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, x, miners);
        ServerLevel level = r.level();
        MineLift.forgetForTests(r.v());
        int coal = com.jrpetty.mcassistant.entity.MineWorks.levelY(level, r.v(), com.jrpetty.mcassistant.entity.MineWorks.COAL);
        int copper = com.jrpetty.mcassistant.entity.MineWorks.levelY(level, r.v(), com.jrpetty.mcassistant.entity.MineWorks.COPPER);
        Kit.hold(level, r.sx(), r.sz(), 48);
        MineWorksGameTests.tower(r, copper - 3);
        MineWorksGameTests.bed(r, coal, 32, net.minecraft.world.level.block.Blocks.COAL_ORE, 80, 4301L);
        MineWorksGameTests.bed(r, copper, 32, net.minecraft.world.level.block.Blocks.COPPER_ORE, 80, 4302L);
        com.jrpetty.mcassistant.entity.MineWorks.sinkForTests(level, r.v(), 4 * ((MineWorksGameTests.TOP - copper) / 3) + 1, null);
        com.jrpetty.mcassistant.entity.MineWorks.roomForTests(level, r.v(), com.jrpetty.mcassistant.entity.MineWorks.COAL);
        com.jrpetty.mcassistant.entity.MineWorks.roomForTests(level, r.v(), com.jrpetty.mcassistant.entity.MineWorks.COPPER);
        com.jrpetty.mcassistant.entity.MineWorks.tick(level, com.jrpetty.mcassistant.entity.Villages.get(r.v()));
        return r;
    }

    @GameTest(template = EMPTY, timeoutTicks = 30000, batch = "bk01_pithead_shift_change")
    public static void bk01_pithead_shift_change(GameTestHelper helper) {
        final int n = 10;
        com.jrpetty.mcassistant.entity.MineRoof.resetForTests();
        MineWorksGameTests.Rig r = workedRig(helper, 6740000, n);
        ServerLevel level = r.level();
        UUID v = r.v();
        List<MineLift.Stop> stops = MineLift.stops(v);
        helper.assertTrue(stops.size() == 3, "the cage, three stops: " + stops);
        MineLift.Stop top = stops.get(0), deep = stops.get(2);
        MineDemand.stockForTests(v, MineWorksGameTests.plenty());
        // Ten miners over at the pithead for the morning shift, a stone's throw from it (as they come from the town).
        for (int i = 0; i < n; i++) {
            VillageFolkEntity m = r.miners().get(i);
            MineWorksGameTests.kit(m, Items.IRON_PICKAXE);
            MineWorksGameTests.zone(r, m, deep.y());
            // On the pithead's ground (the top of the rock the shaft is cut in), round the far side of the stair from the cage.
            int dx = i < 5 ? -5 + i * 2 + (i > 2 ? 1 : 0) : (i % 2 == 0 ? -5 : 5), dz = i < 5 ? -5 : -3 + (i - 5);
            m.teleportTo(r.sx() + dx + 0.5, MineWorksGameTests.TOP, r.sz() + dz + 0.5);
        }
        ServerPlayer you = watcher(helper, level, r.sx() + 5.5, MineWorksGameTests.TOP + 1, r.sz() + 5.5);
        WorksGoal.resetCountsForTests();
        Blink.reset();
        Blink.setOn(true);
        MineWorksGameTests.fast(level);
        level.setDayTime(1000);
        final int[] tick = { 0 }, allDown = { -1 }, allUp = { -1 }, dusk = { -1 };
        final int[] downAt = new int[n], upAt = new int[n];
        java.util.Arrays.fill(downAt, -1);
        java.util.Arrays.fill(upAt, -1);
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            int below = 0, up = 0;
            for (int i = 0; i < n; i++) {
                VillageFolkEntity m = r.miners().get(i);
                // Miners they stay (the little test town would set some of them to farming, with no farmers of its own).
                if (m.stationTask() != com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.MINE) m.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.MINE);
                if (dusk[0] < 0) {
                    // What it digs banked for it, and no trip to the stores: the test's town lies a hundred and sixty blocks
                    // under the pithead's rock, with no way down to it but the mine.
                    if (tick[0] % 100 == 0) m.removeMatching(st -> !st.is(net.minecraft.tags.ItemTags.PICKAXES) && !st.is(Items.TORCH) && !st.is(Items.BREAD)
                        && !st.is(Items.COBBLESTONE), 64 * 36);
                    if (m.peekJob() != null && m.peekJob().type() != com.jrpetty.mcassistant.entity.Job.Type.MINE) m.clearQueue();
                    MineWorksGameTests.keepAtIt(r, m);
                }
                boolean isBelow = m.getY() <= stops.get(1).y() + 2, isUp = m.getY() >= MineWorksGameTests.TOP - 1;
                if (isBelow) below++;
                if (isUp) up++;
                if (isBelow && downAt[i] < 0) downAt[i] = tick[0];
                if (dusk[0] > 0 && isUp && upAt[i] < 0) upAt[i] = tick[0];
            }
            if (dusk[0] < 0 && allDown[0] < 0 && java.util.Arrays.stream(downAt).allMatch(t -> t > 0)) {
                allDown[0] = tick[0];
                Kit.log("[VT] bk01 all " + n + " have been below after " + tick[0] + " ticks; rides " + WorksGoal.RIDES + ", gave up on the cage " + WorksGoal.LIFT_GAVE_UP
                    + "; seen jumps so far " + Blink.seenJumps());
            }
            if (tick[0] % 500 == 0) Kit.log("[VT] bk01 @" + tick[0] + ": below " + below + ", up " + up + ", seen jumps " + Blink.seenJumps() + ", leads "
                + WorksGoal.LEADS + ", hops " + WorksGoal.TELEPORTS + "; cage " + MineLift.report(v));
            if (tick[0] % 1500 == 0) for (VillageFolkEntity m : r.miners()) Kit.log("[VT] bk01   " + m.getAssistantName() + " at " + m.blockPosition().toShortString()
                + " job " + (m.peekJob() == null ? "-" : m.peekJob().type() + ":" + m.peekJob().arg()) + " cage " + MineLift.state(m.getUUID())
                + " works " + WorksGoal.stateForTests(m) + " | " + m.brainNoteForBooks());
            // An hour's work below once all are down (or after five minutes whatever), then the shift's end at dusk.
            if (dusk[0] < 0 && (allDown[0] > 0 && tick[0] - allDown[0] > 1200 || tick[0] > 6000)) {
                dusk[0] = tick[0];
                level.setDayTime(12100);
                Kit.log("[VT] bk01 dusk at " + tick[0] + " (below " + below + ")");
            }
            if (dusk[0] > 0 && allUp[0] < 0 && java.util.Arrays.stream(upAt).allMatch(t -> t > 0)) allUp[0] = tick[0];
            boolean over = allUp[0] > 0 && tick[0] - allUp[0] > 1200 || dusk[0] > 0 && tick[0] - dusk[0] > 7000;
            if (!over) return false;
            Kit.log("[VT] bk01 jumps: \n" + Blink.report());
            Kit.log("[VT] bk01 leads " + WorksGoal.LEADS + " (led on " + WorksGoal.LEAD_STROLLS + ", fresh ways " + WorksGoal.LEAD_REPLANS + ", digs " + WorksGoal.LEAD_DIGS + "), hops " + WorksGoal.TELEPORTS);
            for (String h : WorksGoal.hopsForTests()) Kit.log("[VT] bk01 stuck: " + h);
            for (String l : Blink.seenLines()) Kit.log("[VT] bk01 seen: " + l);
            Kit.Expect ok = new Kit.Expect();
            ok.that(Blink.seenJumps() == 0, "no jump in the player's sight: " + Blink.seenJumps());
            ok.that(allDown[0] > 0 && allDown[0] <= 4000, "all " + n + " miners down at work within 4000 ticks: " + allDown[0] + " " + java.util.Arrays.toString(downAt));
            ok.that(allUp[0] > 0 && allUp[0] - dusk[0] <= 6000, "all up at the pithead within 6000 ticks of dusk: " + (allUp[0] - dusk[0]) + " " + java.util.Arrays.toString(upAt));
            letGo(level, you);
            level.setDayTime(1000);
            helper.assertTrue(ok.clean(), ok.summary());
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    // ============================================================ bk02: the cage at a lower stop, watched from it

    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "bk02_lower_stop")
    public static void bk02_lower_stop(GameTestHelper helper) {
        MineWorksGameTests.Rig r = SupplyChainGameTests.liftRig(helper, 6744000, 3);
        ServerLevel level = r.level();
        UUID v = r.v();
        List<MineLift.Stop> stops = MineLift.stops(v);
        helper.assertTrue(stops.size() == 3, "the cage, three stops: " + stops);
        MineLift.Stop top = stops.get(0), coal = stops.get(1);
        ServerPlayer you = watcher(helper, level, coal.board().getX() + 0.5, coal.y(), coal.board().getZ() + 0.5);
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity m = r.miners().get(i);
            m.setNoAi(true);                                    // the cage's walk in and out only: no errands of its own
            m.teleportTo(top.board().getX() + 0.5, top.board().getY(), top.board().getZ() + 0.5 - i);
        }
        Blink.reset();
        Blink.setOn(true);
        Kit.Expect ok = new Kit.Expect();
        final int[] tick = { 0 }, down = { -1 }, upAt = { -1 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (tick[0] == 10) for (VillageFolkEntity m : r.miners()) ok.that(MineLift.ask(m, v, top.index(), coal.index()), "a ride down asked for");
            if (tick[0] <= 10) return false;
            boolean all = r.miners().stream().allMatch(m -> MineLift.state(m.getUUID()) == MineLift.State.ARRIVED);
            if (down[0] < 0 && all) {
                down[0] = tick[0];
                boolean there = r.miners().stream().allMatch(m -> Math.abs(m.getY() - coal.y()) < 1.1);
                Kit.log("[VT] bk02 down to the coal stop after " + tick[0] + " ticks, all there " + there + "; seen jumps " + Blink.seenJumps());
                ok.that(there, "down at the coal stop");
                for (VillageFolkEntity m : r.miners()) MineLift.done(m.getUUID());
                for (VillageFolkEntity m : r.miners()) ok.that(MineLift.ask(m, v, coal.index(), top.index()), "a ride up asked for");
                return false;
            }
            if (down[0] > 0 && all && upAt[0] < 0) upAt[0] = tick[0];
            if (upAt[0] < 0 && tick[0] < 1200) return false;
            boolean back = r.miners().stream().allMatch(m -> Math.abs(m.getY() - top.y()) < 1.1);
            for (VillageFolkEntity m : r.miners()) MineLift.done(m.getUUID());
            Kit.log("[VT] bk02 up to the pithead after " + upAt[0] + " ticks, all there " + back + "; jumps:\n" + Blink.report());
            for (String l : Blink.seenLines()) Kit.log("[VT] bk02 seen: " + l);
            ok.that(down[0] > 0 && back, "down to the coal stop and back up");
            ok.that(down[0] > 0 && down[0] < 400 && upAt[0] - down[0] < 400, "each run under twenty seconds: " + down[0] + ", " + (upAt[0] - down[0]));
            ok.that(Blink.seenJumps() == 0, "no jump in sight at the lower stop: " + Blink.seenJumps());
        boolean bars = true;
        for (int h = 0; h < 2; h++) {
            int[] o = { Integer.signum(coal.board().getX() - r.sx()), Integer.signum(coal.board().getZ() - r.sz()) };
            for (BlockPos c : new BlockPos[]{ new BlockPos(r.sx() + o[0], coal.y() + h, r.sz() + o[1]), new BlockPos(r.sx() + o[0], coal.y() + h, r.sz()),
                    new BlockPos(r.sx(), coal.y() + h, r.sz() + o[1]) })
                bars &= level.getBlockState(c).is(net.minecraft.world.level.block.Blocks.IRON_BARS);
        }
            ok.that(bars, "the stop's window shut again behind them");
            letGo(level, you);
            helper.assertTrue(ok.clean(), ok.summary());
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    // ============================================================ bk03: a working town, a player at its heart, a day and a night

    /**
     * A town of thirty founded with its buildings put up, a player standing in the middle of it from six in the morning to
     * six the next morning (the level of detail for scale on, as in a game: walks out of sight travelled, the path budget in
     * force). Not one jump in the player's sight; the safety ones (a folk stepped out of a wall) are counted apart.
     */
    @GameTest(template = EMPTY, timeoutTicks = 60000, batch = "bk03_town_day_and_night")
    public static void bk03_town_day_and_night(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.Founding.resetForTests(level.getServer());
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        level.setDayTime(22000L);
        int cx = 6748000, cz = 40_000;
        BuildAuditGameTests.shape(level, BuildAuditGameTests.Ground.MEADOW, cx, cz, com.jrpetty.mcassistant.village.TownPlan.reach() + 16, 2803);
        BlockPos heart = Kit.surface(level, cx, cz);
        Kit.log("[VT] bk03 found 30: " + Kit.command(level, "village found 30 " + cx + " " + cz));
        UUID[] id = { null };
        long[] began = { -1 };
        ServerPlayer[] you = { null };
        helper.onEachTick(() -> {
            if (began[0] == -2) return;
            if (id[0] == null) {
                if (com.jrpetty.mcassistant.entity.Founding.near(level, heart, 8)) return;
                com.jrpetty.mcassistant.entity.Villages.Village v = com.jrpetty.mcassistant.entity.Villages.nearest(level, heart, 96);
                if (v == null) { began[0] = -2; helper.fail("no village at " + heart); return; }
                id[0] = v.id();
                com.jrpetty.mcassistant.entity.Villages.ageForTests(v.id(), com.jrpetty.mcassistant.entity.Villages.Age.IRON);
                Kit.stockForGrowth(level, v);
                for (int i = 0; i < 24; i++) {
                    String p = com.jrpetty.mcassistant.entity.AdminTools.nextProject(v.id());
                    if (p == null) break;
                    com.jrpetty.mcassistant.entity.AdminTools.buildProject(level, v, p, false);
                }
                Kit.stockForGrowth(level, v);
                level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L);      // the next morning, six o'clock
                you[0] = watcher(helper, level, heart.getX() + 0.5, heart.getY() + 1, heart.getZ() + 0.5);
                com.jrpetty.mcassistant.entity.FolkLod.liveForTests(true);
                MineWorksGameTests.fast(level);
                Blink.reset();
                Blink.setOn(true);
                began[0] = level.getGameTime();
                Kit.log("[VT] bk03 " + com.jrpetty.mcassistant.entity.Villages.name(v.id()) + ": " + com.jrpetty.mcassistant.entity.Villages.headcount(v.id())
                    + " folk, a player at the heart from six in the morning");
                return;
            }
            long ran = level.getGameTime() - began[0];
            if (ran == 14000 || ran == 19000) {                                   // the evening, and the middle of the night: who is in bed
                int asleep = 0, all = 0;
                for (com.jrpetty.mcassistant.entity.AssistantEntity a : com.jrpetty.mcassistant.entity.Villages.folkOf(id[0])) {
                    all++;
                    if (a.isSleeping()) { asleep++; continue; }
                    BlockPos bed = a.bedPos();
                    Kit.log("[VT] bk03   awake at night: " + a.getAssistantName() + " at " + a.blockPosition().toShortString() + " bed " + (bed == null ? "-" : bed.toShortString()
                        + " " + (int) Math.sqrt(bed.distSqr(a.blockPosition())) + " off") + " path " + pathLine(a) + " | " + a.brainNoteForBooks());
                }
                Kit.log("[VT] bk03 at night: " + asleep + " of " + all + " asleep");
            }
            if (ran % 4000 == 0) Kit.log("[VT] bk03 @" + ran + " (" + (level.getDayTime() % 24000L) + "): seen jumps " + Blink.seenJumps() + ", safety " + Blink.excusedSeenJumps());
            if (ran < 24200) return;
            began[0] = -2;
            Kit.log("[VT] bk03 a day and a night with a player at the heart:\n" + Blink.report());
            for (String l : Blink.seenLines()) Kit.log("[VT] bk03 seen: " + l);
            long seen = Blink.seenJumps();
            com.jrpetty.mcassistant.entity.FolkLod.liveForTests(false);
            com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
            level.getServer().tickRateManager().setTickRate(20.0F);
            letGo(level, you[0]);
            if (seen == 0) helper.succeed();
            else helper.fail("jumps in the player's sight over a day and a night: " + seen);
        });
    }
}
