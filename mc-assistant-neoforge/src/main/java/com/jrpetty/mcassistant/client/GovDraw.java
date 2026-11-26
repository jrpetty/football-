package com.jrpetty.mcassistant.client;

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
 * [featcivic] The drawing of the town's government, shared by the board's Government page (GovernmentPage) and the Town
 * Hall screen (GovScreen): one section at a time (overview, policy, election, parties, unrest, forms, relations), into a
 * box, from the server's compound (entity/GovReport). Buttons are given a callback by the caller ({@link Act}), so the
 * page and the screen each decide what a press does; every button says why when it cannot be pressed.
 */
public final class GovDraw {

    private GovDraw() {}

    public static final String[] TABS = { "Overview", "Policy", "Election", "Parties", "Unrest", "Forms", "Relations" };

    /** What a button does, given by the caller: the action and its arguments ("vote", "show"...). */
    public interface Act {
        void run(String action, CompoundTag args);
    }

    /** The drawing state of one section: where it is, the cursor, what was hovered, the click zones. */
    static final class Ctx {
        final GuiGraphics g;
        final Font font;
        final CompoundTag d;
        final int x, w, mx, my, top, bottom;
        int y;
        final EarningsPage.Clicks clicks;
        @Nullable final Act act;
        @Nullable List<Component> tip;
        final boolean screen;

        Ctx(GuiGraphics g, Font font, CompoundTag d, int x, int y, int w, int top, int bottom, int mx, int my, EarningsPage.Clicks clicks,
            @Nullable Act act, boolean screen) {
            this.g = g; this.font = font; this.d = d; this.x = x; this.y = y; this.w = w; this.top = top; this.bottom = bottom;
            this.mx = mx; this.my = my; this.clicks = clicks; this.act = act; this.screen = screen;
        }

        boolean visible(int h) { return y + h >= top && y <= bottom; }

        boolean over(int x0, int y0, int ww, int hh) { return mx >= x0 && mx < x0 + ww && my >= y0 && my < y0 + hh && my >= top && my <= bottom; }

        void heading(String s) {
            y += 3;
            if (visible(10)) UiStyle.heading(g, font, s, x, y, w);
            y += 13;
        }

        void line(String s, int colour) {
            if (visible(10)) UiStyle.text(g, font, s, x, y, w, colour);
            if (font.width(s) > w && over(x, y, w, 10)) tip(s);
            y += 11;
        }

        /** Wrapped text, every line of it. */
        void wrap(String s, int colour) {
            wrap(s, colour, x, w);
        }

        void wrap(String s, int colour, int xx, int ww) {
            for (FormattedCharSequence l : TextCache.split(font, s, Math.max(20, ww))) {
                if (visible(10)) g.drawString(font, l, xx, y, colour, false);
                y += 10;
            }
        }

        /** "Label  value", the label muted, the value wrapped beside it. */
        void pair(String label, String value) {
            int lw = Math.min(78, w / 3);
            if (visible(10)) g.drawString(font, UiStyle.clip(font, label, lw - 4), x, y, UiStyle.TEXT_FAINT, false);
            int before = y;
            wrap(value, UiStyle.TEXT, x + lw, w - lw);
            if (y == before) y += 10;
            y += 1;
        }

        void tip(String... lines) {
            tip = new ArrayList<>();
            for (String l : lines) if (l != null && !l.isEmpty()) tip.add(Component.literal(l));
        }

        /** A button at (bx, y): enabled or not (and why not), its action. Returns its width. */
        int button(String label, int bx, int by, boolean enabled, String why, String action, CompoundTag args) {
            int bw = font.width(label) + 12;
            boolean hover = over(bx, by, bw, 14);
            if (by + 14 >= top && by <= bottom) UiStyle.button(g, font, label, bx, by, bw, 14, UiStyle.State.of(enabled && act != null, false, hover));
            if (hover) tip(enabled && act != null ? why : (act == null ? "Open the Town Hall (the ballot box, or this page's button) to do this." : why));
            if (enabled && act != null && by + 14 >= top && by <= bottom) {
                clicks.add(bx, by, bx + bw, by + 14, () -> {
                    UiStyle.click();
                    act.run(action, args);
                });
            }
            return bw;
        }
    }

    static int rgb(int c) {
        return 0xFF000000 | (c & 0xFFFFFF);
    }

    static CompoundTag args(String... kv) {
        CompoundTag t = new CompoundTag();
        for (int i = 0; i + 1 < kv.length; i += 2) t.putString(kv[i], kv[i + 1]);
        return t;
    }

