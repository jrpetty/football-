package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [player-civic] The brewer's stout: a dark beer of the town's wheat and sugar, drunk from the bottle, that puts
 * a will into a body for an hour of digging (Haste) and a little in the belly. The bottle comes back, as a honey
 * bottle's does. A master brewer brews it for the tavern, where the folk buy it of an evening (TradeGoods); a
 * player brews it once a master brewer has taught them (TrainedRecipe, "brew").
 */
public class BrewersStoutItem extends Item {

    public BrewersStoutItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity who) {
        ItemStack left = super.finishUsingItem(stack, level, who);
        if (who instanceof Player p && p.getAbilities().instabuild) return left;
        if (left.isEmpty()) return new ItemStack(Items.GLASS_BOTTLE);
        if (who instanceof Player p && !p.getInventory().add(new ItemStack(Items.GLASS_BOTTLE))) {
            p.drop(new ItemStack(Items.GLASS_BOTTLE), false);
        }
        return left;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity who) {
        return 32;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public SoundEvent getDrinkingSound() {
        return SoundEvents.GENERIC_DRINK;
    }

    @Override
    public SoundEvent getEatingSound() {
        return SoundEvents.GENERIC_DRINK;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Haste for two minutes; the bottle comes back").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Brewed by a master brewer, or one they taught").withStyle(ChatFormatting.DARK_GRAY));
    }
}
