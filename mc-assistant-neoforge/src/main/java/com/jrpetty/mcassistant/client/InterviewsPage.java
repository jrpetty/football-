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
 * [interviews] The Interviews page of the city books (CityScreen), as the server's Interviews.report has it: the
 * interviews coming and held, the latest first. Each: the post, the day and the place, the panel, and who got it and
 * why; then a row for each candidate — its trade, level and years, where it stood on paper, how its interview went part
 * by part (nerves, its letter, its answers, the work it showed, the words put in for it, its honesty, a boast, not
 * coming), its total and what came of it — and the last few lines that were said. The mouse over a candidate gives the
 * whole of it; the wheel scrolls.
 */
public final class InterviewsPage {

    private InterviewsPage() {}

    private static final int ROW = 10;
    private static final String[] PARTS = { "Nrv", "Ltr", "Ans", "Wrk", "Ref", "Hon", "Bst", "Abs" };
    private static final String[] PART_WORDS = { "nerves", "the letter", "the answers", "the work shown", "references", "honesty",
        "a boast", "not coming" };

    private record Line(char kind, @Nullable CompoundTag tag, @Nullable CompoundTag parent, String text) {}

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag d, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<Line> lines = new ArrayList<>();
        List<CompoundTag> all = compounds(d, "interviews");
        lines.add(new Line('H', null, null, "Interviews: " + d.getInt("coming") + " coming, " + d.getInt("held") + " held"));
        if (all.isEmpty()) {
            lines.add(new Line('M', null, null, "None yet. A post that matters — a notice two or more answer, the teacher, the constable, a new "
                + "workplace's first keeper — goes to interview at the hall, or at a table by the board."));
        }
        for (CompoundTag iv : all) {
            String stage = iv.getString("stage");
            boolean done = stage.equals("done");
            String when = done ? "held day " + (iv.getLong("decided") + 1) : stage.equals("cancelled") ? "called off"
                : stage.equals("set") ? "set for day " + (iv.getLong("due") + 1) : "going on now";
            lines.add(new Line('T', iv, null, capital(iv.getString("title")) + " — " + when + (iv.getString("where").isEmpty() ? "" : ", at " + iv.getString("where"))));
            lines.add(new Line('P', iv, null, "Panel: " + iv.getString("panel")));
            if (done && !iv.getString("winner").isEmpty()) {
                lines.add(new Line('W', iv, null, iv.getString("winner") + " got it" + (iv.getBoolean("close") ? " (a close thing)" : "") + ": "
                    + iv.getString("reason") + "."));
            } else if (stage.equals("cancelled")) {
                lines.add(new Line('M', iv, null, capital(iv.getString("reason")) + "."));
            }
            lines.add(new Line('A', iv, null, ""));
            for (CompoundTag c : compounds(iv, "cands")) lines.add(new Line('c', c, iv, ""));
            ListTag said = iv.getList("said", Tag.TAG_STRING);
            for (int i = Math.max(0, said.size() - 4); i < said.size(); i++) {
                String s = said.getString(i);
                int bar = s.indexOf('|');
                lines.add(new Line('S', null, iv, bar < 0 ? s : s.substring(0, bar) + ": “" + s.substring(bar + 1) + "”"));
            }
            lines.add(new Line(' ', null, null, ""));
        }
        int rows = Math.max(1, (h - 4) / ROW);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int cy = y;
        List<Component> tip = null;
        int[] cols = { 0, 92, 150, 168, 188, 214 };
        int partsX = 214, partW = 19, totalX = partsX + PARTS.length * partW + 4;
        for (int i = start; i < lines.size() && cy + ROW <= y + h; i++) {
            Line l = lines.get(i);
            boolean over = mx >= x && mx < x + w && my >= cy && my < cy + ROW;
            switch (l.kind()) {
                case 'H' -> Ui.section(g, font, l.text(), x, cy + 1, w);
                case 'M' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.FAINT);
                case 'T' -> {
                    g.fill(x - 2, cy, x + w, cy + ROW - 1, Ui.HEADER);
                    g.drawString(font, Ui.clip(font, l.text(), w - 4), x, cy + 1, Ui.INK, false);
                }
                case 'P' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.MUTED);
                case 'W' -> small(g, font, Ui.clip(font, l.text(), (int) (w / 0.75F)), x + 4, cy + 2, Ui.GOOD);
                case 'S' -> small(g, font, Ui.clip(font, l.text(), (int) ((w - 8) / 0.75F)), x + 8, cy + 2, Ui.FAINT);
                case 'A' -> {
                    String[] heads = { "Candidate", "Trade", "Lvl", "Age", "Paper" };
                    for (int k = 0; k < heads.length; k++) small(g, font, heads[k], x + cols[k], cy + 2, Ui.FAINT);
                    for (int k = 0; k < PARTS.length; k++) small(g, font, PARTS[k], x + partsX + k * partW, cy + 2, Ui.FAINT);
                    small(g, font, "Total", x + totalX, cy + 2, Ui.FAINT);
                    small(g, font, "Outcome", x + totalX + 26, cy + 2, Ui.FAINT);
                }
                case 'c' -> {
                    CompoundTag c = l.tag();
                    String outcome = c.getString("outcome");
                    boolean chosen = outcome.equals("chosen");
                    g.fill(x - 2, cy, x + w, cy + ROW - 1, over ? Ui.HI : chosen ? Ui.ROW_PICK : Ui.ROW_ALT);
                    String who = c.getString("name") + (c.getString("from").isEmpty() ? "" : " of " + c.getString("from"));
                    small(g, font, Ui.clip(font, who, 118), x, cy + 2, Ui.INK);
                    small(g, font, Ui.clip(font, c.getString("trade"), 74), x + cols[1], cy + 2, Ui.MUTED);
                    small(g, font, Integer.toString(c.getInt("level")), x + cols[2], cy + 2, Ui.INK);
                    small(g, font, Integer.toString(c.getInt("age")), x + cols[3], cy + 2, Ui.INK);
                    small(g, font, Integer.toString(c.getInt("paper")), x + cols[4], cy + 2, Ui.INK);
                    int[] parts = c.getIntArray("parts");
                    boolean seen = c.getBoolean("seen");
                    for (int k = 0; k < PARTS.length && k < parts.length; k++) {
                        int n = parts[k];
                        String s = !seen && !c.getBoolean("absent") ? "·" : n == 0 ? "0" : (n > 0 ? "+" : "") + n;
                        small(g, font, s, x + partsX + k * partW, cy + 2, n > 0 ? Ui.GOOD : n < 0 ? Ui.BAD : Ui.FAINT);
                    }
                    small(g, font, Integer.toString(c.getInt("total")), x + totalX, cy + 2, Ui.INK);
                    String o = outcome.isEmpty() ? (c.getBoolean("absent") ? "absent" : seen ? "heard" : c.getBoolean("letter") ? "letter in" : "waiting")
                        : chosen ? "Chosen" : "Not chosen";
                    small(g, font, Ui.clip(font, o, (int) ((w - totalX - 26) / 0.75F)), x + totalX + 26, cy + 2,
                        chosen ? Ui.GOOD : outcome.isEmpty() ? Ui.WARN : Ui.BAD);
                    if (over) tip = tip(c, l.parent());
                }
                default -> { }
            }
            cy += ROW;
        }
        if (start + rows < lines.size()) small(g, font, "(scroll for more)", x + w - 60, y + h - 8, Ui.FAINT);
        return tip;
    }

    private static List<Component> tip(CompoundTag c, @Nullable CompoundTag iv) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(c.getString("name") + (c.getString("from").isEmpty() ? ", of the town's own" : " of " + c.getString("from"))
            + (iv == null ? "" : ", for " + iv.getString("title"))));
        out.add(Component.literal((c.getString("trade").isEmpty() ? "No trade" : c.getString("trade")) + ", level " + c.getInt("level") + ", "
            + c.getInt("age") + " years; " + (c.getBoolean("letter") ? "wrote a letter" : "no letter")));
        out.add(Component.literal("On paper: " + c.getInt("paper") + (c.getString("good").isEmpty() ? "" : " — " + c.getString("good"))));
        int[] parts = c.getIntArray("parts");
        List<String> said = new ArrayList<>();
        for (int k = 0; k < PART_WORDS.length && k < parts.length; k++) if (parts[k] != 0) said.add(PART_WORDS[k] + " " + (parts[k] > 0 ? "+" : "") + parts[k]);
        if (!said.isEmpty()) out.add(Component.literal("At interview: " + String.join(", ", said)));
        if (!c.getString("evidence").isEmpty()) out.add(Component.literal("Showed: " + c.getString("evidence")));
        if (!c.getString("refs").isEmpty()) out.add(Component.literal("Words for it: " + c.getString("refs")));
        out.add(Component.literal("Total: " + c.getInt("total") + (c.getString("outcome").isEmpty() ? "" : " — " + c.getString("outcome")
            + (c.getString("why").isEmpty() || c.getString("outcome").equals("chosen") ? "" : ": " + c.getString("why")))));
        return out;
    }

    private static List<CompoundTag> compounds(CompoundTag m, String key) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
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
