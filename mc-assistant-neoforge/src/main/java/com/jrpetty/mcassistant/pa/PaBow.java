package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * [pa33g] Every arrow it looses, drawn as a player draws: the bow into its hand, an arrow nocked, the bow pulled (used
 * as the item is, so everyone sees its arms up and the bow bending through its stages), its head and body brought round
 * onto the target no faster than its level allows, and the arrow let go where it is pointing, shaken by its hands
 * (PaArchery: the one table and the arithmetic). Then a real arrow under the game's drag and gravity. Trick shots, the
 * shooting contest, a fight and the End all come through here.
 */
public final class PaBow {

    private PaBow() {}

    /** A shot let go: the arrow, whether it will land on the block it was meant for (blocks only), its flight, the distance. */
    public record Shot(Arrow arrow, boolean willHit, int flightTicks, double dist) {}

    /** One draw under way. */
    public static final class Draw {
        @Nullable final Entity target;
        @Nullable final BlockPos block;
        final Vec3 spot;
        PaArchery.Shooter shooter;
        final int pullAt;
        final int began;
        boolean pulling;
        @Nullable Consumer<Arrow> prep;
        @Nullable Consumer<Shot> done;
        @Nullable BooleanSupplier clear;

        Draw(@Nullable Entity target, @Nullable BlockPos block, Vec3 spot, int pullAt, int began) {
            this.target = target;
            this.block = block;
            this.spot = spot;
            this.pullAt = pullAt;
            this.began = began;
        }

        @Nullable public Entity target() { return target; }
    }

    public static boolean drawing(PlayerAssistantEntity a) {
        return a.mind.bow != null;
    }

    /** At a block (a target, a bell, a ping): aimed at its middle. */
    public static boolean atBlock(PlayerAssistantEntity a, BlockPos at, @Nullable Consumer<Shot> done) {
        return begin(a, null, at, Vec3.atCenterOf(at), done, null, null);
    }

    /** At something (a mob, a pig in a cart, a crystal, the dragon): it tracks it all the way. */
    public static boolean atEntity(PlayerAssistantEntity a, Entity e, @Nullable Consumer<Shot> done, @Nullable Consumer<Arrow> prep,
                                   @Nullable BooleanSupplier clear) {
        return begin(a, e, null, middle(e), done, prep, clear);
    }

    static Vec3 middle(Entity e) {
        return e.getBoundingBox().getCenter();
    }

    /** A draw begun: false with no bow or no arrow. The bow goes into its hand first (a moment before the pull, so it is seen). */
    static boolean begin(PlayerAssistantEntity a, @Nullable Entity e, @Nullable BlockPos block, Vec3 spot, @Nullable Consumer<Shot> done,
                         @Nullable Consumer<Arrow> prep, @Nullable BooleanSupplier clear) {
        if (a.mind.bow != null || a.mind.xbow != null || !PaShots.hasBow(a) || a.countMatching(s -> s.is(Items.ARROW)) < 1) return false;
        boolean inHand = a.getMainHandItem().is(Items.BOW);
        if (!inHand) PaShots.drawBow(a);
        if (a.isUsingItem()) { a.stopUsingItem(); a.mind.blocking = false; }   // the shield down: two hands on the bow
        Draw d = new Draw(e, block, spot, a.tickCount + (inHand ? PaArchery.NOCK : PaArchery.NOCK + 4), a.tickCount);
        d.done = done;
        d.prep = prep;
        d.clear = clear;
        double dist = a.getEyePosition().distanceTo(spot);
        d.shooter = new PaArchery.Shooter(a.veteranLevel(), new java.util.Random(a.getRandom().nextLong()), a.getYHeadRot(), a.getXRot(), dist);
        a.mind.bow = d;
        a.mind.aimEntity = e;
        a.mind.aimSpot = spot;
        a.mind.aimUntil = a.tickCount + 200;
        return true;
    }

    /** The draw let go of without a shot (the target gone, the bow taken away). */
    public static void cancel(PlayerAssistantEntity a) {
        Draw d = a.mind.bow;
        if (d == null) return;
        a.mind.bow = null;
        a.mind.aimUntil = a.tickCount + 4;
        if (a.isUsingItem() && a.getUseItem().is(Items.BOW)) a.stopUsingItem();
    }

    /** Every tick, on the server, after its goals and look control: its head where its aim is, the pull, the loose. */
    static void tick(PlayerAssistantEntity a) {
        Draw d = a.mind.bow;
        if (d == null) return;
        if (!a.isAlive() || a.isSleeping() || a.isFallFlying() || (d.target != null && !d.target.isAlive() && !d.pulling)
            || (d.target != null && d.target.level() != a.level()) || a.tickCount - d.began > 220) { cancel(a); return; }
        if (d.target != null && !d.target.isAlive()) { cancel(a); return; }
        if (!a.getMainHandItem().is(Items.BOW) && a.tickCount >= d.pullAt) { cancel(a); return; }
        Vec3 eye = a.getEyePosition();
        Vec3 at = d.target != null ? middle(d.target) : d.spot;
        PaArchery.Shooter s = d.shooter;
        if (a.tickCount >= d.pullAt && !d.pulling) {
            a.startUsingItem(InteractionHand.MAIN_HAND);                 // the pull: the bow bends, both arms up (seen by all)
            d.pulling = true;
        }
        if (d.pulling && !(a.isUsingItem() && a.getUseItem().is(Items.BOW))) { cancel(a); return; }   // something else took its hands
        s.ticks = d.pulling ? a.getTicksUsingItem() : 0;
        s.see(eye.x, eye.y, eye.z, at.x, at.y, at.z);
        face(a, s.yaw, s.pitch);
        if (!d.pulling || !s.ready()) return;
        if (d.clear != null && !d.clear.getAsBoolean()) return;          // a friend in the way: held, not loosed
        loose(a, d);
    }