    static List<String> strings(CompoundTag t, String key) {
        List<String> out = new ArrayList<>();
        ListTag l = t.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    /**
     * One section into the box (x, y, w, h), scrolled up by {@code offset} pixels. Returns {height of the content, and the
     * tooltip via the holder}.
     */
    public static int section(GuiGraphics g, Font font, CompoundTag d, String tab, int x, int y, int w, int h, int offset, int mx, int my,
                              EarningsPage.Clicks clicks, @Nullable Act act, boolean screen, List<Component> tipOut) {
        Ctx c = new Ctx(g, font, d, x, y - offset, w, y, y + h, mx, my, clicks, act, screen);
        int start = c.y;
        if (d.getString("form").isEmpty()) {
            UiStyle.empty(g, font, "Not settled yet", d.getString("pending"), x, y, w, h);
            return 0;
        }
        switch (tab) {
            case "Policy" -> policy(c);
            case "Election" -> election(c);
            case "Parties" -> parties(c);
            case "Unrest" -> unrest(c);
            case "Forms" -> forms(c);
            case "Relations" -> relations(c);
            case "Ballot" -> ballot(c);
            default -> overview(c);
        }
        if (c.tip != null) tipOut.addAll(c.tip);
        return c.y - start;
    }

    // ------------------------------------------------------------------ overview

    static void overview(Ctx c) {
        CompoundTag d = c.d, rules = d.getCompound("rules");
        int col = rgb(d.getInt("colour"));
        if (c.visible(20)) {
            c.g.fill(c.x, c.y, c.x + 4, c.y + 20, col);
            UiStyle.title(c.g, c.font, UiStyle.clip(c.font, d.getString("formWords"), c.w - 120), c.x + 8, c.y);
            String b = d.getBoolean("elected") ? "Elected" : "Not elected";
            UiStyle.badge(c.g, c.font, b, c.x + c.w - c.font.width(b) - 8, c.y, d.getBoolean("elected") ? UiStyle.SUCCESS_FILL : UiStyle.WARNING_FILL);
            String sub = d.getString("phrase") + (d.getLong("since") >= 0 ? " · since day " + (d.getLong("since") + 1) : "");
            UiStyle.text(c.g, c.font, sub, c.x + 8, c.y + 11, c.w - 12, UiStyle.TEXT_MUTED);
        }
        c.y += 24;
        c.wrap(d.getString("how"), UiStyle.TEXT);
        if (d.contains("lastChange")) {
            String[] p = d.getString("lastChange").split("\\|", -1);
            if (p.length >= 4) c.wrap("Day " + (Long.parseLong(p[0]) + 1) + ": " + (p[1].equals("coup") ? "a coup made it " : "a revolution made it ")
                + p[3].toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + (p.length > 4 && !p[4].isEmpty() ? "; " + p[4] + " left office" : "") + ".", UiStyle.DANGER);
        }
        c.heading("How it works here");
        c.pair("Who owns", rules.getString("ownership"));
        c.pair("Business", rules.getString("business"));
        c.pair("Homes", rules.getString("homes"));
        c.pair("The vote", rules.getString("vote"));
        c.pair("Speech", rules.getString("speech"));
        c.pair("Market", rules.getString("market"));
        c.pair("Wages", rules.getString("wages"));
        c.pair("Taxes", rules.getString("taxes"));
        c.pair("Laws", rules.getString("laws"));
        c.heading("Good at, and bad at");
        int half = (c.w - 8) / 2, y0 = c.y, yl, yr;
        for (String s : strings(rules, "strengths")) c.wrap("+ " + s, UiStyle.SUCCESS, c.x, half);
        yl = c.y;
        c.y = y0;
        for (String s : strings(rules, "weaknesses")) c.wrap("- " + s, UiStyle.DANGER, c.x + half + 8, half);
        yr = c.y;
        c.y = Math.max(yl, yr);
        CompoundTag you = d.getCompound("you");
        if (you.contains("can")) {
            c.heading("What you can do here");
            for (String s : strings(you, "can")) c.wrap("✔ " + s, UiStyle.SUCCESS);
            for (String s : strings(you, "cannot")) c.wrap("✖ " + s, UiStyle.DANGER);
        } else {
            c.heading("What a player can do here");
            for (String s : strings(rules, "player")) c.wrap("• " + s, UiStyle.TEXT);
        }
        CompoundTag u = d.getCompound("unrest");
        c.heading("Unrest");
        meter(c, u.getInt("level"), u.getString("stage"));
    }

    // ------------------------------------------------------------------ policy

    static void policy(Ctx c) {
        CompoundTag p = c.d.getCompound("policy");
        if (p.contains("who")) {
            c.line(c.d.getString("title") + " " + p.getString("who") + "'s policy" + (p.contains("since") ? ", since day " + (p.getLong("since") + 1) : "")
                + " · " + p.getString("style"), UiStyle.TEXT);
            int cw = (c.w - 16) / 3, y0 = c.y + 2;
            card(c, c.x, y0, cw, "TAXES", Policy(p.getString("taxWords")), p.getString("taxWhy"), p.getInt("tax") > 0 ? UiStyle.WARNING_FILL : p.getInt("tax") < 0 ? UiStyle.SUCCESS_FILL : UiStyle.INFO_FILL);
            card(c, c.x + cw + 8, y0, cw, "BUILD FIRST", Policy(p.getString("build")), p.getString("buildWhy"), UiStyle.INFO_FILL);
            card(c, c.x + 2 * (cw + 8), y0, cw, "WAGES", Policy(p.getString("wageWords")), p.getString("wageWhy"), p.getInt("wage") > 0 ? UiStyle.SUCCESS_FILL : p.getInt("wage") < 0 ? UiStyle.WARNING_FILL : UiStyle.INFO_FILL);
            c.y = y0 + 52;
            c.wrap("The policy is the leader's own: what it cares for and its nature" + ("DEMOCRACY".equals(c.d.getString("form")) ? ", and its party's line" : "")
                + ". It leans the council's tax votes, counts twice in what is built next, and sets how generously the wages are paid.", UiStyle.TEXT_FAINT);
        } else if (p.contains("player")) {
            c.wrap(p.getString("player") + " (a player) leads: the policy is theirs, set from the Leader's page.", UiStyle.TEXT);
        } else {
            c.wrap("Nobody holds office just now, so there is no policy beyond the council's.", UiStyle.TEXT_MUTED);
        }
        c.heading("What it means now");
        if (!p.getString("order").isEmpty()) c.pair("Standing order", p.getString("order"));
        if (!p.getString("buildingNext").isEmpty()) c.pair("Going up next", p.getString("buildingNext"));
        if (!p.getString("taxes").isEmpty()) c.pair("Taxes", p.getString("taxes"));
        if (!p.getString("laws").isEmpty()) c.pair("The law", p.getString("laws"));
        List<String> hist = strings(p, "history");
        if (!hist.isEmpty()) {
            c.heading("Policies set out");
            for (int i = hist.size() - 1; i >= 0; i--) c.wrap(hist.get(i), UiStyle.TEXT_MUTED);
        }
    }

    private static String Policy(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static void card(Ctx c, int x, int y, int w, String head, String big, String why, int spine) {
        if (y + 50 < c.top || y > c.bottom) return;
        UiStyle.card(c.g, x, y, w, 48, spine);
        c.g.drawString(c.font, head, x + 6, y + 4, UiStyle.TEXT_FAINT, false);
        UiStyle.title(c.g, c.font, UiStyle.clip(c.font, big, w - 10), x + 6, y + 14);
        List<FormattedCharSequence> l = TextCache.split(c.font, why, w - 10);
        for (int i = 0; i < Math.min(2, l.size()); i++) c.g.drawString(c.font, l.get(i), x + 6, y + 26 + i * 10, UiStyle.TEXT_MUTED, false);
        if (c.over(x, y, w, 48)) c.tip(head + ": " + big, why);
    }

    // ------------------------------------------------------------------ election

    static void election(Ctx c) {
        CompoundTag e = c.d.getCompound("election");
        String office = e.getString("office");
        if (!e.getBoolean("holds")) {
            c.line("Nobody is elected here.", UiStyle.TEXT);
            for (String s : strings(e, "instead")) c.wrap(s, UiStyle.TEXT_MUTED);
            String form = c.d.getString("form");
            c.wrap(switch (form) {
                case "LORD" -> "When the lord dies the eldest grown child is crowned before the board; a lord the town is miserable under faces a vote of no confidence.";
                case "PLANNED" -> "When the chair goes, the party congress meets before the board and the committee appoints the next.";
                case "JUNTA" -> "When the general goes, the senior officer takes command before the watch.";
                case "CHAPLAIN" -> "When the chaplain dies, the chapel chooses the most devout.";
                default -> "";
            }, UiStyle.TEXT_FAINT);
            lastResult(c, e);
            return;
        }
        String state = e.getString("state");
        String head = switch (state) {
            case "called" -> "Campaign under way: polling day is day " + (e.getLong("voteDay")) + ".";
            case "today" -> "Polling day today: the polls open in the morning.";
            case "voting" -> "Polling day: the polls are open at the ballot box.";
            case "closed" -> "The polls have closed: the count is at the board at dusk.";
            case "counting" -> "The count is on at the board.";
            default -> "No election under way. The next is on day " + e.getLong("next") + ".";
        };
        c.line(head, state.equals("none") ? UiStyle.TEXT : UiStyle.ACCENT);
        if (e.contains("voters")) {
            int voted = e.getInt("voted"), voters = Math.max(1, e.getInt("voters"));
            if (c.visible(10)) {
                UiStyle.bar(c.g, c.x, c.y + 2, Math.min(160, c.w / 2), 6, voted / (float) voters, UiStyle.SUCCESS_FILL);
                c.g.drawString(c.font, voted + " of " + voters + " have voted" + (e.getInt("queue") > 0 ? " · " + e.getInt("queue") + " in the queue" : ""),
                    c.x + Math.min(160, c.w / 2) + 6, c.y, UiStyle.TEXT_MUTED, false);
            }
            c.y += 12;
        }
        if (e.contains("youVoted")) c.line("You voted for " + e.getString("youVoted") + ". Your ballot is secret and counted at dusk.", UiStyle.SUCCESS);
        // Show me.
        int bx = c.x;
        bx += c.button("Show me: polling station", bx, c.y, e.contains("station"), e.contains("station") ? "Mark the ballot box on your screen"
            : "The ballot box is set out when an election is called", "show", args("what", "station")) + 4;
        c.button("Show me: speech spot", bx, c.y, true, "Mark where the candidates speak, before the board", "show", args("what", "speech"));
        c.y += 18;
        ListTag cands = e.getList("candidates", Tag.TAG_COMPOUND);
        if (!cands.isEmpty()) {
            c.heading("Standing for " + office);
            for (int i = 0; i < cands.size(); i++) candidate(c, cands.getCompound(i), false, null);
        }
        CompoundTag you = c.d.getCompound("you");
        if (c.screen && you.contains("can")) yourCandidacy(c, you, e);
        lastResult(c, e);
    }

    /** One candidate's card: its colour, name and party, what it stands for, its pledge, its posters and its hour in the square. */
    static void candidate(Ctx c, CompoundTag k, boolean ballot, @Nullable String voteWhy) {
        int h = 34;
        if (c.visible(h)) {
            int col = rgb(k.getInt("colour"));
            UiStyle.card(c.g, c.x, c.y, c.w, h, col);
            UiStyle.title(c.g, c.font, UiStyle.clip(c.font, k.getString("name"), c.w / 2), c.x + 8, c.y + 4);
            int nx = c.x + 12 + UiStyle.titleWidth(c.font, UiStyle.clip(c.font, k.getString("name"), c.w / 2));
            if (!k.getString("party").isEmpty()) nx += UiStyle.chip(c.g, c.font, k.getString("party"), nx, c.y + 2, col) + 4;
            String right = k.getInt("posters") + " posters";
            if (!ballot) c.g.drawString(c.font, right, c.x + c.w - c.font.width(right) - 6, c.y + 4, UiStyle.TEXT_FAINT, false);
            UiStyle.text(c.g, c.font, "For " + k.getString("cares") + ": " + k.getString("pledge"), c.x + 8, c.y + 15, c.w - (ballot ? 80 : 14), UiStyle.TEXT_MUTED);
            String sub = (k.contains("nature") ? k.getString("nature") : k.getBoolean("player") ? "a player" : "") + (k.getString("rally").isEmpty() ? "" : " · " + k.getString("rally"));
            UiStyle.text(c.g, c.font, sub, c.x + 8, c.y + 24, c.w - (ballot ? 80 : 14), UiStyle.TEXT_FAINT);
            if (c.over(c.x, c.y, c.w - (ballot ? 76 : 0), h)) c.tip(k.getString("name") + (k.getString("party").isEmpty() ? "" : ", " + k.getString("party")),
                "Stands for " + k.getString("cares") + " (a " + k.getString("type") + "'s platform).", "Promises: " + k.getString("pledge"),
                k.contains("nature") ? "Nature: " + k.getString("nature") : "", k.getString("rally"));
        }
        if (ballot) {
            CompoundTag a = args("candidate", k.getString("id"), "name", k.getString("name"));
            c.button("Vote", c.x + c.w - 52, c.y + 10, voteWhy == null, voteWhy == null ? "Choose " + k.getString("name") + " (you confirm next)" : voteWhy, "pick", a);
        }
        c.y += h + 4;
    }

    /** A player's own candidacy: stand, promise, withdraw, speak. */
    static void yourCandidacy(Ctx c, CompoundTag you, CompoundTag e) {
        c.heading("Your candidacy");
        if (!you.getBoolean("standing")) {
            String why = null;
            for (String s : strings(you, "cannot")) if (s.startsWith("Stand for")) why = s.substring(s.indexOf(':') + 1).trim();
            c.wrap(why == null ? "You may stand for " + e.getString("office") + ". Put your name forward at the board or the hall." : "You cannot stand: " + why,
                why == null ? UiStyle.TEXT : UiStyle.TEXT_MUTED);
            c.button("Stand for " + e.getString("office"), c.x, c.y, why == null, why == null ? "Put your name on the ballot (at the board or the hall)" : why,
                "stand", new CompoundTag());
            c.y += 18;
            return;
        }
        List<String> made = strings(you, "pledges");
        c.wrap("You are standing. Your promises: " + (made.isEmpty() ? "none yet." : String.join("; ", made) + "."), UiStyle.TEXT);
        if (made.size() < 3) {
            ListTag off = you.getList("offered", Tag.TAG_COMPOUND);
            for (int i = 0; i < off.size(); i++) {
                CompoundTag o = off.getCompound(i);
                if (made.contains(o.getString("words"))) continue;
                c.button("Promise: " + UiStyle.clip(c.font, o.getString("words"), c.w - 90), c.x, c.y, true,
                    "Folk who care for " + o.getString("cares") + " will like it. Up to three promises.", "pledge", args("key", o.getString("key")));
                c.y += 16;
            }
        }
        int bx = c.x;
        bx += c.button("Make a speech", bx, c.y, true, "At the board, once a day: everyone within earshot hears you", "speech", new CompoundTag()) + 4;
        c.button("Withdraw", bx, c.y, true, "Take your name off the ballot (you confirm next)", "confirmWithdraw", new CompoundTag());
        c.y += 18;
    }

    /** The last count as a bar chart: a bar for each, in its colour, the winner starred. */
    static void lastResult(Ctx c, CompoundTag e) {
        if (!e.contains("last")) return;
        CompoundTag last = e.getCompound("last");
        c.heading("The last count: day " + last.getLong("day"));
        ListTag rows = last.getList("rows", Tag.TAG_COMPOUND);
        if (rows.isEmpty()) {
            c.wrap(capital(last.getString("how")) + ".", UiStyle.TEXT_MUTED);
            return;
        }
        int most = 1;
        for (int i = 0; i < rows.size(); i++) most = Math.max(most, rows.getCompound(i).getInt("votes"));
        int label = Math.min(90, c.w / 4), barW = c.w - label - 40;
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag r = rows.getCompound(i);
            if (c.visible(14)) {
                boolean won = r.getString("name").equals(last.getString("winner"));
                c.g.drawString(c.font, UiStyle.clip(c.font, (won ? "★ " : "") + r.getString("name"), label - 4), c.x, c.y + 3, won ? UiStyle.STAR_INK : UiStyle.TEXT, false);
                int n = r.getInt("votes");
                int bw = Math.max(n > 0 ? 2 : 0, barW * n / most);
                c.g.fill(c.x + label, c.y + 1, c.x + label + barW, c.y + 12, UiStyle.WELL);
                c.g.fill(c.x + label, c.y + 1, c.x + label + bw, c.y + 12, rgb(r.getInt("colour")));
                c.g.fill(c.x + label, c.y + 1, c.x + label + bw, c.y + 2, Ui.lighten(rgb(r.getInt("colour")), 0.4F));
                c.g.drawString(c.font, Integer.toString(n), c.x + label + barW + 4, c.y + 3, UiStyle.TEXT, false);
                if (c.over(c.x, c.y, c.w, 14)) c.tip(r.getString("name") + ": " + n + " votes" + (r.getString("party").isEmpty() ? "" : " (" + r.getString("party") + ")"));
            }
            c.y += 15;
        }
        String how = last.getString("how");
        c.wrap(last.getInt("voted") + " of " + last.getInt("voters") + " voted. " + (last.getString("winner").isEmpty() ? "" : last.getString("winner") + " was elected "
            + last.getString("office") + (how.isEmpty() ? "" : ": " + how) + "."), UiStyle.TEXT_MUTED);
    }

    // ------------------------------------------------------------------ the ballot (the screen)

    static void ballot(Ctx c) {
        CompoundTag e = c.d.getCompound("election");
        CompoundTag you = c.d.getCompound("you");
        String why = null;
        for (String s : strings(you, "cannot")) if (s.startsWith("Vote:")) why = s.substring(5).trim();
        if (!e.getBoolean("holds") || !e.contains("candidates")) {
            c.line(e.getBoolean("holds") ? "No election is under way: the next is on day " + e.getLong("next") + "." : "Nobody votes for the leader here.", UiStyle.TEXT);
            c.wrap(e.getBoolean("holds") ? "The ballot opens the day an election is called, and you may vote early from then on." : capital(c.d.getString("how")) + ".",
                UiStyle.TEXT_MUTED);
            lastResult(c, e);
            return;
        }
        c.line("Ballot paper: election for " + e.getString("office") + " of " + c.d.getString("town") + ", day " + e.getLong("voteDay"), UiStyle.TEXT);
        c.wrap(e.contains("youVoted") ? "Your ballot is in the box: a vote for " + e.getString("youVoted") + "."
            : why == null ? "Choose one. Your vote is secret and cannot be changed afterwards." : "You cannot vote: " + why,
            e.contains("youVoted") ? UiStyle.SUCCESS : why == null ? UiStyle.TEXT_MUTED : UiStyle.DANGER);
        c.y += 2;
        ListTag cands = e.getList("candidates", Tag.TAG_COMPOUND);
        for (int i = 0; i < cands.size(); i++) candidate(c, cands.getCompound(i), true, why);
        c.wrap("Away on polling day? Vote early: a ballot cast before the day counts the same.", UiStyle.TEXT_FAINT);
    }

    // ------------------------------------------------------------------ parties

    static void parties(Ctx c) {
        CompoundTag p = c.d.getCompound("parties");
        boolean dem = p.getBoolean("democracy");
        ListTag seats = p.getList("seats", Tag.TAG_COMPOUND);
        if (dem && !seats.isEmpty()) {
            c.line("The town council: " + p.getInt("total") + " seats", UiStyle.TEXT);
            hemicycle(c, seats, p.getInt("total"));
            if (p.contains("coalition")) c.line(capital(p.getString("coalition")) + ".", UiStyle.ACCENT);
            for (int i = 0; i < seats.size(); i++) {
                CompoundTag s = seats.getCompound(i);
                if (c.visible(10)) {
                    c.g.fill(c.x, c.y + 1, c.x + 8, c.y + 9, rgb(s.getInt("colour")));
                    UiStyle.text(c.g, c.font, s.getString("party") + ": " + s.getInt("seats") + " seats, " + s.getInt("votes") + " votes"
                        + (s.getBoolean("governing") ? " · governing" : ""), c.x + 12, c.y, c.w - 12, UiStyle.TEXT);
                }
                c.y += 11;
            }
            ListTag members = p.getList("members", Tag.TAG_COMPOUND);
            if (!members.isEmpty()) {
                c.heading("Members");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < members.size(); i++) {
                    CompoundTag m = members.getCompound(i);
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(m.getString("name")).append(" (").append(m.getString("party")).append(')');
                }
                c.wrap(sb.toString(), UiStyle.TEXT_MUTED);
            }
        } else if (dem) {
            c.wrap("The seats are shared out at the first election, by the votes each party's candidate wins.", UiStyle.TEXT_MUTED);
        } else {
            c.wrap("No parties stand here: " + c.d.getString("short").toLowerCase(java.util.Locale.ROOT) + " has no elected council. These are the town's camps, all the same:",
                UiStyle.TEXT_MUTED);
        }
        ListTag fol = p.getList("following", Tag.TAG_COMPOUND);
        c.heading(dem ? "Who leans where" : "The town's camps");
        int total = 0;
        for (int i = 0; i < fol.size(); i++) total += fol.getCompound(i).getInt("n");
        if (total > 0 && c.visible(10)) {
            int bx = c.x;
            for (int i = 0; i < fol.size(); i++) {
                CompoundTag f = fol.getCompound(i);
                int bw = i == fol.size() - 1 ? c.x + c.w - bx : c.w * f.getInt("n") / total;
                c.g.fill(bx, c.y, bx + bw, c.y + 8, rgb(f.getInt("colour")));
                if (c.over(bx, c.y, bw, 8)) c.tip(f.getString("party") + ": " + f.getInt("n") + " of " + total);
                bx += bw;
            }
        }
        c.y += 12;
        for (int i = 0; i < fol.size(); i++) {
            CompoundTag f = fol.getCompound(i);
            if (c.visible(20)) {
                c.g.fill(c.x, c.y + 1, c.x + 8, c.y + 9, rgb(f.getInt("colour")));
                UiStyle.text(c.g, c.font, f.getString("party") + " (" + f.getInt("n") + "): " + f.getString("pledge"), c.x + 12, c.y, c.w - 12, UiStyle.TEXT);
                UiStyle.text(c.g, c.font, f.getString("tax") + "; builds " + f.getString("build") + " first", c.x + 12, c.y + 10, c.w - 12, UiStyle.TEXT_FAINT);
            }
            c.y += 22;
        }
    }

