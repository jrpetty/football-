package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [kitchen] Food a player eats out of the hand that the folk keep for its own hour (KitchenItems): the packed lunch, kept
 * in the pack for the midday meal out at a far plot, and the honey cake, kept in the stores for the feast. It carries no
 * food of its own to the game's eye, so no hand eats it as a ration at its work or takes it out of the stores for any old
 * meal (entity/Kitchen eats it at its hour); a player eats it all the same, and it is as good a meal as it says.
 */
public class KitchenFoodItem extends Item {

    private final FoodProperties food;
    private final int ticks;
    private final String use, made;

    public KitchenFoodItem(Properties properties, FoodProperties food, int ticks, String use, String made) {
        super(properties);
        this.food = food;
        this.ticks = ticks;
        this.use = use;
        this.made = made;
    }

    /** What eating one does: its hunger, its saturation, and any good in it. */
    public FoodProperties meal() {
        return food;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.canEat(food.canAlwaysEat())) return InteractionResultHolder.fail(stack);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity who) {
        return who.eat(level, stack, food);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.EAT;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity who) {
        return ticks;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal(use).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(made).withStyle(ChatFormatting.DARK_GRAY));
    }
}
