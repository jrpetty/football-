package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [leisure] A kite: a diamond of paper on two crossed sticks, a tail of bows, and a string. Dyed the colour of the dye
 * it was made with (and dyed again at the crafting table, as leather is). Right-click with it in hand to send it up:
 * it flies over your head on its string, downwind, swaying, higher the windier the day, and follows wherever you walk.
 * Right-click again (or put it away) and it is reeled in. Not in the rain. The children fly theirs in the park on dry
 * afternoons (entity/Kites).
 */
public class KiteItem extends Item {

    /** An undyed kite: plain paper. */
    public static final int PAPER = 0xEFE6CF;

    public KiteItem(Item.Properties properties) {
        super(properties);
    }

    /** The kite's colour: its dye's, or plain paper. */
    public static int colour(ItemStack s) {
        return DyedItemColor.getOrDefault(s, PAPER) & 0xFFFFFF;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            String said = com.jrpetty.mcassistant.entity.Kites.fromHand(server, player, held);
            if (said != null && !said.isEmpty()) player.displayClientMessage(Component.literal(said), true);
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Right-click to fly it; again to reel it in.").withStyle(ChatFormatting.GRAY));
    }
}
