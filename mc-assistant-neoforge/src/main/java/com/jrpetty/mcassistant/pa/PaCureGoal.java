package com.jrpetty.mcassistant.pa;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * [pa38] Curing a zombie villager when asked, from level 7: as a player does it. A splash potion of weakness thrown at
 * it (unless it has weakness already), then a golden apple given; the game's own cure takes its three to five minutes,
 * and the villager it becomes gives its player the game's discount for curing it. It won't hit the zombie villager
 * while it's at it, and it says what it's missing if it can't.
 */
final class PaCureGoal extends Goal {

    private final PlayerAssistantEntity a;
    private int thrownAt = -1000, startAt;

    PaCureGoal(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private ZombieVillager zv() {
        PaField f = a.mind.field;
        if (f.cureOrder == null || a.tickCount > f.cureUntil || !(a.level() instanceof ServerLevel sl)) return null;
        Entity e = sl.getEntity(f.cureOrder);
        return e instanceof ZombieVillager z && z.isAlive() ? z : null;
    }

    @Override
    public boolean canUse() {
        if (a.level().isClientSide || a.mind.field.cureOrder == null) return false;
        if (zv() == null) { a.mind.field.cureOrder = null; return false; }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return zv() != null && a.tickCount - startAt < 600;
    }

    @Override
    public void start() {
        startAt = a.tickCount;
        thrownAt = -1000;
        if (a.getTarget() instanceof ZombieVillager) a.setTarget(null);
    }

    @Override
    public void stop() {
        a.getNavigation().stop();
        if (zv() == null) a.mind.field.cureOrder = null;
    }

    @Override
    public void tick() {
        ZombieVillager z = zv();
        PaField f = a.mind.field;
        if (z == null) return;
        if (z.isConverting()) { f.cureOrder = null; return; }
        a.getLookControl().setLookAt(z, 30.0F, 30.0F);
        double d = a.distanceTo(z);
        boolean weak = z.hasEffect(MobEffects.WEAKNESS);
        if (!weak) {
            if (a.tickCount - thrownAt < 40) return;                               // the splash is on its way
            ItemStack pot = PaTrade.weakness(a);
            if (pot.isEmpty()) { done(PaLogiTalk.line(a, "field.cure_need", "what", "a splash potion of weakness")); return; }
            if (d > 6 || !a.hasLineOfSight(z)) { if (a.getNavigation().isDone()) a.getNavigation().moveTo(z, 1.0); return; }
            a.getNavigation().stop();
            ThrownPotion tp = new ThrownPotion(a.level(), a);
            tp.setItem(pot.copyWithCount(1));
            pot.shrink(1);
            Vec3 to = z.position().add(0, z.getBbHeight() * 0.5, 0).subtract(a.getEyePosition());
            Vec3 v = to.normalize().scale(0.5).add(0, 0.12 + to.horizontalDistance() * 0.02, 0);
            tp.setDeltaMovement(v);
            a.level().addFreshEntity(tp);
            a.swing(InteractionHand.MAIN_HAND);
            a.level().playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F / (a.getRandom().nextFloat() * 0.4F + 0.8F));
            thrownAt = a.tickCount;
            return;
        }
        if (a.countMatching(s -> s.is(Items.GOLDEN_APPLE)) < 1) { done(PaLogiTalk.line(a, "field.cure_need", "what", "a golden apple")); return; }
        if (d > 2.5) { if (a.getNavigation().isDone()) a.getNavigation().moveTo(z, 1.0); return; }
        a.getNavigation().stop();
        a.removeMatching(s -> s.is(Items.GOLDEN_APPLE), 1);
        a.swing(InteractionHand.MAIN_HAND);
        if (PaTrade.startCure(a, z)) {
            f.cures++;
            a.earn("cure");
            done(PaLogiTalk.line(a, "field.cure_started"));
        } else done(PaLogiTalk.line(a, "field.cure_need", "what", "weakness on it first"));
    }

    private void done(String line) {
        PaTalk.reply(a, line);
        a.mind.field.cureOrder = null;
    }
}
