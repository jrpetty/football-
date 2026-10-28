package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * [arms] A tabard and a banner at the crafting table: the tabard takes the banner's arms (its field and its
 * charges), and the banner is used up, as a shield takes one (the game's ShieldDecorationRecipe). A tabard that
 * bears arms already takes no more. The town's tailor does the same with the town's banner (entity/Arms).
 */
public class TabardDecorationRecipe extends CustomRecipe {

    public TabardDecorationRecipe(CraftingBookCategory category) {
        super(category);
    }

    /** The tabard and the banner in the grid, and nothing else; or null. */
    private static ItemStack[] parts(CraftingInput input) {
        ItemStack tabard = ItemStack.EMPTY, banner = ItemStack.EMPTY;
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof BannerItem) {
                if (!banner.isEmpty()) return null;
                banner = s;
            } else if (s.is(McAssistantMod.TABARD.get())) {
                if (!tabard.isEmpty()) return null;
                if (!s.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers().isEmpty()
                    || s.has(DataComponents.BASE_COLOR)) return null;
                tabard = s;
            } else {
                return null;
            }
        }
        return tabard.isEmpty() || banner.isEmpty() ? null : new ItemStack[]{ tabard, banner };
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return parts(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack[] p = parts(input);
        if (p == null) return ItemStack.EMPTY;
        ItemStack out = p[0].copyWithCount(1);
        out.set(DataComponents.BANNER_PATTERNS, p[1].getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY));
        out.set(DataComponents.BASE_COLOR, ((BannerItem) p[1].getItem()).getColor());
        return out;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return McAssistantMod.TABARD_DECORATION.get();
    }
}
