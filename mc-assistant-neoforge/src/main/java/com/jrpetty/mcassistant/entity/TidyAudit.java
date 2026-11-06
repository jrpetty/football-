package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [tidy] The tidy checks of a town: every eyesore about the town by kind, the camp beds still out once everybody is
 * housed, the grass and earth inside its buildings, what is lying about its streets, and its looks. Registered into
 * the TownAudit harness ([integrity]) as "grass_inside" (no grass inside buildings), "eyesores" and "looks"; and
 * here as one typed report for the tidy tests.
 */
public final class TidyAudit {

    private TidyAudit() {}

    /** The checks' names in the TownAudit report. */
    public static final String GRASS_INSIDE = "grass_inside", EYESORES = "eyesores", LOOKS = "looks";

    /** The tidy checks, into the TownAudit harness: "no grass inside buildings" (a finding for every cell of earth where a
     *  floor is drawn, or plant on a floor; measure grass_inside), the eyesores (a finding each; measures eyesores and
     *  camp_beds_housed), and the looks (measures looks and its parts). */
    static void registerChecks() {
        TownAudit.register(GRASS_INSIDE, (level, v, r) -> {
            int n = 0;
            for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(v.id())) {
                for (Floors.Fix f : Floors.inside(level, v.id(), b)) {
                    r.find(GRASS_INSIDE, f.pos(), (f.lay() ? "earth where the floor is drawn" : "a plant on the floor") + " in the "
                        + b.structure() + " at " + b.anchor().toShortString());
                    n++;
                }
            }
            r.measure(GRASS_INSIDE, n);
        });
        TownAudit.register(EYESORES, (level, v, r) -> {
            Eyesores.Walk w = Eyesores.walkNow(level, v);
            for (Eyesores.Spot s : w.spots) r.find(EYESORES, s.pos(), s.kind().words + " (" + s.note() + "): " + Eyesores.cause(level, s));
            r.measure(EYESORES, w.total());
            r.measure("camp_beds_housed", w.count(Eyesores.Kind.CAMP_BED));
            r.measure("lying", Sweepers.lying(level, v));
        });
        TownAudit.register(LOOKS, (level, v, r) -> {
            TownLooks.Looks l = TownLooks.measureNow(level, v);
            r.measure(LOOKS, l.score());
            r.measure("looks_tidiness", l.tidiness());
            r.measure("looks_greenery", l.greenery());
            r.measure("looks_lighting", l.lighting());
            r.measure("looks_flowers", l.flowers());
            r.measure("looks_finished", l.finished());
        });
    }

    /** What the audit found. */
    public record Report(int eyesores, Map<Eyesores.Kind, Integer> byKind, int campBedsHoused, int earthInside, int plantsInside,
                         int lying, TownLooks.Looks looks, List<Eyesores.Spot> spots) {

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
        TownLooks.Looks looks = TownLooks.measureNow(level, v);
        Eyesores.Walk w = Eyesores.last(village);
        if (w == null) w = new Eyesores.Walk();
        Map<Eyesores.Kind, Integer> by = new EnumMap<>(Eyesores.Kind.class);
        by.putAll(w.counts);
        int[] inside = Floors.count(level, village);
        return new Report(w.total(), by, w.count(Eyesores.Kind.CAMP_BED), inside[0], inside[1], Sweepers.lying(level, v), looks,
            List.copyOf(w.spots));
    }
}
