package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;

import java.util.UUID;

/**
 * [growth24] A delivery on credit between friends.
 *
 * <p>Envoys came home "no deal: the buyer cannot pay what the seller must have" from 89 of some 115 trade missions in the
 * four-hundred-day run. The buyer pays for a lot in its own goods (only those the seller is short of) and in coin, and the
 * coin it may find is a third of what its treasury holds past two days' wages: a working town that pays its hands every day
 * had none to spare from one week to the next, so it bought from its neighbours only what they would take in kind, which
 * was seldom anything. A merchant's town sells to a friend on account: a friend (relations friendly or better) that owes
 * nothing on any of its deals now may have a delivery's coin on credit, up to a quarter of what the town made yesterday
 * (its takings to come) and sixty-four coin at most. What it cannot pay when the caravan comes stays owed (the deal's
 * debts, TradeDeals.squareDebts) and is squared out of its treasury at the next delivery; while it owes, no more credit.
 */
public final class TradeCredit {

    private TradeCredit() {}

    /** The most coin a delivery is let have on credit. */
    public static final int MOST = 64;

    /** The coin this buyer may have on credit for a delivery from this seller (0: none). */
    public static int line(UUID buyer, UUID seller) {
        if (Ledger.relation(buyer, seller) < Diplomacy.FRIENDLY) return 0;
        if (owes(buyer)) return 0;
        return Math.max(0, Math.min(MOST, Economy.yesterday(buyer) / 4));
    }

    /** Does this town owe coin on any of its deals now? */
    public static boolean owes(UUID town) {
        for (TradeDeals.Deal d : TradeDeals.deals(town)) {
            if (d.buyer.equals(town) && d.bOwes > 0) return true;
            if (d.seller.equals(town) && d.sOwes > 0) return true;
        }
        return false;
    }
}
