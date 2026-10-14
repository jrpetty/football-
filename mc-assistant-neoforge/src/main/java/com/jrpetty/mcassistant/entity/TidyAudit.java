package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [tidy] The tidy checks of a town, in the shape of the TownAudit harness ([integrity]: TownAudit.check(level,
 * village) -> Report): every eyesore about the town by kind, the camp beds still out once everybody is housed, the
 * grass and earth inside its buildings, what is lying about its streets, and its looks. To be folded into TownAudit
 * as its tidy checks once that lands ("no grass inside buildings", "eyesores", "looks").
 */
public final class TidyAudit {

    private TidyAudit() {}

    /** What the audit found. */
    public record Report(int eyesores, Map<Eyesores.Kind, Integer> byKind, int campBedsHoused, int earthInside, int plantsInside,
                         int lying, Tidy.Looks looks, List<Eyesores.Spot> spots) {

        /** No grass, earth or plant inside any building. */
        public boolean noGrassInside() {
            return earthInside == 0 && plantsInside == 0;
        }

        /** The audit's lines, for a test's log or a report. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add("looks " + looks.score() + " (" + looks.word() + "): tidiness " + looks.tidiness() + "/40, greenery " + looks.greenery()
                + "/15, lighting " + looks.lighting() + "/15, flowers " + looks.flowers() + "/10, finished " + looks.finished() + "/20"
                + (looks.worst().isEmpty() ? "" : "; worst: " + looks.worst()));
            out.add("eyesores " + eyesores + " " + byKind + "; camp beds out with everybody housed " + campBedsHoused
                + "; inside the buildings " + earthInside + " earth, " + plantsInside + " plants; lying about " + lying);
            return out;
        }
    }

    /** The town audited now (walked at once). */
    @Nullable
    public static Report check(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        Tidy.Looks looks = Tidy.measureForTests(level, v);
        Eyesores.Walk w = Eyesores.last(village);
        if (w == null) w = new Eyesores.Walk();
        Map<Eyesores.Kind, Integer> by = new EnumMap<>(Eyesores.Kind.class);
        by.putAll(w.counts);
        int[] inside = Floors.count(level, village);
        return new Report(w.total(), by, w.count(Eyesores.Kind.CAMP_BED), inside[0], inside[1], Sweepers.lying(level, v), looks,
            List.copyOf(w.spots));
    }
}
