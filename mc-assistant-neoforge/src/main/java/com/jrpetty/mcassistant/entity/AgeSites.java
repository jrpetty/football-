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

    public static void resetForTests() {
        MISSES.clear();
    }

    /** Has this age's building missed so many looks for its street that it may take a lot walked dry? */
    static boolean hungry(UUID village, String project) {
        return ASKED.contains(project) && MISSES.getOrDefault(village + "|" + project, 0) >= CityPlan.PATIENCE;
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
}