    /** Its head, body and eyes where its aim is (and its look control told the same, so nothing pulls them away). */
    static void face(PlayerAssistantEntity a, double yaw, double pitch) {
        float y = (float) yaw, p = (float) pitch;
        a.setYRot(y);
        a.yBodyRot = y;
        a.setYHeadRot(y);
        a.setXRot(p);
        double[] dir = PaArchery.dir(yaw, pitch);
        Vec3 eye = a.getEyePosition();
        a.getLookControl().setLookAt(eye.x + dir[0] * 16, eye.y + dir[1] * 16, eye.z + dir[2] * 16, 360.0F, 180.0F);
    }

    static void loose(PlayerAssistantEntity a, Draw d) {
        a.mind.bow = null;
        if (a.removeMatching(s -> s.is(Items.ARROW), 1) < 1) { a.stopUsingItem(); return; }
        PaArchery.Shooter s = d.shooter;
        int pulled = s.ticks;
        Vec3 moving = a.getDeltaMovement();
        double own = Math.sqrt(moving.x * moving.x + moving.z * moving.z);
        double[] v = s.loose(own);
        ItemStack bow = a.getMainHandItem();
        Arrow arrow = new Arrow(a.level(), a, new ItemStack(Items.ARROW), bow.copy());
        arrow.setPos(a.getX(), a.getEyeY() - PaArchery.EYE_DROP, a.getZ());
        Vec3 vel = new Vec3(v[0], v[1], v[2]);
        arrow.setDeltaMovement(vel);                                     // no extra randomness: what it loosed is what flies
        double horiz = vel.horizontalDistance();
        arrow.setYRot((float) (Math.atan2(vel.x, vel.z) * 180.0 / Math.PI));
        arrow.setXRot((float) (Math.atan2(vel.y, horiz) * 180.0 / Math.PI));
        arrow.yRotO = arrow.getYRot();
        arrow.xRotO = arrow.getXRot();
        double power = PaArchery.power(pulled);
        arrow.setCritArrow(power >= 1.0);
        arrow.pickup = AbstractArrow.Pickup.ALLOWED;
        arrow.getPersistentData().putUUID("pa_shooter", a.getUUID());   // its own record counts the hits
        if (d.block != null) arrow.getPersistentData().putLong("pa_block", d.block.asLong());
        if (d.prep != null) d.prep.accept(arrow);
        boolean willHit = d.block != null && PaShots.flies(a, arrow.position(), vel, d.block);
        double dist = a.getEyePosition().distanceTo(d.target != null ? middle(d.target) : d.spot);
        int fly = PaArchery.flightTicks(vel.length(), dist);
        a.stopUsingItem();
        a.level().addFreshEntity(arrow);
        a.playSound(net.minecraft.sounds.SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (a.getRandom().nextFloat() * 0.4F + 1.2F) + (float) power * 0.5F);
        if (bow.is(Items.BOW)) bow.hurtAndBreak(1, a, EquipmentSlot.MAINHAND);
        a.mind.aimUntil = a.tickCount + 10;                              // the follow-through: eyes stay on it a moment
        PaData.Rec r = a.rec();
        if (r != null) { r.shots++; PaData.touch(); }
        if (d.done != null) d.done.accept(new Shot(arrow, willHit, fly, dist));
    }

    /** From the server's projectile event: one of its arrows struck something. A hit on what it meant counts on its record. */
    public static void impact(AbstractArrow arrow, net.minecraft.world.phys.HitResult hit) {
        var tag = arrow.getPersistentData();
        if (!tag.hasUUID("pa_shooter") || tag.getBoolean("pa_counted")) return;
        tag.putBoolean("pa_counted", true);
        boolean good;
        if (hit instanceof net.minecraft.world.phys.EntityHitResult eh) good = !(eh.getEntity() instanceof net.minecraft.world.entity.player.Player);
        else if (hit instanceof net.minecraft.world.phys.BlockHitResult bh) good = tag.contains("pa_block") && bh.getBlockPos().asLong() == tag.getLong("pa_block");
        else good = false;
        if (!good || !(arrow.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        if (sl.getEntity(tag.getUUID("pa_shooter")) instanceof PlayerAssistantEntity a) {
            PaData.Rec r = a.rec();
            if (r != null) { r.hits++; PaData.touch(); }
        }
    }
}
