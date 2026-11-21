package com.jrpetty.mcassistant.item;

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
 * [quests] The Quest Journal: a player's own book of the quests it has taken on and how each came out. Use it
 * and it opens (client/QuestJournalScreen): what is under way and what is done, each quest's steps, where to go
 * and which way, who gave it, the reward and the day it is due. The server writes the pages (entity/QuestRun);
 * the book itself carries nothing, so a journal lost is nothing lost.
 *
 * <p>It is made of a book, a feather, an ink sac and a sheet of paper. The shop's workshop keeps a couple in the
 * stores once the town has quests going, the storekeeper makes one to order, and a quest giver hands a player
 * its first, out of the stores, the first time it takes a quest on.
 */
public class QuestJournalItem extends Item {

    public QuestJournalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) com.jrpetty.mcassistant.entity.QuestRun.openJournal(sp);
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Your quests: under way, and done").withColor(0xA89A7A));
    }
}
