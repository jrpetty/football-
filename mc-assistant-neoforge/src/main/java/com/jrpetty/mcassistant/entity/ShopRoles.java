package com.jrpetty.mcassistant.entity;

import javax.annotation.Nullable;

/**
 * Who does what at the shop. The shop's trade covers several jobs: the keeper who runs it, the assistants at
 * its counters who serve the customers face to face, the crafters at its benches who make what it sells, and
 * the stock keeper who sees that the right stock is always coming in.
 *
 * <p>[economy] The shared seam between the store and the wages: the store's work says who does which job, and
 * a job is paid by what it is worth. Until the store's work fills it in, the keeper keeps the shop and every
 * hand at its bench is a crafter.
 */
public final class ShopRoles {

    private ShopRoles() {}

    public enum Role {
        NONE("—"), KEEPER("Shopkeeper"), ASSISTANT("Shop assistant"), CRAFTER("Shop crafter"), STOCK_KEEPER("Stock keeper");

        public final String title;

        Role(String title) {
            this.title = title;
        }
    }

    /** This folk's job at the shop, if it has one there. */
    public static Role role(@Nullable VillageFolkEntity f) {
        if (f == null || f.stationTask() != AssistantEntity.StationTask.SHOP) return Role.NONE;
        if (Workshop.keeper(f.ownerId()) == f) return Role.KEEPER;
        if (Workshop.isHand(f)) return Role.CRAFTER;
        return Role.ASSISTANT;
    }
}
