package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [pa36] The Storage view: every container its assistant knows is its player's, nearest first, each with its name (or
 * kind), how far and which way, how full, what's in it (the biggest few), and whether it's the drop-off, the storage or
 * kept stocked; buttons to ping it, rename it, forget it, make it the drop-off, keep it stocked, take something out. A
 * search box finds a thing across the lot ("iron": every chest with iron, and the total).
 */
public final class PaStorageScreen extends PaLogiBase {

    private EditBox search, rename;
    private BlockPos renaming;
    private int seen = -1;

    PaStorageScreen() {
        super("Storage");
    }

    @Override
    protected void init() {
        size(420, 260);
        search = new EditBox(font, left + 10, top + 28, 150, 14, Component.literal("Find"));
        search.setMaxLength(32);
        search.setHint(Component.literal("Find a thing: iron, bread..."));
        addRenderableWidget(search);
        rename = new EditBox(font, 0, 0, 120, 14, Component.literal("Name"));
        rename.setMaxLength(24);
        rename.visible = false;
        addRenderableWidget(rename);
    }

    @Override
    protected String title() {
        return "Storage: the chests it knows are yours";
    }

    @Override
    protected void body(GuiGraphics g, int mx, int my) {
        CompoundTag s = PaLogiClient.storage;
        if (s == null) { g.drawString(font, "Looking...", left + 10, top + 50, UiStyle.TEXT_FAINT, false); return; }
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        ListTag all = s.getList("c", Tag.TAG_COMPOUND);
        List<CompoundTag> rows = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < all.size(); i++) {
            CompoundTag c = all.getCompound(i);
            if (q.isEmpty()) { rows.add(c); continue; }
            int n = 0;
            for (Tag t : c.getList("items", Tag.TAG_COMPOUND)) {
                CompoundTag it = (CompoundTag) t;
                if (it.getString("i").contains(q.replace(' ', '_')) || nameOf(it.getString("i")).toLowerCase(Locale.ROOT).contains(q)) n += it.getInt("n");
            }
            if (n > 0 || c.getString("n").toLowerCase(Locale.ROOT).contains(q)) { rows.add(c); total += n; }
        }
        // the header line: a count, the search total, the drop-off toggle, refresh
        String head = q.isEmpty() ? all.size() + " container" + (all.size() == 1 ? "" : "s") : rows.size() + " with \"" + q + "\": " + total + " in all";
        boolean auto = s.getBoolean("autoDrop");
        String ad = auto ? "Auto drop-off: on" : "Auto drop-off: off";
        int rw = bw("Refresh");
        g.drawString(font, UiStyle.clip(font, head, Math.max(40, w - bw(ad) - rw - 182)), left + 168, top + 31, muted(), false);
        button(g, ad, left + w - bw(ad) - rw - 12, top + 27, bw(ad), 15, mx, my, true, auto, "When its pack fills while working, it unloads at the drop-off and goes back to it", () -> {
            CompoundTag t = new CompoundTag();
            t.putBoolean("on", !auto);
            PaLogiClient.send("autodrop", t);
            PaLogiClient.send("storage", new CompoundTag());
        });
        button(g, "Refresh", left + w - rw - 8, top + 27, rw, 15, mx, my, true, false, "Look again", () -> PaLogiClient.send("storage", new CompoundTag()));
        int listTop = top + 48, listBottom = top + h - 8, rowH = 38;
        contentH = Math.max(0, rows.size() * rowH - (listBottom - listTop));
        scroll = Math.min(scroll, contentH);
        g.enableScissor(left + 4, listTop, left + w - 4, listBottom);
        int y = listTop - scroll;
        rename.visible = false;
        if (rows.isEmpty()) g.drawString(font, UiStyle.clip(font, q.isEmpty() ? "None yet. Open your chests, or ping them, and it learns them." : "No chest it knows has that.", w - 20), left + 10, y + 4, UiStyle.TEXT_FAINT, false);
        for (CompoundTag c : rows) {
            if (y + rowH >= listTop && y <= listBottom) row(g, c, y, mx, my, q);
            y += rowH;
        }
        g.disableScissor();
        if (contentH > 0) UiStyle.scrollBar(g, left + w - 8, listTop, listBottom - listTop, scroll, contentH, listBottom - listTop);
    }

    private void row(GuiGraphics g, CompoundTag c, int y, int mx, int my, String q) {
        BlockPos b = new BlockPos(c.getInt("x"), c.getInt("y"), c.getInt("z"));
        boolean over = in(mx, my, left + 6, y, w - 16, 36);
        g.fill(left + 6, y, left + w - 10, y + 36, over ? 0xFFE9F3F1 : 0xFFDDE3E2);
        if (c.getBoolean("drop") || c.getBoolean("store")) g.fill(left + 6, y, left + 8, y + 36, PaScreen.BRAND);
        String block = c.getString("b").isEmpty() ? "minecraft:chest" : c.getString("b");
        icon(g, block, left + 11, y + 3, 1);
        String name = c.getString("n");
        String kind = c.getString("t");
        String title = (name.isEmpty() ? cap(kind) : cap(name) + (name.endsWith(kind) ? "" : " " + kind))
            + (c.getBoolean("drop") ? "  · drop-off" : "") + (c.getBoolean("store") ? "  · storage" : "");
        g.drawString(font, UiStyle.clip(font, title, 170), left + 31, y + 3, UiStyle.TEXT, false);
        int size = c.getInt("s"), used = c.getInt("u");
        String where = c.getInt("d") + " blocks " + c.getString("dir") + (size > 0 ? " · " + used + "/" + size + " slots" : "");
        if (c.contains("stock")) where += " · kept at " + c.getInt("stockN") + " " + nameOrWord(c.getString("stock"));
        g.drawString(font, UiStyle.clip(font, where, 200), left + 31, y + 13, muted(), false);
        if (size > 0) UiStyle.bar(g, left + 31, y + 24, 60, 3, used / (float) size, used >= size ? UiStyle.DANGER_FILL : PaScreen.BRAND_LIGHT);
        // the biggest few things in it
        int ix = left + 100;
        for (Tag t : c.getList("items", Tag.TAG_COMPOUND)) {
            CompoundTag it = (CompoundTag) t;
            if (ix > left + 210) break;
            icon(g, it.getString("i"), ix, y + 20, it.getInt("n"));
            if (in(mx, my, ix, y + 20, 16, 16)) tipNow = nameOf(it.getString("i")) + " x" + it.getInt("n");
            ix += 18;
        }
        // its buttons
        int bx = left + w - 186, by = y + 3;
        if (b.equals(renaming)) {
            rename.setX(bx);
            rename.setY(by + 1);
            rename.setWidth(120);
            rename.visible = true;
            button(g, "Save", bx + 124, by, 34, 14, mx, my, true, true, "Name it", () -> saveName());
            button(g, "x", bx + 160, by, 14, 14, mx, my, true, false, "Never mind", () -> renaming = null);
        } else {
            button(g, "Ping", bx, by, 34, 14, mx, my, true, true, "A marker on it, so you can find it", () -> send("ping", b));
            button(g, "Rename", bx + 36, by, 44, 14, mx, my, true, false, "Give it a name: \"the iron chest\"", () -> { renaming = b; rename.setValue(name); setFocused(rename); });
            button(g, "Take...", bx + 82, by, 42, 14, mx, my, true, false, "Take something out of it", () -> PaLogiClient.open(b, "ltake", title));
            button(g, "Forget", bx + 126, by, 42, 14, mx, my, true, false, "It won't use this one again", () -> { send("forget", b); PaLogiClient.send("storage", new CompoundTag()); });
        }
        button(g, "Drop-off", bx, by + 17, 52, 14, mx, my, !c.getBoolean("drop"), c.getBoolean("drop"),
            c.getBoolean("drop") ? "This is where it unloads when its pack is full" : "Make this where it unloads when its pack is full",
            () -> { send("dropoff", b); PaLogiClient.send("storage", new CompoundTag()); });
        if (c.contains("stock")) button(g, "Unstock", bx + 54, by + 17, 46, 14, mx, my, true, false, "Stop topping it up", () -> { send("unstock", b); PaLogiClient.send("storage", new CompoundTag()); });
        else button(g, "Stock...", bx + 54, by + 17, 46, 14, mx, my, true, false, "Keep this chest stocked: it checks every few minutes and tops it up", () -> PaLogiClient.open(b, "lstock", title));
        int mt = c.getInt("mt");                                            // [pa36b] what it may take from here on its own account
        String ml = mt >= 2 ? "Take: any" : mt == 1 ? "Take: basics" : "Take: no";
        String mtip = mt >= 2 ? "When nobody asked, it may take anything from here for its kit, valuables too"
            : mt == 1 ? "When nobody asked, it may take basics from here for its kit (food, wood, coal), never iron and up"
            : "When nobody asked, it takes nothing from here (your orders still can)";
        button(g, ml, bx + 102, by + 17, 66, 14, mx, my, true, mt > 0, mtip, () -> { send("maytake", b); PaLogiClient.send("storage", new CompoundTag()); });
    }

    private static String nameOrWord(String item) {
        return item.startsWith("item:") ? nameOf(item.substring(5)) : item;
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void send(String op, BlockPos b) {
        PaLogiClient.send(op, PaLogiClient.pos(b));
    }

    private void saveName() {
        if (renaming == null || rename.getValue().isBlank()) return;
        CompoundTag t = PaLogiClient.pos(renaming);
        t.putString("name", rename.getValue().trim());
        PaLogiClient.send("rename", t);
        renaming = null;
        PaLogiClient.send("storage", new CompoundTag());
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && renaming != null) { saveName(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void tick() {
        if (seen != PaLogiClient.stamp) seen = PaLogiClient.stamp;
    }
}
