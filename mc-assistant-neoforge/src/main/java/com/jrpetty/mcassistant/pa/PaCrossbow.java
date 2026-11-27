package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * [pa35] Its crossbow, worked as a player works one. Spanning it takes the game's own time (25 ticks, 5 fewer for each
 * level of Quick Charge), with the crossbow held across its body (the charging pose everyone sees) and the loading
 * clicks; then a bolt sits in it (the arrow shows in the crossbow, and it carries it loaded between fights, held up in
 * the ready pose). To shoot, it shoulders it and finds the mark (PaArchery's crossbow row: steadier than a bowstring,
 * the same eyes), and the game's own crossbow lets fly where it points: Multishot's three bolts, Piercing through a
 * line of mobs, a firework rocket from level 8 (only a rocket with a star in it: a plain one does no harm). Fights,
 * the crossbow contest and its tests all come through here.
 */
public final class PaCrossbow {

    private PaCrossbow() {}

    /** One shot asked for: what at, and what to do after. It spans the crossbow first if it must. */
    public static final class Job {
        @Nullable final Entity target;
        @Nullable final BlockPos block;
        final Vec3 spot;
        final int began;
        @Nullable final Consumer<PaBow.Shot> done;
        @Nullable final BooleanSupplier clear;
        /** Spanning: when it will be spanned (0: not spanning); with a rocket rather than a bolt. */
        int loadedAt;
        boolean rocket;
        /** Only to span it (after a fight, as a player reloads): no shot. */
        boolean spanOnly;
        /** Shouldered and aiming (null till spanned). */
        @Nullable PaArchery.Shooter shooter;
        int aimFrom;

        Job(@Nullable Entity target, @Nullable BlockPos block, Vec3 spot, int began, @Nullable Consumer<PaBow.Shot> done, @Nullable BooleanSupplier clear) {
            this.target = target;
            this.block = block;
            this.spot = spot;
            this.began = began;
            this.done = done;
            this.clear = clear;
        }
    }

    // ------------------------------------------------------------------ what it has

    /** A crossbow in its hand or pack it may use (the gear ladder: level 5). */
    public static boolean has(PlayerAssistantEntity a) {
        return usable(a.getMainHandItem()) && PaLevels.canUse(a, a.getMainHandItem()) || a.countMatching(s -> usable(s) && PaLevels.canUse(a, s)) > 0;
    }

    static boolean usable(ItemStack s) {
        return s.is(Items.CROSSBOW);
    }

    /** A firework rocket with a star in it (a plain one only flies, and does no harm). */
    static boolean harmfulRocket(ItemStack s) {
        if (!s.is(Items.FIREWORK_ROCKET)) return false;
        Fireworks f = s.get(DataComponents.FIREWORKS);
        return f != null && !f.explosions().isEmpty();
    }

    /** May it load rockets (the gear ladder: fireworks at 8), and has it one worth loading? */
    static boolean rockets(PlayerAssistantEntity a) {
        return PaLevels.canUse(a, new ItemStack(Items.FIREWORK_ROCKET)) && a.countMatching(PaCrossbow::harmfulRocket) > 0;
    }

    /** Something to load: arrows, or rockets when it may. */
    public static boolean hasAmmo(PlayerAssistantEntity a) {
        return a.hasArrows() || rockets(a);
    }

