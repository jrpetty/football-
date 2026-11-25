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

    static void letGo(ServerLevel level, ServerPlayer p) {
        Blink.unwatchForTests(p);
        Blink.setOn(false);
        level.getServer().getPlayerList().remove(p);
    }

    // ============================================================ bk01: a shift change at the pithead, a player watching

    @GameTest(template = EMPTY, timeoutTicks = 30000, batch = "bk01_pithead_shift_change")
    public static void bk01_pithead_shift_change(GameTestHelper helper) {
        final int n = 10;
        com.jrpetty.mcassistant.entity.MineRoof.resetForTests();
        MineWorksGameTests.Rig r = SupplyChainGameTests.liftRig(helper, 6740000, n);
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
            BlockPos at = new BlockPos(r.sx() - 12 + (i % 5) * 2, MineWorksGameTests.TOP, r.sz() - 10 - (i / 5) * 2);
            BlockPos g = Kit.surface(level, at.getX(), at.getZ());
            m.teleportTo(g.getX() + 0.5, Math.max(g.getY(), MineWorksGameTests.TOP), g.getZ() + 0.5);
        }
        ServerPlayer you = watcher(helper, level, r.sx() + 5.5, MineWorksGameTests.TOP + 1, r.sz() + 5.5);
        WorksGoal.resetCountsForTests();
        Blink.reset();
        Blink.setOn(true);
        MineWorksGameTests.fast(level);
        level.setDayTime(1000);
        final int[] tick = { 0 }, allDown = { -1 }, allUp = { -1 }, dusk = { -1 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            int below = 0, up = 0;
            for (VillageFolkEntity m : r.miners()) {
                if (dusk[0] < 0) MineWorksGameTests.keepAtIt(r, m);
                if (m.getY() <= stops.get(1).y() + 2) below++;
                if (m.getY() >= MineWorksGameTests.TOP - 1) up++;
            }
            if (dusk[0] < 0 && below == n && allDown[0] < 0) {
                allDown[0] = tick[0];
                Kit.log("[VT] bk01 all " + n + " below after " + tick[0] + " ticks; rides " + WorksGoal.RIDES + ", gave up on the cage " + WorksGoal.LIFT_GAVE_UP
                    + "; seen jumps so far " + Blink.seenJumps());
            }
            if (tick[0] % 500 == 0) Kit.log("[VT] bk01 @" + tick[0] + ": below " + below + ", up " + up + ", seen jumps " + Blink.seenJumps() + "; cage " + MineLift.report(v));
            // An hour's work below once all are down (or after five minutes whatever), then the shift's end at dusk.
            if (dusk[0] < 0 && (allDown[0] > 0 && tick[0] - allDown[0] > 1200 || tick[0] > 6000)) {
                dusk[0] = tick[0];
                level.setDayTime(12100);
                Kit.log("[VT] bk01 dusk at " + tick[0] + " (below " + below + ")");
            }
            if (dusk[0] > 0 && up == n && allUp[0] < 0) allUp[0] = tick[0];
            boolean over = allUp[0] > 0 || dusk[0] > 0 && tick[0] - dusk[0] > 7000;
            if (!over) return false;
            Kit.log("[VT] bk01 jumps: \n" + Blink.report());
            for (String l : Blink.seenLines()) Kit.log("[VT] bk01 seen: " + l);
            Kit.Expect ok = new Kit.Expect();
            ok.that(Blink.seenJumps() == 0, "no jump in the player's sight: " + Blink.seenJumps());
            ok.that(allDown[0] > 0 && allDown[0] <= 4000, "all " + n + " miners below at work within 4000 ticks: " + allDown[0]);
            ok.that(allUp[0] > 0 && allUp[0] - dusk[0] <= 6000, "all up at the pithead within 6000 ticks of dusk: " + (allUp[0] - dusk[0]));
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
            m.teleportTo(top.board().getX() + 0.5, top.board().getY(), top.board().getZ() + 0.5);
        }
        Blink.reset();
        Blink.setOn(true);
        Kit.Expect ok = new Kit.Expect();
        for (VillageFolkEntity m : r.miners()) ok.that(MineLift.ask(m, v, top.index(), coal.index()), "a ride down asked for");
        int t = 0;
        boolean all = false;
        while (!all && t < 600) {
            MineLift.runForTests(level, v, 1);
            for (VillageFolkEntity m : r.miners()) Blink.tickCheckForTests(m);
            all = r.miners().stream().allMatch(m -> MineLift.state(m.getUUID()) == MineLift.State.ARRIVED);
            t++;
        }
        boolean there = r.miners().stream().allMatch(m -> Math.abs(m.getY() - coal.y()) < 1.1);
        Kit.log("[VT] bk02 down to the coal stop in " + t + " ticks, all there " + there + "; seen jumps " + Blink.seenJumps());
        for (VillageFolkEntity m : r.miners()) MineLift.done(m.getUUID());
        for (VillageFolkEntity m : r.miners()) ok.that(MineLift.ask(m, v, coal.index(), top.index()), "a ride up asked for");
        int u = 0;
        all = false;
        while (!all && u < 600) {
            MineLift.runForTests(level, v, 1);
            for (VillageFolkEntity m : r.miners()) Blink.tickCheckForTests(m);
            all = r.miners().stream().allMatch(m -> MineLift.state(m.getUUID()) == MineLift.State.ARRIVED);
            u++;
        }
        boolean back = r.miners().stream().allMatch(m -> Math.abs(m.getY() - top.y()) < 1.1);
        for (VillageFolkEntity m : r.miners()) MineLift.done(m.getUUID());
        Kit.log("[VT] bk02 up to the pithead in " + u + " ticks, all there " + back + "; jumps:\n" + Blink.report());
        for (String l : Blink.seenLines()) Kit.log("[VT] bk02 seen: " + l);
        ok.that(there && back, "down to the coal stop and back up");
        ok.that(t < 300 && u < 300, "each run under fifteen seconds: " + t + ", " + u);
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
    }
}
