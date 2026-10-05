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
 * The Jobs page's other view in the city books (CityScreen): the job market between towns, as the
 * server's JobMarket.report has it. The notices on our board and how each went; the applications,
 * each applicant's level, years and knacks, why it came and the leader's verdict; who came and who
 * left this week and why; who is on the road here; and what the other towns want that word of has
 * come here. Scrolls with the wheel; the mouse over an application gives the whole of it.
 */
public final class JobMarketPage {

    private JobMarketPage() {}

    private static final int ROW = 10;

    /** The switch's label for the market: "Between towns: 2 notices up, 3 applied". */
    public static String label(CompoundTag m) {
        int open = m.getInt("open"), waiting = m.getInt("waiting"), came = m.getInt("came"), went = m.getInt("went");
        StringBuilder sb = new StringBuilder("Between towns");
        List<String> bits = new ArrayList<>();
        if (open > 0) bits.add(open + (open == 1 ? " notice up" : " notices up"));
        if (waiting > 0) bits.add(waiting + " applied");
        if (came + went > 0) bits.add(came + " came, " + went + " left");
        if (!bits.isEmpty()) sb.append(": ").append(String.join(", ", bits));
        return sb.toString();
    }

    /** One line of the page: what it is and what it shows. */
    private record Line(char kind, @Nullable CompoundTag tag, String text) {}

