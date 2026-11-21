package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.SimpleTier;

import java.util.List;

/**
 * [workitems] The felling saw: a frame saw, a toothed iron blade held taut in a wooden frame by a twisted string. It
 * cuts wood as quickly as an iron axe, and wears like one (250 cuts). A woodcutter with one fells a whole tree at once,
 * the logs dropping at the stump; a player does the same breaking a tree's log with it while sneaking (entity/WorkTools).
 */
public class FellingSawItem extends DiggerItem {

    /** An iron blade's bite and wear, on wood. */
    public static final Tier BLADE = new SimpleTier(BlockTags.INCORRECT_FOR_IRON_TOOL, 250, 6.0F, 1.0F, 10,
        () -> Ingredient.of(Items.IRON_INGOT));

    /** The most logs of one tree it fells at a go. */
    public static final int MOST_LOGS = 64;

    public FellingSawItem(Properties properties) {
        super(BLADE, BlockTags.MINEABLE_WITH_AXE, properties.attributes(DiggerItem.createAttributes(BLADE, 2.0F, -3.0F)));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Sneak while cutting a tree's log to fell the whole tree").withStyle(ChatFormatting.GRAY));
    }
}
