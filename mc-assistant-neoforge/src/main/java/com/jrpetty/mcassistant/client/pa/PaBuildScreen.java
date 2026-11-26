package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * [featpa] Blueprints (Orders > Blueprints): show it a building, keep it by name, place a copy where you look.
 * <ul>
 * <li><b>Show it</b>: "Copy the building I'm looking at" (the whole building joined to that block), or "Copy the marked
 *     area" after pinging two corners (the ping wheel's Mark an area); the name box names it (or it picks one).</li>
 * <li><b>Kept</b>: the list, newest first, each with its size and block count; pick one to see what it takes against
 *     what it has (its pack and your chests near by), short in red; rename or delete it.</li>
 * <li><b>Place</b>: "Place where I'm looking" stands a ghost of it there; turn it, mirror it, nudge it a block at a
 *     time (left, right, forward, back as you face, up, down), aim again; your own blocks in the way show red, built
 *     round unless you choose "Clear them" (they go in its pack); "Build it".</li>
 * <li><b>Building</b>: layer, blocks placed of all, a bar; what it's short of, with "Get what's short" and "Buy it at
 *     the market"; Stop and Carry on.</li>
 * </ul>
 * The panel sits at the side with no shade over the world, so the ghost and the build stay in view while it is open.
 */
public final class PaBuildScreen extends PaLogiBase {

    private EditBox name;
    private int seen = -1, poll;
    private int listScroll;

    PaBuildScreen() {
        super("Blueprints");
    }

    @Override
    protected void init() {
        w = Math.min(250, width - 16);
        h = height - 16;
        left = width - w - 8;
        top = 8;
        String keep = name == null ? "" : name.getValue();
        name = new EditBox(font, left + 8, top + 44, w - 16, 14, Component.literal("Name"));
        name.setMaxLength(24);
        name.setHint(Component.literal("Name it (optional)"));
        name.setValue(keep);
        addRenderableWidget(name);
    }

    @Override
    protected String title() {
        return "Blueprints";
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        frame(g, title(), mx, my);                                     // no shade: the world (and the ghost) stays in view
        body(g, mx, my);
    }

    @Override
    public void tick() {
        super.tick();
        if (++poll % 20 == 0) PaKitClient.send("bp_status");
    }

    private static CompoundTag args(String k, String v) {
        CompoundTag t = new CompoundTag();
        t.putString(k, v);
        return t;
    }

