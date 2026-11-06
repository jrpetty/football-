package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [civic-land] The Knights page of the town's books (CityScreen): on the left your own service to the town, a bar to
 * the hundred a knighthood wants and what it is made of (quests, battles, gifts, good days, taxes paid, and what was
 * taken off for offences and musters missed), and where you stand: chosen, a knight (since when, your manor, your
 * retinue man by man, your vote on the taxes), or what you still want. A knight's buttons: the vote (lower, keep,
 * raise), the retinue (call, stand down), how it is addressed (Sir, Dame, Knight); anybody owing taxes, Pay. On the
 * right, the town's knights (their manors, retinues and votes), the one it has chosen, and those it has stripped.
 * The buttons say what they do as a command (/village knight ..., /village taxes pay), so they work as typed too.
 */
public final class KnightsPage {

    private KnightsPage() {}

    static final int GOLD = 0xFFC9A227, DONE = 0xFF4E9A3A;

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag realm, int x, int y, int w, int h, int scroll, int mx, int my,
                                       EarningsPage.Clicks clicks) {
        CompoundTag you = realm.getCompound("you"), town = realm.getCompound("knights");
        List<Component> tip = null;
        int lw = Math.max(170, w * 52 / 100), rx = x + lw + 8, rw = w - lw - 8;
        int cy = y;
        String name = realm.getString("town");
        // ------------------------------------------------ your service
        section(g, font, "Your service to " + name, x, cy, lw);
        cy += 12;
        if (you.isEmpty()) {
            TaxesPage.small(g, font, "Your own service shows when you open the books at the board, or with /village knight book.", x + 2, cy, Ui.FAINT);
            cy += 10;
        }
        int service = you.getInt("service"), needed = Math.max(1, you.getInt("needed"));
        boolean knight = you.getBoolean("knight");
        Ui.bar(g, x + 2, cy, lw - 4, 8, Math.min(1F, service / (float) needed), knight || service >= needed ? DONE : GOLD);
        TaxesPage.small(g, font, service + " / " + needed, x + lw / 2 - 10, cy + 1, 0xFFFFFFFF);
        if (mx >= x && mx < x + lw && my >= cy && my < cy + 8) {
            tip = List.of(Component.literal("Service: " + service + " of the " + needed + " a knighthood wants"),
                Component.literal("12 a quest done for the town, 20 a campaign in its army (8 for hire)"),
                Component.literal("1 or 2 a gift its folk were glad of (3 a day), 1 a day a citizen in good standing (2 honoured)"),
                Component.literal("2 for taxes paid in full; 25 off for an offence, 30 for a muster missed"));
        }
        cy += 11;
        String parts = "Quests " + you.getInt("quests") + " · battles " + you.getInt("battles") + " · gifts " + you.getInt("gifts") + " · days "
            + you.getInt("days") + " · taxes " + you.getInt("taxes") + (you.getInt("crimes") > 0 ? " · offences " + you.getInt("crimes") : "")
            + (you.getInt("shirks") > 0 ? " · musters missed " + you.getInt("shirks") : "");
        TaxesPage.small(g, font, Ui.clip(font, parts, (int) ((lw - 4) / 0.75F)), x + 2, cy, Ui.MUTED);
        cy += 10;
        List<String[]> body = new ArrayList<>();
        if (knight) {
            body.add(new String[]{ "G", "You are " + you.getString("style") + " " + Minecraft.getInstance().getUser().getName() + ", Knight of " + name
                + ", since day " + you.getLong("since") + "." });
            body.add(new String[]{ "L", you.getString("manor") });
            ListTag men = you.getList("retinue", Tag.TAG_STRING);
            if (men.isEmpty()) body.add(new String[]{ "L", "No retinue yet: the watch keeps two at home and can spare nobody." });
            else for (int i = 0; i < men.size(); i++) body.add(new String[]{ "L", "Retinue: " + men.getString(i) });
            body.add(new String[]{ "L", "Your vote on the taxes: " + you.getString("vote") + "." });
            if (you.getBoolean("muster")) body.add(new String[]{ "R", "The town calls you to muster: join its army (/village war join)." });
        } else if (you.getBoolean("chosen")) {
            body.add(new String[]{ "G", "The council has chosen you for knighthood: come to the hall at dusk." });
        } else if (!you.getBoolean("citizen")) {
            body.add(new String[]{ "L", "Only a citizen of the town is knighted by it: ask any folk if you may live here." });
        } else if (!you.getString("why").isEmpty()) {
            body.add(new String[]{ "L", "Not yet: " + you.getString("why") + "." });
        }
        if (you.getInt("owed") > 0) body.add(new String[]{ "R", "You owe the town " + you.getInt("owed") + " coins in taxes." });
        ListTag fallen = you.getList("fallen", Tag.TAG_STRING);
        for (int i = 0; i < fallen.size(); i++) body.add(new String[]{ "M", "Once " + fallen.getString(i) + "." });
        int max = (int) ((lw - 4) / 0.75F);
        int buttonsAt = y + h - (knight ? 44 : you.getBoolean("say") ? 30 : 16);
        for (String[] b : body) {
            for (FormattedCharSequence p : TextCache.splitPlain(font, (b[0].equals("L") ? "· " : "") + b[1], max)) {
                if (cy > buttonsAt - 9) break;
                int c = b[0].equals("R") ? TaxesPage.ANGRY : b[0].equals("G") ? Ui.GOOD : b[0].equals("M") ? Ui.FAINT : Ui.MUTED;
                TaxesPage.small(g, font, p, x + 2, cy, c);
                cy += 8;
            }
        }
        // ------------------------------------------------ the buttons
        int by = Math.max(cy + 2, buttonsAt);
        if (knight || you.getBoolean("say")) {
            int bx = x + 2;
            TaxesPage.small(g, font, "Vote:", bx, by + 3, Ui.MUTED);
            bx += 24;
            for (String v : new String[]{ "lower", "keep", "raise" }) bx = button(g, font, v, bx, by, v.equals(you.getString("vote")), mx, my, clicks,
                "village knight vote " + v) + 3;
            by += 14;
        }
        if (knight) {
            int bx = x + 2;
            bx = button(g, font, "Call retinue", bx, by, false, mx, my, clicks, "village knight retinue call") + 3;
            bx = button(g, font, "Stand down", bx, by, false, mx, my, clicks, "village knight retinue dismiss") + 6;
            for (String s : new String[]{ "Sir", "Dame", "Knight" }) bx = button(g, font, s, bx, by, s.equals(you.getString("style")), mx, my, clicks,
                "village knight style " + s.toLowerCase(java.util.Locale.ROOT)) + 3;
            by += 14;
        }
        if (you.getInt("owed") > 0) button(g, font, "Pay " + you.getInt("owed") + " coins of taxes", x + 2, by, false, mx, my, clicks, "village taxes pay");
        // ------------------------------------------------ the town's knights
        int ry = y;
        ListTag list = town.getList("knights", Tag.TAG_COMPOUND);
        section(g, font, "The knights of " + name + " (" + list.size() + ")", rx, ry, rw);
        ry += 12;
        if (list.isEmpty()) {
            TaxesPage.small(g, font, "None yet. A citizen who serves the town well is knighted at the hall.", rx + 2, ry, Ui.FAINT);
            ry += 10;
        }
        for (int i = 0; i < list.size() && ry < y + h - 40; i++) {
            CompoundTag k = list.getCompound(i);
            if (i % 2 == 0) g.fill(rx, ry - 1, rx + rw, ry + 17, Ui.ROW);
            TaxesPage.small(g, font, Ui.clip(font, k.getString("name") + " · since day " + k.getLong("since"), (int) ((rw - 4) / 0.75F)), rx + 3, ry, Ui.INK);
            TaxesPage.small(g, font, Ui.clip(font, (k.getString("manor").isEmpty() ? "no land yet" : k.getString("manor")) + " · retinue "
                + k.getInt("retinue") + " · votes to " + k.getString("vote") + " the taxes", (int) ((rw - 4) / 0.75F)), rx + 3, ry + 8, Ui.FAINT);
            if (mx >= rx && mx < rx + rw && my >= ry - 1 && my < ry + 17) {
                tip = List.of(Component.literal(k.getString("name")), Component.literal("Knighted on day " + k.getLong("since") + "; service " + k.getInt("service")),
                    Component.literal(k.getString("manor").isEmpty() ? "No land yet" : CivicLandWords.cap(k.getString("manor"))),
                    Component.literal(k.getInt("retinue") + " of the watch in the retinue; votes to " + k.getString("vote") + " the taxes"));
            }
            ry += 19;
        }
        List<String[]> more = new ArrayList<>();
        ListTag chosen = town.getList("chosen", Tag.TAG_STRING);
        for (int i = 0; i < chosen.size(); i++) more.add(new String[]{ "G", "Chosen for knighthood: " + chosen.getString(i) + "." });
        ListTag former = town.getList("former", Tag.TAG_STRING);
        for (int i = 0; i < former.size(); i++) more.add(new String[]{ "R", "Stripped: " + former.getString(i) + "." });
        more.add(new String[]{ "M", "The watch can lend knights " + town.getInt("spare") + " more " + (town.getInt("spare") == 1 ? "man" : "men")
            + " (it keeps two at home)." });
        more.add(new String[]{ "M", "A knight sits on the council (its vote counts on the taxes and on what is built), has land and a manor at the "
            + "town's edge and a retinue of the watch; it answers the muster in war, pays a fee each tax day, and loses the title for a crime." });
        int rmax = (int) ((rw - 4) / 0.75F), skip = Math.max(0, scroll), line = 0;
        for (String[] b : more) {
            for (FormattedCharSequence p : TextCache.splitPlain(font, b[1], rmax)) {
                if (line++ < skip) continue;
                if (ry > y + h - 8) break;
                TaxesPage.small(g, font, p, rx + 2, ry, b[0].equals("R") ? TaxesPage.ANGRY : b[0].equals("G") ? Ui.GOOD : Ui.FAINT);
                ry += 8;
            }
        }
        return tip;
    }

    /** A small button starting at {@code x}; clicked, it says its command (and the books are fetched again). Returns its right edge. */
    private static int button(GuiGraphics g, Font font, String label, int x, int y, boolean chosen, int mx, int my, EarningsPage.Clicks clicks, String command) {
        int w = font.width(label) + 8;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 12;
        UiStyle.button(g, font, label, x, y, w, 12, UiStyle.State.of(true, chosen, over));
        clicks.add(x, y, x + w, y + 12, () -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.connection.sendCommand(command);
            UiStyle.click();
            CityScreen.request();
        });
        return x + w;
    }

    private static void section(GuiGraphics g, Font font, String label, int x, int y, int w) {
        String cut = label;
        while (cut.length() > 4 && font.width(cut.toUpperCase()) > w - 12) cut = cut.substring(0, cut.length() - 1);
        Ui.section(g, font, cut.length() < label.length() ? cut.trim() + "…" : label, x, y, w);
    }

    /** Small words the page needs. */
    static final class CivicLandWords {
        static String cap(String s) {
            return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }
}
