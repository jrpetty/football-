package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Grave guard: when the owner dies nearby, this job races to the death spot
 * and sweeps up everything they dropped before it despawns. The owner gets
 * it all back with "give me my stuff".
 */
public class RecoverGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private BlockPos dest;
    private boolean arrived;
    private int recovered;
    private int quietTicks;
    private int noProgressTicks;
    private double lastDistSq;
    private int myGen;

    public RecoverGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.RECOVER; // even mid-combat: this is urgent
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.taskGen() == myGen && assistant.peekJob() == job;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.arrived = false;
        this.recovered = 0;
        this.quietTicks = 0;
        this.noProgressTicks = 0;
        this.lastDistSq = Double.MAX_VALUE;
        this.dest = null;
        if (job != null && job.arg() != null) {
            try {
                String[] parts = job.arg().split(" ");
                this.dest = new BlockPos(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (Exception ignored) {
            }
        }
        if (dest == null) {
            finish("I lost track of where you fell.");
        }
    }

    @Override
    public void stop() {
        this.job = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.pollJob();
        this.job = null;
        this.dest = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || dest == null) return;

        double distSq = assistant.distanceToSqr(dest.getX() + 0.5, dest.getY() + 0.5, dest.getZ() + 0.5);
        if (!arrived) {
            if (distSq < 16.0) {
                arrived = true;
                assistant.getNavigation().stop();
            } else {
                if (assistant.getNavigation().isDone()) {
                    assistant.getNavigation().moveTo(
                        dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.4D);
                }
                if (distSq < lastDistSq - 0.5) {
                    lastDistSq = distSq;
                    noProgressTicks = 0;
                } else if (++noProgressTicks > 400) {
                    finish("I couldn't reach where you fell — got as close as I could.");
                }
                return;
            }
        }

        // At the grave: keep sweeping until nothing new shows up for ~5s.
        if (assistant.tickCount % 10 != 0) return;
        int before = recovered;
        for (ItemEntity drop : assistant.level().getEntitiesOfClass(
                ItemEntity.class, new AABB(dest).inflate(6.0))) {
            ItemStack stack = drop.getItem();
            int count = stack.getCount();
            ItemStack leftover = assistant.insertItem(stack);
            recovered += count - leftover.getCount();
            if (leftover.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(leftover);
            }
        }
        if (recovered > before) {
            quietTicks = 0;
        } else if ((quietTicks += 10) >= 100) {
            finish(recovered > 0
                ? "Secured " + recovered + " of your items — say \"give me my stuff\" when you're back."
                : "I got to where you fell but found nothing — I may have been too late.");
        }
    }
}
