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

    public static void showPlots(List<PlotListPayload.Entry> plots) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PlotsScreen open) {
            open.setPlots(plots);        // a click's answer: refresh in place
        } else {
            mc.setScreen(new PlotsScreen(plots));
        }
    }
}
