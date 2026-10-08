package com.jrpetty.mcassistant.item;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;

import javax.annotation.Nullable;

/**
 * [leisure] A shaped recipe like any other, whose result takes the colour of the dye laid in it: three paper, two
 * sticks, a string and a red dye make a red kite. Read from the same JSON as a shaped recipe (its type
 * "mc_assistant:dyed_shaped"); the game's recipe book, the folk's benches (RecipeBook) and the price list (Prices) see
 * the plain thing it makes, and the dye goes into it like any other part. A kite already made is dyed again at the
 * crafting table as leather is (it is in the game's "dyeable" tag).
 */
public class DyedShapedRecipe extends ShapedRecipe {

    public DyedShapedRecipe(ShapedRecipe base) {
        super(base.getGroup(), base.category(), base.pattern, base.getResultItem(RegistryAccess.EMPTY), base.showNotification());
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack out = super.assemble(input, registries);
        DyeColor dye = dyeIn(input);
        if (dye != null && !out.isEmpty()) out.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb(dye), true));
        return out;
    }

    /** The colour a dye gives a thing (the game's own, as leather takes it). */
    public static int rgb(DyeColor c) {
        return c.getTextureDiffuseColor() & 0xFFFFFF;
    }

    /** The first dye laid in the grid, or null. */
    @Nullable
    public static DyeColor dyeIn(CraftingInput input) {
        for (ItemStack s : input.items()) {
            if (s.getItem() instanceof DyeItem d) return d.getDyeColor();
            DyeColor tagged = s.isEmpty() ? null : DyeColor.getColor(s);
            if (tagged != null) return tagged;
        }
        return null;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return LeisureItems.DYED_SHAPED.get();
    }

    /** The shaped recipe's own reading and writing, the dyeing on top. */
    public static class Serializer implements RecipeSerializer<DyedShapedRecipe> {
        private static final MapCodec<DyedShapedRecipe> CODEC = ShapedRecipe.Serializer.CODEC.xmap(DyedShapedRecipe::new, r -> r);
        private static final StreamCodec<RegistryFriendlyByteBuf, DyedShapedRecipe> STREAM_CODEC =
            ShapedRecipe.Serializer.STREAM_CODEC.map(DyedShapedRecipe::new, r -> r);

        @Override
        public MapCodec<DyedShapedRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, DyedShapedRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
