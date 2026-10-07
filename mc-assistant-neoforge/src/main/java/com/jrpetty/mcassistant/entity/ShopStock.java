package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The shop's own stock: what is on its shelves and in its stockroom to be sold.
 *
 * <p>[economy] The shared seam between the shop and its customers. Whether the shop is open decides whether
 * the town's folk buy what they want for themselves or draw it free from the stores (Purchases); its stock is
 * what they buy out of.
 *
 * <p>[econ-store] The shop sells out of its own stock: the chests and casks of its stockroom and its counters,
 * the little shop's back room and, once it stands, the town store's (Store). What is not in the stockroom is
 * fetched from the village's stores at the customer's asking, so nobody goes without (a town's bread is never
 * locked behind a counter it has not been carried to yet); but that is a stock-out, booked in the stock keeper's
 * book (StockKeeper), and the stock keeper orders the ware in. Every sale is booked there too: what sold, today,
 * is what the stock keeper keeps the shelves to.
 */
public final class ShopStock {

    private ShopStock() {}

    /** Is the town's shop open for business: built, and somebody behind its counter? From then on its folk buy
     *  what they want for themselves; before, they take it from the stores. */
    public static boolean open(UUID village) {
        return (Workshop.stands(village) || Store.stands(village)) && Cafe.open(village, "shop");
    }

    /** How much of what matches the shop has to sell: its own stock, and what it can send to the stores for. */
    public static int count(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Store.count(level, village, what) + Market.stock(level, village, what);
    }

    /** How much of what matches the shop holds of its own (the stockroom and the counters), and no more. */
    public static int held(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Store.count(level, village, what);
    }

    /** Take so many of what matches out of the shop's stock, for a sale: false (and nothing taken) when it has
     *  not that many. */
    public static boolean take(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int n) {
        return !takeStacks(level, v, what, n, null).isEmpty() || n <= 0;
    }

    /** As take, for this buyer: served at the counter by an assistant if it is there (StoreFloor). */
    public static boolean take(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int n, @Nullable VillageFolkEntity buyer) {
        return !takeStacks(level, v, what, n, buyer).isEmpty() || n <= 0;
    }

    /**
     * Take so many of what matches out of the shop's stock, for a sale, and give back the very things taken (an
     * enchanted pick, a cider), or nothing if it has not that many. Out of the stockroom first; what it is short
     * of, out of the stores, booked as a stock-out. Every sale goes into the stock keeper's book, and the buyer
     * (if one is named) is served by an assistant at the counter.
     */
    public static List<ItemStack> takeStacks(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int n,
                                             @Nullable VillageFolkEntity buyer) {
        List<ItemStack> out = new ArrayList<>();
        if (n <= 0) return out;
        UUID id = v.id();
        int own = Store.count(level, id, what);
        int fromStores = Math.max(0, n - own);
        if (fromStores > 0 && Market.stock(level, id, what) < fromStores) {
            // Not in the shop and not in the stores either: wanted and not there.
            ItemStack sample = sampleOf(level, id, what);
            if (!sample.isEmpty()) StockKeeper.missed(level, id, sample, n);
            return out;
        }
        out.addAll(Store.take(level, id, what, Math.min(n, own), true));
        if (fromStores > 0) {
            List<ItemStack> fetched = Store.fromStores(level, id, what, fromStores);
            int got = 0;
            for (ItemStack s : fetched) got += s.getCount();
            if (got < fromStores) {
                // The stores' count was stale (something else took it meanwhile): all back where it came from.
                for (ItemStack s : out) Store.put(level, id, s);
                for (ItemStack s : fetched) TownWork.give(level, v, s);
                out.clear();
                return out;
            }
            out.addAll(fetched);
        }
        if (out.isEmpty()) return out;
        ItemStack one = out.get(0).copyWithCount(1);
        StockKeeper.sold(level, id, one, n, fromStores);
        StoreFloor.served(level, v, buyer, one, n);
        return out;
    }

    /** What the shop asks for one of this, in coin: what it costs in the town today (PriceIndex), with the
     *  shop's margin on it (raised when a ware sells out, lowered when it sits: StockKeeper), and a slow ware
     *  marked down, never under what it cost (Stockroom). */
    public static int price(ServerLevel level, UUID village, ItemStack one) {
        if (one.isEmpty()) return 0;
        ItemStack single = one.copyWithCount(1);
        double each = PriceIndex.each(level, village, single);
        double markup = StockKeeper.markup(village, Stockroom.key(single));
        int p = (int) Math.max(1, Math.round(each * markup));
        if (single.isEnchanted()) p = Math.max(p, (int) Math.round(PriceIndex.usual(single) * 3));
        return Stockroom.asked(level, village, single, p, 1);
    }

    /** One of what matches, as the shop or the stores hold it (for the books), or nothing. */
    private static ItemStack sampleOf(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        for (net.minecraft.world.Container c : Store.containers(level, village, true)) {
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty() && what.test(c.getItem(i))) return c.getItem(i).copyWithCount(1);
        }
        for (net.minecraft.core.BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty() && what.test(c.getItem(i))) return c.getItem(i).copyWithCount(1);
        }
        // Nothing of it anywhere: the store's own range knows what it is.
        return StockKeeper.sampleFor(what);
    }
}
