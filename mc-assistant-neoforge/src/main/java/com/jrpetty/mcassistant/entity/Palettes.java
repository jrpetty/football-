package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.village.ThemeData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
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

    /** A village's choice: the wood of its walls, its roofs, its floors, its frames. */
    public record Look(String walls, String roof, String floor, String frame, List<String> footing, List<String> dressed) {

        /** [styles] The same, in the theme's terms. */
        public ThemeData.Look theme() {
            return new ThemeData.Look(walls, roof, floor, frame, footing, dressed);
        }

        /** [styles] A theme's look, in these terms. */
        public static Look of(ThemeData.Look l) {
            return new Look(l.walls(), l.roof(), l.floor(), l.frame(), l.footing(), l.dressed());
        }
    }

    private static final Map<UUID, Look> LOOKS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKS.clear();
    }

    /** The village's look, worked out from its land (and kept). */
    public static Look of(@Nullable UUID village) {
        if (village == null) return forLand(Themes.of(null), Homeland.Land.PLAINS);
        return LOOKS.computeIfAbsent(village, v -> Architecture.look(v, forLand(Themes.of(v), Homeland.of(v))));   // [culture2] the style's woods
    }

    /** Forget a village's look (its land surveyed afresh, its style or its theme changed). */
    public static void forget(UUID village) {
        LOOKS.remove(village);
    }

    /** [styles] Every village's look forgotten (the themes read afresh). */
    static void forgetAll() {
        LOOKS.clear();
    }

    /** [styles] The look of a town of this theme on this land: the theme's lands (data/mc_assistant/themes). */
    static Look forLand(ThemeData theme, Homeland.Land land) {
        return Look.of(theme.look(land.name()));
    }

    /**
     * The kinds a builder of this village reaches for, best first, for a block of this style (empty
     * for a style the look says nothing about). Every one an item the woods, the mine or the
     * smeltery can give. [styles] The theme's "parts" lists, its look's woods and stones put in.
     */
    public static List<Item> ranked(@Nullable UUID village, Blueprints.Style style) {
        ThemeData t = Themes.of(village);
        Look l = of(village);
        List<Item> out = new ArrayList<>();
        for (String id : t.rankedIds(style.name(), l.theme())) {
            Item it = Themes.item(id);
            if (it != Items.AIR && !out.contains(it)) out.add(it);
        }
        return t.thatch() ? Thatch.palette(village, style, out) : out;   // [workitems] thatch first on a Wood Age roof, with wheat to spare
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
