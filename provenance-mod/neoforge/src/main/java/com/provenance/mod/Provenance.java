package com.provenance.mod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point.
 *
 * <p>All provenance rules live in the {@code core} module, which has no
 * Minecraft on its classpath and is unit tested independently. This module is
 * the adapter: it translates game events into calls on
 * {@link com.provenance.core.RecordRegistry} and renders the results.
 */
@Mod(Provenance.MOD_ID)
public final class Provenance {

    public static final String MOD_ID = "provenance";
    public static final Logger LOGGER = LoggerFactory.getLogger("Provenance");

    public Provenance(IEventBus modEventBus) {
        ProvenanceComponents.register(modEventBus);
        modEventBus.addListener(Network::register);

        NeoForge.EVENT_BUS.register(ServerLifecycle.class);
        NeoForge.EVENT_BUS.register(GameplayEvents.class);
        NeoForge.EVENT_BUS.register(ItemLifecycleEvents.class);
        NeoForge.EVENT_BUS.register(TickEvents.class);
        NeoForge.EVENT_BUS.register(ProvenanceCommands.class);

        // Optional: only does anything if a supported gun mod is installed.
        TaczIntegration.install(NeoForge.EVENT_BUS);
    }
}
