package com.jrpetty.mcassistant.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Putting goods away so they stack the way a person stacks them: onto the part stacks of the
 * same thing first, wherever in the stores they are, and only then into an empty slot.
 *
 * <p>Every way goods went into the village's stores used to have its own little loop, and most of
 * them put a stack into the FIRST EMPTY SLOT they came to, before ever looking further along for a
 * part stack of the same: a storehouse of seven hundred slots ended up holding forty half stacks of
 * cobblestone, eleven of wheat and a run of one-and-twos of everything, and the stores' chests were
 * filled one after another without ever topping up the one before. Some of them also set a stack of
 * more than a slot holds straight into an empty slot, and the chest cut it down to sixty-four — the
 * rest of a bundle of wool simply went. This is the one way in now, for all of them:
 * <ol>
 * <li>onto the part stacks of the same thing — the same item with the same components, so a smith's
 *     marked pickaxe and an enchanted book never stack with a plain one, as in the game — in every
 *     one of the containers, in the order given (the storehouse first);</li>
 * <li>then into empty slots, in the same order, never more in a slot than it holds, and never where
 *     the container would not take it.</li>
 * </ol>
 * And the kinds a storekeeper puts goods in order by, for the storehouse's own tidy.
 */
public final class Stacking {

    private Stacking() {}

    /** The most of this one slot of this container holds. */
    public static int cap(Container c, ItemStack s) {
        return Math.max(1, Math.min(c.getMaxStackSize(), s.getMaxStackSize()));
    }

    /** Into one container: onto its part stacks of the same first, then its empty slots. Returns what would not fit. */
    public static ItemStack insert(Container c, ItemStack stack) {
        return insert(List.of(c), stack, null);
    }

    /** Into these containers, in their order (see the class). Returns what would not fit. */
    public static ItemStack insert(List<? extends Container> into, ItemStack stack) {
        return insert(into, stack, null);
    }

    /**
     * Into these containers, in their order: onto every part stack of the same in all of them first,
     * then into empty slots. The stack given is not changed; what would not fit comes back. If
     * {@code took} is given, {@code took[i]} is raised by what went into the i-th container. Every
     * container something went into is marked changed.
     */
    public static ItemStack insert(List<? extends Container> into, ItemStack stack, @Nullable int[] took) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack rest = stack.copy();
        boolean[] touched = new boolean[into.size()];
        // First: top up what is there already, in every one of them.
        for (int k = 0; k < into.size() && !rest.isEmpty(); k++) {
            Container c = into.get(k);
            int cap = cap(c, rest);
            for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
                ItemStack in = c.getItem(i);
                if (in.isEmpty() || in.getCount() >= cap || !ItemStack.isSameItemSameComponents(in, rest)) continue;
                if (!c.canPlaceItem(i, rest)) continue;
                int m = Math.min(cap - in.getCount(), rest.getCount());
                in.grow(m);
                rest.shrink(m);
                touched[k] = true;
                if (took != null && k < took.length) took[k] += m;
            }
        }
        // Then: empty slots, a slot's worth at a time. (The storehouse grows as it fills, so its
        // size is read again every step.)
        for (int k = 0; k < into.size() && !rest.isEmpty(); k++) {
            Container c = into.get(k);
            int cap = cap(c, rest);
            for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
                if (!c.getItem(i).isEmpty() || !c.canPlaceItem(i, rest)) continue;
                int m = Math.min(cap, rest.getCount());
                c.setItem(i, rest.copyWithCount(m));
                rest.shrink(m);
                touched[k] = true;
                if (took != null && k < took.length) took[k] += m;
            }
        }
        for (int k = 0; k < into.size(); k++) if (touched[k]) into.get(k).setChanged();
        return rest.isEmpty() ? ItemStack.EMPTY : rest;
    }

    /** Is there room in this container for any of this: a part stack of the same, or an empty slot? */
    public static boolean hasRoomFor(Container c, ItemStack s) {
        int cap = cap(c, s);
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty() || (in.getCount() < cap && ItemStack.isSameItemSameComponents(in, s))) return true;
        }
        return false;
    }

    /**
     * Top up the part stacks of one container from the others like them, the later into the
     * earlier, without moving anything else. Returns how many stacks that freed (merged away).
     */
    public static int compact(Container c) {
        int freed = 0;
        int size = c.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack a = c.getItem(i);
            if (a.isEmpty()) continue;
            int cap = cap(c, a);
            for (int j = size - 1; j > i && a.getCount() < cap; j--) {
                ItemStack b = c.getItem(j);
                if (b.isEmpty() || !ItemStack.isSameItemSameComponents(a, b)) continue;
                int m = Math.min(cap - a.getCount(), b.getCount());
                a.grow(m);
                b.shrink(m);
                if (b.isEmpty()) {
                    c.setItem(j, ItemStack.EMPTY);
                    freed++;
                }
            }
        }
        if (freed > 0) c.setChanged();
        return freed;
    }

    /** How many slots of this container hold something. */
    public static int used(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) n++;
        return n;
    }

    // ------------------------------------------------------------------ the kinds, in the storehouse's order

    /** The kinds a storekeeper keeps together, in the order they stand in the storehouse. */
    public static final String[] KINDS = { "food", "crops and seed", "timber", "stone and earth", "ore and metal",
        "cloth and hides", "tools, arms and armour", "everything else" };

    /** Which of the {@link #KINDS} this is (its place in the storehouse's order). */
    public static int kind(ItemStack s) {
        if (s.isEmpty()) return KINDS.length - 1;
        if (s.get(DataComponents.FOOD) != null) return 0;
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (s.is(Items.WHEAT) || s.is(ItemTags.SAPLINGS) || path.endsWith("_seeds") || s.is(Items.SUGAR_CANE)
            || s.is(Items.PUMPKIN) || s.is(Items.MELON) || s.is(Items.COCOA_BEANS) || s.is(Items.NETHER_WART)
            || s.is(Items.BONE_MEAL) || s.is(Items.EGG) || s.is(Items.HONEYCOMB)) return 1;
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(Items.STICK)) return 2;
        if (s.isDamageableItem()) return 6;
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(Items.DIAMOND) || s.is(Items.EMERALD) || s.is(Items.REDSTONE)
            || s.is(Items.LAPIS_LAZULI) || s.is(Items.QUARTZ) || s.is(Items.FLINT) || path.startsWith("raw_")
            || path.endsWith("_ingot") || path.endsWith("_nugget") || path.endsWith("_ore")) return 4;
        if (s.is(ItemTags.WOOL) || s.is(Items.LEATHER) || s.is(Items.RABBIT_HIDE) || s.is(Items.STRING)
            || s.is(Items.FEATHER) || s.is(ItemTags.WOOL_CARPETS)) return 5;
        if (s.getItem() instanceof BlockItem b) {
            net.minecraft.world.level.block.state.BlockState st = b.getBlock().defaultBlockState();
            if (st.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || st.is(net.minecraft.tags.BlockTags.DIRT)
                || st.is(net.minecraft.tags.BlockTags.SAND) || path.contains("cobble") || path.contains("brick")
                || path.equals("gravel") || path.equals("clay") || path.contains("deepslate") || path.contains("smooth_stone")
                || path.equals("glass")) return 3;
        }
        if (s.is(Items.CLAY_BALL) || s.is(Items.BRICK)) return 3;
        return KINDS.length - 1;
    }
}
