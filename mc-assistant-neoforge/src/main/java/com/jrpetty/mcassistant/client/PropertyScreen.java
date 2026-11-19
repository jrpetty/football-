package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.client.UiStyle.State;
import com.jrpetty.mcassistant.net.PropertyAskPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * [orders17] The property office (entity/PropertyOffice): four tabs (Orders, My property, Market, Commission), each a list
 * of rows, a row its lines (and a progress bar for an order) and its buttons. Everything on it is written by the server;
 * a button sends its op and argument back (PropertyAskPayload) and the server answers with the page again. No commands.
 */
public class PropertyScreen extends Screen {

    private static final String[][] TABS = { { "orders", "Orders" }, { "property", "My property" }, { "market", "Market" }, { "commission", "Commission" } };
    private static final int MAX_W = 470, MAX_H = 290;

    private CompoundTag data;
    private int px, py, pw, ph, scroll, contentH;
    private final List<Hit> hits = new ArrayList<>();
    @Nullable private String hover;

    private record Hit(int x, int y, int w, int h, String op, String arg) {
        boolean in(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    public PropertyScreen(CompoundTag data) {
        super(Component.literal("Property office"));
        this.data = data;
    }

    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof PropertyScreen open) {
            if (!open.data.getString("tab").equals(data.getString("tab"))) open.scroll = 0;
            open.data = data;
            return;
        }
        mc.setScreen(new PropertyScreen(data));
    }

    static void ask(String op, String arg) {
        PacketDistributor.sendToServer(new PropertyAskPayload(op, arg));
    }

    @Override
    protected void init() {
        pw = Math.min(MAX_W, width - S4);
        ph = Math.min(MAX_H, height - S4);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        hits.clear();
        hover = null;
        shadow(g, px, py, pw, ph);
        panel(g, px, py, pw, ph);
        title(g, font, "Property office - " + data.getString("town"), px + S2, py + S2);
        String coins = data.getInt("coins") + " coins on you";
        g.drawString(font, coins, px + pw - S2 - font.width(coins), py + S2, TEXT_MUTED, false);
        // The tabs.
        int tx = px + S2, ty = py + 22;
        String tab = data.getString("tab");
        for (String[] t : TABS) {
            int w = font.width(t[1]) + S3;
            boolean sel = t[0].equals(tab) || tab.isEmpty() && t[0].equals("orders");
            boolean over = mouseX >= tx && mouseX < tx + w && mouseY >= ty && mouseY < ty + TAB;
            topTab(g, tx, ty, w, TAB, State.of(true, sel, over));
            g.drawString(font, t[1], tx + (w - font.width(t[1])) / 2, ty + 7, sel ? TEXT : TEXT_MUTED, false);
            hits.add(new Hit(tx, ty, w, TAB, "tab", t[0]));
            tx += w + 2;
        }
        // The last word from the clerk.
        int my = ty + TAB + S1;
        String msg = data.getString("msg");
        if (!msg.isEmpty()) {
            List<String> wrapped = wrap(msg, pw - 2 * S2);
            for (String s : wrapped) { g.drawString(font, s, px + S2, my, INFO, false); my += LINE; }
            my += 2;
        }
        // The rows.
        int top = my, bottom = py + ph - S2;
        well(g, px + S2 - 2, top - 2, pw - 2 * S2 + 4, bottom - top + 4);
        g.enableScissor(px + S2, top, px + pw - S2, bottom);
        int y = top - scroll;
        int w = pw - 2 * S2 - 6;
        ListTag rows = data.getList("rows", Tag.TAG_COMPOUND);
        for (Tag rt : rows) {
            CompoundTag r = (CompoundTag) rt;
            int start = y;
            for (Tag lt : r.getList("lines", Tag.TAG_STRING)) {
                for (String s : wrap(lt.getAsString(), w - S1)) {
                    if (y + LINE > top - LINE && y < bottom) g.drawString(font, s, px + S2 + 2, y + 1, s.startsWith("  ") ? TEXT_MUTED : TEXT, false);
                    y += LINE;
                }
            }
            if (r.contains("bar")) {
                if (y < bottom && y > top - 8) bar(g, px + S2 + 2, y + 1, Math.min(200, w - 4), 5, r.getInt("bar") / 100f, ACCENT_FILL);
                y += 8;
            }
            int bx = px + S2 + 2;
            for (Tag bt : r.getList("buttons", Tag.TAG_COMPOUND)) {
                CompoundTag b = (CompoundTag) bt;
                String label = b.getString("label");
                int bw = font.width(label) + S2;
                if (bx + bw > px + S2 + w) { bx = px + S2 + 2; y += 16; }
                boolean over = mouseX >= bx && mouseX < bx + bw && mouseY >= y && mouseY < y + 14 && mouseY >= top && mouseY < bottom;
                if (y + 14 > top && y < bottom) {
                    button(g, font, label, bx, y, bw, 14, State.of(true, false, over));
                    hits.add(new Hit(bx, Math.max(y, top), bw, Math.min(y + 14, bottom) - Math.max(y, top), b.getString("op"), b.getString("arg")));
                }
                bx += bw + 3;
            }
            if (!r.getList("buttons", Tag.TAG_COMPOUND).isEmpty()) y += 16;
            if (y > start) {
                g.fill(px + S2, y + 1, px + pw - S2, y + 2, EDGE_SOFT);
                y += 4;
            }
        }
        g.disableScissor();
        contentH = y + scroll - top;
        scrollBar(g, px + pw - S2 - 4, top, bottom - top, scroll, Math.max(0, contentH - (bottom - top)), bottom - top);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private List<String> wrap(String s, int w) {
        List<String> out = new ArrayList<>();
        for (net.minecraft.util.FormattedCharSequence f : font.split(Component.literal(s), Math.max(40, w))) {
            StringBuilder sb = new StringBuilder();
            f.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
            out.add(sb.toString());
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (Hit h : hits) {
            if (h.in(mx, my)) {
                click();
                ask(h.op(), h.arg());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll = Math.max(0, Math.min(Math.max(0, contentH - (ph - 60)), scroll - (int) (dy * 20)));
        return true;
    }
}
