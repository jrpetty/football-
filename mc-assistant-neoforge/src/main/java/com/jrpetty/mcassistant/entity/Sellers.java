package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [econ-1b] Competition: where more than one seller has a ware, which one a buyer goes to.
 *
 * <p>The town's own counter (the shop, the stores) always has its price (Purchases.priceEach). Every firm opened by its
 * own folk (Enterprise) that holds the ware has its own: its full cost a piece (its makings and its share of the week's
 * wages, rent and running costs) and its markup, never more than three fifths over the going price nor much under it. A
 * buyer weighs each: the price, made a little dearer by the walk (two in the hundred for every sixteen blocks), against
 * the seller's name for good work (its reputation, nought to one: its hands' skill, its years, how often it was out of
 * stock), which is worth up to fifteen in the hundred of the going price to it. It goes to the best. A firm the folk pass
 * by sells little; under a tenth of its market for a week it cuts its markup (Enterprise.share). No new reasoning for the
 * folk: the choice is made at the counter, in Purchases.get and the shopping trips (StoreFloor.folkBuys).
 */
public final class Sellers {

    private Sellers() {}

    /** The walk: so much dearer in the hundred for every sixteen blocks. */
    static final double WALK = 0.02;
    /** A reputation of one is worth this much of the going price to a buyer. */
    static final double NAME_WORTH = 0.15;
    /** A firm never asks under this of the going price (its own cost-plus would undersell everybody at first). */
    static final double LEAST_OF_GOING = 0.4;

    /** The seller chosen: a firm (null for the town's own counter), its price each, and the buyer's reckoning of it. */
    public record Offer(@Nullable Business firm, double price, double utility) {}

    /** What a firm asks for one of this today, coin. */
    public static double priceOf(ServerLevel level, UUID village, Business b, ItemStack one) {
        double going = Market.askEach(level, village, one);
        long day = Businesses.today(level);
        double cost = Businesses.fullCost(level, village, b, one, day);
        if (cost <= 0.0001) cost = CostBook.of(one).total() * MarketMaker.priceLevel(village);
        double p = cost * (1 + b.markup);
        // [econ-1d] What the market will bear: a firm whose full cost a piece (its week's wages and rent spread over a few
        // sales) would put it over the going price sells at the going price, so long as that pays its makings and its markup,
        // and lets its trade grow to carry its overheads: a new bakery priced its bread at three fifths over the going price
        // and sold to nobody but the well-to-do.
        if (p > going) {
            Business.Lot lot = b.stock.get(Stockroom.key(one));
            double makings = lot != null && lot.qty > 0 ? lot.each() / 100.0 : CostBook.of(one).materials() + CostBook.of(one).fuel();
            p = Math.max(going, makings * (1 + b.markup));
        }
        p = Math.max(p, going * LEAST_OF_GOING);
        p = Math.min(p, going * Businesses.MOST_OVER_GOING);
        return Math.max(0.01, p);
    }

    /** A seller's reputation, nought to one (the town's own counter: a half). */
    static double reputation(UUID village, @Nullable Business b) {
        if (b == null) return 0.5;
        CompoundTag r = Enterprise.rec(village, b.id);
        return r == null ? 0.5 : r.getDouble("rep");
    }

    /** How a buyer weighs a seller: the price and the walk against its name. */
    static double utility(double price, double going, double distance, double rep) {
        return -price * (1 + WALK * distance / 16.0) + rep * NAME_WORTH * going;
    }

    /** Where the town's own counter is: the shop's, else the storehouse, else the square. */
    static BlockPos counter(ServerLevel level, Villages.Village v) {
        BlockPos at = Villages.builtAt(v.id(), Store.buildingForShop(v.id()));
        if (at == null) at = Villages.builtAt(v.id(), "storage");
        return at == null ? v.centre() : at;
    }

    /**
     * The best seller of this ware for this buyer, {@code n} of them: the town's counter at {@code townEach}, or a firm that
     * holds them. The town's counter wins a tie.
     */
    public static Offer best(ServerLevel level, Villages.Village v, ItemStack one, @Nullable VillageFolkEntity buyer, double townEach, int n) {
        return best(level, v, one, buyer, townEach, n, false);
    }

    /** [econ-1d] The same; {@code looking}: only weighing what to buy (no stock-out marked against a firm that has none). */
    public static Offer best(ServerLevel level, Villages.Village v, ItemStack one, @Nullable VillageFolkEntity buyer, double townEach, int n, boolean looking) {
        UUID id = v.id();
        double going = Market.askEach(level, id, one);
        BlockPos from = buyer != null ? buyer.blockPosition() : v.centre();
        double dTown = Math.sqrt(from.distSqr(counter(level, v)));
        Offer best = new Offer(null, townEach, utility(townEach, going, dTown, reputation(id, null)));
        if (!MarketMaker.live(id) && !ANY_FOR_TESTS) return best;
        String key = Stockroom.key(one);
        for (Business b : Enterprise.open(id)) {
            BusinessTypes.BusinessType t = Enterprise.kindOf(id, b);
            if (t == null || !t.wares().contains(key)) continue;
            Business.Lot lot = b.stock.get(key);
            if (lot == null || lot.qty < n) {
                if (!looking && (lot != null || b.allTime.lines.containsKey(key))) Enterprise.stockOut(id, b);   // [econ-1d] (looking)
                continue;
            }
            if (b.ownedBy(buyer == null ? null : buyer.getUUID()) && buyer != null) continue;   // not of itself
            double p = priceOf(level, id, b, one);
            double d = b.at == null ? dTown : Math.sqrt(from.distSqr(b.at));
            double u = utility(p, going, d, reputation(id, b));
            if (u > best.utility()) best = new Offer(b, p, u);
        }
        return best;
    }

    private static volatile boolean ANY_FOR_TESTS;

    /** Tests: firms compete in a town whose market is not open. */
    public static void anyForTests(boolean on) {
        ANY_FOR_TESTS = on;
    }

    /**
     * A firm's sale at the counter (after the buyer's coin went into the treasury, as at any counter): into the firm's books
     * and its till, out of its stock, and the take at the stores not charged again.
     */
    public static void sold(ServerLevel level, UUID village, Business firm, ItemStack what, int n, long pence) {
        if (village == null || what.isEmpty() || n <= 0) return;
        Businesses.sale(level, village, firm, what, Stockroom.key(what), n, pence, true);
        MarketMaker.paidAtCounter(level, village, what, n);
        CompoundTag r = Enterprise.rec(village, firm.id);
        if (r != null) Accounts.add(r, "sales", n);
        Accounts.dirty();
    }
}
