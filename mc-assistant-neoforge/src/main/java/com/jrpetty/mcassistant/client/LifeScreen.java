package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.LifeNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [player15] The player's own pages (entity/PlayerLife writes them, entity/LifePage describes the parts): your life in
 * town, your shop, starting a business, your businesses, the jobs board, everything else you can do. One screen draws
 * them all in the house look: the title band, the tabs, then the page's blocks (headings, paragraphs, rows with an icon,
 * a figure and buttons, bars, choices stepped through with a click, a name box, a small map of places to pick), scrolled
 * with the wheel. Every button goes to the server with the page's choices, and the page comes back up to date; nothing
 * here is a command.
 */
public class LifeScreen extends Screen {

    private static final int PAD = 8, HEAD = 30, TAB_H = 16, FOOT = 22, ROW_H = 22, BTN_H = 16;

    private CompoundTag data;
    private int left, top, w, h, scroll, contentH;
    private final Map<String, String> form = new HashMap<>();
    private final Map<String, EditBox> boxes = new HashMap<>();
    private final List<int[]> zones = new ArrayList<>();
    private final List<Runnable> acts = new ArrayList<>();
    private final List<String> tips = new ArrayList<>();

    public LifeScreen(CompoundTag data) {
        super(Component.literal(data.getString("title")));
        this.data = data;
        readForm();
    }

