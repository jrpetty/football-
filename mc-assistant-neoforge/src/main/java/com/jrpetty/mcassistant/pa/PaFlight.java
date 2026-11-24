package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * [pa33e] Elytra following (level 8), as a player does it: when its player takes to the air it puts on the elytra from
 * its pack (its chestplate goes into the pack), takes off (a rocket from flat ground, or a jump from somewhere high),
 * glides after them at their shoulder, with a rocket when it falls behind, and when they come down it lands beside
 * them at a gentle angle (no fall, no knock into a wall) and puts its chestplate back on. No elytra, or no rocket and
 * nowhere to jump from: it follows on foot, as before.
 */
public final class PaFlight {

    private PaFlight() {}

    /** Every tick, on the server. */
    static void tick(PlayerAssistantEntity a) {
        PaBrain.Mind m = a.mind;
        ServerPlayer owner = a.getOwnerPlayer() instanceof ServerPlayer sp && sp.level() == a.level() ? sp : null;
        if (!a.isFallFlying()) {
            if (m.wingsOn && (a.onGround() || a.isInWater()) && a.tickCount - m.takeoffAt > 10 && a.tickCount - m.wingsAt > 40) putAway(a);
            if (m.takeoffAt > 0 && a.tickCount - m.takeoffAt <= 10 && !a.onGround() && a.getDeltaMovement().y < 0.25) {
                a.glide(true);                                           // the top of the jump: wings open
                if (rockets(a) > 0) boost(a);
                return;
            }
            if (owner == null || !owner.isFallFlying() || !free(a)) return;
            if (a.distanceToSqr(owner) < 5 * 5) return;
            if (!PaLevels.has(a.veteranLevel(), "elytra") || elytra(a) < 0) return;
            boolean high = !a.onGround() && a.fallDistance > 2.0F || drop(a) >= 5;
            if (rockets(a) == 0 && !high) return;                        // no rocket and nowhere to jump from: on foot
            // [pa33g] the wings on first, where it stands, and a moment to be seen in them (the swap reaches every client) before it goes
            if (!a.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                if (wear(a)) { m.wingsAt = a.tickCount; a.getNavigation().stop(); }
                return;
            }
            if (a.tickCount - m.wingsAt < 8) return;
            m.wingsOn = true;
            m.takeoffAt = a.tickCount;
            if (a.onGround()) {
                a.getJumpControl().jump();
                a.setDeltaMovement(a.getDeltaMovement().add(0, 0.5, 0));
                a.hasImpulse = true;
            } else {
                a.glide(true);
                if (rockets(a) > 0) boost(a);
            }
            if (a.tickCount - m.flightSaidAt > 2400) { m.flightSaidAt = a.tickCount; PaTalk.reply(a, PaTalk.line(a, "elytra_up")); }
            return;
        }
        // in the air
        a.fallDistance = 0;                                              // a glide, not a fall
        a.getNavigation().stop();
        boolean up = owner != null && owner.isFallFlying();
        // [pa33g] on its wing, as a wingman flies: a little behind and well out to one side (where its player sees it, clear of their wings)
        Vec3 ahead = up ? owner.getLookAngle().multiply(1, 0, 1).normalize() : Vec3.ZERO;
        Vec3 target = up ? owner.position().subtract(ahead.scale(1.5)).add(-ahead.z * 3.0, 0.4, ahead.x * 3.0) : landing(a, owner);
        Vec3 to = target.subtract(a.position());
        double dist = to.length(), flat = Math.sqrt(to.x * to.x + to.z * to.z);
        float yaw = (float) (Math.atan2(to.z, to.x) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) Math.max(-40.0, Math.min(55.0, -Math.atan2(to.y, Math.max(0.1, flat)) * 180.0 / Math.PI));
        a.setYRot(yaw);
        a.yBodyRot = yaw;
        a.setYHeadRot(yaw);
        a.setXRot(pitch);
        a.getLookControl().setLookAt(target.x, target.y + 1.5, target.z, 360.0F, 90.0F);
        // a player steers with the mouse and the elytra does the rest; a mob's turn needs a steady hand on it
        double pace = up ? Math.max(0.5, Math.min(2.2, owner.getDeltaMovement().length() + dist * 0.06)) : Math.max(0.35, Math.min(0.9, dist * 0.08));
        Vec3 want = dist < 0.01 ? Vec3.ZERO : to.scale(pace / dist);
        if (!up) want = new Vec3(want.x, Math.max(-0.45, want.y), want.z);   // down gently: never a dive into the ground
        a.setDeltaMovement(a.getDeltaMovement().scale(0.8).add(want.scale(0.2)));
        a.hasImpulse = true;
        if (up && dist > 16 && rockets(a) > 0 && a.tickCount - m.rocketAt > 40) boost(a);
        BlockPos ground = PaMoves.groundBelow(a, 2);
        if (!up && (ground != null || a.isInWater()) || a.horizontalCollision && a.getDeltaMovement().horizontalDistance() < 0.2) {
            a.glide(false);                                              // touching down
            a.setDeltaMovement(a.getDeltaMovement().multiply(0.3, 0.3, 0.3));
            m.takeoffAt = a.tickCount;
        }
    }

