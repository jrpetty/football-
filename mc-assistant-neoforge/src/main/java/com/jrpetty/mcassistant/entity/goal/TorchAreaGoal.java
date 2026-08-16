package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.EnumSet;

/**
 * "light up the area" — walks a grid around the spot and torches every dark
 * patch until nothing can spawn there (or the torches run out).
 */
public class TorchAreaGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private final ArrayDeque<BlockPos> spots = new ArrayDeque<>();
    @Nullable private BlockPos current;
    private int placed;
    private int stuckTicks;
    private int myGen;

    public TorchAreaGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.TORCH_AREA && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null
            && assistant.taskGen() == myGen && assistant.peekJob() == job;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.spots.clear();
        this.current = null;
        this.placed = 0;
        this.stuckTicks = 0;
        if (assistant.countMatching(s -> s.is(Items.TORCH)) == 0) {
            assistant.topUpKit();
        }
        if (assistant.countMatching(s -> s.is(Items.TORCH)) == 0) {
            finish("I have no torches — \"craft torches\" first (coal + stick).");
            return;
        }
        int radius = job != null ? job.amount() : 12;
        BlockPos feet = assistant.feetPos();
        // A grid of candidate spots every 5 blocks; ground-snap each.
        for (int dx = -radius; dx <= radius; dx += 5) {
            for (int dz = -radius; dz <= radius; dz += 5) {
                BlockPos spot = groundSnap(feet.offset(dx, 0, dz));
                if (spot != null) spots.addLast(spot);
            }
        }
        assistant.say("Lighting up the area.");
    }

    /** Find a placeable, dark, floor-backed cell near this column. */
    @Nullable
    private BlockPos groundSnap(BlockPos around) {
        for (int dy = 4; dy >= -4; dy--) {
            BlockPos pos = around.above(dy);
            BlockPos floor = pos.below();
            if (assistant.level().getBlockState(pos).canBeReplaced()
                && assistant.level().getBlockState(floor).isFaceSturdy(assistant.level(), floor, Direction.UP)) {
                return assistant.level().getMaxLocalRawBrightness(pos) < 8 ? pos : null;
            }
        }
        return null;
    }

    @Override
    public void stop() {
        this.job = null;
        this.current = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.pollJob();
        this.job = null;
        this.current = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null) return;

        if (current == null) {
            current = spots.pollFirst();
            stuckTicks = 0;
            if (current == null) {
                finish("Area's lit — placed " + placed + " torch" + (placed == 1 ? "" : "es") + ".");
                return;
            }
        }

        // Might have been lit by a neighbor torch in the meantime.
        if (assistant.level().getMaxLocalRawBrightness(current) >= 8
            || !assistant.level().getBlockState(current).canBeReplaced()) {
            current = null;
            return;
        }

        double distSq = assistant.getEyePosition().distanceToSqr(
            current.getX() + 0.5, current.getY() + 0.5, current.getZ() + 0.5);
        assistant.getLookControl().setLookAt(
            current.getX() + 0.5, current.getY() + 0.5, current.getZ() + 0.5);
        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    current.getX() + 0.5, current.getY(), current.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 120) {
                current = null; // can't get there — next spot
            }
            return;
        }

        if (assistant.removeMatching(s -> s.is(Items.TORCH), 1) < 1) {
            finish("Out of torches — placed " + placed + " so far.");
            return;
        }
        assistant.level().setBlockAndUpdate(current, Blocks.TORCH.defaultBlockState());
        assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        placed++;
        current = null;
    }
}
