package com.jrpetty.mcassistant.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [trade-world] The lands over the sea whose merchant ships call at a town's harbour (Harbours), as data: each land's
 * name and the coast it lies on, the names its ships and its people go by, the colour it flies at the masthead and
 * dresses its crews in, what its holds carry (the things a town of folk cannot make for itself yet: spices and sweet
 * things, coloured glass, books, the woods of far forests, dyes the meadows here give no flower for, now and then a
 * music disc, an armour trim or a treatise on some art the town has not got), and what it will buy of a town's surplus.
 *
 * <p>A ship's cargo comes from abroad, as the game's own wandering trader's does: it is the one thing in a harbour
 * town that is not out of somebody's pack, chest or stores. It is kept finite (a few lots a visit, counted, and gone
 * when sold), it is paid for in real coin by whoever buys it, and what the ship buys in turn goes away with it.
 */
public final class ForeignShips {

    private ForeignShips() {}

    /** One lot a land's ships carry: what, how many to a lot, and how far over its usual worth a far-off thing sells. */
    public record Ware(Item item, int lot, double markup, String words) {}

    /** A land over the sea. */
    public record Land(String id, String name, String coast, String people, int colour, Block flag, List<String> ships,
                       List<String> names, List<Ware> goods, List<Item> wants, List<String> treatises) {}