    /** Free to fly: following, awake, not riding, not in a game. */
    static boolean free(PlayerAssistantEntity a) {
        return a.getMode() == com.jrpetty.mcassistant.entity.AssistantEntity.Mode.FOLLOW && !a.isSleeping() && !a.isPassenger()
            && a.plan.idle() && !a.isInWater();
    }

    /** Where to come down: beside its player on the ground, else straight below. */
    static Vec3 landing(PlayerAssistantEntity a, @Nullable ServerPlayer owner) {
        if (owner == null) return a.position().add(a.getDeltaMovement().x * 6, -6, a.getDeltaMovement().z * 6);
        Vec3 side = a.position().subtract(owner.position()).multiply(1, 0, 1);
        side = side.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : side.normalize();
        return owner.position().add(side.scale(2.0));
    }

    /** How far it would drop stepping off here (a cliff, a tower): the most below any of its four sides, up to 24. */
    static int drop(PlayerAssistantEntity a) {
        int most = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos q = a.blockPosition().relative(d);
            if (!a.level().getBlockState(q).getCollisionShape(a.level(), q).isEmpty()) continue;
            int n = 0;
            while (n < 24 && a.level().getBlockState(q.below(n + 1)).getCollisionShape(a.level(), q.below(n + 1)).isEmpty()) n++;
            most = Math.max(most, n);
        }
        return most;
    }

    static int rockets(PlayerAssistantEntity a) {
        return a.countMatching(s -> s.is(Items.FIREWORK_ROCKET));
    }

    /** The elytra's place: -1 for none, 0 worn, 1 in its pack. */
    static int elytra(PlayerAssistantEntity a) {
        if (a.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) return 0;
        return a.countMatching(s -> s.is(Items.ELYTRA) && s.getDamageValue() < s.getMaxDamage() - 1) > 0 ? 1 : -1;
    }

    /** Wings on: the elytra from its pack, the chestplate into it. */
    static boolean wear(PlayerAssistantEntity a) {
        if (a.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) { a.mind.wingsOn = true; return true; }
        ItemStack chest = a.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.isEmpty() && a.isPackFull()) return false;
        if (a.removeMatching(s -> s.is(Items.ELYTRA) && s.getDamageValue() < s.getMaxDamage() - 1, 1) != 1) return false;
        ItemStack wings = new ItemStack(Items.ELYTRA);
        if (!chest.isEmpty()) {
            ItemStack left = a.insertItem(chest.copy());
            if (!left.isEmpty()) { a.insertItem(wings); return false; }
        }
        a.setItemSlot(EquipmentSlot.CHEST, wings);
        a.mind.wingsOn = true;
        a.level().playSound(null, a.blockPosition(), net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_ELYTRA.value(), net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
        return true;
    }

    /** Landed: the elytra into its pack, its best chestplate back on. */
    static void putAway(PlayerAssistantEntity a) {
        a.mind.wingsOn = false;
        ItemStack chest = a.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) return;
        ItemStack best = ItemStack.EMPTY;
        int bestDef = -1;
        for (ItemStack s : a.getInventoryItems()) {
            if (s.getItem() instanceof ArmorItem ai && ai.getEquipmentSlot() == EquipmentSlot.CHEST && ai.getDefense() > bestDef) { best = s; bestDef = ai.getDefense(); }
        }
        ItemStack put = ItemStack.EMPTY;
        if (!best.isEmpty()) {
            final ItemStack pick = best.copyWithCount(1);
            if (a.removeMatching(s -> ItemStack.isSameItemSameComponents(s, pick), 1) == 1) put = pick;
        }
        a.setItemSlot(EquipmentSlot.CHEST, put);
        if (put.getItem() instanceof ArmorItem ai)                       // [pa33g] heard as well as seen: the chestplate back on
            a.level().playSound(null, a.blockPosition(), ai.getEquipSound().value(), net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
        ItemStack left = a.insertItem(chest);
        if (!left.isEmpty()) a.spawnAtLocation(left);
    }

    /** A rocket, as a player fires one in flight: one from its pack, and away. */
    static void boost(PlayerAssistantEntity a) {
        if (a.removeMatching(s -> s.is(Items.FIREWORK_ROCKET), 1) != 1) return;
        a.mind.rocketAt = a.tickCount;
        FireworkRocketEntity r = new FireworkRocketEntity(a.level(), new ItemStack(Items.FIREWORK_ROCKET), a);
        a.level().addFreshEntity(r);
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        Vec3 look = a.getLookAngle();
        a.setDeltaMovement(a.getDeltaMovement().add(look.scale(0.6)));
    }
}
