package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The town's books, opened at the village board: how the village has grown and why.
 *
 * <p>Fifteen pages, picked along the top: the <b>Overview</b> (the figures that matter, with how they
 * have moved over the week, and the first of what is driving it); <b>Growth</b> (its people over
 * time, births against deaths, comings and goings, what they died of); <b>Money</b> (what it makes,
 * takes in and pays out each day, the treasury and its worth, and its output by kind); <b>Jobs</b>
 * (every trade: hands, level, pay, what it made yesterday and this week, per hand, and its share
 * of the whole, with any trade's own history a click away); <b>Folk</b> (everybody, sortable by any
 * column); <b>Society</b> (the age pyramid, moods, how evenly the money is spread, natures, skill,
 * families, friendships, the best liked and the best hand at each trade); <b>Leader</b> (who leads,
 * what it cares about, how long, what it promised, what the village thinks of it, the elections);
 * <b>Homes</b> (beds, households, tenure); <b>Buildings</b> (every building, where, its storeys,
 * how furnished, who lives there, and what is to be built next); <b>Stores</b> (food, timber,
 * stone, coal, iron over time); <b>Why</b> (what is driving its growth and what is holding it
 * back); <b>Trends</b> (per hand, per head); <b>Records</b> (its bests, its totals, its averages and
 * where it is heading); <b>News</b> (the chronicle); and the <b>Board</b> itself. The charts follow
 * the mouse: the day under it, and every line's value that day. The range (a week, a month, a
 * hundred days, all of it) is picked at the top right.
 */
public class CityScreen extends Screen {

    private static final String[] TABS = { "Overview", "Growth", "Money", "Jobs", "Folk", "Society", "Leader", "Homes", "Buildings",
        "Stores", "Why", "Trends", "Records", "News", "Board" };
    /** The pages that read today's figures, not the books (so they show from the first day). */
    private static final java.util.Set<String> TODAY_PAGES = java.util.Set.of("Folk", "Society", "Leader", "Buildings", "Why", "News", "Board");
    private static final int[] RANGES = { 7, 30, 100, 0 };
    private static final String[] RANGE_NAMES = { "7d", "30d", "100d", "All" };

    /** Line colours, readable on the light panel. */
    private static final int BLUE = 0xFF2E6FBF, RED = 0xFFB83227, GREEN = 0xFF23803A, AMBER = 0xFFB7791F, PURPLE = 0xFF7D3C98,
        TEAL = 0xFF16867A, BROWN = 0xFF6E4B2A, GREY = 0xFF5A5A5A, PINK = 0xFFC2457A;
    private static final int[] PALETTE = { BLUE, RED, GREEN, AMBER, PURPLE, TEAL, BROWN, PINK, GREY };

    private final CompoundTag data;
    private int tab, range = 1, scroll, w, h, left, top;
    private String selectedTrade;
    private int sortColumn = 6;
    private boolean sortDown = true;
    /** What the mouse is over in a chart: drawn last, over everything. */
    private List<Component> hover;
    private int hoverX, hoverY;

    public CityScreen(CompoundTag data) {
        super(Component.literal(data.getString("name")));
        this.data = data;
    }