    public static final List<Land> LANDS = List.of(
        new Land("qadira", "Qadira", "the Spice Coast", "Qadiri", 0xD9822B, Blocks.ORANGE_WOOL,
            List.of("the Saffron Star", "the Cinnamon Wind", "the Sultan's Daughter", "the Amber Dhow"),
            List.of("Zahir", "Imra", "Farouq", "Selene", "Tamir", "Nadia", "Rashan", "Yasmeen"),
            List.of(new Ware(Items.COCOA_BEANS, 16, 1.6, "cocoa beans"), new Ware(Items.SUGAR, 16, 1.5, "sugar"),
                new Ware(Items.ORANGE_DYE, 8, 1.6, "saffron dye"), new Ware(Items.RED_DYE, 8, 1.5, "madder red"),
                new Ware(Items.YELLOW_DYE, 8, 1.5, "turmeric yellow"), new Ware(Items.MELON_SLICE, 16, 1.4, "melons"),
                new Ware(Items.HONEY_BOTTLE, 4, 1.6, "date honey"), new Ware(Items.ACACIA_LOG, 16, 1.5, "acacia wood")),
            List.of(Items.WHEAT, Items.BREAD, Items.WHITE_WOOL, Items.IRON_INGOT, Items.LEATHER, Items.COD),
            List.of("navigation", "irrigation")),
        new Land("vellmark", "Vellmark", "the Glass Isles", "Vellish", 0x3AB3DA, Blocks.LIGHT_BLUE_WOOL,
            List.of("the Clear Pane", "the Lantern of Vell", "the Glass Heron", "the Prism"),
            List.of("Aldous", "Brisa", "Corwin", "Ilse", "Mattias", "Oriel", "Saskia", "Tobin"),
            List.of(new Ware(Items.GLASS, 32, 1.4, "glass"), new Ware(Items.GLASS_PANE, 32, 1.4, "window glass"),
                new Ware(Items.TINTED_GLASS, 4, 1.6, "smoked glass"), new Ware(Items.AMETHYST_SHARD, 8, 1.6, "amethyst"),
                new Ware(Items.LIGHT_BLUE_STAINED_GLASS, 16, 1.5, "blue glass"), new Ware(Items.SPYGLASS, 1, 1.5, "a spyglass"),
                new Ware(Items.LIGHT_BLUE_DYE, 8, 1.5, "sea-blue dye"), new Ware(Items.GLASS_BOTTLE, 16, 1.3, "bottles")),
            List.of(Items.COBBLESTONE, Items.STONE_BRICKS, Items.COAL, Items.OAK_LOG, Items.SPRUCE_LOG, Items.SAND),
            List.of("optics", "glassmaking")),
        new Land("orrisand", "Orrisand", "the Northern Reaches", "Orrish", 0x6B4A2E, Blocks.BROWN_WOOL,
            List.of("the Grey Gull", "the Long Winter", "the Scholar", "the Northern Reach"),
            List.of("Bjorna", "Eirik", "Hallvard", "Ingrid", "Ketil", "Sigrun", "Torvald", "Ylva"),
            List.of(new Ware(Items.BOOK, 4, 1.5, "bound books"), new Ware(Items.PAPER, 24, 1.4, "fine paper"),
                new Ware(Items.DARK_OAK_LOG, 16, 1.5, "dark oak"), new Ware(Items.SPRUCE_LOG, 16, 1.3, "northern spruce"),
                new Ware(Items.MAP, 1, 1.5, "a sea chart"), new Ware(Items.LEAD, 2, 1.4, "rope leads"),
                new Ware(Items.WHITE_WOOL, 16, 1.3, "fleeces"), new Ware(Items.BLUE_ICE, 4, 1.6, "blue ice")),
            List.of(Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.APPLE, Items.CARROT, Items.LEATHER, Items.IRON_INGOT),
            List.of("masonry", "printing")),
        new Land("teshwan", "Teshwan", "the Jade Isles", "Teshwani", 0x4CB04C, Blocks.LIME_WOOL,
            List.of("the Jade Crane", "the Lotus", "the Bamboo Tiger", "the Pearl of Teshwan"),
            List.of("Ai", "Daisuke", "Hana", "Kenji", "Lin", "Mei", "Ren", "Suyin"),
            List.of(new Ware(Items.BAMBOO, 32, 1.4, "bamboo"), new Ware(Items.CHERRY_LOG, 16, 1.5, "cherry wood"),
                new Ware(Items.JUNGLE_LOG, 16, 1.5, "jungle wood"), new Ware(Items.MANGROVE_LOG, 16, 1.5, "mangrove wood"),
                new Ware(Items.CYAN_DYE, 8, 1.5, "jade dye"), new Ware(Items.PINK_DYE, 8, 1.5, "blossom pink"),
                new Ware(Items.MAGENTA_DYE, 8, 1.5, "plum dye"), new Ware(Items.TURTLE_SCUTE, 2, 1.6, "turtle shell")),
            List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.WHEAT, Items.COBBLESTONE, Items.CHARCOAL),
            List.of("shipwright", "silk"))
    );

    /** The rarities a ship carries now and then, one at most a visit: a disc, an armour trim, a treatise. */
    static final List<Item> DISCS = List.of(Items.MUSIC_DISC_CAT, Items.MUSIC_DISC_BLOCKS, Items.MUSIC_DISC_CHIRP,
        Items.MUSIC_DISC_FAR, Items.MUSIC_DISC_MALL, Items.MUSIC_DISC_MELLOHI, Items.MUSIC_DISC_STAL, Items.MUSIC_DISC_STRAD);
    static final List<Item> TRIMS = List.of(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE,
        Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE);

    /** The mark a far land's treatise carries (its topic), for the town's scholars to read: see {@link #treatise}. */
    public static final String TREATISE = "mca_treatise";

    @Nullable
    public static Land land(String id) {
        for (Land l : LANDS) if (l.id().equals(id)) return l;
        return null;
    }

    public static int index(Land l) {
        return LANDS.indexOf(l);
    }

    /** A ship's name for a land's visit (a land's ships come in turn, so the same name is not seen twice running). */
    static String shipName(Land l, int visit) {
        return l.ships().get(Math.floorMod(visit, l.ships().size()));
    }

    /** A name for one of a land's people. */
    static String personName(Land l, RandomSource r) {
        return l.names().get(r.nextInt(l.names().size()));
    }

    /**
     * A treatise from a far land: a written book on some art ("Of Navigation, as the Qadiri practise it"), marked with its
     * topic so a town's scholars could take it up (the cross-link the inventions want: {@link #treatise}).
     */
    static ItemStack treatiseOn(Land l, String topic) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        String title = "Of " + Character.toUpperCase(topic.charAt(0)) + topic.substring(1);
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(Filterable.passThrough(Component.literal(title + ", as the " + l.people() + " practise it.\n\nCopied out at "
            + l.coast() + " for the merchants' trade, and carried over the sea.")));
        pages.add(Filterable.passThrough(Component.literal("What is written here is the " + l.people() + " way of " + topic
            + ": their measures, their tools and their patience. A town that studies it may find it has learned something.")));
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), l.name() + " scribes", 0, pages, true));
        CompoundTag tag = new CompoundTag();
        tag.putString(TREATISE, topic);
        tag.putString(TREATISE + "_land", l.id());
        CustomData.set(DataComponents.CUSTOM_DATA, book, tag);
        return book;
    }

    /** [cross-link] The topic of a far land's treatise ("navigation", "optics", ...), or null if this is not one. */
    @Nullable
    public static String treatise(ItemStack s) {
        if (s.isEmpty() || !s.is(Items.WRITTEN_BOOK)) return null;
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null) return null;
        CompoundTag t = d.copyTag();
        return t.contains(TREATISE) ? t.getString(TREATISE) : null;
    }

    /** A dyed piece of a crew's dress in its land's colour (bought abroad, worn here: it goes away with them). */
    static ItemStack dyed(Item leather, int colour) {
        ItemStack s = new ItemStack(leather);
        s.set(DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(colour, false));
        return s;
    }
}
