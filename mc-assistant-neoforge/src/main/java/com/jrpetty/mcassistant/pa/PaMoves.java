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
        PaParkour.tick(a);                                                 // [pa33e] jumps of one to three, a bridge or a pillar when one falls short
        PaFlight.tick(a);
        PaShots.aimTick(a);                                                // [pa33f] eyes on its target while it shoots                                                  // [pa33e] elytra following (it checks its level itself: putting the wings away never waits)
        if (a.tickCount % 4 == 0 && lv >= PaLevels.levelOf("shield")) shield(a);
        if (a.tickCount % 20 == 7) {
            if (lv >= PaLevels.levelOf("parkour")) sprintAfter(a);
            if (lv >= PaLevels.levelOf("pearl")) pearl(a);
        }
        if (a.tickCount % 30 == 11 && lv >= PaLevels.levelOf("end") && a.level().dimension() == net.minecraft.world.level.Level.END) end(a);
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

    // ------------------------------------------------------------------ the End: crystals first, then the dragon

    /** In the End with its player at level 10: the healing crystals shot off their pillars, then arrows at the dragon. */
    static void end(PlayerAssistantEntity a) {
        if (!PaShots.hasBow(a) || a.countMatching(s -> s.is(Items.ARROW)) == 0 || a.isSleeping()) return;
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level()) return;
        net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal = null;
        double bd = 64 * 64;
        for (net.minecraft.world.entity.boss.enderdragon.EndCrystal c : a.level().getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, a.getBoundingBox().inflate(64))) {
            double d = c.distanceToSqr(a);
            if (d < bd && a.hasLineOfSight(c)) { bd = d; crystal = c; }
        }
        if (crystal != null) {
            shootAtEntity(a, crystal);
            if (a.tickCount - a.mind.endSaidAt > 600) { a.mind.endSaidAt = a.tickCount; PaTalk.reply(a, PaTalk.line(a, "end_crystal")); }
            return;
        }
        for (net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon : a.level().getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class, a.getBoundingBox().inflate(48))) {
            if (!dragon.isAlive()) continue;
            shootAtEntity(a, dragon);
            if (a.tickCount - a.mind.endSaidAt > 600) { a.mind.endSaidAt = a.tickCount; PaTalk.reply(a, PaTalk.line(a, "end_dragon")); }
            return;
        }
    }

    static void shootAtEntity(PlayerAssistantEntity a, net.minecraft.world.entity.Entity e) {
        if (a.removeMatching(s -> s.is(Items.ARROW), 1) < 1) return;
        PaShots.drawBow(a);
        PaShots.holdAim(a, e, e.position());                               // [pa33f] facing what it shoots, not its player
        net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(a.level(), a, new ItemStack(Items.ARROW), a.getMainHandItem().copy());
        // [pa33e] the dragon only feels a player's arrows (the game's rule): at the dragon it shoots as its player's second bow
        if ((e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon || e instanceof net.minecraft.world.entity.boss.EnderDragonPart) && a.getOwnerPlayer() != null) arrow.setOwner(a.getOwnerPlayer());
        Vec3 aim = e.position().add(0, e.getBbHeight() * 0.5, 0).add(e.getDeltaMovement().scale(Math.sqrt(a.distanceToSqr(e)) / 3.0));
        double dx = aim.x - a.getX(), dy = aim.y - arrow.getY(), dz = aim.z - a.getZ(), horiz = Math.sqrt(dx * dx + dz * dz);
        a.getLookControl().setLookAt(aim.x, aim.y, aim.z);
        arrow.shoot(dx, dy + horiz * 0.15, dz, 3.0F, PaLevels.spread(a.veteranLevel()));
        a.level().addFreshEntity(arrow);
        a.swing(InteractionHand.MAIN_HAND);
        a.playSound(net.minecraft.sounds.SoundEvents.ARROW_SHOOT, 1.0F, 1.0F);
    }

    /** Tests: a pearl at a spot, now. */
    public static void throwPearlForTests(PlayerAssistantEntity a, Vec3 to) {
        throwPearl(a, to);
    }
}
