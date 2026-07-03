package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Clear & flatten: "clear a 10x10 area" digs everything 3 blocks high at the
 * assistant's level in a rectangle around it, keeping the drops. It skips
 * fluid-bearing blocks (no surprise lava baths) and unbreakables.
 */
public class ClearGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private final List<BlockPos> cells = new ArrayList<>();
    private int cursor;
    private int clearedCount;
    private int workTicks;
    private int workNeeded;
    private int stuckTicks;
    @Nullable private BlockPos currentDig;
    private int myGen;

    public ClearGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.CLEAR && assistant.getTarget() == null;
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
        this.cells.clear();
        this.cursor = 0;
        this.clearedCount = 0;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.currentDig = null;

        int w = 8;
        int d = 8;
        if (job != null && job.arg() != null) {
            String[] parts = job.arg().split("x");
            try {
                w = Math.max(2, Math.min(24, Integer.parseInt(parts[0].trim())));
                d = Math.max(2, Math.min(24, Integer.parseInt(parts[1].trim())));
            } catch (Exception ignored) {
            }
        }
        BlockPos feet = assistant.feetPos();
        // Top-down within each column so gravel has nothing left to fall on.
        for (int h = 2; h >= 0; h--) {
            for (int dx = -w / 2; dx <= w / 2; dx++) {
                for (int dz = -d / 2; dz <= d / 2; dz++) {
                    cells.add(feet.offset(dx, h, dz));
                }
            }
        }
        cells.sort((p1, p2) -> {
            int byY = p2.getY() - p1.getY(); // highest first
            return byY != 0 ? byY : Double.compare(p1.distSqr(feet), p2.distSqr(feet));
        });
        assistant.say("Clearing a " + w + "x" + d + " area — keeping whatever drops.");
    }

    @Override
    public void stop() {
        this.job = null;
        this.currentDig = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(clearedCount > 0);
        assistant.pollJob();
        this.job = null;
        this.currentDig = null;
        assistant.getNavigation().stop();
    }

    private boolean shouldDig(BlockPos pos) {
        BlockState state = assistant.level().getBlockState(pos);
        if (state.canBeReplaced()) return false;
        if (!state.getFluidState().isEmpty()) return false;              // never open fluids
        if (!assistant.level().getFluidState(pos.above()).isEmpty()) return false; // or what's over them
        if (state.getDestroySpeed(assistant.level(), pos) < 0) return false; // bedrock etc.
        return true;
    }

    @Override
    public void tick() {
        if (job == null) return;

        if (assistant.isPackFull()) {
            finish("Pack's full — cleared " + clearedCount + " blocks so far. Stashing.");
            assistant.enqueueFront(Job.deposit());
            return;
        }

        if (currentDig != null) {
            digTick();
            return;
        }

        while (cursor < cells.size() && !shouldDig(cells.get(cursor))) {
            cursor++;
        }
        if (cursor >= cells.size()) {
            finish("Area's clear — " + clearedCount + " blocks out.");
            return;
        }

        BlockPos pos = cells.get(cursor);
        double distSq = assistant.getEyePosition().distanceToSqr(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 100) {
                cursor++; // unreachable — skip it
                stuckTicks = 0;
            }
            return;
        }
        stuckTicks = 0;
        BlockState state = assistant.level().getBlockState(pos);
        assistant.equipBestTool(state);
        this.currentDig = pos;
        this.workTicks = 0;
        this.workNeeded = assistant.workTicksFor(state);
    }

    private void digTick() {
        BlockPos pos = currentDig;
        if (pos == null) return;
        if (!shouldDig(pos)) {
            currentDig = null;
            cursor++;
            return;
        }
        assistant.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (++workTicks < workNeeded) {
            if (workTicks % 8 == 0) {
                assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            return;
        }
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            clearedCount++;
            assistant.damageHeldTool();
            // Falling gravel from above the cleared zone: knock it out too.
            BlockPos above = pos.above();
            if (assistant.level().getBlockState(above).getBlock() instanceof FallingBlock) {
                cells.add(cursor + 1, above);
            }
            sweepDrops(pos);
        }
        currentDig = null;
        cursor++;
    }

    private void sweepDrops(BlockPos around) {
        for (ItemEntity drop : assistant.level().getEntitiesOfClass(
                ItemEntity.class, new AABB(around).inflate(2.5))) {
            ItemStack leftover = assistant.insertItem(drop.getItem());
            if (leftover.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(leftover);
            }
        }
    }
}
