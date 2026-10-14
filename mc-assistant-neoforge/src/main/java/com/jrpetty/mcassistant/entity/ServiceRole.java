package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.ServiceHours;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [shops-homes] The town's service trades, each served the one way (ServicePoints): its building, the point where it
 * serves (the service counter, or the trade's own bench: the bank's ledger desk, the smith's anvil, the tailor's
 * loom), the hours it keeps, who keeps it, and the desk that does its business (ShopDesk, BankDesk, TavernDesk,
 * MakerDesk). Data, so a new trade is a new line here and a desk of its own.
 */
public enum ServiceRole {
    SHOP("shop", "Shop", "shopkeeper", new ServiceHours(1000, 13000), Point.COUNTER, List.of(Store.STRUCTURE, "shop"),
        AssistantEntity.StationTask.SHOP),
    CAFE("cafe", "Café", "cook", new ServiceHours(500, 14000), Point.COUNTER, List.of("cafe"), AssistantEntity.StationTask.COOK),
    TAVERN("tavern", "Tavern", "barkeep", new ServiceHours(10500, 19500), Point.COUNTER, List.of("tavern"), AssistantEntity.StationTask.COOK),
    BANK("bank", "Bank", "banker", new ServiceHours(1500, 11500), Point.LECTERN, List.of("bank"), AssistantEntity.StationTask.BANK),
    SMITHY("smithy", "Smithy", "smith", new ServiceHours(1000, 12000), Point.ANVIL, List.of("smithy"), AssistantEntity.StationTask.SMITH),
    TAILOR("tailor", "Tailor's", "tailor", new ServiceHours(1000, 12000), Point.LOOM, List.of("workshop"), AssistantEntity.StationTask.TAILOR);

    /** What a role serves at. */
    public enum Point {
        COUNTER, LECTERN, ANVIL, LOOM;

        /** Is this block a point of this kind? */
        public boolean is(BlockState s) {
            return switch (this) {
                case COUNTER -> s.getBlock() instanceof com.jrpetty.mcassistant.block.ServiceCounterBlock;
                case LECTERN -> s.getBlock() instanceof LecternBlock;
                case ANVIL -> s.getBlock() instanceof AnvilBlock;
                case LOOM -> s.getBlock() instanceof LoomBlock;
            };
        }
    }

    public final String id, title, keeperNoun;
    public final ServiceHours hours;
    public final Point point;
    /** The buildings it serves from, the grandest first. */
    public final List<String> structures;
    /** The trade that keeps it. */
    public final AssistantEntity.StationTask trade;

    ServiceRole(String id, String title, String keeperNoun, ServiceHours hours, Point point, List<String> structures,
                AssistantEntity.StationTask trade) {
        this.id = id;
        this.title = title;
        this.keeperNoun = keeperNoun;
        this.hours = hours;
        this.point = point;
        this.structures = structures;
        this.trade = trade;
    }

    /** The role called this, or null. */
    @Nullable
    public static ServiceRole of(String id) {
        for (ServiceRole r : values()) if (r.id.equals(id)) return r;
        return null;
    }

    /** The role served from this building, if any. */
    @Nullable
    public static ServiceRole servedIn(String structure) {
        for (ServiceRole r : values()) if (r.structures.contains(structure)) return r;
        return null;
    }

    /** The desk that does its business. */
    ServicePoints.Desk desk() {
        return switch (this) {
            case SHOP, CAFE -> ShopDesk.INSTANCE;
            case BANK -> BankDesk.INSTANCE;
            case TAVERN -> TavernDesk.INSTANCE;
            case SMITHY, TAILOR -> MakerDesk.INSTANCE;
        };
    }

    /** Can the bar serve itself when nobody keeps it (the honesty jar)? Only the tavern's. */
    public boolean selfServe() {
        return this == TAVERN;
    }
}
