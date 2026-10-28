package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * [police] The Constable's Badge: a special constable's warrant. Sworn to a town (entity/PlayerLaw.swear: the town and
 * the holder written on it), it glints. Used in the air it puts its holder on the town's beat or takes it off (its
 * presence then puts off a thief as the watch's own does); shown to a folk of that town (right-click), it arrests one
 * there is a charge to hold, which follows its holder to the nearest guard. Unsworn it is a pretty thing and no more.
 * The town's constable carries one too, and a culprit it runs close to gives itself up.
 */
public class ConstableBadgeItem extends Item {

    public ConstableBadgeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            com.jrpetty.mcassistant.entity.PlayerLaw.togglePatrol(sp, stack);
            level.playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_CHAIN.value(), SoundSource.PLAYERS, 0.8F, 1.3F);
        }
        player.swing(hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d != null && !d.copyTag().getString("mca_badge_town").isEmpty();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag t = d == null ? new CompoundTag() : d.copyTag();
        if (t.getString("mca_badge_town").isEmpty()) {
            tooltip.add(Component.literal("Not sworn: a guard of the watch swears you in").withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.literal("Special constable of " + t.getString("mca_badge_townName")).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("Sworn: " + t.getString("mca_badge_holder")).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Show it to a culprit to arrest it; use it to walk the beat").withStyle(ChatFormatting.DARK_GRAY));
    }
}
