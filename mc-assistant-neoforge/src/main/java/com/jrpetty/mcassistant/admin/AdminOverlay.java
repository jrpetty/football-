package com.jrpetty.mcassistant.admin;

import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AdminTools.Done;
import com.jrpetty.mcassistant.entity.CastleWall;
import com.jrpetty.mcassistant.entity.OreNotes;
import com.jrpetty.mcassistant.entity.ShopBusiness;
import com.jrpetty.mcassistant.entity.StuckSpots;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.TownPlan;
import com.jrpetty.mcassistant.village.WallPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * [admin] Debug overlays for an operator, drawn in particles only that player sees, for a minute: the town plan's lots,
 * the wall's ring, the spots folk got stuck at, the ore the miners noted, the store and work chests. The Wanted/needs
 * view is words (what the age, the shops and the jobs want). Points are worked out once when the overlay starts and
 * sent every half second, never more than {@link #MOST_POINTS} a pulse.
 */
public final class AdminOverlay {

    private AdminOverlay() {}

    private static final int SECONDS = 60, PULSE = 10, MOST_POINTS = 600;
    /** Lots this far past the town's reach are left out (the plan runs on for ever). */
    private static final int LOT_MARGIN = 16;

    static Done show(AdminRunner.Ctx c, Villages.Village v, String view) {
        ServerPlayer p = c.src().getPlayer();
        if (view.equals("off")) {
            int n = 0;
            for (AdminJobs.Job j : AdminJobs.running()) if (j instanceof Overlay && j.src.getPlayer() == p && AdminJobs.cancel(j.id())) n++;
            return Done.said(n == 0 ? "No overlay on." : "Overlay off.");
        }
        ServerLevel level = AdminActions.levelOf(c, v);
        if (view.equals("wanted")) return Done.said(String.join("\n", wanted(level, v)));
        if (p == null) return Done.no("Overlays are drawn for a player; from the console try inspect town or debug audit.");
        List<BlockPos> points = points(level, v, view);
        if (points.isEmpty()) return Done.said("Nothing to show for " + view + " in " + Villages.name(v.id()) + ".");
        Overlay o = new Overlay(view + " overlay of " + Villages.name(v.id()), c, level, points, view);
        String[] why = { "" };
        if (AdminJobs.start(o, why) == null) return Done.no(why[0]);
        return Done.said("Showing " + view + " (" + points.size() + " points) for " + SECONDS + "s; debug overlay off to hide it");
    }

    static final class Overlay extends AdminJobs.Job {
        private final ServerLevel level;
        private final List<BlockPos> points;
        private final ParticleOptions particle;
        private int ticks;

        Overlay(String label, AdminRunner.Ctx c, ServerLevel level, List<BlockPos> points, String view) {
            super(label, c.src(), SECONDS * 20);
            this.level = level;
            this.points = points;
            this.particle = switch (view) {
                case "stuck" -> ParticleTypes.FLAME;
                case "ore" -> ParticleTypes.WAX_ON;
                case "chests" -> ParticleTypes.HEART;
                case "wall" -> ParticleTypes.END_ROD;
                default -> ParticleTypes.HAPPY_VILLAGER;
            };
        }

        @Override
        protected boolean tick(MinecraftServer server) {
            done = ++ticks;
            ServerPlayer p = src.getPlayer();
            if (p == null || p.serverLevel() != level) return true;
            if (ticks % PULSE == 0) {
                for (BlockPos b : points) level.sendParticles(p, particle, true, b.getX() + 0.5, b.getY() + 0.6, b.getZ() + 0.5, 1, 0, 0, 0, 0);
            }
            return ticks >= total;
        }
    }

    /** The points for a view, at most MOST_POINTS. */
    static List<BlockPos> points(ServerLevel level, Villages.Village v, String view) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos h = v.centre();
        switch (view) {
            case "lots" -> {
                int reach = Villages.townReach(v.id()) + LOT_MARGIN;
                for (TownPlan.Lot l : TownPlan.lots()) {
                    boolean turned = l.back() == TownPlan.EAST || l.back() == TownPlan.WEST;
                    int hx = turned ? l.halfDeep() : l.halfAcross(), hz = turned ? l.halfAcross() : l.halfDeep();
                    if (Math.abs(l.x()) - hx > reach || Math.abs(l.z()) - hz > reach) continue;
                    for (int dx = -hx; dx <= hx; dx += 2) { ground(level, h.offset(l.x() + dx, 0, l.z() - hz), out); ground(level, h.offset(l.x() + dx, 0, l.z() + hz), out); }
                    for (int dz = -hz; dz <= hz; dz += 2) { ground(level, h.offset(l.x() - hx, 0, l.z() + dz), out); ground(level, h.offset(l.x() + hx, 0, l.z() + dz), out); }
                }
            }
            case "wall" -> {
                WallPlan.Sides s = CastleWall.sides(v.id());
                if (s != null) {
                    List<int[]> cols = WallPlan.columns(s, WallPlan.towers(s));
                    int step = Math.max(1, cols.size() / MOST_POINTS);
                    for (int i = 0; i < cols.size(); i += step) ground(level, h.offset(cols.get(i)[0], 0, cols.get(i)[1]), out);
                }
            }
            case "stuck" -> { for (StuckSpots.Spot s : StuckSpots.spots(v.id())) out.add(s.at); }
            case "ore" -> out.addAll(OreNotes.near(level, h, 96));
            case "chests" -> {
                out.addAll(Villages.storeChests(level, v.id()));
                for (long l : AdminTools.workChests(v.id())) out.add(BlockPos.of(l));
            }
            default -> { }
        }
        return out.size() > MOST_POINTS ? new ArrayList<>(out.subList(0, MOST_POINTS)) : out;
    }

    private static void ground(ServerLevel level, BlockPos at, List<BlockPos> out) {
        if (out.size() >= MOST_POINTS || !level.isLoaded(at)) return;
        out.add(new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ()));
    }

    /** What the town wants: for its next age, from its shops' makers, and its open jobs. */
    static List<String> wanted(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        List<String> needs = AdminTools.needs(level, v);
        out.add("AGE " + (needs.isEmpty() ? "nothing more for the next age" : String.join("; ", needs)));
        List<String> shop = new ArrayList<>();
        for (ShopBusiness.Wanted w : ShopBusiness.wanted(level, v)) shop.add(w.count() + " " + w.sample().getHoverName().getString() + " at " + w.price());
        out.add("SHOPS " + (shop.isEmpty() ? "want nothing" : String.join(", ", shop)));
        var t = Villages.needed(v.id());
        out.add("JOBS the next hand would be a " + t.title);
        return out;
    }
}
