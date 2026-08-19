package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Ranged combat: when the assistant is holding a bow and has arrows, it
 * fights from distance — kiting creepers (never closer than ~9 blocks) and
 * plinking anything else from range. Runs at higher priority than melee, so
 * holding a bow means shooting; no bow means the melee goal takes over.
 */
public class BowAttackGoal extends Goal {

    private final AssistantEntity assistant;
    private int attackTimer;

    public BowAttackGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = assistant.getTarget();
        return t != null && t.isAlive()
            && assistant.getMainHandItem().is(Items.BOW)
            && assistant.hasArrows();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.attackTimer = 10;
    }

    @Override
    public void stop() {
        assistant.getNavigation().stop();
        if (drawing()) assistant.stopUsingItem();
    }

    /** Mid-draw on the bow hand — never confuse this with a raised shield. */
    private boolean drawing() {
        return assistant.isUsingItem()
            && assistant.getUsedItemHand() == InteractionHand.MAIN_HAND;
    }

    @Override
    public void tick() {
        LivingEntity t = assistant.getTarget();
        if (t == null) return;
        assistant.getLookControl().setLookAt(t, 30.0F, 30.0F);

        double distSq = assistant.distanceToSqr(t);
        // Keep creepers well outside their fuse range; other mobs at bow range.
        double minSq = t instanceof Creeper ? 81.0 : 36.0;
        if (distSq < minSq) {
            Vec3 away = assistant.position().subtract(t.position());
            if (away.lengthSqr() > 0.01) {
                away = away.normalize();
                assistant.getNavigation().moveTo(
                    assistant.getX() + away.x * 5, assistant.getY(), assistant.getZ() + away.z * 5, 1.3D);
            }
        } else if (distSq > 196.0) {
            assistant.getNavigation().moveTo(t, 1.2D);
        } else {
            assistant.getNavigation().stop();
        }

        // The draw is real now: nock (a breather between shots), pull for a
        // full second - the pose anyone reads as an arrow coming - then
        // loose. The arrow used to leave the bow with no wind-up at all.
        boolean inRange = distSq <= 225.0 && assistant.hasLineOfSight(t);
        if (!inRange) {
            if (drawing()) assistant.stopUsingItem();   // hold the arrow, keep moving
            return;
        }
        if (!drawing()) {
            if (--attackTimer <= 0) assistant.startUsingItem(InteractionHand.MAIN_HAND);
        } else if (assistant.getTicksUsingItem() >= 20) {
            assistant.stopUsingItem();
            assistant.performRangedAttack(t, 1.6F);
            this.attackTimer = 10;   // nock the next one
        }
    }
}
