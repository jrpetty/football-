package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.entity.IdCard;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.LifePage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [wpa] Citizen papers: a player's ID card, issued at the Town Hall. Its face (the tooltip) says whose it is, of which
 * town and since when, as written on the day it was issued; used, it opens the owner's card as the register has it
 * today (IdCard), so a promotion or a new name shows without new papers.
 */
public class CitizenPapersItem extends Item {

    public CitizenPapersItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            CompoundTag t = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (t.hasUUID("owner")) PlayerLife.open(sp, "id", LifePage.args("who", t.getUUID("owner").toString()));
            else IdCard.openOwn(sp);
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        CompoundTag t = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!t.contains("name")) {
            lines.add(Component.literal("Blank papers: the Town Hall writes them out").withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.add(Component.literal(t.getString("name")).withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal((t.getBoolean("citizen") ? "Citizen of " : "Resident of ") + t.getString("townName")).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Issued on day " + t.getLong("issued")).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Use it to show the card").withStyle(ChatFormatting.DARK_GRAY));
    }
}
