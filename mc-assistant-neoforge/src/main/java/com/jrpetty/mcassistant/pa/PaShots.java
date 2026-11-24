package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
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

    /** [pa33f] Its eyes (and body) held on what it shot at for a moment after the loose (the follow-through); while it
     *  draws, PaBow keeps them there. [pa33g] Turned no faster than its level allows: no snapping. */
    static void holdAim(PlayerAssistantEntity a, @javax.annotation.Nullable net.minecraft.world.entity.Entity at, Vec3 spot) {
        a.mind.aimEntity = at;
        a.mind.aimSpot = spot;
        a.mind.aimUntil = a.tickCount + 24;
    }

    /** Each tick, from its moves: the aim held (between draws), turning at its level's pace. */
    static void aimTick(PlayerAssistantEntity a) {
        PaBrain.Mind m = a.mind;
        if (m.bow != null || m.aimUntil <= a.tickCount || m.aimSpot == null) return;
        if (m.aimEntity != null && !m.aimEntity.isAlive()) { m.aimUntil = 0; return; }
        Vec3 p = m.aimEntity != null ? m.aimEntity.getBoundingBox().getCenter() : m.aimSpot;
        Vec3 eye = a.getEyePosition();
        double dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
        double wantYaw = PaArchery.yawTo(dx, dz), wantPitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        double turn = PaLevels.turnDegrees(a.veteranLevel());
        double yaw = a.getYHeadRot() + Math.max(-turn, Math.min(turn, PaArchery.wrap(wantYaw - a.getYHeadRot())));
        double pitch = a.getXRot() + Math.max(-turn, Math.min(turn, wantPitch - a.getXRot()));
        PaBow.face(a, yaw, pitch);
    }

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

    /** [pa33g] An arrow at a block, drawn and loosed as a player's (PaBow): {@code done} hears of the shot when it leaves
     *  the bow (whether it will land on the block, worked out from its real flight). False for no bow or arrow. */
    public static boolean shootAt(PlayerAssistantEntity a, BlockPos at, @javax.annotation.Nullable java.util.function.Consumer<PaBow.Shot> done) {
        return PaBow.atBlock(a, at, done);
    }

    /** About how long an arrow takes over so many blocks off a full draw (ticks). */
    static int flightTicks(PlayerAssistantEntity a, double dist) {
        return PaArchery.flightTicks(PaArchery.FULL_SPEED, dist);
    }

    /** [pa33g] At something on the move (a pig in a cart, a running mob): it tracks it through the draw and leads it by
     *  what it saw, as well as its level allows. False for no bow or arrow. */
    public static boolean shootAtMover(PlayerAssistantEntity a, net.minecraft.world.entity.Entity e, @javax.annotation.Nullable java.util.function.Consumer<PaBow.Shot> done) {
        return PaBow.atEntity(a, e, done, null, null);
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
