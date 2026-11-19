package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.BridgeStage;
import com.jrpetty.mcassistant.entity.BridgeStairAudit;
import com.jrpetty.mcassistant.entity.RoadWorks;
import com.jrpetty.mcassistant.entity.TradeWorld;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Visitors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * [bridges18] The bridges and the steps the folk build, audited (entity/BridgeStairAudit): for each crossing and flight,
 * the materials mixed, the gaps in the deck, the railings missing over a drop, the deck's changes of height, the full
 * blocks to climb, the stairs facing the wrong way, the long flights with no landing and the dark places over the water;
 * all of them nought. And walked: the audit's walk dry from end to end, and a folk's own path across with no swimming.
 * The scenes are the stage's (entity/BridgeStage): a road made a path (its timber bridge), a road made stone (its stone
 * bridge), a colony's road over a river, a stone road over a stepped hill, and the ways in at doors off the ground.
 * Towns at x 4,800,000 and on, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BridgeStairGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;
    private static final long DAY = 24000L * 4;

    private static void fresh(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        TradeWorld.resetForTests();
        Visitors.resetForTests();
        level.setDayTime(DAY + 2000);
        level.setWeatherParameters(24000, 0, false, false);
    }

    private static void hold(ServerLevel level, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1 + 64; x += 64) {
            for (int z = z0; z <= z1 + 64; z += 64) {
                Kit.hold(level, Math.min(x, x1), Math.min(z, z1), 40);
                Kit.prepare(level, Math.min(x, x1), Math.min(z, z1), 40);
            }
        }
    }

    /** The line's place nearest x (the road's own line), at the ground's height there. */
    private static BlockPos onLine(ServerLevel level, List<int[]> line, int x) {
        int[] best = line.get(0);
        for (int[] p : line) if (Math.abs(p[0] - x) < Math.abs(best[0] - x)) best = p;
        return new BlockPos(best[0], level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, best[0], best[1]), best[1]);
    }

    /** A folk of the town's own path from one place to the other: found, and dry all the way. */
    private static String folkWalk(ServerLevel level, Villages.Village v, BlockPos from, BlockPos to) {
        List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(v.centre()).inflate(24),
            f -> v.id().equals(f.ownerId()));
        if (folk.isEmpty()) return "no folk";
        VillageFolkEntity f = folk.get(0);
        f.moveTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
        f.setOnGround(true);                      // set down on its feet (a folk raised this tick has not touched the ground yet)
        f.getNavigation().stop();
        Path path = f.getNavigation().createPath(to, 1);
        if (path == null) return "no path";
        int wet = 0;
        for (int i = 0; i < path.getNodeCount(); i++) if (level.getFluidState(path.getNode(i).asBlockPos()).is(FluidTags.WATER)) wet++;
        f.moveTo(v.centre().getX() + 0.5, v.centre().getY(), v.centre().getZ() + 0.5);
        return path.canReach() && wet == 0 ? "ok" : "reach " + path.canReach() + ", " + wet + " in the water, ends " + path.getEndNode();
    }

    /** A road scene's crossing audited: the box round the river, the walk along the road from bank to bank. */
    private static BridgeStairAudit.Counts crossing(GameTestHelper helper, String name, BridgeStage.Scene s, List<int[]> line) {
        ServerLevel level = helper.getLevel();
        BlockPos from = onLine(level, line, s.river() - 14), to = onLine(level, line, s.river() + s.width() + 13);
        int z0 = Math.min(from.getZ(), to.getZ()) - 12, z1 = Math.max(from.getZ(), to.getZ()) + 12;
        BridgeStairAudit.Counts c = BridgeStairAudit.audit(level, from, to, s.river() - 14, s.river() + s.width() + 13, s.y() - 6, s.y() + 8, z0, z1);
        String walked = folkWalk(level, s.a(), onLine(level, line, s.river() - 6), onLine(level, line, s.river() + s.width() + 5));
        // Nothing dropped loose by the building: whatever came up went into the stores.
        List<net.minecraft.world.entity.item.ItemEntity> loose = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            new AABB(s.river() - 14, s.y() - 8, z0, s.river() + s.width() + 13, s.y() + 10, z1));
        Kit.log("[VT] " + name + " " + c + "; folk " + walked + "; loose items " + loose.size()
            + (loose.isEmpty() ? "" : " " + loose.get(0).getItem() + " at " + loose.get(0).blockPosition().toShortString()));
        helper.assertTrue(loose.isEmpty(), name + ": nothing dropped loose: " + loose.size());
        helper.assertTrue(c.walked && "ok".equals(walked), name + ": walked dry across: audit " + c.walked + ", folk " + walked);
        return c;
    }

    // ============================================================ bs01: a path's timber bridge

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bs01_timber")
    public static void bs01_timber(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4800000;
        fresh(helper);
        hold(level, x - 40, x + 260, Z - 60, Z + 80);
        int y = Kit.surface(level, x, Z).getY();
        helper.runAtTickTime(5, () -> {
            BridgeStage.Scene s = BridgeStage.road(level, x, Z, y, 24, 1, Villages.Age.STONE);
            helper.assertTrue(s != null, "two towns");
            helper.assertTrue(RoadWorks.halfForTests(s.a().id(), s.b().id())[0] == 1 && RoadWorks.halfForTests(s.b().id(), s.a().id())[0] == 1,
                "both halves a path");
            BridgeStairAudit.Counts c = crossing(helper, "bs01 timber bridge", s, RoadWorks.lineForTests(s.a().id(), s.b().id()));
            helper.assertTrue(c.faults() == 0, "a timber bridge with nothing amiss: " + c);
            helper.succeed();
        });
    }

    // ============================================================ bs02: a stone road's stone bridge

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "bs02_stone")
    public static void bs02_stone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4804000;
        fresh(helper);
        hold(level, x - 40, x + 260, Z - 60, Z + 80);
        int y = Kit.surface(level, x, Z).getY();
        helper.runAtTickTime(5, () -> {
            BridgeStage.Scene s = BridgeStage.road(level, x, Z, y, 24, 3, Villages.Age.IRON);
            helper.assertTrue(s != null, "two towns");
            helper.assertTrue(RoadWorks.halfForTests(s.a().id(), s.b().id())[0] == 3 && RoadWorks.halfForTests(s.b().id(), s.a().id())[0] == 3,
                "both halves stone: " + java.util.Arrays.toString(RoadWorks.halfForTests(s.a().id(), s.b().id())) + " "
                    + java.util.Arrays.toString(RoadWorks.halfForTests(s.b().id(), s.a().id())));
            BridgeStairAudit.Counts c = crossing(helper, "bs02 stone bridge", s, RoadWorks.lineForTests(s.a().id(), s.b().id()));
            helper.assertTrue(c.faults() == 0, "a stone bridge with nothing amiss: " + c);
            helper.succeed();
        });
    }

    // ============================================================ bs03: a colony road's bridge

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bs03_colony")
    public static void bs03_colony(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4808000;
        fresh(helper);
        hold(level, x - 40, x + 250, Z - 50, Z + 70);
        int y = Kit.surface(level, x, Z).getY();
        helper.runAtTickTime(5, () -> {
            BridgeStage.Scene s = BridgeStage.colony(level, x, Z, y);
            helper.assertTrue(s != null, "a mother and a colony");
            List<int[]> line = BridgeStage.colonyLineForTests(s);
            BridgeStairAudit.Counts c = crossing(helper, "bs03 colony bridge", s, line);
            helper.assertTrue(c.faults() == 0, "the colony road's bridge with nothing amiss: " + c);
            helper.succeed();
        });
    }

    // ============================================================ bs04: a stone road over a stepped hill

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "bs04_hill")
    public static void bs04_hill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4812000;
        fresh(helper);
        hold(level, x - 40, x + 260, Z - 100, Z + 100);
        int y = Kit.surface(level, x, Z).getY();
        helper.runAtTickTime(5, () -> {
            BridgeStage.Scene s = BridgeStage.hill(level, x, Z, y);
            helper.assertTrue(s != null, "two towns");
            List<int[]> line = RoadWorks.lineForTests(s.a().id(), s.b().id());
            BlockPos from = onLine(level, line, x + 76), to = onLine(level, line, x + 144);
            // The road's own width: the walk kept to it, not off over the hill's grass beside it.
            int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int[] p : line) if (p[0] >= x + 76 && p[0] <= x + 144) { z0 = Math.min(z0, p[1] - 1); z1 = Math.max(z1, p[1] + 1); }
            Kit.log("[VT] bs04 the road over the hill from z " + z0 + " to " + z1 + " (the hill " + (Z - 80) + " to " + (Z + 80) + ")");
            BridgeStairAudit.Counts c = BridgeStairAudit.audit(level, from, to, x + 76, x + 144, y - 4, y + 12, z0, z1);
            String walked = folkWalk(level, s.a(), onLine(level, line, x + 96), onLine(level, line, x + 110));
            Kit.log("[VT] bs04 hill road " + c + "; folk " + walked);
            helper.assertTrue(c.walked && "ok".equals(walked), "walked over the hill: " + c.walked + ", folk " + walked);
            helper.assertTrue(c.fullSteps == 0 && c.wrongStairs == 0 && c.landings == 0 && c.mixed == 0, "a stepped road: " + c);
            helper.succeed();
        });
    }

    // ============================================================ bs05: the ways in at doors off the ground

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "bs05_doors")
    public static void bs05_doors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4816000;
        fresh(helper);
        hold(level, x - 30, x + 50, Z - 30, Z + 40);
        int y = Kit.surface(level, x, Z).getY();
        helper.runAtTickTime(5, () -> {
            List<BlockPos> doors = BridgeStage.doors(level, x, Z, y);
            helper.assertTrue(doors.size() == 4, "four houses");
            BridgeStairAudit.Counts all = new BridgeStairAudit.Counts();
            all.walked = true;
            int earth = 0;
            for (BlockPos d : doors) {
                // No earth showing under or beside a flight: everything between the door's level and the ground there stone.
                for (int k = 1; k <= 4; k++) for (int dx = -1; dx <= 1; dx++) for (int yy = y; yy < d.getY(); yy++) {
                    if (level.getBlockState(new BlockPos(d.getX() + dx, yy, d.getZ() + k)).is(net.minecraft.tags.BlockTags.DIRT)) earth++;
                }
                BlockPos out = d.south(), far = new BlockPos(d.getX(), y, d.getZ() + 7);
                BridgeStairAudit.Counts c = BridgeStairAudit.audit(level, out, far, d.getX() - 3, d.getX() + 3, y - 3, y + 6, d.getZ() + 1, d.getZ() + 8);
                Kit.log("[VT] bs05 door at " + d.toShortString() + " (" + (d.getY() - y) + " up): " + c);
                all.add(c);
            }
            Kit.log("[VT] bs05 doors all " + all + "; earth showing by the flights " + earth);
            helper.assertTrue(earth == 0, "no earth showing under or beside a flight: " + earth);
            helper.assertTrue(all.walked, "every door walked to: " + all);
            helper.assertTrue(all.fullSteps == 0 && all.wrongStairs == 0 && all.mixed == 0, "door steps as stairs, one material: " + all);
            helper.succeed();
        });
    }
}
