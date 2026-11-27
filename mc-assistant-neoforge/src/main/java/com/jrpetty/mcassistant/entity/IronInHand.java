package com.jrpetty.mcassistant.entity;

import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [growth24] The town's iron at its smelters, counted with the iron in its stores for what the age asks.
 *
 * <p>The town's haulers take the stores' ore out to its smelters (thirty-two at a time) and the bars come back when they are
 * smelted. A local run's Iron Age town mined sixteen to twenty-eight iron a day, held four in its stores, and had forty-four
 * raw iron in one smelter's pack waiting on fuel: the age counted only the stores, so the iron the town had dug was not the
 * town's. The raw iron and the bars in its smelters' hands are the town's iron as much as the watch's armour is
 * (WatchKit.ironCredit): counted for the age's ask, never spent from.
 */
public final class IronInHand {

    private IronInHand() {}

    private static final Map<UUID, long[]> SEEN = new ConcurrentHashMap<>();

    /** Raw iron and bars in this town's smelters' packs (looked at once in ten seconds). */
    public static int held(net.minecraft.server.level.ServerLevel level, UUID village) {
        long now = level.getGameTime();
        long[] s = SEEN.get(village);
        if (s != null && now >= s[0] && now - s[0] < 200) return (int) s[1];
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.SMELT) continue;
            n += f.countCarried(st -> st.is(Items.RAW_IRON) || st.is(Items.IRON_INGOT));
        }
        SEEN.put(village, new long[]{ now, n });
        return n;
    }

    public static void forgetForTests() {
        SEEN.clear();
    }
}
