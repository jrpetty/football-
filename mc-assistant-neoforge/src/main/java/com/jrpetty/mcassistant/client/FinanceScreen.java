package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.UiConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * [purse20] Your finances, on one screen (the server writes it: entity/FinanceSheet). Tabs across the top:
 * <ul>
 * <li><b>Overview</b>: five cards (net worth with its trend over the week, the coin you carry, income and spending
 *     this week, and the week's profit or loss), net worth over sixty days, the bills due soon and where the week's
 *     money came from and went;</li>
 * <li><b>Balance sheet</b>: what you have and what you owe, in groups that fold, each line a click from its own page
 *     (the town's books at the bank, the bourse, the homes or the land; a business's books; your stall);</li>
 * <li><b>Income &amp; spending</b>: each kind of money, this week against last, in bars; what only moved between your
 *     own holdings kept apart;</li>
 * <li><b>Charts</b>: net worth, cash, income and spending over sixty days, the day under the mouse read out;</li>
 * <li><b>Businesses</b>, <b>Property</b>, <b>Investments</b>, <b>Bills</b> (with the day each is due), the
 *     <b>Ledger</b> (filtered by kind, town and category) and <b>Notices</b> (the toasts, the inventory button).</li>
 * </ul>
 * Laid out for a small window too (two columns where there is room, one where there is not). An operator may look up
 * another player's finances from the header.
 */
public class FinanceScreen extends Screen {

    private static final String[] TABS = {"Overview", "By town", "Balance sheet", "Income & spending", "Charts", "Businesses", "Property", "Investments", "Bills", "Ledger", "Notices"};
    private static final int OVERVIEW = 0, BY_TOWN = 1, BALANCE = 2, CATEGORIES = 3, CHARTS = 4, BUSINESSES = 5, PROPERTY = 6, INVESTMENTS = 7, BILLS = 8, LEDGER = 9, NOTICES = 10;
    private static final String[] GROUPS = {"Cash", "Savings", "Investments", "Property", "Businesses", "Owed to you", "Loans", "Bills owed"};
    private static final int HEAD = 24, TAB_H = 14, FOOT = 14;
    private static final int LAST_WEEK = 0xFF5A5A5A;

    @Nullable private final Screen parent;
    /** The page as the server sent it, and as it is shown (all of it, or one town's part: the town filter). */
    private CompoundTag full = new CompoundTag(), data = new CompoundTag();
    private String townFilter = "";
    private final java.util.Map<String, net.minecraft.world.item.ItemStack> banners = new java.util.HashMap<>();
    private int tab;
    private int left, top, w, h;
    private final Scroll scroll = new Scroll();
    private final Set<String> folded = new HashSet<>();
    private int ledgerKind;                     // 0 all, 1 income, 2 spending, 3 moves
    private String ledgerTown = "", ledgerCat = "";
    private String said = "";
    private long saidAt;
    @Nullable private EditBox lookup;

    /** Clickable places drawn this frame, and the tooltip under the mouse. */
    private record Zone(int x0, int y0, int x1, int y1, Runnable act, boolean body) {}
    private boolean drawingBody;

    private void zone(int x0, int y0, int x1, int y1, Runnable act) {
        zones.add(new Zone(x0, y0, x1, y1, act, drawingBody));
    }
    private final List<Zone> zones = new ArrayList<>();
    @Nullable private String tip;
    private int bodyTop, bodyBottom;

    public FinanceScreen(@Nullable Screen parent) {
        super(Component.literal("Your finances"));
        this.parent = parent;
    }

    public void receive(CompoundTag t) {
        boolean other = !t.getString("name").equals(full.getString("name"));
        full = t;
        if (other) { scroll.reset(); townFilter = ""; }
        banners.clear();
        data = view();
        if (minecraft != null) rebuildWidgets();                            // (not yet shown: init builds them)
    }

    public void said(String words) {
        said = words;
        saidAt = Util.getMillis();
    }

    private boolean self() {
        return !data.contains("self") || data.getBoolean("self");
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        w = Math.min(width - 2 * S2, 470);
        h = Math.min(height - 2 * S2, 320);
        left = (width - w) / 2;
        top = (height - h) / 2;
        lookup = null;
        if (data.getBoolean("op") && w >= 330) {
            int bw = 84;
            lookup = new EditBox(font, left + w - S2 - 40 - S1 - (font.width("Update") + 10) - S1 - 34 - S1 - bw, top + 6, bw, 13, Component.literal("Player"));
            lookup.setHint(Component.literal("a player's name"));
            lookup.setMaxLength(32);
            addRenderableWidget(lookup);
        }
    }

    /** The tabs, laid in rows: {x, y, w, index}. */
    private List<int[]> tabBoxes() {
        List<int[]> out = new ArrayList<>();
        int x = left + S2, y = top + HEAD + 2;
        for (int i = 0; i < TABS.length; i++) {
            int tw = font.width(TABS[i]) + 10;
            if (x + tw > left + w - S2) { x = left + S2; y += TAB_H + 1; }
            out.add(new int[]{ x, y, tw, i });
            x += tw + 2;
        }
        return out;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BACKDROP);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g, mx, my, partialTick);
        zones.clear();
        tip = null;
        shadow(g, left, top, w, h);
        panel(g, left, top, w, h);
        header(g, mx, my);
        List<int[]> tabs = tabBoxes();
        int tabsBottom = top + HEAD + 2;
        for (int[] b : tabs) {
            boolean on = b[3] == tab, over = in(mx, my, b[0], b[1], b[2], TAB_H);
            topTab(g, b[0], b[1], b[2], TAB_H, State.of(true, on, over));
            g.drawString(font, TABS[b[3]], b[0] + 5, b[1] + 3, on ? ACCENT : TEXT_MUTED, false);
            int idx = b[3];
            zone(b[0], b[1], b[0] + b[2], b[1] + TAB_H, () -> { tab = idx; scroll.reset(); });
            tabsBottom = Math.max(tabsBottom, b[1] + TAB_H);
        }
        g.fill(left + S1, tabsBottom, left + w - S1, tabsBottom + 1, EDGE_SOFT);
        bodyTop = tabsBottom + S1;
        bodyBottom = top + h - FOOT - 2;
        int cx = left + S2, cw = w - 2 * S2 - 5;
        well(g, left + S1, bodyTop - 1, w - 2 * S1, bodyBottom - bodyTop + 2, PAGE);
        if (!data.contains("net")) {
            empty(g, font, "Opening your books…", "A moment while the clerks add it up.", cx, bodyTop, cw, bodyBottom - bodyTop);
        } else {
            g.enableScissor(left + S1, bodyTop, left + w - S1, bodyBottom);
            drawingBody = true;
            int y0 = bodyTop + S1 - scroll.offset();
            if (tab != NOTICES && tab != BY_TOWN) y0 = townBar(g, cx, y0, cw, mx, my);
            int end = switch (tab) {
                case BY_TOWN -> byTown(g, cx, y0, cw, mx, my);
                case BALANCE -> balance(g, cx, y0, cw, mx, my);
                case CATEGORIES -> categories(g, cx, y0, cw, mx, my);
                case CHARTS -> charts(g, cx, y0, cw, mx, my);
                case BUSINESSES -> businesses(g, cx, y0, cw, mx, my);
                case PROPERTY -> property(g, cx, y0, cw, mx, my);
                case INVESTMENTS -> investments(g, cx, y0, cw, mx, my);
                case BILLS -> bills(g, cx, y0, cw, mx, my, 99);
                case LEDGER -> ledger(g, cx, y0, cw, mx, my);
                case NOTICES -> notices(g, cx, y0, cw, mx, my);
                default -> overview(g, cx, y0, cw, mx, my);
            };
            g.disableScissor();
            drawingBody = false;
            scroll.fit(end + scroll.offset() - bodyTop + S1, bodyBottom - bodyTop);
            scrollBar(g, left + w - S1 - 4, bodyTop, bodyBottom - bodyTop, scroll.offset(), scroll.max(), bodyBottom - bodyTop);
        }
        footer(g, mx, my);
        for (net.minecraft.client.gui.components.Renderable r : renderables) r.render(g, mx, my, partialTick);   // (not super: it would draw the backdrop again)
        if (tip != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            tooltip(g, font, tip, mx, my, 220);
            g.pose().popPose();
        }
    }

    private void header(GuiGraphics g, int mx, int my) {
        headerBand(g, left, top, w, HEAD);
        int rx = left + w - S2;
        int cw = font.width("Close") + 10;
        rx -= cw;
        button(g, font, "Close", rx, top + 4, cw, 16, State.of(true, false, in(mx, my, rx, top + 4, cw, 16)));
        zone(rx, top + 4, rx + cw, top + 20, this::onClose);
        int uw = font.width("Update") + 10;
        rx -= S1 + uw;
        boolean overR = in(mx, my, rx, top + 4, uw, 16);
        button(g, font, "Update", rx, top + 4, uw, 16, State.of(true, false, overR));
        if (overR) tip = "Add it all up again";
        zone(rx, top + 4, rx + uw, top + 20, () -> { UiStyle.click(); askPage(data.getBoolean("self") ? "" : data.getString("name")); });
        if (lookup != null) {
            int bx = rx - S1 - 34;
            boolean over = in(mx, my, bx, top + 4, 34, 16);
            button(g, font, "Look", bx, top + 4, 34, 16, State.of(true, false, over));
            if (over) tip = "An operator's look at another player's finances (empty: your own)";
            zone(bx, top + 4, bx + 34, top + 20, () -> { UiStyle.click(); askPage(lookup.getValue().trim()); });
            rx = lookup.getX() - S1;
        }
        int x = left + S2;
        String title = self() ? "Your finances" : data.getString("name") + "'s finances";
        title(g, font, clip(font, title, rx - x - 4), x, top + 4);
        String sub = data.contains("day") ? "Day " + (data.getLong("day") + 1) + (self() ? "" : " · an operator's view, read only") : "";
        text(g, font, sub, x, top + 14, rx - x - 4, TEXT_MUTED);
    }

    private void footer(GuiGraphics g, int mx, int my) {
        int fy = top + h - FOOT + 2;
        boolean fresh = Util.getMillis() - saidAt < 6000;
        String msg;
        int ink = TEXT_FAINT;
        if (fresh && !said.isEmpty()) { msg = said; ink = DANGER; }
        else if (FinanceClient.OPEN.isUnbound()) msg = "Tip: bind a key to open this anywhere (Controls, \"Your finances\"; we suggest " + FinanceClient.SUGGESTED_KEY + "). Also the coin in your inventory (E).";
        else msg = "Open with " + FinanceClient.OPEN.getTranslatedKeyMessage().getString() + ", the coin in your inventory (E), the journal (J), the city books or the bank counter.";
        int keys = keyCap(g, font, "Esc", left + w - S2 - font.width("Esc") - 4, fy);
        boolean clipped = text(g, font, msg, left + S2, fy + 1, w - 2 * S2 - keys - S2, ink);
        if (clipped && in(mx, my, left + S2, fy, w - 2 * S2 - keys, 10)) tip = msg;
    }

    // ------------------------------------------------------------------ the pages

    private int overview(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        long net = data.getLong("net"), cash = data.getLong("cash"), in = data.getLong("inWeek"), out = data.getLong("outWeek");
        long inL = data.getLong("inLast"), outL = data.getLong("outLast");
        boolean hasAgo = data.contains("netWeekAgo");
        long ago = data.getLong("netWeekAgo");
        boolean oneTown = data.contains("town");
        if (oneTown) cash = data.getCompound("groups").getLong("Savings");
        String[] labels = {"Net worth", oneTown ? "Savings" : "Cash", "Income", "Spending", "Profit / loss"};
        String[] values = {money(net), money(cash), money(in), money(out), signed(in - out)};
        int[] inks = {net >= 0 ? TEXT : DANGER, TEXT, SUCCESS, out > 0 ? DANGER : TEXT, in - out > 0 ? SUCCESS : in - out < 0 ? DANGER : TEXT};
        String[] subs = {hasAgo ? signed(net - ago) + " on the week" : "first week", oneTown ? "put by here" : data.getBoolean("cashKnown") ? "in your pack" : "when last seen",
            "last week " + money(inL), "last week " + money(outL), "last week " + signed(inL - outL)};
        String[] tips = {oneTown ? "All you have in " + data.getString("town") + ", less all you owe it. The arrow: against a week ago." : "All you have, less all you owe. The arrow: against a week ago.",
            oneTown ? "What you have put by in " + data.getString("town") + ": its bank, savings club and treasury." : "The village coin you carry in your pack.",
            "Income this week (the last seven days): wages, shifts, sales, rent, dividends, interest, coupons, rewards.",
            "Spending this week (the last seven days): rent, taxes, fees, goods, food, loan payments, fines, gifts.",
            "Income less spending over the last seven days (savings and investments moved are neither)."};
        int perRow = Math.max(1, Math.min(5, (cw + S1) / (72 + S1)));
        int cardW = (cw - (perRow - 1) * S1) / perRow, cardH = 36;
        for (int i = 0; i < 5; i++) {
            int col = i % perRow, row = i / perRow;
            int bx = x + col * (cardW + S1), by = y + row * (cardH + S1);
            card(g, bx, by, cardW, cardH, i == 0 ? ACCENT_FILL : i == 2 ? SUCCESS_FILL : i == 3 ? DANGER_FILL : i == 4 ? INFO_FILL : WARNING_FILL);
            text(g, font, labels[i], bx + 6, by + 4, cardW - 10, TEXT_FAINT);
            String v = values[i];
            g.drawString(font, Component.literal(clip(font, v, cardW - 20)).withStyle(ChatFormatting.BOLD), bx + 6, by + 14, inks[i], false);
            if (i == 0 && hasAgo) trend(g, font, Long.compare(net, ago), true, bx + 8 + font.width(v) + 4, by + 14);
            text(g, font, subs[i], bx + 6, by + 25, cardW - 10, TEXT_MUTED);
            if (in(mx, my, bx, by, cardW, cardH)) tip = tips[i];
        }
        y += ((4 / perRow) + 1) * (cardH + S1) + S1;
        ListTag ledger = data.getList("ledger", Tag.TAG_COMPOUND);
        if (ledger.isEmpty() && data.getList("assetRows", Tag.TAG_COMPOUND).size() <= 1 && data.getList("liabilityRows", Tag.TAG_COMPOUND).isEmpty()) {
            card(g, x, y, cw, 34, INFO_FILL);
            text(g, font, "Nothing in your books yet.", x + 8, y + 5, cw - 12, TEXT);
            text(g, font, "Take a shift at a town's earnings board, sell at the market or a stall: every coin shows here.", x + 8, y + 17, cw - 12, TEXT_MUTED);
            y += 40;
        }
        heading(g, font, "Net worth, sixty days", x, y, cw);
        y += 11;
        y = chart(g, x, y, cw, 46, "sNet", true, ACCENT, mx, my) + S2;
        boolean two = cw >= 300;
        int colW = two ? (cw - S2) / 2 : cw;
        int y1 = y;
        heading(g, font, "Bills due soon", x, y1, colW);
        int yb = bills(g, x, y1 + 11, colW, mx, my, 4);
        int x2 = two ? x + colW + S2 : x, y2 = two ? y : yb + S2;
        heading(g, font, "This week's money", x2, y2, colW);
        y2 += 11;
        ListTag cats = data.getList("cats", Tag.TAG_COMPOUND);
        List<CompoundTag> ins = new ArrayList<>(), outs = new ArrayList<>();
        for (Tag t : cats) {
            CompoundTag c = (CompoundTag) t;
            if (c.getLong("w") == 0) continue;
            if (c.getString("kind").equals("INCOME")) ins.add(c);
            else if (c.getString("kind").equals("SPENDING")) outs.add(c);
        }
        ins.sort((a, b) -> Long.compare(b.getLong("w"), a.getLong("w")));
        outs.sort((a, b) -> Long.compare(a.getLong("w"), b.getLong("w")));
        if (ins.isEmpty() && outs.isEmpty()) {
            text(g, font, "Nothing came in or went out this week.", x2, y2, colW, TEXT_FAINT);
            y2 += 11;
        }
        for (int i = 0; i < Math.min(3, ins.size()); i++) y2 = moneyLine(g, x2, y2, colW, ins.get(i).getString("label"), ins.get(i).getLong("w"), SUCCESS);
        for (int i = 0; i < Math.min(3, outs.size()); i++) y2 = moneyLine(g, x2, y2, colW, outs.get(i).getString("label"), outs.get(i).getLong("w"), DANGER);
        return Math.max(yb, y2) + S1;
    }

    private int moneyLine(GuiGraphics g, int x, int y, int w, String label, long v, int ink) {
        String s = signed(v);
        text(g, font, label, x, y, w - font.width(s) - S2, TEXT_MUTED);
        g.drawString(font, s, x + w - font.width(s), y, ink, false);
        return y + 11;
    }

    private int balance(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        long a = data.getLong("assets"), l = data.getLong("liabilities");
        String sum = "Assets " + money(a) + "  −  liabilities " + money(l) + "  =  net worth " + money(a - l);
        g.drawString(font, Component.literal(clip(font, sum, cw)).withStyle(ChatFormatting.BOLD), x, y, a - l >= 0 ? TEXT : DANGER, false);
        y += 13;
        boolean two = cw >= 340;
        int colW = two ? (cw - S2) / 2 : cw;
        int ya = sheet(g, x, y, colW, "What you have", data.getList("assetRows", Tag.TAG_COMPOUND), false, mx, my);
        int x2 = two ? x + colW + S2 : x, y2 = two ? y : ya + S2;
        int yl = sheet(g, x2, y2, colW, "What you owe", data.getList("liabilityRows", Tag.TAG_COMPOUND), true, mx, my);
        return Math.max(ya, yl);
    }

    private int sheet(GuiGraphics g, int x, int y, int w, String title, ListTag rows, boolean owe, int mx, int my) {
        long total = 0;
        for (Tag t : rows) total += ((CompoundTag) t).getLong("v");
        heading(g, font, title + " · " + money(total), x, y, w);
        y += 12;
        if (rows.isEmpty()) {
            text(g, font, owe ? "You owe nothing to anybody." : "Nothing yet.", x + 4, y, w - 4, TEXT_FAINT);
            return y + 12;
        }
        for (String grp : GROUPS) {
            List<CompoundTag> in = new ArrayList<>();
            long sum = 0;
            for (Tag t : rows) if (((CompoundTag) t).getString("g").equals(grp)) { in.add((CompoundTag) t); sum += ((CompoundTag) t).getLong("v"); }
            if (in.isEmpty()) continue;
            boolean shut = folded.contains(grp);
            boolean over = in(mx, my, x, y, w, 12);
            g.fill(x, y, x + w, y + 12, over ? TILE_HOVER : TILE);
            g.drawString(font, (shut ? "▸ " : "▾ ") + grp + " (" + in.size() + ")", x + 3, y + 2, TEXT, false);
            String s = money(sum);
            g.drawString(font, s, x + w - font.width(s) - 3, y + 2, owe ? DANGER : TEXT, false);
            if (over) tip = (shut ? "Show" : "Fold") + " the " + grp.toLowerCase(Locale.ROOT);
            zone(x, y, x + w, y + 12, () -> { if (!folded.remove(grp)) folded.add(grp); });
            y += 13;
            if (shut) continue;
            for (CompoundTag r : in) {
                boolean link = !r.getString("k").isEmpty() && self();
                boolean o = in(mx, my, x, y, w, 11);
                if (o) g.fill(x, y, x + w, y + 11, 0x18000000);
                String v = money(r.getLong("v"));
                String town = r.getString("t");
                int room = w - font.width(v) - 14 - (link ? 6 : 0);
                String label = r.getString("l") + (town.isEmpty() ? "" : " · " + town);
                text(g, font, label, x + 8, y + 1, room, TEXT_MUTED);
                g.drawString(font, v, x + w - font.width(v) - 3 - (link ? 7 : 0), y + 1, owe ? DANGER : TEXT, false);
                if (link) g.drawString(font, "›", x + w - 6, y + 1, ACCENT, false);
                if (o) tip = r.getString("tip") + (link ? " Click to open its page." : "");
                if (link) {
                    String k = r.getString("k");
                    zone(x, y, x + w, y + 11, () -> follow(k));
                }
                y += 11;
            }
            y += 2;
        }
        return y;
    }

    private int categories(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        ListTag cats = data.getList("cats", Tag.TAG_COMPOUND);
        g.fill(x, y + 2, x + 6, y + 6, ACCENT_FILL);
        g.drawString(font, "this week", x + 9, y, TEXT_MUTED, false);
        int lx = x + 9 + font.width("this week") + S2;
        g.fill(lx, y + 2, lx + 6, y + 6, LAST_WEEK);
        g.drawString(font, "last week", lx + 9, y, TEXT_MUTED, false);
        text(g, font, "(the last seven days, and the seven before)", lx + 9 + font.width("last week") + S2, y, cw - (lx - x) - 60, TEXT_FAINT);
        y += 13;
        if (cats.isEmpty()) {
            empty(g, font, "No money booked yet", "Every coin you earn or spend in a town shows here by kind.", x, y, cw, 40);
            return y + 44;
        }
        String[][] kinds = {{"INCOME", "Income"}, {"SPENDING", "Spending"}, {"MOVE", "Moved between your holdings"}};
        for (String[] k : kinds) {
            List<CompoundTag> rows = new ArrayList<>();
            long tw = 0, tl = 0, hi = 1;
            for (Tag t : cats) {
                CompoundTag c = (CompoundTag) t;
                if (!c.getString("kind").equals(k[0])) continue;
                rows.add(c);
                tw += c.getLong("w");
                tl += c.getLong("lw");
                hi = Math.max(hi, Math.max(Math.abs(c.getLong("w")), Math.abs(c.getLong("lw"))));
            }
            if (rows.isEmpty()) continue;
            heading(g, font, k[1] + " · " + signed(tw) + " (last week " + signed(tl) + ")", x, y, cw);
            y += 12;
            rows.sort((a, b) -> Long.compare(Math.abs(b.getLong("w")) + Math.abs(b.getLong("lw")), Math.abs(a.getLong("w")) + Math.abs(a.getLong("lw"))));
            int labelW = Math.min(150, cw / 3), numW = Math.max(font.width("-1,234.56c"), 60);
            int barW = Math.max(30, cw - labelW - numW - 2 * S1);
            for (CompoundTag c : rows) {
                long vw = c.getLong("w"), vl = c.getLong("lw");
                int fill = k[0].equals("INCOME") ? SUCCESS_FILL : k[0].equals("SPENDING") ? DANGER_FILL : INFO_FILL;
                text(g, font, c.getString("label"), x, y + 2, labelW - 4, TEXT_MUTED);
                bar(g, x + labelW, y + 1, barW, 5, (float) Math.abs(vw) / hi, fill);
                int lwPx = (int) (barW * (Math.abs(vl) / (double) hi));
                if (lwPx > 0) g.fill(x + labelW, y + 7, x + labelW + lwPx, y + 10, LAST_WEEK);             // last week: a dark line, no track
                String s = signed(vw);
                g.drawString(font, s, x + cw - font.width(s), y + 2, vw > 0 ? SUCCESS : vw < 0 ? DANGER : TEXT_FAINT, false);
                if (in(mx, my, x, y, cw, 12)) tip = c.getString("label") + ": this week " + signed(vw) + ", last week " + signed(vl) + ", sixty days " + signed(c.getLong("all")) + ".";
                y += 13;
            }
            y += S1;
        }
        return y;
    }

    private int charts(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        boolean two = cw >= 300;
        int colW = two ? (cw - S2) / 2 : cw, ch = 54;
        boolean oneTown = data.contains("town");
        String[][] c = oneTown ? new String[][]{{"sNet", "Net worth here"}, {"sIn", "Income a day here"}, {"sOut", "Spending a day here"}}
            : new String[][]{{"sNet", "Net worth"}, {"sCash", "Cash you carry"}, {"sIn", "Income a day"}, {"sOut", "Spending a day"}};
        int[] colours = oneTown ? new int[]{ACCENT, SUCCESS_FILL, DANGER_FILL} : new int[]{ACCENT, WARNING_FILL, SUCCESS_FILL, DANGER_FILL};
        int yy = y;
        for (int i = 0; i < c.length; i++) {
            int col = two ? i % 2 : 0;
            int bx = x + col * (colW + S2), by = two ? y + (i / 2) * (ch + 26) : yy;
            long[] v = data.getLongArray(c[i][0]);
            long last = v.length == 0 ? 0 : v[v.length - 1];
            heading(g, font, c[i][1], bx, by, colW);
            String s = money(last);
            g.drawString(font, s, bx + colW - font.width(s), by + 10, TEXT, false);
            int end = chart(g, bx, by + 11 + 9, colW, ch, c[i][0], c[i][0].equals("sNet") || c[i][0].equals("sCash"), colours[i], mx, my);
            yy = end + S2;
        }
        int rows = (c.length + 1) / 2;
        text(g, font, "Net worth and cash are written each morning and whenever you open this; income and spending as they are booked.", x, (two ? y + rows * (ch + 26) : yy) + 2, cw, TEXT_FAINT);
        return (two ? y + rows * (ch + 26) : yy) + 14;
    }

    /** A chart of sixty days from the page's series (pennies), the day under the mouse read out. Returns the bottom. */
    private int chart(GuiGraphics g, int x, int y, int w, int ch, String key, boolean level, int colour, int mx, int my) {
        long[] raw = data.getLongArray(key);
        int from = level ? Math.min(raw.length, data.getInt("firstSeen")) : 0;
        int[] v = new int[Math.max(0, raw.length - from)];
        for (int i = 0; i < v.length; i++) v[i] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, raw[from + i] / 100));
        lineChart(g, font, x, y, w, ch, v, colour);
        if (v.length >= 2 && in(mx, my, x, y, w, ch)) {
            int i = Math.max(0, Math.min(v.length - 1, (int) Math.round((mx - x - 2) * (v.length - 1) / (double) Math.max(1, w - 5))));
            int px = x + 2 + (int) ((long) i * (w - 5) / (v.length - 1));
            g.fill(px, y + 1, px + 1, y + ch - 1, 0x60000000);
            long day = data.getLong("day") - (raw.length - 1 - (from + i));
            tip = "Day " + (day + 1) + ": " + money(raw[from + i]);
        }
        if (v.length >= 2) {
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int n : v) { lo = Math.min(lo, n); hi = Math.max(hi, n); }
            g.drawString(font, coins(hi), x + 3, y + 2, TEXT_FAINT, false);
            g.drawString(font, coins(lo), x + 3, y + ch - 10, TEXT_FAINT, false);
        }
        return y + ch;
    }

    private int businesses(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        ListTag list = data.getList("businesses", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            empty(g, font, "You own no business yet", "Charter a firm, buy one or into one, or rent a stall on market day.", x, y, cw, 40);
            return y + 44;
        }
        for (Tag t : list) {
            CompoundTag b = (CompoundTag) t;
            int bh = 44;
            card(g, x, y, cw, bh, b.getBoolean("open") ? ACCENT_FILL : EDGE_SOFT);
            String link = b.getString("k");
            int bw = font.width("Books ›") + 10;
            boolean canLink = self() && !link.isEmpty();
            if (canLink) {
                boolean over = in(mx, my, x + cw - bw - 4, y + 4, bw, 14);
                button(g, font, link.startsWith("stall:") ? "Stall ›" : "Books ›", x + cw - bw - 4, y + 4, bw, 14, State.of(true, false, over));
                if (over) tip = link.startsWith("stall:") ? "Your stall's screen" : "Its books: takings, costs, hands, stock, orders";
                zone(x + cw - bw - 4, y + 4, x + cw - 4, y + 18, () -> follow(link));
            }
            int share = b.getInt("share");
            String name = b.getString("name") + (share > 0 && share < 10000 ? " (" + share / 100 + "%)" : "");
            g.drawString(font, Component.literal(clip(font, name, cw - bw - 20)).withStyle(ChatFormatting.BOLD), x + 7, y + 4, TEXT, false);
            text(g, font, b.getString("type") + " · " + b.getString("town") + (b.getBoolean("open") ? "" : " · shut"), x + 7, y + 14, cw - bw - 20, TEXT_MUTED);
            long profit = b.getLong("profit"), lastP = b.getLong("lastProfit");
            String[] ls = {"Takings", "Costs", "Profit", "Hands", "Stock", "Worth"};
            String[] vs = {money(b.getLong("takings")), money(b.getLong("costs")), signed(profit), String.valueOf(b.getInt("hands")), money(b.getLong("stock")), money(b.getLong("value"))};
            int cols = 6, colW = (cw - 14) / cols;
            for (int i = 0; i < cols; i++) {
                int cx = x + 7 + i * colW;
                text(g, font, ls[i], cx, y + 24, colW - 2, TEXT_FAINT);
                int ink = i == 2 ? (profit > 0 ? SUCCESS : profit < 0 ? DANGER : TEXT) : TEXT;
                text(g, font, vs[i], cx, y + 33, colW - 10, ink);
                if (i == 2 && lastP != Long.MIN_VALUE) trend(g, font, Long.compare(profit, lastP), true, cx + Math.min(colW - 10, font.width(vs[i])) + 2, y + 33);
            }
            if (in(mx, my, x, y + 20, cw, bh - 20)) tip = "The last seven days: takings " + money(b.getLong("takings")) + ", costs " + money(b.getLong("costs"))
                + (b.contains("wages") ? " (wages to its hands " + money(b.getLong("wages")) + ")" : "") + ", profit " + signed(profit)
                + (lastP != Long.MIN_VALUE ? " (the week before " + signed(lastP) + ")" : "") + ". Till " + money(b.getLong("till"))
                + (b.getLong("loan") > 0 ? ", loan " + money(b.getLong("loan")) : "") + ". Worth to you at book value: " + money(b.getLong("value")) + ".";
            y += bh + S1;
        }
        return y;
    }

    private int property(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        ListTag list = data.getList("properties", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            empty(g, font, "You own no property yet", "Buy a house at the bank or from the town, or a land title on the town's capital page.", x, y, cw, 40);
            return y + 44;
        }
        String[] heads = {"Property", "Town", "Value", "Rent in / wk", "Mortgage"};
        int[] ws = colWidths(cw, new int[]{ 34, 18, 16, 16, 16 });
        y = tableHead(g, x, y, ws, heads);
        long tv = 0, tr = 0, tm = 0;
        for (Tag t : list) {
            CompoundTag p = (CompoundTag) t;
            tv += p.getLong("value"); tr += p.getLong("rentIn"); tm += p.getLong("mortgage");
            String[] cells = {p.getString("name") + " (" + p.getString("kind").toLowerCase(Locale.ROOT) + ")", p.getString("town"), money(p.getLong("value")),
                p.getLong("rentIn") > 0 ? money(p.getLong("rentIn")) : "-", p.getLong("mortgage") > 0 ? money(p.getLong("mortgage")) : "-"};
            int[] inks = {TEXT, TEXT_MUTED, TEXT, p.getLong("rentIn") > 0 ? SUCCESS : TEXT_FAINT, p.getLong("mortgage") > 0 ? DANGER : TEXT_FAINT};
            y = tableRow(g, x, y, cw, ws, cells, inks, mx, my, p.getString("k"), cells[0] + ": worth " + cells[2] + ".");
        }
        g.fill(x, y, x + cw, y + 1, EDGE_SOFT);
        y = tableRow(g, x, y + 2, cw, ws, new String[]{"All told", "", money(tv), money(tr), tm > 0 ? money(tm) : "-"}, new int[]{TEXT, TEXT, TEXT, SUCCESS, DANGER}, mx, my, "", null);
        return y;
    }

    private int investments(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        ListTag shares = data.getList("shares", Tag.TAG_COMPOUND), bonds = data.getList("bonds", Tag.TAG_COMPOUND);
        heading(g, font, "Shares", x, y, cw);
        y += 12;
        if (shares.isEmpty()) {
            text(g, font, "No shares. Firms list theirs on a town's bourse (the capital page).", x + 4, y, cw - 4, TEXT_FAINT);
            y += 13;
        } else {
            int[] ws = colWidths(cw, new int[]{ 30, 18, 10, 14, 14, 14 });
            y = tableHead(g, x, y, ws, new String[]{"Firm", "Town", "Shares", "Price", "Value", "Gain / loss"});
            for (Tag t : shares) {
                CompoundTag s = (CompoundTag) t;
                long gain = s.getLong("cost") > 0 ? s.getLong("value") - s.getLong("cost") : 0;
                String pct = s.getLong("cost") > 0 ? String.format(Locale.ROOT, " (%+.0f%%)", gain * 100.0 / s.getLong("cost")) : "";
                String[] cells = {s.getString("name"), s.getString("town"), String.valueOf(s.getInt("n")), money(s.getLong("price")), money(s.getLong("value")),
                    s.getLong("cost") > 0 ? signed(gain) + pct : "-"};
                int[] inks = {TEXT, TEXT_MUTED, TEXT, TEXT, TEXT, gain > 0 ? SUCCESS : gain < 0 ? DANGER : TEXT_FAINT};
                y = tableRow(g, x, y, cw, ws, cells, inks, mx, my, s.getString("k"), s.getInt("n") + " shares of " + s.getString("name") + " at the last price "
                    + money(s.getLong("price")) + (s.getLong("cost") > 0 ? "; they cost you " + money(s.getLong("cost")) : "") + ".");
            }
        }
        y += S2;
        heading(g, font, "Bonds", x, y, cw);
        y += 12;
        if (bonds.isEmpty()) {
            text(g, font, "No bonds. A town with a market sells them at its hall (the capital page).", x + 4, y, cw - 4, TEXT_FAINT);
            return y + 13;
        }
        int[] ws = colWidths(cw, new int[]{ 30, 24, 26, 20 });
        y = tableHead(g, x, y, ws, new String[]{"Town", "Face", "Waiting at the hall", "Rate / wk"});
        for (Tag t : bonds) {
            CompoundTag b = (CompoundTag) t;
            String[] cells = {b.getString("town"), money(b.getLong("face")), b.getLong("due") > 0 ? money(b.getLong("due")) : "-",
                String.format(Locale.ROOT, "%.1f%%", b.getInt("bp") / 100.0)};
            y = tableRow(g, x, y, cw, ws, cells, new int[]{TEXT, TEXT, b.getLong("due") > 0 ? SUCCESS : TEXT_FAINT, TEXT_MUTED}, mx, my, b.getString("k"),
                "Coupons, title rents and share money wait at the hall till you collect them.");
        }
        return y;
    }

    /** The bills: overdue first, then by the day due. {@code most}: how many (the overview shows a few). */
    private int bills(GuiGraphics g, int x, int y, int cw, int mx, int my, int most) {
        ListTag bills = data.getList("bills", Tag.TAG_COMPOUND);
        long today = data.getLong("day");
        if (bills.isEmpty()) {
            text(g, font, "No bills due. You owe nothing.", x + 4, y, cw - 4, TEXT_FAINT);
            return y + 12;
        }
        int n = 0;
        for (Tag t : bills) {
            if (n++ >= most) {
                text(g, font, "and " + (bills.size() - most) + " more on the Bills tab", x + 4, y, cw - 4, TEXT_FAINT);
                int bt = BILLS;
                zone(x, y, x + cw, y + 10, () -> { tab = bt; scroll.reset(); });
                return y + 12;
            }
            CompoundTag b = (CompoundTag) t;
            long d = b.getLong("day");
            boolean od = b.getBoolean("overdue");
            String when = od ? "overdue" : d < 0 ? "any time" : d <= today ? "today" : d == today + 1 ? "tomorrow" : "in " + (d - today) + " days";
            int badgeFill = od ? DANGER_FILL : d >= 0 && d - today <= 2 ? WARNING_FILL : INFO_FILL;
            String amount = b.getLong("amount") > 0 ? money(b.getLong("amount")) : "";
            boolean over = in(mx, my, x, y, cw, 12);
            if (over) g.fill(x, y, x + cw, y + 12, 0x18000000);
            int bw = badge(g, font, when, x + 2, y, badgeFill);
            int aw = font.width(amount);
            text(g, font, b.getString("what") + " · " + b.getString("town"), x + bw + 6, y + 2, cw - bw - aw - 12, TEXT);
            g.drawString(font, amount, x + cw - aw - 2, y + 2, od ? DANGER : TEXT, false);
            if (over) tip = b.getString("what") + " in " + b.getString("town") + (amount.isEmpty() ? "" : ": " + amount) + (d >= 0 && !od ? ", due day " + (d + 1) : "") + ". " + b.getString("tip");
            y += 13;
        }
        return y;
    }

    private int ledger(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        ListTag ledger = data.getList("ledger", Tag.TAG_COMPOUND);
        String[] kinds = {"All", "Income", "Spending", "Moves"};
        int cx = x;
        for (int i = 0; i < kinds.length; i++) {
            boolean on = ledgerKind == i;
            int cwid = chip(g, font, kinds[i], cx, y, on ? ACCENT_FILL : EDGE_SOFT);
            int k = i;
            zone(cx, y, cx + cwid, y + 12, () -> { ledgerKind = k; scroll.reset(); });
            cx += cwid + 3;
        }
        Set<String> towns = new LinkedHashSet<>(), cats = new LinkedHashSet<>();
        for (Tag t : ledger) {
            CompoundTag e = (CompoundTag) t;
            if (!e.getString("v").isEmpty()) towns.add(e.getString("v"));
            cats.add(e.getString("c"));
        }
        String townLabel = "Town: " + (ledgerTown.isEmpty() ? "all" : ledgerTown);
        if (cx + font.width(townLabel) + 8 > x + cw) { cx = x; y += 14; }
        int tw = chip(g, font, townLabel, cx, y, ledgerTown.isEmpty() ? EDGE_SOFT : INFO_FILL);
        List<String> tl = new ArrayList<>(towns);
        zone(cx, y, cx + tw, y + 12, () -> { ledgerTown = cycle(tl, ledgerTown); scroll.reset(); });
        if (in(mx, my, cx, y, tw, 12)) tip = "Click for the next town";
        cx += tw + 3;
        String catLabel = "Kind: " + (ledgerCat.isEmpty() ? "all" : catName(ledgerCat, ledger));
        if (cx + font.width(catLabel) + 8 > x + cw) { cx = x; y += 14; }
        int kw = chip(g, font, catLabel, cx, y, ledgerCat.isEmpty() ? EDGE_SOFT : INFO_FILL);
        List<String> cl = new ArrayList<>(cats);
        zone(cx, y, cx + kw, y + 12, () -> { ledgerCat = cycle(cl, ledgerCat); scroll.reset(); });
        if (in(mx, my, cx, y, kw, 12)) tip = "Click for the next kind of money";
        y += 16;
        List<CompoundTag> shown = new ArrayList<>();
        for (int i = ledger.size() - 1; i >= 0; i--) {
            CompoundTag e = ledger.getCompound(i);
            String kind = kindOf(e.getString("c"));
            if (ledgerKind == 1 && !kind.equals("INCOME") || ledgerKind == 2 && !kind.equals("SPENDING") || ledgerKind == 3 && !kind.equals("MOVE")) continue;
            if (!ledgerTown.isEmpty() && !ledgerTown.equals(e.getString("v"))) continue;
            if (!ledgerCat.isEmpty() && !ledgerCat.equals(e.getString("c"))) continue;
            shown.add(e);
        }
        long sum = 0;
        for (CompoundTag e : shown) sum += e.getLong("a");
        text(g, font, shown.size() + (shown.size() == 1 ? " entry" : " entries") + " shown, " + signed(sum) + " all told (the last " + ledger.size() + " kept; 200 at most)", x, y, cw, TEXT_FAINT);
        y += 12;
        if (shown.isEmpty()) {
            empty(g, font, ledger.isEmpty() ? "Your ledger is empty" : "Nothing matches", ledger.isEmpty() ? "Each coin you earn or spend in a town is written here." : "Try another filter.", x, y, cw, 36);
            return y + 40;
        }
        int dayW = font.width("Day 0000") + 4, amtW = font.width("-1,234.56c") + 4, townW = Math.min(90, cw / 5);
        for (CompoundTag e : shown) {
            if (y > bodyBottom + 20) { y += 11; continue; }                   // past the bottom: counted, not drawn
            boolean over = in(mx, my, x, y, cw, 11);
            if (over) g.fill(x, y, x + cw, y + 11, 0x18000000);
            long a = e.getLong("a");
            g.drawString(font, "Day " + (e.getLong("d") + 1), x + 2, y + 1, TEXT_FAINT, false);
            String w0 = e.getString("w");
            String what = (w0.isEmpty() ? w0 : Character.toUpperCase(w0.charAt(0)) + w0.substring(1)) + (e.getBoolean("i") ? " (account)" : "");
            text(g, font, what, x + dayW, y + 1, cw - dayW - townW - amtW - 4, TEXT);
            text(g, font, e.getString("v"), x + cw - amtW - townW, y + 1, townW - 4, TEXT_MUTED);
            String s = signed(a);
            g.drawString(font, s, x + cw - font.width(s) - 2, y + 1, a > 0 ? SUCCESS : DANGER, false);
            if (over) {
                long t = e.getLong("t");
                int hh = (int) ((t / 1000 + 6) % 24), mm = (int) (t % 1000 * 60 / 1000);
                tip = "Day " + (e.getLong("d") + 1) + String.format(Locale.ROOT, ", %02d:%02d", hh, mm) + ": " + e.getString("w") + " — " + catName(e.getString("c"), ledger)
                    + (e.getString("v").isEmpty() ? "" : ", " + (a > 0 ? "from " : "to ") + e.getString("v")) + ": " + signed(a)
                    + (e.getBoolean("i") ? ". Moved in your accounts, not through your pack." : ".");
            }
            y += 11;
        }
        return y;
    }

    private int notices(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        heading(g, font, "Notices", x, y, cw);
        y += 13;
        String[] labels = {"The week's summary (a toast at the start of each week)", "Bills due within two days or overdue (a toast, once a day)",
            "The coin button in your inventory (E), beside the recipe book"};
        boolean[] on = {UiConfig.financeWeekly(), UiConfig.financeBills(), UiConfig.financeButton()};
        for (int i = 0; i < 3; i++) {
            boolean over = in(mx, my, x, y, cw, 14);
            tile(g, x, y, 12, 12, State.of(true, on[i], over));
            if (on[i]) g.drawString(font, "✔", x + 2, y + 2, ACCENT, false);
            text(g, font, labels[i], x + 18, y + 2, cw - 20, TEXT);
            int k = i;
            zone(x, y, x + cw, y + 13, () -> { UiStyle.click(); UiConfig.setFinance(k, !on[k]); });
            y += 16;
        }
        y += S2;
        heading(g, font, "Ways in", x, y, cw);
        y += 13;
        String[] ways = {"The coin in your inventory (E), beside the recipe book; beside the creative inventory.",
            "A key: Controls › MC Assistant › \"Your finances\" (unbound at first; we suggest " + FinanceClient.SUGGESTED_KEY + ").",
            "The village journal (J): the Finances tab.", "The city books: the coin in the header.", "A bank's counter: the coin in its header.",
            "Your life in town: the Finances button."};
        for (String s : ways) {
            text(g, font, "· " + s, x + 4, y, cw - 4, TEXT_MUTED);
            y += 11;
        }
        y += S2;
        text(g, font, "Only you see your finances. An operator can look anybody's up from the header.", x, y, cw, TEXT_FAINT);
        return y + 12;
    }

    // ------------------------------------------------------------------ [purse20] by town

    /** The page as shown: all of it, or only what is in (and with) the town the filter names. */
    private CompoundTag view() {
        if (townFilter.isEmpty()) return full;
        CompoundTag c = townCard(townFilter);
        if (c == null) { townFilter = ""; return full; }
        CompoundTag v = full.copy();
        v.putString("town", c.getString("name"));
        v.putString("vid", townFilter);
        for (String k : new String[]{"net", "assets", "liabilities", "inWeek", "outWeek", "inLast", "outLast", "firstSeen"}) v.put(k, c.get(k) == null ? net.minecraft.nbt.LongTag.valueOf(0) : c.get(k).copy());
        v.put("cats", c.getList("cats", Tag.TAG_COMPOUND).copy());
        v.put("groups", c.getCompound("groups").copy());
        v.putLongArray("sNet", c.getLongArray("sNet"));
        v.putLongArray("sIn", c.getLongArray("sIn"));
        v.putLongArray("sOut", c.getLongArray("sOut"));
        v.remove("sCash");
        v.remove("netWeekAgo");
        long[] sn = c.getLongArray("sNet");
        if (sn.length >= 8 && c.getInt("firstSeen") <= sn.length - 8) v.putLong("netWeekAgo", sn[sn.length - 8]);
        for (String k : new String[]{"assetRows", "liabilityRows", "businesses", "properties", "shares", "bonds", "bills", "ledger"}) {
            ListTag in = full.getList(k, Tag.TAG_COMPOUND), out = new ListTag();
            for (Tag t : in) if (townFilter.equals(((CompoundTag) t).getString("vid"))) out.add(t.copy());
            v.put(k, out);
        }
        return v;
    }

    @Nullable
    private CompoundTag townCard(String vid) {
        for (Tag t : full.getList("towns", Tag.TAG_COMPOUND)) if (((CompoundTag) t).getString("vid").equals(vid)) return (CompoundTag) t;
        return null;
    }

    private void filter(String vid) {
        townFilter = vid;
        data = view();
        scroll.reset();
    }

    /** The town filter, at the top of a page: every town as a chip, "All towns" first. Returns the bottom. */
    private int townBar(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        ListTag towns = full.getList("towns", Tag.TAG_COMPOUND);
        if (towns.isEmpty()) return y;
        int cx = x;
        g.drawString(font, "Town:", cx, y + 2, TEXT_FAINT, false);
        cx += font.width("Town:") + 4;
        List<String[]> chips = new ArrayList<>();
        chips.add(new String[]{"", "All towns"});
        for (Tag t : towns) chips.add(new String[]{((CompoundTag) t).getString("vid"), ((CompoundTag) t).getString("name")});
        for (String[] c : chips) {
            int cwid = font.width(c[1]) + 8;
            if (cx + cwid > x + cw) { cx = x + font.width("Town:") + 4; y += 14; }
            boolean on = townFilter.equals(c[0]);
            int tint = c[0].isEmpty() ? EDGE_SOFT : 0xFF000000 | townColour(c[0]);
            chip(g, font, c[1], cx, y, on ? ACCENT_FILL : tint);
            if (in(mx, my, cx, y, cwid, 12)) tip = c[0].isEmpty() ? "Everything, in every town" : "Only what you have, owe and deal in " + c[1];
            String vid = c[0];
            zone(cx, y, cx + cwid, y + 12, () -> { UiStyle.click(); filter(vid); });
            cx += cwid + 3;
        }
        return y + 16;
    }

    private int townColour(String vid) {
        CompoundTag c = townCard(vid);
        return c == null ? 0x808080 : c.getInt("colour");
    }

    private net.minecraft.world.item.ItemStack banner(CompoundTag c) {
        return banners.computeIfAbsent(c.getString("vid"), k -> CulturePage.banner(c.getCompound("banner")));
    }

    /** By town: a card for each town (click one for its detail), or the one town's detail. */
    private int byTown(GuiGraphics g, int x, int y, int cw, int mx, int my) {
        ListTag towns = full.getList("towns", Tag.TAG_COMPOUND);
        if (towns.isEmpty()) {
            empty(g, font, "Nothing in any town yet", "Save, invest, rent a stall or buy a house in a town and it shows here.", x, y, cw, 40);
            return y + 44;
        }
        if (!townFilter.isEmpty() && townCard(townFilter) != null) return townDetail(g, x, y, cw, mx, my, townCard(townFilter));
        long sum = 0;
        for (Tag t : towns) sum += ((CompoundTag) t).getLong("net");
        text(g, font, towns.size() + (towns.size() == 1 ? " town" : " towns") + " · " + money(sum) + " in them, " + money(full.getLong("unplacedWorth"))
            + " in no town (the coin you carry) = net worth " + money(full.getLong("net")), x, y, cw, TEXT_MUTED);
        y += 12;
        boolean two = cw >= 300;
        int colW = two ? (cw - S1) / 2 : cw, ch = 74;
        int i = 0, rowTop = y;
        for (Tag t : towns) {
            CompoundTag c = (CompoundTag) t;
            int col = two ? i % 2 : 0;
            if (col == 0 && i > 0) rowTop += ch + S1;
            int bx = x + col * (colW + S1), by = rowTop;
            boolean over = in(mx, my, bx, by, colW, ch);
            card(g, bx, by, colW, ch, 0xFF000000 | UiStyle.accentFill(c.getInt("colour")));
            if (over) g.fill(bx + 3, by + 1, bx + colW - 1, by + ch - 1, 0x14000000);
            net.minecraft.world.item.ItemStack flag = banner(c);
            int tx = bx + 7;
            if (!flag.isEmpty()) { g.renderItem(flag, tx, by + 4); tx += 18; }
            String nw = money(c.getLong("net"));
            g.drawString(font, Component.literal(clip(font, c.getString("name"), colW - (tx - bx) - font.width(nw) - 14)).withStyle(ChatFormatting.BOLD), tx, by + 4, TEXT, false);
            g.drawString(font, Component.literal(nw).withStyle(ChatFormatting.BOLD), bx + colW - font.width(nw) - 6, by + 4, c.getLong("net") >= 0 ? TEXT : DANGER, false);
            List<String> roles = new ArrayList<>();
            for (Tag r : c.getList("roles", Tag.TAG_STRING)) roles.add(r.getAsString());
            text(g, font, roles.isEmpty() ? "You deal here" : String.join(" · ", roles), tx, by + 14, colW - (tx - bx) - 6, TEXT_MUTED);
            String week = "This week " + signed(c.getLong("inWeek")) + " in, " + signed(-c.getLong("outWeek")) + " out";
            text(g, font, week, bx + 7, by + 26, colW - 12, TEXT);
            List<net.minecraft.util.FormattedCharSequence> bl = font.split(Component.literal(breakdown(c)), colW - 12);   // two lines, the rest in the tooltip
            for (int k = 0; k < Math.min(2, bl.size()); k++) g.drawString(font, bl.get(k), bx + 7, by + 37 + k * 10, TEXT_MUTED, false);
            text(g, font, ownership(c), bx + 7, by + 59, colW - 12, TEXT_FAINT);
            if (over) tip = c.getString("name") + ": " + breakdown(c) + ". " + ownership(c) + ". Click for everything you have there.";
            String vid = c.getString("vid");
            zone(bx, by, bx + colW, by + ch, () -> { UiStyle.click(); filter(vid); });
            i++;
        }
        return rowTop + ch + S1;
    }

    /** "Bank 80c · Bonds 20c · Businesses 70c · Debts 10c" from a town card's groups. */
    private static String breakdown(CompoundTag c) {
        CompoundTag gr = c.getCompound("groups");
        String[][] names = {{"Savings", "Bank"}, {"Investments", "Shares & bonds"}, {"Property", "Property"}, {"Businesses", "Businesses"},
            {"Owed to you", "Owed to you"}, {"Loans", "Loans"}, {"Bills owed", "Bills"}};
        List<String> out = new ArrayList<>();
        for (String[] n : names) if (gr.getLong(n[0]) != 0) out.add(n[1] + " " + money(Math.abs(gr.getLong(n[0]))));
        return out.isEmpty() ? "Nothing held here" : String.join(" · ", out);
    }

    /** "Land: 3 of 166 titles · Mill 25%, Smithy 5%". */
    private static String ownership(CompoundTag c) {
        List<String> out = new ArrayList<>();
        if (c.getInt("titlesAll") > 0) out.add("Land: " + c.getInt("titlesMine") + " of " + c.getInt("titlesAll") + " titles");
        for (Tag t : c.getList("firms", Tag.TAG_COMPOUND)) {
            CompoundTag f = (CompoundTag) t;
            int parts = f.getInt("parts");
            out.add(f.getString("name") + " " + (parts >= 10000 ? "100%" : parts % 100 == 0 ? parts / 100 + "%" : String.format(Locale.ROOT, "%.1f%%", parts / 100.0))
                + (parts < 10000 ? " (" + parts / 100 + " of 100 shares)" : ""));
        }
        return out.isEmpty() ? "No share of its land or firms" : String.join(" · ", out);
    }

    /** One town: its card's head, every holding there (each a click from its page), the bills due there, its charts and
     *  its slice of the ledger. */
    private int townDetail(GuiGraphics g, int x, int y, int cw, int mx, int my, CompoundTag c) {
        int bw = chip(g, font, "\u2039 All towns", x, y, EDGE_SOFT);
        zone(x, y, x + bw, y + 12, () -> { UiStyle.click(); filter(""); });
        if (in(mx, my, x, y, bw, 12)) tip = "Back to every town";
        y += 16;
        net.minecraft.world.item.ItemStack flag = banner(c);
        int tx = x;
        if (!flag.isEmpty()) { g.renderItem(flag, x, y); tx += 20; }
        title(g, font, clip(font, c.getString("name"), cw - 120), tx, y);
        String nw = "net worth here " + money(c.getLong("net"));
        g.drawString(font, nw, x + cw - font.width(nw), y, c.getLong("net") >= 0 ? TEXT : DANGER, false);
        List<String> roles = new ArrayList<>();
        for (Tag r : c.getList("roles", Tag.TAG_STRING)) roles.add(r.getAsString());
        text(g, font, (roles.isEmpty() ? "You deal here" : String.join(" · ", roles)) + " · this week " + signed(c.getLong("inWeek")) + " in, "
            + signed(-c.getLong("outWeek")) + " out", tx, y + 10, cw - (tx - x), TEXT_MUTED);
        y += 22;
        text(g, font, ownership(c), x, y, cw, TEXT_FAINT);
        y += 13;
        y = balance(g, x, y, cw, mx, my) + S1;
        heading(g, font, "Bills due here", x, y, cw);
        y = bills(g, x, y + 11, cw, mx, my, 6) + S1;
        heading(g, font, "Net worth here, income and spending, sixty days", x, y, cw);
        y += 11;
        boolean three = cw >= 330;
        int colW = three ? (cw - 2 * S1) / 3 : cw;
        String[] keys = {"sNet", "sIn", "sOut"};
        int[] colours = {ACCENT, SUCCESS_FILL, DANGER_FILL};
        int yy = y;
        for (int i = 0; i < 3; i++) {
            int bx = three ? x + i * (colW + S1) : x, by = three ? y : yy;
            yy = chart(g, bx, by, colW, 40, keys[i], i == 0, colours[i], mx, my) + S1;
        }
        y = (three ? y + 40 + S1 : yy) + S1;
        heading(g, font, "Your ledger here (newest first)", x, y, cw);
        y += 12;
        ListTag led = data.getList("ledger", Tag.TAG_COMPOUND);
        if (led.isEmpty()) { text(g, font, "Nothing booked with " + c.getString("name") + " lately.", x + 4, y, cw - 4, TEXT_FAINT); return y + 12; }
        int shown = 0;
        for (int i = led.size() - 1; i >= 0 && shown < 10; i--, shown++) {
            CompoundTag e = led.getCompound(i);
            long a = e.getLong("a");
            g.drawString(font, "Day " + (e.getLong("d") + 1), x + 2, y + 1, TEXT_FAINT, false);
            String w0 = e.getString("w");
            text(g, font, w0.isEmpty() ? w0 : Character.toUpperCase(w0.charAt(0)) + w0.substring(1), x + font.width("Day 0000") + 4, y + 1, cw - 130, TEXT);
            String sv = signed(a);
            g.drawString(font, sv, x + cw - font.width(sv) - 2, y + 1, a > 0 ? SUCCESS : DANGER, false);
            y += 11;
        }
        if (led.size() > 10) {
            text(g, font, "All " + led.size() + " on the Ledger tab \u203A", x + 4, y, cw - 4, ACCENT);
            zone(x, y, x + cw, y + 10, () -> { tab = LEDGER; scroll.reset(); });
            y += 12;
        }
        return y;
    }

    // ------------------------------------------------------------------ tables

    private int[] colWidths(int cw, int[] parts) {
        int[] out = new int[parts.length];
        int sum = 0;
        for (int p : parts) sum += p;
        for (int i = 0; i < parts.length; i++) out[i] = cw * parts[i] / sum;
        return out;
    }

    private int tableHead(GuiGraphics g, int x, int y, int[] ws, String[] heads) {
        int cx = x;
        for (int i = 0; i < ws.length; i++) {
            text(g, font, heads[i], cx + 2, y, ws[i] - 4, TEXT_FAINT);
            cx += ws[i];
        }
        g.fill(x, y + 10, cx, y + 11, EDGE_SOFT);
        return y + 13;
    }

    private int tableRow(GuiGraphics g, int x, int y, int cw, int[] ws, String[] cells, int[] inks, int mx, int my, String link, @Nullable String rowTip) {
        boolean over = in(mx, my, x, y, cw, 11);
        boolean canLink = !link.isEmpty() && self();
        if (over && rowTip != null) g.fill(x, y, x + cw, y + 11, 0x18000000);
        int cx = x;
        for (int i = 0; i < ws.length; i++) {
            text(g, font, cells[i], cx + 2, y + 1, ws[i] - 4 - (canLink && i == ws.length - 1 ? 6 : 0), inks[i]);
            cx += ws[i];
        }
        if (canLink) {
            g.drawString(font, "›", x + cw - 5, y + 1, ACCENT, false);
            zone(x, y, x + cw, y + 11, () -> follow(link));
        }
        if (over && rowTip != null) tip = rowTip + (canLink ? " Click to open its page." : "");
        return y + 11;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (lookup != null && lookup.mouseClicked(mx, my, button)) { setFocused(lookup); return true; }
        if (button == 0) {
            for (Zone z : new ArrayList<>(zones)) {
                if (z.body() && (my < bodyTop || my >= bodyBottom)) continue;      // scrolled out of the body's view
                if (mx >= z.x0() && mx < z.x1() && my >= z.y0() && my < z.y1()) { z.act().run(); return true; }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll.wheel(dy, 22);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (lookup != null && lookup.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { askPage(lookup.getValue().trim()); return true; }
            if (key != GLFW.GLFW_KEY_ESCAPE) return lookup.keyPressed(key, scan, mods) || true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            tab = Math.floorMod(tab + ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1), TABS.length);
            scroll.reset();
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) { scroll.wheel(-1, bodyBottom - bodyTop - 20); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_UP) { scroll.wheel(1, bodyBottom - bodyTop - 20); return true; }
        if (key == GLFW.GLFW_KEY_DOWN) { scroll.wheel(-1, 11); return true; }
        if (key == GLFW.GLFW_KEY_UP) { scroll.wheel(1, 11); return true; }
        if (FinanceClient.OPEN.matches(key, scan) && (lookup == null || !lookup.isFocused())) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void follow(String link) {
        if (!self()) return;
        UiStyle.click();
        CompoundTag args = new CompoundTag();
        args.putString("k", link);
        FinanceClient.ask("link", args);
    }

    private void askPage(String who) {
        CompoundTag args = new CompoundTag();
        if (!who.isEmpty()) args.putString("who", who);
        FinanceClient.ask("page", args);
    }

    // ------------------------------------------------------------------ words

    private static String cycle(List<String> list, String now) {
        if (list.isEmpty()) return "";
        int i = list.indexOf(now);
        return i < 0 ? list.get(0) : i + 1 >= list.size() ? "" : list.get(i + 1);
    }

    private String catName(String cat, ListTag ledger) {
        for (Tag t : data.getList("cats", Tag.TAG_COMPOUND)) if (((CompoundTag) t).getString("c").equals(cat)) return ((CompoundTag) t).getString("label");
        return cat.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** A category's kind, by its name (the same as entity/PlayerFinance.Cat). */
    private String kindOf(String cat) {
        for (Tag t : data.getList("cats", Tag.TAG_COMPOUND)) if (((CompoundTag) t).getString("c").equals(cat)) return ((CompoundTag) t).getString("kind");
        return switch (cat) {
            case "SAVED", "DRAWN", "BORROWED", "INVESTED", "CASHED", "HELD", "RELEASED", "TILL" -> "MOVE";   // [join21] TILL
            case "RENT_OUT", "TAXES", "FEES", "HANDS", "PURCHASES", "FOOD", "LOAN_PAY", "FINES", "GIFTS", "LOSSES", "OTHER_OUT" -> "SPENDING";
            default -> "INCOME";
        };
    }

    /** Pennies as coin: "12.34c" when there are odd pennies (under a thousand coins), else "1,204c". */
    static String money(long p) {
        long r = Math.abs(p % 100);
        if (r != 0 && Math.abs(p) < 100000) return String.format(Locale.ROOT, "%s%d.%02dc", p < 0 ? "-" : "", Math.abs(p) / 100, r);
        return String.format(Locale.ROOT, "%,dc", p / 100);
    }

    static String signed(long p) {
        return p > 0 ? "+" + money(p) : money(p);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
