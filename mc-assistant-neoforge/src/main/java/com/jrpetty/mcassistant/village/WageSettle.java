package com.jrpetty.mcassistant.village;

import java.util.function.IntUnaryOperator;

/**
 * [soak40] The living wage and the field hand's day, settled together (JobWorth.build). A house's rent is reckoned by a
 * field hand's wage, and the lowest wage must cover two meals and the cheapest rent: each round the rent at the field
 * hand's last wage, then the field hand's wage at least the cost of living. With the rent following the wage at half
 * (one bed's house, nothing dear about the town) two or three rounds settle it. But the cheapest rent follows the wage
 * at more than one for one in a crowded city (homes scarce, a dear quarter, a city's rents, the price of land on top of
 * a house of two), and then there is nothing to settle on: each round only dearer. Six rounds of that on day seventy of
 * the soak took the living wage from 6 to 48 coins overnight (the wage bill 4,113 against 520 coming in, the town's
 * debt in bonds doubling in a week), and the last four-hundred-day run's from 101 to 340. So a round that raises the
 * wage by no less than the round before is not taken: the floor covers the rent asked at the wage before it.
 */
public final class WageSettle {

    private WageSettle() {}

    /**
     * The field hand's day: from its worth at the market ({@code market}), raised to the cost of living at each round's
     * wage ({@code costAt}, whole coins) while that settles, for at most {@code rounds} rounds.
     */
    public static int settle(int market, IntUnaryOperator costAt, int rounds) {
        int rate = Math.max(1, market);
        int lastStep = Integer.MAX_VALUE;
        for (int r = 0; r < rounds; r++) {
            int next = Math.max(Math.max(1, costAt.applyAsInt(rate)), market);
            if (next == rate) break;
            int step = Math.abs(next - rate);
            if (next > rate && step >= lastStep) break;                // no nearer a resting place: dearer every round
            lastStep = step;
            rate = next;
        }
        return rate;
    }
}
