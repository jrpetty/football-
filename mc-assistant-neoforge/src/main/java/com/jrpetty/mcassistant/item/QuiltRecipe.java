package com.jrpetty.mcassistant.item;

import net.minecraft.core.HolderLookup;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/**
 * [leisure] The patchwork quilt: six wool laid in two rows of three, of three colours at the least (a quilt of one or
 * two is a blanket, and no quilt). The shape alone cannot ask for the colours, so this is a recipe of its own kind
 * (its JSON says only "mc_assistant:patchwork_quilt"). The tailor makes it out of the stores' odd wool, laying the
 * six in the grid and asking this recipe whether they make a quilt (entity/Quilts).
 */
public class QuiltRecipe extends CustomRecipe {

    /** Colours a quilt wants at the least. */
    public static final int COLOURS = 3;

    public QuiltRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (input.width() != 3 || input.height() != 2 || input.ingredientCount() != 6) return false;
        Set<Item> colours = new HashSet<>();
        for (ItemStack s : input.items()) {
            if (s.isEmpty() || !s.is(ItemTags.WOOL)) return false;
            colours.add(s.getItem());
        }
        return colours.size() >= COLOURS;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return new ItemStack(LeisureItems.QUILT_ITEM.get());
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= 3 && height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return LeisureItems.PATCHWORK.get();
    }
}
