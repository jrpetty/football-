package com.jrpetty.mazerunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jrpetty.mazerunner.config.MazeConfigs;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Maze Runner — an authored adventure world: a 96×96-chunk unbreakable maze
 * around a central 16×16-chunk Glade. Seven fixed, pre-validated layouts
 * rotate on a per-world shuffled 7-day schedule; the walls physically shift
 * each night, the Glade doors open at dawn and seal at dusk, and one exit
 * portal is live each day. Create a world with the "Maze Runner" world type.
 */
@Mod(MazeRunnerMod.MODID)
public class MazeRunnerMod {

    public static final String MODID = "mazerunner";
    public static final Logger LOGGER = LoggerFactory.getLogger("mazerunner");

    public MazeRunnerMod(IEventBus modEventBus) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModWorldgen.CHUNK_GENERATORS.register(modEventBus);

        modEventBus.addListener(ModEntities::onAttributeCreation);

        NeoForge.EVENT_BUS.register(MazeRuntime.class);
        NeoForge.EVENT_BUS.register(MazeCommands.class);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // client-only classes are never loaded on a dedicated server
            NeoForge.EVENT_BUS.register(com.jrpetty.mazerunner.client.MazeClientEvents.class);
            modEventBus.register(com.jrpetty.mazerunner.client.MazeClientColors.class);
            modEventBus.register(com.jrpetty.mazerunner.client.MazeClientRenderers.class);
        }

        var cfg = MazeConfigs.get(); // fail fast if the bundled maze data is broken
        LOGGER.info("Maze Runner loaded — {}×{} cells, {} toggle points, {} layouts, {} exits.",
            cfg.gridCells, cfg.gridCells, cfg.togglePoints.size(), cfg.layouts.size(), cfg.exits.size());
    }
}
