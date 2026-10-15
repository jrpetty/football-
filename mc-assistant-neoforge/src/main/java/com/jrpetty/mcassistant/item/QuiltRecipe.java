package com.jrpetty.mcassistant.item;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/**
 * [leisure] The patchwork quilt: six wool laid in two rows of three, of three colours at the least (a quilt of one or
 * two is a blanket, and no quilt). The shape alone cannot ask for the colours, so this is a recipe of its own kind
 * (its JSON says only "mc_assistant:patchwork_quilt"). The tailor makes it out of the stores' odd wool, laying the
 * six in the grid and asking this recipe whether they make a quilt (entity/Quilts).
 *
 * <p>The game's recipe book and the town's benches (RecipeBook, Bench, the price list) are told one way that always
 * makes a quilt: two of the warm wools, two of the cool and two of the plain, so whatever wool a bench takes for it, it
 * takes three colours at the least. The grid itself takes any six of three colours or more.
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

    /** A quilt: what it makes, for the recipe book and the benches. */
    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return new ItemStack(LeisureItems.QUILT_ITEM.get());
    }

    /** Not one of the game's hidden recipes (dyeing, fireworks): it shows in the book, and the benches can read it. */
    @Override
    public boolean isSpecial() {
        return false;
    }

    /** The warm wools, the cool and the plain: two of each make a quilt of three colours whichever are taken. */
    public static final Item[][] GROUPS = {
        { Items.RED_WOOL, Items.ORANGE_WOOL, Items.YELLOW_WOOL, Items.PINK_WOOL, Items.MAGENTA_WOOL, Items.BROWN_WOOL },
        { Items.BLUE_WOOL, Items.LIGHT_BLUE_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.GREEN_WOOL, Items.LIME_WOOL },
        { Items.WHITE_WOOL, Items.LIGHT_GRAY_WOOL, Items.GRAY_WOOL, Items.BLACK_WOOL },
    };

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> out = NonNullList.create();
        for (Item[] g : GROUPS) {
            Ingredient one = Ingredient.of(g);
            out.add(one);
            out.add(one);
        }
        return out;
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
