package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.entity.FootballEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.List;

/**
 * [leisure] The leather football: four panels of leather stitched round a wool bladder. Right-click the ground to set
 * it down, and it is a ball (FootballEntity): it rolls, bounces and slows on the grass; run into it to dribble it,
 * punch it to kick it, sneak and right-click it to pick it up again. The children kick it about in the park, and the
 * league's matches are played with it (entity/Kickabout, Football).
 */
public class FootballItem extends Item {

    public FootballItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
        BlockPos at = ctx.getClickedPos();
        Direction face = ctx.getClickedFace();
        BlockPos spot = level.getBlockState(at).getCollisionShape(level, at).isEmpty() ? at : at.relative(face);
        ItemStack held = ctx.getItemInHand();
        FootballEntity ball = FootballEntity.setDown(server, spot.getX() + 0.5, spot.getY() + 0.05, spot.getZ() + 0.5, held.copyWithCount(1), null);
        if (ball == null) return InteractionResult.FAIL;
        Player p = ctx.getPlayer();
        if (p != null) ball.setYRot(p.getYRot());
        level.playSound(null, spot, SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.8F, 1.2F);
        level.gameEvent(p, GameEvent.ENTITY_PLACE, spot);
        if (p == null || !p.getAbilities().instabuild) held.shrink(1);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Right-click the ground to set it down; run into it or punch it to kick it.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Sneak and right-click it to pick it up.").withStyle(ChatFormatting.GRAY));
    }
}
