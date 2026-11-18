package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.MineStairs;
import com.jrpetty.mcassistant.entity.TownLod;
import com.jrpetty.mcassistant.entity.TownMine;
import com.jrpetty.mcassistant.entity.TownWake;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * [mine7] The town's digs kept out of the town: an audit of a growing town with miners and a smelter (the surface
 * openings the town dug inside its own ground, as the town will grow: its whole plan with a margin, its farmland,
 * its buildings), and the fixes one by one. x band 3,700,000 to 3,800,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class MineSiteGameTests {

    private static final int Z = 90000;
    /** The town's ground as it will grow: its whole plan (three rings of lots), and a margin. */
    static final int GROUND = TownPlan.RING + TownPlan.RINGS * TownPlan.PERIOD + 2 + 6;

    /** Run {@code step} every server tick till it says it is done (as LongGameTests.each). */
    static void each(GameTestHelper helper, java.util.function.BooleanSupplier step) {
        final boolean[] over = { false };
        Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> listener = e -> {
            if (over[0]) return;
            try {
                over[0] = step.getAsBoolean();
            } catch (RuntimeException ex) {
                over[0] = true;
                Kit.log("[VT] ms test stopped: " + ex);
                standDown(helper.getLevel());
                helper.runAfterDelay(1, () -> { throw ex; });
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
    }

    static void standDown(ServerLevel level) {
        level.getServer().tickRateManager().setTickRate(20.0F);
        TownWake.liveForTests(false);
        TownLod.liveForTests(false);
        TownLod.forceForTests(level.getServer(), null);
    }

    /** Found a village of this many here, its ground (and the ring its mine goes in) held; then {@code then}. */
    static void found(GameTestHelper helper, int cx, int folk, int hold, Consumer<Villages.Village> then) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        MineStairs.resetForTests(level);
        Kit.hold(level, cx, Z, hold);
        Kit.prepare(level, cx, Z, hold);
        Kit.generousTerrain(level, cx, Z);
        BlockPos heart = Kit.surface(level, cx, Z);
        Kit.log("[VT] ms found " + folk + ": " + Kit.command(level, "village found " + folk + " " + cx + " " + Z));
        TownLod.forceForTests(level.getServer(), TownLod.Lod.FULL);
        each(helper, () -> {
            if (Founding.near(level, heart, 8)) return false;
            Villages.Village v = Villages.nearest(level, heart, 64);
            helper.assertTrue(v != null, "the village is founded");
            then.accept(v);
            return true;
        });
    }

    /** Is this column the town's ground: within its whole plan and a margin, on its farmland, or by its buildings. */
    static boolean townGround(UUID v, BlockPos heart, BlockPos p, int[][] built) {
        int d = Math.max(Math.abs(p.getX() - heart.getX()), Math.abs(p.getZ() - heart.getZ()));
        return d <= GROUND || Villages.onFarmland(v, heart, p, 0) || TownMine.underTheTown(built, p, 4);
    }

    /** The ground's heights, a square of this half-width round the heart. */
    static int[][] heights(ServerLevel level, BlockPos heart, int half) {
        int[][] h = new int[2 * half + 1][2 * half + 1];
        for (int i = 0; i <= 2 * half; i++)
            for (int j = 0; j <= 2 * half; j++)
                h[i][j] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heart.getX() - half + i, heart.getZ() - half + j);
        return h;
    }

    private static boolean natural(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)
            || s.is(Blocks.CLAY) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.COPPER_ORES)
            || s.is(Blocks.COBBLESTONE) || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.isAir();
    }

    /**
     * The openings dug into the town's ground since {@code before}: columns two and more lower than they were, lower than
     * most of the ground two blocks round them (a hole or a stair, not ground levelled), down to bare earth or rock, off
     * the buildings; counted as openings (the columns of one hole together). Returns {openings, columns}, and lists them.
     */
    static int[] openings(ServerLevel level, UUID v, BlockPos heart, int[][] before, int half, List<String> where) {
        int[][] now = heights(level, heart, half);
        int[][] built = TownMine.builtGround(v, heart, level.getGameTime() + 100000L);
        int n = 2 * half + 1;
        boolean[][] dug = new boolean[n][n];
        int columns = 0;
        for (int i = 2; i < n - 2; i++) {
            for (int j = 2; j < n - 2; j++) {
                int h = now[i][j];
                if (h > before[i][j] - 2) continue;
                int higher = 0;
                for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++)
                    if ((Math.abs(a) == 2 || Math.abs(b) == 2) && now[i + a][j + b] >= h + 2) higher++;
                if (higher < 8) continue;
                BlockPos p = new BlockPos(heart.getX() - half + i, h - 1, heart.getZ() - half + j);
                if (!townGround(v, heart, p, built) || TownMine.underTheTown(built, p, 1)) continue;
                if (!natural(level.getBlockState(p))) continue;
                dug[i][j] = true;
                columns++;
            }
        }
        int holes = 0;
        boolean[][] seen = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!dug[i][j] || seen[i][j]) continue;
                holes++;
                int size = 0, deepest = Integer.MAX_VALUE;
                java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                q.add(new int[]{ i, j });
                seen[i][j] = true;
                while (!q.isEmpty()) {
                    int[] c = q.poll();
                    size++;
                    deepest = Math.min(deepest, now[c[0]][c[1]]);
                    for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                        int x = c[0] + a, y = c[1] + b;
                        if (x < 0 || y < 0 || x >= n || y >= n || seen[x][y] || !dug[x][y]) continue;
                        seen[x][y] = true;
                        q.add(new int[]{ x, y });
                    }
                }
                int x = heart.getX() - half + i, z = heart.getZ() - half + j;
                MineStairs.Near st = MineStairs.nearest(level, new BlockPos(x, now[i][j], z), 6);
                if (where.size() < 40) where.add(x + "," + z + " (" + size + " cols, to Y" + deepest + ", "
                    + Math.max(Math.abs(x - heart.getX()), Math.abs(z - heart.getZ())) + " out" + (st != null ? ", mine stairs" : "") + ")");
            }
        }
        return new int[]{ holes, columns };
    }

    /** The town's recorded mine stairs whose head is in the town's ground. */
    static int stairsInTown(ServerLevel level, UUID v, BlockPos heart, List<String> where) {
        int[][] built = TownMine.builtGround(v, heart, level.getGameTime() + 100000L);
        int n = 0;
        for (long key : MineStairs.keysForTests(level)) {
            BlockPos head = MineStairs.head(level, key);
            if (head == null || !townGround(v, heart, head, built)) continue;
            n++;
            if (where.size() < 20) where.add(head.toShortString());
        }
        return n;
    }

    /** The town's miners on plots in the town's ground. */
    static int minersInTown(ServerLevel level, UUID v, BlockPos heart, List<String> where) {
        int[][] built = TownMine.builtGround(v, heart, level.getGameTime() + 100000L);
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v)) {
            WorkZone z = a.workZone();
            if (a.stationTask() != StationTask.MINE || z == null) continue;
            if (!townGround(v, heart, z.center(), built)) continue;
            n++;
            where.add(z.center().getX() + "," + z.center().getZ());
        }
        return n;
    }

    /**
     * The audit: a town of fourteen with four miners and a smelter, its stores kept full so it builds at speed, two days
     * on; then the openings the town dug in its own ground (its whole plan with a margin, its farmland, its buildings),
     * the mine stairs whose heads are in it and the miners on plots in it. All three nought.
     */
    @GameTest(template = "empty", timeoutTicks = 70000, batch = "ms01_audit")
    public static void ms01_audit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cx = 3700000;
        final long[] at = { 0 };
        final int[][][] before = { null };
        int half = GROUND + 4;
        found(helper, cx, 14, 176, v -> {
            UUID id = v.id();
            BlockPos heart = v.centre();
            TownWake.liveForTests(true);
            TownLod.liveForTests(true);
            int miners = 0, smelters = 0;
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity f)) continue;
                if (miners < 4) { f.setJob(StationTask.MINE); miners++; }
                else if (smelters < 1) { f.setJob(StationTask.SMELT); smelters++; }
            }
            Kit.stockForGrowth(level, v);
            before[0] = heights(level, heart, half);
            Kit.log("[VT] ms01 founded at " + heart.toShortString() + ": " + Villages.headcount(id) + " folk, " + miners + " miners, "
                + smelters + " smelter; ground " + GROUND + " out");
            level.getServer().tickRateManager().setTickRate(200.0F);
            each(helper, () -> {
                long t = helper.getTick();
                if (at[0] == 0) at[0] = t;
                long ran = t - at[0];
                if (ran % 12000 == 0) {
                    Kit.stockForGrowth(level, v);
                    List<String> plots = new ArrayList<>();
                    for (AssistantEntity a : Villages.folkOf(id))
                        if (a.stationTask() == StationTask.MINE && a.workZone() != null)
                            plots.add(a.workZone().center().getX() + "," + a.workZone().center().getZ() + "/Y" + a.workZone().depth());
                    Kit.log("[VT] ms01 day " + String.format("%.2f", ran / 24000.0) + ": reach " + Villages.townReach(id) + ", built "
                        + Villages.builtList(id).size() + ", mine " + TownMine.siteOf(id) + ", miners at " + plots);
                }
                if (ran < 48000) return false;
                List<String> holes = new ArrayList<>(), stairs = new ArrayList<>(), plots = new ArrayList<>();
                int[] o = openings(level, id, heart, before[0], half, holes);
                int s = stairsInTown(level, id, heart, stairs);
                int m = minersInTown(level, id, heart, plots);
                BlockPos site = TownMine.siteOf(id);
                Kit.log("[VT] ms01 AUDIT two days on: openings in the town's ground " + o[0] + " (" + o[1] + " columns) " + holes
                    + "; mine stairs with their heads in it " + s + " " + stairs + "; miners on plots in it " + m + " " + plots
                    + "; the mine " + (site == null ? "none" : site.getX() + "," + site.getZ() + ", "
                    + Math.max(Math.abs(site.getX() - heart.getX()), Math.abs(site.getZ() - heart.getZ())) + " out")
                    + "; reach " + Villages.townReach(id) + ", built " + Villages.builtList(id).size());
                standDown(level);
                helper.assertTrue(o[0] == 0, "no openings dug in the town's ground: " + o[0] + " " + holes);
                helper.assertTrue(s == 0, "no mine stairs head in the town's ground: " + s + " " + stairs);
                helper.assertTrue(m == 0, "no miner on a plot in the town's ground: " + m + " " + plots);
                helper.runAfterDelay(1, helper::succeed);
                return true;
            });
        });
    }
}
