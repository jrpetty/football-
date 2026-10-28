package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.block.WindowBoxBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * [workitems] A window box as it is carried: which flower is in it (the recipe's), shown on it and in its name's line.
 * Its flower travels as the block's own state (the game's block_state component, set by its recipe), so it is set down
 * just as it was made.
 */
public class WindowBoxItem extends BlockItem {

    public WindowBoxItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** The flower in this box (poppies, if nothing says otherwise). */
    public static WindowBoxBlock.Flower flower(ItemStack stack) {
        BlockItemStateProperties p = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY);
        String name = p.properties().get("flower");
        if (name != null) for (WindowBoxBlock.Flower f : WindowBoxBlock.Flower.values()) if (f.getSerializedName().equals(name)) return f;
        return WindowBoxBlock.Flower.POPPY;
    }

    /** A box of this flower. */
    public static ItemStack of(WindowBoxBlock.Flower f) {
        ItemStack s = new ItemStack(WorkItems.WINDOW_BOX_ITEM.get());
        if (f == WindowBoxBlock.Flower.POPPY) return s;              // the plain box is the poppies' (its recipe's), so they stack
        s.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(WindowBoxBlock.FLOWER, f));
        return s;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        String f = flower(stack).plural();
        lines.add(Component.literal("Planted with " + f).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Hang it under a window; water it with a can or a bucket").withStyle(ChatFormatting.DARK_GRAY));
    }
}
