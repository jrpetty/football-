package com.jrpetty.mcassistant.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * [tech-tree] The longbow: a yew stave as tall as its archer. Drawn and loosed as a bow is, but its arrow leaves a fifth
 * faster (it carries further) and strikes a quarter harder: InventionArms.onArrow sets the arrow by the weapon it left,
 * whoever drew it, a player or a folk in the line.
 */
public class LongbowItem extends BowItem {

    public LongbowItem(Item.Properties props) {
        super(props);
    }

    @Override
    public int getDefaultProjectileRange() {
        return 20;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Flies a fifth further and strikes a quarter harder than a bow").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
