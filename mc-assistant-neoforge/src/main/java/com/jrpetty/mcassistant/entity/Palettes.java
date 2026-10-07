package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each village's look: the woods and stones its builders reach for first, so a street is of a
 * piece and not a patchwork of whatever came out of the stores that morning.
 *
 * <p>Every part of a building has its own short list, best first, of the kinds that suit it (six
 * to a dozen): the walls in the wood the village's own woods grow, the roof in a darker wood
 * against them, the floors in a third, the posts and beams in the logs of the roof's wood (dark
 * timbers on light walls), the footings in the land's own stone (sandstone in the desert,
 * terracotta in the badlands, deepslate under the mountains), and dressed stone the same. A
 * builder takes the first kind on the list it has enough of for the building, and goes down the
 * list only for what it has none of — so a house is all one wood, and the next one too, for as
 * long as the woods hold out. Nothing precious goes into a wall or a roof (no iron, no gold); a
 * roof is wood until the Iron Age slates it and the Diamond Age coppers the great ones (Ages).
 * Mossy stone is on the dressed-stone list, but only ever used if the stores happen to hold some.
 */
public final class Palettes {

    private Palettes() {}

    /** The woods, best known first; and the stones a footing or a wall may be. */
    static final List<String> WOODS = List.of("oak", "spruce", "birch", "dark_oak", "jungle", "acacia", "mangrove", "cherry");

    /** A village's choice: the wood of its walls, its roofs, its floors, its frames. */
    public record Look(String walls, String roof, String floor, String frame, List<String> footing, List<String> dressed) {}

