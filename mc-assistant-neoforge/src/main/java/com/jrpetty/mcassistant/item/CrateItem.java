package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.block.ShippingCrateBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * [workitems] A shipping crate as it is carried: one to a hand's slot, its nine stacks inside it (they show on it), never
 * put in another crate, and spilled where it lies if it is burnt.
 */
public class CrateItem extends BlockItem {

    public CrateItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        List<ItemStack> in = ShippingCrateBlock.contents(stack);
        if (in.isEmpty()) {
            lines.add(Component.literal("Empty: holds nine stacks, and keeps them when broken").withStyle(ChatFormatting.GRAY));
            return;
        }
        int n = 0;
        for (ItemStack s : in) {
            n += s.getCount();
            if (lines.size() < 6) lines.add(Component.literal(s.getCount() + " ").append(s.getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal(in.size() + " of 9 stacks, " + n + " things in all").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public void onDestroyed(ItemEntity e) {
        ShippingCrateBlock.spill(e);
    }
}
