package com.jrpetty.mcassistant.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * [player-civic] A master's recipe: a shaped crafting recipe like any other (the town's makers read it out of the
 * game's recipes, so the folk know it, and the price list prices it), that a <em>player</em> can only craft once a
 * master of its trade has taught them it (PlayerTrades). The trade is the recipe's own field ("trade": "smith").
 *
 * <p>The grid never says who is crafting, so the recipe looks for the player whose crafting grid holds just what
 * it was given (the crafting table's or the inventory's own); found, it asks whether that player has learned the
 * trade. Nobody's grid (a crafter block, another mod's machine) makes nothing: the folk's benches never ask a
 * recipe to match, they take its parts out of the stores themselves (TradeGoods), so they are not held back by it.
 */
public class TrainedRecipe extends ShapedRecipe {

    private final String trade;
    private final ItemStack made;

    public TrainedRecipe(String trade, String group, CraftingBookCategory category, ShapedRecipePattern pattern,
                         ItemStack result, boolean showNotification) {
        super(group, category, pattern, result, showNotification);
        this.trade = trade;
        this.made = result;
    }

    /** The trade a player must have learned ("smith", "brew", "cook"). */
    public String trade() { return trade; }

    public ItemStack made() { return made; }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (!super.matches(input, level)) return false;
        List<Player> at = crafters(input, level);
        if (at.isEmpty()) return false;
        // Two players with the very same grid at the very same moment: both must have learned it, so nobody
        // crafts on another's lessons.
        for (Player p : at) if (!com.jrpetty.mcassistant.entity.PlayerTrades.knows(p.getUUID(), trade)) return false;
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CivicItems.TRAINED.get();
    }

    /** Who is crafting this input: the player handed to the game while it takes a result, else every player whose grid holds it. */
    public static List<Player> crafters(CraftingInput input, Level level) {
        Player hooked = net.neoforged.neoforge.common.CommonHooks.getCraftingPlayer();
        if (hooked != null) return List.of(hooked);
        List<Player> found = new ArrayList<>();
        for (Player p : level.players()) {
            AbstractContainerMenu m = p.containerMenu;
            int from, to;
            if (m instanceof CraftingMenu) { from = 1; to = 10; }
            else if (m instanceof InventoryMenu) { from = InventoryMenu.CRAFT_SLOT_START; to = InventoryMenu.CRAFT_SLOT_END; }
            else continue;
            if (to <= m.slots.size() && holds(m, from, to, input)) found.add(p);
        }
        return found;
    }

    /** Does the menu's grid hold this input: the same things, in the same order, leaving the empty squares out? */
    private static boolean holds(AbstractContainerMenu m, int from, int to, CraftingInput input) {
        List<ItemStack> grid = new ArrayList<>();
        for (int i = from; i < to; i++) {
            ItemStack s = m.getSlot(i).getItem();
            if (!s.isEmpty()) grid.add(s);
        }
        List<ItemStack> asked = new ArrayList<>();
        for (ItemStack s : input.items()) if (!s.isEmpty()) asked.add(s);
        if (grid.size() != asked.size()) return false;
        for (int i = 0; i < grid.size(); i++) {
            if (!ItemStack.isSameItemSameComponents(grid.get(i), asked.get(i)) || grid.get(i).getCount() != asked.get(i).getCount()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the serializer

    public static final class Serializer implements RecipeSerializer<TrainedRecipe> {

        private static final MapCodec<TrainedRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("trade").forGetter(TrainedRecipe::trade),
            Codec.STRING.optionalFieldOf("group", "").forGetter(ShapedRecipe::getGroup),
            CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter(ShapedRecipe::category),
            ShapedRecipePattern.MAP_CODEC.forGetter(r -> r.pattern),
            ItemStack.STRICT_CODEC.fieldOf("result").forGetter(TrainedRecipe::made),
            Codec.BOOL.optionalFieldOf("show_notification", true).forGetter(ShapedRecipe::showNotification)
        ).apply(i, TrainedRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, TrainedRecipe> STREAM_CODEC = StreamCodec.of(
            (buf, r) -> {
                buf.writeUtf(r.trade());
                buf.writeUtf(r.getGroup());
                CraftingBookCategory.STREAM_CODEC.encode(buf, r.category());
                ShapedRecipePattern.STREAM_CODEC.encode(buf, r.pattern);
                ItemStack.STREAM_CODEC.encode(buf, r.made());
                buf.writeBoolean(r.showNotification());
            },
            buf -> new TrainedRecipe(buf.readUtf(), buf.readUtf(), CraftingBookCategory.STREAM_CODEC.decode(buf),
                ShapedRecipePattern.STREAM_CODEC.decode(buf), ItemStack.STREAM_CODEC.decode(buf), buf.readBoolean()));

        @Override
        public MapCodec<TrainedRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, TrainedRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
