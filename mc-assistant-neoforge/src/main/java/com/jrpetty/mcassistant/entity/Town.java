package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Town hall bookkeeping shared across a player's whole crew. Right now it holds
 * the active town center — the Job Board a player last activated — which the
 * crew's autonomous role coordination organizes around (divide labour, pool to
 * the depot beside it). Kept deliberately small; it's the seed of the wider
 * "work with other AIs" cooperation layer.
 *
 * State is per-owner and in-memory (re-established by right-clicking the board),
 * so a server restart just needs one more click to re-activate the town.
 */
public final class Town {

    private Town() {}

    private static final Map<UUID, BlockPos> CENTER = new ConcurrentHashMap<>();

    /** Mark a Job Board as this owner's town center. */
    public static void setCenter(UUID owner, BlockPos pos) {
        if (owner != null && pos != null) CENTER.put(owner, pos.immutable());
    }

    /** The owner's active town center (Job Board), or null if none set. */
    @Nullable
    public static BlockPos center(UUID owner) {
        return owner == null ? null : CENTER.get(owner);
    }

    // ------------------------------ needs board ------------------------------
    // Per-owner demand for raw materials. A blocked member (or the foreman
    // watching the depot) posts what the town is short on; idle members whose
    // trade fits claim a chunk and haul it to the depot. It's a demand signal,
    // not exact accounting — over-supply is fine, shortfalls simply get re-posted.

    private static final int NEED_CAP = 256;
    private static final Map<UUID, EnumMap<GatherGoal.Kind, Integer>> NEEDS = new ConcurrentHashMap<>();

    /** Post (or top up) demand for a material. Held as a high-water mark, capped. */
    public static synchronized void postNeed(UUID owner, GatherGoal.Kind kind, int amount) {
        if (owner == null || kind == null || amount <= 0) return;
        NEEDS.computeIfAbsent(owner, k -> new EnumMap<>(GatherGoal.Kind.class))
             .merge(kind, Math.min(amount, NEED_CAP), (a, b) -> Math.min(NEED_CAP, Math.max(a, b)));
    }

    /** Claim the most-wanted material this worker can fetch, decrementing the
     *  board by `chunk`. Returns the kind claimed, or null if nothing fits. */
    @Nullable
    public static synchronized GatherGoal.Kind claimNeed(UUID owner, Predicate<GatherGoal.Kind> canDo, int chunk) {
        Map<GatherGoal.Kind, Integer> board = owner == null ? null : NEEDS.get(owner);
        if (board == null) return null;
        GatherGoal.Kind pick = null;
        int best = 0;
        for (Map.Entry<GatherGoal.Kind, Integer> e : board.entrySet()) {
            if (e.getValue() > best && canDo.test(e.getKey())) { best = e.getValue(); pick = e.getKey(); }
        }
        if (pick == null) return null;
        int left = board.get(pick) - chunk;
        if (left <= 0) board.remove(pick); else board.put(pick, left);
        return pick;
    }
}
