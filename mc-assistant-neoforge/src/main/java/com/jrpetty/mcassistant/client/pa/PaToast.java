package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * [pa33] The first thing a new player hears of their assistant: a notice, once a session, while they have none
 * ("Your own assistant: press Y and click Get my assistant"). The key shown is the one bound, whatever it is.
 */
final class PaToast implements Toast {

    private static boolean shown;
    private final String title, text;

    private PaToast(String title, String text) {
        this.title = title;
        this.text = text;
    }

    /** Once a session, for a player who has no assistant yet. */
    static void maybeShow(boolean has) {
        if (has || shown) return;
        shown = true;
        Minecraft mc = Minecraft.getInstance();
        String key = PaClient.PANEL.getTranslatedKeyMessage().getString();
        mc.getToasts().addToast(new PaToast("Your own assistant",
            "Press " + key + " and click Get my assistant. It talks, follows and works for you."));
    }

    @Override
    public int width() {
        return 220;
    }

    @Override
    public int height() {
        return 40;
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long since) {
        Font font = toasts.getMinecraft().font;
        int w = width(), h = height();
        g.fill(0, 0, w, h, 0xFF000000);
        g.fill(1, 1, w - 1, h - 1, UiStyle.PANEL);
        g.fill(1, 1, 3, h - 1, 0xFF2BB5A0);
        g.renderItem(new ItemStack(McAssistantMod.ASSISTANT_WHISTLE.get()), 7, 12);
        g.drawString(font, UiStyle.clip(font, title, w - 34), 28, 5, UiStyle.TEXT, false);
        List<FormattedCharSequence> lines = font.split(Component.literal(text), w - 34);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), 28, 16 + i * 10, UiStyle.TEXT_MUTED, false);
        return since >= 10000L * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
