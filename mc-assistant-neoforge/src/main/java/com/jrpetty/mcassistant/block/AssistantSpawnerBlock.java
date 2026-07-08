package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Assistant Spawner — a placeable block that acts as your assistant's home
 * point. Right-click it and:
 *   - if you already have an assistant, it teleports here (its home is reset
 *     to this block);
 *   - if you don't, a fresh one spawns on top, bound to you.
 * Either way the entity lands in a collision-free spot so it can't suffocate.
 */
public class AssistantSpawnerBlock extends Block {

    public AssistantSpawnerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        Vec3 spot = findSafeSpot(serverLevel, pos);
        AssistantEntity existing = AssistantEntity.byOwner(serverPlayer.getUUID());

        if (existing != null) {
            if (existing.level() != serverLevel) {
                serverPlayer.sendSystemMessage(Component.literal(
                    "<Assistant> I'm in another dimension — come find me, or /assistant dismiss and re-summon."));
                return InteractionResult.CONSUME;
            }
            existing.getNavigation().stop();
            existing.setHome(pos);
            existing.teleportTo(spot.x, spot.y, spot.z);
            existing.setMode(AssistantEntity.Mode.FOLLOW);
            existing.say("Summoned — this spawner is home now.");
            return InteractionResult.CONSUME;
        }

        AssistantEntity assistant = McAssistantMod.ASSISTANT.get().create(serverLevel);
        if (assistant == null) {
            return InteractionResult.CONSUME;
        }
        assistant.moveTo(spot.x, spot.y, spot.z, serverPlayer.getYRot(), 0);
        assistant.setOwner(serverPlayer);
        assistant.setHome(pos);
        serverLevel.addFreshEntity(assistant);
        assistant.say("Assistant online. Talk to me with ! commands (\"!follow\", \"!gather logs 16\", \"!status\") or /assistant.");
        // One spawner, one companion: creating a FRESH assistant uses the block
        // up (with break particles). Re-summoning your crew keeps it; so does
        // creative mode.
        if (!serverPlayer.isCreative()) {
            serverLevel.levelEvent(2001, pos, Block.getId(state));
            serverLevel.removeBlock(pos, false);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * A collision-free spot on top of (or beside) the block, so the 2-block-tall
     * assistant doesn't spawn embedded in a wall and suffocate.
     */
    private static Vec3 findSafeSpot(ServerLevel level, BlockPos pos) {
        int[][] offsets = { {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] off : offsets) {
            double x = pos.getX() + 0.5 + off[0];
            double y = pos.getY() + 1.0;
            double z = pos.getZ() + 0.5 + off[1];
            AABB box = McAssistantMod.ASSISTANT.get().getDimensions().makeBoundingBox(x, y, z);
            if (level.noCollision(box)) {
                return new Vec3(x, y, z);
            }
        }
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }
}
