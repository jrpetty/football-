package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * [pa33d] Its bow at a block: a target, a bell, a pinged spot. Aimed as a player aims (with drop for the distance), as
 * steady as its level allows, and judged honestly: where the arrow would land is worked out from its flight, so a hit is
 * a hit. Used by trick shots and the shooting contest.
 */
public final class PaShots {

    private PaShots() {}

    /** A bow in its hand or its pack (its own: not the town watch's rule for who may draw one). */
    public static boolean hasBow(PlayerAssistantEntity a) {
        return a.getMainHandItem().is(Items.BOW) || a.countMatching(s -> s.is(Items.BOW)) > 0;
    }

    /** The bow into its hand (what was there goes in the pack). */
    static void drawBow(PlayerAssistantEntity a) {
        if (a.getMainHandItem().is(Items.BOW)) return;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).is(Items.BOW)) {
                ItemStack held = a.getMainHandItem().copy();
                a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, inv.get(i).copy());
                inv.set(i, held);
                return;
            }
        }
    }

    /** Can it shoot that from here (in range for its level, with a clear line)? */
    public static boolean canShoot(PlayerAssistantEntity a, BlockPos at) {
        double d = Math.sqrt(a.blockPosition().distSqr(at));
        if (d > PaLevels.range(a.veteranLevel()) + (PaLevels.has(a.veteranLevel(), "trick") ? 12 : 0)) return false;
        Vec3 eye = a.getEyePosition();
        BlockHitResult hit = a.level().clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(at), net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, a));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(at);
    }

    /** One arrow at a block. True when it will hit it (the flight worked out at once); false for a miss or no shot. */
    public static boolean shootAt(PlayerAssistantEntity a, BlockPos at) {
        if (!hasBow(a) || a.removeMatching(s -> s.is(Items.ARROW), 1) < 1) return false;
        drawBow(a);
        int lv = a.veteranLevel();
        float velocity = 2.6F + 0.04F * lv;
        Arrow arrow = new Arrow(a.level(), a, new ItemStack(Items.ARROW), a.getMainHandItem().copy());
        Vec3 aim = Vec3.atCenterOf(at);
        double dx = aim.x - a.getX(), dy = aim.y - arrow.getY(), dz = aim.z - a.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        // a player's lob: the least lift whose flight meets the block (worked out as a player learns it, by eye)
        double lift = 0;
        for (int k = 0; k <= 60; k++) {
            double l = horiz * k * 0.01;
            if (flies(a, arrow.position(), new Vec3(dx, dy + l, dz).normalize().scale(velocity), at)) { lift = l; break; }
        }
        arrow.shoot(dx, dy + lift, dz, velocity, PaLevels.spread(lv) * 0.5F);
        arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED;
        boolean hit = lands(a, arrow, at);
        a.playSound(net.minecraft.sounds.SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (a.getRandom().nextFloat() * 0.4F + 0.8F));
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        a.level().addFreshEntity(arrow);
        return hit;
    }

    /** [pa33e] About how long an arrow takes over so many blocks (ticks). */
    static int flightTicks(PlayerAssistantEntity a, double dist) {
        float v = 2.6F + 0.04F * a.veteranLevel();
        return (int) Math.ceil(dist / (v * 0.93));
    }

    /** [pa33e] At something on the move (a pig in a cart, a running mob): aimed at where it will be, the arrow's own
     *  flight worked out to meet it. The ticks till it gets there, or -1 for no bow or arrow. */
    public static int shootAtMover(PlayerAssistantEntity a, net.minecraft.world.entity.Entity e) {
        if (!hasBow(a) || a.removeMatching(s -> s.is(Items.ARROW), 1) < 1) return -1;
        drawBow(a);
        int lv = a.veteranLevel();
        float velocity = 2.6F + 0.04F * lv;
        Arrow arrow = new Arrow(a.level(), a, new ItemStack(Items.ARROW), a.getMainHandItem().copy());
        Vec3 move = e.getRootVehicle().getDeltaMovement();
        if (e.getRootVehicle() == e && e.onGround()) move = new Vec3(move.x, 0, move.z);
        Vec3 at = e.position().add(0, e.getBbHeight() * 0.6, 0);
        int ticks = 0;
        for (int k = 0; k < 4; k++) {                                     // where it'll be when the arrow is: settle on it
            ticks = flightTicks(a, at.distanceTo(arrow.position()));
            at = e.position().add(0, e.getBbHeight() * 0.6, 0).add(move.scale(PaLevels.leads(lv) ? ticks : 0));
        }
        double dx = at.x - a.getX(), dy = at.y - arrow.getY(), dz = at.z - a.getZ(), horiz = Math.sqrt(dx * dx + dz * dz);
        double lift = horiz * 0.02;
        for (int k = 0; k <= 60; k++) {
            double l = horiz * k * 0.01;
            if (passes(arrow.position(), new Vec3(dx, dy + l, dz).normalize().scale(velocity), at)) { lift = l; break; }
        }
        arrow.shoot(dx, dy + lift, dz, velocity, PaLevels.spread(lv) * 0.3F);
        arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED;
        a.playSound(net.minecraft.sounds.SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (a.getRandom().nextFloat() * 0.4F + 0.8F));
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        a.level().addFreshEntity(arrow);
        return ticks;
    }

    /** Does a flight pass within half a block of a point (drag and gravity as the game's)? */
    static boolean passes(Vec3 from, Vec3 vel, Vec3 point) {
        Vec3 pos = from, v = vel;
        for (int t = 0; t < 120; t++) {
            Vec3 next = pos.add(v);
            // nearest approach on this tick's segment
            Vec3 seg = next.subtract(pos);
            double u = Math.max(0, Math.min(1, point.subtract(pos).dot(seg) / Math.max(1e-6, seg.lengthSqr())));
            if (pos.add(seg.scale(u)).distanceTo(point) < 0.5) return true;
            if (next.y < point.y - 3 && v.y < 0) return false;
            pos = next;
            v = v.scale(0.99).add(0, -0.05, 0);
        }
        return false;
    }

    /** Fly the arrow in our heads (the game's own drag and gravity) and see what it meets first. */
    static boolean lands(PlayerAssistantEntity a, Arrow arrow, BlockPos at) {
        return flies(a, arrow.position(), arrow.getDeltaMovement(), at);
    }

    static boolean flies(PlayerAssistantEntity a, Vec3 from, Vec3 vel, BlockPos at) {
        Vec3 pos = from, v = vel;
        for (int t = 0; t < 120; t++) {
            Vec3 next = pos.add(v);
            BlockHitResult hit = a.level().clip(new net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, a));
            if (hit.getType() != HitResult.Type.MISS) return hit.getBlockPos().equals(at);
            pos = next;
            v = v.scale(0.99).add(0, -0.05, 0);
            if (pos.y < a.level().getMinBuildHeight()) return false;
        }
        return false;
    }
}
