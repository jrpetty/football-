package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Footholds;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.MineStairs;
import com.jrpetty.mcassistant.entity.OreNotes;
import com.jrpetty.mcassistant.entity.OreSight;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [mines] Tidy mining: the steps a folk builds to reach something come down again, the ways in and out of the rock
 * are shared and remembered, a plot keeps one staircase, and ore is swept the way a player sees it.
 *
 * <p>Band: x 1760000-1779999, z 66000. The test world is flat (earth over bedrock), so each test stands a block of
 * rock on it to work in.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TidyMinesGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    /** The cobblestone each folk carries: what it builds its steps of. */
    private static final int COBBLE = 32;

    /** A block of rock on the flat world, `half` out each way and `height` high: the y of its top surface. */
    private static int rock(ServerLevel level, int cx, int cz, int half, int height) {
        int base = Kit.surface(level, cx, cz).getY();
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int y = base; y < base + height; y++) level.setBlock(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        return base + height;
    }

    private static void air(ServerLevel level, BlockPos from, BlockPos to) {
        for (BlockPos p : BlockPos.betweenClosed(from, to)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
    }

    /** A fresh world for one test: everything the mines keep with the world forgotten. */
    private static void fresh(ServerLevel level, int x, int radius) {
        Kit.reset(level);
        Footholds.resetForTests(level);
        OreNotes.resetForTests(level);
        level.setDayTime(2000);
        Kit.hold(level, x, Z, radius);
        Kit.prepare(level, x, Z, radius);
    }

    /**
     * The one folk of a town whose heart is well off to one side, on no trade and left to no work of its own choosing
     * (a second folk is given a trade by its town at once, and a miner's plot is moved to its town's mine), a pick and
     * cobblestone in its pack, stood here. The test gives it its work at once: a hand with nothing queued takes the
     * cobblestone it carries back to the builders' stores.
     */
    private static VillageFolkEntity folk(ServerLevel level, int x, BlockPos at, ItemStack pick) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 40, Z + 30), 0.0F);
        if (f == null) return null;
        f.setAutonomous(false);
        f.clearQueue();
        f.getInventoryItems().clear();
        f.insertItem(pick);
        f.insertItem(new ItemStack(Items.COBBLESTONE, COBBLE));
        f.insertItem(new ItemStack(Items.TORCH, 16));
        f.insertItem(new ItemStack(Items.BREAD, 8));
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        return f;
    }

    /** No break, no meal and no bell in the middle of a test of something else. */
    private static void keepAtWork(ServerLevel level, VillageFolkEntity f) {
        if (f.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
    }

    private static int cobble(VillageFolkEntity f) {
        return f.countMatching(s -> s.is(Items.COBBLESTONE));
    }

    private static int cobbleBlocks(ServerLevel level, BlockPos from, BlockPos to) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(from, to)) if (level.getBlockState(p).is(Blocks.COBBLESTONE)) n++;
        return n;
    }

    // ============================================================ tmn01: up to an ore, and every step back down

    /**
     * An iron ore hanging in a stone roof seven blocks over a stone floor, out of reach from the floor: a folk sent for
     * iron builds itself up to it, mines it, and comes down; every block it laid is gone and back in its pack.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tmn01_pillar_ore")
    public static void tmn01_pillar_ore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1760000;
        fresh(level, x, 40);
        BlockPos floor = Kit.surface(level, x, Z);
        BlockPos ore = floor.above(7);
        roofWithOre(level, ore);
        VillageFolkEntity f = folk(level, x, floor.south(2), new ItemStack(Items.STONE_PICKAXE));
        helper.assertTrue(f != null, "a folk");
        helper.assertTrue(OreSight.exposed(level, ore), "the ore shows from below");
        f.enqueueFront(Job.gather(GatherGoal.Kind.IRON, 1));
        int[] most = { 0 }, atTop = { 0 };
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            int standing = Footholds.countForTests(level, floor, 12);
            if (standing > most[0]) { most[0] = standing; atTop[0] = cobble(f); }   // its pack, all its steps up
            if (t % 100 == 0) Kit.log("tmn01 @" + t + " at " + f.blockPosition().toShortString() + ", steps standing " + standing + " — " + f.debugLine());
            boolean mined = !level.getBlockState(ore).is(Blocks.IRON_ORE);
            if (t > 20 && mined && standing == 0 && f.onGround()) {
                int left = cobbleBlocks(level, floor.offset(-3, 0, -3), ore.offset(3, -1, 3));
                Kit.log("tmn01 done at " + t + ": built up " + most[0] + ", cobble in pack " + cobble(f) + " (" + atTop[0] + " at the top)"
                    + ", raw iron " + f.countMatching(s -> s.is(Items.RAW_IRON)) + ", cobble left standing " + left);
                helper.assertTrue(most[0] >= 1, "it built itself up to the ore: " + most[0] + " steps");
                helper.assertTrue(f.countMatching(s -> s.is(Items.RAW_IRON)) >= 1, "the iron is in its pack");
                helper.assertTrue(left == 0, "no block it laid is left standing: " + left);
                helper.assertTrue(cobble(f) == atTop[0] + most[0], "every block it laid is back in its pack: " + cobble(f) + " = " + atTop[0] + " + " + most[0]);
                helper.succeed();
            }
        });
    }

    /** A stone roof three across over this spot, the ore in its middle, open below. */
    private static void roofWithOre(ServerLevel level, BlockPos ore) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) level.setBlock(ore.offset(dx, 1, dz), Blocks.STONE.defaultBlockState(), 2);
        }
        level.setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), 2);
    }

    // ============================================================ tmn02: called away mid-climb

    /**
     * The same climb, cut short three steps up (its job called off, and the folk called away): its steps stand,
     * booked; the next time it is by them it takes them all down, back into its pack.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tmn02_interrupted")
    public static void tmn02_interrupted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1762000;
        fresh(level, x, 40);
        BlockPos floor = Kit.surface(level, x, Z);
        BlockPos ore = floor.above(8);                                       // the top of its look for iron
        roofWithOre(level, ore);
        VillageFolkEntity f = folk(level, x, floor.south(2), new ItemStack(Items.STONE_PICKAXE));
        helper.assertTrue(f != null, "a folk");
        f.enqueueFront(Job.gather(GatherGoal.Kind.IRON, 1));
        long[] away = { -1 }, back = { -1 };
        int[] left = { 0 }, packed = { 0 };
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            int standing = Footholds.countForTests(level, floor, 12);
            if (t % 100 == 0) Kit.log("tmn02 @" + t + " at " + f.blockPosition().toShortString() + ", steps standing " + standing + " — " + f.debugLine());
            if (away[0] < 0 && standing >= 3) {
                // Called away: the job off, and off somewhere else.
                away[0] = t;
                left[0] = standing;
                f.clearQueue();
                f.moveTo(floor.getX() + 7.5, floor.getY(), floor.getZ() + 2.5, 0.0F, 0.0F);   // out of reach of its steps
                Kit.log("tmn02 called away at " + t + " with " + standing + " steps standing");
                return;
            }
            if (away[0] < 0) return;
            if (back[0] < 0 && t - away[0] >= 100) {
                helper.assertTrue(Footholds.countForTests(level, floor, 12) == left[0], "its steps wait, booked, while it is away");
                back[0] = t;
                packed[0] = cobble(f);
                f.moveTo(floor.getX() + 1.5, floor.getY(), floor.getZ() + 2.5, 0.0F, 0.0F);   // beside the foot of its steps
                Kit.log("tmn02 back by them at " + t);
                return;
            }
            if (back[0] > 0 && f.distanceToSqr(floor.getX() + 1.5, floor.getY(), floor.getZ() + 2.5) > 0.5) {
                // It lingers by its steps a while (the test's stand-in for its work there): held, not walked off.
                f.getNavigation().stop();
                f.moveTo(floor.getX() + 1.5, floor.getY(), floor.getZ() + 2.5, f.getYRot(), 0.0F);
            }
            if (back[0] > 0 && standing == 0) {
                int blocks = cobbleBlocks(level, floor.offset(-3, 0, -3), ore.offset(3, -1, 3));
                Kit.log("tmn02 cleared at " + t + " (" + (t - back[0]) + " after it came back): cobble in pack " + cobble(f) + " (" + packed[0]
                    + " before, " + left[0] + " steps), left standing " + blocks);
                helper.assertTrue(blocks == 0, "the leftovers are taken down: " + blocks + " standing");
                helper.assertTrue(cobble(f) == packed[0] + left[0], "back in its pack: " + cobble(f) + " = " + packed[0] + " + " + left[0]);
                helper.succeed();
            }
        });
    }

    // ============================================================ tmn03: one staircase, run after run

    /**
     * A village miner on a plot of rock runs down to the same depth twice, the second run started two blocks off the
     * first one's head: it walks onto the plot's stairs and down them, so the plot keeps one staircase and the second
     * run cuts no new stairs.
     */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "tmn03_one_stair")
    public static void tmn03_one_stair(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1764000;
        fresh(level, x, 48);
        int top = rock(level, x, Z, 14, 14);
        BlockPos head = new BlockPos(x, top, Z);
        int depth = top - 9;
        VillageFolkEntity f = folk(level, x, head, new ItemStack(Items.DIAMOND_PICKAXE));
        helper.assertTrue(f != null, "a folk");
        f.assignPlot(WorkZone.around(head, 8, depth), "the test's mine");
        f.setAutonomous(false);
        long key = MineStairs.plotKey(head);
        int[] run = { 0 };
        long[] since = { 0 };
        List<List<BlockPos>> stairs = new ArrayList<>();
        Set<Long> cutAbove = new HashSet<>();
        f.enqueueFront(Job.mine(depth, "north"));
        run[0] = 1;
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            if (t % 200 == 0) Kit.log("tmn03 run " + run[0] + " @" + t + " at " + f.blockPosition().toShortString() + " — " + f.debugLine());
            if (run[0] == 0 || t - since[0] < 20) return;
            Job j = f.peekJob();
            if (j != null && j.type() == Job.Type.MINE) return;
            if (f.running(com.jrpetty.mcassistant.entity.goal.MineGoal.class)) return;
            List<BlockPos> now = MineStairs.stairsForTests(level, key);
            Set<Long> cut = openAbove(level, head, depth + 3);
            Kit.log("tmn03 run " + run[0] + " done at " + t + ": " + now.size() + " steps, " + MineStairs.keysForTests(level).size()
                + " staircases kept, " + cut.size() + " blocks cut above the galleries");
            if (run[0] == 1) {
                helper.assertTrue(now.size() >= 6, "the first run cut stairs down: " + now.size());
                stairs.add(now);
                cutAbove.addAll(cut);
                f.clearQueue();
                f.moveTo(head.getX() + 2.5, head.getY(), head.getZ() + 1.5, 0.0F, 0.0F);   // a step or two off the head
                run[0] = 2;
                since[0] = t;
                f.enqueueFront(Job.mine(depth, "north"));
                return;
            }
            List<BlockPos> first = stairs.get(0);
            boolean same = now.size() >= first.size() && now.subList(0, first.size()).equals(first);
            Set<Long> fresh = new HashSet<>(cut);
            fresh.removeAll(cutAbove);
            helper.assertTrue(MineStairs.keysForTests(level).size() == 1, "one staircase kept: " + MineStairs.keysForTests(level).size());
            helper.assertTrue(same, "the second run went down the first run's stairs: " + first.size() + " then " + now.size() + " steps");
            helper.assertTrue(fresh.isEmpty(), "the second run cut no new stairs: " + fresh.size() + " fresh blocks above the galleries");
            helper.succeed();
        });
    }

    /** Every open block in the plot's rock above this y: the stairs, before the galleries at the bottom. */
    private static Set<Long> openAbove(ServerLevel level, BlockPos head, int above) {
        Set<Long> out = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(head.getX() - 12, above, head.getZ() - 12), new BlockPos(head.getX() + 12, head.getY() - 1, head.getZ() + 12))) {
            if (level.getBlockState(p).isAir()) out.add(p.asLong());
        }
        return out;
    }

    // ============================================================ tmn04: the known way out

    /**
     * Stairs the town knows, fourteen steps down into the rock, and at their foot a tunnel twenty blocks long; a folk
     * lost at the far end of the tunnel walks to the stairs and up them, and cuts no way of its own.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "tmn04_known_route")
    public static void tmn04_known_route(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1766000;
        fresh(level, x, 48);
        int top = rock(level, x, Z, 24, 20);
        List<BlockPos> steps = new ArrayList<>();
        for (int k = 0; k <= 14; k++) {
            BlockPos s = new BlockPos(x, top - k, Z - k);
            steps.add(s);
            air(level, s, s.above(2));
        }
        MineStairs.record(level, MineStairs.plotKey(new BlockPos(x, 0, Z)), steps);
        BlockPos foot = steps.get(14);
        air(level, foot, foot.offset(20, 1, 0));
        BlockPos lost = foot.east(20);
        VillageFolkEntity f = folk(level, x, lost, new ItemStack(Items.STONE_PICKAXE));
        helper.assertTrue(f != null, "a folk");
        helper.assertTrue(MineStairs.underground(level, lost), "lost underground");
        helper.runAtTickTime(5, () -> f.enqueueFront(Job.mine(lost.getY(), MineStairs.OUT)));
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            BlockPos at = f.blockPosition();
            if (t % 200 == 0) Kit.log("tmn04 @" + t + " at " + at.toShortString() + " — " + f.debugLine());
            if (t > 20 && MineStairs.open(level, at)) {
                int ways = outWays(level) + 1;                               // the known stairs, and any way cut
                Kit.log("tmn04 out at " + t + " at " + at.toShortString() + "; ways known " + ways);
                helper.assertTrue(ways == 1, "it took the known stairs and cut none of its own: " + ways + " ways known");
                helper.assertTrue(Math.abs(at.getX() - x) <= 3 && Math.abs(at.getZ() - Z) <= 3, "it came out at the head of the known stairs: " + at.toShortString());
                helper.succeed();
            }
        });
    }

    // ============================================================ tmn05: a new way, and kept for the next

    /**
     * Shut in deep in solid rock with no way known: the first folk cuts its own way up, and it is booked as a way out;
     * a second folk lost in the same pocket goes up those stairs, and no second way is cut.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "tmn05_new_route")
    public static void tmn05_new_route(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1768000;
        fresh(level, x, 40);
        int top = rock(level, x, Z, 18, 18);
        BlockPos pocket = new BlockPos(x, top - 12, Z);
        air(level, pocket, pocket.above());
        VillageFolkEntity first = folk(level, x, pocket, new ItemStack(Items.IRON_PICKAXE));
        helper.assertTrue(first != null, "a folk");
        helper.runAtTickTime(5, () -> first.enqueueFront(Job.mine(pocket.getY(), MineStairs.OUT)));
        VillageFolkEntity[] second = { null };
        long[] firstOut = { -1 };
        helper.onEachTick(() -> {
            keepAtWork(level, first);
            if (second[0] != null) keepAtWork(level, second[0]);
            long t = helper.getTick();
            if (t % 300 == 0) Kit.log("tmn05 @" + t + " first at " + first.blockPosition().toShortString()
                + (second[0] == null ? "" : ", second at " + second[0].blockPosition().toShortString() + " — " + second[0].debugLine()));
            if (firstOut[0] < 0) {
                if (t <= 5 || !MineStairs.open(level, first.blockPosition()) || first.peekJob() != null) return;
                int outs = outWays(level);
                Kit.log("tmn05 first out at " + t + "; ways out known " + outs);
                helper.assertTrue(outs == 1, "the way it cut is booked as a way out: " + outs);
                firstOut[0] = t;
                first.moveTo(x + 12.5, top, Z + 12.5, 0.0F, 0.0F);
                VillageFolkEntity f2 = folk(level, x, pocket, new ItemStack(Items.STONE_PICKAXE));
                helper.assertTrue(f2 != null, "a second folk");
                second[0] = f2;
                f2.enqueueFront(Job.mine(pocket.getY(), MineStairs.OUT));
                return;
            }
            VillageFolkEntity f2 = second[0];
            if (t - firstOut[0] > 20 && MineStairs.open(level, f2.blockPosition())) {
                int ways = outWays(level);
                Kit.log("tmn05 second out at " + t + " at " + f2.blockPosition().toShortString() + "; ways known " + ways);
                helper.assertTrue(ways == 1, "the second went up the first one's way, and cut none: " + ways + " ways known");
                helper.succeed();
            }
        });
    }

    /** The ways out folk cut for themselves that the world keeps. */
    private static int outWays(ServerLevel level) {
        int n = 0;
        for (long key : MineStairs.keysForTests(level)) if (MineStairs.kindOf(key) == MineStairs.Kind.OUT) n++;
        return n;
    }

    // ============================================================ tmn06: out of a hole, and nothing left behind

    /**
     * A folk buried in a narrow hole three deep in the rock, a block over its head: it breaks out and builds itself up
     * and out; once free it steps off its steps and takes them all down, back into its pack.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tmn06_escape")
    public static void tmn06_escape(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1770000;
        fresh(level, x, 40);
        int top = rock(level, x, Z, 12, 10);
        BlockPos bottom = new BlockPos(x, top - 3, Z);
        air(level, bottom, bottom.above(2));
        VillageFolkEntity f = folk(level, x, bottom, new ItemStack(Items.STONE_PICKAXE));
        helper.assertTrue(f != null, "a folk");
        level.setBlock(bottom.above(), Blocks.STONE.defaultBlockState(), 2);   // buried: rock at its head
        long[] free = { -1 };
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            int standing = Footholds.countForTests(level, bottom, 8);
            if (t % 100 == 0) Kit.log("tmn06 @" + t + " at " + f.blockPosition().toShortString() + ", steps standing " + standing + " — " + f.debugLine());
            if (free[0] < 0 && f.blockPosition().getY() >= top) {
                free[0] = t;
                Kit.log("tmn06 out of the hole at " + t + " with " + standing + " steps standing");
            }
            if (free[0] > 0 && standing == 0) {
                int blocks = cobbleBlocks(level, bottom.offset(-1, 0, -1), bottom.offset(1, 3, 1));
                Kit.log("tmn06 tidy at " + t + ": cobble left in the hole " + blocks + ", in its pack " + cobble(f) + "/" + COBBLE);
                helper.assertTrue(blocks == 0, "no block it laid is left in the hole: " + blocks);
                helper.assertTrue(cobble(f) >= COBBLE, "every one is back in its pack: " + cobble(f) + "/" + COBBLE);
                helper.succeed();
            }
        });
    }

    // ============================================================ tmn07: a cave section swept

    /**
     * A cave chamber in the rock, ore scattered in its walls, floor and roof (one in the roof out of reach from the
     * floor), and an ore hidden in the rock over the roof with no face open: a village miner working the chamber
     * leaves no ore showing (the roof's built up to, the steps taken down after), and the hidden ore where it is.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "tmn07_cave_sweep")
    public static void tmn07_cave_sweep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1772000;
        fresh(level, x, 40);
        int top = rock(level, x, Z, 12, 18);
        BlockPos floor = new BlockPos(x, top - 12, Z);                       // the chamber's floor (feet level)
        air(level, floor.offset(-3, 0, -3), floor.offset(3, 5, 3));         // seven across, six high
        List<BlockPos> ores = List.of(
            floor.offset(-4, 1, 0),                                          // west wall
            floor.offset(4, 0, 1),                                           // east wall, low
            floor.offset(1, 3, -4),                                          // north wall, high
            floor.offset(-1, 2, 4),                                          // south wall
            floor.offset(2, -1, 2),                                          // the floor
            floor.offset(-2, -1, -1),                                        // the floor
            floor.offset(0, 6, 0));                                          // the roof, out of reach from the floor
        for (int i = 0; i < ores.size(); i++) {
            level.setBlock(ores.get(i), (i % 2 == 0 ? Blocks.IRON_ORE : Blocks.COAL_ORE).defaultBlockState(), 2);
        }
        BlockPos hidden = floor.offset(2, 8, -2);                            // two blocks of rock over the roof
        level.setBlock(hidden, Blocks.COPPER_ORE.defaultBlockState(), 2);
        helper.assertTrue(!OreSight.exposed(level, hidden), "the hidden ore shows no face");
        VillageFolkEntity f = folk(level, x, floor, new ItemStack(Items.IRON_PICKAXE));
        helper.assertTrue(f != null, "a folk");
        f.assignPlot(WorkZone.around(floor, 8, floor.getY() - 2), "the test's cave");
        f.setAutonomous(false);
        f.enqueueFront(Job.mine(floor.getY(), "north"));
        int[] built = { 0 };
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            int showing = 0;
            for (BlockPos p : BlockPos.betweenClosed(floor.offset(-5, -2, -5), floor.offset(5, 7, 5))) {
                if (OreSight.isOre(level.getBlockState(p)) && OreSight.exposed(level, p)) showing++;
            }
            int standing = Footholds.countForTests(level, floor, 10);
            built[0] = Math.max(built[0], standing);
            if (t % 200 == 0) Kit.log("tmn07 @" + t + " at " + f.blockPosition().toShortString() + ": ore showing " + showing + ", steps standing " + standing + " — " + f.debugLine());
            if (t > 20 && showing == 0 && standing == 0) {
                BlockState h = level.getBlockState(hidden);
                Kit.log("tmn07 swept at " + t + ": the hidden ore " + h.getBlock() + ", raw iron " + f.countMatching(s -> s.is(Items.RAW_IRON))
                    + ", coal " + f.countMatching(s -> s.is(Items.COAL)) + "; built up " + built[0] + " for the roof");
                helper.assertTrue(h.is(Blocks.COPPER_ORE), "the ore with no face open is left in the rock");
                helper.assertTrue(f.countMatching(s -> s.is(Items.RAW_IRON)) >= 4, "the iron taken, the roof's too");
                helper.assertTrue(built[0] >= 1, "it built itself up to the roof's ore (and the steps came down): " + built[0]);
                helper.succeed();
            }
        });
    }
}
