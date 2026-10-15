package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * [business-books] The Businesses page of the town's books (CityScreen): every business in the town, a line each: who
 * owns it, how many work there, what it took this week and what it made or lost, its till; a click opens its books
 * (BusinessScreen). Under them, the town's coin counted to the penny (CoinCensus).
 */
public final class BusinessesPage {

    private BusinessesPage() {}

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag t, int x, int y, int w, int h, int scroll, int mx, int my,
                                       EarningsPage.Clicks clicks) {
        Ui.section(g, font, "The town's businesses", x, y, w);
        int cy = y + 12;
        ListTag rows = t.getList("rows", Tag.TAG_COMPOUND);
        if (rows.isEmpty()) {
            TaxesPage.small(g, font, "None yet: a café, a shop, a tavern or a smithy built opens the first, owned by whoever puts up the money.", x + 2, cy, Ui.FAINT);
            return null;
        }
        int[] cols = { 0, w * 30 / 100, w * 55 / 100, w * 64 / 100, w * 76 / 100, w * 88 / 100 };
        String[] heads = { "Business", "Owner", "Staff", "Week took", "Week net", "Till" };
        for (int i = 0; i < heads.length; i++) TaxesPage.small(g, font, heads[i], x + 2 + cols[i], cy, Ui.FAINT);
        cy += 10;
        List<Component> tip = null;
        for (int i = Math.max(0, scroll); i < rows.size() && cy < y + h - 22; i++) {
            CompoundTag r = rows.getCompound(i);
            boolean over = mx >= x && mx < x + w && my >= cy - 1 && my < cy + 9;
            if (over) {
                g.fill(x, cy - 1, x + w, cy + 8, UiStyle.TILE_HOVER);
                tip = List.of(Component.literal(r.getString("name") + ": " + r.getString("type")),
                    Component.literal("Since it opened: took " + p(r.getLong("revAll")) + ", net " + p(r.getLong("netAll"))),
                    Component.literal("Click for its books"));
            }
            TaxesPage.small(g, font, Ui.clip(font, r.getString("name") + (r.getBoolean("open") ? "" : " (closed)"), (int) ((cols[1] - 4) / 0.75F)), x + 2, cy, Ui.INK);
            TaxesPage.small(g, font, Ui.clip(font, r.getBoolean("town") ? "the town" : r.getString("owner"), (int) ((cols[2] - cols[1] - 4) / 0.75F)), x + 2 + cols[1], cy,
                r.getBoolean("town") ? UiStyle.INFO : Ui.MUTED);
            TaxesPage.small(g, font, Integer.toString(r.getInt("staff")), x + 2 + cols[2], cy, Ui.MUTED);
            TaxesPage.small(g, font, p(r.getLong("rev7")), x + 2 + cols[3], cy, Ui.INK);
            long net = r.getLong("net7");
            TaxesPage.small(g, font, p(net), x + 2 + cols[4], cy, net >= 0 ? Ui.GOOD : Ui.BAD);
            TaxesPage.small(g, font, r.getBoolean("mirror") ? "own books" : p(r.getLong("till")), x + 2 + cols[5], cy, Ui.MUTED);
            String id = r.getString("id");
            clicks.add(x, cy - 1, x + w, cy + 9, () -> {
                var mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.connection.sendCommand("village business books " + id);
                UiStyle.click();
            });
            cy += 10;
        }
        String census = t.getString("census");
        if (!census.isEmpty()) TaxesPage.small(g, font, Ui.clip(font, "The town's coin: " + census, (int) ((w - 4) / 0.75F)), x + 2, y + h - 10, Ui.FAINT);
        return tip;
    }

    private static String p(long pence) {
        return String.format(Locale.ROOT, "%,.2fc", pence / 100.0);
    }
}
