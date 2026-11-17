package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [supply] A firm's wares on the town's shelves are the firm's, not the market's.
 *
 * <p>A firm of the town's own folk keeps what it makes in the town's stores, bonded in its name, to sell at its own price.
 * But a folk helping itself at the stores (its rations off the shelf, a hand's kit) was charged the market's ask for
 * whatever it took, and a firm's loaves went out as the market's: the bakery's books held twelve loaves its shelf no
 * longer had, it never sold one, and the market took the money for its bread. Now what the stores hold of a ware is the
 * market's own only past what the firms hold of it; a take that runs into a firm's stock is the firm's sale, at the
 * firm's price, into its till (MarketMaker.taken); and the town's counter offers a ware against a firm's only while the
 * market holds some of its own (Sellers.best).
 */
public final class Bonded {

    private Bonded() {}

    /** What the open firms of the town hold of a ware, in their books. */
    static long firmsHeld(UUID village, String key) {
        long n = 0;
        for (Business b : Enterprise.open(village)) n += Enterprise.held(b, key);
        return n;
    }

    /** What the market holds of a ware of its own: the stores' count, less what the firms hold bonded in them. */
    static long marketOwn(ServerLevel level, UUID village, String key) {
        long held = firmsHeld(village, key);
        long all = Market.stock(level, village, Stockroom.matcher(key));
        return Math.max(0, all - held);
    }

    /** The firm holding the most of a ware (not {@code buyer}'s own), or null. */
    @Nullable
    static Business holder(UUID village, String key, @Nullable Accounts.Account buyer) {
        Business best = null;
        for (Business b : Enterprise.open(village)) {
            if (buyer != null && buyer.kind() == Accounts.Kind.FIRM && b.id.equals(buyer.key())) continue;
            if (Enterprise.held(b, key) > 0 && (best == null || Enterprise.held(b, key) > Enterprise.held(best, key))) best = b;
        }
        return best;
    }

    /**
     * So many of a ware just taken out of the stores (MarketMaker.taken, the take already off the shelf): how many of them
     * were a firm's (the stores now hold fewer than the firms' books say they hold), and that firm, or null.
     */
    @Nullable
    static Object[] firmsShare(ServerLevel level, UUID village, ItemStack st, int k, Accounts.Account buyer) {
        String key = Stockroom.key(st);
        if (firmsHeld(village, key) <= 0) return null;
        Business b = holder(village, key, buyer);
        if (b == null) return null;
        long now = Market.stock(level, village, Stockroom.matcher(key));      // after the take
        long held = firmsHeld(village, key);
        long marketBefore = Math.max(0, now + k - held);
        int firms = (int) Math.max(0, Math.min(Math.min(k, Enterprise.held(b, key)), k - marketBefore));
        return firms <= 0 ? null : new Object[]{ b, firms };
    }
}
