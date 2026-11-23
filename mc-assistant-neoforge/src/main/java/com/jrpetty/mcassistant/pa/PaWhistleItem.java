package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [pa33] The Assistant Whistle: the crafted way to a Player AI Assistant. Used on the ground with none of your own,
 * it makes one there, yours for good (the whistle is spent). With one already: blown, it calls it to you at once
 * (no cooldown), and brings a fallen one straight back (spent then too).
 */
public class PaWhistleItem extends Item {

    public PaWhistleItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof ServerPlayer p)) return InteractionResult.PASS;
        BlockPos at = ctx.getClickedPos().relative(ctx.getClickedFace());
        blow(p, ctx.getItemInHand(), at);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(held);
        if (player instanceof ServerPlayer p) blow(p, held, null);
        return InteractionResultHolder.consume(held);
    }

    private static void blow(ServerPlayer p, ItemStack held, BlockPos at) {
        p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.PLAYERS, 1.0F, 1.6F);
        PaData.Rec r = PaData.first(p.getUUID());
        if (r == null) {
            if (PaLife.create(p, at, "whistle") != null && !p.getAbilities().instabuild) held.shrink(1);
            return;
        }
        boolean dead = r.dead(p.serverLevel().getGameTime());
        PaLife.call(p, r, true);
        if (dead && !p.getAbilities().instabuild) held.shrink(1);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Use on the ground: your Player AI Assistant appears there.").withStyle(net.minecraft.ChatFormatting.GRAY));
        lines.add(Component.literal("Blow it: your assistant comes to you; a fallen one comes straight back.").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
