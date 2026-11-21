package com.jrpetty.mcassistant.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [tech-tree] The arbalest: a crossbow with a steel prod, spanned with a windlass. It takes three quarters as long again
 * to span ({@link #SLOW}: let go before then and it is not spanned), and its bolt strikes half again as hard and flies a
 * little flatter (InventionArms.onArrow). The town's crossbowmen take longer over it too (Armies.reloadTicks).
 */
public class ArbalestItem extends CrossbowItem {

    /** How much longer it takes to span than a crossbow, in hundredths. */
    public static final int SLOW = 175;

    public ArbalestItem(Item.Properties props) {
        super(props);
    }

    /** Ticks to span it, by a player: the crossbow's own, three quarters as long again. */
    public static int spanTicks(ItemStack stack, LivingEntity who) {
        return CrossbowItem.getChargeDuration(stack, who) * SLOW / 100;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity who) {
        return spanTicks(stack, who) + 3;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity who, int timeLeft) {
        int used = getUseDuration(stack, who) - timeLeft;
        if (used < spanTicks(stack, who)) return;                  // let go too soon: the windlass runs back
        super.releaseUsing(stack, level, who, timeLeft);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, lines, flag);
        lines.add(Component.literal("Slow to span; its bolt strikes half again as hard").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
