package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * [soak40] What the long soak of the town found, held to. On ground of its own: x 9,430,000 to 9,440,000, z 92000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SoakGameTests {

    private static final String EMPTY = "empty";

    /**
     * A folk with blocks set down over it (a street raised a course where it stood: stone at its feet and its head) steps
     * up out of them onto the top, unhurt, rather than dying "trapped in the ground".
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "soak_sk01_step_out")
    public static void sk01_step_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 9_430_000, z = 92000;
        Kit.hold(level, x, z, 16);
        Kit.prepare(level, x, z, 16);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        BlockPos at = heart.offset(6, 0, 6);
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        f.getNavigation().stop();
        float full = f.getHealth();
        // The street raised two courses over a 5x5 where it stands: nowhere to step aside at its own height.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(at.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(at.offset(dx, 1, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        // At the first hurt from the wall it is out of it, onto the top, and the hurt is not taken.
        boolean took = f.hurt(level.damageSources().inWall(), 1.0F);
        Kit.log("sk01 buried at " + at.toShortString() + ": hurt taken " + took + ", now at " + f.blockPosition().toShortString()
            + ", health " + f.getHealth() + " of " + full + ", in the wall " + f.isInWall());
        helper.assertTrue(!took && f.getHealth() >= full, "the hurt not taken: " + took + ", health " + f.getHealth());
        helper.assertTrue(f.blockPosition().getY() >= at.getY() + 2 && !f.isInWall(),
            "stood on the top of what was set down over it: " + f.blockPosition().toShortString());
        // And left to itself in the town, it is still alive and unhurt a while on.
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(f.isAlive() && f.getHealth() >= full, "alive and unhurt: " + f.getHealth());
            helper.succeed();
        });
    }

    /**
     * The road between two towns surveyed a slice at a time over ground nobody is near: not a road yet while the survey
     * is under way (the server goes on), then the very road a survey made all at once would have given.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "soak_sk02_sliced_survey")
    public static void sk02_sliced_survey(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.RoadWorks.resetForTests();
        int x = 9_434_000, z = 92000, far = 260;
        java.util.UUID[] towns = new java.util.UUID[2];
        for (int i = 0; i < 2; i++) {
            int tx = x + i * far;
            Kit.hold(level, tx, z, 24);
            Kit.prepare(level, tx, z, 24);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, tx, z), 0.0F);
            helper.assertTrue(f != null && f.ownerId() != null, "town " + i);
            towns[i] = f.ownerId();
        }
        com.jrpetty.mcassistant.entity.Routes.sliceForTests(0);
        final int[] calls = { 0, 0 };                                   // calls, of them not a road yet
        helper.onEachTick(() -> {
            calls[0]++;
            java.util.List<int[]> line = com.jrpetty.mcassistant.entity.RoadWorks.lineForTests(towns[0], towns[1]);
            if (line == null) {
                calls[1]++;
                if (calls[0] > 3800) {
                    com.jrpetty.mcassistant.entity.Routes.sliceForTests(30);
                    helper.fail("the survey never finished: " + calls[0] + " calls");
                }
                return;
            }
            com.jrpetty.mcassistant.entity.Routes.sliceForTests(30);
            java.util.List<int[]> once = com.jrpetty.mcassistant.entity.Routes.surveyNowForTests(towns[0], towns[1]);
            boolean same = once != null && once.size() == line.size();
            for (int k = 0; same && k < line.size(); k++) same = once.get(k)[0] == line.get(k)[0] && once.get(k)[1] == line.get(k)[1];
            Kit.log("sk02 the road after " + calls[0] + " calls (" + calls[1] + " of them not yet a road): " + line.size()
                + " steps; all at once: " + (once == null ? "none" : once.size()) + " steps, the same " + same);
            helper.assertTrue(calls[1] >= 2, "surveyed a slice at a time, not all at once: " + calls[1] + " calls before it was a road");
            helper.assertTrue(line.size() > far / 2, "a road between them: " + line.size());
            helper.assertTrue(same, "the very road the survey all at once makes");
            helper.succeed();
        });
    }
}
