package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Patrol: walks laps between two named places in guard mode, engaging any
 * hostiles it meets (combat pauses the walk; the patrol resumes after).
 * Endless by design — "stop" ends it.
 */
public class PatrolGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private BlockPos destA;
    @Nullable private BlockPos destB;
    private boolean towardB = true;
    private int repathCooldown;
    private int noProgressTicks;
    private double lastDistSq = Double.MAX_VALUE;
    private int myGen;

    public PatrolGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.PATROL && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null
            && assistant.taskGen() == myGen && assistant.peekJob() == job;
    }

    @Nullable
    private BlockPos resolve(String name) {
        return "home".equals(name) ? assistant.getHome() : assistant.getWaypoint(name);
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.repathCooldown = 0;
        this.noProgressTicks = 0;
        this.lastDistSq = Double.MAX_VALUE;
        String[] places = job != null && job.arg() != null ? job.arg().split("\\|") : new String[0];
        if (places.length != 2) {
            finish("Tell me two places — \"patrol between home and the mine\".");
            return;
        }
        this.destA = resolve(places[0].trim());
        this.destB = resolve(places[1].trim());
        if (destA == null || destB == null) {
            finish("I don't know " + (destA == null ? "\"" + places[0].trim() + "\"" : "\"" + places[1].trim() + "\"")
                + " — mark it first (\"remember this spot as ...\").");
            return;
        }
        assistant.setMode(AssistantEntity.Mode.GUARD);
        assistant.say("Patrolling between " + places[0].trim() + " and " + places[1].trim()
            + " — say \"stop\" to end the watch.");
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
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || destA == null || destB == null) return;
        BlockPos dest = towardB ? destB : destA;

        double distSq = assistant.distanceToSqr(dest.getX() + 0.5, dest.getY() + 1, dest.getZ() + 0.5);
        if (distSq < 9.0) {
            towardB = !towardB; // reached one end — turn around
            lastDistSq = Double.MAX_VALUE;
            noProgressTicks = 0;
            return;
        }

        if (--repathCooldown <= 0) {
            repathCooldown = 40;
            assistant.getNavigation().moveTo(dest.getX() + 0.5, dest.getY() + 1, dest.getZ() + 0.5, 1.1D);
        }

        if (distSq < lastDistSq - 0.5) {
            lastDistSq = distSq;
            noProgressTicks = 0;
        } else if (++noProgressTicks > 400) {
            // Can't reach this end — turn around rather than give up the watch.
            towardB = !towardB;
            lastDistSq = Double.MAX_VALUE;
            noProgressTicks = 0;
        }
    }
}
