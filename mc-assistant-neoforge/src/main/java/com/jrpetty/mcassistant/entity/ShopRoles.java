package com.jrpetty.mcassistant.entity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Who does what at the shop. The shop's trade covers several jobs: the keeper who runs it, the assistants at
 * its counters who serve the customers face to face, the crafters at its benches who make what it sells, and
 * the stock keeper who sees that the right stock is always coming in.
 *
 * <p>[economy] The shared seam between the store and the wages: the store's work says who does which job, and
 * a job is paid by what it is worth.
 *
 * <p>[econ-store] The jobs, all of the shop's trade (StationTask.SHOP), told apart by a tag the folk carries
 * (kept with it in the world): the keeper is the one the shop's workshop calls its keeper (Workshop.keeper);
 * an assistant carries {@link StoreStaff#ASSISTANT}, the stock keeper {@link StoreStaff#STOCK_KEEPER}; every
 * other hand of the trade is a crafter at the bench (Workshop.HAND). How many of each the shop wants, and who
 * is taken on, is StoreStaff's.
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
        if (f.getTags().contains(StoreStaff.STOCK_KEEPER)) return Role.STOCK_KEEPER;
        if (f.getTags().contains(StoreStaff.ASSISTANT)) return Role.ASSISTANT;
        return Role.CRAFTER;
    }

    /** Is this folk one of the shop's staff under its keeper (a hand at the bench, an assistant or the stock
     *  keeper), and so never the keeper while one of the trade is free of the other jobs (Workshop.keeper)? */
    public static boolean underTheKeeper(VillageFolkEntity f) {
        return f.getTags().contains(Workshop.HAND) || f.getTags().contains(StoreStaff.ASSISTANT) || f.getTags().contains(StoreStaff.STOCK_KEEPER);
    }

    /** How many more of the shop's trade the town's shape should count for the shop, past its keeper and its
     *  crafters: its assistants and its stock keeper (Villages.target). */
    public static int staffWanted(@Nullable UUID village) {
        return village == null ? 0 : StoreStaff.assistantsWanted(village) + StoreStaff.stockKeepersWanted(village);
    }

    /** A word for the nameplate ("Stock keeper"), or "" for anybody not of the shop's staff. */
    public static String badge(VillageFolkEntity f) {
        return switch (role(f)) {
            case KEEPER -> "Shopkeeper";
            case ASSISTANT -> "Assistant";
            case CRAFTER -> "Crafter";
            case STOCK_KEEPER -> "Stock keeper";
            case NONE -> "";
        };
    }
}