    /** The council's seats as dots in a half circle, each in its party's colour. */
    static void hemicycle(Ctx c, ListTag seats, int total) {
        int h = 46;
        if (c.visible(h) && total > 0) {
            int cx = c.x + c.w / 2, cy = c.y + h - 4;
            int r = Math.min(40, c.w / 4);
            int idx = 0;
            for (int i = 0; i < seats.size(); i++) {
                CompoundTag s = seats.getCompound(i);
                for (int n = 0; n < s.getInt("seats"); n++, idx++) {
                    double ang = Math.PI - Math.PI * (idx + 0.5) / total;
                    int px = cx + (int) Math.round(Math.cos(ang) * r), py = cy - (int) Math.round(Math.sin(ang) * r);
                    c.g.fill(px - 4, py - 4, px + 4, py + 4, UiStyle.EDGE);
                    c.g.fill(px - 3, py - 3, px + 3, py + 3, rgb(s.getInt("colour")));
                }
            }
            String mid = total / 2 + 1 + " for a majority";
            c.g.drawString(c.font, mid, cx - c.font.width(mid) / 2, cy - 6, UiStyle.TEXT_FAINT, false);
        }
        c.y += h + 2;
    }

    // ------------------------------------------------------------------ unrest

    static void meter(Ctx c, int level, String stage) {
        int h = 26;
        if (c.visible(h)) {
            int bw = c.w - 4;
            c.g.fill(c.x, c.y, c.x + bw, c.y + 10, UiStyle.WELL);
            int fill = level >= 75 ? UiStyle.DANGER_FILL : level >= 45 ? UiStyle.WARNING_FILL : UiStyle.SUCCESS_FILL;
            c.g.fill(c.x, c.y, c.x + bw * level / 100, c.y + 10, fill);
            int[] marks = { 25, 45, 60, 75, 90 };
            String[] names = { "grumble", "petition", "protest", "strike", "revolt" };
            for (int i = 0; i < marks.length; i++) {
                int mx = c.x + bw * marks[i] / 100;
                c.g.fill(mx, c.y - 1, mx + 1, c.y + 12, UiStyle.EDGE);
                String n = names[i];
                c.g.pose().pushPose();
                c.g.pose().translate(mx - c.font.width(n) * 0.35F, c.y + 13, 0);
                c.g.pose().scale(0.7F, 0.7F, 1F);
                c.g.drawString(c.font, n, 0, 0, UiStyle.TEXT_FAINT, false);
                c.g.pose().popPose();
            }
            String v = level + " · " + stage;
            c.g.drawString(c.font, v, c.x + 3, c.y + 1, UiStyle.TEXT_ON_DARK, true);
            if (c.over(c.x, c.y, bw, 22)) c.tip("Unrest " + level + " of 100: " + stage + ".",
                "At 25 folk grumble; 45, a petition; 60, protests in the square; 75, strikes; 90, the rising.");
        }
        c.y += h;
    }

