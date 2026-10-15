package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.block.QuiltBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.List;

/**
 * [leisure] The patchwork quilt in the hand: right-click a bed (either half) to lay it over the bed, its foot and half
 * its head, the pillow left out. It goes on nothing but a bed. A folk who sleeps under one wakes the happier for it,
 * and the warmer in winter (entity/Quilts); a player's own bed is the better-looking for it.
 */
public class QuiltItem extends BlockItem {

    public QuiltItem(QuiltBlock block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos clicked = ctx.getClickedPos();
        BlockState bed = level.getBlockState(clicked);
        Player player = ctx.getPlayer();
        if (!(bed.getBlock() instanceof BedBlock)) {
            if (player != null && !level.isClientSide) player.displayClientMessage(Component.literal("A quilt is laid on a bed."), true);
            return InteractionResult.FAIL;
        }
        BlockPos foot = bed.getValue(BedBlock.PART) == BedPart.FOOT ? clicked : clicked.relative(bed.getValue(BedBlock.FACING).getOpposite());
        BlockState footState = level.getBlockState(foot);
        BlockPos at = foot.above();
        if (!QuiltBlock.bedFoot(footState) || !level.getBlockState(at).canBeReplaced()) {
            if (player != null && !level.isClientSide) player.displayClientMessage(Component.literal("There's no room over the bed for a quilt."), true);
            return InteractionResult.FAIL;
        }
        if (level.getBlockState(at).getBlock() instanceof QuiltBlock) return InteractionResult.FAIL;
        if (!level.isClientSide) {
            level.setBlock(at, ((QuiltBlock) getBlock()).onBed(footState), 3);
            level.playSound(null, at, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
            level.gameEvent(player, GameEvent.BLOCK_PLACE, at);
            ItemStack held = ctx.getItemInHand();
            if (player == null || !player.getAbilities().instabuild) held.shrink(1);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Right-click a bed to lay it over the bed.").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
