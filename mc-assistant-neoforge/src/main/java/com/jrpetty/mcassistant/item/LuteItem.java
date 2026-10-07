package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [leisure] The lute: a round-bellied body and a long neck, strung with three strings. Held in the hand. Right-click
 * and hold to strum a short tune on it (a guitar's voice, the harp's ringing over it on the strong beats, the notes
 * rising off it); let go and the tune stops. The town's buskers and its band play it (entity/Lutes).
 */
public class LuteItem extends Item {

    /** A tune's length in ticks: sixteen beats, a beat every four ticks. */
    public static final int LENGTH = 64;

    public LuteItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(this)) return InteractionResultHolder.fail(held);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(held);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity who) {
        return LENGTH;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity who, ItemStack stack, int remaining) {
        if (!(level instanceof ServerLevel server)) return;
        int t = LENGTH - remaining;
        if (t % 4 != 0) return;
        int beat = t / 4;
        int tune = Math.floorMod(who.getUUID().hashCode() + (int) (level.getGameTime() / 2400L), com.jrpetty.mcassistant.entity.Lutes.TUNES.length);
        com.jrpetty.mcassistant.entity.Lutes.strum(server, who, com.jrpetty.mcassistant.entity.Lutes.TUNES[tune], beat, 100, 1.0F);
        if (beat % 4 == 0) who.swing(who.getUsedItemHand() == null ? InteractionHand.MAIN_HAND : who.getUsedItemHand(), true);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity who, int remaining) {
        if (who instanceof Player p) p.getCooldowns().addCooldown(this, 10);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity who) {
        if (who instanceof Player p) p.getCooldowns().addCooldown(this, 16);
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Hold right-click to strum a tune.").withStyle(ChatFormatting.GRAY));
    }
}
