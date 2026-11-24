package com.jrpetty.mcassistant.pa;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * [pa36] Carries out a logistics job (PaLogiJob) with the assistant's own legs and hands: walking to a chest, opening
 * it, moving stacks in or out at a player's pace, closing it. While the job has handed work to the original engine
 * (gathering, crafting, smelting, hunting, fetching dropped things) this goal stands aside so the engine's goals can
 * run, and picks the job up again when the engine's queue is empty.
 */
public class PaLogiGoal extends Goal {

    private final PlayerAssistantEntity a;
    private PaTask t;

    public PaLogiGoal(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean mine() {
        return a.task != null && a.task.kind == PaTask.Kind.LOGI && a.getTarget() == null && !a.plan.paused && a.jobCount() == 0
            && a.mind.climbing == 0;
    }

    @Override
    public boolean canUse() {
        return mine();
    }

    @Override
    public boolean canContinueToUse() {
        return mine() && a.task == t;
    }

    @Override
    public void start() {
        t = a.task;
        PaLogiJob j = PaLogistics.job(a, t);
        if (j != null) j.resumed(a);
    }

    @Override
    public void stop() {
        a.getNavigation().stop();
        PaLogiJob j = t == null ? null : PaLogistics.job(a, t);
        if (j != null) j.paused(a);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (t == null || a.task != t) return;
        PaLogiJob j = PaLogistics.job(a, t);
        if (j == null) {
            t.result = "I lost track of that job.";
            t.failed = true;
            a.task = null;
            return;
        }
        t.ticks++;
        j.tick(a);
    }
}
