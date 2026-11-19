package com.jrpetty.mcassistant.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** [econ4] The trade cycle's arithmetic (CycleModel), checked without a world: it cycles, it stays bounded, it recovers. */
class CycleModelTest {

    /** The phases day by day over so many days, from the model's starting state. */
    private static List<CycleModel.Phase> run(CycleModel m, int days, int shockFrom, int shockDays, double shock) {
        List<CycleModel.Phase> out = new ArrayList<>();
        CycleModel.Phase was = null;
        for (int d = 0; d < days; d++) {
            m.shock = d >= shockFrom && d < shockFrom + shockDays ? shock : 0;
            double before = m.employment;
            m.step();
            was = CycleModel.phase(m.employment, m.employment - before, was);
            out.add(was);
            assertTrue(m.employment >= 0.80 && m.employment <= 0.995, "employment bounded: " + m.employment);
            assertTrue(m.wageShare >= 0.55 && m.wageShare <= 0.95, "wage share bounded: " + m.wageShare);
            assertTrue(m.debt >= 0 && m.debt <= 4.0, "debt bounded: " + m.debt);
            assertTrue(m.rate >= 0.005 && m.rate <= 0.10, "rate bounded: " + m.rate);
        }
        return out;
    }

    @Test
    @DisplayName("boom, slowing, slump and recovering come round in turn, more than once, with no die rolled")
    void cycles() {
        List<CycleModel.Phase> ph = run(new CycleModel(), 160, -1, 0, 0);
        // Collapse the runs and count the full turns boom -> slowing -> slump -> recovering.
        List<CycleModel.Phase> runs = new ArrayList<>();
        for (CycleModel.Phase p : ph) if (runs.isEmpty() || runs.get(runs.size() - 1) != p) runs.add(p);
        int turns = 0;
        for (int i = 0; i + 3 < runs.size(); i++) {
            if (runs.get(i) == CycleModel.Phase.BOOM && runs.get(i + 1) == CycleModel.Phase.SLOWING && runs.get(i + 2) == CycleModel.Phase.SLUMP
                && runs.get(i + 3) == CycleModel.Phase.RECOVERING) turns++;
        }
        assertTrue(turns >= 2, "at least two full turns of the cycle in 160 days: " + runs);
        // The same start gives the same cycle: nothing random in it.
        assertTrue(ph.equals(run(new CycleModel(), 160, -1, 0, 0)), "deterministic");
    }

    @Test
    @DisplayName("the council's rate is dearer in the boom than in the slump")
    void rateLeans() {
        CycleModel m = new CycleModel();
        double boom = 0, slump = 0;
        int nb = 0, ns = 0;
        CycleModel.Phase was = null;
        for (int d = 0; d < 160; d++) {
            double before = m.employment;
            m.step();
            was = CycleModel.phase(m.employment, m.employment - before, was);
            if (was == CycleModel.Phase.BOOM) { boom += m.rate; nb++; }
            if (was == CycleModel.Phase.SLUMP) { slump += m.rate; ns++; }
        }
        assertTrue(nb > 0 && ns > 0 && boom / nb > slump / ns, "boom rate " + boom / nb + " against slump " + slump / ns);
    }

    @Test
    @DisplayName("a hard shock tips it into a slump, and it recovers: no death spiral")
    void recovers() {
        CycleModel m = new CycleModel();
        List<CycleModel.Phase> ph = run(m, 120, 10, 20, -0.08);
        assertTrue(ph.subList(10, 40).contains(CycleModel.Phase.SLUMP), "the shock brings a slump: " + ph.subList(10, 40));
        assertTrue(ph.subList(40, 120).contains(CycleModel.Phase.BOOM), "and a boom again after it: " + ph.subList(40, 120));
    }
}
