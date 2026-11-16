package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [econ-1b] The kinds of business a folk may open of its own (Enterprise): what each is called, the work its hands do
 * (one of the town's own work loops, the hands tagged as the firm's: no new trade), its premises, the age and size of town
 * it wants, the wares it sells, the hands it starts with besides its founder, and whether it makes what it sells or buys it
 * in to sell on.
 *
 * <ul>
 * <li><b>The makers</b>: the toolsmith and the armourer at the smith's anvil, the carpenter at the shop's bench (chests,
 *     barrels, beds, tables), the clothier and the weaver at the tailor's, the chandler at the hives (candles), the potter
 *     and the glassworks at the furnace.</li>
 * <li><b>Food and drink</b>: the baker, the butcher and the café at the cook's fire, the fishmonger's stall of its own
 *     catch, the tavern at the brewer's, the apothecary's draughts.</li>
 * <li><b>Shops</b>: the greengrocer and the general store, which buy in at the market and sell on, and the stationer.</li>
 * </ul>
 *
 * <p>Premises: a <b>stall</b> on the square (eight coins, its own once paid for), or a <b>room</b> let from the town in the
 * building of its trade (a bench in the smithy, a corner of the workshop), paid for in its rent. A firm that does well takes
 * larger premises (Enterprise: expansion).
 */
public final class BusinessTypes {

    private BusinessTypes() {}

    /** One kind of business. {@code goods}: its wares by their names in the books (Stockroom.key); "drink/*" every café drink. */
    public record BusinessType(String key, String title, String pattern, String noun, StationTask work, @Nullable ShopRoles.Role role,
                               String premises, Villages.Age from, int minFolk, List<String> goods, int startStaff, boolean resells,
                               double perDay) {
        /** "Wren's Tools", for a founder's first name. */
        public String nameFor(String first) {
            return String.format(Locale.ROOT, pattern, first + (first.endsWith("s") ? "'" : "'s"));
        }

        /** Its wares, the café's drinks among them for a tavern. */
        public List<String> wares() {
            if (!goods.contains("drink/*")) return goods;
            List<String> out = new ArrayList<>();
            for (String g : goods) if (!g.equals("drink/*")) out.add(g);
            for (Stockroom.Ware w : Cafe.cafeWares()) if (w.key().startsWith("drink/")) out.add(w.key());
            return out;
        }

        public boolean stall() {
            return premises.equals("stall");
        }
    }

    static String k(Item it) {
        return Stockroom.key(new ItemStack(it));
    }

    static List<String> keys(Item... items) {
        List<String> out = new ArrayList<>();
        for (Item it : items) out.add(k(it));
        return out;
    }

