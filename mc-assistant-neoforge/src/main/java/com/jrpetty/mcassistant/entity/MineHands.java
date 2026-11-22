package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [growth24] The miners kept at the mine while the town's age waits on what comes out of it.
 *
 * <p>With the works' levels no longer running out (MineLevels), a local run's town of fifty-nine came into the Iron Age on
 * day 26 with six to ten miners and sat on 0 to 8 iron for a week: the works booked three shifts in five days. Its miners'
 * days went to the town's work (levelling and filling for the next lots: a fifth of a day), to lending a hand at the logs
 * (nearly half of one miner's day) and to walking; the hours down the works were a tenth of their day or less. While the
 * age asks for iron or diamonds (Villages.needs), a miner is not called to the town's works (TownJobs.choose) and, with
 * nothing in its own line for a moment, waits for its next shift rather than being lent out (lendAHand). Not for the Stone
 * Age's coal: a Stone Age town short of coal is short of timber for its houses as often as not, and its miners' hands at
 * the logs were what built them (a local run's town kept them at the mine and stood a week at eleven buildings, no logs).
 */
public final class MineHands {

    private MineHands() {}

    private static final Map<UUID, long[]> KEPT = new ConcurrentHashMap<>();
    /** Looked at once a minute a town. */
    private static final long EVERY = 1200L;

    /** Is the town's age waiting on the mine now (iron or diamonds on its list)? */
    public static boolean kept(ServerLevel level, @Nullable UUID village) {
        if (village == null || Villages.get(village) == null) return false;
        long now = level.getGameTime();
        long[] k = KEPT.get(village);
        if (k != null && now >= k[0] && now - k[0] < EVERY) return k[1] != 0;
        boolean on = false;
        for (Villages.Need n : Villages.needs(level, village)) {
            Villages.Task t = n.task();
            if (t == Villages.Task.IRON || t == Villages.Task.DIAMOND) { on = true; break; }
        }
        KEPT.put(village, new long[]{ now, on ? 1 : 0 });
        return on;
    }

    /** Tests: look afresh at the next asking. */
    public static void forgetForTests() {
        KEPT.clear();
    }
}
