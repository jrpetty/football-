package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [civic-land] The Taxes page of the town's books (CityScreen), as the server's CivicLand.report has it. On the left,
 * the four taxes the council sets, each at its rate (four pips), what it does and since when; the next tax day and the
 * council's next sitting; and the take day by day for three weeks, a bar a day (the head tax and the levy in gold, the
 * share of wages in blue, the toll in green, the citizens' taxes in purple), the mouse over a day for its figures. On
 * the right, the town's temper: the unrest on a bar marked where folk grumble, protest and rise, and the reasons for it;
 * a revolt, its strike day and its challenge to the leader, in red; the last protest and how the council answered; who
 * owes back taxes; the council's last votes on the taxes and who voted how; and the last tax day's round.
 */
public final class TaxesPage {

    private TaxesPage() {}

    static final int HEAD = 0xFFC9A227, WAGE = 0xFF3F6FA8, TOLL = 0xFF4E9A3A, PLAYERS = 0xFF8A6FB0, CALM = 0xFF4E9A3A, GRUMBLE = 0xFFC9A227,
        ANGRY = 0xFFC2452F;

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag realm, int x, int y, int w, int h, int scroll, int mx, int my) {
        CompoundTag m = realm.getCompound("taxes"), u = realm.getCompound("unrest");
        if (m.isEmpty()) {
            UiStyle.empty(g, font, "The taxes", "The town's books have no taxes in them yet.", x, y, w, Math.min(h, 60));
            return null;
        }
        List<Component> tip = null;
        int lw = Math.max(160, w * 50 / 100), rx = x + lw + 8, rw = w - lw - 8;
        int cy = y;
        // ------------------------------------------------ the rates
        section(g, font, "The taxes of " + realm.getString("town"), x, cy, lw);
        cy += 12;
        ListTag rates = m.getList("rates", Tag.TAG_COMPOUND);
        for (int i = 0; i < rates.size(); i++) {
            CompoundTag r = rates.getCompound(i);
            if (i % 2 == 0) g.fill(x, cy - 1, x + lw, cy + 17, Ui.ROW);
            small(g, font, r.getString("lever"), x + 3, cy, Ui.INK);
            int rate = r.getInt("rate");
            for (int p = 0; p < 3; p++) {
                int px = x + lw - 40 + p * 9;
                g.fill(px, cy, px + 7, cy + 6, p < rate ? (rate >= 3 ? ANGRY : rate == 2 ? GRUMBLE : CALM) : 0x40000000);
            }
            small(g, font, Ui.clip(font, r.getString("words"), (int) ((lw - 50) / 0.75F) - 70), x + 3 + (int) (font.width(r.getString("lever")) * 0.75F) + 6, cy,
                rate == 0 ? Ui.FAINT : Ui.MUTED);
            small(g, font, Ui.clip(font, r.getString("does"), (int) ((lw - 6) / 0.75F)), x + 3, cy + 8, Ui.FAINT);
            if (mx >= x && mx < x + lw && my >= cy - 1 && my < cy + 17) {
                long since = r.getLong("since");
                tip = List.of(Component.literal(r.getString("lever") + ": " + r.getString("words")), Component.literal(r.getString("does")),
                    Component.literal(since >= 0 ? "Set on day " + since : "As it has always been"));
            }
            cy += 19;
        }
        long day = m.getLong("day"), taxDay = m.getLong("taxDay"), sitting = m.getLong("sitting");
        small(g, font, (taxDay == day ? "Tax day is today" + (m.getBoolean("roundOpen") && !m.getString("collector").isEmpty()
            ? ": " + m.getString("collector") + " is going round." : ".") : "Next tax day: day " + taxDay + ".")
            + "  The council sits on the taxes " + (sitting == day ? "this evening." : "on day " + sitting + "."), x + 2, cy, Ui.MUTED);
        cy += 12;
        // ------------------------------------------------ the take
        section(g, font, "What they take: " + m.getInt("week") + " coins this week", x, cy, lw);
        cy += 12;
        int chartH = Math.max(36, Math.min(60, y + h - cy - 22));
        List<Component> ct = chart(g, font, m.getList("days", Tag.TAG_COMPOUND), x, cy, lw, chartH, mx, my);
        if (ct != null) tip = ct;
        cy += chartH + 2;
        legend(g, font, x + 2, cy);
        cy += 9;
        small(g, font, Ui.clip(font, m.getString("round"), (int) ((lw - 4) / 0.75F)), x + 2, cy, Ui.FAINT);
        // ------------------------------------------------ the temper
        int ry = y;
        int level = u.getInt("level");
        String word = u.getString("word");
        section(g, font, "The town's temper: " + word, rx, ry, rw);
        ry += 12;
        int colour = u.getBoolean("revolt") || level >= 60 ? ANGRY : level >= 40 ? GRUMBLE : CALM;
        Ui.bar(g, rx + 2, ry, rw - 4, 8, level / 100F, colour);
        for (int mark : new int[]{ 40, 60, 75 }) {
            int mx0 = rx + 2 + (rw - 4) * mark / 100;
            g.fill(mx0, ry - 2, mx0 + 1, ry + 10, Ui.EDGE);
        }
        if (mx >= rx && mx < rx + rw && my >= ry - 2 && my < ry + 10) {
            tip = List.of(Component.literal("Unrest " + level + " of 100 (heading for " + u.getInt("target") + ")"),
                Component.literal("40: folk grumble and keep their coin"), Component.literal("60: they protest at the hall"),
                Component.literal("75, a protest refused: they rise, refuse to pay and strike"));
        }
        ry += 11;
        small(g, font, "Unrest " + level + " of 100 · the taxes take " + u.getInt("burden") + " in a hundred of a wage", rx + 2, ry, Ui.MUTED);
        ry += 9;
        List<String[]> body = new ArrayList<>();
        ListTag why = u.getList("why", Tag.TAG_STRING);
        body.add(new String[]{ "H", "Why" });
        if (why.isEmpty()) body.add(new String[]{ "M", "Nothing troubles the town about its taxes." });
        for (int i = 0; i < why.size(); i++) body.add(new String[]{ "L", why.getString(i) });
        if (u.getBoolean("revolt")) {
            body.add(new String[]{ "H", "The revolt" });
            body.add(new String[]{ "R", "In revolt since day " + u.getLong("revoltSince") + ": " + u.getInt("angry") + " folk refuse to pay"
                + (u.getInt("refusals") > 0 ? " (" + u.getInt("refusals") + " refusals to the collector's face)" : "") + "." });
            if (u.getLong("strikeDay") >= 0) body.add(new String[]{ "R", "Strike day: day " + u.getLong("strikeDay") + " (no work, a picket before the hall)." });
            if (u.getLong("challenged") >= 0) body.add(new String[]{ "R", "The leader was challenged on day " + u.getLong("challenged") + "." });
        }
        body.add(new String[]{ "H", "Protests" });
        if (u.getLong("protestDay") < 0) body.add(new String[]{ "M", u.getLong("protestDue") >= 0 ? "One is called for this afternoon." : "None yet." });
        else body.add(new String[]{ "L", "The last on day " + u.getLong("protestDay") + (u.getString("answer").isEmpty() ? "" : ": " + u.getString("answer"))
            + " (" + u.getInt("protests") + " in all)." });
        body.add(new String[]{ "H", "Back taxes: " + m.getInt("arrearsFolk") + " owe " + m.getInt("arrearsCoins") + " coins" });
        ListTag owing = m.getList("owing", Tag.TAG_STRING);
        if (owing.isEmpty()) body.add(new String[]{ "M", "Nobody owes the town a coin." });
        for (int i = 0; i < owing.size(); i++) body.add(new String[]{ "L", owing.getString(i) });
        body.add(new String[]{ "H", "The council's votes on the taxes" });
        ListTag votes = m.getList("votes", Tag.TAG_COMPOUND);
        if (votes.isEmpty()) body.add(new String[]{ "M", "The council has not voted on the taxes yet." });
        for (int i = votes.size() - 1; i >= 0 && i >= votes.size() - 4; i--) {
            CompoundTag v = votes.getCompound(i);
            body.add(new String[]{ v.getBoolean("carried") ? "G" : "L", "Day " + (v.getLong("d") - 1) + ": " + v.getString("motion") + " — "
                + (v.getBoolean("carried") ? "carried " : "lost ") + v.getInt("ayes") + " to " + v.getInt("nays") });
            if (!v.getString("ayeNames").isEmpty()) body.add(new String[]{ "M", "Aye: " + v.getString("ayeNames") });
            if (!v.getString("nayNames").isEmpty()) body.add(new String[]{ "M", "No: " + v.getString("nayNames") });
        }
        body.add(new String[]{ "M", "Pay what you owe to any folk (\"I'd like to pay my taxes\") or /village taxes pay; urge the council: \"The taxes should be lower\"." });
        int max = (int) ((rw - 4) / 0.75F), skip = Math.max(0, scroll), line = 0;
        for (String[] b : body) {
            if (b[0].equals("H")) {
                if (line++ < skip) continue;
                if (ry > y + h - 10) break;
                ry += 2;
                small(g, font, b[1], rx, ry, Ui.ACCENT);
                ry += 9;
                continue;
            }
            for (FormattedCharSequence p : TextCache.splitPlain(font, (b[0].equals("L") || b[0].equals("G") ? "· " : b[0].equals("R") ? "! " : "") + b[1], max)) {
                if (line++ < skip) continue;
                if (ry > y + h - 8) break;
                int c = b[0].equals("R") ? ANGRY : b[0].equals("G") ? Ui.GOOD : b[0].equals("M") ? Ui.FAINT : Ui.MUTED;
                small(g, font, p, rx + 2, ry, c);
                ry += 8;
            }
        }
        return tip;
    }

    /** A bar a day, oldest on the left: the head tax and levy, the share of wages, the toll, the citizens' taxes, stacked. */
    @Nullable
    private static List<Component> chart(GuiGraphics g, Font font, ListTag days, int x, int y, int w, int h, int mx, int my) {
        g.fill(x, y, x + w, y + h, 0xFFD4D0C4);
        g.renderOutline(x, y, w, h, Ui.EDGE_SOFT);
        if (days.isEmpty()) {
            small(g, font, "Nothing taken yet.", x + 4, y + h / 2 - 3, Ui.FAINT);
            return null;
        }
        int most = 1;
        for (int i = 0; i < days.size(); i++) most = Math.max(most, days.getCompound(i).getInt("all"));
        int n = days.size(), slot = Math.max(3, (w - 6) / Math.max(1, n)), base = y + h - 3, room = h - 6;
        List<Component> tip = null;
        for (int i = 0; i < n; i++) {
            CompoundTag d = days.getCompound(i);
            int bx = x + 3 + i * slot, bw = Math.max(2, slot - 2), top = base;
            int[][] parts = { { d.getInt("head") + d.getInt("levy"), HEAD }, { d.getInt("wage"), WAGE }, { d.getInt("toll"), TOLL }, { d.getInt("players"), PLAYERS } };
            for (int[] part : parts) {
                int ph = room * part[0] / most;
                if (ph <= 0) continue;
                g.fill(bx, top - ph, bx + bw, top, part[1]);
                top -= ph;
            }
            if (mx >= bx && mx < bx + slot && my >= y && my < y + h) {
                g.renderOutline(bx - 1, y + 1, bw + 2, h - 2, Ui.EDGE);
                tip = List.of(Component.literal("Day " + d.getLong("d") + ": " + d.getInt("all") + " coins"),
                    Component.literal("Head tax " + d.getInt("head") + ", war levy " + d.getInt("levy") + ", toll " + d.getInt("toll")),
                    Component.literal("Share of wages " + d.getInt("wage") + " (of " + d.getInt("wages") + " paid), citizens " + d.getInt("players")));
            }
        }
        return tip;
    }

    /** The key under the chart: each colour's square, then its name, one after another. */
    private static void legend(GuiGraphics g, Font font, int x, int y) {
        String[] names = { "head tax & levy", "share of wages", "toll", "citizens" };
        int[] c = { HEAD, WAGE, TOLL, PLAYERS };
        int at = x;
        for (int i = 0; i < names.length; i++) {
            g.fill(at, y + 1, at + 5, y + 6, c[i]);
            small(g, font, names[i], at + 7, y, Ui.FAINT);
            at += 7 + (int) (font.width(names[i]) * 0.75F) + 8;
        }
    }

    private static void section(GuiGraphics g, Font font, String label, int x, int y, int w) {
        String cut = label;
        while (cut.length() > 4 && font.width(cut.toUpperCase()) > w - 12) cut = cut.substring(0, cut.length() - 1);
        Ui.section(g, font, cut.length() < label.length() ? cut.trim() + "…" : label, x, y, w);
    }

    static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    static void small(GuiGraphics g, Font font, FormattedCharSequence s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }
}
