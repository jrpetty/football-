package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * [biz30] A business's data, for its owner (entity/BizData.build): a dashboard of what it sold and what it cost.
 * <ul>
 * <li><b>Overview</b>: today, this week and all time (sales, costs, profit, margin) with the week's way against the last;
 *     customers a day; the best seller; takings and profit day by day.</li>
 * <li><b>Sales</b>: units and takings a day over 7, 28 or 56 days (a bar's numbers under the mouse); each ware's units a day,
 *     units, takings, average price and share, sorted by a click on a column.</li>
 * <li><b>Costs</b>: stock, wages, rent and tax, and the rest, stacked day by day under the profit line; the breakdown.</li>
 * <li><b>Stock</b>: on hand, days of cover at the rate it sells, sales missed for want of it, what to order.</li>
 * <li><b>Staff</b>: its hands, their days and wages; its open hours, the hours kept, sales an hour kept.</li>
 * <li><b>Customers</b>: how many, folk and players, those who came back, and its busiest hours.</li>
 * <li><b>All businesses</b> (an owner of two or more): side by side, a click into any.</li>
 * </ul>
 * A word of advice from the numbers at its foot. Charts are plain fills in the mod's own look; it fits the GUI at 2 to 4 on
 * 1280x720 and 1920x1080 (ScreenFit.side). It asks the server again (BizDataAskPayload) when its range changes or another
 * business is picked.
 */
public class BusinessDataScreen extends Screen {

    private static final String[] TABS = { "Overview", "Sales", "Costs", "Stock", "Staff", "Customers", "All" };
    private static final int PAD = 8, HEAD = 32, FOOT = 14;
    // the series' colours
    static final int REV = 0xFF4E9A3A, UNITS = 0xFF2F6FA8, STOCK = 0xFFB07A3A, WAGES = 0xFF4A82C8, RENT = 0xFF9C4A7A, OTHER = 0xFF8A8A8A,
        PROFIT = 0xFF1E1E1E, LOSS = 0xFFC2452F, FOLK = 0xFF5E9E34, PLAYER = 0xFF4A82C8, GRID = 0x22000000;

    private CompoundTag data;
    private int left, top, w, h, tab, scroll, sortCol = 3;
    private boolean sortDown = true;
    private final List<int[]> boxes = new ArrayList<>();
    private final List<Runnable> acts = new ArrayList<>();
    private final List<Component> tip = new ArrayList<>();

    public BusinessDataScreen(CompoundTag data) {
        super(Component.literal("Business data"));
        this.data = data;
    }

    /** The server's answer: open, or bring the open dashboard up to date (its tab kept). */
    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof BusinessDataScreen open) {
            boolean same = open.data.getString("id").equals(data.getString("id"));
            open.data = data;
            if (!same) { open.tab = 0; open.scroll = 0; }
            if (!open.hasAll() && open.tab == 6) open.tab = 0;
            return;
        }
        mc.setScreen(new BusinessDataScreen(data));
    }

    /** Ask the server for a business's data ("" for the asker's first), over so many days. */
    public static void ask(String id, int range) {
        if (Minecraft.getInstance().getConnection() == null) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.jrpetty.mcassistant.net.BizDataAskPayload(id, range));
    }

    private boolean hasAll() {
        return data.getList("firms", Tag.TAG_COMPOUND).size() >= 2;
    }

    @Override
    protected void init() {
        w = ScreenFit.side(480, width, 8);
        h = ScreenFit.side(300, height, 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        boxes.clear();
        acts.clear();
        tip.clear();
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        UiStyle.headerBand(g, left, top, w, HEAD - 6);
        int x = left + PAD, inner = w - PAD * 2;
        // the range, the books, a way out
        int bx = left + w - PAD - 36;
        button(g, "Close", bx, top + 5, 36, mx, my, true, this::onClose);
        bx -= 40;
        button(g, "Books", bx, top + 5, 38, mx, my, true, () -> command("village business books " + BusinessScreen.quoted(data.getString("id"))));
        int range = data.getInt("range");
        if (data.getBoolean("full")) {
            for (int i = 2; i >= 0; i--) {
                int r = new int[]{ 7, 28, 56 }[i];
                bx -= 28;
                int rr = r;
                button(g, r + "d", bx, top + 5, 26, mx, my, range != r, () -> ask(data.getString("id"), rr));
                if (range == r) g.fill(bx + 2, top + 17, bx + 24, top + 18, UiStyle.ACCENT_FILL);
            }
        }
        int titleRoom = bx - x - 6;
        UiStyle.title(g, font, UiStyle.clip(font, data.getString("name"), titleRoom), x, top + 5);
        small(g, data.getString("kind") + " · " + data.getString("town") + (data.getString("where").isEmpty() ? "" : " · " + data.getString("where"))
            + " · " + data.getString("owners") + " · since day " + (data.getLong("opened") + 1), x, top + 16, UiStyle.TEXT_MUTED, titleRoom);
        // the tabs
        int tx = x;
        List<Integer> shownTabs = new ArrayList<>();
        for (int i = 0; i < 7; i++) if (i == 0 || i == 6 && hasAll() || i < 6 && data.getBoolean("full")) shownTabs.add(i);
        if (!shownTabs.contains(tab)) tab = 0;
        for (int i : shownTabs) {
            String name = i == 6 ? "All businesses" : TABS[i];
            int tw = font.width(name) + 10;
            if (tx + tw > left + w - PAD) { name = TABS[i]; tw = font.width(name) + 8; }
            boolean over = mx >= tx && mx < tx + tw && my >= top + HEAD - 6 && my < top + HEAD + 8;
            UiStyle.topTab(g, tx, top + HEAD - 6, tw, 14, UiStyle.State.of(true, i == tab, over));
            g.drawString(font, name, tx + (tw - font.width(name)) / 2, top + HEAD - 3, UiStyle.TEXT, false);
            int k = i;
            zone(tx, top + HEAD - 6, tw, 14, () -> { tab = k; scroll = 0; UiStyle.click(); });
            tx += tw + 2;
        }
        int y = top + HEAD + 12, bh = top + h - FOOT - 2 - y;
        switch (tab) {
            case 1 -> sales(g, x, y, inner, bh, mx, my);
            case 2 -> costs(g, x, y, inner, bh, mx, my);
            case 3 -> stock(g, x, y, inner, bh, mx, my);
            case 4 -> staff(g, x, y, inner, bh, mx, my);
            case 5 -> customers(g, x, y, inner, bh, mx, my);
            case 6 -> all(g, x, y, inner, bh, mx, my);
            default -> overview(g, x, y, inner, bh, mx, my);
        }
        // a word from the numbers
        String advice = data.getString("advice");
        if (!advice.isEmpty()) {
            int fy = top + h - FOOT;
            g.fill(left + 2, fy - 2, left + w - 2, fy - 1, UiStyle.EDGE_SOFT);
            boolean warn = !advice.startsWith("Going well") && !advice.startsWith("Steady") && !advice.startsWith("You see");
            int bw = UiStyle.badge(g, font, warn ? "Advice" : "Note", x, fy, warn ? UiStyle.WARNING_FILL : UiStyle.SUCCESS_FILL);
            String shown = UiStyle.clip(font, advice, inner - bw - 6);
            g.drawString(font, shown, x + bw + 5, fy + 2, warn ? UiStyle.WARNING : UiStyle.SUCCESS, false);
            if (mx >= x && mx < x + inner && my >= fy && my < fy + 11) {
                ListTag all = data.getList("advice_all", Tag.TAG_STRING);
                List<Component> lines = new ArrayList<>();
                if (all.isEmpty()) lines.add(Component.literal(advice));
                for (int i = 0; i < all.size(); i++) lines.add(Component.literal("• " + all.getString(i)));
                setTip(lines);
            }
        }
        for (var r : renderables) r.render(g, mx, my, pt);
        if (!tip.isEmpty()) g.renderComponentTooltip(font, splitTip(tip), mx, my);
    }

    private List<Component> splitTip(List<Component> in) {
        List<Component> out = new ArrayList<>();
        for (Component c : in) {
            for (var seq : font.getSplitter().splitLines(c, 260, net.minecraft.network.chat.Style.EMPTY)) out.add(Component.literal(seq.getString()));
        }
        return out;
    }

    private void setTip(List<Component> lines) {
        tip.clear();
        tip.addAll(lines);
    }

    // ------------------------------------------------------------------ the tabs

    private void overview(GuiGraphics g, int x, int y, int inner, int bh, int mx, int my) {
        long[] td = data.getLongArray("p_today"), wk = data.getLongArray("p_week"), pw = data.getLongArray("p_before"), al = data.getLongArray("p_all");
        if (td.length < 6) return;
        // four tiles: sales, costs, profit, margin (the week; today and all time under; the way against last week)
        String[] names = { "Sales", "Costs", "Profit", "Margin" };
        int gap = 4, tw = (inner - gap * 3) / 4, th = 40;
        for (int i = 0; i < 4; i++) {
            int tx = x + i * (tw + gap);
            UiStyle.tile(g, tx, y, tw, th, UiStyle.State.of(true, false, false));
            small(g, names[i] + " · this week", tx + 4, y + 3, UiStyle.TEXT_FAINT, tw - 8);
            long v, last, t0, a0;
            String big, sub;
            if (i < 3) {
                v = wk[i]; last = pw[i]; t0 = td[i]; a0 = al[i];
                big = c(v);
                sub = "today " + c(t0) + " · all " + c(a0);
            } else {
                v = margin(wk); last = margin(pw); t0 = margin(td); a0 = margin(al);
                big = wk[0] == 0 ? "–" : v + "%";
                sub = "today " + (td[0] == 0 ? "–" : t0 + "%") + " · all " + (al[0] == 0 ? "–" : a0 + "%");
            }
            int ink = i == 2 || i == 3 ? (v >= 0 ? UiStyle.SUCCESS : UiStyle.DANGER) : UiStyle.TEXT;
            g.drawString(font, UiStyle.clip(font, big, tw - 22), tx + 4, y + 13, ink, false);
            int delta = Long.compare(v, last);
            UiStyle.trend(g, font, delta, i != 1, tx + tw - 12, y + 13);
            small(g, sub, tx + 4, y + 25, UiStyle.TEXT_MUTED, tw - 8);
            if (mx >= tx && mx < tx + tw && my >= y && my < y + th) {
                String pct = last == 0 ? "" : " (" + (v - last > 0 ? "+" : "") + ((v - last) * 100 / Math.max(1, Math.abs(last))) + "%)";
                setTip(List.of(Component.literal(names[i] + ": this week " + (i == 3 ? big : c(v)) + ", last week " + (i == 3 ? (pw[0] == 0 ? "–" : last + "%") : c(last)) + pct)));
            }
        }
        y += th + 6;
        if (!data.getBoolean("full")) {
            small(g, "The rest of its books are its owner's: what it sold, to whom, what it cost.", x, y, UiStyle.TEXT_MUTED, inner);
            return;
        }
        // facts on the right, the chart on the left
        int fw = Math.min(150, inner / 3), cw = inner - fw - 8;
        long[][] days = days();
        long[] rev = col(days, 1), net = col(days, 6);
        chart(g, x, y, cw, bh - (y - (top + HEAD + 12)) - 2, rev, REV, net, true, mx, my, "Takings a day, and the profit line");
        int fx = x + cw + 8, fy = y;
        long[] span = data.getLongArray("span");
        int range = data.getInt("range");
        long cust = span.length > 9 ? span[9] : 0, trans = span.length > 8 ? span[8] : 0;
        fy = fact(g, "Customers a day", fmt1(cust / (double) range), fx, fy, fw);
        fy = fact(g, "Sales a day", fmt1(trans / (double) range), fx, fy, fw);
        fy = fact(g, "Sales today", td[4] + " (" + td[5] + " items)", fx, fy, fw);
        Item best = null;
        for (Item it : items()) if (best == null || it.rev > best.rev) best = it;
        fy = fact(g, "Best seller", best == null || best.rev == 0 ? "–" : best.name + " (" + best.units + ")", fx, fy, fw);
        fy = fact(g, "Till", c(data.getLong("till")), fx, fy, fw);
        int[] hours = data.getIntArray("hours");
        int bhr = -1;
        for (int i = 0; i < hours.length; i++) if (bhr < 0 || hours[i] > hours[bhr]) bhr = i;
        fact(g, "Busiest hour", bhr < 0 || hours[bhr] == 0 ? "–" : hourWords(bhr), fx, fy, fw);
    }

    private void sales(GuiGraphics g, int x, int y, int inner, int bh, int mx, int my) {
        long[][] days = days();
        int ch = Math.max(46, bh * 2 / 5);
        chart(g, x, y, inner, ch, col(days, 1), REV, null, true, mx, my, "Takings a day (bars), units a day (blue)");
        overlayUnits(g, x, y, inner, ch, col(days, 7));
        y += ch + 6;
        List<Item> items = items();
        long totalRev = 0;
        for (Item it : items) totalRev += it.rev;
        int range = data.getInt("range");
        String[] heads = { "Ware", "A day", "Units", "Takings", "Avg price", "Share" };
        int[] cols = { 0, inner * 38 / 100, inner * 50 / 100, inner * 62 / 100, inner * 78 / 100, inner * 90 / 100 };
        for (int i = 0; i < heads.length; i++) {
            int hx = x + cols[i], hw = (i + 1 < cols.length ? cols[i + 1] : inner) - cols[i];
            boolean over = mx >= hx && mx < hx + hw && my >= y && my < y + 10;
            UiStyle.sortHeading(g, font, heads[i], hx, y, hw, sortCol == i, sortDown, over);
            int k = i;
            zone(hx, y, hw, 10, () -> { if (sortCol == k) sortDown = !sortDown; else { sortCol = k; sortDown = k != 0; } UiStyle.click(); });
        }
        y += 12;
        final long tr = totalRev;
        Comparator<Item> cmp = switch (sortCol) {
            case 0 -> Comparator.comparing((Item it) -> it.name);
            case 1, 2 -> Comparator.comparingLong((Item it) -> it.units);
            case 4 -> Comparator.comparingDouble((Item it) -> it.units == 0 ? 0 : it.rev / (double) it.units);
            default -> Comparator.comparingLong((Item it) -> it.rev);
        };
        items.removeIf(it -> it.units == 0 && it.rev == 0);
        items.sort(sortDown ? cmp.reversed() : cmp);
        int rows = Math.max(1, (top + h - FOOT - 4 - y) / 11);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - rows)));
        if (items.isEmpty()) {
            UiStyle.empty(g, font, "Nothing sold in these " + range + " days", "Stock its shelves and open its doors.", x, y, inner, top + h - FOOT - 4 - y);
        }
        for (int i = scroll; i < items.size() && i < scroll + rows; i++) {
            Item it = items.get(i);
            if ((i - scroll) % 2 == 0) g.fill(x - 2, y - 1, x + inner + 2, y + 10, 0x10000000);
            boolean over = mx >= x && mx < x + inner && my >= y - 1 && my < y + 10;
            if (over) g.fill(x - 2, y - 1, x + inner + 2, y + 10, UiStyle.TILE_HOVER);
            cell(g, it.name, x + cols[0], y, cols[1] - 4, UiStyle.TEXT);
            cell(g, fmt1(it.units / (double) range), x + cols[1], y, cols[2] - cols[1] - 4, UiStyle.TEXT_MUTED);
            cell(g, Long.toString(it.units), x + cols[2], y, cols[3] - cols[2] - 4, UiStyle.TEXT);
            cell(g, c(it.rev), x + cols[3], y, cols[4] - cols[3] - 4, UiStyle.TEXT);
            cell(g, it.units == 0 ? "–" : c(it.rev / Math.max(1, it.units)), x + cols[4], y, cols[5] - cols[4] - 4, UiStyle.TEXT_MUTED);
            int share = tr == 0 ? 0 : (int) (it.rev * 100 / tr);
            int sw = inner - cols[5] - 2;
            g.fill(x + cols[5], y + 1, x + cols[5] + sw * share / 100, y + 8, Ui.lighten(REV, 0.25F));
            cell(g, share + "%", x + cols[5] + 1, y, sw, UiStyle.TEXT);
            if (over) setTip(List.of(Component.literal(it.name + ": " + it.units + " sold for " + c(it.rev) + " in " + range + " days, " + it.today + " today; cost "
                + c(it.cogs) + (it.missed > 0 ? "; " + it.missed + " sales missed (sold out)" : ""))));
            y += 11;
        }
        if (items.size() > rows) UiStyle.scrollBar(g, x + inner + 3, top + HEAD + 12 + ch + 18, top + h - FOOT - 4 - (top + HEAD + 12 + ch + 18), scroll, items.size() - rows, rows);
    }

    private void costs(GuiGraphics g, int x, int y, int inner, int bh, int mx, int my) {
        long[][] days = days();
        int lw = Math.min(150, inner / 3), cw = inner - lw - 8;
        stacked(g, x, y, cw, bh - 2, days, mx, my);
        // the breakdown
        long[] span = data.getLongArray("span");
        if (span.length < 7) return;
        int lx = x + cw + 8, ly = y;
        long rev = span[1], total = span[2] + span[3] + span[4] + span[5];
        UiStyle.heading(g, font, "In " + data.getInt("range") + " days", lx, ly, lw);
        ly += 13;
        String[] names = { "Stock (goods sold)", "Wages", "Rent and tax", "Other" };
        int[] colours = { STOCK, WAGES, RENT, OTHER };
        for (int i = 0; i < 4; i++) {
            long v = span[2 + i];
            g.fill(lx, ly + 1, lx + 7, ly + 8, colours[i]);
            small(g, names[i], lx + 10, ly, UiStyle.TEXT, lw - 60);
            String right = c(v) + (rev > 0 ? " " + v * 100 / rev + "%" : "");
            small(g, right, lx + lw - (int) (font.width(right) * 0.75F), ly, UiStyle.TEXT_MUTED, 60);
            ly += 10;
        }
        g.fill(lx, ly, lx + lw, ly + 1, UiStyle.EDGE_SOFT);
        ly += 3;
        ly = fact(g, "Costs", c(total), lx, ly, lw);
        ly = fact(g, "Takings", c(rev), lx, ly, lw);
        long net = span[6];
        small(g, "Profit", lx, ly, UiStyle.TEXT_FAINT, lw);
        g.drawString(font, c(net) + (rev > 0 ? " (" + net * 100 / rev + "%)" : ""), lx, ly + 8, net >= 0 ? UiStyle.SUCCESS : UiStyle.DANGER, false);
        ly += 22;
        small(g, "Percent of takings. The bars stack each day's costs;", lx, ly, UiStyle.TEXT_FAINT, lw);
        small(g, "the line is the day's profit.", lx, ly + 8, UiStyle.TEXT_FAINT, lw);
    }

    private void stock(GuiGraphics g, int x, int y, int inner, int bh, int mx, int my) {
        List<Item> items = items();
        items.removeIf(it -> it.onHand == 0 && it.units == 0 && it.missed == 0);
        items.sort(Comparator.comparingLong((Item it) -> -(it.units * 1000 + it.onHand)));
        int range = data.getInt("range");
        String[] heads = { "Ware", "On hand", "Sells a day", "Days of cover", "Missed", "Order" };
        int[] cols = { 0, inner * 34 / 100, inner * 48 / 100, inner * 63 / 100, inner * 78 / 100, inner * 89 / 100 };
        for (int i = 0; i < heads.length; i++) small(g, heads[i], x + cols[i], y, UiStyle.TEXT_FAINT, (i + 1 < cols.length ? cols[i + 1] : inner) - cols[i] - 2);
        y += 11;
        long atCost = 0;
        for (Item it : items) atCost += it.stockCost;
        int rows = Math.max(1, (top + h - FOOT - 16 - y) / 11);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - rows)));
        if (items.isEmpty()) UiStyle.empty(g, font, "Nothing on its shelves", "Order stock, or make it.", x, y, inner, top + h - FOOT - 16 - y);
        for (int i = scroll; i < items.size() && i < scroll + rows; i++) {
            Item it = items.get(i);
            if ((i - scroll) % 2 == 0) g.fill(x - 2, y - 1, x + inner + 2, y + 10, 0x10000000);
            double perDay = it.units / (double) range;
            double cover = perDay <= 0 ? -1 : it.onHand / perDay;
            long order = perDay <= 0 ? 0 : Math.max(0, Math.round(perDay * 7 - it.onHand));
            cell(g, it.name, x + cols[0], y, cols[1] - 4, UiStyle.TEXT);
            cell(g, Long.toString(it.onHand), x + cols[1], y, cols[2] - cols[1] - 4, UiStyle.TEXT);
            cell(g, fmt1(perDay), x + cols[2], y, cols[3] - cols[2] - 4, UiStyle.TEXT_MUTED);
            int coverInk = cover < 0 ? UiStyle.TEXT_FAINT : cover < 2 ? UiStyle.DANGER : cover < 5 ? UiStyle.WARNING : UiStyle.SUCCESS;
            // a little gauge of the cover, a week full
            int gw = Math.max(10, cols[4] - cols[3] - 30);
            if (cover >= 0) {
                g.fill(x + cols[3], y + 2, x + cols[3] + gw, y + 7, UiStyle.WELL);
                g.fill(x + cols[3], y + 2, x + cols[3] + (int) (gw * Math.min(1, cover / 7)), y + 7, cover < 2 ? UiStyle.DANGER_FILL : cover < 5 ? UiStyle.WARNING_FILL : UiStyle.SUCCESS_FILL);
            }
            cell(g, cover < 0 ? "no sales" : cover >= 99 ? "99+" : fmt1(cover), x + cols[3] + (cover < 0 ? 0 : gw + 3), y, 30, coverInk);
            cell(g, it.missed == 0 ? "–" : Long.toString(it.missed), x + cols[4], y, cols[5] - cols[4] - 4, it.missed > 0 ? UiStyle.DANGER : UiStyle.TEXT_FAINT);
            cell(g, order == 0 ? "–" : Long.toString(order), x + cols[5], y, inner - cols[5], order > 0 ? UiStyle.ACCENT : UiStyle.TEXT_FAINT);
            boolean over = mx >= x && mx < x + inner && my >= y - 1 && my < y + 10;
            if (over) setTip(List.of(Component.literal(it.name + ": " + it.onHand + " on hand at cost " + c(it.stockCost) + "; " + it.in + " bought in and " + it.made
                + " made in " + range + " days; sells " + fmt1(perDay) + " a day" + (order > 0 ? "; a week's stock wants " + order + " more" : "")
                + (it.missed > 0 ? "; " + it.missed + " buyers went without" : ""))));
            y += 11;
        }
        int fy = top + h - FOOT - 13;
        small(g, "Stock at cost " + c(atCost) + " · days of cover at the rate it sold over " + range + " days · Order: enough for a week", x, fy, UiStyle.TEXT_FAINT, inner);
    }

    private void staff(GuiGraphics g, int x, int y, int inner, int bh, int mx, int my) {
        long[] span = data.getLongArray("span");
        if (span.length < 15) return;
        int range = data.getInt("range");
        long open = span[12], kept = span[13], rev = span[1];
        int gap = 4, tw = (inner - gap * 3) / 4;
        String[][] tiles = {
            { "Open", hoursWords(open) },
            { "Kept", hoursWords(kept) + (open > 0 ? " (" + kept * 100 / open + "%)" : "") },
            { "Wages", c(span[3]) + (rev > 0 ? " · " + span[3] * 100 / rev + "%" : "") },
            { "Sales an hour kept", kept <= 0 ? "–" : c(rev * 60 / kept) } };
        for (int i = 0; i < 4; i++) {
            int tx = x + i * (tw + gap);
            UiStyle.tile(g, tx, y, tw, 28, UiStyle.State.of(true, false, false));
            small(g, tiles[i][0] + " · " + range + " days", tx + 4, y + 3, UiStyle.TEXT_FAINT, tw - 8);
            g.drawString(font, UiStyle.clip(font, tiles[i][1], tw - 8), tx + 4, y + 14, UiStyle.TEXT, false);
        }
        y += 34;
        if (open > 0) {
            small(g, "Kept against open, its counter's hours", x, y, UiStyle.TEXT_FAINT, inner);
            y += 9;
            g.fill(x, y, x + inner, y + 6, UiStyle.WELL);
            g.fill(x, y, x + (int) (inner * Math.min(1, kept / (double) open)), y + 6, UiStyle.SUCCESS_FILL);
            y += 12;
        }
        UiStyle.heading(g, font, "Its hands", x, y, inner);
        y += 13;
        ListTag staff = data.getList("staff", Tag.TAG_COMPOUND);
        if (staff.isEmpty()) {
            small(g, "Nobody on its books in these days: its owner keeps it, or nobody does.", x, y, UiStyle.TEXT_MUTED, inner);
            return;
        }
        int[] cols = { 0, inner * 40 / 100, inner * 58 / 100, inner * 76 / 100 };
        String[] heads = { "Hand", "Days paid", "Wages", "Now" };
        for (int i = 0; i < heads.length; i++) small(g, heads[i], x + cols[i], y, UiStyle.TEXT_FAINT, 80);
        y += 10;
        for (int i = 0; i < staff.size() && y < top + h - FOOT - 10; i++) {
            CompoundTag s = staff.getCompound(i);
            cell(g, s.getString("n"), x + cols[0], y, cols[1] - 4, UiStyle.TEXT);
            cell(g, Long.toString(s.getLong("days")), x + cols[1], y, cols[2] - cols[1] - 4, UiStyle.TEXT_MUTED);
            cell(g, c(s.getLong("pay")), x + cols[2], y, cols[3] - cols[2] - 4, UiStyle.TEXT);
            cell(g, s.getBoolean("now") ? "keeps it now" : "left", x + cols[3], y, inner - cols[3], s.getBoolean("now") ? UiStyle.SUCCESS : UiStyle.TEXT_FAINT);
            y += 11;
        }
    }

    private void customers(GuiGraphics g, int x, int y, int inner, int bh, int mx, int my) {
        long[] span = data.getLongArray("span");
        if (span.length < 15) return;
        int range = data.getInt("range");
        long trans = span[8], cust = span[9], players = span[10], repeats = span[11];
        int gap = 4, tw = (inner - gap * 3) / 4;
        String[][] tiles = {
            { "Customers", cust + " (" + fmt1(cust / (double) range) + " a day)" },
            { "Sales", trans + (cust > 0 ? " (" + fmt1(trans / (double) cust) + " each)" : "") },
            { "Came back", cust == 0 ? "–" : repeats * 100 / cust + "% of days' buyers" },
            { "Players", cust == 0 ? "–" : players + " of " + cust } };
        for (int i = 0; i < 4; i++) {
            int tx = x + i * (tw + gap);
            UiStyle.tile(g, tx, y, tw, 28, UiStyle.State.of(true, false, false));
            small(g, tiles[i][0] + " · " + range + " days", tx + 4, y + 3, UiStyle.TEXT_FAINT, tw - 8);
            g.drawString(font, UiStyle.clip(font, tiles[i][1], tw - 8), tx + 4, y + 14, UiStyle.TEXT, false);
        }
        y += 34;
        // folk against players
        if (cust > 0) {
            small(g, "Folk and players", x, y, UiStyle.TEXT_FAINT, inner);
            y += 9;
            int pw = (int) (inner * players / Math.max(1, cust));
            g.fill(x, y, x + inner - pw, y + 7, FOLK);
            g.fill(x + inner - pw, y, x + inner, y + 7, PLAYER);
            small(g, (cust - players) + " folk", x + 2, y, UiStyle.TEXT_ON_DARK, inner / 2);
            if (players > 0) small(g, players + " players", x + inner - pw + 2, y, UiStyle.TEXT_ON_DARK, Math.max(10, pw - 4));
            y += 13;
        }
        // the hours: from six in the morning round the clock
        int[] hours = data.getIntArray("hours");
        if (hours.length < 24) return;
        int ch = top + h - FOOT - 4 - y - 10;
        small(g, "Sales by the hour (" + range + " days)", x, y, UiStyle.TEXT_FAINT, inner);
        y += 9;
        UiStyle.well(g, x, y, inner, ch, UiStyle.PAGE);
        int hi = 1;
        for (int v : hours) hi = Math.max(hi, v);
        int cw = (inner - 4) / 24;
        for (int k = 0; k < 24; k++) {
            int hr = (k + 6) % 24;
            int v = hours[hr];
            int bx = x + 2 + k * cw, bhh = (int) ((long) v * (ch - 4) / hi);
            boolean over = mx >= bx && mx < bx + cw && my >= y && my < y + ch;
            g.fill(bx + 1, y + ch - 2 - bhh, bx + cw - 1, y + ch - 2, over ? Ui.lighten(UNITS, 0.3F) : UNITS);
            if (over) setTip(List.of(Component.literal(hourWords(hr) + ": " + v + " sales")));
            if (k % 3 == 0) small(g, hr + "h", bx, y + ch + 1, UiStyle.TEXT_FAINT, cw * 3);
        }
    }

    private void all(GuiGraphics g, int x, int y, int inner, int bh, int mx, int my) {
        ListTag firms = data.getList("firms", Tag.TAG_COMPOUND);
        String[] heads = { "Business", "Week's takings", "Profit", "Margin", "Last week", "Till" };
        int[] cols = { 0, inner * 38 / 100, inner * 54 / 100, inner * 68 / 100, inner * 78 / 100, inner * 90 / 100 };
        for (int i = 0; i < heads.length; i++) small(g, heads[i], x + cols[i], y, UiStyle.TEXT_FAINT, (i + 1 < cols.length ? cols[i + 1] : inner) - cols[i] - 2);
        y += 11;
        long hiRev = 1;
        for (int i = 0; i < firms.size(); i++) hiRev = Math.max(hiRev, firms.getCompound(i).getLongArray("v")[0]);
        long sumRev = 0, sumNet = 0;
        for (int i = 0; i < firms.size() && y < top + h - FOOT - 24; i++) {
            CompoundTag f = firms.getCompound(i);
            long[] v = java.util.Arrays.copyOf(f.getLongArray("v"), 6);
            sumRev += v[0];
            sumNet += v[1];
            boolean me = f.getString("id").equals(data.getString("id"));
            boolean over = mx >= x && mx < x + inner && my >= y - 1 && my < y + 20;
            UiStyle.tile(g, x - 2, y - 1, inner + 4, 20, UiStyle.State.of(true, me, over));
            cell(g, f.getString("n"), x + cols[0] + 2, y + 1, cols[1] - 6, UiStyle.TEXT);
            small(g, f.getString("kind") + " · " + f.getString("town"), x + cols[0] + 2, y + 11, UiStyle.TEXT_FAINT, cols[1] - 6);
            int rw = cols[2] - cols[1] - 6;
            g.fill(x + cols[1], y + 11, x + cols[1] + (int) (rw * v[0] / hiRev), y + 15, Ui.lighten(REV, 0.15F));
            cell(g, c(v[0]), x + cols[1], y + 1, rw, UiStyle.TEXT);
            cell(g, c(v[1]), x + cols[2], y + 1, cols[3] - cols[2] - 4, v[1] >= 0 ? UiStyle.SUCCESS : UiStyle.DANGER);
            cell(g, v[0] == 0 ? "–" : v[1] * 100 / v[0] + "%", x + cols[3], y + 1, cols[4] - cols[3] - 4, UiStyle.TEXT_MUTED);
            int tx = x + cols[4];
            UiStyle.trend(g, font, Long.compare(v[1], v[3]), true, tx, y + 1);
            cell(g, c(v[3]), tx + 10, y + 1, cols[5] - cols[4] - 14, UiStyle.TEXT_FAINT);
            cell(g, c(v[4]), x + cols[5], y + 1, inner - cols[5], UiStyle.TEXT_MUTED);
            String id = f.getString("id");
            zone(x - 2, y - 1, inner + 4, 20, () -> { UiStyle.click(); tab = 0; ask(id, data.getInt("range")); });
            if (over) setTip(List.of(Component.literal(f.getString("n") + ": " + v[5] + " customers this week. Click for its data.")));
            y += 22;
        }
        int fy = top + h - FOOT - 13;
        g.drawString(font, "All told this week: takings " + c(sumRev) + ", profit " + c(sumNet), x, fy, sumNet >= 0 ? UiStyle.SUCCESS : UiStyle.DANGER, false);
    }

    // ------------------------------------------------------------------ charts

    /**
     * Bars a day in a well, with the value of the tallest at its head, nought on its baseline, the first and last days
     * under it, and (if given) a line over the bars on the same scale (profit, which may go under nought). The day under the
     * mouse is lit and its numbers shown.
     */
    private void chart(GuiGraphics g, int x, int y, int cw, int ch, long[] bars, int colour, long[] line, boolean money, int mx, int my, String title) {
        small(g, title, x, y, UiStyle.TEXT_FAINT, cw);
        y += 9;
        ch -= 9 + 9;
        if (ch < 16) return;
        int axis = 28;
        int px = x + axis, pw = cw - axis;
        UiStyle.well(g, px, y, pw, ch, UiStyle.PAGE);
        long hi = 1, lo = 0;
        for (long v : bars) hi = Math.max(hi, v);
        if (line != null) for (long v : line) { hi = Math.max(hi, v); lo = Math.min(lo, v); }
        hi = nice(hi);
        int n = bars.length;
        if (n == 0) return;
        int baseY = y + 2 + (int) ((ch - 4) * hi / (double) (hi - lo));
        // grid: the head, the middle, nought
        for (int k = 0; k <= 2; k++) {
            long v = hi - (hi - lo) * k / 2;
            int gy = y + 2 + (int) ((ch - 4) * (hi - v) / (double) (hi - lo));
            g.fill(px + 1, gy, px + pw - 1, gy + 1, GRID);
            String lab = money ? cShort(v) : Long.toString(v);
            small(g, lab, px - 2 - (int) (font.width(lab) * 0.75F), gy - 3, UiStyle.TEXT_FAINT, axis);
        }
        if (lo < 0) {
            g.fill(px + 1, baseY, px + pw - 1, baseY + 1, 0x55000000);
            small(g, "0", px - 2 - (int) (font.width("0") * 0.75F), baseY - 3, UiStyle.TEXT_FAINT, axis);
        }
        double colW = (pw - 4) / (double) n;
        int hover = -1;
        for (int i = 0; i < n; i++) {
            int bx0 = px + 2 + (int) (i * colW), bx1 = px + 2 + (int) ((i + 1) * colW) - (colW >= 4 ? 1 : 0);
            boolean over = mx >= bx0 && mx < Math.max(bx0 + 1, bx1) && my >= y && my < y + ch;
            if (over) { hover = i; g.fill(bx0, y + 1, Math.max(bx0 + 1, bx1), y + ch - 1, 0x18000000); }
            long v = Math.max(0, bars[i]);
            int top0 = baseY - (int) ((baseY - y - 2) * v / (double) hi);
            if (v > 0) g.fill(bx0, top0, Math.max(bx0 + 1, bx1), baseY, over ? Ui.lighten(colour, 0.25F) : colour);
        }
        if (line != null) plot(g, px, y, pw, ch, line, hi, lo, colW);
        long[][] days = days();
        if (days.length == n) {
            small(g, "day " + (days[0][0] + 1), px, y + ch + 1, UiStyle.TEXT_FAINT, pw / 2);
            String last = "today";
            small(g, last, px + pw - (int) (font.width(last) * 0.75F), y + ch + 1, UiStyle.TEXT_FAINT, 40);
            if (hover >= 0) dayTip(days[hover]);
        }
    }

    /** The units a day as a blue line of dots over the takings, on their own scale (its head marked on the right). */
    private void overlayUnits(GuiGraphics g, int x, int y, int cw, int ch, long[] units) {
        y += 9;
        ch -= 18;
        if (ch < 16 || units.length == 0) return;
        int axis = 28, px = x + axis, pw = cw - axis;
        long hi = 1;
        for (long v : units) hi = Math.max(hi, v);
        hi = nice(hi);
        double colW = (pw - 4) / (double) units.length;
        int prevX = -1, prevY = -1;
        for (int i = 0; i < units.length; i++) {
            int cx = px + 2 + (int) ((i + 0.5) * colW);
            int cy = y + ch - 2 - (int) ((ch - 4) * units[i] / (double) hi);
            if (prevX >= 0) line(g, prevX, prevY, cx, cy, UNITS);
            g.fill(cx - 1, cy - 1, cx + 2, cy + 2, UNITS);
            prevX = cx;
            prevY = cy;
        }
        String lab = hi + " units";
        small(g, lab, px + pw - 2 - (int) (font.width(lab) * 0.75F), y + 2, UNITS, 60);
    }

    /** Each day's costs stacked (stock, wages, rent and tax, other) with the profit line over them; a day under the mouse lit. */
    private void stacked(GuiGraphics g, int x, int y, int cw, int ch, long[][] days, int mx, int my) {
        small(g, "Costs a day, stacked; the line is profit", x, y, UiStyle.TEXT_FAINT, cw);
        y += 9;
        ch -= 18;
        if (ch < 16 || days.length == 0) return;
        int axis = 28, px = x + axis, pw = cw - axis;
        UiStyle.well(g, px, y, pw, ch, UiStyle.PAGE);
        long hi = 1, lo = 0;
        for (long[] d : days) {
            hi = Math.max(hi, d[2] + d[3] + d[4] + d[5]);
            hi = Math.max(hi, d[6]);
            lo = Math.min(lo, d[6]);
        }
        hi = nice(hi);
        int baseY = y + 2 + (int) ((ch - 4) * hi / (double) (hi - lo));
        for (int k = 0; k <= 2; k++) {
            long v = hi - (hi - lo) * k / 2;
            int gy = y + 2 + (int) ((ch - 4) * (hi - v) / (double) (hi - lo));
            g.fill(px + 1, gy, px + pw - 1, gy + 1, GRID);
            String lab = cShort(v);
            small(g, lab, px - 2 - (int) (font.width(lab) * 0.75F), gy - 3, UiStyle.TEXT_FAINT, axis);
        }
        if (lo < 0) g.fill(px + 1, baseY, px + pw - 1, baseY + 1, 0x55000000);
        double colW = (pw - 4) / (double) days.length;
        int[] colours = { STOCK, WAGES, RENT, OTHER };
        int hover = -1;
        for (int i = 0; i < days.length; i++) {
            int bx0 = px + 2 + (int) (i * colW), bx1 = Math.max(bx0 + 1, px + 2 + (int) ((i + 1) * colW) - (colW >= 4 ? 1 : 0));
            boolean over = mx >= bx0 && mx < bx1 && my >= y && my < y + ch;
            if (over) { hover = i; g.fill(bx0, y + 1, bx1, y + ch - 1, 0x18000000); }
            int yy = baseY;
            for (int s = 0; s < 4; s++) {
                long v = Math.max(0, days[i][2 + s]);
                int hgt = (int) Math.round((baseY - y - 2) * v / (double) hi);
                if (hgt > 0) g.fill(bx0, yy - hgt, bx1, yy, over ? Ui.lighten(colours[s], 0.25F) : colours[s]);
                yy -= hgt;
            }
        }
        plot(g, px, y, pw, ch, col(days, 6), hi, lo, colW);
        small(g, "day " + (days[0][0] + 1), px, y + ch + 1, UiStyle.TEXT_FAINT, pw / 2);
        small(g, "today", px + pw - (int) (font.width("today") * 0.75F), y + ch + 1, UiStyle.TEXT_FAINT, 40);
        if (hover >= 0) dayTip(days[hover]);
    }

    /** A line through the days' values (profit: dark above nought, red under). */
    private void plot(GuiGraphics g, int px, int y, int pw, int ch, long[] vals, long hi, long lo, double colW) {
        int prevX = -1, prevY = -1;
        for (int i = 0; i < vals.length; i++) {
            int cx = px + 2 + (int) ((i + 0.5) * colW);
            int cy = y + 2 + (int) ((ch - 4) * (hi - vals[i]) / (double) (hi - lo));
            int ink = vals[i] < 0 ? LOSS : PROFIT;
            if (prevX >= 0) line(g, prevX, prevY, cx, cy, ink);
            g.fill(cx - 1, cy - 1, cx + 1, cy + 1, ink);
            prevX = cx;
            prevY = cy;
        }
    }

    /** A 1px line. */
    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int ink) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), steps = Math.max(dx, dy);
        if (steps == 0) { g.fill(x0, y0, x0 + 1, y0 + 1, ink); return; }
        for (int k = 0; k <= steps; k++) {
            int xx = x0 + (x1 - x0) * k / steps, yy = y0 + (y1 - y0) * k / steps;
            g.fill(xx, yy, xx + 1, yy + 1, ink);
        }
    }

    private void dayTip(long[] d) {
        long rev = d[1], costs = d[2] + d[3] + d[4] + d[5];
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Day " + (d[0] + 1) + (d[0] == data.getLong("today") ? " (today)" : "")));
        lines.add(Component.literal("Takings " + c(rev) + " · " + d[7] + " items in " + d[8] + " sales to " + d[9] + " customers"));
        lines.add(Component.literal("Costs " + c(costs) + ": stock " + c(d[2]) + ", wages " + c(d[3]) + ", rent and tax " + c(d[4]) + ", other " + c(d[5])));
        lines.add(Component.literal("Profit " + c(d[6]) + (d[14] > 0 ? " · " + d[14] + " sales missed" : "")));
        setTip(lines);
    }

    // ------------------------------------------------------------------ the data

    record Item(String key, String name, long units, long rev, long cogs, long made, long in, long missed, long today, long onHand, long stockCost) {}

    private List<Item> items() {
        List<Item> out = new ArrayList<>();
        ListTag l = data.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag c = l.getCompound(i);
            long[] v = java.util.Arrays.copyOf(c.getLongArray("v"), 10);
            out.add(new Item(c.getString("k"), c.getString("n"), v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]));
        }
        return out;
    }

    private long[][] days() {
        ListTag l = data.getList("days", Tag.TAG_LONG_ARRAY);
        long[][] out = new long[l.size()][];
        for (int i = 0; i < l.size(); i++) out[i] = java.util.Arrays.copyOf(((net.minecraft.nbt.LongArrayTag) l.get(i)).getAsLongArray(), 15);
        return out;
    }

    private static long[] col(long[][] rows, int k) {
        long[] out = new long[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = rows[i][k];
        return out;
    }

    private static long margin(long[] p) {
        return p[0] == 0 ? 0 : p[2] * 100 / p[0];
    }

    /** A round head for a chart's scale. */
    private static long nice(long v) {
        if (v <= 10) return 10;
        long mag = 1;
        while (mag * 10 <= v) mag *= 10;
        for (long m : new long[]{ 1, 2, 5, 10 }) if (m * mag >= v) return m * mag;
        return v;
    }

    private static String c(long pence) {
        return String.format(Locale.ROOT, "%,.2fc", pence / 100.0);
    }

    /** Coin for an axis: "12c", "1.5kc". */
    private static String cShort(long pence) {
        double c = pence / 100.0;
        if (Math.abs(c) >= 1000) return String.format(Locale.ROOT, "%.1fk", c / 1000);
        if (Math.abs(c) >= 10) return String.format(Locale.ROOT, "%.0fc", c);
        return String.format(Locale.ROOT, "%.1fc", c);
    }

    private static String fmt1(double v) {
        return v >= 100 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String hourWords(int h) {
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + (h < 12 ? " in the morning" : h < 18 ? " in the afternoon" : " at night");
    }

    private static String hoursWords(long minutes) {
        return minutes < 60 ? minutes + " min" : String.format(Locale.ROOT, "%.1f h", minutes / 60.0);
    }

    // ------------------------------------------------------------------ bits

    private int fact(GuiGraphics g, String label, String value, int x, int y, int w) {
        small(g, label, x, y, UiStyle.TEXT_FAINT, w);
        g.drawString(font, UiStyle.clip(font, value, w), x, y + 8, UiStyle.TEXT, false);
        return y + 20;
    }

    /** A table cell: full-size text where the panel is wide, small where it is not. */
    private void cell(GuiGraphics g, String s, int x, int y, int max, int colour) {
        if (w >= 460) g.drawString(font, UiStyle.clip(font, s, max), x, y, colour, false);
        else small(g, s, x, y + 1, colour, max);
    }

    private void button(GuiGraphics g, String label, int x, int y, int bw, int mx, int my, boolean enabled, Runnable act) {
        boolean over = mx >= x && mx < x + bw && my >= y && my < y + 14;
        UiStyle.button(g, font, label, x, y, bw, 14, UiStyle.State.of(enabled, !enabled, over));
        if (enabled) zone(x, y, bw, 14, () -> { UiStyle.click(); act.run(); });
    }

    private void zone(int x, int y, int zw, int zh, Runnable act) {
        boxes.add(new int[]{ x, y, x + zw, y + zh });
        acts.add(act);
    }

    private void command(String c) {
        if (Minecraft.getInstance().player != null) com.jrpetty.mcassistant.net.LifeNetwork.command(c);
        UiStyle.click();
    }

    private void small(GuiGraphics g, String s, int x, int y, int colour, int max) {
        String cut = s;
        while (cut.length() > 1 && font.width(cut) * 0.75F > max) cut = cut.substring(0, cut.length() - 1);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, cut.length() < s.length() ? cut + "…" : s, 0, 0, colour, false);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        for (int i = boxes.size() - 1; i >= 0; i--) {
            int[] b = boxes.get(i);
            if (mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3]) {
                acts.get(i).run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll = Math.max(0, scroll - (int) Math.signum(dy));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_7) {
            int t = key - GLFW.GLFW_KEY_1;
            if (data.getBoolean("full") && (t < 6 || hasAll())) { tab = t; scroll = 0; return true; }
        }
        return super.keyPressed(key, scan, mods);
    }
}
