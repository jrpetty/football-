package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * [pa33d] Moves a player learns, by level: the shield up when something swings, shoots or hisses (level 4); a sprint
 * after a player who has run on ahead (level 7); a water-bucket clutch on a long fall (level 8); an ender pearl thrown
 * to catch up when it's left far behind and stuck (level 9). Each with a word, at its corner of the triangle.
 */
public final class PaMoves {

    private PaMoves() {}

    /** Every tick, on the server. */
    static void tick(PlayerAssistantEntity a) {
        int lv = a.veteranLevel();
        if (lv >= PaLevels.levelOf("clutch")) clutch(a);
        if (a.tickCount % 4 == 0 && lv >= PaLevels.levelOf("shield")) shield(a);
        if (a.tickCount % 20 == 7) {
            if (lv >= PaLevels.levelOf("parkour")) sprintAfter(a);
            if (lv >= PaLevels.levelOf("pearl")) pearl(a);
        }
    }

    // ------------------------------------------------------------------ the shield

    static void shield(PlayerAssistantEntity a) {
        ItemStack off = a.getOffhandItem();
        if (!off.is(Items.SHIELD)) {
            if (a.mind.blocking) { a.stopUsingItem(); a.mind.blocking = false; }
            return;
        }
        boolean threat = !a.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, a.getBoundingBox().inflate(3.5),
            e -> e instanceof Enemy && e.isAlive() && (e.getTarget() == a || e instanceof Creeper c && c.getSwellDir() > 0)).isEmpty()
            || !a.level().getEntitiesOfClass(AbstractArrow.class, a.getBoundingBox().inflate(8),
            ar -> !ar.onGround() && ar.getOwner() != a && ar.getDeltaMovement().lengthSqr() > 0.5
                && ar.getDeltaMovement().normalize().dot(a.position().subtract(ar.position()).normalize()) > 0.9).isEmpty();
        if (threat && !a.isUsingItem()) {
            a.startUsingItem(InteractionHand.OFF_HAND);
            a.mind.blocking = true;
            a.mind.blockUntil = a.tickCount + 20;
        } else if (a.mind.blocking && !threat && a.tickCount > a.mind.blockUntil) {
            a.stopUsingItem();
            a.mind.blocking = false;
        }
    }

    // ------------------------------------------------------------------ the water-bucket clutch

    static void clutch(PlayerAssistantEntity a) {
        PaBrain.Mind m = a.mind;
        if (m.clutchAt != null) {                                          // landed: the water back in the bucket
            if ((a.onGround() || a.isInWater()) && a.tickCount - m.clutchTick > 3) {
                if (a.level().getBlockState(m.clutchAt).is(Blocks.WATER)) {
                    a.level().setBlock(m.clutchAt, Blocks.AIR.defaultBlockState(), 3);
                    swapBucket(a, Items.BUCKET, Items.WATER_BUCKET);
                    a.level().playSound(null, m.clutchAt, net.minecraft.sounds.SoundEvents.BUCKET_FILL, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
                }
                m.clutchAt = null;
                if (a.getRandom().nextInt(2) == 0) PaTalk.reply(a, PaTalk.line(a, "clutch"));
            }
            return;
        }
        if (a.onGround() || a.isInWater() || a.fallDistance < 4.0F || a.getDeltaMovement().y > -0.5) return;
        if (a.level().dimension() == net.minecraft.world.level.Level.NETHER) return;   // water boils there
        if (a.countMatching(s -> s.is(Items.WATER_BUCKET)) == 0) return;
        double vy = -a.getDeltaMovement().y;
        int look = (int) Math.ceil(vy * 2 + 3);                              // as far as the next two ticks of falling
        BlockPos ground = groundBelow(a, look);
        if (ground == null) return;
        double above = a.getY() - (ground.getY() + 1);
        if (above > Math.max(3.2, vy * 1.6 + 0.6) || a.fallDistance + above < 6.0F) return;   // only a fall that would hurt, just before it lands
        BlockPos at = ground.above();
        if (!a.level().getBlockState(at).canBeReplaced()) return;
        a.level().setBlock(at, Blocks.WATER.defaultBlockState(), 3);
        swapBucket(a, Items.WATER_BUCKET, Items.BUCKET);
        a.swing(InteractionHand.MAIN_HAND);
        a.level().playSound(null, at, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
        a.playAnim("point");
        m.clutchAt = at.immutable();
        m.clutchTick = a.tickCount;
        a.earn("trick");
    }

    @Nullable
    static BlockPos groundBelow(PlayerAssistantEntity a, int most) {
        BlockPos p = a.blockPosition();
        for (int i = 0; i <= most; i++) {
            BlockPos q = p.below(i);
            if (!a.level().getBlockState(q).getCollisionShape(a.level(), q).isEmpty()) return q;
        }
        return null;
    }

    static void swapBucket(PlayerAssistantEntity a, net.minecraft.world.item.Item from, net.minecraft.world.item.Item to) {
        if (a.removeMatching(s -> s.is(from), 1) == 1) {
            ItemStack left = a.insertItem(new ItemStack(to));
            if (!left.isEmpty()) a.spawnAtLocation(left);
        }
    }

    // ------------------------------------------------------------------ sprinting after its player

    static void sprintAfter(PlayerAssistantEntity a) {
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level()) return;
        boolean far = a.distanceToSqr(owner) > 10 * 10 && owner.isSprinting();
        if (a.isSprinting() != far && a.getMode() == com.jrpetty.mcassistant.entity.AssistantEntity.Mode.FOLLOW) a.setSprinting(far);
    }

    // ------------------------------------------------------------------ an ender pearl to catch up

    static void pearl(PlayerAssistantEntity a) {
        PaBrain.Mind m = a.mind;
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level()) return;
        if (a.getMode() != com.jrpetty.mcassistant.entity.AssistantEntity.Mode.FOLLOW || a.isSleeping() || a.tickCount - m.pearlAt < 400) return;
        double d = Math.sqrt(a.distanceToSqr(owner));
        if (d < 20 || d > 45) { m.pearlStuck = 0; m.pearlDist = d; return; }
        // stuck: no nearer in three seconds
        if (d > m.pearlDist - 1.0) m.pearlStuck++; else m.pearlStuck = 0;
        m.pearlDist = d;
        if (m.pearlStuck < 3 || a.countMatching(s -> s.is(Items.ENDER_PEARL)) == 0) return;
        if (!owner.onGround()) return;
        throwPearl(a, owner.position());
        m.pearlAt = a.tickCount;
        m.pearlStuck = 0;
        PaTalk.reply(a, PaTalk.line(a, "pearl"));
    }

    /** A pearl lobbed to land at a spot (it goes where the pearl lands, as a player does). */
    static void throwPearl(PlayerAssistantEntity a, Vec3 to) {
        a.removeMatching(s -> s.is(Items.ENDER_PEARL), 1);
        net.minecraft.world.entity.projectile.ThrownEnderpearl pearl = new net.minecraft.world.entity.projectile.ThrownEnderpearl(a.level(), a);
        pearl.setItem(new ItemStack(Items.ENDER_PEARL));
        Vec3 from = a.getEyePosition();
        double dx = to.x - from.x, dz = to.z - from.z, dy = to.y - from.y;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float v = 1.5F;
        // the least lift whose flight lands near the spot (thrown things fall at 0.03 a tick, drag 0.99)
        double bestLift = horiz * 0.3, bestErr = Double.MAX_VALUE;
        for (int k = 0; k <= 80; k++) {
            double lift = horiz * k * 0.0125;
            Vec3 vel = new Vec3(dx, dy + lift, dz).normalize().scale(v);
            Vec3 p = from;
            for (int t = 0; t < 200; t++) {
                p = p.add(vel);
                vel = vel.scale(0.99).add(0, -0.03, 0);
                if (vel.y < 0 && p.y <= to.y) break;
            }
            double err = p.distanceToSqr(to.x, to.y, to.z);
            if (err < bestErr) { bestErr = err; bestLift = lift; }
        }
        pearl.shoot(dx, dy + bestLift, dz, v, 0.5F);
        a.level().addFreshEntity(pearl);
        a.swing(InteractionHand.MAIN_HAND);
        a.level().playSound(null, a.blockPosition(), net.minecraft.sounds.SoundEvents.ENDER_PEARL_THROW, net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 0.4F);
        a.playAnim("point");
        a.earn("trick");
    }

    /** Tests: a pearl at a spot, now. */
    public static void throwPearlForTests(PlayerAssistantEntity a, Vec3 to) {
        throwPearl(a, to);
    }
}
