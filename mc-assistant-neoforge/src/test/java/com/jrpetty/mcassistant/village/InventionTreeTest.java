package com.jrpetty.mcassistant.village;

import com.jrpetty.mcassistant.village.InventionTree.Invention;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [tech-tree] The inventions' tree (InventionTree) walked without the game: every invention reachable in its age (what it
 * needs comes no later, and nothing needs itself round about), its numbers in bounds, its makings read; and the new arms'
 * edges on paper (ArmsEdge): none of them nothing at all, pikes against a charge and hammers against plate worth more,
 * and the same battle won the more often with them.
 */
class InventionTreeTest {

    @Test
    void everyInventionCanBeReachedInItsAge() {
        Set<String> ids = new HashSet<>();
        for (Invention i : Invention.values()) {
            assertTrue(ids.add(i.id()), "one invention per id: " + i.id());
            assertEquals(i, InventionTree.byId(i.id()), "found by its id");
            assertEquals(i, InventionTree.byId(i.title), "found by its title: " + i.title);
            assertTrue(i.depth() >= 1 && i.depth() <= 6, "a depth in the tree, so no ring of needs: " + i + " " + i.depth());
            for (Invention n : i.needs()) {
                assertTrue(n.age <= i.age, i + " needs " + n + ", which waits for a later age");
                assertTrue(n != i, i + " needs itself");
            }
            assertTrue(i.cost >= 10 && i.cost <= 200, "its learning in bounds: " + i + " " + i.cost);
            assertTrue(i.days >= 1 && i.days <= 7, "its days in bounds: " + i + " " + i.days);
            assertTrue(!i.makings().isEmpty() || i.paper + i.ink + i.books > 0, "something to make it of: " + i);
            for (InventionTree.Making m : i.makings()) assertTrue(m.count() > 0 && m.item().contains(":") || m.kind(), "a making read: " + m);
            assertTrue(!i.effect.isBlank() && !i.unlocks.isBlank(), "it says what it does: " + i);
        }
        assertTrue(Invention.values().length >= 20, "a tree worth the name: " + Invention.values().length);
        // A town that has everything of an age knows its way to everything of the next.
        EnumSet<Invention> known = EnumSet.noneOf(Invention.class);
        for (int age = 0; age <= 4; age++) {
            boolean more = true;
            while (more) {
                more = false;
                for (Invention i : Invention.values()) if (InventionTree.open(i, known, age)) { known.add(i); more = true; }
            }
            for (Invention i : Invention.values()) if (i.age <= age) assertTrue(known.contains(i), i + " cannot be reached by " + InventionTree.AGES[age]);
        }
        assertNotNull(InventionTree.byId("the windmill's millstones"));
        assertTrue(InventionTree.byId("nothing at all") == null);
    }

    @Test
    void daysLeftCountsTheLearningAndTheModel() {
        assertEquals(3, InventionTree.daysLeft(1000, 400, 0), "a thousand hundredths at four hundred a day");
        assertEquals(4, InventionTree.daysLeft(100, 400, 4), "the model's days are the least");
        assertEquals(0, InventionTree.daysLeft(0, 400, 0));
    }

    @Test
    void theNewArmsAreWorthSomethingOnPaper() {
        ArmsEdge.Kit none = new ArmsEdge.Kit(), pikes = new ArmsEdge.Kit(), hammers = new ArmsEdge.Kit(), plate = new ArmsEdge.Kit();
        assertEquals(1.0, ArmsEdge.volley(none, 6), 1e-9, "no new bows: the duel as ever");
        assertEquals(1.0, ArmsEdge.clash(none, 20, none, true), 1e-9, "no new arms: the clash as ever");
        assertEquals(1.0, ArmsEdge.braced(none, 20, true), 1e-9);
        pikes.pikes = 10;
        hammers.hammers = 10;
        plate.armour = 15;
        assertTrue(ArmsEdge.clash(pikes, 20, none, true) > 1.4, "pikes against a charge");
        assertEquals(1.0, ArmsEdge.clash(pikes, 20, none, false), 1e-9, "pikes against a line on foot: nothing");
        assertTrue(ArmsEdge.braced(pikes, 20, true) < 0.5, "the flank's shock blunted");
        assertTrue(ArmsEdge.clash(hammers, 20, plate, false) > 1.4, "hammers through plate");
        assertEquals(1.0, ArmsEdge.clash(hammers, 20, none, false), 1e-9, "hammers against the unarmoured: nothing");
        assertEquals(10, ArmsEdge.gateBlows(false, false));
        assertTrue(ArmsEdge.gateBlows(true, false) < 10 && ArmsEdge.gateBlows(true, true) < ArmsEdge.gateBlows(true, false));
        // The same battle, two hundred times over: with nine pikes against a charge, won the more often.
        int without = 0, with = 0;
        for (int t = 0; t < 200; t++) {
            if (fight(t, 0) == 0) without++;
            if (fight(t, 9) == 0) with++;
        }
        assertTrue(with > without + 10, "the pikes win more against riders: " + without + " -> " + with);
    }

    private static int fight(int seed, int pikes) {
        BattleModel.Side us = new BattleModel.Side("Us", 20, 120, 6, 60, BattleModel.Tactic.STRAIGHT);
        BattleModel.Side them = new BattleModel.Side("Them", 20, 120, 6, 60, BattleModel.Tactic.CAVALRY);
        us.kit.pikes = pikes;
        return BattleModel.resolve(us, them, 0, 0, 0, 0, 0, new Random(77L + seed)).winner();
    }

    @Test
    void theModelsMakingsReadAsTheyAreWritten() {
        List<InventionTree.Making> m = Invention.WINDMILL.makings();
        assertEquals(3, m.size());
        assertEquals("#planks", m.get(0).item());
        assertEquals("4 planks", m.get(0).words());
        assertEquals("2 sticks", m.get(2).words());
    }
}
