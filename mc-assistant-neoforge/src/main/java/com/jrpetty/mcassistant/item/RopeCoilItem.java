package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.block.RopeBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * [workitems] A rope coil: four lengths of string laid up with a strip of leather for the hitch. Used on the top edge of
 * a drop (the top of the edge block, facing the drop; or its side, from above), it lets a hanging rope down as far as
 * twenty-four blocks, to be climbed like a ladder; break any piece of it and the rope comes up again as the coil.
 * The cave dwellers carry two, and the miners one, for the ravines, pits and shafts (entity/Ropes).
 */
public class RopeCoilItem extends Item {

    public RopeCoilItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos clicked = ctx.getClickedPos();
        Direction face = ctx.getClickedFace();
        // Its side, from above: the rope hangs down that face. Its top: over the edge the way the player looks.
        Direction out = face.getAxis().isHorizontal() ? face : face == Direction.UP ? ctx.getHorizontalDirection() : null;
        if (out == null) return InteractionResult.PASS;
        BlockPos top = clicked.relative(out);
        if (level.isClientSide) return InteractionResult.SUCCESS;
        int n = RopeBlock.hang(level, top, out.getOpposite(), RopeBlock.LONGEST, WorkItems.ROPE.get());
        Player p = ctx.getPlayer();
        if (n <= 0) {
            if (p != null) p.displayClientMessage(Component.literal("There's no drop there to let a rope down."), true);
            return InteractionResult.FAIL;
        }
        if (p != null) {
            if (!p.getAbilities().instabuild) ctx.getItemInHand().shrink(1);
            p.displayClientMessage(Component.literal("You let the rope down: " + n + (n == 1 ? " block" : " blocks")
                + (n >= RopeBlock.LONGEST ? ", the whole coil, and it still doesn't reach the bottom." : ".")), true);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Use on the edge of a drop to let a rope down (" + RopeBlock.LONGEST + " blocks)")
            .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Break the rope to take it up again").withStyle(ChatFormatting.DARK_GRAY));
    }
}
