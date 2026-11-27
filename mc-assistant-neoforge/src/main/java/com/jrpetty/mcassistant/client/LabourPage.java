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
 * [labour] The Jobs page's third view in the city books (CityScreen): the town's labour market, as the server's
 * LabourMarket.report has it. Who is in work, their own masters and out of work; the openings (the employer, the trade,
 * the wage offered, what it wants, the hours, who applied and who was taken); the wage index by trade against a week
 * ago; the departments; the days' record (hired, gave notice, laid off); the latest hirings, quits and lay-offs; and who
 * is out of work and why. Scrolls with the wheel; the mouse over an opening gives its applicants and their verdicts.
 */
public final class LabourPage {

    private LabourPage() {}

    private static final int ROW = 10;

    /** The switch's label: "Labour market: 3 openings, 1 out of work". */
    public static String label(CompoundTag m) {
        if (!m.getBoolean("live")) return "Labour market";
        List<String> bits = new ArrayList<>();
        int open = m.getInt("open"), out = m.getInt("out");
        if (open > 0) bits.add(open + (open == 1 ? " opening" : " openings"));
        if (out > 0) bits.add(out + " out of work");
        return "Labour market" + (bits.isEmpty() ? "" : ": " + String.join(", ", bits));
    }

    private record Line(char kind, @Nullable CompoundTag tag, String text) {}

