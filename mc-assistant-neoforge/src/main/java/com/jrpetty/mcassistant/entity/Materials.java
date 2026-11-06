package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.ThemeData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [styles] What a town builds with when the block a design wants is short: the theme's materials (THEMES.md).
 *
 * <ul>
 * <li><b>Never</b>, at any age, a block of the theme's "forbidden" list as building material: the precious blocks
 *     (diamond, emerald, gold, iron, lapis...) and the Nether's and the End's (netherrack, nether brick, blackstone,
 *     basalt, quartz, obsidian, end stone, purpur...), unless the theme names that very block as its own somewhere (its
 *     parts, its ages' or styles' rules, a palette: the Nether Age's quartz footings under the great buildings).</li>
 * <li><b>No rough fill</b> (earth, gravel, sand, wool...: the "rough" list) in a wall, a floor or a roof after the
 *     theme's "rough_until" age (the medieval theme's Stone Age): from the Iron Age a town is built of proper stuff.</li>
 * <li><b>The stand-ins</b>, best first, age by age: the "fallback" list of the part's group (walls, floors, roofs).
 *     A builder short of the town's own block goes down it (stone bricks, cobblestone, stone, planks...) and, past it,
 *     waits for the stores rather than lay dirt or a diamond block. Before then (the Wood and Stone Ages) any proper
 *     block it has will do, as it always has, so a young town is never held up.</li>
 * </ul>
 * The earth of a flower box, the glass of a window and the rest of the parts in no group are not held to the lists
 * (only never a forbidden block). Asked by the builder (BuildGoal.takeStyled), the rebuilders after a fire
 * (Rebuilding), and the renovation (Renovation), which also takes out what an older town laid that is not fit.
 */
public final class Materials {

    private Materials() {}

    /** The age a town's builders build for: its own; a hired hand's (no town) builds as a young town does. */
    static Villages.Age ageOf(@Nullable UUID village) {
        if (village == null || Villages.get(village) == null) return Villages.Age.WOOD;
        return Villages.ageOf(village);
    }

    private static List<String> ages() {
        List<String> out = new ArrayList<>();
        for (Villages.Age a : Villages.Age.values()) out.add(a.name());
        return out;
    }

    /** Is this block one of the theme's own (named by it anywhere)? Never held against it. */
    static boolean named(ThemeData t, Block b) {
        return Themes.named(t).contains(b);
    }

    /** What each theme has said of a block or an item: forbidden (1), rough (2), both, or neither; asked once. */
    private static final java.util.Map<String, java.util.Map<Object, Integer>> SAID = new java.util.concurrent.ConcurrentHashMap<>();

    /** Forget what was said (the themes read afresh). */
    static void forget() {
        SAID.clear();
    }

    private static int said(ThemeData t, BlockState s) {
        return SAID.computeIfAbsent(t.id, k -> new java.util.concurrent.ConcurrentHashMap<>()).computeIfAbsent(s.getBlock(), k -> {
            if (s.isAir() || named(t, s.getBlock())) return 0;
            BlockState d = s.getBlock().defaultBlockState();
            int out = 0;
            for (String w : t.forbidden()) if (Themes.blocks(w).test(d)) { out |= 1; break; }
            for (String w : t.rough()) if (Themes.blocks(w).test(d)) { out |= 2; break; }
            return out;
        });
    }

    private static int said(ThemeData t, ItemStack s) {
        if (s.isEmpty()) return 0;
        return SAID.computeIfAbsent(t.id, k -> new java.util.concurrent.ConcurrentHashMap<>()).computeIfAbsent(s.getItem(), k -> {
            if (s.getItem() instanceof BlockItem bi && named(t, bi.getBlock())) return 0;
            ItemStack one = new ItemStack(s.getItem());
            int out = 0;
            for (String w : t.forbidden()) if (Themes.items(w).test(one)) { out |= 1; break; }
            for (String w : t.rough()) if (Themes.items(w).test(one)) { out |= 2; break; }
            return out;
        });
    }

    /** A block the theme never builds with (and does not name as its own). */
    public static boolean forbidden(ThemeData t, BlockState s) {
        return (said(t, s) & 1) != 0;
    }

    public static boolean forbidden(ThemeData t, ItemStack s) {
        return (said(t, s) & 1) != 0;
    }

