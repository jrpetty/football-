package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.client.UiStyle.State;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * [dining] The order screen's panel (entity/DiningDesk), in the service screen's shell: the menu on the left by course
 * (Drinks, Starters, Mains, Desserts as tabs), a card for each dish (its picture, name and price, what is in it, the
 * hunger it gives as pips, and its badges: the chef's special, the town's own dish, sold out); the bill on the right:
 * what you have chosen with − and +, how it is to be served (at your table, to take away, brought to you), whether you
 * pay now, and the one primary button, Order. Once an order is in, the bill shows each dish's progress (in the kitchen,
 * on its way, on your table) and, when it is time, Pay with a tip. Click a card to add one, right-click to take one off;
 * Left and Right change course, Up and Down pick a dish, Enter adds it, Delete takes it off. Everything parsed once per
 * update from the server: nothing is allocated while it draws.
 */
final class DiningPanel implements ServiceScreen.Panel {

    private static final int CARD_H = 40, TAB_H = 16, BILL_MIN = 132, BILL_MAX = 176, LINE_H = 12;
    private static final String[] MODES = { "table", "takeaway", "delivery" };
    private static final String[] MODE_LABELS = { "At table", "Take away", "Bring it" };
    private static final int[] TIPS = { 0, 1, 2, 3 };
    private static final String[] TIP_LABELS = { "No tip", "+1", "+2", "+3" };
    private static final String[] TIP_HOVERS = { "No tip", "A tip of 1 for the waiter (it lifts their day).", "A tip of 2 for the waiter (it lifts their day).",
        "A tip of 3 for the waiter (it lifts their day)." };

    private record Dish(String id, String name, String priceText, int price, String about, int hunger, boolean soldOut, boolean special,
                        boolean local, int count, String countText, ItemStack icon, String hoverText) {}

    private record Course(String title, List<Dish> dishes, String tabText) {}

    private record BasketLine(String id, String text, String priceText, boolean soldOut) {}

    private record OrderLine(String name, String step, int state, String priceText, boolean struck) {}

    private final ServiceScreen screen;
    private final List<Course> courses = new ArrayList<>();
    private final List<BasketLine> basket = new ArrayList<>();
    private final List<OrderLine> order = new ArrayList<>();
    private final Scroll scroll = new Scroll();
    private int course = -1, selected = -1, tip = 0;
    private String orderLabel = "Order";
    private String place = "", context = "", empty = "", mode = "table", totalText = "", orderTotals = "", payText = "";
    private int table, total, due;
    private boolean payNow, hasOrder, paid, finished;
    private int x, y, w, h, billW, cols;
    @Nullable private String hover;

    DiningPanel(ServiceScreen screen) {
        this.screen = screen;
    }

    // ------------------------------------------------------------------ the server's data

