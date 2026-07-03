package com.jrpetty.mazerunner.client;

import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.MazeConfigs;
import com.jrpetty.mazerunner.gen.MazeChunkGenerator;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client-side atmosphere: maze fog. A noticeable (not blinding) haze rolls
 * through the corridors for the first ten real minutes of each morning, and
 * again for ten minutes in the dead of night — right while the walls shift.
 * The Glade stays clear.
 */
public final class MazeClientEvents {

    // Day-time tick windows. Day runs at 1/6 tick per real tick, night at 1/3:
    // 2000 day-ticks = 10 real minutes; 4000 night-ticks = 10 real minutes.
    private static final int MORNING_END = 2000;
    private static final int NIGHT_FOG_START = 16000; // 10 real min after dusk (12000)
    private static final int NIGHT_FOG_END = 20000;

    private MazeClientEvents() {}

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.level.dimension() != Level.OVERWORLD) return;
        if (!isMazeWorld(mc)) return;

        MazeConfigData cfg = MazeConfigs.get();
        int cellX = Math.floorDiv(mc.player.getBlockX(), cfg.cellSize);
        int cellZ = Math.floorDiv(mc.player.getBlockZ(), cfg.cellSize);
        if (!cfg.inGrid(cellX, cellZ) || cfg.inGlade(cellX, cellZ)) return;

        float strength = fogStrength(mc.level.getDayTime() % 24000);
        if (strength <= 0.0F) return;

        float far = 170.0F - 130.0F * strength; // 40 at full strength
        float near = 24.0F - 16.0F * strength;  // 8 at full strength
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), near));
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), far));
        event.setCanceled(true);
    }

    /** 0..1 with smooth ramps at the window edges. */
    private static float fogStrength(long t) {
        if (t < MORNING_END) {
            return ramp(t, 0, MORNING_END, 300);
        }
        if (t >= NIGHT_FOG_START && t < NIGHT_FOG_END) {
            return ramp(t, NIGHT_FOG_START, NIGHT_FOG_END, 500);
        }
        return 0.0F;
    }

    private static float ramp(long t, int start, int end, int edge) {
        float in = Math.min(1.0F, (t - start) / (float) edge);
        float out = Math.min(1.0F, (end - t) / (float) edge);
        return Math.max(0.0F, Math.min(in, out));
    }

    /**
     * In singleplayer, check the integrated server's generator; on a remote
     * server we can't see the generator, so having the mod installed while on
     * an overworld is treated as a maze world.
     */
    private static boolean isMazeWorld(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return true;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld != null
            && overworld.getChunkSource().getGenerator() instanceof MazeChunkGenerator;
    }
}
