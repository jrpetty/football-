package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/** Trails the owner while the assistant is in FOLLOW mode. */
public class FollowOwnerGoal extends Goal {
    private final AssistantEntity assistant;
    private final double speed;
    private final float startDistance; // start walking when farther than this
    private final float teleportDistance; // snap to the owner if absurdly far
    private Player owner;
    private int recalcTicks;

    public FollowOwnerGoal(AssistantEntity assistant, double speed, float startDistance, float teleportDistance) {
        this.assistant = assistant;
        this.speed = speed;
        this.startDistance = startDistance;
        this.teleportDistance = teleportDistance;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (assistant.getMode() != AssistantEntity.Mode.FOLLOW) return false;
        Player p = assistant.getOwnerPlayer();
        if (p == null || p.isSpectator()) return false;
        if (assistant.distanceToSqr(p) < startDistance * startDistance) return false;
        this.owner = p;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return assistant.getMode() == AssistantEntity.Mode.FOLLOW
            && owner != null && owner.isAlive()
            && assistant.distanceToSqr(owner) > (startDistance * startDistance) * 0.6;
    }

    @Override
    public void stop() {
        this.owner = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        assistant.getLookControl().setLookAt(owner, 10.0F, assistant.getMaxHeadXRot());
        if (--recalcTicks <= 0) {
            recalcTicks = adjustedTickDelay(10);
            double distSq = assistant.distanceToSqr(owner);
            if (distSq > teleportDistance * teleportDistance) {
                // Way behind (owner sprinted/boated off) — catch up like a pet.
                assistant.teleportTo(owner.getX(), owner.getY(), owner.getZ());
                assistant.getNavigation().stop();
                return;
            }
            assistant.getNavigation().moveTo(owner, speed);
        }
    }
}
