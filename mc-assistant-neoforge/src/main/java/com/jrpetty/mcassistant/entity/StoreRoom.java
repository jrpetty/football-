package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32] Room in the stores made before they are full, not after.
 *
 * <p>The stores grew a chest only when a load would not go in (Villages.growStores, from Market.intoStores and the depot):
 * the chest wanted a hand from the town's works to set it down, and till one came the load that did not fit went on the
 * ground at the heart (Crafts.store), or home again in a carrier's pack, and a farmer with a full pack stood at a full
 * storehouse. Now, looked at once a minute (Villages.weighGluts), stores with less than a tenth of their slots free (or
 * fewer than a chest's worth) ask for another chest at once, while there is still room for the day's loads: the hand
 * comes, the chest goes down, and the harvest never meets a full store.
 */
public final class StoreRoom {

    private StoreRoom() {}

    private static final Map<UUID, int[]> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LAST.clear();
        SCANNED.clear();
    }

    /** The last look: {slots, free, chests grown ahead}. */
    public static int[] last(UUID village) {
        int[] l = LAST.get(village);
        return l == null ? new int[3] : l.clone();
    }

    /** Free slots and all slots in the town's stores: {all, free}. */
    static int[] room(ServerLevel level, UUID village) {
        int all = 0, free = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                all++;
                if (c.getItem(i).isEmpty()) free++;
            }
        }
        return new int[]{ all, free };
    }

    /** Is the room running out: under a tenth free, or under a chest's worth? */
    static boolean tight(int all, int free) {
        return all > 0 && (free < 27 || free * 10 < all);
    }

    private static final Map<UUID, Long> SCANNED = new ConcurrentHashMap<>();

    /** The look (at each visit of the town's works, TownWork.tick; the stores counted once a minute): stores running out
     *  of room get another chest now. */
    public static void ahead(ServerLevel level, UUID village) {
        if (Villages.get(village) == null || TownLod.away(village)) return;
        int[] l = LAST.computeIfAbsent(village, k -> new int[3]);
        long now = level.getGameTime();
        Long at = SCANNED.get(village);
        if (at == null || now - at >= 1200 || now < at) {
            SCANNED.put(village, now);
            int[] r = room(level, village);
            l[0] = r[0];
            l[1] = r[1];
        }
        int[] r = { l[0], l[1] };
        if (!tight(r[0], r[1])) return;
        BlockPos grown = Villages.growStores(level, village);
        if (grown != null) {
            l[2]++;
            l[1] += 27;
            SCANNED.remove(village);
            Villages.tell(village, level.getDayTime() / 24000L, "the stores were filling up (" + r[1] + " of " + r[0]
                + " places free): another chest was set down in good time");
        }
    }
}
