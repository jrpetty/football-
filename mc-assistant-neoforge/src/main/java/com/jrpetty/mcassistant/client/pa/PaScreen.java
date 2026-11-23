package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import com.jrpetty.mcassistant.pa.PaClientConfig;
import com.jrpetty.mcassistant.pa.PaUnderstand;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * [pa33] The Player AI Assistant's panel (Y, or right-click it): Talk (a conversation with a text box, suggestion
 * chips and a microphone), Orders (one click for the common jobs, each with a small picker for how many and where),
 * Jobs (the live list with progress and times; reorder, cancel, pause), Pack (what it carries; its pack and gear) and
 * About (its level, skills and record, and every setting). With no assistant yet it offers one. Fits every GUI scale
 * from a 427x240 screen up.
 */
public class PaScreen extends Screen {

    static final int BRAND = 0xFF1F8A7A, BRAND_LIGHT = 0xFF2BB5A0, BRAND_WASH = 0xFFD7EEEA, HEADER = 0xFF173B40;
    private static final String[] TABS = {"talk", "orders", "jobs", "pack", "stats", "look", "settings"};          // [pa33c-2b]
    private static final String[] TAB_LABELS = {"Talk", "Orders", "Jobs", "Pack", "Stats", "Look", "Settings"};
    /** [pa33b] The personality triangle (About) and the look preview's turn (Look). */
    private final PaTriangle triangle = new PaTriangle();
    private float lookYaw;
    private boolean turning;
    private double turnFrom;

    /** True while the portrait draws (its plate and bubble belong to the world, not the frame). */
    static boolean drawingPortrait;

    private String tab;
    private int left, top, w, h, seen = -1;
    private EditBox input, nameBox;
    private int scroll, contentH, logScroll;
    private boolean stickBottom = true;
    private String picker = "";
    private final List<String> pick = new ArrayList<>();       // the picker's choices so far
    private boolean confirmDismiss;
    private int tour = -1;

    /** Click zones laid down while drawing: x, y, w, h, what to do, and a tooltip. */
    private record Zone(int x, int y, int w, int h, Runnable run, String tip) {}

    private final List<Zone> zones = new ArrayList<>();
    private String tipNow = "";

    public PaScreen(String tab) {
        super(Component.literal("Player AI Assistant"));
        this.tab = tab == null || tab.isEmpty() ? "talk" : tab;
        if (this.tab.equals("last")) this.tab = PaClientConfig.get(PaClientConfig.LASTTAB, "talk");   // [pa33c-2b] where you left it
        if (this.tab.equals("about")) this.tab = "settings";
        if (java.util.Arrays.stream(TABS).noneMatch(this.tab::equals)) this.tab = "talk";
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        String size = PaClientConfig.get(PaClientConfig.UISCALE, "m");                 // [pa33c-2b] Settings, Look: panel size
        w = com.jrpetty.mcassistant.pa.PaLayout.panelW(this.width, size);
        h = com.jrpetty.mcassistant.pa.PaLayout.panelH(this.height, size);
        left = (this.width - w) / 2;
        top = (this.height - h) / 2;
        String keep = input == null ? "" : input.getValue();
        PaPingClient.holdForTyping();                              // [pa33c-2b] a fresh ping waits while you type
        input = new EditBox(font, left + 10, top + h - 34, w - 104, 16, Component.literal("Talk to your assistant"));
        input.setMaxLength(240);
        input.setHint(Component.literal("Say anything: \"get me 16 logs\", \"build a small house here\"..."));
        input.setValue(keep);
        input.setBordered(true);
        addWidget(input);
        nameBox = new EditBox(font, 0, 0, 90, 14, Component.literal("Name"));
        nameBox.setMaxLength(16);
        addWidget(nameBox);
        if (tab.equals("talk")) setInitialFocus(input);
    }

