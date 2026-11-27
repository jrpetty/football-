package com.jrpetty.mcassistant.client;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.entity.Entity;

/**
 * [wagons] Who is carrying a crate to or from a wagon just now, as the client knows it (TradeWagon tells it each tick
 * from its synced word): the folk's arms go out in front and a crate is drawn in them (FolkModel, WagonClient.CrateLayer).
 * Plain collections only (no client classes), so the wagon's common code may call it; looked up in O(1), nothing made.
 */
public final class WagonClientHooks {

    private WagonClientHooks() {}

    private static final IntOpenHashSet CARRYING = new IntOpenHashSet();
    private static final Int2IntOpenHashMap BY_WAGON = new Int2IntOpenHashMap();

    static {
        BY_WAGON.defaultReturnValue(-1);
    }

    /** This wagon's carrier is now this folk (an entity id), or nobody (-1). */
    public static void carrying(Entity wagon, int folk) {
        int was = BY_WAGON.get(wagon.getId());
        if (was == folk) return;
        if (was >= 0) CARRYING.remove(was);
        if (folk >= 0) {
            CARRYING.add(folk);
            BY_WAGON.put(wagon.getId(), folk);
        } else {
            BY_WAGON.remove(wagon.getId());
        }
    }

    /** Is this folk carrying a wagon's crate? */
    public static boolean isCarrying(int folk) {
        return !CARRYING.isEmpty() && CARRYING.contains(folk);
    }
}
