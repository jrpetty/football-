package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Memory Core — everything a companion IS, dropped when it dies (its gear
 * stays where it fell). Right-click the ground with it and that exact bot
 * comes back: same name, veteran level, role, waypoints, home, and station
 * assignment. Fireproof and slow to despawn, because it's a friend.
 */
public class MemoryCoreItem extends Item {

    public MemoryCoreItem(Properties properties) {
        super(properties.stacksTo(1).fireResistant().rarity(Rarity.RARE));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(ctx.getLevel() instanceof ServerLevel level)
            || !(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = ctx.getItemInHand();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            player.sendSystemMessage(Component.literal(
                "<Assistant> This core is blank — one only forms when a companion falls."));
            return InteractionResult.CONSUME;
        }
        if (AssistantEntity.allFor(player.getUUID()).size() >= AssistantEntity.MAX_PER_OWNER) {
            player.sendSystemMessage(Component.literal(
                "<Assistant> Your crew is full — dismiss someone before a revival."));
            return InteractionResult.CONSUME; // keep the core
        }

        BlockPos pos = ctx.getClickedPos().above();
        AssistantEntity revived = McAssistantMod.ASSISTANT.get().create(level);
        if (revived == null) return InteractionResult.CONSUME;
        revived.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0);
        revived.setOwner(player);
        level.addFreshEntity(revived);
        revived.applyMemoryCore(data.copyTag());
        stack.shrink(1);
        return InteractionResult.CONSUME;
    }
}
