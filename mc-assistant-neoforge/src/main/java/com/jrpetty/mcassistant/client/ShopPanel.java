package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.client.UiStyle.State;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * [shops-homes] The shop's panel (the shop, the store, the café): Buy and Sell along the top; on Buy, the kinds of goods
 * down the left (Food, Drink, Clothes, Tools, Household, Comforts, Materials), the list in the middle (each line its
 * icon, name, price, how many are in, which way its price is going and why, and Order for what is not in), and the
 * basket on the right (− and + on each line, shift for a stack; the sum, each discount as its own line, the total, and
 * Buy). The total is the server's (the basket is kept there): the screen never prices anything itself. On Sell, what
 * you carry that the town buys, at its price. Keys: Up and Down pick a line, Enter or + adds it (shift: a stack), −
 * takes one out, Tab moves along the kinds, B buys.
 */
final class ShopPanel implements ServiceScreen.Panel {

    private static final int ROW_H = 22, TAB_H = 16;
    private static final String ALL = "ALL";

    private record Line(String id, ItemStack stack, String cat, String name, int stock, int each, int trend, String hint,
                        boolean own, String why, boolean order, String tip, boolean fresh, boolean clearance, boolean back) {}

    /** A making the shop wants of you: what, how many, for how much, and how many you carry. */
    private record Want(String id, ItemStack stack, String name, int count, int price, int have) {}

    private record Sell(String id, ItemStack stack, String name, int have, int lot, int bid, String why) {}

    private record Priced(String id, int n, int price) {}

    private final ServiceScreen screen;
    private final List<Line> lines = new ArrayList<>();
    private final List<Line> shown = new ArrayList<>();
    private final List<Sell> sells = new ArrayList<>();
    private final List<Want> wants = new ArrayList<>();
    private String till = "";
    private final List<String[]> cats = new ArrayList<>();
    private final List<Priced> quoted = new ArrayList<>();
    private final List<String> adjustLabels = new ArrayList<>();
    private final List<Integer> adjustAmounts = new ArrayList<>();
    private final List<String> receipts = new ArrayList<>();
    private final Map<String, Integer> basket = new LinkedHashMap<>();
    private final Map<String, ItemStack> icons = new LinkedHashMap<>();
    private final Scroll listScroll = new Scroll(), basketScroll = new Scroll();
    private int subtotal, total;
    private boolean buys, haggles, selling, first = true;
    private String cat = ALL;
    private int selected = -1;
    /** The last basket sent: until the server has answered it, its own basket is not read back over ours. */
    private int basketSent = -1;
    private int x, y, w, h, catW, listX, listW, basketX, basketW;
    private boolean compact;
    @Nullable private String tip;

