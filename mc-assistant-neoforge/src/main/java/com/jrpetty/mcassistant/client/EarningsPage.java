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
 * [earning] The Earnings page of the town's books (CityScreen; the server's EarnBoard writes it, asked for when the
 * page is opened and every few seconds while it is): on the left your purse, the shift or contract under way, what you
 * earned today and this week by source (a bar each, the week behind today), your stake in each town (the bank, rent
 * waiting, houses, the week's earnings there) and your job skills; on the right the ways to earn in the town you stand
 * in, each with its reward, how far and by when: Marker sets a marker on it (the HUD's arrow and a column of sparks),
 * Take and Every day take an open shift, Accept a haul contract. Rested buttons say why in their tooltip.
 */
public final class EarningsPage {

    private EarningsPage() {}

    /** A click area on the page, handed to the screen. */
    public interface Clicks {
        void add(int x0, int y0, int x1, int y1, Runnable act);
    }

    static final int ROW = 24, WEEK = 0xFF9CB8D8, TODAY = 0xFF3F6FA8;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, int x, int y, int w, int h, int scroll, int mx, int my, Clicks clicks) {
        if (EarnClient.stale()) {
            EarnClient.pageAt = EarnClient.ticks;
            EarnClient.askPage();
        }
        CompoundTag p = EarnClient.page;
        if (p.isEmpty()) {
            UiStyle.empty(g, font, "Your earnings", "Fetching your purse and the work going…", x, y, w, Math.min(h, 60));
            return null;
        }
        List<Component> tip = null;
        int mid = x + w * 40 / 100;
        int lw = mid - x - 8, rx = mid, rw = x + w - mid;
        int cy = y;
        // ---- the purse
        Ui.section(g, font, "Your purse", x, cy, lw);
        cy += 11;
        String purse = p.getInt("purse") + "c";
        UiStyle.title(g, font, purse, x + 2, cy);
        int all = sum(p.getIntArray("all"));
        g.drawString(font, Ui.clip(font, "carried · " + all + "c earned in all", lw - UiStyle.titleWidth(font, purse) - 8),
            x + 6 + UiStyle.titleWidth(font, purse), cy + 2, Ui.FAINT, false);
        cy += 12;
        for (String key : new String[]{ "shift", "standing", "contract" }) {
            if (!p.contains(key)) continue;
            String label = key.equals("shift") ? "On shift: " : key.equals("standing") ? "Standing job: " : "Contract: ";
            g.drawString(font, Ui.clip(font, label + p.getString(key), lw), x + 2, cy, key.equals("shift") ? Ui.GOOD : Ui.MUTED, false);
            if (key.equals("contract") && p.contains("contractAt")) {
                int[] at = p.getIntArray("contractAt");
                clicks.add(x, cy - 1, x + lw, cy + 9, () -> { EarnClient.mark(at, "your contract"); UiStyle.click(); });
            }
            cy += 10;
        }
        cy += 3;
        // ---- earned, by source
        int[] today = p.getIntArray("today"), week = p.getIntArray("week");
        ListTag labels = p.getList("sources", Tag.TAG_STRING);
        Ui.section(g, font, "Earned: today " + sum(today) + "c · this week " + sum(week) + "c", x, cy, lw);
        cy += 11;
        int most = 1;
        for (int v : week) most = Math.max(most, v);
        int shown = 0, labelW = 70;
        for (int i = 0; i < Math.min(labels.size(), week.length); i++) {
            if (week[i] <= 0) continue;
            String l = labels.getString(i);
            g.drawString(font, Ui.clip(font, l, labelW - 2), x + 2, cy, Ui.MUTED, false);
            int bx = x + labelW, bw = lw - labelW - 34;
            g.fill(bx, cy + 1, bx + Math.max(1, bw * week[i] / most), cy + 4, WEEK);
            if (i < today.length && today[i] > 0) g.fill(bx, cy + 5, bx + Math.max(1, bw * today[i] / most), cy + 8, TODAY);
            Ui.right(g, font, week[i] + "c", x + lw, cy, Ui.INK);
            if (mx >= x && mx < x + lw && my >= cy - 1 && my < cy + 9) {
                tip = List.of(Component.literal(l + ": " + (i < today.length ? today[i] : 0) + "c today, " + week[i] + "c this week"));
            }
            cy += 10;
            shown++;
        }
        if (shown == 0) {
            g.drawString(font, Ui.clip(font, "Nothing earned this week yet: try a way on the right.", lw), x + 2, cy, Ui.FAINT, false);
            cy += 10;
        } else {
            g.fill(x + 2, cy + 1, x + 8, cy + 4, WEEK);
            g.drawString(font, "week", x + 10, cy, Ui.FAINT, false);
            g.fill(x + 40, cy + 1, x + 46, cy + 4, TODAY);
            g.drawString(font, "today", x + 48, cy, Ui.FAINT, false);
            cy += 10;
        }
        cy += 3;
        // ---- each town
        Ui.section(g, font, "In each town", x, cy, lw);
        cy += 11;
        ListTag towns = p.getList("towns", Tag.TAG_COMPOUND);
        for (int i = 0; i < towns.size() && cy < y + h - 70; i++) {
            CompoundTag t = towns.getCompound(i);
            g.drawString(font, Ui.clip(font, (t.getBoolean("here") ? "▸ " : "") + t.getString("name"), lw / 2), x + 2, cy, Ui.INK, false);
            String line = "bank " + t.getInt("bank") + "c" + (t.getInt("rent") > 0 ? " · rent " + t.getInt("rent") + "c" : "") + " · week " + t.getInt("week") + "c";
            Ui.right(g, font, Ui.clip(font, line, lw / 2 + 10), x + lw, cy, Ui.MUTED);
            if (mx >= x && mx < x + lw && my >= cy - 1 && my < cy + 9) {
                tip = List.of(Component.literal(t.getString("name")), Component.literal("In the bank: " + t.getInt("bank") + "c"),
                    Component.literal("Rent waiting: " + t.getInt("rent") + "c; houses: " + t.getInt("houses")),
                    Component.literal("Earned here: " + t.getInt("today") + "c today, " + t.getInt("week") + "c this week"),
                    Component.literal("The town's treasury: " + t.getInt("treasury") + "c"));
            }
            cy += 10;
        }
        if (towns.isEmpty()) {
            g.drawString(font, "No towns yet.", x + 2, cy, Ui.FAINT, false);
            cy += 10;
        }
        cy += 3;
        // ---- skills
        Ui.section(g, font, "Job skills", x, cy, lw);
        cy += 11;
        ListTag skills = p.getList("skills", Tag.TAG_COMPOUND);
        int col = lw / 2;
        for (int i = 0; i < skills.size(); i++) {
            CompoundTag s = skills.getCompound(i);
            int sx = x + 2 + (i % 2) * col, sy = cy + (i / 2) * 11;
            if (sy > y + h - 10) break;
            g.drawString(font, Ui.clip(font, s.getString("role"), col - 44), sx, sy, s.getInt("xp") > 0 ? Ui.INK : Ui.FAINT, false);
            Ui.bar(g, sx + col - 40, sy + 2, 32, 5, s.getFloat("progress"), Ui.ACCENT);
            if (mx >= sx && mx < sx + col && my >= sy - 1 && my < sy + 9) {
                List<Component> t = new ArrayList<>();
                t.add(Component.literal(s.getString("role") + ": " + s.getString("level") + " (" + s.getInt("xp") + " xp)"));
                t.add(Component.literal("Each level adds a tenth to pay; Skilled opens the senior post."));
                if (s.getInt("poor") > 0) t.add(Component.literal(s.getInt("poor") + " poor shift(s) in a row"));
                tip = t;
            }
        }
        // ---- the ways to earn
        String town = p.getString("town");
        Ui.section(g, font, town.isEmpty() ? "Ways to earn" : "Ways to earn in " + town, rx, y, rw);
        ListTag ways = p.getList("ways", Tag.TAG_COMPOUND);
        int top = y + 11, bottom = y + h - 2;
        int rows = Math.max(1, (bottom - top) / ROW);
        int start = Math.max(0, Math.min(scroll / 3, Math.max(0, ways.size() - rows)));
        int ry = top;
        if (ways.isEmpty()) g.drawString(font, "Stand in a town to see the work going there.", rx + 2, ry + 2, Ui.FAINT, false);
        for (int i = start; i < ways.size() && ry + ROW <= bottom; i++, ry += ROW) {
            CompoundTag wy = ways.getCompound(i);
            boolean over = mx >= rx && mx < rx + rw && my >= ry && my < ry + ROW - 1;
            g.fill(rx, ry, rx + rw, ry + ROW - 1, over ? Ui.HI : i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
            g.fill(rx, ry, rx + 2, ry + ROW - 1, colourOf(wy.getString("kind")));
            String reward = wy.getString("reward");
            int rwid = font.width(reward);
            boolean blocked = !wy.getString("why").isEmpty();
            // The buttons, right to left.
            int bx = rx + rw - 4;
            List<int[]> buttons = new ArrayList<>();
            String action = wy.getString("action");
            if (action.equals("take")) {
                bx = button(g, font, "Every day", bx, ry + 6, !blocked || wy.getString("why").startsWith("Opens"), mx, my, buttons);
                bx = button(g, font, "Take", bx, ry + 6, !blocked, mx, my, buttons);
            } else if (action.equals("contract")) {
                bx = button(g, font, "Accept", bx, ry + 6, true, mx, my, buttons);
            }
            boolean hasAt = wy.contains("at");
            if (hasAt) bx = button(g, font, "Marker", bx, ry + 6, true, mx, my, buttons);
            int textR = bx - 4;
            g.drawString(font, Ui.clip(font, wy.getString("title"), textR - rx - 10 - rwid), rx + 6, ry + 3, blocked ? Ui.FAINT : Ui.INK, false);
            g.drawString(font, reward, textR - rwid, ry + 3, blocked ? Ui.FAINT : Ui.GOOD, false);
            String sub = blocked ? wy.getString("why") : wy.getString("detail");
            String far = (wy.contains("distance") ? wy.getInt("distance") + "m" : "") + (wy.getString("deadline").isEmpty() ? "" : " · " + wy.getString("deadline"));
            g.drawString(font, Ui.clip(font, sub, textR - rx - 12 - font.width(far)), rx + 6, ry + 13, blocked ? Ui.BAD : Ui.FAINT, false);
            Ui.right(g, font, far, textR, ry + 13, Ui.FAINT);
            // The clicks: each button, and the row itself for the marker.
            final CompoundTag row = wy;
            int b = 0;
            if (action.equals("take")) {
                int[] every = buttons.get(b++), take = buttons.get(b++);
                boolean everyOk = !blocked || wy.getString("why").startsWith("Opens");
                if (everyOk) clicks.add(every[0], every[1], every[2], every[3], () -> takeShift(row, true));
                if (!blocked) clicks.add(take[0], take[1], take[2], take[3], () -> takeShift(row, false));
                if (in(mx, my, take)) tip = List.of(Component.literal(blocked ? wy.getString("why") : "Take a shift now (about four hours)."));
                if (in(mx, my, every)) tip = List.of(Component.literal("A standing job: your shift starts each day at its hours while you're in town."));
            } else if (action.equals("contract")) {
                int[] acc = buttons.get(b++);
                clicks.add(acc[0], acc[1], acc[2], acc[3], () -> {
                    CompoundTag a = new CompoundTag();
                    a.putString("id", row.getString("id"));
                    EarnClient.ask("contract", a);
                    UiStyle.click();
                });
                if (in(mx, my, acc)) tip = List.of(Component.literal("Take the contract: the fee is held in escrow till you deliver."));
            }
            if (hasAt) {
                int[] mk = buttons.get(b);
                int[] at = wy.getIntArray("at");
                clicks.add(mk[0], mk[1], mk[2], mk[3], () -> { EarnClient.mark(at, row.getString("title")); UiStyle.click(); });
                if (in(mx, my, mk)) tip = List.of(Component.literal("Set a marker: an arrow at the top of the screen and sparks over the place."));
            }
            if (over && tip == null) {
                List<Component> t = new ArrayList<>();
                t.add(Component.literal(wy.getString("title") + " — " + reward));
                t.add(Component.literal(wy.getString("detail")));
                if (blocked) t.add(Component.literal(wy.getString("why")));
                if (wy.contains("level")) t.add(Component.literal("Your skill: " + wy.getString("level")));
                tip = t;
            }
        }
        if (ways.size() > rows) {
            UiStyle.scrollBar(g, rx + rw - 3, top, bottom - top, start * ROW, (ways.size() - rows) * ROW, bottom - top);
        }
        return tip;
    }

    private static void takeShift(CompoundTag row, boolean standing) {
        CompoundTag a = new CompoundTag();
        a.putString("town", row.getString("townId"));
        a.putString("role", row.getString("role"));
        a.putBoolean("standing", standing);
        EarnClient.ask("take", a);
        UiStyle.click();
    }

    /** A small button drawn ending at {@code right}; its box kept; returns where the next one ends. */
    private static int button(GuiGraphics g, Font font, String label, int right, int y, boolean enabled, int mx, int my, List<int[]> boxes) {
        int w = font.width(label) + 8, x = right - w;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 12;
        UiStyle.button(g, font, label, x, y, w, 12, UiStyle.State.of(enabled, false, over));
        boxes.add(new int[]{ x, y, x + w, y + 12 });
        return x - 3;
    }

    private static boolean in(int mx, int my, int[] b) {
        return mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3];
    }

    private static int colourOf(String kind) {
        return switch (kind) {
            case "shift" -> 0xFF4E9A3A;
            case "wanted" -> 0xFFC08A1E;
            case "bounty" -> 0xFFC2452F;
            case "contract" -> 0xFF4A82C8;
            case "price" -> 0xFF8A6FB0;
            default -> 0xFF8B8B8B;
        };
    }

    private static int sum(int[] a) {
        int n = 0;
        for (int v : a) n += v;
        return n;
    }
}