    /** The server's page: opened, or the open one brought up to date (its scroll and what was typed kept). */
    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (data.contains("marker")) EarnClient.mark(data.getCompound("marker").getIntArray("at"), data.getCompound("marker").getString("l"));
        if (data.contains("ghost")) LifeGhost.set(data.getCompound("ghost"));
        if (data.getBoolean("close")) {
            if (mc.screen instanceof LifeScreen) mc.setScreen(null);
            return;
        }
        if (mc.screen instanceof LifeScreen open && open.data.getString("page").equals(data.getString("page"))
                && open.data.getString("key").equals(data.getString("key"))) {
            open.data = data;
            open.readForm();
            open.rebuildWidgets();
            return;
        }
        mc.setScreen(new LifeScreen(data));
    }

    private void readForm() {
        for (Tag e : data.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) e;
            String t = b.getString("t");
            if ((t.equals("choice") || t.equals("field")) && !form.containsKey(b.getString("id"))) form.put(b.getString("id"), b.getString("v"));
            if (t.equals("choice") && b.getBoolean("force")) form.put(b.getString("id"), b.getString("v"));
        }
    }

    @Override
    protected void init() {
        w = Math.min(460, width - 16);
        h = Math.min(330, height - 16);
        left = (width - w) / 2;
        top = (height - h) / 2;
        for (EditBox old : boxes.values()) form.put(keyOf(old), old.getValue());
        boxes.clear();
        for (Tag e : data.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) e;
            if (!b.getString("t").equals("field")) continue;
            String id = b.getString("id");
            EditBox box = new EditBox(font, 0, 0, 160, 14, Component.literal(b.getString("l")));
            box.setMaxLength(Math.max(4, b.getInt("max")));
            box.setValue(form.getOrDefault(id, b.getString("v")));
            box.setResponder(v -> form.put(id, v));
            box.setMessage(Component.literal(id));
            boxes.put(id, box);
            addRenderableWidget(box);
        }
    }

    private static String keyOf(EditBox b) {
        return b.getMessage().getString();
    }

    private int contentTop() {
        return top + HEAD + (data.getList("tabs", Tag.TAG_COMPOUND).isEmpty() ? 4 : TAB_H + 6);
    }

    private int contentBottom() {
        return top + h - FOOT;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, UiStyle.BACKDROP);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);                                       // the backdrop (the widgets again on top, below)
        zones.clear();
        acts.clear();
        tips.clear();
        picHover = "";
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        UiStyle.headerBand(g, left, top, w, HEAD);
        UiStyle.title(g, font, data.getString("title"), left + PAD, top + 5);
        UiStyle.text(g, font, data.getString("sub"), left + PAD, top + 18, w - PAD * 2 - 56, UiStyle.TEXT_MUTED);
        button(g, "Close", left + w - PAD - 44, top + 7, 44, mx, my, true, false, "", this::onClose);
        if (!data.getString("back").isEmpty()) {
            CompoundTag a = data.getCompound("backArgs");
            button(g, "Back", left + w - PAD - 92, top + 7, 44, mx, my, true, false, "", () -> send(data.getString("back"), "open", a, false));
        }
        // the tabs
        int tx = left + PAD;
        for (Tag e : data.getList("tabs", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) e;
            int tw = font.width(t.getString("l")) + 14;
            boolean over = in(mx, my, tx, top + HEAD + 3, tw, TAB_H);
            UiStyle.topTab(g, tx, top + HEAD + 3, tw, TAB_H, UiStyle.State.of(true, t.getBoolean("on"), over));
            g.drawString(font, t.getString("l"), tx + 7, top + HEAD + 7, UiStyle.TEXT, false);
            String id = t.getString("id");
            zone(tx, top + HEAD + 3, tw, TAB_H, () -> send("tab", LifePageArgs.of("tab", id), false));
            tx += tw + 2;
        }
        // the body, scrolled
        int y0 = contentTop(), y1 = contentBottom();
        UiStyle.well(g, left + PAD - 2, y0 - 2, w - PAD * 2 + 4, y1 - y0 + 2, UiStyle.PAGE);
        g.enableScissor(left + PAD, y0, left + w - PAD, y1);
        int y = y0 + 2 - scroll;
        int x = left + PAD + 2, iw = w - PAD * 2 - 10;
        for (EditBox b : boxes.values()) b.visible = false;
        for (Tag e : data.getList("blocks", Tag.TAG_COMPOUND)) {
            y = block(g, (CompoundTag) e, x, y, iw, mx, my, y0, y1);
        }
        contentH = y + scroll - (y0 + 2);
        g.disableScissor();
        int max = Math.max(0, contentH - (y1 - y0) + 4);
        if (scroll > max) scroll = max;
        if (max > 0) UiStyle.scrollBar(g, left + w - PAD - 5, y0, y1 - y0, scroll, contentH, y1 - y0);
        // the footer: the last word from the server
        String msg = data.getString("msg");
        if (!msg.isEmpty()) {
            int colour = data.getBoolean("ok") ? UiStyle.SUCCESS : UiStyle.DANGER;
            UiStyle.text(g, font, msg, left + PAD, top + h - FOOT + 7, w - PAD * 2, colour);
            if (font.width(msg) > w - PAD * 2 && in(mx, my, left + PAD, top + h - FOOT + 4, w - PAD * 2, 14)) tips.add(msg);
        }
        for (var r : renderables) r.render(g, mx, my, pt);
        if (!tips.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 500);
            UiStyle.tooltip(g, font, tips.get(tips.size() - 1), mx, my, 220);
            g.pose().popPose();
        }
    }

    /** One block at y; returns the y under it. */
    private int block(GuiGraphics g, CompoundTag b, int x, int y, int iw, int mx, int my, int y0, int y1) {
        boolean shown = true;
        switch (b.getString("t")) {
            case "h" -> {
                y += 3;
                UiStyle.heading(g, font, b.getString("text"), x, y, iw);
                return y + 14;
            }
            case "p" -> {
                int colour = switch (b.getInt("c")) {
                    case 1 -> UiStyle.SUCCESS;
                    case 2 -> UiStyle.WARNING;
                    case 3 -> UiStyle.DANGER;
                    case 4 -> UiStyle.TEXT_FAINT;
                    default -> UiStyle.TEXT;
                };
                for (FormattedCharSequence line : font.split(Component.literal(b.getString("text")), iw)) {
                    g.drawString(font, line, x, y, colour, false);
                    y += 10;
                }
                return y + 2;
            }
            case "bar" -> {
                int lw = Math.min(150, iw / 3);
                UiStyle.text(g, font, b.getString("l"), x, y + 2, lw - 4, UiStyle.TEXT);
                int rw = Math.min(110, font.width(b.getString("r")) + 4);
                UiStyle.bar(g, x + lw, y + 2, iw - lw - rw - 4, 8, b.getFloat("f"), UiStyle.ACCENT_FILL);
                UiStyle.text(g, font, b.getString("r"), x + iw - rw, y + 2, rw, UiStyle.TEXT_MUTED);
                return y + 13;
            }
            case "field" -> {
                UiStyle.text(g, font, b.getString("l"), x, y + 4, 110, UiStyle.TEXT);
                EditBox box = boxes.get(b.getString("id"));
                if (box != null) {
                    box.setX(x + 112);
                    box.setY(y + 1);
                    box.setWidth(Math.min(220, iw - 116));
                    box.visible = y + 1 >= y0 && y + 15 <= y1;
                }
                return y + 19;
            }
            case "choice" -> {
                UiStyle.text(g, font, b.getString("l"), x, y + 5, 110, UiStyle.TEXT);
                ListTag opts = b.getList("opts", Tag.TAG_COMPOUND);
                String id = b.getString("id");
                String v = form.getOrDefault(id, b.getString("v"));
                int at = 0;
                for (int i = 0; i < opts.size(); i++) if (opts.getCompound(i).getString("v").equals(v)) at = i;
                String label = opts.isEmpty() ? "" : opts.getCompound(at).getString("l");
                int bw = Math.min(iw - 116, Math.max(90, font.width(label) + 30));
                int next = opts.isEmpty() ? 0 : (at + 1) % opts.size();
                int prev = opts.isEmpty() ? 0 : (at + opts.size() - 1) % opts.size();
                button(g, "<", x + 112, y + 1, 14, mx, my, opts.size() > 1, false, "", () -> form.put(id, opts.getCompound(prev).getString("v")));
                button(g, label, x + 128, y + 1, bw - 32, mx, my, true, true, "Click for the next", () -> form.put(id, opts.getCompound(next).getString("v")));
                button(g, ">", x + 96 + bw, y + 1, 14, mx, my, opts.size() > 1, false, "", () -> form.put(id, opts.getCompound(next).getString("v")));
                return y + 19;
            }
            case "map" -> { return map(g, b, x, y, iw, mx, my); }
            case "pic" -> {
                int ph = 104, pw = Math.min(iw, 240);
                if (y + ph >= y0 && y <= y1) {
                    UiStyle.well(g, x, y, pw, ph, 0xFFB9D3E8);
                    BlueprintPicture.draw(g, b.getString("key"), b.getIntArray("cells"), x, y, pw, ph);
                    if (in(mx, my, x, y, pw, ph)) {
                        tips.add("Drag to turn it");
                        picHover = b.getString("key");
                    }
                }
                return y + ph + 2;
            }
            default -> {
                // a row
                ListTag btns = b.getList("b", Tag.TAG_COMPOUND);
                boolean bare = b.getString("l").isEmpty() && b.getString("s").isEmpty() && b.getString("r").isEmpty();
                int rh = bare ? BTN_H + 4 : b.getString("s").isEmpty() ? ROW_H - 4 : ROW_H + 2;
                if (y + rh < y0 || y > y1) shown = false;
                if (!shown) return y + rh;
                int bx = x + iw;
                int[] bws = new int[btns.size()];
                int all = 0;
                for (int i = 0; i < btns.size(); i++) {
                    bws[i] = Math.min(150, font.width(btns.getCompound(i).getString("l")) + 12);
                    all += bws[i] + 3;
                }
                int by = y + (rh - BTN_H) / 2;
                if (bare) bx = x + all;                                       // a row of buttons on its own reads from the left
                for (int i = btns.size() - 1; i >= 0; i--) {
                    CompoundTag c = btns.getCompound(i);
                    bx -= bws[i];
                    boolean on = c.getBoolean("on");
                    String why = c.getString("why");
                    button(g, c.getString("l"), bx, by, bws[i], mx, my, on, c.getBoolean("main"), on ? why : (why.isEmpty() ? "Not now" : why),
                        () -> press(c));
                    bx -= 3;
                }
                if (bare) return y + rh;
                int tx = x;
                if (b.contains("icon")) {
                    ItemStack icon = ItemStack.parseOptional(Minecraft.getInstance().level.registryAccess(), b.getCompound("icon"));
                    g.renderItem(icon, x, y + (rh - 16) / 2);
                    tx += 20;
                }
                String right = b.getString("r");
                int rw = right.isEmpty() ? 0 : Math.min(120, font.width(right) + 6);
                int room = Math.max(20, x + iw - all - rw - tx - 4);
                if (b.getString("s").isEmpty()) {
                    UiStyle.text(g, font, b.getString("l"), tx, y + (rh - 8) / 2, room, UiStyle.TEXT);
                } else {
                    UiStyle.text(g, font, b.getString("l"), tx, y + 3, room, UiStyle.TEXT);
                    UiStyle.text(g, font, b.getString("s"), tx, y + 13, room, UiStyle.TEXT_MUTED);
                    if (font.width(b.getString("s")) > room && in(mx, my, tx, y + 12, room, 10)) tips.add(b.getString("s"));
                }
                if (font.width(b.getString("l")) > room && in(mx, my, tx, y + 2, room, 10)) tips.add(b.getString("l"));
                if (rw > 0) UiStyle.text(g, font, right, x + iw - all - rw, y + (rh - 8) / 2, rw, UiStyle.TEXT_MUTED);
                g.fill(x, y + rh - 1, x + iw, y + rh, 0x22000000);
                return y + rh;
            }
        }
    }

    /** A small map of places: the town's heart, you, and the places to choose (the chosen ringed). */
    private int map(GuiGraphics g, CompoundTag b, int x, int y, int iw, int mx, int my) {
        UiStyle.text(g, font, b.getString("l"), x, y, iw, UiStyle.TEXT_MUTED);
        y += 11;
        int mh = 110, mw = Math.min(iw, 260);
        UiStyle.well(g, x, y, mw, mh, 0xFF9DB58A);
        ListTag pts = b.getList("pts", Tag.TAG_COMPOUND);
        int span = 16;
        for (int i = 0; i < pts.size(); i++) {
            CompoundTag p = pts.getCompound(i);
            span = Math.max(span, Math.max(Math.abs(p.getInt("x")), Math.abs(p.getInt("z"))) + 6);
        }
        double scale = Math.min((mw / 2.0 - 8) / span, (mh / 2.0 - 8) / span);
        int cx = x + mw / 2, cy = y + mh / 2;
        for (int i = 0; i < pts.size(); i++) {
            CompoundTag p = pts.getCompound(i);
            int px = cx + (int) Math.round(p.getInt("x") * scale), py = cy + (int) Math.round(p.getInt("z") * scale);
            int k = p.getInt("k");
            int colour = k == 1 ? 0xFF7A3B10 : k == 2 ? 0xFF2050C0 : p.getBoolean("sel") ? UiStyle.ACCENT : 0xFFE0E0E0;
            int r = k == 0 ? 4 : 3;
            if (p.getBoolean("sel")) g.fill(px - r - 2, py - r - 2, px + r + 2, py + r + 2, 0xFFFFFFFF);
            g.fill(px - r, py - r, px + r, py + r, colour);
            g.fill(px - r, py - r, px + r, py - r + 1, 0xFF202020);
            boolean over = in(mx, my, px - r - 2, py - r - 2, 2 * r + 4, 2 * r + 4);
            if (over) tips.add(p.getString("l"));
            if (p.contains("a")) {
                String a = p.getString("a");
                CompoundTag args = p.getCompound("args");
                zone(px - r - 2, py - r - 2, 2 * r + 4, 2 * r + 4, () -> send(a, args, true));
            }
        }
        // the legend to the right
        int lx = x + mw + 8, ly = y + 2;
        for (int i = 0; i < pts.size() && lx < x + iw - 40; i++) {
            CompoundTag p = pts.getCompound(i);
            if (p.getInt("k") != 0) continue;
            int colour = p.getBoolean("sel") ? UiStyle.ACCENT : UiStyle.TEXT_MUTED;
            UiStyle.text(g, font, (p.getBoolean("sel") ? "> " : "  ") + p.getString("l"), lx, ly, x + iw - lx, colour);
            ly += 10;
            if (ly > y + mh - 8) break;
        }
        return y + mh + 4;
    }

    private void press(CompoundTag c) {
        CompoundTag args = c.getCompound("args").copy();
        if (c.getBoolean("shift") && hasShiftDown() && args.contains("d")) args.putInt("d", args.getInt("d") * 10);
        send(c.getString("a"), args, true);
    }

    private void send(String action, CompoundTag args, boolean withForm) {
        send(data.getString("page"), action, args, withForm);
    }

    private void send(String page, String action, CompoundTag args, boolean withForm) {
        CompoundTag a = args.copy();
        if (withForm) {
            CompoundTag f = new CompoundTag();
            for (Map.Entry<String, String> e : form.entrySet()) f.putString(e.getKey(), e.getValue());
            a.put("f", f);
        }
        if (!data.getString("key").isEmpty() && !a.contains("key")) a.putString("key", data.getString("key"));
        LifeNetwork.act(page, action, a);
        UiStyle.click();
    }

    private void button(GuiGraphics g, String label, int x, int y, int bw, int mx, int my, boolean on, boolean main, String tip, Runnable act) {
        boolean over = in(mx, my, x, y, bw, BTN_H);
        UiStyle.button(g, font, UiStyle.clip(font, label, bw - 6), x, y, bw, BTN_H, UiStyle.State.of(on, main, over));
        if (over && !tip.isEmpty()) tips.add(tip);
        else if (over && font.width(label) > bw - 6) tips.add(label);
        if (on) zone(x, y, bw, BTN_H, act);
    }

    private void zone(int x, int y, int zw, int zh, Runnable act) {
        zones.add(new int[]{ x, y, x + zw, y + zh });
        acts.add(act);
    }

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            boolean inBody = my >= contentTop() && my < contentBottom();
            for (int i = zones.size() - 1; i >= 0; i--) {
                int[] z = zones.get(i);
                if (mx >= z[0] && mx < z[2] && my >= z[1] && my < z[3]) {
                    boolean header = z[1] < contentTop();
                    if (!header && !inBody) continue;
                    acts.get(i).run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private String picHover = "";

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!picHover.isEmpty()) {
            BlueprintPicture.drag(picHover, dx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll = Math.max(0, scroll - (int) (dy * 20));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A tiny helper so the tabs can make their arguments without the server's classes. */
    static final class LifePageArgs {
        static CompoundTag of(String k, String v) {
            CompoundTag t = new CompoundTag();
            t.putString(k, v);
            return t;
        }
    }
}
