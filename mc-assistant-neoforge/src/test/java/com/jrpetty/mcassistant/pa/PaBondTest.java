package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa35] The bond: it rises and falls on the right things, can't be farmed in a day, wants respect their cooldown and a
 *  hush, firsts go in the story once, and each step does more. */
class PaBondTest {

    @Test
    void risesAndFallsOnTheRightThings() {
        for (PaBond.Event e : PaBond.Event.values()) {
            PaBond.State s = new PaBond.State();
            s.bond = 50;
            double d = s.apply(e, 1);
            boolean good = switch (e) { case HIT, STARVING, DIED, INSULTED, WANT_IGNORED, ABANDONED -> false; default -> true; };
            assertTrue(good ? d > 0 : d < 0, e + " moves it " + (good ? "up" : "down") + ": " + d);
        }
    }

    @Test
    void cannotBeFarmedInADay() {
        PaBond.State s = new PaBond.State();
        for (int i = 0; i < 500; i++) s.apply(PaBond.Event.FED, 3);
        assertEquals(5 + PaBond.Event.FED.dailyCap, s.bond, 1e-9, "feeding all day counts up to its cap");
        s.apply(PaBond.Event.FED, 4);
        assertEquals(5 + PaBond.Event.FED.dailyCap + PaBond.Event.FED.amount, s.bond, 1e-9, "a new day, a little more");
        PaBond.State h = new PaBond.State();
        h.bond = 50;
        for (int i = 0; i < 100; i++) h.apply(PaBond.Event.HIT, 1);
        assertEquals(50 + PaBond.Event.HIT.dailyCap, h.bond, 1e-9, "a clumsy day of misclicks costs at most its cap");
    }

    @Test
    void stepsAndWhatTheyDo() {
        assertEquals(0, PaBond.tier(0));
        assertEquals("Mate", PaBond.TIERS[PaBond.tier(15)]);
        assertEquals("Ride or Die", PaBond.TIERS[PaBond.tier(100)]);
        for (int t = 1; t < PaBond.TIERS.length; t++) {
            assertTrue(PaBond.effects(t).size() >= PaBond.effects(t - 1).size(), "each step does at least as much: " + t);
            assertTrue(PaBond.retreatsAt(t) < PaBond.retreatsAt(t - 1), "braver each step");
        }
        assertTrue(PaBond.effects(0).isEmpty(), "a stranger does nothing extra");
        assertFalse(PaBond.handsTools(1));
        assertTrue(PaBond.handsTools(2));
        assertTrue(PaBond.giftChance(4) > PaBond.giftChance(3) && PaBond.giftChance(2) == 0);
    }

    @Test
    void wantsAreRareAndRespectAHush() {
        PaBond.State s = new PaBond.State();
        assertFalse(PaBond.mayWant(s, 1, 100000, 0.0), "never while told to be quiet");
        assertTrue(PaBond.mayWant(s, 0, 100000, 0.0));
        assertFalse(PaBond.mayWant(s, 0, 100000, 0.9), "rare: most checks, no");
        s.want = "bed";
        assertFalse(PaBond.mayWant(s, 0, 100000, 0.0), "one at a time");
        s.want = "";
        s.wantAgainAt = 100000 + PaBond.WANT_COOLDOWN;
        assertFalse(PaBond.mayWant(s, 0, 100000 + PaBond.WANT_COOLDOWN - 1, 0.0), "not within the cooldown");
        assertTrue(PaBond.mayWant(s, 0, 100000 + PaBond.WANT_COOLDOWN, 0.0));
    }

    @Test
    void firstsGoInTheStoryOnce() {
        PaBond.State s = new PaBond.State();
        assertTrue(s.note("diamond", 3, "120, -40, 88", "Our first diamonds."));
        assertFalse(s.note("diamond", 9, "0, 0, 0", "Our first diamonds again?"), "a first is only a first once");
        assertTrue(s.note(null, 9, "", "A close call with a creeper."), "other moments as often as they happen");
        assertEquals(2, s.story.size());
        for (int i = 0; i < 100; i++) s.note(null, i, "", "moment " + i);
        assertEquals(60, s.story.size(), "the story keeps its last sixty");
    }

    @Test
    void favouritesAreItsOwn() {
        List<String> a = PaBond.favouritesFor(42), b = PaBond.favouritesFor(42), c = PaBond.favouritesFor(43);
        assertEquals(a, b, "the same assistant, the same favourites");
        assertEquals(3, a.size());
        assertFalse(a.get(1).equals(a.get(2)), "two different things");
        assertTrue(!a.equals(c) || !PaBond.favouritesFor(44).equals(a), "different assistants differ");
        assertEquals("a cooked salmon", PaBond.said("cooked_salmon"));
        assertEquals("an amethyst shard", PaBond.said("amethyst_shard"));
        assertEquals("some sweet berries", PaBond.said("sweet_berries"));
    }
}