    private static final ItemStack[] CAT_ICONS = {
        new ItemStack(Items.CHEST), new ItemStack(Items.BREAD), new ItemStack(Items.HONEY_BOTTLE), new ItemStack(Items.LEATHER_CHESTPLATE),
        new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.LANTERN), new ItemStack(Items.PAINTING), new ItemStack(Items.OAK_PLANKS) };
    private static final String[] CAT_IDS = { ALL, "FOOD", "DRINK", "CLOTHES", "TOOLS", "HOUSEHOLD", "COMFORTS", "MATERIALS" };

    ShopPanel(ServiceScreen screen) {
        this.screen = screen;
    }

    // ------------------------------------------------------------------ the data

    @Override
    public void setData(CompoundTag data) {
        CompoundTag body = data.getCompound("body");
        lines.clear();
        ListTag items = body.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag e = items.getCompound(i);
            ItemStack s = icons.computeIfAbsent(e.getString("id"), k -> ServiceScreen.stack(e, "item"));
            lines.add(new Line(e.getString("id"), s, e.getString("cat"), e.getString("name"), e.getInt("stock"), e.getInt("each"),
                e.getInt("trend"), e.getString("hint"), e.getBoolean("own"), e.getString("why"), e.getBoolean("order"), e.getString("tip"),
                e.getBoolean("new"), e.getBoolean("clearance"), e.getBoolean("back")));
        }
        cats.clear();
        cats.add(new String[]{ ALL, "All" });
        ListTag cl = body.getList("cats", Tag.TAG_COMPOUND);
        for (int i = 0; i < cl.size(); i++) cats.add(new String[]{ cl.getCompound(i).getString("id"), cl.getCompound(i).getString("label") });
        sells.clear();
        ListTag sl = body.getList("sell", Tag.TAG_COMPOUND);
        for (int i = 0; i < sl.size(); i++) {
            CompoundTag e = sl.getCompound(i);
            ItemStack s = icons.computeIfAbsent("sell:" + e.getString("id"), k -> ServiceScreen.stack(e, "item"));
            sells.add(new Sell(e.getString("id"), s, e.getString("name"), e.getInt("have"), e.getInt("lot"), e.getInt("bid"), e.getString("why")));
        }
        wants.clear();
        ListTag wl = body.getList("wanted", Tag.TAG_COMPOUND);
        for (int i = 0; i < wl.size(); i++) {
            CompoundTag e = wl.getCompound(i);
            ItemStack st = icons.computeIfAbsent("want:" + e.getString("id"), k -> ServiceScreen.stack(e, "item"));
            wants.add(new Want(e.getString("id"), st, e.getString("name"), e.getInt("count"), e.getInt("price"), e.getInt("have")));
        }
        till = body.getString("till");
        CompoundTag q = body.getCompound("quote");
        quoted.clear();
        ListTag ql = q.getList("lines", Tag.TAG_COMPOUND);
        for (int i = 0; i < ql.size(); i++) quoted.add(new Priced(ql.getCompound(i).getString("id"), ql.getCompound(i).getInt("n"), ql.getCompound(i).getInt("price")));
        adjustLabels.clear();
        adjustAmounts.clear();
        ListTag adj = q.getList("adjust", Tag.TAG_COMPOUND);
        for (int i = 0; i < adj.size(); i++) {
            adjustLabels.add(adj.getCompound(i).getString("label"));
            adjustAmounts.add(adj.getCompound(i).getInt("amount"));
        }
        subtotal = q.getInt("subtotal");
        total = q.getInt("total");
        receipts.clear();
        ListTag rl = body.getList("receipts", Tag.TAG_STRING);
        for (int i = 0; i < rl.size(); i++) receipts.add(rl.getString(i));
        if (first) selling = data.getString("tab").equals("sell");          // opened on its Sell tab
        first = false;
        buys = body.getBoolean("buys");
        haggles = body.getBoolean("haggles");
        if (!buys) selling = false;
        // The server's basket is the basket, once it has answered the last one we sent.
        if (data.getInt("nonce") >= basketSent) {
            basket.clear();
            for (Priced p : quoted) basket.put(p.id(), p.n());
        }
        filter();
    }

    private void filter() {
        shown.clear();
        for (Line l : lines) if (cat.equals(ALL) || l.cat().equals(cat)) shown.add(l);
        if (selected >= shown.size()) selected = shown.size() - 1;
    }

    // ------------------------------------------------------------------ layout

    @Override
    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        compact = w < 380;
        catW = compact ? 22 : 76;
        basketW = compact ? 112 : 140;
        basketX = x + w - basketW;
        listX = x + catW + S1;
        listW = basketX - S1 - listX;
    }

    private Font font() {
        return Minecraft.getInstance().font;
    }

    private int bodyTop() {
        return y + TAB_H + 2;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        tip = null;
        Font font = font();
        topTabs(g, font, mouseX, mouseY);
        if (selling) {
            sellList(g, font, mouseX, mouseY);
        } else {
            categories(g, font, mouseX, mouseY);
            buyList(g, font, mouseX, mouseY);
        }
        basketPane(g, font, mouseX, mouseY);
    }

    private void topTabs(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int tw = 56;
        String[] labels = buys ? new String[]{ "Buy", "Sell" } : new String[]{ "Buy" };
        for (int i = 0; i < labels.length; i++) {
            int tx = x + i * (tw + 2);
            boolean sel = (i == 1) == selling;
            boolean over = ServiceScreen.in(mouseX, mouseY, tx, y, tw, TAB_H);
            topTab(g, tx, y, tw, TAB_H, State.of(true, sel, over));
            g.drawString(font, labels[i], tx + (tw - font.width(labels[i])) / 2, y + 4, sel ? TEXT : TEXT_MUTED, false);
        }
        String note = selling ? "The town pays four fifths of its price" : shown.size() + (shown.size() == 1 ? " line" : " lines");
        int nx = x + labels.length * (tw + 2) + S2;
        text(g, font, note, nx, y + 4, basketX - S2 - nx, TEXT_FAINT);
    }

    private void categories(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int cy = bodyTop();
        for (String[] c : cats) {
            boolean sel = c[0].equals(cat);
            boolean over = ServiceScreen.in(mouseX, mouseY, x, cy, catW, ROW_H);
            sideTab(g, x, cy, catW, ROW_H, State.of(true, sel, over));
            g.renderItem(iconFor(c[0]), x + 3, cy + 3);
            if (!compact) text(g, font, c[1], x + 3 + ICON + S1, cy + 7, catW - ICON - 10, sel ? TEXT : TEXT_MUTED);
            if (over && compact) tip = c[1];
            cy += ROW_H;
        }
    }

    private static ItemStack iconFor(String cat) {
        for (int i = 0; i < CAT_IDS.length; i++) if (CAT_IDS[i].equals(cat)) return CAT_ICONS[i];
        return CAT_ICONS[0];
    }

    private void buyList(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int ly = bodyTop(), lh = y + h - ly;
        well(g, listX, ly, listW, lh, PAGE);
        listScroll.fit(shown.size() * ROW_H, lh - 2);
        g.enableScissor(listX + 1, ly + 1, listX + listW - 1, ly + lh - 1);
        int ry = ly + 1 - listScroll.offset();
        for (int i = 0; i < shown.size(); i++, ry += ROW_H) {
            if (ry + ROW_H < ly || ry > ly + lh) continue;
            buyRow(g, font, shown.get(i), i, listX + 1, ry, listW - 6, mouseX, mouseY, ly, lh);
        }
        g.disableScissor();
        scrollBar(g, listX + listW - 4, ly + 1, lh - 2, listScroll.offset(), listScroll.max(), lh);
        if (shown.isEmpty()) text(g, font, lines.isEmpty() ? "Nothing on the shelves just now." : "Nothing of that kind in.", listX + S2, ly + S2, listW - S4, TEXT_FAINT);
    }

    private void buyRow(GuiGraphics g, Font font, Line l, int i, int rx, int ry, int rw, int mouseX, int mouseY, int ly, int lh) {
        boolean inList = mouseY >= ly && mouseY < ly + lh;
        boolean over = inList && ServiceScreen.in(mouseX, mouseY, rx, ry, rw, ROW_H);
        boolean out = l.stock() <= 0;
        if (i == selected) g.fill(rx, ry, rx + rw, ry + ROW_H, TILE_SELECTED);
        else if (over) g.fill(rx, ry, rx + rw, ry + ROW_H, TILE_HOVER);
        g.renderItem(l.stack(), rx + 2, ry + 3);
        int bw = out ? (l.order() ? 40 : 0) : 16;
        int priceRight = rx + rw - S1 - bw - (bw > 0 ? S1 : 0);
        String price = out ? "" : l.each() + "c";
        int tx = rx + 2 + ICON + S1;
        int room = priceRight - font.width(price) - S1 - tx;
        text(g, font, l.name(), tx, ry + 2, room, out ? TEXT_DISABLED : TEXT);
        String arrow = l.trend() > 0 ? "↑ " : l.trend() < 0 ? "↓ " : "";
        String badge = out ? "Sold out · " : l.fresh() ? "NEW · " : l.back() ? "Back in stock · " : l.clearance() ? "Clearance · " : "";
        String sub = badge + (out ? l.why() : l.stock() + " in" + (l.hint().isEmpty() ? "" : " · " + arrow + l.hint())
            + (l.hint().isEmpty() && !arrow.isEmpty() ? " · " + arrow.trim() : ""));
        int subColour = out ? TEXT_FAINT : l.fresh() || l.back() ? INFO : l.clearance() ? SUCCESS : l.trend() > 0 ? WARNING : l.trend() < 0 ? SUCCESS : TEXT_FAINT;
        boolean clipped = text(g, font, sub, tx, ry + 12, room, subColour);
        if (!out) g.drawString(font, price, priceRight - font.width(price), ry + 7, TEXT, false);
        if (bw > 0) {
            int bx = rx + rw - S1 - bw;
            boolean overB = inList && ServiceScreen.in(mouseX, mouseY, bx, ry + 3, bw, 16);
            boolean enabled = !screen.waiting();
            button(g, font, out ? "Order" : "+", bx, ry + 3, bw, 16, State.of(enabled, false, overB));
            if (overB) tip = out ? "Ask for it to be made: " + l.why() : "Add one to your basket (shift: a stack)";
        }
        if (over && tip == null) tip = clipped ? sub + "\n" + l.tip() : l.tip();
    }

    private void sellList(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int ly = bodyTop(), lh = y + h - ly;
        int lx = x, lw = basketX - S1 - x;
        well(g, lx, ly, lw, lh, PAGE);
        listScroll.fit((wants.size() + sells.size()) * ROW_H, lh - 2);
        g.enableScissor(lx + 1, ly + 1, lx + lw - 1, ly + lh - 1);
        int ry = ly + 1 - listScroll.offset();
        for (Want w : wants) {
            if (ry + ROW_H >= ly && ry <= ly + lh) {
                int rx = lx + 1, rw = lw - 6;
                boolean inList = mouseY >= ly && mouseY < ly + lh;
                g.fill(rx, ry, rx + rw, ry + ROW_H, HIGHLIGHT);
                g.fill(rx, ry, rx + 2, ry + ROW_H, HIGHLIGHT_SPINE);
                g.renderItem(w.stack(), rx + 4, ry + 3);
                int bx = rx + rw - S1 - 48;
                int tx = rx + 4 + ICON + S1, room = bx - S1 - tx;
                text(g, font, "Wanted: " + w.name(), tx, ry + 2, room, TEXT);
                String sub = w.count() + " for " + w.price() + "c · you have " + w.have();
                text(g, font, sub, tx, ry + 12, room, w.have() > 0 ? SUCCESS : TEXT_FAINT);
                boolean can = w.have() > 0 && screen.open() && !screen.waiting();
                boolean overB = inList && ServiceScreen.in(mouseX, mouseY, bx, ry + 3, 48, 16);
                button(g, font, "Supply", bx, ry + 3, 48, 16, State.of(can, false, overB));
                if (overB) tip = !screen.open() ? screen.status() : w.have() > 0 ? "Sell the shop what it is short of, at a good price"
                    : "You've none of this on you: the shop is short of it for its bench.";
            }
            ry += ROW_H;
        }
        for (int i = 0; i < sells.size(); i++, ry += ROW_H) {
            if (ry + ROW_H < ly || ry > ly + lh) continue;
            Sell s = sells.get(i);
            int rx = lx + 1, rw = lw - 6;
            boolean inList = mouseY >= ly && mouseY < ly + lh;
            boolean over = inList && ServiceScreen.in(mouseX, mouseY, rx, ry, rw, ROW_H);
            if (over) g.fill(rx, ry, rx + rw, ry + ROW_H, TILE_HOVER);
            g.renderItem(s.stack(), rx + 2, ry + 3);
            int bx = rx + rw - S1 - 40;
            int tx = rx + 2 + ICON + S1, room = bx - S1 - tx;
            boolean can = s.bid() > 0 && s.have() >= s.lot();
            text(g, font, s.name() + "  ×" + s.have(), tx, ry + 2, room, can ? TEXT : TEXT_DISABLED);
            String sub = !s.why().isEmpty() ? s.why() : s.have() < s.lot() ? "Bought by the " + s.lot() + "." : (s.lot() > 1 ? s.lot() + " for " : "") + s.bid() + "c";
            text(g, font, sub, tx, ry + 12, room, can ? TEXT_FAINT : DANGER);
            boolean overB = inList && ServiceScreen.in(mouseX, mouseY, bx, ry + 3, 40, 16);
            button(g, font, "Sell", bx, ry + 3, 40, 16, State.of(can && screen.open() && !screen.waiting(), false, overB));
            if (overB) tip = !screen.open() ? screen.status() : can ? "Sell " + (s.lot() > 1 ? s.lot() : "one") + " (shift: all you have)" : sub;
        }
        g.disableScissor();
        scrollBar(g, lx + lw - 4, ly + 1, lh - 2, listScroll.offset(), listScroll.max(), lh);
        if (sells.isEmpty() && wants.isEmpty()) text(g, font, "Nothing you carry is wanted here.", lx + S2, ly + S2, lw - S4, TEXT_FAINT);
    }

    /** The basket: its lines with − and +, the sum, each discount, the total, Buy, Haggle. */
    private void basketPane(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int bx = basketX, by = y, bh = h;
        well(g, bx, by, basketW, bh, CARD);
        heading(g, font, selling ? "Ledger" : "Basket", bx + S1 + 2, by + 4, basketW - S2 - 4);
        int footH = 12 * (2 + adjustLabels.size()) + 22 + (haggles ? 18 : 0);
        int listTop = by + 16, listH = Math.max(0, bh - 16 - footH - S1);
        if (selling) {
            int ry = listTop;
            if (!till.isEmpty()) {
                for (net.minecraft.util.FormattedCharSequence seq : TextCache.splitPlain(font, "The shop's till. " + till, basketW - S2 - 2)) {
                    g.drawString(font, seq, bx + S1 + 2, ry, TEXT_FAINT, false);
                    ry += 10;
                }
                ry += 4;
            }
            if (receipts.isEmpty()) text(g, font, "Your dealings here will show here.", bx + S1 + 2, ry, basketW - S2, TEXT_FAINT);
            for (String r : receipts) {
                if (ry + 10 > by + bh - 4) break;
                boolean clipped = text(g, font, r, bx + S1 + 2, ry, basketW - S2 - 2, TEXT_MUTED);
                if (clipped && ServiceScreen.in(mouseX, mouseY, bx, ry, basketW, 10)) tip = r;
                ry += 11;
            }
            return;
        }
        basketScroll.fit(quoted.size() * 20, listH);
        g.enableScissor(bx + 1, listTop, bx + basketW - 1, listTop + listH);
        int ry = listTop - basketScroll.offset();
        for (Priced p : quoted) {
            ItemStack icon = icons.getOrDefault(p.id(), ItemStack.EMPTY);
            g.renderItem(icon, bx + 2, ry + 1);
            String price = p.price() + "c";
            int stepX = bx + basketW - S1 - 26;
            text(g, font, "×" + p.n(), bx + 2 + ICON + 1, ry + 1, stepX - (bx + ICON + 3) - 2, TEXT);
            text(g, font, price, bx + 2 + ICON + 1, ry + 10, stepX - (bx + ICON + 3) - 2, TEXT_FAINT);
            boolean overMinus = ServiceScreen.in(mouseX, mouseY, stepX, ry + 2, 12, 14);
            boolean overPlus = ServiceScreen.in(mouseX, mouseY, stepX + 13, ry + 2, 12, 14);
            button(g, font, "-", stepX, ry + 2, 12, 14, State.of(!screen.waiting(), false, overMinus));
            button(g, font, "+", stepX + 13, ry + 2, 12, 14, State.of(!screen.waiting(), false, overPlus));
            if (overMinus || overPlus) tip = "Shift for a whole stack";
            if (ServiceScreen.in(mouseX, mouseY, bx, ry, stepX - bx, 20)) tip = nameOf(p.id()) + ": " + p.n() + " for " + p.price() + "c";
            ry += 20;
        }
        g.disableScissor();
        if (quoted.isEmpty()) text(g, font, "Empty. Pick something with +.", bx + S1 + 2, listTop + 2, basketW - S2, TEXT_FAINT);
        // The sums.
        int fy = by + bh - footH;
        g.fill(bx + S1, fy - 2, bx + basketW - S1, fy - 1, EDGE_SOFT);
        sum(g, font, "Subtotal", subtotal, bx, fy, TEXT_MUTED);
        fy += 12;
        for (int i = 0; i < adjustLabels.size(); i++) {
            int a = adjustAmounts.get(i);
            boolean clipped = sumLine(g, font, adjustLabels.get(i), a == 0 ? "" : (a > 0 ? "+" : "") + a + "c", bx, fy, a < 0 ? SUCCESS : a > 0 ? WARNING : TEXT_FAINT);
            if (clipped && ServiceScreen.in(mouseX, mouseY, bx, fy, basketW, 12)) tip = adjustLabels.get(i);
            fy += 12;
        }
        int coins = Math.max(0, total);
        g.drawString(font, "Total", bx + S1 + 2, fy, TEXT, false);
        String tot = coins + "c";
        title(g, font, tot, bx + basketW - S1 - 2 - titleWidth(font, tot), fy);
        fy += 12;
        // Buy: the primary action.
        boolean canBuy = !quoted.isEmpty() && screen.open() && !screen.waiting();
        int bw = basketW - S2;
        boolean overBuy = ServiceScreen.in(mouseX, mouseY, bx + S1, fy, bw, 18);
        if (canBuy) {
            g.fill(bx + S1, fy, bx + S1 + bw, fy + 18, overBuy ? Ui.lighten(ACCENT_FILL, 0.15F) : ACCENT_FILL);
            g.renderOutline(bx + S1, fy, bw, 18, ACCENT);
            String label = "Buy for " + coins + "c";
            g.drawString(font, label, bx + S1 + (bw - font.width(label)) / 2, fy + 5, TEXT_ON_DARK, false);
        } else {
            button(g, font, "Buy", bx + S1, fy, bw, 18, State.DISABLED);
        }
        if (overBuy) tip = canBuy ? "Buy the basket (B)" : !screen.open() ? screen.status() : screen.waiting() ? "At the counter…" : "Your basket is empty.";
        fy += 20;
        if (haggles) {
            boolean overH = ServiceScreen.in(mouseX, mouseY, bx + S1, fy, bw, 14);
            button(g, font, "Haggle", bx + S1, fy, bw, 14, State.of(screen.open() && !screen.waiting(), false, overH));
            if (overH) tip = "Ask for a better price: once a day; the keeper may take it ill.";
        }
    }

    private void sum(GuiGraphics g, Font font, String label, int amount, int bx, int fy, int colour) {
        sumLine(g, font, label, amount + "c", bx, fy, colour);
    }

    private boolean sumLine(GuiGraphics g, Font font, String label, String amount, int bx, int fy, int colour) {
        int aw = font.width(amount);
        g.drawString(font, amount, bx + basketW - S1 - 2 - aw, fy, colour, false);
        return text(g, font, label, bx + S1 + 2, fy, basketW - S2 - 6 - aw, colour);
    }

    private String nameOf(String id) {
        for (Line l : lines) if (l.id().equals(id)) return l.name();
        return id;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean shift = Screen.hasShiftDown();
        // The top tabs.
        if (ServiceScreen.in(mouseX, mouseY, x, y, 56, TAB_H)) { selling = false; listScroll.reset(); click(); return true; }
        if (buys && ServiceScreen.in(mouseX, mouseY, x + 58, y, 56, TAB_H)) { selling = true; listScroll.reset(); click(); return true; }
        int ly = bodyTop(), lh = y + h - ly;
        if (!selling) {
            int cy = ly;
            for (String[] c : cats) {
                if (ServiceScreen.in(mouseX, mouseY, x, cy, catW, ROW_H)) {
                    cat = c[0];
                    selected = -1;
                    listScroll.reset();
                    filter();
                    click();
                    return true;
                }
                cy += ROW_H;
            }
            if (ServiceScreen.in(mouseX, mouseY, listX, ly, listW, lh)) {
                int i = (int) ((mouseY - ly - 1 + listScroll.offset()) / ROW_H);
                if (i < 0 || i >= shown.size()) return true;
                selected = i;
                Line l = shown.get(i);
                int rw = listW - 6, rx = listX + 1;
                int bw = l.stock() <= 0 ? (l.order() ? 40 : 0) : 16;
                int bx = rx + rw - S1 - bw;
                int ry = ly + 1 - listScroll.offset() + i * ROW_H;
                if (bw > 0 && ServiceScreen.in(mouseX, mouseY, bx, ry + 3, bw, 16) && !screen.waiting()) {
                    click();
                    if (l.stock() <= 0) {
                        CompoundTag a = new CompoundTag();
                        a.putString("id", l.id());
                        screen.send("order", a);
                    } else {
                        add(l, shift ? Math.min(l.stock(), l.stack().getMaxStackSize()) : 1);
                    }
                }
                return true;
            }
        } else if (ServiceScreen.in(mouseX, mouseY, x, ly, basketX - S1 - x, lh)) {
            int i = (int) ((mouseY - ly - 1 + listScroll.offset()) / ROW_H);
            if (i >= 0 && i < wants.size()) {
                Want w = wants.get(i);
                int rw = basketX - S1 - x - 6, rx = x + 1;
                int bx = rx + rw - S1 - 48;
                int ry = ly + 1 - listScroll.offset() + i * ROW_H;
                if (ServiceScreen.in(mouseX, mouseY, bx, ry + 3, 48, 16) && w.have() > 0 && screen.open() && !screen.waiting()) {
                    click();
                    CompoundTag a = new CompoundTag();
                    a.putString("id", w.id());
                    a.putInt("n", w.count());
                    screen.send("supply", a);
                }
                return true;
            }
            i -= wants.size();
            if (i < 0 || i >= sells.size()) return true;
            Sell s = sells.get(i);
            int rw = basketX - S1 - x - 6, rx = x + 1;
            int bx = rx + rw - S1 - 40;
            int ry = ly + 1 - listScroll.offset() + (i + wants.size()) * ROW_H;
            if (ServiceScreen.in(mouseX, mouseY, bx, ry + 3, 40, 16) && s.bid() > 0 && s.have() >= s.lot() && screen.open() && !screen.waiting()) {
                click();
                CompoundTag a = new CompoundTag();
                a.putString("id", s.id());
                a.putInt("lots", shift ? Math.max(1, s.have() / s.lot()) : 1);
                screen.send("sell", a);
            }
            return true;
        }
        if (!selling && ServiceScreen.in(mouseX, mouseY, basketX, y, basketW, h)) return basketClick(mouseX, mouseY, shift);
        return false;
    }

    private boolean basketClick(double mouseX, double mouseY, boolean shift) {
        int footH = 12 * (2 + adjustLabels.size()) + 22 + (haggles ? 18 : 0);
        int listTop = y + 16;
        int ry = listTop - basketScroll.offset();
        for (Priced p : quoted) {
            int stepX = basketX + basketW - S1 - 26;
            if (ry + 20 > listTop && ServiceScreen.in(mouseX, mouseY, stepX, ry + 2, 12, 14)) { change(p.id(), shift ? -p.n() : -1); click(); return true; }
            if (ry + 20 > listTop && ServiceScreen.in(mouseX, mouseY, stepX + 13, ry + 2, 12, 14)) { change(p.id(), shift ? 64 : 1); click(); return true; }
            ry += 20;
        }
        int fy = y + h - footH + 12 * (2 + adjustLabels.size());
        int bw = basketW - S2;
        if (ServiceScreen.in(mouseX, mouseY, basketX + S1, fy, bw, 18)) { buy(); return true; }
        if (haggles && ServiceScreen.in(mouseX, mouseY, basketX + S1, fy + 20, bw, 14) && screen.open() && !screen.waiting()) {
            click();
            screen.send("haggle", new CompoundTag());
            return true;
        }
        return true;
    }

    private void buy() {
        if (quoted.isEmpty() || !screen.open() || screen.waiting()) return;
        click();
        screen.send("buy", new CompoundTag());
    }

    private void add(Line l, int n) {
        change(l.id(), n);
    }

    /** Change a line of the basket by so many (within what is in), and tell the server, which prices it. */
    private void change(String id, int by) {
        int stock = 0;
        for (Line l : lines) if (l.id().equals(id)) stock = l.stock();
        int now = Math.max(0, Math.min(Math.min(64, stock), basket.getOrDefault(id, 0) + by));
        if (now <= 0) basket.remove(id);
        else basket.put(id, now);
        CompoundTag b = new CompoundTag();
        for (Map.Entry<String, Integer> e : basket.entrySet()) b.putInt(e.getKey(), e.getValue());
        CompoundTag a = new CompoundTag();
        a.put("basket", b);
        basketSent = screen.peekNonce();
        screen.send("basket", a);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dy) {
        if (ServiceScreen.in(mouseX, mouseY, basketX, y, basketW, h)) { basketScroll.wheel(dy, 20); return true; }
        listScroll.wheel(dy, ROW_H);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int mods) {
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        switch (key) {
            case GLFW.GLFW_KEY_DOWN -> { selected = Math.min(shown.size() - 1, selected + 1); revealSelected(); return true; }
            case GLFW.GLFW_KEY_UP -> { selected = Math.max(0, selected - 1); revealSelected(); return true; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
                if (!selling && selected >= 0 && selected < shown.size() && shown.get(selected).stock() > 0) {
                    Line l = shown.get(selected);
                    add(l, shift ? Math.min(l.stock(), l.stack().getMaxStackSize()) : 1);
                    click();
                }
                return true;
            }
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> {
                if (!selling && selected >= 0 && selected < shown.size()) { change(shown.get(selected).id(), shift ? -64 : -1); click(); }
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                int at = 0;
                for (int i = 0; i < cats.size(); i++) if (cats.get(i)[0].equals(cat)) at = i;
                cat = cats.get(Math.floorMod(at + (shift ? -1 : 1), cats.size()))[0];
                selected = -1;
                listScroll.reset();
                filter();
                click();
                return true;
            }
            case GLFW.GLFW_KEY_B -> { buy(); return true; }
            default -> { return false; }
        }
    }

    private void revealSelected() {
        if (selected < 0) return;
        listScroll.reveal(selected * ROW_H, ROW_H, y + h - bodyTop() - 2);
    }

    @Nullable
    @Override
    public String tooltip() {
        return tip;
    }
}