    void startTour() {
        tour = 0;
        tab = "talk";
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x70101014);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);
        zones.clear();
        tipNow = "";
        CompoundTag s = PaClient.state;
        if (seen != PaClient.stamp) {
            seen = PaClient.stamp;
            if (stickBottom) logScroll = Integer.MAX_VALUE;
            if (nameBox.getValue().isEmpty()) nameBox.setValue(s.getString("name"));
        }
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        input.visible = false;
        nameBox.visible = false;
        if (!s.getBoolean("has")) {
            welcome(g, mx, my);
            footer(g, mx, my);
            tooltip(g, mx, my);
            return;
        }
        header(g, s, mx, my);
        int by = top + 44, bh = h - 44 - 14;
        if (s.getBoolean("dead")) {
            g.fill(left + 6, by, left + w - 6, by + 14, 0xFFEACBC2);
            g.drawString(font, UiStyle.clip(font, s.getString("name") + " is knocked out: back in " + s.getInt("back") + "s. A whistle brings them back now.", w - 20),
                left + 10, by + 3, UiStyle.DANGER, false);
            by += 16;
            bh -= 16;
        }
        switch (tab) {
            case "talk" -> talk(g, s, left + 6, by, w - 12, bh, mx, my);
            case "orders" -> orders(g, s, left + 6, by, w - 12, bh, mx, my);
            case "jobs" -> scrolled(g, by, bh, mx, my, (y) -> jobs(g, s, left + 8, y, w - 22, mx, my));
            case "pack" -> scrolled(g, by, bh, mx, my, (y) -> pack(g, s, left + 8, y, w - 22, mx, my));
            case "stats" -> scrolled(g, by, bh, mx, my, (y) -> stats(g, s, left + 8, y, w - 22, mx, my));
            case "settings" -> {
                int sw = com.jrpetty.mcassistant.pa.PaLayout.sectionListW(w);
                sections(g, left + 6, by, sw, bh, mx, my);
                scrolledIn(g, left + 8 + sw, by, w - 14 - sw, bh, mx, my, (y) -> settings(g, s, left + 14 + sw, y, w - 30 - sw, mx, my));
            }
            case "look" -> scrolled(g, by, bh, mx, my, (y) -> looks(g, s, left + 8, y, w - 22, mx, my));
            default -> { }
        }
        footer(g, mx, my);
        if (tour >= 0) tourOverlay(g, mx, my);
        if (drag != null && !dragStack.isEmpty()) {                 // [pa33c-2b] the thing being dragged, under the pointer
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.renderItem(dragStack, mx - 8, my - 8);
            g.renderItemDecorations(font, dragStack, mx - 8, my - 8);
            g.pose().popPose();
        } else if (!hoverItem.isEmpty()) {
            g.renderTooltip(font, hoverItem, mx, my);
            hoverItem = net.minecraft.world.item.ItemStack.EMPTY;
            return;
        }
        tooltip(g, mx, my);
    }

    private void header(GuiGraphics g, CompoundTag s, int mx, int my) {
        g.fill(left + 1, top + 1, left + w - 1, top + 26, HEADER);
        g.fill(left + 1, top + 26, left + w - 1, top + 27, BRAND_LIGHT);
        // portrait
        PlayerAssistantEntity a = PaClient.mine();
        int px = left + 4, py = top + 3;
        g.fill(px, py, px + 22, py + 22, 0xFF0E2A2E);
        if (a != null) {
            drawingPortrait = true;
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, px, py, px + 22, py + 22, 10, 0.0625F, mx, my, a);
            } finally {
                drawingPortrait = false;
            }
        } else {
            g.drawCenteredString(font, s.getString("name").isEmpty() ? "?" : s.getString("name").substring(0, 1), px + 11, py + 7, BRAND_LIGHT);
        }
        g.drawString(font, s.getString("name"), left + 30, top + 4, 0xFFFFFFFF, true);
        String sub = "Player AI Assistant";
        String state = s.getString("state");
        if (a != null) state = a.state();
        String[] f = state.split("\\|", -1);
        if (f.length > 1 && !f[1].isEmpty()) sub = f[1];
        g.drawString(font, UiStyle.clip(font, sub, w - 150), left + 30, top + 15, 0xFF9FD8CE, false);
        // health
        float hp = a != null ? a.getHealth() : s.getFloat("hp"), max = a != null ? a.getMaxHealth() : Math.max(1, s.getFloat("maxHp"));
        // [pa33c-2b] hearts and hunger as a player's, from its right
        int food = a != null ? a.food() : s.getInt("hunger");
        int bx = left + w - 24;
        PaHud.hearts(g, bx, top + 4, hp, max);
        PaHud.food(g, bx, top + 15, food);
        if (in(mx, my, bx - 80, top + 3, 80, 22))
            g.renderTooltip(font, net.minecraft.network.chat.Component.literal((int) Math.ceil(hp) + " of " + (int) max + " health, food " + food + " of 20"), mx, my);
        button(g, "✕", left + w - 20, top + 6, 14, 14, mx, my, true, false, "Close (Esc)", this::onClose);
        // tabs
        int tx = left + 6;
        for (int i = 0; i < TABS.length; i++) {
            String label = TAB_LABELS[i];
            if (TABS[i].equals("jobs")) {
                int n = s.getList("steps", Tag.TAG_COMPOUND).size();
                if (n > 0) label += " (" + n + ")";
            }
            int tw = font.width(label) + 14;
            boolean on = tab.equals(TABS[i]);
            boolean over = in(mx, my, tx, top + 29, tw, 14);
            UiStyle.topTab(g, tx, top + 29, tw, 14, UiStyle.State.of(true, on, over));
            g.drawString(font, label, tx + 7, top + 32, on ? UiStyle.TEXT : muted(), false);
            final String t = TABS[i];
            zone(tx, top + 29, tw, 14, () -> { tab = t; scroll = 0; picker = ""; pick.clear(); confirmDismiss = false; PaClientConfig.set(PaClientConfig.LASTTAB, t); }, "");
            tx += tw + 2;
        }
    }

    private void footer(GuiGraphics g, int mx, int my) {
        String msg;
        if (!PaClient.note.isEmpty() && System.currentTimeMillis() - PaClient.noteAt < 6000) msg = PaClient.note;
        else if (!PaVoice.status.isEmpty() && System.currentTimeMillis() - PaVoice.statusAt < 6000) msg = PaVoice.status;
        else msg = PaClient.PANEL.getTranslatedKeyMessage().getString() + " opens this  ·  hold " + PaClient.TALK.getTranslatedKeyMessage().getString()
            + " to talk  ·  " + PaClient.WHEEL.getTranslatedKeyMessage().getString() + " action wheel" + (PaClient.has() ? "  ·  right-click it" : "");
        g.drawString(font, UiStyle.clip(font, msg, w - 16), left + 8, top + h - 11, faint(), false);
    }

    // ------------------------------------------------------------------ no assistant yet

    private void welcome(GuiGraphics g, int mx, int my) {
        g.fill(left + 1, top + 1, left + w - 1, top + 26, HEADER);
        g.drawString(font, "Meet your Player AI Assistant", left + 10, top + 9, 0xFFFFFFFF, true);
        button(g, "✕", left + w - 20, top + 6, 14, 14, mx, my, true, false, "Close", this::onClose);
        int y = top + 34, x = left + 12, iw = w - 24;
        y = para(g, "A companion of your own. It follows you, talks with you, and does what a player does: chops, mines, farms, "
            + "crafts, smelts, builds from the mod's drawings, fights, fetches and tidies. Tell it what you want in plain words, "
            + "click an order, or hold a key and say it.", x, y, iw, UiStyle.TEXT);
        y += 6;
        boolean free = PaClient.state.getBoolean("free");
        if (free) {
            button(g, "Get my assistant", left + (w - 140) / 2, y, 140, 20, mx, my, true, true,
                "Your assistant appears beside you, yours for good", () -> { PaClient.send("summon", new CompoundTag()); onClose(); });
            y += 28;
        }
        UiStyle.heading(g, font, "Other ways to get one", x, y, iw);
        y += 13;
        y = para(g, "• Craft an Assistant Whistle (4 iron, 4 redstone, a gold ingot) and use it on the ground.", x, y, iw, muted());
        y = para(g, "• Craft an Assistant Spawner (8 rotten flesh round a diamond), place it and click it: it becomes your assistant's home.", x, y, iw, muted());
        y += 4;
        button(g, "Ask the Guide", x, y, 90, 16, mx, my, true, false, "The Guide's article on the assistant",
            () -> com.jrpetty.mcassistant.client.guide.GuideClient.openAt(this, "pa_getting"));
        y += 24;
        if (y + 40 < top + h - 14) {
            UiStyle.heading(g, font, "Things you can say to it", x, y, iw);
            y += 13;
            for (String ex : new String[]{"\"Follow me\"   \"Get me 16 logs\"   \"Build a small house here\"",
                    "\"Mine iron until you have 20\"   \"Guard me\"   \"What are you doing?\""}) {
                g.drawString(font, UiStyle.clip(font, ex, iw), x, y, BRAND, false);
                y += 11;
            }
        }
    }

    // ------------------------------------------------------------------ talk

    private void talk(GuiGraphics g, CompoundTag s, int x, int y, int iw, int ih, int mx, int my) {
        int logH = ih - 40;
        UiStyle.well(g, x, y, iw, logH, 0xFFE9EEEC);
        // the conversation, newest at the bottom
        ListTag log = s.getList("log", Tag.TAG_COMPOUND);
        int bubbleW = Math.max(120, iw * 7 / 10);
        List<Object[]> laid = new ArrayList<>();
        int total = 4;
        for (Tag t : log) {
            CompoundTag l = (CompoundTag) t;
            String said = l.getString("t") + (l.getInt("n") > 1 ? "  \u00D7" + l.getInt("n") : "");   // [pa33b] said again: ×2
            List<FormattedCharSequence> lines = font.split(Component.literal(said), bubbleW - 10);
            laid.add(new Object[]{l.getInt("w"), lines});
            total += lines.size() * 10 + 8 + (l.getInt("w") == 1 ? 0 : 0);
        }
        int maxScroll = Math.max(0, total - logH + 4);
        logScroll = Math.max(0, Math.min(logScroll, maxScroll));
        stickBottom = logScroll >= maxScroll;
        g.enableScissor(x + 1, y + 1, x + iw - 1, y + logH - 1);
        int cy = y + 4 - logScroll;
        if (log.isEmpty()) {
            centred(g, "Say hello, or try one of the suggestions below.", x + iw / 2, y + logH / 2 - 4, faint());
        }
        for (Object[] o : laid) {
            int who = (Integer) o[0];
            @SuppressWarnings("unchecked") List<FormattedCharSequence> lines = (List<FormattedCharSequence>) o[1];
            int lw = 0;
            for (FormattedCharSequence l : lines) lw = Math.max(lw, font.width(l));
            int bw2 = lw + 10, bh2 = lines.size() * 10 + 4;
            int bx = who == 0 ? x + iw - bw2 - 6 : x + 6;
            int fill = who == 0 ? UiStyle.BUBBLE_YOU : who == 1 ? UiStyle.BUBBLE_THEM : 0xFFE4E4E4;
            if (cy + bh2 > y && cy < y + logH) {
                g.fill(bx, cy, bx + bw2, cy + bh2, fill);
                g.fill(bx, cy, bx + 2, cy + bh2, who == 0 ? UiStyle.ACCENT_FILL : who == 1 ? BRAND_LIGHT : 0xFF999999);
                for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), bx + 6, cy + 2 + i * 10, UiStyle.TEXT, false);
            }
            cy += bh2 + 4;
        }
        g.disableScissor();
        if (maxScroll > 0) UiStyle.scrollBar(g, x + iw - 5, y + 1, logH - 2, logScroll, total, logH);
        // chips: its question's choices, else suggestions for now
        List<String[]> chips = new ArrayList<>(PaClient.choices);
        if (chips.isEmpty() && s.contains("ask")) {
            for (Tag t : s.getList("askChoices", Tag.TAG_COMPOUND)) chips.add(new String[]{((CompoundTag) t).getString("l"), ((CompoundTag) t).getString("s")});
        }
        boolean asked = !chips.isEmpty();
        if (chips.isEmpty()) chips = PaChips.forNow();
        int chipY = y + logH + 3, chipX = x;
        for (String[] c : chips) {
            int cw = font.width(c[0]) + 10;
            if (chipX + cw > x + iw) break;
            boolean over = in(mx, my, chipX, chipY, cw, 13);
            g.fill(chipX, chipY, chipX + cw, chipY + 13, over ? BRAND_LIGHT : asked ? BRAND : 0xFFBFD9D4);
            g.drawString(font, c[0], chipX + 5, chipY + 3, over || asked ? 0xFFFFFFFF : UiStyle.TEXT, false);
            final String say = c[1];
            zone(chipX, chipY, cw, 13, () -> chip(say), asked ? "" : "Say: \"" + say + "\"");
            chipX += cw + 4;
        }
        // the box, send and the microphone
        input.visible = true;
        input.setX(x + 2);
        input.setY(y + ih - 19);
        input.setWidth(iw - 100);
        String pinged = PaPingClient.chip(minecraft);             // [pa33c-2b] a ping waiting: "that" in what you type is it
        input.setHint(pinged != null ? Component.literal(UiStyle.clip(font, pinged, iw - 110)).withStyle(st -> st.withColor(0x2B8A7A))
            : Component.literal("Say anything: \"get me 16 logs\", \"build a small house here\"..."));
        input.render(g, mx, my, 0);
        String typed = input.getValue();
        if (!typed.isEmpty() && input.isFocused()) {
            List<String> sug = PaUnderstand.complete(typed, 3);
            if (!sug.isEmpty() && sug.get(0).startsWith(typed.toLowerCase())) {
                String rest = sug.get(0).substring(typed.length());
                g.drawString(font, rest, input.getX() + 4 + font.width(typed), input.getY() + 4, 0xFF9A9A9A, false);
            }
            int sy = input.getY() - 2 - sug.size() * 12;
            for (int i = 0; i < sug.size(); i++) {
                int sw = font.width(sug.get(i)) + 10;
                boolean over = in(mx, my, input.getX(), sy + i * 12, sw, 12);
                g.fill(input.getX(), sy + i * 12, input.getX() + sw, sy + i * 12 + 12, over ? 0xFFF4F4F4 : 0xF0FFFFFF);
                g.drawString(font, sug.get(i), input.getX() + 5, sy + i * 12 + 2, muted(), false);
                final String v = sug.get(i);
                zone(input.getX(), sy + i * 12, sw, 12, () -> { input.setValue(v); input.moveCursorToEnd(false); }, "");
            }
        }
        button(g, "Send", x + iw - 94, y + ih - 19, 40, 16, mx, my, !typed.isBlank(), true, "Send (Enter)", this::sendTyped);
        boolean listening = PaVoice.listening;
        button(g, listening ? "\u25A0 Send" : "\u25CF Speak", x + iw - 50, y + ih - 19, 50, 16, mx, my, true, listening,
            listening ? "Stop listening and send" : "Click, speak, click again (or hold " + PaClient.TALK.getTranslatedKeyMessage().getString() + " outside this panel)",
            () -> { if (PaVoice.listening) PaVoice.stopListening(); else PaVoice.startListening(); });
        if (listening && !PaVoice.transcript.isEmpty()) {
            g.drawString(font, UiStyle.clip(font, "“" + PaVoice.transcript + "”", iw - 100), x + 4, y + logH - 12, BRAND, false);
        }
    }

    private void chip(String say) {
        if (say.startsWith("#change ")) {
            input.setValue(say.substring(8));
            input.moveCursorToEnd(false);
            setFocused(input);
            PaClient.choices = new ArrayList<>();
            return;
        }
        if (say.startsWith("#guide:")) {
            String id = say.substring(7);
            if (id.isEmpty()) com.jrpetty.mcassistant.client.guide.GuideClient.open(this);
            else com.jrpetty.mcassistant.client.guide.GuideClient.openAt(this, id);
            return;
        }
        if (say.equals("#tour")) {
            startTour();
            return;
        }
        PaClient.talk(say, false);
        stickBottom = true;
        logScroll = Integer.MAX_VALUE;
    }

    private void sendTyped() {
        String t = input.getValue().trim();
        if (t.isEmpty()) return;
        PaClient.talk(t, false);
        input.setValue("");
        stickBottom = true;
        logScroll = Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------------ orders

    /** label, picker key ("" = at once), what it says at once / the picker's phrase, tooltip. */
    private static final String[][] ORDERS = {
        {"Follow me", "", "follow me", "It follows you and fights what comes at you"},
        {"Stay here", "", "stay here", "It waits where it stands"},
        {"Come here", "", "come here", "It comes to you (from anywhere)"},
        {"Guard me", "", "guard me", "It sticks close and attacks anything hostile near you"},
        {"Guard here", "guardhere", "", "It holds a post and guards it"},
        {"Get wood", "wood", "", "It chops trees (and makes an axe if it must)"},
        {"Get stone", "stone", "", "It mines stone (making a pickaxe first if it has none)"},
        {"Mine ore", "ore", "", "It digs a mine to the right depth for the ore and follows the veins"},
        {"Mine down", "down", "", "It digs a staircase mine down to a level"},
        {"Farm", "farm", "", "Harvest and replant, or lay out a new field"},
        {"Build", "build", "", "A building from the mod's drawings, where you choose"},
        {"Craft", "craft", "", "It makes it from scratch: gathers, smelts and crafts the whole chain"},
        {"Smelt", "smelt", "", "It finds a furnace (or makes one) and smelts with fuel"},
        {"Deposit", "deposit", "", "It puts its pack away in a chest"},
        {"Fight", "fight", "", "It attacks"},
        {"Bring food", "", "bring me food", "It brings you food (cooking or hunting some if it has none)"},
        {"Light up", "", "light up the area", "It places torches round about (making them if it can)"},
        {"Sort chests", "", "sort the chests", "It tidies the chests near by"},
        {"Go home", "", "go home", "It walks home (its spawner, or where you set it)"},
        {"Quiet", "", "be quiet", "It stops chatting and joking: answers and warnings only (click again to let it talk)"},   // [pa33b]
        {"Stop", "", "stop", "It stops everything at once"},
    };

    private void orders(GuiGraphics g, CompoundTag s, int x, int y, int iw, int ih, int mx, int my) {
        int cols = iw >= 380 ? 5 : 4;
        int tw = (iw - (cols - 1) * 4) / cols, th = 16;
        int rows = (ORDERS.length + cols - 1) / cols;
        boolean hushed = s.getCompound("set").getInt("mute") > 0;
        for (int i = 0; i < ORDERS.length; i++) {
            String[] o = ORDERS[i];
            if (o[0].equals("Quiet") && hushed) o = new String[]{"Talk again", "", "you can talk again", "It chats and jokes again"};
            final String[] oo = o;
            int cx = x + (i % cols) * (tw + 4), cy = y + 2 + (i / cols) * (th + 3);
            boolean on = picker.equals(o[1]) && !o[1].isEmpty();
            boolean danger = o[0].equals("Stop");
            boolean over = in(mx, my, cx, cy, tw, th);
            g.fill(cx, cy, cx + tw, cy + th, on ? BRAND : over ? 0xFFE6F2EF : danger ? 0xFFEFD3CC : 0xFFDCE6E4);
            g.fill(cx, cy + th - 1, cx + tw, cy + th, on ? BRAND_LIGHT : 0xFF9DB5B0);
            PaIcons.draw(g, iconFor(o[0]), cx + 2, cy + 2);
            g.drawString(font, UiStyle.clip(font, o[0], tw - 18), cx + 16, cy + 4, on ? 0xFFFFFFFF : UiStyle.TEXT, false);
            zone(cx, cy, tw, th, () -> {
                if (oo[1].isEmpty()) { PaClient.talk(oo[2], false); tab = "talk"; }
                else { picker = picker.equals(oo[1]) ? "" : oo[1]; pick.clear(); defaults(); }
            }, oo[3]);
        }
        int py = y + 2 + rows * (th + 3) + 4;
        if (picker.isEmpty()) {
            para(g, "Click an order. The ones with choices open a picker here. Everything you click shows in Talk, where it answers.", x + 2, py + 2, iw - 4, faint());
            return;
        }
        UiStyle.well(g, x, py, iw, y + ih - py - 2, 0xFFEFF5F4);
        pickerBody(g, x + 6, py + 4, iw - 12, mx, my);
    }

    private static String iconFor(String label) {
        return switch (label) {
            case "Follow me" -> "follow"; case "Stay here" -> "stay"; case "Come here" -> "follow"; case "Guard me", "Guard here" -> "guard";
            case "Get wood" -> "gather"; case "Get stone", "Mine ore", "Mine down" -> "mine_ore"; case "Farm" -> "farm";
            case "Build" -> "build"; case "Craft" -> "craft"; case "Smelt" -> "smelt"; case "Deposit" -> "deposit";
            case "Fight" -> "fight"; case "Bring food" -> "bring_food"; case "Light up" -> "light_area"; case "Sort chests" -> "sort";
            case "Go home" -> "go_home"; case "Stop" -> "paused";
            default -> "";
        };
    }

    /** A picker: rows of choices; the order it makes shows, with Go. */
    private void pickerBody(GuiGraphics g, int x, int y, int iw, int mx, int my) {
        String[][] rows;
        switch (picker) {
            case "wood" -> rows = new String[][]{{"How many logs", "8", "16", "32", "64"}};
            case "stone" -> rows = new String[][]{{"How much stone", "16", "32", "64", "128"}};
            case "ore" -> rows = new String[][]{{"Which ore", "iron", "coal", "copper", "gold", "redstone", "diamond"}, {"Until it has", "8", "16", "32"}};
            case "down" -> rows = new String[][]{{"Down to", "Y 16 (iron)", "Y 0", "Y -16 (gold)", "Y -58 (diamond)"}};
            case "farm" -> rows = new String[][]{{"What", "tend the crops", "farm where I look", "new field here"}};
            case "build" -> rows = new String[][]{{"What", "small house", "shelter", "farm", "storage", "workshop", "smeltery", "watchtower"},
                {"Where", "in front of me", "where I'm looking", "by my home"}};
            case "craft" -> rows = new String[][]{{"What", "stone pickaxe", "iron pickaxe", "stone sword", "stone axe", "torches", "chest", "furnace",
                "crafting table", "planks", "bread", "bucket"}, {"How many", "1", "4", "16"}};
            case "smelt" -> rows = new String[][]{{"What", "iron", "gold", "sand into glass", "logs into charcoal", "beef", "porkchop"}, {"How many", "8", "16", "32"}};
            case "deposit" -> rows = new String[][]{{"Where", "the nearest chest", "the chest I'm looking at"}};
            case "fight" -> rows = new String[][]{{"What", "the nearest monster", "what I'm looking at"}};
            case "guardhere" -> rows = new String[][]{{"What", "this spot", "the farm", "my base"}};
            default -> rows = new String[0][];
        }
        int cy = y;
        for (int r = 0; r < rows.length; r++) {
            String[] row = rows[r];
            g.drawString(font, row[0], x, cy + 3, muted(), false);
            int cx = x + 76;
            for (int i = 1; i < row.length; i++) {
                int bw = font.width(row[i]) + 10;
                if (cx + bw > x + iw) { cx = x + 76; cy += 15; }
                boolean on = pick.size() > r && pick.get(r).equals(row[i]);
                final int rr = r;
                final String v = row[i];
                button(g, row[i], cx, cy, bw, 13, mx, my, true, on, "", () -> {
                    while (pick.size() <= rr) pick.add("");
                    pick.set(rr, v);
                });
                cx += bw + 3;
            }
            cy += 17;
        }
        String say = phrase();
        if (say != null) {
            g.drawString(font, UiStyle.clip(font, "“" + say + "”", iw - 60), x, cy + 4, BRAND, false);
            button(g, "Go", x + iw - 50, cy, 50, 15, mx, my, true, true, "Send this order", () -> {
                PaClient.talk(say, false);
                picker = "";
                pick.clear();
                tab = "talk";
            });
        } else {
            g.drawString(font, "Pick one from each row.", x, cy + 4, faint(), false);
        }
    }

    /** The sensible choice already picked on a picker's later rows (how many, where): one click less. */
    private void defaults() {
        String second = switch (picker) {
            case "ore" -> "8";
            case "build" -> "in front of me";
            case "craft" -> "1";
            case "smelt" -> "8";
            default -> null;
        };
        if (second != null) { pick.add(""); pick.add(second); }
    }

    /** The order the picker makes, or null while a row is unpicked. */
    private String phrase() {
        String a = pick.isEmpty() ? null : pick.get(0);
        String b = pick.size() > 1 ? pick.get(1) : null;
        if (a == null || a.isEmpty()) return null;
        return switch (picker) {
            case "wood" -> "gather " + a + " logs";
            case "stone" -> "gather " + a + " stone";
            case "ore" -> b == null || b.isEmpty() ? null : (a.equals("diamond") ? "mine for diamonds until you have " + b : "mine " + a + " until you have " + b);
            case "down" -> "dig a mine down to level " + a.replaceAll("^Y (-?\\d+).*$", "$1");
            case "farm" -> a.equals("tend the crops") ? "tend the farm" : a.equals("farm where I look") ? "farm this field" : "build a farm here";
            case "build" -> b == null || b.isEmpty() ? null : (a.equals("farm") ? "build a farm" : "build a " + a)
                + (b.equals("in front of me") ? " here" : b.equals("where I'm looking") ? " over there" : " by my house");
            case "craft" -> b == null || b.isEmpty() ? null : "craft " + (b.equals("1") ? (a.endsWith("s") ? "" : "a ") : b + " ") + a;
            case "smelt" -> b == null || b.isEmpty() ? null : "smelt " + b + " " + a.split(" ")[0];
            case "deposit" -> a.startsWith("the nearest") ? "deposit your items" : "put everything in that chest";
            case "fight" -> a.startsWith("the nearest") ? "fight the monsters" : "attack that";
            case "guardhere" -> a.equals("this spot") ? "guard here" : a.equals("the farm") ? "guard the farm" : "guard the base";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ jobs

    private int jobs(GuiGraphics g, CompoundTag s, int x, int y, int iw, int mx, int my) {
        ListTag steps = s.getList("steps", Tag.TAG_COMPOUND);
        boolean paused = s.getBoolean("paused");
        int cy = y;
        button(g, paused ? "▶ Resume" : "⏸ Pause", x, cy, 64, 14, mx, my, !steps.isEmpty(), paused, paused ? "Carry on" : "Hold the list; it waits",
            () -> job(paused ? "resume" : "pause", 0));
        button(g, "Clear all", x + 68, cy, 56, 14, mx, my, !steps.isEmpty(), false, "Stop and forget the whole list", () -> job("clear", 0));
        button(g, "Undo last order", x + 128, cy, 86, 14, mx, my, true, false, "Put the list back as it was before your last order",
            () -> PaClient.talk("undo", false));
        cy += 20;
        if (steps.isEmpty()) {
            UiStyle.empty(g, font, "Nothing on the list", "Give it something to do in Talk or Orders.", x, cy, iw, 50);
            return cy + 54 - y;
        }
        for (int i = 0; i < steps.size(); i++) {
            CompoundTag st = steps.getCompound(i);
            boolean now = st.getBoolean("now");
            int rh = 26;
            UiStyle.card(g, x, cy, iw, rh, now ? BRAND_LIGHT : st.getBoolean("auto") ? 0xFFB0B0B0 : 0xFF8FB0AA);
            PaIcons.draw(g, st.getString("k").toLowerCase(), x + 5, cy + 3);
            String label = PaNetLabel.cap(st.getString("l")) + (st.getBoolean("auto") ? " (on its own)" : "");
            g.drawString(font, UiStyle.clip(font, label, iw - 110), x + 20, cy + 3, UiStyle.TEXT, false);
            int pct = st.getInt("pct");
            String tally = st.getString("tally");
            String eta = now ? "about " + PaNetLabel.time(st.getInt("eta")) + " left" : "about " + PaNetLabel.time(st.getInt("eta"));
            g.drawString(font, (now ? (paused ? "Paused" : "Now") : "Next") + " · " + eta + (tally.isEmpty() ? "" : " · " + tally),
                x + 20, cy + 14, faint(), false);
            if (now && pct >= 0) UiStyle.bar(g, x + iw - 104, cy + 16, 50, 4, pct / 100F, BRAND_LIGHT);
            final int idx = i;
            button(g, "▲", x + iw - 50, cy + 5, 14, 14, mx, my, i > 0, false, "Sooner", () -> job("up", idx));
            button(g, "▼", x + iw - 34, cy + 5, 14, 14, mx, my, i < steps.size() - 1, false, "Later", () -> job("down", idx));
            button(g, "✕", x + iw - 18, cy + 5, 14, 14, mx, my, true, false, "Cancel this one", () -> job("cancel", idx));
            cy += rh + 3;
        }
        return cy - y;
    }

    private void job(String op, int i) {
        CompoundTag a = new CompoundTag();
        a.putString("op", op);
        a.putInt("i", i);
        PaClient.send("job", a);
    }

    // ------------------------------------------------------------------ pack

    // ------------------------------------------------------------------ [pa33c-2b] the Pack tab: it in 3D, its gear, its pack, yours

    /** A slot laid down while drawing: its kind ('a' its pack, 'p' your inventory, 'e' its gear), index or gear name, and where. */
    private record Slot(char kind, int i, String e, int x, int y) {}

    private final List<Slot> slots = new ArrayList<>();
    private final java.util.Map<Integer, net.minecraft.world.item.ItemStack> packItems = new java.util.HashMap<>();
    private final java.util.Map<String, net.minecraft.world.item.ItemStack> gearItems = new java.util.HashMap<>();
    private int packStamp = -1;
    private Slot drag;
    private net.minecraft.world.item.ItemStack dragStack = net.minecraft.world.item.ItemStack.EMPTY;
    private net.minecraft.world.item.ItemStack hoverItem = net.minecraft.world.item.ItemStack.EMPTY;
    private boolean lockMode;
    private static final String[] GEAR = {"head", "chest", "legs", "feet", "mainhand", "offhand"};
    private static final String[] GEAR_TIP = {"Head", "Body", "Legs", "Feet", "Main hand", "Off hand"};

    private void readPack(CompoundTag s) {
        if (packStamp == PaClient.stamp || minecraft == null || minecraft.level == null) return;
        packStamp = PaClient.stamp;
        packItems.clear();
        gearItems.clear();
        var reg = minecraft.level.registryAccess();
        for (Tag t : s.getList("slots", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            packItems.put(c.getInt("i"), net.minecraft.world.item.ItemStack.parseOptional(reg, c.getCompound("s")));
        }
        CompoundTag gear = s.getCompound("gear");
        for (String k : gear.getAllKeys()) gearItems.put(k, net.minecraft.world.item.ItemStack.parseOptional(reg, gear.getCompound(k)));
    }

    private net.minecraft.world.item.ItemStack stackOf(Slot sl) {
        return switch (sl.kind()) {
            case 'a' -> packItems.getOrDefault(sl.i(), net.minecraft.world.item.ItemStack.EMPTY);
            case 'e' -> gearItems.getOrDefault(sl.e(), net.minecraft.world.item.ItemStack.EMPTY);
            default -> minecraft.player == null ? net.minecraft.world.item.ItemStack.EMPTY : minecraft.player.getInventory().getItem(sl.i());
        };
    }

    private boolean kept(CompoundTag s, net.minecraft.world.item.ItemStack st) {
        if (st.isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        for (Tag t : s.getList("keep", Tag.TAG_STRING)) if (t.getAsString().equals(id)) return true;
        return false;
    }

    /** One slot: frame, the item and its count, a lock for a closed row, a mark on a kept item. */
    private void slot(GuiGraphics g, CompoundTag s, Slot sl, boolean locked, String empty, int mx, int my) {
        slots.add(sl);
        int x = sl.x(), y = sl.y();
        boolean over = in(mx, my, x, y, 18, 18);
        boolean target = drag != null && over && !locked;
        g.fill(x, y, x + 18, y + 18, target ? BRAND_LIGHT : 0xFF7A7A7A);
        g.fill(x + 1, y + 1, x + 17, y + 17, locked ? 0xFFB4B4B4 : over ? 0xFFF7F7F7 : 0xFFE2E2E2);
        net.minecraft.world.item.ItemStack st = stackOf(sl);
        boolean dragged = drag != null && drag.kind() == sl.kind() && drag.i() == sl.i() && java.util.Objects.equals(drag.e(), sl.e());
        if (!st.isEmpty() && !dragged) {
            g.renderItem(st, x + 1, y + 1);
            g.renderItemDecorations(font, st, x + 1, y + 1);
            if (kept(s, st)) { g.fill(x + 12, y + 1, x + 17, y + 6, BRAND); g.fill(x + 13, y + 2, x + 16, y + 5, 0xFFFFFFFF); g.fill(x + 14, y + 3, x + 15, y + 4, BRAND); }
            if (over && drag == null) hoverItem = st;
        } else if (st.isEmpty() && !empty.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x + 9, y + 7, 0);
            g.pose().scale(0.5F, 0.5F, 1);
            g.drawCenteredString(font, empty, 0, 0, 0xFF8A8A8A);
            g.pose().popPose();
        }
        if (locked) {                                               // a closed row: a little padlock
            g.fill(x + 6, y + 8, x + 12, y + 13, 0xFF6A6A6A);
            g.fill(x + 7, y + 5, x + 11, y + 6, 0xFF6A6A6A);
            g.fill(x + 7, y + 5, x + 8, y + 8, 0xFF6A6A6A);
            g.fill(x + 10, y + 5, x + 11, y + 8, 0xFF6A6A6A);
        }
    }

    private int pack(GuiGraphics g, CompoundTag s, int x, int y, int iw, int mx, int my) {
        readPack(s);
        slots.clear();
        int open = s.contains("open") ? s.getInt("open") : 27, dist = s.getInt("dist");
        boolean near = dist <= 8;
        // it, in 3D, with its gear round it
        UiStyle.heading(g, font, "Gear", x, y, 76);
        int gy = y + 13;
        for (int k = 0; k < 4; k++) slot(g, s, new Slot('e', k, GEAR[k], x, gy + k * 19), false, GEAR_TIP[k].toLowerCase(), mx, my);
        PlayerAssistantEntity a = PaClient.mine();
        int mxl = x + 21, myt = gy, mw = 54, mh = 75;
        g.fill(mxl, myt, mxl + mw, myt + mh, 0xFF1C3438);
        if (a != null) {
            drawingPortrait = true;
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, mxl, myt, mxl + mw, myt + mh, 30, 0.0625F, mx, my, a);
            } finally {
                drawingPortrait = false;
            }
        }
        slot(g, s, new Slot('e', 4, GEAR[4], mxl + 8, myt + mh + 3), false, "hand", mx, my);
        slot(g, s, new Slot('e', 5, GEAR[5], mxl + 28, myt + mh + 3), false, "off", mx, my);
        int leftH = myt + mh + 24 - y;
        // its pack: rows open with its level
        int bx = x + 84;
        int used = s.getInt("packUsed");
        UiStyle.heading(g, font, "Its pack  " + used + "/" + open + (open < 27 ? "  (row 3 at level 3)" : ""), bx, y, 9 * 18);
        for (int i = 0; i < 27; i++) {
            Slot sl = new Slot('a', i, null, bx + (i % 9) * 18, y + 13 + (i / 9) * 18);
            boolean locked = i >= open;
            slot(g, s, sl, locked, "", mx, my);
            if (locked && in(mx, my, sl.x(), sl.y(), 18, 18)) tipNow = "This row opens at level 3 (it is level " + s.getInt("level") + ")";
        }
        // yours
        int py = y + 13 + 54 + 6;
        UiStyle.heading(g, font, near ? "Your inventory" : "Your inventory  (come within 8 blocks to hand over)", bx, py, Math.max(9 * 18, iw - 84));
        py += 13;
        for (int j = 9; j < 36; j++) slot(g, s, new Slot('p', j, null, bx + ((j - 9) % 9) * 18, py + ((j - 9) / 9) * 18), false, "", mx, my);
        for (int j = 0; j < 9; j++) slot(g, s, new Slot('p', j, null, bx + j * 18, py + 58), false, "", mx, my);
        int midEnd = py + 58 + 18 - y;
        // the quick buttons: beside, or under when narrow
        int cxb = bx + 9 * 18 + 8, bw = com.jrpetty.mcassistant.pa.PaLayout.packButton(w);
        boolean beside = bw > 0;
        int cyb = beside ? y + 13 : y + Math.max(leftH, midEnd) + 6;
        if (!beside) { cxb = x; bw = -bw; }
        String[][] b = {
            {"Equip best", "Puts on the best armour in its pack, its best weapon in hand and a shield in the other"},
            {"Give me everything", "Hands you everything in its pack but its gear and what it keeps (within 8 blocks)"},
            {"Dump junk", "Drops dirt, gravel, rough stone, rotten flesh and spare seeds at its feet (not what it keeps)"},
            {"Sort", "Piles merged and laid out: tools, armour, food, ores, blocks, the rest"},
            {lockMode ? "Keep: click items" : "Keep...", "Click items in its pack to lock them: kept things are never handed over or dumped. Right-click any item does the same"},
            {"Open full pack", "The full pack screen, with shift-click and the rest (or sneak-right-click it)"}};
        String[] op = {"best", "all", "dump", "sort", "", ""};
        for (int k = 0; k < b.length; k++) {
            int bxk = beside ? cxb : cxb + (k % 3) * (bw + 4), byk = beside ? cyb + k * 18 : cyb + (k / 3) * 18;
            final int kk = k;
            boolean enabled = !(k == 1 && !near);
            button(g, b[k][0], bxk, byk, bw, 15, mx, my, enabled, k == 4 && lockMode, b[k][1], () -> {
                if (kk == 4) { lockMode = !lockMode; return; }
                if (kk == 5) { PaClient.send("pack", new CompoundTag()); return; }
                packOp(op[kk], -1, -1, null, null);
            });
        }
        int buttonsEnd = beside ? cyb + b.length * 18 - y : cyb + 36 - y;
        int infoY = y + Math.max(Math.max(leftH, midEnd), buttonsEnd) + 4;
        g.drawString(font, UiStyle.clip(font, "In its hand: " + s.getString("held") + "   ·   drag between the grids, click to hand over, right-click to keep", iw),
            x, infoY, faint(), false);
        return infoY + 12 - y;
    }

    private void packOp(String op, int i, int j, String e, String extra) {
        CompoundTag t = new CompoundTag();
        t.putString("op", op);
        if (i >= 0) t.putInt("i", i);
        if (j >= 0) t.putInt("j", j);
        if (e != null) t.putString("e", e);
        if (extra != null) t.putString("to", extra);
        PaClient.send("packop", t);
        UiStyle.click();
    }

    private Slot slotAt(double mx, double my) {
        for (Slot sl : slots) if (in(mx, my, sl.x(), sl.y(), 18, 18)) return sl;
        return null;
    }

    /** A press on a slot: right-click keeps it; left starts a drag (a click if let go on the same slot). */
    private boolean pick(double mx, double my, int button) {
        Slot sl = slotAt(mx, my);
        if (sl == null) return false;
        net.minecraft.world.item.ItemStack st = stackOf(sl);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || (lockMode && sl.kind() == 'a')) {
            if (sl.kind() == 'a' && !st.isEmpty()) packOp("lock", sl.i(), -1, null, null);
            return true;
        }
        if (st.isEmpty()) return true;
        drag = sl;
        dragStack = st.copy();
        return true;
    }

    /** Let go: a move between grids (or onto its gear), or a click on the slot it started on. */
    private void drop(double mx, double my, int button) {
        Slot from = drag, to = slotAt(mx, my);
        drag = null;
        dragStack = net.minecraft.world.item.ItemStack.EMPTY;
        if (from == null) return;
        boolean same = to != null && to.kind() == from.kind() && to.i() == from.i() && java.util.Objects.equals(to.e(), from.e());
        if (to == null) return;
        if (same) {                                                  // a click: over to the other side
            switch (from.kind()) {
                case 'a' -> packOp("take", from.i(), -1, null, null);
                case 'p' -> packOp("give", -1, from.i(), null, null);
                default -> packOp("unequip", -1, -1, from.e(), null);
            }
            return;
        }
        int open = PaClient.state.contains("open") ? PaClient.state.getInt("open") : 27;
        switch (from.kind() + "" + to.kind()) {
            case "ap" -> packOp("take", from.i(), -1, null, null);
            case "pa" -> { if (to.i() < open) packOp("give", to.i(), from.i(), null, null); }
            case "aa" -> { if (to.i() < open) { CompoundTag t = new CompoundTag(); t.putString("op", "move"); t.putInt("i", from.i()); t.putInt("k", to.i()); PaClient.send("packop", t); } }
            case "ae" -> packOp("equip", from.i(), -1, to.e(), null);
            case "pe" -> packOp("equip", -1, from.i(), to.e(), null);
            case "ea" -> packOp("unequip", -1, -1, from.e(), null);
            case "ep" -> packOp("unequip", -1, -1, from.e(), "you");
            default -> { }
        }
    }

    // ------------------------------------------------------------------ [pa33c-2b] the Stats tab

    private int stats(GuiGraphics g, CompoundTag s, int x, int y, int iw, int mx, int my) {
        int half = iw >= 360 ? iw / 2 - 6 : iw, cy = y;
        UiStyle.heading(g, font, s.getString("name") + ", level " + s.getInt("level"), x, cy, half);
        cy += 14;
        int xp = s.getInt("xp"), lo = s.getInt("xpHere"), hi = Math.max(lo + 1, s.getInt("xpNext"));
        float part = Math.max(0, Math.min(1, (xp - lo) / (float) (hi - lo)));
        int bw = Math.min(half, 200);
        g.fill(x, cy, x + bw, cy + 7, 0xFFB9C7C4);
        g.fill(x, cy, x + Math.round(bw * part), cy + 7, BRAND_LIGHT);
        if (in(mx, my, x, cy, bw, 7)) tipNow = xp + " xp; level " + (s.getInt("level") + 1) + " at " + hi;
        cy += 10;
        cy = line(g, xp + " xp  ·  " + (hi - xp) + " to the next level  ·  " + s.getInt("done") + " jobs done for you", x, cy, half);
        PlayerAssistantEntity a = PaClient.mine();
        float hp = a != null ? a.getHealth() : s.getFloat("hp"), max = a != null ? a.getMaxHealth() : Math.max(1, s.getFloat("maxHp"));
        int food = a != null ? a.food() : s.getInt("hunger");
        g.drawString(font, "Health", x, cy + 1, muted(), false);
        PaHud.hearts(g, x + 50 + 81, cy, hp, max);
        g.drawString(font, (int) Math.ceil(hp) + "/" + (int) max, x + 140, cy + 1, UiStyle.TEXT, false);
        cy += 12;
        g.drawString(font, "Food", x, cy + 1, muted(), false);
        PaHud.food(g, x + 50 + 81, cy, food);
        g.drawString(font, food + "/20", x + 140, cy + 1, UiStyle.TEXT, false);
        cy += 14;
        cy = line(g, "Hearts grow with its level, from 3 to 10. Food in its pack: " + s.getInt("food") + ".", x, cy, half);
        String where = s.getString("dim").replace("_", " ") + ", " + s.getInt("dist") + " blocks from you";
        cy = line(g, "Where: " + where + "  ·  " + s.getString("mode"), x, cy, half);
        if (!s.getString("bed").isEmpty()) cy = line(g, "Its bed: " + s.getString("bed"), x, cy, half);
        if (!s.getString("home").isEmpty()) cy = line(g, "Its home: " + s.getString("home"), x, cy, half);
        int leftEnd = cy;
        int sx = half == iw ? x : x + half + 12;
        cy = half == iw ? cy + 6 : y;
        UiStyle.heading(g, font, "Its record", sx, cy, half);
        cy += 14;
        ListTag deeds = s.getList("deeds", Tag.TAG_STRING);
        if (deeds.isEmpty()) cy = line(g, "Nothing yet: give it a job.", sx, cy, half);
        for (Tag t : deeds) cy = line(g, "• " + t.getAsString(), sx, cy, half);
        if (!s.getString("today").isEmpty()) cy = line(g, "Today: " + s.getString("today"), sx, cy, half);
        if (!s.getString("death").isEmpty()) cy = line(g, s.getString("death"), sx, cy, half);
        return Math.max(leftEnd, cy) - y + 4;
    }

    // ------------------------------------------------------------------ about and settings

    // ------------------------------------------------------------------ [pa33c-2b] settings, in sections; stats

    static final String[] SECTIONS = {"Personality", "Voice", "Behaviour", "Look", "Keys", "Advanced"};
    private static final String[] SECTION_TIPS = {"Its humour, mood and manner, its name", "How it sounds and how it hears you",
        "What it does on its own, how much it talks, its language", "Its look, and this panel's size and colours", "Talk mode, every key, pings",
        "Start over, its bed and home, dismiss"};
    private int section;
    private boolean confirmReset;

    /** The section list down the left, kept in view while the section scrolls. */
    private void sections(GuiGraphics g, int x, int y, int sw, int sh, int mx, int my) {
        UiStyle.well(g, x, y, sw, sh, UiStyle.PAGE);
        int cy = y + 4;
        for (int i = 0; i < SECTIONS.length; i++) {
            boolean on = section == i, over = in(mx, my, x + 2, cy, sw - 4, 16);
            g.fill(x + 2, cy, x + sw - 2, cy + 16, on ? BRAND : over ? BRAND_WASH : 0x00000000);
            if (on) g.fill(x + 2, cy, x + 4, cy + 16, BRAND_LIGHT);
            g.drawString(font, UiStyle.clip(font, SECTIONS[i], sw - 14), x + 8, cy + 4, on ? 0xFFFFFFFF : UiStyle.TEXT, false);
            final int k = i;
            zone(x + 2, cy, sw - 4, 16, () -> { section = k; scroll = 0; confirmDismiss = false; confirmReset = false; }, SECTION_TIPS[i]);
            cy += 18;
        }
    }

    private int settings(GuiGraphics g, CompoundTag s, int x, int y, int iw, int mx, int my) {
        CompoundTag set = s.getCompound("set");
        int half = Math.min(iw, com.jrpetty.mcassistant.pa.PaLayout.settingsW(w)), sx = x, cy = y;
        UiStyle.heading(g, font, SECTIONS[section], x, cy, half);
        cy += 14;
        switch (section) {
            case 0 -> cy = secPersonality(g, s, x, cy, half, mx, my);
            case 1 -> cy = secVoice(g, s, sx, cy, half, mx, my);
            case 2 -> cy = secBehaviour(g, s, set, sx, cy, half, mx, my);
            case 3 -> cy = secLook(g, s, sx, cy, half, mx, my);
            case 4 -> cy = secKeys(g, sx, cy, half, mx, my);
            default -> cy = secAdvanced(g, s, sx, cy, half, mx, my);
        }
        return cy - y + 4;
    }

    private int secPersonality(GuiGraphics g, CompoundTag s, int x, int cy, int half, int mx, int my) {
        // [pa33b] personality: the triangle, its presets, its region and a sample line
        triangle.sync(s.getCompound("set"));
        int tsize = Math.max(80, Math.min(130, half - font.width("Professional") - 26));
        int tb = triangle.draw(g, font, x + 4, cy, tsize, mx, my);
        int px = x + tsize + 14, pyy = cy + 6, pw = font.width("Professional") + 10;
        for (String pr : com.jrpetty.mcassistant.pa.PaPersona.PRESETS) {
            if (px + pw > x + half + 2) break;
            final String name = pr;
            button(g, pr, px, pyy, pw, 13, mx, my, true, false, "Snap the dot to " + pr, () -> triangle.preset(name));
            pyy += 15;
        }
        cy = Math.max(tb, pyy) + 2;
        int lk = Math.floorMod(s.getInt("look"), PaRenderer.LOOK_NAMES.length);
        g.drawString(font, "Look", x, cy + 3, muted(), false);
        button(g, PaRenderer.LOOK_NAMES[lk] + "  \u203A", x + 70, cy, 90, 13, mx, my, true, false, "Choose its look (Look tab)", () -> { tab = "look"; scroll = 0; });
        cy += 16;
        g.drawString(font, "Name", x, cy + 3, muted(), false);
        nameBox.visible = true;
        nameBox.setX(x + 70);
        nameBox.setY(cy);
        nameBox.setWidth(Math.min(90, half - 120));
        nameBox.render(g, mx, my, 0);
        button(g, "Rename", x + 74 + nameBox.getWidth(), cy, 46, 14, mx, my, !nameBox.getValue().isBlank(), false, "", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("name", nameBox.getValue());
            PaClient.send("look", a);
        });
        cy += 18;
        return cy;
    }

    private int secVoice(GuiGraphics g, CompoundTag s, int sx, int cy, int half, int mx, int my) {
        boolean speak = PaClientConfig.get(PaClientConfig.SPEAK, true);
        cy = toggle(g, "Speak replies aloud", speak, sx, cy, half, mx, my, PaVoice.tts().status(), () -> PaClientConfig.set(PaClientConfig.SPEAK, !speak));
        List<String> voices = PaVoice.tts().voices();
        int vi = PaClientConfig.get(PaClientConfig.VOICE, 0);
        String vname = voices.isEmpty() || vi <= 0 ? "Default" : voices.get(Math.min(vi, voices.size() - 1));
        // [pa33b] the accent it listens for and speaks in: English (UK), English (US), or Auto
        String acc = PaClientConfig.get(PaClientConfig.ACCENT, "auto");
        com.jrpetty.mcassistant.pa.voice.PaAccent accNow = PaVoice.accent();
        g.drawString(font, "Accent", sx, cy + 3, muted(), false);
        button(g, UiStyle.clip(font, com.jrpetty.mcassistant.pa.voice.PaAccent.settingLabel(acc, accNow), 90), sx + 60, cy, 96, 13, mx, my, true, false,
            "Which English it listens for and speaks: English (UK), English (US), or Auto (your game's language, then your computer's)",
            () -> { PaClientConfig.set(PaClientConfig.ACCENT, com.jrpetty.mcassistant.pa.voice.PaAccent.next(acc)); PaVoice.speak("Hello, this is my voice."); });
        cy += 15;
        // [pa33b] the voice picker: every voice, grouped by engine (the most natural first), each with a preview
        String vkey = PaClientConfig.get(PaClientConfig.VOICEKEY, "");
        List<com.jrpetty.mcassistant.pa.voice.PaTts.VoiceInfo> cat = PaVoice.tts().catalogue();
        String cur = vkey.isEmpty() ? "Auto: " + PaVoice.tts().voiceInUse(0) : vkey.substring(vkey.indexOf(':') + 1);
        g.drawString(font, "Voice", sx, cy + 3, muted(), false);
        button(g, UiStyle.clip(font, cur + (voicesOpen ? "  \u25B4" : "  \u25BE"), 90), sx + 60, cy, 96, 13, mx, my, true, false,
            "Choose its voice (the list shows every voice on your computer)", () -> voicesOpen = !voicesOpen);
        cy += 15;
        String sug = PaVoice.suggestFor(Math.floorMod(s.getInt("look"), PaRenderer.LOOK_NAMES.length), cat);
        if (sug != null) { g.drawString(font, UiStyle.clip(font, "Suggested for this look: " + sug, half), sx, cy, faint(), false); cy += 11; }
        if (voicesOpen) {
            String group = "";
            button(g, "Auto (most natural)", sx, cy, half - 22, 12, mx, my, true, vkey.isEmpty(), "", () -> PaClientConfig.set(PaClientConfig.VOICEKEY, ""));
            cy += 14;
            for (com.jrpetty.mcassistant.pa.voice.PaTts.VoiceInfo v : cat) {
                if (!v.group().equals(group)) { group = v.group(); g.drawString(font, group, sx, cy + 1, BRAND, false); cy += 11; }
                final String k = v.key();
                button(g, UiStyle.clip(font, v.label(), half - 30), sx, cy, half - 22, 12, mx, my, true, k.equals(vkey), "", () -> PaClientConfig.set(PaClientConfig.VOICEKEY, k));
                button(g, "\u25B6", sx + half - 20, cy, 14, 12, mx, my, true, false, "Preview", () -> {
                    PaClientConfig.set(PaClientConfig.VOICEKEY, k);
                    PaVoice.speak(PaVoice.sample(triangle.tri()), triangle.tri().gloomy(), triangle.tri().funny());
                });
                cy += 14;
            }
            if (cat.isEmpty()) cy = para(g, PaVoice.tts().status(), sx, cy, half, faint());
            // [pa33b] the natural voices (Kokoro): offered, explained, and fetched only once the player agrees
            if (!PaVoice.tts().kokoroReady()) {
                if (!voicesAsk) {
                    button(g, PaVoice.installingVoices ? "Downloading..." : "Get natural British voices...", sx, cy, half - 22, 13, mx, my, !PaVoice.installingVoices, false,
                        "George, Lewis, Daniel, Fable, Emma, Isabella, Alice, Lily: very human, offline", () -> voicesAsk = true);
                    cy += 16;
                } else {
                    cy = para(g, "Natural voices (Kokoro, Apache-2.0) run on your computer, offline. This downloads about "
                        + com.jrpetty.mcassistant.pa.voice.PaKokoro.totalMb() + " MB once into your config folder, from GitHub and Maven Central: the voice model and voices (Apache-2.0) and ONNX Runtime (MIT). "
                        + "They also need the free program eSpeak NG on your computer to read words. Nothing you say is sent anywhere.", sx, cy, half, faint());
                    button(g, "I agree: download", sx, cy, 100, 14, mx, my, true, true, "", () -> { voicesAsk = false; PaVoice.installVoices(); });
                    button(g, "Not now", sx + 106, cy, 60, 14, mx, my, true, false, "", () -> voicesAsk = false);
                    cy += 18;
                }
            }
            cy = para(g, "More natural voices: on Windows add voices in Settings > Time & language > Speech; on a Mac, System Settings > Accessibility > Spoken Content > System voice > Manage Voices (pick an Enhanced or Premium one).",
                sx, cy, half, faint());
        }
        int rate = PaClientConfig.get(PaClientConfig.RATE, 0), vol = PaClientConfig.get(PaClientConfig.VOLUME, 80), pitch = PaClientConfig.get(PaClientConfig.PITCH, 0);
        cy = stepper(g, "Speed", rate + "", sx, cy, mx, my, () -> PaClientConfig.set(PaClientConfig.RATE, Math.max(-5, rate - 1)),
            () -> PaClientConfig.set(PaClientConfig.RATE, Math.min(5, rate + 1)));
        cy = stepper(g, "Pitch", pitch + "", sx, cy, mx, my, () -> PaClientConfig.set(PaClientConfig.PITCH, Math.max(-5, pitch - 1)),
            () -> PaClientConfig.set(PaClientConfig.PITCH, Math.min(5, pitch + 1)));
        cy = stepper(g, "Volume", vol + "%", sx, cy, mx, my, () -> PaClientConfig.set(PaClientConfig.VOLUME, Math.max(0, vol - 10)),
            () -> PaClientConfig.set(PaClientConfig.VOLUME, Math.min(100, vol + 10)));
        boolean listen = PaClientConfig.get(PaClientConfig.LISTEN, false);
        cy = toggle(g, "Listen (hold " + PaClient.TALK.getTranslatedKeyMessage().getString() + " to talk)", listen, sx, cy, half, mx, my, PaVoice.stt().status(),
            () -> PaClientConfig.set(PaClientConfig.LISTEN, !listen));
        if (listen && !PaVoice.stt().ready()) {
            boolean agreed = PaClientConfig.get(PaClientConfig.AGREED, false);
            cy = para(g, "Listening needs the offline speech model (Vosk, about 40 MB), downloaded once into your config folder. Only the words you say go to the server.",
                sx, cy, half, faint());
            button(g, PaVoice.installing ? "Downloading..." : agreed ? "Download again" : "I agree: download it", sx, cy, 120, 14, mx, my, !PaVoice.installing, true, "",
                () -> { PaClientConfig.set(PaClientConfig.AGREED, true); PaVoice.install(); });
            cy += 18;
        }
        return cy;
    }

    private int secBehaviour(GuiGraphics g, CompoundTag s, CompoundTag set, int sx, int cy, int half, int mx, int my) {
        boolean hud = PaClientConfig.get(PaClientConfig.HUD, true);
        cy = toggle(g, "Show what it's doing (HUD)", hud, sx, cy, half, mx, my, "", () -> PaClientConfig.set(PaClientConfig.HUD, !hud));
        cy = toggle(g, "Auto-help", set.getBoolean("auto"), sx, cy, half, mx, my,
            "Keeps the furnace going, the farm picked, the chests sorted, you fed and safe; it says what it's doing and you can veto it", () -> setting("auto", !set.getBoolean("auto")));
        cy = toggle(g, "Ask before big jobs", set.getBoolean("confirm"), sx, cy, half, mx, my, "", () -> setting("confirm", !set.getBoolean("confirm")));
        cy = toggle(g, "Use your chests", set.getBoolean("chests"), sx, cy, half, mx, my,
            "Off: only chests you point it at. Never a town's stores or anyone else's.", () -> setting("chests", !set.getBoolean("chests")));
        cy = toggle(g, "Break near your builds", set.getBoolean("break"), sx, cy, half, mx, my,
            "Off: it fells only trees and never takes laid cobblestone. On: any logs or stone will do.", () -> setting("break", !set.getBoolean("break")));
        cy = toggle(g, "Shelter at night", set.getBoolean("sleep"), sx, cy, half, mx, my, "", () -> setting("sleep", !set.getBoolean("sleep")));
        int roam = set.getInt("roam");
        cy = stepper(g, "Roam", roam + " blocks", sx, cy, mx, my, () -> settingInt("roam", roam - 8), () -> settingInt("roam", roam + 8));
        String[] chat = {"Quiet", "Low", "Normal", "Chatty", "Won't shut up"};
        int ch = Math.max(0, Math.min(4, set.getInt("chatty")));
        g.drawString(font, "Chattiness", sx, cy + 3, muted(), false);
        button(g, chat[ch], sx + 60, cy, 76, 13, mx, my, true, false,
            ch == 4 && triangle.tri().funny() >= 0.8 ? "Joke Machine: a remark on everything you do, every 10 to 20 seconds" : "How much it says unasked (it always answers you)",
            () -> settingInt("chatty", (ch + 1) % 5));
        cy += 15;
        // [pa33b] hushed or not: Talking, No jokes (plain words), Quiet (answers and warnings only); kept until lifted
        String[] hush = {"Talking", "No jokes", "Quiet"};
        int mu = Math.max(0, Math.min(2, set.getInt("mute")));
        g.drawString(font, "Jokes", sx, cy + 3, muted(), false);
        button(g, (mu > 0 ? "\u00D7 " : "") + hush[mu], sx + 60, cy, 76, 13, mx, my, true, mu > 0,
            mu == 0 ? "Say \"no more jokes\" or \"shut up\" any time; this does the same" : "Say \"you can talk again\" or click to go back to Talking",
            () -> settingInt("mute", (mu + 1) % 3));
        cy += 15;
        // [pa33b] match my energy: how strong its language back may be (never past the server's cap), and other players
        int lang = Math.max(0, Math.min(2, set.getInt("language"))), cap = set.contains("langCap") ? set.getInt("langCap") : 1;
        g.drawString(font, "Language", sx, cy + 3, muted(), false);
        button(g, com.jrpetty.mcassistant.pa.PaTone.LANGUAGE[lang] + (lang > cap ? " *" : ""), sx + 60, cy, 76, 13, mx, my, true, false,
            lang > cap ? "This server allows up to " + com.jrpetty.mcassistant.pa.PaTone.LANGUAGE[cap] + ", so it talks " + com.jrpetty.mcassistant.pa.PaTone.LANGUAGE[cap] + " here"
                : "When you're rude to it, it answers in kind: Clean never swears, Cheeky is mild (bloody, damn, arse), Unfiltered swears back",
            () -> settingInt("language", (lang + 1) % 3));
        cy += 15;
        if (lang > cap) { g.drawString(font, UiStyle.clip(font, "* This server allows up to " + com.jrpetty.mcassistant.pa.PaTone.LANGUAGE[cap] + ".", half), sx, cy, faint(), false); cy += 11; }
        int rb = Math.max(0, Math.min(2, set.getInt("roastBack")));
        g.drawString(font, "Others", sx, cy + 3, muted(), false);
        button(g, UiStyle.clip(font, "Roast back: " + com.jrpetty.mcassistant.pa.PaStrangers.ROAST_BACK[rb], 110), sx + 60, cy, 112, 13, mx, my, true, false,
            set.getBoolean("roastAllowed") || !set.contains("roastAllowed") ? "Other players who roast it (or you) get it back, by name. It never starts it."
                : "This server has turned roasting other players off", () -> settingInt("roastBack", (rb + 1) % 3));
        cy += 15;
        cy = toggle(g, "Defend me", !set.contains("defend") || set.getBoolean("defend"), sx, cy, half, mx, my,
            "When someone else says you're rubbish, it sticks up for you", () -> setting("defend", !(!set.contains("defend") || set.getBoolean("defend"))));
        int spend = set.getInt("spend");
        cy = stepper(g, "Spend limit", spend + " coins", sx, cy, mx, my, () -> settingInt("spend", spend - 16), () -> settingInt("spend", spend + 16));
        return cy;
    }

    private int secLook(GuiGraphics g, CompoundTag s, int sx, int cy, int half, int mx, int my) {
        int lk = Math.floorMod(s.getInt("look"), PaRenderer.LOOK_NAMES.length);
        g.drawString(font, "Its look", sx, cy + 3, muted(), false);
        button(g, PaRenderer.LOOK_NAMES[lk] + "  ›", sx + 72, cy, 100, 13, mx, my, true, false, "Choose its outfit (the Look tab, with a preview)",
            () -> { tab = "look"; scroll = 0; PaClientConfig.set(PaClientConfig.LASTTAB, "look"); });
        cy += 18;
        UiStyle.heading(g, font, "This panel", sx, cy, half);
        cy += 14;
        String size = PaClientConfig.get(PaClientConfig.UISCALE, "m");
        g.drawString(font, "Panel size", sx, cy + 3, muted(), false);
        String[] sizes = {"s", "m", "l"}, names = {"Small", "Medium", "Large"};
        for (int k = 0; k < 3; k++) {
            final String v = sizes[k];
            button(g, names[k], sx + 72 + k * 54, cy, 50, 13, mx, my, true, size.equals(v), "The panel's size on screen (the game's GUI scale still applies)",
                () -> { PaClientConfig.set(PaClientConfig.UISCALE, v); init(minecraft, width, height); });
        }
        cy += 16;
        boolean hc = PaClientConfig.get(PaClientConfig.CONTRAST, false);
        cy = toggle(g, "High contrast text", hc, sx, cy, half, mx, my, "Darker, stronger text on the panel", () -> PaClientConfig.set(PaClientConfig.CONTRAST, !hc));
        boolean sub = PaClientConfig.get(PaClientConfig.SUBTITLES, false);
        cy = toggle(g, "Subtitles always", sub, sx, cy, half, mx, my, "What it says shows as a subtitle even when it is in sight (and its voice is off or quiet)",
            () -> PaClientConfig.set(PaClientConfig.SUBTITLES, !sub));
        cy = para(g, "Pings use a shape for each kind as well as a colour (move: circle, attack: diamond, gather: square, danger: triangle, build: hexagon), so colour is never the only clue.",
            sx, cy + 2, half, faint());
        return cy;
    }

    private int secAdvanced(GuiGraphics g, CompoundTag s, int sx, int cy, int half, int mx, int my) {
        UiStyle.heading(g, font, "Its bed and home", sx, cy, half);
        cy += 14;
        cy = line(g, "Bed: " + (s.getString("bed").isEmpty() ? "none (it comes back by its spawner, or you)" : s.getString("bed")), sx, cy, half);
        cy = line(g, "Home: " + (s.getString("home").isEmpty() ? "none" : s.getString("home")), sx, cy, half);
        int bw = com.jrpetty.mcassistant.pa.PaLayout.halfButton(half);
        button(g, "Use the bed I look at", sx, cy, bw, 14, mx, my, true, false, "Look at a bed first; it comes back there after a fall",
            () -> { onClose(); PaClient.talk("use this bed", false); });
        button(g, "Clear its bed", sx + bw + 4, cy, bw, 14, mx, my, !s.getString("bed").isEmpty(), false, "It forgets its bed (it comes back by its spawner, or you)",
            () -> { CompoundTag t = new CompoundTag(); t.putBoolean("clearBed", true); PaClient.send("settings", t); });
        cy += 18;
        button(g, "Set home here", sx, cy, bw, 14, mx, my, true, false, "Where you stand becomes its home (it goes there to shelter and sort)",
            () -> PaClient.talk("set home here", false));
        cy += 20;
        UiStyle.heading(g, font, "Start over", sx, cy, half);
        cy += 14;
        button(g, "Replay the tour", sx, cy, bw, 14, mx, my, true, false, "The quick tour of this panel", this::startTour);
        if (!confirmReset) button(g, "Reset settings...", sx + bw + 4, cy, bw, 14, mx, my, true, false, "Every setting back as it came (its name, look, pack and level stay)",
            () -> confirmReset = true);
        else button(g, "Really reset", sx + bw + 4, cy, bw, 14, mx, my, true, true, "Its settings go back to how they came",
            () -> { CompoundTag t = new CompoundTag(); t.putBoolean("reset", true); PaClient.send("settings", t); confirmReset = false; });
        cy += 20;
        UiStyle.heading(g, font, "Dismiss", sx, cy, half);
        cy += 14;
        cy = para(g, "Send " + s.getString("name") + " away for good. It gives back everything it carries first. This can't be undone.", sx, cy, half, faint());
        if (!confirmDismiss) button(g, "Dismiss...", sx, cy, bw, 14, mx, my, true, false, "You'll be asked again", () -> confirmDismiss = true);
        else {
            button(g, "Yes, dismiss", sx, cy, bw, 14, mx, my, true, true, "This can't be undone",
                () -> { PaClient.send("dismiss", new CompoundTag()); confirmDismiss = false; });
            button(g, "Keep them", sx + bw + 4, cy, bw, 14, mx, my, true, false, "", () -> confirmDismiss = false);
        }
        cy += 20;
        return cy;
    }

    private int secKeys(GuiGraphics g, int sx, int cy, int half, int mx, int my) {
        // [pa33c-2b] talk mode and the keys (live bindings; Controls to change them)
        String tm = PaClientConfig.get(PaClientConfig.TALKMODE, "hold");
        com.jrpetty.mcassistant.pa.PaTalkMode.Mode mode = com.jrpetty.mcassistant.pa.PaTalkMode.of(tm);
        g.drawString(font, "Talk mode", sx, cy + 3, muted(), false);
        button(g, UiStyle.clip(font, com.jrpetty.mcassistant.pa.PaTalkMode.label(mode), 110), sx + 60, cy, 112, 13, mx, my, true, false,
            "Hold the talk key to speak, press once to start and again to send, or voice activation (always listening; sends after a pause)",
            () -> PaClientConfig.set(PaClientConfig.TALKMODE, mode == com.jrpetty.mcassistant.pa.PaTalkMode.Mode.HOLD ? "toggle"
                : mode == com.jrpetty.mcassistant.pa.PaTalkMode.Mode.TOGGLE ? "voice" : "hold"));
        cy += 15;
        if (mode == com.jrpetty.mcassistant.pa.PaTalkMode.Mode.VOICE) {
            int sens = PaClientConfig.get(PaClientConfig.VASENS, 50);
            cy = stepper(g, "Sensitivity", sens + "%", sx, cy, mx, my, () -> PaClientConfig.set(PaClientConfig.VASENS, Math.max(0, sens - 10)),
                () -> PaClientConfig.set(PaClientConfig.VASENS, Math.min(100, sens + 10)));
        }
        net.minecraft.client.KeyMapping[] keys = {PaClient.TALK, PaClient.PANEL, PaClient.PING, PaClient.WHEEL, PaClient.QUIET, PaClient.COME, PaClient.CANCEL_PING};
        for (net.minecraft.client.KeyMapping k : keys) {
            String what = net.minecraft.network.chat.Component.translatable(k.getName()).getString().replace("Player AI Assistant: ", "");
            String bound = k.isUnbound() ? "not set" : k.getTranslatedKeyMessage().getString();
            g.drawString(font, UiStyle.clip(font, what, half - 70), sx, cy + 2, muted(), false);
            g.drawString(font, bound, sx + half - 8 - font.width(bound), cy + 2, k.isUnbound() ? faint() : UiStyle.TEXT, false);
            cy += 11;
        }
        button(g, "Change keys...", sx, cy + 2, 100, 14, mx, my, true, false, "Opens Controls: the assistant's keys are under \"Player AI Assistant\"",
            () -> minecraft.setScreen(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(this, minecraft.options)));
        cy += 20;
        // [pa33c-2b] pings: what the middle button does, sharing, quick pings, voice pings
        UiStyle.heading(g, font, "Pings", sx, cy + 4, half);
        cy += 18;
        String pm = PaClientConfig.get(PaClientConfig.PINGMODE, "both");
        g.drawString(font, "Middle button", sx, cy + 3, muted(), false);
        button(g, pm.equals("ping") ? "Ping only" : pm.equals("pick") ? "Pick block only" : "Ping (sneak: pick block)", sx + 72, cy, Math.max(100, half - 80), 13, mx, my, true, false,
            "What the middle mouse button does when the ping key is on it: ping (pick block while sneaking), ping only, or vanilla pick block only",
            () -> PaClientConfig.set(PaClientConfig.PINGMODE, pm.equals("both") ? "ping" : pm.equals("ping") ? "pick" : "both"));
        cy += 15;
        cy = toggle(g, "Act on quick pings", PaClientConfig.get(PaClientConfig.ACTQUICK, false), sx, cy, half, mx, my,
            "Off: a tap is only a \"look here\" marker until you pick from the wheel, tap again or say an order. On: a tap does the first option at once",
            () -> PaClientConfig.set(PaClientConfig.ACTQUICK, !PaClientConfig.get(PaClientConfig.ACTQUICK, false)));
        cy = toggle(g, "Show my pings to players near by", PaClientConfig.get(PaClientConfig.SHAREPINGS, false), sx, cy, half, mx, my,
            "Your plain markers appear for players within 128 blocks. They never order anyone's assistant",
            () -> PaClientConfig.set(PaClientConfig.SHAREPINGS, !PaClientConfig.get(PaClientConfig.SHAREPINGS, false)));
        cy = toggle(g, "Ping what I look at while talking", PaClientConfig.get(PaClientConfig.VOICEPING, true), sx, cy, half, mx, my,
            "Speaking marks what you look at as you start, and each thing you look at after, so \"chop that, and that\" means those",
            () -> PaClientConfig.set(PaClientConfig.VOICEPING, !PaClientConfig.get(PaClientConfig.VOICEPING, true)));
        return cy;
    }

    private void centred(GuiGraphics g, String text, int cx, int y, int colour) {
        g.drawString(font, text, cx - font.width(text) / 2, y, colour, false);
    }

    private int line(GuiGraphics g, String text, int x, int y, int w) {
        return para(g, text, x, y, w, UiStyle.TEXT);
    }

    private int toggle(GuiGraphics g, String label, boolean on, int x, int y, int w, int mx, int my, String tip, Runnable flip) {
        g.drawString(font, UiStyle.clip(font, label, w - 44), x, y + 3, UiStyle.TEXT, false);
        int bx = x + w - 38;
        boolean over = in(mx, my, bx, y, 36, 13);
        g.fill(bx, y, bx + 36, y + 13, on ? (over ? BRAND_LIGHT : BRAND) : (over ? 0xFFCFCFCF : 0xFFB5B5B5));
        g.fill(on ? bx + 22 : bx + 2, y + 2, on ? bx + 34 : bx + 14, y + 11, 0xFFFFFFFF);
        g.drawString(font, on ? "On" : "Off", on ? bx + 5 : bx + 17, y + 3, on ? 0xFFFFFFFF : muted(), false);
        zone(bx, y, 36, 13, flip, tip);
        if (!tip.isEmpty() && in(mx, my, x, y, w - 40, 13)) tipNow = tip;
        return y + 15;
    }

    private int stepper(GuiGraphics g, String label, String value, int x, int y, int mx, int my, Runnable down, Runnable up) {
        g.drawString(font, label, x, y + 3, muted(), false);
        button(g, "-", x + 60, y, 13, 13, mx, my, true, false, "", down);
        centred(g, value, x + 104, y + 3, UiStyle.TEXT);
        button(g, "+", x + 135, y, 13, 13, mx, my, true, false, "", up);
        return y + 15;
    }

    private void setting(String key, boolean v) {
        CompoundTag a = new CompoundTag();
        a.putBoolean(key, v);
        PaClient.send("settings", a);
    }

    // ------------------------------------------------------------------ [pa33b] the Look tab

    private int lookPrevX, lookPrevY, lookPrevW, lookPrevH;
    private boolean voicesAsk;   // [pa33b] the natural-voices consent showing
    private boolean voicesOpen;

    /** A big preview to turn with the mouse, and every look to pick (instant and free). */
    private int looks(GuiGraphics g, CompoundTag s, int x, int y, int iw, int mx, int my) {
        int lk = Math.floorMod(s.getInt("look"), PaRenderer.LOOK_NAMES.length);
        int pw = Math.min(110, iw / 3), ph = Math.max(110, Math.min(150, pw + 40));
        lookPrevX = x; lookPrevY = y; lookPrevW = pw; lookPrevH = ph;
        g.fill(x, y, x + pw, y + ph, 0xFF0E2A2E);
        PlayerAssistantEntity a = PaClient.mine();
        if (a != null) {
            drawingPortrait = true;
            try {
                float oy = a.yBodyRot, oyh = a.yHeadRot, oyr = a.getYRot(), ox = a.getXRot();
                a.yBodyRot = 180 + lookYaw; a.setYRot(180 + lookYaw); a.yHeadRot = a.getYRot(); a.yHeadRotO = a.yHeadRot; a.setXRot(0);
                float tall = lk == 17 ? 0.6F : 1F;                  // the enderman is a good deal taller
                InventoryScreen.renderEntityInInventory(g, x + pw / 2F, y + ph - 12, Math.min(pw, ph) * 0.42F * tall,
                    new org.joml.Vector3f(), new org.joml.Quaternionf().rotateZ((float) Math.PI), null, a);
                a.yBodyRot = oy; a.yHeadRot = oyh; a.setYRot(oyr); a.setXRot(ox);
            } finally {
                drawingPortrait = false;
            }
        }
        centred(g, PaRenderer.LOOK_NAMES[lk], x + pw / 2, y + ph + 3, UiStyle.TEXT);
        g.drawString(font, UiStyle.clip(font, "Drag to turn", pw), x + (pw - font.width("Drag to turn")) / 2, y + ph + 14, faint(), false);
        int gx = x + pw + 10, gw = iw - pw - 10, cols = Math.max(1, gw / 74), bw = (gw - (cols - 1) * 4) / cols;
        UiStyle.heading(g, font, "Choose a look", gx, y, gw);
        int cy = y + 13;
        for (int i = 0; i < PaRenderer.LOOK_NAMES.length; i++) {
            int col = i % cols, row = i / cols;
            final int which = i;
            button(g, PaRenderer.LOOK_NAMES[i], gx + col * (bw + 4), cy + row * 16, bw, 14, mx, my, true, i == lk,
                PaRenderer.LOOK_BLURBS[i], () -> look("look", which));
        }
        int rows = (PaRenderer.LOOK_NAMES.length + cols - 1) / cols;
        int after = cy + rows * 16 + 4;
        after = para(g, "Free and instant; everyone sees it. Each look brings a few remarks of its own (about one in five).", gx, after, gw, faint());
        return Math.max(after, y + ph + 26);
    }

    private void settingInt(String key, int v) {
        CompoundTag a = new CompoundTag();
        a.putInt(key, v);
        PaClient.send("settings", a);
    }

    private void look(String key, int v) {
        CompoundTag a = new CompoundTag();
        a.putInt(key, v);
        PaClient.send("look", a);
    }

    // ------------------------------------------------------------------ the tour

    private static final String[][] TOUR = {
        {"header", "This is your assistant's panel. Press Y any time to open it, or right-click your assistant."},
        {"input", "Talk to it here in plain words: \"follow me\", \"get me 16 logs\", \"build a small house here\". Typos are fine."},
        {"chips", "Or click a suggestion: they change with what you're looking at and holding, and the time of day."},
        {"orders", "Orders: one click for the common jobs. The ones with choices ask how many and where."},
        {"jobs", "Jobs: what it's doing now and next, with progress. Reorder, cancel or pause any time. \"Stop\" always works at once."},
        {"mic", "Voice: hold Z and speak (turn listening on in Settings, Voice first). Its replies are spoken aloud, and always shown."},
        {"wheel", "Out in the world, look at something and press X: the action wheel offers what fits (mine this vein, use that chest, attack that)."},
    };

    private void tourOverlay(GuiGraphics g, int mx, int my) {
        if (tour >= TOUR.length) { tour = -1; PaClient.send("tour", new CompoundTag()); PaClientConfig.set(PaClientConfig.TOURED, true); return; }
        String where = TOUR[tour][0];
        int hx, hy, hw, hh;
        switch (where) {
            case "header" -> { hx = left; hy = top; hw = w; hh = 27; }
            case "input" -> { hx = left + 6; hy = top + h - 36; hw = w - 12; hh = 20; }
            case "chips" -> { hx = left + 6; hy = top + h - 55; hw = w - 12; hh = 16; }
            case "orders", "jobs" -> {
                int tx = left + 6;
                int idx = where.equals("orders") ? 1 : 2;
                for (int i = 0; i < idx; i++) tx += font.width(TAB_LABELS[i]) + 16;
                hx = tx; hy = top + 29; hw = font.width(TAB_LABELS[idx]) + 14; hh = 14;
            }
            case "mic" -> { hx = left + w - 60; hy = top + h - 36; hw = 54; hh = 20; }
            default -> { hx = left + w / 2 - 40; hy = top + h / 2 - 10; hw = 80; hh = 20; }
        }
        // dim everything but the spot
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        int dim = 0x90000000;
        g.fill(0, 0, width, hy, dim);
        g.fill(0, hy + hh, width, height, dim);
        g.fill(0, hy, hx, hy + hh, dim);
        g.fill(hx + hw, hy, width, hy + hh, dim);
        g.renderOutline(hx - 1, hy - 1, hw + 2, hh + 2, BRAND_LIGHT);
        if (where.equals("wheel")) {
            // no part of the panel to point at: the key itself, as it reads on the keyboard
            g.fill(hx, hy, hx + hw, hy + hh, 0xF0173B40);
            String k = "[" + PaClient.WHEEL.getTranslatedKeyMessage().getString() + "] action wheel";
            g.drawString(font, k, hx + (hw - font.width(k)) / 2, hy + 6, 0xFFFFFFFF, false);
        }
        int bw = Math.min(260, width - 20);
        List<FormattedCharSequence> lines = font.split(Component.literal(TOUR[tour][1]), bw - 16);
        int bh = lines.size() * 10 + 30;
        int bx = Math.max(6, Math.min(width - bw - 6, hx + hw / 2 - bw / 2));
        int byy = hy + hh + 6 + bh < height ? hy + hh + 6 : hy - bh - 6;
        g.fill(bx, byy, bx + bw, byy + bh, 0xF0173B40);
        g.fill(bx, byy, bx + 2, byy + bh, BRAND_LIGHT);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), bx + 8, byy + 6 + i * 10, 0xFFFFFFFF, false);
        String step = (tour + 1) + "/" + TOUR.length;
        g.drawString(font, step, bx + 8, byy + bh - 14, 0xFF9FD8CE, false);
        g.pose().popPose();
        // buttons above the dim
        g.pose().pushPose();
        g.pose().translate(0, 0, 450);
        button(g, tour == TOUR.length - 1 ? "Done" : "Next", bx + bw - 48, byy + bh - 17, 42, 13, mx, my, true, true, "", () -> {
            tour++;
            if (tour < TOUR.length && (TOUR[tour][0].equals("orders") || TOUR[tour][0].equals("jobs") || TOUR[tour][0].equals("wheel"))) tab = "talk";
        });
        button(g, "Skip", bx + bw - 94, byy + bh - 17, 42, 13, mx, my, true, false, "", () -> tour = TOUR.length);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ the small kit

    private interface Body {
        int draw(int y);
    }

    private void scrolled(GuiGraphics g, int by, int bh, int mx, int my, Body body) {
        scrolledIn(g, left + 6, by, w - 12, bh, mx, my, body);
    }

    private void scrolledIn(GuiGraphics g, int wx, int by, int ww, int bh, int mx, int my, Body body) {
        UiStyle.well(g, wx, by, ww, bh, UiStyle.PAGE);
        g.enableScissor(wx + 1, by + 1, wx + ww - 1, by + bh - 1);
        int startZones = zones.size();
        contentH = body.draw(by + 4 - scroll);
        g.disableScissor();
        // zones outside the visible body do nothing
        zones.removeIf(z -> zones.indexOf(z) >= startZones && (z.y() + z.h() <= by || z.y() >= by + bh));
        int max = Math.max(0, contentH - bh + 8);
        if (scroll > max) scroll = max;
        if (max > 0) UiStyle.scrollBar(g, wx + ww - 6, by + 1, bh - 2, scroll, contentH + 8, bh);
    }

    private int para(GuiGraphics g, String text, int x, int y, int w, int colour) {
        for (FormattedCharSequence l : font.split(Component.literal(text), w)) {
            g.drawString(font, l, x, y, colour, false);
            y += 10;
        }
        return y + 2;
    }

    private void button(GuiGraphics g, String label, int x, int y, int bw, int bh, int mx, int my, boolean enabled, boolean primary, String tip, Runnable run) {
        boolean over = enabled && in(mx, my, x, y, bw, bh);
        int fill = !enabled ? 0xFFBDBDBD : primary ? (over ? BRAND_LIGHT : BRAND) : (over ? 0xFFF0F0F0 : 0xFFDCDCDC);
        g.fill(x, y, x + bw, y + bh, fill);
        g.fill(x, y + bh - 1, x + bw, y + bh, primary && enabled ? 0xFF135A50 : 0xFF8E8E8E);
        int ink = !enabled ? UiStyle.TEXT_DISABLED : primary ? 0xFFFFFFFF : UiStyle.TEXT;
        String l = UiStyle.clip(font, label, bw - 4);
        g.drawString(font, l, x + (bw - font.width(l)) / 2, y + (bh - 8) / 2, ink, false);
        if (enabled) zone(x, y, bw, bh, run, tip);
        else if (!tip.isEmpty() && in(mx, my, x, y, bw, bh)) tipNow = tip;
    }

    private void zone(int x, int y, int zw, int zh, Runnable run, String tip) {
        zones.add(new Zone(x, y, zw, zh, run, tip));
    }

    private void tooltip(GuiGraphics g, int mx, int my) {
        String tip = tipNow;
        for (int i = zones.size() - 1; i >= 0 && tip.isEmpty(); i--) {
            Zone z = zones.get(i);
            if (in(mx, my, z.x(), z.y(), z.w(), z.h()) && !z.tip().isEmpty()) tip = z.tip();
        }
        if (!tip.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 500);
            UiStyle.tooltip(g, font, tip, mx, my, 220);
            g.pose().popPose();
        }
    }

    /** [pa33c-2b] Settings, Look: high contrast makes the quiet text as strong as the rest. */
    private static int muted() {
        return PaClientConfig.get(PaClientConfig.CONTRAST, false) ? 0xFF000000 : UiStyle.TEXT_MUTED;
    }

    private static int faint() {
        return PaClientConfig.get(PaClientConfig.CONTRAST, false) ? 0xFF1A1A1A : UiStyle.TEXT_FAINT;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (tab.equals("settings") && section == 0 && triangle.drag(mx, my)) return true;
        if (tab.equals("look") && turning) { lookYaw += (float) dx * 2.5F; return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        turning = false;
        if (drag != null) { drop(mx, my, button); return true; }
        if (triangle.release()) return true;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (tab.equals("settings") && section == 0 && triangle.click(mx, my)) return true;
        if (tab.equals("look") && in(mx, my, lookPrevX, lookPrevY, lookPrevW, lookPrevH)) { turning = true; return true; }
        if (tab.equals("pack") && pick(mx, my, button)) return true;               // [pa33c-2b] a slot: dragged or clicked
        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone z = zones.get(i);
            if (in(mx, my, z.x(), z.y(), z.w(), z.h())) {
                UiStyle.click();
                z.run().run();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (tab.equals("talk")) {
            logScroll = Math.max(0, logScroll - (int) (dy * 20));
            stickBottom = false;
        } else {
            scroll = Math.max(0, scroll - (int) (dy * 20));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (tour >= 0 && key == GLFW.GLFW_KEY_ESCAPE) { tour = TOUR.length; return true; }
        if (input.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { sendTyped(); return true; }
            if (key == GLFW.GLFW_KEY_TAB) {
                List<String> sug = PaUnderstand.complete(input.getValue(), 1);
                if (!sug.isEmpty()) { input.setValue(sug.get(0)); input.moveCursorToEnd(false); }
                return true;
            }
            if (key != GLFW.GLFW_KEY_ESCAPE) return input.keyPressed(key, scan, mods) || true;
        }
        if (nameBox.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) return nameBox.keyPressed(key, scan, mods) || true;
        if (tab.equals("settings") && section == 0 && triangle.key(key)) return true;
        if (PaClient.PANEL.matches(key, scan) && !input.isFocused()) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (input.isFocused()) return input.charTyped(c, mods);
        if (nameBox.isFocused()) return nameBox.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    @Override
    public void tick() {
        // the panel asks for fresh numbers now and then while it is open (progress, health)
        if (minecraft != null && minecraft.level != null && minecraft.level.getGameTime() % 20 == 0) PaClient.send("state", new CompoundTag());
    }
}
