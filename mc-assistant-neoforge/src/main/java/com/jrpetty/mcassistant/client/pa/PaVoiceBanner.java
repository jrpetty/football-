package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * [pa35] The natural voice coming down, as a small card at the top right: what it is, a progress bar, and a one-click
 * "Don't download" (clickable whenever the mouse is free, on any screen, the assistant's panel included; Settings >
 * Voice does the same). It stays a few seconds after it finishes or stops, then goes. Client-side only.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PaVoiceBanner {

    private PaVoiceBanner() {}

    private static int bx, by, bw, bh;
    private static boolean button;

    /** Is the card up just now? */
    static boolean showing() {
        return PaVoice.installingVoices || System.currentTimeMillis() - PaVoice.packEndedAt < 6000L && PaVoice.packEndedAt > 0;
    }

    /** Draw the card (mx, my: the mouse in a screen, or -1 on the HUD where it can't be clicked). */
    static void draw(GuiGraphics g, Minecraft mc, int mx, int my) {
        button = false;
        if (!showing() || mc.options.hideGui || mx < 0 && mc.screen != null) return;   // over a screen, the screen's own pass draws it (clickable)
        Font font = mc.font;
        int w = g.guiWidth();
        bw = Math.min(220, w - 16);
        bh = 36;
        bx = w - bw - 6;
        by = 6;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        UiStyle.shadow(g, bx, by, bw, bh);
        g.fill(bx, by, bx + bw, by + bh, 0xF0173B40);
        g.fill(bx, by, bx + 2, by + bh, PaScreen.BRAND_LIGHT);
        String title = PaVoice.installingVoices ? "Your assistant's natural voice" : PaVoice.packFailed ? "Natural voice: not this time" : "Natural voice";
        g.drawString(font, UiStyle.clip(font, title, bw - 12), bx + 7, by + 4, 0xFFFFFFFF, false);
        String line = PaVoice.packLine == null ? "" : PaVoice.packLine;
        g.drawString(font, UiStyle.clip(font, line, bw - 12), bx + 7, by + 14, 0xFFBFE3DC, false);
        int barW = bw - 14 - (PaVoice.installingVoices ? 78 : 0);
        g.fill(bx + 7, by + 26, bx + 7 + barW, by + 31, 0xFF0E2A2E);
        g.fill(bx + 7, by + 26, bx + 7 + (int) Math.round(barW * Math.max(0, Math.min(1, PaVoice.packFraction))), by + 31, PaVoice.packFailed ? 0xFFB5543F : PaScreen.BRAND_LIGHT);
        if (PaVoice.installingVoices) {
            String pct = Math.round(PaVoice.packFraction * 100) + "%";
            int kx = bx + bw - 72, ky = by + 23, kw = 66, kh = 11;
            boolean over = mx >= kx && mx < kx + kw && my >= ky && my < ky + kh;
            button = mx >= 0;
            g.fill(kx, ky, kx + kw, ky + kh, over ? 0xFFF0F0F0 : 0xFFDCDCDC);
            String label = mx >= 0 ? "Don't download" : pct;
            g.drawString(font, UiStyle.clip(font, label, kw - 4), kx + (kw - Math.min(kw - 4, font.width(label))) / 2, ky + 2, UiStyle.TEXT, false);
        }
        g.pose().popPose();
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Render.Post e) {
        draw(e.getGuiGraphics(), Minecraft.getInstance(), e.getMouseX(), e.getMouseY());
    }

    @SubscribeEvent
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre e) {
        if (!button || !PaVoice.installingVoices) return;
        int kx = bx + bw - 72, ky = by + 23;
        if (e.getMouseX() >= kx && e.getMouseX() < kx + 66 && e.getMouseY() >= ky && e.getMouseY() < ky + 11) {
            UiStyle.click();
            PaVoice.declineVoices();
            e.setCanceled(true);
        }
    }
}
