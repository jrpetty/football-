package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [life28] A time-lapse worth filming: the building going up nearest the camera goes up whole, from its footing to its
 * roof, in the half minute or so the time-lapse lasts, its builders round it at work the while.
 *
 * <p>A time-lapse only ran the world faster, and on a server that cannot run thirty-two times its pace (most) a builder
 * walked to the stores and back as often as it laid a block: the clip showed a villa's walls rise a course or two.
 * Cinematic licence, for an operator's camera only (TimeLapse asks the same as the speed keys): while it runs, the
 * building nearest the one who asked (a site the town has under way, within reach) is laid a course at a time from the
 * bottom up (Renovation's engine, bottom first, its makings taken as staged and not out of the stores), paced to finish
 * a little before the time-lapse does whatever the server's pace, and every builder of the town near it is stood round
 * it laying blocks (its arm and its act), not walking off to the stores. The camera goes to the site (CameraNetwork).
 */
public final class LapseBuild {

    private LapseBuild() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** How near the one who asked a site may be. */
    static final int REACH = 64;

    private record Target(UUID village, Ledger.Building b, int total, long from, long until) {}

    @Nullable private static Target target;
    private static int laid;

    /** The site the camera should frame (its middle, a little up), or null. */
    @Nullable
    public static BlockPos site() {
        return target == null ? null : target.b().anchor().above(3);
    }

    @Nullable
    public static String label() {
        return target == null ? null : "Building " + Villages.spoken(target.b().structure()).replaceFirst("^(the|a|an) ", "");
    }

    /** A time-lapse begun by this player for so many real seconds: the nearest site under way taken up, if any. */
    public static void begin(ServerPlayer p, int seconds) {
        target = null;
        laid = 0;
        if (!(p.level() instanceof ServerLevel level)) return;
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE * 2);
        if (v == null) return;
        Ledger.Building best = null;
        double bd = (double) REACH * REACH;
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(v.id()).entrySet()) {
            Villages.Site s = e.getValue();
            double d = s.anchor().distSqr(p.blockPosition());
            if (d >= bd || !com.jrpetty.mcassistant.entity.goal.Blueprints.has(e.getKey())) continue;
            // (One half up already is no time-lapse: a building from its footing is, below.)
            int all = 0, up = 0;
            for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement pl : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan(e.getKey(), s.anchor(), s.facing(), 13)) {
                if (pl.part() == com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.CLEAR) continue;
                all++;
                if (!level.getBlockState(pl.pos()).isAir()) up++;
            }
            if (up * 2 > all) continue;
            bd = d;
            best = new Ledger.Building(e.getKey(), s.anchor(), s.facing());
        }
        if (best == null) {
            // None under way near: the town's next building (a house, if it wants one) on the lot its plan gives it,
            // put on its register now and raised in the time-lapse from the bare plot.
            String next = Villages.projectsWanted(v.id()).contains("house") ? "house" : AdminTools.nextProject(v.id());
            Villages.Site s = next == null ? null : Villages.siteFor(level, v.id(), next);
            if (s == null || s.anchor().distSqr(p.blockPosition()) > (double) REACH * REACH * 4) return;
            AdminTools.fellTheLot(level, v, next, s.anchor(), s.facing());
            com.jrpetty.mcassistant.entity.goal.BuildGoal.clearWildPlants(level, next, s.anchor(), s.facing(), 8);
            Ledger.built(v.id(), next, s.anchor(), s.facing());
            Villages.noteProject(v.id(), next, level.getGameTime());
            best = new Ledger.Building(next, s.anchor(), s.facing());
        }
        int total = 0;
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement pl : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan(best.structure(), best.anchor(), best.facing(), 13)) {
            if (pl.part() != com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.CLEAR) total++;
        }
        long now = System.nanoTime();
        // Done a few seconds before the time-lapse is: the roof on, and a moment to look at it.
        long span = Math.max(8L, seconds - 5L) * 1_000_000_000L;
        target = new Target(v.id(), best, Math.max(1, total), now, now + span);
        LOG.info("[MCA-LAPSE] {}'s time-lapse: {} at {} goes up in {}s ({} blocks)", p.getName().getString(), best.structure(),
            best.anchor().toShortString(), span / 1_000_000_000L, total);
    }

    public static void end() {
        if (target != null) LOG.info("[MCA-LAPSE] the time-lapse over: {} blocks laid of {}", laid, target.total());
        target = null;
    }

    /** From the server's tick while a time-lapse runs. */
    public static void tick(MinecraftServer server) {
        Target t = target;
        if (t == null) return;
        Villages.Village v = Villages.get(t.village());
        ServerLevel level = v == null ? null : server.getLevel(v.dim());
        if (level == null || !level.isLoaded(t.b().anchor())) return;
        long now = System.nanoTime();
        double share = Math.min(1.0, (now - t.from()) / (double) (t.until() - t.from()));
        int due = (int) Math.ceil(t.total() * share) - laid;
        if (due > 0) {
            int n = Renovation.renovate(level, v, t.b(), Math.min(due, 48), true);
            if (n > 0) laid += n;
            if (n == 0 && share >= 1.0) {
                LOG.info("[MCA-LAPSE] {} is up: {} blocks", t.b().structure(), laid);
                target = null;
                return;
            }
        }
        if (server.getTickCount() % 10 == 0) builders(level, v, t);
    }

    /** The town's builders near the site stood round it, laying. */
    private static void builders(ServerLevel level, Villages.Village v, Target t) {
        BlockPos at = t.b().anchor();
        int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(t.b().structure());
        int r = Math.max(half[0], half[1]) + 2;
        List<VillageFolkEntity> crew = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping() || f.level() != level) continue;
            Job j = f.peekJob();
            boolean builder = j != null && j.type() == Job.Type.BUILD && j.arg() != null && j.arg().startsWith(t.b().structure());
            if (builder) crew.add(f);
            if (crew.size() >= 6) break;
        }
        // Short of builders: the nearest grown hands not at the watch or a fight make up the crew (it is the film's crew).
        if (crew.size() < 4) {
            List<VillageFolkEntity> near = new ArrayList<>();
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isSleeping() && f.level() == level && !crew.contains(f)
                    && f.getTarget() == null && f.stationTask() != AssistantEntity.StationTask.GUARD && f.blockPosition().distSqr(at) < 48 * 48) near.add(f);
            }
            near.sort(java.util.Comparator.comparingDouble(f -> f.blockPosition().distSqr(at)));
            for (int i = 0; i < near.size() && crew.size() < 4; i++) crew.add(near.get(i));
        }
        for (int i = 0; i < crew.size(); i++) {
            VillageFolkEntity f = crew.get(i);
            double ang = i * (Math.PI * 2 / Math.max(1, crew.size()));
            BlockPos stand = Land.surface(level, at.getX() + (int) Math.round(Math.cos(ang) * r), at.getZ() + (int) Math.round(Math.sin(ang) * r));
            if (stand == null) continue;
            if (f.blockPosition().distSqr(stand) > 4.0) f.walkTo(stand, 1.0D);
            else {
                f.getNavigation().stop();
                f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 2.0, at.getZ() + 0.5);
                f.swing(InteractionHand.MAIN_HAND);
                FolkAction.play(f, FolkAction.Act.BUILD, 30);
            }
        }
    }
}
