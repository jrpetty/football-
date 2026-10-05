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
 * <p>Sixteen pages, picked along the top: the <b>Overview</b> (the figures that matter, with how they
 * have moved over the week, and the first of what is driving it); <b>Growth</b> (its people over
 * time, births against deaths, comings and goings, what they died of); <b>Money</b> (what it makes,
 * takes in and pays out each day, the treasury and its worth, and its output by kind);
 * <b>Production</b> (every item it makes: yesterday, a day, a month, all told, used, in store,
 * worth, trend, who makes it, and the leader's reading of it against what is short); <b>Jobs</b>
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

    private static final String[] TABS = { "Overview", "Growth", "Money", "Production", "Shops", "Jobs", "Folk", "Society", "Leader", "Homes",
        "Buildings", "Stores", "Why", "Trends", "Records", "News", "Board" };
    /** The pages that read today's figures, not the books (so they show from the first day). */
    private static final java.util.Set<String> TODAY_PAGES = java.util.Set.of("Folk", "Society", "Leader", "Buildings", "Why", "News", "Board",
        "Shops", "Homes");
    private static final int[] RANGES = { 7, 30, 100, 0 };
    private static final String[] RANGE_NAMES = { "7d", "30d", "100d", "All" };

    /** Line colours, readable on the light panel. */
    private static final int BLUE = 0xFF2E6FBF, RED = 0xFFB83227, GREEN = 0xFF23803A, AMBER = 0xFFB7791F, PURPLE = 0xFF7D3C98,
        TEAL = 0xFF16867A, BROWN = 0xFF6E4B2A, GREY = 0xFF5A5A5A, PINK = 0xFFC2457A;
    private static final int[] PALETTE = { BLUE, RED, GREEN, AMBER, PURPLE, TEAL, BROWN, PINK, GREY };

    private final CompoundTag data;
    private int tab, range = 1, scroll, w, h, left, top;
    private String selectedTrade;
    /** The production page: the kind shown (null: all), the column sorted by and which way, the item picked. */
    private String prodKind, prodItem;
    /** The shops page: the seller shown (by id; null: the first that stands). */
    private String shopSeller;
    private int prodSort = 7;
    private boolean prodDown = true;
    /** What can be clicked on the page just drawn: its box and what a click does. */
    private final List<Zone> zones = new ArrayList<>();

    private record Zone(int x0, int y0, int x1, int y1, Runnable act) {}
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
        zones.clear();
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
                case "Production" -> production(g, x, y, cw, ch, mouseX, mouseY);
                case "Shops" -> shops(g, x, y, cw, ch, mouseX, mouseY);
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
        int[] in = plus(series("takings"), series("sold"), series("tithe"), series("rent"), series("house_sales"));
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
            wages = sumLast(series("wages"), n), spent = sumLast(series("spent"), n), rent = sumLast(series("rent"), n),
            houses = sumLast(series("house_sales"), n);
        by += 2;
        small(g, Ui.clip(font, "In: " + takings + " from the work, " + sold + " sold, " + tithe + " tithe, " + rent + " rent, " + houses + " houses sold.",
            (int) (half / 0.75)), bx, by, Ui.MUTED);
        small(g, "Out: " + wages + " in wages, " + spent + " bought in. Net " + (takings + sold + tithe + rent + houses - wages - spent) + ".", bx, by + 9, Ui.MUTED);
    }

    // ------------------------------------------------------------------ production

    private static final String[] KINDS = { "all", "food", "timber", "stone", "ore", "animal", "craft", "plant", "other" };
    private static final String[] KIND_WORDS = { "All", "Food", "Timber", "Stone", "Ore & metal", "Wool, hides", "Crafts", "Plants", "Other" };
    private static final String[] PROD_HEADS = { "Item", "Yest.", "A day", "30 d", "In all", "Used", "Stock", "c a day", "" };

    private static net.minecraft.world.item.ItemStack stackOf(String id) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null) return net.minecraft.world.item.ItemStack.EMPTY;
        return new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl));
    }

    private static String itemName(String id) {
        net.minecraft.world.item.ItemStack s = stackOf(id);
        return s.isEmpty() ? id : s.getHoverName().getString();
    }

    /** An item's icon, at half size (a row is ten pixels). */
    private void icon(GuiGraphics g, String id, int x, int y, float scale) {
        net.minecraft.world.item.ItemStack s = stackOf(id);
        if (s.isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1F);
        g.renderItem(s, 0, 0);
        g.pose().popPose();
    }

    private static String num(double v) {
        if (v >= 10000) return shortNum((int) Math.min(Integer.MAX_VALUE, Math.round(v)));
        if (v >= 10 || v == 0 || v == Math.rint(v)) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private double worthADay(CompoundTag r) {
        return r.getInt("w7") / 7.0 * r.getInt("each100") / 100.0;
    }

    /**
     * What the village makes, item by item: every log, stone, loaf and lantern it has brought in or
     * made, yesterday, a day this week, the month, all told; what it used of it making other things,
     * what it has in the stores, what it is worth a day, and whether it is making more or less of it.
     * Pick a kind along the top, sort by any column, click an item for its own story.
     */
    private void production(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        CompoundTag p = data.getCompound("production");
        List<CompoundTag> rows = new ArrayList<>();
        ListTag l = p.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) rows.add(l.getCompound(i));
        int[] made = p.getIntArray("made"), kinds = p.getIntArray("kinds"), worth = p.getIntArray("worth");
        int[] adults = series("adults");
        // The cards.
        int cardW = (cw - 4 * 4) / 5, cardH = 30;
        int week = Math.min(7, made.length);
        double perDay = week == 0 ? 0 : sumLast(made, week) / (double) week;
        double worthDay = week == 0 ? 0 : sumLast(worth, week) / (double) week;
        int distinct = 0;
        for (CompoundTag r : rows) if (r.getInt("w7") > 0) distinct++;
        card(g, x, y, cardW, cardH, "Made yesterday", num(last(made)), delta(made, 7, ""), GREEN);
        card(g, x + (cardW + 4), y, cardW, cardH, "A day, this week", num(perDay), "things brought in or made", BLUE);
        card(g, x + 2 * (cardW + 4), y, cardW, cardH, "Kinds of thing", Integer.toString(distinct), "made this week", PURPLE);
        card(g, x + 3 * (cardW + 4), y, cardW, cardH, "Worth a day", num(worthDay) + "c", "at the market's prices", AMBER);
        card(g, x + 4 * (cardW + 4), y, cardW, cardH, "Per grown folk", num(last(adults) == 0 ? 0 : perDay / last(adults)), "things a day each", TEAL);
        // The kinds.
        int ky = y + cardH + 4, kx = x;
        for (int i = 0; i < KINDS.length; i++) {
            String k = KINDS[i];
            boolean on = k.equals("all") ? prodKind == null : k.equals(prodKind);
            int kw = (int) (font.width(KIND_WORDS[i]) * 0.75) + 8;
            g.fill(kx, ky, kx + kw, ky + 10, on ? Ui.ROW_PICK : Ui.ROW);
            g.renderOutline(kx, ky, kw, 10, Ui.EDGE_SOFT);
            small(g, KIND_WORDS[i], kx + 4, ky + 2, on ? Ui.GOOD : Ui.MUTED);
            final String pick = k.equals("all") ? null : k;
            zones.add(new Zone(kx, ky, kx + kw, ky + 10, () -> { prodKind = pick; scroll = 0; }));
            kx += kw + 2;
        }
        // The table.
        int ty = ky + 14;
        int side = Math.max(136, cw * 30 / 100);
        int tw = cw - side - 8;
        // The name a third of the row, the seven figures evenly after it, the trend at the end.
        int nameW = tw * 33 / 100, figW = (tw - 18 - nameW) / 7;
        int[] cols = new int[8];
        for (int i = 1; i < 8; i++) cols[i] = nameW + (i - 1) * figW;
        List<CompoundTag> shown = new ArrayList<>();
        for (CompoundTag r : rows) if (prodKind == null || prodKind.equals(r.getString("kind"))) shown.add(r);
        Comparator<CompoundTag> order = switch (prodSort) {
            case 0 -> Comparator.comparing((CompoundTag r) -> itemName(r.getString("id")));
            case 1 -> Comparator.comparingInt((CompoundTag r) -> r.getInt("d1"));
            case 2 -> Comparator.comparingInt((CompoundTag r) -> r.getInt("w7"));
            case 3 -> Comparator.comparingInt((CompoundTag r) -> r.getInt("m30"));
            case 4 -> Comparator.comparingLong((CompoundTag r) -> r.getLong("total"));
            case 5 -> Comparator.comparingInt((CompoundTag r) -> r.getInt("used7"));
            case 6 -> Comparator.comparingInt((CompoundTag r) -> r.getInt("on_hand"));
            case 8 -> Comparator.comparingInt((CompoundTag r) -> r.getInt("w7") - r.getInt("prev7"));
            default -> Comparator.comparingDouble(this::worthADay);
        };
        shown.sort(prodDown ? order.reversed() : order);
        for (int i = 0; i < PROD_HEADS.length; i++) {
            int cx0 = i < cols.length ? cols[i] : tw - 18;
            int cx1 = i + 1 < cols.length ? cols[i + 1] : i + 1 == cols.length ? tw - 18 : tw;
            String hd = (i == 8 ? "±" : PROD_HEADS[i]) + (i == prodSort ? (prodDown ? "▼" : "▲") : "");
            small(g, Ui.clip(font, hd, (int) ((cx1 - cx0 - 2) / 0.75)), x + cx0, ty, i == prodSort ? Ui.GOOD : Ui.FAINT);
            final int col = i;
            zones.add(new Zone(x + cx0, ty, x + cx1, ty + 9, () -> {
                if (prodSort == col) prodDown = !prodDown; else { prodSort = col; prodDown = col != 0; }
            }));
        }
        int ry = ty + 10;
        int rows_ = Math.max(1, (y + ch - 12 - ry) / 10);
        int start = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - rows_)));
        for (int i = start; i < Math.min(shown.size(), start + rows_); i++) {
            CompoundTag r = shown.get(i);
            String id = r.getString("id");
            boolean picked = id.equals(prodItem);
            boolean over = mx >= x && mx < x + tw && my >= ry - 1 && my < ry + 9;
            g.fill(x - 2, ry - 1, x + tw, ry + 9, picked ? Ui.ROW_PICK : over ? Ui.HI : i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
            icon(g, id, x, ry - 1, 0.6F);
            int trend = r.getInt("w7") - r.getInt("prev7");
            String[] cells = { itemName(id), num(r.getInt("d1")), num(r.getInt("w7") / 7.0), num(r.getInt("m30")), num(r.getLong("total")),
                num(r.getInt("used7")), num(r.getInt("on_hand")), num(worthADay(r)) + "c" };
            for (int c = 0; c < cells.length; c++) {
                int colW = (c + 1 < cols.length ? cols[c + 1] : tw - 18) - cols[c] - 2 - (c == 0 ? 11 : 0);
                small(g, Ui.clip(font, cells[c], (int) (colW / 0.75)), x + cols[c] + (c == 0 ? 11 : 0), ry + 1, c == 0 ? Ui.INK : Ui.INK);
            }
            String arrow = r.getInt("prev7") == 0 && r.getInt("w7") > 0 ? "new" : trend > 0 ? "▲" : trend < 0 ? "▼" : "=";
            small(g, arrow, x + tw - 16, ry + 1, trend > 0 ? Ui.GOOD : trend < 0 ? Ui.BAD : Ui.FAINT);
            zones.add(new Zone(x, ry - 1, x + tw, ry + 9, () -> prodItem = id.equals(prodItem) ? null : id));
            ry += 10;
        }
        if (shown.isEmpty()) small(g, "Nothing of this kind made yet: the books are written each morning.", x, ry + 2, Ui.MUTED);
        small(g, Ui.clip(font, shown.size() + " things · click a heading to sort, an item for its story" + (shown.size() > rows_ ? " · scroll for more" : ""),
            (int) (tw / 0.75)), x, y + ch - 9, Ui.FAINT);
        // The side: the item picked, or the whole; and the leader's reading.
        int sx = x + tw + 8, sy = ty;
        CompoundTag pick = null;
        for (CompoundTag r : rows) if (r.getString("id").equals(prodItem)) pick = r;
        int chartH = Math.max(50, (y + ch - sy) / 2 - 4);
        if (pick != null) {
            String id = pick.getString("id");
            icon(g, id, sx, sy - 2, 1F);
            g.drawString(font, Ui.clip(font, itemName(id), side - 20), sx + 18, sy + 2, Ui.INK, false);
            sy += 16;
            String[] facts = {
                "Made by: " + (pick.getString("by").isEmpty() ? "brought in" : pick.getString("by")),
                num(pick.getInt("w7") / 7.0) + " a day this week, " + num(pick.getInt("prev7") / 7.0) + " the week before",
                num(pick.getLong("total")) + " made in all, " + num(pick.getLong("used_total")) + " used making other things",
                num(pick.getInt("on_hand")) + " in the stores" + (pick.getInt("w7") > 0 && pick.getInt("used7") > pick.getInt("w7")
                    ? " — used faster than made" : ""),
                "Worth " + num(pick.getInt("each100") / 100.0) + "c each, " + num(worthADay(pick)) + "c a day" };
            for (String f : facts) {
                small(g, Ui.clip(font, f, (int) (side / 0.75)), sx, sy, Ui.MUTED);
                sy += 9;
            }
            sy += 2;
            chart(g, sx, sy, side, chartH, "Made a day", mx, my, new Series(itemName(id), pick.getIntArray("series"), GREEN));
        } else {
            chart(g, sx, sy, side, chartH, "Things made a day, and their worth", mx, my, new Series("Made", made, GREEN),
                new Series("Worth", worth, AMBER), new Series("Kinds", kinds, PURPLE));
        }
        sy += chartH + 12;
        Ui.section(g, font, "What the leader reads from it", sx, sy, side);
        sy += 12;
        List<String> reading = new ArrayList<>();
        ListTag rl = p.getList("reading", Tag.TAG_STRING);
        for (int i = 0; i < rl.size(); i++) reading.add(rl.getString(i));
        if (reading.isEmpty()) { small(g, "Nothing short: it makes what it needs.", sx, sy, Ui.GOOD); sy += 9; }
        for (String line : reading) {
            for (FormattedCharSequence part : font.split(Component.literal("· " + line), (int) (side / 0.75))) {
                if (sy > y + ch - 9) break;
                small(g, part, sx, sy, line.contains("none at all") ? Ui.BAD : line.contains("more hands") ? Ui.WARN : Ui.INK);
                sy += 9;
            }
        }
    }

    // ------------------------------------------------------------------ the shops

    private static final String[] SHOP_HEADS = { "Ware", "Stock / target", "Sold 7d", "Missed", "Made 7d", "Price", "Cost", "Note" };

    /**
     * The village's sellers and their books: the shop, the café, the tavern, the market and the
     * stores' counter. For each, who keeps it, whether it is open, what it sold and took this week and
     * what it made; and every ware: what it has against what it means to keep (the target follows
     * what sells), sold, asked for and missed, made, its price (and any markdown on slow stock), what
     * one costs to make, and what it is short of to make more.
     */
    private void shops(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        CompoundTag rep = data.getCompound("shops");
        List<CompoundTag> sellers = new ArrayList<>();
        ListTag sl = rep.getList("sellers", Tag.TAG_COMPOUND);
        for (int i = 0; i < sl.size(); i++) sellers.add(sl.getCompound(i));
        if (sellers.isEmpty()) {
            g.drawString(font, "No shop, café, tavern or market stands yet.", x, y, Ui.MUTED, false);
            return;
        }
        CompoundTag pick = null;
        for (CompoundTag t : sellers) if (t.getString("id").equals(shopSeller)) pick = t;
        if (pick == null) for (CompoundTag t : sellers) if (t.getBoolean("built")) { pick = t; break; }
        if (pick == null) pick = sellers.get(0);
        // The sellers, along the top.
        int kx = x;
        for (CompoundTag t : sellers) {
            String label = capital(t.getString("name")) + (t.getBoolean("built") ? "" : " (none yet)");
            int kw = (int) (font.width(label) * 0.75) + 10;
            boolean on = t == pick;
            g.fill(kx, y, kx + kw, y + 11, on ? Ui.ROW_PICK : Ui.ROW);
            g.renderOutline(kx, y, kw, 11, Ui.EDGE_SOFT);
            small(g, label, kx + 5, y + 2, on ? Ui.GOOD : t.getBoolean("built") ? Ui.INK : Ui.FAINT);
            final String id = t.getString("id");
            zones.add(new Zone(kx, y, kx + kw, y + 11, () -> { shopSeller = id; scroll = 0; }));
            kx += kw + 3;
        }
        if (rep.getBoolean("marketDay")) small(g, "Market day today", x + cw - (int) (font.width("Market day today") * 0.75), y + 2, Ui.GOOD);
        // The seller's week.
        int sy = y + 15;
        int cardW = (cw - 3 * 4) / 4, cardH = 28;
        card(g, x, sy, cardW, cardH, "Kept by", pick.getString("keeper").isEmpty() ? "nobody" : pick.getString("keeper"),
            pick.getBoolean("open") ? "open" : pick.getBoolean("built") ? "shut: nobody to keep it" : "not built yet", pick.getBoolean("open") ? GREEN : RED);
        card(g, x + cardW + 4, sy, cardW, cardH, "Sold this week", num(pick.getInt("sold7")), pick.getInt("soldToday") + " today", BLUE);
        card(g, x + 2 * (cardW + 4), sy, cardW, cardH, "Taken this week", pick.getInt("coin7") + "c", "at the counter", AMBER);
        card(g, x + 3 * (cardW + 4), sy, cardW, cardH, "Made this week", pick.getBoolean("makes") ? num(pick.getInt("made7")) : "—",
            pick.getBoolean("makes") ? pick.getInt("madeToday") + " today" : "sells what others make", PURPLE);
        int ty = sy + cardH + 6;
        ListTag shorts = pick.getList("short", Tag.TAG_STRING);
        if (shorts.size() > 0) {
            StringBuilder sb = new StringBuilder("Short of: ");
            for (int i = 0; i < shorts.size(); i++) sb.append(i == 0 ? "" : "; ").append(shorts.getString(i));
            small(g, Ui.clip(font, sb.toString(), (int) (cw / 0.75)), x, ty, Ui.WARN);
            ty += 10;
        }
        // The wares.
        int[] cols = { 0, cw * 24 / 100, cw * 44 / 100, cw * 52 / 100, cw * 60 / 100, cw * 68 / 100, cw * 77 / 100, cw * 84 / 100 };
        for (int i = 0; i < SHOP_HEADS.length; i++) small(g, SHOP_HEADS[i], x + cols[i], ty, Ui.FAINT);
        ty += 10;
        List<CompoundTag> wares = new ArrayList<>();
        ListTag wl = pick.getList("wares", Tag.TAG_COMPOUND);
        for (int i = 0; i < wl.size(); i++) wares.add(wl.getCompound(i));
        wares.sort(Comparator.comparingInt((CompoundTag r) -> -r.getInt("sold7")).thenComparing(r -> r.getString("name")));
        int rowsFit = Math.max(1, (y + ch - 10 - ty) / 10);
        int start = Math.max(0, Math.min(scroll, Math.max(0, wares.size() - rowsFit)));
        for (int i = start; i < Math.min(wares.size(), start + rowsFit); i++) {
            CompoundTag r = wares.get(i);
            boolean over = mx >= x && mx < x + cw && my >= ty - 1 && my < ty + 9;
            g.fill(x - 2, ty - 1, x + cw, ty + 9, over ? Ui.HI : i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
            icon(g, r.getString("item"), x, ty - 1, 0.6F);
            small(g, Ui.clip(font, r.getString("name"), (int) ((cols[1] - 14) / 0.75)), x + 11, ty + 1, Ui.INK);
            int have = r.getInt("onHand"), target = Math.max(1, r.getInt("target"));
            int bw = cols[2] - cols[1] - 44;
            float frac = Math.min(1f, have / (float) target);
            Ui.bar(g, x + cols[1], ty + 1, bw, 6, frac, frac >= 1f ? GREEN : frac >= 0.5f ? AMBER : RED);
            small(g, have + " / " + r.getInt("target"), x + cols[1] + bw + 3, ty + 1, Ui.MUTED);
            small(g, num(r.getInt("sold7")), x + cols[2], ty + 1, Ui.INK);
            small(g, num(r.getInt("missed7")), x + cols[3], ty + 1, r.getInt("missed7") > 0 ? Ui.BAD : Ui.FAINT);
            small(g, num(r.getInt("made7")), x + cols[4], ty + 1, Ui.INK);
            String price = r.getInt("price") + "c" + (r.getInt("markdown") > 0 ? " -" + r.getInt("markdown") + "%" : "");
            small(g, price, x + cols[5], ty + 1, r.getInt("markdown") > 0 ? Ui.WARN : Ui.INK);
            small(g, num(r.getDouble("cost")) + "c", x + cols[6], ty + 1, Ui.MUTED);
            String note = !r.getString("short").isEmpty() ? "short: " + r.getString("short") : r.getString("status");
            small(g, Ui.clip(font, note, (int) ((cw - cols[7]) / 0.75)), x + cols[7], ty + 1, !r.getString("short").isEmpty() ? Ui.WARN : Ui.MUTED);
            if (over) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(r.getString("name")));
                tip.add(Component.literal("On hand " + have + ", keeps " + r.getInt("target") + " (usually " + r.getInt("usual") + ", between "
                    + r.getInt("fewest") + " and " + r.getInt("most") + ")"));
                tip.add(Component.literal("Sold " + r.getInt("soldToday") + " today, " + r.getInt("soldYesterday") + " yesterday, " + r.getInt("sold7")
                    + " this week; missed " + r.getInt("missed7")));
                if (!r.getString("how").isEmpty()) tip.add(Component.literal("Made by " + r.getString("maker") + ": " + r.getString("how")));
                if (!r.getString("short").isEmpty()) tip.add(Component.literal("Short of " + r.getString("short")));
                hover = tip;
                hoverX = mx;
                hoverY = my;
            }
            ty += 10;
        }
        if (wares.isEmpty()) small(g, "Nothing on its books yet.", x, ty, Ui.MUTED);
        small(g, Ui.clip(font, wares.size() + " wares · the stock it keeps follows what sells · the mouse over a ware for its books"
            + (wares.size() > rowsFit ? " · scroll for more" : ""), (int) (cw / 0.75)), x, y + ch - 9, Ui.FAINT);
    }

    private void jobs(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        List<CompoundTag> jobs = compounds("jobs");
        jobs.sort(Comparator.comparingInt((CompoundTag c) -> c.getInt("week")).reversed().thenComparing(c -> -c.getInt("hands")));
        int tableH = Math.min(ch / 2 + 20, 14 + jobs.size() * 11);
        String[] heads = { "Trade", "Hands", "Lvl", "Pay", "Yesterday", "Week", "Per hand", "Return", "Share of the week's output" };
        int[] cols = { 0, 92, 124, 146, 174, 220, 258, 298, 336 };
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
            float perHand = j.getInt("hands") == 0 ? 0 : j.getInt("week") / (7f * j.getInt("hands"));
            g.drawString(font, (perHand >= 10 || perHand == 0 ? Integer.toString(Math.round(perHand)) : String.format(Locale.ROOT, "%.1f", perHand)) + "c",
                x + cols[6], ry + 1, Ui.INK, false);
            // What a hand makes a day for each coin of its pay: above 1, the trade earns its keep.
            if (j.getInt("hands") > 0 && j.getInt("wage") > 0) {
                float ret = j.getInt("week") / (7f * j.getInt("hands") * j.getInt("wage"));
                g.drawString(font, String.format(Locale.ROOT, "%.1f×", ret), x + cols[7], ry + 1, ret >= 1 ? Ui.GOOD : Ui.BAD, false);
            } else {
                g.drawString(font, "—", x + cols[7], ry + 1, Ui.FAINT, false);
            }
            int bw = cw - cols[8] - 30;
            Ui.bar(g, x + cols[8], ry + 1, bw, 7, j.getInt("share") / 100f, Ui.job(j.getInt("ordinal")));
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
            stacked(g, x, cy, half, chartH, "What each trade made, a day (click a trade for its own)", mx, my, tradesStacked("trade_out", 7));
            chart(g, x + half + 6, cy, half, chartH, "Hands, the five biggest trades", mx, my,
                tradeSeries("trade_hands", 5).toArray(new Series[0]));
        }
    }

    /** Every trade's series, the biggest few by name and the rest together as "Others". */
    private List<Series> tradesStacked(String key, int most) {
        List<Series> top = tradeSeries(key, most);
        CompoundTag t = data.getCompound(key);
        int[] others = new int[days().length];
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (Series s : top) shown.add(s.name());
        boolean any = false;
        for (String id : t.getAllKeys()) {
            String name = id.charAt(0) + id.substring(1).toLowerCase(Locale.ROOT);
            for (CompoundTag j : compounds("jobs")) if (j.getString("id").equals(id)) { name = j.getString("title"); break; }
            if (shown.contains(name)) continue;
            int[] v = t.getIntArray(id);
            for (int i = 0; i < Math.min(v.length, others.length); i++) { others[i] += v[i]; any |= v[i] != 0; }
        }
        List<Series> out = new ArrayList<>(top);
        if (any) out.add(new Series("Others", others, GREY));
        return out;
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

    private static final String[] FOLK_HEADS = { "Name", "Trade", "Lvl", "Age", "Loose", "Pay", "Made", "Mood", "Nature", "Net worth" };
    private static final int[] FOLK_COLS = { 0, 82, 160, 182, 210, 246, 274, 306, 336, 410 };

    /**
     * Folk: every one of them, sortable by any heading, its loose money (what is in its purse) and its
     * net worth (that, its share of what its household has put by toward a house, its share of a house
     * it owns, what it carries and the comforts of home) side by side, the mouse over a row for the
     * whole of it laid out; the town's money in sum along the top, and the players with a stake in the
     * village along the foot.
     */
    private void folk(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        List<CompoundTag> people = compounds("people");
        people.sort(folkOrder());
        List<CompoundTag> players = compounds("players");
        // The town's money, in sum.
        int loose = 0, worth = 0, fund = 0, owned = 0, grown = 0;
        CompoundTag richest = null;
        for (CompoundTag p : people) {
            if (p.getString("wealth").isEmpty()) continue;                 // a child: its family keeps it
            grown++;
            loose += p.getInt("purse");
            worth += p.getInt("worth");
            fund += p.getInt("house_fund");
            owned += p.getInt("house_owned");
            if (richest == null || p.getInt("worth") > richest.getInt("worth")) richest = p;
        }
        String sums = "Loose money " + num(loose) + "c (" + (grown == 0 ? 0 : loose / grown) + " each) · net worth " + num(worth) + "c ("
            + (grown == 0 ? 0 : worth / grown) + " each)" + (fund > 0 ? " · put by toward houses " + num(fund) + "c" : "")
            + (owned > 0 ? " · in houses they own " + num(owned) + "c" : "")
            + (richest == null ? "" : " · richest " + richest.getString("name") + " (" + num(richest.getInt("worth")) + "c)");
        small(g, Ui.clip(font, sums, (int) (cw / 0.75)), x, y, Ui.MUTED);
        y += 11;
        ch -= 11;
        // The players with a stake in it, along the foot (a line each, up to four).
        int playerRows = Math.min(4, players.size());
        int foot = playerRows == 0 ? 0 : 12 + 10 * playerRows;
        ch -= foot;
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
                p.getInt("years") + "y", p.getString("wealth").isEmpty() ? "—" : p.getInt("purse") + "c", p.getInt("wage") + "c", p.getInt("made") + "c",
                Integer.toString(p.getInt("mood")), p.getString("type"),
                p.getString("wealth").isEmpty() ? "a child" : p.getInt("worth") + "c · " + p.getString("wealth") };
            boolean over = mx >= x && mx < x + cw && my >= ry - 1 && my < ry + 9;
            if (over && !p.getString("wealth").isEmpty()) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(p.getString("name") + ", " + p.getString("trade").toLowerCase(Locale.ROOT)));
                tip.add(Component.literal("Loose money: " + p.getInt("purse") + "c in its purse").withColor(0xE8C46A));
                if (p.getInt("house_fund") > 0) tip.add(Component.literal("Put by toward a house: " + p.getInt("house_fund") + "c"));
                if (p.getInt("house_owned") > 0) tip.add(Component.literal("Its share of the house it owns: " + p.getInt("house_owned") + "c"));
                tip.add(Component.literal("What it carries (tools, gear, keepsakes): " + p.getInt("goods") + "c"));
                if (p.getInt("comforts") > 0) tip.add(Component.literal("The comforts of home: " + p.getInt("comforts") + "c"));
                tip.add(Component.literal("Net worth: " + p.getInt("worth") + "c — " + p.getString("wealth")).withColor(0x9EE07A));
                tip.add(Component.literal("Paid " + p.getInt("wage") + "c a day; " + p.getInt("earned") + "c earned in all").withColor(0x9AA3B2));
                hover = tip;
                hoverX = mx;
                hoverY = my;
            }
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
        small(g, Ui.clip(font, people.size() + " folk (" + bs + "); " + purses + " coins loose in purses · the mouse over a row for its money laid out"
            + " · click a heading to sort · ★ the leader", (int) (cw / 0.75)), x, y + ch - 10, Ui.FAINT);
        if (playerRows > 0) {
            int py = y + ch + 2;
            small(g, "Players", x, py, Ui.FAINT);
            String[] heads = { "Loose money", "Houses owned", "Rent owed them", "Net worth" };
            int[] at = { cw * 30 / 100, cw * 48 / 100, cw * 66 / 100, cw * 82 / 100 };
            for (int i = 0; i < heads.length; i++) small(g, heads[i], x + at[i], py, Ui.FAINT);
            py += 10;
            for (int i = 0; i < playerRows; i++) {
                CompoundTag p = players.get(i);
                g.fill(x - 2, py - 1, x + cw, py + 9, i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
                small(g, Ui.clip(font, p.getString("name") + (p.getBoolean("citizen") ? " (citizen)" : ""), (int) (at[0] / 0.75) - 4), x + 2, py + 1, Ui.INK);
                small(g, p.getInt("loose") < 0 ? "away" : p.getInt("loose") + "c", x + at[0], py + 1, p.getInt("loose") < 0 ? Ui.FAINT : Ui.INK);
                small(g, p.getInt("houses") == 0 ? "none" : p.getInt("houses") + " (" + p.getInt("house_worth") + "c)", x + at[1], py + 1, Ui.INK);
                small(g, p.getInt("rent_due") + "c", x + at[2], py + 1, Ui.INK);
                small(g, p.getInt("worth") + "c" + (p.getInt("loose") < 0 ? " + purse" : ""), x + at[3], py + 1, GREEN);
                py += 10;
            }
        }
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
            case 9 -> Comparator.comparingInt(p -> p.getString("wealth").isEmpty() ? -1 : p.getInt("worth"));
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
        if (l.contains("pace")) rows.add("Sets the work " + (l.getInt("pace") >= 0 ? "+" : "") + l.getInt("pace") + "% and pay at " + l.getInt("pay") + "%");
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

    private static final String[] HOME_HEADS = { "Household", "House", "Terms", "Rent", "Put by toward the price", "Own one?" };

    /**
     * Homes: beds against folk and households housed over time, the tenures (rented, owned, saving to
     * buy, the leader's, players'), the rent coming in, and every household: where it lives, on what
     * terms, its rent, what it has put by toward the price, and whether it wants a house of its own.
     */
    private void homes(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
        CompoundTag hm = data.getCompound("homes");
        int third = (cw - 12) / 3, chartH = Math.max(50, ch * 34 / 100);
        chart(g, x, y, third, chartH, "Folk against beds", mx, my, new Series("Folk", series("pop"), BLUE),
            new Series("Room", series("room"), GREEN), new Series("With a bed", series("bedded"), AMBER));
        chart(g, x + third + 6, y, third, chartH, "Rented, owned, saving to buy", mx, my, new Series("Rented", series("rented"), TEAL),
            new Series("Owned", series("owned"), PURPLE), new Series("Saving", series("saving"), AMBER), new Series("Waiting", series("waiting"), RED),
            new Series("Rent-free", series("rent_free"), BLUE));
        bars(g, x + 2 * (third + 6), y, third, chartH, "Rent and houses sold, a day (coins)", mx, my,
            new Series("Rent", series("rent"), GREEN), new Series("Sold", series("house_sales"), BROWN));
        // The figures.
        int ty = y + chartH + 12;
        String[][] figures = {
            { "Housed", Integer.toString(hm.getInt("housed")) }, { "Waiting", Integer.toString(hm.getInt("waiting")) },
            { "Renting", Integer.toString(hm.getInt("rented")) }, { "Owned", Integer.toString(hm.getInt("owned")) },
            { "Saving to buy", Integer.toString(hm.getInt("saving")) }, { "Put by", hm.getInt("saved") + "c" },
            { "Rent yesterday", hm.getInt("rent_yesterday") + "c" }, { "Owed", hm.getInt("owed") + "c" },
            { "Players'", Integer.toString(hm.getInt("players")) }, { "Empty", Integer.toString(hm.getInt("empty")) },
            { "Founders rent-free", Integer.toString(hm.getInt("rent_free")) } };
        int colW = cw / 5;
        for (int i = 0; i < figures.length; i++) {
            int cx = x + (i % 5) * colW, cy = ty + (i / 5) * 10;
            small(g, figures[i][0] + ":", cx, cy, Ui.FAINT);
            small(g, figures[i][1], cx + (int) (font.width(figures[i][0] + ": ") * 0.75), cy, Ui.INK);
        }
        int by = ty + 10 * ((figures.length + 4) / 5) + 2;
        small(g, "Beds", x, by, Ui.FAINT);
        Ui.bar(g, x + 22, by, cw / 4, 6, hm.getInt("folk") == 0 ? 0 : hm.getInt("bedded") / (float) Math.max(1, hm.getInt("folk")), GREEN);
        small(g, Ui.clip(font, hm.getInt("bedded") + " of " + hm.getInt("folk") + " folk have a bed, " + hm.getInt("beds_made") + " made up, room for "
            + hm.getInt("room") + "; founders live rent-free till they can afford it, the rest rent, and buy when they have saved the price",
            (int) ((cw * 3 / 4 - 30) / 0.75)),
            x + 28 + cw / 4, by, Ui.MUTED);
        // Every household.
        int hy = by + 12;
        int[] cols = { 0, cw * 26 / 100, cw * 42 / 100, cw * 55 / 100, cw * 61 / 100, cw * 80 / 100 };
        for (int i = 0; i < HOME_HEADS.length; i++) small(g, HOME_HEADS[i], x + cols[i], hy, Ui.FAINT);
        hy += 10;
        List<CompoundTag> rows = new ArrayList<>();
        ListTag rl = hm.getList("rows", Tag.TAG_COMPOUND);
        for (int i = 0; i < rl.size(); i++) rows.add(rl.getCompound(i));
        rows.sort(Comparator.comparing((CompoundTag r) -> r.getString("status")).thenComparing(r -> -r.getInt("saved")));
        int rowsFit = Math.max(1, (y + ch - 10 - hy) / 10);
        int start = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - rowsFit)));
        for (int i = start; i < Math.min(rows.size(), start + rowsFit); i++) {
            CompoundTag r = rows.get(i);
            boolean over = mx >= x && mx < x + cw && my >= hy - 1 && my < hy + 9;
            g.fill(x - 2, hy - 1, x + cw, hy + 9, over ? Ui.HI : i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
            String status = r.getString("status");
            int sc = status.equals("owns") ? GREEN : status.equals("saving") ? AMBER : status.equals("the leader's") ? PURPLE
                : status.equals("rent-free") ? BLUE : TEAL;
            g.fill(x - 2, hy - 1, x, hy + 9, sc);
            String rent = r.getBoolean("rent_free") ? "free (" + r.getInt("rent_due") + "c later)"
                : r.getInt("rent") == 0 ? "—" : r.getInt("rent") + "c" + (r.getInt("owed") > 0 ? " (owes " + r.getInt("owed") + ")" : "");
            String[] cells = { r.getString("household"), r.getString("kind") + ", " + r.getString("address"), status, rent };
            for (int c = 0; c < cells.length; c++) {
                int w = cols[c + 1] - cols[c] - 3;
                small(g, Ui.clip(font, cells[c], (int) (w / 0.75)), x + cols[c] + (c == 0 ? 2 : 0), hy + 1, c == 2 ? sc : Ui.INK);
            }
            // Toward the price: a bar, and the figures.
            int px = x + cols[4], pw = cols[5] - cols[4] - 4;
            int price = r.getInt("price"), saved = r.getInt("saved");
            if (status.equals("owns")) {
                small(g, Ui.clip(font, "bought" + (price > 0 ? " for " + price + "c" : ""), (int) (pw / 0.75)), px, hy + 1, GREEN);
            } else if (price > 0) {
                Ui.bar(g, px, hy + 1, pw / 2, 6, Math.min(1f, saved / (float) price), saved > 0 ? AMBER : Ui.EDGE_SOFT);
                small(g, saved + " of " + price + "c", px + pw / 2 + 3, hy + 1, Ui.MUTED);
            } else {
                small(g, "—", px, hy + 1, Ui.FAINT);
            }
            small(g, Ui.clip(font, (r.getBoolean("wants") ? "yes: " : "no: ") + r.getString("why"), (int) ((cw - cols[5]) / 0.75)),
                x + cols[5], hy + 1, r.getBoolean("wants") ? Ui.INK : Ui.MUTED);
            if (over) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(r.getString("household")));
                tip.add(Component.literal(r.getString("kind") + ", " + r.getString("address") + " — " + r.getString("terms")));
                if (r.getInt("rent") > 0) tip.add(Component.literal("Rent " + r.getInt("rent") + "c a day" + (r.getString("rent_note").isEmpty() ? "" : "; " + r.getString("rent_note"))));
                else if (r.getBoolean("rent_free")) tip.add(Component.literal("Rent-free: " + r.getString("rent_note")));
                if (price > 0) tip.add(Component.literal("Put by " + saved + " of " + price + "c"));
                tip.add(Component.literal((r.getBoolean("wants") ? "Wants a house of its own: " : "Content to rent: ") + r.getString("why")));
                hover = tip;
                hoverX = mx;
                hoverY = my;
            }
            hy += 10;
        }
        if (rows.isEmpty()) small(g, "No household has a house yet.", x, hy, Ui.MUTED);
        small(g, Ui.clip(font, rows.size() + " households · the mouse over a row for the whole of it" + (rows.size() > rowsFit ? " · scroll for more" : ""),
            (int) (cw / 0.75)), x, y + ch - 9, Ui.FAINT);
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
        // Each part out of what it can come to (food and mood a quarter each, beds a fifth, and so on).
        String[] parts = { "Food", "Homes", "Mood", "Safety", "Amenities", "Wages", "Rest day" };
        int[] vals = now.getIntArray("content_parts"), most = now.getIntArray("content_max");
        for (int i = 0; i < parts.length && i < vals.length; i++) {
            int max = i < most.length ? Math.max(1, most[i]) : 100;
            float frac = vals[i] / (float) max;
            g.drawString(font, parts[i], rx, ry, Ui.INK, false);
            Ui.bar(g, rx + 56, ry, rw - 56 - 34, 8, frac, frac >= 0.6f ? GREEN : frac >= 0.35f ? AMBER : RED);
            Ui.right(g, font, vals[i] + "/" + max, rx + rw, ry, Ui.MUTED);
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
        boolean savings = so.getInt("median") > 0 || so.getInt("top_tenth") > 0;
        small(g, savings ? "Spread " + word + " (Gini " + gini + " of 100)" : "No savings yet: every purse is empty", cx, cy, Ui.INK);
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
            plural(so.getInt("friendships"), "friendship") + ", " + plural(so.getInt("rivalries"), "rivalry", "rivalries") };
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
            Ui.bar(g, cx + 72, cy, col - 72 - 22, 7, levels[i] / (float) grown, PALETTE[(i + 2) % PALETTE.length]);
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
            if (at < 0 || best <= 0) continue;                                // no record of nothing
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
        int[] wagesAll = series("wages"), inAll = plus(series("takings"), series("sold"), series("tithe"), series("rent"), series("house_sales"));
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
        Ui.section(g, font, "In 30 days, at this pace", rx, ry, half);
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

    private static final String[] LEAGUE_HEADS = { "Village", "Folk", "Age", "Worth", "Buildings", "Where", "Terms" };
    private static final int[] LEAGUE_COLS = { 0, 110, 140, 200, 240, 286, 370 };

    private void news(GuiGraphics g, int x, int y, int cw, int ch) {
        // The villages of the world, biggest first: how this one measures up.
        List<CompoundTag> league = compounds("league");
        if (league.size() > 1) {
            Ui.section(g, font, "The villages of the world, biggest first", x, y, cw);
            y += 12;
            for (int i = 0; i < LEAGUE_HEADS.length; i++) if (LEAGUE_COLS[i] < cw - 20) small(g, LEAGUE_HEADS[i], x + LEAGUE_COLS[i], y, Ui.FAINT);
            y += 9;
            int shown = 0;
            for (int i = 0; i < league.size() && shown < 6; i++, shown++) {
                CompoundTag v = league.get(i);
                g.fill(x - 2, y - 1, x + cw, y + 8, v.getBoolean("self") ? Ui.ROW_PICK : i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
                String[] cells = { (i + 1) + ". " + v.getString("name"), Integer.toString(v.getInt("folk")), v.getString("age"),
                    shortNum(v.getInt("worth")) + "c", Integer.toString(v.getInt("buildings")),
                    v.getBoolean("self") ? "here" : v.getInt("dist") + " " + v.getString("dir"), v.getString("terms") };
                for (int c = 0; c < cells.length; c++) {
                    if (LEAGUE_COLS[c] >= cw - 20) break;
                    int colW = (c + 1 < LEAGUE_COLS.length ? LEAGUE_COLS[c + 1] : cw) - LEAGUE_COLS[c] - 3;
                    small(g, Ui.clip(font, cells[c], (int) (colW / 0.75)), x + LEAGUE_COLS[c], y, Ui.INK);
                }
                y += 9;
            }
            y += 6;
            ch -= 27 + shown * 9 + 6;
        }
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
        // The key, wrapping to a second row rather than leaving a line unnamed.
        int kx = px + 3, ky = py + 1;
        for (Series s : all) {
            int kw = (int) (font.width(s.name()) * 0.75) + 10;
            if (kx + kw > px + pw && kx > px + 3) {
                kx = px + 3;
                ky += 8;
                if (ky > py + ph - 8) break;
            }
            g.fill(kx, ky + 2, kx + 6, ky + 4, s.colour());
            small(g, s.name(), kx + 8, ky, Ui.MUTED);
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

    /** Bars stacked a day at a time: what each part was, and the whole, with the day under the mouse read out. */
    private void stacked(GuiGraphics g, int x, int y, int cw, int ch, String title, int mx, int my, List<Series> all) {
        int[] days = days();
        int n = span();
        small(g, Ui.clip(font, title, (int) ((cw - 4) / 0.75)), x, y, Ui.FAINT);
        int px = x + 24, py = y + 9, pw = cw - 26, ph = ch - 20;
        g.fill(px, py, px + pw, py + ph, 0xFFD4D4D4);
        g.renderOutline(px - 1, py - 1, pw + 2, ph + 2, Ui.EDGE_SOFT);
        if (n <= 0 || pw < 10 || ph < 10 || all.isEmpty()) return;
        int[] totals = new int[n];
        for (Series s : all) {
            int[] v = s.values();
            for (int i = 0; i < n; i++) {
                int idx = v.length - n + i;
                if (idx >= 0) totals[i] += Math.max(0, v[idx]);
            }
        }
        int max = 1;
        for (int t : totals) max = Math.max(max, t);
        max = niceUp(max);
        for (int k = 0; k <= 2; k++) {
            int gy = py + ph - (int) ((ph - 1) * k / 2.0);
            g.fill(px, gy, px + pw, gy + 1, k == 0 ? Ui.EDGE_SOFT : 0xFFC0C0C0);
            String lbl = shortNum(max * k / 2);
            small(g, lbl, px - 2 - (int) (font.width(lbl) * 0.75), gy - 3, Ui.FAINT);
        }
        double slot = pw / (double) n;
        int bw = Math.max(1, (int) slot - (slot > 4 ? 1 : 0));
        for (int i = 0; i < n; i++) {
            int sx = px + (int) (i * slot);
            int base = 0;
            for (Series s : all) {
                int[] v = s.values();
                int idx = v.length - n + i;
                if (idx < 0 || v[idx] <= 0) continue;
                int y0 = py + ph - (int) Math.round(base * (ph - 1) / (double) max);
                base += v[idx];
                int y1 = py + ph - (int) Math.round(base * (ph - 1) / (double) max);
                if (y0 > y1) g.fill(sx, y1, sx + bw, y0, s.colour());
            }
        }
        small(g, "day " + days[days.length - n], px, py + ph + 2, Ui.FAINT);
        String lastDay = "day " + days[days.length - 1];
        small(g, lastDay, px + pw - (int) (font.width(lastDay) * 0.75), py + ph + 2, Ui.FAINT);
        int kx = px + 3, ky = py + 1;
        for (Series s : all) {
            int kw = (int) (font.width(s.name()) * 0.75) + 12;
            if (kx + kw > px + pw) { kx = px + 3; ky += 8; }
            if (ky > py + ph - 8) break;
            g.fill(kx, ky + 2, kx + 6, ky + 4, s.colour());
            small(g, s.name(), kx + 8, ky, Ui.MUTED);
            kx += kw + 2;
        }
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            int i = Math.max(0, Math.min(n - 1, (int) ((mx - px) / slot)));
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("Day " + days[days.length - n + i] + ": " + totals[i] + " in all"));
            for (Series s : all) {
                int idx = s.values().length - n + i;
                if (idx >= 0 && s.values()[idx] > 0) {
                    int pct = totals[i] == 0 ? 0 : Math.round(s.values()[idx] * 100f / totals[i]);
                    tip.add(Component.literal(s.name() + ": " + s.values()[idx] + " (" + pct + "%)").withColor(s.colour() & 0xFFFFFF));
                }
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

    private static String plural(int n, String one) {
        return plural(n, one, one + "s");
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the mouse

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (Zone z : new ArrayList<>(zones)) {
            if (mx >= z.x0() && mx < z.x1() && my >= z.y0() && my < z.y1()) { z.act().run(); return true; }
        }
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
                    else { sortColumn = i; sortDown = i != 0 && i != 1 && i != 8; }
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
