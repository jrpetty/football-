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
        if (!a.hasBow() || a.removeMatching(s -> s.is(Items.ARROW), 1) < 1) return false;
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
