package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
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
        ItemStack hand = assistant.getMainHandItem();
        return t != null && t.isAlive()
            && (hand.is(Items.BOW) || hand.is(Items.CROSSBOW))
            && assistant.hasArrows()
            && assistant.combatStance() != AssistantEntity.Stance.MELEE;
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

        ItemStack hand = assistant.getMainHandItem();
        boolean inRange = distSq <= 225.0 && assistant.hasLineOfSight(t);
        if (hand.is(Items.CROSSBOW)) {
            tickCrossbow(t, hand, inRange);
            return;
        }
        // The draw is real now: nock (a breather between shots), pull for a
        // full second - the pose anyone reads as an arrow coming - then
        // loose. The arrow used to leave the bow with no wind-up at all.
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

    /** The crossbow's rhythm is its own: span it ANYWHERE - out of range, no
     *  line of sight, mid-walk, that being the whole point of a crossbow -
     *  carry it loaded, and loose the moment the shot is there. Vanilla's
     *  loader asks the shooter for ammunition; the bolt is deducted from the
     *  pack here, when the charge actually takes. */
    private void tickCrossbow(LivingEntity t, ItemStack hand, boolean inRange) {
        if (CrossbowItem.isCharged(hand)) {
            if (inRange && --attackTimer <= 0) {
                if (hand.getItem() instanceof CrossbowItem cb) {
                    cb.performShooting(assistant.level(), assistant, InteractionHand.MAIN_HAND,
                        hand, 1.6F, 6.0F, t);
                }
                this.attackTimer = 10;
            }
            return;
        }
        if (!drawing()) {
            assistant.startUsingItem(InteractionHand.MAIN_HAND);
        } else if (assistant.getTicksUsingItem() >= CrossbowItem.getChargeDuration(hand, assistant) + 2) {
            assistant.releaseUsingItem();
            if (CrossbowItem.isCharged(hand)) {
                assistant.consumeArrow();
                this.attackTimer = 5;   // shoulder it and pick the shot
            }
        }
    }
}
