package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * [war-after] The Wanted Poster: an outlaw's likeness and crimes, as a town posts them (entity/Outlaws). Blank, used in a
 * town it is copied off the board with the most wanted of its outlaws; written up, it says who, for what, where and for
 * how much, and glints while the bounty stands.
 */
public class WantedPosterItem extends Item {

    public WantedPosterItem(Properties properties) {
        super(properties);
    }

    static CompoundTag tag(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? new CompoundTag() : d.copyTag();
    }

    public static boolean blank(ItemStack stack) {
        return tag(stack).getString("mca_wanted_player").isEmpty();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!blank(stack)) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide && level instanceof ServerLevel sl && player instanceof ServerPlayer sp
                && com.jrpetty.mcassistant.entity.Outlaws.fillPoster(sl, sp, stack)) {
            level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8F, 1.0F);
        }
        player.swing(hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return tag(stack).getInt("mca_wanted_bounty") > 0;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag t = tag(stack);
        if (t.getString("mca_wanted_player").isEmpty()) {
            tooltip.add(Component.literal("Blank: use it in a town to copy its most wanted off the board").withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.literal("WANTED: " + t.getString("mca_wanted_name")).withStyle(ChatFormatting.RED));
        tooltip.add(Component.literal("Outlaw of " + t.getString("mca_wanted_townName") + " since day " + t.getLong("mca_wanted_since")).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("For " + t.getString("mca_wanted_why")).withStyle(ChatFormatting.GRAY));
        int b = t.getInt("mca_wanted_bounty");
        tooltip.add(Component.literal(b > 0 ? "Bounty: " + b + " coins, of any guard of the town" : "No bounty: the treasury could not run to one").withStyle(ChatFormatting.GOLD));
    }
}
