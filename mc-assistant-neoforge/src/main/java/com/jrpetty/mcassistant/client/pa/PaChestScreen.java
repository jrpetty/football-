package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [pa36] The chest wheel's pickers. Put stuff in: what's in its pack and in yours, a stack or all of each (yours are
 * handed over by the pick). Take out: what's in the chest, to you or to its pack. Fill this with / keep stocked with:
 * a thing (typed, or one of the chest's own or its pack's) and how many. Name this chest: a name.
 */
public final class PaChestScreen extends PaLogiBase {

    enum Mode { PUT, TAKE, FILL, STOCK, NAME }

    private final BlockPos at;
    private final Mode mode;
    private final String label;
    private EditBox box;
    private String note = "";
    private int qty = 64;

    PaChestScreen(BlockPos at, Mode mode, String label) {
        super("Chest");
        this.at = at;
        this.mode = mode;
        this.label = label == null || label.isEmpty() ? "Chest" : label;
    }

    @Override
    protected void init() {
        size(340, 236);
        if (mode == Mode.FILL || mode == Mode.STOCK || mode == Mode.NAME) {
            box = new EditBox(font, left + 10, top + 30, w - 20, 16, Component.literal(mode == Mode.NAME ? "Name" : "What"));
            box.setMaxLength(mode == Mode.NAME ? 24 : 40);
            box.setHint(Component.literal(mode == Mode.NAME ? "iron, food, wood, storage..." : "bread, stone, torches, oak logs..."));
            addRenderableWidget(box);
            setInitialFocus(box);
        }
    }

    @Override
    protected String title() {
        return switch (mode) {
            case PUT -> "Put stuff in: " + label;
            case TAKE -> "Take out of: " + label;
            case FILL -> "Fill " + label + " with...";
            case STOCK -> "Keep " + label + " stocked with...";
            case NAME -> "Name this chest";
        };
    }

    @Override
    protected void body(GuiGraphics g, int mx, int my) {
        switch (mode) {
            case PUT -> put(g, mx, my);
            case TAKE -> take(g, mx, my);
            case FILL, STOCK -> fill(g, mx, my);
            case NAME -> name(g, mx, my);
        }
        if (!note.isEmpty()) g.drawString(font, UiStyle.clip(font, note, w - 20), left + 10, top + h - 14, PaScreen.BRAND, false);
    }

    // ---- put in: its pack, then yours

    private void put(GuiGraphics g, int mx, int my) {
        Map<String, Integer> its = new LinkedHashMap<>(), yours = new LinkedHashMap<>();
        var reg = Minecraft.getInstance().level.registryAccess();
        for (Tag t : PaClient.state.getList("slots", Tag.TAG_COMPOUND)) {
            ItemStack s = ItemStack.parseOptional(reg, ((CompoundTag) t).getCompound("s"));
            if (!s.isEmpty()) its.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        for (ItemStack s : Minecraft.getInstance().player.getInventory().items)
            if (!s.isEmpty()) yours.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        int colW = (w - 30) / 2;
        int y0 = top + 30;
        list(g, "Its pack", its, left + 10, y0, colW, mx, my, "pack");
        list(g, "Yours (handed over)", yours, left + 20 + colW, y0, colW, mx, my, "me");
        g.drawString(font, UiStyle.clip(font, "It walks over and puts them in at a player's pace.", w - 20), left + 10, top + h - 26, UiStyle.TEXT_FAINT, false);
    }

    private void list(GuiGraphics g, String head, Map<String, Integer> m, int x, int y, int cw, int mx, int my, String src) {
        UiStyle.heading(g, font, head, x, y, cw);
        int ry = y + 13, rows = (top + h - 32 - ry) / 18;
        int i = 0;
        if (m.isEmpty()) g.drawString(font, "Nothing.", x + 2, ry + 4, UiStyle.TEXT_FAINT, false);
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (i++ >= rows) { g.drawString(font, "and " + (m.size() - rows) + " more...", x + 2, ry + 4, UiStyle.TEXT_FAINT, false); break; }
            String id = e.getKey();
            int n = e.getValue();
            icon(g, id, x, ry, n);
            int max = new ItemStack(item(id)).getMaxStackSize();
            g.drawString(font, UiStyle.clip(font, nameOf(id), cw - 80), x + 19, ry + 5, UiStyle.TEXT, false);
            int bx = x + cw - 58;
            if (n > max) button(g, String.valueOf(max), bx, ry + 1, 28, 15, mx, my, true, false, "Put in one stack (" + max + ")", () -> sendPut(id, max, src));
            button(g, "All", bx + 30, ry + 1, 28, 15, mx, my, true, true, "Put in all " + n, () -> sendPut(id, n, src));
            ry += 18;
        }
    }

    private void sendPut(String id, int n, String src) {
        CompoundTag t = PaLogiClient.pos(at);
        t.putString("item", "item:" + id);
        t.putInt("n", n);
        t.putString("src", src);
        PaLogiClient.send("put", t);
        note = (src.equals("me") ? "Handed over " : "Putting in ") + n + " " + nameOf(id) + ".";
    }

    // ---- take out: to you or to its pack

