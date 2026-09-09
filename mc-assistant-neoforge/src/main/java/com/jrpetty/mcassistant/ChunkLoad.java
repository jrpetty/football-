package com.jrpetty.mcassistant;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import java.util.UUID;

/**
 * Station chunk loading. A stationed assistant keeps a small square of chunks
 * around its post force-loaded and fully ticking, so crops grow, furnaces
 * burn, saplings regrow, and the specialist keeps working while the player is
 * hundreds of blocks away. Tickets are owned by the assistant's UUID, persist
 * with the world save (the station resumes ticking after a reload before the
 * player ever comes back), and are released when the bot stands down or dies.
 */
public final class ChunkLoad {

    public static final TicketController CONTROLLER = new TicketController(
        ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "stations"), null);

    private ChunkLoad() {}

    public static void onRegisterControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /** Force (on=true) or release (on=false) a (2r+1)x(2r+1) square of fully
     *  ticking chunks around {@code center}, owned by {@code owner}. */
    public static void setLoaded(ServerLevel level, UUID owner, BlockPos center, int radius, boolean on) {
        // Releasing always goes through, even with the feature switched off —
        // otherwise flipping the config mid-world would strand live tickets.
        if (on && !AssistantConfig.chunkLoading()) return;
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                CONTROLLER.forceChunk(level, owner, cx + dx, cz + dz, on, true);
            }
        }
    }
}
