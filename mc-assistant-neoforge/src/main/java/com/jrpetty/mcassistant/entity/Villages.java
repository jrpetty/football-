package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village register: which settlement a folk belongs to, where its heart is,
 * and — the part that makes a village a village rather than ten strangers —
 * which trade it is currently short of.
 *
 * <p>A village's id doubles as the "owner" every folk in it shares. That is not
 * a trick for its own sake: every piece of crew machinery in this mod is keyed
 * on the owner — the claim book so two hands never walk to the same block, the
 * field-banding, the shared finds, the worn paths, the shift handover. Giving a
 * village one id hands all of it to the folk for free, and keeps one village's
 * business out of the next one's.
 *
 * <p>Nothing here is saved. It does not need to be: every folk persists its own
 * village id and centre, and the first one to load re-registers the settlement.
 */
public final class Villages {

    private Villages() {}

    public record Village(UUID id, BlockPos centre) {}

    private static final Map<UUID, Village> ALL = new ConcurrentHashMap<>();

    /** How far apart two settlements have to be to be two settlements. */
    public static final int VILLAGE_RANGE = 96;

    /** A full village, and the trades it wants in it. Four farmers feed the
     *  rest, three miners keep the stone and metal coming, two woodcutters
     *  supply every build, and one smelter turns the ore into tools. */
    public static final int VILLAGE_SIZE = 10;

    private static final Map<AssistantEntity.StationTask, Integer> RATIO =
        new EnumMap<>(Map.of(
            AssistantEntity.StationTask.FARM, 4,
            AssistantEntity.StationTask.MINE, 3,
            AssistantEntity.StationTask.WOOD, 2,
            AssistantEntity.StationTask.SMELT, 1));

    public static void register(Village village) {
        ALL.put(village.id(), village);
    }

    @Nullable
    public static Village get(@Nullable UUID id) {
        return id == null ? null : ALL.get(id);
    }

    /** The settlement whose heart is nearest this spot, if there is one close
     *  enough to walk to. */
    @Nullable
    public static Village nearest(Level level, BlockPos pos) {
        Village best = null;
        double bestDist = Double.MAX_VALUE;
        for (Village v : ALL.values()) {
            double d = v.centre().distSqr(pos);
            if (d > (double) VILLAGE_RANGE * VILLAGE_RANGE || d >= bestDist) continue;
            bestDist = d;
            best = v;
        }
        return best;
    }

    public static Village found(BlockPos centre) {
        Village v = new Village(UUID.randomUUID(), centre.immutable());
        ALL.put(v.id(), v);
        return v;
    }

    /** Everyone alive who belongs to this settlement. */
    public static List<AssistantEntity> folkOf(@Nullable UUID villageId) {
        if (villageId == null) return List.of();
        List<AssistantEntity> out = new ArrayList<>();
        for (AssistantEntity a : AssistantEntity.allFor(villageId)) {
            if (a.isAlive()) out.add(a);
        }
        return out;
    }

    public static int headcount(@Nullable UUID villageId) {
        return folkOf(villageId).size();
    }

    /**
     * The trade this settlement most needs the next pair of hands to take up.
     * Whichever trade is furthest below its share of the village gets the
     * newcomer — so ten folk settle into four farmers, three miners, two
     * woodcutters and a smelter without anyone being told, and a village that
     * loses its smelter replaces it with the next one to look for work.
     */
    public static AssistantEntity.StationTask needed(@Nullable UUID villageId) {
        List<AssistantEntity> folk = folkOf(villageId);
        Map<AssistantEntity.StationTask, Integer> have =
            new EnumMap<>(AssistantEntity.StationTask.class);
        for (AssistantEntity a : folk) {
            if (a.stationTask() != AssistantEntity.StationTask.NONE) {
                have.merge(a.stationTask(), 1, Integer::sum);
            }
        }
        // Count the newcomer itself, so the very first folk in an empty village
        // works out its share against a village of one and takes the farm.
        int total = Math.max(1, folk.size());
        AssistantEntity.StationTask best = AssistantEntity.StationTask.FARM;
        double bestDeficit = -Double.MAX_VALUE;
        for (Map.Entry<AssistantEntity.StationTask, Integer> want : RATIO.entrySet()) {
            double target = want.getValue() * total / (double) VILLAGE_SIZE;
            double deficit = target - have.getOrDefault(want.getKey(), 0);
            // Ties break toward the trade the village wants most of, which
            // keeps a young settlement growing food before it grows anything
            // else.
            if (deficit > bestDeficit
                || (deficit == bestDeficit && want.getValue() > RATIO.getOrDefault(best, 0))) {
                bestDeficit = deficit;
                best = want.getKey();
            }
        }
        return best;
    }

    // ---- the settlement's own building work, kept to one project at a time ----

    private static final Map<UUID, Long> LAST_PROJECT = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> BUILT = new ConcurrentHashMap<>();

    /** Long enough that a village grows over days rather than minutes. A
     *  settlement that threw up every building the hour it was founded would
     *  not feel like one. */
    private static final long PROJECT_GAP = 9600L;   // eight minutes

    public static boolean projectDue(UUID villageId, long gameTime) {
        return gameTime - LAST_PROJECT.getOrDefault(villageId, -PROJECT_GAP) >= PROJECT_GAP;
    }

    public static void noteProject(UUID villageId, String structure, long gameTime) {
        LAST_PROJECT.put(villageId, gameTime);
        BUILT.computeIfAbsent(villageId, k -> new ArrayList<>()).add(structure);
    }

    private static int built(UUID villageId, String structure) {
        int n = 0;
        for (String s : BUILT.getOrDefault(villageId, List.of())) {
            if (s.equals(structure)) n++;
        }
        return n;
    }

    /**
     * What the settlement should put up next, or null when it is content for
     * now. Storage first — nowhere to put anything is the first thing that
     * hurts — then a roof per few folk, then the workshop and the smeltery
     * that the trades actually use.
     */
    @Nullable
    public static String nextProject(UUID villageId) {
        int folk = headcount(villageId);
        if (folk == 0) return null;
        if (built(villageId, "storage") < 1) return "storage";
        if (built(villageId, "house") < Math.max(1, folk / 3)) return "house";
        boolean smelter = false;
        for (AssistantEntity a : folkOf(villageId)) {
            if (a.stationTask() == AssistantEntity.StationTask.SMELT) { smelter = true; break; }
        }
        if (smelter && built(villageId, "smeltery") < 1) return "smeltery";
        if (folk >= 4 && built(villageId, "workshop") < 1) return "workshop";
        if (folk >= 8 && built(villageId, "watchtower") < 1) return "watchtower";
        return null;
    }
}
