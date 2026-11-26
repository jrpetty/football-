package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Blink;
import com.jrpetty.mcassistant.entity.PitStep;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * [tp50] A pit a folk cannot walk out of (the soak's slot between a sunken house's retaining wall and the hill it was cut
 * into): it digs steps up the natural bank and walks out, in a player's sight, nothing built cut. On ground of its own: x
 * 9,560,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PitStepGameTests {

    private static final String EMPTY = "empty";

    /**
     * pt01: three folk stood in a slot three deep, a block wide and seven long, a brick wall down one side and the meadow's
     * earth the other, a player looking on from the bank: out of it within two minutes, by steps cut in the earth side, walked
     * up (not one seen to jump); the brick wall whole.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "pt01_pit_step")
    public static void pt01_pit_step(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9_560_000;
        List<VillageFolkEntity> folk = MineSiteGameTests.town(level, x, 64, 3, StationTask.FARM);
        helper.assertTrue(folk.size() == 3, "three folk");
        PitStep.resetForTests();
        BlockPos heart = folk.get(0).villageCentre();
        BlockPos top = Kit.surface(level, heart.getX() + 10, heart.getZ() + 6);    // the meadow's standing height
        int y = top.getY(), sx = top.getX(), sz = top.getZ();
        // The slot: air three high where the ground was, its floor the earth under it; bricks along its south side.
        for (int i = 0; i < 7; i++) {
            for (int h = 1; h <= 3; h++) {
                level.setBlock(new BlockPos(sx + i, y - h, sz), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(new BlockPos(sx + i, y - h, sz + 1), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
            level.setBlock(new BlockPos(sx + i, y - 4, sz), Blocks.DIRT.defaultBlockState(), 2);
        }
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity f = folk.get(i);
            f.teleportTo(sx + 1 + 2 * i + 0.5, y - 3, sz + 0.5);
            f.getNavigation().stop();
        }
        ServerPlayer you = BlinkGameTests.watcher(helper, level, sx + 3.5, y, sz - 8.5);
        Blink.reset();
        Blink.setOn(true);
        level.setDayTime(1000);
        Kit.log("[VT] pt01 a slot at " + sx + ", " + (y - 3) + ", " + sz + ", three folk in it, a player on the bank");
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            int in = 0;
            for (VillageFolkEntity f : folk) {
                boolean inSlot = f.getY() < y - 1.5 && Math.abs(f.getZ() - (sz + 0.5)) < 0.8 && f.getX() >= sx && f.getX() < sx + 7;
                if (inSlot) in++;
                // Kept walking to the ground beyond the slot's wall (a walk round by its ends, once out), as the soak's folk were.
                if (inSlot && !PitStep.digging(f) && tick[0] % 40 == 0 && (f.getNavigation().isDone() || f.getNavigation().getPath() == null))
                    f.getNavigation().moveTo(sx + 3.5, y, sz + 6.5, 1.0D);
            }
            if (tick[0] % 200 == 0) Kit.log("[VT] pt01 @" + tick[0] + ": in the slot " + in + ", stairs " + PitStep.STAIRS + ", cut " + PitStep.CUT
                + ", seen jumps " + Blink.seenJumps());
            if (in > 0 && tick[0] < 3200) return false;
            int bricks = 0;
            for (int i = 0; i < 7; i++) for (int h = 1; h <= 3; h++) if (level.getBlockState(new BlockPos(sx + i, y - h, sz + 1)).is(Blocks.STONE_BRICKS)) bricks++;
            Kit.log("[VT] pt01 done @" + tick[0] + ": in the slot " + in + ", stairs " + PitStep.STAIRS + ", cut " + PitStep.CUT + ", bricks " + bricks
                + "/21, seen jumps " + Blink.seenJumps());
            for (String l : Blink.seenLines()) Kit.log("[VT] pt01 seen: " + l);
            BlinkGameTests.letGo(level, you);
            if (in > 0) helper.fail("still in the slot after " + tick[0] + " ticks: " + in);
            else if (PitStep.STAIRS < 1) helper.fail("out of the slot with no stair dug");
            else if (bricks != 21) helper.fail("the brick wall cut: " + bricks + " of 21 left");
            else if (Blink.seenJumps() - Blink.excusedSeenJumps() > 0) helper.fail("seen to jump: " + Blink.seenJumps());
            else helper.succeed();
            return true;
        });
    }
}
