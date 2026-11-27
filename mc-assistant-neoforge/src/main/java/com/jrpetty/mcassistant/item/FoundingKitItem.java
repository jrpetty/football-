package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.entity.FoundingEasy;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [found23] Founding Kit: everything a party needs to go and found a town, in one bundle. Right-click it (anywhere) and
 * the founding screen opens: where (here, or one of the best spots near), how big, its name. It is used up when the
 * founding is confirmed, not before. Every new player is given one (config foundingFreeKit); it is cheap to make.
 */
public class FoundingKitItem extends Item {

    public FoundingKitItem(Properties properties) {
        super(properties.stacksTo(16).rarity(Rarity.UNCOMMON));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) FoundingEasy.open(sp, "kit");
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getPlayer() == null) return InteractionResult.PASS;
        if (!ctx.getLevel().isClientSide && ctx.getPlayer() instanceof ServerPlayer sp) FoundingEasy.open(sp, "kit");
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Right-click to start a village").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
