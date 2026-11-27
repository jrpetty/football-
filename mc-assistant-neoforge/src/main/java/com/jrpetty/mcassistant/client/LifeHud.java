package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Calendar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * [featlife] The calendar on the screen: a small chip at the top centre with the day of the week, what the day is (market
 * day, the fair, the day of rest) and the week of the month: "Saturday · Market day · Week 2". Read off the world's own
 * day (Calendar is the same in every town), so nothing is sent for it. Hidden with the rest of the HUD (F1) and while a
 * screen is open.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class LifeHud {

    private LifeHud() {}

    /** The chip's bed (dark, see-through), its spine by the kind of day, and its ink. */
    static final int BED = 0xB0101418, INK = 0xFFF0F0F0, WORK = 0xFF8B97A6, MARKET = 0xFFD39B2A, REST = 0xFF4E9A3A, FAIR = 0xFFC2452F;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null || mc.screen != null) return;
        if (mc.getDebugOverlay().showDebugScreen()) return;
        long day = mc.level.getDayTime() / 24000L;
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        String text = Calendar.hudLine(day);
        int tw = font.width(text), w = tw + UiStyle.S2 + 6, h = 13;
        int x = (g.guiWidth() - w) / 2, y = 2;
        int spine = Calendar.isFairDay(day) ? FAIR : Calendar.isMarketDay(day) ? MARKET : Calendar.isRestDay(day) ? REST : WORK;
        g.fill(x, y, x + w, y + h, BED);
        g.fill(x, y, x + 2, y + h, spine);
        g.drawString(font, text, x + 2 + UiStyle.S1, y + 3, INK, false);
    }
}
