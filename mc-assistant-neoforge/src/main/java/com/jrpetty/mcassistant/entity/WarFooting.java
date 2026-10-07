package com.jrpetty.mcassistant.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How a town readies itself for war, and who will fight for it.
 *
 * <p>[war] The shared seam between the town's preparations and the fighting. The town's way of working
 * on a war footing (the militia, the armoury, the siege stores, the curfew, the war chest) belongs to the
 * preparations work; the war bands draw their fighters from {@link #militia}. Until it is filled in, a
 * town's fighters are its watch.
 */
public final class WarFooting {

    private WarFooting() {}

    /** Is this town on a war footing (at war, or on its guard)? */
    public static boolean ready(UUID village) {
        return Wars.footing(village) != Wars.Footing.PEACE;
    }

    /** Everybody who would fight for this town now: its watch, and (once there is one) its militia called up. */
    public static List<VillageFolkEntity> militia(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.isAlive()
                    && f.stationTask() == AssistantEntity.StationTask.GUARD) out.add(f);
        }
        return out;
    }
}
