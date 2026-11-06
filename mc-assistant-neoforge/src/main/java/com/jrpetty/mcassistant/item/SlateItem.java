package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [leisure] A slate and chalk: a dark grey slate in a wooden frame, a stick of chalk with it. Smooth stone, a stick and
 * bone meal at the bench. The chalk wears: every line written on it uses a little (its durability), and when the
 * chalk is gone so is the slate. The town's schoolchildren carry one to their lessons (entity/Slates).
 *
 * <p>Right-click to write on it: what it is named (rename it at an anvil to choose the words), or a doodle of a
 * child's; the line stays on it (its tooltip) till you sneak and right-click to wipe it clean.
 */
public class SlateItem extends Item {

    /** The chalk in a new slate: so many lines, or so many beats of lessons. */
    public static final int CHALK = 96;

    private static final String[] DOODLES = { "2 + 2 = 4", "A B C D E F G", "Back in five minutes!", "Mind the creepers!", "Tag — you're it!",
        "☺", "I can spell 'necessary'", "Wheat, carrots, potatoes", "Nine nines are eighty-one", "Here be dragons", "♥", "The quick brown fox",
        "Don't tell the teacher", "Football after school?" };

    public SlateItem(Item.Properties properties) {
        super(properties);
    }

    /** What is written on it now, or null if it is clean. */
    @Nullable
    public static String written(ItemStack s) {
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) return null;
        String t = lore.lines().get(0).getString();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    /** A line chalked up on it (what was there before rubbed out). */
    public static void chalk(ItemStack s, String line) {
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("\"" + line + "\"").withStyle(ChatFormatting.WHITE))));
    }

    /** A child's doodle. */
    public static String doodle(RandomSource r) {
        return DOODLES[r.nextInt(DOODLES.length)];
    }

    /** A little chalk used (the slate gone with the last of it); true if there was chalk to use. */
    public static boolean wear(ItemStack s, @Nullable LivingEntity who) {
        if (s.isEmpty() || !s.isDamageableItem()) return false;
        if (who != null) {
            s.hurtAndBreak(1, who, EquipmentSlot.MAINHAND);
        } else {
            s.setDamageValue(s.getDamageValue() + 1);
            if (s.getDamageValue() >= s.getMaxDamage()) s.shrink(1);
        }
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel server)) return InteractionResultHolder.success(held);
        if (player.isShiftKeyDown()) {
            if (written(held) == null) return InteractionResultHolder.pass(held);
            held.remove(DataComponents.LORE);
            level.playSound(null, player.blockPosition(), SoundEvents.WOOL_HIT, SoundSource.PLAYERS, 0.6F, 1.4F);
            player.displayClientMessage(Component.literal("You wipe the slate clean."), true);
            return InteractionResultHolder.success(held);
        }
        String line = held.has(DataComponents.CUSTOM_NAME) ? held.getHoverName().getString() : doodle(level.getRandom());
        chalk(held, line);
        level.playSound(null, player.blockPosition(), SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, 0.7F, 1.6F);
        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.WHITE_CONCRETE_POWDER.defaultBlockState()),
            player.getX() + player.getLookAngle().x * 0.6, player.getEyeY() - 0.4, player.getZ() + player.getLookAngle().z * 0.6, 5, 0.08, 0.05, 0.08, 0.0);
        player.displayClientMessage(Component.literal("You chalk on the slate: \"" + line + "\""), true);
        wear(held, player);
        player.getCooldowns().addCooldown(this, 10);
        return InteractionResultHolder.success(held);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        if (written(stack) == null) lines.add(Component.literal("Right-click to write on it; sneak and right-click to wipe it.").withStyle(ChatFormatting.GRAY));
        int left = stack.getMaxDamage() - stack.getDamageValue();
        lines.add(Component.literal("Chalk left: " + left + " of " + stack.getMaxDamage()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
