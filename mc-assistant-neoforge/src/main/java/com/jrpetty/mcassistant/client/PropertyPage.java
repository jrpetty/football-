package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * [orders17] The Property page of the town's books (CityScreen; the server's PropertyOffice.report): the buildings going up
 * for players (each with its progress, its expected day and what it waits on), the builders' queue and pace, what is for
 * sale and to let, the last sales; and the button that opens the property office (PropertyScreen) to order, sell, let or
 * refit. Returns the button's box {x, y, w, h} for the screen's click zones.
 */
public final class PropertyPage {

    private PropertyPage() {}

    public static int[] draw(GuiGraphics g, Font font, CompoundTag t, int x, int y, int w, int h, int scroll, int mx, int my) {
        Ui.section(g, font, "Property: building orders, the register and the market", x, y, w);
        String label = "Open the property office";
        int bw = font.width(label) + 12, bx = x + 2, by = y + 12;
        boolean over = mx >= bx && mx < bx + bw && my >= by && my < by + 14;
        UiStyle.button(g, font, label, bx, by, bw, 14, UiStyle.State.of(true, false, over));
        int ly = by + 18 - scroll;
        for (Tag l : t.getList("lines", Tag.TAG_STRING)) {
            String s = l.getAsString();
            boolean head = s.startsWith("#");
            if (head) s = s.substring(1);
            if (ly >= by + 16 && ly < y + h - 8) {
                if (head) g.drawString(font, Ui.clip(font, s, w - 4), x + 2, ly, UiStyle.ACCENT, false);
                else g.drawString(font, Ui.clip(font, s, w - 10), x + 8, ly, UiStyle.TEXT, false);
            }
            ly += head ? 12 : 10;
        }
        return new int[]{ bx, by, bw, 14 };
    }
}
