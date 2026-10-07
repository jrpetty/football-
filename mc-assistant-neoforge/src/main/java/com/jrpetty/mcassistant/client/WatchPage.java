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
 * [police] The Watch page of the town's books (CityScreen), as the server's Police.report has it. Across the top, the
 * watch in a line: how many, the captain, the constable, the town's trust in it, the watch house, the curfew. On the
 * left, today's roster: each guard, its duty (blue for the walls, green for the town's policing, grey for rest), what it
 * is doing now and its record (arrests, cases solved, fights broken up, folk helped, chases won); under it the week's
 * rosters as a grid of the duties, a tick for each guard that has done both kinds of work. On the right, eight weeks of
 * trouble as a chart (incidents in red, arrests in blue, the coin of the fines in gold), the week's and the month's
 * figures, and below (the scroll wheel moves it) the cells, the wanted, the beats, the week's incidents and the town's
 * special constables.
 */
public final class WatchPage {

    private WatchPage() {}

    private static final int DEFENCE = 0xFF2E6FBF, POLICE = 0xFF23803A, REST = 0xFF8A8A8A, CRISIS = 0xFFB83227,
        INCIDENT = 0xFFB83227, ARREST = 0xFF2E6FBF, FINE = 0xFFB7791F;

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<Component> tip = null;
        int cy = y;
        String head = "The watch of " + m.getString("town") + ": " + m.getInt("guards") + (m.getInt("guards") == 1 ? " guard" : " guards")
            + (m.getString("captain").isEmpty() ? "" : ", captain " + m.getString("captain"))
            + (m.getString("constable").isEmpty() ? "" : ", constable " + m.getString("constable"));
        section(g, font, head, x, cy, w);
        cy += 12;
        small(g, font, "Trust in the watch: " + m.getInt("trust") + " (" + m.getString("trustWord") + ").  " + m.getString("house"), x + 2, cy,
            m.getInt("trust") >= 60 ? Ui.GOOD : m.getInt("trust") < 40 ? Ui.BAD : Ui.MUTED);
        cy += 9;
        String sub = m.getString("override").isEmpty() ? m.getString("curfew") : m.getString("override");
        small(g, font, sub, x + 2, cy, m.getString("override").isEmpty() ? Ui.FAINT : Ui.BAD);
        cy += 12;
        if (m.getInt("guards") == 0) {
            section(g, font, "No watch", x, cy, w);
            small(g, font, "The town has no guards: nobody keeps its walls or its peace. Folk take up the watch's trade as the town grows.", x + 4, cy + 14, Ui.FAINT);
            return null;
        }
        int lw = Math.max(150, w * 52 / 100), rx = x + lw + 8, rw = w - lw - 8;
        // ------------------------------------------------ the roster
        section(g, font, "Today's roster", x, cy, lw);
        int ly = cy + 12;
        ListTag roster = m.getList("roster", Tag.TAG_COMPOUND);
        for (int i = 0; i < roster.size() && ly < y + h - 70; i++) {
            CompoundTag r = roster.getCompound(i);
            String duty = r.getString("duty");
            int colour = r.getBoolean("defence") ? DEFENCE : duty.equals("rest") ? REST : POLICE;
            if (duty.startsWith("the walls: the bell") || duty.equals("the fire") || duty.equals("the flood")) colour = CRISIS;
            if (i % 2 == 0) g.fill(x, ly - 1, x + lw, ly + 17, Ui.ROW);
            g.fill(x + 2, ly + 1, x + 6, ly + 5, colour);
            String name = r.getString("name") + (r.getBoolean("constable") ? " (constable)" : r.getBoolean("captain") ? " (captain)" : "");
            small(g, font, Ui.clip(font, name, (int) ((lw * 0.45F) / 0.75F)), x + 9, ly, Ui.INK);
            small(g, font, Ui.clip(font, duty, (int) ((lw * 0.5F) / 0.75F)), x + 9 + (int) (lw * 0.45F), ly, colour);
            String rec = r.getInt("arrests") + " arr · " + r.getInt("solved") + " solved · " + r.getInt("fights") + " fights · " + r.getInt("helped") + " helped · "
                + r.getInt("chases") + " chases";
            small(g, font, Ui.clip(font, r.getString("doing"), (int) ((lw * 0.45F) / 0.75F)), x + 9, ly + 8, Ui.FAINT);
            small(g, font, Ui.clip(font, rec, (int) ((lw * 0.52F) / 0.75F)), x + 9 + (int) (lw * 0.45F), ly + 8, Ui.FAINT);
            if (mx >= x && mx < x + lw && my >= ly - 1 && my < ly + 17) {
                tip = List.of(Component.literal(name), Component.literal("Today: " + duty), Component.literal("Now: " + r.getString("doing")),
                    Component.literal(r.getInt("arrests") + " arrests, " + r.getInt("solved") + " cases solved, " + r.getInt("fights") + " fights broken up"),
                    Component.literal(r.getInt("helped") + " folk helped, " + r.getInt("chases") + " chases won, " + r.getInt("lost") + " lost"
                        + (r.getInt("wrong") > 0 ? ", " + r.getInt("wrong") + " wrong arrests" : "")),
                    Component.literal(r.getBoolean("both") ? "Walls and streets both this week" : "Not yet both kinds of duty this week"));
            }
            ly += 19;
        }
        // ------------------------------------------------ the week's grid
        ly += 4;
        section(g, font, "The week's rosters", x, ly, lw);
        ly += 12;
        CompoundTag week = m.getCompound("week");
        long today = m.getLong("day");
        int cell = Math.max(10, Math.min(16, (lw - 70) / 7));
        for (int d = 0; d < 7; d++) small(g, font, d == 6 ? "today" : (today - 6 + d) + "", x + 60 + d * cell, ly, Ui.FAINT);
        ly += 9;
        for (int i = 0; i < roster.size() && ly < y + h - 10; i++) {
            CompoundTag r = roster.getCompound(i);
            small(g, font, Ui.clip(font, r.getString("name"), 70), x + 2, ly + 1, Ui.MUTED);
            for (int d = 0; d < 7; d++) {
                String key = Long.toString(today - 6 + d);
                String letter = week.contains(key) ? week.getCompound(key).getString(r.getString("id")) : "";
                int cx = x + 60 + d * cell;
                if (letter.isEmpty()) {
                    g.fill(cx, ly, cx + cell - 2, ly + 8, 0x30000000);
                    continue;
                }
                int c = letter.equals("W") ? DEFENCE : letter.equals("R") ? REST : POLICE;
                g.fill(cx, ly, cx + cell - 2, ly + 8, c);
                small(g, font, letter, cx + (cell - 2) / 2 - 2, ly + 1, 0xFFFFFFFF);
            }
            if (r.getBoolean("both")) small(g, font, "✔", x + 60 + 7 * cell + 2, ly + 1, Ui.GOOD);
            ly += 10;
        }
        small(g, font, "W walls · B beat · D desk · I cases · E escort · V events · R rest", x + 2, Math.min(ly + 2, y + h - 8), Ui.FAINT);
        // ------------------------------------------------ the chart and the figures
        section(g, font, "Eight weeks of trouble", rx, cy, rw);
        int chartH = 44;
        List<Component> ct = chart(g, font, m, rx, cy + 12, rw, chartH, mx, my);
        if (ct != null) tip = ct;
        int ry = cy + 12 + chartH + 4;
        small(g, font, "This week: " + m.getInt("arrestsWeek") + " arrests, " + m.getInt("fightsWeek") + " fights broken up, " + m.getInt("helpedWeek")
            + " folk helped, " + m.getInt("finesWeek") + " coins in fines.", rx, ry, Ui.MUTED);
        ry += 9;
        small(g, font, "This month: " + m.getInt("arrestsMonth") + " arrests, " + m.getInt("finesMonth") + " coins in fines.", rx, ry, Ui.MUTED);
        ry += 12;
        // ------------------------------------------------ the lists, scrolled
        List<String[]> body = new ArrayList<>();
        list(body, "In the cells", m.getList("cells", Tag.TAG_STRING), "Nobody held.");
        list(body, "Wanted", m.getList("wanted", Tag.TAG_STRING), "Nobody wanted.");
        list(body, "The beats", m.getList("beats", Tag.TAG_STRING), "No beat walked today.");
        list(body, "Incidents this week", m.getList("incidents", Tag.TAG_STRING), "A quiet week.");
        list(body, "Special constables", m.getList("constables", Tag.TAG_STRING), "None sworn in.");
        int max = (int) (rw / 0.75F) - 4, skip = Math.max(0, scroll);
        int line = 0;
        for (String[] b : body) {
            List<FormattedCharSequence> parts = b[0].equals("H") ? List.of() : font.split(FormattedText.of((b[0].equals("L") ? "· " : "") + b[1]), max);
            if (b[0].equals("H")) {
                if (line++ < skip) continue;
                if (ry > y + h - 10) break;
                ry += 2;
                small(g, font, b[1], rx, ry, Ui.ACCENT);
                ry += 9;
                continue;
            }
            for (FormattedCharSequence p : parts) {
                if (line++ < skip) continue;
                if (ry > y + h - 8) break;
                small(g, font, p, rx + 2, ry, b[0].equals("M") ? Ui.FAINT : Ui.MUTED);
                ry += 8;
            }
        }
        return tip;
    }

    private static void list(List<String[]> body, String title, ListTag l, String none) {
        body.add(new String[]{ "H", title });
        if (l.isEmpty()) body.add(new String[]{ "M", none });
        for (int i = 0; i < l.size(); i++) body.add(new String[]{ "L", l.getString(i) });
    }

    /** A bar a week: the incidents (crimes reported, fights, disorder) in red, the arrests in blue, the fines' coin in gold. */
    @Nullable
    private static List<Component> chart(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int mx, int my) {
        int[] crimes = m.getIntArray("crimes"), arrests = m.getIntArray("arrests"), fines = m.getIntArray("fines");
        g.fill(x, y, x + w, y + h, 0xFFD4D0C4);
        g.renderOutline(x, y, w, h, Ui.EDGE_SOFT);
        int most = 1;
        for (int i = 0; i < crimes.length; i++) {
            most = Math.max(most, crimes[i]);
            if (i < arrests.length) most = Math.max(most, arrests[i]);
            if (i < fines.length) most = Math.max(most, fines[i]);
        }
        int n = Math.max(1, crimes.length), slot = Math.max(3, (w - 4) / n), base = y + h - 9;
        List<Component> tip = null;
        for (int i = 0; i < crimes.length; i++) {
            int bx = x + 3 + i * slot, third = Math.max(1, (slot - 2) / 3);
            int ch = (h - 14) * crimes[i] / most, ah = (h - 14) * (i < arrests.length ? arrests[i] : 0) / most, fh = (h - 14) * (i < fines.length ? fines[i] : 0) / most;
            if (ch > 0) g.fill(bx, base - ch, bx + third, base, INCIDENT);
            if (ah > 0) g.fill(bx + third, base - ah, bx + 2 * third, base, ARREST);
            if (fh > 0) g.fill(bx + 2 * third, base - fh, bx + 3 * third, base, FINE);
            if (mx >= bx && mx < bx + slot && my >= y && my < y + h) {
                int ago = crimes.length - 1 - i;
                tip = List.of(Component.literal(ago == 0 ? "This week" : ago == 1 ? "Last week" : ago + " weeks ago"),
                    Component.literal(crimes[i] + " incidents (crimes reported, fights, disorder)"),
                    Component.literal((i < arrests.length ? arrests[i] : 0) + " arrests, " + (i < fines.length ? fines[i] : 0) + " coins in fines"));
            }
        }
        small(g, font, "■ incidents ■ arrests ■ fines", x + 3, y + h - 8, Ui.FAINT);
        return tip;
    }

    private static void section(GuiGraphics g, Font font, String label, int x, int y, int w) {
        String cut = label;
        while (cut.length() > 4 && font.width(cut.toUpperCase()) > w - 12) cut = cut.substring(0, cut.length() - 1);
        Ui.section(g, font, cut.length() < label.length() ? cut.trim() + "…" : label, x, y, w);
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
}
