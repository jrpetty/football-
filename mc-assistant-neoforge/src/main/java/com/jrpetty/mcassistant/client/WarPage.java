package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The War page of the town's books (CityScreen), as the server's WarAndPeace.report has it: whether the
 * town is at peace, on its guard or at war, and its strength as it counts it; each war, with what it is
 * for, its strength against the enemy's as the elder reckons it (and on what: the scouts' report, or
 * rumour), what the war footing has cost, who is likelier to sue for peace, where the war banner hangs,
 * and its course day by day (or, at peace, the last war's, to its treaty); the quarrels and the council
 * of war's votes; the allies and their guards on
 * the walls; what the town holds against its neighbours; its treaties; its wars before; its weariness
 * of war and the peace candidate; and those its wars cost, with its Remembrance Day. Scrolls with the wheel.
 */
public final class WarPage {

    private WarPage() {}

    private static final int ROW = 10;

    /** One line of the page: what kind of line, its words, and (for a balance) the two strengths. */
    private record Line(char kind, String text, int a, int b) {
        Line(char kind, String text) { this(kind, text, 0, 0); }
    }

    public static void draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll) {
        List<Line> lines = new ArrayList<>();
        String footing = m.getString("footing");
        lines.add(new Line('P', capital(m.getString("footing_word")) + ": strength " + m.getInt("strength") + " (" + m.getString("strength_words")
            + "); the elder is " + m.getString("temper") + " and lets a war stand " + m.getInt("stand") + " days before it talks"
            + (m.getBoolean("on") ? "" : ". Wars are switched off (villageWars)")));
        // How weary of war the town is, as a bar: what wore it down beside it.
        if (m.getInt("weary") > 0) {
            lines.add(new Line('Y', "Weariness: " + m.getString("weary_word") + (m.getString("weary_why").isEmpty() ? "" : " (" + m.getString("weary_why") + ")"),
                m.getInt("weary"), 100));
        }
        for (String e : strings(m, "election")) lines.add(new Line('W', capital(e) + "."));
        ListTag wars = m.getList("wars", Tag.TAG_COMPOUND);
        if (wars.isEmpty()) lines.add(new Line('M', "No war. A feud boils over only with a real grievance, a hawk for an elder and the council's vote."));
        for (int i = 0; i < wars.size(); i++) {
            CompoundTag war = wars.getCompound(i);
            lines.add(new Line('H', "The war with " + war.getString("enemy") + ", day " + war.getLong("days") + " (since day " + war.getLong("since") + ")"));
            lines.add(new Line('N', (war.getBoolean("ours") ? "Ours, for " : "Theirs: ") + war.getString("goal") + "."));
            lines.add(new Line('S', "Us against " + war.getString("enemy") + ", as the elder reckons it (" + war.getString("source") + ")",
                war.getInt("us"), war.getInt("them")));
            lines.add(new Line('W', "The war footing has cost " + war.getInt("cost") + " coins (the militia's pay, and the work lost to it)."));
            lines.add(new Line('N', capital(war.getString("outlook")) + "."));
            lines.add(new Line('M', capital(war.getString("banner")) + "."));
            for (String c : strings(war, "course")) lines.add(new Line('C', c));
        }
        List<String> last = strings(m, "last_course");
        if (wars.isEmpty() && !last.isEmpty()) {
            lines.add(new Line('H', last.get(0)));
            for (String c : last.subList(1, last.size())) lines.add(new Line('C', c));
        }
        section(lines, "Quarrels and the council of war", strings(m, "quarrels"), 'W', "No quarrel under way.");
        section(lines, "Allies", strings(m, "allies"), 'G', "No sworn allies.");
        section(lines, "Held against our neighbours (the last month)", strings(m, "grievances"), 'N', "Nothing.");
        section(lines, "Treaties", strings(m, "treaties"), 'G', "None signed.");
        section(lines, "Wars before", strings(m, "past"), 'M', "None.");
        List<String> fallen = strings(m, "fallen");
        String rem = m.getString("remembrance");
        if (!fallen.isEmpty() || !rem.isEmpty()) {
            lines.add(new Line('H', "Those the wars cost"));
            if (fallen.isEmpty()) lines.add(new Line('M', "Nobody: the plaque remembers the war itself, and the peace."));
            for (String f : fallen) lines.add(new Line('C', f));
            if (!rem.isEmpty()) lines.add(new Line('N', rem));
        }

        int rows = Math.max(1, (h - 4) / ROW);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int cy = y;
        int clip = (int) (w / 0.75F);
        for (int i = start; i < lines.size() && cy + ROW <= y + h; i++) {
            Line l = lines.get(i);
            switch (l.kind()) {
                case 'P' -> {
                    int colour = footing.equals("WAR") ? Ui.BAD : footing.equals("TENSION") ? Ui.WARN : Ui.GOOD;
                    g.fill(x, cy, x + 3, cy + 8, colour);
                    small(g, font, Ui.clip(font, l.text(), clip - 8), x + 6, cy + 1, colour);
                }
                case 'H' -> Ui.section(g, font, l.text(), x, cy + 1, w);
                case 'S' -> {
                    int most = Math.max(1, Math.max(l.a(), l.b()));
                    int bw = Math.max(20, w / 3);
                    small(g, font, Ui.clip(font, l.text(), (int) ((w - bw - 50) / 0.75F)), x + 4, cy + 1, Ui.MUTED);
                    int bx = x + w - bw - 40;
                    Ui.bar(g, bx, cy + 1, bw, 3, l.a() / (float) most, Ui.GOOD);
                    Ui.bar(g, bx, cy + 5, bw, 3, l.b() / (float) most, Ui.BAD);
                    small(g, font, l.a() + " : " + l.b(), bx + bw + 4, cy + 1, l.a() >= l.b() ? Ui.GOOD : Ui.BAD);
                }
                case 'W' -> small(g, font, Ui.clip(font, l.text(), clip), x + 4, cy + 2, Ui.WARN);
                case 'Y' -> {
                    int bw = Math.max(20, w / 4);
                    float frac = l.a() / (float) Math.max(1, l.b());
                    small(g, font, Ui.clip(font, l.text(), (int) ((w - bw - 36) / 0.75F)), x + 4, cy + 2, frac >= 0.5F ? Ui.BAD : Ui.WARN);
                    Ui.bar(g, x + w - bw - 28, cy + 2, bw, 5, frac, frac >= 0.7F ? Ui.BAD : frac >= 0.5F ? Ui.WARN : Ui.MUTED);
                    small(g, font, l.a() + "/100", x + w - 24, cy + 2, frac >= 0.5F ? Ui.BAD : Ui.MUTED);
                }
                case 'G' -> small(g, font, Ui.clip(font, l.text(), clip), x + 4, cy + 2, Ui.GOOD);
                case 'C' -> small(g, font, Ui.clip(font, "· " + l.text(), clip), x + 8, cy + 2, Ui.INK);
                case 'M' -> small(g, font, Ui.clip(font, l.text(), clip), x + 4, cy + 2, Ui.FAINT);
                default -> small(g, font, Ui.clip(font, l.text(), clip), x + 4, cy + 2, Ui.MUTED);
            }
            cy += ROW;
        }
        if (lines.size() > rows) small(g, font, "scroll for more", x + w - (int) (font.width("scroll for more") * 0.75F) - 2, y + h - 8, Ui.FAINT);
    }

    private static void section(List<Line> lines, String title, List<String> items, char kind, @Nullable String none) {
        lines.add(new Line('H', title));
        if (items.isEmpty() && none != null) lines.add(new Line('M', none));
        for (String s : items) lines.add(new Line(kind, capital(s)));
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
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + s.substring(1);
    }
}
