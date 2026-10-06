package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * The shop's own stock: what is on its shelves and in its stockroom to be sold.
 *
 * <p>[economy] The shared seam between the shop and its customers. Whether the shop is open decides whether
 * the town's folk buy what they want for themselves or draw it free from the stores (Purchases); its stock is
 * what they buy out of. The shop's side (its stockroom, the deliveries to it, its stock keeper) belongs to the
 * work on the store as a business. Until then the shop is open when it is built and somebody keeps it, and
 * its stock is the town's stores, as it has always sold out of.
 */
public final class ShopStock {

    private ShopStock() {}

    /** Is the town's shop open for business: built, and somebody behind its counter? From then on its folk buy
     *  what they want for themselves; before, they take it from the stores. */
    public static boolean open(UUID village) {
        return Workshop.stands(village) && Cafe.open(village, "shop");
    }

    /** How much of what matches the shop has to sell. */
    public static int count(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    /** Take so many of what matches out of the shop's stock, for a sale: false (and nothing taken) when it has
     *  not that many. */
    public static boolean take(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int n) {
        return TownWork.take(level, v, what, n);
    }
}
