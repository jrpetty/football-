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
 * [econ-trade] The Trade page of the town's books (CityScreen), as the server's TradeDeals.report has it: the
 * town's trade book (each ware: what it holds against what it keeps, over or short, what it makes and what goes
 * out a day, its days of cover, how the land leans to it, its price here and its worth to the town); the
 * specialising over the days (its farmers and miners, and how far the deals lean their shares); its deals
 * (the partner, the terms, the deliveries made against those due, the shortfalls, what it has gained by its own
 * prices, what either owes); the talks, round by round, as the chronicle tells them; the deals past; and the
 * caravans' log. Scrolls with the wheel; the mouse over a ware or a deal gives the whole of it.
 */
public final class TradePage {

    private TradePage() {}

    private static final int ROW = 10, CHART = 5;

    private record Line(char kind, @Nullable CompoundTag tag, String text) {}

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line('S', null, m.getString("summary") + " — " + m.getString("land") + "."));
        lines.add(new Line('H', null, "The trade book"));
        lines.add(new Line('h', null, ""));
        for (CompoundTag c : compounds(m, "wares")) lines.add(new Line('W', c, ""));
        lines.add(new Line('H', null, "Specialising"));
        int[] specDays = m.getIntArray("spec_days");
        CompoundTag lean = m.getCompound("lean");
        List<String> leans = new ArrayList<>();
        for (String k : lean.getAllKeys()) if (lean.getInt(k) != 100) leans.add(k + "s ×" + String.format(Locale.ROOT, "%.2f", lean.getInt(k) / 100.0));
        lines.add(new Line('M', null, leans.isEmpty() ? "No deal leans the trades' shares: every trade at its usual share."
            : "The deals lean the shares: " + String.join(", ", leans) + " (never under ×0.65 or over ×1.40; never fewer farmers on short commons)."));
        if (specDays.length >= 2) {
            lines.add(new Line('C', m, ""));
            for (int i = 1; i < CHART; i++) lines.add(new Line(' ', null, ""));
        }
        lines.add(new Line('H', null, "Deals"));
        List<CompoundTag> deals = compounds(m, "deals");
        if (deals.isEmpty()) lines.add(new Line('M', null, "None standing. An envoy and the other town's leader strike them before the board."));
        for (CompoundTag c : deals) lines.add(new Line('D', c, ""));
        lines.add(new Line('H', null, "The talks"));
        List<String> talks = strings(m, "talks");
        if (talks.isEmpty()) lines.add(new Line('M', null, "No talks yet."));
        for (String s : talks) lines.add(new Line('T', null, s));
        List<String> past = strings(m, "past");
        if (!past.isEmpty()) {
            lines.add(new Line('H', null, "Deals past"));
            for (String s : past) lines.add(new Line('N', null, s));
        }
        List<String> log = strings(m, "log");
        if (!log.isEmpty()) {
            lines.add(new Line('H', null, "The caravans"));
            for (String s : log) lines.add(new Line('N', null, s));
        }

        int rows = Math.max(1, (h - 4) / ROW);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int cy = y;
        List<Component> tip = null;
        int[] cols = { 0, 58, 88, 118, 178, 214, 250, 286, 360, 400 };
        for (int i = start; i < lines.size() && cy + ROW <= y + h; i++) {
            Line l = lines.get(i);
            boolean over = mx >= x && mx < x + w && my >= cy && my < cy + ROW;
            switch (l.kind()) {
                case 'H' -> Ui.section(g, font, l.text(), x, cy + 1, w);
                case 'S' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x, cy + 2, Ui.INK);
                case 'M' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.FAINT);
                case 'N' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.MUTED);
                case 'T' -> {
                    small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.INK);
                    if (over) tip = wrap(font, l.text());
                }
                case 'h' -> {
                    String[] heads = { "Ware", "Held", "Keeps", "Over / short", "Made/d", "Out/d", "Cover", "Land", "Here", "To us" };
                    for (int k = 0; k < heads.length; k++) if (cols[k] < w - 20) small(g, font, heads[k], x + cols[k], cy + 2, Ui.FAINT);
                }
                case 'W' -> {
                    CompoundTag c = l.tag();
                    String status = c.getString("status");
                    g.fill(x - 2, cy, x + w, cy + ROW - 1, over ? Ui.HI : status.equals("SURPLUS") ? Ui.ROW_PICK : Ui.ROW);
                    float days = c.getFloat("days");
                    String[] cells = { capital(c.getString("ware")), Integer.toString(c.getInt("held")), Integer.toString(c.getInt("keep")),
                        status.equals("SURPLUS") ? c.getInt("spare") + " to spare" : status.equals("SHORT") ? "short " + c.getInt("want") : "enough",
                        String.format(Locale.ROOT, "%.0f", c.getFloat("made")), String.format(Locale.ROOT, "%.0f", c.getFloat("used")),
                        days < 0 ? "—" : days >= 99 ? "99+ d" : String.format(Locale.ROOT, "%.1f d", days),
                        c.getString("landWords").replace("the land ", "").replace(" ground for it", "").replace(" for it", ""),
                        price(c.getFloat("price")), price(c.getFloat("worth")) };
                    for (int k = 0; k < cells.length; k++) {
                        if (cols[k] >= w - 20) break;
                        int colW = (k + 1 < cols.length ? cols[k + 1] : w) - cols[k] - 3;
                        int col = k == 3 ? (status.equals("SURPLUS") ? Ui.GOOD : status.equals("SHORT") ? Ui.BAD : Ui.MUTED) : Ui.INK;
                        small(g, font, Ui.clip(font, cells[k], (int) (colW / 0.75F)), x + cols[k], cy + 2, col);
                    }
                    if (over) {
                        tip = new ArrayList<>();
                        tip.add(Component.literal(capital(c.getString("ware")) + " (" + c.getString("item") + ")"));
                        tip.add(Component.literal(c.getInt("held") + " held; the town keeps " + c.getInt("keep") + " for itself"));
                        tip.add(Component.literal(status.equals("SURPLUS") ? c.getInt("spare") + " it can afford to send (its own needs first)"
                            : status.equals("SHORT") ? "short " + c.getInt("want") + " (under its keep, or what its age asks for)" : "enough, nothing over"));
                        tip.add(Component.literal(String.format(Locale.ROOT, "Makes %.1f a day; %.1f go out a day (eaten, built, sold, sent)",
                            c.getFloat("made"), c.getFloat("used")) + (days >= 0 ? String.format(Locale.ROOT, "; %.1f days' cover", days) : "")));
                        tip.add(Component.literal("The land: " + c.getString("landWords") + String.format(Locale.ROOT, " (×%.2f)", c.getFloat("land"))));
                        tip.add(Component.literal("One costs " + price(c.getFloat("price")) + " here today, and is worth " + price(c.getFloat("worth"))
                            + " to the town: dearer when short, cheaper in a glut. Deals are reckoned by this."));
                    }
                }
                case 'D' -> {
                    CompoundTag c = l.tag();
                    g.fill(x - 2, cy, x + w, cy + ROW - 1, over ? Ui.HI : Ui.ROW_ALT);
                    String made = c.getInt("made") + " of " + c.getInt("due") + " made";
                    int gained = c.getInt("gained");
                    small(g, font, Ui.clip(font, c.getString("partner"), 70), x, cy + 2, Ui.INK);
                    small(g, font, Ui.clip(font, c.getString("terms"), (int) ((w - 190) / 0.75F)), x + 58, cy + 2, Ui.MUTED);
                    small(g, font, made, x + w - 128, cy + 2, c.getInt("shortThem") + c.getInt("shortUs") > 0 ? Ui.WARN : Ui.INK);
                    small(g, font, (gained >= 0 ? "+" : "") + gained + "c to us", x + w - 60, cy + 2, gained >= 0 ? Ui.GOOD : Ui.BAD);
                    if (over) {
                        tip = new ArrayList<>();
                        tip.add(Component.literal("With " + c.getString("partner") + ": " + c.getString("terms")));
                        tip.add(Component.literal(made + "; short " + c.getInt("shortUs") + " ourselves, " + c.getInt("shortThem") + " by them"));
                        tip.add(Component.literal((gained >= 0 ? "+" : "") + gained + " coin gained by our own prices so far ("
                            + (c.getInt("expected") >= 0 ? "+" : "") + c.getInt("expected") + " a delivery when it was struck)"));
                        if (c.getInt("weOwe") > 0) tip.add(Component.literal("We owe them " + c.getInt("weOwe") + " coin for deliveries short"));
                        if (c.getInt("theyOwe") > 0) tip.add(Component.literal("They owe us " + c.getInt("theyOwe") + " coin for deliveries short"));
                        tip.add(Component.literal("A delivery short costs " + c.getInt("penalty") + " coin; three in a row break it. "
                            + (c.getBoolean("road") ? "A caravan is on the road." : "Next due day " + c.getLong("next") + "; ends day " + c.getLong("ends") + ".")));
                    }
                }
                case 'C' -> {
                    if (cy + CHART * ROW <= y + h) {
                        List<Component> t = chart(g, font, l.tag(), x, cy, w, CHART * ROW - 2, mx, my);
                        if (t != null) tip = t;
                    }
                }
                default -> { }
            }
            cy += ROW;
        }
        if (start + rows < lines.size()) small(g, font, "(scroll for more)", x + w - 60, y + h - 8, Ui.FAINT);
        return tip;
    }

    /** Farmers and miners over the days, and the leans the deals put on their shares. */
    @Nullable
    private static List<Component> chart(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int mx, int my) {
        int[] days = m.getIntArray("spec_days"), farmers = m.getIntArray("spec_farmers"), miners = m.getIntArray("spec_miners");
        int[] farm = m.getIntArray("spec_farm"), mine = m.getIntArray("spec_mine");
        int n = days.length;
        g.fill(x, y, x + w, y + h, Ui.ROW_ALT);
        g.renderOutline(x, y, w, h, Ui.EDGE_SOFT);
        int most = 1;
        for (int i = 0; i < n; i++) most = Math.max(most, Math.max(farmers[i], miners[i]));
        int left = x + 4, right = x + w - 4, top = y + 10, bottom = y + h - 3;
        int farmCol = 0xFF23803A, mineCol = 0xFF5A5A5A;
        small(g, font, "Farmers", x + 4, y + 2, farmCol);
        small(g, font, "Miners", x + 40, y + 2, mineCol);
        small(g, font, "(the last " + n + " mornings)", x + 72, y + 2, Ui.FAINT);
        int px0 = -1, fy0 = 0, my0 = 0;
        for (int i = 0; i < n; i++) {
            int px = n == 1 ? left : left + (right - left) * i / (n - 1);
            int fy = bottom - (bottom - top) * farmers[i] / most, myy = bottom - (bottom - top) * miners[i] / most;
            if (px0 >= 0) {
                line(g, px0, fy0, px, fy, farmCol);
                line(g, px0, my0, px, myy, mineCol);
            }
            px0 = px;
            fy0 = fy;
            my0 = myy;
        }
        if (mx >= x && mx < x + w && my >= y && my < y + h && n > 0) {
            int i = n == 1 ? 0 : Math.max(0, Math.min(n - 1, Math.round((mx - left) * (n - 1) / (float) Math.max(1, right - left))));
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("Day " + days[i]));
            tip.add(Component.literal(farmers[i] + " farmers (share ×" + String.format(Locale.ROOT, "%.2f", farm[i] / 100.0) + ")"));
            tip.add(Component.literal(miners[i] + " miners (share ×" + String.format(Locale.ROOT, "%.2f", mine[i] / 100.0) + ")"));
            return tip;
        }
        return null;
    }

    /** A line of single pixels, one per column. */
    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int colour) {
        int steps = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        for (int s = 0; s <= steps; s++) {
            int px = x0 + (x1 - x0) * s / steps, py = y0 + (y1 - y0) * s / steps;
            g.fill(px, py, px + 1, py + 1, colour);
        }
    }

    private static String price(float each) {
        if (each >= 1.0F) return String.format(Locale.ROOT, "%.1fc", each);
        if (each <= 0) return "—";
        return "1c/" + Math.max(2, Math.round(1.0F / each));
    }

    private static List<Component> wrap(Font font, String s) {
        List<Component> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (font.width(line + " " + word) > 260 && line.length() > 0) {
                out.add(Component.literal(line.toString()));
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(Component.literal(line.toString()));
        return out;
    }

    private static List<CompoundTag> compounds(CompoundTag m, String key) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    private static List<String> strings(CompoundTag m, String key) {
        List<String> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