    static void unrest(Ctx c) {
        CompoundTag u = c.d.getCompound("unrest");
        meter(c, u.getInt("level"), u.getString("stage"));
        c.wrap(u.getString("next"), UiStyle.TEXT);
        c.wrap("Under " + c.d.getString("short").toLowerCase(java.util.Locale.ROOT) + ", grievances turn into unrest at " + u.getInt("amp") + " in a hundred"
            + (u.getBoolean("capped") ? "; the vote lets it out, so it never comes to a strike or a rising here." : "; with no vote to let it out, it can end in a rising."),
            UiStyle.TEXT_FAINT);
        if (!u.getString("why").isEmpty()) c.pair("What's behind it", u.getString("why"));
        if (u.getInt("tax") > 0) c.pair("The taxes' share", "tax unrest " + u.getInt("tax") + " (the Taxes page)");
        c.pair("They would rather", capital(u.getString("wanted")));
        if (u.contains("petition")) {
            CompoundTag p = u.getCompound("petition");
            c.heading("The petition");
            c.wrap(p.getString("by") + "'s petition asks for " + p.getString("asks") + ".", UiStyle.TEXT);
            if (c.visible(10)) {
                UiStyle.bar(c.g, c.x, c.y + 2, Math.min(160, c.w / 2), 6, p.getInt("signed") / (float) Math.max(1, p.getInt("needed")), UiStyle.INFO_FILL);
                c.g.drawString(c.font, p.getInt("signed") + " of " + p.getInt("needed") + " names", c.x + Math.min(160, c.w / 2) + 6, c.y, UiStyle.TEXT_MUTED, false);
            }
            c.y += 12;
            boolean citizen = c.d.getCompound("you").getBoolean("citizen");
            boolean signed = p.getBoolean("youSigned");
            c.button(signed ? "Signed" : "Sign the petition", c.x, c.y, citizen && !signed, signed ? "Your name is on it already"
                : citizen ? "Put your name to it (counted each morning)" : "Only a citizen may sign: ask any folk if you may live here", "sign", new CompoundTag());
            c.y += 18;
        }
        int[] hist = u.getIntArray("history");
        if (hist.length >= 2) {
            c.heading("The last days");
            if (c.visible(24)) {
                UiStyle.well(c.g, c.x, c.y, Math.min(c.w, 200), 24);
                UiStyle.sparkline(c.g, c.x + 3, c.y + 3, Math.min(c.w, 200) - 6, 18, hist, hist.length, UiStyle.DANGER_FILL);
            }
            c.y += 28;
        }
        List<String> log = strings(u, "log");
        if (!log.isEmpty()) {
            c.heading("What has happened");
            for (int i = log.size() - 1; i >= 0; i--) c.wrap(log.get(i), UiStyle.TEXT_MUTED);
        }
    }

