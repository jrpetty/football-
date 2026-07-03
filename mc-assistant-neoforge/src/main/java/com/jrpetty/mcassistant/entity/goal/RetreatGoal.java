package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Survival instinct: badly hurt, the assistant breaks off, runs to its home
 * point (or its owner), and waits until it has eaten itself back to health.
 * It runs at higher priority than every work goal, so the current job pauses
 * (staying at the head of the queue) and RESUMES by itself once recovered.
 */
public class RetreatGoal extends Goal {

    private final AssistantEntity assistant;
    private int repathCooldown;

    public RetreatGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        return assistant.getHealth() <= assistant.getMaxHealth() * 0.35F
            && (assistant.getTarget() != null
                || assistant.tickCount - assistant.lastDamageTick() < 100);
    }

    @Override
    public boolean canContinueToUse() {
        // Keep retreating until decently healed (eating happens meanwhile).
        return assistant.getHealth() < assistant.getMaxHealth() * 0.7F;
    }

    @Override
    public void start() {
        assistant.setRetreating(true);
        assistant.setTarget(null);
        assistant.say("I'm hurt — falling back to recover!");
        this.repathCooldown = 0;
        moveToSafety();
    }

    @Override
    public void stop() {
        assistant.setRetreating(false);
        assistant.getNavigation().stop();
        assistant.say("Recovered — back to it.");
    }

    @Override
    public void tick() {
        assistant.setTarget(null); // don't get baited back into the fight
        if (--repathCooldown <= 0) {
            moveToSafety();
        }
    }

    private void moveToSafety() {
        this.repathCooldown = 40;
        BlockPos home = assistant.getHome();
        if (home != null) {
            assistant.getNavigation().moveTo(home.getX() + 0.5, home.getY() + 1, home.getZ() + 0.5, 1.4D);
            return;
        }
        Player owner = assistant.getOwnerPlayer();
        if (owner != null && assistant.distanceToSqr(owner) > 9.0) {
            assistant.getNavigation().moveTo(owner, 1.4D);
        }
    }
}
