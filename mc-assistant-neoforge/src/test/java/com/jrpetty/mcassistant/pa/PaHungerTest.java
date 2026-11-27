package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33c-2b] Hearts by level and the hunger bar by the player's rules. */
class PaHungerTest {

    @Test
    void heartsGrowWithLevel() {
        assertEquals(20.0F, PaHunger.maxHealth(1, 10, 20), "[pa35] ten hearts at level 1, like a player");
        assertEquals(40.0F, PaHunger.maxHealth(10, 10, 20), "[pa35] twenty (two rows) at level 10");
        for (int l = 2; l <= 10; l++) assertTrue(PaHunger.maxHealth(l, 10, 20) - PaHunger.maxHealth(l - 1, 10, 20) <= 4, "about two health a level, smoothly");
        assertEquals(6.0F, PaHunger.maxHealth(1, 3, 10), "an operator's own figures still work: three hearts at level 1");
        assertEquals(20.0F, PaHunger.maxHealth(10, 3, 10), "ten at level 10");
        float last = PaHunger.maxHealth(1, 3, 10);
        for (int l = 2; l <= 10; l++) {
            float hp = PaHunger.maxHealth(l, 3, 10);
            assertTrue(hp >= last && hp - last <= 2.0F, "roughly a heart a level, never down: " + l + " -> " + hp);
            assertEquals(0, hp % 2, "whole hearts");
            last = hp;
        }
        assertEquals(6.0F, PaHunger.maxHealth(0, 3, 10), "below 1 counts as 1");
        assertEquals(12.0F, PaHunger.maxHealth(10, 3, 6), "the server's cap");
    }

    @Test
    void workDrainsItSaturationFirst() {
        PaHunger h = new PaHunger();
        h.set(20, 2.0F, 0);
        for (int i = 0; i < 3; i++) { h.exhaust(4.01F); h.tick(2, 20, 20, true); }
        assertEquals(19, h.food(), "saturation went first (2), then a point of food");
        assertEquals(0.0F, h.saturation());
        PaHunger p = new PaHunger();
        p.set(20, 0, 0);
        p.exhaust(4.01F);
        p.tick(0, 20, 20, true);
        assertEquals(20, p.food(), "on Peaceful the bar never drops");
    }

    @Test
    void healsOnlyWhenFed() {
        PaHunger full = new PaHunger();
        full.set(20, 5.0F, 0);
        float healed = 0;
        for (int i = 0; i < 10; i++) healed += full.tick(2, 10, 20, true);
        assertTrue(healed > 0, "saturated at a full bar: quick healing");
        PaHunger fed = new PaHunger();
        fed.set(18, 0, 0);
        healed = 0;
        for (int i = 0; i < 80; i++) healed += fed.tick(2, 10, 20, true);
        assertEquals(1.0F, healed, "18 or more: a heart's half every four seconds");
        PaHunger peckish = new PaHunger();
        peckish.set(17, 0, 0);
        healed = 0;
        for (int i = 0; i < 400; i++) healed += peckish.tick(2, 10, 20, true);
        assertEquals(0.0F, healed, "under 18: no healing");
        PaHunger noRegen = new PaHunger();
        noRegen.set(20, 5, 0);
        healed = 0;
        for (int i = 0; i < 100; i++) healed += noRegen.tick(2, 10, 20, false);
        assertEquals(0.0F, healed, "naturalRegeneration off: none");
    }

    @Test
    void starvesByTheDifficultysRules() {
        assertEquals(-1.0F, starve(2, 5), "Normal: starves while above half a heart");
        assertEquals(0.0F, starve(2, 1), "Normal: never below half a heart");
        assertEquals(0.0F, starve(1, 10), "Easy: never below ten");
        assertEquals(-1.0F, starve(1, 11), "Easy: above ten it starves");
        assertEquals(-1.0F, starve(3, 1), "Hard: all the way");
        assertEquals(0.0F, starve(0, 5), "Peaceful: never");
    }

    private static float starve(int difficulty, float health) {
        PaHunger h = new PaHunger();
        h.set(0, 0, 0);
        float d = 0;
        for (int i = 0; i < 80; i++) d += h.tick(difficulty, health, 20, true);
        return d;
    }

    @Test
    void eatingAndWanting() {
        PaHunger h = new PaHunger();
        h.set(10, 0, 0);
        assertTrue(h.wantsFood(20, 20), "hungry");
        h.eat(5, 6.0F);                                       // bread
        assertEquals(15, h.food());
        assertEquals(6.0F, h.saturation());
        assertTrue(!h.wantsFood(20, 20) && h.wantsFood(10, 20), "not hungry at 15 unless hurt");
        h.eat(8, 12.8F);                                      // steak: capped
        assertEquals(20, h.food());
        assertTrue(h.saturation() <= 20);
    }
}
