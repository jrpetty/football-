package com.jrpetty.mcassistant.village;

/**
 * [growth24] How much the age's want of a good adds to its demand in the morning's prices (PriceIndex.reckon).
 *
 * <p>A town short of what its age asks it to hold (the food in its stores, its timber, stone, coal and iron) had a third of
 * the whole shortfall counted as wanted, every day, on top of what its folk ate, drew and used: a young town whose larder
 * mark grows with every child was short of a few hundred food from one week to the next, so every food on the board was
 * "wanted" at ten times what was eaten, its cover fell under a day, and its price went up fifteen in the hundred a day to
 * three times its usual worth. Food is most of a household's basket, and the young towns' price index rose twelve to
 * eighteen in the hundred a week. A stock the town would like to hold is not a meal eaten: now the age's want is counted
 * at most as much again as the good is wanted or made in a day (a lot at the least), so a good the age asks for is dearer
 * (half the cover: about four tenths over its usual worth), and is made and bought in, without running away.
 */
public final class AgeWant {

    private AgeWant() {}

    /**
     * The age's want of a good as counted in its demand: a third of the shortfall a day (as before), but no more than what
     * the good is otherwise wanted or made in a day, nor less than a lot.
     */
    public static int counted(int shortfall, double daysCover, int ordinary, int made, int bundle) {
        if (shortfall <= 0) return 0;
        int raw = (int) Math.ceil(shortfall / daysCover);
        return Math.min(raw, Math.max(Math.max(1, bundle), Math.max(ordinary, made)));
    }
}