    private void take(GuiGraphics g, int mx, int my) {
        CompoundTag p = PaLogiClient.peek;
        int y = top + 30;
        if (p == null || p.getInt("x") != at.getX() || p.getInt("y") != at.getY() || p.getInt("z") != at.getZ()) {
            g.drawString(font, "Looking inside...", left + 10, y, UiStyle.TEXT_FAINT, false);
            return;
        }
        if (p.contains("why")) { UiStyle.text(g, font, p.getString("why"), left + 10, y, w - 20, UiStyle.DANGER); return; }
        ListTag items = p.getList("items", Tag.TAG_COMPOUND);
        if (items.isEmpty()) { g.drawString(font, "It's empty.", left + 10, y, UiStyle.TEXT_FAINT, false); return; }
        UiStyle.heading(g, font, "In " + p.getString("name"), left + 10, y, w - 20);
        int ry = y + 13, rows = (top + h - 30 - ry) / 18;
        for (int i = 0; i < items.size() && i < rows; i++) {
            CompoundTag it = items.getCompound(i);
            String id = it.getString("i");
            int n = it.getInt("n");
            icon(g, id, left + 10, ry, n);
            g.drawString(font, UiStyle.clip(font, nameOf(id) + "  x" + n, w - 200), left + 29, ry + 5, UiStyle.TEXT, false);
            int max = new ItemStack(item(id)).getMaxStackSize();
            int bx = left + w - 170;
            button(g, "Stack to me", bx, ry + 1, 70, 15, mx, my, true, false, "Bring you one stack", () -> sendTake(id, Math.min(n, max), "me"));
            button(g, "All to me", bx + 72, ry + 1, 46, 15, mx, my, true, true, "Bring you all " + n, () -> sendTake(id, n, "me"));
            button(g, "Its pack", bx + 120, ry + 1, 40, 15, mx, my, true, false, "Into its own pack", () -> sendTake(id, n, "pack"));
            ry += 18;
        }
    }

    private void sendTake(String id, int n, String dest) {
        CompoundTag t = PaLogiClient.pos(at);
        t.putString("item", "item:" + id);
        t.putInt("n", n);
        t.putString("dest", dest);
        PaLogiClient.send("take", t);
        note = (dest.equals("me") ? "Bringing you " : "Taking ") + n + " " + nameOf(id) + ".";
    }

    // ---- fill with / keep stocked with

    private void fill(GuiGraphics g, int mx, int my) {
        int y = top + 52;
        g.drawString(font, "How many", left + 10, y + 3, muted(), false);
        int[] ns = mode == Mode.FILL ? new int[]{16, 32, 64, 128, 256, 0} : new int[]{8, 16, 32, 64, 128};
        int x = left + 60;
        for (int n : ns) {
            String l = n == 0 ? "Till full" : String.valueOf(n);
            final int nn = n;
            button(g, l, x, y, bw(l), 15, mx, my, true, qty == n, n == 0 ? "Until the chest is full" : n + " of them", () -> qty = nn);
            x += bw(l) + 3;
        }
        // suggestions: what's in the chest, then its pack
        List<String> ids = new ArrayList<>();
        CompoundTag p = PaLogiClient.peek;
        if (p != null && p.getInt("x") == at.getX() && p.getInt("z") == at.getZ())
            for (Tag t : p.getList("items", Tag.TAG_COMPOUND)) { String id = ((CompoundTag) t).getString("i"); if (!ids.contains(id)) ids.add(id); }
        var reg = Minecraft.getInstance().level.registryAccess();
        for (Tag t : PaClient.state.getList("slots", Tag.TAG_COMPOUND)) {
            ItemStack s = ItemStack.parseOptional(reg, ((CompoundTag) t).getCompound("s"));
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (!s.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        int sy = y + 22;
        g.drawString(font, "Or pick one:", left + 10, sy, muted(), false);
        sy += 12;
        int sx = left + 10;
        for (String id : ids) {
            if (sx + 20 > left + w - 10) { sx = left + 10; sy += 20; }
            if (sy > top + h - 50) break;
            final String fid = id;
            icon(g, id, sx, sy, 1);
            zone(sx, sy, 18, 18, () -> box.setValue("item:" + fid), nameOf(id));
            if (in(mx, my, sx, sy, 18, 18)) tipNow = nameOf(id);
            sx += 20;
        }
        String what = box.getValue().trim();
        String go = mode == Mode.FILL ? "Fill it" : "Keep it stocked";
        button(g, go, left + w - bw(go) - 10, top + h - 34, bw(go), 16, mx, my, !what.isEmpty(), true,
            mode == Mode.FILL ? "It gathers, crafts, smelts or fetches until there are that many in the chest" : "It checks every few minutes and tops it up", this::sendFill);
    }

    private void sendFill() {
        String what = box.getValue().trim();
        if (what.isEmpty()) return;
        CompoundTag t = PaLogiClient.pos(at);
        t.putString("item", what.toLowerCase(java.util.Locale.ROOT));
        t.putInt("n", qty);
        PaLogiClient.send(mode == Mode.FILL ? "fill" : "stock", t);
        onClose();
    }

    // ---- name

    private void name(GuiGraphics g, int mx, int my) {
        g.drawString(font, UiStyle.clip(font, "Then say \"put the iron in the " + (box.getValue().isEmpty() ? "iron" : box.getValue()) + " chest\".", w - 20), left + 10, top + 54, muted(), false);
        g.drawString(font, UiStyle.clip(font, "\"storage\" makes it your main storage.", w - 20), left + 10, top + 66, UiStyle.TEXT_FAINT, false);
        button(g, "Save", left + w - 50, top + h - 34, 40, 16, mx, my, !box.getValue().isBlank(), true, "Name it", this::sendName);
    }

    private void sendName() {
        if (box.getValue().isBlank()) return;
        CompoundTag t = PaLogiClient.pos(at);
        t.putString("name", box.getValue().trim());
        PaLogiClient.send("rename", t);
        onClose();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && box != null && box.isFocused()) {
            if (mode == Mode.NAME) sendName(); else sendFill();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
