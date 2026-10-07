package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [kitchen] The bandage: a roll of clean cloth, wound tight. Right-click to bind a wound: four hearts back over five
 * seconds (the third rung of regeneration, a heart every twelve ticks), then a short wait before the next, as there
 * is with an ender pearl. A whole body has nothing to bind, and keeps its bandage. The watch and the cave team carry
 * two to four each out of the stores, and bind their own after a fight; the healer binds the hurt on its round
 * (entity/Kitchen).
 */
public class BandageItem extends Item {

    /** Five seconds of the third rung of regeneration: four hearts. */
    public static final int BIND_TICKS = 100, BIND_AMPLIFIER = 2;
    /** The wait before the next one, in ticks. */
    public static final int COOLDOWN = 160;

    public BandageItem(Properties properties) {
        super(properties);
    }

    /** A wound bound: the good of it, on whoever it is bound on (a player, or a folk: entity/Kitchen). */
    public static void bind(LivingEntity who) {
        who.addEffect(new MobEffectInstance(MobEffects.REGENERATION, BIND_TICKS, BIND_AMPLIFIER));
        who.level().playSound(null, who.blockPosition(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.9F, 1.3F);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getHealth() >= player.getMaxHealth()) {
            if (!level.isClientSide) player.displayClientMessage(Component.literal("Nothing to bind: you're whole."), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            bind(player);
            player.getCooldowns().addCooldown(this, COOLDOWN);
            player.awardStat(Stats.ITEM_USED.get(this));
            stack.consume(1, player);
        }
        player.swing(hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Right-click to bind a wound: four hearts over five seconds").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Rolled by the healer, or the tailor; the watch carries a few").withStyle(ChatFormatting.DARK_GRAY));
    }
}