    @Override
    protected void body(GuiGraphics g, int mx, int my) {
        CompoundTag l = PaKitClient.list, st = PaKitClient.status, pv = PaKitClient.preview;
        int x = left + 8, iw = w - 16, y = top + 28;
        g.drawString(font, "Show it a building", x, y, UiStyle.TEXT, false);
        y += 34;
        int half = (iw - 4) / 2;
        button(g, "Copy what I look at", x, y, half, 16, mx, my, true, false,
            "Look at a building (or stand in it) and click: it takes in the whole building joined to that block", () -> PaKitClient.send("bp_copy_look", args("name", name.getValue())));
        boolean area = l != null && l.getBoolean("area");
        button(g, "Copy marked area", x + half + 4, y, half, 16, mx, my, area, false,
            area ? "The two corners you pinged last (the ping wheel's Mark an area)" : "Ping one corner, pick Mark an area on the wheel, ping the other corner (or pick Copy this building there)",
            () -> PaKitClient.send("bp_copy_area", args("name", name.getValue())));
        y += 22;
        // a build under way
        CompoundTag run = st != null && st.contains("run") ? st.getCompound("run") : null;
        if (run != null && (run.getBoolean("active") || !run.getBoolean("done"))) {
            y = runBox(g, run, x, y, iw, mx, my);
        }
        if (pv != null && st != null && st.getBoolean("previewing")) {
            placing(g, pv, x, y, iw, mx, my);
            return;
        }
        // the list
        UiStyle.heading(g, font, "Kept", x, y, iw);
        y += 12;
        ListTag list = l == null ? new ListTag() : l.getList("list", Tag.TAG_COMPOUND);
        String sel = l == null ? "" : l.getString("sel");
        if (list.isEmpty()) {
            g.drawString(font, "None yet.", x + 2, y + 2, muted(), false);
            y += 14;
        }
        int rows = Math.min(list.size(), 6);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() - rows)));
        for (int i = listScroll; i < listScroll + rows && i < list.size(); i++) {
            CompoundTag b = list.getCompound(i);
            String n = b.getString("n");
            boolean on = n.equalsIgnoreCase(sel);
            boolean over = in(mx, my, x, y, iw, 15);
            g.fill(x, y, x + iw, y + 15, on ? PaScreen.BRAND : over ? 0xFFE6F2EF : 0xFFDCE6E4);
            g.drawString(font, UiStyle.clip(font, n, iw / 2), x + 4, y + 4, on ? 0xFFFFFFFF : UiStyle.TEXT, false);
            String sz = b.getString("size") + " · " + b.getInt("blocks");
            g.drawString(font, UiStyle.clip(font, sz, iw / 2 - 6), x + iw - 4 - Math.min(font.width(sz), iw / 2 - 6), y + 4, on ? 0xFFE0F2EE : muted(), false);
            zone(x, y, iw, 15, () -> PaKitClient.send("bp_select", args("name", n)), "");
            y += 16;
        }
        if (list.size() > rows) {
            button(g, "▲", x + iw - 30, y, 14, 12, mx, my, listScroll > 0, false, "", () -> listScroll--);
            button(g, "▼", x + iw - 14, y, 14, 12, mx, my, listScroll + rows < list.size(), false, "", () -> listScroll++);
            y += 14;
        }
        if (sel.isEmpty()) return;
        y += 2;
        int third = (iw - 8) / 3;
        button(g, "Place where I look", x, y, iw, 16, mx, my, true, true, "Look at the ground where it should go (its middle there) and click: a ghost of it stands there",
            () -> PaKitClient.send("bp_aim", args("name", sel)));
        y += 19;
        button(g, "Rename", x, y, third, 14, mx, my, !name.getValue().isBlank(), false, "Type the new name in the box above, then click",
            () -> { CompoundTag t = args("name", sel); t.putString("to", name.getValue()); PaKitClient.send("bp_rename", t); });
        button(g, "Delete", x + third + 4, y, third, 14, mx, my, true, false, "Forget this blueprint", () -> PaKitClient.send("bp_delete", args("name", sel)));
        button(g, "Refresh", x + 2 * (third + 4), y, third, 14, mx, my, true, false, "Count what it has again", () -> PaKitClient.send("bp_select", args("name", sel)));
        y += 18;
        // what it takes
        UiStyle.heading(g, font, "What it takes (has / needs)", x, y, iw);
        y += 12;
        ListTag mat = l.getList("mat", Tag.TAG_COMPOUND);
        int cols = Math.max(1, iw / 58), i = 0;
        for (Tag tg : mat) {
            CompoundTag m = (CompoundTag) tg;
            int cx = x + (i % cols) * 58, cy = y + (i / cols) * 20;
            if (cy > top + h - 22) break;
            icon(g, m.getString("i"), cx, cy, 1);
            int have = m.getInt("have"), need = m.getInt("need");
            g.drawString(font, Math.min(have, 9999) + "/" + need, cx + 18, cy + 5, have >= need ? UiStyle.SUCCESS : UiStyle.DANGER, false);
            if (in(mx, my, cx, cy, 56, 18)) tipNow = nameOf(m.getString("i")) + ": needs " + need + ", has " + have + (have >= need ? "" : " (short by " + (need - have) + ")");
            i++;
        }
    }

    /** The build under way: its bar, what it's short of, and its buttons. */
    private int runBox(GuiGraphics g, CompoundTag run, int x, int y, int iw, int mx, int my) {
        UiStyle.well(g, x, y, iw, 62, 0xFFEFF5F4);
        boolean active = run.getBoolean("active");
        String head = (active ? "Building " : run.getBoolean("done") ? "Built " : "Stopped: ") + run.getString("name");
        g.drawString(font, UiStyle.clip(font, head, iw - 8), x + 4, y + 4, UiStyle.TEXT, false);
        String line = "Layer " + run.getInt("layer") + " of " + run.getInt("layers") + " · " + run.getInt("placed") + "/" + run.getInt("total") + " blocks";
        g.drawString(font, UiStyle.clip(font, line, iw - 8), x + 4, y + 15, muted(), false);
        UiStyle.bar(g, x + 4, y + 26, iw - 8, 6, run.getInt("pct") / 100f, PaScreen.BRAND_LIGHT);
        ListTag miss = run.getList("missing", Tag.TAG_COMPOUND);
        if (!miss.isEmpty()) {
            StringBuilder b = new StringBuilder("Short of: ");
            for (int i = 0; i < miss.size() && i < 4; i++) { CompoundTag m = miss.getCompound(i); b.append(i > 0 ? ", " : "").append(m.getInt("n")).append(' ').append(nameOf(m.getString("i")).toLowerCase()); }
            g.drawString(font, UiStyle.clip(font, b.toString(), iw - 8), x + 4, y + 35, UiStyle.DANGER, false);
        }
        int q = (iw - 8 - 12) / 4;
        int by = y + 46;
        if (active) button(g, "Stop", x + 4, by, q, 13, mx, my, true, false, "Stop building (Carry on picks it up again)", () -> PaKitClient.send("bp_stop"));
        else button(g, "Carry on", x + 4, by, q, 13, mx, my, true, true, "Pick the build up where it left off", () -> PaKitClient.send("bp_resume"));
        button(g, "Get short", x + 8 + q, by, q, 13, mx, my, true, false, "It crafts what it can from your things, and goes for the rest", () -> PaKitClient.send("bp_fetch"));
        button(g, "Buy short", x + 12 + 2 * q, by, q, 13, mx, my, true, false, "An errand to the market for what's short", () -> PaKitClient.send("bp_buy"));
        button(g, "Errand...", x + 16 + 3 * q, by, q, 13, mx, my, true, false, "The errand screen", PaKitClient::openErrand);
        return y + 66;
    }

    /** Placing: the ghost's buttons. */
    private void placing(GuiGraphics g, CompoundTag pv, int x, int y, int iw, int mx, int my) {
        UiStyle.heading(g, font, "Placing " + pv.getString("name"), x, y, iw);
        y += 12;
        g.drawString(font, UiStyle.clip(font, pv.getString("size") + (pv.getBoolean("mirror") ? " · mirrored" : "") + " · turned " + pv.getInt("rot") * 90 + "°", iw), x, y, muted(), false);
        y += 12;
        int half = (iw - 4) / 2;
        button(g, "↻ Turn", x, y, half, 16, mx, my, true, false, "A quarter turn clockwise, about its middle", () -> PaKitClient.send("bp_rot"));
        button(g, "Mirror", x + half + 4, y, half, 16, mx, my, true, pv.getBoolean("mirror"), "Flip it left for right", () -> PaKitClient.send("bp_mirror"));
        y += 20;
        // nudges, as the player faces
        Direction f = Minecraft.getInstance().player == null ? Direction.NORTH : Minecraft.getInstance().player.getDirection();
        int b = (iw - 20) / 6;
        String[] labels = {"Left", "Right", "Fwd", "Back", "Up", "Down"};
        Direction[] dirs = {f.getCounterClockWise(), f.getClockWise(), f, f.getOpposite(), Direction.UP, Direction.DOWN};
        for (int i = 0; i < 6; i++) {
            Direction d = dirs[i];
            button(g, labels[i], x + i * (b + 4), y, b, 14, mx, my, true, false, "Move it a block " + labels[i].toLowerCase().replace("fwd", "forward"), () -> {
                CompoundTag t = new CompoundTag();
                t.putInt("dx", d.getStepX());
                t.putInt("dy", d.getStepY());
                t.putInt("dz", d.getStepZ());
                PaKitClient.send("bp_nudge", t);
            });
        }
        y += 18;
        button(g, "Aim again where I look", x, y, iw, 14, mx, my, true, false, "Close this, look where it should go, open Blueprints and click this",
            () -> PaKitClient.send("bp_aim", args("name", pv.getString("name"))));
        y += 20;
        int clashes = pv.getInt("clashes");
        if (clashes > 0) {
            y = paraWrap(g, clashes + " of your own blocks are in the way (red). It won't knock anything of yours down unless you say so.", x, y, iw, UiStyle.DANGER);
            boolean clear = pv.getBoolean("clear");
            button(g, "Build round them", x, y, half, 14, mx, my, true, !clear, "Leave your blocks where they are; it builds the rest", () -> { CompoundTag t = new CompoundTag(); t.putBoolean("on", false); PaKitClient.send("bp_clear", t); });
            button(g, "Clear them", x + half + 4, y, half, 14, mx, my, true, clear, "It takes them down first (they go in its pack, chests' things too)", () -> { CompoundTag t = new CompoundTag(); t.putBoolean("on", true); PaKitClient.send("bp_clear", t); });
            y += 18;
        } else {
            g.drawString(font, "Nothing of yours in the way.", x, y, UiStyle.SUCCESS, false);
            y += 12;
        }
        String shortOf = pv.getString("short");
        if (!shortOf.isEmpty()) {
            y = paraWrap(g, "Short of " + shortOf + ". It'll use what it has, fetch from your chests, and tell you what's missing.", x, y, iw, UiStyle.WARNING);
            button(g, "Get what's short", x, y, half, 14, mx, my, true, false, "It crafts what it can from your things, and goes for the rest", () -> PaKitClient.send("bp_fetch"));
            button(g, "Buy at the market", x + half + 4, y, half, 14, mx, my, true, false, "An errand: the shortfall on its shopping list", () -> PaKitClient.send("bp_buy"));
            y += 18;
        } else {
            g.drawString(font, "It has everything it needs.", x, y, UiStyle.SUCCESS, false);
            y += 12;
        }
        y += 4;
        button(g, "Build it", x, y, half, 18, mx, my, true, true, "It walks over and builds it, layer by layer from the ground up", () -> PaKitClient.send("bp_confirm"));
        button(g, "Cancel", x + half + 4, y, half, 18, mx, my, true, false, "Take the ghost away", () -> PaKitClient.send("bp_cancel"));
    }

    private int paraWrap(GuiGraphics g, String text, int x, int y, int w, int colour) {
        for (var line : font.split(Component.literal(text), w)) {
            g.drawString(font, line, x, y, colour, false);
            y += 10;
        }
        return y + 2;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (name.isFocused() && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) return name.keyPressed(key, scan, mods) || true;
        return super.keyPressed(key, scan, mods);
    }
}
