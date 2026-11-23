package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32b] Where the buildings the ages ask for go.
 *
 * <p>The buildings an age asks for before the next (Villages.needs): the storage, the shelter, the houses and the well; the
 * wall, the smeltery and the hall; the workshop, the watchtower and the market; the lighthouse and the chapel; the gateway.
 * A lighthouse is a light for the folk out after dark to find their way home by: on a town by the water it goes on the edge
 * lot nearest the water ({@link #shore}: the water's edge counts for it as forty blocks of building work saved); a town with
 * no water near it puts it on its edge lot all the same, as a beacon over the fields, rather than waiting for a coast it
 * will never have.
 */
public final class AgeSites {

    private AgeSites() {}

    /** Every building an age asks for (Villages.wantsFor), the wall aside (it rings the heart, no lot). */
    public static final List<String> ASKED = List.of("storage", "shelter", "house", "well", "smeltery", "hall", "workshop", "watchtower",
        "market", "lighthouse", "chapel", "gateway");

    /** How far round a lot's middle the lighthouse looks for water. */
    static final int SHORE = 20;

    /** What water near a lot is worth to this building, in blocks of building work (taken off its score; 0 for most). */
    static int shore(ServerLevel level, String project, int x, int z) {
        if (!"lighthouse".equals(project)) return 0;
        for (int dx = -SHORE; dx <= SHORE; dx += 4) {
            for (int dz = -SHORE; dz <= SHORE; dz += 4) {
                int cx = x + dx, cz = z + dz;
                if (!level.hasChunk(cx >> 4, cz >> 4)) continue;
                BlockPos top = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(cx, 0, cz)).below();
                if (!level.getFluidState(top).isEmpty() && level.getFluidState(top).is(net.minecraft.tags.FluidTags.WATER)) return 400;
            }
        }
        return 0;
    }

    /**
     * Looks in a row that found nothing but lots off the walked streets, by town and building. The town's own count
     * (CityPlan.hungry) starts again at every lot found for anything: a busy Diamond Age town set out a library, a tavern
     * and a cafe while its chapel, too big for every lot on its walked streets, waited a hundred looks for a turn at the
     * lots up the hill. An age's building that has missed so many looks of its own takes a lot whose street is walked dry
     * (steps cut into the bank, never across the water) as an island town does.
     */
    private static final Map<String, Integer> MISSES = new ConcurrentHashMap<>();
    static final int PATIENCE = 4;

    public static void resetForTests() {
        MISSES.clear();
        HELD.clear();
        BARE.clear();
    }

    /** Has this age's building missed so many looks for its street that it may take a lot walked dry? */
    static boolean hungry(UUID village, String project) {
        // [econ32d] Four looks, not the town's twelve: an age's building that finds no lot is set aside five minutes between
        // looks (Villages.defer), and twelve of them were an hour of the age standing still (and a restart began them again).
        return ASKED.contains(project) && MISSES.getOrDefault(village + "|" + project, 0) >= PATIENCE;
    }

    /** A look for this building is over (Villages.siteFor). */
    static void looked(UUID village, String project, boolean found, boolean passedOver) {
        if (!ASKED.contains(project)) return;
        String k = village + "|" + project;
        if (found) MISSES.remove(k);
        else if (passedOver) MISSES.merge(k, 1, Integer::sum);
    }

    // ------------------------------------------------------------------ [econ32c] the age's building's ground kept

    private static final String SITE = "age.site.";

    /**
     * The ground an age's building was set out on, kept with the town (a restart forgot the sites of the buildings going
     * up, and a Diamond Age town's chapel, 523 blocks of it laid, was begun again on another lot, and again after the next).
     */
    static void keep(UUID village, String project, Villages.Site site) {
        if (!ASKED.contains(project) || project.equals("house")) return;
        com.jrpetty.mcassistant.village.Ledger.note(village, SITE + project, site.anchor().getX() + "," + site.anchor().getY() + "," + site.anchor().getZ()
            + "," + site.facing().get3DDataValue() + "," + site.radius());
    }

    /** The ground kept for this building, or null. */
    @javax.annotation.Nullable
    static Villages.Site kept(UUID village, String project) {
        if (!ASKED.contains(project)) return null;
        String raw = com.jrpetty.mcassistant.village.Ledger.note(village, SITE + project);
        if (raw == null || raw.isEmpty()) return null;
        try {
            String[] p = raw.split(",");
            return new Villages.Site(new net.minecraft.core.BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])),
                net.minecraft.core.Direction.from3DDataValue(Integer.parseInt(p[3])), Integer.parseInt(p[4]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Built, or its ground given up: the note let go. */
    static void forget(UUID village, String project) {
        com.jrpetty.mcassistant.village.Ledger.forget(village, SITE + project);
    }

    private static final Map<String, Integer> HELD = new ConcurrentHashMap<>();

    /** Keep an age's building's ground through a builder's failure to reach it (Villages.rejectSite): true, five times. */
    static boolean holdGround(UUID village, String project) {
        Villages.Site site = kept(village, project);
        if (site == null || !begun(project, site)) return false;      // nothing laid yet: another lot, as ever
        return HELD.merge(village + "|" + project, 1, Integer::sum) <= 5;
    }

    /** Has a good part of it been laid on its ground (a score of its blocks standing where its drawing puts them)? */
    static boolean begun(String project, Villages.Site site) {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        net.minecraft.server.level.ServerLevel level = server.overworld();
        int laid = 0;
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p
                : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan(project, site.anchor(), site.facing(), Math.max(site.radius(), 13))) {
            if (p.part() == com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.CLEAR || p.pos().getY() <= site.anchor().getY()
                || !level.hasChunkAt(p.pos())) continue;                // its walls, not the ground it stands on
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p.pos());
            if (st.isAir() || !st.getFluidState().isEmpty() || st.canBeReplaced() || st.is(net.minecraft.tags.BlockTags.DIRT)
                || st.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || st.is(net.minecraft.tags.BlockTags.SAND)
                || st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.LEAVES)
                || st.is(net.minecraft.world.level.block.Blocks.GRAVEL)) continue;   // the world's own, not a wall
            if (++laid >= 20) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ [econ32d] the town's edge

    /** Looks with no lot of the plan that would do before an age's building goes up at the town's edge. */
    static final int EDGE_AFTER = 6;
    private static final Map<String, Integer> BARE = new ConcurrentHashMap<>();

    /**
     * An age's building that no lot of the plan will take, look after look, set out at the town's edge, its door to the town
     * (as a knight's manor is: KnightManors.find). A Diamond Age river town of seventy had forty-four lots big enough for its
     * chapel, every one under its fields or its mine, on the wall's ring, across the river, against a neighbour or on a cliff;
     * without the chapel the age could never come. Null until it has missed {@link #EDGE_AFTER} looks, or with no ground.
     */
    @javax.annotation.Nullable
    static Villages.Site edge(ServerLevel level, Villages.Village v, String project) {
        if (!ASKED.contains(project) || project.equals("house") || project.equals("well")) return null;
        String k = v.id() + "|" + project;
        if (BARE.merge(k, 1, Integer::sum) < EDGE_AFTER) return null;
        Villages.Site s = edgeGround(level, v, project);
        if (s == null && SiteWhy.LOG) com.mojang.logging.LogUtils.getLogger().info("[MCA-SITEWHY] {}: no ground at the town's edge for the {} either",
            Villages.name(v.id()), project);
        if (s != null) {
            BARE.remove(k);
            com.mojang.logging.LogUtils.getLogger().info("[MCA-BUILD] {}: the {} goes up at the town's edge, at {}, {} (no lot of the plan would take it)",
                Villages.name(v.id()), project, s.anchor().getX(), s.anchor().getZ());
        }
        return s;
    }

    /** Ground at the town's edge for it: rings out past its buildings (its colonies aside) in sixteen directions, the nearest
     *  first; level and dry (KnightManors.level), clear of its buildings and the ground set out for others, off its fields,
     *  its mine, its wall's ring, its railways, the flood's ground, a player's building and any other town's. */
    @javax.annotation.Nullable
    static Villages.Site edgeGround(ServerLevel level, Villages.Village v, String project) {
        java.util.UUID id = v.id();
        BlockPos c = v.centre();
        int[] half = com.jrpetty.mcassistant.entity.goal.Blueprints.has(project)
            ? com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf(project) : com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(project);
        int mine = Math.max(half[0], half[1]);
        int reach = 16;
        List<com.jrpetty.mcassistant.village.Ledger.Building> built = com.jrpetty.mcassistant.village.Ledger.buildings(id);
        for (com.jrpetty.mcassistant.village.Ledger.Building b : built) {
            if (Footways.skip(b.structure())) continue;                   // the wall, the storehouse in its shed, the colonies far off
            int[] h = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(b.structure());
            double d = Math.sqrt(b.anchor().distSqr(new BlockPos(c.getX(), b.anchor().getY(), c.getZ())));
            if (d > 3 * Villages.VILLAGE_RANGE) continue;
            reach = Math.max(reach, (int) Math.ceil(d) + Math.max(h[0], h[1]));
        }
        Map<String, Villages.Site> others = Villages.sitesOf(id);
        for (int ring = reach + mine + 4; ring <= reach + mine + 40; ring += 6) {
            for (int i = 0; i < 16; i++) {
                double ang = Math.PI * 2 * i / 16.0;
                int x = c.getX() + (int) Math.round(Math.cos(ang) * ring), z = c.getZ() + (int) Math.round(Math.sin(ang) * ring);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                net.minecraft.core.Direction back = Math.abs(Math.cos(ang)) >= Math.abs(Math.sin(ang))
                    ? (Math.cos(ang) > 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
                    : (Math.sin(ang) > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
                boolean turned = back.getAxis() == net.minecraft.core.Direction.Axis.X;
                int hx = turned ? half[1] : half[0], hz = turned ? half[0] : half[1];
                BlockPos ground = KnightManors.level(level, x, z, hx, hz);
                if (ground == null) continue;
                boolean clear = true;
                for (com.jrpetty.mcassistant.village.Ledger.Building b : built) {
                    if (Footways.skip(b.structure())) continue;
                    int[] h = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(b.structure());
                    if (Math.abs(b.anchor().getX() - x) <= h[0] + hx + 4 && Math.abs(b.anchor().getZ() - z) <= h[1] + hz + 4) { clear = false; break; }
                }
                for (Map.Entry<String, Villages.Site> o : others.entrySet()) {
                    if (o.getKey().equals(project)) continue;
                    BlockPos a = o.getValue().anchor();
                    if (Math.abs(a.getX() - x) <= 2 * mine + 6 && Math.abs(a.getZ() - z) <= 2 * mine + 6) { clear = false; break; }
                }
                if (!clear) continue;
                int dx = x - c.getX(), dz = z - c.getZ();
                if (Villages.onFarmland(id, dx, dz, hx, hz) || MineGround.anyMeets(x - hx - 1, z - hz - 1, x + hx + 1, z + hz + 1)
                    || CastleWall.onRing(id, dx, dz, hx, hz) || Railways.crosses(id, x - hx, z - hz, x + hx, z + hz)
                    || Floods.lowGround(id, ground) || PlayerGap.crowds(level, id, ground, hx, hz)) continue;
                Villages.Village other = Villages.nearest(level, ground, Villages.VILLAGE_RANGE);
                if (other != null && !other.id().equals(id)) continue;
                if (VanillaVillages.meets(level, x - hx, z - hz, x + hx, z + hz, VanillaVillages.MARGIN)) continue;
                return new Villages.Site(ground, back, 0);
            }
        }
        return null;
    }

    /** Tests: a look with no lot of the plan for it (edge). */
    @javax.annotation.Nullable
    public static Villages.Site edgeForTests(ServerLevel level, Villages.Village v, String project) {
        return edge(level, v, project);
    }

    /** Tests: the edge's count for this building, as if it had missed so many looks. */
    public static void bareForTests(java.util.UUID village, String project, int looks) {
        BARE.put(village + "|" + project, looks);
    }
}
