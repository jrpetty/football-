package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Clearing out an old chest: once a village's goods have the Village Storehouse to go to,
 * the chests its folk set down here and there over the years are emptied into it. A hand
 * walks to one, loads its pack with what is in it, and — when it is empty — takes the
 * chest itself up as well (or, for one that is part of a building's furniture, leaves it
 * standing as furniture and no longer one of the village's stores). The deposit at the
 * storehouse that follows carries it all home. A pack that fills first comes back for
 * the rest another time.
 */
public class RetireGoal extends Goal {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private BlockPos chest;
    private int stuckTicks;
    private double bestDistSq = Double.MAX_VALUE;
    private int myGen;

    public RetireGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.RETIRE && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.stuckTicks = 0;
        this.bestDistSq = Double.MAX_VALUE;
        this.chest = job == null ? null : TravelGoal.parseCoords(job.arg());
        if (chest == null) finish(null);
    }

    @Override
    public void stop() {
        this.job = null;
        this.chest = null;
        assistant.getNavigation().stop();
    }

    private void finish(@Nullable String message) {
        if (message != null) assistant.sayRoutine(message);
        if (chest != null) com.jrpetty.mcassistant.entity.Retiring.release(chest);
        assistant.pollJob();
        this.job = null;
        this.chest = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || chest == null) return;
        double distSq = assistant.getEyePosition().distanceToSqr(chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5);
        assistant.getLookControl().setLookAt(chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5);
        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(chest.getX() + 0.5, chest.getY(), chest.getZ() + 0.5, 1.1D);
            }
            if (distSq < bestDistSq - 1.0) {
                bestDistSq = distSq;
                stuckTicks = 0;
            } else if (++stuckTicks > 300) {
                if (assistant.isSettler() && assistant.putBeside(chest)) {
                    stuckTicks = 0;
                    bestDistSq = Double.MAX_VALUE;
                    return;
                }
                com.jrpetty.mcassistant.entity.Retiring.unreachable(assistant.level(), chest);
                finish(null);
            }
            return;
        }
        BlockEntity be = assistant.level().getBlockEntity(chest);
        if (!(be instanceof Container c) || !com.jrpetty.mcassistant.entity.Retiring.retirable(assistant.level(), chest, be)) {
            LOG.info("[MCA-RETIRE] {} found no old chest of the village's at {} ({})", assistant.getName().getString(),
                chest.toShortString(), assistant.level().getBlockState(chest));
            finish(null);
            return;
        }
        // Everything out, as far as the pack will hold it.
        boolean full = false;
        for (int i = 0; i < c.getContainerSize() && !full; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) continue;
            ItemStack left = assistant.insertItem(s.copy());
            int took = s.getCount() - left.getCount();
            if (took > 0) {
                s.shrink(took);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            }
            if (!left.isEmpty()) full = true;
        }
        c.setChanged();
        assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        LOG.info("[MCA-RETIRE] {} emptied the old chest at {}{}", assistant.getName().getString(), chest.toShortString(),
            c.isEmpty() ? "" : " (pack full, some left)");
        if (!c.isEmpty()) {
            finish("My pack's full — I'll be back for the rest of that old chest.");
            return;
        }
        // Empty: furniture stays furniture; anything else is taken up and carried home.
        BlockState state = assistant.level().getBlockState(chest);
        if (com.jrpetty.mcassistant.entity.Retiring.isFurniture(assistant, chest)) {
            com.jrpetty.mcassistant.entity.Retiring.unmark(assistant.level(), chest);
            finish("Emptied the old chest — everything goes to the storehouse now.");
            return;
        }
        ItemStack item = new ItemStack(state.getBlock().asItem());
        assistant.level().removeBlock(chest, false);
        if (!item.isEmpty()) {
            ItemStack left = assistant.insertItem(item);
            if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(assistant.level(), chest, left);
        }
        assistant.note(AssistantEntity.Deed.LOADS_HAULED, 1);
        finish("Took the old chest up — everything goes to the storehouse now.");
    }
}
