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
 * [econ-1a] The Market page of the town's books (CityScreen): the storehouse as the town's market. Where it stands (the
 * founders' commons, or open, its spread and its cash), what it pays and asks for the goods most traded today, how many
 * it holds, what it bought and sold of each today; the week's trade, its store credit and what it is owed; the
 * privatisation programme's progress; and the town's coin counted to the penny.
 */
public final class MarketPage {

    private MarketPage() {}

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag t, int x, int y, int w, int h, int scroll, int mx, int my) {
        Ui.section(g, font, "The market (the storehouse)", x, y, w);
        int cy = y + 12;
        int wide = (int) ((w - 4) / 0.75F);
        for (var line : font.split(Component.literal(t.getString("state")), wide)) {
            TaxesPage.small(g, font, line, x + 2, cy, t.getBoolean("live") ? Ui.INK : UiStyle.INFO);
            cy += 8;
        }
        if (!t.getBoolean("live")) {
            cy += 2;
            for (String s : strings(t, "programme")) { TaxesPage.small(g, font, Ui.clip(font, s, wide), x + 2, cy, Ui.MUTED); cy += 8; }
            return null;
        }
        TaxesPage.small(g, font, Ui.clip(font, "This week: bought " + p(t.getLong("week_bought")) + " of the producers, sold " + p(t.getLong("week_sold"))
            + " (exports " + p(t.getLong("week_exports")) + "), staff " + p(t.getLong("week_staff")) + ". Today: bought " + p(t.getLong("today_bought"))
            + ", sold " + p(t.getLong("today_sold")) + ".", wide), x + 2, cy, Ui.MUTED);
        cy += 8;
        String owes = "Owes producers " + p(t.getLong("credit")) + " (store credit); owed " + p(t.getLong("owed"))
            + " by folk and businesses, and " + p(t.getLong("debts")) + " by the town itself on IOUs; the treasury's float " + p(t.getLong("advanced")) + ".";
        boolean overOwes = mx >= x && mx < x + w && my >= cy - 1 && my < cy + 8;
        TaxesPage.small(g, font, Ui.clip(font, owes, wide), x + 2, cy, Ui.MUTED);
        cy += 11;
        int[] cols = { 0, w * 34 / 100, w * 48 / 100, w * 62 / 100, w * 76 / 100, w * 88 / 100 };
        String[] heads = { "Good", "Bid", "Ask", "Stock", "Bought", "Sold" };
        for (int i = 0; i < heads.length; i++) TaxesPage.small(g, font, heads[i], x + 2 + cols[i], cy, Ui.FAINT);
        cy += 10;
        List<Component> tip = null;
        ListTag rows = t.getList("rows", Tag.TAG_COMPOUND);
        int bottom = y + h - 46 - 9;                                         // [ui29] the rows stop above the notes under them
        for (int i = Math.max(0, scroll); i < rows.size() && cy <= bottom; i++) {
            CompoundTag r = rows.getCompound(i);
            if (mx >= x && mx < x + w && my >= cy - 1 && my < cy + 9) {
                g.fill(x, cy - 1, x + w, cy + 8, UiStyle.TILE_HOVER);
                tip = List.of(Component.literal(r.getString("name") + ": the market pays " + c(r.getDouble("bid")) + ", asks " + c(r.getDouble("ask"))),
                    Component.literal(r.getInt("stock") + " in the stores; today bought " + r.getLong("bq") + ", sold " + r.getLong("sq")));
            }
            TaxesPage.small(g, font, Ui.clip(font, r.getString("name"), (int) ((cols[1] - 4) / 0.75F)), x + 2, cy, Ui.INK);
            TaxesPage.small(g, font, c(r.getDouble("bid")), x + 2 + cols[1], cy, Ui.GOOD);
            TaxesPage.small(g, font, c(r.getDouble("ask")), x + 2 + cols[2], cy, Ui.BAD);
            TaxesPage.small(g, font, Integer.toString(r.getInt("stock")), x + 2 + cols[3], cy, Ui.MUTED);
            TaxesPage.small(g, font, Long.toString(r.getLong("bq")), x + 2 + cols[4], cy, Ui.MUTED);
            TaxesPage.small(g, font, Long.toString(r.getLong("sq")), x + 2 + cols[5], cy, Ui.MUTED);
            cy += 9;
        }
        int fy = y + h - 46;
        List<String> foot = new ArrayList<>(strings(t, "programme"));
        int iouLines = 0;
        if (!t.getString("owing").isEmpty()) { foot.add(0, t.getString("owing")); iouLines++; }   // [econ51] what it owes, in plain words
        if (!t.getString("budget").isEmpty()) foot.add(0, t.getString("budget"));          // [econ-1b] the town's budget
        if (!t.getString("iouWeek").isEmpty()) { foot.add(Math.min(2, foot.size()), t.getString("iouWeek")); iouLines++; }   // [econ-1d] the IOUs' week
        foot.add("The town's coin: " + t.getString("census"));
        int i = 0;
        for (String s : foot) {
            if (fy > y + h - 8) break;
            boolean iou = !t.getString("budget").isEmpty() ? i >= 1 && i <= iouLines : i < iouLines;
            if (iou && mx >= x && mx < x + w && my >= fy - 1 && my < fy + 7) {
                g.fill(x, fy - 1, x + w, fy + 7, UiStyle.TILE_HOVER);
                tip = iouTip();
            }
            TaxesPage.small(g, font, Ui.clip(font, s, wide), x + 2, fy, iou && s.contains("over it") ? Ui.BAD : Ui.FAINT);
            fy += 8;
            i++;
        }
        if (overOwes && tip == null) tip = iouTip();
        return tip;
    }

    /**
     * [econ51] What an IOU is, for the mouse over the lines that count them (here and the Capital page): a player read
     * "IOUs 367.23c of the 78.24c it can carry: short" and could make nothing of it.
     */
    static List<Component> iouTip() {
        return List.of(
            Component.literal("What an IOU is").withColor(0xFFE08A),
            Component.literal("When the treasury has no coin, the town's builders and works"),
            Component.literal("still take what they need from its own market, and the"),
            Component.literal("treasury leaves a note for it: an IOU. The town owes it to itself."),
            Component.literal("Taxes, rents and the market's takings pay them off first,"),
            Component.literal("oldest first. Bonds sold to pay them off count too, past a"),
            Component.literal("week's takings: a debt by another name is still a debt."),
            Component.literal("What it can carry: half a week's takings (two coins a grown"),
            Component.literal("folk at the least). Over that the builders get part of each"),
            Component.literal("day's money and the rest pays the debt down. Slower, never stopped."));
    }

    private static List<String> strings(CompoundTag t, String key) {
        List<String> out = new ArrayList<>();
        for (Tag e : t.getList(key, Tag.TAG_STRING)) out.add(e.getAsString());
        return out;
    }

    private static String p(long pence) {
        return String.format(Locale.ROOT, "%,.2fc", pence / 100.0);
    }

    private static String c(double coin) {
        return String.format(Locale.ROOT, "%.2fc", coin);
    }
}
