package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.client.UiStyle.State;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * [shops-homes] The plain desk's panel (the bank, the tavern, the smithy, the tailor's): the sections the server sends,
 * each a heading, a few lines of reading and rows of things to do, a row an icon, what it is, what it costs, an amount
 * to choose (− and +, shift for ten) where it takes one, and its button, rested with the reason when it cannot be done.
 * Scrolls when it does not fit; Up and Down pick a row, Enter presses it.
 */
final class DeskPanel implements ServiceScreen.Panel {

    private static final int ROW_H = 24, BUTTON_W = 64, STEP_W = 64;

    private record Row(String action, String button, String label, String sub, ItemStack icon, boolean enabled, String why,
                       CompoundTag args, @Nullable int[] amount) {}

    private record Section(String title, List<String> lines, List<Row> rows) {}

    private final ServiceScreen screen;
    private final List<Section> sections = new ArrayList<>();
    /** The amount chosen on each row, by its action and arguments, kept across the server's updates. */
    private final Map<String, Integer> chosen = new HashMap<>();
    private final Scroll scroll = new Scroll();
    private int x, y, w, h, selected = -1;
    @Nullable private String tip;

    DeskPanel(ServiceScreen screen) {
        this.screen = screen;
    }

    @Override
    public void setData(CompoundTag data) {
        sections.clear();
        ListTag ss = data.getCompound("body").getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < ss.size(); i++) {
            CompoundTag s = ss.getCompound(i);
            List<String> lines = new ArrayList<>();
            ListTag ls = s.getList("lines", Tag.TAG_STRING);
            for (int k = 0; k < ls.size(); k++) lines.add(ls.getString(k));
            List<Row> rows = new ArrayList<>();
            ListTag rs = s.getList("rows", Tag.TAG_COMPOUND);
            for (int k = 0; k < rs.size(); k++) {
                CompoundTag r = rs.getCompound(k);
                int[] amount = null;
                if (r.contains("amount")) {
                    CompoundTag a = r.getCompound("amount");
                    amount = new int[]{ a.getInt("min"), a.getInt("max"), a.getInt("start"), a.getInt("step") };
                }
                rows.add(new Row(r.getString("action"), r.getString("button"), r.getString("label"), r.getString("sub"),
                    ServiceScreen.stack(r, "icon"), r.getBoolean("enabled"), r.getString("why"), r.getCompound("args"), amount));
            }
            sections.add(new Section(s.getString("title"), lines, rows));
        }
    }

    @Override
    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    private Font font() {
        return Minecraft.getInstance().font;
    }

    private String key(Row r) {
        return r.action() + r.args();
    }

    private int amountOf(Row r) {
        if (r.amount() == null) return 0;
        int v = chosen.getOrDefault(key(r), r.amount()[2]);
        return Math.max(r.amount()[0], Math.min(r.amount()[1], v));
    }

    /** The rows in order, for the keys. */
    private List<Row> allRows() {
        List<Row> out = new ArrayList<>();
        for (Section s : sections) out.addAll(s.rows());
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        tip = null;
        Font font = font();
        well(g, x, y, w, h, PAGE);
        int inner = w - 2 * S2 - 4;
        int content = measure(font, inner);
        scroll.fit(content, h - 2 * S1);
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        int cy = y + S1 - scroll.offset();
        int index = 0;
        for (Section s : sections) {
            if (!s.title().isEmpty()) {
                heading(g, font, s.title(), x + S2, cy + 2, inner);
                cy += LINE + S1;
            }
            for (String line : s.lines()) {
                for (FormattedCharSequence seq : TextCache.splitPlain(font, line, inner)) {
                    g.drawString(font, seq, x + S2, cy, TEXT_MUTED, false);
                    cy += LINE;
                }
                cy += 2;
            }
            for (Row r : s.rows()) {
                drawRow(g, font, r, x + S2, cy, inner, mouseX, mouseY, index == selected);
                cy += ROW_H + 2;
                index++;
            }
            cy += S1;
        }
        g.disableScissor();
        scrollBar(g, x + w - 5, y + 2, h - 4, scroll.offset(), scroll.max(), h);
        if (sections.isEmpty()) text(g, font, "Nothing to be done here just now.", x + S2, y + S2, inner, TEXT_FAINT);
    }

    private int measure(Font font, int inner) {
        int n = 0;
        for (Section s : sections) {
            if (!s.title().isEmpty()) n += LINE + S1;
            for (String line : s.lines()) n += TextCache.splitPlain(font, line, inner).size() * LINE + 2;
            n += s.rows().size() * (ROW_H + 2) + S1;
        }
        return n;
    }

    private void drawRow(GuiGraphics g, Font font, Row r, int rx, int ry, int rw, int mouseX, int mouseY, boolean sel) {
        boolean visible = ry + ROW_H > y && ry < y + h;
        if (!visible) return;
        boolean over = ServiceScreen.in(mouseX, mouseY, rx, ry, rw, ROW_H) && mouseY >= y && mouseY < y + h;
        tile(g, rx, ry, rw, ROW_H, sel ? State.SELECTED : State.NORMAL);
        if (!r.icon().isEmpty()) g.renderItem(r.icon(), rx + S1, ry + (ROW_H - ICON) / 2);
        int tx = rx + S1 + ICON + S1;
        int right = rx + rw - S1 - BUTTON_W;
        if (r.amount() != null) right -= STEP_W + S1;
        int room = right - tx - S1;
        text(g, font, r.label(), tx, ry + 3, room, r.enabled() ? TEXT : TEXT_DISABLED);
        String sub = r.enabled() || r.why().isEmpty() ? r.sub() : r.why();
        boolean clipped = text(g, font, sub, tx, ry + 13, room, r.enabled() ? TEXT_FAINT : DANGER);
        if (over && clipped) tip = sub;
        // The amount: − n +
        if (r.amount() != null) {
            int sx = right + S1;
            int n = amountOf(r);
            boolean canStep = r.enabled() && !screen.waiting();
            boolean overMinus = ServiceScreen.in(mouseX, mouseY, sx, ry + 4, 14, 16);
            boolean overPlus = ServiceScreen.in(mouseX, mouseY, sx + STEP_W - 14, ry + 4, 14, 16);
            button(g, font, "-", sx, ry + 4, 14, 16, State.of(canStep && n > r.amount()[0], false, overMinus));
            String num = String.valueOf(n);
            g.fill(sx + 15, ry + 4, sx + STEP_W - 15, ry + 20, WELL_LIGHT);
            g.drawString(font, num, sx + (STEP_W - font.width(num)) / 2, ry + 8, TEXT, false);
            button(g, font, "+", sx + STEP_W - 14, ry + 4, 14, 16, State.of(canStep && n < r.amount()[1], false, overPlus));
            if (overMinus || overPlus) tip = "Shift for ten at a time";
        }
        int bx = rx + rw - S1 - BUTTON_W;
        boolean enabled = r.enabled() && (screen.open() || r.action().equals("use")) && !screen.waiting();
        boolean overButton = ServiceScreen.in(mouseX, mouseY, bx, ry + 3, BUTTON_W, ROW_H - 6);
        boolean clippedB = button(g, font, r.button(), bx, ry + 3, BUTTON_W, ROW_H - 6, State.of(enabled, false, overButton));
        if (overButton) {
            if (!r.enabled() && !r.why().isEmpty()) tip = r.why();
            else if (!screen.open() && !r.action().equals("use")) tip = screen.status();
            else if (clippedB) tip = r.button();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!ServiceScreen.in(mouseX, mouseY, x, y, w, h)) return false;
        Font font = font();
        int inner = w - 2 * S2 - 4;
        int cy = y + S1 - scroll.offset();
        int index = 0;
        boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        for (Section s : sections) {
            if (!s.title().isEmpty()) cy += LINE + S1;
            for (String line : s.lines()) cy += TextCache.splitPlain(font, line, inner).size() * LINE + 2;
            for (Row r : s.rows()) {
                int rx = x + S2, rw = inner;
                if (ServiceScreen.in(mouseX, mouseY, rx, cy, rw, ROW_H)) {
                    selected = index;
                    int right = rx + rw - S1 - BUTTON_W;
                    if (r.amount() != null) {
                        right -= STEP_W + S1;
                        int sx = right + S1;
                        int step = r.amount()[3] * (shift ? 10 : 1);
                        if (ServiceScreen.in(mouseX, mouseY, sx, cy + 4, 14, 16)) { nudge(r, -step); return true; }
                        if (ServiceScreen.in(mouseX, mouseY, sx + STEP_W - 14, cy + 4, 14, 16)) { nudge(r, step); return true; }
                    }
                    int bx = rx + rw - S1 - BUTTON_W;
                    if (ServiceScreen.in(mouseX, mouseY, bx, cy + 3, BUTTON_W, ROW_H - 6)) press(r);
                    return true;
                }
                cy += ROW_H + 2;
                index++;
            }
            cy += S1;
        }
        return true;
    }

    private void nudge(Row r, int by) {
        if (!r.enabled()) return;
        chosen.put(key(r), Math.max(r.amount()[0], Math.min(r.amount()[1], amountOf(r) + by)));
        click();
    }

    private void press(Row r) {
        boolean enabled = r.enabled() && (screen.open() || r.action().equals("use")) && !screen.waiting();
        if (!enabled) return;
        click();
        CompoundTag args = r.args().copy();
        if (r.amount() != null) args.putInt("amount", amountOf(r));
        screen.send(r.action(), args);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dy) {
        if (!ServiceScreen.in(mouseX, mouseY, x, y, w, h)) return false;
        scroll.wheel(dy, ROW_H);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int mods) {
        List<Row> rows = allRows();
        if (rows.isEmpty()) return false;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        switch (key) {
            case GLFW.GLFW_KEY_DOWN -> { selected = Math.min(rows.size() - 1, selected + 1); return true; }
            case GLFW.GLFW_KEY_UP -> { selected = Math.max(0, selected - 1); return true; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (selected >= 0 && selected < rows.size()) press(rows.get(selected));
                return true;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> {
                if (selected >= 0 && rows.get(selected).amount() != null) nudge(rows.get(selected), -rows.get(selected).amount()[3] * (shift ? 10 : 1));
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
                if (selected >= 0 && rows.get(selected).amount() != null) nudge(rows.get(selected), rows.get(selected).amount()[3] * (shift ? 10 : 1));
                return true;
            }
            default -> { return false; }
        }
    }

    @Nullable
    @Override
    public String tooltip() {
        return tip;
    }
}