    /** Every kind, the makers first. */
    public static final List<BusinessType> ALL = List.of(
        // ---------------------------------------------------------------- the makers
        new BusinessType("toolsmith", "Toolsmith", "%s Tools", "toolshop", StationTask.SMITH, null, "stall", Villages.Age.STONE, 10,
            keys(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE, Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL,
                Items.IRON_HOE, Items.SHEARS, Items.BUCKET), 0, false, CostBook.SMITHS_A_DAY / 4),
        new BusinessType("armourer", "Armourer", "%s Armoury", "armoury", StationTask.SMITH, null, "room", Villages.Age.IRON, 18,
            keys(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.SHIELD, Items.IRON_SWORD, Items.STONE_SWORD),
            0, false, CostBook.SMITHS_A_DAY / 8),
        new BusinessType("carpenter", "Carpenter", "%s Joinery", "joinery", StationTask.SHOP, ShopRoles.Role.CRAFTER, "stall", Villages.Age.STONE, 10,
            List.of(k(Items.CHEST), k(Items.BARREL), "bed", k(Items.CRAFTING_TABLE), k(Items.ITEM_FRAME), k(Items.PAINTING), k(Items.LADDER)),
            0, false, CostBook.CRAFTS_A_DAY / 8),
        new BusinessType("clothier", "Clothier", "%s Clothes", "clothes shop", StationTask.TAILOR, null, "stall", Villages.Age.STONE, 12,
            keys(Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS), 0, false, CostBook.CRAFTS_A_DAY / 12),
        new BusinessType("weaver", "Weaver", "%s Looms", "weaving shed", StationTask.TAILOR, null, "stall", Villages.Age.STONE, 12,
            List.of("wool", "rug", "banner"), 0, false, CostBook.CRAFTS_A_DAY / 4),
        new BusinessType("chandler", "Chandler", "%s Candles", "chandlery", StationTask.BEEKEEP, null, "stall", Villages.Age.STONE, 12,
            List.of("candle", k(Items.HONEY_BOTTLE), k(Items.HONEYCOMB)), 0, false, CostBook.CRAFTS_A_DAY / 8),
        new BusinessType("potter", "Potter", "%s Pottery", "pottery", StationTask.SMELT, null, "stall", Villages.Age.STONE, 12,
            keys(Items.FLOWER_POT, Items.BRICK, Items.TERRACOTTA, Items.DECORATED_POT), 0, false, CostBook.SMELTS_A_DAY / 8),
        new BusinessType("glassworks", "Glassworks", "%s Glassworks", "glassworks", StationTask.SMELT, null, "room", Villages.Age.STONE, 14,
            keys(Items.GLASS, Items.GLASS_PANE, Items.GLASS_BOTTLE), 0, false, CostBook.SMELTS_A_DAY / 4),
        // ---------------------------------------------------------------- food and drink
        new BusinessType("baker", "Baker", "%s Bakery", "bakery", StationTask.COOK, null, "stall", Villages.Age.WOOD, 8,
            keys(Items.BREAD, Items.CAKE, Items.COOKIE, Items.PUMPKIN_PIE), 0, false, 32),
        new BusinessType("butcher", "Butcher", "%s Butchery", "butcher's", StationTask.COOK, null, "stall", Villages.Age.STONE, 10,
            keys(Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON), 0, false, 24),
        new BusinessType("fishmonger", "Fishmonger", "%s Fish Stall", "fish stall", StationTask.FISH, null, "stall", Villages.Age.WOOD, 8,
            keys(Items.COD, Items.SALMON, Items.COOKED_COD, Items.COOKED_SALMON), 0, false, 24),
        new BusinessType("cafe", "Café", "%s Café", "café", StationTask.COOK, null, "room", Villages.Age.STONE, 14,
            keys(Items.MUSHROOM_STEW, Items.BEETROOT_SOUP, Items.BAKED_POTATO, Items.COOKIE), 1, false, 24),
        new BusinessType("tavern", "Tavern", "%s Tavern", "tavern", StationTask.BREW, null, "room", Villages.Age.STONE, 14,
            List.of("drink/*"), 1, false, CostBook.BREWS_A_DAY / 2),
        new BusinessType("inn", "Inn", "%s Inn", "inn", StationTask.COOK, null, "room", Villages.Age.IRON, 30,
            keys(Items.COOKED_BEEF, Items.BREAD, Items.BAKED_POTATO, Items.RABBIT_STEW), 2, false, 24),
        new BusinessType("apothecary", "Apothecary", "%s Apothecary", "apothecary's", StationTask.BREW, null, "stall", Villages.Age.IRON, 20,
            List.of("potion", k(Items.GLASS_BOTTLE), k(Items.GLISTERING_MELON_SLICE)), 0, false, CostBook.BREWS_A_DAY / 4),
        // ---------------------------------------------------------------- shops
        new BusinessType("greengrocer", "Greengrocer", "%s Greengrocer's", "greengrocer's", StationTask.SHOP, ShopRoles.Role.ASSISTANT, "stall",
            Villages.Age.WOOD, 12, keys(Items.CARROT, Items.POTATO, Items.APPLE, Items.BEETROOT, Items.MELON_SLICE, Items.SWEET_BERRIES), 0, true, 64),
        new BusinessType("generalstore", "General store", "%s Store", "general store", StationTask.SHOP, ShopRoles.Role.ASSISTANT, "room",
            Villages.Age.STONE, 16, keys(Items.TORCH, Items.LANTERN, Items.BUCKET, Items.SHEARS, Items.FLINT_AND_STEEL, Items.FISHING_ROD), 1, true, 64),
        new BusinessType("stationer", "Stationer", "%s Stationery", "stationer's", StationTask.CARTOGRAPHER, null, "stall", Villages.Age.IRON, 20,
            keys(Items.PAPER, Items.BOOK, Items.WRITABLE_BOOK, Items.MAP), 0, false, CostBook.CRAFTS_A_DAY / 8)
    );

    /** A kind by its key, or null. */
    @Nullable
    public static BusinessType of(String key) {
        if (key == null) return null;
        for (BusinessType t : ALL) if (t.key().equalsIgnoreCase(key)) return t;
        return null;
    }

    /** The kinds a town of this age and size may see opened. */
    static List<BusinessType> allowed(java.util.UUID village) {
        Villages.Age age = Villages.ageOf(village);
        int folk = Villages.headcount(village);
        List<BusinessType> out = new ArrayList<>();
        for (BusinessType t : ALL) if (age.ordinal() >= t.from().ordinal() && folk >= t.minFolk()) out.add(t);
        return out;
    }

    /** Every kind's key, for the commands. */
    public static List<String> keys() {
        List<String> out = new ArrayList<>();
        for (BusinessType t : ALL) out.add(t.key());
        return out;
    }
}
