package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * "pick up the items around here" — sweeps every dropped item within 16
 * blocks into the pack, nearest first.
 */
public class CleanupGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private ItemEntity target;
    private int pickedUp;
    private int stuckTicks;
    private int myGen;

    public CleanupGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.CLEANUP && assistant.getTarget() == null;
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
        this.pickedUp = 0;
        this.stuckTicks = 0;
        this.target = null;
    }

    @Override
    public void stop() {
        this.job = null;
        this.target = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.pollJob();
        this.job = null;
        this.target = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null) return;

        if (target == null || !target.isAlive()) {
            target = findNearest();
            stuckTicks = 0;
            if (target == null) {
                finish(pickedUp > 0
                    ? "All tidy — picked up " + pickedUp + " items."
                    : "Nothing to pick up around here.");
                return;
            }
        }

        assistant.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (assistant.distanceToSqr(target) > 4.0) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(target, 1.2D);
            }
            if (++stuckTicks > 160) {
                target = null; // unreachable — try another
            }
            return;
        }

        ItemStack stack = target.getItem();
        int count = stack.getCount();
        ItemStack leftover = assistant.insertItem(stack);
        pickedUp += count - leftover.getCount();
        if (leftover.isEmpty()) {
            target.discard();
        } else {
            target.setItem(leftover);
            finish("Pack's full — picked up " + pickedUp + " items.");
            return;
        }
        target = null;
    }

    @Nullable
    private ItemEntity findNearest() {
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity item : assistant.level().getEntitiesOfClass(
                ItemEntity.class, assistant.getBoundingBox().inflate(16.0))) {
            double d = item.distanceToSqr(assistant);
            if (d < bestDist) { bestDist = d; best = item; }
        }
        return best;
    }
}