    /** The server's answer: open the books (keeping the page that was open, if they are open already). */
    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        CityScreen next = new CityScreen(data);
        if (mc.screen instanceof CityScreen open) {
            next.tab = open.tab;
            next.range = open.range;
            next.selectedTrade = open.selectedTrade;
        }
        if (data.contains("tab")) next.tab = Math.max(0, Math.min(TABS.length - 1, data.getInt("tab")));   // a page asked for
        mc.setScreen(next);
    }

    /** Ask the server for the books of the village you stand in (the journal's button). */
    public static void request() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.jrpetty.mcassistant.net.VillageAskPayload(4));
    }

    @Override
    protected void init() {
        this.w = Math.min(this.width - 12, 500);
        this.h = Math.min(this.height - 12, 330);
        this.left = (this.width - w) / 2;
        this.top = (this.height - h) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int tabWidth(int i, boolean narrow) {
        return narrow ? (int) (font.width(TABS[i]) * 0.75) + 6 : font.width(TABS[i]) + 8;
    }

    /** Where each tab sits: {x, y, width}; in one row if they fit, in the small hand if that makes them fit, else in two rows. */
    private int[][] tabBoxes() {
        int[][] out = new int[TABS.length][];
        int room = w - 8;
        int total = 0, small = 0;
        for (int i = 0; i < TABS.length; i++) { total += tabWidth(i, false) + 1; small += tabWidth(i, true) + 1; }
        boolean narrow = total > room && small <= room;
        boolean two = total > room && small > room;
        int perRow = two ? (TABS.length + 1) / 2 : TABS.length;
        int tx = left + 4, ty = top + 36;
        for (int i = 0; i < TABS.length; i++) {
            if (i == perRow) { tx = left + 4; ty += 14; }
            boolean sm = narrow || (two && rowWidth(i < perRow ? 0 : perRow, i < perRow ? perRow : TABS.length) > room);
            int tw = tabWidth(i, sm);
            out[i] = new int[]{ tx, ty, tw, sm ? 1 : 0 };
            tx += tw + 1;
        }
        return out;
    }

    private int rowWidth(int from, int to) {
        int total = 0;
        for (int i = from; i < to; i++) total += tabWidth(i, false) + 1;
        return total;
    }

    /** Where the page itself begins, under the tabs. */
    private int pageTop() {
        int[][] boxes = tabBoxes();
        return boxes[boxes.length - 1][1] + 18;
    }

    private String page() {
        return TABS[Math.max(0, Math.min(TABS.length - 1, tab))];
    }

    // ------------------------------------------------------------------ the data

    private int[] days() {
        return data.getIntArray("days");
    }

    private int[] series(String key) {
        return data.getCompound("series").getIntArray(key);
    }

    private CompoundTag now() {
        return data.getCompound("now");
    }

    private List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        ListTag l = data.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    private List<CompoundTag> compounds(String key) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = data.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    /** How many of the last days the range shows. */
    private int span() {
        int n = days().length;
        int r = RANGES[range];
        return r == 0 ? n : Math.min(n, r);
    }

    private static int last(int[] a) {
        return a.length == 0 ? 0 : a[a.length - 1];
    }

    /** The value {@code back} days before the last (or the first there is). */
    private static int ago(int[] a, int back) {
        if (a.length == 0) return 0;
        return a[Math.max(0, a.length - 1 - back)];
    }

    private static int sumLast(int[] a, int n) {
        int s = 0;
        for (int i = Math.max(0, a.length - n); i < a.length; i++) s += a[i];
        return s;
    }

    private static int[] plus(int[]... parts) {
        int n = 0;
        for (int[] p : parts) n = Math.max(n, p.length);
        int[] out = new int[n];
        for (int[] p : parts) for (int i = 0; i < p.length; i++) out[i] += p[i];
        return out;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hover = null;
        Ui.panel(g, left, top, w, h, 34);
        String head = data.getString("name") + " — " + data.getString("age") + ", " + data.getString("rank").toLowerCase(Locale.ROOT)
            + ", day " + data.getLong("today");
        g.drawString(font, Ui.clip(font, head, w - 140), left + 8, top + 6, Ui.INK, false);
        String sub = data.getString("land") + " · " + data.getString("look");
        g.drawString(font, Ui.clip(font, sub, w - 140), left + 8, top + 17, Ui.MUTED, false);
        // The range, top right.
        int rx = left + w - 8;
        for (int i = RANGE_NAMES.length - 1; i >= 0; i--) {
            int bw = font.width(RANGE_NAMES[i]) + 8;
            rx -= bw;
            boolean on = i == range;
            g.fill(rx, top + 6, rx + bw - 2, top + 18, on ? Ui.ROW_PICK : Ui.ROW);
            g.renderOutline(rx, top + 6, bw - 2, 12, Ui.EDGE_SOFT);
            g.drawString(font, RANGE_NAMES[i], rx + 3, top + 8, on ? Ui.GOOD : Ui.MUTED, false);
        }
        g.drawString(font, "Close ×", left + w - 8 - font.width("Close ×"), top + 21, Ui.FAINT, false);
        // The tabs (in the small hand, or in two rows, when the window is narrow).
        int[][] boxes = tabBoxes();
        for (int i = 0; i < TABS.length; i++) {
            int tx = boxes[i][0], ty = boxes[i][1], tw = boxes[i][2];
            boolean on = i == tab;
            g.fill(tx, ty, tx + tw, ty + 13, on ? Ui.PANEL : Ui.HEADER);
            g.renderOutline(tx, ty, tw, 13, on ? Ui.EDGE : Ui.EDGE_SOFT);
            if (on) g.fill(tx + 1, ty + 12, tx + tw - 1, ty + 13, Ui.PANEL);
            if (boxes[i][3] == 1) small(g, TABS[i], tx + 3, ty + 4, on ? Ui.INK : Ui.MUTED);
            else g.drawString(font, TABS[i], tx + 4, ty + 3, on ? Ui.INK : Ui.MUTED, false);
        }
        int x = left + 8, y = pageTop(), cw = w - 16, ch = top + h - 8 - y;
        if (days().length == 0 && !TODAY_PAGES.contains(page())) {
            g.drawString(font, "The town's books are written each morning. Come back tomorrow for the first of them;", x, y, Ui.MUTED, false);
            g.drawString(font, "the Folk, Society, Leader, Buildings, Why, News and Board pages have today's figures.", x, y + 11, Ui.MUTED, false);
        } else {
            switch (page()) {
                case "Overview" -> overview(g, x, y, cw, ch, mouseX, mouseY);
                case "Growth" -> growth(g, x, y, cw, ch, mouseX, mouseY);
                case "Money" -> money(g, x, y, cw, ch, mouseX, mouseY);
                case "Jobs" -> jobs(g, x, y, cw, ch, mouseX, mouseY);
                case "Folk" -> folk(g, x, y, cw, ch, mouseX, mouseY);
                case "Society" -> society(g, x, y, cw, ch);
                case "Leader" -> leader(g, x, y, cw, ch);
                case "Homes" -> homes(g, x, y, cw, ch, mouseX, mouseY);
                case "Buildings" -> buildings(g, x, y, cw, ch, mouseX, mouseY);
                case "Stores" -> stores(g, x, y, cw, ch, mouseX, mouseY);
                case "Why" -> why(g, x, y, cw, ch);
                case "Trends" -> trends(g, x, y, cw, ch, mouseX, mouseY);
                case "Records" -> records(g, x, y, cw, ch);
                case "News" -> news(g, x, y, cw, ch);
                default -> board(g, x, y, cw, ch);
            }
        }
        if (hover != null) g.renderComponentTooltip(font, hover, hoverX, hoverY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    // ------------------------------------------------------------------ the pages

    private void overview(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        CompoundTag now = now();
        CompoundTag leader = data.getCompound("leader");
        int[] pop = series("pop"), out = series("output"), coins = series("coins"), worth = series("worth");
        int cardW = (cw - 3 * 4) / 4, cardH = 30;
        card(g, x, y, cardW, cardH, "Population", Integer.toString(last(pop)), delta(pop, 7, ""), BLUE);
        card(g, x + (cardW + 4), y, cardW, cardH, "Made a day", now.getInt("output_week_avg") + "c",
            now.getInt("trend") == 0 ? "yesterday " + now.getInt("output_yesterday") : (now.getInt("trend") > 0 ? "+" : "") + now.getInt("trend") + "% on the week", GREEN);
        card(g, x + 2 * (cardW + 4), y, cardW, cardH, "Treasury", now.getInt("coins") + "c", delta(coins, 7, "c"), AMBER);
        card(g, x + 3 * (cardW + 4), y, cardW, cardH, "Worth", Math.max(0, now.getInt("worth")) + "c", delta(worth, 7, "c"), PURPLE);
        int y2 = y + cardH + 4;
        CompoundTag homes = data.getCompound("homes");
        card(g, x, y2, cardW, cardH, "Content", now.getInt("content") + "/100", now.getString("content_word"),
            now.getInt("content") >= 60 ? GREEN : now.getInt("content") >= 40 ? AMBER : RED);
        card(g, x + (cardW + 4), y2, cardW, cardH, "Beds", homes.getInt("bedded") + "/" + homes.getInt("folk"),
            "room for " + homes.getInt("room"), homes.getInt("folk") >= homes.getInt("room") ? RED : GREEN);
        int[] fd = series("food_days10");
        card(g, x + 2 * (cardW + 4), y2, cardW, cardH, "Food put by", String.format(Locale.ROOT, "%.1f days", last(fd) / 10.0),
            now.getInt("food") + " in the stores", last(fd) < 15 ? RED : GREEN);
        card(g, x + 3 * (cardW + 4), y2, cardW, cardH, data.getCompound("leader").getString("title").isEmpty() ? "Leader"
            : capital(leader.getString("title")), leader.getString("name"),
            leader.contains("approval") ? leader.getInt("approval") + "% approve" : "", BROWN);
        small(g, Ui.clip(font, ages(), (int) (cw / 0.75)), x, y2 + cardH + 3, Ui.MUTED);
        int cy = y2 + cardH + 12;
        int chartH = Math.max(50, (ch - (cy - y)) / 2 - 18);
        int half = (cw - 6) / 2;
        chart(g, x, cy, half, chartH, "Population", mx, my, new Series("Folk", pop, BLUE), new Series("Children", series("kids"), PINK));
        chart(g, x + half + 6, cy, half, chartH, "Made a day (coins' worth)", mx, my, new Series("Output", out, GREEN),
            new Series("Wages", series("wages"), RED));
        int dy = cy + chartH + 16;
        Ui.section(g, font, "What is driving it", x, dy, cw);
        dy += 11;
        List<String> drivers = strings("drivers");
        for (String d : drivers) {
            if (dy > y + ch - 9) break;
            dy = driverLine(g, d, x, dy, cw, 1);
        }
    }

    private String delta(int[] a, int back, String unit) {
        if (a.length < 2) return "";
        int d = last(a) - ago(a, back);
        int days = Math.min(back, a.length - 1);
        return (d > 0 ? "+" : d < 0 ? "" : "±") + d + unit + " in " + days + (days == 1 ? " day" : " days");
    }

    private void card(GuiGraphics g, int x, int y, int cw, int ch, String label, String value, String note, int colour) {
        g.fill(x, y, x + cw, y + ch, Ui.ROW_ALT);
        g.renderOutline(x, y, cw, ch, Ui.EDGE_SOFT);
        g.fill(x, y, x + 2, y + ch, colour);
        g.drawString(font, Ui.clip(font, label.toUpperCase(Locale.ROOT), cw - 8), x + 5, y + 3, Ui.FAINT, false);
        g.drawString(font, Ui.clip(font, value, cw - 8), x + 5, y + 12, colour, false);
        small(g, Ui.clip(font, note, (int) ((cw - 8) / 0.75)), x + 5, y + 22, Ui.MUTED);
    }

    private void growth(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        int half = (cw - 6) / 2, chartH = (ch - 50) / 2;
        chart(g, x, y, half, chartH, "People", mx, my, new Series("All", series("pop"), BLUE), new Series("Grown", series("adults"), GREEN),
            new Series("Children", series("kids"), PINK));
        bars(g, x + half + 6, y, half, chartH, "Born and died, each day", mx, my,
            new Series("Born", series("born"), GREEN), new Series("Died", series("died"), RED));
        int y2 = y + chartH + 14;
        bars(g, x, y2, half, chartH, "Came and went, each day", mx, my,
            new Series("Came", series("moved_in"), TEAL), new Series("Left", series("moved_out"), AMBER));
        // The trades' hands over time: the five biggest.
        List<Series> trades = tradeSeries("trade_hands", 5);
        chart(g, x + half + 6, y2, half, chartH, "Hands at each trade (the five biggest)", mx, my, trades.toArray(new Series[0]));
        int ty = y2 + chartH + 14;
        int n = span();
        int[] pop = series("pop");
        int born = sumLast(series("born"), n), died = sumLast(series("died"), n), came = sumLast(series("moved_in"), n), went = sumLast(series("moved_out"), n);
        double rate = pop.length > 1 && ago(pop, n) > 0 ? (Math.pow(last(pop) / (double) ago(pop, Math.min(n, pop.length - 1)), 1.0 / Math.max(1, Math.min(n, pop.length - 1))) - 1) * 100 : 0;
        StringBuilder causes = new StringBuilder();
        CompoundTag c = data.getCompound("causes");
        for (String k : c.getAllKeys()) causes.append(causes.length() == 0 ? "" : ", ").append(k).append(' ').append(c.getInt(k));
        g.drawString(font, Ui.clip(font, "In the range: " + born + " born, " + died + " died, " + came + " came, " + went + " left; growing "
            + String.format(Locale.ROOT, "%.1f", rate) + "% a day.", cw), x, ty, Ui.INK, false);
        small(g, Ui.clip(font, "Deaths, all told: " + (causes.length() == 0 ? "none" : causes.toString()), (int) (cw / 0.75)), x, ty + 11, Ui.MUTED);
    }

    private void money(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        int half = (cw - 6) / 2, chartH = (ch - 30) / 2;
        int[] in = plus(series("takings"), series("sold"), series("tithe"));
        int[] outs = plus(series("wages"), series("spent"));
        chart(g, x, y, half, chartH, "Made, earned and paid out, a day", mx, my, new Series("Made", series("output"), GREEN),
            new Series("Money in", in, BLUE), new Series("Paid out", outs, RED));
        chart(g, x + half + 6, y, half, chartH, "Treasury, purses and worth", mx, my, new Series("Treasury", series("coins"), AMBER),
            new Series("Purses", series("purses"), PURPLE), new Series("Worth", series("worth"), TEAL));
        int y2 = y + chartH + 14;
        bars(g, x, y2, half, chartH, "Money in against money out", mx, my, new Series("In", in, BLUE), new Series("Out", outs, RED));
        // What it made, by kind, over the range.
        String[] kinds = { "out_food", "out_timber", "out_stone", "out_ore", "out_animal", "out_craft", "out_plant" };
        String[] names = { "Food", "Timber", "Stone", "Ore and metal", "Wool, hides, honey", "Crafts", "Plants" };
        int n = span();
        int[] tot = new int[kinds.length];
        int all = 0;
        for (int i = 0; i < kinds.length; i++) { tot[i] = sumLast(series(kinds[i]), n); all += tot[i]; }
        int bx = x + half + 6, by = y2;
        Ui.section(g, font, "What it made, by kind (" + RANGE_NAMES[range] + ")", bx, by, half);
        by += 12;
        for (int i = 0; i < kinds.length; i++) {
            float frac = all == 0 ? 0 : tot[i] / (float) all;
            g.drawString(font, names[i], bx, by, Ui.INK, false);
            Ui.bar(g, bx + 92, by, half - 92 - 60, 8, frac, PALETTE[i % PALETTE.length]);
            Ui.right(g, font, tot[i] + "c " + Math.round(frac * 100) + "%", bx + half, by, Ui.MUTED);
            by += 12;
        }
        int takings = sumLast(series("takings"), n), sold = sumLast(series("sold"), n), tithe = sumLast(series("tithe"), n),
            wages = sumLast(series("wages"), n), spent = sumLast(series("spent"), n);
        by += 2;
        small(g, "In: " + takings + " from the work, " + sold + " sold, " + tithe + " tithe.", bx, by, Ui.MUTED);
        small(g, "Out: " + wages + " in wages, " + spent + " bought in. Net " + (takings + sold + tithe - wages - spent) + ".", bx, by + 9, Ui.MUTED);
    }

    private void jobs(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        List<CompoundTag> jobs = compounds("jobs");
        jobs.sort(Comparator.comparingInt((CompoundTag c) -> c.getInt("week")).reversed().thenComparing(c -> -c.getInt("hands")));
        int tableH = Math.min(ch / 2 + 20, 14 + jobs.size() * 11);
        String[] heads = { "Trade", "Hands", "Lvl", "Pay", "Yesterday", "Week", "Per hand", "Share of the week's output" };
        int[] cols = { 0, 92, 128, 152, 182, 232, 272, 318 };
        for (int i = 0; i < heads.length; i++) small(g, heads[i], x + cols[i], y, Ui.FAINT);
        int ry = y + 9;
        int maxRows = (tableH - 9) / 11;
        int start = Math.max(0, Math.min(scroll, Math.max(0, jobs.size() - maxRows)));
        for (int i = start; i < Math.min(jobs.size(), start + maxRows); i++) {
            CompoundTag j = jobs.get(i);
            boolean picked = j.getString("id").equals(selectedTrade);
            boolean over = mx >= x && mx < x + cw && my >= ry && my < ry + 11;
            g.fill(x - 2, ry - 1, x + cw, ry + 10, picked ? Ui.ROW_PICK : over ? Ui.HI : (i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT));
            Ui.chip(g, x, ry + 1, Ui.job(j.getInt("ordinal")));
            g.drawString(font, Ui.clip(font, j.getString("title"), 80), x + 10, ry + 1, Ui.INK, false);
            g.drawString(font, Integer.toString(j.getInt("hands")), x + cols[1], ry + 1, Ui.INK, false);
            g.drawString(font, Integer.toString(j.getInt("level")), x + cols[2], ry + 1, Ui.INK, false);
            g.drawString(font, j.getInt("wage") + "c", x + cols[3], ry + 1, Ui.INK, false);
            g.drawString(font, j.getInt("yesterday") + "c", x + cols[4], ry + 1, Ui.INK, false);
            g.drawString(font, j.getInt("week") + "c", x + cols[5], ry + 1, Ui.INK, false);
            g.drawString(font, j.getInt("per_head") + "c", x + cols[6], ry + 1, Ui.INK, false);
            int bw = cw - cols[7] - 30;
            Ui.bar(g, x + cols[7], ry + 1, bw, 7, j.getInt("share") / 100f, Ui.job(j.getInt("ordinal")));
            Ui.right(g, font, j.getInt("share") + "%", x + cw, ry + 1, Ui.MUTED);
            ry += 11;
        }
        if (jobs.size() > maxRows) small(g, "(scroll for more trades)", x, ry, Ui.FAINT);
        // The chosen trade's history, or the five biggest earners'.
        int cy = y + tableH + 8;
        int chartH = y + ch - cy - 4;
        if (chartH < 40) return;
        int half = (cw - 6) / 2;
        if (selectedTrade != null && data.getCompound("trade_out").contains(selectedTrade)) {
            String title = selectedTrade.charAt(0) + selectedTrade.substring(1).toLowerCase(Locale.ROOT);
            for (CompoundTag j : jobs) if (j.getString("id").equals(selectedTrade)) title = j.getString("title");
            chart(g, x, cy, half, chartH, title + ": made a day", mx, my,
                new Series("Made", data.getCompound("trade_out").getIntArray(selectedTrade), GREEN));
            chart(g, x + half + 6, cy, half, chartH, title + ": hands", mx, my,
                new Series("Hands", data.getCompound("trade_hands").getIntArray(selectedTrade), BLUE));
        } else {
            chart(g, x, cy, half, chartH, "Made a day, the five biggest earners (click a trade)", mx, my,
                tradeSeries("trade_out", 5).toArray(new Series[0]));
            chart(g, x + half + 6, cy, half, chartH, "Hands, the five biggest trades", mx, my,
                tradeSeries("trade_hands", 5).toArray(new Series[0]));
        }
    }

    /** The biggest trades' series (by their total in the range), coloured as their uniforms. */
    private List<Series> tradeSeries(String key, int most) {
        CompoundTag t = data.getCompound(key);
        List<String> ids = new ArrayList<>(t.getAllKeys());
        int n = span();
        ids.sort(Comparator.comparingInt((String id) -> sumLast(t.getIntArray(id), n)).reversed());
        List<Series> out = new ArrayList<>();
        for (int i = 0; i < Math.min(most, ids.size()); i++) {
            String id = ids.get(i);
            int colour = PALETTE[i % PALETTE.length];
            String name = id.charAt(0) + id.substring(1).toLowerCase(Locale.ROOT);
            for (CompoundTag j : compounds("jobs")) {
                if (j.getString("id").equals(id)) { name = j.getString("title"); colour = Ui.job(j.getInt("ordinal")); break; }
            }
            out.add(new Series(name, t.getIntArray(id), colour));
        }
        return out;
    }

    private static final String[] FOLK_HEADS = { "Name", "Trade", "Lvl", "Age", "Purse", "Pay", "Made", "Mood", "Nature", "Wealth" };
    private static final int[] FOLK_COLS = { 0, 82, 160, 182, 210, 246, 274, 306, 336, 410 };

    private void folk(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        List<CompoundTag> people = compounds("people");
        people.sort(folkOrder());
        for (int i = 0; i < FOLK_HEADS.length; i++) {
            if (FOLK_COLS[i] >= cw - 20) break;
            String hd = FOLK_HEADS[i] + (i == sortColumn ? (sortDown ? " ▼" : " ▲") : "");
            small(g, hd, x + FOLK_COLS[i], y, i == sortColumn ? Ui.GOOD : Ui.FAINT);
        }
        int ry = y + 10;
        int rows = (ch - 22) / 10;
        int start = Math.max(0, Math.min(scroll, Math.max(0, people.size() - rows)));
        for (int i = start; i < Math.min(people.size(), start + rows); i++) {
            CompoundTag p = people.get(i);
            g.fill(x - 2, ry - 1, x + cw, ry + 9, p.getBoolean("leader") ? Ui.ROW_PICK : i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
            Ui.chip(g, x, ry, Ui.job(p.getInt("ordinal")));
            String[] cells = { p.getString("name") + (p.getBoolean("leader") ? " ★" : ""), p.getString("trade"), Integer.toString(p.getInt("level")),
                p.getInt("years") + "y", p.getInt("purse") + "c", p.getInt("wage") + "c", p.getInt("made") + "c",
                Integer.toString(p.getInt("mood")), p.getString("type"), p.getString("wealth") };
            for (int c = 0; c < cells.length; c++) {
                if (FOLK_COLS[c] >= cw - 20) break;
                int colW = (c + 1 < FOLK_COLS.length ? FOLK_COLS[c + 1] : cw) - FOLK_COLS[c] - 3;
                small(g, Ui.clip(font, cells[c], (int) (colW / 0.75)), x + FOLK_COLS[c] + (c == 0 ? 9 : 0), ry + 1,
                    c == 7 ? (p.getInt("mood") >= 60 ? Ui.GOOD : p.getInt("mood") < 35 ? Ui.BAD : Ui.INK) : Ui.INK);
            }
            ry += 10;
        }
        java.util.Map<String, Integer> bands = new java.util.TreeMap<>();
        int purses = 0;
        for (CompoundTag p : people) {
            if (!p.getString("wealth").isEmpty()) bands.merge(p.getString("wealth"), 1, Integer::sum);
            purses += p.getInt("purse");
        }
        StringBuilder bs = new StringBuilder();
        for (var e : bands.entrySet()) bs.append(bs.length() == 0 ? "" : ", ").append(e.getValue()).append(' ').append(e.getKey());
        small(g, Ui.clip(font, people.size() + " folk (" + bs + "); " + purses + " coins in purses, "
            + (people.isEmpty() ? 0 : purses / people.size()) + " each · click a heading to sort · ★ the leader", (int) (cw / 0.75)), x, y + ch - 10, Ui.FAINT);
    }

    private Comparator<CompoundTag> folkOrder() {
        Comparator<CompoundTag> c = switch (sortColumn) {
            case 0 -> Comparator.comparing(p -> p.getString("name"));
            case 1 -> Comparator.comparing(p -> p.getString("trade"));
            case 2 -> Comparator.comparingInt(p -> p.getInt("level"));
            case 3 -> Comparator.comparingInt(p -> p.getInt("years"));
            case 4 -> Comparator.comparingInt(p -> p.getInt("purse"));
            case 5 -> Comparator.comparingInt(p -> p.getInt("wage"));
            case 7 -> Comparator.comparingInt(p -> p.getInt("mood"));
            case 8 -> Comparator.comparing(p -> p.getString("type"));
            case 9 -> Comparator.comparing(p -> p.getString("wealth"));
            default -> Comparator.comparingInt(p -> p.getInt("made"));
        };
        return sortDown ? c.reversed() : c;
    }

    private void leader(GuiGraphics g, int x, int y, int cw, int ch) {
        CompoundTag l = data.getCompound("leader");
        int half = (cw - 10) / 2;
        g.drawString(font, Ui.clip(font, l.getString("name") + (l.getString("title").isEmpty() ? "" : ", the " + l.getString("title")), half), x, y, Ui.INK, false);
        int ly = y + 12;
        List<String> rows = new ArrayList<>();
        if (l.contains("type")) rows.add(capital(l.getString("type")));
        if (l.contains("trade")) rows.add("A " + l.getString("trade").toLowerCase(Locale.ROOT) + " by trade, level " + l.getInt("level")
            + (l.getInt("age") >= 0 ? ", " + l.getInt("age") + " days old" : ""));
        if (!l.getString("traits").isEmpty()) rows.add("Nature: " + l.getString("traits"));
        if (!l.getString("partner").isEmpty() || l.getInt("children") > 0) rows.add("Family: " + (l.getString("partner").isEmpty() ? "" : "partner "
            + l.getString("partner")) + (l.getInt("children") > 0 ? (l.getString("partner").isEmpty() ? "" : "; ") + l.getInt("children") + " children" : ""));
        if (l.contains("home")) rows.add("Lives in " + l.getString("home") + (l.getString("escort").isEmpty() ? "" : "; " + l.getString("escort")));
        if (l.contains("purse")) rows.add("Purse: " + l.getInt("purse") + " coins");
        long elected = l.getLong("elected_on");
        if (elected >= 0) rows.add("In office since day " + elected + " (" + (data.getLong("today") - elected) + " days)");
        rows.add("Mandate: " + (l.getString("mandate").isEmpty() ? "none — chosen, not elected" : l.getString("mandate")));
        rows.add("Orders: " + (l.getString("orders").isEmpty() ? "none yet" : l.getString("orders")));
        rows.add("Council: " + (l.getString("council").isEmpty() ? "none" : l.getString("council")));
        if (l.contains("pace")) rows.add("Sets the pace at " + l.getInt("pace") + "% and pay at " + l.getInt("pay") + "%");
        for (String r : rows) {
            for (FormattedCharSequence line : font.split(Component.literal(r), half)) {
                g.drawString(font, line, x, ly, Ui.INK, false);
                ly += 10;
            }
        }
        if (l.contains("approval")) {
            ly += 4;
            g.drawString(font, "Approval", x, ly, Ui.FAINT, false);
            Ui.bar(g, x + 52, ly, half - 90, 8, l.getInt("approval") / 100f, l.getInt("approval") >= 60 ? GREEN : l.getInt("approval") >= 40 ? AMBER : RED);
            Ui.right(g, font, l.getInt("approval") + "%", x + half, ly, Ui.MUTED);
            ly += 11;
            small(g, "Regard, on average: " + l.getInt("regard") + " (from -100 to 100)", x, ly, Ui.MUTED);
            ly += 10;
        }
        for (FormattedCharSequence line : font.split(Component.literal(l.getString("line")), half)) {
            if (ly > y + ch - 10) break;
            small(g, line, x, ly, Ui.MUTED);
            ly += 9;
        }
        // The right-hand column: what it cares about, the elections, what it remembers.
        int rx = x + half + 10, ry = y;
        if (l.contains("values")) {
            Ui.section(g, font, "What it cares about", rx, ry, half);
            ry += 12;
            int[] vals = l.getIntArray("values");
            ListTag names = l.getList("value_names", Tag.TAG_STRING);
            for (int i = 0; i < vals.length && i < names.size(); i++) {
                g.drawString(font, names.getString(i), rx, ry, Ui.INK, false);
                Ui.bar(g, rx + 84, ry, half - 84 - 22, 8, vals[i] / 100f, PALETTE[i % PALETTE.length]);
                Ui.right(g, font, Integer.toString(vals[i]), rx + half, ry, Ui.MUTED);
                ry += 11;
            }
        }
        ry += 4;
        Ui.section(g, font, "Elections", rx, ry, half);
        ry += 12;
        for (FormattedCharSequence line : font.split(Component.literal(l.getString("election")), half)) {
            small(g, line, rx, ry, Ui.INK);
            ry += 9;
        }
        String hist = l.getString("elections");
        if (!hist.isEmpty()) {
            String[] all = hist.split(";");
            for (int i = all.length - 1; i >= Math.max(0, all.length - 5); i--) {
                String[] p = all[i].split("\\|");
                String line = p.length >= 3 ? "Day " + p[0] + ": " + p[1] + " — " + p[2] : all[i];
                small(g, Ui.clip(font, line, (int) (half / 0.75)), rx, ry, Ui.MUTED);
                ry += 9;
            }
        }
        if (l.contains("memories")) {
            ry += 4;
            Ui.section(g, font, "Lately", rx, ry, half);
            ry += 12;
            ListTag mem = l.getList("memories", Tag.TAG_STRING);
            for (int i = 0; i < mem.size() && ry < y + ch - 9; i++) {
                small(g, Ui.clip(font, mem.getString(i), (int) (half / 0.75)), rx, ry, Ui.MUTED);
                ry += 9;
            }
        }
    }

    private void homes(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        CompoundTag hm = data.getCompound("homes");
        int half = (cw - 6) / 2, chartH = ch - 96;
        chart(g, x, y, half, chartH, "Folk against beds", mx, my, new Series("Folk", series("pop"), BLUE),
            new Series("Room", series("room"), GREEN), new Series("With a bed", series("bedded"), AMBER));
        chart(g, x + half + 6, y, half, chartH, "Households housed and waiting", mx, my, new Series("Housed", series("housed"), GREEN),
            new Series("Waiting", series("waiting"), RED), new Series("Buildings", series("buildings"), BROWN));
        int ty = y + chartH + 14;
        String[] names = { "housed", "waiting", "given", "owned", "rented", "players", "empty" };
        String[] words = { "Households housed", "Waiting for a house", "Given by the village", "Owned", "Rented", "Players' houses", "Empty" };
        int colW = cw / 4;
        for (int i = 0; i < names.length; i++) {
            int cx = x + (i % 4) * colW, cy = ty + (i / 4) * 11;
            g.drawString(font, words[i] + ": ", cx, cy, Ui.FAINT, false);
            g.drawString(font, Integer.toString(hm.getInt(names[i])), cx + font.width(words[i] + ": "), cy, Ui.INK, false);
        }
        int by = ty + 24;
        g.drawString(font, "Beds", x, by, Ui.FAINT, false);
        Ui.bar(g, x + 30, by, cw / 2, 8, hm.getInt("folk") == 0 ? 0 : hm.getInt("bedded") / (float) Math.max(1, hm.getInt("folk")), GREEN);
        g.drawString(font, hm.getInt("bedded") + " of " + hm.getInt("folk") + " folk have a bed; " + hm.getInt("beds_made") + " made up in houses, room for "
            + hm.getInt("room") + (hm.getInt("for_sale") == 1 ? "; houses are sold" : "; houses are given"), x + 36 + cw / 2, by, Ui.MUTED, false);
        small(g, Ui.clip(font, hm.getString("line"), (int) (cw / 0.75)), x, by + 12, Ui.MUTED);
    }

    private void stores(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        CompoundTag now = now();
        int half = (cw - 6) / 2, chartH = (ch - 40) / 2;
        chart(g, x, y, half, chartH, "Food in the stores", mx, my, new Series("Food", series("food"), GREEN));
        int[] fd = series("food_days10");
        chart(g, x + half + 6, y, half, chartH, "Days of food put by (tenths)", mx, my, new Series("Days ×10", fd, AMBER));
        int y2 = y + chartH + 14;
        chart(g, x, y2, half, chartH, "Timber and stone", mx, my, new Series("Logs", series("logs"), BROWN), new Series("Stone", series("stone"), GREY));
        chart(g, x + half + 6, y2, half, chartH, "Coal and iron", mx, my, new Series("Coal", series("coal"), 0xFF222222), new Series("Iron", series("iron"), RED));
        int ty = y2 + chartH + 12;
        String[] keys = { "food", "logs", "stone", "coal", "iron", "diamond", "obsidian" };
        StringBuilder sb = new StringBuilder("Now: ");
        for (String k : keys) sb.append(k).append(' ').append(now.getInt(k)).append("  ");
        small(g, Ui.clip(font, sb.toString(), (int) (cw / 0.75)), x, ty, Ui.INK);
        small(g, Ui.clip(font, "The larder's books: " + now.getString("food_books"), (int) (cw / 0.75)), x, ty + 9, Ui.MUTED);
    }

    private void why(GuiGraphics g, int x, int y, int cw, int ch) {
        CompoundTag now = now();
        int half = cw * 3 / 5;
        Ui.section(g, font, "What is driving growth, and what is holding it back", x, y, half);
        int dy = y + 12;
        for (String d : strings("drivers")) {
            if (dy > y + ch - 10) break;
            dy = driverLine(g, d, x, dy, half, 3);
            dy += 2;
        }
        int rx = x + half + 8, rw = cw - half - 8, ry = y;
        Ui.section(g, font, "Contentment " + now.getInt("content") + "/100", rx, ry, rw);
        ry += 12;
        String[] parts = { "Food", "Homes", "Mood", "Safety", "Amenities", "Wages" };
        int[] vals = now.getIntArray("content_parts");
        for (int i = 0; i < parts.length && i < vals.length; i++) {
            g.drawString(font, parts[i], rx, ry, Ui.INK, false);
            Ui.bar(g, rx + 56, ry, rw - 56 - 22, 8, vals[i] / 100f, vals[i] >= 60 ? GREEN : vals[i] >= 35 ? AMBER : RED);
            Ui.right(g, font, Integer.toString(vals[i]), rx + rw, ry, Ui.MUTED);
            ry += 11;
        }
        ry += 4;
        Ui.section(g, font, "Short of", rx, ry, rw);
        ry += 12;
        List<String> needs = strings("needs");
        if (needs.isEmpty()) { small(g, "Nothing: about to come of age.", rx, ry, Ui.GOOD); ry += 9; }
        for (String n : needs) {
            if (ry > y + ch - 40) break;
            for (FormattedCharSequence line : font.split(Component.literal("· " + n), (int) (rw / 0.75))) { small(g, line, rx, ry, Ui.INK); ry += 9; }
        }
        ry += 4;
        Ui.section(g, font, "Next to build", rx, ry, rw);
        ry += 12;
        for (FormattedCharSequence line : font.split(Component.literal(now.getString("next")), (int) (rw / 0.75))) {
            if (ry > y + ch - 9) break;
            small(g, line, rx, ry, Ui.MUTED);
            ry += 9;
        }
        if (!now.getString("set_aside").isEmpty() && ry < y + ch - 18) {
            for (FormattedCharSequence line : font.split(Component.literal("Set aside: " + now.getString("set_aside")), (int) (rw / 0.75))) {
                if (ry > y + ch - 9) break;
                small(g, line, rx, ry, Ui.WARN);
                ry += 9;
            }
        }
    }

    /** What each head makes and is worth, how content they are, and how the town has come on. */
    private void trends(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        int half = (cw - 6) / 2, chartH = (ch - 30) / 2;
        int[] out = series("output"), adults = series("adults"), worth = series("worth"), pop = series("pop");
        int[] perHand = new int[out.length], perHead = new int[worth.length];
        for (int i = 0; i < out.length; i++) perHand[i] = i < adults.length && adults[i] > 0 ? Math.round(out[i] / (float) adults[i]) : 0;
        for (int i = 0; i < worth.length; i++) perHead[i] = i < pop.length && pop[i] > 0 ? Math.round(worth[i] / (float) pop[i]) : 0;
        chart(g, x, y, half, chartH, "Made per grown folk a day (coins' worth)", mx, my, new Series("Per hand", perHand, GREEN));
        chart(g, x + half + 6, y, half, chartH, "Worth per head (coins)", mx, my, new Series("Per head", perHead, PURPLE));
        int y2 = y + chartH + 14;
        chart(g, x, y2, half, chartH, "Contentment, idle hands and the watch", mx, my, new Series("Content", series("content"), AMBER),
            new Series("Idle", series("idle"), RED), new Series("Guards", series("guards"), GREY));
        chart(g, x + half + 6, y2, half, chartH, "Buildings and renown", mx, my, new Series("Buildings", series("buildings"), BROWN),
            new Series("Renown", series("renown"), TEAL), new Series("Age", series("age"), BLUE));
    }

    /** The village as a society: its ages, its moods, how its money is spread, its natures, skills, families and friendships. */
    private void society(GuiGraphics g, int x, int y, int cw, int ch) {
        CompoundTag so = data.getCompound("society");
        int col = (cw - 16) / 3;
        int bottom = y + ch - 2;
        // The first column: the age pyramid and how they feel.
        int cx = x, cy = y;
        Ui.section(g, font, "Ages (in years)", cx, cy, col);
        cy += 12;
        int[] ages = so.getIntArray("ages");
        int most = 1;
        for (int a : ages) most = Math.max(most, a);
        for (int i = ages.length - 1; i >= 0; i--) {
            String band = i == 9 ? "90+" : (i * 10) + "-" + (i * 10 + 9);
            small(g, band, cx, cy + 1, Ui.MUTED);
            int bw = col - 50;
            int len = Math.round(bw * ages[i] / (float) most);
            int colour = i < 2 ? PINK : i >= 7 ? GREY : BLUE;
            g.fill(cx + 28, cy, cx + 28 + Math.max(ages[i] > 0 ? 1 : 0, len), cy + 7, colour);
            Ui.right(g, font, Integer.toString(ages[i]), cx + col, cy, Ui.MUTED);
            cy += 9;
        }
        small(g, so.getInt("children") + " children, " + so.getInt("grown") + " grown, " + so.getInt("old") + " old", cx, cy + 1, Ui.MUTED);
        cy += 14;
        Ui.section(g, font, "How they feel", cx, cy, col);
        cy += 12;
        String[] moodNames = { "Miserable", "Low", "So-so", "Good", "Joyful" };
        int[] moodCol = { RED, AMBER, GREY, GREEN, TEAL };
        int[] moods = so.getIntArray("moods");
        int folk = 0;
        for (int m : moods) folk += m;
        for (int i = moods.length - 1; i >= 0 && cy < bottom - 8; i--) {
            small(g, moodNames[i], cx, cy + 1, Ui.MUTED);
            Ui.bar(g, cx + 44, cy, col - 44 - 22, 7, folk == 0 ? 0 : moods[i] / (float) folk, moodCol[i]);
            Ui.right(g, font, Integer.toString(moods[i]), cx + col, cy, Ui.MUTED);
            cy += 9;
        }
        // The second column: the money and the families.
        cx = x + col + 8;
        cy = y;
        int gini = so.getInt("gini");
        Ui.section(g, font, "How the money is spread", cx, cy, col);
        cy += 12;
        String word = gini < 25 ? "evenly" : gini < 40 ? "fairly" : gini < 55 ? "unevenly" : "very unevenly";
        small(g, "Spread " + word + " (Gini " + gini + " of 100)", cx, cy, Ui.INK);
        cy += 9;
        Ui.bar(g, cx, cy, col - 4, 6, gini / 100f, gini < 40 ? GREEN : gini < 55 ? AMBER : RED);
        cy += 9;
        small(g, "Middle purse " + so.getInt("median") + "c", cx, cy, Ui.MUTED);
        cy += 9;
        small(g, "The richest tenth hold " + so.getInt("top_tenth") + "%;", cx, cy, Ui.MUTED);
        cy += 9;
        small(g, "the poorer half hold " + so.getInt("bottom_half") + "%", cx, cy, Ui.MUTED);
        cy += 11;
        small(g, "The richest:", cx, cy, Ui.FAINT);
        cy += 9;
        ListTag rich = so.getList("richest", Tag.TAG_STRING);
        for (int i = 0; i < rich.size(); i++) {
            String[] p = rich.getString(i).split("\\|");
            if (p.length < 3) continue;
            small(g, Ui.clip(font, p[0] + ", " + p[1].toLowerCase(Locale.ROOT), (int) ((col - 30) / 0.75)), cx, cy, Ui.INK);
            Ui.right(g, font, p[2] + "c", cx + col, cy - 1, Ui.MUTED);
            cy += 9;
        }
        cy += 5;
        Ui.section(g, font, "Families and friends", cx, cy, col);
        cy += 12;
        String[] fam = {
            so.getInt("couples") + " couples, " + so.getInt("single") + " single",
            so.getInt("households") + " households, " + String.format(Locale.ROOT, "%.1f", so.getInt("household_avg10") / 10.0)
                + " to a house (at most " + so.getInt("household_max") + ")",
            so.getInt("friendships") + " friendships, " + so.getInt("rivalries") + " rivalries" };
        for (String f : fam) {
            if (cy > bottom - 8) break;
            small(g, Ui.clip(font, f, (int) (col / 0.75)), cx, cy, Ui.INK);
            cy += 9;
        }
        ListTag liked = so.getList("liked", Tag.TAG_STRING);
        if (liked.size() > 0 && cy < bottom - 18) {
            cy += 3;
            small(g, "Best liked:", cx, cy, Ui.FAINT);
            cy += 9;
            for (int i = 0; i < liked.size() && cy < bottom - 8; i++) {
                String[] p = liked.getString(i).split("\\|");
                if (p.length < 2) continue;
                small(g, Ui.clip(font, p[0], (int) ((col - 30) / 0.75)), cx, cy, Ui.INK);
                Ui.right(g, font, (p[1].startsWith("-") ? "" : "+") + p[1], cx + col, cy - 1, Ui.MUTED);
                cy += 9;
            }
        }
        // The third column: natures, skill, the best hand at each trade.
        cx = x + 2 * (col + 8);
        cy = y;
        Ui.section(g, font, "Their natures", cx, cy, col);
        cy += 12;
        CompoundTag nat = so.getCompound("natures");
        List<String> types = new ArrayList<>(nat.getAllKeys());
        types.sort(Comparator.comparingInt(nat::getInt).reversed());
        int grown = Math.max(1, so.getInt("grown"));
        for (int i = 0; i < types.size() && i < 6; i++) {
            small(g, types.get(i), cx, cy + 1, Ui.MUTED);
            Ui.bar(g, cx + 50, cy, col - 50 - 22, 7, nat.getInt(types.get(i)) / (float) grown, PALETTE[i % PALETTE.length]);
            Ui.right(g, font, Integer.toString(nat.getInt(types.get(i))), cx + col, cy, Ui.MUTED);
            cy += 9;
        }
        CompoundTag tr = so.getCompound("traits");
        List<String> traits = new ArrayList<>(tr.getAllKeys());
        traits.sort(Comparator.comparingInt(tr::getInt).reversed());
        StringBuilder ts = new StringBuilder();
        for (int i = 0; i < Math.min(4, traits.size()); i++) ts.append(i == 0 ? "Mostly " : ", ").append(traits.get(i).toLowerCase(Locale.ROOT));
        if (ts.length() > 0) {
            small(g, Ui.clip(font, ts.toString(), (int) (col / 0.75)), cx, cy + 1, Ui.FAINT);
            cy += 10;
        }
        cy += 4;
        Ui.section(g, font, "How skilled", cx, cy, col);
        cy += 12;
        String[] lv = { "Novice", "Apprentice", "Journeyman", "Skilled", "Expert", "Master" };
        String[] lvRange = { "0-4", "5-9", "10-14", "15-19", "20-29", "30+" };
        int[] levels = so.getIntArray("levels");
        for (int i = levels.length - 1; i >= 0 && cy < bottom - 8; i--) {
            small(g, lv[i] + " " + lvRange[i], cx, cy + 1, Ui.MUTED);
            Ui.bar(g, cx + 62, cy, col - 62 - 22, 7, levels[i] / (float) grown, PALETTE[(i + 2) % PALETTE.length]);
            Ui.right(g, font, Integer.toString(levels[i]), cx + col, cy, Ui.MUTED);
            cy += 9;
        }
        ListTag masters = so.getList("masters", Tag.TAG_STRING);
        if (masters.size() > 0 && cy < bottom - 18) {
            cy += 3;
            small(g, "The best hand at each trade:", cx, cy, Ui.FAINT);
            cy += 9;
            for (int i = 0; i < masters.size() && cy < bottom - 8; i++) {
                String[] p = masters.getString(i).split("\\|");
                if (p.length < 3) continue;
                small(g, Ui.clip(font, p[0] + ": " + p[1], (int) ((col - 22) / 0.75)), cx, cy, Ui.INK);
                Ui.right(g, font, "L" + p[2], cx + col, cy - 1, Ui.MUTED);
                cy += 9;
            }
        }
    }

    private static final String[] BUILDING_HEADS = { "Building", "Where", "Storeys", "Furnished", "Home" };
    private static final int[] BUILDING_COLS = { 0, 92, 168, 206, 262 };

    /** Every building in the village, what it is, where, how far it has come; and what it will build next. */
    private void buildings(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        List<CompoundTag> all = compounds("buildings");
        all.sort(Comparator.comparing((CompoundTag c) -> c.getString("title")).thenComparingInt(c -> c.getInt("dist")));
        int side = Math.min(170, cw / 3);
        int tw = cw - side - 8;
        for (int i = 0; i < BUILDING_HEADS.length; i++) {
            if (BUILDING_COLS[i] >= tw - 20) break;
            small(g, BUILDING_HEADS[i], x + BUILDING_COLS[i], y, Ui.FAINT);
        }
        int ry = y + 10;
        int rows = (ch - 22) / 10;
        int start = Math.max(0, Math.min(scroll, Math.max(0, all.size() - rows)));
        int furnished = 0, furnishOf = 0, tall = 0, going = 0;
        for (CompoundTag b : all) {
            furnished += b.getInt("furnished");
            furnishOf += b.getInt("furnish_of");
            if (b.getInt("storeys") > 1) tall++;
            if (b.getBoolean("raising")) going++;
        }
        for (int i = start; i < Math.min(all.size(), start + rows); i++) {
            CompoundTag b = all.get(i);
            g.fill(x - 2, ry - 1, x + tw, ry + 9, i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
            String where = b.getString("dir").equals("at the heart") ? "at the heart" : b.getInt("dist") + " " + b.getString("dir");
            String storeys = b.getBoolean("raising") ? "going up" : Integer.toString(b.getInt("storeys"));
            String fur = b.getInt("furnish_of") == 0 ? "—" : Math.round(b.getInt("furnished") * 100f / b.getInt("furnish_of")) + "%";
            String home = b.contains("living") ? b.getInt("living") + " · " + b.getString("tenure") : "";
            String[] cells = { b.getString("title"), where, storeys, fur, home };
            for (int c = 0; c < cells.length; c++) {
                if (BUILDING_COLS[c] >= tw - 20) break;
                int colW = (c + 1 < BUILDING_COLS.length ? BUILDING_COLS[c + 1] : tw) - BUILDING_COLS[c] - 3;
                small(g, Ui.clip(font, cells[c], (int) (colW / 0.75)), x + BUILDING_COLS[c], ry + 1,
                    c == 2 && b.getBoolean("raising") ? Ui.WARN : Ui.INK);
            }
            ry += 10;
        }
        small(g, Ui.clip(font, all.size() + " buildings, " + tall + " of two storeys" + (going > 0 ? ", " + going + " going up" : "")
            + (furnishOf > 0 ? "; insides " + Math.round(furnished * 100f / furnishOf) + "% furnished for the age" : "")
            + (all.size() > rows ? " · scroll for more" : ""), (int) (tw / 0.75)), x, y + ch - 10, Ui.FAINT);
        // The side: how many of each, and what comes next.
        int sx = x + tw + 8, sy = y;
        Ui.section(g, font, "By kind", sx, sy, side);
        sy += 12;
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (CompoundTag b : all) kinds.merge(b.getString("title"), 1, Integer::sum);
        List<java.util.Map.Entry<String, Integer>> sorted = new ArrayList<>(kinds.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        int shown = 0;
        int half = (side - 4) / 2;
        for (java.util.Map.Entry<String, Integer> e : sorted) {
            if (sy > y + ch / 2 + 10) break;
            int kx = sx + (shown % 2) * (half + 4);
            small(g, Ui.clip(font, e.getKey(), (int) ((half - 14) / 0.75)), kx, sy, Ui.INK);
            Ui.right(g, font, Integer.toString(e.getValue()), kx + half, sy - 1, Ui.MUTED);
            if (shown % 2 == 1) sy += 9;
            shown++;
        }
        if (shown % 2 == 1) sy += 9;
        sy += 6;
        Ui.section(g, font, "To build, in order", sx, sy, side);
        sy += 12;
        List<String> queue = strings("queue");
        if (queue.isEmpty()) { small(g, "Nothing wanted now.", sx, sy, Ui.MUTED); sy += 9; }
        for (int i = 0; i < queue.size() && sy < y + ch - 30; i++) {
            small(g, Ui.clip(font, (i + 1) + ". " + capital(queue.get(i)), (int) (side / 0.75)), sx, sy, i == 0 ? Ui.GOOD : Ui.INK);
            sy += 9;
        }
        String next = now().getString("next");
        if (!next.isEmpty()) {
            sy += 3;
            for (FormattedCharSequence line : font.split(Component.literal(next), (int) (side / 0.75))) {
                if (sy > y + ch - 9) break;
                small(g, line, sx, sy, Ui.MUTED);
                sy += 9;
            }
        }
    }

    /** The town's records and totals from all its books, the averages over the range, and where it is heading. */
    private void records(GuiGraphics g, int x, int y, int cw, int ch) {
        int[] days = days();
        int half = (cw - 10) / 2;
        int ly = y;
        Ui.section(g, font, "Records, in all the books", x, ly, half);
        ly += 12;
        String[][] recs = {
            { "Most folk", "pop" }, { "Most made in a day", "output" }, { "Fullest treasury", "coins" }, { "Greatest worth", "worth" },
            { "Most born in a day", "born" }, { "Most content", "content" }, { "Most buildings", "buildings" }, { "Most renown", "renown" } };
        for (String[] r : recs) {
            int[] s = series(r[1]);
            int best = -1, at = -1;
            for (int i = 0; i < s.length; i++) if (s[i] > best) { best = s[i]; at = i; }
            if (at < 0) continue;
            g.drawString(font, r[0], x, ly, Ui.INK, false);
            Ui.right(g, font, best + (r[1].equals("output") || r[1].equals("coins") || r[1].equals("worth") ? "c" : "")
                + "  on day " + days[Math.min(at, days.length - 1)], x + half, ly, Ui.MUTED);
            ly += 10;
        }
        int[] content = series("content");
        int low = Integer.MAX_VALUE, lowAt = -1;
        for (int i = 0; i < content.length; i++) if (content[i] < low) { low = content[i]; lowAt = i; }
        if (lowAt >= 0) {
            g.drawString(font, "Least content", x, ly, Ui.INK, false);
            Ui.right(g, font, low + "  on day " + days[lowAt], x + half, ly, Ui.MUTED);
            ly += 10;
        }
        // The longest run of days the village grew or held its numbers.
        int[] pop = series("pop");
        int run = 0, bestRun = 0;
        for (int i = 1; i < pop.length; i++) {
            run = pop[i] >= pop[i - 1] ? run + 1 : 0;
            bestRun = Math.max(bestRun, run);
        }
        g.drawString(font, "Longest run without a loss", x, ly, Ui.INK, false);
        Ui.right(g, font, bestRun + " days", x + half, ly, Ui.MUTED);
        ly += 14;
        Ui.section(g, font, "All told (" + days.length + " days in the books)", x, ly, half);
        ly += 12;
        int[] wagesAll = series("wages"), inAll = plus(series("takings"), series("sold"), series("tithe"));
        String[][] totals = {
            { "Born", Integer.toString(sumLast(series("born"), days.length)) }, { "Died", Integer.toString(sumLast(series("died"), days.length)) },
            { "Came to live here", Integer.toString(sumLast(series("moved_in"), days.length)) },
            { "Left", Integer.toString(sumLast(series("moved_out"), days.length)) },
            { "Made, all told", shortNum(sumLast(series("output"), days.length)) + "c" },
            { "Money in", shortNum(sumLast(inAll, days.length)) + "c" }, { "Wages paid", shortNum(sumLast(wagesAll, days.length)) + "c" } };
        for (String[] t : totals) {
            if (ly > y + ch - 10) break;
            g.drawString(font, t[0], x, ly, Ui.INK, false);
            Ui.right(g, font, t[1], x + half, ly, Ui.MUTED);
            ly += 10;
        }
        // The right-hand column: the averages over the range, and the forecast.
        int rx = x + half + 10, ry = y;
        int n = span();
        Ui.section(g, font, "A day, on average (" + RANGE_NAMES[range] + ")", rx, ry, half);
        ry += 12;
        String[][] avgs = {
            { "Born", per(series("born"), n) }, { "Died", per(series("died"), n) }, { "Made", per(series("output"), n) + "c" },
            { "Money in", per(inAll, n) + "c" }, { "Wages", per(wagesAll, n) + "c" },
            { "Made per grown folk", ratio(series("output"), series("adults"), n) + "c" } };
        for (String[] a : avgs) {
            g.drawString(font, a[0], rx, ry, Ui.INK, false);
            Ui.right(g, font, a[1], rx + half, ry, Ui.MUTED);
            ry += 10;
        }
        ry += 4;
        Ui.section(g, font, "Where it is heading (in 30 days, at this pace)", rx, ry, half);
        ry += 12;
        String[][] heads = { { "Folk", "pop", "" }, { "Made a day", "output", "c" }, { "Treasury", "coins", "c" }, { "Worth", "worth", "c" },
            { "Buildings", "buildings", "" } };
        for (String[] hd : heads) {
            int[] s = series(hd[1]);
            if (s.length < 3) continue;
            double slope = slope(s, Math.min(14, s.length));
            int then = (int) Math.max(0, Math.round(last(s) + slope * 30));
            g.drawString(font, hd[0], rx, ry, Ui.INK, false);
            String arrow = slope > 0.05 ? "▲ " : slope < -0.05 ? "▼ " : "= ";
            Ui.right(g, font, arrow + last(s) + hd[2] + " → " + then + hd[2], rx + half, ry, slope > 0.05 ? Ui.GOOD : slope < -0.05 ? Ui.BAD : Ui.MUTED);
            ry += 10;
        }
        int[] food = series("food");
        if (food.length >= 3) {
            double fs = slope(food, Math.min(7, food.length));
            String line = fs < -0.5 ? "the larder empties in about " + Math.round(last(food) / -fs) + " days" : fs > 0.5 ? "the larder is filling" : "the larder holds steady";
            g.drawString(font, "Food", rx, ry, Ui.INK, false);
            Ui.right(g, font, line, rx + half, ry, fs < -0.5 ? Ui.BAD : Ui.MUTED);
            ry += 10;
        }
        ry += 4;
        small(g, Ui.clip(font, "Ages: " + ages(), (int) (half / 0.75)), rx, Math.min(ry, y + ch - 9), Ui.FAINT);
    }

    private String per(int[] s, int n) {
        int k = Math.min(n, s.length);
        if (k == 0) return "0";
        double v = sumLast(s, k) / (double) k;
        return v >= 10 ? Long.toString(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private String ratio(int[] top, int[] bottom, int n) {
        int k = Math.min(n, Math.min(top.length, bottom.length));
        int t = sumLast(top, k), b = sumLast(bottom, k);
        return b == 0 ? "0" : Long.toString(Math.round(t / (double) b));
    }

    /** The least-squares slope of the last n values: how much it moves a day. */
    private static double slope(int[] s, int n) {
        int k = Math.min(n, s.length);
        if (k < 2) return 0;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < k; i++) {
            double xv = i, yv = s[s.length - k + i];
            sx += xv; sy += yv; sxx += xv * xv; sxy += xv * yv;
        }
        double d = k * sxx - sx * sx;
        return d == 0 ? 0 : (k * sxy - sx * sy) / d;
    }

    private void news(GuiGraphics g, int x, int y, int cw, int ch) {
        List<String> all = new ArrayList<>();
        List<String> n = strings("neighbours");
        if (!n.isEmpty()) {
            all.add("Neighbours:");
            for (String s : n) all.add("  " + s);
            all.add("");
        }
        all.add("The chronicle, latest first:");
        all.addAll(strings("news"));
        lines(g, all, x, y, cw, ch);
    }

    /** The ages and the day each began, read from the books: "Wood Age day 1 · Stone Age day 6 · ...". */
    private String ages() {
        int[] age = series("age"), days = days();
        String[] names = { "Wood", "Stone", "Iron", "Diamond", "Nether", "Beyond" };
        StringBuilder sb = new StringBuilder();
        int was = -1;
        for (int i = 0; i < age.length && i < days.length; i++) {
            if (age[i] == was) continue;
            was = age[i];
            if (sb.length() > 0) sb.append(" · ");
            sb.append(was < names.length ? names[was] : "Age " + was).append(" Age from day ").append(days[i]);
        }
        return sb.toString();
    }

    /** A line of the analysis: its mark (+ - = !) as a coloured tick, the rest wrapped. Returns the next line's y. */
    private int driverLine(GuiGraphics g, String d, int x, int y, int cw, int maxLines) {
        if (d.isEmpty()) return y;
        char mark = d.charAt(0);
        String text = d.substring(1);
        int colour = switch (mark) {
            case '+' -> GREEN;
            case '-' -> RED;
            case '!' -> PURPLE;
            default -> GREY;
        };
        String sym = switch (mark) {
            case '+' -> "▲";
            case '-' -> "▼";
            case '!' -> "★";
            default -> "•";
        };
        g.drawString(font, sym, x, y, colour, false);
        int n = 0;
        for (FormattedCharSequence line : font.split(Component.literal(text), cw - 12)) {
            if (n++ >= maxLines) break;
            g.drawString(font, line, x + 10, y, mark == '!' ? colour : Ui.INK, false);
            y += 10;
        }
        return y;
    }

    private void lines(GuiGraphics g, List<String> text, int x, int y, int cw, int ch) {
        List<FormattedCharSequence> all = new ArrayList<>();
        for (String t : text) all.addAll(font.split(Component.literal(t), cw));
        int rows = ch / 10;
        int start = Math.max(0, Math.min(scroll, Math.max(0, all.size() - rows)));
        int ly = y;
        for (int i = start; i < Math.min(all.size(), start + rows); i++) {
            g.drawString(font, all.get(i), x, ly, Ui.INK, false);
            ly += 10;
        }
        if (all.isEmpty()) g.drawString(font, "Nothing written yet.", x, y, Ui.MUTED, false);
    }

    private void board(GuiGraphics g, int x, int y, int cw, int ch) {
        List<String> parts = new ArrayList<>();
        parts.add(data.getString("board_title"));
        for (String p : data.getString("board").split("\n")) parts.add(p);
        lines(g, parts, x, y, cw, ch);
    }

    // ------------------------------------------------------------------ the charts

    record Series(String name, int[] values, int colour) {}

    /** A line chart over the range: the lines, a scale, the first and last day, a key; the day under the mouse read out. */
    private void chart(GuiGraphics g, int x, int y, int cw, int ch, String title, int mx, int my, Series... all) {
        int[] days = days();
        int n = span();
        int from = days.length - n;
        small(g, Ui.clip(font, title, (int) ((cw - 4) / 0.75)), x, y, Ui.FAINT);
        int px = x + 24, py = y + 9, pw = cw - 26, ph = ch - 20;
        g.fill(px, py, px + pw, py + ph, 0xFFD4D4D4);
        g.renderOutline(px - 1, py - 1, pw + 2, ph + 2, Ui.EDGE_SOFT);
        if (n <= 0 || pw < 10 || ph < 10) return;
        int max = 1, min = 0;
        for (Series s : all) for (int i = Math.max(0, s.values().length - n); i < s.values().length; i++) {
            max = Math.max(max, s.values()[i]);
            min = Math.min(min, s.values()[i]);
        }
        max = niceUp(max);
        // The scale: four lines, labelled.
        for (int k = 0; k <= 4; k++) {
            int gy = py + ph - (int) ((ph - 1) * k / 4.0);
            g.fill(px, gy, px + pw, gy + 1, k == 0 ? Ui.EDGE_SOFT : 0xFFC0C0C0);
            String lbl = shortNum(min + (max - min) * k / 4);
            small(g, lbl, px - 2 - (int) (font.width(lbl) * 0.75), gy - 3, Ui.FAINT);
        }
        small(g, "day " + days[from], px, py + ph + 2, Ui.FAINT);
        String lastDay = "day " + days[days.length - 1];
        small(g, lastDay, px + pw - (int) (font.width(lastDay) * 0.75), py + ph + 2, Ui.FAINT);
        // The lines.
        for (Series s : all) {
            int[] v = s.values();
            int off = v.length - n;
            int prevX = -1, prevY = -1;
            for (int i = 0; i < n; i++) {
                if (off + i < 0) continue;
                int sx = px + (n == 1 ? pw / 2 : (int) Math.round(i * (pw - 1) / (double) (n - 1)));
                int sy = py + ph - 1 - (int) Math.round((v[off + i] - min) * (ph - 2) / (double) Math.max(1, max - min));
                if (prevX >= 0) line(g, prevX, prevY, sx, sy, s.colour());
                else g.fill(sx, sy, sx + 1, sy + 1, s.colour());
                prevX = sx;
                prevY = sy;
            }
        }
        // The key.
        int kx = px + 3;
        for (Series s : all) {
            int kw = (int) (font.width(s.name()) * 0.75) + 10;
            if (kx + kw > px + pw) break;
            g.fill(kx, py + 3, kx + 6, py + 5, s.colour());
            small(g, s.name(), kx + 8, py + 1, Ui.MUTED);
            kx += kw + 4;
        }
        // The day under the mouse.
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            int i = n == 1 ? 0 : (int) Math.round((mx - px) * (n - 1) / (double) (pw - 1));
            i = Math.max(0, Math.min(n - 1, i));
            int sx = px + (n == 1 ? pw / 2 : (int) Math.round(i * (pw - 1) / (double) (n - 1)));
            g.fill(sx, py, sx + 1, py + ph, 0x80000000);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("Day " + days[from + i]));
            for (Series s : all) {
                int idx = s.values().length - n + i;
                if (idx >= 0 && idx < s.values().length) tip.add(Component.literal(s.name() + ": " + s.values()[idx]).withColor(s.colour() & 0xFFFFFF));
            }
            hover = tip;
            hoverX = mx;
            hoverY = my;
        }
    }

    /** A bar chart over the range: each day's bars side by side, each series its colour. */
    private void bars(GuiGraphics g, int x, int y, int cw, int ch, String title, int mx, int my, Series... all) {
        int[] days = days();
        int n = span();
        small(g, Ui.clip(font, title, (int) ((cw - 4) / 0.75)), x, y, Ui.FAINT);
        int px = x + 24, py = y + 9, pw = cw - 26, ph = ch - 20;
        g.fill(px, py, px + pw, py + ph, 0xFFD4D4D4);
        g.renderOutline(px - 1, py - 1, pw + 2, ph + 2, Ui.EDGE_SOFT);
        if (n <= 0 || pw < 10 || ph < 10) return;
        int max = 1;
        for (Series s : all) for (int i = Math.max(0, s.values().length - n); i < s.values().length; i++) max = Math.max(max, s.values()[i]);
        max = niceUp(max);
        for (int k = 0; k <= 2; k++) {
            int gy = py + ph - (int) ((ph - 1) * k / 2.0);
            g.fill(px, gy, px + pw, gy + 1, k == 0 ? Ui.EDGE_SOFT : 0xFFC0C0C0);
            String lbl = shortNum(max * k / 2);
            small(g, lbl, px - 2 - (int) (font.width(lbl) * 0.75), gy - 3, Ui.FAINT);
        }
        double slot = pw / (double) n;
        int k = all.length;
        for (int i = 0; i < n; i++) {
            int sx = px + (int) (i * slot);
            int bw = Math.max(1, (int) (slot / k) - (slot > 4 ? 1 : 0));
            for (int s = 0; s < k; s++) {
                int[] v = all[s].values();
                int idx = v.length - n + i;
                if (idx < 0) continue;
                int bh = (int) Math.round(v[idx] * (ph - 1) / (double) max);
                if (bh <= 0) continue;
                g.fill(sx + s * bw, py + ph - bh, sx + s * bw + bw, py + ph, all[s].colour());
            }
        }
        small(g, "day " + days[days.length - n], px, py + ph + 2, Ui.FAINT);
        String lastDay = "day " + days[days.length - 1];
        small(g, lastDay, px + pw - (int) (font.width(lastDay) * 0.75), py + ph + 2, Ui.FAINT);
        int kx = px + 3;
        for (Series s : all) {
            g.fill(kx, py + 3, kx + 6, py + 5, s.colour());
            small(g, s.name(), kx + 8, py + 1, Ui.MUTED);
            kx += (int) (font.width(s.name()) * 0.75) + 14;
        }
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            int i = Math.max(0, Math.min(n - 1, (int) ((mx - px) / slot)));
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("Day " + days[days.length - n + i]));
            for (Series s : all) {
                int idx = s.values().length - n + i;
                if (idx >= 0) tip.add(Component.literal(s.name() + ": " + s.values()[idx]).withColor(s.colour() & 0xFFFFFF));
            }
            hover = tip;
            hoverX = mx;
            hoverY = my;
        }
    }

    /** A line a pixel wide, stepped (GuiGraphics draws only boxes). */
    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int colour) {
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) { g.fill(x0, y0, x0 + 1, y0 + 1, colour); return; }
        for (int i = 0; i <= steps; i++) {
            int x = x0 + Math.round(dx * i / (float) steps), y = y0 + Math.round(dy * i / (float) steps);
            g.fill(x, y, x + 1, y + 1, colour);
        }
    }

    private static int niceUp(int v) {
        if (v <= 4) return 4;
        double mag = Math.pow(10, Math.floor(Math.log10(v)));
        double[] steps = { 1, 2, 2.5, 5, 10 };
        for (double s : steps) if (s * mag >= v) return (int) Math.round(s * mag);
        return (int) Math.round(10 * mag);
    }

    private static String shortNum(int v) {
        if (Math.abs(v) >= 10000) return (v / 1000) + "k";
        if (Math.abs(v) >= 1000) return String.format(Locale.ROOT, "%.1fk", v / 1000.0);
        return Integer.toString(v);
    }

    private void small(GuiGraphics g, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private void small(GuiGraphics g, FormattedCharSequence s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the mouse

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // The range buttons.
        int rx = left + w - 8;
        for (int i = RANGE_NAMES.length - 1; i >= 0; i--) {
            int bw = font.width(RANGE_NAMES[i]) + 8;
            rx -= bw;
            if (mx >= rx && mx < rx + bw - 2 && my >= top + 6 && my < top + 18) { range = i; return true; }
        }
        if (mx >= left + w - 8 - font.width("Close ×") && mx < left + w - 8 && my >= top + 20 && my < top + 30) { onClose(); return true; }
        // The tabs.
        int[][] boxes = tabBoxes();
        for (int i = 0; i < TABS.length; i++) {
            int tx = boxes[i][0], ty = boxes[i][1], tw = boxes[i][2];
            if (mx >= tx && mx < tx + tw && my >= ty && my < ty + 13) { tab = i; scroll = 0; return true; }
        }
        int x = left + 8, y = pageTop(), cw = w - 16;
        if (page().equals("Jobs")) {
            // A trade picked from the table: its own history below.
            List<CompoundTag> jobs = compounds("jobs");
            jobs.sort(Comparator.comparingInt((CompoundTag c) -> c.getInt("week")).reversed().thenComparing(c -> -c.getInt("hands")));
            int ch = top + h - 8 - y;
            int tableH = Math.min(ch / 2 + 20, 14 + jobs.size() * 11);
            int maxRows = (tableH - 9) / 11;
            int start = Math.max(0, Math.min(scroll, Math.max(0, jobs.size() - maxRows)));
            int row = (int) ((my - (y + 9)) / 11);
            if (mx >= x && mx < x + cw && my >= y + 9 && row >= 0 && row < maxRows && start + row < jobs.size()) {
                String id = jobs.get(start + row).getString("id");
                selectedTrade = id.equals(selectedTrade) ? null : id;
                return true;
            }
        }
        if (page().equals("Folk") && my >= y && my < y + 9) {
            for (int i = FOLK_COLS.length - 1; i >= 0; i--) {
                if (mx >= x + FOLK_COLS[i]) {
                    if (sortColumn == i) sortDown = !sortDown;
                    else { sortColumn = i; sortDown = i != 0 && i != 1 && i != 8 && i != 9; }
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Left and right: the next page.
        if (key == 263) { tab = (tab + TABS.length - 1) % TABS.length; scroll = 0; return true; }
        if (key == 262) { tab = (tab + 1) % TABS.length; scroll = 0; return true; }
        return super.keyPressed(key, scan, mods);
    }
}
