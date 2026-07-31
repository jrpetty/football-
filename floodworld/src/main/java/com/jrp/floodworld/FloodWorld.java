package com.jrp.floodworld;

import com.jrp.floodworld.command.FloodWorldCommand;
import com.jrp.floodworld.flow.FloodWorldFluids;
import com.jrp.floodworld.sim.FloodEngine;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;

@Mod(FloodWorld.MODID)
public final class FloodWorld {

    public static final String MODID = "floodworld";
    public static final String FLOWING_FLUIDS_MODID = "flowing_fluids";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean flowingFluidsLoaded = false;

    public FloodWorld(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::commonSetup);
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        NeoForge.EVENT_BUS.register(FloodEngine.class);
        NeoForge.EVENT_BUS.register(FloodWorldCommand.class);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        if (!ModList.get().isLoaded(FLOWING_FLUIDS_MODID)) {
            LOGGER.warn("FloodWorld: Flowing Fluids ('{}') is not installed — FloodWorld will stay dormant.",
                    FLOWING_FLUIDS_MODID);
            return;
        }
        FloodWorldFluids.init(MODID);
        if (FloodWorldFluids.isAvailable()) {
            flowingFluidsLoaded = true;
            LOGGER.info("FloodWorld: Flowing Fluids detected — flood simulation enabled.");
        } else {
            LOGGER.warn("FloodWorld: Flowing Fluids is present but its API could not be acquired — disabling.");
        }
    }

    public static boolean flowingFluidsLoaded() {
        return flowingFluidsLoaded;
    }
}
