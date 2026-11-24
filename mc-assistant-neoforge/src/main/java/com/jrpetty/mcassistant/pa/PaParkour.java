package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * [pa33e] Parkour after its player (level 7), as a player does it: where the path runs out at a gap of one to three
 * blocks (level, a step up or a step down on the far side) it sprint-jumps across. A jump that falls short is not tried
 * again blindly: down in the hole it pillars out with blocks from its pack, and the next time at that gap it bridges it.
 * A path the ordinary way is always first; this is only for where there is none.
 */
public final class PaParkour {

    private PaParkour() {}

    static final int NONE = 0, BRIDGE = 1, PILLAR = 2;

    /** A gap to cross: the block it jumps from (its feet), where it lands (its feet), the way, and how wide. */
    record Gap(BlockPos edge, BlockPos land, Direction dir, int width) {}

    /** Every tick, on the server. */
    static void tick(PlayerAssistantEntity a) {
        PaBrain.Mind m = a.mind;
        if (!PaLevels.has(a.veteranLevel(), "parkour")) return;
        ServerPlayer owner = a.getOwnerPlayer() instanceof ServerPlayer sp && sp.level() == a.level() ? sp : null;
        if (m.leapTo != null) { landing(a, m); return; }
        if (owner == null || !following(a) || owner.isFallFlying() || owner.isSpectator()) { m.pkMode = NONE; return; }
        if (m.pkMode == PILLAR) { pillarOut(a, owner, m); return; }
        if (m.pkMode == BRIDGE) { bridge(a, m); return; }
        if (a.tickCount % 5 != 2 || !a.onGround() || a.isInWater()) return;
        double d = Math.sqrt(a.distanceToSqr(owner));
        if (d < 4.0 || d > 40.0 || a.tickCount < m.pkRestUntil) { m.pkStuck = 0; m.pkDist = d; return; }
        if (d > m.pkDist - 0.4) m.pkStuck++; else m.pkStuck = 0;              // no nearer
        m.pkDist = d;
        if (m.pkStuck < 4) return;                                           // a second without getting closer: the path has run out
        Gap g = gap(a, owner);
        if (g == null) {
            // down a hole with its player above: up and out
            if (owner.getY() - a.getY() >= 1.5 && blocks(a) > 0 && Math.abs(owner.getX() - a.getX()) + Math.abs(owner.getZ() - a.getZ()) < 12) {
                m.pkMode = PILLAR;
                m.pkStuck = 0;
            }
            return;
        }
        BlockPos feet = a.blockPosition();
        if (!feet.equals(g.edge())) {                                        // to the edge first
            if (feet.distManhattan(g.edge()) <= 3) a.getNavigation().moveTo(g.edge().getX() + 0.5, g.edge().getY(), g.edge().getZ() + 0.5, 1.0);
            return;
        }
        m.pkStuck = 0;
        if (g.equals(m.failedGap) && blocks(a) >= g.width()) { startBridge(a, m, g); return; }
        leap(a, m, g);
    }

    static boolean following(PlayerAssistantEntity a) {
        return a.getMode() == com.jrpetty.mcassistant.entity.AssistantEntity.Mode.FOLLOW && a.plan.idle() && !a.isSleeping()
            && !a.isPassenger() && !a.isFallFlying() && PaChallenge.GAMES.get(a.getOwnerId()) == null;
    }

    // ------------------------------------------------------------------ seeing a gap

