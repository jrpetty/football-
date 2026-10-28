package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.entity.WarHorn;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [war] The War Horn: blown (use) it sounds its call, the goat horn's own, heard a long way off; sneaking, use picks the
 * next call (muster, march, form up, begin, charge, rally, retreat, victory). A player who leads a town, or fights in
 * its army, gives that army the order with it when it is in the field near by (WarHorn.blown).
 */
public class WarHornItem extends Item {

    /** As long between calls as a goat horn's. */
    static final int COOLDOWN = 140;

    public WarHornItem(Properties properties) {
        super(properties);
    }

    public static WarHorn.Signal signal(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        int i = data == null ? WarHorn.Signal.CHARGE.ordinal() : data.copyTag().getInt("signal");
        WarHorn.Signal[] all = WarHorn.Signal.values();
        return all[Math.floorMod(i, all.length)];
    }

    static void signal(ItemStack stack, WarHorn.Signal s) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, (CompoundTag t) -> t.putInt("signal", s.ordinal()));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            WarHorn.Signal next = WarHorn.Signal.values()[(signal(stack).ordinal() + 1) % WarHorn.Signal.values().length];
            signal(stack, next);
            if (!level.isClientSide) player.displayClientMessage(Component.translatable("item.mc_assistant.war_horn.signal", next.words), true);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        player.startUsingItem(hand);
        if (level instanceof ServerLevel server) WarHorn.blown(server, player, signal(stack));
        player.getCooldowns().addCooldown(this, COOLDOWN);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 40;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.TOOT_HORN;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.mc_assistant.war_horn.signal", signal(stack).words).withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