    @Override
    public void setData(CompoundTag data) {
        CompoundTag b = data.getCompound("body");
        empty = b.getString("empty");
        place = b.getString("place");
        table = b.getInt("table");
        context = b.getString("meal") + (place.isEmpty() ? "" : " at " + place.replace("The ", "the ")) + (table > 0 ? "  ·  Table " + table : "");
        courses.clear();
        ListTag cs = b.getList("courses", Tag.TAG_COMPOUND);
        int firstWithFood = -1;
        for (int i = 0; i < cs.size(); i++) {
            CompoundTag c = cs.getCompound(i);
            List<Dish> dishes = new ArrayList<>();
            ListTag ds = c.getList("dishes", Tag.TAG_COMPOUND);
            int open = 0;
            for (int k = 0; k < ds.size(); k++) {
                CompoundTag d = ds.getCompound(k);
                int n = d.getInt("count");
                boolean sold = d.getBoolean("soldOut");
                if (!sold) open++;
                dishes.add(new Dish(d.getString("id"), d.getString("name"), coins(d.getInt("price")), d.getInt("price"), d.getString("about"),
                    d.getInt("hunger"), sold, d.getBoolean("special"), d.getBoolean("local"), n, n > 0 ? "x" + n : "", ServiceScreen.stack(d, "icon"),
                    sold ? d.getString("name") + ": sold out today." : d.getString("name") + ". " + d.getString("about") + ". " + d.getInt("hunger")
                        + " hunger. Click to add, right-click to take one off."));
            }
            courses.add(new Course(c.getString("title"), dishes, c.getString("title") + " " + open));
            if (firstWithFood < 0 && open > 0 && i == 2) firstWithFood = i;                 // the mains, when there are any
        }
        if (course < 0 || course >= courses.size()) course = firstWithFood >= 0 ? firstWithFood : 0;
        basket.clear();
        ListTag bl = b.getList("basket", Tag.TAG_COMPOUND);
        for (int i = 0; i < bl.size(); i++) {
            CompoundTag l = bl.getCompound(i);
            basket.add(new BasketLine(l.getString("id"), l.getInt("count") + " x " + l.getString("name"), coins(l.getInt("price")), l.getBoolean("soldOut")));
        }
        total = b.getInt("total");
        totalText = coins(total);
        orderLabel = total <= 0 ? "Order" : "Order  ·  " + totalText;
        mode = b.getString("mode");
        payNow = b.getBoolean("payNow");
        order.clear();
        hasOrder = b.contains("order");
        if (hasOrder) {
            CompoundTag o = b.getCompound("order");
            ListTag ol = o.getList("lines", Tag.TAG_COMPOUND);
            for (int i = 0; i < ol.size(); i++) {
                CompoundTag l = ol.getCompound(i);
                order.add(new OrderLine(l.getString("name"), l.getString("step"), l.getInt("state"), coins(l.getInt("price")), l.getBoolean("struck")));
            }
            due = o.getInt("due");
            paid = o.getBoolean("paid");
            finished = o.getBoolean("finished");
            orderTotals = "Total " + coins(o.getInt("total")) + (o.getInt("paidCoins") > 0 ? "  ·  paid " + coins(o.getInt("paidCoins")) : "");
        }
        payText = due > 0 ? "Pay " + coins(due + tip) : tip > 0 ? "Leave " + coins(tip) : "All paid";
    }

    private static String coins(int n) {
        return UiStyle.number(n) + (n == 1 ? " coin" : " coins");
    }

