package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
}