    // ------------------------------------------------------------------ the forms compared

    static void forms(Ctx c) {
        ListTag l = c.d.getList("compare", Tag.TAG_COMPOUND);
        String now = c.d.getString("form");
        c.wrap("Every form a town may take, side by side. No form suits every town: each has real strengths and real costs. "
            + "Hover a row for its strengths and weaknesses.", UiStyle.TEXT_MUTED);
        int known = l.isEmpty() ? 0 : l.getCompound(0).getInt("known");
        int[] colW = cols(c.w);
        String[] heads = { "Form", "Vote", "Business", "Market", "Wages", "Unrest", "Towns" };
        if (c.visible(10)) {
            int hx = c.x;
            for (int i = 0; i < heads.length; i++) {
                if (colW[i] > 0) c.g.drawString(c.font, UiStyle.clip(c.font, heads[i], colW[i] - 4), hx, c.y, UiStyle.TEXT_FAINT, false);
                hx += colW[i];
            }
        }
        c.y += 12;
        for (int i = 0; i < l.size(); i++) {
            CompoundTag f = l.getCompound(i);
            boolean mine = f.getString("form").equals(now);
            int rh = 13;
            if (c.visible(rh)) {
                if (mine) c.g.fill(c.x - 2, c.y - 1, c.x + c.w, c.y + rh - 1, UiStyle.TILE_SELECTED);
                else if (i % 2 == 0) c.g.fill(c.x - 2, c.y - 1, c.x + c.w, c.y + rh - 1, 0x18000000);
                String[] cells = { f.getString("short"), f.getString("vote"), f.getString("business"), f.getString("market"), f.getString("wages"),
                    "×" + (f.getInt("unrest") / 100.0), f.getInt("towns") + (known > 0 ? "" : "") };
                int cx = c.x;
                for (int k = 0; k < cells.length; k++) {
                    if (colW[k] <= 0) continue;
                    if (k == 0) {
                        c.g.fill(cx, c.y + 1, cx + 6, c.y + 9, rgb(f.getInt("colour")));
                        UiStyle.text(c.g, c.font, cells[k], cx + 9, c.y + 1, colW[k] - 12, mine ? UiStyle.ACCENT : UiStyle.TEXT);
                    } else UiStyle.text(c.g, c.font, cells[k], cx, c.y + 1, colW[k] - 4, k == 5 ? (f.getInt("unrest") > 100 ? UiStyle.DANGER : UiStyle.SUCCESS) : UiStyle.TEXT_MUTED);
                    cx += colW[k];
                }
                if (c.over(c.x, c.y, c.w, rh)) {
                    List<String> t = new ArrayList<>();
                    t.add(f.getString("words") + (mine ? " (this town)" : ""));
                    t.add("Vote: " + f.getString("vote"));
                    t.add("Business: " + f.getString("business"));
                    t.add("Market: " + f.getString("market") + "; wages " + f.getString("wages").toLowerCase(java.util.Locale.ROOT));
                    for (String s : strings(f, "strengths")) t.add("+ " + s);
                    for (String s : strings(f, "weaknesses")) t.add("- " + s);
                    t.add("Unrest builds at ×" + (f.getInt("unrest") / 100.0) + ". Towns under it about: " + f.getInt("towns") + " of " + known + ".");
                    c.tip(t.toArray(new String[0]));
                }
            }
            c.y += rh;
        }
        int dem = 0, all = 0;
        for (int i = 0; i < l.size(); i++) {
            CompoundTag f = l.getCompound(i);
            all += f.getInt("towns");
            if (f.getString("form").equals("DEMOCRACY") || f.getString("form").equals("REEVE")) dem += f.getInt("towns");
        }
        if (all > 0) c.wrap(dem + " of the " + all + " towns about elect their leaders.", UiStyle.TEXT_FAINT);
    }