    /** The page, from the scroll'th line down. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<Line> lines = new ArrayList<>();
        if (!m.getBoolean("live")) {
            lines.add(new Line('H', null, "The labour market"));
            lines.add(new Line('M', null, "None yet: until the market opens, the elder shares the work out among the trades."));
        } else {
            lines.add(new Line('S', null, m.getInt("employed") + " in work on terms · " + m.getInt("self") + " their own masters · "
                + m.getInt("out") + " out of work · living wage " + m.getInt("living") + "c"
                + (m.getInt("minimum") > 0 ? " · minimum wage " + m.getInt("minimum") + "c" : "")));
            lines.add(new Line('H', null, "Openings"));
            List<CompoundTag> vs = compounds(m, "vacancies");
            if (vs.isEmpty()) lines.add(new Line('M', null, "No openings: every employer has the hands it wants."));
            else lines.add(new Line('A', null, ""));
            for (CompoundTag v : vs) lines.add(new Line('O', v, ""));
            lines.add(new Line('H', null, "The wage index: a journeyman's day by trade"));
            List<CompoundTag> idx = compounds(m, "index");
            if (idx.isEmpty()) lines.add(new Line('M', null, "Reckoned from the first morning of the market."));
            for (int i = 0; i < idx.size(); i += 2) {
                CompoundTag a = idx.get(i), b = i + 1 < idx.size() ? idx.get(i + 1) : null;
                CompoundTag pair = new CompoundTag();
                pair.put("a", a);
                if (b != null) pair.put("b", b);
                lines.add(new Line('I', pair, ""));
            }
            lines.add(new Line('H', null, "The days"));
            List<CompoundTag> days = compounds(m, "days");
            if (!days.isEmpty()) lines.add(new Line('D', null, ""));
            for (int i = Math.max(0, days.size() - 8); i < days.size(); i++) lines.add(new Line('d', days.get(i), ""));
            lines.add(new Line('H', null, "Employers"));
            for (String s : strings(m, "depts")) lines.add(new Line('N', null, s));
            lines.add(new Line('H', null, "Lately"));
            List<String> news = strings(m, "news");
            if (news.isEmpty()) lines.add(new Line('M', null, "Nobody taken on, gone or let go lately."));
            for (String s : news) lines.add(new Line('L', null, s));
            List<String> outs = strings(m, "outs");
            if (!outs.isEmpty()) {
                lines.add(new Line('H', null, "Out of work"));
                for (String s : outs) lines.add(new Line('W', null, s));
            }
        }
        int rows = Math.max(1, (h - 4) / ROW);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int cy = y;
        List<Component> tip = null;
        for (int i = start; i < lines.size() && cy + ROW <= y + h; i++) {
            Line l = lines.get(i);
            boolean over = mx >= x && mx < x + w && my >= cy && my < cy + ROW;
            switch (l.kind()) {
                case 'H' -> Ui.section(g, font, l.text(), x, cy + 1, w);
                case 'S' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x, cy + 2, Ui.INK);
                case 'M' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.FAINT);
                case 'N' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.MUTED);
                case 'L' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2,
                    l.text().contains("laid off") ? Ui.BAD : l.text().contains("gave notice") ? Ui.WARN : Ui.GOOD);
                case 'W' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.WARN);
                case 'A' -> {
                    String[] heads = { "Employer", "Wants", "Wage", "Level", "Applied", "How it stands" };
                    int[] cols = { 0, 92, 160, 190, 222, 256 };
                    for (int k = 0; k < heads.length; k++) small(g, font, heads[k], x + cols[k], cy + 2, Ui.FAINT);
                }
                case 'O' -> {
                    CompoundTag o = l.tag();
                    String state = o.getString("state");
                    boolean open = state.equals("open") || state.equals("interview");
                    g.fill(x - 2, cy, x + w, cy + ROW - 1, over ? Ui.HI : open ? Ui.ROW : Ui.ROW_ALT);
                    Ui.chip(g, x, cy + 1, Ui.job(o.getInt("ordinal")));
                    small(g, font, Ui.clip(font, o.getString("employer"), 110), x + 8, cy + 2, Ui.INK);
                    int n = o.getInt("openings");
                    small(g, font, Ui.clip(font, (n > 1 && open ? n + " " : "") + o.getString("title") + (o.getBoolean("essential") ? " !" : ""), 88),
                        x + 92, cy + 2, o.getBoolean("essential") ? Ui.BAD : Ui.INK);
                    small(g, font, o.getInt("wage") + "c", x + 160, cy + 2, Ui.INK);
                    small(g, font, (o.getInt("level") > 0 ? Integer.toString(o.getInt("level")) : "any") + (o.getBoolean("apprentices") ? "+a" : ""),
                        x + 190, cy + 2, Ui.MUTED);
                    ListTag apps = o.getList("apps", Tag.TAG_COMPOUND);
                    small(g, font, Integer.toString(apps.size()), x + 222, cy + 2, Ui.INK);
                    String how = switch (state) {
                        case "open" -> "open since day " + (o.getLong("posted") + 1);
                        case "interview" -> "interviews set";
                        case "filled" -> "taken: " + o.getString("by");
                        default -> "came down: " + o.getString("note");
                    };
                    int col = state.equals("filled") ? Ui.GOOD : open ? Ui.WARN : Ui.FAINT;
                    small(g, font, Ui.clip(font, how, (int) ((w - 256) / 0.75F)), x + 256, cy + 2, col);
                    if (over) {
                        tip = new ArrayList<>();
                        tip.add(Component.literal(o.getString("employer") + " wants " + (n > 1 ? n + " " : "a ") + o.getString("title")
                            + ": " + o.getInt("wage") + " a day for a journeyman" + (o.getBoolean("essential") ? " (essential work)" : "")));
                        tip.add(Component.literal("Wants " + (o.getInt("level") > 0 ? "level " + o.getInt("level") : "any willing hand")
                            + (o.getBoolean("apprentices") ? "; an apprentice taken at three quarters" : "") + "; hours " + o.getString("hours")));
                        for (int k = 0; k < apps.size(); k++) {
                            CompoundTag a = apps.getCompound(k);
                            String v = a.getString("verdict");
                            String vs2 = switch (v) {
                                case "taken" -> "taken";
                                case "shortlisted" -> "shortlisted";
                                case "waiting" -> "waiting";
                                case "not_taken" -> "not taken";
                                default -> "withdrew";
                            };
                            tip.add(Component.literal("  " + a.getString("name") + " (" + a.getString("from") + ", level " + a.getInt("level") + "): "
                                + a.getInt("wage") + "c" + (a.getInt("now") > 0 ? " against " + a.getInt("now") + "c" : "") + " — " + vs2
                                + (a.getString("why").isEmpty() ? "" : ": " + a.getString("why"))));
                        }
                        if (apps.isEmpty()) tip.add(Component.literal("  Nobody has applied yet: the offer goes up each morning it stands empty."));
                    }
                }
                case 'I' -> {
                    CompoundTag pair = l.tag();
                    int half = w / 2;
                    for (int k = 0; k < 2; k++) {
                        if (!pair.contains(k == 0 ? "a" : "b")) continue;
                        CompoundTag r = pair.getCompound(k == 0 ? "a" : "b");
                        int bx = x + k * half;
                        Ui.chip(g, bx, cy + 1, Ui.job(r.getInt("ordinal")));
                        small(g, font, Ui.clip(font, capital(r.getString("title")), 90), bx + 8, cy + 2, Ui.INK);
                        small(g, font, r.getInt("wage") + "c", bx + 84, cy + 2, Ui.INK);
                        double was = r.getDouble("was"), now = r.getDouble("index");
                        String trend = was < 0 ? String.format(Locale.ROOT, "x%.2f", now)
                            : String.format(Locale.ROOT, "x%.2f %s", now, now > was + 0.005 ? "up" : now < was - 0.005 ? "down" : "same");
                        small(g, font, trend, bx + 108, cy + 2, was >= 0 && now > was + 0.005 ? Ui.GOOD : was >= 0 && now < was - 0.005 ? Ui.BAD : Ui.MUTED);
                        if (r.getInt("paid") > 0) small(g, font, "paid " + r.getInt("paid") + "c", bx + 146, cy + 2, Ui.FAINT);
                    }
                }
                case 'D' -> {
                    String[] heads = { "Day", "In work", "Own", "Out", "Openings", "Hired", "Notice", "Laid off", "Relief" };
                    int[] cols = { 0, 30, 68, 96, 122, 166, 200, 236, 276 };
                    for (int k = 0; k < heads.length; k++) small(g, font, heads[k], x + cols[k], cy + 2, Ui.FAINT);
                }
                case 'd' -> {
                    CompoundTag d = l.tag();
                    int[] n = d.getIntArray("n");
                    if (n.length < 8) break;
                    int[] cols = { 0, 30, 68, 96, 122, 166, 200, 236, 276 };
                    String[] vals = { Long.toString(d.getLong("day") + 1), Integer.toString(n[0]), Integer.toString(n[1]), Integer.toString(n[2]),
                        Integer.toString(n[3]), Integer.toString(n[4]), Integer.toString(n[5]), Integer.toString(n[6]),
                        String.format(Locale.ROOT, "%.2fc", d.getLong("relief") / 100.0) };
                    for (int k = 0; k < vals.length; k++) small(g, font, vals[k], x + cols[k], cy + 2, k == 3 && n[2] > 0 || k == 7 && n[6] > 0 ? Ui.BAD : Ui.INK);
                }
                default -> { }
            }
            cy += ROW;
        }
        if (start + rows < lines.size()) small(g, font, "(scroll for more)", x + w - 60, y + h - 8, Ui.FAINT);
        return tip;
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
