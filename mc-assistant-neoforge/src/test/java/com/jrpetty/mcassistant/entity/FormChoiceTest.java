package com.jrpetty.mcassistant.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [featcivic] The founding of towns' governments (FormChoice), checked without a world: thousands of towns founded the
 * way the game founds them (a land, four to eight founders with two traits each, their values from their natures, the
 * town's ethos from all of it) come out mostly elected democracies, every other form still turns up, the same town
 * always gets the same form, and a town of strong character still gets the form its character calls for.
 */
class FormChoiceTest {

    /** One town's axes, founded as Identity.seed does it, from a seeded die. */
    static int[] found(Random rng, UUID town) {
        Homeland.Land[] lands = Homeland.Land.values();
        Homeland.Land land = lands[rng.nextInt(lands.length)];
        int n = 4 + rng.nextInt(5);
        List<int[]> weights = new ArrayList<>();
        List<Collection<Social.Trait>> natures = new ArrayList<>();
        Social.Trait[] all = Social.Trait.values();
        for (int i = 0; i < n; i++) {
            List<Social.Trait> traits = new ArrayList<>();
            while (traits.size() < 2) {
                Social.Trait t = all[rng.nextInt(all.length)];
                if (traits.contains(t) || !traits.isEmpty() && traits.get(0).opposite() == t) continue;
                traits.add(t);
            }
            weights.add(Values.base(traits, new UUID(rng.nextLong(), rng.nextLong())));
            natures.add(traits);
        }
        return Ethos.seed(town, land, weights, natures);
    }

    static Map<Government.Form, Integer> tally(int towns, long seed) {
        Random rng = new Random(seed);
        Map<Government.Form, Integer> n = new EnumMap<>(Government.Form.class);
        for (int i = 0; i < towns; i++) {
            UUID town = new UUID(rng.nextLong(), rng.nextLong());
            n.merge(FormChoice.choose(found(rng, town), town), 1, Integer::sum);
        }
        return n;
    }

    @Test
    @DisplayName("Most towns are founded as elected democracies: between seven and eight in ten, over thousands of seeds")
    void mostlyDemocratic() {
        int towns = 6000;
        for (long seed : new long[]{ 1L, 42L, 20261126L }) {
            Map<Government.Form, Integer> n = tally(towns, seed);
            int dem = n.getOrDefault(Government.Form.DEMOCRACY, 0) + n.getOrDefault(Government.Form.REEVE, 0);
            double share = dem / (double) towns;
            System.out.println("[featcivic] seed " + seed + ": " + n + " -> elected " + String.format(java.util.Locale.ROOT, "%.3f", share));
            assertTrue(share >= 0.70 && share <= 0.82, "elected democracies " + share + " of towns (seed " + seed + "): " + n);
            assertTrue(n.getOrDefault(Government.Form.DEMOCRACY, 0) > n.getOrDefault(Government.Form.REEVE, 0),
                "the full representative democracy the commonest form: " + n);
            for (Government.Form f : Government.Form.values()) {
                assertTrue(n.getOrDefault(f, 0) > 0, f + " never turns up (seed " + seed + "): " + n);
                if (!FormChoice.democratic(f)) assertTrue(n.getOrDefault(f, 0) < towns * 0.12, f + " too common: " + n);
            }
        }
    }

    @Test
    @DisplayName("The same town always gets the same form")
    void stable() {
        Random rng = new Random(7);
        for (int i = 0; i < 200; i++) {
            UUID town = new UUID(rng.nextLong(), rng.nextLong());
            int[] axes = found(rng, town);
            assertEquals(FormChoice.choose(axes, town), FormChoice.choose(axes.clone(), town));
        }
    }

    @Test
    @DisplayName("A town of strong character gets the form its character calls for; a moderate one elects")
    void character() {
        int[] port = { 70, -30, -45, 20, 65, -40, 10 }, hold = { -35, 75, 20, -45, -70, 55, -60 }, abbey = { -20, -40, 80, 45, -10, 60, 30 };
        assertTrue(FormChoice.othersOdds(port) < 0.15, "an open trading port elects: " + FormChoice.othersOdds(port));
        assertTrue(FormChoice.othersOdds(hold) > 0.95, "a martial, closed, hierarchical hold does not: " + FormChoice.othersOdds(hold));
        assertTrue(FormChoice.othersOdds(abbey) > 0.9, "a devout, traditional abbey town does not: " + FormChoice.othersOdds(abbey));
        Government.Form h = (Government.Form) FormChoice.strongestOther(hold)[0];
        assertTrue(h == Government.Form.LORD || h == Government.Form.JUNTA, "the hold under a lord or a general: " + h);
        assertEquals(Government.Form.CHAPLAIN, FormChoice.strongestOther(abbey)[0]);
        int[] plain = new int[7];
        assertTrue(FormChoice.democratic(FormChoice.choose(plain, 12345L)) || FormChoice.othersOdds(plain) < 0.2, "a plain town elects");
        int[] egal = { -45, -40, -20, 30, 0, -10, 70 };
        assertEquals(Government.Form.PLANNED, FormChoice.strongestOther(egal)[0], "a self-reliant, learned, egalitarian town leans to the plan");
    }

    @Test
    @DisplayName("Ideology: kindred governments get on, opposed ones do not")
    void ideology() {
        assertEquals(2, Ideology.lean(Government.Form.DEMOCRACY, Government.Form.REEVE));
        assertTrue(Ideology.lean(Government.Form.DEMOCRACY, Government.Form.JUNTA) <= -1);
        assertTrue(Ideology.lean(Government.Form.PLANNED, Government.Form.GUILD) <= -1);
        assertTrue(Ideology.lean(Government.Form.LORD, Government.Form.JUNTA) >= 0);
        assertTrue(Ideology.distance(Government.Form.DEMOCRACY, Government.Form.JUNTA) > Ideology.distance(Government.Form.DEMOCRACY, Government.Form.COMMUNE));
    }
}
