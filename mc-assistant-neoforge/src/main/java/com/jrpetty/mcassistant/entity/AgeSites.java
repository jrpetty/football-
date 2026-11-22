package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

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
                if (!level.getFluidState(top).isEmpty() && level.getFluidState(top).is(net.minecraft.tags.FluidTags.WATER)) return 40;
            }
        }
        return 0;
    }
}