    /** Rough fill (earth, gravel, sand, wool), not the theme's own. */
    public static boolean rough(ThemeData t, BlockState s) {
        return (said(t, s) & 2) != 0;
    }

    public static boolean rough(ThemeData t, ItemStack s) {
        return (said(t, s) & 2) != 0;
    }

    /** Is the town past the age of rough fill and of any block it has (the Iron Age, in the medieval theme)? */
    public static boolean proper(@Nullable UUID village) {
        ThemeData t = Themes.of(village);
        int until;
        try {
            until = Villages.Age.valueOf(t.roughUntil()).ordinal();
        } catch (IllegalArgumentException e) {
            until = Villages.Age.STONE.ordinal();
        }
        return ageOf(village).ordinal() > until;
    }

    /** Is this part held to the materials' lists (a wall, a floor, a roof...)? */
    static boolean governed(ThemeData t, Blueprints.Style style) {
        return t.governed(style.name());
    }

    /**
     * May a builder of this town lay this in a block of this kind (beyond the town's own list, which always may)?
     * Never a forbidden block; and once the town is past rough fill, nothing rough in a wall, a floor or a roof.
     */
    public static boolean suitable(@Nullable UUID village, Blueprints.Style style, ItemStack s) {
        ThemeData t = Themes.of(village);
        if (forbidden(t, s)) return false;
        if (!governed(t, style)) return true;
        return !(proper(village) && rough(t, s));
    }

    /** May the builder still fall back on any proper block it has, past the stand-ins (a young town; an ungoverned part)? */
    public static boolean anyGoes(@Nullable UUID village, Blueprints.Style style) {
        return !governed(Themes.of(village), style) || !proper(village);
    }

    /** The stand-ins for a block of this kind in this town's age, best first. */
    public static List<Predicate<ItemStack>> fallback(@Nullable UUID village, Blueprints.Style style) {
        ThemeData t = Themes.of(village);
        List<Predicate<ItemStack>> out = new ArrayList<>();
        for (String w : t.fallback(style.name(), ageOf(village).name(), ages())) out.add(Themes.items(w));
        return out;
    }

    /**
     * Is the block standing in a building's place of this kind not fit for it in this town: forbidden, or rough once the
     * town is past rough fill? (The renovation takes it out; the rebuilders do not put it back.)
     */
    public static boolean unfit(@Nullable UUID village, Blueprints.Style style, BlockState s) {
        ThemeData t = Themes.of(village);
        if (forbidden(t, s)) return true;
        return governed(t, style) && proper(village) && rough(t, s);
    }

    /**
     * A block for a place of this kind out of the stores, paid for: the town's own first (Palettes.ranked), then the
     * stand-ins, then (a young town) whatever proper block they hold. Null if the stores have nothing fit: the work
     * waits for them. The block as laid (its default state).
     */
    @Nullable
    public static BlockState take(ServerLevel level, Villages.Village v, Blueprints.Style style) {
        UUID id = v.id();
        for (Item it : Palettes.ranked(id, style)) {
            if (it instanceof BlockItem bi && Crafts.take(level, v, s -> s.is(it), 1)) return bi.getBlock().defaultBlockState();
        }
        Predicate<ItemStack> fit = s -> s.getItem() instanceof BlockItem && suitable(id, style, s)
            && (BuildGoal.isRoof(style) ? Masonry.fitForARoof(s) : !Palettes.precious(s));
        for (Predicate<ItemStack> p : fallback(id, style)) {
            ItemStack got = Crafts.takeOne(level, v, p.and(fit));
            if (!got.isEmpty()) return ((BlockItem) got.getItem()).getBlock().defaultBlockState();
        }
        if (anyGoes(id, style)) {
            ItemStack got = Crafts.takeOne(level, v, fit.and(BuildGoal::isBuildingBlock));
            if (!got.isEmpty()) return ((BlockItem) got.getItem()).getBlock().defaultBlockState();
        }
        return null;
    }

    /** Tests: the first of these the builder would take for a block of this kind, as BuildGoal.takeStyled orders them. */
    @Nullable
    public static Item choiceForTests(@Nullable UUID village, Blueprints.Style style, List<ItemStack> held) {
        return BuildGoal.choose(village, style, held);
    }

}
