package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [growth22] A town grown full keeps making room. The town of the four-hundred-day run on 0.319.0 had built every lot its
 * plan could use (212 lots, the rest of them over its river or up a cliff: "no lot for the house, lap 510", and for the
 * flats and the town hall too), seven households waiting for a home and eight folk lodging in spare beds; and it was careful
 * of its money (its wages hardly paid), so Grow did nothing at all: no garden, no refacing, and no second storey, which was
 * the only room it had left to grow into. A town careful of its money still puts off its gardens and makeovers, but a home
 * is not a makeover (TownBudget counts homes and beds among the essentials): while households wait for one, the council's
 * houses are raised a storey, one at a time, in what the stores can pay for.
 */
public final class FullTown {

    private FullTown() {}

    /** Households waiting for a home, by town, and when it was counted (once a minute). */
    private static final Map<UUID, long[]> WAITING = new ConcurrentHashMap<>();

    /** How many households wait for a home of their own (counted at most once a minute). */
    static int waiting(ServerLevel level, UUID village) {
        long now = level.getGameTime();
        long[] c = WAITING.get(village);
        if (c != null && now >= c[0] && now - c[0] < 1200L) return (int) c[1];
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isShowcase()) folk.add(f);
        int n = Homes.waiting(village, folk, level.getDayTime() / 24000L).size();
        WAITING.put(village, new long[]{ now, n });
        return n;
    }

    /** [Grow.work, the town careful of its money] Its next council house raised a storey, if households wait for homes. */
    static int storeysOnly(ServerLevel level, Villages.Village v, Villages.Age age, int budget) {
        UUID id = v.id();
        if (age.ordinal() < Villages.Age.IRON.ordinal() || waiting(level, id) == 0) return 0;
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (!b.structure().equals("house") || !Land.areaLoaded(level, b.anchor(), 7)) continue;
            if (HousingMarket.isPrivate(id, b.anchor()) || Beliefs.untouchable(id, b) || Ledger.grown(id, b.anchor())) continue;
            return Grow.storey(level, v, b, budget, Grow.palette(id, age), false);   // one house at a time
        }
        return 0;
    }

    public static int waitingForTests(ServerLevel level, UUID village) {
        WAITING.remove(village);
        return waiting(level, village);
    }

    public static void forgetForTests() {
        WAITING.clear();
    }
}
