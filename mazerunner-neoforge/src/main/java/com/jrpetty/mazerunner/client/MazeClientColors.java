package com.jrpetty.mazerunner.client;

import com.jrpetty.mazerunner.ModBlocks;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.FoliageColor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** Tints the maze ivy with biome foliage colour, exactly like vanilla vines. */
public final class MazeClientColors {

    private MazeClientColors() {}

    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register(
            (state, getter, pos, tintIndex) -> getter != null && pos != null
                ? BiomeColors.getAverageFoliageColor(getter, pos)
                : FoliageColor.getDefaultColor(),
            ModBlocks.MAZE_VINE.get());
    }
}
