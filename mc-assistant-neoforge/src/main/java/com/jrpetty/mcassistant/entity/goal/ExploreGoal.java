package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Explore / relocate. When the local area is tapped out the idle brain hands the
 * bot an EXPLORE job aimed at a ground spot ~30 blocks off. This goal walks it
 * there in staged re-paths and — crucially — ENDS THE LEG the instant it comes
 * within reach of a fresh resource patch, so the normal survival/gather brain
 * takes over and puts it to work. Arrive at a barren spot and the idle brain just
 * picks another leg, so it keeps striking out until it finds something. Night,
 * combat, and being hurt all preempt it (higher-priority goals), and it drops the
 * leg at nightfall so the go-home routine runs clean.
 */
public class ExploreGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private BlockPos dest;
    private int myGen;
    private int repathCd;
    private int noMoveTicks;
    private int scanCd;
    private double lastDistSq;

    public ExploreGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.EXPLORE && assistant.getTarget() == null;
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
        this.repathCd = 0;
        this.noMoveTicks = 0;
        this.scanCd = 0;
        this.lastDistSq = Double.MAX_VALUE;
        this.dest = TravelGoal.parseCoords(job != null ? job.arg() : null);
        if (dest == null) finish(false, null);
    }

    @Override
    public void stop() {
        this.job = null;
        this.dest = null;
        assistant.getNavigation().stop();
    }

    private void finish(boolean productive, @Nullable String message) {
        if (message != null) assistant.say(message);
        assistant.noteJobOutcome(productive);
        assistant.pollJob();
        this.job = null;
        this.dest = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || dest == null) return;

        // Night fell mid-trek: drop the leg so the go-home/shelter routine takes over.
        if (assistant.level().isNight()) { finish(false, null); return; }

        // Reached a fresh patch on the way? Stop here and let the work brain take
        // over — don't overshoot a stand of trees to reach an arbitrary point.
        if (--scanCd <= 0) {
            scanCd = 20;
            if (assistant.usefulResourceNearby(14)) {
                finish(true, "Found a good spot — getting to work.");
                return;
            }
        }

        double distSq = assistant.distanceToSqr(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
        if (distSq < 12.0) { // arrived; if still dry the idle brain picks the next leg
            finish(assistant.usefulResourceNearby(16), null);
            return;
        }

        if (--repathCd <= 0) {
            repathCd = 40;
            assistant.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.15D);
        }

        // No headway for ~12s -> the way is blocked; stop and let the brain re-pick
        // (EscapeGoal and the reach logic handle wedging; a new leg tries a new heading).
        if (distSq > lastDistSq - 0.5) {
            if (++noMoveTicks > 240) finish(false, null);
        } else {
            noMoveTicks = 0;
            lastDistSq = distSq;
        }
    }
}
