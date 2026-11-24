package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * [pa36] The Craft view: a search over everything the server can craft (vanilla and modded). With nothing typed it
 * lists what its assistant can make right now. Each row says "can make now (up to N)" or what's missing; Craft x1, x8
 * or all of them; a click shows the recipe with what it has ticked. It crafts at a table at a player's hand pace and
 * hands the result to you.
 */
public final class PaCraftScreen extends PaLogiBase {

    private final String first;
    private EditBox search;
    private String asked = null;
    private long typedAt;

    PaCraftScreen(String q) {
        super("Craft");
        this.first = q == null ? "" : q;
    }

    @Override
    protected void init() {
        size(400, 250);
        search = new EditBox(font, left + 10, top + 28, 180, 14, Component.literal("Search"));
        search.setMaxLength(32);
        search.setHint(Component.literal("Search: piston, iron, bed..."));
        search.setValue(search.getValue().isEmpty() ? first : search.getValue());
        search.setResponder(t -> typedAt = System.currentTimeMillis());
        addRenderableWidget(search);
        setInitialFocus(search);
        if (asked == null) asked = first;
    }

    @Override
    public void tick() {
        String q = search.getValue().trim();
        if (!q.equals(asked) && System.currentTimeMillis() - typedAt > 300) {
            asked = q;
            CompoundTag t = new CompoundTag();
            t.putString("q", q);
            PaLogiClient.send("craftq", t);
        }
    }

    @Override
    protected String title() {
        return "Craft: anything it has the makings of";
    }

    @Override
    protected void body(GuiGraphics g, int mx, int my) {
        CompoundTag d = PaLogiClient.craft;
        int listW = w - 160;
        g.drawString(font, UiStyle.clip(font, search.getValue().isBlank() ? "What it can make now:" : "Matching:", 160), left + 198, top + 31, muted(), false);
        int listTop = top + 48, listBottom = top + h - 8, rowH = 20;
        if (d == null) { g.drawString(font, "Looking...", left + 10, listTop + 4, UiStyle.TEXT_FAINT, false); recipe(g, mx, my, left + listW + 6, listTop); return; }
        ListTag l = d.getList("list", Tag.TAG_COMPOUND);
        contentH = Math.max(0, l.size() * rowH - (listBottom - listTop));
        scroll = Math.min(scroll, contentH);
        g.enableScissor(left + 4, listTop, left + listW, listBottom);
        int y = listTop - scroll;
        if (l.isEmpty()) g.drawString(font, UiStyle.clip(font, search.getValue().isBlank() ? "Nothing yet: give it some materials." : "Nothing by that name.", listW - 10), left + 10, y + 4, UiStyle.TEXT_FAINT, false);
        for (int i = 0; i < l.size(); i++, y += rowH) {
            if (y + rowH < listTop || y > listBottom) continue;
            CompoundTag c = l.getCompound(i);
            String id = c.getString("i");
            boolean now = c.getBoolean("now");
            boolean over = in(mx, my, left + 6, y, listW - 10, rowH - 2);
            g.fill(left + 6, y, left + listW - 4, y + rowH - 2, over ? 0xFFE9F3F1 : 0xFFDDE3E2);
            icon(g, id, left + 8, y + 1, 1);
            g.drawString(font, UiStyle.clip(font, nameOf(id), listW - 150), left + 27, y + 2, UiStyle.TEXT, false);
            String st = now ? "can make now" + (c.getInt("max") > 1 ? " (up to " + c.getInt("max") + ")" : "") : "missing: " + c.getString("miss");
            g.drawString(font, UiStyle.clip(font, st, listW - 150), left + 27, y + 10, now ? UiStyle.SUCCESS : UiStyle.DANGER, false);
            zone(left + 6, y, listW - 120, rowH - 2, () -> {
                CompoundTag t = new CompoundTag();
                t.putString("item", id);
                PaLogiClient.send("recipe", t);
            }, "Show the recipe");
            int bx = left + listW - 112;
            button(g, "x1", bx, y + 2, 22, 14, mx, my, true, now, "Craft one", () -> craft(id, 1, false));
            button(g, "x8", bx + 24, y + 2, 22, 14, mx, my, true, false, "Craft eight", () -> craft(id, 8, false));
            button(g, "All", bx + 48, y + 2, 26, 14, mx, my, now, now, "As many as it can from what it has", () -> craft(id, 1, true));
        }
        g.disableScissor();
        if (contentH > 0) UiStyle.scrollBar(g, left + listW - 4, listTop, listBottom - listTop, scroll, contentH, listBottom - listTop);
        recipe(g, mx, my, left + listW + 6, listTop);
    }

    private void recipe(GuiGraphics g, int mx, int my, int x, int y) {
        CompoundTag r = PaLogiClient.recipe;
        int rw = left + w - 8 - x;
        g.fill(x, y, x + rw, top + h - 8, 0xFFE2E2E2);
        if (r == null) { UiStyle.text(g, font, "Click a thing to see its recipe.", x + 6, y + 6, rw - 12, UiStyle.TEXT_FAINT); return; }
        String id = r.getString("i");
        icon(g, id, x + 6, y + 6, Math.max(1, r.getInt("yield")));
        g.drawString(font, UiStyle.clip(font, nameOf(id), rw - 30), x + 26, y + 10, UiStyle.TEXT, false);
        int py = y + 28;
        for (Tag t : r.getList("parts", Tag.TAG_COMPOUND)) {
            CompoundTag p = (CompoundTag) t;
            int need = p.getInt("need"), have = p.getInt("have");
            icon(g, p.getString("i"), x + 6, py, need);
            boolean ok = have >= need;
            g.drawString(font, UiStyle.clip(font, (ok ? "got " : "have " + have + " of ") + need, rw - 32), x + 26, py + 4, ok ? UiStyle.SUCCESS : UiStyle.DANGER, false);
            if (in(mx, my, x + 6, py, 16, 16)) tipNow = p.getString("l");
            py += 18;
            if (py > top + h - 30) break;
        }
        g.drawString(font, UiStyle.clip(font, r.getBoolean("table") ? "At a crafting table." : "In the hand.", rw - 12), x + 6, top + h - 20, muted(), false);
    }

    private void craft(String id, int n, boolean all) {
        CompoundTag t = new CompoundTag();
        t.putString("item", id);
        t.putInt("n", n);
        t.putBoolean("all", all);
        PaLogiClient.send("craft", t);
        onClose();
    }
}
