package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.entity.PropertyItems;
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
 * [wpc] A deed: the register's copy of a title (land, a house, premises), in the holder's pack. The register is the truth:
 * used, the deed opens the page for what it names as the register has it today, and says plainly if the title has passed
 * to somebody else since (a sale, an estate, a repossession), in which case the paper is void.
 */
public class DeedItem extends Item {

    public DeedItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) PropertyItems.useDeed(sp, held);
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        CompoundTag t = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!t.contains("name")) {
            lines.add(Component.literal("A blank deed: the register writes them").withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.add(Component.literal(t.getString("name")).withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("Held by " + t.getString("holder") + ", of " + t.getString("townName")).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Written on day " + t.getLong("issued")).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Use it to see the title on the register").withStyle(ChatFormatting.DARK_GRAY));
    }
}
