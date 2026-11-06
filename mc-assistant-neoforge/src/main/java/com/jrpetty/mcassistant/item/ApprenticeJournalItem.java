package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [player-civic] An apprentice's journal: a book bound in leather, with a feather and ink. Opened (used), it shows its
 * owner's trades: who they are learning from, the lessons done, what each has unlocked, and the next lesson
 * (PlayerTrades.page). The master hands one to a new apprentice out of the town's stores, and the tailor binds
 * them for the town's young apprentices, who write up their day at the master's side in it (TradeGoods).
 */
public class ApprenticeJournalItem extends Item {

    public ApprenticeJournalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) com.jrpetty.mcassistant.entity.PlayerTrades.openPage(sp);
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Your trades, your lessons and what they unlock").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Use it to read").withStyle(ChatFormatting.DARK_GRAY));
    }
}
