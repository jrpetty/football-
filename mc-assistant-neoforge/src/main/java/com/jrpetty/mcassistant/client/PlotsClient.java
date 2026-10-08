package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.PlotListPayload;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * The client's end of the plots menu: the server sends the plot book, this
 * puts it on screen. Kept apart from the network class so the common code
 * never touches a client-only type.
 */
public final class PlotsClient {

    private PlotsClient() {}

    /** The last book the server sent — the map reads this to draw plots that
     *  have no workers yet, which otherwise exist nowhere a player can see. */
    public static List<PlotListPayload.Entry> book = List.of();
    private static boolean openRequested;

    /** The B key: ask for the book and open the screen when it arrives. */
    public static void requestOpen() {
        openRequested = true;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.jrpetty.mcassistant.net.PlotOrderPayload(0, -1, ""));
    }

    /** A silent refresh — updates the cache (and any open book) only. */
    public static void refresh() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.jrpetty.mcassistant.net.PlotOrderPayload(0, -1, ""));
    }

    public static void showPlots(List<PlotListPayload.Entry> plots) {
        book = plots;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PlotsScreen open) {
            open.setPlots(plots);        // a click's answer: refresh in place
        } else if (openRequested) {
            mc.setScreen(new PlotsScreen(plots));
        }
        openRequested = false;
    }
}
