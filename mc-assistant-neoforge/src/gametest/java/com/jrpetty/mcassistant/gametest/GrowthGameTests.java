package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.MineDemand;
import com.jrpetty.mcassistant.entity.MineWorks;
import com.jrpetty.mcassistant.entity.TownBlocks;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.WorksGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * [growth22] A grown town that keeps advancing, and the leftovers: drifts that stop short of a hill's flank, refused spots
 * that are asked rather than tried. Each test's town at its own x in 5,100,000 to 5,200,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class GrowthGameTests {

    /**
     * A drift driven north on the coal level towards a cliff (the bed of rock cut away fifteen blocks north of the shaft,
     * open air beyond, as a mountain's flank is): it stops two blocks short of the open air and turns, or ends there; no
     * cell of the works comes within two blocks of the daylight, and the miner never walks out onto the slope. The miner's
     * plot is the shaft itself (the mine's own ground, where no chest may go): its chest is looked for, not tried, so the
     * gate books no refusals for it.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "gw01_drift_hillside")
    public static void gw01_drift_hillside(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 5100000, 1);
        ServerLevel level = r.level();
        VillageFolkEntity m = r.miners().get(0);
        MineDemand.stockForTests(r.v(), MineWorksGameTests.plenty());
        MineWorksGameTests.kit(m, Items.IRON_PICKAXE);
        MineWorksGameTests.zone(r, m, 88);
        MineWorks.pinForTests(r.v(), Direction.NORTH);
        int y = MineWorksGameTests.level(r, MineWorks.COAL, Blocks.COAL_ORE, 60, m);
        // The flank: everything of the bed north of the cliff line taken away, down to the flat.
        int cliff = r.sz() - 15;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = r.sx() - 40; x <= r.sx() + 40; x++) {
            for (int z = r.sz() - 40; z < cliff; z++) {
                for (int yy = y - 3; yy <= y + 4; yy++) level.setBlock(p.set(x, yy, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        m.teleportTo(r.sx() - 6 + 0.5, y, r.sz() - 6 + 0.5);
        MineWorksGameTests.fast(level);
        int refusedBefore = TownBlocks.refusalCount();
        final int[] tick = { 0 };
        final int[] daylightSeen = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            MineWorksGameTests.keepAtIt(r, m);
            // The miner never out on the slope: no tick spent standing in the open air north of the cliff.
            if (m.getZ() < cliff && m.getY() >= y - 1 && m.getY() <= y + 4) daylightSeen[0]++;
            List<int[]> drifts = MineWorks.driftsForTests(level, r.v());
            int[] d = drifts.isEmpty() ? null : drifts.get(0);
            boolean settled = d != null && (d[7] != '-' || d[4] >= 1 && d[3] >= 16);
            if (!settled && tick[0] < 11000) return false;
            List<BlockPos> cells = MineWorks.cellsForTests(level, r.v(), MineWorks.COAL);
            int near = 0, open = 0, minZ = Integer.MAX_VALUE;
            for (BlockPos c : cells) {
                if (level.getBlockState(c).isAir() && MineWorks.daylight(level, c, 2)) near++;
                minZ = Math.min(minZ, c.getZ());
                for (Direction dir : Direction.values()) {
                    BlockPos s = c.relative(dir);
                    if (dir == Direction.DOWN || !level.getBlockState(s).isAir()) continue;
                    if (s.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.getX(), s.getZ())) open++;
                }
            }
            int refused = TownBlocks.refusalCount() - refusedBefore;
            Kit.log("[VT] gw01 after " + tick[0] + " ticks: drift " + (d == null ? "none" : "length " + d[3] + ", turned " + d[4] + ", bent " + d[5]
                + ", branches " + d[6] + ", ended " + (char) d[7]) + "; " + cells.size() + " cells, nearest the cliff z " + minZ + " (cliff at " + cliff
                + "), within two of the daylight " + near + ", openings to the sky " + open + ", ticks on the slope " + daylightSeen[0]
                + ", sealed to the daylight " + WorksGoal.DAYLIGHT_SEALED + "; refusals booked " + refused
                + " | " + String.join(" | ", MineWorks.report(level, r.v())) + " || " + String.join(" | ", MineWorks.eventsForTests()));
            helper.assertTrue(d != null && d[3] >= 6, "a drift was driven");
            helper.assertTrue(minZ >= cliff + MineWorks.SKIN, "two blocks of rock kept to the cliff: nearest z " + minZ);
            helper.assertTrue(near == 0 && open == 0, "no cell near the daylight (" + near + "), no opening (" + open + ")");
            helper.assertTrue(daylightSeen[0] == 0, "the miner never out on the slope");
            helper.assertTrue(refused <= 2, "the gate booked " + refused + " refusals (the chest asked for, not tried)");
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    /** The gate: a cell refused for a reason, asked again and again, is booked once; a look (allows) books nothing. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "gw02_refused_once")
    public static void gw02_refused_once(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 5110000, 1);
        ServerLevel level = r.level();
        Villages.Village v = Villages.get(r.v());
        BlockPos onMine = new BlockPos(r.sx() + 1, 100, r.sz() + 1);
        int before = TownBlocks.refusalCount();
        int looked = 0;
        for (int i = 0; i < 200; i++) if (!TownBlocks.allows(level, v, onMine, TownBlocks.Why.FIELD)) looked++;
        int afterLooks = TownBlocks.refusalCount() - before;
        int tried = 0;
        for (int i = 0; i < 200; i++) if (!TownBlocks.mayPlace(level, v, onMine, TownBlocks.Why.FIELD)) tried++;
        int afterTries = TownBlocks.refusalCount() - before;
        Kit.log("[VT] gw02 looked " + looked + " refused, booked " + afterLooks + "; tried " + tried + " refused, booked " + afterTries);
        helper.assertTrue(looked == 200 && tried == 200, "the mine's ground takes no field: " + looked + "/" + tried);
        helper.assertTrue(afterLooks == 0, "a look books nothing: " + afterLooks);
        helper.assertTrue(afterTries == 1, "200 tries at one cell booked once: " + afterTries);
        MineWorksGameTests.done(helper);
    }
}