    /** A rocket for this one? A crowd round it (three or more) or something big (forty health or more), and it has one. */
    static boolean rocketFor(PlayerAssistantEntity a, @Nullable Entity target) {
        if (!rockets(a)) return false;
        if (!a.hasArrows()) return true;
        if (!(target instanceof LivingEntity t)) return false;
        if (t.getMaxHealth() >= 40) return true;
        return a.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, t.getBoundingBox().inflate(3), Entity::isAlive).size() >= 3;
    }

    public static boolean busy(PlayerAssistantEntity a) {
        return a.mind.xbow != null;
    }

    /** Spanned and loaded? */
    public static boolean charged(PlayerAssistantEntity a) {
        return usable(a.getMainHandItem()) && CrossbowItem.isCharged(a.getMainHandItem());
    }

    /** The crossbow into its hand (a moment before anything else, so it is seen), the old item to its pack. */
    static boolean toHand(PlayerAssistantEntity a) {
        if (usable(a.getMainHandItem())) return true;
        var inv = a.getInventoryItems();
        int best = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!usable(s) || !PaLevels.canUse(a, s)) continue;
            if (best < 0 || CrossbowItem.isCharged(s) && !CrossbowItem.isCharged(inv.get(best))) best = i;   // a loaded one first
        }
        if (best < 0) return false;
        ItemStack held = a.getMainHandItem().copy();
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, inv.get(best).copy());
        inv.set(best, held);
        return true;
    }

    // ------------------------------------------------------------------ asking for a shot

    public static boolean atBlock(PlayerAssistantEntity a, BlockPos at, @Nullable Consumer<PaBow.Shot> done) {
        return begin(a, null, at, Vec3.atCenterOf(at), done, null);
    }

    public static boolean atEntity(PlayerAssistantEntity a, Entity e, @Nullable Consumer<PaBow.Shot> done, @Nullable BooleanSupplier clear) {
        return begin(a, e, null, PaBow.middle(e), done, clear);
    }

    static boolean begin(PlayerAssistantEntity a, @Nullable Entity e, @Nullable BlockPos block, Vec3 spot, @Nullable Consumer<PaBow.Shot> done,
                         @Nullable BooleanSupplier clear) {
        if (a.mind.xbow != null || a.mind.bow != null || !has(a)) return false;
        boolean loaded = toHand(a) && CrossbowItem.isCharged(a.getMainHandItem());
        if (!loaded && !hasAmmo(a)) return false;
        if (a.isUsingItem()) { a.stopUsingItem(); a.mind.blocking = false; }      // the shield down: two hands on the crossbow
        Job j = new Job(e, block, spot, a.tickCount, done, clear);
        a.mind.xbow = j;
        a.mind.aimEntity = e;
        a.mind.aimSpot = spot;
        a.mind.aimUntil = a.tickCount + 200;
        if (!loaded) span(a, j);
        else shoulder(a, j);
        return true;
    }

    /** Spanning begins: the crossbow across its body, the game's loading sounds as it goes. */
    static void span(PlayerAssistantEntity a, Job j) {
        ItemStack cb = a.getMainHandItem();
        j.rocket = rocketFor(a, j.target);
        j.loadedAt = a.tickCount + CrossbowItem.getChargeDuration(cb, a);
        a.startUsingItem(InteractionHand.MAIN_HAND);
    }

    /** Shouldered: from here it aims (from where its head points now). */
    static void shoulder(PlayerAssistantEntity a, Job j) {
        double dist = a.getEyePosition().distanceTo(j.target != null ? PaBow.middle(j.target) : j.spot);
        boolean rocket = chargedWithRocket(a.getMainHandItem());
        j.shooter = new PaArchery.Shooter(PaArchery.crossbowRow(a.veteranLevel()), new java.util.Random(a.getRandom().nextLong()), a.getYHeadRot(), a.getXRot(),
            dist, rocket ? PaArchery.ROCKET_SPEED : PaArchery.BOLT_SPEED);
        j.aimFrom = a.tickCount;
        j.loadedAt = 0;
    }

    static boolean chargedWithRocket(ItemStack cb) {
        ChargedProjectiles c = cb.get(DataComponents.CHARGED_PROJECTILES);
        return c != null && c.contains(Items.FIREWORK_ROCKET);
    }

    /** The shot let go of (the target gone, the crossbow taken away). A crossbow already spanned stays loaded. */
    public static void cancel(PlayerAssistantEntity a) {
        if (a.mind.xbow == null) return;
        a.mind.xbow = null;
        a.mind.aimUntil = a.tickCount + 4;
        if (a.isUsingItem() && usable(a.getUseItem())) a.stopUsingItem();
    }

    /** After a fight, as a player does: the crossbow in its hand spanned again, so it carries a bolt ready. */
    static void reload(PlayerAssistantEntity a) {
        ItemStack cb = a.getMainHandItem();
        if (a.mind.xbow != null || a.mind.bow != null || !usable(cb) || CrossbowItem.isCharged(cb) || !PaLevels.canUse(a, cb) || !a.hasArrows()
            || a.isUsingItem() || a.getTarget() != null) return;
        Job j = new Job(null, null, a.getEyePosition(), a.tickCount, null, null);
        j.spanOnly = true;
        a.mind.xbow = j;
        span(a, j);
        j.rocket = false;
    }

    // ------------------------------------------------------------------ every tick

    static void tick(PlayerAssistantEntity a) {
        Job j = a.mind.xbow;
        if (j == null) return;
        if (j.spanOnly && a.getTarget() != null) { cancel(a); return; }   // a fight: the reload gives way to it
        if (!a.isAlive() || a.isSleeping() || a.isFallFlying() || a.tickCount - j.began > 260
            || j.target != null && (!j.target.isAlive() || j.target.level() != a.level())) { cancel(a); return; }
        ItemStack cb = a.getMainHandItem();
        if (!usable(cb)) { cancel(a); return; }
        Vec3 eye = a.getEyePosition();
        Vec3 at = j.target != null ? PaBow.middle(j.target) : j.spot;
        if (j.loadedAt > 0) {                                             // spanning
            if (!(a.isUsingItem() && usable(a.getUseItem()))) { cancel(a); return; }   // something else took its hands
            if (!j.spanOnly) PaShots.holdAim(a, j.target, at);
            if (a.tickCount < j.loadedAt) return;
            if (!load(a, cb, j.rocket)) { cancel(a); return; }
            a.stopUsingItem();
            if (j.spanOnly) { a.mind.xbow = null; return; }
            shoulder(a, j);
            return;
        }
        PaArchery.Shooter s = j.shooter;
        if (s == null) { shoulder(a, j); s = j.shooter; }
        s.ticks = a.tickCount - j.aimFrom;
        s.see(eye.x, eye.y, eye.z, at.x, at.y, at.z);
        PaBow.face(a, s.yaw, s.pitch);
        if (!s.ready()) return;
        if (j.clear != null && !j.clear.getAsBoolean()) return;           // a friend in the way: held, not loosed
        fire(a, j);
    }

    /** The bolt in: one arrow (or rocket) from its pack, Multishot's two extra copies as the game makes them (not to pick up). */
    static boolean load(PlayerAssistantEntity a, ItemStack cb, boolean rocket) {
        if (!(a.level() instanceof ServerLevel sl)) return false;
        ItemStack ammo = ItemStack.EMPTY;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size() && ammo.isEmpty(); i++) {
            ItemStack s = inv.get(i);
            if (rocket ? harmfulRocket(s) : s.is(Items.ARROW)) {
                ammo = s.split(1);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            }
        }
        if (ammo.isEmpty() && rocket) return load(a, cb, false);
        if (ammo.isEmpty()) return false;
        int n = EnchantmentHelper.processProjectileCount(sl, cb, a, 1);
        List<ItemStack> list = new ArrayList<>(n);
        list.add(ammo);
        for (int k = 1; k < n; k++) {
            ItemStack extra = ammo.copyWithCount(1);
            extra.set(DataComponents.INTANGIBLE_PROJECTILE, Unit.INSTANCE);
            list.add(extra);
        }
        cb.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(list));
        a.level().playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.CROSSBOW_LOADING_END.value(), a.getSoundSource(), 1.0F,
            1.0F / (a.getRandom().nextFloat() * 0.5F + 1.0F) + 0.2F);
        a.mind.xbowLoads++;
        return true;
    }

    /** Let fly where it points, through the game's own crossbow (Multishot, Piercing, rockets as a player's). */
    static void fire(PlayerAssistantEntity a, Job j) {
        a.mind.xbow = null;
        ItemStack cb = a.getMainHandItem();
        if (!(cb.getItem() instanceof CrossbowItem item) || !CrossbowItem.isCharged(cb)) return;
        PaArchery.Shooter s = j.shooter;
        Vec3 moving = a.getDeltaMovement();
        double[] v = s.loose(Math.sqrt(moving.x * moving.x + moving.z * moving.z));
        Vec3 vel = new Vec3(v[0], v[1], v[2]);
        // its head where its hands put the shot (the game's crossbow shoots along its view)
        double yaw = PaArchery.yawTo(vel.x, vel.z), pitch = -Math.toDegrees(Math.atan2(vel.y, vel.horizontalDistance()));
        PaBow.face(a, yaw, pitch);
        a.yHeadRotO = a.getYHeadRot();
        a.xRotO = a.getXRot();
        boolean rocket = chargedWithRocket(cb);
        int count = cb.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY).getItems().size();
        item.performShooting(a.level(), a, InteractionHand.MAIN_HAND, cb, (float) vel.length(), 0.0F, null);
        Projectile main = null;
        for (Projectile p : a.level().getEntitiesOfClass(Projectile.class, a.getBoundingBox().inflate(3), p -> p.getOwner() == a && p.tickCount == 0)) {
            p.getPersistentData().putUUID("pa_shooter", a.getUUID());
            if (j.block != null) p.getPersistentData().putLong("pa_block", j.block.asLong());
            if (p instanceof AbstractArrow arr) {
                arr.setCritArrow(true);                                   // as a player's crossbow bolts
                if (arr.pickup != AbstractArrow.Pickup.CREATIVE_ONLY) arr.pickup = AbstractArrow.Pickup.ALLOWED;   // Multishot's copies stay not-to-pick-up
            }
            if (main == null || p.getDeltaMovement().normalize().dot(vel.normalize()) > main.getDeltaMovement().normalize().dot(vel.normalize())) main = p;
        }
        a.mind.aimUntil = a.tickCount + 10;
        a.mind.xbowShots++;
        PaData.Rec r = a.rec();
        if (r != null) { r.shots++; PaData.touch(); }
        if (count > 1 && a.getRandom().nextInt(6) == 0) PaTalk.reply(a, PaTalk.line(a, "xbow.multishot"));
        else if (rocket && a.getRandom().nextInt(3) == 0) PaTalk.reply(a, PaTalk.line(a, "xbow.rocket"));
        double dist = a.getEyePosition().distanceTo(j.target != null ? PaBow.middle(j.target) : j.spot);
        boolean willHit = j.block != null && main != null && PaShots.flies(a, main.position(), main.getDeltaMovement(), j.block);
        int fly = PaArchery.flightTicks(vel.length(), dist);
        if (j.done != null) j.done.accept(new PaBow.Shot(main instanceof net.minecraft.world.entity.projectile.Arrow ar ? ar : null, willHit, fly, dist));
    }
}
