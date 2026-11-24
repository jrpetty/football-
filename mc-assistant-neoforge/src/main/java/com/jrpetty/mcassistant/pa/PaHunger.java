package com.jrpetty.mcassistant.pa;

/**
 * [pa33c-2b] Its hunger bar, by the player's own rules (vanilla FoodData, which only works for players): food 0 to 20,
 * saturation up to the food level, and exhaustion that work builds up (sprinting, mining, fighting, jumping, being hit).
 * Every 4 exhaustion takes a point of saturation, then of food. It heals on its own only while fed: quickly while
 * saturated at a full bar, slowly at 18 or more; at 0 it starves, down to half a heart on Normal, to 10 health on Easy,
 * to death on Hard, and not at all on Peaceful. Its hearts grow with its level. Pure Java, so it is tested on its own.
 */
public final class PaHunger {

    public static final int MAX_FOOD = 20;

    private int food = MAX_FOOD;
    private float saturation = 5.0F;
    private float exhaustion;
    private int timer;

    public int food() { return food; }

    public float saturation() { return saturation; }

    public float exhaustion() { return exhaustion; }

    public void set(int food, float saturation, float exhaustion) {
        this.food = Math.max(0, Math.min(MAX_FOOD, food));
        this.saturation = Math.max(0, Math.min(this.food, saturation));
        this.exhaustion = Math.max(0, Math.min(40, exhaustion));
    }

    /** Work done: sprinting 0.1 a block, jumping 0.05, a swing 0.1, a block broken 0.005, a hit taken 0.1 (as a player). */
    public void exhaust(float amount) {
        exhaustion = Math.min(40.0F, exhaustion + Math.max(0, amount) * efficiency);
    }

    /** [pa35] How hungry the same work makes it (1 at level 1, less with practice: PaGrowth.hunger). */
    public float efficiency = 1.0F;

    /** It ate: as FoodData.eat (nutrition, and the food's saturation, never past the food level). */
    public void eat(int nutrition, float sat) {
        food = Math.min(MAX_FOOD, food + Math.max(0, nutrition));
        saturation = Math.min(food, saturation + Math.max(0, sat));
    }

    /** Is it hungry enough to eat, as a player would (not wasting a full bar)? */
    public boolean wantsFood(float health, float maxHealth) {
        return food <= 14 || (food < MAX_FOOD && health < maxHealth);
    }

    /**
     * One tick, as FoodData.tick. Returns the health change: positive to heal, negative to starve, 0 for nothing.
     * difficulty: 0 peaceful, 1 easy, 2 normal, 3 hard. regen: the naturalRegeneration rule.
     */
    public float tick(int difficulty, float health, float maxHealth, boolean regen) {
        if (exhaustion > 4.0F) {
            exhaustion -= 4.0F;
            if (saturation > 0.0F) saturation = Math.max(saturation - 1.0F, 0.0F);
            else if (difficulty > 0) food = Math.max(food - 1, 0);
        }
        boolean hurt = health < maxHealth;
        if (regen && saturation > 0.0F && hurt && food >= MAX_FOOD) {
            if (++timer >= 10) {
                float f = Math.min(saturation, 6.0F);
                exhaust(f);
                timer = 0;
                return f / 6.0F;
            }
        } else if (regen && food >= 18 && hurt) {
            if (++timer >= 80) {
                exhaust(6.0F);
                timer = 0;
                return 1.0F;
            }
        } else if (food <= 0) {
            if (++timer >= 80) {
                timer = 0;
                if (health > 10.0F || difficulty >= 3 || (health > 1.0F && difficulty == 2)) return -1.0F;
            }
        } else {
            timer = 0;
        }
        return 0.0F;
    }

    // ------------------------------------------------------------------ hearts by level

    /** Max health (HP) at a level (1..10): from {@code startHearts} at level 1 to {@code maxHearts} at 10, in whole hearts. */
    public static float maxHealth(int level, int startHearts, int maxHearts) {
        int l = Math.max(1, Math.min(10, level));
        int lo = Math.max(1, Math.min(startHearts, maxHearts)), hi = Math.max(lo, maxHearts);
        int hearts = lo + Math.round((hi - lo) * (l - 1) / 9.0F);
        return hearts * 2.0F;
    }
}
