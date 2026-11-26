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
 * <li><b>Show it</b>: "Copy what I look at" (the whole building joined to that block), or "Copy marked area" after
 *     pinging two corners (the ping wheel's Mark an area); the name box names it (or it picks one).</li>
 * <li><b>Kept</b>: the list, newest first, each with its size and block count; pick one to see what it takes against
 *     what it has (its pack and your chests near by), short in red; rename or delete it (delete asks twice).</li>
 * <li><b>Place</b>: "Place where I look" stands a ghost of it there; turn it, mirror it, nudge it a block at a time
 *     (left, right, forward, back as you face, up, down), aim again, "Show me" marks it; your own blocks in the way show
 *     red, built round unless you choose "Clear them" (asks twice: they go in its pack); "Build it".</li>
 * <li><b>Building</b>: layer, blocks placed of all, a bar; what it's short of, with "Get short" and "Buy short"; Stop
 *     and Carry on; "Show me".</li>
 * </ul>
 * The panel sits at the side with no shade over the world, so the ghost and the build stay in view while it's open. The
 * strip at its foot says what the last click did, why, and what's next.
 */
public final class PaBuildScreen extends PaKitBase {

    private EditBox name;
    private int poll, listScroll;

    PaBuildScreen() {
        super("Blueprints", "bp");
    }

    @Override
    protected void init() {
        w = Math.min(256, width - 16);
        h = height - 16;
        left = width - w - 8;
        top = 8;
        String keep = name == null ? "" : name.getValue();
        name = new EditBox(font, left + 8, top + 40, w - 16, 14, Component.literal("Name"));
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
    protected String idleWords() {
        return "Show it a building, then place a copy where you look. Every click says here what happened and what's next.";
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
        int footH = h < 300 ? 34 : 44, foot = top + h - footH - 6;
        boolean placingNow = pv != null && st != null && st.getBoolean("previewing");
        name.visible = hintSeen() && !placingNow;
        if (hint(g, x, y, iw, "Copying a building", new String[]{
                "Look at a building and click Copy what I look at.",
                "Pick it, look where the copy goes, click Place where I look.",
                "Turn or mirror the ghost. Red marks your own blocks: it builds round them.",
                "Click Build it: it builds by hand from its pack and your chests."}, mx, my)) return;
        feedback(g, x, foot, iw, footH);
        if (placingNow) {
            placing(g, pv, x, y, iw, foot - 4, mx, my);
            return;
        }
        g.drawString(font, "Show it a building", x, y, UiStyle.TEXT, false);
        y += 30;
        int half = (iw - 4) / 2;
        button(g, "Copy what I look at", x, y, half, 16, mx, my, true, false,
            "Look at a building (or stand in it) and click: it takes in the whole building joined to that block", () -> PaKitClient.send("bp_copy_look", args("name", name.getValue())));
        boolean area = l != null && l.getBoolean("area");
        button(g, "Copy marked area", x + half + 4, y, half, 16, mx, my, area, false,
            area ? "The two corners you pinged last (the ping wheel's Mark an area)" : "no area marked yet. Ping one corner, pick Mark an area on the wheel, then ping the other corner",
            () -> PaKitClient.send("bp_copy_area", args("name", name.getValue())));
        y += 22;
        // a build under way
        CompoundTag run = st != null && st.contains("run") ? st.getCompound("run") : null;
        if (run != null && (run.getBoolean("active") || !run.getBoolean("done"))) y = runBox(g, run, x, y, iw, mx, my);
        // the list
        UiStyle.heading(g, font, "Kept", x, y, iw);
        y += 12;
        ListTag list = l == null ? new ListTag() : l.getList("list", Tag.TAG_COMPOUND);
        String sel = l == null ? "" : l.getString("sel");
        if (list.isEmpty()) {
            y = para(g, "None yet. Copy a building above and it shows here.", x + 2, y + 2, iw - 4, UiStyle.TEXT_MUTED);
        }
        int below = sel.isEmpty() ? 0 : 19 + 18 + 12 + 20;               // the buttons and one row of what it takes
        int rows = Math.max(1, Math.min(Math.min(list.size(), 5), (foot - 4 - y - below - (list.size() > 5 ? 14 : 0)) / 16));
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() - rows)));
        for (int i = listScroll; i < listScroll + rows && i < list.size(); i++) {
            CompoundTag b = list.getCompound(i);
            String n = b.getString("n");
            boolean on = n.equalsIgnoreCase(sel);
            tile(g, x, y, iw, 15, on, mx, my);
            g.drawString(font, UiStyle.clip(font, n, iw / 2 - 4), x + 6, y + 4, UiStyle.TEXT, false);
            String sz = b.getString("size");
            String szc = UiStyle.clip(font, sz, iw / 2 - 6);
            g.drawString(font, szc, x + iw - 4 - font.width(szc), y + 4, UiStyle.TEXT_MUTED, false);
            zone(x, y, iw, 15, () -> PaKitClient.send("bp_select", args("name", n)), "Pick " + n + ": " + b.getString("size") + ", " + b.getInt("blocks") + " blocks");
            y += 16;
        }
        if (list.size() > rows) {
            g.drawString(font, (listScroll + 1) + "-" + Math.min(list.size(), listScroll + rows) + " of " + list.size(), x, y + 2, UiStyle.TEXT_FAINT, false);
            button(g, "▲", x + iw - 30, y, 14, 12, mx, my, listScroll > 0, false, listScroll > 0 ? "Up the list" : "already at the top", () -> listScroll--);
            button(g, "▼", x + iw - 14, y, 14, 12, mx, my, listScroll + rows < list.size(), false, listScroll + rows < list.size() ? "Down the list" : "already at the bottom", () -> listScroll++);
            y += 14;
        }
        if (sel.isEmpty()) return;
        y += 2;
        if (y + 16 > foot - 4) return;                                   // no room (a build box above, a small window): the list alone
        int third = (iw - 8) / 3;
        button(g, "Place where I look", x, y, iw, 16, mx, my, true, true, "Close this, look at the ground where it should go, open Blueprints, click: a ghost of it stands there (its middle where you look)",
            () -> PaKitClient.send("bp_aim", args("name", sel)));
        y += 19;
        if (y + 14 > foot - 4) return;
        boolean named = !name.getValue().isBlank();
        button(g, "Rename", x, y, third, 14, mx, my, named, false, named ? "Rename it to \"" + name.getValue() + "\"" : "type the new name in the box at the top first",
            () -> { CompoundTag t = args("name", sel); t.putString("to", name.getValue()); PaKitClient.send("bp_rename", t); });
        confirmButton(g, "del:" + sel, "Delete", "Sure? Delete", x + third + 4, y, third, 14, mx, my, true, false,
            "Forget this blueprint for good (the building itself stays standing)", () -> PaKitClient.send("bp_delete", args("name", sel)));
        button(g, "Recount", x + 2 * (third + 4), y, third, 14, mx, my, true, false, "Count what it has again (its pack and your chests near it)", () -> PaKitClient.send("bp_select", args("name", sel)));
        y += 18;
        if (y + 30 > foot - 4) return;
        // what it takes
        UiStyle.heading(g, font, "What it takes (has / needs)", x, y, iw);
        y += 12;
        ListTag mat = l.getList("mat", Tag.TAG_COMPOUND);
        int cols = Math.max(1, iw / 60), i = 0;
        for (Tag tg : mat) {
            CompoundTag m = (CompoundTag) tg;
            int cx = x + (i % cols) * 60, cy = y + (i / cols) * 20;
            if (cy + 18 > foot - 2) { g.drawString(font, "+" + (mat.size() - i) + " more", cx, cy + 5, UiStyle.TEXT_FAINT, false); break; }
            icon(g, m.getString("i"), cx, cy, 1);
            int have = m.getInt("have"), need = m.getInt("need");
            g.drawString(font, Math.min(have, 9999) + "/" + need, cx + 18, cy + 5, have >= need ? UiStyle.SUCCESS : UiStyle.DANGER, false);
            if (in(mx, my, cx, cy, 58, 18)) tipNow = nameOf(m.getString("i")) + ": needs " + need + ", has " + have + (have >= need ? "" : " (short by " + (need - have) + ")");
            i++;
        }
    }

    /** The build under way: its bar, what it's short of, and its buttons. */
    private int runBox(GuiGraphics g, CompoundTag run, int x, int y, int iw, int mx, int my) {
        UiStyle.well(g, x, y, iw, 64, UiStyle.CARD);
        boolean active = run.getBoolean("active");
        String head = (active ? "Building " : run.getBoolean("done") ? "Built " : "Stopped: ") + run.getString("name");
        UiStyle.title(g, font, UiStyle.clip(font, head, iw - 60), x + 4, y + 4);
        button(g, "Show me", x + iw - 52, y + 2, 48, 12, mx, my, true, false, "Mark the build on your screen, with how far it is", () -> PaKitClient.send("bp_show"));
        String line = active ? "Layer " + run.getInt("layer") + " of " + run.getInt("layers") + " · " + run.getInt("placed") + " of " + run.getInt("total") + " blocks"
            : run.getInt("placed") + " of " + run.getInt("total") + " blocks done";
        g.drawString(font, UiStyle.clip(font, line, iw - 8), x + 4, y + 16, UiStyle.TEXT_MUTED, false);
        UiStyle.bar(g, x + 4, y + 27, iw - 8, 6, run.getInt("pct") / 100f, UiStyle.ACCENT_FILL);
        ListTag miss = run.getList("missing", Tag.TAG_COMPOUND);
        boolean shortOf = !miss.isEmpty();
        if (shortOf) {
            StringBuilder b = new StringBuilder("Short of: ");
            for (int i = 0; i < miss.size() && i < 4; i++) { CompoundTag m = miss.getCompound(i); b.append(i > 0 ? ", " : "").append(m.getInt("n")).append(' ').append(plural(nameOf(m.getString("i")).toLowerCase(), m.getInt("n"))); }
            g.drawString(font, UiStyle.clip(font, b.toString(), iw - 8), x + 4, y + 36, UiStyle.DANGER, false);
        }
        int q = (iw - 8 - 12) / 4;
        int by = y + 48;
        if (active) button(g, "Stop", x + 4, by, q, 13, mx, my, true, false, "Stop building; Carry on picks it up where it left off", () -> PaKitClient.send("bp_stop"));
        else button(g, "Carry on", x + 4, by, q, 13, mx, my, true, true, "Pick the build up where it left off", () -> PaKitClient.send("bp_resume"));
        button(g, "Get short", x + 8 + q, by, q, 13, mx, my, true, false, "It crafts what it can from your things, and goes for the rest", () -> PaKitClient.send("bp_fetch"));
        button(g, "Buy short", x + 12 + 2 * q, by, q, 13, mx, my, true, false, "What's short goes on an errand's shopping list (you send it)", () -> PaKitClient.send("bp_buy"));
        button(g, "Errand", x + 16 + 3 * q, by, q, 13, mx, my, true, false, "Open the errand screen", PaKitClient::openErrand);
        return y + 68;
    }

    private static String plural(String n, int k) {
        if (k == 1 || n.endsWith("s") || n.endsWith("glass") || n.endsWith("stone") || n.endsWith("wool") || n.endsWith("bread") || n.endsWith("dirt") || n.endsWith("sand")) return n;
        return n.endsWith("ch") || n.endsWith("sh") || n.endsWith("x") ? n + "es" : n + "s";
    }

    /** Placing: the ghost's buttons. */
    private void placing(GuiGraphics g, CompoundTag pv, int x, int y, int iw, int bottom, int mx, int my) {
        String about = pv.getString("size") + (pv.getBoolean("mirror") ? ", mirrored" : "") + ", turned " + pv.getInt("rot") * 90 + "°";
        UiStyle.title(g, font, UiStyle.clip(font, "Placing " + pv.getString("name"), iw - 54), x, y + 2);
        button(g, "Show me", x + iw - 50, y, 50, 13, mx, my, true, false, "Mark the ghost on your screen, with how far it is", () -> PaKitClient.send("bp_show"));
        y += 15;
        g.drawString(font, UiStyle.clip(font, about, iw), x, y, UiStyle.TEXT_MUTED, false);
        y += 11;
        int half = (iw - 4) / 2;
        button(g, "Turn", x, y, half, 16, mx, my, true, false, "A quarter turn clockwise, about its middle", () -> PaKitClient.send("bp_rot"));
        button(g, pv.getBoolean("mirror") ? "Unmirror" : "Mirror", x + half + 4, y, half, 16, mx, my, true, false, "Flip it left for right", () -> PaKitClient.send("bp_mirror"));
        y += 20;
        Direction f = Minecraft.getInstance().player == null ? Direction.NORTH : Minecraft.getInstance().player.getDirection();
        int b = (iw - 20) / 6;
        String[] labels = {"Left", "Right", "Fwd", "Back", "Up", "Down"};
        String[] words = {"left", "right", "forward", "back", "up", "down"};
        Direction[] dirs = {f.getCounterClockWise(), f.getClockWise(), f, f.getOpposite(), Direction.UP, Direction.DOWN};
        for (int i = 0; i < 6; i++) {
            Direction d = dirs[i];
            button(g, labels[i], x + i * (b + 4), y, b, 14, mx, my, true, false, "Move it one block " + words[i] + " (as you face now)", () -> {
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
        y += 19;
        int clashes = pv.getInt("clashes");
        boolean clear = pv.getBoolean("clear");
        if (clashes > 0) {
            g.drawString(font, UiStyle.clip(font, clashes + " of your blocks in the way (red): kept unless you say", iw), x, y, UiStyle.DANGER, false);
            if (in(mx, my, x, y, iw, 10)) tipNow = clashes + " of your own blocks are in the way (red outlines). It won't knock anything of yours down unless you choose Clear them.";
            y += 11;
            button(g, "Build round them", x, y, half, 14, mx, my, clear, false, clear ? "Leave your blocks where they are; it builds the rest" : "already building round them", () -> {
                CompoundTag t = new CompoundTag(); t.putBoolean("on", false); PaKitClient.send("bp_clear", t); });
            confirmButton(g, "clear", "Clear them", "Sure? Clear", x + half + 4, y, half, 14, mx, my, !clear, false,
                !clear ? "It takes your " + clashes + " blocks down first (they go in its pack, chests' things too)" : "already set to clear them",
                () -> { CompoundTag t = new CompoundTag(); t.putBoolean("on", true); PaKitClient.send("bp_clear", t); });
            y += 18;
        } else {
            g.drawString(font, "Nothing of yours in the way.", x, y, UiStyle.SUCCESS, false);
            y += 12;
        }
        String shortOf = pv.getString("short");
        if (!shortOf.isEmpty()) {
            g.drawString(font, UiStyle.clip(font, "Short of " + shortOf, iw), x, y, UiStyle.WARNING, false);
            if (in(mx, my, x, y, iw, 10)) tipNow = "Short of " + shortOf + ". It uses what it has, fetches from your chests near the spot, and says what's still missing.";
            y += 11;
            button(g, "Get what's short", x, y, half, 14, mx, my, true, false, "It crafts what it can from your things, and goes for the rest", () -> PaKitClient.send("bp_fetch"));
            button(g, "Buy at the market", x + half + 4, y, half, 14, mx, my, true, false, "What's short goes on an errand's shopping list (you send it)", () -> PaKitClient.send("bp_buy"));
            y += 18;
        } else {
            g.drawString(font, "It has everything it needs.", x, y, UiStyle.SUCCESS, false);
            y += 12;
        }
        y = Math.min(y + 4, bottom - 18);
        if (clear && clashes > 0) confirmButton(g, "build", "Build it", "Sure? Build", x, y, half, 18, mx, my, true, true,
            "Builds it, taking down " + clashes + " of your blocks first", () -> PaKitClient.send("bp_confirm"));
        else button(g, "Build it", x, y, half, 18, mx, my, true, true, "It walks over and builds it, layer by layer from the ground up", () -> PaKitClient.send("bp_confirm"));
        button(g, "Cancel", x + half + 4, y, half, 18, mx, my, true, false, "Take the ghost away; nothing is built", () -> PaKitClient.send("bp_cancel"));
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (name.isFocused() && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) return name.keyPressed(key, scan, mods) || true;
        return super.keyPressed(key, scan, mods);
    }
}
