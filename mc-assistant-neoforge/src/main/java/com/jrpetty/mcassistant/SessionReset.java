package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Requests;
import com.jrpetty.mcassistant.entity.Town;
import com.jrpetty.mcassistant.entity.Villages;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Nothing about a village, a crew or a claim is saved in one place — every
 * settler carries its own share and puts the register back when it loads. Which
 * means the register itself is only memory, and memory outlives a world: quit to
 * the title screen, open another world, and the new one inherited the last one's
 * villages, their age and buildings, their stores and their claim book. A
 * spawner placed near the same coordinates joined the OLD village, never made a
 * founding chest, and waited on a clock that belonged to a different world.
 *
 * <p>So the register is wiped when a world closes and again when one starts.
 */
public final class SessionReset {

    private SessionReset() {}

    /**
     * True while a world opens or closes. The resets below are the tests' resets too, and some of them clear what is
     * kept with the world (the town affairs' letters, petitions and fund; the league table). Between tests that is a
     * clean slate; as a world opens it is the town's history lost at every restart. Those check this and forget only
     * what is in memory.
     */
    private static boolean opening;

    /** Is a world opening or closing (so what is saved with it must be left alone)? */
    public static boolean opening() {
        return opening;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        session();
        com.jrpetty.mcassistant.entity.Prices.reset();      // this world's recipes, priced afresh
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        session();
        com.jrpetty.mcassistant.entity.Prices.reset();
    }

    private static void session() {
        opening = true;
        try {
            resetAll();
        } finally {
            opening = false;
        }
    }

    /** Wipe every piece of in-memory village and crew state. */
    public static void resetAll() {
        Villages.resetForTests();
        VillagerTakeover.resetForTests();
        VillageSpawner.resetForTests();
        Colonies.reset();
        AssistantEntity.resetRegistryForTests();
        Town.resetAll();
        Requests.resetAll();
        ChunkLoad.reset();
        com.jrpetty.mcassistant.entity.Reach.reset();
    }
}
