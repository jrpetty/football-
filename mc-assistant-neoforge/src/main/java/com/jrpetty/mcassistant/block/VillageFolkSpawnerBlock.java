package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * The Village Folk Spawner: place it, and a settler stands up.
 *
 * <p>Exactly the Assistant Spawner's shape — a block you craft, place and
 * spend — because that is the one way of getting a hand into the world here
 * that has been proven to work. The first one placed founds a settlement on
 * the spot and leaves the founding stores beside it; every one placed after
 * that within reach adds a settler to it. No charter to right-click, no
 * command to remember.
 */
public class VillageFolkSpawnerBlock extends Block {

    public VillageFolkSpawnerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level instanceof ServerLevel server)
            || !(placer instanceof ServerPlayer player)) {
            return;
        }
        VillageFolkEntity folk = raise(server, pos, player.getYRot());
        if (folk == null) {
            player.sendSystemMessage(Component.literal(
                "<Village> That settlement is full. Found another further out."));
            return;   // the block stays; mine it back
        }
        player.displayClientMessage(Component.literal(
            Villages.headcount(folk.ownerId()) + " settled here — they will sort themselves out."), true);
        // Placing spends the spawner: one block, one settler.
        server.levelEvent(2001, pos, Block.getId(state));
        server.removeBlock(pos, false);
    }

    /**
     * Stand one settler up at this spot, founding a village if there is none
     * within reach. Shared with the /village command, so both do exactly the
     * same thing. Returns null when the settlement is at its cap.
     */
    @Nullable
    public static VillageFolkEntity raise(ServerLevel server, BlockPos at, float yaw) {
        Villages.Village village = Villages.nearest(server, at, Villages.VILLAGE_RANGE * 2);
        boolean founding = village == null;
        if (founding) village = Villages.found(server, at);
        int cap = com.jrpetty.mcassistant.AssistantConfig.villageGrowthCap();
        if (Villages.headcount(village.id()) >= cap) return null;

        VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(server);
        if (folk == null) return null;
        Vec3 spot = safeSpot(server, at);
        folk.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
        folk.rename(Names.freeFor(village.id()));
        VillageSpawner.starterKit(folk);
        folk.joinVillage(village.id(), village.centre());
        server.addFreshEntity(folk);
        Villages.recordBirth(village.id());
        if (founding) {
            // The founding stores, and the ground kept awake — the same start
            // a village the world grew gets.
            VillageSpawner.supplyChest(server, at.above());
        }
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(server, village.id(), village.centre(),
            VillageSpawner.loadedRadiusFor(Villages.headcount(village.id())), true);
        return folk;
    }

    /** Somewhere beside the block a two-block-tall settler can actually stand
     *  without being embedded in a wall. */
    private static Vec3 safeSpot(ServerLevel level, BlockPos pos) {
        double[][] offsets = { {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {0, 0} };
        for (double[] off : offsets) {
            double x = pos.getX() + 0.5 + off[0];
            double z = pos.getZ() + 0.5 + off[1];
            AABB box = McAssistantMod.VILLAGE_FOLK.get().getDimensions()
                .makeBoundingBox(x, pos.getY(), z);
            if (level.noCollision(box)) return new Vec3(x, pos.getY(), z);
        }
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
    }
}