    static int[] cols(int w) {
        // Form, Vote, Business, Market, Wages, Unrest, Towns
        if (w >= 420) return new int[]{ 96, (w - 96 - 46 - 40) / 4, (w - 96 - 46 - 40) / 4, (w - 96 - 46 - 40) / 4, (w - 96 - 46 - 40) / 4, 46, 40 };
        return new int[]{ 90, (w - 90 - 40) / 2, (w - 90 - 40) / 2, 0, 0, 40, 0 };
    }

    // ------------------------------------------------------------------ relations

    static void relations(Ctx c) {
        ListTag l = c.d.getList("relations", Tag.TAG_COMPOUND);
        if (l.isEmpty()) {
            c.wrap("No towns near enough to have dealings with.", UiStyle.TEXT_MUTED);
            return;
        }
        c.wrap("How the towns about are governed, and what that does to how they get on with " + c.d.getString("town") + ".", UiStyle.TEXT_MUTED);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag r = l.getCompound(i);
            int h = 24;
            if (c.visible(h)) {
                UiStyle.card(c.g, c.x, c.y, c.w, h - 2, rgb(r.getInt("colour")));
                String name = r.getString("name");
                UiStyle.text(c.g, c.font, name, c.x + 8, c.y + 3, c.w / 3, UiStyle.TEXT);
                int nx = c.x + 10 + Math.min(c.w / 3, c.font.width(name));
                nx += UiStyle.chip(c.g, c.font, r.getString("form"), nx, c.y + 1, rgb(r.getInt("colour"))) + 4;
                int rel = r.getInt("relation");
                String terms = r.getString("terms") + " (" + (rel > 0 ? "+" : "") + rel + ")";
                c.g.drawString(c.font, terms, c.x + c.w - c.font.width(terms) - 6, c.y + 3, rel >= 20 ? UiStyle.SUCCESS : rel <= -20 ? UiStyle.DANGER : UiStyle.TEXT_MUTED, false);
                if (r.getBoolean("embargo")) UiStyle.badge(c.g, c.font, "embargo", c.x + c.w - c.font.width(terms) - 60, c.y + 2, UiStyle.DANGER_FILL);
                UiStyle.text(c.g, c.font, r.getString("note"), c.x + 8, c.y + 13, c.w - 14, UiStyle.TEXT_FAINT);
                if (c.over(c.x, c.y, c.w, h)) c.tip(name + ": " + r.getString("form"), r.getString("terms") + ", " + rel, r.getString("note"),
                    r.getBoolean("embargo") ? "No trade between you: an embargo over how you are each ruled." : "");
            }
            c.y += h;
        }
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
