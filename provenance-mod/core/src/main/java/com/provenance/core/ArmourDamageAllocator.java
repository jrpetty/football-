package com.provenance.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one damage event across the armour pieces that actually mitigated it.
 *
 * <p>The naive approach — crediting every worn piece with the full prevented
 * amount — inflates the item-wide totals fourfold and would let a full set
 * reach Server Relic four times faster than it earned. This allocates the
 * prevented damage in proportion to each piece's contribution and guarantees
 * the parts sum to exactly the whole.
 *
 * <p>Integer remainder from the proportional split is handed to the largest
 * contributor rather than dropped, so no fraction of a real event goes
 * unrecorded and no rounding can manufacture extra.
 */
public final class ArmourDamageAllocator {

    /** One worn piece and the share of mitigation it was responsible for. */
    public record Piece(ItemRecord record, double weight) {
    }

    /** A piece and the whole-number damage it should be credited with. */
    public record Allocation(ItemRecord record, long amount) {
    }

    private ArmourDamageAllocator() {
    }

    /**
     * @param pieces          worn armour with each piece's protection weight
     *                        (armour points, toughness-adjusted, or whatever the
     *                        platform exposes)
     * @param preventedAmount total damage actually prevented, in the same
     *                        hundredths-of-a-heart units used everywhere else
     * @return one allocation per piece, summing to exactly {@code preventedAmount}
     */
    public static List<Allocation> allocate(List<Piece> pieces, long preventedAmount) {
        List<Allocation> result = new ArrayList<>();
        if (pieces == null || pieces.isEmpty() || preventedAmount <= 0) {
            return result;
        }

        double totalWeight = 0.0d;
        for (Piece piece : pieces) {
            if (piece.weight() > 0.0d) {
                totalWeight += piece.weight();
            }
        }

        // No usable weights: fall back to an even split rather than crediting
        // everyone the full amount.
        if (totalWeight <= 0.0d) {
            long each = preventedAmount / pieces.size();
            long remainder = preventedAmount - (each * pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                long amount = each + (i == 0 ? remainder : 0L);
                if (amount > 0) {
                    result.add(new Allocation(pieces.get(i).record(), amount));
                }
            }
            return result;
        }

        long assigned = 0L;
        int largestIndex = 0;
        double largestWeight = -1.0d;
        long[] amounts = new long[pieces.size()];

        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            double weight = Math.max(0.0d, piece.weight());
            long share = (long) Math.floor(preventedAmount * (weight / totalWeight));
            amounts[i] = share;
            assigned += share;
            if (weight > largestWeight) {
                largestWeight = weight;
                largestIndex = i;
            }
        }

        // Give the rounding remainder to the piece that did the most work, so
        // the allocations sum to the real event exactly.
        amounts[largestIndex] += preventedAmount - assigned;

        for (int i = 0; i < pieces.size(); i++) {
            if (amounts[i] > 0) {
                result.add(new Allocation(pieces.get(i).record(), amounts[i]));
            }
        }
        return result;
    }
}
