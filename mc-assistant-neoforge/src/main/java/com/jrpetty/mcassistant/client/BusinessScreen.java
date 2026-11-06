package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [business-books] A business's books on a page (entity/BusinessBooks.books): who owns it and who works there and what
 * each is paid; today, this week and all time (takings, cost of goods, margin, wages, rent, running costs, taxes,
 * losses, interest, net profit, the owners' drawings); a chart of the last fourteen days; item by item what it sold,
 * for what, what it cost, the margin, and how one is made (its last batch's ingredients and their cost, and the standard
 * cost with its labour); its stock at cost; what it bought from whom at what price; and why its prices moved. Or, sent
 * with "list", the town's businesses a line each, a click to any one's books.
 *
 * <p>Its buttons are commands (/village business ...), so they work typed too.
 */
public class BusinessScreen extends Screen {

    private static final String[] TABS = { "Summary", "Wares", "Costs", "Stock & orders", "Notes" };
    private static final int PAD = 8, HEAD = 34;

    private CompoundTag data;
    private int left, top, w, h, tab, scroll;
    private final List<int[]> boxes = new ArrayList<>();
    private final List<Runnable> acts = new ArrayList<>();
    private EditBox item, qty;

    public BusinessScreen(CompoundTag data) {
        super(Component.literal("Business books"));
        this.data = data;
    }

    /** The server's answer: open the books, or bring the open page up to date. */
    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof BusinessScreen open && open.data.getString("id").equals(data.getString("id")) && !data.getBoolean("list")) {
            open.data = data;
            open.rebuildWidgets();
            return;
        }
        mc.setScreen(new BusinessScreen(data));
    }

    private boolean list() {
        return data.getBoolean("list");
    }

    @Override
    protected void init() {
        w = Math.min(420, width - 8);
        h = Math.min(272, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        item = null;
        qty = null;
        if (!list() && tab == 3 && data.getBoolean("full") && (data.getBoolean("mine") || data.getBoolean("admin")) && !data.getBoolean("public")) {
            item = new EditBox(font, left + PAD + 70, top + h - 22, 90, 14, Component.literal("Item"));
            item.setHint(Component.literal("wheat").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            item.setMaxLength(48);
            qty = new EditBox(font, left + PAD + 164, top + h - 22, 40, 14, Component.literal("How many"));
            qty.setHint(Component.literal("30").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            qty.setFilter(s -> s.matches("\\d{0,5}"));
            addRenderableWidget(item);
            addRenderableWidget(qty);
        }
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
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        UiStyle.headerBand(g, left, top, w, HEAD - 6);
        int x = left + PAD, inner = w - PAD * 2;
        if (list()) {
            UiStyle.title(g, font, "Businesses of " + data.getString("town"), x, top + 6);
            small(g, "Click a business for its books. Every coin is counted: " + data.getString("census"), x, top + 18, UiStyle.TEXT_MUTED, inner);
            drawList(g, x, top + HEAD, inner, mx, my);
        } else {
            String title = data.getString("name") + (data.getBoolean("open") ? "" : " (closed)");
            UiStyle.title(g, font, title, x, top + 6);
            small(g, data.getString("type") + " of " + data.getString("town") + " · owned by " + data.getString("ownersWords") + " · since day "
                + data.getLong("opened"), x, top + 18, UiStyle.TEXT_MUTED, inner - 40);
            button(g, "List", left + w - PAD - 30, top + 6, 30, mx, my, () -> command("village business"));
            int tx = x;
            for (int i = 0; i < TABS.length; i++) {
                int tw = font.width(TABS[i]) + 10;
                boolean over = mx >= tx && mx < tx + tw && my >= top + HEAD - 6 && my < top + HEAD + 8;
                UiStyle.topTab(g, tx, top + HEAD - 6, tw, 14, UiStyle.State.of(true, i == tab, over));
                g.drawString(font, TABS[i], tx + 5, top + HEAD - 3, UiStyle.TEXT, false);
                int k = i;
                zone(tx, top + HEAD - 6, tw, 14, () -> { tab = k; scroll = 0; rebuildWidgets(); });
                tx += tw + 2;
            }
            int y = top + HEAD + 12;
            if (!data.getBoolean("full") && tab > 0) {
                small(g, "The full books are for the owners, the town's citizens and its friends.", x, y, UiStyle.TEXT_MUTED, inner);
            } else {
                switch (tab) {
                    case 0 -> summary(g, x, y, inner, mx, my);
                    case 1 -> wares(g, x, y, inner, mx, my);
                    case 2 -> costs(g, x, y, inner);
                    case 3 -> stock(g, x, y, inner, mx, my);
                    default -> notes(g, x, y, inner);
                }
            }
        }
        for (var r : renderables) r.render(g, mx, my, pt);
    }

    private void drawList(GuiGraphics g, int x, int y, int inner, int mx, int my) {
        int[] cols = { 0, 120, 210, 250, 300, 350 };
        String[] heads = { "Business", "Owner", "Staff", "Week took", "Week net", "Till" };
        for (int i = 0; i < heads.length; i++) small(g, heads[i], x + cols[i], y, UiStyle.TEXT_FAINT, 200);
        y += 10;
        ListTag rows = data.getList("rows", Tag.TAG_COMPOUND);
        int shown = 0;
        for (int i = scroll; i < rows.size() && y < top + h - 14; i++) {
            CompoundTag r = rows.getCompound(i);
            boolean over = mx >= x && mx < x + inner && my >= y - 1 && my < y + 10;
            if (over) g.fill(x - 2, y - 1, x + inner, y + 9, UiStyle.TILE_HOVER);
            small(g, r.getString("name") + (r.getBoolean("open") ? "" : " (closed)"), x + cols[0], y, UiStyle.TEXT, 115);
            small(g, r.getBoolean("town") ? "the town" : r.getString("owner"), x + cols[1], y, r.getBoolean("town") ? UiStyle.INFO : UiStyle.TEXT_MUTED, 85);
            small(g, Integer.toString(r.getInt("staff")), x + cols[2], y, UiStyle.TEXT_MUTED, 30);
            small(g, p(r.getLong("rev7")), x + cols[3], y, UiStyle.TEXT, 50);
            long net = r.getLong("net7");
            small(g, p(net), x + cols[4], y, net >= 0 ? UiStyle.SUCCESS : UiStyle.DANGER, 50);
            small(g, r.getBoolean("mirror") ? "own books" : p(r.getLong("till")), x + cols[5], y, UiStyle.TEXT_MUTED, 60);
            String id = r.getString("id");
            zone(x, y - 1, inner, 10, () -> command("village business books " + id));
            y += 11;
            shown++;
        }
        if (rows.isEmpty()) small(g, "No businesses yet: a café, a shop or a tavern built makes the first.", x, y, UiStyle.TEXT_MUTED, inner);
    }

    private void summary(GuiGraphics g, int x, int y, int inner, int mx, int my) {
        int half = inner / 2 - 4;
        // The figures, three columns: today, the week, all time.
        String[] rowsWords = { "Takings", "Cost of goods", "Gross margin", "Wages", "Rent", "Running costs", "Taxes", "Losses", "Interest", "Net profit",
            "Owners drew" };
        String[] keys = { "revenue", "cogs", "gross", "wages", "rent", "other", "taxes", "losses", "interest", "net", "drawings" };
        CompoundTag[] per = { data.getCompound("today"), data.getCompound("week"), data.getCompound("all") };
        String[] perName = { "Today", "Week", "All time" };
        boolean full = data.getBoolean("full");
        small(g, "", x, y, 0, 10);
        for (int c = 0; c < 3; c++) if (full || c == 1) small(g, perName[c], x + 72 + c * 48, y, UiStyle.TEXT_FAINT, 46);
        int ry = y + 9;
        for (int i = 0; i < keys.length; i++) {
            boolean bold = keys[i].equals("net") || keys[i].equals("gross");
            small(g, rowsWords[i], x, ry, bold ? UiStyle.TEXT : UiStyle.TEXT_MUTED, 70);
            for (int c = 0; c < 3; c++) {
                if (!full && c != 1) continue;
                long v = per[c].getLong(keys[i]);
                int col = keys[i].equals("net") ? (v >= 0 ? UiStyle.SUCCESS : UiStyle.DANGER) : UiStyle.TEXT;
                small(g, p(v), x + 72 + c * 48, ry, col, 46);
            }
            ry += 9;
        }
        ry += 4;
        if (full) {
            small(g, "Till " + p(data.getLong("till")) + (data.getLong("account") < 0 ? " · owes the stores " + p(-data.getLong("account")) + " (credit "
                + p(data.getLong("credit")) + ")" : "") + (data.getLong("loan") > 0 ? " · owes the " + data.getString("lender") + " " + p(data.getLong("loan")) : ""),
                x, ry, UiStyle.TEXT, half + 20);
            ry += 9;
            small(g, "Stock " + data.getLong("stockUnits") + " at cost " + p(data.getLong("stockCost")) + " · rent " + p(data.getLong("rent")) + " a day · markup "
                + Math.round(data.getDouble("markup") * 100) + "% · worth " + p(data.getLong("worth")), x, ry, UiStyle.TEXT_MUTED, half + 20);
            ry += 9;
            if (data.getBoolean("public") && !data.getString("census").isEmpty()) {
                for (FormattedCharSequence line : font.split(Component.literal("The town's coin: " + data.getString("census")), (int) ((half + 20) / 0.75F))) {
                    if (ry > top + h - 24) break;
                    smallSeq(g, line, x, ry, UiStyle.TEXT_FAINT);
                    ry += 8;
                }
            }
        }
        // The owners and the staff, on the right.
        int rx = x + half + 26, rw = inner - half - 26;
        Ui.section(g, font, "Owners", rx, y, rw);
        int sy = y + 12;
        ListTag owners = data.getList("owners", Tag.TAG_COMPOUND);
        for (int i = 0; i < owners.size(); i++) {
            CompoundTag o = owners.getCompound(i);
            small(g, o.getString("name") + " (" + o.getString("holder") + ") " + String.format(Locale.ROOT, "%.1f%%", o.getInt("parts") / 100.0), rx, sy,
                UiStyle.TEXT, rw);
            sy += 9;
        }
        sy += 3;
        Ui.section(g, font, "Staff and pay", rx, sy, rw);
        sy += 12;
        ListTag staff = data.getList("staff", Tag.TAG_COMPOUND);
        if (staff.isEmpty()) small(g, "No hands of its own.", rx, sy, UiStyle.TEXT_FAINT, rw);
        for (int i = 0; i < staff.size() && sy < top + h - 60; i++) {
            CompoundTag s = staff.getCompound(i);
            small(g, s.getString("name") + ", " + s.getString("job") + ": " + s.getInt("wage") + "c a day"
                + (full ? ", paid " + p(s.getLong("paidWeek")) + " this week" : "") + (s.getBoolean("short") ? " (short time)" : ""), rx, sy, UiStyle.TEXT_MUTED, rw);
            sy += 9;
        }
        // The chart: fourteen days, takings and the day's net.
        if (full) chart(g, rx, top + h - 56, rw, 40, mx, my);
        // The owner's buttons.
        int by = top + h - 16;
        if (data.getBoolean("mine") && data.getLong("due") >= 100) {
            button(g, "Take drawings " + p(data.getLong("due")), x, by, 110, mx, my, () -> command("village business draw " + data.getString("id")));
        } else if (!data.getBoolean("public") && !data.getBoolean("mine")) {
            button(g, "Buy it (" + p(data.getLong("worth")) + "+)", x, by, 110, mx, my, () -> command("village business buy " + data.getString("id")));
            button(g, "Invest 20c", x + 114, by, 60, mx, my, () -> command("village business invest " + data.getString("id") + " 20"));
        }
    }

    private void chart(GuiGraphics g, int x, int y, int w, int hgt, int mx, int my) {
        ListTag days = data.getList("chart", Tag.TAG_COMPOUND);
        if (days.isEmpty()) return;
        small(g, "Last 14 days: takings, and the day's net", x, y - 9, UiStyle.TEXT_FAINT, w);
        long most = 1;
        for (int i = 0; i < days.size(); i++) {
            most = Math.max(most, days.getCompound(i).getLong("rev"));
            most = Math.max(most, Math.abs(days.getCompound(i).getLong("net")));
        }
        UiStyle.well(g, x, y, w, hgt);
        int mid = y + hgt * 2 / 3;
        g.fill(x + 1, mid, x + w - 1, mid + 1, UiStyle.EDGE_SOFT);
        int bw = Math.max(2, (w - 4) / days.size());
        for (int i = 0; i < days.size(); i++) {
            CompoundTag d = days.getCompound(i);
            int bx = x + 2 + i * bw;
            int rh = (int) ((mid - y - 2) * d.getLong("rev") / most);
            if (rh > 0) g.fill(bx, mid - rh, bx + bw / 2, mid, UiStyle.INFO_FILL);
            long net = d.getLong("net");
            int nh = (int) ((net >= 0 ? (mid - y - 2) : (y + hgt - mid - 2)) * Math.abs(net) / most);
            if (net >= 0 && nh > 0) g.fill(bx + bw / 2, mid - nh, bx + bw - 1, mid, UiStyle.SUCCESS_FILL);
            if (net < 0 && nh > 0) g.fill(bx + bw / 2, mid + 1, bx + bw - 1, mid + 1 + nh, UiStyle.DANGER_FILL);
            if (mx >= bx && mx < bx + bw && my >= y && my < y + hgt) {
                setTooltipForNextRenderPass(Component.literal("Day " + d.getLong("day") + ": took " + p(d.getLong("rev")) + ", net " + p(net)));
            }
        }
    }

    private void wares(GuiGraphics g, int x, int y, int inner, int mx, int my) {
        int[] cols = { 0, 92, 120, 162, 204, 246, 290, 340 };
        String[] heads = { "This week", "Sold", "Takings", "Cost", "Margin", "Cost each", "Price each", "Made/used" };
        for (int i = 0; i < heads.length; i++) small(g, heads[i], x + cols[i], y, UiStyle.TEXT_FAINT, 60);
        y += 10;
        ListTag items = data.getList("items", Tag.TAG_COMPOUND);
        for (int i = scroll; i < items.size() && y < top + h - 12; i++) {
            CompoundTag r = items.getCompound(i);
            boolean over = mx >= x && mx < x + inner && my >= y - 1 && my < y + 9;
            if (over) {
                g.fill(x - 2, y - 1, x + inner, y + 8, UiStyle.TILE_HOVER);
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(r.getString("name")));
                if (r.contains("card")) tip.add(Component.literal("Last batch: " + r.getString("card")));
                if (r.contains("standard")) tip.add(Component.literal("Standard cost: " + r.getString("standard")));
                if (r.contains("full")) tip.add(Component.literal("Full cost here (with its share of wages, rent and running): "
                    + String.format(Locale.ROOT, "%.2fc", r.getDouble("full")) + "; asks " + String.format(Locale.ROOT, "%.2fc", r.getDouble("price"))
                    + (r.getInt("trend") > 0 ? " (going price rising)" : r.getInt("trend") < 0 ? " (going price falling)" : "")));
                if (r.getBoolean("dropped")) tip.add(Component.literal("No longer ordered: it lost money."));
                List<FormattedCharSequence> lines = new ArrayList<>();
                for (Component c : tip) lines.addAll(font.split(c, 260));
                setTooltipForNextRenderPass(lines);
            }
            small(g, r.getString("name"), x + cols[0], y, UiStyle.TEXT, 88);
            small(g, Long.toString(r.getLong("units")), x + cols[1], y, UiStyle.TEXT, 26);
            small(g, p(r.getLong("revenue")), x + cols[2], y, UiStyle.TEXT, 40);
            small(g, p(r.getLong("cogs")), x + cols[3], y, UiStyle.TEXT_MUTED, 40);
            long m = r.getLong("revenue") - r.getLong("cogs");
            small(g, p(m), x + cols[4], y, m >= 0 ? UiStyle.SUCCESS : UiStyle.DANGER, 40);
            small(g, String.format(Locale.ROOT, "%.2fc", r.getDouble("each")), x + cols[5], y, UiStyle.TEXT_MUTED, 42);
            small(g, r.contains("price") ? String.format(Locale.ROOT, "%.2fc", r.getDouble("price")) : "", x + cols[6], y, UiStyle.TEXT_MUTED, 46);
            small(g, r.getLong("made") + "/" + r.getLong("used"), x + cols[7], y, UiStyle.TEXT_FAINT, 40);
            y += 10;
        }
        if (items.isEmpty()) small(g, "Nothing sold or held yet.", x, y, UiStyle.TEXT_MUTED, inner);
        small(g, "Hover a ware: how one is made, its ingredients and their cost, the labour, the price and why.", x, top + h - 10, UiStyle.TEXT_FAINT, inner);
    }

    private void costs(GuiGraphics g, int x, int y, int inner) {
        CompoundTag wk = data.getCompound("week"), all = data.getCompound("all");
        List<String> lines = new ArrayList<>();
        lines.add("This week: takings " + p(wk.getLong("revenue")) + " from " + wk.getLong("units") + " sales; its stock cost " + p(wk.getLong("cogs"))
            + ", leaving a gross margin of " + p(wk.getLong("gross")) + ".");
        lines.add("Out of that: wages " + p(wk.getLong("wages")) + ", rent to the town " + p(wk.getLong("rent")) + ", running costs (lights, repairs) "
            + p(wk.getLong("other")) + ", taxes (the market toll) " + p(wk.getLong("taxes")) + ", stale stock " + p(wk.getLong("losses")) + ", interest "
            + p(wk.getLong("interest")) + ".");
        lines.add("Net profit " + p(wk.getLong("net")) + "; the owners drew " + p(wk.getLong("drawings")) + "; capital put in " + p(wk.getLong("capital"))
            + "; stock bought " + p(wk.getLong("bought")) + "; sold to the town for its own use " + p(wk.getLong("toTown")) + ".");
        lines.add("");
        lines.add("Since it opened: took " + p(all.getLong("revenue")) + ", net " + p(all.getLong("net")) + ", drawn " + p(all.getLong("drawings")) + ", wages paid "
            + p(all.getLong("wages")) + ", rent " + p(all.getLong("rent")) + ".");
        lines.add("");
        ListTag staff = data.getList("staff", Tag.TAG_COMPOUND);
        for (int i = 0; i < staff.size(); i++) {
            CompoundTag s = staff.getCompound(i);
            lines.add(s.getString("name") + " (" + s.getString("job") + "): " + s.getInt("wage") + "c a day by its worth; paid " + p(s.getLong("paidToday")) + " today, "
                + p(s.getLong("paidWeek")) + " this week" + (s.getBoolean("short") ? ", on short time (half pay)" : "") + ".");
        }
        para(g, lines, x, y, inner);
    }

    private void stock(GuiGraphics g, int x, int y, int inner, int mx, int my) {
        int half = inner / 2 - 4;
        Ui.section(g, font, "Stock on hand, at cost", x, y, half);
        int sy = y + 12;
        ListTag items = data.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size() && sy < top + h - 30; i++) {
            CompoundTag r = items.getCompound(i);
            if (r.getLong("onHand") <= 0) continue;
            small(g, r.getLong("onHand") + " " + r.getString("name") + ": " + p(r.getLong("stockCost")) + " (" + String.format(Locale.ROOT, "%.2fc", r.getDouble("each"))
                + " each)", x, sy, UiStyle.TEXT, half);
            sy += 9;
        }
        int rx = x + half + 8, rw = inner - half - 8;
        boolean store = data.getString("id").equals("storehouse");
        Ui.section(g, font, store ? "Bought in (the producers, at their wages)" : "Bought: from whom, at what", rx, y, rw);
        int oy = y + 12;
        ListTag orders = data.getList("orders", Tag.TAG_COMPOUND);
        for (int i = scroll; i < orders.size() && oy < top + h - 30; i++) {
            CompoundTag o = orders.getCompound(i);
            small(g, "Day " + o.getLong("day") + ": " + o.getLong("qty") + " " + o.getString("item") + " from " + o.getString("from") + " at "
                + String.format(Locale.ROOT, "%.2fc", o.getDouble("unit")) + " = " + p(o.getLong("total")) + (o.getLong("discount") > 0 ? " (" + p(o.getLong("discount"))
                + " off)" : ""), rx, oy, UiStyle.TEXT_MUTED, rw);
            oy += 9;
        }
        if (orders.isEmpty()) small(g, "Nothing bought yet.", rx, oy, UiStyle.TEXT_FAINT, rw);
        if (store) {
            ListTag to = data.getList("soldTo", Tag.TAG_COMPOUND);
            oy += 4;
            Ui.section(g, font, "Sold to the businesses this week", rx, oy, rw);
            oy += 12;
            for (int i = 0; i < to.size() && oy < top + h - 14; i++) {
                CompoundTag r = to.getCompound(i);
                small(g, r.getString("to") + ": " + r.getLong("qty") + " for " + p(r.getLong("total")), rx, oy, UiStyle.TEXT_MUTED, rw);
                oy += 9;
            }
        }
        if (item != null) {
            small(g, "Order from the stores:", x, top + h - 19, UiStyle.TEXT, 70);
            button(g, "Order", left + PAD + 208, top + h - 22, 40, mx, my, () -> {
                String it = item.getValue().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
                String n = qty.getValue().trim();
                if (!it.isEmpty() && !n.isEmpty()) command("village business order " + data.getString("id") + " " + it + " " + n);
            });
            small(g, "3% off 64+, 6% off 256+, 10% off 1024+", left + PAD + 252, top + h - 19, UiStyle.TEXT_FAINT, 140);
        }
    }

    private void notes(GuiGraphics g, int x, int y, int inner) {
        List<String> lines = new ArrayList<>();
        ListTag notes = data.getList("notes", Tag.TAG_STRING);
        for (int i = 0; i < notes.size(); i++) lines.add(notes.getString(i));
        if (lines.isEmpty()) lines.add("Nothing to note yet: its prices, owners and hard times are written here as they happen.");
        para(g, lines, x, y, inner);
    }

    // ------------------------------------------------------------------ bits

    private void para(GuiGraphics g, List<String> lines, int x, int y, int inner) {
        int skip = scroll;
        for (String s : lines) {
            for (FormattedCharSequence line : font.split(Component.literal(s), (int) (inner / 0.75F))) {
                if (skip-- > 0) continue;
                if (y > top + h - 12) return;
                smallSeq(g, line, x, y, UiStyle.TEXT_MUTED);
                y += 8;
            }
            if (s.isEmpty()) y += 2;
        }
    }

    private void button(GuiGraphics g, String label, int x, int y, int bw, int mx, int my, Runnable act) {
        boolean over = mx >= x && mx < x + bw && my >= y && my < y + 14;
        UiStyle.button(g, font, label, x, y, bw, 14, UiStyle.State.of(true, false, over));
        zone(x, y, bw, 14, act);
    }

    private void zone(int x, int y, int zw, int zh, Runnable act) {
        boxes.add(new int[]{ x, y, x + zw, y + zh });
        acts.add(act);
    }

    private void command(String c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(c);
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

    private void smallSeq(GuiGraphics g, FormattedCharSequence s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static String p(long pence) {
        return String.format(Locale.ROOT, "%,.2fc", pence / 100.0);
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
}