    /** The page, drawn from the scroll'th line down. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<Line> lines = new ArrayList<>();
        List<CompoundTag> ops = compounds(m, "openings"), apps = compounds(m, "applications"), moves = compounds(m, "moves");
        List<String> road = strings(m, "road"), seen = strings(m, "seen");
        lines.add(new Line('H', null, "Notices on our board"));
        if (ops.isEmpty()) lines.add(new Line('M', null, "None yet: the town has found the hands it wants at home, or has no coin to pay a newcomer."));
        for (CompoundTag o : ops) lines.add(new Line('O', o, ""));
        lines.add(new Line('H', null, "Applications"));
        if (apps.isEmpty()) lines.add(new Line('M', null, "Nobody has applied: folk elsewhere read our notices on their own boards, if word of them has reached them."));
        else lines.add(new Line('A', null, ""));                       // the headings
        for (CompoundTag a : apps) lines.add(new Line('a', a, ""));
        lines.add(new Line('H', null, "Came and went this week"));
        if (moves.isEmpty()) lines.add(new Line('M', null, "Nobody came or went this week."));
        for (CompoundTag v : moves) lines.add(new Line('V', v, ""));
        if (!road.isEmpty()) {
            lines.add(new Line('H', null, "On the road here"));
            for (String s : road) lines.add(new Line('G', null, s));
        }
        lines.add(new Line('H', null, "Word from other towns"));
        if (seen.isEmpty()) lines.add(new Line('M', null, "No word of notices elsewhere: it comes by the roads, the caravans and the elders' dealings."));
        for (String s : seen) lines.add(new Line('N', null, s));

        int rows = Math.max(1, (h - 4) / ROW);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int cy = y;
        List<Component> tip = null;
        for (int i = start; i < lines.size() && cy + ROW <= y + h; i++) {
            Line l = lines.get(i);
            boolean over = mx >= x && mx < x + w && my >= cy && my < cy + ROW;
            switch (l.kind()) {
                case 'H' -> Ui.section(g, font, l.text(), x, cy + 1, w);
                case 'M' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.FAINT);
                case 'G' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.GOOD);
                case 'N' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.MUTED);
                case 'O' -> {
                    CompoundTag o = l.tag();
                    g.fill(x - 2, cy, x + w, cy + ROW - 1, over ? Ui.HI : Ui.ROW);
                    Ui.chip(g, x, cy + 1, Ui.job(o.getInt("ordinal")));
                    small(g, font, capital(o.getString("title")), x + 9, cy + 2, Ui.INK);
                    small(g, font, o.getInt("wage") + "c a day", x + 70, cy + 2, Ui.INK);
                    small(g, font, Ui.clip(font, o.getString("wants"), (int) (190 / 0.75F)), x + 112, cy + 2, Ui.MUTED);
                    String state = o.getString("state");
                    String how = switch (state) {
                        case "open" -> "open, day " + (o.getLong("posted") + 1) + (o.getInt("applied") > 0 ? " — " + o.getInt("applied") + " applied" : "");
                        case "filled" -> "filled: " + o.getString("by") + " of " + o.getString("from");
                        case "lapsed" -> "came down: " + o.getString("note");
                        default -> "withdrawn: " + o.getString("note");
                    };
                    int col = state.equals("open") ? Ui.WARN : state.equals("filled") ? Ui.GOOD : Ui.FAINT;
                    small(g, font, Ui.clip(font, how, (int) ((w - 306) / 0.75F)), x + 306, cy + 2, col);
                    if (over) tip = List.of(Component.literal("Wanted: " + o.getString("title") + ", " + o.getInt("wage") + " a day"),
                        Component.literal("Wants: " + o.getString("wants")), Component.literal("Why: " + o.getString("why")),
                        Component.literal(capital(how)));
                }
                case 'A' -> {
                    String[] heads = { "Name", "From", "For", "Lvl", "Age", "Kn.", "Verdict" };
                    int[] cols = { 0, 64, 128, 186, 206, 228, 250 };
                    for (int k = 0; k < heads.length; k++) small(g, font, heads[k], x + cols[k], cy + 2, Ui.FAINT);
                }
                case 'a' -> {
                    CompoundTag a = l.tag();
                    String verdict = a.getString("verdict");
                    g.fill(x - 2, cy, x + w, cy + ROW - 1, over ? Ui.HI : verdict.equals("hired") ? Ui.ROW_PICK : Ui.ROW_ALT);
                    small(g, font, Ui.clip(font, a.getString("name"), 80), x, cy + 2, Ui.INK);
                    small(g, font, Ui.clip(font, a.getString("from"), 80), x + 64, cy + 2, Ui.MUTED);
                    small(g, font, Ui.clip(font, a.getString("title"), 74), x + 128, cy + 2, Ui.MUTED);
                    small(g, font, Integer.toString(a.getInt("level")), x + 186, cy + 2, Ui.INK);
                    small(g, font, Integer.toString(a.getInt("age")), x + 206, cy + 2, Ui.INK);
                    small(g, font, Integer.toString(a.getInt("knacks")), x + 228, cy + 2, Ui.INK);
                    String v = switch (verdict) {
                        case "hired" -> "Taken on";
                        case "refused" -> "No: " + a.getString("why");
                        case "withdrawn" -> "Withdrawn: " + a.getString("why");
                        default -> "Waiting for the leader";
                    };
                    int col = verdict.equals("hired") ? Ui.GOOD : verdict.equals("waiting") ? Ui.WARN : Ui.BAD;
                    small(g, font, Ui.clip(font, v, (int) ((w - 250) / 0.75F)), x + 250, cy + 2, col);
                    if (over) {
                        tip = new ArrayList<>();
                        tip.add(Component.literal(a.getString("name") + " of " + a.getString("from") + ", for the " + a.getString("title") + "'s place"));
                        tip.add(Component.literal("Level " + a.getInt("level") + ", " + a.getInt("age") + " years old, "
                            + a.getInt("knacks") + (a.getInt("knacks") == 1 ? " knack" : " knacks") + " of the trade; applied day " + (a.getLong("day") + 1)));
                        tip.add(Component.literal("Why it applied: " + a.getString("reasons")));
                        tip.add(Component.literal(verdict.equals("hired") ? capital(a.getString("why")) : v));
                    }
                }
                case 'V' -> {
                    CompoundTag v = l.tag();
                    boolean in = v.getBoolean("in");
                    String text = "Day " + (v.getLong("day") + 1) + ": " + v.getString("name") + (in ? " came from " : " left for ") + v.getString("other")
                        + (v.getString("title").isEmpty() ? "" : " (" + v.getString("title") + ")") + ", " + v.getString("why");
                    small(g, font, (in ? "+ " : "- ") + Ui.clip(font, text, (int) (w / 0.75F) - 12), x + 2, cy + 2, in ? Ui.GOOD : Ui.WARN);
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
