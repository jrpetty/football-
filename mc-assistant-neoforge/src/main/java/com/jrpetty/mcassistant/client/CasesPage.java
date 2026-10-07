package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [crime] The Cases page of the town's books (CityScreen), as the server's Crime.report has it. Across the top, the
 * month's crime (how many, how many solved, how many in every hundred folk) with eight weeks of it as a chart (the
 * crimes, the solved, and the deeds put off by the watch, the lamps and the folk about), and beside it what keeps crime
 * down in this town. Below, the casebook: the cases down the left, the latest first, and the one picked (the scroll
 * wheel moves down the list) opened on the right as its case file: what was done, where and when; where it stands;
 * the clues; what the witnesses said; the suspects and the weight of the evidence on each; and the detective's notes.
 */
public final class CasesPage {

    private CasesPage() {}

    private static final int CRIME = 0xFFB83227, SOLVED = 0xFF23803A, OFF = 0xFF8A8A8A, OPEN = 0xFFB7791F, CLEARED = 0xFF2E6FBF;

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        ListTag cases = m.getList("cases", Tag.TAG_COMPOUND);
        int cy = y;
        head(g, font, "Casebook: " + m.getInt("month") + (m.getInt("month") == 1 ? " crime" : " crimes") + " in 28 days, "
            + m.getInt("solved") + " solved, " + m.getInt("unsolved") + " given up — " + m.getInt("rate") + " in every 100 folk", x, cy, w);
        cy += 12;
        // The eight weeks, and what keeps crime down.
        int chartW = Math.min(150, w / 3), chartH = 44;
        List<Component> tip = chart(g, font, m, x, cy, chartW, chartH, mx, my);
        int kx = x + chartW + 10, kw = w - chartW - 10, ky = cy;
        ListTag keeps = m.getList("keeps", Tag.TAG_STRING);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < keeps.size(); i++) lines.add(keeps.getString(i));
        lines.add(m.getString("constable"));
        for (String s : lines) {
            for (FormattedCharSequence part : wrap(font, s, kw)) {
                if (ky > cy + chartH + 4) break;
                small(g, font, part, kx, ky, Ui.MUTED);
                ky += 8;
            }
        }
        cy += chartH + 10;
        if (cases.isEmpty()) {
            head(g, font, "No cases", x, cy, w);
            small(g, font, "Nothing has been reported to the watch. Folk who are poor, hungry, low or bitter, and not honest enough to let it go,", x + 4, cy + 14, Ui.FAINT);
            small(g, font, "may pick a purse, take from a chest or break a window. The watch looks into it, and the council tries the culprit.", x + 4, cy + 23, Ui.FAINT);
            return tip;
        }
        // The list.
        int lw = Math.max(110, w * 34 / 100), rows = Math.max(1, (y + h - cy - 12) / 10);
        int pick = Math.max(0, Math.min(scroll / 3, cases.size() - 1));       // the wheel moves three a notch: a case a notch
        int start = Math.max(0, Math.min(pick - rows / 2, cases.size() - rows));
        head(g, font, "Cases (scroll to pick)", x, cy, lw);
        int ly = cy + 12;
        for (int i = start; i < cases.size() && i < start + rows; i++) {
            CompoundTag c = cases.getCompound(i);
            boolean on = i == pick;
            if (on) g.fill(x, ly - 1, x + lw, ly + 9, Ui.ROW_PICK);
            g.fill(x + 2, ly + 2, x + 6, ly + 6, colour(c.getString("stage")));
            small(g, font, Ui.clip(font, "Day " + c.getLong("day") + " · " + c.getString("title"), (int) ((lw - 10) / 0.75F)), x + 9, ly, on ? Ui.INK : Ui.MUTED);
            if (mx >= x && mx < x + lw && my >= ly - 1 && my < ly + 9) {
                tip = List.of(Component.literal(c.getString("title")), Component.literal(c.getString("what")), Component.literal(c.getString("status")));
            }
            ly += 10;
        }
        // The case file.
        CompoundTag c = cases.getCompound(pick);
        int fx = x + lw + 8, fw = w - lw - 8, fy = cy, bottom = y + h;
        head(g, font, "Case file: " + c.getString("title"), fx, fy, fw);
        fy += 12;
        List<String[]> body = new ArrayList<>();
        body.add(new String[]{ "N", c.getString("what") + ", " + c.getString("hour") + " on day " + c.getLong("day") + "." });
        StringBuilder where = new StringBuilder(cap(c.getString("status")));
        if (!c.getString("investigator").isEmpty()) where.append("; ").append(c.getString("investigator")).append(" on the case");
        if (!c.getString("accused").isEmpty()) where.append("; accused: ").append(c.getString("accused"));
        body.add(new String[]{ "S", where + "." });
        if (!c.getString("verdict").isEmpty()) body.add(new String[]{ "S", "Verdict: " + c.getString("verdict") + (c.getString("sentence").isEmpty() ? "" : "; " + c.getString("sentence")) + "." });
        if (c.contains("helpers")) body.add(new String[]{ "S", "With the help of " + c.getString("helpers") + "." });
        section(body, "Clues", c.getList("clues", Tag.TAG_STRING), "None found yet.");
        section(body, "Witnesses", c.getList("witnesses", Tag.TAG_STRING), "Nobody asked yet.");
        section(body, "Suspects", c.getList("suspects", Tag.TAG_STRING), "Not narrowed down yet.");
        section(body, "The detective's notes", c.getList("notes", Tag.TAG_STRING), "None.");
        int max = (int) (fw / 0.75F) - 4;
        for (String[] b : body) {
            if (fy > bottom - 8) break;
            if (b[0].equals("H")) {
                fy += 2;
                small(g, font, b[1], fx, fy, Ui.ACCENT);
                fy += 9;
                continue;
            }
            int colour = b[0].equals("S") ? Ui.INK : b[0].equals("M") ? Ui.FAINT : Ui.MUTED;
            for (FormattedCharSequence part : font.split(FormattedText.of((b[0].equals("L") ? "· " : "") + b[1]), max)) {
                if (fy > bottom - 8) break;
                small(g, font, part, fx + (b[0].equals("L") ? 2 : 0), fy, colour);
                fy += 8;
            }
        }
        return tip;
    }

    /** A section heading, cut to the width it has (Ui.section writes it in capitals, which run wide). */
    private static void head(GuiGraphics g, Font font, String label, int x, int y, int w) {
        String cut = label;
        while (cut.length() > 4 && font.width(cut.toUpperCase()) > w - 12) cut = cut.substring(0, cut.length() - 1);
        Ui.section(g, font, cut.length() < label.length() ? cut.trim() + "…" : label, x, y, w);
    }

    private static void section(List<String[]> body, String title, ListTag l, String none) {
        body.add(new String[]{ "H", title });
        if (l.isEmpty()) body.add(new String[]{ "M", none });
        for (int i = 0; i < l.size(); i++) body.add(new String[]{ "L", l.getString(i) });
    }

    /** Eight weeks of it: a bar a week, the crimes in red with the solved in green over them, and the deeds put off in grey. */
    @Nullable
    private static List<Component> chart(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int mx, int my) {
        int[] crimes = m.getIntArray("weeks"), solved = m.getIntArray("solved_weeks"), off = m.getIntArray("off_weeks");
        g.fill(x, y, x + w, y + h, 0xFFD4D0C4);
        g.renderOutline(x, y, w, h, Ui.EDGE_SOFT);
        int most = 1;
        for (int i = 0; i < crimes.length; i++) most = Math.max(most, Math.max(crimes[i], i < off.length ? off[i] : 0));
        int n = Math.max(1, crimes.length), slot = (w - 4) / n, base = y + h - 9;
        List<Component> tip = null;
        for (int i = 0; i < crimes.length; i++) {
            int bx = x + 3 + i * slot;
            int ch = (h - 14) * crimes[i] / most, sh = (h - 14) * (i < solved.length ? solved[i] : 0) / most, oh = (h - 14) * (i < off.length ? off[i] : 0) / most;
            if (oh > 0) g.fill(bx + slot / 2, base - oh, bx + slot - 2, base, OFF);
            if (ch > 0) g.fill(bx, base - ch, bx + slot / 2, base, CRIME);
            if (sh > 0) g.fill(bx, base - sh, bx + slot / 2, base, SOLVED);
            if (mx >= bx && mx < bx + slot && my >= y && my < y + h) {
                int ago = crimes.length - 1 - i;
                tip = List.of(Component.literal(ago == 0 ? "This week" : ago == 1 ? "Last week" : ago + " weeks ago"),
                    Component.literal(crimes[i] + " crimes, " + (i < solved.length ? solved[i] : 0) + " solved"),
                    Component.literal((i < off.length ? off[i] : 0) + " put off by the watch, the lamps or folk about"));
            }
        }
        small(g, font, "■ crimes ■ solved ■ put off", x + 3, y + h - 8, Ui.FAINT);
        return tip;
    }

    private static int colour(String stage) {
        return switch (stage) {
            case "CONVICTED" -> SOLVED;
            case "UNSOLVED" -> OFF;
            case "ACQUITTED" -> CLEARED;
            default -> OPEN;
        };
    }

    private static List<FormattedCharSequence> wrap(Font font, String s, int w) {
        return font.split(FormattedText.of(s), (int) (w / 0.75F));
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static void small(GuiGraphics g, Font font, FormattedCharSequence s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
