package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [growth24] Whose a chunk the mod keeps loaded is, when a town goes to sleep (TownLod).
 *
 * <p>A town with nobody near sleeps: the chunks it and its folk keep loaded are let go, its folk are saved with them, and
 * its step simulates it at its rates. But a chunk was let go only if it lay outside the ground of every town still awake,
 * and a town's ground there is a square of its whole stores' reach and more (TownLod.areaChunks): sixteen chunks each way
 * for a town of sixty-five. In the four-hundred-day run the one town held awake (its heart force-loaded) had five
 * neighbours within that square; when they slept, every chunk their folk had asked for inside it stayed loaded, and so did
 * their folk, working away in full as if watched while their towns were also stepped at their rates: 250 to 330 folk of
 * sleeping towns loaded at once (the towns further off kept 2 to 60), and the server's cost a tick climbed with them from
 * 6 ms to 25-40 ms of its 50.
 *
 * <p>Now a request is judged by who made it: the town's own (its heart's ring, or one of its folk) is let go when that town
 * sleeps, wherever it lies; another town's folk's request is kept while that town is awake (its fields at the edge of its
 * neighbour's ground still work); and a request nobody can be put to (a trip's own, or a folk not loaded) goes with the
 * town whose heart is nearest of those whose ground covers the chunk. The same rule decides whether a request made while a
 * town sleeps is parked for its waking.
 */
public final class SleepClaims {

    private SleepClaims() {}

    /** The town a request's owner is: a town itself, or one of its folk loaded now; null when it cannot be told. */
    @Nullable
    static UUID townOf(ServerLevel level, UUID owner) {
        if (Villages.get(owner) != null) return owner;
        if (level.getEntity(owner) instanceof VillageFolkEntity f) return f.ownerId();
        UUID by = ASKED_BY.get(owner);                                   // [soak40] a trip's window: the town of the folk that asked
        if (by != null && Villages.get(by) != null) return by;
        return null;
    }

    /**
     * [soak40] The towns whose folk asked for a request under a name of its own (a caravan's, a scout's, a job seeker's
     * window as it walks, its own station while it is not loaded): kept as the request is made, from the folk whose tick
     * is making it (Accounts.actor). Such a request could not be put to anybody, and went with the town whose ground it
     * lay on: a caravan of an awake town crossing a sleeping colony's ground had its window let go (or parked for the
     * colony's waking) under it, and stood frozen in an unticked chunk, its courier's runs undone, for days (the soak's
     * Tybalt, a hundred and forty blocks out, on day forty-six).
     */
    private static final java.util.Map<UUID, UUID> ASKED_BY = new java.util.concurrent.ConcurrentHashMap<>();

    /** [soak40] ChunkLoad's word that this owner is asking for chunks now: whose folk asks, if a folk's tick is asking. */
    public static void asking(UUID owner) {
        VillageFolkEntity f = Accounts.actor();
        UUID town = f == null ? null : f.ownerId();
        if (town == null || town.equals(owner) || Villages.get(owner) != null) return;
        if (ASKED_BY.size() > 20000) ASKED_BY.clear();
        ASKED_BY.put(owner, town);
    }

    /** [soak40] Forget who asked (a world closing). */
    static void reset() {
        ASKED_BY.clear();
    }

    /** Of the towns in this level whose ground covers this chunk, the one whose heart is nearest; null for none. */
    @Nullable
    static Villages.Village nearestCovering(ServerLevel level, long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        Villages.Village best = null;
        long bestD = Long.MAX_VALUE;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int cx = v.centre().getX() >> 4, cz = v.centre().getZ() >> 4;
            int r = TownLod.areaChunks(v.id());
            if (Math.abs(x - cx) > r || Math.abs(z - cz) > r) continue;
            long d = (long) (x - cx) * (x - cx) + (long) (z - cz) * (z - cz);
            if (d < bestD) { bestD = d; best = v; }
        }
        return best;
    }

    /** Is this request (on the sleeper's ground) let go now that the sleeper sleeps? */
    static boolean letGo(ServerLevel level, Villages.Village sleeper, UUID owner, long chunk) {
        UUID town = townOf(level, owner);
        if (town != null) return town.equals(sleeper.id()) || TownLod.away(town);
        Villages.Village near = nearestCovering(level, chunk);
        return near == null || near.id().equals(sleeper.id()) || TownLod.away(near.id());
    }

    /**
     * The sleeping town a request made now is parked with (for its waking), or null to force it as ever: the owner's own
     * town if it is asleep; for an owner that cannot be told, the town nearest of those covering the chunk, if asleep.
     */
    @Nullable
    static UUID parkWith(ServerLevel level, UUID owner, long chunk) {
        UUID town = townOf(level, owner);
        if (town != null) return TownLod.away(town) ? town : null;
        Villages.Village near = nearestCovering(level, chunk);
        return near != null && TownLod.away(near.id()) ? near.id() : null;
    }
}
