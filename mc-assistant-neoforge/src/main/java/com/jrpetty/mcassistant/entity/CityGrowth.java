package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [city26] A town that has filled its plan lays out the next ring of it.
 *
 * <p>The town plan (village/TownPlan) used to stop at three rings of blocks, ninety-one blocks out. A town of a hundred
 * filled them (or found the rest wet or up a cliff) and stood still for the rest of its days: on the long run the home
 * town sat at fifty-seven buildings for two hundred days with seventeen households waiting for a home. A real town grows
 * on outward: its streets run on past the last houses and new ones go up along them.
 *
 * <p>So the plan now runs to {@link TownPlan#MAX_RINGS} rings, but a town builds only on the rings it has laid out: the
 * first three at its founding, and the next one each time the ones it has are full, that is, once it has a fair size
 * (the third ring is already in its reach) and three looks in a row for a building's lot found nothing in them at all.
 * At most one ring a day, so one awkward building does not set the town sprawling. The new ring's streets are the old
 * ones run on (the same grid, so they join), the town wall goes out round it as houses go up there (CastleWall), and
 * its lots go round the farmland and the mine's ground as the old ones do (Villages.lotKeptOff). Kept in the town's
 * notes; told in its news.
 */
public final class CityGrowth {

    private CityGrowth() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** Where the town keeps how many rings of its plan it has laid out. */
    static final String NOTE = "plan.rings";
    /** Looks in a row that found no lot at all in the rings laid out, before the next is laid out. */
    static final int FULL_AFTER = 3;
    /** The fewest buildings a town has before it lays out a fourth ring (its third ring in reach: Villages.townReach). */
    static final int LEAST_BUILT = 45;
    /** At most one new ring in this many ticks (a day). */
    static final long GAP = 24000L;

    private static final Map<UUID, Integer> MISSES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() {
        MISSES.clear();
        LAST.clear();
    }

    /** How many rings of the plan this town has laid out (three till it has filled them). */
    public static int rings(@Nullable UUID village) {
        if (village == null) return TownPlan.RINGS;
        String n = Ledger.note(village, NOTE);
        if (n == null || n.isEmpty()) return TownPlan.RINGS;
        try {
            return Math.max(TownPlan.RINGS, Math.min(TownPlan.MAX_RINGS, Integer.parseInt(n.trim())));
        } catch (NumberFormatException e) {
            return TownPlan.RINGS;
        }
    }

    /** How far out this town's plan reaches, with the rings it has laid out. */
    public static int reach(@Nullable UUID village) {
        return TownPlan.reach(rings(village));
    }

    /** Is this lot on a ring the town has laid out? */
    public static boolean laidOut(UUID village, TownPlan.Lot lot) {
        return TownPlan.ringOf(lot) < rings(village);
    }

    /** Tests: as if a day had gone by since the last ring was laid out. */
    public static void sinceForTests(UUID village) {
        LAST.remove(village);
    }

    /** Tests and the admin: lay the plan out to this many rings. */
    public static void setRings(UUID village, int rings) {
        Ledger.note(village, NOTE, Integer.toString(Math.max(TownPlan.RINGS, Math.min(TownPlan.MAX_RINGS, rings))));
    }

    /**
     * A look for a building's lot is over (Villages.siteFor): found one, or found none on any ring laid out (not merely
     * waiting for ground to load). After {@link #FULL_AFTER} such looks in a row the next ring is laid out.
     */
    public static void looked(ServerLevel level, Villages.Village v, String project, boolean found, boolean waiting) {
        UUID id = v.id();
        if (found) {
            MISSES.remove(id);
            return;
        }
        if (waiting) return;
        int have = rings(id);
        if (have >= TownPlan.MAX_RINGS) return;
        int built = 0;
        for (String s : Villages.builtList(id)) if (!"colony".equals(s)) built++;
        if (built < LEAST_BUILT && !forTheAge(level, id, project)) return;   // [econ32] the age's own building waits on no count
        int misses = MISSES.merge(id, 1, Integer::sum);
        if (misses < FULL_AFTER) return;
        long now = level.getGameTime();
        Long last = LAST.get(id);
        if (last != null && now - last < GAP && now >= last) return;
        open(level, v, have + 1, project);
    }

    /**
     * [econ32] Is this a building the town's age asks for (Villages.needs: "a lighthouse", "a chapel")? A local run's Diamond
     * Age town of fifty with twenty-nine buildings found no lot for its lighthouse or its chapel on its three rings (its fields
     * and its mine's ground round them) and, short of the forty-five buildings a fourth ring waited on, never laid one out:
     * the age could never come.
     */
    static boolean forTheAge(ServerLevel level, UUID village, String project) {
        if (project == null || project.isEmpty()) return false;
        String plan = Themes.structure(project);
        for (Villages.Need n : Villages.needs(level, village)) {
            if (n.task() == Villages.Task.BUILD && (n.what().endsWith(" " + plan) || n.what().endsWith(" " + Villages.spoken(plan)))) return true;
        }
        return false;
    }

    /** Lay the plan out to this many rings, and say so. */
    static void open(ServerLevel level, Villages.Village v, int rings, String why) {
        UUID id = v.id();
        setRings(id, rings);
        MISSES.remove(id);
        LAST.put(id, level.getGameTime());
        CityPlan.forget(id);                         // the walk runs out over the new ring's streets
        LOG.info("[MCA-PLAN] {} lays out ring {} of its plan ({} blocks out) for want of a lot ({})",
            Villages.name(id), rings, TownPlan.reach(rings), why);
        Villages.tell(id, level.getDayTime() / 24000L, "the town has filled its streets, so the next ring of them is laid out, "
            + TownPlan.reach(rings) + " blocks from the square");
    }

    /** For the status line: the rings laid out, and the misses toward the next. */
    public static String report(UUID village) {
        return rings(village) + " of " + TownPlan.MAX_RINGS + " rings laid out" + (MISSES.getOrDefault(village, 0) > 0
            ? " (" + MISSES.get(village) + " looks with nothing free)" : "");
    }
}
