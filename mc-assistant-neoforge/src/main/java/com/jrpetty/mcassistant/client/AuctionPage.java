package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [fleet] The Auction page of the town's books (CityScreen), as the server's Auctions.report has it. On the left the
 * town's auction: when the next is, today's lots (where each came from, its reserve, the bid and who has it, how it went,
 * the last few bids), and the players' lots waiting for the next one; on the right the past sales. Along the foot, the
 * fishing fleet and its fish market: the boats, where they are today and who is out, the day's catch and the prices it
 * makes, and the fortnight at sea and at the market.
 */
public final class AuctionPage {

    private AuctionPage() {}

    private static final int SEA = 0xFF2F6D9E;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<Component> tip = null;
        int fleetH = Math.min(96, h / 2 - 4);
        int topH = h - fleetH - 6;
        int mid = x + w * 58 / 100;
        int lw = mid - x - 8, rw = x + w - mid;
        // ---- the auction
        int cy = y;
        Ui.section(g, font, "The auction", x, cy, lw);
        cy += 11;
        if (!m.getBoolean("holds")) {
            for (String line : wrap(font, "No auction yet: a town of " + m.getInt("from") + " folk from the Stone Age holds one on the square every market"
                    + " day — its rare finds, the merchants' curios and what players put up, to the best bid, folk and players alike.", (int) (lw / 0.75F))) {
                small(g, font, line, x + 2, cy, Ui.MUTED);
                cy += 9;
            }
        } else {
            small(g, font, Ui.clip(font, m.getString("when") + (m.getString("auctioneer").isEmpty() ? "" : " Called by " + m.getString("auctioneer") + "."),
                (int) (lw / 0.75F)), x + 2, cy, Ui.MUTED);
            cy += 10;
            ListTag lots = m.getList("lots", Tag.TAG_COMPOUND);
            if (lots.isEmpty()) {
                small(g, font, "No lots today.", x + 2, cy, Ui.FAINT);
                cy += 9;
            }
            for (int i = 0; i < lots.size() && cy < y + topH - 20; i++) {
                CompoundTag l = lots.getCompound(i);
                boolean over = mx >= x && mx < x + lw && my >= cy - 1 && my < cy + 17;
                g.fill(x, cy - 1, x + lw, cy + 17, l.getBoolean("now") ? Ui.ROW_PICK : over ? Ui.HI : i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
                icon(g, l.getString("item"), x + 1, cy, 1.0F);
                String head = (i + 1) + ". " + l.getString("name") + " — from " + l.getInt("reserve") + "c (worth " + l.getInt("worth") + "c)";
                small(g, font, Ui.clip(font, head, (int) ((lw - 22) / 0.75F)), x + 20, cy, Ui.INK);
                String state = !l.getString("result").isEmpty() ? cap(l.getString("result"))
                    : l.getInt("high") > 0 ? "bid " + l.getInt("high") + "c by " + l.getString("highName") + (l.getBoolean("now") ? " — under the hammer" : "")
                    : l.getBoolean("now") ? "under the hammer, no bids yet" : "to come";
                small(g, font, Ui.clip(font, cap(l.getString("from")) + " · " + state, (int) ((lw - 22) / 0.75F)), x + 20, cy + 8,
                    l.getString("result").startsWith("sold") ? Ui.GOOD : Ui.MUTED);
                if (over) {
                    tip = new ArrayList<>();
                    tip.add(Component.literal(l.getString("name") + ", " + l.getString("from")));
                    ListTag bids = l.getList("bids", Tag.TAG_STRING);
                    if (bids.isEmpty()) tip.add(Component.literal("No bids"));
                    for (int k = 0; k < bids.size(); k++) tip.add(Component.literal("  " + bids.getString(k)));
                }
                cy += 19;
            }
            if (m.contains("sold")) {
                small(g, font, "Sold today: " + m.getInt("sold") + " for " + m.getInt("takings") + "c, a crowd of " + m.getInt("crowd") + ".", x + 2, cy, Ui.FAINT);
                cy += 10;
            }
            ListTag waiting = m.getList("waiting", Tag.TAG_STRING);
            if (!waiting.isEmpty() && cy < y + topH - 10) {
                small(g, font, "Players' lots for the next auction:", x + 2, cy, Ui.INK);
                cy += 9;
                for (int i = 0; i < waiting.size() && cy < y + topH - 2; i++) {
                    small(g, font, Ui.clip(font, "  " + waiting.getString(i), (int) (lw / 0.75F)), x + 2, cy, Ui.MUTED);
                    cy += 9;
                }
            }
        }
        // ---- the past sales
        int ry = y;
        ListTag sales = m.getList("sales", Tag.TAG_STRING);
        int rows = Math.max(1, (topH - 12) / 9);
        int start = Math.max(0, Math.min(scroll, Math.max(0, sales.size() - rows)));
        Ui.section(g, font, "Past sales" + (sales.size() > rows ? " (" + (start + 1) + "-" + Math.min(sales.size(), start + rows) + " of " + sales.size() + ")" : ""),
            mid, ry, rw);
        ry += 11;
        if (sales.isEmpty()) small(g, font, "Nothing sold yet.", mid + 2, ry, Ui.FAINT);
        for (int i = start; i < sales.size() && i < start + rows; i++) {
            String s = sales.getString(i);
            small(g, font, Ui.clip(font, s, (int) ((rw - 4) / 0.75F)), mid + 2, ry, s.contains("no sale") ? Ui.FAINT : Ui.MUTED);
            ry += 9;
        }
        // ---- the fleet and the fish market
        CompoundTag f = m.getCompound("fleet");
        int fy = y + topH + 4;
        g.fill(x, fy - 2, x + w, fy - 1, Ui.EDGE_SOFT);
        Ui.section(g, font, "The fishing fleet and the fish market", x, fy, w);
        fy += 11;
        if (!f.getBoolean("has")) {
            small(g, font, Ui.clip(font, "No fleet: a waterside town of " + f.getInt("from") + " with a quay over open water fits out two to four boats.",
                (int) (w / 0.75F)), x + 2, fy, Ui.MUTED);
            return tip;
        }
        int half = x + w / 2;
        g.fill(x + 2, fy + 1, x + 6, fy + 5, SEA);
        small(g, font, Ui.clip(font, f.getInt("boats") + " of " + f.getInt("wanted") + " boats — " + f.getString("today"), (int) ((w / 2 - 10) / 0.75F)),
            x + 9, fy, Ui.INK);
        int ly = fy + 9;
        ListTag crew = f.getList("crew", Tag.TAG_STRING);
        for (int i = 0; i < crew.size() && ly < y + h - 9; i++) {
            small(g, font, Ui.clip(font, "  " + crew.getString(i), (int) ((w / 2 - 10) / 0.75F)), x + 2, ly, Ui.MUTED);
            ly += 9;
        }
        ListTag days = f.getList("days", Tag.TAG_STRING);
        for (int i = 0; i < days.size() && ly < y + h - 9; i++) {
            small(g, font, Ui.clip(font, days.getString(i), (int) ((w / 2 - 10) / 0.75F)), x + 2, ly, Ui.FAINT);
            ly += 9;
        }
        CompoundTag mk = f.getCompound("market");
        int my2 = fy;
        if (!mk.getBoolean("stall")) {
            small(g, font, "No fish market on the quay yet.", half, my2, Ui.MUTED);
            return tip;
        }
        String state = mk.getBoolean("open") ? "open" + (mk.getString("seller").isEmpty() ? "" : ", " + mk.getString("seller") + " selling")
            : mk.getBoolean("closed") ? "shut for the day" : "shut till the boats come in";
        small(g, font, Ui.clip(font, "Fish market: " + state, (int) ((w / 2) / 0.75F)), half, my2, mk.getBoolean("open") ? Ui.GOOD : Ui.INK);
        my2 += 9;
        small(g, font, Ui.clip(font, "Landed " + mk.getInt("landed") + ", sold " + mk.getInt("sold") + " for "
            + String.format(Locale.ROOT, "%.2fc", mk.getInt("takings100") / 100.0) + ", in the barrels " + mk.getInt("inBarrels")
            + (mk.getInt("smoked") > 0 ? ", smoked " + mk.getInt("smoked") : ""), (int) ((w / 2) / 0.75F)), half, my2, Ui.MUTED);
        my2 += 9;
        int glut = mk.getInt("glut100");
        small(g, font, Ui.clip(font, String.format(Locale.ROOT, "Cod %.2fc, salmon %.2fc: %d%% of the town's price by the catch%s",
            mk.getInt("cod100") / 100.0, mk.getInt("salmon100") / 100.0, glut, glut <= 70 ? " (a big catch)" : glut >= 115 ? " (a poor catch)" : ""),
            (int) ((w / 2) / 0.75F)), half, my2, glut <= 70 ? Ui.GOOD : glut >= 115 ? Ui.BAD : Ui.MUTED);
        my2 += 9;
        ListTag mdays = mk.getList("days", Tag.TAG_STRING);
        for (int i = 0; i < mdays.size() && my2 < y + h - 9; i++) {
            small(g, font, Ui.clip(font, mdays.getString(i), (int) ((w / 2) / 0.75F)), half, my2, Ui.FAINT);
            my2 += 9;
        }
        return tip;
    }

    private static void icon(GuiGraphics g, String id, int x, int y, float scale) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return;
        ItemStack s = new ItemStack(BuiltInRegistries.ITEM.get(rl));
        if (s.isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1F);
        g.renderItem(s, 0, 0);
        g.pose().popPose();
    }

    private static List<String> wrap(Font font, String text, int max) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.width(next) > max && !line.isEmpty()) {
                out.add(line);
                line = word;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