    private static final Map<UUID, Look> LOOKS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKS.clear();
    }

    /** The village's look, worked out from its land (and kept). */
    public static Look of(@Nullable UUID village) {
        if (village == null) return forLand(Homeland.Land.PLAINS);
        return LOOKS.computeIfAbsent(village, v -> Architecture.look(v, forLand(Homeland.of(v))));   // [culture2] the style's woods
    }

    /** Forget a village's look (its land surveyed afresh). */
    public static void forget(UUID village) {
        LOOKS.remove(village);
    }

    static Look forLand(Homeland.Land land) {
        return switch (land) {
            case TAIGA, SNOW -> new Look("spruce", "dark_oak", "spruce", "dark_oak",
                List.of("cobblestone", "stone", "andesite", "cobbled_deepslate", "diorite", "granite", "tuff"),
                List.of("stone_bricks", "polished_andesite", "mossy_stone_bricks", "deepslate_bricks", "polished_diorite", "polished_granite"));
            case FOREST -> new Look("birch", "dark_oak", "oak", "dark_oak",
                List.of("cobblestone", "stone", "andesite", "mossy_cobblestone", "diorite", "granite", "cobbled_deepslate"),
                List.of("stone_bricks", "mossy_stone_bricks", "polished_andesite", "polished_diorite", "polished_granite", "deepslate_bricks"));
            case MOUNTAIN -> new Look("spruce", "dark_oak", "spruce", "spruce",
                List.of("cobbled_deepslate", "cobblestone", "andesite", "tuff", "stone", "granite", "diorite"),
                List.of("deepslate_bricks", "stone_bricks", "polished_deepslate", "polished_andesite", "tuff_bricks", "polished_granite"));
            case DESERT -> new Look("birch", "jungle", "acacia", "jungle",
                List.of("sandstone", "smooth_sandstone", "cobblestone", "stone", "andesite", "granite"),
                List.of("cut_sandstone", "smooth_sandstone", "chiseled_sandstone", "stone_bricks", "polished_granite", "polished_andesite"));
            case SAVANNA -> new Look("acacia", "dark_oak", "acacia", "dark_oak",
                List.of("cobblestone", "stone", "granite", "andesite", "terracotta", "diorite"),
                List.of("stone_bricks", "polished_granite", "polished_andesite", "bricks", "polished_diorite", "mud_bricks"));
            case JUNGLE -> new Look("jungle", "dark_oak", "jungle", "jungle",
                List.of("mossy_cobblestone", "cobblestone", "stone", "andesite", "granite", "diorite"),
                List.of("mossy_stone_bricks", "stone_bricks", "polished_andesite", "polished_granite", "polished_diorite", "mud_bricks"));
            case SWAMP -> new Look("mangrove", "dark_oak", "spruce", "mangrove",
                List.of("mud_bricks", "cobblestone", "stone", "andesite", "mossy_cobblestone", "diorite"),
                List.of("mud_bricks", "stone_bricks", "mossy_stone_bricks", "polished_andesite", "polished_diorite", "packed_mud"));
            case BADLANDS -> new Look("dark_oak", "spruce", "acacia", "spruce",
                List.of("terracotta", "red_sandstone", "cobblestone", "stone", "granite", "andesite"),
                List.of("cut_red_sandstone", "smooth_red_sandstone", "bricks", "stone_bricks", "polished_granite", "polished_andesite"));
            case MEADOW -> new Look("cherry", "spruce", "oak", "spruce",
                List.of("cobblestone", "stone", "andesite", "diorite", "granite", "cobbled_deepslate"),
                List.of("stone_bricks", "polished_diorite", "polished_andesite", "mossy_stone_bricks", "polished_granite", "deepslate_bricks"));
            case COAST, RIVER -> new Look("oak", "spruce", "spruce", "spruce",
                List.of("cobblestone", "stone", "andesite", "diorite", "granite", "cobbled_deepslate"),
                List.of("stone_bricks", "polished_andesite", "polished_diorite", "mossy_stone_bricks", "polished_granite", "deepslate_bricks"));
            default -> new Look("oak", "dark_oak", "spruce", "spruce",
                List.of("cobblestone", "stone", "andesite", "diorite", "granite", "cobbled_deepslate", "tuff"),
                List.of("stone_bricks", "polished_andesite", "mossy_stone_bricks", "polished_diorite", "polished_granite", "deepslate_bricks"));
        };
    }

    /** The woods for a part, its own first and the rest after, darker woods earlier for a roof or a frame. */
    private static List<String> woods(String first, boolean dark) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(first);
        if (dark) { out.add("dark_oak"); out.add("spruce"); out.add("mangrove"); }
        out.addAll(WOODS);
        return new ArrayList<>(out);
    }

    /**
     * The kinds a builder of this village reaches for, best first, for a block of this style (empty
     * for a style the look says nothing about). Every one an item the woods, the mine or the
     * smeltery can give.
     */
    public static List<Item> ranked(@Nullable UUID village, Blueprints.Style style) {
        Look l = of(village);
        List<String> ids = new ArrayList<>();
        switch (style) {
            case WALL -> { for (String w : woods(l.walls(), false)) ids.add(planks(w)); ids.add("bamboo_planks"); }
            case FLOOR -> { for (String w : woods(l.floor(), false)) ids.add(planks(w)); ids.add("bamboo_planks"); }
            case ROOF_BLOCK -> { for (String w : woods(l.roof(), true)) ids.add(planks(w)); }
            case ROOF_STAIR, ROOF_STAIR_TOP -> { for (String w : woods(l.roof(), true)) ids.add(w + "_stairs"); }
            case ROOF_SLAB, ROOF_SLAB_TOP -> { for (String w : woods(l.roof(), true)) ids.add(w + "_slab"); }
            case POST, BEAM_ACROSS, BEAM_ALONG -> { for (String w : woods(l.frame(), true)) ids.add(w.equals("bamboo") ? "bamboo_block" : w + "_log"); }
            case FOUNDATION, WALL_LOW -> ids.addAll(l.footing());
            case MASONRY -> ids.addAll(l.dressed());
            default -> { }
        }
        List<Item> out = new ArrayList<>();
        for (String id : ids) {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id));
            if (it != Items.AIR && !out.contains(it)) out.add(it);
        }
        return out;
    }

    private static String planks(String wood) {
        return wood + "_planks";
    }

    /** Never in a wall or a roof: the metals and the gems (an iron roof is a fortune left out in the rain). */
    public static boolean precious(ItemStack s) {
        if (s.isEmpty()) return false;
        String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return p.equals("iron_block") || p.equals("gold_block") || p.equals("diamond_block") || p.equals("emerald_block")
            || p.equals("lapis_block") || p.equals("netherite_block") || p.equals("raw_iron_block") || p.equals("raw_gold_block")
            || p.equals("amethyst_block") || p.equals("redstone_block") || p.equals("copper_block") || p.equals("raw_copper_block");
    }

    /** The village's look in a line (the board, the status): "spruce walls, dark oak roofs, oak floors, cobblestone footings". */
    public static String line(@Nullable UUID village) {
        Look l = of(village);
        return l.walls().replace('_', ' ') + " walls, " + l.roof().replace('_', ' ') + " roofs, " + l.floor().replace('_', ' ')
            + " floors, " + l.frame().replace('_', ' ') + " beams, " + l.footing().get(0).replace('_', ' ') + " footings";
    }
}
