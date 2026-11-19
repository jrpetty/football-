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
 * [econ4] The Economy dashboard of the town's books (CityScreen; the server's EconReport). At the top the times: the
 * cycle's phase as a badge and its line (prices this week, the index, the council's rate). Then a grid of line charts of the
 * last sixty mornings (UiStyle.lineChart), each with its title and today's figure: the price index, output, the mean wage,
 * those out of work, the treasury, the town's debt, the money supply, the population, the council's reckoning of
 * employment, its rate, and the trade bought in and sold out; under them a strip of the phase day by day. The mouse over a
 * chart gives its lowest, highest and today's figures. Below, scrolled, the dashboard in words and the trade by ware and
 * by partner town.
 */
public final class EconomyPage {

    private EconomyPage() {}

    /** The phases' colours: boom, slowing, slump, recovering. */
    static final int[] PHASE_FILL = { UiStyle.SUCCESS_FILL, UiStyle.WARNING_FILL, UiStyle.DANGER_FILL, UiStyle.INFO_FILL };
    static final int[] PHASE_INK = { UiStyle.SUCCESS, UiStyle.WARNING, UiStyle.DANGER, UiStyle.INFO };

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag t, int x, int y, int w, int h, int scroll, int mx, int my) {
        Ui.section(g, font, "Economy: the dashboard", x, y, w);
        int wide = (int) ((w - 4) / 0.75F);
        if (!t.getBoolean("live")) {
            TaxesPage.small(g, font, Ui.clip(font, t.getString("state"), wide), x + 2, y + 12, UiStyle.INFO);
            return null;
        }
        List<Component> tip = null;
        int cy = y + 12;
        int ph = Math.max(0, Math.min(3, t.getInt("phaseN")));
        int bw = UiStyle.badge(g, font, capital(t.getString("phase")), x + 2, cy, PHASE_FILL[ph]);
        TaxesPage.small(g, font, Ui.clip(font, t.getString("times"), (int) ((w - bw - 10) / 0.75F)), x + bw + 6, cy + 3, PHASE_INK[ph]);
        cy += 14;
        // The charts.
        ListTag charts = t.getList("charts", Tag.TAG_COMPOUND);
        int cols = w >= 360 ? 4 : w >= 240 ? 3 : 2;
        int gap = 4, cellW = (w - gap * (cols - 1)) / cols, chartH = 22, cellH = chartH + 10;
        for (int i = 0; i < charts.size(); i++) {
            CompoundTag c = charts.getCompound(i);
            int cx = x + (i % cols) * (cellW + gap), top = cy + (i / cols) * (cellH + 2);
            int[] v = c.getIntArray("v");
            String unit = c.getString("unit");
            String now = v.length == 0 ? "-" : fmt(v[v.length - 1], unit);
            TaxesPage.small(g, font, Ui.clip(font, c.getString("title") + " " + now, (int) (cellW / 0.75F)), cx + 1, top, UiStyle.TEXT_MUTED);
            int colour = colourFor(c.getString("key"), v);
            UiStyle.lineChart(g, font, cx, top + 8, cellW, chartH, v.length > 60 ? java.util.Arrays.copyOfRange(v, v.length - 60, v.length) : v, colour);
            if (mx >= cx && mx < cx + cellW && my >= top && my < top + cellH && v.length > 0) {
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int n : v) { lo = Math.min(lo, n); hi = Math.max(hi, n); }
                tip = new ArrayList<>();
                tip.add(Component.literal(c.getString("title") + ": the last " + v.length + (v.length == 1 ? " morning" : " mornings")));
                tip.add(Component.literal("Today " + now + "; lowest " + fmt(lo, unit) + ", highest " + fmt(hi, unit)));
                if (v.length > 7) tip.add(Component.literal("A week ago " + fmt(v[v.length - 8], unit)));
            }
        }
        cy += ((charts.size() + cols - 1) / cols) * (cellH + 2);
        // The phase, morning by morning.
        int[] phases = t.getIntArray("phases");
        if (phases.length > 0) {
            int n = Math.min(60, phases.length), from = phases.length - n;
            TaxesPage.small(g, font, "The times, morning by morning:", x + 2, cy, UiStyle.TEXT_FAINT);
            int sx = x + 2 + (int) (font.width("The times, morning by morning: ") * 0.75F), sw = Math.max(20, x + w - sx - 2);
            for (int i = 0; i < n; i++) {
                int p = Math.max(0, Math.min(3, phases[from + i]));
                int a = sx + i * sw / n, b = sx + (i + 1) * sw / n;
                g.fill(a, cy, Math.max(a + 1, b), cy + 6, PHASE_FILL[p]);
            }
            if (my >= cy && my < cy + 7 && mx >= sx && mx < sx + sw) {
                tip = new ArrayList<>();
                tip.add(Component.literal("Green a boom, amber slowing, red a slump, blue recovering"));
            }
            cy += 10;
        }
        // The words, scrolled.
        List<String[]> rows = new ArrayList<>();
        for (Tag e : t.getList("lines", Tag.TAG_STRING)) rows.add(new String[]{ e.getAsString(), "" });
        String[][] parts = { { "inGoods", "Bought in this week, by ware" }, { "inPartners", "Bought in, by town" }, { "outGoods", "Sold out this week, by ware" },
            { "outPartners", "Sold out, by town" } };
        for (String[] pt : parts) {
            ListTag l = t.getList(pt[0], Tag.TAG_COMPOUND);
            if (l.isEmpty()) continue;
            List<String> bits = new ArrayList<>();
            for (Tag e : l) bits.add(((CompoundTag) e).getString("name") + " " + p(((CompoundTag) e).getLong("p")));
            rows.add(new String[]{ pt[1] + ": " + String.join(", ", bits), "trade" });
        }
        if (!t.getString("shocks").isEmpty()) rows.add(new String[]{ "Shocks: " + t.getString("shocks"), "bad" });
        int bottom = y + h - 6;
        for (int i = Math.max(0, Math.min(scroll, rows.size() - 1)); i < rows.size() && cy < bottom; i++) {
            String[] r = rows.get(i);
            int colour = r[1].equals("bad") ? Ui.BAD : r[1].equals("trade") ? Ui.GOOD : Ui.MUTED;
            for (net.minecraft.util.FormattedCharSequence line : font.split(Component.literal(r[0]), wide)) {
                if (cy >= bottom) break;
                TaxesPage.small(g, font, line, x + 2, cy, colour);
                cy += 8;
            }
        }
        return tip;
    }

    static int colourFor(String key, int[] v) {
        return switch (key) {
            case "cpi", "rate" -> UiStyle.WARNING_FILL;
            case "unemp", "debt" -> UiStyle.DANGER_FILL;
            case "treasury", "money", "tradeIn", "tradeOut" -> UiStyle.INFO_FILL;
            default -> UiStyle.SUCCESS_FILL;
        };
    }

    static String fmt(int v, String unit) {
        return switch (unit) {
            case "/100" -> String.format(Locale.ROOT, "%.1f", v / 100.0);
            case "/100c" -> String.format(Locale.ROOT, "%.2fc", v / 100.0);
            case "/10%" -> String.format(Locale.ROOT, "%.1f%%", v / 10.0);
            case "bp" -> String.format(Locale.ROOT, "%+.2f%%", v / 100.0);
            case "c" -> UiStyle.number(v) + "c";
            default -> UiStyle.number(v);
        };
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String p(long pence) {
        return String.format(Locale.ROOT, "%,.2fc", pence / 100.0);
    }
}
