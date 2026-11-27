package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * [pa35] Its crossbow in a fight: the bow's footwork (well back from a creeper, at range from anything else, never off
 * a post), spanning as it backs off (a player can walk while spanning, slowly), then shouldered and loosed through
 * PaCrossbow. Never through a friend. After the fight it spans it again, so it carries a bolt.
 */
final class PaCrossbowGoal extends Goal {

    private final PlayerAssistantEntity a;
    private int rest;

    PaCrossbowGoal(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = a.getTarget();
        ItemStack h = a.getMainHandItem();
        return t != null && t.isAlive() && h.is(Items.CROSSBOW) && a.mayShoot(h) && (CrossbowItem.isCharged(h) || PaCrossbow.hasAmmo(a))
            && a.combatStance() != AssistantEntity.Stance.MELEE;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() || PaCrossbow.busy(a) && a.getTarget() != null;
    }

    @Override
    public void start() {
        rest = 4;
    }

    @Override
    public void stop() {
        a.getNavigation().stop();
        if (a.mind.xbow != null && a.mind.xbow.target != null && !a.mind.xbow.target.isAlive()) PaCrossbow.cancel(a);
    }

    private double range() {
        return PaArchery.crossbowRow(a.veteranLevel()).range() + (a.holdingAPost() ? 12 : 0);
    }

    @Override
    public void tick() {
        LivingEntity t = a.getTarget();
        if (t == null) return;
        double distSq = a.distanceToSqr(t);
        boolean post = a.holdingAPost(), spanning = a.mind.xbow != null && a.mind.xbow.loadedAt > 0;
        double minSq = t instanceof Creeper ? 81.0 : 36.0;
        if (post) {
            a.getNavigation().stop();
        } else if (distSq < minSq) {
            Vec3 away = a.position().subtract(t.position());
            if (away.lengthSqr() > 0.01) {
                away = away.normalize();
                a.getNavigation().moveTo(a.getX() + away.x * 5, a.getY(), a.getZ() + away.z * 5, spanning ? 0.6D : 1.3D);   // spanning: a slow walk, as a player's
            }
        } else if (distSq > Math.min(range() - 4, 24) * Math.min(range() - 4, 24)) {
            if (!a.onWatch()) a.getNavigation().moveTo(t, spanning ? 0.6D : 1.2D);
        } else {
            a.getNavigation().stop();
        }
        if (PaCrossbow.busy(a)) return;
        boolean inRange = distSq <= range() * range() && a.hasLineOfSight(t);
        if (!inRange && CrossbowItem.isCharged(a.getMainHandItem())) return;   // loaded: hold it till there's a shot
        if (--rest > 0) return;
        rest = 6;
        double least = Math.sqrt(minSq), most = range();
        PaCrossbow.atEntity(a, t, null, () -> t.isAlive() && a.hasLineOfSight(t) && PaSidestep.clear(a, t, least, most));   // [pa38] a step aside (PaSidestep)
    }

    /** Every half second: for a ranged fight, the crossbow over the bow once it may use one (and has something to load);
     *  out of a fight, a crossbow in its hand spanned again. */
    static void prefer(PlayerAssistantEntity a) {
        if (a.level().isClientSide) return;
        if (a.getTarget() == null) { PaCrossbow.reload(a); return; }
        if (PaCrossbow.busy(a) || PaBow.drawing(a) || !a.wantsRanged()) return;
        if (a.getMainHandItem().is(Items.BOW) && PaCrossbow.has(a) && (PaCrossbow.hasAmmo(a) || a.countMatching(s -> s.is(Items.CROSSBOW) && CrossbowItem.isCharged(s)) > 0))
            PaCrossbow.toHand(a);
    }
}
