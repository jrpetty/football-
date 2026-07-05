package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Survival instinct — threat-aware. The assistant breaks off a fight it
 * shouldn't be in (badly hurt, or outnumbered with no way to heal), runs to
 * safety, and waits until it has eaten itself back to health. When it has a
 * home or a nearby owner it heads there; solo in the dark it runs AWAY from the
 * nearest monster and toward the brightest ground it can see (light = fewer
 * mobs = a chance to heal). It outranks every work goal, so the current job
 * pauses at the head of the queue and RESUMES once the danger has passed.
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
        // Badly hurt in (or just out of) a fight...
        if (assistant.getHealth() <= assistant.getMaxHealth() * 0.35F
            && (assistant.getTarget() != null
                || assistant.tickCount - assistant.lastDamageTick() < 100)) {
            return true;
        }
        // ...or a fight we should break off even above the HP floor.
        return assistant.shouldDisengage() && assistant.threatCount(10.0) > 0;
    }

    @Override
    public boolean canContinueToUse() {
        // Keep retreating until decently healed OR the pack of threats has thinned.
        return assistant.getHealth() < assistant.getMaxHealth() * 0.7F
            || assistant.threatCount(8.0) >= 2;
    }

    @Override
    public void start() {
        assistant.setRetreating(true);
        assistant.setTarget(null);
        boolean hurt = assistant.getHealth() <= assistant.getMaxHealth() * 0.4F;
        assistant.say(hurt ? "I'm hurt — falling back to recover!" : "Too many of them — pulling back!");
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
        this.repathCooldown = 30;
        BlockPos home = assistant.getHome();
        if (home != null) {
            assistant.getNavigation().moveTo(home.getX() + 0.5, home.getY() + 1, home.getZ() + 0.5, 1.4D);
            return;
        }
        // Only fall back to the player when it's NOT doing its own thing — a
        // detached, autonomous bot fends for itself (home or bright ground), it
        // doesn't come running to you.
        Player owner = assistant.getOwnerPlayer();
        if (!assistant.isAutonomous() && owner != null
            && assistant.distanceToSqr(owner) > 9.0 && assistant.distanceToSqr(owner) < 1600.0) {
            assistant.getNavigation().moveTo(owner, 1.5D);
            return;
        }
        fleeTowardLight();
    }

    /** Solo and in danger: run away from the nearest monster, biased toward the
     *  brightest of a few headings — light means fewer mobs and a place to heal. */
    private void fleeTowardLight() {
        Monster threat = assistant.nearestMonster(16.0);
        Vec3 pos = assistant.position();
        Vec3 away = threat != null ? pos.subtract(threat.position()) : new Vec3(1, 0, 1);
        away = new Vec3(away.x, 0, away.z);
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 1);
        away = away.normalize();
        double baseAngle = Math.atan2(away.z, away.x);
        double destX = pos.x + away.x * 12, destZ = pos.z + away.z * 12;
        int bestLight = -1;
        for (int k = -1; k <= 1; k++) {
            double ang = baseAngle + k * 0.5;
            double x = pos.x + Math.cos(ang) * 12;
            double z = pos.z + Math.sin(ang) * 12;
            int light = assistant.level().getMaxLocalRawBrightness(BlockPos.containing(x, pos.y, z));
            if (light > bestLight) { bestLight = light; destX = x; destZ = z; }
        }
        assistant.getNavigation().moveTo(destX, pos.y, destZ, 1.5D);
    }
}
