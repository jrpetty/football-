package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [culture2] A town's own dish (DishItems): food, with a line under its name saying whose dish it is and what good
 * it does, and, for the three served in a bowl, the bowl back when it is eaten (as the game's own stews give it).
 * Where a dish was made, and how (a colony's take on its mother's dish), is written on the stack itself as lore when
 * the town's cook makes it (entity/Cuisine).
 */
public class DishItem extends Item {

    private final boolean bowl;
    private final String whose, good;

    public DishItem(Properties properties, boolean bowl, String whose, String good) {
        super(properties);
        this.bowl = bowl;
        this.whose = whose;
        this.good = good;
    }

    /** Served in a bowl: the bowl comes back when it is eaten. */
    public boolean inABowl() {
        return bowl;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity who) {
        ItemStack left = super.finishUsingItem(stack, level, who);
        if (!bowl || who instanceof Player p && p.getAbilities().instabuild) return left;
        if (left.isEmpty()) return new ItemStack(Items.BOWL);
        if (who instanceof Player p && !p.getInventory().add(new ItemStack(Items.BOWL))) p.drop(new ItemStack(Items.BOWL), false);
        return left;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal(whose).withStyle(ChatFormatting.GRAY));
        if (!good.isEmpty()) lines.add(Component.literal(good).withStyle(ChatFormatting.DARK_GRAY));
    }
}