    @Override
    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        billW = Math.max(BILL_MIN, Math.min(BILL_MAX, w * 36 / 100));
        cols = w - billW - S2 >= 250 ? 2 : 1;
    }

    private Font font() {
        return Minecraft.getInstance().font;
    }

    private int menuW() {
        return w - billW - S2;
    }

    @Nullable
    private Course current() {
        return course >= 0 && course < courses.size() ? courses.get(course) : null;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        hover = null;
        Font font = font();
        if (!empty.isEmpty()) {
            well(g, x, y, w, h, PAGE);
            UiStyle.empty(g, font, empty, "Try a café, a tavern or a restaurant.", x, y, w, h);
            return;
        }
        drawTabs(g, font, mouseX, mouseY);
        drawCards(g, font, mouseX, mouseY);
        drawBill(g, font, mouseX, mouseY);
    }

    private void drawTabs(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int tx = x;
        int tw = Math.max(40, (menuW() - S1 * (courses.size() - 1)) / Math.max(1, courses.size()));
        for (int i = 0; i < courses.size(); i++) {
            boolean over = ServiceScreen.in(mouseX, mouseY, tx, y, tw, TAB_H);
            topTab(g, tx, y, tw, TAB_H, State.of(true, i == course, over));
            String label = clip(font, courses.get(i).title(), tw - S2);
            g.drawString(font, label, tx + (tw - font.width(label)) / 2, y + 4, i == course ? TEXT : TEXT_MUTED, false);
            if (over) hover = courses.get(i).tabText() + " to be had  (Left / Right)";
            tx += tw + S1;
        }
    }

    private int cardsTop() {
        return y + TAB_H + S1 + LINE;
    }

    private void drawCards(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int mw = menuW();
        text(g, font, context, x + 2, y + TAB_H + 3, mw - 4, TEXT_FAINT);
        int top = cardsTop(), bottom = y + h;
        well(g, x, top, mw, bottom - top, PAGE);
        Course c = current();
        if (c == null || c.dishes().isEmpty()) {
            UiStyle.empty(g, font, "Nothing on this course today", "Try another tab.", x, top, mw, bottom - top);
            return;
        }
        int cw = (mw - 2 * S1 - (cols - 1) * S1 - 4) / cols;
        int rows = (c.dishes().size() + cols - 1) / cols;
        scroll.fit(rows * (CARD_H + S1) + S1, bottom - top);
        g.enableScissor(x + 1, top + 1, x + mw - 1, bottom - 1);
        for (int i = 0; i < c.dishes().size(); i++) {
            int cx = x + S1 + (i % cols) * (cw + S1), cy = top + S1 + (i / cols) * (CARD_H + S1) - scroll.offset();
            if (cy + CARD_H < top || cy > bottom) continue;
            drawCard(g, font, c.dishes().get(i), cx, cy, cw, i == selected, mouseX, mouseY, mouseY >= top && mouseY < bottom);
        }
        g.disableScissor();
        scrollBar(g, x + mw - 5, top + 2, bottom - top - 4, scroll.offset(), scroll.max(), bottom - top);
    }

    private void drawCard(GuiGraphics g, Font font, Dish d, int cx, int cy, int cw, boolean sel, int mouseX, int mouseY, boolean inView) {
        boolean over = inView && ServiceScreen.in(mouseX, mouseY, cx, cy, cw, CARD_H);
        int spine = d.special() ? HIGHLIGHT_SPINE : d.local() ? INFO_FILL : 0;
        card(g, cx, cy, cw, CARD_H, spine);
        if (d.soldOut()) g.fill(cx + 1, cy + 1, cx + cw - 1, cy + CARD_H - 1, 0x60B7B7B7);
        else if (over || sel) g.fill(cx + 1, cy + 1, cx + cw - 1, cy + CARD_H - 1, sel ? 0x40CFE0BC : 0x30FFFFFF);
        if (over || sel) g.renderOutline(cx, cy, cw, CARD_H, sel ? ACCENT : EDGE);
        // The picture, in a little well, with how many are in the bill on its corner.
        int ix = cx + S1 + 2, iy = cy + (CARD_H - 20) / 2;
        well(g, ix, iy, 20, 20, WELL_LIGHT);
        g.renderItem(d.icon(), ix + 2, iy + 2);
        if (d.count() > 0) {
            int bw = font.width(d.countText()) + 4;
            g.fill(ix + 20 - bw + 2, iy - 2, ix + 22, iy + 8, ACCENT_FILL);
            g.drawString(font, d.countText(), ix + 22 - bw + 2, iy - 1, TEXT_ON_DARK, false);
        }
        int tx = ix + 20 + S2, right = cx + cw - S1 - 2;
        int priceW = font.width(d.priceText());
        g.drawString(font, d.priceText(), right - priceW, cy + 4, d.soldOut() ? TEXT_DISABLED : ACCENT, false);
        boolean clippedName = text(g, font, d.name(), tx, cy + 4, right - priceW - S1 - tx, d.soldOut() ? TEXT_DISABLED : TEXT);
        boolean clippedAbout = text(g, font, d.about(), tx, cy + 15, right - tx, TEXT_FAINT);
        // The hunger it gives, as pips; then its badges.
        int py = cy + 28, px = tx;
        for (int i = 0; i < Math.min(10, (d.hunger() + 1) / 2); i++) {
            g.fill(px, py + 2, px + 4, py + 6, d.soldOut() ? TEXT_DISABLED : WARNING_FILL);
            px += 5;
        }
        int bx = Math.max(px + S1, tx + 54);
        if (d.soldOut()) bx += badge(g, font, "Sold out", bx, py - 1, DANGER_FILL) + 2;
        else {
            if (d.special()) bx += badge(g, font, "Special", bx, py - 1, HIGHLIGHT_SPINE) + 2;
            if (d.local() && bx < right - 30) badge(g, font, "Local", bx, py - 1, INFO_FILL);
        }
        if (over) hover = d.hoverText();
    }

    private void drawBill(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int bx = x + w - billW, by = y, bw = billW, bh = h;
        card(g, bx, by, bw, bh, 0);
        int ix = bx + S2, iw = bw - 2 * S2, cy = by + S1 + 1;
        if (hasOrder && basket.isEmpty()) {
            heading(g, font, "Your order", ix, cy, iw);
            cy += LINE + S1;
            for (OrderLine l : order) {
                if (cy + 2 * LINE_H > by + bh - 52) break;
                int dot = l.struck() ? DANGER_FILL : l.state() >= 4 ? SUCCESS_FILL : l.state() >= 2 ? INFO_FILL : l.state() == 1 ? WARNING_FILL : TEXT_FAINT;
                g.fill(ix, cy + 3, ix + 4, cy + 7, dot);
                text(g, font, l.name(), ix + 7, cy, iw - 7 - font.width(l.priceText()) - S1, l.struck() ? TEXT_DISABLED : TEXT);
                g.drawString(font, l.priceText(), ix + iw - font.width(l.priceText()), cy, l.struck() ? TEXT_DISABLED : TEXT_MUTED, false);
                if (l.struck()) g.fill(ix + 7, cy + 4, ix + iw, cy + 5, TEXT_DISABLED);
                cy += LINE_H - 2;
                text(g, font, l.step(), ix + 7, cy, iw - 7, l.struck() ? DANGER : TEXT_FAINT);
                cy += LINE_H;
            }
            int fy = by + bh - 50;
            g.fill(ix, fy - 3, ix + iw, fy - 2, EDGE_SOFT);
            text(g, font, orderTotals, ix, fy, iw, TEXT_MUTED);
            // The tip: four chips.
            int chipW = (iw - 3 * 2) / 4;
            for (int i = 0; i < TIPS.length; i++) {
                int chx = ix + i * (chipW + 2);
                boolean over = ServiceScreen.in(mouseX, mouseY, chx, fy + 12, chipW, 14);
                button(g, font, TIP_LABELS[i], chx, fy + 12, chipW, 14, State.of(true, tip == TIPS[i], over));
                if (over) hover = TIP_HOVERS[i];
            }
            boolean canPay = due > 0 || tip > 0;
            boolean over = ServiceScreen.in(mouseX, mouseY, ix, fy + 30, iw, 16);
            primary(g, font, paid && due == 0 && tip == 0 ? (finished ? "All paid. Thank you!" : "Paid") : payText, ix, fy + 30, iw, 16,
                canPay && !screen.waiting(), over);
            if (over && !canPay) hover = paid ? "Paid in full." : "Nothing to pay yet.";
            return;
        }
        heading(g, font, hasOrder ? "Adding to your order" : "Your bill", ix, cy, iw);
        cy += LINE + S1;
        if (basket.isEmpty()) {
            UiStyle.empty(g, font, "Nothing chosen yet", "Click a dish to add it.", bx, cy, bw, by + bh - 76 - cy);
        }
        for (int i = 0; i < basket.size(); i++) {
            BasketLine l = basket.get(i);
            if (cy + LINE_H > by + bh - 78) {
                text(g, font, "...", ix, cy, iw, TEXT_FAINT);
                break;
            }
            int mx = ix + iw - 22;
            text(g, font, l.text(), ix, cy + 1, mx - ix - font.width(l.priceText()) - S1, l.soldOut() ? DANGER : TEXT);
            g.drawString(font, l.priceText(), mx - S1 - font.width(l.priceText()), cy + 1, TEXT_MUTED, false);
            boolean overMinus = ServiceScreen.in(mouseX, mouseY, mx, cy, 10, 10), overPlus = ServiceScreen.in(mouseX, mouseY, mx + 12, cy, 10, 10);
            button(g, font, "-", mx, cy, 10, 10, State.of(!screen.waiting(), false, overMinus));
            button(g, font, "+", mx + 12, cy, 10, 10, State.of(!screen.waiting() && !l.soldOut(), false, overPlus));
            if (overMinus) hover = "One fewer";
            if (overPlus) hover = l.soldOut() ? "Sold out" : "One more";
            cy += LINE_H;
        }
        int fy = by + bh - 74;
        g.fill(ix, fy - 3, ix + iw, fy - 2, EDGE_SOFT);
        g.drawString(font, "Total", ix, fy, TEXT, false);
        g.drawString(font, totalText, ix + iw - font.width(totalText), fy, TEXT, false);
        // How it is served: three segments.
        int segW = (iw - 4) / 3;
        for (int i = 0; i < MODES.length; i++) {
            int sx = ix + i * (segW + 2);
            boolean enabled = !MODES[i].equals("table") || table > 0;
            boolean over = ServiceScreen.in(mouseX, mouseY, sx, fy + 12, segW, 14);
            button(g, font, MODE_LABELS[i], sx, fy + 12, segW, 14, State.of(enabled, mode.equals(MODES[i]), over));
            if (over) hover = !enabled ? "Sit at a table (right-click its chair) to be served there."
                : i == 0 ? "Served at your table; pay now or from the bill." : i == 1 ? "Handed to you at the counter, paid now."
                : "A runner brings it to you anywhere in town, paid now.";
        }
        // Pay now (at a table only: the others are always paid now).
        boolean table0 = mode.equals("table");
        boolean overPay = ServiceScreen.in(mouseX, mouseY, ix, fy + 30, iw, 12);
        well(g, ix, fy + 31, 9, 9, WELL_LIGHT);
        if (payNow || !table0) g.fill(ix + 2, fy + 33, ix + 7, fy + 38, table0 ? ACCENT_FILL : TEXT_DISABLED);
        text(g, font, table0 ? "Pay now (else from the bill)" : "Paid when you order", ix + 13, fy + 32, iw - 13, table0 ? TEXT_MUTED : TEXT_FAINT);
        if (overPay && table0) hover = "Pay upfront, or pay the bill when you have eaten.";
        boolean can = !basket.isEmpty() && screen.open() && !screen.waiting();
        boolean overOrder = ServiceScreen.in(mouseX, mouseY, ix, fy + 46, iw, 18);
        primary(g, font, orderLabel, ix, fy + 46, iw, 18, can, overOrder);
        if (overOrder && !can) hover = basket.isEmpty() ? "Choose something first." : !screen.open() ? screen.status() : "At the counter...";
    }

    /** The one primary button: the accent fill, light text. */
    private static void primary(GuiGraphics g, Font font, String label, int bx, int by, int bw, int bh, boolean enabled, boolean over) {
        if (!enabled) {
            button(g, font, label, bx, by, bw, bh, State.DISABLED);
            return;
        }
        g.fill(bx, by, bx + bw, by + bh, EDGE);
        g.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, over ? Ui.lighten(ACCENT_FILL, 0.15F) : ACCENT_FILL);
        g.fill(bx + 1, by + 1, bx + bw - 1, by + 2, Ui.lighten(ACCENT_FILL, 0.4F));
        String shown = clip(font, label, bw - S2);
        g.drawString(font, shown, bx + (bw - font.width(shown)) / 2, by + (bh - GLYPH) / 2 + 1, TEXT_ON_DARK, true);
    }

    @Nullable
    @Override
    public String tooltip() {
        return hover;
    }

    // ------------------------------------------------------------------ the mouse and the keys

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!empty.isEmpty()) return false;
        Font font = font();
        // The tabs.
        int tx = x, tw = Math.max(40, (menuW() - S1 * (courses.size() - 1)) / Math.max(1, courses.size()));
        for (int i = 0; i < courses.size(); i++) {
            if (ServiceScreen.in(mx, my, tx, y, tw, TAB_H)) {
                pickCourse(i);
                return true;
            }
            tx += tw + S1;
        }
        // The cards: click adds one, right-click takes one off.
        Course c = current();
        int top = cardsTop(), bottom = y + h, mw = menuW();
        if (c != null && ServiceScreen.in(mx, my, x, top, mw, bottom - top)) {
            int cw = (mw - 2 * S1 - (cols - 1) * S1 - 4) / cols;
            for (int i = 0; i < c.dishes().size(); i++) {
                int cx = x + S1 + (i % cols) * (cw + S1), cy = top + S1 + (i / cols) * (CARD_H + S1) - scroll.offset();
                if (!ServiceScreen.in(mx, my, cx, cy, cw, CARD_H)) continue;
                selected = i;
                Dish d = c.dishes().get(i);
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    if (d.count() > 0) send("remove", d.id());
                } else if (!d.soldOut()) {
                    send("add", d.id());
                }
                return true;
            }
            return true;
        }
        int bx = x + w - billW, ix = bx + S2, iw = billW - 2 * S2;
        if (!ServiceScreen.in(mx, my, bx, y, billW, h)) return false;
        if (hasOrder && basket.isEmpty()) {
            int fy = y + h - 50, chipW = (iw - 3 * 2) / 4;
            for (int i = 0; i < TIPS.length; i++) {
                if (ServiceScreen.in(mx, my, ix + i * (chipW + 2), fy + 12, chipW, 14)) {
                    tip = TIPS[i];
                    payText = due > 0 ? "Pay " + coins(due + tip) : tip > 0 ? "Leave " + coins(tip) : "All paid";
                    click();
                    return true;
                }
            }
            if (ServiceScreen.in(mx, my, ix, fy + 30, iw, 16) && (due > 0 || tip > 0) && !screen.waiting()) {
                CompoundTag a = new CompoundTag();
                a.putInt("tip", tip);
                screen.send("pay", a);
                tip = 0;
                click();
            }
            return true;
        }
        int cy = y + S1 + 1 + LINE + S1;
        for (BasketLine l : basket) {
            int mxB = ix + iw - 22;
            if (ServiceScreen.in(mx, my, mxB, cy, 10, 10)) { send("remove", l.id()); return true; }
            if (ServiceScreen.in(mx, my, mxB + 12, cy, 10, 10) && !l.soldOut()) { send("add", l.id()); return true; }
            cy += LINE_H;
        }
        int fy = y + h - 74, segW = (iw - 4) / 3;
        for (int i = 0; i < MODES.length; i++) {
            if (ServiceScreen.in(mx, my, ix + i * (segW + 2), fy + 12, segW, 14)) {
                if (!MODES[i].equals("table") || table > 0) {
                    CompoundTag a = new CompoundTag();
                    a.putString("mode", MODES[i]);
                    screen.send("mode", a);
                    click();
                }
                return true;
            }
        }
        if (ServiceScreen.in(mx, my, ix, fy + 30, iw, 12) && mode.equals("table")) {
            CompoundTag a = new CompoundTag();
            a.putBoolean("on", !payNow);
            screen.send("payNow", a);
            click();
            return true;
        }
        if (ServiceScreen.in(mx, my, ix, fy + 46, iw, 18) && !basket.isEmpty() && screen.open() && !screen.waiting()) {
            screen.send("order", new CompoundTag());
            click();
            return true;
        }
        return true;
    }

    private void send(String action, String id) {
        if (screen.waiting()) return;
        CompoundTag a = new CompoundTag();
        a.putString("id", id);
        screen.send(action, a);
        click();
    }

    private void pickCourse(int i) {
        if (i == course) return;
        course = i;
        selected = -1;
        scroll.reset();
        click();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dy) {
        if (!ServiceScreen.in(mx, my, x, cardsTop(), menuW(), y + h - cardsTop())) return false;
        scroll.wheel(dy, CARD_H / 2);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int mods) {
        Course c = current();
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> { if (course > 0) pickCourse(course - 1); return true; }
            case GLFW.GLFW_KEY_RIGHT -> { if (course < courses.size() - 1) pickCourse(course + 1); return true; }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                if (c == null || c.dishes().isEmpty()) return true;
                int step = key == GLFW.GLFW_KEY_UP ? -cols : cols;
                selected = selected < 0 ? 0 : Math.max(0, Math.min(c.dishes().size() - 1, selected + step));
                int top = cardsTop();
                scroll.reveal(S1 + (selected / cols) * (CARD_H + S1), CARD_H, y + h - top);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (c != null && selected >= 0 && selected < c.dishes().size() && !c.dishes().get(selected).soldOut()) send("add", c.dishes().get(selected).id());
                return true;
            }
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                if (c != null && selected >= 0 && selected < c.dishes().size()) send("remove", c.dishes().get(selected).id());
                return true;
            }
            default -> { return false; }
        }
    }
}
