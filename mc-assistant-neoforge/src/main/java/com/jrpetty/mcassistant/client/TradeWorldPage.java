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

/**
 * [trade-world] The Harbour page and the Roads page of the town's books (CityScreen), as the server writes them
 * (entity/Harbours.report, entity/RoadWorks.report): rows of headings, lines, quieter lines and warnings. The Harbour
 * page has the shore, the office and pier, the lighthouse, the harbourmaster, the ship in harbour (its hold, its purse,
 * what it has bought here), the next ship due, the ships gone and what the lands over the sea think of the town; the
 * Roads page each road (its grade, its traffic, its next grade and what that costs, the gang's work, the inn halfway)
 * and the comings and goings (who has come and gone of their own accord, who is thinking of leaving).
 */
public final class TradeWorldPage {

    private TradeWorldPage() {}

    /** The page. Returns the tooltip for what the mouse is over (a clipped row's whole text), or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<String> rows = new ArrayList<>();
        ListTag l = m.getList("rows", Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) rows.add(l.getString(i));
        if (rows.isEmpty()) {
            Ui.section(g, font, "Not yet", x, y, w);
            small(g, font, "Nothing written here yet.", x + 4, y + 14, Ui.MUTED);
            return null;
        }
        int max = (int) ((w - 10) / 0.75F);
        List<String[]> lines = new ArrayList<>();
        for (String r : rows) {
            char kind = r.length() > 1 && r.charAt(1) == '|' ? r.charAt(0) : 'M';
            String text = r.length() > 1 && r.charAt(1) == '|' ? r.substring(2) : r;
            if (kind == 'H') {
                lines.add(new String[]{ "H", text });
                continue;
            }
            for (String part : wrap(font, text, max - 8)) lines.add(new String[]{ String.valueOf(kind), part, text });
        }
        int fit = Math.max(1, (h - 2) / 9);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - fit)));
        int cy = y;
        List<Component> tip = null;
        for (int i = start; i < lines.size() && cy < y + h - 8; i++) {
            String[] r = lines.get(i);
            switch (r[0]) {
                case "H" -> {
                    Ui.section(g, font, r[1], x, cy, w);
                    cy += 11;
                    continue;
                }
                case "L" -> small(g, font, r[1], x + 3, cy, Ui.INK);
                case "W" -> small(g, font, r[1], x + 6, cy, Ui.WARN);
                default -> small(g, font, r[1], x + 6, cy, Ui.MUTED);
            }
            if (mx >= x && mx < x + w && my >= cy && my < cy + 9 && r.length > 2 && !r[2].equals(r[1])) {
                tip = List.of(Component.literal(r[2]));
            }
            cy += 9;
        }
        if (lines.size() > fit) small(g, font, "(scroll for more)", x + w - 60, y + h - 8, Ui.FAINT);
        return tip;
    }

    private static List<String> wrap(Font font, String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = cur.length() == 0 ? word : cur + " " + word;
            if (font.width(next) > max && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(next);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }
}
