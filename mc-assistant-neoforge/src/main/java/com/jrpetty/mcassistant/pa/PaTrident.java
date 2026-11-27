package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * [pa35] The trident from level 7 (the gear ladder), thrown as a player throws one: raised overhead for the game's ten
 * ticks, aimed with the bow's eyes (PaArchery: its level's row, a trident's 2.5 a tick), let go where it points, and,
 * with Loyalty, back to its hand (it catches it). Without Loyalty it fights with it in hand rather than throw it away.
 * Only when it has no bow or crossbow with something to shoot: the trident is the reach of last resort.
 */
final class PaTrident extends Goal {

    /** The game's trident: its throw speed, and the ticks raised before it may be thrown. */
    static final double SPEED = 2.5;
    static final int RAISE = 10;

    private final PlayerAssistantEntity a;
    @Nullable private PaArchery.Shooter shooter;
    private int raisedAt, rest;

    PaTrident(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    static boolean loyal(PlayerAssistantEntity a, ItemStack s) {
        return a.level() instanceof ServerLevel sl && s.is(Items.TRIDENT) && EnchantmentHelper.getTridentReturnToOwnerAcceleration(sl, s, a) > 0;
    }

    /** A Loyalty trident it may use, in hand or pack. */
    static int slot(PlayerAssistantEntity a) {
        if (loyal(a, a.getMainHandItem()) && PaLevels.canUse(a, a.getMainHandItem())) return -1;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) if (loyal(a, inv.get(i)) && PaLevels.canUse(a, inv.get(i))) return i;
        return -2;
    }

    @Override
    public boolean canUse() {
        LivingEntity t = a.getTarget();
        if (t == null || !t.isAlive() || a.mind.trident != null || a.combatStance() == AssistantEntity.Stance.MELEE) return false;
        if (a.hasBow() && (a.hasArrows() || PaCrossbow.hasAmmo(a))) return false;       // the bow or crossbow first
        if (PaBow.drawing(a) || PaCrossbow.busy(a)) return false;
        double d = a.distanceToSqr(t);
        return d > 5 * 5 && d < 24 * 24 && a.hasLineOfSight(t) && slot(a) > -2;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity t = a.getTarget();
        return t != null && t.isAlive() && a.mind.trident == null && a.getMainHandItem().is(Items.TRIDENT) && (shooter != null || canUse());
    }

    @Override
    public void start() {
        int s = slot(a);
        if (s >= 0) {
            ItemStack held = a.getMainHandItem().copy();
            a.setItemSlot(EquipmentSlot.MAINHAND, a.getInventoryItems().get(s).copy());
            a.getInventoryItems().set(s, held);
        }
        shooter = null;
        rest = 4;
        a.getNavigation().stop();
    }

    @Override
    public void stop() {
        shooter = null;
        if (a.isUsingItem() && a.getUseItem().is(Items.TRIDENT)) a.stopUsingItem();
    }

    @Override
    public void tick() {
        LivingEntity t = a.getTarget();
        if (t == null) return;
        if (shooter == null) {
            if (--rest > 0) return;
            if (a.isUsingItem()) { a.stopUsingItem(); a.mind.blocking = false; }
            a.startUsingItem(InteractionHand.MAIN_HAND);                     // raised overhead (everyone sees it)
            raisedAt = a.tickCount;
            shooter = new PaArchery.Shooter(PaArchery.row(a.veteranLevel()), new java.util.Random(a.getRandom().nextLong()), a.getYHeadRot(), a.getXRot(),
                a.distanceTo(t), SPEED);
            return;
        }
        Vec3 eye = a.getEyePosition(), at = PaBow.middle(t);
        shooter.ticks = Math.max(0, a.tickCount - raisedAt - RAISE + shooter.plannedDraw);   // its aim settles from the raise
        shooter.see(eye.x, eye.y, eye.z, at.x, at.y, at.z);
        PaBow.face(a, shooter.yaw, shooter.pitch);
        if (a.tickCount - raisedAt < RAISE || !shooter.ready()) return;
        if (!com.jrpetty.mcassistant.entity.FolkCombat.clearShot(a, t)) return;
        throwIt(a, shooter);
        shooter = null;
    }

    static void throwIt(PlayerAssistantEntity a, PaArchery.Shooter s) {
        ItemStack held = a.getMainHandItem();
        a.stopUsingItem();
        held.hurtAndBreak(1, a, EquipmentSlot.MAINHAND);
        if (held.isEmpty()) return;
        ThrownTrident tt = new ThrownTrident(a.level(), a, held.copy());
        double[] v = s.loose(a.getDeltaMovement().horizontalDistance());
        tt.setDeltaMovement(v[0], v[1], v[2]);
        Vec3 vel = new Vec3(v[0], v[1], v[2]);
        tt.setYRot((float) (Math.atan2(vel.x, vel.z) * 180.0 / Math.PI));
        tt.setXRot((float) (Math.atan2(vel.y, vel.horizontalDistance()) * 180.0 / Math.PI));
        tt.pickup = AbstractArrow.Pickup.ALLOWED;
        tt.getPersistentData().putUUID("pa_shooter", a.getUUID());
        a.level().addFreshEntity(tt);
        a.level().playSound(null, tt, SoundEvents.TRIDENT_THROW.value(), a.getSoundSource(), 1.0F, 1.0F);
        a.mind.trident = new Out(tt.getUUID(), a.getMainHandItem().copy(), a.tickCount);
        a.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        a.mind.tridentThrows++;
    }

    /** A trident in the air: which, what it was, when thrown. */
    record Out(java.util.UUID id, ItemStack stack, int at) {}

    /** Every tick: a Loyalty trident come home is caught (into its hand, or its pack if its hand is full). */
    static void tick(PlayerAssistantEntity a) {
        Out o = a.mind.trident;
        if (o == null || !(a.level() instanceof ServerLevel sl)) return;
        Entity e = sl.getEntity(o.id());
        boolean back = e instanceof ThrownTrident tt && tt.isNoPhysics() && a.tickCount - o.at() > 5 && tt.distanceToSqr(a.getEyePosition()) < 2.5 * 2.5;   // coming home, not just let go
        if (!back && e != null && a.tickCount - o.at() < 400) return;
        if (e != null) e.discard();
        a.mind.trident = null;
        ItemStack s = e instanceof ThrownTrident tt ? tt.getPickupItemStackOrigin().copy() : o.stack();
        if (s.isEmpty()) return;
        if (a.getMainHandItem().isEmpty()) a.setItemSlot(EquipmentSlot.MAINHAND, s);
        else { ItemStack left = a.insertItem(s); if (!left.isEmpty()) a.spawnAtLocation(left); }
        if (back) a.mind.tridentCatches++;
    }
}
