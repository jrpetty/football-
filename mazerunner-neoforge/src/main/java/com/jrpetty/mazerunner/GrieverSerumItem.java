package com.jrpetty.mazerunner;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Griever Serum — the cure for the Changing. Harvested from a slain
 * Griever, it purges the venom (poison, weakness, nausea) and steadies the
 * runner for a few seconds afterwards.
 */
public class GrieverSerumItem extends Item {

    /** Ticks of recovery granted after the venom is purged. */
    private static final int RECOVERY_TICKS = 200;
    private static final int COOLDOWN_TICKS = 40;

    public GrieverSerumItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide) {
            boolean wasChanging = player.hasEffect(MobEffects.POISON)
                || player.hasEffect(MobEffects.WEAKNESS)
                || player.hasEffect(MobEffects.CONFUSION);

            player.removeEffect(MobEffects.POISON);
            player.removeEffect(MobEffects.WEAKNESS);
            player.removeEffect(MobEffects.CONFUSION);
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, RECOVERY_TICKS, 0));
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, RECOVERY_TICKS, 0));

            player.displayClientMessage(Component.literal(wasChanging
                    ? "The Changing recedes — the serum burns it out of you."
                    : "The serum steadies you.")
                .withStyle(ChatFormatting.GREEN), true);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.HONEY_DRINK,
            SoundSource.PLAYERS, 1.0F, 1.2F);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
