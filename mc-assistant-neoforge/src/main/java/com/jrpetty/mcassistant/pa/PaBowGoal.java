package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * [pa33g] Its bow in a fight: the footwork of the town's archers (well back from a creeper, at bow range from anything
 * else, never off a post), and every arrow drawn and loosed as a player's through PaBow: head and body round onto the
 * target at its level's pace, a full pull (a quick snap up close), the loose where it points. Never through a friend.
 */
final class PaBowGoal extends Goal {

    private final PlayerAssistantEntity a;
    private int rest;

    PaBowGoal(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = a.getTarget();
        return t != null && t.isAlive() && a.getMainHandItem().is(Items.BOW) && a.mayShoot(a.getMainHandItem()) && a.hasArrows()
            && a.combatStance() != AssistantEntity.Stance.MELEE;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        rest = 6;
    }

    @Override
    public void stop() {
        a.getNavigation().stop();
        PaBow.Draw d = a.mind.bow;
        if (d != null && d.target() instanceof LivingEntity && d.target() != null && !d.target().isAlive()) PaBow.cancel(a);
    }

    private double range() {
        return PaLevels.range(a.veteranLevel()) + (a.holdingAPost() ? 12 : 0);
    }

    @Override
    public void tick() {
        LivingEntity t = a.getTarget();
        if (t == null) return;
        double distSq = a.distanceToSqr(t);
        boolean post = a.holdingAPost();
        double minSq = t instanceof Creeper ? 81.0 : 36.0;
        if (post) {
            a.getNavigation().stop();
        } else if (distSq < minSq) {
            Vec3 away = a.position().subtract(t.position());
            if (away.lengthSqr() > 0.01) {
                away = away.normalize();
                a.getNavigation().moveTo(a.getX() + away.x * 5, a.getY(), a.getZ() + away.z * 5, 1.3D);
            }
        } else if (distSq > Math.min(range() - 4, 20) * Math.min(range() - 4, 20)) {
            if (!a.onWatch()) a.getNavigation().moveTo(t, 1.2D);
        } else {
            a.getNavigation().stop();                                    // stand to shoot: a walking archer is a worse one
        }
        if (PaBow.drawing(a)) return;
        boolean inRange = distSq <= range() * range() && a.hasLineOfSight(t);
        if (!inRange || --rest > 0) return;
        double least = Math.sqrt(minSq), most = range();
        PaBow.atEntity(a, t, shot -> rest = 8, null,
            () -> t.isAlive() && a.hasLineOfSight(t) && PaSidestep.clear(a, t, least, most));   // [pa38] its player in the way: a step aside (PaSidestep)
    }
}
