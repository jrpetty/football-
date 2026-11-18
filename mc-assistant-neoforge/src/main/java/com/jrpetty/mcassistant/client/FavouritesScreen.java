package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.UiConfig;
import com.jrpetty.mcassistant.net.FavouritesAskPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * [ui] The folk you follow (entity/Favourites): a shortlist of cards, and one folk's progress in full.
 *
 * <p><b>The list</b>: a card for each, in your order or sorted (level, mood, wealth, the week's change, name): its
 * likeness, name, trade and level with how far to the next, its mood, its worth, what it is doing now (or that it is
 * away, or remembered), and arrows for how it has moved since last week. In your own order, the arrows on a card's
 * edge move it up or down. Click a card for its detail.
 *
 * <p><b>A folk's detail</b>: its likeness in its own colour, its saying, its mood, health and worth; then three tabs:
 * <b>Progress</b> (line charts of its level, worth, wage and mood over the days it has been followed, each with its
 * change this week), <b>Life</b> (its timeline: new trades, rises, a wedding, children, a new house, a firm, illness,
 * the weighty things it remembers) and <b>Details</b> (everything its last snapshot holds, its traits and its own life).
 * The footer opens its full profile when it is about, pins it to the tracker, or takes it off the list.
 *
 * <p>Opened by its key (unbound until you bind it), from the city books' header and from a folk's profile. The server
 * sends the list or the detail when asked; the screen asks again every five seconds while it is open, never once shut.
 */
public class FavouritesScreen extends Screen {

    private static final int MAX_W = 460, MAX_H = 320, MIN_W = 240, MIN_H = 180;
    private static final int LIST_HEAD = 30, DETAIL_HEAD = 58, TABS_H = 18, FOOT_H = 24, CARD_H = 48;
    private static final int REFRESH_TICKS = 100;

    private enum Sort {
        ORDER("Order", "Your own order: move a card with the arrows on its edge."),
        LEVEL("Level", "Highest level first."),
        MOOD("Mood", "Happiest first."),
        WORTH("Wealth", "Richest first."),
        WEEK("Week", "Most changed this week first (level, worth and mood)."),
        NAME("Name", "A to Z.");

        final String label, tip;

        Sort(String label, String tip) { this.label = label; this.tip = tip; }
    }

    private static final String[] DETAIL_TABS = { "Progress", "Life", "Details" };

    private static Sort sort = Sort.ORDER;

    @Nullable private final Screen parent;
    private CompoundTag list = new CompoundTag();
    private final List<CompoundTag> cards = new ArrayList<>();
    @Nullable private CompoundTag detail;
    @Nullable private UUID detailId;
    private int detailTab;
    private int left, top, w, h, ticks;
    private final UiStyle.Scroll scroll = new UiStyle.Scroll();
    private boolean confirmRemove;
    /** The detail's page laid out: its rows, its height, and the width and tab it was laid out for. */
    private final List<Row> rows = new ArrayList<>();
    private int laidW = -1, laidTab = -1, pageH;
    private String saying = "";

    public FavouritesScreen(@Nullable Screen parent) {
        super(Component.literal("Favourites"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ data

    void receive(CompoundTag data) {
        if (data.getString("kind").equals("list")) {
            list = data;
            cards.clear();
            ListTag l = data.getList("cards", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) cards.add(l.getCompound(i));
            resort();
            if (detailId != null) {                                       // the folk shown is gone off the list
                boolean still = false;
                for (CompoundTag c : cards) still |= c.getUUID("uuid").equals(detailId);
                if (!still) back();
            }
        } else if (data.getString("kind").equals("detail")) {
            boolean fresh = detailId == null || !detailId.equals(data.getUUID("uuid"));
            detail = data;
            detailId = data.getUUID("uuid");
            String s = data.getCompound("last").getString("saying");
            saying = s.isEmpty() ? "" : "“" + s + "”";
            laidW = -1;
            if (fresh) { scroll.reset(); confirmRemove = false; }
        }
    }

    /** Ask again for what is shown (a star changed, a card moved). */
    void refresh() {
        if (detailId != null) PacketDistributor.sendToServer(new FavouritesAskPayload("detail", detailId, 1));
        PacketDistributor.sendToServer(new FavouritesAskPayload("list", FavouritesAskPayload.NOBODY, 1));
    }

    private void resort() {
        Comparator<CompoundTag> by = switch (sort) {
            case LEVEL -> Comparator.comparingInt((CompoundTag c) -> -c.getInt("level"));
            case MOOD -> Comparator.comparingInt((CompoundTag c) -> -c.getInt("mood"));
            case WORTH -> Comparator.comparingInt((CompoundTag c) -> -c.getInt("worth"));
            case WEEK -> Comparator.comparingInt((CompoundTag c) -> -(Math.abs(c.getInt("d7_level")) * 50
                + Math.abs(c.getInt("d7_worth")) + Math.abs(c.getInt("d7_mood")) * 2));
            case NAME -> Comparator.comparing((CompoundTag c) -> c.getString("name"), String.CASE_INSENSITIVE_ORDER);
            default -> null;
        };
        if (by == null) {
            cards.clear();
            ListTag l = list.getList("cards", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) cards.add(l.getCompound(i));
        } else {
            cards.sort(Comparator.comparing((CompoundTag c) -> c.getBoolean("dead")).thenComparing(by));
        }
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void init() {
        w = Math.max(Math.min(MIN_W, width - 8), Math.min(MAX_W, width - 2 * UiStyle.S2));
        h = Math.max(Math.min(MIN_H, height - 8), Math.min(MAX_H, height - 2 * UiStyle.S2));
        left = (width - w) / 2;
        top = (height - h) / 2;
        laidW = -1;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS != 0) return;
        if (detailId != null) PacketDistributor.sendToServer(new FavouritesAskPayload("detail", detailId, 0));
        else PacketDistributor.sendToServer(new FavouritesAskPayload("list", FavouritesAskPayload.NOBODY, 0));
    }

    @Override
    public void onClose() {
        if (detailId != null && !list.isEmpty()) { back(); return; }
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private void back() {
        detailId = null;
        detail = null;
        confirmRemove = false;
        scroll.reset();
        if (list.isEmpty()) PacketDistributor.sendToServer(new FavouritesAskPayload("list", FavouritesAskPayload.NOBODY, 1));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
        g.fill(0, 0, width, height, UiStyle.BACKDROP);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        String tip = detailId != null && detail != null ? detail(g, mx, my) : list(g, mx, my);
        if (tip != null) UiStyle.tooltip(g, font, tip, mx, my, 220);
    }

    // ------------------------------------------------------------------ the list

    private int bodyY() { return top + LIST_HEAD + UiStyle.S1; }
    private int bodyH() { return top + h - FOOT_H - bodyY() - UiStyle.S1; }
    private int cols() { return w >= 380 ? 2 : 1; }
    private int cardW() { return (w - 2 * UiStyle.S2 - 5 - (cols() - 1) * UiStyle.S1) / cols(); }

    @Nullable
    private String list(GuiGraphics g, int mx, int my) {
        String tip = null;
        UiStyle.headerBand(g, left, top, w, LIST_HEAD);
        UiStyle.title(g, font, "★ Favourites", left + UiStyle.S2, top + 6);
        String count = cards.size() + " of " + Math.max(1, list.getInt("cap")) + " followed";
        g.drawString(font, count, left + UiStyle.S2, top + 18, UiStyle.TEXT_FAINT, false);
        // The sorts, right.
        int sx = left + w - UiStyle.S2;
        Sort[] sorts = Sort.values();
        for (int i = sorts.length - 1; i >= 0; i--) {
            int bw = font.width(sorts[i].label) + 10;
            sx -= bw;
            boolean over = inside(mx, my, sx, top + 7, bw, 16);
            UiStyle.topTab(g, sx, top + 7, bw, 16, UiStyle.State.of(true, sort == sorts[i], over));
            g.drawString(font, sorts[i].label, sx + 5, top + 11, sort == sorts[i] ? UiStyle.TEXT : UiStyle.TEXT_MUTED, false);
            if (over) tip = "Sort: " + sorts[i].tip;
            sx -= 2;
        }
        int x = left + UiStyle.S2, y = bodyY(), bh = bodyH(), cw = cardW(), n = cols();
        int rowsN = (cards.size() + n - 1) / n;
        scroll.fit(rowsN * (CARD_H + UiStyle.S1), bh);
        if (cards.isEmpty()) {
            UiStyle.well(g, x, y, w - 2 * UiStyle.S2, bh, UiStyle.PAGE);
            UiStyle.empty(g, font, list.isEmpty() ? "Asking the town…" : "You follow nobody yet",
                "Star a folk on its profile or talk screen to follow it here.", x, y, w - 2 * UiStyle.S2, bh);
        } else {
            g.enableScissor(x - 1, y, x + w - 2 * UiStyle.S2 + 1, y + bh);
            for (int i = 0; i < cards.size(); i++) {
                int cx = x + (i % n) * (cw + UiStyle.S1), cy = y + (i / n) * (CARD_H + UiStyle.S1) - scroll.offset();
                if (cy + CARD_H < y || cy > y + bh) continue;
                String t = card(g, cards.get(i), cx, cy, cw, mx, my, inside(mx, my, x, y, w, bh));
                if (t != null) tip = t;
            }
            g.disableScissor();
            UiStyle.scrollBar(g, left + w - UiStyle.S2 - 3, y, bh, scroll.offset(), scroll.max(), bh);
        }
        // The footer.
        int fy = top + h - FOOT_H + 3, bw = 64;
        int fx = left + w - UiStyle.S2 - bw;
        UiStyle.button(g, font, "Close", fx, fy, bw, 18, UiStyle.State.of(true, false, inside(mx, my, fx, fy, bw, 18)));
        fx -= 84 + UiStyle.S1;
        boolean tracker = UiConfig.tracker();
        UiStyle.button(g, font, tracker ? "Tracker: on" : "Tracker: off", fx, fy, 84, 18, UiStyle.State.of(true, tracker, inside(mx, my, fx, fy, 84, 18)));
        if (inside(mx, my, fx, fy, 84, 18)) tip = "A small card at the side of the screen for your pinned favourite (pin one on its page)."
            + (UiConfig.pinned().isEmpty() ? "\nNobody is pinned yet." : "");
        UiStyle.text(g, font, cards.isEmpty() ? "Esc closes" : "Click a card for its progress · Esc closes",
            left + UiStyle.S2, fy + 5, fx - left - 2 * UiStyle.S2, UiStyle.TEXT_FAINT);
        return tip;
    }

    /** One folk's card. Returns its tooltip when the mouse is over it. */
    @Nullable
    private String card(GuiGraphics g, CompoundTag c, int x, int y, int cw, int mx, int my, boolean inBody) {
        boolean over = inBody && inside(mx, my, x, y, cw, CARD_H);
        boolean dead = c.getBoolean("dead"), about = c.getBoolean("about");
        int rgb = c.getInt("colour");
        UiStyle.tile(g, x, y, cw, CARD_H, over ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
        g.fill(x + 1, y + 1, x + 3, y + CARD_H - 1, dead ? UiStyle.EDGE_SOFT : UiStyle.accentFill(rgb));
        UUID id = c.getUUID("uuid");
        LivingEntity e = dead ? null : FolkPortrait.find(c.getInt("entity"), id);
        int pw = 34, ph = CARD_H - 8;
        FolkPortrait.draw(g, font, e, c.getString("name"), dead ? 0x8A8A8A : rgb, Ui.job(c.getInt("ord")), x + 6, y + 4, pw, ph, true, mx, my);
        int tx = x + 6 + pw + 6, right = x + cw - (sort == Sort.ORDER ? 17 : 6);
        // Mood, top right.
        int mood = c.getInt("mood");
        String moodS = dead ? "✝" : UiStyle.number(Math.max(0, Math.min(999, mood)));
        int bw = UiStyle.badge(g, font, moodS, right - font.width(moodS) - 6, y + 4, dead ? UiStyle.EDGE_SOFT : UiStyle.levelFill(mood, 60, 35));
        UiStyle.title(g, font, UiStyle.clip(font, c.getString("name"), right - bw - 4 - tx), tx, y + 5);
        String sub = c.getString("trade") + (c.getBoolean("child") ? "" : " · level " + c.getInt("level"));
        String worth = UiStyle.coins(c.getInt("worth"));
        g.drawString(font, worth, right - font.width(worth), y + 16, UiStyle.TEXT_MUTED, false);
        UiStyle.text(g, font, sub, tx, y + 16, right - font.width(worth) - 4 - tx, UiStyle.TEXT_MUTED);
        // How far to the next level, and the week's arrows.
        int barW = Math.min(56, (right - tx) / 3);
        UiStyle.bar(g, tx, y + 28, barW, 4, c.getInt("xp_pct") / 100F, UiStyle.ACCENT_FILL);
        int ax = tx + barW + 6;
        ax = arrow(g, "lvl", c.getInt("d7_level"), ax, y + 26, right);
        ax = arrow(g, "c", c.getInt("d7_worth"), ax, y + 26, right);
        arrow(g, "mood", c.getInt("d7_mood"), ax, y + 26, right);
        String line = dead ? "Remembered · died on day " + c.getLong("died")
            : !about ? "Away · last seen day " + c.getLong("day") + (c.getString("town").isEmpty() ? "" : ", " + c.getString("town"))
            : capital(c.getString("doing"));
        UiStyle.text(g, font, line, tx, y + 36, right - tx, dead ? UiStyle.TEXT_FAINT : about ? UiStyle.INFO : UiStyle.WARNING);
        // In your own order: the arrows that move it.
        if (over && sort == Sort.ORDER) {
            boolean upOver = inside(mx, my, x + cw - 13, y + 2, 11, 10), downOver = inside(mx, my, x + cw - 13, y + CARD_H - 12, 11, 10);
            UiStyle.tile(g, x + cw - 13, y + 2, 11, 10, upOver ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
            UiStyle.tile(g, x + cw - 13, y + CARD_H - 12, 11, 10, downOver ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
            g.drawString(font, "▴", x + cw - 10, y + 3, UiStyle.TEXT, false);
            g.drawString(font, "▾", x + cw - 10, y + CARD_H - 11, UiStyle.TEXT, false);
            if (upOver) return "Move up your list.";
            if (downOver) return "Move down your list.";
        }
        if (!over) return null;
        StringBuilder sb = new StringBuilder(c.getString("name"));
        if (!c.getString("saying").isEmpty() && !dead) sb.append("  “").append(c.getString("saying")).append("”");
        sb.append("\nSince yesterday: level ").append(UiStyle.signed(c.getInt("d1_level"))).append(", worth ")
            .append(UiStyle.signed(c.getInt("d1_worth"))).append("c, mood ").append(UiStyle.signed(c.getInt("d1_mood")));
        sb.append("\nThis week: level ").append(UiStyle.signed(c.getInt("d7_level"))).append(", worth ")
            .append(UiStyle.signed(c.getInt("d7_worth"))).append("c, mood ").append(UiStyle.signed(c.getInt("d7_mood")));
        if (!c.getString("latest").isEmpty()) sb.append("\nLatest: ").append(c.getString("latest"));
        sb.append("\nClick for its progress.");
        return sb.toString();
    }

    /** A week's change as an arrow and a number ("▲2 lvl"); nothing for no change. Returns where the next one goes. */
    private int arrow(GuiGraphics g, String what, int delta, int x, int y, int right) {
        if (delta == 0) return x;
        String s = Math.abs(delta) + what;
        if (x + 8 + font.width(s) > right) return x;
        int aw = UiStyle.trend(g, font, delta, true, x, y);
        g.drawString(font, s, x + aw + 1, y, UiStyle.TEXT_FAINT, false);
        return x + aw + 1 + font.width(s) + 6;
    }

    // ------------------------------------------------------------------ one folk

    private int pageX() { return left + UiStyle.S2; }
    private int pageY() { return top + DETAIL_HEAD + TABS_H + UiStyle.S1; }
    private int pageW() { return w - 2 * UiStyle.S2 - 4; }
    private int pageH() { return top + h - FOOT_H - pageY() - UiStyle.S1; }

    @Nullable
    private String detail(GuiGraphics g, int mx, int my) {
        CompoundTag d = detail, last = d.getCompound("last");
        boolean dead = last.getBoolean("dead");
        int rgb = last.getInt("colour");
        String tip = null;
        // The header, in its own colour.
        UiStyle.headerBand(g, left, top, w, DETAIL_HEAD);
        g.fill(left + 1, top + 1, left + w - 1, top + 4, dead ? UiStyle.EDGE_SOFT : UiStyle.accentFill(rgb));
        LivingEntity e = dead ? null : FolkPortrait.find(d.getInt("entity"), detailId);
        int px = left + UiStyle.S2, py = top + 7, pw = 42, ph = DETAIL_HEAD - 12;
        FolkPortrait.draw(g, font, e, last.getString("name"), dead ? 0x8A8A8A : rgb, Ui.job(last.getInt("ord")), px, py, pw, ph, true, mx, my);
        int tx = px + pw + UiStyle.S2, rightW = w < 360 ? 0 : 112, tw = left + w - UiStyle.S2 - rightW - tx - (rightW > 0 ? UiStyle.S2 : 0);
        UiStyle.title(g, font, UiStyle.clip(font, last.getString("name"), tw - 12), tx, top + 8);
        int nameW = UiStyle.titleWidth(font, UiStyle.clip(font, last.getString("name"), tw - 12));
        g.drawString(font, "★", tx + nameW + 4, top + 8, UiStyle.STAR, true);
        String sub = (dead ? "Remembered · " : "") + last.getString("trade") + (last.getBoolean("child") ? "" : " · level " + last.getInt("level"))
            + " · " + last.getInt("years") + (last.getInt("years") == 1 ? " year" : " years") + (last.getString("town").isEmpty() ? "" : " · " + last.getString("town"));
        UiStyle.text(g, font, sub, tx, top + 20, tw, UiStyle.TEXT_MUTED);
        if (!saying.isEmpty()) {
            if (UiStyle.text(g, font, saying, tx, top + 32, tw, UiStyle.INFO) && inside(mx, my, tx, top + 31, tw, 10)) tip = saying;
        }
        String state = dead ? "Died on day " + last.getLong("died") : d.getBoolean("about") ? capital(last.getString("doing"))
            : "Away · last seen day " + last.getLong("day");
        UiStyle.text(g, font, state, tx, top + 43, tw, dead ? UiStyle.TEXT_FAINT : d.getBoolean("about") ? UiStyle.TEXT_FAINT : UiStyle.WARNING);
        if (rightW > 0) {
            int rx = left + w - UiStyle.S2 - rightW;
            int mood = last.getInt("mood");
            UiStyle.meter(g, font, "Mood", mood, rx, top + 9, rightW, 44);
            if (inside(mx, my, rx, top + 9, rightW, 10)) tip = "Mood " + mood + " of 100, when last looked at.";
            int hp = last.getInt("health");
            UiStyle.meter(g, font, "Health", hp, rx, top + 21, rightW, 44);
            if (inside(mx, my, rx, top + 21, rightW, 10)) tip = "Health " + hp + "%" + (last.getString("ill").isEmpty() ? "" : ": " + last.getString("ill"));
            String worth = UiStyle.coins(last.getInt("worth"));
            g.drawString(font, "Worth", rx, top + 33, UiStyle.TEXT_MUTED, false);
            g.drawString(font, worth, rx + rightW - font.width(worth), top + 33, UiStyle.TEXT, false);
            String wage = last.getInt("wage") > 0 ? last.getInt("wage") + "c a day" : "no wage";
            g.drawString(font, "Wage", rx, top + 43, UiStyle.TEXT_MUTED, false);
            g.drawString(font, wage, rx + rightW - font.width(wage), top + 43, UiStyle.TEXT, false);
        }
        // The tabs.
        int ty = top + DETAIL_HEAD + 2, x = left + UiStyle.S2, each = Math.min(86, (w - 2 * UiStyle.S2) / DETAIL_TABS.length);
        for (int i = 0; i < DETAIL_TABS.length; i++) {
            boolean over = inside(mx, my, x, ty, each - 2, TABS_H - 2);
            UiStyle.topTab(g, x, ty, each - 2, TABS_H - 2, UiStyle.State.of(true, i == detailTab, over));
            String label = DETAIL_TABS[i] + (i == 1 ? " (" + d.getList("events", Tag.TAG_COMPOUND).size() + ")" : "");
            label = UiStyle.clip(font, label, each - 22);
            g.drawString(font, label, x + (each - 2 - font.width(label)) / 2 + 5, ty + 4, i == detailTab ? UiStyle.TEXT : UiStyle.TEXT_MUTED, false);
            UiStyle.keyCap(g, font, Integer.toString(i + 1), x + 2, ty + 3);
            x += each;
        }
        // The page.
        if (laidW != pageW() || laidTab != detailTab) layout();
        int pX = pageX(), pY = pageY(), pW = pageW(), pH = pageH();
        scroll.fit(pageH, pH);
        g.enableScissor(pX - 2, pY, pX + pW + 2, pY + pH);
        boolean in = inside(mx, my, pX, pY, pW, pH);
        for (Row r : rows) {
            int ry = pY + r.y - scroll.offset();
            if (ry + r.h < pY || ry > pY + pH) continue;
            String t = r.draw(g, pX, ry, pW, in ? mx : -1, in ? my : -1);
            if (t != null) tip = t;
        }
        g.disableScissor();
        UiStyle.scrollBar(g, pX + pW + 2, pY, pH, scroll.offset(), scroll.max(), pH);
        // The footer: back, its profile, pin, take off.
        int fy = top + h - FOOT_H + 3, bh = 18;
        int fx = left + UiStyle.S2;
        UiStyle.button(g, font, "‹ All", fx, fy, 48, bh, UiStyle.State.of(true, false, inside(mx, my, fx, fy, 48, bh)));
        if (inside(mx, my, fx, fy, 48, bh)) tip = "Back to all your favourites (Esc).";
        int rx = left + w - UiStyle.S2;
        int rw = confirmRemove ? 74 : 60;
        rx -= rw;
        UiStyle.button(g, font, confirmRemove ? "Sure? Yes" : "Unfollow", rx, fy, rw, bh, UiStyle.State.of(true, confirmRemove, inside(mx, my, rx, fy, rw, bh)));
        if (inside(mx, my, rx, fy, rw, bh)) tip = confirmRemove ? "Click again to take it off your favourites (its history goes with it)." : "Take it off your favourites.";
        boolean pinned = detailId.toString().equals(UiConfig.pinned());
        rx -= 50 + UiStyle.S1;
        UiStyle.button(g, font, pinned ? "Pinned" : "Pin", rx, fy, 50, bh, UiStyle.State.of(!dead, pinned, inside(mx, my, rx, fy, 50, bh)));
        if (inside(mx, my, rx, fy, 50, bh)) tip = pinned ? "Pinned to the tracker. Click to unpin." : "Pin it to the small tracker at the side of the screen (turns the tracker on).";
        boolean canProfile = e != null;
        rx -= 58 + UiStyle.S1;
        UiStyle.button(g, font, "Profile", rx, fy, 58, bh, UiStyle.State.of(canProfile, false, inside(mx, my, rx, fy, 58, bh)));
        if (inside(mx, my, rx, fy, 58, bh)) tip = canProfile ? "Its full profile." : dead ? "It has died." : "Its full profile, once you are near enough to see it.";
        return tip;
    }

    // ------------------------------------------------------------------ the detail's pages

    /** A piece of the detail's page: where it sits and how tall it is, and how it draws (returning a tooltip). */
    private abstract static class Row {
        int y, h;

        @Nullable
        abstract String draw(GuiGraphics g, int x, int y, int w, int mx, int my);
    }

    private void layout() {
        laidW = pageW();
        laidTab = detailTab;
        rows.clear();
        int[] y = { 0 };
        int pw = laidW;
        CompoundTag d = detail, last = d.getCompound("last");
        switch (detailTab) {
            case 0 -> {
                int n = d.getLongArray("days").length;
                add(y, heading("How it has come on" + (n > 0 ? " · " + n + (n == 1 ? " day" : " days") + " followed" : "")));
                int[] lvl = d.getIntArray("level"), xp = d.getIntArray("xp_pct");
                int[] lv = new int[lvl.length];
                for (int i = 0; i < lvl.length; i++) lv[i] = lvl[i] * 100 + (i < xp.length ? xp[i] : 0);
                int cols = pw >= 300 ? 2 : 1, cw = (pw - (cols - 1) * UiStyle.S2) / cols;
                List<Chart> charts = List.of(
                    new Chart("Level", lv, last.getInt("level") * 100 + last.getInt("xp_pct"), 100, "", 0xFF2E7D32),
                    new Chart("Worth", d.getIntArray("worth"), last.getInt("worth"), 1, "c", 0xFFB8860B),
                    new Chart("Wage", d.getIntArray("wage"), last.getInt("wage"), 1, "c", 0xFF1D4A86),
                    new Chart("Mood", d.getIntArray("mood"), last.getInt("mood"), 1, "", 0xFF8E2416));
                for (int i = 0; i < charts.size(); i += cols) {
                    List<Chart> line = charts.subList(i, Math.min(charts.size(), i + cols));
                    int fcw = cw;
                    add(y, new Row() {
                        { h = 62; }
                        String draw(GuiGraphics g, int x, int ry, int w, int mx, int my) {
                            String tip = null;
                            for (int k = 0; k < line.size(); k++) {
                                String t = line.get(k).draw(g, x + k * (fcw + UiStyle.S2), ry, fcw, mx, my);
                                if (t != null) tip = t;
                            }
                            return tip;
                        }
                    });
                }
                int[] fr = d.getIntArray("friends");
                add(y, fact("Friends", last.getInt("friends") + (fr.length > 7 ? " (" + UiStyle.signed(last.getInt("friends") - fr[fr.length - 8]) + " this week)" : ""), UiStyle.TEXT));
                if (n < 2) add(y, note("A line grows here each morning it is about: come back in a day or two."));
            }
            case 1 -> {
                ListTag ev = d.getList("events", Tag.TAG_COMPOUND);
                add(y, heading("Its life, newest first"));
                if (ev.isEmpty()) add(y, note("Nothing yet: what happens to it from now on is put down here."));
                for (int i = 0; i < ev.size(); i++) {
                    CompoundTag e = ev.getCompound(i);
                    add(y, event(e.getLong("day"), e.getString("kind"), e.getString("text"), pw));
                }
            }
            default -> {
                CompoundTag self = d.getCompound("self");
                if (!self.isEmpty()) {
                    add(y, heading("Who it is"));
                    ListTag chips = self.getList("chips", Tag.TAG_COMPOUND);
                    add(y, chips(chips, pw));
                    ListTag own = self.getList("own", Tag.TAG_COMPOUND);
                    for (int i = 0; i < own.size(); i++) add(y, fact(own.getCompound(i).getString("k"), capital(own.getCompound(i).getString("v")), UiStyle.TEXT));
                }
                add(y, heading("Work and money"));
                add(y, fact("Trade", last.getString("trade") + (last.getBoolean("child") ? "" : ", level " + last.getInt("level") + " (" + last.getInt("xp_pct") + "% to the next)"), UiStyle.TEXT));
                add(y, fact("Firm", capital(last.getString("firm")), UiStyle.TEXT));
                add(y, fact("Wage", last.getInt("wage") > 0 ? last.getInt("wage") + "c a day" : "", UiStyle.TEXT));
                add(y, fact("Purse", last.getInt("coin") + "c", UiStyle.TEXT));
                add(y, fact("Worth", last.getInt("worth") + "c in all", UiStyle.TEXT));
                add(y, heading("Home and family"));
                add(y, fact("Town", last.getString("town"), UiStyle.TEXT));
                add(y, fact("Home", capital(last.getString("home")), UiStyle.TEXT));
                add(y, fact("Partner", last.getString("partner").isEmpty() ? "none" : last.getString("partner"), UiStyle.TEXT));
                add(y, fact("Children", Integer.toString(last.getInt("kids")), UiStyle.TEXT));
                add(y, fact("Friends", Integer.toString(last.getInt("friends")), UiStyle.TEXT));
                add(y, heading("Last seen"));
                add(y, fact("Day", last.getLong("day") + (d.getBoolean("about") ? ", about now" : ", away since"), UiStyle.TEXT));
                add(y, fact("Where", last.getInt("x") + ", " + last.getInt("y") + ", " + last.getInt("z") + " (" + dimension(last.getString("dim")) + ")", UiStyle.TEXT_MUTED));
                add(y, fact("Health", last.getInt("health") + "%" + (last.getString("ill").isEmpty() ? "" : ", " + last.getString("ill")), UiStyle.levelInk(last.getInt("health"), 60, 35)));
                add(y, fact("Followed", "since day " + d.getLong("first"), UiStyle.TEXT_MUTED));
            }
        }
        pageH = y[0];
    }

    private void add(int[] y, Row r) {
        if (r instanceof Head && y[0] > 0) y[0] += UiStyle.S1;
        r.y = y[0];
        rows.add(r);
        y[0] += r.h + 2;
    }

    private static final class Head extends Row {
        final String text;

        Head(String text) { this.text = text; h = UiStyle.LINE; }

        String draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
            UiStyle.heading(g, Minecraft().font, text, x, y, w);
            return null;
        }
    }

    private static net.minecraft.client.Minecraft Minecraft() {
        return net.minecraft.client.Minecraft.getInstance();
    }

    private Row heading(String text) {
        return new Head(text);
    }

    private Row note(String text) {
        List<FormattedCharSequence> lines = TextCache.splitPlain(font, text, Math.max(40, laidW));
        return new Row() {
            { h = lines.size() * UiStyle.LINE; }
            String draw(GuiGraphics g, int x, int ry, int w, int mx, int my) {
                for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x, ry + i * UiStyle.LINE, UiStyle.TEXT_FAINT, false);
                return null;
            }
        };
    }

    private Row fact(String key, String value, int ink) {
        if (value == null || value.isBlank()) return new Row() { { h = -2; } String draw(GuiGraphics g, int x, int ry, int w, int mx, int my) { return null; } };
        int keyW = Math.min(76, laidW / 3);
        List<FormattedCharSequence> lines = TextCache.splitPlain(font, value, Math.max(40, laidW - keyW));
        return new Row() {
            { h = lines.size() * UiStyle.LINE; }
            String draw(GuiGraphics g, int x, int ry, int w, int mx, int my) {
                g.drawString(font, UiStyle.clip(font, key, keyW - UiStyle.S1), x, ry, UiStyle.TEXT_FAINT, false);
                for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + keyW, ry + i * UiStyle.LINE, ink, false);
                return null;
            }
        };
    }

    private Row chips(ListTag chips, int pw) {
        List<String> texts = new ArrayList<>();
        List<Integer> tints = new ArrayList<>();
        for (int i = 0; i < chips.size(); i++) {
            texts.add(chips.getCompound(i).getString("t"));
            tints.add(chipTint(chips.getCompound(i).getString("k")));
        }
        int rowsN = 1, cx = 0;
        for (String t : texts) {
            int cw = font.width(t) + 8;
            if (cx > 0 && cx + cw > pw) { rowsN++; cx = 0; }
            cx += cw + UiStyle.S1;
        }
        int hh = rowsN * 14;
        return new Row() {
            { h = hh; }
            String draw(GuiGraphics g, int x, int ry, int w, int mx, int my) {
                int at = 0, line = 0;
                for (int i = 0; i < texts.size(); i++) {
                    int cw = font.width(texts.get(i)) + 8;
                    if (at > 0 && at + cw > w) { line++; at = 0; }
                    at += UiStyle.chip(g, font, texts.get(i), x + at, ry + line * 14, tints.get(i)) + UiStyle.S1;
                }
                return null;
            }
        };
    }

    /** A chip's tint by what it says: a trait, the kind of folk, a hobby, a season, a mark. */
    static int chipTint(String kind) {
        return switch (kind) {
            case "trait" -> 0xFF5E9E34;
            case "kind" -> 0xFF4A82C8;
            case "hobby" -> 0xFFB57BA6;
            case "season" -> 0xFFD39B2A;
            default -> 0xFF8B8B8B;
        };
    }

    private Row event(long day, String kind, String text, int pw) {
        String dayS = "Day " + day;
        int dayW = Math.max(46, font.width("Day 0000") + 4);
        List<FormattedCharSequence> lines = TextCache.splitPlain(font, text, Math.max(40, pw - dayW - 24));
        ItemStack icon = FavouritesClient.icon(kind);
        return new Row() {
            { h = Math.max(18, lines.size() * UiStyle.LINE + 6); }
            String draw(GuiGraphics g, int x, int ry, int w, int mx, int my) {
                UiStyle.card(g, x, ry, w, h, kindColour(kind));
                g.renderItem(icon, x + 5, ry + (h - 16) / 2);
                g.drawString(font, dayS, x + 24, ry + 4, UiStyle.TEXT_FAINT, false);
                for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 24 + dayW, ry + 4 + i * UiStyle.LINE, kind.equals("memory") ? UiStyle.TEXT_MUTED : UiStyle.TEXT, false);
                return null;
            }
        };
    }

    private static int kindColour(String kind) {
        return switch (kind) {
            case "level", "raise", "trade", "firm" -> UiStyle.SUCCESS_FILL;
            case "laid_off", "cut", "ill", "died", "widowed" -> UiStyle.DANGER_FILL;
            case "married", "child" -> 0xFFD06A8A;
            case "moved", "well" -> UiStyle.INFO_FILL;
            case "followed" -> UiStyle.STAR;
            default -> UiStyle.EDGE_SOFT;
        };
    }

    /** One chart card: its name, its value now and its change this week, the line, its range under. */
    private final class Chart {
        final String name, unit;
        final int[] values;
        final int now, per, colour;
        final String nowS, weekS, range;
        final int weekInk;

        Chart(String name, int[] values, int now, int per, String unit, int colour) {
            this.name = name;
            this.values = values;
            this.now = now;
            this.per = per;
            this.unit = unit;
            this.colour = colour;
            this.nowS = per == 100 ? "level " + now / 100 : now + unit;
            int week = values.length > 7 ? now - values[values.length - 8] : values.length > 0 ? now - values[0] : 0;
            this.weekS = (per == 100 ? week / 100 == 0 && week != 0 ? (week > 0 ? "+" : "-") + Math.abs(week) + "% of a level" : UiStyle.signed(week / 100) + " levels" : UiStyle.signed(week) + unit)
                + (values.length > 7 ? " this week" : " so far");
            this.weekInk = week > 0 ? UiStyle.SUCCESS : week < 0 ? UiStyle.DANGER : UiStyle.TEXT_FAINT;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int v : values) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            this.range = values.length == 0 ? "" : per == 100 ? "levels " + lo / 100 + "–" + hi / 100 : lo + unit + " – " + hi + unit;
        }

        @Nullable
        String draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
            UiStyle.card(g, x, y, w, 60, colour);
            g.drawString(font, name, x + 6, y + 4, UiStyle.TEXT, false);
            g.drawString(font, nowS, x + w - 5 - font.width(nowS), y + 4, UiStyle.TEXT, false);
            UiStyle.text(g, font, weekS, x + 6, y + 14, w - 12, weekInk);
            UiStyle.lineChart(g, font, x + 5, y + 24, w - 10, 26, values, colour);
            UiStyle.text(g, font, range, x + 6, y + 51, w - 12, UiStyle.TEXT_FAINT);
            if (inside(mx, my, x, y, w, 60)) return name + " each morning it was about (" + values.length + (values.length == 1 ? " day" : " days")
                + "), now " + nowS + (range.isEmpty() ? "" : "; " + range) + ".";
            return null;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int fy = top + h - FOOT_H + 3;
        if (detailId != null && detail != null) {
            boolean dead = detail.getCompound("last").getBoolean("dead");
            if (inside(mx, my, left + UiStyle.S2, fy, 48, 18)) { UiStyle.click(); back(); return true; }
            int rx = left + w - UiStyle.S2, rw = confirmRemove ? 74 : 60;
            rx -= rw;
            if (inside(mx, my, rx, fy, rw, 18)) {
                UiStyle.click();
                if (confirmRemove) {
                    PacketDistributor.sendToServer(new FavouritesAskPayload("remove", detailId, 0));
                    if (detailId.toString().equals(UiConfig.pinned())) UiConfig.setPinned("");
                    back();
                    PacketDistributor.sendToServer(new FavouritesAskPayload("list", FavouritesAskPayload.NOBODY, 1));
                } else confirmRemove = true;
                return true;
            }
            confirmRemove = false;
            rx -= 50 + UiStyle.S1;
            if (inside(mx, my, rx, fy, 50, 18) && !dead) {
                UiStyle.click();
                boolean pinned = detailId.toString().equals(UiConfig.pinned());
                UiConfig.setPinned(pinned ? "" : detailId.toString());
                if (!pinned) UiConfig.setTracker(true);
                return true;
            }
            rx -= 58 + UiStyle.S1;
            if (inside(mx, my, rx, fy, 58, 18) && FolkPortrait.find(detail.getInt("entity"), detailId) != null) {
                UiStyle.click();
                FolkProfileScreen.open(detailId, "about", this);
                return true;
            }
            int ty = top + DETAIL_HEAD + 2, x = left + UiStyle.S2, each = Math.min(86, (w - 2 * UiStyle.S2) / DETAIL_TABS.length);
            for (int i = 0; i < DETAIL_TABS.length; i++, x += each) {
                if (inside(mx, my, x, ty, each - 2, TABS_H - 2)) { pickTab(i); return true; }
            }
            return super.mouseClicked(mx, my, button);
        }
        // The list: the sorts, the cards and their arrows, the footer.
        int sx = left + w - UiStyle.S2;
        Sort[] sorts = Sort.values();
        for (int i = sorts.length - 1; i >= 0; i--) {
            int bw = font.width(sorts[i].label) + 10;
            sx -= bw;
            if (inside(mx, my, sx, top + 7, bw, 16)) {
                UiStyle.click();
                sort = sorts[i];
                resort();
                scroll.reset();
                return true;
            }
            sx -= 2;
        }
        int bx = left + w - UiStyle.S2 - 64;
        if (inside(mx, my, bx, fy, 64, 18)) { UiStyle.click(); if (minecraft != null) minecraft.setScreen(parent); return true; }
        bx -= 84 + UiStyle.S1;
        if (inside(mx, my, bx, fy, 84, 18)) { UiStyle.click(); UiConfig.setTracker(!UiConfig.tracker()); return true; }
        int x = left + UiStyle.S2, y = bodyY(), bh = bodyH(), cw = cardW(), n = cols();
        if (inside(mx, my, x, y, w - 2 * UiStyle.S2, bh)) {
            for (int i = 0; i < cards.size(); i++) {
                int cx = x + (i % n) * (cw + UiStyle.S1), cy = y + (i / n) * (CARD_H + UiStyle.S1) - scroll.offset();
                if (!inside(mx, my, cx, cy, cw, CARD_H)) continue;
                UUID id = cards.get(i).getUUID("uuid");
                if (sort == Sort.ORDER && inside(mx, my, cx + cw - 13, cy + 2, 11, 10)) {
                    UiStyle.click();
                    PacketDistributor.sendToServer(new FavouritesAskPayload("move", id, -1));
                } else if (sort == Sort.ORDER && inside(mx, my, cx + cw - 13, cy + CARD_H - 12, 11, 10)) {
                    UiStyle.click();
                    PacketDistributor.sendToServer(new FavouritesAskPayload("move", id, 1));
                } else {
                    UiStyle.click();
                    openDetail(id);
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void openDetail(UUID id) {
        detailId = id;
        detail = null;
        detailTab = 0;
        scroll.reset();
        PacketDistributor.sendToServer(new FavouritesAskPayload("detail", id, 1));
    }

    private void pickTab(int i) {
        if (i == detailTab) return;
        UiStyle.click();
        detailTab = i;
        scroll.reset();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll.wheel(dy, 3 * UiStyle.LINE);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (detailId != null && detail != null) {
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_3) { pickTab(key - GLFW.GLFW_KEY_1); return true; }
            if (key == GLFW.GLFW_KEY_TAB) { pickTab((detailTab + ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? 2 : 1)) % 3); return true; }
            if (key == GLFW.GLFW_KEY_BACKSPACE) { back(); return true; }
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) { scroll.wheel(-1, 60); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_UP) { scroll.wheel(1, 60); return true; }
        if (key == GLFW.GLFW_KEY_DOWN) { scroll.wheel(-1, UiStyle.LINE); return true; }
        if (key == GLFW.GLFW_KEY_UP) { scroll.wheel(1, UiStyle.LINE); return true; }
        if (FavouritesClient.OPEN.matches(key, scan) && detailId == null) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    // ------------------------------------------------------------------ words

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String dimension(String id) {
        return switch (id) {
            case "minecraft:overworld" -> "the world";
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            default -> id;
        };
    }
}
