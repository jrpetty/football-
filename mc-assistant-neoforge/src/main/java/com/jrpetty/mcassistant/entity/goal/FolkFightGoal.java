package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.FolkCombat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * [war] A folk's fight with a blade (FolkCombat): in to strike when its cooldown is up (a jump first for a crit when
 * it has the room and the knack), out of reach and circling while it recharges, the shield up when the other winds
 * up, the axe for a raised shield; a golden apple or a potion when badly hurt and out of the line. The bow's fight is
 * BowAttackGoal's, which runs before this one while there is a bow in hand.
 */
public class FolkFightGoal extends Goal {

    private final AssistantEntity a;
    private int pathAt, flipAt, jumpedAt = -100;

    public FolkFightGoal(AssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = a.getTarget();
        return t != null && t.isAlive() && !a.wantsRanged() && a.distanceToSqr(t) < 32 * 32;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        a.getNavigation().stop();
        a.getMoveControl().strafe(0.0F, 0.0F);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity t = a.getTarget();
        if (t == null) return;
        long now = a.level().getGameTime();
        a.getLookControl().setLookAt(t, 30.0F, 30.0F);
        if (a.tickCount % 10 == 0 && FolkCombat.healNow(a)) return;
        if (a.tickCount % 5 == 0) FolkCombat.chooseBladeNow(a, t, now);
        double d2 = a.distanceToSqr(t);
        boolean reach = a.isWithinMeleeAttackRange(t);
        boolean ready = FolkCombat.readyNow(a, now);
        double skill = FolkCombat.skill(a);
        // Its shield up as the other winds up to strike: read from the other's rhythm (a practised eye sees its blow
        // coming), else from the swing itself; a moment's reaction, quicker for the practised.
        int coming = FolkCombat.readyIn(t, now);
        boolean windUp = coming <= 2 + (int) Math.round(skill * 3) || coming == Integer.MAX_VALUE && (t.swinging || t instanceof Mob m && m.isAggressive() && d2 < 6);
        if (!ready && d2 < 12 && windUp && a.getRandom().nextDouble() < 0.25 + skill * 0.7) FolkCombat.raiseNow(a, 6);
        if (ready) {
            if (reach) {
                if (FolkCombat.critComing(a, now)) {
                    if (!a.onGround() && a.getDeltaMovement().y < 0) FolkCombat.strike(a, t);
                    return;
                }
                if (d2 < 3.0 && a.tickCount - jumpedAt > 30 && a.getRandom().nextDouble() < skill * 0.5 && FolkCombat.jumpNow(a, now)) {
                    jumpedAt = a.tickCount;
                    return;
                }
                a.getNavigation().stop();
                FolkCombat.strike(a, t);
                return;
            }
            if (a.tickCount >= pathAt || a.getNavigation().isDone()) {
                a.getNavigation().moveTo(t, d2 > 16 ? 1.25 : 1.1);
                a.setSprinting(d2 > 12 && d2 < 64);
                pathAt = a.tickCount + 8;
            }
            return;
        }
        // Recharging: at arm's length, circling, never stood face to face trading blows.
        a.getNavigation().stop();
        if (a.tickCount >= flipAt) {
            flipAt = a.tickCount + 30 + a.getRandom().nextInt(30);
            FolkCombat.flip(a);
        }
        float side = 0.45F * FolkCombat.strafeSide(a);
        if (d2 < 3.0) a.getMoveControl().strafe(-0.5F, side);
        else if (d2 > 12.0) a.getMoveControl().strafe(0.4F, side);
        else a.getMoveControl().strafe(0.0F, side);
    }
}
