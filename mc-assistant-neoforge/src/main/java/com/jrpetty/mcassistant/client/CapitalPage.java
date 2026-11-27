package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [capital] The Capital page of the town's books (CityScreen; the server's CapitalReport): four parts, one under the
 * other, scrolled as one. <b>Savings and loans:</b> the savings club (or the bank), its deposits, its vault and what it may
 * lend, its loans and their rate against what its savers are paid, what it has written off, each firm's loan, and its last
 * decisions (who asked, what it lent and why). <b>The bourse:</b> each firm's last price against yesterday's and what a
 * share is worth by its books, the day's trades and the bids and asks standing, its dividends and new shares; the mouse
 * over a firm gives its holders. <b>Land:</b> the titles, what they are worth and earn, the land index and the rents that
 * follow it (held in a rent-wage spiral), the plots for sale. <b>The town's debt:</b> its bonds, their rate and room, the
 * week's coupons, sales and repayments, the IOUs to its market against what it can carry, and who holds its bonds.
 */
public final class CapitalPage {

    private CapitalPage() {}

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag t, int x, int y, int w, int h, int scroll, int mx, int my) {
        Ui.section(g, font, "Capital: savings, shares, land and the town's debt", x, y, w);
        int wide = (int) ((w - 4) / 0.75F);
        if (!t.getBoolean("live")) {
            TaxesPage.small(g, font, Ui.clip(font, t.getString("state"), wide), x + 2, y + 12, UiStyle.INFO);
            return null;
        }
        // Every line of the page, then drawn from the scroll on.
        List<Row> rows = new ArrayList<>();
        CompoundTag c = t.getCompound("credit");
        rows.add(Row.head("Savings and loans: " + c.getString("lender")));
        rows.add(Row.text("Deposits " + p(c.getLong("deposits")) + (c.getInt("savers") >= 0 ? " from " + c.getInt("savers") + " savers" : "") + "; vault "
            + p(c.getLong("vault")) + ", may lend " + p(c.getLong("lendable")) + "; firms owe " + p(c.getLong("loans")) + ".", Ui.MUTED));
        rows.add(Row.text("Lends at " + bp(c.getInt("bp")) + " a week, pays its savers " + bp(c.getInt("depositBp")) + "; written off "
            + p(c.getLong("defaulted")) + " in four weeks (" + p(c.getLong("writtenOffAll")) + " in all).", Ui.MUTED));
        for (Tag e : c.getList("loansList", Tag.TAG_COMPOUND)) {
            CompoundTag l = (CompoundTag) e;
            rows.add(Row.text("  " + l.getString("firm") + " owes " + p(l.getLong("owed")) + " at " + bp(l.getInt("bp"))
                + (l.getInt("behind") > 0 ? ", " + l.getInt("behind") + (l.getInt("behind") == 1 ? " week" : " weeks") + " behind" : ""), l.getInt("behind") > 0 ? Ui.BAD : Ui.INK));
        }
        ListTag dec = c.getList("decisions", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, dec.size() - 5); i < dec.size(); i++) {
            CompoundTag d = dec.getCompound(i);
            rows.add(Row.text("  Day " + (d.getLong("day") + 1) + ": " + d.getString("firm") + " asked " + p(d.getLong("asked")) + " for " + d.getString("what")
                + (d.getLong("lent") > 0 ? ", lent " + p(d.getLong("lent")) : ", refused") + ": " + d.getString("why"), d.getLong("lent") > 0 ? Ui.GOOD : Ui.FAINT));
        }
        CompoundTag b = t.getCompound("bourse");
        rows.add(Row.head("The bourse: " + p(b.getLong("cap")) + " of shares at their last prices"));
        ListTag firms = b.getList("rows", Tag.TAG_COMPOUND);
        if (firms.isEmpty()) rows.add(Row.text("No firm of the folk's own to trade yet.", Ui.FAINT));
        for (Tag e : firms) {
            CompoundTag r = (CompoundTag) e;
            long last = r.getLong("last"), prev = r.getLong("prev");
            String move = last > prev ? " up " + p(last - prev) : last < prev ? " down " + p(prev - last) : "";
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(r.getString("name") + ": its holders"));
            for (Tag hh : r.getList("holders", Tag.TAG_COMPOUND)) {
                CompoundTag ho = (CompoundTag) hh;
                tip.add(Component.literal(String.format(Locale.ROOT, "  %s: %.1f shares", ho.getString("name"), ho.getDouble("shares"))));
            }
            tip.add(Component.literal("Dividends this week " + p(r.getLong("divWeek")) + ", in all " + p(r.getLong("divAll")) + "; new shares issued " + r.getInt("issued")));
            rows.add(Row.tip("  " + r.getString("name") + ": " + p(last) + move + " a share (worth " + p(r.getLong("fair")) + "); traded " + r.getInt("vol")
                + " today, " + r.getInt("volAll") + " in all; bids " + r.getInt("bids") + ", asks " + r.getInt("asks"), last >= prev ? Ui.INK : Ui.BAD, tip));
        }
        ListTag trades = b.getList("trades", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, trades.size() - 4); i < trades.size(); i++) {
            CompoundTag tr = trades.getCompound(i);
            rows.add(Row.text("  Day " + (tr.getLong("day") + 1) + ": " + tr.getString("buyer") + " bought " + tr.getInt("n") + " of " + tr.getString("firm") + " from "
                + tr.getString("seller") + " at " + p(tr.getLong("each")), Ui.FAINT));
        }
        for (Tag e : b.getList("wiped", Tag.TAG_COMPOUND)) {
            CompoundTag wi = (CompoundTag) e;
            rows.add(Row.text("  Day " + (wi.getLong("day") + 1) + ": " + wi.getString("firm") + " bankrupt, its shares wiped (" + wi.getString("holders") + ")", Ui.BAD));
        }
        CompoundTag l = t.getCompound("land");
        rows.add(Row.head(String.format(Locale.ROOT, "Land: %d titles worth %s; index %.2f, rents at %d%%%s", l.getInt("plots"), p(l.getLong("sum")),
            l.getDouble("index"), Math.round(l.getDouble("rentFactor") * 100), l.getBoolean("spiral") ? " (held: a rent-wage spiral)" : "")));
        int shown = 0;
        for (Tag e : l.getList("rows", Tag.TAG_COMPOUND)) {
            CompoundTag r = (CompoundTag) e;
            if (shown++ >= 12) break;
            rows.add(Row.text("  " + r.getInt("n") + ". " + r.getString("name") + " (" + r.getString("kind") + "), " + r.getString("holderName") + ": worth "
                + p(r.getLong("value")) + ", earns " + p(r.getLong("earns")) + " a week" + (r.getBoolean("idle") ? ", idle" : "") + (r.getBoolean("leased") ? ", on a lease" : "")
                + (r.getBoolean("forSale") ? ", for sale" : ""), r.getBoolean("forSale") ? Ui.GOOD : r.getBoolean("idle") ? Ui.BAD : Ui.INK));
        }
        CompoundTag d = t.getCompound("bonds");
        rows.add(Row.head("The town's debt: bonds " + p(d.getLong("out")) + " at " + bp(d.getInt("bp")) + " a week; owed to its own market on IOUs " + p(d.getLong("ious"))));
        rows.add(Row.text("Room for " + p(d.getLong("room")) + " more (at most " + p(d.getLong("ceiling")) + ": two weeks' revenue, a fifth of it in coupons); coupons "
            + p(d.getLong("coupons")) + " a week" + (d.getLong("arrears") > 0 ? ", " + p(d.getLong("arrears")) + " behind" : "") + ".", Ui.MUTED));
        CompoundTag tw = d.getCompound("thisWeek"), lw = d.getCompound("lastWeek");
        rows.add(Row.text("This week sold " + p(tw.getLong("sold")) + " (IOUs paid off " + p(tw.getLong("funded")) + "), coupons " + p(tw.getLong("coupons")) + ", repaid "
            + p(tw.getLong("repaid")) + ", rolled over " + p(tw.getLong("rolled")) + "; last week sold " + p(lw.getLong("sold")) + ".", Ui.MUTED));
        long load = d.contains("load") ? d.getLong("load") : d.getLong("ious");     // [econ51] the IOUs and the bonds past a week's revenue
        rows.add(Row.tip("IOUs (the treasury's notes to its market for goods taken with no coin) " + p(d.getLong("ious"))
            + (load > d.getLong("ious") ? ", and " + p(load - d.getLong("ious")) + " of bonds past a week's revenue" : "")
            + "; it can carry " + p(d.getLong("limit")) + " (half a week's revenue of " + p(d.getLong("week")) + ").",
            load > d.getLong("limit") ? Ui.BAD : Ui.GOOD, MarketPage.iouTip()));
        if (!d.getString("why").isEmpty()) rows.add(Row.text(d.getString("why"), Ui.FAINT));
        List<String> hs = new ArrayList<>();
        for (Tag e : d.getList("holders", Tag.TAG_COMPOUND)) hs.add(((CompoundTag) e).getString("name") + " " + p(((CompoundTag) e).getLong("p")));
        if (!hs.isEmpty()) rows.add(Row.text("Held by: " + String.join(", ", hs), Ui.FAINT));
        // Drawn from the scroll on.
        int cy = y + 12, bottom = y + h - 6;
        List<Component> tip = null;
        for (int i = Math.max(0, Math.min(scroll, rows.size() - 1)); i < rows.size() && cy < bottom; i++) {
            Row r = rows.get(i);
            if (r.head) {
                cy += 2;
                TaxesPage.small(g, font, Ui.clip(font, r.text, wide), x + 2, cy, UiStyle.INFO);
                cy += 10;
                continue;
            }
            if (r.tip != null && mx >= x && mx < x + w && my >= cy - 1 && my < cy + 8) {
                g.fill(x, cy - 1, x + w, cy + 7, UiStyle.TILE_HOVER);
                tip = r.tip;
            }
            TaxesPage.small(g, font, Ui.clip(font, r.text, wide), x + 2, cy, r.colour);
            cy += 8;
        }
        return tip;
    }

    /** A line of the page: a part's heading, or a line of text (with what the mouse over it shows). */
    private record Row(String text, int colour, boolean head, @Nullable List<Component> tip) {
        static Row head(String s) {
            return new Row(s, UiStyle.INFO, true, null);
        }

        static Row text(String s, int colour) {
            return new Row(s, colour, false, null);
        }

        static Row tip(String s, int colour, List<Component> tip) {
            return new Row(s, colour, false, tip);
        }
    }

    private static String p(long pence) {
        return String.format(Locale.ROOT, "%,.2fc", pence / 100.0);
    }

    private static String bp(int bp) {
        return String.format(Locale.ROOT, "%.1f%%", bp / 100.0);
    }
}
