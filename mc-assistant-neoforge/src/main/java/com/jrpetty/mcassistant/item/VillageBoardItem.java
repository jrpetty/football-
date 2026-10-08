package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.block.VillageBoardBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * A Village Board, folded up. Use it on the ground and it goes up there: ten blocks
 * wide and five high, facing you, standing on the block you used it on and centred where
 * you are looking. Put up near a village, it tells you everything about that village.
 */
public class VillageBoardItem extends Item {

    public VillageBoardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos base = context.getClickedFace() == Direction.UP ? context.getClickedPos().above()
            : context.getClickedPos().relative(context.getClickedFace());
        BlockPos anchor = base.relative(VillageBoardBlock.right(facing), -VillageBoardBlock.WIDE / 2);
        if (!VillageBoardBlock.fits(level, anchor, facing)) {
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(Component.literal(
                    "Not enough room: a Village Board needs a clear space ten blocks wide and five high."), true);
            }
            return InteractionResult.FAIL;
        }
        com.jrpetty.mcassistant.entity.Villages.Village v = com.jrpetty.mcassistant.entity.Villages.nearest(level, anchor,
            com.jrpetty.mcassistant.entity.Villages.VILLAGE_RANGE * 2);
        VillageBoardBlock.raise(level, anchor, facing, v == null ? null : v.id());
        level.playSound(null, base, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
        if (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild) context.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Ten wide, five high: what a village is doing,").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("how it is getting on and what it is working towards.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Every village puts one up on its square.").withStyle(ChatFormatting.DARK_GRAY));
    }
}
