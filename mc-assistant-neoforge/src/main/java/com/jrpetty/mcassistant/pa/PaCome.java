package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * [pa33g] "Come here": it makes its way to its player on foot the whole way, running (sprinting) while it's far and
 * walking the last few blocks, a leg at a time over long ground (each leg a path the game finds, so it swims, climbs and
 * goes round as a mob does), its own chunks kept awake around it as it goes (PaChunks), and a word on how far now and
 * then. No teleport for a plain come-here: only the old catch-up rules (another world; truly stuck for half a minute,
 * with a line saying so).
 */
public final class PaCome {

    private PaCome() {}

    /** Running beyond this; walking within it. */
    static final double RUN_FROM = 10.0;
    /** Close enough: arrived. */
    static final double ARRIVED = 2.6;
    /** A leg over long ground (blocks): within its own window of awake chunks. */
    static final int LEG = 20;
    /** Ticks without getting any closer before it takes the shortcut (truly stuck). */
    static final int STUCK = 600;
    /** A word on progress, at most this often, while it's further off than FAR. */
    static final int SAY_EVERY = 400;
    static final double FAR = 40.0;

    /** One trip to its player. */
    public static final class Trip {
        int started, saidAt, bestAt, pathAt = -100;
        double startDist, best = Double.MAX_VALUE;
        @Nullable Vec3 leg;
        /** Tests: how many shortcuts were taken (0 for a walk). */
        public int shortcuts;
    }

    /** The order: on its way (another world: across, as before). The line to say. */
    static String start(PlayerAssistantEntity a, ServerPlayer p) {
        a.mind.post = null;
        a.setMode(AssistantEntity.Mode.FOLLOW);
        if (a.level() != p.level()) {
            a.mind.come = null;
            PaLife.bringTo(a, p);
            return PaTalk.line(a, "come");
        }
        double d = Math.sqrt(a.distanceToSqr(p));
        if (d <= ARRIVED + 1) {
            a.mind.come = null;
            return PaTalk.line(a, "come");
        }
        Trip t = new Trip();
        t.started = a.tickCount;
        t.saidAt = a.tickCount;
        t.bestAt = a.tickCount;
        t.startDist = d;
        a.mind.come = t;
        a.getNavigation().stop();
        return d > FAR ? PaTalk.line(a, "come_far", "dist", String.valueOf(Math.round(d))) : PaTalk.line(a, "come");
    }

    /** Any other order ends the trip. */
    static void end(PlayerAssistantEntity a) {
        if (a.mind.come == null) return;
        a.mind.come = null;
        a.setSprinting(false);
    }

    /** The trip as a goal: above its work (a come-here comes first), below getting out of trouble. */
    static final class ComeGoal extends Goal {
        private final PlayerAssistantEntity a;

        ComeGoal(PlayerAssistantEntity a) {
            this.a = a;
            setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return a.mind.come != null && !a.isSleeping() && !a.isPassenger();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void stop() {
            a.setSprinting(false);
            if (a.mind.come == null) a.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            Trip t = a.mind.come;
            if (t == null) return;
            Player owner = a.getOwnerPlayer();
            if (!(owner instanceof ServerPlayer p) || !p.isAlive()) { end(a); return; }
            if (p.level() != a.level()) {                                // another world: across (the old rule)
                end(a);
                PaLife.bringTo(a, p);
                return;
            }
            double d = Math.sqrt(a.distanceToSqr(p));
            if (d <= ARRIVED) {
                end(a);
                a.getNavigation().stop();
                if (t.startDist > FAR) PaTalk.reply(a, PaTalk.line(a, "come_arrived"));
                return;
            }
            if (d < t.best - 1.0) { t.best = d; t.bestAt = a.tickCount; }
            if (a.tickCount - t.bestAt > STUCK) {                        // truly stuck: the shortcut, said out loud
                t.shortcuts++;
                PaTalk.reply(a, PaTalk.line(a, "come_stuck"));
                end(a);
                PaLife.bringTo(a, p);
                return;
            }
            if (d > FAR && a.tickCount - t.saidAt > SAY_EVERY) {
                t.saidAt = a.tickCount;
                PaTalk.reply(a, PaTalk.line(a, "come_progress", "dist", String.valueOf(Math.round(d))));
            }
            boolean run = d > RUN_FROM;
            a.setSprinting(run && !a.isInWater());
            double speed = run ? 1.25 : 1.0;
            boolean repath = a.getNavigation().isDone() || a.tickCount - t.pathAt >= 20
                || (t.leg != null && a.position().distanceToSqr(t.leg) < 6 * 6);
            if (!repath) return;
            t.pathAt = a.tickCount;
            if (d <= LEG + 4) {
                t.leg = null;
                a.getNavigation().moveTo(p, speed);
                return;
            }
            Vec3 to = p.position().subtract(a.position()).multiply(1, 0, 1).normalize();
            for (int len : new int[]{LEG, 14, 8}) {
                for (double turn : new double[]{0, 30, -30, 60, -60}) {
                    Vec3 dir = to.yRot((float) Math.toRadians(turn));
                    Vec3 leg = ground(a, a.position().add(dir.scale(len)));
                    if (leg == null) continue;
                    Path path = a.getNavigation().createPath(BlockPos.containing(leg), 1);
                    if (path == null) continue;
                    a.getNavigation().moveTo(path, speed);
                    t.leg = leg;
                    return;
                }
            }
            a.getNavigation().moveTo(p, speed);                          // nothing better: straight at them, as far as it goes
        }
    }

    /** Standing room at that spot's top (its chunk awake), or null. */
    @Nullable
    static Vec3 ground(PlayerAssistantEntity a, Vec3 at) {
        BlockPos col = BlockPos.containing(at);
        if (!a.level().hasChunkAt(col)) return null;
        int y = a.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ());
        return new Vec3(col.getX() + 0.5, y, col.getZ() + 0.5);
    }
}
