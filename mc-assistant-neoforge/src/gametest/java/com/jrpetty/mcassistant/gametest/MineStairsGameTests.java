package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.MineStairs;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Nobody is left at the bottom of a mine (entity/MineStairs, MineGoal): a folk on stairs somebody broke
 * mends them as it climbs; a folk shut in down there with no stairs at all cuts its own up to the sky;
 * a hand sent for stone never takes a step from under the stairs; and a folk lost underground with
 * nothing to do down there is sent up of its own accord.
 *
 * <p>The test world is flat, three blocks of earth over bedrock, so each test stands a block of rock on
 * it to be underground in.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class MineStairsGameTests {

    private static final String EMPTY = "empty";

    /** A block of rock on the flat world, `half` out each way and `height` high: the y of its top surface. */
    private static int rock(ServerLevel level, int cx, int cz, int half, int height) {
        int base = Kit.surface(level, cx, cz).getY();
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int y = base; y < base + height; y++) {
                    level.setBlock(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        return base + height;
    }

    private static VillageFolkEntity folk(ServerLevel level, BlockPos home, BlockPos at) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, home, 0.0F);
        if (f == null) return null;
        f.clearQueue();
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.STONE_PICKAXE));
        f.insertItem(new ItemStack(Items.COBBLESTONE, 32));
        f.insertItem(new ItemStack(Items.TORCH, 8));
        f.insertItem(new ItemStack(Items.BREAD, 8));
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        return f;
    }

    /** No break, no meal and no bell in the middle of a test of something else. */
    private static void keepAtWork(ServerLevel level, VillageFolkEntity f) {
        if (f.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
    }

    /**
     * Stairs fourteen steps down into rock, two steps' floors taken out from under them and a block laid
     * across a third: a folk at the bottom, sent up, climbs them, laying the floors again and cutting the
     * block out as it goes, and comes out at the top.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "ms01_mends_stairs")
    public static void ms01_mends_stairs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 510000, z = 60000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        int top = rock(level, x, z, 18, 24);
        List<BlockPos> steps = new ArrayList<>();
        for (int k = 0; k <= 14; k++) {
            BlockPos s = new BlockPos(x, top - k, z - k);
            steps.add(s);
            for (int h = 0; h <= 2; h++) level.setBlock(s.above(h), Blocks.AIR.defaultBlockState(), 2);
        }
        MineStairs.record(level, MineStairs.plotKey(new BlockPos(x, 0, z)), steps);
        BlockPos gone5 = steps.get(5).below(), gone6 = steps.get(6).below();
        level.setBlock(gone5, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(gone6, Blocks.AIR.defaultBlockState(), 2);
        BlockPos laid = steps.get(9);
        level.setBlock(laid, Blocks.GRAVEL.defaultBlockState(), 2);
        BlockPos bottom = steps.get(14);
        VillageFolkEntity f = folk(level, new BlockPos(x + 6, top, z + 6), bottom);
        helper.assertTrue(f != null, "a folk");
        helper.assertTrue(MineStairs.underground(level, bottom), "the bottom of the stairs is underground");
        helper.runAtTickTime(5, () -> f.enqueueFront(Job.mine(bottom.getY(), MineStairs.OUT)));
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            BlockPos at = f.blockPosition();
            if (t % 200 == 0) Kit.log("ms01 @" + t + " at " + at.toShortString() + " — " + f.debugLine());
            if (at.getY() >= top && !MineStairs.underground(level, at)) {
                boolean floors = !level.getBlockState(gone5).isAir() && !level.getBlockState(gone6).isAir();
                boolean clear = level.getBlockState(laid).getCollisionShape(level, laid).isEmpty();
                Kit.log("ms01 out at " + t + " at " + at.toShortString() + "; floors laid again " + floors
                    + " (" + level.getBlockState(gone5).getBlock() + ", " + level.getBlockState(gone6).getBlock() + "), the block across the stairs cut out " + clear);
                helper.assertTrue(floors, "the two steps taken out from under the stairs are laid again");
                helper.assertTrue(clear, "the block laid across the stairs is cut out");
                helper.succeed();
            }
        });
    }

    /** Shut in fourteen down in solid rock, no stairs anywhere: it cuts its own up to the sky, and they are kept. */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "ms02_digs_out")
    public static void ms02_digs_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 514000, z = 60000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        int top = rock(level, x, z, 18, 22);
        BlockPos pocket = new BlockPos(x, top - 14, z);
        level.setBlock(pocket, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(pocket.above(), Blocks.AIR.defaultBlockState(), 2);
        VillageFolkEntity f = folk(level, new BlockPos(x + 4, top, z + 4), pocket);
        helper.assertTrue(f != null, "a folk");
        helper.runAtTickTime(5, () -> f.enqueueFront(Job.mine(pocket.getY(), MineStairs.OUT)));
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            BlockPos at = f.blockPosition();
            if (t % 300 == 0) Kit.log("ms02 @" + t + " at " + at.toShortString() + " — " + f.debugLine());
            if (t > 5 && MineStairs.open(level, at)) {
                int longest = 0;
                for (long key : MineStairs.keysForTests(level)) longest = Math.max(longest, MineStairs.stairsForTests(level, key).size());
                Kit.log("ms02 out at " + t + " at " + at.toShortString() + " (" + (at.getY() - pocket.getY()) + " up); stairs kept: " + longest + " steps");
                helper.assertTrue(at.getY() - pocket.getY() >= 10, "it climbed out of the rock, not round it: " + at.toShortString());
                helper.assertTrue(longest >= 8, "the stairs it cut are kept: " + longest + " steps");
                helper.succeed();
            }
        });
    }

    /**
     * A hand sent for stone beside a mine's stairs: the floors of the steps (the nearest stone there is)
     * stay where they are, and it takes its stone from the rock further off.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "ms03_stairs_spared")
    public static void ms03_stairs_spared(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 518000, z = 60000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos g = Kit.surface(level, x, z);
        // Six steps' floors, stone in the grass, the stairs' head at the folk's feet.
        List<BlockPos> steps = new ArrayList<>();
        List<BlockPos> floors = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            BlockPos step = g.offset(1 + k, 0, 0);
            steps.add(step);
            floors.add(step.below());
            level.setBlock(step.below(), Blocks.STONE.defaultBlockState(), 2);
        }
        MineStairs.record(level, MineStairs.plotKey(g), steps);
        // The rock to take: a row of stone ten blocks off.
        for (int k = 0; k < 12; k++) level.setBlock(g.offset(-10, -1, -3 + k % 6).below(k / 6), Blocks.STONE.defaultBlockState(), 2);
        VillageFolkEntity f = folk(level, g, g);
        helper.assertTrue(f != null, "a folk");
        f.getInventoryItems().clear();
        f.insertItem(new ItemStack(Items.STONE_PICKAXE));
        helper.runAtTickTime(5, () -> f.enqueueFront(Job.gather(GatherGoal.Kind.STONE, 4)));
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            int got = f.countCarried(s -> s.is(Items.COBBLESTONE));
            for (BlockPos fl : floors) {
                if (!level.getBlockState(fl).is(Blocks.STONE)) {
                    helper.fail("a step's floor of the mine stairs was taken at " + fl.toShortString() + " (tick " + t + ") — " + f.debugLine());
                    return;
                }
            }
            if (t % 300 == 0) Kit.log("ms03 @" + t + " cobblestone " + got + " — " + f.debugLine());
            if (got >= 3) {
                Kit.log("ms03 " + got + " stone gathered at " + t + ", every step's floor still there");
                helper.succeed();
            }
        });
    }

    /**
     * A folk lost in a hole in the rock with nothing to do down there and no way up it can walk: it is
     * sent up of its own accord (MineStairs.lookForAWayUp), and comes out.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "ms04_lost_folk")
    public static void ms04_lost_folk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 522000, z = 60000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        int top = rock(level, x, z, 18, 18);
        BlockPos pocket = new BlockPos(x, top - 10, z);
        for (int dx = 0; dx <= 2; dx++) {
            level.setBlock(pocket.offset(dx, 0, 0), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(pocket.offset(dx, 1, 0), Blocks.AIR.defaultBlockState(), 2);
        }
        VillageFolkEntity f = folk(level, new BlockPos(x + 4, top, z + 4), pocket);
        helper.assertTrue(f != null, "a folk");
        final boolean[] sent = { false };
        helper.onEachTick(() -> {
            keepAtWork(level, f);
            long t = helper.getTick();
            BlockPos at = f.blockPosition();
            Job j = f.peekJob();
            if (!sent[0] && j != null && j.type() == Job.Type.MINE && MineStairs.OUT.equals(j.arg())) {
                sent[0] = true;
                Kit.log("ms04 sent up at " + t + " from " + at.toShortString());
            }
            if (t % 400 == 0) Kit.log("ms04 @" + t + " at " + at.toShortString() + " — " + f.debugLine());
            if (t > 20 && MineStairs.open(level, at)) {
                Kit.log("ms04 out at " + t + " at " + at.toShortString() + "; sent up by itself " + sent[0]);
                helper.assertTrue(sent[0], "it was sent up because it was lost, not by the test");
                helper.succeed();
            }
        });
    }
}
