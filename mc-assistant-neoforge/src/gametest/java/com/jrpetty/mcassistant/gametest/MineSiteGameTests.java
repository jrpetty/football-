package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.MineGround;
import com.jrpetty.mcassistant.entity.MineStairs;
import com.jrpetty.mcassistant.entity.TownLod;
import com.jrpetty.mcassistant.entity.TownDigs;
import com.jrpetty.mcassistant.entity.TownMine;
import com.jrpetty.mcassistant.entity.TownWake;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.village.Ledger;
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

    // ------------------------------------------------------------------ the fixes, one by one

    /** A small town of folk raised at (x, Z) on held ground of this half-width; its miners. */
    static List<VillageFolkEntity> town(ServerLevel level, int x, int hold, int n, StationTask trade) {
        Kit.reset(level);
        MineStairs.resetForTests(level);
        TownDigs.resetForTests();
        Kit.hold(level, x, Z, hold);
        Kit.prepare(level, x, Z, hold);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
            if (f == null) continue;
            f.setJob(trade);
            out.add(f);
        }
        if (!out.isEmpty()) TownMine.forgetForTests(out.get(0).ownerId());
        return out;
    }

    /** Stairs cut into the ground here, going east (steps down to the bottom of the dirt), and recorded. */
    static List<BlockPos> cutStairs(ServerLevel level, BlockPos head, int steps, long key) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            BlockPos s = head.offset(i, -i, 0);
            for (int h = 0; h <= 2; h++) level.setBlock(s.above(h), Blocks.AIR.defaultBlockState(), 2);
            out.add(s);
        }
        MineStairs.record(level, key, out);
        return out;
    }

    /**
     * The mine opened beyond the town's whole plan (as it will grow) and its faces never in the town's ground, small
     * town or grown; a miner with no face to be had (the ground out there not loaded) gets no plot of its own in the town.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "ms02_site_beyond_plan")
    public static void ms02_site_beyond_plan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3704000;
        List<VillageFolkEntity> miners = town(level, x, 184, 3, StationTask.MINE);
        helper.assertTrue(miners.size() == 3, "three miners");
        UUID v = miners.get(0).ownerId();
        BlockPos heart = miners.get(0).villageCentre();
        helper.assertTrue(v != null && heart != null, "one town");
        BlockPos site = TownMine.site(level, v, heart, p -> false);
        helper.assertTrue(site != null, "the mine is opened");
        int d = Math.max(Math.abs(site.getX() - heart.getX()), Math.abs(site.getZ() - heart.getZ()));
        Kit.log("[VT] ms02 small town (reach " + Villages.townReach(v) + "): the mine at " + d + " out; the plan reaches " + TownDigs.PLAN_REACH);
        helper.assertTrue(d - TownMine.PITCH / 2 > TownDigs.PLAN_REACH + TownDigs.MARGIN, "the first face beyond the whole plan: " + d);
        // Every face handed out, one miner after another, out of the town's ground.
        int faces = 0;
        for (int i = 0; i < 12; i++) {
            VillageFolkEntity f = miners.get(i % 3);
            BlockPos face = TownMine.faceFor(level, f, heart, p -> false);
            if (face == null) break;
            helper.assertTrue(!TownDigs.inTownGround(level, v, heart, face, TownMine.PITCH / 2), "a face in the town's ground: " + face.toShortString());
            f.assignPlot(WorkZone.around(face, 8, heart.getY() - 3), "Face " + i);
            TownMine.spent(v, face);
            faces++;
        }
        // The town grows to its whole plan (three rings of lots): the mine and its faces are still clear of it.
        for (int i = 0; i < 46; i++) Villages.noteProject(v, "house", level.getGameTime());
        TownMine.redraw(v);
        BlockPos face = TownMine.faceFor(level, miners.get(0), heart, p -> false);
        BlockPos again = TownMine.site(level, v, heart, p -> false);
        Kit.log("[VT] ms02 grown (reach " + Villages.townReach(v) + "): " + faces + " faces handed out clear of the town; the next "
            + (face == null ? "none" : face.toShortString()) + "; site kept " + (again != null && again.getX() == site.getX() && again.getZ() == site.getZ()));
        helper.assertTrue(faces >= 6, "faces handed out: " + faces);
        helper.assertTrue(Villages.townReach(v) == TownDigs.PLAN_REACH, "the town grown to its whole plan: " + Villages.townReach(v));
        helper.assertTrue(again != null && again.getX() == site.getX() && again.getZ() == site.getZ(), "the mine stays where it is when the town grows");
        helper.assertTrue(face == null || !TownDigs.inTownGround(level, v, heart, face, TownMine.PITCH / 2), "the next face clear too");

        // Another town, its mine ring not loaded: no face, and its miner stakes no plot of its own in the town.
        int x2 = x + 2000;
        Kit.hold(level, x2, Z, 60);
        Kit.prepare(level, x2, Z, 60);
        BlockPos heart2 = Kit.surface(level, x2, Z);
        VillageFolkEntity lone = VillageFolkSpawnerBlock.raise(level, heart2, 0.0F);
        helper.assertTrue(lone != null && lone.ownerId() != null && !lone.ownerId().equals(v), "a town of its own");
        TownMine.forgetForTests(lone.ownerId());
        lone.setJob(StationTask.MINE);
        BlockPos none = TownMine.faceFor(level, lone, heart2, p -> true);
        helper.assertTrue(none == null, "no face with the ring not loaded: " + none);
        helper.runAfterDelay(300, () -> {
            WorkZone z = lone.workZone();
            Kit.log("[VT] ms02 no ground loaded: the miner's plot " + (z == null ? "none" : z.center().toShortString()));
            helper.assertTrue(z == null || !TownDigs.inTownGround(level, lone.ownerId(), heart2, z.center(), 0),
                "no plot in the town's ground while it waits: " + (z == null ? "" : z.center().toShortString()));
            helper.succeed();
        });
    }

    /** A hand of the town sent for stone, earth or sand never cuts it in the town's ground; timber as ever; outside, yes. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "ms03_no_gather_in_town")
    public static void ms03_no_gather_in_town(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3708000;
        List<VillageFolkEntity> folk = town(level, x, 140, 1, StationTask.SMELT);
        helper.assertTrue(!folk.isEmpty(), "a smelter");
        VillageFolkEntity f = folk.get(0);
        UUID v = f.ownerId();
        BlockPos heart = f.villageCentre();
        BlockPos square = heart.offset(4, -2, 4), street = heart.offset(60, -2, 0), farOut = heart.offset(120, -2, 0);
        boolean sq = TownDigs.mayCut(f, GatherGoal.Kind.STONE, square), st = TownDigs.mayCut(f, GatherGoal.Kind.DIRT, street);
        boolean sand = TownDigs.mayCut(f, GatherGoal.Kind.SAND, street), logs = TownDigs.mayCut(f, GatherGoal.Kind.LOGS, square);
        boolean out = TownDigs.mayCut(f, GatherGoal.Kind.STONE, farOut);
        // Its own plot on the square, earth under its feet: none of it to be had.
        f.assignPlot(WorkZone.around(heart, 6, heart.getY() - 8), "Bench");
        f.teleportTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
        boolean near = f.resourceNearby(GatherGoal.Kind.DIRT, 16);
        BlockPos hit = new GatherGoal(f).nearestForTests(GatherGoal.Kind.DIRT);
        Kit.log("[VT] ms03 may cut: stone under the square " + sq + ", earth in a street " + st + ", sand " + sand + ", timber " + logs
            + ", stone 120 out " + out + "; earth near its bench " + near + ", the gather's pick " + hit);
        helper.assertTrue(!sq && !st && !sand, "nothing cut in the town's ground");
        helper.assertTrue(logs && out, "timber as ever, and stone out beyond the town");
        helper.assertTrue(!near && hit == null, "no earth to be had from its bench on the square: " + near + " " + hit);
        helper.succeed();
    }

    /**
     * An older world: the town's mine opened in what is now its ground (sixty out), a miner on a stray plot of its own in
     * the town off the mine's faces, stray stairs there (unmarked, as an older world's are), stairs on the mine's first
     * face, a staircase another town cut and a hole nobody recorded. The mine stays where it is, its ground set aside as it
     * stands; the stray miner goes to a face of the mine; the town fills and turfs its own stray stairs, not the mine's
     * own, another town's or the unrecorded hole; marked stairs keep their mark over a save and a load.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "ms04_old_world")
    public static void ms04_old_world(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3712000;
        List<VillageFolkEntity> miners = town(level, x, 184, 2, StationTask.MINE);
        helper.assertTrue(miners.size() == 2, "two miners");
        VillageFolkEntity a = miners.get(0);
        UUID v = a.ownerId();
        BlockPos heart = a.villageCentre();
        Villages.Village vil = Villages.get(v);
        Ledger.note(v, "mine.site", (heart.getX() + 60) + "," + heart.getZ());
        BlockPos oldPlot = heart.offset(-40, 0, 30);
        a.assignPlot(WorkZone.around(oldPlot, 8, heart.getY() - 3), "Old pit");
        miners.get(1).assignPlot(WorkZone.around(heart.offset(60, 0, 0), 8, heart.getY() - 3), "The first face");
        BlockPos h1 = oldPlot, h2 = heart.offset(-35, 0, -20), h3 = heart.offset(20, 0, -40), h4 = heart.offset(62, 0, 2);
        List<BlockPos> ours = cutStairs(level, h1, 4, MineStairs.plotKey(h1));
        // A gallery off the same plot, open to the sky: a trench two deep, five long (the pits a heart plot's miner left).
        for (int k = 0; k < 5; k++) for (int dy = 1; dy <= 2; dy++) level.setBlock(h1.offset(-2 - k, -dy, 4), Blocks.AIR.defaultBlockState(), 2);
        cutStairs(level, h2, 4, MineStairs.plotKey(h2));
        MineStairs.own(level, MineStairs.plotKey(h2), UUID.randomUUID());          // another town's
        cutStairs(level, h4, 4, MineStairs.plotKey(h4));                            // the mine's own, on its first face
        for (int h = -3; h <= 0; h++) level.setBlock(h3.above(h), Blocks.AIR.defaultBlockState(), 2);   // nobody's hole
        // A save and a load: the mark is kept.
        UUID other = MineStairs.owner(level, MineStairs.plotKey(h2));
        MineStairs.reloadForTests(level);
        helper.assertTrue(other != null && other.equals(MineStairs.owner(level, MineStairs.plotKey(h2))), "the mark kept over a save and a load");
        helper.assertTrue(MineStairs.steps(level, MineStairs.plotKey(h1)).size() == 4, "the stairs kept over a save and a load");

        // The first look after the load: the mine's ground set aside as it stands; the mine not moved.
        TownDigs.tick(level, vil);
        BlockPos site = TownMine.siteOf(v);
        boolean firstFace = MineGround.reserved(v, heart.getX() + 60, heart.getZ());
        boolean strayFree = !MineGround.reserved(v, h1.getX(), h1.getZ());
        helper.assertTrue(site != null && site.getX() == heart.getX() + 60 && MineGround.has(v) && firstFace,
            "the mine stays, its ground set aside as it stands: " + site + ", " + MineGround.faceCount(v) + " faces");
        helper.assertTrue(strayFree, "the stray plot is not the mine's ground");
        // The stray miner moves to a face of the mine; the first face's miner stays.
        boolean moved = a.seekTheSeamForTests();
        BlockPos firstAt = miners.get(1).workZone().center();
        miners.get(1).seekTheSeamForTests();
        WorkZone z = a.workZone();
        Kit.log("[VT] ms04 an older world: the mine kept at " + site.getX() + "," + site.getZ() + " (" + MineGround.faceCount(v)
            + " faces set aside); the stray miner moved " + moved + " to " + (z == null ? "no plot" : z.center().toShortString())
            + "; the first face's miner at " + miners.get(1).workZone().center().toShortString());
        helper.assertTrue(z == null || MineGround.reserved(v, z.center().getX(), z.center().getZ()), "the stray miner on the mine's ground, or waiting");
        helper.assertTrue(miners.get(1).workZone().center().equals(firstAt), "the miner on the mine's first face is left there");
        helper.assertTrue(!TownDigs.mayRunFrom(miners.get(1), oldPlot), "no mine run from a stray plot in the town's ground");

        // The town fills in its own stray stairs; not the mine's own, another town's, nor a hole nobody recorded.
        int laid = 0;
        for (int i = 0; i < 4; i++) laid += TownDigs.seal(level, vil, TownDigs.FILL_A_LOOK_FOR_TESTS);
        int pits = 0;
        for (int i = 0; i < 4; i++) pits += TownDigs.hollows(level, vil, 200);
        boolean trench = true;
        for (int k = 0; k < 5; k++) trench &= level.getBlockState(h1.offset(-2 - k, -1, 4)).is(Blocks.GRASS_BLOCK)
            && !level.getBlockState(h1.offset(-2 - k, -2, 4)).isAir();
        BlockPos top = ours.get(1).atY(h1.getY() - 1);
        boolean filled = true;
        for (BlockPos st : ours) for (int y = st.getY(); y < h1.getY(); y++) filled &= !level.getBlockState(st.atY(y)).isAir();
        boolean turf = level.getBlockState(top).is(Blocks.GRASS_BLOCK);
        boolean otherOpen = level.getBlockState(h2.offset(1, -1, 0)).isAir() && !MineStairs.steps(level, MineStairs.plotKey(h2)).isEmpty();
        boolean minesOpen = level.getBlockState(h4.offset(1, -1, 0)).isAir() && !MineStairs.steps(level, MineStairs.plotKey(h4)).isEmpty();
        boolean holeOpen = level.getBlockState(h3.below()).isAir();
        boolean forgotten = MineStairs.steps(level, MineStairs.plotKey(h1)).isEmpty();
        Kit.log("[VT] ms04 sealed: " + laid + " blocks laid, filled " + filled + ", turfed " + turf + ", record let go " + forgotten
            + "; the mine's own stairs left " + minesOpen + ", another town's left " + otherOpen + ", the unrecorded hole left " + holeOpen
            + "; sealed " + TownDigs.sealed(v) + "; the trench off its plot filled " + trench + " (" + pits + " blocks)");
        helper.assertTrue(filled && turf && forgotten && laid > 0, "the town's own stray stairs filled and turfed");
        helper.assertTrue(trench, "the pit its gallery left filled and turfed");
        helper.assertTrue(minesOpen && otherOpen && holeOpen, "nothing filled that was the mine's or not the town's own dig");
        helper.succeed();
    }

    /** The mine's ground is kept with the world: set aside when the mine is opened, the same after a save and a load. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "ms06_ground_kept")
    public static void ms06_ground_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3720000;
        List<VillageFolkEntity> miners = town(level, x, 184, 1, StationTask.MINE);
        UUID v = miners.get(0).ownerId();
        BlockPos heart = miners.get(0).villageCentre();
        BlockPos site = TownMine.site(level, v, heart, p -> false);
        helper.assertTrue(site != null && MineGround.has(v), "the mine opened and its ground set aside");
        int faces = MineGround.faceCount(v);
        boolean way = MineGround.reserved(v, heart.getX() + (site.getX() - heart.getX()) * 100 / 107,
            heart.getZ() + (site.getZ() - heart.getZ()) * 100 / 107);
        boolean townFree = !MineGround.reserved(v, heart.getX() + 40, heart.getZ()) && !MineGround.meets(v, heart.getX() - TownDigs.PLAN_REACH,
            heart.getZ() - TownDigs.PLAN_REACH, heart.getX() + TownDigs.PLAN_REACH, heart.getZ() + TownDigs.PLAN_REACH);
        Ledger.reloadForTests();
        MineGround.forgetCache();
        int after = MineGround.faceCount(v);
        boolean still = MineGround.reserved(v, site.getX(), site.getZ()) && MineGround.reserved(v, site.getX() + 9, site.getZ() + 9);
        String status = com.jrpetty.mcassistant.VillageCommands.statusText(level, Villages.get(v));
        Kit.log("[VT] ms06 the mine's ground: " + faces + " faces, the way to it " + way + ", clear of the plan " + townFree + "; after a save and a load "
            + after + " faces, the first face still set aside " + still + "; status says " + status.contains("Mine: the mine's ground set aside"));
        helper.assertTrue(faces >= 9 && townFree, "a mine's worth of faces set aside, none of the town's plan: " + faces);
        helper.assertTrue(after == faces && still, "the same ground after a save and a load: " + after);
        helper.assertTrue(status.contains("Mine: the mine's ground set aside"), "the status says so");
        helper.succeed();
    }

    /**
     * The planners keep off the mine's ground (an older world's mine, set aside in what is now the town's plan): no lot is
     * offered on it, no building of any kind goes up on it, the gate refuses fields, walls, gardens, lamps and new
     * buildings on it while the mine, the way to it and the ground work may; no land title is written on it.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "ms07_planners_keep_off")
    public static void ms07_planners_keep_off(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3724000;
        com.jrpetty.mcassistant.entity.MarketMaker.liveForTests(true);
        List<VillageFolkEntity> folk = town(level, x, 120, 3, StationTask.FARM);
        for (VillageFolkEntity f : folk) f.keepTradeForTests();
        UUID v = folk.get(0).ownerId();
        BlockPos heart = folk.get(0).villageCentre();
        Villages.Village vil = Villages.get(v);
        Ledger.note(v, "mine.site", (heart.getX() + 50) + "," + heart.getZ());
        TownDigs.tick(level, vil);
        helper.assertTrue(MineGround.has(v), "the older world's mine set aside");
        // The lots of the plan: every one over the mine's ground kept off.
        int over = 0, keptOff = 0;
        for (TownPlan.Lot lot : TownPlan.lots()) {
            if (lot.kind() != TownPlan.Kind.LOT) continue;
            int h = TownPlan.LOT / 2;
            if (!MineGround.meets(v, heart.getX() + lot.x() - h, heart.getZ() + lot.z() - h, heart.getX() + lot.x() + h, heart.getZ() + lot.z() + h)) continue;
            over++;
            if (Villages.lotKeptOff(v, heart, lot)) keptOff++;
        }
        // Buildings asked for, one after another: none on it.
        int sites = 0, onIt = 0;
        for (String project : List.of("house", "house", "house", "house", "house", "house", "smithy", "workshop")) {
            Villages.Site site = Villages.siteFor(level, v, project);
            if (site == null) continue;
            sites++;
            int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(project);
            int r = Math.max(half[0], half[1]);
            BlockPos an = site.anchor();
            if (MineGround.meets(v, an.getX() - r, an.getZ() - r, an.getX() + r, an.getZ() + r)) onIt++;
            Villages.privateSiteDone(v, project, false);                  // off the list, its lot kept spoken for: the next one
        }
        // The gate, on the mine's ground.
        BlockPos g = Kit.surface(level, heart.getX() + 50, heart.getZ() + 3);
        com.jrpetty.mcassistant.entity.TownBlocks.Why[] no = { com.jrpetty.mcassistant.entity.TownBlocks.Why.BUILD, com.jrpetty.mcassistant.entity.TownBlocks.Why.FIELD,
            com.jrpetty.mcassistant.entity.TownBlocks.Why.WALL, com.jrpetty.mcassistant.entity.TownBlocks.Why.GARDEN, com.jrpetty.mcassistant.entity.TownBlocks.Why.LAMP,
            com.jrpetty.mcassistant.entity.TownBlocks.Why.DECOR, com.jrpetty.mcassistant.entity.TownBlocks.Why.CIVIC };
        int refused = 0;
        for (var why : no) if (!com.jrpetty.mcassistant.entity.TownBlocks.allows(level, vil, g, why)) refused++;
        boolean mineOk = com.jrpetty.mcassistant.entity.TownBlocks.allows(level, vil, g, com.jrpetty.mcassistant.entity.TownBlocks.Why.MINE)
            && com.jrpetty.mcassistant.entity.TownBlocks.allows(level, vil, g, com.jrpetty.mcassistant.entity.TownBlocks.Why.ROAD);
        // Land titles: a homestead whose ground is the mine's gets none.
        List<com.jrpetty.mcassistant.entity.Business> homes = com.jrpetty.mcassistant.entity.Privatisation.ensureForTests(level, v);
        long day = level.getDayTime() / 24000L;
        com.jrpetty.mcassistant.entity.Business titled = null;
        for (com.jrpetty.mcassistant.entity.Business b : com.jrpetty.mcassistant.entity.Businesses.all(v)) {
            if (b.at == null || MineGround.reserved(v, b.at.getX(), b.at.getZ())) b.at = heart.offset(-60, 0, 0);
        }
        com.jrpetty.mcassistant.entity.LandTitles.morningForTests(level, vil, day);
        for (com.jrpetty.mcassistant.entity.Business b : com.jrpetty.mcassistant.entity.Businesses.all(v))
            if (com.jrpetty.mcassistant.entity.LandTitles.titleForTests(v, "firm:" + b.id) != null) { titled = b; break; }
        boolean titleOff = false;
        if (titled != null) {
            titled.at = g;
            com.jrpetty.mcassistant.entity.LandTitles.morningForTests(level, vil, day + 1);
            titleOff = com.jrpetty.mcassistant.entity.LandTitles.titleForTests(v, "firm:" + titled.id) == null;
        }
        Kit.log("[VT] ms07 lots over the mine's ground " + over + ", kept off " + keptOff + "; sites " + sites + ", on it " + onIt
            + "; the gate refused " + refused + " of " + no.length + ", the mine and the way allowed " + mineOk + "; homesteads " + homes.size()
            + ", a title " + (titled != null) + ", gone once its ground is the mine's " + titleOff);
        helper.assertTrue(over > 0 && keptOff == over, "every lot over the mine's ground kept off: " + keptOff + " of " + over);
        helper.assertTrue(sites > 0 && onIt == 0, "no building sited on the mine's ground: " + onIt + " of " + sites);
        helper.assertTrue(refused == no.length && mineOk, "the gate keeps the mine's ground for the mine: " + refused + "/" + no.length + ", " + mineOk);
        helper.assertTrue(titled != null && titleOff, "no land title on the mine's ground");
        com.jrpetty.mcassistant.entity.MarketMaker.liveForTests(false);
        helper.succeed();
    }

    /**
     * A town grown all round its mine (an older world's, set aside in what is now its plan; the town at its whole plan)
     * still mines there: its miners are handed the mine's faces, run from them and cut there; the mine is not moved.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "ms08_grown_round")
    public static void ms08_grown_round(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3728000;
        List<VillageFolkEntity> miners = town(level, x, 184, 2, StationTask.MINE);
        UUID v = miners.get(0).ownerId();
        BlockPos heart = miners.get(0).villageCentre();
        Ledger.note(v, "mine.site", (heart.getX() + 50) + "," + heart.getZ());
        for (int i = 0; i < 46; i++) Villages.noteProject(v, "house", level.getGameTime());
        TownMine.redraw(v);
        BlockPos site = TownMine.site(level, v, heart, p -> false);
        int inPlan = 0, handed = 0;
        for (VillageFolkEntity m : miners) {
            BlockPos face = TownMine.faceFor(level, m, heart, p -> false);
            if (face == null) continue;
            handed++;
            m.assignPlot(WorkZone.around(face, 8, heart.getY() - 3), "Face");
            if (TownDigs.inPlan(v, heart, face, 0)) inPlan++;
        }
        VillageFolkEntity m = miners.get(0);
        BlockPos at = m.workZone().center();
        boolean run = TownDigs.mayRunFrom(m, at), cut = TownDigs.mayCut(m, GatherGoal.Kind.STONE, at.below(2));
        boolean kept = !m.seekTheSeamForTests() || m.workZone().center().equals(at);
        Kit.log("[VT] ms08 grown to reach " + Villages.townReach(v) + ": the mine at " + site + ", " + handed + " faces handed out (" + inPlan
            + " inside the plan); may run " + run + ", may cut " + cut + ", kept on its face " + kept);
        helper.assertTrue(site != null && site.getX() == heart.getX() + 50, "the mine is not moved");
        helper.assertTrue(handed == 2 && run && cut && kept, "the town grown round it still mines there");
        helper.succeed();
    }

    /** Out at the mine: a timber portal with a lantern over each staircase's head, and a path from the town to it. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "ms05_portal_path")
    public static void ms05_portal_path(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3716000;
        List<VillageFolkEntity> miners = town(level, x, 184, 1, StationTask.MINE);
        VillageFolkEntity a = miners.get(0);
        UUID v = a.ownerId();
        BlockPos heart = a.villageCentre();
        Villages.Village vil = Villages.get(v);
        BlockPos site = TownMine.site(level, v, heart, p -> false);
        helper.assertTrue(site != null, "the mine is opened");
        BlockPos head = Kit.surface(level, site.getX(), site.getZ());
        long key = MineStairs.plotKey(head);
        cutStairs(level, head, 4, key);
        MineStairs.own(level, key, v);
        int portals = TownDigs.portals(level, vil);
        boolean posts = level.getBlockState(head.north()).is(Blocks.STRIPPED_SPRUCE_LOG) && level.getBlockState(head.south()).is(Blocks.STRIPPED_SPRUCE_LOG);
        boolean lamp = level.getBlockState(head.above(2)).is(Blocks.LANTERN);
        boolean clear = level.getBlockState(head).isAir() && level.getBlockState(head.above()).isAir();
        int again = TownDigs.portals(level, vil);
        int trod = 0;
        for (int i = 0; i < 8; i++) trod += TownDigs.path(level, vil, 64);
        int pathBlocks = 0;
        for (int i = Villages.townReach(v) + 2; i < Math.abs(site.getX() - heart.getX()) + Math.abs(site.getZ() - heart.getZ()); i += 4) {
            double t = i / Math.sqrt(site.atY(0).distSqr(heart.atY(0)));
            if (t > 1) break;
            int px = heart.getX() + (int) Math.round((site.getX() - heart.getX()) * t), pz = heart.getZ() + (int) Math.round((site.getZ() - heart.getZ()) * t);
            BlockPos g = Kit.surface(level, px, pz).below();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (level.getBlockState(g.offset(dx, 0, dz)).is(Blocks.DIRT_PATH)) { pathBlocks++; dx = 2; break; }
        }
        Kit.log("[VT] ms05 portal " + portals + " (posts " + posts + ", lantern " + lamp + ", the way down clear " + clear + "), again " + again
            + "; path trodden " + trod + ", found along the way " + pathBlocks);
        helper.assertTrue(portals == 1 && posts && lamp && clear, "a timber portal with a lantern over the stair head");
        helper.assertTrue(again == 0, "put up once");
        helper.assertTrue(trod > 40 && pathBlocks >= 8, "a path from the town to the mine: " + trod + ", " + pathBlocks);
        helper.succeed();
    }
}
