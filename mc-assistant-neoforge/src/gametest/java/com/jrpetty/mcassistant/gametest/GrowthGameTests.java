package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.BridgeStage;
import com.jrpetty.mcassistant.entity.BridgeStairAudit;
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

    /**
     * Doors three and four blocks over the ground before them (houses on plinths): the way in at each turns along the wall,
     * a landing at the door level with the floor and the flight running down beside the wall to the ground, each step a stair
     * facing up the flight, nothing sticking straight out from the door; walked from the door to the ground. A door two
     * blocks up keeps its short straight flight.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "gw03_door_flights")
    public static void gw03_door_flights(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 5120000, z = 90000;
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.TurnedFlights.resetForTests();
        for (int hx = x - 64; hx <= x + 128; hx += 64) { Kit.hold(level, hx, z, 40); Kit.prepare(level, hx, z, 40); }
        int y = Kit.surface(level, x, z).getY();
        helper.runAtTickTime(5, () -> {
            BridgeStage.flat(level, x - 20, x + 60, z - 20, z + 30, y);
            Villages.Village v = BridgeStage.town(level, x, z, y, 3);
            helper.assertTrue(v != null, "the town");
            int[] lifts = { 3, 4, 2 };
            List<BlockPos> doors = new java.util.ArrayList<>();
            List<Direction> outs = new java.util.ArrayList<>();
            int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint("house");
            for (int k = 0; k < lifts.length; k++) {
                // A town house of its own drawing on a stone plinth, its floor so many blocks over the meadow.
                BlockPos a = new BlockPos(x - 8 + 24 * k, y + lifts[k], z + 10);
                BridgeStage.box(level, a.getX() - half[0], a.getX() + half[0], y, a.getY() - 1, a.getZ() - half[1], a.getZ() + half[1],
                    Blocks.STONE_BRICKS.defaultBlockState());
                com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, "house", a, Direction.NORTH, 13,
                    com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
                com.jrpetty.mcassistant.village.Ledger.built(v.id(), "house", a, Direction.NORTH);
                for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(v.id())) {
                    if (!b.anchor().equals(a)) continue;
                    for (com.jrpetty.mcassistant.entity.DoorWays.Door dr : com.jrpetty.mcassistant.entity.DoorWays.doorsOf(level, b)) {
                        if (Math.abs(dr.pos().getY() - a.getY()) > 1) continue;
                        doors.add(dr.pos());
                        outs.add(dr.out());
                        break;
                    }
                }
                helper.assertTrue(doors.size() == k + 1, "the house's door at " + a.toShortString());
                // The plinth only under the house: the meadow before its door wall, a drop to the ground.
                int front = doors.get(k).getZ();
                BridgeStage.box(level, a.getX() - half[0] - 1, a.getX() + half[0] + 1, y, a.getY() - 1, front + 1, a.getZ() + half[1] + 1,
                    Blocks.AIR.defaultBlockState());
            }
            for (int k = 0; k < doors.size(); k++) Kit.log("[VT] gw03 before, door at " + doors.get(k).toShortString() + " out " + outs.get(k) + ": "
                + com.jrpetty.mcassistant.entity.TurnedFlights.whyForTests(level, new com.jrpetty.mcassistant.entity.DoorWays.Door(doors.get(k), outs.get(k))));
            boolean was = com.jrpetty.mcassistant.entity.TownJobs.instantNow();
            com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
            try {
                for (int i = 0; i < 64; i++) com.jrpetty.mcassistant.entity.DoorWays.tick(level, v);
            } finally {
                com.jrpetty.mcassistant.entity.TownJobs.instantForTests(was);
            }
            Kit.Expect expect = new Kit.Expect();
            for (int k = 0; k < doors.size(); k++) {
                BlockPos d = doors.get(k);
                Direction o = outs.get(k);
                com.jrpetty.mcassistant.entity.DoorWays.Door door = new com.jrpetty.mcassistant.entity.DoorWays.Door(d, o);
                List<BlockPos> flight = com.jrpetty.mcassistant.entity.TurnedFlights.flightForTests(level, door);
                BlockPos landing = d.relative(o);
                boolean flatLanding = level.getBlockState(landing.below()).isFaceSturdy(level, landing.below(), Direction.UP)
                    && !(level.getBlockState(landing.below()).getBlock() instanceof net.minecraft.world.level.block.StairBlock);
                // Nothing straight out past the landing over the meadow (no jetty of steps into the street).
                boolean jetty = false;
                for (int yy = y; yy < d.getY(); yy++) if (!level.getBlockState(d.relative(o, 2).atY(yy)).isAir()) jetty = true;
                BlockPos far = d.relative(o, 8).atY(y);
                Direction side = o.getClockWise();
                int x0 = Math.min(landing.getX(), far.getX()), x1 = Math.max(landing.getX(), far.getX());
                int z0 = Math.min(landing.getZ(), far.getZ()), z1 = Math.max(landing.getZ(), far.getZ());
                if (side.getAxis() == Direction.Axis.X) { x0 -= 8; x1 += 8; } else { z0 -= 8; z1 += 8; }
                BridgeStairAudit.Counts c = BridgeStairAudit.audit(level, landing, far, x0, x1, y - 3, d.getY() + 3, z0, z1);
                Kit.log("[VT] gw03 door " + lifts[k] + " up at " + d.toShortString() + " (" + com.jrpetty.mcassistant.entity.TurnedFlights.whyForTests(level, door) + "): flight "
                    + (flight == null ? "straight" : flight.size() + " places " + flight) + ", landing flat " + flatLanding + ", jetty " + jetty + "; " + c);
                if (lifts[k] >= 3) {
                    expect.that(flight != null && flight.size() >= lifts[k] + 1, "door " + lifts[k] + " up turned along the wall: " + flight);
                    expect.that(flatLanding && !jetty, "door " + lifts[k] + " up: a flat landing (" + flatLanding + "), nothing straight out (" + jetty + ")");
                } else {
                    expect.that(flight == null, "door " + lifts[k] + " up keeps its straight flight");
                }
                expect.that(c.walked && c.drops == 0 && c.fullSteps == 0 && c.wrongStairs == 0, "door " + lifts[k] + " up walked by stairs: " + c);
            }
            if (expect.clean()) helper.succeed(); else helper.fail(expect.summary());
        });
    }

    /**
     * [growth22] Hill houses: three builders held to their jobs (nothing else in their day: a test hook) each put up a house
     * by their own goal at their own pace, each cell's makings handed them: one on the flat, one on a hillside with its door
     * uphill, one with its door down. Where each builder's ticks went is logged (walking to a cell out of reach, standing in
     * its own cell, at its pace, set down beside a cell, a cell given up); the hill houses go up about as fast as the flat one.
     */
    @GameTest(template = "empty", timeoutTicks = 40000, batch = "gw04_hill_house")
    public static void gw04_hill_house(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        int cx = 5130000, cz = 90000;
        Kit.hold(level, cx, cz, 96);
        Kit.prepare(level, cx, cz, 96);
        BlockPos heart = Kit.surface(level, cx, cz);
        int hx = cx, hz = cz + 120;
        Kit.hold(level, hx, hz, 48);
        Kit.prepare(level, hx, hz, 48);
        int base = Kit.surface(level, hx, hz).getY();
        for (int dx = -40; dx <= 40; dx++) {
            for (int dz = -24; dz <= 24; dz++) {
                int h = Math.max(0, (dz + 24) / 2 + (dx % 7 == 0 ? 1 : 0));
                for (int y = base; y < base + h; y++) {
                    net.minecraft.world.level.block.state.BlockState st = y == base + h - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : y >= base + h - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    level.setBlock(new BlockPos(hx + dx, y, hz + dz), st, 2 | 16);
                }
            }
        }
        List<VillageFolkEntity> builders = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) builders.add(com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, heart, 0.0F));
        helper.assertTrue(builders.stream().allMatch(java.util.Objects::nonNull), "the builders");
        Villages.Village v = Villages.get(builders.get(0).ownerId());
        Kit.stockForGrowth(level, v);
        BlockPos[] at = {
            BuildAuditGameTests.siteOn(level, "house", cx + 20, cz + 70, Direction.NORTH),
            BuildAuditGameTests.siteOn(level, "house", hx + 18, hz, Direction.NORTH),
            BuildAuditGameTests.siteOn(level, "house", hx - 18, hz, Direction.SOUTH) };
        Direction[] facing = { Direction.NORTH, Direction.NORTH, Direction.SOUTH };
        String[] name = { "flat", "hill, door uphill", "hill, door downhill" };
        long[] took = { -1, -1, -1 };
        int[] jobs = new int[3];
        com.jrpetty.mcassistant.entity.goal.BuildGoal.statsForTests(true);
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity b = builders.get(i);
            b.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.STORE);
            b.setAutonomous(false);
            VillageFolkEntity.holdForTests(b.getUUID(), true);
            BuildWorldGameTests.stock(b, null);
            b.teleportTo(at[i].getX() + 0.5, Kit.surface(level, at[i].getX(), at[i].getZ() + 9).getY(), at[i].getZ() + 9.5);
            b.clearQueue();
        }
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stockAtPaceForTests(BuildWorldGameTests::stock);
        level.getServer().tickRateManager().setTickRate(10000.0F);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (level.getDayTime() % 24000L > 11000L) level.setDayTime(1000);
            boolean all = true;
            for (int i = 0; i < 3; i++) {
                if (took[i] >= 0) continue;
                all = false;
                VillageFolkEntity b = builders.get(i);
                // Held to it: a build job in hand whenever its last one ended (it picks up where it left off).
                if (b.peekJob() == null && tick[0] % 20 == 0) {
                    b.enqueue(com.jrpetty.mcassistant.entity.Job.buildAt("house", at[i], facing[i], 0));
                    jobs[i]++;
                }
                if (tick[0] % 100 != 0) continue;
                int miss = FixesGameTests.missingOf(level, at[i], facing[i]);
                if (miss <= 3) took[i] = tick[0];
            }
            if (!all && tick[0] < 36000) return false;
            List<String> out = new java.util.ArrayList<>();
            int[] missing = new int[3];
            for (int i = 0; i < 3; i++) {
                missing[i] = FixesGameTests.missingOf(level, at[i], facing[i]);
                long[] st = com.jrpetty.mcassistant.entity.goal.BuildGoal.statsForTests(builders.get(i).getUUID());
                out.add(name[i] + " at " + at[i].toShortString() + ": " + (took[i] < 0 ? "not done" : took[i] + " ticks") + ", " + missing[i]
                    + " missing, " + jobs[i] + " jobs; ticks: all " + st[0] + ", walking " + st[1] + ", stood in its cell " + st[2] + ", pace " + st[3]
                    + ", parts' turns " + st[4] + ", set beside " + st[5] + ", given up " + st[6]);
            }
            Kit.log("[VT] gw04 " + String.join("; ", out));
            com.jrpetty.mcassistant.entity.goal.BuildGoal.stockAtPaceForTests(null);
            com.jrpetty.mcassistant.entity.goal.BuildGoal.statsForTests(false);
            for (VillageFolkEntity b : builders) VillageFolkEntity.holdForTests(b.getUUID(), false);
            MineSiteGameTests.standDown(level);
            Kit.Expect expect = new Kit.Expect();
            for (int i = 0; i < 3; i++) expect.that(took[i] > 0 && missing[i] <= 3, name[i] + " built: " + took[i] + " ticks, " + missing[i] + " missing");
            expect.that(took[1] <= Math.max(took[0] * 3 / 2, took[0] + 1200) && took[2] <= Math.max(took[0] * 3 / 2, took[0] + 1200),
                "the hill houses about as quick as the flat one: " + took[0] + ", " + took[1] + ", " + took[2]);
            helper.runAfterDelay(1, () -> { if (expect.clean()) helper.succeed(); else helper.fail(expect.summary()); });
            return true;
        });
    }
}
