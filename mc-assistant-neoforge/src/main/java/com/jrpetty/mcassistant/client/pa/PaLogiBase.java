package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * [pa36] What the logistics screens share: the assistant panel's look (its teal header, light body), click zones laid
 * down while drawing, a scrolled list, small buttons with tooltips, item icons by id. Fits from a 427x240 screen up.
 */
abstract class PaLogiBase extends Screen {

    protected int left, top, w, h, scroll, contentH;
    protected String tipNow = "";
    private record Zone(int x, int y, int w, int h, Runnable run, String tip) {}
    private final List<Zone> zones = new ArrayList<>();

    protected PaLogiBase(String title) {
        super(Component.literal(title));
    }

    protected void size(int maxW, int maxH) {
        w = Math.min(maxW, width - 16);
        h = Math.min(maxH, height - 16);
        left = (width - w) / 2;
        top = (height - h) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The screen's title, in its header. */
    protected abstract String title();

    /** The screen's own drawing (under the widgets, which Screen.render draws after). */
    protected abstract void body(GuiGraphics g, int mx, int my);

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x70101014);
        frame(g, title(), mx, my);
        body(g, mx, my);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);                                   // the background (frame and body), then the widgets
        tips(g, mx, my);
    }

    /** The frame: shadow, body, header band with the title and a close cross. */
    protected void frame(GuiGraphics g, String title, int mx, int my) {
        zones.clear();
        tipNow = "";
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        g.fill(left, top, left + w, top + 22, PaScreen.HEADER);
        g.drawString(font, UiStyle.clip(font, title, w - 40), left + 8, top + 7, 0xFFFFFFFF, false);
        button(g, "x", left + w - 18, top + 4, 14, 14, mx, my, true, false, "Close", this::onClose);
    }

    protected void zone(int x, int y, int zw, int zh, Runnable run, String tip) {
        zones.add(new Zone(x, y, zw, zh, run, tip));
    }

    protected static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    protected void button(GuiGraphics g, String label, int x, int y, int bw, int bh, int mx, int my, boolean enabled, boolean primary, String tip, Runnable run) {
        boolean over = enabled && in(mx, my, x, y, bw, bh);
        int fill = !enabled ? 0xFFBDBDBD : primary ? (over ? PaScreen.BRAND_LIGHT : PaScreen.BRAND) : (over ? 0xFFF0F0F0 : 0xFFDCDCDC);
        g.fill(x, y, x + bw, y + bh, fill);
        g.fill(x, y + bh - 1, x + bw, y + bh, primary && enabled ? 0xFF135A50 : 0xFF8E8E8E);
        int ink = !enabled ? UiStyle.TEXT_DISABLED : primary ? 0xFFFFFFFF : UiStyle.TEXT;
        String l = UiStyle.clip(font, label, bw - 4);
        g.drawString(font, l, x + (bw - font.width(l)) / 2, y + (bh - 8) / 2, ink, false);
        if (enabled) zone(x, y, bw, bh, run, tip);
        if (over && !tip.isEmpty()) tipNow = tip;
    }

    /** A width that fits the label (and a little air). */
    protected int bw(String label) {
        return font.width(label) + 10;
    }

    protected void tips(GuiGraphics g, int mx, int my) {
        if (!tipNow.isEmpty()) UiStyle.tooltip(g, font, tipNow, mx, my, 200);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone z = zones.get(i);
            if (in((int) mx, (int) my, z.x, z.y, z.w, z.h)) { z.run.run(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll = Math.max(0, Math.min(Math.max(0, contentH), scroll - (int) (dy * 18)));
        return true;
    }

    static Item item(String id) {
        try {
            return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        } catch (RuntimeException e) {
            return Items.BARRIER;
        }
    }

    static String nameOf(String id) {
        Item it = item(id);
        return new ItemStack(it).getHoverName().getString();
    }

    protected void icon(GuiGraphics g, String id, int x, int y, int count) {
        ItemStack s = new ItemStack(item(id), Math.max(1, Math.min(99, count)));
        g.renderItem(s, x, y);
        if (count > 1) g.renderItemDecorations(font, s, x, y, count > 999 ? (count / 1000) + "k" : String.valueOf(count));
    }

    protected int muted() {
        return UiStyle.TEXT_MUTED;
    }
}
