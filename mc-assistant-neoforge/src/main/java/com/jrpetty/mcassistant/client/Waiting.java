package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * [ui29] A screen that opens on the server's answer (the town's books, your life, the journal, the plots, your finances, a
 * folk's profile) showed nothing at all until the answer came, which on a busy server is seconds of a key that seems to
 * do nothing. Now the key's press puts a small "opening..." line over the game at once; it goes the moment any screen
 * opens, or after a few seconds if none does (the server said why in the chat).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class Waiting {

    private Waiting() {}

    private static final int SHOW_MS = 6000;
    private static String label = "";
    private static long since;

    /** A request has gone to the server and its screen will open on the answer: say so until it does. */
    public static void start(String what) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;                                     // a screen is up: it stays until the new one replaces it
        label = what;
        since = Util.getMillis();
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        label = "";
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (label.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        long age = Util.getMillis() - since;
        if (age > SHOW_MS || mc.screen != null || mc.options.hideGui) {
            if (age > SHOW_MS) label = "";
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int dots = (int) (age / 300 % 4);
        String text = label + ".".repeat(dots);
        int w = font.width(label + "...") + 16, h = 18;
        int x = (g.guiWidth() - w) / 2, y = g.guiHeight() / 3;
        UiStyle.panel(g, x, y, w, h);
        g.drawString(font, text, x + 8, y + 5, UiStyle.TEXT_MUTED, false);
    }
}