    /** A gap of 1..3 straight ahead toward its player (along the nearer axis), with ground on the far side. */
    @Nullable
    static Gap gap(PlayerAssistantEntity a, ServerPlayer owner) {
        Vec3 to = owner.position().subtract(a.position());
        Direction dir = Math.abs(to.x) > Math.abs(to.z) ? (to.x > 0 ? Direction.EAST : Direction.WEST) : (to.z > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos feet = a.blockPosition();
        for (int i = 0; i <= 2; i++) {
            BlockPos edge = feet.relative(dir, i);
            if (!ground(a, edge) || !clear(a, edge, 3)) return null;
            if (ground(a, edge.relative(dir))) continue;
            // the gap
            int w = 0;
            BlockPos q = edge.relative(dir);
            while (w < 4 && !ground(a, q) && !ground(a, q.above()) && clear(a, q, 3)) { w++; q = q.relative(dir); }
            if (w < 1 || w > 3) return null;
            for (int dy : new int[]{0, 1, -1}) {                             // level, a step up, a step down
                BlockPos land = q.above(dy);
                if (dy == 1 && w > 2) continue;                                // up and three across: too far
                if (ground(a, land) && clear(a, land, 2)) return new Gap(edge.immutable(), land.immutable(), dir, w);
            }
            return null;
        }
        return null;
    }

    /** Something to stand on under these feet. */
    static boolean ground(PlayerAssistantEntity a, BlockPos feet) {
        BlockPos b = feet.below();
        return !a.level().getBlockState(b).getCollisionShape(a.level(), b).isEmpty() && a.level().getBlockState(feet).getCollisionShape(a.level(), feet).isEmpty();
    }

    /** Room for a body (and a jump) here: so many blocks of air up from the feet. */
    static boolean clear(PlayerAssistantEntity a, BlockPos feet, int up) {
        for (int i = 0; i < up; i++) {
            BlockPos q = feet.above(i);
            if (!a.level().getBlockState(q).getCollisionShape(a.level(), q).isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the jump

    static void leap(PlayerAssistantEntity a, PaBrain.Mind m, Gap g) {
        Vec3 from = a.position(), to = Vec3.atBottomCenterOf(g.land());
        Vec3 flat = new Vec3(to.x - from.x, 0, to.z - from.z);
        double dist = flat.length();
        int up = g.land().getY() - g.edge().getY();
        // a sprint-jump: about seven blocks of carry for each block a tick it starts with (air drag 0.91 a tick, twelve ticks up and down)
        double v0 = Math.min(0.62, (dist + 0.25) / (up > 0 ? 5.6 : up < 0 ? 8.4 : 7.2));
        Vec3 h = flat.normalize().scale(v0);
        a.getNavigation().stop();
        a.setSprinting(true);
        float yaw = (float) (Math.atan2(flat.z, flat.x) * 180.0 / Math.PI) - 90.0F;
        a.setYRot(yaw);
        a.yBodyRot = yaw;
        a.setYHeadRot(yaw);
        a.setDeltaMovement(h.x, 0.42 + (up > 0 ? 0.1 : 0.0), h.z);
        a.hasImpulse = true;
        m.leapAt = a.tickCount;
        m.leapFrom = g.edge();
        m.leapTo = g.land();
        m.leapGap = g;
        if (a.tickCount - m.pkSaidAt > 1200 && a.getRandom().nextInt(3) == 0) { m.pkSaidAt = a.tickCount; PaTalk.reply(a, PaTalk.line(a, "parkour")); }
    }

    /** In the air after a jump: on the far side, or short (and then what to do about it). */
    static void landing(PlayerAssistantEntity a, PaBrain.Mind m) {
        int t = a.tickCount - m.leapAt;
        if (t < 3) return;
        if (!(a.onGround() || a.isInWater() || t > 40)) return;
        BlockPos to = m.leapTo;
        boolean made = a.getY() >= to.getY() - 0.4 && Math.abs(a.getX() - (to.getX() + 0.5)) < 1.6 && Math.abs(a.getZ() - (to.getZ() + 0.5)) < 1.6;
        Gap g = m.leapGap;
        m.leapTo = null;
        a.setSprinting(false);
        if (made) {
            a.earn("trick");
            return;
        }
        m.failedGap = g;                                                     // next time at this gap: a bridge
        if (a.getY() < m.leapFrom.getY() - 1.5) {
            m.pkMode = blocks(a) > 0 ? PILLAR : NONE;
            PaTalk.reply(a, PaTalk.line(a, "parkour_short"));
        }
        m.pkRestUntil = a.tickCount + 20;
    }

    // ------------------------------------------------------------------ the fallbacks: a bridge, a pillar

    static void startBridge(PlayerAssistantEntity a, PaBrain.Mind m, Gap g) {
        m.pkMode = BRIDGE;
        m.pkBridge = g;
        m.pkLaid = 0;
        a.getNavigation().stop();
    }

    /** From the edge, a block into the gap at a time, walking out onto each. */
    static void bridge(PlayerAssistantEntity a, PaBrain.Mind m) {
        Gap g = m.pkBridge;
        if (g == null || m.pkLaid >= g.width()) { m.pkMode = NONE; m.pkBridge = null; m.failedGap = null; return; }
        if (a.tickCount % 6 != 0) return;
        BlockPos spot = g.edge().relative(g.dir(), m.pkLaid + 1).below();      // where the next block goes (under the next step)
        BlockPos stand = g.edge().relative(g.dir(), m.pkLaid);
        if (a.blockPosition().distManhattan(stand) > 1) { a.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.8); return; }
        a.getNavigation().stop();
        if (!a.level().getBlockState(spot).canBeReplaced()) { m.pkLaid++; return; }
        if (!place(a, spot)) { m.pkMode = NONE; return; }
        m.pkLaid++;
        a.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
    }

    /** Down a hole: a jump and a block under its feet, till it's up level with its player (or out of blocks). */
    static void pillarOut(PlayerAssistantEntity a, ServerPlayer owner, PaBrain.Mind m) {
        if (a.getY() >= owner.getY() - 0.5 || blocks(a) == 0 || !clear(a, a.blockPosition(), 3)) {
            m.pkMode = NONE;
            m.pkJump = null;
            m.pkRestUntil = a.tickCount + 20;
            return;
        }
        a.getNavigation().stop();
        if (a.onGround() && m.pkJump == null) { m.pkJump = a.blockPosition(); a.jumpFromGround(); return; }
        if (m.pkJump != null && a.getY() >= m.pkJump.getY() + 1.05) {
            if (a.level().getBlockState(m.pkJump).canBeReplaced()) place(a, m.pkJump);
            m.pkJump = null;
        } else if (m.pkJump != null && a.onGround() && a.getBlockY() <= m.pkJump.getY() && a.tickCount % 20 == 0) {
            m.pkJump = null;
        }
    }

    static int blocks(PlayerAssistantEntity a) {
        return a.countMatching(PaTaskGoal::pillarBlock);
    }

    /** A block from its pack set here (a swing, a sound). */
    static boolean place(PlayerAssistantEntity a, BlockPos at) {
        if (PaSafety.offLimits(a, at)) return false;
        for (int i = 0; i < a.getInventoryItems().size(); i++) {
            ItemStack s = a.getInventoryItems().get(i);
            if (!PaTaskGoal.pillarBlock(s)) continue;
            a.level().setBlock(at, ((BlockItem) s.getItem()).getBlock().defaultBlockState(), 3);
            s.shrink(1);
            a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            a.placeSound(at);
            return true;
        }
        return false;
    }
}
