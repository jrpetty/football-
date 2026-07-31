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
 * Assistant Spawner — place it and your companion appears on the spot, bound
 * to you, with home set right there; the block is used up in the act (one
 * spawner, one companion). If your crew is already full the block just sits
 * placed (mine it back, dismiss someone, try again). Right-clicking a placed
 * spawner still works as before for leftover blocks: it summons your existing
 * assistant home for free, or spawns a fresh one and consumes the block.
 * Either way the entity lands in a collision-free spot so it can't suffocate.
 */
public class AssistantSpawnerBlock extends Block {

    public AssistantSpawnerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @javax.annotation.Nullable net.minecraft.world.entity.LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)
            || !(placer instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (AssistantEntity.allFor(serverPlayer.getUUID()).size() >= AssistantEntity.MAX_PER_OWNER) {
            serverPlayer.sendSystemMessage(Component.literal(
                "<Assistant> You already run a full crew — dismiss one first (the spawner will keep)."));
            return; // block stays placed; mine it back when you're ready
        }
        Vec3 spot = findSafeSpot(serverLevel, pos);
        AssistantEntity assistant = McAssistantMod.ASSISTANT.get().create(serverLevel);
        if (assistant == null) return;
        assistant.moveTo(spot.x, spot.y, spot.z, serverPlayer.getYRot(), 0);
        assistant.setOwner(serverPlayer);
        assistant.setHome(pos);
        serverLevel.addFreshEntity(assistant);
        assistant.say("Reporting in — this spot is home. RIGHT-CLICK me to open my screen, "
            + "use the arrows to pick what I specialise in, and put the tools I ask for in my pack. "
            + "I'll work the ground around me unless you mark me a patch with a Work Zone Marker.");
        // Hand over the handbook so the whole system explains itself in-game.
        // Only if they haven't got one, so a second bot doesn't mean a second book
        // — but a player who lost theirs gets a replacement with their next hire.
        if (!com.jrpetty.mcassistant.item.AssistantHandbook.carriedBy(serverPlayer)) {
            com.jrpetty.mcassistant.item.AssistantHandbook.giveTo(serverPlayer);
            assistant.say("Put a handbook in your pack too — everything I can do is in there.");
        }
        // The act of placing spends the spawner: one spawner, one companion.
        serverLevel.levelEvent(2001, pos, Block.getId(state));
        serverLevel.removeBlock(pos, false);
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
        assistant.say("Reporting in. RIGHT-CLICK me to pick what I specialise in — "
            + "the handbook covers the rest.");
        if (!com.jrpetty.mcassistant.item.AssistantHandbook.carriedBy(serverPlayer)) {
            com.jrpetty.mcassistant.item.AssistantHandbook.giveTo(serverPlayer);
        }
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
