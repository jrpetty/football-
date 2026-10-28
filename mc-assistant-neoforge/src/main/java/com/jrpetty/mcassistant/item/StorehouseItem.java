package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** A storehouse unit: on its own a crate; twenty-seven in a cube, a Village Storehouse. */
public class StorehouseItem extends BlockItem {

    public StorehouseItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        int stacks = StorehouseBlockEntity.stacksCarried(stack);
        if (stacks > 0) {
            tooltip.add(Component.literal("Carrying a storehouse's goods: " + stacks + " stacks").withStyle(ChatFormatting.GOLD));
        }
        tooltip.add(Component.literal("27 in a 3×3×3 cube join into a Village Storehouse").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("(as much as 27 chests; a village keeps everything in it)").withStyle(ChatFormatting.DARK_GRAY));
    }
}
